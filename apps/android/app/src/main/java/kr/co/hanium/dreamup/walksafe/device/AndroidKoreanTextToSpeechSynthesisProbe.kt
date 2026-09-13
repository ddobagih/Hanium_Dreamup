package kr.co.hanium.dreamup.walksafe.device

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.Closeable
import java.io.File
import java.util.Locale
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.voice.selectInstalledOfflineKoreanVoice
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection

enum class KoreanTextToSpeechSynthesisProbeResult {
    AVAILABLE,
    INITIALIZATION_FAILED,
    KOREAN_LANGUAGE_UNAVAILABLE,
    OUTPUT_FILE_UNAVAILABLE,
    LISTENER_REGISTRATION_FAILED,
    SYNTHESIS_REJECTED,
    SYNTHESIS_ERROR,
    SYNTHESIS_STOPPED,
    EMPTY_OUTPUT,
    OUTPUT_TOO_SHORT,
    TIMEOUT_SCHEDULING_FAILED,
    TIMED_OUT,
    CANCELLED,
    CLOSED,
    ;

    val available: Boolean
        get() = this == AVAILABLE
}

/**
 * Verifies Korean TTS by selecting a local voice and producing a private temporary audio file.
 * Voice metadata alone never passes: the callback must complete with a structurally complete
 * PCM WAV containing at least one second of audio. This does not verify audible speech.
 */
class AndroidKoreanTextToSpeechSynthesisProbe(
    context: Context,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    onResult: (KoreanTextToSpeechSynthesisProbeResult) -> Unit,
) : Closeable {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val session = KoreanTextToSpeechSynthesisProbeSession(
        engineFactory = KoreanTextToSpeechProbeEngineFactory {
            AndroidKoreanTextToSpeechProbeEngine(appContext)
        },
        outputFileFactory = KoreanTextToSpeechProbeOutputFileFactory {
            File.createTempFile("walksafe-korean-tts-", ".wav", appContext.cacheDir)
        },
        timeoutScheduler = AndroidKoreanTextToSpeechProbeTimeoutScheduler(mainHandler),
        timeoutMs = timeoutMs,
        onResult = { result ->
            if (Looper.myLooper() == Looper.getMainLooper()) {
                onResult(result)
            } else if (!mainHandler.post { onResult(result) }) {
                onResult(result)
            }
        },
    )

    fun start() = session.start()

    fun cancel() = session.cancel()

    override fun close() = session.close()

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
    }
}

internal fun interface KoreanTextToSpeechProbeEngineFactory {
    fun create(): KoreanTextToSpeechProbeEngine
}

internal interface KoreanTextToSpeechProbeEngine : Closeable {
    fun setInitializationListener(listener: (Boolean) -> Unit)

    fun selectKoreanLanguage(): Boolean

    fun setProgressListener(listener: KoreanTextToSpeechProbeProgressListener): Boolean

    fun synthesizeToFile(text: CharSequence, outputFile: File, utteranceId: String): Boolean
}

internal interface KoreanTextToSpeechProbeProgressListener {
    fun onDone(callbackUtteranceId: String?)

    fun onError(callbackUtteranceId: String?)

    fun onStop(callbackUtteranceId: String?)
}

internal fun interface KoreanTextToSpeechProbeOutputFileFactory {
    fun create(): File
}

internal fun interface KoreanTextToSpeechProbeTimeoutCancellation {
    fun cancel()
}

internal fun interface KoreanTextToSpeechProbeTimeoutScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): KoreanTextToSpeechProbeTimeoutCancellation
}

internal class KoreanTextToSpeechSynthesisProbeSession(
    private val engineFactory: KoreanTextToSpeechProbeEngineFactory,
    private val outputFileFactory: KoreanTextToSpeechProbeOutputFileFactory,
    private val timeoutScheduler: KoreanTextToSpeechProbeTimeoutScheduler,
    private val timeoutMs: Long,
    private val onResult: (KoreanTextToSpeechSynthesisProbeResult) -> Unit,
    private val utteranceId: String = "walksafe-korean-tts-${UUID.randomUUID()}",
) : Closeable {
    private val lock = Any()
    private var state = ProbeState.IDLE
    private var engine: KoreanTextToSpeechProbeEngine? = null
    private var outputFile: File? = null
    private var timeoutCancellation: KoreanTextToSpeechProbeTimeoutCancellation? = null

    init {
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
        require(utteranceId.isNotBlank()) { "utteranceId must not be blank" }
    }

    fun start() {
        synchronized(lock) {
            check(state == ProbeState.IDLE) { "Korean TTS synthesis probe may only be started once" }
            state = ProbeState.RUNNING
        }

        val scheduledTimeout = runCatching {
            timeoutScheduler.schedule(timeoutMs) {
                finish(KoreanTextToSpeechSynthesisProbeResult.TIMED_OUT)
            }
        }.getOrElse {
            finish(KoreanTextToSpeechSynthesisProbeResult.TIMEOUT_SCHEDULING_FAILED)
            return
        }
        val timeoutRetained = synchronized(lock) {
            if (state == ProbeState.RUNNING) {
                timeoutCancellation = scheduledTimeout
                true
            } else {
                false
            }
        }
        if (!timeoutRetained) {
            runCatching { scheduledTimeout.cancel() }
            return
        }

        val createdEngine = runCatching { engineFactory.create() }.getOrElse {
            finish(KoreanTextToSpeechSynthesisProbeResult.INITIALIZATION_FAILED)
            return
        }
        val engineRetained = synchronized(lock) {
            if (state == ProbeState.RUNNING) {
                engine = createdEngine
                true
            } else {
                false
            }
        }
        if (!engineRetained) {
            runCatching { createdEngine.close() }
            return
        }

        runCatching {
            createdEngine.setInitializationListener { initialized ->
                onInitialized(createdEngine, initialized)
            }
        }.onFailure {
            finish(KoreanTextToSpeechSynthesisProbeResult.INITIALIZATION_FAILED)
        }
    }

    fun cancel() {
        finish(KoreanTextToSpeechSynthesisProbeResult.CANCELLED)
    }

    private fun onInitialized(
        initializedEngine: KoreanTextToSpeechProbeEngine,
        initialized: Boolean,
    ) {
        if (!isCurrent(initializedEngine)) return
        if (!initialized) {
            finish(KoreanTextToSpeechSynthesisProbeResult.INITIALIZATION_FAILED)
            return
        }
        if (!runCatching { initializedEngine.selectKoreanLanguage() }.getOrDefault(false)) {
            finish(KoreanTextToSpeechSynthesisProbeResult.KOREAN_LANGUAGE_UNAVAILABLE)
            return
        }

        val createdOutput = runCatching { outputFileFactory.create() }.getOrElse {
            finish(KoreanTextToSpeechSynthesisProbeResult.OUTPUT_FILE_UNAVAILABLE)
            return
        }
        val outputRetained = synchronized(lock) {
            if (state == ProbeState.RUNNING && engine === initializedEngine) {
                outputFile = createdOutput
                true
            } else {
                false
            }
        }
        if (!outputRetained) {
            runCatching { createdOutput.delete() }
            return
        }

        val listenerAccepted = runCatching {
            initializedEngine.setProgressListener(
                object : KoreanTextToSpeechProbeProgressListener {
                    override fun onDone(callbackUtteranceId: String?) {
                        if (callbackUtteranceId != utteranceId) return
                        val completedOutput = synchronized(lock) {
                            outputFile.takeIf {
                                state == ProbeState.RUNNING && engine === initializedEngine
                            }
                        } ?: return
                        val outputVerdict = runCatching {
                            KoreanTtsOutputDurationPolicy.verify(completedOutput.readBytes())
                        }.getOrDefault(KoreanTtsOutputDurationVerdict.UNREADABLE)
                        finish(
                            when (outputVerdict) {
                                KoreanTtsOutputDurationVerdict.SUFFICIENT ->
                                    KoreanTextToSpeechSynthesisProbeResult.AVAILABLE
                                KoreanTtsOutputDurationVerdict.TOO_SHORT ->
                                    KoreanTextToSpeechSynthesisProbeResult.OUTPUT_TOO_SHORT
                                KoreanTtsOutputDurationVerdict.EMPTY,
                                KoreanTtsOutputDurationVerdict.UNREADABLE ->
                                    KoreanTextToSpeechSynthesisProbeResult.EMPTY_OUTPUT
                            },
                        )
                    }

                    override fun onError(callbackUtteranceId: String?) {
                        if (callbackUtteranceId == utteranceId) {
                            finish(KoreanTextToSpeechSynthesisProbeResult.SYNTHESIS_ERROR)
                        }
                    }

                    override fun onStop(callbackUtteranceId: String?) {
                        if (callbackUtteranceId == utteranceId) {
                            finish(KoreanTextToSpeechSynthesisProbeResult.SYNTHESIS_STOPPED)
                        }
                    }
                },
            )
        }.getOrDefault(false)
        if (!listenerAccepted) {
            finish(KoreanTextToSpeechSynthesisProbeResult.LISTENER_REGISTRATION_FAILED)
            return
        }
        if (!isCurrent(initializedEngine)) return

        val synthesisAccepted = runCatching {
            initializedEngine.synthesizeToFile(PROBE_TEXT_KO, createdOutput, utteranceId)
        }.getOrDefault(false)
        if (!synthesisAccepted) {
            finish(KoreanTextToSpeechSynthesisProbeResult.SYNTHESIS_REJECTED)
        }
    }

    private fun isCurrent(candidate: KoreanTextToSpeechProbeEngine): Boolean =
        synchronized(lock) {
            state == ProbeState.RUNNING && engine === candidate
        }

    private fun finish(
        result: KoreanTextToSpeechSynthesisProbeResult,
        closing: Boolean = false,
    ) {
        val cleanup = synchronized(lock) {
            if (state != ProbeState.RUNNING) {
                if (closing) state = ProbeState.CLOSED
                null
            } else {
                state = if (closing) ProbeState.CLOSED else ProbeState.TERMINAL
                ProbeCleanup(timeoutCancellation, engine, outputFile).also {
                    timeoutCancellation = null
                    engine = null
                    outputFile = null
                }
            }
        } ?: return

        runCatching { cleanup.timeoutCancellation?.cancel() }
        runCatching { cleanup.engine?.close() }
        runCatching { cleanup.outputFile?.delete() }
        onResult(result)
    }

    override fun close() {
        finish(KoreanTextToSpeechSynthesisProbeResult.CLOSED, closing = true)
    }

    private data class ProbeCleanup(
        val timeoutCancellation: KoreanTextToSpeechProbeTimeoutCancellation?,
        val engine: KoreanTextToSpeechProbeEngine?,
        val outputFile: File?,
    )

    private enum class ProbeState {
        IDLE,
        RUNNING,
        TERMINAL,
        CLOSED,
    }

    private companion object {
        const val PROBE_TEXT_KO = "기기 점검 안내입니다."
    }
}

private class AndroidKoreanTextToSpeechProbeEngine(
    context: Context,
) : KoreanTextToSpeechProbeEngine {
    private val lock = Any()
    private var initializationListener: ((Boolean) -> Unit)? = null
    private var pendingInitialization: Boolean? = null
    private var initializationDelivered = false
    private var closed = false
    private val textToSpeech = TextToSpeech(context) { status ->
        publishInitialization(status == TextToSpeech.SUCCESS)
    }

    override fun setInitializationListener(listener: (Boolean) -> Unit) {
        var pending: Boolean? = null
        synchronized(lock) {
            check(initializationListener == null && !initializationDelivered) {
                "TTS initialization listener may only be set once"
            }
            if (closed) return
            initializationListener = listener
            pending = pendingInitialization
            if (pending != null) {
                initializationDelivered = true
                initializationListener = null
                pendingInitialization = null
            }
        }
        pending?.let(listener)
    }

    private fun publishInitialization(initialized: Boolean) {
        var callback: ((Boolean) -> Unit)? = null
        synchronized(lock) {
            if (closed || initializationDelivered) return
            callback = initializationListener
            if (callback == null) {
                pendingInitialization = initialized
            } else {
                initializationDelivered = true
                initializationListener = null
            }
        }
        callback?.invoke(initialized)
    }

    override fun selectKoreanLanguage(): Boolean {
        if (isClosed()) return false
        return runCatching {
            if (textToSpeech.setLanguage(Locale.KOREAN) < TextToSpeech.LANG_AVAILABLE) {
                return@runCatching false
            }
            selectInstalledOfflineKoreanVoice(textToSpeech) == KoreanOfflineVoiceSelection.SELECTED
        }.getOrDefault(false)
    }

    override fun setProgressListener(listener: KoreanTextToSpeechProbeProgressListener): Boolean {
        if (isClosed()) return false
        return runCatching {
            textToSpeech.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        listener.onDone(utteranceId)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        listener.onError(utteranceId)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        listener.onError(utteranceId)
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        listener.onStop(utteranceId)
                    }
                },
            ) == TextToSpeech.SUCCESS
        }.getOrDefault(false)
    }

    override fun synthesizeToFile(
        text: CharSequence,
        outputFile: File,
        utteranceId: String,
    ): Boolean {
        if (isClosed()) return false
        return runCatching {
            textToSpeech.synthesizeToFile(text, Bundle(), outputFile, utteranceId) ==
                TextToSpeech.SUCCESS
        }.getOrDefault(false)
    }

    private fun isClosed(): Boolean = synchronized(lock) { closed }

    override fun close() {
        val shouldClose = synchronized(lock) {
            if (closed) {
                false
            } else {
                closed = true
                initializationListener = null
                pendingInitialization = null
                true
            }
        }
        if (!shouldClose) return
        runCatching { textToSpeech.stop() }
        runCatching { textToSpeech.shutdown() }
    }
}

private class AndroidKoreanTextToSpeechProbeTimeoutScheduler(
    private val handler: Handler,
) : KoreanTextToSpeechProbeTimeoutScheduler {
    override fun schedule(
        delayMs: Long,
        task: () -> Unit,
    ): KoreanTextToSpeechProbeTimeoutCancellation {
        val runnable = Runnable(task)
        check(handler.postDelayed(runnable, delayMs)) { "Unable to schedule Korean TTS timeout" }
        return KoreanTextToSpeechProbeTimeoutCancellation {
            handler.removeCallbacks(runnable)
        }
    }
}

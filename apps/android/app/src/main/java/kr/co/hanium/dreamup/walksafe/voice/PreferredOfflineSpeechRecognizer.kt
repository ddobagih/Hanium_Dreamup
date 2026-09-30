package kr.co.hanium.dreamup.walksafe.voice

import android.Manifest
import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.File
import java.util.Locale

internal interface OfflineSpeechInput

internal interface OneShotSpeechBackend {
    fun setRecognitionListener(listener: RecognitionListener)
    fun startListening(input: OfflineSpeechInput)
    fun destroy()
}

internal interface PlatformSpeechBackend : OneShotSpeechBackend {
    fun checkRecognitionSupport(
        input: OfflineSpeechInput,
        onInstalledLanguages: (languages: () -> List<String>) -> Unit,
        onError: (Int) -> Unit,
    )
    fun cancel()
}

internal interface PreferredSpeechPlatform {
    fun canQueryOnDeviceSupport(): Boolean
    fun isOnDeviceRecognitionAvailable(): Boolean
    fun hasMicrophonePermission(): Boolean
    fun createPlatform(): PlatformSpeechBackend
    fun createVosk(directory: File): OneShotSpeechBackend
}

internal class PreferredPlatformCooldown(var retryAfterMs: Long = 0L)

internal data class PreferredSpeechDependencies(
    val runtime: VoiceCallbackRuntime,
    val platform: PreferredSpeechPlatform,
    val requests: PreferredOfflineSpeechRequest,
    val cooldown: PreferredPlatformCooldown,
    val emitDiagnostic: (action: () -> Unit) -> Unit,
    val annotateResult: (Bundle?, OfflineSpeechEngine) -> Bundle? =
        { results, engine -> withOfflineSpeechResultEngine(results, engine) },
)

private class AndroidOfflineSpeechInput(val intent: Intent) : OfflineSpeechInput

private fun OfflineSpeechInput.androidIntent(): Intent =
    (this as AndroidOfflineSpeechInput).intent

private class AndroidPreferredSpeechPlatform(
    private val context: Context,
) : PreferredSpeechPlatform {
    override fun canQueryOnDeviceSupport(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    override fun isOnDeviceRecognitionAvailable(): Boolean =
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override fun hasMicrophonePermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    override fun createPlatform(): PlatformSpeechBackend =
        AndroidPlatformSpeechBackend(context, SpeechRecognizer.createOnDeviceSpeechRecognizer(context))

    override fun createVosk(directory: File): OneShotSpeechBackend =
        AndroidVoskSpeechBackend(VoskSpeechRecognizer(context, directory))
}

private class AndroidPlatformSpeechBackend(
    private val context: Context,
    private val delegate: SpeechRecognizer,
) : PlatformSpeechBackend {
    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    override fun checkRecognitionSupport(
        input: OfflineSpeechInput,
        onInstalledLanguages: (languages: () -> List<String>) -> Unit,
        onError: (Int) -> Unit,
    ) {
        delegate.checkRecognitionSupport(
            input.androidIntent(),
            context.mainExecutor,
            object : RecognitionSupportCallback {
                override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                    // The adapter must reject a stale callback before reading its metadata.
                    onInstalledLanguages { recognitionSupport.installedOnDeviceLanguages }
                }

                override fun onError(error: Int) {
                    onError(error)
                }
            },
        )
    }

    override fun setRecognitionListener(listener: RecognitionListener) {
        delegate.setRecognitionListener(listener)
    }

    override fun startListening(input: OfflineSpeechInput) {
        delegate.startListening(input.androidIntent())
    }

    override fun cancel() {
        delegate.cancel()
    }

    override fun destroy() {
        delegate.destroy()
    }
}

private class AndroidVoskSpeechBackend(
    private val delegate: VoskSpeechRecognizer,
) : OneShotSpeechBackend {
    override fun setRecognitionListener(listener: RecognitionListener) {
        delegate.setRecognitionListener(listener)
    }

    override fun startListening(input: OfflineSpeechInput) {
        delegate.startListening(input.androidIntent())
    }

    override fun destroy() {
        delegate.destroy()
    }
}

/**
 * HOME one-shot adapter. Only an installed Korean on-device service may precede Vosk.
 * Never uses createSpeechRecognizer, downloads models, or replays a failed recording.
 */
internal class PreferredOfflineSpeechRecognizer internal constructor(
    private val modelDirectory: () -> File?,
    private val dependencies: PreferredSpeechDependencies,
) {
    constructor(context: Context, modelDirectory: () -> File?) : this(
        modelDirectory = modelDirectory,
        dependencies = androidDependencies(context),
    )

    private val runtime = dependencies.runtime
    private val requests = dependencies.requests
    private var listener: RecognitionListener? = null
    private var request: Request? = null
    private var platform: PlatformSpeechBackend? = null
    private var fallback: OneShotSpeechBackend? = null
    private var timeout: Runnable? = null
    private var destroyed = false

    fun setRecognitionListener(listener: RecognitionListener) {
        checkMainThread()
        this.listener = listener
    }

    fun startListening(
        intent: Intent,
        preferPlatform: Boolean = false,
        isRequestCurrent: () -> Boolean = { true },
    ) {
        startPreparedRequest(
            prepareInput = {
                AndroidOfflineSpeechInput(Intent(intent).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                })
            },
            preferPlatform = preferPlatform,
            isRequestCurrent = isRequestCurrent,
        )
    }

    internal fun startPreparedRequest(
        prepareInput: () -> OfflineSpeechInput,
        preferPlatform: Boolean = false,
        isRequestCurrent: () -> Boolean = { true },
    ) {
        checkMainThread()
        val callback = listener ?: return
        if (destroyed) {
            callback.onError(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        if (request != null) {
            // Repeated UI input is not a cancellation of the request already using this listener.
            return
        }
        val id = requests.begin() ?: return
        val current = Request(id, callback, prepareInput(), isRequestCurrent)
        request = current
        diagnose { logVoiceInputDiagnostic(VoiceInputDiagnosticEvent.ONE_SHOT_REQUESTED) }
        if (!accepts(current)) return
        if (!hasMicrophonePermission()) {
            fail(current, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }
        val platformCoolingDown = runtime.nowMs() < dependencies.cooldown.retryAfterMs
        if (preferPlatform && dependencies.platform.canQueryOnDeviceSupport() &&
            !platformCoolingDown &&
            runCatching { dependencies.platform.isOnDeviceRecognitionAvailable() }.getOrDefault(false)
        ) {
            queryPlatform(current)
        } else {
            diagnose {
                logVoiceInputDiagnostic(
                    when {
                        !preferPlatform -> VoiceInputDiagnosticEvent.VOSK_ONLY_REQUESTED
                        !dependencies.platform.canQueryOnDeviceSupport() -> VoiceInputDiagnosticEvent.PLATFORM_UNAVAILABLE
                        platformCoolingDown -> VoiceInputDiagnosticEvent.PLATFORM_COOLDOWN
                        else -> VoiceInputDiagnosticEvent.PLATFORM_UNAVAILABLE
                    },
                )
            }
            select(current, platformReady = false)
        }
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private fun queryPlatform(current: Request) {
        val recognizer = runCatching {
            dependencies.platform.createPlatform()
        }.getOrNull()
        if (recognizer == null) {
            diagnose { logVoiceInputDiagnostic(VoiceInputDiagnosticEvent.PLATFORM_SUPPORT_ERROR) }
            select(current, platformReady = false)
            return
        }
        platform = recognizer
        armTimeout(current, SUPPORT_TIMEOUT_MS) {
            diagnose { logVoiceInputDiagnostic(VoiceInputDiagnosticEvent.PLATFORM_SUPPORT_TIMEOUT) }
            select(current, platformReady = false)
        }
        runCatching {
            recognizer.checkRecognitionSupport(
                current.input,
                onInstalledLanguages = support@ { installedLanguages ->
                    if (!accepts(current) || current.selected) return@support
                    val installed = installedLanguages().any {
                        val tag = it.lowercase(Locale.ROOT).replace('_', '-')
                        tag == "ko" || tag.startsWith("ko-")
                    }
                    diagnose {
                        logVoiceInputDiagnostic(
                            if (installed) VoiceInputDiagnosticEvent.PLATFORM_SUPPORT_READY
                            else VoiceInputDiagnosticEvent.PLATFORM_SUPPORT_NOT_INSTALLED,
                        )
                    }
                    select(current, installed)
                },
                onError = { error ->
                    if (accepts(current) && !current.selected) {
                        diagnose {
                            logVoiceInputDiagnostic(VoiceInputDiagnosticEvent.PLATFORM_SUPPORT_ERROR, errorCode = error)
                        }
                        select(current, platformReady = false)
                    }
                },
            )
        }.onFailure {
            if (accepts(current) && !current.selected) {
                diagnose { logVoiceInputDiagnostic(VoiceInputDiagnosticEvent.PLATFORM_SUPPORT_ERROR) }
                select(current, platformReady = false)
            }
        }
    }

    private fun select(current: Request, platformReady: Boolean) {
        if (!accepts(current) || current.selected) return
        clearTimeout()
        val directory = runCatching(modelDirectory).getOrNull()
        val selection = requests.select(
            current.id,
            platformReady = platformReady && platform != null,
            fallbackReady = directory != null,
            microphoneGranted = hasMicrophonePermission(),
        )
        current.selected = true
        diagnose { logVoiceInputDiagnostic(VoiceInputDiagnosticEvent.ENGINE_SELECTED, selection = selection) }
        when (selection) {
            OfflineSpeechSelection.PLATFORM -> {
                armTimeout(current, START_TIMEOUT_MS) { failPlatformStart(current) }
                runCatching {
                    checkNotNull(platform).setRecognitionListener(callbacks(current, OfflineSpeechEngine.PLATFORM))
                    checkNotNull(platform).startListening(current.input)
                }.onFailure { failPlatformStart(current) }
            }
            OfflineSpeechSelection.VOSK -> {
                disposePlatform()
                armTimeout(current, START_TIMEOUT_MS) { fail(current, SpeechRecognizer.ERROR_CLIENT) }
                runCatching {
                    val recognizer = dependencies.platform.createVosk(checkNotNull(directory))
                    fallback = recognizer
                    recognizer.setRecognitionListener(callbacks(current, OfflineSpeechEngine.VOSK))
                    recognizer.startListening(current.input)
                }.onFailure { fail(current, SpeechRecognizer.ERROR_CLIENT) }
            }
            OfflineSpeechSelection.BUSY -> fail(current, SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            OfflineSpeechSelection.PERMISSION_DENIED -> fail(current, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            OfflineSpeechSelection.UNAVAILABLE -> fail(current, SpeechRecognizer.ERROR_CLIENT)
            OfflineSpeechSelection.STALE -> Unit
        }
    }

    private fun callbacks(current: Request, engine: OfflineSpeechEngine): RecognitionListener =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (!accepts(current, engine) || current.ready) return
                current.ready = true
                diagnose { logVoiceInputDiagnostic(VoiceInputDiagnosticEvent.READY, backend = engine) }
                if (current.phase == null) {
                    current.phase = SpeechPhase.WAITING_FOR_SPEECH
                    current.phaseStartedAtMs = runtime.nowMs()
                }
                armSpeechPhaseTimeout(current)
                current.listener.onReadyForSpeech(params)
            }

            override fun onBeginningOfSpeech() {
                if (!accepts(current, engine)) return
                if (current.phase == null || current.phase == SpeechPhase.WAITING_FOR_SPEECH) {
                    enterSpeechPhase(current, SpeechPhase.SPEAKING)
                }
                current.listener.onBeginningOfSpeech()
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (accepts(current, engine)) current.listener.onRmsChanged(rmsdB)
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                if (accepts(current, engine)) current.listener.onBufferReceived(buffer)
            }

            override fun onEndOfSpeech() {
                if (!accepts(current, engine)) return
                if (current.phase != SpeechPhase.WAITING_FOR_FINAL) {
                    enterSpeechPhase(current, SpeechPhase.WAITING_FOR_FINAL)
                }
                current.listener.onEndOfSpeech()
            }

            override fun onError(error: Int) {
                if (!accepts(current, engine)) return
                diagnose { logVoiceInputDiagnostic(VoiceInputDiagnosticEvent.ERROR, backend = engine, errorCode = error) }
                if (engine == OfflineSpeechEngine.PLATFORM && error in setOf(
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
                        SpeechRecognizer.ERROR_SERVER,
                        SpeechRecognizer.ERROR_CLIENT,
                    )
                ) {
                    // Next explicit request may choose Vosk; never re-record this request.
                    dependencies.cooldown.retryAfterMs = runtime.nowMs() + PLATFORM_RETRY_DELAY_MS
                }
                fail(current, error)
            }

            override fun onResults(results: Bundle?) {
                if (!accepts(current, engine)) return
                diagnose {
                    logVoiceInputDiagnostic(
                        VoiceInputDiagnosticEvent.FINAL_RECEIVED,
                        backend = engine,
                        confidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.getOrNull(0),
                        resultCount = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.size ?: 0,
                    )
                }
                if (retire(current)) {
                    current.listener.onResults(dependencies.annotateResult(results, engine))
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!accepts(current, engine)) return
                if (!current.partialObserved && partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        .orEmpty().any { it.isNotBlank() }
                ) {
                    current.partialObserved = true
                    diagnose { logVoiceInputDiagnostic(VoiceInputDiagnosticEvent.PARTIAL_NONEMPTY, backend = engine) }
                }
                current.listener.onPartialResults(partialResults)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                if (accepts(current, engine)) current.listener.onEvent(eventType, params)
            }
        }

    private fun accepts(current: Request, engine: OfflineSpeechEngine? = null): Boolean {
        if (destroyed || request !== current || !requests.accepts(current.id, engine)) return false
        val requestCurrent = runCatching(current.isCurrent).getOrDefault(false)
        if (destroyed || request !== current || !requests.accepts(current.id, engine)) return false
        if (!requestCurrent) {
            diagnose { logVoiceInputDiagnostic(VoiceInputDiagnosticEvent.REQUEST_STALE) }
            cancel()
            return false
        }
        return true
    }

    private fun fail(current: Request, error: Int) {
        if (!accepts(current)) return
        if (retire(current)) current.listener.onError(error)
    }

    private fun failPlatformStart(current: Request) {
        if (!accepts(current, OfflineSpeechEngine.PLATFORM)) return
        dependencies.cooldown.retryAfterMs = runtime.nowMs() + PLATFORM_RETRY_DELAY_MS
        fail(current, SpeechRecognizer.ERROR_CLIENT)
    }

    private fun retire(current: Request): Boolean {
        if (request !== current) return false
        request = null
        return requests.finish(current.id, ::cleanup)
    }

    fun cancel() {
        checkMainThread()
        if (request != null) diagnose { logVoiceInputDiagnostic(VoiceInputDiagnosticEvent.CANCELLED) }
        request = null
        requests.cancel(::cleanup)
    }

    fun destroy() {
        checkMainThread()
        if (destroyed) return
        if (request != null) diagnose { logVoiceInputDiagnostic(VoiceInputDiagnosticEvent.CLOSED) }
        destroyed = true
        request = null
        listener = null
        requests.close(::cleanup)
        runtime.removeAllCallbacks()
    }

    private fun cleanup() {
        clearTimeout()
        disposePlatform()
        val oldFallback = fallback
        fallback = null
        runCatching { oldFallback?.destroy() }
    }

    private fun disposePlatform() {
        val oldPlatform = platform
        platform = null
        runCatching { oldPlatform?.cancel() }
        runCatching { oldPlatform?.destroy() }
    }

    private fun enterSpeechPhase(current: Request, phase: SpeechPhase) {
        current.phase = phase
        current.phaseStartedAtMs = runtime.nowMs()
        // Early speech callbacks do not replace the existing startup watchdog.
        if (current.ready) armSpeechPhaseTimeout(current)
    }

    private fun armSpeechPhaseTimeout(current: Request) {
        val budgetMs = when (current.phase ?: return) {
            SpeechPhase.WAITING_FOR_SPEECH,
            SpeechPhase.WAITING_FOR_FINAL,
            -> LISTEN_TIMEOUT_MS
            SpeechPhase.SPEAKING -> SPEAKING_TIMEOUT_MS
        }
        val elapsedMs = (runtime.nowMs() - current.phaseStartedAtMs).coerceAtLeast(0L)
        armTimeout(current, (budgetMs - elapsedMs).coerceAtLeast(0L)) {
            fail(current, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        }
    }

    private fun armTimeout(current: Request, delayMs: Long, action: () -> Unit) {
        clearTimeout()
        lateinit var callback: Runnable
        callback = Runnable {
            if (timeout !== callback || !accepts(current)) return@Runnable
            // isCurrent is external code: it must not let this task clear a replacement.
            if (timeout !== callback) return@Runnable
            timeout = null
            diagnose { logVoiceInputDiagnostic(VoiceInputDiagnosticEvent.WATCHDOG) }
            action()
        }
        timeout = callback
        runtime.postDelayed(callback, delayMs)
    }

    private fun clearTimeout() {
        timeout?.let(runtime::removeCallbacks)
        timeout = null
    }

    private fun hasMicrophonePermission(): Boolean =
        dependencies.platform.hasMicrophonePermission()

    private fun checkMainThread() {
        runtime.checkMainThread("PreferredOfflineSpeechRecognizer")
    }

    private fun diagnose(action: () -> Unit) {
        dependencies.emitDiagnostic(action)
    }

    private enum class SpeechPhase { WAITING_FOR_SPEECH, SPEAKING, WAITING_FOR_FINAL }

    private class Request(
        val id: Long,
        val listener: RecognitionListener,
        val input: OfflineSpeechInput,
        val isCurrent: () -> Boolean,
        var selected: Boolean = false,
        var ready: Boolean = false,
        var partialObserved: Boolean = false,
        var phase: SpeechPhase? = null,
        var phaseStartedAtMs: Long = 0L,
    )

    private companion object {
        const val SUPPORT_TIMEOUT_MS = 3_000L
        const val START_TIMEOUT_MS = 20_000L
        const val LISTEN_TIMEOUT_MS = 10_000L
        const val SPEAKING_TIMEOUT_MS = 20_000L
        const val PLATFORM_RETRY_DELAY_MS = 30_000L
        val processCooldown = PreferredPlatformCooldown()

        fun androidDependencies(context: Context): PreferredSpeechDependencies {
            val appContext = context.applicationContext
            return PreferredSpeechDependencies(
                runtime = HandlerVoiceCallbackRuntime(Handler(Looper.getMainLooper())),
                platform = AndroidPreferredSpeechPlatform(appContext),
                requests = PreferredOfflineSpeechRequest(),
                cooldown = processCooldown,
                emitDiagnostic = { action -> action() },
            )
        }
    }
}

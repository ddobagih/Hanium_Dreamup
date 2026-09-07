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
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.File
import java.util.Locale

/**
 * HOME one-shot adapter. Only an installed Korean on-device service may precede Vosk.
 * Never uses createSpeechRecognizer, downloads models, or replays a failed recording.
 */
internal class PreferredOfflineSpeechRecognizer(
    context: Context,
    private val modelDirectory: () -> File?,
) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val requests = PreferredOfflineSpeechRequest()
    private var listener: RecognitionListener? = null
    private var request: Request? = null
    private var platform: SpeechRecognizer? = null
    private var fallback: VoskSpeechRecognizer? = null
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
        checkMainThread()
        val callback = listener ?: return
        if (destroyed) {
            callback.onError(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        if (request != null) {
            cancel()
            callback.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }
        val id = requests.begin() ?: return
        val current = Request(id, callback, Intent(intent).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }, isRequestCurrent)
        request = current
        logVoiceInputDiagnostic(VoiceInputDiagnosticEvent.ONE_SHOT_REQUESTED)
        if (!accepts(current)) return
        if (!hasMicrophonePermission()) {
            fail(current, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }
        val platformCoolingDown = SystemClock.elapsedRealtime() < platformRetryAfterMs
        if (preferPlatform && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !platformCoolingDown &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        ) {
            queryPlatform(current)
        } else {
            logVoiceInputDiagnostic(
                when {
                    !preferPlatform -> VoiceInputDiagnosticEvent.VOSK_ONLY_REQUESTED
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> VoiceInputDiagnosticEvent.PLATFORM_UNAVAILABLE
                    platformCoolingDown -> VoiceInputDiagnosticEvent.PLATFORM_COOLDOWN
                    else -> VoiceInputDiagnosticEvent.PLATFORM_UNAVAILABLE
                },
            )
            select(current, platformReady = false)
        }
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private fun queryPlatform(current: Request) {
        val recognizer = runCatching {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }.getOrNull()
        if (recognizer == null) {
            logVoiceInputDiagnostic(VoiceInputDiagnosticEvent.PLATFORM_SUPPORT_ERROR)
            select(current, platformReady = false)
            return
        }
        platform = recognizer
        armTimeout(current, SUPPORT_TIMEOUT_MS) {
            logVoiceInputDiagnostic(VoiceInputDiagnosticEvent.PLATFORM_SUPPORT_TIMEOUT)
            select(current, platformReady = false)
        }
        runCatching {
            recognizer.checkRecognitionSupport(
                current.intent,
                context.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                        if (!accepts(current) || current.selected) return
                        val installed = recognitionSupport.installedOnDeviceLanguages.any {
                            val tag = it.lowercase(Locale.ROOT).replace('_', '-')
                            tag == "ko" || tag.startsWith("ko-")
                        }
                        logVoiceInputDiagnostic(
                            if (installed) VoiceInputDiagnosticEvent.PLATFORM_SUPPORT_READY
                            else VoiceInputDiagnosticEvent.PLATFORM_SUPPORT_NOT_INSTALLED,
                        )
                        select(current, installed)
                    }

                    override fun onError(error: Int) {
                        if (accepts(current) && !current.selected) {
                            logVoiceInputDiagnostic(VoiceInputDiagnosticEvent.PLATFORM_SUPPORT_ERROR, errorCode = error)
                            select(current, platformReady = false)
                        }
                    }
                },
            )
        }.onFailure {
            if (accepts(current) && !current.selected) {
                logVoiceInputDiagnostic(VoiceInputDiagnosticEvent.PLATFORM_SUPPORT_ERROR)
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
        logVoiceInputDiagnostic(VoiceInputDiagnosticEvent.ENGINE_SELECTED, selection = selection)
        when (selection) {
            OfflineSpeechSelection.PLATFORM -> {
                armTimeout(current, START_TIMEOUT_MS) { fail(current, SpeechRecognizer.ERROR_CLIENT) }
                runCatching {
                    checkNotNull(platform).setRecognitionListener(callbacks(current, OfflineSpeechEngine.PLATFORM))
                    checkNotNull(platform).startListening(current.intent)
                }.onFailure { fail(current, SpeechRecognizer.ERROR_CLIENT) }
            }
            OfflineSpeechSelection.VOSK -> {
                disposePlatform()
                armTimeout(current, START_TIMEOUT_MS) { fail(current, SpeechRecognizer.ERROR_CLIENT) }
                runCatching {
                    val recognizer = VoskSpeechRecognizer(context, checkNotNull(directory))
                    fallback = recognizer
                    recognizer.setRecognitionListener(callbacks(current, OfflineSpeechEngine.VOSK))
                    recognizer.startListening(current.intent)
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
                logVoiceInputDiagnostic(VoiceInputDiagnosticEvent.READY, backend = engine)
                armTimeout(current, LISTEN_TIMEOUT_MS) {
                    fail(current, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                }
                current.listener.onReadyForSpeech(params)
            }

            override fun onBeginningOfSpeech() {
                if (accepts(current, engine)) current.listener.onBeginningOfSpeech()
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (accepts(current, engine)) current.listener.onRmsChanged(rmsdB)
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                if (accepts(current, engine)) current.listener.onBufferReceived(buffer)
            }

            override fun onEndOfSpeech() {
                if (accepts(current, engine)) current.listener.onEndOfSpeech()
            }

            override fun onError(error: Int) {
                if (!accepts(current, engine)) return
                logVoiceInputDiagnostic(VoiceInputDiagnosticEvent.ERROR, backend = engine, errorCode = error)
                if (engine == OfflineSpeechEngine.PLATFORM && error in setOf(
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
                        SpeechRecognizer.ERROR_SERVER,
                        SpeechRecognizer.ERROR_CLIENT,
                    )
                ) {
                    // Next explicit request may choose Vosk; never re-record this request.
                    platformRetryAfterMs = SystemClock.elapsedRealtime() + PLATFORM_RETRY_DELAY_MS
                }
                fail(current, error)
            }

            override fun onResults(results: Bundle?) {
                if (!accepts(current, engine)) return
                logVoiceInputDiagnostic(
                    VoiceInputDiagnosticEvent.FINAL_RECEIVED,
                    backend = engine,
                    confidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.getOrNull(0),
                    resultCount = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.size ?: 0,
                )
                if (retire(current)) {
                    current.listener.onResults(withOfflineSpeechResultEngine(results, engine))
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!accepts(current, engine)) return
                if (!current.partialObserved && partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        .orEmpty().any { it.isNotBlank() }
                ) {
                    current.partialObserved = true
                    logVoiceInputDiagnostic(VoiceInputDiagnosticEvent.PARTIAL_NONEMPTY, backend = engine)
                }
                current.listener.onPartialResults(partialResults)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                if (accepts(current, engine)) current.listener.onEvent(eventType, params)
            }
        }

    private fun accepts(current: Request, engine: OfflineSpeechEngine? = null): Boolean {
        if (destroyed || request !== current || !requests.accepts(current.id, engine)) return false
        if (!runCatching(current.isCurrent).getOrDefault(false)) {
            logVoiceInputDiagnostic(VoiceInputDiagnosticEvent.REQUEST_STALE)
            cancel()
            return false
        }
        return true
    }

    private fun fail(current: Request, error: Int) {
        if (!accepts(current)) return
        if (retire(current)) current.listener.onError(error)
    }

    private fun retire(current: Request): Boolean {
        if (request !== current) return false
        request = null
        return requests.finish(current.id, ::cleanup)
    }

    fun cancel() {
        checkMainThread()
        if (request != null) logVoiceInputDiagnostic(VoiceInputDiagnosticEvent.CANCELLED)
        request = null
        requests.cancel(::cleanup)
    }

    fun destroy() {
        checkMainThread()
        if (destroyed) return
        if (request != null) logVoiceInputDiagnostic(VoiceInputDiagnosticEvent.CLOSED)
        destroyed = true
        request = null
        listener = null
        requests.close(::cleanup)
        handler.removeCallbacksAndMessages(null)
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

    private fun armTimeout(current: Request, delayMs: Long, action: () -> Unit) {
        clearTimeout()
        val callback = Runnable {
            if (accepts(current)) {
                logVoiceInputDiagnostic(VoiceInputDiagnosticEvent.WATCHDOG)
                action()
            }
        }
        timeout = callback
        handler.postDelayed(callback, delayMs)
    }

    private fun clearTimeout() {
        timeout?.let(handler::removeCallbacks)
        timeout = null
    }

    private fun hasMicrophonePermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "PreferredOfflineSpeechRecognizer must be called on the main thread"
        }
    }

    private class Request(
        val id: Long,
        val listener: RecognitionListener,
        val intent: Intent,
        val isCurrent: () -> Boolean,
        var selected: Boolean = false,
        var ready: Boolean = false,
        var partialObserved: Boolean = false,
    )

    private companion object {
        const val SUPPORT_TIMEOUT_MS = 3_000L
        const val START_TIMEOUT_MS = 20_000L
        const val LISTEN_TIMEOUT_MS = 10_000L
        const val PLATFORM_RETRY_DELAY_MS = 30_000L
        var platformRetryAfterMs = 0L
    }
}

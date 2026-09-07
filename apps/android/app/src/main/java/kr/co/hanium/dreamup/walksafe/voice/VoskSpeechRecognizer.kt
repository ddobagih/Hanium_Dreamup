package kr.co.hanium.dreamup.walksafe.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import java.io.File

/** A bounded button request over the same offline decoder used by hands-free listening. */
internal class VoskSpeechRecognizer(
    context: Context,
    modelDirectory: File,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val requests = VoskSpeechRequestFence()
    private var listener: RecognitionListener? = null
    private var requestListener: RecognitionListener? = null
    private var requestId: Long? = null
    private var activeRunId: Long? = null
    private var timeout: Runnable? = null
    private var speechBegan = false
    private var destroyed = false
    private val transcriber = VoskStreamingTranscriber(
        context = appContext,
        modelDirectory = modelDirectory,
        isInputSuppressed = { false },
        listener = object : VoskStreamingListener {
            override fun onReady(runId: Long) {
                handler.post {
                    if (!accepts(runId)) return@post
                    armTimeout(checkNotNull(requestId), LISTEN_TIMEOUT_MS, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                    requestListener?.onReadyForSpeech(Bundle())
                }
            }

            override fun onTranscript(runId: Long, transcript: VoskTranscript) {
                handler.post {
                    if (!accepts(runId)) return@post
                    if (!speechBegan) {
                        speechBegan = true
                        requestListener?.onBeginningOfSpeech()
                        if (!accepts(runId)) return@post
                    }
                    val results = transcript.toSpeechRecognitionResults()
                    if (transcript.isFinal) {
                        val completedListener = retireRequest() ?: return@post
                        completedListener.onEndOfSpeech()
                        completedListener.onResults(results)
                    } else {
                        requestListener?.onPartialResults(results)
                    }
                }
            }

            override fun onError(runId: Long, error: VoskStreamingError) {
                handler.post {
                    if (!accepts(runId)) return@post
                    finishWithError(
                        when (error) {
                            VoskStreamingError.MODEL_UNAVAILABLE,
                            VoskStreamingError.MODEL_LOAD_FAILED,
                            VoskStreamingError.INFERENCE_FAILED,
                            -> SpeechRecognizer.ERROR_CLIENT
                            VoskStreamingError.AUDIO_START_FAILED,
                            VoskStreamingError.AUDIO_READ_FAILED,
                            -> SpeechRecognizer.ERROR_AUDIO
                        },
                    )
                }
            }
        },
    )

    fun setRecognitionListener(listener: RecognitionListener) {
        checkMainThread()
        this.listener = listener
    }

    @Suppress("UNUSED_PARAMETER")
    fun startListening(intent: Intent) {
        checkMainThread()
        val currentListener = listener ?: return
        if (destroyed) {
            currentListener.onError(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        if (requestId != null) {
            finishWithError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            currentListener.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }
        val id = checkNotNull(requests.begin())
        requestId = id
        requestListener = currentListener
        speechBegan = false
        armTimeout(id, LOAD_TIMEOUT_MS, SpeechRecognizer.ERROR_CLIENT)
        if (transcriber.start { activeRunId = it } == null) {
            finishWithError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        }
    }

    fun cancel() {
        checkMainThread()
        requests.cancel()
        requestId = null
        requestListener = null
        activeRunId = null
        timeout?.let(handler::removeCallbacks)
        timeout = null
        transcriber.stop()
    }

    fun destroy() {
        checkMainThread()
        if (destroyed) return
        cancel()
        destroyed = true
        listener = null
        handler.removeCallbacksAndMessages(null)
        transcriber.close()
    }

    private fun accepts(runId: Long): Boolean =
        !destroyed && activeRunId == runId && requestId?.let(requests::accepts) == true

    private fun retireRequest(): RecognitionListener? {
        val id = requestId ?: return null
        if (!requests.finish(id)) return null
        val completedListener = requestListener
        requestId = null
        requestListener = null
        activeRunId = null
        timeout?.let(handler::removeCallbacks)
        timeout = null
        transcriber.stop()
        return completedListener
    }

    private fun finishWithError(error: Int) {
        retireRequest()?.onError(error)
    }

    private fun armTimeout(id: Long, durationMs: Long, error: Int) {
        timeout?.let(handler::removeCallbacks)
        val watchdog = Runnable {
            if (requests.accepts(id)) finishWithError(error)
        }
        timeout = watchdog
        handler.postDelayed(watchdog, durationMs)
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "VoskSpeechRecognizer must be called on the main thread"
        }
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 20_000L
        const val LISTEN_TIMEOUT_MS = 10_000L
    }
}

internal fun VoskTranscript.toSpeechRecognitionResults(): Bundle = Bundle().apply {
    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(confidence ?: Float.NaN))
}

internal class VoskSpeechRequestFence {
    private var generation = 0L
    private var active: Long? = null

    fun begin(): Long? {
        if (active != null) return null
        return (++generation).also { active = it }
    }

    fun accepts(id: Long): Boolean = active == id

    fun finish(id: Long): Boolean {
        if (!accepts(id)) return false
        active = null
        return true
    }

    fun cancel() {
        active = null
    }
}

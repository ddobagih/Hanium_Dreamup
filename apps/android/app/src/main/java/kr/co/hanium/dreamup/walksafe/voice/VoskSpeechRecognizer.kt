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
import java.io.Closeable
import java.io.File

internal interface VoskRequestStream : Closeable {
    fun start(onRunReserved: (Long) -> Unit): Long?
    fun stop()
}

internal data class VoskSpeechDependencies(
    val runtime: VoiceCallbackRuntime,
    val microphoneGranted: () -> Boolean,
    val createStream: (listener: VoskStreamingListener) -> VoskRequestStream,
    val readyBundle: () -> Bundle?,
    val transcriptResults: (VoskTranscript) -> Bundle? =
        { transcript -> transcript.toSpeechRecognitionResults() },
    /** S04-32: typed stage summary per request; no transcript or audio is ever passed. */
    val stageSummarySink: (VoiceRequestStageSummary) -> Unit = {},
)

/** A bounded button request over the same offline decoder used by hands-free listening. */
internal class VoskSpeechRecognizer internal constructor(
    private val dependencies: VoskSpeechDependencies,
) {
    constructor(context: Context, modelDirectory: File) : this(
        dependencies = androidDependencies(context, modelDirectory),
    )

    private val runtime = dependencies.runtime
    private val requests = VoskSpeechRequestFence()
    private var listener: RecognitionListener? = null
    private var requestListener: RecognitionListener? = null
    private var requestId: Long? = null
    private var activeRunId: Long? = null
    private var timeout: Runnable? = null
    private var speechBegan = false
    private var readyDelivered = false
    private var firstTranscriptAtMs: Long? = null
    private var audioEnded = false
    private var destroyed = false
    private val stages = VoiceRequestStageTracker(runtime::nowMs)
    private val transcriber = dependencies.createStream(
        object : VoskStreamingListener {
            override fun onReady(runId: Long) {
                runtime.post {
                    if (!accepts(runId) || readyDelivered) return@post
                    readyDelivered = true
                    stages.ready()
                    if (audioEnded) {
                        // The endpoint already owns the final-result deadline.
                    } else if (speechBegan) {
                        armTranscriptTimeout()
                    } else {
                        armTimeout(checkNotNull(requestId), LISTEN_TIMEOUT_MS, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                    }
                    requestListener?.onReadyForSpeech(dependencies.readyBundle())
                }
            }

            override fun onAudioEnd(runId: Long) {
                runtime.post {
                    if (!accepts(runId) || audioEnded) return@post
                    audioEnded = true
                    stages.audioEnded()
                    armTimeout(checkNotNull(requestId), FINAL_WAIT_TIMEOUT_MS, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                    requestListener?.onEndOfSpeech()
                }
            }

            override fun onTranscript(runId: Long, transcript: VoskTranscript) {
                runtime.post {
                    if (!accepts(runId)) return@post
                    if (!speechBegan) {
                        speechBegan = true
                        firstTranscriptAtMs = runtime.nowMs()
                        stages.speechStarted()
                        // A transcript is the existing speech-start signal, even when empty.
                        // Before ready, leave the original model-load watchdog in place.
                        if (readyDelivered && !audioEnded) armTranscriptTimeout()
                        requestListener?.onBeginningOfSpeech()
                        if (!accepts(runId)) return@post
                    }
                    val results = dependencies.transcriptResults(transcript)
                    if (transcript.isFinal) {
                        val endStillPending = !audioEnded
                        val completedListener = retireRequest() ?: return@post
                        if (endStillPending) completedListener.onEndOfSpeech()
                        dependencies.stageSummarySink(stages.finish(errorCode = null, finalConfidence = transcript.confidence))
                        completedListener.onResults(results)
                    } else {
                        requestListener?.onPartialResults(results)
                    }
                }
            }

            override fun onError(runId: Long, error: VoskStreamingError) {
                runtime.post {
                    if (!accepts(runId)) return@post
                    stages.streamingError(error)
                    finishWithError(
                        when (error) {
                            VoskStreamingError.MODEL_UNAVAILABLE,
                            VoskStreamingError.MODEL_LOAD_FAILED,
                            VoskStreamingError.INFERENCE_FAILED,
                            -> SpeechRecognizer.ERROR_CLIENT
                            VoskStreamingError.AUDIO_START_FAILED,
                            VoskStreamingError.AUDIO_READ_FAILED,
                            -> SpeechRecognizer.ERROR_AUDIO
                            VoskStreamingError.AUDIO_SILENCED -> SPEECH_ERROR_INPUT_SILENCED
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
        startRequest()
    }

    internal fun startRequest() {
        checkMainThread()
        val currentListener = listener ?: return
        if (destroyed) {
            currentListener.onError(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        if (requestId != null) {
            return
        }
        if (!dependencies.microphoneGranted()) {
            currentListener.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }
        val id = checkNotNull(requests.begin())
        requestId = id
        requestListener = currentListener
        speechBegan = false
        readyDelivered = false
        firstTranscriptAtMs = null
        audioEnded = false
        stages.begin()
        armTimeout(id, LOAD_TIMEOUT_MS, SpeechRecognizer.ERROR_CLIENT)
        if (transcriber.start { activeRunId = it } == null) {
            finishWithError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        }
    }

    fun cancel() {
        checkMainThread()
        if (requestId != null) dependencies.stageSummarySink(stages.cancel())
        requests.cancel()
        requestId = null
        requestListener = null
        activeRunId = null
        timeout?.let(runtime::removeCallbacks)
        timeout = null
        audioEnded = false
        transcriber.stop()
    }

    fun destroy() {
        checkMainThread()
        if (destroyed) return
        cancel()
        destroyed = true
        listener = null
        runtime.removeAllCallbacks()
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
        timeout?.let(runtime::removeCallbacks)
        timeout = null
        audioEnded = false
        transcriber.stop()
        return completedListener
    }

    private fun finishWithError(error: Int) {
        val completedListener = retireRequest() ?: return
        dependencies.stageSummarySink(stages.finish(errorCode = error))
        completedListener.onError(error)
    }

    private fun armTranscriptTimeout() {
        val elapsedMs = (runtime.nowMs() - checkNotNull(firstTranscriptAtMs)).coerceAtLeast(0L)
        armTimeout(
            checkNotNull(requestId),
            (TRANSCRIPT_TIMEOUT_MS - elapsedMs).coerceAtLeast(0L),
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        )
    }

    private fun armTimeout(id: Long, durationMs: Long, error: Int) {
        timeout?.let(runtime::removeCallbacks)
        lateinit var watchdog: Runnable
        watchdog = Runnable {
            if (timeout !== watchdog || !requests.accepts(id)) return@Runnable
            timeout = null
            finishWithError(error)
        }
        timeout = watchdog
        runtime.postDelayed(watchdog, durationMs)
    }

    private fun checkMainThread() {
        runtime.checkMainThread("VoskSpeechRecognizer")
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 20_000L
        const val LISTEN_TIMEOUT_MS = 10_000L
        const val TRANSCRIPT_TIMEOUT_MS = 20_000L
        const val FINAL_WAIT_TIMEOUT_MS = 10_000L

        fun androidDependencies(context: Context, modelDirectory: File): VoskSpeechDependencies {
            val appContext = context.applicationContext
            return VoskSpeechDependencies(
                runtime = HandlerVoiceCallbackRuntime(Handler(Looper.getMainLooper())),
                microphoneGranted = {
                    appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                },
                createStream = { listener ->
                    val delegate = VoskStreamingTranscriber(
                        context = appContext,
                        modelDirectory = modelDirectory,
                        isInputSuppressed = { false },
                        listener = listener,
                    )
                    object : VoskRequestStream {
                        override fun start(onRunReserved: (Long) -> Unit): Long? =
                            delegate.start { runId -> onRunReserved(runId) }

                        override fun stop() {
                            delegate.stop()
                        }

                        override fun close() {
                            delegate.close()
                        }
                    }
                },
                readyBundle = { Bundle() },
                stageSummarySink = ::logVoiceRequestStageSummary,
            )
        }
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

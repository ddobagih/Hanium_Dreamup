package kr.co.hanium.dreamup.walksafe.voice

import android.content.Context
import java.io.Closeable
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import org.vosk.Model
import org.vosk.Recognizer

internal enum class VoskStreamingError {
    MODEL_UNAVAILABLE,
    MODEL_LOAD_FAILED,
    AUDIO_START_FAILED,
    AUDIO_READ_FAILED,
    INFERENCE_FAILED,
}

internal interface VoskStreamingListener {
    fun onReady(runId: Long)
    fun onTranscript(runId: Long, transcript: VoskTranscript)
    fun onError(runId: Long, error: VoskStreamingError)
}

/**
 * Runs one Korean Vosk model for both wake-phrase and command transcripts.
 * PCM remains in RAM and is never written to disk.
 */
internal class VoskStreamingTranscriber(
    private val context: Context,
    private val modelDirectory: File,
    private val isInputSuppressed: () -> Boolean,
    private val listener: VoskStreamingListener,
    private val loader: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "walksafe-vosk-loader").apply { isDaemon = true }
    },
) : Closeable {
    private enum class State {
        STOPPED,
        STARTING,
        RUNNING,
        CLOSED,
    }

    private val stateLock = Any()
    private val decoderLock = Any()
    private var state = State.STOPPED
    private var generation = 0L
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioSource: AndroidPcmAudioSource? = null
    private var failureReportedGeneration: Long? = null

    @Volatile
    private var decoderSuppressed = false
    private var lastPartial = ""
    private var lastPartialEmittedAtNanos = 0L

    fun start() {
        val startGeneration = synchronized(stateLock) {
            if (state != State.STOPPED) return
            state = State.STARTING
            generation += 1L
            generation
        }
        try {
            loader.execute { loadAndStart(startGeneration) }
        } catch (_: RejectedExecutionException) {
            failStart(startGeneration, VoskStreamingError.MODEL_LOAD_FAILED)
        }
    }

    fun stop() {
        val resources = synchronized(stateLock) {
            if (state == State.STOPPED || state == State.CLOSED) return
            generation += 1L
            state = State.STOPPED
            detachResourcesLocked()
        }
        releaseResources(resources)
    }

    fun isRunning(): Boolean = synchronized(stateLock) { state == State.RUNNING }

    override fun close() {
        val resources = synchronized(stateLock) {
            if (state == State.CLOSED) return
            generation += 1L
            state = State.CLOSED
            detachResourcesLocked()
        }
        releaseResources(resources)
        loader.shutdownNow()
    }

    private fun loadAndStart(startGeneration: Long) {
        if (!hasRequiredModelFiles(modelDirectory)) {
            failStart(startGeneration, VoskStreamingError.MODEL_UNAVAILABLE)
            return
        }
        val openedModel = try {
            Model(modelDirectory.absolutePath)
        } catch (_: Exception) {
            failStart(startGeneration, VoskStreamingError.MODEL_LOAD_FAILED)
            return
        }
        val openedRecognizer = try {
            Recognizer(openedModel, SAMPLE_RATE_HZ.toFloat()).apply {
                setWords(true)
                setPartialWords(false)
                setMaxAlternatives(0)
                setEndpointerMode(Recognizer.EndpointerMode.SHORT)
            }
        } catch (_: Exception) {
            openedModel.close()
            failStart(startGeneration, VoskStreamingError.MODEL_LOAD_FAILED)
            return
        }
        val source = AndroidPcmAudioSource(
            context = context,
            onPcm16 = { samples -> processPcm(startGeneration, openedRecognizer, samples) },
            onReadError = { handleRuntimeFailure(startGeneration, VoskStreamingError.AUDIO_READ_FAILED) },
        )

        var audioStartFailed = false
        val accepted = synchronized(stateLock) {
            if (state != State.STARTING || generation != startGeneration) {
                false
            } else {
                model = openedModel
                recognizer = openedRecognizer
                audioSource = source
                try {
                    source.start()
                    decoderSuppressed = false
                    lastPartial = ""
                    lastPartialEmittedAtNanos = 0L
                    state = State.RUNNING
                    true
                } catch (_: Throwable) {
                    detachResourcesLocked()
                    state = State.STOPPED
                    generation += 1L
                    audioStartFailed = true
                    false
                }
            }
        }
        if (!accepted) {
            source.stop()
            openedRecognizer.close()
            openedModel.close()
            if (audioStartFailed) {
                listener.onError(startGeneration, VoskStreamingError.AUDIO_START_FAILED)
            }
            return
        }
        listener.onReady(startGeneration)
    }

    private fun processPcm(
        callbackGeneration: Long,
        activeRecognizer: Recognizer,
        samples: ShortArray,
    ) {
        synchronized(decoderLock) {
            if (!isCurrentRunning(callbackGeneration, activeRecognizer)) return
            if (runCatching(isInputSuppressed).getOrDefault(true)) {
                if (!decoderSuppressed) {
                    decoderSuppressed = true
                    activeRecognizer.reset()
                    lastPartial = ""
                }
                return
            }
            if (decoderSuppressed) {
                decoderSuppressed = false
                activeRecognizer.reset()
                lastPartial = ""
            }
            try {
                if (activeRecognizer.acceptWaveForm(samples, samples.size)) {
                    emitTranscript(callbackGeneration, activeRecognizer.result, isFinal = true)
                    lastPartial = ""
                } else {
                    maybeEmitPartial(callbackGeneration, activeRecognizer)
                }
            } catch (_: Throwable) {
                handleRuntimeFailure(callbackGeneration, VoskStreamingError.INFERENCE_FAILED)
            }
        }
    }

    private fun maybeEmitPartial(
        callbackGeneration: Long,
        activeRecognizer: Recognizer,
    ) {
        val nowNanos = System.nanoTime()
        if (nowNanos - lastPartialEmittedAtNanos < PARTIAL_INTERVAL_NANOS) return
        val transcript = parseVoskTranscript(activeRecognizer.partialResult, isFinal = false) ?: return
        if (transcript.text == lastPartial) return
        lastPartial = transcript.text
        lastPartialEmittedAtNanos = nowNanos
        listener.onTranscript(callbackGeneration, transcript)
    }

    private fun emitTranscript(
        callbackGeneration: Long,
        payload: String,
        isFinal: Boolean,
    ) {
        parseVoskTranscript(payload, isFinal)?.let { transcript ->
            listener.onTranscript(callbackGeneration, transcript)
        }
    }

    private fun handleRuntimeFailure(
        callbackGeneration: Long,
        error: VoskStreamingError,
    ) {
        val shouldStop = synchronized(stateLock) {
            if (
                state == State.RUNNING &&
                generation == callbackGeneration &&
                failureReportedGeneration != callbackGeneration
            ) {
                failureReportedGeneration = callbackGeneration
                true
            } else {
                false
            }
        }
        if (!shouldStop) return
        listener.onError(callbackGeneration, error)
        try {
            loader.execute { stopGeneration(callbackGeneration) }
        } catch (_: RejectedExecutionException) {
            stopGeneration(callbackGeneration)
        }
    }

    private fun stopGeneration(callbackGeneration: Long) {
        val resources = synchronized(stateLock) {
            if (state != State.RUNNING || generation != callbackGeneration) return
            generation += 1L
            state = State.STOPPED
            detachResourcesLocked()
        }
        releaseResources(resources)
    }

    private fun failStart(
        startGeneration: Long,
        error: VoskStreamingError,
    ) {
        val accepted = synchronized(stateLock) {
            if (state != State.STARTING || generation != startGeneration) {
                false
            } else {
                state = State.STOPPED
                generation += 1L
                true
            }
        }
        if (accepted) listener.onError(startGeneration, error)
    }

    private fun isCurrentRunning(
        callbackGeneration: Long,
        activeRecognizer: Recognizer,
    ): Boolean = synchronized(stateLock) {
        state == State.RUNNING &&
            generation == callbackGeneration &&
            recognizer === activeRecognizer
    }

    private fun detachResourcesLocked(): Resources {
        val resources = Resources(audioSource, recognizer, model)
        audioSource = null
        recognizer = null
        model = null
        failureReportedGeneration = null
        return resources
    }

    private fun releaseResources(resources: Resources) {
        resources.audioSource?.stop()
        synchronized(decoderLock) {
            runCatching { resources.recognizer?.close() }
            runCatching { resources.model?.close() }
            decoderSuppressed = false
            lastPartial = ""
            lastPartialEmittedAtNanos = 0L
        }
    }

    private data class Resources(
        val audioSource: AndroidPcmAudioSource?,
        val recognizer: Recognizer?,
        val model: Model?,
    )

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val PARTIAL_INTERVAL_NANOS = 200_000_000L

        fun hasRequiredModelFiles(directory: File): Boolean =
            directory.isDirectory &&
                listOf(
                    "am/final.mdl",
                    "conf/mfcc.conf",
                    "conf/model.conf",
                    "graph/Gr.fst",
                    "graph/HCLr.fst",
                ).all { relative -> File(directory, relative).isFile }
    }
}

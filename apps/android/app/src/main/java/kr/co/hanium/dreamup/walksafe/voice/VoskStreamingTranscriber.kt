package kr.co.hanium.dreamup.walksafe.voice

import android.content.Context
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executor
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
    /** S04-27: the system silenced this capture; not a user who stayed quiet. */
    AUDIO_SILENCED,
    AUDIO_ROUTE_CHANGED,
    INFERENCE_FAILED,
}

internal fun voskAudioInputFailure(code: Int): VoskStreamingError =
    when (code) {
        PCM_INPUT_SILENCED_CODE -> VoskStreamingError.AUDIO_SILENCED
        PCM_INPUT_ROUTE_CHANGED_CODE -> VoskStreamingError.AUDIO_ROUTE_CHANGED
        else -> VoskStreamingError.AUDIO_READ_FAILED
    }

internal interface VoskStreamingListener {
    fun onReady(runId: Long)
    /** Decoder utterance endpoint, not physical microphone release. */
    fun onAudioEnd(runId: Long) = Unit
    fun onAudioEnd(runId: Long, inputGeneration: Long?) {
        onAudioEnd(runId)
    }
    fun onTranscript(runId: Long, transcript: VoskTranscript)
    fun onTranscript(runId: Long, transcript: VoskTranscript, inputGeneration: Long?) {
        onTranscript(runId, transcript)
    }
    fun onError(runId: Long, error: VoskStreamingError)
}

internal fun submitReservedVoskStreamingRun(
    runId: Long,
    onRunReserved: (Long) -> Unit,
    executor: Executor,
    task: Runnable,
) {
    onRunReserved(runId)
    executor.execute(task)
}

/** The same single worker must finish release before it can run the next start. */
internal fun requestVoskResourceRelease(executor: ExecutorService, release: () -> Unit) {
    executor.execute(release)
}

/** Only microphone start/release is shared; model loading stays per instance. */
internal object VoskStreamingWorker {
    val process: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "walksafe-vosk-microphone").apply { isDaemon = true }
    }
}

internal data class VoskPreparedRun(
    val audioSource: VoskPcmSource,
    val recognizer: Recognizer?,
    val model: Model?,
)

/**
 * Runs one Korean Vosk model for both wake-phrase and command transcripts.
 * PCM remains in RAM and is never written to disk.
 */
internal class VoskStreamingTranscriber(
    private val context: Context?,
    private val modelDirectory: File,
    private val isInputSuppressed: () -> Boolean,
    private val listener: VoskStreamingListener,
    private val loader: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "walksafe-vosk-loader").apply { isDaemon = true }
    },
    private val homeWakeEndpointing: Boolean = false,
    private val inputGeneration: () -> Long? = { null },
    private val preparedRunFactory: (((Int) -> Unit) -> VoskPreparedRun)? = null,
    private val onRuntimeResourcesDetached: () -> Unit = {},
) : Closeable {
    private enum class State {
        STOPPED,
        STARTING,
        RUNNING,
        CLOSED,
    }

    private val stateLock = Any()
    private val requestLock = Any()
    private val decoderLock = Any()
    private var state = State.STOPPED
    private var generation = 0L
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioSource: VoskPcmSource? = null
    private var failureReportedGeneration: Long? = null
    private var startupReadError: VoskStreamingError? = null

    @Volatile
    private var decoderSuppressed = false
    private var decoderInputGeneration: Long? = null
    private var lastPartial = ""
    private var lastPartialEmittedAtNanos = 0L

    fun start(onRunReserved: (Long) -> Unit = {}): Long? = synchronized(requestLock) {
        val startGeneration = synchronized(stateLock) {
            if (state != State.STOPPED) return null
            state = State.STARTING
            generation += 1L
            startupReadError = null
            generation
        }
        try {
            submitReservedVoskStreamingRun(
                runId = startGeneration,
                onRunReserved = onRunReserved,
                executor = loader,
                task = Runnable { loadAndStart(startGeneration) },
            )
        } catch (_: RejectedExecutionException) {
            failStart(startGeneration, VoskStreamingError.MODEL_LOAD_FAILED)
        }
        return startGeneration
    }

    fun stop() {
        synchronized(requestLock) {
            synchronized(stateLock) {
                if (state == State.STOPPED || state == State.CLOSED) return
                generation += 1L
                state = State.STOPPED
                val resources = detachResourcesLocked()
                requestVoskResourceRelease(VoskStreamingWorker.process) { releaseResources(resources) }
            }
        }
    }

    fun isRunning(): Boolean = synchronized(stateLock) { state == State.RUNNING }

    override fun close() {
        synchronized(requestLock) {
            synchronized(stateLock) {
                if (state == State.CLOSED) return
                generation += 1L
                state = State.CLOSED
                val resources = detachResourcesLocked()
                requestVoskResourceRelease(VoskStreamingWorker.process) { releaseResources(resources) }
            }
            // shutdownNow would discard the queued release and allow a new owner too early.
            if (loader !== VoskStreamingWorker.process) loader.shutdown()
        }
    }

    private fun loadAndStart(startGeneration: Long) {
        preparedRunFactory?.let { factory ->
            val prepared = try {
                factory { code -> handleRuntimeFailure(startGeneration, voskAudioInputFailure(code)) }
            } catch (_: Exception) {
                failStart(startGeneration, VoskStreamingError.MODEL_LOAD_FAILED)
                return
            }
            enqueuePrepared(startGeneration, prepared)
            return
        }
        val verifiedDirectory = try {
            BundledVoskModelInstaller.verifyOrRepairInstalledModel(checkNotNull(context), modelDirectory)
        } catch (_: Exception) {
            failStart(startGeneration, VoskStreamingError.MODEL_LOAD_FAILED)
            return
        }
        if (!hasRequiredModelFiles(verifiedDirectory)) {
            failStart(startGeneration, VoskStreamingError.MODEL_UNAVAILABLE)
            return
        }
        val openedModel = try {
            Model(verifiedDirectory.absolutePath)
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
                if (homeWakeEndpointing) {
                    // Native endpoint durations are seconds; only HOME waits for a short wake.
                    // Keep real endpoint results and their original word confidences.
                    setEndpointerDelays(5.0f, 0.35f, 5.0f)
                }
            }
        } catch (_: Exception) {
            runCatching { openedModel.close() }
            failStart(startGeneration, VoskStreamingError.MODEL_LOAD_FAILED)
            return
        }
        val source = AndroidPcmAudioSource(
            context = checkNotNull(context),
            onPcm16 = { samples -> processPcm(startGeneration, openedRecognizer, samples) },
            onReadError = { code -> handleRuntimeFailure(startGeneration, voskAudioInputFailure(code)) },
        )
        enqueuePrepared(startGeneration, VoskPreparedRun(source, openedRecognizer, openedModel))
    }

    private fun enqueuePrepared(startGeneration: Long, prepared: VoskPreparedRun) {
        try {
            VoskStreamingWorker.process.execute { startPrepared(startGeneration, prepared) }
        } catch (_: RejectedExecutionException) {
            cleanupThenNotifyVoskFailure(
                cleanup = { releaseResources(Resources(prepared.audioSource, prepared.recognizer, prepared.model)) },
                notify = { failStart(startGeneration, VoskStreamingError.AUDIO_START_FAILED) },
            )
        }
    }

    private fun startPrepared(startGeneration: Long, prepared: VoskPreparedRun) {
        val source = prepared.audioSource
        // AudioRecord.startRecording may block. Never hold the UI-facing state lock over it.
        val stillStarting = synchronized(stateLock) {
            state == State.STARTING && generation == startGeneration
        }
        var audioStartFailed = false
        val audioStarted = if (stillStarting) {
            try {
                source.start()
                true
            } catch (_: Throwable) {
                audioStartFailed = true
                false
            }
        } else {
            false
        }
        var startupError: VoskStreamingError? = null
        val accepted = synchronized(stateLock) {
            if (state == State.STARTING && generation == startGeneration) {
                if (!audioStarted || startupReadError != null) {
                    startupError = if (audioStartFailed) {
                        VoskStreamingError.AUDIO_START_FAILED
                    } else {
                        startupReadError
                    }
                    state = State.STOPPED
                    generation += 1L
                    startupReadError = null
                    false
                } else {
                model = prepared.model
                recognizer = prepared.recognizer
                audioSource = source
                decoderSuppressed = false
                decoderInputGeneration = null
                lastPartial = ""
                lastPartialEmittedAtNanos = 0L
                state = State.RUNNING
                true
                }
            } else {
                false
            }
        }
        if (!accepted) {
            cleanupThenNotifyVoskFailure(
                cleanup = { releaseResources(Resources(source, prepared.recognizer, prepared.model)) },
                notify = { startupError?.let { listener.onError(startGeneration, it) } },
            )
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
            try {
            val capturedInputGeneration = runCatching(inputGeneration).getOrNull()
            if (runCatching(isInputSuppressed).getOrDefault(true)) {
                if (!decoderSuppressed) {
                    decoderSuppressed = true
                    activeRecognizer.reset()
                    lastPartial = ""
                }
                return
            }
            if (decoderSuppressed || decoderInputGeneration != capturedInputGeneration) {
                decoderSuppressed = false
                activeRecognizer.reset()
                lastPartial = ""
            }
            decoderInputGeneration = capturedInputGeneration
                if (activeRecognizer.acceptWaveForm(samples, samples.size)) {
                    listener.onAudioEnd(callbackGeneration, capturedInputGeneration)
                    emitTranscript(
                        callbackGeneration,
                        activeRecognizer.result,
                        isFinal = true,
                        capturedInputGeneration = capturedInputGeneration,
                    )
                    lastPartial = ""
                } else {
                    maybeEmitPartial(callbackGeneration, activeRecognizer, capturedInputGeneration)
                }
            } catch (_: Exception) {
                handleRuntimeFailure(callbackGeneration, VoskStreamingError.INFERENCE_FAILED)
            }
        }
    }

    private fun maybeEmitPartial(
        callbackGeneration: Long,
        activeRecognizer: Recognizer,
        capturedInputGeneration: Long?,
    ) {
        val nowNanos = System.nanoTime()
        if (nowNanos - lastPartialEmittedAtNanos < PARTIAL_INTERVAL_NANOS) return
        val transcript = parseVoskTranscript(activeRecognizer.partialResult, isFinal = false) ?: return
        if (transcript.text == lastPartial) return
        lastPartial = transcript.text
        lastPartialEmittedAtNanos = nowNanos
        listener.onTranscript(callbackGeneration, transcript, capturedInputGeneration)
    }

    private fun emitTranscript(
        callbackGeneration: Long,
        payload: String,
        isFinal: Boolean,
        capturedInputGeneration: Long?,
    ) {
        parseVoskTranscript(payload, isFinal)?.let { transcript ->
            listener.onTranscript(callbackGeneration, transcript, capturedInputGeneration)
        }
    }

    private fun handleRuntimeFailure(
        callbackGeneration: Long,
        error: VoskStreamingError,
    ) {
        val shouldReport = synchronized(stateLock) {
            if (state == State.STARTING && generation == callbackGeneration) {
                if (startupReadError == null) startupReadError = error
                false
            } else if (
                state == State.RUNNING &&
                generation == callbackGeneration &&
                failureReportedGeneration != callbackGeneration
            ) {
                failureReportedGeneration = callbackGeneration
                generation += 1L
                state = State.STOPPED
                val resources = detachResourcesLocked()
                try {
                    onRuntimeResourcesDetached()
                } catch (_: Exception) {
                    // An observer failure must not skip audio release or the original terminal error.
                }
                requestVoskResourceRelease(VoskStreamingWorker.process) { releaseResources(resources) }
                true
            } else {
                false
            }
        }
        if (!shouldReport) return
        listener.onError(callbackGeneration, error)
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
        startupReadError = null
        return resources
    }

    private fun releaseResources(resources: Resources) {
        releaseVoskResources(
            decoderLock = decoderLock,
            stopAudio = { resources.audioSource?.stop() },
            closeRecognizer = { resources.recognizer?.close() },
            closeModel = { resources.model?.close() },
            resetDecoderState = {
                decoderSuppressed = false
                decoderInputGeneration = null
                lastPartial = ""
                lastPartialEmittedAtNanos = 0L
            },
        )
    }

    private data class Resources(
        val audioSource: VoskPcmSource?,
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
                ).all { relative -> File(directory, relative).let { it.isFile && it.length() > 0L } }
    }
}

/**
 * The production cleanup composition: stop capture before taking the decoder lock, then
 * close native decoder resources and reset state under that same lock. Ordinary cleanup
 * exceptions retain their existing handling. A fatal signal escaping an action is deferred
 * until all independent actions and the state reset have been attempted, then rethrown.
 */
internal fun releaseVoskResources(
    decoderLock: Any,
    stopAudio: () -> Unit,
    closeRecognizer: () -> Unit,
    closeModel: () -> Unit,
    resetDecoderState: () -> Unit,
) {
    var firstFatal: Throwable? = null

    fun retainFailure(failure: Throwable) {
        val first = firstFatal
        if (first == null) {
            firstFatal = failure
        } else if (first !== failure) {
            // Keep the original object. Later failures are suppressed when supported;
            // disabled/failed suppression must not replace it or skip another cleanup.
            runCatching { first.addSuppressed(failure) }
        }
    }

    fun attempt(action: () -> Unit) {
        try {
            attemptVoskCleanup(action)
        } catch (failure: Throwable) {
            retainFailure(failure)
        }
    }

    attempt(stopAudio)
    synchronized(decoderLock) {
        attempt(closeRecognizer)
        attempt(closeModel)
        try {
            resetDecoderState()
        } catch (failure: Throwable) {
            // Reset was not an exception-swallowing cleanup action before this composition.
            // Preserve its propagation unless an earlier fatal signal already owns the exit.
            if (firstFatal == null) throw failure
            retainFailure(failure)
        }
    }
    firstFatal?.let { throw it }
}

/** Attempt the failure callback even when cleanup throws, without replacing its original failure. */
internal fun cleanupThenNotifyVoskFailure(cleanup: () -> Unit, notify: () -> Unit) {
    var cleanupFailure: Throwable? = null
    try {
        cleanup()
    } catch (failure: Throwable) {
        cleanupFailure = failure
    }
    try {
        notify()
    } catch (notificationFailure: Throwable) {
        val original = cleanupFailure ?: throw notificationFailure
        if (original !== notificationFailure) runCatching { original.addSuppressed(notificationFailure) }
    }
    cleanupFailure?.let { throw it }
}

/** Preserve ordinary cleanup-exception handling; fatal signals escape to the composition. */
internal fun attemptVoskCleanup(action: () -> Unit) {
    try {
        action()
    } catch (_: Exception) {
        // Ownership was already revoked; there is no safe resource to return to a caller.
    }
}

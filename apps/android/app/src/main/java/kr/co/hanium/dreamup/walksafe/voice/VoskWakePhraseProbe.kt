package kr.co.hanium.dreamup.walksafe.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.io.File

internal enum class VoskWakePhraseProbeAvailability {
    AVAILABLE,
    UNAVAILABLE,
}

internal enum class VoskWakePhraseProbeFailure {
    TIMEOUT,
    STREAM_ERROR,
    START_FAILED,
    CANCELLED,
    CLOSED,
}

internal data class VoskWakePhraseProbeResult(
    val availability: VoskWakePhraseProbeAvailability,
    val failure: VoskWakePhraseProbeFailure? = null,
    val streamingError: VoskStreamingError? = null,
    val commandTranscript: VoskTranscript? = null,
) {
    init {
        require(
            (availability == VoskWakePhraseProbeAvailability.AVAILABLE) == (failure == null),
        )
        require(
            (failure == VoskWakePhraseProbeFailure.STREAM_ERROR) == (streamingError != null),
        )
        require(commandTranscript == null || (
            availability == VoskWakePhraseProbeAvailability.AVAILABLE &&
                commandTranscript.isFinal && commandTranscript.isTrustedForCommand() &&
                commandTranscript.text.isNotBlank()
            ))
    }

    companion object {
        fun available(
            commandTranscript: VoskTranscript? = null,
        ): VoskWakePhraseProbeResult = VoskWakePhraseProbeResult(
            availability = VoskWakePhraseProbeAvailability.AVAILABLE,
            commandTranscript = commandTranscript,
        )

        fun unavailable(
            failure: VoskWakePhraseProbeFailure,
            streamingError: VoskStreamingError? = null,
        ): VoskWakePhraseProbeResult = VoskWakePhraseProbeResult(
            availability = VoskWakePhraseProbeAvailability.UNAVAILABLE,
            failure = failure,
            streamingError = streamingError,
        )
    }
}

internal interface VoskWakePhraseStream : Closeable {
    fun start()
    fun stop()
}

internal fun interface VoskWakePhraseStreamFactory {
    fun create(listener: VoskStreamingListener): VoskWakePhraseStream
}

internal fun interface VoskWakePhraseProbeCancellation {
    fun cancel()
}

/** Posts result delivery and owns the active phase timeout. Production uses the main looper. */
internal interface VoskWakePhraseProbeDispatcher {
    fun post(task: () -> Unit)

    fun postDelayed(
        delayMs: Long,
        task: () -> Unit,
    ): VoskWakePhraseProbeCancellation
}

/**
 * Foreground-only wake proof; an explicit HOME owner may keep listening until addressed.
 * Audio and transcript text remain callback-local and are never persisted by this class.
 */
internal class VoskWakePhraseProbe internal constructor(
    private val streamFactory: VoskWakePhraseStreamFactory,
    private val dispatcher: VoskWakePhraseProbeDispatcher,
    private val timeoutMs: Long,
    private val onResult: (VoskWakePhraseProbeResult) -> Unit,
    private val onReady: () -> Unit = {},
    private val onDiagnostic: (VoiceInputDiagnosticEvent, Float?) -> Unit = { _, _ -> },
    private val listenUntilWake: Boolean = false,
) : Closeable {
    private enum class State {
        IDLE,
        STARTING,
        LISTENING,
        TERMINAL,
    }

    private val stateLock = Any()
    private var state = State.IDLE
    private var activeRunId: Long? = null
    private var stream: VoskWakePhraseStream? = null
    private var timeoutCancellation: VoskWakePhraseProbeCancellation? = null
    private var partialObserved = false

    constructor(
        context: Context,
        modelDirectory: File,
        onResult: (VoskWakePhraseProbeResult) -> Unit,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        isInputSuppressed: () -> Boolean = { false },
        onReady: () -> Unit = {},
        onDiagnostic: (VoiceInputDiagnosticEvent, Float?) -> Unit = { event, confidence ->
            logVoiceInputDiagnostic(event, backend = OfflineSpeechEngine.VOSK, confidence = confidence)
        },
        listenUntilWake: Boolean = false,
    ) : this(
        streamFactory = VoskWakePhraseStreamFactory { listener ->
            AndroidVoskWakePhraseStream(
                context = context.applicationContext,
                modelDirectory = modelDirectory,
                isInputSuppressed = isInputSuppressed,
                listener = listener,
                onDiagnostic = onDiagnostic,
                homeWakeEndpointing = listenUntilWake,
            )
        },
        dispatcher = AndroidVoskWakePhraseProbeDispatcher(),
        timeoutMs = timeoutMs,
        onResult = onResult,
        onReady = onReady,
        onDiagnostic = onDiagnostic,
        listenUntilWake = listenUntilWake,
    )

    init {
        require(timeoutMs > 0L) { "Vosk wake-phrase timeout must be positive" }
    }

    private val listener = object : VoskStreamingListener {
        override fun onReady(runId: Long) {
            handleReady(runId)
        }

        override fun onTranscript(runId: Long, transcript: VoskTranscript) {
            if (listenUntilWake && transcript.isFinal) {
                // Capture callbacks must return before HOME cleanup joins the capture worker.
                // Deliver handoff only after dispatcher-thread stop/close releases its lease.
                dispatcher.post { handleTranscript(runId, transcript) }
            } else {
                handleTranscript(runId, transcript)
            }
        }

        override fun onError(runId: Long, error: VoskStreamingError) {
            if (listenUntilWake) {
                dispatcher.post { handleError(runId, error) }
            } else {
                handleError(runId, error)
            }
        }
    }

    /** Starts exactly one attempt. A terminal or already-started probe cannot be reused. */
    fun start(): Boolean {
        synchronized(stateLock) {
            if (state != State.IDLE) return false
            state = State.STARTING
        }
        diagnose(VoiceInputDiagnosticEvent.WAKE_START_REQUESTED)

        val createdStream = try {
            streamFactory.create(listener)
        } catch (_: Throwable) {
            finishUnavailable(VoskWakePhraseProbeFailure.START_FAILED)
            return false
        }
        val accepted = synchronized(stateLock) {
            if (state == State.STARTING) {
                stream = createdStream
                true
            } else {
                false
            }
        }
        if (!accepted) {
            releaseStream(createdStream)
            return false
        }

        val scheduledTimeout = try {
            dispatcher.postDelayed(timeoutMs) {
                finish(
                    VoskWakePhraseProbeResult.unavailable(VoskWakePhraseProbeFailure.TIMEOUT),
                    expectedState = State.STARTING,
                )
            }
        } catch (_: Throwable) {
            finishUnavailable(VoskWakePhraseProbeFailure.START_FAILED)
            return false
        }
        val timeoutAccepted = synchronized(stateLock) {
            if (state == State.STARTING && stream === createdStream) {
                timeoutCancellation = scheduledTimeout
                true
            } else {
                false
            }
        }
        if (!timeoutAccepted) {
            runCatching { scheduledTimeout.cancel() }
            return false
        }

        return try {
            createdStream.start()
            true
        } catch (_: Throwable) {
            finishUnavailable(VoskWakePhraseProbeFailure.START_FAILED)
            false
        }
    }

    fun cancel() {
        finishUnavailable(VoskWakePhraseProbeFailure.CANCELLED)
    }

    override fun close() {
        finishUnavailable(VoskWakePhraseProbeFailure.CLOSED)
    }

    private fun handleReady(runId: Long) {
        if (runId <= 0L) return
        val preparationTimeout = synchronized(stateLock) {
            if (state != State.STARTING) return
            activeRunId = runId
            state = State.LISTENING
            timeoutCancellation.also { timeoutCancellation = null }
        }
        runCatching { preparationTimeout?.cancel() }

        val listeningTimeout = if (listenUntilWake) null else try {
            dispatcher.postDelayed(timeoutMs) {
                finish(
                    VoskWakePhraseProbeResult.unavailable(VoskWakePhraseProbeFailure.TIMEOUT),
                    expectedState = State.LISTENING,
                    expectedRunId = runId,
                )
            }
        } catch (_: Throwable) {
            finish(
                VoskWakePhraseProbeResult.unavailable(VoskWakePhraseProbeFailure.START_FAILED),
                expectedState = State.LISTENING,
                expectedRunId = runId,
            )
            return
        }
        val timeoutAccepted = synchronized(stateLock) {
            if (state == State.LISTENING && activeRunId == runId) {
                timeoutCancellation = listeningTimeout
                true
            } else {
                false
            }
        }
        if (!timeoutAccepted) {
            runCatching { listeningTimeout?.cancel() }
            return
        }

        diagnose(VoiceInputDiagnosticEvent.WAKE_READY)
        runCatching { dispatcher.post(onReady) }
    }

    private fun handleTranscript(
        runId: Long,
        transcript: VoskTranscript,
    ) {
        val current = synchronized(stateLock) {
            state == State.LISTENING && activeRunId == runId
        }
        if (!current) return
        if (!transcript.isFinal) {
            val firstPartial = synchronized(stateLock) {
                if (partialObserved) false else {
                    partialObserved = true
                    true
                }
            }
            if (firstPartial) diagnose(VoiceInputDiagnosticEvent.WAKE_PARTIAL_FIRST)
            return
        }
        diagnose(VoiceInputDiagnosticEvent.WAKE_FINAL_RECEIVED, transcript.confidence)
        if (!transcript.isTrustedForCommand()) {
            diagnose(VoiceInputDiagnosticEvent.WAKE_FINAL_UNTRUSTED, transcript.confidence)
            return
        }

        val extraction = WakePhraseCommandExtractor.extractFinalTranscript(
            transcript = transcript.text,
            mode = WakePhraseCommandMode.WAKE_PHRASE_REQUIRED,
        )
        val addressed = when (extraction) {
            WakePhraseCommandExtraction.NotAddressed -> false
            WakePhraseCommandExtraction.AwaitingCommand,
            is WakePhraseCommandExtraction.Command,
            -> true
        }
        diagnose(
            if (addressed) VoiceInputDiagnosticEvent.WAKE_ADDRESSED
            else VoiceInputDiagnosticEvent.WAKE_NOT_ADDRESSED,
            transcript.confidence,
        )
        if (addressed) {
            // Only HOME consumes a command in the wake utterance; capability probes never do.
            val command = if (listenUntilWake && extraction is WakePhraseCommandExtraction.Command) {
                transcript.copy(text = extraction.text)
            } else null
            finish(VoskWakePhraseProbeResult.available(command))
        }
    }

    private fun handleError(
        runId: Long,
        error: VoskStreamingError,
    ) {
        val current = synchronized(stateLock) {
            state == State.STARTING ||
                (state == State.LISTENING && activeRunId == runId)
        }
        if (!current) return
        finishUnavailable(
            failure = VoskWakePhraseProbeFailure.STREAM_ERROR,
            streamingError = error,
        )
    }

    private fun finishUnavailable(
        failure: VoskWakePhraseProbeFailure,
        streamingError: VoskStreamingError? = null,
    ) {
        finish(VoskWakePhraseProbeResult.unavailable(failure, streamingError))
    }

    private fun finish(
        result: VoskWakePhraseProbeResult,
        expectedState: State? = null,
        expectedRunId: Long? = null,
    ) {
        val resources = synchronized(stateLock) {
            if (state == State.TERMINAL) return
            if (expectedState != null && state != expectedState) return
            if (expectedRunId != null && activeRunId != expectedRunId) return
            state = State.TERMINAL
            activeRunId = null
            TerminalResources(
                timeout = timeoutCancellation.also { timeoutCancellation = null },
                stream = stream.also { stream = null },
            )
        }
        diagnose(
            when (result.failure) {
                null -> VoiceInputDiagnosticEvent.WAKE_AVAILABLE
                VoskWakePhraseProbeFailure.TIMEOUT -> VoiceInputDiagnosticEvent.WAKE_TIMEOUT
                VoskWakePhraseProbeFailure.STREAM_ERROR -> VoiceInputDiagnosticEvent.WAKE_STREAM_ERROR
                VoskWakePhraseProbeFailure.START_FAILED -> VoiceInputDiagnosticEvent.WAKE_START_FAILED
                VoskWakePhraseProbeFailure.CANCELLED -> VoiceInputDiagnosticEvent.WAKE_CANCELLED
                VoskWakePhraseProbeFailure.CLOSED -> VoiceInputDiagnosticEvent.WAKE_CLOSED
            },
        )
        runCatching { resources.timeout?.cancel() }
        resources.stream?.let(::releaseStream)
        runCatching {
            dispatcher.post { onResult(result) }
        }
    }

    private fun diagnose(event: VoiceInputDiagnosticEvent, confidence: Float? = null) {
        runCatching { onDiagnostic(event, confidence) }
    }

    private fun releaseStream(stream: VoskWakePhraseStream) {
        runCatching { stream.stop() }
        runCatching { stream.close() }
    }

    private data class TerminalResources(
        val timeout: VoskWakePhraseProbeCancellation?,
        val stream: VoskWakePhraseStream?,
    )

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 20_000L
    }
}

private class AndroidVoskWakePhraseStream(
    context: Context,
    modelDirectory: File,
    isInputSuppressed: () -> Boolean,
    listener: VoskStreamingListener,
    onDiagnostic: (VoiceInputDiagnosticEvent, Float?) -> Unit,
    homeWakeEndpointing: Boolean,
) : VoskWakePhraseStream {
    private var previousSuppression: Boolean? = null
    private val transcriber = VoskStreamingTranscriber(
        context = context,
        modelDirectory = modelDirectory,
        isInputSuppressed = {
            val suppressed = isInputSuppressed()
            if (previousSuppression != suppressed) {
                previousSuppression = suppressed
                runCatching {
                    onDiagnostic(
                        if (suppressed) VoiceInputDiagnosticEvent.WAKE_INPUT_SUPPRESSED
                        else VoiceInputDiagnosticEvent.WAKE_INPUT_UNSUPPRESSED,
                        null,
                    )
                }
            }
            suppressed
        },
        listener = listener,
        homeWakeEndpointing = homeWakeEndpointing,
    )

    override fun start() {
        transcriber.start()
    }

    override fun stop() {
        transcriber.stop()
    }

    override fun close() {
        transcriber.close()
    }
}

private class AndroidVoskWakePhraseProbeDispatcher(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : VoskWakePhraseProbeDispatcher {
    override fun post(task: () -> Unit) {
        check(handler.post(Runnable { task() })) { "Main looper rejected Vosk probe result" }
    }

    override fun postDelayed(
        delayMs: Long,
        task: () -> Unit,
    ): VoskWakePhraseProbeCancellation {
        val runnable = Runnable { task() }
        check(handler.postDelayed(runnable, delayMs)) { "Main looper rejected Vosk probe timeout" }
        return VoskWakePhraseProbeCancellation {
            handler.removeCallbacks(runnable)
        }
    }
}

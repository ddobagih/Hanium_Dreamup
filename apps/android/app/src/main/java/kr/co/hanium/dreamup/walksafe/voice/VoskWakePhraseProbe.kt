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
) {
    init {
        require(
            (availability == VoskWakePhraseProbeAvailability.AVAILABLE) == (failure == null),
        )
        require(
            (failure == VoskWakePhraseProbeFailure.STREAM_ERROR) == (streamingError != null),
        )
    }

    companion object {
        fun available(): VoskWakePhraseProbeResult = VoskWakePhraseProbeResult(
            availability = VoskWakePhraseProbeAvailability.AVAILABLE,
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

/** Posts result delivery and owns the one-shot timeout. Production uses the main looper. */
internal interface VoskWakePhraseProbeDispatcher {
    fun post(task: () -> Unit)

    fun postDelayed(
        delayMs: Long,
        task: () -> Unit,
    ): VoskWakePhraseProbeCancellation
}

/**
 * One-shot, foreground-only proof that the production Vosk path can hear the exact wake phrase.
 * Audio and transcript text remain callback-local and are never persisted by this class.
 */
internal class VoskWakePhraseProbe internal constructor(
    private val streamFactory: VoskWakePhraseStreamFactory,
    private val dispatcher: VoskWakePhraseProbeDispatcher,
    private val timeoutMs: Long,
    private val onResult: (VoskWakePhraseProbeResult) -> Unit,
    private val onReady: () -> Unit = {},
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

    constructor(
        context: Context,
        modelDirectory: File,
        onResult: (VoskWakePhraseProbeResult) -> Unit,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        isInputSuppressed: () -> Boolean = { false },
        onReady: () -> Unit = {},
    ) : this(
        streamFactory = VoskWakePhraseStreamFactory { listener ->
            AndroidVoskWakePhraseStream(
                context = context.applicationContext,
                modelDirectory = modelDirectory,
                isInputSuppressed = isInputSuppressed,
                listener = listener,
            )
        },
        dispatcher = AndroidVoskWakePhraseProbeDispatcher(),
        timeoutMs = timeoutMs,
        onResult = onResult,
        onReady = onReady,
    )

    init {
        require(timeoutMs > 0L) { "Vosk wake-phrase timeout must be positive" }
    }

    private val listener = object : VoskStreamingListener {
        override fun onReady(runId: Long) {
            handleReady(runId)
        }

        override fun onTranscript(runId: Long, transcript: VoskTranscript) {
            handleTranscript(runId, transcript)
        }

        override fun onError(runId: Long, error: VoskStreamingError) {
            handleError(runId, error)
        }
    }

    /** Starts exactly one attempt. A terminal or already-started probe cannot be reused. */
    fun start(): Boolean {
        synchronized(stateLock) {
            if (state != State.IDLE) return false
            state = State.STARTING
        }

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
                finishUnavailable(VoskWakePhraseProbeFailure.TIMEOUT)
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
        val accepted = synchronized(stateLock) {
            if (state != State.STARTING) return@synchronized false
            activeRunId = runId
            state = State.LISTENING
            true
        }
        if (accepted) runCatching { dispatcher.post(onReady) }
    }

    private fun handleTranscript(
        runId: Long,
        transcript: VoskTranscript,
    ) {
        val current = synchronized(stateLock) {
            state == State.LISTENING && activeRunId == runId
        }
        if (!current || !transcript.isFinal || !transcript.isTrustedForCommand()) return

        val addressed = when (
            WakePhraseCommandExtractor.extractFinalTranscript(
                transcript = transcript.text,
                mode = WakePhraseCommandMode.WAKE_PHRASE_REQUIRED,
            )
        ) {
            WakePhraseCommandExtraction.NotAddressed -> false
            WakePhraseCommandExtraction.AwaitingCommand,
            is WakePhraseCommandExtraction.Command,
            -> true
        }
        if (addressed) finish(VoskWakePhraseProbeResult.available())
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

    private fun finish(result: VoskWakePhraseProbeResult) {
        val resources = synchronized(stateLock) {
            if (state == State.TERMINAL) return
            state = State.TERMINAL
            activeRunId = null
            TerminalResources(
                timeout = timeoutCancellation.also { timeoutCancellation = null },
                stream = stream.also { stream = null },
            )
        }
        runCatching { resources.timeout?.cancel() }
        resources.stream?.let(::releaseStream)
        runCatching {
            dispatcher.post { onResult(result) }
        }
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
) : VoskWakePhraseStream {
    private val transcriber = VoskStreamingTranscriber(
        context = context,
        modelDirectory = modelDirectory,
        isInputSuppressed = isInputSuppressed,
        listener = listener,
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

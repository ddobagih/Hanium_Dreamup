package kr.co.hanium.dreamup.walksafe.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.Closeable
import java.io.File
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState

internal data class HandsFreeVoiceEligibility(
    val walkState: WalkSessionState,
    val microphoneGranted: Boolean,
    val disclosureAccepted: Boolean,
    val modelReady: Boolean,
)

internal sealed interface HandsFreeVoiceTerminalFailure {
    val statusToken: String

    data class Transcriber(val error: VoskStreamingError) : HandsFreeVoiceTerminalFailure {
        override val statusToken: String = when (error) {
            VoskStreamingError.MODEL_UNAVAILABLE -> "model_unavailable"
            VoskStreamingError.MODEL_LOAD_FAILED -> "model_load_failed"
            VoskStreamingError.AUDIO_START_FAILED -> "audio_start_failed"
            VoskStreamingError.AUDIO_READ_FAILED -> "audio_read_failed"
            VoskStreamingError.INFERENCE_FAILED -> "inference_failed"
        }
    }

    data object CommandDispatchFailed : HandsFreeVoiceTerminalFailure {
        override val statusToken: String = "command_dispatch_failed"
    }

    data object OutputTimeout : HandsFreeVoiceTerminalFailure {
        override val statusToken: String = "output_timeout"
    }
}

internal object HandsFreeVoiceRunCallbackFence {
    fun acceptsReady(closed: Boolean, startingRunId: Long?, callbackRunId: Long): Boolean =
        !closed && startingRunId == callbackRunId

    fun acceptsError(
        closed: Boolean,
        startingRunId: Long?,
        activeRunId: Long?,
        callbackRunId: Long,
    ): Boolean = !closed && (activeRunId ?: startingRunId) == callbackRunId
}

internal fun notifyHandsFreeVoiceTerminalFailure(
    failure: HandsFreeVoiceTerminalFailure,
    callback: (HandsFreeVoiceTerminalFailure) -> Unit,
) {
    runCatching { callback(failure) }
}

/** Binds the pure wake/command lifecycle to one continuous on-device Vosk stream. */
internal class HandsFreeVoiceController(
    context: Context,
    modelDirectory: File,
    private val eligibility: () -> HandsFreeVoiceEligibility,
    private val isAppSpeechActive: () -> Boolean,
    private val onCommand: (text: String, confidence: Float?) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onCommandListeningStarted: () -> Unit = {},
    private val onTerminalFailure: (HandsFreeVoiceTerminalFailure) -> Unit = {},
    private val onSessionEnded: () -> Unit = {},
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val wakeAcknowledgementPlayer: WakeAcknowledgementPlayer = WakeAcknowledgementPlayer(
        context,
        isAppSpeechActive,
        mainHandler,
    ),
) : WalkVoiceSessionController, Closeable {
    private val stateMachine = HandsFreeVoiceStateMachine()
    private var activeRunId: Long? = null
    private var startingRunId: Long? = null
    private var starting = false
    private var closed = false
    private var terminalFailureDelivered = false
    private var commandTimeout: Runnable? = null
    private var outputPoll: Runnable? = null
    private var outputObserved = false
    private var outputWaitStartedAtMs = 0L
    private var outputQuietSinceMs: Long? = null
    private var preserveStopStatus = false

    private val transcriber = VoskStreamingTranscriber(
        context = context.applicationContext,
        modelDirectory = modelDirectory,
        isInputSuppressed = ::shouldSuppressInput,
        listener = object : VoskStreamingListener {
            override fun onReady(runId: Long) {
                mainHandler.post { handleReady(runId) }
            }

            override fun onTranscript(runId: Long, transcript: VoskTranscript) {
                mainHandler.post { handleTranscript(runId, transcript) }
            }

            override fun onError(runId: Long, error: VoskStreamingError) {
                mainHandler.post { handleError(runId, error) }
            }
        },
    )

    override fun start(): Boolean {
        checkMainThread()
        if (closed) return false
        if (starting || transcriber.isRunning()) return true
        preserveStopStatus = false
        val current = eligibility()
        if (!current.allowsListening()) {
            endSessionWithoutRestart("eligibility")
            return false
        }
        activeRunId = null
        startingRunId = null
        starting = true
        terminalFailureDelivered = false
        onStatus("voice_hands_free=model_loading")
        val runReserved = transcriber.start { runId ->
            startingRunId = runId
        } != null
        if (!runReserved) {
            endSessionWithoutRestart("transcriber_start_rejected")
            return false
        }
        return starting || activeRunId != null
    }

    override fun stop() {
        checkMainThread()
        if (closed) return
        activeRunId = null
        startingRunId = null
        starting = false
        terminalFailureDelivered = true
        cancelScheduledWork()
        stateMachine.stop()
        transcriber.stop()
        if (!preserveStopStatus) onStatus("voice_hands_free=stopped")
    }

    override fun close() {
        checkMainThread()
        if (closed) return
        closed = true
        activeRunId = null
        startingRunId = null
        starting = false
        terminalFailureDelivered = true
        cancelScheduledWork()
        stateMachine.stop()
        transcriber.close()
        wakeAcknowledgementPlayer.close()
    }

    private fun handleReady(runId: Long) {
        if (!HandsFreeVoiceRunCallbackFence.acceptsReady(closed, startingRunId, runId)) return
        startingRunId = null
        starting = false
        val current = eligibility()
        if (!current.allowsListening()) {
            endSessionWithoutRestart("eligibility_changed")
            return
        }
        activeRunId = runId
        val transition = stateMachine.startIfEligible(
            walkState = current.walkState,
            voiceConsentGranted = current.disclosureAccepted,
            modelsReady = current.modelReady,
        )
        if (transition.state !is HandsFreeVoiceState.WaitingForWakeWord) {
            endSessionWithoutRestart("state")
            return
        }
        onStatus("voice_hands_free=waiting_wake_phrase")
    }

    private fun handleTranscript(runId: Long, transcript: VoskTranscript) {
        if (closed || activeRunId != runId) return
        if (!eligibility().allowsListening()) {
            endSessionWithoutRestart("eligibility_changed")
            return
        }
        if (!transcript.isFinal) return
        if (!transcript.isTrustedForCommand()) {
            onStatus("voice_hands_free=low_confidence")
            return
        }
        when (stateMachine.snapshot()) {
            is HandsFreeVoiceState.WaitingForWakeWord -> {
                when (
                    val extraction = WakePhraseCommandExtractor.extractFinalTranscript(
                        transcript.text,
                        WakePhraseCommandMode.WAKE_PHRASE_REQUIRED,
                    )
                ) {
                    WakePhraseCommandExtraction.NotAddressed -> Unit
                    WakePhraseCommandExtraction.AwaitingCommand -> {
                        acknowledgeWake(runId)
                    }
                    is WakePhraseCommandExtraction.Command -> {
                        acknowledgeWake(runId, extraction.text, transcript.confidence)
                    }
                }
            }
            is HandsFreeVoiceState.WaitingForCommand -> {
                when (
                    val extraction = WakePhraseCommandExtractor.extractFinalTranscript(
                        transcript.text,
                        WakePhraseCommandMode.COMMAND_AWAITED,
                    )
                ) {
                    WakePhraseCommandExtraction.NotAddressed,
                    WakePhraseCommandExtraction.AwaitingCommand,
                    -> Unit
                    is WakePhraseCommandExtraction.Command ->
                        dispatchCommand(extraction.text, transcript.confidence)
                }
            }
            else -> Unit
        }
    }

    private fun acknowledgeWake(runId: Long, command: String? = null, confidence: Float? = null) {
        val transition = stateMachine.onWakeWordDetected(stateMachine.snapshot().generation)
        val acknowledging = transition.state as? HandsFreeVoiceState.AcknowledgingWake ?: return
        if (!transition.accepted) return
        onStatus("voice_hands_free=acknowledging_wake")
        if (closed || activeRunId != runId || stateMachine.snapshot() != acknowledging) return
        wakeAcknowledgementPlayer.play { result ->
            if (closed || terminalFailureDelivered || activeRunId != runId ||
                stateMachine.snapshot() != acknowledging
            ) return@play
            if (!eligibility().allowsListening()) {
                endSessionWithoutRestart("eligibility_changed")
                return@play
            }
            if (result == WakeAcknowledgementResult.CANCELLED ||
                runCatching(isAppSpeechActive).getOrDefault(true)
            ) {
                stateMachine.cancel(acknowledging.generation)
                onStatus("voice_hands_free=waiting_wake_phrase")
                return@play
            }
            if (result == WakeAcknowledgementResult.FAILED) {
                onStatus("voice_hands_free=wake_acknowledgement_failed")
            }
            openCommandWindow(acknowledging.generation, runId, command, confidence)
        }
    }

    private fun openCommandWindow(generation: Long, runId: Long, command: String?, confidence: Float?) {
        val transition = stateMachine.onWakeAcknowledgementFinished(generation)
        if (!transition.accepted || transition.state !is HandsFreeVoiceState.WaitingForCommand) return
        runCatching(onCommandListeningStarted)
        // A page update can synchronously cancel this owner; it must not revive the microphone.
        if (closed || activeRunId != runId || stateMachine.snapshot() != transition.state) return
        if (!eligibility().allowsListening()) {
            endSessionWithoutRestart("eligibility_changed")
            return
        }
        scheduleCommandTimeout(transition.state.generation)
        onStatus("voice_hands_free=waiting_command")
        if (command != null) dispatchCommand(command, confidence)
    }

    private fun dispatchCommand(text: String, confidence: Float?) {
        val transition = stateMachine.onCommandRecognized(
            callbackGeneration = stateMachine.snapshot().generation,
            command = text,
        )
        val processing = transition.state as? HandsFreeVoiceState.ProcessingCommand ?: return
        commandTimeout?.let(mainHandler::removeCallbacks)
        commandTimeout = null
        onStatus("voice_hands_free=processing_command")
        try {
            onCommand(processing.command, confidence)
        } catch (_: RuntimeException) {
            failClosed(
                processing.generation,
                HandsFreeVoiceTerminalFailure.CommandDispatchFailed,
            )
            return
        }
        if (!eligibility().allowsListening()) {
            endSessionWithoutRestart("eligibility_changed")
            return
        }
        val speaking = stateMachine.onCommandDispatched(processing.generation)
        if (speaking.state !is HandsFreeVoiceState.Speaking) return
        waitForOutputToSettle(speaking.state.generation)
    }

    private fun scheduleCommandTimeout(generation: Long) {
        commandTimeout?.let(mainHandler::removeCallbacks)
        lateinit var timeout: Runnable
        timeout = Runnable {
            if (commandTimeout !== timeout) return@Runnable
            commandTimeout = null
            val transition = stateMachine.onCommandWindowTimedOut(generation)
            if (transition.accepted) {
                onStatus("voice_hands_free=command_timeout")
            }
        }
        commandTimeout = timeout
        mainHandler.postDelayed(timeout, COMMAND_WINDOW_MS)
    }

    private fun waitForOutputToSettle(generation: Long) {
        outputPoll?.let(mainHandler::removeCallbacks)
        outputObserved = false
        outputWaitStartedAtMs = SystemClock.elapsedRealtime()
        outputQuietSinceMs = null
        lateinit var poll: Runnable
        poll = Runnable {
            if (outputPoll !== poll) return@Runnable
            val state = stateMachine.snapshot()
            if (state !is HandsFreeVoiceState.Speaking || state.generation != generation) {
                outputPoll = null
                return@Runnable
            }
            val nowMs = SystemClock.elapsedRealtime()
            val outputActive = runCatching(isAppSpeechActive).getOrDefault(true)
            if (outputActive) {
                outputObserved = true
                outputQuietSinceMs = null
            } else if (outputObserved) {
                val quietSince = outputQuietSinceMs ?: nowMs.also { outputQuietSinceMs = it }
                if (nowMs - quietSince >= OUTPUT_QUIET_MS) {
                    finishOutput(generation)
                    return@Runnable
                }
            } else if (nowMs - outputWaitStartedAtMs >= NO_OUTPUT_SETTLE_MS) {
                finishOutput(generation)
                return@Runnable
            }
            if (nowMs - outputWaitStartedAtMs >= MAX_OUTPUT_WAIT_MS) {
                failClosed(generation, HandsFreeVoiceTerminalFailure.OutputTimeout)
                return@Runnable
            }
            mainHandler.postDelayed(poll, OUTPUT_POLL_MS)
        }
        outputPoll = poll
        mainHandler.post(poll)
    }

    private fun finishOutput(generation: Long) {
        outputPoll = null
        val transition = stateMachine.onTtsFinished(generation)
        if (transition.accepted) {
            onStatus("voice_hands_free=waiting_wake_phrase")
        }
    }

    private fun handleError(runId: Long, error: VoskStreamingError) {
        if (
            !HandsFreeVoiceRunCallbackFence.acceptsError(
                closed = closed,
                startingRunId = startingRunId,
                activeRunId = activeRunId,
                callbackRunId = runId,
            ) || terminalFailureDelivered
        ) return
        terminalFailureDelivered = true
        activeRunId = null
        startingRunId = null
        starting = false
        cancelScheduledWork()
        val generation = stateMachine.snapshot().generation
        stateMachine.onError(generation)
        transcriber.stop()
        preserveStopStatus = true
        val failure = HandsFreeVoiceTerminalFailure.Transcriber(error)
        onStatus("voice_hands_free=error_${failure.statusToken}")
        notifyHandsFreeVoiceTerminalFailure(failure, onTerminalFailure)
    }

    private fun failClosed(generation: Long, failure: HandsFreeVoiceTerminalFailure) {
        if (terminalFailureDelivered) return
        terminalFailureDelivered = true
        cancelScheduledWork()
        stateMachine.onError(generation)
        activeRunId = null
        startingRunId = null
        starting = false
        transcriber.stop()
        preserveStopStatus = true
        onStatus("voice_hands_free=error_${failure.statusToken}")
        notifyHandsFreeVoiceTerminalFailure(failure, onTerminalFailure)
    }

    private fun shouldSuppressInput(): Boolean {
        if (closed || runCatching(isAppSpeechActive).getOrDefault(true)) return true
        return when (stateMachine.snapshot()) {
            is HandsFreeVoiceState.WaitingForWakeWord,
            is HandsFreeVoiceState.WaitingForCommand,
            -> false
            else -> true
        }
    }

    private fun cancelScheduledWork() {
        commandTimeout?.let(mainHandler::removeCallbacks)
        outputPoll?.let(mainHandler::removeCallbacks)
        commandTimeout = null
        outputPoll = null
        wakeAcknowledgementPlayer.cancel()
    }

    private fun endSessionWithoutRestart(reason: String) {
        activeRunId = null
        startingRunId = null
        starting = false
        terminalFailureDelivered = true
        cancelScheduledWork()
        stateMachine.stop()
        transcriber.stop()
        preserveStopStatus = true
        onStatus("voice_hands_free=blocked $reason")
        runCatching(onSessionEnded)
    }

    private fun HandsFreeVoiceEligibility.allowsListening(): Boolean =
        walkState == WalkSessionState.ACTIVE &&
            microphoneGranted &&
            disclosureAccepted &&
            modelReady

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "HandsFreeVoiceController must be called on the main thread"
        }
    }

    private companion object {
        const val COMMAND_WINDOW_MS = 6_000L
        const val OUTPUT_POLL_MS = 100L
        const val OUTPUT_QUIET_MS = 500L
        const val NO_OUTPUT_SETTLE_MS = 1_200L
        const val MAX_OUTPUT_WAIT_MS = 30_000L
    }
}

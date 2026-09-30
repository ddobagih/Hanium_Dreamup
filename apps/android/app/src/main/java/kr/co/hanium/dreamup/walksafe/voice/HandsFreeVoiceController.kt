package kr.co.hanium.dreamup.walksafe.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
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
            VoskStreamingError.AUDIO_SILENCED -> "audio_input_silenced"
            VoskStreamingError.INFERENCE_FAILED -> "inference_failed"
        }
    }

    data object CommandDispatchFailed : HandsFreeVoiceTerminalFailure {
        override val statusToken: String = "command_dispatch_failed"
    }

    data object OutputTimeout : HandsFreeVoiceTerminalFailure {
        override val statusToken: String = "output_timeout"
    }

    /** S04-11: the asynchronous work a command started never reported completion, failure or cancellation. */
    data object CommandResultTimeout : HandsFreeVoiceTerminalFailure {
        override val statusToken: String = "command_result_timeout"
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
    // S04-32: output-wait and decoder failures use the same typed, DEBUG-only stage channel.
    voiceRequestStageSummaryOrNull(failure)?.let(::logVoiceRequestStageSummary)
    runCatching { callback(failure) }
}

/** R18 (S04-19, FP-026): the one short re-ask after a hands-free command rejected for low confidence. */
internal const val HANDS_FREE_LOW_CONFIDENCE_REPROMPT_KO = "다시 말씀해 주세요."

internal interface HandsFreeStreamingPort : Closeable {
    fun start(onRunReserved: (Long) -> Unit): Long?
    fun isRunning(): Boolean
    fun stop()
}

internal data class HandsFreeVoiceDependencies(
    val runtime: VoiceCallbackRuntime,
    val createStream: (
        listener: VoskStreamingListener,
        isInputSuppressed: () -> Boolean,
        inputGeneration: () -> Long,
    ) -> HandsFreeStreamingPort,
    val playAcknowledgement: (callback: (WakeAcknowledgementResult) -> Unit) -> Unit,
    val cancelAcknowledgement: () -> Unit,
    val closeAcknowledgement: () -> Unit,
    /**
     * R18 (S04-19, FP-026 "no-op 후 재질문"): plays the short spoken re-ask ("다시 말씀해 주세요") after a
     * command was rejected for low confidence and reports when it has ended. Null plays the wake cue.
     */
    val playLowConfidenceReprompt: ((callback: (WakeAcknowledgementResult) -> Unit) -> Unit)? = null,
)

/** Binds the pure wake/command lifecycle to one continuous on-device Vosk stream. */
internal class HandsFreeVoiceController internal constructor(
    private val dependencies: HandsFreeVoiceDependencies,
    private val eligibility: () -> HandsFreeVoiceEligibility,
    private val isAppSpeechActive: () -> Boolean,
    private val onCommand: (text: String, confidence: Float?) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onCommandListeningStarted: () -> Unit = {},
    private val onTerminalFailure: (HandsFreeVoiceTerminalFailure) -> Unit = {},
    private val onSessionEnded: () -> Unit = {},
    private val isSpeechRecognitionBlocked: () -> Boolean = isAppSpeechActive,
    /**
     * S04-11/S05-28: true while asynchronous work started by command [commandId] (the value of
     * [dispatchingCommandId] during its [onCommand] call) has not completed, failed or been cancelled.
     * "No speech right now" is not "command finished": wake listening waits until this turns false.
     */
    private val isCommandWorkPending: (commandId: Long) -> Boolean = { false },
) : WalkVoiceSessionController, Closeable {
    constructor(
        context: Context,
        modelDirectory: File,
        eligibility: () -> HandsFreeVoiceEligibility,
        isAppSpeechActive: () -> Boolean,
        onCommand: (text: String, confidence: Float?) -> Unit,
        onStatus: (String) -> Unit,
        onCommandListeningStarted: () -> Unit = {},
        onTerminalFailure: (HandsFreeVoiceTerminalFailure) -> Unit = {},
        onSessionEnded: () -> Unit = {},
        mainHandler: Handler = Handler(Looper.getMainLooper()),
        wakeAcknowledgementPlayer: WakeAcknowledgementPlayer = WakeAcknowledgementPlayer(
            context,
            isAppSpeechActive,
            mainHandler,
        ),
        isSpeechRecognitionBlocked: () -> Boolean = isAppSpeechActive,
        isCommandWorkPending: (commandId: Long) -> Boolean = { false },
        speakLowConfidenceReprompt: ((onFinished: (WakeAcknowledgementResult) -> Unit) -> Unit)? = null,
    ) : this(
        dependencies = HandsFreeVoiceDependencies(
            runtime = HandlerVoiceCallbackRuntime(mainHandler),
            createStream = { listener, isInputSuppressed, inputGeneration ->
                val delegate = VoskStreamingTranscriber(
                    context = context.applicationContext,
                    modelDirectory = modelDirectory,
                    isInputSuppressed = isInputSuppressed,
                    listener = listener,
                    inputGeneration = inputGeneration,
                )
                object : HandsFreeStreamingPort {
                    override fun start(onRunReserved: (Long) -> Unit): Long? =
                        delegate.start { runId -> onRunReserved(runId) }

                    override fun isRunning(): Boolean = delegate.isRunning()

                    override fun stop() {
                        delegate.stop()
                    }

                    override fun close() {
                        delegate.close()
                    }
                }
            },
            playAcknowledgement = { callback ->
                wakeAcknowledgementPlayer.play { result -> callback(result) }
            },
            cancelAcknowledgement = { wakeAcknowledgementPlayer.cancel() },
            closeAcknowledgement = { wakeAcknowledgementPlayer.close() },
            playLowConfidenceReprompt = speakLowConfidenceReprompt,
        ),
        eligibility = eligibility,
        isAppSpeechActive = isAppSpeechActive,
        onCommand = onCommand,
        onStatus = onStatus,
        onCommandListeningStarted = onCommandListeningStarted,
        onTerminalFailure = onTerminalFailure,
        onSessionEnded = onSessionEnded,
        isSpeechRecognitionBlocked = isSpeechRecognitionBlocked,
        isCommandWorkPending = isCommandWorkPending,
    )

    private val stateMachine = HandsFreeVoiceStateMachine()
    private var activeRunId: Long? = null
    private var startingRunId: Long? = null
    private var starting = false
    private var closed = false
    private var terminalFailureDelivered = false
    private var commandTimeout: Runnable? = null
    private var modelLoadTimeout: Runnable? = null
    private var eligibilityPoll: Runnable? = null
    private var commandSpeechStarted = false
    private var commandAudioEnded = false
    private var outputPoll: Runnable? = null
    private var outputObserved = false
    private var outputWaitStartedAtMs = 0L
    private var outputQuietSinceMs: Long? = null
    private var outputActiveAtLastPoll = false
    private var outputProgressAtMs = 0L
    private var preserveStopStatus = false
    /** R18: the open command window already followed one low-confidence re-ask. */
    private var lowConfidenceRepromptSpent = false
    private var repromptSettlePoll: Runnable? = null

    private val transcriber = dependencies.createStream(
        object : VoskStreamingListener {
            override fun onReady(runId: Long) {
                dependencies.runtime.post { handleReady(runId) }
            }

            override fun onTranscript(runId: Long, transcript: VoskTranscript) {
                onTranscript(runId, transcript, inputGeneration = null)
            }

            override fun onTranscript(runId: Long, transcript: VoskTranscript, inputGeneration: Long?) {
                dependencies.runtime.post { handleTranscript(runId, transcript, inputGeneration) }
            }

            override fun onAudioEnd(runId: Long, inputGeneration: Long?) {
                dependencies.runtime.post { handleAudioEnd(runId, inputGeneration) }
            }

            override fun onError(runId: Long, error: VoskStreamingError) {
                dependencies.runtime.post { handleError(runId, error) }
            }
        },
        ::shouldSuppressInput,
        { stateMachine.snapshot().generation },
    )

    override fun start(): Boolean {
        checkMainThread()
        if (closed) return false
        preserveStopStatus = false
        val current = eligibility()
        if (!current.allowsListening()) {
            endSessionWithoutRestart("eligibility")
            return false
        }
        if (starting || transcriber.isRunning()) return true
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
        val expectedRunId = startingRunId
        if (starting && expectedRunId != null) {
            val watchdog = Runnable {
                if (HandsFreeVoiceRunCallbackFence.acceptsReady(closed, startingRunId, expectedRunId)) {
                    handleError(expectedRunId, VoskStreamingError.MODEL_LOAD_FAILED)
                }
            }
            modelLoadTimeout = watchdog
            dependencies.runtime.postDelayed(watchdog, MODEL_LOAD_TIMEOUT_MS)
        }
        monitorEligibility()
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
        dependencies.closeAcknowledgement()
    }

    private fun handleReady(runId: Long) {
        if (!HandsFreeVoiceRunCallbackFence.acceptsReady(closed, startingRunId, runId)) return
        modelLoadTimeout?.let(dependencies.runtime::removeCallbacks)
        modelLoadTimeout = null
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

    private fun handleAudioEnd(runId: Long, inputGeneration: Long?) {
        if (closed || activeRunId != runId) return
        if (!eligibility().allowsListening()) {
            endSessionWithoutRestart("eligibility_changed")
            return
        }
        val state = stateMachine.snapshot()
        if (state !is HandsFreeVoiceState.WaitingForCommand ||
            inputGeneration != state.generation || !commandSpeechStarted || commandAudioEnded ||
            shouldSuppressInput()
        ) return
        commandAudioEnded = true
        scheduleCommandTimeout(state.generation, COMMAND_FINAL_RESULT_TIMEOUT_MS)
    }

    private fun handleTranscript(runId: Long, transcript: VoskTranscript, inputGeneration: Long?) {
        if (closed || activeRunId != runId) return
        if (!eligibility().allowsListening()) {
            endSessionWithoutRestart("eligibility_changed")
            return
        }
        // PCM can finish decoding before a TTS starts, then wait on the main queue. A run ID
        // alone also cannot distinguish a previous wake/command window in the same stream.
        if (inputGeneration != stateMachine.snapshot().generation || shouldSuppressInput()) return
        if (!transcript.isFinal) {
            val state = stateMachine.snapshot()
            if (state is HandsFreeVoiceState.WaitingForCommand && !commandSpeechStarted && transcript.text.isNotBlank()) {
                commandSpeechStarted = true
                scheduleCommandTimeout(state.generation, COMMAND_UTTERANCE_TIMEOUT_MS)
            }
            return
        }
        if (!transcript.isTrustedForCommand()) {
            val state = stateMachine.snapshot()
            // Per-word scores can show that an addressed command is clear even if a very doubtful
            // wake word pulls the whole-utterance mean below the threshold (or vice versa). Neither
            // span may execute, but one clear span warrants the single spoken recovery prompt.
            if (state is HandsFreeVoiceState.WaitingForWakeWord ||
                state is HandsFreeVoiceState.WaitingForCommandResult
            ) {
                val extraction = WakePhraseCommandExtractor.extractFinalTranscript(
                    transcript.text,
                    WakePhraseCommandMode.WAKE_PHRASE_REQUIRED,
                )
                if (extraction is WakePhraseCommandExtraction.Command) {
                    val clearWake = transcript.hasTrustedWakeWordsForReprompt(extraction.text)
                    val clearCommand = transcript.hasTrustedCommandWordsForReprompt(extraction.text)
                    if (clearWake || clearCommand) {
                        onStatus(if (clearWake) "voice_hands_free=low_confidence_command" else "voice_hands_free=low_confidence_wake")
                        repromptLowConfidence(runId)
                        return
                    }
                }
            }
            onStatus("voice_hands_free=low_confidence")
            // R18: a doubtful final in the open command window is asked for again once; while only the
            // wake phrase is awaited, unaddressed low-confidence audio stays silent.
            if (state is HandsFreeVoiceState.WaitingForCommand) handleLowConfidenceInCommandWindow(runId, state)
            return
        }
        when (stateMachine.snapshot()) {
            // S04-11: while a command's result is awaited in silence, only the wake phrase is accepted.
            is HandsFreeVoiceState.WaitingForWakeWord,
            is HandsFreeVoiceState.WaitingForCommandResult,
            -> {
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
                        // A clear wake phrase must not carry a doubtful command (S05-23): open the
                        // command window instead, which asks for the command again.
                        val command = transcript.commandSpanOrNull(extraction.text)
                        if (!transcript.wakeSpanTrustedBefore(extraction.text)) {
                            // S04-19: nor may a clear command lift a doubtful wake phrase.
                            onStatus("voice_hands_free=low_confidence_wake")
                            // R18: only the wake phrase was doubtful - neither acted on, but asked again
                            // once with the command window open. Both doubtful stays silent.
                            if (command != null) repromptLowConfidence(runId)
                        } else if (command == null) {
                            onStatus("voice_hands_free=low_confidence_command")
                            // R18: the re-ask is spoken (not only the wake cue) and counts as the one re-ask.
                            repromptLowConfidence(runId)
                        } else {
                            acknowledgeWake(runId, command.text, command.confidence)
                        }
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
                    is WakePhraseCommandExtraction.Command -> {
                        val command = transcript.commandSpanOrNull(extraction.text)
                        if (command == null) {
                            onStatus("voice_hands_free=low_confidence")
                            (stateMachine.snapshot() as? HandsFreeVoiceState.WaitingForCommand)
                                ?.let { waiting -> handleLowConfidenceInCommandWindow(runId, waiting) }
                        } else {
                            dispatchCommand(command.text, command.confidence)
                        }
                    }
                }
            }
            else -> Unit
        }
    }

    /**
     * R18 (S04-19, FP-026 failure_behavior "한 번에 하나씩 다시 묻는다"): the first doubtful command in a
     * row is asked for again with a fresh window; a second one in a row closes the window without
     * another re-ask, back to wake listening.
     */
    private fun handleLowConfidenceInCommandWindow(runId: Long, state: HandsFreeVoiceState.WaitingForCommand) {
        if (!lowConfidenceRepromptSpent) {
            repromptLowConfidence(runId)
            return
        }
        lowConfidenceRepromptSpent = false
        commandTimeout?.let(dependencies.runtime::removeCallbacks)
        commandTimeout = null
        // Same transition as the window's own end: WaitingForCommand -> wake listening.
        val transition = stateMachine.onCommandWindowTimedOut(state.generation)
        if (transition.accepted) {
            onStatus("voice_hands_free=low_confidence_window_closed")
            onStatus("voice_hands_free=waiting_wake_phrase")
        }
    }

    /**
     * R18: nothing is acted on; one short re-ask plays with input suppressed, then the command window
     * opens again with a new timeout. Used for a doubtful command in the window and for a wake
     * utterance whose command or (only) wake phrase was doubtful.
     */
    private fun repromptLowConfidence(runId: Long) {
        val current = stateMachine.snapshot()
        val awaitingCommandResult = current is HandsFreeVoiceState.WaitingForCommandResult
        val transition = if (current is HandsFreeVoiceState.WaitingForCommand) {
            stateMachine.onCommandRepromptRequested(current.generation)
        } else {
            stateMachine.onWakeWordDetected(current.generation)
        }
        val reprompting = transition.state as? HandsFreeVoiceState.AcknowledgingWake ?: return
        if (!transition.accepted) return
        lowConfidenceRepromptSpent = true
        commandTimeout?.let(dependencies.runtime::removeCallbacks)
        commandTimeout = null
        if (awaitingCommandResult) {
            outputPoll?.let(dependencies.runtime::removeCallbacks)
            outputPoll = null
            onStatus("voice_hands_free=command_result_wait_interrupted_by_wake")
        }
        onStatus("voice_hands_free=low_confidence_reprompt")
        if (closed || activeRunId != runId || stateMachine.snapshot() != reprompting) return
        val play = dependencies.playLowConfidenceReprompt ?: dependencies.playAcknowledgement
        play reprompt@ { result ->
            if (closed || terminalFailureDelivered || activeRunId != runId ||
                stateMachine.snapshot() != reprompting
            ) return@reprompt
            if (!eligibility().allowsListening()) {
                endSessionWithoutRestart("eligibility_changed")
                return@reprompt
            }
            if (result == WakeAcknowledgementResult.CANCELLED) {
                lowConfidenceRepromptSpent = false
                stateMachine.cancel(reprompting.generation)
                onStatus("voice_hands_free=waiting_wake_phrase")
                return@reprompt
            }
            if (result == WakeAcknowledgementResult.FAILED) {
                onStatus("voice_hands_free=low_confidence_reprompt_failed")
            }
            reopenCommandWindowAfterReprompt(reprompting.generation, runId)
        }
    }

    /** The re-ask itself blocks recognition until its speaker tail ends; open the window after it, bounded. */
    private fun reopenCommandWindowAfterReprompt(generation: Long, runId: Long) {
        repromptSettlePoll?.let(dependencies.runtime::removeCallbacks)
        val startedAtMs = dependencies.runtime.nowMs()
        lateinit var poll: Runnable
        poll = Runnable {
            if (repromptSettlePoll !== poll) return@Runnable
            val state = stateMachine.snapshot()
            if (closed || terminalFailureDelivered || activeRunId != runId ||
                state !is HandsFreeVoiceState.AcknowledgingWake || state.generation != generation
            ) {
                repromptSettlePoll = null
                return@Runnable
            }
            if (!runCatching(isSpeechRecognitionBlocked).getOrDefault(true)) {
                repromptSettlePoll = null
                openCommandWindow(generation, runId, command = null, confidence = null)
                return@Runnable
            }
            if (dependencies.runtime.nowMs() - startedAtMs >= REPROMPT_SETTLE_TIMEOUT_MS) {
                repromptSettlePoll = null
                lowConfidenceRepromptSpent = false
                stateMachine.cancel(generation)
                onStatus("voice_hands_free=waiting_wake_phrase")
                return@Runnable
            }
            dependencies.runtime.postDelayed(poll, OUTPUT_POLL_MS)
        }
        repromptSettlePoll = poll
        poll.run()
    }

    private fun acknowledgeWake(runId: Long, command: String? = null, confidence: Float? = null) {
        val awaitingCommandResult =
            stateMachine.snapshot() is HandsFreeVoiceState.WaitingForCommandResult
        val transition = stateMachine.onWakeWordDetected(stateMachine.snapshot().generation)
        val acknowledging = transition.state as? HandsFreeVoiceState.AcknowledgingWake ?: return
        if (!transition.accepted) return
        // R18: a clearly heard wake phrase starts a new exchange with its own one re-ask.
        lowConfidenceRepromptSpent = false
        if (awaitingCommandResult) {
            // S04-11: the user called again before the earlier command's result; the new command
            // wins. The earlier result is still spoken by its owner if it arrives.
            outputPoll?.let(dependencies.runtime::removeCallbacks)
            outputPoll = null
            onStatus("voice_hands_free=command_result_wait_interrupted_by_wake")
        }
        onStatus("voice_hands_free=acknowledging_wake")
        if (closed || activeRunId != runId || stateMachine.snapshot() != acknowledging) return
        dependencies.playAcknowledgement acknowledgement@ { result ->
            if (closed || terminalFailureDelivered || activeRunId != runId ||
                stateMachine.snapshot() != acknowledging
            ) return@acknowledgement
            if (!eligibility().allowsListening()) {
                endSessionWithoutRestart("eligibility_changed")
                return@acknowledgement
            }
            if (result == WakeAcknowledgementResult.CANCELLED ||
                runCatching(isSpeechRecognitionBlocked).getOrDefault(true)
            ) {
                stateMachine.cancel(acknowledging.generation)
                onStatus("voice_hands_free=waiting_wake_phrase")
                return@acknowledgement
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
        commandSpeechStarted = false
        commandAudioEnded = false
        scheduleCommandTimeout(transition.state.generation)
        onStatus("voice_hands_free=waiting_command")
        if (command != null) dispatchCommand(command, confidence)
    }

    private fun dispatchCommand(text: String, confidence: Float?) {
        if (shouldSuppressInput()) return
        val transition = stateMachine.onCommandRecognized(
            callbackGeneration = stateMachine.snapshot().generation,
            command = text,
        )
        val processing = transition.state as? HandsFreeVoiceState.ProcessingCommand ?: return
        commandTimeout?.let(dependencies.runtime::removeCallbacks)
        commandTimeout = null
        lowConfidenceRepromptSpent = false
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
        waitForOutputToSettle(speaking.state.generation, commandId = processing.generation)
    }

    /** S04-11: id of the command whose [onCommand] call is running now; null outside that call. */
    internal fun dispatchingCommandId(): Long? =
        (stateMachine.snapshot() as? HandsFreeVoiceState.ProcessingCommand)?.generation

    private fun scheduleCommandTimeout(generation: Long, timeoutMs: Long = COMMAND_WINDOW_MS) {
        commandTimeout?.let(dependencies.runtime::removeCallbacks)
        lateinit var timeout: Runnable
        timeout = Runnable {
            if (commandTimeout !== timeout) return@Runnable
            commandTimeout = null
            val transition = stateMachine.onCommandWindowTimedOut(generation)
            if (transition.accepted) {
                lowConfidenceRepromptSpent = false
                onStatus("voice_hands_free=command_timeout")
                // The state machine is already back at wake listening; report it like every other return.
                onStatus("voice_hands_free=waiting_wake_phrase")
            }
        }
        commandTimeout = timeout
        dependencies.runtime.postDelayed(timeout, timeoutMs)
    }

    private fun monitorEligibility() {
        eligibilityPoll?.let(dependencies.runtime::removeCallbacks)
        lateinit var poll: Runnable
        poll = Runnable {
            if (eligibilityPoll !== poll || closed || (!starting && activeRunId == null)) return@Runnable
            val allowsListening =
                runCatching { eligibility().allowsListening() }.getOrDefault(false)
            if (eligibilityPoll !== poll || closed || (!starting && activeRunId == null)) return@Runnable
            if (!allowsListening) {
                endSessionWithoutRestart("eligibility_changed")
                return@Runnable
            }
            val state = stateMachine.snapshot()
            if (state is HandsFreeVoiceState.WaitingForCommand &&
                runCatching(isSpeechRecognitionBlocked).getOrDefault(true)
            ) {
                commandTimeout?.let(dependencies.runtime::removeCallbacks)
                commandTimeout = null
                lowConfidenceRepromptSpent = false
                stateMachine.cancel(state.generation)
                onStatus("voice_hands_free=command_interrupted_by_output")
            }
            dependencies.runtime.postDelayed(poll, ELIGIBILITY_POLL_MS)
        }
        eligibilityPoll = poll
        dependencies.runtime.postDelayed(poll, ELIGIBILITY_POLL_MS)
    }

    private fun waitForOutputToSettle(generation: Long, commandId: Long) {
        outputPoll?.let(dependencies.runtime::removeCallbacks)
        val dispatchedAtMs = dependencies.runtime.nowMs()
        outputObserved = false
        outputWaitStartedAtMs = dispatchedAtMs
        outputQuietSinceMs = null
        outputActiveAtLastPoll = false
        outputProgressAtMs = dispatchedAtMs
        var commandWorkWasPending = false
        var workQuietSinceMs: Long? = null
        // S04-11: the wait moves between Speaking (output on, input suppressed) and
        // WaitingForCommandResult (silent, wake phrase accepted); each move is a new generation.
        var pollGeneration = generation
        lateinit var poll: Runnable
        poll = Runnable {
            if (outputPoll !== poll) return@Runnable
            val state = stateMachine.snapshot()
            val awaitingResult = state is HandsFreeVoiceState.WaitingForCommandResult
            if (
                (state !is HandsFreeVoiceState.Speaking && !awaitingResult) ||
                state.generation != pollGeneration
            ) {
                outputPoll = null
                return@Runnable
            }
            val nowMs = dependencies.runtime.nowMs()
            val outputActive = runCatching(isAppSpeechActive).getOrDefault(true)
            // S04-12/S05-29: a new utterance starting is progress; only output that stays on without
            // any new start beyond the feedback layer's own per-utterance terminal limit is a stall.
            if (outputActive && !outputActiveAtLastPoll) outputProgressAtMs = nowMs
            outputActiveAtLastPoll = outputActive
            if (awaitingResult && outputActive) {
                // The result (or another announcement) is playing: suppress input again.
                val speaking = stateMachine.onCommandResultOutputStarted(pollGeneration)
                val speakingState = speaking.state as? HandsFreeVoiceState.Speaking
                if (!speaking.accepted || speakingState == null) {
                    outputPoll = null
                    return@Runnable
                }
                pollGeneration = speakingState.generation
                onStatus("voice_hands_free=command_result_output")
            }
            // S04-11: unknown work state is treated as still pending (fail closed at the ceiling).
            val workPending = runCatching { isCommandWorkPending(commandId) }.getOrDefault(true)
            if (workPending) {
                commandWorkWasPending = true
                if (nowMs - dispatchedAtMs >= COMMAND_WORK_TIMEOUT_MS) {
                    failClosed(pollGeneration, HandsFreeVoiceTerminalFailure.CommandResultTimeout)
                    return@Runnable
                }
                if (outputActive) {
                    workQuietSinceMs = null
                } else {
                    val quietSince = workQuietSinceMs ?: nowMs.also { workQuietSinceMs = it }
                    if (
                        stateMachine.snapshot() is HandsFreeVoiceState.Speaking &&
                        nowMs - quietSince >= NO_OUTPUT_SETTLE_MS
                    ) {
                        // S04-11: pause/stop must not be unavailable for the whole request latency.
                        // The result stays bound to this command and fenced by its own generation.
                        val reopened = stateMachine.onCommandWorkSilent(pollGeneration)
                        val waitingState = reopened.state as? HandsFreeVoiceState.WaitingForCommandResult
                        if (reopened.accepted && waitingState != null) {
                            pollGeneration = waitingState.generation
                            onStatus("voice_hands_free=waiting_wake_phrase_command_pending")
                        }
                    }
                }
                dependencies.runtime.postDelayed(poll, OUTPUT_POLL_MS)
                return@Runnable
            }
            if (commandWorkWasPending) {
                // The command's own result has just arrived: judge its response from here, not
                // from the acknowledgement spoken before the work finished.
                commandWorkWasPending = false
                workQuietSinceMs = null
                outputObserved = false
                outputQuietSinceMs = null
                outputWaitStartedAtMs = nowMs
            }
            if (outputActive) {
                outputObserved = true
                outputQuietSinceMs = null
            } else if (outputObserved) {
                val quietSince = outputQuietSinceMs ?: nowMs.also { outputQuietSinceMs = it }
                if (nowMs - quietSince >= OUTPUT_QUIET_MS) {
                    finishOutput(pollGeneration)
                    return@Runnable
                }
            } else if (nowMs - outputWaitStartedAtMs >= NO_OUTPUT_SETTLE_MS) {
                finishOutput(pollGeneration)
                return@Runnable
            }
            if (outputActive && nowMs - outputProgressAtMs >= OUTPUT_STALL_TIMEOUT_MS) {
                failClosed(pollGeneration, HandsFreeVoiceTerminalFailure.OutputTimeout)
                return@Runnable
            }
            dependencies.runtime.postDelayed(poll, OUTPUT_POLL_MS)
        }
        outputPoll = poll
        dependencies.runtime.post(poll)
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
        if (closed || runCatching(isSpeechRecognitionBlocked).getOrDefault(true)) return true
        return when (stateMachine.snapshot()) {
            is HandsFreeVoiceState.WaitingForWakeWord,
            is HandsFreeVoiceState.WaitingForCommand,
            is HandsFreeVoiceState.WaitingForCommandResult,
            -> false
            else -> true
        }
    }

    private fun cancelScheduledWork() {
        modelLoadTimeout?.let(dependencies.runtime::removeCallbacks)
        eligibilityPoll?.let(dependencies.runtime::removeCallbacks)
        commandTimeout?.let(dependencies.runtime::removeCallbacks)
        outputPoll?.let(dependencies.runtime::removeCallbacks)
        repromptSettlePoll?.let(dependencies.runtime::removeCallbacks)
        commandTimeout = null
        outputPoll = null
        repromptSettlePoll = null
        lowConfidenceRepromptSpent = false
        modelLoadTimeout = null
        eligibilityPoll = null
        dependencies.cancelAcknowledgement()
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
        dependencies.runtime.checkMainThread("HandsFreeVoiceController")
    }

    private companion object {
        const val MODEL_LOAD_TIMEOUT_MS = 20_000L
        const val COMMAND_UTTERANCE_TIMEOUT_MS = 20_000L
        // A bounded decoder/main-queue handoff after the first utterance endpoint.
        const val COMMAND_FINAL_RESULT_TIMEOUT_MS = 1_200L
        const val ELIGIBILITY_POLL_MS = 250L
        const val COMMAND_WINDOW_MS = 6_000L
        // R18: the re-ask's speaker tail (or a TalkBack hold) must end before the window reopens.
        const val REPROMPT_SETTLE_TIMEOUT_MS = 4_000L
        const val OUTPUT_POLL_MS = 100L
        const val OUTPUT_QUIET_MS = 500L
        const val NO_OUTPUT_SETTLE_MS = 1_200L
        // Above feedback.utteranceTerminalTimeoutMs (at most 120 s after onStart): the actuator's
        // own watchdog ends a stuck utterance first; this only catches output flags that never clear.
        const val OUTPUT_STALL_TIMEOUT_MS = 130_000L
        // Covers route/search HTTP connect+read (4 s + 8 s) with margin. The wake phrase stays
        // accepted during the silent part of this wait (S04-11), so it bounds the result, not deafness.
        const val COMMAND_WORK_TIMEOUT_MS = 30_000L
    }
}

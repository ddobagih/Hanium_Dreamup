package kr.co.hanium.dreamup.walksafe.voice

import kr.co.hanium.dreamup.walksafe.session.WalkSessionState

internal sealed interface HandsFreeVoiceState {
    val generation: Long

    data class Stopped(override val generation: Long) : HandsFreeVoiceState

    data class WaitingForWakeWord(override val generation: Long) : HandsFreeVoiceState

    data class AcknowledgingWake(override val generation: Long) : HandsFreeVoiceState

    data class WaitingForCommand(override val generation: Long) : HandsFreeVoiceState

    data class ProcessingCommand(
        override val generation: Long,
        val command: String,
    ) : HandsFreeVoiceState

    data class Speaking(override val generation: Long) : HandsFreeVoiceState

    /**
     * S04-11: the dispatched command's own work (search, route request) is still pending and no
     * output plays. The command's result is still awaited, but the wake phrase is accepted so
     * "보행 일시정지" or "멈춰" are never unavailable for the whole request latency.
     */
    data class WaitingForCommandResult(override val generation: Long) : HandsFreeVoiceState
}

internal sealed interface HandsFreeVoiceEffect {
    data class StartWakeWordListening(val generation: Long) : HandsFreeVoiceEffect

    data class AcknowledgeWake(val generation: Long) : HandsFreeVoiceEffect

    data class StartCommandWindow(val generation: Long) : HandsFreeVoiceEffect

    data class ProcessCommand(
        val generation: Long,
        val command: String,
    ) : HandsFreeVoiceEffect

    data class Speak(
        val generation: Long,
        val text: String,
    ) : HandsFreeVoiceEffect

    data class StopAllVoiceActivity(val generation: Long) : HandsFreeVoiceEffect
}

internal data class HandsFreeVoiceTransition(
    val state: HandsFreeVoiceState,
    val effects: List<HandsFreeVoiceEffect> = emptyList(),
    val accepted: Boolean = true,
)

/** Pure lifecycle for wake word -> command -> handling -> TTS -> wake word listening. */
internal class HandsFreeVoiceStateMachine {
    private var generation = 0L
    private var current: HandsFreeVoiceState = HandsFreeVoiceState.Stopped(generation)

    @Synchronized
    fun snapshot(): HandsFreeVoiceState = current

    @Synchronized
    fun startIfEligible(
        walkState: WalkSessionState,
        voiceConsentGranted: Boolean,
        modelsReady: Boolean,
    ): HandsFreeVoiceTransition {
        if (
            walkState != WalkSessionState.ACTIVE ||
            !voiceConsentGranted ||
            !modelsReady
        ) {
            return if (current is HandsFreeVoiceState.Stopped) unchanged() else stop()
        }
        if (current !is HandsFreeVoiceState.Stopped) return unchanged()
        return waitForWakeWord()
    }

    @Synchronized
    fun onWakeWordDetected(callbackGeneration: Long): HandsFreeVoiceTransition {
        if (
            !isCurrent<HandsFreeVoiceState.WaitingForWakeWord>(callbackGeneration) &&
            !isCurrent<HandsFreeVoiceState.WaitingForCommandResult>(callbackGeneration)
        ) {
            return unchanged()
        }
        val nextGeneration = nextGeneration()
        current = HandsFreeVoiceState.AcknowledgingWake(nextGeneration)
        return changed(HandsFreeVoiceEffect.AcknowledgeWake(nextGeneration))
    }

    /**
     * R18 (FP-026 "no-op 후 재질문"): a command heard in the open window with too little confidence
     * is not acted on; the window is closed while one short re-ask plays, then opens again through
     * [onWakeAcknowledgementFinished] with a new generation (audio from before is fenced off).
     */
    @Synchronized
    fun onCommandRepromptRequested(callbackGeneration: Long): HandsFreeVoiceTransition {
        if (!isCurrent<HandsFreeVoiceState.WaitingForCommand>(callbackGeneration)) {
            return unchanged()
        }
        val nextGeneration = nextGeneration()
        current = HandsFreeVoiceState.AcknowledgingWake(nextGeneration)
        return changed(HandsFreeVoiceEffect.AcknowledgeWake(nextGeneration))
    }

    @Synchronized
    fun onWakeAcknowledgementFinished(callbackGeneration: Long): HandsFreeVoiceTransition {
        if (!isCurrent<HandsFreeVoiceState.AcknowledgingWake>(callbackGeneration)) {
            return unchanged()
        }
        val nextGeneration = nextGeneration()
        current = HandsFreeVoiceState.WaitingForCommand(nextGeneration)
        return changed(HandsFreeVoiceEffect.StartCommandWindow(nextGeneration))
    }

    @Synchronized
    fun onCommandRecognized(
        callbackGeneration: Long,
        command: String,
    ): HandsFreeVoiceTransition {
        if (
            !isCurrent<HandsFreeVoiceState.WaitingForCommand>(callbackGeneration) ||
            command.isBlank()
        ) {
            return unchanged()
        }
        val normalizedCommand = command.trim()
        val nextGeneration = nextGeneration()
        current = HandsFreeVoiceState.ProcessingCommand(nextGeneration, normalizedCommand)
        return changed(HandsFreeVoiceEffect.ProcessCommand(nextGeneration, normalizedCommand))
    }

    @Synchronized
    fun onCommandResponseReady(
        callbackGeneration: Long,
        spokenResponse: String,
    ): HandsFreeVoiceTransition {
        if (
            !isCurrent<HandsFreeVoiceState.ProcessingCommand>(callbackGeneration) ||
            spokenResponse.isBlank()
        ) {
            return unchanged()
        }
        val nextGeneration = nextGeneration()
        current = HandsFreeVoiceState.Speaking(nextGeneration)
        return changed(HandsFreeVoiceEffect.Speak(nextGeneration, spokenResponse.trim()))
    }

    /** The existing app command path owns its response speech; wait for that output to finish. */
    @Synchronized
    fun onCommandDispatched(callbackGeneration: Long): HandsFreeVoiceTransition {
        if (!isCurrent<HandsFreeVoiceState.ProcessingCommand>(callbackGeneration)) {
            return unchanged()
        }
        val nextGeneration = nextGeneration()
        current = HandsFreeVoiceState.Speaking(nextGeneration)
        return HandsFreeVoiceTransition(state = current)
    }

    @Synchronized
    fun onTtsFinished(callbackGeneration: Long): HandsFreeVoiceTransition {
        if (
            !isCurrent<HandsFreeVoiceState.Speaking>(callbackGeneration) &&
            !isCurrent<HandsFreeVoiceState.WaitingForCommandResult>(callbackGeneration)
        ) {
            return unchanged()
        }
        return waitForWakeWord()
    }

    /**
     * S04-11: the command's work is still pending and output has been quiet; accept the wake phrase
     * while the result is awaited. A new generation fences audio captured before this point.
     */
    @Synchronized
    fun onCommandWorkSilent(callbackGeneration: Long): HandsFreeVoiceTransition {
        if (!isCurrent<HandsFreeVoiceState.Speaking>(callbackGeneration)) return unchanged()
        val nextGeneration = nextGeneration()
        current = HandsFreeVoiceState.WaitingForCommandResult(nextGeneration)
        return HandsFreeVoiceTransition(state = current)
    }

    /**
     * S04-11: output started while the command result was awaited (the result itself or another
     * announcement). Input is suppressed again, and audio from the silent window is fenced off.
     */
    @Synchronized
    fun onCommandResultOutputStarted(callbackGeneration: Long): HandsFreeVoiceTransition {
        if (!isCurrent<HandsFreeVoiceState.WaitingForCommandResult>(callbackGeneration)) {
            return unchanged()
        }
        val nextGeneration = nextGeneration()
        current = HandsFreeVoiceState.Speaking(nextGeneration)
        return HandsFreeVoiceTransition(state = current)
    }

    @Synchronized
    fun onCommandWindowTimedOut(callbackGeneration: Long): HandsFreeVoiceTransition {
        if (!isCurrent<HandsFreeVoiceState.WaitingForCommand>(callbackGeneration)) {
            return unchanged()
        }
        return waitForWakeWord()
    }

    @Synchronized
    fun cancel(callbackGeneration: Long): HandsFreeVoiceTransition {
        if (
            current is HandsFreeVoiceState.Stopped ||
            current.generation != callbackGeneration
        ) {
            return unchanged()
        }
        val nextGeneration = nextGeneration()
        current = HandsFreeVoiceState.WaitingForWakeWord(nextGeneration)
        return HandsFreeVoiceTransition(
            state = current,
            effects = listOf(
                HandsFreeVoiceEffect.StopAllVoiceActivity(nextGeneration),
                HandsFreeVoiceEffect.StartWakeWordListening(nextGeneration),
            ),
        )
    }

    @Synchronized
    fun onError(callbackGeneration: Long): HandsFreeVoiceTransition {
        if (
            current is HandsFreeVoiceState.Stopped ||
            current.generation != callbackGeneration
        ) {
            return unchanged()
        }
        return stop()
    }

    /** State is invalidated before the caller attempts the fallible audio shutdown effect. */
    @Synchronized
    fun stop(): HandsFreeVoiceTransition {
        val nextGeneration = nextGeneration()
        current = HandsFreeVoiceState.Stopped(nextGeneration)
        return changed(HandsFreeVoiceEffect.StopAllVoiceActivity(nextGeneration))
    }

    private fun waitForWakeWord(): HandsFreeVoiceTransition {
        val nextGeneration = nextGeneration()
        current = HandsFreeVoiceState.WaitingForWakeWord(nextGeneration)
        return changed(HandsFreeVoiceEffect.StartWakeWordListening(nextGeneration))
    }

    private fun changed(effect: HandsFreeVoiceEffect) = HandsFreeVoiceTransition(
        state = current,
        effects = listOf(effect),
    )

    private fun unchanged() = HandsFreeVoiceTransition(
        state = current,
        accepted = false,
    )

    private fun nextGeneration(): Long {
        generation += 1L
        return generation
    }

    private inline fun <reified T : HandsFreeVoiceState> isCurrent(
        callbackGeneration: Long,
    ): Boolean = current is T && current.generation == callbackGeneration
}

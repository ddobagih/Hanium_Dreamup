package kr.co.hanium.dreamup.walksafe.voice.composition

import kr.co.hanium.dreamup.walksafe.navigation.session.RouteGuidanceController
import kr.co.hanium.dreamup.walksafe.voice.input.VoiceInputSessionController
import kr.co.hanium.dreamup.walksafe.voice.output.CommandResponseToken
import kr.co.hanium.dreamup.walksafe.voice.output.SpeechOutputController

/** Guidance uses the current input/output owners without creating another speech lifetime. */
internal class GuidanceSpeechPeerAdapter(
    private val output: () -> SpeechOutputController,
    private val input: () -> VoiceInputSessionController,
    private val canSpeakNavigation: (Long) -> Boolean,
    private val clearWaitingFailure: (() -> Boolean) -> Unit,
) : RouteGuidanceController.SpeechPort {
    override val commandGeneration get() = output().currentCommandGeneration()
    override val commandPending get() = output().hasPendingCommandResponse()
    override val validatingStarted get() = output().currentActuatorOrNull()?.validatingStartedSpeech == true
    override val statusText get() = output().currentActuatorOrNull()?.statusText()
    override fun busy() = guidanceVoiceInputActive(input) || input().isHandsFreeVoiceSpeechOutputActive()
    override fun outputBusy() = input().isHandsFreeVoiceSpeechOutputActive()
    override fun inputActive() = guidanceVoiceInputActive(input)
    override fun canSpeakNavigation(nowMs: Long) = canSpeakNavigation.invoke(nowMs)
    override fun cancelNavigation() { output().currentActuatorOrNull()?.cancelNavigationSpeech() }
    override fun cancelCommand() = output().cancelCommandSpeechResponse()
    override fun failCommand(generation: Long) {
        output().failRegisteredCommandResponse(CommandResponseToken("command-response-$generation", generation))
    }
    override fun submitNavigation(message: String, isInstructionStillCurrent: () -> Boolean,
        onCompleted: () -> Unit, onFailed: () -> Unit) = output().dispatchNavigationSpeech(
        message, onCompleted, onFailed, isInstructionStillCurrent,
    )
    override fun submitStatus(message: String, current: () -> Boolean, completed: () -> Unit,
        failed: (() -> Unit)?): Boolean =
        if (failed == null) output().speakCommandResponse(
            message, statusExplanation = true, additionalCurrent = current, onCompleted = completed,
        ) else output().speakCommandResponse(
            message, statusExplanation = true, additionalCurrent = current, onCompleted = completed,
            onFailed = failed, onCancelled = failed, onRetired = failed,
        )
    override fun submitInstalledStart(message: String, current: () -> Boolean, terminal: () -> Unit) =
        output().speakCommandResponse(
            message, statusExplanation = true, additionalCurrent = current,
            onCompleted = { clearWaitingFailure(current); terminal() },
            onFailed = terminal, onCancelled = terminal, onRetired = terminal,
        )
}

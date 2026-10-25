package kr.co.hanium.dreamup.walksafe.voice.output

import kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorDecisionToken
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.voice.CurrentStateSpeechLease

/** A response owns output callbacks; it does not own a microphone or destination dialog. */
internal data class CommandResponseToken(val responseId: String, val generation: Long)

internal data class SpeechOutputLifetime(val generation: Int, val foreground: Boolean)

internal data class CommandSpeechContext(
    val recognitionGeneration: Int,
    val actorId: String?,
    val gatewayGeneration: Long,
    val walkEpoch: WalkRuntimeEpoch?,
    val page: Enum<*>,
    val voiceInteractionPage: Boolean,
    val visibleResponse: Boolean,
    val recognitionActive: Boolean,
    val gatewayRecording: Boolean,
)

internal data class NavigationSpeechOwner(
    val epoch: WalkRuntimeEpoch?,
    val routeId: String?,
    val decisionToken: RouteNavigatorDecisionToken?,
)

/** Read-only facts needed by output admission; none exposes an Activity or mutable input state. */
internal interface SpeechOutputReader {
    fun lifetime(): SpeechOutputLifetime
    fun commandContext(): CommandSpeechContext
    fun navigationOwner(): NavigationSpeechOwner
    fun navigationOwnerIsCurrent(owner: NavigationSpeechOwner): Boolean
    fun homeFeatureContextAvailable(): Boolean
    fun stateSourceIsCurrent(source: CurrentStateSpeechLease): Boolean
    fun guidanceVoiceInputActive(): Boolean
    fun screenReaderActive(): Boolean
}

internal enum class CommandPromptEffect { PREPARING, ORDINARY_RESPONSE, CLEAR }

/** The input/dialog owner applies these effects in the existing command callback order. */
internal interface SpeechOutputInputEffects {
    fun invalidateHomeWakeInput()
    fun promptState(token: CommandResponseToken, effect: CommandPromptEffect)
    fun cancelDeferredInput()
    fun refreshHomeWakeListening()
    fun cancelHomeWakeListening()
    fun cancelCommandDialogs(token: CommandResponseToken)
}

internal enum class SpeechAnnouncementPriority { RISK, INTERACTION, NAVIGATION, ADVISORY }

/** Captures one View target. False means it was replaced or is no longer visible. */
internal fun interface SpeechAccessibilityTarget {
    fun presentIfCurrent(message: String, priority: SpeechAnnouncementPriority): Boolean
}

internal interface SpeechOutputPresentation {
    fun showLastResult(message: String)
    fun showVoiceStatus(message: String)
    fun refreshVoiceControls()
    fun navigationStatus(message: String)
    fun commandTerminalDiagnostic(completed: Boolean)
    fun accessibilityTargetOrNull(): SpeechAccessibilityTarget?
    fun canScheduleInteraction(): Boolean
    fun postInteraction(task: Runnable, delayMs: Long)
    fun removeInteraction(task: Runnable)
}

/** READY collaborators keep risk, navigation and capability state with their original owners. */
internal interface SpeechOutputReadyEffects {
    fun refreshReadyUi()
    fun reevaluateCurrentRisk(actuator: AndroidFeedbackActuator, lifecycleGeneration: Int, nowMs: Long)
    fun retryCurrentNavigation(nowMs: Long)
    fun offlineSpeechUnavailable(lifecycleGeneration: Int, actuator: AndroidFeedbackActuator)
    fun beforeCommandReplacement(previousGeneration: Long)
}

internal interface SpeechOutputStorageBinding {
    fun invalidate()
    fun refresh(actuator: AndroidFeedbackActuator)
}

/** Factory supplies Android context/capability checks without transferring their ownership. */
internal fun interface SpeechOutputActuatorFactory {
    fun create(onUnavailable: () -> Unit, onReady: () -> Unit): AndroidFeedbackActuator
}

/** The Android adapter retains UI dispatch, readiness Handler and retirement Handler semantics. */
internal interface SpeechOutputScheduler {
    fun elapsedRealtimeMs(): Long
    fun isMainThread(): Boolean
    fun runOnMain(action: () -> Unit)
    fun postReadiness(task: Runnable, delayMs: Long)
    fun removeReadiness(task: Runnable)
    fun postRetired(action: () -> Unit)
}

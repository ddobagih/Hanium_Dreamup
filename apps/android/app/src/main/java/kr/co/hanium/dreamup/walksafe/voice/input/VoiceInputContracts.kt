package kr.co.hanium.dreamup.walksafe.voice.input

import android.content.Intent
import android.speech.RecognitionListener
import java.io.File
import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupCapabilityDecision
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorDecisionToken
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.voice.*

internal enum class VoiceRecognitionPurpose { COMMAND, WALK_SESSION_RESUME, WALK_SESSION_TAKEOVER }

internal data class VoiceInputIdentity(val actorId: String?, val processGeneration: Long)
internal data class VoiceInputPage(
    val owner: Enum<*>,
    val voiceInteraction: Boolean,
    val voiceCommand: Boolean,
    val guidance: Boolean,
    val destinationSearch: Boolean,
    val pendingVoiceCommand: Boolean,
    val noPendingPageOrGuidance: Boolean,
)
internal data class VoiceInputConfirmation(
    val resumePromptPending: Boolean,
    val resumeToken: WalkSessionConfirmationToken?,
    val takeoverPromptPending: Boolean,
    val takeoverOperationId: String?,
    val takeoverStartToken: WalkSessionConfirmationToken?,
)
internal data class VoiceInputCommandWork(
    val searchGeneration: Int,
    val searchInFlight: Boolean,
    val routeGeneration: Int,
    val routeInFlight: Boolean,
    val locationGeneration: Long = 0L,
    val locationInFlight: Boolean = false,
)

/** Immutable result only; the consumer still owns parsing, confirmation and route effects. */
internal data class RecognizedVoiceInput(
    val phrases: List<String>,
    val confidenceScores: List<Float>?,
    val expectedNavigationDecisionToken: RouteNavigatorDecisionToken?,
    val includeRecognizedTextInStatus: Boolean = true,
    val source: String = "device_stt",
    val backend: OfflineSpeechEngine? = null,
    val hasAdditionalAlternatives: Boolean = false,
)

/** Current external facts. SDK calls, parsing and mutable state are not exposed here. */
internal interface VoiceInputEnvironment {
    fun identity(): VoiceInputIdentity
    fun page(): VoiceInputPage
    fun walkOrNull(): WalkSessionSnapshot?
    fun foreground(): Boolean
    fun feedbackLifetimeGeneration(): Int
    fun capabilityDecision(): WalkSafeStartupCapabilityDecision?
    fun capabilityAllows(decision: WalkSafeStartupCapabilityDecision, requirement: kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupRequirement): Boolean
    fun microphoneGranted(): Boolean
    fun notificationGranted(): Boolean
    fun handsFreeFeatureEnabled(): Boolean
    fun voiceGuidanceFeatureEnabled(): Boolean
    fun disclosureAccepted(): Boolean
    fun homeFeatureContextAvailable(): Boolean
    fun startupCapabilityConfirmed(): Boolean
    fun runtimeActive(): Boolean
    fun confirmation(): VoiceInputConfirmation
    fun commandWork(): VoiceInputCommandWork
    /** A lease-expiring query: invoke only where the original input owner captured a decision. */
    fun pendingNavigationDecisionToken(): RouteNavigatorDecisionToken?
    fun takeoverConflictAvailable(epoch: WalkRuntimeEpoch): Boolean
    fun serverCommandRecognitionEnabled(): Boolean = false
    fun walkAreaCommandAvailable(): Boolean = false
    fun walkWakeContextAvailable(): Boolean = false
}

internal interface VoiceInputCommandEffects {
    fun recognized(result: RecognizedVoiceInput)
    fun resumeRecognized(phrases: List<String>, confidenceScores: FloatArray? = null)
    fun takeoverRecognized(phrases: List<String>)
    fun resumeRecognizerNotStarted()
    fun cancelTakeover(epoch: WalkRuntimeEpoch, reason: String)
    fun clearWalkEndConfirmation()
    fun invalidateGuidanceStopConfirmation()
    fun commandInputMiss(error: Int)
    /** True when the existing destination owner showed its waiting status or spoke its page. */
    fun handleDestinationPromptIfPresent(): Boolean
}

internal interface VoiceInputPresentation {
    fun voiceStatus(message: String)
    fun navigationStatus(message: String)
    fun refreshControls(active: Boolean? = null)
    fun status(title: String, detail: String)
    fun lastResult(message: String)
    fun recognitionSignal(message: String)
    fun showCommandPage()
    fun canShowStatus(): Boolean
}

internal interface VoiceInputApplicationEffects {
    fun requireStartupCapabilityConfirmation(): Boolean
    fun permissionStateChanged(reason: String)
    fun showMicrophoneDenial()
    fun continueDeviceCheck()
    fun refreshCapabilityUi()
    fun limitPersistentHandsFreeRecognition()
}

internal interface VoiceInputOutput {
    fun commandResponsePending(): Boolean
    fun commandGeneration(): Long
    fun beginCommandSpeech(commandId: Long) = Unit
    fun commandSpeechRevision(commandId: Long): Long = 0L
    fun commandSpeechProgress(commandId: Long): CommandSpeechProgress = CommandSpeechProgress.NOT_TRACKED
    /** Excludes the wake ACK and gateway player, which have separate native owners. */
    fun nativeOrAccessibilitySpeechActive(): Boolean
    fun nativeRecognitionBlocked(): Boolean
    fun prepareForRecognition(): Boolean?
    fun canSpeakNavigation(nowMs: Long): Boolean
    fun speakInteraction(message: String): Boolean
    fun speakPreparingInput(message: String, onCompleted: () -> Unit, onFailed: () -> Unit): Boolean
    fun cancelCommandResponse()
    fun playListeningStarted()
    fun playListeningEnded()
    fun speakLowConfidenceReprompt(message: String, onCompleted: () -> Unit, onFailed: () -> Unit): Boolean
    fun screenReaderActive(): Boolean
}

internal interface VoiceInputScheduler {
    fun nowMs(): Long
    fun isMainThread(): Boolean
    fun runOnMain(action: () -> Unit)
    fun canScheduleRestart(): Boolean
    fun postRestart(task: Runnable, delayMs: Long)
    fun removeRestart(task: Runnable)
    fun postDeferred(task: Runnable, delayMs: Long)
    fun removeDeferred(task: Runnable)
}

/** The adapter delegates to the existing PreferredOfflineSpeechRecognizer. */
internal interface VoiceCommandRecognizer {
    fun setRecognitionListener(listener: RecognitionListener)
    fun startListening(intent: Intent, preferPlatform: Boolean, isRequestCurrent: () -> Boolean)
    fun cancel()
    fun destroy()
}

internal data class HandsFreeInputCallbacks(
    val eligibility: () -> HandsFreeVoiceEligibility,
    val speechActive: () -> Boolean,
    val command: (String, Float?) -> Unit,
    val status: (String) -> Unit,
    val listeningStarted: () -> Unit,
    val terminalFailure: (HandsFreeVoiceTerminalFailure) -> Unit,
    val sessionEnded: () -> Unit,
    val recognitionBlocked: () -> Boolean,
    val workPending: (Long) -> Boolean,
    val lowConfidenceReprompt: ((WakeAcknowledgementResult) -> Unit) -> Unit,
    val commandSpeechProgress: (Long) -> CommandSpeechProgress = { CommandSpeechProgress.NOT_TRACKED },
    val commandSpeechRevision: (Long) -> Long = { 0L },
)

internal interface VoiceInputPlatform {
    fun applicationAvailable(): Boolean
    fun modelAssetAvailable(): Boolean
    fun prepareInstalledModel(): File
    fun executeModelWork(action: () -> Unit)
    fun shutdownModelWorker()
    fun createRecognizer(modelDirectory: () -> File?): VoiceCommandRecognizer
    fun commandIntent(): Intent
    fun createHandsFree(modelDirectory: File, callbacks: HandsFreeInputCallbacks): HandsFreeVoiceController
    fun attachHandsFree(controller: HandsFreeVoiceController): Boolean
    fun detachHandsFree(controller: HandsFreeVoiceController)
    fun setUserStopListener(listener: ((WalkVoiceSessionController) -> Unit)?)
    fun isAttached(controller: HandsFreeVoiceController): Boolean
    fun userStoppedWalk(walkId: String): Boolean
    fun recordUserStop(walkId: String)
    fun registerStart(controller: HandsFreeVoiceController,
        onFailure: (Long, WalkVoiceSessionController, WalkVoiceStartFailure) -> Unit): Long?
    fun invalidateStart(requestId: Long, controller: HandsFreeVoiceController)
    fun invalidateStartOnSessionEnd(requestId: Long, controller: HandsFreeVoiceController): Boolean?
    fun reportStartFailure(requestId: Long, controller: HandsFreeVoiceController, failure: WalkVoiceStartFailure)
    fun startService(requestId: Long)
    fun stopService()
    fun inputHeldElsewhere(): Boolean
}

internal data class WakeInputState(
    val destroyed: Boolean,
    val recognitionActive: Boolean,
    val recognitionGeneration: Int,
    val promptPending: Boolean,
    val deferredPending: Boolean,
    val modelFailed: Boolean,
    val gatewayRecording: Boolean,
    val gatewayInteractionPending: Boolean,
)
internal interface ForegroundWakeSession {
    fun state(): WakeInputState
    fun homeCommandAvailable(): Boolean
    fun speechActive(): Boolean
    fun recognitionBlocked(): Boolean
    fun modelDirectoryOrNull(): File?
    fun stopHandsFree(): Boolean
    fun scheduleRestart()
    fun cueCompletedForCurrentRecognition()
    fun startCommandRecognition(): Boolean
    fun recognized(result: RecognizedVoiceInput)
}
internal interface WakeAcknowledgementEffect {
    fun isPlaying(): Boolean
    fun play(onFinished: (WakeAcknowledgementResult) -> Unit)
    fun cancel()
    fun close()
}
internal interface WakeProbeEffect { fun start(); fun cancel() }
internal data class ForegroundWakeCallbacks(
    val directory: File,
    val inputGeneration: () -> Long?,
    val suppressed: () -> Boolean,
    val ready: () -> Unit,
    val result: (VoskWakePhraseProbeResult) -> Unit,
)
internal interface ForegroundWakePlatform {
    fun createAcknowledgement(speechActive: () -> Boolean): WakeAcknowledgementEffect
    fun createProbe(callbacks: ForegroundWakeCallbacks): WakeProbeEffect
}

internal interface GatewayVoiceRecorder {
    val isRecording: Boolean
    fun start(): Boolean
    fun stop(): File?
    fun cancel()
}
internal interface GatewayVoicePlayer {
    fun play(temporaryFile: File, onCompleted: () -> Unit, onFailed: () -> Unit): Boolean
    fun cancel()
}
internal interface GatewayVoicePlatform {
    fun createRecorder(onLimitReached: () -> Unit): GatewayVoiceRecorder
    fun createRecorder(onLimitReached: () -> Unit, onInputFailure: (VoiceInputFailure) -> Unit): GatewayVoiceRecorder =
        createRecorder(onLimitReached)
    fun createRecorder(onLimitReached: () -> Unit, onInputFailure: (VoiceInputFailure) -> Unit,
                       onSpeechEnded: () -> Unit): GatewayVoiceRecorder = createRecorder(onLimitReached, onInputFailure)
    fun createPlayer(): GatewayVoicePlayer
    fun transcribe(session: GatewayFieldSession, audio: File, requestId: String): CancellableNetworkCall<GatewaySpeechTranscript>
    fun synthesize(session: GatewayFieldSession, message: String, requestId: String): CancellableNetworkCall<GatewaySpeechAudio>
    fun execute(action: () -> Unit)
    fun shutdownWorker()
}
internal interface GatewayVoiceEnvironment {
    fun networkAllowed(reason: String): Boolean
    fun networkState(): Pair<kr.co.hanium.dreamup.walksafe.network.MobileNetworkPreference,
        kr.co.hanium.dreamup.walksafe.network.ActiveNetworkTransport>
    fun sessionPrerequisitesAllowed(): Boolean
    fun processSnapshot(): GatewaySessionProcessSnapshot
}
internal interface GatewayVoiceInputSession {
    fun recognitionActive(): Boolean
    fun recognitionBlocked(): Boolean
    fun stopHandsFree(): Boolean
    fun setRecordingInputActive(active: Boolean)
    fun invalidateHomeWakeInput()
    fun scheduleRestart()
    fun recognized(result: RecognizedVoiceInput)
    fun inputFailed(failure: VoiceInputFailure) = Unit
}

/** Current core facts for presentation/admission only; all transitions stay with the controller. */
internal data class VoiceInputState(
    val recognitionActive: Boolean,
    val recognitionGeneration: Int,
    val recognitionPurpose: VoiceRecognitionPurpose,
    val promptPending: Boolean,
    val promptReadyGeneration: Int?,
    val deferredPending: Boolean,
    val modelDirectory: File?,
    val modelPreparing: Boolean,
    val modelPreparationFailed: Boolean,
    val destroyed: Boolean,
    val oneShotLimited: Boolean,
    val ownsCommandPage: Boolean,
)

/** Diagnostic effects do not decide input eligibility or completion. */
internal interface VoiceInputDiagnostics {
    fun input(event: VoiceInputDiagnosticEvent, confidence: Float? = null, resultCount: Int? = null, errorCode: Int? = null)
    fun wake(message: String)
    fun serviceInfo(message: String)
    fun serviceWarning(message: String, error: Throwable? = null)
}

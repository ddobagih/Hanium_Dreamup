package kr.co.hanium.dreamup.walksafe.voice.composition

import android.speech.SpeechRecognizer
import kr.co.hanium.dreamup.walksafe.feedback.NavigationSpeechDispatchResult
import kr.co.hanium.dreamup.walksafe.feedback.delivery.*
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.session.*
import kr.co.hanium.dreamup.walksafe.navigation.start.NativeGuidanceEntryController
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.session.runtime.*
import kr.co.hanium.dreamup.walksafe.voice.*
import kr.co.hanium.dreamup.walksafe.voice.input.*
import kr.co.hanium.dreamup.walksafe.voice.interaction.*
import kr.co.hanium.dreamup.walksafe.voice.output.*
import kr.co.hanium.dreamup.walksafe.diagnostics.*

/** Input and output share the actual controller; suppliers preserve each original lazy read. */
internal class SpeechInputPeerAdapter(
    private val input: () -> VoiceInputSessionController,
    private val interaction: () -> VoiceInteractionControllers,
) : SpeechOutputInputEffects {
    override fun invalidateHomeWakeInput() = input().invalidateForegroundHomeWakeInput()
    override fun promptState(token: CommandResponseToken, effect: CommandPromptEffect) = input().applyCommandPromptEffect(effect)
    override fun cancelDeferredInput() = input().cancelDeferredVoiceRecognitionStart()
    override fun refreshHomeWakeListening() = input().refreshForegroundHomeWakeListening()
    override fun cancelHomeWakeListening() = input().cancelForegroundHomeWakeListening()
    override fun cancelCommandDialogs(token: CommandResponseToken) = interaction().destinations.cancelCommandDialogs()
}

internal class InteractionInputPeerAdapter(private val input: () -> VoiceInputSessionController) : VoiceInteractionInput {
    override fun generation() = input().state.recognitionGeneration
    override fun active() = input().state.recognitionActive
    override fun promptPending() = input().state.promptPending
    override fun completePreparedPrompt(generation: Int?) = input().completePreparedPrompt(generation)
    override fun clearPromptReady() = input().clearPromptReady()
    override fun startVoiceCommandRecognition() = input().startVoiceCommandRecognition()
}

/** Native and accessibility contention remains with the same output actor, not a copied flag. */
internal class VoiceInputSpeechPeerAdapter(
    private val speech: () -> SpeechOutputController,
    private val risk: () -> RiskFeedbackDeliveryController<*>,
    private val nowMs: () -> Long,
    private val screenReader: () -> Boolean,
) : VoiceInputOutput {
    override fun commandResponsePending() = speech().hasPendingCommandResponse()
    override fun commandGeneration() = speech().currentCommandGeneration()
    override fun commandSpeechRevision(commandId: Long) = speech().commandSpeechRevision(commandId)
    override fun beginCommandSpeech(commandId: Long) = speech().beginCommandSpeech(commandId)
    override fun commandSpeechProgress(commandId: Long) = speech().commandSpeechProgress(commandId)
    override fun nativeOrAccessibilitySpeechActive(): Boolean {
        val current = speech().currentActuatorOrNull()
        if (current?.isAppSpeechActive() == true) return true
        val now = nowMs()
        val readerActive = screenReader()
        return speech().externalFallbackSpeechOutputActive(now, readerActive)
    }
    override fun nativeRecognitionBlocked() = speech().currentActuatorOrNull()?.isSpeechRecognitionBlocked() == true
    override fun prepareForRecognition() = speech().currentActuatorOrNull()?.prepareForSpeechRecognition()
    override fun canSpeakNavigation(nowMs: Long) = risk().canSpeakNavigation(nowMs)
    override fun speakInteraction(message: String) = speech().speakInteraction(message)
    override fun speakPreparingInput(message: String, onCompleted: () -> Unit, onFailed: () -> Unit) =
        speech().speakCommandResponse(message, preparingInput = true, onCompleted = onCompleted, onFailed = onFailed)
    override fun cancelCommandResponse() = speech().cancelCommandSpeechResponseForInput()
    override fun playListeningStarted() { speech().ensureFeedbackActuator().playVoiceListeningStartVibration() }
    override fun playListeningEnded() { speech().ensureFeedbackActuator().playVoiceListeningEndVibration() }
    override fun speakLowConfidenceReprompt(message: String, onCompleted: () -> Unit, onFailed: () -> Unit) =
        speech().ensureFeedbackActuator().speakInteraction(message, onCompleted, onFailed) == NavigationSpeechDispatchResult.ACCEPTED
    override fun screenReaderActive() = screenReader()
}

internal class InteractionSpeechPeerAdapter(
    private val speech: () -> SpeechOutputController,
    private val guidance: () -> RouteGuidanceController,
    private val currentState: () -> Unit,
) : VoiceInteractionOutput {
    override fun responseGeneration() = speech().currentCommandGeneration()
    override fun reserveCommandSpeech() = speech().reserveCommandSpeech()
    override fun speakInteraction(message: String) = speech().speakInteraction(message)
    override fun speakStatusExplanation(message: String) = speech().speakStatusExplanation(message)
    override fun speakCurrentState() = currentState()
    override fun speakCommandResponse(message: String, preparingInput: Boolean, onCompleted: (() -> Unit)?,
        onFailed: (() -> Unit)?, onCancelled: (() -> Unit)?) = speech().speakCommandResponse(
        message, preparingInput, onCompleted, onFailed, onCancelled = onCancelled)
    override fun speakCommandParts(parts: List<String>, preparingInput: Boolean, onCompleted: (() -> Unit)?,
        onFailed: (() -> Unit)?, onCancelled: (() -> Unit)?, isCurrent: () -> Boolean) =
        speech().speakCommandResponse(parts.joinToString(" "), preparingInput, onCompleted, onFailed,
            onCancelled = onCancelled, additionalCurrent = isCurrent, speechParts = parts)
    override fun speakWalkEndQuestion(message: String, onCompleted: () -> Unit, onFailed: () -> Unit) =
        speech().observeNativeCommandSpeech(onCompleted, onFailed) { done, failed ->
            speech().ensureFeedbackActuator().speakInteraction(message, done, failed) == NavigationSpeechDispatchResult.ACCEPTED
        }
    override fun speakRouteDecision(message: String, token: RouteNavigatorDecisionToken, repeatRequested: Boolean) =
        guidance().speakRouteDecision(message, token, repeatRequested)
}

internal class RiskVoicePeerAdapter(
    private val speech: () -> SpeechOutputController,
    private val input: () -> VoiceInputSessionController,
) : RiskFeedbackOutput {
    override fun currentActor() = speech().currentActuatorOrNull()
    override fun ensureActor() = speech().ensureFeedbackActuator()
    override fun reconcileTalkBackRiskTracks(trackIds: Set<String>) = speech().reconcileTalkBackRiskTracks(trackIds)
    override fun guidanceVoiceInputActive() = guidanceVoiceInputActive(input)
    override fun recognitionActive() = input().state.recognitionActive
    override fun cancelGatewaySpeechInteraction(reason: String) = input().gateway.cancelGatewaySpeechInteraction(reason)
    override fun cancelVoiceCommandRecognition() = input().cancelVoiceCommandRecognition()
}

internal fun guidanceVoiceInputActive(input: () -> VoiceInputSessionController): Boolean =
    input().state.deferredPending || input().state.recognitionActive || input().gateway.isRecording() || input().commandInputActive()

/** Recognition reaches the real dialog/command owners; SDK permission handling stays outside. */
internal class VoiceInputCommandPeerAdapter(
    private val interaction: () -> VoiceInteractionControllers,
    private val search: () -> DestinationSearchController,
    private val resume: () -> WalkSessionResumeController,
    private val gatewayWalk: () -> GatewayWalkSessionController,
    private val presentation: VoiceInputPresentation,
    private val speech: () -> SpeechOutputController,
    private val homeContextAvailable: () -> Boolean,
    private val diagnostics: VoiceInteractionDiagnostics = object : VoiceInteractionDiagnostics {},
) : VoiceInputCommandEffects {
    override fun recognized(result: RecognizedVoiceInput) = interaction().commands.handleRecognized(result)
    override fun resumeRecognized(phrases: List<String>, confidenceScores: FloatArray?) = resume().handleWalkSessionResumeRecognition(phrases, confidenceScores)
    override fun takeoverRecognized(phrases: List<String>) = gatewayWalk().handleGatewayWalkTakeoverRecognition(phrases)
    override fun resumeRecognizerNotStarted() = resume().handleWalkSessionResumeRecognizerNotStarted()
    override fun cancelTakeover(epoch: WalkRuntimeEpoch, reason: String) = gatewayWalk().cancelGatewayWalkTakeoverConfirmation(epoch, reason)
    override fun clearWalkEndConfirmation() = interaction().commands.clearVoiceEndConfirmation()
    override fun invalidateGuidanceStopConfirmation() = interaction().commands.invalidateGuidanceStopConfirmation()
    override fun commandInputMiss(error: Int) {
        val dialog = interaction().destinations.currentVoiceDestinationDialogState()
        diagnostics.dialog(stage = VoiceDialogDiagnosticStage.INPUT_ERROR,
            reason = when(error) {
                SpeechRecognizer.ERROR_NO_MATCH -> VoiceDialogDiagnosticReason.NO_MATCH
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceDialogDiagnosticReason.SPEECH_TIMEOUT
                else -> VoiceDialogDiagnosticReason.OTHER_ERROR
            }, dialogValid = dialog != null, pageIndex = dialog?.pageIndex,
            origin = classifyVoiceDialogDiagnosticOrigin("device_stt"))
        if (!interaction().destinations.retryHomeDestinationVoiceDialog("말씀을 정확히 듣지 못했습니다.")) {
            speech().speakInteraction("음성 명령을 인식하지 못했습니다.")
        }
    }
    override fun handleDestinationPromptIfPresent(): Boolean {
        if (search().state.pendingVoiceQuery != null) {
            presentation.voiceStatus("목적지 검색이 끝나면 후보 안내를 듣고 말씀해 주세요.")
            return true
        }
        val dialog = interaction().destinations.currentVoiceDestinationDialogState() ?: search().state.voicePage?.takeIf {
            !homeContextAvailable() && it.query == search().state.query && it.currentPageResults.isNotEmpty() && !search().state.inFlight
        }
        if (dialog != null) {
            interaction().destinations.speakHomeDestinationVoicePrompt(dialog, listenAfterPrompt = true)
            return true
        }
        return false
    }
}

/** Route mutations go directly to their existing acquisition/guidance owners. */
internal class VoiceRoutePeerAdapter(
    private val acquisition: () -> RouteAcquisitionController,
    private val archive: () -> RouteArchiveController,
    private val guidance: () -> RouteGuidanceController,
    private val compass: () -> RouteCompassSession,
    private val position: () -> PositioningSessionController,
    private val entry: () -> NativeGuidanceEntryController,
    private val admission: Admission,
    private val effects: Effects,
) : VoiceInteractionRoute {
    interface Admission {
        fun navigationCollectionAllowsWork(): Boolean
        fun destinationSearchAllowsWork(): Boolean
        fun explainNavigationFeatureUnavailable()
        fun blockRouteMutationWhileDeviationChoicePending(): Boolean
    }
    interface Effects {
        fun updateRouteDeviationActions(decision: RouteNavigatorUserDecision?)
        fun startNavigationServicesIfNeeded()
    }
    override fun requestGeneration() = acquisition().requestGeneration
    override fun requestPending() = acquisition().requestPending
    override fun requestReason() = acquisition().requestReason
    override fun navigationCollectionAllowsWork() = admission.navigationCollectionAllowsWork()
    override fun destinationSearchAllowsWork() = admission.destinationSearchAllowsWork()
    override fun explainNavigationFeatureUnavailable() = admission.explainNavigationFeatureUnavailable()
    override fun blockRouteMutationWhileDeviationChoicePending() = admission.blockRouteMutationWhileDeviationChoicePending()
    override fun requestRoute(destination: RoutePoint, reason: String, destinationCoordinateEvidence: DestinationCoordinateEvidence?) =
        acquisition().requestRoute(destination, reason, destinationCoordinateEvidence = destinationCoordinateEvidence)
    override fun retainRouteAfterRerouteFailure(message: String) = acquisition().retainRouteAfterRerouteFailure(message)
    override fun cancelActiveRouteRequest(preserveExistingRoute: Boolean) = acquisition().cancelActiveRouteRequest(preserveExistingRoute)
    override fun confirmArrival(expectedToken: RouteNavigatorDecisionToken) = acquisition().confirmArrival(expectedToken)
    override fun resetRouteState() = acquisition().resetRouteState()
    override fun snapshotPurgeFailed() = archive().purgeFailed
    override fun updateRouteDeviationActions(decision: RouteNavigatorUserDecision?) = effects.updateRouteDeviationActions(decision)
    override fun applyRouteDeviationSafetyUpdate(update: RouteNavigatorUpdate) { guidance().applyRouteDeviationSafetyUpdate(update) }
    override fun requestLocationRecheck(expectedToken: RouteNavigatorDecisionToken?) =
        guidance().requestLocationRecheck(expectedToken, effects::startNavigationServicesIfNeeded)
    override fun currentRouteCompassHeadingResult(nowMs: Long) = compass().currentRouteCompassHeadingResult(nowMs)
    override fun tryDispatchRouteInstruction(update: RouteNavigatorUpdate, epoch: WalkRuntimeEpoch, compass: RouteCompassHeadingResult?) =
        guidance().tryDispatchRouteInstruction(update, epoch, compass)
    override fun startNativeDestinationGuidance() = entry().start()
    override fun onDestinationSelected(result: DestinationSearchResult) = acquisition().onDestinationSelected(result)
    override fun currentRouteLocationAvailable() = position().currentRouteLocationOrNull() != null
}

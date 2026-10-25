package kr.co.hanium.dreamup.walksafe.voice.interaction

import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.voice.*
import kr.co.hanium.dreamup.walksafe.diagnostics.*

/** Android page and view facts are read separately, only when the original branch needs them. */
internal interface VoiceInteractionPresentation {
    fun homeContextAvailable(): Boolean
    fun voiceInteractionPage(): Boolean
    fun pendingDestination(): DestinationSearchResult?
    fun clearPendingDestination()
    fun showDestinationConfirmation()
    fun showCommandPage(action: AndroidVoiceAction)
    fun navigationStatus(message: String)
    fun voiceStatus(message: String)
    fun status(title: String, detail: String)
    fun refreshVoiceControls(active: Boolean)
    fun refreshDestinationRange(state: DestinationSearchVoiceState?)
    fun destinationRangeState(): DestinationSearchVoiceState?
    fun queryHasText(): Boolean
    fun latitudeHasText(): Boolean
    fun longitudeHasText(): Boolean
    fun setQueryText(query: String)
    fun clearCoordinateText()
    fun renderSearch()
    fun openDestinationConfirmation(result: DestinationSearchResult)
}

/** Original actor and process queries remain independent: no eager snapshot expires a lease. */
internal interface VoiceInteractionIdentity {
    fun actorId(): String?
    fun processGeneration(): Long
    fun feedbackLifetimeGeneration(): Int
}

internal interface VoiceInteractionScheduler {
    fun nowMs(): Long
    fun runOnUiThread(action: () -> Unit)
    fun postDialogExpiry(action: () -> Unit, delayMs: Long)
}

/** R2 remains the only microphone/prompt generation owner. */
internal interface VoiceInteractionInput {
    fun generation(): Int
    fun active(): Boolean
    fun promptPending(): Boolean
    fun completePreparedPrompt(generation: Int?)
    fun clearPromptReady()
    fun startVoiceCommandRecognition(): Boolean
}

/** Uses the existing R1 output actor; completion means its native terminal callback. */
internal interface VoiceInteractionOutput {
    fun responseGeneration(): Long
    fun reserveCommandSpeech(): (Boolean) -> Unit = {}
    fun speakInteraction(message: String): Boolean
    fun speakStatusExplanation(message: String): Boolean
    fun speakCurrentState()
    fun speakCommandResponse(message: String, preparingInput: Boolean = false,
        onCompleted: (() -> Unit)? = null, onFailed: (() -> Unit)? = null,
        onCancelled: (() -> Unit)? = null): Boolean
    fun speakCommandParts(parts: List<String>, preparingInput: Boolean = false,
        onCompleted: (() -> Unit)? = null, onFailed: (() -> Unit)? = null,
        onCancelled: (() -> Unit)? = null, isCurrent: () -> Boolean = { true }): Boolean =
        if (isCurrent()) speakCommandResponse(parts.joinToString(" "), preparingInput, onCompleted, onFailed, onCancelled)
        else { onFailed?.invoke(); false }
    fun speakWalkEndQuestion(message: String, onCompleted: () -> Unit, onFailed: () -> Unit): Boolean
    fun speakRouteDecision(message: String, token: RouteNavigatorDecisionToken, repeatRequested: Boolean): Boolean
}

/** Existing route request and sensor owners retain all mutation and observation state. */
internal interface VoiceInteractionRoute {
    fun requestGeneration(): Int
    fun requestPending(): Boolean
    fun requestReason(): String?
    fun navigationCollectionAllowsWork(): Boolean
    fun destinationSearchAllowsWork(): Boolean
    fun explainNavigationFeatureUnavailable()
    fun blockRouteMutationWhileDeviationChoicePending(): Boolean
    fun requestRoute(destination: RoutePoint, reason: String, destinationCoordinateEvidence: DestinationCoordinateEvidence?)
    fun retainRouteAfterRerouteFailure(message: String)
    fun cancelActiveRouteRequest(preserveExistingRoute: Boolean)
    fun confirmArrival(expectedToken: RouteNavigatorDecisionToken): RouteNavigatorUpdate
    fun resetRouteState()
    fun snapshotPurgeFailed(): Boolean
    fun updateRouteDeviationActions(decision: RouteNavigatorUserDecision?)
    fun applyRouteDeviationSafetyUpdate(update: RouteNavigatorUpdate)
    fun requestLocationRecheck(expectedToken: RouteNavigatorDecisionToken?): Boolean
    fun currentRouteCompassHeadingResult(nowMs: Long): RouteCompassHeadingResult?
    fun tryDispatchRouteInstruction(update: RouteNavigatorUpdate, epoch: WalkRuntimeEpoch, compass: RouteCompassHeadingResult?)
    fun startNativeDestinationGuidance()
    fun onDestinationSelected(result: DestinationSearchResult): Boolean
    fun currentRouteLocationAvailable(): Boolean
}

/** Walk lifecycle and explicit-report authority stay with their existing owners. */
internal interface VoiceInteractionWalk {
    fun snapshot(): WalkSessionSnapshot
    fun runtimeEpochOrNull(): WalkRuntimeEpoch?
    fun runtimeActive(): Boolean
    fun pauseWalk()
    fun prepareResume()
    fun confirmWalkEnd()
    fun requireReporterUserId(reason: String): Boolean
    fun cancelExplicitReportIfOpen(): Boolean
    fun requestExplicitVoiceReport()
    fun refreshLocalQuarantine(onLoaded: (List<String>) -> Unit) { onLoaded(emptyList()) }
    fun localQuarantineDescriptions(): List<String> = emptyList()
    /** Exact immutable query result identity; selection does not replace this token. */
    fun localQuarantineListToken(): Long? = null
    fun localQuarantineSelectionToken(): Long? = null
    fun localQuarantineDescription(number: Int): String? = null
    fun selectLocalQuarantineNumber(number: Int): Boolean = false
    fun confirmLocalQuarantineDelete(confidence: Double?, expectedListToken: Long, expectedSelectionToken: Long): Boolean = false
}

/** Side-effect diagnostics have the original DEBUG/default logging implementation. */
internal interface VoiceInteractionDiagnostics {
    fun input(event: VoiceInputDiagnosticEvent, matched: Boolean? = null) = logVoiceInputDiagnostic(event, matched = matched)
    fun dialog(stage: VoiceDialogDiagnosticStage, reason: VoiceDialogDiagnosticReason = VoiceDialogDiagnosticReason.NONE,
        dialogValid: Boolean? = null, pageIndex: Int? = null, origin: RuntimeDiagnosticOrigin = RuntimeDiagnosticOrigin.UNKNOWN) =
        logVoiceDialogDiagnostic(stage, reason, dialogValid, pageIndex, origin)
    fun debug(message: String) { android.util.Log.d("WalkSafeVoiceInput", message) }
    fun platform(disposition: PlatformVoiceCandidateDisposition, valid: Boolean, origin: RuntimeDiagnosticOrigin, pageIndex: Int?) {
        BoundedRuntimeDiagnosticLog.recordState(domain = RuntimeDiagnosticDomain.VOICE_DIALOG,
            stage = VoiceDialogDiagnosticStage.COMMAND_CLASSIFIED, reason = disposition,
            context = if (valid) VoiceDialogDiagnosticContext.VALID else VoiceDialogDiagnosticContext.ABSENT_OR_STALE,
            origin = origin, pageIndex = pageIndex)
    }
}

internal fun String.toVoiceStatusToken(maxLength: Int): String = trim().replace(Regex("\\s+"), "_").take(maxLength).ifBlank { "-" }

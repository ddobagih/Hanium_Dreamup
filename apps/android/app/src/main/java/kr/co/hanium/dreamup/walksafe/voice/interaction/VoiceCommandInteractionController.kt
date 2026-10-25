package kr.co.hanium.dreamup.walksafe.voice.interaction

import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.session.NavigationRunState
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.voice.*
import kr.co.hanium.dreamup.walksafe.voice.input.RecognizedVoiceInput
import kr.co.hanium.dreamup.walksafe.diagnostics.*

/** Owns command parsing and confirmation leases; construction is inert. */
internal class VoiceCommandInteractionController(
    private val search: () -> DestinationSearchController,
    private val routeRun: () -> NavigationRunState,
    private val walk: VoiceInteractionWalk,
    private val route: VoiceInteractionRoute,
    private val identity: VoiceInteractionIdentity,
    private val ui: VoiceInteractionPresentation,
    private val input: VoiceInteractionInput,
    private val output: VoiceInteractionOutput,
    private val scheduler: VoiceInteractionScheduler,
    private val diagnostics: VoiceInteractionDiagnostics = object : VoiceInteractionDiagnostics {},
    private val dialog: () -> DestinationVoiceDialogController,
) {
    private val walkSessionVoiceControlPolicy = WalkSessionVoiceControlPolicy()

    private var voiceEndConfirmationPromptPending = false

    /** R17: the one "길안내를 끝낼까요?" question, in memory only, ended by its window or the next utterance. */
    private val guidanceStopConfirmation = kr.co.hanium.dreamup.walksafe.navigation.GuidanceStopConfirmationPolicy()

    private var guidanceStopConfirmationRouteGeneration: Int? = null

    private var guidanceStopConfirmationWalkEpoch: WalkRuntimeEpoch? = null

    fun acceptsWalkControlConfidence(value: Float): Boolean = walkSessionVoiceControlPolicy.acceptsConfidence(value)

    val endConfirmationPending: Boolean get() = voiceEndConfirmationPromptPending

    private class LocalQuarantineVoiceLease(
        val actor: String?, val process: Long, val feedback: Int, val epoch: WalkRuntimeEpoch,
        val summaries: List<String>, val listToken: Long,
    ) {
        var heard = false
        var selected: Int? = null
        var selectionToken: Long? = null
        var selectionHeard = false
    }
    private var localQuarantineVoiceLease: LocalQuarantineVoiceLease? = null
    private fun localQuarantineLeaseCurrent(lease: LocalQuarantineVoiceLease): Boolean =
        localQuarantineVoiceLease === lease && identity.actorId() == lease.actor &&
            identity.processGeneration() == lease.process && identity.feedbackLifetimeGeneration() == lease.feedback &&
            walk.snapshot().epoch == lease.epoch && walk.localQuarantineListToken() == lease.listToken &&
            walk.localQuarantineDescriptions() == lease.summaries

    private var localQuarantineRequestGeneration = 0L
    private fun speakLocalQuarantineList() {
        localQuarantineVoiceLease = null
        val request = localQuarantineRequestGeneration
        val actor = identity.actorId()
        val process = identity.processGeneration()
        val feedback = identity.feedbackLifetimeGeneration()
        val epoch = walk.snapshot().epoch
        val completeReservation = output.reserveCommandSpeech()
        fun requestCurrent() = localQuarantineRequestGeneration == request && identity.actorId() == actor &&
            identity.processGeneration() == process && identity.feedbackLifetimeGeneration() == feedback && walk.snapshot().epoch == epoch
        try {
            walk.refreshLocalQuarantine { loaded -> scheduler.runOnUiThread {
                if (!requestCurrent()) { completeReservation(false); return@runOnUiThread }
                val listToken = walk.localQuarantineListToken()
                if (listToken == null) { completeReservation(false); return@runOnUiThread }
                val summaries = loaded.toList()
                if (walk.localQuarantineDescriptions() != summaries) { completeReservation(false); return@runOnUiThread }
                if (summaries.isEmpty()) {
                    output.speakInteraction("현재 안내할 기기 보관 신고가 없습니다. 화면에서 보관 내역과 계정 상태를 확인해 주세요.")
                    completeReservation(true)
                    return@runOnUiThread
                }
                val lease = LocalQuarantineVoiceLease(actor, process, feedback, epoch, summaries, listToken)
                localQuarantineVoiceLease = lease
                val parts = listOf("기기에 보관한 신고 목록입니다.") + summaries.mapIndexed { index, text -> "${index + 1}번 $text" } +
                    "기기 신고 번호 선택이라고 말씀해 주세요. 삭제는 선택한 안내를 듣고 별도로 확인합니다."
                val accepted = output.speakCommandParts(parts,
                    onCompleted = { if (localQuarantineLeaseCurrent(lease)) lease.heard = true },
                    onFailed = { if (localQuarantineVoiceLease === lease) localQuarantineVoiceLease = null },
                    onCancelled = { if (localQuarantineVoiceLease === lease) localQuarantineVoiceLease = null },
                    isCurrent = { requestCurrent() && localQuarantineLeaseCurrent(lease) })
                completeReservation(accepted)
            } }
        } catch (_: Exception) {
            completeReservation(false)
            if (requestCurrent()) output.speakInteraction("기기에 보관한 신고를 확인하지 못했습니다. 잠시 뒤 다시 시도해 주세요.")
        }
    }

    private fun selectLocalQuarantineByVoice(number: Int) {
        val lease = localQuarantineVoiceLease
        if (lease == null || !lease.heard || !localQuarantineLeaseCurrent(lease) || number !in 1..lease.summaries.size ||
            walk.localQuarantineDescription(number) != lease.summaries[number - 1] || !walk.selectLocalQuarantineNumber(number)) {
            output.speakInteraction("기기 신고 목록을 끝까지 들은 뒤 현재 번호를 선택해 주세요."); return
        }
        val selectionToken = walk.localQuarantineSelectionToken() ?: run {
            output.speakInteraction("선택한 기기 신고가 바뀌었습니다. 목록을 다시 확인해 주세요."); return
        }
        lease.selected = number; lease.selectionToken = selectionToken; lease.selectionHeard = false
        output.speakCommandParts(listOf("${number}번 ${lease.summaries[number - 1]}",
            "이 신고를 기기에서 삭제하려면 기기 신고 삭제 확인이라고 말씀해 주세요. 서버의 신고는 바뀌지 않습니다."),
            onCompleted = { if (localQuarantineLeaseCurrent(lease) && lease.selected == number && walk.localQuarantineSelectionToken() == selectionToken) lease.selectionHeard = true },
            onFailed = { lease.selectionHeard = false }, onCancelled = { lease.selectionHeard = false },
            isCurrent = { localQuarantineLeaseCurrent(lease) && lease.selected == number && walk.localQuarantineSelectionToken() == selectionToken })
    }

    private fun confirmLocalQuarantineByVoice(confidence: Double?) {
        val lease = localQuarantineVoiceLease
        val selectionToken = lease?.selectionToken
        if (lease == null || !lease.heard || !lease.selectionHeard || !localQuarantineLeaseCurrent(lease) ||
            lease.selected == null || selectionToken == null || walk.localQuarantineSelectionToken() != selectionToken || confidence == null || !confidence.isFinite() || confidence !in .8..1.0 ||
            !walk.confirmLocalQuarantineDelete(confidence, lease.listToken, selectionToken)) {
            output.speakInteraction("선택한 기기 신고 안내를 끝까지 듣고 삭제를 다시 확인해 주세요."); return
        }
        lease.selectionHeard = false
    }

    fun handleRecognized(result: RecognizedVoiceInput) = handleVoiceCommandPhrases(result.phrases,
        result.confidenceScores?.toFloatArray(), result.expectedNavigationDecisionToken,
        result.includeRecognizedTextInStatus, result.source, result.backend, result.hasAdditionalAlternatives)

    fun handleVoiceCommandPhrases(
        phrases: List<String>,
        confidenceScores: FloatArray?,
        expectedNavigationDecisionToken: RouteNavigatorDecisionToken?,
        includeRecognizedTextInStatus: Boolean = true,
        redactedRecognitionSource: String = "device_stt",
        resultBackend: OfflineSpeechEngine? = null,
        hasAdditionalAlternatives: Boolean = false,
    ) {
        localQuarantineRequestGeneration += 1L
        val recognized = phrases.firstOrNull().orEmpty()
        // R17: every final ends an earlier "길안내를 끝낼까요?" question; only a stop said inside it counts.
        val confirmationWasOpen = guidanceStopConfirmation.consumeOpenConfirmation(scheduler.nowMs())
        val snapshot = walk.snapshot()
        val guidanceStopConfirmationOpen = confirmationWasOpen &&
            guidanceStopConfirmationRouteGeneration == route.requestGeneration() &&
            guidanceStopConfirmationWalkEpoch == snapshot.epoch && routeRun().active
        guidanceStopConfirmationRouteGeneration = null
        guidanceStopConfirmationWalkEpoch = null
        val walkDecision = walkSessionVoiceControlPolicy.evaluate(
            phrase = recognized,
            confidence = confidenceScores?.getOrNull(0) ?: Float.NaN,
            state = snapshot.state,
            epoch = snapshot.epoch,
        )
        diagnostics.input(
            VoiceInputDiagnosticEvent.WALK_DECISION,
            matched = walkDecision.action != WalkSessionVoiceAction.NO_OP,
        )
        if (walkDecision.action != WalkSessionVoiceAction.NO_OP) {
            executeWalkSessionVoiceAction(walkDecision.action, snapshot.epoch)
            return
        }
        if (voiceEndConfirmationPromptPending) {
            diagnostics.input(VoiceInputDiagnosticEvent.COMMAND_GATE_BLOCKED)
            clearVoiceEndConfirmation()
            ui.navigationStatus("voice=walk_end_confirmation_unmatched")
            output.speakInteraction("보행 종료를 확인하지 못했습니다. 종료하려면 다시 요청해 주세요.")
            return
        }
        val destinationDialogState = dialog().currentVoiceDestinationDialogState()
        diagnostics.dialog(
            stage = VoiceDialogDiagnosticStage.FINAL_RECEIVED,
            reason = when {
                destinationDialogState != null -> VoiceDialogDiagnosticReason.NONE
                dialog().hasHomeLease() -> VoiceDialogDiagnosticReason.STALE_DIALOG_CONTEXT
                else -> VoiceDialogDiagnosticReason.NO_DIALOG_CONTEXT
            },
            dialogValid = destinationDialogState != null,
            pageIndex = destinationDialogState?.pageIndex,
            origin = classifyVoiceDialogDiagnosticOrigin(redactedRecognitionSource),
        )
        val allowBareDestinationIndex = destinationDialogState != null ||
            (!ui.homeContextAvailable() &&
                search().state.voicePage != null &&
                search().state.voicePage?.query == search().state.query &&
                !search().state.inFlight && route.destinationSearchAllowsWork())
        if (BuildConfig.DEBUG) {
            val candidate = kr.co.hanium.dreamup.walksafe.navigation.parseAndroidVoiceCommand(
                phrases.firstOrNull().orEmpty(),
                allowBareDestinationIndex = allowBareDestinationIndex,
            )
            diagnostics.input(
                VoiceInputDiagnosticEvent.COMMAND_CANDIDATE_PARSED,
                matched = candidate != null,
            )
        }
        val scoredAction = selectAndroidVoiceAction(
            phrases,
            confidenceScores,
            allowBareDestinationIndex = allowBareDestinationIndex,
        )
        val platformCandidate = if (scoredAction == null) {
            assessPlatformVoiceCandidate(
                phrases = phrases,
                confidenceScores = confidenceScores,
                backend = resultBackend,
                isFinal = true,
                isHomeContext = ui.homeContextAvailable(),
                allowBareDestinationIndex = allowBareDestinationIndex,
                hasAdditionalAlternatives = hasAdditionalAlternatives,
                destinationDialogState = destinationDialogState,
            )
        } else null
        if (BuildConfig.DEBUG && platformCandidate != null) {
            diagnostics.debug("event=PLATFORM_CANDIDATE_POLICY disposition=${platformCandidate.disposition.name}",
            )
            diagnostics.platform(platformCandidate.disposition, destinationDialogState != null,
                classifyVoiceDialogDiagnosticOrigin(redactedRecognitionSource), destinationDialogState?.pageIndex)
        }
        val action = scoredAction ?: platformCandidate?.previewAction
        diagnostics.input(VoiceInputDiagnosticEvent.COMMAND_SELECTED, matched = action != null)
        if (platformCandidate?.disposition == PlatformVoiceCandidateDisposition.CONFIRMATION_REQUIRED) {
            val showExistingDestinationConfirmation =
                platformCandidate.candidate ==
                    kr.co.hanium.dreamup.walksafe.navigation.AndroidVoiceCommand.StartNavigation &&
                    ui.pendingDestination() != null
            if (showExistingDestinationConfirmation) {
                ui.showDestinationConfirmation()
            }
            val message = when {
                showExistingDestinationConfirmation ->
                    "안내 시작은 화면의 안내 시작 버튼을 눌러 확인해 주세요."
                destinationDialogState != null ->
                    "안내한 후보 번호를 확인하지 못했습니다."
                else ->
                    "확인이 필요한 실행 명령입니다. 음성 화면을 닫은 뒤 실행할 기능을 직접 선택해 주세요."
            }
            ui.voiceStatus(message)
            if (showExistingDestinationConfirmation || !dialog().retryHomeDestinationVoiceDialog(message)) {
                output.speakInteraction(message)
            }
            return
        }
        if (platformCandidate?.disposition == PlatformVoiceCandidateDisposition.UNRECOGNIZED) {
            // Saying it again cannot help; nothing said was a command. Same wording as the scored
            // path's miss below, so one dead end does not survive in a second place.
            val message = unrecognizedCommandMessageKo(recognized)
            ui.voiceStatus(message)
            if (!dialog().retryHomeDestinationVoiceDialog(message)) {
                output.speakInteraction(message)
            }
            return
        }
        if (platformCandidate?.disposition in setOf(
                PlatformVoiceCandidateDisposition.AMBIGUOUS,
                PlatformVoiceCandidateDisposition.INVALID_FINAL,
            )
        ) {
            val message = "음성 후보를 하나로 확정하지 못했습니다. 다시 말씀해 주세요."
            ui.voiceStatus(message)
            if (!dialog().retryHomeDestinationVoiceDialog(message)) {
                output.speakInteraction(message)
            }
            return
        }
        if (platformCandidate?.disposition == PlatformVoiceCandidateDisposition.PREVIEW_ONLY) {
            ui.voiceStatus(
                if (action is AndroidVoiceAction.SearchDestination) {
                    "음성 후보로 검색 결과를 보여드립니다. 목적지를 직접 선택해 주세요."
                } else {
                    "도움말을 읽어드립니다."
                },
            )
        }
        if (snapshot.state == WalkSessionState.PAUSED &&
            action !in setOf(AndroidVoiceAction.SpeakVoiceHelp, AndroidVoiceAction.OpenHelp, AndroidVoiceAction.SpeakCurrentState, AndroidVoiceAction.ConfirmArrival, AndroidVoiceAction.RejectArrival, AndroidVoiceAction.OpenSettings, AndroidVoiceAction.ListLocalQuarantinedReports, AndroidVoiceAction.ConfirmLocalQuarantinedReportDelete) && action !is AndroidVoiceAction.SelectLocalQuarantinedReport) {
            diagnostics.input(VoiceInputDiagnosticEvent.COMMAND_GATE_BLOCKED)
            ui.navigationStatus("voice=paused_command_unmatched")
            output.speakInteraction("일시정지 중에는 보행 재개 또는 보행 종료라고 말씀해 주세요.")
            return
        }
        if (action == null) {
            ui.navigationStatus(
                if (includeRecognizedTextInStatus) {
                    "voice=command_unmatched phrase=${recognized.toVoiceStatusToken(maxLength = 48)}"
                } else {
                    "voice=command_unmatched source=$redactedRecognitionSource"
                },
            )
            val retryMessage = if (kr.co.hanium.dreamup.walksafe.navigation.isCompoundVoiceCommand(recognized)) {
                kr.co.hanium.dreamup.walksafe.navigation.COMPOUND_VOICE_COMMAND_REFUSAL_KO
            } else if (kr.co.hanium.dreamup.walksafe.navigation.isLocationDescriptionRequest(recognized)) {
                kr.co.hanium.dreamup.walksafe.navigation.LOCATION_DESCRIPTION_UNSUPPORTED_KO
            } else {
                "명령을 이해하지 못했습니다."
            }
            if (!dialog().retryHomeDestinationVoiceDialog(retryMessage)) {
                output.speakInteraction(unrecognizedCommandMessageKo(recognized))
            }
            return
        }
        if (
            action in setOf(
                AndroidVoiceAction.RequestReroute,
                AndroidVoiceAction.RecheckLocation,
                AndroidVoiceAction.CancelDestination,
                AndroidVoiceAction.StopNavigation,
            ) && expectedNavigationDecisionToken != routeRun().navigator.pendingDecisionToken()
        ) {
            diagnostics.input(VoiceInputDiagnosticEvent.COMMAND_GATE_BLOCKED)
            ui.navigationStatus("voice=navigation_decision_stale")
            output.speakInteraction("경로 상태가 바뀌어 이전 음성 결정을 적용하지 않았습니다. 다시 확인해 주세요.")
            return
        }
        if (action == AndroidVoiceAction.StopNavigation &&
            confirmGuidanceStopFirst(recognized, guidanceStopConfirmationOpen)
        ) return
        diagnostics.input(VoiceInputDiagnosticEvent.COMMAND_EXECUTION_REQUESTED)
        executeVoiceAction(action, expectedNavigationDecisionToken, confidenceScores?.getOrNull(0)?.toDouble())
    }

    fun executeWalkSessionVoiceAction(
        action: WalkSessionVoiceAction,
        expectedEpoch: WalkRuntimeEpoch,
    ) {
        if (walk.snapshot().epoch != expectedEpoch) return
        when (action) {
            WalkSessionVoiceAction.PAUSE -> {
                walk.pauseWalk()
                output.speakInteraction("보행 안내를 일시정지했습니다.")
            }
            WalkSessionVoiceAction.RESUME -> {
                walk.prepareResume()
                output.speakInteraction("필수 기능을 다시 확인한 뒤 보행 재개를 확인합니다.")
            }
            WalkSessionVoiceAction.REQUEST_END ->
                requestVoiceWalkEndConfirmation(expectedEpoch)
            WalkSessionVoiceAction.CONFIRM_END -> {
                voiceEndConfirmationPromptPending = false
                walk.confirmWalkEnd()
                output.speakInteraction("보행을 종료했습니다.")
            }
            WalkSessionVoiceAction.CANCEL_END -> {
                voiceEndConfirmationPromptPending = false
                ui.navigationStatus("voice=walk_end_cancelled")
                output.speakInteraction("보행 종료를 취소했습니다.")
            }
            WalkSessionVoiceAction.NO_OP -> Unit
        }
    }

    private fun requestVoiceWalkEndConfirmation(expectedEpoch: WalkRuntimeEpoch) {
        val snapshot = walk.snapshot()
        if (
            snapshot.epoch != expectedEpoch ||
            snapshot.state !in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED)
        ) {
            clearVoiceEndConfirmation()
            return
        }
        voiceEndConfirmationPromptPending = true
        val prompt = "보행을 종료할까요? 보행 종료 확인 또는 보행 종료 취소라고 말해 주세요."
        val generation = identity.feedbackLifetimeGeneration()
        val delivered = {
            scheduler.runOnUiThread {
                val current = walk.snapshot()
                if (
                    identity.feedbackLifetimeGeneration() == generation &&
                    voiceEndConfirmationPromptPending &&
                    current.epoch == expectedEpoch &&
                    current.state in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED)
                ) {
                    if (!input.startVoiceCommandRecognition()) {
                        clearVoiceEndConfirmation()
                        ui.navigationStatus("voice=walk_end_confirmation_unavailable")
                    }
                }
            }
        }
        val failed = {
            scheduler.runOnUiThread {
                if (
                    identity.feedbackLifetimeGeneration() == generation &&
                    walk.snapshot().epoch == expectedEpoch
                ) {
                    clearVoiceEndConfirmation()
                    ui.navigationStatus("voice=walk_end_prompt_failed")
                }
            }
        }
        val accepted = output.speakWalkEndQuestion(
            message = prompt,
            onCompleted = delivered,
            onFailed = failed,
        )
        if (!accepted) failed()
    }

    fun clearVoiceEndConfirmation() {
        voiceEndConfirmationPromptPending = false
        walkSessionVoiceControlPolicy.invalidatePendingEnd()
    }

    private fun executeVoiceAction(
        action: AndroidVoiceAction,
        expectedNavigationDecisionToken: RouteNavigatorDecisionToken?,
        confirmationConfidence: Double? = null,
    ) {
        if (action !is AndroidVoiceAction.SelectLocalQuarantinedReport && action !in setOf(AndroidVoiceAction.ListLocalQuarantinedReports, AndroidVoiceAction.ConfirmLocalQuarantinedReportDelete)) localQuarantineVoiceLease = null
        if (ui.homeContextAvailable() &&
            action !is AndroidVoiceAction.SearchDestination &&
            action !is AndroidVoiceAction.SelectDestinationCandidate &&
            action !is AndroidVoiceAction.SpeakDestinationCandidateDetail &&
            action !is AndroidVoiceAction.SelectLocalQuarantinedReport &&
            action !in setOf(
                AndroidVoiceAction.RepeatDestinationCandidates,
                AndroidVoiceAction.HearMoreDestinationCandidates,
                AndroidVoiceAction.PreviousDestinationCandidates,
                AndroidVoiceAction.CancelDestination,
                AndroidVoiceAction.CancelDialog,
                AndroidVoiceAction.StartNavigation,
                AndroidVoiceAction.SpeakCurrentGuidance,
                AndroidVoiceAction.SpeakCurrentState,
                AndroidVoiceAction.ConfirmArrival,
                AndroidVoiceAction.RejectArrival,
                AndroidVoiceAction.SpeakVoiceHelp,
                AndroidVoiceAction.OpenHelp,
                AndroidVoiceAction.ListLocalQuarantinedReports,
                AndroidVoiceAction.ConfirmLocalQuarantinedReportDelete,
                AndroidVoiceAction.OpenSettings,
            )
        ) {
            ui.navigationStatus("voice=walk_action_requires_active_walk")
            output.speakInteraction("보행을 시작한 뒤 사용할 수 있는 명령입니다. 목적지를 먼저 검색해 주세요.")
            return
        }
        ui.showCommandPage(action)
        when (action) {
            AndroidVoiceAction.ListLocalQuarantinedReports -> speakLocalQuarantineList()
            is AndroidVoiceAction.SelectLocalQuarantinedReport -> selectLocalQuarantineByVoice(action.number)
            AndroidVoiceAction.ConfirmLocalQuarantinedReportDelete -> confirmLocalQuarantineByVoice(confirmationConfidence)
            AndroidVoiceAction.OpenSettings -> output.speakStatusExplanation("설정 화면을 열었습니다.")
            AndroidVoiceAction.StartNavigation -> {
                if (dialog().startPendingWalkingDestinationChange()) Unit
                else if (ui.pendingDestination() != null) route.startNativeDestinationGuidance()
                else output.speakInteraction(
                    if (routeRun().active) "이미 길안내 중입니다."
                    else "목적지를 검색하고 후보를 선택한 뒤 안내 시작이라고 말해 주세요.",
                )
            }
            // Legacy arrival phrases are status requests; only fresh route evidence can end a walk.
            AndroidVoiceAction.SpeakCurrentState,
            AndroidVoiceAction.ConfirmArrival,
            AndroidVoiceAction.RejectArrival -> output.speakCurrentState()
            AndroidVoiceAction.SpeakCurrentGuidance -> {
                // S05-11 / FP-026: an open candidate dialog outranks guidance for "다시 말해줘".
                if (dialog().activeVoiceCandidateDialogOrNull() != null) dialog().repeatVoiceDestinationCandidates()
                // S06-40: a walking selection waiting for "길안내 시작" is a confirmation dialog too.
                else if (dialog().repeatPendingWalkingDestinationChange()) Unit
                else if (walk.runtimeActive()) speakNextNavigationInstruction()
                else output.speakInteraction("진행 중인 길안내가 없습니다. 목적지를 먼저 선택해 주세요.")
            }
            AndroidVoiceAction.SpeakVoiceHelp, AndroidVoiceAction.OpenHelp -> {
                val help = voiceCommandHelpKo(walk.snapshot().state == WalkSessionState.PAUSED)
                output.speakInteraction(help)
            }
            AndroidVoiceAction.CreateReport -> {
                ui.navigationStatus("voice=report_command_recognized")
                walk.requestExplicitVoiceReport()
            }
            is AndroidVoiceAction.SearchDestination -> dialog().startVoiceDestinationSearch(action.query)
            is AndroidVoiceAction.SelectDestinationCandidate -> dialog().selectVoiceDestinationCandidate(action.oneBasedIndex)
            is AndroidVoiceAction.SpeakDestinationCandidateDetail -> dialog().speakVoiceDestinationCandidateDetail(action.oneBasedIndex)
            AndroidVoiceAction.RepeatDestinationCandidates -> dialog().repeatVoiceDestinationCandidates()
            AndroidVoiceAction.HearMoreDestinationCandidates -> dialog().hearMoreVoiceDestinationCandidates()
            AndroidVoiceAction.PreviousDestinationCandidates -> dialog().previousVoiceDestinationCandidates()
            AndroidVoiceAction.CancelDestination -> dialog().cancelDestinationFromVoice()
            AndroidVoiceAction.CancelDialog -> cancelVoiceDialogFromVoice()
            AndroidVoiceAction.SpeakNextNavigationInstruction -> speakNextNavigationInstruction()
            AndroidVoiceAction.RequestReroute -> requestRerouteFromVoice(expectedNavigationDecisionToken)
            AndroidVoiceAction.RecheckLocation -> recheckLocationFromVoice(expectedNavigationDecisionToken)
            AndroidVoiceAction.StopNavigation -> stopNavigationFromVoice(expectedNavigationDecisionToken)
        }
    }

    /**
     * "다시 말씀해 주세요" asks a walker who cannot read the screen to guess. The destination
     * dialog already re-offers its numbers instead; everywhere else ended in a dead end. Each
     * hint names commands the parser accepts in the state the walk is actually in.
     */
    private fun unrecognizedCommandHintKo(): String = when {
        voiceEndConfirmationPromptPending -> "보행 종료 확인 또는 보행 종료 취소라고 말씀해 주세요."
        walk.snapshot().state == WalkSessionState.PAUSED ->
            "보행 재개 또는 보행 종료라고 말씀해 주세요."
        routeRun().navigator.pendingUserDecision() == RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION ->
            "도착했습니다. 안내를 종료합니다."
        routeRun().navigator.pendingUserDecision() == RouteNavigatorUserDecision.REROUTE ->
            "새 경로 요청, 위치 다시 확인, 길안내 종료 중에서 말씀해 주세요."
        routeRun().navigator.pendingUserDecision() == RouteNavigatorUserDecision.LOCATION_RECHECK ->
            "위치 다시 확인이라고 말씀해 주세요."
        routeRun().active -> "다음 안내 알려줘 또는 길안내 종료라고 말씀해 주세요."
        ui.pendingDestination() != null -> "안내 시작 또는 목적지 취소라고 말씀해 주세요."
        else -> "목적지를 말하거나 도움말이라고 말씀해 주세요."
    }

    /**
     * S05-6: a sentence with two commands is refused whole and the user is asked for one at a time.
     * S05-10: a location description request is named as not offered, apart from a plain miss.
     */
    private fun unrecognizedCommandMessageKo(recognized: String): String =
        if (kr.co.hanium.dreamup.walksafe.navigation.isCompoundVoiceCommand(recognized)) {
            "${kr.co.hanium.dreamup.walksafe.navigation.COMPOUND_VOICE_COMMAND_REFUSAL_KO} ${unrecognizedCommandHintKo()}"
        } else if (kr.co.hanium.dreamup.walksafe.navigation.isLocationDescriptionRequest(recognized)) {
            "${kr.co.hanium.dreamup.walksafe.navigation.LOCATION_DESCRIPTION_UNSUPPORTED_KO} ${unrecognizedCommandHintKo()}" +
                locationRecheckOfferKo()
        } else {
            "못 알아들었습니다. ${unrecognizedCommandHintKo()}"
        }

    /**
     * voice-F10 (S05-10, 05.txt:139 "재측정 요청과 위치 설명 요청을 구분"): while the route waits for a
     * location re-check or a reroute choice, the location question is paired with the re-measure command
     * that works now. Elsewhere "위치 다시 확인" is not accepted (recheckLocationFromVoice), so not offered.
     */
    private fun locationRecheckOfferKo(): String = when (routeRun().navigator.pendingUserDecision()) {
        RouteNavigatorUserDecision.LOCATION_RECHECK, RouteNavigatorUserDecision.REROUTE ->
            " 위치 다시 확인이라고 하면 현재 위치를 다시 잽니다."
        else -> ""
    }

    /**
     * S05-12/S06-21: a bare "취소" closes only the dialog open now - the candidate dialog, a running
     * search or a walking selection waiting for "길안내 시작". It never removes the destination, never
     * touches a route or a route request and never ends guidance ("목적지 취소"/"길안내 종료" do that).
     */
    private fun cancelVoiceDialogFromVoice() {
        // voice-F5: an explicit report waiting for its confirmation is the dialog open now; it is dropped.
        if (walk.cancelExplicitReportIfOpen()) {
            ui.navigationStatus("voice=dialog_cancelled report=true route_active=${routeRun().active}")
            output.speakInteraction("신고를 취소했습니다. 저장하지 않았습니다.")
            return
        }
        // voice-F5: a search still waiting for its location fix, or a spoken query with no list yet,
        // is a running search too.
        val searchCancelled = search().state.inFlight ||
            search().isAcquiring ||
            (search().state.pendingVoiceQuery != null && search().state.voicePage == null)
        val dialogOpen = searchCancelled || voiceDestinationDialogOpen()
        if (!dialogOpen) {
            ui.navigationStatus("voice=dialog_cancel_nothing_open route_active=${routeRun().active}")
            output.speakInteraction(
                when {
                    // voice-F2: a confirmed switch to another place is on its way; only "목적지 취소" stops
                    // it and keeps the running route ("길안내 종료" would end both).
                    destinationReplacementRequestPending() ->
                        "새 목적지 경로를 준비하고 있습니다. 바꾸지 않으려면 목적지 취소라고 말해 주세요. " +
                            "진행 중인 길안내는 계속됩니다."
                    // voice-F5: a route question waiting for its answer names its own answers.
                    voiceRouteDecisionPending() -> "취소할 대화가 없습니다. ${unrecognizedCommandHintKo()}"
                    routeRun().active -> "취소할 대화가 없습니다. 길안내를 끝내려면 길안내 종료라고 말해 주세요."
                    ui.pendingDestination() != null || routeRun().destination != null ->
                        "취소할 대화가 없습니다. 목적지를 지우려면 목적지 취소라고 말해 주세요."
                    else -> "취소할 대화가 없습니다."
                },
            )
            return
        }
        dialog().clearWalkingSelection()
        search().clearVoiceSelection()
        dialog().clearHomeDestinationVoiceDialog()
        if (searchCancelled) search().cancel()
        // The location wait ends without a request in flight; refresh its "현재 위치를 확인하고 있습니다" line.
        if (searchCancelled) ui.renderSearch()
        ui.navigationStatus("voice=dialog_cancelled search=$searchCancelled route_active=${routeRun().active}")
        output.speakInteraction(
            (if (searchCancelled) "목적지 검색을 취소했습니다." else "후보 선택을 닫았습니다.") +
                (if (routeRun().active) " 진행 중인 길안내는 계속됩니다." else ""),
        )
    }

    fun invalidateGuidanceStopConfirmation() {
        guidanceStopConfirmation.invalidate()
        guidanceStopConfirmationRouteGeneration = null
        guidanceStopConfirmationWalkEpoch = null
    }

    /**
     * R17 (voice-F3, S05-12/S06-21; 05.txt:157): during guidance with a candidate dialog, a search or a
     * walking selection open, a stop phrase without "길" ("안내 취소") may be about the candidate reading,
     * so it is asked once. True when the question was asked and nothing else may run for this utterance.
     * "길안내 종료/취소" and every stop with no dialog open end guidance at once (중지 우선, 05.txt:125).
     */
    private fun confirmGuidanceStopFirst(recognized: String, confirmationOpen: Boolean): Boolean {
        val phrase = kr.co.hanium.dreamup.walksafe.navigation.guidanceStopPhraseOrNull(recognized) ?: return false
        val decision = guidanceStopConfirmation.decide(
            phrase = phrase,
            confirmationWasOpen = confirmationOpen,
            dialogOpen = voiceDestinationDialogOpen(),
            guidanceRunning = routeRun().active,
            nowMs = scheduler.nowMs(),
        )
        if (decision != kr.co.hanium.dreamup.walksafe.navigation.GuidanceStopDecision.CONFIRM) return false
        val expectedRouteGeneration = route.requestGeneration()
        val expectedWalkEpoch = walk.snapshot().epoch
        val preparationGeneration = input.generation()
        val responseGeneration = output.responseGeneration() + 1L
        guidanceStopConfirmationRouteGeneration = expectedRouteGeneration
        guidanceStopConfirmationWalkEpoch = expectedWalkEpoch
        ui.navigationStatus("voice=navigation_stop_confirmation_requested")
        output.speakCommandResponse(
            message = kr.co.hanium.dreamup.walksafe.navigation.GUIDANCE_STOP_CONFIRMATION_PROMPT_KO,
            preparingInput = true,
            onCompleted = {
                val current = output.responseGeneration() == responseGeneration &&
                    input.generation() == preparationGeneration &&
                    guidanceStopConfirmationRouteGeneration == expectedRouteGeneration &&
                    guidanceStopConfirmationWalkEpoch == expectedWalkEpoch &&
                    walk.snapshot().epoch == expectedWalkEpoch &&
                    route.requestGeneration() == expectedRouteGeneration && routeRun().active &&
                    input.promptPending()
                if (current) {
                    guidanceStopConfirmation.promptDelivered(scheduler.nowMs())
                    input.completePreparedPrompt(preparationGeneration)
                    if (!input.startVoiceCommandRecognition()) {
                        invalidateGuidanceStopConfirmation()
                        input.clearPromptReady()
                        ui.refreshVoiceControls(active = false)
                    }
                } else if (output.responseGeneration() == responseGeneration) {
                    invalidateGuidanceStopConfirmation()
                    input.completePreparedPrompt(null)
                    ui.refreshVoiceControls(active = input.active())
                }
            },
            onFailed = {
                if (output.responseGeneration() == responseGeneration) {
                    invalidateGuidanceStopConfirmation()
                    input.completePreparedPrompt(null)
                    ui.refreshVoiceControls(active = input.active())
                }
            },
        )
        return true
    }

    /** voice-F2/S06-40: a confirmed switch to another place is being routed while the old route runs. */
    private fun destinationReplacementRequestPending(): Boolean =
        routeRun().active && routeRun().navigator.hasRoute() && route.requestPending() &&
            route.requestReason() == "user_destination"

    /** Reroute and location re-check can still require a spoken choice. */
    private fun voiceRouteDecisionPending(): Boolean = routeRun().navigator.pendingUserDecision() in setOf(
        RouteNavigatorUserDecision.REROUTE, RouteNavigatorUserDecision.LOCATION_RECHECK,
    )

    /** S05-12/R17: a candidate dialog, a running or location-waiting search, or a walking selection is open. */
    private fun voiceDestinationDialogOpen(): Boolean =
        search().state.inFlight || search().state.voicePage != null ||
            dialog().hasHomeLease() || dialog().hasWalkingSelection() ||
            search().state.pendingVoiceQuery != null || search().isAcquiring

    /**
     * Says what the walk is doing rather than why the command was refused.
     *
     * The walker cannot see the screen, so the state is the one thing they need and the one thing
     * the old wording buried. It only names a command where exactly one is left; the three-way
     * choice was already read out when the deviation was announced, and repeating it costs seconds
     * mid-walk.
     */
    private fun explainRouteCommandUnavailable(
        onRouteKo: String = "경로를 따라가는 중입니다.",
    ) {
        output.speakInteraction(routeCommandUnavailableMessage(onRouteKo))
    }

    fun routeCommandUnavailableMessage(
        onRouteKo: String = "경로를 따라가는 중입니다.",
    ): String = when {
        !routeRun().active -> "길안내 중이 아닙니다."
        routeRun().directionPauseReason == "tmap_unavailable" -> "새 경로를 찾지 못해 방향 안내가 멈춰 있습니다."
        routeRun().navigator.pendingUserDecision() == RouteNavigatorUserDecision.LOCATION_RECHECK ->
            "위치 다시 확인만 할 수 있습니다."
        routeRun().navigator.pendingUserDecision() == RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION ->
            "도착했습니다. 안내를 종료합니다."
        routeRun().navigator.pendingUserDecision() == RouteNavigatorUserDecision.REROUTE ->
            "경로를 벗어난 상태입니다."
        else -> onRouteKo
    }

    // Non-voice UI callers capture on click; voice dispatch always supplies its input token.
    fun requestRerouteFromVoice() {
        requestRerouteFromVoice(routeRun().navigator.pendingDecisionToken())
    }

    fun requestRerouteFromVoice(expectedToken: RouteNavigatorDecisionToken?) {
        if (!route.navigationCollectionAllowsWork()) {
            route.explainNavigationFeatureUnavailable()
            return
        }
        val destination = routeRun().destination
        if (
            destination == null ||
            !routeRun().active ||
            routeRun().navigator.pendingUserDecision() != RouteNavigatorUserDecision.REROUTE
        ) {
            explainRouteCommandUnavailable()
            return
        }
        if (expectedToken == null) {
            explainRouteCommandUnavailable()
            return
        }
        val decision = routeRun().navigator.selectDeviationChoice(RouteDeviationChoice.NEW_ROUTE, expectedToken)
        if (!decision.shouldReroute) {
            explainRouteCommandUnavailable()
            return
        }
        route.updateRouteDeviationActions(decision.pendingUserDecision)
        ui.navigationStatus("navigation=off_route_reroute_user_confirmed")
        output.speakInteraction(requireNotNull(decision.instruction))
        route.requestRoute(
            destination,
            reason = "off_route",
            destinationCoordinateEvidence = routeRun().navigator.destinationCoordinateEvidenceFor(destination),
        )
        if (!route.requestPending() && routeRun().navigator.hasRoute()) {
            route.retainRouteAfterRerouteFailure("TMAP 새 경로 요청을 시작할 수 없어 방향 안내를 중지했습니다.")
        }
    }

    // Non-voice UI callers capture on click; voice dispatch always supplies its input token.
    fun recheckLocationFromVoice() {
        recheckLocationFromVoice(routeRun().navigator.pendingDecisionToken())
    }

    fun recheckLocationFromVoice(expectedToken: RouteNavigatorDecisionToken?) {
        if (!route.navigationCollectionAllowsWork()) {
            route.explainNavigationFeatureUnavailable()
            return
        }
        if (!route.requestLocationRecheck(expectedToken)) {
            explainRouteCommandUnavailable()
        }
    }

    // Non-voice UI callers capture on click; voice dispatch always supplies its input token.
    fun endNavigationAfterDeviation() {
        endNavigationAfterDeviation(routeRun().navigator.pendingDecisionToken())
    }

    fun endNavigationAfterDeviation(expectedToken: RouteNavigatorDecisionToken?) {
        if (
            !routeRun().active ||
            routeRun().navigator.pendingUserDecision() != RouteNavigatorUserDecision.REROUTE
        ) {
            explainRouteCommandUnavailable()
            return
        }
        if (expectedToken == null) return
        val decision = routeRun().navigator.selectDeviationChoice(RouteDeviationChoice.END_NAVIGATION, expectedToken)
        if (decision.reason != "off_route_navigation_ended") return
        route.resetRouteState()
        if (route.snapshotPurgeFailed()) {
            val detail =
                "암호화 경로 기록을 삭제하지 못했습니다. 새 길안내를 시작하지 말고 앱 저장소를 확인해 주세요."
            ui.status(
                "길안내 종료 저장소 확인 필요",
                detail,
            )
            ui.navigationStatus("navigation=route_snapshot_purge_failed")
            output.speakInteraction(detail)
        } else {
            ui.navigationStatus("navigation=off_route_navigation_ended_by_user")
            output.speakInteraction(requireNotNull(decision.instruction))
        }
    }

    fun speakNextNavigationInstruction() {
        if (!route.navigationCollectionAllowsWork()) {
            route.explainNavigationFeatureUnavailable()
            return
        }
        val expectedWalkEpoch = walk.runtimeEpochOrNull() ?: return
        ui.navigationStatus("voice=next_navigation_instruction active=${routeRun().active}")
        if (route.requestPending()) {
            output.speakInteraction("목적지까지의 경로를 계산하고 있습니다.")
            return
        }
        if (!routeRun().active) {
            output.speakInteraction("진행 중인 길안내가 없습니다.")
            return
        }
        routeRun().navigator.pendingDecisionToken()?.let { token ->
            if (token.decision == RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION) {
                output.speakCurrentState()
                return
            }
            val message = when (token.decision) {
                RouteNavigatorUserDecision.REROUTE ->
                    "경로를 벗어났습니다. 새 경로 요청, 위치 다시 확인, 길안내 종료 중에서 선택해 주세요."
                RouteNavigatorUserDecision.LOCATION_RECHECK ->
                    "현재 위치와 경로를 확인해야 합니다. 위치 다시 확인을 선택해 주세요."
                RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION ->
                    "도착했습니다. 안내를 종료합니다."
            }
            output.speakRouteDecision(message, token, repeatRequested = true)
            return
        }
        // Reuse the fresh automatic context without adding another location/motion observation.
        val nowMs = scheduler.nowMs()
        val compass = route.currentRouteCompassHeadingResult(nowMs)
        val update = routeRun().navigator.currentGuidance(nowMs, compass?.observation, compass?.reason)
        if (update == null) {
            output.speakInteraction("현재 위치를 확인한 뒤 다음 경로를 안내합니다.")
            return
        }
        route.tryDispatchRouteInstruction(update, expectedWalkEpoch, compass)
    }

    // Non-voice UI callers capture on click; voice dispatch always supplies its input token.
    private fun stopNavigationFromVoice() {
        stopNavigationFromVoice(routeRun().navigator.pendingDecisionToken())
    }

    private fun stopNavigationFromVoice(expectedToken: RouteNavigatorDecisionToken?) {
        val hadActiveNavigation = routeRun().active || route.requestPending()
        if (
            routeRun().active &&
            routeRun().navigator.pendingUserDecision() == RouteNavigatorUserDecision.REROUTE
        ) {
            endNavigationAfterDeviation(expectedToken)
            return
        }
        if (route.requestPending()) {
            route.cancelActiveRouteRequest(preserveExistingRoute = false)
        } else if (routeRun().active) {
            route.resetRouteState()
        }
        val message = if (hadActiveNavigation) "길안내를 중지했습니다." else "진행 중인 길안내가 없습니다."
        ui.navigationStatus("voice=navigation_stopped hadActive=$hadActiveNavigation")
        output.speakInteraction(message)
    }
}

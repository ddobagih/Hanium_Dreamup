package kr.co.hanium.dreamup.walksafe.voice.interaction

import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.session.NavigationRunState
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.voice.*
import kr.co.hanium.dreamup.walksafe.voice.input.RecognizedVoiceInput
import kr.co.hanium.dreamup.walksafe.diagnostics.*

/** Owns candidate dialog leases and heard-page authority; construction is inert. */
internal class DestinationVoiceDialogController(
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
    private val repeatGuidance: () -> Unit,
) {
    private var homeDestinationVoiceDialogLease: HomeDestinationVoiceDialogLease? = null

    /** voice-F6: the candidate list's age notice is said once per searched list. */
    private val destinationOriginAgeNoticeOnce = kr.co.hanium.dreamup.walksafe.navigation.DestinationOriginAgeNoticeOnce()

    // S06-40: a candidate chosen by voice while walking waits here for a separate "길안내 시작".
    private val walkingDestinationChange =
        kr.co.hanium.dreamup.walksafe.navigation.WalkingDestinationChangeConfirmation<Triple<String, Long, WalkRuntimeEpoch>>()

    fun hasHomeLease(): Boolean = homeDestinationVoiceDialogLease != null

    fun hasWalkingSelection(): Boolean = walkingDestinationChange.hasPending()

    fun clearWalkingSelection() = walkingDestinationChange.clear()

    fun cancelCommandDialogs() {
        homeDestinationVoiceDialogLease?.close()
        homeDestinationVoiceDialogLease = null
        search().state.voicePage?.invalidateVoicePrompt()
    }

    fun clearHomeDestinationVoiceDialog() {
        clearHomeDestinationVoiceDialog(VoiceDialogDiagnosticReason.UNKNOWN)
    }

    fun clearHomeDestinationVoiceDialog(reason: VoiceDialogDiagnosticReason) {
        val dialogForDiagnostic = currentVoiceDestinationDialogState()
        homeDestinationVoiceDialogLease?.close()
        homeDestinationVoiceDialogLease = null
        search().state.voicePage?.invalidateVoicePrompt()
        // S06-34: a closed or cancelled dialog no longer shows a voice range. voice-F9: also when the
        // list itself is kept (BACKGROUNDED, PAGE_CHANGED); a page shows it again only when spoken.
        ui.refreshDestinationRange(null)
        diagnostics.dialog(
            stage = VoiceDialogDiagnosticStage.DIALOG_CLEARED,
            reason = reason,
            dialogValid = dialogForDiagnostic != null,
            pageIndex = dialogForDiagnostic?.pageIndex,
        )
    }

    fun currentVoiceDestinationDialogState(): DestinationSearchVoiceState? {
        val lease = homeDestinationVoiceDialogLease ?: return null
        val state = lease.currentState(
            actorId = identity.actorId(),
            sessionGeneration = identity.processGeneration(),
            searchGeneration = search().state.generation,
            query = search().state.query,
            state = search().state.voicePage,
            homeContextAvailable = ui.homeContextAvailable(),
            voicePage = ui.voiceInteractionPage(),
        )
        if (state == null && homeDestinationVoiceDialogLease === lease) {
            lease.close()
            homeDestinationVoiceDialogLease = null
            ui.refreshDestinationRange(null)
        }
        return state
    }

    fun retryHomeDestinationVoiceDialog(message: String): Boolean {
        val state = currentVoiceDestinationDialogState()
        if (state == null) {
            diagnostics.dialog(
                stage = VoiceDialogDiagnosticStage.RETRY_SKIPPED,
                reason = if (homeDestinationVoiceDialogLease == null) {
                    VoiceDialogDiagnosticReason.NO_DIALOG_CONTEXT
                } else {
                    VoiceDialogDiagnosticReason.STALE_DIALOG_CONTEXT
                },
                dialogValid = false,
            )
            return false
        }
        diagnostics.dialog(
            stage = VoiceDialogDiagnosticStage.RETRY_REQUESTED,
            dialogValid = true,
            pageIndex = state.pageIndex,
        )
        speakHomeDestinationVoicePrompt(
            state,
            message,
        )
        return true
    }

    fun speakHomeDestinationVoicePrompt(
        state: DestinationSearchVoiceState,
        message: String = state.voicePrompt(),
        listenAfterPrompt: Boolean = ui.homeContextAvailable() && ui.voiceInteractionPage(),
    ): Boolean {
        clearHomeDestinationVoiceDialog()
        if (state.results.isEmpty()) return output.speakInteraction(message)
        val actorId = identity.actorId() ?: return false
        // S06-34/voice-F9: only a page that is actually spoken shows its range.
        ui.refreshDestinationRange(state)
        val sessionGeneration = identity.processGeneration()
        val searchGeneration = search().state.generation
        val homeFollowUp = ui.homeContextAvailable() && ui.voiceInteractionPage()
        val lease = if (homeFollowUp) HomeDestinationVoiceDialogLease(
            actorId = actorId,
            sessionGeneration = sessionGeneration,
            searchGeneration = searchGeneration,
            state = state,
            nowElapsedRealtimeMs = scheduler::nowMs,
            answerWindowStartsAfterPrompt = true,
        ) else null
        homeDestinationVoiceDialogLease = lease
        val dialogForDiagnostic = currentVoiceDestinationDialogState()
        diagnostics.dialog(
            stage = VoiceDialogDiagnosticStage.DIALOG_OPENED,
            dialogValid = dialogForDiagnostic != null,
            pageIndex = dialogForDiagnostic?.pageIndex,
        )
        // Retry hints cannot authorize a page whose candidate list was never fully spoken.
        val candidatePrompt = state.voicePrompt()
        // S06-31: a list measured long ago says that its distances may have changed; the numbers, order
        // and distances stay as searched (S06-32). voice-F6: once per list, after the direct answer,
        // next to the page's distance basis.
        val originAgeNotice = destinationOriginAgeNoticeOnce.noticeOrNull(state.origin, scheduler.nowMs())
        val attempt = state.beginVoicePrompt()
        val preparationGeneration = input.generation()
        val responseGeneration = output.responseGeneration() + 1L
        fun pageCurrent(): Boolean =
            search().state.voicePage === state && search().state.query == state.query &&
                search().state.generation == searchGeneration && identity.actorId() == actorId &&
                identity.processGeneration() == sessionGeneration &&
                (!homeFollowUp || (homeDestinationVoiceDialogLease === lease &&
                    currentVoiceDestinationDialogState() === state))
        val pageParts = state.voicePromptParts(originAgeNotice)
        val prefix = if (message.contains(candidatePrompt)) message.substringBefore(candidatePrompt).trim() else message.trim()
        val suffix = if (message.contains(candidatePrompt)) message.substringAfter(candidatePrompt).trim() else ""
        val parts = (listOf(prefix) + pageParts + suffix).filter(String::isNotBlank)
        return output.speakCommandParts(
            parts = parts,
            isCurrent = ::pageCurrent,
            preparingInput = listenAfterPrompt,
            onCompleted = {
                val current = pageCurrent() && input.generation() == preparationGeneration &&
                    state.completeVoicePrompt(attempt)
                if (!current) state.failVoicePrompt(attempt)
                if (current && originAgeNotice != null) destinationOriginAgeNoticeOnce.markDelivered(state.origin)
                // The answer time is measured from here, not from the start of a long page prompt.
                if (current) lease?.openAnswerWindow()
                if (current && lease != null) {
                    scheduler.postDialogExpiry({
                        if (homeDestinationVoiceDialogLease === lease) currentVoiceDestinationDialogState()
                    }, 30_000L)
                }
                if (listenAfterPrompt && (!homeFollowUp || homeDestinationVoiceDialogLease === lease) &&
                    input.generation() == preparationGeneration
                ) {
                    val awaitingInput = input.promptPending()
                    input.completePreparedPrompt(if (current && awaitingInput) preparationGeneration else null)
                    if (current && awaitingInput) {
                        if (BuildConfig.DEBUG) diagnostics.debug("event=DESTINATION_VOICE_PROMPT_DONE dialog_current=true",
                        )
                        if (BuildConfig.DEBUG) diagnostics.debug("event=DESTINATION_VOICE_FOLLOW_UP_REQUESTED",
                        )
                        if (!input.startVoiceCommandRecognition()) {
                            input.clearPromptReady()
                            ui.refreshVoiceControls(active = false)
                            if (BuildConfig.DEBUG) diagnostics.debug("event=DESTINATION_VOICE_FOLLOW_UP_BLOCKED",
                            )
                        }
                    } else {
                        ui.refreshVoiceControls(active = input.active())
                    }
                }
            },
            onFailed = {
                state.failVoicePrompt(attempt)
                lease?.openAnswerWindow()
                if (output.responseGeneration() == responseGeneration &&
                    ui.destinationRangeState() === state
                ) ui.refreshDestinationRange(null)
                if (listenAfterPrompt && (!homeFollowUp || homeDestinationVoiceDialogLease === lease) &&
                    input.generation() == preparationGeneration
                ) {
                    // Keep only the owned retry context; the failed page has no selection authority.
                    input.completePreparedPrompt(null)
                    ui.voiceStatus(
                        "후보 안내를 완료하지 못했습니다. 음성 명령 버튼을 눌러 다시 시도해 주세요.",
                    )
                    ui.refreshVoiceControls(active = input.active())
                }
            },
            onCancelled = {
                state.failVoicePrompt(attempt)
                lease?.close()
                if (homeDestinationVoiceDialogLease === lease) homeDestinationVoiceDialogLease = null
                if (ui.destinationRangeState() === state) ui.refreshDestinationRange(null)
            },
        )
    }

    /**
     * S06-38 (S05-14): "이전 후보" goes back one page of the list this search already fetched; no request.
     * The page is read in full again before its numbers can be chosen (the current-page rule is kept).
     */
    fun previousVoiceDestinationCandidates() {
        val state = if (ui.homeContextAvailable()) {
            currentVoiceDestinationDialogState()
        } else {
            search().state.voicePage
        }
        if (state == null) {
            output.speakInteraction(noVoiceDestinationCandidatesMessage())
            return
        }
        val transition = state.onCommand(DestinationSearchVoiceCommand.PreviousPage)
        if (!transition.accepted) {
            speakHomeDestinationVoicePrompt(state, "첫 번째 후보 목록입니다.")
            return
        }
        search().showVoicePage(transition.state)
        speakHomeDestinationVoicePrompt(transition.state)
    }

    fun startVoiceDestinationSearch(query: String) {
        if (!route.destinationSearchAllowsWork()) {
            route.explainNavigationFeatureUnavailable()
            return
        }
        clearHomeDestinationVoiceDialog()
        if (search().state.inFlight) {
            search().cancel()
        }
        ui.setQueryText(query)
        walkingDestinationChange.clear()
        search().requestVoiceResults(query)
        if (search().search(reset = true)) {
            ui.navigationStatus("voice=destination_search query=${query.toVoiceStatusToken(maxLength = 48)}")
            output.speakInteraction("검색 중입니다.")
        } else {
            search().clearVoiceRequest()
        }
    }

    fun repeatVoiceDestinationCandidates() {
        // S06-40 x S05-11 (FP-026 design_rules[1]): the pending confirmation outranks the old list.
        if (repeatPendingWalkingDestinationChange()) return
        val state = if (ui.homeContextAvailable()) {
            currentVoiceDestinationDialogState()
        } else {
            search().state.voicePage
        } ?: voiceStateForShownDestinationResultsOrNull()
        val transition = state?.onCommand(DestinationSearchVoiceCommand.RepeatPage)
        if (transition == null || !transition.accepted) {
            // S05-11: with no candidate dialog, the walk's current guidance owns "다시 듣기".
            if (!search().state.inFlight && walk.runtimeActive() && routeRun().active) {
                repeatGuidance()
            } else {
                output.speakInteraction(noVoiceDestinationCandidatesMessage())
            }
            return
        }
        speakHomeDestinationVoicePrompt(transition.state)
    }

    /** S05-11: the candidate dialog that currently owns repeat requests, or null when none is open. */
    fun activeVoiceCandidateDialogOrNull(): DestinationSearchVoiceState? =
        if (ui.homeContextAvailable()) {
            currentVoiceDestinationDialogState()
        } else {
            search().state.voicePage?.takeIf { it.query == search().state.query && !search().state.inFlight }
        }

    /**
     * S06-6: the one result list already on screen becomes the voice page when no candidate dialog
     * owns it (keyboard search, or a dialog that expired). There is no re-search, and a number is
     * accepted only after this page has been read in full (speakHomeDestinationVoicePrompt).
     */
    private fun voiceStateForShownDestinationResultsOrNull(): DestinationSearchVoiceState? {
        if (search().state.inFlight || search().state.query.isBlank() || search().state.results.isEmpty()) {
            return null
        }
        val results = search().state.results.toList()
        search().state.voicePage?.takeIf { it.query == search().state.query && it.results == results }
            ?.let { return it }
        return DestinationSearchVoiceState(
            query = search().state.query,
            results = results,
            moreResultsAvailable = search().canLoadMore(),
            origin = search().state.origin,
        ).also { search().showVoicePage(it) }
    }

    private fun noVoiceDestinationCandidatesMessage(): String =
        if (search().state.inFlight) {
            "목적지를 검색하고 있습니다. 결과를 받은 뒤 다시 듣기라고 말씀해 주세요."
        } else {
            "먼저 목적지를 검색해 주세요."
        }

    fun hearMoreVoiceDestinationCandidates() {
        if (!route.destinationSearchAllowsWork()) {
            route.explainNavigationFeatureUnavailable()
            return
        }
        val state = if (ui.homeContextAvailable()) {
            currentVoiceDestinationDialogState()
        } else {
            search().state.voicePage
        }
        if (state == null) {
            // No page was heard yet in this dialog: start from the first page of the shown results.
            val shown = voiceStateForShownDestinationResultsOrNull()
            if (shown == null) {
                output.speakInteraction(noVoiceDestinationCandidatesMessage())
            } else {
                speakHomeDestinationVoicePrompt(shown)
            }
            return
        }
        val transition = state.onCommand(DestinationSearchVoiceCommand.HearMore)
        if (transition.accepted) {
            search().showVoicePage(transition.state)
            speakHomeDestinationVoicePrompt(transition.state)
            return
        }
        // A running request is not an exhausted list: say which one it is.
        if (search().state.inFlight) {
            speakHomeDestinationVoicePrompt(
                state,
                "추가 후보를 아직 찾고 있습니다. 잠시 후 더 듣기라고 말씀해 주세요.",
            )
            return
        }
        // S06-9: the search already fetched every candidate it can offer (DESTINATION_SEARCH_MAX_RESULTS
        // in one request), so "더 듣기" only pages that list and never sends a second request. One search
        // keeps one origin and one list version; nothing is merged or silently re-sorted (S06-13/S06-14).
        speakHomeDestinationVoicePrompt(
            state,
            if (search().state.providerHasMore == true) {
                "이번 검색에서 안내할 수 있는 후보를 모두 안내했습니다. 다른 후보는 더 자세한 장소명으로 " +
                    "다시 검색해 주세요. 안내한 후보 번호를 말씀해 주셔도 됩니다."
            } else {
                "더 안내할 목적지 후보가 없습니다. 안내한 후보 번호를 말씀해 주세요."
            },
        )
    }

    fun cancelDestinationFromVoice() {
        if (route.blockRouteMutationWhileDeviationChoicePending()) return
        // S06-39: "목적지 취소" cancels the search, the candidate choice and a confirmed replacement
        // request; it never ends a running route. Ending guidance is the separate "길안내 종료".
        val routeRunning = routeRun().active && routeRun().navigator.hasRoute()
        val searchLocationAcquiring = search().isAcquiring
        if (routeRunning) {
            // A confirmed switch to another place (S06-40) is still a destination choice; an
            // off-route reroute belongs to the running guidance and is kept.
            val replacementPending = route.requestPending() &&
                route.requestReason() == "user_destination"
            val hadSearch = search().state.inFlight || searchLocationAcquiring ||
                search().state.voicePage != null ||
                homeDestinationVoiceDialogLease != null || walkingDestinationChange.hasPending() ||
                replacementPending
            // The walking selection waiting for "길안내 시작" (S06-40) is a candidate choice too.
            walkingDestinationChange.clear()
            search().clearVoiceSelection()
            clearHomeDestinationVoiceDialog()
            if (search().state.inFlight || searchLocationAcquiring) search().cancel()
            if (replacementPending) route.cancelActiveRouteRequest(preserveExistingRoute = true)
            ui.navigationStatus(
                "voice=destination_cancel_route_kept hadSearch=$hadSearch replacement=$replacementPending",
            )
            output.speakInteraction(
                (if (hadSearch) "목적지 검색과 후보 선택을 취소했습니다. " else "") +
                    "진행 중인 길안내는 계속됩니다. 길안내를 끝내려면 길안내 종료라고 말해 주세요.",
            )
            return
        }
        // No route is running here. A first route request still in flight belongs to the destination
        // being removed and is cancelled with it by route.resetRouteState().
        val firstRequestCancelled = route.requestPending()
        val hadDestination = routeRun().active ||
            route.requestPending() ||
            search().state.inFlight ||
            searchLocationAcquiring ||
            routeRun().destination != null ||
            ui.queryHasText() ||
            ui.latitudeHasText() ||
            ui.longitudeHasText()
        ui.clearPendingDestination()
        walkingDestinationChange.clear()
        search().clearVoiceSelection()
        if (search().state.inFlight || searchLocationAcquiring) search().cancel()
        route.resetRouteState()
        search().clearQuery()
        ui.setQueryText("")
        ui.clearCoordinateText()
        ui.renderSearch()
        val message = when {
            // No guidance was running (see above); say only what was actually removed.
            firstRequestCancelled -> "목적지와 경로 요청을 취소했습니다. 길안내는 시작하지 않습니다."
            hadDestination -> "목적지를 취소했습니다."
            else -> "취소할 목적지가 없습니다."
        }
        ui.navigationStatus(
            "voice=destination_cancelled hadDestination=$hadDestination firstRequest=$firstRequestCancelled",
        )
        output.speakInteraction(message)
    }

    fun speakVoiceDestinationCandidateDetail(oneBasedIndex: Int) {
        val state = activeVoiceCandidateDialogOrNull()
        val result = state?.onCommand(DestinationSearchVoiceCommand.SelectCandidate(oneBasedIndex))?.selectedResult
        if (result == null) {
            output.speakInteraction(state?.selectionRejectionMessage(oneBasedIndex)
                ?: "현재 들은 후보가 없습니다. 다시 듣기로 후보 안내를 끝까지 들은 뒤 번호와 상세라고 말씀해 주세요.")
            return
        }
        val actor = identity.actorId()
        val generation = identity.processGeneration()
        val message = listOfNotNull("${oneBasedIndex}번 ${result.name}", result.speechAddressOrNull(),
            destinationCategoryLabelKo(result.category), result.distanceM?.let { "검색 위치 기준 ${approximateDestinationDistanceKo(it, state.origin?.accuracyM)}" },
            result.distanceM?.let { destinationDistanceKindKo(result.distanceSource) }).joinToString(", ")
        output.speakCommandParts(listOf(message), isCurrent = {
            identity.actorId() == actor && identity.processGeneration() == generation &&
                activeVoiceCandidateDialogOrNull() === state &&
                state.onCommand(DestinationSearchVoiceCommand.SelectCandidate(oneBasedIndex)).accepted
        })
    }

    fun selectVoiceDestinationCandidate(oneBasedIndex: Int) {
        if (!route.destinationSearchAllowsWork()) {
            route.explainNavigationFeatureUnavailable()
            return
        }
        if (!walk.requireReporterUserId("login_required_voice_destination_select")) return
        val state = if (ui.homeContextAvailable()) {
            currentVoiceDestinationDialogState()
        } else {
            search().state.voicePage
        }
        val voiceSelection = state?.onCommand(
            DestinationSearchVoiceCommand.SelectCandidate(oneBasedIndex),
        )
        val selected = voiceSelection?.selectedResult
        if (selected == null) {
            // Searching, out-of-range number, expired or never-spoken dialog, and no results are
            // different situations; each says what can be done now (S06-8). STT failure is separate.
            val message = when {
                search().state.inFlight -> "목적지 검색이 끝난 뒤 후보 번호를 말씀해 주세요."
                state != null -> state.selectionRejectionMessage(oneBasedIndex)
                search().state.results.isNotEmpty() ->
                    "후보 선택 시간이 지났거나 아직 후보를 음성으로 안내하지 않았습니다. " +
                        "다시 듣기라고 말씀하시면 현재 후보를 안내합니다."
                else -> "선택할 목적지 후보가 없습니다. 먼저 목적지를 검색해 주세요."
            }
            ui.navigationStatus("voice=destination_candidate_missing index=$oneBasedIndex")
            if (!retryHomeDestinationVoiceDialog(message)) {
                output.speakInteraction(message)
            }
            return
        }
        if (ui.homeContextAvailable()) {
            search().clearVoiceSelection()
            ui.openDestinationConfirmation(selected)
            ui.navigationStatus("voice=destination_candidate_selected index=$oneBasedIndex")
            output.speakInteraction(
                "${selectedDestinationSpeechKo(selected)} 길안내 시작을 선택하면 " +
                    "현재 상태를 확인한 뒤 안내를 시작합니다.",
            )
            return
        }
        // S06-40 (FP-026-DETAIL-01): while walking the number only selects the place, as at home; the
        // route is requested after a separate "길안내 시작" for this selection. Until then it stays.
        val owner = walkingDestinationChangeOwnerOrNull() ?: return
        walkingDestinationChange.offer(selected, owner, scheduler.nowMs())
        search().clearVoiceSelection()
        ui.navigationStatus("voice=destination_candidate_selected index=$oneBasedIndex start_pending=true")
        output.speakInteraction(
            "${selectedDestinationSpeechKo(selected)} " + if (routeRun().active || route.requestPending()) {
                "지금 길안내는 그대로 계속됩니다. 이 장소로 경로를 바꾸려면 길안내 시작이라고 말해 주세요."
            } else {
                "이 장소로 길안내를 시작하려면 길안내 시작이라고 말해 주세요."
            },
        )
    }

    private fun walkingDestinationChangeOwnerOrNull(): Triple<String, Long, WalkRuntimeEpoch>? {
        val actorId = identity.actorId() ?: return null
        return Triple(actorId, identity.processGeneration(), walk.snapshot().epoch)
    }

    /** S06-40: "길안내 시작" consumes the walking selection; a stale or foreign one keeps the route. */
    fun startPendingWalkingDestinationChange(): Boolean {
        if (!walkingDestinationChange.hasPending()) return false
        if (ui.homeContextAvailable()) {
            // The walk is over: the home confirmation screen owns "안내 시작" again.
            walkingDestinationChange.clear()
            return false
        }
        val selected = walkingDestinationChangeOwnerOrNull()?.let { owner ->
            walkingDestinationChange.consume(owner, scheduler.nowMs())
        }
        if (selected == null) {
            ui.navigationStatus("voice=destination_change_expired")
            output.speakInteraction("선택한 목적지의 확인 시간이 지났습니다. 지금 길안내는 그대로입니다. 목적지를 다시 선택해 주세요.")
            return true
        }
        if (!route.onDestinationSelected(selected)) {
            if (routeRun().destination == selected.point && !route.currentRouteLocationAvailable()) {
                output.speakInteraction(
                    "${selected.name} 목적지를 유지했습니다. GPS 위치를 확인한 뒤 " +
                        "같은 후보 번호를 다시 말하고 길안내 시작이라고 말해 주세요.",
                )
            }
            return true
        }
        ui.navigationStatus("voice=destination_change_started")
        output.speakInteraction("${selected.name} 경로를 확인합니다.")
        return true
    }

    /**
     * S06-40 x S05-11: "다시 말해줘"/"다시 듣기" while a walking selection waits for "길안내 시작" repeats
     * that confirmation prompt, not the route guidance. It only reads the selection (peek); the
     * 30 s window and the one-shot consume by "길안내 시작" are unchanged.
     */
    fun repeatPendingWalkingDestinationChange(): Boolean {
        if (!walkingDestinationChange.hasPending() || ui.homeContextAvailable()) return false
        val waiting = walkingDestinationChangeOwnerOrNull()?.let { owner ->
            walkingDestinationChange.peek(owner, scheduler.nowMs())
        } ?: return false
        ui.navigationStatus("voice=destination_change_prompt_repeated")
        output.speakInteraction(
            "${selectedDestinationSpeechKo(waiting)} " + if (routeRun().active || route.requestPending()) {
                "지금 길안내는 그대로 계속됩니다. 이 장소로 경로를 바꾸려면 길안내 시작이라고 말해 주세요."
            } else {
                "이 장소로 길안내를 시작하려면 길안내 시작이라고 말해 주세요."
            },
        )
        return true
    }

    /**
     * Same-name places stay distinguishable: repeat the address the candidate was read with.
     * S06-7: the chosen candidate is also said with its search-time distance basis, in the HOME and
     * the walking branch alike (the walking branch has no confirmation screen before "길안내 시작").
     */
    private fun selectedDestinationSpeechKo(selected: DestinationSearchResult): String =
        listOfNotNull(
            selected.speechAddressOrNull()?.let { "${selected.name}, ${it}에 있는 장소를 선택했습니다." }
                ?: "${selected.name} 목적지를 선택했습니다.",
            kr.co.hanium.dreamup.walksafe.navigation.selectedDestinationDistanceSentenceKo(
                selected,
                search().state.origin,
                scheduler.nowMs(),
            ),
        ).joinToString(" ")
}

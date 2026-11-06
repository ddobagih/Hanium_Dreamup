package kr.co.hanium.dreamup.walksafe.navigation

import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kr.co.hanium.dreamup.walksafe.network.AndroidNavigationRequestCoordinator
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.GatewayCapacityProcessState
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessSnapshot
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionRevalidation
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionRevalidationStatus
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

/** Search requests and candidate state; construction never reads authority or starts work. */
internal class DestinationSearchController(
    private val facts: Facts,
    private val actions: Actions,
    private val backend: Backend,
    private val navigationRequests: AndroidNavigationRequestCoordinator,
    private val worker: Executor,
    private val postToUi: (() -> Unit) -> Unit,
    private val locationFactory: () -> DestinationSearchLocationController?,
) {
    interface Facts {
        fun searchAllowed(): Boolean
        fun rawQuery(): String
        fun epoch(): WalkRuntimeEpoch
        fun actorId(): String?
        fun gatewaySnapshot(): GatewaySessionProcessSnapshot
        fun isSessionCurrent(session: GatewayFieldSession): Boolean
        fun isEpochAllowed(epoch: WalkRuntimeEpoch): Boolean
        fun locationAccessAllowed(): Boolean
        fun locationSourceReady(): Boolean
    }

    interface Backend {
        fun searchCall(session: GatewayFieldSession, query: String, limit: Int, origin: RoutePoint?): CancellableNetworkCall<DestinationSearchResponse>
        fun revalidate(session: GatewayFieldSession, actorId: String?, capacitySessionGeneration: Long): GatewaySessionRevalidation
    }

    interface Actions {
        fun explainUnavailable()
        fun blockRouteMutation(): Boolean
        fun requireActor(): Boolean
        fun networkAllowed(): Boolean
        fun sessionOrNull(): GatewayFieldSession?
        fun ensureLocationPermission(): Boolean
        fun render()
        fun controls(controls: Controls)
        fun navigationStatus(text: String)
        fun status(title: String, detail: String)
        fun speak(message: String)
        fun offerCandidates(state: DestinationSearchVoiceState, message: String = state.voicePrompt())
        fun sessionNotReady(session: GatewayFieldSession)
        /** Captured before state/UI cleanup, then tested after it, matching credential-renewal ordering. */
        fun failureAdmission(session: GatewayFieldSession, error: Exception): (() -> Boolean)?
    }

    data class Controls(val searchEnabled: Boolean? = null, val cancelEnabled: Boolean? = null,
        val moreEnabled: Boolean? = null, val hideMore: Boolean = false)
    data class State(
        val inFlight: Boolean, val generation: Int, val query: String, val zeroResultQuery: String?,
        val page: Int, val providerHasMore: Boolean?, val origin: DestinationSearchOrigin?,
        val results: List<DestinationSearchResult>, val locationMessage: String?,
        val pendingVoiceQuery: String?, val pendingVoicePage: Int?, val voicePage: DestinationSearchVoiceState?,
    )

    private var destinationSearchInFlight = false
    private var destinationSearchLocationController: DestinationSearchLocationController? = null
    private var destinationSearchLocationMessage: String? = null
    private var destinationSearchGeneration = 0
    private var destinationSearchQuery = ""
    private var destinationSearchZeroResultQuery: String? = null
    private var destinationSearchPage = 1
    private var destinationSearchProviderHasMore: Boolean? = null
    private var destinationSearchOrigin: DestinationSearchOrigin? = null
    private val destinationSearchResults = mutableListOf<DestinationSearchResult>()
    private var pendingVoiceDestinationQuery: String? = null
    private var pendingVoiceDestinationPageIndex: Int? = null
    private var destinationSearchVoiceState: DestinationSearchVoiceState? = null

    val state: State get() = State(destinationSearchInFlight, destinationSearchGeneration,
        destinationSearchQuery, destinationSearchZeroResultQuery, destinationSearchPage,
        destinationSearchProviderHasMore, destinationSearchOrigin, destinationSearchResults.toList(),
        destinationSearchLocationMessage, pendingVoiceDestinationQuery, pendingVoiceDestinationPageIndex,
        destinationSearchVoiceState)
    val locationConsumerGeneration: Long get() = destinationSearchLocationController?.consumerGeneration ?: 0L
    val isAcquiring: Boolean get() = destinationSearchLocationController?.isAcquiring == true

    fun clearVoiceRequest() { pendingVoiceDestinationQuery = null; pendingVoiceDestinationPageIndex = null }
    fun clearPendingVoiceQuery() { pendingVoiceDestinationQuery = null }
    fun clearVoicePage() { destinationSearchVoiceState = null }
    fun clearVoiceSelection() { clearVoiceRequest(); clearVoicePage() }
    fun requestVoiceResults(query: String) {
        pendingVoiceDestinationQuery = query
        pendingVoiceDestinationPageIndex = 0
        destinationSearchVoiceState = null
    }
    fun showVoicePage(state: DestinationSearchVoiceState) { destinationSearchVoiceState = state }
    fun clearResults() { destinationSearchResults.clear() }
    fun clearQuery() { destinationSearchQuery = "" }
    fun retirePending() {
        cancelLocation()
        destinationSearchGeneration += 1
        destinationSearchInFlight = false
        clearVoiceSelection()
    }
    fun resetForSearchEntry() {
        retirePending()
        destinationSearchQuery = ""
        destinationSearchPage = 1
        destinationSearchResults.clear()
        destinationSearchOrigin = null
    }

    private fun destinationSearchLocationLeaseOrNull(): DestinationSearchLocationLease? {
        if (!facts.locationAccessAllowed()) return null
        val actorId = facts.actorId()?.takeIf { it.isNotBlank() } ?: return null
        val snapshot = facts.gatewaySnapshot()
        if (snapshot.session == null || snapshot.deletionRecoveryOnly || snapshot.storageBlocked) return null
        return DestinationSearchLocationLease(actorId, snapshot.generation)
    }

    private fun destinationSearchLocationControllerOrNull(): DestinationSearchLocationController? {
        if (!facts.locationSourceReady()) return null
        return destinationSearchLocationController ?: locationFactory()?.also {
            destinationSearchLocationController = it
        }
    }

    fun cancelLocation() {
        destinationSearchLocationController?.cancel()
        destinationSearchLocationMessage = null
    }

    fun requestLocation(query: String? = null, reset: Boolean = true): Boolean {
        val lease = destinationSearchLocationLeaseOrNull()
        val controller = destinationSearchLocationControllerOrNull()
        if (lease == null || controller == null) {
            cancelLocation()
            destinationSearchLocationMessage = "현재 위치를 확인할 수 없습니다. 위치 권한과 위치 서비스를 켠 뒤 검색을 다시 눌러 주세요."
            actions.render()
            if (query != null) actions.speak(requireNotNull(destinationSearchLocationMessage))
            return false
        }
        if (query == null && (controller.isAcquiring || controller.originOrNull(lease) != null)) return true
        destinationSearchLocationMessage = "현재 위치를 확인하고 있습니다. 최대 10초 동안 기다려 주세요."
        actions.controls(Controls(cancelEnabled = true))
        actions.render()
        val requestGeneration = destinationSearchGeneration
        fun currentConsumer(): Boolean = destinationSearchGeneration == requestGeneration &&
            facts.searchAllowed() && destinationSearchLocationLeaseOrNull() == lease &&
            (query == null || canonicalDestinationSearchQueryOrNull(facts.rawQuery()) == query)
        return controller.acquire(
            lease,
            stillAllowed = ::currentConsumer,
        ) { origin ->
            if (!currentConsumer()) return@acquire
            destinationSearchLocationMessage = if (origin == null) {
                "현재 위치를 확인하지 못했습니다. 위치 서비스를 확인하고 검색을 다시 눌러 주세요."
            } else null
            if (origin == null) {
                pendingVoiceDestinationQuery = null
                pendingVoiceDestinationPageIndex = null
                actions.navigationStatus("navigation=destination_search_location_unavailable")
                if (query != null) actions.speak(requireNotNull(destinationSearchLocationMessage))
            }
            actions.render()
            if (origin != null && query != null &&
                canonicalDestinationSearchQueryOrNull(facts.rawQuery()) == query
            ) search(reset)
        }
    }

    fun search(reset: Boolean): Boolean {
        if (!facts.searchAllowed()) {
            actions.explainUnavailable()
            return false
        }
        if (actions.blockRouteMutation()) return false
        val rawQuery = facts.rawQuery()
        if (rawQuery.isBlank()) {
            actions.navigationStatus("navigation=destination_query_missing")
            return false
        }
        val query = canonicalDestinationSearchQueryOrNull(rawQuery) ?: run {
            actions.navigationStatus("navigation=destination_query_invalid max_code_points=80")
            actions.speak("검색할 장소명을 80자 이하로 입력해 주세요.")
            return false
        }
        val expectedWalkEpoch = facts.epoch()
        if (!actions.requireActor()) return false
        if (!actions.networkAllowed()) return false
        val gatewaySession = actions.sessionOrNull() ?: return false
        val gatewayProcessSnapshot = facts.gatewaySnapshot()
        if (
            gatewayProcessSnapshot.session !== gatewaySession ||
            gatewayProcessSnapshot.deletionRecoveryOnly ||
            gatewayProcessSnapshot.storageBlocked
        ) return false
        val expectedGatewaySessionGeneration = gatewayProcessSnapshot.generation
        GatewayCapacityProcessState.fenceSessionGeneration(
            expectedGatewaySessionGeneration,
        )
        if (destinationSearchInFlight || navigationRequests.hasActiveDestinationSearch()) return false
        if (!actions.ensureLocationPermission()) {
            return false
        }
        val resetSearch = reset || destinationSearchQuery != query
        // S06-9: every search asks once for the whole candidate cap, so there is no append request; one
        // would re-fetch the same list from a newer origin and mix two distance bases (S06-13/S06-14).
        if (!resetSearch) return false
        if (resetSearch) {
            destinationSearchPage = 1
            destinationSearchProviderHasMore = null
            destinationSearchResults.clear()
            destinationSearchQuery = query
            destinationSearchZeroResultQuery = null
            destinationSearchVoiceState = null
            actions.render()
        }
        val searchLocationLease = destinationSearchLocationLeaseOrNull()
        val origin = searchLocationLease?.let { destinationSearchLocationControllerOrNull()?.originOrNull(it) }
        if (origin == null) return requestLocation(query, resetSearch)
        destinationSearchLocationMessage = null
        // S06-31: the list keeps its origin accuracy; voice distances and directions stay within it.
        val searchOrigin = searchLocationLease?.let { lease ->
            destinationSearchLocationControllerOrNull()?.searchOriginOrNull(lease)
        }
        val requestedPage = if (resetSearch) 1 else destinationSearchPage + 1
        val retainedCandidates = destinationSearchResults.toList()
        val retainedVoicePageIndex = destinationSearchVoiceState?.takeIf { it.query == query }?.pageIndex ?: 0
        val requestId = destinationSearchGeneration + 1
        destinationSearchGeneration = requestId
        fun isCurrentSearchRequest(): Boolean =
            isLeaseCurrent(expectedWalkEpoch, requestId) &&
                facts.gatewaySnapshot().generation == expectedGatewaySessionGeneration &&
                facts.isSessionCurrent(gatewaySession)
        fun recoverRetainedCandidates(message: String): Boolean {
            if (resetSearch || retainedCandidates.isEmpty() || !isCurrentSearchRequest() ||
                destinationSearchQuery != query
            ) return false
            pendingVoiceDestinationQuery = null
            pendingVoiceDestinationPageIndex = null
            val state = DestinationSearchVoiceState(
                query = query,
                results = retainedCandidates,
                pageIndex = retainedVoicePageIndex.coerceAtMost((retainedCandidates.size - 1) / DESTINATION_SEARCH_PAGE_SIZE),
                moreResultsAvailable = canLoadMore(retainedCandidates.size),
            )
            destinationSearchVoiceState = state
            // Re-offer the retained page with the new request generation; never revive an old lease.
            actions.offerCandidates(state, message + " " + state.voicePrompt())
            return true
        }
        fun handleSearchFailure(error: Exception) {
            if (!isCurrentSearchRequest()) return
            val failureGuard = actions.failureAdmission(gatewaySession, error)
            destinationSearchInFlight = false
            actions.controls(Controls(searchEnabled = true))
            actions.controls(Controls(cancelEnabled = false))
            actions.render()
            val voiceRequest = pendingVoiceDestinationQuery == query
            if (voiceRequest) {
                pendingVoiceDestinationQuery = null
                pendingVoiceDestinationPageIndex = null
            }
            if (failureGuard?.invoke() != true) {
                return
            }
            val failure = classifyNavigationBackendFailure(error)
            // Shared error kinds, search wording: a search failure is not a route failure,
            // and each message names the next step (search again, another name, login).
            val searchFailureMessage = failure.kind.destinationSearchUserMessage
            destinationSearchLocationMessage = searchFailureMessage
            actions.render()
            actions.navigationStatus("navigation=destination_search_failed ${failure.kind.statusToken}")
            actions.status("목적지 검색 실패", searchFailureMessage)
            if (!recoverRetainedCandidates("추가 검색에 실패했습니다. 다시 더 듣기라고 말씀해 주세요.")) {
                actions.speak(searchFailureMessage)
            }
        }
        // S06-9: one request for the whole cap; voice then reads it three at a time from this one list.
        val queryLimit = DESTINATION_SEARCH_MAX_RESULTS
        destinationSearchInFlight = true
        actions.controls(Controls(searchEnabled = false))
        actions.controls(Controls(moreEnabled = false))
        actions.controls(Controls(cancelEnabled = true))
        // The list was cleared for the new query: show "searching" until the response, not a zero result.
        actions.render()
        val searchCall = try {
            navigationRequests.trackDestinationSearch(
                backend.searchCall(
                    session = gatewaySession,
                    query = query,
                    limit = queryLimit,
                    origin = origin,
                ),
            )
        } catch (error: Exception) {
            handleSearchFailure(error)
            return false
        }
        try {
            worker.execute {
                try {
                    if (
                        !isCurrentSearchRequest() ||
                        !facts.isSessionCurrent(gatewaySession)
                    ) {
                        navigationRequests.completeDestinationSearch(searchCall)
                        searchCall.cancel()
                        return@execute
                    }
                    val revalidation = backend.revalidate(
                        gatewaySession,
                        facts.actorId(),
                        capacitySessionGeneration =
                            expectedGatewaySessionGeneration,
                    )
                    if (revalidation.status != GatewaySessionRevalidationStatus.READY) {
                        navigationRequests.completeDestinationSearch(searchCall)
                        searchCall.cancel()
                        postToUi {
                            if (
                                !isCurrentSearchRequest()
                            ) return@postToUi
                            if (
                                revalidation.status ==
                                GatewaySessionRevalidationStatus.NOT_READY
                            ) {
                                actions.sessionNotReady(gatewaySession)
                            } else {
                                destinationSearchInFlight = false
                                actions.controls(Controls(searchEnabled = true))
                                actions.controls(Controls(cancelEnabled = false))
                                actions.render()
                                recoverRetainedCandidates("추가 검색을 잠시 진행할 수 없습니다. 다시 더 듣기라고 말씀해 주세요.")
                            }
                            if (pendingVoiceDestinationQuery == query) {
                                pendingVoiceDestinationQuery = null
                                pendingVoiceDestinationPageIndex = null
                            }
                            actions.navigationStatus(
                                "navigation=destination_search_wait " +
                                    "gateway=${revalidation.status.name.lowercase(Locale.US)}",
                            )
                        }
                        return@execute
                    }
                    if (
                        !isCurrentSearchRequest() ||
                        !facts.isSessionCurrent(gatewaySession)
                    ) {
                        navigationRequests.completeDestinationSearch(searchCall)
                        searchCall.cancel()
                        return@execute
                    }
                    val result = searchCall.execute()
                    if (!facts.isSessionCurrent(gatewaySession)) {
                        navigationRequests.completeDestinationSearch(searchCall)
                        postToUi {
                            if (
                                !isCurrentSearchRequest()
                            ) return@postToUi
                            destinationSearchInFlight = false
                            actions.controls(Controls(searchEnabled = true))
                            actions.controls(Controls(cancelEnabled = false))
                            actions.render()
                            actions.navigationStatus("navigation=destination_search_cancelled session_changed")
                            if (pendingVoiceDestinationQuery == query) {
                                pendingVoiceDestinationQuery = null
                                pendingVoiceDestinationPageIndex = null
                            }
                        }
                        return@execute
                    }
                    navigationRequests.completeDestinationSearch(searchCall)
                    postToUi {
                        if (
                            !isCurrentSearchRequest()
                        ) return@postToUi
                        if (!facts.isSessionCurrent(gatewaySession)) {
                            destinationSearchInFlight = false
                            actions.controls(Controls(searchEnabled = true))
                            actions.controls(Controls(cancelEnabled = false))
                            actions.render()
                            actions.navigationStatus("navigation=destination_search_cancelled session_changed")
                            if (pendingVoiceDestinationQuery == query) {
                                pendingVoiceDestinationQuery = null
                                pendingVoiceDestinationPageIndex = null
                            }
                            return@postToUi
                        }
                        destinationSearchInFlight = false
                        actions.controls(Controls(searchEnabled = true))
                        actions.controls(Controls(cancelEnabled = false))
                        destinationSearchPage = requestedPage
                        destinationSearchProviderHasMore = result.moreResultsAvailable
                        if (resetSearch) destinationSearchOrigin = searchOrigin
                        val merged = if (resetSearch) result.results else {
                            destinationSearchResults + result.results
                        }
                        destinationSearchResults.clear()
                        // Existing candidates come first, so announced numbers never move; only the newly
                        // added tail is limited, and the list never exceeds the candidate cap (S06-12).
                        destinationSearchResults.addAll(
                            merged.distinctBy { "${it.id}:${it.point.latitude}:${it.point.longitude}" }
                                .take(DESTINATION_SEARCH_MAX_RESULTS),
                        )
                        destinationSearchZeroResultQuery = if (destinationSearchResults.isEmpty()) query else null
                        actions.render()
                        actions.navigationStatus("navigation=search_results query=${result.query} count=${result.results.size}")
                        if (pendingVoiceDestinationQuery == query) {
                            pendingVoiceDestinationQuery = null
                            val requestedPageIndex = pendingVoiceDestinationPageIndex ?: 0
                            pendingVoiceDestinationPageIndex = null
                            val lastPageIndex = if (destinationSearchResults.isEmpty()) {
                                0
                            } else {
                                (destinationSearchResults.size - 1) / DESTINATION_SEARCH_PAGE_SIZE
                            }
                            if (requestedPageIndex <= lastPageIndex) {
                                destinationSearchVoiceState = DestinationSearchVoiceState(
                                    query = query,
                                    results = destinationSearchResults.toList(),
                                    pageIndex = requestedPageIndex,
                                    moreResultsAvailable = canLoadMore(),
                                    origin = destinationSearchOrigin,
                                )
                                actions.offerCandidates(requireNotNull(destinationSearchVoiceState))
                            } else {
                                // A "more" merge that added no new unique candidate is not the end
                                // of the search while the provider still reports more results.
                                val providerHasMore = canLoadMore()
                                destinationSearchVoiceState = DestinationSearchVoiceState(
                                    query = query,
                                    results = destinationSearchResults.toList(),
                                    pageIndex = lastPageIndex,
                                    moreResultsAvailable = providerHasMore,
                                )
                                val state = requireNotNull(destinationSearchVoiceState)
                                val lead = if (providerHasMore) {
                                    "이번에는 새 후보를 찾지 못했습니다. '더 듣기'로 다시 찾을 수 있습니다. "
                                } else {
                                    "더 안내할 목적지 후보가 없습니다. "
                                }
                                actions.offerCandidates(state, lead + state.voicePrompt())
                            }
                        }
                    }
                } catch (_: CancellationException) {
                    navigationRequests.completeDestinationSearch(searchCall)
                    postToUi {
                        if (
                            !isCurrentSearchRequest()
                        ) return@postToUi
                        destinationSearchInFlight = false
                        actions.controls(Controls(searchEnabled = true))
                        actions.controls(Controls(cancelEnabled = false))
                        actions.render()
                        actions.navigationStatus("navigation=destination_search_cancelled")
                        recoverRetainedCandidates("추가 검색을 완료하지 못했습니다. 다시 더 듣기라고 말씀해 주세요.")
                    }
                } catch (error: Exception) {
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    navigationRequests.completeDestinationSearch(searchCall)
                    postToUi {
                        if (
                            !isCurrentSearchRequest()
                        ) return@postToUi
                        handleSearchFailure(error)
                    }
                } finally {
                    navigationRequests.completeDestinationSearch(searchCall)
                }
            }
            return true
        } catch (_: RejectedExecutionException) {
            navigationRequests.completeDestinationSearch(searchCall)
            searchCall.cancel()
            if (!isCurrentSearchRequest()) {
                return false
            }
            destinationSearchInFlight = false
            actions.controls(Controls(searchEnabled = true))
            actions.controls(Controls(cancelEnabled = false))
            actions.render()
            actions.navigationStatus("navigation=destination_search_failed executor_rejected")
            if (recoverRetainedCandidates("추가 검색을 시작할 수 없습니다. 다시 더 듣기라고 말씀해 주세요.")) {
                return false
            }
            if (pendingVoiceDestinationQuery == query) {
                pendingVoiceDestinationQuery = null
                pendingVoiceDestinationPageIndex = null
                actions.speak("${query} 목적지 검색을 시작할 수 없습니다.")
            }
            return false
        }
    }

    /** One "more candidates" rule for voice, screen and failure recovery (S06-10/11). */
    fun canLoadMore(resultCount: Int = destinationSearchResults.size): Boolean =
        destinationSearchCanRequestMore(
            resultCount = resultCount,
            // S06-9: each search already requested the whole cap, so no larger request can add candidates.
            requestedCount = DESTINATION_SEARCH_MAX_RESULTS,
            maxResults = DESTINATION_SEARCH_MAX_RESULTS,
            providerMoreAvailable = destinationSearchProviderHasMore,
        )

    fun isLeaseCurrent(expectedWalkEpoch: WalkRuntimeEpoch, expectedRequestId: Int): Boolean =
        expectedRequestId == destinationSearchGeneration && facts.isEpochAllowed(expectedWalkEpoch)

    fun cancel() {
        val locationWasActive = isAcquiring
        cancelLocation()
        clearRequest(navigationRequests.cancelDestinationSearch() || locationWasActive)
    }

    fun clearRequest(transportWasActive: Boolean) {
        if (!destinationSearchInFlight && !transportWasActive) return
        pendingVoiceDestinationQuery = null
        pendingVoiceDestinationPageIndex = null
        destinationSearchGeneration += 1
        destinationSearchInFlight = false
        actions.controls(Controls(searchEnabled = true))
        actions.controls(Controls(cancelEnabled = false))
        actions.controls(Controls(moreEnabled = false))
        actions.controls(Controls(hideMore = true))
        actions.render()
        actions.navigationStatus("navigation=destination_search_cancelled")
    }

    companion object {
        private const val DESTINATION_SEARCH_PAGE_SIZE = 3
        private const val DESTINATION_SEARCH_MAX_RESULTS = 10
    }
}

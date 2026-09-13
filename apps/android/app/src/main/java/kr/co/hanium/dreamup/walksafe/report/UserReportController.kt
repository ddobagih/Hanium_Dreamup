package kr.co.hanium.dreamup.walksafe.report

import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.UserReportHttpException
import kr.co.hanium.dreamup.walksafe.network.UserReportNetworkClient
import kr.co.hanium.dreamup.walksafe.network.UserReportProtocolException

/**
 * [localIdentityEpoch] is a local lease only. User-rights calls separately require the v7 session's
 * authenticated Backend actor and account-generation binding.
 */
internal data class UserReportAuthority(
    val session: GatewayFieldSession,
    val sessionGeneration: Long,
    val localIdentityEpoch: Long,
) {
    init {
        require(sessionGeneration >= 0L)
        require(localIdentityEpoch > 0L)
    }
}

internal enum class UserReportUiPhase {
    SIGNED_OUT,
    IDLE,
    LOADING_LIST,
    LOADING_MORE,
    LOADING_REQUEST_HISTORY,
    LOADING_MORE_REQUEST_HISTORY,
    LOADING_DETAIL,
    LOADING_CONTENT,
    LOADING_REQUEST_STATUS,
    LOADING_DELETION_STATUS,
    SUBMITTING_REQUEST,
    SUBMITTING_CORRECTION,
    READY,
    EMPTY,
    ERROR,
}

internal enum class UserReportFailure {
    NOT_FOUND_OR_SIGNED_OUT,
    NETWORK_RESTRICTED,
    INVALID_REQUEST,
    LOCAL_TRACKING,
    TEMPORARY,
    MALFORMED_RESPONSE,
}

internal data class UserReportUiState(
    val phase: UserReportUiPhase = UserReportUiPhase.SIGNED_OUT,
    val statusFilter: UserReportStatus? = null,
    val reports: List<UserReportSummary> = emptyList(),
    val nextCursor: String? = null,
    val requestHistoryItems: List<UserReportRequestHistoryItem> = emptyList(),
    val requestHistoryNextCursor: String? = null,
    val requestHistoryReportId: String? = null,
    val requestHistorySnapshotRevision: Long = 0L,
    val requestHistoryTotalCount: Long = 0L,
    val requestHistoryLoaded: Boolean = false,
    val requestHistoryRefreshSequence: Long = 0L,
    val selectedDetail: UserReportDetail? = null,
    val selectedContent: UserReportContentCurrent? = null,
    val latestCorrection: UserReportContentRevision? = null,
    val latestCreatedRequest: UserReportRequestSummary? = null,
    val trackedRequestReferences: List<UserReportRequestReference> = emptyList(),
    val selectedRequestStatus: UserReportRequestSummary? = null,
    val trackedDeletionRequestIds: List<String> = emptyList(),
    val selectedDeletionStatus: UserReportDeletionStatus? = null,
    val failure: UserReportFailure? = null,
    val retryAvailable: Boolean = false,
)

internal class UserReportController(
    private val client: UserReportNetworkClient,
    private val workerExecutor: Executor,
    private val callbackExecutor: Executor,
    private val authorityProvider: () -> UserReportAuthority?,
    private val observer: (UserReportUiState) -> Unit,
    private val deletionTracker: UserReportDeletionTracker? = null,
    private val requestIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val correctionIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val networkAllowedProvider: () -> Boolean = { true },
) {
    private val lock = Any()
    private var generation = 0L
    private var authorityChangeSequence = 0L
    private var destroyed = false
    private var state = UserReportUiState()
    private var boundAuthority: UserReportAuthority? = null
    private var active: ActiveOperation? = null
    private var retryAction: Action? = null
    private var pendingRequestIntent: UserReportRequestIntent? = null
    private var pendingCorrectionIntent: UserReportCorrectionIntent? = null

    fun snapshot(): UserReportUiState = synchronized(lock) { state }

    fun onNetworkPolicyChanged() {
        val nextState = synchronized(lock) {
            if (destroyed || boundAuthority == null) return
            val networkAllowed = networkAllowedProvider()
            if (networkAllowed && active == null) return
            interruptForNetworkChangeLocked(active?.action ?: retryAction, networkAllowed)
        }
        publish(nextState)
    }

    fun onAuthorityChanged() {
        val sequence = synchronized(lock) {
            if (destroyed) return
            authorityChangeSequence += 1L
            authorityChangeSequence
        }
        val next = authorityProvider()
        val nextState: UserReportUiState?
        synchronized(lock) {
            if (
                destroyed ||
                sequence != authorityChangeSequence ||
                !exactNullableAuthority(next, authorityProvider())
            ) return
            val previous = boundAuthority
            if (previous != null && next != null && exactAuthority(previous, next)) return
            generation += 1L
            active?.call?.cancel()
            active = null
            retryAction = null
            pendingRequestIntent = null
            pendingCorrectionIntent = null
            boundAuthority = next
            state = UserReportUiState(
                phase = if (next == null) {
                    UserReportUiPhase.SIGNED_OUT
                } else {
                    UserReportUiPhase.IDLE
                },
                statusFilter = state.statusFilter,
            )
            nextState = state
        }
        nextState?.let(::publish)
    }

    fun loadReports(statusFilter: UserReportStatus? = snapshot().statusFilter): Boolean =
        startList(cursor = null, statusFilter = statusFilter)

    fun loadNextPage(): Boolean {
        val snapshot = snapshot()
        val cursor = snapshot.nextCursor ?: return false
        return startList(cursor = cursor, statusFilter = snapshot.statusFilter)
    }

    fun loadRequestHistory(reportId: String? = null): Boolean =
        startRequestHistory(cursor = null, reportId = reportId)

    fun loadNextRequestHistoryPage(): Boolean {
        val snapshot = snapshot()
        val cursor = snapshot.requestHistoryNextCursor ?: return false
        return startRequestHistory(
            cursor = cursor,
            reportId = snapshot.requestHistoryReportId,
        )
    }

    fun openDetail(reportId: String): Boolean {
        if (!validCanonicalUserReportUuid(reportId)) return false
        val current = snapshot()
        if (current.reports.none { it.reportId == reportId }) return false
        return start(
            action = Action.Detail(reportId),
            loadingPhase = UserReportUiPhase.LOADING_DETAIL,
            prepare = { before ->
                before.copy(
                    selectedDetail = null,
                    selectedContent = null,
                    latestCorrection = null,
                    latestCreatedRequest = null,
                    selectedRequestStatus = null,
                    selectedDeletionStatus = null,
                )
            },
            createCall = { authority -> client.reportDetailCall(authority.session, reportId) },
        ) { result, before, _ ->
            require(result.reportId == reportId)
            before.copy(
                phase = UserReportUiPhase.READY,
                selectedDetail = result,
                failure = null,
                retryAvailable = false,
            )
        }
    }

    fun loadContent(reportId: String): Boolean {
        if (!validCanonicalUserReportUuid(reportId)) return false
        if (snapshot().selectedDetail?.reportId != reportId) return false
        return start(
            action = Action.Content(reportId),
            loadingPhase = UserReportUiPhase.LOADING_CONTENT,
            createCall = { authority -> client.reportContentCall(authority.session, reportId) },
        ) { result, before, _ ->
            require(result.reportId == reportId)
            before.copy(
                phase = UserReportUiPhase.READY,
                selectedContent = result,
                latestCorrection = null,
                failure = null,
                retryAvailable = false,
            )
        }
    }

    fun submitCorrection(
        reportId: String,
        userDescription: UserReportCorrectionPatch<String>,
        categoryHint: UserReportCorrectionPatch<UserReportContentCategory>,
    ): Boolean {
        val intent = synchronized(lock) {
            if (destroyed || active != null) return false
            val current = state.selectedContent
            if (current?.reportId != reportId) return false
            pendingCorrectionIntent?.let { pending ->
                if (
                    pending.reportId != reportId ||
                    pending.expectedRevision != current.revision ||
                    pending.userDescription != userDescription ||
                    pending.categoryHint != categoryHint
                ) return false
                pending
            } ?: runCatching {
                UserReportCorrectionIntent(
                    reportId = reportId,
                    expectedRevision = current.revision,
                    idempotencyKey = correctionIdFactory(),
                    userDescription = userDescription,
                    categoryHint = categoryHint,
                )
            }.getOrNull()?.also { pendingCorrectionIntent = it } ?: return false
        }
        return startCorrection(intent)
    }

    fun refreshDeletionStatus(requestId: String): Boolean {
        if (!validCanonicalUserReportUuid(requestId)) return false
        val current = snapshot()
        if (requestId !in current.trackedDeletionRequestIds) return false
        val expectedReportId = current.trackedRequestReferences.singleOrNull {
            it.requestId == requestId && it.requestType == UserReportRequestType.DELETE
        }?.reportId ?: current.selectedDetail
            ?.takeIf { detail ->
                detail.latestRequest?.let { request ->
                    request.requestId == requestId &&
                        request.requestType == UserReportRequestType.DELETE
                } == true
            }
            ?.reportId
        return start(
            action = Action.DeletionStatus(requestId),
            loadingPhase = UserReportUiPhase.LOADING_DELETION_STATUS,
            precondition = { locked ->
                requestId in locked.trackedDeletionRequestIds &&
                    (
                        expectedReportId == null ||
                            locked.trackedRequestReferences.any {
                                it.reportId == expectedReportId &&
                                    it.requestId == requestId &&
                                    it.requestType == UserReportRequestType.DELETE
                            } ||
                            locked.selectedDetail?.let { detail ->
                                detail.reportId == expectedReportId &&
                                    detail.latestRequest?.let { request ->
                                        request.requestId == requestId &&
                                            request.requestType == UserReportRequestType.DELETE
                                    } == true
                            } == true
                    )
            },
            prepare = { before -> before.copy(selectedDeletionStatus = null) },
            createCall = { authority ->
                client.reportDeletionStatusCall(authority.session, requestId)
            },
        ) { result, before, _ ->
            require(result.requestId == requestId)
            require(expectedReportId == null || result.reportId == expectedReportId)
            before.copy(
                phase = UserReportUiPhase.READY,
                selectedDeletionStatus = result,
                failure = null,
                retryAvailable = false,
            )
        }
    }

    fun refreshRequestStatus(reportId: String, requestId: String): Boolean {
        if (
            !validCanonicalUserReportUuid(reportId) ||
            !validCanonicalUserReportUuid(requestId)
        ) return false
        val current = snapshot()
        if (current.selectedDetail?.reportId != reportId) return false
        if (
            current.requestHistoryItems.any { item ->
                item.source == UserReportRequestHistorySource.DELETION_TOMBSTONE &&
                    item.reportId == reportId &&
                    item.requestId == requestId
            }
        ) return false
        val reference = current.trackedRequestReferences.singleOrNull {
            it.reportId == reportId && it.requestId == requestId
        } ?: current.selectedDetail.latestRequest
            ?.takeIf { it.requestId == requestId }
            ?.let {
                UserReportRequestReference(
                    reportId = reportId,
                    requestId = it.requestId,
                    requestType = it.requestType,
                )
            }
            ?: return false
        return start(
            action = Action.RequestStatus(reference),
            loadingPhase = UserReportUiPhase.LOADING_REQUEST_STATUS,
            precondition = { locked ->
                val selected = locked.selectedDetail
                selected?.reportId == reference.reportId &&
                    locked.requestHistoryItems.none { item ->
                        item.source == UserReportRequestHistorySource.DELETION_TOMBSTONE &&
                            item.reportId == reference.reportId &&
                            item.requestId == reference.requestId
                    } &&
                    (
                        locked.trackedRequestReferences.any { it == reference } ||
                            selected.latestRequest?.let {
                                it.requestId == reference.requestId &&
                                    it.requestType == reference.requestType
                            } == true
                    )
            },
            prepare = { before -> before.copy(selectedRequestStatus = null) },
            createCall = { authority ->
                client.reportRequestStatusCall(
                    session = authority.session,
                    reportId = reference.reportId,
                    requestId = reference.requestId,
                )
            },
        ) { result, before, _ ->
            require(result.requestId == reference.requestId)
            require(result.requestType == reference.requestType)
            before.copy(
                phase = UserReportUiPhase.READY,
                selectedRequestStatus = result,
                failure = null,
                retryAvailable = false,
            )
        }
    }

    fun submitRequest(
        reportId: String,
        requestType: UserReportRequestType,
        requestText: String,
    ): Boolean {
        if (!validCanonicalUserReportUuid(reportId) || !validUserReportRequestText(requestText)) {
            return false
        }
        if (requestType == UserReportRequestType.DELETE) {
            val session = authorityProvider()?.session ?: return false
            if (
                deletionTracker == null ||
                !session.isBackendAccountDeviceBound ||
                session.backendAccountGeneration == null
            ) return false
        }
        val intent = synchronized(lock) {
            if (destroyed || active != null) return false
            if (state.selectedDetail?.reportId != reportId) return false
            pendingRequestIntent?.let { pending ->
                if (
                    pending.reportId != reportId ||
                    pending.requestType != requestType ||
                    pending.requestText != requestText
                ) return false
                pending
            } ?: runCatching {
                UserReportRequestIntent(
                    clientRequestId = requestIdFactory(),
                    reportId = reportId,
                    requestType = requestType,
                    requestText = requestText,
                )
            }.getOrNull()?.also { pendingRequestIntent = it } ?: return false
        }
        return startRequest(intent)
    }

    fun retry(): Boolean = when (val action = synchronized(lock) {
        if (destroyed || active != null) return false
        retryAction
    }) {
        is Action.List -> startList(action.cursor, action.statusFilter)
        is Action.RequestHistory -> startRequestHistory(action.cursor, action.reportId)
        is Action.Detail -> openDetail(action.reportId)
        is Action.Content -> loadContent(action.reportId)
        is Action.RequestStatus -> refreshRequestStatus(
            action.reference.reportId,
            action.reference.requestId,
        )
        is Action.Request -> {
            val pending = synchronized(lock) { pendingRequestIntent }
            if (pending != action.intent) false else startRequest(action.intent)
        }
        is Action.Correction -> {
            val pending = synchronized(lock) { pendingCorrectionIntent }
            if (pending != action.intent) false else startCorrection(action.intent)
        }
        is Action.DeletionStatus -> refreshDeletionStatus(action.requestId)
        null -> false
    }

    fun onDestroy() {
        synchronized(lock) {
            if (destroyed) return
            destroyed = true
            generation += 1L
            active?.call?.cancel()
            active = null
            retryAction = null
            pendingRequestIntent = null
            pendingCorrectionIntent = null
            boundAuthority = null
            state = UserReportUiState(statusFilter = state.statusFilter)
        }
    }

    private fun startList(
        cursor: String?,
        statusFilter: UserReportStatus?,
    ): Boolean {
        if (cursor != null && !validUserReportCursor(cursor)) return false
        val action = Action.List(cursor = cursor, statusFilter = statusFilter)
        return start(
            action = action,
            loadingPhase = if (cursor == null) {
                UserReportUiPhase.LOADING_LIST
            } else {
                UserReportUiPhase.LOADING_MORE
            },
            prepare = { before ->
                if (cursor == null) {
                    before.copy(
                        statusFilter = statusFilter,
                        nextCursor = null,
                        selectedDetail = null,
                        selectedContent = null,
                        latestCorrection = null,
                        latestCreatedRequest = null,
                        selectedRequestStatus = null,
                        selectedDeletionStatus = null,
                    )
                } else {
                    require(before.statusFilter == statusFilter && before.nextCursor == cursor)
                    before
                }
            },
            createCall = { authority ->
                client.listReportsCall(
                    session = authority.session,
                    limit = PAGE_LIMIT,
                    cursor = cursor,
                    userStatus = statusFilter,
                )
            },
        ) { result, before, _ ->
            require(result.nextCursor == null || result.nextCursor != cursor)
            val existing = if (cursor == null) emptyList() else before.reports
            val existingIds = existing.mapTo(mutableSetOf(), UserReportSummary::reportId)
            require(result.items.none { it.reportId in existingIds })
            val reports = existing + result.items
            before.copy(
                phase = readyPhase(reports, before.requestHistoryItems),
                statusFilter = statusFilter,
                reports = reports,
                nextCursor = result.nextCursor,
                requestHistoryRefreshSequence = if (cursor == null) {
                    before.requestHistoryRefreshSequence + 1L
                } else {
                    before.requestHistoryRefreshSequence
                },
                failure = null,
                retryAvailable = false,
            )
        }
    }

    private fun startRequestHistory(
        cursor: String?,
        reportId: String?,
    ): Boolean {
        if (cursor != null && !validUserReportCursor(cursor)) return false
        if (reportId != null && !validCanonicalUserReportUuid(reportId)) return false
        val action = Action.RequestHistory(cursor = cursor, reportId = reportId)
        return start(
            action = action,
            loadingPhase = if (cursor == null) {
                UserReportUiPhase.LOADING_REQUEST_HISTORY
            } else {
                UserReportUiPhase.LOADING_MORE_REQUEST_HISTORY
            },
            precondition = { before ->
                cursor == null ||
                    (
                        before.requestHistoryLoaded &&
                            before.requestHistoryNextCursor == cursor &&
                            before.requestHistoryReportId == reportId
                        )
            },
            createCall = { authority ->
                client.requestHistoryCall(
                    session = authority.session,
                    limit = PAGE_LIMIT,
                    cursor = cursor,
                    reportId = reportId,
                )
            },
        ) { result, before, _ ->
            require(result.reportId == reportId)
            require(result.nextCursor == null || result.nextCursor != cursor)
            val existing = if (cursor == null) emptyList() else before.requestHistoryItems
            if (cursor == null) {
                require(
                    result.totalCount == 0L ||
                        (
                            result.items.isNotEmpty() &&
                                result.items.first().revision == result.snapshotRevision
                            ),
                )
            } else {
                require(result.items.isNotEmpty())
                require(result.snapshotRevision == before.requestHistorySnapshotRevision)
                require(result.totalCount == before.requestHistoryTotalCount)
                val previousRevision = existing.lastOrNull()?.revision
                val nextRevision = result.items.firstOrNull()?.revision
                require(
                    previousRevision == null ||
                        nextRevision == null ||
                        previousRevision > nextRevision,
                )
            }
            val existingRevisions = existing.mapTo(mutableSetOf()) { it.revision }
            val existingRequestIds = existing.mapTo(mutableSetOf()) { it.requestId }
            require(result.items.none { it.revision in existingRevisions })
            require(result.items.none { it.requestId in existingRequestIds })
            val items = existing + result.items
            require(items.size.toLong() <= result.totalCount)
            require(
                (result.nextCursor == null) ==
                    (items.size.toLong() == result.totalCount),
            )
            val tombstones = items.filter {
                it.source == UserReportRequestHistorySource.DELETION_TOMBSTONE
            }
            val tombstonedReportIds = tombstones.mapTo(mutableSetOf()) { it.reportId }
            val tombstonedRequestIds = tombstones.mapTo(mutableSetOf()) { it.requestId }
            val tombstonedRequests = tombstones.mapTo(mutableSetOf()) {
                it.reportId to it.requestId
            }
            val reports = before.reports.filterNot { report ->
                report.reportId in tombstonedReportIds ||
                    report.latestRequest?.let {
                        report.reportId to it.requestId in tombstonedRequests
                    } == true
            }
            val activeRequestReferences = items.asReversed().mapNotNull { item ->
                item.request?.let { request ->
                    UserReportRequestReference(
                        reportId = item.reportId,
                        requestId = item.requestId,
                        requestType = request.requestType,
                    )
                }
            }.filterNot { reference ->
                reference.reportId in tombstonedReportIds ||
                    reference.reportId to reference.requestId in tombstonedRequests
            }
            val selectedDetail = before.selectedDetail?.takeUnless { detail ->
                detail.reportId in tombstonedReportIds ||
                    detail.latestRequest?.let {
                        detail.reportId to it.requestId in tombstonedRequests
                    } == true
            }
            val selectedDetailRemoved = before.selectedDetail != null && selectedDetail == null
            pendingRequestIntent = pendingRequestIntent?.takeUnless {
                it.reportId in tombstonedReportIds
            }
            pendingCorrectionIntent = pendingCorrectionIntent?.takeUnless {
                it.reportId in tombstonedReportIds
            }
            val selectedReportId = selectedDetail?.reportId
            val requestSummaryWasTombstoned: (UserReportRequestSummary) -> Boolean = {
                selectedDetailRemoved ||
                    if (selectedReportId == null) {
                        it.requestId in tombstonedRequestIds
                    } else {
                        selectedReportId to it.requestId in tombstonedRequests
                    }
            }
            before.copy(
                phase = readyPhase(reports, items),
                reports = reports,
                requestHistoryItems = items,
                requestHistoryNextCursor = result.nextCursor,
                requestHistoryReportId = result.reportId,
                requestHistorySnapshotRevision = result.snapshotRevision,
                requestHistoryTotalCount = result.totalCount,
                requestHistoryLoaded = true,
                trackedRequestReferences = activeRequestReferences,
                trackedDeletionRequestIds = activeRequestReferences
                    .filter { it.requestType == UserReportRequestType.DELETE }
                    .map(UserReportRequestReference::requestId),
                selectedDetail = selectedDetail,
                selectedContent = if (selectedDetailRemoved) {
                    null
                } else {
                    before.selectedContent?.takeUnless { it.reportId in tombstonedReportIds }
                },
                latestCorrection = if (selectedDetailRemoved) {
                    null
                } else {
                    before.latestCorrection?.takeUnless { it.reportId in tombstonedReportIds }
                },
                latestCreatedRequest = if (selectedDetailRemoved) {
                    null
                } else {
                    before.latestCreatedRequest?.takeUnless(requestSummaryWasTombstoned)
                },
                selectedRequestStatus = if (selectedDetailRemoved) {
                    null
                } else {
                    before.selectedRequestStatus?.takeUnless(requestSummaryWasTombstoned)
                },
                selectedDeletionStatus = if (selectedDetailRemoved) {
                    null
                } else {
                    before.selectedDeletionStatus?.takeUnless {
                        it.reportId to it.requestId in tombstonedRequests
                    }
                },
                failure = null,
                retryAvailable = false,
            )
        }
    }

    private fun startRequest(intent: UserReportRequestIntent): Boolean =
        start(
            action = Action.Request(intent),
            loadingPhase = UserReportUiPhase.SUBMITTING_REQUEST,
            prepare = { before -> before.copy(selectedRequestStatus = null) },
            createCall = { authority -> client.createRequestCall(authority.session, intent) },
        ) { result, before, authority ->
            require(result.requestType == intent.requestType)
            val reference = UserReportRequestReference(
                reportId = intent.reportId,
                requestId = result.requestId,
                requestType = result.requestType,
            )
            val trackingSucceeded = deletionTracker?.let { tracker ->
                runCatching { tracker.trackRequest(authority.session, reference) }
                    .getOrDefault(false)
            } ?: true
            val trackedRequestReferences = if (
                deletionTracker != null && trackingSucceeded
            ) {
                (before.trackedRequestReferences + reference)
                    .distinctBy(UserReportRequestReference::requestId)
            } else {
                before.trackedRequestReferences
            }
            val trackedDeletionRequestIds = if (
                intent.requestType == UserReportRequestType.DELETE && trackingSucceeded
            ) {
                requireNotNull(deletionTracker)
                (before.trackedDeletionRequestIds + result.requestId).distinct()
            } else {
                before.trackedDeletionRequestIds
            }
            pendingRequestIntent = null
            val reports = before.reports.map { report ->
                if (report.reportId == intent.reportId) {
                    report.copy(latestRequest = result)
                } else {
                    report
                }
            }
            val detail = before.selectedDetail?.let { selected ->
                if (selected.reportId == intent.reportId) {
                    selected.copy(latestRequest = result)
                } else {
                    selected
                }
            }
            before.copy(
                phase = if (trackingSucceeded) {
                    UserReportUiPhase.READY
                } else {
                    UserReportUiPhase.ERROR
                },
                reports = reports,
                selectedDetail = detail,
                latestCreatedRequest = result,
                trackedRequestReferences = trackedRequestReferences,
                trackedDeletionRequestIds = trackedDeletionRequestIds,
                requestHistoryRefreshSequence = before.requestHistoryRefreshSequence + 1L,
                failure = if (trackingSucceeded) null else UserReportFailure.LOCAL_TRACKING,
                retryAvailable = false,
            )
        }

    private fun startCorrection(intent: UserReportCorrectionIntent): Boolean =
        start(
            action = Action.Correction(intent),
            loadingPhase = UserReportUiPhase.SUBMITTING_CORRECTION,
            createCall = { authority ->
                client.correctReportContentCall(authority.session, intent)
            },
        ) { result, before, _ ->
            require(result.reportId == intent.reportId)
            require(result.expectedRevision == intent.expectedRevision)
            require(result.idempotencyKey == intent.idempotencyKey)
            pendingCorrectionIntent = null
            before.copy(
                phase = UserReportUiPhase.READY,
                selectedContent = UserReportContentCurrent(
                    reportId = result.reportId,
                    revision = result.revision,
                    contentSha256 = result.contentSha256,
                    userDescription = result.userDescription,
                    categoryHint = result.categoryHint,
                    correctedAt = result.correctedAt,
                ),
                latestCorrection = result,
                failure = null,
                retryAvailable = false,
            )
        }

    private fun <T> start(
        action: Action,
        loadingPhase: UserReportUiPhase,
        precondition: (UserReportUiState) -> Boolean = { true },
        prepare: (UserReportUiState) -> UserReportUiState = { it },
        createCall: (UserReportAuthority) -> CancellableNetworkCall<T>,
        applyResult: (T, UserReportUiState, UserReportAuthority) -> UserReportUiState,
    ): Boolean {
        val operation: ActiveOperation
        val call: CancellableNetworkCall<T>
        val loading: UserReportUiState
        synchronized(lock) {
            if (destroyed || active != null) return false
            val authority = authorityProvider()
            val bound = boundAuthority
            if (authority == null || bound == null || !exactAuthority(bound, authority)) {
                generation += 1L
                retryAction = null
                pendingRequestIntent = null
                pendingCorrectionIntent = null
                boundAuthority = authority
                state = UserReportUiState(
                    phase = if (authority == null) {
                        UserReportUiPhase.SIGNED_OUT
                    } else {
                        UserReportUiPhase.IDLE
                    },
                    statusFilter = state.statusFilter,
                    failure = if (authority == null) {
                        UserReportFailure.NOT_FOUND_OR_SIGNED_OUT
                    } else {
                        null
                    },
                )
                publish(state)
                return false
            }
            if (!precondition(state)) return false
            if (!networkAllowedProvider()) {
                publish(interruptForNetworkChangeLocked(action, networkAllowed = false))
                return false
            }
            call = runCatching { createCall(authority) }.getOrElse {
                state = state.copy(
                    phase = UserReportUiPhase.ERROR,
                    failure = UserReportFailure.INVALID_REQUEST,
                    retryAvailable = false,
                )
                publish(state)
                return false
            }
            generation += 1L
            operation = ActiveOperation(
                generation = generation,
                authority = authority,
                action = action,
                call = call,
                previousState = state,
            )
            active = operation
            retryAction = null
            loading = prepare(state).copy(
                phase = loadingPhase,
                failure = null,
                retryAvailable = false,
            )
            state = loading
        }
        publish(loading)
        return try {
            workerExecutor.execute {
                if (!canExecute(operation)) return@execute
                val result = runCatching { call.execute() }
                try {
                    callbackExecutor.execute {
                        complete(operation, call, result, applyResult)
                    }
                } catch (_: RejectedExecutionException) {
                    runCatching { call.cancel() }
                    failToSchedule(operation)
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            runCatching { call.cancel() }
            failToSchedule(operation)
            false
        }
    }

    private fun canExecute(operation: ActiveOperation): Boolean {
        val blocked = synchronized(lock) {
            if (destroyed || active !== operation || operation.generation != generation) {
                return false
            }
            if (networkAllowedProvider()) return true
            interruptForNetworkChangeLocked(operation.action, networkAllowed = false)
        }
        publish(blocked)
        return false
    }

    private fun interruptForNetworkChangeLocked(
        action: Action?,
        networkAllowed: Boolean,
    ): UserReportUiState {
        val interrupted = active
        generation += 1L
        active = null
        retryAction = action
        state = (interrupted?.previousState ?: state).copy(
            phase = UserReportUiPhase.ERROR,
            failure = if (networkAllowed) {
                UserReportFailure.TEMPORARY
            } else {
                UserReportFailure.NETWORK_RESTRICTED
            },
            retryAvailable = action != null,
        )
        runCatching { interrupted?.call?.cancel() }
        return state
    }

    private fun <T> complete(
        operation: ActiveOperation,
        call: CancellableNetworkCall<T>,
        result: Result<T>,
        applyResult: (T, UserReportUiState, UserReportAuthority) -> UserReportUiState,
    ) {
        val nextState: UserReportUiState?
        var cursorRecovery: Action.RequestHistory? = null
        synchronized(lock) {
            val currentAuthority = authorityProvider()
            if (
                destroyed ||
                active !== operation ||
                operation.call !== call ||
                operation.generation != generation
            ) {
                return
            }
            if (
                currentAuthority == null ||
                !exactAuthority(operation.authority, currentAuthority)
            ) {
                generation += 1L
                active = null
                retryAction = null
                pendingRequestIntent = null
                pendingCorrectionIntent = null
                boundAuthority = currentAuthority
                state = UserReportUiState(
                    phase = if (currentAuthority == null) {
                        UserReportUiPhase.SIGNED_OUT
                    } else {
                        UserReportUiPhase.IDLE
                    },
                    statusFilter = state.statusFilter,
                    failure = if (currentAuthority == null) {
                        UserReportFailure.NOT_FOUND_OR_SIGNED_OUT
                    } else {
                        null
                    },
                )
                nextState = state
                return@synchronized
            }
            if (!networkAllowedProvider()) {
                nextState = interruptForNetworkChangeLocked(
                    operation.action,
                    networkAllowed = false,
                )
                return@synchronized
            }
            active = null
            nextState = result.fold(
                onSuccess = { value ->
                    runCatching { applyResult(value, state, operation.authority) }.fold(
                        onSuccess = { applied ->
                            retryAction = null
                            state = applied
                            state
                        },
                        onFailure = {
                            retryAction = operation.action
                            state = state.copy(
                                phase = UserReportUiPhase.ERROR,
                                failure = UserReportFailure.MALFORMED_RESPONSE,
                                retryAvailable = true,
                            )
                            state
                        },
                    )
                },
                onFailure = { error ->
                    val concealedCollectionFailure =
                        (error as? UserReportHttpException)?.statusCode == 404 &&
                            (
                                operation.action is Action.List ||
                                    (
                                        operation.action is Action.RequestHistory &&
                                            operation.action.reportId == null
                                        )
                                )
                    if (concealedCollectionFailure) {
                        retryAction = null
                        pendingRequestIntent = null
                        pendingCorrectionIntent = null
                        state = UserReportUiState(
                            phase = UserReportUiPhase.ERROR,
                            statusFilter = state.statusFilter,
                            requestHistoryRefreshSequence =
                                state.requestHistoryRefreshSequence,
                            failure = UserReportFailure.NOT_FOUND_OR_SIGNED_OUT,
                            retryAvailable = false,
                        )
                        return@fold state
                    }
                    val historyCursorFailure = (operation.action as? Action.RequestHistory)
                        ?.takeIf {
                            it.cursor != null &&
                                (error as? UserReportHttpException)?.statusCode == 422
                        }
                    if (historyCursorFailure != null) {
                        retryAction = null
                        cursorRecovery = historyCursorFailure.copy(cursor = null)
                        state = state.copy(
                            phase = readyPhase(state.reports, state.requestHistoryItems),
                            failure = null,
                            retryAvailable = false,
                        )
                        return@fold state
                    }
                    val terminalRequestFailure =
                        operation.action is Action.Request ||
                            operation.action is Action.Correction
                    val terminalIntentFailure =
                        terminalRequestFailure &&
                            error is UserReportHttpException &&
                            error.statusCode in TERMINAL_REQUEST_STATUS_CODES
                    if (terminalIntentFailure) {
                        if (operation.action is Action.Request) pendingRequestIntent = null
                        if (operation.action is Action.Correction) pendingCorrectionIntent = null
                        retryAction = null
                    } else {
                        retryAction = operation.action
                    }
                    state = state.copy(
                        phase = UserReportUiPhase.ERROR,
                        failure = classifyFailure(error),
                        retryAvailable = !terminalIntentFailure,
                    )
                    state
                },
            )
        }
        cursorRecovery?.let { recovery ->
            startRequestHistory(cursor = null, reportId = recovery.reportId)
        } ?: nextState?.let(::publish)
    }

    private fun failToSchedule(operation: ActiveOperation) {
        val failed = synchronized(lock) {
            if (destroyed || active !== operation) return
            active = null
            retryAction = operation.action
            state = state.copy(
                phase = UserReportUiPhase.ERROR,
                failure = UserReportFailure.TEMPORARY,
                retryAvailable = true,
            )
            state
        }
        publish(failed)
    }

    private fun publish(value: UserReportUiState) {
        try {
            callbackExecutor.execute {
                val deliver = synchronized(lock) {
                    !destroyed &&
                        state === value &&
                        exactNullableAuthority(boundAuthority, authorityProvider())
                }
                if (deliver) observer(value)
            }
        } catch (_: RejectedExecutionException) {
            Unit
        }
    }

    private fun classifyFailure(error: Throwable): UserReportFailure = when {
        error is UserReportProtocolException -> UserReportFailure.MALFORMED_RESPONSE
        (error as? UserReportHttpException)?.statusCode == 404 ->
            UserReportFailure.NOT_FOUND_OR_SIGNED_OUT
        error.statusCodeOrNull() in setOf(409, 413, 415, 422) ->
            UserReportFailure.INVALID_REQUEST
        else -> UserReportFailure.TEMPORARY
    }

    private fun Throwable.statusCodeOrNull(): Int? =
        (this as? UserReportHttpException)?.statusCode

    private fun readyPhase(
        reports: List<UserReportSummary>,
        requestHistoryItems: List<UserReportRequestHistoryItem>,
    ): UserReportUiPhase = if (reports.isEmpty() && requestHistoryItems.isEmpty()) {
        UserReportUiPhase.EMPTY
    } else {
        UserReportUiPhase.READY
    }

    private fun exactAuthority(
        expected: UserReportAuthority,
        current: UserReportAuthority,
    ): Boolean =
        current.session === expected.session &&
            current.sessionGeneration == expected.sessionGeneration &&
            current.localIdentityEpoch == expected.localIdentityEpoch

    private fun exactNullableAuthority(
        expected: UserReportAuthority?,
        current: UserReportAuthority?,
    ): Boolean = when {
        expected == null || current == null -> expected == null && current == null
        else -> exactAuthority(expected, current)
    }

    private data class ActiveOperation(
        val generation: Long,
        val authority: UserReportAuthority,
        val action: Action,
        val call: CancellableNetworkCall<*>,
        val previousState: UserReportUiState,
    )

    private sealed interface Action {
        data class List(
            val cursor: String?,
            val statusFilter: UserReportStatus?,
        ) : Action

        data class RequestHistory(
            val cursor: String?,
            val reportId: String?,
        ) : Action

        data class Detail(val reportId: String) : Action

        data class Content(val reportId: String) : Action

        data class RequestStatus(val reference: UserReportRequestReference) : Action

        data class Request(val intent: UserReportRequestIntent) : Action

        data class Correction(val intent: UserReportCorrectionIntent) : Action

        data class DeletionStatus(val requestId: String) : Action
    }

    private companion object {
        const val PAGE_LIMIT = 25
        val TERMINAL_REQUEST_STATUS_CODES = setOf(404, 409, 413, 415, 422)
    }
}

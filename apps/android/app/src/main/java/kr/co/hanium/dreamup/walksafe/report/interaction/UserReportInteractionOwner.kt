package kr.co.hanium.dreamup.walksafe.report.interaction

import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessSnapshot
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionScope
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionVerificationState
import kr.co.hanium.dreamup.walksafe.report.*
import kr.co.hanium.dreamup.walksafe.session.PermissionSessionPolicy

/** User-rights commands, authority publication and expiry; Controller/Presenter remain single peers. */
internal class UserReportInteractionOwner(
    private val reports: Reports,
    private val presentation: Presentation,
    private val activity: Activity,
    private val dialogs: Dialogs,
    private val gatewaySnapshot: () -> GatewaySessionProcessSnapshot,
    private val deletionProcessingBlocked: () -> Boolean,
    private val permissions: () -> PermissionSessionPolicy,
    private val wallClockMs: () -> Long,
    private val localQueue: (() -> AndroidReportQueueStore)? = null,
    private val localInteractionEpoch: () -> Any? = { null },
    private val localExecutor: java.util.concurrent.Executor = java.util.concurrent.Executor { it.run() },
) {
    interface Reports {
        fun initialized(): Boolean
        fun snapshot(): UserReportUiState
        fun onAuthorityChanged()
        fun loadReports()
        fun loadReports(filter: UserReportStatus?)
        fun retry()
        fun loadNextPage()
        fun loadNextRequestHistoryPage()
        fun loadContent(reportId: String)
        fun openDetail(reportId: String)
        fun loadRequestHistory()
        fun submitCorrection(reportId: String, userDescription: UserReportCorrectionPatch<String>, categoryHint: UserReportCorrectionPatch<UserReportContentCategory>): Boolean
        fun submitRequest(reportId: String, requestType: UserReportRequestType, requestText: String): Boolean
        fun refreshDeletionStatus(requestId: String): Boolean
        fun refreshRequestStatus(reportId: String, requestId: String): Boolean
    }
    data class CorrectionDraft(
        val rawDescription: String,
        val clearDescription: Boolean,
        val categoryHint: UserReportCorrectionPatch<UserReportContentCategory>,
    )
    interface Presentation {
        fun initialized(): Boolean
        fun revalidateAuthority(authority: UserReportAuthority?)
        fun render(state: UserReportUiState, authority: UserReportAuthority?)
        fun clearForAuthorityFence()
        fun setStatusMessage(message: String)
        fun correctionDraft(): CorrectionDraft
        fun requestText(): String
        fun toggleDescriptionClear()
        fun nextCategory()
        fun renderLocalQuarantine(summaries: List<LocalQuarantinedReportSummary>, selectedReportId: String?) {}
    }
    interface Activity {
        fun foreground(): Boolean
        fun destroyed(): Boolean
        fun removeTimer(task: Runnable)
        fun postTimer(task: Runnable, delayMs: Long): Boolean
        fun runOnUiThread(action: () -> Unit) { action() }
    }
    fun interface Dialogs {
        fun confirm(title: String, message: String, negativeLabel: String, positiveLabel: String, onConfirmed: () -> Unit)
    }
    private data class LocalSelection(val summary: LocalQuarantinedReportSummary,
        val authority: UserReportAuthority, val interactionEpoch: Any, val generation: Long)
    @Volatile private var localQuarantineSummaries = emptyList<LocalQuarantinedReportSummary>()
    @Volatile private var localQuarantineAuthority: UserReportAuthority? = null
    @Volatile private var localQuarantineEpoch: Any? = null
    @Volatile private var localListToken: Long? = null
    @Volatile private var localQuarantineSelection: LocalSelection? = null
    @Volatile private var localQuarantineGeneration = 0L

    fun invalidateLocalQuarantineSelection() {
        localQuarantineGeneration += 1L
        localQuarantineSelection = null
        localQuarantineSummaries = emptyList()
        localQuarantineAuthority = null
        localQuarantineEpoch = null
        localListToken = null
        if (presentation.initialized()) presentation.renderLocalQuarantine(emptyList(), null)
    }

    fun refreshLocalQuarantine(onLoaded: (List<String>) -> Unit = {}) {
        invalidateLocalQuarantineSelection()
        val authority = currentUserReportAuthorityOrNull()
        if (authority == null || !activity.foreground() || activity.destroyed() || localInteractionEpoch() == null) {
            setUserReportStatusMessage("기기 보관 신고: 현재 계정 상태를 확인해 주세요.")
            onLoaded(emptyList())
            return
        }
        val epoch = localInteractionEpoch() ?: return
        val generation = localQuarantineGeneration
        try {
            localExecutor.execute {
                val result = runCatching {
                    checkNotNull(localQueue?.invoke()).quarantinedSummaries(authority.session.actorId, authority.session.backendAccountGeneration)
                }
                activity.runOnUiThread {
                    val current = currentUserReportAuthorityOrNull()
                    if (activity.destroyed() || !activity.foreground() || generation != localQuarantineGeneration ||
                        localInteractionEpoch() != epoch || current?.session !== authority.session ||
                        current.sessionGeneration != authority.sessionGeneration || current.localIdentityEpoch != authority.localIdentityEpoch
                    ) { onLoaded(emptyList()); return@runOnUiThread }
                    if (result.isFailure) {
                        val message = "기기 보관 신고를 확인하지 못했습니다. 다시 확인해 주세요."
                        setUserReportStatusMessage(message)
                        onLoaded(listOf(message))
                        return@runOnUiThread
                    }
                    val summaries = result.getOrThrow()
                    localQuarantineSummaries = summaries
                    localQuarantineAuthority = authority
                    localQuarantineEpoch = epoch
                    localListToken = generation
                    presentation.renderLocalQuarantine(localQuarantineSummaries, null)
                    onLoaded(summaries.map { it.descriptionKo() })
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            setUserReportStatusMessage("기기 보관 신고를 확인하지 못했습니다. 다시 확인해 주세요.")
            onLoaded(emptyList())
        }
    }

    private fun localListIsCurrent(): Boolean {
        val expected = localQuarantineAuthority ?: return false
        val current = currentUserReportAuthorityOrNull() ?: return false
        return activity.foreground() && !activity.destroyed() && localInteractionEpoch() == localQuarantineEpoch &&
            current.session === expected.session && current.sessionGeneration == expected.sessionGeneration &&
            current.localIdentityEpoch == expected.localIdentityEpoch
    }

    fun selectLocalQuarantine(reportId: String): Boolean {
        if (!localListIsCurrent()) { invalidateLocalQuarantineSelection(); return false }
        val authority = currentUserReportAuthorityOrNull() ?: return false
        val epoch = localInteractionEpoch() ?: return false
        if (!activity.foreground() || activity.destroyed()) return false
        val summary = localQuarantineSummaries.singleOrNull { it.reportId == reportId &&
            it.reporterActorId == authority.session.actorId && it.accountGeneration == authority.session.backendAccountGeneration } ?: return false
        localQuarantineGeneration += 1L
        localQuarantineSelection = LocalSelection(summary, authority, epoch, localQuarantineGeneration)
        presentation.renderLocalQuarantine(localQuarantineSummaries, reportId)
        setUserReportStatusMessage(summary.descriptionKo())
        return true
    }

    fun localQuarantineListToken(): Long? = if (localListIsCurrent()) localListToken else null
    fun localQuarantineSelectionToken(): Long? = localQuarantineSelection
        ?.takeIf(::localSelectionIsCurrent)?.generation
    fun localQuarantineDescriptions(): List<String> = if (localListIsCurrent()) localQuarantineSummaries.map { it.descriptionKo() } else emptyList()
    fun localQuarantineDescription(number: Int): String? = if (localListIsCurrent() && number in 1..localQuarantineSummaries.size)
        localQuarantineSummaries[number - 1].descriptionKo() else null
    fun selectLocalQuarantineNumber(number: Int): Boolean = number in 1..localQuarantineSummaries.size &&
        selectLocalQuarantine(localQuarantineSummaries[number - 1].reportId)

    fun confirmLocalQuarantineDelete(confirmationConfidence: Double? = 1.0,
        expectedListToken: Long? = null, expectedSelectionToken: Long? = null): Boolean {
        if ((expectedListToken != null && expectedListToken != localQuarantineListToken()) ||
            (expectedSelectionToken != null && expectedSelectionToken != localQuarantineSelectionToken())) return false
        if (confirmationConfidence == null || !confirmationConfidence.isFinite() || confirmationConfidence !in .8..1.0) return false
        val selected = localQuarantineSelection ?: return false
        if (!localSelectionIsCurrent(selected)) { invalidateLocalQuarantineSelection(); return false }
        val consumed = java.util.concurrent.atomic.AtomicBoolean(false)
        dialogs.confirm("기기에 보관한 신고 삭제 확인",
            selected.summary.descriptionKo() + " 이 기기에 보관한 신고 한 건을 삭제합니다. 서버 접수나 기관 보관본의 삭제 요청은 보내지 않습니다.",
            "취소", "기기 신고 삭제") {
            if (!consumed.compareAndSet(false, true)) return@confirm
            if (!localSelectionIsCurrent(selected)) { invalidateLocalQuarantineSelection(); return@confirm }
            try {
                localExecutor.execute {
                    if (!localSelectionIsCurrent(selected)) return@execute
                    val deleted = runCatching { localQueue?.invoke()?.deleteQuarantinedExact(selected.summary,
                        selected.authority.session.actorId, selected.authority.session.backendAccountGeneration,
                        currentAdmission = { localSelectionIsCurrent(selected) }) == true }.getOrDefault(false)
                    activity.runOnUiThread {
                        if (!localSelectionIsCurrent(selected)) return@runOnUiThread
                        invalidateLocalQuarantineSelection()
                        setUserReportStatusMessage(if (deleted) "기기에 보관한 신고 한 건을 삭제했습니다." else "기기 보관 신고 상태가 바뀌었거나 삭제하지 못했습니다. 다시 확인해 주세요.")
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                invalidateLocalQuarantineSelection()
                setUserReportStatusMessage("기기 보관 신고를 삭제하지 못했습니다. 다시 확인해 주세요.")
            }
        }
        return true
    }

    private fun localSelectionIsCurrent(selected: LocalSelection): Boolean {
        val authority = currentUserReportAuthorityOrNull() ?: return false
        return activity.foreground() && !activity.destroyed() && localQuarantineSelection === selected &&
            localQuarantineGeneration == selected.generation && localInteractionEpoch() == selected.interactionEpoch &&
            authority.session === selected.authority.session && authority.sessionGeneration == selected.authority.sessionGeneration &&
            authority.localIdentityEpoch == selected.authority.localIdentityEpoch
    }

    private var restoredUserReportStatusFilter: UserReportStatus? = null
    private var userReportAuthorityExpiryRunnable: Runnable? = null
    fun restoreStatusFilter(wireValue: String?) {
        restoredUserReportStatusFilter = wireValue?.let(UserReportStatus::fromWireOrNull)
    }
    fun restoredStatusFilter(): UserReportStatus? = restoredUserReportStatusFilter
    fun cycleStatusFilter() = reports.loadReports(nextUserReportStatusFilter(reports.snapshot().statusFilter))
    fun refresh() = reports.loadReports()
    fun retry() = reports.retry()
    fun loadMore() = reports.loadNextPage()
    fun loadHistoryMore() = reports.loadNextRequestHistoryPage()
    fun loadSelectedContent() {
        reports.snapshot().selectedDetail?.let { detail -> reports.loadContent(detail.reportId) }
    }
    fun toggleDescriptionClear() = presentation.toggleDescriptionClear()
    fun nextCategory() = presentation.nextCategory()
    fun openDetail(reportId: String) { invalidateLocalQuarantineSelection(); reports.openDetail(reportId) }
    fun loadRequestHistory() = reports.loadRequestHistory()
    fun initializeRequestsAfterBinding() {
        reports.onAuthorityChanged()
        if (currentUserReportAuthorityOrNull() != null) reports.loadReports(restoredUserReportStatusFilter)
        else renderUserReportState(reports.snapshot())
    }

    fun currentUserReportAuthorityOrNull(): UserReportAuthority? {
        val snapshot = gatewaySnapshot()
        val session = snapshot.session ?: return null
        val firstRun = snapshot.restoredFirstRunSnapshot ?: return null
        val actorId = firstRun.reporterActorBinding?.value ?: return null
        if (
            deletionProcessingBlocked() ||
            snapshot.storageBlocked ||
            snapshot.deletionRecoveryOnly ||
            !firstRun.isComplete ||
            session.sessionScope != GatewaySessionScope.GENERAL ||
            !session.isBackendAccountDeviceBound ||
            session.backendAccountGeneration == null ||
            session.backendDevicePersistenceSnapshotOrNull() == null ||
            session.actorId != actorId ||
            session.verificationState != GatewaySessionVerificationState.VERIFIED ||
            !session.isUsableFor(actorId) ||
            !permissions().isAuthenticatedFor(actorId)
        ) return null
        return UserReportAuthority(
            session = session,
            sessionGeneration = snapshot.generation,
            localIdentityEpoch = firstRun.epoch,
        )
    }

    fun cancelUserReportAuthorityExpirySchedule() {
        val scheduled = userReportAuthorityExpiryRunnable ?: return
        userReportAuthorityExpiryRunnable = null
        activity.removeTimer(scheduled)
    }

    fun scheduleUserReportAuthorityExpiryIfNeeded(
        authority: UserReportAuthority? = currentUserReportAuthorityOrNull(),
    ) {
        cancelUserReportAuthorityExpirySchedule()
        if (
            !activity.foreground() ||
            activity.destroyed() ||
            authority == null
        ) return
        val process = gatewaySnapshot()
        if (!process.matchesUserReportAuthority(authority)) return
        val session = authority.session
        val expiresAtEpochMs = minOf(
            session.accessExpiresAtEpochMs,
            session.idleExpiresAtEpochMs,
            session.absoluteExpiresAtEpochMs,
        )
        lateinit var expiry: Runnable
        expiry = Runnable {
            if (userReportAuthorityExpiryRunnable !== expiry) return@Runnable
            userReportAuthorityExpiryRunnable = null
            if (!activity.foreground() || activity.destroyed()) return@Runnable
            val current = gatewaySnapshot()
            if (!current.matchesUserReportAuthority(authority)) {
                scheduleUserReportAuthorityExpiryIfNeeded()
                return@Runnable
            }
            if (wallClockMs() < expiresAtEpochMs) {
                scheduleUserReportAuthorityExpiryIfNeeded(authority)
                return@Runnable
            }
            clearUserReportRequestUiForAuthorityFence()
            if (reports.initialized()) {
                reports.onAuthorityChanged()
            }
        }
        userReportAuthorityExpiryRunnable = expiry
        val posted = activity.postTimer(
            expiry,
            userReportAuthorityExpiryDelayMs(
                accessExpiresAtEpochMs = session.accessExpiresAtEpochMs,
                idleExpiresAtEpochMs = session.idleExpiresAtEpochMs,
                absoluteExpiresAtEpochMs = session.absoluteExpiresAtEpochMs,
                nowEpochMs = wallClockMs(),
            ),
        )
        if (!posted && userReportAuthorityExpiryRunnable === expiry) {
            userReportAuthorityExpiryRunnable = null
        }
    }

    private fun GatewaySessionProcessSnapshot.matchesUserReportAuthority(
        authority: UserReportAuthority,
    ): Boolean =
        session === authority.session &&
            generation == authority.sessionGeneration &&
            restoredFirstRunSnapshot?.epoch == authority.localIdentityEpoch

    fun revalidateUserReportAuthorityOnResume() {
        invalidateLocalQuarantineSelection()
        val authority = currentUserReportAuthorityOrNull()
        if (presentation.initialized()) presentation.revalidateAuthority(authority)
        if (reports.initialized()) reports.onAuthorityChanged()
        scheduleUserReportAuthorityExpiryIfNeeded(authority)
    }

    fun nextUserReportStatusFilter(current: UserReportStatus?): UserReportStatus? =
        when (current) {
            null -> UserReportStatus.RECEIVED
            UserReportStatus.RECEIVED -> UserReportStatus.REJECTED
            UserReportStatus.REJECTED -> UserReportStatus.INSTITUTION_SUBMITTED
            UserReportStatus.INSTITUTION_SUBMITTED -> UserReportStatus.RESOLVED
            UserReportStatus.RESOLVED -> null
        }

    fun renderUserReportState(state: UserReportUiState) {
        if (activity.destroyed() || !presentation.initialized()) return
        presentation.render(state, currentUserReportAuthorityOrNull())
    }

    fun clearUserReportRequestUiForAuthorityFence() {
        invalidateLocalQuarantineSelection()
        if (presentation.initialized()) presentation.clearForAuthorityFence()
    }

    fun setUserReportStatusMessage(message: String) {
        if (presentation.initialized()) presentation.setStatusMessage(message)
    }

    fun confirmUserReportCorrection() {
        val state = reports.snapshot()
        val detail = state.selectedDetail ?: run {
            setUserReportStatusMessage("내 신고 상태: 먼저 신고 상세를 선택하세요.")
            return
        }
        if (state.selectedContent?.reportId != detail.reportId) {
            setUserReportStatusMessage("내 신고 상태: 현재 신고 내용을 먼저 불러오세요.")
            return
        }
        val draft = presentation.correctionDraft()
        val rawDescription = draft.rawDescription
        val descriptionPatch: UserReportCorrectionPatch<String> = when {
            draft.clearDescription -> UserReportCorrectionPatch.Clear
            rawDescription.isBlank() -> UserReportCorrectionPatch.Omitted
            else -> {
                val canonical =
                    canonicalUserReportCorrectionDescriptionOrNull(rawDescription)
                        ?: run {
                            setUserReportStatusMessage(
                                "내 신고 상태: 정정 설명을 1자 이상 500자 이하로 입력하세요.",
                            )
                            return
                        }
                UserReportCorrectionPatch.Value(canonical)
            }
        }
        if (
            descriptionPatch == UserReportCorrectionPatch.Omitted &&
            draft.categoryHint == UserReportCorrectionPatch.Omitted
        ) {
            setUserReportStatusMessage("내 신고 상태: 정정할 설명이나 분류를 선택하세요.")
            return
        }
        dialogs.confirm(
            title = "신고 내용 정정 확인",
            message = "선택한 신고의 설명과 분류만 새 개정으로 추가합니다. " +
                "기존 개정 기록은 보존됩니다.",
            negativeLabel = "취소", positiveLabel = "정정 반영",
        ) {
                if (
                    !reports.submitCorrection(
                        reportId = detail.reportId,
                        userDescription = descriptionPatch,
                        categoryHint = presentation.correctionDraft().categoryHint,
                    )
                ) {
                    setUserReportStatusMessage(
                        "내 신고 상태: 현재 신고 내용을 다시 불러온 뒤 시도하세요.",
                    )
                }
            }

    }

    fun refreshLatestUserReportDeletionStatus() {
        val state = reports.snapshot()
        val selectedRequestId = state.selectedDetail
            ?.latestRequest
            ?.takeIf { it.requestType == UserReportRequestType.DELETE }
            ?.requestId
            ?.takeIf { it in state.trackedDeletionRequestIds }
        val requestId = selectedRequestId
            ?: state.trackedDeletionRequestIds.lastOrNull()
            ?: run {
                setUserReportStatusMessage("내 신고 상태: 확인할 삭제 요청이 없습니다.")
                return
            }
        if (!reports.refreshDeletionStatus(requestId)) {
            setUserReportStatusMessage(
                "내 신고 상태: 앱 서버 원본과 기관 보관본 삭제 상태를 다시 확인할 수 없습니다.",
            )
        }
    }

    fun refreshLatestUserReportRequestStatus() {
        val state = reports.snapshot()
        val detail = state.selectedDetail ?: run {
            setUserReportStatusMessage("내 신고 상태: 먼저 신고 상세를 선택하세요.")
            return
        }
        val requestId = detail.latestRequest?.requestId
            ?: state.trackedRequestReferences
                .lastOrNull { it.reportId == detail.reportId }
                ?.requestId
            ?: run {
                setUserReportStatusMessage("내 신고 상태: 확인할 정정·삭제 요청이 없습니다.")
                return
            }
        if (!reports.refreshRequestStatus(detail.reportId, requestId)) {
            setUserReportStatusMessage("내 신고 상태: 요청 처리 상태를 다시 확인할 수 없습니다.")
        }
    }

    fun confirmUserReportDeleteRequest() {
        val detail = reports.snapshot().selectedDetail ?: run {
            setUserReportStatusMessage("내 신고 상태: 먼저 신고 상세를 선택하세요.")
            return
        }
        val requestText = presentation.requestText()
        if (!validUserReportRequestText(requestText)) {
            setUserReportStatusMessage(
                "내 신고 상태: 요청 사유를 1자 이상 500자 이하로 입력하세요.",
            )
            return
        }
        dialogs.confirm(
            title = "신고 한 건 삭제 요청 확인",
            message = "선택한 신고 한 건의 삭제를 요청합니다. " +
                "계정과 개인정보 전체 삭제 요청이 아닙니다.",
            negativeLabel = "취소", positiveLabel = "요청 보내기",
        ) {
                reports.submitRequest(
                    reportId = detail.reportId,
                    requestType = UserReportRequestType.DELETE,
                    requestText = requestText,
                )
            }

    }
    companion object {
        fun userReportAuthorityExpiryDelayMs(accessExpiresAtEpochMs: Long, idleExpiresAtEpochMs: Long,
            absoluteExpiresAtEpochMs: Long, nowEpochMs: Long): Long {
            val expiresAtEpochMs = minOf(accessExpiresAtEpochMs, idleExpiresAtEpochMs, absoluteExpiresAtEpochMs)
            return if (nowEpochMs >= expiresAtEpochMs) 0L else expiresAtEpochMs - nowEpochMs
        }
    }
}

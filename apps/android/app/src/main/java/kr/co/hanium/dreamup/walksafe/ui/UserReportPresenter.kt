package kr.co.hanium.dreamup.walksafe.ui

import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kr.co.hanium.dreamup.walksafe.report.LocalQuarantinedReportSummary
import kr.co.hanium.dreamup.walksafe.report.UserReportAuthority
import kr.co.hanium.dreamup.walksafe.report.UserReportContentCategory
import kr.co.hanium.dreamup.walksafe.report.UserReportCorrectionPatch
import kr.co.hanium.dreamup.walksafe.report.UserReportDeletionState
import kr.co.hanium.dreamup.walksafe.report.UserReportDeletionStatus
import kr.co.hanium.dreamup.walksafe.report.UserReportDetail
import kr.co.hanium.dreamup.walksafe.report.UserReportFailure
import kr.co.hanium.dreamup.walksafe.report.UserReportRequestHistoryItem
import kr.co.hanium.dreamup.walksafe.report.UserReportRequestHistorySource
import kr.co.hanium.dreamup.walksafe.report.UserReportRequestSummary
import kr.co.hanium.dreamup.walksafe.report.UserReportSummary
import kr.co.hanium.dreamup.walksafe.report.UserReportUiPhase
import kr.co.hanium.dreamup.walksafe.report.UserReportUiState
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_BUTTON_TEXT
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_LINE
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_NOTICE_FILL
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_COLOR_NOTICE_TEXT
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_CORNER_RADIUS_DP
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_GROUP_GAP_DP
import kr.co.hanium.dreamup.walksafe.ui.WalkSafeViewStyle.Companion.WS_TOUCH_WALK_ACTION_DP
import kotlin.math.roundToInt

/** Owns report presentation and ephemeral drafts for one panel and exact authority. */
internal class UserReportPresenter(
    private val panel: UserReportPanel,
    private val style: WalkSafeViewStyle,
    private val actions: Actions,
    private val dispatcher: UiDispatcher = AndroidUiDispatcher(),
    private val authorityFenceTarget: AuthorityFenceTarget? = null,
) {
    interface UiDispatcher {
        fun isMainThread(): Boolean
        fun post(action: Runnable)
    }

    fun interface AuthorityFenceTarget { fun clear() }

    private class AndroidUiDispatcher : UiDispatcher {
        private val handler by lazy { Handler(Looper.getMainLooper()) }
        override fun isMainThread() = Looper.myLooper() == Looper.getMainLooper()
        override fun post(action: Runnable) { handler.post(action) }
    }

    data class Actions(
        val openDetail: (String) -> Unit,
        val loadRequestHistory: () -> Unit,
        val selectLocalQuarantine: (String) -> Unit = {},
    )

    data class CorrectionDraft(
        val rawDescription: String,
        val clearDescription: Boolean,
        val categoryHint: UserReportCorrectionPatch<UserReportContentCategory>,
    )

    private val context get() = panel.userReportControls.context
    private val authorityPresentationGeneration = java.util.concurrent.atomic.AtomicLong()
    private var renderedUserReportAuthority: UserReportAuthority? = null
    private var renderedUserReportInputReportId: String? = null
    private var renderedUserReportRequestId: String? = null
    private var renderedUserReportCorrectionId: String? = null
    private var renderedUserReports: List<UserReportSummary>? = null
    private var renderedUserReportHistoryItems: List<UserReportRequestHistoryItem>? = null
    private var renderedUserReportHistoryRefreshSequence = 0L
    private var clearUserReportCorrectionDescription = false
    private var userReportCorrectionCategoryPatch:
        UserReportCorrectionPatch<UserReportContentCategory> = UserReportCorrectionPatch.Omitted
    private val userReportDetailButtonsByReportId = mutableMapOf<String, Button>()

    fun revalidateAuthority(authority: UserReportAuthority?) {
        val rendered = renderedUserReportAuthority
        val renderedAuthorityChanged =
            authority != null &&
                rendered != null &&
                (
                    rendered.session !== authority.session ||
                        rendered.sessionGeneration != authority.sessionGeneration ||
                        rendered.localIdentityEpoch != authority.localIdentityEpoch
                    )
        if (authority == null || renderedAuthorityChanged) {
            clearForAuthorityFence()
        }
    }

    fun toggleDescriptionClear() {
        clearUserReportCorrectionDescription = !clearUserReportCorrectionDescription
        if (clearUserReportCorrectionDescription) {
            panel.userReportCorrectionDescriptionInput.text?.clear()
        }
        updateUserReportCorrectionPatchButtons()
    }

    fun nextCategory() {
        userReportCorrectionCategoryPatch =
            nextUserReportCorrectionCategoryPatch(userReportCorrectionCategoryPatch)
        updateUserReportCorrectionPatchButtons()
    }

    fun correctionDraft(): CorrectionDraft = CorrectionDraft(
        rawDescription = panel.userReportCorrectionDescriptionInput.text?.toString().orEmpty(),
        clearDescription = clearUserReportCorrectionDescription,
        categoryHint = userReportCorrectionCategoryPatch,
    )

    fun requestText(): String = panel.userReportRequestTextInput.text?.toString()?.trim().orEmpty()

    fun render(state: UserReportUiState, authority: UserReportAuthority?) {
        authorityPresentationGeneration.incrementAndGet()
        val authorityReady = authority != null
        reconcileUserReportRequestInput(state, authority)
        val refreshRequestHistory =
            authorityReady &&
                state.requestHistoryRefreshSequence >
                renderedUserReportHistoryRefreshSequence
        if (refreshRequestHistory) {
            renderedUserReportHistoryRefreshSequence = state.requestHistoryRefreshSequence
        }
        val busy = refreshRequestHistory || state.phase in setOf(
            UserReportUiPhase.LOADING_LIST,
            UserReportUiPhase.LOADING_MORE,
            UserReportUiPhase.LOADING_REQUEST_HISTORY,
            UserReportUiPhase.LOADING_MORE_REQUEST_HISTORY,
            UserReportUiPhase.LOADING_DETAIL,
            UserReportUiPhase.LOADING_CONTENT,
            UserReportUiPhase.LOADING_REQUEST_STATUS,
            UserReportUiPhase.LOADING_DELETION_STATUS,
            UserReportUiPhase.SUBMITTING_REQUEST,
            UserReportUiPhase.SUBMITTING_CORRECTION,
        )
        val statusMessage = if (refreshRequestHistory) {
            "내 신고 상태: 정정·삭제 요청 이력을 불러오는 중입니다."
        } else when (state.phase) {
            UserReportUiPhase.SIGNED_OUT -> "내 신고 상태: 로그인이 필요합니다."
            UserReportUiPhase.IDLE -> "내 신고 상태: 새로고침을 눌러 확인하세요."
            UserReportUiPhase.LOADING_LIST -> "내 신고 상태: 목록을 불러오는 중입니다."
            UserReportUiPhase.LOADING_MORE -> "내 신고 상태: 다음 목록을 불러오는 중입니다."
            UserReportUiPhase.LOADING_REQUEST_HISTORY ->
                "내 신고 상태: 정정·삭제 요청 이력을 불러오는 중입니다."
            UserReportUiPhase.LOADING_MORE_REQUEST_HISTORY ->
                "내 신고 상태: 다음 정정·삭제 요청 이력을 불러오는 중입니다."
            UserReportUiPhase.LOADING_DETAIL -> "내 신고 상태: 상세 상태를 불러오는 중입니다."
            UserReportUiPhase.LOADING_CONTENT -> "내 신고 상태: 현재 신고 내용을 불러오는 중입니다."
            UserReportUiPhase.LOADING_REQUEST_STATUS ->
                "내 신고 상태: 정정·삭제 요청 처리 상태를 불러오는 중입니다."
            UserReportUiPhase.LOADING_DELETION_STATUS ->
                "내 신고 상태: 앱 서버 원본과 기관 보관본 삭제 상태를 불러오는 중입니다."
            UserReportUiPhase.SUBMITTING_REQUEST -> "내 신고 상태: 요청을 접수하는 중입니다."
            UserReportUiPhase.SUBMITTING_CORRECTION ->
                "내 신고 상태: 구조화된 내용 정정을 반영하는 중입니다."
            UserReportUiPhase.READY ->
                "내 신고 상태: 신고 ${state.reports.size}건, 요청 이력 " +
                    "${state.requestHistoryItems.size}건을 표시합니다."
            UserReportUiPhase.EMPTY -> "내 신고 상태: 신고와 요청 이력이 없습니다."
            UserReportUiPhase.ERROR -> when (state.failure) {
                UserReportFailure.NOT_FOUND_OR_SIGNED_OUT ->
                    "내 신고 상태 오류: 로그인 또는 신고 소유 상태를 확인할 수 없습니다."
                UserReportFailure.INVALID_REQUEST ->
                    "내 신고 상태 오류: 요청 내용을 확인한 뒤 다시 시도하세요."
                UserReportFailure.LOCAL_TRACKING ->
                    "내 신고 상태 오류: 요청은 접수됐지만 이 기기에 상태 추적 정보를 저장하지 못했습니다."
                UserReportFailure.MALFORMED_RESPONSE ->
                    "내 신고 상태 오류: 서버 응답 형식을 확인할 수 없습니다."
                UserReportFailure.NETWORK_RESTRICTED ->
                    "내 신고: Wi-Fi에 연결하거나 모바일 데이터 사용을 허용한 뒤 다시 시도하세요."
                UserReportFailure.TEMPORARY,
                null,
                -> "내 신고 상태 오류: 연결을 확인한 뒤 다시 시도하세요."
            }
        }
        setStatusMessage(statusMessage)
        val filterLabel = state.statusFilter?.labelKo ?: "전체"
        panel.userReportFilterButton.text = "상태 필터: $filterLabel"
        panel.userReportFilterButton.contentDescription = "내 신고 상태 필터: $filterLabel"
        panel.userReportFilterButton.isEnabled = authorityReady && !busy
        panel.userReportRefreshButton.isEnabled = authorityReady && !busy
        panel.userReportRetryButton.visibility =
            if (state.retryAvailable) View.VISIBLE else View.GONE
        panel.userReportRetryButton.isEnabled = state.retryAvailable && authorityReady && !busy
        panel.userReportMoreButton.visibility =
            if (state.nextCursor != null) View.VISIBLE else View.GONE
        panel.userReportMoreButton.isEnabled = state.nextCursor != null && authorityReady && !busy
        renderUserReportList(state.reports, busy)
        val historyStatus = when {
            !authorityReady -> "요청 이력: 로그인이 필요합니다."
            refreshRequestHistory ||
                (
                    !state.requestHistoryLoaded &&
                        state.phase == UserReportUiPhase.LOADING_REQUEST_HISTORY
                    ) ->
                "요청 이력: 첫 페이지를 불러오는 중입니다."
            !state.requestHistoryLoaded -> "요청 이력: 신고 목록 확인 뒤 불러옵니다."
            state.requestHistoryItems.isEmpty() -> "요청 이력: 표시할 이력이 없습니다."
            else ->
                "요청 이력: ${state.requestHistoryItems.size}건 / 전체 " +
                    "${state.requestHistoryTotalCount}건"
        }
        panel.userReportHistoryStatusText.text = historyStatus
        panel.userReportHistoryStatusText.contentDescription = historyStatus
        renderUserReportRequestHistory(state.requestHistoryItems)
        panel.userReportHistoryMoreButton.visibility =
            if (state.requestHistoryNextCursor != null) View.VISIBLE else View.GONE
        panel.userReportHistoryMoreButton.isEnabled =
            state.requestHistoryNextCursor != null && authorityReady && !busy
        val detail = state.selectedDetail
        val detailMessage = detail?.let {
            userReportDetailText(
                detail = it,
                state = state,
            )
        } ?: state.selectedDeletionStatus?.let { deletion ->
            "삭제 요청 상태\n${userReportDeletionStatusText(deletion)}"
        } ?: "신고를 선택하면 상세 상태가 표시됩니다."
        panel.userReportDetailText.text = detailMessage
        panel.userReportDetailText.contentDescription = detailMessage
        val requestAvailable = detail != null && authorityReady && !busy
        panel.userReportRequestTextInput.isEnabled = requestAvailable
        panel.userReportDeleteButton.isEnabled = requestAvailable
        panel.userReportContentButton.isEnabled = requestAvailable
        val correctionAvailable =
            state.selectedContent?.reportId == detail?.reportId && requestAvailable
        panel.userReportCorrectionDescriptionInput.isEnabled =
            correctionAvailable && !clearUserReportCorrectionDescription
        panel.userReportCorrectionDescriptionClearButton.isEnabled = correctionAvailable
        panel.userReportCorrectionCategoryButton.isEnabled = correctionAvailable
        panel.userReportCorrectionButton.isEnabled = correctionAvailable
        val selectedReportHasRequest = detail?.let { selected ->
            selected.latestRequest != null || state.trackedRequestReferences.any {
                it.reportId == selected.reportId
            }
        } == true
        panel.userReportRequestStatusButton.text = if (selectedReportHasRequest) {
            "선택 신고 요청 처리 상태 확인"
        } else {
            "선택 신고 요청 처리 상태 없음"
        }
        panel.userReportRequestStatusButton.contentDescription =
            panel.userReportRequestStatusButton.text
        panel.userReportRequestStatusButton.isEnabled =
            requestAvailable && selectedReportHasRequest
        val trackedDeletionCount = state.trackedDeletionRequestIds.size
        panel.userReportDeletionStatusButton.text = if (trackedDeletionCount == 0) {
            "신고 물리 삭제·기관 보관본 상태 없음"
        } else {
            "신고 물리 삭제·기관 보관본 상태 확인 ($trackedDeletionCount 건)"
        }
        panel.userReportDeletionStatusButton.contentDescription =
            panel.userReportDeletionStatusButton.text
        panel.userReportDeletionStatusButton.isEnabled =
            authorityReady && !busy && trackedDeletionCount > 0
        state.latestCreatedRequest?.let { created ->
            if (renderedUserReportRequestId != created.requestId) {
                renderedUserReportRequestId = created.requestId
                panel.userReportRequestTextInput.text?.clear()
            }
        }
        state.latestCorrection?.let { correction ->
            if (renderedUserReportCorrectionId != correction.idempotencyKey) {
                renderedUserReportCorrectionId = correction.idempotencyKey
                panel.userReportCorrectionDescriptionInput.text?.clear()
                clearUserReportCorrectionDescription = false
                userReportCorrectionCategoryPatch = UserReportCorrectionPatch.Omitted
                updateUserReportCorrectionPatchButtons()
            }
        }
        if (refreshRequestHistory) {
            actions.loadRequestHistory()
        }
    }

    private fun renderUserReportList(
        reports: List<UserReportSummary>,
        busy: Boolean,
    ) {
        if (renderedUserReports != reports) {
            val focusedReportId = panel.userReportListContainer.findFocus()?.tag as? String
            panel.userReportListContainer.removeAllViews()
            userReportDetailButtonsByReportId.clear()
            reports.forEachIndexed { index, report ->
                val summary = TextView(context).apply {
                    text = userReportSummaryText(report)
                    contentDescription = text
                    textSize = 16f
                    setTextColor(WS_COLOR_BUTTON_TEXT)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                }
                val detailButton = Button(context).apply {
                    text = "${index + 1}번 신고 상세 보기"
                    contentDescription =
                        "${index + 1}번 신고 상세 보기. 현재 상태 ${report.userStatus.labelKo}"
                    tag = report.reportId
                    minimumHeight = style.accessibilityTargetSizePx()
                    minimumWidth = style.accessibilityTargetSizePx()
                    isSingleLine = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    setOnClickListener { actions.openDetail(report.reportId) }
                }
                style.applyWsButtonStyle(detailButton, WS_TOUCH_WALK_ACTION_DP)
                panel.userReportListContainer.addView(summary)
                panel.userReportListContainer.addView(detailButton)
                userReportDetailButtonsByReportId[report.reportId] = detailButton
            }
            renderedUserReports = reports.toList()
            focusedReportId?.let { reportId ->
                userReportDetailButtonsByReportId[reportId]?.let { target ->
                    target.post { target.requestFocus() }
                }
            }
        }
        userReportDetailButtonsByReportId.values.forEach { it.isEnabled = !busy }
    }

    private fun renderUserReportRequestHistory(
        items: List<UserReportRequestHistoryItem>,
    ) {
        val previous = renderedUserReportHistoryItems
        if (previous == items) return
        val appendFrom = previous?.takeIf {
            it.isNotEmpty() &&
                items.size > it.size &&
                items.subList(0, it.size) == it
        }?.size
        if (appendFrom == null) panel.userReportHistoryContainer.removeAllViews()
        var firstAppendedText: TextView? = null
        items.drop(appendFrom ?: 0).forEach { item ->
            val message = userReportRequestHistoryText(item)
            val historyText = TextView(context).apply {
                text = message
                contentDescription = message
                textSize = 16f
                setTextColor(WS_COLOR_NOTICE_TEXT)
                isFocusable = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
            if (firstAppendedText == null) firstAppendedText = historyText
            panel.userReportHistoryContainer.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val density = context.resources.displayMetrics.density
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = WS_CORNER_RADIUS_DP * density
                        setColor(WS_COLOR_NOTICE_FILL)
                        setStroke((1f * density).roundToInt(), WS_COLOR_LINE)
                    }
                    setPadding(
                        (14f * density).roundToInt(),
                        (12f * density).roundToInt(),
                        (14f * density).roundToInt(),
                        (12f * density).roundToInt(),
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin = (WS_GROUP_GAP_DP * density).roundToInt()
                    }
                    addView(historyText)
                },
            )
        }
        renderedUserReportHistoryItems = items.toList()
        if (appendFrom != null) {
            firstAppendedText?.let { target ->
                target.post {
                    if (
                        target.isAttachedToWindow &&
                        renderedUserReportHistoryItems == items
                    ) {
                        target.requestFocus()
                        target.performAccessibilityAction(
                            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                            null,
                        )
                    }
                }
            }
        }
    }

    private fun userReportRequestHistoryText(item: UserReportRequestHistoryItem): String =
        when (item.source) {
            UserReportRequestHistorySource.ACTIVE_REQUEST -> buildString {
                val request = requireNotNull(item.request)
                append("정정·삭제 요청 이력\n")
                append("신고 번호: ")
                append(item.reportId)
                append("\n")
                append(userReportExactRequestStatusText(request))
                item.deletionStatus?.let { deletion ->
                    append("\n")
                    append(userReportDeletionStatusText(deletion))
                }
            }
            UserReportRequestHistorySource.DELETION_TOMBSTONE -> buildString {
                append("신고 삭제 완료 이력\n")
                append(userReportDeletionStatusText(requireNotNull(item.deletionStatus)))
            }
        }

    fun revealLocalQuarantine() {
        val heading = panel.localQuarantineStatusText
        val reveal = Runnable {
            if (!heading.isAttachedToWindow || !heading.isShown) return@Runnable
            heading.requestRectangleOnScreen(android.graphics.Rect(0, 0, heading.width, heading.height), true)
            heading.requestFocus()
            heading.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        }
        if (heading.width > 0 && heading.height > 0) reveal.run() else heading.post(reveal)
    }

    fun renderLocalQuarantine(summaries: List<LocalQuarantinedReportSummary>, selectedReportId: String?) {
        panel.localQuarantineContainer.removeAllViews()
        val message = if (summaries.isEmpty()) "기기 보관 신고: 표시할 신고가 없습니다."
            else "기기 보관 신고: ${summaries.size}건입니다. 먼저 한 건을 선택한 뒤 별도로 삭제를 확인하세요."
        panel.localQuarantineStatusText.text = message
        panel.localQuarantineStatusText.contentDescription = message
        summaries.forEachIndexed { index, summary ->
            val description = "${index + 1}번. ${summary.descriptionKo()}"
            val button = style.accessiblePriorityUserButton(label = description,
                spokenLabel = description + if (summary.reportId == selectedReportId) " 선택됨." else " 선택.",
                onClick = { actions.selectLocalQuarantine(summary.reportId) })
            panel.localQuarantineContainer.addView(button)
        }
        panel.localQuarantineDeleteButton.isEnabled = summaries.any { it.reportId == selectedReportId }
    }

    fun setStatusMessage(message: String) {
        panel.userReportStatusText.text = message
        panel.userReportStatusText.contentDescription = message
    }

    fun clearForAuthorityFence() {
        renderLocalQuarantine(emptyList(), null)
        val generation = authorityPresentationGeneration.incrementAndGet()
        renderedUserReportAuthority = null
        renderedUserReportInputReportId = null
        renderedUserReportRequestId = null
        renderedUserReportCorrectionId = null
        renderedUserReportHistoryItems = null
        renderedUserReportHistoryRefreshSequence = 0L
        clearUserReportCorrectionDescription = false
        userReportCorrectionCategoryPatch = UserReportCorrectionPatch.Omitted
        val clear = Runnable {
            if (generation != authorityPresentationGeneration.get()) return@Runnable
            if (authorityFenceTarget != null) authorityFenceTarget.clear() else clearAuthorityViews()
        }
        if (dispatcher.isMainThread()) clear.run() else dispatcher.post(clear)
    }

    private fun clearAuthorityViews() {
        panel.userReportRequestTextInput.text?.clear()
        panel.userReportCorrectionDescriptionInput.text?.clear()
        updateUserReportCorrectionPatchButtons()
        panel.userReportHistoryContainer.removeAllViews()
        val message = "요청 이력: 로그인이 필요합니다."
        panel.userReportHistoryStatusText.text = message
        panel.userReportHistoryStatusText.contentDescription = message
        panel.userReportHistoryMoreButton.visibility = View.GONE
        panel.userReportHistoryMoreButton.isEnabled = false
    }

    private fun reconcileUserReportRequestInput(
        state: UserReportUiState,
        authority: UserReportAuthority?,
    ) {
        val previousAuthority = renderedUserReportAuthority
        val sameAuthority = when {
            previousAuthority == null && authority == null -> true
            previousAuthority == null || authority == null -> false
            else ->
                previousAuthority.session === authority.session &&
                    previousAuthority.sessionGeneration == authority.sessionGeneration &&
                    previousAuthority.localIdentityEpoch == authority.localIdentityEpoch
        }
        val selectedReportId = state.selectedDetail?.reportId
        if (!sameAuthority) {
            renderedUserReportHistoryItems = null
            renderedUserReportHistoryRefreshSequence = 0L
        }
        if (
            !sameAuthority ||
            state.phase == UserReportUiPhase.SIGNED_OUT ||
            renderedUserReportInputReportId != selectedReportId
        ) {
            panel.userReportRequestTextInput.text?.clear()
            panel.userReportCorrectionDescriptionInput.text?.clear()
            renderedUserReportRequestId = null
            renderedUserReportCorrectionId = null
            clearUserReportCorrectionDescription = false
            userReportCorrectionCategoryPatch = UserReportCorrectionPatch.Omitted
            updateUserReportCorrectionPatchButtons()
        }
        renderedUserReportAuthority = authority
        renderedUserReportInputReportId = if (authority == null) null else selectedReportId
    }

    private fun userReportSummaryText(report: UserReportSummary): String = buildString {
        append("신고 상태: ")
        append(report.userStatus.labelKo)
        append("\n접수 시각: ")
        append(report.createdAt)
        append("\n신고 번호: ")
        append(report.reportId)
        report.publicRejectionReason?.let {
            append("\n공개 기각 사유: ")
            append(it)
        }
        report.latestRequest?.let {
            append("\n")
            append(userReportRequestResultText(it))
        }
    }

    private fun userReportDetailText(
        detail: UserReportDetail,
        state: UserReportUiState,
    ): String = buildString {
        append("선택 신고 상세\n")
        append("상태: ")
        append(detail.userStatus.labelKo)
        append("\n접수 시각: ")
        append(detail.createdAt)
        append("\n신고 번호: ")
        append(detail.reportId)
        detail.publicRejectionReason?.let {
            append("\n공개 기각 사유: ")
            append(it)
        }
        val selectedRequestStatus = state.selectedRequestStatus
        detail.latestRequest?.takeUnless {
            it.requestId == selectedRequestStatus?.requestId
        }?.let {
            append("\n")
            append(userReportRequestResultText(it))
        }
        state.selectedContent?.takeIf { it.reportId == detail.reportId }?.let { content ->
            append("\n현재 신고 내용 개정: ")
            append(content.revision)
            append("\n설명: ")
            append(content.userDescription ?: "입력 없음")
            append("\n분류: ")
            append(content.categoryHint?.labelKo ?: "선택 없음")
            content.correctedAt?.let {
                append("\n최근 정정 시각: ")
                append(it)
            }
        }
        state.selectedDeletionStatus?.takeIf { it.reportId == detail.reportId }?.let { deletion ->
            append("\n")
            append(userReportDeletionStatusText(deletion))
        }
        selectedRequestStatus?.let { request ->
            append("\n")
            append(userReportExactRequestStatusText(request))
        }
    }

    private fun userReportRequestResultText(request: UserReportRequestSummary): String =
        buildString {
            append("최신 ")
            append(request.requestType.labelKo)
            append(" 결과: ")
            append(request.status.labelKo)
            request.publicResponse?.let {
                append("\n공개 답변: ")
                append(it)
            }
        }

    private fun userReportExactRequestStatusText(request: UserReportRequestSummary): String =
        buildString {
            append("확인한 ")
            append(request.requestType.labelKo)
            append(" 처리 상태: ")
            append(request.status.labelKo)
            request.publicResponse?.let {
                append("\n요청 공개 답변: ")
                append(it)
            }
            append("\n요청 상태 갱신 시각: ")
            append(request.updatedAt)
        }

    private fun userReportDeletionStatusText(deletion: UserReportDeletionStatus): String =
        buildString {
            append("앱 서버 원본 삭제 상태: ")
            append(deletion.state.labelKo)
            append("\n앱 서버 원본 상태 갱신 시각: ")
            append(deletion.updatedAt)
            append("\n기관 보관본 수: ")
            append(deletion.externalCopyCount)
            if (deletion.externalCopies.isEmpty()) {
                append(
                    if (deletion.state == UserReportDeletionState.DELETED) {
                        "\n기관 보관본 상태: 확인된 기관 보관본 없음"
                    } else {
                        "\n기관 보관본 상태: 앱 서버 원본 삭제 완료 전에는 제공되지 않음"
                    },
                )
            } else {
                deletion.externalCopies.forEachIndexed { index, copy ->
                    append("\n기관 보관본 ")
                    append(index + 1)
                    append(" · ")
                    append(copy.institution)
                    append(": ")
                    append(copy.state.labelKo)
                    copy.statusRecordedAt?.let { recordedAt ->
                        append("\n기관 상태 기록 시각: ")
                        append(recordedAt)
                    }
                }
            }
        }

    private fun nextUserReportCorrectionCategoryPatch(
        current: UserReportCorrectionPatch<UserReportContentCategory>,
    ): UserReportCorrectionPatch<UserReportContentCategory> = when (current) {
        UserReportCorrectionPatch.Omitted ->
            UserReportCorrectionPatch.Value(UserReportContentCategory.SIDEWALK_OBSTRUCTION)
        UserReportCorrectionPatch.Clear -> UserReportCorrectionPatch.Omitted
        is UserReportCorrectionPatch.Value -> {
            val categories = UserReportContentCategory.entries
            val nextIndex = categories.indexOf(current.value) + 1
            if (nextIndex in categories.indices) {
                UserReportCorrectionPatch.Value(categories[nextIndex])
            } else {
                UserReportCorrectionPatch.Clear
            }
        }
    }

    private fun updateUserReportCorrectionPatchButtons() {
        panel.userReportCorrectionDescriptionClearButton.text =
            if (clearUserReportCorrectionDescription) {
                "설명: 값 지우기"
            } else {
                "설명: 기존값 유지"
            }
        panel.userReportCorrectionDescriptionClearButton.contentDescription =
            panel.userReportCorrectionDescriptionClearButton.text
        val categoryLabel = when (val patch = userReportCorrectionCategoryPatch) {
            UserReportCorrectionPatch.Omitted -> "기존값 유지"
            UserReportCorrectionPatch.Clear -> "값 지우기"
            is UserReportCorrectionPatch.Value -> patch.value.labelKo
        }
        panel.userReportCorrectionCategoryButton.text = "분류: $categoryLabel"
        panel.userReportCorrectionCategoryButton.contentDescription =
            panel.userReportCorrectionCategoryButton.text
    }
}

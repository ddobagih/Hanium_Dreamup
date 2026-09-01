package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityUserReportStaticTest {
    private val main =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val client =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/network/AndroidUserReportClient.kt")
            .readText()
    private val controller =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/report/UserReportController.kt")
            .readText()
    private val models =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/report/UserReportModels.kt")
            .readText()

    @Test
    fun userReportAuthorityExpiryDelayUsesTheEarliestBoundaryAndNeverRunsLate() {
        assertEquals(100L, userReportAuthorityExpiryDelayMs(1_100L, 1_200L, 1_300L, 1_000L))
        assertEquals(50L, userReportAuthorityExpiryDelayMs(1_200L, 1_050L, 1_300L, 1_000L))
        assertEquals(25L, userReportAuthorityExpiryDelayMs(1_200L, 1_300L, 1_025L, 1_000L))
        assertEquals(0L, userReportAuthorityExpiryDelayMs(1_000L, 1_200L, 1_300L, 1_000L))
        assertEquals(0L, userReportAuthorityExpiryDelayMs(900L, 1_200L, 1_300L, 1_000L))
    }

    @Test
    fun reportHistoryIsScrubbedOnResumeAndAtNaturalSessionExpiry() {
        val resume = main.substringAfter("override fun onResume()")
            .substringBefore("override fun onWindowFocusChanged(")
        val revalidate = main.substringAfter("private fun revalidateUserReportAuthorityOnResume()")
            .substringBefore("private fun nextUserReportStatusFilter(")
        val schedule = main.substringAfter("private fun scheduleUserReportAuthorityExpiryIfNeeded(")
            .substringBefore("private fun GatewaySessionProcessSnapshot.matchesUserReportAuthority(")
        val pause = main.substringAfter("override fun onPause()")
            .substringBefore("internal fun pauseWalkSafeRuntime()")
        val destroy = main.substringAfter("override fun onDestroy()")
            .substringBefore("override fun onRequestPermissionsResult(")

        assertTrue(resume.contains("revalidateUserReportAuthorityOnResume()"))
        assertTrue(revalidate.contains("currentUserReportAuthorityOrNull()"))
        assertTrue(revalidate.contains("clearUserReportRequestUiForAuthorityFence()"))
        assertTrue(revalidate.contains("userReportController.onAuthorityChanged()"))
        assertTrue(revalidate.contains("scheduleUserReportAuthorityExpiryIfNeeded(authority)"))
        assertTrue(schedule.contains("session.accessExpiresAtEpochMs"))
        assertTrue(schedule.contains("session.idleExpiresAtEpochMs"))
        assertTrue(schedule.contains("session.absoluteExpiresAtEpochMs"))
        assertTrue(schedule.contains("current.matchesUserReportAuthority(authority)"))
        assertTrue(schedule.contains("System.currentTimeMillis() < expiresAtEpochMs"))
        assertTrue(schedule.contains("clearUserReportRequestUiForAuthorityFence()"))
        assertTrue(schedule.contains("userReportController.onAuthorityChanged()"))
        assertTrue(pause.contains("cancelUserReportAuthorityExpirySchedule()"))
        assertTrue(destroy.contains("cancelUserReportAuthorityExpirySchedule()"))
    }

    @Test
    fun reportRightsSurfaceUsesTextStatusReflowLiveRegionAndStableFocus() {
        val views = main.substringAfter("userReportStatusText = TextView(this).apply")
            .substringBefore("accountDeletionStatusText = TextView(this).apply")
        val rendering = main.substringAfter("private fun renderUserReportState(")
            .substringBefore("private fun onGatewaySessionButtonClicked()")

        listOf(
            "접수됨",
            "기각됨",
            "기관 제출됨",
            "처리 완료",
            "공개 기각 사유",
            "최신 ",
            "공개 답변",
            "요청 상태 갱신 시각",
        ).forEach { assertTrue((main + models).contains(it)) }
        assertTrue(views.contains("accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE"))
        assertTrue(views.contains("contentDescription"))
        assertTrue(views.contains("minimumHeight = accessibilityTargetSizePx()"))
        assertTrue(rendering.contains("minimumWidth = accessibilityTargetSizePx()"))
        assertTrue(rendering.contains("isSingleLine = false"))
        assertTrue(rendering.contains("focusedReportId"))
        assertTrue(rendering.contains("userReportDetailButtonsByReportId[reportId]"))
        assertTrue(rendering.contains("requestFocus()"))
        assertFalse(rendering.contains("announceForAccessibility("))
        assertFalse(rendering.contains("speakInteraction("))
        assertFalse(rendering.contains("vibrate"))
    }

    @Test
    fun structuredCorrectionAndSingleReportDeletionHaveSeparateExplicitConfirmation() {
        val correction = main.substringAfter("private fun confirmUserReportCorrection(")
            .substringBefore("private fun refreshLatestUserReportDeletionStatus(")
        val deletion = main.substringAfter("private fun confirmUserReportDeleteRequest(")
            .substringBefore("private fun onGatewaySessionButtonClicked()")

        assertTrue(correction.contains("신고 내용 정정 확인"))
        assertTrue(correction.contains("submitCorrection("))
        assertTrue(correction.contains("UserReportCorrectionPatch.Value"))
        assertTrue(deletion.contains("신고 한 건 삭제 요청 확인"))
        assertTrue(deletion.contains("계정과 개인정보 전체 삭제 요청이 아닙니다."))
        assertTrue(deletion.contains("UserReportRequestType.DELETE"))
        assertTrue(main.contains("계정과 개인정보 삭제 요청 확인"))
    }

    @Test
    fun contentCorrectionRequestStatusAndPhysicalDeletionStatusAreReachableFromTheUi() {
        val views = main.substringAfter("userReportStatusText = TextView(this).apply")
            .substringBefore("accountDeletionStatusText = TextView(this).apply")
        val rendering = main.substringAfter("private fun renderUserReportState(")
            .substringBefore("private fun renderUserReportList(")

        assertTrue(views.contains("userReportController.loadContent(detail.reportId)"))
        assertTrue(views.contains("AndroidReportDeletionTrackerStore(applicationContext)"))
        assertTrue(views.contains("refreshLatestUserReportRequestStatus"))
        assertTrue(views.contains("refreshLatestUserReportDeletionStatus"))
        assertTrue(rendering.contains("UserReportUiPhase.LOADING_CONTENT"))
        assertTrue(rendering.contains("UserReportUiPhase.LOADING_REQUEST_STATUS"))
        assertTrue(rendering.contains("UserReportUiPhase.LOADING_DELETION_STATUS"))
        assertTrue(rendering.contains("UserReportUiPhase.SUBMITTING_CORRECTION"))
        assertTrue(rendering.contains("state.selectedContent"))
        assertTrue(main.contains("state.selectedRequestStatus"))
        assertTrue(rendering.contains("state.trackedDeletionRequestIds.size"))
        assertTrue(views.contains("선택 신고 요청 처리 상태 확인"))
        assertTrue(views.contains("신고 물리 삭제·기관 보관본 상태 확인"))
        assertTrue(main.contains("앱 서버 원본 삭제 상태:"))
        assertTrue(main.contains("기관 보관본 수:"))
        assertTrue(main.contains("state.selectedDeletionStatus?.let"))
        assertTrue(models.contains("기관에 삭제 요청 전달됨 · 삭제 완료 아님"))
        assertTrue(models.contains("기관이 삭제 요청 접수를 회신함 · 삭제 완료 아님"))
        assertTrue(models.contains("기관이 삭제 완료를 회신함 · 기관 회신 사실"))
        assertTrue(main.contains("요청은 접수됐지만 이 기기에 상태 추적 정보를 저장하지 못했습니다."))
        assertFalse(main.contains("삭제 처리 상태:"))
    }

    @Test
    fun lifecycleFencesAndRotationRetainOnlyTheNonSensitiveFilter() {
        val save = main.substringAfter("override fun onSaveInstanceState(outState: Bundle)")
            .substringBefore("override fun onPause()")
        val destroy = main.substringAfter("override fun onDestroy()")
            .substringBefore("override fun onRequestPermissionsResult(")

        assertTrue(main.contains("userReportController.onAuthorityChanged()"))
        assertTrue(destroy.contains("userReportController.onDestroy()"))
        assertTrue(destroy.contains("clearUserReportRequestUiForAuthorityFence()"))
        assertTrue(save.contains("STATE_USER_REPORT_STATUS_FILTER"))
        assertFalse(save.contains("reportId"))
        assertFalse(save.contains("requestText"))
        assertFalse(save.contains("latestCreatedRequest"))
        assertTrue(main.contains("isSaveEnabled = false"))
        assertTrue(main.contains("renderedUserReportInputReportId != selectedReportId"))
        assertTrue(main.contains("state.phase == UserReportUiPhase.SIGNED_OUT"))
        assertTrue(main.contains("userReportRequestTextInput.text?.clear()"))
        assertTrue(main.contains("renderedUserReportRequestId = null"))
        assertTrue(controller.contains("current.session === expected.session"))
        assertTrue(controller.contains("current.sessionGeneration == expected.sessionGeneration"))
        assertTrue(
            controller.contains(
                "current.localIdentityEpoch == expected.localIdentityEpoch",
            ),
        )
        assertFalse(controller.contains("val accountGeneration"))
        assertTrue(
            controller.contains(
                "authenticated Backend actor and account-generation binding",
            ),
        )
        val authority = main.substringAfter("private fun currentUserReportAuthorityOrNull()")
            .substringBefore("private fun nextUserReportStatusFilter(")
        assertTrue(authority.contains("!session.isBackendAccountDeviceBound"))
        assertTrue(authority.contains("session.backendAccountGeneration == null"))
        assertTrue(authority.contains("session.backendDevicePersistenceSnapshotOrNull() == null"))
    }

    @Test
    fun sensitiveRequestTextIsBoundToExactAuthorityAndSelectedReport() {
        val authorityFence = main.substringAfter(
            "private fun clearUserReportRequestUiForAuthorityFence()",
        ).substringBefore("private fun reconcileUserReportRequestInput(")
        val sessionChange = main.substringAfter("private fun onGatewayProcessSessionChanged(")
            .substringBefore("private fun registerGatewayCapacityNetworkObserver()")
        val deletionFence = main.substringAfter("private fun applyAccountDeletionRuntimeFence()")
            .substringBefore("private fun persistCurrentAccountDeletionJournal()")
        val binding = main.substringAfter("private fun reconcileUserReportRequestInput(")
            .substringBefore("private fun userReportSummaryText(")

        assertTrue(sessionChange.contains("clearUserReportRequestUiForAuthorityFence()"))
        assertTrue(deletionFence.contains("clearUserReportRequestUiForAuthorityFence()"))
        assertTrue(authorityFence.contains("renderedUserReportAuthority = null"))
        assertTrue(authorityFence.contains("renderedUserReportInputReportId = null"))
        assertTrue(authorityFence.contains("renderedUserReportRequestId = null"))
        assertTrue(authorityFence.contains("userReportRequestTextInput.text?.clear()"))
        assertTrue(authorityFence.contains("userReportHistoryContainer.removeAllViews()"))
        assertTrue(authorityFence.contains("요청 이력: 로그인이 필요합니다."))
        assertTrue(authorityFence.contains("userReportHistoryMoreButton.visibility = View.GONE"))
        assertTrue(authorityFence.contains("userReportHistoryMoreButton.isEnabled = false"))
        assertTrue(binding.contains("previousAuthority.session === authority.session"))
        assertTrue(binding.contains("previousAuthority.sessionGeneration == authority.sessionGeneration"))
        assertTrue(binding.contains("previousAuthority.localIdentityEpoch == authority.localIdentityEpoch"))
        assertTrue(binding.contains("state.phase == UserReportUiPhase.SIGNED_OUT"))
        assertTrue(binding.contains("renderedUserReportInputReportId != selectedReportId"))
        assertTrue(binding.contains("userReportRequestTextInput.text?.clear()"))
        assertTrue(binding.contains("renderedUserReportRequestId = null"))
        assertFalse(binding.contains("UserReportUiPhase.SUBMITTING_REQUEST"))
    }

    @Test
    fun unchangedReportsKeepStableViewsAndLoadingDoesNotClearTheirContent() {
        val listRendering = main.substringAfter("private fun renderUserReportList(")
            .substringBefore("private fun setUserReportStatusMessage(")
        val listLoading = controller.substringAfter("private fun startList(")
            .substringAfter("prepare = { before ->")
            .substringBefore("createCall =")

        assertTrue(listRendering.contains("if (renderedUserReports != reports)"))
        assertTrue(listRendering.contains("tag = report.reportId"))
        assertTrue(listRendering.contains("userReportDetailButtonsByReportId[report.reportId]"))
        assertTrue(listRendering.contains("userReportDetailButtonsByReportId[reportId]"))
        assertTrue(listRendering.contains("renderedUserReports = reports.toList()"))
        assertFalse(listLoading.contains("reports = emptyList()"))
    }

    @Test
    fun serverRequestHistoryIsPagedRefreshedAndTombstonesShowOnlyDeletionFacts() {
        val rendering = main.substringAfter("private fun renderUserReportState(")
            .substringBefore("private fun renderUserReportList(")
        val tombstone = main.substringAfter(
            "UserReportRequestHistorySource.DELETION_TOMBSTONE ->",
        ).substringBefore("private fun setUserReportStatusMessage(")

        assertTrue(client.contains("/requests/history"))
        assertTrue(client.contains("USER_REPORT_REQUEST_HISTORY_MAX_RESPONSE_BYTES = 48 * 1024"))
        assertTrue(client.contains("it.items.size <= limit"))
        assertTrue(main.contains("label = \"다음 요청 이력\""))
        assertTrue(main.contains("userReportController.loadNextRequestHistoryPage()"))
        assertTrue(rendering.contains("state.requestHistoryRefreshSequence"))
        assertTrue(rendering.contains("userReportController.loadRequestHistory()"))
        assertTrue(main.contains("renderUserReportRequestHistory(state.requestHistoryItems)"))
        assertTrue(tombstone.contains("userReportDeletionStatusText"))
        assertFalse(tombstone.contains("userReportExactRequestStatusText"))
        assertFalse(tombstone.contains("item.reportId"))
        assertFalse(tombstone.contains("item.requestId"))
        assertFalse(tombstone.contains("item.request"))
        assertFalse(tombstone.contains("publicResponse"))
        assertFalse(tombstone.contains("request.status"))
        assertTrue(controller.contains("?.statusCode == 422"))
        assertTrue(controller.contains("startRequestHistory(cursor = null"))
        assertFalse(
            controller.substringAfter("fun onAuthorityChanged()")
                .substringBefore("fun loadReports(")
                .contains("trackedRequestReferences("),
        )
    }

    @Test
    fun requestHistoryPaginationAppendsCardsAndMovesFocusToTheFirstNewItem() {
        val history = main.substringAfter("private fun renderUserReportRequestHistory(")
            .substringBefore("private fun userReportRequestHistoryText(")

        assertTrue(history.contains("items.subList(0, it.size) == it"))
        assertTrue(history.contains("items.drop(appendFrom ?: 0)"))
        assertTrue(history.contains("if (appendFrom == null) userReportHistoryContainer.removeAllViews()"))
        assertTrue(history.contains("target.requestFocus()"))
        assertTrue(history.contains("AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS"))
        assertTrue(history.contains("renderedUserReportHistoryItems == items"))
    }

    @Test
    fun directReportStatusMessagesUpdateVisibleAndSpokenTextTogether() {
        val correction = main.substringAfter("private fun confirmUserReportCorrection(")
            .substringBefore("private fun onGatewaySessionButtonClicked()")
        val statusHelper = main.substringAfter("private fun setUserReportStatusMessage(")
            .substringBefore("private fun reconcileUserReportRequestInput(")

        assertFalse(correction.contains("userReportStatusText.text ="))
        assertTrue(correction.contains("setUserReportStatusMessage("))
        assertTrue(statusHelper.contains("userReportStatusText.text = message"))
        assertTrue(statusHelper.contains("userReportStatusText.contentDescription = message"))
    }

    @Test
    fun clientHasNoDirectBackendOrAutomaticInstitutionNotificationPath() {
        assertTrue(client.contains("const val USER_REPORT_LIST_PATH = \"/api/reports/mine\""))
        assertTrue(client.contains("session.requestHeaders().forEach(::setRequestProperty)"))
        assertTrue(client.contains("useCaches = false"))
        assertTrue(client.contains("USER_REPORT_MAX_RESPONSE_BYTES"))
        assertFalse(client.contains("127.0.0.1:8000"))
        assertFalse(client.contains("localhost:8000"))
        assertFalse(client.contains("/admin/"))
        val userReportSources = client + controller
        assertFalse(userReportSources.contains("NotificationManager"))
        assertFalse(userReportSources.contains("Vibrator"))
        assertFalse(userReportSources.contains("institutionDelivery"))
        assertFalse(userReportSources.contains("candidateNotification"))
    }
}

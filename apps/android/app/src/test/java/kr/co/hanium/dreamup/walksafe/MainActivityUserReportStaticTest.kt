package kr.co.hanium.dreamup.walksafe

import java.io.File
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
    fun contentCorrectionAndPhysicalDeletionStatusAreReachableFromTheUi() {
        val views = main.substringAfter("userReportStatusText = TextView(this).apply")
            .substringBefore("accountDeletionStatusText = TextView(this).apply")
        val rendering = main.substringAfter("private fun renderUserReportState(")
            .substringBefore("private fun renderUserReportList(")

        assertTrue(views.contains("userReportController.loadContent(detail.reportId)"))
        assertTrue(views.contains("AndroidReportDeletionTrackerStore(applicationContext)"))
        assertTrue(views.contains("refreshLatestUserReportDeletionStatus"))
        assertTrue(rendering.contains("UserReportUiPhase.LOADING_CONTENT"))
        assertTrue(rendering.contains("UserReportUiPhase.LOADING_DELETION_STATUS"))
        assertTrue(rendering.contains("UserReportUiPhase.SUBMITTING_CORRECTION"))
        assertTrue(rendering.contains("state.selectedContent"))
        assertTrue(rendering.contains("state.trackedDeletionRequestIds.size"))
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

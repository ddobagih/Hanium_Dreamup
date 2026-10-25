package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserReportPresenterStaticTest {
    private val source = ReportStaticSourceInspector.read(
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/ui/UserReportPresenter.kt",
    )

    @Test
    fun presentationStateIsPrivateAndControllerAndAuthorityPolicyStayOutsideThePresenter() {
        assertTrue(source.contains("private val panel: UserReportPanel"))
        assertTrue(source.contains("private val style: WalkSafeViewStyle"))
        assertTrue(source.contains("fun render(state: UserReportUiState, authority: UserReportAuthority?)"))
        assertFalse(source.contains("MainActivity"))
        assertFalse(source.contains("UserReportController"))
        assertFalse(source.contains("currentUserReportAuthorityOrNull"))
        assertFalse(source.contains("GatewaySessionProcessCoordinator"))
        assertFalse(source.contains("submitCorrection("))
        assertFalse(source.contains("submitRequest("))
        val declarations = source.substringAfter("data class Actions(")
            .substringBefore("data class CorrectionDraft(")
        assertFalse(declarations.contains("val host:"))
        assertTrue(declarations.contains("val openDetail: (String) -> Unit"))
        assertTrue(declarations.contains("val loadRequestHistory: () -> Unit"))
        assertTrue(declarations.contains("val selectLocalQuarantine: (String) -> Unit = {}"))
        assertEquals(3, Regex("val [a-zA-Z]+:").findAll(declarations).count())
        assertEquals(setOf("openDetail", "loadRequestHistory", "selectLocalQuarantine"),
            Regex("val ([a-zA-Z]+):").findAll(declarations).map { it.groupValues[1] }.toSet())
        listOf(
            "renderedUserReportAuthority", "renderedUserReportInputReportId", "renderedUserReportRequestId",
            "renderedUserReportCorrectionId", "renderedUserReports", "renderedUserReportHistoryItems",
            "renderedUserReportHistoryRefreshSequence", "clearUserReportCorrectionDescription",
            "userReportCorrectionCategoryPatch", "userReportDetailButtonsByReportId",
        ).forEach { field ->
            assertTrue(source.contains("private var $field") || source.contains("private val $field"))
        }
    }

    @Test
    fun refreshSequenceIsCommittedBeforeTheSingleHistoryRequestAndBusyStateIncludesTheRefresh() {
        val render = ReportStaticSourceInspector.functionBlock(source, "fun render")
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                render,
                "reconcileUserReportRequestInput(state, authority)",
                "state.requestHistoryRefreshSequence >",
                "renderedUserReportHistoryRefreshSequence = state.requestHistoryRefreshSequence",
                "val busy = refreshRequestHistory ||",
                "if (refreshRequestHistory)",
                "actions.loadRequestHistory()",
            ),
        )
        assertEquals(1, Regex(Regex.escape("actions.loadRequestHistory()")).findAll(render).count())
        assertTrue(render.contains("state.phase in setOf("))
        assertTrue(render.contains("UserReportUiPhase.LOADING_MORE_REQUEST_HISTORY"))
        assertTrue(render.contains("UserReportUiPhase.SUBMITTING_CORRECTION"))
    }

    @Test
    fun draftsAndHistoryRefreshAreBoundToSessionReferenceGenerationAndIdentityEpoch() {
        val reconcile = ReportStaticSourceInspector.functionBlock(source, "private fun reconcileUserReportRequestInput")
        assertTrue(reconcile.contains("previousAuthority.session === authority.session"))
        assertTrue(reconcile.contains("previousAuthority.sessionGeneration == authority.sessionGeneration"))
        assertTrue(reconcile.contains("previousAuthority.localIdentityEpoch == authority.localIdentityEpoch"))
        assertTrue(reconcile.contains("state.phase == UserReportUiPhase.SIGNED_OUT"))
        assertTrue(reconcile.contains("renderedUserReportInputReportId != selectedReportId"))
        assertTrue(reconcile.contains("renderedUserReportHistoryRefreshSequence = 0L"))
        assertTrue(reconcile.contains("panel.userReportRequestTextInput.text?.clear()"))
        assertTrue(reconcile.contains("panel.userReportCorrectionDescriptionInput.text?.clear()"))
        assertTrue(reconcile.contains("renderedUserReportRequestId = null"))
        assertTrue(reconcile.contains("renderedUserReportCorrectionId = null"))
        assertTrue(reconcile.contains("userReportCorrectionCategoryPatch = UserReportCorrectionPatch.Omitted"))
        assertFalse(reconcile.contains("UserReportUiPhase.SUBMITTING_REQUEST"))
        val resume = ReportStaticSourceInspector.functionBlock(source, "fun revalidateAuthority")
        assertTrue(resume.contains("rendered.session !== authority.session"))
        assertTrue(resume.contains("rendered.sessionGeneration != authority.sessionGeneration"))
        assertTrue(resume.contains("rendered.localIdentityEpoch != authority.localIdentityEpoch"))
        assertTrue(resume.contains("clearForAuthorityFence()"))
    }

    @Test
    fun authorityFenceResetsDraftStateImmediatelyAndClearsViewsOnTheMainLooper() {
        val clear = ReportStaticSourceInspector.functionBlock(source, "fun clearForAuthorityFence")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(clear,
            "renderedUserReportAuthority = null", "renderedUserReportRequestId = null",
            "renderedUserReportHistoryRefreshSequence = 0L", "clearUserReportCorrectionDescription = false",
            "userReportCorrectionCategoryPatch = UserReportCorrectionPatch.Omitted", "val clear = Runnable",
            "authorityFenceTarget.clear() else clearAuthorityViews()", "dispatcher.isMainThread()",
            "clear.run() else dispatcher.post(clear)",
        ))
        val views = ReportStaticSourceInspector.functionBlock(source, "private fun clearAuthorityViews")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(views,
            "panel.userReportRequestTextInput.text?.clear()",
            "panel.userReportHistoryContainer.removeAllViews()",
            "요청 이력: 로그인이 필요합니다.",
            "panel.userReportHistoryMoreButton.visibility = View.GONE",
            "panel.userReportHistoryMoreButton.isEnabled = false",
        ))
        assertTrue(source.contains("Looper.myLooper() == Looper.getMainLooper()"))

    }

    @Test
    fun equalReportListsKeepTheirViewsAndRebuiltListsRestoreReportFocus() {
        val list = ReportStaticSourceInspector.functionBlock(source, "private fun renderUserReportList")
        assertTrue(list.contains("if (renderedUserReports != reports)"))
        assertTrue(list.contains("panel.userReportListContainer.findFocus()?.tag as? String"))
        assertTrue(list.contains("tag = report.reportId"))
        assertTrue(list.contains("actions.openDetail(report.reportId)"))
        assertTrue(list.contains("style.applyWsButtonStyle(detailButton, WS_TOUCH_WALK_ACTION_DP)"))
        assertTrue(list.contains("renderedUserReports = reports.toList()"))
        assertTrue(list.contains("userReportDetailButtonsByReportId[reportId]"))
        assertTrue(list.contains("target.post { target.requestFocus() }"))
        assertTrue(list.contains("userReportDetailButtonsByReportId.values.forEach { it.isEnabled = !busy }"))
    }

    @Test
    fun appendedHistoryKeepsExistingCardsAndFocusesOnlyAnAttachedCurrentFirstAppend() {
        val history = ReportStaticSourceInspector.functionBlock(source, "private fun renderUserReportRequestHistory")
        assertTrue(history.contains("if (previous == items) return"))
        assertTrue(history.contains("items.subList(0, it.size) == it"))
        assertTrue(history.contains("items.drop(appendFrom ?: 0)"))
        assertTrue(history.contains("if (appendFrom == null) panel.userReportHistoryContainer.removeAllViews()"))
        assertTrue(history.contains("if (firstAppendedText == null) firstAppendedText = historyText"))
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                history,
                "renderedUserReportHistoryItems = items.toList()",
                "if (appendFrom != null)",
                "target.isAttachedToWindow",
                "renderedUserReportHistoryItems == items",
                "target.requestFocus()",
                "AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS",
            ),
        )
    }

    @Test
    fun correctionTogglesAndDraftReadsPreserveTheClearOmittedAndCategoryCycle() {
        val toggle = ReportStaticSourceInspector.functionBlock(source, "fun toggleDescriptionClear")
        assertTrue(toggle.contains("clearUserReportCorrectionDescription = !clearUserReportCorrectionDescription"))
        assertTrue(toggle.contains("panel.userReportCorrectionDescriptionInput.text?.clear()"))
        assertTrue(toggle.contains("updateUserReportCorrectionPatchButtons()"))
        val cycle = ReportStaticSourceInspector.functionBlock(source, "private fun nextUserReportCorrectionCategoryPatch")
        assertTrue(cycle.contains("UserReportContentCategory.SIDEWALK_OBSTRUCTION"))
        assertTrue(cycle.contains("UserReportContentCategory.entries"))
        assertTrue(cycle.contains("UserReportCorrectionPatch.Clear -> UserReportCorrectionPatch.Omitted"))
        assertTrue(cycle.contains("UserReportCorrectionPatch.Value(categories[nextIndex])"))
        assertTrue(source.contains("rawDescription = panel.userReportCorrectionDescriptionInput.text?.toString().orEmpty()"))
        assertTrue(source.contains("clearDescription = clearUserReportCorrectionDescription"))
        assertTrue(source.contains("categoryHint = userReportCorrectionCategoryPatch"))
        assertTrue(source.contains("fun requestText(): String = panel.userReportRequestTextInput.text?.toString()?.trim().orEmpty()"))
    }
}

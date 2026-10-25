package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityUserReportStaticTest {
    private val main =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val presenter =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/UserReportPresenter.kt").readText()
    private val reportPanel =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/UserReportPanel.kt").readText()
    private val settingsPanel =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/SettingsPrivacyPanel.kt").readText()
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
        val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/report/interaction/UserReportInteractionOwner.kt").readText()
        val wiring = MainActivityRefactorSource.member(main, "createUserReportInteractionOwner")
        assertTrue(MainActivityRefactorSource.member(main, "revalidateUserReportAuthorityOnResume").contains("userReportInteractionOwner.revalidateUserReportAuthorityOnResume()"))
        assertTrue(MainActivityRefactorSource.member(main, "scheduleUserReportAuthorityExpiryIfNeeded").contains("userReportInteractionOwner.scheduleUserReportAuthorityExpiryIfNeeded(authority)"))

        val resume = main.substringAfter("override fun onResume()")
            .substringBefore("override fun onWindowFocusChanged(")
        val revalidate = MainActivityRefactorSource.member(owner, "revalidateUserReportAuthorityOnResume")
        val schedule = MainActivityRefactorSource.member(owner, "scheduleUserReportAuthorityExpiryIfNeeded")
        val pause = main.substringAfter("override fun onPause()")
            .substringBefore("internal fun pauseWalkSafeRuntime()")
        val destroy = main.substringAfter("override fun onDestroy()")
            .substringBefore("override fun onRequestPermissionsResult(")

        assertTrue(resume.contains("revalidateUserReportAuthorityOnResume()"))
        assertTrue(revalidate.contains("currentUserReportAuthorityOrNull()"))
        assertTrue(revalidate.contains("presentation.revalidateAuthority(authority)"))
        assertTrue(wiring.contains("userReportPresenter.revalidateAuthority(authority)"))
        assertTrue(wiring.contains("userReportController.onAuthorityChanged()"))
        assertTrue(wiring.contains("wallClockMs = System::currentTimeMillis"))
        val presenterRevalidate = ReportStaticSourceInspector.functionBlock(presenter, "fun revalidateAuthority")
        assertTrue(presenterRevalidate.contains("clearForAuthorityFence()"))
        assertTrue(revalidate.contains("reports.onAuthorityChanged()"))
        assertTrue(revalidate.contains("scheduleUserReportAuthorityExpiryIfNeeded(authority)"))
        assertTrue(schedule.contains("session.accessExpiresAtEpochMs"))
        assertTrue(schedule.contains("session.idleExpiresAtEpochMs"))
        assertTrue(schedule.contains("session.absoluteExpiresAtEpochMs"))
        assertTrue(schedule.contains("current.matchesUserReportAuthority(authority)"))
        assertTrue(schedule.contains("wallClockMs() < expiresAtEpochMs"))
        assertTrue(schedule.contains("clearUserReportRequestUiForAuthorityFence()"))
        assertTrue(schedule.contains("reports.onAuthorityChanged()"))
        assertTrue(pause.contains("cancelUserReportAuthorityExpirySchedule()"))
        assertTrue(destroy.contains("cancelUserReportAuthorityExpirySchedule()"))
    }

    @Test
    fun reportRightsSurfaceUsesTextStatusReflowLiveRegionAndStableFocus() {
        val views = reportPanel
        val rendering = presenter

        listOf(
            "접수됨",
            "기각됨",
            "기관 제출 기록",
            "처리 완료",
            "관리자가 수동 기록한 전달 상태",
            "공개 기각 사유",
            "최신 ",
            "공개 답변",
            "요청 상태 갱신 시각",
        ).forEach { assertTrue(presenter.contains(it) || models.contains(it)) }
        assertTrue(views.contains("accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE"))
        assertTrue(views.contains("contentDescription"))
        assertTrue(views.contains("minimumHeight = style.accessibilityTargetSizePx()"))
        assertTrue(rendering.contains("minimumWidth = style.accessibilityTargetSizePx()"))
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
        val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/report/interaction/UserReportInteractionOwner.kt").readText()
        val wiring = MainActivityRefactorSource.member(main, "createUserReportInteractionOwner")
        assertTrue(MainActivityRefactorSource.member(main, "confirmUserReportCorrection").contains("userReportInteractionOwner.confirmUserReportCorrection("))
        assertTrue(MainActivityRefactorSource.member(main, "confirmUserReportDeleteRequest").contains("userReportInteractionOwner.confirmUserReportDeleteRequest("))

        val correction = MainActivityRefactorSource.member(owner, "confirmUserReportCorrection")
        val deletion = MainActivityRefactorSource.member(owner, "confirmUserReportDeleteRequest")

        assertTrue(correction.contains("신고 내용 정정 확인"))
        assertTrue(correction.contains("submitCorrection("))
        assertTrue(correction.contains("UserReportCorrectionPatch.Value"))
        assertTrue(deletion.contains("신고 한 건 삭제 요청 확인"))
        assertTrue(deletion.contains("계정과 개인정보 전체 삭제 요청이 아닙니다."))
        assertTrue(deletion.contains("UserReportRequestType.DELETE"))
        assertTrue(deletion.contains("validUserReportRequestText(requestText)"))
        assertFalse(deletion.contains("requestText.length > 500"))
        assertTrue(settingsPanel.contains("계정과 개인정보 삭제 요청 확인"))
    }

    @Test
    fun contentCorrectionRequestStatusAndPhysicalDeletionStatusAreReachableFromTheUi() {
        val views = reportPanel
        val content = MainActivityRefactorSource.member(main, "buildContentView")
        val start = content.indexOf("val reportPanel = UserReportPanel(")
        val end = content.indexOf("nativeUiPublication.publish(NativeUiPublication.Target.ACCOUNT_DELETION_STATUS_TEXT)", start)
        assertTrue("Missing report panel/controller setup", start >= 0 && end > start)
        val setup = content.substring(start, end)
        val rendering = ReportStaticSourceInspector.functionBlock(presenter, "fun render")

        assertTrue(setup.contains("userReportController.loadContent(detail.reportId)"))
        assertTrue(setup.contains("AndroidReportDeletionTrackerStore(applicationContext)"))
        assertTrue(setup.contains("onRequestStatus = ::refreshLatestUserReportRequestStatus"))
        assertTrue(setup.contains("onDeletionStatus = ::refreshLatestUserReportDeletionStatus"))
        assertTrue(rendering.contains("UserReportUiPhase.LOADING_CONTENT"))
        assertTrue(rendering.contains("UserReportUiPhase.LOADING_REQUEST_STATUS"))
        assertTrue(rendering.contains("UserReportUiPhase.LOADING_DELETION_STATUS"))
        assertTrue(rendering.contains("UserReportUiPhase.SUBMITTING_CORRECTION"))
        assertTrue(rendering.contains("state.selectedContent"))
        assertTrue(presenter.contains("state.selectedRequestStatus"))
        assertTrue(rendering.contains("state.trackedDeletionRequestIds.size"))
        assertTrue(views.contains("선택 신고 요청 처리 상태 확인"))
        assertTrue(views.contains("신고 물리 삭제·기관 보관본 상태 확인"))
        assertTrue(presenter.contains("앱 서버 원본 삭제 상태:"))
        assertTrue(presenter.contains("기관 보관본 수:"))
        assertTrue(presenter.contains("state.selectedDeletionStatus?.let"))
        assertTrue(models.contains("기관에 삭제 요청 전달됨 · 삭제 완료 아님"))
        assertTrue(models.contains("기관이 삭제 요청 접수를 회신함 · 삭제 완료 아님"))
        assertTrue(models.contains("기관이 삭제 완료를 회신함 · 기관 회신 사실"))
        assertTrue(presenter.contains("요청은 접수됐지만 이 기기에 상태 추적 정보를 저장하지 못했습니다."))
        assertFalse(main.contains("삭제 처리 상태:"))
        assertFalse(presenter.contains("삭제 처리 상태:"))
    }

    @Test
    fun reportPanelActionsAndBindingsConnectToTheExistingControllerInOrder() {
        val content = MainActivityRefactorSource.member(main, "buildContentView")
        val start = content.indexOf("val reportPanel = UserReportPanel(")
        val end = content.indexOf("nativeUiPublication.publish(NativeUiPublication.Target.ACCOUNT_DELETION_STATUS_TEXT)", start)
        assertTrue("Missing report panel/controller setup", start >= 0 && end > start)
        val setup = content.substring(start, end)
        val controls = ReportStaticSourceInspector.blockAfter(
            reportPanel,
            "val userReportControls: LinearLayout = LinearLayout(context).apply",
        )
        val children = listOf(
            "userReportStatusText", "userReportFilterButton", "userReportRefreshButton",
            "userReportRetryButton", "userReportListContainer", "userReportMoreButton",
            "userReportHistoryStatusText", "userReportHistoryContainer", "userReportHistoryMoreButton",
            "userReportDetailText", "userReportContentButton", "userReportCorrectionDescriptionInput",
            "userReportCorrectionDescriptionClearButton", "userReportCorrectionCategoryButton",
            "userReportCorrectionButton", "userReportRequestTextInput", "userReportDeleteButton",
            "userReportRequestStatusButton", "userReportDeletionStatusButton",
        )
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                controls,
                *children.map { "addView($it)" }.toTypedArray(),
            ),
        )
        (children + "userReportControls").forEach { field ->
            assertTrue(reportPanel.contains("val $field:"))
            assertTrue(presenter.contains("panel.$field"))
        }
        mapOf(
            "userReportFilterButton" to "onFilter",
            "userReportRefreshButton" to "onRefresh",
            "userReportRetryButton" to "onRetry",
            "userReportMoreButton" to "onMore",
            "userReportHistoryMoreButton" to "onHistoryMore",
            "userReportContentButton" to "onContent",
            "userReportCorrectionDescriptionClearButton" to "onToggleDescriptionClear",
            "userReportCorrectionCategoryButton" to "onNextCategory",
            "userReportCorrectionButton" to "onCorrection",
            "userReportDeleteButton" to "onDelete",
            "userReportRequestStatusButton" to "onRequestStatus",
            "userReportDeletionStatusButton" to "onDeletionStatus",
        ).forEach { (field, action) ->
            val button = reportPanel.substringAfter("val $field: Button =")
                .substringBefore("\n    val ")
            assertTrue(button.contains("onClick = actions.$action"))
        }
        mapOf(
            "onFilter" to "userReportController.loadReports(next)",
            "onRefresh" to "userReportController.loadReports()",
            "onRetry" to "userReportController.retry()",
            "onMore" to "userReportController.loadNextPage()",
            "onHistoryMore" to "userReportController.loadNextRequestHistoryPage()",
            "onContent" to "userReportController.loadContent(detail.reportId)",
            "onToggleDescriptionClear" to "userReportPresenter.toggleDescriptionClear()",
            "onNextCategory" to "userReportPresenter.nextCategory()",
        ).forEach { (action, call) ->
            assertTrue(ReportStaticSourceInspector.blockAfter(setup, "$action =").contains(call))
        }
        assertTrue(setup.contains("onCorrection = ::confirmUserReportCorrection"))
        assertTrue(setup.contains("onDelete = ::confirmUserReportDeleteRequest"))
        assertTrue(setup.contains("authorityProvider = ::currentUserReportAuthorityOrNull"))
        assertTrue(setup.contains("observer = ::renderUserReportState"))
        assertTrue(setup.contains("networkAllowedProvider = ::isGatewayFeatureNetworkAllowed"))
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                setup,
                "actions = UserReportPanel.Actions(",
                "userReportPresenter = UserReportPresenter(",
                "panel = reportPanel",
                "userReportController = UserReportController(",
                "userReportController.onAuthorityChanged()",
                "if (currentUserReportAuthorityOrNull() != null)",
                "userReportController.loadReports(restoredUserReportStatusFilter)",
                "renderUserReportState(userReportController.snapshot())",
            ),
        )
    }

    @Test
    fun lifecycleFencesAndRotationRetainOnlyTheNonSensitiveFilter() {
        val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/report/interaction/UserReportInteractionOwner.kt").readText()
        val wiring = MainActivityRefactorSource.member(main, "createUserReportInteractionOwner")
        assertTrue(MainActivityRefactorSource.member(main, "currentUserReportAuthorityOrNull").contains("userReportInteractionOwner.currentUserReportAuthorityOrNull("))

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
        listOf("userReportRequestTextInput", "userReportCorrectionDescriptionInput").forEach { field ->
            val input = ReportStaticSourceInspector.blockAfter(
                reportPanel,
                "val $field: EditText = EditText(context).apply",
            )
            assertTrue(input.contains("isSaveEnabled = false"))
        }
        assertTrue(presenter.contains("renderedUserReportInputReportId != selectedReportId"))
        assertTrue(presenter.contains("state.phase == UserReportUiPhase.SIGNED_OUT"))
        assertTrue(presenter.contains("panel.userReportRequestTextInput.text?.clear()"))
        assertTrue(presenter.contains("renderedUserReportRequestId = null"))
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
        val authority = MainActivityRefactorSource.member(owner, "currentUserReportAuthorityOrNull")
        assertTrue(authority.contains("!session.isBackendAccountDeviceBound"))
        assertTrue(authority.contains("session.backendAccountGeneration == null"))
        assertTrue(authority.contains("session.backendDevicePersistenceSnapshotOrNull() == null"))
    }

    @Test
    fun sensitiveRequestTextIsBoundToExactAuthorityAndSelectedReport() {
        val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/report/interaction/UserReportInteractionOwner.kt").readText()
        val wiring = MainActivityRefactorSource.member(main, "createUserReportInteractionOwner")
        assertTrue(MainActivityRefactorSource.member(main, "clearUserReportRequestUiForAuthorityFence").contains("userReportInteractionOwner.clearUserReportRequestUiForAuthorityFence()"))

        val authorityFence = ReportStaticSourceInspector.functionBlock(presenter, "fun clearForAuthorityFence")
        val activityFence = ReportStaticSourceInspector.functionBlock(
            owner,
            "fun clearUserReportRequestUiForAuthorityFence",
        )
        assertTrue(activityFence.contains("presentation.clearForAuthorityFence()"))
        assertTrue(wiring.contains("userReportPresenter.clearForAuthorityFence()"))
        val sessionChange = delegatedOwner("onGatewayProcessSessionChanged", "actorSessionLifecycleCoordinator", "session/gateway/ActorSessionLifecycleCoordinator.kt", "onGatewayProcessSessionChanged", "{ actorSessionLifecycleCoordinator.onGatewayProcessSessionChanged(snapshot, consentAuthorityChanged) refreshGuidanceDiagnosticAuthority() }")
        val gatewayWiring = MainActivityRefactorSource.composition(main, "actorSessionLifecycleCoordinator")
        assertTrue(gatewayWiring.contains("clearUserReportRequestUiForAuthorityFence()"))
        val deletionFence = MainActivityRefactorSource.composition(main, "accountDeletionRuntimeFenceController")
        val deletionOwner = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/deletion/runtime/AccountDeletionRuntimeFenceController.kt").readText()
        assertTrue(MainActivityRefactorSource.member(deletionOwner, "apply").contains("reports.clearUserRequests()"))
        val binding = ReportStaticSourceInspector.functionBlock(presenter, "private fun reconcileUserReportRequestInput")

        assertTrue(sessionChange.contains("reports.clearAuthorityUiFence()"))
        assertTrue(deletionFence.contains("reports = ports.reports"))
        assertTrue(MainActivityRefactorSource.factory(main, "createPrivacyRuntimeFence").contains("clearUserReportRequestUiForAuthorityFence()"))
        assertTrue(authorityFence.contains("renderedUserReportAuthority = null"))
        assertTrue(authorityFence.contains("renderedUserReportInputReportId = null"))
        assertTrue(authorityFence.contains("renderedUserReportRequestId = null"))
        assertTrue(authorityFence.contains("if (authorityFenceTarget != null) authorityFenceTarget.clear() else clearAuthorityViews()"))
        val clearViews = ReportStaticSourceInspector.functionBlock(presenter, "private fun clearAuthorityViews")
        assertTrue(clearViews.contains("panel.userReportRequestTextInput.text?.clear()"))
        assertTrue(clearViews.contains("panel.userReportHistoryContainer.removeAllViews()"))
        assertTrue(clearViews.contains("요청 이력: 로그인이 필요합니다."))
        assertTrue(clearViews.contains("panel.userReportHistoryMoreButton.visibility = View.GONE"))
        assertTrue(clearViews.contains("panel.userReportHistoryMoreButton.isEnabled = false"))
        assertTrue(binding.contains("previousAuthority.session === authority.session"))
        assertTrue(binding.contains("previousAuthority.sessionGeneration == authority.sessionGeneration"))
        assertTrue(binding.contains("previousAuthority.localIdentityEpoch == authority.localIdentityEpoch"))
        assertTrue(binding.contains("state.phase == UserReportUiPhase.SIGNED_OUT"))
        assertTrue(binding.contains("renderedUserReportInputReportId != selectedReportId"))
        assertTrue(binding.contains("panel.userReportRequestTextInput.text?.clear()"))
        assertTrue(binding.contains("renderedUserReportRequestId = null"))
        assertFalse(binding.contains("UserReportUiPhase.SUBMITTING_REQUEST"))
    }

    @Test
    fun unchangedReportsKeepStableViewsAndLoadingDoesNotClearTheirContent() {
        val listRendering = ReportStaticSourceInspector.functionBlock(presenter, "private fun renderUserReportList")
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
        val rendering = ReportStaticSourceInspector.functionBlock(presenter, "fun render")
        val historyText = presenter.substringAfter("private fun userReportRequestHistoryText(")
            .substringBefore("fun setStatusMessage(")
        val tombstone = ReportStaticSourceInspector.blockAfter(
            historyText,
            "UserReportRequestHistorySource.DELETION_TOMBSTONE -> buildString",
        )

        assertTrue(client.contains("/requests/history"))
        assertTrue(client.contains("USER_REPORT_REQUEST_HISTORY_MAX_RESPONSE_BYTES = 48 * 1024"))
        assertTrue(client.contains("it.items.size <= limit"))
        assertTrue(reportPanel.contains("label = \"다음 요청 이력\""))
        val setup = MainActivityRefactorSource.member(main, "buildContentView")
        assertTrue(setup.contains("val reportPanel = UserReportPanel("))
        val historyMore = ReportStaticSourceInspector.blockAfter(setup, "onHistoryMore =")
        assertTrue(historyMore.contains("userReportController.loadNextRequestHistoryPage()"))
        assertTrue(rendering.contains("state.requestHistoryRefreshSequence"))
        assertTrue(rendering.contains("actions.loadRequestHistory()"))
        assertTrue(presenter.contains("renderUserReportRequestHistory(state.requestHistoryItems)"))
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
        val history = ReportStaticSourceInspector.functionBlock(presenter, "private fun renderUserReportRequestHistory")

        assertTrue(history.contains("items.subList(0, it.size) == it"))
        assertTrue(history.contains("items.drop(appendFrom ?: 0)"))
        assertTrue(history.contains("if (appendFrom == null) panel.userReportHistoryContainer.removeAllViews()"))
        assertTrue(history.contains("target.requestFocus()"))
        assertTrue(history.contains("AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS"))
        assertTrue(history.contains("renderedUserReportHistoryItems == items"))
    }

    @Test
    fun directReportStatusMessagesUpdateVisibleAndSpokenTextTogether() {
        val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/report/interaction/UserReportInteractionOwner.kt").readText()
        val wiring = MainActivityRefactorSource.member(main, "createUserReportInteractionOwner")
        assertTrue(MainActivityRefactorSource.member(main, "confirmUserReportCorrection").contains("userReportInteractionOwner.confirmUserReportCorrection("))

        val correction = MainActivityRefactorSource.member(owner, "confirmUserReportCorrection")
        val activityStatus = MainActivityRefactorSource.member(owner, "setUserReportStatusMessage")
        val statusHelper = ReportStaticSourceInspector.functionBlock(presenter, "fun setStatusMessage")
        assertTrue(activityStatus.contains("presentation.setStatusMessage(message)"))
        assertTrue(wiring.contains("userReportPresenter.setStatusMessage(message)"))

        assertFalse(correction.contains("userReportStatusText.text ="))
        assertTrue(correction.contains("setUserReportStatusMessage("))
        assertTrue(statusHelper.contains("panel.userReportStatusText.text = message"))
        assertTrue(statusHelper.contains("panel.userReportStatusText.contentDescription = message"))
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
        listOf(client, controller).forEach { source ->
            assertFalse(source.contains("NotificationManager"))
            assertFalse(source.contains("Vibrator"))
            assertFalse(source.contains("institutionDelivery"))
            assertFalse(source.contains("candidateNotification"))
        }
    }

    @Test
    fun presenterInitializationRenderingAndDraftReadsUseTheActualActivityWiring() {
        val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/report/interaction/UserReportInteractionOwner.kt").readText()
        val wiring = MainActivityRefactorSource.member(main, "createUserReportInteractionOwner")
        assertTrue(MainActivityRefactorSource.member(main, "renderUserReportState").contains("userReportInteractionOwner.renderUserReportState("))
        assertTrue(MainActivityRefactorSource.member(main, "confirmUserReportCorrection").contains("userReportInteractionOwner.confirmUserReportCorrection("))
        assertTrue(MainActivityRefactorSource.member(main, "confirmUserReportDeleteRequest").contains("userReportInteractionOwner.confirmUserReportDeleteRequest("))

        val content = MainActivityRefactorSource.member(main, "buildContentView")
        val start = content.indexOf("val reportPanel = UserReportPanel(")
        val end = content.indexOf("nativeUiPublication.publish(NativeUiPublication.Target.ACCOUNT_DELETION_STATUS_TEXT)", start)
        assertTrue("Missing report panel/controller setup", start >= 0 && end > start)
        val setup = content.substring(start, end)
        assertTrue(setup.contains("panel = reportPanel"))
        assertTrue(setup.contains("openDetail = { reportId -> userReportController.openDetail(reportId) }"))
        assertTrue(setup.contains("loadRequestHistory = { userReportController.loadRequestHistory() }"))
        val rendering = MainActivityRefactorSource.member(owner, "renderUserReportState")
        assertTrue(rendering.contains("activity.destroyed()"))
        assertTrue(rendering.contains("!presentation.initialized()"))
        assertTrue(rendering.contains("presentation.render(state, currentUserReportAuthorityOrNull())"))
        assertFalse(rendering.contains("renderUserReportList("))
        assertTrue(wiring.contains("override fun destroyed() = privacyStartupInspectionDestroyed"))
        assertTrue(wiring.contains("this@MainActivity::userReportPresenter.isInitialized"))
        assertTrue(wiring.contains("userReportPresenter.render(state, authority)"))
        assertTrue(wiring.contains("val draft = userReportPresenter.correctionDraft()"))
        assertTrue(wiring.contains("userReportPresenter.requestText()"))
        assertTrue(wiring.contains(".setPositiveButton(positiveLabel) { _, _ -> onConfirmed() }"))
        val correction = MainActivityRefactorSource.member(owner, "confirmUserReportCorrection")
        assertTrue(correction.contains("val draft = presentation.correctionDraft()"))
        assertTrue(correction.contains("draft.clearDescription"))
        assertTrue(correction.contains("draft.categoryHint == UserReportCorrectionPatch.Omitted"))
        val submit = ReportStaticSourceInspector.blockAfter(correction, "dialogs.confirm(")
        assertTrue(submit.contains("categoryHint = presentation.correctionDraft().categoryHint"))
        assertFalse(submit.contains("categoryHint = draft.categoryHint"))
        val deletion = MainActivityRefactorSource.member(owner, "confirmUserReportDeleteRequest")
        assertTrue(deletion.contains("val requestText = presentation.requestText()"))
        listOf(
            "renderedUserReportAuthority", "renderedUserReportInputReportId", "renderedUserReportRequestId",
            "renderedUserReportCorrectionId", "renderedUserReports", "renderedUserReportHistoryItems",
            "renderedUserReportHistoryRefreshSequence", "clearUserReportCorrectionDescription",
            "userReportCorrectionCategoryPatch", "userReportDetailButtonsByReportId",
        ).forEach { field ->
            assertFalse(main.contains("private var $field"))
            assertFalse(main.contains("private val $field"))
        }
    }

    private fun reportSetup(): String {
        val start = main.indexOf("val reportPanel = UserReportPanel(")
        val end = main.indexOf("accountDeletionStatusText = settingsPanel.accountDeletionStatusText", start)
        assertTrue("Missing report panel/controller setup", start >= 0 && end > start)
        return main.substring(start, end)
    }

    // Structural wiring and owner policy are checked separately; this does not execute Main.
    private fun delegatedOwner(name: String, owner: String, path: String, target: String, expectedBody: String): String {
        val caller = MainActivityRefactorSource.factory(main, name)
        // Match the whole caller, including additional effects; a guarded call or lambda is different.
        val declaration = caller.filterNot(Char::isWhitespace)
        val body = Regex.escape(expectedBody.filterNot(Char::isWhitespace))
        val expected = Regex("""^(?:private|internal|public|override)*fun$name\([^{}]*\)(?::[\w?<>]+)?$body$""")
        assertTrue("Main caller changed for $owner.$target: $caller", expected.matches(declaration))
        assertTrue(expectedBody.contains("$owner.$target("))
        MainActivityRefactorSource.composition(main, owner)
        return MainActivityRefactorSource.member(
            File("src/main/java/kr/co/hanium/dreamup/walksafe/$path").readText(), target,
        )
    }
}

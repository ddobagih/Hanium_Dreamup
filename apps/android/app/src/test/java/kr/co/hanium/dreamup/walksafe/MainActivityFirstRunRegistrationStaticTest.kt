package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityFirstRunRegistrationStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun progressReflectsTheActiveFlowWhileLegacyMappingKeepsTwelveStages() {
        assertTrue(source.contains("const val FIRST_RUN_STAGE_COUNT = 12"))
        assertTrue(source.contains("const val EMAIL_FIRST_RUN_STAGE_COUNT = 6"))
        val progress = sourceSection(
            "firstRunProgressBar = LinearLayout(this).apply",
            "firstRunOnboardingStatusText = TextView(this).apply",
        )
        assertTrue(
            progress.contains(
                "repeat(firstRunStageCount(firstRunOnboardingSnapshot))",
            ),
        )

        val mapping = functionBlock("private fun legacyFirstRunStageNumber(")
        assertInOrder(
            mapping,
            "FirstRunOnboardingStage.PURPOSE_AND_SAFETY -> 1",
            "FirstRunOnboardingStage.INTEGRATED_CONSENT -> 3",
            "FirstRunOnboardingStage.LOCAL_CREDENTIAL_PHONE_SUBMISSION -> 4",
            "FirstRunOnboardingStage.FP004_TRAINING -> 11",
            "FirstRunOnboardingStage.COMPLETE -> 12",
            "FirstRunOnboardingStage.BLOCKED_UNDER_14 -> 2",
        )

        val update = functionBlock("private fun updateFirstRunOnboardingUi()")
        // 그릴 화면은 renderStage 가 정하고, 진행 번호는 언제나 실제 snapshot 을 따른다.
        assertTrue(update.contains("val renderStage = firstRunPreviewStage ?: snapshot.stage"))
        assertTrue(update.contains("val stageNumber = firstRunStageNumber(snapshot)"))
        assertTrue(update.contains("val stageCount = firstRunStageCount(snapshot)"))
        assertTrue(update.contains("if (index < stageNumber)"))
    }

    @Test
    fun freshProcessAndLegacyReporterIdentityFailClosedUntilFirstRunCompletes() {
        val create = functionBlock("override fun onCreate(savedInstanceState: Bundle?)")
        assertInOrder(
            create,
            "firstRunOnboardingSnapshot = FirstRunOnboardingPolicy.initialEmailAccount(",
            "restorePriorityUserOnboardingFromPrefs()",
            "scheduleGatewaySessionRestoreAfterPrivacyStartupInspection()",
        )

        val manualReporter = functionBlock("private fun persistReporterUserFromInput()")
        assertTrue(manualReporter.contains("login=blocked manual_reporter_id_disallowed"))
        assertFalse(manualReporter.contains("reporterUserId ="))

        val legacyRestore =
            functionBlock("private fun restorePermissionSessionStateFromPrefs()")
        assertInOrder(
            legacyRestore,
            "reporterUserId = null",
            "remove(PREF_REPORTER_USER_ID_KEY).commit()",
        )
        assertFalse(legacyRestore.contains("getString(PREF_REPORTER_USER_ID_KEY"))

        val reporter = functionBlock("private fun currentReporterUserId()")
        assertInOrder(
            reporter,
            "if (!firstRunOnboardingComplete()) return null",
            "firstRunOnboardingSnapshot.reporterActorBinding?.value ?: return null",
            "return reporterUserId.takeIf",
        )
        val processSession = functionBlock("private fun onGatewayProcessSessionChanged(")
        assertInOrder(
            processSession,
            "val firstRun = snapshot.restoredFirstRunSnapshot",
            "val reporterActorId = firstRun?.reporterActorBinding?.value",
            "val verifiedEmailActorId = firstRun?.takeIf",
            "val actorId = reporterActorId ?: verifiedEmailActorId",
            "session != null",
            "firstRun != null",
            "actorId != null",
            "actorId == session.actorId",
            "firstRunOnboardingSnapshot = firstRun",
            "reporterUserId = actorId",
            "firstRun.isComplete",
            "} else {\n            permissionSessionPolicy.authenticationExpired()",
        )
        assertTrue(
            functionBlock("private fun onAccountLogoutClicked()")
                .contains("firstRunOnboardingSnapshot = FirstRunOnboardingPolicy.initialEmailAccount("),
        )
    }

    @Test
    fun startupProbesOnlyStartBehindTheDeviceCheckGate() {
        val create = functionBlock("override fun onCreate(savedInstanceState: Bundle?)")
        assertInOrder(
            create,
            "startupCapabilityProbe = AndroidStartupCapabilityProbe(this)",
            "maybeStartFirstRunDeviceCheckProbes()",
        )
        assertFalse(create.contains("walkSessionResourceProbe.start"))
        assertFalse(create.contains("startupCapabilityProbe.start()"))

        val start = functionBlock("private fun maybeStartFirstRunDeviceCheckProbes()")
        assertInOrder(
            start,
            "if (!firstRunDeviceCheckAllowsPreflight()) return",
            "walkSessionResourceProbe.start {",
            "observeWalkRuntimeResourceSafety()",
            "refreshStartupCapabilityUi()",
            "startupCapabilityProbe.start()",
        )
        val gate = functionBlock("private fun firstRunDeviceCheckAllowsPreflight()")
        assertTrue(gate.contains("PostLoginDeviceCheckState.RUNNING"))
        assertTrue(gate.contains("PostLoginDeviceCheckPolicy.isCurrent"))
    }

    @Test
    fun trainingResetRestartsCompletedFirstRunAtFp004AndPublishesTheStateChange() {
        assertTrue(
            source.contains(
                "@Volatile\n    private lateinit var firstRunOnboardingSnapshot:",
            ),
        )
        val reset = functionBlock("private fun resetPriorityUserTraining()")
        assertInOrder(
            reset,
            "priorityUserOnboardingPolicy.resetTraining()",
            "FirstRunOnboardingPolicy.restartFp004Training(",
            "firstRunOnboardingSnapshot",
            "if (firstRunRestart.accepted)",
            "firstRunOnboardingSnapshot = firstRunRestart.current",
            "onFirstRunOnboardingStateChanged(",
        )

        val stateChange =
            functionBlock("private fun onFirstRunOnboardingStateChanged(")
        assertInOrder(
            stateChange,
            "if (!firstRunOnboardingComplete())",
            "clearGatewaySession(logoutRemote = true)",
            "if (rawWalkWasActive)",
            "bindFirstRunVerifiedActorForTraining()",
        )
    }

    @Test
    fun gatewayAuthenticationCommitAndDowngradeUseExactCoordinatorOperations() {
        val login = functionBlock("private fun onGatewaySessionButtonClicked()")
        assertInOrder(
            login,
            "val operation = GatewaySessionProcessCoordinator.beginOperation() ?: return",
            "commitLoggedInGatewaySession(",
        )
        val commit = functionBlock("private fun commitLoggedInGatewaySession(")
        assertInOrder(
            commit,
            "GatewaySessionProcessCoordinator.snapshot().inFlightOperationId",
            "operation.operationId",
            "priorityUserOnboardingActorId != session.actorId",
            "gatewaySessionStore.saveInitialIfAbsent(",
            "GatewaySessionProcessCoordinator.publishVerified(",
        )

        val clear = functionBlock("private fun clearGatewaySession(")
        assertInOrder(
            clear,
            "val operation = GatewaySessionProcessCoordinator.beginOperation() ?: return null",
            "gatewaySessionStore.moveActiveToPendingRevocation(",
            "GatewaySessionProcessCoordinator.publishPendingRevocation(operation)",
            "permissionSessionPolicy.authenticationExpired()",
        )
    }

    @Test
    fun firstRunReadinessIsTheEarliestGateAndDetectorLoadingFailsClosed() {
        val readiness = functionBlock("private fun captureWalkSessionReadiness(")
        assertInOrder(
            readiness,
            "firstRunOnboardingComplete() &&",
            "loadDetectorAfterCameraGate()",
        )
        assertInOrder(
            readiness,
            "WalkSessionReadinessRequirement.FIRST_RUN_ONBOARDING ->",
            "WalkSessionReadinessRequirement.PRIORITY_USER_ONBOARDING ->",
            "WalkSessionReadinessRequirement.PERMISSIONS ->",
            "WalkSessionReadinessRequirement.MODEL ->",
        )
        assertTrue(readiness.contains("first_run_onboarding_incomplete:"))

        val blockReason = functionBlock("private fun walkSessionReadinessBlockReason(")
        assertInOrder(
            blockReason,
            "if (!firstRunOnboardingComplete())",
            "if (!decision.mayConfirmAndStart)",
            "priorityUserOnboardingPolicy.evaluate(",
            "missingRequiredWalkSessionPermissions(action)",
        )

        val detectorLoad = functionBlock("private fun loadDetectorAfterCameraGate()")
        assertInOrder(
            detectorLoad,
            "if (!firstRunOnboardingComplete()) return",
            "loadDetectorForCurrentProcess()",
        )
        assertInOrder(
            functionBlock("private fun loadDetectorForCurrentProcess()"),
            "detectorLoadAttempted = true",
            "TfliteAndroidFrameDetector.createWithStatus",
        )
    }

    @Test
    fun permissionsRequireCompletionExceptForDeviceCheckPreflightAndUseFirstRunLeases() {
        val policy = functionBlock("private fun firstRunPermissionRequestAllowed(")
        assertInOrder(
            policy,
            "PermissionRequestPurpose.POST_LOGIN_DEVICE_CHECK ->",
            "FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION",
            "PermissionRequestPurpose.METRIC_PREFLIGHT_CAMERA ->",
            "firstRunDeviceCheckAllowsPreflight()",
            "PermissionRequestPurpose.WALK_SESSION,",
            "PermissionRequestPurpose.RUNTIME_CAMERA,",
            "PermissionRequestPurpose.NAVIGATION,",
            "PermissionRequestPurpose.VOICE_COMMAND,",
            "firstRunOnboardingComplete()",
        )

        val request = functionBlock("private fun requestPermissionsWithLease(")
        assertInOrder(
            request,
            "if (!firstRunPermissionRequestAllowed(purpose)) return null",
            "firstRunLease = currentFirstRunAsyncLease()",
            "requestPermissions(permissions, requestCode)",
        )

        val current = functionBlock("private fun isPermissionRequestLeaseCurrent(")
        assertInOrder(
            current,
            "if (!isFirstRunAsyncLeaseCurrent(lease.firstRunLease)) return false",
            "if (!firstRunPermissionRequestAllowed(lease.purpose)) return false",
            "val snapshot = walkSessionLifecycle.snapshot()",
        )

        val lease = sourceSection(
            "private data class PermissionRequestLease(",
            "private data class FirstRunAsyncLease(",
        )
        assertTrue(lease.contains("val firstRunLease: FirstRunAsyncLease"))
    }

    @Test
    fun deviceCheckPreflightIsStageBoundLeaseBoundAndWorkloadIsolated() {
        val stageGate = functionBlock("private fun firstRunDeviceCheckAllowsPreflight()")
        assertInOrder(
            stageGate,
            "PostLoginDeviceCheckState.RUNNING",
            "PostLoginDeviceCheckPolicy.isCurrent",
        )

        val eligibility = functionBlock("private fun canBeginRuntimeMetricPreflight()")
        assertTrue(eligibility.contains("if (!firstRunDeviceCheckAllowsPreflight()) return false"))

        val begin = functionBlock("private fun beginRuntimeMetricPreflight()")
        assertInOrder(
            begin,
            "if (!canBeginRuntimeMetricPreflight()) return",
            "metricPreflightFirstRunLease = currentFirstRunAsyncLease()",
            "requestPermissionsWithLease(",
            "startRuntimeMetricPreflight(generation, metricPreflightLifecycleGeneration)",
        )

        val current = functionBlock("private fun isRuntimeMetricPreflightAttemptCurrent(")
        assertInOrder(
            current,
            "metricPreflightFirstRunLease?.let(::isFirstRunAsyncLeaseCurrent) == true",
            "firstRunDeviceCheckAllowsPreflight()",
            "generation == runtimeMetricPreflightGeneration",
        )
        assertTrue(
            functionBlock("private fun invalidateRuntimeMetricEvidence(")
                .contains("metricPreflightFirstRunLease = null"),
        )
        assertTrue(
            functionBlock("private fun onFirstRunOnboardingStateChanged(")
                .contains("invalidateRuntimeMetricEvidence(\"first_run_stage_changed\")"),
        )

        val start = functionBlock("private fun startRuntimeMetricPreflightSession(")
        assertTrue(start.contains("arSessionPurpose = ArSessionPurpose.PREFLIGHT"))
        assertTrue(start.contains("runtimeMetricOutputAllowed = false"))
        listOf(
            "loadDetectorAfterCameraGate(",
            "startNavigationServicesIfNeeded(",
            "startCameraFallbackSession(",
            "prepareReportCandidate(",
            "publishCurrentFrameReportState(",
            "dispatchFeedback(",
        ).forEach { forbiddenCall ->
            assertFalse(
                "device-check preflight must not call $forbiddenCall",
                start.contains(forbiddenCall),
            )
        }

        val frame = functionBlock("private fun handleRuntimeMetricPreflightFrame(")
        assertInOrder(
            frame,
            "if (firstRunOnboardingComplete())",
            "observeOfficialEnvironmentCameraFrame(",
            "val validSamples = snapshot.runtimeMetricValidSampleCount()",
        )

        val failure = functionBlock("private fun handleRuntimeMetricFrameFailure(")
        assertInOrder(
            failure,
            "expectedPurpose == ArSessionPurpose.RUNTIME",
            "firstRunOnboardingComplete()",
            "observeOfficialEnvironmentCameraFrame(",
            "when (expectedPurpose)",
        )
    }

    @Test
    fun completionGateCoversWalkSensorsCameraLocationRouteFeedbackAndReports() {
        assertTrue(
            functionBlock("private fun isWalkSessionRuntimeActive()")
                .contains("return firstRunOnboardingComplete() &&"),
        )
        assertInOrder(
            functionBlock("private fun activateWalkSessionRuntime()"),
            "if (!isWalkSessionRuntimeActive()) return",
            "earthOrientationTracker.start()",
            "startNavigationServicesIfNeeded()",
        )
        listOf(
            "private fun startDepthSession()",
            "private fun startCameraFallbackSession(",
        ).forEach { marker ->
            assertInOrder(
                functionBlock(marker),
                "if (!firstRunOnboardingComplete()) return",
                "if (!isWalkSessionRuntimeActive()) return",
            )
        }
        listOf(
            "private fun currentLocationCollectionAllowsWork()",
            "private fun currentNavigationCollectionAllowsWork()",
            "private fun currentFeedbackDeviceGateAllowsAlerts()",
        ).forEach { marker ->
            assertTrue(
                functionBlock(marker).contains("if (!firstRunOnboardingComplete()) return false"),
            )
        }
        assertTrue(
            functionBlock("private fun currentRuntimeMetricOutputAllowsWork(")
                .contains("firstRunOnboardingComplete() &&"),
        )
        assertTrue(
            functionBlock("private fun startLocationUpdatesIfAllowed(")
                .contains("currentLocationCollectionAllowsWork()"),
        )
        assertTrue(
            functionBlock("private fun startStepTrackingIfAllowed()")
                .contains("currentNavigationCollectionAllowsWork()"),
        )
        assertTrue(
            functionBlock("private fun requestRoute(")
                .contains("if (!currentNavigationCollectionAllowsWork()) return"),
        )
        assertTrue(
            functionBlock("private fun requestExplicitReport()")
                .contains("if (!currentRuntimeMetricOutputAllowsWork())"),
        )
        assertInOrder(
            functionBlock("private fun processReportCandidate("),
            "walkSessionLifecycle.isRuntimeEpochCurrent(expectedWalkEpoch)",
            "!officialEnvironmentOutputsAllowed || !phoneMountingOutputsAllowed",
            "reportQueueStore.enqueue(",
        )
        assertInOrder(
            functionBlock("private fun drainInitialExactReportQueue("),
            "val context = reportQueueDrainContext(trigger)",
            "if (!context.allRequiredBaseGatesAllowed()) break",
            "reportQueueDrainCoordinator.startNext(",
            "call.execute()",
        )
    }

    @Test
    fun firstRunStatusIsTalkBackAccessibleAndFirstOnTheStartupSurface() {
        val status = sourceSection(
            "firstRunOnboardingStatusText = TextView(this).apply",
            "ViewCompat.setAccessibilityHeading(firstRunOnboardingStatusText, true)",
        )
        assertTrue(status.contains("contentDescription = text"))
        assertTrue(
            status.contains("importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES"),
        )
        assertTrue(
            status.contains("accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE"),
        )

        val controls = sourceSection(
            "firstRunOnboardingControls = LinearLayout(this).apply",
            "priorityUserOnboardingStatusText = TextView(this).apply",
        )
        assertInOrder(
            controls,
            "addView(firstRunOnboardingStatusText)",
            "addView(firstRunPurposeButton)",
            "addView(firstRunAgeButtons.getValue(ageBand))",
        )

        val overlay = sourceSection(
            "val overlay = LinearLayout(this).apply",
            "controlsScroll = ScrollView(this).apply",
        )
        // 준비 표면(우선사용자 교육·환경·장착·기기 점검)은 walkReadinessControls 한 덩어리다.
        assertInOrder(
            overlay,
            "addView(productPurposeText)",
            "addView(firstRunOnboardingControls)",
            "addView(walkReadinessControls)",
            "addView(runtimeControls)",
        )
        val readiness = sourceSection(
            "walkReadinessControls = LinearLayout(this).apply",
            "applyWalkButtonStyle(actionButton",
        )
        assertInOrder(
            readiness,
            "addView(priorityUserOnboardingControls)",
            "addView(startupCapabilityText)",
        )

        val traversal = functionBlock("private fun linkFirstRunAccessibilityTraversal()")
        assertInOrder(
            traversal,
            "add(firstRunOnboardingStatusText)",
            "add(firstRunPurposeButton)",
            "add(firstRunAgeButtons.getValue(ageBand))",
            "current.accessibilityTraversalAfter = previous.id",
        )
        assertTrue(traversal.contains("previous.nextFocusForwardId = current.id"))
        assertTrue(traversal.contains("previous.nextFocusDownId = current.id"))
        assertTrue(traversal.contains("current.nextFocusUpId = previous.id"))

        val update = functionBlock("private fun updateFirstRunOnboardingUi()")
        assertInOrder(
            update,
            "firstRunOnboardingStatusText.text = message",
            "firstRunOnboardingStatusText.contentDescription = message",
        )
    }

    private fun sourceSection(startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("missing source marker: $startMarker", start >= 0)
        val end = source.indexOf(endMarker, start + startMarker.length)
        assertTrue("missing source marker: $endMarker", end > start)
        return source.substring(start, end)
    }

    private fun functionBlock(marker: String): String {
        val start = source.indexOf(marker)
        assertTrue("missing function: $marker", start >= 0)
        val end = listOf(
            source.indexOf("\n    private fun ", start + marker.length),
            source.indexOf("\n    internal fun ", start + marker.length),
            source.indexOf("\n    override fun ", start + marker.length),
        ).filter { it > start }
            .minOrNull()
            ?: source.length
        return source.substring(start, end)
    }

    private fun assertInOrder(section: String, vararg tokens: String) {
        var previous = -1
        tokens.forEach { token ->
            val current = section.indexOf(token, previous + 1)
            assertTrue("missing or out-of-order token: $token", current > previous)
            previous = current
        }
    }
}

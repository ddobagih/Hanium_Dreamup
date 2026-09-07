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
        assertTrue(source.contains("const val EMAIL_FIRST_RUN_STAGE_COUNT = 7"))
        val progress = sourceSection(
            "firstRunProgressBar = LinearLayout(this).apply",
            "firstRunOnboardingStatusText = TextView(this).apply",
        )
        assertTrue(
            progress.contains(
                "repeat(firstRunStageCount(firstRunOnboardingSnapshot))",
            ),
        )

        val mapping = functionBlock("private fun firstRunStageNumber(")
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
            "val firstRunIsStale =",
            "val effectiveFirstRun = if (firstRunIsStale)",
            "val reporterActorId = effectiveFirstRun?.reporterActorBinding?.value",
            "val verifiedEmailActorId = effectiveFirstRun?.takeIf",
            "val actorId = reporterActorId ?: verifiedEmailActorId",
            "val verifiedActorSession = session?.takeIf",
            "actorId == current.actorId",
            "current.verificationState == GatewaySessionVerificationState.VERIFIED",
            "current.isUsableFor(current.actorId)",
            "if (verifiedActorSession == null)",
            "cancelPendingPriorityUserTrainingFeedback()",
            "reporterUserId = null",
            "if (\n            verifiedActorSession != null",
            "effectiveFirstRun != null",
            "actorId != null",
            "actorId == verifiedActorSession.actorId",
            "if (firstRun != null && !firstRunIsStale)",
            "firstRunOnboardingSnapshot = firstRun",
            "val appliedFirstRun = firstRunOnboardingSnapshot",
            "reporterUserId = actorId",
            "appliedFirstRun.isComplete",
            "} else {\n            permissionSessionPolicy.authenticationExpired()",
        )
        assertTrue(
            functionBlock("private fun onAccountLogoutClicked()")
                .contains("firstRunOnboardingSnapshot = FirstRunOnboardingPolicy.initialEmailAccount("),
        )
    }

    @Test
    fun startupProbeUsesTheDeviceCheckGateAndResourceMonitoringAlsoStartsForActiveWalks() {
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
            "ensureWalkSessionResourceMonitoring()",
            "startupCapabilityProbe.start()",
        )
        val resourceMonitoring =
            functionBlock("private fun ensureWalkSessionResourceMonitoring()")
        assertInOrder(
            resourceMonitoring,
            "walkSessionResourceProbe.start {",
            "observeWalkRuntimeResourceSafety()",
            "refreshStartupCapabilityUi()",
        )
        assertTrue(
            functionBlock("private fun activateWalkSessionRuntime()")
                .contains("ensureWalkSessionResourceMonitoring()"),
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
            "val operation = if (expectedProcessGeneration != null && expectedSession != null)",
            "GatewaySessionProcessCoordinator.beginGeneralSessionOperationIfCurrent(",
            "GatewaySessionProcessCoordinator.beginOperation()",
            "} ?: return null",
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
            "val postLoginBinding =",
            "isPostLoginDeviceCheckSnapshotBindingLive(it)",
            "firstRunLease = currentFirstRunAsyncLease()",
            "requestPermissions(permissions, requestCode)",
        )

        val current = functionBlock("private fun isPermissionRequestLeaseCurrent(")
        assertInOrder(
            current,
            "if (!isFirstRunAsyncLeaseCurrent(lease.firstRunLease)) return false",
            "if (!firstRunPermissionRequestAllowed(lease.purpose)) return false",
            "lease.postLoginBinding?.let(::isPostLoginDeviceCheckSnapshotBindingLive)",
            "val snapshot = walkSessionLifecycle.snapshot()",
        )

        val lease = sourceSection(
            "private data class PermissionRequestLease(",
            "private data class FirstRunAsyncLease(",
        )
        assertTrue(lease.contains("val firstRunLease: FirstRunAsyncLease"))
        assertTrue(lease.contains("val postLoginBinding: PostLoginDeviceCheckBinding?"))
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
        assertTrue(frame.contains("val validSamples = snapshot.runtimeMetricValidSampleCount()"))
        assertFalse(frame.contains("observeOfficialEnvironmentCameraFrame("))

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
    fun deviceCheckMovesFocusToVisiblePostureOrEducationWithoutForcingHomeFocus() {
        val changed = functionBlock("private fun onFirstRunOnboardingStateChanged(")
        val focus = functionBlock("private fun focusCurrentFirstRunStage(")
        val update = functionBlock("private fun updateFirstRunOnboardingUi()")
        assertInOrder(changed, "updateFirstRunOnboardingUi()", "focusCurrentFirstRunStage(firstRunOnboardingSnapshot.stage)")
        assertTrue(focus.contains("FirstRunOnboardingStage.FP004_TRAINING ->"))
        assertTrue(focus.contains("if (shouldShowFirstRunPhonePosture()) firstRunPhonePostureText"))
        assertTrue(focus.contains("else priorityUserOnboardingStatusText"))
        assertTrue(focus.contains("FirstRunOnboardingStage.COMPLETE -> return"))
        assertTrue(focus.contains("target.post"))
        assertTrue(focus.contains("firstRunOnboardingSnapshot.stage != stage"))
        assertTrue(focus.contains("!target.isShown"))
        assertTrue(focus.contains("target.requestRectangleOnScreen("))
        assertTrue(focus.contains("ACTION_ACCESSIBILITY_FOCUS"))
        assertTrue(update.contains("snapshot.stage == FirstRunOnboardingStage.FP004_TRAINING"))
        assertTrue(update.contains("val showWalkPreparation = snapshot.isComplete"))
    }

    @Test
    fun completionGateCoversWalkSensorsCameraLocationRouteFeedbackAndReports() {
        assertTrue(
            functionBlock("private fun isWalkSessionRuntimeActive()")
                .contains("return firstRunOnboardingComplete() &&"),
        )
        val activation = functionBlock("private fun activateWalkSessionRuntime()")
        val startAfterCameraRelease =
            functionBlock("private fun startWalkSessionRuntimeAfterCameraRelease(")
        assertInOrder(
            activation,
            "if (!isWalkSessionRuntimeActive()) return",
            "beginRuntimeCameraHandoff(runtimeEpoch)",
            "stopOfficialEnvironmentCameraPreflight(",
            "startWalkSessionRuntimeAfterCameraRelease(runtimeEpoch)",
        )
        assertInOrder(
            startAfterCameraRelease,
            "!walkSessionLifecycle.isRuntimeEpochCurrent(expectedEpoch)",
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
            "private fun currentStepTrackingCollectionAllowsWork()",
            "private fun currentFeedbackDeviceGateAllowsAlerts()",
        ).forEach { marker ->
            assertTrue(
                functionBlock(marker).contains("if (!firstRunOnboardingComplete()) return false"),
            )
        }
        assertTrue(
            functionBlock("private fun currentNavigationCollectionAllowsWork()")
                .contains("return currentStepTrackingCollectionAllowsWork()"),
        )
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
                .contains("currentStepTrackingCollectionAllowsWork()"),
        )
        assertTrue(
            functionBlock("private fun requestRoute(")
                .contains("if (!currentNavigationCollectionAllowsWork()) return"),
        )
        assertTrue(
            functionBlock("private fun ensureExplicitReportCapturePreconditions()")
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
    fun firstRunStatusAndAccountContentKeepTheVisibleNativeTraversalOrder() {
        val status = sourceSection("firstRunOnboardingStatusText = TextView(this).apply", "ViewCompat.setAccessibilityHeading(firstRunOnboardingStatusText, true)")
        assertTrue(status.contains("contentDescription = text"))
        assertTrue(status.contains("importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES"))
        assertTrue(status.contains("accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE"))
        val controls = sourceSection("firstRunOnboardingControls = LinearLayout(this).apply", "priorityUserOnboardingStatusText = TextView(this).apply")
        assertInOrder(controls, "addView(firstRunOnboardingStatusText)", "addView(productPurposeText)", "addView(accountAccessControls)", "addView(firstRunPurposeButton)")
        assertFalse(controls.contains("addView(firstRunAgeButtons.getValue(ageBand))"))
        val overlay = sourceSection("val overlay = LinearLayout(this).apply", "controlsScroll = ScrollView(this).apply")
        assertInOrder(overlay, "addView(firstRunOnboardingControls)", "addView(walkReadinessControls)", "addView(runtimeControls)")
        val readiness = sourceSection("walkReadinessControls = LinearLayout(this).apply", "walkLastResultText = TextView(this).apply")
        assertInOrder(readiness, "addView(priorityUserOnboardingControls)", "addView(startupCapabilityText)", "addView(nativeDeviceCheckPanel)", "addView(startupMetricPreflightButton)", "addView(startupCapabilityConfirmButton)")
        val traversal = functionBlock("private fun linkFirstRunAccessibilityTraversal()")
        assertInOrder(traversal, "add(firstRunOnboardingStatusText)", "add(accountAccessStatusText)", "add(accountEmailInput)", "add(accountPasswordInput)", "add(accountConsentClauseTexts.getValue(key))", "add(accountConsentListenButtons.getValue(key))", "current.accessibilityTraversalAfter = previous.id")
        assertTrue(traversal.contains("previous.nextFocusForwardId = current.id"))
        assertTrue(traversal.contains("previous.nextFocusDownId = current.id"))
        assertTrue(traversal.contains("current.nextFocusUpId = previous.id"))
        val update = functionBlock("private fun updateFirstRunOnboardingUi()")
        assertInOrder(update, "firstRunOnboardingStatusText.text = message", "firstRunOnboardingStatusText.contentDescription = message")
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

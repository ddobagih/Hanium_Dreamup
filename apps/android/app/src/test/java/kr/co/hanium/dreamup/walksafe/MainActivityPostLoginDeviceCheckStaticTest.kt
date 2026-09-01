package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityPostLoginDeviceCheckStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val fieldLogSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/fieldlog/FieldSessionLog.kt",
    ).readText()

    @Test
    fun loginDoesNotAutomaticallyStartAndOneTapBeginsTheDeviceCheck() {
        val login = source.substringAfter("private fun loginEmailAccount(")
            .substringBefore("private fun postAccountFailure(")
        val start = source.substringAfter(
            "private fun startPostLoginDeviceCheckFromPrimaryAction()",
        ).substringBefore("private fun handlePostLoginDeviceCheckPrimaryAction()")
        val primaryAction = source.substringAfter(
            "private fun handlePostLoginDeviceCheckPrimaryAction()",
        ).substringBefore("private fun beginPostLoginDeviceCheckFromUserAction(")
        val begin = source.substringAfter("private fun beginPostLoginDeviceCheckFromUserAction(")
            .substringBefore("private fun handlePostLoginDeviceCheckPermissionResult(")

        assertFalse(login.contains("beginPostLoginDeviceCheckFromUserAction("))
        assertTrue(source.contains("setOnClickListener { handlePostLoginDeviceCheckPrimaryAction() }"))
        assertTrue(primaryAction.contains("startPostLoginDeviceCheckFromPrimaryAction()"))
        assertTrue(start.contains("acknowledgeHandsFreeVoiceDisclosure()"))
        assertTrue(start.contains("beginPostLoginDeviceCheckFromUserAction(sessionBinding)"))
        assertFalse(primaryAction.contains("showPostLoginDeviceCheckExplanation()"))
        assertFalse(start.contains("AlertDialog"))
        assertFalse(begin.contains("AlertDialog"))
        assertTrue(begin.contains("requestPermissionsWithLease("))
        assertTrue(source.contains("PostLoginDeviceCheckPolicy.beginFromUserAction("))
    }

    @Test
    fun permissionAndDeviceStagesUseLocalTransitionsAndRespectLimitedFeatures() {
        val firstRunGate = source.substringAfter("private fun firstRunOnboardingComplete()")
            .substringBefore("private fun postLoginDeviceCheckPassesFeatureGate()")
        val deviceGate = source.substringAfter("private fun postLoginDeviceCheckPassesFeatureGate()")
            .substringBefore("private fun revalidateCompletedPostLoginDeviceCheckPrerequisites()")
        val featureGate = source.substringAfter(
            "private fun postLoginDeviceFeatureEnabled(feature: PostLoginDeviceCheckFeature)",
        ).substringBefore("private fun postLoginDeviceCheckItemsMessage()")

        assertTrue(source.contains("PermissionRequestPurpose.POST_LOGIN_DEVICE_CHECK"))
        assertTrue(source.contains("recordEmailJitPermissionObservation("))
        assertTrue(source.contains("recordEmailDeviceCheckPassed("))
        assertTrue(source.contains("postLoginDeviceCheckPassesFeatureGate()"))
        assertTrue(firstRunGate.contains("firstRunOnboardingSnapshot.mayEnterWalk"))
        assertTrue(firstRunGate.contains("GatewaySessionProcessCoordinator.snapshot()"))
        assertTrue(deviceGate.contains("postLoginDeviceCheckSnapshot.passesFeatureGate"))
        assertFalse(deviceGate.contains("WalkSafeStartupCapabilityTier.FULL"))
        assertTrue(featureGate.contains("postLoginDeviceCheckSnapshot.passesFeatureGate"))
        assertTrue(featureGate.contains("feature !in currentPostLoginDisabledFeatures()"))
    }

    @Test
    fun passedDeviceCheckCannotBeReexposedAsANoOpCompletionButton() {
        val button = source.substringAfter("startupMetricPreflightButton = Button(this).apply")
            .substringBefore("postLoginDeviceCheckSettingsButton = Button(this).apply")
        val firstRunUi = source.substringAfter("private fun updateFirstRunOnboardingUi()")
            .substringBefore("private fun linkPriorityUserAccessibilityTraversal()")
        val capabilityUi = source.substringAfter("private fun refreshStartupCapabilityUi()")
            .substringBefore("private fun applyRuntimeReadinessIfActive(")
        val primaryAction = source.substringAfter(
            "private fun handlePostLoginDeviceCheckPrimaryAction()",
        ).substringBefore("private fun beginPostLoginDeviceCheckFromUserAction(")

        assertTrue(button.contains("visibility = View.GONE"))
        assertFalse(firstRunUi.contains("startupMetricPreflightButton.visibility"))
        assertTrue(capabilityUi.contains("val deviceCheckStage"))
        assertTrue(capabilityUi.contains("val showDeviceCheckAction"))
        assertTrue(capabilityUi.contains("!postLoginDeviceCheckSnapshot.passesFeatureGate"))
        assertTrue(capabilityUi.contains("isEnabled = showDeviceCheckAction &&"))
        assertTrue(primaryAction.contains("postLoginDeviceCheckSnapshot.passesFeatureGate"))
        assertTrue(
            primaryAction.indexOf("postLoginDeviceCheckSnapshot.passesFeatureGate") <
                primaryAction.indexOf("startPostLoginDeviceCheckFromPrimaryAction()"),
        )
    }

    @Test
    fun cameraPipelineUsesBoundedDetectorAttemptsWithoutWalkOutputs() {
        val preflight = source.substringAfter(
            "private fun startPostLoginCameraPipelinePreflight(",
        ).substringBefore("private fun isPostLoginCameraPipelinePreflightCurrent(")
        assertTrue(preflight.contains("ProcessCameraProvider.getInstance(this)"))
        assertTrue(preflight.contains("frameClaimed.compareAndSet(false, true)"))
        assertTrue(preflight.contains("attemptedFrames.incrementAndGet()"))
        assertTrue(preflight.contains("POST_LOGIN_CAMERA_PIPELINE_MAX_FRAMES"))
        assertTrue(preflight.contains("frameClaimed.set(false)"))
        assertTrue(preflight.contains("POST_LOGIN_CAMERA_PIPELINE_TIMEOUT_MS"))
        assertTrue(preflight.contains("frameDetector.detect("))
        assertTrue(preflight.contains("DeviceCheckDetectorExecutionPolicy.passes(result)"))
        assertTrue(preflight.contains("finishPostLoginCameraPipelinePreflight("))
        assertFalse(preflight.contains("phoneMountingSensorProbe"))
        assertFalse(preflight.contains("CameraLumaMeasurementPolicy"))
        assertFalse(preflight.contains("cameraFrameQualityObservation"))
        assertFalse(preflight.contains("angularShakeDegreesPerSecond"))
        assertFalse(preflight.contains("mountPitchDegrees"))
        assertFalse(preflight.contains("dispatchFeedback("))
        assertFalse(preflight.contains("processReportCandidate("))
        assertFalse(preflight.contains("recordRawCollectionDetectionMetadata("))
    }

    @Test
    fun functionalLocationProbeWaitsForAPassingSampleAndCleansUpItsCallback() {
        val start = source.substringAfter("private fun startPostLoginLocationFixProbe(")
            .substringBefore("private fun isPostLoginDeviceCheckBindingCurrent(")
        val finish = source.substringAfter("private fun finishPostLoginLocationFixProbe(")
            .substringBefore("private fun cancelPostLoginLocationFixProbe(")
        val cancel = source.substringAfter("private fun cancelPostLoginLocationFixProbe(")
            .substringBefore("private fun requestPostLoginDeviceCheckHaptic(")

        assertTrue(start.contains("object : LocationCallback()"))
        assertTrue(start.contains("val decisions = result.locations.map(::evaluatePostLoginLocationFix)"))
        assertTrue(start.contains("decisions.forEach(::recordPostLoginLocationFixDiagnostic)"))
        assertTrue(start.contains("DeviceCheckLocationFixPolicy.selectBatch(decisions)"))
        assertTrue(start.contains("DeviceCheckLocationFixReason.MOCK_LOCATION"))
        assertTrue(start.contains("fusedLocationClient.requestLocationUpdates("))
        assertTrue(start.contains("callback,"))
        assertTrue(start.contains("Looper.getMainLooper()"))
        assertTrue(start.contains("setWaitForAccurateLocation(false)"))
        assertTrue(
            start.contains(
                "setMaxUpdateAgeMillis(DeviceCheckLocationFixPolicy.MAX_AGE_MS)",
            ),
        )
        assertTrue(source.contains("POST_LOGIN_LOCATION_FIX_TIMEOUT_MS = 30_000L"))
        assertFalse(start.contains("getCurrentLocation("))
        assertTrue(finish.contains("cancelPostLoginLocationFixProbe()"))
        assertTrue(cancel.contains("removeLocationUpdates"))
    }

    @Test
    fun cameraChecksOwnTheirUseCasesAndWaitForAnalyzerCleanupBeforeRetry() {
        val start = source.substringAfter("private fun startPostLoginCameraPipelinePreflight(")
            .substringBefore("private fun isPostLoginCameraPipelinePreflightCurrent(")
        val stop = source.substringAfter("private fun stopPostLoginCameraPipelinePreflight()")
            .substringBefore("private fun currentFirstRunAsyncLease()")

        assertTrue(start.contains("officialEnvironmentCameraCleanupPending"))
        assertTrue(start.contains("postLoginCameraPipelineProvider = provider"))
        assertTrue(start.contains("postLoginCameraPipelineAnalysis = analysis"))
        assertFalse(start.contains("cameraFallbackProvider = provider"))
        assertTrue(start.contains("terminalClaim.compareAndSet(false, true)"))
        assertTrue(stop.contains("ownedAnalysis?.clearAnalyzer()"))
        assertTrue(stop.contains("postLoginCameraPipelineCleanupPending ="))
        assertTrue(stop.contains("detectorExecutor.execute"))
        assertTrue(stop.contains("ownedProvider?.unbind(ownedAnalysis)"))
        assertTrue(stop.contains("CAMERA_ANALYZER_RELEASE_BARRIER_TIMEOUT_MS"))
        assertTrue(stop.contains("awaitCameraXClosed(ownedCamera)"))
        assertTrue(stop.contains("cameraXReleaseBlocked = true"))
        assertTrue(stop.contains("PostLoginCameraPipelineFailure.RELEASE_TIMED_OUT"))
        assertTrue(
            stop.indexOf("maybeContinuePostLoginDeviceCheck(currentBinding)") <
                stop.indexOf("refreshStartupCapabilityUi()"),
        )
    }

    @Test
    fun locationFailureIsDiagnosableWithoutLoggingCoordinates() {
        val diagnostic = source.substringAfter("private fun recordPostLoginLocationFixDiagnostic(")
            .substringBefore("private fun finishPostLoginLocationFixProbe(")
        val terminalDiagnostic = source.substringAfter(
            "private fun recordPostLoginLocationTerminalDiagnostic(",
        ).substringBefore("private fun cancelPostLoginLocationFixProbe()")
        val failure = source.substringAfter("private fun postLoginLocationFixFailureMessage()")
            .substringBefore("Proves the camera and production detector path")

        assertTrue(diagnostic.contains("GPS_PREFLIGHT_LOG_TAG"))
        assertTrue(diagnostic.contains("decision.reason"))
        assertTrue(diagnostic.contains("decision.accuracyMeters"))
        assertTrue(diagnostic.contains("decision.ageMs"))
        assertFalse(diagnostic.contains("latitude"))
        assertFalse(diagnostic.contains("longitude"))
        assertTrue(terminalDiagnostic.contains("PREF_DEVICE_CHECK_LOCATION_RESULT"))
        assertTrue(terminalDiagnostic.contains("PREF_DEVICE_CHECK_LOCATION_REASON"))
        assertTrue(terminalDiagnostic.contains("PREF_DEVICE_CHECK_LOCATION_ACCURACY_BUCKET"))
        assertTrue(terminalDiagnostic.contains("PREF_DEVICE_CHECK_LOCATION_AGE_BUCKET"))
        assertTrue(terminalDiagnostic.contains("post_login_location_check_terminal"))
        assertFalse(terminalDiagnostic.contains("latitude"))
        assertFalse(terminalDiagnostic.contains("longitude"))
        assertTrue(fieldLogSource.contains("\"accuracy_bucket\""))
        assertTrue(fieldLogSource.contains("\"age_bucket\""))
        assertTrue(fieldLogSource.contains("\"result\""))
        assertTrue(failure.contains("ACCURACY_OUTSIDE_FUNCTIONAL_RANGE"))
        assertTrue(failure.contains("MOCK_LOCATION"))
        assertTrue(failure.contains("PostLoginLocationFixTerminalFailure.REQUEST_FAILED"))
    }

    @Test
    fun statusIsAccessibleAndFailureOffersSettingsRecovery() {
        val evaluate = source.substringAfter("private fun evaluatePostLoginDeviceCheck(")
            .substringBefore("private fun currentPostLoginDeviceCheckObservation()")
        val progress = source.substringAfter(
            "private fun announcePostLoginDeviceCheckProgress(message: String)",
        ).substringBefore("private fun announcePostLoginDeviceCheckTerminal(message: String)")
        val terminal = source.substringAfter(
            "private fun announcePostLoginDeviceCheckTerminal(message: String)",
        ).substringBefore("private fun clearPostLoginDeviceCheckOverallTimeout()")
        val timeout = source.substringAfter("private fun startPostLoginDeviceCheckRuntime(")
            .substringBefore("private fun maybeContinuePostLoginDeviceCheck(")
        val sessionFailure = source.substringAfter(
            "private fun failPostLoginDeviceCheckForSessionChange()",
        ).substringBefore("private fun cancelPostLoginDeviceCheckRuntime(")
        val revalidate = source.substringAfter(
            "private fun revalidateCompletedPostLoginDeviceCheckPrerequisites()",
        ).substringBefore("private fun bindPostLoginDeviceCheckSession(")
        assertTrue(source.contains("View.ACCESSIBILITY_LIVE_REGION_POLITE"))
        assertTrue(
            source.contains(
                "필수 기능 중 일부를 사용할 수 없습니다. 위의 사용할 수 없는 기능 목록을 확인하세요.",
            ),
        )
        assertFalse(source.contains("필수 카메라·GPS·마이크·진동·음성 기능을 사용할 수 없습니다."))
        assertTrue(source.contains("postLoginDeviceCheckSettingsButton"))
        assertTrue(source.contains("Settings.ACTION_LOCATION_SOURCE_SETTINGS"))
        assertTrue(source.contains("Settings.ACTION_APPLICATION_DETAILS_SETTINGS"))
        assertTrue(evaluate.contains("val detail = postLoginDeviceCheckFailureMessage(next.failure)"))
        assertTrue(evaluate.contains("기기 점검에 실패했습니다. \$detail"))
        assertTrue(evaluate.contains("announcePostLoginDeviceCheckTerminal("))
        assertFalse(evaluate.contains("화면의 실패 항목을 확인하세요"))
        assertTrue(progress.contains("View.ACCESSIBILITY_LIVE_REGION_POLITE"))
        assertTrue(terminal.contains("View.ACCESSIBILITY_LIVE_REGION_NONE"))
        assertFalse(terminal.contains("View.ACCESSIBILITY_LIVE_REGION_POLITE"))
        assertTrue(terminal.contains("isScreenReaderActive()"))
        assertTrue(terminal.contains("PostLoginDeviceCheckState.FAIL"))
        assertTrue(terminal.contains("postLoginDeviceCheckLiveStatusText.isShown"))
        assertTrue(terminal.contains("ACTION_ACCESSIBILITY_FOCUS"))
        assertEquals(1, "ACTION_ACCESSIBILITY_FOCUS".toRegex().findAll(terminal).count())
        assertTrue(timeout.contains("announcePostLoginDeviceCheckTerminal("))
        assertTrue(sessionFailure.contains("announcePostLoginDeviceCheckTerminal("))
        assertFalse(revalidate.contains("PostLoginDeviceCheckPolicy.invalidatePassedGate("))
        assertFalse(revalidate.contains("announcePostLoginDeviceCheckTerminal("))
    }

    @Test
    fun everyAsyncMutationAndResourceStartRequiresTheLiveGatewayBinding() {
        val start = source.substringAfter("private fun startPostLoginDeviceCheckRuntime(")
            .substringBefore("private fun maybeContinuePostLoginDeviceCheck(")
        val orchestration = source.substringAfter("private fun maybeContinuePostLoginDeviceCheck(")
            .substringBefore("private fun evaluatePostLoginDeviceCheck(")
        val evaluate = source.substringAfter("private fun evaluatePostLoginDeviceCheck(")
            .substringBefore("private fun currentPostLoginDeviceCheckObservation()")
        val camera = source.substringAfter("private fun startPostLoginCameraPipelinePreflight(")
            .substringBefore("private fun isPostLoginCameraPipelinePreflightCurrent(")
        val permissionCurrent = source.substringAfter("private fun isPermissionRequestLeaseCurrent(")
            .substringBefore("private fun handleMetricPreflightCameraPermissionResult()")
        val metricCurrent = source.substringAfter(
            "private fun isRuntimeMetricPreflightAttemptCurrent(",
        ).substringBefore("private fun finishRuntimeMetricPreflight(")

        assertTrue(start.contains("isPostLoginDeviceCheckBindingCurrent(binding)"))
        assertTrue(start.contains("!isPostLoginDeviceCheckBindingCurrent(binding)"))
        assertTrue(orchestration.contains("isPostLoginDeviceCheckBindingCurrent(binding)"))
        assertTrue(evaluate.contains("isPostLoginDeviceCheckSnapshotBindingLive(binding)"))
        assertTrue(camera.contains("isPostLoginDeviceCheckBindingCurrent(binding)"))
        assertTrue(permissionCurrent.contains("lease.postLoginBinding"))
        assertTrue(permissionCurrent.contains("isPostLoginDeviceCheckSnapshotBindingLive"))
        assertTrue(metricCurrent.contains("isPostLoginDeviceCheckSnapshotBindingLive(binding)"))
        assertTrue(source.contains("val postLoginBinding: PostLoginDeviceCheckBinding?"))
        assertFalse(source.contains("postLoginAttemptGeneration"))
    }

    @Test
    fun automaticMeasurementDoesNotWaitForWakePhraseOrHapticUserActions() {
        val orchestration = source.substringAfter("private fun maybeContinuePostLoginDeviceCheck(")
            .substringBefore("private fun evaluatePostLoginDeviceCheck(")
        val observation = source.substringAfter("private fun currentPostLoginDeviceCheckObservation()")
            .substringBefore("private fun completePostLoginDeviceCheckPass(")
        val evaluation = source.substringAfter("private fun evaluatePostLoginDeviceCheck(")
            .substringBefore("private fun currentPostLoginDeviceCheckObservation()")
        val completion = source.substringAfter("private fun completePostLoginDeviceCheckPass(")
            .substringBefore("private fun failPostLoginDeviceCheckForSessionChange()")

        assertFalse(orchestration.contains("maybeStartPostLoginWakePhraseProbe(binding)"))
        assertFalse(orchestration.contains("beginPostLoginWakePhraseProbeFromUserAction()"))
        assertFalse(orchestration.contains("confirmPostLoginDeviceCheckHaptic("))
        assertTrue(
            observation.contains(
                "wakePhraseRecognition = speechRecognitionAvailable.toDeviceCheckSignal()",
            ),
        )
        assertTrue(
            observation.contains(
                "hapticFeedback = startup.vibrationAvailable.toDeviceCheckSignal()",
            ),
        )
        assertTrue(evaluation.contains("PostLoginDeviceCheckState.FULL"))
        assertTrue(evaluation.contains("PostLoginDeviceCheckState.LIMITED"))
        assertTrue(evaluation.contains("completePostLoginDeviceCheckPass(next.state)"))
        assertTrue(completion.contains("recordEmailDeviceCheckPassed("))
        assertFalse(completion.contains("startupCapabilityConfirmButton"))
    }

    @Test
    fun inconclusiveFeatureMeasurementsBecomeLimitsInsteadOfBlockingTheApp() {
        val observation = source.substringAfter(
            "private fun currentPostLoginDeviceCheckObservation()",
        ).substringBefore("private fun completePostLoginDeviceCheckPass(")
        val blocking = observation.substringAfter("blockingFailure = when {")

        assertFalse(blocking.contains("!hasCameraPermission()"))
        assertFalse(blocking.contains("PostLoginDeviceCheckFailure.REQUIRED_PERMISSION"))
        assertTrue(blocking.contains("PostLoginDeviceCheckFailure.DEVICE_RESOURCE"))
        assertFalse(blocking.contains("PostLoginMetricDepthState.UNKNOWN"))
        assertFalse(blocking.contains("PostLoginDeviceCheckFailure.DEPTH_UNKNOWN"))
        assertFalse(blocking.contains("PostLoginMetricDepthState.TIMED_OUT"))
        assertFalse(blocking.contains("PostLoginDeviceCheckFailure.DEPTH_TIMEOUT"))
        assertFalse(blocking.contains("PostLoginCameraPipelineFailure.BIND_FAILED"))
        assertFalse(blocking.contains("PostLoginCameraPipelineFailure.NO_VALID_FRAME"))
        assertFalse(blocking.contains("PostLoginCameraPipelineFailure.DETECTOR_FAILED"))
        assertFalse(blocking.contains("PostLoginCameraPipelineFailure.RELEASE_TIMED_OUT"))
        assertFalse(blocking.contains("PostLoginCameraPipelineFailure.TIMED_OUT"))
        assertFalse(blocking.contains("PostLoginDeviceCheckFailure.CAMERA_PIPELINE_UNAVAILABLE"))

        val unsupported = observation.substringAfter("val unsupportedFeatures = buildSet {")
            .substringBefore("return PostLoginDeviceCheckObservation(")
        assertFalse(unsupported.contains("cameraPermissionGranted &&"))
        assertFalse(unsupported.contains("cameraPipeline == PostLoginDeviceCheckSignal.UNAVAILABLE"))
        assertFalse(unsupported.contains("!hasCameraPermission()"))
        assertTrue(unsupported.contains("startup.cameraAvailable == false"))
        assertTrue(unsupported.contains("detectorUnavailable"))
        assertTrue(unsupported.contains("PostLoginMetricDepthState.UNKNOWN"))
        assertTrue(unsupported.contains("PostLoginMetricDepthState.TIMED_OUT"))
    }

    @Test
    fun terminalCancellationOwnsTimeoutTtsCameraAndMetricPreflightCleanup() {
        val start = source.substringAfter("private fun startPostLoginDeviceCheckRuntime(")
            .substringBefore("private fun maybeContinuePostLoginDeviceCheck(")
        val cancel = source.substringAfter("private fun cancelPostLoginDeviceCheckRuntime(")
            .substringBefore("private fun isLocationServiceEnabledForDeviceCheck()")
        val metricCurrent = source.substringAfter(
            "private fun isRuntimeMetricPreflightAttemptCurrent(",
        ).substringBefore("private fun finishRuntimeMetricPreflight(")

        assertTrue(
            start.indexOf("postLoginDeviceCheckOverallTimeout = timeout") <
                start.indexOf("loadDetectorForPostLoginDeviceCheck()"),
        )
        assertTrue(start.contains("cancelPostLoginDeviceCheckRuntime(\"check_timeout\")"))
        assertTrue(cancel.contains("invalidateRuntimeMetricEvidence("))
        assertTrue(cancel.contains("startupCapabilityProbe.close()"))
        assertTrue(cancel.contains("stopPostLoginCameraPipelinePreflight()"))
        assertTrue(metricCurrent.contains("metricPreflightPostLoginBinding"))
        assertTrue(metricCurrent.contains("isPostLoginDeviceCheckSnapshotBindingLive"))
    }

    @Test
    fun backgroundFailureRemainsAccessibleDuringTheAutomaticCheck() {
        val resume = source.substringAfter(
            "private fun resumeWalkSafeRuntimeAfterPrivacyStartupInspection()",
        ).substringBefore("override fun onBackPressed()")
        val pause = source.substringAfter("override fun onPause()")
            .substringBefore("override fun onDestroy()")

        assertTrue(resume.contains("postLoginDeviceCheckBackgroundFailurePending"))
        assertTrue(resume.contains("announcePostLoginDeviceCheckTerminal("))
        assertTrue(resume.contains("PostLoginDeviceCheckFailure.BACKGROUNDED"))
        assertTrue(pause.contains("PostLoginDeviceCheckPolicy.onBackground("))
        assertTrue(pause.contains("postLoginDeviceCheckBackgroundFailurePending ="))
        assertTrue(pause.contains("PostLoginDeviceCheckFailure.BACKGROUNDED"))
    }

    @Test
    fun longItemListIsNotLiveAndLegacyInteractivePromptsStayHidden() {
        val capabilityText = source.substringAfter("startupCapabilityText = TextView(this).apply")
            .substringBefore("postLoginDeviceCheckLiveStatusText = TextView(this).apply")
        val liveStatus = source.substringAfter(
            "postLoginDeviceCheckLiveStatusText = TextView(this).apply",
        ).substringBefore("startupMetricPreflightButton = Button(this).apply")
        val items = source.substringAfter("private fun postLoginDeviceCheckItemsMessage()")
            .substringBefore("private fun postLoginDeviceCheckPermissionLabel(")
        val refresh = source.substringAfter("private fun refreshStartupCapabilityUi()")
            .substringBefore("private fun refreshOfficialEnvironmentUi(")

        assertFalse(capabilityText.contains("contentDescription"))
        assertFalse(capabilityText.contains("accessibilityLiveRegion"))
        assertTrue(liveStatus.contains("View.ACCESSIBILITY_LIVE_REGION_POLITE"))
        listOf(
            "postLoginDeviceCheckWakePhraseInstructionText",
            "postLoginDeviceCheckWakePhraseStartButton",
            "postLoginDeviceCheckHapticQuestionText",
            "postLoginDeviceCheckHapticConfirmButton",
            "postLoginDeviceCheckHapticRejectButton",
        ).forEach { control ->
            assertTrue(refresh.contains("$control.visibility = View.GONE"))
        }
        assertTrue(items.contains("startup.microphoneAvailable.toDeviceCheckSignal()"))
        assertTrue(items.contains("startup.gpsAvailable.toDeviceCheckSignal()"))
        assertTrue(items.contains("startup.vibrationAvailable.toDeviceCheckSignal()"))
        assertTrue(items.contains("startup.cameraAvailable.toDeviceCheckSignal()"))
    }
}

package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraXFallbackCompositionStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val advisoryPolicy = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/feedback/AndroidNonMetricObstacleAdvisoryPolicy.kt",
    ).readText()
    private val debugOverride = File(
        "src/debug/java/kr/co/hanium/dreamup/walksafe/CameraFallbackTestOverride.kt",
    ).readText()
    private val releaseOverride = File(
        "src/release/java/kr/co/hanium/dreamup/walksafe/CameraFallbackTestOverride.kt",
    ).readText()
    private val build = File("build.gradle.kts").readText()

    @Test
    fun fallbackUsesLatestFrameBackpressureAndAlwaysClosesImageProxy() {
        assertTrue(build.contains("androidx.camera:camera-camera2:1.6.1"))
        assertTrue(build.contains("androidx.camera:camera-lifecycle:1.6.1"))
        assertTrue(source.contains("ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST"))
        assertTrue(source.contains("setOutputImageRotationEnabled(true)"))
        assertTrue(source.contains("CameraSelector.DEFAULT_BACK_CAMERA"))
        assertTrue(source.contains("provider.bindToLifecycle(\n                        cameraFallbackLifecycleOwner"))
        assertTrue(source.contains("cameraFallbackLifecycleOwner.moveTo(Lifecycle.State.RESUMED)"))
        assertTrue(source.contains("cameraFallbackLifecycleOwner.moveTo(Lifecycle.State.CREATED)"))
        assertTrue(source.contains("cameraFallbackLifecycleOwner.moveTo(Lifecycle.State.DESTROYED)"))
        val analyzer = source.substringAfter("private fun analyzeCameraFallbackFrame(")
            .substringBefore("private fun emitCameraFallbackAdvisory(")
        assertTrue(analyzer.contains("finally"))
        assertTrue(analyzer.contains("imageProxy.close()"))
        assertTrue(analyzer.contains("var detectorSucceeded = false"))
        assertTrue(analyzer.contains("detectorRuntimeSupervisor.onSuccess()"))
        assertTrue(analyzer.contains("detectorSucceeded = true"))
        assertTrue(analyzer.contains("if (detectorSucceeded)"))
        assertTrue(analyzer.contains("recordCameraFallbackFrameAnalyzedOnce("))
        assertTrue(analyzer.contains("expectedWalkEpoch"))
        assertTrue(analyzer.contains("expectedFallbackGeneration"))
        assertTrue(
            analyzer.indexOf("lifecycleGeneration != feedbackLifecycleGeneration") <
                analyzer.indexOf("recordCameraFallbackFrameAnalyzedOnce("),
        )
    }

    @Test
    fun fallbackBindsPreviewAndImageAnalysisTogether() {
        val fallbackBinding = source.substringAfter("private fun bindCameraFallbackSession()")
            .substringBefore("private fun stopCameraFallbackSession(")
        val boundUseCases = fallbackBinding.substringAfter("provider.bindToLifecycle(")
            .substringBefore(")")

        assertTrue(source.contains("import androidx.camera.core.Preview"))
        assertTrue(fallbackBinding.contains("val preview = Preview.Builder()"))
        assertTrue(boundUseCases.contains("preview"))
        assertTrue(boundUseCases.contains("analysis"))
    }

    @Test
    fun degradedDetectionsStayOutsideMetricRouteAndReportPipelines() {
        val analyzer = source.substringAfter("private fun analyzeCameraFallbackFrame(")
            .substringBefore("private fun emitCameraFallbackAdvisory(")
        val emitter = source.substringAfter("private fun emitCameraFallbackAdvisory(")
            .substringBefore("private fun isCameraFallbackAdvisoryStillDeliverable(")
        val finalGuard = source.substringAfter("private fun isCameraFallbackAdvisoryStillDeliverable(")
            .substringBefore("private fun currentAndroidLocalTactileTier(")
        assertFalse(analyzer.contains("publishDetectionSnapshot"))
        assertFalse(analyzer.contains("prepareTactileFrameDispatch"))
        assertFalse(analyzer.contains("publishCurrentFrameReportState"))
        assertTrue(emitter.contains("val speechAccepted = speakAdvisory("))
        assertFalse(emitter.contains("speakInteraction(action.message)"))
        assertTrue(
            emitter.indexOf("isCameraFallbackAdvisoryStillDeliverable") <
                emitter.indexOf("val speechAccepted = speakAdvisory("),
        )
        assertFalse(emitter.contains("vibrateAdvisory"))
        assertFalse(emitter.contains("vibrationPatternMs"))
        assertTrue(emitter.contains("if (!nonMetricAdvisoryPolicy.claimDelivery(action)) return"))
        assertTrue(emitter.contains("nonMetricAdvisoryPolicy.rejectDelivery(action)"))
        assertTrue(emitter.contains("nonMetricAdvisoryPolicy.confirmDelivery(action"))
        assertTrue(emitter.contains("nonMetricAdvisoryPolicy.isDeliveryCurrent(action)"))
        assertTrue(
            emitter.indexOf("if (!nonMetricAdvisoryPolicy.claimDelivery(action)) return") <
                emitter.indexOf("val speechAccepted = speakAdvisory("),
        )
        assertTrue(
            emitter.indexOf("nonMetricAdvisoryPolicy.confirmDelivery(action") <
                emitter.indexOf("\"camera_non_metric_advisory_emitted\""),
        )
        assertFalse(advisoryPolicy.contains("vibrationPatternMs"))
        assertFalse(advisoryPolicy.contains("ADVISORY_VIBRATION_PATTERN_MS"))
        assertTrue(advisoryPolicy.contains("pendingQueueCapacity: Int = 3"))
        assertTrue(finalGuard.contains("nowMs <= action.validUntilMs"))
        assertTrue(finalGuard.contains("isCameraFallbackLeaseCurrent("))
        assertTrue(finalGuard.contains("expectedWalkEpoch"))
        assertTrue(finalGuard.contains("requireRunning = true"))
        assertTrue(finalGuard.contains("lifecycleGeneration == feedbackLifecycleGeneration"))
        assertTrue(finalGuard.contains("AndroidLocalTactileTier.CAMERA_IMU_NON_METRIC"))
        assertTrue(source.contains("latestReportCandidateStatus = \"reportCandidate=blocked:camera_non_metric_tier\""))
        assertTrue(source.contains("카메라 보조 경고 · 거리 제한 모드"))
        assertTrue(source.contains("현재 사용 가능한 위치·경로·걸음 수·음성·진동 기능은 계속"))
        assertFalse(source.contains("카메라 보조 경고 · TMAP 경로 유지"))
    }

    @Test
    fun fieldEvidenceRecordsTheNonMetricAndReportIsolationContracts() {
        val sessionStart = source.substringAfter("private fun bindCameraFallbackSession()")
            .substringBefore("private fun stopCameraFallbackSession(")
        val frameAnalyzed = source.substringAfter("private fun recordCameraFallbackFrameAnalyzedOnce(")
            .substringBefore("private fun emitCameraFallbackAdvisory(")
        val advisory = source.substringAfter("private fun emitCameraFallbackAdvisory(")
            .substringBefore("private fun isCameraFallbackAdvisoryStillDeliverable(")

        assertTrue(sessionStart.contains("\"camera_non_metric_session_started\""))
        assertTrue(sessionStart.contains("\"loaded_model\" to (detectorLoadedModelKey ?: \"none\")"))
        assertTrue(sessionStart.contains("\"model_fallback_used\" to detectorModelFallbackUsed"))
        assertTrue(sessionStart.contains("\"metric\" to false"))
        assertTrue(sessionStart.contains("\"reports_allowed\" to false"))
        assertTrue(sessionStart.contains("\"reason\" to startReason.fieldValue"))
        assertTrue(sessionStart.contains("\"state\" to availabilityState"))
        assertTrue(frameAnalyzed.contains("\"camera_non_metric_frame_analyzed\""))
        assertTrue(frameAnalyzed.contains("cameraFallbackFrameAnalyzedGeneration == fallbackGeneration"))
        assertTrue(frameAnalyzed.contains("isCameraFallbackLeaseCurrent("))
        assertTrue(frameAnalyzed.contains("expectedWalkEpoch"))
        assertTrue(frameAnalyzed.contains("requireRunning = true"))
        assertTrue(frameAnalyzed.contains("lifecycleGeneration != feedbackLifecycleGeneration"))
        assertTrue(frameAnalyzed.contains("\"loaded_model\" to (detectorLoadedModelKey ?: \"none\")"))
        assertTrue(frameAnalyzed.contains("\"model_fallback_used\" to detectorModelFallbackUsed"))
        assertTrue(frameAnalyzed.contains("\"metric\" to false"))
        assertTrue(frameAnalyzed.contains("\"reports_allowed\" to false"))
        assertTrue(frameAnalyzed.contains("\"state\" to \"detector_succeeded\""))
        assertTrue(source.contains("fieldSessionLog.appendCameraNonMetricSample("))
        assertTrue(source.contains("val nowMs = SystemClock.elapsedRealtime()"))
        assertTrue(source.contains("val advisoryGate = currentCameraFallbackAdvisoryGate(nowMs)"))
        assertTrue(source.contains("val tier = resolveAndroidLocalTactileTier(advisoryGate)"))
        assertTrue(
            source.indexOf("val advisoryGate = currentCameraFallbackAdvisoryGate(nowMs)") <
                source.indexOf("val tier = resolveAndroidLocalTactileTier(advisoryGate)"),
        )
        assertTrue(source.contains("elapsedRealtimeMs = nowMs"))
        assertTrue(source.contains("inferenceMs = result.timing.totalMs ?: return"))
        assertTrue(source.contains("detectionCount = result.detections.size"))
        assertTrue(source.contains("capabilityTier = tier.name"))
        assertTrue(source.contains("cameraPermissionGranted = advisoryGate.cameraPermissionGranted"))
        assertTrue(source.contains("cameraFallbackRunning = advisoryGate.cameraFallbackRunning"))
        assertTrue(source.contains("detectorAvailable = advisoryGate.detectorAvailable"))
        assertTrue(source.contains("imuFresh = advisoryGate.imuFresh"))
        assertTrue(source.contains("tmapRouteActive = advisoryGate.tmapRouteActive"))
        assertTrue(advisory.contains("\"camera_non_metric_advisory_emitted\""))
        assertTrue(advisory.contains("\"direction\" to action.direction.name"))
        assertTrue(advisory.contains("\"loaded_model\" to (detectorLoadedModelKey ?: \"none\")"))
        assertTrue(advisory.contains("\"model_fallback_used\" to detectorModelFallbackUsed"))
        assertTrue(advisory.contains("\"metric\" to false"))
        assertTrue(advisory.contains("\"tmap_authoritative\" to true"))
        assertTrue(advisory.contains("\"tmap_route_active\" to currentCameraFallbackAdvisoryGate(nowMs).tmapRouteActive"))
        assertTrue(advisory.contains("\"reports_allowed\" to false"))
    }

    @Test
    fun generalHazardConfidenceDoesNotDependOnDestinationOrRouteBearing() {
        val motionContext = source.substringAfter("private fun buildDepthMotionContext(")
            .substringBefore("internal fun processTactileSnapshotFrame(")

        assertFalse(motionContext.contains("routeBearingAlignmentQuality"))
        assertFalse(motionContext.contains("routeNavigator.currentBearingDeg()"))
        assertTrue(motionContext.contains("return MotionContext("))
        assertTrue(motionContext.contains("freshnessQuality = freshness"))
    }

    @Test
    fun debugIntentOverrideIsConsumedOnlyByTheDebugSourceSetAndReadyGate() {
        val continuation = source.substringAfter("private fun continueDepthSessionStart(")
            .substringBefore("private fun stopDepthSession(")

        assertTrue(debugOverride.contains("kr.co.hanium.dreamup.walksafe.debug.FORCE_CAMERA_NON_METRIC_FALLBACK"))
        assertTrue(debugOverride.contains("getBooleanExtra(EXTRA_FORCE_CAMERA_NON_METRIC_FALLBACK, false)"))
        assertTrue(debugOverride.contains("removeExtra(EXTRA_FORCE_CAMERA_NON_METRIC_FALLBACK)"))
        assertFalse(releaseOverride.contains("FORCE_CAMERA_NON_METRIC_FALLBACK"))
        assertFalse(releaseOverride.contains("getBooleanExtra"))
        assertFalse(releaseOverride.contains("removeExtra"))
        assertTrue(releaseOverride.contains("consumeForceCameraNonMetricFallback(intent: Intent?): Boolean = false"))
        assertTrue(continuation.contains("gate == ArCoreStartGate.READY &&"))
        assertTrue(continuation.contains("CameraFallbackTestOverride.consumeForceCameraNonMetricFallback(intent)"))
        assertTrue(continuation.contains("CameraFallbackStartReason.DEBUG_FORCED_SUPPORTED"))
        assertTrue(source.contains("DEBUG_FORCED_SUPPORTED(\"debug_forced_supported\")"))
    }

    @Test
    fun unknownAvailabilityUsesAsyncRecheckWithLifecycleAndRequestGenerationGuards() {
        val start = source.substringAfter("private fun startDepthSession()")
            .substringBefore("private fun continueDepthSessionStart(")
        val callbackGuard = source.substringAfter("private fun isArCoreAvailabilityCallbackCurrent(")
            .substringBefore("private fun invalidateArCoreAvailabilityRecheck()")
        val pause = source.substringAfter("internal fun pauseWalkSafeRuntime()")
            .substringBefore("private fun invalidateFrameStateForPause()")
        val destroy = source.substringAfter("override fun onDestroy()")
            .substringBefore("override fun onRequestPermissionsResult(")

        assertTrue(start.contains("shouldRecheckArCoreAvailability(availability)"))
        assertTrue(start.contains("requestArCoreAvailabilityRecheck(requestGeneration, lifecycleGeneration)"))
        assertTrue(start.contains("checkAvailabilityAsync(this)"))
        assertTrue(start.contains("isArCoreAvailabilityCallbackCurrent(requestGeneration, lifecycleGeneration)"))
        assertTrue(start.contains("else -> continueDepthSessionStart(availability)"))
        assertFalse(
            start.substringAfter("private fun requestArCoreAvailabilityRecheck(")
                .contains("startDepthSession()"),
        )
        assertTrue(callbackGuard.contains("requestGeneration == arCoreAvailabilityRequestGeneration"))
        assertTrue(callbackGuard.contains("lifecycleGeneration == feedbackLifecycleGeneration"))
        assertTrue(callbackGuard.contains("isWalkSessionRuntimeActive()"))
        assertTrue(callbackGuard.contains("!isFinishing"))
        assertTrue(callbackGuard.contains("!isDestroyed"))
        assertTrue(pause.contains("invalidateArCoreAvailabilityRecheck()"))
        assertTrue(destroy.contains("invalidateArCoreAvailabilityRecheck()"))
    }

    @Test
    fun cameraOwnershipIsInvalidatedBeforeSwitchingToArCore() {
        val arCoreStart = source.substringAfter("private fun startDepthSession()")
            .substringBefore("private fun stopDepthSession(")
        val fallbackStop = source.substringAfter("private fun stopCameraFallbackSession(")
            .substringBefore("private fun analyzeCameraFallbackFrame(")

        assertTrue(arCoreStart.indexOf("stopCameraFallbackSession(updateUi = false)") < arCoreStart.indexOf("requestInstall"))
        assertTrue(arCoreStart.indexOf("stopCameraFallbackSession(updateUi = false)") < arCoreStart.indexOf("Session(this)"))
        assertTrue(fallbackStop.indexOf("cameraFallbackGeneration += 1") < fallbackStop.indexOf("clearAnalyzer()"))
        assertTrue(fallbackStop.indexOf("clearAnalyzer()") < fallbackStop.indexOf("unbind(ownedAnalysis)"))
        assertTrue(fallbackStop.contains("detectorExecutor.execute"))
        assertTrue(fallbackStop.contains("CAMERA_ANALYZER_RELEASE_BARRIER_TIMEOUT_MS"))
        assertTrue(fallbackStop.contains("awaitCameraXClosed(ownedCamera)"))
        assertTrue(fallbackStop.contains("closed && !analyzerBarrierTimedOut.get()"))
        assertTrue(fallbackStop.contains("cameraFallbackAfterRelease = onReleased"))
        assertTrue(fallbackStop.contains("unbind(ownedPreview)"))
        assertFalse(fallbackStop.contains("unbindAll()"))

        val continuation = source.substringAfter("private fun continueDepthSessionStart(")
            .substringBefore("private fun stopDepthSession(")
        assertTrue(continuation.contains("expectedLifecycleGeneration"))
        assertTrue(continuation.contains("walkSessionLifecycle.isRuntimeEpochCurrent(expectedEpoch)"))
        assertTrue(continuation.contains("isWalkSessionRuntimeActive()"))
    }

    @Test
    fun definitiveArCoreIncompatibilityStartsTheCameraFallback() {
        val arCoreStart = source.substringAfter("private fun continueDepthSessionStart(")
            .substringBefore("private fun stopDepthSession(")
        val unsupportedBranch = arCoreStart.substringAfter("ArCoreStartGate.CAMERA_FALLBACK -> {")
            .substringBefore("ArCoreStartGate.RETRY_LATER -> {")
        val incompatibleCatch = arCoreStart.substringAfter("catch (_: UnavailableDeviceNotCompatibleException) {")
            .substringBefore("catch (error: Exception) {")
        assertTrue(arCoreStart.contains("val gate = resolveArCoreStartGate(availability)"))
        assertTrue(unsupportedBranch.contains("arCoreSupported = false"))
        assertTrue(unsupportedBranch.contains("CameraFallbackStartReason.ARCORE_AVAILABILITY_UNSUPPORTED"))
        assertTrue(unsupportedBranch.indexOf("startCameraFallbackSession(") < unsupportedBranch.indexOf("return"))
        assertTrue(incompatibleCatch.contains("arCoreSupported = false"))
        assertTrue(incompatibleCatch.contains("stopDepthSession(closeSession = true)"))
        assertTrue(incompatibleCatch.contains("CameraFallbackStartReason.ARCORE_SESSION_INCOMPATIBLE"))
        assertTrue(arCoreStart.contains("CameraFallbackStartReason.ARCORE_DEPTH_UNSUPPORTED"))
        assertTrue(source.contains("ARCORE_AVAILABILITY_UNSUPPORTED(\"arcore_availability_unsupported\")"))
        assertTrue(source.contains("ARCORE_DEPTH_UNSUPPORTED(\"arcore_depth_unsupported\")"))
        assertTrue(source.contains("ARCORE_SESSION_INCOMPATIBLE(\"arcore_session_incompatible\")"))
    }

    @Test
    fun unavailableDetectorLimitsCameraHazardWithoutStoppingOtherFeatures() {
        val fallbackStart = source.substringAfter("private fun startCameraFallbackSession(")
            .substringBefore("private fun bindCameraFallbackSession()")
        val detectorFailure = source.substringAfter("private fun disableDetectorAfterRuntimeFailure(")
            .substringBefore("private fun clearDetectionStateAfterRuntimeTransition()")

        assertTrue(fallbackStart.contains("if (!detectorAvailable)"))
        assertTrue(fallbackStart.contains("cameraFallbackRequested = false"))
        assertTrue(fallbackStart.contains("continueAfterCameraFallbackFailure("))
        assertTrue(fallbackStart.contains("대체 장애물 탐지기를 사용할 수 없습니다."))
        assertFalse(fallbackStart.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        assertFalse(fallbackStart.contains("TMAP 전용"))
        assertTrue(fallbackStart.indexOf("if (!detectorAvailable)") < fallbackStart.indexOf("cameraFallbackRequested = true"))
        assertTrue(detectorFailure.contains("stopCameraFallbackSession(updateUi = false)"))
        assertTrue(detectorFailure.contains("객체 탐지 중지 · 안전 중지"))
        assertTrue(detectorFailure.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        assertFalse(detectorFailure.contains("TMAP 전용"))
    }

    @Test
    fun talkBackAdvisoryNeverPreemptsRiskOrNavigation() {
        val advisory = source.substringAfter("private fun speakAdvisory(\n")
            .substringBefore("private fun isScreenReaderActive()")
        val talkBack = source.substringAfter("TalkBackAnnouncementPriority.ADVISORY -> {")
            .substringBefore("TalkBackAnnouncementPriority.NAVIGATION -> {")

        assertTrue(advisory.contains("TalkBackAnnouncementPriority.ADVISORY"))
        assertTrue(advisory.contains("isAppSpeechIdleForExternalAdvisory()"))
        assertTrue(advisory.contains("validUntilMs = action.validUntilMs"))
        assertTrue(advisory.contains("isCameraFallbackAdvisoryStillDeliverable"))
        assertTrue(advisory.contains("nonMetricAdvisoryPolicy.isDeliveryCurrent(action)"))
        assertTrue(advisory.contains("onDelivered = onDelivered"))
        assertTrue(advisory.contains("onCompleted = onDelivered"))
        assertTrue(advisory.contains("onFailed = onFailed"))
        assertTrue(talkBack.contains("nowMs < riskAnnouncementHoldUntilMs"))
        assertTrue(talkBack.contains("nowMs < navigationAnnouncementHoldUntilMs"))
        assertTrue(talkBack.contains("TALKBACK_ADVISORY_DUP_WINDOW_MS"))
    }
}

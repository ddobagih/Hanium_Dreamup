package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraXFallbackCompositionStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val cameraSource = MainActivityRefactorSource.camera
    private val outputSource = MainActivityRefactorSource.speech
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
        assertTrue(cameraSource.contains("ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST"))
        assertTrue(cameraSource.contains("setOutputImageRotationEnabled(true)"))
        assertTrue(cameraSource.contains("CameraSelector.DEFAULT_BACK_CAMERA"))
        assertTrue(cameraSource.contains("provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)"))
        assertTrue(source.contains("cameraFallbackController.moveTo(Lifecycle.State.RESUMED)"))
        assertTrue(source.contains("cameraFallbackController.moveTo(Lifecycle.State.CREATED)"))
        assertTrue(source.contains("cameraFallbackController.moveTo(Lifecycle.State.DESTROYED)"))
        val worker = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryCameraFallbackDetectorWorker.kt").readText()
        val analyzer = MainActivityRefactorSource.member(worker, "analyze")
        val invocation = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryDetectorInvocationWorker.kt").readText()
        val mainWorker = MainActivityRefactorSource.member(source, "createPrimaryCameraFallbackDetectorWorker")
        assertTrue(MainActivityRefactorSource.member(source, "analyzeCameraFallbackFrame").contains("primaryCameraFallbackDetectorWorker.analyze(frameCapture, expectedWalkEpoch, expectedFallbackGeneration)"))
        val consume = MainActivityRefactorSource.member(cameraSource, "consumeImage")
        assertTrue(source.contains("onFrame = { capture, lease -> analyzeCameraFallbackFrame(capture, lease.epoch, lease.generation) }"))
        assertTrue(consume.contains("finally"))
        assertTrue(consume.contains("capture.close()"))
        assertTrue(cameraSource.contains("proxy.close()"))
        assertTrue(analyzer.contains("frameCapture.copyOwnedInputAndClose()"))
        assertTrue(invocation.contains("var detectorSucceeded = false"))
        assertTrue(mainWorker.contains("recordCameraFallbackFrameAnalyzedOnce(epoch, fallbackGeneration, deliveryOwner)"))
        assertTrue(mainWorker.contains("deliveryOwner == feedbackLifecycleGeneration"))
        // S14-36: outcomes reach the supervisor only through its ticket-checked entry point.
        assertTrue(analyzer.contains("supervisor.onDetectorOutput("))
        assertTrue(invocation.contains("invocation.detectorSucceeded = true"))
        assertTrue(analyzer.contains("if (observed.detectorSucceeded)"))
        assertTrue(analyzer.contains("recording.analyzed("))
        assertTrue(analyzer.contains("expectedEpoch"))
        assertTrue(analyzer.contains("fallbackGeneration"))
        assertTrue(
            analyzer.indexOf("!authority.completionIsCurrent(expectedEpoch, fallbackGeneration, deliveryOwner, detectorGeneration)") <
                analyzer.indexOf("recording.analyzed("),
        )
    }

    @Test
    fun fallbackBindsPreviewAndImageAnalysisTogether() {
        val fallbackBinding = MainActivityRefactorSource.member(cameraSource, "requestBind", occurrence = 1)
        val mainBinding = MainActivityRefactorSource.member(source, "bindCameraFallbackSession")
        assertTrue(mainBinding.contains("cameraFallbackController.bind(expectedWalkEpoch, ::displayRotation)"))
        assertTrue(cameraSource.contains("val preview = Preview.Builder()"))
        assertTrue(cameraSource.contains("provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)"))
        assertTrue(fallbackBinding.contains("preview.setSurfaceProvider(surfaceProvider())"))
        assertTrue(fallbackBinding.contains("analysis.setAnalyzer(detectorExecutor)"))
    }

    @Test
    fun degradedDetectionsStayOutsideMetricRouteAndReportPipelines() {
        val worker = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryCameraFallbackDetectorWorker.kt").readText()
        val analyzer = MainActivityRefactorSource.member(worker, "analyze")
        assertTrue(MainActivityRefactorSource.member(source, "analyzeCameraFallbackFrame").contains("primaryCameraFallbackDetectorWorker.analyze("))
        val deliverySource = File("src/main/java/kr/co/hanium/dreamup/walksafe/feedback/delivery/CameraNonMetricAdvisoryDeliveryController.kt").readText()
        val emitter = MainActivityRefactorSource.member(deliverySource, "dispatch")
        val deliveryGuard = MainActivityRefactorSource.member(deliverySource, "stillDeliverable")
        val deliveryBinding = MainActivityRefactorSource.property(source, "cameraAdvisoryDelivery")
        val finalGuard = MainActivityRefactorSource.member(source, "isCameraFallbackAdvisoryOwnerCurrent")
        assertTrue(deliveryBinding.contains("CameraNonMetricAdvisoryDeliveryController("))
        assertTrue(deliveryBinding.contains("policy = nonMetricAdvisoryPolicy"))
        assertTrue(deliveryBinding.contains("mountingLock = { phoneMountingObservationLock }"))
        assertTrue(deliveryBinding.contains("isCameraFallbackAdvisoryOwnerCurrent("))
        assertTrue(MainActivityRefactorSource.member(source, "createPrimaryCameraFallbackDetectorWorker").contains(
            "cameraAdvisoryDelivery.dispatch(action, epoch, generation, deliveryOwner)"))
        assertFalse(analyzer.contains("publishDetectionSnapshot"))
        assertFalse(analyzer.contains("prepareTactileFrameDispatch"))
        assertFalse(analyzer.contains("publishCurrentFrameReportState"))
        assertTrue(emitter.contains("val accepted = speech.speak("))
        assertFalse(emitter.contains("speakInteraction(action.message)"))
        assertTrue(
            emitter.indexOf("stillDeliverable") <
                emitter.indexOf("val accepted = speech.speak("),
        )
        assertFalse(emitter.contains("vibrateAdvisory"))
        assertFalse(emitter.contains("vibrationPatternMs"))
        assertTrue(emitter.contains("if (!policy.claimDelivery(action)) return"))
        assertTrue(emitter.contains("policy.rejectDelivery(action)"))
        assertTrue(emitter.contains("policy.isDeliveryCurrent(action)"))
        assertTrue(
            emitter.indexOf("if (!policy.claimDelivery(action)) return") <
                emitter.indexOf("val accepted = speech.speak("),
        )
        val completion = MainActivityRefactorSource.member(source, "recordCameraFallbackAdvisoryDelivery")
        val commit = MainActivityRefactorSource.member(deliverySource, "complete")
        assertTrue(MainActivityRefactorSource.member(source, "completeCameraFallbackAdvisory").contains("cameraAdvisoryDelivery.complete(action, nowMs, ownerCurrent)"))
        assertTrue(emitter.contains("val confirmed = complete(action, nowMs)"))
        assertTrue(emitter.contains("if (confirmed) recordDelivered(action, nowMs)"))
        assertTrue(deliveryBinding.contains("recordDelivered = ::recordCameraFallbackAdvisoryDelivery"))
        assertTrue(commit.contains("policy.confirmDelivery(action, nowMs)"))
        assertTrue(commit.contains("if (!ownerCurrent() || !authority.mountingAllowed())"))
        assertTrue(emitter.indexOf("val confirmed = complete(action, nowMs)") < emitter.indexOf("if (confirmed) recordDelivered(action, nowMs)"))
        assertTrue(completion.contains("\"camera_non_metric_advisory_emitted\""))
        assertFalse(advisoryPolicy.contains("vibrationPatternMs"))
        assertFalse(advisoryPolicy.contains("ADVISORY_VIBRATION_PATTERN_MS"))
        assertTrue(advisoryPolicy.contains("pendingQueueCapacity: Int = 3"))
        assertTrue(deliveryGuard.contains("nowMs <= action.validUntilMs"))
        assertTrue(deliveryGuard.contains("authority.newOutputAllowed(nowMs)"))
        assertTrue(deliveryBinding.contains("!isGuidanceVoiceInputActive() && currentRawCameraFrameAllowsNewOutput(nowMs)"))
        assertTrue(finalGuard.contains("isCameraFallbackLeaseCurrent("))
        assertTrue(finalGuard.contains("expectedWalkEpoch"))
        assertTrue(finalGuard.contains("requireRunning = true"))
        assertTrue(finalGuard.contains("lifecycleGeneration == feedbackLifecycleGeneration"))
        assertTrue(finalGuard.contains("AndroidLocalTactileTier.CAMERA_IMU_NON_METRIC"))
        val lifecycle = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/camera/lifecycle/RuntimeCameraLifecycleCoordinator.kt").readText()
        MainActivityRefactorSource.composition(source, "runtimeCameraLifecycle")
        assertTrue(lifecycle.contains("presentation.reportStatus(\"reportCandidate=blocked:camera_non_metric_tier\")"))
        assertTrue(lifecycle.contains("카메라 보조 경고 · 거리 제한 모드"))
        assertTrue(lifecycle.contains("현재 사용 가능한 위치·경로·걸음 수·음성·진동 기능은 계속"))
        assertFalse(lifecycle.contains("카메라 보조 경고 · TMAP 경로 유지"))
    }

    @Test
    fun fieldEvidenceRecordsTheNonMetricAndReportIsolationContracts() {
        val sessionStart = MainActivityRefactorSource.function(source, "private fun handleCameraFallbackSourceEvent(")
        assertTrue(source.contains("onEvent = ::handleCameraFallbackSourceEvent"))
        assertTrue(MainActivityRefactorSource.member(cameraSource, "bind").contains("onEvent(CameraFallbackEvent.Bound(lease, reason, available))"))
        val frameAnalyzed = MainActivityRefactorSource.member(source, "recordCameraFallbackFrameAnalyzedOnce")
        val advisory = MainActivityRefactorSource.member(source, "recordCameraFallbackAdvisoryDelivery")

        assertTrue(sessionStart.contains("\"camera_non_metric_session_started\""))
        assertTrue(sessionStart.contains("\"loaded_model\" to (admission.loadedModel() ?: \"none\")"))
        assertTrue(sessionStart.contains("\"model_fallback_used\" to admission.detectorFallbackUsed()"))
        assertTrue(sessionStart.contains("\"metric\" to false"))
        assertTrue(sessionStart.contains("\"reports_allowed\" to false"))
        assertTrue(sessionStart.contains("\"reason\" to event.reason.fieldValue"))
        assertTrue(sessionStart.contains("\"state\" to event.availability.name"))
        assertTrue(frameAnalyzed.contains("\"camera_non_metric_frame_analyzed\""))
        assertTrue(frameAnalyzed.contains("cameraFallbackController.state.frameAnalyzedGeneration == fallbackGeneration"))
        assertTrue(frameAnalyzed.contains("isCameraFallbackLeaseCurrent("))
        assertTrue(frameAnalyzed.contains("expectedWalkEpoch"))
        assertTrue(frameAnalyzed.contains("requireRunning = true"))
        assertTrue(frameAnalyzed.contains("lifecycleGeneration != feedbackLifecycleGeneration"))
        assertTrue(frameAnalyzed.contains("\"loaded_model\" to (primaryDetectorLifecycleController.metadata().loadedModelKey ?: \"none\")"))
        assertTrue(frameAnalyzed.contains("\"model_fallback_used\" to primaryDetectorLifecycleController.metadata().fallbackUsed"))
        assertTrue(frameAnalyzed.contains("\"metric\" to false"))
        assertTrue(frameAnalyzed.contains("\"reports_allowed\" to false"))
        assertTrue(frameAnalyzed.contains("\"state\" to \"detector_succeeded\""))
        val sample = MainActivityRefactorSource.member(source, "recordCameraFallbackInferenceSample")
        val workerSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryCameraFallbackDetectorWorker.kt").readText()
        val worker = MainActivityRefactorSource.member(workerSource, "analyze")
        val workerBinding = MainActivityRefactorSource.member(source, "createPrimaryCameraFallbackDetectorWorker")
        assertTrue(sample.contains("fieldSessionLog.appendCameraNonMetricSample("))
        assertTrue(worker.contains("val now = elapsedRealtimeMs()"))
        assertTrue(workerBinding.contains("SystemClock::elapsedRealtime"))
        assertTrue(worker.contains("val gate = authority.advisoryGate(elapsedRealtimeMs())"))
        assertTrue(worker.contains("val tier = authority.tactileTier(gate)"))
        assertTrue(worker.indexOf("val gate = authority.advisoryGate(elapsedRealtimeMs())") < worker.indexOf("val tier = authority.tactileTier(gate)"))
        assertTrue(workerBinding.contains("currentCameraFallbackAdvisoryGate(nowElapsedRealtimeMs)"))
        assertTrue(workerBinding.contains("resolveAndroidLocalTactileTier(gate)"))
        assertTrue(worker.contains("recording.sample(observed.result, tier, gate, now)"))
        assertTrue(workerBinding.contains("recordCameraFallbackInferenceSample(result, tier, gate, observedAtElapsedRealtimeMs)"))
        listOf("elapsedRealtimeMs = nowMs", "inferenceMs = result.timing.totalMs ?: return",
            "detectionCount = result.detections.size", "capabilityTier = tier.name",
            "cameraPermissionGranted = advisoryGate.cameraPermissionGranted", "cameraFallbackRunning = advisoryGate.cameraFallbackRunning",
            "detectorAvailable = advisoryGate.detectorAvailable", "imuFresh = advisoryGate.imuFresh",
            "tmapRouteActive = advisoryGate.tmapRouteActive").forEach { assertTrue(it, sample.contains(it)) }
        assertTrue(advisory.contains("\"camera_non_metric_advisory_emitted\""))
        assertTrue(advisory.contains("\"direction\" to action.direction.name"))
        assertTrue(advisory.contains("\"loaded_model\" to (primaryDetectorLifecycleController.metadata().loadedModelKey ?: \"none\")"))
        assertTrue(advisory.contains("\"model_fallback_used\" to primaryDetectorLifecycleController.metadata().fallbackUsed"))
        assertTrue(advisory.contains("\"metric\" to false"))
        assertTrue(advisory.contains("\"tmap_authoritative\" to true"))
        assertTrue(advisory.contains("\"tmap_route_active\" to currentCameraFallbackAdvisoryGate(nowMs).tmapRouteActive"))
        assertTrue(advisory.contains("\"reports_allowed\" to false"))
    }

    @Test
    fun generalHazardConfidenceDoesNotDependOnDestinationOrRouteBearing() {
        val motionOwner = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/capture/FrameMotionEvidenceComposer.kt").readText()
        val motionContext = MainActivityRefactorSource.member(motionOwner, "buildDepthMotionContext")
        assertTrue(MainActivityRefactorSource.property(source, "frameMotionEvidence").contains("FrameMotionEvidenceComposer({ positioningSessionController }, { routeNavigator },"))
        assertTrue(MainActivityRefactorSource.property(source, "frameRouteEvidence").contains("override fun motionContext(capturedElapsedMs: Long) = this@MainActivity.frameMotionEvidence.buildDepthMotionContext(capturedElapsedMs)"))

        assertFalse(motionContext.contains("routeBearingAlignmentQuality"))
        assertFalse(motionContext.contains("routeNavigator.currentBearingDeg()"))
        assertFalse(Regex("""\bnavigator\s*\(\s*\)\s*\.\s*currentBearingDeg\s*\(""").containsMatchIn(motionContext))
        assertTrue(motionContext.contains("return MotionContext("))
        assertTrue(motionContext.contains("freshnessQuality = freshness"))
    }

    @Test
    fun debugIntentOverrideIsConsumedOnlyByTheDebugSourceSetAndReadyGate() {
        val continuation = MainActivityRefactorSource.function(source, "private fun continueDepthSessionStart(")

        assertTrue(debugOverride.contains("kr.co.hanium.dreamup.walksafe.debug.FORCE_CAMERA_NON_METRIC_FALLBACK"))
        assertTrue(debugOverride.contains("getBooleanExtra(EXTRA_FORCE_CAMERA_NON_METRIC_FALLBACK, false)"))
        assertTrue(debugOverride.contains("removeExtra(EXTRA_FORCE_CAMERA_NON_METRIC_FALLBACK)"))
        assertFalse(releaseOverride.contains("FORCE_CAMERA_NON_METRIC_FALLBACK"))
        assertFalse(releaseOverride.contains("getBooleanExtra"))
        assertFalse(releaseOverride.contains("removeExtra"))
        assertTrue(releaseOverride.contains("consumeForceCameraNonMetricFallback(intent: Intent?): Boolean = false"))
        assertTrue(continuation.contains("gate == ArCoreStartGate.READY &&"))
        assertTrue(continuation.contains("platform.consumeForcedCameraFallback()"))
        val platform = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/camera/lifecycle/AndroidRuntimeArPlatform.kt").readText()
        assertTrue(MainActivityRefactorSource.member(platform, "consumeForcedCameraFallback").contains("CameraFallbackTestOverride.consumeForceCameraNonMetricFallback(activity().intent)"))
        assertTrue(continuation.contains("CameraFallbackStartReason.DEBUG_FORCED_SUPPORTED"))
        assertTrue(cameraSource.contains("DEBUG_FORCED_SUPPORTED(\"debug_forced_supported\")"))
    }

    @Test
    fun unknownAvailabilityUsesAsyncRecheckWithLifecycleAndRequestGenerationGuards() {
        val start = MainActivityRefactorSource.function(source, "private fun startDepthSession()")
        val recheck = MainActivityRefactorSource.function(source, "private fun requestArCoreAvailabilityRecheck(")
        val platform = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/camera/lifecycle/AndroidRuntimeArPlatform.kt").readText()
        val callbackGuard = MainActivityRefactorSource.function(source, "private fun isArCoreAvailabilityCallbackCurrent(")
        val pause = source.substringAfter("internal fun pauseWalkSafeRuntime()")
            .substringBefore("private fun invalidateFrameStateForPause()")
        val destroy = source.substringAfter("override fun onDestroy()")
            .substringBefore("override fun onRequestPermissionsResult(")

        assertTrue(start.contains("shouldRecheckArCoreAvailability(availability)"))
        assertTrue(start.contains("requestArCoreAvailabilityRecheck(requestGeneration, lifecycleGeneration)"))
        assertTrue(recheck.contains("platform.availabilityAsync { availability ->"))
        assertTrue(MainActivityRefactorSource.member(platform, "availabilityAsync").contains("checkAvailabilityAsync(activity())"))
        assertTrue(recheck.contains("isArCoreAvailabilityCallbackCurrent(requestGeneration, lifecycleGeneration)"))
        assertTrue(recheck.contains("else -> continueDepthSessionStart(availability)"))
        assertFalse(
            recheck
                .contains("startDepthSession()"),
        )
        assertTrue(callbackGuard.contains("requestGeneration == arCoreAvailabilityRequestGeneration"))
        assertTrue(callbackGuard.contains("lifecycleGeneration == admission.feedbackGeneration()"))
        assertTrue(callbackGuard.contains("admission.runtimeActive()"))
        assertTrue(callbackGuard.contains("!admission.finishing"))
        assertTrue(callbackGuard.contains("!admission.destroyed"))
        assertTrue(pause.contains("invalidateArCoreAvailabilityRecheck()"))
        assertTrue(destroy.contains("invalidateArCoreAvailabilityRecheck()"))
    }

    @Test
    fun cameraOwnershipIsInvalidatedBeforeSwitchingToArCore() {
        val arCoreStart = MainActivityRefactorSource.function(source, "private fun continueDepthSessionStart(")
        val transactionSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/camera/lifecycle/RuntimeArLifecycleTransactions.kt").readText()
        val nativeStart = MainActivityRefactorSource.member(transactionSource, "start")
        val platform = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/camera/lifecycle/AndroidRuntimeArPlatform.kt").readText()
        val fallbackStop = MainActivityRefactorSource.member(cameraSource, "stop")
        assertTrue(MainActivityRefactorSource.member(source, "stopCameraFallbackSession").contains("cameraFallbackController.stop(stoppedEpoch, updateUi, onReleased, onReleaseFailed)"))

        assertTrue(arCoreStart.indexOf("renderer.stopFallback(") in 0 until arCoreStart.indexOf("transactions.start("))
        assertTrue(nativeStart.indexOf("platform.requestInstall(") < nativeStart.indexOf("platform.createSession()"))
        assertTrue(MainActivityRefactorSource.member(platform, "createSession").contains("Session(activity())"))
        assertTrue(MainActivityRefactorSource.member(platform, "requestInstall").contains("requestInstall(activity(), userRequested)"))
        assertTrue(fallbackStop.indexOf("generation += 1") < fallbackStop.indexOf("clearAnalyzer()"))
        assertTrue(fallbackStop.indexOf("clearAnalyzer()") < fallbackStop.indexOf("owned.unbindOwned()"))
        assertTrue(fallbackStop.contains("detectorExecutor.execute"))
        assertTrue(fallbackStop.contains("ANALYZER_RELEASE_BARRIER_TIMEOUT_MS"))
        assertTrue(fallbackStop.contains("owned.awaitClosed { closed ->"))
        assertTrue(fallbackStop.contains("finishRelease(attempt, closed && !attempt.barrierTimedOut && !attempt.preparationFailed)"))
        assertTrue(fallbackStop.contains("OwnedCameraReleaseAttempt(owned, stoppedEpoch, onReleased, onReleaseFailed)"))
        val finishRelease = MainActivityRefactorSource.member(cameraSource, "finishRelease")
        assertTrue(finishRelease.contains("releaseAttempt !== attempt || !attempt.terminal.compareAndSet(false, true)"))
        assertTrue(finishRelease.indexOf("if (released) releaseAttempt = null else releaseBlocked = true") in
            0 until finishRelease.indexOf("if (released) completed?.invoke()"))
        assertTrue(cameraSource.contains("provider.unbind(analysis)"))
        assertTrue(cameraSource.contains("provider.unbind(it)"))
        assertFalse(cameraSource.contains("unbindAll()"))

        val continuation = arCoreStart
        assertTrue(continuation.contains("expectedLifecycleGeneration"))
        assertTrue(continuation.contains("walk().isRuntimeEpochCurrent(expectedEpoch)"))
        assertTrue(continuation.contains("admission.runtimeActive()"))
    }

    @Test
    fun definitiveArCoreIncompatibilityStartsTheCameraFallback() {
        val arCoreStart = MainActivityRefactorSource.function(source, "private fun continueDepthSessionStart(")
        val transactionSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/camera/lifecycle/RuntimeArLifecycleTransactions.kt").readText()
        val nativeStart = MainActivityRefactorSource.member(transactionSource, "start")
        val unsupportedBranch = arCoreStart.substringAfter("ArCoreStartGate.CAMERA_FALLBACK -> {")
            .substringBefore("ArCoreStartGate.RETRY_LATER -> {")
        val incompatibleCatch = nativeStart.substringAfter("catch (_: UnavailableDeviceNotCompatibleException) {")
            .substringBefore("catch (error: Exception) {")
        assertTrue(arCoreStart.contains("val gate = resolveArCoreStartGate(availability)"))
        assertTrue(unsupportedBranch.contains("admission.arCoreSupported = false"))
        assertTrue(unsupportedBranch.contains("CameraFallbackStartReason.ARCORE_AVAILABILITY_UNSUPPORTED"))
        assertTrue(unsupportedBranch.indexOf("requestCameraFallbackStart(") < unsupportedBranch.indexOf("return"))
        assertTrue(incompatibleCatch.contains("admission.arCoreSupported = false"))
        assertTrue(incompatibleCatch.contains("stopIncompatible()"))
        assertTrue(arCoreStart.contains("{ transactions.stopDepthSession(closeSession = true) }"))
        assertTrue(incompatibleCatch.contains("CameraFallbackStartReason.ARCORE_SESSION_INCOMPATIBLE"))
        assertTrue(nativeStart.contains("CameraFallbackStartReason.ARCORE_DEPTH_UNSUPPORTED"))
        assertTrue(cameraSource.contains("ARCORE_AVAILABILITY_UNSUPPORTED(\"arcore_availability_unsupported\")"))
        assertTrue(cameraSource.contains("ARCORE_DEPTH_UNSUPPORTED(\"arcore_depth_unsupported\")"))
        assertTrue(cameraSource.contains("ARCORE_SESSION_INCOMPATIBLE(\"arcore_session_incompatible\")"))
    }

    @Test
    fun unavailableDetectorLimitsCameraHazardWithoutStoppingOtherFeatures() {
        val fallbackStart = MainActivityRefactorSource.function(source, "private fun startCameraFallbackSession(")
        val detectorSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryDetectorLifecycleController.kt").readText()
        val detectorFailure = MainActivityRefactorSource.member(detectorSource, "disableAfterFailure")
        val failureBindings = MainActivityRefactorSource.composition(source, "primaryDetectorLifecycleController")

        assertTrue(fallbackStart.contains("if (!admission.detectorAvailable())"))
        assertTrue(fallbackStart.contains("fallback().rejectRequest()"))
        assertTrue(fallbackStart.contains("continueAfterCameraFallbackFailure("))
        assertTrue(fallbackStart.contains("대체 장애물 탐지기를 사용할 수 없습니다."))
        assertFalse(fallbackStart.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        assertFalse(fallbackStart.contains("TMAP 전용"))
        assertTrue(fallbackStart.indexOf("if (!admission.detectorAvailable())") < fallbackStart.indexOf("fallback().beginRequestedSession()"))
        assertTrue(detectorFailure.contains("failureEffects.stopCameraFallback()"))
        assertTrue(failureBindings.contains("객체 탐지 중지 · 안전 중지"))
        assertTrue(detectorFailure.contains("failureEffects.enterSafetyStop()"))
        assertFalse(detectorFailure.contains("TMAP 전용"))
        assertTrue(failureBindings.contains("override fun stopCameraFallback() = stopCameraFallbackSession(updateUi = false)"))
        assertTrue(failureBindings.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
    }

    @Test
    fun appTtsAdvisoryPreservesDeadlinesAndNeverPreemptsRiskOrNavigation() {
        val deliverySource = File("src/main/java/kr/co/hanium/dreamup/walksafe/feedback/delivery/CameraNonMetricAdvisoryDeliveryController.kt").readText()
        val producer = ReportStaticSourceInspector.maskNonCode(MainActivityRefactorSource.member(deliverySource, "dispatch"))
        val deliveryBinding = ReportStaticSourceInspector.maskNonCode(MainActivityRefactorSource.property(source, "cameraAdvisoryDelivery"))
        assertTrue(deliveryBinding.contains("CameraNonMetricAdvisoryDeliveryController("))
        assertTrue(deliveryBinding.contains("speech = CameraAdvisorySpeech { message, deadline, current, delivered, failed ->"))
        assertTrue(deliveryBinding.contains("speechOutputController.speakAdvisory(message, deadline, current, delivered, failed)"))
        assertTrue(deliveryBinding.contains("policy = nonMetricAdvisoryPolicy"))
        assertTrue(deliveryBinding.contains("isCameraFallbackAdvisoryOwnerCurrent("))
        val advisory = ReportStaticSourceInspector.maskNonCode(MainActivityRefactorSource.member(outputSource, "speakAdvisory"))
        val actuator = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/feedback/AndroidFeedbackActuator.kt",
        ).readText()
        val dispatch = ReportStaticSourceInspector.maskNonCode(ReportStaticSourceInspector.functionBlock(actuator, "private fun speakReady"))
        val startValidation = ReportStaticSourceInspector.maskNonCode(ReportStaticSourceInspector.functionBlock(
            actuator,
            "private fun isUtteranceStillValidAtStart",
        ))

        assertFalse(advisory.contains("TalkBackAnnouncementPriority.ADVISORY"))
        assertFalse(advisory.contains("isScreenReaderActive()"))
        assertTrue(advisory.contains("ensureFeedbackActuator().speakAdvisory("))
        assertTrue(advisory.contains("isFeedbackLifecycleCurrent(generation)"))
        assertTrue(advisory.contains("reader.guidanceVoiceInputActive()"))
        assertTrue(producer.contains("action.message, action.validUntilMs"))
        assertTrue(producer.contains("stillDeliverable"))
        assertTrue(producer.contains("policy.isDeliveryCurrent(action)"))
        val advisoryCode = ReportStaticSourceInspector.maskNonCode(advisory).filterNot(Char::isWhitespace)
        assertTrue(advisoryCode.contains(
            "if(!isFeedbackLifecycleCurrent(generation)||!isStillDeliverable()||" +
                "reader.guidanceVoiceInputActive()){onFailed()returnfalse}",
        ))
        assertTrue(advisoryCode.contains(
            "valterminal=AtomicBoolean(false)" +
                "valdelivered={if(terminal.compareAndSet(false,true))onDelivered()}" +
                "valfailed={if(terminal.compareAndSet(false,true))onFailed()}",
        ))
        assertTrue(advisoryCode.contains(
            "valaccepted=ensureFeedbackActuator().speakAdvisory(" +
                "message=message,validUntilMs=validUntilMs,isStillValid=isStillDeliverable," +
                "onCompleted=delivered,onFailed=failed,)" +
                "if(!accepted)failed()returnaccepted",
        ))
        assertTrue(dispatch.contains("isAdvisory && synchronized(pendingUtterances) { pendingUtterances.isNotEmpty() }"))
        assertTrue(dispatch.contains("SpeechPriority.ADVISORY -> TextToSpeech.QUEUE_ADD"))
        assertTrue(dispatch.contains("return NavigationSpeechDispatchResult.SUPPRESSED"))
        assertTrue(startValidation.contains("speechStartIsCurrent("))
        assertTrue(startValidation.contains("deadlineAndValidator.first, deadlineAndValidator.second"))
        assertTrue(ReportStaticSourceInspector.maskNonCode(actuator).contains("nowMs <= deadlineMs && runCatching(validator).getOrDefault(false)"))
        assertTrue(startValidation.contains("markUtteranceFinished(utteranceId, completed = false, cancelled = true)"))
    }
}

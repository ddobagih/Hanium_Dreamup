package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityDeviceCheckFeatureIsolationStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun persistedHardwareLimitsAndCurrentPermissionLimitsAreCombinedWithoutOverlimitingLocation() {
        val observation = functionBlock("private fun currentPostLoginDeviceCheckObservation()")
        val current = functionBlock("private fun currentPostLoginDisabledFeatures()")
        val labels = functionBlock("private fun postLoginDeviceCheckDisabledFeatureText()")
        val apply = functionBlock("private fun applyPostLoginDeviceFeatureRestrictions(")

        val wire = MainActivityRefactorSource.composition(source, "postLoginDeviceInteraction")
        assertTrue(wire.contains("override val snapshot get() = postLoginDeviceCheckSnapshot"))
        assertTrue(wire.contains("override val cameraChecksDeferred get() = postLoginCameraDependentChecksDeferred"))
        assertTrue(wire.contains("override val obstacleOverride get() = runtimeObstacleDetectionCapabilityOverride"))
        assertTrue(wire.contains("override val metricOverride get() = metricDistanceCapabilityOverride"))
        assertTrue(wire.contains("override fun cameraPermission() = hasCameraPermission()"))
        assertTrue(wire.contains("override fun locationPermission() = hasLocationPermission()"))
        assertTrue(wire.contains("override fun locationServiceEnabled() = isLocationServiceEnabledForDeviceCheck()"))
        assertTrue(wire.contains("override fun disclosureAccepted() = isHandsFreeVoiceDisclosureAccepted()"))
        assertTrue(wire.contains("override fun activityRecognitionPermission() = hasActivityRecognitionPermission()"))
        assertTrue(observation.contains("!hardware.cameraPermission"))
        val unsupported = observation
            .substringAfter("val unsupported = buildSet {")
            .substringBefore("return PostLoginDeviceCheckObservation(")
        assertFalse(unsupported.contains("cameraPipeline == PostLoginDeviceCheckSignal.UNAVAILABLE"))
        assertTrue(current.contains("addAll(facts.snapshot.disabledFeatures)"))
        assertTrue(current.contains("!facts.cameraPermission()"))
        assertTrue(current.contains("PostLoginDeviceCheckFeature.OBSTACLE_DETECTION"))
        assertTrue(current.contains("PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE"))
        assertTrue(current.contains("facts.obstacleOverride == false"))
        assertTrue(current.contains("facts.metricOverride == false"))
        assertTrue(current.contains("!facts.locationPermission()"))
        assertTrue(current.contains("!facts.locationServiceEnabled()"))
        assertTrue(current.contains("!facts.disclosureAccepted()"))
        assertFalse(current.contains("!facts.activityRecognitionPermission()"))
        assertTrue(labels.contains("activityRecognitionPermission = !passes || facts.activityRecognitionPermission()"))
        assertTrue(presenter("postLoginDeviceCheckDisabledFeatureText").contains("!facts.activityRecognitionPermission"))
        assertTrue(presenter("postLoginDeviceCheckDisabledFeatureText").contains("걸음 수 추적"))
        assertTrue(apply.contains("evaluation.disabledFeatures().forEach"))
    }

    @Test
    fun officialEnvironmentRunsOnlyTheSensorsNeededByEnabledFeatures() {
        val confirm = functionBlock("private fun confirmOfficialEnvironmentConditions()")
        val gps = functionBlock("private fun requestOfficialEnvironmentGpsPreflight(")
        val assessment = owner("device/environment/OfficialEnvironmentEvidenceStore.kt", "currentAssessment")
        assertTrue(MainActivityRefactorSource.member(source, "currentOfficialEnvironmentAssessment").contains("officialEnvironmentEvidenceStore.currentAssessment(epoch, nowElapsedRealtimeMs, officialEnvironmentAssessmentFacts"))
        val mounting = functionBlock("private fun phoneMountingReadiness(")
        val runtimeMounting = functionBlock("private fun activatePhoneMountingRuntime()")
        val outputGate = functionBlock("private fun walkSafetyOutputsAllowed()")
            .substringBefore("\n    private fun ")
        val navigationGate = functionBlock("private fun navigationEnvironmentOutputsAllowed()")
            .substringBefore("\n    private fun ")
        val cameraGate = functionBlock("private fun cameraEnvironmentOutputsAllowed()")
            .substringBefore("\n    private fun ")
        val watchdog = functionBlock("private fun scheduleOfficialEnvironmentRuntimeWatchdog(")

        assertTrue(confirm.contains("val requireLocation = facts.locationEnabled()"))
        assertTrue(confirm.contains("val requireCamera = facts.cameraEnabled()"))
        assertTrue(confirm.contains("else if (requireCamera)"))
        assertTrue(confirm.contains("else if (requireLocation)"))
        assertTrue(gps.contains("val cameraRequired = facts.cameraEnabled()"))
        assertTrue(gps.contains("cameraRequired && !cameraActive"))
        assertTrue(assessment.contains("OfficialEnvironmentFactor.GPS_QUALITY"))
        assertTrue(assessment.contains("OfficialEnvironmentFactor.CAMERA_QUALITY"))
        assertTrue(assessment.contains("val enabled = buildSet"))
        assertTrue(assessment.contains("if (facts.locationEnabled())"))
        assertTrue(assessment.contains("if (facts.cameraEnabled())"))
        assertTrue(assessment.contains("enabledMeasuredFactors = enabled,"))
        assertTrue(assessment.contains("runtimeUnavailableMeasuredFactors = runtimeUnavailable,"))
        assertTrue(assessment.contains("recoverableRuntimeUnavailableMeasuredFactors = recoverableUnavailable"))
        assertFalse(assessment.contains("status = EnvironmentEvidenceStatus.PASS"))
        assertTrue(mounting.contains("if (!cameraAnalysisFeaturesEnabled())"))
        assertTrue(mounting.contains("WalkSessionReadinessStatus.READY to \"\""))
        assertTrue(runtimeMounting.contains("phoneMountingOutputsAllowed = true"))
        assertTrue(outputGate.contains("navigationEnvironmentOutputsAllowed() && cameraEnvironmentOutputsAllowed()"))
        assertTrue(navigationGate.contains("postLoginDeviceFeatureEnabled(PostLoginDeviceCheckFeature.LOCATION_GUIDANCE)"))
        assertTrue(navigationGate.contains("navigationOutputsAllowed == true"))
        assertFalse(navigationGate.contains("phoneMountingOutputsAllowed"))
        assertTrue(cameraGate.contains("cameraAnalysisFeaturesEnabled()"))
        assertTrue(cameraGate.contains("hasCameraPermission()"))
        assertTrue(cameraGate.contains("cameraOutputsAllowed == true"))
        assertTrue(cameraGate.contains("phoneMountingOutputsAllowed"))
        assertFalse(cameraGate.contains("!cameraAnalysisFeaturesEnabled()"))
        assertTrue(watchdog.contains("if (!locationRequired && !cameraRequired) return"))
    }

    @Test
    fun disabledCameraAnalysisSkipsCameraRuntimeAndStartsRemainingFeatures() {
        val activation = functionBlock("private fun activateWalkSessionRuntime()")
        val afterRelease = functionBlock("private fun startWalkSessionRuntimeAfterCameraRelease(")
        val withoutCamera = functionBlock("private fun startWalkSessionRuntimeWithoutCamera()")
        val permissions = functionBlock("private fun ensurePermissionsThenStart()")
        val confirmed = functionBlock("private fun startConfirmedRuntimeAfterCameraPermission()")
        val stepTracking = functionBlock(
            "private fun currentStepTrackingCollectionAllowsWork()",
        )

        assertTrue(activation.contains("if (camera.analysisFeaturesEnabled())"))
        assertTrue(afterRelease.contains("if (!admission.cameraEnabled())"))
        assertTrue(afterRelease.contains("startWalkSessionRuntimeWithoutCamera()"))
        assertTrue(withoutCamera.contains("effects.startNavigation()"))
        assertTrue(withoutCamera.contains("effects.startHandsFreeVoice()"))
        assertTrue(permissions.contains("if (!cameraAnalysisFeaturesEnabled())"))
        assertTrue(confirmed.contains("if (!cameraAnalysisFeaturesEnabled())"))
        assertTrue(stepTracking.contains("if (!firstRunOnboardingComplete()) return false"))
        assertTrue(stepTracking.contains("if (!isWalkSessionRuntimeActive()) return false"))
        assertTrue(stepTracking.contains("if (!isStartupCapabilityConfirmed()) return false"))
        assertTrue(stepTracking.contains("if (!postLoginDeviceFeatureEnabled(PostLoginDeviceCheckFeature.LOCATION_GUIDANCE)) return false"))
        assertTrue(stepTracking.contains("if (!hasLocationPermission()) return false"))
        assertFalse(stepTracking.contains("navigationEnvironmentOutputsAllowed()"))
        val navigationOutput = functionBlock("private fun currentNavigationCollectionAllowsWork()")
        assertTrue(navigationOutput.contains("navigationEnvironmentOutputsAllowed()"))
        assertTrue(navigationOutput.contains("currentStepTrackingCollectionAllowsWork()"))
        assertFalse(stepTracking.contains("phoneMountingOutputsAllowed"))
        assertFalse(stepTracking.contains("cameraEnvironmentOutputsAllowed()"))
    }

    /**
     * Rewritten for S03-6/S03-7 (w20 review "tests"): the name is kept so the baseline entry reads as
     * fixed. Dependent resources still stop first and recovery from settings is unchanged, but a lost
     * camera or location no longer continues a camera-less walk: the capability refresh (which runs
     * applyRuntimeReadinessIfActive and safety-stops an ACTIVE walk) comes before any continuation or
     * "나머지 기능은 계속" notice, and both are skipped once the walk has stopped.
     */
    @Test
    fun permissionLossStopsOnlyDependentResourcesAndCanBeRecoveredFromSettings() {
        val observed = functionBlock("private fun applyObservedPermissionStateChange(")
        val locationRevocation = observed.substringAfter(
            "if (!platform.preciseLocationGranted() || !readiness.locationServiceEnabled())",
        ).substringBefore("if (!platform.notificationGranted())")
        val settingsUi = presenter("startupPresentation")
        val focus = functionBlock("override fun onWindowFocusChanged(hasFocus: Boolean)")
        val refresh = functionBlock("private fun refreshStartupCapabilityUi()")

        // Dependent resources stop at once.
        assertTrue(observed.contains("runtime.stopDepth()"))
        assertTrue(observed.contains("runtime.stopCameraFallback()"))
        assertTrue(observed.contains("runtime.stopLocation()"))
        assertFalse(locationRevocation.contains("runtime.stopSteps()"))
        assertTrue(observed.contains("runtime.stopHandsFree()"))
        // Losing both core permissions stops the whole walk behind the recovery barrier.
        val wholeStop = observed.indexOf("runtime.safetyStop(\"core_guidance_permissions_unavailable\")")
        assertTrue(wholeStop >= 0)
        assertTrue(wholeStop < observed.indexOf("enterPermissionRecoveryBarrier("))
        // S03-6: the refresh (readiness re-evaluation) runs before any notice or continuation.
        assertTrue(refresh.contains("applyRuntimeReadinessIfActive(decision)"))
        val refreshAt = observed.indexOf("if (readiness.startupProbeReady()) runtime.refreshStartup()")
        assertTrue(refreshAt > wholeStop)
        assertTrue(refreshAt < observed.indexOf("\"일부 기능 제한\""))
        assertTrue(refreshAt < observed.indexOf("runtime.startWithoutCamera()"))
        assertTrue(refreshAt < observed.indexOf("runtime.activateMounting()"))
        assertTrue(Regex("runtime\\.refreshStartup\\(\\)").findAll(observed).count() == 1)
        // The "나머지 기능은 계속" notice only while the walk is still running after the refresh.
        val notice = observed.substringBefore("\"일부 기능 제한\"")
        assertTrue(notice.contains("val stateAfterRefresh = authority.lifecycleOrNull()!!.snapshot().state"))
        assertTrue(notice.contains("stateAfterRefresh in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED)"))
        // The camera-less continuation only for a walk that is still ACTIVE after the refresh.
        val continuation = observed.substringAfter("// The refresh above may have safety-stopped the walk")
        assertTrue(
            continuation.indexOf("if (authority.lifecycleOrNull()!!.snapshot().state == WalkSessionState.ACTIVE) {") in
                0 until continuation.indexOf("runtime.startWithoutCamera()"),
        )
        assertTrue(
            continuation.indexOf("runtime.activateMounting()") <
                continuation.indexOf("runtime.startWithoutCamera()"),
        )
        // Recovery from settings is unchanged.
        assertTrue(settingsUi.contains("transientRestriction"))
        assertTrue(focus.contains("postLoginDeviceCheckSnapshot.passesFeatureGate"))
        assertTrue(focus.contains("refreshStartupCapabilityUi()"))
    }

    @Test
    fun deferredCameraMeasurementsStayRestrictedAndBecomeExplicitlyRetryable() {
        val bind = functionBlock("private fun bindPostLoginDeviceCheckSession(")
        val complete = functionBlock("private fun completePostLoginDeviceCheckPass(")
        val current = functionBlock("private fun currentPostLoginDisabledFeatures()")
        val retryable = functionBlock("private fun deferredCameraDependentChecksCanBeRetried()")
        val primaryAction = functionBlock(
            "private fun handlePostLoginDeviceCheckPrimaryAction()",
        )
        val refresh = functionBlock("private fun refreshStartupCapabilityUi()")
        val newWalk = functionBlock("private fun resetWalkTransientStateForNewWalk()")

        assertTrue(bind.contains("val bound = PostLoginDeviceCheckPolicy.bindSession(previous, actorId, sessionGeneration)"))
        assertTrue(bind.contains("if (bound === previous && bound.passesFeatureGate) revalidateCompletedPrerequisites()"))
        assertTrue(bind.contains("val next = if (bound === previous) current else bound"))
        assertTrue(bind.contains("restored.cameraDependentChecksDeferred"))
        assertTrue(bind.contains("else if (next != previous)"))
        assertTrue(bind.contains("cameraDependentChecksDeferred = false"))
        assertFalse(bind.contains("restored?.cameraDependentChecksDeferred ?: false"))
        assertTrue(bind.contains("if (restored.cameraDependentChecksDeferred)"))
        assertTrue(bind.contains("metricDistance = if (restored.cameraDependentChecksDeferred) false"))
        assertTrue(bind.contains("obstacleDetection = if (restored.cameraDependentChecksDeferred) false"))
        assertTrue(
            complete.contains(
                "probes.signals.cameraPipeline != PostLoginDeviceCheckSignal.READY",
            ),
        )
        assertTrue(
            complete.contains(
                "PostLoginDeviceCheckFeature.OBSTACLE_DETECTION !in",
            ),
        )
        assertTrue(
            complete.indexOf("savedResults.save(") <
                complete.indexOf(
                    "cameraDependentChecksDeferred = deferred",
                ),
        )
        assertTrue(current.contains("if (facts.cameraChecksDeferred)"))
        assertTrue(current.contains("PostLoginDeviceCheckFeature.OBSTACLE_DETECTION"))
        assertTrue(current.contains("PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE"))
        assertTrue(retryable.contains("facts.snapshot.passesFeatureGate"))
        assertTrue(retryable.contains("facts.cameraChecksDeferred"))
        assertTrue(retryable.contains("facts.cameraPermission()"))
        assertTrue(primaryAction.contains("if (!deferredChecksCanBeRetried) return"))
        assertTrue(primaryAction.contains("state = PostLoginDeviceCheckState.NOT_RUN"))
        assertTrue(primaryAction.contains("beginFromPrimaryAction()"))
        assertTrue(MainActivityRefactorSource.composition(source, "startupCapabilityFactsRenderer").contains("deferredCameraDependentChecksCanBeRetried()"))
        assertTrue(presenter("startupPresentation").contains("카메라·거리 기능 다시 점검"))
        assertTrue(newWalk.contains("if (postLoginCameraDependentChecksDeferred)"))
        assertTrue(newWalk.contains("runtimeMetricSession.restoreCapability(if (postLoginCameraDependentChecksDeferred)"))
        assertTrue(newWalk.contains("runtimeMetricSession.retainDeviceDepthApiCapability("))
        assertTrue(newWalk.contains("\"new_walk\", postLoginDeviceCheckSnapshot.bindingOrNull, metricDistanceCapabilityOverride"))
        assertFalse(newWalk.contains("postLoginDeviceCheckSnapshot.passesFeatureGate"))
    }

    @Test
    fun restoredTerminalCameraStatusMatchesTheEffectiveObstacleFeature() {
        val terminalStatus = functionBlock(
            "private fun terminalCameraDetectorStatusText(): String?",
        )
        val terminalDistanceStatus = functionBlock(
            "private fun terminalMetricDistanceStatusText(): String?",
        )
        val items = functionBlock("private fun postLoginDeviceCheckItemsMessage()")

        assertTrue(terminalStatus.contains("facts.snapshot.passesFeatureGate"))
        assertTrue(terminalStatus.contains("facts.cameraChecksDeferred"))
        assertTrue(terminalStatus.contains("재점검 필요"))
        assertTrue(terminalStatus.contains("PostLoginDeviceCheckFeature.OBSTACLE_DETECTION"))
        assertTrue(terminalStatus.contains("-> \"제한\""))
        assertTrue(terminalStatus.contains("!facts.detectorLoadAttempted()"))
        assertTrue(terminalStatus.contains("통과 · 보행 시작 시 모델 재확인"))
        assertTrue(terminalStatus.contains("facts.detectorAvailable() -> \"통과\""))
        assertFalse(terminalStatus.contains("검사 대기"))
        assertTrue(items.contains("terminalCameraDetectorStatusText()"))
        assertTrue(terminalDistanceStatus.contains("facts.snapshot.passesFeatureGate"))
        assertTrue(terminalDistanceStatus.contains("facts.cameraChecksDeferred"))
        assertTrue(
            terminalDistanceStatus.contains(
                "PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE",
            ),
        )
        assertTrue(terminalDistanceStatus.contains("제한(거리 제한 모드)"))
        assertTrue(terminalDistanceStatus.contains("PostLoginMetricDepthState.SUPPORTED"))
        assertTrue(terminalDistanceStatus.contains("\"지원\""))
        assertTrue(terminalDistanceStatus.contains("PostLoginMetricDepthState.UNKNOWN"))
        assertTrue(terminalDistanceStatus.contains("확인 불가(AR 서비스·권한 확인)"))
        assertTrue(terminalDistanceStatus.contains("지원 확인 대기"))
        assertFalse(terminalDistanceStatus.contains("통과"))
        assertFalse(terminalDistanceStatus.contains("검사 대기"))
        assertTrue(items.contains("terminalMetricDistanceStatusText()"))
    }

    @Test
    fun missingMicrophonePermissionIsExplicitInItemsWithoutResettingTheStoredResult() {
        val items = presenter("postLoginDeviceCheckItemsMessage")
        val captured = functionBlock("private fun postLoginDeviceCheckItemsMessage()")
        assertTrue(captured.contains("val microphonePermission = facts.recordAudioPermission()"))
        assertTrue(captured.contains("microphoneAvailable = startup.microphoneAvailable"))
        assertTrue(captured.contains("microphonePermission = microphonePermission"))

        assertTrue(items.contains("val microphoneText = if (!facts.microphonePermission)"))
        assertTrue(items.contains("\"제한(마이크 권한 필요)\""))
        assertTrue(items.contains("facts.microphoneAvailable.toDeviceCheckSignal()"))
        assertTrue(items.contains("observation.wakePhraseRecognition"))
        assertTrue(items.contains("\"호출어\\t\$microphoneText\""))
        assertFalse(items.contains("postLoginDeviceCheckSnapshot ="))
        assertFalse(items.contains("state = PostLoginDeviceCheckState.NOT_RUN"))
    }

    @Test
    fun permissionLimitationsMatchTheFeaturesThatActuallyStop() {
        val labels = presenter("postLoginDeviceCheckDisabledFeatureText")
        val captured = functionBlock("private fun postLoginDeviceCheckDisabledFeatureText()")
        assertTrue(captured.contains("metricFeatureEnabled = !passes || postLoginDeviceFeatureEnabled(PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE)"))
        assertTrue(captured.contains("locationFeatureEnabled = !passes || postLoginDeviceFeatureEnabled(PostLoginDeviceCheckFeature.LOCATION_GUIDANCE)"))
        val items = presenter("postLoginDeviceCheckItemsMessage")
        assertTrue(functionBlock("private fun postLoginDeviceCheckItemsMessage()").contains("val locationPermission = facts.locationPermission()"))
        val denial = functionBlock("private fun ObservedPermission.denialReasonKo()")

        assertTrue(labels.contains("카메라 기반 신고"))
        assertTrue(labels.contains("위치가 필요한 신고"))
        assertTrue(
            labels.contains(
                "!facts.metricFeatureEnabled",
            ),
        )
        assertTrue(
            labels.contains(
                "!facts.locationFeatureEnabled",
            ),
        )
        assertTrue(items.contains("\"ARCore Depth\\t\$distanceText\""))
        assertTrue(items.contains("val locationText = if (!facts.locationPermission)"))
        assertTrue(items.contains("제한(정확한 위치 권한 필요)"))
        assertTrue(items.contains("\"위치\\t\$locationText\""))
        assertTrue(denial.contains("위치·경로 안내와 위치가 필요한 신고 전송"))
        assertFalse(denial.contains("거리 측정"))
    }

    @Test
    fun collapsedReadinessAndEnvironmentCopyDiscloseLimitedFeatures() {
        val readiness = functionBlock("private fun updateWalkReadinessSection()")
        val environment = delegatedOwner("officialEnvironmentStatusMessage", "officialEnvironmentPresentation", "startup/capability/OfficialEnvironmentPresentation.kt", "officialEnvironmentStatusMessage", "= officialEnvironmentPresentation.officialEnvironmentStatusMessage(assessment)")

        assertTrue(readiness.contains("if (facts.hasCurrentRestrictions())"))
        assertTrue(readiness.contains("facts.disabledFeatureText()"))
        assertTrue(readiness.contains("제한된 카메라 장착 점검은 생략했습니다"))
        assertTrue(readiness.contains("나머지 기능은 사용할 수 있습니다"))
        assertTrue(environment.contains("val supportedReason = when"))
        assertTrue(environment.contains("val skippedQualityNotice = when"))
        assertTrue(environment.contains("skippedQualityNotice +"))
        assertTrue(environment.contains("제한된 카메라 품질 점검은 생략했습니다"))
        assertTrue(environment.contains("제한된 위치 품질 점검은 생략했습니다"))
        assertTrue(environment.contains("제한된 위치·카메라 품질 점검은 생략했습니다"))
        assertTrue(environment.contains("원인: \$supportedReason"))
    }

    @Test
    fun restoredLazyDetectorFailureImmediatelyDowngradesOnlyCameraFeatures() {
        val loader = functionBlock("private fun loadDetectorForCurrentProcess()")
        val readiness = functionBlock("private fun captureWalkSessionReadiness(")

        assertTrue(loader.contains("preparation.featureGatePassed()"))
        assertTrue(loader.contains("!detector.projection().available"))
        assertTrue(loader.contains("metricDistanceWasEnabled"))
        assertTrue(loader.contains("preparation.modelUnavailable(metricDistanceWasEnabled)"))
        val detectorBinding = MainActivityRefactorSource.composition(source, "primaryDetectorLifecycleController")
        assertTrue(detectorBinding.contains("runtimeObstacleDetectionCapabilityOverride = false"))
        assertTrue(detectorBinding.contains("if (metricDistanceWasEnabled) runtimeMetricSession.restoreCapability(false)"))
        assertTrue(readiness.contains("var effectiveDecision = capability.reusableOrRefresh(decision, captureStartedAt)"))
        assertTrue(
            readiness.indexOf("producers.features.prepareDetector(") <
                readiness.indexOf("val plan = WalkSessionReadinessPlan("),
        )
        assertTrue(
            readiness.contains(
                "effectiveDecision = producers.features.prepareDetector(effectiveDecision, action, producers.firstRun::isComplete)",
            ),
        )
        assertTrue(readiness.contains("mode = effectiveDecision.effectiveWalkSessionMode()"))
        // S03-6: FP-017-DETAIL-01 requires the model in both modes; disabled camera is not READY.
        val model = owner("session/readiness/WalkReadinessProducers.kt", "model")
        assertTrue(model.contains("val state = detector.modelState()"))
        assertTrue(model.contains("WalkSessionFeatureReadiness.model(admission.cameraEnabled(), state.available, state.failureReason,"))
        assertTrue(model.contains("preparing = allowPreparationPending && state.loadInFlight && admission.cameraPermission()"))
        assertTrue(owner("session/readiness/WalkReadinessProducers.kt", "prepareDetector").contains("if (admission.disabledFeatures() != before) effective = admission.restrict(effective)"))
        assertFalse(owner("session/readiness/WalkReadinessProducers.kt", "model").contains("!admission.cameraEnabled() || detector.isAvailable()"))
    }

    /**
     * Rewritten for S03-6/S03-7 (w20 review "tests"): the name is kept so the baseline entry reads as
     * fixed. Runtime metric loss still closes only the depth session and asks for the fallback, but
     * an ACTIVE walk never adopts the distance-limited mode silently: the refresh re-evaluates
     * readiness, the lifecycle safety-stops the mode change (WalkSessionActiveModeChangeTest), and
     * the fallback request then returns without starting a camera or a camera-less continuation.
     */
    @Test
    fun runtimeMetricLossLimitsOnlyDistanceAndKeepsTheActiveSession() {
        val metricLoss = functionBlock("private fun handleRuntimeMetricLoss(")
        val fallback = functionBlock("private fun requestCameraFallbackStart(")
        val refresh = functionBlock("private fun refreshStartupCapabilityUi()")
        val readiness = functionBlock("private fun applyRuntimeReadinessIfActive(")

        assertTrue(metricLoss.contains("loss.stopDepth()"))
        val lossBinding = MainActivityRefactorSource.composition(source, "runtimeMetricPreflight")
        assertTrue(lossBinding.contains("override fun stopDepth() { stopDepthSession(closeSession = true) }"))
        assertTrue(metricLoss.contains("loss.requestFallback(availability)"))
        assertTrue(lossBinding.contains("requestCameraFallbackStart(CameraFallbackStartReason.RUNTIME_METRIC_LOST, availability)"))
        assertFalse(metricLoss.contains("invalidateRuntimeMetricEvidence("))
        assertFalse(metricLoss.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        // No "다른 기능은 계속" notice right before the stop (S03-6 P3).
        assertFalse(metricLoss.contains("updateStatus("))
        assertTrue(lossBinding.contains("override fun status(title: String, detail: String) = updateStatus(title, detail)"))
        assertFalse("metric loss must not announce a state before fallback readiness decides",
            metricLoss.substringBefore("loss.requestFallback(availability)").contains("presentation.status("))
        assertFalse(metricLoss.contains("다른 기능은 계속됩니다"))

        assertTrue(
            fallback.indexOf("metric.restoreCapability(false)") <
                fallback.indexOf("val distanceLimitedDecision"),
        )
        assertTrue(fallback.contains("automaticallyContinueMetricOnlyDowngrade"))
        assertTrue(fallback.contains("WalkSafeStartupRequirement.METRIC_DISTANCE"))
        // The refresh runs the readiness path that stops an ACTIVE walk on a mode change (S03-7).
        assertTrue(refresh.contains("applyRuntimeReadinessIfActive(decision)"))
        assertTrue(readiness.contains("WalkSessionEvent.RuntimeReadinessChanged(readiness)"))
        assertTrue(readiness.contains("transition.current.state == WalkSessionState.SAFE_STOP"))
        val refreshAt = fallback.indexOf("readiness.refreshUi()")
        val stoppedAt = fallback.indexOf("walkAfterChange.state == WalkSessionState.SAFE_STOP")
        assertTrue(refreshAt in 0 until stoppedAt)
        assertTrue(
            fallback.substring(0, stoppedAt).substringAfterLast("if (")
                .contains("walkBeforeChange.state == WalkSessionState.ACTIVE &&"),
        )
        val stopped = fallback.substring(stoppedAt).substringBefore("val automaticTransitionReady")
        assertTrue(stopped.contains("pendingCameraFallbackStart = null"))
        assertTrue(stopped.contains("return"))
        // Nothing starts before that return: neither the fallback camera nor a camera-less walk.
        val beforeStopReturn = fallback.substringBefore("val automaticTransitionReady")
        assertFalse(beforeStopReturn.contains("startCameraFallbackSession("))
        assertFalse(beforeStopReturn.contains("readiness.startWithoutCamera()"))

        // A fallback failure (camera or detector) also leaves the stop to the readiness path.
        val fallbackFailure = functionBlock("private fun continueAfterCameraFallbackFailure(")
        assertFalse(fallbackFailure.contains("readiness.enterForegroundRecheckAndCancelOutputs("))
        assertTrue(fallbackFailure.contains("readiness.setObstacleUnavailable()"))
        assertTrue(fallbackFailure.contains("metric.restoreCapability(false)"))
        assertTrue(
            fallbackFailure.indexOf("readiness.refreshUi()") in
                0 until fallbackFailure.indexOf(
                    "if (walk().snapshot().state != WalkSessionState.ACTIVE) return",
                ),
        )
        assertTrue(
            fallbackFailure.indexOf("if (walk().snapshot().state != WalkSessionState.ACTIVE) return") <
                fallbackFailure.indexOf("readiness.startWithoutCamera()"),
        )

        val fallbackStop = owner("runtime/camera/lifecycle/RuntimeCameraLifecycleCoordinator.kt", "handleCameraFallbackSourceEvent")
        assertTrue(MainActivityRefactorSource.member(source, "stopCameraFallbackSession").contains("cameraFallbackController.stop("))
        assertTrue(source.contains("onEvent = ::handleCameraFallbackSourceEvent"))
        assertTrue(fallbackStop.contains("is CameraFallbackEvent.ReleaseFailed ->"))
        assertFalse(fallbackStop.contains("readiness.enterForegroundRecheckAndCancelOutputs("))
        assertTrue(fallbackStop.contains("walk().isRuntimeEpochCurrent(event.stoppedEpoch)"))

        // A new walk retains only current typed API support, never an optimistic saved FULL result.
        val newWalk = functionBlock("private fun resetWalkTransientStateForNewWalk()")
        assertTrue(newWalk.contains("runtimeObstacleDetectionCapabilityOverride = null"))
        assertTrue(newWalk.contains("runtimeMetricSession.restoreCapability(if (postLoginCameraDependentChecksDeferred)"))
        assertTrue(newWalk.contains("runtimeMetricSession.retainDeviceDepthApiCapability("))
        assertTrue(newWalk.contains("\"new_walk\", postLoginDeviceCheckSnapshot.bindingOrNull, metricDistanceCapabilityOverride"))
        assertFalse(newWalk.contains("postLoginDeviceCheckSnapshot.passesFeatureGate"))
    }

    @Test
    fun wakePhraseFailuresOnlyLimitHandsFreeVoiceCapability() {
        val observation = functionBlock("private fun currentPostLoginDeviceCheckObservation(")
        val wakeOwner = owner("device/postlogin/AndroidPostLoginDeviceProbes.kt", "maybeStartWake")
        assertTrue(wakeOwner.contains("wakeSignal = PostLoginDeviceCheckSignal.UNAVAILABLE"))
        assertTrue(wakeOwner.contains("wakeSignal = if (available) PostLoginDeviceCheckSignal.READY else PostLoginDeviceCheckSignal.UNAVAILABLE"))
        assertFalse(wakeOwner.contains("onDeviceSpeechRecognitionCapabilityOverride"))
        val wakeEffect = ReportStaticSourceInspector.functionBlock(MainActivityRefactorSource.property(source, "postLoginDeviceProbesHolder"), "override fun wakeResult")
        assertFalse(wakeEffect.contains("onDeviceSpeechRecognitionCapabilityOverride"))

        assertTrue(observation.contains("probeSignals.wakePhrase == PostLoginDeviceCheckSignal.UNAVAILABLE"))
        assertTrue(observation.contains("hardware.voiceModelPreparationFailed"))
        assertTrue(observation.contains("add(PostLoginDeviceCheckFeature.HANDS_FREE_VOICE)"))
        assertTrue(
            !Regex(
                """postLoginWakePhraseSignal\s*=\s*PostLoginDeviceCheckSignal\.UNAVAILABLE\s+""" +
                    """onDeviceSpeechRecognitionCapabilityOverride\s*=\s*false""",
            ).containsMatchIn(source),
        )
        assertTrue(
            !Regex(
                """postLoginWakePhraseSignal\s*=\s*available\.toDeviceCheckSignal\(\)\s+""" +
                    """onDeviceSpeechRecognitionCapabilityOverride\s*=\s*available""",
            ).containsMatchIn(source),
        )
    }

    @Test
    fun activeFeatureChangesRebindConfirmationAndNoCameraDevicesRemainInstallable() {
        val refresh = functionBlock("private fun refreshStartupCapabilityUi()")
        val firstRunUi = functionBlock("private fun updateFirstRunOnboardingUi()")
        val mountingUi = functionBlock("private fun updatePhoneMountingUi()")

        assertTrue(refresh.contains("automaticallyRebindActiveFeatureDecision"))
        assertTrue(refresh.contains("confirmation.confirmed == previousDecision"))
        assertTrue(refresh.contains("decision.pendingRequirements.isEmpty()"))
        assertTrue(refresh.contains("changedUnavailableRequirements.all"))
        assertTrue(
            firstRunUi.contains(
                "val showPhoneMountingPreparation = showWalkPreparation && facts.cameraAnalysisEnabled()",
            ),
        )
        assertTrue(
            Regex(
                """phoneMountingChestConfirmButton\.visibility\s*=\s*""" +
                    """if \(facts\.showPhoneMountingPreparation &&\s*!facts\.developmentGuidanceStartBypassEnabled &&\s*""" +
                    """\(!facts\.developmentQuickStartEnabled \|\| facts\.walkState != WalkSessionState.READY\)\s*""" +
                    """\) View\.VISIBLE else View\.GONE""",
            ).containsMatchIn(owner("ui/FirstRunOnboardingPresenter.kt", "renderPreparation")),
        )
        assertTrue(
            Regex(
                """private val developmentGuidanceStartBypassEnabled:\s*Boolean\s+""" +
                    """get\(\)\s*=\s*BuildConfig\.DEBUG\s*&&\s*""" +
                    """BuildConfig\.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS\s*&&""",
            ).containsMatchIn(source),
        )
        assertTrue(
            owner("ui/FirstRunOnboardingPresenter.kt", "renderPreparation").contains("phoneMountingNecklaceConfirmButton.visibility = View.GONE"),
        )
        assertTrue(
            refresh.indexOf("ui.updateFirstRun()") <
                refresh.lastIndexOf("ui.updateMounting()"),
        )
        val noCamera = mountingUi
            .substringAfter("if (!admission.cameraEnabled()) {")
            .substringBefore("val mountingChoiceVisibility")
        assertTrue(noCamera.contains("view.setChestVisible(false)"))
        assertTrue(noCamera.contains("view.hideNecklace()"))
        assertTrue(noCamera.contains("return"))
        assertTrue(
            manifest.contains(
                "<uses-feature android:name=\"android.hardware.camera\" android:required=\"false\" />",
            ),
        )
    }

    private fun functionBlock(signature: String): String {
        if (signature.contains("completePostLoginDeviceCheckPass(")) {
            assertTrue(MainActivityRefactorSource.device.contains("-> completePass()"))
            assertTrue(source.contains("PostLoginDeviceCheckController("))
            return MainActivityRefactorSource.member(MainActivityRefactorSource.device, "completePass")
        }
        if (signature.contains("ObservedPermission.denialReasonKo")) return ReportStaticSourceInspector.functionBlock(source, "private fun ObservedPermission.denialReasonKo")
        if (signature.contains("captureWalkSessionReadiness(")) {
            assertTrue(MainActivityRefactorSource.property(source, "walkReadinessCoordinator").contains("WalkReadinessCoordinator("))
            assertTrue(MainActivityRefactorSource.member(source, "captureWalkSessionReadiness").contains("walkReadinessCoordinator.capture("))
            return owner("session/readiness/WalkReadinessCoordinator.kt", "capture")
        }
        return MainActivityRefactorSource.function(source, signature)
    }
    private fun owner(path: String, name: String): String = MainActivityRefactorSource.member(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/$path").readText(), name,
    )
    private fun presenter(name: String) = ReportStaticSourceInspector.functionBlock(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DeviceCheckPresenter.kt").readText(), "fun $name",
    )

    // Structural wiring and owner policy are checked separately; this does not execute Main.
    private fun delegatedOwner(name: String, owner: String, path: String, target: String, expectedBody: String): String {
        val caller = MainActivityRefactorSource.factory(source, name)
        // Match the whole caller, including additional effects; a guarded call or lambda is different.
        val declaration = caller.filterNot(Char::isWhitespace)
        val body = Regex.escape(expectedBody.filterNot(Char::isWhitespace))
        val expected = Regex("""^(?:private|internal|public|override)*fun$name\([^{}]*\)(?::[\w?<>]+)?$body$""")
        assertTrue("Main caller changed for $owner.$target: $caller", expected.matches(declaration))
        assertTrue(expectedBody.contains("$owner.$target("))
        MainActivityRefactorSource.composition(source, owner)
        return MainActivityRefactorSource.member(
            File("src/main/java/kr/co/hanium/dreamup/walksafe/$path").readText(), target,
        )
    }
}

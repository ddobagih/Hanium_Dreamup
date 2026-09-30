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

        assertTrue(observation.contains("val cameraPermissionGranted = hasCameraPermission()"))
        val unsupported = observation
            .substringAfter("val unsupportedFeatures = buildSet {")
            .substringBefore("return PostLoginDeviceCheckObservation(")
        assertFalse(unsupported.contains("cameraPipeline == PostLoginDeviceCheckSignal.UNAVAILABLE"))
        assertTrue(current.contains("addAll(postLoginDeviceCheckSnapshot.disabledFeatures)"))
        assertTrue(current.contains("!hasCameraPermission()"))
        assertTrue(current.contains("PostLoginDeviceCheckFeature.OBSTACLE_DETECTION"))
        assertTrue(current.contains("PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE"))
        assertTrue(current.contains("runtimeObstacleDetectionCapabilityOverride == false"))
        assertTrue(current.contains("metricDistanceCapabilityOverride == false"))
        assertTrue(current.contains("!hasLocationPermission()"))
        assertTrue(current.contains("!isLocationServiceEnabledForDeviceCheck()"))
        assertTrue(current.contains("!isHandsFreeVoiceDisclosureAccepted()"))
        assertFalse(current.contains("!hasActivityRecognitionPermission()"))
        assertTrue(labels.contains("!hasActivityRecognitionPermission()"))
        assertTrue(labels.contains("걸음 수 추적"))
        assertTrue(apply.contains("currentPostLoginDisabledFeatures().forEach"))
    }

    @Test
    fun officialEnvironmentRunsOnlyTheSensorsNeededByEnabledFeatures() {
        val confirm = functionBlock("private fun confirmOfficialEnvironmentConditions()")
        val gps = functionBlock("private fun requestOfficialEnvironmentGpsPreflight(")
        val assessment = functionBlock("private fun currentOfficialEnvironmentAssessment(")
        val mounting = functionBlock("private fun phoneMountingReadiness(")
        val runtimeMounting = functionBlock("private fun activatePhoneMountingRuntime()")
        val outputGate = functionBlock("private fun walkSafetyOutputsAllowed()")
            .substringBefore("\n    private fun ")
        val navigationGate = functionBlock("private fun navigationEnvironmentOutputsAllowed()")
            .substringBefore("\n    private fun ")
        val cameraGate = functionBlock("private fun cameraEnvironmentOutputsAllowed()")
            .substringBefore("\n    private fun ")
        val watchdog = functionBlock("private fun scheduleOfficialEnvironmentRuntimeWatchdog(")

        assertTrue(confirm.contains("val requireLocation = postLoginDeviceFeatureEnabled("))
        assertTrue(confirm.contains("val requireCamera = cameraAnalysisFeaturesEnabled()"))
        assertTrue(confirm.contains("else if (requireCamera)"))
        assertTrue(confirm.contains("else if (requireLocation)"))
        assertTrue(gps.contains("val cameraRequired = cameraAnalysisFeaturesEnabled()"))
        assertTrue(gps.contains("(cameraRequired && !officialEnvironmentCameraPreflightActive)"))
        assertTrue(assessment.contains("OfficialEnvironmentFactor.GPS_QUALITY"))
        assertTrue(assessment.contains("OfficialEnvironmentFactor.CAMERA_QUALITY"))
        assertTrue(assessment.contains("val enabledMeasuredFactors = buildSet"))
        assertTrue(assessment.contains("if (postLoginDeviceFeatureEnabled(PostLoginDeviceCheckFeature.LOCATION_GUIDANCE))"))
        assertTrue(assessment.contains("if (cameraAnalysisFeaturesEnabled())"))
        assertTrue(assessment.contains("enabledMeasuredFactors = enabledMeasuredFactors,"))
        assertTrue(assessment.contains("runtimeUnavailableMeasuredFactors = runtimeUnavailableMeasuredFactors,"))
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

        assertTrue(activation.contains("if (cameraAnalysisFeaturesEnabled())"))
        assertTrue(afterRelease.contains("if (!cameraAnalysisFeaturesEnabled())"))
        assertTrue(afterRelease.contains("startWalkSessionRuntimeWithoutCamera()"))
        assertTrue(withoutCamera.contains("startNavigationServicesIfNeeded()"))
        assertTrue(withoutCamera.contains("maybeStartHandsFreeVoiceService()"))
        assertTrue(permissions.contains("if (!cameraAnalysisFeaturesEnabled())"))
        assertTrue(confirmed.contains("if (!cameraAnalysisFeaturesEnabled())"))
        assertTrue(
            stepTracking.contains(
                "if (!navigationEnvironmentOutputsAllowed()) return false",
            ),
        )
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
            "if (!hasLocationPermission() || !isLocationServiceEnabledForDeviceCheck())",
        ).substringBefore("if (!hasHandsFreeNotificationPermission())")
        val settingsUi = source.substringAfter("postLoginDeviceCheckSettingsButton.apply {")
            .substringBefore("postLoginDeviceCheckWakePhraseInstructionText.visibility")
        val focus = functionBlock("override fun onWindowFocusChanged(hasFocus: Boolean)")
        val refresh = functionBlock("private fun refreshStartupCapabilityUi()")

        // Dependent resources stop at once.
        assertTrue(observed.contains("stopDepthSession(closeSession = true)"))
        assertTrue(observed.contains("stopCameraFallbackSession(updateUi = false)"))
        assertTrue(observed.contains("stopLocationUpdates()"))
        assertFalse(locationRevocation.contains("stopStepTracking()"))
        assertTrue(observed.contains("stopHandsFreeVoiceService()"))
        // Losing both core permissions stops the whole walk behind the recovery barrier.
        val wholeStop = observed.indexOf("enterWalkSessionSafetyStopAndCancelOutputs(\"core_guidance_permissions_unavailable\")")
        assertTrue(wholeStop >= 0)
        assertTrue(wholeStop < observed.indexOf("enterPermissionRecoveryBarrier("))
        // S03-6: the refresh (readiness re-evaluation) runs before any notice or continuation.
        assertTrue(refresh.contains("applyRuntimeReadinessIfActive(decision)"))
        val refreshAt = observed.indexOf("if (::startupCapabilityProbe.isInitialized) refreshStartupCapabilityUi()")
        assertTrue(refreshAt > wholeStop)
        assertTrue(refreshAt < observed.indexOf("\"일부 기능 제한\""))
        assertTrue(refreshAt < observed.indexOf("startWalkSessionRuntimeWithoutCamera()"))
        assertTrue(refreshAt < observed.indexOf("activatePhoneMountingRuntime()"))
        assertTrue(Regex("refreshStartupCapabilityUi\\(\\)").findAll(observed).count() == 1)
        // The "나머지 기능은 계속" notice only while the walk is still running after the refresh.
        val notice = observed.substringBefore("\"일부 기능 제한\"")
        assertTrue(notice.contains("val stateAfterRefresh = walkSessionLifecycle.snapshot().state"))
        assertTrue(notice.contains("stateAfterRefresh in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED)"))
        // The camera-less continuation only for a walk that is still ACTIVE after the refresh.
        val continuation = observed.substringAfter("// The refresh above may have safety-stopped the walk")
        assertTrue(
            continuation.indexOf("if (walkSessionLifecycle.snapshot().state == WalkSessionState.ACTIVE) {") in
                0 until continuation.indexOf("startWalkSessionRuntimeWithoutCamera()"),
        )
        assertTrue(
            continuation.indexOf("activatePhoneMountingRuntime()") <
                continuation.indexOf("startWalkSessionRuntimeWithoutCamera()"),
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
        val retryable = source
            .substringAfter("private fun deferredCameraDependentChecksCanBeRetried()")
            .substringBefore("private fun cameraAnalysisFeaturesEnabled()")
        val primaryAction = functionBlock(
            "private fun handlePostLoginDeviceCheckPrimaryAction()",
        )
        val refresh = functionBlock("private fun refreshStartupCapabilityUi()")
        val newWalk = functionBlock("private fun resetWalkTransientStateForNewWalk()")

        assertTrue(bind.contains("val sessionBindingChanged = next != previous"))
        assertTrue(bind.contains("restored.cameraDependentChecksDeferred"))
        assertTrue(bind.contains("else if (sessionBindingChanged)"))
        assertTrue(bind.contains("postLoginCameraDependentChecksDeferred = false"))
        assertFalse(bind.contains("restored?.cameraDependentChecksDeferred ?: false"))
        assertTrue(bind.contains("if (restored.cameraDependentChecksDeferred)"))
        assertTrue(bind.contains("metricDistanceCapabilityOverride"))
        assertTrue(bind.contains("runtimeObstacleDetectionCapabilityOverride"))
        assertTrue(
            complete.contains(
                "postLoginCameraPipelineSignal != PostLoginDeviceCheckSignal.READY",
            ),
        )
        assertTrue(
            complete.contains(
                "PostLoginDeviceCheckFeature.OBSTACLE_DETECTION !in",
            ),
        )
        assertTrue(
            complete.indexOf("postLoginDeviceCheckResultStore.save(") <
                complete.indexOf(
                    "postLoginCameraDependentChecksDeferred = " +
                        "cameraDependentChecksDeferred",
                ),
        )
        assertTrue(current.contains("if (postLoginCameraDependentChecksDeferred)"))
        assertTrue(current.contains("PostLoginDeviceCheckFeature.OBSTACLE_DETECTION"))
        assertTrue(current.contains("PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE"))
        assertTrue(retryable.contains("postLoginDeviceCheckSnapshot.passesFeatureGate"))
        assertTrue(retryable.contains("postLoginCameraDependentChecksDeferred"))
        assertTrue(retryable.contains("hasCameraPermission()"))
        assertTrue(primaryAction.contains("if (!deferredCameraDependentChecksCanBeRetried()) return"))
        assertTrue(primaryAction.contains("state = PostLoginDeviceCheckState.NOT_RUN"))
        assertTrue(primaryAction.contains("startPostLoginDeviceCheckFromPrimaryAction()"))
        assertTrue(refresh.contains("deferredCameraDependentChecksCanBeRetried()"))
        assertTrue(refresh.contains("카메라·거리 기능 다시 점검"))
        assertTrue(newWalk.contains("if (postLoginCameraDependentChecksDeferred)"))
        assertTrue(newWalk.contains("metricDistanceCapabilityOverride"))
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

        assertTrue(terminalStatus.contains("postLoginDeviceCheckSnapshot.passesFeatureGate"))
        assertTrue(terminalStatus.contains("postLoginCameraDependentChecksDeferred"))
        assertTrue(terminalStatus.contains("재점검 필요"))
        assertTrue(terminalStatus.contains("PostLoginDeviceCheckFeature.OBSTACLE_DETECTION"))
        assertTrue(terminalStatus.contains("-> \"제한\""))
        assertTrue(terminalStatus.contains("!detectorLoadAttempted"))
        assertTrue(terminalStatus.contains("통과 · 보행 시작 시 모델 재확인"))
        assertTrue(terminalStatus.contains("detectorAvailable -> \"통과\""))
        assertFalse(terminalStatus.contains("검사 대기"))
        assertTrue(items.contains("terminalCameraDetectorStatusText()"))
        assertTrue(terminalDistanceStatus.contains("postLoginDeviceCheckSnapshot.passesFeatureGate"))
        assertTrue(terminalDistanceStatus.contains("postLoginCameraDependentChecksDeferred"))
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
        val items = functionBlock("private fun postLoginDeviceCheckItemsMessage()")

        assertTrue(items.contains("val microphoneText = if (!hasRecordAudioPermission())"))
        assertTrue(items.contains("\"제한(마이크 권한 필요)\""))
        assertTrue(items.contains("startup.microphoneAvailable.toDeviceCheckSignal()"))
        assertTrue(items.contains("observation.wakePhraseRecognition"))
        assertTrue(items.contains("\"호출어\\t\$microphoneText\""))
        assertFalse(items.contains("postLoginDeviceCheckSnapshot ="))
        assertFalse(items.contains("state = PostLoginDeviceCheckState.NOT_RUN"))
    }

    @Test
    fun permissionLimitationsMatchTheFeaturesThatActuallyStop() {
        val labels = functionBlock("private fun postLoginDeviceCheckDisabledFeatureText()")
        val items = functionBlock("private fun postLoginDeviceCheckItemsMessage()")
        val denial = functionBlock("private fun ObservedPermission.denialReasonKo()")

        assertTrue(labels.contains("카메라 기반 신고"))
        assertTrue(labels.contains("위치가 필요한 신고"))
        assertTrue(
            labels.contains(
                "!postLoginDeviceFeatureEnabled(\n" +
                    "                PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE",
            ),
        )
        assertTrue(
            labels.contains(
                "!postLoginDeviceFeatureEnabled(PostLoginDeviceCheckFeature.LOCATION_GUIDANCE)",
            ),
        )
        assertTrue(items.contains("\"ARCore Depth\\t\$distanceText\""))
        assertTrue(items.contains("val locationText = if (!hasLocationPermission())"))
        assertTrue(items.contains("제한(정확한 위치 권한 필요)"))
        assertTrue(items.contains("\"위치\\t\$locationText\""))
        assertTrue(denial.contains("위치·경로 안내와 위치가 필요한 신고 전송"))
        assertFalse(denial.contains("거리 측정"))
    }

    @Test
    fun collapsedReadinessAndEnvironmentCopyDiscloseLimitedFeatures() {
        val readiness = functionBlock("private fun updateWalkReadinessSection()")
        val environment = functionBlock("private fun officialEnvironmentStatusMessage(")

        assertTrue(readiness.contains("if (hasCurrentPostLoginDeviceRestrictions())"))
        assertTrue(readiness.contains("postLoginDeviceCheckDisabledFeatureText()"))
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

        assertTrue(loader.contains("postLoginDeviceCheckSnapshot.passesFeatureGate"))
        assertTrue(loader.contains("!detectorAvailable"))
        assertTrue(loader.contains("metricDistanceWasEnabled"))
        assertTrue(loader.contains("runtimeObstacleDetectionCapabilityOverride = false"))
        assertTrue(loader.contains("metricDistanceCapabilityOverride = false"))
        assertTrue(readiness.contains("var effectiveDecision = decision"))
        assertTrue(
            readiness.indexOf("loadDetectorAfterCameraGate()") <
                readiness.indexOf("val plan = WalkSessionReadinessPlan("),
        )
        assertTrue(
            readiness.contains(
                "effectiveDecision = applyPostLoginDeviceFeatureRestrictions(decision)",
            ),
        )
        assertTrue(readiness.contains("mode = effectiveDecision.effectiveWalkSessionMode()"))
        // S03-6: FP-017-DETAIL-01 requires the model in both modes; disabled camera is not READY.
        assertTrue(readiness.contains("cameraGuidanceEnabled = cameraAnalysisFeaturesEnabled()"))
        assertFalse(readiness.contains("!cameraAnalysisFeaturesEnabled() || detectorAvailable"))
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

        assertTrue(metricLoss.contains("stopDepthSession(closeSession = true)"))
        assertTrue(metricLoss.contains("CameraFallbackStartReason.RUNTIME_METRIC_LOST"))
        assertFalse(metricLoss.contains("invalidateRuntimeMetricEvidence("))
        assertFalse(metricLoss.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        // No "다른 기능은 계속" notice right before the stop (S03-6 P3).
        assertFalse(metricLoss.contains("updateStatus("))
        assertFalse(metricLoss.contains("다른 기능은 계속됩니다"))

        assertTrue(
            fallback.indexOf("metricDistanceCapabilityOverride = false") <
                fallback.indexOf("val distanceLimitedDecision"),
        )
        assertTrue(fallback.contains("automaticallyContinueMetricOnlyDowngrade"))
        assertTrue(fallback.contains("WalkSafeStartupRequirement.METRIC_DISTANCE"))
        // The refresh runs the readiness path that stops an ACTIVE walk on a mode change (S03-7).
        assertTrue(refresh.contains("applyRuntimeReadinessIfActive(decision)"))
        assertTrue(readiness.contains("WalkSessionEvent.RuntimeReadinessChanged(readiness)"))
        assertTrue(readiness.contains("transition.current.state == WalkSessionState.SAFE_STOP"))
        val refreshAt = fallback.indexOf("refreshStartupCapabilityUi()")
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
        assertFalse(beforeStopReturn.contains("startWalkSessionRuntimeWithoutCamera()"))

        // A fallback failure (camera or detector) also leaves the stop to the readiness path.
        val fallbackFailure = functionBlock("private fun continueAfterCameraFallbackFailure(")
        assertFalse(fallbackFailure.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        assertTrue(fallbackFailure.contains("runtimeObstacleDetectionCapabilityOverride = false"))
        assertTrue(fallbackFailure.contains("metricDistanceCapabilityOverride = false"))
        assertTrue(
            fallbackFailure.indexOf("refreshStartupCapabilityUi()") in
                0 until fallbackFailure.indexOf(
                    "if (walkSessionLifecycle.snapshot().state != WalkSessionState.ACTIVE) return",
                ),
        )
        assertTrue(
            fallbackFailure.indexOf("if (walkSessionLifecycle.snapshot().state != WalkSessionState.ACTIVE) return") <
                fallbackFailure.indexOf("startWalkSessionRuntimeWithoutCamera()"),
        )

        val fallbackStop = functionBlock("private fun stopCameraFallbackSession(")
        assertTrue(fallbackStop.contains("val stoppedWalkEpoch"))
        assertFalse(fallbackStop.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        assertTrue(fallbackStop.contains("walkSessionLifecycle.isRuntimeEpochCurrent(stoppedWalkEpoch)"))

        // "새 보행 준비" clears the runtime overrides, so the stop is not sticky.
        val newWalk = functionBlock("private fun resetWalkTransientStateForNewWalk()")
        assertTrue(newWalk.contains("runtimeObstacleDetectionCapabilityOverride = null"))
        assertTrue(newWalk.contains("metricDistanceCapabilityOverride ="))
    }

    @Test
    fun wakePhraseFailuresOnlyLimitHandsFreeVoiceCapability() {
        val observation = functionBlock("private fun currentPostLoginDeviceCheckObservation(")

        assertTrue(observation.contains("postLoginWakePhraseSignal == PostLoginDeviceCheckSignal.UNAVAILABLE"))
        assertTrue(observation.contains("handsFreeVoiceModelPreparationFailed"))
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
        assertTrue(refresh.contains("confirmedStartupCapabilityDecision == previousDecision"))
        assertTrue(refresh.contains("decision.pendingRequirements.isEmpty()"))
        assertTrue(refresh.contains("changedUnavailableRequirements.all"))
        assertTrue(
            firstRunUi.contains(
                "val showPhoneMountingPreparation =\n" +
                    "            showWalkPreparation && cameraAnalysisFeaturesEnabled()",
            ),
        )
        assertTrue(
            Regex(
                """phoneMountingChestConfirmButton\.visibility\s*=\s*""" +
                    """if \(showPhoneMountingPreparation &&\s*!developmentGuidanceStartBypassEnabled &&\s*""" +
                    """\(!developmentQuickStartEnabled \|\| walkState != WalkSessionState.READY\)\s*""" +
                    """\) View\.VISIBLE else View\.GONE""",
            ).containsMatchIn(firstRunUi),
        )
        assertTrue(
            Regex(
                """private val developmentGuidanceStartBypassEnabled:\s*Boolean\s+""" +
                    """get\(\)\s*=\s*BuildConfig\.DEBUG\s*&&\s*""" +
                    """BuildConfig\.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS\s*&&""",
            ).containsMatchIn(source),
        )
        assertTrue(
            firstRunUi.contains("phoneMountingNecklaceConfirmButton.visibility = View.GONE"),
        )
        assertTrue(
            refresh.indexOf("updateFirstRunOnboardingUi()") <
                refresh.lastIndexOf("updatePhoneMountingUi()"),
        )
        val noCamera = mountingUi
            .substringAfter("if (!cameraAnalysisFeaturesEnabled()) {")
            .substringBefore("val mountingChoiceVisibility")
        assertTrue(noCamera.contains("phoneMountingChestConfirmButton.visibility = View.GONE"))
        assertTrue(noCamera.contains("phoneMountingNecklaceConfirmButton.visibility = View.GONE"))
        assertTrue(noCamera.contains("return"))
        assertTrue(
            manifest.contains(
                "<uses-feature android:name=\"android.hardware.camera\" android:required=\"false\" />",
            ),
        )
    }

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "missing function: $signature" }
        var depth = 0
        var opened = false
        for (index in start until source.length) {
            when (source[index]) {
                '{' -> {
                    depth += 1
                    opened = true
                }
                '}' -> {
                    depth -= 1
                    if (opened && depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("unterminated function: $signature")
    }
}

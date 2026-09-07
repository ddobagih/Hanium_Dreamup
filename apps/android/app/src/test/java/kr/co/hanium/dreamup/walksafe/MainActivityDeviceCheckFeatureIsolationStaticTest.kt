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
        val watchdog = functionBlock("private fun scheduleOfficialEnvironmentRuntimeWatchdog(")

        assertTrue(confirm.contains("val requireLocation = postLoginDeviceFeatureEnabled("))
        assertTrue(confirm.contains("val requireCamera = cameraAnalysisFeaturesEnabled()"))
        assertTrue(confirm.contains("else if (requireCamera)"))
        assertTrue(confirm.contains("else if (requireLocation)"))
        assertTrue(gps.contains("val cameraRequired = cameraAnalysisFeaturesEnabled()"))
        assertTrue(gps.contains("(cameraRequired && !officialEnvironmentCameraPreflightActive)"))
        assertTrue(assessment.contains("OfficialEnvironmentFactor.GPS_QUALITY"))
        assertTrue(assessment.contains("OfficialEnvironmentFactor.CAMERA_QUALITY"))
        assertTrue(mounting.contains("if (!cameraAnalysisFeaturesEnabled())"))
        assertTrue(mounting.contains("WalkSessionReadinessStatus.READY to \"\""))
        assertTrue(runtimeMounting.contains("phoneMountingOutputsAllowed = true"))
        assertTrue(outputGate.contains("!cameraAnalysisFeaturesEnabled()"))
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
                "if (cameraAnalysisFeaturesEnabled() && !phoneMountingOutputsAllowed) return false",
            ),
        )
    }

    @Test
    fun permissionLossStopsOnlyDependentResourcesAndCanBeRecoveredFromSettings() {
        val observed = functionBlock("private fun applyObservedPermissionStateChange(")
        val locationRevocation = observed.substringAfter(
            "if (!hasLocationPermission() || !isLocationServiceEnabledForDeviceCheck())",
        ).substringBefore("if (!hasHandsFreeNotificationPermission())")
        val settingsUi = source.substringAfter("postLoginDeviceCheckSettingsButton.apply {")
            .substringBefore("postLoginDeviceCheckWakePhraseInstructionText.visibility")
        val focus = functionBlock("override fun onWindowFocusChanged(hasFocus: Boolean)")

        assertTrue(observed.contains("stopDepthSession(closeSession = true)"))
        assertTrue(observed.contains("stopCameraFallbackSession(updateUi = false)"))
        assertTrue(observed.contains("stopLocationUpdates()"))
        assertFalse(locationRevocation.contains("stopStepTracking()"))
        assertTrue(observed.contains("stopHandsFreeVoiceService()"))
        assertFalse(observed.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        assertTrue(observed.contains("activatePhoneMountingRuntime()"))
        assertTrue(observed.contains("startWalkSessionRuntimeWithoutCamera()"))
        assertTrue(observed.contains("applyCurrentOfficialEnvironmentRuntimeAssessment()"))
        assertTrue(observed.contains("startNavigationServicesIfNeeded()"))
        assertTrue(
            observed.indexOf("activatePhoneMountingRuntime()") <
                observed.indexOf("startWalkSessionRuntimeWithoutCamera()"),
        )
        assertTrue(
            observed.indexOf("startWalkSessionRuntimeWithoutCamera()") <
                observed.indexOf("} else {"),
        )
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
        assertTrue(readiness.contains("!cameraAnalysisFeaturesEnabled() || detectorAvailable"))
    }

    @Test
    fun runtimeMetricLossLimitsOnlyDistanceAndKeepsTheActiveSession() {
        val metricLoss = functionBlock("private fun handleRuntimeMetricLoss(")
        val fallback = functionBlock("private fun requestCameraFallbackStart(")
        val fallbackStart = functionBlock("private fun startCameraFallbackSession(")

        assertTrue(metricLoss.contains("stopDepthSession(closeSession = true)"))
        assertTrue(metricLoss.contains("CameraFallbackStartReason.RUNTIME_METRIC_LOST"))
        assertFalse(metricLoss.contains("invalidateRuntimeMetricEvidence("))
        assertFalse(metricLoss.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        assertTrue(fallback.contains("automaticallyContinueMetricOnlyDowngrade"))
        assertTrue(fallback.contains("confirmedStartupCapabilityDecision"))
        assertTrue(fallback.contains("WalkSafeStartupRequirement.METRIC_DISTANCE"))
        assertTrue(fallback.contains("WalkSessionMode.DISTANCE_LIMITED"))
        assertTrue(
            fallback.indexOf("metricDistanceCapabilityOverride = false") <
                fallback.indexOf("val distanceLimitedDecision"),
        )
        assertTrue(
            fallback.indexOf("enterWalkSessionForegroundRecheckAndCancelOutputs(") <
                fallback.indexOf(
                    "pendingCameraFallbackStart = PendingCameraFallbackStart(reason, availability)",
                ),
        )
        assertTrue(fallback.contains("startWalkSessionRuntimeWithoutCamera()"))
        assertTrue(fallbackStart.contains("미터 거리 안내와 거리 수치가 필요한 자동 위험 판정만 제한"))
        assertTrue(fallbackStart.contains("위치·경로·걸음 수·음성·진동 기능은 계속"))
        val fallbackFailure = functionBlock(
            "private fun continueAfterCameraFallbackFailure(",
        )
        assertFalse(fallbackFailure.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        assertTrue(fallbackFailure.contains("startWalkSessionRuntimeWithoutCamera()"))
        assertTrue(fallbackFailure.contains("runtimeObstacleDetectionCapabilityOverride = false"))
        assertTrue(fallbackFailure.contains("metricDistanceCapabilityOverride = false"))

        val fallbackSessionStart = functionBlock("private fun startCameraFallbackSession(")
        assertFalse(fallbackSessionStart.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        val fallbackBind = functionBlock("private fun bindCameraFallbackSession()")
        assertFalse(fallbackBind.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        val fallbackStop = functionBlock("private fun stopCameraFallbackSession(")
        assertTrue(fallbackStop.contains("val stoppedWalkEpoch"))
        assertTrue(fallbackStop.contains("walkSessionLifecycle.currentRuntimeEpochOrNull()"))
        assertFalse(fallbackStop.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        assertTrue(
            fallbackStop.contains("onReleased != null || !cameraFallbackCleanupPending"),
        )
        assertFalse(fallbackStop.contains("replacementActiveWalk"))
        assertTrue(
            fallbackStop.contains(
                "walkSessionLifecycle.isRuntimeEpochCurrent(stoppedWalkEpoch)",
            ),
        )

        val transition = functionBlock("private fun transitionWalkSession(")
        assertTrue(transition.contains("transition.previous.state != transition.current.state"))
        val newWalk = functionBlock("private fun resetWalkTransientStateForNewWalk()")
        assertTrue(newWalk.contains("runtimeObstacleDetectionCapabilityOverride = null"))
        assertTrue(newWalk.contains("metricDistanceCapabilityOverride ="))
        assertTrue(newWalk.contains("postLoginDeviceCheckSnapshot.disabledFeatures"))
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
                    """if \(showPhoneMountingPreparation &&\s*""" +
                    """\(!developmentQuickStartEnabled \|\| walkState != WalkSessionState.READY\)\s*""" +
                    """\) View\.VISIBLE else View\.GONE""",
            ).containsMatchIn(firstRunUi),
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

package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityOfficialEnvironmentStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun confirmationIsMemoryOnlyAndBoundToCurrentEpoch() {
        val confirmation = functionBlock("private fun confirmOfficialEnvironmentConditions()")

        assertTrue(confirmation.contains("epoch = snapshot.epoch"))
        assertTrue(confirmation.contains("brightTime = EnvironmentEvidenceStatus.PASS"))
        assertTrue(confirmation.contains("dryWeather = EnvironmentEvidenceStatus.PASS"))
        assertTrue(confirmation.contains("noDenseFog = EnvironmentEvidenceStatus.PASS"))
        assertTrue(confirmation.contains("ordinaryUrbanSidewalk = EnvironmentEvidenceStatus.PASS"))
        assertTrue(confirmation.contains("noConstruction = EnvironmentEvidenceStatus.PASS"))
        assertTrue(confirmation.contains("noSevereCrowding = EnvironmentEvidenceStatus.PASS"))
        assertTrue(
            confirmation.contains(
                "supportLimitsNoticeAcknowledged = EnvironmentEvidenceStatus.PASS",
            ),
        )
        assertFalse(confirmation.contains("stepLengthPrefs"))
        assertFalse(source.contains("PREF_OFFICIAL_ENVIRONMENT"))
    }

    @Test
    fun gpsPreflightContinuouslyRefreshesAndIsGenerationEpochBound() {
        val request = functionBlock("private fun requestOfficialEnvironmentGpsPreflight(")
        val lease = functionBlock("private fun isOfficialEnvironmentGpsPreflightCurrent(")
        val observe = functionBlock("private fun observeOfficialEnvironmentGpsLocation(")
        val stop = functionBlock("private fun stopOfficialEnvironmentGpsPreflight(")
        val invalidate = functionBlock("private fun invalidateOfficialEnvironmentEvidence(")
        val cameraStop = functionBlock("private fun stopOfficialEnvironmentCameraPreflight(")

        assertTrue(request.contains("object : LocationCallback()"))
        assertTrue(request.contains("fusedLocationClient.requestLocationUpdates("))
        assertTrue(request.contains("callback,"))
        assertTrue(request.contains("Looper.getMainLooper()"))
        assertTrue(request.contains("Priority.PRIORITY_HIGH_ACCURACY"))
        assertTrue(request.contains("LocationRequest.Builder("))
        assertTrue(request.contains("setMaxUpdateAgeMillis(0L)"))
        assertTrue(request.contains("setWaitForAccurateLocation(true)"))
        assertTrue(
            request.contains(
                "setDurationMillis(OFFICIAL_ENVIRONMENT_GPS_REQUEST_MAX_DURATION_MS)",
            ),
        )
        assertFalse(request.contains("getCurrentLocation("))
        val mockCompatibility = functionBlock("private fun isMockLocationCompat(")
        assertTrue(mockCompatibility.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.S"))
        assertTrue(mockCompatibility.contains("location.isMock"))
        assertTrue(mockCompatibility.contains("location.isFromMockProvider"))
        assertTrue(request.contains("isOfficialEnvironmentGpsPreflightCurrent("))
        assertTrue(lease.contains("officialEnvironmentGpsCallback === callback"))
        assertTrue(lease.contains("generation == officialEnvironmentPreflightGeneration"))
        assertTrue(lease.contains("officialEnvironmentCameraPreflightActive"))
        assertTrue(lease.contains("snapshot.epoch == epoch"))
        assertTrue(observe.contains("OfficialEnvironmentGpsPreflightPolicy.assess("))
        assertTrue(observe.contains("OfficialEnvironmentGpsPreflightPolicy.selectEvidence("))
        assertTrue(observe.contains("maximumGpsHorizontalAccuracyMeters"))
        assertTrue(observe.contains("GPS_PREFLIGHT_LOG_TAG"))
        assertFalse(observe.contains("\"latitude="))
        assertFalse(observe.contains("\"longitude="))
        assertTrue(stop.contains("removeLocationUpdates"))
        assertTrue(invalidate.contains("officialEnvironmentPreflightGeneration += 1L"))
        assertTrue(invalidate.contains("stopOfficialEnvironmentCameraPreflight()"))
        assertTrue(cameraStop.contains("stopOfficialEnvironmentGpsPreflight()"))
        assertFalse(request.contains("activateWalkSessionRuntime()"))
        assertFalse(request.contains("confirmedStartupCapabilityDecision ="))
    }

    @Test
    fun officialEnvironmentMapsIntoWalkReadinessFailClosed() {
        val readiness = functionBlock("private fun officialEnvironmentReadiness(")
        val capture = functionBlock("private fun captureWalkSessionReadiness(")
        val blockReason = functionBlock("private fun walkSessionReadinessBlockReason(")

        assertTrue(readiness.contains("OfficialEnvironmentSupport.SUPPORTED"))
        assertTrue(readiness.contains("WalkSessionReadinessStatus.READY"))
        assertTrue(
            readiness.contains(
                "OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY",
            ),
        )
        assertTrue(readiness.contains("OfficialEnvironmentSupport.LIMITED"))
        assertTrue(readiness.contains("WalkSessionReadinessStatus.PENDING"))
        assertTrue(readiness.contains("OfficialEnvironmentSupport.UNSUPPORTED"))
        assertTrue(readiness.contains("WalkSessionReadinessStatus.UNAVAILABLE"))
        assertTrue(
            capture.contains(
                "WalkSessionReadinessRequirement.OFFICIAL_ENVIRONMENT ->\n" +
                    "                    officialEnvironmentReadiness(epoch)",
            ),
        )
        assertTrue(blockReason.contains("officialEnvironmentBlockReason()?.let { return it }"))
        assertTrue(
            blockReason.indexOf("officialEnvironmentBlockReason()") <
                blockReason.indexOf("missingRequiredWalkSessionPermissions(action)"),
        )
    }

    @Test
    fun sessionIdentityBoundariesInvalidateEnvironmentEvidence() {
        val manualReporterInput = functionBlock("private fun persistReporterUserFromInput()")
        val verifiedActorBinding =
            functionBlock("private fun bindFirstRunVerifiedActorForTraining()")

        assertTrue(
            manualReporterInput.contains(
                "updateNavigationStatus(\"login=blocked manual_reporter_id_disallowed\")",
            ),
        )
        assertFalse(manualReporterInput.contains("reporterUserId ="))
        assertTrue(
            functionBlock("internal fun pauseWalkSafeRuntime()")
                .contains("invalidateOfficialEnvironmentEvidence(\"app_paused\")"),
        )
        assertTrue(
            functionBlock("override fun onDestroy()")
                .contains("invalidateOfficialEnvironmentEvidence(\"app_destroyed\")"),
        )
        assertTrue(
            verifiedActorBinding
                .contains("invalidateOfficialEnvironmentEvidence(\"first_run_actor_bound\")"),
        )
        assertTrue(
            functionBlock("private fun onAccountLogoutClicked()")
                .contains(
                    "invalidateOfficialEnvironmentEvidence(\"priority_user_account_logged_out\")",
                ),
        )
        assertTrue(
            functionBlock("private fun resetPriorityUserTraining()")
                .contains("invalidateOfficialEnvironmentEvidence(\"priority_user_training_reset\")"),
        )
        assertTrue(
            functionBlock("private fun startFreshWalk(")
                .contains("invalidateOfficialEnvironmentEvidence(\"new_walk:\$reason\")"),
        )
        assertTrue(
            functionBlock("private fun enterWalkSessionSafetyStopAndCancelOutputs(")
                .contains("invalidateOfficialEnvironmentEvidence(\"safety_stop:\$reason\")"),
        )
        assertTrue(
            functionBlock("private fun applyRuntimeReadinessIfActive(")
                .contains("invalidateOfficialEnvironmentEvidence(\"runtime_readiness_changed\")"),
        )
        val invalidate = functionBlock("private fun invalidateOfficialEnvironmentEvidence(")
        assertTrue(invalidate.contains("officialEnvironmentUserConfirmation = null"))
        assertTrue(invalidate.contains("officialEnvironmentGpsEvidence = null"))
        assertTrue(invalidate.contains("officialEnvironmentCameraEvidence = null"))
        assertTrue(invalidate.contains("officialEnvironmentRuntimeGuard = null"))
    }

    @Test
    fun runtimeRetrySuppressesOutputsWithoutStoppingMeasurement() {
        val decision = functionBlock("private fun applyOfficialEnvironmentRuntimeDecision(")
        val retry = decision
            .substringAfter("OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY ->")
            .substringBefore("OfficialEnvironmentRuntimeAction.SAFE_STOP ->")
        val terminal = decision.substringAfter("OfficialEnvironmentRuntimeAction.SAFE_STOP ->")

        assertTrue(retry.contains("officialEnvironmentOutputsAllowed = false"))
        assertTrue(retry.contains("reportPrivacyConsentSession.cancelActiveCalls()"))
        assertTrue(retry.contains("scheduleOfficialEnvironmentRuntimeWatchdog"))
        assertTrue(
            retry.indexOf("cancelVoiceCommandRecognition()") <
                retry.indexOf("speakInteraction("),
        )
        assertFalse(retry.contains("cancelWalkSessionOutputs("))
        assertFalse(retry.contains("stopLocationUpdates("))
        assertFalse(retry.contains("stopCameraFallbackSession("))
        assertFalse(retry.contains("stopDepthSession("))
        assertTrue(
            terminal.contains(
                "enterWalkSessionSafetyStopAndCancelOutputs(",
            ),
        )
        assertTrue(
            functionBlock("private fun isCameraFallbackAdvisoryStillDeliverable(")
                .contains("officialEnvironmentOutputsAllowed"),
        )
        assertTrue(
            functionBlock("private fun processReportCandidate(")
                .contains(
                    "if (!officialEnvironmentOutputsAllowed || !phoneMountingOutputsAllowed)",
                ),
        )
        val preflightFrame = functionBlock("private fun handleRuntimeMetricPreflightFrame(")
        assertFalse(preflightFrame.contains("observeOfficialEnvironmentCameraFrame("))
        val frameFailure = functionBlock("private fun handleRuntimeMetricFrameFailure(")
        assertTrue(frameFailure.contains("arSessionGeneration != expectedArSessionGeneration"))
        assertTrue(frameFailure.contains("walkSessionLifecycle.snapshot().epoch != expectedWalkEpoch"))
        val watchdog =
            functionBlock("private fun scheduleOfficialEnvironmentRuntimeWatchdog(")
        assertTrue(watchdog.contains("evidence.observedAtElapsedRealtimeMs"))
        assertTrue(watchdog.contains("evidence.maximumEvidenceAgeMs"))
        assertTrue(
            watchdog.contains(
                "minOf(profile.maximumMeasuredEvidenceAgeMs, evidenceMaximumAgeMs)",
            ),
        )
    }

    @Test
    fun centralSafetyStopClosesBothCameraPipelines() {
        val safetyStop =
            functionBlock("private fun enterWalkSessionSafetyStopAndCancelOutputs(")

        assertInOrder(
            safetyStop,
            "invalidateOfficialEnvironmentEvidence(\"safety_stop:\$reason\")",
            "cancelWalkSessionOutputs(reason)",
            "stopCameraFallbackSession(updateUi = false)",
            "stopDepthSession(closeSession = true)",
        )
    }

    @Test
    fun environmentStatusExposesCauseAndNextActionAccessibly() {
        val statusView = source.substringAfter(
            "officialEnvironmentStatusText = TextView(this).apply",
        ).substringBefore("startupCapabilityText = TextView(this).apply")
        val overlay = source.substringAfter("val overlay = LinearLayout(this).apply")
            .substringBefore("val controlsScroll = ScrollView(this).apply")
        val readiness = source
            .substringAfter("walkReadinessControls = LinearLayout(this).apply")
            .substringBefore("walkLastResultText = TextView(this).apply")
        val update = functionBlock("private fun updateOfficialEnvironmentUi()")
        val message = functionBlock("private fun officialEnvironmentStatusMessage(")
        val detail = functionBlock("private fun officialEnvironmentMeasurementDetail(")
        val guidance = functionBlock("private fun updatePrewalkGuidance(")

        assertTrue(statusView.contains("View.ACCESSIBILITY_LIVE_REGION_NONE"))
        assertTrue(update.contains("officialEnvironmentStatusText.contentDescription = message"))
        assertTrue(update.contains("officialEnvironmentStatusText.text.toString() != message"))
        assertTrue(update.contains("!officialEnvironmentCameraPreflightActive"))
        assertTrue(update.contains("위치·카메라 자동 점검 중"))
        assertTrue(update.contains("activeOfficialEnvironmentProfile != null"))
        assertTrue(update.contains("승인된 환경 프로필 없음"))
        assertTrue(message.contains("원인:"))
        assertTrue(message.contains("다음 행동:"))
        assertTrue(detail.contains("GPS_ACCURACY_OUTSIDE_APPROVED_RANGE"))
        assertTrue(detail.contains("현재 정확도"))
        assertTrue(detail.contains("CAMERA_STABILIZING"))
        assertTrue(guidance.contains("ACTION_ACCESSIBILITY_FOCUS"))
        assertFalse(guidance.contains("requestFocus()"))
        assertFalse(guidance.contains("announceForAccessibility("))
        assertTrue(guidance.contains("환경과 장착 점검 통과. 보행 시작 확인"))
        assertInOrder(
            readiness,
            "addView(priorityUserOnboardingControls)",
            "addView(officialEnvironmentStatusText)",
            "addView(officialEnvironmentConfirmButton)",
            "addView(startupCapabilityText)",
        )
        assertTrue(overlay.contains("addView(walkReadinessControls)"))
    }

    @Test
    fun prewalkCameraAndMountingSensorsProduceAllRequiredMeasurements() {
        val confirmation = functionBlock("private fun confirmOfficialEnvironmentConditions()")
        val start = functionBlock("private fun startOfficialEnvironmentCameraPreflight(")
        val current = functionBlock("private fun isOfficialEnvironmentCameraPreflightCurrent(")
        val measurement = functionBlock("private fun cameraFrameQualityObservation(")
        val invalidate = functionBlock("private fun invalidateOfficialEnvironmentEvidence(")
        val activation = functionBlock("private fun activateWalkSessionRuntime()")
        val startAfterRelease =
            functionBlock("private fun startWalkSessionRuntimeAfterCameraRelease(")

        assertTrue(confirmation.contains("startOfficialEnvironmentCameraPreflight(snapshot.epoch)"))
        assertTrue(start.contains("phoneMountingSensorProbe.start()"))
        assertTrue(start.contains("ProcessCameraProvider.getInstance(this)"))
        assertTrue(start.contains("ImageAnalysis.Builder()"))
        assertTrue(
            start.indexOf("cameraFallbackAnalysis = analysis") <
                start.indexOf("provider.bindToLifecycle("),
        )
        assertTrue(start.contains("imageProxy.planes.firstOrNull()"))
        assertTrue(start.contains("cameraFrameQualityObservation("))
        assertTrue(start.contains("expectedCameraPreflightGeneration = generation"))
        assertTrue(start.contains("observedAtElapsedRealtimeMs = nowMs"))
        assertFalse(start.contains("imageProxy.imageInfo.timestamp / NANOS_PER_MILLISECOND"))
        assertTrue(current.contains("generation == officialEnvironmentCameraPreflightGeneration"))
        assertTrue(current.contains("walkSessionLifecycle.snapshot().epoch == epoch"))
        assertTrue(measurement.contains("CameraLumaMeasurementPolicy.measure("))
        assertTrue(
            measurement.contains(
                "phoneMountingSensorProbe.latestNow()",
            ),
        )
        assertTrue(measurement.contains("normalizedBrightness = luma?.normalizedBrightness"))
        assertTrue(measurement.contains("occludedFraction = luma?.darkOrOccludedFraction"))
        assertTrue(
            measurement.contains(
                "angularShakeDegreesPerSecond = mounting?.angularShakeDegreesPerSecond",
            ),
        )
        assertTrue(
            measurement.contains(
                "mountPitchDegrees = mounting?.cameraPitchFromHorizontalDegrees",
            ),
        )
        assertTrue(invalidate.contains("stopOfficialEnvironmentCameraPreflight()"))
        assertTrue(invalidate.contains("phoneMountingSensorProbe.stop()"))
        assertTrue(
            activation.indexOf(
                "stopOfficialEnvironmentCameraPreflight(",
            ) <
                activation.indexOf("startWalkSessionRuntimeAfterCameraRelease(runtimeEpoch)"),
        )
        assertTrue(startAfterRelease.contains("ensurePermissionsThenStart()"))
    }

    @Test
    fun debugPrewalkCameraLogContainsRawMeasurementsThresholdsAndDecision() {
        val observation = functionBlock("private fun observeOfficialEnvironmentCameraFrame(")

        assertTrue(
            observation.contains("BuildConfig.DEBUG && expectedCameraPreflightGeneration != null"),
        )
        assertTrue(observation.contains("CAMERA_PREFLIGHT_LOG_TAG"))
        assertTrue(observation.contains("brightness=${'$'}{observation.normalizedBrightness}"))
        assertTrue(observation.contains("occludedFraction=${'$'}{observation.occludedFraction}"))
        assertTrue(
            observation.contains(
                "shakeDegreesPerSecond=${'$'}{observation.angularShakeDegreesPerSecond}",
            ),
        )
        assertTrue(observation.contains("mountPitchDegrees=${'$'}{observation.mountPitchDegrees}"))
        assertTrue(observation.contains("status=${'$'}{cameraAssessment.status}"))
        assertTrue(observation.contains("reason=${'$'}{cameraAssessment.reason}"))
    }

    @Test
    fun prewalkRetryFencesOldFramesAndReleasesOnlyOwnedCameraResources() {
        val confirmation = functionBlock("private fun confirmOfficialEnvironmentConditions()")
        val mounting = functionBlock("private fun confirmPhoneMounting(")
        val observer = functionBlock("private fun observeOfficialEnvironmentCameraFrame(")
        val stop = functionBlock("private fun stopOfficialEnvironmentCameraPreflight(")

        assertTrue(confirmation.contains("officialEnvironmentCameraCleanupPending"))
        assertTrue(confirmation.contains("onReleased = ::confirmOfficialEnvironmentConditions"))
        assertTrue(observer.contains("expectedCameraPreflightGeneration"))
        assertTrue(observer.contains("isOfficialEnvironmentCameraPreflightCurrent("))
        assertTrue(observer.contains("previousObservedAtMs"))
        assertTrue(observer.contains("val commit = synchronized(phoneMountingObservationLock)"))
        assertTrue(observer.contains("candidateObservedAtMs < previousObservedAtMs"))
        assertTrue(observer.contains("officialEnvironmentCameraStabilityGate.observe("))
        assertTrue(observer.contains("CameraPreflightStabilityDecision.RETAIN_PREVIOUS_PASS"))
        assertTrue(observer.contains("CAMERA_STABILIZING"))
        assertTrue(observer.contains("officialEnvironmentCameraEvidence = evidence"))
        assertTrue(
            observer.indexOf("val commit = synchronized(phoneMountingObservationLock)") <
                observer.indexOf("officialEnvironmentCameraEvidence = evidence"),
        )
        assertTrue(stop.contains("synchronized(phoneMountingObservationLock)"))
        assertTrue(stop.contains("officialEnvironmentCameraPreflightActive = false"))
        assertTrue(stop.contains("officialEnvironmentCameraPreflightGeneration += 1L"))
        assertTrue(stop.contains("unbind(ownedAnalysis)"))
        assertTrue(stop.contains("detectorExecutor.execute"))
        assertTrue(stop.contains("officialEnvironmentAfterCameraRelease"))
        assertFalse(stop.contains("unbindAll()"))
        assertTrue(stop.contains("phoneMountingSensorProbe.stop()"))
        assertTrue(mounting.contains("onReleased = { confirmPhoneMounting(method) }"))
        assertInOrder(
            mounting.substringAfter("onReleased = { confirmPhoneMounting(method) }"),
            "return",
            "resetPrewalkCameraEvidenceForNewAttempt()",
            "val confirmedAtMs",
        )
    }

    @Test
    fun activeLocationPermissionRevocationStopsOnlyLocationFeatures() {
        val permissionChange =
            functionBlock("private fun applyObservedPermissionStateChange(")

        assertTrue(permissionChange.contains("stopLocationUpdates()"))
        val locationRevocation = permissionChange.substringAfter(
            "if (!hasLocationPermission() || !isLocationServiceEnabledForDeviceCheck())",
        ).substringBefore("if (!hasHandsFreeNotificationPermission())")
        assertFalse(locationRevocation.contains("stopStepTracking()"))
        assertTrue(permissionChange.contains("navigationRequests.cancelRoute()"))
        assertTrue(permissionChange.contains("resetRouteState()"))
        assertTrue(permissionChange.contains("해당 기능만 중지하고 나머지 기능은 계속 사용합니다."))
        assertFalse(permissionChange.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        assertFalse(permissionChange.contains("WalkSessionEvent.Resume"))
    }

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "missing function: $signature" }
        var braceDepth = 0
        var sawOpeningBrace = false
        for (index in start until source.length) {
            when (source[index]) {
                '{' -> {
                    braceDepth += 1
                    sawOpeningBrace = true
                }
                '}' -> if (sawOpeningBrace) {
                    braceDepth -= 1
                    if (braceDepth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("unterminated function: $signature")
    }

    private fun assertInOrder(text: String, vararg fragments: String) {
        var previous = -1
        fragments.forEach { fragment ->
            val index = text.indexOf(fragment)
            assertTrue("missing or out of order: $fragment", index > previous)
            previous = index
        }
    }
}

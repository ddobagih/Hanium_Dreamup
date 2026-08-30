package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityStartupCapabilityStaticTest {
    private val activity = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val probe = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/device/AndroidStartupCapabilityProbe.kt",
    ).readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val strings = File("src/main/res/values/strings.xml").readText()
    private val capability = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/device/WalkSafeStartupCapability.kt",
    ).readText()

    @Test
    fun launcherAndFirstScreenUseTheApprovedProductIdentityAndOrdering() {
        assertTrue(manifest.contains("android:label=\"@string/app_name\""))
        assertTrue(strings.contains("WalkSafe(워크세이프)"))
        assertTrue(capability.contains("시각장애인의 도심 보행"))
        assertTrue(capability.contains("안드로이드 보행 보조 서비스"))

        // 준비 표면은 walkReadinessControls 한 덩어리로 묶여 오버레이에 들어간다. 화면에 나오는
        // 순서는 목적 고지 → 준비 표면 → 보행 화면이고, 덩어리 안에서 점검과 확인의 순서가 유지된다.
        val overlay = activity.substringAfter("val overlay = LinearLayout(this).apply")
            .substringBefore("val controlsScroll = ScrollView(this).apply")
        val purpose = overlay.indexOf("addView(productPurposeText)")
        val readiness = overlay.indexOf("addView(walkReadinessControls)")
        val runtime = overlay.indexOf("addView(runtimeControls)")
        assertTrue(purpose >= 0)
        assertTrue(purpose < readiness)
        assertTrue(readiness < runtime)

        val readinessBlock = activity
            .substringAfter("walkReadinessControls = LinearLayout(this).apply")
            .substringBefore("\n        }\n")
        val capability = readinessBlock.indexOf("addView(startupCapabilityText)")
        val confirmation = readinessBlock.indexOf("addView(startupCapabilityConfirmButton)")
        assertTrue(capability >= 0)
        assertTrue(capability < confirmation)
    }

    @Test
    fun capabilityProbeChecksEveryRequiredDeviceFunctionFailClosed() {
        assertTrue(probe.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.S"))
        assertTrue(probe.contains("PackageManager.FEATURE_CAMERA_ANY"))
        assertTrue(probe.contains("PackageManager.FEATURE_LOCATION_GPS"))
        assertTrue(probe.contains("PackageManager.FEATURE_MICROPHONE"))
        assertTrue(probe.contains("hasVibrator() == true"))
        assertTrue(probe.contains("SpeechRecognizer.isOnDeviceRecognitionAvailable"))
        assertTrue(probe.contains("!voice.isNetworkConnectionRequired"))
        assertTrue(probe.contains("ARCORE_DEPTH_FEATURE"))
        assertFalse(probe.contains("-> packageManager.hasSystemFeature(ARCORE_DEPTH_FEATURE)"))
        assertTrue(probe.contains("live session proves stable metric frames"))
        assertTrue(probe.contains("ApprovedDeviceProfileMatcher.match"))
        assertTrue(probe.contains("approvedDesignatedDeviceProfile = approvedDeviceProfileMatch.approved"))
        assertTrue(probe.contains("designatedDeviceProfileVersion = approvedDeviceProfileMatch.profileVersion"))
        assertTrue(capability.contains("APPROVED_DEVICE_PROFILE"))
        assertTrue(capability.contains("designatedDeviceProfileVersion"))
    }

    @Test
    fun noRuntimeStartIsEnabledBeforeTheExactCapabilityDecisionIsConfirmed() {
        val permissionStart = activity.substringAfter("private fun ensurePermissionsThenStart()")
            .substringBefore("private fun currentReporterUserId()")
        val depthStart = activity.substringAfter("private fun startDepthSession()")
            .substringBefore("private fun requestArCoreAvailabilityRecheck(")
        val actionGate = activity.substringAfter("private fun applyActionButtonState()")
            .substringBefore("private fun openAppSettings()")

        assertTrue(permissionStart.contains("if (!requireStartupCapabilityConfirmation()) return"))
        assertTrue(depthStart.contains("if (!requireStartupCapabilityConfirmation()) return"))
        assertTrue(actionGate.contains("isStartupCapabilityConfirmed()"))
        assertTrue(
            activity.contains(
                "firstRunOnboardingComplete() &&\n" +
                    "                confirmed &&\n" +
                    "                isWalkSessionRuntimeActive()",
            ),
        )
        assertFalse(activity.contains("confirmedStartupCapabilityDecision = decision\n        startDepthSession()"))
    }

    @Test
    fun runtimeDistanceDowngradeRequiresLimitedDecisionBeforeExistingFallbackStarts() {
        val fallbackRequest = activity.substringAfter("private fun requestCameraFallbackStart(")
            .substringBefore("private fun startCameraFallbackSession(")
        val arCoreStart = activity.substringAfter("private fun continueDepthSessionStart(")
            .substringBefore("private fun stopDepthSession(")

        assertTrue(fallbackRequest.contains("metricDistanceCapabilityOverride = false"))
        assertTrue(fallbackRequest.contains("refreshStartupCapabilityUi()"))
        assertTrue(fallbackRequest.contains("if (isStartupCapabilityConfirmed())"))
        assertTrue(fallbackRequest.contains("WALKSAFE_LIMITED_DISTANCE_NOTICE_KO"))
        assertTrue(arCoreStart.contains("requestCameraFallbackStart("))
        assertFalse(arCoreStart.contains("startCameraFallbackSession("))
    }

    @Test
    fun voiceCommandsUseOnlyTheCapabilityCheckedOnDeviceRecognizer() {
        val voiceStart = activity.substringAfter("private fun startVoiceCommandRecognition()")
            .substringBefore("private fun cancelVoiceCommandRecognition()")

        assertTrue(activity.contains("SpeechRecognizer.isOnDeviceRecognitionAvailable"))
        assertTrue(activity.contains("SpeechRecognizer.createOnDeviceSpeechRecognizer"))
        assertFalse(activity.contains("SpeechRecognizer.createSpeechRecognizer"))
        assertTrue(voiceStart.contains("if (!requireStartupCapabilityConfirmation()) return"))
    }

    @Test
    fun limitationMustBeDeliveredBeforeConfirmationAndResultIsPersistedWithProvenance() {
        val confirmation = activity.substringAfter("private fun confirmStartupCapabilityDecision()")
            .substringBefore("private fun persistStartupCapabilityDecision(")
        val completion = activity.substringAfter("private fun completeStartupCapabilityConfirmation(")
            .substringBefore("private fun failStartupCapabilityConfirmation(")
        val persistence = activity.substringAfter("private fun persistStartupCapabilityDecision(")
            .substringBefore("private fun isStartupCapabilityConfirmed()")

        assertTrue(confirmation.contains("deliverStartupCapabilityNotice("))
        assertTrue(confirmation.contains("onDelivered = { completeStartupCapabilityConfirmation(decision) }"))
        assertFalse(confirmation.contains("confirmedStartupCapabilityDecision = decision"))
        assertTrue(completion.contains("confirmedStartupCapabilityDecision = expected"))
        assertTrue(activity.contains("startupCapabilityConfirmationPending"))
        assertTrue(activity.contains("onCompleted = deliveredOnMain"))
        assertTrue(persistence.contains("startup_capability_tier"))
        assertTrue(persistence.contains("startup_capability_device_model"))
        assertTrue(persistence.contains("startup_capability_android_version"))
        assertTrue(persistence.contains("startup_capability_app_version"))
        assertTrue(persistence.contains("startup_capability_source_commit"))
        assertTrue(persistence.contains("startup_capability_device_profile_status"))
        assertTrue(persistence.contains("startup_capability_device_profile_version"))
        assertTrue(persistence.contains("startup_capability_evaluated_at_epoch_ms"))
    }

    @Test
    fun runtimeSpeechFailureInvalidatesConfirmationAndStopsWalkingOutputs() {
        val failure = activity.substringAfter("private fun handleRuntimeSpeechCapabilityFailure(")
            .substringBefore("private fun feedbackActuatorStatusText()")
        val safetyStop = activity.substringAfter("private fun enterWalkSessionSafetyStopAndCancelOutputs(")
            .substringBefore("private fun handleRuntimeSpeechCapabilityFailure(")

        assertTrue(failure.contains("confirmedStartupCapabilityDecision = null"))
        assertTrue(failure.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        assertTrue(failure.contains("stopCameraFallbackSession(updateUi = false)"))
        assertTrue(failure.contains("stopDepthSession(closeSession = true)"))
        assertTrue(safetyStop.contains("resetRouteState()"))
        assertTrue(safetyStop.contains("stopLocationUpdates()"))
        assertTrue(safetyStop.contains("stopStepTracking()"))
        assertTrue(safetyStop.contains("earthOrientationTracker.stop()"))
        assertTrue(failure.contains("refreshStartupCapabilityUi()"))

        val navigationStart = activity.substringAfter("private fun startNavigationServicesIfNeeded()")
            .substringBefore("private fun missingCameraPermissions()")
        val navigationGate = activity.substringAfter("private fun currentNavigationCollectionAllowsWork()")
            .substringBefore("private fun missingCameraPermissions()")
        val locationUpdate = activity.substringAfter("private fun handleLocationUpdate(")
            .substringBefore("private fun updateHeadingFromLocation(")
        assertTrue(navigationStart.contains("if (!currentNavigationCollectionAllowsWork())"))
        assertTrue(navigationGate.contains("if (!isStartupCapabilityConfirmed()) return false"))
        assertTrue(navigationGate.contains("currentRuntimeMetricOutputAllowsWork()"))
        assertTrue(locationUpdate.contains("!currentNavigationCollectionAllowsWork()"))
    }

    @Test
    fun structuralSpeechRecognitionErrorsFailClosedButNoMatchCanRetry() {
        val listener = activity.substringAfter("private fun buildVoiceCommandRecognitionListener(")
            .substringBefore("private fun handleVoiceCommandPhrases(")

        assertTrue(listener.contains("SpeechRecognizer.ERROR_NO_MATCH"))
        assertTrue(listener.contains("SpeechRecognizer.ERROR_SPEECH_TIMEOUT"))
        assertTrue(listener.contains("speechRecognizer?.destroy()"))
        assertTrue(listener.contains("handleRuntimeSpeechCapabilityFailure("))
        assertTrue(listener.contains("WalkSafeStartupRequirement.ON_DEVICE_STT"))
    }
}

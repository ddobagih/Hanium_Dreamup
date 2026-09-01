package kr.co.hanium.dreamup.walksafe

import android.speech.SpeechRecognizer
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

        val overlay = activity.substringAfter("val overlay = LinearLayout(this).apply")
            .substringBefore("val controlsScroll = ScrollView(this).apply")
        val readiness = activity
            .substringAfter("walkReadinessControls = LinearLayout(this).apply")
            .substringBefore("walkLastResultText = TextView(this).apply")
        val purpose = overlay.indexOf("addView(productPurposeText)")
        val readinessGroup = overlay.indexOf("addView(walkReadinessControls)")
        val runtime = overlay.indexOf("addView(runtimeControls)")
        val capability = readiness.indexOf("addView(startupCapabilityText)")
        val confirmation = readiness.indexOf("addView(startupCapabilityConfirmButton)")
        assertTrue(purpose >= 0)
        assertTrue(purpose < readinessGroup)
        assertTrue(readinessGroup < runtime)
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
        assertTrue(probe.contains("BundledVoskModelInstaller.bundledModelAvailable"))
        assertTrue(probe.contains("AndroidKoreanTextToSpeechSynthesisProbe"))
        assertFalse(probe.contains("SpeechRecognizer.isOnDeviceRecognitionAvailable"))
        assertFalse(probe.contains("voice.isNetworkConnectionRequired"))
        assertTrue(probe.contains("ARCORE_DEPTH_FEATURE"))
        assertFalse(probe.contains("-> packageManager.hasSystemFeature(ARCORE_DEPTH_FEATURE)"))
        assertTrue(probe.contains("live session proves stable metric frames on this device"))
        assertTrue(probe.contains("ApprovedDeviceProfileMatcher.match"))
        assertTrue(probe.contains("approvedDesignatedDeviceProfile = approvedDeviceProfileMatch.approved"))
        assertTrue(probe.contains("designatedDeviceProfileVersion = approvedDeviceProfileMatch.profileVersion"))
        assertTrue(activity.contains("approvedDeviceProfileRequired = false"))
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
        val automaticStart =
            activity.substringAfter("private fun maybeAdvanceWalkSessionAfterCapabilityCheck()")
                .substringBefore("private fun requestGatewayWalkStart(")

        assertTrue(permissionStart.contains("if (!requireStartupCapabilityConfirmation()) return"))
        assertTrue(depthStart.contains("if (!requireStartupCapabilityConfirmation()) return"))
        assertTrue(actionGate.contains("isStartupCapabilityConfirmed()"))
        assertTrue(automaticStart.contains("if (!isStartupCapabilityConfirmed())"))
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
        assertTrue(
            fallbackRequest.contains(
                "if (automaticTransitionReady || isStartupCapabilityConfirmed())",
            ),
        )
        assertTrue(fallbackRequest.contains("WALKSAFE_LIMITED_DISTANCE_NOTICE_KO"))
        assertTrue(arCoreStart.contains("requestCameraFallbackStart("))
        assertFalse(arCoreStart.contains("startCameraFallbackSession("))
    }

    @Test
    fun voiceCommandsUseOnlyTheCapabilityCheckedOnDeviceRecognizer() {
        val voiceStart = ReportStaticSourceInspector.functionBlock(
            activity,
            "private fun startVoiceCommandRecognition",
        )

        assertTrue(activity.contains("SpeechRecognizer.isOnDeviceRecognitionAvailable"))
        assertTrue(activity.contains("SpeechRecognizer.createOnDeviceSpeechRecognizer"))
        assertFalse(activity.contains("SpeechRecognizer.createSpeechRecognizer"))
        assertFalse(voiceStart.contains("postLoginDeviceFeatureEnabled"))
        assertFalse(voiceStart.contains("WalkSafeStartupRequirement.ON_DEVICE_STT"))
        assertTrue(voiceStart.contains("if (!hasRecordAudioPermission())"))
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                voiceStart,
                "SpeechRecognizer.isOnDeviceRecognitionAvailable",
                "stopHandsFreeVoiceService()",
            ),
        )
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
    fun runtimeSpeechFailureRestrictsOnlyTheRelatedVoiceFeature() {
        val failure = activity.substringAfter("private fun handleRuntimeSpeechCapabilityFailure(")
            .substringBefore("private fun feedbackActuatorStatusText()")
        val oneShotFailure = ReportStaticSourceInspector.functionBlock(
            activity,
            "private fun handleOneShotSpeechRecognitionFailure",
        )

        assertTrue(failure.contains("offlineKoreanTextToSpeechCapabilityOverride = false"))
        assertFalse(failure.contains("onDeviceSpeechRecognitionCapabilityOverride = false"))
        assertFalse(failure.contains("WalkSafeStartupRequirement.ON_DEVICE_STT"))
        assertTrue(failure.contains("화면과 사용 가능한 다른 기능은 계속 사용할 수 있습니다."))
        assertFalse(failure.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        assertFalse(failure.contains("stopDepthSession("))
        assertFalse(failure.contains("stopLocationUpdates()"))
        assertTrue(failure.contains("refreshStartupCapabilityUi()"))
        assertTrue(oneShotFailure.contains("oneShotSpeechRecognitionLimited = true"))
        assertFalse(oneShotFailure.contains("onDeviceSpeechRecognitionCapabilityOverride"))
        assertFalse(oneShotFailure.contains("stopHandsFreeVoiceService()"))
        assertFalse(oneShotFailure.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        assertTrue(oneShotFailure.contains("scheduleHandsFreeVoiceRestart()"))

        val navigationStart = activity.substringAfter("private fun startNavigationServicesIfNeeded()")
            .substringBefore("private fun missingCameraPermissions()")
        val navigationGate = activity.substringAfter("private fun currentNavigationCollectionAllowsWork()")
            .substringBefore("private fun missingCameraPermissions()")
        val stepTrackingGate = activity.substringAfter(
            "private fun currentStepTrackingCollectionAllowsWork()",
        ).substringBefore("private fun currentNavigationCollectionAllowsWork()")
        val locationUpdate = activity.substringAfter("private fun handleLocationUpdate(")
            .substringBefore("private fun updateHeadingFromLocation(")
        assertTrue(navigationStart.contains("if (!currentStepTrackingCollectionAllowsWork())"))
        assertTrue(stepTrackingGate.contains("if (!isStartupCapabilityConfirmed()) return false"))
        assertTrue(
            navigationGate.contains(
                "postLoginDeviceFeatureEnabled(PostLoginDeviceCheckFeature.LOCATION_GUIDANCE)",
            ),
        )
        assertFalse(navigationGate.contains("currentRuntimeMetricOutputAllowsWork()"))
        assertTrue(locationUpdate.contains("!currentNavigationCollectionAllowsWork()"))
    }

    @Test
    fun structuralSpeechRecognitionErrorsFailClosedButNoMatchCanRetry() {
        val listener = activity.substringAfter("private fun buildVoiceCommandRecognitionListener(")
            .substringBefore("private fun handleVoiceCommandPhrases(")

        assertTrue(listener.contains("SpeechRecognizer.ERROR_NO_MATCH"))
        assertTrue(listener.contains("SpeechRecognizer.ERROR_SPEECH_TIMEOUT"))
        assertTrue(listener.contains("speechRecognizer?.destroy()"))
        assertTrue(listener.contains("handleOneShotSpeechRecognitionFailure("))
        assertFalse(listener.contains("onDeviceSpeechRecognitionCapabilityOverride"))
        assertTrue(
            listener.contains(
                "VoiceRecognitionPurpose.WALK_SESSION_RESUME ->\n" +
                    "                        handleWalkSessionResumeRecognizerNotStarted()",
            ),
        )
        assertTrue(
            listener.contains(
                "permanentlyLimit = " +
                    "shouldPermanentlyLimitOneShotSpeechRecognition(error)",
            ),
        )
    }

    @Test
    fun onlyARecognizerLanguageContractFailureIsSticky() {
        assertTrue(
            shouldPermanentlyLimitOneShotSpeechRecognition(
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            ),
        )
        listOf(
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            Int.MIN_VALUE,
        ).forEach { error ->
            assertFalse(shouldPermanentlyLimitOneShotSpeechRecognition(error))
        }

        val voiceStart = ReportStaticSourceInspector.functionBlock(
            activity,
            "private fun startVoiceCommandRecognition",
        )
        assertTrue(voiceStart.contains("permanentlyLimit = true"))
        assertTrue(voiceStart.contains("permanentlyLimit = false"))
        assertFalse(
            voiceStart.substringAfter("SpeechRecognizer.createOnDeviceSpeechRecognizer")
                .substringBefore("recognizer.setRecognitionListener")
                .contains("permanentlyLimit = true"),
        )
    }
}

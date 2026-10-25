package kr.co.hanium.dreamup.walksafe.ui

import kr.co.hanium.dreamup.walksafe.device.DeviceCheckLocationFixReason
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckFailure
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckFeature
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckObservation
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckSignal
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckState
import kr.co.hanium.dreamup.walksafe.device.PostLoginMetricDepthState
import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupCapabilityTier
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingStage
import kr.co.hanium.dreamup.walksafe.session.WalkSessionRecoveryStage
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCheckPresenterTest {
    @Test
    fun unstartedAndPermissionObservationDoNotRequireProbeFacts() {
        assertEquals("항목별 상태: 점검 시작 전", DeviceCheckPresenter.postLoginDeviceCheckItemsMessage(DeviceCheckPresenter.ItemsFacts(PostLoginDeviceCheckState.NOT_RUN)))
        assertEquals("항목별 상태: Android 권한 확인 중", DeviceCheckPresenter.postLoginDeviceCheckItemsMessage(DeviceCheckPresenter.ItemsFacts(PostLoginDeviceCheckState.REQUESTING_PERMISSIONS)))
    }

    @Test
    fun unavailableSignalsOverridePendingAndKeepPerFactorMessages() {
        val facts = DeviceCheckPresenter.ItemsFacts(
            state = PostLoginDeviceCheckState.RUNNING,
            observation = observation().copy(cameraPipeline = PostLoginDeviceCheckSignal.UNAVAILABLE),
            microphoneAvailable = true, gpsAvailable = true, cameraAvailable = true,
            microphonePermission = false, locationPermission = false, cameraPermission = true,
            cameraPipelineActive = true,
        )
        val text = DeviceCheckPresenter.postLoginDeviceCheckItemsMessage(facts)
        assertTrue(text.contains("카메라\t제한"))
        assertTrue(text.contains("위치\t제한(정확한 위치 권한 필요)"))
        assertTrue(text.contains("호출어\t제한(마이크 권한 필요)"))
        assertTrue(text.contains("ARCore Depth\t지원 확인 중"))
    }

    @Test
    fun terminalCameraAndDepthEvidenceTakePrecedenceOverDeferredChecks() {
        val facts = DeviceCheckPresenter.ItemsFacts(
            state = PostLoginDeviceCheckState.LIMITED, observation = observation(),
            cameraChecksDeferred = true, terminalCameraText = "카메라 제한", terminalMetricText = "거리 실측 확인",
            resourceWaitNotice = "냉각 대기",
        )
        val text = DeviceCheckPresenter.postLoginDeviceCheckItemsMessage(facts)
        assertTrue(text.contains("카메라\t카메라 제한"))
        assertTrue(text.contains("ARCore Depth\t거리 실측 확인"))
        assertTrue(text.contains("기기 상태\t냉각 대기"))
        assertFalse(text.contains("권한 허용 후 재점검 필요"))
    }

    @Test
    fun disabledFeaturesRemainOrderedAndVoiceCommandRestrictionIsDistinctFromWakeStandby() {
        val facts = DeviceCheckPresenter.DisabledFacts(
            setOf(PostLoginDeviceCheckFeature.HANDS_FREE_VOICE, PostLoginDeviceCheckFeature.LOCATION_GUIDANCE),
            true, false, true, false, false,
        )
        assertEquals("위치·경로 안내, 호출어 대기, 위치가 필요한 신고, 걸음 수 추적·정지 확인 후 대기 신고 자동 전송", DeviceCheckPresenter.postLoginDeviceCheckDisabledFeatureText(facts))
        assertTrue(DeviceCheckPresenter.postLoginDeviceCheckDisabledFeatureText(facts.copy(voiceCommandsUnavailable = true)).contains("호출어·음성 명령"))
    }

    @Test
    fun locationRequestFailureAndMockLocationGiveDifferentRecoveryInstructions() {
        val request = DeviceCheckPresenter.LocationFailureFacts(null, null, true)
        assertTrue(DeviceCheckPresenter.postLoginLocationFixFailureMessage(request).contains("Android 위치 요청을 시작하지 못했습니다"))
        val mock = request.copy(reason = DeviceCheckLocationFixReason.MOCK_LOCATION, requestFailed = false)
        assertTrue(DeviceCheckPresenter.postLoginLocationFixFailureMessage(mock).contains("모의 위치 앱을 끄고"))
        val accuracy = mock.copy(reason = DeviceCheckLocationFixReason.ACCURACY_OUTSIDE_FUNCTIONAL_RANGE, accuracyMeters = 31f)
        assertTrue(DeviceCheckPresenter.postLoginLocationFixFailureMessage(accuracy).contains("약 31미터"))
    }

    @Test
    fun cameraBindAndFrameFailuresAreNotDescribedAsALensCover() {
        val facts = failureFacts().copy(cameraPipelineFailureName = "BIND_FAILED")
        assertEquals("카메라를 열지 못했습니다. 다른 카메라 앱을 닫은 뒤 다시 점검하세요.", DeviceCheckPresenter.postLoginDeviceCheckFailureMessage(PostLoginDeviceCheckFailure.CAMERA_PIPELINE_UNAVAILABLE, facts))
        assertTrue(DeviceCheckPresenter.postLoginDeviceCheckFailureMessage(PostLoginDeviceCheckFailure.CAMERA_PIPELINE_UNAVAILABLE, facts.copy(cameraPipelineFailureName = "NO_VALID_FRAME")).contains("유효한 카메라 영상을 받지 못했습니다"))
        assertEquals("음성 자료 필요", DeviceCheckPresenter.postLoginDeviceCheckFailureMessage(PostLoginDeviceCheckFailure.KOREAN_TTS_UNAVAILABLE, facts.copy(koreanSpeechDetail = "음성 자료 필요")))
    }

    @Test
    fun readyConfirmationStillNeedsItsTokenAndCurrentSafetyFacts() {
        val facts = startup()
        assertTrue(DeviceCheckPresenter.startupPresentation(facts).confirmationAction!!.enabled)
        assertFalse(DeviceCheckPresenter.startupPresentation(facts.copy(confirmation = facts.confirmation.copy(confirmationTokenPresent = false))).confirmationAction!!.enabled)
        assertFalse(DeviceCheckPresenter.startupPresentation(facts.copy(capability = facts.capability.copy(phoneMountingReady = false))).confirmationAction!!.enabled)
        assertFalse(DeviceCheckPresenter.startupPresentation(facts.copy(capability = facts.capability.copy(priorityMayStartWalk = false))).confirmationAction!!.enabled)
    }

    @Test
    fun pausedRecheckUsesTheExistingTokenExceptionAndResumePromptStillBlocksIt() {
        val facts = startup().let { it.copy(confirmation = it.confirmation.copy(walkState = WalkSessionState.PAUSED, recoveryStage = WalkSessionRecoveryStage.RECHECK_REQUIRED, confirmationTokenPresent = false)) }
        val action = DeviceCheckPresenter.startupPresentation(facts).confirmationAction!!
        assertTrue(action.enabled)
        assertEquals("보행 재개 상태 다시 확인", action.text)
        assertFalse(DeviceCheckPresenter.startupPresentation(facts.copy(confirmation = facts.confirmation.copy(resumePromptPending = true))).confirmationAction!!.enabled)
    }

    @Test
    fun explicitResumeKeepsTheMainRendererAsTheOnlyConfirmationControlOwner() {
        val facts = startup().let { it.copy(confirmation = it.confirmation.copy(walkState = WalkSessionState.PAUSED, awaitingExplicitResume = true)) }
        val result = DeviceCheckPresenter.startupPresentation(facts)
        assertTrue(result.useExplicitResumeControl)
        assertNull(result.confirmationAction)
        val newWalk = DeviceCheckPresenter.startupPresentation(facts.copy(confirmation = facts.confirmation.copy(walkState = WalkSessionState.ENDED)))
        assertFalse(newWalk.useExplicitResumeControl)
        assertEquals("새 보행 준비", newWalk.confirmationAction!!.text)
    }

    @Test
    fun runningChecksCannotBeRestartedAndOwnedWakeProbeCannotBeStartedAgain() {
        val facts = startup().let { it.copy(check = it.check.copy(state = PostLoginDeviceCheckState.RUNNING, passesFeatureGate = false), wake = it.wake.copy(probeActive = true)) }
        val result = DeviceCheckPresenter.startupPresentation(facts)
        assertTrue(result.deviceAction.visible)
        assertFalse(result.deviceAction.enabled)
        assertTrue(result.wakeInstructionVisible)
        assertTrue(result.wakeAction.visible)
        assertFalse(result.wakeAction.enabled)
        assertEquals("길라잡이 호출어 듣는 중", result.wakeAction.text)
    }

    @Test
    fun locationFailuresPreserveTheSpecificSettingsTarget() {
        val facts = startup().let { it.copy(check = it.check.copy(state = PostLoginDeviceCheckState.FAIL, failure = PostLoginDeviceCheckFailure.LOCATION_FIX_UNAVAILABLE, passesFeatureGate = false)) }
        assertEquals("Android 개발자 옵션 열기", DeviceCheckPresenter.startupPresentation(facts.copy(settings = facts.settings.copy(locationFixReason = DeviceCheckLocationFixReason.MOCK_LOCATION))).settingsAction.text)
        val requestFailure = DeviceCheckPresenter.startupPresentation(facts.copy(settings = facts.settings.copy(locationRequestFailed = true)))
        assertTrue(requestFailure.settingsAction.visible)
        assertEquals("Android 앱 권한 설정 열기", requestFailure.settingsAction.text)
    }

    @Test
    fun confirmedCapabilityRetainsItsExactNoticeAndDoesNotOfferDuplicateConfirmation() {
        val facts = startup().let { it.copy(capability = it.capability.copy(confirmed = true)) }
        val result = DeviceCheckPresenter.startupPresentation(facts)
        assertFalse(result.confirmationAction!!.enabled)
        assertEquals("목적과 안전 제한 확인 완료", result.confirmationAction!!.text)
        assertEquals("기기 안내\n기기 점검: 모든 기능 통과\n항목별 상태\n확인 완료: WalkSafe 기능을 시작할 수 있습니다.\n교육 안내", result.capabilityMessage)
    }

    private fun failureFacts() = DeviceCheckPresenter.FailureFacts(null, DeviceCheckPresenter.LocationFailureFacts(null, null, false), null, null)

    private fun startup() = DeviceCheckPresenter.StartupFacts(
        capability = DeviceCheckPresenter.CapabilityFacts("기기 안내", WalkSafeStartupCapabilityTier.FULL, true, false, false, emptyList(), "교육 안내", true, true, true),
        check = DeviceCheckPresenter.CheckFacts(PostLoginDeviceCheckState.FULL, null, true, false, "", "항목별 상태", "", true, FirstRunOnboardingStage.DEVICE_CHECK, false, false),
        settings = DeviceCheckPresenter.SettingsFacts(false, true, false, null, null),
        wake = DeviceCheckPresenter.WakeFacts(PostLoginDeviceCheckSignal.PENDING, true, false, false),
        confirmation = DeviceCheckPresenter.ConfirmationFacts(WalkSessionState.READY, null, true, false, true, false, false, false, false),
    )

    private fun observation() = PostLoginDeviceCheckObservation(
        minimumAndroidVersion = PostLoginDeviceCheckSignal.READY,
        requiredPermissions = PostLoginDeviceCheckSignal.READY,
        voiceDisclosure = PostLoginDeviceCheckSignal.READY,
        coreHardwareAndServices = PostLoginDeviceCheckSignal.READY,
        locationService = PostLoginDeviceCheckSignal.READY,
        locationFix = PostLoginDeviceCheckSignal.PENDING,
        deviceResources = PostLoginDeviceCheckSignal.PENDING,
        detector = PostLoginDeviceCheckSignal.PENDING,
        cameraPipeline = PostLoginDeviceCheckSignal.PENDING,
        koreanTextToSpeech = PostLoginDeviceCheckSignal.PENDING,
        wakePhraseRecognition = PostLoginDeviceCheckSignal.PENDING,
        metricDepth = PostLoginMetricDepthState.PENDING,
    )
}

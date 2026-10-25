package kr.co.hanium.dreamup.walksafe.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PriorityUserTrainingOutputCapabilityTest {
    @Test
    fun pendingCameraDistanceDoesNotBlockVerifiedTrainingOutputsOrOpenWalking() {
        val decision = decision(pending = listOf(WalkSafeStartupRequirement.METRIC_DISTANCE))
        assertTrue(decision.allowsTrainingOutput(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS))
        assertTrue(decision.allowsTrainingOutput(WalkSafeStartupRequirement.VIBRATION, vibrationAvailable = true))
        assertFalse(decision.mayConfirmAndStart)
        assertFalse(decision.allowsTrainingOutput(WalkSafeStartupRequirement.METRIC_DISTANCE))
    }

    @Test
    fun pendingOrFailedOutputStillCannotBeUsedAndDoesNotBlockTheOtherOutput() {
        for (output in listOf(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS, WalkSafeStartupRequirement.VIBRATION)) {
            val other = if (output == WalkSafeStartupRequirement.VIBRATION)
                WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS else WalkSafeStartupRequirement.VIBRATION
            for (decision in listOf(decision(pending = listOf(output)), decision(unavailable = listOf(output)))) {
                assertFalse(decision.allowsTrainingOutput(output, vibrationAvailable = true))
                assertTrue(decision.allowsTrainingOutput(other, vibrationAvailable = true))
                assertFalse(decision.mayConfirmAndStart)
            }
        }
    }

    @Test
    fun unsupportedOrPendingPlatformCannotUseTrainingOutputs() {
        for (decision in listOf(decision(pending = listOf(WalkSafeStartupRequirement.ANDROID_VERSION)),
            decision(unavailable = listOf(WalkSafeStartupRequirement.ANDROID_VERSION)))) {
            assertFalse(decision.allowsTrainingOutput(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS))
            assertFalse(decision.allowsTrainingOutput(WalkSafeStartupRequirement.VIBRATION, vibrationAvailable = true))
        }
    }

    @Test
    fun resolverDoesNotReportVibrationSoTrainingRequiresItsActualProbeResult() {
        for (vibration in listOf(null, false, true)) {
            val input = WalkSafeStartupCapabilityInput(
                androidVersionSupported = true,
                cameraAvailable = true,
                gpsAvailable = true,
                microphoneAvailable = true,
                vibrationAvailable = vibration,
                onDeviceSpeechRecognitionAvailable = true,
                offlineKoreanTextToSpeechAvailable = true,
                metricDistanceAvailable = null,
                approvedDesignatedDeviceProfile = true,
                designatedDeviceProfileVersion = "test",
            )
            val decision = WalkSafeStartupCapabilityResolver.resolve(input)
            assertFalse(decision.mayConfirmAndStart)
            assertTrue(decision.allowsTrainingOutput(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS, vibration))
            org.junit.Assert.assertEquals(vibration == true,
                decision.allowsTrainingOutput(WalkSafeStartupRequirement.VIBRATION, vibration))
            assertFalse(decision.allowsTrainingOutput(WalkSafeStartupRequirement.VIBRATION))
        }
    }

    private fun decision(
        pending: List<WalkSafeStartupRequirement> = emptyList(),
        unavailable: List<WalkSafeStartupRequirement> = emptyList(),
    ) = WalkSafeStartupCapabilityDecision(WalkSafeStartupCapabilityTier.BLOCKED, unavailable, pending, "확인 중")
}

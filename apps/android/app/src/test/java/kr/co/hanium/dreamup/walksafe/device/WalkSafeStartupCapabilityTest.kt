package kr.co.hanium.dreamup.walksafe.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkSafeStartupCapabilityTest {
    @Test
    fun allRequiredCapabilitiesYieldFullTier() {
        val decision = WalkSafeStartupCapabilityResolver.resolve(availableInput())

        assertEquals(WalkSafeStartupCapabilityTier.FULL, decision.tier)
        assertTrue(decision.mayConfirmAndStart)
        assertTrue(decision.unavailableRequirements.isEmpty())
        assertTrue(decision.pendingRequirements.isEmpty())
        assertTrue(decision.noticeKo.contains("보행 안전을 보장하지 않습니다"))
    }

    @Test
    fun distanceOnlyFailureYieldsLimitedTierWithExactApprovedNotice() {
        val decision = WalkSafeStartupCapabilityResolver.resolve(
            availableInput().copy(metricDistanceAvailable = false),
        )

        assertEquals(WalkSafeStartupCapabilityTier.LIMITED, decision.tier)
        assertTrue(decision.mayConfirmAndStart)
        assertEquals(WALKSAFE_LIMITED_DISTANCE_NOTICE_KO, decision.noticeKo)
        assertEquals(
            listOf(WalkSafeStartupRequirement.METRIC_DISTANCE),
            decision.unavailableRequirements,
        )
    }

    @Test
    fun metricDistanceCannotYieldFullWithoutAnApprovedVersionedDeviceProfile() {
        val unapproved = WalkSafeStartupCapabilityResolver.resolve(
            availableInput().copy(
                approvedDesignatedDeviceProfile = false,
                designatedDeviceProfileVersion = null,
            ),
        )
        val unversioned = WalkSafeStartupCapabilityResolver.resolve(
            availableInput().copy(designatedDeviceProfileVersion = null),
        )

        assertEquals(WalkSafeStartupCapabilityTier.LIMITED, unapproved.tier)
        assertEquals(
            listOf(WalkSafeStartupRequirement.APPROVED_DEVICE_PROFILE),
            unapproved.unavailableRequirements,
        )
        assertEquals(WalkSafeStartupCapabilityTier.LIMITED, unversioned.tier)
    }

    @Test
    fun everyUnavailableCoreCapabilityBlocksStart() {
        val unavailableInputs = listOf(
            availableInput().copy(androidVersionSupported = false),
            availableInput().copy(cameraAvailable = false),
            availableInput().copy(microphoneAvailable = false),
            availableInput().copy(vibrationAvailable = false),
            availableInput().copy(onDeviceSpeechRecognitionAvailable = false),
            availableInput().copy(offlineKoreanTextToSpeechAvailable = false),
        )

        unavailableInputs.forEach { input ->
            val decision = WalkSafeStartupCapabilityResolver.resolve(input)
            assertEquals(WalkSafeStartupCapabilityTier.BLOCKED, decision.tier)
            assertFalse(decision.mayConfirmAndStart)
            assertTrue(decision.unavailableRequirements.isNotEmpty())
        }
    }

    @Test
    fun unavailableGpsFallsBackToCameraOnlyLimitedModeIsRejected() {
        val decision = WalkSafeStartupCapabilityResolver.resolve(
            availableInput().copy(gpsAvailable = false),
        )

        assertEquals(WalkSafeStartupCapabilityTier.BLOCKED, decision.tier)
        assertFalse(decision.mayConfirmAndStart)
        assertEquals(
            listOf(WalkSafeStartupRequirement.GPS),
            decision.unavailableRequirements,
        )
        assertTrue(decision.noticeKo.contains("GPS 위치"))
    }

    @Test
    fun everyUnknownCapabilityFailsClosedUntilProbeCompletes() {
        val unknownInputs = listOf(
            availableInput().copy(androidVersionSupported = null),
            availableInput().copy(cameraAvailable = null),
            availableInput().copy(gpsAvailable = null),
            availableInput().copy(microphoneAvailable = null),
            availableInput().copy(vibrationAvailable = null),
            availableInput().copy(onDeviceSpeechRecognitionAvailable = null),
            availableInput().copy(offlineKoreanTextToSpeechAvailable = null),
            availableInput().copy(metricDistanceAvailable = null),
            availableInput().copy(approvedDesignatedDeviceProfile = null),
        )

        unknownInputs.forEach { input ->
            val decision = WalkSafeStartupCapabilityResolver.resolve(input)
            assertEquals(WalkSafeStartupCapabilityTier.BLOCKED, decision.tier)
            assertFalse(decision.mayConfirmAndStart)
            assertTrue(decision.pendingRequirements.isNotEmpty())
        }
    }

    @Test
    fun knownCoreFailureIsExplainedWithoutWaitingForOtherProbes() {
        val decision = WalkSafeStartupCapabilityResolver.resolve(
            availableInput().copy(
                cameraAvailable = false,
                offlineKoreanTextToSpeechAvailable = null,
                metricDistanceAvailable = null,
            ),
        )

        assertEquals(WalkSafeStartupCapabilityTier.BLOCKED, decision.tier)
        assertFalse(decision.mayConfirmAndStart)
        assertTrue(decision.noticeKo.contains("카메라"))
        assertTrue(decision.pendingRequirements.isNotEmpty())
    }

    @Test
    fun productNoticeNamesThreePillarsAndSafetyBoundaryWithBaseline() {
        assertEquals(
            "워크세이프는 시각장애인의 도심 보행 중 가까운 위험과 이동 방향을 알려 주고 손상 점자블록 신고를 돕는 안드로이드 보행 보조 서비스입니다.",
            WALKSAFE_PRODUCT_PURPOSE_STATEMENT_KO,
        )
        assertEquals(
            "워크세이프는 보행 안전을 보장하지 않으며 흰지팡이·안내견·보호자를 대신하지 않습니다.",
            WALKSAFE_PRODUCT_SAFETY_LIMITATION_KO,
        )
        assertTrue(WALKSAFE_PRODUCT_PURPOSE_NOTICE_KO.contains(WALKSAFE_DISPLAY_NAME))
        assertTrue(WALKSAFE_PRODUCT_PURPOSE_NOTICE_KO.contains(WALKSAFE_PRODUCT_PURPOSE_STATEMENT_KO))
        assertTrue(WALKSAFE_PRODUCT_PURPOSE_NOTICE_KO.contains(WALKSAFE_PRODUCT_SAFETY_LIMITATION_KO))
        assertTrue(WALKSAFE_PRODUCT_PURPOSE_NOTICE_KO.contains("가까운 위험"))
        assertTrue(WALKSAFE_PRODUCT_PURPOSE_NOTICE_KO.contains("TMAP"))
        assertTrue(WALKSAFE_PRODUCT_PURPOSE_NOTICE_KO.contains("손상된 점자블록 신고"))
        assertTrue(WALKSAFE_PRODUCT_PURPOSE_NOTICE_KO.contains("보행 안전을 보장하지 않"))
        assertTrue(WALKSAFE_PRODUCT_PURPOSE_NOTICE_KO.contains("흰지팡이·안내견·보호자를 대신하지 않습니다"))
        assertTrue(WALKSAFE_PRODUCT_PURPOSE_NOTICE_KO.contains(WALKSAFE_PRODUCT_NOTICE_BASELINE))
    }

    private fun availableInput() = WalkSafeStartupCapabilityInput(
        androidVersionSupported = true,
        cameraAvailable = true,
        gpsAvailable = true,
        microphoneAvailable = true,
        vibrationAvailable = true,
        onDeviceSpeechRecognitionAvailable = true,
        offlineKoreanTextToSpeechAvailable = true,
        metricDistanceAvailable = true,
        approvedDesignatedDeviceProfile = true,
        designatedDeviceProfileVersion = "test-profile-v1",
    )
}

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
        assertTrue(decision.noticeKo.contains(WalkSafeStartupRequirement.METRIC_DISTANCE.labelKo))
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
        assertTrue(
            unapproved.noticeKo.contains(
                WalkSafeStartupRequirement.APPROVED_DEVICE_PROFILE.labelKo,
            ),
        )
        assertEquals(WalkSafeStartupCapabilityTier.LIMITED, unversioned.tier)
        assertEquals(
            listOf(WalkSafeStartupRequirement.APPROVED_DEVICE_PROFILE),
            unversioned.unavailableRequirements,
        )
    }

    @Test
    fun explicitInstallCheckCanReplaceTheStaticApprovedDeviceList() {
        val decision = WalkSafeStartupCapabilityResolver.resolve(
            availableInput().copy(
                approvedDesignatedDeviceProfile = false,
                designatedDeviceProfileVersion = null,
            ),
            approvedDeviceProfileRequired = false,
        )

        assertEquals(WalkSafeStartupCapabilityTier.FULL, decision.tier)
        assertTrue(decision.mayConfirmAndStart)
        assertFalse(
            decision.unavailableRequirements.contains(
                WalkSafeStartupRequirement.APPROVED_DEVICE_PROFILE,
            ),
        )
    }

    @Test
    fun unsupportedAndroidVersionBlocksStartAndPreservesEveryKnownFailure() {
        val decision = WalkSafeStartupCapabilityResolver.resolve(
            availableInput().copy(
                androidVersionSupported = false,
                cameraAvailable = false,
                gpsAvailable = false,
            ),
        )

        assertEquals(WalkSafeStartupCapabilityTier.BLOCKED, decision.tier)
        assertFalse(decision.mayConfirmAndStart)
        assertEquals(
            listOf(
                WalkSafeStartupRequirement.ANDROID_VERSION,
                WalkSafeStartupRequirement.CAMERA,
                WalkSafeStartupRequirement.GPS,
            ),
            decision.unavailableRequirements,
        )
        decision.unavailableRequirements.forEach { requirement ->
            assertTrue(decision.noticeKo.contains(requirement.labelKo))
        }
    }

    @Test
    fun missingOfflineKoreanVoiceBlocksTheStartInsteadOfLimitingIt() {
        // RQ-FP-027-001: 오프라인 한국어 음성이 준비되지 않은 기기에서는 보행을 시작하지 않는다.
        val decision = WalkSafeStartupCapabilityResolver.resolve(
            availableInput().copy(offlineKoreanTextToSpeechAvailable = false),
        )

        assertEquals(WalkSafeStartupCapabilityTier.BLOCKED, decision.tier)
        assertFalse(decision.mayConfirmAndStart)
        assertEquals(
            listOf(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS),
            decision.unavailableRequirements,
        )
        assertTrue(decision.noticeKo.contains(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS.labelKo))
    }

    @Test
    fun everyUnavailableFeatureYieldsLimitedTierAndNamesTheRestriction() {
        val unavailableInputs = listOf(
            WalkSafeStartupRequirement.CAMERA to availableInput().copy(cameraAvailable = false),
            WalkSafeStartupRequirement.GPS to availableInput().copy(gpsAvailable = false),
            WalkSafeStartupRequirement.MICROPHONE to
                availableInput().copy(microphoneAvailable = false),
            WalkSafeStartupRequirement.VIBRATION to
                availableInput().copy(vibrationAvailable = false),
            WalkSafeStartupRequirement.ON_DEVICE_STT to
                availableInput().copy(onDeviceSpeechRecognitionAvailable = false),
            WalkSafeStartupRequirement.METRIC_DISTANCE to
                availableInput().copy(metricDistanceAvailable = false),
            WalkSafeStartupRequirement.APPROVED_DEVICE_PROFILE to availableInput().copy(
                approvedDesignatedDeviceProfile = false,
                designatedDeviceProfileVersion = null,
            ),
        )

        unavailableInputs.forEach { (requirement, input) ->
            val decision = WalkSafeStartupCapabilityResolver.resolve(input)
            assertEquals(WalkSafeStartupCapabilityTier.LIMITED, decision.tier)
            assertTrue(decision.mayConfirmAndStart)
            assertEquals(listOf(requirement), decision.unavailableRequirements)
            assertTrue(decision.pendingRequirements.isEmpty())
            assertTrue(decision.noticeKo.contains(requirement.labelKo))
        }
    }

    @Test
    fun unavailableGpsYieldsLimitedTier() {
        val decision = WalkSafeStartupCapabilityResolver.resolve(
            availableInput().copy(gpsAvailable = false),
        )

        assertEquals(WalkSafeStartupCapabilityTier.LIMITED, decision.tier)
        assertTrue(decision.mayConfirmAndStart)
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
    fun knownFeatureFailureRemainsVisibleWhileOtherProbesArePending() {
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
        assertEquals(
            listOf(WalkSafeStartupRequirement.CAMERA),
            decision.unavailableRequirements,
        )
        assertEquals(
            listOf(
                WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS,
                WalkSafeStartupRequirement.METRIC_DISTANCE,
            ),
            decision.pendingRequirements,
        )
    }

    @Test
    fun multipleUnavailableFeaturesAreAllPreservedAndNamed() {
        val decision = WalkSafeStartupCapabilityResolver.resolve(
            availableInput().copy(
                cameraAvailable = false,
                microphoneAvailable = false,
                vibrationAvailable = false,
                metricDistanceAvailable = false,
            ),
        )
        val expectedUnavailable = listOf(
            WalkSafeStartupRequirement.CAMERA,
            WalkSafeStartupRequirement.MICROPHONE,
            WalkSafeStartupRequirement.VIBRATION,
            WalkSafeStartupRequirement.METRIC_DISTANCE,
        )

        assertEquals(WalkSafeStartupCapabilityTier.LIMITED, decision.tier)
        assertTrue(decision.mayConfirmAndStart)
        assertEquals(expectedUnavailable, decision.unavailableRequirements)
        expectedUnavailable.forEach { requirement ->
            assertTrue(decision.noticeKo.contains(requirement.labelKo))
        }
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

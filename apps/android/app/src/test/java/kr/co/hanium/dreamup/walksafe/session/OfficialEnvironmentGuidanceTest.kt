package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialEnvironmentGuidanceTest {
    private fun assessment(confirmed: Boolean = false) = OfficialEnvironmentAssessment(
        epoch = WalkRuntimeEpoch("walk-test", 1L),
        assessedAtElapsedRealtimeMs = 1_000L,
        profileId = "test-profile",
        factorStatuses = OfficialEnvironmentFactor.entries.associateWith {
            if (confirmed || it in setOf(
                    OfficialEnvironmentFactor.GPS_QUALITY,
                    OfficialEnvironmentFactor.CAMERA_QUALITY,
                )) EnvironmentEvidenceStatus.PASS else EnvironmentEvidenceStatus.UNKNOWN
        },
        usageLimitsAcknowledged = true,
    )

    @Test
    fun educationWithGoodSensorsStillAnnouncesUnconfirmedSurroundings() {
        val value = assessment()
        assertTrue(value.canStartWalk)
        assertTrue(value.conditionallyAllowed)
        assertEquals("센서 점검은 통과했지만 날씨·보도 등 일부 주변 환경은 미확인입니다.", value.preparationNoticeKo())
        assertEquals(EnvironmentEvidenceStatus.UNKNOWN, value.factorStatuses[OfficialEnvironmentFactor.DRY_WEATHER])
    }

    @Test
    fun missingProfileOrFailedRequiredSensorNeverAnnouncesReadiness() {
        val value = assessment()
        listOf(
            value.copy(profileId = null),
            value.copy(factorStatuses = value.factorStatuses +
                (OfficialEnvironmentFactor.CAMERA_QUALITY to EnvironmentEvidenceStatus.FAIL)),
            value.copy(usageLimitsAcknowledged = false),
        ).forEach {
            assertFalse(it.canStartWalk)
            assertEquals("환경 점검을 완료하지 못했습니다. 다시 점검해 주세요.", it.preparationNoticeKo())
        }
    }

    @Test
    fun fullyConfirmedConditionsAreDistinctFromConditionalSupport() {
        val value = assessment(confirmed = true)
        assertTrue(value.canStartWalk)
        assertFalse(value.conditionallyAllowed)
        assertEquals("사용 중인 센서와 주변 환경 확인을 마쳤습니다.", value.preparationNoticeKo())
    }
}

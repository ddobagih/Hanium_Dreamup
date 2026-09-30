package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S03-6 review (FP-018 user_guidance, FP-043 design_rules[4]): a mid-walk safety stop says which
 * feature was lost and what to do next. The raw readiness codes are diagnostics only.
 */
class WalkReadinessStopGuidanceTest {
    private val epoch = WalkRuntimeEpoch("walk-guidance", 0L)

    private fun snapshot(
        mode: WalkSessionMode = WalkSessionMode.FULL,
        unavailable: Map<WalkSessionReadinessRequirement, String> = emptyMap(),
    ): WalkSessionReadinessSnapshot {
        val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, mode)
        val observations = WalkSessionReadinessRequirement.entries.associateWith { requirement ->
            val status = when {
                !plan.requires(requirement) -> WalkSessionReadinessStatus.NOT_REQUIRED
                requirement in unavailable -> WalkSessionReadinessStatus.UNAVAILABLE
                else -> WalkSessionReadinessStatus.READY
            }
            WalkSessionReadinessObservation(epoch, requirement, status, unavailable[requirement].orEmpty())
        }
        return WalkSessionReadinessSnapshot(epoch, plan, 1L, observations)
    }

    private val rawCode = Regex("[a-z]+_[a-z_]+|startup_unavailable|:[A-Z]")

    @Test fun lostLocationNamesLocationAndTheNextActionWithoutRawCodes() {
        val readiness = snapshot(
            unavailable = mapOf(
                WalkSessionReadinessRequirement.LOCATION to "location_guidance_disabled,startup_unavailable:GPS",
            ),
        )
        assertTrue(readiness.blockingReasons.single().contains("location_guidance_disabled"))
        val sentence = walkReadinessStopReasonKo(readiness)
        assertTrue(sentence.startsWith("정확한 위치를 사용할 수 없어 보행 안내를 멈췄습니다."))
        assertTrue(sentence.contains("새 보행 준비를 눌러 주세요."))
        assertFalse(rawCode.containsMatchIn(sentence))
    }

    @Test fun lostCameraOrModelNamesCameraGuidance() {
        for (requirement in listOf(WalkSessionReadinessRequirement.CAMERA, WalkSessionReadinessRequirement.MODEL)) {
            val sentence = walkReadinessStopReasonKo(
                snapshot(unavailable = mapOf(requirement to "camera_guidance_disabled")),
            )
            assertTrue(requirement.name, sentence.startsWith("카메라 장애물 안내를 사용할 수 없어 보행 안내를 멈췄습니다."))
            assertTrue(sentence.contains("새 보행 준비"))
            assertFalse(rawCode.containsMatchIn(sentence))
        }
    }

    @Test fun locationLossIsNamedFirstWhenSeveralFeaturesAreLost() {
        val sentence = walkReadinessStopReasonKo(
            snapshot(
                unavailable = mapOf(
                    WalkSessionReadinessRequirement.CAMERA to "camera_permission_missing",
                    WalkSessionReadinessRequirement.LOCATION to "precise_location_permission_missing",
                ),
            ),
        )
        assertTrue(sentence.startsWith("정확한 위치를"))
    }

    @Test fun aModeChangeWithEveryFeatureReadyNamesTheDistanceFeature() {
        // S03-7: the metric frames were lost and the distance-limited plan is READY, but the walk
        // was confirmed in FULL mode, so it stops.
        val readiness = snapshot(mode = WalkSessionMode.DISTANCE_LIMITED)
        assertTrue(readiness.isReady)
        val sentence = walkReadinessStopReasonKo(readiness)
        assertTrue(sentence.startsWith("거리 확인 기능 상태가 바뀌어 보행 안내를 멈췄습니다."))
        assertTrue(sentence.contains("새 보행 준비"))
        assertEquals(
            sentence,
            walkReadinessStopReasonKo(
                snapshot(unavailable = mapOf(WalkSessionReadinessRequirement.METRIC_DISTANCE to "metric_lost")),
            ),
        )
    }

    @Test fun anyOtherLossStillGivesTheGenericNextAction() {
        val sentence = walkReadinessStopReasonKo(
            snapshot(unavailable = mapOf(WalkSessionReadinessRequirement.VOICE_OUTPUT to "offline_korean_tts_unavailable")),
        )
        assertEquals("필수 기능 상태가 바뀌어 보행 안내를 멈췄습니다. 새 보행 준비를 눌러 다시 점검해 주세요.", sentence)
        assertFalse(rawCode.containsMatchIn(sentence))
    }
}

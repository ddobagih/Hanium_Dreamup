package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 종합분석 6.8: 같은 fix를 새 timestamp로 반복 전달해도 독립 근거·정지 시간·TTL이 늘지 않는다. */
class UserMotionRepackagedFixRegressionTest {
    @Test
    fun repackagedSameFixCannotExtendStationaryTtlOrAuthorizeAnotherZupt() {
        val estimator = UserMotionStateEstimator()
        val detector = PedestrianStationaryDetector()
        for (at in 1_000L..3_000L step 100L) {
            estimator.observeMotion(detector.observe(quiet(at)), at)
            if (at % 1_000L == 0L) speed(estimator, at, id = at)
        }
        val original = estimator.snapshot(3_000L)
        assertEquals(UserMovementState.STATIONARY, original.movement)
        assertTrue(estimator.claimZeroVelocityUpdate(3_000L, 3_000L))
        val stationarySince = original.stateSinceElapsedRealtimeMs

        // The 3,000 ms fix is re-delivered with new envelope times but the same original identity.
        for (at in 3_100L..5_200L step 100L) {
            estimator.observeMotion(detector.observe(quiet(at)), at)
            if (at % 500L == 0L) speed(estimator, at, id = 3_000L)
            val state = estimator.snapshot(at)
            if (state.movement == UserMovementState.STATIONARY) {
                assertEquals("at=$at", 3_000L, state.speedObservedAtElapsedRealtimeMs)
                assertTrue("at=$at", requireNotNull(state.expiresAtElapsedRealtimeMs) <= 3_000L + UserMotionStateConfig().maximumSpeedAgeMs)
                assertEquals(stationarySince, state.stateSinceElapsedRealtimeMs)
            }
            if (at % 300L == 0L) assertFalse("at=$at", estimator.claimZeroVelocityUpdate(at, at))
        }
        val afterOriginalTtl = estimator.snapshot(5_200L)
        assertEquals(UserMovementState.UNKNOWN, afterOriginalTtl.movement)
        assertFalse(afterOriginalTtl.zeroVelocityUpdateAllowed)

        // A genuinely new fix restores the evidence; it is not blocked forever.
        speed(estimator, 5_200L, id = 5_200L)
        assertEquals(UserMovementState.STATIONARY, estimator.snapshot(5_200L).movement)
    }

    private fun quiet(at: Long): PedestrianMotionSample {
        val window = PedestrianMotionWindowCoverage(5, at - 200L, at, 50L)
        return PedestrianMotionSample(at, accelerationMagnitudeMeanMps2 = 9.81,
            accelerationMagnitudeVarianceMps4 = 0.02, gyroscopeRmsRadPerSecond = 0.02,
            gyroscopePeakRadPerSecond = 0.05, accelerationWindow = window, gyroscopeWindow = window)
    }

    private fun speed(estimator: UserMotionStateEstimator, at: Long, id: Long) = estimator.observeSpeed(
        WalkingSpeedObservation(0.0, 0.1, at), UserMotionLocationEvidence(at, 2.0, false, observationId = id), at,
    )
}

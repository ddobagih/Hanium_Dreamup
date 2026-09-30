package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** 종합분석 6.8: 표본 하나 또는 짧은 불완전 창은 분산 0이어도 WARMING_UP/UNKNOWN. */
class UserMotionIncompleteWindowRegressionTest {
    @Test
    fun zeroVarianceSingleSampleOrShortIncompleteWindowNeverBecomesStationaryOrZupt() {
        // Relative to the window end: sample count, start offset, maximum gap.
        val incomplete = mapOf(
            "one sample" to Triple(1, 0L, 0L),
            "five samples over 100 ms" to Triple(5, 100L, 25L),
            "four samples over 300 ms" to Triple(4, 300L, 100L),
            "five samples with a 300 ms gap" to Triple(5, 300L, 300L),
        )
        for ((label, shape) in incomplete) {
            val detector = PedestrianStationaryDetector()
            val estimator = UserMotionStateEstimator()
            for (at in 1_000L..4_000L step 100L) {
                val window = PedestrianMotionWindowCoverage(shape.first, at - shape.second, at, shape.third)
                val decision = detector.observe(zeroVariance(at, window))
                assertEquals(label, PedestrianStationaryState.UNKNOWN, decision.state)
                assertFalse(label, decision.shouldApplyZeroVelocityUpdate)
                estimator.observeMotion(decision, at)
                estimator.observeSpeed(
                    WalkingSpeedObservation(0.0, 0.1, at), UserMotionLocationEvidence(at, 2.0, false), at,
                )
                val snapshot = estimator.snapshot(at)
                assertEquals(label, UserMovementState.UNKNOWN, snapshot.movement)
                assertEquals(label, UserDeviceRotationState.UNKNOWN, snapshot.deviceRotation)
                assertFalse(label, snapshot.zeroVelocityUpdateAllowed)
                assertNull(label, snapshot.stationaryCandidateSinceElapsedRealtimeMs)
            }
            assertFalse(label, estimator.claimZeroVelocityUpdate(4_000L, 4_000L))
        }
    }

    @Test
    fun theSameZeroVarianceInputWithACompleteWindowIsNotRejectedForever() {
        val detector = PedestrianStationaryDetector()
        val estimator = UserMotionStateEstimator()
        for (at in 1_000L..3_000L step 100L) {
            estimator.observeMotion(detector.observe(zeroVariance(at, PedestrianMotionWindowCoverage(5, at - 200L, at, 50L))), at)
            estimator.observeSpeed(
                WalkingSpeedObservation(0.0, 0.1, at), UserMotionLocationEvidence(at, 2.0, false), at,
            )
        }
        assertEquals(UserMovementState.STATIONARY, estimator.snapshot(3_000L).movement)
    }

    private fun zeroVariance(at: Long, window: PedestrianMotionWindowCoverage) = PedestrianMotionSample(
        at,
        accelerationMagnitudeMeanMps2 = 9.80665,
        accelerationMagnitudeVarianceMps4 = 0.0,
        gyroscopeRmsRadPerSecond = 0.0,
        gyroscopePeakRadPerSecond = 0.0,
        accelerationWindow = window,
        gyroscopeWindow = window,
    )
}

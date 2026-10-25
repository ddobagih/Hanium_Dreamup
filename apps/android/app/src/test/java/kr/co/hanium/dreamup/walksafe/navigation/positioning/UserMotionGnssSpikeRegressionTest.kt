package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S09-23 (originals/09.txt:60): accurate GNSS movement that conflicts with stillness withholds ZUPT,
 * but one GNSS spike alone does not release a confirmed stationary state; persistence (two distinct
 * accurate fixes, the confidence-recovery rule) and accuracy are both required.
 */
class UserMotionGnssSpikeRegressionTest {
    @Test
    fun oneAccurateSpikeKeepsStationaryButWithholdsZuptUntilStillSpeedReturns() {
        val estimator = UserMotionStateEstimator()
        val detector = confirmStationary(estimator)

        quiet(estimator, detector, 3_100L)
        speed(estimator, 3_100L, 1.5, accuracy = 0.3)
        val spike = estimator.snapshot(3_100L)
        assertEquals(UserMovementState.STATIONARY, spike.movement)
        assertEquals(UserMotionStateReason.GNSS_MOVEMENT_UNCONFIRMED, spike.reason)
        assertFalse(spike.zeroVelocityUpdateAllowed)
        assertFalse(estimator.claimZeroVelocityUpdate(3_100L, 3_100L))
        assertFalse(TransferStationaryEvidencePolicy.confirmed(estimator.peek(3_100L), 3_100L))

        quiet(estimator, detector, 3_200L)
        speed(estimator, 3_200L, 0.0)
        val recovered = estimator.snapshot(3_200L)
        assertEquals(UserMovementState.STATIONARY, recovered.movement)
        assertEquals(UserMotionStateReason.STATIONARY_CONFIRMED, recovered.reason)
        assertTrue(recovered.zeroVelocityUpdateAllowed)
        assertTrue(estimator.claimZeroVelocityUpdate(3_200L, 3_200L))
    }

    @Test
    fun twoDistinctAccurateMovingFixesReleaseStationaryAndZuptStaysWithheld() {
        val estimator = UserMotionStateEstimator()
        val detector = confirmStationary(estimator)

        quiet(estimator, detector, 3_100L)
        speed(estimator, 3_100L, 1.5, accuracy = 0.3)
        assertEquals(UserMovementState.STATIONARY, estimator.snapshot(3_100L).movement)
        quiet(estimator, detector, 3_200L)
        speed(estimator, 3_200L, 1.4, accuracy = 0.3)
        val moving = estimator.snapshot(3_200L)
        assertEquals(UserMovementState.MOVING, moving.movement)
        assertEquals(UserMotionStateReason.GNSS_MOVEMENT_WITH_QUIET_DEVICE, moving.reason)
        assertFalse(moving.zeroVelocityUpdateAllowed)
        assertFalse(estimator.claimZeroVelocityUpdate(3_200L, 3_200L))
    }

    @Test
    fun repeatedFixIdentityOrAStillFixInBetweenIsNotPersistentMovement() {
        val estimator = UserMotionStateEstimator()
        val detector = confirmStationary(estimator)

        quiet(estimator, detector, 3_100L)
        estimator.observeSpeed(WalkingSpeedObservation(1.5, 0.3, 3_100L),
            UserMotionLocationEvidence(3_100L, 2.0, false, observationId = 3_100L), 3_100L)
        estimator.snapshot(3_100L)
        // The same fix re-delivered with a new envelope time is not a second measurement.
        estimator.observeSpeed(
            WalkingSpeedObservation(1.5, 0.3, 3_150L),
            UserMotionLocationEvidence(3_150L, 2.0, false, observationId = 3_100L),
            3_150L,
        )
        quiet(estimator, detector, 3_150L)
        assertEquals(UserMovementState.STATIONARY, estimator.snapshot(3_150L).movement)

        quiet(estimator, detector, 3_200L)
        speed(estimator, 3_200L, 0.0)
        estimator.snapshot(3_200L)
        quiet(estimator, detector, 3_300L)
        speed(estimator, 3_300L, 1.5, accuracy = 0.3)
        val secondSpike = estimator.snapshot(3_300L)
        assertEquals(UserMovementState.STATIONARY, secondSpike.movement)
        assertEquals(UserMotionStateReason.GNSS_MOVEMENT_UNCONFIRMED, secondSpike.reason)
    }

    @Test
    fun inaccurateSpikeIsIgnoredAndQuietPhoneOnAMovingGroundStillNeverBecomesStationary() {
        val estimator = UserMotionStateEstimator()
        val detector = confirmStationary(estimator)
        quiet(estimator, detector, 3_100L)
        speed(estimator, 3_100L, 3.0, accuracy = 2.0)
        val ignored = estimator.snapshot(3_100L)
        assertEquals(UserMovementState.STATIONARY, ignored.movement)
        assertEquals(UserMotionStateReason.STATIONARY_CONFIRMED, ignored.reason)
        assertEquals(3_000L, ignored.speedObservedAtElapsedRealtimeMs)

        // Without a published stillness a single accurate ground speed is still MOVING at once.
        val fresh = UserMotionStateEstimator()
        val freshDetector = PedestrianStationaryDetector()
        for (at in 1_000L..3_000L step 100L) {
            quiet(fresh, freshDetector, at)
            speed(fresh, at, 8.0, accuracy = 0.5)
        }
        val vehicle = fresh.snapshot(3_000L)
        assertEquals(UserMovementState.MOVING, vehicle.movement)
        assertFalse(vehicle.zeroVelocityUpdateAllowed)
    }

    private fun confirmStationary(estimator: UserMotionStateEstimator): PedestrianStationaryDetector {
        val detector = PedestrianStationaryDetector()
        for (at in 1_000L..3_000L step 100L) {
            quiet(estimator, detector, at)
            speed(estimator, at, 0.0)
            estimator.snapshot(at)
        }
        val confirmed = estimator.snapshot(3_000L)
        assertEquals(UserMovementState.STATIONARY, confirmed.movement)
        assertEquals(UserMotionStateReason.STATIONARY_CONFIRMED, confirmed.reason)
        assertTrue(confirmed.zeroVelocityUpdateAllowed)
        return detector
    }

    private fun quiet(estimator: UserMotionStateEstimator, detector: PedestrianStationaryDetector, at: Long) {
        val window = PedestrianMotionWindowCoverage(5, at - 200L, at, 50L)
        estimator.observeMotion(
            detector.observe(
                PedestrianMotionSample(
                    at,
                    accelerationMagnitudeMeanMps2 = 9.81,
                    accelerationMagnitudeVarianceMps4 = 0.02,
                    gyroscopeRmsRadPerSecond = 0.02,
                    gyroscopePeakRadPerSecond = 0.05,
                    accelerationWindow = window,
                    gyroscopeWindow = window,
                ),
            ),
            at,
        )
    }

    private fun speed(estimator: UserMotionStateEstimator, at: Long, value: Double, accuracy: Double = 0.1) =
        estimator.observeSpeed(WalkingSpeedObservation(value, accuracy, at), UserMotionLocationEvidence(at, 2.0, false), at)
}

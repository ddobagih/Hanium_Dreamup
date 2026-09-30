package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADD-MOTION-043: only continuous GNSS + inertial STATIONARY evidence may start a transfer. */
class TransferStationaryEvidencePolicyTest {
    @Test
    fun tenSecondsOfUnchangedStepsWithoutGnssOrInertialEvidenceIsNotStationary() {
        val estimator = UserMotionStateEstimator()
        estimator.observeSteps(1, PdrStepQuality.HIGH, 0L, 0L)
        for (at in 100L..10_000L step 100L) {
            val snapshot = estimator.snapshot(at)
            assertFalse("step silence at $at", TransferStationaryEvidencePolicy.confirmed(snapshot, at))
        }
        assertEquals(UserMovementState.UNKNOWN, estimator.snapshot(10_000L).movement)
    }

    @Test
    fun quietPhoneAndStationaryGnssConfirmOnlyAfterTheContinuousWindow() {
        val estimator = UserMotionStateEstimator()
        val first = firstConfirmation(estimator, 1_000L..12_000L, speed = 0.0)
        assertNotNull(first)
        val (at, snapshot) = checkNotNull(first)
        assertEquals(UserMovementState.STATIONARY, snapshot.movement)
        assertTrue(at - checkNotNull(snapshot.stateSinceElapsedRealtimeMs) >= TransferStationaryEvidencePolicy.MINIMUM_CONTINUOUS_STATIONARY_MS)
        assertTrue(at - checkNotNull(snapshot.stationaryCandidateSinceElapsedRealtimeMs) >= TransferStationaryEvidencePolicy.MINIMUM_CONTINUOUS_STATIONARY_MS)
        assertTrue("both sources, repeatedly: not before 5 s of quiet phone", at >= 6_000L)
    }

    @Test
    fun quietPhoneWithoutGnssNeverConfirms() {
        assertNull(firstConfirmation(UserMotionStateEstimator(), 1_000L..12_000L, speed = null))
    }

    @Test
    fun stationaryGnssWithoutInertialEvidenceNeverConfirms() {
        val estimator = UserMotionStateEstimator()
        for (at in 1_000L..12_000L step 100L) {
            speed(estimator, at, 0.0)
            assertFalse(TransferStationaryEvidencePolicy.confirmed(estimator.snapshot(at), at))
        }
    }

    @Test
    fun vehicleSpeedWithAQuietPhoneNeverConfirms() {
        assertNull(firstConfirmation(UserMotionStateEstimator(), 1_000L..12_000L, speed = 8.0))
    }

    @Test
    fun oneMovingFixRestartsTheWindowAndStaleOrMismatchedSnapshotsFailClosed() {
        val estimator = UserMotionStateEstimator()
        val detector = PedestrianStationaryDetector()
        val (confirmedAt, snapshot) = checkNotNull(firstConfirmation(estimator, 1_000L..12_000L, 0.0, detector))
        assertFalse("different evaluation time", TransferStationaryEvidencePolicy.confirmed(snapshot, confirmedAt + 1L))
        assertFalse(TransferStationaryEvidencePolicy.confirmed(snapshot, -1L))
        assertFalse("inputs stopped: evidence expires",
            TransferStationaryEvidencePolicy.confirmed(estimator.snapshot(confirmedAt + 600L), confirmedAt + 600L))

        val moving = confirmedAt + 700L
        estimator.observeMotion(detector.observe(sample(moving)), moving)
        speed(estimator, moving, 1.2)
        assertFalse(TransferStationaryEvidencePolicy.confirmed(estimator.snapshot(moving), moving))
        var again: Long? = null
        for (at in moving + 100L..moving + 8_000L step 100L) {
            estimator.observeMotion(detector.observe(sample(at)), at)
            speed(estimator, at, 0.0)
            if (TransferStationaryEvidencePolicy.confirmed(estimator.snapshot(at), at)) { again = at; break }
        }
        assertTrue(checkNotNull(again) - moving >= TransferStationaryEvidencePolicy.MINIMUM_CONTINUOUS_STATIONARY_MS)
    }

    private fun firstConfirmation(
        estimator: UserMotionStateEstimator,
        range: LongRange,
        speed: Double?,
        detector: PedestrianStationaryDetector = PedestrianStationaryDetector(),
    ): Pair<Long, UserMotionStateSnapshot>? {
        for (at in range step 100L) {
            estimator.observeMotion(detector.observe(sample(at)), at)
            if (speed != null) speed(estimator, at, speed)
            val snapshot = estimator.snapshot(at)
            if (TransferStationaryEvidencePolicy.confirmed(snapshot, at)) return at to snapshot
        }
        return null
    }

    private fun sample(at: Long): PedestrianMotionSample {
        val window = PedestrianMotionWindowCoverage(5, at - 200L, at, 50L)
        return PedestrianMotionSample(at, accelerationMagnitudeMeanMps2 = 9.81,
            accelerationMagnitudeVarianceMps4 = 0.02,
            gyroscopeRmsRadPerSecond = 0.02,
            gyroscopePeakRadPerSecond = 0.05,
            accelerationWindow = window, gyroscopeWindow = window)
    }

    private fun speed(estimator: UserMotionStateEstimator, at: Long, value: Double) =
        estimator.observeSpeed(WalkingSpeedObservation(value, 0.1, at), UserMotionLocationEvidence(at, 2.0, false), at)
}

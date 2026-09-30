package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADD-MOTION-045-F1 / w21 AM43-F2: RAW metadata, transfer admission and the status line read the
 * motion state with a read-only peek. A peek at a later time must not make a slightly earlier
 * navigation evaluation INVALID_TIME, must not cost that evaluation its ZUPT, and must not restart
 * the published state-since time.
 */
class UserMotionPeekSideEffectTest {
    @Test
    fun aLaterPeekLeavesAnEarlierNavigationEvaluationValid() {
        val baseline = PositioningCoordinator()
        val coordinator = PositioningCoordinator()
        // Detector/RAW thread reads at 10 000 ms; the location callback sampled 9 990 ms earlier.
        coordinator.peekUserMotion(10_000L)
        val location = coordinator.currentUserMotion(9_990L)
        assertNotEquals(UserMotionStateReason.INVALID_TIME, location.reason)
        assertEquals(baseline.currentUserMotion(9_990L), location)
        val evaluated = coordinator.evaluate(9_995L).userMotion
        assertNotEquals(UserMotionStateReason.INVALID_TIME, evaluated?.reason)
        assertEquals(baseline.evaluate(9_995L).userMotion, evaluated)
    }

    @Test
    fun aPeekDoesNotCostTheNavigationOwnerItsZeroVelocityUpdate() {
        val estimator = UserMotionStateEstimator()
        val twin = UserMotionStateEstimator()
        feed(estimator)
        feed(twin)
        val peeked = estimator.peek(3_050L)
        assertEquals(UserMovementState.STATIONARY, peeked.movement)
        val navigation = estimator.snapshot(3_000L)
        assertEquals(twin.snapshot(3_000L), navigation)
        assertTrue(navigation.zeroVelocityUpdateAllowed)
        assertTrue(estimator.claimZeroVelocityUpdate(3_000L, 3_000L))
    }

    @Test
    fun aPeekNeverRestartsThePublishedStateSince() {
        val estimator = UserMotionStateEstimator()
        val detector = feed(estimator)
        val published = estimator.snapshot(3_000L)
        assertEquals(UserMovementState.STATIONARY, published.movement)
        // Moving evidence arrives; a peek sees MOVING (tentatively "since now") but does not publish it.
        // S09-23 (09.txt:60): one GNSS spike no longer releases stillness, so the moving evidence is two
        // distinct accurate fixes.
        speed(estimator, 3_050L, 1.5)
        speed(estimator, 3_100L, 1.5)
        val peeked = estimator.peek(3_100L)
        assertEquals(UserMovementState.MOVING, peeked.movement)
        assertEquals(3_100L, peeked.stateSinceElapsedRealtimeMs)
        // Stillness evidence returns before the navigation owner published anything else: the
        // STATIONARY interval was never interrupted in the published sequence.
        estimator.observeMotion(detector.observe(sample(3_200L)), 3_200L)
        speed(estimator, 3_200L, 0.0)
        val again = estimator.snapshot(3_200L)
        assertEquals(UserMovementState.STATIONARY, again.movement)
        assertEquals(published.stateSinceElapsedRealtimeMs, again.stateSinceElapsedRealtimeMs)
    }

    @Test
    fun peekMatchesTheSnapshotClassificationAtTheSameTime() {
        val estimator = UserMotionStateEstimator()
        val twin = UserMotionStateEstimator()
        feed(estimator)
        feed(twin)
        assertEquals(twin.snapshot(3_000L), estimator.peek(3_000L))
        assertEquals(UserMotionStateReason.INVALID_TIME, estimator.peek(-1L).reason)
    }

    private fun feed(estimator: UserMotionStateEstimator): PedestrianStationaryDetector {
        val detector = PedestrianStationaryDetector()
        for (at in 1_000L..3_000L step 100L) {
            estimator.observeMotion(detector.observe(sample(at)), at)
            speed(estimator, at, 0.0)
        }
        return detector
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

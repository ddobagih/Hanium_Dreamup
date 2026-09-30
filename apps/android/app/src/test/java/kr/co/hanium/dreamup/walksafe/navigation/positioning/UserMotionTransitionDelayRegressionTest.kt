package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 종합분석 6.8: 걷다가 멈춤·멈췄다 출발에서 진입/해제 지연을 측정하고 이전 상태가 무한 잔존하지 않음.
 * Synthetic JVM timeline only; bounds come from the current config, not from field data.
 */
class UserMotionTransitionDelayRegressionTest {
    private val config = UserMotionStateConfig()
    private val stationaryConfig = PedestrianStationaryDetectorConfig()

    @Test
    fun walkStopWalkHasBoundedEntryAndImmediateReleaseAndNoStaleState() {
        val estimator = UserMotionStateEstimator()
        val detector = PedestrianStationaryDetector()
        // Walking 1.0-5.0 s: a step every 500 ms, 1 Hz GNSS speed, dynamic acceleration.
        for (at in 1_000L..5_000L step 100L) {
            estimator.observeMotion(detector.observe(walking(at)), at)
            if (at % 500L == 0L) estimator.observeSteps(1, PdrStepQuality.HIGH, at, at)
            if (at % 1_000L == 0L) speed(estimator, at, 1.2)
            assertEquals("at=$at", UserMovementState.MOVING, estimator.snapshot(at).movement)
        }
        val stoppedAt = 5_000L
        // Stop: quiet windows from 5.1 s, zero-speed fixes at 1 Hz.
        var entryAt: Long? = null
        for (at in 5_100L..9_000L step 100L) {
            estimator.observeMotion(detector.observe(quiet(at)), at)
            if (at % 1_000L == 0L) speed(estimator, at, 0.0)
            val state = estimator.snapshot(at)
            if (at - stoppedAt > config.maximumStepAgeMs && at - stoppedAt > config.maximumSpeedAgeMs) {
                assertNotEquals("old MOVING persisted at=$at", UserMovementState.MOVING, state.movement)
            }
            if (entryAt == null && state.movement == UserMovementState.STATIONARY) entryAt = at
            if (entryAt == null) assertFalse(state.zeroVelocityUpdateAllowed)
        }
        val entry = requireNotNull(entryAt) { "stationary entry never happened" }
        val entryDelayMs = entry - stoppedAt
        assertTrue(entry - 5_100L >= stationaryConfig.stationaryDwellMs)
        assertTrue("entryDelayMs=$entryDelayMs",
            entryDelayMs <= maxOf(config.maximumStepAgeMs, config.maximumSpeedAgeMs) + stationaryConfig.stationaryDwellMs)

        // Departure: the first valid step releases STATIONARY at its own measurement time.
        val departAt = 9_100L
        estimator.observeMotion(detector.observe(walking(departAt)), departAt)
        estimator.observeSteps(1, PdrStepQuality.HIGH, departAt, departAt)
        val released = estimator.snapshot(departAt)
        val releaseDelayMs = requireNotNull(released.stateSinceElapsedRealtimeMs) - departAt
        assertEquals(UserMovementState.MOVING, released.movement)
        assertEquals(0L, releaseDelayMs)
        assertFalse(released.zeroVelocityUpdateAllowed)
        println("ADD-MOTION-056 entryDelayMs=$entryDelayMs releaseDelayMs=$releaseDelayMs")
    }

    @Test
    fun neitherMovingNorStationaryOutlivesItsOwnEvidenceWhenInputStops() {
        val walkingEstimator = UserMotionStateEstimator()
        val walkingDetector = PedestrianStationaryDetector()
        for (at in 1_000L..3_000L step 100L) {
            walkingEstimator.observeMotion(walkingDetector.observe(walking(at)), at)
            if (at % 500L == 0L) walkingEstimator.observeSteps(1, PdrStepQuality.HIGH, at, at)
            if (at % 1_000L == 0L) speed(walkingEstimator, at, 1.2)
        }
        val moving = walkingEstimator.snapshot(3_000L)
        assertEquals(UserMovementState.MOVING, moving.movement)
        // expiresAt is the earliest component expiry (here the rotation window), so re-evaluation is
        // due then; MOVING itself must end once its own step and speed sources are stale.
        assertTrue(requireNotNull(moving.expiresAtElapsedRealtimeMs) <= 3_000L + config.maximumStepAgeMs)
        val movingSourcesStaleAt = 3_000L + maxOf(config.maximumStepAgeMs, config.maximumSpeedAgeMs) + 1L
        assertEquals(UserMovementState.UNKNOWN, walkingEstimator.snapshot(movingSourcesStaleAt).movement)

        val stillEstimator = UserMotionStateEstimator()
        val stillDetector = PedestrianStationaryDetector()
        for (at in 1_000L..3_000L step 100L) {
            stillEstimator.observeMotion(stillDetector.observe(quiet(at)), at)
            speed(stillEstimator, at, 0.0)
        }
        val still = stillEstimator.snapshot(3_000L)
        assertEquals(UserMovementState.STATIONARY, still.movement)
        val stillExpiry = requireNotNull(still.expiresAtElapsedRealtimeMs)
        assertEquals(UserMovementState.UNKNOWN, stillEstimator.snapshot(stillExpiry + 1L).movement)
        assertFalse(stillEstimator.snapshot(stillExpiry + 1L).zeroVelocityUpdateAllowed)
    }

    private fun walking(at: Long): PedestrianMotionSample {
        val window = PedestrianMotionWindowCoverage(5, at - 200L, at, 50L)
        return PedestrianMotionSample(at, accelerationMagnitudeMeanMps2 = 10.4,
            accelerationMagnitudeVarianceMps4 = 1.5, gyroscopeRmsRadPerSecond = 0.25,
            gyroscopePeakRadPerSecond = 0.6, accelerationWindow = window, gyroscopeWindow = window)
    }

    private fun quiet(at: Long): PedestrianMotionSample {
        val window = PedestrianMotionWindowCoverage(5, at - 200L, at, 50L)
        return PedestrianMotionSample(at, accelerationMagnitudeMeanMps2 = 9.81,
            accelerationMagnitudeVarianceMps4 = 0.02, gyroscopeRmsRadPerSecond = 0.02,
            gyroscopePeakRadPerSecond = 0.05, accelerationWindow = window, gyroscopeWindow = window)
    }

    private fun speed(estimator: UserMotionStateEstimator, at: Long, value: Double) = estimator.observeSpeed(
        WalkingSpeedObservation(value, 0.1, at), UserMotionLocationEvidence(at, 2.0, false), at,
    )
}

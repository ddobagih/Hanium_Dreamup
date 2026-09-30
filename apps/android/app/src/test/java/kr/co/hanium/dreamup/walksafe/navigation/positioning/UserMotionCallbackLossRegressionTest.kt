package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 종합분석 6.8 (JVM part only): 모든 callback이 끊기면 정해진 만료로 UNKNOWN, 새 근거 없이 자동 복구 금지.
 * Screen lock, calls and permission revocation themselves need a device run.
 */
class UserMotionCallbackLossRegressionTest {
    @Test
    fun allCallbacksStoppingExpiresToUnknownAndOnlyAFreshWindowRecovers() {
        val coordinator = PositioningCoordinator()
        val detector = PedestrianStationaryDetector()
        coordinator.observeGnss(GnssPositionObservation(37.5, 127.0, 2.0, 1_000L, 1_000L))
        for (at in 1_000L..3_000L step 100L) {
            coordinator.observeUserMotion(detector.observe(quiet(at)), at)
            speed(coordinator, at)
        }
        val last = coordinator.currentUserMotion(3_000L)
        assertEquals(UserMovementState.STATIONARY, last.movement)
        val expiry = requireNotNull(last.expiresAtElapsedRealtimeMs)

        // No callbacks at all: repeated watchdog reads only expire, never recover.
        for (read in listOf(expiry + 1L, expiry + 1_000L, 60_000L, 600_000L)) {
            val state = coordinator.evaluate(read).userMotion!!
            assertEquals("read=$read", UserMovementState.UNKNOWN, state.movement)
            assertFalse("read=$read", state.zeroVelocityUpdateAllowed)
            if (read >= 60_000L) assertEquals("read=$read", UserMotionStateReason.EXPIRED_EVIDENCE, state.reason)
        }
        assertEquals(PedestrianStationaryState.UNKNOWN, detector.expire(600_000L))

        // One quiet sample after resumption is not a recovery; a new full dwell is required.
        val resumedAt = 600_100L
        coordinator.observeUserMotion(detector.observe(quiet(resumedAt)), resumedAt)
        speed(coordinator, resumedAt)
        val resumed = coordinator.currentUserMotion(resumedAt)
        assertEquals(UserMovementState.UNKNOWN, resumed.movement)
        assertFalse(resumed.zeroVelocityUpdateAllowed)
        var at = resumedAt + 100L
        while (at < resumedAt + PedestrianStationaryDetectorConfig().stationaryDwellMs) {
            coordinator.observeUserMotion(detector.observe(quiet(at)), at)
            speed(coordinator, at)
            assertEquals("at=$at", UserMovementState.UNKNOWN, coordinator.currentUserMotion(at).movement)
            at += 100L
        }
        coordinator.observeUserMotion(detector.observe(quiet(at)), at)
        speed(coordinator, at)
        assertTrue(coordinator.currentUserMotion(at).movement == UserMovementState.STATIONARY)
    }

    private fun quiet(at: Long): PedestrianMotionSample {
        val window = PedestrianMotionWindowCoverage(5, at - 200L, at, 50L)
        return PedestrianMotionSample(at, accelerationMagnitudeMeanMps2 = 9.81,
            accelerationMagnitudeVarianceMps4 = 0.02, gyroscopeRmsRadPerSecond = 0.02,
            gyroscopePeakRadPerSecond = 0.05, accelerationWindow = window, gyroscopeWindow = window)
    }

    private fun speed(coordinator: PositioningCoordinator, at: Long) = coordinator.observeUserSpeed(
        WalkingSpeedObservation(0.0, 0.1, at), UserMotionLocationEvidence(at, 2.0, false), at,
    )
}

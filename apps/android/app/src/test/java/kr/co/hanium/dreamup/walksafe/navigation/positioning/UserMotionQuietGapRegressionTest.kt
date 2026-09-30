package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 종합분석 6.8: 조용한 한 표본 -> 10초 무입력 -> 조용한 표본은 새 연속 창 전 STATIONARY/ZUPT 금지. */
class UserMotionQuietGapRegressionTest {
    @Test
    fun quietSampleTenSecondGapQuietSampleNeedsANewContinuousWindowBeforeStationaryOrZupt() {
        val config = PedestrianStationaryDetectorConfig()
        val coordinator = PositioningCoordinator()
        val detector = PedestrianStationaryDetector()
        coordinator.observeGnss(GnssPositionObservation(37.5, 127.0, 2.0, 1_000L, 1_000L))
        coordinator.observeUserMotion(detector.observe(quiet(1_000L)), 1_000L)
        speed(coordinator, 1_000L)

        // No observation of any kind for 10 s, then one quiet window and a zero-speed fix.
        coordinator.observeGnss(GnssPositionObservation(37.5, 127.0, 2.0, 11_000L, 11_000L))
        val afterGap = coordinator.observeUserMotion(detector.observe(quiet(11_000L)), 11_000L)
        speed(coordinator, 11_000L)
        val state = coordinator.currentUserMotion(11_000L)

        assertEquals(UserMovementState.UNKNOWN, afterGap.movement)
        assertEquals(UserMovementState.UNKNOWN, state.movement)
        assertFalse(state.zeroVelocityUpdateAllowed)
        assertEquals(11_000L, state.stationaryCandidateSinceElapsedRealtimeMs)
        val zupt = coordinator.observeZupt(ZuptObservation(11_000L), 11_000L)
        assertEquals(PedestrianMotionUpdateDisposition.HARD_REJECTED, zupt.motionUpdate?.disposition)
        assertEquals(PedestrianMotionHardRejectReason.MOTION_NOT_CONFIRMED, zupt.motionUpdate?.hardRejectReason)

        // A new continuous window is required; it is not blocked forever either.
        var at = 11_100L
        while (at < 11_000L + config.stationaryDwellMs) {
            coordinator.observeUserMotion(detector.observe(quiet(at)), at)
            speed(coordinator, at)
            assertEquals("at=$at", UserMovementState.UNKNOWN, coordinator.currentUserMotion(at).movement)
            at += 100L
        }
        coordinator.observeUserMotion(detector.observe(quiet(at)), at)
        speed(coordinator, at)
        val confirmed = coordinator.currentUserMotion(at)
        assertEquals(UserMovementState.STATIONARY, confirmed.movement)
        assertTrue(confirmed.zeroVelocityUpdateAllowed)
        assertEquals(
            PedestrianMotionUpdateDisposition.APPLIED,
            coordinator.observeZupt(ZuptObservation(at), at).motionUpdate?.disposition,
        )
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

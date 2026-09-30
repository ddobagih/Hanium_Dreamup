package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 종합분석 6.8: 정지 후 휴대폰만 회전하면 위치 이동과 구분하고 몸 회전을 단정하지 않는다.
 * Facing refresh while stationary is covered by RouteFacingGuidanceTest/RouteNavigatorFacingGuidanceTest.
 */
class UserMotionPhoneOnlyRotationRegressionTest {
    @Test
    fun rotatingOnlyThePhoneAfterStoppingKeepsGroundStationaryAsDeviceTurningWithoutZupt() {
        val coordinator = PositioningCoordinator()
        val detector = PedestrianStationaryDetector()
        coordinator.observeGnss(GnssPositionObservation(37.5, 127.0, 2.0, 1_000L, 1_000L))
        for (at in 1_000L..3_000L step 100L) {
            coordinator.observeUserMotion(detector.observe(sample(at, turning = false)), at)
            speed(coordinator, at)
        }
        val stopped = coordinator.currentUserMotion(3_000L)
        assertEquals(UserMovementState.STATIONARY, stopped.movement)
        assertEquals(UserDeviceRotationState.STABLE, stopped.deviceRotation)
        assertTrue(stopped.zeroVelocityUpdateAllowed)
        val stationarySince = stopped.stateSinceElapsedRealtimeMs

        for (at in 3_100L..4_500L step 100L) {
            val decision = detector.observe(sample(at, turning = true))
            val state = coordinator.observeUserMotion(decision, at)
            speed(coordinator, at)
            assertEquals("at=$at", UserMovementState.STATIONARY, state.movement)
            assertEquals("at=$at", UserDeviceRotationState.TURNING, state.deviceRotation)
            assertEquals("at=$at", UserLocomotionState.UNKNOWN, state.locomotion)
            assertFalse("at=$at", state.zeroVelocityUpdateAllowed)
            assertEquals(stationarySince, state.stateSinceElapsedRealtimeMs)
            assertEquals(
                PedestrianMotionHardRejectReason.MOTION_NOT_CONFIRMED,
                coordinator.observeZupt(ZuptObservation(at), at).motionUpdate?.hardRejectReason,
            )
        }
        // The API names device rotation only; there is no body-turn state to claim.
        assertTrue(UserDeviceRotationState.entries.map { it.name }.none { it.contains("BODY") })
    }

    private fun sample(at: Long, turning: Boolean): PedestrianMotionSample {
        val window = PedestrianMotionWindowCoverage(5, at - 200L, at, 50L)
        return PedestrianMotionSample(at, accelerationMagnitudeMeanMps2 = 9.81,
            accelerationMagnitudeVarianceMps4 = 0.02,
            gyroscopeRmsRadPerSecond = if (turning) 0.6 else 0.02,
            gyroscopePeakRadPerSecond = if (turning) 1.2 else 0.05,
            accelerationWindow = window, gyroscopeWindow = window)
    }

    private fun speed(coordinator: PositioningCoordinator, at: Long) = coordinator.observeUserSpeed(
        WalkingSpeedObservation(0.0, 0.1, at), UserMotionLocationEvidence(at, 2.0, false), at,
    )
}

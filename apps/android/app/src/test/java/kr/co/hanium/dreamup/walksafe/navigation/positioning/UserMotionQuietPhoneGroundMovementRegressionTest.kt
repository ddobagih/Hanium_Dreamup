package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 종합분석 6.8: 차량·에스컬레이터에서 휴대폰이 조용해도 근거 있는 지면 이동을 정지/ZUPT로 덮지 않는다.
 * Speeds are synthetic examples of an escalator-like and a vehicle-like fix, not field thresholds.
 */
class UserMotionQuietPhoneGroundMovementRegressionTest {
    @Test
    fun quietPhoneWithSlowOrFastGroundSpeedIsNeverStationaryOrZupt() {
        val cases = listOf(0.45 to 0.2, 0.5 to 0.1, 0.6 to 0.1, 8.0 to 0.5, 15.0 to 0.8)
        for ((speedMps, accuracyMps) in cases) {
            val coordinator = PositioningCoordinator()
            val detector = PedestrianStationaryDetector()
            coordinator.observeGnss(GnssPositionObservation(37.5, 127.0, 2.0, 1_000L, 1_000L))
            for (at in 1_000L..4_000L step 100L) {
                val decision = detector.observe(quiet(at))
                val state = coordinator.observeUserMotion(decision, at)
                coordinator.observeUserSpeed(
                    WalkingSpeedObservation(speedMps, accuracyMps, at), UserMotionLocationEvidence(at, 2.0, false), at,
                )
                val label = "speed=$speedMps±$accuracyMps at=$at detector=${decision.state}"
                val current = coordinator.currentUserMotion(at)
                assertNotEquals(label, UserMovementState.STATIONARY, current.movement)
                assertNotEquals(label, UserLocomotionState.WALKING, current.locomotion)
                assertFalse(label, current.zeroVelocityUpdateAllowed)
                assertFalse(label, state.zeroVelocityUpdateAllowed)
                val zupt = coordinator.observeZupt(ZuptObservation(at), at)
                assertEquals(label, PedestrianMotionUpdateDisposition.HARD_REJECTED, zupt.motionUpdate?.disposition)
            }
            if (speedMps - accuracyMps >= UserMotionStateConfig().movingSpeedLowerBoundMps) {
                assertEquals(UserMovementState.MOVING, coordinator.currentUserMotion(4_000L).movement)
            }
        }
    }

    private fun quiet(at: Long): PedestrianMotionSample {
        val window = PedestrianMotionWindowCoverage(5, at - 200L, at, 50L)
        return PedestrianMotionSample(at, accelerationMagnitudeMeanMps2 = 9.81,
            accelerationMagnitudeVarianceMps4 = 0.02, gyroscopeRmsRadPerSecond = 0.02,
            gyroscopePeakRadPerSecond = 0.05, accelerationWindow = window, gyroscopeWindow = window)
    }
}

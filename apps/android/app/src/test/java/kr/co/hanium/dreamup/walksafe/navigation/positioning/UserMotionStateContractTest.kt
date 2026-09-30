package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Direct checks of the 종합분석 6.2~6.5 motion-state contract (ADD-MOTION-004/016/017/029/030). */
class UserMotionStateContractTest {
    @Test fun walkingAndDeviceTurningAreIndependentAxesAndRotationStaysDeviceBased() {
        val estimator = UserMotionStateEstimator()
        val detector = PedestrianStationaryDetector()
        for (at in 1_000L..3_000L step 100L) {
            estimator.observeMotion(detector.observe(sample(at, turning = true)), at)
            if (at % 500L == 0L) estimator.observeSteps(1, PdrStepQuality.HIGH, at, at)
        }
        val result = estimator.snapshot(3_000L)
        assertEquals(UserMovementState.MOVING, result.movement)
        assertEquals(UserLocomotionState.WALKING, result.locomotion)
        assertEquals(UserDeviceRotationState.TURNING, result.deviceRotation)
        assertFalse(result.zeroVelocityUpdateAllowed)
    }

    @Test fun stateSinceKeepsFirstApprovalWhileSourceTimesAndExpiryFollowNewEvidence() {
        val estimator = UserMotionStateEstimator()
        estimator.observeSteps(1, PdrStepQuality.HIGH, 1_000L, 1_000L)
        assertEquals(1_000L, estimator.snapshot(1_000L).stateSinceElapsedRealtimeMs)
        var latest = estimator.snapshot(1_000L)
        for (at in 1_500L..6_000L step 500L) {
            estimator.observeSteps(1, PdrStepQuality.HIGH, at, at)
            latest = estimator.snapshot(at)
        }
        assertEquals(UserMovementState.MOVING, latest.movement)
        assertEquals(1_000L, latest.stateSinceElapsedRealtimeMs)
        assertEquals(6_000L, latest.stepObservedAtElapsedRealtimeMs)
        assertEquals(6_000L, latest.evaluatedAtElapsedRealtimeMs)
        assertEquals(8_000L, latest.expiresAtElapsedRealtimeMs)
        // stateSince is an approval time, not evidence freshness: it is already older than any TTL.
        assertTrue(latest.evaluatedAtElapsedRealtimeMs - requireNotNull(latest.stateSinceElapsedRealtimeMs) > 2_000L)
    }

    @Test fun publishedExpiryNeverExceedsTheEarliestSupportingSourceExpiry() {
        val stationary = UserMotionStateEstimator()
        feedQuiet(stationary, speedMps = 0.0)
        val still = stationary.snapshot(3_000L)
        assertEquals(UserMovementState.STATIONARY, still.movement)
        // speed 3000+2000, motion sample 3000+500, acceleration/gyroscope windows 3000+250.
        assertEquals(3_250L, still.expiresAtElapsedRealtimeMs)

        val gnssOnly = UserMotionStateEstimator()
        gnssOnly.observeSpeed(WalkingSpeedObservation(1.2, 0.1, 1_000L), location(1_000L), 1_000L)
        val moving = gnssOnly.snapshot(1_500L)
        assertEquals(UserMovementState.MOVING, moving.movement)
        assertEquals(3_000L, moving.expiresAtElapsedRealtimeMs)
        assertEquals(UserMovementState.MOVING, gnssOnly.snapshot(3_000L).movement)
        assertEquals(UserMovementState.UNKNOWN, gnssOnly.snapshot(3_001L).movement)
    }

    @Test fun inertialLossEndsStationaryAndZuptEvenWhileGnssStillShowsMovement() {
        val estimator = UserMotionStateEstimator()
        feedQuiet(estimator, speedMps = 0.0)
        assertEquals(UserMovementState.STATIONARY, estimator.snapshot(3_000L).movement)
        estimator.observeSpeed(WalkingSpeedObservation(1.2, 0.1, 3_600L), location(3_600L), 3_600L)
        val result = estimator.snapshot(3_600L)
        assertEquals(UserMovementState.MOVING, result.movement)
        assertEquals(UserMotionStateReason.GNSS_MOVEMENT, result.reason)
        assertEquals(UserDeviceRotationState.UNKNOWN, result.deviceRotation)
        assertFalse(result.zeroVelocityUpdateAllowed)
        assertFalse(estimator.claimZeroVelocityUpdate(3_000L, 3_600L))
    }

    @Test fun gnssLossKeepsRelativeWalkingWhileAbsolutePositionQualityDegradesSeparately() {
        val coordinator = PositioningCoordinator()
        val initial = coordinator.observeGnss(
            GnssPositionObservation(37.5, 127.0, 1.5, 1_000L, receivedAtElapsedRealtimeMs = 1_000L),
        )
        assertEquals(PositionQuality.HIGH, initial.quality)
        var latest = initial
        for (at in 1_500L..30_000L step 500L) {
            latest = coordinator.observeStep(
                stepCount = 1,
                headingInput = PedestrianHeadingInput(
                    nowElapsedRealtimeMs = at,
                    speed = null,
                    gpsCourse = null,
                    magneticTrueHeading = null,
                    phoneForwardMounted = false,
                ),
                quality = PdrStepQuality.HIGH,
                receivedAtElapsedRealtimeMs = at,
            )
        }
        assertEquals(UserMovementState.MOVING, latest.userMotion?.movement)
        assertEquals(UserLocomotionState.WALKING, latest.userMotion?.locomotion)
        assertNotEquals(PositionQuality.HIGH, latest.quality)
        assertTrue(latest.confidence.guidancePaused)
    }

    @Test fun filteredGnssMovementIsNotFedBackAsIndependentUserMotionEvidence() {
        val coordinator = PositioningCoordinator()
        val metreLongitude = 1.0 / (6_378_137.0 * kotlin.math.cos(Math.toRadians(37.5)) * Math.PI / 180.0)
        var latest = coordinator.observeGnss(GnssPositionObservation(37.5, 127.0, 1.5, 1_000L))
        for (second in 1..6) {
            val at = 1_000L + second * 1_000L
            latest = coordinator.observeGnss(
                GnssPositionObservation(37.5, 127.0 + second * 1.5 * metreLongitude, 1.5, at),
            )
        }
        assertTrue(requireNotNull(latest.filtered).velocityEastMps > 0.0)
        // Only the raw fix's own speed may be motion evidence; the filter's output never votes again.
        assertEquals(UserMovementState.UNKNOWN, latest.userMotion?.movement)
        assertEquals(UserMotionStateReason.NO_EVIDENCE, latest.userMotion?.reason)
        assertEquals(null, latest.userMotion?.speedObservedAtElapsedRealtimeMs)
    }

    private fun feedQuiet(estimator: UserMotionStateEstimator, speedMps: Double) {
        val detector = PedestrianStationaryDetector()
        for (at in 1_000L..3_000L step 100L) {
            estimator.observeMotion(detector.observe(sample(at)), at)
            estimator.observeSpeed(WalkingSpeedObservation(speedMps, 0.1, at), location(at), at)
        }
    }

    private fun sample(at: Long, turning: Boolean = false): PedestrianMotionSample {
        val window = PedestrianMotionWindowCoverage(5, at - 200L, at, 50L)
        return PedestrianMotionSample(at, accelerationMagnitudeMeanMps2 = 9.81,
            accelerationMagnitudeVarianceMps4 = 0.02,
            gyroscopeRmsRadPerSecond = if (turning) 0.3 else 0.02,
            gyroscopePeakRadPerSecond = if (turning) 0.5 else 0.05,
            accelerationWindow = window, gyroscopeWindow = window)
    }

    private fun location(at: Long) = UserMotionLocationEvidence(at, 2.0, false)
}

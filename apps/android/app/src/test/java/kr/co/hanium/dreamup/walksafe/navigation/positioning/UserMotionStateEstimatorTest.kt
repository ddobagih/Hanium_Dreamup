package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import org.junit.Assert.*
import org.junit.Test

class UserMotionStateEstimatorTest {
    @Test fun quietWithoutGnssIsNotGroundStationaryOrZupt() {
        val estimator = UserMotionStateEstimator()
        feed(estimator, withSpeed = false)
        val result = estimator.snapshot(3_000L)
        assertEquals(UserMovementState.UNKNOWN, result.movement)
        assertEquals(UserDeviceRotationState.STABLE, result.deviceRotation)
        assertFalse(result.zeroVelocityUpdateAllowed)
    }

    @Test fun vehicleSpeedWinsOverQuietPhoneWithoutClaimingWalking() {
        val estimator = UserMotionStateEstimator()
        feed(estimator, speed = 8.0)
        val result = estimator.snapshot(3_000L)
        assertEquals(UserMovementState.MOVING, result.movement)
        assertEquals(UserLocomotionState.UNKNOWN, result.locomotion)
        assertEquals(UserMotionStateReason.GNSS_MOVEMENT_WITH_QUIET_DEVICE, result.reason)
        assertFalse(result.zeroVelocityUpdateAllowed)
    }

    @Test fun stationaryAndPhoneRotationAreIndependentButTurningForbidsZupt() {
        val estimator = UserMotionStateEstimator()
        feed(estimator, turning = true)
        val result = estimator.snapshot(3_000L)
        assertEquals(UserMovementState.STATIONARY, result.movement)
        assertEquals(UserDeviceRotationState.TURNING, result.deviceRotation)
        assertFalse(result.zeroVelocityUpdateAllowed)
    }

    @Test fun oneSampleThenTenSecondGapNeverCompletesStationaryDwell() {
        val detector = PedestrianStationaryDetector()
        val estimator = UserMotionStateEstimator()
        val one = sample(1_000L).copy(accelerationWindow = PedestrianMotionWindowCoverage(1, 1_000L, 1_000L, 0L))
        estimator.observeMotion(detector.observe(one), 1_000L)
        estimator.observeMotion(detector.observe(sample(11_000L)), 11_000L)
        speed(estimator, 11_000L, 0.0)
        val result = estimator.snapshot(11_000L)
        assertEquals(UserMovementState.UNKNOWN, result.movement)
        assertFalse(result.zeroVelocityUpdateAllowed)
        assertEquals(PedestrianStationaryState.CANDIDATE, detector.currentState())
    }

    @Test fun invalidCoverageAndMissingGyroscopeCannotAuthorizeZupt() {
        val estimator = UserMotionStateEstimator()
        val detector = PedestrianStationaryDetector()
        for (at in 1_000L..5_000L step 100L) {
            val input = sample(at).copy(gyroscopeRmsRadPerSecond = null, gyroscopePeakRadPerSecond = null,
                gyroscopeWindow = null)
            estimator.observeMotion(detector.observe(input), at)
            speed(estimator, at, 0.0)
        }
        assertFalse(estimator.snapshot(5_000L).zeroVelocityUpdateAllowed)
        val badWindow = sample(5_100L).copy(accelerationWindow = PedestrianMotionWindowCoverage(5, 4_000L, 5_100L, 600L))
        estimator.observeMotion(detector.observe(badWindow), 5_100L)
        assertEquals(UserMovementState.UNKNOWN, estimator.snapshot(5_100L).movement)
    }

    @Test fun noInputExpiresAndEvaluationCannotRefreshOriginalEvidence() {
        val estimator = UserMotionStateEstimator()
        feed(estimator)
        val first = estimator.snapshot(3_000L)
        val read = estimator.snapshot(3_100L)
        assertEquals(UserMovementState.STATIONARY, first.movement)
        assertEquals(first.stateSinceElapsedRealtimeMs, read.stateSinceElapsedRealtimeMs)
        assertEquals(first.expiresAtElapsedRealtimeMs, read.expiresAtElapsedRealtimeMs)
        assertEquals(UserMovementState.UNKNOWN, estimator.snapshot(3_251L).movement)
        assertEquals(UserMotionStateReason.INVALID_TIME, estimator.snapshot(3_000L).reason)
    }

    @Test fun newGenerationRejectsLateOldSpeedStepsAndMotion() {
        val estimator = UserMotionStateEstimator()
        val old = estimator.inputGeneration()
        feed(estimator)
        estimator.reset()
        estimator.observeSpeed(WalkingSpeedObservation(2.0, 0.1, 4_000L), location(4_000L), 4_000L, old)
        estimator.observeSteps(2, PdrStepQuality.HIGH, 4_000L, 4_000L, old)
        estimator.observeMotion(PedestrianStationaryDetector().observe(sample(4_000L)), 4_000L, old)
        val result = estimator.snapshot(4_000L)
        assertEquals(old + 1L, result.generation)
        assertEquals(UserMovementState.UNKNOWN, result.movement)
        assertEquals(UserMotionStateReason.NO_EVIDENCE, result.reason)
    }

    @Test fun invalidFutureAndNanDoNotConsumeSourceOrder() {
        val estimator = UserMotionStateEstimator()
        estimator.observeSpeed(WalkingSpeedObservation(2.0, 0.1, 9_000L), location(9_000L), 1_000L)
        estimator.observeSpeed(WalkingSpeedObservation(Double.NaN, 0.1, 8_000L), location(8_000L), 8_000L)
        speed(estimator, 1_500L, 1.2)
        val result = estimator.snapshot(1_500L)
        assertEquals(UserMovementState.MOVING, result.movement)
        assertEquals(1_500L, result.speedObservedAtElapsedRealtimeMs)
    }

    @Test fun duplicateFixIdentityCannotRefreshSpeedUsingANewEnvelopeTime() {
        val estimator = UserMotionStateEstimator()
        speed(estimator, 1_000L, 1.2)
        estimator.observeSpeed(WalkingSpeedObservation(1.2, 0.1, 2_500L),
            location(2_500L).copy(observationId = 1_000L), 2_500L)
        assertEquals(UserMovementState.UNKNOWN, estimator.snapshot(3_001L).movement)
    }

    @Test fun motionAndSpeedSourcesCanArriveInDifferentMeasurementOrder() {
        val estimator = UserMotionStateEstimator()
        val detector = PedestrianStationaryDetector()
        estimator.observeMotion(detector.observe(sample(2_000L)), 2_100L)
        estimator.observeSpeed(WalkingSpeedObservation(1.2, 0.1, 1_900L), location(1_900L), 2_100L)
        assertEquals(UserMovementState.MOVING, estimator.snapshot(2_100L).movement)
    }

    @Test fun oneBatchIsNotTwoIndependentWalkingEventsAndDuplicateIsIgnored() {
        val estimator = UserMotionStateEstimator()
        estimator.observeSteps(8, PdrStepQuality.HIGH, 1_000L, 1_000L)
        estimator.observeSteps(8, PdrStepQuality.HIGH, 1_000L, 1_100L)
        assertEquals(UserLocomotionState.UNKNOWN, estimator.snapshot(1_100L).locomotion)
        estimator.observeSteps(1, PdrStepQuality.HIGH, 1_500L, 1_500L)
        assertEquals(UserLocomotionState.WALKING, estimator.snapshot(1_500L).locomotion)
        assertEquals(UserMovementState.UNKNOWN, estimator.snapshot(3_501L).movement)
    }

    @Test fun sameSpeedOrOverlappingWindowCannotGenerateRepeatedZupt() {
        val estimator = UserMotionStateEstimator()
        val detector = feed(estimator)
        assertTrue(estimator.claimZeroVelocityUpdate(3_000L, 3_000L))
        assertFalse(estimator.claimZeroVelocityUpdate(3_000L, 3_000L))
        estimator.observeMotion(detector.observe(sample(3_100L)), 3_100L)
        speed(estimator, 3_100L, 0.0)
        assertFalse(estimator.claimZeroVelocityUpdate(3_100L, 3_100L))
        estimator.observeMotion(detector.observe(sample(3_300L)), 3_300L)
        speed(estimator, 3_300L, 0.0)
        assertTrue(estimator.claimZeroVelocityUpdate(3_300L, 3_300L))
    }

    @Test fun duplicateSensorWindowDoesNotAccumulateStationaryTime() {
        val detector = PedestrianStationaryDetector()
        val window = sample(1_000L)
        detector.observe(window)
        for (at in 1_100L..3_000L step 100L) detector.observe(window.copy(elapsedRealtimeMs = at))
        assertEquals(PedestrianStationaryState.UNKNOWN, detector.currentState())
        assertEquals(PedestrianStationaryState.CANDIDATE, detector.observe(sample(3_100L)).state)
    }

    @Test fun detectorWatchdogExpiresWithoutManufacturingObservations() {
        val detector = PedestrianStationaryDetector()
        for (at in 1_000L..3_000L step 100L) detector.observe(sample(at))
        assertEquals(PedestrianStationaryState.STATIONARY, detector.currentState())
        assertEquals(PedestrianStationaryState.UNKNOWN, detector.expire(3_251L))
        assertEquals(PedestrianStationaryState.CANDIDATE, detector.observe(sample(3_400L)).state)
    }

    private fun feed(estimator: UserMotionStateEstimator, speed: Double = 0.0, withSpeed: Boolean = true,
        turning: Boolean = false): PedestrianStationaryDetector {
        val detector = PedestrianStationaryDetector()
        for (at in 1_000L..3_000L step 100L) {
            estimator.observeMotion(detector.observe(sample(at, turning)), at)
            if (withSpeed) speed(estimator, at, speed)
        }
        return detector
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
    private fun speed(estimator: UserMotionStateEstimator, at: Long, value: Double) =
        estimator.observeSpeed(WalkingSpeedObservation(value, 0.1, at), location(at), at)
}

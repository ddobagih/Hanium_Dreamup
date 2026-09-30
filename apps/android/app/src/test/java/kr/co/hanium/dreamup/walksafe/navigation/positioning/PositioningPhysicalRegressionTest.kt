package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import org.junit.Assert.*
import org.junit.Test

class PositioningPhysicalRegressionTest {
    @Test fun futureStaleAndLongGapMotionCannotMutateFilter() {
        val estimator = initialized()
        val before = estimator.estimateAt(1_000L)
        val covariance = estimator.covarianceSnapshot()
        assertEquals(PedestrianMotionHardRejectReason.INVALID_TIME,
            estimator.observePdrStep(step(2_000L).copy(receivedAtElapsedRealtimeMs = 1_999L)).hardRejectReason)
        assertEquals(PedestrianMotionHardRejectReason.STALE,
            estimator.observePdrStep(step(2_000L).copy(receivedAtElapsedRealtimeMs = 12_001L)).hardRejectReason)
        assertEquals(PedestrianMotionHardRejectReason.STALE,
            estimator.observeZupt(ZuptObservation(Long.MAX_VALUE)).hardRejectReason)
        assertEquals(before, estimator.estimateAt(1_000L))
        assertArrayEquals(covariance, estimator.covarianceSnapshot(), 0.0)
        assertEquals(PedestrianMotionUpdateDisposition.APPLIED, estimator.observePdrStep(step(2_000L)).disposition)
    }

    @Test fun unknownCounterBatchDirectionCannotMoveEveryStepAlongLastHeading() {
        val estimator = initialized()
        val before = requireNotNull(estimator.estimateAt(1_000L))
        val result = estimator.observePdrStep(step(2_000L).copy(stepCount = 12))
        assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, result.disposition)
        assertEquals(before.latitude, requireNotNull(result.estimate).latitude, 0.0)
        assertEquals(before.longitude, result.estimate.longitude, 0.0)
        assertTrue(result.estimate.horizontalUncertaintyM > before.horizontalUncertaintyM)
        assertEquals(PedestrianMotionHardRejectReason.INVALID_STEP,
            estimator.observePdrStep(step(3_000L).copy(stepCount = Int.MAX_VALUE)).hardRejectReason)
    }

    @Test fun constantVelocityProcessNoiseIsInvariantToPredictionSubdivision() {
        val coarse = initialized(PedestrianPositionEstimatorConfig(maximumPredictStepSeconds = 1.0))
        val fine = initialized(PedestrianPositionEstimatorConfig(maximumPredictStepSeconds = 0.1))
        assertEquals(requireNotNull(coarse.estimateAt(4_000L)).horizontalUncertaintyM,
            requireNotNull(fine.estimateAt(4_000L)).horizontalUncertaintyM, 1e-10)
    }

    @Test fun sameControlledTrajectoryHasSameDistanceAndProcessNoiseAtDifferentEventRates() {
        val individual = initialized()
        val batched = initialized()
        individual.observePdrStep(step(2_000L).copy(stepLengthSigmaM = 0.0, headingAccuracyDegrees = 0.0))
        individual.observePdrStep(step(3_000L).copy(stepLengthSigmaM = 0.0, headingAccuracyDegrees = 0.0))
        batched.observePdrStep(step(3_000L).copy(stepCount = 2, headingCoversWholeBatch = true,
            stepLengthSigmaM = 0.0, headingAccuracyDegrees = 0.0))
        assertEquals(individual.estimateAt(3_000L), batched.estimateAt(3_000L))
        assertArrayEquals(individual.covarianceSnapshot(), batched.covarianceSnapshot(), 1e-12)
    }

    @Test fun missingAccuracyStillReceivesGnssNoiseInflation() {
        val neutral = PedestrianPositionEstimator().observeGnss(fix(1_000L).copy(horizontalAccuracyM = null))
        val degraded = PedestrianPositionEstimator().observeGnss(fix(1_000L).copy(horizontalAccuracyM = null,
            measurementNoiseMultiplier = 4.0))
        assertEquals(2.0 * requireNotNull(neutral.estimate).horizontalUncertaintyM,
            requireNotNull(degraded.estimate).horizontalUncertaintyM, 1e-12)
    }

    @Test fun futureStepCannotPoisonHeadingOrMovementSourceBeforeValidation() {
        val coordinator = PositioningCoordinator()
        coordinator.observeGnss(fix(1_000L))
        val bad = coordinator.observeStep(1, heading(100_000L), PdrStepQuality.HIGH,
            receivedAtElapsedRealtimeMs = 1_100L)
        assertEquals(PedestrianMotionHardRejectReason.INVALID_TIME, bad.motionUpdate?.hardRejectReason)
        val valid = coordinator.observeStep(1, heading(2_000L), PdrStepQuality.HIGH)
        assertEquals(PedestrianMotionUpdateDisposition.APPLIED, valid.motionUpdate?.disposition)
        assertNotNull(valid.selectedHeading)
    }

    @Test fun resetGenerationRejectsEveryOldInputWithoutRestoringState() {
        val coordinator = PositioningCoordinator()
        val old = coordinator.currentInputGeneration()
        coordinator.observeGnss(fix(1_000L), old)
        coordinator.reset()
        assertEquals(PedestrianMotionHardRejectReason.GENERATION_MISMATCH,
            coordinator.observeGnss(fix(2_000L), old).motionUpdate?.hardRejectReason)
        coordinator.observeUserSpeed(WalkingSpeedObservation(1.0, 0.1, 2_000L),
            UserMotionLocationEvidence(2_000L, 2.0, false), 2_000L, old)
        assertEquals(PedestrianMotionHardRejectReason.GENERATION_MISMATCH,
            coordinator.observeStep(1, heading(2_000L), PdrStepQuality.HIGH, expectedGeneration = old).motionUpdate?.hardRejectReason)
        assertEquals(PedestrianMotionHardRejectReason.GENERATION_MISMATCH,
            coordinator.observeZupt(ZuptObservation(2_000L), expectedGeneration = old).motionUpdate?.hardRejectReason)
        val rejectedMotion = coordinator.observeUserMotion(PedestrianStationaryDetector().observe(
            PedestrianMotionSample(2_000L, stepDetected = true)), 2_000L, old)
        assertEquals(UserMotionStateReason.GENERATION_MISMATCH, rejectedMotion.reason)
        val current = coordinator.evaluate(2_000L)
        assertNull(current.filtered)
        assertEquals(UserMovementState.UNKNOWN, current.userMotion?.movement)
        assertEquals(GnssObservationDisposition.INITIALIZED, coordinator.observeGnss(fix(2_100L)).gnssUpdate?.disposition)
    }

    @Test fun noInputWatchdogExpiresPositionConfidenceAndDoesNotRecoverByRepeatedReads() {
        val coordinator = PositioningCoordinator()
        coordinator.observeGnss(fix(1_000L))
        val expired = coordinator.evaluate(11_001L)
        assertEquals(PositionQuality.UNAVAILABLE, expired.quality)
        assertTrue(expired.confidence.guidancePaused)
        coordinator.observeGnss(fix(12_000L))
        assertTrue(coordinator.evaluate(12_000L).confidence.guidancePaused)
        assertTrue(coordinator.evaluate(12_001L).confidence.guidancePaused)
        assertFalse(coordinator.observeGnss(fix(13_000L)).confidence.guidancePaused)
    }

    @Test fun directZuptCannotBypassMissingPhysicalMotionEvidence() {
        val coordinator = PositioningCoordinator()
        coordinator.observeGnss(fix(1_000L))
        assertEquals(PedestrianMotionHardRejectReason.MOTION_NOT_CONFIRMED,
            coordinator.observeZupt(ZuptObservation(2_000L)).motionUpdate?.hardRejectReason)
    }

    @Test fun futureInvalidGnssDoesNotPoisonRecoveryWatermark() {
        val coordinator = PositioningCoordinator()
        coordinator.observeGnss(fix(1_000L))
        coordinator.observeGnss(fix(90_000L).copy(receivedAtElapsedRealtimeMs = 1_100L))
        val valid = coordinator.observeGnss(fix(2_000L))
        assertEquals(GnssObservationDisposition.APPLIED, valid.gnssUpdate?.disposition)
        assertFalse(valid.confidence.guidancePaused)
    }

    private fun initialized(config: PedestrianPositionEstimatorConfig = PedestrianPositionEstimatorConfig()) =
        PedestrianPositionEstimator(config).also { it.observeGnss(fix(1_000L)) }
    private fun fix(at: Long) = GnssPositionObservation(37.0, 127.0, 1.5, at)
    private fun step(at: Long) = PdrStepObservation(1, 0.65, headingDegreesTrueNorth = 90.0,
        headingAccuracyDegrees = 5.0, quality = PdrStepQuality.HIGH, elapsedRealtimeMs = at)
    private fun heading(at: Long) = PedestrianHeadingInput(at, WalkingSpeedObservation(1.0, 0.1, at),
        HeadingObservation(90.0, 5.0, at), null, false)
}

package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.StepEvent
import kr.co.hanium.dreamup.walksafe.navigation.StepEventConfidence
import kr.co.hanium.dreamup.walksafe.navigation.StepEventSource
import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PositioningCoordinatorStepConflictAdmissionRegressionTest {
    @Test
    fun currentStepConflictingWithStationaryGnssDoesNotMovePositionMean() {
        val coordinator = coordinator()
        // Stored fix, without the different constant-velocity UI forecast.
        val before = requireNotNull(coordinator.evaluate(1_000L).filtered)
        val speed = WalkingSpeedObservation(0.0, 0.1, 1_000L)
        val location = UserMotionLocationEvidence(1_000L, 2.0, false, 11L)
        coordinator.observeUserSpeed(speed, location, 1_100L)

        val result = step(coordinator, speed = speed)

        val motion = requireNotNull(result.userMotion)
        val conflict = motion.support.conflicts.single()
        assertEquals(UserMotionStateReason.STEP_WITH_STATIONARY_GNSS, conflict.reason)
        assertEquals(motion.evidenceRefs.toSet(), conflict.evidenceRefs.toSet())
        val gnssRef = conflict.evidenceRefs.single { it.source == UserMotionEvidenceSource.GNSS_FIX }
        assertEquals(speed, gnssRef.speedObservation)
        assertEquals(location, gnssRef.locationEvidence)
        assertEquals(11L, gnssRef.observationId)
        assertEquals(1_000L, gnssRef.measuredAtElapsedRealtimeMs)
        assertEquals(1_100L, gnssRef.receivedAtElapsedRealtimeMs)
        val stepRef = conflict.evidenceRefs.single { it.stepEvidence != null }
        assertEquals(UserMotionStepEvidence(1, PdrStepQuality.HIGH), stepRef.stepEvidence)
        assertEquals(1_200L, stepRef.measuredAtElapsedRealtimeMs)
        assertEquals(1_300L, stepRef.receivedAtElapsedRealtimeMs)
        assertEquals(UserMovementState.MOVING, motion.movement)
        assertEquals(UserMotionStateReason.STEP_OBSERVED, motion.reason)
        assertFalse(motion.zeroVelocityUpdateAllowed)
        assertEquals(3_200L, motion.expiresAtElapsedRealtimeMs)
        assertCandidateHeading(result)
        assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, result.motionUpdate?.disposition)
        val after = requireNotNull(result.filtered)
        assertEquals(before.localEnu, after.localEnu)
        assertEquals(before.coordinate, after.coordinate)
        assertTrue(after.horizontalUncertaintyM > before.horizontalUncertaintyM)
    }

    @Test
    fun futureStationaryGnssDoesNotRestrictAnEarlierDelayedStep() {
        val coordinator = coordinator()
        val control = coordinator()
        val speed = WalkingSpeedObservation(0.0, 0.1, 1_500L)
        val location = UserMotionLocationEvidence(1_500L, 2.0, false, 22L)
        coordinator.observeUserSpeed(speed, location, 1_500L)
        val heading = PedestrianHeadingInput(1_200L, WalkingSpeedObservation(1.0, 0.1, 1_200L),
            HeadingObservation(0.0, 5.0, 1_200L), null, true)

        val result = coordinator.observeStep(1, heading, PdrStepQuality.HIGH, receivedAtElapsedRealtimeMs = 1_550L)
        val expected = control.observeStep(1, heading, PdrStepQuality.HIGH, receivedAtElapsedRealtimeMs = 1_550L)

        val motion = requireNotNull(result.userMotion)
        val conflict = motion.support.conflicts.single()
        assertEquals(UserMotionStateReason.STEP_WITH_STATIONARY_GNSS, conflict.reason)
        assertEquals(motion.evidenceRefs.toSet(), conflict.evidenceRefs.toSet())
        val gnssRef = conflict.evidenceRefs.single { it.source == UserMotionEvidenceSource.GNSS_FIX }
        assertEquals(speed, gnssRef.speedObservation)
        assertEquals(location, gnssRef.locationEvidence)
        assertEquals(22L, gnssRef.observationId)
        assertEquals(1_500L, gnssRef.measuredAtElapsedRealtimeMs)
        assertEquals(1_500L, gnssRef.receivedAtElapsedRealtimeMs)
        val stepRef = conflict.evidenceRefs.single { it.stepEvidence != null }
        assertEquals(UserMotionStepEvidence(1, PdrStepQuality.HIGH), stepRef.stepEvidence)
        assertEquals(1_200L, stepRef.measuredAtElapsedRealtimeMs)
        assertEquals(1_550L, stepRef.receivedAtElapsedRealtimeMs)
        assertEquals(UserMovementState.MOVING, motion.movement)
        assertEquals(UserMotionStateReason.STEP_OBSERVED, motion.reason)
        assertFalse(motion.zeroVelocityUpdateAllowed)
        assertEquals(3_200L, motion.expiresAtElapsedRealtimeMs)
        assertEquals(PedestrianHeadingSource.GPS_COURSE, result.selectedHeading?.source)
        assertEquals(0.0, requireNotNull(result.selectedHeading).headingDegreesTrueNorth, 1e-9)
        assertEquals(5.0, requireNotNull(result.selectedHeadingAccuracyDegrees), 1e-9)
        assertEquals(1_200L, result.selectedHeading?.sourceObservationAtElapsedRealtimeMs)
        assertNull(result.selectedHeading?.rotationObservationAtElapsedRealtimeMs)
        assertEquals(PedestrianMotionUpdateDisposition.APPLIED, result.motionUpdate?.disposition)
        assertTrue(requireNotNull(result.filtered).localEnu.northM > 0.0)
        assertEquals(expected.motionUpdate, result.motionUpdate)
        assertEquals(expected.filtered, result.filtered)
    }

    @Test
    fun stationaryGnssAtOrBeforeTheOriginalStepStillRestrictsDirection() {
        listOf(1_000L to 1_200L, 1_200L to 1_200L, 1_500L to 1_600L).forEach { (gnssAt, stepAt) ->
            val coordinator = coordinator()
            val before = requireNotNull(coordinator.evaluate(1_000L).filtered)
            val speed = WalkingSpeedObservation(0.0, 0.1, gnssAt)
            val location = UserMotionLocationEvidence(gnssAt, 2.0, false, 22L)
            val speedReceipt = maxOf(gnssAt, 1_100L)
            coordinator.observeUserSpeed(speed, location, speedReceipt)
            val heading = PedestrianHeadingInput(stepAt, WalkingSpeedObservation(1.0, 0.1, stepAt),
                HeadingObservation(0.0, 5.0, stepAt), null, true)

            val result = coordinator.observeStep(1, heading, PdrStepQuality.HIGH, receivedAtElapsedRealtimeMs = 1_700L)

            val conflict = requireNotNull(result.userMotion).support.conflicts.single()
            assertEquals(UserMotionStateReason.STEP_WITH_STATIONARY_GNSS, conflict.reason)
            val gnssRef = conflict.evidenceRefs.single { it.source == UserMotionEvidenceSource.GNSS_FIX }
            assertEquals(speed, gnssRef.speedObservation)
            assertEquals(location, gnssRef.locationEvidence)
            assertEquals(gnssAt, gnssRef.measuredAtElapsedRealtimeMs)
            assertEquals(speedReceipt, gnssRef.receivedAtElapsedRealtimeMs)
            val stepRef = conflict.evidenceRefs.single { it.stepEvidence != null }
            assertEquals(UserMotionStepEvidence(1, PdrStepQuality.HIGH), stepRef.stepEvidence)
            assertEquals(stepAt, stepRef.measuredAtElapsedRealtimeMs)
            assertEquals(1_700L, stepRef.receivedAtElapsedRealtimeMs)
            assertEquals(0.0, requireNotNull(result.selectedHeading).headingDegreesTrueNorth, 1e-9)
            assertEquals(5.0, requireNotNull(result.selectedHeadingAccuracyDegrees), 1e-9)
            assertEquals(stepAt, result.selectedHeading?.sourceObservationAtElapsedRealtimeMs)
            assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, result.motionUpdate?.disposition)
            val after = requireNotNull(result.filtered)
            assertEquals(before.localEnu, after.localEnu)
            assertEquals(before.coordinate, after.coordinate)
            assertTrue(after.horizontalUncertaintyM > before.horizontalUncertaintyM)
        }
    }

    @Test
    fun movingGnssAndHighQualityFreshMagneticStepRemainDirected() {
        val coordinator = coordinator()
        val speed = observeSpeed(coordinator, speedMps = 1.0)

        assertDirectedWithoutStepConflict(step(coordinator, speed = speed))
    }

    @Test
    fun freshStepWithoutAcceptedGnssSpeedRemainsDirected() {
        assertDirectedWithoutStepConflict(step(coordinator()))
    }

    @Test
    fun mockGnssCannotCreateStationaryStepConflict() {
        val coordinator = coordinator()
        val speed = observeSpeed(coordinator, location = UserMotionLocationEvidence(1_000L, 2.0, true, 11L))

        val result = step(coordinator, speed = speed)

        assertDirectedWithoutStepConflict(result)
        assertFalse(requireNotNull(result.userMotion).evidenceRefs.any { it.source == UserMotionEvidenceSource.GNSS_FIX })
    }

    @Test
    fun inaccurateGnssPositionCannotCreateStationaryStepConflict() {
        val coordinator = coordinator()
        val speed = observeSpeed(coordinator, location = UserMotionLocationEvidence(1_000L, 50.0, false, 11L))

        assertDirectedWithoutStepConflict(step(coordinator, speed = speed))
    }

    @Test
    fun inaccurateGnssSpeedCannotCreateStationaryStepConflict() {
        val coordinator = coordinator()
        observeSpeed(coordinator, accuracyMps = 1.0)

        assertDirectedWithoutStepConflict(step(coordinator))
    }

    @Test
    fun expiredGnssEvidenceCannotCreateStationaryStepConflict() {
        val coordinator = coordinator()
        observeSpeed(coordinator)

        val result = step(coordinator, at = 3_101L, receipt = 3_151L)

        assertDirectedWithoutStepConflict(result)
        assertFalse(requireNotNull(result.userMotion).evidenceRefs.any { it.source == UserMotionEvidenceSource.GNSS_FIX })
    }

    @Test
    fun conflictExpiresAndFreshHeadingCanDirectTheNextStep() {
        val coordinator = coordinator()
        val stationarySpeed = observeSpeed(coordinator)
        val conflicted = step(coordinator, speed = stationarySpeed)
        assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, conflicted.motionUpdate?.disposition)
        assertEquals(LocalEnuPosition(0.0, 0.0), requireNotNull(conflicted.filtered).localEnu)

        val fresh = step(coordinator, at = 3_101L, receipt = 3_151L)

        assertDirectedWithoutStepConflict(fresh)
        assertEquals(UserMovementState.MOVING, fresh.userMotion?.movement)
        assertEquals(1_000L, requireNotNull(conflicted.userMotion).support.conflicts.single()
            .evidenceRefs.single { it.source == UserMotionEvidenceSource.GNSS_FIX }.measuredAtElapsedRealtimeMs)
    }

    @Test
    fun stationaryGnssAtItsExistingFreshnessBoundaryStillRestrictsTheCurrentStep() {
        val coordinator = coordinator()
        val speed = observeSpeed(coordinator)

        val result = step(coordinator, at = 3_000L, receipt = 3_000L, speed = speed)

        assertCandidateHeading(result)
        assertEquals(UserMotionStateReason.STEP_WITH_STATIONARY_GNSS,
            requireNotNull(result.userMotion).support.conflicts.single().reason)
        assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, result.motionUpdate?.disposition)
        assertEquals(LocalEnuPosition(0.0, 0.0), requireNotNull(result.filtered).localEnu)
    }

    @Test
    fun lowFallbackKeepsItsExistingQualityLimitWithoutInventingStepEvidence() {
        val coordinator = coordinator()
        val speed = observeSpeed(coordinator)
        val event = event(1, StepEventSource.ACCELEROMETER_FALLBACK, StepEventConfidence.LOW)

        val result = step(coordinator, speed = speed, quality = PdrStepQuality.LOW, original = event)

        assertCandidateHeading(result)
        assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, result.motionUpdate?.disposition)
        val motion = requireNotNull(result.userMotion)
        assertNull(motion.stepObservedAtElapsedRealtimeMs)
        assertTrue(motion.support.conflicts.isEmpty())
        assertFalse(motion.evidenceRefs.any { it.stepEvidence != null })
        assertEquals(UserMovementState.UNKNOWN, motion.movement)
    }

    @Test
    fun conflictingCounterBatchPreservesOriginalQualityCountAndEventFacts() {
        val coordinator = coordinator()
        val speed = observeSpeed(coordinator)
        val original = event(3, StepEventSource.STEP_COUNTER, StepEventConfidence.MEDIUM)

        val result = step(coordinator, speed = speed, count = 3, quality = PdrStepQuality.MEDIUM,
            original = original, coversBatch = true)

        assertCandidateHeading(result)
        assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, result.motionUpdate?.disposition)
        assertEquals(LocalEnuPosition(0.0, 0.0), requireNotNull(result.filtered).localEnu)
        val motion = requireNotNull(result.userMotion)
        val ref = motion.evidenceRefs.single { it.stepEvidence != null }
        assertEquals(UserMotionEvidenceSource.STEP_COUNTER, ref.source)
        assertEquals(UserMotionStepEvidence(3, PdrStepQuality.MEDIUM,
            StepEventSource.STEP_COUNTER, StepEventConfidence.MEDIUM, original), ref.stepEvidence)
        assertEquals(1_200L, ref.measuredAtElapsedRealtimeMs)
        assertEquals(1_300L, ref.receivedAtElapsedRealtimeMs)
        assertEquals(3_200L, motion.expiresAtElapsedRealtimeMs)
        assertEquals(UserMovementState.MOVING, motion.movement)
        assertEquals(UserLocomotionState.UNKNOWN, motion.locomotion)
        assertTrue(motion.support.conflicts.single().evidenceRefs.contains(ref))
    }

    @Test
    fun nonConflictingCounterBatchStillNeedsWholeBatchHeadingCoverage() {
        val coordinator = coordinator()
        val speed = observeSpeed(coordinator, speedMps = 1.0)
        val original = event(3, StepEventSource.STEP_COUNTER, StepEventConfidence.MEDIUM)

        val result = step(coordinator, speed = speed, count = 3, quality = PdrStepQuality.MEDIUM, original = original)

        assertCandidateHeading(result)
        assertTrue(requireNotNull(result.userMotion).support.conflicts.isEmpty())
        assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, result.motionUpdate?.disposition)
        assertEquals(LocalEnuPosition(0.0, 0.0), requireNotNull(result.filtered).localEnu)
        assertEquals(original, requireNotNull(result.userMotion).evidenceRefs.single { it.stepEvidence != null }.stepEvidence?.originalStepEvent)
    }

    @Test
    fun priorOwnerAndGenerationCannotPublishAConflictingCurrentStep() {
        val coordinator = coordinator()
        val current = owner(2L)
        assertTrue(coordinator.beginUserMotionEpoch(current, 900L))
        val speed = observeSpeed(coordinator)
        val generation = coordinator.currentInputGeneration()

        val oldOwner = step(coordinator, speed = speed, expectedOwner = owner(1L))
        val oldGeneration = step(coordinator, speed = speed, expectedGeneration = generation - 1L)

        assertEquals(PedestrianMotionUpdateDisposition.HARD_REJECTED, oldOwner.motionUpdate?.disposition)
        assertEquals(UserMotionStateReason.OWNER_MISMATCH, oldOwner.userMotion?.reason)
        assertEquals(PedestrianMotionHardRejectReason.GENERATION_MISMATCH, oldGeneration.motionUpdate?.hardRejectReason)
        assertNull(coordinator.peekUserMotion(1_300L).stepObservedAtElapsedRealtimeMs)
        assertTrue(coordinator.peekUserMotion(1_300L).support.conflicts.isEmpty())
        val accepted = step(coordinator, speed = speed)
        assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, accepted.motionUpdate?.disposition)
        assertEquals(current, accepted.userMotion?.owner)
        assertEquals(generation, accepted.inputGeneration)
        assertEquals(LocalEnuPosition(0.0, 0.0), requireNotNull(accepted.filtered).localEnu)
    }

    @Test
    fun quietDeviceWithGroundMovementDoesNotLatchAHeadingRestriction() {
        val coordinator = coordinator()
        val detector = PedestrianStationaryDetector()
        for (at in 1_000L..2_600L step 100L) {
            val window = PedestrianMotionWindowCoverage(5, at - 200L, at, 50L)
            val decision = detector.observe(PedestrianMotionSample(at,
                accelerationMagnitudeMeanMps2 = 9.81, accelerationMagnitudeVarianceMps4 = 0.02,
                gyroscopeRmsRadPerSecond = 0.02, gyroscopePeakRadPerSecond = 0.05,
                accelerationWindow = window, gyroscopeWindow = window))
            coordinator.observeUserMotion(decision, at)
        }
        val speed = WalkingSpeedObservation(1.2, 0.1, 2_600L)
        coordinator.observeUserSpeed(speed, UserMotionLocationEvidence(2_600L, 2.0, false, 12L), 2_600L)
        val before = coordinator.peekUserMotion(2_600L)
        assertEquals(UserMovementState.MOVING, before.movement)
        assertEquals(UserMotionStateReason.GNSS_MOVEMENT_WITH_QUIET_DEVICE, before.support.conflicts.single().reason)

        val result = step(coordinator, at = 2_700L, receipt = 2_700L, speed = speed)

        assertDirectedWithoutStepConflict(result)
        assertEquals(UserMovementState.MOVING, result.userMotion?.movement)
    }

    private fun assertCandidateHeading(result: PositioningSnapshot) {
        assertEquals(90.0, requireNotNull(result.selectedHeading).headingDegreesTrueNorth, 1e-9)
        assertEquals(5.0, requireNotNull(result.selectedHeadingAccuracyDegrees), 1e-9)
    }

    private fun assertDirectedWithoutStepConflict(result: PositioningSnapshot) {
        assertCandidateHeading(result)
        assertEquals(PedestrianMotionUpdateDisposition.APPLIED, result.motionUpdate?.disposition)
        assertTrue(requireNotNull(result.filtered).localEnu.eastM > 0.0)
        assertFalse(requireNotNull(result.userMotion).support.conflicts.any {
            it.reason == UserMotionStateReason.STEP_WITH_STATIONARY_GNSS
        })
    }

    private fun coordinator() = PositioningCoordinator().also {
        val result = it.observeGnss(GnssPositionObservation(37.5, 127.0, 1.5, 1_000L, 1_100L))
        assertEquals(GnssObservationDisposition.INITIALIZED, result.gnssUpdate?.disposition)
    }

    private fun observeSpeed(coordinator: PositioningCoordinator, speedMps: Double = 0.0, accuracyMps: Double = 0.1,
        location: UserMotionLocationEvidence = UserMotionLocationEvidence(1_000L, 2.0, false, 11L)) =
        WalkingSpeedObservation(speedMps, accuracyMps, 1_000L).also { coordinator.observeUserSpeed(it, location, 1_100L) }

    private fun event(count: Int, source: StepEventSource, confidence: StepEventConfidence) =
        StepEvent(count, 1_200L, source, confidence, 1_200_000_000L, 1_300_000_000L, 7)

    private fun owner(actor: Long) = UserMotionStateOwner(actor, WalkRuntimeEpoch("walk-$actor", 0L), "boot-1", "process-1")

    private fun step(coordinator: PositioningCoordinator, at: Long = 1_200L, receipt: Long = 1_300L,
        speed: WalkingSpeedObservation = WalkingSpeedObservation(0.3, 0.1, at), count: Int = 1,
        quality: PdrStepQuality = PdrStepQuality.HIGH, original: StepEvent? = null, coversBatch: Boolean = false,
        expectedOwner: UserMotionStateOwner? = coordinator.currentUserMotionOwner(),
        expectedGeneration: Long = coordinator.currentInputGeneration()) = coordinator.observeStep(
            count, PedestrianHeadingInput(at, speed, null, HeadingObservation(90.0, 5.0, at), true), quality,
            receivedAtElapsedRealtimeMs = receipt, expectedMotionOwner = expectedOwner,
            expectedGeneration = expectedGeneration, originalStepEvent = original, headingCoversWholeBatch = coversBatch,
        )
}

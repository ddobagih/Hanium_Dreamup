package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.StepEventConfidence
import kr.co.hanium.dreamup.walksafe.navigation.StepEventSource
import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Source coverage is supplied input provenance, never an independent sample count or field accuracy. */
class UserMotionSupportProvenanceTest {
    @Test
    fun eachInertialSourceKeepsItsOwnActualWindowStatistics() {
        val coordinator = PositioningCoordinator()
        val acceleration = PedestrianMotionWindowCoverage(5, 800L, 1_000L, 50L)
        val gyroscope = PedestrianMotionWindowCoverage(9, 700L, 1_000L, 60L)
        motion(coordinator, quietSample(1_000L).copy(
            accelerationWindow = acceleration, gyroscopeWindow = gyroscope), 1_100L)
        val result = coordinator.currentUserMotion(1_100L)
        val supports = result.support.sourceCoverage.associateBy { it.source }

        assertEquals(acceleration, supports.getValue(UserMotionEvidenceSource.ACCELEROMETER_WINDOW).windowCoverage)
        assertEquals(5, supports.getValue(UserMotionEvidenceSource.ACCELEROMETER_WINDOW).sampleCount)
        assertEquals(50L, supports.getValue(UserMotionEvidenceSource.ACCELEROMETER_WINDOW).maximumGapMs)
        assertEquals(gyroscope, supports.getValue(UserMotionEvidenceSource.GYROSCOPE_WINDOW).windowCoverage)
        assertEquals(9, supports.getValue(UserMotionEvidenceSource.GYROSCOPE_WINDOW).sampleCount)
        assertEquals(60L, supports.getValue(UserMotionEvidenceSource.GYROSCOPE_WINDOW).maximumGapMs)
        assertEquals(result.evidenceRefs, result.support.sourceCoverage.map { it.evidenceRef })
        result.support.sourceCoverage.forEach { assertNull(it.evidenceRef.observationId) }
        assertTrue(result.support.conflicts.isEmpty())
        assertEquals(UserMovementState.UNKNOWN, result.movement)
        assertFalse(result.zeroVelocityUpdateAllowed)
    }

    @Test
    fun gnssAndLegacyStepDoNotInventRawSampleCountsOrWindowGaps() {
        val result = conflictingInputs().currentUserMotion(1_250L)
        assertEquals(2, result.support.sourceCoverage.size)
        result.support.sourceCoverage.forEach { source ->
            assertNull(source.windowCoverage)
            assertNull(source.sampleCount)
            assertNull(source.maximumGapMs)
        }
        val step = result.evidenceRefs.single { it.stepEvidence != null }
        assertEquals(UserMotionStepEvidence(8, PdrStepQuality.MEDIUM), step.stepEvidence)
        assertEquals(UserMotionEvidenceSource.UNKNOWN_STEP_SOURCE, step.source)
        assertNull(step.observationId)
        assertEquals(UserLocomotionState.UNKNOWN, result.locomotion)
    }

    @Test
    fun suppliedDetectorAndCounterFactsReachTheImmutableMotionEvidence() {
        val inputs = listOf(
            Triple(StepEventSource.STEP_DETECTOR, StepEventConfidence.HIGH, PdrStepQuality.HIGH),
            Triple(StepEventSource.STEP_COUNTER, StepEventConfidence.MEDIUM, PdrStepQuality.MEDIUM),
        )
        inputs.forEach { (source, confidence, quality) ->
            val coordinator = PositioningCoordinator()
            step(coordinator, 8, 1_100L, 1_200L, quality, source, confidence)
            val result = coordinator.currentUserMotion(1_250L)
            val ref = result.evidenceRefs.single()
            assertEquals(UserMotionStepEvidence(8, quality, source, confidence), ref.stepEvidence)
            assertEquals(when (source) {
                StepEventSource.STEP_DETECTOR -> UserMotionEvidenceSource.STEP_DETECTOR
                StepEventSource.STEP_COUNTER -> UserMotionEvidenceSource.STEP_COUNTER
                StepEventSource.ACCELEROMETER_FALLBACK -> UserMotionEvidenceSource.ACCELEROMETER_STEP_FALLBACK
            }, ref.source)
            assertEquals(ref, result.support.sourceCoverage.single().evidenceRef)
            assertNull(ref.observationId)
            assertEquals(1_100L, ref.measuredAtElapsedRealtimeMs)
            assertEquals(1_200L, ref.receivedAtElapsedRealtimeMs)
            assertEquals(3_100L, result.expiresAtElapsedRealtimeMs)
            assertEquals(UserMovementState.MOVING, result.movement)
            assertEquals(UserLocomotionState.UNKNOWN, result.locomotion)
        }
    }

    @Test
    fun aSourceIsNotInferredFromConfidenceAndQualityKeepsItsSeparateSuppliedValue() {
        val coordinator = PositioningCoordinator()
        step(coordinator, 1, 1_100L, 1_200L, PdrStepQuality.MEDIUM,
            confidence = StepEventConfidence.HIGH)
        val ref = coordinator.currentUserMotion(1_250L).evidenceRefs.single()

        assertEquals(UserMotionEvidenceSource.UNKNOWN_STEP_SOURCE, ref.source)
        assertEquals(UserMotionStepEvidence(1, PdrStepQuality.MEDIUM, null, StepEventConfidence.HIGH), ref.stepEvidence)
        assertNull(ref.observationId)

        val sourceOnly = PositioningCoordinator()
        step(sourceOnly, 1, 1_100L, 1_200L, source = StepEventSource.STEP_DETECTOR)
        val sourceOnlyRef = sourceOnly.currentUserMotion(1_250L).evidenceRefs.single()
        assertEquals(UserMotionEvidenceSource.STEP_DETECTOR, sourceOnlyRef.source)
        assertEquals(UserMotionStepEvidence(1, PdrStepQuality.HIGH, StepEventSource.STEP_DETECTOR, null),
            sourceOnlyRef.stepEvidence)
    }

    @Test
    fun suppliedFallbackFactsDoNotOverrideTheSeparatelySuppliedPdrQualityPolicy() {
        // API-level quality/metadata independence; the actual producer's LOW quality case is rejected below.
        val coordinator = PositioningCoordinator()
        step(coordinator, 1, 1_100L, 1_200L, PdrStepQuality.MEDIUM,
            StepEventSource.ACCELEROMETER_FALLBACK, StepEventConfidence.LOW)
        val result = coordinator.currentUserMotion(1_250L)
        val ref = result.evidenceRefs.single()
        assertEquals(UserMotionEvidenceSource.ACCELEROMETER_STEP_FALLBACK, ref.source)
        assertEquals(UserMotionStepEvidence(1, PdrStepQuality.MEDIUM,
            StepEventSource.ACCELEROMETER_FALLBACK, StepEventConfidence.LOW), ref.stepEvidence)
        assertEquals(UserMotionEvidenceSource.ACCELEROMETER_STEP_FALLBACK, result.support.sourceCoverage.single().source)
        assertNull(ref.observationId)
        assertEquals(UserMovementState.MOVING, result.movement)
        assertEquals(UserLocomotionState.UNKNOWN, result.locomotion)
        assertEquals(3_100L, result.expiresAtElapsedRealtimeMs)
    }

    @Test
    fun lowFallbackAndDuplicateStepInputsCannotReplaceSourceEvidenceOrRenewItsTtl() {
        val coordinator = PositioningCoordinator()
        step(coordinator, 8, 1_100L, 1_200L, PdrStepQuality.MEDIUM,
            StepEventSource.STEP_COUNTER, StepEventConfidence.MEDIUM)
        val first = coordinator.currentUserMotion(1_250L)
        step(coordinator, 1, 1_500L, 1_550L, PdrStepQuality.LOW,
            StepEventSource.ACCELEROMETER_FALLBACK, StepEventConfidence.LOW)
        step(coordinator, 1, 1_100L, 1_600L, PdrStepQuality.HIGH,
            StepEventSource.STEP_DETECTOR, StepEventConfidence.HIGH)
        val result = coordinator.currentUserMotion(1_800L)

        assertEquals(first.support, result.support)
        assertEquals(first.evidenceRefs, result.evidenceRefs)
        assertEquals(first.expiresAtElapsedRealtimeMs, result.expiresAtElapsedRealtimeMs)
        assertEquals(UserMotionEvidenceSource.STEP_COUNTER, result.evidenceRefs.single().source)
        assertEquals(UserLocomotionState.UNKNOWN, result.locomotion)
        val expired = coordinator.peekUserMotion(3_101L)
        assertTrue(expired.support.sourceCoverage.isEmpty())
        assertTrue(expired.support.conflicts.isEmpty())
        assertEquals(UserMovementState.UNKNOWN, expired.movement)
    }

    @Test
    fun conflictPairsAreCurrentFreshRefsAndPeekingCannotRenewTheirReceiptOrDeadline() {
        val coordinator = conflictingInputs()
        val first = coordinator.currentUserMotion(1_250L)
        val conflict = first.support.conflicts.single()
        assertEquals(UserMotionStateReason.STEP_WITH_STATIONARY_GNSS, conflict.reason)
        assertEquals(first.evidenceRefs.toSet(), conflict.evidenceRefs.toSet())
        val read = coordinator.peekUserMotion(2_500L)
        val nextPublication = coordinator.currentUserMotion(1_300L)
        assertEquals(first.support, read.support)
        assertEquals(first.support, nextPublication.support)
        assertEquals(first.stateSinceElapsedRealtimeMs, nextPublication.stateSinceElapsedRealtimeMs)
        assertEquals(first.expiresAtElapsedRealtimeMs, read.expiresAtElapsedRealtimeMs)

        val expiredGnss = coordinator.currentUserMotion(3_001L)
        assertTrue(expiredGnss.support.conflicts.isEmpty())
        assertEquals(UserMotionEvidenceSource.UNKNOWN_STEP_SOURCE, expiredGnss.support.sourceCoverage.single().source)
        assertEquals(first.expiresAtElapsedRealtimeMs, expiredGnss.expiresAtElapsedRealtimeMs)
        assertEquals(2, first.support.sourceCoverage.size)
        assertEquals(1, first.support.conflicts.size)
    }

    @Test
    fun ownerAndCollectionResetClearSupportAndRejectedOldOwnerCannotRestoreIt() {
        val coordinator = PositioningCoordinator()
        val oldOwner = UserMotionStateOwner(1L, WalkRuntimeEpoch("walk-1", 0L), null, "process-1")
        assertTrue(coordinator.beginUserMotionEpoch(oldOwner, 900L))
        speed(coordinator, WalkingSpeedObservation(0.0, 0.1, 1_000L), 1_000L, 1_100L, oldOwner)
        step(coordinator, 1, 1_100L, 1_200L, owner = oldOwner)
        val first = coordinator.currentUserMotion(1_250L)
        assertEquals(1, first.support.conflicts.size)
        val generation = coordinator.currentInputGeneration()
        val newOwner = oldOwner.copy(walkEpoch = WalkRuntimeEpoch("walk-2", 0L))
        assertTrue(coordinator.beginUserMotionEpoch(newOwner, 1_300L))
        speed(coordinator, WalkingSpeedObservation(0.0, 0.1, 1_400L), 1_400L, 1_420L, oldOwner)
        step(coordinator, 1, 1_410L, 1_430L, owner = oldOwner)
        val reset = coordinator.currentUserMotion(1_450L)
        assertEquals(newOwner, reset.owner)
        assertTrue(reset.support.sourceCoverage.isEmpty())
        assertTrue(reset.support.conflicts.isEmpty())
        assertEquals(generation, coordinator.currentInputGeneration())
        assertEquals(2, first.support.sourceCoverage.size)

        speed(coordinator, WalkingSpeedObservation(0.0, 0.1, 1_500L), 1_500L, 1_520L, newOwner)
        step(coordinator, 1, 1_510L, 1_530L, owner = newOwner)
        assertEquals(1, coordinator.currentUserMotion(1_550L).support.conflicts.size)
        coordinator.reset()
        val collectionReset = coordinator.currentUserMotion(1_600L)
        assertTrue(collectionReset.support.sourceCoverage.isEmpty())
        assertTrue(collectionReset.support.conflicts.isEmpty())
        assertEquals(generation + 1L, coordinator.currentInputGeneration())
    }

    @Test
    fun publishedSupportAndNestedConflictRefsAreReadOnly() {
        val first = conflictingInputs().currentUserMotion(1_250L)
        assertReadOnly { (first.support.sourceCoverage as MutableList<UserMotionSourceSupport>).clear() }
        assertReadOnly { (first.support.conflicts as MutableList<UserMotionEvidenceConflict>).clear() }
        assertReadOnly { (first.support.conflicts.single().evidenceRefs as MutableList<UserMotionEvidenceRef>).clear() }
        assertEquals(2, first.support.sourceCoverage.size)
        assertEquals(2, first.support.conflicts.single().evidenceRefs.size)
    }

    @Test
    fun supportCannotReissueAWindowReceiptAfterAnAcceptedMissingWindow() {
        val coordinator = PositioningCoordinator()
        val detector = PedestrianStationaryDetector()
        val original = quietSample(1_000L)
        motion(coordinator, original, 1_100L, detector)
        val first = coordinator.currentUserMotion(1_100L)
        motion(coordinator, original.copy(elapsedRealtimeMs = 1_100L, accelerationWindow = null), 1_150L, detector)
        assertFalse(coordinator.currentUserMotion(1_150L).support.sourceCoverage.any {
            it.source == UserMotionEvidenceSource.ACCELEROMETER_WINDOW
        })
        motion(coordinator, original.copy(elapsedRealtimeMs = 1_200L), 1_200L, detector)
        val returned = coordinator.currentUserMotion(1_200L)
        val acceleration = returned.support.sourceCoverage.single {
            it.source == UserMotionEvidenceSource.ACCELEROMETER_WINDOW
        }
        assertEquals(1_100L, acceleration.evidenceRef.receivedAtElapsedRealtimeMs)
        assertEquals(1_000L, acceleration.evidenceRef.measuredAtElapsedRealtimeMs)
        assertEquals(original.accelerationWindow, acceleration.windowCoverage)
        assertEquals(first.expiresAtElapsedRealtimeMs, returned.expiresAtElapsedRealtimeMs)
        val expired = coordinator.peekUserMotion(1_251L)
        assertTrue(expired.support.sourceCoverage.isEmpty())
        assertTrue(expired.support.conflicts.isEmpty())
    }

    @Test
    fun aGnssFixWithoutSpeedKeepsCoverageButCannotCreateAStationarySpeedConflict() {
        val coordinator = PositioningCoordinator()
        speed(coordinator, null, 1_000L, 1_100L)
        step(coordinator, 1, 1_100L, 1_200L)
        val result = coordinator.currentUserMotion(1_250L)
        assertEquals(2, result.support.sourceCoverage.size)
        assertNull(result.support.sourceCoverage.single { it.source == UserMotionEvidenceSource.GNSS_FIX }
            .evidenceRef.speedObservation)
        assertTrue(result.support.conflicts.isEmpty())
        assertEquals(setOf(UserMotionStateReason.STEP_OBSERVED), result.reasonCodes)
        assertEquals(UserMovementState.MOVING, result.movement)
        assertFalse(result.zeroVelocityUpdateAllowed)
    }

    @Test
    fun quietDeviceAndAccurateGroundMovementKeepTheirExistingReasonAndConflictPair() {
        val coordinator = PositioningCoordinator()
        val detector = PedestrianStationaryDetector()
        for (at in 1_000L..2_600L step 100L) motion(coordinator, quietSample(at), at, detector)
        speed(coordinator, WalkingSpeedObservation(1.2, 0.1, 2_600L), 2_600L, 2_600L)
        val result = coordinator.currentUserMotion(2_600L)

        assertEquals(UserMovementState.MOVING, result.movement)
        assertEquals(UserLocomotionState.UNKNOWN, result.locomotion)
        assertEquals(UserMotionStateReason.GNSS_MOVEMENT_WITH_QUIET_DEVICE, result.reason)
        assertEquals(setOf(UserMotionStateReason.GNSS_MOVEMENT,
            UserMotionStateReason.GNSS_MOVEMENT_WITH_QUIET_DEVICE), result.reasonCodes)
        val conflict = result.support.conflicts.single()
        assertEquals(UserMotionStateReason.GNSS_MOVEMENT_WITH_QUIET_DEVICE, conflict.reason)
        assertEquals(setOf(UserMotionEvidenceSource.ACCELEROMETER_WINDOW, UserMotionEvidenceSource.GNSS_FIX),
            conflict.evidenceRefs.map { it.source }.toSet())
        assertTrue(result.evidenceRefs.containsAll(conflict.evidenceRefs))
        assertFalse(result.zeroVelocityUpdateAllowed)
    }

    private fun conflictingInputs() = PositioningCoordinator().also { coordinator ->
        speed(coordinator, WalkingSpeedObservation(0.0, 0.1, 1_000L), 1_000L, 1_100L)
        step(coordinator, 8, 1_100L, 1_200L, PdrStepQuality.MEDIUM)
    }

    private fun speed(coordinator: PositioningCoordinator, observation: WalkingSpeedObservation?,
        atMs: Long, receivedAtMs: Long, owner: UserMotionStateOwner? = coordinator.currentUserMotionOwner()) {
        coordinator.observeUserSpeed(observation, UserMotionLocationEvidence(atMs, 2.0, false, atMs),
            receivedAtMs, expectedMotionOwner = owner)
    }

    private fun step(coordinator: PositioningCoordinator, count: Int, atMs: Long, receivedAtMs: Long,
        quality: PdrStepQuality = PdrStepQuality.HIGH, source: StepEventSource? = null,
        confidence: StepEventConfidence? = null, owner: UserMotionStateOwner? = coordinator.currentUserMotionOwner()) {
        coordinator.observeStep(count, PedestrianHeadingInput(
            nowElapsedRealtimeMs = atMs, speed = null, gpsCourse = null,
            magneticTrueHeading = null, phoneForwardMounted = false,
        ), quality, receivedAtElapsedRealtimeMs = receivedAtMs, expectedMotionOwner = owner,
            stepEventSource = source, stepEventConfidence = confidence)
    }

    private fun motion(coordinator: PositioningCoordinator, sample: PedestrianMotionSample, receivedAtMs: Long,
        detector: PedestrianStationaryDetector = PedestrianStationaryDetector()) {
        val decision = detector.observe(sample)
        assertTrue(decision.accepted)
        coordinator.observeUserMotion(decision, receivedAtMs)
    }

    private fun quietSample(atMs: Long): PedestrianMotionSample {
        val window = PedestrianMotionWindowCoverage(5, atMs - 200L, atMs, 50L)
        return PedestrianMotionSample(atMs, accelerationMagnitudeMeanMps2 = 9.81,
            accelerationMagnitudeVarianceMps4 = 0.02, gyroscopeRmsRadPerSecond = 0.02,
            gyroscopePeakRadPerSecond = 0.05, accelerationWindow = window, gyroscopeWindow = window)
    }

    private fun assertReadOnly(mutate: () -> Unit) {
        try {
            mutate()
            fail("Published support and conflict refs must be immutable")
        } catch (_: UnsupportedOperationException) {
            // A consumer cannot rewrite this or a previous navigation publication.
        }
    }
}

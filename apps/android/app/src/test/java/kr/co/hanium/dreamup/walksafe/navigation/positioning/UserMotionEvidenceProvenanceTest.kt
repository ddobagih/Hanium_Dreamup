package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** ADD-MOTION-007/018: accepted evidence must remain distinguishable in the published snapshot. */
class UserMotionEvidenceProvenanceTest {
    @Test
    fun distinctOriginalGnssFixIdsRemainDistinguishableAfterPublication() {
        val first = movingSnapshot(observationId = 11L, receivedAtMs = 1_100L)
        val second = movingSnapshot(observationId = 12L, receivedAtMs = 1_100L)

        assertEquals(UserMovementState.MOVING, first.movement)
        assertEquals(first.movement, second.movement)
        assertNotEquals("The original fix ID must not be discarded by publication", first, second)
    }

    @Test
    fun distinctGnssReceiptTimesRemainDistinguishableWithoutRefreshingMeasurementTime() {
        val first = movingSnapshot(observationId = 11L, receivedAtMs = 1_100L)
        val second = movingSnapshot(observationId = 11L, receivedAtMs = 1_200L)

        assertEquals(first.speedObservedAtElapsedRealtimeMs, second.speedObservedAtElapsedRealtimeMs)
        assertEquals(first.expiresAtElapsedRealtimeMs, second.expiresAtElapsedRealtimeMs)
        assertNotEquals("Receipt provenance must survive without changing the original TTL", first, second)
    }

    @Test
    fun distinctActualWindowSampleCountsRemainDistinguishableAfterPublication() {
        val first = quietSnapshot(sampleCount = 5)
        val second = quietSnapshot(sampleCount = 9)

        assertEquals(UserDeviceRotationState.STABLE, first.deviceRotation)
        assertEquals(first.deviceRotation, second.deviceRotation)
        assertEquals(first.movement, second.movement)
        assertNotEquals("The actual input window statistics must not be discarded", first, second)
    }

    @Test
    fun gnssRefPreservesTheAcceptedInputAndBothTimesWithoutInferringReceptionHealth() {
        val result = movingSnapshot(11L, 1_100L)
        val ref = result.evidenceRefs.single()

        assertEquals(UserMotionEvidenceSource.GNSS_FIX, ref.source)
        assertEquals(11L, ref.observationId)
        assertEquals(1_000L, ref.measuredAtElapsedRealtimeMs)
        assertEquals(1_100L, ref.receivedAtElapsedRealtimeMs)
        assertEquals(UserMotionLocationEvidence(1_000L, 2.0, false, 11L), ref.locationEvidence)
        assertEquals(WalkingSpeedObservation(1.2, 0.1, 1_000L), ref.speedObservation)
        assertNull(ref.windowCoverage)
        assertEquals(UserMotionStateReason.GNSS_MOVEMENT, result.reason)
        assertEquals(setOf(UserMotionStateReason.GNSS_MOVEMENT), result.reasonCodes)
        assertEquals(3_000L, result.expiresAtElapsedRealtimeMs)
        assertFalse(result.zeroVelocityUpdateAllowed)
    }

    @Test
    fun bothInertialRefsExposeActualCoverageWithoutInventingOriginalIds() {
        val result = quietSnapshot(9)
        assertEquals(setOf(UserMotionEvidenceSource.ACCELEROMETER_WINDOW,
            UserMotionEvidenceSource.GYROSCOPE_WINDOW), result.evidenceRefs.map { it.source }.toSet())
        result.evidenceRefs.forEach { ref ->
            assertEquals(PedestrianMotionWindowCoverage(9, 800L, 1_000L, 50L), ref.windowCoverage)
            assertEquals(1_000L, ref.measuredAtElapsedRealtimeMs)
            assertEquals(1_100L, ref.receivedAtElapsedRealtimeMs)
            assertNull(ref.observationId)
            assertNull(ref.locationEvidence)
            assertNull(ref.speedObservation)
        }
        assertEquals(UserMovementState.UNKNOWN, result.movement)
        assertFalse(result.zeroVelocityUpdateAllowed)
    }

    @Test
    fun repackagingTheSameWindowCannotRefreshItsReceiptOrOriginalExpiry() {
        val estimator = UserMotionStateEstimator()
        val sample = quietSample(1_000L, 5)
        estimator.observeMotion(PedestrianStationaryDetector().observe(sample), 1_100L)
        val first = estimator.snapshot(1_100L)
        estimator.observeMotion(PedestrianStationaryDetector().observe(
            sample.copy(elapsedRealtimeMs = 1_150L)), 1_200L)
        val repackaged = estimator.snapshot(1_200L)

        assertEquals(first.evidenceRefs, repackaged.evidenceRefs)
        assertEquals(first.expiresAtElapsedRealtimeMs, repackaged.expiresAtElapsedRealtimeMs)
        assertTrue(estimator.peek(1_251L).evidenceRefs.isEmpty())
        assertFalse(estimator.peek(1_251L).zeroVelocityUpdateAllowed)
    }

    @Test
    fun theSameWindowAfterAMissingWindowCannotAcquireANewFirstReceipt() {
        val estimator = UserMotionStateEstimator()
        val detector = PedestrianStationaryDetector()
        val original = quietSample(1_000L, 5)
        val firstDecision = detector.observe(original)
        assertTrue(firstDecision.accepted)
        estimator.observeMotion(firstDecision, 1_100L)
        val first = estimator.snapshot(1_100L)

        val missingDecision = detector.observe(original.copy(elapsedRealtimeMs = 1_100L,
            accelerationWindow = null))
        assertTrue(missingDecision.accepted)
        estimator.observeMotion(missingDecision, 1_150L)
        assertFalse(estimator.snapshot(1_150L).evidenceRefs.any {
            it.source == UserMotionEvidenceSource.ACCELEROMETER_WINDOW
        })

        val returningDecision = detector.observe(original.copy(elapsedRealtimeMs = 1_200L))
        assertTrue(returningDecision.accepted)
        estimator.observeMotion(returningDecision, 1_200L)
        val returned = estimator.snapshot(1_200L)
        val acceleration = returned.evidenceRefs.single {
            it.source == UserMotionEvidenceSource.ACCELEROMETER_WINDOW
        }
        assertEquals(1_100L, acceleration.receivedAtElapsedRealtimeMs)
        assertEquals(1_000L, acceleration.measuredAtElapsedRealtimeMs)
        assertEquals(original.accelerationWindow, acceleration.windowCoverage)
        assertEquals(first.expiresAtElapsedRealtimeMs, returned.expiresAtElapsedRealtimeMs)
        assertEquals(first.movement, returned.movement)
        assertFalse(returned.zeroVelocityUpdateAllowed)
    }

    @Test
    fun aFreshStepAndGnssMovementKeepBothReasonsWithTheExistingStepPriority() {
        val estimator = UserMotionStateEstimator()
        movingSpeed(estimator, 1_000L, 11L, 1_100L)
        estimator.observeSteps(1, PdrStepQuality.HIGH, 1_100L, 1_200L)
        val result = estimator.snapshot(1_250L)

        assertEquals(UserMovementState.MOVING, result.movement)
        assertEquals(UserLocomotionState.UNKNOWN, result.locomotion)
        assertEquals(UserMotionStateReason.STEP_OBSERVED, result.reason)
        assertEquals(setOf(UserMotionStateReason.STEP_OBSERVED, UserMotionStateReason.GNSS_MOVEMENT),
            result.reasonCodes)
        val step = result.evidenceRefs.single { it.source == UserMotionEvidenceSource.UNKNOWN_STEP_SOURCE }
        assertNull(step.observationId)
        assertEquals(1_100L, step.measuredAtElapsedRealtimeMs)
        assertEquals(1_200L, step.receivedAtElapsedRealtimeMs)
        assertFalse(result.zeroVelocityUpdateAllowed)
    }

    @Test
    fun quietDeviceAndGroundMovementKeepBothReasonsWithoutClaimingWalkingOrZupt() {
        val estimator = UserMotionStateEstimator()
        val detector = PedestrianStationaryDetector()
        for (at in 1_000L..2_600L step 100L) {
            estimator.observeMotion(detector.observe(quietSample(at, 5)), at)
        }
        movingSpeed(estimator, 2_600L, 11L, 2_600L)
        val result = estimator.snapshot(2_600L)

        assertEquals(UserMovementState.MOVING, result.movement)
        assertEquals(UserLocomotionState.UNKNOWN, result.locomotion)
        assertEquals(UserMotionStateReason.GNSS_MOVEMENT_WITH_QUIET_DEVICE, result.reason)
        assertEquals(setOf(UserMotionStateReason.GNSS_MOVEMENT,
            UserMotionStateReason.GNSS_MOVEMENT_WITH_QUIET_DEVICE), result.reasonCodes)
        assertFalse(result.zeroVelocityUpdateAllowed)
    }

    @Test
    fun anUnconfirmedGnssSpikeKeepsTheExistingStillnessAndWithholdsZupt() {
        val estimator = UserMotionStateEstimator()
        val detector = PedestrianStationaryDetector()
        for (at in 1_000L..3_000L step 100L) {
            estimator.observeMotion(detector.observe(quietSample(at, 5)), at)
            estimator.observeSpeed(WalkingSpeedObservation(0.0, 0.1, at),
                UserMotionLocationEvidence(at, 2.0, false), at)
        }
        assertEquals(UserMovementState.STATIONARY, estimator.snapshot(3_000L).movement)
        estimator.observeMotion(detector.observe(quietSample(3_100L, 5)), 3_100L)
        movingSpeed(estimator, 3_100L, 3_100L, 3_100L)
        val result = estimator.snapshot(3_100L)

        assertEquals(UserMovementState.STATIONARY, result.movement)
        assertEquals(UserMotionStateReason.GNSS_MOVEMENT_UNCONFIRMED, result.reason)
        assertEquals(setOf(UserMotionStateReason.GNSS_MOVEMENT_UNCONFIRMED,
            UserMotionStateReason.GNSS_MOVEMENT_WITH_QUIET_DEVICE), result.reasonCodes)
        assertFalse(result.zeroVelocityUpdateAllowed)
    }

    @Test
    fun duplicateGnssIdentityCannotReplaceProvenanceOrRefreshExpiredMovementReasons() {
        val estimator = UserMotionStateEstimator()
        movingSpeed(estimator, 1_000L, 11L, 1_100L)
        val first = estimator.snapshot(1_200L)
        movingSpeed(estimator, 2_000L, 11L, 2_100L)
        val duplicate = estimator.snapshot(2_250L)

        assertEquals(first.evidenceRefs, duplicate.evidenceRefs)
        assertEquals(first.reasonCodes, duplicate.reasonCodes)
        assertEquals(first.expiresAtElapsedRealtimeMs, duplicate.expiresAtElapsedRealtimeMs)
        val expired = estimator.snapshot(3_001L)
        assertEquals(UserMovementState.UNKNOWN, expired.movement)
        assertTrue(expired.evidenceRefs.isEmpty())
        assertEquals(setOf(UserMotionStateReason.EXPIRED_EVIDENCE), expired.reasonCodes)
    }

    @Test
    fun ownerAndGenerationResetClearRefsAndOldOwnerCannotRestoreThem() {
        val estimator = UserMotionStateEstimator()
        val oldOwner = UserMotionStateOwner(1L, WalkRuntimeEpoch("walk-1", 0L), null, "process-1")
        val newOwner = oldOwner.copy(walkEpoch = WalkRuntimeEpoch("walk-2", 0L))
        assertTrue(estimator.beginEpoch(oldOwner, 0L))
        estimator.observeSpeed(WalkingSpeedObservation(1.2, 0.1, 1_000L),
            UserMotionLocationEvidence(1_000L, 2.0, false, 11L), 1_100L, expectedOwner = oldOwner)
        val previous = estimator.snapshot(1_200L)

        assertTrue(estimator.beginEpoch(newOwner, 1_300L))
        estimator.observeSpeed(WalkingSpeedObservation(1.2, 0.1, 1_400L),
            UserMotionLocationEvidence(1_400L, 2.0, false, 12L), 1_450L, expectedOwner = oldOwner)
        val cleared = estimator.snapshot(1_500L)
        assertTrue(cleared.evidenceRefs.isEmpty())
        assertEquals(setOf(UserMotionStateReason.NO_EVIDENCE), cleared.reasonCodes)
        assertEquals(newOwner, cleared.owner)
        assertEquals(11L, previous.evidenceRefs.single().observationId)
        estimator.observeSpeed(WalkingSpeedObservation(1.2, 0.1, 1_600L),
            UserMotionLocationEvidence(1_600L, 2.0, false, 12L), 1_650L, expectedOwner = newOwner)
        assertEquals(12L, estimator.peek(1_700L).evidenceRefs.single().observationId)
        estimator.reset()
        assertTrue(estimator.peek(1_800L).evidenceRefs.isEmpty())
        assertEquals(previous.generation + 1L, estimator.inputGeneration())
    }

    @Test
    fun peekDoesNotPublishOrRefreshRefsAndPublishedCollectionsCannotBeMutated() {
        val estimator = UserMotionStateEstimator()
        movingSpeed(estimator, 1_000L, 11L, 1_100L)
        val first = estimator.snapshot(1_200L)
        val read = estimator.peek(2_500L)
        val navigation = estimator.snapshot(1_250L)

        assertEquals(first.evidenceRefs, read.evidenceRefs)
        assertEquals(first.evidenceRefs, navigation.evidenceRefs)
        assertEquals(first.stateSinceElapsedRealtimeMs, navigation.stateSinceElapsedRealtimeMs)
        assertEquals(first.expiresAtElapsedRealtimeMs, navigation.expiresAtElapsedRealtimeMs)
        assertEquals(UserMotionStateReason.GNSS_MOVEMENT, navigation.reason)
        assertReadOnly { (first.evidenceRefs as MutableList<UserMotionEvidenceRef>).clear() }
        assertReadOnly { (first.reasonCodes as MutableSet<UserMotionStateReason>).clear() }
        movingSpeed(estimator, 1_300L, 12L, 1_400L)
        assertEquals(12L, estimator.snapshot(1_500L).evidenceRefs.single().observationId)
        assertEquals(11L, first.evidenceRefs.single().observationId)
    }

    private fun movingSnapshot(observationId: Long, receivedAtMs: Long): UserMotionStateSnapshot {
        val estimator = UserMotionStateEstimator()
        movingSpeed(estimator, 1_000L, observationId, receivedAtMs)
        return estimator.snapshot(1_250L)
    }

    private fun movingSpeed(estimator: UserMotionStateEstimator, at: Long, observationId: Long, receivedAtMs: Long) {
        estimator.observeSpeed(
            WalkingSpeedObservation(1.2, 0.1, at),
            UserMotionLocationEvidence(at, 2.0, false, observationId),
            receivedAtMs,
        )
    }

    private fun quietSnapshot(sampleCount: Int): UserMotionStateSnapshot {
        val estimator = UserMotionStateEstimator()
        val decision = PedestrianStationaryDetector().observe(quietSample(1_000L, sampleCount))
        assertTrue(decision.accepted)
        estimator.observeMotion(decision, 1_100L)
        return estimator.snapshot(1_250L)
    }

    private fun quietSample(at: Long, sampleCount: Int): PedestrianMotionSample {
        val window = PedestrianMotionWindowCoverage(sampleCount, at - 200L, at, 50L)
        return PedestrianMotionSample(
            elapsedRealtimeMs = at,
            accelerationMagnitudeMeanMps2 = 9.81,
            accelerationMagnitudeVarianceMps4 = 0.02,
            gyroscopeRmsRadPerSecond = 0.02,
            gyroscopePeakRadPerSecond = 0.05,
            accelerationWindow = window,
            gyroscopeWindow = window,
        )
    }

    private fun assertReadOnly(mutate: () -> Unit) {
        try {
            mutate()
            fail("Published evidence collections must be immutable")
        } catch (_: UnsupportedOperationException) {
            // A consumer cannot mutate the navigation owner's evidence or an earlier publication.
        }
    }
}

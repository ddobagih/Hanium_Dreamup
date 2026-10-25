package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADD-MOTION-018: preserve supplied event facts without treating a batch as independent events. */
class UserMotionStepProvenanceRegressionTest {
    @Test
    fun oneHighQualityStepAndOneMediumQualityBatchKeepTheirDistinctSuppliedEvidence() {
        val single = stepSnapshot(1, PdrStepQuality.HIGH)
        val batch = stepSnapshot(8, PdrStepQuality.MEDIUM)
        assertSameClassificationAndTtl(single, batch)
        assertNotEquals("One callback's supplied count/quality must survive in its evidence", single, batch)
    }

    @Test
    fun aDifferentSuppliedStepCountRemainsVisibleWithoutChangingIndependentEventCount() {
        val single = stepSnapshot(1, PdrStepQuality.HIGH)
        val batch = stepSnapshot(8, PdrStepQuality.HIGH)
        assertSameClassificationAndTtl(single, batch)
        assertNotEquals("A batch count is input provenance, not eight independent events", single.evidenceRefs, batch.evidenceRefs)
    }

    @Test
    fun aDifferentSuppliedPdrQualityRemainsVisibleAtTheSameMeasurementTime() {
        val high = stepSnapshot(1, PdrStepQuality.HIGH)
        val medium = stepSnapshot(1, PdrStepQuality.MEDIUM)
        assertSameClassificationAndTtl(high, medium)
        assertNotEquals("The supplied PDR quality must not be discarded", high.evidenceRefs, medium.evidenceRefs)
    }

    @Test
    fun acceptedBatchPreservesTheSuppliedValuesWithoutInventingAnOriginalSourceOrId() {
        val batch = stepSnapshot(8, PdrStepQuality.MEDIUM)
        val ref = batch.evidenceRefs.single()
        assertEquals(UserMotionStepEvidence(8, PdrStepQuality.MEDIUM), ref.stepEvidence)
        assertEquals(UserMotionEvidenceSource.UNKNOWN_STEP_SOURCE, ref.source)
        assertNull(ref.observationId)
        assertEquals(1_000L, ref.measuredAtElapsedRealtimeMs)
        assertEquals(1_050L, ref.receivedAtElapsedRealtimeMs)
        assertEquals(UserLocomotionState.UNKNOWN, batch.locomotion)
    }

    @Test
    fun lowInvalidAndDuplicateEventsCannotReplaceAcceptedEvidenceOrRenewItsTtl() {
        val coordinator = PositioningCoordinator()
        coordinator.observeStep(1, heading(1_000L), PdrStepQuality.HIGH, receivedAtElapsedRealtimeMs = 1_050L)
        val first = coordinator.currentUserMotion(1_100L)
        coordinator.observeStep(8, heading(1_500L), PdrStepQuality.LOW, receivedAtElapsedRealtimeMs = 1_550L)
        coordinator.observeStep(0, heading(1_600L), PdrStepQuality.HIGH, receivedAtElapsedRealtimeMs = 1_650L)
        coordinator.observeStep(8, heading(1_000L), PdrStepQuality.MEDIUM, receivedAtElapsedRealtimeMs = 1_700L)
        val unchanged = coordinator.currentUserMotion(1_800L)
        assertEquals(first.evidenceRefs, unchanged.evidenceRefs)
        assertEquals(first.expiresAtElapsedRealtimeMs, unchanged.expiresAtElapsedRealtimeMs)
        assertEquals(UserMotionStepEvidence(1, PdrStepQuality.HIGH), unchanged.evidenceRefs.single().stepEvidence)
        assertEquals(UserLocomotionState.UNKNOWN, unchanged.locomotion)
        assertEquals(UserMovementState.UNKNOWN, coordinator.currentUserMotion(3_001L).movement)
    }

    @Test
    fun aSecondIndependentEventUpdatesLatestEvidenceWithoutMutatingThePublishedBatch() {
        val coordinator = PositioningCoordinator()
        coordinator.observeStep(8, heading(1_000L), PdrStepQuality.MEDIUM, receivedAtElapsedRealtimeMs = 1_050L)
        val batch = coordinator.currentUserMotion(1_100L)
        assertEquals(UserLocomotionState.UNKNOWN, batch.locomotion)
        coordinator.observeStep(1, heading(1_500L), PdrStepQuality.HIGH, receivedAtElapsedRealtimeMs = 1_550L)
        val walking = coordinator.currentUserMotion(1_600L)
        assertEquals(UserLocomotionState.WALKING, walking.locomotion)
        assertEquals(UserMotionStepEvidence(1, PdrStepQuality.HIGH), walking.evidenceRefs.single().stepEvidence)
        assertEquals(UserMotionStepEvidence(8, PdrStepQuality.MEDIUM), batch.evidenceRefs.single().stepEvidence)
        assertEquals(3_000L, batch.expiresAtElapsedRealtimeMs)
        assertEquals(3_500L, walking.expiresAtElapsedRealtimeMs)
    }

    @Test
    fun stepMovementExpiresAtItsMeasurementDeadlineAndOldOwnerCannotRestoreIt() {
        val coordinator = PositioningCoordinator()
        val generation = coordinator.currentInputGeneration()
        val oldOwner = UserMotionStateOwner(1L, WalkRuntimeEpoch("walk-1", 0L), null, "process-1")
        assertTrue(coordinator.beginUserMotionEpoch(oldOwner, 950L))
        coordinator.observeStep(8, heading(1_000L), PdrStepQuality.MEDIUM,
            receivedAtElapsedRealtimeMs = 1_050L, expectedMotionOwner = oldOwner)
        val current = coordinator.currentUserMotion(1_100L)
        assertEquals(oldOwner, current.owner)
        assertEquals(UserMovementState.MOVING, current.movement)
        assertEquals(UserLocomotionState.UNKNOWN, current.locomotion)
        assertEquals(3_000L, current.expiresAtElapsedRealtimeMs)
        assertEquals(UserMovementState.MOVING, coordinator.peekUserMotion(3_000L).movement)
        val expired = coordinator.currentUserMotion(3_001L)
        assertEquals(UserMovementState.UNKNOWN, expired.movement)
        assertTrue(expired.evidenceRefs.isEmpty())

        val newOwner = oldOwner.copy(walkEpoch = WalkRuntimeEpoch("walk-2", 0L))
        assertTrue(coordinator.beginUserMotionEpoch(newOwner, 4_000L))
        coordinator.observeStep(8, heading(4_100L), PdrStepQuality.MEDIUM,
            receivedAtElapsedRealtimeMs = 4_150L, expectedMotionOwner = oldOwner)
        val reset = coordinator.currentUserMotion(4_200L)
        assertEquals(newOwner, reset.owner)
        assertEquals(UserMotionStateReason.NO_EVIDENCE, reset.reason)
        assertTrue(reset.evidenceRefs.isEmpty())
        assertEquals(generation, coordinator.currentInputGeneration())
    }

    private fun stepSnapshot(count: Int, quality: PdrStepQuality): UserMotionStateSnapshot {
        val coordinator = PositioningCoordinator()
        coordinator.observeStep(count, heading(1_000L), quality, receivedAtElapsedRealtimeMs = 1_050L)
        return coordinator.currentUserMotion(1_100L)
    }

    private fun assertSameClassificationAndTtl(first: UserMotionStateSnapshot, second: UserMotionStateSnapshot) {
        assertEquals(UserMovementState.MOVING, first.movement)
        assertEquals(UserLocomotionState.UNKNOWN, first.locomotion)
        assertEquals(UserMotionStateReason.STEP_OBSERVED, first.reason)
        assertEquals(first.movement, second.movement)
        assertEquals(first.locomotion, second.locomotion)
        assertEquals(first.reason, second.reason)
        assertEquals(first.expiresAtElapsedRealtimeMs, second.expiresAtElapsedRealtimeMs)
    }

    private fun heading(at: Long) = PedestrianHeadingInput(
        nowElapsedRealtimeMs = at, speed = null, gpsCourse = null,
        magneticTrueHeading = null, phoneForwardMounted = false,
    )
}

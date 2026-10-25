package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADD-MOTION-007: omitting a provider identity must not manufacture one from measurement time. */
class UserMotionMissingGnssIdentityRegressionTest {
    @Test
    fun theActualMainStyleInputWithoutAnIdKeepsItsOriginalIdentityUnknown() {
        val coordinator = PositioningCoordinator()
        // The current Main supplies these three fields and omits observationId.
        coordinator.observeUserSpeed(WalkingSpeedObservation(1.2, 0.1, 1_000L),
            UserMotionLocationEvidence(1_000L, 2.0, false), 1_100L)
        val result = coordinator.currentUserMotion(1_200L)
        val ref = result.evidenceRefs.single()

        assertEquals(UserMovementState.MOVING, result.movement)
        assertEquals(UserMotionStateReason.GNSS_MOVEMENT, result.reason)
        assertEquals(3_000L, result.expiresAtElapsedRealtimeMs)
        assertEquals(1_000L, ref.measuredAtElapsedRealtimeMs)
        assertEquals(1_100L, ref.receivedAtElapsedRealtimeMs)
        assertEquals(2.0, requireNotNull(ref.locationEvidence).horizontalAccuracyM)
        assertFalse(result.zeroVelocityUpdateAllowed)
        assertNull("Measurement time is not a supplied provider fix identity", ref.observationId)
    }

    @Test
    fun aProvidedIdAndItsFirstReceiptSurviveDuplicateRepackaging() {
        val coordinator = PositioningCoordinator()
        speed(coordinator, 1_000L, 1_100L, id = 11L)
        val first = coordinator.currentUserMotion(1_200L)
        speed(coordinator, 1_000L, 1_250L, id = 11L)
        speed(coordinator, 1_400L, 1_500L, id = 11L)
        val result = coordinator.currentUserMotion(1_600L)

        assertEquals(first.evidenceRefs, result.evidenceRefs)
        assertEquals(11L, result.evidenceRefs.single().observationId)
        assertEquals(1_000L, result.evidenceRefs.single().measuredAtElapsedRealtimeMs)
        assertEquals(1_100L, result.evidenceRefs.single().receivedAtElapsedRealtimeMs)
        assertEquals(first.expiresAtElapsedRealtimeMs, result.expiresAtElapsedRealtimeMs)
        assertEquals(UserMovementState.MOVING, result.movement)
    }

    @Test
    fun aMissingIdBetweenKnownInputsCannotAllowTheKnownDuplicateToRenewEvidence() {
        val coordinator = PositioningCoordinator()
        speed(coordinator, 1_000L, 1_050L, id = 11L)
        speed(coordinator, 1_100L, 1_200L)
        val missing = coordinator.currentUserMotion(1_250L)
        assertEquals(1_100L, missing.evidenceRefs.single().measuredAtElapsedRealtimeMs)
        assertEquals(1_200L, missing.evidenceRefs.single().receivedAtElapsedRealtimeMs)
        assertEquals(3_100L, missing.expiresAtElapsedRealtimeMs)

        speed(coordinator, 1_300L, 1_400L, id = 11L)
        val duplicate = coordinator.currentUserMotion(1_450L)
        assertEquals(missing.evidenceRefs, duplicate.evidenceRefs)
        assertEquals(missing.support, duplicate.support)
        assertEquals(missing.expiresAtElapsedRealtimeMs, duplicate.expiresAtElapsedRealtimeMs)
        assertEquals(UserMovementState.MOVING, duplicate.movement)

        speed(coordinator, 1_500L, 1_550L, id = 12L)
        val next = coordinator.currentUserMotion(1_600L)
        assertEquals(12L, next.evidenceRefs.single().observationId)
        assertEquals(1_500L, next.evidenceRefs.single().measuredAtElapsedRealtimeMs)
        assertEquals(1_550L, next.evidenceRefs.single().receivedAtElapsedRealtimeMs)
        assertEquals(3_500L, next.expiresAtElapsedRealtimeMs)
        assertEquals(UserMovementState.MOVING, next.movement)
    }

    @Test
    fun anOmittedIdStillUsesMeasurementTimeForDuplicateOutOfOrderAndExpiryGuards() {
        val coordinator = PositioningCoordinator()
        speed(coordinator, 1_000L, 1_100L)
        val first = coordinator.currentUserMotion(1_200L)
        speed(coordinator, 1_000L, 1_250L)
        speed(coordinator, 950L, 1_300L)
        val result = coordinator.currentUserMotion(1_400L)
        assertEquals(first.evidenceRefs, result.evidenceRefs)
        assertEquals(3_000L, result.expiresAtElapsedRealtimeMs)

        val expired = coordinator.currentUserMotion(3_001L)
        assertEquals(UserMovementState.UNKNOWN, expired.movement)
        assertEquals(UserMotionStateReason.EXPIRED_EVIDENCE, expired.reason)
        assertTrue(expired.evidenceRefs.isEmpty())
        assertFalse(expired.zeroVelocityUpdateAllowed)
    }

    @Test
    fun ownerAndCollectionResetClearIdentityEvidenceAndOldOwnersCannotRestoreIt() {
        val coordinator = PositioningCoordinator()
        val oldOwner = UserMotionStateOwner(1L, WalkRuntimeEpoch("walk-1", 0L), null, "process-1")
        assertTrue(coordinator.beginUserMotionEpoch(oldOwner, 900L))
        speed(coordinator, 1_000L, 1_100L, id = 11L, owner = oldOwner)
        val first = coordinator.currentUserMotion(1_200L)
        val generation = coordinator.currentInputGeneration()
        val newOwner = oldOwner.copy(walkEpoch = WalkRuntimeEpoch("walk-2", 0L))
        assertTrue(coordinator.beginUserMotionEpoch(newOwner, 1_300L))
        speed(coordinator, 1_400L, 1_450L, id = 12L, owner = oldOwner)
        val reset = coordinator.currentUserMotion(1_460L)
        assertEquals(newOwner, reset.owner)
        assertTrue(reset.evidenceRefs.isEmpty())
        assertEquals(UserMovementState.UNKNOWN, reset.movement)
        assertEquals(generation, coordinator.currentInputGeneration())

        speed(coordinator, 1_500L, 1_550L, id = 11L, owner = newOwner)
        assertEquals(11L, coordinator.currentUserMotion(1_600L).evidenceRefs.single().observationId)
        assertEquals(11L, first.evidenceRefs.single().observationId)
        coordinator.reset()
        assertTrue(coordinator.currentUserMotion(1_650L).evidenceRefs.isEmpty())
        assertEquals(generation + 1L, coordinator.currentInputGeneration())
    }

    private fun speed(coordinator: PositioningCoordinator, measuredAtMs: Long, receivedAtMs: Long,
        id: Long? = null, owner: UserMotionStateOwner? = coordinator.currentUserMotionOwner()) {
        val evidence = if (id == null) UserMotionLocationEvidence(measuredAtMs, 2.0, false)
            else UserMotionLocationEvidence(measuredAtMs, 2.0, false, id)
        coordinator.observeUserSpeed(WalkingSpeedObservation(1.2, 0.1, measuredAtMs), evidence,
            receivedAtMs, expectedMotionOwner = owner)
    }
}

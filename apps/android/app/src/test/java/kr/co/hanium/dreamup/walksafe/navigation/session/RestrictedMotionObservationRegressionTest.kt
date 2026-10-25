package kr.co.hanium.dreamup.walksafe.navigation.session

import kr.co.hanium.dreamup.walksafe.navigation.*
import org.junit.Assert.*
import org.junit.Test

class RestrictedMotionObservationRegressionTest {
    @Test fun aRealStepRemainsRecoveryEvidenceWithoutPublishingDisplacementWhileRestricted() {
        val f = PositioningSessionControllerTest.Fixture()
        f.start()
        val binding = requireNotNull(f.controller.binding)
        val owner = requireNotNull(binding.coordinator.currentUserMotionOwner())
        f.publishMotionPosition(1_000L)
        val trusted = requireNotNull(f.controller.trustedLocation)
        val before = binding.coordinator.evaluate(1_000L).filtered
        f.statusMessages.clear()
        f.navigationAllowed = false
        val event = step(2_000L)
        f.controller.handlePositioningStepEvent(event, binding, 2_100L, owner)
        val actual = binding.coordinator.peekUserMotion(2_100L)
        assertEquals(2_000L, actual.stepObservedAtElapsedRealtimeMs)
        assertSame(event, actual.evidenceRefs.single { it.stepEvidence != null }.stepEvidence?.originalStepEvent)
        val after = binding.coordinator.evaluate(2_100L)
        assertEquals(before?.coordinate, after.filtered?.coordinate)
        assertEquals(before?.localEnu, after.filtered?.localEnu)
        assertEquals(before?.velocityEastMps, after.filtered?.velocityEastMps)
        assertEquals(before?.velocityNorthMps, after.filtered?.velocityNorthMps)
        assertNull("Motion-only evaluation must not fabricate a new GNSS observation", after.raw)
        assertSame(trusted, f.controller.trustedLocation)
        assertEquals(1_000L, trusted.elapsedRealtimeMs)
        assertSame(binding, f.controller.binding)
        assertEquals(owner, binding.coordinator.currentUserMotionOwner())
        assertTrue(f.statusMessages.isEmpty())
    }

    @Test fun theCounterBoundaryIsDiscardedWhileRestrictedInsteadOfReplayedAsWalking() {
        val f = PositioningSessionControllerTest.Fixture(); f.start()
        val binding = requireNotNull(f.controller.binding)
        val owner = requireNotNull(binding.coordinator.currentUserMotionOwner())
        f.navigationAllowed = false
        f.controller.handlePositioningStepEvent(step(2_000L).copy(source = StepEventSource.STEP_COUNTER), binding, 2_100L, owner)
        assertTrue(binding.counterBoundaryEstablished)
        assertNull(binding.coordinator.peekUserMotion(2_100L).stepObservedAtElapsedRealtimeMs)
    }

    @Test fun disablingTheCollectionFeatureCannotAdmitLateStepsForTheRetainedOwner() {
        val f = PositioningSessionControllerTest.Fixture(); f.start()
        val binding = requireNotNull(f.controller.binding)
        val owner = requireNotNull(binding.coordinator.currentUserMotionOwner())
        f.navigationAllowed = false
        f.motionObservationAllowed = false
        f.controller.handlePositioningStepEvent(step(2_000L), binding, 2_100L, owner)
        assertNull(binding.coordinator.peekUserMotion(2_100L).stepObservedAtElapsedRealtimeMs)
        assertFalse(binding.counterBoundaryEstablished)
    }

    @Test fun retiredOwnerAndFutureOrStaleRawStepsCannotBecomeRecoveryEvidence() {
        val f = PositioningSessionControllerTest.Fixture(); f.start()
        val binding = requireNotNull(f.controller.binding)
        val owner = requireNotNull(binding.coordinator.currentUserMotionOwner())
        f.navigationAllowed = false
        f.controller.handlePositioningStepEvent(step(2_000L), binding, 1_999L, owner)
        f.controller.handlePositioningStepEvent(step(2_000L), binding, 4_001L, owner)
        assertNull(binding.coordinator.peekUserMotion(4_001L).stepObservedAtElapsedRealtimeMs)
        f.owner = f.owner.copy(actorId = "other", gatewayGeneration = 2L)
        f.controller.handlePositioningStepEvent(step(4_100L), binding, 4_200L, owner)
        assertNull(binding.coordinator.peekUserMotion(4_200L).stepObservedAtElapsedRealtimeMs)
    }

    private fun step(at: Long) = StepEvent(1, at, StepEventSource.STEP_DETECTOR, StepEventConfidence.HIGH,
        measurementElapsedRealtimeNanos = at * 1_000_000L,
        receivedAtElapsedRealtimeNanos = (at + 1L) * 1_000_000L,
        producerRegistrationGeneration = 1)
}

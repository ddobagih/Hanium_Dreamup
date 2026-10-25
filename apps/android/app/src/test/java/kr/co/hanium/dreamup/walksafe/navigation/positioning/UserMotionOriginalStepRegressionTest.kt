package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.StepEvent
import kr.co.hanium.dreamup.walksafe.navigation.StepEventConfidence
import kr.co.hanium.dreamup.walksafe.navigation.StepEventSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UserMotionOriginalStepRegressionTest {
    @Test
    fun originalDetectorAndCounterPacketsSurviveCoordinatorPublication() {
        for (source in listOf(StepEventSource.STEP_DETECTOR, StepEventSource.STEP_COUNTER)) {
            val count = if (source == StepEventSource.STEP_COUNTER) 3 else 1
            val confidence = if (count == 3) StepEventConfidence.MEDIUM else StepEventConfidence.HIGH
            val quality = if (count == 3) PdrStepQuality.MEDIUM else PdrStepQuality.HIGH
            val event = StepEvent(count, 1_000L, source, confidence, 1_000_123_456L, 1_100_987_654L)
            val result = observe(PositioningCoordinator(), event, quality)
            val ref = requireNotNull(result.userMotion).evidenceRefs.single()
            val evidence = requireNotNull(ref.stepEvidence)
            assertSame(event, evidence.originalStepEvent)
            assertEquals(1_000_123_456L, evidence.originalStepEvent?.measurementElapsedRealtimeNanos)
            assertEquals(1_100_987_654L, evidence.originalStepEvent?.receivedAtElapsedRealtimeNanos)
            assertEquals(count, evidence.stepCount)
            assertEquals(source, evidence.source)
            assertEquals(confidence, evidence.confidence)
            assertNull(ref.observationId)
            assertEquals(UserLocomotionState.UNKNOWN, result.userMotion?.locomotion)
        }
    }

    @Test
    fun invalidOriginalFactsCannotConsumeTheStepTimeBeforeValidRetry() {
        val valid = StepEvent(1, 1_000L, StepEventSource.STEP_DETECTOR, StepEventConfidence.HIGH,
            1_000_123_456L, 1_100_987_654L)
        for (bad in listOf(valid.copy(deltaSteps = 2), valid.copy(timestampMs = 999L),
            valid.copy(measurementElapsedRealtimeNanos = 1_001_123_456L),
            valid.copy(receivedAtElapsedRealtimeNanos = 1_000_123_455L),
            valid.copy(source = StepEventSource.STEP_COUNTER),
        )) {
            val coordinator = PositioningCoordinator()
            val rejected = observe(coordinator, bad, PdrStepQuality.HIGH)
            assertEquals(PedestrianMotionHardRejectReason.INVALID_STEP, rejected.motionUpdate?.hardRejectReason)
            assertTrue(requireNotNull(rejected.userMotion).evidenceRefs.isEmpty())
            val accepted = observe(coordinator, valid, PdrStepQuality.HIGH)
            assertSame(valid, requireNotNull(accepted.userMotion).evidenceRefs.single().stepEvidence?.originalStepEvent)
        }
    }

    @Test
    fun legacyStepInputDoesNotInventAnOriginalPacket() {
        val estimator = UserMotionStateEstimator()
        estimator.observeSteps(1, PdrStepQuality.HIGH, 1_000L, 1_100L)
        val ref = estimator.peek(1_100L).evidenceRefs.single()
        assertEquals(UserMotionEvidenceSource.UNKNOWN_STEP_SOURCE, ref.source)
        assertNull(ref.stepEvidence?.originalStepEvent)
    }

    private fun observe(coordinator: PositioningCoordinator, event: StepEvent, quality: PdrStepQuality) =
        coordinator.observeStep(1.takeIf { quality == PdrStepQuality.HIGH } ?: 3,
            PedestrianHeadingInput(1_000L, null, null, null, false), quality,
            receivedAtElapsedRealtimeMs = 1_100L,
            stepEventSource = if (quality == PdrStepQuality.HIGH) StepEventSource.STEP_DETECTOR else StepEventSource.STEP_COUNTER,
            stepEventConfidence = if (quality == PdrStepQuality.HIGH) StepEventConfidence.HIGH else StepEventConfidence.MEDIUM,
            originalStepEvent = event,
        )
}

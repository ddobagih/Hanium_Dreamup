package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.StepEvent
import kr.co.hanium.dreamup.walksafe.navigation.StepEventConfidence
import kr.co.hanium.dreamup.walksafe.navigation.StepEventSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UserMotionStepConfidenceAdmissionRegressionTest {
    @Test
    fun stateCannotUpgradeTheOriginalPacketOrConsumeItsTimeBeforeValidRetry() {
        for ((confidence, requested) in listOf(StepEventConfidence.LOW to PdrStepQuality.HIGH,
            StepEventConfidence.LOW to PdrStepQuality.MEDIUM, StepEventConfidence.MEDIUM to PdrStepQuality.HIGH)) {
            val source = if (confidence == StepEventConfidence.LOW) StepEventSource.ACCELEROMETER_FALLBACK
                else StepEventSource.STEP_COUNTER
            val packet = StepEvent(1, 1_000L, source, confidence, 1_000_123_456L, 1_100_987_654L)
            val estimator = UserMotionStateEstimator()
            estimator.observeSteps(1, requested, 1_000L, 1_100L, originalStepEvent = packet)
            val rejected = estimator.peek(1_100L)
            assertEquals(UserMovementState.UNKNOWN, rejected.movement)
            assertTrue(rejected.evidenceRefs.isEmpty())
            val valid = if (confidence == StepEventConfidence.LOW) packet.copy(
                source = StepEventSource.STEP_DETECTOR, confidence = StepEventConfidence.HIGH) else packet
            val allowed = if (confidence == StepEventConfidence.LOW) PdrStepQuality.HIGH else PdrStepQuality.MEDIUM
            estimator.observeSteps(1, allowed, 1_000L, 1_100L, originalStepEvent = valid)
            assertSame(valid, estimator.peek(1_100L).evidenceRefs.single().stepEvidence?.originalStepEvent)
        }
    }

    @Test
    fun coordinatorCannotUpgradeACounterPacketBeforeTheSameTimeMediumPdrRetry() {
        val coordinator = PositioningCoordinator()
        coordinator.observeGnss(GnssPositionObservation(latitude = 37.5, longitude = 127.0,
            horizontalAccuracyM = 1.5, elapsedRealtimeMs = 1_000L, receivedAtElapsedRealtimeMs = 1_000L))
        val packet = StepEvent(1, 2_000L, StepEventSource.STEP_COUNTER, StepEventConfidence.MEDIUM,
            2_000_123_456L, 2_100_987_654L)
        val input = PedestrianHeadingInput(2_000L, WalkingSpeedObservation(1.0, 0.1, 2_000L),
            HeadingObservation(90.0, 5.0, 2_000L), null, false)
        val rejected = coordinator.observeStep(1, input, PdrStepQuality.HIGH,
            receivedAtElapsedRealtimeMs = 2_100L, originalStepEvent = packet)
        assertEquals(PedestrianMotionHardRejectReason.INVALID_STEP, rejected.motionUpdate?.hardRejectReason)
        assertTrue(requireNotNull(rejected.userMotion).evidenceRefs.isEmpty())
        val accepted = coordinator.observeStep(1, input, PdrStepQuality.MEDIUM,
            receivedAtElapsedRealtimeMs = 2_100L, originalStepEvent = packet)
        assertEquals(PedestrianMotionUpdateDisposition.APPLIED, accepted.motionUpdate?.disposition)
        assertSame(packet, requireNotNull(accepted.userMotion).evidenceRefs.single().stepEvidence?.originalStepEvent)
    }

    @Test
    fun highConfidencePacketAllowsConservativeMediumQuality() {
        val packet = StepEvent(1, 1_000L, StepEventSource.STEP_DETECTOR, StepEventConfidence.HIGH,
            1_000_123_456L, 1_100_987_654L)
        val estimator = UserMotionStateEstimator()
        estimator.observeSteps(1, PdrStepQuality.MEDIUM, 1_000L, 1_100L, originalStepEvent = packet)
        val evidence = requireNotNull(estimator.peek(1_100L).evidenceRefs.single().stepEvidence)
        assertEquals(PdrStepQuality.MEDIUM, evidence.quality)
        assertEquals(StepEventConfidence.HIGH, evidence.confidence)
        assertSame(packet, evidence.originalStepEvent)
    }
}

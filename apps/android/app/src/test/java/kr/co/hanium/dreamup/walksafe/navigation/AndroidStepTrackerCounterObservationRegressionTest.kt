package kr.co.hanium.dreamup.walksafe.navigation

import android.hardware.Sensor
import android.hardware.SensorEventListener
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PdrStepQuality
import kr.co.hanium.dreamup.walksafe.navigation.positioning.UserMotionStateEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADD-MOTION-009: equal delta/time does not erase the producer's original Counter observations. */
class AndroidStepTrackerCounterObservationRegressionTest {
    @Test
    fun originalCounterBaselineValueSurvivesTheEqualDeltaAndDeliveryTime() {
        val first = counterPacket(100f, 0L)
        val second = counterPacket(999f, 0L)
        assertSameStepSummary(first, second)
        assertNotEquals("Different hardware baseline values must survive the Counter packet", first, second)
        assertEquals(StepCounterSample(100f, 0L, 600_987_654L), first.counterObservation?.baseline)
        assertEquals(StepCounterSample(999f, 0L, 600_987_654L), second.counterObservation?.baseline)
        assertEquals(StepCounterSample(103f, 1_000_987_654L, 1_100_987_654L), first.counterObservation?.current)
        assertEquals(StepCounterSample(1_002f, 1_000_987_654L, 1_100_987_654L), second.counterObservation?.current)
        assertEquals(first.counterObservation?.baseline, first.counterObservation?.previous)
        assertEquals(1, first.producerRegistrationGeneration)
        assertEquals(1, first.counterObservation?.producerRegistrationGeneration)
    }

    @Test
    fun originalCounterObservationStartSurvivesTheEqualDeltaAndDeliveryTime() {
        val first = counterPacket(100f, 0L)
        val second = counterPacket(100f, 500_123_456L)
        assertSameStepSummary(first, second)
        assertNotEquals("Different original observation windows must survive the Counter packet", first, second)
        assertEquals(StepCounterSample(100f, 0L, 600_987_654L), first.counterObservation?.baseline)
        assertEquals(StepCounterSample(100f, 500_123_456L, 600_987_654L), second.counterObservation?.baseline)
        assertEquals(first.counterObservation?.current, second.counterObservation?.current)
    }

    @Test
    fun motionEvidenceRetainsTheOriginalCounterHistoryThroughItsExistingPacketContract() {
        fun evidence(packet: StepEvent) = UserMotionStateEstimator().also {
            it.observeSteps(3, PdrStepQuality.MEDIUM, 1_000L, 1_100L,
                originalStepEvent = packet)
        }.peek(1_100L).evidenceRefs.single().stepEvidence
        val first = counterPacket(100f, 0L)
        val second = counterPacket(999f, 0L)
        val firstEvidence = evidence(first)
        val secondEvidence = evidence(second)
        assertSame(first, firstEvidence?.originalStepEvent)
        assertSame(second, secondEvidence?.originalStepEvent)
        assertNotEquals("Motion publication must retain the producer's distinct raw Counter basis",
            firstEvidence, secondEvidence)
    }

    @Test
    fun latestAcceptedCounterSamplesRemainAvailableWithoutStepEmission() {
        val events = mutableListOf<StepEvent>()
        val tracker = AndroidStepTracker(registration = CounterRegistration,
            onStepCountChanged = {}, onStepEvent = { events += it }).also { assertTrue(it.start()) }
        assertNull(tracker.latestCounterObservation)
        assertTrue(tracker.onStepCounterTotal(100f, 0L))
        val baseline = requireNotNull(tracker.latestCounterObservation)
        assertEquals(StepCounterSample(100f, 0L), baseline.baseline)
        assertNull(baseline.previous)
        assertEquals(baseline.baseline, baseline.current)
        assertTrue(tracker.onStepCounterTotal(100f, 1_000_123_456L, 1_100_987_654L))
        val unchanged = requireNotNull(tracker.latestCounterObservation)
        assertEquals(baseline.baseline, unchanged.baseline)
        assertEquals(baseline.current, unchanged.previous)
        assertEquals(StepCounterSample(100f, 1_000_123_456L, 1_100_987_654L), unchanged.current)
        assertTrue(events.isEmpty())
        assertFalse(tracker.onStepCounterTotal(Float.NaN, 2_000_000_000L))
        assertFalse(tracker.onStepCounterTotal(103f, 2_000_000_000L, 1_999_999_999L))
        assertFalse(tracker.onStepCounterTotal(103f, 1_000_123_456L))
        assertSame(unchanged, tracker.latestCounterObservation)
        assertTrue(tracker.onStepCounterTotal(98f, 2_000_000_000L))
        assertTrue(events.isEmpty())
        assertTrue(tracker.onStepCounterTotal(103f, 3_000_000_000L))
        val event = events.single()
        assertEquals(3, event.deltaSteps)
        assertEquals(StepCounterSample(98f, 2_000_000_000L), event.counterObservation?.previous)
        assertEquals(StepCounterSample(103f, 3_000_000_000L), event.counterObservation?.current)
        assertNull(event.receivedAtElapsedRealtimeNanos)
    }

    @Test
    fun healthyDetectorAbsorptionStillRetainsTheActualCounterSample() {
        val events = mutableListOf<StepEvent>()
        val registration = object : StepSensorRegistration {
            override fun hasSensor(type: Int) = type in setOf(Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR)
            override fun register(listener: SensorEventListener, type: Int) = hasSensor(type)
            override fun unregister(listener: SensorEventListener?) = Unit
        }
        val tracker = AndroidStepTracker(registration = registration,
            onStepCountChanged = {}, onStepEvent = { events += it }).also { assertTrue(it.start()) }
        assertTrue(tracker.onStepCounterTotal(100f, 1_000_000_000L))
        assertTrue(tracker.onStepDetected(1_500_000_000L))
        assertTrue(tracker.onStepCounterTotal(101f, 2_000_000_000L, 2_100_000_000L))
        assertEquals(StepCounterSample(101f, 2_000_000_000L, 2_100_000_000L), tracker.latestCounterObservation?.current)
        assertEquals(1, tracker.latestCounterObservation?.producerRegistrationGeneration)
        assertEquals(1, events.single().producerRegistrationGeneration)
        assertNull(events.single().counterObservation)
        assertEquals(StepEventSource.STEP_DETECTOR, events.single().source)
    }

    @Test
    fun restartingClearsLatestCounterHistoryAndKeepsTheDeliveredPacketImmutable() {
        val events = mutableListOf<StepEvent>()
        val tracker = AndroidStepTracker(registration = CounterRegistration,
            onStepCountChanged = {}, onStepEvent = { events += it }).also { assertTrue(it.start()) }
        assertTrue(tracker.onStepCounterTotal(100f, 0L))
        assertTrue(tracker.onStepCounterTotal(103f, 1_000_000_000L))
        val first = events.single()
        tracker.stop()
        assertNull(tracker.latestCounterObservation)
        assertTrue(tracker.start())
        assertNull(tracker.latestCounterObservation)
        assertTrue(tracker.onStepCounterTotal(500f, 2_000_000_000L))
        assertTrue(tracker.onStepCounterTotal(502f, 3_000_000_000L))
        assertEquals(1, first.producerRegistrationGeneration)
        assertEquals(StepCounterSample(100f, 0L), first.counterObservation?.baseline)
        assertEquals(3, events.last().producerRegistrationGeneration)
        assertEquals(StepCounterSample(500f, 2_000_000_000L), events.last().counterObservation?.baseline)
        assertEquals(2, events.last().deltaSteps)
    }

    @Test
    fun inconsistentCounterPayloadCannotConsumeMotionTimeBeforeTheValidPacketRetry() {
        val packet = counterPacket(100f, 0L)
        val observation = requireNotNull(packet.counterObservation)
        for (invalid in listOf(packet.copy(producerRegistrationGeneration = -1),
            packet.copy(counterObservation = observation.copy(producerRegistrationGeneration = 2)),
            packet.copy(counterObservation = observation.copy(current = observation.current.copy(cumulativeSteps = Float.NaN))),
            packet.copy(counterObservation = observation.copy(current = observation.current.copy(measurementElapsedRealtimeNanos = 1_000_123_456L))),
            packet.copy(counterObservation = observation.copy(current = observation.current.copy(receivedAtElapsedRealtimeNanos = null))),
            packet.copy(counterObservation = observation.copy(baseline = observation.baseline.copy(measurementElapsedRealtimeNanos = 2_000_000_000L))))) {
            val estimator = UserMotionStateEstimator()
            estimator.observeSteps(3, PdrStepQuality.MEDIUM, 1_000L, 1_100L, originalStepEvent = invalid)
            assertTrue(estimator.peek(1_100L).evidenceRefs.isEmpty())
            estimator.observeSteps(3, PdrStepQuality.MEDIUM, 1_000L, 1_100L, originalStepEvent = packet)
            assertSame(packet, estimator.peek(1_100L).evidenceRefs.single().stepEvidence?.originalStepEvent)
        }
    }

    @Test
    fun historicalReceiptsCannotFollowTheCurrentReceiptWithinTheSameMillisecond() {
        val valid = counterPacket(100f, 0L)
        val observation = requireNotNull(valid.counterObservation)
        val futureReceipt = 1_100_999_999L
        val invalid = valid.copy(counterObservation = observation.copy(
            baseline = observation.baseline.copy(receivedAtElapsedRealtimeNanos = futureReceipt),
            previous = requireNotNull(observation.previous).copy(receivedAtElapsedRealtimeNanos = futureReceipt)))
        assertHistoricalPacketRejectedBeforeSameTimeRetry(invalid, valid)
    }

    @Test
    fun historicalReceiptsCannotExceedEvaluationWhenTheCurrentReceiptIsUnknown() {
        val valid = counterPacket(100f, 0L)
        val observation = requireNotNull(valid.counterObservation)
        val invalid = valid.copy(receivedAtElapsedRealtimeNanos = null,
            counterObservation = observation.copy(
                baseline = observation.baseline.copy(receivedAtElapsedRealtimeNanos = 2_000_000_000L),
                previous = requireNotNull(observation.previous).copy(receivedAtElapsedRealtimeNanos = 2_000_000_000L),
                current = observation.current.copy(receivedAtElapsedRealtimeNanos = null)))
        assertHistoricalPacketRejectedBeforeSameTimeRetry(invalid, valid)
    }

    @Test
    fun sameCounterMeasurementCannotCarryTwoRawValues() {
        val valid = counterPacket(100f, 0L)
        val observation = requireNotNull(valid.counterObservation)
        val invalid = valid.copy(counterObservation = observation.copy(
            previous = requireNotNull(observation.previous).copy(cumulativeSteps = 101f)))
        assertHistoricalPacketRejectedBeforeSameTimeRetry(invalid, valid)
    }

    @Test
    fun sameCounterMeasurementCannotCarryTwoKnownReceiptTimes() {
        val valid = counterPacket(100f, 0L)
        val observation = requireNotNull(valid.counterObservation)
        val invalid = valid.copy(counterObservation = observation.copy(
            previous = requireNotNull(observation.previous).copy(receivedAtElapsedRealtimeNanos = 600_987_655L)))
        assertHistoricalPacketRejectedBeforeSameTimeRetry(invalid, valid)
    }

    @Test
    fun sameCounterMeasurementAcceptsUnknownReceiptWithoutErasingKnownFacts() {
        val valid = counterPacket(100f, 0L)
        val observation = requireNotNull(valid.counterObservation)
        for (partial in listOf(
            observation.copy(baseline = observation.baseline.copy(receivedAtElapsedRealtimeNanos = null)),
            observation.copy(previous = requireNotNull(observation.previous).copy(receivedAtElapsedRealtimeNanos = null)),
        )) {
            val packet = valid.copy(counterObservation = partial)
            val estimator = UserMotionStateEstimator()
            estimator.observeSteps(3, PdrStepQuality.MEDIUM, 1_000L, 1_100L, originalStepEvent = packet)
            assertSame(packet, estimator.peek(1_100L).evidenceRefs.single().stepEvidence?.originalStepEvent)
        }
    }

    @Test
    fun fractionalCounterSamplesKeepTheirOriginalValuesAndExistingIntegerDelta() {
        val events = mutableListOf<StepEvent>()
        val tracker = AndroidStepTracker(registration = CounterRegistration,
            onStepCountChanged = {}, onStepEvent = { events += it })
        assertTrue(tracker.start())
        assertTrue(tracker.onStepCounterTotal(100.25f, 0L, 600_987_654L))
        assertTrue(tracker.onStepCounterTotal(103.75f, 1_000_987_654L, 1_100_987_654L))
        val packet = events.single()
        tracker.stop()
        assertEquals(3, packet.deltaSteps)
        assertEquals(100.25f, requireNotNull(packet.counterObservation).baseline.cumulativeSteps, 0f)
        assertEquals(103.75f, requireNotNull(packet.counterObservation).current.cumulativeSteps, 0f)
        val estimator = UserMotionStateEstimator()
        estimator.observeSteps(3, PdrStepQuality.MEDIUM, 1_000L, 1_100L, originalStepEvent = packet)
        assertSame(packet, estimator.peek(1_100L).evidenceRefs.single().stepEvidence?.originalStepEvent)
    }

    private fun assertHistoricalPacketRejectedBeforeSameTimeRetry(invalid: StepEvent, valid: StepEvent) {
        val estimator = UserMotionStateEstimator()
        estimator.observeSteps(3, PdrStepQuality.MEDIUM, 1_000L, 1_100L, originalStepEvent = invalid)
        assertTrue("Inconsistent historical Counter samples must not become movement evidence",
            estimator.peek(1_100L).evidenceRefs.isEmpty())
        estimator.observeSteps(3, PdrStepQuality.MEDIUM, 1_000L, 1_100L, originalStepEvent = valid)
        assertSame(valid, estimator.peek(1_100L).evidenceRefs.single().stepEvidence?.originalStepEvent)
    }

    private fun assertSameStepSummary(first: StepEvent, second: StepEvent) {
        assertEquals(3, first.deltaSteps)
        assertEquals(first.deltaSteps, second.deltaSteps)
        assertEquals(1_000L, first.timestampMs)
        assertEquals(first.timestampMs, second.timestampMs)
        assertEquals(StepEventSource.STEP_COUNTER, first.source)
        assertEquals(first.source, second.source)
        assertEquals(StepEventConfidence.MEDIUM, first.confidence)
        assertEquals(first.confidence, second.confidence)
        assertEquals(1_000_987_654L, first.measurementElapsedRealtimeNanos)
        assertEquals(first.measurementElapsedRealtimeNanos, second.measurementElapsedRealtimeNanos)
        assertEquals(1_100_987_654L, first.receivedAtElapsedRealtimeNanos)
        assertEquals(first.receivedAtElapsedRealtimeNanos, second.receivedAtElapsedRealtimeNanos)
    }

    private fun counterPacket(baseline: Float, baselineAtNs: Long): StepEvent {
        val events = mutableListOf<StepEvent>()
        val tracker = AndroidStepTracker(registration = CounterRegistration,
            onStepCountChanged = {}, onStepEvent = { events += it })
        assertTrue(tracker.start())
        assertTrue(tracker.onStepCounterTotal(baseline, baselineAtNs, 600_987_654L))
        assertTrue(tracker.onStepCounterTotal(baseline + 3f, 1_000_987_654L, 1_100_987_654L))
        tracker.stop()
        return events.single()
    }

    private object CounterRegistration : StepSensorRegistration {
        override fun hasSensor(type: Int) = type == Sensor.TYPE_STEP_COUNTER
        override fun register(listener: SensorEventListener, type: Int) = hasSensor(type)
        override fun unregister(listener: SensorEventListener?) = Unit
    }
}

/** Existing count/source/timing assertions keep all six old fields; new raw facts have dedicated tests. */
internal fun StepEvent.legacyCountAndTiming(): StepEvent =
    copy(producerRegistrationGeneration = null, counterObservation = null)

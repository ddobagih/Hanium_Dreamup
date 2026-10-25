package kr.co.hanium.dreamup.walksafe.navigation

import android.hardware.Sensor
import android.hardware.SensorEventListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADD-MOTION-032: rejected Counter receipt times must not consume accepted sensor state. */
class AndroidStepTrackerCounterReceiptRegressionTest {
    @Test
    fun backwardsBaselineReceiptCannotMutateStateOrConsumeTheValidSameMeasurementRetry() {
        val fixture = Fixture()
        assertTrue(fixture.tracker.onStepCounterTotal(100f, 1_000_000_000L, 2_000_000_000L))
        fixture.assertRejectedWithoutMutation(103f, 1_500_000_000L, 1_900_000_000L)
        assertTrue(fixture.tracker.onStepCounterTotal(103f, 1_500_000_000L, 2_100_000_000L))
        val event = fixture.events.single()
        assertEquals(listOf(0, 3), fixture.counts)
        assertEquals(3, event.deltaSteps)
        assertEquals(StepCounterSample(100f, 1_000_000_000L, 2_000_000_000L), event.counterObservation?.baseline)
        assertEquals(event.counterObservation?.baseline, event.counterObservation?.previous)
        assertEquals(StepCounterSample(103f, 1_500_000_000L, 2_100_000_000L), event.counterObservation?.current)
        assertConsistent(event, 2_100L)
    }

    @Test
    fun receiptAfterBaselineButBeforePreviousAcceptedReceiptIsRejected() {
        val fixture = Fixture()
        assertTrue(fixture.tracker.onStepCounterTotal(100f, 500_000_000L, 600_000_000L))
        assertTrue(fixture.tracker.onStepCounterTotal(100f, 1_000_000_000L, 2_000_000_000L))
        fixture.assertRejectedWithoutMutation(103f, 1_500_000_000L, 1_900_000_000L)
        assertTrue(fixture.tracker.onStepCounterTotal(103f, 1_500_000_000L, 2_100_000_000L))
        assertEquals(3, fixture.events.single().deltaSteps)
        assertEquals(StepCounterSample(100f, 1_000_000_000L, 2_000_000_000L),
            fixture.events.single().counterObservation?.previous)
        assertConsistent(fixture.events.single(), 2_100L)
    }

    @Test
    fun unknownReceiptsDoNotEraseTheLastKnownReceiptWhenBaselineIsAlsoUnknown() {
        val fixture = Fixture()
        assertTrue(fixture.tracker.onStepCounterTotal(100f, 500_000_000L))
        assertTrue(fixture.tracker.onStepCounterTotal(100f, 1_000_000_000L, 2_000_000_000L))
        assertTrue(fixture.tracker.onStepCounterTotal(100f, 1_200_000_000L))
        assertTrue(fixture.tracker.onStepCounterTotal(100f, 1_300_000_000L))
        fixture.assertRejectedWithoutMutation(103f, 1_500_000_000L, 1_900_000_000L)
        assertTrue(fixture.tracker.onStepCounterTotal(103f, 1_500_000_000L, 2_100_000_000L))
        val event = fixture.events.single()
        assertNull(event.counterObservation?.baseline?.receivedAtElapsedRealtimeNanos)
        assertNull(event.counterObservation?.previous?.receivedAtElapsedRealtimeNanos)
        assertEquals(2_100_000_000L, event.receivedAtElapsedRealtimeNanos)
        assertConsistent(event, 2_100L)
    }

    @Test
    fun receiptOrderingUsesOriginalNanosecondsWithinTheSameMillisecond() {
        val fixture = Fixture()
        val measurement = 1_500_123_456L
        val receipt = 2_000_987_654L
        assertTrue(fixture.tracker.onStepCounterTotal(100f, 1_000_123_456L, receipt))
        fixture.assertRejectedWithoutMutation(103f, measurement, receipt - 1L)
        assertTrue(fixture.tracker.onStepCounterTotal(103f, measurement, receipt))
        val event = fixture.events.single()
        assertEquals(measurement, event.measurementElapsedRealtimeNanos)
        assertEquals(receipt, event.receivedAtElapsedRealtimeNanos)
        assertEquals(StepCounterSample(103f, measurement, receipt), event.counterObservation?.current)
        assertConsistent(event, receipt / MS)
    }

    @Test
    fun increasingAndEqualKnownReceiptsKeepTheirRawHistoryAndCounts() {
        val fixture = Fixture()
        assertTrue(fixture.tracker.onStepCounterTotal(100.25f, 1_000_123_456L, 2_000_987_654L))
        assertTrue(fixture.tracker.onStepCounterTotal(103.75f, 1_500_123_456L, 2_000_987_654L))
        assertTrue(fixture.tracker.onStepCounterTotal(105.5f, 2_500_123_456L, 3_000_987_654L))
        assertEquals(listOf(0, 3, 5), fixture.counts)
        assertEquals(listOf(3, 2), fixture.events.map { it.deltaSteps })
        assertEquals(StepCounterSample(100.25f, 1_000_123_456L, 2_000_987_654L),
            fixture.events.last().counterObservation?.baseline)
        assertEquals(StepCounterSample(103.75f, 1_500_123_456L, 2_000_987_654L),
            fixture.events.last().counterObservation?.previous)
        assertEquals(StepCounterSample(105.5f, 2_500_123_456L, 3_000_987_654L),
            fixture.events.last().counterObservation?.current)
        fixture.events.forEach { assertConsistent(it, 3_000L) }
    }

    @Test
    fun legacyUnknownAndMixedReceiptSamplesRemainAcceptedWithoutInventedTimes() {
        for (baselineReceipt in listOf(null, 1_100_000_000L)) {
            val fixture = Fixture()
            assertTrue(fixture.tracker.onStepCounterTotal(100f, 1_000_000_000L, baselineReceipt))
            assertTrue(fixture.tracker.onStepCounterTotal(103f, 1_500_000_000L))
            assertTrue(fixture.tracker.onStepCounterTotal(105f, 2_000_000_000L, 2_100_000_000L))
            assertTrue(fixture.tracker.onStepCounterTotal(106f, 2_500_000_000L))
            assertEquals(listOf(0, 3, 5, 6), fixture.counts)
            assertNull(fixture.events.first().receivedAtElapsedRealtimeNanos)
            assertEquals(baselineReceipt, fixture.events.first().counterObservation?.baseline?.receivedAtElapsedRealtimeNanos)
            assertNull(fixture.events[1].counterObservation?.previous?.receivedAtElapsedRealtimeNanos)
            assertNull(fixture.events.last().receivedAtElapsedRealtimeNanos)
            fixture.events.forEach { assertConsistent(it, 2_500L) }
        }
    }

    @Test
    fun invalidFirstCounterTimeDoesNotEstablishBaselineOrConsumeItsRetry() {
        val fixture = Fixture()
        fixture.assertRejectedWithoutMutation(100f, -1L, 1_000L)
        fixture.assertRejectedWithoutMutation(100f, 1_000L, 999L)
        assertEquals(StepCounterBaselineState.NOT_ESTABLISHED, fixture.tracker.counterBaselineState)
        assertTrue(fixture.tracker.onStepCounterTotal(100f, 1_000L, 1_001L))
        assertEquals(StepCounterBaselineState.ESTABLISHED, fixture.tracker.counterBaselineState)
        assertEquals(listOf(0), fixture.counts)
        assertTrue(fixture.events.isEmpty())
    }

    @Test
    fun stopStartClearsTheReceiptGuardAlongWithTheRegistrationBaseline() {
        val fixture = Fixture()
        assertTrue(fixture.tracker.onStepCounterTotal(100f, 1_000_000_000L, 20_000_000_000L))
        assertTrue(fixture.tracker.onStepCounterTotal(102f, 2_000_000_000L, 21_000_000_000L))
        val oldEvent = fixture.events.single()
        fixture.tracker.stop()
        assertNull(fixture.tracker.latestCounterObservation)
        assertTrue(fixture.tracker.start())
        assertTrue(fixture.tracker.onStepCounterTotal(500f, 3_000_000_000L, 3_100_000_000L))
        assertTrue(fixture.tracker.onStepCounterTotal(501f, 4_000_000_000L, 4_100_000_000L))
        assertEquals(listOf(0, 2, 2, 3), fixture.counts)
        assertEquals(1, oldEvent.producerRegistrationGeneration)
        assertEquals(3, fixture.events.last().producerRegistrationGeneration)
        assertEquals(StepCounterSample(500f, 3_000_000_000L, 3_100_000_000L),
            fixture.events.last().counterObservation?.baseline)
        assertEquals(21_000_000_000L, oldEvent.receivedAtElapsedRealtimeNanos)
        assertConsistent(fixture.events.last(), 4_100L)
    }

    @Test
    fun rejectedCounterCannotCoverTheDetectorOrChangeTheStallHandover() {
        val fixture = Fixture(withDetector = true)
        assertTrue(fixture.tracker.onStepCounterTotal(100f, 1_000_000_000L, 20_000_000_000L))
        fixture.assertRejectedWithoutMutation(103f, 12_000_000_000L, 19_000_000_000L)
        assertTrue(fixture.tracker.onStepDetected(12_000_000_000L, 20_000_000_000L))
        assertEquals(StepEventSource.STEP_DETECTOR, fixture.events.single().source)
        assertTrue(fixture.tracker.onStepCounterTotal(103f, 12_000_000_000L, 20_100_000_000L))
        assertEquals(1, fixture.events.size)
        assertTrue(fixture.tracker.onStepCounterTotal(105f, 23_000_000_000L, 23_100_000_000L))
        assertEquals(listOf(1, 2), fixture.events.map { it.deltaSteps })
        assertEquals(StepEventSource.STEP_COUNTER, fixture.events.last().source)
        assertEquals(5, fixture.counts.last())
        assertConsistent(fixture.events.last(), 23_100L)
    }

    @Test
    fun rejectedCounterKeepsThePreBaselineDetectorRecountOffset() {
        val fixture = Fixture(withDetector = true)
        listOf(1_000L, 1_500L, 2_000L).forEach { assertTrue(fixture.tracker.onStepDetected(it * MS)) }
        assertTrue(fixture.tracker.onStepCounterTotal(801f, 2_500L * MS, 20_000L * MS))
        fixture.assertRejectedWithoutMutation(803f, 12_100L * MS, 19_000L * MS)
        assertTrue(fixture.tracker.onStepCounterTotal(803f, 12_100L * MS, 20_100L * MS))
        assertEquals(3, fixture.counts.last())
        assertEquals(3, fixture.events.sumOf { it.deltaSteps })
        assertTrue(fixture.tracker.onStepCounterTotal(804f, 22_200L * MS, 22_300L * MS))
        assertEquals(4, fixture.counts.last())
        assertEquals(4, fixture.events.sumOf { it.deltaSteps })
        assertEquals(1, fixture.events.last().deltaSteps)
        assertConsistent(fixture.events.last(), 22_300L)
    }

    private fun assertConsistent(event: StepEvent, receivedAtMs: Long) {
        assertTrue(requireNotNull(event.counterObservation).isConsistentWith(event, receivedAtMs))
        assertTrue(event.isConsistentWith(event.deltaSteps, event.timestampMs, receivedAtMs,
            StepEventSource.STEP_COUNTER, StepEventConfidence.MEDIUM))
    }

    private class Fixture(withDetector: Boolean = false) {
        val counts = mutableListOf<Int>()
        val events = mutableListOf<StepEvent>()
        private val registration = object : StepSensorRegistration {
            override fun hasSensor(type: Int) = type == Sensor.TYPE_STEP_COUNTER ||
                (withDetector && type == Sensor.TYPE_STEP_DETECTOR)
            override fun register(listener: SensorEventListener, type: Int) = hasSensor(type)
            override fun unregister(listener: SensorEventListener?) = Unit
        }
        val tracker = AndroidStepTracker(registration, counts::add, onStepEvent = events::add)
            .also { assertTrue(it.start()) }

        fun assertRejectedWithoutMutation(total: Float, measurementNs: Long, receiptNs: Long) {
            val oldCounts = counts.toList()
            val oldEvents = events.toList()
            val oldHistory = tracker.latestCounterObservation
            val oldHealth = tracker.operationalHealth
            val oldBaseline = tracker.counterBaselineState
            assertFalse("Backwards Counter time must be rejected before any producer state changes",
                tracker.onStepCounterTotal(total, measurementNs, receiptNs))
            assertEquals(oldCounts, counts)
            assertEquals(oldEvents, events)
            assertSame(oldHistory, tracker.latestCounterObservation)
            assertSame(oldHealth, tracker.operationalHealth)
            assertEquals(oldBaseline, tracker.counterBaselineState)
        }
    }

    private companion object { const val MS = 1_000_000L }
}

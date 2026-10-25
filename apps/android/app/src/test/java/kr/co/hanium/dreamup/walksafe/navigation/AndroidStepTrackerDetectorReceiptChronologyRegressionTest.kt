package kr.co.hanium.dreamup.walksafe.navigation

import android.hardware.Sensor
import android.hardware.SensorEventListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADD-MOTION-032/048: Counter and Detector callbacks share actual receipt chronology. */
class AndroidStepTrackerDetectorReceiptChronologyRegressionTest {
    @Test
    fun reversedDetectorReceiptCannotChangeStateOrConsumeTheValidSameMeasurementRetry() {
        val fixture = Fixture(withCounter = false)
        assertTrue(fixture.tracker.onStepDetected(SECOND, 2 * SECOND))
        fixture.assertRejectedWithoutMutation { fixture.tracker.onStepDetected(1_500 * MS, 1_900 * MS) }
        assertTrue(fixture.tracker.onStepDetected(1_500 * MS, 2_100 * MS))
        assertEquals(listOf(1, 2), fixture.counts)
        assertEquals(listOf(SECOND, 1_500 * MS), fixture.events.map { it.measurementElapsedRealtimeNanos })
        assertEquals(2_100 * MS, fixture.events.last().receivedAtElapsedRealtimeNanos)
        assertEquals(1, fixture.events.last().producerRegistrationGeneration)
        assertTrue(fixture.events.last().isConsistentWith(1, 1_500L, 2_100L,
            StepEventSource.STEP_DETECTOR, StepEventConfidence.HIGH))
    }

    @Test
    fun counterReceiptProtectsTheFollowingDetectorBeforeAnyHandoverMutation() {
        val fixture = Fixture()
        assertTrue(fixture.tracker.onStepCounterTotal(100f, SECOND, 2 * SECOND))
        fixture.assertRejectedWithoutMutation { fixture.tracker.onStepDetected(1_500 * MS, 1_900 * MS) }
        assertTrue(fixture.tracker.onStepDetected(1_500 * MS, 2_100 * MS))
        assertEquals(StepEventSource.STEP_DETECTOR, fixture.events.single().source)
        assertTrue(fixture.tracker.onStepCounterTotal(103f, 2 * SECOND, 2_200 * MS))
        assertEquals(listOf(0, 3), fixture.counts)
        assertEquals(1, fixture.events.size)
        assertEquals(StepCounterSample(103f, 2 * SECOND, 2_200 * MS), fixture.tracker.latestCounterObservation?.current)
    }

    @Test
    fun detectorReceiptProtectsTheFollowingCounterBaselineAndItsSameMeasurementRetry() {
        val fixture = Fixture()
        assertTrue(fixture.tracker.onStepDetected(SECOND, 2 * SECOND))
        fixture.assertRejectedWithoutMutation { fixture.tracker.onStepCounterTotal(100f, 1_500 * MS, 1_900 * MS) }
        assertEquals(StepCounterBaselineState.NOT_ESTABLISHED, fixture.tracker.counterBaselineState)
        assertTrue(fixture.tracker.onStepCounterTotal(100f, 1_500 * MS, 2_100 * MS))
        assertTrue(fixture.tracker.onStepCounterTotal(103f, 2 * SECOND, 2_200 * MS))
        assertEquals(listOf(1, 1, 3), fixture.counts)
        assertEquals(1, fixture.events.size)
        assertEquals(StepCounterSample(100f, 1_500 * MS, 2_100 * MS), fixture.tracker.latestCounterObservation?.baseline)
        assertEquals(2_200 * MS, fixture.tracker.latestCounterObservation?.current?.receivedAtElapsedRealtimeNanos)
    }

    @Test
    fun unknownReceiptsInBothStepSourcesKeepTheEarlierKnownDetectorReceipt() {
        val fixture = Fixture()
        assertTrue(fixture.tracker.onStepCounterTotal(100f, 500 * MS))
        assertTrue(fixture.tracker.onStepDetected(SECOND, 2 * SECOND))
        assertTrue(fixture.tracker.onStepCounterTotal(100f, 1_200 * MS))
        assertTrue(fixture.tracker.onStepDetected(1_300 * MS))
        fixture.assertRejectedWithoutMutation { fixture.tracker.onStepCounterTotal(103f, 1_500 * MS, 1_900 * MS) }
        assertTrue(fixture.tracker.onStepCounterTotal(103f, 1_500 * MS, 2_100 * MS))
        val history = requireNotNull(fixture.tracker.latestCounterObservation)
        assertNull(history.baseline.receivedAtElapsedRealtimeNanos)
        assertNull(history.previous?.receivedAtElapsedRealtimeNanos)
        assertEquals(StepCounterSample(103f, 1_500 * MS, 2_100 * MS), history.current)
        assertNull(fixture.events.last().receivedAtElapsedRealtimeNanos)
        assertEquals(3, fixture.counts.last())
    }

    @Test
    fun unknownDetectorReceiptsKeepTheEarlierKnownCounterReceipt() {
        val fixture = Fixture()
        assertTrue(fixture.tracker.onStepCounterTotal(100f, 500 * MS, 2 * SECOND))
        assertTrue(fixture.tracker.onStepDetected(SECOND))
        assertTrue(fixture.tracker.onStepCounterTotal(100f, 1_200 * MS))
        assertTrue(fixture.tracker.onStepDetected(1_300 * MS))
        fixture.assertRejectedWithoutMutation { fixture.tracker.onStepDetected(1_500 * MS, 1_900 * MS) }
        assertTrue(fixture.tracker.onStepDetected(1_500 * MS, 2_100 * MS))
        assertEquals(3, fixture.events.size)
        assertEquals(2_100 * MS, fixture.events.last().receivedAtElapsedRealtimeNanos)
    }

    @Test
    fun receiptNanosecondsRemainOrderedWhileCrossSourceMeasurementsCanArriveLate() {
        val fixture = Fixture()
        val receipt = 2_000_987_654L
        assertTrue(fixture.tracker.onStepDetected(1_000_123_456L, receipt))
        assertTrue(fixture.tracker.onStepCounterTotal(100f, 900_123_456L, receipt))
        assertEquals(StepCounterSample(100f, 900_123_456L, receipt), fixture.tracker.latestCounterObservation?.baseline)
        fixture.assertRejectedWithoutMutation { fixture.tracker.onStepDetected(1_500_123_456L, receipt - 1L) }
        assertTrue(fixture.tracker.onStepDetected(1_500_123_456L, receipt))
        assertEquals(1_500_123_456L, fixture.events.last().measurementElapsedRealtimeNanos)
        assertEquals(receipt, fixture.events.last().receivedAtElapsedRealtimeNanos)
        assertTrue(fixture.tracker.onStepCounterTotal(103f, 1_400_123_456L, receipt))
        assertEquals(StepCounterSample(103f, 1_400_123_456L, receipt), fixture.tracker.latestCounterObservation?.current)
    }

    @Test
    fun rejectedDetectorTimesCannotAdvanceTheKnownReceipt() {
        for (invalidMeasurement in listOf(-1L, SECOND, 900 * MS, 11 * SECOND)) {
            val fixture = Fixture()
            assertTrue(fixture.tracker.onStepDetected(SECOND, 2 * SECOND))
            fixture.assertRejectedWithoutMutation { fixture.tracker.onStepDetected(invalidMeasurement, 10 * SECOND) }
            assertTrue(fixture.tracker.onStepCounterTotal(100f, 1_500 * MS, 2_100 * MS))
            assertEquals(StepCounterSample(100f, 1_500 * MS, 2_100 * MS), fixture.tracker.latestCounterObservation?.current)
        }
    }

    @Test
    fun rejectedCounterValuesOrTimesCannotAdvanceTheReceiptBeforeTheDetectorRetry() {
        val invalidInputs = listOf(
            Float.NaN to 1_500 * MS,
            Float.POSITIVE_INFINITY to 1_500 * MS,
            -1f to 1_500 * MS,
            Float.MAX_VALUE to 1_500 * MS,
            103f to 500 * MS,
            103f to -1L,
            103f to 11 * SECOND,
        )
        for ((total, measuredAt) in invalidInputs) {
            val fixture = Fixture()
            assertTrue(fixture.tracker.onStepCounterTotal(100f, 500 * MS, SECOND))
            assertTrue(fixture.tracker.onStepDetected(SECOND, 2 * SECOND))
            fixture.assertRejectedWithoutMutation { fixture.tracker.onStepCounterTotal(total, measuredAt, 10 * SECOND) }
            assertTrue(fixture.tracker.onStepDetected(1_500 * MS, 2_100 * MS))
            assertEquals(2_100 * MS, fixture.events.last().receivedAtElapsedRealtimeNanos)
        }
    }

    @Test
    fun stopStartClearsBothReceiptAndMeasurementGuardsWithoutChangingOldRawHistory() {
        val fixture = Fixture()
        assertTrue(fixture.tracker.onStepDetected(SECOND, 20 * SECOND))
        assertTrue(fixture.tracker.onStepCounterTotal(100f, 2 * SECOND, 21 * SECOND))
        val oldHistory = requireNotNull(fixture.tracker.latestCounterObservation)
        fixture.tracker.stop()
        assertTrue(fixture.tracker.start())
        assertTrue(fixture.tracker.onStepCounterTotal(500f, SECOND, 1_100 * MS))
        assertTrue(fixture.tracker.onStepDetected(900 * MS, 1_200 * MS))
        assertEquals(3, fixture.events.last().producerRegistrationGeneration)
        assertEquals(StepCounterSample(500f, SECOND, 1_100 * MS), fixture.tracker.latestCounterObservation?.baseline)
        assertEquals(StepCounterSample(100f, 2 * SECOND, 21 * SECOND), oldHistory.current)
        assertEquals(2, fixture.counts.last())
    }

    @Test
    fun detectorCountCallbackRestartCannotRestoreItsReceiptOrEmitAnOldPacket() {
        lateinit var tracker: AndroidStepTracker
        var restart = true
        val events = mutableListOf<StepEvent>()
        tracker = AndroidStepTracker(Registration(true), onStepCountChanged = {
            if (restart) {
                restart = false
                tracker.stop()
                assertTrue(tracker.start())
            }
        }, onStepEvent = events::add)
        assertTrue(tracker.start())
        assertTrue(tracker.onStepDetected(SECOND, 20 * SECOND))
        assertTrue(events.isEmpty())
        assertNull(tracker.latestCounterObservation)
        assertTrue(tracker.onStepCounterTotal(100f, 2 * SECOND, 2_100 * MS))
        assertTrue(tracker.onStepDetected(2_500 * MS, 2_600 * MS))
        assertEquals(3, events.single().producerRegistrationGeneration)
        assertEquals(2_600 * MS, events.single().receivedAtElapsedRealtimeNanos)
    }

    private class Fixture(withCounter: Boolean = true) {
        val counts = mutableListOf<Int>()
        val events = mutableListOf<StepEvent>()
        val tracker = AndroidStepTracker(Registration(withCounter), counts::add, onStepEvent = events::add)
            .also { assertTrue(it.start()) }

        fun assertRejectedWithoutMutation(input: () -> Boolean) {
            val oldCounts = counts.toList()
            val oldEvents = events.toList()
            val oldHealth = tracker.operationalHealth
            val oldHistory = tracker.latestCounterObservation
            val oldBaseline = tracker.counterBaselineState
            assertFalse("An earlier actual step callback receipt must be rejected before any state mutation", input())
            assertEquals(oldCounts, counts)
            assertEquals(oldEvents, events)
            assertSame(oldHealth, tracker.operationalHealth)
            assertSame(oldHistory, tracker.latestCounterObservation)
            assertEquals(oldBaseline, tracker.counterBaselineState)
        }
    }

    private class Registration(private val withCounter: Boolean) : StepSensorRegistration {
        override fun hasSensor(type: Int) = type == Sensor.TYPE_STEP_DETECTOR ||
            (withCounter && type == Sensor.TYPE_STEP_COUNTER)
        override fun register(listener: SensorEventListener, type: Int) = hasSensor(type)
        override fun unregister(listener: SensorEventListener?) = Unit
    }

    private companion object {
        const val MS = 1_000_000L
        const val SECOND = 1_000_000_000L
    }
}

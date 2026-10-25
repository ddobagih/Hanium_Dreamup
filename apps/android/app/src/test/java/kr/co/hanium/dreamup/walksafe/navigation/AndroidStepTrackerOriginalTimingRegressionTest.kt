package kr.co.hanium.dreamup.walksafe.navigation

import android.hardware.Sensor
import android.hardware.SensorEventListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADD-MOTION-008/009: different original sensor clocks must survive the emitted step packet. */
class AndroidStepTrackerOriginalTimingRegressionTest {
    @Test
    fun detectorMeasurementNanosecondsSurviveIndependentOfTheMillisecondDisplayTime() {
        val first = detectorEvent(1_000_123_456L)
        val second = detectorEvent(1_000_987_654L)
        assertEquals(1_000_123_456L, first.measurementElapsedRealtimeNanos)
        assertEquals(1_000_987_654L, second.measurementElapsedRealtimeNanos)
        assertEquals(first.timestampMs, second.timestampMs)
        assertEquals(first.deltaSteps, second.deltaSteps)
        assertEquals(first.source, second.source)
        assertEquals(first.confidence, second.confidence)
        assertNotEquals("Original Detector times must survive the producer API", first, second)
    }

    @Test
    fun counterBatchMeasurementNanosecondsSurviveIndependentOfTheMillisecondDisplayTime() {
        val first = counterEvent(1_000_123_456L)
        val second = counterEvent(1_000_987_654L)
        assertEquals(1_000_123_456L, first.measurementElapsedRealtimeNanos)
        assertEquals(1_000_987_654L, second.measurementElapsedRealtimeNanos)
        assertEquals(first.timestampMs, second.timestampMs)
        assertEquals(first.deltaSteps, second.deltaSteps)
        assertEquals(first.source, second.source)
        assertEquals(first.confidence, second.confidence)
        assertNotEquals("Original Counter batch clocks must survive the producer API", first, second)
    }

    @Test
    fun suppliedActualReceiptSurvivesBothProducerPaths() {
        val at = 1_000_123_456L
        val receipt = 1_100_987_654L
        for (event in listOf(detectorEvent(at, receipt), counterEvent(at, receipt))) {
            assertEquals(at, event.measurementElapsedRealtimeNanos)
            assertEquals(receipt, event.receivedAtElapsedRealtimeNanos)
        }
        assertNull(detectorEvent(at).receivedAtElapsedRealtimeNanos)
        assertNull(counterEvent(at).receivedAtElapsedRealtimeNanos)
    }

    @Test
    fun invalidReceiptCannotConsumeEitherSourceMeasurement() {
        val at = 1_000_123_456L
        val detectorEvents = mutableListOf<StepEvent>()
        val detector = tracker(Sensor.TYPE_STEP_DETECTOR, detectorEvents)
        assertFalse(detector.onStepDetected(at, at - 1L))
        assertTrue(detectorEvents.isEmpty())
        assertTrue(detector.onStepDetected(at, at + 1L))
        assertEquals(at, detectorEvents.single().measurementElapsedRealtimeNanos)
        val counterEvents = mutableListOf<StepEvent>()
        val counter = tracker(Sensor.TYPE_STEP_COUNTER, counterEvents)
        assertTrue(counter.onStepCounterTotal(100f, 0L))
        assertFalse(counter.onStepCounterTotal(103f, at, at - 1L))
        assertTrue(counterEvents.isEmpty())
        assertTrue(counter.onStepCounterTotal(103f, at, at + 1L))
        assertEquals(3, counterEvents.single().deltaSteps)
        assertEquals(at, counterEvents.single().measurementElapsedRealtimeNanos)
    }

    private fun detectorEvent(at: Long, receivedAtNs: Long? = null): StepEvent {
        val events = mutableListOf<StepEvent>()
        val tracker = tracker(Sensor.TYPE_STEP_DETECTOR, events)
        assertTrue(tracker.onStepDetected(at, receivedAtNs))
        return events.single()
    }

    private fun counterEvent(at: Long, receivedAtNs: Long? = null): StepEvent {
        val events = mutableListOf<StepEvent>()
        val tracker = tracker(Sensor.TYPE_STEP_COUNTER, events)
        assertTrue(tracker.onStepCounterTotal(100f, 0L))
        assertTrue(tracker.onStepCounterTotal(103f, at, receivedAtNs))
        return events.single()
    }

    private fun tracker(type: Int, events: MutableList<StepEvent>) = AndroidStepTracker(
        registration = Registration(type), onStepCountChanged = {}, onStepEvent = { events += it },
    ).also { assertTrue(it.start()) }

    private class Registration(private val type: Int) : StepSensorRegistration {
        override fun hasSensor(type: Int) = type == this.type
        override fun register(listener: SensorEventListener, type: Int) = type == this.type
        override fun unregister(listener: SensorEventListener?) = Unit
    }
}

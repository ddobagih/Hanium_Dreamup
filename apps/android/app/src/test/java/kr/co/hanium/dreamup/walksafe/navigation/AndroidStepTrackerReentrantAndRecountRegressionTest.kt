package kr.co.hanium.dreamup.walksafe.navigation

import android.hardware.Sensor
import android.hardware.SensorEventListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidStepTrackerReentrantAndRecountRegressionTest {
    @Test
    fun laggingBaselineRecountIsNotReemittedWhenDetectorStalls() {
        val events = mutableListOf<StepEvent>()
        val counts = mutableListOf<Int>()
        val tracker = startedTracker(setOf(Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR), events) { counts += it }
        listOf(1_000L, 1_500L, 2_000L).forEach { assertTrue(tracker.onStepDetected(it * MS)) }
        assertEquals(3, events.sumOf { it.deltaSteps })
        assertTrue(tracker.onStepCounterTotal(801f, 2_500L * MS))
        assertTrue(tracker.onStepCounterTotal(803f, 12_100L * MS))
        assertEquals(3, counts.last())
        assertEquals("Recounted pre-baseline Detector steps are already emitted", 3, events.sumOf { it.deltaSteps })
    }

    @Test
    fun counterCountCallbackRestartCannotEmitAnOldWindowStep() {
        val events = mutableListOf<StepEvent>()
        var restart = false
        lateinit var tracker: AndroidStepTracker
        tracker = startedTracker(setOf(Sensor.TYPE_STEP_COUNTER), events) {
            if (restart && it == 3) {
                restart = false
                tracker.stop()
                assertTrue(tracker.start())
            }
        }
        assertTrue(tracker.onStepCounterTotal(100f, 1_000L * MS))
        restart = true
        assertTrue(tracker.onStepCounterTotal(103f, 3_000L * MS))
        assertTrue("The count callback revoked the old window before PDR emission", events.isEmpty())
        assertTrue(tracker.onStepCounterTotal(200f, 4_000L * MS))
        assertTrue(tracker.onStepCounterTotal(202f, 5_000L * MS))
        assertEquals(2, events.single().deltaSteps)
        assertEquals(5_000L, events.single().timestampMs)
    }

    @Test
    fun counterCountCallbackRestartCannotSeedTheNextDetectorStallClock() {
        val events = mutableListOf<StepEvent>()
        var restart = false
        lateinit var tracker: AndroidStepTracker
        tracker = startedTracker(setOf(Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR), events) {
            if (restart && it == 3) {
                restart = false
                tracker.stop()
                assertTrue(tracker.start())
            }
        }
        assertTrue(tracker.onStepCounterTotal(100f, 1_000L * MS))
        restart = true
        assertTrue(tracker.onStepCounterTotal(103f, 3_000L * MS))
        assertTrue(events.isEmpty())
        assertTrue(tracker.onStepCounterTotal(200f, 4_000L * MS))
        assertTrue(tracker.onStepCounterTotal(203f, 13_500L * MS))
        assertTrue("9.5s in this window must not reuse the previous window's 3s stall origin", events.isEmpty())
    }

    @Test
    fun detectorCountCallbackRestartCannotEmitAnOldWindowStep() {
        val events = mutableListOf<StepEvent>()
        var restart = true
        lateinit var tracker: AndroidStepTracker
        tracker = startedTracker(setOf(Sensor.TYPE_STEP_DETECTOR), events) {
            if (restart && it == 1) {
                restart = false
                tracker.stop()
                assertTrue(tracker.start())
            }
        }
        assertTrue(tracker.onStepDetected(1_000L * MS))
        assertTrue("The old Detector event was revoked before emission", events.isEmpty())
        assertTrue(tracker.onStepDetected(2_000L * MS))
        assertEquals(2_000L, events.single().timestampMs)
    }

    @Test
    fun eventCallbackRestartKeepsTheDeliveredPacketAndClearsTheNextWindowBaseline() {
        val events = mutableListOf<StepEvent>()
        var restart = true
        lateinit var tracker: AndroidStepTracker
        tracker = AndroidStepTracker(Registration(setOf(Sensor.TYPE_STEP_COUNTER)), {}, onStepEvent = {
            events += it
            if (restart) {
                restart = false
                tracker.stop()
                assertTrue(tracker.start())
            }
        })
        assertTrue(tracker.start())
        assertTrue(tracker.onStepCounterTotal(100f, 1_000L * MS, 1_100L * MS))
        assertTrue(tracker.onStepCounterTotal(103f, 3_000L * MS, 3_100L * MS))
        val first = events.single()
        assertEquals(3, first.deltaSteps)
        assertEquals(3_000L * MS, first.measurementElapsedRealtimeNanos)
        assertEquals(3_100L * MS, first.receivedAtElapsedRealtimeNanos)
        assertTrue(tracker.onStepCounterTotal(200f, 4_000L * MS, 4_100L * MS))
        assertEquals(1, events.size)
        assertTrue(tracker.onStepCounterTotal(202f, 5_000L * MS, 5_100L * MS))
        assertEquals(2, events.last().deltaSteps)
        assertEquals(5_000L * MS, events.last().measurementElapsedRealtimeNanos)
        assertEquals(first, events.first())
    }

    private fun startedTracker(types: Set<Int>, events: MutableList<StepEvent>, count: (Int) -> Unit) =
        AndroidStepTracker(Registration(types), count, onStepEvent = { events += it }).also { assertTrue(it.start()) }

    private class Registration(private val types: Set<Int>) : StepSensorRegistration {
        override fun hasSensor(type: Int) = type in types
        override fun register(listener: SensorEventListener, type: Int) = type in types
        override fun unregister(listener: SensorEventListener?) = Unit
    }

    private companion object { const val MS = 1_000_000L }
}

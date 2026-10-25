package kr.co.hanium.dreamup.walksafe.navigation

import android.hardware.Sensor
import android.hardware.SensorEventListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADD-MOTION-032/035: Counter time is checked before UI totals and handover state change. */
class AndroidStepTrackerCounterOrderingTest {
    @Test
    fun duplicateCounterTimeCannotChangeTotalsOrConsumeTheNextIncrement() =
        assertCounterInputRejectedWithoutStateChange(3_000_000_000L)

    @Test
    fun reversedCounterTimeCannotChangeTotalsOrConsumeTheNextIncrement() =
        assertCounterInputRejectedWithoutStateChange(2_000_000_000L)

    @Test
    fun reversedCounterCannotAbsorbStepsDuringSilentDetectorHandover() {
        val counts = mutableListOf<Int>()
        val events = mutableListOf<StepEvent>()
        val tracker = tracker(setOf(Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR), counts, events)
        assertTrue(tracker.onStepCounterTotal(100f, SECOND))
        assertTrue(tracker.onStepCounterTotal(103f, 12 * SECOND))
        assertFalse(tracker.onStepCounterTotal(104f, 11 * SECOND))
        assertEquals(listOf(0, 3), counts)
        assertEquals(listOf(counter(3, 12_000L)), events.map { it.legacyCountAndTiming() })
        assertTrue(tracker.onStepCounterTotal(105f, 13 * SECOND))
        assertEquals(listOf(0, 3, 5), counts)
        assertEquals(listOf(counter(3, 12_000L), counter(2, 13_000L)), events.map { it.legacyCountAndTiming() })
    }

    @Test
    fun restartDropsThePreviousCounterTimestampWatermark() {
        val counts = mutableListOf<Int>()
        val events = mutableListOf<StepEvent>()
        val tracker = tracker(setOf(Sensor.TYPE_STEP_COUNTER), counts, events)
        assertTrue(tracker.onStepCounterTotal(100f, 10 * SECOND))
        tracker.stop()
        assertTrue(tracker.start())
        assertTrue(tracker.onStepCounterTotal(200f, SECOND))
        assertTrue(tracker.onStepCounterTotal(202f, 2 * SECOND))
        assertEquals(listOf(counter(2, 2_000L)), events.map { it.legacyCountAndTiming() })
    }

    private fun assertCounterInputRejectedWithoutStateChange(invalidAt: Long) {
        val counts = mutableListOf<Int>()
        val events = mutableListOf<StepEvent>()
        val tracker = tracker(setOf(Sensor.TYPE_STEP_COUNTER), counts, events)
        assertTrue(tracker.onStepCounterTotal(100f, SECOND))
        assertTrue(tracker.onStepCounterTotal(103f, 3 * SECOND))
        assertFalse(tracker.onStepCounterTotal(104f, invalidAt))
        assertEquals(listOf(0, 3), counts)
        assertEquals(listOf(counter(3, 3_000L)), events.map { it.legacyCountAndTiming() })
        assertTrue(tracker.onStepCounterTotal(105f, 4 * SECOND))
        assertEquals(listOf(0, 3, 5), counts)
        assertEquals(listOf(counter(3, 3_000L), counter(2, 4_000L)), events.map { it.legacyCountAndTiming() })
    }

    private fun tracker(types: Set<Int>, counts: MutableList<Int>, events: MutableList<StepEvent>) =
        AndroidStepTracker(registration = Registration(types), onStepCountChanged = { counts += it },
            onStepEvent = { events += it }).also { assertTrue(it.start()) }

    private fun counter(steps: Int, at: Long) =
        StepEvent(steps, at, StepEventSource.STEP_COUNTER, StepEventConfidence.MEDIUM, at * 1_000_000L)

    private class Registration(private val types: Set<Int>) : StepSensorRegistration {
        override fun hasSensor(type: Int): Boolean = type in types
        override fun register(listener: SensorEventListener, type: Int): Boolean = type in types
        override fun unregister(listener: SensorEventListener?) = Unit
    }

    private companion object {
        const val SECOND = 1_000_000_000L
    }
}

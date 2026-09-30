package kr.co.hanium.dreamup.walksafe.navigation

import android.hardware.Sensor
import android.hardware.SensorEventListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADD-MOTION-032: impossible counter input is rejected before the baseline or walk total changes. */
class AndroidStepTrackerCounterInputTest {
    @Test
    fun nonFiniteNegativeOrUntimedCounterValuesCannotPoisonTheBaseline() {
        val counts = mutableListOf<Int>()
        val events = mutableListOf<StepEvent>()
        val tracker = counterOnlyTracker(counts, events)

        assertFalse(tracker.onStepCounterTotal(Float.NaN, 1_000_000_000L))
        assertFalse(tracker.onStepCounterTotal(-1f, 1_100_000_000L))
        assertFalse(tracker.onStepCounterTotal(Float.POSITIVE_INFINITY, 1_200_000_000L))
        assertFalse(tracker.onStepCounterTotal(50f, -1L))
        assertTrue(counts.isEmpty())

        assertTrue(tracker.onStepCounterTotal(100f, 2_000_000_000L))
        // The same inputs stay rejected once the baseline exists.
        assertFalse(tracker.onStepCounterTotal(Float.NaN, 2_200_000_000L))
        assertFalse(tracker.onStepCounterTotal(-5f, 2_300_000_000L))
        assertFalse(tracker.onStepCounterTotal(Float.NEGATIVE_INFINITY, 2_400_000_000L))
        assertTrue(tracker.onStepCounterTotal(103f, 3_000_000_000L))

        assertEquals(
            listOf(StepEvent(3, 3_000L, StepEventSource.STEP_COUNTER, StepEventConfidence.MEDIUM)),
            events,
        )
        assertEquals(listOf(0, 3), counts)
    }

    @Test
    fun counterJumpBeyondTheIntegerTotalIsRejectedWithoutSaturatingLaterSteps() {
        val counts = mutableListOf<Int>()
        val events = mutableListOf<StepEvent>()
        val tracker = counterOnlyTracker(counts, events)

        assertTrue(tracker.onStepCounterTotal(10f, 1_000_000_000L))
        assertFalse(tracker.onStepCounterTotal(3.0e9f, 2_000_000_000L))
        assertTrue(tracker.onStepCounterTotal(12f, 3_000_000_000L))

        assertEquals(
            listOf(StepEvent(2, 3_000L, StepEventSource.STEP_COUNTER, StepEventConfidence.MEDIUM)),
            events,
        )
        assertEquals(listOf(0, 2), counts)
    }

    private fun counterOnlyTracker(
        counts: MutableList<Int>,
        events: MutableList<StepEvent>,
    ): AndroidStepTracker {
        val tracker = AndroidStepTracker(
            registration = CounterOnlyRegistration(),
            onStepCountChanged = { counts += it },
            onStepEvent = { events += it },
        )
        assertTrue(tracker.start())
        return tracker
    }

    private class CounterOnlyRegistration : StepSensorRegistration {
        override fun hasSensor(type: Int): Boolean = type == Sensor.TYPE_STEP_COUNTER
        override fun register(listener: SensorEventListener, type: Int): Boolean =
            type == Sensor.TYPE_STEP_COUNTER
        override fun unregister(listener: SensorEventListener?) = Unit
    }
}

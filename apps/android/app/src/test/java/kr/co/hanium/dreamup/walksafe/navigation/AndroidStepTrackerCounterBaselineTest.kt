package kr.co.hanium.dreamup.walksafe.navigation

import android.hardware.Sensor
import android.hardware.SensorEventListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S09-20 (originals/09.txt:57): the Counter baseline state is explicit, Detector steps from the
 * start-up interval before the first Counter sample are session steps exactly once (the shown count
 * matches the PDR steps), and the Counter's cumulative history is never imported.
 */
class AndroidStepTrackerCounterBaselineTest {
    @Test
    fun detectorStepsBeforeTheFirstCounterSampleAreCountedOnceAndMatchPdr() {
        val counts = mutableListOf<Int>()
        val events = mutableListOf<StepEvent>()
        val tracker = tracker(setOf(Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR), counts, events)
        assertEquals(StepCounterBaselineState.NOT_ESTABLISHED, tracker.counterBaselineState)

        listOf(1_000L, 1_500L, 2_000L).forEach { assertTrue(tracker.onStepDetected(it * MS)) }
        assertEquals(listOf(1, 2, 3), counts)
        // First Counter sample: its cumulative 5 000 steps (history plus the three) become the baseline.
        assertTrue(tracker.onStepCounterTotal(5_003f, 2_000L * MS))
        assertEquals(StepCounterBaselineState.ESTABLISHED, tracker.counterBaselineState)
        assertEquals(3, counts.last())

        // Later steps are counted by the Counter only, not again by the Detector.
        listOf(2_500L, 3_000L).forEach { assertTrue(tracker.onStepDetected(it * MS)) }
        assertTrue(tracker.onStepCounterTotal(5_005f, 3_000L * MS))
        assertEquals(5, counts.last())
        assertEquals(counts.last(), events.sumOf { it.deltaSteps })
    }

    @Test
    fun aLateDetectorEventInsideTheBaselineIsASessionStepOnce() {
        val counts = mutableListOf<Int>()
        val events = mutableListOf<StepEvent>()
        val tracker = tracker(setOf(Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR), counts, events)
        assertTrue(tracker.onStepDetected(1_000L * MS))
        assertTrue(tracker.onStepCounterTotal(802f, 1_600L * MS))
        // Delivered after the Counter sample but measured before it (Detector latency).
        assertTrue(tracker.onStepDetected(1_500L * MS))
        assertEquals(2, counts.last())
        assertTrue(tracker.onStepDetected(2_100L * MS))
        assertTrue(tracker.onStepCounterTotal(803f, 2_100L * MS))
        assertEquals(3, counts.last())
        assertEquals(3, events.sumOf { it.deltaSteps })
    }

    /** w25 review pos-F10: a first Counter sample stamped before its total caught up does not double-count. */
    @Test
    fun aLaggingFirstCounterTotalDoesNotCountTheStartUpStepsTwice() {
        val counts = mutableListOf<Int>()
        val events = mutableListOf<StepEvent>()
        val tracker = tracker(setOf(Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR), counts, events)
        listOf(1_000L, 1_500L, 2_000L).forEach { assertTrue(tracker.onStepDetected(it * MS)) }
        // Stamped at the third step, but the total includes only the first one.
        assertTrue(tracker.onStepCounterTotal(801f, 2_000L * MS))
        assertEquals(3, counts.last())
        // The Counter catches up (two recounted start-up steps) while the Detector reports one new step.
        assertTrue(tracker.onStepDetected(2_500L * MS))
        assertTrue(tracker.onStepCounterTotal(804f, 2_600L * MS))
        assertEquals(4, counts.last())
        assertEquals(counts.last(), events.sumOf { it.deltaSteps })
        // Later steps are counted once.
        assertTrue(tracker.onStepDetected(3_000L * MS))
        assertTrue(tracker.onStepCounterTotal(805f, 3_000L * MS))
        assertEquals(5, counts.last())
        assertEquals(counts.last(), events.sumOf { it.deltaSteps })
    }

    @Test
    fun aCounterIncrementDeliveredBeforeItsDetectorEventIsNotLostAsARecount() {
        val counts = mutableListOf<Int>()
        val events = mutableListOf<StepEvent>()
        val tracker = tracker(setOf(Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR), counts, events)
        listOf(1_000L, 1_500L, 2_000L).forEach { assertTrue(tracker.onStepDetected(it * MS)) }
        assertTrue(tracker.onStepCounterTotal(5_003f, 2_000L * MS))
        // The Counter reports the next step before the Detector event for it is delivered.
        assertTrue(tracker.onStepCounterTotal(5_004f, 2_500L * MS))
        assertTrue(tracker.onStepDetected(2_500L * MS))
        assertEquals(4, counts.last())
        assertEquals(counts.last(), events.sumOf { it.deltaSteps })
        assertTrue(counts.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun counterOnlyStartsAtZeroAndDetectorOnlyHasNoCounterBaseline() {
        val counts = mutableListOf<Int>()
        val counterOnly = tracker(setOf(Sensor.TYPE_STEP_COUNTER), counts, mutableListOf())
        assertEquals(StepCounterBaselineState.NOT_ESTABLISHED, counterOnly.counterBaselineState)
        assertTrue(counterOnly.onStepCounterTotal(12_345f, 1_000L * MS))
        assertEquals(StepCounterBaselineState.ESTABLISHED, counterOnly.counterBaselineState)
        assertEquals(listOf(0), counts)
        assertTrue(counterOnly.onStepCounterTotal(12_348f, 2_000L * MS))
        assertEquals(listOf(0, 3), counts)

        val detectorOnly = tracker(setOf(Sensor.TYPE_STEP_DETECTOR), mutableListOf(), mutableListOf())
        assertEquals(StepCounterBaselineState.NOT_REGISTERED, detectorOnly.counterBaselineState)
        detectorOnly.stop()
        counterOnly.stop()
        assertEquals(StepCounterBaselineState.NOT_REGISTERED, counterOnly.counterBaselineState)
    }

    private fun tracker(sensors: Set<Int>, counts: MutableList<Int>, events: MutableList<StepEvent>): AndroidStepTracker {
        val tracker = AndroidStepTracker(
            registration = Registration(sensors),
            onStepCountChanged = { counts += it },
            onStepEvent = { events += it },
        )
        assertTrue(tracker.start())
        return tracker
    }

    private class Registration(private val sensors: Set<Int>) : StepSensorRegistration {
        override fun hasSensor(type: Int): Boolean = type in sensors
        override fun register(listener: SensorEventListener, type: Int): Boolean = type in sensors
        override fun unregister(listener: SensorEventListener?) = Unit
    }

    private companion object {
        const val MS = 1_000_000L
    }
}

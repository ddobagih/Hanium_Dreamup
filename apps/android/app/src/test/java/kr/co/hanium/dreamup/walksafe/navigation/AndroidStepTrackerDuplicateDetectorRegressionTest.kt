package kr.co.hanium.dreamup.walksafe.navigation

import android.hardware.Sensor
import android.hardware.SensorEventListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADD-MOTION-008: repeating a Detector observation does not create an independent step. */
class AndroidStepTrackerDuplicateDetectorRegressionTest {
    @Test
    fun repeatingOneDetectorTimestampCountsAndEmitsExactlyOnce() {
        val counts = mutableListOf<Int>()
        val events = mutableListOf<StepEvent>()
        val tracker = tracker(setOf(Sensor.TYPE_STEP_DETECTOR), counts, events)
        assertTrue(tracker.onStepDetected(1_000L * MS))
        tracker.onStepDetected(1_000L * MS)

        assertEquals("The original Detector timestamp is one step", listOf(1), counts)
        assertEquals(listOf(detector(1_000L)), events.map { it.legacyCountAndTiming() })
    }

    @Test
    fun aDuplicateBeforeTheCounterBaselineDoesNotIncreaseTheSessionOffsetOrPdrSteps() {
        val counts = mutableListOf<Int>()
        val events = mutableListOf<StepEvent>()
        val tracker = tracker(setOf(Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR), counts, events)
        assertTrue(tracker.onStepDetected(1_000L * MS))
        tracker.onStepDetected(1_000L * MS)
        assertEquals(listOf(1), counts)
        assertEquals(listOf(detector(1_000L)), events.map { it.legacyCountAndTiming() })

        assertTrue(tracker.onStepCounterTotal(100f, 1_500L * MS))
        assertEquals(StepCounterBaselineState.ESTABLISHED, tracker.counterBaselineState)
        assertEquals(1, counts.last())
        assertTrue(tracker.onStepDetected(2_000L * MS))
        assertTrue(tracker.onStepCounterTotal(101f, 2_100L * MS))
        assertEquals(2, counts.last())
        assertEquals(listOf(detector(1_000L), detector(2_000L)), events.map { it.legacyCountAndTiming() })
        assertEquals(counts.last(), events.sumOf { it.deltaSteps })
    }

    @Test
    fun distinctDetectorTimestampsAreAcceptedAndNegativeOrReversedInputsCannotChangeCounts() {
        val counts = mutableListOf<Int>()
        val events = mutableListOf<StepEvent>()
        val tracker = tracker(setOf(Sensor.TYPE_STEP_DETECTOR), counts, events)
        assertTrue(tracker.onStepDetected(1_000L * MS))
        assertTrue(tracker.onStepDetected(1_500L * MS))
        val before = events.toList()
        assertFalse(tracker.onStepDetected(-1L))
        assertFalse(tracker.onStepDetected(1_250L * MS))
        assertEquals(listOf(1, 2), counts)
        assertEquals(before, events)
        assertTrue(tracker.onStepDetected(2_000L * MS))
        assertEquals(listOf(1, 2, 3), counts)
        assertEquals(listOf(detector(1_000L), detector(1_500L), detector(2_000L)), events.map { it.legacyCountAndTiming() })
    }

    @Test
    fun newWalkResetDropsPreviousCountsAndAcceptsANewDetectorObservation() {
        val counts = mutableListOf<Int>()
        val events = mutableListOf<StepEvent>()
        val tracker = tracker(setOf(Sensor.TYPE_STEP_DETECTOR), counts, events)
        assertTrue(tracker.onStepDetected(1_000L * MS))
        assertTrue(tracker.onStepDetected(1_500L * MS))
        tracker.resetForNewWalk()
        assertEquals(listOf(1, 2, 0), counts)
        assertTrue(tracker.start())
        assertTrue(tracker.onStepDetected(2_000L * MS))
        assertEquals(listOf(1, 2, 0, 1), counts)
        assertEquals(detector(2_000L), events.last().legacyCountAndTiming())
    }

    @Test
    fun aLateDetectorCoveredByCounterHandoverDoesNotReemitAnAlreadyCountedStep() {
        val counts = mutableListOf<Int>()
        val events = mutableListOf<StepEvent>()
        val tracker = tracker(setOf(Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR), counts, events)
        assertTrue(tracker.onStepCounterTotal(100f, 0L))
        listOf(1_000L, 1_600L, 2_200L).forEach { assertTrue(tracker.onStepDetected(it * MS)) }
        assertTrue(tracker.onStepCounterTotal(108f, 15_000L * MS))
        val before = events.toList()
        val beforeCount = counts.last()
        assertTrue(tracker.onStepDetected(14_900L * MS))
        assertEquals(before, events)
        assertEquals(beforeCount, counts.last())

        assertTrue(tracker.onStepDetected(15_600L * MS))
        assertTrue(tracker.onStepCounterTotal(109f, 15_600L * MS))
        assertEquals(listOf(detector(1_000L), detector(1_600L), detector(2_200L),
            StepEvent(5, 15_000L, StepEventSource.STEP_COUNTER, StepEventConfidence.MEDIUM, 15_000L * MS),
            detector(15_600L)), events.map { it.legacyCountAndTiming() })
        assertEquals(9, counts.last())
        assertEquals(counts.last(), events.sumOf { it.deltaSteps })
    }

    private fun tracker(sensors: Set<Int>, counts: MutableList<Int>, events: MutableList<StepEvent>): AndroidStepTracker =
        AndroidStepTracker(Registration(sensors), onStepCountChanged = { counts += it },
            onStepEvent = { events += it }).also { assertTrue(it.start()) }

    private fun detector(atMs: Long) = StepEvent(1, atMs, StepEventSource.STEP_DETECTOR, StepEventConfidence.HIGH, atMs * MS)

    private class Registration(private val sensors: Set<Int>) : StepSensorRegistration {
        override fun hasSensor(type: Int): Boolean = type in sensors
        override fun register(listener: SensorEventListener, type: Int): Boolean = type in sensors
        override fun unregister(listener: SensorEventListener?) = Unit
    }

    private companion object {
        const val MS = 1_000_000L
    }
}

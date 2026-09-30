package kr.co.hanium.dreamup.walksafe.navigation

import android.hardware.Sensor
import android.hardware.SensorEventListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S09-18 / ADD-MOTION-061: a registered but silent Step Detector hands PDR input to the Step
 * Counter without counting any step twice and without inventing per-step times for a batch.
 */
class AndroidStepTrackerDetectorStallTest {
    @Test
    fun registeredDetectorThatNeverReportsFallsBackToCounterBatches() {
        val events = mutableListOf<StepEvent>()
        val tracker = bothSensorsTracker(events)

        assertTrue(tracker.onStepCounterTotal(100f, 0L))
        assertTrue(tracker.onStepCounterTotal(104f, 12_000_000_000L))

        assertEquals(listOf(counter(4, 12_000L)), events)
    }

    @Test
    fun healthyDetectorKeepsTheCounterSilent() {
        val events = mutableListOf<StepEvent>()
        val tracker = bothSensorsTracker(events)
        assertTrue(tracker.onStepCounterTotal(100f, 0L))

        listOf(1_000L, 1_600L, 2_200L).forEach { assertTrue(tracker.onStepDetected(it * MS)) }
        assertTrue(tracker.onStepCounterTotal(103f, 2_200L * MS))

        assertEquals(listOf(detector(1_000L), detector(1_600L), detector(2_200L)), events)
    }

    @Test
    fun detectorStallHandsOnlyUncoveredStepsToTheCounterAndRecoversWithoutDuplicates() {
        val events = mutableListOf<StepEvent>()
        val counts = mutableListOf<Int>()
        val tracker = bothSensorsTracker(events, counts)
        assertTrue(tracker.onStepCounterTotal(100f, 0L))
        listOf(1_000L, 1_600L, 2_200L).forEach { tracker.onStepDetected(it * MS) }

        // Detector silent for longer than the documented delivery delays while the counter grows.
        assertTrue(tracker.onStepCounterTotal(108f, 15_000L * MS))
        // A late detector event for a step the batch already covered is not counted again.
        assertTrue(tracker.onStepDetected(14_900L * MS))
        // The detector resumes with a newer step and is preferred again.
        assertTrue(tracker.onStepDetected(15_600L * MS))
        assertTrue(tracker.onStepCounterTotal(109f, 15_600L * MS))

        assertEquals(
            listOf(detector(1_000L), detector(1_600L), detector(2_200L), counter(5, 15_000L), detector(15_600L)),
            events,
        )
        assertEquals(9, events.sumOf { it.deltaSteps })
        assertEquals(9, counts.last())
    }

    @Test
    fun detectorStepsEmittedBeforeTheCounterCatchesUpAreSubtracted() {
        val events = mutableListOf<StepEvent>()
        val tracker = bothSensorsTracker(events)
        assertTrue(tracker.onStepCounterTotal(100f, 0L))
        listOf(1_000L, 1_500L, 2_000L).forEach { tracker.onStepDetected(it * MS) }

        assertTrue(tracker.onStepCounterTotal(105f, 13_000L * MS))

        assertEquals(counter(2, 13_000L), events.last())
        assertEquals(5, events.sumOf { it.deltaSteps })
    }

    @Test
    fun detectorStepsBeforeTheCounterBaselineAreNotSubtractedFromTheHandOverBatch() {
        // S09-18 review: the Counter's first sample already includes the three detector steps, so
        // its window starts after them; only the 20 later steps are the hand-over batch.
        val events = mutableListOf<StepEvent>()
        val tracker = bothSensorsTracker(events)
        listOf(1_000L, 1_500L, 2_000L).forEach { assertTrue(tracker.onStepDetected(it * MS)) }
        assertTrue(tracker.onStepCounterTotal(103f, 2_500L * MS))

        // The detector stalls while the user takes 20 more real steps.
        assertTrue(tracker.onStepCounterTotal(123f, 15_000L * MS))

        assertEquals(
            listOf(detector(1_000L), detector(1_500L), detector(2_000L), counter(20, 15_000L)),
            events,
        )
        assertEquals(23, events.sumOf { it.deltaSteps })
        // A late detector event covered by the batch is still not counted again.
        assertTrue(tracker.onStepDetected(14_000L * MS))
        assertEquals(23, events.sumOf { it.deltaSteps })
    }

    @Test
    fun surplusCounterStepsFromAHealthyDetectorPeriodAreNotDumpedLater() {
        val events = mutableListOf<StepEvent>()
        val tracker = bothSensorsTracker(events)
        assertTrue(tracker.onStepCounterTotal(100f, 0L))
        listOf(1_000L, 1_500L, 2_000L, 2_500L, 3_000L).forEach { tracker.onStepDetected(it * MS) }
        // The counter saw two more steps than the detector while the detector was healthy.
        assertTrue(tracker.onStepCounterTotal(107f, 3_000L * MS))

        assertTrue(tracker.onStepCounterTotal(109f, 30_000L * MS))

        assertEquals(counter(2, 30_000L), events.last())
    }

    @Test
    fun counterOnlyAndDetectorOnlyPathsAreUnchanged() {
        val counterEvents = mutableListOf<StepEvent>()
        val counterOnly = AndroidStepTracker(
            registration = Registration(setOf(Sensor.TYPE_STEP_COUNTER)),
            onStepCountChanged = {},
            onStepEvent = { counterEvents += it },
        )
        assertTrue(counterOnly.start())
        counterOnly.onStepCounterTotal(10f, 0L)
        counterOnly.onStepCounterTotal(12f, 500L * MS)

        val detectorEvents = mutableListOf<StepEvent>()
        val detectorCounts = mutableListOf<Int>()
        val detectorOnly = AndroidStepTracker(
            registration = Registration(setOf(Sensor.TYPE_STEP_DETECTOR)),
            onStepCountChanged = { detectorCounts += it },
            onStepEvent = { detectorEvents += it },
        )
        assertTrue(detectorOnly.start())
        detectorOnly.onStepDetected(100L * MS)
        detectorOnly.onStepDetected(60_000L * MS)

        assertEquals(listOf(counter(2, 500L)), counterEvents)
        assertEquals(listOf(detector(100L), detector(60_000L)), detectorEvents)
        assertEquals(listOf(1, 2), detectorCounts)
    }

    @Test
    fun restartDropsTheOldDetectorAndCoverageState() {
        val events = mutableListOf<StepEvent>()
        val tracker = bothSensorsTracker(events)
        assertTrue(tracker.onStepCounterTotal(100f, 0L))
        tracker.onStepDetected(1_000L * MS)
        assertTrue(tracker.onStepCounterTotal(110f, 20_000L * MS))
        tracker.stop()
        assertTrue(tracker.start())
        events.clear()

        assertTrue(tracker.onStepCounterTotal(200f, 30_000L * MS))
        assertTrue(tracker.onStepDetected(30_500L * MS))
        assertTrue(tracker.onStepCounterTotal(201f, 30_500L * MS))

        assertEquals(listOf(detector(30_500L)), events)
    }

    private fun bothSensorsTracker(
        events: MutableList<StepEvent>,
        counts: MutableList<Int> = mutableListOf(),
    ): AndroidStepTracker = AndroidStepTracker(
        registration = Registration(setOf(Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR)),
        onStepCountChanged = { counts += it },
        onStepEvent = { events += it },
    ).also { assertTrue(it.start()) }

    private fun counter(steps: Int, atMs: Long) =
        StepEvent(steps, atMs, StepEventSource.STEP_COUNTER, StepEventConfidence.MEDIUM)

    private fun detector(atMs: Long) =
        StepEvent(1, atMs, StepEventSource.STEP_DETECTOR, StepEventConfidence.HIGH)

    private class Registration(private val types: Set<Int>) : StepSensorRegistration {
        override fun hasSensor(type: Int): Boolean = type in types
        override fun register(listener: SensorEventListener, type: Int): Boolean = type in types
        override fun unregister(listener: SensorEventListener?) = Unit
    }

    private companion object {
        const val MS = 1_000_000L
    }
}

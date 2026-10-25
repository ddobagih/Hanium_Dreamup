package kr.co.hanium.dreamup.walksafe.navigation

import android.hardware.Sensor
import android.hardware.SensorEventListener
import kr.co.hanium.dreamup.walksafe.navigation.positioning.TrackedSensorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADD-MOTION-032/035: representation limits precede count, clock, health and PDR mutations. */
class StepTrackerIntegerCapacityRegressionTest {
    @Test
    fun registeredDetectorCannotOverflowTheCounterTotalRetainedAcrossRestart() {
        val f = fullCounterWalk()
        f.restart(DETECTOR)
        val health = f.tracker.operationalHealth
        val counts = f.counts.toList()
        val events = f.events.toList()

        assertFalse("One more registered Detector step cannot fit in the retained Int total",
            f.tracker.onStepDetected(3 * SECOND, 3 * SECOND + MS, 1f))
        assertEquals(counts, f.counts)
        assertEquals(events, f.events)
        assertSame(health, f.tracker.operationalHealth)
        assertNull(f.tracker.latestCounterObservation)
        assertEquals(setOf(Sensor.TYPE_STEP_DETECTOR), f.registration.registered)

        f.tracker.resetForNewWalk()
        assertTrue(f.tracker.start())
        assertTrue(f.tracker.onStepDetected(3 * SECOND, 3 * SECOND + MS, 1f))
        assertEquals(1, f.counts.last())
        assertEquals(StepEventSource.STEP_DETECTOR, f.events.last().source)
    }

    @Test
    fun detectorBeforeTheNewCounterBaselineCannotOverflowTheRetainedOffset() {
        val f = fullCounterWalk()
        f.restart(BOTH)
        val health = f.tracker.operationalHealth
        val counts = f.counts.toList()
        val events = f.events.toList()

        assertFalse(f.tracker.onStepDetected(3 * SECOND, 3 * SECOND + MS))
        assertEquals(counts, f.counts)
        assertEquals(events, f.events)
        assertSame(health, f.tracker.operationalHealth)
        assertEquals(StepCounterBaselineState.NOT_ESTABLISHED, f.tracker.counterBaselineState)

        // This baseline changes no count and is representable even at a full session total.
        assertTrue(f.tracker.onStepCounterTotal(LARGE_COUNTER, 3 * SECOND, 3 * SECOND + MS))
        assertEquals(Int.MAX_VALUE, f.counts.last())
        assertEquals(StepCounterBaselineState.ESTABLISHED, f.tracker.counterBaselineState)
    }

    @Test
    fun uncoveredDetectorCannotOverflowPdrButAnOlderCoveredDetectorIsStillAccepted() {
        val f = Fixture(BOTH)
        assertTrue(f.tracker.onStepCounterTotal(1f, SECOND, SECOND + MS))
        assertTrue(f.tracker.onStepCounterTotal(LARGE_COUNTER, 20 * SECOND, 20 * SECOND + MS))
        assertEquals(Int.MAX_VALUE, f.counts.last())
        assertEquals(Int.MAX_VALUE, f.events.single().deltaSteps)
        val health = f.tracker.operationalHealth
        val counts = f.counts.toList()
        val events = f.events.toList()

        assertFalse(f.tracker.onStepDetected(21 * SECOND, 21 * SECOND + MS))
        assertSame(health, f.tracker.operationalHealth)
        assertEquals(counts, f.counts)
        assertEquals(events, f.events)

        // The rejected later measurement/receipt cannot fence this already covered original step.
        assertTrue(f.tracker.onStepDetected(19 * SECOND, 20 * SECOND + 2 * MS))
        assertEquals(counts, f.counts)
        assertEquals(events, f.events)
        assertEquals(19 * SECOND, f.tracker.operationalHealth.source(TrackedSensorSource.STEP_DETECTOR)!!
            .lastAcceptedSample!!.measurementElapsedRealtimeNanos)
    }

    @Test
    fun detectorCanUseTheLastIntegerSlotAndThenRejectWithoutConsumingTheNextClock() {
        val f = fullCounterWalk(baseline = 2f)
        f.restart(DETECTOR)
        assertTrue(f.tracker.onStepDetected(3 * SECOND, 3 * SECOND + MS, 0.25f))
        assertEquals(Int.MAX_VALUE, f.counts.last())
        val health = f.tracker.operationalHealth
        val events = f.events.toList()
        assertFalse(f.tracker.onStepDetected(4 * SECOND, 4 * SECOND + MS))
        assertSame(health, f.tracker.operationalHealth)
        assertEquals(Int.MAX_VALUE, f.counts.last())
        assertEquals(events, f.events)
    }

    @Test
    fun twoFallbackStepsAreRejectedAtomicallyWhenOnlyOneIntegerSlotRemains() {
        val f = fullCounterWalk(baseline = 2f)
        f.restart(ACCELEROMETER)
        prepareTwoPeaks(f)
        val health = f.tracker.operationalHealth
        val counts = f.counts.toList()
        val events = f.events.toList()

        f.acceleration(3_700, 9f)
        assertEquals("A two-step sample must not partially commit its first step", counts, f.counts)
        assertEquals(events, f.events)
        assertSame(health, f.tracker.operationalHealth)

        // A corrected, non-emitting value at the same time proves the rejected sample's clock stayed free.
        f.acceleration(3_700, 14f)
        assertNotSame(health, f.tracker.operationalHealth)
        assertEquals(3_700 * MS, f.tracker.operationalHealth.source(TrackedSensorSource.ACCELEROMETER)!!
            .lastAcceptedSample!!.measurementElapsedRealtimeNanos)
        assertEquals(counts, f.counts)
        assertEquals(events, f.events)
        val retryHealth = f.tracker.operationalHealth
        f.acceleration(3_800, 9f)
        assertSame(retryHealth, f.tracker.operationalHealth)
        assertEquals(counts, f.counts)
        assertEquals(events, f.events)
    }

    @Test
    fun aFullTotalStillAcceptsZeroStepAccelerationAndRejectsAClosingPeak() {
        val f = fullCounterWalk()
        f.restart(ACCELEROMETER)
        prepareTwoPeaks(f)
        val health = f.tracker.operationalHealth
        assertEquals(3_600 * MS, health.source(TrackedSensorSource.ACCELEROMETER)!!
            .lastAcceptedSample!!.measurementElapsedRealtimeNanos)
        val counts = f.counts.toList()
        val events = f.events.toList()
        f.acceleration(3_700, 9f)
        assertSame(health, f.tracker.operationalHealth)
        assertEquals(counts, f.counts)
        assertEquals(events, f.events)
    }

    @Test
    fun exactlyTwoSlotsAcceptBothFallbackStepsButTheNextSingleStepCannotOverflow() {
        val f = fullCounterWalk(baseline = 3f)
        f.restart(ACCELEROMETER)
        val beforeEvents = f.events.size
        prepareTwoPeaks(f)
        f.acceleration(3_700, 9f)
        assertEquals(listOf(Int.MAX_VALUE - 1, Int.MAX_VALUE), f.counts.takeLast(2))
        assertEquals(listOf(3_100 * MS, 3_600 * MS), f.events.drop(beforeEvents)
            .map { it.measurementElapsedRealtimeNanos })
        assertTrue(f.events.drop(beforeEvents).all {
            it.deltaSteps == 1 && it.source == StepEventSource.ACCELEROMETER_FALLBACK && it.confidence == StepEventConfidence.LOW
        })
        f.acceleration(4_100, 13f)
        val health = f.tracker.operationalHealth
        val events = f.events.toList()
        f.acceleration(4_200, 9f)
        assertSame(health, f.tracker.operationalHealth)
        assertEquals(Int.MAX_VALUE, f.counts.last())
        assertEquals(events, f.events)
    }

    @Test
    fun newWalkResetRestoresNormalTwoPeakFallbackAfterCapacityRejection() {
        val f = fullCounterWalk(baseline = 2f)
        f.restart(ACCELEROMETER)
        prepareTwoPeaks(f)
        val counts = f.counts.toList()
        f.acceleration(3_700, 9f)
        assertEquals(counts, f.counts)
        f.tracker.resetForNewWalk()
        assertTrue(f.tracker.start())
        prepareTwoPeaks(f)
        f.acceleration(3_700, 9f)
        assertEquals(listOf(0, 1, 2), f.counts.takeLast(3))
        assertEquals(listOf(StepEventSource.ACCELEROMETER_FALLBACK, StepEventSource.ACCELEROMETER_FALLBACK),
            f.events.takeLast(2).map { it.source })
    }

    @Test
    fun ordinaryRegisteredSourceChangesKeepTheWalkTotalAndOriginalConfidence() {
        val f = Fixture(COUNTER)
        assertTrue(f.tracker.onStepCounterTotal(100f, SECOND, SECOND + MS))
        assertTrue(f.tracker.onStepCounterTotal(103f, 2 * SECOND, 2 * SECOND + MS))
        f.restart(DETECTOR)
        assertTrue(f.tracker.onStepDetected(2_500 * MS, 2_500 * MS + MS))
        f.restart(ACCELEROMETER)
        prepareTwoPeaks(f)
        f.acceleration(3_700, 9f)
        assertEquals(listOf(0, 3, 4, 5, 6), f.counts)
        assertEquals(listOf(StepEventConfidence.MEDIUM, StepEventConfidence.HIGH, StepEventConfidence.LOW, StepEventConfidence.LOW),
            f.events.map { it.confidence })
    }

    @Test
    fun fractionalCounterValuesKeepTheirRawEvidenceAndIntegerDelta() {
        val f = Fixture(COUNTER)
        assertTrue(f.tracker.onStepCounterTotal(100.25f, SECOND, SECOND + MS))
        assertTrue(f.tracker.onStepCounterTotal(103.75f, 2 * SECOND, 2 * SECOND + MS))
        assertEquals(listOf(0, 3), f.counts)
        assertEquals(3, f.events.single().deltaSteps)
        assertEquals(100.25f, f.events.single().counterObservation!!.baseline.cumulativeSteps, 0f)
        assertEquals(103.75f, f.events.single().counterObservation!!.current.cumulativeSteps, 0f)
    }

    @Test
    fun counterOverflowStillRejectsBeforeHealthClockAndNormalRecovery() {
        val f = Fixture(COUNTER)
        assertTrue(f.tracker.onStepCounterTotal(10f, SECOND, SECOND + MS))
        val health = f.tracker.operationalHealth
        val evidence = f.tracker.latestCounterObservation
        assertFalse(f.tracker.onStepCounterTotal(3.0e9f, 2 * SECOND, 2 * SECOND + MS))
        assertSame(health, f.tracker.operationalHealth)
        assertSame(evidence, f.tracker.latestCounterObservation)
        assertEquals(listOf(0), f.counts)
        assertTrue(f.events.isEmpty())
        assertTrue(f.tracker.onStepCounterTotal(12f, 2 * SECOND, 2 * SECOND + MS))
        assertEquals(listOf(0, 2), f.counts)
        assertEquals(2, f.events.single().deltaSteps)
    }

    @Test
    fun preBaselineRecountAndLateDetectorRestorationKeepTheirExistingMeaning() {
        val f = Fixture(BOTH)
        assertTrue(f.tracker.onStepDetected(500 * MS, 600 * MS))
        assertTrue(f.tracker.onStepCounterTotal(100f, SECOND, SECOND + MS))
        assertTrue(f.tracker.onStepCounterTotal(101f, 2 * SECOND, 2 * SECOND + MS))
        assertEquals(1, f.counts.last())
        assertTrue(f.tracker.onStepDetected(1_500 * MS, 2 * SECOND + 2 * MS))
        assertEquals(2, f.counts.last())
        assertEquals(listOf(1, 1), f.events.map { it.deltaSteps })
        assertTrue(f.events.all { it.source == StepEventSource.STEP_DETECTOR })
    }

    private fun fullCounterWalk(baseline: Float = 1f): Fixture = Fixture(COUNTER).also {
        assertTrue(it.tracker.onStepCounterTotal(baseline, SECOND, SECOND + MS))
        assertTrue(it.tracker.onStepCounterTotal(LARGE_COUNTER, 2 * SECOND, 2 * SECOND + MS))
        assertEquals((LARGE_COUNTER.toDouble() - baseline.toDouble()).toInt(), it.counts.last())
    }

    private fun prepareTwoPeaks(f: Fixture) {
        f.acceleration(3_000, 9f)
        f.acceleration(3_100, 13f)
        f.acceleration(3_200, 9f)
        f.acceleration(3_600, 13f)
    }

    private class Fixture(sensors: Set<Int>) {
        val registration = Registration(sensors)
        val counts = mutableListOf<Int>()
        val events = mutableListOf<StepEvent>()
        val tracker = AndroidStepTracker(registration, counts::add, onStepEvent = events::add)
            .also { assertTrue(it.start()) }

        fun restart(sensors: Set<Int>) {
            tracker.stop()
            registration.successfulSensors = sensors
            assertTrue(tracker.start())
            assertEquals(sensors, registration.registered)
        }

        fun acceleration(atMs: Long, z: Float) = tracker.onAccelerometerSample(0f, 0f, z, atMs * MS, atMs * MS + MS)
    }

    private class Registration(var successfulSensors: Set<Int>) : StepSensorRegistration {
        val registered = mutableSetOf<Int>()
        override fun hasSensor(type: Int) = type in ALL
        override fun register(listener: SensorEventListener, type: Int): Boolean =
            (type in successfulSensors).also { if (it) registered += type }
        override fun unregister(listener: SensorEventListener?) { registered.clear() }
    }

    private companion object {
        const val MS = 1_000_000L
        const val SECOND = 1_000_000_000L
        const val LARGE_COUNTER = 2147483648f
        val COUNTER = setOf(Sensor.TYPE_STEP_COUNTER)
        val DETECTOR = setOf(Sensor.TYPE_STEP_DETECTOR)
        val BOTH = COUNTER + DETECTOR
        val ACCELEROMETER = setOf(Sensor.TYPE_ACCELEROMETER)
        val ALL = BOTH + ACCELEROMETER
    }
}

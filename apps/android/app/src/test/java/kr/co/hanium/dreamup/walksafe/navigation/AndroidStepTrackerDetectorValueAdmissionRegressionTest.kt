package kr.co.hanium.dreamup.walksafe.navigation

import android.hardware.Sensor
import android.hardware.SensorEventListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADD-MOTION-032/035: the production Detector value gate precedes every state mutation. */
class AndroidStepTrackerDetectorValueAdmissionRegressionTest {
    @Test
    fun positiveInfinityCannotCreateAHighConfidenceStepOrConsumeReceiptBeforeTheCounterBaseline() {
        val fixture = Fixture(withCounter = true)
        fixture.assertRejectedWithoutMutation {
            fixture.tracker.onStepDetected(SECOND, 2 * SECOND, Float.POSITIVE_INFINITY)
        }
        assertEquals(StepCounterBaselineState.NOT_ESTABLISHED, fixture.tracker.counterBaselineState)
        // A rejected value's later receipt must not fence an otherwise valid Counter callback.
        assertTrue(fixture.tracker.onStepCounterTotal(100f, 1_500 * MS, 1_900 * MS))
        assertEquals(listOf(0), fixture.counts)
        assertTrue(fixture.events.isEmpty())
        assertEquals(StepCounterSample(100f, 1_500 * MS, 1_900 * MS),
            fixture.tracker.latestCounterObservation?.baseline)
    }

    @Test
    fun aCorrectedFiniteSignalAtTheSameMeasurementRemainsAdmissibleAfterInfinity() {
        val fixture = Fixture(withCounter = false)
        // The independent retry assertion exposes timestamp pollution even before the return guard is fixed.
        fixture.tracker.onStepDetected(SECOND, 2 * SECOND, Float.POSITIVE_INFINITY)
        assertTrue("The rejected raw value must not consume this original Detector measurement",
            fixture.tracker.onStepDetected(SECOND, 2_100 * MS, 1f))
        assertEquals(listOf(1), fixture.counts)
        val event = fixture.events.single()
        assertEquals(StepEventSource.STEP_DETECTOR, event.source)
        assertEquals(StepEventConfidence.HIGH, event.confidence)
        assertEquals(SECOND, event.measurementElapsedRealtimeNanos)
        assertEquals(2_100 * MS, event.receivedAtElapsedRealtimeNanos)
        assertTrue(event.isConsistentWith(1, 1_000L, 2_100L,
            StepEventSource.STEP_DETECTOR, StepEventConfidence.HIGH))
    }

    @Test
    fun theExistingNonPositiveGateKeepsNaNZeroAndNegativeValuesOutWithoutConsumingClocks() {
        for (value in listOf(Float.NaN, Float.NEGATIVE_INFINITY, 0f, -0f, -1f)) {
            val fixture = Fixture(withCounter = true)
            assertTrue(fixture.tracker.onStepCounterTotal(100f, 500 * MS, 600 * MS))
            fixture.assertRejectedWithoutMutation {
                fixture.tracker.onStepDetected(SECOND, 20 * SECOND, value)
            }
            assertTrue(fixture.tracker.onStepDetected(SECOND, 1_100 * MS, 1f))
            assertEquals(1_100 * MS, fixture.events.single().receivedAtElapsedRealtimeNanos)
        }
    }

    @Test
    fun anyPreviouslyAllowedPositiveFiniteValueStillMeansOneSignalAndLegacyCallsStillWork() {
        for (value in listOf(1f, 0.25f, 2f, Float.MIN_VALUE, Float.MAX_VALUE)) {
            val fixture = Fixture(withCounter = false)
            assertTrue(fixture.tracker.onStepDetected(SECOND, 1_100 * MS, value))
            assertEquals(listOf(1), fixture.counts)
            assertEquals(1, fixture.events.single().deltaSteps)
            fixture.assertRejectedWithoutMutation {
                fixture.tracker.onStepDetected(SECOND, 1_200 * MS, value)
            }
            assertTrue(fixture.tracker.onStepDetected(2 * SECOND, 2_100 * MS))
            assertEquals(listOf(1, 2), fixture.counts)
            assertEquals(listOf(SECOND, 2 * SECOND), fixture.events.map { it.measurementElapsedRealtimeNanos })
            assertTrue(fixture.events.all { it.source == StepEventSource.STEP_DETECTOR && it.confidence == StepEventConfidence.HIGH })
        }
    }

    @Test
    fun positiveFiniteSignalsKeepCounterBaselineStallCoverageAndRecoverySemantics() {
        val fixture = Fixture(withCounter = true)
        assertTrue(fixture.tracker.onStepCounterTotal(100f, 0L))
        assertTrue(fixture.tracker.onStepDetected(SECOND, detectorValue = 0.25f))
        assertTrue(fixture.tracker.onStepCounterTotal(105f, 12 * SECOND))
        assertTrue(fixture.tracker.onStepDetected(11_900 * MS, detectorValue = 2f))
        assertTrue(fixture.tracker.onStepDetected(12_200 * MS, detectorValue = Float.MAX_VALUE))
        assertTrue(fixture.tracker.onStepCounterTotal(106f, 12_200 * MS))
        assertEquals(listOf(1, 4, 1), fixture.events.map { it.deltaSteps })
        assertEquals(listOf(StepEventSource.STEP_DETECTOR, StepEventSource.STEP_COUNTER, StepEventSource.STEP_DETECTOR),
            fixture.events.map { it.source })
        assertEquals(6, fixture.counts.last())
        assertEquals(6, fixture.events.sumOf { it.deltaSteps })
    }

    @Test
    fun stopStartAndNewWalkStillResetMeasurementIdentityAndPreserveOldRawPackets() {
        val fixture = Fixture(withCounter = false)
        assertTrue(fixture.tracker.onStepDetected(SECOND, 2 * SECOND, 1f))
        val oldPacket = fixture.events.single()
        fixture.tracker.stop()
        assertTrue(fixture.tracker.start())
        assertTrue(fixture.tracker.onStepDetected(500 * MS, 600 * MS, 0.25f))
        assertEquals(listOf(1, 3), fixture.events.map { it.producerRegistrationGeneration })
        assertEquals(listOf(1, 2), fixture.counts)
        fixture.tracker.resetForNewWalk()
        assertTrue(fixture.tracker.start())
        assertTrue(fixture.tracker.onStepDetected(100 * MS, 200 * MS))
        assertEquals(listOf(1, 2, 0, 1), fixture.counts)
        assertEquals(5, fixture.events.last().producerRegistrationGeneration)
        assertEquals(SECOND, oldPacket.measurementElapsedRealtimeNanos)
        assertEquals(2 * SECOND, oldPacket.receivedAtElapsedRealtimeNanos)
        assertEquals(1, oldPacket.producerRegistrationGeneration)
    }

    private class Fixture(withCounter: Boolean) {
        val counts = mutableListOf<Int>()
        val events = mutableListOf<StepEvent>()
        val tracker = AndroidStepTracker(Registration(withCounter), counts::add, onStepEvent = events::add)
            .also { assertTrue(it.start()) }

        fun assertRejectedWithoutMutation(input: () -> Boolean) {
            val beforeCounts = counts.toList()
            val beforeEvents = events.toList()
            val beforeHealth = tracker.operationalHealth
            val beforeHistory = tracker.latestCounterObservation
            val beforeBaseline = tracker.counterBaselineState
            assertFalse("Invalid Detector values must be rejected before count, history, health or clocks change", input())
            assertEquals(beforeCounts, counts)
            assertEquals(beforeEvents, events)
            assertSame(beforeHealth, tracker.operationalHealth)
            assertSame(beforeHistory, tracker.latestCounterObservation)
            assertEquals(beforeBaseline, tracker.counterBaselineState)
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

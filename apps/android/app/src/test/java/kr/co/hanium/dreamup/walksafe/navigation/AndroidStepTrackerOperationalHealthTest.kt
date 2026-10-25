package kr.co.hanium.dreamup.walksafe.navigation

import android.hardware.Sensor
import android.hardware.SensorEventListener
import kr.co.hanium.dreamup.walksafe.navigation.positioning.AcceptedSensorSample
import kr.co.hanium.dreamup.walksafe.navigation.positioning.SensorRegistrationOutcome
import kr.co.hanium.dreamup.walksafe.navigation.positioning.SensorSourceHealth
import kr.co.hanium.dreamup.walksafe.navigation.positioning.SensorTrackerLifecycle
import kr.co.hanium.dreamup.walksafe.navigation.positioning.SensorTrackerStoppedReason
import kr.co.hanium.dreamup.walksafe.navigation.positioning.TrackedSensorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidStepTrackerOperationalHealthTest {
    @Test
    fun registrationSuccessWithoutSamplesDoesNotInventMeasurementOrFallbackRegistration() {
        val registration = Registration(ALL, rejected = setOf(Sensor.TYPE_STEP_DETECTOR))
        val tracker = AndroidStepTracker(registration, onStepCountChanged = {})
        val idle = tracker.operationalHealth
        assertEquals(SensorTrackerLifecycle.IDLE, idle.lifecycle)
        assertTrue(idle.sources.all { it.available == null && it.lastAcceptedSample == null })
        assertTrue(tracker.start())
        val active = tracker.operationalHealth
        assertEquals(SensorTrackerLifecycle.ACTIVE, active.lifecycle)
        assertFalse(active.cleanupPending)
        assertTrue(active.sources.all { it.lastAcceptedSample == null })
        assertSource(tracker, TrackedSensorSource.STEP_COUNTER, true, SensorRegistrationOutcome.REGISTERED, true)
        assertSource(tracker, TrackedSensorSource.STEP_DETECTOR, true, SensorRegistrationOutcome.RETURNED_FALSE, false)
        assertSource(tracker, TrackedSensorSource.ACCELEROMETER, null, SensorRegistrationOutcome.NOT_ATTEMPTED, false)
        assertEquals(listOf(Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR), registration.attempts)
        assertTrue(idle.sources.all { it.available == null })
    }

    @Test
    fun unavailableAndRejectedSourcesHaveDifferentAttemptFacts() {
        val unavailable = AndroidStepTracker(Registration(emptySet()), onStepCountChanged = {})
        assertFalse(unavailable.start())
        assertEquals(SensorTrackerStoppedReason.FAILED_START, unavailable.operationalHealth.stoppedReason)
        assertTrue(unavailable.operationalHealth.sources.all {
            it.available == false && it.lastRegistrationOutcome == SensorRegistrationOutcome.UNAVAILABLE
        })
        val rejected = AndroidStepTracker(Registration(ALL, rejected = ALL), onStepCountChanged = {})
        assertFalse(rejected.start())
        assertTrue(rejected.operationalHealth.sources.all {
            it.available == true && it.lastRegistrationOutcome == SensorRegistrationOutcome.RETURNED_FALSE &&
                !it.acceptingSamples && it.lastAcceptedSample == null
        })
        assertFalse(rejected.operationalHealth.cleanupPending)
    }

    @Test
    fun acceptedBaselineAndUnchangedCounterCarryActualInputTimesWithoutProducingSteps() {
        val events = mutableListOf<StepEvent>()
        val tracker = AndroidStepTracker(Registration(setOf(Sensor.TYPE_STEP_COUNTER)), {}, onStepEvent = events::add)
        assertTrue(tracker.start())
        assertTrue(tracker.onStepCounterTotal(100.25f, 1_000_123L, 1_100_456L))
        val baseline = tracker.operationalHealth
        assertSample(tracker, TrackedSensorSource.STEP_COUNTER, 1_000_123L, 1_100_456L)
        assertTrue(tracker.onStepCounterTotal(100.25f, 2_000_789L))
        assertSample(tracker, TrackedSensorSource.STEP_COUNTER, 2_000_789L, null)
        assertTrue(events.isEmpty())
        assertEquals(1_000_123L, baseline.source(TrackedSensorSource.STEP_COUNTER)!!.lastAcceptedSample!!.measurementElapsedRealtimeNanos)
        val unchanged = tracker.operationalHealth
        assertFalse(tracker.onStepCounterTotal(101f, 2_000_789L, 3_000_000L))
        assertFalse(tracker.onStepCounterTotal(Float.NaN, 4_000_000L, 4_000_001L))
        assertFalse(tracker.onStepCounterTotal(101f, 5_000_000L, 4_999_999L))
        assertSame(unchanged, tracker.operationalHealth)
    }

    @Test
    fun detectorAlreadyCoveredByCounterIsStillAnAcceptedSensorSample() {
        val events = mutableListOf<StepEvent>()
        val tracker = AndroidStepTracker(Registration(setOf(Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR)),
            {}, onStepEvent = events::add)
        assertTrue(tracker.start())
        assertTrue(tracker.onStepCounterTotal(100f, 0L, 1L))
        assertTrue(tracker.onStepCounterTotal(103f, 15_000_000_000L, 15_100_000_000L))
        assertEquals(1, events.size)
        assertTrue(tracker.onStepDetected(14_900_000_000L, 15_200_000_000L))
        assertEquals(1, events.size)
        assertSample(tracker, TrackedSensorSource.STEP_DETECTOR, 14_900_000_000L, 15_200_000_000L)
    }

    @Test
    fun peaklessAccelerationUpdatesInputHealthButRejectedInputAndQueriesDoNot() {
        val counts = mutableListOf<Int>()
        val events = mutableListOf<StepEvent>()
        val tracker = AndroidStepTracker(Registration(setOf(Sensor.TYPE_ACCELEROMETER)), counts::add, onStepEvent = events::add)
        assertTrue(tracker.start())
        tracker.onAccelerometerSample(0f, 0f, 9.8f, 1_000_123L, 2_000_456L)
        assertSample(tracker, TrackedSensorSource.ACCELEROMETER, 1_000_123L, 2_000_456L)
        val accepted = tracker.operationalHealth
        tracker.onAccelerometerSample(0f, 0f, 9.8f, 1_000_123L, 3_000_000L)
        tracker.onAccelerometerSample(0f, 0f, 9.8f, 1_000_122L, 3_000_000L)
        tracker.onAccelerometerSample(Float.NaN, 0f, 9.8f, 4_000_000L, 5_000_000L)
        tracker.onAccelerometerSample(0f, 0f, 9.8f, 6_000_000L, 5_999_999L)
        assertSame(accepted, tracker.operationalHealth)
        assertSame(accepted, tracker.operationalHealth)
        tracker.onAccelerometerSample(0f, 0f, 9.8f, 6_000_000L)
        assertSample(tracker, TrackedSensorSource.ACCELEROMETER, 6_000_000L, null)
        assertTrue(events.isEmpty())
        assertTrue(counts.isEmpty())
    }

    @Test
    fun publicationPrecedesReentrantStopAndDoesNotRestoreTheRetiredSample() {
        lateinit var tracker: AndroidStepTracker
        var observed: AcceptedSensorSample? = null
        tracker = AndroidStepTracker(Registration(setOf(Sensor.TYPE_STEP_COUNTER)), onStepCountChanged = {
            observed = tracker.operationalHealth.source(TrackedSensorSource.STEP_COUNTER)!!.lastAcceptedSample
            tracker.stop()
        })
        assertTrue(tracker.start())
        assertTrue(tracker.onStepCounterTotal(100f, 123_456L, 234_567L))
        assertEquals(123_456L, observed!!.measurementElapsedRealtimeNanos)
        assertEquals(SensorTrackerStoppedReason.EXPLICIT_STOP, tracker.operationalHealth.stoppedReason)
        assertTrue(tracker.operationalHealth.sources.all { !it.acceptingSamples && it.lastAcceptedSample == null })
    }

    @Test
    fun failedCleanupPreservesFactsAndRestartWaitsForANewGenerationSample() {
        val registration = Registration(setOf(Sensor.TYPE_STEP_COUNTER), unregisterFailures = 1)
        val tracker = AndroidStepTracker(registration, onStepCountChanged = {})
        assertTrue(tracker.start())
        assertTrue(tracker.onStepCounterTotal(100f, 100L, 200L))
        val old = tracker.operationalHealth
        assertTrue(runCatching { tracker.stop() }.isFailure)
        val stopped = tracker.operationalHealth
        assertEquals(SensorTrackerLifecycle.STOPPED, stopped.lifecycle)
        assertEquals(SensorTrackerStoppedReason.EXPLICIT_STOP, stopped.stoppedReason)
        assertTrue(stopped.cleanupPending)
        assertSource(tracker, TrackedSensorSource.STEP_COUNTER, true, SensorRegistrationOutcome.REGISTERED, false)
        assertNull(stopped.source(TrackedSensorSource.STEP_COUNTER)!!.lastAcceptedSample)
        assertTrue(tracker.start())
        val recovering = tracker.operationalHealth
        assertFalse(recovering.cleanupPending)
        assertTrue(recovering.registrationGeneration > old.registrationGeneration)
        assertTrue(recovering.sources.all { it.lastAcceptedSample == null })
        assertTrue(tracker.onStepCounterTotal(101f, 300L, 400L))
        assertSample(tracker, TrackedSensorSource.STEP_COUNTER, 300L, 400L)
        val current = tracker.operationalHealth
        assertTrue(tracker.start())
        assertSame(current, tracker.operationalHealth)
        assertEquals(100L, old.source(TrackedSensorSource.STEP_COUNTER)!!.lastAcceptedSample!!.measurementElapsedRealtimeNanos)
        assertEquals(1, registration.listeners.size)
    }

    @Test
    fun registrationExceptionsRetainTheirCategoryWithoutInferringCurrentPermission() {
        for (failure in listOf(SecurityException("denied"), IllegalStateException("failed"))) {
            val registration = Registration(ALL, failure = failure, unregisterFailures = 2)
            val tracker = AndroidStepTracker(registration, onStepCountChanged = {})
            val result = runCatching { tracker.start() }
            if (failure is SecurityException) assertEquals(false, result.getOrThrow())
            else assertSame(failure, result.exceptionOrNull())
            val failed = tracker.operationalHealth
            assertEquals(SensorTrackerLifecycle.STOPPED, failed.lifecycle)
            assertEquals(SensorTrackerStoppedReason.FAILED_START, failed.stoppedReason)
            assertTrue(failed.cleanupPending)
            assertSource(tracker, TrackedSensorSource.STEP_COUNTER, true, SensorRegistrationOutcome.REGISTERED, false)
            assertSource(tracker, TrackedSensorSource.STEP_DETECTOR, true,
                if (failure is SecurityException) SensorRegistrationOutcome.SECURITY_EXCEPTION else SensorRegistrationOutcome.RUNTIME_EXCEPTION,
                false)
            assertSource(tracker, TrackedSensorSource.ACCELEROMETER, null, SensorRegistrationOutcome.NOT_ATTEMPTED, false)
            val attempts = registration.attempts.toList()
            assertFalse(tracker.start())
            assertEquals(attempts, registration.attempts)
            assertEquals(SensorTrackerStoppedReason.FAILED_START, tracker.operationalHealth.stoppedReason)
            assertTrue(tracker.operationalHealth.cleanupPending)
            assertTrue(tracker.start())
            assertTrue(tracker.operationalHealth.sources.all { it.lastAcceptedSample == null })
        }
    }

    @Test
    fun unstartedLegacyInputDoesNotClaimAnActiveSensorAndSnapshotsCannotBeMutated() {
        val tracker = AndroidStepTracker(Registration(ALL), onStepCountChanged = {})
        assertTrue(tracker.onStepCounterTotal(100f, 100L, 200L))
        assertEquals(SensorTrackerLifecycle.IDLE, tracker.operationalHealth.lifecycle)
        assertTrue(tracker.operationalHealth.sources.all { it.lastAcceptedSample == null })
        val health = tracker.operationalHealth
        assertTrue(runCatching { (health.sources as MutableList<SensorSourceHealth>).clear() }
            .exceptionOrNull() is UnsupportedOperationException)
        assertEquals(3, tracker.operationalHealth.sources.size)
    }

    @Test
    fun failedInitialAvailabilityProbeRetiresTheAttemptAndRetainsKnownAvailability() {
        val failure = IllegalStateException("detector probe failed")
        val registration = Registration(setOf(Sensor.TYPE_STEP_DETECTOR),
            probeFailure = Sensor.TYPE_STEP_DETECTOR to failure)
        val tracker = AndroidStepTracker(registration, onStepCountChanged = {})
        assertSame(failure, runCatching { tracker.start() }.exceptionOrNull())
        assertEquals(SensorTrackerLifecycle.STOPPED, tracker.operationalHealth.lifecycle)
        assertEquals(SensorTrackerStoppedReason.FAILED_START, tracker.operationalHealth.stoppedReason)
        assertFalse(tracker.operationalHealth.cleanupPending)
        assertSource(tracker, TrackedSensorSource.STEP_COUNTER, false, SensorRegistrationOutcome.UNAVAILABLE, false)
        val detector = tracker.operationalHealth.source(TrackedSensorSource.STEP_DETECTOR)!!
        assertNull(detector.available)
        assertEquals(SensorRegistrationOutcome.NOT_ATTEMPTED, detector.lastRegistrationOutcome)
        assertTrue(registration.attempts.isEmpty())
        assertTrue(registration.listeners.isEmpty())
    }

    @Test
    fun deniedAvailabilityProbeAfterCounterRegistrationKeepsTheExistingFalseAndCleanupContract() {
        val failure = SecurityException("detector probe denied")
        val registration = Registration(ALL, probeFailure = Sensor.TYPE_STEP_DETECTOR to failure)
        val tracker = AndroidStepTracker(registration, onStepCountChanged = {})
        val attempt = runCatching { tracker.start() }
        assertTrue("The existing post-Counter SecurityException contract returns false", attempt.isSuccess)
        assertEquals(false, attempt.getOrThrow())
        assertEquals(SensorTrackerStoppedReason.FAILED_START, tracker.operationalHealth.stoppedReason)
        assertSource(tracker, TrackedSensorSource.STEP_COUNTER, true, SensorRegistrationOutcome.REGISTERED, false)
        assertEquals(listOf(Sensor.TYPE_STEP_COUNTER), registration.attempts)
        assertEquals(1, registration.unregisterCalls)
        assertTrue(registration.listeners.isEmpty())
    }

    @Test
    fun anUnusedFallbackAvailabilityProbeCannotAbortRegisteredHardwareStepSources() {
        val registration = Registration(ALL,
            probeFailure = Sensor.TYPE_ACCELEROMETER to IllegalStateException("unused fallback probe failed"))
        val tracker = AndroidStepTracker(registration, onStepCountChanged = {})
        val attempt = runCatching { tracker.start() }
        assertTrue("The hardware path does not need the unused fallback probe", attempt.isSuccess)
        assertEquals(true, attempt.getOrThrow())
        assertEquals(listOf(Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR), registration.attempts)
        assertFalse(Sensor.TYPE_ACCELEROMETER in registration.probeAttempts)
        val fallback = tracker.operationalHealth.source(TrackedSensorSource.ACCELEROMETER)!!
        assertNull(fallback.available)
        assertEquals(SensorRegistrationOutcome.NOT_ATTEMPTED, fallback.lastRegistrationOutcome)
        assertEquals(0, registration.unregisterCalls)
    }

    @Test
    fun logicalRetirementIsVisibleInsideCleanupBeforeItsAcknowledgement() {
        for (failedStart in listOf(false, true)) {
            lateinit var tracker: AndroidStepTracker
            var cleanupObserved = false
            val registration = Registration(ALL,
                failure = if (failedStart) SecurityException("denied") else null,
                unregisterObserver = {
                    val health = tracker.operationalHealth
                    assertEquals(SensorTrackerLifecycle.STOPPED, health.lifecycle)
                    assertEquals(if (failedStart) SensorTrackerStoppedReason.FAILED_START else SensorTrackerStoppedReason.EXPLICIT_STOP,
                        health.stoppedReason)
                    assertTrue(health.cleanupPending)
                    assertTrue(health.sources.all { !it.acceptingSamples && it.lastAcceptedSample == null })
                    cleanupObserved = true
                })
            tracker = AndroidStepTracker(registration, onStepCountChanged = {})
            if (failedStart) assertFalse(tracker.start()) else {
                assertTrue(tracker.start())
                assertTrue(tracker.onStepCounterTotal(100f, 100L, 200L))
                tracker.stop()
            }
            assertTrue(cleanupObserved)
            assertFalse(tracker.operationalHealth.cleanupPending)
        }
    }

    private fun assertSource(tracker: AndroidStepTracker, source: TrackedSensorSource, available: Boolean?,
        outcome: SensorRegistrationOutcome, accepting: Boolean) {
        val row = tracker.operationalHealth.source(source)!!
        assertEquals(available, row.available)
        assertEquals(outcome, row.lastRegistrationOutcome)
        assertEquals(accepting, row.acceptingSamples)
    }

    private fun assertSample(tracker: AndroidStepTracker, source: TrackedSensorSource, measurement: Long, receipt: Long?) {
        val sample = tracker.operationalHealth.source(source)!!.lastAcceptedSample!!
        assertEquals(tracker.operationalHealth.registrationGeneration, sample.producerRegistrationGeneration)
        assertEquals(measurement, sample.measurementElapsedRealtimeNanos)
        assertEquals(receipt, sample.receivedAtElapsedRealtimeNanos)
    }

    private class Registration(
        private val available: Set<Int>,
        private val rejected: Set<Int> = emptySet(),
        private var failure: RuntimeException? = null,
        private var unregisterFailures: Int = 0,
        private var probeFailure: Pair<Int, RuntimeException>? = null,
        private val unregisterObserver: (() -> Unit)? = null,
    ) : StepSensorRegistration {
        val attempts = mutableListOf<Int>()
        val probeAttempts = mutableListOf<Int>()
        val listeners = mutableSetOf<SensorEventListener>()
        var unregisterCalls = 0
        override fun hasSensor(type: Int): Boolean {
            probeAttempts += type
            probeFailure?.takeIf { it.first == type }?.let { probeFailure = null; throw it.second }
            return type in available
        }
        override fun register(listener: SensorEventListener, type: Int): Boolean {
            attempts += type
            if (type == Sensor.TYPE_STEP_DETECTOR) failure?.let { failure = null; throw it }
            if (type in rejected) return false
            listeners += listener
            return true
        }
        override fun unregister(listener: SensorEventListener?) {
            unregisterCalls += 1
            unregisterObserver?.invoke()
            if (unregisterFailures > 0) { unregisterFailures -= 1; throw IllegalStateException("cleanup failed") }
            listeners.remove(listener)
        }
    }

    private companion object {
        val ALL = setOf(Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR, Sensor.TYPE_ACCELEROMETER)
    }
}

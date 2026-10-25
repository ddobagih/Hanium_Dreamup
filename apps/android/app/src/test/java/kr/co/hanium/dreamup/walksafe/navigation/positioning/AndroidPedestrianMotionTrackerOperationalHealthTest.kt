package kr.co.hanium.dreamup.walksafe.navigation.positioning

import android.hardware.Sensor
import android.hardware.SensorEventListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPedestrianMotionTrackerOperationalHealthTest {
    @Test
    fun successfulAndRejectedMotionSourcesRemainSeparateFromActualMeasurement() {
        val tracker = AndroidPedestrianMotionTracker(Registration(BOTH, rejected = setOf(Sensor.TYPE_GYROSCOPE)),
            onDecision = {})
        val idle = tracker.operationalHealth
        assertEquals(SensorTrackerLifecycle.IDLE, idle.lifecycle)
        assertTrue(idle.sources.all { it.available == null && it.lastAcceptedSample == null })
        assertTrue(tracker.start())
        assertEquals(SensorTrackerLifecycle.ACTIVE, tracker.operationalHealth.lifecycle)
        assertFalse(tracker.operationalHealth.cleanupPending)
        assertSource(tracker, TrackedSensorSource.ACCELEROMETER, true, SensorRegistrationOutcome.REGISTERED, true)
        assertSource(tracker, TrackedSensorSource.GYROSCOPE, true, SensorRegistrationOutcome.RETURNED_FALSE, false)
        assertTrue(tracker.operationalHealth.sources.all { it.lastAcceptedSample == null })
        assertFalse(tracker.onSensorSample(Sensor.TYPE_GYROSCOPE, VALUES, 100L, 200L))
        assertTrue(idle.sources.all { it.available == null })
    }

    @Test
    fun absentSourcesAndAllRejectedRegistrationsReportDifferentFailures() {
        val absent = AndroidPedestrianMotionTracker(Registration(emptySet()), onDecision = {})
        assertFalse(absent.start())
        assertEquals(SensorTrackerStoppedReason.FAILED_START, absent.operationalHealth.stoppedReason)
        assertTrue(absent.operationalHealth.sources.all {
            it.available == false && it.lastRegistrationOutcome == SensorRegistrationOutcome.UNAVAILABLE && !it.acceptingSamples
        })
        val rejected = AndroidPedestrianMotionTracker(Registration(BOTH, rejected = BOTH), onDecision = {})
        assertFalse(rejected.start())
        assertEquals(SensorTrackerStoppedReason.FAILED_START, rejected.operationalHealth.stoppedReason)
        assertFalse(rejected.operationalHealth.cleanupPending)
        assertTrue(rejected.operationalHealth.sources.all {
            it.available == true && it.lastRegistrationOutcome == SensorRegistrationOutcome.RETURNED_FALSE && !it.acceptingSamples
        })
    }

    @Test
    fun crossSourceLateInputKeepsOriginalTimesEvenWhenItCannotEmitAnotherDecision() {
        var decisions = 0
        val tracker = AndroidPedestrianMotionTracker(Registration(BOTH), onDecision = { decisions += 1 })
        assertTrue(tracker.start())
        assertTrue(tracker.onSensorSample(Sensor.TYPE_ACCELEROMETER, VALUES, 1_000_987_654L, 1_100_123_456L))
        assertEquals(1, decisions)
        val acceleration = tracker.operationalHealth
        assertTrue(tracker.onSensorSample(Sensor.TYPE_GYROSCOPE, floatArrayOf(0f, 0f, 0f), 900_123_456L, 1_200_987_654L))
        assertEquals(1, decisions)
        assertSample(tracker, TrackedSensorSource.ACCELEROMETER, 1_000_987_654L, 1_100_123_456L)
        assertSample(tracker, TrackedSensorSource.GYROSCOPE, 900_123_456L, 1_200_987_654L)
        assertNull(acceleration.source(TrackedSensorSource.GYROSCOPE)!!.lastAcceptedSample)
        val accepted = tracker.operationalHealth
        assertFalse(tracker.onSensorSample(Sensor.TYPE_GYROSCOPE, VALUES, 900_123_456L, 1_300_000_000L))
        assertFalse(tracker.onSensorSample(Sensor.TYPE_GYROSCOPE, VALUES, 900_123_455L, 1_300_000_000L))
        assertFalse(tracker.onSensorSample(Sensor.TYPE_GYROSCOPE, floatArrayOf(Float.NaN, 0f, 0f), 2_000_000_000L, 2_100_000_000L))
        assertFalse(tracker.onSensorSample(Sensor.TYPE_GYROSCOPE, floatArrayOf(1f), 2_000_000_000L, 2_100_000_000L))
        assertFalse(tracker.onSensorSample(Sensor.TYPE_GYROSCOPE, VALUES, 2_000_000_000L, 1_999_999_999L))
        assertSame(accepted, tracker.operationalHealth)
        assertTrue(tracker.onSensorSample(Sensor.TYPE_GYROSCOPE, VALUES, 2_000_000_000L))
        assertSample(tracker, TrackedSensorSource.GYROSCOPE, 2_000_000_000L, null)
        assertEquals(2, decisions)
    }

    @Test
    fun samplePublicationPrecedesReentrantStopAndCannotRestoreOldHealth() {
        lateinit var tracker: AndroidPedestrianMotionTracker
        var callbackSample: AcceptedSensorSample? = null
        tracker = AndroidPedestrianMotionTracker(Registration(BOTH), onDecision = {
            callbackSample = tracker.operationalHealth.source(TrackedSensorSource.ACCELEROMETER)!!.lastAcceptedSample
            tracker.stop()
        })
        assertTrue(tracker.start())
        assertTrue(tracker.onSensorSample(Sensor.TYPE_ACCELEROMETER, VALUES, 100_123_456L, 200_234_567L))
        assertEquals(100_123_456L, callbackSample!!.measurementElapsedRealtimeNanos)
        assertEquals(SensorTrackerStoppedReason.EXPLICIT_STOP, tracker.operationalHealth.stoppedReason)
        assertTrue(tracker.operationalHealth.sources.all { !it.acceptingSamples && it.lastAcceptedSample == null })
        assertFalse(tracker.onSensorSample(Sensor.TYPE_ACCELEROMETER, VALUES, 300_000_000L, 400_000_000L))
    }

    @Test
    fun failedRegistrationRetainsAttemptOutcomeAndCleanupTargetWithoutCurrentPermissionInference() {
        for (failure in listOf(SecurityException("denied"), IllegalStateException("failed"))) {
            val registration = Registration(BOTH, failure = failure, unregisterFailures = 2)
            val tracker = AndroidPedestrianMotionTracker(registration, onDecision = {})
            assertSame(failure, runCatching { tracker.start() }.exceptionOrNull())
            assertEquals(SensorTrackerLifecycle.STOPPED, tracker.operationalHealth.lifecycle)
            assertEquals(SensorTrackerStoppedReason.FAILED_START, tracker.operationalHealth.stoppedReason)
            assertTrue(tracker.operationalHealth.cleanupPending)
            assertSource(tracker, TrackedSensorSource.ACCELEROMETER, true, SensorRegistrationOutcome.REGISTERED, false)
            assertSource(tracker, TrackedSensorSource.GYROSCOPE, true,
                if (failure is SecurityException) SensorRegistrationOutcome.SECURITY_EXCEPTION else SensorRegistrationOutcome.RUNTIME_EXCEPTION,
                false)
            val attempts = registration.attempts.toList()
            assertTrue(runCatching { tracker.start() }.isFailure)
            assertEquals(attempts, registration.attempts)
            assertEquals(SensorTrackerStoppedReason.FAILED_START, tracker.operationalHealth.stoppedReason)
            assertTrue(tracker.operationalHealth.cleanupPending)
            assertFalse(tracker.onSensorSample(Sensor.TYPE_ACCELEROMETER, VALUES, 100L, 200L))
            assertTrue(tracker.start())
            assertFalse(tracker.operationalHealth.cleanupPending)
            assertTrue(tracker.operationalHealth.sources.all { it.lastAcceptedSample == null })
            assertEquals(1, registration.listeners.size)
        }
    }

    @Test
    fun stoppedAndRecoveringGenerationCannotReuseOldSamplesAndStartIsIdempotent() {
        val tracker = AndroidPedestrianMotionTracker(Registration(BOTH), onDecision = {})
        assertTrue(tracker.start())
        assertTrue(tracker.onSensorSample(Sensor.TYPE_ACCELEROMETER, VALUES, 100L, 200L))
        val old = tracker.operationalHealth
        tracker.stop()
        assertEquals(SensorTrackerLifecycle.STOPPED, tracker.operationalHealth.lifecycle)
        assertFalse(tracker.operationalHealth.cleanupPending)
        assertTrue(tracker.operationalHealth.sources.all { it.lastAcceptedSample == null && !it.acceptingSamples })
        assertTrue(tracker.start())
        assertTrue(tracker.operationalHealth.registrationGeneration > old.registrationGeneration)
        assertTrue(tracker.operationalHealth.sources.all { it.lastAcceptedSample == null })
        assertTrue(tracker.onSensorSample(Sensor.TYPE_ACCELEROMETER, VALUES, 300L))
        assertSample(tracker, TrackedSensorSource.ACCELEROMETER, 300L, null)
        val current = tracker.operationalHealth
        assertTrue(tracker.start())
        assertSame(current, tracker.operationalHealth)
        assertEquals(100L, old.source(TrackedSensorSource.ACCELEROMETER)!!.lastAcceptedSample!!.measurementElapsedRealtimeNanos)
    }

    @Test
    fun closedCleanupFailureStaysClosedUntilCleanupAcknowledgementAndNeverRegistersAgain() {
        val registration = Registration(BOTH, unregisterFailures = 1)
        val tracker = AndroidPedestrianMotionTracker(registration, onDecision = {})
        assertTrue(tracker.start())
        assertTrue(tracker.onSensorSample(Sensor.TYPE_ACCELEROMETER, VALUES, 100L, 200L))
        val attempts = registration.attempts.toList()
        assertTrue(runCatching { tracker.close() }.isFailure)
        assertEquals(SensorTrackerLifecycle.CLOSED, tracker.operationalHealth.lifecycle)
        assertEquals(SensorTrackerStoppedReason.CLOSED, tracker.operationalHealth.stoppedReason)
        assertTrue(tracker.operationalHealth.cleanupPending)
        assertTrue(tracker.operationalHealth.sources.all { !it.acceptingSamples && it.lastAcceptedSample == null })
        assertFalse(tracker.start())
        assertFalse(tracker.onSensorSample(Sensor.TYPE_ACCELEROMETER, VALUES, 300L, 400L))
        tracker.close()
        tracker.close()
        assertFalse(tracker.operationalHealth.cleanupPending)
        assertEquals(SensorTrackerLifecycle.CLOSED, tracker.operationalHealth.lifecycle)
        assertEquals(SensorTrackerStoppedReason.CLOSED, tracker.operationalHealth.stoppedReason)
        assertEquals(attempts, registration.attempts)
        assertTrue(registration.listeners.isEmpty())
    }

    @Test
    fun failedInitialAvailabilityProbeRetiresTheAttemptAndRetainsKnownAvailability() {
        val failure = IllegalStateException("gyroscope probe failed")
        val registration = Registration(setOf(Sensor.TYPE_GYROSCOPE),
            probeFailure = Sensor.TYPE_GYROSCOPE to failure)
        val tracker = AndroidPedestrianMotionTracker(registration, onDecision = {})
        assertSame(failure, runCatching { tracker.start() }.exceptionOrNull())
        assertEquals(SensorTrackerLifecycle.STOPPED, tracker.operationalHealth.lifecycle)
        assertEquals(SensorTrackerStoppedReason.FAILED_START, tracker.operationalHealth.stoppedReason)
        assertFalse(tracker.operationalHealth.cleanupPending)
        assertSource(tracker, TrackedSensorSource.ACCELEROMETER, false, SensorRegistrationOutcome.UNAVAILABLE, false)
        val gyroscope = tracker.operationalHealth.source(TrackedSensorSource.GYROSCOPE)!!
        assertNull(gyroscope.available)
        assertEquals(SensorRegistrationOutcome.NOT_ATTEMPTED, gyroscope.lastRegistrationOutcome)
        assertTrue(registration.attempts.isEmpty())
    }

    @Test
    fun availabilityProbeFailureAfterAccelerationRegistrationRetainsTheOriginalCleanupBoundary() {
        val failure = SecurityException("gyroscope probe denied")
        val registration = Registration(BOTH, probeFailure = Sensor.TYPE_GYROSCOPE to failure)
        val tracker = AndroidPedestrianMotionTracker(registration, onDecision = {})
        assertSame(failure, runCatching { tracker.start() }.exceptionOrNull())
        assertEquals(SensorTrackerStoppedReason.FAILED_START, tracker.operationalHealth.stoppedReason)
        assertSource(tracker, TrackedSensorSource.ACCELEROMETER, true, SensorRegistrationOutcome.REGISTERED, false)
        assertEquals(listOf(Sensor.TYPE_ACCELEROMETER), registration.attempts)
        assertEquals(1, registration.unregisterCalls)
        assertTrue(registration.listeners.isEmpty())
    }

    @Test
    fun retirementAndClosedReasonAreVisibleInsideCleanupBeforeItsAcknowledgement() {
        for (operation in listOf("stop", "close", "failed_start")) {
            lateinit var tracker: AndroidPedestrianMotionTracker
            var cleanupObserved = false
            val failure = SecurityException("denied")
            val registration = Registration(BOTH,
                failure = if (operation == "failed_start") failure else null,
                unregisterObserver = {
                    val health = tracker.operationalHealth
                    assertEquals(if (operation == "close") SensorTrackerLifecycle.CLOSED else SensorTrackerLifecycle.STOPPED,
                        health.lifecycle)
                    assertEquals(when (operation) {
                        "close" -> SensorTrackerStoppedReason.CLOSED
                        "failed_start" -> SensorTrackerStoppedReason.FAILED_START
                        else -> SensorTrackerStoppedReason.EXPLICIT_STOP
                    }, health.stoppedReason)
                    assertTrue(health.cleanupPending)
                    assertTrue(health.sources.all { !it.acceptingSamples && it.lastAcceptedSample == null })
                    cleanupObserved = true
                })
            tracker = AndroidPedestrianMotionTracker(registration, onDecision = {})
            if (operation == "failed_start") assertSame(failure, runCatching { tracker.start() }.exceptionOrNull()) else {
                assertTrue(tracker.start())
                assertTrue(tracker.onSensorSample(Sensor.TYPE_ACCELEROMETER, VALUES, 100L, 200L))
                if (operation == "close") tracker.close() else tracker.stop()
            }
            assertTrue(cleanupObserved)
            assertFalse(tracker.operationalHealth.cleanupPending)
        }
    }

    private fun assertSource(tracker: AndroidPedestrianMotionTracker, source: TrackedSensorSource, available: Boolean,
        outcome: SensorRegistrationOutcome, accepting: Boolean) {
        val row = tracker.operationalHealth.source(source)!!
        assertEquals(available, row.available)
        assertEquals(outcome, row.lastRegistrationOutcome)
        assertEquals(accepting, row.acceptingSamples)
    }

    private fun assertSample(tracker: AndroidPedestrianMotionTracker, source: TrackedSensorSource, measurement: Long, receipt: Long?) {
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
    ) : MotionSensorRegistration {
        val attempts = mutableListOf<Int>()
        val listeners = mutableSetOf<SensorEventListener>()
        var unregisterCalls = 0
        override fun hasSensor(type: Int): Boolean {
            probeFailure?.takeIf { it.first == type }?.let { probeFailure = null; throw it.second }
            return type in available
        }
        override fun register(listener: SensorEventListener, type: Int): Boolean {
            attempts += type
            if (type == Sensor.TYPE_GYROSCOPE) failure?.let { failure = null; throw it }
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
        val BOTH = setOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE)
        val VALUES = floatArrayOf(0f, 0f, 9.8f)
    }
}

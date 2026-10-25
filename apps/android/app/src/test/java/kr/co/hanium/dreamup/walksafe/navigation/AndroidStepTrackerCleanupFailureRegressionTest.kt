package kr.co.hanium.dreamup.walksafe.navigation

import android.hardware.Sensor
import android.hardware.SensorEventListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidStepTrackerCleanupFailureRegressionTest {
    @Test
    fun failedStopStillRetiresThePreviousCounterObservation() {
        val registration = Registration(setOf(Sensor.TYPE_STEP_COUNTER), unregisterFailures = 1)
        val tracker = tracker(registration)
        assertTrue(tracker.start())
        assertTrue(tracker.onStepCounterTotal(100f, 0L))
        assertTrue(tracker.onStepCounterTotal(103f, 1_000_000_000L))
        assertNotNull(tracker.latestCounterObservation)
        assertTrue(runCatching { tracker.stop() }.isFailure)
        assertNull("A retired registration must not expose its old Counter evidence", tracker.latestCounterObservation)
        tracker.stop()
        assertTrue(registration.listeners.isEmpty())
    }

    @Test
    fun retryCannotReplaceTheStepListenerUntilFailedCleanupIsAcknowledged() {
        val registration = Registration(setOf(Sensor.TYPE_STEP_COUNTER), unregisterFailures = 2)
        val tracker = tracker(registration)
        assertTrue(tracker.start())
        assertTrue(runCatching { tracker.stop() }.isFailure)
        val attempts = registration.attempts.toList()
        assertFalse(tracker.start())
        assertEquals(attempts, registration.attempts)
        assertEquals(1, registration.listeners.size)
        assertTrue(tracker.start())
        assertEquals(1, registration.listeners.size)
        tracker.stop()
        assertTrue(registration.listeners.isEmpty())
    }

    @Test
    fun failedStartCleanupCannotLoseThePartiallyRegisteredStepListener() {
        val registration = Registration(setOf(Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR),
            failDetectorOnce = true, unregisterFailures = 2)
        val tracker = tracker(registration)
        assertFalse(tracker.start())
        assertEquals(1, registration.listeners.size)
        val attempts = registration.attempts.toList()
        assertFalse(tracker.start())
        assertEquals(attempts, registration.attempts)
        assertTrue(tracker.start())
        assertEquals(1, registration.listeners.size)
        tracker.stop()
        assertTrue(registration.listeners.isEmpty())
    }

    @Test
    fun genericRegistrationFailureCleansUpBeforeItsOriginalExceptionLeavesStart() {
        val registration = GenericFailureRegistration()
        var trackingStartedCalls = 0
        val tracker = AndroidStepTracker(registration = registration, onStepCountChanged = {},
            onTrackingStarted = { trackingStartedCalls += 1 })
        assertSame(registration.registrationFailure, runCatching { tracker.start() }.exceptionOrNull())
        assertEquals("The first failed registration must be cleaned before any retry", 1, registration.unregisterCalls)
        assertTrue(registration.listeners.isEmpty())
        assertEquals(StepCounterBaselineState.NOT_REGISTERED, tracker.counterBaselineState)
        assertEquals(0, trackingStartedCalls)
        assertTrue(tracker.start())
        assertEquals(1, registration.listeners.size)
        assertEquals(1, trackingStartedCalls)
        tracker.stop()
        assertTrue(registration.listeners.isEmpty())
    }

    @Test
    fun genericRegistrationFailureRetainsItsCleanupCauseAndRetryTarget() {
        val registration = GenericFailureRegistration(unregisterFailures = 2)
        val tracker = tracker(registration)
        val failure = runCatching { tracker.start() }.exceptionOrNull()
        assertSame(registration.registrationFailure, failure)
        assertEquals("The cleanup failure must not replace or disappear from the original failure",
            listOf(registration.cleanupFailure), requireNotNull(failure).suppressed.toList())
        assertEquals(StepCounterBaselineState.NOT_REGISTERED, tracker.counterBaselineState)
        assertEquals(1, registration.listeners.size)
        val attempts = registration.attempts.toList()
        assertFalse(tracker.start())
        assertEquals(attempts, registration.attempts)
        assertEquals(1, registration.listeners.size)
        assertTrue(tracker.start())
        assertEquals(1, registration.listeners.size)
        tracker.stop()
        assertTrue(registration.listeners.isEmpty())
    }

    private class GenericFailureRegistration(private var unregisterFailures: Int = 0) : StepSensorRegistration {
        val registrationFailure = IllegalStateException("detector registration failed")
        val cleanupFailure = IllegalStateException("step cleanup failed")
        val attempts = mutableListOf<Int>()
        val listeners = mutableSetOf<SensorEventListener>()
        var unregisterCalls = 0
        private var failDetectorOnce = true

        override fun hasSensor(type: Int): Boolean =
            type == Sensor.TYPE_STEP_COUNTER || type == Sensor.TYPE_STEP_DETECTOR

        override fun register(listener: SensorEventListener, type: Int): Boolean {
            attempts += type
            if (type == Sensor.TYPE_STEP_DETECTOR && failDetectorOnce) {
                failDetectorOnce = false
                throw registrationFailure
            }
            listeners += listener
            return true
        }

        override fun unregister(listener: SensorEventListener?) {
            unregisterCalls += 1
            if (unregisterFailures > 0) {
                unregisterFailures -= 1
                throw cleanupFailure
            }
            listeners.remove(listener)
        }
    }

    private fun tracker(registration: StepSensorRegistration) =
        AndroidStepTracker(registration = registration, onStepCountChanged = {})

    private class Registration(
        private val available: Set<Int>,
        private var failDetectorOnce: Boolean = false,
        private var unregisterFailures: Int = 0,
    ) : StepSensorRegistration {
        val attempts = mutableListOf<Int>()
        val listeners = mutableSetOf<SensorEventListener>()

        override fun hasSensor(type: Int): Boolean = type in available

        override fun register(listener: SensorEventListener, type: Int): Boolean {
            attempts += type
            if (type == Sensor.TYPE_STEP_DETECTOR && failDetectorOnce) {
                failDetectorOnce = false
                throw SecurityException("step registration denied")
            }
            listeners += listener
            return true
        }

        override fun unregister(listener: SensorEventListener?) {
            if (unregisterFailures > 0) {
                unregisterFailures -= 1
                throw IllegalStateException("step cleanup failed")
            }
            listeners.remove(listener)
        }
    }
}

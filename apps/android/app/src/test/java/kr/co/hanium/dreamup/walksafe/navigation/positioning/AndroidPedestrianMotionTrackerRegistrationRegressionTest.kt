package kr.co.hanium.dreamup.walksafe.navigation.positioning

import android.hardware.Sensor
import android.hardware.SensorEventListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPedestrianMotionTrackerRegistrationRegressionTest {
    @Test
    fun permissionFailureAfterAccelerationRegistrationCannotLeakThePreviousListenerOnRetry() {
        assertFailedAttemptIsReleased(SecurityException("gyroscope registration denied"))
    }

    @Test
    fun runtimeFailureAfterAccelerationRegistrationCannotLeakThePreviousListenerOnRetry() {
        assertFailedAttemptIsReleased(IllegalStateException("gyroscope registration failed"))
    }

    @Test
    fun unavailableOrRejectedGyroscopeKeepsTheRegisteredAccelerationSource() {
        for (available in listOf(setOf(Sensor.TYPE_ACCELEROMETER), BOTH)) {
            val registration = Registration(available, rejectGyroscope = true)
            val tracker = AndroidPedestrianMotionTracker(registration = registration, onDecision = {})
            assertTrue(tracker.start())
            assertEquals(1, registration.listeners.size)
            tracker.stop()
            assertTrue(registration.listeners.isEmpty())
        }
    }

    @Test
    fun rejectedRegistrationAndClosedTrackerDoNotAcceptStepEvidenceOrRestart() {
        val rejected = Registration(BOTH, rejectAll = true)
        val tracker = AndroidPedestrianMotionTracker(registration = rejected, onDecision = {})
        assertFalse(tracker.start())
        assertFalse(tracker.recordStep(1_000L))
        assertEquals(1, rejected.unregisterCalls)
        assertTrue(rejected.listeners.isEmpty())
        tracker.close()
        val attempts = rejected.attempts.toList()
        assertFalse(tracker.start())
        assertEquals(attempts, rejected.attempts)
    }

    @Test
    fun failedStartCannotReplaceItsListenerWhileCleanupStillFails() {
        val registration = Registration(BOTH, failure = SecurityException("registration denied"),
            unregisterFailures = 2)
        val tracker = AndroidPedestrianMotionTracker(registration = registration, onDecision = {})
        assertTrue(runCatching { tracker.start() }.isFailure)
        assertEquals(1, registration.listeners.size)
        val attempts = registration.attempts.toList()
        assertTrue(runCatching { tracker.start() }.isFailure)
        assertEquals(attempts, registration.attempts)
        assertEquals(1, registration.listeners.size)
        assertFalse(tracker.recordStep(1_000L))
        assertTrue(tracker.start())
        assertEquals(1, registration.listeners.size)
        tracker.stop()
        assertTrue(registration.listeners.isEmpty())
    }

    @Test
    fun failedStopRetainsItsCleanupTargetAndDisablesEvidenceBeforeRetry() {
        val registration = Registration(BOTH, unregisterFailures = 1)
        val tracker = AndroidPedestrianMotionTracker(registration = registration, onDecision = {})
        assertTrue(tracker.start())
        assertTrue(tracker.recordStep(1_000L))
        assertTrue(runCatching { tracker.stop() }.isFailure)
        assertFalse(tracker.recordStep(1_100L))
        assertEquals(1, registration.listeners.size)
        assertTrue(tracker.start())
        assertEquals(1, registration.listeners.size)
        assertTrue(tracker.recordStep(1_000L))
        tracker.stop()
        assertTrue(registration.listeners.isEmpty())
    }

    @Test
    fun closedTrackerRetriesFailedCleanupWithoutRegisteringAgain() {
        val registration = Registration(BOTH, unregisterFailures = 1)
        val tracker = AndroidPedestrianMotionTracker(registration = registration, onDecision = {})
        assertTrue(tracker.start())
        val attempts = registration.attempts.toList()
        assertTrue(runCatching { tracker.close() }.isFailure)
        assertFalse(tracker.start())
        assertFalse(tracker.recordStep(1_000L))
        tracker.close()
        tracker.close()
        assertEquals(attempts, registration.attempts)
        assertEquals(2, registration.unregisterCalls)
        assertTrue(registration.listeners.isEmpty())
    }

    private fun assertFailedAttemptIsReleased(failure: RuntimeException) {
        val registration = Registration(BOTH, failure = failure)
        val tracker = AndroidPedestrianMotionTracker(registration = registration, onDecision = {})
        // Both a thrown failure and a false result are safe only if the owned resources are released.
        runCatching { tracker.start() }
        assertTrue(tracker.start())
        tracker.stop()
        assertTrue("The failed registration's original listener must also be released",
            registration.listeners.isEmpty())
        assertFalse(tracker.recordStep(1_000L))
    }

    private class Registration(
        private val available: Set<Int>,
        private var failure: RuntimeException? = null,
        private val rejectGyroscope: Boolean = false,
        private val rejectAll: Boolean = false,
        private var unregisterFailures: Int = 0,
    ) : MotionSensorRegistration {
        val attempts = mutableListOf<Int>()
        val listeners = mutableSetOf<SensorEventListener>()
        var unregisterCalls = 0

        override fun hasSensor(type: Int): Boolean = type in available

        override fun register(listener: SensorEventListener, type: Int): Boolean {
            attempts += type
            if (type == Sensor.TYPE_GYROSCOPE) failure?.let {
                failure = null
                throw it
            }
            if (rejectAll || (rejectGyroscope && type == Sensor.TYPE_GYROSCOPE)) return false
            listeners += listener
            return true
        }

        override fun unregister(listener: SensorEventListener?) {
            unregisterCalls += 1
            if (unregisterFailures > 0) {
                unregisterFailures -= 1
                throw IllegalStateException("sensor cleanup failed")
            }
            listeners.remove(listener)
        }
    }

    private companion object {
        val BOTH = setOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE)
    }
}

package kr.co.hanium.dreamup.walksafe.navigation

import android.hardware.Sensor
import android.hardware.SensorEventListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidStepTrackerRegistrationTest {
    @Test
    fun partialRegistrationFailureCleansUpAndAllowsRetry() {
        val registration = FailingOnceRegistration()
        var trackingStartedCalls = 0
        val tracker = AndroidStepTracker(
            registration = registration,
            onStepCountChanged = {},
            onTrackingStarted = { trackingStartedCalls += 1 },
        )

        assertFalse(tracker.start())
        assertEquals(listOf(Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR), registration.attempts)
        assertEquals(1, registration.unregisterCalls)
        assertTrue(registration.listeners.isEmpty())
        assertEquals(0, trackingStartedCalls)

        assertTrue(tracker.start())
        assertEquals(1, trackingStartedCalls)
        assertEquals(1, registration.listeners.size)
        assertEquals(
            listOf(
                Sensor.TYPE_STEP_COUNTER,
                Sensor.TYPE_STEP_DETECTOR,
                Sensor.TYPE_STEP_COUNTER,
                Sensor.TYPE_STEP_DETECTOR,
            ),
            registration.attempts,
        )

        tracker.stop()
        assertEquals(2, registration.unregisterCalls)
        assertTrue(registration.listeners.isEmpty())
    }

    private class FailingOnceRegistration : StepSensorRegistration {
        val attempts = mutableListOf<Int>()
        val listeners = mutableSetOf<SensorEventListener>()
        var unregisterCalls = 0
        private var failDetectorRegistration = true

        override fun hasSensor(type: Int): Boolean =
            type == Sensor.TYPE_STEP_COUNTER || type == Sensor.TYPE_STEP_DETECTOR

        override fun register(listener: SensorEventListener, type: Int): Boolean {
            attempts += type
            if (type == Sensor.TYPE_STEP_DETECTOR && failDetectorRegistration) {
                failDetectorRegistration = false
                throw SecurityException("step detector permission denied")
            }
            listeners += listener
            return true
        }

        override fun unregister(listener: SensorEventListener?) {
            unregisterCalls += 1
            listeners.remove(listener)
        }
    }
}

package kr.co.hanium.dreamup.walksafe.device

import android.hardware.Sensor
import android.hardware.SensorEventListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Injected sensor registration and samples; this does not assert native sensor delivery. */
class AndroidPhoneMountingSensorProbeTest {
    @Test
    fun gravityOnlyDeviceStartsAndMeasuresPitchWithoutAGyroscope() {
        val registration = FakeRegistration(sensors = setOf(Sensor.TYPE_GRAVITY))
        val probe = AndroidPhoneMountingSensorProbe(registration) { 10_000L }

        assertTrue(probe.start())
        assertEquals(listOf(Sensor.TYPE_GRAVITY), registration.attemptedTypes)
        gravity(probe, registration.listener)

        val measured = requireNotNull(probe.latestNow())
        assertEquals(45.0, measured.cameraPitchFromHorizontalDegrees, 0.0001)
        assertNull(measured.angularShakeDegreesPerSecond)
    }

    @Test
    fun gyroscopeRegistrationFailureCannotDiscardRegisteredGravity() {
        for (throws in listOf(false, true)) {
            val registration = FakeRegistration(failedType = Sensor.TYPE_GYROSCOPE, throws = throws)
            val probe = AndroidPhoneMountingSensorProbe(registration) { 10_000L }

            assertTrue(probe.start())
            assertTrue(registration.attemptedTypes.contains(Sensor.TYPE_GYROSCOPE))
            assertTrue(registration.unregistered.isEmpty())
            gravity(probe, registration.listener)

            assertEquals(45.0, requireNotNull(probe.latestNow()).cameraPitchFromHorizontalDegrees, 0.0001)
            assertNull(probe.latestNow()?.angularShakeDegreesPerSecond)
        }
    }

    @Test
    fun absentStaleOrInvalidGyroscopeSamplesNeverHideFreshGravity() {
        var now = 10_000L
        val registration = FakeRegistration()
        val probe = AndroidPhoneMountingSensorProbe(registration) { now }
        assertTrue(probe.start())
        gravity(probe, registration.listener)
        assertNotNull(probe.latestNow())

        probe.onSensorSample(registration.listener, Sensor.TYPE_GYROSCOPE, 10_000_000_000L,
            floatArrayOf(0f, 0f, 100f))
        assertTrue(requireNotNull(probe.latestNow()?.angularShakeDegreesPerSecond) > 5_000.0)
        assertEquals(45.0, requireNotNull(probe.latestNow()).cameraPitchFromHorizontalDegrees, 0.0001)

        now = 11_500L
        gravity(probe, registration.listener, observedAtMs = now)
        val afterGyroStalls = requireNotNull(probe.latestNow())
        assertEquals(45.0, afterGyroStalls.cameraPitchFromHorizontalDegrees, 0.0001)
        assertNull(afterGyroStalls.angularShakeDegreesPerSecond)

        probe.onSensorSample(registration.listener, Sensor.TYPE_GYROSCOPE, now * 1_000_000L,
            floatArrayOf(Float.NaN, 0f, 0f))
        assertNotNull(probe.latestNow())
        assertNotNull(probe.latestNow(shakeWindowMs = -1L))
    }

    @Test
    fun missingOrFailedGravityStillPreventsStart() {
        val missing = FakeRegistration(sensors = setOf(Sensor.TYPE_GYROSCOPE))
        val missingProbe = AndroidPhoneMountingSensorProbe(missing) { 10_000L }
        assertFalse(missingProbe.start())
        assertNull(missingProbe.latestNow())

        val failed = FakeRegistration(failedType = Sensor.TYPE_GRAVITY)
        val failedProbe = AndroidPhoneMountingSensorProbe(failed) { 10_000L }
        assertFalse(failedProbe.start())
        assertEquals(listOf(Sensor.TYPE_GRAVITY), failed.attemptedTypes)
        assertEquals(1, failed.unregistered.size)
        gravity(failedProbe, failed.listener)
        assertNull(failedProbe.latestNow())
    }

    @Test
    fun gravityMustStillBeFreshFiniteNonzeroAndNotInTheFuture() {
        var now = 10_000L
        val registration = FakeRegistration()
        val probe = AndroidPhoneMountingSensorProbe(registration) { now }
        assertTrue(probe.start())
        assertNull(probe.latestNow())

        for (values in listOf(floatArrayOf(Float.NaN, 1f, 1f), floatArrayOf(0f, 0f, 0f))) {
            probe.onSensorSample(registration.listener, Sensor.TYPE_GRAVITY, now * 1_000_000L, values)
            assertNull(probe.latestNow())
        }
        gravity(probe, registration.listener)
        now = 11_000L
        assertNotNull(probe.latestNow())
        now = 11_001L
        assertNull(probe.latestNow())
        gravity(probe, registration.listener, observedAtMs = 11_002L)
        assertNull(probe.latestNow())
        now = 11_002L
        assertNotNull(probe.latestNow())
        assertNull(probe.latestNow(maximumSensorAgeMs = -1L))
        now = -1L
        assertNull(probe.latestNow())
    }

    @Test
    fun stopAndRestartRequireNewGravityAndRejectRetiredListenerSamples() {
        val registration = FakeRegistration()
        val probe = AndroidPhoneMountingSensorProbe(registration) { 10_000L }
        assertTrue(probe.start())
        val retired = registration.listener
        gravity(probe, retired)
        assertNotNull(probe.latestNow())

        probe.stop()
        assertNull(probe.latestNow())
        assertEquals(listOf(retired), registration.unregistered)
        assertTrue(probe.start())
        gravity(probe, retired)
        assertNull(probe.latestNow())
        gravity(probe, registration.listener)
        assertNotNull(probe.latestNow())
    }

    private fun gravity(
        probe: AndroidPhoneMountingSensorProbe,
        listener: SensorEventListener,
        observedAtMs: Long = 10_000L,
    ) = probe.onSensorSample(listener, Sensor.TYPE_GRAVITY, observedAtMs * 1_000_000L,
        floatArrayOf(0f, 6.93f, 6.93f))

    private class FakeRegistration(
        private val sensors: Set<Int> = setOf(Sensor.TYPE_GRAVITY, Sensor.TYPE_GYROSCOPE),
        private val failedType: Int? = null,
        private val throws: Boolean = false,
    ) : PhoneMountingSensorRegistration {
        val attemptedTypes = mutableListOf<Int>()
        val unregistered = mutableListOf<SensorEventListener>()
        lateinit var listener: SensorEventListener

        override fun hasSensor(type: Int): Boolean = type in sensors

        override fun register(listener: SensorEventListener, type: Int): Boolean {
            this.listener = listener
            attemptedTypes += type
            if (type == failedType && throws) throw IllegalStateException("synthetic registration failure")
            return hasSensor(type) && type != failedType
        }

        override fun unregister(listener: SensorEventListener) {
            unregistered += listener
        }
    }
}

package kr.co.hanium.dreamup.walksafe.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFrameQualityMeasurementTest {

    @Test
    fun lumaStatsReportBrightnessAndDarkShare() {
        val allWhite = cameraLumaStatsOf(IntArray(100) { 255 })!!
        assertEquals(1.0, allWhite.normalizedBrightness, 1e-9)
        assertEquals(0.0, allWhite.occludedFraction, 1e-9)

        val allBlack = cameraLumaStatsOf(IntArray(100) { 0 })!!
        assertEquals(0.0, allBlack.normalizedBrightness, 1e-9)
        assertEquals(1.0, allBlack.occludedFraction, 1e-9)

        // A lens half covered by a hand reads as half near-black.
        val halfCovered = cameraLumaStatsOf(IntArray(100) { if (it < 50) 0 else 200 })!!
        assertEquals(0.5, halfCovered.occludedFraction, 1e-9)
    }

    @Test
    fun lumaStatsRejectAnEmptySampleInsteadOfReportingZeroBrightness() {
        assertNull(cameraLumaStatsOf(IntArray(0)))
    }

    @Test
    fun shakeIsTheGyroscopeMagnitudeInDegrees() {
        // 1 rad/s on a single axis.
        assertEquals(57.2957795, angularShakeDegreesPerSecond(1f, 0f, 0f)!!, 1e-4)
        assertEquals(0.0, angularShakeDegreesPerSecond(0f, 0f, 0f)!!, 1e-9)
        // Magnitude, not per-axis: a 3-4-5 triangle is 5 rad/s.
        assertEquals(5.0 * 57.2957795, angularShakeDegreesPerSecond(3f, 4f, 0f)!!, 1e-3)
    }

    @Test
    fun mountPitchIsZeroUprightAndNegativeWhenTheCameraLooksDown() {
        // Phone upright, rear camera at the horizon: gravity along +y.
        assertEquals(0.0, cameraMountPitchDegrees(0f, 9.81f, 0f)!!, 1e-6)
        // Phone flat, screen up: the rear camera looks straight down.
        assertEquals(-90.0, cameraMountPitchDegrees(0f, 0f, 9.81f)!!, 1e-6)
        // Phone flat, screen down: the rear camera looks straight up.
        assertEquals(90.0, cameraMountPitchDegrees(0f, 0f, -9.81f)!!, 1e-6)
        // Tilted forward off vertical: the camera looks below the horizon.
        assertTrue(cameraMountPitchDegrees(0f, 6.94f, 6.94f)!! < 0.0)
    }

    @Test
    fun mountPitchIsUnavailableWhileTheDeviceIsNotAtRestUnderGravity() {
        assertNull(cameraMountPitchDegrees(0f, 0f, 0f))
        assertNull(cameraMountPitchDegrees(0f, 40f, 0f))
        assertNull(cameraMountPitchDegrees(Float.NaN, 0f, 0f))
    }

    @Test
    fun motionReadingsAreNullBeforeAnySensorEventAndWithoutASensorManager() {
        val monitor = DeviceMotionMonitor(sensorManager = null, elapsedRealtimeMs = { 0L })

        monitor.start()

        assertNull(monitor.angularShakeDegreesPerSecond)
        assertNull(monitor.mountPitchDegrees)
    }
}

package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.EarthOrientationAccuracy
import kr.co.hanium.dreamup.walksafe.navigation.RotationMatrix3
import kr.co.hanium.dreamup.walksafe.navigation.RouteCompassSensorState
import kr.co.hanium.dreamup.walksafe.navigation.RouteCompassStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * S09-32: the sensor's heading error is not the walking-heading accuracy when the magnetometer
 * sample that certifies it was taken at another time while the phone turns. The output adds the
 * physical |rate| x skew; it invents no new margin and rejects a total above the existing limit.
 */
class ChestMountedHeadingRotationSkewTest {
    @Test
    fun turningDuringTheSensorSkewWidensTheReportedWalkingAccuracy() {
        val result = evaluate(rateDegreesPerSecond = 40.0, magneticAtMs = 900L)
        assertTrue(result.isValid)
        // 10 degrees sensor error + 40 deg/s x 100 ms.
        assertEquals(14.0, requireNotNull(result.accuracyDegrees), 1e-9)
        assertTrue(result.accuracyDegrees!! > 10.0)
    }

    @Test
    fun unknownRateOrSimultaneousSamplesKeepTheSensorAccuracy() {
        assertEquals(10.0, requireNotNull(evaluate(rateDegreesPerSecond = null, magneticAtMs = 900L).accuracyDegrees), 1e-9)
        assertEquals(10.0, requireNotNull(evaluate(rateDegreesPerSecond = 90.0, magneticAtMs = 1_000L).accuracyDegrees), 1e-9)
    }

    @Test
    fun aTotalAboveTheExistingOutputLimitIsNotReportedAsAValidHeading() {
        // 10 + 90 deg/s x 250 ms = 32.5 degrees > the existing 30 degree output limit.
        val result = evaluate(rateDegreesPerSecond = 90.0, magneticAtMs = 750L)
        assertFalse(result.isValid)
        assertNull(result.trueHeadingDegrees)
        assertEquals(ChestMountedHeadingGateReason.HEADING_ACCURACY, result.gateReason)
    }

    @Test
    fun malformedRatesAreRejectedInsteadOfIgnored() {
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { rate ->
            val result = evaluate(rateDegreesPerSecond = rate, magneticAtMs = 1_000L)
            assertFalse("$rate", result.isValid)
            assertEquals("$rate", ChestMountedHeadingGateReason.HEADING_ACCURACY, result.gateReason)
        }
    }

    @Test
    fun routeCompassStateExposesTheMeasuredRotationVectorRate() {
        val state = RouteCompassSensorState()
        state.start(1, 0L, setOf(RouteCompassStream.ROTATION_VECTOR))
        state.onRotationVector(1, matrixForHeading(0.0), 5f, 1_000L, EarthOrientationAccuracy.HIGH, 1_000L)
        assertNull(state.latestRotationRateDegreesPerSecond())
        state.onRotationVector(1, matrixForHeading(10.0), 5f, 1_100L, EarthOrientationAccuracy.HIGH, 1_100L)
        assertEquals(100.0, requireNotNull(state.latestRotationRateDegreesPerSecond()), 0.1)
        state.onRotationVector(1, null, 5f, 1_200L, EarthOrientationAccuracy.HIGH, 1_200L)
        assertNull(state.latestRotationRateDegreesPerSecond())
    }

    private fun evaluate(rateDegreesPerSecond: Double?, magneticAtMs: Long): ChestMountedHeadingResult =
        ChestMountedHeading().evaluate(
            nowElapsedRealtimeMs = 1_000L,
            rotation = ChestMountedRotationSample(
                deviceToMagneticEnu = matrixForHeading(0.0),
                observedAtMs = 1_000L,
                headingAccuracyDegrees = 10.0,
                accuracy = EarthOrientationAccuracy.HIGH,
                angularRateDegreesPerSecond = rateDegreesPerSecond,
            ),
            magneticField = ChestMountedMagneticFieldSample(
                xMicrotesla = 0.0, yMicrotesla = 0.0, zMicrotesla = -50.0,
                observedAtMs = magneticAtMs, accuracy = EarthOrientationAccuracy.HIGH,
            ),
            geomagneticReference = ChestMountedGeomagneticReference(
                declinationDegrees = 0.0, inclinationDegrees = 0.0, expectedFieldStrengthMicrotesla = 50.0,
            ),
        )

    private fun matrixForHeading(headingDegrees: Double): RotationMatrix3 {
        val headingRadians = Math.toRadians(headingDegrees)
        val east = sin(headingRadians).toFloat()
        val north = cos(headingRadians).toFloat()
        return RotationMatrix3(
            north, 0f, -east,
            -east, 0f, -north,
            0f, 1f, 0f,
        )
    }
}

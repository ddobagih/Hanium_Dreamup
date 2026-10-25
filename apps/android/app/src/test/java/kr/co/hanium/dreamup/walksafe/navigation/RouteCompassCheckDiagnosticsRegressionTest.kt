package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** S08-34: existing rejection policy must retain the facts from the check that actually ran. */
class RouteCompassCheckDiagnosticsRegressionTest {
    @Test
    fun reportedErrorRejectionKeepsItsInputWithoutInventingProjectionFacts() {
        for (error in listOf(30.01f, Float.POSITIVE_INFINITY)) {
            val result = primary(error = error)
            assertNull(result.observation)
            assertEquals(RouteCompassHeadingReason.HEADING_ACCURACY, result.reason)
            val diagnostic = RouteGuidanceDiagnostics.compassState(result, NOW + 10L)
            assertEquals("ROTATION_VECTOR", diagnostic["compass_source"])
            assertEquals("REPORTED_HEADING_ERROR", diagnostic["compass_rejected_check"])
            assertEquals(10L, diagnostic["compass_input_age_ms"])
            assertEquals(if (error.isFinite()) 30 else null, diagnostic["reported_heading_accuracy_deg"])
            assertNull(diagnostic["heading_accuracy_deg"])
            assertNull(diagnostic["rear_horizontal_permille"])
        }
    }

    @Test
    fun projectedErrorRejectionKeepsTheDistinctRearAxisCheck() {
        val result = primary(tiltDegrees = 60.0, error = 15.01f)
        assertNull(result.observation)
        assertEquals(RouteCompassHeadingReason.HEADING_ACCURACY, result.reason)
        val diagnostic = RouteGuidanceDiagnostics.compassState(result, NOW)
        assertEquals("REAR_AXIS_PROJECTION", diagnostic["compass_rejected_check"])
        assertEquals(15, diagnostic["reported_heading_accuracy_deg"])
        assertEquals(30, diagnostic["heading_accuracy_deg"])
        assertEquals(500, diagnostic["rear_horizontal_permille"])
    }

    @Test
    fun magneticStrengthRejectionKeepsMeasuredAndExpectedFieldFacts() {
        val result = fallback(sample(0.0, -80.0, -60.0))
        assertNull(result.observation)
        assertEquals(RouteCompassHeadingReason.MAGNETIC_FIELD_ANOMALY, result.reason)
        val diagnostic = RouteGuidanceDiagnostics.compassState(result, NOW)
        assertEquals("GRAVITY_MAGNETIC", diagnostic["compass_source"])
        assertEquals("MAGNETIC_FIELD_STRENGTH", diagnostic["compass_rejected_check"])
        assertEquals(100, diagnostic["magnetic_field_ut"])
        assertEquals(50, diagnostic["expected_magnetic_field_ut"])
        assertEquals(50, diagnostic["magnetic_field_deviation_ut"])
        assertEquals(30, diagnostic["magnetic_field_allowance_ut"])
        assertNull(diagnostic["magnetic_horizontal_permille"])
    }

    @Test
    fun parallelMagneticBasisRejectionKeepsItsHorizontalRatio() {
        val result = fallback(sample(0.0, 50.0, 0.0))
        assertNull(result.observation)
        assertEquals(RouteCompassHeadingReason.INVALID_ROTATION, result.reason)
        val diagnostic = RouteGuidanceDiagnostics.compassState(result, NOW)
        assertEquals("MAGNETIC_HORIZONTAL_BASIS", diagnostic["compass_rejected_check"])
        assertEquals(50, diagnostic["magnetic_field_ut"])
        assertEquals(50, diagnostic["expected_magnetic_field_ut"])
        assertEquals(0, diagnostic["magnetic_horizontal_permille"])
    }

    @Test
    fun acceptedFallbackKeepsCheckedFieldAndRearAxisFacts() {
        val result = fallback(sample(0.0, -40.0, -30.0))
        assertEquals(RouteCompassHeadingReason.VALID, result.reason)
        val diagnostic = RouteGuidanceDiagnostics.compassState(result, NOW)
        assertEquals("GRAVITY_MAGNETIC", diagnostic["compass_source"])
        assertNull(diagnostic["compass_rejected_check"])
        assertEquals(1_000, diagnostic["rear_horizontal_permille"])
        assertEquals(50, diagnostic["magnetic_field_ut"])
        assertEquals(600, diagnostic["magnetic_horizontal_permille"])
        assertNull(diagnostic["reported_heading_accuracy_deg"])
    }

    @Test
    fun absentInputDoesNotFabricateNumericCheckFacts() {
        val result = RouteCompassHeading.evaluate(null, 0.0, NOW)
        val diagnostic = RouteGuidanceDiagnostics.compassState(result, NOW)
        assertEquals("ORIENTATION_MISSING", diagnostic["reason"])
        listOf("compass_source", "compass_rejected_check", "compass_input_age_ms",
            "rear_horizontal_permille", "magnetic_field_ut", "expected_magnetic_field_ut",
            "magnetic_field_deviation_ut", "magnetic_field_allowance_ut", "magnetic_horizontal_permille",
        ).forEach { assertNull(diagnostic[it]) }
    }

    private fun primary(tiltDegrees: Double = 0.0, error: Float = 5f): RouteCompassHeadingResult {
        val tilt = Math.toRadians(tiltDegrees)
        val rotation = RotationMatrix3(1f, 0f, 0f, 0f, -sin(tilt).toFloat(), -cos(tilt).toFloat(),
            0f, cos(tilt).toFloat(), -sin(tilt).toFloat())
        return RouteCompassHeading.evaluate(DeviceEarthOrientation(rotation, NOW, error, EarthOrientationAccuracy.HIGH), 0.0, NOW)
    }

    private fun fallback(magnetic: RouteCompassVectorSample) = RouteCompassHeading.evaluateFallback(
        gravity = sample(0.0, 9.80665, 0.0), accelerometer = null, magnetic = magnetic,
        reference = ChestMountedGeomagneticReference(0.0, 53.13, 50.0), nowMs = NOW,
    )

    private fun sample(x: Double, y: Double, z: Double) =
        RouteCompassVectorSample(x, y, z, NOW, EarthOrientationAccuracy.HIGH)

    private companion object {
        const val NOW = 1_000L
    }
}

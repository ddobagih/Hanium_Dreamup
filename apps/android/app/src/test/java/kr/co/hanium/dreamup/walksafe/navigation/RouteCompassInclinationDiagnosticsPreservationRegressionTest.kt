package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S08-34: retain already computed inclination checks without changing compass admission. */
class RouteCompassInclinationDiagnosticsPreservationRegressionTest {
    @Test
    fun rejectedReversedInclinationRetainsItsMeasuredExpectedResidualAndAllowance() {
        val result = fallback(magneticY = 40.0)
        assertEquals(RouteCompassHeadingReason.MAGNETIC_FIELD_ANOMALY, result.reason)
        assertNull(result.observation)
        val evidence = requireNotNull(result.checkEvidence)
        assertEquals(RouteCompassRejectedCheck.MAGNETIC_FIELD_INCLINATION, evidence.rejectedCheck)
        assertEquals(-53.130102, requireNotNull(evidence.magneticInclinationDegrees), 0.00001)
        assertEquals(53.13, requireNotNull(evidence.expectedMagneticInclinationDegrees), 0.0)
        assertEquals(106.260102, requireNotNull(evidence.magneticInclinationResidualDegrees), 0.00001)
        assertEquals(30.0, requireNotNull(evidence.magneticInclinationAllowanceDegrees), 0.0)

        val fields = RouteGuidanceDiagnostics.compassState(result, NOW)
        assertInclination(fields, -53, 53, 106, 30)
        assertEquals("MAGNETIC_FIELD_ANOMALY", fields["reason"])
        assertEquals("MAGNETIC_FIELD_INCLINATION", fields["compass_rejected_check"])
        assertEquals("GRAVITY_MAGNETIC", fields["compass_source"])
        assertEquals(50, fields["magnetic_field_ut"])
        assertEquals(600, fields["magnetic_horizontal_permille"])
        assertEquals(0L, fields["compass_input_age_ms"])
    }

    @Test
    fun normalSignedReferencesKeepTheirCheckedNumbersWithoutPromotingHeadingAccuracy() {
        listOf(1.0, -1.0).forEach { sign ->
            val result = fallback(magneticY = -40.0 * sign, expectedInclination = 53.13 * sign)
            assertEquals(RouteCompassHeadingReason.VALID, result.reason)
            val observation = requireNotNull(result.observation)
            assertNull(observation.headingAccuracyDegrees)
            assertEquals(NOW, observation.observedAtElapsedRealtimeMs)
            assertInclination(RouteGuidanceDiagnostics.compassState(result, NOW),
                (53 * sign).toInt(), (53 * sign).toInt(), 0, 30)
        }
    }

    @Test
    fun missingInputAndAnEarlierFailedCheckDoNotInventInclinationNumbers() {
        val missing = RouteCompassHeading.evaluate(null, 0.0, NOW)
        val strengthRejected = RouteCompassHeading.evaluateFallback(
            gravity = vector(0.0, 9.80665, 0.0), accelerometer = null,
            magnetic = vector(0.0, -80.0, -60.0), reference = reference(), nowMs = NOW,
        )
        assertEquals(RouteCompassHeadingReason.ORIENTATION_MISSING, missing.reason)
        assertEquals(RouteCompassRejectedCheck.MAGNETIC_FIELD_STRENGTH,
            strengthRejected.checkEvidence?.rejectedCheck)
        listOf(missing, strengthRejected).forEach { result ->
            val fields = RouteGuidanceDiagnostics.compassState(result, NOW)
            inclinationKeys.forEach { assertNull("$it was not evaluated", fields[it]) }
        }
    }

    @Test
    fun nonFiniteDiagnosticFactsRemainNullAndDoNotMutateTheResult() {
        val normal = fallback()
        val evidence = requireNotNull(normal.checkEvidence).copy(
            magneticInclinationDegrees = Double.NaN,
            expectedMagneticInclinationDegrees = Double.POSITIVE_INFINITY,
            magneticInclinationResidualDegrees = Double.NEGATIVE_INFINITY,
            magneticInclinationAllowanceDegrees = Double.NaN,
        )
        val result = normal.copy(checkEvidence = evidence)
        val fields = RouteGuidanceDiagnostics.compassState(result, NOW)
        inclinationKeys.forEach { assertNull(fields[it]) }
        assertEquals("VALID", fields["reason"])
        assertEquals(normal.observation, result.observation)
        assertTrue(requireNotNull(result.checkEvidence?.magneticInclinationDegrees).isNaN())
        assertEquals(Double.POSITIVE_INFINITY, result.checkEvidence?.expectedMagneticInclinationDegrees)
    }

    @Test
    fun finiteDiagnosticFactsUseTheExistingWholeDegreeRounding() {
        val normal = fallback()
        val result = normal.copy(checkEvidence = requireNotNull(normal.checkEvidence).copy(
            magneticInclinationDegrees = -53.6,
            expectedMagneticInclinationDegrees = 53.6,
            magneticInclinationResidualDegrees = 106.49,
            magneticInclinationAllowanceDegrees = 20.51,
        ))
        val fields = RouteGuidanceDiagnostics.compassState(result, NOW)
        assertInclination(fields, -54, 54, 106, 21)
        assertEquals(normal.observation, result.observation)
        assertEquals(-53.6, requireNotNull(result.checkEvidence?.magneticInclinationDegrees), 0.0)
    }

    @Test
    fun generatedCueRetainsTheActualRejectedCompassAndTheSeparateRouteReason() {
        val firstCompass = fallback(magneticY = 40.0)
        val navigator = RouteNavigator().apply {
            setRoute(WalkingRoute("TEST", WalkingRouteSummary(400, 400),
                listOf(point(0.0), point(400.0)),
                listOf(WalkingRouteGuidePoint(0, point(200.0), "안내", 200, 200, turnType = 13))))
        }
        val location = point(50.0).let { TrustedLocation(it.latitude, it.longitude, 2f, NOW) }
        val first = navigator.update(location, NOW, false, facingObservation = firstCompass.observation,
            facingUnavailableReason = firstCompass.reason)
        assertEquals("guidance_facing_recheck_wait", first.reason)
        assertNull(first.speechCueToken)
        assertNull(navigator.retryGuidance(NOW + 1_499L, null, firstCompass.reason)?.instruction)
        val cueAt = NOW + 1_500L
        val compass = RouteCompassHeading.evaluateFallback(
            gravity = RouteCompassVectorSample(0.0, 9.80665, 0.0, cueAt, EarthOrientationAccuracy.HIGH),
            accelerometer = null,
            magnetic = RouteCompassVectorSample(0.0, 40.0, -30.0, cueAt, EarthOrientationAccuracy.HIGH),
            reference = reference(), nowMs = cueAt,
        )
        assertEquals(firstCompass.reason, compass.reason)
        assertNull(compass.observation)
        val update = requireNotNull(navigator.retryGuidance(cueAt, compass.observation, compass.reason))
        val token = requireNotNull(update.speechCueToken)
        val generated = RouteGuidanceDiagnostics.guidanceGenerated(update, cueAt + 10L, compass)
        assertInclination(generated, -53, 53, 106, 30)
        assertEquals("CURRENT_SEGMENT", generated["reason"])
        assertEquals("MAGNETIC_FIELD_ANOMALY", generated["compass_reason"])
        assertEquals("MAGNETIC_FIELD_INCLINATION", generated["compass_rejected_check"])
        assertEquals("UNKNOWN", generated["expression_level"])
        assertNull(generated["relative_angle_deg"])
        assertEquals("${token.routeRevision}:${token.cueRevision}", generated["cue_id"])
        assertEquals(10L, generated["compass_input_age_ms"])
        val start = RouteGuidanceDiagnostics.guidanceStartChecked(update, cueAt + 20L, current = true)
        assertEquals(generated["cue_id"], start["cue_id"])
        assertEquals("started", start["result"])
        assertNotNull(compass.checkEvidence)
        generated.forEach { (key, value) ->
            assertTrue("$key carries only diagnostic numbers/enums/ids", value == null || value is Int ||
                value is Long || (value is String && Regex("[A-Z_]+|[a-z]+|\\d+:\\d+").matches(value)))
        }
    }

    private fun assertInclination(fields: Map<String, Any?>, measured: Int, expected: Int,
        residual: Int, allowance: Int) {
        assertEquals(measured, fields["magnetic_inclination_deg"])
        assertEquals(expected, fields["expected_magnetic_inclination_deg"])
        assertEquals(residual, fields["magnetic_inclination_residual_deg"])
        assertEquals(allowance, fields["magnetic_inclination_allowance_deg"])
    }

    private fun fallback(magneticY: Double = -40.0, expectedInclination: Double = 53.13) =
        RouteCompassHeading.evaluateFallback(
            gravity = vector(0.0, 9.80665, 0.0), accelerometer = null,
            magnetic = vector(0.0, magneticY, -30.0), reference = reference(expectedInclination), nowMs = NOW,
        )
    private fun vector(x: Double, y: Double, z: Double) =
        RouteCompassVectorSample(x, y, z, NOW, EarthOrientationAccuracy.HIGH)
    private fun reference(inclination: Double = 53.13) = ChestMountedGeomagneticReference(0.0, inclination, 50.0)
    private fun point(eastM: Double) = RoutePoint(0.0, eastM / 111_194.92664455874)

    private companion object {
        const val NOW = 1_000L
        val inclinationKeys = listOf("magnetic_inclination_deg", "expected_magnetic_inclination_deg",
            "magnetic_inclination_residual_deg", "magnetic_inclination_allowance_deg")
    }
}

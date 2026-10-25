package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 08.txt #10: field strength alone cannot validate a gravity/magnetic direction basis. */
class RouteCompassInclinationRegressionTest {
    private val nowMs = 1_000L
    private val reference = ChestMountedGeomagneticReference(0.0, 53.13, 50.0)
    private val gravity = vector(0.0, 9.80665, 0.0)
    private val magnetic = vector(0.0, -40.0, -30.0)

    @Test
    fun aFieldWithTheSameStrengthButReversedInclinationCannotProduceAFacing() {
        val normal = fallback(gravity, magnetic, reference)
        val normalObservation = requireNotNull(normal.observation)
        assertEquals(RouteCompassHeadingReason.VALID, normal.reason)
        assertEquals(0.0, normalObservation.degreesTrueNorth, 0.00001)

        val reversed = magnetic.copy(y = 40.0)
        assertEquals(magnetic.norm(), reversed.norm(), 0.0)
        val result = fallback(gravity, reversed, reference)

        assertEquals(RouteCompassHeadingReason.MAGNETIC_FIELD_ANOMALY, result.reason)
        assertNull(result.observation)
        val evidence = requireNotNull(result.checkEvidence)
        assertEquals(RouteCompassRejectedCheck.MAGNETIC_FIELD_INCLINATION, evidence.rejectedCheck)
        assertEquals(50.0, requireNotNull(evidence.magneticFieldMicrotesla), 0.00001)
        assertEquals(-53.130102, requireNotNull(evidence.magneticInclinationDegrees), 0.00001)
        assertEquals(53.13, requireNotNull(evidence.expectedMagneticInclinationDegrees), 0.0)
        assertEquals(30.0, requireNotNull(evidence.magneticInclinationAllowanceDegrees), 0.0)
        assertTrue(requireNotNull(evidence.magneticInclinationResidualDegrees) > 100.0)
    }

    @Test
    fun anInvalidReferenceInclinationCannotBeTreatedAsAValidatedReference() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 91.0, -91.0).forEach { invalidInclination ->
            val result = fallback(gravity, magnetic, reference.copy(inclinationDegrees = invalidInclination))

            assertEquals(RouteCompassHeadingReason.REFERENCE_MISSING, result.reason)
            assertNull(result.observation)
        }
    }

    @Test
    fun matchingSignedInclinationsRemainAvailableAcrossASharedPoseRotation() {
        listOf(1.0, -1.0).forEach { sign ->
            val matchingMagnetic = magnetic.copy(y = -40.0 * sign)
            val matchingReference = reference.copy(inclinationDegrees = reference.inclinationDegrees * sign)
            listOf(false, true).forEach { rotate ->
                val g = if (rotate) gravity.rotateAboutZ() else gravity
                val m = if (rotate) matchingMagnetic.rotateAboutZ() else matchingMagnetic
                val result = fallback(g, m, matchingReference)
                val observation = requireNotNull(result.observation)

                assertEquals(RouteCompassHeadingReason.VALID, result.reason)
                assertEquals(0.0, observation.degreesTrueNorth, 0.00001)
                assertEquals(nowMs, observation.observedAtElapsedRealtimeMs)
                assertEquals(RouteFacingSource.GRAVITY_MAGNETIC, observation.source)
                assertEquals(RouteFacingSensorQuality.CALIBRATED_SENSOR_CHECKS, observation.sensorQuality)
                assertNull(observation.headingAccuracyDegrees)
            }
        }
    }

    private fun fallback(
        g: RouteCompassVectorSample,
        m: RouteCompassVectorSample,
        ref: ChestMountedGeomagneticReference,
    ) = RouteCompassHeading.evaluateFallback(g, g, m, ref, nowMs)

    private fun vector(x: Double, y: Double, z: Double) =
        RouteCompassVectorSample(x, y, z, nowMs, EarthOrientationAccuracy.HIGH)

    private fun RouteCompassVectorSample.rotateAboutZ() = copy(x = -y, y = x)
}

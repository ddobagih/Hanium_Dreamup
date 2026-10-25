package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Route-only dip admission changes from 20 to 30 degrees. A passed dip check does not supply
 * numeric heading accuracy or waive field strength, source quality, freshness or phone posture.
 */
class RouteCompassInclinationAllowanceRegressionTest {
    private val reference = ChestMountedGeomagneticReference(0.0, 40.0, 50.0)
    private val observedReference = ChestMountedGeomagneticReference(
        -8.928451538, 52.491764069, 50.31284765625)

    @Test
    fun changingOnlyInclinationByTwentyToThirtyDegreesKeepsTheSameFacing() {
        val baseline = requireNotNull(fallback(magnetic(40.0)).observation)
        for (dip in listOf(61.0, 65.0, 69.99)) {
            val result = fallback(magnetic(dip))
            assertValidFallback(result, NOW)
            val observation = requireNotNull(result.observation)
            assertEquals("The horizontal field direction has not changed", baseline.degreesTrueNorth,
                observation.degreesTrueNorth, 0.00001)
            assertEquals(RouteFacingSensorQuality.CALIBRATED_SENSOR_CHECKS, observation.sensorQuality)
            val evidence = requireNotNull(result.checkEvidence)
            assertEquals(50.0, requireNotNull(evidence.magneticFieldMicrotesla), 0.00001)
            assertEquals(dip - 40.0, requireNotNull(evidence.magneticInclinationResidualDegrees), 0.00001)
            assertEquals(30.0, requireNotNull(evidence.magneticInclinationAllowanceDegrees), 0.0)
        }
    }

    @Test
    fun thirtyDegreesIsInclusiveAndTheNearestOutsideValuesAreRejectedOnBothSides() {
        for (sign in listOf(1.0, -1.0)) {
            val ref = reference.copy(inclinationDegrees = sign * 30.0)
            val inside = fallback(magnetic(sign * 0.0001), ref)
            assertValidFallback(inside, NOW)
            assertTrue(requireNotNull(inside.checkEvidence?.magneticInclinationResidualDegrees) < 30.0)

            // An exactly horizontal field gives an exact zero dip, avoiding a trigonometric
            // rounding ambiguity at the inclusive 30-degree boundary.
            val boundary = fallback(magnetic(0.0), ref)
            assertValidFallback(boundary, NOW)
            assertEquals(30.0, requireNotNull(boundary.checkEvidence?.magneticInclinationResidualDegrees), 0.0)

            val outside = fallback(magnetic(-sign * 0.0001), ref)
            assertInclinationRejected(outside)
            assertTrue(requireNotNull(outside.checkEvidence?.magneticInclinationResidualDegrees) > 30.0)
        }
    }

    @Test
    fun theAllowanceUsesSignedNegativeInclinationAndDoesNotAdmitAReversedDip() {
        val negativeReference = reference.copy(inclinationDegrees = -40.0)
        val admitted = fallback(magnetic(-65.0), negativeReference)
        assertValidFallback(admitted, NOW)
        assertEquals(0.0, requireNotNull(admitted.observation).degreesTrueNorth, 0.00001)
        assertEquals(-65.0, requireNotNull(admitted.checkEvidence?.magneticInclinationDegrees), 0.00001)
        assertEquals(25.0, requireNotNull(admitted.checkEvidence?.magneticInclinationResidualDegrees), 0.00001)

        for (sign in listOf(1.0, -1.0)) {
            val reversed = fallback(magnetic(-sign * 40.0), reference.copy(inclinationDegrees = sign * 40.0))
            assertInclinationRejected(reversed)
            assertEquals(80.0, requireNotNull(reversed.checkEvidence?.magneticInclinationResidualDegrees), 0.00001)
        }
    }

    @Test
    fun anOtherwiseAdmissibleDipCannotWaiveStrongFieldOrNearParallelVectors() {
        val strong = fallback(magnetic(65.0, strength = 81.0))
        assertEquals(RouteCompassHeadingReason.MAGNETIC_FIELD_ANOMALY, strong.reason)
        assertNull(strong.observation)
        assertEquals(RouteCompassRejectedCheck.MAGNETIC_FIELD_STRENGTH, strong.checkEvidence?.rejectedCheck)

        // A 27-degree residual would be admitted, but the horizontal basis is still too small.
        val nearParallel = fallback(magnetic(87.0), reference.copy(inclinationDegrees = 60.0))
        assertEquals(RouteCompassHeadingReason.INVALID_ROTATION, nearParallel.reason)
        assertNull(nearParallel.observation)
        assertEquals(RouteCompassRejectedCheck.MAGNETIC_HORIZONTAL_BASIS, nearParallel.checkEvidence?.rejectedCheck)
        assertTrue(requireNotNull(nearParallel.checkEvidence?.magneticHorizontalRatio) < 0.1)
    }

    @Test
    fun aTwentyFiveDegreeResidualCannotWaiveStaleOrLowQualitySources() {
        val stale = RouteCompassHeading.evaluateFallback(
            gravity(NOW - 501L), null, magnetic(65.0, NOW - 501L), reference, NOW)
        assertEquals(RouteCompassHeadingReason.STALE, stale.reason)
        assertNull(stale.observation)

        for (lowGravity in listOf(false, true)) {
            val g = gravity().let { if (lowGravity) it.copy(accuracy = EarthOrientationAccuracy.LOW) else it }
            val m = magnetic(65.0).let { if (lowGravity) it else it.copy(accuracy = EarthOrientationAccuracy.LOW) }
            val lowQuality = RouteCompassHeading.evaluateFallback(g, null, m, reference, NOW)
            assertEquals(RouteCompassHeadingReason.QUALITY_LOW, lowQuality.reason)
            assertNull(lowQuality.observation)
        }
    }

    @Test
    fun missingRawRotationVectorAccuracyCanUseTheWidenedIndependentDipAllowance() {
        val state = sensorState()
        state.onSensorCallback(1, RouteCompassStream.ROTATION_VECTOR, NOW * NS_PER_MS,
            NOW * NS_PER_MS) { receipt ->
            val decoded = RouteCompassRawSensorConsumer.rotationVector(state, 1,
                floatArrayOf(0.70710677f, 0f, 0f, 0.70710677f, -1f),
                NOW * NS_PER_MS, EarthOrientationAccuracy.HIGH, receipt)
            assertTrue(requireNotNull(decoded).headingErrorDeg.isNaN())
        }
        state.admitRaw(RouteCompassStream.GRAVITY, gravity())
        state.admitRaw(RouteCompassStream.MAGNETIC_FIELD, magnetic(65.0))

        val result = state.evaluate(NOW, reference)
        assertEquals(NOW, state.latestRotationEventAt())
        assertValidFallback(result, NOW)
        assertEquals(RouteFacingSensorQuality.CALIBRATED_WITHOUT_MOTION_CHECK,
            requireNotNull(result.observation).sensorQuality)
        assertEquals(25.0, requireNotNull(result.checkEvidence?.magneticInclinationResidualDegrees), 0.0001)
    }

    @Test
    fun recoveryRequiresANewerAdmittedPairAndKeepsItsOriginalFreshness() {
        val state = sensorState()
        state.admitRaw(RouteCompassStream.GRAVITY, gravity())
        state.admitRaw(RouteCompassStream.MAGNETIC_FIELD, magnetic(75.0))
        assertInclinationRejected(state.evaluate(NOW, reference))

        state.admitRaw(RouteCompassStream.MAGNETIC_FIELD, magnetic(65.0, 900L), 1_100L)
        state.admitRaw(RouteCompassStream.GRAVITY, gravity(900L), 1_100L)
        val late = state.evaluate(1_100L, reference)
        assertInclinationRejected(late)
        assertEquals(NOW, state.latestMagneticAt())
        assertEquals(NOW, late.checkEvidence?.observedAtElapsedRealtimeMs)

        state.admitRaw(RouteCompassStream.MAGNETIC_FIELD, magnetic(65.0, 1_200L))
        state.admitRaw(RouteCompassStream.GRAVITY, gravity(1_200L))
        assertValidFallback(state.evaluate(1_200L, reference), 1_200L)
        assertValidFallback(state.evaluate(1_700L, reference), 1_200L)
        val expired = state.evaluate(1_701L, reference)
        assertEquals(RouteCompassHeadingReason.STALE, expired.reason)
        assertNull(expired.observation)

        state.admitRaw(RouteCompassStream.MAGNETIC_FIELD, magnetic(65.0, 1_800L))
        state.admitRaw(RouteCompassStream.GRAVITY, gravity(1_800L))
        assertValidFallback(state.evaluate(1_800L, reference), 1_800L)
    }

    @Test
    fun theObservedFlatVectorsBecomeAvailableAfterACommonPoseRotation() {
        // Fixed observed vectors supplied for this regression; clock and owner are synthetic.
        // Rotating both vectors (x, z, -y) preserves norm and dip while separating phone posture.
        // This proves policy admission, not the real phone's absolute bearing accuracy.
        val g = observedGravity().rotateForPosture()
        val m = observedMagnetic().rotateForPosture()
        assertEquals(observedGravity().norm(), g.norm(), 0.000000001)
        assertEquals(observedMagnetic().norm(), m.norm(), 0.000000001)
        val result = RouteCompassHeading.evaluateFallback(g, null, m, observedReference, NOW)

        assertValidFallback(result, NOW)
        val evidence = requireNotNull(result.checkEvidence)
        assertEquals(74.818255125, requireNotNull(evidence.magneticFieldMicrotesla), 0.000001)
        assertEquals(77.209128845, requireNotNull(evidence.magneticInclinationDegrees), 0.000001)
        assertEquals(24.717364777, requireNotNull(evidence.magneticInclinationResidualDegrees), 0.000001)
        assertEquals(30.0, requireNotNull(evidence.magneticInclinationAllowanceDegrees), 0.0)
        assertTrue(requireNotNull(evidence.forwardHorizontal) > 0.99)
    }

    @Test
    fun theUnrotatedObservedFlatVectorsStillRequireTheExistingPhonePosture() {
        val result = RouteCompassHeading.evaluateFallback(
            observedGravity(), null, observedMagnetic(), observedReference, NOW)

        assertEquals(RouteCompassHeadingReason.REAR_AXIS_VERTICAL, result.reason)
        assertNull(result.observation)
        val evidence = requireNotNull(result.checkEvidence)
        assertNull("The admitted magnetic checks must not be reported as a magnetic fault", evidence.rejectedCheck)
        assertEquals(24.717364777, requireNotNull(evidence.magneticInclinationResidualDegrees), 0.000001)
        assertTrue(requireNotNull(evidence.forwardHorizontal) < 0.3)
    }

    private fun fallback(m: RouteCompassVectorSample, ref: ChestMountedGeomagneticReference = reference) =
        RouteCompassHeading.evaluateFallback(gravity(), gravity(), m, ref, NOW)

    private fun assertValidFallback(result: RouteCompassHeadingResult, observedAtMs: Long) {
        assertEquals(RouteCompassHeadingReason.VALID, result.reason)
        val observation = requireNotNull(result.observation)
        assertEquals(RouteFacingSource.GRAVITY_MAGNETIC, observation.source)
        assertEquals(observedAtMs, observation.observedAtElapsedRealtimeMs)
        assertTrue(observation.degreesTrueNorth.isFinite())
        assertFalse(observation.magneticFieldSuspect)
        assertNull(observation.headingAccuracyDegrees)
        assertNull(observation.reportedHeadingAccuracyDegrees)
        assertNull(result.checkEvidence?.rejectedCheck)
    }

    private fun assertInclinationRejected(result: RouteCompassHeadingResult) {
        assertEquals(RouteCompassHeadingReason.MAGNETIC_FIELD_ANOMALY, result.reason)
        assertNull(result.observation)
        assertEquals(RouteCompassRejectedCheck.MAGNETIC_FIELD_INCLINATION, result.checkEvidence?.rejectedCheck)
    }

    private fun sensorState() = RouteCompassSensorState().apply {
        start(1, 0L, setOf(RouteCompassStream.ROTATION_VECTOR,
            RouteCompassStream.GRAVITY, RouteCompassStream.MAGNETIC_FIELD))
    }

    private fun RouteCompassSensorState.admitRaw(stream: RouteCompassStream,
        sample: RouteCompassVectorSample, receiptMs: Long = sample.observedAtMs) {
        val timestamp = requireNotNull(sample.sensorTimestampNanos)
        onSensorCallback(1, stream, timestamp, receiptMs * NS_PER_MS) { receipt ->
            RouteCompassRawSensorConsumer.vector(this, 1, stream,
                floatArrayOf(sample.x.toFloat(), sample.y.toFloat(), sample.z.toFloat()),
                timestamp, sample.accuracy, receipt)
        }
    }

    private fun gravity(atMs: Long = NOW) = vector(0.0, 9.80665, 0.0, atMs)

    private fun magnetic(dipDegrees: Double, atMs: Long = NOW, strength: Double = 50.0): RouteCompassVectorSample {
        val radians = Math.toRadians(dipDegrees)
        return vector(0.0, -strength * sin(radians), -strength * cos(radians), atMs)
    }

    private fun observedGravity() = vector(0.05, -0.20, -9.8, NOW)
    private fun observedMagnetic() = vector(-12.64, -9.64, 73.11, NOW)
    private fun RouteCompassVectorSample.rotateForPosture() = copy(y = z, z = -y)
    private fun vector(x: Double, y: Double, z: Double, atMs: Long) =
        RouteCompassVectorSample(x, y, z, atMs, EarthOrientationAccuracy.HIGH, atMs * NS_PER_MS)

    private companion object {
        const val NOW = 1_000L
        const val NS_PER_MS = 1_000_000L
    }
}

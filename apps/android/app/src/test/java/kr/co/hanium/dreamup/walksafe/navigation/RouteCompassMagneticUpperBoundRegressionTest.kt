package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.atan2

/** Only the route compass's positive field-strength allowance changes. Lower strength,
 * inclination, time, source quality and strict chest/PDR policy keep their existing contracts.
 */
class RouteCompassMagneticUpperBoundRegressionTest {
    @Test
    fun normalInclinationBetweenTheOldAndNewUpperBoundsProducesAFreshFallback() {
        for (strength in listOf(63.0, 70.0, 79.99, 80.0)) {
            val result = fallback(strength)
            assertEquals("strength=$strength", RouteCompassHeadingReason.VALID, result.reason)
            val observation = requireNotNull(result.observation)
            assertEquals(RouteFacingSource.GRAVITY_MAGNETIC, observation.source)
            assertEquals(NOW, observation.observedAtElapsedRealtimeMs)
            assertEquals(0.0, observation.degreesTrueNorth, 0.00001)
            assertFalse("Raising the upper limit does not introduce a new quality tier",
                observation.magneticFieldSuspect)
            assertNull(observation.headingAccuracyDegrees)
            assertNull(observation.reportedHeadingAccuracyDegrees)
        }
    }

    @Test
    fun missingRotationVectorNumericAccuracyStillUsesTheAdmittedMagneticFallback() {
        val state = RouteCompassSensorState().apply {
            start(1, 0L, setOf(RouteCompassStream.ROTATION_VECTOR,
                RouteCompassStream.GRAVITY, RouteCompassStream.MAGNETIC_FIELD))
        }
        state.onSensorCallback(1, RouteCompassStream.ROTATION_VECTOR, NOW * NS_PER_MS,
            NOW * NS_PER_MS) { receipt ->
            RouteCompassRawSensorConsumer.rotationVector(state, 1,
                floatArrayOf(0.70710677f, 0f, 0f, 0.70710677f, -1f),
                NOW * NS_PER_MS, EarthOrientationAccuracy.HIGH, receipt)
        }
        state.admit(RouteCompassStream.GRAVITY, gravity(NOW), NOW)
        state.admit(RouteCompassStream.MAGNETIC_FIELD, magnetic(70.0, NOW), NOW)

        val result = state.evaluate(NOW, reference)
        assertEquals(RouteCompassHeadingReason.VALID, result.reason)
        val observation = requireNotNull(result.observation)
        assertEquals(RouteFacingSource.GRAVITY_MAGNETIC, observation.source)
        assertEquals(RouteFacingSensorQuality.CALIBRATED_WITHOUT_MOTION_CHECK, observation.sensorQuality)
        assertEquals(NOW, observation.observedAtElapsedRealtimeMs)
        assertNull(observation.headingAccuracyDegrees)
        assertNull(observation.reportedHeadingAccuracyDegrees)
    }

    @Test
    fun rotationVectorCrossCheckUsesTheSameUpperLimitWithoutChangingItsExistingSuspectPolicy() {
        val primary = RouteCompassHeading.evaluate(DeviceEarthOrientation(
            RotationMatrix3(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f),
            NOW, 5f, EarthOrientationAccuracy.HIGH), 0.0, NOW)
        assertNotNull(primary.observation)
        for (strength in listOf(70.0, 80.0)) {
            val checked = RouteCompassHeading.crossCheckMagneticField(primary,
                magnetic(strength, NOW), reference, NOW)
            assertEquals(primary.observation, checked.observation)
            assertNull(checked.checkEvidence?.rejectedCheck)
        }
        for (strength in listOf(80.01, 100.0, 150.0)) {
            val checked = RouteCompassHeading.crossCheckMagneticField(primary,
                magnetic(strength, NOW), reference, NOW)
            assertEquals(RouteCompassHeadingReason.VALID, checked.reason)
            assertTrue(requireNotNull(checked.observation).magneticFieldSuspect)
            assertNull(RouteFacingGuidance.evaluate(0.0, checked.observation, NOW).clockHour)
        }
    }

    @Test
    fun valuesAboveTheRaisedUpperLimitStillRejectTheFallback() {
        for (strength in listOf(80.01, 100.0, 150.0)) {
            val result = fallback(strength)
            assertStrengthRejected(result)
            assertEquals(strength, requireNotNull(result.checkEvidence?.magneticFieldMicrotesla), 0.00001)
        }
    }

    @Test
    fun lowerAllowanceStillRejectsTwentyFiveMicroteslaAndKeepsItsExactBoundary() {
        for (strength in listOf(25.0, 37.49)) assertStrengthRejected(fallback(strength))
        assertEquals(RouteCompassHeadingReason.VALID, fallback(37.5).reason)
    }

    @Test
    fun theAbsoluteFifteenToOneHundredMicroteslaRangeDoesNotExpand() {
        assertEquals(RouteCompassHeadingReason.VALID,
            fallback(100.0, reference.copy(expectedFieldStrengthMicrotesla = 70.0)).reason)
        assertStrengthRejected(fallback(100.01, reference.copy(expectedFieldStrengthMicrotesla = 70.0)))
        assertEquals(RouteCompassHeadingReason.VALID,
            fallback(15.0, reference.copy(expectedFieldStrengthMicrotesla = 20.0)).reason)
        assertStrengthRejected(fallback(14.99, reference.copy(expectedFieldStrengthMicrotesla = 20.0)))
    }

    @Test
    fun theObservedDeskVectorPassesMagneticChecksButKeepsItsIndependentPostureCheck() {
        // Fixed observed vectors from magnetic-threshold-audit/prepared-samples.json, first row.
        // Test clock/owner are synthetic. This is not a new device observation or bearing proof.
        val observedReference = ChestMountedGeomagneticReference(
            -8.928451538, 52.491764069, 50.3128125)
        val result = RouteCompassHeading.evaluateFallback(
            gravity = vector(0.05, -0.19, -9.8, NOW), accelerometer = null,
            magnetic = vector(-14.72, -12.49, 65.49, NOW),
            reference = observedReference, nowMs = NOW)

        assertEquals(RouteCompassHeadingReason.REAR_AXIS_VERTICAL, result.reason)
        assertNull(result.observation)
        val evidence = requireNotNull(result.checkEvidence)
        assertNull("Passed magnetic checks must not be reported as a magnetic fault", evidence.rejectedCheck)
        assertTrue(requireNotNull(evidence.magneticFieldMicrotesla) < 80.5008)
        assertTrue(requireNotNull(evidence.magneticInclinationResidualDegrees) > 20.0)
        assertTrue(requireNotNull(evidence.magneticInclinationResidualDegrees) < 30.0)
        assertEquals(30.0, requireNotNull(evidence.magneticInclinationAllowanceDegrees), 0.0)
        assertTrue("Admitted magnetic checks cannot waive phone posture",
            requireNotNull(evidence.forwardHorizontal) < 0.3)
    }

    @Test
    fun theLatestStrongFieldCannotHideBehindAnOlderNormalPairOrItsLateArrival() {
        for (strength in listOf(100.0, 150.0)) for (lateNormal in listOf(false, true)) {
            val state = RouteCompassSensorState().apply {
                start(1, 0L, setOf(RouteCompassStream.GRAVITY, RouteCompassStream.MAGNETIC_FIELD))
            }
            if (lateNormal) {
                state.admit(RouteCompassStream.MAGNETIC_FIELD, magnetic(strength, 1_000L), 1_000L)
                state.admit(RouteCompassStream.MAGNETIC_FIELD, magnetic(50.0, 900L), 1_100L)
                state.admit(RouteCompassStream.GRAVITY, gravity(900L), 1_100L)
            } else {
                state.admit(RouteCompassStream.MAGNETIC_FIELD, magnetic(50.0, 900L), 900L)
                state.admit(RouteCompassStream.GRAVITY, gravity(900L), 900L)
                state.admit(RouteCompassStream.MAGNETIC_FIELD, magnetic(strength, 1_000L), 1_000L)
            }
            val restricted = state.evaluate(1_100L, reference)
            assertStrengthRejected(restricted)
            assertEquals(1_000L, state.latestMagneticAt())
            assertEquals(1_000L, restricted.checkEvidence?.observedAtElapsedRealtimeMs)
            assertEquals(strength, requireNotNull(restricted.checkEvidence?.magneticFieldMicrotesla), 0.00001)

            state.admit(RouteCompassStream.MAGNETIC_FIELD, magnetic(50.0, 1_200L), 1_200L)
            state.admit(RouteCompassStream.GRAVITY, gravity(1_200L), 1_200L)
            val recovered = state.evaluate(1_200L, reference)
            assertEquals(RouteCompassHeadingReason.VALID, recovered.reason)
            assertEquals(1_200L, requireNotNull(recovered.observation).observedAtElapsedRealtimeMs)
        }
    }

    private fun fallback(strength: Double, ref: ChestMountedGeomagneticReference = reference) =
        RouteCompassHeading.evaluateFallback(gravity(NOW), gravity(NOW), magnetic(strength, NOW), ref, NOW)

    private fun assertStrengthRejected(result: RouteCompassHeadingResult) {
        assertEquals(RouteCompassHeadingReason.MAGNETIC_FIELD_ANOMALY, result.reason)
        assertNull(result.observation)
        assertEquals(RouteCompassRejectedCheck.MAGNETIC_FIELD_STRENGTH, result.checkEvidence?.rejectedCheck)
    }

    private fun RouteCompassSensorState.admit(stream: RouteCompassStream,
        sample: RouteCompassVectorSample, receiptMs: Long) {
        onSensorCallback(1, stream, requireNotNull(sample.sensorTimestampNanos), receiptMs * NS_PER_MS) { receipt ->
            onVector(1, stream, sample, receipt)
        }
    }

    private fun gravity(atMs: Long) = vector(0.0, 9.80665, 0.0, atMs)
    private fun magnetic(strength: Double, atMs: Long) = vector(0.0, -strength * 0.8, -strength * 0.6, atMs)
    private fun vector(x: Double, y: Double, z: Double, atMs: Long) =
        RouteCompassVectorSample(x, y, z, atMs, EarthOrientationAccuracy.HIGH, atMs * NS_PER_MS)

    private companion object {
        const val NOW = 1_000L
        const val NS_PER_MS = 1_000_000L
        val reference = ChestMountedGeomagneticReference(0.0, Math.toDegrees(atan2(40.0, 30.0)), 50.0)
    }
}

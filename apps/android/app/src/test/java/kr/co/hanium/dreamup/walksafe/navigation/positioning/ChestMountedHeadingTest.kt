package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.EarthOrientationAccuracy
import kr.co.hanium.dreamup.walksafe.navigation.RotationMatrix3
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChestMountedHeadingTest {
    @Test
    fun mapsDeviceNegativeZToMagneticNorth() {
        val result = evaluate(headingDegrees = 0.0)

        assertTrue(result.isValid)
        assertEquals(0.0, requireNotNull(result.trueHeadingDegrees), 1e-6)
        assertEquals(5.0, requireNotNull(result.accuracyDegrees), 1e-6)
        assertEquals(1_000L, result.observedAtMs)
        assertEquals(ChestMountedHeadingGateReason.VALID, result.gateReason)
        assertEquals(
            ChestMountedHeadingSource.ROTATION_VECTOR_AND_MAGNETIC_FIELD,
            result.source,
        )
    }

    @Test
    fun mapsDeviceNegativeZToMagneticEast() {
        val result = evaluate(headingDegrees = 90.0)

        assertEquals(90.0, requireNotNull(result.trueHeadingDegrees), 1e-5)
    }

    @Test
    fun addsDeclinationAndWrapsTrueHeading() {
        val result = evaluate(headingDegrees = 355.0, declinationDegrees = 10.0)

        assertEquals(5.0, requireNotNull(result.trueHeadingDegrees), 1e-5)
    }

    @Test
    fun rejectsDirectionDisturbanceEvenWhenFieldMagnitudeMatches() {
        val core = ChestMountedHeading()
        val result = core.evaluate(
            nowElapsedRealtimeMs = 1_000L,
            rotation = rotation(),
            magneticField = magneticField(xMicrotesla = 50.0, zMicrotesla = 0.0),
            geomagneticReference = reference(),
        )

        assertInvalid(
            result,
            ChestMountedHeadingGateReason.FIELD_HORIZONTAL_DIRECTION_ANOMALY,
        )
    }

    @Test
    fun rejectsUnsupportedChestPosture() {
        val core = ChestMountedHeading()
        val result = core.evaluate(
            nowElapsedRealtimeMs = 1_000L,
            rotation = rotation(
                matrix = RotationMatrix3(
                    1f, 0f, 0f,
                    0f, 1f, 0f,
                    0f, 0f, 1f,
                ),
            ),
            magneticField = magneticField(),
            geomagneticReference = reference(),
        )

        assertInvalid(result, ChestMountedHeadingGateReason.UNSUPPORTED_POSTURE)
    }

    @Test
    fun rejectsStaleRotationAndMagneticSamplesIndependently() {
        val core = ChestMountedHeading()
        val staleRotation = core.evaluate(
            nowElapsedRealtimeMs = 1_000L,
            rotation = rotation(headingDegrees = 0.0, observedAtMs = 499L),
            magneticField = magneticField(observedAtMs = 1_000L),
            geomagneticReference = reference(),
        )
        val staleMagnetic = core.evaluate(
            nowElapsedRealtimeMs = 1_000L,
            rotation = rotation(headingDegrees = 0.0, observedAtMs = 1_000L),
            magneticField = magneticField(observedAtMs = 499L),
            geomagneticReference = reference(),
        )

        assertInvalid(staleRotation, ChestMountedHeadingGateReason.ROTATION_STALE)
        assertInvalid(staleMagnetic, ChestMountedHeadingGateReason.MAGNETIC_FIELD_STALE)
    }

    @Test
    fun rejectsSensorTimestampSkewAboveLimit() {
        val core = ChestMountedHeading()
        val result = core.evaluate(
            nowElapsedRealtimeMs = 1_000L,
            rotation = rotation(headingDegrees = 0.0, observedAtMs = 1_000L),
            magneticField = magneticField(observedAtMs = 749L),
            geomagneticReference = reference(),
        )

        assertInvalid(result, ChestMountedHeadingGateReason.SENSOR_SKEW)
    }

    @Test
    fun requiresMediumSensorAccuracyAndAtMostThirtyDegreeHeadingAccuracy() {
        val core = ChestMountedHeading()
        val lowRotation = core.evaluate(
            nowElapsedRealtimeMs = 1_000L,
            rotation = rotation(accuracy = EarthOrientationAccuracy.LOW),
            magneticField = magneticField(),
            geomagneticReference = reference(),
        )
        val lowMagnetic = core.evaluate(
            nowElapsedRealtimeMs = 1_000L,
            rotation = rotation(),
            magneticField = magneticField(accuracy = EarthOrientationAccuracy.LOW),
            geomagneticReference = reference(),
        )
        val inaccurateHeading = core.evaluate(
            nowElapsedRealtimeMs = 1_000L,
            rotation = rotation(headingAccuracyDegrees = 30.01),
            magneticField = magneticField(),
            geomagneticReference = reference(),
        )
        val boundary = core.evaluate(
            nowElapsedRealtimeMs = 1_000L,
            rotation = rotation(headingAccuracyDegrees = 30.0),
            magneticField = magneticField(),
            geomagneticReference = reference(),
        )

        assertInvalid(lowRotation, ChestMountedHeadingGateReason.ROTATION_ACCURACY)
        assertInvalid(lowMagnetic, ChestMountedHeadingGateReason.MAGNETIC_FIELD_ACCURACY)
        assertInvalid(inaccurateHeading, ChestMountedHeadingGateReason.HEADING_ACCURACY)
        assertTrue(boundary.isValid)
    }

    @Test
    fun rejectsNonFiniteFieldAndNonOrthonormalRotation() {
        val core = ChestMountedHeading()
        val invalidField = core.evaluate(
            nowElapsedRealtimeMs = 1_000L,
            rotation = rotation(),
            magneticField = magneticField(xMicrotesla = Double.NaN),
            geomagneticReference = reference(),
        )
        val invalidRotation = core.evaluate(
            nowElapsedRealtimeMs = 1_000L,
            rotation = rotation(
                matrix = RotationMatrix3(
                    1f, 0f, 0f,
                    0f, 1f, 0f,
                    0f, 0f, 2f,
                ),
            ),
            magneticField = magneticField(),
            geomagneticReference = reference(),
        )

        assertInvalid(invalidField, ChestMountedHeadingGateReason.INVALID_MAGNETIC_FIELD)
        assertInvalid(invalidRotation, ChestMountedHeadingGateReason.INVALID_ROTATION)
    }

    @Test
    fun fiveNormalSamplesUnderOneSecondDoNotRecoverFromFieldAnomaly() {
        val core = ChestMountedHeading()
        val anomaly = evaluateAt(core, observedAtMs = 100L, fieldStrengthMicrotesla = 70.1)

        assertInvalid(anomaly, ChestMountedHeadingGateReason.FIELD_ANOMALY)
        for (sampleIndex in 1..4) {
            val recovering = evaluateAt(
                core,
                observedAtMs = 100L + sampleIndex * 100L,
                fieldStrengthMicrotesla = 50.0,
            )
            assertInvalid(recovering, ChestMountedHeadingGateReason.FIELD_RECOVERING)
        }
        val fifthNormal = evaluateAt(core, observedAtMs = 600L, fieldStrengthMicrotesla = 50.0)
        val recovered = evaluateAt(core, observedAtMs = 1_200L, fieldStrengthMicrotesla = 50.0)

        assertInvalid(fifthNormal, ChestMountedHeadingGateReason.FIELD_RECOVERING)
        assertTrue(recovered.isValid)
    }

    @Test
    fun oneSecondWithoutFiveNormalSamplesDoesNotRecoverFromAnomaly() {
        val core = ChestMountedHeading()
        evaluateAt(core, observedAtMs = 100L, fieldStrengthMicrotesla = 70.1)
        val firstNormal = evaluateAt(core, observedAtMs = 200L, fieldStrengthMicrotesla = 50.0)
        val oneSecondLater = evaluateAt(core, observedAtMs = 1_200L, fieldStrengthMicrotesla = 50.0)
        evaluateAt(core, observedAtMs = 1_300L, fieldStrengthMicrotesla = 50.0)
        evaluateAt(core, observedAtMs = 1_400L, fieldStrengthMicrotesla = 50.0)
        val recovered = evaluateAt(core, observedAtMs = 1_500L, fieldStrengthMicrotesla = 50.0)

        assertInvalid(firstNormal, ChestMountedHeadingGateReason.FIELD_RECOVERING)
        assertInvalid(oneSecondLater, ChestMountedHeadingGateReason.FIELD_RECOVERING)
        assertTrue(recovered.isValid)
    }

    @Test
    fun reportsUnavailableMagneticSensor() {
        val result = ChestMountedHeading().evaluate(
            nowElapsedRealtimeMs = 1_000L,
            rotation = rotation(),
            magneticField = magneticField(),
            geomagneticReference = reference(),
            magneticSensorAvailable = false,
        )

        assertInvalid(result, ChestMountedHeadingGateReason.MAGNETIC_SENSOR_UNAVAILABLE)
    }

    private fun evaluate(
        headingDegrees: Double,
        declinationDegrees: Double = 0.0,
    ) = ChestMountedHeading().evaluate(
        nowElapsedRealtimeMs = 1_000L,
        rotation = rotation(headingDegrees = headingDegrees),
        magneticField = magneticFieldForHeading(headingDegrees),
        geomagneticReference = reference(declinationDegrees),
    )

    private fun evaluateAt(
        core: ChestMountedHeading,
        observedAtMs: Long,
        fieldStrengthMicrotesla: Double,
    ) = core.evaluate(
        nowElapsedRealtimeMs = observedAtMs,
        rotation = rotation(observedAtMs = observedAtMs),
        magneticField = magneticField(
            zMicrotesla = -fieldStrengthMicrotesla,
            observedAtMs = observedAtMs,
        ),
        geomagneticReference = reference(),
    )

    private fun rotation(
        headingDegrees: Double = 0.0,
        observedAtMs: Long = 1_000L,
        headingAccuracyDegrees: Double = 5.0,
        accuracy: EarthOrientationAccuracy = EarthOrientationAccuracy.HIGH,
        matrix: RotationMatrix3 = matrixForHeading(headingDegrees),
    ) = ChestMountedRotationSample(
        deviceToMagneticEnu = matrix,
        observedAtMs = observedAtMs,
        headingAccuracyDegrees = headingAccuracyDegrees,
        accuracy = accuracy,
    )

    private fun magneticField(
        xMicrotesla: Double = 0.0,
        yMicrotesla: Double = 0.0,
        zMicrotesla: Double = -50.0,
        observedAtMs: Long = 1_000L,
        accuracy: EarthOrientationAccuracy = EarthOrientationAccuracy.HIGH,
    ) = ChestMountedMagneticFieldSample(
        xMicrotesla = xMicrotesla,
        yMicrotesla = yMicrotesla,
        zMicrotesla = zMicrotesla,
        observedAtMs = observedAtMs,
        accuracy = accuracy,
    )

    private fun reference(declinationDegrees: Double = 0.0) =
        ChestMountedGeomagneticReference(
            declinationDegrees = declinationDegrees,
            inclinationDegrees = 0.0,
            expectedFieldStrengthMicrotesla = 50.0,
        )

    private fun magneticFieldForHeading(headingDegrees: Double): ChestMountedMagneticFieldSample {
        val headingRadians = Math.toRadians(headingDegrees)
        return magneticField(
            xMicrotesla = -50.0 * sin(headingRadians),
            zMicrotesla = -50.0 * cos(headingRadians),
        )
    }

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

    private fun assertInvalid(
        result: ChestMountedHeadingResult,
        expectedReason: ChestMountedHeadingGateReason,
    ) {
        assertFalse(result.isValid)
        assertNull(result.trueHeadingDegrees)
        assertEquals(expectedReason, result.gateReason)
    }
}

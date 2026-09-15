package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedHeading
import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedHeadingGateReason
import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedMagneticFieldSample
import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedRotationSample
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class RouteCompassHeadingTest {
    private val now = 1_000L

    @Test fun rearCameraCardinalDirectionsMapToMagneticNorthEastSouthAndWest() {
        listOf(0.0, 90.0, 180.0, 270.0).forEach { heading ->
            assertHeading(heading, evaluate(matrix(heading)))
        }
    }

    @Test fun rollDoesNotChangeTheRearCameraHeadingOrRequirePhoneTopUp() {
        listOf(0.0, 90.0, 180.0, 270.0).forEach { roll ->
            assertHeading(70.0, evaluate(matrix(heading = 70.0, roll = roll)))
        }
    }

    @Test fun fortyFiveDegreeTiltKeepsTheHeadingAndConservativelyIncreasesError() {
        listOf(-45.0, 45.0).forEach { tilt ->
            val result = evaluate(matrix(heading = 90.0, tilt = tilt))
            assertHeading(90.0, result)
            assertEquals(5.0 * sqrt(2.0), result.observation!!.headingAccuracyDegrees!!, 0.00001)
        }
    }

    @Test fun theSameTiltedMatrixRejectedByStrictChestMountIsUsableForRouteFacing() {
        val rotation = matrix(heading = 90.0, tilt = 45.0)
        val strict = ChestMountedHeading().evaluate(
            nowElapsedRealtimeMs = now,
            rotation = ChestMountedRotationSample(rotation, now, 5.0, EarthOrientationAccuracy.HIGH),
            magneticField = ChestMountedMagneticFieldSample(-50.0, 0.0, 0.0, now, EarthOrientationAccuracy.HIGH),
            geomagneticReference = ChestMountedGeomagneticReference(0.0, 0.0, 50.0),
        )
        assertEquals(ChestMountedHeadingGateReason.UNSUPPORTED_POSTURE, strict.gateReason)
        assertNull(strict.trueHeadingDegrees)
        assertHeading(90.0, evaluate(rotation))
    }

    @Test fun sixtyDegreeTiltIsIncludedButMoreVerticalRearAxesAreUnavailable() {
        listOf(-60.0, 60.0).forEach { tilt ->
            assertHeading(0.0, evaluate(matrix(tilt = tilt)))
        }
        listOf(-60.1, 60.1, -90.0, 90.0).forEach { tilt ->
            assertInvalid(RouteCompassHeadingReason.REAR_AXIS_VERTICAL, evaluate(matrix(tilt = tilt)))
        }
    }

    @Test fun aHorizontalScreenWithVerticalRearCameraHasNoUsableCompassDirection() {
        assertInvalid(
            RouteCompassHeadingReason.REAR_AXIS_VERTICAL,
            evaluate(RotationMatrix3(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)),
        )
    }

    @Test fun declinationIsAddedAndWrapsAcrossNorthInBothDirections() {
        assertHeading(5.0, evaluate(matrix(heading = 355.0), declination = 10.0))
        assertHeading(355.0, evaluate(matrix(heading = 5.0), declination = -10.0))
        assertHeading(0.0, evaluate(matrix(heading = 90.0), declination = 270.0))
        assertHeading(280.0, evaluate(matrix(heading = 190.0), declination = -270.0))
    }

    @Test fun missingOrientationAndReferenceRemainDistinct() {
        assertInvalid(RouteCompassHeadingReason.ORIENTATION_MISSING, RouteCompassHeading.evaluate(null, 0.0, now))
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { declination ->
            assertInvalid(RouteCompassHeadingReason.REFERENCE_MISSING, evaluate(declination = declination))
        }
    }

    @Test fun sampleAgeZeroAndFiveHundredMillisecondsAreAcceptedButOlderIsStale() {
        assertHeading(0.0, evaluate(observedAt = now))
        val boundary = evaluate(observedAt = now - 500L)
        assertHeading(0.0, boundary)
        assertEquals(now - 500L, boundary.observation!!.observedAtElapsedRealtimeMs)
        assertInvalid(RouteCompassHeadingReason.STALE, evaluate(observedAt = now - 501L))
    }

    @Test fun futureNegativeAndOverflowingTimestampsAreRejected() {
        assertInvalid(RouteCompassHeadingReason.INVALID_TIMESTAMP, evaluate(observedAt = now + 1L))
        assertInvalid(RouteCompassHeadingReason.INVALID_TIMESTAMP, evaluate(observedAt = -1L))
        val extremeOrientation = orientation(observedAt = Long.MIN_VALUE)
        assertInvalid(RouteCompassHeadingReason.INVALID_TIMESTAMP, RouteCompassHeading.evaluate(extremeOrientation, 0.0, Long.MAX_VALUE))
        assertInvalid(RouteCompassHeadingReason.INVALID_TIMESTAMP, RouteCompassHeading.evaluate(orientation(), 0.0, -1L))
    }

    @Test fun mediumAndHighQualityArePreservedAndLowerQualityIsRejected() {
        listOf(EarthOrientationAccuracy.MEDIUM, EarthOrientationAccuracy.HIGH).forEach {
            assertHeading(0.0, evaluate(quality = it))
        }
        listOf(EarthOrientationAccuracy.UNRELIABLE, EarthOrientationAccuracy.LOW).forEach {
            assertInvalid(RouteCompassHeadingReason.QUALITY_LOW, evaluate(quality = it))
        }
    }

    @Test fun missingHeadingAccuracyIsDistinguishedFromAnExcessiveError() {
        listOf(Float.NaN, -1f, -0.01f).forEach {
            assertInvalid(RouteCompassHeadingReason.HEADING_ACCURACY_UNAVAILABLE, evaluate(error = it))
        }
        listOf(30.01f, Float.POSITIVE_INFINITY).forEach {
            assertInvalid(RouteCompassHeadingReason.HEADING_ACCURACY, evaluate(error = it))
        }
        assertHeading(0.0, evaluate(error = 0f))
        assertHeading(0.0, evaluate(error = 30f))
    }

    @Test fun projectedErrorAboveThirtyDegreesIsRejectedWithoutClamping() {
        assertHeading(0.0, evaluate(matrix(tilt = 60.0), error = 15f))
        assertEquals(30.0, evaluate(matrix(tilt = 60.0), error = 15f).observation!!.headingAccuracyDegrees!!, 0.00001)
        assertInvalid(RouteCompassHeadingReason.HEADING_ACCURACY, evaluate(matrix(tilt = 60.0), error = 15.01f))
        assertInvalid(RouteCompassHeadingReason.HEADING_ACCURACY, evaluate(matrix(tilt = 45.0), error = 30f))
    }

    @Test fun invalidRotationsDoNotProduceAnObservation() {
        listOf(
            matrix().copy(m00 = Float.NaN),
            matrix().copy(m00 = Float.POSITIVE_INFINITY),
            matrix().copy(m00 = 2f),
            matrix().copy(m00 = -1f),
            matrix().copy(m01 = 0.2f),
        ).forEach { assertInvalid(RouteCompassHeadingReason.INVALID_ROTATION, evaluate(it)) }
    }

    @Test fun unavailableInputNeverReturnsAPreviouslyAcceptedHeading() {
        assertHeading(90.0, evaluate(matrix(90.0)))
        assertInvalid(RouteCompassHeadingReason.ORIENTATION_MISSING, RouteCompassHeading.evaluate(null, 0.0, now))
        assertInvalid(RouteCompassHeadingReason.QUALITY_LOW, evaluate(quality = EarthOrientationAccuracy.LOW))
    }

    private fun evaluate(
        rotation: RotationMatrix3 = matrix(),
        declination: Double? = 0.0,
        observedAt: Long = now,
        error: Float = 5f,
        quality: EarthOrientationAccuracy = EarthOrientationAccuracy.HIGH,
    ) = RouteCompassHeading.evaluate(orientation(rotation, observedAt, error, quality), declination, now)

    private fun orientation(
        rotation: RotationMatrix3 = matrix(),
        observedAt: Long = now,
        error: Float = 5f,
        quality: EarthOrientationAccuracy = EarthOrientationAccuracy.HIGH,
    ) = DeviceEarthOrientation(rotation, observedAt, error, quality)

    /** Device -Z faces heading; tilt raises it above the horizon and roll keeps that axis fixed. */
    private fun matrix(heading: Double = 0.0, tilt: Double = 0.0, roll: Double = 0.0): RotationMatrix3 {
        val h = Math.toRadians(heading)
        val t = Math.toRadians(tilt)
        val r = Math.toRadians(roll)
        val x = doubleArrayOf(cos(h), -sin(h), 0.0)
        val y = doubleArrayOf(-sin(h) * sin(t), -cos(h) * sin(t), cos(t))
        val z = doubleArrayOf(-sin(h) * cos(t), -cos(h) * cos(t), -sin(t))
        val rolledX = DoubleArray(3) { x[it] * cos(r) + y[it] * sin(r) }
        val rolledY = DoubleArray(3) { -x[it] * sin(r) + y[it] * cos(r) }
        return RotationMatrix3(
            rolledX[0].toFloat(), rolledY[0].toFloat(), z[0].toFloat(),
            rolledX[1].toFloat(), rolledY[1].toFloat(), z[1].toFloat(),
            rolledX[2].toFloat(), rolledY[2].toFloat(), z[2].toFloat(),
        )
    }

    private fun assertHeading(expected: Double, result: RouteCompassHeadingResult) {
        assertEquals(RouteCompassHeadingReason.VALID, result.reason)
        assertNotNull(result.observation)
        assertEquals(expected, result.observation!!.degreesTrueNorth, 0.00001)
    }

    private fun assertInvalid(expected: RouteCompassHeadingReason, result: RouteCompassHeadingResult) {
        assertEquals(expected, result.reason)
        assertNull(result.observation)
    }
}

package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class RouteCompassFallbackTest {
    private val now = 1_000L
    private val reference = ChestMountedGeomagneticReference(0.0, 53.13, 50.0)
    // Portrait, rear -Z points north; magnetic ENU vector = (0,30,-40).
    private val gravity = sample(0.0, 9.80665, 0.0)
    private val magnetic = sample(0.0, -40.0, -30.0)

    @Test fun gravityCompassCarriesSourceAndQualityWithoutInventingAngularError() {
        val observation = requireNotNull(fallback().observation)
        assertEquals(0.0, observation.degreesTrueNorth, 0.00001)
        assertEquals(RouteFacingSource.GRAVITY_MAGNETIC, observation.source)
        assertEquals(RouteFacingSensorQuality.CALIBRATED_SENSOR_CHECKS, observation.sensorQuality)
        assertNull(observation.headingAccuracyDegrees)
        assertNull(observation.reportedHeadingAccuracyDegrees)
        assertTrue(RouteFacingGuidance.isUsableObservation(observation, now))
    }

    @Test fun independentAccelerometerWorksWhenGravityHasNoEvents() {
        val observation = requireNotNull(fallback(gravity = null, acceleration = gravity).observation)
        assertEquals(RouteFacingSource.ACCELEROMETER_MAGNETIC, observation.source)
        assertEquals(0.0, observation.degreesTrueNorth, 0.00001)
    }

    @Test fun invalidOrStaleGravityDoesNotHideValidIndependentAccelerometer() {
        listOf(gravity.copy(x = Double.NaN), gravity.copy(y = 0.0), gravity.copy(observedAtMs = 499L),
            gravity.copy(accuracy = EarthOrientationAccuracy.UNRELIABLE)).forEach {
            assertEquals(RouteFacingSource.ACCELEROMETER_MAGNETIC, fallback(it, gravity).observation?.source)
        }
    }

    @Test fun freshGravityIsPreferredOverAcceleration() {
        assertEquals(RouteFacingSource.GRAVITY_MAGNETIC, fallback(acceleration = gravity).observation?.source)
    }

    @Test fun missingReferenceGravityAndMagneticInputsHaveDistinctReasons() {
        assertReason(RouteCompassHeadingReason.REFERENCE_MISSING, fallback(reference = null))
        assertReason(RouteCompassHeadingReason.GRAVITY_MISSING, fallback(gravity = null))
        assertReason(RouteCompassHeadingReason.MAGNETIC_FIELD_MISSING, fallback(magnetic = null))
        assertReason(RouteCompassHeadingReason.REFERENCE_MISSING, fallback(reference = reference.copy(declinationDegrees = Double.NaN)))
    }

    @Test fun eachStreamMustBeFreshAndTheObservationUsesTheOlderTime() {
        assertEquals(500L, fallback(gravity.copy(observedAtMs = 500L), magnetic = magnetic.copy(observedAtMs = 750L)).observation?.observedAtElapsedRealtimeMs)
        assertReason(RouteCompassHeadingReason.STALE, fallback(gravity.copy(observedAtMs = 499L)))
        assertReason(RouteCompassHeadingReason.STALE, fallback(magnetic = magnetic.copy(observedAtMs = 499L)))
        assertReason(RouteCompassHeadingReason.SENSOR_SKEW, fallback(gravity.copy(observedAtMs = 749L)))
    }

    @Test fun futureNegativeAndOverflowingTimesDoNotPass() {
        listOf(-1L, Long.MIN_VALUE, now + 1, Long.MAX_VALUE).forEach {
            assertReason(RouteCompassHeadingReason.INVALID_TIMESTAMP, fallback(gravity.copy(observedAtMs = it)))
            assertReason(RouteCompassHeadingReason.INVALID_TIMESTAMP, fallback(magnetic = magnetic.copy(observedAtMs = it)))
        }
        assertReason(RouteCompassHeadingReason.INVALID_TIMESTAMP, fallback(nowMs = -1))
    }

    @Test fun bothSensorsNeedMediumOrHighQualityAndChangingGravityCannotBypassPoorMagneticQuality() {
        listOf(EarthOrientationAccuracy.LOW, EarthOrientationAccuracy.UNRELIABLE).forEach {
            assertReason(RouteCompassHeadingReason.QUALITY_LOW, fallback(gravity.copy(accuracy = it)))
            assertReason(RouteCompassHeadingReason.QUALITY_LOW, fallback(acceleration = gravity, magnetic = magnetic.copy(accuracy = it)))
        }
        assertNotNull(fallback(gravity.copy(accuracy = EarthOrientationAccuracy.MEDIUM), magnetic = magnetic.copy(accuracy = EarthOrientationAccuracy.MEDIUM)).observation)
    }

    @Test fun freeFallAndLargeAccelerationAreRejectedForBothSources() {
        listOf(0.0, 0.5, 7.0, 13.0, 25.0, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertReason(RouteCompassHeadingReason.GRAVITY_UNSTABLE, fallback(gravity.copy(y = it)))
            assertReason(RouteCompassHeadingReason.GRAVITY_UNSTABLE, fallback(gravity = null, acceleration = gravity.copy(y = it)))
        }
    }

    @Test fun rawAccelerationDetectsMotionHiddenByFusedGravity() {
        assertReason(RouteCompassHeadingReason.GRAVITY_UNSTABLE, fallback(acceleration = gravity.copy(x = 4.0)))
        assertReason(RouteCompassHeadingReason.GRAVITY_UNSTABLE, fallback(acceleration = gravity.copy(y = 15.0)))
        assertReason(RouteCompassHeadingReason.GRAVITY_UNSTABLE, fallback(acceleration = gravity.copy(x = Double.NaN)))
    }

    @Test fun staleRawAccelerationDoesNotInvalidateFreshFusedGravity() {
        assertNotNull(fallback(acceleration = gravity.copy(y = 15.0, observedAtMs = 499L)).observation)
    }

    @Test fun nonfiniteAndDisturbedMagneticFieldsAreRejectedWithoutReusingPriorOutput() {
        assertNotNull(fallback().observation)
        listOf(magnetic.copy(x = Double.NaN), magnetic.copy(z = Double.POSITIVE_INFINITY),
            sample(0.0, 0.0, 0.0), sample(0.0, -80.0, -60.0), sample(0.0, -20.0, -15.0)).forEach {
            assertReason(RouteCompassHeadingReason.MAGNETIC_FIELD_ANOMALY, fallback(magnetic = it))
        }
    }

    @Test fun parallelGravityAndMagneticVectorsCannotDefineNorth() {
        assertReason(RouteCompassHeadingReason.INVALID_ROTATION, fallback(magnetic = sample(0.0, 50.0, 0.0)))
    }

    @Test fun nearlyParallelGravityAndMagneticVectorsDoNotInventStableNorth() {
        listOf(0.01, 0.1, 1.0, 4.9).forEach { horizontal ->
            assertReason(RouteCompassHeadingReason.INVALID_ROTATION,
                fallback(magnetic = sample(0.0, sqrt(2500.0 - horizontal * horizontal), -horizontal)))
        }
        assertNotNull(fallback(magnetic = sample(0.0, sqrt(2500.0 - 36.0), -6.0)).observation)
    }

    @Test fun rearCameraVerticalRemainsUnavailable() {
        assertReason(RouteCompassHeadingReason.REAR_AXIS_VERTICAL,
            fallback(sample(0.0, 0.0, 9.80665), magnetic = sample(0.0, 30.0, -40.0)))
    }

    @Test fun declinationAddsAndNormalizesWithoutClaimingReportedError() {
        assertEquals(355.0, fallback(reference = reference.copy(declinationDegrees = -725.0)).observation!!.degreesTrueNorth, 0.00001)
    }

    @Test fun primaryMissingOrUnboundedErrorAllowsFallbackButExplicitPoorRvDoesNot() {
        listOf(RouteCompassHeadingReason.SENSOR_UNAVAILABLE, RouteCompassHeadingReason.ORIENTATION_MISSING,
            RouteCompassHeadingReason.HEADING_ACCURACY_UNAVAILABLE, RouteCompassHeadingReason.STALE).forEach {
            assertTrue(RouteCompassHeading.permitsFallback(RouteCompassHeadingResult(null, it)))
        }
        listOf(RouteCompassHeadingReason.QUALITY_LOW, RouteCompassHeadingReason.HEADING_ACCURACY,
            RouteCompassHeadingReason.INVALID_ROTATION, RouteCompassHeadingReason.REAR_AXIS_VERTICAL,
            RouteCompassHeadingReason.INVALID_TIMESTAMP).forEach {
            assertFalse(RouteCompassHeading.permitsFallback(RouteCompassHeadingResult(null, it)))
        }
        assertFalse(RouteCompassHeading.permitsFallback(fallback()))
    }

    @Test fun sourceQualityContractCannotForgeANumericErrorOrAnUnknownSource() {
        val observation = requireNotNull(fallback().observation)
        assertFalse(RouteFacingGuidance.isUsableObservation(observation.copy(headingAccuracyDegrees = 0.0), now))
        assertFalse(RouteFacingGuidance.isUsableObservation(observation.copy(reportedHeadingAccuracyDegrees = 0.0), now))
        assertFalse(RouteFacingGuidance.isUsableObservation(observation.copy(source = RouteFacingSource.ROTATION_VECTOR), now))
        assertFalse(RouteFacingGuidance.isUsableObservation(observation.copy(sensorQuality = RouteFacingSensorQuality.REPORTED_ANGULAR_ERROR), now))
        assertTrue(RouteFacingGuidance.isUsableObservation(RouteFacingObservation(0.0, 5.0, now), now))
        assertFalse(RouteFacingGuidance.isUsableObservation(RouteFacingObservation(0.0, null, now), now))
    }

    @Test fun fallbackUsesTwentyDegreePolicyGuardAndStillRejectsStaleObservation() {
        val observation = requireNotNull(fallback().observation)
        assertEquals(RouteFacingDirection.FRONT, RouteFacingGuidance.evaluate(24.0, observation, now).direction)
        assertEquals(RouteFacingDirection.RIGHT, RouteFacingGuidance.evaluate(25.0, observation, now).direction)
        assertEquals(RouteFacingDirection.BEHIND, RouteFacingGuidance.evaluate(156.0, observation, now).direction)
        assertEquals(RouteFacingDirection.LEFT, RouteFacingGuidance.evaluate(270.0, observation, now).direction)
        assertEquals(RouteFacingDirection.UNKNOWN, RouteFacingGuidance.evaluate(0.0, observation, now + 501L).direction)
    }

    @Test fun randomQuaternionOracleChecksAxesTiltRollDeclinationAndPolarProjection() {
        val random = Random(5513)
        var accepted = 0
        repeat(2_000) {
            // Independently rotate world gravity/magnetic vectors into device coordinates using q.
            val q = DoubleArray(4) { random.nextDouble(-1.0, 1.0) }
            val norm = sqrt(q.sumOf { it * it })
            for (i in q.indices) q[i] /= norm
            val inverse = doubleArrayOf(q[0], -q[1], -q[2], -q[3])
            val deviceGravity = rotate(inverse, doubleArrayOf(0.0, 0.0, 9.80665))
            val deviceMagnetic = rotate(inverse, doubleArrayOf(0.0, 30.0, -40.0))
            val rearWorld = rotate(q, doubleArrayOf(0.0, 0.0, -1.0))
            val horizontal = sqrt(rearWorld[0] * rearWorld[0] + rearWorld[1] * rearWorld[1])
            val result = fallback(sample(*deviceGravity), magnetic = sample(*deviceMagnetic), reference = reference.copy(declinationDegrees = -7.3))
            if (horizontal < 0.5) assertReason(RouteCompassHeadingReason.REAR_AXIS_VERTICAL, result)
            else {
                accepted++
                val expected = ((Math.toDegrees(atan2(rearWorld[0], rearWorld[1])) - 7.3) % 360.0 + 360.0) % 360.0
                val actual = requireNotNull(result.observation).degreesTrueNorth
                assertTrue(abs((actual - expected + 540.0) % 360.0 - 180.0) < 0.00001)
            }
        }
        assertTrue(accepted > 1_000)
    }

    private fun rotate(q: DoubleArray, v: DoubleArray): DoubleArray {
        fun multiply(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
            a[0]*b[0]-a[1]*b[1]-a[2]*b[2]-a[3]*b[3],
            a[0]*b[1]+a[1]*b[0]+a[2]*b[3]-a[3]*b[2],
            a[0]*b[2]-a[1]*b[3]+a[2]*b[0]+a[3]*b[1],
            a[0]*b[3]+a[1]*b[2]-a[2]*b[1]+a[3]*b[0],
        )
        return multiply(multiply(q, doubleArrayOf(0.0, *v)), doubleArrayOf(q[0], -q[1], -q[2], -q[3])).copyOfRange(1, 4)
    }

    private fun sample(vararg values: Double) = RouteCompassVectorSample(values[0], values[1], values[2], now, EarthOrientationAccuracy.HIGH)
    private fun fallback(
        gravity: RouteCompassVectorSample? = this.gravity,
        acceleration: RouteCompassVectorSample? = null,
        magnetic: RouteCompassVectorSample? = this.magnetic,
        reference: ChestMountedGeomagneticReference? = this.reference,
        nowMs: Long = now,
    ) = RouteCompassHeading.evaluateFallback(gravity, acceleration, magnetic, reference, nowMs)
    private fun assertReason(reason: RouteCompassHeadingReason, result: RouteCompassHeadingResult) {
        assertEquals(reason, result.reason)
        assertNull(result.observation)
    }
}

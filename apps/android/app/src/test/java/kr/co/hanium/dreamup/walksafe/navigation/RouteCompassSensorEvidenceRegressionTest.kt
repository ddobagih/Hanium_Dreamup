package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 08.txt #6, #9, #11, #12, #13, #19: sensor/route evidence that must widen or withhold direction. */
class RouteCompassSensorEvidenceRegressionTest {
    private val now = 1_000L
    private val reference = ChestMountedGeomagneticReference(0.0, 53.13, 50.0)
    private val gravity = RouteCompassVectorSample(0.0, 9.80665, 0.0, now, EarthOrientationAccuracy.HIGH)
    private val magnetic = RouteCompassVectorSample(0.0, -40.0, -30.0, now, EarthOrientationAccuracy.HIGH)

    // --- #6: a fresh-enough sample is not necessarily close to the current facing while turning.

    @Test fun fastRotationTimesSampleAgeWithholdsDirectionWithoutMovingTheSampleForward() {
        val turning = RouteFacingObservation(0.0, 5.0, now - 400L, angularRateDegreesPerSecond = 180.0)
        val result = RouteFacingGuidance.evaluate(0.0, turning, now)
        assertEquals(RouteFacingDirection.UNKNOWN, result.direction)
        assertNull(result.clockHour)
        // The same sample without measured rotation keeps the current behaviour.
        val still = turning.copy(angularRateDegreesPerSecond = 0.0)
        assertEquals(RouteFacingDirection.FRONT, RouteFacingGuidance.evaluate(0.0, still, now).direction)
        assertEquals(12, RouteFacingGuidance.evaluate(0.0, still, now).clockHour)
        assertEquals(RouteFacingGuidance.evaluate(0.0, turning.copy(angularRateDegreesPerSecond = null), now),
            RouteFacingGuidance.evaluate(0.0, still, now))
    }

    @Test fun moderateRotationKeepsANominalClockWithoutPromotingReportedPrecision() {
        val observation = RouteFacingObservation(0.0, 3.0, now - 300L, angularRateDegreesPerSecond = 30.0)
        val result = RouteFacingGuidance.evaluate(270.0, observation, now)
        assertEquals(RouteFacingDirection.LEFT, result.direction)
        assertEquals(9, result.clockHour)
        assertFalse(result.clockPrecisionAvailable)
        assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK, result.clockReferenceKind)
        assertEquals(now - 300L, observation.observedAtElapsedRealtimeMs)
        assertEquals(3.0, observation.headingAccuracyDegrees!!, 0.0)
        assertNull(result.clockRange)
        assertEquals(9, RouteFacingGuidance.evaluate(270.0, observation.copy(angularRateDegreesPerSecond = 0.0), now).clockHour)
    }

    @Test fun invalidAngularRateIsNotUsable() {
        listOf(Double.NaN, -1.0, Double.POSITIVE_INFINITY).forEach {
            assertFalse(RouteFacingGuidance.isUsableObservation(RouteFacingObservation(0.0, 5.0, now, angularRateDegreesPerSecond = it), now))
        }
    }

    // --- #9: rotation vector also fuses the magnetometer.

    @Test fun freshStrongMagneticAnomalyLimitsRotationVectorToCoarseDirection() {
        val rv = RouteCompassHeadingResult(RouteFacingObservation(0.0, 3.0, now), RouteCompassHeadingReason.VALID)
        val anomalous = magnetic.copy(x = 0.0, y = -120.0, z = -90.0)
        val checked = RouteCompassHeading.crossCheckMagneticField(rv, anomalous, reference, now)
        val observation = requireNotNull(checked.observation)
        assertTrue(observation.magneticFieldSuspect)
        assertTrue(RouteFacingGuidance.isUsableObservation(observation, now))
        // No clock number; the sector uses the fallback margin (0° vs 24° is still FRONT, 26° is not).
        assertNull(RouteFacingGuidance.evaluate(0.0, observation, now).clockHour)
        assertEquals(RouteFacingDirection.FRONT, RouteFacingGuidance.evaluate(24.0, observation, now).direction)
        assertEquals(RouteFacingDirection.RIGHT, RouteFacingGuidance.evaluate(26.0, observation, now).direction)
        assertEquals(RouteFacingDirection.FRONT, RouteFacingGuidance.evaluate(26.0, rv.observation, now).direction)
    }

    @Test fun absentStaleOrNormalMagneticFieldDoesNotBlockAHealthyRotationVector() {
        val rv = RouteCompassHeadingResult(RouteFacingObservation(0.0, 3.0, now), RouteCompassHeadingReason.VALID)
        assertEquals(rv, RouteCompassHeading.crossCheckMagneticField(rv, null, reference, now))
        val checked = RouteCompassHeading.crossCheckMagneticField(rv, magnetic, reference, now)
        assertEquals(rv.observation, checked.observation)
        assertEquals(rv.reason, checked.reason)
        val evidence = requireNotNull(checked.checkEvidence)
        assertEquals(50.0, requireNotNull(evidence.magneticFieldMicrotesla), 0.0)
        assertEquals(50.0, requireNotNull(evidence.expectedMagneticFieldMicrotesla), 0.0)
        assertEquals(0.0, requireNotNull(evidence.magneticFieldDeviationMicrotesla), 0.0)
        assertEquals(12.5, requireNotNull(evidence.magneticFieldAllowanceMicrotesla), 0.0)
        assertNull(evidence.rejectedCheck)
        assertNull(evidence.forwardHorizontal)
        assertEquals(rv, RouteCompassHeading.crossCheckMagneticField(rv, magnetic.copy(y = -120.0, observedAtMs = now - 501L), reference, now))
        assertEquals(rv, RouteCompassHeading.crossCheckMagneticField(rv, magnetic.copy(y = -120.0), null, now))
        assertEquals(12, RouteFacingGuidance.evaluate(0.0, rv.observation, now).clockHour)
    }

    // --- #11: accelerometer basis requires short-interval stability.

    @Test fun walkingImpactAccelerationIsNotUsedAsGravity() {
        val walking = listOf(-3.0, 3.0, -3.0, 3.0).mapIndexed { index, x ->
            gravity.copy(x = x, observedAtMs = now - 150L + index * 50L)
        }
        assertEquals(RouteCompassHeadingReason.GRAVITY_UNSTABLE, fallback(null, walking).reason)
    }

    @Test fun aSingleInstantaneousAccelerationCannotShowStability() {
        assertEquals(RouteCompassHeadingReason.GRAVITY_UNSTABLE, fallback(null, listOf(gravity)).reason)
        assertEquals(RouteCompassHeadingReason.GRAVITY_UNSTABLE,
            fallback(null, listOf(gravity.copy(observedAtMs = now - 50L), gravity)).reason)
    }

    @Test fun steadyAccelerationWindowReportsItsOldestSampleTime() {
        val steady = listOf(now - 180L, now - 90L, now).map { gravity.copy(observedAtMs = it) }
        val observation = requireNotNull(fallback(null, steady).observation)
        assertEquals(RouteFacingSource.ACCELEROMETER_MAGNETIC, observation.source)
        assertEquals(now - 180L, observation.observedAtElapsedRealtimeMs)
        // Samples older than the window are ignored rather than averaged in.
        val withOld = steady + gravity.copy(x = 5.0, observedAtMs = now - 400L)
        assertEquals(now - 180L, requireNotNull(fallback(null, withOld).observation).observedAtElapsedRealtimeMs)
    }

    // --- #12: a skipped motion check is not reported as a passed check.

    @Test fun missingStaleOrSkewedRawAccelerationIsMarkedAsNotMotionChecked() {
        listOf(emptyList(), listOf(gravity.copy(observedAtMs = now - 501L)), listOf(gravity.copy(observedAtMs = now - 260L)))
            .forEach { acceleration ->
                assertEquals(RouteFacingSensorQuality.CALIBRATED_WITHOUT_MOTION_CHECK,
                    fallback(gravity, acceleration).observation?.sensorQuality)
            }
        assertEquals(RouteFacingSensorQuality.CALIBRATED_SENSOR_CHECKS,
            fallback(gravity, listOf(gravity)).observation?.sensorQuality)
    }

    // --- #13: tolerated time difference shrinks while rotating; closest samples are paired.

    @Test fun rotationShrinksTheToleratedSensorTimeDifference() {
        val skewed = listOf(magnetic.copy(observedAtMs = now - 200L))
        assertNotNull(fallback(gravity, emptyList(), skewed, angularRate = 0.0).observation)
        assertNotNull(fallback(gravity, emptyList(), skewed, angularRate = null).observation)
        assertEquals(RouteCompassHeadingReason.SENSOR_SKEW, fallback(gravity, emptyList(), skewed, angularRate = 90.0).reason)
        assertEquals(111L, RouteCompassHeading.maximumSkewMs(90.0))
        assertEquals(250L, RouteCompassHeading.maximumSkewMs(null))
    }

    @Test fun eachBasisIsPairedWithTheClosestMagneticSampleAndKeepsTheOlderTime() {
        val history = listOf(magnetic.copy(observedAtMs = now - 200L), magnetic.copy(observedAtMs = now - 20L))
        val observation = requireNotNull(fallback(gravity, emptyList(), history, angularRate = 90.0).observation)
        assertEquals(now - 20L, observation.observedAtElapsedRealtimeMs)
        assertEquals(90.0, observation.angularRateDegreesPerSecond!!, 0.0)
        assertEquals(magnetic, RouteCompassHeading.closest(listOf(magnetic.copy(observedAtMs = now - 2L), magnetic), now - 1L))
    }

    // --- #19: route bearing uncertainty is combined with the sensor margin.

    @Test fun routeCandidatesThatRequireTheSameActionKeepANominalClockWithoutReportedPrecision() {
        val facing = RouteFacingObservation(0.0, 2.0, now)
        val agreed = RouteFacingGuidance.evaluate(17.0, facing, now, routeBearingUncertaintyDegrees = 7.0)
        assertEquals(RouteFacingDirection.FRONT, agreed.direction)
        assertEquals(1, agreed.clockHour)
        assertFalse(agreed.clockPrecisionAvailable)
        assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK, agreed.clockReferenceKind)
        assertNull(agreed.clockRange)
        assertEquals(1, RouteFacingGuidance.evaluate(30.0, facing, now).clockHour)
        val uncertain = RouteFacingGuidance.evaluate(30.0, facing, now, routeBearingUncertaintyDegrees = 9.0)
        assertEquals(1, uncertain.clockHour)
        assertFalse(uncertain.clockPrecisionAvailable)
        assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK, uncertain.clockReferenceKind)
    }

    @Test fun routeCandidatesThatStraddleASectorEdgeWithholdTheDirection() {
        // Candidates on one side of the facing still share the same correction side.
        val facing = RouteFacingObservation(0.0, 2.0, now)
        assertEquals(RouteFacingDirection.RIGHT, RouteFacingGuidance.evaluate(45.0, facing, now, 14.0).direction)
        // A coarse sensor plus candidates reaching across straight ahead ask for different actions.
        val coarse = RouteFacingObservation(0.0, 25.0, now)
        assertEquals(RouteFacingDirection.FRONT, RouteFacingGuidance.evaluate(5.0, coarse, now).direction)
        assertEquals(RouteFacingDirection.UNKNOWN, RouteFacingGuidance.evaluate(5.0, coarse, now, 15.0).direction)
        assertEquals(RouteFacingDirection.UNKNOWN, RouteFacingGuidance.evaluate(0.0, facing, now, Double.NaN).direction)
    }

    private fun fallback(
        gravity: RouteCompassVectorSample?,
        accelerometer: List<RouteCompassVectorSample>,
        magnetic: List<RouteCompassVectorSample> = listOf(this.magnetic),
        angularRate: Double? = null,
    ) = RouteCompassHeading.evaluateFallback(gravity, accelerometer, magnetic, reference, now, angularRate)
}

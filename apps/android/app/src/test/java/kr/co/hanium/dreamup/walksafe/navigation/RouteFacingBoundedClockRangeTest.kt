package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Range availability does not change the existing single-hour or sensor-quality contracts. */
class RouteFacingBoundedClockRangeTest {
    @Test fun reportedTwelveDegreeErrorSupportsANominalClockWithoutMeasuredPrecision() {
        val measured = rv(12f)
        assertEquals(RouteCompassHeadingReason.VALID, measured.reason)
        val result = RouteFacingGuidance.evaluate(0.0, measured.observation, 1_000L)
        assertNominal(result, 12)
    }

    @Test fun sectorEdgeHasOneNominalClockWithoutPretendingToHaveMeasuredPrecision() {
        val result = RouteFacingGuidance.evaluate(15.0, rv(8f).observation, 1_000L)
        assertNominal(result, 1)
    }

    @Test fun rearAxisProjectionKeepsTheActualEnlargedErrorBesideTheNominalClock() {
        val tilt = Math.toRadians(60.0)
        val orientation = DeviceEarthOrientation(
            RotationMatrix3(1f, 0f, 0f, 0f, (-sin(tilt)).toFloat(), -0.5f,
                0f, 0.5f, (-sin(tilt)).toFloat()),
            1_000L, 5f, EarthOrientationAccuracy.HIGH)
        val measured = RouteCompassHeading.evaluate(orientation, 0.0, 1_000L)
        assertEquals(RouteCompassHeadingReason.VALID, measured.reason)
        assertEquals(5.0, requireNotNull(measured.observation).reportedHeadingAccuracyDegrees!!, 0.0001)
        assertEquals(10.0, measured.observation!!.headingAccuracyDegrees!!, 0.0001)
        val result = RouteFacingGuidance.evaluate(0.0, measured.observation, 1_000L)
        assertNominal(result, 12)
    }

    @Test fun nominalClocksWrapAcrossTwelveAndSix() {
        assertNominal(RouteFacingGuidance.evaluate(0.0, rv(12f).observation, 1_000L), 12)
        assertNominal(RouteFacingGuidance.evaluate(180.0, rv(12f).observation, 1_000L), 6)
        assertNominal(RouteFacingGuidance.evaluate(90.0, rv(12f).observation, 1_000L), 3)
    }

    @Test fun exactHoursAndTheirPrecisionContractRemainUnchanged() {
        for (hour in 1..12) {
            val result = RouteFacingGuidance.evaluate((hour % 12) * 30.0, rv(0.1f).observation, 1_000L)
            assertEquals(hour, result.clockHour)
            assertTrue(result.clockPrecisionAvailable)
            assertTrue(result.hasClockReference)
            assertNull("An exact hour does not also need a range", result.clockRange)
        }
    }

    @Test fun aCoarseSupportedDirectionCanOfferANominalClockWithoutReducingItsError() {
        val observation = rv(30f).observation
        assertTrue(RouteFacingGuidance.isUsableObservation(observation, 1_000L))
        val result = RouteFacingGuidance.evaluate(45.0, observation, 1_000L)
        assertEquals(RouteFacingDirection.RIGHT, result.direction)
        assertNominal(result, 2)
        assertEquals(30.0, observation!!.reportedHeadingAccuracyDegrees!!, 0.0)
    }

    @Test fun rotationSinceTheOriginalSampleCanRemoveNominalAvailabilityWithoutRestampingIt() {
        val observation = requireNotNull(rv(12f).observation).copy(angularRateDegreesPerSecond = 100.0)
        assertNominal(RouteFacingGuidance.evaluate(0.0, observation, 1_000L), 12)
        assertTrue(RouteFacingGuidance.isUsableObservation(observation, 1_500L))
        assertNoNumericReference(RouteFacingGuidance.evaluate(0.0, observation, 1_500L))
        assertEquals(1_000L, observation.observedAtElapsedRealtimeMs)
        assertEquals(1_500L, RouteFacingGuidance.observationValidUntilElapsedRealtimeMs(observation, 1_000L))
    }

    @Test fun routeBearingUncertaintyIsIncludedRatherThanHiddenByRangeFormatting() {
        val observation = rv(12f).observation
        assertNominal(RouteFacingGuidance.evaluate(0.0, observation, 1_000L), 12)
        assertNoNumericReference(RouteFacingGuidance.evaluate(0.0, observation, 1_000L, 40.0))
        assertNoNumericReference(RouteFacingGuidance.evaluate(null, observation, 1_000L))
        assertNoNumericReference(RouteFacingGuidance.evaluate(0.0, observation, 1_000L, Double.NaN))
    }

    @Test fun realCalibratedFallbackKeepsErrorNullBesideItsNominalClock() {
        val gravity = RouteCompassVectorSample(0.0, 9.80665, 0.0, 1_000L, EarthOrientationAccuracy.HIGH)
        val magnetic = RouteCompassVectorSample(0.0, 0.0, -50.0, 1_000L, EarthOrientationAccuracy.HIGH)
        val measured = RouteCompassHeading.evaluateFallback(gravity, gravity, magnetic,
            ChestMountedGeomagneticReference(0.0, 0.0, 50.0), 1_000L)
        val observation = requireNotNull(measured.observation)
        assertEquals(RouteFacingSource.GRAVITY_MAGNETIC, observation.source)
        assertNull(observation.reportedHeadingAccuracyDegrees)
        assertNull(observation.headingAccuracyDegrees)
        assertTrue(RouteFacingGuidance.isUsableObservation(observation, 1_000L))
        assertNominal(RouteFacingGuidance.evaluate(0.0, observation, 1_000L), 12)
    }

    @Test fun magneticAnomalyKeepsItsEvidenceButCannotAuthorizeAnHourOrRange() {
        val measured = RouteCompassHeading.crossCheckMagneticField(rv(12f),
            RouteCompassVectorSample(0.0, 0.0, -100.0, 1_000L, EarthOrientationAccuracy.HIGH),
            ChestMountedGeomagneticReference(0.0, 0.0, 50.0), 1_000L)
        assertEquals(RouteCompassHeadingReason.VALID, measured.reason)
        assertTrue(requireNotNull(measured.observation).magneticFieldSuspect)
        assertNoNumericReference(RouteFacingGuidance.evaluate(0.0, measured.observation, 1_000L))
    }

    @Test fun expiredFutureInvalidOrMissingObservationsStayNonNumeric() {
        val observation = requireNotNull(rv(12f).observation)
        assertNominal(RouteFacingGuidance.evaluate(0.0, observation, 1_500L), 12)
        assertNoNumericReference(RouteFacingGuidance.evaluate(0.0, observation, 1_501L))
        assertNoNumericReference(RouteFacingGuidance.evaluate(0.0, observation, 999L))
        assertNoNumericReference(RouteFacingGuidance.evaluate(0.0, null, 1_000L))
        for (error in listOf(Float.NaN, -1f, 31f)) {
            val rejected = rv(error)
            assertNull(rejected.observation)
            assertNoNumericReference(RouteFacingGuidance.evaluate(0.0, rejected.observation, 1_000L))
        }
    }

    private fun assertNominal(result: RouteFacingGuidanceResult, hour: Int) {
        assertEquals(hour, result.clockHour)
        assertNull(result.clockRange)
        assertFalse(result.clockPrecisionAvailable)
        assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK, result.clockReferenceKind)
        assertTrue(result.hasClockReference)
    }

    private fun assertNoNumericReference(result: RouteFacingGuidanceResult) {
        assertNull(result.clockHour)
        assertNull(result.clockRange)
        assertFalse(result.hasClockReference)
    }

    private fun rv(error: Float, heading: Double = 0.0, at: Long = 1_000L): RouteCompassHeadingResult {
        val angle = Math.toRadians(heading)
        val rotation = RotationMatrix3(cos(angle).toFloat(), 0f, (-sin(angle)).toFloat(),
            (-sin(angle)).toFloat(), 0f, (-cos(angle)).toFloat(), 0f, 1f, 0f)
        return RouteCompassHeading.evaluate(DeviceEarthOrientation(rotation, at, error, EarthOrientationAccuracy.HIGH), 0.0, at)
    }
}

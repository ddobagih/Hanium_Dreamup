package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.GnssSatelliteSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sqrt

class PositioningAccuracySemanticsTest {
    @Test
    fun smallReportedRadiusIsDistinctFromTheFlooredNominalModelSigma() {
        val snapshot = PositioningCoordinator().observeGnss(fix(1.0))
        val raw = requireNotNull(snapshot.raw)
        assertEquals(ReportedAccuracyRadius68M(1.0), raw.reportedAccuracyRadius68M)
        assertEquals(1.0, requireNotNull(raw.effectiveHorizontalAccuracyM), 0.0)
        assertEquals(MeasurementAxisStdDevM(1.5), raw.nominalMeasurementAxisStdDevM)
        assertEquals(PositionMaxAxisSigmaM(1.5), snapshot.filtered?.positionMaxAxisSigmaM)
        assertEquals(PositionMaxAxisSigmaM(1.5), snapshot.gnssUpdate?.estimate?.positionMaxAxisSigmaM)
    }

    @Test
    fun modelFloorIsAppliedBeforeTheNoiseWeightExactlyOnce() {
        val snapshot = PositioningCoordinator().observeGnss(fix(1.0, weight = 4.0))
        assertEquals(ReportedAccuracyRadius68M(1.0), snapshot.raw?.reportedAccuracyRadius68M)
        assertEquals(2.0, requireNotNull(snapshot.raw?.effectiveHorizontalAccuracyM), 0.0)
        assertEquals(MeasurementAxisStdDevM(3.0), snapshot.raw?.nominalMeasurementAxisStdDevM)
        assertEquals(PositionMaxAxisSigmaM(3.0), snapshot.filtered?.positionMaxAxisSigmaM)
    }

    @Test
    fun missingRadiusKeepsUnknownReportedRadiusAndWeightsTheExistingDefault() {
        val snapshot = PositioningCoordinator().observeGnss(fix(null, weight = 4.0))
        assertNull(snapshot.raw?.reportedAccuracyRadius68M)
        assertNull(snapshot.raw?.effectiveHorizontalAccuracyM)
        assertEquals(MeasurementAxisStdDevM(100.0), snapshot.raw?.nominalMeasurementAxisStdDevM)
        assertEquals(PositionMaxAxisSigmaM(100.0), snapshot.filtered?.positionMaxAxisSigmaM)
    }

    @Test
    fun coordinatorUsesItsEstimatorConfigurationForSmallZeroAndMissingRadii() {
        val config = PedestrianPositionEstimatorConfig(minimumGnssSigmaM = 2.5, missingAccuracySigmaM = 7.0)
        listOf(0.0 to 7.5, 1.0 to 7.5, null to 21.0).forEach { (radius, expectedSigma) ->
            val coordinator = PositioningCoordinator(estimator = PedestrianPositionEstimator(config))
            val snapshot = coordinator.observeGnss(fix(radius, weight = 9.0))
            assertEquals(MeasurementAxisStdDevM(expectedSigma), snapshot.raw?.nominalMeasurementAxisStdDevM)
            assertEquals(PositionMaxAxisSigmaM(expectedSigma), snapshot.filtered?.positionMaxAxisSigmaM)
            assertEquals(radius?.times(3.0), snapshot.raw?.effectiveHorizontalAccuracyM)
        }
    }

    @Test
    fun callerAndSignalWeightsUseTheirMaximumRatherThanTheirProduct() {
        listOf(1.0, 16.0).forEach { callerWeight ->
            val coordinator = PositioningCoordinator()
            repeat(3) { index ->
                coordinator.addGnssSignalEpoch(
                    elapsedRealtimeNanos = (index + 1L) * 1_000_000_000L,
                    signals = (1..4).map { svid ->
                        GnssSatelliteSignal(1, svid, 1_575_420_000.0, 10.0, true)
                    },
                )
            }
            val snapshot = coordinator.observeGnss(fix(1.0, weight = callerWeight, at = 3_100L))
            assertTrue(snapshot.gnssQuality.measurementNoiseMultiplier > 1.0)
            val weight = maxOf(callerWeight, snapshot.gnssQuality.measurementNoiseMultiplier)
            assertEquals(1.5 * sqrt(weight), requireNotNull(snapshot.raw?.nominalMeasurementAxisStdDevM).meters, 1e-12)
            assertEquals(sqrt(weight), requireNotNull(snapshot.raw?.effectiveHorizontalAccuracyM), 1e-12)
            assertEquals(1.5 * sqrt(weight), requireNotNull(snapshot.filtered?.positionMaxAxisSigmaM).meters, 1e-12)
        }
    }

    @Test
    fun nominalModelSigmaDoesNotPretendToIncludeTheSoftInnovationGate() {
        val coordinator = PositioningCoordinator()
        coordinator.observeGnss(fix(3.0))
        val longitude = 127.0 + 30.0 / (LocalGeodesy.METERS_PER_DEGREE * cos(Math.toRadians(37.5)))
        val snapshot = coordinator.observeGnss(fix(3.0, at = 2_000L).copy(longitude = longitude))
        assertEquals(GnssObservationDisposition.DOWNWEIGHTED, snapshot.gnssUpdate?.disposition)
        assertTrue(requireNotNull(snapshot.gnssUpdate?.gateWeight) in 0.0..0.999999)
        assertEquals(MeasurementAxisStdDevM(3.0), snapshot.raw?.nominalMeasurementAxisStdDevM)
        assertEquals(3.0, requireNotNull(snapshot.raw?.effectiveHorizontalAccuracyM), 0.0)
    }

    @Test
    fun equalNumbersHaveSeparateRadiusModelAndPosteriorTypesWithoutConversion() {
        val snapshot = PositioningCoordinator().observeGnss(fix(10.0))
        assertEquals(ReportedAccuracyRadius68M(10.0), snapshot.raw?.reportedAccuracyRadius68M)
        assertEquals(MeasurementAxisStdDevM(10.0), snapshot.raw?.nominalMeasurementAxisStdDevM)
        assertEquals(PositionMaxAxisSigmaM(10.0), snapshot.filtered?.positionMaxAxisSigmaM)
    }

    @Test
    fun qualityBoundariesStillUseTheUnconvertedPosteriorMaxAxisSigma() {
        listOf(
            2.0 to PositionQuality.HIGH,
            2.000001 to PositionQuality.MEDIUM,
            5.0 to PositionQuality.MEDIUM,
            5.000001 to PositionQuality.LOW,
            15.0 to PositionQuality.LOW,
            15.000001 to PositionQuality.UNAVAILABLE,
        ).forEach { (radius, expectedQuality) ->
            val snapshot = PositioningCoordinator().observeGnss(fix(radius))
            assertEquals(PositionMaxAxisSigmaM(radius), snapshot.filtered?.positionMaxAxisSigmaM)
            assertEquals(expectedQuality, snapshot.quality)
        }
    }

    @Test
    fun invalidNumericInputsKeepTheirOriginalRejectionReasonsAndNoNominalSigma() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0).forEach { radius ->
            val snapshot = PositioningCoordinator().observeGnss(fix(radius))
            assertEquals(GnssHardRejectReason.INVALID_ACCURACY, snapshot.gnssUpdate?.hardRejectReason)
            assertNull(snapshot.raw?.reportedAccuracyRadius68M)
            assertNull(snapshot.raw?.nominalMeasurementAxisStdDevM)
        }
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.5).forEach { weight ->
            val snapshot = PositioningCoordinator().observeGnss(fix(1.0, weight = weight))
            assertEquals(GnssHardRejectReason.INVALID_ACCURACY, snapshot.gnssUpdate?.hardRejectReason)
            assertNull(snapshot.raw?.nominalMeasurementAxisStdDevM)
        }
        val overflow = PositioningCoordinator().observeGnss(fix(Double.MAX_VALUE))
        assertEquals(GnssHardRejectReason.NUMERICAL_FAILURE, overflow.gnssUpdate?.hardRejectReason)
        assertNull(overflow.raw?.nominalMeasurementAxisStdDevM)
    }

    @Test
    fun legacyConstructorsComponentsAndCopyKeepTheirOriginalScalarOrdering() {
        val observation = GnssPositionObservation(37.5, 127.0, 1.0, 1_000L)
        val (latitude, longitude, radius, timestamp) = observation.copy()
        assertEquals(37.5, latitude, 0.0)
        assertEquals(127.0, longitude, 0.0)
        assertEquals(1.0, requireNotNull(radius), 0.0)
        assertEquals(1_000L, timestamp)
        val raw = PositioningRawFix(PositionCoordinate(latitude, longitude), null, radius, radius, timestamp)
        assertNull(raw.copy().nominalMeasurementAxisStdDevM)
        assertEquals(radius, raw.component3())
        val trusted = TrustedLocation(latitude, longitude, 1f, timestamp)
        val routePosition = FilteredRoutePosition(RoutePoint(latitude, longitude), 1.0, timestamp)
        assertEquals(HorizontalUncertaintyKind.LEGACY_UNSPECIFIED, trusted.copy().uncertaintyKind)
        assertEquals(HorizontalUncertaintyKind.LEGACY_UNSPECIFIED, routePosition.copy().uncertaintyKind)
        assertEquals(1f, trusted.component3(), 0f)
        assertEquals(timestamp, routePosition.component3())
        assertNull(trusted.reportedAccuracyRadius68M)
        assertNull(trusted.positionMaxAxisSigmaM)
        assertNull(routePosition.reportedAccuracyRadius68M)
        assertNull(routePosition.positionMaxAxisSigmaM)
    }

    private fun fix(radius: Double?, weight: Double = 1.0, at: Long = 1_000L) =
        GnssPositionObservation(37.5, 127.0, radius, at, measurementNoiseMultiplier = weight)
}

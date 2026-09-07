package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteCorridorMatcherTest {
    @Test
    fun straightSegmentReturnsASeparateSnappedCoordinateAndProgress() {
        val matcher = RouteCorridorMatcher()
        val filtered = RoutePoint(37.00005, 127.0005)

        val result = matcher.match(
            routeId = "straight",
            polyline = listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0, 127.001)),
            position = position(filtered, accuracyM = 10.0, headingDeg = 90.0),
        )

        assertEquals(filtered, result.filteredPoint)
        assertNotEquals(filtered, result.matchedPoint)
        assertEquals(37.0, requireNotNull(result.matchedPoint).latitude, 0.000001)
        assertEquals(127.0005, requireNotNull(result.matchedPoint).longitude, 0.000001)
        assertEquals(0, result.segmentIndex)
        assertEquals(0.5, requireNotNull(result.segmentFraction), 0.01)
        assertTrue(requireNotNull(result.crossTrackDistanceM) in 5.0..6.2)
        assertTrue(requireNotNull(result.geometricProgressM) in 43.0..46.0)
        assertEquals(90f, requireNotNull(result.bearingDeg), 1f)
        assertEquals(RouteMatchQuality.HIGH, result.quality)
    }

    @Test
    fun cornerUsesHeadingAndGeometryToSelectTheOutgoingSegment() {
        val matcher = RouteCorridorMatcher()
        val route = listOf(
            RoutePoint(37.0, 127.0),
            RoutePoint(37.0, 127.001),
            RoutePoint(37.001, 127.001),
        )

        val result = matcher.match(
            routeId = "corner",
            polyline = route,
            position = position(RoutePoint(37.0004, 127.00103), accuracyM = 8.0, headingDeg = 0.0),
        )

        assertEquals(1, result.segmentIndex)
        assertEquals(127.001, requireNotNull(result.matchedPoint).longitude, 0.000001)
        assertEquals(0f, requireNotNull(result.bearingDeg), 1f)
        assertTrue(result.quality == RouteMatchQuality.HIGH || result.quality == RouteMatchQuality.MEDIUM)
    }

    @Test
    fun zeroLengthSegmentIsSkippedWithoutChangingOriginalSegmentIndexes() {
        val matcher = RouteCorridorMatcher()
        val duplicate = RoutePoint(37.0, 127.0)

        val result = matcher.match(
            routeId = "duplicate",
            polyline = listOf(duplicate, duplicate, RoutePoint(37.0, 127.001)),
            position = position(RoutePoint(37.0, 127.0004), accuracyM = 4.0, headingDeg = 90.0),
        )

        assertEquals(1, result.segmentIndex)
        assertEquals(RouteMatchQuality.HIGH, result.quality)
    }

    @Test
    fun nonAdjacentIntersectionBranchRequiresTwoClearlyBetterSamples() {
        val matcher = RouteCorridorMatcher()
        val route = crossingRoute()
        matcher.match(
            routeId = "crossing",
            polyline = route,
            position = position(RoutePoint(37.0, 126.9995), accuracyM = 3.0, headingDeg = 90.0, nowMs = 1_000L),
        )

        val first = matcher.match(
            routeId = "crossing",
            polyline = route,
            position = position(RoutePoint(37.0, 127.0), accuracyM = 3.0, headingDeg = 180.0, nowMs = 2_000L),
            previousProgressM = 300.0,
        )
        val confirmed = matcher.match(
            routeId = "crossing",
            polyline = route,
            position = position(RoutePoint(37.0, 127.0), accuracyM = 3.0, headingDeg = 180.0, nowMs = 3_000L),
            previousProgressM = 300.0,
        )

        assertEquals(RouteMatchReason.BRANCH_SWITCH_PENDING, first.reason)
        assertEquals(RouteMatchQuality.LOW, first.quality)
        assertEquals(0, first.segmentIndex)
        assertEquals(2, confirmed.segmentIndex)
        assertEquals(RouteMatchReason.MATCHED, confirmed.reason)
        assertTrue(confirmed.quality == RouteMatchQuality.HIGH || confirmed.quality == RouteMatchQuality.MEDIUM)
    }

    @Test
    fun parallelSegmentsWithoutEvidenceReturnLowAmbiguousConfidence() {
        val matcher = RouteCorridorMatcher()
        val route = listOf(
            RoutePoint(37.0, 127.0),
            RoutePoint(37.0, 127.001),
            RoutePoint(37.00005, 127.001),
            RoutePoint(37.00005, 127.0),
        )

        val result = matcher.match(
            routeId = "parallel",
            polyline = route,
            position = position(RoutePoint(37.000025, 127.0005), accuracyM = 5.0, headingDeg = null),
        )

        assertNotNull(result.matchedPoint)
        assertEquals(RouteMatchQuality.LOW, result.quality)
        assertEquals(RouteMatchReason.AMBIGUOUS_CANDIDATES, result.reason)
        assertTrue(result.confidence < 0.4)
    }

    @Test
    fun weakAbsoluteFitInsideTheCorridorReturnsLowConfidence() {
        val matcher = RouteCorridorMatcher()

        val result = matcher.match(
            routeId = "weak-fit",
            polyline = listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0, 127.001)),
            position = position(RoutePoint(37.000108, 127.0005), accuracyM = 3.0, headingDeg = 90.0),
        )

        assertNotNull(result.matchedPoint)
        assertEquals(RouteMatchQuality.LOW, result.quality)
        assertEquals(RouteMatchReason.LOW_CONFIDENCE, result.reason)
    }

    @Test
    fun pointOutsideTheUncertaintyAwareCorridorIsUnmatched() {
        val matcher = RouteCorridorMatcher()

        val result = matcher.match(
            routeId = "outside",
            polyline = listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0, 127.001)),
            position = position(RoutePoint(37.0006, 127.0005), accuracyM = 3.0, headingDeg = 90.0),
        )

        assertEquals(RouteMatchQuality.UNMATCHED, result.quality)
        assertEquals(RouteMatchReason.CORRIDOR_OUTSIDE, result.reason)
        assertNull(result.matchedPoint)
        assertNotNull(result.crossTrackDistanceM)
    }

    @Test
    fun resetClearsPendingBranchHysteresis() {
        val matcher = RouteCorridorMatcher()
        val route = crossingRoute()
        matcher.match(
            routeId = "reset",
            polyline = route,
            position = position(RoutePoint(37.0, 126.9995), accuracyM = 3.0, headingDeg = 90.0, nowMs = 1_000L),
        )
        val pending = matcher.match(
            routeId = "reset",
            polyline = route,
            position = position(RoutePoint(37.0, 127.0), accuracyM = 3.0, headingDeg = 180.0, nowMs = 2_000L),
            previousProgressM = 300.0,
        )

        matcher.reset()
        val afterReset = matcher.match(
            routeId = "reset",
            polyline = route,
            position = position(RoutePoint(37.0, 127.0), accuracyM = 3.0, headingDeg = 180.0, nowMs = 2_000L),
            previousProgressM = 300.0,
        )

        assertEquals(RouteMatchReason.BRANCH_SWITCH_PENDING, pending.reason)
        assertEquals(2, afterReset.segmentIndex)
        assertEquals(RouteMatchReason.MATCHED, afterReset.reason)
    }

    @Test
    fun duplicateAndOutOfOrderSamplesDoNotAdvanceBranchConfirmation() {
        val matcher = RouteCorridorMatcher()
        val route = crossingRoute()
        matcher.match(
            routeId = "ordered",
            polyline = route,
            position = position(RoutePoint(37.0, 126.9995), 3.0, 90.0, nowMs = 1_000L),
        )
        val first = matcher.match(
            routeId = "ordered",
            polyline = route,
            position = position(RoutePoint(37.0, 127.0), 3.0, 180.0, nowMs = 2_000L),
            previousProgressM = 300.0,
        )

        val duplicate = matcher.match(
            routeId = "ordered",
            polyline = route,
            position = position(RoutePoint(37.0, 127.0), 3.0, 180.0, nowMs = 2_000L),
            previousProgressM = 300.0,
        )
        val outOfOrder = matcher.match(
            routeId = "ordered",
            polyline = route,
            position = position(RoutePoint(37.0, 127.0), 3.0, 180.0, nowMs = 1_500L),
            previousProgressM = 300.0,
        )
        val nextFresh = matcher.match(
            routeId = "ordered",
            polyline = route,
            position = position(RoutePoint(37.0, 127.0), 3.0, 180.0, nowMs = 3_000L),
            previousProgressM = 300.0,
        )

        assertEquals(RouteMatchReason.BRANCH_SWITCH_PENDING, first.reason)
        assertEquals(RouteMatchReason.STALE_SAMPLE, duplicate.reason)
        assertEquals(RouteMatchReason.STALE_SAMPLE, outOfOrder.reason)
        assertEquals(RouteMatchQuality.UNMATCHED, duplicate.quality)
        assertEquals(2, nextFresh.segmentIndex)
        assertEquals(RouteMatchReason.MATCHED, nextFresh.reason)
    }

    @Test
    fun positioningInterruptionClearsPendingBranchConfirmationOnly() {
        val matcher = RouteCorridorMatcher()
        val route = crossingRoute()
        matcher.match(
            routeId = "interrupted",
            polyline = route,
            position = position(RoutePoint(37.0, 126.9995), 3.0, 90.0, nowMs = 1_000L),
        )
        val first = matcher.match(
            routeId = "interrupted",
            polyline = route,
            position = position(RoutePoint(37.0, 127.0), 3.0, 180.0, nowMs = 2_000L),
            previousProgressM = 300.0,
        )

        matcher.onPositioningEvidenceInterrupted()
        val firstAfterInterruption = matcher.match(
            routeId = "interrupted",
            polyline = route,
            position = position(RoutePoint(37.0, 127.0), 3.0, 180.0, nowMs = 3_000L),
            previousProgressM = 300.0,
        )
        val confirmed = matcher.match(
            routeId = "interrupted",
            polyline = route,
            position = position(RoutePoint(37.0, 127.0), 3.0, 180.0, nowMs = 4_000L),
            previousProgressM = 300.0,
        )

        assertEquals(RouteMatchReason.BRANCH_SWITCH_PENDING, first.reason)
        assertEquals(RouteMatchReason.BRANCH_SWITCH_PENDING, firstAfterInterruption.reason)
        assertEquals(2, confirmed.segmentIndex)
        assertEquals(RouteMatchReason.MATCHED, confirmed.reason)
    }

    private fun crossingRoute(): List<RoutePoint> {
        return listOf(
            RoutePoint(37.0, 126.999),
            RoutePoint(37.0, 127.0),
            RoutePoint(37.001, 127.0),
            RoutePoint(37.0, 127.0),
            RoutePoint(37.0, 127.001),
        )
    }

    private fun position(
        point: RoutePoint,
        accuracyM: Double,
        headingDeg: Double?,
        nowMs: Long = 1_000L,
    ): FilteredRoutePosition {
        return FilteredRoutePosition(
            point = point,
            horizontalAccuracyM = accuracyM,
            elapsedRealtimeMs = nowMs,
            covarianceEnu = EnuPositionCovariance(
                eastVarianceM2 = accuracyM * accuracyM,
                northVarianceM2 = accuracyM * accuracyM,
            ),
            heading = headingDeg?.let { RouteHeadingEstimate(it, standardDeviationDeg = 5.0) },
        )
    }
}

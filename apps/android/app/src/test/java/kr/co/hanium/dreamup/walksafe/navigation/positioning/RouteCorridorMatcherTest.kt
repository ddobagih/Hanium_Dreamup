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
    fun antimeridianInteriorPointsMatchTheShortSegmentInBothDirections() {
        val forward = listOf(RoutePoint(0.0, 179.999), RoutePoint(0.0, -179.999))
        val totalLengthM = 6_371_000.0 * Math.toRadians(0.002)
        for (reversed in listOf(false, true)) {
            val route = if (reversed) forward.reversed() else forward
            for ((longitude, forwardFraction) in listOf(179.9995 to 0.25, -179.9995 to 0.75)) {
                val filtered = RoutePoint(0.0, longitude)
                val fraction = if (reversed) 1.0 - forwardFraction else forwardFraction
                val result = RouteCorridorMatcher().match(
                    routeId = "dateline-interior",
                    polyline = route,
                    position = position(filtered, accuracyM = 3.0, headingDeg = if (reversed) 270.0 else 90.0),
                )

                assertEquals(RouteMatchReason.MATCHED, result.reason)
                assertEquals(filtered, result.filteredPoint)
                assertEquals(0, result.segmentIndex)
                assertEquals(fraction, requireNotNull(result.segmentFraction), 0.000001)
                assertEquals(0.0, requireNotNull(result.crossTrackDistanceM), 0.000001)
                assertEquals(longitude, requireNotNull(result.matchedPoint).longitude, 0.000001)
                assertEquals(0.0, requireNotNull(result.matchedPoint).latitude, 0.0)
                assertEquals(totalLengthM * fraction, requireNotNull(result.geometricProgressM), 0.001)
            }
        }
    }

    @Test
    fun antimeridianSignedBoundaryMidpointsAreEquivalentInBothDirections() {
        val forward = listOf(RoutePoint(0.0, 179.999), RoutePoint(0.0, -179.999))
        for (route in listOf(forward, forward.reversed())) {
            val results = listOf(180.0, -180.0).map { longitude ->
                val filtered = RoutePoint(0.0, longitude)
                RouteCorridorMatcher().match(
                    routeId = "dateline-midpoint",
                    polyline = route,
                    position = position(filtered, accuracyM = 3.0, headingDeg = null),
                ).also { result ->
                    assertEquals(RouteMatchReason.MATCHED, result.reason)
                    assertEquals(filtered, result.filteredPoint)
                    assertEquals(0.5, requireNotNull(result.segmentFraction), 0.000001)
                    assertEquals(0.0, requireNotNull(result.crossTrackDistanceM), 0.000001)
                    val matchedLongitude = requireNotNull(result.matchedPoint).longitude
                    assertTrue(matchedLongitude in -180.0..180.0)
                    assertEquals(180.0, kotlin.math.abs(matchedLongitude), 0.000001)
                }
            }

            assertEquals(
                requireNotNull(results[0].geometricProgressM),
                requireNotNull(results[1].geometricProgressM),
                0.001,
            )
            assertEquals(
                requireNotNull(results[0].crossTrackDistanceM),
                requireNotNull(results[1].crossTrackDistanceM),
                0.000001,
            )
        }
    }

    @Test
    fun antimeridianSegmentRejectsGreenwichPositionInBothDirections() {
        val forward = listOf(RoutePoint(0.0, 179.999), RoutePoint(0.0, -179.999))
        for (route in listOf(forward, forward.reversed())) {
            val filtered = RoutePoint(0.0, 0.0)
            val result = RouteCorridorMatcher().match(
                routeId = "dateline-opposite-side",
                polyline = route,
                position = position(filtered, accuracyM = 3.0, headingDeg = null),
            )

            assertEquals(RouteMatchQuality.UNMATCHED, result.quality)
            assertEquals(RouteMatchReason.CORRIDOR_OUTSIDE, result.reason)
            assertEquals(filtered, result.filteredPoint)
            assertNull(result.matchedPoint)
            assertTrue(requireNotNull(result.crossTrackDistanceM).isFinite())
        }
    }

    @Test
    fun antimeridianEndpointAliasesPreserveZeroAndFullProgress() {
        val totalLengthM = 6_371_000.0 * Math.toRadians(0.001)
        for (neighbor in listOf(179.999, -179.999)) {
            for (boundary in listOf(180.0, -180.0)) {
                val endingAtBoundary = listOf(RoutePoint(0.0, neighbor), RoutePoint(0.0, boundary))
                for (reversed in listOf(false, true)) {
                    val route = if (reversed) endingAtBoundary.reversed() else endingAtBoundary
                    val fraction = if (reversed) 0.0 else 1.0
                    for (observedBoundary in listOf(180.0, -180.0)) {
                        val filtered = RoutePoint(0.0, observedBoundary)
                        val result = RouteCorridorMatcher().match(
                            routeId = "dateline-endpoint",
                            polyline = route,
                            position = position(filtered, accuracyM = 3.0, headingDeg = null),
                        )

                        assertEquals(RouteMatchReason.MATCHED, result.reason)
                        assertEquals(filtered, result.filteredPoint)
                        assertEquals(fraction, requireNotNull(result.segmentFraction), 0.000001)
                        assertEquals(0.0, requireNotNull(result.crossTrackDistanceM), 0.000001)
                        assertEquals(totalLengthM * fraction, requireNotNull(result.geometricProgressM), 0.001)
                        val matchedLongitude = requireNotNull(result.matchedPoint).longitude
                        assertTrue(matchedLongitude in -180.0..180.0)
                        assertEquals(180.0, kotlin.math.abs(matchedLongitude), 0.000001)
                    }
                }
            }
        }
    }

    @Test
    fun antimeridianNearbySamplesClampToBothEndpointsInBothDirections() {
        val forward = listOf(RoutePoint(0.0, 179.999), RoutePoint(0.0, -179.999))
        for (reversed in listOf(false, true)) {
            val route = if (reversed) forward.reversed() else forward
            for ((longitude, forwardFraction) in listOf(179.99899 to 0.0, -179.99899 to 1.0)) {
                val fraction = if (reversed) 1.0 - forwardFraction else forwardFraction
                val endpoint = if (fraction == 0.0) route.first() else route.last()
                val result = RouteCorridorMatcher().match(
                    routeId = "dateline-clamped-endpoint",
                    polyline = route,
                    position = position(
                        RoutePoint(0.0, longitude),
                        accuracyM = 3.0,
                        headingDeg = if (reversed) 270.0 else 90.0,
                    ),
                )

                assertEquals(RouteMatchReason.MATCHED, result.reason)
                assertEquals(fraction, requireNotNull(result.segmentFraction), 0.0)
                assertEquals(endpoint.longitude, requireNotNull(result.matchedPoint).longitude, 0.000001)
                assertEquals(endpoint.latitude, requireNotNull(result.matchedPoint).latitude, 0.0)
                assertTrue(requireNotNull(result.crossTrackDistanceM) in 1.0..1.2)
                assertTrue(requireNotNull(result.matchedPoint).longitude in -180.0..180.0)
            }
        }
    }

    @Test
    fun ordinaryDomesticRoutePreservesGeometryInBothDirections() {
        val forward = listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0, 127.001))
        val filtered = RoutePoint(37.00005, 127.0005)
        for (reversed in listOf(false, true)) {
            val bearing = if (reversed) 270.0 else 90.0
            val result = RouteCorridorMatcher().match(
                routeId = "domestic-longitude-control",
                polyline = if (reversed) forward.reversed() else forward,
                position = position(filtered, accuracyM = 10.0, headingDeg = bearing),
            )

            assertEquals(filtered, result.filteredPoint)
            assertEquals(RouteMatchQuality.HIGH, result.quality)
            assertEquals(RouteMatchReason.MATCHED, result.reason)
            assertEquals(0, result.segmentIndex)
            assertEquals(37.0, requireNotNull(result.matchedPoint).latitude, 0.000001)
            assertEquals(127.0005, requireNotNull(result.matchedPoint).longitude, 0.000001)
            assertEquals(0.5, requireNotNull(result.segmentFraction), 0.000001)
            assertTrue(requireNotNull(result.crossTrackDistanceM) in 5.0..6.2)
            assertTrue(requireNotNull(result.geometricProgressM) in 43.0..46.0)
            assertEquals(bearing.toFloat(), requireNotNull(result.bearingDeg), 1f)
        }
    }

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

    @Test
    fun validDirectionalCovarianceIsNotReplacedByTheScalarRadius() {
        val route = listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0, 127.001))
        val fix = position(RoutePoint(37.00005, 127.0005), 10.0, 90.0)
        val directional = RouteCorridorMatcher().match(
            "directional", route,
            fix.copy(covarianceEnu = EnuPositionCovariance(100.0, 1.0)),
        )
        val scalar = RouteCorridorMatcher().match("scalar", route, fix.copy(covarianceEnu = null))

        assertEquals(RouteMatchQuality.LOW, directional.quality)
        assertEquals(RouteMatchQuality.HIGH, scalar.quality)
        assertTrue(directional.confidence < scalar.confidence)
        assertEquals(fix.point, directional.filteredPoint)
    }

    @Test
    fun invalidCovarianceStillUsesTheScalarFallback() {
        val route = listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0, 127.001))
        val fix = position(RoutePoint(37.00005, 127.0005), 10.0, 90.0)
        val invalid = RouteCorridorMatcher().match(
            "same", route, fix.copy(covarianceEnu = EnuPositionCovariance(1.0, 1.0, 2.0)),
        )
        val scalar = RouteCorridorMatcher().match("same", route, fix.copy(covarianceEnu = null))

        assertEquals(scalar, invalid)
    }

    @Test
    fun invalidInputOrRouteBreaksPendingBranchEvidence() {
        for (invalidRoute in listOf(false, true)) {
            val matcher = RouteCorridorMatcher()
            val route = crossingRoute()
            matcher.match("interrupted", route, position(RoutePoint(37.0, 126.9995), 3.0, 90.0))
            val pending = matcher.match(
                "interrupted", route,
                position(RoutePoint(37.0, 127.0), 3.0, 180.0, 2_000L), 300.0,
            )
            assertEquals(RouteMatchReason.BRANCH_SWITCH_PENDING, pending.reason)
            val invalid = matcher.match(
                "interrupted", if (invalidRoute) emptyList() else route,
                position(RoutePoint(if (invalidRoute) 37.0 else Double.NaN, 127.0), 3.0, 180.0, 2_500L),
            )
            assertEquals(RouteMatchQuality.UNMATCHED, invalid.quality)

            val restarted = matcher.match(
                "interrupted", route,
                position(RoutePoint(37.0, 127.0), 3.0, 180.0, 3_000L), 300.0,
            )
            assertEquals(RouteMatchReason.BRANCH_SWITCH_PENDING, restarted.reason)
            val confirmed = matcher.match(
                "interrupted", route,
                position(RoutePoint(37.0, 127.0), 3.0, 180.0, 4_000L), 300.0,
            )
            assertEquals(RouteMatchReason.MATCHED, confirmed.reason)
            assertEquals(2, confirmed.segmentIndex)
        }
    }

    @Test
    fun sameIdInPlaceGeometryReplacementMatchesAFreshMatcher() {
        val matcher = RouteCorridorMatcher()
        val route = crossingRoute().toMutableList()
        matcher.match("same-id", route, position(RoutePoint(37.0, 126.9995), 3.0, 90.0))
        for (index in route.indices) {
            val point = route[index]
            route[index] = RoutePoint(point.latitude + 0.01, point.longitude)
        }
        val fix = position(RoutePoint(37.01, 127.0), 3.0, 180.0, 2_000L)

        val replaced = matcher.match("same-id", route, fix)
        val fresh = RouteCorridorMatcher().match("same-id", route, fix)

        assertEquals(fresh, replaced)
    }

    @Test
    fun negativeInitialTimestampDoesNotEstablishMatcherState() {
        val matcher = RouteCorridorMatcher()
        val route = listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0, 127.001))
        val fix = position(RoutePoint(37.0, 127.0005), 3.0, 90.0)

        val invalid = matcher.match("time", route, fix.copy(elapsedRealtimeMs = -1L))
        assertEquals(RouteMatchReason.INVALID_INPUT, invalid.reason)
        assertEquals(RouteCorridorMatcher().match("time", route, fix), matcher.match("time", route, fix))
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

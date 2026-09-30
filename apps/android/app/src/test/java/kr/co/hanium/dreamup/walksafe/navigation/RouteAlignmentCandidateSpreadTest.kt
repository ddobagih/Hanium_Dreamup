package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteCorridorMatchResult
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchQuality
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchReason
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test

/** 08.txt #19: agreed route candidates carry their bearing range into the facing decision. */
class RouteAlignmentCandidateSpreadTest {
    private val metersPerDegree = 111_320.0

    private fun zigzag(bearings: List<Double>): WalkingRoute {
        var lat = 37.0
        var lon = 127.0
        val points = mutableListOf(RoutePoint(lat, lon))
        bearings.forEach { b ->
            lat += 5.0 * cos(Math.toRadians(b)) / metersPerDegree
            lon += 5.0 * sin(Math.toRadians(b)) / (metersPerDegree * cos(Math.toRadians(lat)))
            points += RoutePoint(lat, lon)
        }
        return WalkingRoute("TEST", WalkingRouteSummary(bearings.size * 5, bearings.size * 4), points, emptyList())
    }

    private fun select(route: WalkingRoute, point: RoutePoint): RouteAlignmentSelection {
        val position = FilteredRoutePosition(point, 3.0, 1_000L)
        val lowMatch = RouteCorridorMatchResult(
            "route", point, null, null, null, null, null, null, 0.2, RouteMatchQuality.LOW, RouteMatchReason.LOW_CONFIDENCE,
        )
        return RouteAlignmentSelector().select(route, position, lowMatch, 1_000L, 35.0)
    }

    @Test
    fun similarButNotIdenticalCandidatesReportTheirLargestBearingDifference() {
        val route = zigzag(List(20) { if (it % 2 == 0) 5.0 else -5.0 })
        val selection = select(route, route.polyline[10])
        assertEquals(RouteAlignmentReason.AGREED_CANDIDATES, selection.diagnostic.reason)
        assertEquals(10.0, selection.bearingUncertaintyDegrees, 0.5)
    }

    @Test
    fun collinearCandidatesAddNoUncertainty() {
        val route = zigzag(List(20) { 0.0 })
        val selection = select(route, route.polyline[10])
        assertEquals(RouteAlignmentReason.AGREED_CANDIDATES, selection.diagnostic.reason)
        assertEquals(0.0, selection.bearingUncertaintyDegrees, 0.01)
    }
}

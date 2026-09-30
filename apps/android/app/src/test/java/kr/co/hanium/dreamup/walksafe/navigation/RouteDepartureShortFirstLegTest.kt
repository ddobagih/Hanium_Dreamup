package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteCorridorMatchResult
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchQuality
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchReason
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 08.txt #17: a sub-metre first segment (below any walkable/positioning resolution) must not
 * decide the departure direction by itself, and a real corner must not be smoothed across.
 */
class RouteDepartureShortFirstLegTest {
    private val metersPerDegree = 111_320.0
    private val start = RoutePoint(37.0, 127.0)

    private fun route(vararg legs: Pair<Double, Double>): WalkingRoute {
        var lat = start.latitude
        var lon = start.longitude
        val points = mutableListOf(start)
        legs.forEach { (bearing, length) ->
            lat += length * cos(Math.toRadians(bearing)) / metersPerDegree
            lon += length * sin(Math.toRadians(bearing)) / (metersPerDegree * cos(Math.toRadians(lat)))
            points += RoutePoint(lat, lon)
        }
        return WalkingRoute("TEST", WalkingRouteSummary(legs.sumOf { it.second }.toInt(), 100), points, emptyList())
    }

    private fun departure(route: WalkingRoute): RouteAlignmentSelection {
        val origin = TrustedLocation(start.latitude, start.longitude, 5f, 1_000L)
        val position = FilteredRoutePosition(start, 5.0, 1_000L)
        val match = RouteCorridorMatchResult(
            "route", start, null, null, null, null, null, null, 0.2, RouteMatchQuality.LOW, RouteMatchReason.LOW_CONFIDENCE,
        )
        return RouteAlignmentSelector().apply { reset(origin) }.select(route, position, match, 1_000L, 35.0)
    }

    private fun angle(a: Double, b: Double) = abs((a - b + 540.0) % 360.0 - 180.0)

    @Test
    fun subMetreFirstLegFollowedByATurnDoesNotDecideTheDepartureDirection() {
        val selection = departure(route(0.0 to 0.5, 90.0 to 200.0))
        assertNull("a 0.5 m leg before a corner is not a departure direction", selection.bearingDegrees)
        assertEquals(RouteAlignmentReason.NO_ROUTE_BEARING, selection.diagnostic.reason)
    }

    @Test
    fun subMetreFirstLegFollowedByTheSameDirectionLooksAlongTheRoute() {
        val selection = departure(route(10.0 to 0.5, 0.0 to 200.0))
        assertEquals(RouteAlignmentReason.DEPARTURE_SEGMENT, selection.diagnostic.reason)
        assertTrue("bearing=${selection.bearingDegrees}", angle(requireNotNull(selection.bearingDegrees), 0.0) < 1.0)
    }

    @Test
    fun realShortEntriesAndStraightRoutesKeepTheirFirstLegDirection() {
        val entry = departure(route(0.0 to 5.0, 90.0 to 200.0))
        assertEquals(RouteAlignmentReason.DEPARTURE_SEGMENT, entry.diagnostic.reason)
        assertTrue(angle(requireNotNull(entry.bearingDegrees), 0.0) < 0.5)
        val straight = departure(route(90.0 to 200.0))
        assertTrue(angle(requireNotNull(straight.bearingDegrees), 90.0) < 0.5)
    }
}

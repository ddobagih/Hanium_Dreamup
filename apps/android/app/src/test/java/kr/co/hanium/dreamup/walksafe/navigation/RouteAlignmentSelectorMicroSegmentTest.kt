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
 * 08.txt #17 (S08-17): past the departure, a sub-metre leg on the route must not decide the compared
 * bearing either. A noise kink between continuing legs is cleaned in the derived direction only;
 * real corners and short entries (>= 1 m) are not merged or smoothed across.
 */
class RouteAlignmentSelectorMicroSegmentTest {
    private val metersPerDegree = 111_320.0
    private val start = RoutePoint(37.0, 127.0)

    @Test
    fun aSubMetreKinkBetweenContinuingLegsDoesNotDecideTheBearing() {
        val route = route(90.0 to 50.0, 0.0 to 0.3, 90.0 to 50.0)
        val selection = selectOn(route, segmentIndex = 1)
        assertEquals(RouteAlignmentReason.CURRENT_SEGMENT, selection.diagnostic.reason)
        assertTrue("bearing=${selection.bearingDegrees}", angle(requireNotNull(selection.bearingDegrees), 90.0) < 2.0)
    }

    @Test
    fun aSubMetreLegRightBeforeACornerGivesNoBearingInsteadOfItsOwnDiagonal() {
        val route = route(90.0 to 50.0, 45.0 to 0.3, 0.0 to 50.0)
        val selection = selectOn(route, segmentIndex = 1)
        assertNull("bearing=${selection.bearingDegrees}", selection.bearingDegrees)
        assertEquals(RouteAlignmentReason.NO_ROUTE_BEARING, selection.diagnostic.reason)
    }

    @Test
    fun aMicroVertexIntoARealTurnKeepsTheTurnDirection() {
        val route = route(90.0 to 50.0, 0.0 to 0.5, 0.0 to 50.0)
        val selection = selectOn(route, segmentIndex = 1)
        assertTrue("bearing=${selection.bearingDegrees}", angle(requireNotNull(selection.bearingDegrees), 0.0) < 1.0)
    }

    @Test
    fun realShortEntriesAndDenseStraightRoutesKeepTheirDirection() {
        // A 2 m entry is a real leg: it is neither merged into its neighbours nor looked across.
        val entry = selectOn(route(90.0 to 50.0, 0.0 to 2.0, 90.0 to 50.0), segmentIndex = 1)
        assertTrue("bearing=${entry.bearingDegrees}", angle(requireNotNull(entry.bearingDegrees), 0.0) < 0.5)
        // Dense 0.5 m sampling of one straight line still gives that line's direction.
        val dense = selectOn(route(*Array(80) { 90.0 to 0.5 }), segmentIndex = 40)
        assertEquals(RouteAlignmentReason.CURRENT_SEGMENT, dense.diagnostic.reason)
        assertTrue("bearing=${dense.bearingDegrees}", angle(requireNotNull(dense.bearingDegrees), 90.0) < 1.0)
    }

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

    /** An accepted mid-route match at the middle of [segmentIndex] (no departure anchor). */
    private fun selectOn(route: WalkingRoute, segmentIndex: Int): RouteAlignmentSelection {
        val a = route.polyline[segmentIndex]
        val b = route.polyline[segmentIndex + 1]
        val point = RoutePoint((a.latitude + b.latitude) / 2.0, (a.longitude + b.longitude) / 2.0)
        val match = RouteCorridorMatchResult(
            "route", point, point, segmentIndex, 0.5, 0.0, null, null, 0.9, RouteMatchQuality.HIGH, RouteMatchReason.MATCHED,
        )
        return RouteAlignmentSelector().apply { reset() }
            .select(route, FilteredRoutePosition(point, 3.0, 1_000L), match, 1_000L, 35.0)
    }

    private fun angle(a: Double, b: Double) = abs((a - b + 540.0) % 360.0 - 180.0)
}

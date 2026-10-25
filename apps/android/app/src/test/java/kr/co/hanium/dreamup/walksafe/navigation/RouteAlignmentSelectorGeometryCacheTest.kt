package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteCorridorMatcher
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteAlignmentSelectorGeometryCacheTest {
    @Test
    fun installedFixedGeometryKeepsRepeatedBearingSelectionsEquivalent() {
        val route = route(mutableListOf(point(0.0), point(100.0), point(100.0, 100.0)))
        val selector = RouteAlignmentSelector().apply { installGeometry(RouteGeometryCache(route.polyline)) }
        val first = select(selector, route, point(20.0), 1_000L)
        val repeated = select(selector, route, point(20.0), 2_000L)

        assertEquals(first, repeated)
        assertEquals(RouteAlignmentReason.CURRENT_SEGMENT, repeated.diagnostic.reason)
        assertEquals(90.0, requireNotNull(repeated.bearingDegrees), 0.01)
    }

    @Test
    fun directCallerInPlaceReplacementRefreshesInstalledGeometry() {
        val points = mutableListOf(point(0.0), point(100.0))
        val route = route(points)
        val selector = RouteAlignmentSelector().apply { installGeometry(RouteGeometryCache(points)) }
        select(selector, route, point(20.0), 1_000L)
        points[0] = point(0.0, 500.0)
        points[1] = point(100.0, 500.0)
        val actual = select(selector, route, point(20.0, 500.0), 2_000L)
        val expected = select(RouteAlignmentSelector(), route, point(20.0, 500.0), 2_000L)

        assertEquals(expected, actual)
        assertEquals(RouteAlignmentReason.CURRENT_SEGMENT, actual.diagnostic.reason)
        assertEquals(point(35.0, 500.0).latitude, requireNotNull(actual.target).latitude, 1e-10)
    }

    private fun select(selector: RouteAlignmentSelector, route: WalkingRoute, point: RoutePoint, time: Long): RouteAlignmentSelection {
        val position = FilteredRoutePosition(point, 3.0, time)
        val match = RouteCorridorMatcher().match("same-id", route.polyline, position)
        return selector.select(route, position, match, time, 35.0)
    }

    private fun route(points: MutableList<RoutePoint>) = WalkingRoute("STAIR_AVOID",
        WalkingRouteSummary(200, 200), points, emptyList(), providerRouteId = "same-id")

    private fun point(east: Double, north: Double = 0.0) = RoutePoint(
        north / 111_194.92664455873, 127.0 + east / 111_194.92664455873,
    )
}

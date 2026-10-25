package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RouteNavigatorGeometryCacheTest {
    @Test
    fun positionEvidenceResetsKeepTheSameInstalledGeometry() {
        listOf<(RouteNavigator) -> Unit>(
            { it.onPositioningEvidenceInterrupted() },
            { it.onUntrustedLocation() },
        ).forEach { interrupt ->
            val navigator = RouteNavigator().apply { setRoute(route(0.0)) }
            val geometry = field(navigator, "routeGeometry")
            val selector = requireNotNull(field(navigator, "routeAlignmentSelector"))
            assertSame(geometry, field(selector, "geometry"))

            interrupt(navigator)

            assertSame(geometry, field(navigator, "routeGeometry"))
            assertSame(geometry, field(selector, "geometry"))
            val fix = location(20.0)
            navigator.update(fix, fix.elapsedRealtimeMs, false)
            assertSame(geometry, field(selector, "geometry"))
        }
    }

    @Test
    fun reinstallReplacesTheSharedSnapshotAndClearReleasesIt() {
        val navigator = RouteNavigator().apply { setRoute(route(0.0)) }
        val previous = field(navigator, "routeGeometry")
        val selector = requireNotNull(field(navigator, "routeAlignmentSelector"))
        navigator.setRoute(route(500.0))
        val current = field(navigator, "routeGeometry")
        assertNotSame(previous, current)
        assertSame(current, field(selector, "geometry"))

        navigator.clear()

        assertNull(field(navigator, "routeGeometry"))
        assertNull(field(selector, "geometry"))
        assertEquals(emptyList<Any>(), field(selector, "cachedSegments"))
    }

    @Test
    fun installedShapeGuidesAndStepPointsOwnTheirLists() {
        val points = mutableListOf(point(0.0), point(100.0), point(100.0, 100.0))
        val guides = mutableListOf(guide(0, points[0], 0), guide(1, points[1], 100))
        val stepPoints = points.toMutableList()
        val steps = mutableListOf(WalkingRouteStep(0, 200, 200, stepPoints, null, null, null, null))
        val route = WalkingRoute("STAIR_AVOID", WalkingRouteSummary(200, 200), points, guides, steps,
            providerRouteId = "same-id")
        val navigator = RouteNavigator().apply { setRoute(route) }
        val expected = requireNotNull(navigator.currentProjection(location(20.0)))
        val instruction = navigator.currentInstruction(location(20.0))

        points.replaceAll { point(0.0, 500.0) }
        guides.clear()
        stepPoints.clear()
        steps.clear()

        assertEquals(expected, navigator.currentProjection(location(20.0)))
        assertEquals(instruction, navigator.currentInstruction(location(20.0)))
        val fix = location(20.0)
        navigator.update(fix, fix.elapsedRealtimeMs, false)
        assertEquals(20.0, requireNotNull(navigator.currentRouteMatch()?.geometricProgressM), 0.01)
    }

    @Test
    fun sameProviderIdReinstallUsesNewGeometryAndNewProjectionRevision() {
        val original = route(0.0)
        val replacement = route(500.0)
        val navigator = RouteNavigator().apply { setRoute(original) }
        val oldRevision = requireNotNull(navigator.currentProjection(location(20.0))?.routeRevision)
        navigator.setRoute(replacement)
        val actual = requireNotNull(navigator.currentProjection(location(20.0, 500.0)))
        val expected = requireNotNull(RouteNavigator().apply { setRoute(replacement) }
            .currentProjection(location(20.0, 500.0)))

        assertEquals(oldRevision + 1L, actual.routeRevision)
        assertEquals(expected.copy(routeRevision = actual.routeRevision), actual)
        assertEquals(0.0, actual.distanceToRouteM, 0.01)
        navigator.clear()
        assertNull(navigator.currentProjection(location(20.0, 500.0)))
    }

    private fun route(north: Double): WalkingRoute {
        val points = listOf(point(0.0, north), point(100.0, north), point(100.0, north + 100.0))
        return WalkingRoute("STAIR_AVOID", WalkingRouteSummary(200, 200), points,
            listOf(guide(0, points[0], 0), guide(1, points[1], 100)), providerRouteId = "same-id")
    }

    // Cache identity is the performance contract under test; it has no product-facing getter.
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)

    private fun guide(index: Int, point: RoutePoint, progress: Int) = WalkingRouteGuidePoint(
        index, point, null, progress, 200 - progress, turnType = if (index == 0) 200 else 13,
    )

    private fun point(east: Double, north: Double = 0.0) = RoutePoint(
        0.0 + north / 111_194.92664455873, 127.0 + east / 111_194.92664455873,
    )

    private fun location(east: Double, north: Double = 0.0): TrustedLocation = point(east, north).let {
        TrustedLocation(it.latitude, it.longitude, 3f, 1_000L)
    }
}

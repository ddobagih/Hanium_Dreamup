package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteNavigatorVisualTestSnapshotTest {
    @Test
    fun snapshotCollectionMutationsCannotChangeTheActiveRoute() {
        val navigator = RouteNavigator()
        val expected = route()
        navigator.setRoute(expected)
        val snapshot = requireNotNull(navigator.visualTestRouteSnapshot())

        (snapshot.polyline as MutableList<RoutePoint>).clear()
        (snapshot.guidePoints as MutableList<WalkingRouteGuidePoint>).clear()
        snapshot.steps.forEach { step -> (step.points as MutableList<RoutePoint>).clear() }
        (snapshot.steps as MutableList<WalkingRouteStep>).clear()

        assertEquals(expected, navigator.visualTestRouteSnapshot())
        assertEquals(2, expected.polyline.size)
        assertEquals(2, expected.guidePoints.size)
        assertEquals(2, expected.steps.size)
        assertTrue(expected.steps.all { it.points.size == 2 })
        assertTrue(navigator.hasRoute())
    }

    @Test
    fun readingSnapshotsDoesNotConsumeGuidanceOrChangeNavigationState() {
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 6_000L))
        navigator.setRoute(route())
        val location = TrustedLocation(37.00063, 127.0, 5f, 1_000L)
        val offered = navigator.update(location, nowMs = 1_000L, requestInFlight = false)
        val routeId = navigator.currentRouteId()
        val bearing = navigator.currentBearingDeg()
        val match = navigator.currentRouteMatch()
        val instruction = navigator.currentInstruction(location)

        repeat(3) { assertNotNull(navigator.visualTestRouteSnapshot()) }

        assertEquals(routeId, navigator.currentRouteId())
        assertEquals(bearing, navigator.currentBearingDeg())
        assertEquals(match, navigator.currentRouteMatch())
        assertEquals(instruction, navigator.currentInstruction(location))
        val repeated = navigator.update(location, nowMs = 1_100L, requestInFlight = false)
        assertEquals("route_guidance", offered.reason)
        assertEquals("route_guidance", repeated.reason)
        assertEquals(offered.guideIndex, repeated.guideIndex)
        assertEquals(offered.instruction, repeated.instruction)

        navigator.acknowledgeInstruction(repeated, spokenAtMs = 1_100L)
        navigator.visualTestRouteSnapshot()
        val rateLimited = navigator.update(location, nowMs = 1_200L, requestInFlight = false)
        assertEquals("guidance_rate_limited", rateLimited.reason)

        navigator.onUntrustedLocation()
        val pendingDecision = navigator.pendingUserDecision()
        val decisionToken = navigator.pendingDecisionToken()
        assertNotNull(decisionToken)
        navigator.visualTestRouteSnapshot()
        assertEquals(pendingDecision, navigator.pendingUserDecision())
        assertEquals(decisionToken, navigator.pendingDecisionToken())
        assertNull(navigator.currentInstruction(location))
    }

    @Test
    fun snapshotIsAbsentBeforeRouteInstallationAndAfterClear() {
        val navigator = RouteNavigator()
        assertNull(navigator.visualTestRouteSnapshot())
        navigator.setRoute(route())
        assertNotNull(navigator.visualTestRouteSnapshot())

        navigator.clear()

        assertNull(navigator.visualTestRouteSnapshot())
    }

    private fun route(): WalkingRoute {
        val points = listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0009, 127.0))
        return WalkingRoute(
            priority = "STAIR_AVOID",
            summary = WalkingRouteSummary(distanceM = 100, durationS = 90),
            polyline = points,
            guidePoints = listOf(
                WalkingRouteGuidePoint(0, RoutePoint(37.00045, 127.0), "첫 회전", 50, 50, 0f),
                WalkingRouteGuidePoint(1, RoutePoint(37.00081, 127.0), "다음 회전", 90, 10, 0f),
            ),
            steps = listOf(
                WalkingRouteStep(0, 50, 45, points, "첫 회전", null, null, null),
                WalkingRouteStep(1, 50, 45, points, "다음 회전", null, null, null),
            ),
            providerRouteId = "visual-test-route",
        )
    }
}

package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteNavigatorTest {
    @Test
    fun emitsFallbackInstructionWhenGuideInstructionMissing() {
        val navigator = RouteNavigator()
        navigator.setRoute(route(guideInstruction = null))

        val update = navigator.update(locationNearStart(), nowMs = 1_000L, requestInFlight = false)

        assertNotNull(update.instruction)
        assertFalse(update.shouldReroute)
    }

    @Test
    fun exposesCurrentRouteBearingForMotionContext() {
        val navigator = RouteNavigator()
        navigator.setRoute(route(bearingDeg = 12.5f))

        assertEquals(12.5f, navigator.currentBearingDeg()!!, 0.001f)
    }

    @Test
    fun gatesRerouteByOffRouteCooldownAndInFlight() {
        val navigator = RouteNavigator()
        navigator.setRoute(route())

        val blocked = navigator.update(offRouteLocation(), nowMs = 1_000L, requestInFlight = true)
        val first = navigator.update(offRouteLocation(), nowMs = 2_000L, requestInFlight = false)
        val cooldown = navigator.update(offRouteLocation(), nowMs = 3_000L, requestInFlight = false)

        assertFalse(blocked.shouldReroute)
        assertTrue(first.shouldReroute)
        assertFalse(cooldown.shouldReroute)
    }

    @Test
    fun requiresConsecutiveOffRouteSamplesBeforeReroute() {
        val navigator = RouteNavigator()
        navigator.setRoute(route())

        val first = navigator.update(offRouteLocation(), nowMs = 1_000L, requestInFlight = false)
        val second = navigator.update(offRouteLocation(), nowMs = 2_000L, requestInFlight = false)

        assertFalse(first.shouldReroute)
        assertFalse(first.offRoute)
        assertEquals("off_route_pending", first.reason)
        assertTrue(second.shouldReroute)
        assertTrue(second.offRoute)
    }

    @Test
    fun ignoresOffRouteWhenAccuracyStillOverlapsRoute() {
        val navigator = RouteNavigator()
        navigator.setRoute(route())

        val first = navigator.update(uncertainOffRouteLocation(), nowMs = 1_000L, requestInFlight = false)
        val second = navigator.update(uncertainOffRouteLocation(), nowMs = 2_000L, requestInFlight = false)
        val preciseFirst = navigator.update(slightlyOffRouteLocation(accuracyM = 3f), nowMs = 8_000L, requestInFlight = false)

        assertFalse(first.shouldReroute)
        assertFalse(first.offRoute)
        assertFalse(second.shouldReroute)
        assertFalse(second.offRoute)
        assertFalse(preciseFirst.shouldReroute)
        assertEquals("off_route_pending", preciseFirst.reason)
    }

    @Test
    fun arrivalClearsRoute() {
        val navigator = RouteNavigator()
        navigator.setRoute(route())

        val update = navigator.update(
            TrustedLocation(37.0009, 127.0, 5f, 1_000L),
            nowMs = 1_000L,
            requestInFlight = false,
        )

        assertTrue(update.arrived)
    }

    private fun route(guideInstruction: String? = "직진하세요.", bearingDeg: Float? = null): WalkingRoute {
        return WalkingRoute(
            priority = "STAIR_AVOID",
            summary = WalkingRouteSummary(distanceM = 100, durationS = 90),
            polyline = listOf(
                RoutePoint(37.0, 127.0),
                RoutePoint(37.0009, 127.0),
            ),
            guidePoints = listOf(
                WalkingRouteGuidePoint(
                    index = 0,
                    point = RoutePoint(37.00045, 127.0),
                    instruction = guideInstruction,
                    distanceFromStartM = 50,
                    remainingDistanceM = 50,
                    bearingDeg = bearingDeg,
                ),
            ),
        )
    }

    private fun locationNearStart(): TrustedLocation {
        return TrustedLocation(37.0, 127.0, 5f, 1_000L)
    }

    private fun offRouteLocation(): TrustedLocation {
        return TrustedLocation(37.0, 127.01, 5f, 1_000L)
    }

    private fun uncertainOffRouteLocation(): TrustedLocation {
        return slightlyOffRouteLocation(accuracyM = 10f)
    }

    private fun slightlyOffRouteLocation(accuracyM: Float): TrustedLocation {
        return TrustedLocation(37.0, 127.00045, accuracyM, 1_000L)
    }
}

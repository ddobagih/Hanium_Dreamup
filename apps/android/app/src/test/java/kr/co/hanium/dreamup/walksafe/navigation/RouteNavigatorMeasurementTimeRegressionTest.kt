package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 07.txt #14: ordering and consecutive-evidence decisions use measurement time, not processing time. */
class RouteNavigatorMeasurementTimeRegressionTest {
    @Test
    fun acceptedMatchIsKeyedToTheMeasurementTimeOfADelayedFix() {
        val navigator = navigator()
        navigator.update(fix(east = 50.0, north = 0.0, at = 1_000), 4_000, false)

        assertNotNull(navigator.currentAcceptedRouteMatchFor(1_000))
        assertNull(navigator.currentAcceptedRouteMatchFor(4_000))
    }

    @Test
    fun deviationFixesMeasuredTooFarApartAreNotConsecutiveEvenWhenProcessedTogether() {
        val navigator = navigator()
        // Delivered 8.5 s late, then a fix measured 10.5 s later but processed 2.1 s later.
        val first = navigator.update(offRoute(at = 1_000), 9_500, false)
        val second = navigator.update(offRoute(at = 11_500), 11_600, false)

        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, first.pendingUserDecision)
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, second.pendingUserDecision)
        assertEquals(RouteNavigatorUserDecision.REROUTE,
            navigator.update(offRoute(at = 12_500), 12_600, false).pendingUserDecision)
    }

    @Test
    fun deviationFixesMeasuredOneSecondApartStayConsecutiveAfterAProcessingStall() {
        val navigator = navigator()
        navigator.update(offRoute(at = 1_000), 1_100, false)
        // Measured 1 s later; its processing was delayed 10.9 s but it is still a fresh fix.
        val second = navigator.update(offRoute(at = 2_000), 12_000, false)

        assertEquals(RouteNavigatorUserDecision.REROUTE, second.pendingUserDecision)
    }

    @Test
    fun reprojectedSameRawFixIsNotASecondDeviationObservation() {
        val navigator = navigator()
        val raw = offRoute(at = 1_000)
        val filtered = FilteredRoutePosition(RoutePoint(raw.latitude, raw.longitude), 3.0, 1_000)
        navigator.update(raw, 1_000, false, filtered)
        val reprojected = navigator.update(raw, 2_000, false, filtered.copy(elapsedRealtimeMs = 2_000))

        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, reprojected.pendingUserDecision)
        val fresh = offRoute(at = 3_000)
        assertEquals(RouteNavigatorUserDecision.REROUTE, navigator.update(fresh, 3_000, false,
            FilteredRoutePosition(RoutePoint(fresh.latitude, fresh.longitude), 3.0, 3_000)).pendingUserDecision)
    }

    private fun navigator() = RouteNavigator().apply {
        setRoute(WalkingRoute("STAIR_AVOID", WalkingRouteSummary(300, 300), listOf(point(0.0), point(300.0)), emptyList()))
    }

    private fun offRoute(at: Long) = fix(east = 50.0, north = 60.0, at = at)

    private fun fix(east: Double, north: Double, at: Long): TrustedLocation {
        val point = point(east, north)
        return TrustedLocation(point.latitude, point.longitude, 3f, at)
    }

    private fun point(east: Double, north: Double = 0.0) =
        RoutePoint(37.0 + north / 111194.92664455874, 127.0 + east / (111194.92664455874 * kotlin.math.cos(Math.toRadians(37.0))))
}

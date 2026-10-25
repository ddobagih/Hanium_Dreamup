package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteHeadingEstimate
import org.junit.Assert.*
import org.junit.Test

/** A separate installed-route speaker can own the start sentence without changing route evidence. */
class RouteNavigatorStartSummaryOwnershipTest {
    @Test fun theDefaultApiStillAnnouncesTheStartAndTotalDistance() {
        val navigator = RouteNavigator().apply { setRoute(route()) }
        val update = sample(navigator, 1_000L)
        assertTrue(requireNotNull(update.instruction).startsWith("길안내를 시작합니다. "))
        assertTrue(requireNotNull(update.infoUtterance).message.contains("전체 경로는 약 200m"))
        assertNotNull(navigator.currentAcceptedRouteMatchFor(1_000L))
        assertTrue(navigator.reserveInstruction(update))
    }

    @Test fun aSeparateStartOwnerSuppressesOnlyTheDuplicateStartAndTotalDistance() {
        val navigator = RouteNavigator().apply { setRoute(route(), announceStartSummary = false) }
        val update = sample(navigator, 1_000L)
        assertEquals("route_guidance", update.reason)
        assertNotNull(update.instruction)
        assertFalse(update.instruction!!.contains("길안내를 시작합니다"))
        assertFalse(update.infoUtterance?.message?.contains("전체 경로") == true)
        assertNotNull(navigator.currentAcceptedRouteMatchFor(1_000L))
        assertTrue(navigator.reserveInstruction(update))
    }

    @Test fun summaryOwnershipResetsForTheNextRouteAndClear() {
        val navigator = RouteNavigator()
        navigator.setRoute(route(), announceStartSummary = false)
        assertFalse(requireNotNull(sample(navigator, 1_000L).instruction).contains("길안내를 시작합니다"))
        navigator.setRoute(route())
        assertTrue(requireNotNull(sample(navigator, 2_000L).instruction).contains("길안내를 시작합니다"))
        navigator.clear()
        navigator.setRoute(route())
        assertTrue(requireNotNull(sample(navigator, 3_000L).instruction).contains("길안내를 시작합니다"))
    }

    @Test fun joiningStillNeedsFreshAcceptedEvidenceWhenTheStartOwnerIsSeparate() {
        val navigator = RouteNavigator().apply {
            setRoute(route(), waitForInitialRouteJoin = true, announceStartSummary = false)
        }
        val outside = TrustedLocation(37.0002, 127.0, 1f, 1_000L)
        val reference = navigator.update(outside, 1_000L, false, filteredPosition = filtered(outside),
            facingObservation = RouteFacingObservation(90.0, 1.0, 1_000L))
        assertTrue(navigator.isWaitingForInitialRouteJoin())
        assertTrue(reference.directionOnly)
        assertNull(navigator.currentAcceptedRouteMatchFor(1_000L))
        assertFalse(requireNotNull(reference.instruction).contains("길안내를 시작합니다"))
        val joined = sample(navigator, 2_000L)
        assertFalse(navigator.isWaitingForInitialRouteJoin())
        assertNotNull(navigator.currentAcceptedRouteMatchFor(2_000L))
        assertFalse(requireNotNull(joined.instruction).contains("길안내를 시작합니다"))
        assertFalse(joined.infoUtterance?.message?.contains("전체 경로") == true)
        assertTrue(navigator.reserveInstruction(joined))
    }

    private fun sample(navigator: RouteNavigator, atMs: Long): RouteNavigatorUpdate {
        val location = TrustedLocation(37.0, 127.0003, 1f, atMs)
        return navigator.update(location, atMs, false, filteredPosition = filtered(location),
            facingObservation = RouteFacingObservation(90.0, 1.0, atMs))
    }

    private fun filtered(location: TrustedLocation) = FilteredRoutePosition(
        RoutePoint(location.latitude, location.longitude), location.accuracyM.toDouble(), location.elapsedRealtimeMs,
        heading = RouteHeadingEstimate(90.0, 5.0),
    )

    private fun route() = WalkingRoute("STAIR_AVOID", WalkingRouteSummary(200, 200),
        listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0, 127.001)),
        listOf(
            WalkingRouteGuidePoint(0, RoutePoint(37.0, 127.0), "출발", 0, 0, 90f, pointType = "SP"),
            WalkingRouteGuidePoint(1, RoutePoint(37.0, 127.001), "도착", 200, 200, turnType = 201),
        ))
}

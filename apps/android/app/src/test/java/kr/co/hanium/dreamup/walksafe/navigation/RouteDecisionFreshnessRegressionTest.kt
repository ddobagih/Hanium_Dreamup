package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import org.junit.Assert.*
import org.junit.Test

class RouteDecisionFreshnessRegressionTest {
    @Test fun expiredArrivalCannotEndTheRoute() {
        var now = 1_000L
        val navigator = RouteNavigator(nowElapsedRealtimeMs = { now }).apply { setRoute(route()) }
        dwellAtTheDestination(navigator, 1_000L)
        val token = requireNotNull(navigator.pendingDecisionToken())
        now = 31_000L
        assertFalse(navigator.confirmArrival(token).arrived)
        assertTrue(navigator.hasRoute())
        assertNull(navigator.pendingDecisionToken())
    }

    @Test fun oldRouteQuestionCannotConfirmReplacementWithSameProviderId() {
        val navigator = RouteNavigator().apply { setRoute(route()) }
        dwellAtTheDestination(navigator, 1_000L)
        val old = requireNotNull(navigator.pendingDecisionToken())
        navigator.setRoute(route())
        dwellAtTheDestination(navigator, 5_000L)
        val current = requireNotNull(navigator.pendingDecisionToken())
        assertNotEquals(old, current)
        assertFalse(navigator.confirmArrival(old).arrived)
        assertTrue(navigator.confirmArrival(current).arrived)
    }

    @Test fun widelySeparatedArrivalSamplesDoNotFormConfirmation() {
        val navigator = RouteNavigator().apply { setRoute(route()) }
        navigator.update(fix(1_000L), 1_000L, false)
        navigator.update(fix(20_000L), 20_000L, false)
        assertNull(navigator.pendingDecisionToken())
        // S07-32 (R6): consecutive evidence restarted at 20 s and must last 3 s before the question.
        for (at in listOf(21_000L, 22_000L)) {
            navigator.update(fix(at), at, false)
            assertNull("at=$at", navigator.pendingDecisionToken())
        }
        navigator.update(fix(23_000L), 23_000L, false)
        assertNotNull(navigator.pendingDecisionToken())
    }

    @Test fun newerFilteredProjectionCannotReuseOneRawFixAsTwoArrivalSamples() {
        val navigator = RouteNavigator().apply { setRoute(route()) }
        val raw = fix(1_000L)
        val filtered = FilteredRoutePosition(RoutePoint(raw.latitude, raw.longitude), 1.0, 1_000L)
        navigator.update(raw, 1_000L, false, filtered)
        navigator.update(raw, 2_000L, false, filtered.copy(elapsedRealtimeMs = 2_000L))
        assertNull(navigator.pendingDecisionToken())
    }

    @Test fun temporalDiscontinuityInvalidatesAlreadyOfferedArrivalQuestion() {
        val navigator = RouteNavigator().apply { setRoute(route()) }
        dwellAtTheDestination(navigator, 1_000L)
        val old = requireNotNull(navigator.pendingDecisionToken())
        navigator.update(fix(3_500L), 3_500L, false)
        assertNull(navigator.pendingDecisionToken())
        assertFalse(navigator.confirmArrival(old).arrived)
    }

    @Test fun staleDeviationTokenCannotApproveANewQuestion() {
        val navigator = RouteNavigator().apply { setRoute(route()) }
        navigator.onUntrustedLocation()
        val old = requireNotNull(navigator.pendingDecisionToken())
        navigator.clear()
        navigator.setRoute(route())
        navigator.onUntrustedLocation()
        val current = requireNotNull(navigator.pendingDecisionToken())
        assertEquals("route_decision_stale", navigator.selectDeviationChoice(RouteDeviationChoice.RECHECK_LOCATION, old).reason)
        assertEquals(current, navigator.pendingDecisionToken())
        assertEquals("off_route_location_recheck_requested",
            navigator.selectDeviationChoice(RouteDeviationChoice.RECHECK_LOCATION, current).reason)
    }

    /** S07-35: a question the position clearly no longer fits is withdrawn at any trusted radius. */
    @Test fun arrivalQuestionIsWithdrawnWhenATrustedButCoarserFixClearlyLeftTheDestination() {
        val navigator = RouteNavigator().apply { setRoute(route()) }
        dwellAtTheDestination(navigator, 1_000L)
        assertNotNull(navigator.pendingDecisionToken())
        // 60 m back along the route with a 12 m radius: 60 - 12 m is beyond the 12 + 5 m area on both samples.
        navigator.update(back(60.0, 12f, 5_000L), 5_000L, false)
        assertNotNull("one sample is not enough", navigator.pendingDecisionToken())
        navigator.update(back(60.0, 12f, 6_000L), 6_000L, false)
        assertNull(navigator.pendingDecisionToken())
    }

    @Test fun coarseFixesThatDoNotProveDepartureKeepTheQuestion() {
        for ((backM, accuracyM) in listOf(25.0 to 12f, 60.0 to 16f)) {
            val navigator = RouteNavigator().apply { setRoute(route()) }
            dwellAtTheDestination(navigator, 1_000L)
            // 25 - 12 m stays inside 12 + 5 m; a 16 m radius is not a trusted location.
            navigator.update(back(backM, accuracyM, 5_000L), 5_000L, false)
            navigator.update(back(backM, accuracyM, 6_000L), 6_000L, false)
            assertNotNull("back=$backM accuracy=$accuracyM", navigator.pendingDecisionToken())
        }
    }

    private fun back(metres: Double, accuracyM: Float, at: Long) = TrustedLocation(
        37.0, 127.001 - metres / (111_194.92664455874 * kotlin.math.cos(Math.toRadians(37.0))), accuracyM, at,
    )
    private fun fix(at: Long) = TrustedLocation(37.0, 127.001, 1f, at)
    /** S07-32 (R6): four 1 s samples at the destination = 3 s of consecutive evidence; the question opens on the last. */
    private fun dwellAtTheDestination(navigator: RouteNavigator, fromMs: Long) {
        for (at in fromMs until fromMs + 3_000L step 1_000L) {
            navigator.update(fix(at), at, false)
            assertNull("at=$at", navigator.pendingDecisionToken())
        }
        navigator.update(fix(fromMs + 3_000L), fromMs + 3_000L, false)
    }
    private fun route() = WalkingRoute(
        "STAIR_AVOID", WalkingRouteSummary(89, 90),
        listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0, 127.001)), emptyList(),
        providerRouteId = "same-provider-route",
    )
}

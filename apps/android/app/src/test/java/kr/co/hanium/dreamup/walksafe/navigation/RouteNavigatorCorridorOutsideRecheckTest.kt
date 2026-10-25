package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteHeadingEstimate
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchQuality
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchReason
import org.junit.Assert.*
import org.junit.Test

/** S07-25: the existing matcher can withhold position before confirmed deviation is justified. */
class RouteNavigatorCorridorOutsideRecheckTest {
    @Test fun corridorOutsideBeforeTheExistingDeviationDistanceOffersRecheck() {
        val nav = navigator()
        val update = sample(nav, outside(7_000L))
        val match = requireNotNull(nav.currentRouteMatch())
        assertEquals(RouteMatchReason.CORRIDOR_OUTSIDE, match.reason)
        assertEquals(RouteMatchQuality.UNMATCHED, match.quality)
        assertTrue(requireNotNull(match.crossTrackDistanceM) in 12.0..12.5)
        assertTrue(match.crossTrackDistanceM!! - 1.0 < RouteNavigatorConfig().offRouteDistanceM)
        assertRecheck(update)
        assertNotNull(nav.pendingDecisionToken())
    }

    @Test fun repeatedOutsideKeepsTheDecisionAndDoesNotRepeatItsSpeech() {
        val nav = navigator()
        assertRecheck(sample(nav, outside(7_000L)))
        val token = requireNotNull(nav.pendingDecisionToken())
        val repeated = sample(nav, outside(8_000L))
        assertEquals(token, nav.pendingDecisionToken())
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, repeated.pendingUserDecision)
        assertNull(repeated.instruction)
        assertFalse(repeated.offRoute)
        assertFalse(repeated.shouldReroute)
    }

    @Test fun explicitRecheckWhileStillOutsideRequestsANewCheck() {
        val nav = navigator()
        assertRecheck(sample(nav, outside(7_000L)))
        val oldToken = requireNotNull(nav.pendingDecisionToken())
        assertEquals("off_route_location_recheck_requested",
            nav.selectDeviationChoice(RouteDeviationChoice.RECHECK_LOCATION, oldToken).reason)
        assertRecheck(sample(nav, outside(8_000L)))
        assertNotEquals(oldToken, nav.pendingDecisionToken())
        assertEquals("route_decision_stale",
            nav.selectDeviationChoice(RouteDeviationChoice.RECHECK_LOCATION, oldToken).reason)
    }

    @Test fun freshOnRouteDoesNotResumeUntilTheUserRequestsRecheck() {
        val nav = navigator()
        assertRecheck(sample(nav, outside(7_000L)))
        val blocked = sample(nav, onRoute(8_000L))
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, blocked.pendingUserDecision)
        assertNull(nav.currentInstruction(onRoute(8_000L)))
        val token = requireNotNull(nav.pendingDecisionToken())
        nav.selectDeviationChoice(RouteDeviationChoice.RECHECK_LOCATION, token)
        val fresh = sample(nav, onRoute(9_000L))
        assertEquals("guidance_facing_recheck_wait", fresh.reason)
        assertNull(fresh.instruction)
        assertNotNull(nav.currentAcceptedRouteMatchFor(9_000L))
        // sample checks at fix time + 100 ms, so this episode starts at 9,100 ms.
        val beforeGrace = requireNotNull(nav.retryGuidance(10_599L))
        assertEquals("guidance_facing_recheck_wait", beforeGrace.reason)
        assertNull(beforeGrace.instruction)
        val announced = requireNotNull(nav.retryGuidance(10_600L))
        assertEquals("route_guidance", announced.reason)
        assertNotNull(announced.instruction)
        assertNull(announced.pendingUserDecision)
        assertNull(fresh.pendingUserDecision)
        assertFalse(fresh.offRoute)
        assertFalse(fresh.shouldReroute)
    }

    @Test fun duplicateDoesNotReplaceTheOutsideDecision() {
        val nav = navigator()
        val point = outside(7_000L)
        assertRecheck(sample(nav, point))
        val token = nav.pendingDecisionToken()
        val duplicate = sample(nav, point, nowMs = 7_200L)
        assertEquals("location_sample_duplicate", duplicate.reason)
        assertEquals(token, nav.pendingDecisionToken())
        assertNull(duplicate.instruction)
        assertFalse(duplicate.shouldReroute)
    }

    @Test fun anOlderRawSampleCannotReplaceTheOutsideDecision() {
        val nav = navigator()
        assertRecheck(sample(nav, outside(7_000L)))
        val token = nav.pendingDecisionToken()
        val older = sample(nav, outside(6_000L), nowMs = 8_000L)
        assertEquals("location_sample_not_newer", older.reason)
        assertEquals(token, nav.pendingDecisionToken())
        assertNull(older.instruction)
        assertFalse(older.shouldReroute)
    }

    @Test fun staleAndFutureInputsCannotCreateARecheckDecision() {
        listOf(outside(2_000L) to 20_000L, outside(9_000L) to 8_000L).forEach { (point, now) ->
            val nav = navigator()
            val rejected = sample(nav, point, now)
            assertEquals("location_sample_invalid_or_stale", rejected.reason)
            assertNull(nav.pendingDecisionToken())
            assertNull(rejected.instruction)
            assertFalse(rejected.shouldReroute)
        }
    }

    @Test fun rawTimestampReuseWithNewFilteredTimeIsNotNewOutsideEvidence() {
        val nav = navigator()
        val point = outside(7_000L)
        assertRecheck(sample(nav, point))
        val token = nav.pendingDecisionToken()
        val update = nav.update(point, nowMs = 8_100L, requestInFlight = false,
            filteredPosition = filtered(outside(8_000L)))
        assertEquals(token, nav.pendingDecisionToken())
        assertFalse(update.offRoute)
        assertFalse(update.shouldReroute)
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, update.pendingUserDecision)
    }

    @Test fun aGapAndRepeatedOutsideNeverConfirmDeviationFromRecheckAlone() {
        val nav = navigator()
        assertRecheck(sample(nav, outside(7_000L)))
        val token = nav.pendingDecisionToken()
        val afterGap = sample(nav, outside(30_000L))
        assertEquals(token, nav.pendingDecisionToken())
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, afterGap.pendingUserDecision)
        assertFalse(afterGap.offRoute)
        assertFalse(afterGap.shouldReroute)
    }

    @Test fun routeReplacementRetiresTheOldRecheckToken() {
        val nav = navigator()
        assertRecheck(sample(nav, outside(7_000L)))
        val token = requireNotNull(nav.pendingDecisionToken())
        nav.setRoute(route())
        assertNull(nav.pendingDecisionToken())
        assertEquals("route_decision_stale",
            nav.selectDeviationChoice(RouteDeviationChoice.RECHECK_LOCATION, token).reason)
        assertTrue(nav.hasRoute())
    }

    @Test fun existingFarDeviationStillRequiresTwoSamplesAndExplicitReroute() {
        val nav = navigator()
        val far = outside(7_000L).copy(latitude = 37.0006)
        val first = sample(nav, far)
        assertEquals("off_route_pending", first.reason)
        assertFalse(first.offRoute)
        val second = sample(nav, far.copy(elapsedRealtimeMs = 8_000L))
        assertTrue(second.offRoute)
        assertFalse(second.shouldReroute)
        assertEquals(RouteNavigatorUserDecision.REROUTE, second.pendingUserDecision)
        val token = requireNotNull(nav.pendingDecisionToken())
        assertTrue(nav.selectDeviationChoice(RouteDeviationChoice.NEW_ROUTE, token).shouldReroute)
    }

    @Test fun acceptedOnRouteInputsKeepNormalGuidance() {
        val nav = navigator()
        val update = sample(nav, onRoute(7_000L))
        assertEquals(RouteMatchReason.MATCHED, nav.currentRouteMatch()?.reason)
        assertEquals("route_guidance", update.reason)
        assertNull(nav.pendingDecisionToken())
        assertFalse(update.offRoute)
        assertFalse(update.shouldReroute)
    }

    @Test fun diagnosticModeDoesNotBecomeAProductRecheckDecision() {
        val nav = navigator(diagnostic = true)
        val update = sample(nav, outside(7_000L))
        assertEquals(RouteMatchReason.CORRIDOR_OUTSIDE, nav.currentRouteMatch()?.reason)
        assertNull(nav.pendingDecisionToken())
        assertFalse(update.userDecisionRequired)
        assertFalse(update.shouldReroute)
    }

    private fun assertRecheck(update: RouteNavigatorUpdate) {
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, update.pendingUserDecision)
        assertTrue(update.userDecisionRequired)
        assertTrue(update.cancelStaleNavigationSpeech)
        assertFalse(update.offRoute)
        assertFalse(update.shouldReroute)
        assertFalse(update.arrived)
        val text = requireNotNull(update.instruction)
        assertTrue(text, text.contains("위치 다시 확인"))
        assertTrue(text, text.contains("경로"))
        listOf("꺾으세요", "이동하세요", "직진하세요", "시 방향").forEach { assertFalse(text, text.contains(it)) }
    }

    private fun navigator(diagnostic: Boolean = false): RouteNavigator = RouteNavigator(
        allowDegradedRouteGuidance = diagnostic,
    ).apply {
        setRoute(route(), origin = TrustedLocation(37.0, 127.0, 1f, 0L))
        sample(this, TrustedLocation(37.0, 127.0002, 1f, 1_000L))
    }

    private fun route() = WalkingRoute("STAIR_AVOID", WalkingRouteSummary(200, 200),
        listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0, 127.002)), emptyList())

    private fun outside(at: Long) = TrustedLocation(37.00011, 127.0003, 1f, at)
    private fun onRoute(at: Long) = TrustedLocation(37.0, 127.0003, 1f, at)
    private fun filtered(point: TrustedLocation) = FilteredRoutePosition(
        RoutePoint(point.latitude, point.longitude), 1.0, point.elapsedRealtimeMs,
        heading = RouteHeadingEstimate(90.0, 5.0),
    )
    private fun sample(nav: RouteNavigator, point: TrustedLocation, nowMs: Long = point.elapsedRealtimeMs + 100L) =
        nav.update(point, nowMs, requestInFlight = false, filteredPosition = filtered(point))
}

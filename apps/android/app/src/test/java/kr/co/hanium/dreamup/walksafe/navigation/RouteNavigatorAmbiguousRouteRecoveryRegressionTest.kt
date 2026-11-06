package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteHeadingEstimate
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchQuality
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S07-26: transient ambiguity reacquires automatically; persistent ambiguity and real deviation retain their decisions. */
class RouteNavigatorAmbiguousRouteRecoveryRegressionTest {
    private val midpoint = TrustedLocation(37.000025, 127.0005, 5f, 1_000L)

    @Test fun ambiguousParallelCandidatesWaitWithoutUsingTheUnsafeSnap() {
        val nav = navigator()
        val waiting = nav.update(midpoint, 1_000L, false)
        assertEquals(RouteMatchQuality.LOW, nav.currentRouteMatch()?.quality)
        assertEquals(RouteMatchReason.AMBIGUOUS_CANDIDATES, nav.currentRouteMatch()?.reason)
        assertEquals("route_geometry_recheck_wait", waiting.reason)
        assertNull(waiting.pendingUserDecision); assertNull(nav.pendingDecisionToken())
        assertNull(waiting.instruction); assertNull(waiting.speechCueToken)
        assertNull(nav.currentAcceptedRouteMatchFor(1_000L)); assertNull(nav.currentBearingDeg())
        assertFalse(waiting.offRoute); assertFalse(waiting.shouldReroute); assertFalse(waiting.arrived)
    }

    @Test fun aLocationRecheckChoiceCannotSkipTheInitialAutomaticWait() {
        val nav = navigator(); nav.update(midpoint, 1_000L, false)
        val choice = nav.selectDeviationChoice(RouteDeviationChoice.RECHECK_LOCATION)
        assertEquals("reroute_decision_missing", choice.reason)
        assertNull(choice.pendingUserDecision); assertFalse(choice.shouldReroute); assertTrue(nav.hasRoute())
        assertNull(nav.currentAcceptedRouteMatchFor(1_000L))
    }

    @Test fun repeatedAmbiguityKeepsTheFirstDeadlineThenOneProblemDecision() {
        val nav = navigator(); nav.update(midpoint, 1_000L, false)
        listOf(2_000L, 4_000L, 9_999L).forEach { at ->
            val waiting = nav.update(midpoint.copy(elapsedRealtimeMs = at), at, false)
            assertEquals("route_geometry_recheck_wait", waiting.reason)
            assertNull(waiting.pendingUserDecision); assertNull(waiting.instruction)
            assertNull(nav.currentAcceptedRouteMatchFor(at))
        }
        val problem = nav.update(midpoint.copy(elapsedRealtimeMs = 10_000L), 10_000L, false)
        assertEquals("route_geometry_recheck_required", problem.reason)
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, problem.pendingUserDecision)
        assertTrue(requireNotNull(problem.instruction).contains("확인되면 길 안내를 자동으로 이어갑니다"))
        val token = requireNotNull(nav.pendingDecisionToken())
        val repeated = nav.update(midpoint.copy(elapsedRealtimeMs = 10_100L), 10_100L, false)
        assertEquals(token, nav.pendingDecisionToken()); assertNull(repeated.instruction)
        assertFalse(repeated.offRoute); assertFalse(repeated.shouldReroute); assertFalse(repeated.arrived)
    }

    @Test fun duplicateAmbiguityCannotManufactureRecoveryOrADecision() {
        val nav = navigator(); nav.update(midpoint, 1_000L, false)
        val duplicate = nav.update(midpoint.copy(), 1_500L, false)
        assertNull(duplicate.instruction); assertNull(duplicate.speechCueToken)
        assertNull(nav.pendingDecisionToken()); assertNull(nav.currentAcceptedRouteMatchFor(1_000L))
        assertTrue(nav.hasRoute()); assertFalse(duplicate.arrived)
    }

    @Test fun aDuplicateOfTheFirstGoodFixCannotBeTheSecondRecoverySample() {
        val nav = navigator(); nav.update(midpoint, 1_000L, false)
        val first = normalLocation(2_000L)
        val waiting = nav.update(first, 2_000L, false, filteredPosition(first))
        assertEquals("route_geometry_recheck_wait", waiting.reason)
        val duplicate = nav.update(first.copy(), 2_100L, false, filteredPosition(first))
        assertNull(duplicate.speechCueToken); assertNull(nav.currentInstruction(first))
        val second = normalLocation(2_200L)
        val recovered = nav.update(second, 2_200L, false, filteredPosition(second),
            facingObservation = RouteFacingObservation(90.0, 3.0, 2_200L))
        assertNotNull(nav.currentAcceptedRouteMatchFor(2_200L)); assertNotNull(recovered.speechCueToken)
        assertNull(recovered.pendingUserDecision); assertFalse(recovered.offRoute)
    }

    @Test fun twoDistinctFreshNormalFixesAutomaticallyRestoreGuidanceWithoutAChoice() {
        val nav = navigator(); nav.update(midpoint, 1_000L, false)
        val first = normalLocation(2_000L)
        val waiting = nav.update(first, 2_000L, false, filteredPosition(first),
            facingObservation = RouteFacingObservation(90.0, 3.0, 2_000L))
        assertEquals("route_geometry_recheck_wait", waiting.reason); assertNull(waiting.speechCueToken)
        val second = normalLocation(2_100L)
        val recovered = nav.update(second, 2_100L, false, filteredPosition(second),
            facingObservation = RouteFacingObservation(90.0, 3.0, 2_100L))
        assertNotNull(recovered.instruction); assertNotNull(recovered.speechCueToken)
        assertNull(nav.pendingDecisionToken()); assertNotNull(nav.currentAcceptedRouteMatchFor(2_100L))
        assertFalse(recovered.offRoute); assertFalse(recovered.shouldReroute); assertFalse(recovered.arrived)
    }

    @Test fun aFreshAmbiguousSampleResetsOnlyRecoveryEvidence() {
        val nav = navigator(); nav.update(midpoint, 1_000L, false)
        val first = normalLocation(2_000L); nav.update(first, 2_000L, false, filteredPosition(first))
        nav.update(midpoint.copy(elapsedRealtimeMs = 2_100L), 2_100L, false)
        val good = normalLocation(2_200L)
        assertEquals("route_geometry_recheck_wait", nav.update(good, 2_200L, false, filteredPosition(good)).reason)
        assertNull(nav.currentInstruction(good)); assertNull(nav.pendingDecisionToken())
        val second = normalLocation(2_300L)
        val recovered = nav.update(second, 2_300L, false, filteredPosition(second),
            facingObservation = RouteFacingObservation(90.0, 3.0, 2_300L))
        assertNotNull(recovered.speechCueToken); assertFalse(recovered.shouldReroute)
    }

    @Test fun persistentAmbiguityCannotAuthorizeRerouteOrEndTheInstalledRoute() {
        val nav = navigator(); nav.update(midpoint, 1_000L, false)
        nav.update(midpoint.copy(elapsedRealtimeMs = 10_000L), 10_000L, false)
        val token = requireNotNull(nav.pendingDecisionToken())
        listOf(RouteDeviationChoice.NEW_ROUTE, RouteDeviationChoice.END_NAVIGATION).forEach { choice ->
            val rejected = nav.selectDeviationChoice(choice, token)
            assertEquals("reroute_decision_missing", rejected.reason)
            assertFalse(rejected.shouldReroute); assertFalse(rejected.offRoute)
            assertTrue(nav.hasRoute()); assertEquals(token, nav.pendingDecisionToken())
        }
    }

    @Test fun aNormalInitialFixStillOffersGuidanceWithoutALocationRecheck() {
        val nav = navigator(); val normal = normalLocation(1_000L)
        val waiting = nav.update(normal, 1_000L, false, filteredPosition(normal))
        assertEquals("guidance_facing_recheck_wait", waiting.reason)
        assertNull(waiting.instruction); assertNull(waiting.pendingUserDecision)
        val update = requireNotNull(nav.retryGuidance(2_500L))
        assertEquals("route_guidance", update.reason); assertNotNull(update.speechCueToken)
        assertNull(nav.pendingDecisionToken()); assertFalse(update.offRoute); assertFalse(update.shouldReroute)
    }

    @Test fun aProblemDecisionStillNeedsTwoFreshSamplesForAutomaticRecovery() {
        val nav = navigator(); nav.update(midpoint, 1_000L, false)
        nav.update(midpoint.copy(elapsedRealtimeMs = 10_000L), 10_000L, false)
        assertNotNull(nav.pendingDecisionToken())
        val first = normalLocation(10_100L)
        val waiting = nav.update(first, 10_100L, false, filteredPosition(first))
        assertEquals("route_geometry_recheck_required", waiting.reason); assertNull(waiting.speechCueToken)
        val second = normalLocation(10_200L)
        val recovered = nav.update(second, 10_200L, false, filteredPosition(second),
            facingObservation = RouteFacingObservation(90.0, 3.0, 10_200L))
        assertNotNull(recovered.speechCueToken); assertNull(nav.pendingDecisionToken())
        assertFalse(recovered.offRoute); assertFalse(recovered.shouldReroute)
    }

    @Test fun confirmedOutsideRouteStillUsesTheExistingRerouteDecision() {
        val nav = RouteNavigator(RouteNavigatorConfig(offRouteConfirmSamples = 1)).apply { setRoute(parallelRoute()) }
        val update = nav.update(TrustedLocation(37.0, 127.01, 5f, 1_000L), 1_000L, false)
        assertEquals(RouteNavigatorUserDecision.REROUTE, update.pendingUserDecision)
        assertTrue(update.offRoute); assertFalse(update.shouldReroute)
        assertTrue(requireNotNull(update.instruction).contains("새 경로 요청"))
    }

    @Test fun recoveryDoesNotRequireStandingOnTheSameOneMeterSegment() {
        val points = (0..200).map { RoutePoint(0.0, it / 111_194.92664455874) }
        val nav = RouteNavigator(nowElapsedRealtimeMs = { 1_000L }).apply {
            setRoute(WalkingRoute("TEST", WalkingRouteSummary(200, 200), points, emptyList()))
        }
        fun fix(east: Double, at: Long) = TrustedLocation(0.0, east / 111_194.92664455874, 1f, at)
        nav.update(fix(10.2, 1_000L), 1_000L, false)
        nav.onUntrustedLocation()
        val first = fix(11.6, 2_000L)
        nav.update(first, 2_000L, false, filteredPosition(first))
        assertNull(nav.currentAcceptedRouteMatchFor(2_000L))
        val second = fix(13.0, 3_000L)
        nav.update(second, 3_000L, false, filteredPosition(second),
            facingObservation = RouteFacingObservation(90.0, 3.0, 3_000L))
        assertNotNull(nav.currentAcceptedRouteMatchFor(3_000L))
        assertNull(nav.pendingDecisionToken())
    }

    @Test fun recoveryCannotJumpToANearbyParallelReturnLeg() {
        val nav = navigator(); nav.update(midpoint, 1_000L, false)
        val first = normalLocation(2_000L)
        nav.update(first, 2_000L, false, filteredPosition(first))
        val otherLeg = first.copy(latitude = 37.00005, elapsedRealtimeMs = 3_000L)
        nav.update(otherLeg, 3_000L, false, filteredPosition(otherLeg))
        assertNull(nav.currentAcceptedRouteMatchFor(3_000L))
        assertNull(nav.currentInstruction(otherLeg))
    }

    private fun normalLocation(atMs: Long) = TrustedLocation(37.0, 127.0005, 1f, atMs)

    private fun filteredPosition(location: TrustedLocation) = FilteredRoutePosition(
        point = RoutePoint(location.latitude, location.longitude),
        horizontalAccuracyM = location.accuracyM.toDouble(),
        elapsedRealtimeMs = location.elapsedRealtimeMs,
        heading = RouteHeadingEstimate(90.0, standardDeviationDeg = 5.0),
    )

    private fun navigator() = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0)).apply {
        setRoute(parallelRoute())
    }

    private fun parallelRoute() = WalkingRoute(
        priority = "STAIR_AVOID",
        summary = WalkingRouteSummary(distanceM = 190, durationS = 180),
        polyline = listOf(
            RoutePoint(37.0, 127.0),
            RoutePoint(37.0, 127.001),
            RoutePoint(37.00005, 127.001),
            RoutePoint(37.00005, 127.0),
        ),
        guidePoints = emptyList(),
    )
}

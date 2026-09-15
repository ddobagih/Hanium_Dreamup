package kr.co.hanium.dreamup.walksafe.navigation

import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

class RouteNavigatorOrderedAlignmentTest {
    @Test fun freshDepartureSupportsTwentyToNinetyMeterRoutesEvenWhenAccuracyIncludesTheEndpoint() {
        listOf(20.0, 50.0, 90.0).forEach { length ->
            listOf(90.0 to 12, 0.0 to 3, 270.0 to 6, 180.0 to 9).forEach { (heading, hour) ->
                val origin = fix(0.0, accuracy = 40f)
                val navigator = navigator(route(point(0.0), point(length)), origin)
                val update = navigator.update(origin, 1_000, false, facingObservation = facing(heading))
                assertClock(hour, update)
                assertEquals(RouteAlignmentReason.DEPARTURE_SEGMENT, update.routeAlignmentDiagnostic?.reason)
                assertFalse(update.arrivalCandidate)
                assertFalse(update.instruction?.contains("뒤로 걸") == true)
            }
        }
    }

    @Test fun delayedResponseKeepsTheOriginalRequestAnchorWhenTheCurrentFreshFixStillAgrees() {
        val origin = fix(0.0, accuracy = 20f)
        val navigator = navigator(route(point(0.0), point(20.0)), origin)
        val update = navigator.update(origin.copy(elapsedRealtimeMs = 31_000), 31_000, false,
            facingObservation = facing(90.0, 31_000))
        assertClock(12, update)
        assertEquals(RouteAlignmentReason.DEPARTURE_SEGMENT, update.routeAlignmentDiagnostic?.reason)
        assertEquals(1_000L, origin.elapsedRealtimeMs)
    }

    @Test fun delayedResponseAfterTheUserTurnsTheCornerUsesTheCurrentAcceptedLeg() {
        val navigator = navigator(route(point(0.0), point(10.0), point(10.0, -100.0)), fix(0.0))
        val update = navigator.update(fix(10.0, -30.0, time = 31_000), 31_000, false,
            facingObservation = facing(180.0, 31_000))
        assertClock(12, update)
        assertEquals(RouteAlignmentReason.CURRENT_SEGMENT, update.routeAlignmentDiagnostic?.reason)
        assertEquals(1, update.routeAlignmentDiagnostic?.segmentIndex)
    }

    @Test fun nearbyFirstCornerDoesNotReplaceDepartureDirectionWithTheOutgoingLeg() {
        val navigator = navigator(cornerRoute(), fix(0.0, accuracy = 20f))
        val update = navigator.update(fix(0.0, accuracy = 20f), 1_000, false,
            facingObservation = facing(90.0))
        assertClock(12, update)
        assertEquals(0, update.routeAlignmentDiagnostic?.segmentIndex)
        assertTrue(update.instruction?.contains("20m 앞, 오른쪽으로 꺾으세요") == true)
    }

    @Test fun targetStopsAtTheCornerUntilTheCurrentProjectionReachesItsVertex() {
        val navigator = navigator(cornerRoute(), null)
        val before = navigator.update(fix(18.0), 1_000, false, facingObservation = facing(90.0))
        assertClock(12, before)
        assertEquals(0, before.routeAlignmentDiagnostic?.segmentIndex)
        val vertex = navigator.update(fix(20.0, time = 2_000), 2_000, false,
            facingObservation = facing(180.0, 2_000))
        assertClock(12, vertex)
        assertEquals(1, vertex.routeAlignmentDiagnostic?.segmentIndex)
    }

    @Test fun realOpposingOutbackRemainsAmbiguousEvenWithADepartureRequest() {
        listOf<TrustedLocation?>(null, fix(0.0, accuracy = 10f)).forEach { origin ->
            val navigator = navigator(route(point(0.0), point(100.0), point(0.0, 4.0)), origin)
            val update = navigator.update(fix(0.0, 4.0, accuracy = 10f), 1_000, false,
                facingObservation = facing(90.0))
            assertEquals(RouteAlignmentReason.BRANCH_AMBIGUOUS, update.routeAlignmentDiagnostic?.reason)
            assertNoClock(update)
            assertNull(navigator.currentAcceptedRouteMatchFor(1_000))
        }
    }

    @Test fun restoredProjectionSelectsTheEarlierLegAfterRetreatWithoutLockingGuideProgressForward() {
        val navigator = navigator(route(point(0.0), point(100.0), point(100.0, -200.0)), fix(0.0))
        navigator.update(fix(30.0), 1_000, false, facingObservation = facing(90.0))
        navigator.update(fix(100.0, -20.0, time = 2_000), 2_000, false, facingObservation = facing(180.0, 2_000))
        val back = navigator.update(fix(70.0, time = 3_000), 3_000, false, facingObservation = facing(90.0, 3_000))
        assertClock(12, back)
        assertEquals(0, back.routeAlignmentDiagnostic?.segmentIndex)
    }

    @Test fun clockRotationUsesNewCompassWhileInFlightSpeechAndTenSecondCadenceRemainProtected() {
        val origin = fix(0.0, accuracy = 20f)
        val navigator = navigator(route(point(0.0), point(50.0)), origin)
        val first = navigator.update(origin, 1_000, false, facingObservation = facing(90.0))
        assertClock(12, first)
        assertTrue(navigator.reserveInstruction(first))
        val inFlight = requireNotNull(navigator.currentGuidance(1_100, facing(0.0, 1_100)))
        assertEquals("guidance_in_flight", inFlight.reason)
        assertFalse(inFlight.cancelStaleNavigationSpeech)
        navigator.acknowledgeInstruction(first, 2_000)
        val manual = requireNotNull(navigator.currentGuidance(2_100, facing(0.0, 2_100)))
        assertClock(3, manual)
        assertTrue(navigator.reserveInstruction(manual))
        navigator.acknowledgeInstruction(manual, 3_000)
        assertNull(navigator.retryGuidance(3_001, facing(270.0, 3_001))?.instruction)
        navigator.update(origin.copy(elapsedRealtimeMs = 12_000), 12_000, false,
            facingObservation = facing(270.0, 12_000))
        val periodic = requireNotNull(navigator.retryGuidance(13_000, facing(270.0, 13_000)))
        assertClock(6, periodic)
        assertFalse(periodic.instruction?.contains("왼쪽으로") == true)
        assertFalse(periodic.instruction?.contains("오른쪽으로") == true)
    }

    @Test fun staleCompassStillSuppressesClockEvenWhenTheOrderedRouteTargetIsKnown() {
        val navigator = navigator(route(point(0.0), point(90.0)), fix(0.0))
        val update = navigator.update(fix(0.0), 1_000, false, facingObservation = facing(90.0, 499))
        assertNoClock(update)
        assertNotNull(update.routeAlignmentDiagnostic?.bearingDegreesTrueNorth)
        assertTrue(update.instruction?.contains("현재 바라보는 방향을 확인할 수 없습니다") == true)
    }

    @Test fun replacementAndInterruptionDoNotReviveThePreviousDepartureAnchor() {
        val short = route(point(0.0), point(20.0))
        val navigator = navigator(short, fix(0.0, accuracy = 40f))
        assertClock(12, navigator.update(fix(0.0, accuracy = 40f), 1_000, false, facingObservation = facing(90.0)))
        navigator.onPositioningEvidenceInterrupted()
        assertNoClock(navigator.update(fix(0.0, time = 2_000, accuracy = 40f), 2_000, false,
            facingObservation = facing(90.0, 2_000)))
        navigator.setRoute(short)
        assertNoClock(navigator.update(fix(0.0, time = 3_000, accuracy = 40f), 3_000, false,
            facingObservation = facing(90.0, 3_000)))
        navigator.clear()
        assertNull(navigator.currentGuidance(3_001, facing(90.0, 3_001)))
    }

    @Test fun realArrivalEvidenceStillWinsAfterDepartureAndObservedProgress() {
        val navigator = RouteNavigator().apply { setRoute(route(point(0.0), point(100.0)), origin = fix(0.0)) }
        navigator.update(fix(0.0), 1_000, false, facingObservation = facing(90.0))
        val nearEnd = navigator.update(fix(100.0, time = 2_000), 2_000, false, facingObservation = facing(90.0, 2_000))
        assertNoClock(nearEnd)
        val arrived = navigator.update(fix(100.0, time = 3_000), 3_000, false, facingObservation = facing(90.0, 3_000))
        assertTrue(arrived.arrivalCandidate)
        assertEquals(RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION, arrived.pendingUserDecision)
    }

    @Test fun futureOrRemoteRequestOriginDoesNotAuthorizeAShortRouteDeparture() {
        listOf(fix(0.0, time = 1_001), fix(200.0)).forEach { origin ->
            val navigator = navigator(route(point(0.0), point(20.0)), origin)
            val update = navigator.update(fix(0.0, accuracy = 40f), 1_000, false, facingObservation = facing(90.0))
            assertNoClock(update)
        }
    }

    @Test fun originAlreadyProjectedInsideTheRouteDoesNotForceTheFirstSegment() {
        val origin = fix(100.0, -100.0)
        val navigator = navigator(route(point(0.0), point(100.0), point(100.0, -300.0)), origin)
        val update = navigator.update(origin, 1_000, false, facingObservation = facing(180.0))
        assertClock(12, update)
        assertEquals(RouteAlignmentReason.CURRENT_SEGMENT, update.routeAlignmentDiagnostic?.reason)
        assertEquals(1, update.routeAlignmentDiagnostic?.segmentIndex)
    }

    @Test fun clearProviderStartOffsetUsesAConnectorButSmallUncertainOffsetDoesNotTurnTheUserSideways() {
        val route = route(point(0.0), point(100.0))
        val offset = fix(0.0, 15.0)
        val connector = navigator(route, offset).update(offset, 1_000, false, facingObservation = facing(180.0))
        assertClock(12, connector)
        assertEquals(RouteAlignmentReason.DEPARTURE_CONNECTOR, connector.routeAlignmentDiagnostic?.reason)
        assertTrue(connector.instruction?.contains("경로 시작점은") == true)
        val uncertain = offset.copy(accuracyM = 20f)
        val departure = navigator(route, uncertain).update(uncertain, 1_000, false, facingObservation = facing(90.0))
        assertClock(12, departure)
        assertEquals(RouteAlignmentReason.DEPARTURE_SEGMENT, departure.routeAlignmentDiagnostic?.reason)
    }

    @Test fun lateralStartOffsetStillUsesTheConnectorWhenProjectionIsInsideTheFirstSegment() {
        val route = route(point(0.0), point(100.0))
        val offset = fix(5.0, 15.0)
        val update = navigator(route, offset).update(offset, 1_000, false, facingObservation = facing(180.0))
        assertClock(1, update)
        assertEquals(RouteAlignmentReason.DEPARTURE_CONNECTOR, update.routeAlignmentDiagnostic?.reason)
        assertFalse(update.instruction?.contains("왼쪽으로") == true)
        val uncertain = offset.copy(accuracyM = 20f)
        val alongRoute = navigator(route, uncertain).update(uncertain, 1_000, false, facingObservation = facing(90.0))
        assertClock(12, alongRoute)
        assertEquals(RouteAlignmentReason.DEPARTURE_SEGMENT, alongRoute.routeAlignmentDiagnostic?.reason)
    }

    @Test fun delayedArrivalAtAShortFirstLegVertexEndsTheDepartureAuthority() {
        val navigator = navigator(route(point(0.0), point(7.0), point(7.0, -100.0)), fix(0.0))
        val update = navigator.update(fix(7.0, time = 31_000), 31_000, false,
            facingObservation = facing(180.0, 31_000))
        assertClock(12, update)
        assertEquals(RouteAlignmentReason.CURRENT_SEGMENT, update.routeAlignmentDiagnostic?.reason)
        assertEquals(1, update.routeAlignmentDiagnostic?.segmentIndex)
    }

    @Test fun aSmallVertexProjectionWithinTheOriginUncertaintyDoesNotEndDepartureEarly() {
        val navigator = navigator(route(point(0.0), point(3.0), point(3.0, -100.0)), fix(0.0))
        val update = navigator.update(fix(3.0, time = 31_000), 31_000, false,
            facingObservation = facing(90.0, 31_000))
        assertClock(12, update)
        assertEquals(RouteAlignmentReason.DEPARTURE_SEGMENT, update.routeAlignmentDiagnostic?.reason)
        assertEquals(0, update.routeAlignmentDiagnostic?.segmentIndex)
    }

    @Test fun publicLiveTmapFixtureKeepsTheOriginSnapDistinctFromTheFirstRouteLeg() {
        val route = publicLiveRoute()
        listOf(3f to RouteAlignmentReason.DEPARTURE_CONNECTOR, 20f to RouteAlignmentReason.DEPARTURE_SEGMENT).forEach { (accuracy, reason) ->
            val origin = TrustedLocation(37.5662952, 126.9779451, accuracy, 1_000)
            val navigator = navigator(route, origin)
            val update = navigator.update(origin.copy(elapsedRealtimeMs = 31_000), 31_000, false,
                facingObservation = facing(270.0, 31_000))
            println("PUBLIC_TMAP accuracy=$accuracy diagnostic=${update.routeAlignmentDiagnostic} instruction=${update.instruction}")
            assertEquals(reason, update.routeAlignmentDiagnostic?.reason)
            assertClock(if (accuracy == 3f) 9 else 12, update)
            assertEquals(0, update.routeAlignmentDiagnostic?.segmentIndex)
        }
    }

    private fun navigator(route: WalkingRoute, origin: TrustedLocation?) = RouteNavigator(allowDegradedRouteGuidance = true).apply {
        setRoute(route, origin = origin)
    }
    private fun cornerRoute() = route(point(0.0), point(20.0), point(20.0, -80.0)).copy(guidePoints = listOf(
        WalkingRouteGuidePoint(0, point(20.0), null, 20, 80, turnType = 13),
    ))
    private fun route(vararg points: RoutePoint): WalkingRoute {
        val distanceM = points.toList().zipWithNext().sumOf { (a, b) -> haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude) }.roundToInt()
        return WalkingRoute("TEST", WalkingRouteSummary(distanceM, distanceM), points.toList(), emptyList())
    }
    private fun point(east: Double, north: Double = 0.0) = RoutePoint(north / 111_194.92664455874, east / 111_194.92664455874)
    private fun fix(east: Double, north: Double = 0.0, time: Long = 1_000, accuracy: Float = 3f): TrustedLocation =
        point(east, north).let { TrustedLocation(it.latitude, it.longitude, accuracy, time) }
    private fun facing(heading: Double, time: Long = 1_000) = RouteFacingObservation(heading, 5.0, time)
    private fun assertClock(hour: Int, update: RouteNavigatorUpdate) {
        assertTrue("$update", update.instruction?.contains("약 ${hour}시 방향") == true)
    }
    private fun assertNoClock(update: RouteNavigatorUpdate) {
        assertFalse("$update", update.instruction?.contains("시 방향") == true)
    }

    // Captured public Seoul City Hall -> Cheonggye Plaza response, 2026-09-15; no user location.
    // Source: work/route-start-facing-20260915/provider/live-normalized-route.json (all geometry retained).
    private fun publicLiveRoute() = WalkingRoute(
        priority = "STAIR_AVOID",
        summary = WalkingRouteSummary(466, 368),
        polyline = listOf(
            RoutePoint(37.566157493985145, 126.97794330490989, null),
            RoutePoint(37.56615749338722, 126.97790997461412, null),
            RoutePoint(37.56615471025223, 126.97759333688305, null),
            RoutePoint(37.56615470626619, 126.97737113491135, null),
            RoutePoint(37.566354683012555, 126.97737112924987, null),
            RoutePoint(37.56662965083942, 126.97736001136667, null),
            RoutePoint(37.566868511952975, 126.97736000460418, null),
            RoutePoint(37.566974055136036, 126.9773544465668, null),
            RoutePoint(37.56698516550335, 126.97738499902333, null),
            RoutePoint(37.56710459606009, 126.97738499564204, null),
            RoutePoint(37.56712403764582, 126.97735166479588, null),
            RoutePoint(37.5672406906979, 126.97734888396856, null),
            RoutePoint(37.56732679174708, 126.9773461040062, null),
            RoutePoint(37.56799060319643, 126.97733219758875, null),
            RoutePoint(37.56803781982844, 126.97732664120262, null),
            RoutePoint(37.56885161348762, 126.97729328786544, null),
            RoutePoint(37.56893493718147, 126.97729606303082, null),
            RoutePoint(37.56899604168537, 126.97732383654707, null),
            RoutePoint(37.56904048195874, 126.97737938578152, null),
            RoutePoint(37.569048815967484, 126.97747104385857, null),
            RoutePoint(37.56904326265241, 126.97755992480424, null),
            RoutePoint(37.56902660315568, 126.97785156536295, null),
            RoutePoint(37.569015496724894, 126.97804043735286, null),
            RoutePoint(37.56899050591031, 126.97839040616503, null),
        ),
        guidePoints = listOf(
            WalkingRouteGuidePoint(0, RoutePoint(37.566157493985145, 126.97794330490989, null), "보행자도로를 따라 51m 이동", 0, 466, 269.9987134391505f, 200, "SP", 11),
            WalkingRouteGuidePoint(1, RoutePoint(37.56615470626619, 126.97737113491135, null), "시청역 5번출구에서 우회전 후 세종대로를 따라 94m 이동", 51, 415, 359.99871426013544f, 13, "GP", 11),
            WalkingRouteGuidePoint(2, RoutePoint(37.56698516550335, 126.97738499902333, null), "횡단보도 후 보행자도로를 따라 13m 이동", 145, 321, 359.9987142242184f, 211, "GP", 15),
            WalkingRouteGuidePoint(3, RoutePoint(37.56710459606009, 126.97738499564204, null), "직진 후 세종대로를 따라 235m 이동", 158, 308, 306.34877706540897f, 11, "GP", 11),
            WalkingRouteGuidePoint(4, RoutePoint(37.56899050591031, 126.97839040616503, null), "도착", 466, 0, null, 201, "EP", 0),
        ),
        steps = listOf(
            WalkingRouteStep(0, 51, 42, listOf(RoutePoint(37.566157493985145, 126.97794330490989, null), RoutePoint(37.56615749338722, 126.97790997461412, null), RoutePoint(37.56615471025223, 126.97759333688305, null), RoutePoint(37.56615470626619, 126.97737113491135, null)), "보행자도로, 51m", "보행자도로", null, 11),
            WalkingRouteStep(1, 94, 97, listOf(RoutePoint(37.56615470626619, 126.97737113491135, null), RoutePoint(37.566354683012555, 126.97737112924987, null), RoutePoint(37.56662965083942, 126.97736001136667, null), RoutePoint(37.566868511952975, 126.97736000460418, null), RoutePoint(37.566974055136036, 126.9773544465668, null), RoutePoint(37.56698516550335, 126.97738499902333, null)), "세종대로, 94m", "세종대로", null, 11),
            WalkingRouteStep(2, 13, 8, listOf(RoutePoint(37.56698516550335, 126.97738499902333, null), RoutePoint(37.56710459606009, 126.97738499564204, null)), "보행자도로, 13m", "보행자도로", null, 15),
            WalkingRouteStep(3, 235, 168, listOf(RoutePoint(37.56710459606009, 126.97738499564204, null), RoutePoint(37.56712403764582, 126.97735166479588, null), RoutePoint(37.5672406906979, 126.97734888396856, null), RoutePoint(37.56732679174708, 126.9773461040062, null), RoutePoint(37.56799060319643, 126.97733219758875, null), RoutePoint(37.56803781982844, 126.97732664120262, null), RoutePoint(37.56885161348762, 126.97729328786544, null), RoutePoint(37.56893493718147, 126.97729606303082, null), RoutePoint(37.56899604168537, 126.97732383654707, null), RoutePoint(37.56904048195874, 126.97737938578152, null), RoutePoint(37.569048815967484, 126.97747104385857, null), RoutePoint(37.56904326265241, 126.97755992480424, null)), "세종대로, 235m", "세종대로", null, 11),
            WalkingRouteStep(4, 73, 53, listOf(RoutePoint(37.56904326265241, 126.97755992480424, null), RoutePoint(37.56902660315568, 126.97785156536295, null), RoutePoint(37.569015496724894, 126.97804043735286, null), RoutePoint(37.56899050591031, 126.97839040616503, null)), "청계천로, 73m", "청계천로", null, 11),
        ),
    )
}

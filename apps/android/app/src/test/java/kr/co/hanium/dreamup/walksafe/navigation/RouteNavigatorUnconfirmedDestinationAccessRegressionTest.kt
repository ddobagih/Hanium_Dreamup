package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.HorizontalUncertaintyKind
import org.junit.Assert.*
import org.junit.Test

/** S07-11/38: provider coordinates are not a verified entrance or final walking connection. */
class RouteNavigatorUnconfirmedDestinationAccessRegressionTest {
    @Test fun zeroGapNoorPointKeepsUnknownAccessAcrossUpdateManualAndRetry() {
        assertZeroGapNotice(DestinationCoordinateKind.NOOR)
    }

    @Test fun zeroGapFrontPointIsAlsoAnUnverifiedEntrance() {
        assertZeroGapNotice(DestinationCoordinateKind.FRONT)
    }

    @Test fun legacyMissingProvenanceStillDoesNotVerifyAnEntrance() {
        val nav = navigator(evidence = null)
        walk(nav)
        assertEndNotice(sample(nav, 190.0, 61_000L))
        assertTrue(nav.currentInstruction(fix(190.0, 61_000L)).orEmpty().contains("경로 종점"))
    }

    @Test fun automaticQuestionRetainsAccessUncertaintyWithoutChangingUserAck() {
        val nav = navigator()
        walk(nav)
        sample(nav, 190.0, 61_000L)
        listOf(62_000L, 63_000L, 64_000L).forEach { at ->
            assertFalse(sample(nav, 200.0, at).arrivalCandidate)
        }
        val question = sample(nav, 200.0, 65_000L)
        assertTrue(question.arrivalCandidate)
        assertFalse(question.arrived)
        assertEquals("arrival_confirmation_required", question.reason)
        assertEquals("도착했습니다. 안내를 종료합니다", question.instruction)
        val token = requireNotNull(nav.pendingDecisionToken())
        assertTrue(nav.confirmArrival(token).arrived)
        assertFalse(nav.hasRoute())
    }

    @Test fun explicitArrivalRequestAlsoExplainsTheUnverifiedAccess() {
        val nav = navigator()
        walk(nav)
        sample(nav, 190.0, 61_000L)
        val question = nav.requestArrivalConfirmation(61_000L)
        assertEquals("arrival_waiting_for_trusted_evidence", question.reason)
        assertFalse(question.arrivalCandidate)
        assertFalse(question.arrived)
        assertNull(nav.pendingDecisionToken())
        assertTrue(nav.hasRoute())
    }

    @Test fun unknownAccessNoticeHasNoRelativeMoveWithAValidHeading() {
        val nav = navigator()
        walk(nav)
        assertEndNotice(sample(nav, 190.0, 61_000L, heading = 90.0))
    }

    @Test fun providerTerminalMarkerStillNeedsTheRouteEndAccessExplanation() {
        val nav = navigator()
        val terminal = route().copy(guidePoints = route().guidePoints +
            WalkingRouteGuidePoint(1, point(195.0), null, 195, 5, turnType = 201, pointType = "EP"))
        nav.setRoute(terminal, destination = point(200.0), origin = fix(0.0, 0L),
            destinationCoordinateEvidence = evidence(DestinationCoordinateKind.FRONT))
        walk(nav)
        val update = sample(nav, 190.0, 61_000L)
        assertEquals(1, update.guideIndex)
        assertEndNotice(update)
    }

    @Test fun aPendingProtectedTurnIsNotReplacedByAnEndAccessNotice() {
        val nav = navigator()
        val turning = route().copy(guidePoints = route().guidePoints +
            WalkingRouteGuidePoint(1, point(195.0), null, 195, 5, turnType = 13))
        nav.setRoute(turning, destination = point(200.0), origin = fix(0.0, 0L),
            destinationCoordinateEvidence = evidence(DestinationCoordinateKind.NOOR))
        walk(nav)
        val update = sample(nav, 190.0, 61_000L)
        assertEquals(1, update.guideIndex)
        assertFalse(requireNotNull(update.instruction).contains("경로 종점"))
        assertFalse(update.arrived)
        assertFalse(update.arrivalCandidate)
    }

    @Test fun earlierOnRouteGuidanceStillDescribesTheRemainingRoute() {
        val nav = navigator()
        sample(nav, 0.0, 1_000L, heading = 90.0)
        val update = sample(nav, 30.0, 11_000L, heading = 90.0)
        assertFalse(update.arrivalCandidate)
        assertFalse(requireNotNull(update.instruction).contains("경로 종점"))
        assertFalse(update.arrived)
    }

    @Test fun thePreviouslyAcceptedFortyMetreGapRemainsInformationOnly() {
        val nav = navigator(destinationM = 240.0)
        walk(nav)
        val end = sample(nav, 200.0, 61_000L)
        val text = requireNotNull(end.instruction)
        assertTrue(text, text.contains("종점에서 직선으로 약 40m"))
        assertFalse(text.contains("이동하세요"))
        assertFalse(end.arrivalCandidate)
        assertEquals("arrival_request_not_near_destination", nav.requestArrivalConfirmation(61_000L).reason)
    }

    @Test fun rejectionKeepsTheRouteAndDoesNotImmediatelyReopenTheQuestion() {
        val nav = navigator()
        walk(nav)
        sample(nav, 190.0, 61_000L)
        for (at in 62_000L..65_000L step 1_000L) sample(nav, 200.0, at)
        val rejection = nav.rejectArrival(requireNotNull(nav.pendingDecisionToken()))
        assertEquals("arrival_rejected_route_retained", rejection.reason)
        assertTrue(nav.hasRoute())
        listOf(66_000L, 67_000L, 69_000L, 70_000L).forEach { at ->
            assertFalse(sample(nav, 200.0, at).arrivalCandidate)
        }
    }

    @Test fun replacingTheRouteRejectsTheOldUserConfirmationToken() {
        val nav = navigator()
        walk(nav)
        sample(nav, 190.0, 61_000L)
        for (at in 62_000L..65_000L step 1_000L) sample(nav, 200.0, at)
        val oldToken = requireNotNull(nav.pendingDecisionToken())
        nav.setRoute(route(), destination = point(200.0), origin = fix(0.0, 70_000L),
            destinationCoordinateEvidence = evidence(DestinationCoordinateKind.FRONT))
        assertEquals("arrival_confirmation_stale", nav.confirmArrival(oldToken).reason)
        assertTrue(nav.hasRoute())
    }

    @Test fun provenanceAndAlternativeCoordinatesAreRetainedWithoutBeingReclassified() {
        val source = evidence(DestinationCoordinateKind.NOOR)
        val nav = navigator(evidence = source)
        assertEquals(source, nav.destinationCoordinateEvidenceFor(point(200.0)))
        assertNull(nav.destinationCoordinateEvidenceFor(point(240.0)))
        walk(nav)
        sample(nav, 190.0, 61_000L)
        assertEquals(source, nav.destinationCoordinateEvidenceFor(point(200.0)))
    }

    private fun assertZeroGapNotice(kind: DestinationCoordinateKind) {
        val nav = navigator(evidence = evidence(kind))
        walk(nav)
        val update = sample(nav, 190.0, 61_000L)
        assertEndNotice(update)
        assertTrue(requireNotNull(update.instruction).contains("지도에서 받은 목적지 위치는"))
        assertAccessWarning(requireNotNull(nav.currentInstruction(fix(190.0, 61_000L))))
        val manual = requireNotNull(nav.currentGuidance(61_000L, facingObservation = null))
        assertEndNotice(manual)
        assertTrue(nav.reserveInstruction(manual))
        assertTrue(nav.isInstructionCurrent(manual, 61_000L, facingObservation = null))
        nav.releaseInstruction(manual, 61_000L)
        assertEndNotice(requireNotNull(nav.retryGuidance(63_000L, facingObservation = null)))
    }

    private fun assertEndNotice(update: RouteNavigatorUpdate) {
        assertEquals("route_guidance", update.reason)
        val text = requireNotNull(update.instruction)
        assertTrue(text, text.contains("경로 종점"))
        assertAccessWarning(text)
        assertFalse(text, text.contains("이동하세요") || text.contains("꺾으세요") || text.contains("시 방향"))
        assertFalse(update.arrived)
        assertFalse(update.offRoute)
        assertFalse(update.shouldReroute)
        assertFalse(update.directionOnly)
    }

    private fun assertAccessWarning(text: String) {
        assertTrue(text, text.contains("목적지 위치는"))
        assertTrue(text, text.contains("출입구"))
        assertTrue(text, text.contains("출입구까지의 통로는 확인되지 않았습니다"))
        assertFalse(text, text.contains("이동하세요") || text.contains("꺾으세요") || text.contains("시 방향"))
    }

    private fun navigator(destinationM: Double = 200.0,
        evidence: DestinationCoordinateEvidence? = evidence(DestinationCoordinateKind.NOOR)) =
        RouteNavigator(RouteNavigatorConfig(maximumProgressAdvanceM = 40.0, guidanceIntervalMs = 0L)).apply {
            setRoute(route(), destination = point(destinationM), origin = fix(0.0, 0L),
                destinationCoordinateEvidence = evidence)
        }

    private fun route() = WalkingRoute("RECOMMEND", WalkingRouteSummary(200, 200),
        listOf(point(0.0), point(200.0)),
        listOf(WalkingRouteGuidePoint(0, point(20.0), null, 20, 180, turnType = 13)))

    private fun evidence(kind: DestinationCoordinateKind) = DestinationCoordinateEvidence(kind,
        listOf(DestinationCoordinateCandidate(kind, point(200.0)),
            DestinationCoordinateCandidate(if (kind == DestinationCoordinateKind.NOOR)
                DestinationCoordinateKind.FRONT else DestinationCoordinateKind.NOOR, point(240.0))))

    private fun walk(nav: RouteNavigator) = listOf(0.0, 30.0, 70.0, 110.0, 150.0, 180.0)
        .forEachIndexed { index, east -> sample(nav, east, 1_000L + index * 10_000L) }

    private fun sample(nav: RouteNavigator, east: Double, at: Long, heading: Double? = null): RouteNavigatorUpdate {
        val fix = fix(east, at)
        return nav.update(fix, at, false,
            filteredPosition = FilteredRoutePosition(point(east), 3.0, at,
                uncertaintyKind = HorizontalUncertaintyKind.FILTER_MAX_AXIS_SIGMA),
            facingObservation = heading?.let { RouteFacingObservation(it, 5.0, at) })
    }

    private fun fix(east: Double, at: Long) = point(east).let {
        TrustedLocation(it.latitude, it.longitude, 3f, at, HorizontalUncertaintyKind.ANDROID_RADIUS_68)
    }
    private fun point(east: Double) = RoutePoint(0.0, east / 111_194.92664455874)
}

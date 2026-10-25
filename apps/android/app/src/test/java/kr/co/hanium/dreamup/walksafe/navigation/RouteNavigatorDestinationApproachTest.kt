package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchQuality
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S07-37, 07.txt:339-350: possible proximity is information, never arrival or a movement command. */
class RouteNavigatorDestinationApproachTest {
    @Test
    fun possibleProximityExplainsTheEntranceCheckWithoutOpeningArrivalOrGivingMovementCommands() {
        for (heading in listOf(90.0, 270.0, null)) {
            val navigator = navigator()
            walk(navigator)
            val update = sample(navigator, 190.0, 71_000L, heading = heading)
            assertAcceptedNonterminal(navigator, update)
            val guidance = requireNotNull(navigator.currentGuidance(71_000L, facing(heading, 71_000L)))
            assertPossibleApproach(requireNotNull(guidance.instruction))
            assertPossibleApproach(requireNotNull(navigator.currentInstruction(fix(190.0, 71_000L))))
        }
    }

    @Test
    fun anOverlappingAccuracyCircleIsDescribedAsPossibleProximityRatherThanAnExactPosition() {
        val navigator = navigator()
        walk(navigator)
        // The point is 20 m away, outside the manual request's 17 m area, but its 10 m
        // accuracy circle overlaps that area. This does not establish a precise location.
        val update = sample(navigator, 180.0, 71_000L)
        assertAcceptedNonterminal(navigator, update)
        val guidance = requireNotNull(navigator.currentGuidance(71_000L))
        assertPossibleApproach(requireNotNull(guidance.instruction))
    }

    @Test
    fun bothRawAndFilteredPositionsMustSupportTheExistingManualNearArea() {
        val navigator = navigator()
        walk(navigator)
        val raw = fix(170.0, 71_000L)
        val update = navigator.update(raw, 71_000L, false, filteredPosition = filtered(190.0, 71_000L))
        assertNoApproach(update)
        assertEquals("arrival_request_not_near_destination", navigator.requestArrivalConfirmation(71_000L).reason)
        assertNull(navigator.pendingDecisionToken())
    }

    @Test
    fun farFromTheDestinationKeepsOrdinaryGuidanceWithoutAnArrivalQuestion() {
        val navigator = navigator()
        walk(navigator, lastEastM = 150.0)
        val update = sample(navigator, 150.0, 71_000L)
        assertNoApproach(update)
        assertFalse(update.arrivalCandidate)
        assertNull(navigator.pendingDecisionToken())
        assertEquals("arrival_request_not_near_destination", navigator.requestArrivalConfirmation(71_000L).reason)
    }

    @Test
    fun stalePairedEvidenceAndLaterGuidanceRetriesCannotOfferPossibleProximity() {
        val navigator = navigator()
        walk(navigator)
        val stale = navigator.update(fix(190.0, 71_000L), 71_000L, false,
            filteredPosition = filtered(190.0, 50_000L))
        assertEquals("location_sample_invalid_or_stale", stale.reason)
        assertNoApproach(stale)
        assertNull(navigator.currentGuidance(71_000L))

        sample(navigator, 190.0, 72_000L)
        assertNull(navigator.currentGuidance(72_000L + LocationTrustConfig().maxAgeMs + 1))
        assertEquals("arrival_request_location_unavailable",
            navigator.requestArrivalConfirmation(72_000L + LocationTrustConfig().maxAgeMs + 1).reason)
    }

    @Test
    fun ambiguousRouteVisitsAtTheDestinationDoNotOfferPossibleProximity() {
        val navigator = RouteNavigator().apply {
            setRoute(WalkingRoute("TEST", WalkingRouteSummary(400, 400),
                listOf(point(-80.0), point(0.0), point(0.0, 80.0), point(0.0), point(80.0), point(0.0)),
                emptyList()))
        }
        val update = sample(navigator, 0.0, 1_000L)
        assertEquals(RouteMatchQuality.LOW, navigator.currentRouteMatch()?.quality)
        assertEquals(RouteMatchReason.AMBIGUOUS_CANDIDATES, navigator.currentRouteMatch()?.reason)
        assertNoApproach(update)
        assertFalse(update.arrivalCandidate)
        assertNull(navigator.currentAcceptedRouteMatchFor(1_000L))
    }

    @Test
    fun diagnosticModeDoesNotOfferTheProductArrivalApproachPhase() {
        val navigator = navigator(degraded = true)
        walk(navigator)
        val update = sample(navigator, 190.0, 71_000L)
        assertNoApproach(update)
        assertFalse(update.arrivalCandidate)
        assertNull(navigator.pendingDecisionToken())
        assertEquals("arrival_request_unavailable", navigator.requestArrivalConfirmation(71_000L).reason)
    }

    @Test
    fun aRouteEndSeparateFromTheRequestedDestinationKeepsTheGapNotice() {
        val navigator = navigator(destinationEastM = 220.0)
        walk(navigator)
        val update = sample(navigator, 200.0, 71_000L)
        assertNoApproach(update)
        val instruction = requireNotNull(navigator.currentInstruction(fix(200.0, 71_000L)))
        assertTrue(instruction, instruction.contains("요청한 목적지는 종점에서 직선으로 약 20m 떨어져 있습니다."))
        assertTrue(instruction, instruction.contains("요청한 목적지의 최종 접근을 확인하세요"))
        assertFalse(update.arrivalCandidate)
        assertNull(navigator.pendingDecisionToken())
    }

    @Test
    fun explicitRequestCannotOpenAQuestionOrReplaceAutomaticEvidence() {
        val navigator = navigator()
        walk(navigator)
        sample(navigator, 190.0, 71_000L)
        assertFalse(navigator.confirmArrival().arrived)
        val request = navigator.requestArrivalConfirmation(71_000L)
        assertFalse(request.arrived)
        assertNull(request.pendingUserDecision)
        assertNull(navigator.pendingDecisionToken())
        assertTrue(navigator.hasRoute())
        assertAcceptedNonterminal(navigator, sample(navigator, 190.0, 72_000L), at = 72_000L)
        assertNull(navigator.requestArrivalConfirmation(72_000L).pendingUserDecision)
    }

    @Test
    fun strictAccuracyAndThreeSecondDwellStillControlTheAutomaticQuestion() {
        val navigator = navigator()
        walk(navigator)
        for (at in 71_000L..73_000L step 1_000L) {
            assertFalse(sample(navigator, 200.0, at).arrivalCandidate)
            assertNull(navigator.pendingDecisionToken())
        }
        val question = sample(navigator, 200.0, 74_000L)
        assertTrue(question.arrivalCandidate)
        assertFalse(question.arrived)
        assertEquals(RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION, question.pendingUserDecision)

        val inaccurate = navigator()
        walk(inaccurate)
        for (at in 71_000L..75_000L step 1_000L) {
            assertFalse(sample(inaccurate, 200.0, at, accuracyM = 11f).arrivalCandidate)
            assertNull(inaccurate.pendingDecisionToken())
        }
    }

    @Test
    fun theInformationCueCanStartWithoutFacingButStillExpiresWithItsLocationEvidence() {
        val navigator = navigator()
        walk(navigator)
        val update = sample(navigator, 190.0, 71_000L, heading = 270.0)
        assertPossibleApproach(requireNotNull(update.instruction))
        assertTrue(navigator.reserveInstruction(update))
        assertTrue(navigator.isInstructionCurrent(update, 71_001L, null))
        assertTrue(navigator.isInstructionCurrent(update, 71_002L, facing(90.0, 71_002L)))
        assertFalse(navigator.isInstructionCurrent(update, 71_000L + LocationTrustConfig().maxAgeMs + 1, null))
    }

    @Test
    fun anOpenDecisionInterruptedEvidenceOrReinstalledRouteInvalidatesTheInformationCue() {
        for (invalidate in listOf<(RouteNavigator) -> Unit>(
            { it.onUntrustedLocation() },
            { it.onPositioningEvidenceInterrupted() },
            { it.setRoute(WalkingRoute("TEST", WalkingRouteSummary(200, 200),
                listOf(point(0.0), point(200.0)), emptyList()), destination = point(200.0)) },
        )) {
            val navigator = navigator()
            walk(navigator)
            val update = sample(navigator, 190.0, 71_000L)
            assertPossibleApproach(requireNotNull(update.instruction))
            assertTrue(navigator.reserveInstruction(update))
            invalidate(navigator)
            assertFalse(navigator.isInstructionCurrent(update, 71_001L, null))
        }
    }

    @Test
    fun completedOrdinaryGuidanceDoesNotHideApproachAndCompassChangesDoNotRepeatTheInformation() {
        val navigator = navigator()
        walk(navigator, lastEastM = 150.0)
        val ordinary = sample(navigator, 170.0, 61_000L, accuracyM = 3f)
        assertNoApproach(ordinary)
        assertTrue(navigator.reserveInstruction(ordinary))
        navigator.acknowledgeInstruction(ordinary, 61_100L)

        // Both fixes are in the existing 13..30 m speech band. The new phase is new information.
        val approach = sample(navigator, 180.0, 71_000L)
        assertPossibleApproach(requireNotNull(approach.instruction))
        assertTrue(navigator.reserveInstruction(approach))
        navigator.acknowledgeInstruction(approach, 71_100L)
        val afterRotation = sample(navigator, 181.0, 72_000L, heading = 270.0)
        assertNull(afterRotation.instruction)
        assertEquals("guidance_already_completed", afterRotation.reason)
    }

    @Test
    fun enteringApproachRevokesAnInFlightOrdinaryCueBeforeOfferingTheInformation() {
        val navigator = navigator()
        walk(navigator, lastEastM = 150.0)
        val ordinary = sample(navigator, 170.0, 61_000L, accuracyM = 3f, heading = 270.0)
        assertTrue(navigator.reserveInstruction(ordinary))
        val approach = sample(navigator, 180.0, 71_000L, heading = 270.0)
        assertPossibleApproach(requireNotNull(approach.instruction))
        assertTrue(approach.cancelStaleNavigationSpeech)
        assertFalse(navigator.isInstructionCurrent(ordinary, 71_000L, facing(270.0, 71_000L)))
        assertTrue(navigator.reserveInstruction(approach))
        assertTrue(navigator.isInstructionCurrent(approach, 71_001L, null))
    }

    @Test
    fun anUnpassedCrosswalkNearTheDestinationKeepsItsReferenceNotice() {
        for (heading in listOf(90.0, null)) {
            val navigator = navigator(guide = WalkingRouteGuidePoint(0, point(195.0), "횡단보도", 195, 5, turnType = 211))
            walk(navigator)
            sample(navigator, 190.0, 71_000L, heading = heading)
            val current = requireNotNull(navigator.currentInstruction(fix(190.0, 71_000L)))
            assertTrue(current, current.contains("5m 앞, 횡단보도 안내 지점입니다."))
            assertTrue(current, current.contains(CROSSWALK_REFERENCE_NOTICE_KO))
            val spoken = requireNotNull(navigator.currentGuidance(71_000L, facing(heading, 71_000L))?.instruction)
            assertTrue(spoken, spoken.contains(CROSSWALK_REFERENCE_NOTICE_KO))
            assertFalse(spoken, spoken.contains("목적지 근처일 수 있습니다"))
            assertNull(navigator.pendingDecisionToken())
        }
    }

    @Test
    fun anUnpassedTurnNearTheDestinationKeepsItsMappedAction() {
        val navigator = navigator(guide = WalkingRouteGuidePoint(0, point(195.0), null, 195, 5, turnType = 13))
        walk(navigator)
        sample(navigator, 190.0, 71_000L, heading = 90.0)
        val current = requireNotNull(navigator.currentInstruction(fix(190.0, 71_000L)))
        assertTrue(current, current.contains("5m 앞, 3시 방향으로 꺾으세요."))
        val spoken = requireNotNull(navigator.currentGuidance(71_000L, facing(90.0, 71_000L))?.instruction)
        assertTrue(spoken, spoken.contains("3시 방향으로 꺾으세요"))
        assertFalse(spoken, spoken.contains("목적지 근처일 수 있습니다"))
        assertNull(navigator.pendingDecisionToken())
    }

    @Test
    fun aHeldTurnAtTheLastCornerKeepsTheNearTurnInstruction() {
        val navigator = RouteNavigator(RouteNavigatorConfig(maximumProgressAdvanceM = 40.0, guidanceIntervalMs = 0L)).apply {
            setRoute(WalkingRoute("TEST", WalkingRouteSummary(200, 200),
                listOf(point(0.0), point(190.0), point(190.0, -10.0)),
                listOf(WalkingRouteGuidePoint(0, point(190.0), null, 190, 10, turnType = 13))))
        }
        walk(navigator)
        val vertex = sample(navigator, 190.0, 71_000L, heading = 180.0)
        assertEquals(RouteAlignmentReason.TURN_PASSAGE_PENDING, vertex.routeAlignmentDiagnostic?.reason)
        val instruction = requireNotNull(vertex.instruction)
        assertTrue(instruction, instruction.contains("안내 지점 부근입니다. 경로 기준으로 3시 방향으로 꺾으세요."))
        assertFalse(instruction, instruction.contains("목적지 근처일 수 있습니다"))
        assertNull(navigator.pendingDecisionToken())
    }

    @Test
    fun anUnpassedUnmappedStairGuideNearTheDestinationKeepsTheExistingGuideAction() {
        // Provider text is not promoted to a new movement instruction. Preserve the existing
        // generic guide action for this unpassed guide, rather than hiding the guide altogether.
        val navigator = navigator(guide = WalkingRouteGuidePoint(0, point(195.0), "계단", 195, 5, turnType = 241))
        walk(navigator)
        sample(navigator, 190.0, 71_000L, heading = 90.0)
        val current = requireNotNull(navigator.currentInstruction(fix(190.0, 71_000L)))
        assertTrue(current, current.contains("전방 5m 안내 지점까지 이동하세요."))
        val spoken = requireNotNull(navigator.currentGuidance(71_000L, facing(90.0, 71_000L))?.instruction)
        assertTrue(spoken, spoken.contains("전방 5m 안내 지점까지 이동하세요."))
        assertFalse(spoken, spoken.contains("목적지 근처일 수 있습니다"))
        assertNull(navigator.pendingDecisionToken())
    }

    @Test
    fun uncertainPassageOfANearDestinationProtectedGuideIsNotReplacedByApproach() {
        val navigator = navigator(guide = WalkingRouteGuidePoint(0, point(195.0), "횡단보도", 195, 5, turnType = 211))
        walk(navigator)
        sample(navigator, 190.0, 71_000L, heading = 90.0)
        val update = sample(navigator, 196.0, 72_000L, heading = 90.0)
        assertEquals(0, update.guideIndex)
        assertEquals("안내 지점 통과 여부를 확인 중입니다.", navigator.currentInstruction(fix(196.0, 72_000L)))
        assertTrue(update.instruction.orEmpty(), update.instruction.orEmpty().contains("안내 지점 통과 여부를 확인 중입니다."))
        assertFalse(update.arrivalCandidate)
        assertNull(navigator.pendingDecisionToken())
    }

    @Test
    fun anExplicitTerminalGuideStillAllowsPossibleProximityBeforeItIsPassed() {
        val navigator = navigator(guide = WalkingRouteGuidePoint(0, point(195.0), null, 195, 5, turnType = 201))
        walk(navigator)
        val update = sample(navigator, 190.0, 71_000L)
        assertAcceptedNonterminal(navigator, update)
        assertPossibleApproach(requireNotNull(update.instruction))
        assertPossibleApproach(requireNotNull(navigator.currentGuidance(71_000L)?.instruction))
        assertPossibleApproach(requireNotNull(navigator.currentInstruction(fix(190.0, 71_000L))))
        assertTrue(navigator.reserveInstruction(update))
        assertTrue(navigator.isInstructionCurrent(update, 71_001L, null))
    }

    @Test
    fun aTerminalCodeWithACrosswalkMarkerStillKeepsTheCrosswalkReferenceNotice() {
        val navigator = navigator(guide = WalkingRouteGuidePoint(0, point(195.0), "횡단보도", 195, 5, turnType = 201))
        walk(navigator)
        sample(navigator, 190.0, 71_000L, heading = 90.0)
        val current = requireNotNull(navigator.currentInstruction(fix(190.0, 71_000L)))
        assertTrue(current, current.contains(CROSSWALK_REFERENCE_NOTICE_KO))
        val spoken = requireNotNull(navigator.currentGuidance(71_000L, facing(90.0, 71_000L))?.instruction)
        assertTrue(spoken, spoken.contains(CROSSWALK_REFERENCE_NOTICE_KO))
        assertFalse(spoken, spoken.contains("목적지 근처일 수 있습니다"))
    }

    private fun assertAcceptedNonterminal(navigator: RouteNavigator, update: RouteNavigatorUpdate, at: Long = 71_000L) {
        assertNotNull(navigator.currentAcceptedRouteMatchFor(at))
        assertTrue(navigator.currentRouteMatch()?.quality in setOf(RouteMatchQuality.HIGH, RouteMatchQuality.MEDIUM))
        assertFalse(update.arrived)
        assertFalse(update.arrivalCandidate)
        assertNull(update.pendingUserDecision)
        assertNull(navigator.pendingDecisionToken())
        assertTrue(navigator.hasRoute())
    }

    private fun assertPossibleApproach(instruction: String) {
        assertTrue(instruction, instruction.contains("목적지 근처일 수 있습니다"))
        assertTrue(instruction, instruction.contains("출입구"))
        assertTrue(instruction, instruction.contains("실제로"))
        assertFalse(instruction, instruction.contains("도착 확인"))
        listOf("꺾으세요", "이동하세요", "직진하세요", "돌아가세요", "돌리세요", "맞추세요", "걸으세요").forEach {
            assertFalse("$it in $instruction", instruction.contains(it))
        }
    }

    private fun assertNoApproach(update: RouteNavigatorUpdate) {
        assertFalse(update.instruction.orEmpty(), update.instruction.orEmpty().contains("목적지 근처일 수 있습니다"))
        assertFalse(update.arrived)
    }

    private fun navigator(degraded: Boolean = false, destinationEastM: Double = 200.0,
        guide: WalkingRouteGuidePoint? = null) = RouteNavigator(
        RouteNavigatorConfig(maximumProgressAdvanceM = 40.0, guidanceIntervalMs = 0L),
        allowDegradedRouteGuidance = degraded,
    ).apply {
        setRoute(WalkingRoute("TEST", WalkingRouteSummary(200, 200),
            listOf(point(0.0), point(200.0)), listOfNotNull(guide)), destination = point(destinationEastM))
    }

    private fun walk(navigator: RouteNavigator, lastEastM: Double = 180.0) {
        listOf(0.0, 30.0, 60.0, 90.0, 120.0, 150.0, 180.0).filter { it <= lastEastM }.forEachIndexed { index, east ->
            sample(navigator, east, 1_000L + index * 10_000L, accuracyM = 3f)
        }
    }

    private fun sample(navigator: RouteNavigator, east: Double, at: Long, accuracyM: Float = 10f,
        heading: Double? = null): RouteNavigatorUpdate = navigator.update(fix(east, at, accuracyM), at, false,
        facingObservation = facing(heading, at))

    private fun fix(east: Double, at: Long, accuracyM: Float = 10f): TrustedLocation =
        point(east).let { TrustedLocation(it.latitude, it.longitude, accuracyM, at) }

    private fun filtered(east: Double, at: Long) = FilteredRoutePosition(point(east), 10.0, at)
    private fun point(east: Double, north: Double = 0.0) =
        RoutePoint(north / 111_194.92664455874, east / 111_194.92664455874)
    private fun facing(heading: Double?, at: Long) = heading?.let { RouteFacingObservation(it, 5.0, at) }
}

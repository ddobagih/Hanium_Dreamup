package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteHeadingEstimate
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Installed-route journeys through the production navigator. Positions and speech terminals are
 * deterministic inputs, not HOME UI, provider/storage ACK, Android TTS, or outdoor evidence.
 * Production dwell, accuracy, progress, joining, and retry thresholds remain unchanged.
 */
class RouteNavigatorJourneyScenarioTest {
    @Test fun straightJourneyJoinsSpeaksAndOnlyConfirmationEndsTheRoute() {
        val journey = Journey(listOf(point(0.0), point(100.0)), turnType = 11)
        assertTrue(journey.navigator.isWaitingForInitialRouteJoin())
        val first = journey.at(0.0)
        assertFalse(journey.navigator.isWaitingForInitialRouteJoin())
        assertEquals("route_guidance", first.reason)
        assertTrue(first.instruction!!.contains("직진하세요"))
        journey.complete(first)
        journey.walkToEnd()
        journey.confirmAndAssertEnded()
    }

    @Test fun leftTurnJourneyKeepsTheRouteRelativeTurnAndReachesTheDestination() {
        turnJourney(12, "9시 방향으로 꺾으세요", 100.0)
    }

    @Test fun rightTurnJourneyKeepsTheRouteRelativeTurnAndReachesTheDestination() {
        turnJourney(13, "3시 방향으로 꺾으세요", -100.0)
    }

    @Test fun uTurnJourneyUsesTheProviderManeuverAndDoesNotArriveOnTheOutgoingLeg() {
        val journey = Journey(listOf(point(0.0), point(100.0), point(100.0, 25.0), point(0.0, 25.0)), 14)
        val first = journey.at(0.0)
        assertTrue(first.instruction!!.contains("6시 방향으로 돌아가세요"))
        assertFalse(first.instruction!!.contains("9시 방향으로 꺾으세요"))
        journey.complete(first)
        journey.walkToEnd()
        journey.confirmAndAssertEnded()
    }

    @Test fun aShortRouteStillRequiresDwellAndAnExplicitArrivalDecision() {
        val journey = Journey(listOf(point(0.0), point(8.0)), 11)
        val first = journey.at(0.0)
        assertFalse(first.arrivalCandidate)
        assertFalse(first.arrived)
        assertTrue(journey.navigator.hasRoute())
        assertFalse(journey.at(8.0, afterMs = 1_000L).arrivalCandidate)
        assertFalse(journey.at(8.0, afterMs = 1_000L).arrivalCandidate)
        assertTrue(journey.at(8.0, afterMs = 1_000L).arrivalCandidate)
        journey.confirmAndAssertEnded()
    }

    @Test fun theAccuracyDiskMustFitInsideTheArrivalRadiusForTheWholeDwell() {
        for ((distanceFromEnd, expected) in listOf(9.01 to false, 8.99 to true)) {
            val journey = Journey(listOf(point(0.0), point(100.0)), 11)
            repeat(4) { index ->
                val update = journey.at(100.0 - distanceFromEnd, afterMs = 1_000L, accuracyM = 3f)
                assertEquals("distance=$distanceFromEnd sample=$index", expected && index == 3, update.arrivalCandidate)
                assertFalse(update.arrived)
                assertTrue(journey.navigator.hasRoute())
            }
            if (expected) journey.confirmAndAssertEnded()
            else assertNull(journey.navigator.pendingDecisionToken())
        }
    }

    @Test fun repeatedRawFixesCannotAccumulateDwellAndARealRetreatResetsIt() {
        val journey = Journey(listOf(point(0.0), point(100.0)), 11)
        journey.at(100.0)
        val raw = journey.fix(100.0)
        val filtered = FilteredRoutePosition(RoutePoint(raw.latitude, raw.longitude), 3.0,
            raw.elapsedRealtimeMs, heading = RouteHeadingEstimate(90.0, 5.0))
        repeat(5) {
            journey.now += 500L
            val duplicate = journey.navigator.update(raw, journey.now, false, filtered)
            assertFalse(duplicate.arrivalCandidate)
            assertFalse(duplicate.arrived)
        }
        assertNull(journey.navigator.pendingDecisionToken())
        // Walk ten metres back and return at 2 m/s; do not inject an impossible position jump.
        assertFalse(journey.at(90.0, afterMs = 5_000L).arrivalCandidate)
        assertNotNull(journey.navigator.currentAcceptedRouteMatchFor(journey.now))
        assertFalse(journey.at(100.0, afterMs = 5_000L).arrivalCandidate)
        repeat(2) { assertFalse(journey.at(100.0, afterMs = 1_000L).arrivalCandidate) }
        assertTrue(journey.at(100.0, afterMs = 1_000L).arrivalCandidate)
        journey.confirmAndAssertEnded()
    }

    @Test fun aReturningRouteNearItsStartCannotSkipTheJourneyAndAskForArrival() {
        val journey = Journey(listOf(point(0.0), point(200.0), point(0.0, 4.0)), 14)
        repeat(5) {
            val update = journey.at(0.0, afterMs = 1_000L)
            assertFalse(update.arrivalCandidate)
            assertFalse(update.arrived)
            assertFalse(update.instruction?.contains("도착 후보") == true)
        }
        assertTrue(journey.navigator.hasRoute())
        assertNull(journey.navigator.pendingDecisionToken())
        assertFalse(journey.navigator.confirmArrival().arrived)
    }

    @Test fun arrivalRevokesPendingSpeechAndRejectionRequiresANewQuestion() {
        val journey = Journey(listOf(point(0.0), point(100.0)), 11)
        val speech = journey.at(85.0)
        assertTrue(journey.navigator.reserveInstruction(speech))
        val question = journey.dwellAtEnd()
        assertTrue(question.cancelStaleNavigationSpeech)
        assertFalse(journey.navigator.reserveInstruction(speech))
        val oldQuestion = requireNotNull(journey.navigator.pendingDecisionToken())
        assertEquals("arrival_rejected_route_retained", journey.navigator.rejectArrival(oldQuestion).reason)
        journey.navigator.acknowledgeInstruction(speech, journey.now)
        repeat(4) { assertFalse(journey.at(100.0, afterMs = 1_000L).arrivalCandidate) }
        assertFalse(journey.navigator.confirmArrival(oldQuestion).arrived)
        assertTrue(journey.navigator.hasRoute())

        val requested = journey.navigator.requestArrivalConfirmation(journey.now)
        assertEquals("arrival_waiting_for_trusted_evidence", requested.reason)
        assertNull(journey.navigator.pendingDecisionToken())
        journey.at(60.0, afterMs = 1_000L)
        journey.at(50.0, afterMs = 1_000L)
        journey.at(100.0, afterMs = 1_000L)
        journey.dwellAtEnd()
        val newQuestion = requireNotNull(journey.navigator.pendingDecisionToken())
        assertNotEquals(oldQuestion, newQuestion)
        journey.confirmAndAssertEnded()
        journey.navigator.acknowledgeInstruction(speech, journey.now + 1L)
        assertFalse(journey.navigator.reserveInstruction(speech))
        assertFalse(journey.navigator.hasRoute())
    }

    @Test fun replacingTheDestinationRetiresBothOldSpeechAndOldArrivalAnswers() {
        val journey = Journey(listOf(point(0.0), point(100.0)), 11)
        val oldSpeech = journey.at(85.0)
        assertTrue(journey.navigator.reserveInstruction(oldSpeech))
        journey.dwellAtEnd()
        val oldArrival = requireNotNull(journey.navigator.pendingDecisionToken())
        journey.install(destination = point(100.0, 8.0).copy(name = "새 목적지"))
        assertTrue(journey.navigator.isWaitingForInitialRouteJoin())
        assertFalse(journey.navigator.confirmArrival(oldArrival).arrived)
        assertEquals("arrival_confirmation_stale", journey.navigator.rejectArrival(oldArrival).reason)
        journey.navigator.acknowledgeInstruction(oldSpeech, journey.now)
        assertFalse(journey.navigator.reserveInstruction(oldSpeech))
        val newSpeech = journey.at(0.0)
        assertNotEquals(oldSpeech.speechCueToken, newSpeech.speechCueToken)
        assertTrue(journey.navigator.reserveInstruction(newSpeech))
        journey.navigator.acknowledgeInstruction(oldSpeech, journey.now + 1L)
        assertEquals("guidance_in_flight", journey.at(0.0, afterMs = 100L).reason)
        journey.navigator.acknowledgeInstruction(newSpeech, journey.now)
        journey.walkToEnd()
        assertFalse(journey.navigator.confirmArrival(oldArrival).arrived)
        journey.confirmAndAssertEnded()
    }

    @Test fun cancellationRetiresPendingSpeechAndArrivalTokensBeforeAnotherJourney() {
        val journey = Journey(listOf(point(0.0), point(100.0)), 11)
        val speech = journey.at(85.0)
        assertTrue(journey.navigator.reserveInstruction(speech))
        journey.dwellAtEnd()
        val arrival = requireNotNull(journey.navigator.pendingDecisionToken())
        journey.navigator.clear()
        assertFalse(journey.navigator.hasRoute())
        assertFalse(journey.navigator.confirmArrival(arrival).arrived)
        journey.navigator.acknowledgeInstruction(speech, journey.now)
        assertFalse(journey.navigator.reserveInstruction(speech))
        assertNull(journey.navigator.retryGuidance(journey.now))
        journey.install()
        journey.walkToEnd()
        assertFalse(journey.navigator.confirmArrival(arrival).arrived)
        journey.confirmAndAssertEnded()
    }

    @Test fun arrivalCancelsAnInFlightSummaryAndLateInformationCompletionCannotReopenIt() {
        val journey = Journey(listOf(point(0.0), point(100.0)), 11)
        val cue = journey.at(85.0)
        val information = requireNotNull(cue.infoUtterance)
        assertFalse(journey.navigator.reserveInformation(information, journey.now))
        assertTrue(journey.navigator.reserveInstruction(cue))
        journey.navigator.acknowledgeInstruction(cue, journey.now)
        assertTrue(journey.navigator.reserveInformation(information, journey.now))
        assertTrue(journey.navigator.isInformationCurrent(information, journey.now))
        val question = journey.dwellAtEnd()
        assertTrue(question.cancelStaleNavigationSpeech)
        assertFalse(journey.navigator.isInformationCurrent(information, journey.now))
        journey.navigator.finishInformation(information)
        assertFalse(journey.navigator.reserveInformation(information, journey.now))
        journey.confirmAndAssertEnded()
        assertFalse(journey.navigator.isInformationCurrent(information, journey.now))
    }

    @Test fun reachingTheProviderEndpointCannotClaimArrivalAtADifferentRequestedDestination() {
        val journey = Journey(listOf(point(0.0), point(100.0)), 11)
        journey.install(destination = point(100.0, 100.0).copy(name = "종점 너머 목적지"))
        for (progress in 0..100 step 5) {
            val update = journey.at(progress.toDouble())
            assertFalse(update.arrivalCandidate)
            journey.complete(update)
        }
        repeat(4) { assertFalse(journey.at(100.0, afterMs = 1_000L).arrivalCandidate) }
        assertEquals("arrival_request_not_near_destination", journey.navigator.requestArrivalConfirmation(journey.now).reason)
        assertFalse(journey.navigator.confirmArrival().arrived)
        assertTrue(journey.navigator.hasRoute())
    }

    @Test fun deviationNeedsApprovalAndAReplacementJourneyRejectsOldCallbacks() {
        val journey = Journey(listOf(point(0.0), point(100.0)), 11)
        val oldSpeech = journey.at(0.0)
        assertTrue(journey.navigator.reserveInstruction(oldSpeech))
        val first = journey.outside()
        assertFalse(first.offRoute)
        assertFalse(first.shouldReroute)
        val confirmed = journey.outside()
        assertTrue(confirmed.offRoute)
        assertFalse(confirmed.shouldReroute)
        val decision = requireNotNull(journey.navigator.pendingDecisionToken())
        assertEquals(RouteNavigatorUserDecision.REROUTE, decision.decision)
        val approved = journey.navigator.selectDeviationChoice(RouteDeviationChoice.NEW_ROUTE, decision)
        assertTrue(approved.shouldReroute)
        assertEquals("off_route_reroute_approved_in_flight", journey.outside(requestInFlight = true).reason)
        assertFalse(journey.navigator.reserveInstruction(oldSpeech))
        journey.install()
        journey.navigator.acknowledgeInstruction(oldSpeech, journey.now)
        assertEquals("route_decision_stale", journey.navigator.selectDeviationChoice(RouteDeviationChoice.NEW_ROUTE, decision).reason)
        assertNull(journey.navigator.pendingDecisionToken())
        journey.walkToEnd()
        journey.confirmAndAssertEnded()
    }

    private fun turnJourney(turnType: Int, turnText: String, north: Double) {
        val journey = Journey(listOf(point(0.0), point(100.0), point(100.0, north)), turnType)
        val first = journey.at(0.0)
        assertEquals(1, first.guideIndex)
        assertTrue(first.instruction!!.contains("다음 안내 지점에서 경로 기준으로 $turnText"))
        assertFalse(first.instruction!!.contains("전체 경로"))
        assertTrue(first.infoUtterance!!.message.contains("전체 경로는 약 200m"))
        journey.complete(first)
        journey.walkToEnd()
        journey.confirmAndAssertEnded()
    }

    private class Journey(val points: List<RoutePoint>, turnType: Int) {
        var now = 0L
        private val lengths = points.zipWithNext { a, b -> hypot(east(b) - east(a), north(b) - north(a)) }
        val length = lengths.sum()
        val navigator = RouteNavigator(nowElapsedRealtimeMs = { now })
        private var lastFacing: RouteFacingObservation? = null
        private val route = WalkingRoute("TEST", WalkingRouteSummary(length.toInt(), length.toInt()), points,
            listOf(
                WalkingRouteGuidePoint(0, points.first(), null, 0, length.toInt(), turnType = 200),
                WalkingRouteGuidePoint(1, points[1], null, lengths.first().toInt(),
                    (length - lengths.first()).toInt(), turnType = turnType),
            ), providerRouteId = "journey-provider-id")

        init { install() }

        fun install(destination: RoutePoint = points.last()) {
            navigator.setRoute(route, destination, waitForInitialRouteJoin = true)
        }

        fun fix(progress: Double, accuracyM: Float = 3f): TrustedLocation {
            val (point, _) = position(progress)
            return TrustedLocation(point.latitude, point.longitude, accuracyM, now)
        }

        fun at(progress: Double, afterMs: Long = 5_000L, accuracyM: Float = 3f): RouteNavigatorUpdate {
            now += afterMs
            val (point, heading) = position(progress)
            val raw = fix(progress, accuracyM)
            lastFacing = RouteFacingObservation(heading, 5.0, now)
            val update = navigator.update(raw, now, false,
                FilteredRoutePosition(point, accuracyM.toDouble(), now, heading = RouteHeadingEstimate(heading, 5.0)),
                facingObservation = lastFacing)
            println("JOURNEY progress=$progress at=$now reason=${update.reason} guide=${update.guideIndex} arrived=${update.arrived}")
            assertFalse("No location may finish a journey", update.arrived)
            assertFalse(update.shouldReroute)
            return update
        }

        fun outside(requestInFlight: Boolean = false): RouteNavigatorUpdate {
            now += 1_000L
            val p = point(0.0, 80.0)
            return navigator.update(TrustedLocation(p.latitude, p.longitude, 3f, now), now, requestInFlight)
        }

        fun complete(cue: RouteNavigatorUpdate) {
            if (cue.speechCueToken == null) return
            assertNotNull(cue.instruction)
            assertTrue(navigator.reserveInstruction(cue))
            assertFalse(navigator.reserveInstruction(cue))
            assertTrue("Offered cue must pass its production START guard", navigator.isInstructionCurrent(cue, now, lastFacing))
            assertTrue(navigator.isInstructionCompletionCurrent(cue, now))
            // Synthetic terminal input; actual TTS delivery is tested by the speech owner.
            navigator.acknowledgeInstruction(cue, now)
            assertFalse(navigator.isInstructionCompletionCurrent(cue, now))
        }

        fun walkToEnd() {
            var progress = 0.0
            while (progress < length) {
                val update = at(progress)
                if (length - progress > 12.0) assertFalse("Early arrival at $progress/$length", update.arrivalCandidate)
                complete(update)
                progress += 5.0
            }
            dwellAtEnd()
        }

        fun dwellAtEnd(): RouteNavigatorUpdate {
            var last: RouteNavigatorUpdate? = null
            repeat(4) { last = at(length, afterMs = 1_000L) }
            assertTrue("No arrival question: $last", last!!.arrivalCandidate)
            assertTrue(navigator.hasRoute())
            return last!!
        }

        fun confirmAndAssertEnded() {
            val token = requireNotNull(navigator.pendingDecisionToken())
            assertEquals(RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION, token.decision)
            assertTrue(navigator.hasRoute())
            val ended = navigator.completeDetectedArrival(token)
            assertTrue(ended.arrived)
            assertEquals("arrival_confirmed", ended.reason)
            assertEquals("도착했습니다. 안내를 종료합니다", ended.instruction)
            assertFalse(navigator.hasRoute())
            assertNull(navigator.pendingDecisionToken())
            assertNull(navigator.retryGuidance(now))
            assertFalse(navigator.confirmArrival(token).arrived)
        }

        private fun position(progress: Double): Pair<RoutePoint, Double> {
            var remaining = progress.coerceIn(0.0, length)
            for ((index, segmentLength) in lengths.withIndex()) {
                if (remaining <= segmentLength || index == lengths.lastIndex) {
                    val a = points[index]
                    val b = points[index + 1]
                    val fraction = remaining / segmentLength
                    return RoutePoint(a.latitude + (b.latitude - a.latitude) * fraction,
                        a.longitude + (b.longitude - a.longitude) * fraction) to
                        ((Math.toDegrees(atan2(east(b) - east(a), north(b) - north(a))) + 360.0) % 360.0)
                }
                remaining -= segmentLength
            }
            error("No segment")
        }
    }

    companion object {
        private const val METRES_PER_DEGREE = 111194.92664455874
        private fun point(east: Double, north: Double = 0.0) = RoutePoint(north / METRES_PER_DEGREE, east / METRES_PER_DEGREE)
        private fun east(point: RoutePoint) = point.longitude * METRES_PER_DEGREE
        private fun north(point: RoutePoint) = point.latitude * METRES_PER_DEGREE
    }
}

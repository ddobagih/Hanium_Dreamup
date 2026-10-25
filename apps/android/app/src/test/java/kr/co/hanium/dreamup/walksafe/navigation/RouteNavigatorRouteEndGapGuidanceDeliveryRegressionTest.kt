package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.HorizontalUncertaintyKind
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S07-11, 07.txt:127-133: the route-end gap notice must survive actual guidance delivery. */
class RouteNavigatorRouteEndGapGuidanceDeliveryRegressionTest {
    @Test fun aFreshEndpointUpdateDeliversTheExistingGapNoticeWithoutFacing() {
        val navigator = navigator()
        walk(navigator)
        val update = sample(navigator, 200.0, 81_000L)
        assertEquals(RouteMatchQuality.HIGH, navigator.currentRouteMatch()?.quality)
        assertEquals(RouteAlignmentReason.NEAR_ROUTE_END, update.routeAlignmentDiagnostic?.reason)
        assertGapNotice(update)
        assertTrue(requireNotNull(update.instruction).endsWith(requireNotNull(navigator.currentInstruction(fix(200.0, 81_000L)))))
    }

    @Test fun productionDefaultsDeliverTheSameNoticeFromFreshAcceptedEndpointEvidence() {
        val navigator = RouteNavigator().apply { setRoute(route(), destination = point(240.0)) }
        walk(navigator)
        val update = sample(navigator, 200.0, 81_000L)
        assertEquals(RouteMatchQuality.HIGH, navigator.currentAcceptedRouteMatchFor(81_000L)?.quality)
        assertEquals(RouteAlignmentReason.NEAR_ROUTE_END, update.routeAlignmentDiagnostic?.reason)
        assertGapNotice(update)
    }

    @Test fun productionDefaultsAndTheRealRequestOriginStillDeliverTheEndNotice() {
        val navigator = RouteNavigator().apply {
            setRoute(route(), destination = point(240.0), origin = fix(0.0, 1_000L))
        }
        walk(navigator)
        val update = sample(navigator, 200.0, 81_000L)
        assertEquals(RouteMatchQuality.HIGH, navigator.currentAcceptedRouteMatchFor(81_000L)?.quality)
        assertEquals(RouteAlignmentReason.NEAR_ROUTE_END, update.routeAlignmentDiagnostic?.reason)
        assertGapNotice(update)
    }

    @Test fun aManualGuidanceRequestKeepsTheRouteEndNotice() {
        val navigator = atEnd()
        assertGapNotice(requireNotNull(navigator.currentGuidance(81_100L)))
    }

    @Test fun aDeliveryRetryKeepsTheRouteEndNotice() {
        val navigator = atEnd()
        assertGapNotice(requireNotNull(navigator.retryGuidance(81_100L)))
    }

    @Test fun usableFacingDoesNotTurnTheGapIntoARelativeDirectionOrMovementCommand() {
        for (heading in listOf(0.0, 90.0, 270.0)) {
            val navigator = navigator()
            walk(navigator)
            val update = sample(navigator, 200.0, 81_000L, heading)
            assertGapNotice(update)
            assertFalse(requireNotNull(update.speechCueToken).requiresFacingEvidence)
            assertEquals(RouteFacingExpression.NOT_SPOKEN, update.facingDiagnostic?.expression)
        }
    }

    @Test fun theReservedNoticeSurvivesCompassChangesButNotExpiredLocationEvidence() {
        val navigator = navigator()
        walk(navigator)
        val update = sample(navigator, 200.0, 81_000L)
        assertGapNotice(update)
        assertTrue(navigator.reserveInstruction(update))
        assertTrue(navigator.isInstructionCurrent(update, 81_100L, null))
        assertTrue(navigator.isInstructionCurrent(update, 81_200L, facing(270.0, 81_200L)))
        assertFalse(navigator.isInstructionCurrent(update, 91_001L, null))
        assertNull(navigator.currentGuidance(91_001L)?.instruction)
        assertNull(navigator.retryGuidance(91_001L)?.instruction)
    }

    @Test fun enteringTheEndNoticeRevokesAReservedOrdinaryGuidanceCue() {
        val navigator = navigator()
        walk(navigator, lastEastM = 150.0)
        val ordinary = sample(navigator, 170.0, 61_000L, 90.0)
        assertFalse(requireNotNull(ordinary.instruction).contains(GAP_TEXT))
        assertTrue(navigator.reserveInstruction(ordinary))
        val notice = sample(navigator, 200.0, 71_000L)
        assertGapNotice(notice)
        assertTrue(notice.cancelStaleNavigationSpeech)
        assertFalse(navigator.isInstructionCurrent(ordinary, 71_000L, null))
        assertFalse(navigator.reserveInstruction(ordinary))
        assertTrue(navigator.reserveInstruction(notice))
        navigator.acknowledgeInstruction(ordinary, 71_001L)
        assertTrue(navigator.isInstructionCurrent(notice, 71_001L, null))
    }

    @Test fun completedOrdinaryGuidanceInTheSameDistanceBandDoesNotHideTheNewNotice() {
        val navigator = navigator(guidanceIntervalMs = 6_000L)
        walk(navigator, lastEastM = 150.0)
        val ordinary = sample(navigator, 170.0, 61_000L)
        assertTrue(navigator.reserveInstruction(ordinary))
        navigator.acknowledgeInstruction(ordinary, 61_000L)
        val notice = sample(navigator, 180.0, 66_000L)
        assertEquals(ordinary.speechCueToken?.distanceBand, notice.speechCueToken?.distanceBand)
        assertGapNotice(notice)
        assertTrue(navigator.reserveInstruction(notice))
    }

    @Test fun failedNoticeDeliveryRetriesAfterTheExistingDelayAndIgnoresTheLateAck() {
        val navigator = navigator()
        walk(navigator)
        val first = sample(navigator, 200.0, 81_000L)
        assertGapNotice(first)
        assertTrue(navigator.reserveInstruction(first))
        navigator.releaseInstruction(first, 81_001L)
        assertEquals("guidance_retry_wait", navigator.retryGuidance(83_000L)?.reason)
        navigator.acknowledgeInstruction(first, 83_000L)
        val retry = requireNotNull(navigator.retryGuidance(83_001L))
        assertGapNotice(retry)
        assertNotEquals(first.speechCueToken, retry.speechCueToken)
        assertTrue(navigator.reserveInstruction(retry))
        assertFalse(navigator.reserveInstruction(first))
    }

    @Test fun completedNoticeKeepsDedupeWhileAnExplicitRepeatCanStillDeliverIt() {
        val navigator = navigator()
        walk(navigator)
        val first = sample(navigator, 200.0, 81_000L)
        assertGapNotice(first)
        assertTrue(navigator.reserveInstruction(first))
        navigator.acknowledgeInstruction(first, 81_001L)
        assertEquals("guidance_already_completed", navigator.retryGuidance(81_100L)?.reason)
        val repeated = requireNotNull(navigator.currentGuidance(81_200L))
        assertGapNotice(repeated)
        assertTrue(navigator.reserveInstruction(repeated))
    }

    @Test fun interruptionRouteRemovalOrReinstallationCannotKeepTheNoticeCurrent() {
        for (invalidate in listOf<(RouteNavigator) -> Unit>(
            { it.onPositioningEvidenceInterrupted() }, { it.clear() },
            { it.setRoute(route(), destination = point(240.0)) },
        )) {
            val navigator = navigator()
            walk(navigator)
            val notice = sample(navigator, 200.0, 81_000L)
            assertGapNotice(notice)
            assertTrue(navigator.reserveInstruction(notice))
            invalidate(navigator)
            assertFalse(navigator.isInstructionCurrent(notice, 81_100L, null))
            assertNull(navigator.currentGuidance(81_100L)?.instruction)
        }
    }

    @Test fun aFarGapDoesNotEnableArrivalButtonsOrVoiceConfirmation() {
        val navigator = atEnd()
        assertEquals("arrival_request_not_near_destination", navigator.requestArrivalConfirmation(81_100L).reason)
        assertNull(navigator.pendingDecisionToken())
        assertEquals(AndroidVoiceCommand.ConfirmArrival, parseAndroidVoiceCommand("도착 확인"))
        assertFalse(navigator.confirmArrival().arrived)
        assertTrue(navigator.hasRoute())
    }

    @Test fun theNormalEndpointStillUsesProximityAndTheExistingArrivalDwellAndToken() {
        val navigator = navigator(destinationEastM = 200.0)
        walk(navigator, lastEastM = 180.0)
        val first = sample(navigator, 190.0, 61_000L)
        assertFalse(requireNotNull(first.instruction).contains(GAP_TEXT))
        assertTrue("${first.reason}: ${first.instruction}", requireNotNull(first.instruction).contains("목적지 근처일 수 있습니다"))
        assertFalse(first.arrivalCandidate)
        assertFalse(sample(navigator, 200.0, 62_000L).arrivalCandidate)
        assertFalse(sample(navigator, 200.0, 63_000L).arrivalCandidate)
        assertFalse(sample(navigator, 200.0, 64_000L).arrivalCandidate)
        val question = sample(navigator, 200.0, 65_000L)
        assertTrue(question.arrivalCandidate)
        assertFalse(question.arrived)
        val token = requireNotNull(navigator.pendingDecisionToken())
        assertTrue(navigator.confirmArrival(token).arrived)
    }

    @Test fun earlierProgressAndUnacceptedEndpointEvidenceCannotOfferTheEndNotice() {
        val navigator = navigator()
        walk(navigator, lastEastM = 150.0)
        val earlier = sample(navigator, 150.0, 61_000L, 90.0)
        assertFalse(requireNotNull(earlier.instruction).contains(GAP_TEXT))
        val offset = fix(200.0, 71_000L, northM = 12.0)
        val low = navigator.update(offset, 71_000L, false, filteredPosition = filtered(offset))
        assertEquals(RouteMatchQuality.LOW, navigator.currentRouteMatch()?.quality)
        assertNull(navigator.currentAcceptedRouteMatchFor(71_000L))
        assertFalse(low.instruction.orEmpty().contains(GAP_TEXT))
        assertFalse(low.arrivalCandidate)
        assertFalse(low.arrived)
    }

    private fun assertGapNotice(update: RouteNavigatorUpdate) {
        assertNotNull("The actual delivery must retain the end notice: ${update.reason}", update.instruction)
        val text = requireNotNull(update.instruction)
        assertTrue(text, text.contains("경로 종점입니다."))
        assertTrue(text, text.contains(GAP_TEXT))
        assertTrue(text, text.contains("요청한 목적지의 최종 접근을 확인하세요."))
        for (command in listOf("이동하세요", "꺾으세요", "돌리세요", "맞추세요", "약 12시", "목적지까지 약")) {
            assertFalse(text, text.contains(command))
        }
        assertFalse(update.arrived)
        assertFalse(update.arrivalCandidate)
        assertFalse(update.offRoute)
        assertFalse(update.shouldReroute)
        assertFalse(update.directionOnly)
        assertNull(update.pendingUserDecision)
    }

    private fun atEnd() = navigator().also { walk(it); sample(it, 200.0, 81_000L) }

    private fun navigator(destinationEastM: Double = 240.0, guidanceIntervalMs: Long = 0L) =
        RouteNavigator(RouteNavigatorConfig(maximumProgressAdvanceM = 40.0, guidanceIntervalMs = guidanceIntervalMs)).apply {
            setRoute(route(), destination = point(destinationEastM))
        }

    private fun route() = WalkingRoute("STAIR_AVOID", WalkingRouteSummary(200, 200),
        listOf(point(0.0), point(200.0)),
        listOf(WalkingRouteGuidePoint(0, point(20.0), null, 20, 180, turnType = 13)))

    private fun walk(navigator: RouteNavigator, lastEastM: Double = 195.0) {
        listOf(0.0, 30.0, 70.0, 110.0, 150.0, 180.0, 195.0).filter { it <= lastEastM }
            .forEachIndexed { index, eastM -> sample(navigator, eastM, 1_000L + index * 10_000L) }
    }

    private fun sample(navigator: RouteNavigator, eastM: Double, atMs: Long, heading: Double? = null): RouteNavigatorUpdate {
        val fix = fix(eastM, atMs)
        return navigator.update(fix, atMs, false, filteredPosition = filtered(fix),
            facingObservation = heading?.let { facing(it, atMs) })
    }

    private fun filtered(fix: TrustedLocation) = FilteredRoutePosition(RoutePoint(fix.latitude, fix.longitude),
        3.0, fix.elapsedRealtimeMs, uncertaintyKind = HorizontalUncertaintyKind.FILTER_MAX_AXIS_SIGMA)

    private fun fix(eastM: Double, atMs: Long, northM: Double = 0.0) = point(eastM, northM).let {
        TrustedLocation(it.latitude, it.longitude, 3f, atMs, HorizontalUncertaintyKind.ANDROID_RADIUS_68)
    }

    private fun point(eastM: Double, northM: Double = 0.0) =
        RoutePoint(northM / 111_194.92664455874, eastM / 111_194.92664455874)

    private fun facing(heading: Double, atMs: Long) = RouteFacingObservation(heading, 5.0, atMs)

    companion object {
        private const val GAP_TEXT = "요청한 목적지는 종점에서 직선으로 약 40m 떨어져 있습니다."
    }
}

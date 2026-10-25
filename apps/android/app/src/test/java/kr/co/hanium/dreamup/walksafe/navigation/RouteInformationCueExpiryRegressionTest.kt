package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The non-relative cue keeps route/notice ownership without inheriting the action's facing TTL. */
class RouteInformationCueExpiryRegressionTest {
    @Test
    fun informationNeedsReservedActionCompletionButOutlivesItsFacingLease() {
        val navigator = navigator()
        val action = offer(navigator)
        val information = requireNotNull(action.infoUtterance)
        assertFalse(navigator.reserveInformation(information, 1_000L))
        assertTrue(navigator.reserveInstruction(action))
        assertFalse(navigator.reserveInformation(information, 1_100L))
        navigator.acknowledgeInstruction(action, 1_700L)
        assertFalse("information cannot precede the actual completion that enabled it",
            navigator.isInformationCurrent(information, 1_699L))
        assertFalse(navigator.isInstructionCurrent(action, 1_700L, facing(1_700L)))
        assertTrue(navigator.isInformationCurrent(information, 1_700L))
        assertTrue(navigator.reserveInformation(information, 1_700L))
        assertFalse(navigator.reserveInformation(information, 1_700L))
        assertTrue(navigator.isInformationCurrent(information, 1_800L))
        navigator.finishInformation(information)
        assertFalse(navigator.isInformationCurrent(information, 1_800L))
        assertFalse(navigator.reserveInformation(information, 1_800L))
        assertEquals(1_000L, action.speechCueToken?.facingObservedAtElapsedRealtimeMs)
        assertEquals(1_500L, action.speechCueToken?.facingValidUntilElapsedRealtimeMs)
    }

    @Test
    fun unreservedOrFailedActionCannotActivateInformation() {
        for (failed in listOf(false, true)) {
            val navigator = navigator()
            val action = offer(navigator)
            val information = requireNotNull(action.infoUtterance)
            if (failed) {
                assertTrue(navigator.reserveInstruction(action))
                navigator.releaseInstruction(action, 1_100L)
            }
            navigator.acknowledgeInstruction(action, 1_200L)
            assertFalse("failed=$failed", navigator.isInformationCurrent(information, 1_200L))
            assertFalse(navigator.reserveInformation(information, 1_200L))
        }
    }

    @Test
    fun lateOldCompletionCannotActivateOrConsumeReplacementInformation() {
        val navigator = navigator()
        val old = offer(navigator)
        val oldInformation = requireNotNull(old.infoUtterance)
        assertTrue(navigator.reserveInstruction(old))
        navigator.releaseInstruction(old, 1_100L)
        val replacement = requireNotNull(navigator.retryGuidance(3_100L, facing(3_100L)))
        assertNotEquals(old.speechCueToken, replacement.speechCueToken)
        val replacementInformation = requireNotNull(replacement.infoUtterance)
        assertTrue(navigator.reserveInstruction(replacement))
        navigator.acknowledgeInstruction(old, 3_150L)
        assertFalse(navigator.isInformationCurrent(oldInformation, 3_150L))
        assertFalse(navigator.isInformationCurrent(replacementInformation, 3_150L))
        navigator.acknowledgeInstruction(replacement, 3_200L)
        assertTrue(navigator.reserveInformation(replacementInformation, 3_200L))
        navigator.acknowledgeInstruction(old, 3_250L)
        navigator.finishInformation(oldInformation)
        assertTrue(navigator.isInformationCurrent(replacementInformation, 3_250L))
    }

    @Test
    fun alteredPayloadNoticeRouteOrParentCannotReserveTheCapturedInformation() {
        val navigator = navigator()
        val action = offer(navigator)
        val information = complete(navigator, action)
        val token = information.cueToken
        val altered = listOf(
            information.copy(message = information.message + " 다른 설명입니다."),
            information.copy(cueToken = token.copy(routeId = "other-route")),
            information.copy(cueToken = token.copy(routeRevision = token.routeRevision + 1L)),
            information.copy(cueToken = token.copy(actionCueRevision = token.actionCueRevision + 1L)),
            information.copy(cueToken = token.copy(guideIndex = token.guideIndex?.plus(1))),
            information.copy(cueToken = token.copy(positionEstimateUncertain = !token.positionEstimateUncertain)),
        )
        for (candidate in altered) {
            assertFalse(navigator.isInformationCurrent(candidate, 1_700L))
            assertFalse(navigator.reserveInformation(candidate, 1_700L))
            navigator.finishInformation(candidate)
            assertTrue(navigator.isInformationCurrent(information, 1_700L))
        }
        assertTrue(navigator.reserveInformation(information, 1_700L))
    }

    @Test
    fun aLateFacingChangeAloneDoesNotInvalidateNonRelativeInformation() {
        val navigator = navigator()
        val action = offer(navigator)
        val information = complete(navigator, action)
        val changed = navigator.update(fix(1_800L), 1_800L, false,
            facingObservation = RouteFacingObservation(180.0, 5.0, 1_800L))
        assertFalse(changed.cancelStaleNavigationSpeech)
        assertNotNull(navigator.currentRouteMatch())
        assertTrue(navigator.isInformationCurrent(information, 1_800L))
        assertTrue(navigator.reserveInformation(information, 1_800L))
        assertFalse(information.message.contains("시 방향"))
        assertFalse(information.message.contains("꺾으세요"))
        assertEquals(1_500L, action.speechCueToken?.facingValidUntilElapsedRealtimeMs)
    }

    @Test
    fun routeReplacementOrExpiredLocationContextCannotReviveAnOldInformationCue() {
        for (replaceRoute in listOf(false, true)) {
            val navigator = navigator()
            val information = complete(navigator, offer(navigator))
            val now = if (replaceRoute) 1_800L else 61_000L
            if (replaceRoute) navigator.setRoute(route())
            assertFalse("replaceRoute=$replaceRoute", navigator.isInformationCurrent(information, now))
            navigator.update(fix(now + 1L), now + 1L, false, facingObservation = facing(now + 1L))
            assertFalse(navigator.isInformationCurrent(information, now + 1L))
            assertFalse(navigator.reserveInformation(information, now + 1L))
        }
    }

    @Test
    fun positionUncertaintyTransitionsDoNotQueueUnsolicitedExplanations() {
        val navigator = RouteNavigator(allowDegradedRouteGuidance = true).apply {
            setRoute(WalkingRoute("TEST", WalkingRouteSummary(125, 300),
                listOf(point(0.0), point(30.0), point(30.0, 20.0), point(0.0, 20.0),
                    point(0.0, 5.0), point(30.0, 5.0)), emptyList()))
        }
        navigator.update(branchFix(0.0, 1_000L), 1_000L, false, facingObservation = facing(1_000L))
        val uncertain = navigator.update(branchFix(2.4, 2_000L), 2_000L, false,
            facingObservation = facing(2_000L))
        assertEquals(RouteMatchQuality.LOW, navigator.currentRouteMatch()?.quality)
        assertNotNull(uncertain.instruction)
        assertNotNull(uncertain.speechCueToken)
        assertTrue(navigator.reserveInstruction(uncertain))
        navigator.acknowledgeInstruction(uncertain, 2_001L)
        assertFalse("completed action must not be reserved again", navigator.reserveInstruction(uncertain))
        assertEquals(null, uncertain.infoUtterance)
        val recovered = navigator.update(branchFix(0.0, 2_100L), 2_100L, false, facingObservation = facing(2_100L))
        assertEquals(RouteMatchQuality.HIGH, navigator.currentRouteMatch()?.quality)
        assertEquals(null, recovered.infoUtterance)
        val uncertainAgain = navigator.update(branchFix(2.4, 2_200L), 2_200L, false, facingObservation = facing(2_200L))
        assertEquals(RouteMatchQuality.LOW, navigator.currentRouteMatch()?.quality)
        assertEquals(null, uncertainAgain.infoUtterance)
    }

    @Test
    fun arrivalStateQueryDoesNotRevokeTheInformationOrOpenADecision() {
        val navigator = RouteNavigator().apply { setRoute(route(), destination = RoutePoint(0.0, 0.0)) }
        val information = complete(navigator, offer(navigator))
        assertTrue(navigator.reserveInformation(information, 1_700L))
        val decision = navigator.requestArrivalConfirmation(1_800L)
        assertEquals(null, decision.pendingUserDecision)
        assertTrue(navigator.isInformationCurrent(information, 1_800L))
        assertTrue(navigator.hasRoute())
    }

    private fun complete(navigator: RouteNavigator, action: RouteNavigatorUpdate): RouteInformationUtterance {
        assertTrue(navigator.reserveInstruction(action))
        navigator.acknowledgeInstruction(action, 1_700L)
        return requireNotNull(action.infoUtterance)
    }

    private fun navigator() = RouteNavigator().apply { setRoute(route()) }
    private fun offer(navigator: RouteNavigator) = navigator.update(fix(1_000L), 1_000L, false,
        facingObservation = facing(1_000L))
    private fun fix(at: Long) = TrustedLocation(0.0, 0.0, 2f, at)
    private fun facing(at: Long) = RouteFacingObservation(90.0, 5.0, at)

    private fun route() = WalkingRoute("STAIR_AVOID", WalkingRouteSummary(333, 300),
        listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.001), RoutePoint(0.001, 0.001), RoutePoint(0.001, 0.002)),
        listOf(WalkingRouteGuidePoint(0, RoutePoint(0.0, 0.0), null, 0, 333, turnType = 200, pointType = "SP"),
            WalkingRouteGuidePoint(1, RoutePoint(0.0, 0.001), null, 111, 222, turnType = 12)))

    private fun point(eastM: Double, northM: Double = 0.0) =
        RoutePoint(northM / 111194.92664455874, eastM / 111194.92664455874)

    private fun branchFix(northM: Double, at: Long): TrustedLocation {
        val point = point(15.0, northM)
        return TrustedLocation(point.latitude, point.longitude, 3f, at)
    }
}

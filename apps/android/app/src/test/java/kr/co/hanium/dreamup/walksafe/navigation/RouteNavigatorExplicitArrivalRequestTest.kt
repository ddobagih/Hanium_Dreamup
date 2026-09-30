package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 07.txt #36: a rejected arrival must not force the walker away before confirming it again. */
class RouteNavigatorExplicitArrivalRequestTest {
    @Test
    fun afterRejectionTheSamePlaceCanBeConfirmedOnlyThroughANewUserQuestion() {
        val navigator = navigator()
        // S07-32 (R6): the arrival question needs 3 s of consecutive near-destination evidence.
        for (at in 1_000L..3_000L step 1_000L) assertFalse(sample(navigator, 298.0, at).arrivalCandidate)
        assertTrue(sample(navigator, 298.0, 4_000).arrivalCandidate)
        assertEquals("arrival_rejected_route_retained", navigator.rejectArrival().reason)
        // Automatic re-asking stays suppressed while the walker remains at the same place.
        repeat(5) { assertFalse(sample(navigator, 298.0, 5_000L + it * 1_000).arrivalCandidate) }
        assertFalse(navigator.confirmArrival().arrived)

        // The user's own request is not delayed by the dwell: it opens at once.
        val request = navigator.requestArrivalConfirmation(10_000)

        assertEquals("arrival_confirmation_requested_by_user", request.reason)
        assertEquals(RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION, request.pendingUserDecision)
        assertFalse(request.arrived)
        assertTrue(navigator.hasRoute())
        val token = requireNotNull(navigator.pendingDecisionToken())
        assertEquals(RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION, token.decision)
        // The next fix keeps the same question instead of re-announcing a new one.
        assertTrue(sample(navigator, 298.0, 11_000).arrivalCandidate)
        assertEquals(token, navigator.pendingDecisionToken())
        assertTrue(navigator.confirmArrival(token).arrived)
        assertFalse(navigator.hasRoute())
    }

    @Test
    fun explicitQuestionNamesTheDestinationAndCanBeRejectedAgainWithoutRearmingTheAutomaticOne() {
        val navigator = navigator(destination = point(100.0, -200.0).copy(name = "서울역"))
        sample(navigator, 298.0, 1_000)
        sample(navigator, 298.0, 2_000)
        navigator.rejectArrival()
        sample(navigator, 298.0, 3_000)

        val request = navigator.requestArrivalConfirmation(3_500)
        assertTrue(request.instruction?.contains("서울역") == true)
        // The answers the question names must be phrases the voice parser accepts.
        assertTrue(request.instruction!!.contains("도착 확인, 아니면 도착 아니야라고"))
        assertEquals(AndroidVoiceCommand.ConfirmArrival, parseAndroidVoiceCommand("도착 확인"))
        assertEquals(AndroidVoiceCommand.RejectArrival, parseAndroidVoiceCommand("도착 아니야"))
        val token = requireNotNull(navigator.pendingDecisionToken())
        assertEquals("arrival_rejected_route_retained", navigator.rejectArrival(token).reason)
        repeat(3) { assertFalse(sample(navigator, 298.0, 4_000L + it * 1_000).arrivalCandidate) }
        assertTrue(navigator.hasRoute())
    }

    @Test
    fun requestAwayFromTheDestinationDoesNotOpenAQuestionOrPauseGuidance() {
        val navigator = navigator()
        sample(navigator, 250.0, 1_000)

        val request = navigator.requestArrivalConfirmation(1_500)

        assertEquals("arrival_request_not_near_destination", request.reason)
        assertNull(request.pendingUserDecision)
        assertNull(navigator.pendingDecisionToken())
        assertNotNull(request.instruction)
        assertTrue(navigator.hasRoute())
        assertFalse(sample(navigator, 251.0, 2_000).userDecisionRequired)
    }

    @Test
    fun anotherOpenDecisionOrDeviationIsNeverReplacedByAnArrivalQuestion() {
        val navigator = navigator()
        sample(navigator, 298.0, 1_000)
        navigator.onUntrustedLocation()
        val recheck = requireNotNull(navigator.pendingDecisionToken())

        val request = navigator.requestArrivalConfirmation(1_500)

        assertEquals("arrival_request_unavailable", request.reason)
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, request.pendingUserDecision)
        assertEquals(recheck, navigator.pendingDecisionToken())
    }

    @Test
    fun staleOrMissingLocationAndTestModeCannotOpenTheQuestion() {
        val noFix = navigator()
        assertEquals("arrival_request_location_unavailable", noFix.requestArrivalConfirmation(1_000).reason)

        val stale = navigator()
        sample(stale, 298.0, 1_000)
        val late = stale.requestArrivalConfirmation(1_000 + LocationTrustConfig().maxAgeMs + 1)
        assertEquals("arrival_request_location_unavailable", late.reason)
        assertNull(stale.pendingDecisionToken())

        val degraded = navigator(degraded = true)
        sample(degraded, 298.0, 1_000)
        assertEquals("arrival_request_unavailable", degraded.requestArrivalConfirmation(1_500).reason)
        assertNull(degraded.pendingDecisionToken())

        assertEquals("arrival_request_unavailable", RouteNavigator().requestArrivalConfirmation(1_000).reason)
    }

    @Test
    fun explicitQuestionIsWithdrawnWhenTheWalkerLeavesLikeTheAutomaticOne() {
        val navigator = navigator()
        sample(navigator, 298.0, 1_000)
        sample(navigator, 298.0, 2_000)
        navigator.rejectArrival()
        sample(navigator, 298.0, 3_000)
        navigator.requestArrivalConfirmation(3_500)
        val token = requireNotNull(navigator.pendingDecisionToken())

        sample(navigator, 250.0, 4_000)
        sample(navigator, 249.0, 5_000)

        assertNull(navigator.pendingDecisionToken())
        assertFalse(navigator.confirmArrival(token).arrived)
        assertTrue(navigator.hasRoute())
    }

    private fun navigator(degraded: Boolean = false, destination: RoutePoint? = null) =
        RouteNavigator(allowDegradedRouteGuidance = degraded).apply {
            setRoute(
                WalkingRoute(
                    "TEST", WalkingRouteSummary(300, 300),
                    listOf(point(0.0), point(100.0), point(100.0, -200.0)),
                    listOf(
                        WalkingRouteGuidePoint(0, point(100.0), null, 100, 200, turnType = 13),
                        WalkingRouteGuidePoint(1, point(100.0, -150.0), null, 250, 50, turnType = 11),
                    ),
                ),
                destination = destination,
            )
        }

    private fun sample(navigator: RouteNavigator, progress: Double, now: Long): RouteNavigatorUpdate =
        navigator.update(fix(progress, now), now, false)

    private fun fix(progress: Double, now: Long): TrustedLocation {
        val point = if (progress <= 100) point(progress) else point(100.0, 100.0 - progress)
        return TrustedLocation(point.latitude, point.longitude, 3f, now)
    }

    private fun point(east: Double, north: Double = 0.0) =
        RoutePoint(north / 111194.92664455874, east / 111194.92664455874)
}

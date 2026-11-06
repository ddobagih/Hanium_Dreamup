package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Explicit arrival speech is a query and cannot replace automatic trusted evidence. */
class RouteNavigatorExplicitArrivalRequestTest {
    @Test
    fun explicitRequestCannotCreateArrivalOrBypassTheExistingDwell() {
        val navigator = navigator()
        sample(navigator, 298.0, 0) // Retire the earlier protected guide before starting arrival dwell.
        sample(navigator, 298.0, 1_000)
        sample(navigator, 298.0, 2_000)
        val request = navigator.requestArrivalConfirmation(2_500)
        assertEquals("arrival_waiting_for_trusted_evidence", request.reason)
        assertNull(navigator.pendingDecisionToken())
        assertFalse(request.arrived)
        assertFalse(sample(navigator, 298.0, 3_000).arrivalCandidate)
        assertTrue(sample(navigator, 298.0, 4_000).arrivalCandidate)
        val token = requireNotNull(navigator.pendingDecisionToken())
        assertFalse(navigator.completeDetectedArrival(token.copy(decisionRevision = token.decisionRevision + 1)).arrived)
        assertTrue(navigator.completeDetectedArrival(token).arrived)
        assertFalse(navigator.hasRoute())
        assertFalse(navigator.completeDetectedArrival(token).arrived)
    }

    @Test
    fun explicitRequestAfterRejectionNeverRearmsOrAsksAQuestion() {
        val navigator = navigator()
        sample(navigator, 298.0, 0)
        for (at in 1_000L..4_000L step 1_000L) sample(navigator, 298.0, at)
        navigator.rejectArrival()
        val request = navigator.requestArrivalConfirmation(4_500)
        assertEquals("arrival_waiting_for_trusted_evidence", request.reason)
        assertFalse(request.instruction!!.contains("말씀"))
        assertFalse(request.instruction!!.contains("도착 확인"))
        assertNull(navigator.pendingDecisionToken())
        repeat(4) { assertFalse(sample(navigator, 298.0, 5_000L + it * 1_000L).arrivalCandidate) }
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

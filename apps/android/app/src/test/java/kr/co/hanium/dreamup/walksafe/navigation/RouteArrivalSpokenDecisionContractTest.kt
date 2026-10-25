package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trusted automatic arrival announces its terminal result without asking for an answer. */
class RouteArrivalSpokenDecisionContractTest {
    @Test fun qualifiedAutomaticArrivalAnnouncesTheExactEndingAndClaimsOnlyItsCurrentRoute() {
        val navigator = navigator()
        val candidate = automaticQuestion(navigator)
        assertTrue(candidate.arrivalCandidate)
        assertEquals("도착했습니다. 안내를 종료합니다", candidate.instruction)
        assertFalse(candidate.instruction!!.contains("확인"))
        val token = requireNotNull(navigator.pendingDecisionToken())
        assertTrue(navigator.hasRoute())
        val completed = navigator.completeDetectedArrival(token)
        assertTrue(completed.arrived)
        assertEquals(candidate.instruction, completed.instruction)
        assertFalse(navigator.hasRoute())
    }

    @Test fun theOldAutomaticCandidateCannotTerminateAReplacementRoute() {
        val navigator = navigator()
        automaticQuestion(navigator)
        val token = requireNotNull(navigator.pendingDecisionToken())
        navigator.setRoute(WalkingRoute("STAIR_AVOID", WalkingRouteSummary(400, 400),
            listOf(point(0.0), point(400.0)), emptyList()))
        assertFalse(navigator.completeDetectedArrival(token).arrived)
        assertTrue(navigator.hasRoute())
    }

    private fun automaticQuestion(navigator: RouteNavigator): RouteNavigatorUpdate {
        var update: RouteNavigatorUpdate? = null
        for (at in 1_000L..4_000L step 1_000L) {
            val destination = point(300.0)
            update = navigator.update(TrustedLocation(destination.latitude, destination.longitude, 2f, at), at, false)
        }
        return requireNotNull(update)
    }

    private fun navigator() = RouteNavigator().apply {
        setRoute(WalkingRoute("STAIR_AVOID", WalkingRouteSummary(300, 300),
            listOf(point(0.0), point(300.0)), emptyList()))
    }

    private fun point(eastM: Double) = RoutePoint(0.0, eastM / 111_194.92664455874)
}

package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S07-23 / ADD-MOTION-038 (nav-F4, Root R5, 07.txt:231): when the 10 s stationary-jump hold expires
 * while the steps still show no walking (≤ 0.5 m since the jump), a protected guide point (turn,
 * crossing) is not passed on GNSS alone; the user is asked to recheck the location instead.
 */
class StationaryJumpProtectedGuideRecheckRegressionTest {
    private val north = 1.0 / 111_320.0
    private val facing = { at: Long -> RouteFacingObservation(0.0, 5.0, at) }

    @Test
    fun anExpiredHoldWithoutStepsKeepsAProtectedGuideAndAsksForALocationRecheck() {
        for (turnType in listOf(13, 211)) {
            val navigator = navigator(turnType)
            assertEquals(0, update(navigator, 0.0, 1_000L, 0.0).guideIndex)
            assertEquals(0, update(navigator, 1.0, 2_000L, 0.7).guideIndex)
            for (at in 3_000L..13_000L step 1_000L) {
                assertEquals("turnType=$turnType at=$at", 0, update(navigator, 40.0, at, 0.7).guideIndex)
            }
            val expired = update(navigator, 40.0, 14_000L, 0.7)
            assertEquals("turnType=$turnType", "stationary_jump_location_recheck_required", expired.reason)
            assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, expired.pendingUserDecision)
            assertNull(expired.guideIndex)
            assertFalse(expired.arrivalCandidate)
            // Still no steps: the decision stays open and no guidance is resumed from the jump.
            assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, update(navigator, 40.0, 15_000L, 0.7).pendingUserDecision)
            assertEquals("off_route_location_recheck_requested",
                navigator.selectDeviationChoice(RouteDeviationChoice.RECHECK_LOCATION).reason)
            // The rechecked fix is back before the guide: the protected guide was never consumed.
            val rechecked = update(navigator, 1.0, 16_000L, 0.7)
            assertNull(rechecked.pendingUserDecision)
            assertEquals("turnType=$turnType", 0, rechecked.guideIndex)
        }
    }

    @Test
    fun stepsBeyondHalfAMetreAfterTheHoldReleaseTheProtectedGuideToGnss() {
        val navigator = navigator(13)
        update(navigator, 0.0, 1_000L, 0.0)
        update(navigator, 1.0, 2_000L, 0.7)
        for (at in 3_000L..13_000L step 1_000L) assertEquals("at=$at", 0, update(navigator, 40.0, at, 0.7).guideIndex)
        val released = update(navigator, 40.0, 14_000L, 1.4)
        assertNull(released.pendingUserDecision)
        assertNotEquals(0, released.guideIndex)
    }

    private fun update(navigator: RouteNavigator, northM: Double, at: Long, steps: Double?) =
        navigator.update(TrustedLocation(37.0 + northM * north, 127.0, 3f, at), at, false, steps, facing(at))
    private fun navigator(turnType: Int) = RouteNavigator().apply {
        setRoute(WalkingRoute(
            priority = "TEST",
            summary = WalkingRouteSummary(200, 200),
            polyline = listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0 + 200 * north, 127.0)),
            guidePoints = listOf(
                WalkingRouteGuidePoint(0, RoutePoint(37.0 + 20 * north, 127.0), null, 20, 180, turnType = turnType),
                WalkingRouteGuidePoint(1, RoutePoint(37.0 + 150 * north, 127.0), "직진하세요.", 150, 50, turnType = 11),
            ),
        ))
    }
}

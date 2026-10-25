package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteNavigatorCappedProgressRegressionTest {
    // This fixture observes capped route progress and guide order, independently of heading recheck delay.
    // Production 1.5-second delay and immediate safety actions are covered by RouteDirectionRecheckGraceTest.

    @Test
    fun acceptedMatchBeyondAdvanceCapUpdatesDistanceAndDoesNotRepeatPassedTurn() {
        val navigator = navigator()
        assertEquals(0, navigator.update(fix(0.0, 1_000L), 1_000L, false).guideIndex)

        val passed = fix(60.0, 2_000L)
        val update = navigator.update(passed, 2_000L, false)
        val match = requireNotNull(navigator.currentAcceptedRouteMatchFor(2_000L))
        assertEquals(60.0, requireNotNull(match.geometricProgressM), 1.0)
        assertEquals(1, update.guideIndex)
        assertTrue(navigator.currentInstruction(passed)?.contains("90m 앞") == true)
        assertFalse(navigator.currentInstruction(passed)?.contains("오른쪽으로 꺾으세요") == true)

        (61..63).forEach { progress ->
            val atMs = 2_000L + progress
            val next = fix(progress.toDouble(), atMs)
            val repeated = navigator.update(next, atMs, false)
            assertNotNull(navigator.currentAcceptedRouteMatchFor(atMs))
            assertEquals(1, repeated.guideIndex)
            assertTrue(navigator.currentInstruction(next)?.contains("${150 - progress}m 앞") == true)
            assertFalse(navigator.currentInstruction(next)?.contains("오른쪽으로 꺾으세요") == true)
        }
    }

    @Test
    fun progressChangeDoesNotResumeGuidanceWhileOffRouteDecisionIsPending() {
        val navigator = navigator()
        navigator.update(fix(0.0, 1_000L), 1_000L, false)
        navigator.update(fix(60.0, 2_000L), 2_000L, false)

        val offRoute = fix(60.0, 3_000L, northM = 100.0)
        val suspected = navigator.update(offRoute, 3_000L, false)
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, suspected.pendingUserDecision)
        assertNull(navigator.currentInstruction(offRoute))

        val confirmed = navigator.update(fix(60.0, 4_000L, northM = 100.0), 4_000L, false)
        assertEquals(RouteNavigatorUserDecision.REROUTE, confirmed.pendingUserDecision)
        assertFalse(confirmed.shouldReroute)
    }

    private fun navigator() = RouteNavigator(
        RouteNavigatorConfig(directionRecheckGraceMs = 0L, maximumProgressAdvanceM = 40.0, guidanceIntervalMs = 0L),
    ).apply {
        setRoute(WalkingRoute(
            priority = "TEST",
            summary = WalkingRouteSummary(200, 200),
            polyline = listOf(point(0.0), point(200.0)),
            guidePoints = listOf(
                WalkingRouteGuidePoint(0, point(50.0), null, 50, 150, turnType = 13),
                WalkingRouteGuidePoint(1, point(150.0), null, 150, 50, turnType = 11),
            ),
        ))
    }

    private fun fix(eastM: Double, atMs: Long, northM: Double = 0.0): TrustedLocation {
        val position = point(eastM, northM)
        return TrustedLocation(position.latitude, position.longitude, 3f, atMs)
    }

    private fun point(eastM: Double, northM: Double = 0.0) =
        RoutePoint(northM / 111194.92664455874, eastM / 111194.92664455874)
}

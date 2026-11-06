package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteHeadingEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 07.txt #49: completion or failure of an utterance from a replaced route changes nothing now. */
class RouteNavigatorLateSpeechCallbackTest {
    @Test
    fun lateCompletionAndFailureAfterRouteReplacementKeepTheNewRouteCueAndCadence() {
        val navigator = navigator()
        val old = sample(navigator, 30.0, 1_000)
        assertTrue(navigator.reserveInstruction(old))

        navigator.setRoute(route())
        val replacement = sample(navigator, 31.0, 2_000)
        assertNotNull(replacement.instruction)
        navigator.acknowledgeInstruction(old, 2_500)
        navigator.releaseInstruction(old, 2_500)

        val next = sample(navigator, 32.0, 3_000)
        assertNotNull("late callback must not rate-limit or delay the new route", next.instruction)
        assertEquals(replacement.speechCueToken?.routeRevision, next.speechCueToken?.routeRevision)
        assertTrue(navigator.reserveInstruction(next))
        navigator.acknowledgeInstruction(next, 3_500)
        // The new route's own completion now drives its cadence.
        assertNull(sample(navigator, 33.0, 4_000).instruction)
    }

    @Test
    fun lateCompletionAfterEndOrArrivalCannotTouchTheNextRoute() {
        listOf<(RouteNavigator) -> Unit>(
            { it.clear() },
            { nav ->
                // Arrival confirmation clears the engine before a new route is installed.
                nav.setRoute(route())
                // S07-32 (R6): 3 s of consecutive evidence opens the question.
                listOf(4_000L, 5_000L, 6_000L, 7_000L, 8_000L).forEach { at ->
                    val raw = fix(298.0, at)
                    nav.update(raw, at, false, FilteredRoutePosition(RoutePoint(raw.latitude, raw.longitude), 3.0, at,
                        heading = RouteHeadingEstimate(180.0, 8.0)))
                }
                assertTrue(nav.confirmArrival(requireNotNull(nav.pendingDecisionToken())).arrived)
            },
        ).forEach { end ->
            val navigator = navigator()
            val old = sample(navigator, 30.0, 1_000)
            assertTrue(navigator.reserveInstruction(old))
            end(navigator)
            navigator.setRoute(route())
            sample(navigator, 31.0, 10_000)
            navigator.acknowledgeInstruction(old, 10_500)
            navigator.releaseInstruction(old, 10_500)
            val next = sample(navigator, 32.0, 11_000)
            assertNotNull(next.instruction)
            assertTrue(navigator.reserveInstruction(next))
        }
    }

    private fun navigator() = RouteNavigator().apply { setRoute(route()) }

    private fun route() = WalkingRoute("TEST", WalkingRouteSummary(300, 300),
        listOf(point(0.0), point(100.0), point(100.0, -200.0)), listOf(
            WalkingRouteGuidePoint(0, point(100.0), null, 100, 200, turnType = 13),
            WalkingRouteGuidePoint(1, point(100.0, -150.0), null, 250, 50, turnType = 11)))

    private fun sample(navigator: RouteNavigator, progress: Double, now: Long) =
        navigator.update(fix(progress, now), now, false, facingObservation = RouteFacingObservation(
            if (progress <= 100) 90.0 else 180.0, 5.0, now,
        ))

    private fun fix(progress: Double, now: Long): TrustedLocation {
        val point = if (progress <= 100) point(progress) else point(100.0, 100.0 - progress)
        return TrustedLocation(point.latitude, point.longitude, 3f, now)
    }

    private fun point(east: Double, north: Double = 0.0) = RoutePoint(north / 111194.92664455874, east / 111194.92664455874)
}

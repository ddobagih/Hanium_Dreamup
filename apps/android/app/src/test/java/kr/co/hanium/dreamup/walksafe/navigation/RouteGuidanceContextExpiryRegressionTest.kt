package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.*
import org.junit.Test

/**
 * 08.txt #22: a stored route context is compared with a fresh facing for up to the 10 s location
 * age. Its route bearing must also expire when recent movement could already have reached the
 * next branch, taking the position uncertainty into account.
 */
class RouteGuidanceContextExpiryRegressionTest {
    private val north = 1.0 / 111_320.0
    private fun facing(at: Long) = RouteFacingObservation(0.0, 5.0, at)

    private fun navigator() = RouteNavigator().apply { setRoute(route()) }

    private fun route() = WalkingRoute(
            priority = "TEST",
            summary = WalkingRouteSummary(200, 200),
            polyline = listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0 + 200 * north, 127.0)),
            guidePoints = listOf(
                WalkingRouteGuidePoint(0, RoutePoint(37.0 + 40 * north, 127.0), "직진하세요.", 40, 160, turnType = 11),
            ),
        )

    private fun update(navigator: RouteNavigator, northM: Double, at: Long, steps: Double?) =
        navigator.update(TrustedLocation(37.0 + northM * north, 127.0, 3f, at), at, false, steps, facing(at))

    @Test
    fun walkingUserNearABranchNeedsANewLocationBeforeAnotherBearingComparison() {
        val navigator = navigator()
        update(navigator, 28.6, 1_000L, 28.6)
        assertNotNull(update(navigator, 30.0, 2_000L, 30.0).instruction)
        // 10 m to the guide at ~1.4 m/s: after 6 s the user may already be past the branch.
        assertNull(navigator.currentGuidance(8_000L, facing(8_000L)))
        assertNull(navigator.retryGuidance(8_000L, facing(8_000L)))
    }

    @Test
    fun aNearBranchActionLosingItsPositionContextRequestsNativeCancellationExactlyOnce() {
        val nav = navigator()
        update(nav, 20.0, 1_000L, 20.0)
        val cue = update(nav, 30.0, 4_000L, 30.0)
        assertNotNull(cue.instruction)
        assertTrue(nav.reserveInstruction(cue))
        assertTrue(nav.isInstructionCurrent(cue, 4_080L, facing(4_000L)))
        // The next branch is 10 m away. Three seconds at the just-observed step speed
        // can reach it even though the original GPS measurement is under its 10 s age limit.
        val cancellation = requireNotNull(nav.retryGuidance(7_000L, facing(7_000L)))
        assertNull(cancellation.instruction)
        assertTrue(cancellation.cancelStaleNavigationSpeech)
        assertEquals("stale_route_context", cancellation.reason)
        assertFalse(nav.isInstructionCompletionCurrent(cue, 7_000L))
        assertNull("consume the native cancellation once, without a retry loop", nav.retryGuidance(7_001L, facing(7_001L)))
        assertTrue(nav.hasRoute())
        val recovered = update(nav, 31.0, 7_100L, 31.0)
        assertNotNull(recovered.instruction)
        assertFalse(recovered.cancelStaleNavigationSpeech)
        assertTrue(nav.reserveInstruction(recovered))
        assertTrue(nav.isInstructionCurrent(recovered, 7_100L, facing(7_100L)))
    }

    @Test
    fun expiryDiscoveredByNativeStartValidationStillReachesTheNextOutputUpdate() {
        val nav = navigator()
        update(nav, 20.0, 1_000L, 20.0)
        val cue = update(nav, 30.0, 4_000L, 30.0)
        assertTrue(nav.reserveInstruction(cue))
        assertFalse(nav.prepareFreshDirectionStart(cue, 7_000L, facing(7_000L)))
        val cancellation = requireNotNull(nav.retryGuidance(7_000L, facing(7_000L)))
        assertTrue(cancellation.cancelStaleNavigationSpeech)
        assertFalse(nav.isInstructionCompletionCurrent(cue, 7_000L))
        nav.acknowledgeInstruction(cue, 7_001L)
        assertFalse("a late DONE cannot turn a canceled cue into delivery", nav.isInstructionCompletionCurrent(cue, 7_001L))
    }

    @Test
    fun expiredContextCancellationCannotLeakIntoAReplacedRoute() {
        for (clearFirst in listOf(false, true)) {
            val nav = navigator()
            update(nav, 20.0, 1_000L, 20.0)
            val cue = update(nav, 30.0, 4_000L, 30.0)
            assertTrue(nav.reserveInstruction(cue))
            assertFalse(nav.prepareFreshDirectionStart(cue, 7_000L, facing(7_000L)))
            if (clearFirst) nav.clear()
            nav.setRoute(route())
            val fresh = update(nav, 30.0, 7_100L, 30.0)
            assertNotNull(fresh.instruction)
            assertFalse(fresh.cancelStaleNavigationSpeech)
        }
    }

    @Test
    fun stationaryUserKeepsTheContextForTheLocationAgeLimit() {
        val navigator = navigator()
        update(navigator, 30.0, 1_000L, 30.0)
        assertNotNull(update(navigator, 30.0, 2_000L, 30.0).instruction)
        assertNotNull(navigator.currentGuidance(10_000L, facing(10_000L))?.instruction)
    }

    @Test
    fun farBranchKeepsTheContextWhileWalking() {
        val navigator = navigator()
        update(navigator, 0.0, 1_000L, 0.0)
        update(navigator, 1.4, 2_000L, 1.4)
        // 38.6 m to the guide: 6 s of walking cannot reach it.
        assertNotNull(navigator.currentGuidance(8_000L, facing(8_000L))?.instruction)
    }
}

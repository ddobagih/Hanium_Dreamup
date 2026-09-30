package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 08.txt #22: a stored route context is compared with a fresh facing for up to the 10 s location
 * age. Its route bearing must also expire when recent movement could already have reached the
 * next branch, taking the position uncertainty into account.
 */
class RouteGuidanceContextExpiryRegressionTest {
    private val north = 1.0 / 111_320.0
    private fun facing(at: Long) = RouteFacingObservation(0.0, 5.0, at)

    private fun navigator() = RouteNavigator().apply {
        setRoute(WalkingRoute(
            priority = "TEST",
            summary = WalkingRouteSummary(200, 200),
            polyline = listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0 + 200 * north, 127.0)),
            guidePoints = listOf(
                WalkingRouteGuidePoint(0, RoutePoint(37.0 + 40 * north, 127.0), "직진하세요.", 40, 160, turnType = 11),
            ),
        ))
    }

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

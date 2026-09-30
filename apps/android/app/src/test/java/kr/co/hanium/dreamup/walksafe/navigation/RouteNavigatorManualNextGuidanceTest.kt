package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 07.txt #45: repeated "다음 안내" requests in place are previews, never route progress. */
class RouteNavigatorManualNextGuidanceTest {
    @Test
    fun repeatedManualRequestsInPlaceKeepTheFirstTurnUntilTheWalkerActuallyMoves() {
        val navigator = navigator()
        val first = sample(navigator, 30.0, 1_000)
        assertEquals(0, first.guideIndex)
        assertTrue(navigator.reserveInstruction(first))
        navigator.acknowledgeInstruction(first, 1_100)

        repeat(6) { attempt ->
            val now = 1_500L + attempt * 1_000L
            val manual = navigator.currentGuidance(now, facing(now))
            assertEquals("manual request $attempt", 0, manual?.guideIndex)
            if (manual?.instruction != null && navigator.reserveInstruction(manual)) {
                navigator.acknowledgeInstruction(manual, now + 100)
            }
            assertEquals(0, navigator.retryGuidance(now + 200, facing(now + 200))?.guideIndex ?: 0)
        }
        assertTrue(navigator.currentInstruction(fix(30.0, 7_000))!!.contains("70m 앞, 오른쪽으로 꺾으세요."))

        sample(navigator, 50.0, 12_000)
        assertEquals(0, navigator.currentGuidance(12_100, facing(12_100))?.guideIndex)
        assertTrue(navigator.currentInstruction(fix(50.0, 12_000))!!.contains("50m 앞, 오른쪽으로 꺾으세요."))
    }

    private fun navigator() = RouteNavigator().apply {
        setRoute(WalkingRoute("TEST", WalkingRouteSummary(300, 300),
            listOf(point(0.0), point(100.0), point(100.0, -200.0)), listOf(
                WalkingRouteGuidePoint(0, point(100.0), null, 100, 200, turnType = 13),
                WalkingRouteGuidePoint(1, point(100.0, -150.0), null, 250, 50, turnType = 11))))
    }

    private fun sample(navigator: RouteNavigator, progress: Double, now: Long) =
        navigator.update(fix(progress, now), now, false, facingObservation = facing(now))

    private fun facing(now: Long) = RouteFacingObservation(90.0, 5.0, now)

    private fun fix(progress: Double, now: Long): TrustedLocation {
        val point = point(progress)
        return TrustedLocation(point.latitude, point.longitude, 3f, now)
    }

    private fun point(east: Double, north: Double = 0.0) = RoutePoint(north / 111194.92664455874, east / 111194.92664455874)
}

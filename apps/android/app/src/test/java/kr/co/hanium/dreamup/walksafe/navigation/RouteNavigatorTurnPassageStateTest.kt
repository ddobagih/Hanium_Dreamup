package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteHeadingEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 07.txt #18: beyond a turn point the old turn command is not repeated as a zero-distance cue. */
class RouteNavigatorTurnPassageStateTest {
    @Test
    fun approachThenUncertainPassageThenNextGuideWithoutRepeatingTheOldTurn() {
        val navigator = navigator()
        sample(navigator, 90.0, 1_000, 90.0)
        assertTrue(navigator.currentInstruction(fix(90.0, 1_000))!!.contains("10m 앞, 3시 방향으로 꺾으세요."))

        // Past the turn point but inside the passage margin: the state is "passage unconfirmed".
        val passing = sample(navigator, 101.0, 2_000, 180.0)
        assertEquals(0, passing.guideIndex)
        assertEquals("안내 지점 통과 여부를 확인 중입니다.", navigator.currentInstruction(fix(101.0, 2_000)))
        assertFalse(passing.instruction.orEmpty().contains("3시 방향으로 꺾으세요"))

        val passed = sample(navigator, 110.0, 3_000, 180.0)
        assertEquals(1, passed.guideIndex)
        assertFalse(navigator.currentInstruction(fix(110.0, 3_000)).orEmpty().contains("3시 방향으로 꺾으세요"))
    }

    private fun navigator() = RouteNavigator().apply {
        setRoute(WalkingRoute("TEST", WalkingRouteSummary(300, 300),
            listOf(point(0.0), point(100.0), point(100.0, -200.0)), listOf(
                WalkingRouteGuidePoint(0, point(100.0), null, 100, 200, turnType = 13),
                WalkingRouteGuidePoint(1, point(100.0, -150.0), null, 250, 50, turnType = 11))))
    }

    private fun sample(navigator: RouteNavigator, progress: Double, now: Long, heading: Double): RouteNavigatorUpdate {
        val raw = fix(progress, now)
        return navigator.update(raw, now, false,
            FilteredRoutePosition(RoutePoint(raw.latitude, raw.longitude), 3.0, now, heading = RouteHeadingEstimate(heading, 8.0)),
            facingObservation = RouteFacingObservation(heading, 5.0, now))
    }

    private fun fix(progress: Double, now: Long): TrustedLocation {
        val point = if (progress <= 100) point(progress) else point(100.0, 100.0 - progress)
        return TrustedLocation(point.latitude, point.longitude, 3f, now)
    }

    private fun point(east: Double, north: Double = 0.0) = RoutePoint(north / 111194.92664455874, east / 111194.92664455874)
}

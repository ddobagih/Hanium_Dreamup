package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S07-15 (Root R7, 07.txt:160-166): after a position gap long enough that the time-scaled progress
 * prior exceeds its 80 m floor (3 m/s x gap + 2 x radius > 80 m, ~23 s at 5 m), the first accepted
 * sample only realigns; the next continuous, reachable sample resumes and passes at most one guide.
 */
class RouteLongGapRealignmentRegressionTest {
    @Test
    fun theFirstSampleAfterALongGapRealignsAndTheNextPassesOneGuideAtATime() {
        val navigator = walkedTo50m()
        val realigned = navigator.update(fix(130.0, 56_000L), 56_000L, false)
        assertAccepted(navigator)
        assertEquals("30 s gap: the jump past two guides only realigns", 0, realigned.guideIndex)
        assertEquals(1, navigator.update(fix(131.0, 57_000L), 57_000L, false).guideIndex)
        assertEquals(2, navigator.update(fix(132.0, 58_000L), 58_000L, false).guideIndex)
    }

    @Test
    fun aGapWhosePriorStaysWithinTheFloorKeepsTheOrdinaryCatchUp() {
        val navigator = walkedTo50m()
        // 20 s: 3 x 20 + 2 x 5 = 70 m <= 80 m, so this is not a realignment gap.
        val caughtUp = navigator.update(fix(110.0, 46_000L), 46_000L, false)
        assertAccepted(navigator)
        assertEquals(1, caughtUp.guideIndex)
    }

    @Test
    fun anUnreachableNextSampleKeepsRealigning() {
        val navigator = walkedTo50m()
        assertEquals(0, navigator.update(fix(130.0, 56_000L), 56_000L, false).guideIndex)
        // 100 m in 1 s is not reachable from the realigned sample: still no passage.
        assertEquals(0, navigator.update(fix(230.0, 57_000L), 57_000L, false).guideIndex)
        assertEquals(1, navigator.update(fix(231.0, 58_000L), 58_000L, false).guideIndex)
    }

    @Test
    fun theRealignedSampleIsNotArrivalEvidence() {
        val navigator = RouteNavigator().apply { setRoute(route(emptyList())) }
        for ((index, eastM) in (300..350 step 2).withIndex()) {
            navigator.update(fix(eastM.toDouble(), 1_000L + index * 1_000L), 1_000L + index * 1_000L, false)
        }
        // Last walked sample at 26 s, then a 30 s gap to the route end.
        assertFalse(navigator.update(fix(400.0, 56_000L), 56_000L, false).arrivalCandidate)
        // Arrival evidence starts at the next sample (57 s), so 3 s of it is reached at 60 s, not 59 s.
        for (at in 57_000L..59_000L step 1_000L) assertFalse("at=$at", navigator.update(fix(400.0, at), at, false).arrivalCandidate)
        assertTrue(navigator.update(fix(400.0, 60_000L), 60_000L, false).arrivalCandidate)
    }

    private fun walkedTo50m(): RouteNavigator = RouteNavigator().apply {
        setRoute(route(listOf(
            WalkingRouteGuidePoint(0, point(100.0), "직진하세요.", 100, 300, turnType = 11),
            WalkingRouteGuidePoint(1, point(120.0), "직진하세요.", 120, 280, turnType = 11),
            WalkingRouteGuidePoint(2, point(300.0), "직진하세요.", 300, 100, turnType = 11),
        )))
        // 1 Hz walk from 0 to 50 m, last sample at 26 s.
        for ((index, eastM) in (0..50 step 2).withIndex()) {
            assertEquals(0, update(fix(eastM.toDouble(), 1_000L + index * 1_000L), 1_000L + index * 1_000L, false).guideIndex)
        }
        assertAccepted(this)
    }
    private fun assertAccepted(navigator: RouteNavigator) =
        assertTrue(navigator.currentRouteMatch()?.quality in setOf(RouteMatchQuality.HIGH, RouteMatchQuality.MEDIUM))
    private fun route(guides: List<WalkingRouteGuidePoint>) =
        WalkingRoute("TEST", WalkingRouteSummary(400, 400), listOf(point(0.0), point(400.0)), guides)
    private fun point(eastM: Double) = RoutePoint(0.0, eastM / 111_194.92664455874)
    private fun fix(eastM: Double, at: Long) = point(eastM).let { TrustedLocation(it.latitude, it.longitude, 5f, at) }
}

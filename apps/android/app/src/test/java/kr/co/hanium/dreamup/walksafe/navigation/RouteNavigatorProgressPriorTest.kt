package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteCorridorMatcher
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteCorridorMatcherConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 07.txt #15 (S07-15): the progress range comes from the measured time since the reference
 * progress, the pedestrian speed bound and the position uncertainty, not from a fixed amount per
 * update, so update frequency alone does not change the matched progress.
 */
class RouteNavigatorProgressPriorTest {
    private val metresPerDegree = 111_194.92664455874

    @Test
    fun theSameWalkGivesTheSameProgressWhetherSampledEverySecondOrOnceAfterALongGap() {
        // A hairpin: out 200 m east, 10 m north, back west. After 150 s the user is on the way back.
        val hairpin = listOf(p(0.0, 0.0), p(200.0, 0.0), p(200.0, 10.0), p(0.0, 10.0))
        val dense = matcher(timeScaled = true)
        dense.match("R", hairpin, fix(0.0, 0.0, 1_000L))
        var denseProgress = 0.0
        for (second in 1..150) {
            val walkedM = second * 2.4
            val (east, north) = if (walkedM <= 200.0) walkedM to 0.0 else if (walkedM <= 210.0) 200.0 to walkedM - 200.0
                else 410.0 - walkedM to 10.0
            denseProgress = requireNotNull(dense.match("R", hairpin, fix(east, north, 1_000L + second * 1_000L), denseProgress)
                .geometricProgressM)
        }
        assertEquals(360.0, denseProgress, 3.0)
        // Sparse: the last accepted progress (0 m) is 150 s old. The branch change still needs its
        // two confirmations, after which the late samples give the same progress as the dense walk.
        fun sparse(timeScaled: Boolean): Double {
            val matcher = matcher(timeScaled)
            matcher.match("R", hairpin, fix(0.0, 0.0, 1_000L))
            matcher.match("R", hairpin, fix(50.0, 10.0, 151_000L), 0.0, 1_000L)
            return requireNotNull(matcher.match("R", hairpin, fix(47.6, 10.0, 152_000L), 0.0, 1_000L).geometricProgressM)
        }
        assertEquals(denseProgress + 2.4, sparse(timeScaled = true), 3.0)
        // A fixed per-update prior keeps pulling the late samples back onto the outbound leg.
        val fixedAfterGap = sparse(timeScaled = false)
        assertTrue("fixed prior progress=$fixedAfterGap", fixedAfterGap < 100.0)
    }

    private fun matcher(timeScaled: Boolean) = RouteCorridorMatcher(RouteCorridorMatcherConfig(
        progressSigmaM = 80.0, allowReverseTravel = true, timeScaledProgressPrior = timeScaled,
    ))
    private fun p(eastM: Double, northM: Double) = RoutePoint(northM / metresPerDegree, eastM / metresPerDegree)
    private fun fix(eastM: Double, northM: Double, at: Long) = FilteredRoutePosition(p(eastM, northM), 5.0, at)
}

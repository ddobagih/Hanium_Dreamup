package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S08-5 (nav-F1): a precise sample withheld only because it is near a sector edge must not clear
 * the confirmed clock sector; otherwise the next sector would be spoken after one sample and a
 * hovering turn would alternate numbers sample by sample. Absent or imprecise samples still clear it.
 */
class RouteClockSectorBoundaryRegressionTest {
    // Route bearing 90 (east): facing 90 -> 12시, facing 60 -> 1시, facing 75 -> 15° edge band (no number).
    @Test
    fun anEdgeBandSampleKeepsTheConfirmedSectorSoTheChangeStillNeedsTwoFreshSamples() {
        val nav = navigator()
        assertEquals("12", clock(nav.update(fix(), 1_000, false, facingObservation = facing(1_000, 90.0)).instruction))
        assertEquals("edge band", "-", clock(nav.currentGuidance(1_100, facing(1_100, 75.0))?.instruction))
        assertEquals("first fresh sample of 1시", "-", clock(nav.currentGuidance(1_200, facing(1_200, 60.0))?.instruction))
        assertEquals("the same sample repeated", "-", clock(nav.currentGuidance(1_200, facing(1_200, 60.0))?.instruction))
        assertEquals("second fresh sample of 1시", "1", clock(nav.currentGuidance(1_300, facing(1_300, 60.0))?.instruction))
    }

    @Test
    fun aTurnHoveringAcrossTheEdgeDoesNotAlternateNumbersSampleBySample() {
        val nav = navigator()
        nav.update(fix(), 1_000, false, facingObservation = facing(1_000, 90.0))
        val spoken = listOf(75.0, 60.0, 75.0, 90.0, 75.0, 60.0, 60.0).mapIndexed { index, degrees ->
            val at = 1_100L + index * 100L
            clock(nav.currentGuidance(at, facing(at, degrees))?.instruction)
        }
        // 12 stays confirmed through edge samples; 1시 needs two consecutive fresh samples.
        assertEquals(listOf("-", "-", "-", "12", "-", "-", "1"), spoken)
    }

    @Test
    fun absentOrImpreciseSamplesStillClearTheSectorSoTheNextNumberIsImmediate() {
        for (lost in listOf<RouteFacingObservation?>(null, facing(1_100, 90.0, error = 20.0))) {
            val nav = navigator()
            assertEquals("12", clock(nav.update(fix(), 1_000, false, facingObservation = facing(1_000, 90.0)).instruction))
            assertEquals("lost=$lost", "-", clock(nav.currentGuidance(1_100, lost)?.instruction))
            assertEquals("lost=$lost", "1", clock(nav.currentGuidance(1_200, facing(1_200, 60.0))?.instruction))
        }
    }

    private fun clock(instruction: String?) = Regex("약 (\\d+)시 방향").find(instruction.orEmpty())?.groupValues?.get(1) ?: "-"
    private fun navigator() = RouteNavigator().apply {
        setRoute(WalkingRoute("TEST", WalkingRouteSummary(400, 400), listOf(point(0.0), point(400.0)),
            listOf(WalkingRouteGuidePoint(0, point(200.0), null, 200, 200, turnType = 13))))
    }
    private fun point(eastM: Double) = RoutePoint(0.0, eastM / 111_194.92664455874)
    private fun fix() = point(50.0).let { TrustedLocation(it.latitude, it.longitude, 2f, 1_000) }
    private fun facing(at: Long, degrees: Double, error: Double = 3.0) = RouteFacingObservation(degrees, error, at)
}

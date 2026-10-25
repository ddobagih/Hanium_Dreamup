package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.*
import org.junit.Test

/** One representative clock; repeated rendering cannot stand in for another sensor measurement. */
class RouteClockSectorBoundaryRegressionTest {
    @Test fun aNominalEdgeClockNeedsTwoDistinctFreshSamples() {
        val nav = navigator()
        val original = nav.update(fix(), 1_000L, false, facingObservation = facing(1_000L, 90.0))
        assertEquals("12", clock(original.instruction))
        assertEquals("12", clock(nav.currentGuidance(1_100L, facing(1_100L, 75.0))?.instruction))
        assertEquals("same sample cannot confirm", "12", clock(nav.currentGuidance(1_150L, facing(1_100L, 75.0))?.instruction))
        val confirmed = requireNotNull(nav.currentGuidance(1_200L, facing(1_200L, 75.0)))
        assertEquals("1", clock(confirmed.instruction))
        assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK, confirmed.facingDiagnostic?.clockReferenceKind)
        assertNoRange(confirmed.instruction)
        assertEquals(1_000L, original.speechCueToken?.facingObservedAtElapsedRealtimeMs)
        assertEquals(1_500L, original.speechCueToken?.facingValidUntilElapsedRealtimeMs)
    }

    @Test fun actualWalkingSwayAcrossPrecisionEdgesKeepsOneRepresentativeClock() {
        val nav = navigator()
        val trend = RouteHeadingTrend()
        val first = facing(1_000L, 90.0)
        nav.update(fix(), 1_000L, false, facingObservation = first.copy(headingTrend = trend.observe(first, 1_000L)))
        val spoken = listOf(82.0, 98.0, 82.0, 98.0, 82.0, 98.0, 82.0).mapIndexed { index, degrees ->
            val at = 1_100L + index * 100L
            val raw = facing(at, degrees, error = 12.0)
            val sample = raw.copy(headingTrend = trend.observe(raw, at))
            assertEquals(degrees, sample.degreesTrueNorth, 0.0)
            assertEquals(12.0, sample.headingAccuracyDegrees!!, 0.0)
            assertEquals(at, sample.observedAtElapsedRealtimeMs)
            val cue = nav.currentGuidance(at, sample)
            assertNoRange(cue?.instruction)
            clock(cue?.instruction)
        }
        assertEquals(listOf("12", "12", "12", "12", "12", "12", "12"), spoken)
        assertNotNull(nav.currentAcceptedRouteMatchFor(1_000L))
        assertNull(nav.currentAcceptedRouteMatchFor(1_700L))
    }

    @Test fun absentOrUnconfirmedNominalSamplesNeverRenewTheOldObservationLease() {
        for (lost in listOf<RouteFacingObservation?>(null, facing(1_100L, 90.0, error = 20.0))) {
            val nav = navigator()
            val original = nav.update(fix(), 1_000L, false, facingObservation = facing(1_000L, 90.0))
            assertEquals("12", clock(original.instruction))
            assertEquals("lost=$lost", "12", clock(nav.currentGuidance(1_100L, lost)?.instruction))
            assertEquals("lost=$lost", "1", clock(nav.currentGuidance(1_200L, facing(1_200L, 60.0))?.instruction))
            assertEquals(1_000L, original.speechCueToken?.facingObservedAtElapsedRealtimeMs)
            assertEquals(1_500L, original.speechCueToken?.facingValidUntilElapsedRealtimeMs)
        }
    }

    private fun assertNoRange(text: String?) { assertFalse(text.orEmpty().contains("시에서") || text.orEmpty().contains("사이 방향")) }
    private fun clock(instruction: String?) = Regex("약 (\\d+)시 방향").find(instruction.orEmpty())?.groupValues?.get(1) ?: "-"
    private fun navigator() = RouteNavigator().apply {
        setRoute(WalkingRoute("TEST", WalkingRouteSummary(400, 400), listOf(point(0.0), point(400.0)),
            listOf(WalkingRouteGuidePoint(0, point(200.0), null, 200, 200, turnType = 13))))
    }
    private fun point(eastM: Double) = RoutePoint(0.0, eastM / 111_194.92664455874)
    private fun fix() = point(50.0).let { TrustedLocation(it.latitude, it.longitude, 2f, 1_000L) }
    private fun facing(at: Long, degrees: Double, error: Double = 3.0) = RouteFacingObservation(degrees, error, at)
}

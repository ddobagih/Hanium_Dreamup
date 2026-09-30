package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 07.txt #23: step progress is an auxiliary input. When steps show the user did not walk but
 * the GNSS route progress jumps far, the jump alone must not confirm guide passage or arrival.
 */
class StepProgressStationaryJumpRegressionTest {
    private val north = 1.0 / 111_320.0
    private val facing = { at: Long -> RouteFacingObservation(0.0, 5.0, at) }

    @Test
    fun stationaryStepsHoldAGuidePassageClaimedOnlyByAGnssJump() {
        val navigator = navigator()
        assertEquals(0, update(navigator, 0.0, 1_000L, 0.0).guideIndex)
        assertEquals(0, update(navigator, 1.0, 2_000L, 0.7).guideIndex)
        val jumped = update(navigator, 40.0, 3_000L, 0.7)
        assertEquals("steps say stationary; the 39 m jump must not consume the guide", 0, jumped.guideIndex)
        assertEquals(false, jumped.stepProgressConsistent)
    }

    @Test
    fun walkedStepsOrMissingStepEvidenceKeepTheGnssProgress() {
        listOf<Double?>(40.0, null).forEach { steps ->
            val navigator = navigator()
            update(navigator, 0.0, 1_000L, steps?.let { 0.0 })
            update(navigator, 1.0, 2_000L, steps?.let { 0.7 })
            val moved = update(navigator, 40.0, 3_000L, steps)
            assertNotEquals("steps=$steps", 0, moved.guideIndex)
        }
    }

    @Test
    fun stationaryJumpToTheRouteEndDoesNotCountAsAnArrivalSample() {
        val navigator = RouteNavigator().apply { setRoute(route(emptyList())) }
        update(navigator, 150.0, 1_000L, 150.0)
        update(navigator, 150.5, 2_000L, 150.0)
        val jumped = update(navigator, 200.0, 3_000L, 150.0)
        assertFalse(jumped.arrivalCandidate)
        val stillThere = update(navigator, 200.0, 4_000L, 150.0)
        assertFalse("the jump sample itself is not arrival evidence", stillThere.arrivalCandidate)
        // S07-23: a persisting jumped position with no steps stays held, not only the jump sample.
        for (at in 5_000L..13_000L step 1_000L) {
            assertFalse("held at $at", update(navigator, 200.0, at, 150.0).arrivalCandidate)
        }
        // R5 (07.txt:231): the 10 s hold expired but the steps still show no walking since the jump,
        // so arrival stays held and the user is asked to recheck the location instead.
        val expired = update(navigator, 200.0, 14_000L, 150.0)
        assertFalse(expired.arrivalCandidate)
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, expired.pendingUserDecision)
        assertFalse(update(navigator, 200.0, 15_000L, 150.0).arrivalCandidate)
        // After the user's recheck GNSS progress counts again, and two samples still form the candidate.
        assertEquals("off_route_location_recheck_requested",
            navigator.selectDeviationChoice(RouteDeviationChoice.RECHECK_LOCATION).reason)
        // S07-32 (R6): the samples must also span 3 s before the question.
        for (at in 16_000L..18_000L step 1_000L) assertFalse(update(navigator, 200.0, at, 150.0).arrivalCandidate)
        assertTrue(update(navigator, 200.0, 19_000L, 150.0).arrivalCandidate)
    }

    /** S07-23: the hold is anchored before the jump, so a persisting jump cannot consume the guide next sample. */
    @Test
    fun aPersistingStationaryJumpStaysHeldUntilStepsExplainIt() {
        val navigator = navigator()
        update(navigator, 0.0, 1_000L, 0.0)
        update(navigator, 1.0, 2_000L, 0.7)
        for ((at, steps) in listOf(3_000L to 0.7, 4_000L to 0.7, 5_000L to 1.4, 6_000L to 1.4)) {
            val held = update(navigator, 40.0, at, steps)
            assertEquals("at=$at one step does not explain a 39 m jump", 0, held.guideIndex)
            assertEquals(false, held.stepProgressConsistent)
        }
        // The steps catch up with the claimed progress: the position is used again.
        assertNotEquals(0, update(navigator, 40.0, 7_000L, 40.0).guideIndex)
    }

    @Test
    fun theHoldEndsAfterTenSecondsOrWhenStepEvidenceDisappears() {
        val expired = navigator()
        update(expired, 0.0, 1_000L, 0.0)
        update(expired, 1.0, 2_000L, 0.7)
        for (at in 3_000L..13_000L step 2_000L) assertEquals("at=$at", 0, update(expired, 40.0, at, 0.7).guideIndex)
        assertNotEquals(0, update(expired, 40.0, 13_500L, 0.7).guideIndex)
        val stepsLost = navigator()
        update(stepsLost, 0.0, 1_000L, 0.0)
        update(stepsLost, 1.0, 2_000L, 0.7)
        assertEquals(0, update(stepsLost, 40.0, 3_000L, 0.7).guideIndex)
        assertNotEquals("no step evidence keeps GNSS-only behaviour", 0, update(stepsLost, 40.0, 4_000L, null).guideIndex)
    }

    @Test
    fun withoutStepsTheArrivalStillUsesTwoGnssSamples() {
        val navigator = RouteNavigator().apply { setRoute(route(emptyList())) }
        update(navigator, 150.0, 1_000L, null)
        update(navigator, 150.5, 2_000L, null)
        // S07-32 (R6): the samples at the end must also span 3 s before the question.
        for (at in 3_000L..5_000L step 1_000L) assertFalse(update(navigator, 200.0, at, null).arrivalCandidate)
        assertTrue(update(navigator, 200.0, 6_000L, null).arrivalCandidate)
    }

    private fun update(navigator: RouteNavigator, northM: Double, at: Long, steps: Double?) =
        navigator.update(TrustedLocation(37.0 + northM * north, 127.0, 3f, at), at, false, steps, facing(at))

    private fun navigator() = RouteNavigator().apply {
        setRoute(route(listOf(
            WalkingRouteGuidePoint(0, RoutePoint(37.0 + 20 * north, 127.0), "직진하세요.", 20, 180, turnType = 11),
            WalkingRouteGuidePoint(1, RoutePoint(37.0 + 150 * north, 127.0), "직진하세요.", 150, 50, turnType = 11),
        )))
    }

    private fun route(guides: List<WalkingRouteGuidePoint>) = WalkingRoute(
        priority = "TEST",
        summary = WalkingRouteSummary(200, 200),
        polyline = listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0 + 200 * north, 127.0)),
        guidePoints = guides,
    )
}

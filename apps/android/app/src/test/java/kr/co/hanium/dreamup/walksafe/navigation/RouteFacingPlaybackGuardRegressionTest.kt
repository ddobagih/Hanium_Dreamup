package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S08-25: the playback-start guard re-evaluates facing without rejecting a still-true coarse cue. */
class RouteFacingPlaybackGuardRegressionTest {
    @Test fun coarseCueStaysPlayableWhenTheClockSectorIsConfirmedBeforePlayback() {
        val navigator = navigator()
        // S08-5: a precise first sample already carries its clock number, so the coarse cue comes
        // from a sample whose sensor error cannot support one.
        assertTrue(navigator().update(fix(1_000L), 1_000L, false, facingObservation = facing(1_000L))
            .instruction.orEmpty().contains("약 12시 방향"))
        val offered = navigator.update(fix(1_000L), 1_000L, false, facingObservation = imprecise(1_000L))
        assertFalse(offered.instruction.orEmpty().contains("시 방향"))
        assertTrue(offered.instruction.orEmpty().contains("앞쪽이 경로 진행 방향"))
        assertTrue(navigator.reserveInstruction(offered))
        // The next precise sample supports 12 o'clock; "앞쪽" is still true at playback start.
        assertTrue(navigator.isInstructionCurrent(offered, 1_050L, facing(1_050L)))
    }

    @Test fun numericCueIsStillRevokedWhenDirectionPrecisionOrFreshnessIsLost() {
        val navigator = navigator()
        navigator.update(fix(1_000L), 1_000L, false, facingObservation = facing(1_000L))
        val numeric = requireNotNull(navigator.currentGuidance(1_001L, facing(1_001L)))
        assertTrue(numeric.instruction.orEmpty().contains("약 12시 방향"))
        assertTrue(navigator.isInstructionCurrent(numeric, 1_002L, facing(1_002L)))
        assertFalse(navigator.isInstructionCurrent(numeric, 1_003L, facing(1_003L, 270.0)))
        assertFalse(navigator.isInstructionCurrent(numeric, 1_004L, facing(1_004L).copy(headingAccuracyDegrees = 20.0)))
        assertFalse(navigator.isInstructionCurrent(numeric, 1_600L, facing(1_004L)))
        assertFalse(navigator.isInstructionCurrent(numeric, 1_601L, null))
    }

    @Test fun coarseCueIsRevokedWhenTheCoarseDirectionItselfChanges() {
        val navigator = navigator()
        val offered = navigator.update(fix(1_000L), 1_000L, false, facingObservation = imprecise(1_000L))
        assertFalse(offered.instruction.orEmpty().contains("시 방향"))
        assertFalse(navigator.isInstructionCurrent(offered, 1_050L, facing(1_050L, 270.0)))
        assertFalse(navigator.isInstructionCurrent(offered, 1_100L, facing(1_100L, 0.0)))
    }

    private fun navigator() = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0L)).apply { setRoute(route()) }
    private fun route() = WalkingRoute("STAIR_AVOID", WalkingRouteSummary(89, 90),
        listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0, 127.001)),
        listOf(WalkingRouteGuidePoint(0, RoutePoint(37.0, 127.0009), "우회전", 80, 9, turnType = 13)))
    private fun fix(at: Long) = TrustedLocation(37.0, 127.0, 1f, at)
    private fun facing(at: Long, degrees: Double = 90.0) = RouteFacingObservation(degrees, 3.0, at)
    private fun imprecise(at: Long) = facing(at).copy(headingAccuracyDegrees = 20.0)
}

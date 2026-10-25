package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Playback rechecks the actual spoken claim: precise clock, or a non-action direction recheck. */
class RouteFacingPlaybackGuardRegressionTest {
    // This suite isolates START validation after a recheck cue has already been offered.
    // Production 1.5-second delay and immediate safety actions are covered by RouteDirectionRecheckGraceTest.

    @Test fun recheckCueIsRevokedWhenTheClockSectorIsConfirmedBeforePlayback() {
        val navigator = navigator()
        // Four-sector RV support prevents both numeric forms without changing the source.
        assertTrue(navigator().update(fix(1_000L), 1_000L, false, facingObservation = facing(1_000L))
            .instruction.orEmpty().contains("약 12시 방향"))
        val offered = navigator.update(fix(1_000L), 1_000L, false, facingObservation = imprecise(1_000L))
        assertFalse(offered.instruction.orEmpty().contains("카메라 기준 약"))
        assertTrue(offered.instruction.orEmpty().contains("시계 방향을 확정하기 어렵습니다"))
        assertTrue(offered.instruction.orEmpty().contains("방향이 확인되면 안내를 이어갑니다"))
        assertFalse(requireNotNull(offered.speechCueToken).requiresFacingEvidence)
        assertTrue(navigator.reserveInstruction(offered))
        assertTrue(navigator.isInstructionCurrent(offered, 1_020L, imprecise(1_020L)))
        // A precise 12 o'clock is now available, so the queued request to recheck is obsolete.
        assertFalse(navigator.isInstructionCurrent(offered, 1_050L, facing(1_050L)))
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

    @Test fun recheckCueIsRevokedWhenTheMeasuredDirectionContextChanges() {
        val navigator = navigator()
        val offered = navigator.update(fix(1_000L), 1_000L, false, facingObservation = imprecise(1_000L))
        assertFalse(offered.instruction.orEmpty().contains("카메라 기준 약"))
        assertFalse(navigator.isInstructionCurrent(offered, 1_050L, facing(1_050L, 270.0)))
        assertFalse(navigator.isInstructionCurrent(offered, 1_100L, facing(1_100L, 0.0)))
    }

    private fun navigator() = RouteNavigator(RouteNavigatorConfig(directionRecheckGraceMs = 0L, guidanceIntervalMs = 0L)).apply { setRoute(route()) }
    private fun route() = WalkingRoute("STAIR_AVOID", WalkingRouteSummary(89, 90),
        listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0, 127.001)),
        listOf(WalkingRouteGuidePoint(0, RoutePoint(37.0, 127.0009), "우회전", 80, 9, turnType = 13)))
    private fun fix(at: Long) = TrustedLocation(37.0, 127.0, 1f, at)
    private fun facing(at: Long, degrees: Double = 90.0) = RouteFacingObservation(degrees, 3.0, at)
    private fun imprecise(at: Long) = RouteFacingObservation(75.0, 30.0, at)
}

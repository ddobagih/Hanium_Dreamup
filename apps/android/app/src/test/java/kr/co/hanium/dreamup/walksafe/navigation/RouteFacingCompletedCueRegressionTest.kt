package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Completion distinguishes a non-action recheck from a delivered clock direction. */
class RouteFacingCompletedCueRegressionTest {
    // This suite isolates DONE coverage and the unchanged minimum/periodic speech cadence.
    // Production 1.5-second delay and immediate safety actions are covered by RouteDirectionRecheckGraceTest.

    @Test fun aCompletedRecheckCannotConsumeImmediateRecoveryOrTheFollowingDirectionCadence() {
        val navigator = navigator()
        // This accepted RV error spans four sectors and cannot support a numeric reference.
        val coarse = navigator.update(fix(1_000L), 1_000L, false,
            facingObservation = RouteFacingObservation(75.0, 30.0, 1_000L))
        assertFalse(coarse.instruction.orEmpty().contains("바라보고 있는 방향 기준 약"))
        assertTrue(coarse.instruction.orEmpty().contains("경로 기준 3시 방향 회전이 있습니다"))
        assertTrue(navigator.reserveInstruction(coarse))
        navigator.acknowledgeInstruction(coarse, 4_000L)
        // An explanation DONE is not a delivered direction. The first actual direction recovers
        // immediately; the original six/ten-second cadence begins at that direction's own DONE.
        val recovered = navigator.update(fix(4_100L), 4_100L, false, facingObservation = facing(4_100L))
        assertTrue(recovered.instruction.orEmpty().contains("약 12시 방향"))
        assertTrue(navigator.reserveInstruction(recovered))
        assertTrue(navigator.isInstructionCurrent(recovered, 4_100L, facing(4_100L)))
        navigator.acknowledgeInstruction(coarse, 4_101L)
        navigator.releaseInstruction(coarse, 4_102L)
        assertEquals("guidance_in_flight", navigator.retryGuidance(4_103L, facing(4_103L))?.reason)
        navigator.acknowledgeInstruction(recovered, 5_000L)
        val early = navigator.update(fix(10_999L), 10_999L, false, facingObservation = facing(10_999L))
        assertNull(early.instruction)
        assertEquals("guidance_rate_limited", early.reason)
        val repeat = requireNotNull(navigator.retryGuidance(11_000L, facing(11_000L)))
        assertNull(repeat.instruction)
        assertEquals("guidance_already_completed", repeat.reason)
        assertNull(navigator.retryGuidance(14_999L, facing(14_999L))?.instruction)
        val periodic = requireNotNull(navigator.retryGuidance(15_000L, facing(15_000L)))
        assertTrue(periodic.instruction.orEmpty().contains("약 12시 방향"))
        assertTrue(navigator.reserveInstruction(periodic))
    }

    @Test fun sameCompletedClockDeduplicatesNominalPrecisionLossUntilThePeriodicDeadline() {
        val navigator = navigator()
        navigator.update(fix(1_000L), 1_000L, false, facingObservation = facing(1_000L))
        val numeric = requireNotNull(navigator.currentGuidance(1_001L, facing(1_001L)))
        assertTrue(numeric.instruction.orEmpty().contains("약 12시 방향"))
        assertTrue(navigator.reserveInstruction(numeric))
        navigator.acknowledgeInstruction(numeric, 2_000L)
        navigator.update(fix(8_499L), 8_499L, false,
            facingObservation = facing(8_499L).copy(headingAccuracyDegrees = 20.0))
        val early = navigator.update(fix(8_500L), 8_500L, false,
            facingObservation = facing(8_500L).copy(headingAccuracyDegrees = 20.0))
        assertNull(early.instruction)
        assertEquals("guidance_already_completed", early.reason)
        val coarse = requireNotNull(navigator.retryGuidance(12_000L,
            facing(12_000L).copy(headingAccuracyDegrees = 20.0)))
        assertTrue(coarse.instruction.orEmpty().contains("바라보고 있는 방향 기준 약 12시 방향"))
        assertFalse(coarse.instruction.orEmpty().contains("시에서") || coarse.instruction.orEmpty().contains("사이 방향"))
        assertEquals(RouteFacingExpression.CLOCK, coarse.facingDiagnostic?.expression)
        assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK, coarse.facingDiagnostic?.clockReferenceKind)
        assertTrue(requireNotNull(coarse.speechCueToken).requiresFacingEvidence)
        assertTrue(navigator.reserveInstruction(coarse))
        navigator.acknowledgeInstruction(coarse, 12_100L)
        assertNull(navigator.retryGuidance(12_101L, facing(12_101L).copy(headingAccuracyDegrees = 20.0))?.instruction)
    }

    @Test fun meaningfulFacingChangesStillProduceANewCueBeforeThePeriodicDeadline() {
        val navigator = navigator()
        val front = navigator.update(fix(1_000L), 1_000L, false, facingObservation = facing(1_000L))
        assertTrue(navigator.reserveInstruction(front))
        navigator.acknowledgeInstruction(front, 2_000L)
        val changing = requireNotNull(navigator.update(fix(8_500L), 8_500L, false,
            facingObservation = facing(8_500L, 270.0)).instruction)
        assertTrue(changing.contains("방향이 확인되면 안내를 이어갑니다"))
        assertFalse(changing.contains("몸을 돌려주세요"))
        val behind = requireNotNull(navigator.currentGuidance(8_501L, facing(8_501L, 270.0))?.instruction)
        assertTrue(behind.contains("몸을 돌려주세요"))
        assertFalse(behind.contains("꺾으세요"))

        val unknown = navigator()
        val missing = unknown.update(fix(1_000L), 1_000L, false, facingObservation = null)
        assertTrue(unknown.reserveInstruction(missing))
        unknown.acknowledgeInstruction(missing, 2_000L)
        val recovered = requireNotNull(unknown.update(fix(8_500L), 8_500L, false,
            facingObservation = facing(8_500L)).instruction)
        assertTrue(recovered.contains("약 12시 방향"))
    }

    private fun navigator() = RouteNavigator(RouteNavigatorConfig(directionRecheckGraceMs = 0L)).apply { setRoute(route()) }
    private fun route() = WalkingRoute("STAIR_AVOID", WalkingRouteSummary(89, 90),
        listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0, 127.001)),
        listOf(WalkingRouteGuidePoint(0, RoutePoint(37.0, 127.0009), "우회전", 80, 9, turnType = 13)))
    private fun fix(at: Long) = TrustedLocation(37.0, 127.0, 1f, at)
    private fun facing(at: Long, degrees: Double = 90.0) = RouteFacingObservation(degrees, 3.0, at)
}

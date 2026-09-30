package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S08-24: completed-cue dedupe reacts to meaningful facing changes, not to clock availability alone. */
class RouteFacingCompletedCueRegressionTest {
    @Test fun clockConfirmationAloneDoesNotBreakTheTenSecondCadence() {
        val navigator = navigator()
        // S08-5: a precise first sample is spoken with its clock number at once, so the coarse cue
        // here comes from a sample whose sensor error cannot support a number.
        val coarse = navigator.update(fix(1_000L), 1_000L, false,
            facingObservation = facing(1_000L).copy(headingAccuracyDegrees = 20.0))
        assertFalse(coarse.instruction.orEmpty().contains("시 방향"))
        assertTrue(navigator.reserveInstruction(coarse))
        navigator.acknowledgeInstruction(coarse, 4_000L)
        // A precise sample now supports 12 o'clock, but the user is still facing the same way.
        val early = navigator.update(fix(13_999L), 13_999L, false, facingObservation = facing(13_999L))
        assertNull(early.instruction)
        assertEquals("guidance_already_completed", early.reason)
        val periodic = requireNotNull(navigator.retryGuidance(14_000L, facing(14_000L)))
        assertTrue(periodic.instruction.orEmpty().contains("약 12시 방향"))
    }

    @Test fun precisionLossAfterANumericCueIsNotANewAnnouncement() {
        val navigator = navigator()
        navigator.update(fix(1_000L), 1_000L, false, facingObservation = facing(1_000L))
        val numeric = requireNotNull(navigator.currentGuidance(1_001L, facing(1_001L)))
        assertTrue(numeric.instruction.orEmpty().contains("약 12시 방향"))
        assertTrue(navigator.reserveInstruction(numeric))
        navigator.acknowledgeInstruction(numeric, 2_000L)
        val coarse = navigator.update(fix(8_500L), 8_500L, false,
            facingObservation = facing(8_500L).copy(headingAccuracyDegrees = 20.0))
        assertNull(coarse.instruction)
        assertEquals("guidance_already_completed", coarse.reason)
        assertNotNull(navigator.retryGuidance(12_000L, facing(12_000L).copy(headingAccuracyDegrees = 20.0))?.instruction)
    }

    @Test fun meaningfulFacingChangesStillProduceANewCueBeforeThePeriodicDeadline() {
        val navigator = navigator()
        val front = navigator.update(fix(1_000L), 1_000L, false, facingObservation = facing(1_000L))
        assertTrue(navigator.reserveInstruction(front))
        navigator.acknowledgeInstruction(front, 2_000L)
        val behind = requireNotNull(navigator.update(fix(8_500L), 8_500L, false,
            facingObservation = facing(8_500L, 270.0)).instruction)
        assertTrue(behind.contains("몸을 돌리세요"))
        assertFalse(behind.contains("꺾으세요"))

        val unknown = navigator()
        val missing = unknown.update(fix(1_000L), 1_000L, false, facingObservation = null)
        assertTrue(unknown.reserveInstruction(missing))
        unknown.acknowledgeInstruction(missing, 2_000L)
        val recovered = requireNotNull(unknown.update(fix(8_500L), 8_500L, false,
            facingObservation = facing(8_500L)).instruction)
        assertTrue(recovered.contains("앞쪽이 경로 진행 방향"))
    }

    private fun navigator() = RouteNavigator().apply { setRoute(route()) }
    private fun route() = WalkingRoute("STAIR_AVOID", WalkingRouteSummary(89, 90),
        listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0, 127.001)),
        listOf(WalkingRouteGuidePoint(0, RoutePoint(37.0, 127.0009), "우회전", 80, 9, turnType = 13)))
    private fun fix(at: Long) = TrustedLocation(37.0, 127.0, 1f, at)
    private fun facing(at: Long, degrees: Double = 90.0) = RouteFacingObservation(degrees, 3.0, at)
}

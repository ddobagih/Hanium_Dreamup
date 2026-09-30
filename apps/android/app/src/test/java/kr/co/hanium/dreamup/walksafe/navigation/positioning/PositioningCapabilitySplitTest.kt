package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S09-34 (originals/09.txt:94): "destination arrival cannot be confirmed" and "how far the user just
 * walked can still be estimated" are different capabilities of one positioning snapshot.
 */
class PositioningCapabilitySplitTest {
    @Test
    fun gnssGapRemovesAbsoluteCapabilityButFreshStepsKeepRelativeMotion() {
        val coordinator = PositioningCoordinator()
        coordinator.observeGnss(fix(1_000L))
        val fresh = coordinator.observeStep(1, heading(1_500L), PdrStepQuality.HIGH)
        assertTrue(fresh.absoluteArrivalConfirmable)
        assertTrue(fresh.relativeMotionEstimable)

        for (at in 2_000L..11_500L step 500L) coordinator.observeStep(1, heading(at), PdrStepQuality.HIGH)
        val gap = coordinator.evaluate(11_600L)
        assertEquals(PositionQuality.UNAVAILABLE, gap.quality)
        assertTrue(gap.confidence.guidancePaused)
        assertFalse(gap.absoluteArrivalConfirmable)
        assertTrue(gap.relativeMotionEstimable)

        // Steps stop as well: neither capability remains.
        val silent = coordinator.evaluate(13_600L)
        assertFalse(silent.absoluteArrivalConfirmable)
        assertFalse(silent.relativeMotionEstimable)
    }

    @Test
    fun lowQualityStepsOrNoFilterNeverClaimRelativeMotion() {
        val uninitialized = PositioningCoordinator()
        val noFilter = uninitialized.observeStep(1, heading(1_000L), PdrStepQuality.HIGH)
        assertFalse(noFilter.relativeMotionEstimable)
        assertFalse(noFilter.absoluteArrivalConfirmable)

        val coordinator = PositioningCoordinator()
        coordinator.observeGnss(fix(1_000L))
        val low = coordinator.observeStep(1, heading(1_500L), PdrStepQuality.LOW)
        assertFalse(low.relativeMotionEstimable)
        assertTrue(low.absoluteArrivalConfirmable)
    }

    private fun fix(at: Long) = GnssPositionObservation(37.0, 127.0, 1.5, at)

    private fun heading(at: Long) = PedestrianHeadingInput(
        at, WalkingSpeedObservation(1.0, 0.1, at), HeadingObservation(90.0, 5.0, at), null, false,
    )
}

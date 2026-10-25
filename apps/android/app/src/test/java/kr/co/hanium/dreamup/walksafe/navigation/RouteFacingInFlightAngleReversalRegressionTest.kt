package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S08-23 (nav-F2): an in-flight sentence finishes through ordinary turns, but a fresh usable facing
 * that reverses its command's meaning revokes and regenerates it: an "ahead" cue once the route is
 * more than 90° plus the sector margin off either side, a left/right cue once the route is beyond
 * the margin on the other side. Route bearing 90 (east), 5° sensor error -> 10° margin.
 */
class RouteFacingInFlightAngleReversalRegressionTest {
    @Test
    fun anAheadCueIsRevokedOnceTheRouteIsFarMoreThanARightAngleOff() {
        for (relative in listOf(120.0, 140.0, -120.0)) {
            val nav = navigator()
            val ahead = nav.update(fix(), 1_000, false, facingObservation = facing(1_000, 0.0))
            assertTrue(ahead.instruction?.contains("약 12시 방향") == true)
            assertTrue(nav.reserveInstruction(ahead))
            val observation = facing(1_100, relative)
            val turned = requireNotNull(nav.retryGuidance(1_100, observation))
            assertTrue("relative=$relative", turned.cancelStaleNavigationSpeech)
            // A cold nominal replacement needs a distinct second actual observation; range speech is forbidden.
            assertEquals("relative=$relative", "guidance_facing_recheck_wait", turned.reason)
            assertEquals(null, turned.instruction)
            assertFalse(nav.reserveInstruction(turned))
            if (relative == 140.0) {
                val nextObservation = facing(1_200L, relative)
                val confirmed = requireNotNull(nav.retryGuidance(1_200L, nextObservation))
                assertTrue(confirmed.instruction.orEmpty().contains("약 5시 방향"))
                assertFalse(confirmed.instruction.orEmpty().contains("사이 방향"))
                assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK, confirmed.facingDiagnostic?.clockReferenceKind)
                assertTrue(nav.reserveInstruction(confirmed))
                assertTrue(nav.isInstructionCurrent(confirmed, 1_700L, nextObservation))
                assertFalse(nav.isInstructionCurrent(confirmed, 1_701L, nextObservation))
            }
            assertFalse(turned.instruction?.contains("약 12시 방향") == true)
            assertFalse(nav.reserveInstruction(ahead))
        }
    }

    @Test
    fun anAheadCueFinishesThroughSixtyAndNinetyDegreeTurns() {
        for (relative in listOf(60.0, 90.0, -60.0, -90.0)) {
            val nav = navigator()
            val ahead = nav.update(fix(), 1_000, false, facingObservation = facing(1_000, 0.0))
            assertTrue(nav.reserveInstruction(ahead))
            val turned = requireNotNull(nav.retryGuidance(1_100, facing(1_100, relative)))
            assertEquals("relative=$relative", "guidance_in_flight", turned.reason)
            assertFalse("relative=$relative", turned.cancelStaleNavigationSpeech)
        }
    }

    @Test
    fun aSideCueIsRevokedOnlyWhenTheRouteIsBeyondTheMarginOnTheOtherSide() {
        for ((offered, flipped, kept) in listOf(Triple(-90.0, 30.0, 5.0), Triple(90.0, -30.0, -5.0))) {
            val nav = navigator()
            val side = nav.update(fix(), 1_000, false, facingObservation = facing(1_000, offered))
            assertTrue(side.instruction?.contains(if (offered < 0) "약 9시 방향" else "약 3시 방향") == true)
            assertTrue(nav.reserveInstruction(side))
            val withinMargin = requireNotNull(nav.retryGuidance(1_100, facing(1_100, kept)))
            assertEquals("offered=$offered kept=$kept", "guidance_in_flight", withinMargin.reason)
            assertFalse(withinMargin.cancelStaleNavigationSpeech)
            val reversed = requireNotNull(nav.retryGuidance(1_200, facing(1_200, flipped)))
            assertTrue("offered=$offered flipped=$flipped", reversed.cancelStaleNavigationSpeech)
            assertEquals("route_guidance", reversed.reason)
            val hour = if (flipped > 0.0) 1 else 11
            assertTrue(reversed.instruction.orEmpty().contains("약 ${hour}시 방향"))
            assertFalse(reversed.instruction.orEmpty().contains("사이 방향"))
            assertEquals(RouteClockReferenceKind.REPORTED_ERROR, reversed.facingDiagnostic?.clockReferenceKind)
            assertFalse(nav.reserveInstruction(side))
            assertTrue(nav.reserveInstruction(reversed))
            assertTrue(nav.isInstructionCurrent(reversed, 1_700L, facing(1_200L, flipped)))
            assertFalse(nav.isInstructionCurrent(reversed, 1_701L, facing(1_200L, flipped)))
        }
    }

    private fun navigator() = RouteNavigator().apply {
        setRoute(WalkingRoute("TEST", WalkingRouteSummary(400, 400),
            listOf(point(0.0), point(400.0)),
            listOf(WalkingRouteGuidePoint(0, point(200.0), null, 200, 200, turnType = 13))))
    }
    private fun point(eastM: Double) = RoutePoint(0.0, eastM / 111_194.92664455874)
    private fun fix() = point(50.0).let { TrustedLocation(it.latitude, it.longitude, 2f, 1_000) }
    /** [relativeDegrees] is the route's signed angle from the rear camera (positive = right). */
    private fun facing(nowMs: Long, relativeDegrees: Double) =
        RouteFacingObservation((90.0 - relativeDegrees + 360.0) % 360.0, 5.0, nowMs)
}

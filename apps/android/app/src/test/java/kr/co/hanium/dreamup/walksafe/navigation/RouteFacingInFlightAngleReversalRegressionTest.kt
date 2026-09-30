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
        for ((relative, side) in listOf(120.0 to "경로는 오른쪽입니다", 140.0 to "경로는 오른쪽입니다", -120.0 to "경로는 왼쪽입니다")) {
            val nav = navigator()
            val ahead = nav.update(fix(), 1_000, false, facingObservation = facing(1_000, 0.0))
            assertTrue(ahead.instruction?.contains("앞쪽이 경로 진행 방향입니다") == true)
            assertTrue(nav.reserveInstruction(ahead))
            val turned = requireNotNull(nav.retryGuidance(1_100, facing(1_100, relative)))
            assertEquals("relative=$relative", "route_guidance", turned.reason)
            assertTrue("relative=$relative", turned.cancelStaleNavigationSpeech)
            assertTrue(turned.instruction, turned.instruction?.contains(side) == true)
            assertFalse(turned.instruction?.contains("앞쪽이 경로 진행 방향입니다") == true)
            assertFalse(nav.reserveInstruction(ahead))
            assertTrue(nav.reserveInstruction(turned))
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
            assertTrue(side.instruction?.contains(if (offered < 0) "경로는 왼쪽입니다" else "경로는 오른쪽입니다") == true)
            assertTrue(nav.reserveInstruction(side))
            val withinMargin = requireNotNull(nav.retryGuidance(1_100, facing(1_100, kept)))
            assertEquals("offered=$offered kept=$kept", "guidance_in_flight", withinMargin.reason)
            assertFalse(withinMargin.cancelStaleNavigationSpeech)
            val reversed = requireNotNull(nav.retryGuidance(1_200, facing(1_200, flipped)))
            assertTrue("offered=$offered flipped=$flipped", reversed.cancelStaleNavigationSpeech)
            assertTrue(reversed.instruction, reversed.instruction?.contains("앞쪽이 경로 진행 방향입니다") == true)
            assertFalse(nav.reserveInstruction(side))
            assertTrue(nav.reserveInstruction(reversed))
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

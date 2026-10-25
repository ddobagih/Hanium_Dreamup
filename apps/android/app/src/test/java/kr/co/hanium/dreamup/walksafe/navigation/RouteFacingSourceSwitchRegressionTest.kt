package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 08.txt #14: after a primary/fallback compass switch, the new source must show fresh continuity
 * before a turn command. The earlier source is comparison evidence only, never the current facing.
 */
class RouteFacingSourceSwitchRegressionTest {
    // This suite isolates source/sample confirmation, not the recheck announcement timer.
    // Production 1.5-second delay and immediate safety actions are covered by RouteDirectionRecheckGraceTest.

    private val north = 1.0 / 111_320.0

    private fun rv(heading: Double, at: Long) = RouteFacingObservation(heading, 5.0, at)
    private fun gravity(heading: Double, at: Long) = RouteFacingObservation(
        heading, null, at, RouteFacingSource.GRAVITY_MAGNETIC, RouteFacingSensorQuality.CALIBRATED_SENSOR_CHECKS,
    )

    private fun started(first: RouteFacingObservation): Pair<RouteNavigator, String> {
        val navigator = RouteNavigator(RouteNavigatorConfig(directionRecheckGraceMs = 0L)).apply {
            setRoute(WalkingRoute(
                priority = "TEST",
                summary = WalkingRouteSummary(200, 200),
                polyline = listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0 + 200 * north, 127.0)),
                guidePoints = listOf(
                    WalkingRouteGuidePoint(0, RoutePoint(37.0 + 150 * north, 127.0), "직진하세요.", 150, 50, turnType = 11),
                ),
            ))
        }
        val update = navigator.update(TrustedLocation(37.0 + 20 * north, 127.0, 3f, 1_000L), 1_000L, false, null, first)
        return navigator to requireNotNull(update.instruction)
    }

    @Test
    fun firstObservationAfterAPrimaryToFallbackSwitchDoesNotCommandATurn() {
        val (navigator, first) = started(rv(90.0, 1_000L))
        assertTrue(first, first.contains("약 9시 방향"))
        val switched = requireNotNull(navigator.currentGuidance(1_100L, gravity(90.0, 1_100L))?.instruction)
        assertFalse("switch must not command immediately: $switched", switched.contains("왼쪽"))
        assertFalse(switched, switched.contains("몸을 돌리세요"))
        val confirmed = requireNotNull(navigator.currentGuidance(1_200L, gravity(90.0, 1_200L))?.instruction)
        assertNominalClock(confirmed, 9)
    }

    @Test
    fun aRepeatedSampleCannotConfirmTheNewSourceAndADifferentDirectionRestartsConfirmation() {
        val (navigator, _) = started(rv(90.0, 1_000L))
        navigator.currentGuidance(1_100L, gravity(90.0, 1_100L))
        val repeated = navigator.currentGuidance(1_150L, gravity(90.0, 1_100L))?.instruction.orEmpty()
        assertFalse(repeated, repeated.contains("왼쪽"))
        // A large disagreement between two fresh fallback samples is not continuity either.
        val jumped = navigator.currentGuidance(1_200L, gravity(270.0, 1_200L))?.instruction.orEmpty()
        assertFalse(jumped, jumped.contains("오른쪽"))
        val confirmed = navigator.currentGuidance(1_300L, gravity(270.0, 1_300L))?.instruction.orEmpty()
        assertNominalClock(confirmed, 3)
    }

    @Test
    fun switchingBackToThePrimaryIsAlsoASwitch() {
        val (navigator, first) = started(gravity(90.0, 1_000L))
        assertRecheck(first)
        val back = navigator.currentGuidance(1_100L, rv(90.0, 1_100L))?.instruction.orEmpty()
        assertFalse(back, back.contains("왼쪽"))
        assertTrue(navigator.currentGuidance(1_200L, rv(90.0, 1_200L))?.instruction.orEmpty().contains("약 9시 방향"))
    }

    /** 08.txt #12: a fallback whose motion check could not run is not treated as checked. */
    @Test
    fun motionUncheckedFallbackNeedsTwoAgreeingFreshObservationsForEachDirection() {
        fun unchecked(heading: Double, at: Long) = gravity(heading, at)
            .copy(sensorQuality = RouteFacingSensorQuality.CALIBRATED_WITHOUT_MOTION_CHECK)
        val (navigator, first) = started(unchecked(90.0, 1_000L))
        assertFalse(first, first.contains("왼쪽"))
        assertNominalClock(navigator.currentGuidance(1_100L, unchecked(90.0, 1_100L))?.instruction.orEmpty(), 9)
        val turned = navigator.currentGuidance(1_200L, unchecked(270.0, 1_200L))?.instruction.orEmpty()
        assertFalse(turned, turned.contains("오른쪽"))
        assertNominalClock(navigator.currentGuidance(1_300L, unchecked(270.0, 1_300L))?.instruction.orEmpty(), 3)
        // A changed nominal clock still needs fresh continuity; no numeric accuracy is invented.
        assertRecheck(navigator.currentGuidance(1_400L, gravity(90.0, 1_400L))?.instruction.orEmpty())
        assertRecheck(navigator.currentGuidance(1_500L, rv(90.0, 1_500L))?.instruction.orEmpty())
        assertTrue(navigator.currentGuidance(1_600L, rv(90.0, 1_600L))?.instruction.orEmpty().contains("약 9시 방향"))
    }

    @Test
    fun anUnchangedSourceKeepsImmediateDirection() {
        val (navigator, _) = started(rv(90.0, 1_000L))
        val changing = navigator.currentGuidance(1_100L, rv(270.0, 1_100L))?.instruction.orEmpty()
        assertRecheck(changing)
        val confirmed = navigator.currentGuidance(1_200L, rv(270.0, 1_200L))?.instruction.orEmpty()
        assertTrue(confirmed, confirmed.contains("약 3시 방향"))
    }
    private fun assertNominalClock(text: String, hour: Int) {
        assertTrue(text, text.contains("약 ${hour}시 방향"))
        assertFalse(text, text.contains("시에서") || text.contains("사이 방향"))
    }
    private fun assertRecheck(text: String) {
        assertTrue(text, text.contains("확정하기 어렵습니다"))
        assertFalse(text, text.contains("시 방향"))
        assertFalse(text, text.contains("몸을 돌리세요"))
        assertFalse(text, text.contains("직진하세요"))
    }
}

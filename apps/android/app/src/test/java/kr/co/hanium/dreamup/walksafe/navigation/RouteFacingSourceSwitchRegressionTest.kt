package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 08.txt #14: after a primary/fallback compass switch, the new source must show fresh continuity
 * before a turn command. The earlier source is comparison evidence only, never the current facing.
 */
class RouteFacingSourceSwitchRegressionTest {
    private val north = 1.0 / 111_320.0

    private fun rv(heading: Double, at: Long) = RouteFacingObservation(heading, 5.0, at)
    private fun gravity(heading: Double, at: Long) = RouteFacingObservation(
        heading, null, at, RouteFacingSource.GRAVITY_MAGNETIC, RouteFacingSensorQuality.CALIBRATED_SENSOR_CHECKS,
    )

    private fun started(first: RouteFacingObservation): Pair<RouteNavigator, String> {
        val navigator = RouteNavigator().apply {
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
        assertTrue(first, first.contains("경로는 왼쪽"))
        val switched = requireNotNull(navigator.currentGuidance(1_100L, gravity(90.0, 1_100L))?.instruction)
        assertFalse("switch must not command immediately: $switched", switched.contains("왼쪽"))
        assertFalse(switched, switched.contains("몸을 돌리세요"))
        val confirmed = requireNotNull(navigator.currentGuidance(1_200L, gravity(90.0, 1_200L))?.instruction)
        assertTrue(confirmed, confirmed.contains("경로는 왼쪽"))
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
        assertTrue(confirmed, confirmed.contains("경로는 오른쪽"))
    }

    @Test
    fun switchingBackToThePrimaryIsAlsoASwitch() {
        val (navigator, first) = started(gravity(90.0, 1_000L))
        assertTrue(first, first.contains("경로는 왼쪽"))
        val back = navigator.currentGuidance(1_100L, rv(90.0, 1_100L))?.instruction.orEmpty()
        assertFalse(back, back.contains("왼쪽"))
        assertTrue(navigator.currentGuidance(1_200L, rv(90.0, 1_200L))?.instruction.orEmpty().contains("경로는 왼쪽"))
    }

    /** 08.txt #12: a fallback whose motion check could not run is not treated as checked. */
    @Test
    fun motionUncheckedFallbackNeedsTwoAgreeingFreshObservationsForEachDirection() {
        fun unchecked(heading: Double, at: Long) = gravity(heading, at)
            .copy(sensorQuality = RouteFacingSensorQuality.CALIBRATED_WITHOUT_MOTION_CHECK)
        val (navigator, first) = started(unchecked(90.0, 1_000L))
        assertFalse(first, first.contains("왼쪽"))
        assertTrue(navigator.currentGuidance(1_100L, unchecked(90.0, 1_100L))?.instruction.orEmpty().contains("경로는 왼쪽"))
        val turned = navigator.currentGuidance(1_200L, unchecked(270.0, 1_200L))?.instruction.orEmpty()
        assertFalse(turned, turned.contains("오른쪽"))
        assertTrue(navigator.currentGuidance(1_300L, unchecked(270.0, 1_300L))?.instruction.orEmpty().contains("경로는 오른쪽"))
        // The same device with a checked sample is not delayed.
        assertTrue(navigator.currentGuidance(1_400L, gravity(90.0, 1_400L))?.instruction.orEmpty().contains("경로는 왼쪽"))
    }

    @Test
    fun anUnchangedSourceKeepsImmediateDirection() {
        val (navigator, _) = started(rv(90.0, 1_000L))
        val same = navigator.currentGuidance(1_100L, rv(270.0, 1_100L))?.instruction.orEmpty()
        assertTrue(same, same.contains("경로는 오른쪽"))
    }
}

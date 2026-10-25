package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.GnssPositionObservation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PedestrianMotionSample
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PedestrianMotionUpdateDisposition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PedestrianMotionWindowCoverage
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PedestrianStationaryDetector
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningCoordinator
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningSnapshot
import kr.co.hanium.dreamup.walksafe.navigation.positioning.UserLocomotionState
import kr.co.hanium.dreamup.walksafe.navigation.positioning.UserMovementState
import kr.co.hanium.dreamup.walksafe.navigation.positioning.WalkingSpeedObservation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.ZuptObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 종합분석 6.8: 정지 중 GNSS 단발 점프는 즉시 보행 확정·진행 안내 소비 금지.
 * Production order (Main): quiet motion -> rate-limited ZUPT, GNSS fix -> user speed ->
 * filtered position -> RouteNavigator. The motion state itself is never a route input.
 */
class StationaryGnssJumpRouteProgressRegressionTest {
    @Test
    fun singleGnssJumpWhileStationaryDoesNotConfirmWalkingOrConsumeTheNextTurn() {
        for (jumpEastM in listOf(30.0, 60.0)) {
            val coordinator = PositioningCoordinator()
            val detector = PedestrianStationaryDetector()
            val navigator = navigator()
            var lastZuptAt: Long? = null
            var zuptApplied = 0
            var before: RouteNavigatorUpdate? = null
            fun motionUntil(endAt: Long, startAt: Long) {
                for (at in startAt..endAt step 100L) {
                    val decision = detector.observe(quiet(at))
                    val motion = coordinator.observeUserMotion(decision, at)
                    if (decision.shouldApplyZeroVelocityUpdate && motion.zeroVelocityUpdateAllowed &&
                        lastZuptAt?.let { at - it >= 1_000L } != false
                    ) {
                        val zupt = coordinator.observeZupt(ZuptObservation(at), at)
                        if (zupt.motionUpdate?.disposition == PedestrianMotionUpdateDisposition.APPLIED) {
                            lastZuptAt = at
                            zuptApplied += 1
                        }
                    }
                }
            }
            for (second in 1L..11L) {
                motionUntil(second * 1_000L, if (second == 1L) 1_000L else (second - 1L) * 1_000L + 100L)
                before = routeUpdate(navigator, coordinator, 0.0, second * 1_000L + 50L)
            }
            assertTrue("stationary ZUPTs must be applied before the jump", zuptApplied >= 5)
            assertEquals(UserMovementState.STATIONARY, coordinator.currentUserMotion(11_050L).movement)
            val guideBefore = requireNotNull(before).guideIndex
            assertEquals(0, guideBefore)

            val jumpAt = 12_050L
            motionUntil(12_000L, 11_100L)
            val jumpSnapshot = gnss(coordinator, jumpEastM, jumpAt)
            val jumped = navigator.update(
                location(jumpEastM, jumpAt), jumpAt, false, filtered(jumpSnapshot, jumpAt),
            )
            val motion = coordinator.currentUserMotion(jumpAt)
            val filteredEastM = requireNotNull(jumpSnapshot.filtered).localEnu.eastM
            println("ADD-MOTION-055 jump=${jumpEastM}m filteredEast=${"%.2f".format(filteredEastM)}m " +
                "disposition=${jumpSnapshot.gnssUpdate?.disposition} guide=${jumped.guideIndex} " +
                "reason=${jumped.reason} movement=${motion.movement} locomotion=${motion.locomotion}")

            assertNotEquals(UserLocomotionState.WALKING, motion.locomotion)
            assertFalse(jumped.arrived)
            assertEquals("jump=$jumpEastM reason=${jumped.reason}", guideBefore, jumped.guideIndex)
            assertTrue("filtered position must not follow the single jump", filteredEastM < 20.0)

            // The next ordinary stationary fix still keeps the same guide.
            motionUntil(13_000L, 12_100L)
            assertEquals(guideBefore, routeUpdate(navigator, coordinator, 0.0, 13_050L).guideIndex)
        }
    }

    /**
     * ADD-MOTION-038 (종합분석 6.6): the stationary cross-check is step evidence, not the motion
     * state. A GNSS jump that persists over several samples while no step is taken confirms neither
     * the next turn nor arrival.
     */
    @Test
    fun sustainedStationaryJumpNeverConfirmsPassageOrArrival() {
        val navigator = navigator()
        for (at in 1_000L..3_000L step 1_000L) {
            assertEquals(0, navigator.update(location(0.0, at), at, false, stepProgressM = 0.0,
                facingObservation = RouteFacingObservation(90.0, 2.0, at)).guideIndex)
        }
        for (at in 4_000L..10_000L step 1_000L) {
            val jumped = navigator.update(location(30.0, at), at, false, stepProgressM = 0.0,
                facingObservation = RouteFacingObservation(90.0, 2.0, at))
            assertEquals("at=$at", 0, jumped.guideIndex)
            assertFalse(jumped.arrived)
            assertFalse(jumped.arrivalCandidate)
        }
    }

    private fun routeUpdate(
        navigator: RouteNavigator,
        coordinator: PositioningCoordinator,
        eastM: Double,
        at: Long,
    ): RouteNavigatorUpdate {
        val snapshot = gnss(coordinator, eastM, at)
        return navigator.update(location(eastM, at), at, false, filtered(snapshot, at))
    }

    private fun gnss(coordinator: PositioningCoordinator, eastM: Double, at: Long): PositioningSnapshot {
        val point = point(eastM)
        val snapshot = coordinator.observeGnss(GnssPositionObservation(point.latitude, point.longitude, 3.0, at, at))
        coordinator.observeUserSpeed(
            WalkingSpeedObservation(0.0, 0.1, at), UserMotionLocationEvidence(at, 3.0, false), at,
        )
        return snapshot
    }

    private fun filtered(snapshot: PositioningSnapshot, at: Long): FilteredRoutePosition {
        val fix = requireNotNull(snapshot.filtered)
        return FilteredRoutePosition(
            RoutePoint(fix.coordinate.latitude, fix.coordinate.longitude),
            fix.horizontalUncertaintyM,
            at,
        )
    }

    private fun navigator(): RouteNavigator = RouteNavigator().apply {
        setRoute(
            WalkingRoute(
                priority = "TEST",
                summary = WalkingRouteSummary(200, 200),
                polyline = listOf(point(0.0), point(200.0)),
                guidePoints = listOf(
                    WalkingRouteGuidePoint(0, point(20.0), null, 20, 180, turnType = 13),
                    WalkingRouteGuidePoint(1, point(190.0), null, 190, 10, turnType = 211),
                ),
            ),
            destination = point(300.0),
        )
    }

    private fun quiet(at: Long): PedestrianMotionSample {
        val window = PedestrianMotionWindowCoverage(5, at - 200L, at, 50L)
        return PedestrianMotionSample(at, accelerationMagnitudeMeanMps2 = 9.81,
            accelerationMagnitudeVarianceMps4 = 0.02, gyroscopeRmsRadPerSecond = 0.02,
            gyroscopePeakRadPerSecond = 0.05, accelerationWindow = window, gyroscopeWindow = window)
    }

    private fun location(eastM: Double, at: Long): TrustedLocation {
        val point = point(eastM)
        return TrustedLocation(point.latitude, point.longitude, 3f, at)
    }

    private fun point(eastM: Double): RoutePoint = RoutePoint(0.0, eastM / 111194.92664455874)
}

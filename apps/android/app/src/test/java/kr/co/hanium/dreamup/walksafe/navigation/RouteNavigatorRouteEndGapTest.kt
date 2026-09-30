package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S20-27: reaching the last polyline point is the route end, not arrival. When the requested
 * destination is beyond the arrival radius from that end, the app says how far (as information,
 * not a move command) and never opens arrival confirmation on its own.
 */
class RouteNavigatorRouteEndGapTest {
    @Test
    fun routeEndFortyMetresShortOfTheDestinationStatesTheGapAndDoesNotClaimArrival() {
        val navigator = navigator(destinationEastM = 240.0)
        walkToTheEnd(navigator)
        val atEnd = location(200.0, 81_000L)
        val update = navigator.update(atEnd, nowMs = 81_000L, requestInFlight = false)
        assertFalse(update.arrived)
        assertFalse(update.arrivalCandidate)
        val instruction = navigator.currentInstruction(atEnd).orEmpty()
        assertTrue(instruction, instruction.contains("요청한 목적지는 종점에서 직선으로 약 40m 떨어져 있습니다."))
        assertTrue(instruction, instruction.contains("요청한 목적지의 최종 접근을 확인하세요"))
        // Information only: no direction or "이동하세요" command for a gap that has no route.
        assertFalse(instruction, instruction.contains("이동하세요"))
        assertFalse(instruction, instruction.contains("목적지까지 약"))
    }

    @Test
    fun aDestinationAtTheRouteEndKeepsTheOrdinaryRemainingDistanceWording() {
        val navigator = navigator(destinationEastM = 200.0)
        walkToTheEnd(navigator)
        val instruction = navigator.currentInstruction(location(190.0, 81_000L)).orEmpty()
        assertFalse(instruction, instruction.contains("종점에서 직선으로"))
    }

    private fun walkToTheEnd(navigator: RouteNavigator) {
        listOf(0.0, 30.0, 70.0, 110.0, 150.0, 180.0, 195.0).forEachIndexed { index, eastM ->
            navigator.update(location(eastM, 1_000L + index * 10_000L), nowMs = 1_000L + index * 10_000L, requestInFlight = false)
        }
    }

    private fun navigator(destinationEastM: Double): RouteNavigator = RouteNavigator(
        RouteNavigatorConfig(maximumProgressAdvanceM = 40.0, guidanceIntervalMs = 0L),
    ).apply {
        setRoute(
            WalkingRoute(
                priority = "TEST",
                summary = WalkingRouteSummary(200, 200),
                polyline = listOf(point(0.0), point(200.0)),
                guidePoints = listOf(
                    WalkingRouteGuidePoint(0, point(20.0), null, 20, 180, turnType = 13),
                ),
            ),
            destination = point(destinationEastM),
        )
    }

    private fun location(eastM: Double, atMs: Long, accuracyM: Float = 3f): TrustedLocation {
        val point = point(eastM)
        return TrustedLocation(point.latitude, point.longitude, accuracyM, atMs)
    }

    private fun point(eastM: Double): RoutePoint = RoutePoint(0.0, eastM / 111194.92664455874)
}

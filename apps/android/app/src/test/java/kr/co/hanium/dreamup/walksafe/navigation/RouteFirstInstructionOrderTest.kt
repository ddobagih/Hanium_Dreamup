package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S08-30: the relative direction and action are computed when the sentence is made and go stale while
 * it is spoken, so in the first route instruction they come before the long-lived route total.
 */
class RouteFirstInstructionOrderTest {
    @Test
    fun directionAndActionAreSpokenBeforeTheRouteTotal() {
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0)).apply { setRoute(route()) }
        val first = navigator.update(
            TrustedLocation(0.0, 0.0, 2f, 1_000L), 1_000L, false,
            facingObservation = RouteFacingObservation(90.0, 5.0, 1_000L),
        ).instruction.orEmpty()
        val start = first.indexOf("길안내를 시작합니다.")
        val action = first.indexOf("왼쪽으로 꺾으세요")
        val total = first.indexOf("전체 경로는 약 333m입니다.")
        assertTrue(first, start == 0)
        assertTrue(first, action > start)
        assertTrue(first, total > action)
    }

    private fun guide(index: Int, point: RoutePoint, progressM: Int?, turnType: Int) = WalkingRouteGuidePoint(
        index, point, null, progressM, progressM?.let { 333 - it }, turnType = turnType,
    )

    private fun route() = WalkingRoute(
        priority = "STAIR_AVOID",
        summary = WalkingRouteSummary(333, 300),
        polyline = listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.001), RoutePoint(0.001, 0.001), RoutePoint(0.001, 0.002)),
        guidePoints = listOf(
            guide(0, RoutePoint(0.0, 0.0), 0, 200).copy(pointType = "SP"),
            guide(1, RoutePoint(0.0, 0.001), 111, 12).copy(instruction = "좌회전 후 94m 이동"),
            guide(2, RoutePoint(0.001, 0.001), 222, 13),
        ),
    )
}

package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S08-20: beside the route its progress direction is described, never turned into a movement command. */
class RouteNavigatorLateralOffsetGuidanceTest {
    @Test
    fun anAcceptedLegFarBesideThePositionIsDescribedWithoutACommand() {
        // A large reported radius lets the matcher accept a leg 12 m or 25 m away; that offset still
        // exceeds both the radius and the 8 m search floor, so the user is beside the leg.
        for ((northM, accuracyM) in listOf(12.0 to 10f, 25.0 to 20f)) {
            mapOf(90.0 to "약 12시 방향", 0.0 to "약 3시 방향", 270.0 to "약 6시 방향").forEach { (heading, clock) ->
                val navigator = navigator()
                val update = navigator.update(fix(northM, accuracyM), 1_000, false, facingObservation = facing(1_000, heading))
                val instruction = requireNotNull(update.instruction)
                assertTrue(navigator.currentRouteMatch()?.quality in setOf(RouteMatchQuality.HIGH, RouteMatchQuality.MEDIUM))
                assertEquals(RouteAlignmentReason.CURRENT_SEGMENT, update.routeAlignmentDiagnostic?.reason)
                assertFalse(update.directionOnly)
                assertTrue(instruction, instruction.contains("경로 진행 방향은 바라보고 있는 방향 기준 ${clock}입니다."))
                assertTrue(instruction.contains(
                    "현재 위치는 경로 옆으로 떨어져 있으며, 현재 위치에서 경로까지 걸어갈 수 있는 통로는 확인되지 않았습니다."))
                // The upcoming turn stays as a route overview, not as an action from this position.
                assertTrue(instruction, instruction.contains("경로를 따라 150m 가면 다음 안내 지점에는 경로 기준 3시 방향 회전이 있습니다."))
                assertNoCommand(instruction)
            }
        }
    }

    @Test
    fun anOffsetWithinTheReportedRadiusOrSearchFloorKeepsTheRouteCommand() {
        for ((northM, accuracyM) in listOf(0.0 to 5f, 6.0 to 5f, 9.0 to 10f, 18.0 to 20f)) {
            val instruction = requireNotNull(navigator().update(fix(northM, accuracyM), 1_000, false,
                facingObservation = facing(1_000, 90.0)).instruction)
            assertTrue(instruction, instruction.contains("약 12시 방향, 다음 안내 지점까지 경로를 따라 약 150미터입니다. 다음 안내 지점에서 경로 기준으로 3시 방향으로 꺾으세요."))
            assertFalse(instruction.contains("경로 옆으로 떨어져"))
        }
    }

    @Test
    fun anUnknownFacingBesideTheRouteStillGivesNoCommand() {
        val instruction = requireNotNull(navigator().update(fix(12.0, 10f), 1_000, false, facingObservation = null).instruction)
        assertTrue(instruction, instruction.contains("사용자 방향을 확인할 수 없어 다음 방향 안내를 보류합니다. 현재 위치는 경로 옆으로"))
        assertTrue(instruction.contains("경로를 따라 150m 가면 다음 안내 지점에는 경로 기준 3시 방향 회전이 있습니다."))
        assertNoCommand(instruction)
    }

    /** nav-F9 (08.txt:221): the movement command is replaced by one action that does not move the user. */
    @Test
    fun besideTheRouteTheActionIsToStopCheckTheWalkwayAndAskAgain() {
        val action = "잠시 멈춰 주변 보행로를 확인한 뒤 다시 안내를 요청해 주세요."
        for (observation in listOf(facing(1_000, 90.0), null)) {
            val instruction = requireNotNull(navigator().update(fix(12.0, 10f), 1_000, false,
                facingObservation = observation).instruction)
            assertTrue(instruction, instruction.endsWith(action))
            assertTrue(instruction.indexOf("경로 기준 3시 방향 회전이 있습니다.") < instruction.indexOf(action))
            assertNoCommand(instruction)
        }
        val onRoute = requireNotNull(navigator().update(fix(0.0, 5f), 1_000, false,
            facingObservation = facing(1_000, 90.0)).instruction)
        assertFalse(onRoute.contains(action))
    }

    private fun assertNoCommand(instruction: String) {
        listOf("꺾으세요", "이동하세요", "직진하세요", "돌아가세요", "돌리세요", "맞추세요", "걸으세요").forEach {
            assertFalse("$it in $instruction", instruction.contains(it))
        }
    }

    private fun navigator() = RouteNavigator().apply {
        setRoute(WalkingRoute("TEST", WalkingRouteSummary(400, 400),
            listOf(point(0.0), point(400.0)),
            listOf(WalkingRouteGuidePoint(0, point(200.0), "안내", 200, 200, turnType = 13))))
    }
    private fun point(eastM: Double, northM: Double = 0.0) =
        RoutePoint(northM / 111_194.92664455874, eastM / 111_194.92664455874)
    private fun fix(northM: Double, accuracyM: Float) =
        point(50.0, northM).let { TrustedLocation(it.latitude, it.longitude, accuracyM, 1_000) }
    private fun facing(nowMs: Long, degrees: Double) = RouteFacingObservation(degrees, 5.0, nowMs)
}

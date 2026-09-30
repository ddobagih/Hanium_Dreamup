package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteNavigatorFacingReferenceTest {
    @Test
    fun routeGuidanceNamesPhoneFrameAndConditionsBodyTurn() {
        listOf(
            0.0 to "오른쪽 경로 방향으로 몸을 돌리세요",
            180.0 to "왼쪽 경로 방향으로 몸을 돌리세요",
            270.0 to "경로 방향으로 몸을 돌리세요",
        ).forEach { (heading, expectedTurn) ->
            val instruction = requireNotNull(navigator().update(
                TrustedLocation(0.0, 0.00045, 2f, 1_000), 1_000, false,
                facingObservation = RouteFacingObservation(heading, 5.0, 1_000),
            ).instruction)
            val condition = "휴대전화 후면 카메라를 몸 정면에 맞춘 상태에서만"
            assertTrue(instruction.contains("휴대전화 후면 카메라 기준"))
            assertTrue(instruction.contains(expectedTurn))
            assertTrue(instruction.indexOf(condition) in 0 until instruction.indexOf("몸을 돌리세요"))
            assertFalse(instruction.contains("현재 바라보는"))
        }

        val front = requireNotNull(navigator().update(
            TrustedLocation(0.0, 0.00045, 2f, 1_000), 1_000, false,
            facingObservation = RouteFacingObservation(90.0, 5.0, 1_000),
        ).instruction)
        // S08-5: the first fresh sample now speaks its clock number in the same phone frame.
        assertTrue(front.contains("휴대전화 후면 카메라 기준 경로는 약 12시 방향입니다. 앞쪽이 경로 진행 방향입니다."))
        assertFalse(front.contains("현재 바라보는"))
    }

    @Test
    fun unavailableCameraDirectionDoesNotClaimToMeasureUserFacing() {
        val instruction = requireNotNull(navigator().update(
            TrustedLocation(0.0, 0.00045, 2f, 1_000), 1_000, false,
            facingObservation = null,
        ).instruction)
        assertTrue(instruction.contains("휴대전화 후면 카메라 방향을 확인할 수 없습니다"))
        assertFalse(instruction.contains("현재 바라보는 방향"))
    }

    private fun navigator() = RouteNavigator().apply {
        setRoute(WalkingRoute(
            "TEST", WalkingRouteSummary(200, 200),
            listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.0009), RoutePoint(-0.0009, 0.0009)),
            listOf(WalkingRouteGuidePoint(0, RoutePoint(0.0, 0.0009), null, 100, 100, turnType = 13)),
        ))
    }
}

package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteNavigatorFacingReferenceTest {
    // This fixture isolates the declared body-facing reference frame of the spoken sentence.
    // Production 1.5-second delay and immediate safety actions are covered by RouteDirectionRecheckGraceTest.

    @Test
    fun routeGuidanceNamesBodyFacingAndUsesTheShortBodyTurn() {
        listOf(
            0.0 to "안내한 방향으로 몸을 돌려주세요",
            180.0 to "안내한 방향으로 몸을 돌려주세요",
            270.0 to "안내한 방향으로 몸을 돌려주세요",
        ).forEach { (heading, expectedTurn) ->
            val instruction = requireNotNull(navigator().update(
                TrustedLocation(0.0, 0.00045, 2f, 1_000), 1_000, false,
                facingObservation = RouteFacingObservation(heading, 5.0, 1_000),
            ).instruction)
            val condition = "휴대전화 후면 카메라를 몸 정면에 맞춘 상태에서만"
            assertTrue(instruction.contains("바라보고 있는 방향 기준"))
            assertTrue(instruction.contains(expectedTurn))
            assertFalse(instruction.contains(condition))
            assertFalse(instruction.contains("현재 바라보는"))
        }

        val front = requireNotNull(navigator().update(
            TrustedLocation(0.0, 0.00045, 2f, 1_000), 1_000, false,
            facingObservation = RouteFacingObservation(90.0, 5.0, 1_000),
        ).instruction)
        // S08-5: the first fresh sample now speaks its clock number in the declared facing frame.
        assertTrue(front.contains("바라보고 있는 방향 기준 약 12시 방향, 다음 안내 지점까지 경로를 따라 약 50미터입니다."))
        assertFalse(front.contains("현재 바라보는"))
    }

    @Test
    fun unavailableCameraDirectionDoesNotClaimToMeasureUserFacing() {
        val instruction = requireNotNull(navigator().update(
            TrustedLocation(0.0, 0.00045, 2f, 1_000), 1_000, false,
            facingObservation = null,
        ).instruction)
        assertTrue(instruction.contains("사용자 방향을 확인할 수 없어 다음 방향 안내를 보류합니다"))
        assertFalse(instruction.contains("현재 바라보는 방향"))
    }

    private fun navigator() = RouteNavigator(RouteNavigatorConfig(directionRecheckGraceMs = 0L)).apply {
        setRoute(WalkingRoute(
            "TEST", WalkingRouteSummary(200, 200),
            listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.0009), RoutePoint(-0.0009, 0.0009)),
            listOf(WalkingRouteGuidePoint(0, RoutePoint(0.0, 0.0009), null, 100, 100, turnType = 13)),
        ))
    }
}

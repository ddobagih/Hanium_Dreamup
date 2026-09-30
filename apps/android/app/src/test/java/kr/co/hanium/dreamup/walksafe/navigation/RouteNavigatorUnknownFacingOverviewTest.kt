package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S08-27: an unknown facing gives the next maneuver only as a route overview, never as a command. */
class RouteNavigatorUnknownFacingOverviewTest {
    @Test
    fun unknownFacingSpeaksTheNextTurnAsOverviewWithoutAnExecutableCommand() {
        for (observation in listOf(null, facing(1_000).copy(headingAccuracyDegrees = 31.0), facing(499))) {
            val update = navigator(turnType = 13).update(fix(), 1_000, false, facingObservation = observation)
            val instruction = requireNotNull(update.instruction)
            assertTrue(instruction, instruction.contains("경로를 따라 150m 가면 다음 안내 지점에는 오른쪽 회전이 있습니다."))
            assertTrue(instruction.contains("방향과 위치를 확인한 뒤 다시 안내를 요청해 주세요."))
            assertNoCommand(instruction)
            // The overview comes after the direction notice and before the recovery request.
            assertTrue(instruction.indexOf("오른쪽 회전이 있습니다") < instruction.indexOf("방향과 위치를 확인한 뒤"))
        }
    }

    @Test
    fun overviewCoversMappedManeuversWithoutClockNumbers() {
        mapOf(
            // nav-F7: the distance is along the route, not "ahead" of a facing that is unknown.
            11 to "경로를 따라 150m 가면 다음 안내 지점에서 경로는 직진으로 이어집니다.",
            12 to "경로를 따라 150m 가면 다음 안내 지점에는 왼쪽 회전이 있습니다.",
            17 to "경로를 따라 150m 가면 다음 안내 지점에는 왼쪽 길로 가는 회전이 있습니다.",
            18 to "경로를 따라 150m 가면 다음 안내 지점에는 오른쪽 길로 가는 회전이 있습니다.",
        ).forEach { (turnType, expected) ->
            val instruction = requireNotNull(navigator(turnType).update(fix(), 1_000, false, facingObservation = null).instruction)
            assertTrue(instruction, instruction.contains(expected))
            assertNoCommand(instruction)
        }
    }

    @Test
    fun aKnownFacingKeepsTheExecutableCommandAndUnmappedTypesGiveNoOverview() {
        val front = requireNotNull(navigator(13).update(fix(), 1_000, false, facingObservation = facing(1_000)).instruction)
        assertTrue(front.contains("150m 앞, 오른쪽으로 꺾으세요."))
        assertFalse(front.contains("회전이 있습니다"))
        val instruction = requireNotNull(navigator(125).update(fix(), 1_000, false, facingObservation = null).instruction)
        assertFalse(instruction, instruction.contains("다음 안내 지점"))
        assertTrue(instruction.contains("방향과 위치를 확인한 뒤 다시 안내를 요청해 주세요."))
        assertNoCommand(instruction)
    }

    /** nav-F3 (08.txt:288): a crossing marker is still given as an overview, never as a crossing command. */
    @Test
    fun anUnknownFacingGivesACrossingMarkerAsAnOverviewWithItsReferenceNotice() {
        val instruction = requireNotNull(navigator(211).update(fix(), 1_000, false, facingObservation = null).instruction)
        assertTrue(instruction, instruction.contains(
            "경로를 따라 150m 가면 다음 안내 지점에 횡단보도가 있습니다. $CROSSWALK_REFERENCE_NOTICE_KO"))
        assertTrue(instruction.indexOf(CROSSWALK_REFERENCE_NOTICE_KO) < instruction.indexOf("방향과 위치를 확인한 뒤"))
        assertNoCommand(instruction)
        assertFalse(instruction.contains("건너세요"))
    }

    private fun assertNoCommand(instruction: String) {
        listOf("꺾으세요", "이동하세요", "직진하세요", "돌아가세요", "돌리세요", "시 방향").forEach {
            assertFalse("$it in $instruction", instruction.contains(it))
        }
    }

    private fun navigator(turnType: Int) = RouteNavigator().apply {
        setRoute(WalkingRoute("TEST", WalkingRouteSummary(400, 400),
            listOf(point(0.0), point(400.0)),
            listOf(WalkingRouteGuidePoint(0, point(200.0), "안내", 200, 200, turnType = turnType))))
    }
    private fun point(eastM: Double) = RoutePoint(0.0, eastM / 111_194.92664455874)
    private fun fix() = point(50.0).let { TrustedLocation(it.latitude, it.longitude, 2f, 1_000) }
    private fun facing(nowMs: Long, degrees: Double = 90.0) = RouteFacingObservation(degrees, 5.0, nowMs)
}

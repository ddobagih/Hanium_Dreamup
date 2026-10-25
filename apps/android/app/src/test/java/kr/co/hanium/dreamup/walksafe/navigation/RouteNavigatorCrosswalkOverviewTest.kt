package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * nav-F3 (S08-27 08.txt:288, S08-20 08.txt:221): with an unknown facing or beside the route, a
 * crosswalk-marked guide point is still described as a route overview -- the crossing, a mapped turn
 * and the reference notice -- without an action verb, a crossing command or a signal judgement.
 */
class RouteNavigatorCrosswalkOverviewTest {
    // This fixture isolates non-action crossing wording and its reference notice.
    // Production 1.5-second delay and immediate safety actions are covered by RouteDirectionRecheckGraceTest.

    private val crossing = "경로를 따라 150m 가면 다음 안내 지점에 횡단보도가 있습니다."

    @Test
    fun aCrossingMarkerIsAnOverviewInTheUnknownFacingAndBesideRouteStates() {
        for ((name, guide) in cases()) {
            val states = mapOf(
                "unknown-facing" to navigator(guide).update(fix(), 1_000, false, facingObservation = null),
                "beside" to navigator(guide).update(fix(northM = 12.0, accuracyM = 10f), 1_000, false,
                    facingObservation = facing(1_000)),
                "beside-unknown-facing" to navigator(guide).update(fix(northM = 12.0, accuracyM = 10f), 1_000, false,
                    facingObservation = null),
            )
            states.forEach { (state, update) ->
                val instruction = requireNotNull(update.instruction)
                val label = "$name/$state: $instruction"
                assertTrue(label, instruction.contains("$crossing${turnOverview(name)} $CROSSWALK_REFERENCE_NOTICE_KO"))
                assertEquals(label, name == "rightTurnAtCrossing", instruction.contains("회전이 있습니다"))
                assertNoActionOrCrossingJudgement(label, instruction)
                assertFalse("An overview must not claim the user's facing: $label", instruction.contains("전방"))
                assertTrue(label, instruction.contains("참고 안내"))
                assertTrue(label, instruction.contains("주변을 확인"))
                assertTrue(label, instruction.contains("횡단 판단은 대신하지"))
                val previousNotice =
                    "횡단보도 정보는 참고용입니다. 신호와 차량 등 주변 안전을 직접 확인하세요. 이 안내는 건너도 안전하다는 판단을 대신하지 않습니다."
                assertTrue("The spoken reference must be shorter: $label",
                    CROSSWALK_REFERENCE_NOTICE_KO.length < previousNotice.length)
            }
        }
    }

    @Test
    fun theOnRouteCommandKeepsTheCrossingNoticeAfterTheClockAndRouteDistance() {
        val expected = mapOf(
            "crosswalk211" to "횡단보도 안내 지점입니다. $CROSSWALK_REFERENCE_NOTICE_KO",
            "rightTurnAtCrossing" to "3시 방향으로 꺾으세요. $CROSSWALK_REFERENCE_NOTICE_KO",
        )
        for ((name, guide) in cases()) {
            val instruction = requireNotNull(navigator(guide).update(fix(accuracyM = 5f), 1_000, false,
                facingObservation = facing(1_000)).instruction)
            assertTrue(instruction, instruction.contains("약 12시 방향, 다음 안내 지점까지 경로를 따라 약 150미터입니다. 다음 안내 지점에서 경로 기준으로 ${expected.getValue(name)}"))
            assertFalse(instruction.contains(crossing))
        }
    }

    private fun turnOverview(name: String) = if (name == "rightTurnAtCrossing") " 그 지점에는 경로 기준 3시 방향 회전이 있습니다." else ""
    private fun assertNoActionOrCrossingJudgement(label: String, instruction: String) {
        listOf("꺾으세요", "이동하세요", "직진하세요", "돌아가세요", "돌리세요", "맞추세요", "걸으세요", "건너세요", "시 방향 오른쪽 길").forEach {
            assertFalse("$it in $label", instruction.contains(it))
        }
        // Outside the fixed reference notice nothing mentions signals or crossing permission.
        val rest = instruction.replace(CROSSWALK_REFERENCE_NOTICE_KO, "")
        listOf("신호", "건너", "초록").forEach { assertFalse("$it in $label", rest.contains(it)) }
    }
    private fun cases() = listOf(
        "crosswalk211" to WalkingRouteGuidePoint(0, point(200.0), "횡단보도", 200, 200, turnType = 211),
        "rightTurnAtCrossing" to WalkingRouteGuidePoint(0, point(200.0), null, 200, 200, turnType = 13, pointType = "횡단보도"),
    )
    private fun navigator(guide: WalkingRouteGuidePoint) = RouteNavigator(RouteNavigatorConfig(directionRecheckGraceMs = 0L)).apply {
        setRoute(WalkingRoute("TEST", WalkingRouteSummary(400, 400), listOf(point(0.0), point(400.0)), listOf(guide)))
    }
    private fun point(eastM: Double, northM: Double = 0.0) =
        RoutePoint(northM / 111_194.92664455874, eastM / 111_194.92664455874)
    private fun fix(northM: Double = 0.0, accuracyM: Float = 2f) =
        point(50.0, northM).let { TrustedLocation(it.latitude, it.longitude, accuracyM, 1_000) }
    private fun facing(nowMs: Long) = RouteFacingObservation(90.0, 3.0, nowMs)
}

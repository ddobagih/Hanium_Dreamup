package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S08-30: short-lived direction/action and longer route explanations need separate utterances. */
class RouteInstructionSpeechSeparationRegressionTest {
    @Test
    fun firstAlignedInstructionDoesNotMixRouteTotalIntoItsDirectionAndAction() {
        val navigator = navigator()
        val first = navigator.update(fix(1_000L), 1_000L, false,
            facingObservation = facing(1_000L))
        val instruction = requireNotNull(first.instruction)
        val directionAt = instruction.indexOf("약 12시")
        val actionAt = instruction.indexOf("9시 방향으로 꺾으세요")
        assertTrue(instruction, directionAt >= 0)
        assertTrue(instruction, actionAt > directionAt)
        assertNotNull(first.speechCueToken)
        assertFalse("the first direction/action utterance must not contain route-total information: $instruction",
            instruction.contains("전체 경로는"))
        val information = requireNotNull(first.infoUtterance)
        assertTrue(information.message, information.message.contains("전체 경로는 약 333m입니다."))
        assertFalse(information.message, information.message.contains("12시"))
        assertFalse(information.message, information.message.contains("꺾으세요"))
    }

    @Test
    fun firstUncertainInstructionDoesNotMixLongPositionNoticeIntoShortGuidance() {
        val navigator = nearbyBranches()
        navigator.update(branchFix(15.0, 0.0, 1_000L), 1_000L, false,
            facingObservation = facing(1_000L))
        val first = navigator.update(branchFix(15.0, 2.4, 2_000L), 2_000L, false,
            facingObservation = facing(2_000L))
        assertEquals(RouteMatchQuality.LOW, navigator.currentRouteMatch()?.quality)
        assertNotNull(first.routeAlignmentDiagnostic?.bearingDegreesTrueNorth)
        assertNotNull(first.speechCueToken)
        val instruction = requireNotNull(first.instruction)
        assertFalse("unsolicited position uncertainty must not interrupt the direction/action: $instruction",
            instruction.contains("현재 경로 위치가 불확실하여 남은 거리와 안내 지점을 추정합니다."))
        assertFalse(first.infoUtterance?.message.orEmpty().contains(
            "현재 경로 위치가 불확실하여 남은 거리와 안내 지점을 추정합니다."))
    }

    @Test
    fun shortDirectionStillUsesItsOriginalInclusive500MillisecondLease() {
        val navigator = navigator()
        val first = navigator.update(fix(1_000L), 1_000L, false,
            facingObservation = facing(1_000L))
        val token = requireNotNull(first.speechCueToken)
        assertTrue(token.requiresFacingEvidence)
        assertEquals(1_000L, token.facingObservedAtElapsedRealtimeMs)
        assertEquals(1_500L, token.facingValidUntilElapsedRealtimeMs)
        assertTrue(navigator.reserveInstruction(first))
        assertTrue(navigator.isInstructionCurrent(first, 1_500L, facing(1_500L)))
        assertFalse("a later fresh heading must not extend the original cue's lease",
            navigator.isInstructionCurrent(first, 1_501L, facing(1_501L)))
    }

    @Test
    fun unknownOverviewStillBlocksMovementAndRejectsItsOldTextAfterFacingRecovers() {
        val navigator = navigator()
        val first = navigator.update(fix(1_000L), 1_000L, false, facingObservation = null)
        assertEquals("guidance_facing_recheck_wait", first.reason)
        assertNull(first.instruction)
        assertNull(first.speechCueToken)
        assertNull(navigator.retryGuidance(2_499L, null)?.instruction)
        val overview = requireNotNull(navigator.retryGuidance(2_500L, null))
        val instruction = requireNotNull(overview.instruction)
        assertTrue(instruction, instruction.contains("경로 기준 9시 방향 회전이 있습니다"))
        assertFalse(instruction, instruction.contains("꺾으세요"))
        assertFalse(instruction, instruction.contains("몸을 돌려주세요"))
        val token = requireNotNull(overview.speechCueToken)
        assertFalse(token.requiresFacingEvidence)
        assertNull(token.facingObservedAtElapsedRealtimeMs)
        assertNull(token.facingValidUntilElapsedRealtimeMs)
        assertTrue(navigator.reserveInstruction(overview))
        assertTrue(navigator.isInstructionCurrent(overview, 2_600L, null))
        assertFalse(navigator.isInstructionCurrent(overview, 2_600L, facing(2_600L)))
    }

    private fun navigator() = RouteNavigator().apply { setRoute(route()) }

    private fun route() = WalkingRoute(
        priority = "STAIR_AVOID",
        summary = WalkingRouteSummary(333, 300),
        polyline = listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.001),
            RoutePoint(0.001, 0.001), RoutePoint(0.001, 0.002)),
        guidePoints = listOf(
            guide(0, RoutePoint(0.0, 0.0), 0, 200).copy(pointType = "SP"),
            guide(1, RoutePoint(0.0, 0.001), 111, 12).copy(instruction = "좌회전 후 94m 이동"),
            guide(2, RoutePoint(0.001, 0.001), 222, 13),
        ),
    )

    private fun guide(index: Int, point: RoutePoint, progressM: Int, turnType: Int) =
        WalkingRouteGuidePoint(index, point, null, progressM, 333 - progressM, turnType = turnType)

    private fun fix(at: Long) = TrustedLocation(0.0, 0.0, 2f, at)
    private fun facing(at: Long) = RouteFacingObservation(90.0, 5.0, at)

    private fun nearbyBranches() = RouteNavigator(allowDegradedRouteGuidance = true).apply {
        setRoute(WalkingRoute("TEST", WalkingRouteSummary(125, 300),
            listOf(point(0.0), point(30.0), point(30.0, 20.0), point(0.0, 20.0),
                point(0.0, 5.0), point(30.0, 5.0)), emptyList()))
    }

    private fun point(eastM: Double, northM: Double = 0.0) =
        RoutePoint(northM / 111194.92664455874, eastM / 111194.92664455874)

    private fun branchFix(eastM: Double, northM: Double, at: Long): TrustedLocation {
        val point = point(eastM, northM)
        return TrustedLocation(point.latitude, point.longitude, 3f, at)
    }
}

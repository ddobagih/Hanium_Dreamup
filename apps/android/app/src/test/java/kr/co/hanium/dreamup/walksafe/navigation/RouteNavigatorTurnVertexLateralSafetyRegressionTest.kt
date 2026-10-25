package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteCorridorMatcher
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S08-20: an unpassed turn does not establish a walkway from a position beside its vertex. */
class RouteNavigatorTurnVertexLateralSafetyRegressionTest {
    @Test
    fun acceptedVertexBesideTheRouteDescribesTheTurnWithoutCommandingIt() {
        val navigator = navigator()
        val update = navigator.update(fix(20.0, 12.0, 10f), 1_000L, false,
            facingObservation = facing(90.0))
        assertEquals(RouteMatchQuality.MEDIUM, navigator.currentRouteMatch()?.quality)
        assertEquals(RouteAlignmentReason.TURN_PASSAGE_PENDING, update.routeAlignmentDiagnostic?.reason)
        assertFalse(update.directionOnly)
        assertFalse(update.offRoute)
        assertFalse(update.shouldReroute)
        assertFalse(update.arrived)
        assertNull(update.pendingUserDecision)
        val text = requireNotNull(update.instruction)
        assertNoMovementCommand(text)
        assertTrue(text, text.contains("현재 위치는 경로 옆으로 떨어져 있으며"))
        assertTrue(text, text.contains("걸어갈 수 있는 통로는 확인되지 않았습니다."))
        assertTrue(text, text.contains("다음 안내 지점에는 경로 기준 3시 방향 회전이 있습니다."))
        assertTrue(text, text.endsWith(STOP_AND_CHECK))
        // A manual request uses the same safety context rather than restoring the turn command.
        val repeated = requireNotNull(navigator.currentGuidance(1_100L, facing(90.0, 1_100L)))
        assertEquals(text, repeated.instruction)
    }

    @Test
    fun vertexSelectionPreservesLateralFactWhileWithholdingTheOutgoingBearing() {
        val route = cornerRoute()
        val position = FilteredRoutePosition(point(20.0, 12.0), 10.0, 1_000L)
        val match = RouteCorridorMatcher().match("corner", route.polyline, position)
        assertEquals(RouteMatchQuality.MEDIUM, match.quality)
        val selection = RouteAlignmentSelector().select(route, position, match, 1_000L, 35.0,
            PendingRouteGuide(20.0, 10.0))
        assertEquals(RouteAlignmentReason.TURN_PASSAGE_PENDING, selection.diagnostic.reason)
        assertTrue(selection.beyondPendingTurnVertex)
        assertNull(selection.bearingDegrees)
        assertTrue("The pending turn must retain the existing lateral-distance fact", selection.lateralOffsetBeyondUncertainty)
    }

    @Test
    fun anOnRouteVertexKeepsTheExistingTurnCommandForEitherFacing() {
        for (heading in listOf(90.0, 180.0)) {
            val update = navigator().update(fix(20.0, 0.0, 3f), 1_000L, false,
                facingObservation = facing(heading))
            assertEquals(RouteAlignmentReason.TURN_PASSAGE_PENDING, update.routeAlignmentDiagnostic?.reason)
            assertEquals("안내 지점 부근입니다. 경로 기준으로 3시 방향으로 꺾으세요.", update.instruction)
            assertFalse(requireNotNull(update.instruction).contains(STOP_AND_CHECK))
        }
    }

    @Test
    fun aReservedVertexTurnIsRevokedWhenANewFixPlacesTheUserBesideTheRoute() {
        val navigator = navigator()
        val first = navigator.update(fix(20.0, 0.0, 10f), 1_000L, false,
            facingObservation = facing(90.0))
        assertEquals("안내 지점 부근입니다. 경로 기준으로 3시 방향으로 꺾으세요.", first.instruction)
        assertTrue(navigator.reserveInstruction(first))
        assertTrue(navigator.isInstructionCurrent(first, 1_000L, facing(90.0)))
        val beside = navigator.update(fix(20.0, 12.0, 10f, 2_000L), 2_000L, false,
            facingObservation = facing(90.0, 2_000L))
        assertEquals(RouteAlignmentReason.TURN_PASSAGE_PENDING, beside.routeAlignmentDiagnostic?.reason)
        assertSafeReplacement(navigator, first, beside)
        assertEquals(first.guideIndex, beside.guideIndex)
    }

    @Test
    fun aReservedOrdinaryRouteCommandIsAlsoRevokedByTheSameLateralFact() {
        val navigator = RouteNavigator(allowDegradedRouteGuidance = false).apply {
            setRoute(WalkingRoute("TEST", WalkingRouteSummary(400, 400),
                listOf(point(0.0), point(400.0)),
                listOf(WalkingRouteGuidePoint(0, point(200.0), null, 200, 200, turnType = 13))), origin = null)
        }
        val first = navigator.update(fix(50.0, 0.0, 10f), 1_000L, false,
            facingObservation = facing(90.0))
        assertTrue(requireNotNull(first.instruction).contains("3시 방향으로 꺾으세요."))
        assertTrue(navigator.reserveInstruction(first))
        assertTrue(navigator.isInstructionCurrent(first, 1_000L, facing(90.0)))
        val atMs = 1_100L
        assertTrue(requireNotNull(first.speechCueToken).hasCurrentFacingEvidence(atMs))
        val beside = navigator.update(fix(50.0, 12.0, 10f, atMs), atMs, false,
            facingObservation = facing(90.0, atMs))
        assertEquals(RouteAlignmentReason.CURRENT_SEGMENT, beside.routeAlignmentDiagnostic?.reason)
        assertSafeReplacement(navigator, first, beside, atMs)
    }

    @Test
    fun unchangedOnRouteOrBesideSafetyFactsKeepTheReservedVertexSentence() {
        for (northM in listOf(0.0, 12.0)) {
            val navigator = navigator()
            val first = navigator.update(fix(20.0, northM, 10f), 1_000L, false,
                facingObservation = facing(90.0))
            assertTrue(navigator.reserveInstruction(first))
            val unchanged = navigator.update(fix(20.0, northM, 10f, 2_000L), 2_000L, false,
                facingObservation = facing(90.0, 2_000L))
            assertEquals("north=$northM", "guidance_in_flight", unchanged.reason)
            assertFalse(unchanged.cancelStaleNavigationSpeech)
            assertNull(unchanged.instruction)
            assertTrue(navigator.isInstructionCurrent(first, 2_000L, facing(90.0, 2_000L)))
        }
    }

    @Test
    fun aCompletedVertexTurnDoesNotSuppressTheNewBesideSafetyInstruction() {
        val navigator = navigator()
        val first = navigator.update(fix(20.0, 0.0, 10f), 1_000L, false,
            facingObservation = facing(90.0))
        assertEquals("안내 지점 부근입니다. 경로 기준으로 3시 방향으로 꺾으세요.", first.instruction)
        assertTrue(navigator.reserveInstruction(first))
        navigator.acknowledgeInstruction(first, 1_000L)
        val beside = navigator.update(fix(20.0, 12.0, 10f, 2_000L), 2_000L, false,
            facingObservation = facing(90.0, 2_000L))
        assertCompletedSafetyReplacement(navigator, first, beside, 2_000L)
    }

    @Test
    fun aCompletedOrdinaryCommandDoesNotDelayTheNewBesideSafetyInstruction() {
        val navigator = RouteNavigator(allowDegradedRouteGuidance = false).apply {
            setRoute(WalkingRoute("TEST", WalkingRouteSummary(400, 400),
                listOf(point(0.0), point(400.0)),
                listOf(WalkingRouteGuidePoint(0, point(200.0), null, 200, 200, turnType = 13))), origin = null)
        }
        val first = navigator.update(fix(50.0, 0.0, 10f), 1_000L, false,
            facingObservation = facing(90.0))
        assertTrue(requireNotNull(first.instruction).contains("3시 방향으로 꺾으세요."))
        assertTrue(navigator.reserveInstruction(first))
        navigator.acknowledgeInstruction(first, 1_000L)
        val beside = navigator.update(fix(50.0, 12.0, 10f, 1_100L), 1_100L, false,
            facingObservation = facing(90.0, 1_100L))
        assertCompletedSafetyReplacement(navigator, first, beside, 1_100L)
    }

    @Test
    fun unchangedCompletedSafetyFactsKeepTheExistingDedupeAndCadence() {
        for (northM in listOf(0.0, 12.0)) {
            val navigator = navigator()
            val first = navigator.update(fix(20.0, northM, 10f), 1_000L, false,
                facingObservation = facing(90.0))
            assertTrue(navigator.reserveInstruction(first))
            navigator.acknowledgeInstruction(first, 1_000L)
            val unchanged = navigator.update(fix(20.0, northM, 10f, 2_000L), 2_000L, false,
                facingObservation = facing(90.0, 2_000L))
            assertEquals("north=$northM", "guidance_rate_limited", unchanged.reason)
            assertNull(unchanged.instruction)
            assertFalse(unchanged.cancelStaleNavigationSpeech)
            val afterCadence = navigator.update(fix(20.0, northM, 10f, 7_000L), 7_000L, false,
                facingObservation = facing(90.0, 7_000L))
            assertEquals("north=$northM", "guidance_already_completed", afterCadence.reason)
            assertNull(afterCadence.instruction)
            assertFalse(afterCadence.cancelStaleNavigationSpeech)
        }
        val navigator = RouteNavigator(allowDegradedRouteGuidance = false).apply {
            setRoute(WalkingRoute("TEST", WalkingRouteSummary(400, 400),
                listOf(point(0.0), point(400.0)),
                listOf(WalkingRouteGuidePoint(0, point(200.0), null, 200, 200, turnType = 13))), origin = null)
        }
        val first = navigator.update(fix(50.0, 0.0, 10f), 1_000L, false,
            facingObservation = facing(90.0))
        assertTrue(navigator.reserveInstruction(first))
        navigator.acknowledgeInstruction(first, 1_000L)
        val unchanged = navigator.update(fix(50.0, 0.0, 10f, 1_100L), 1_100L, false,
            facingObservation = facing(90.0, 1_100L))
        assertEquals("guidance_rate_limited", unchanged.reason)
        assertNull(unchanged.instruction)
        assertFalse(unchanged.cancelStaleNavigationSpeech)
    }

    @Test
    fun aRateLimitedFacingRefreshDoesNotEraseTheNextBesideSafetyTransition() {
        val navigator = RouteNavigator(allowDegradedRouteGuidance = false).apply {
            setRoute(WalkingRoute("TEST", WalkingRouteSummary(400, 400),
                listOf(point(0.0), point(400.0)),
                listOf(WalkingRouteGuidePoint(0, point(200.0), null, 200, 200, turnType = 13))), origin = null)
        }
        val first = navigator.update(fix(50.0, 0.0, 10f), 1_000L, false,
            facingObservation = facing(90.0))
        assertTrue(requireNotNull(first.instruction).contains("3시 방향으로 꺾으세요."))
        assertTrue(navigator.reserveInstruction(first))
        navigator.acknowledgeInstruction(first, 1_000L)
        val refreshed = navigator.update(fix(50.0, 0.0, 10f, 1_100L), 1_100L, false,
            facingObservation = facing(0.0, 1_100L))
        assertEquals("guidance_rate_limited", refreshed.reason)
        assertEquals(RouteDirectionGraceMode.GRACE_RETAINED, navigator.currentDirectionGraceSnapshot()?.mode)
        assertNull(refreshed.instruction)
        assertTrue(requireNotNull(first.speechCueToken).hasCurrentFacingEvidence(1_200L))
        val beside = navigator.update(fix(50.0, 12.0, 10f, 1_200L), 1_200L, false,
            facingObservation = facing(90.0, 1_200L))
        assertCompletedSafetyReplacement(navigator, first, beside, 1_200L)
    }

    @Test
    fun aRateLimitedRefreshKeepsCadenceAndDedupeWhenTheBesideFactIsUnchanged() {
        for (northM in listOf(0.0, 12.0)) {
            val navigator = RouteNavigator(allowDegradedRouteGuidance = false).apply {
                setRoute(WalkingRoute("TEST", WalkingRouteSummary(400, 400),
                    listOf(point(0.0), point(400.0)),
                    listOf(WalkingRouteGuidePoint(0, point(200.0), null, 200, 200, turnType = 13))), origin = null)
            }
            val first = navigator.update(fix(50.0, northM, 10f), 1_000L, false,
                facingObservation = facing(90.0))
            assertTrue(navigator.reserveInstruction(first))
            navigator.acknowledgeInstruction(first, 1_000L)
            val refreshed = navigator.update(fix(50.0, northM, 10f, 1_100L), 1_100L, false,
                facingObservation = facing(0.0, 1_100L))
            assertEquals("north=$northM",
                "guidance_rate_limited", refreshed.reason)
            assertNull(refreshed.instruction)
            val unchanged = navigator.update(fix(50.0, northM, 10f, 1_200L), 1_200L, false,
                facingObservation = facing(90.0, 1_200L))
            assertEquals("north=$northM", "guidance_rate_limited", unchanged.reason)
            assertNull(unchanged.instruction)
            assertFalse(unchanged.cancelStaleNavigationSpeech)
            val afterCadence = navigator.update(fix(50.0, northM, 10f, 7_000L), 7_000L, false,
                facingObservation = facing(90.0, 7_000L))
            assertEquals("north=$northM", "guidance_already_completed", afterCadence.reason)
            assertNull(afterCadence.instruction)
        }
    }

    private fun assertCompletedSafetyReplacement(navigator: RouteNavigator, first: RouteNavigatorUpdate,
        beside: RouteNavigatorUpdate, atMs: Long) {
        val text = requireNotNull(beside.instruction) { "The new safety instruction must be offered: ${beside.reason}" }
        assertNoMovementCommand(text)
        assertTrue(text, text.contains("걸어갈 수 있는 통로는 확인되지 않았습니다."))
        assertTrue(text, text.endsWith(STOP_AND_CHECK))
        assertNotEquals(first.speechCueToken, beside.speechCueToken)
        assertFalse(beside.cancelStaleNavigationSpeech)
        assertTrue(navigator.reserveInstruction(beside))
        assertTrue(navigator.isInstructionCurrent(beside, atMs, facing(90.0, atMs)))
    }

    private fun assertSafeReplacement(navigator: RouteNavigator, first: RouteNavigatorUpdate,
        beside: RouteNavigatorUpdate, atMs: Long = 2_000L) {
        assertFalse("The old movement command must fail its playback validator",
            navigator.isInstructionCurrent(first, atMs, facing(90.0, atMs)))
        assertTrue("The dispatcher must cancel the reserved command", beside.cancelStaleNavigationSpeech)
        val text = requireNotNull(beside.instruction)
        assertNoMovementCommand(text)
        assertTrue(text, text.contains("걸어갈 수 있는 통로는 확인되지 않았습니다."))
        assertTrue(text, text.endsWith(STOP_AND_CHECK))
        assertNotEquals(first.speechCueToken, beside.speechCueToken)
        assertFalse(navigator.reserveInstruction(first))
        assertTrue(navigator.reserveInstruction(beside))
        // A late completion of the invalidated action cannot consume the replacement.
        navigator.acknowledgeInstruction(first, atMs)
        assertTrue(navigator.isInstructionCurrent(beside, atMs, facing(90.0, atMs)))
    }

    private fun assertNoMovementCommand(text: String) {
        listOf("꺾으세요", "이동하세요", "직진하세요", "돌아가세요", "돌리세요", "맞추세요", "걸으세요").forEach {
            assertFalse("$it in $text", text.contains(it))
        }
    }

    private fun navigator() = RouteNavigator(allowDegradedRouteGuidance = false).apply {
        setRoute(cornerRoute(), origin = null)
    }

    private fun cornerRoute() = WalkingRoute("TEST", WalkingRouteSummary(100, 100),
        listOf(point(0.0), point(20.0), point(20.0, -80.0)),
        listOf(WalkingRouteGuidePoint(0, point(20.0), null, 20, 80, turnType = 13)))

    private fun point(eastM: Double, northM: Double = 0.0) =
        RoutePoint(northM / 111_194.92664455874, eastM / 111_194.92664455874)

    private fun fix(eastM: Double, northM: Double, accuracyM: Float, atMs: Long = 1_000L) =
        point(eastM, northM).let { TrustedLocation(it.latitude, it.longitude, accuracyM, atMs) }

    private fun facing(heading: Double, atMs: Long = 1_000L) = RouteFacingObservation(heading, 5.0, atMs)

    private companion object {
        const val STOP_AND_CHECK = "잠시 멈춰 주변 보행로를 확인한 뒤 다시 안내를 요청해 주세요."
    }
}

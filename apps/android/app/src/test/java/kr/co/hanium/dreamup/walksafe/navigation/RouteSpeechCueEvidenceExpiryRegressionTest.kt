package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteHeadingEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S08-25: new sensor input must not renew the original evidence of an already offered cue. */
class RouteSpeechCueEvidenceExpiryRegressionTest {
    @Test
    fun expiredOriginalObservationCannotBeRenewedByFreshFacingOfTheSameDirection() {
        for (generatedAt in listOf(1_000L, 1_200L)) {
            val navigator = navigator()
            val offered = navigator.update(fix(generatedAt), generatedAt, false,
                facingObservation = facing(1_000L))
            assertNotNull(offered.instruction)
            val token = requireNotNull(offered.speechCueToken)
            assertEquals(navigator.currentRouteId(), token.routeId)
            assertTrue(token.requiresFacingEvidence)
            assertEquals(1_000L, token.facingObservedAtElapsedRealtimeMs)
            assertEquals(1_500L, token.facingValidUntilElapsedRealtimeMs)
            assertTrue(navigator.reserveInstruction(offered))
            assertFalse("original observation=1000, generated=$generatedAt, playback=1600",
                navigator.isInstructionCurrent(offered, 1_600L, facing(1_600L)))
            assertFalse(navigator.isInstructionCurrent(offered, 1_501L, facing(1_501L)))
        }
    }

    @Test
    fun expiredUnreservedCueIsRegeneratedFromFreshEvidenceWithANewToken() {
        val navigator = navigator()
        val offered = navigator.update(fix(1_000L), 1_000L, false, facingObservation = facing(1_000L))
        val regenerated = requireNotNull(navigator.retryGuidance(1_600L, facing(1_600L)))
        assertNotNull(regenerated.instruction)
        assertNotEquals("a new observation must generate a new cue rather than relabel the old cue",
            offered.speechCueToken, regenerated.speechCueToken)
        assertFalse(navigator.isInstructionCurrent(offered, 1_600L, facing(1_600L)))
        assertTrue(navigator.reserveInstruction(regenerated))
        assertTrue(navigator.isInstructionCurrent(regenerated, 1_600L, facing(1_600L)))
    }

    @Test
    fun originalObservationRemainsValidAtTheInclusive500MillisecondBoundary() {
        for (generatedAt in listOf(1_000L, 1_200L)) {
            val navigator = navigator()
            val offered = navigator.update(fix(generatedAt), generatedAt, false,
                facingObservation = facing(1_000L))
            assertTrue(navigator.reserveInstruction(offered))
            assertTrue("original observation=1000, generated=$generatedAt, playback=1500",
                navigator.isInstructionCurrent(offered, 1_500L, facing(1_500L)))
        }
    }

    @Test
    fun unknownFacingOverviewDoesNotRequireADirectionLeaseButStillBelongsToItsRoute() {
        val navigator = navigator()
        val first = navigator.update(fix(1_000L), 1_000L, false, facingObservation = null)
        assertEquals("guidance_facing_recheck_wait", first.reason)
        assertNull(first.instruction)
        assertNull(first.speechCueToken)
        assertNull(navigator.retryGuidance(2_499L, null)?.instruction)
        val overview = requireNotNull(navigator.retryGuidance(2_500L, null))
        assertTrue(overview.instruction.orEmpty().contains("경로 기준 3시 방향 회전이 있습니다"))
        assertFalse(overview.instruction.orEmpty().contains("꺾으세요"))
        val token = requireNotNull(overview.speechCueToken)
        assertFalse(token.requiresFacingEvidence)
        assertNull(token.facingObservedAtElapsedRealtimeMs)
        assertNull(token.facingValidUntilElapsedRealtimeMs)
        assertTrue(navigator.reserveInstruction(overview))
        assertTrue(navigator.isInstructionCurrent(overview, 2_600L, null))
        navigator.setRoute(route())
        assertFalse(navigator.isInstructionCurrent(overview, 2_600L, null))
    }

    @Test
    fun cancelledCueLateCompletionCannotConsumeItsFreshReplacement() {
        val navigator = navigator()
        val old = navigator.update(fix(1_000L), 1_000L, false, facingObservation = facing(1_000L))
        assertTrue(navigator.reserveInstruction(old))
        navigator.releaseInstruction(old, 1_600L)
        val replacement = requireNotNull(navigator.retryGuidance(3_600L, facing(3_600L)))
        assertNotNull(replacement.instruction)
        assertNotEquals(old.speechCueToken, replacement.speechCueToken)
        assertTrue(navigator.reserveInstruction(replacement))
        navigator.acknowledgeInstruction(old, 3_650L)
        assertTrue(navigator.isInstructionCurrent(replacement, 3_650L, facing(3_650L)))
        navigator.acknowledgeInstruction(replacement, 3_700L)
        assertFalse(navigator.isInstructionCurrent(replacement, 3_700L, facing(3_700L)))
        assertNull(navigator.retryGuidance(3_701L, facing(3_701L))?.instruction)
    }

    @Test
    fun originalObservationAndDeadlineRemainImmutableUntilAFreshCueIsGenerated() {
        val navigator = navigator()
        val offered = navigator.update(fix(1_000L), 1_000L, false, facingObservation = facing(800L))
        val token = requireNotNull(offered.speechCueToken)
        assertEquals(800L, token.facingObservedAtElapsedRealtimeMs)
        assertEquals(1_300L, token.facingValidUntilElapsedRealtimeMs)
        val stillOffered = requireNotNull(navigator.retryGuidance(1_200L, facing(1_200L)))
        assertEquals(token, stillOffered.speechCueToken)
        assertTrue(navigator.isInstructionCurrent(stillOffered, 1_300L, facing(1_300L)))
        assertFalse(navigator.isInstructionCurrent(stillOffered, 1_301L, facing(1_301L)))
        val regenerated = requireNotNull(navigator.retryGuidance(1_301L, facing(1_301L)))
        assertNotEquals(token, regenerated.speechCueToken)
        assertEquals(1_301L, regenerated.speechCueToken?.facingObservedAtElapsedRealtimeMs)
        assertEquals(1_801L, regenerated.speechCueToken?.facingValidUntilElapsedRealtimeMs)
        assertTrue(navigator.isInstructionCurrent(regenerated, 1_301L, facing(1_301L)))
    }

    @Test
    fun missingFutureOrUnboundedDirectionEvidenceCannotAuthorizeAToken() {
        val missing = RouteSpeechCueToken(1L, 1L, null, -1)
        assertFalse(missing.hasCurrentFacingEvidence(1_000L))
        val valid = missing.copy(facingObservedAtElapsedRealtimeMs = 1_000L,
            facingValidUntilElapsedRealtimeMs = 1_500L)
        assertTrue(valid.hasCurrentFacingEvidence(1_000L))
        assertFalse(valid.copy(facingObservedAtElapsedRealtimeMs = 1_001L).hasCurrentFacingEvidence(1_000L))
        assertFalse(valid.copy(facingValidUntilElapsedRealtimeMs = Long.MAX_VALUE).hasCurrentFacingEvidence(1_000L))
        assertFalse(valid.copy(facingValidUntilElapsedRealtimeMs = 3_000L).hasCurrentFacingEvidence(1_600L))
    }

    @Test
    fun observationDeadlineCannotOverflowIntoAnUnlimitedLease() {
        val observedAt = Long.MAX_VALUE - 400L
        val deadline = requireNotNull(RouteFacingGuidance.observationValidUntilElapsedRealtimeMs(
            facing(observedAt), observedAt + 100L))
        assertEquals(Long.MAX_VALUE - 1L, deadline)
        val token = RouteSpeechCueToken(1L, 1L, null, -1, facingObservedAtElapsedRealtimeMs = observedAt,
            facingValidUntilElapsedRealtimeMs = deadline)
        assertTrue(token.hasCurrentFacingEvidence(observedAt + 100L))
        assertFalse(token.hasCurrentFacingEvidence(Long.MAX_VALUE))
    }

    @Test
    fun passageConfirmationMayStartWithoutAFacingObservationWhileItsRouteContextIsCurrent() {
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0L)).apply {
            setRoute(cornerRoute(100.0, 300))
        }
        val approach = cornerSample(navigator, 90.0, 1_000L, 90.0, 100.0)
        assertTrue(navigator.reserveInstruction(approach))
        navigator.acknowledgeInstruction(approach, 1_000L)
        val passing = cornerSample(navigator, 101.0, 2_000L, 180.0, 100.0)
        assertTrue(passing.instruction.orEmpty().contains("안내 지점 통과 여부를 확인 중입니다."))
        assertEquals(RouteFacingExpression.NOT_SPOKEN, passing.facingDiagnostic?.expression)
        assertFalse(requireNotNull(passing.speechCueToken).requiresFacingEvidence)
        assertTrue(navigator.reserveInstruction(passing))
        assertTrue("passage confirmation does not describe the current facing",
            navigator.isInstructionCurrent(passing, 2_010L, null))
        assertTrue(navigator.isInstructionCurrent(passing, 2_600L, null))
    }

    @Test
    fun routeRelativeNearTurnSurvivesHeadingChangesAndLossInBothStartValidationAndProducerRefresh() {
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0L)).apply {
            setRoute(cornerRoute(20.0, 120))
        }
        val approach = cornerSample(navigator, 10.0, 1_000L, 90.0, 20.0)
        assertTrue(navigator.reserveInstruction(approach))
        navigator.acknowledgeInstruction(approach, 1_000L)
        val vertex = cornerSample(navigator, 20.0, 2_000L, 180.0, 20.0)
        assertEquals(RouteAlignmentReason.TURN_PASSAGE_PENDING, vertex.routeAlignmentDiagnostic?.reason)
        assertTrue(vertex.instruction.orEmpty().startsWith("안내 지점 부근입니다. 경로 기준으로 "))
        assertTrue(vertex.instruction.orEmpty().contains("3시 방향으로 꺾으세요."))
        assertEquals(RouteFacingExpression.NOT_SPOKEN, vertex.facingDiagnostic?.expression)
        assertFalse(requireNotNull(vertex.speechCueToken).requiresFacingEvidence)
        assertTrue(navigator.reserveInstruction(vertex))
        val changed = RouteFacingObservation(270.0, 3.0, 2_010L)
        assertTrue(navigator.isInstructionCurrent(vertex, 2_010L, changed))
        val refreshed = requireNotNull(navigator.retryGuidance(2_010L, changed))
        assertEquals("guidance_in_flight", refreshed.reason)
        assertFalse(refreshed.cancelStaleNavigationSpeech)
        // This fixture's recent speed is (10 - hypot(3, 3)) m/s: at 501 ms it has not
        // exhausted the 3 m branch-travel allowance, while at 600 ms its context has expired.
        assertTrue(navigator.isInstructionCurrent(vertex, 2_501L, null))
        val withoutFacing = requireNotNull(navigator.currentGuidance(2_501L, null))
        assertEquals("guidance_in_flight", withoutFacing.reason)
        assertFalse(withoutFacing.cancelStaleNavigationSpeech)
        assertFalse("the branch-travel bound still expires a non-relative cue's route context",
            navigator.isInstructionCurrent(vertex, 2_600L, RouteFacingObservation(270.0, 3.0, 2_600L)))
    }

    @Test
    fun recoveredFacingStillRejectsTheOldUnknownOverviewAtPlaybackStart() {
        val navigator = navigator()
        val first = navigator.update(fix(1_000L), 1_000L, false, facingObservation = null)
        assertEquals("guidance_facing_recheck_wait", first.reason)
        assertNull(first.instruction)
        assertNull(first.speechCueToken)
        assertNull(navigator.retryGuidance(2_499L, null)?.instruction)
        val overview = requireNotNull(navigator.retryGuidance(2_500L, null))
        assertEquals(RouteFacingExpression.UNKNOWN, overview.facingDiagnostic?.expression)
        assertFalse(requireNotNull(overview.speechCueToken).requiresFacingEvidence)
        assertTrue(navigator.reserveInstruction(overview))
        assertTrue(navigator.isInstructionCurrent(overview, 2_550L, null))
        assertFalse("non-relative evidence exemption must not keep an obsolete unknown-facing description",
            navigator.isInstructionCurrent(overview, 2_600L, facing(2_600L)))
    }

    private fun cornerRoute(turnM: Double, totalM: Int) = WalkingRoute("TEST", WalkingRouteSummary(totalM, totalM),
        listOf(cornerPoint(0.0), cornerPoint(turnM), cornerPoint(turnM, -(totalM - turnM))), listOf(
            WalkingRouteGuidePoint(0, cornerPoint(turnM), null, turnM.toInt(), totalM - turnM.toInt(), turnType = 13),
            WalkingRouteGuidePoint(1, cornerPoint(turnM, -(totalM - turnM) * .75), null,
                (turnM + (totalM - turnM) * .75).toInt(), ((totalM - turnM) * .25).toInt(), turnType = 11)))

    private fun cornerSample(navigator: RouteNavigator, progressM: Double, at: Long, heading: Double,
        turnM: Double): RouteNavigatorUpdate {
        val point = if (progressM <= turnM) cornerPoint(progressM) else cornerPoint(turnM, turnM - progressM)
        val raw = TrustedLocation(point.latitude, point.longitude, 3f, at)
        return navigator.update(raw, at, false,
            FilteredRoutePosition(point, 3.0, at, heading = RouteHeadingEstimate(heading, 8.0)),
            facingObservation = RouteFacingObservation(heading, 5.0, at))
    }

    private fun cornerPoint(eastM: Double, northM: Double = 0.0) =
        RoutePoint(northM / 111194.92664455874, eastM / 111194.92664455874)

    private fun navigator() = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0L)).apply { setRoute(route()) }

    private fun route() = WalkingRoute("STAIR_AVOID", WalkingRouteSummary(89, 90),
        listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0, 127.001)),
        listOf(WalkingRouteGuidePoint(0, RoutePoint(37.0, 127.0009), "우회전", 80, 9, turnType = 13)))

    private fun fix(at: Long) = TrustedLocation(37.0, 127.0, 1f, at)
    private fun facing(at: Long) = RouteFacingObservation(90.0, 3.0, at)
}

package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteHeadingEstimate
import org.junit.Assert.*
import org.junit.Test

/** Synthetic sensor observations are independent inputs, not bearings borrowed from the matcher. */
class RouteSpeechEvidenceRegressionTest {
    @Test fun freshFacingChangesPreserveAnInFlightSentenceOnTheSameGuide() {
        val nav = navigator()
        val first = nav.update(fix(), 1_000, false, facingObservation = facing(1_000))
        assertTrue(nav.reserveInstruction(first))
        val turned = requireNotNull(nav.retryGuidance(1_100, facing(1_100, 0.0)))
        assertEquals("guidance_in_flight", turned.reason)
        assertFalse(turned.cancelStaleNavigationSpeech)
        nav.acknowledgeInstruction(first, 1_200)
        val manual = requireNotNull(nav.currentGuidance(1_201, facing(1_201, 0.0)))
        assertTrue(manual.instruction?.contains("안내한 방향으로 몸을 돌려주세요") == true)
        assertFalse(manual.instruction?.contains("꺾으세요") == true)
    }

    /** S08-23: a turn reversing the in-flight command's meaning is revoked like lost direction evidence. */
    @Test fun aReversingTurnRevokesTheInFlightSentenceAndRegeneratesItFromTheFreshFacing() {
        val nav = navigator()
        val first = nav.update(fix(), 1_000, false, facingObservation = facing(1_000))
        assertTrue(first.instruction?.contains("약 12시 방향") == true)
        assertTrue(nav.reserveInstruction(first))
        val reversed = requireNotNull(nav.retryGuidance(1_100, facing(1_100, 270.0)))
        assertEquals("route_guidance", reversed.reason)
        assertTrue(reversed.cancelStaleNavigationSpeech)
        assertTrue(reversed.instruction?.contains("방향이 확인되면 안내를 이어갑니다") == true)
        assertFalse(reversed.instruction?.contains("꺾으세요") == true)
        assertFalse(nav.isInstructionCurrent(first, 1_100, facing(1_100, 270.0)))
        assertFalse(nav.reserveInstruction(first))
        assertTrue(nav.reserveInstruction(reversed))
    }

    @Test fun adjacentTurnsFinishSpeakingButALeftRightReversalDoesNot() {
        val nav = navigator()
        val left = nav.update(fix(), 1_000, false, facingObservation = facing(1_000, 180.0))
        assertTrue(left.instruction?.contains("약 9시 방향") == true)
        assertTrue(nav.reserveInstruction(left))
        val adjacent = requireNotNull(nav.retryGuidance(1_100, facing(1_100, 90.0)))
        assertEquals("guidance_in_flight", adjacent.reason)
        assertFalse(adjacent.cancelStaleNavigationSpeech)
        val reversed = requireNotNull(nav.retryGuidance(1_200, facing(1_200, 0.0)))
        assertTrue(reversed.cancelStaleNavigationSpeech)
        assertTrue(reversed.instruction?.contains("방향이 확인되면 안내를 이어갑니다") == true)
        assertFalse(reversed.instruction?.contains("몸을 돌려주세요") == true)
        assertFalse(nav.reserveInstruction(left))
        assertTrue(nav.reserveInstruction(reversed))
    }

    @Test fun queuedDirectionChangeStillFailsTheActualStartGuard() {
        val nav = navigator()
        val queued = nav.update(fix(), 1_000, false, facingObservation = facing(1_000))
        assertTrue(nav.reserveInstruction(queued))
        assertFalse(nav.isInstructionCurrent(queued, 1_100, facing(1_100, 0.0)))
        nav.releaseInstruction(queued, 1_100)
        assertNull(nav.retryGuidance(3_099, facing(3_099, 0.0))?.instruction)
        val retry = requireNotNull(nav.retryGuidance(3_100, facing(3_100, 0.0)))
        assertNotEquals(queued.speechCueToken, retry.speechCueToken)
        assertTrue(nav.reserveInstruction(retry))
        nav.acknowledgeInstruction(queued, 3_101)
        assertEquals("guidance_in_flight", nav.retryGuidance(3_102, facing(3_102, 0.0))?.reason)
    }

    @Test fun missingFacingRevokesTheOldSentenceEvenWithANewManualRequestPending() {
        val nav = navigator()
        val first = nav.update(fix(), 1_000, false, facingObservation = facing(1_000))
        assertTrue(nav.reserveInstruction(first))
        assertEquals("guidance_in_flight", nav.currentGuidance(1_100, facing(1_100))?.reason)
        val lost = requireNotNull(nav.retryGuidance(1_200, null))
        assertTrue(lost.cancelStaleNavigationSpeech)
        assertFalse(lost.instruction?.contains("꺾으세요") == true)
        assertFalse(nav.isInstructionCurrent(first, 1_200, null))
        assertFalse(nav.reserveInstruction(first))
        assertTrue(nav.reserveInstruction(lost))
        nav.acknowledgeInstruction(first, 1_201)
        assertEquals("guidance_in_flight", nav.retryGuidance(1_202, null)?.reason)
    }

    @Test fun supportedNominalClockPreservesPlayingThenManualClockKeepsTheOriginalStartDeadline() {
        // Cold nominal confirmation uses the production fixed GRACE; the other tests isolate immediate revocation.
        val nav = RouteNavigator().apply { setRoute(WalkingRoute("TEST", WalkingRouteSummary(400, 400),
            listOf(point(0.0), point(400.0)),
            listOf(WalkingRouteGuidePoint(0, point(200.0), null, 200, 200, turnType = 13)))) }
        val first = nav.update(fix(), 1_000, false, facingObservation = facing(1_000))
        assertTrue(first.instruction?.contains("12시 방향") == true)
        assertTrue(nav.reserveInstruction(first))
        assertTrue(nav.isInstructionCurrent(first, 1_000, facing(1_000)))
        val continuing = requireNotNull(nav.retryGuidance(1_100, facing(1_100).copy(headingAccuracyDegrees = 20.0)))
        assertEquals("guidance_in_flight", continuing.reason)
        assertFalse(continuing.cancelStaleNavigationSpeech)
        assertFalse(nav.reserveInstruction(first))
        assertNull(continuing.instruction)
        assertNull(continuing.speechCueToken)
        val confirmed = facing(1_101).copy(headingAccuracyDegrees = 20.0)
        val samePacket = requireNotNull(nav.retryGuidance(1_101, confirmed))
        assertEquals("guidance_in_flight", samePacket.reason)
        assertFalse(samePacket.cancelStaleNavigationSpeech)
        assertNull(samePacket.instruction)
        assertNull(samePacket.speechCueToken)
        assertTrue(nav.isInstructionCurrent(first, 1_101, confirmed))
        assertTrue(nav.isInstructionCompletionCurrent(first, 1_101))
        assertEquals(1_000L, first.speechCueToken?.facingObservedAtElapsedRealtimeMs)
        assertEquals(1_500L, first.speechCueToken?.facingValidUntilElapsedRealtimeMs)
        nav.acknowledgeInstruction(first, 1_101)
        assertFalse(nav.isInstructionCompletionCurrent(first, 1_101))
        val coarse = requireNotNull(nav.currentGuidance(1_102, facing(1_102).copy(headingAccuracyDegrees = 20.0)))
        assertFalse(coarse.cancelStaleNavigationSpeech)
        assertTrue(coarse.instruction?.contains("바라보고 있는 방향 기준 약 12시 방향") == true)
        assertFalse(coarse.instruction.orEmpty().contains("시에서") || coarse.instruction.orEmpty().contains("사이 방향"))
        assertEquals(RouteFacingExpression.CLOCK, coarse.facingDiagnostic?.expression)
        assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK, coarse.facingDiagnostic?.clockReferenceKind)
        assertTrue(requireNotNull(coarse.speechCueToken).requiresFacingEvidence)
        assertFalse(nav.isInstructionCurrent(first, 1_102, facing(1_102).copy(headingAccuracyDegrees = 20.0)))
        assertFalse(nav.reserveInstruction(first))
        assertTrue(nav.reserveInstruction(coarse))
        assertEquals(1_102L, coarse.speechCueToken?.facingObservedAtElapsedRealtimeMs)
        assertEquals(1_602L, coarse.speechCueToken?.facingValidUntilElapsedRealtimeMs)
        assertTrue(nav.isInstructionCurrent(coarse, 1_602, facing(1_102).copy(headingAccuracyDegrees = 20.0)))
        assertFalse(nav.isInstructionCurrent(coarse, 1_603, facing(1_102).copy(headingAccuracyDegrees = 20.0)))
        assertFalse(nav.isInstructionCurrent(coarse, 1_603, facing(1_603).copy(headingAccuracyDegrees = 20.0)))
    }

    @Test fun failedManualDeliveryRetriesOnceAndLateFailureCannotConsumeItsReplacement() {
        val nav = navigator()
        val first = nav.update(fix(), 1_000, false, facingObservation = facing(1_000))
        assertTrue(nav.reserveInstruction(first))
        nav.acknowledgeInstruction(first, 2_000)
        val manual = requireNotNull(nav.currentGuidance(2_001, facing(2_001)))
        assertTrue(nav.reserveInstruction(manual))
        nav.releaseInstruction(manual, 2_002)
        assertEquals("guidance_retry_wait", nav.retryGuidance(4_001, facing(4_001))?.reason)
        val retry = requireNotNull(nav.retryGuidance(4_002, facing(4_002)))
        assertNotNull(retry.instruction)
        assertNotEquals(manual.speechCueToken, retry.speechCueToken)
        assertTrue(nav.reserveInstruction(retry))
        assertEquals("guidance_in_flight", nav.currentGuidance(4_003, facing(4_003))?.reason)
        nav.releaseInstruction(manual, 4_004)
        nav.acknowledgeInstruction(manual, 4_005)
        assertEquals("guidance_in_flight", nav.retryGuidance(4_006, facing(4_006))?.reason)
        nav.acknowledgeInstruction(retry, 5_000)
        val requestedDuringRetry = requireNotNull(nav.retryGuidance(5_001, facing(5_001)))
        assertNotNull(requestedDuringRetry.instruction)
        assertTrue(nav.reserveInstruction(requestedDuringRetry))
        nav.releaseInstruction(retry, 5_002)
        nav.acknowledgeInstruction(requestedDuringRetry, 6_000)
        assertNull(nav.retryGuidance(6_001, facing(6_001))?.instruction)
    }

    @Test fun absentStaleOrInaccurateFacingNeverBorrowsGpsCourseForMovement() {
        for (observation in listOf(null, facing(499), facing(1_000).copy(headingAccuracyDegrees = 31.0))) {
            val nav = navigator()
            val raw = fix()
            val filtered = FilteredRoutePosition(RoutePoint(raw.latitude, raw.longitude), 2.0,
                raw.elapsedRealtimeMs, heading = RouteHeadingEstimate(90.0, 5.0))
            val update = nav.update(raw, 1_000, false, filtered, facingObservation = observation)
            assertNotNull(nav.currentAcceptedRouteMatchFor(1_000))
            assertFalse(update.instruction?.contains("꺾으세요") == true)
            assertFalse(update.instruction?.contains("이동하세요") == true)
            assertTrue(update.instruction?.contains("방향이 확인되면 안내를 이어갑니다") == true)
        }
    }

    @Test fun independentFacingEnablesTheManeuverWithoutReplacingTheOriginalFixTime() {
        val nav = navigator()
        val raw = fix()
        val update = nav.update(raw, 1_200, false, facingObservation = facing(1_200))
        assertTrue(update.instruction?.contains("다음 안내 지점에서 경로 기준으로 3시 방향으로 꺾으세요") == true)
        assertNotNull(nav.currentAcceptedRouteMatchFor(raw.elapsedRealtimeMs))
        assertNull(nav.currentAcceptedRouteMatchFor(1_200))
        assertEquals(1_000L, raw.elapsedRealtimeMs)
    }

    // Isolate offered-cue validation and sensor provenance; default cancellation/grace is tested separately.
    private fun navigator() = RouteNavigator(RouteNavigatorConfig(directionRecheckGraceMs = 0L)).apply {
        setRoute(WalkingRoute("TEST", WalkingRouteSummary(400, 400),
            listOf(point(0.0), point(400.0)),
            listOf(WalkingRouteGuidePoint(0, point(200.0), null, 200, 200, turnType = 13))))
    }
    private fun point(eastM: Double) = RoutePoint(0.0, eastM / 111_194.92664455874)
    private fun fix() = point(50.0).let { TrustedLocation(it.latitude, it.longitude, 2f, 1_000) }
    private fun facing(nowMs: Long, degrees: Double = 90.0) = RouteFacingObservation(degrees, 5.0, nowMs)
}

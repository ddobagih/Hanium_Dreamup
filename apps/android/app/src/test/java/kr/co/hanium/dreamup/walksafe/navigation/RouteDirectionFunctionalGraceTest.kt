package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.*
import org.junit.Test

/** Production Nav, original observations and actual reserve/START/DONE boundaries. */
class RouteDirectionFunctionalGraceTest {
    @Test fun gpsExpiryRejectsAReservedFreshFacingCueAndItsLateDoneCannotConsumeTheNewCue() {
        val nav = navigator()
        val first = nav.update(fix(), 1_000L, false, facingObservation = good(1_000L))
        assertTrue(nav.reserveInstruction(first)); nav.acknowledgeInstruction(first, 1_100L)
        val queued = requireNotNull(nav.currentGuidance(10_800L, good(10_800L)))
        assertTrue(nav.reserveInstruction(queued))
        assertTrue(nav.isInstructionCurrent(queued, 10_801L, good(10_801L)))
        assertTrue(nav.isInstructionCompletionCurrent(queued, 10_801L))
        assertTrue("Facing is still within its original lease when GPS expires",
            requireNotNull(queued.speechCueToken).hasCurrentFacingEvidence(11_001L))
        // The real actuator validates its active request before delivering native START/DONE.
        assertFalse(nav.isInstructionCurrent(queued, 11_001L, good(11_001L)))
        assertFalse(nav.isInstructionCompletionCurrent(queued, 11_001L))
        nav.acknowledgeInstruction(queued, 11_001L)
        nav.update(fix().copy(elapsedRealtimeMs = 11_002L), 11_002L, false,
            facingObservation = good(11_002L))
        val fresh = requireNotNull(nav.currentGuidance(11_002L, good(11_002L)))
        assertTrue("Fresh input did not offer a reservable cue: ${fresh.reason}", nav.reserveInstruction(fresh))
        nav.acknowledgeInstruction(queued, 11_003L)
        assertTrue("The old callback must not complete the new positioning cue",
            nav.isInstructionCompletionCurrent(fresh, 11_003L))
    }

    @Test fun staleGpsContextRevokesTheCueWithoutRestartingTheUnresolvedDirectionFault() {
        val nav = RouteNavigator(RouteNavigatorConfig(directionRecheckGraceMs = 4_500L)).apply { setRoute(route()) }
        val first = nav.update(fix(), 1_000L, false, facingObservation = good(1_000L))
        assertTrue(nav.reserveInstruction(first)); nav.acknowledgeInstruction(first, 1_100L)
        nav.retryGuidance(1_200L, bad(1_200L))
        nav.retryGuidance(5_700L, bad(5_700L))
        assertEquals(1_200L, nav.currentDirectionGraceSnapshot()?.onsetElapsedRealtimeMs)
        assertEquals(5_700L, nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        assertFalse("Old GPS is not evidence of a measured body reversal",
            nav.hasHardInvalidatedDirectionClaim(11_001L, null))
        assertNull(nav.currentGuidance(11_001L, bad(11_001L)))
        assertFalse("The stale position still forbids the old relative cue",
            nav.isInstructionCurrent(first, 11_001L, good(11_001L)))
        assertEquals(1_200L, nav.currentDirectionGraceSnapshot()?.onsetElapsedRealtimeMs)
        assertEquals(5_700L, nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        val refreshedGps = fix().copy(elapsedRealtimeMs = 11_002L)
        nav.update(refreshedGps, 11_002L, false, facingObservation = bad(11_002L))
        assertEquals("GPS alone must not reset the ongoing direction failure", 1_200L,
            nav.currentDirectionGraceSnapshot()?.onsetElapsedRealtimeMs)
        assertEquals(5_700L, nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        nav.retryGuidance(11_003L, good(11_003L))
        assertFalse("A qualified new direction recovers without another timer", nav.isDirectionRecheckPending())
    }

    @Test fun shortQualityFaultPreservesPlayingUntilImmediateFreshRecovery() {
        val nav = navigator()
        val first = nav.update(fix(), 1_000L, false, facingObservation = good(1_000L))
        assertTrue(nav.reserveInstruction(first))
        assertTrue(nav.isInstructionCurrent(first, 1_100L, good(1_100L)))
        val bad = requireNotNull(nav.retryGuidance(1_200L, bad(1_200L)))
        assertEquals("guidance_in_flight", bad.reason)
        assertFalse(bad.cancelStaleNavigationSpeech)
        assertEquals(RouteDirectionGraceMode.GRACE_RETAINED, nav.currentDirectionGraceSnapshot()?.mode)
        val recovered = requireNotNull(nav.retryGuidance(1_500L, good(1_500L)))
        assertEquals("guidance_in_flight", recovered.reason)
        assertFalse(recovered.cancelStaleNavigationSpeech)
        assertFalse(nav.isDirectionRecheckPending())
        assertEquals(1_500L, nav.currentDirectionGraceSnapshot()?.retainedObservedAtElapsedRealtimeMs)
    }

    @Test fun repeatedBadCannotExtendTheExactDeadlineAndLateDoneCannotConsumeTheRetiredCue() {
        val nav = navigator()
        val first = nav.update(fix(), 1_000L, false, facingObservation = good(1_000L))
        assertTrue(nav.reserveInstruction(first))
        for (at in listOf(1_200L, 1_500L, 1_800L, 2_699L)) {
            val update = requireNotNull(nav.retryGuidance(at, bad(at)))
            assertFalse(update.cancelStaleNavigationSpeech)
            assertEquals(2_700L, nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        }
        val problem = requireNotNull(nav.retryGuidance(2_700L, bad(2_700L)))
        assertTrue(problem.cancelStaleNavigationSpeech)
        assertProblem(problem)
        assertEquals(RouteDirectionGraceMode.PROBLEM, nav.currentDirectionGraceSnapshot()?.mode)
        assertTrue(nav.reserveInstruction(problem))
        nav.acknowledgeInstruction(first, 2_701L)
        assertEquals("guidance_in_flight", nav.retryGuidance(2_702L, bad(2_702L))?.reason)
    }

    @Test fun aNewManualCueStartsDuringGraceWithTheUnchangedOriginalObservationLease() {
        val nav = navigator()
        val first = nav.update(fix(), 1_000L, false, facingObservation = good(1_000L))
        assertTrue(nav.reserveInstruction(first)); nav.acknowledgeInstruction(first, 1_100L)
        nav.retryGuidance(1_200L, bad(1_200L))
        val manual = requireNotNull(nav.currentGuidance(2_000L, bad(2_000L)))
        assertNumeric(manual)
        val token = requireNotNull(manual.speechCueToken)
        assertEquals(1_000L, token.facingObservedAtElapsedRealtimeMs)
        assertEquals(1_500L, token.facingValidUntilElapsedRealtimeMs)
        assertNotNull(token.directionGraceEpisodeId)
        assertEquals(1_200L, token.directionGraceStartedAtElapsedRealtimeMs)
        assertEquals(2_700L, token.directionGraceDeadlineElapsedRealtimeMs)
        assertTrue(nav.reserveInstruction(manual))
        assertTrue(nav.isInstructionCurrent(manual, 2_200L, bad(2_200L)))
        assertFalse(nav.isInstructionCurrent(manual, 2_700L, bad(2_700L)))
        nav.acknowledgeInstruction(manual, 2_300L)
        assertTrue(nav.isDirectionRecheckPending())
        assertEquals(2_700L, nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        assertProblem(requireNotNull(nav.retryGuidance(2_700L, bad(2_700L))))
    }

    @Test fun anOrdinaryQueuedCueDoesNotAcquireTheSeparateGraceAuthorization() {
        val nav = navigator()
        val queued = nav.update(fix(), 1_000L, false, facingObservation = good(1_000L))
        assertTrue(nav.reserveInstruction(queued))
        assertNull(queued.speechCueToken?.directionGraceEpisodeId)
        assertFalse(nav.isInstructionCurrent(queued, 1_600L, good(1_600L)))
    }

    @Test fun lateSampleGapIsAnchoredAtOriginalLeaseExpiryAndCannotReviveAnAncientDirection() {
        val nav = navigator()
        nav.update(fix(), 1_000L, false, facingObservation = good(1_000L))
        val problem = requireNotNull(nav.currentGuidance(4_000L, null))
        assertProblem(problem)
        assertEquals(1_501L, nav.currentDirectionGraceSnapshot()?.onsetElapsedRealtimeMs)
        assertEquals(3_001L, nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        assertFalse(requireNotNull(problem.speechCueToken).requiresFacingEvidence)
    }

    @Test fun sameSensorTimestampCannotRecoverOrRenewAnEpisode() {
        val nav = navigator()
        nav.update(fix(), 1_000L, false, facingObservation = good(1_000L))
        nav.retryGuidance(1_200L, bad(1_200L))
        val id = nav.currentDirectionGraceSnapshot()?.episodeId
        nav.retryGuidance(1_300L, good(1_200L))
        assertEquals(id, nav.currentDirectionGraceSnapshot()?.episodeId)
        assertEquals(2_700L, nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        nav.retryGuidance(1_400L, good(1_400L))
        assertFalse(nav.isDirectionRecheckPending())
        nav.retryGuidance(1_500L, bad(1_500L))
        assertEquals(3_000L, nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        assertNotEquals(id, nav.currentDirectionGraceSnapshot()?.episodeId)
    }

    @Test fun reversalIsHardButQualityLossAndCoarseUnknownAreSoft() {
        val nav = navigator()
        val first = nav.update(fix(), 1_000L, false, facingObservation = good(1_000L))
        assertTrue(nav.reserveInstruction(first))
        assertFalse(nav.hasHardInvalidatedDirectionClaim(1_200L, bad(1_200L)))
        val unknown = good(1_200L).copy(degreesTrueNorth = 68.0, headingAccuracyDegrees = 18.3,
            reportedHeadingAccuracyDegrees = 12.0)
        assertEquals(RouteFacingDirection.UNKNOWN, RouteFacingGuidance.evaluate(90.0, unknown, 1_200L).direction)
        assertFalse(nav.hasHardInvalidatedDirectionClaim(1_200L, unknown))
        assertFalse(requireNotNull(nav.retryGuidance(1_200L, unknown)).cancelStaleNavigationSpeech)
        val reverse = good(1_300L).copy(degreesTrueNorth = 270.0)
        assertTrue(nav.hasHardInvalidatedDirectionClaim(1_300L, reverse))
        assertTrue(requireNotNull(nav.retryGuidance(1_300L, reverse)).cancelStaleNavigationSpeech)
        assertFalse(nav.isInstructionCurrent(first, 1_301L, good(1_301L)))
    }

    @Test fun coldMissingMeasurementsNeverCreateNumericDirection() {
        val nav = navigator()
        assertNull(nav.update(fix(), 1_000L, false, facingObservation = null).instruction)
        assertNull(nav.currentGuidance(2_000L, bad(2_000L))?.instruction)
        assertProblem(requireNotNull(nav.retryGuidance(2_500L, bad(2_500L))))
        assertNull(nav.currentDirectionGraceSnapshot()?.retainedObservedAtElapsedRealtimeMs)
    }

    @Test fun aClockRollbackAndNewRouteRetireRetainedAuthorization() {
        for (replaceRoute in listOf(false, true)) {
            val nav = navigator()
            val first = nav.update(fix(), 1_000L, false, facingObservation = good(1_000L))
            assertTrue(nav.reserveInstruction(first)); nav.acknowledgeInstruction(first, 1_100L)
            nav.retryGuidance(1_200L, bad(1_200L))
            val grace = requireNotNull(nav.currentGuidance(1_400L, bad(1_400L)))
            assertTrue(nav.reserveInstruction(grace))
            if (replaceRoute) nav.setRoute(route()) else nav.retryGuidance(1_300L, bad(1_300L))
            assertFalse(nav.isInstructionCurrent(grace, 1_450L, bad(1_450L)))
            assertNull(nav.currentDirectionGraceSnapshot()?.retainedObservedAtElapsedRealtimeMs)
        }
    }

    @Test fun sensorProblemIsPublishedAtItsDeadlineEvenDuringTransportBackoff() {
        val nav = navigator()
        val first = nav.update(fix(), 1_000L, false, facingObservation = good(1_000L))
        assertTrue(nav.reserveInstruction(first)); nav.releaseInstruction(first, 1_100L)
        assertEquals("guidance_retry_wait", nav.retryGuidance(1_200L, bad(1_200L))?.reason)
        assertEquals("guidance_retry_wait", nav.retryGuidance(2_699L, bad(2_699L))?.reason)
        assertProblem(requireNotNull(nav.retryGuidance(2_700L, bad(2_700L))))
    }

    @Test fun nominalClockRetainsTheReportedAccuracyAndUsesANewGraceCue() {
        val nav = navigator()
        val first = good(1_000L).copy(headingAccuracyDegrees = 12.0, reportedHeadingAccuracyDegrees = 12.0)
        assertEquals("guidance_facing_recheck_wait", nav.update(fix(), 1_000L, false, facingObservation = first).reason)
        val nominal = requireNotNull(nav.retryGuidance(1_050L, first.copy(observedAtElapsedRealtimeMs = 1_050L)))
        assertEquals(RouteFacingExpression.CLOCK, nominal.facingDiagnostic?.expression)
        assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK, nominal.facingDiagnostic?.clockReferenceKind)
        assertFalse(nominal.instruction.orEmpty().contains("시 사이"))
        assertTrue(nav.reserveInstruction(nominal)); nav.acknowledgeInstruction(nominal, 1_100L)
        nav.retryGuidance(1_200L, bad(1_200L))
        val retained = requireNotNull(nav.currentGuidance(2_000L, bad(2_000L)))
        assertEquals(RouteFacingExpression.CLOCK, retained.facingDiagnostic?.expression)
        assertEquals(12.0, nav.currentDirectionGraceSnapshot()?.retainedReportedHeadingAccuracyDegrees)
        assertTrue(nav.reserveInstruction(retained))
        assertTrue(nav.isInstructionCurrent(retained, 2_200L, bad(2_200L)))
    }

    private fun assertNumeric(update: RouteNavigatorUpdate) {
        assertTrue(update.instruction.orEmpty(), update.instruction.orEmpty().contains("약 12시 방향"))
        assertTrue(requireNotNull(update.speechCueToken).requiresFacingEvidence)
    }
    private fun assertProblem(update: RouteNavigatorUpdate) {
        assertNotNull(update.instruction)
        assertEquals(RouteFacingExpression.UNKNOWN, update.facingDiagnostic?.expression)
        assertFalse(update.instruction.orEmpty().contains("카메라 기준 약"))
        assertFalse(update.instruction.orEmpty().contains("몸을 돌리세요"))
    }
    private fun navigator() = RouteNavigator().apply { setRoute(route()) }
    private fun route() = WalkingRoute("TEST", WalkingRouteSummary(200, 200),
        listOf(point(0.0), point(100.0), point(100.0, -100.0)),
        listOf(WalkingRouteGuidePoint(0, point(100.0), null, 100, 100, turnType = 13)))
    private fun point(eastM: Double, northM: Double = 0.0) =
        RoutePoint(northM / 111_194.92664455874, eastM / 111_194.92664455874)
    private fun fix() = point(50.0).let { TrustedLocation(it.latitude, it.longitude, 2f, 1_000L) }
    private fun good(at: Long) = RouteFacingObservation(90.0, 3.0, at)
    private fun bad(at: Long) = RouteFacingObservation(90.0, 31.0, at)
}

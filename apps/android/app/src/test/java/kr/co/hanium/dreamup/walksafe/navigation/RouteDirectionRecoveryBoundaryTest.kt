package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.*
import org.junit.Test

/** Follow-up utility boundaries; production Nav and original evidence, no restamped samples. */
class RouteDirectionRecoveryBoundaryTest {
    @Test fun aQueuedGracePacketStartsImmediatelyAfterFullySupportingFreshRecovery() {
        val nav = navigator()
        val first = nav.update(fix(1_000L), 1_000L, false, facingObservation = good(1_000L))
        assertTrue(nav.reserveInstruction(first)); nav.acknowledgeInstruction(first, 1_100L)
        nav.retryGuidance(1_200L, bad(1_200L))
        val queued = requireNotNull(nav.currentGuidance(2_000L, bad(2_000L)))
        assertTrue(nav.reserveInstruction(queued))
        val recovered = requireNotNull(nav.retryGuidance(2_100L, good(2_100L)))
        assertFalse(recovered.cancelStaleNavigationSpeech)
        assertEquals("guidance_in_flight", recovered.reason)
        assertEquals(RouteDirectionGraceMode.NORMAL, nav.currentDirectionGraceSnapshot()?.mode)
        assertTrue("Fresh GOOD fully supports this exact GRACE packet", nav.isInstructionCurrent(queued, 2_200L, good(2_200L)))
        assertEquals(1_000L, queued.speechCueToken?.facingObservedAtElapsedRealtimeMs)
        assertEquals(1_500L, queued.speechCueToken?.facingValidUntilElapsedRealtimeMs)
        assertEquals(2_700L, queued.speechCueToken?.directionGraceDeadlineElapsedRealtimeMs)
        assertTrue(nav.isInstructionCompletionCurrent(queued, 2_300L))
        nav.acknowledgeInstruction(queued, 2_300L)
        assertEquals("guidance_rate_limited", nav.retryGuidance(2_301L, good(2_301L))?.reason)
    }

    @Test fun recoveredGraceAuthorizationIsPacketSpecificBoundedAndNeedsLiveFullSupport() {
        val nav = navigator()
        val first = nav.update(fix(1_000L), 1_000L, false, facingObservation = good(1_000L))
        assertTrue(nav.reserveInstruction(first)); nav.acknowledgeInstruction(first, 1_100L)
        nav.retryGuidance(1_200L, bad(1_200L))
        val queued = requireNotNull(nav.currentGuidance(2_000L, bad(2_000L)))
        assertTrue(nav.reserveInstruction(queued)); nav.retryGuidance(2_100L, good(2_100L))
        assertFalse(nav.isInstructionCurrent(first, 2_200L, good(2_200L)))
        assertFalse(nav.isInstructionCurrent(queued, 2_200L, bad(2_200L)))
        assertFalse(nav.isInstructionCurrent(queued, 2_200L, good(2_200L).copy(degreesTrueNorth = 270.0)))
        assertFalse(nav.isInstructionCurrent(queued, 2_700L, good(2_700L)))
        assertTrue(nav.isInstructionCurrent(queued, 2_699L, good(2_699L)))
    }

    @Test fun anOrdinaryNormalQueueStillExpiresAtItsOriginalFiveHundredMilliseconds() {
        val nav = navigator()
        val ordinary = nav.update(fix(1_000L), 1_000L, false, facingObservation = good(1_000L))
        assertTrue(nav.reserveInstruction(ordinary))
        assertFalse(nav.isDirectionRecheckPending())
        assertNull(ordinary.speechCueToken?.directionGraceEpisodeId)
        assertFalse(nav.isInstructionCurrent(ordinary, 1_600L, good(1_600L)))
    }

    @Test fun aOneMeterGpsShiftKeepsTheSameGraceAndAllowsANewClosestTargetCue() {
        val nav = RouteNavigator().apply {
            setRoute(WalkingRoute("TEST", WalkingRouteSummary(300, 300),
                listOf(point(0.0), point(300.0)), emptyList()), waitForInitialRouteJoin = true)
        }
        val first = nav.update(fix(1_000L, -20.0, 20.0), 1_000L, false,
            facingObservation = good(1_000L).copy(degreesTrueNorth = 135.0))
        assertTrue(first.instruction.orEmpty(), first.instruction.orEmpty().contains("약 12시 방향"))
        assertTrue(first.directionOnly)
        assertTrue(nav.reserveInstruction(first)); nav.acknowledgeInstruction(first, 1_100L)
        nav.retryGuidance(1_200L, bad(1_200L))
        val episode = nav.currentDirectionGraceSnapshot()?.episodeId
        val oldBearing = first.routeAlignmentDiagnostic?.bearingDegreesTrueNorth
        val shifted = nav.update(fix(1_400L, -19.0, 20.0), 1_400L, false, facingObservation = bad(1_400L))
        assertNotEquals(oldBearing, shifted.routeAlignmentDiagnostic?.bearingDegreesTrueNorth)
        assertFalse(shifted.cancelStaleNavigationSpeech)
        assertEquals(RouteDirectionGraceMode.GRACE_RETAINED, nav.currentDirectionGraceSnapshot()?.mode)
        assertEquals(episode, nav.currentDirectionGraceSnapshot()?.episodeId)
        assertEquals(2_700L, nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        val manual = requireNotNull(nav.currentGuidance(1_600L, bad(1_600L)))
        assertTrue(manual.instruction.orEmpty(), manual.instruction.orEmpty().contains("약 12시 방향"))
        assertTrue(manual.instruction.orEmpty().contains("직선거리 약"))
        assertEquals(1_000L, manual.speechCueToken?.facingObservedAtElapsedRealtimeMs)
        assertEquals(1_500L, manual.speechCueToken?.facingValidUntilElapsedRealtimeMs)
        assertTrue(nav.reserveInstruction(manual))
        assertTrue(nav.isInstructionCurrent(manual, 1_800L, bad(1_800L)))
        assertNull(nav.currentAcceptedRouteMatchFor(1_400L))
        assertTrue(nav.isWaitingForInitialRouteJoin())
    }

    @Test fun completionAfterTheSensorDeadlineIsRejectedEvenIfAGoodSampleChangedTheRetainedTimestamp() {
        val nav = navigator()
        val playing = nav.update(fix(1_000L), 1_000L, false, facingObservation = good(1_000L))
        assertTrue(nav.reserveInstruction(playing))
        nav.retryGuidance(1_100L, good(1_100L))
        nav.retryGuidance(1_200L, bad(1_200L))
        assertEquals(1_100L, nav.currentDirectionGraceSnapshot()?.retainedObservedAtElapsedRealtimeMs)
        assertTrue(nav.isInstructionCompletionCurrent(playing, 2_699L))
        assertFalse("Native DONE arrives before the next UI evaluation", nav.isInstructionCompletionCurrent(playing, 2_700L))
        nav.acknowledgeInstruction(playing, 2_700L)
        assertEquals(RouteDirectionGraceMode.PROBLEM, nav.retryGuidance(2_700L, bad(2_700L))?.let {
            nav.currentDirectionGraceSnapshot()?.mode
        })
    }

    @Test fun activeGraceRejectsFreshContradictingClockBeforeTheUiRetryConsumesIt() {
        val nav = navigator()
        val first = nav.update(fix(1_000L), 1_000L, false, facingObservation = good(1_000L))
        assertTrue(nav.reserveInstruction(first)); nav.acknowledgeInstruction(first, 1_100L)
        nav.retryGuidance(1_200L, bad(1_200L))
        val queued = requireNotNull(nav.currentGuidance(2_000L, bad(2_000L)))
        assertTrue(nav.reserveInstruction(queued))
        val contradicting = good(2_100L).copy(degreesTrueNorth = 150.0)
        assertFalse(nav.hasHardInvalidatedDirectionClaim(2_100L, contradicting))
        assertEquals(10, RouteFacingGuidance.evaluate(90.0, contradicting, 2_100L).clockHour)
        assertFalse(nav.isInstructionCurrent(queued, 2_100L, contradicting))
        // A quality loss alone still uses the retained reference in this same episode.
        assertTrue(nav.isInstructionCurrent(queued, 2_200L, bad(2_200L)))
    }

    @Test fun cachedGeometryClassificationDoesNotPromoteOldOrBadEvidenceToCurrentFreshEvidence() {
        val observation = good(1_000L).copy(degreesTrueNorth = 135.0)
        assertFalse(RouteFacingGuidance.isUsableObservation(observation, 2_000L))
        assertNull(RouteFacingGuidance.evaluate(136.0, observation, 2_000L, 4.0).clockHour)
        assertEquals(12, RouteFacingGuidance.evaluateRetainedReference(136.0, observation, 1_000L, 4.0).clockHour)
        assertFalse(RouteFacingGuidance.evaluateRetainedReference(136.0, observation, 1_000L, 180.0).hasClockReference)
        assertFalse(RouteFacingGuidance.evaluateRetainedReference(136.0, bad(1_000L), 1_000L, 4.0).hasClockReference)
        assertFalse(RouteFacingGuidance.evaluateRetainedReference(136.0, observation, 1_501L, 4.0).hasClockReference)
        assertEquals(1_000L, observation.observedAtElapsedRealtimeMs)
        assertEquals(3.0, observation.reportedHeadingAccuracyDegrees)
    }

    @Test fun crossingToTheOppositeSideCannotReuseTheCachedInitialApproachDirection() {
        val nav = RouteNavigator().apply {
            setRoute(WalkingRoute("TEST", WalkingRouteSummary(300, 300),
                listOf(point(0.0), point(300.0)), emptyList()), waitForInitialRouteJoin = true)
        }
        val first = nav.update(fix(1_000L, -20.0, 20.0), 1_000L, false,
            facingObservation = good(1_000L).copy(degreesTrueNorth = 135.0))
        assertTrue(nav.reserveInstruction(first))
        nav.retryGuidance(1_200L, bad(1_200L))
        val episode = nav.currentDirectionGraceSnapshot()?.episodeId
        val changed = nav.update(fix(1_800L, -19.0, -20.0), 1_800L, false, facingObservation = bad(1_800L))
        assertTrue(changed.cancelStaleNavigationSpeech)
        assertNull(changed.instruction)
        assertEquals(episode, nav.currentDirectionGraceSnapshot()?.episodeId)
        assertEquals(2_700L, nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        assertEquals(RouteDirectionGraceMode.RECHECK_WAIT, nav.currentDirectionGraceSnapshot()?.mode)
        assertFalse(nav.isInstructionCurrent(first, 1_801L, bad(1_801L)))
        assertNull(nav.currentAcceptedRouteMatchFor(1_800L))
    }

    @Test fun anExactNormalPacketQualifiedAtBadOnsetOwnsASeparateGraceGrantWithoutRestamping() {
        val nav = navigator()
        val normal = nav.update(fix(1_000L), 1_000L, false, facingObservation = good(1_000L))
        assertTrue(nav.reserveInstruction(normal))
        nav.retryGuidance(1_100L, good(1_100L))
        nav.retryGuidance(1_200L, bad(1_200L))
        assertFalse(requireNotNull(normal.speechCueToken).hasCurrentFacingEvidence(1_600L))
        assertTrue(nav.isInstructionCurrent(normal, 1_600L, bad(1_600L)))
        assertEquals(1_000L, normal.speechCueToken?.facingObservedAtElapsedRealtimeMs)
        assertEquals(1_500L, normal.speechCueToken?.facingValidUntilElapsedRealtimeMs)
        assertEquals(2_700L, nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        assertEquals(normal.speechCueToken?.cueRevision, nav.currentDirectionGraceSnapshot()?.authorizedCueRevision)
        assertEquals(RouteDirectionGraceAuthorization.NORMAL_PENDING, nav.currentDirectionGraceSnapshot()?.authorizationKind)
        assertFalse(nav.isInstructionCurrent(normal, 1_600L, good(1_600L).copy(degreesTrueNorth = 150.0)))
        assertFalse(nav.isInstructionCurrent(normal, 2_700L, bad(2_700L)))
    }

    @Test fun retiredOwnerReceiptsCannotAnchorTheNewOwnerAndTheEarliestActualReceiptWins() {
        val nav = navigator()
        val normal = nav.update(fix(1_000L), 1_000L, false, facingObservation = good(1_000L))
        assertTrue(nav.reserveInstruction(normal))
        val revision = nav.currentTactileRouteProgress().routeVersion
        nav.recordFirstDirectionFaultReceipt(1_050L, revision) { false }
        nav.recordFirstDirectionFaultReceipt(1_060L, requireNotNull(revision) + 1L) { true }
        nav.recordFirstDirectionFaultReceipt(1_100L, revision) { true }
        nav.recordFirstDirectionFaultReceipt(1_200L, revision) { true }
        nav.retryGuidance(1_300L, bad(1_300L), null, Long.MAX_VALUE)
        assertEquals(1_100L, nav.currentDirectionGraceSnapshot()?.onsetElapsedRealtimeMs)
        assertEquals(2_600L, nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        assertTrue(nav.isInstructionCurrent(normal, 1_600L, bad(1_600L)))
        nav.retryGuidance(1_700L, bad(1_700L), null, 1_500L)
        assertEquals(2_600L, nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        nav.invalidateDirectionClaim()
        assertNull(nav.currentDirectionGraceSnapshot())
        assertFalse(nav.isInstructionCurrent(normal, 1_800L, bad(1_800L)))
    }

    @Test fun unsupportedGeometryRetiresNumericSpeechOnceAndLetsTheRecoveryExplanationFinish() {
        val nav = RouteNavigator().apply {
            setRoute(WalkingRoute("TEST", WalkingRouteSummary(300, 300),
                listOf(point(0.0), point(300.0)), emptyList()), waitForInitialRouteJoin = true)
        }
        val numeric = nav.update(fix(1_000L, -20.0, 20.0), 1_000L, false,
            facingObservation = good(1_000L).copy(degreesTrueNorth = 135.0))
        assertTrue(nav.reserveInstruction(numeric))
        nav.retryGuidance(1_200L, bad(1_200L))
        val unsupported = nav.update(fix(1_800L, -19.0, -20.0), 1_800L, false, facingObservation = bad(1_800L))
        assertTrue(unsupported.cancelStaleNavigationSpeech)
        assertNull(unsupported.instruction)
        val problem = requireNotNull(nav.retryGuidance(2_700L, bad(2_700L)))
        assertNotNull(problem.instruction)
        assertFalse(requireNotNull(problem.speechCueToken).requiresFacingEvidence)
        assertTrue(nav.reserveInstruction(problem))
        assertTrue(nav.isInstructionCurrent(problem, 2_700L, bad(2_700L)))
        for (at in listOf(2_701L, 2_800L, 3_000L)) {
            val continuing = requireNotNull(nav.retryGuidance(at, bad(at)))
            assertEquals("guidance_in_flight", continuing.reason)
            assertNull(continuing.instruction)
            assertFalse("Same nonnumeric explanation must not be restarted at $at", continuing.cancelStaleNavigationSpeech)
            assertTrue(nav.isInstructionCurrent(problem, at, bad(at)))
        }
        assertTrue(nav.isInstructionCompletionCurrent(problem, 3_100L))
        nav.acknowledgeInstruction(problem, 3_100L)
        assertEquals(RouteDirectionGraceMode.PROBLEM, nav.currentDirectionGraceSnapshot()?.mode)
        assertFalse(nav.isInstructionCompletionCurrent(numeric, 3_101L))
        assertNull(nav.currentAcceptedRouteMatchFor(1_800L))
    }

    private fun navigator() = RouteNavigator().apply { setRoute(WalkingRoute("TEST", WalkingRouteSummary(200, 200),
        listOf(point(0.0), point(100.0), point(100.0, -100.0)),
        listOf(WalkingRouteGuidePoint(0, point(100.0), null, 100, 100, turnType = 13)))) }
    private fun point(eastM: Double, northM: Double = 0.0) = RoutePoint(northM / 111_194.92664455874, eastM / 111_194.92664455874)
    private fun fix(at: Long, eastM: Double = 50.0, northM: Double = 0.0) = point(eastM, northM).let {
        TrustedLocation(it.latitude, it.longitude, 2f, at)
    }
    private fun good(at: Long) = RouteFacingObservation(90.0, 3.0, at)
    private fun bad(at: Long) = RouteFacingObservation(90.0, 31.0, at)
}

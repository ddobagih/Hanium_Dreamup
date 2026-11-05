package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.*
import org.junit.Test

/** Uses the production 1.5 second default, with real Navigator offer/reserve/START/DONE calls. */
class RouteDirectionRecheckGraceTest {
    @Test fun initialMissingHeadingWaitsUntilTheExactDefaultBoundaryWithoutLosingTheRoute() {
        assertEquals(1_500L, RouteNavigatorConfig().directionRecheckGraceMs)
        val nav = navigator()
        assertWaiting(nav.update(fix(), 1_000L, false, facingObservation = null))
        assertWaiting(nav.retryGuidance(2_499L, null))
        val recheck = requireNotNull(nav.retryGuidance(2_500L, null))
        assertRecheck(recheck)
        assertTrue(recheck.instruction.orEmpty().contains("경로 기준 3시 방향 회전이 있습니다"))
        assertTrue(nav.hasRoute())
        assertNotNull(nav.currentAcceptedRouteMatchFor(1_000L))
        assertTrue(nav.reserveInstruction(recheck))
        assertTrue(nav.isInstructionCurrent(recheck, 2_500L, null))
    }

    @Test fun repeatedChecksDoNotRestartTheEpisodeTimer() {
        val nav = navigator()
        assertWaiting(nav.update(fix(), 1_000L, false, facingObservation = null))
        for (at in listOf(1_000L, 1_300L, 1_600L, 1_900L, 2_200L, 2_499L)) {
            assertWaiting(nav.retryGuidance(at, null))
        }
        assertRecheck(requireNotNull(nav.retryGuidance(2_500L, null)))
    }

    @Test fun aShortQualitySpikePreservesPlayingAndRecoversWithoutARecheckUtterance() {
        val nav = navigator()
        val first = nav.update(fix(), 1_000L, false, facingObservation = facing(1_000L))
        assertTrue(nav.reserveInstruction(first))
        val held = requireNotNull(nav.retryGuidance(1_200L, imprecise(1_200L)))
        assertEquals("guidance_in_flight", held.reason)
        assertFalse(held.cancelStaleNavigationSpeech)
        assertEquals(2_700L, nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        // Exact pending packet has a separate GRACE grant; original normal timestamps stay intact.
        assertTrue(nav.isInstructionCurrent(first, 1_200L, imprecise(1_200L)))
        assertEquals(RouteDirectionGraceAuthorization.NORMAL_PENDING, nav.currentDirectionGraceSnapshot()?.authorizationKind)
        assertFalse(nav.reserveInstruction(first))
        val recovered = requireNotNull(nav.retryGuidance(1_500L, facing(1_500L)))
        assertEquals("guidance_in_flight", recovered.reason)
        assertFalse(recovered.cancelStaleNavigationSpeech)
        assertFalse(nav.isDirectionRecheckPending())
        nav.acknowledgeInstruction(first, 1_501L)
        assertEquals("guidance_rate_limited", nav.retryGuidance(1_502L, facing(1_502L))?.reason)
    }

    @Test fun aSampleGapPreservesPlayingUntilTheOriginalLeasePlusGraceDeadline() {
        val nav = navigator()
        val first = nav.update(fix(), 1_000L, false, facingObservation = facing(1_000L))
        assertTrue(nav.reserveInstruction(first))
        val stale = requireNotNull(nav.retryGuidance(1_501L, facing(1_000L)))
        assertEquals("guidance_in_flight", stale.reason)
        assertFalse(stale.cancelStaleNavigationSpeech)
        assertFalse(nav.isInstructionCurrent(first, 1_501L, facing(1_000L)))
        assertEquals(1_501L, nav.currentDirectionGraceSnapshot()?.onsetElapsedRealtimeMs)
        val retained = requireNotNull(nav.retryGuidance(3_000L, facing(1_000L)))
        assertEquals("guidance_in_flight", retained.reason)
        assertFalse(retained.cancelStaleNavigationSpeech)
        val problem = requireNotNull(nav.retryGuidance(3_001L, facing(1_000L)))
        assertTrue(problem.cancelStaleNavigationSpeech)
        assertRecheck(problem)
    }

    @Test fun aRecoveredDirectionStartsANewGraceIfItBecomesUncertainAgain() {
        val nav = navigator()
        assertWaiting(nav.update(fix(), 1_000L, false, facingObservation = null))
        assertClock(requireNotNull(nav.retryGuidance(2_000L, facing(2_000L))), 12)
        assertRetainedClock(nav, nav.retryGuidance(2_100L, imprecise(2_100L)), 12, 3_600L)
        assertRetainedClock(nav, nav.retryGuidance(3_599L, imprecise(3_599L)), 12, 3_600L)
        assertRecheck(requireNotNull(nav.retryGuidance(3_600L, imprecise(3_600L))))
    }

    @Test fun anUnconfirmedNewHourWaitsForTwoDistinctSensorSamplesWithoutSpeakingACoarseTurn() {
        val nav = navigator()
        val old = nav.update(fix(), 1_000L, false, facingObservation = facing(1_000L))
        assertClock(old, 12)
        assertRetainedClock(nav, nav.currentGuidance(1_100L, facing(1_100L, heading = 0.0)), 12, 2_600L)
        assertRetainedClock(nav, nav.currentGuidance(1_200L, facing(1_100L, heading = 0.0)), 12, 2_600L)
        assertFalse(nav.isInstructionCurrent(old, 1_200L, facing(1_100L, heading = 0.0)))
        val confirmed = requireNotNull(nav.currentGuidance(1_300L, facing(1_300L, heading = 0.0)))
        assertClock(confirmed, 3)
        assertFalse(nav.isDirectionRecheckPending())
        assertTrue(confirmed.instruction.orEmpty().contains("안내한 방향으로 몸을 돌려주세요"))
    }

    @Test fun aManualRequestWaitsForTheGraceButKeepsItsIntent() {
        val nav = navigator()
        assertWaiting(nav.update(fix(), 1_000L, false, facingObservation = null))
        assertWaiting(nav.currentGuidance(1_100L, null))
        assertRecheck(requireNotNull(nav.retryGuidance(2_500L, null)))
    }

    @Test fun changingSourceKeepsTheFirstDeadlineUntilDirectionIsActuallyConfirmed() {
        val nav = navigator()
        val fallback = RouteFacingObservation(90.0, null, 1_000L,
            RouteFacingSource.GRAVITY_MAGNETIC, RouteFacingSensorQuality.CALIBRATED_SENSOR_CHECKS)
        assertWaiting(nav.update(fix(), 1_000L, false, facingObservation = fallback))
        assertWaiting(nav.retryGuidance(2_000L, imprecise(2_000L)))
        assertWaiting(nav.retryGuidance(2_499L, imprecise(2_000L)))
        assertRecheck(requireNotNull(nav.retryGuidance(2_500L, imprecise(2_500L))))
        assertTrue(nav.isDirectionRecheckPending())
    }

    @Test fun absentAndStaleSamplesOfTheSameSourceDoNotKeepRestartingTheGrace() {
        val nav = navigator()
        nav.update(fix(), 1_000L, false, facingObservation = facing(1_000L))
        assertRetainedClock(nav, nav.retryGuidance(1_501L, facing(1_000L)), 12, 3_001L)
        assertRetainedClock(nav, nav.retryGuidance(1_800L, null), 12, 3_001L)
        assertRetainedClock(nav, nav.retryGuidance(2_100L, facing(1_000L)), 12, 3_001L)
        assertRetainedClock(nav, nav.retryGuidance(3_000L, null), 12, 3_001L)
        assertRecheck(requireNotNull(nav.retryGuidance(3_001L, null)))
    }

    @Test fun replacingOrClearingTheRouteCannotInheritThePreviousGraceDeadline() {
        for (clearFirst in listOf(false, true)) {
            val nav = navigator()
            assertWaiting(nav.update(fix(), 1_000L, false, facingObservation = null))
            if (clearFirst) nav.clear()
            nav.setRoute(route())
            assertWaiting(nav.update(fix(2_000L), 2_000L, false, facingObservation = null))
            assertWaiting(nav.retryGuidance(2_500L, null))
            assertWaiting(nav.retryGuidance(3_499L, null))
            assertRecheck(requireNotNull(nav.retryGuidance(3_500L, null)))
        }
    }

    @Test fun positionInterruptionRequiresANewContextAndANewGrace() {
        var clockMs = 1_000L
        val nav = RouteNavigator(nowElapsedRealtimeMs = { clockMs }).apply { setRoute(route()) }
        assertWaiting(nav.update(fix(), 1_000L, false, facingObservation = null))
        clockMs = 2_500L
        nav.onPositioningEvidenceInterrupted()
        assertNull(nav.retryGuidance(2_500L, null))
        // Lost position authority cannot be replaced by one fix or a duplicate receipt.
        val first = nav.update(fix(2_600L), 2_600L, false, facingObservation = null)
        assertEquals("route_geometry_recheck_required", first.reason)
        assertNull(first.instruction)
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, first.pendingUserDecision)
        assertNull(nav.currentAcceptedRouteMatchFor(2_600L))
        assertNull(nav.retryGuidance(2_650L, null)?.instruction)
        assertWaiting(nav.update(fix(2_700L), 2_700L, false, facingObservation = null))
        assertWaiting(nav.retryGuidance(4_199L, null))
        assertRecheck(requireNotNull(nav.retryGuidance(4_200L, null)))
    }

    @Test fun movingToAnotherGuideCannotExtendTheSameUnconfirmedDirectionEpisode() {
        val nav = RouteNavigator().apply {
            setRoute(WalkingRoute("TEST", WalkingRouteSummary(400, 400),
                listOf(point(0.0), point(400.0)), listOf(
                    WalkingRouteGuidePoint(0, point(100.0), null, 100, 300, turnType = 11),
                    WalkingRouteGuidePoint(1, point(200.0), null, 100, 200, turnType = 11))))
        }
        assertWaiting(nav.update(fix(eastM = 90.0), 1_000L, false, stepProgressM = 0.0, facingObservation = null))
        val changed = nav.update(fix(2_000L, eastM = 115.0), 2_000L, false,
            stepProgressM = 25.0, facingObservation = null)
        assertWaiting(changed)
        assertWaiting(nav.retryGuidance(2_499L, null))
        val nextGuide = requireNotNull(nav.retryGuidance(2_500L, null))
        assertRecheck(nextGuide)
        assertEquals(1, nextGuide.guideIndex)
    }

    @Test fun aBackwardClockRestartsTheEpisodeAndNegativeTimeCannotProduceDirectionSpeech() {
        val nav = RouteNavigator(nowElapsedRealtimeMs = { 1_000L }).apply { setRoute(route()) }
        assertNull(nav.update(fix(), -1L, false, facingObservation = null).instruction)
        // Invalid time invalidates position authority too; two real new fixes must restore it.
        val first = nav.update(fix(), 1_000L, false, facingObservation = null)
        assertEquals("route_geometry_recheck_required", first.reason)
        assertNull(first.instruction)
        assertNull(nav.currentAcceptedRouteMatchFor(1_000L))
        assertWaiting(nav.update(fix(1_100L), 1_100L, false, facingObservation = null))
        assertClock(requireNotNull(nav.retryGuidance(1_500L, facing(1_500L))), 12)
        assertRetainedClock(nav, nav.retryGuidance(2_000L, null), 12, 3_500L)
        assertRetainedClock(nav, nav.retryGuidance(2_500L, null), 12, 3_500L)
        // Rollback discards retained direction. The new cold episode cannot authorize a clock.
        assertWaiting(nav.retryGuidance(2_300L, null, null, 2_000L))
        assertNull(nav.currentDirectionGraceSnapshot()?.retainedObservedAtElapsedRealtimeMs)
        assertWaiting(nav.retryGuidance(3_799L, null))
        assertRecheck(requireNotNull(nav.retryGuidance(3_800L, null)))
    }

    @Test fun theGraceDoesNotExtendLocationFreshnessOrPermitRetryPastTheNextBranch() {
        val nav = navigator()
        nav.update(fix(), 1_000L, false, facingObservation = facing(1_000L))
        // Discovering this ancient sample cannot resurrect it for another 1.5 seconds.
        assertRecheck(requireNotNull(nav.retryGuidance(10_900L, null)))
        assertEquals(3_001L, nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        assertNull(nav.retryGuidance(12_400L, null))
        val near = navigator()
        near.update(fix(eastM = 98.0), 1_000L, false, facingObservation = facing(1_000L))
        assertRetainedClock(near, near.retryGuidance(1_100L, null), 12, 2_600L)
        assertNull(near.retryGuidance(3_101L, null))
    }

    @Test fun besideRouteAndUnpassedTurnsAreNotHeldByTheDirectionRecheckGrace() {
        val beside = navigator().update(fix(northM = 12.0, accuracyM = 10f), 1_000L, false,
            facingObservation = null)
        assertTrue(beside.instruction.orEmpty().contains("잠시 멈춰 주변 보행로를 확인"))
        val corner = WalkingRoute("TEST", WalkingRouteSummary(100, 100),
            listOf(point(0.0), point(20.0), point(20.0, -80.0)),
            listOf(WalkingRouteGuidePoint(0, point(20.0), null, 20, 80, turnType = 13)))
        val nav = RouteNavigator().apply { setRoute(corner) }
        nav.update(fix(eastM = 10.0, accuracyM = 3f), 1_000L, false, facingObservation = null)
        val vertex = nav.update(fix(2_000L, eastM = 20.0, accuracyM = 3f), 2_000L, false,
            facingObservation = null)
        assertEquals(RouteAlignmentReason.TURN_PASSAGE_PENDING, vertex.routeAlignmentDiagnostic?.reason)
        assertTrue(vertex.instruction.orEmpty().contains("경로 기준으로 3시 방향으로 꺾으세요"))
        assertTrue(nav.reserveInstruction(vertex))
        assertTrue(nav.isInstructionCurrent(vertex, 2_000L, null))
    }

    @Test fun badObservationsDuringDeliveryBackoffStartTheGraceWithoutMovingEitherDeadline() {
        val nav = navigator()
        val failed = nav.update(fix(), 1_000L, false, facingObservation = facing(1_000L))
        assertTrue(nav.reserveInstruction(failed))
        nav.releaseInstruction(failed, 1_100L)
        for (at in listOf(1_200L, 2_699L)) {
            val backoff = requireNotNull(nav.retryGuidance(at, imprecise(at)))
            assertEquals("guidance_retry_wait", backoff.reason)
            assertNull(backoff.instruction)
            assertEquals(2_700L, nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        }
        // Sensor PROBLEM is due at 2700, independently of the transport's 3100 retry deadline.
        val recheck = requireNotNull(nav.retryGuidance(2_700L, imprecise(2_700L)))
        assertRecheck(recheck)
        assertTrue(nav.reserveInstruction(recheck))
        nav.acknowledgeInstruction(failed, 2_701L)
        assertEquals("guidance_in_flight", nav.retryGuidance(2_702L, imprecise(2_702L))?.reason)
        nav.acknowledgeInstruction(recheck, 2_800L)
        val recovered = requireNotNull(nav.currentGuidance(2_900L, facing(2_900L)))
        assertClock(recovered, 12)
        assertTrue(nav.reserveInstruction(recovered))
        assertTrue(nav.isInstructionCurrent(recovered, 2_900L, facing(2_900L)))
    }

    @Test fun realDeviationDecisionsAreImmediateWithTheDefaultGrace() {
        val nav = navigator()
        nav.update(fix(), 1_000L, false, facingObservation = null)
        val suspected = nav.update(fix(2_000L, northM = 100.0), 2_000L, false, facingObservation = null)
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, suspected.pendingUserDecision)
        assertTrue(suspected.instruction.orEmpty().contains("위치 다시 확인"))
        val confirmed = nav.update(fix(3_000L, northM = 100.0), 3_000L, false, facingObservation = null)
        assertEquals(RouteNavigatorUserDecision.REROUTE, confirmed.pendingUserDecision)
        assertTrue(confirmed.offRoute)
        assertTrue(confirmed.instruction.orEmpty().contains("새 경로 요청"))
        assertFalse(confirmed.instruction.orEmpty().contains("시 방향"))
    }


    @Test fun repeatedSourceSwitchesCannotPostponeTheFirstUnconfirmedDeadline() {
        val nav = navigator()
        val fallback = facing(1_000L).copy(source = RouteFacingSource.GRAVITY_MAGNETIC,
            sensorQuality = RouteFacingSensorQuality.CALIBRATED_SENSOR_CHECKS, headingAccuracyDegrees = null)
        assertWaiting(nav.update(fix(), 1_000L, false, facingObservation = fallback))
        assertWaiting(nav.retryGuidance(1_300L, imprecise(1_300L)))
        assertWaiting(nav.retryGuidance(1_600L, fallback.copy(observedAtElapsedRealtimeMs = 1_600L)))
        assertWaiting(nav.retryGuidance(1_900L, imprecise(1_900L)))
        assertWaiting(nav.retryGuidance(2_200L, fallback.copy(observedAtElapsedRealtimeMs = 2_200L)))
        assertWaiting(nav.retryGuidance(2_499L, imprecise(2_499L)))
        assertRecheck(requireNotNull(nav.retryGuidance(2_500L, imprecise(2_500L))))
        assertTrue(nav.isDirectionRecheckPending())
        assertNotNull(nav.currentAcceptedRouteMatchFor(1_000L))
    }

    @Test fun sourceRecoveryStillNeedsTwoFreshSamplesAfterTheOriginalDeadline() {
        val nav = navigator()
        assertClock(nav.update(fix(), 1_000L, false, facingObservation = facing(1_000L)), 12)
        assertRetainedClock(nav, nav.retryGuidance(1_100L, imprecise(1_100L)), 12, 2_600L)
        val switched = facing(2_500L).copy(source = RouteFacingSource.GRAVITY_MAGNETIC,
            sensorQuality = RouteFacingSensorQuality.CALIBRATED_SENSOR_CHECKS)
        assertRetainedClock(nav, nav.retryGuidance(2_500L, switched), 12, 2_600L)
        assertRetainedClock(nav, nav.retryGuidance(2_599L, switched), 12, 2_600L)
        val recheck = requireNotNull(nav.retryGuidance(2_600L, switched))
        assertRecheck(recheck)
        assertTrue(nav.isDirectionRecheckPending())
        // Keep the original malformed fallback as a negative: its copied RV angular errors
        // cannot establish source continuity, even with another fresh sensor timestamp.
        assertFalse(RouteFacingGuidance.isUsableObservation(switched, 2_600L))
        assertRecheck(requireNotNull(nav.retryGuidance(2_700L,
            switched.copy(observedAtElapsedRealtimeMs = 2_700L))))
        assertTrue(nav.isDirectionRecheckPending())
        val validFallback = switched.copy(headingAccuracyDegrees = null, reportedHeadingAccuracyDegrees = null,
            observedAtElapsedRealtimeMs = 2_800L)
        assertTrue(RouteFacingGuidance.isUsableObservation(validFallback, 2_800L))
        assertFalse(RouteFacingGuidance.evaluate(90.0, validFallback, 2_800L).clockPrecisionAvailable)
        assertRecheck(requireNotNull(nav.retryGuidance(2_800L, validFallback)))
        // The fallback establishes a real source switch but cannot report a precise clock.
        // Returning to RV still needs two distinct fresh observations; the old deadline remains.
        val firstRv = facing(2_900L)
        assertRecheck(requireNotNull(nav.retryGuidance(2_900L, firstRv)))
        assertRecheck(requireNotNull(nav.retryGuidance(2_950L, firstRv)))
        assertTrue(nav.isDirectionRecheckPending())
        val recovered = requireNotNull(nav.retryGuidance(3_000L, facing(3_000L)))
        assertClock(recovered, 12)
        assertFalse(nav.isDirectionRecheckPending())
        assertTrue(nav.reserveInstruction(recovered))
        assertTrue(nav.isInstructionCurrent(recovered, 3_000L, facing(3_000L)))
    }

    @Test fun changingUnconfirmedClockCandidatesDoesNotResetTheFirstDeadline() {
        val nav = navigator()
        assertClock(nav.update(fix(), 1_000L, false, facingObservation = facing(1_000L)), 12)
        for ((at, heading) in listOf(1_100L to 0.0, 1_400L to 180.0, 1_700L to 0.0,
                2_000L to 180.0, 2_300L to 0.0)) {
            assertRetainedClock(nav, nav.retryGuidance(at, facing(at, heading)), 12, 2_600L)
            assertTrue(nav.isDirectionRecheckPending())
        }
        val sameSample = facing(2_300L, 0.0)
        assertRetainedClock(nav, nav.retryGuidance(2_599L, sameSample), 12, 2_600L)
        assertRecheck(requireNotNull(nav.retryGuidance(2_600L, sameSample)))
        assertTrue(nav.isDirectionRecheckPending())
        assertClock(requireNotNull(nav.retryGuidance(2_700L, facing(2_700L, 0.0))), 3)
        assertFalse(nav.isDirectionRecheckPending())
    }

    @Test fun anUnpassedTurnDoesNotEraseAnAlreadyPendingDirectionDeadline() {
        val corner = WalkingRoute("TEST", WalkingRouteSummary(100, 100),
            listOf(point(0.0), point(20.0), point(20.0, -80.0)),
            listOf(WalkingRouteGuidePoint(0, point(20.0), null, 20, 80, turnType = 13)))
        val nav = RouteNavigator().apply { setRoute(corner) }
        assertWaiting(nav.update(fix(eastM = 10.0, accuracyM = 3f), 1_000L, false))
        val vertex = nav.update(fix(2_000L, eastM = 20.0, accuracyM = 3f), 2_000L, false)
        assertEquals(RouteAlignmentReason.TURN_PASSAGE_PENDING, vertex.routeAlignmentDiagnostic?.reason)
        assertTrue(vertex.instruction.orEmpty().contains("경로 기준으로 3시 방향으로 꺾으세요"))
        assertFalse(vertex.instruction.orEmpty().contains("카메라 기준 약"))
        assertTrue(nav.isDirectionRecheckPending())
        assertWaiting(nav.update(fix(2_499L, eastM = 15.0, accuracyM = 3f), 2_499L, false))
        assertRecheck(requireNotNull(nav.retryGuidance(2_500L)))
    }

    @Test fun aBesideRouteSafetyNoticeDoesNotEraseAnAlreadyPendingDirectionDeadline() {
        val nav = navigator()
        assertWaiting(nav.update(fix(accuracyM = 10f), 1_000L, false))
        val beside = nav.update(fix(2_000L, northM = 12.0, accuracyM = 10f), 2_000L, false)
        assertTrue(beside.instruction.orEmpty().contains("잠시 멈춰 주변 보행로를 확인"))
        assertFalse(beside.instruction.orEmpty().contains("몸을 돌리세요"))
        assertTrue(nav.isDirectionRecheckPending())
        assertWaiting(nav.update(fix(2_499L, accuracyM = 10f), 2_499L, false))
        assertRecheck(requireNotNull(nav.retryGuidance(2_500L)))
    }

    @Test fun actualClockConfirmationEndsPendingStateEvenWhileASentenceIsInFlight() {
        val nav = navigator()
        val first = nav.update(fix(), 1_000L, false, facingObservation = facing(1_000L))
        assertClock(first, 12)
        assertFalse(nav.isDirectionRecheckPending())
        assertTrue(nav.reserveInstruction(first))
        val changing = facing(1_100L, 0.0)
        assertEquals("guidance_in_flight", nav.retryGuidance(1_100L, changing)?.reason)
        assertTrue(nav.isDirectionRecheckPending())
        assertEquals("guidance_in_flight", nav.retryGuidance(1_150L, changing)?.reason)
        assertTrue(nav.isDirectionRecheckPending())
        assertEquals("guidance_in_flight", nav.retryGuidance(1_200L, facing(1_200L, 0.0))?.reason)
        assertFalse(nav.isDirectionRecheckPending())
    }

    @Test fun firstNumericRecoveryAfterExplanationDoneIsImmediateButActualDirectionKeepsCadence() {
        val nav = navigator()
        assertWaiting(nav.update(fix(), 1_000L, false))
        val recheck = requireNotNull(nav.retryGuidance(2_500L))
        assertRecheck(recheck)
        assertTrue(nav.reserveInstruction(recheck))
        nav.acknowledgeInstruction(recheck, 2_600L)
        assertTrue(nav.isDirectionRecheckPending())
        val recovered = requireNotNull(nav.retryGuidance(3_000L, facing(3_000L)))
        assertClock(recovered, 12)
        assertTrue(nav.reserveInstruction(recovered))
        assertTrue(nav.isInstructionCurrent(recovered, 3_000L, facing(3_000L)))
        assertFalse(nav.isDirectionRecheckPending())
        nav.acknowledgeInstruction(recheck, 3_001L)
        assertEquals("guidance_in_flight", nav.retryGuidance(3_002L, facing(3_002L))?.reason)
        nav.acknowledgeInstruction(recovered, 3_100L)
        assertEquals("guidance_rate_limited", nav.retryGuidance(3_200L, imprecise(3_200L))?.reason)
        assertEquals(4_700L, nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        assertTrue(nav.isDirectionRecheckPending())
        assertEquals("guidance_rate_limited", nav.retryGuidance(3_300L, facing(3_300L))?.reason)
        assertFalse(nav.isDirectionRecheckPending())
        val early = nav.update(fix(5_099L), 5_099L, false, facingObservation = facing(5_099L))
        assertNull(early.instruction)
        assertEquals("guidance_rate_limited", early.reason)
        assertNull(nav.retryGuidance(5_100L, facing(5_100L))?.instruction)
        assertNull(nav.retryGuidance(8_099L, facing(8_099L))?.instruction)
        assertClock(requireNotNull(nav.retryGuidance(8_100L, facing(8_100L))), 12)
    }


    @Test fun delayedBadDeliveryUsesItsFirstReceiptWithoutMovingTheMeasurementClock() {
        val nav = navigator()
        val old = nav.update(fix(900L), 900L, false, facingObservation = facing(900L))
        assertTrue(nav.reserveInstruction(old))
        val delivered = requireNotNull(nav.retryGuidance(2_200L, imprecise(2_200L), null, 1_000L))
        assertEquals("guidance_in_flight", delivered.reason)
        assertFalse(delivered.cancelStaleNavigationSpeech)
        assertEquals(2_500L, nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        assertTrue(nav.isInstructionCurrent(old, 2_200L, imprecise(2_200L)))
        assertFalse(requireNotNull(old.speechCueToken).hasCurrentFacingEvidence(2_200L))
        assertFalse(nav.reserveInstruction(old))
        // Later receipts do not replace the admitted original onset.
        assertEquals("guidance_in_flight", nav.retryGuidance(2_499L, imprecise(2_499L), null, 2_000L)?.reason)
        val recheck = requireNotNull(nav.retryGuidance(2_500L, imprecise(2_500L), null, 2_000L))
        assertRecheck(recheck)
        assertTrue(recheck.cancelStaleNavigationSpeech)
        assertNotNull(nav.currentAcceptedRouteMatchFor(900L))
        assertNull(nav.currentAcceptedRouteMatchFor(2_500L))
    }

    @Test fun aBadReceiptAlreadyPastItsDeadlineDoesNotRenewStaleFacingOrAddAnotherGrace() {
        for (observation in listOf(imprecise(2_600L), facing(1_000L))) {
            val nav = navigator()
            val old = nav.update(fix(900L), 900L, false, facingObservation = facing(900L))
            assertTrue(nav.reserveInstruction(old))
            val recheck = requireNotNull(nav.retryGuidance(2_600L, observation, null, 1_000L))
            assertRecheck(recheck)
            assertTrue(recheck.cancelStaleNavigationSpeech)
            assertFalse(nav.isInstructionCurrent(old, 2_600L, observation))
            assertFalse(nav.reserveInstruction(old))
            assertNotNull(nav.currentAcceptedRouteMatchFor(900L))
        }
    }

    @Test fun invalidBadReceiptTimesCannotMoveTheRealObservationDeadline() {
        for (invalidReceipt in listOf(-1L, Long.MAX_VALUE)) {
            val nav = navigator()
            nav.update(fix(), 1_000L, false, facingObservation = facing(1_000L))
            assertRetainedClock(nav, nav.retryGuidance(1_100L, imprecise(1_100L), null, invalidReceipt), 12, 2_600L)
            assertRetainedClock(nav, nav.retryGuidance(2_599L, imprecise(2_599L), null, invalidReceipt), 12, 2_600L)
            assertRecheck(requireNotNull(nav.retryGuidance(2_600L, imprecise(2_600L), null, invalidReceipt)))
        }
    }

    @Test fun anOldBadReceiptCannotRecreateAnEpisodeAfterActualFreshClockConfirmation() {
        val nav = navigator()
        assertWaiting(nav.update(fix(), 1_000L, false))
        assertClock(requireNotNull(nav.retryGuidance(2_000L, facing(2_000L), null, 1_000L)), 12)
        assertFalse(nav.isDirectionRecheckPending())
        assertClock(requireNotNull(nav.retryGuidance(2_100L, facing(2_100L), null, 1_000L)), 12)
        assertFalse(nav.isDirectionRecheckPending())
        // With no newer sample, the next fault starts from the actual 2600 lease expiry.
        assertRetainedClock(nav, nav.retryGuidance(3_000L, imprecise(3_000L)), 12, 4_101L)
        assertRetainedClock(nav, nav.retryGuidance(4_100L, imprecise(4_100L)), 12, 4_101L)
        assertRecheck(requireNotNull(nav.retryGuidance(4_101L, imprecise(4_101L))))
    }

    @Test fun aNewRouteWithoutItsOwnPositionCannotConsumeThePreviousRoutesBadReceipt() {
        val nav = navigator()
        assertWaiting(nav.update(fix(), 1_000L, false))
        nav.setRoute(route())
        assertNull(nav.retryGuidance(2_000L, imprecise(2_000L), null, 1_000L))
        assertFalse(nav.isDirectionRecheckPending())
        assertNull(nav.currentAcceptedRouteMatchFor(1_000L))
        // A new accepted context receives its own owner-validated receipt, not the retired one.
        assertWaiting(nav.update(fix(2_100L), 2_100L, false))
        assertWaiting(nav.retryGuidance(3_599L, null, null, 2_100L))
        assertRecheck(requireNotNull(nav.retryGuidance(3_600L, null, null, 2_100L)))
    }

    @Test fun theOriginalThreeArgumentRetryJvmMethodRemainsCallable() {
        val nav = navigator()
        assertWaiting(nav.update(fix(), 1_000L, false))
        val method = RouteNavigator::class.java.getMethod("retryGuidance", java.lang.Long.TYPE,
            RouteFacingObservation::class.java, RouteCompassHeadingReason::class.java)
        assertRecheck(method.invoke(nav, 2_500L, null, null) as RouteNavigatorUpdate)
    }

    @Test fun fastFreshDirectionAndPositionDoNotImplicitlyChooseLocationRecheckAfterACorridorLoss() {
        val nav = navigator()
        assertClock(nav.update(fix(), 1_000L, false, facingObservation = facing(1_000L)), 12)
        val outside = nav.update(fix(2_000L, northM = 20.0), 2_000L, false,
            facingObservation = facing(2_000L))
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, outside.pendingUserDecision)
        assertTrue(outside.cancelStaleNavigationSpeech)
        val owner = requireNotNull(nav.pendingDecisionToken())
        // Retain the original 20 m lateral return in 100 ms. Its derived southward movement
        // course conflicts with this eastbound route, so the first on-route coordinate is LOW.
        val firstReturn = nav.update(fix(2_100L), 2_100L, false,
            facingObservation = facing(2_100L))
        assertEquals(kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchReason.LOW_CONFIDENCE,
            nav.currentRouteMatch()?.reason)
        assertNull(nav.currentAcceptedRouteMatchFor(2_100L))
        assertEquals(owner, nav.pendingDecisionToken())
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, firstReturn.pendingUserDecision)
        // A separate fresh same-coordinate observation has no lateral movement course. Even
        // after it restores accepted matching, no direction callback chooses recheck for the user.
        val recovered = nav.update(fix(2_200L), 2_200L, false,
            facingObservation = facing(2_200L))
        assertNotNull(nav.currentAcceptedRouteMatchFor(2_200L))
        assertEquals(owner, nav.pendingDecisionToken())
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, recovered.pendingUserDecision)
        assertNull(recovered.speechCueToken)
        val retry = nav.retryGuidance(2_250L, facing(2_250L))
        assertNull(retry?.instruction)
        assertNull(retry?.speechCueToken)
        assertNull(nav.currentGuidance(2_300L, facing(2_300L))?.instruction)
        assertEquals(owner, nav.pendingDecisionToken())
        assertTrue(nav.hasRoute())
        assertFalse(recovered.shouldReroute)
        assertFalse(nav.isDirectionRecheckPending())
    }

    private fun assertRetainedClock(nav: RouteNavigator, update: RouteNavigatorUpdate?, hour: Int, deadlineMs: Long) {
        val actual = requireNotNull(update)
        assertClock(actual, hour)
        assertFalse(actual.cancelStaleNavigationSpeech)
        val token = requireNotNull(actual.speechCueToken)
        assertNotNull(token.directionGraceEpisodeId)
        assertEquals(deadlineMs, token.directionGraceDeadlineElapsedRealtimeMs)
        assertEquals(RouteDirectionGraceMode.GRACE_RETAINED, nav.currentDirectionGraceSnapshot()?.mode)
        assertEquals(deadlineMs, nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
    }

    private fun assertWaiting(update: RouteNavigatorUpdate?) {
        val actual = requireNotNull(update)
        assertEquals("${actual.reason}: ${actual.instruction}", "guidance_facing_recheck_wait", actual.reason)
        assertNull(actual.instruction)
        assertFalse(actual.arrived)
        assertFalse(actual.offRoute)
        assertFalse(actual.shouldReroute)
        assertFalse(actual.userDecisionRequired)
    }

    private fun assertRecheck(update: RouteNavigatorUpdate) {
        val text = requireNotNull(update.instruction)
        assertEquals("route_guidance", update.reason)
        assertTrue(text, text.contains("확인"))
        // A future route-relative clock is an overview, not a numeric current-camera claim.
        assertFalse(text, text.contains("카메라 기준 약") || text.contains("몸을 돌리세요") || text.contains("꺾으세요"))
        assertEquals(RouteFacingExpression.UNKNOWN, update.facingDiagnostic?.expression)
        assertFalse(requireNotNull(update.speechCueToken).requiresFacingEvidence)
    }

    private fun assertClock(update: RouteNavigatorUpdate, hour: Int) {
        val text = requireNotNull(update.instruction)
        assertTrue(text, text.contains("약 ${hour}시 방향"))
        assertTrue(text, text.contains("미터"))
        assertFalse(text, text.contains("다시 확인"))
        assertTrue(requireNotNull(update.speechCueToken).requiresFacingEvidence)
    }

    private fun navigator() = RouteNavigator().apply { setRoute(route()) }
    private fun route() = WalkingRoute("TEST", WalkingRouteSummary(200, 200),
        listOf(point(0.0), point(100.0), point(100.0, -100.0)),
        listOf(WalkingRouteGuidePoint(0, point(100.0), null, 100, 100, turnType = 13)))
    private fun point(eastM: Double, northM: Double = 0.0) =
        RoutePoint(northM / 111_194.92664455874, eastM / 111_194.92664455874)
    private fun fix(atMs: Long = 1_000L, eastM: Double = 50.0, northM: Double = 0.0, accuracyM: Float = 2f) =
        point(eastM, northM).let { TrustedLocation(it.latitude, it.longitude, accuracyM, atMs) }
    private fun facing(atMs: Long, heading: Double = 90.0) = RouteFacingObservation(heading, 3.0, atMs)
    // This accepted RV quality spans four clock sectors across the 15-degree edge, exceeding
    // bounded RANGE support. Keep its actual measurement time and avoid a source switch.
    private fun imprecise(atMs: Long) = RouteFacingObservation(75.0, 30.0, atMs).also { observation ->
        assertTrue(RouteFacingGuidance.isUsableObservation(observation, atMs))
        val support = RouteFacingGuidance.evaluate(90.0, observation, atMs)
        assertNull(support.clockHour)
        assertNull(support.clockRange)
    }
}

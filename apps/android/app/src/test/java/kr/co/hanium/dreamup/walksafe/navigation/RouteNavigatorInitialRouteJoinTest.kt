package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteHeadingEstimate
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchQuality
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchReason
import org.junit.Assert.*
import org.junit.Test

/** The initial explicit route joins from accepted position evidence, never a speech-only snap. */
class RouteNavigatorInitialRouteJoinTest {
    @Test fun optInBlocksFallbackOutputsBeforeTheFirstMeasurement() {
        val navigator = navigator()
        assertTrue(navigator.isWaitingForInitialRouteJoin())
        assertTrue(navigator.hasRoute())
        assertWaitingOutputs(navigator, onRoute(1_000L), 1_000L)
        assertWaiting(navigator.requestArrivalConfirmation(1_000L))
        assertNoProgress(navigator)
    }

    @Test fun repeatedOutsideAndFarFixesWaitWithoutADeviationLatch() {
        val navigator = navigator()
        listOf(outside(1_000L), outside(2_000L), far(3_000L), far(4_000L)).forEach { point ->
            assertWaiting(sample(navigator, point))
            assertTrue(navigator.isWaitingForInitialRouteJoin())
            assertEquals(RouteMatchReason.CORRIDOR_OUTSIDE, navigator.currentRouteMatch()?.reason)
            assertWaitingOutputs(navigator, point, point.elapsedRealtimeMs + 100L)
            assertNoProgress(navigator)
        }
    }

    @Test fun freshAcceptedJoinResumesWithoutARecheckAndLaterDeviationUsesExistingPolicy() {
        val navigator = navigator()
        assertWaiting(sample(navigator, far(1_000L)))
        val joined = sample(navigator, onRoute(2_000L))
        assertEquals("route_guidance", joined.reason)
        assertFalse(navigator.isWaitingForInitialRouteJoin())
        assertNotNull(navigator.currentAcceptedRouteMatchFor(2_000L))
        assertNotNull(navigator.currentProjection(onRoute(2_000L)))
        assertNull(navigator.pendingDecisionToken())

        val firstDeviation = sample(navigator, far(3_000L))
        assertEquals("off_route_pending", firstDeviation.reason)
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, firstDeviation.pendingUserDecision)
        val confirmed = sample(navigator, far(4_000L))
        assertTrue(confirmed.offRoute)
        assertEquals(RouteNavigatorUserDecision.REROUTE, confirmed.pendingUserDecision)
        assertFalse(navigator.isWaitingForInitialRouteJoin())
        val returned = sample(navigator, onRoute(5_000L))
        assertEquals(RouteNavigatorUserDecision.REROUTE, returned.pendingUserDecision)
        assertTrue(returned.offRoute)
    }

    @Test fun ambiguousParallelRouteWaitsThenJoinsFromAnUnambiguousFreshFix() {
        val navigator = navigator(parallelRoute())
        val midpoint = TrustedLocation(37.000025, 127.0005, 5f, 1_000L)
        assertWaiting(navigator.update(midpoint, 1_100L, false))
        assertEquals(RouteMatchReason.AMBIGUOUS_CANDIDATES, navigator.currentRouteMatch()?.reason)
        assertEquals(RouteMatchQuality.LOW, navigator.currentRouteMatch()?.quality)
        assertWaitingOutputs(navigator, midpoint, 1_100L)
        assertNoProgress(navigator)

        val recovering = sample(navigator, onRoute(2_000L).copy(longitude = 127.0005))
        assertEquals("route_geometry_recheck_wait", recovering.reason)
        assertNull(navigator.currentAcceptedRouteMatchFor(2_000L))
        val joined = sample(navigator, onRoute(2_100L).copy(longitude = 127.0005))
        assertEquals("route_guidance", joined.reason)
        assertFalse(navigator.isWaitingForInitialRouteJoin())
        assertNotNull(navigator.currentAcceptedRouteMatchFor(2_100L))
        assertNull(navigator.pendingDecisionToken())
    }

    @Test fun theExistingMediumQualityMatchAlsoCompletesInitialJoining() {
        val navigator = navigator()
        val point = onRoute(1_000L).copy(latitude = 37.00001)
        val joined = sample(navigator, point)
        assertEquals(RouteMatchQuality.MEDIUM, navigator.currentRouteMatch()?.quality)
        assertEquals("route_guidance", joined.reason)
        assertFalse(navigator.isWaitingForInitialRouteJoin())
        assertNotNull(navigator.currentAcceptedRouteMatchFor(1_000L))
    }

    @Test fun untrustedInputAfterJoiningRetainsTheExistingExplicitRecheckPolicy() {
        val navigator = navigator()
        sample(navigator, onRoute(1_000L))
        val interrupted = requireNotNull(navigator.onUntrustedLocation())
        assertFalse(navigator.isWaitingForInitialRouteJoin())
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, interrupted.pendingUserDecision)
        val fresh = sample(navigator, onRoute(2_000L))
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, fresh.pendingUserDecision)
        assertNull(navigator.currentInstruction(onRoute(2_000L)))
    }

    @Test fun duplicateOlderFutureAndExpiredFixesCannotJoinOrAdvance() {
        val navigator = navigator()
        val outside = outside(1_000L)
        assertWaiting(sample(navigator, outside))
        assertWaiting(sample(navigator, outside, 1_200L))
        assertEquals("location_sample_not_newer", sample(navigator, onRoute(900L), 2_100L).reason)
        assertEquals("location_sample_not_newer", sample(navigator, onRoute(1_000L), 2_100L).reason)
        assertEquals("location_sample_invalid_or_stale", sample(navigator, onRoute(3_000L), 2_100L).reason)
        assertEquals("location_sample_invalid_or_stale", sample(navigator, onRoute(2_000L), 20_100L).reason)
        assertTrue(navigator.isWaitingForInitialRouteJoin())
        assertNoProgress(navigator)
        assertWaitingOutputs(navigator, outside, 20_100L)
        assertEquals("route_geometry_recheck_wait", sample(navigator, onRoute(21_000L)).reason)
        assertNull(navigator.currentAcceptedRouteMatchFor(21_000L))
        assertEquals("route_guidance", sample(navigator, onRoute(21_100L)).reason)
        assertFalse(navigator.isWaitingForInitialRouteJoin())
    }

    @Test fun reprojectingTheSameRawMeasurementCannotBeTheFirstJoin() {
        val navigator = navigator()
        val raw = outside(1_000L)
        assertWaiting(sample(navigator, raw))
        val reprojected = filtered(onRoute(2_000L))
        assertWaiting(navigator.update(raw, 2_100L, false, filteredPosition = reprojected))
        assertTrue(navigator.isWaitingForInitialRouteJoin())
        assertNull(navigator.currentAcceptedRouteMatchFor(2_000L))
        assertNoProgress(navigator)
        assertEquals("route_guidance", sample(navigator, onRoute(3_000L)).reason)
        assertFalse(navigator.isWaitingForInitialRouteJoin())
    }

    @Test fun lowConfidenceCanReferenceTheStartButCannotCreateDegradedProgressBeforeJoin() {
        val navigator = navigator(degraded = true)
        val low = TrustedLocation(37.00008, 127.0003, 1f, 1_000L)
        assertWaiting(sample(navigator, low))
        assertEquals(RouteMatchQuality.LOW, navigator.currentRouteMatch()?.quality)
        assertEquals(RouteMatchReason.LOW_CONFIDENCE, navigator.currentRouteMatch()?.reason)
        assertWaitingOutputs(navigator, low, 1_100L)
        assertNoProgress(navigator)
        assertFalse(navigator.requestArrivalConfirmation(1_100L).arrivalCandidate)
    }

    @Test fun untrustedLocationWaitsAndAFreshJoinNeedsNoExplicitRecheck() {
        val navigator = navigator()
        assertWaiting(sample(navigator, outside(1_000L)))
        assertWaiting(requireNotNull(navigator.onUntrustedLocation()))
        assertNull(navigator.pendingDecisionToken())
        assertTrue(navigator.isWaitingForInitialRouteJoin())
        assertEquals("route_geometry_recheck_wait", sample(navigator, onRoute(2_000L)).reason)
        assertNull(navigator.currentAcceptedRouteMatchFor(2_000L))
        assertEquals("route_guidance", sample(navigator, onRoute(2_100L)).reason)
        assertFalse(navigator.isWaitingForInitialRouteJoin())
        assertNull(navigator.pendingDecisionToken())
    }

    @Test fun clearAndReplacementResetTheInitialJoinScopeAndRetireOldSpeech() {
        val navigator = navigator()
        val old = sample(navigator, onRoute(1_000L))
        assertTrue(navigator.reserveInstruction(old))
        assertFalse(navigator.isWaitingForInitialRouteJoin())
        navigator.setRoute(straightRoute(), waitForInitialRouteJoin = true)
        assertTrue(navigator.isWaitingForInitialRouteJoin())
        assertFalse(navigator.reserveInstruction(old))
        navigator.acknowledgeInstruction(old, 2_000L)
        assertNoProgress(navigator)
        assertWaiting(sample(navigator, outside(3_000L)))
        navigator.clear()
        assertFalse(navigator.isWaitingForInitialRouteJoin())
        assertFalse(navigator.hasRoute())
        navigator.setRoute(straightRoute())
        assertFalse(navigator.isWaitingForInitialRouteJoin())
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK,
            sample(navigator, outside(4_000L)).pendingUserDecision)
    }

    @Test fun theDefaultApiReacquiresInitialAmbiguityWithoutInventingAChoice() {
        val navigator = RouteNavigator().apply { setRoute(parallelRoute()) }
        val midpoint = TrustedLocation(37.000025, 127.0005, 5f, 1_000L)
        val update = navigator.update(midpoint, 1_100L, false)
        assertFalse(navigator.isWaitingForInitialRouteJoin())
        assertNull(update.pendingUserDecision)
        assertEquals("route_geometry_recheck_wait", update.reason)
        assertNull(update.instruction)
        assertNull(update.speechCueToken)
    }

    @Test fun departureConnectorDescribesTheStartPointClockAndDistanceWithoutMovementCommands() {
        val point = northOfStart(1_000L)
        val navigator = navigatorWithOrigin(point)
        val update = reference(navigator, point, 1_100L, heading = 90.0)
        assertWaiting(update)
        assertTrue(navigator.isWaitingForInitialRouteJoin())
        assertEquals(RouteAlignmentReason.DEPARTURE_CONNECTOR, update.routeAlignmentDiagnostic?.reason)
        assertTrue(requireNotNull(update.instruction).contains("3시 방향"))
        assertTrue(update.instruction!!.contains("약 22미터"))
        assertFalse(update.instruction!!.contains("연결 통로는 확인되지 않았습니다"))
        assertTrue(navigator.reserveInstruction(update))
        assertTrue(navigator.isInstructionCurrent(update, 1_100L, facing(90.0, 1_100L)))
        navigator.acknowledgeInstruction(update, 1_200L)
        assertNoProgress(navigator)
        assertNull(navigator.currentInstruction(point))
        assertNull(navigator.currentProjection(point))
    }

    @Test fun unavailableFacingKeepsDistanceAndExplainsTheActualSensorReason() {
        val point = northOfStart(1_000L)
        val navigator = navigatorWithOrigin(point)
        val update = navigator.update(point, 1_100L, false, filteredPosition = filtered(point),
            facingUnavailableReason = RouteCompassHeadingReason.SENSOR_STALLED)
        val text = requireNotNull(update.instruction)
        assertTrue(text.contains("약 22미터"))
        assertTrue(text.contains("센서 값이 들어오지 않고 있습니다"))
        assertTrue(text.contains("잠시 멈춰 방향을 기다려 주세요"))
        assertFalse(text.contains("앞으로 향하게 해 주세요"))
        assertFalse(Regex("[0-9]+시 방향").containsMatchIn(text))
        assertWaiting(update)
        assertNoProgress(navigator)
    }

    @Test fun anUncertainTargetDirectionCannotProduceAClockNumber() {
        val navigator = navigator(parallelRoute())
        val point = TrustedLocation(37.000025, 127.0, 5f, 1_000L)
        val update = reference(navigator, point, 1_100L, heading = 0.0)
        assertTrue(navigator.isWaitingForInitialRouteJoin())
        assertEquals("route_geometry_recheck_wait", update.reason)
        assertNull(update.instruction)
        assertNull(update.speechCueToken)
        assertFalse(Regex("[0-9]+시 방향").containsMatchIn(update.instruction.orEmpty()))
        assertNoProgress(navigator)
    }

    @Test fun duplicateSamplesUseExistingCueCadenceAndFailuresCanRetry() {
        val point = northOfStart(1_000L)
        val navigator = navigatorWithOrigin(point)
        val first = reference(navigator, point, 1_100L, 90.0)
        assertTrue(navigator.reserveInstruction(first))
        assertNull(reference(navigator, point, 1_200L, 90.0).instruction)
        navigator.releaseInstruction(first, 1_300L)
        assertNull(navigator.retryGuidance(1_400L, facing(90.0, 1_400L))?.instruction)
        val retry = requireNotNull(navigator.retryGuidance(3_400L, facing(90.0, 3_400L)))
        assertNotNull(retry.instruction)
        assertNotEquals(first.speechCueToken, retry.speechCueToken)
        assertTrue(navigator.reserveInstruction(retry))
        navigator.acknowledgeInstruction(retry, 3_500L)
        assertNull(reference(navigator, point, 3_600L, 90.0).instruction)
        assertNoProgress(navigator)
    }

    @Test fun reversedFacingCancelsTheOldReferenceAndOffersTheCurrentDirection() {
        val point = northOfStart(1_000L)
        val navigator = navigatorWithOrigin(point)
        val first = reference(navigator, point, 1_100L, 90.0)
        assertTrue(navigator.reserveInstruction(first))
        assertFalse(navigator.isInstructionCurrent(first, 1_200L, facing(270.0, 1_200L)))
        val changed = reference(navigator, point.copy(elapsedRealtimeMs = 2_000L), 2_100L, 270.0)
        assertNotNull(changed.instruction)
        assertTrue(changed.cancelStaleNavigationSpeech)
        navigator.acknowledgeInstruction(first, 2_200L)
        assertTrue(navigator.reserveInstruction(changed))
        assertNoProgress(navigator)
    }

    @Test fun joiningRetiresTheReferenceAndKeepsTheRealRouteStartSummary() {
        val point = northOfStart(1_000L)
        val route = straightRoute().let { it.copy(guidePoints = listOf(
            WalkingRouteGuidePoint(0, it.polyline.first(), "출발", 0, 0, 90f, pointType = "SP"),
        ) + it.guidePoints) }
        // Exercise join handover independently of the recheck announcement delay.
        val navigator = RouteNavigator(RouteNavigatorConfig(directionRecheckGraceMs = 0L)).apply {
            setRoute(route, origin = point, waitForInitialRouteJoin = true)
        }
        val reference = reference(navigator, point, 1_100L, 90.0)
        assertTrue(navigator.reserveInstruction(reference))
        val joined = sample(navigator, onRoute(2_000L))
        assertFalse(navigator.isWaitingForInitialRouteJoin())
        assertFalse(joined.directionOnly)
        assertTrue(joined.cancelStaleNavigationSpeech)
        assertTrue(requireNotNull(joined.instruction).contains("길안내를 시작합니다"))
        assertFalse(navigator.isInstructionCurrent(reference, 2_100L, facing(90.0, 2_100L)))
        navigator.acknowledgeInstruction(reference, 2_200L)
        assertTrue(navigator.reserveInstruction(joined))
    }

    @Test fun stalePositionClearAndReplacementRetireReferenceCallbacks() {
        val point = northOfStart(1_000L)
        val navigator = navigatorWithOrigin(point)
        val reference = reference(navigator, point, 1_100L, 90.0)
        assertTrue(navigator.reserveInstruction(reference))
        assertFalse(navigator.isInstructionCurrent(reference, 20_000L, facing(90.0, 20_000L)))
        assertNoProgress(navigator)
        navigator.clear()
        assertFalse(navigator.reserveInstruction(reference))
        navigator.setRoute(straightRoute(), waitForInitialRouteJoin = true)
        navigator.acknowledgeInstruction(reference, 21_000L)
        val current = reference(navigator, northOfStart(22_000L), 22_100L, 90.0)
        assertTrue(navigator.reserveInstruction(current))
        assertNoProgress(navigator)
    }

    @Test fun aChangedStartDistanceBandRetiresTheOldReference() {
        val point = northOfStart(1_000L)
        val navigator = navigatorWithOrigin(point)
        val first = reference(navigator, point, 1_100L, 90.0)
        assertTrue(navigator.reserveInstruction(first))
        val changed = reference(navigator, point.copy(latitude = 37.0006, elapsedRealtimeMs = 2_000L), 2_100L, 90.0)
        assertTrue(changed.cancelStaleNavigationSpeech)
        assertTrue(requireNotNull(changed.instruction).contains("약 67미터"))
        assertFalse(navigator.isInstructionCurrent(first, 2_100L, facing(90.0, 2_100L)))
        assertTrue(navigator.reserveInstruction(changed))
        assertTrue(navigator.isWaitingForInitialRouteJoin())
        assertNoProgress(navigator)
    }

    @Test fun completedInitialReferenceDoesNotRepeatOnTheOrdinaryPeriodicDeadline() {
        val point = northOfStart(1_000L)
        val navigator = navigatorWithOrigin(point)
        val first = reference(navigator, point, 1_100L, 90.0)
        assertTrue(navigator.reserveInstruction(first))
        navigator.acknowledgeInstruction(first, 1_200L)
        listOf(11_200L, 21_200L, 101_200L).forEach { at ->
            val unchanged = reference(navigator, point.copy(elapsedRealtimeMs = at), at, 90.0)
            assertEquals("guidance_already_completed", unchanged.reason)
            assertNull(unchanged.instruction)
            assertTrue(navigator.isWaitingForInitialRouteJoin())
            assertNoProgress(navigator)
        }
    }

    @Test fun theUserCanRepeatACompletedInitialReferenceWithoutPromotingProgress() {
        val point = northOfStart(1_000L)
        val navigator = navigatorWithOrigin(point)
        val first = reference(navigator, point, 1_100L, 90.0)
        assertTrue(navigator.reserveInstruction(first))
        navigator.acknowledgeInstruction(first, 1_200L)
        val repeated = requireNotNull(navigator.currentGuidance(1_300L, facing(90.0, 1_300L)))
        assertWaiting(repeated)
        assertNotEquals(first.speechCueToken, repeated.speechCueToken)
        assertTrue(navigator.reserveInstruction(repeated))
        assertNoProgress(navigator)
    }

    @Test fun changedDirectionOrDistanceStillOffersAReferenceAfterActualCompletion() {
        for (changeDirection in listOf(false, true)) {
            val point = northOfStart(1_000L)
            val navigator = navigatorWithOrigin(point)
            val first = reference(navigator, point, 1_100L, 90.0)
            assertTrue(navigator.reserveInstruction(first))
            navigator.acknowledgeInstruction(first, 1_200L)
            val changedPoint = point.copy(latitude = if (changeDirection) point.latitude else 37.0006,
                elapsedRealtimeMs = 8_000L)
            val changed = reference(navigator, changedPoint, 8_000L, if (changeDirection) 270.0 else 90.0)
            assertWaiting(changed)
            assertNotNull(changed.instruction)
            assertTrue(navigator.reserveInstruction(changed))
            assertNoProgress(navigator)
        }
    }

    @Test fun postureRecoveryDoesNotAddASecondGenericFacingAction() {
        val point = northOfStart(1_000L)
        val navigator = navigatorWithOrigin(point)
        val text = requireNotNull(navigator.update(point, 1_100L, false, filteredPosition = filtered(point),
            facingUnavailableReason = RouteCompassHeadingReason.REAR_AXIS_VERTICAL).instruction)
        assertTrue(text.contains("휴대전화 후면을 걷는 방향으로 들어 주세요"))
        assertFalse(text.contains("앞으로 향하게 해 주세요"))
        assertFalse(text.contains("잠시 멈춰 방향을 기다려 주세요"))
        assertTrue(text.contains("약 22미터"))
        assertFalse(Regex("[0-9]+시 방향").containsMatchIn(text))
        assertNoProgress(navigator)
    }

    @Test fun positionAmbiguityDoesNotInventACompassOrNumericCueBeforeReacquisition() {
        val navigator = navigator(parallelRoute())
        val point = TrustedLocation(37.000025, 127.0, 5f, 1_000L)
        val waiting = navigator.update(point, 1_100L, false, filteredPosition = filtered(point),
            facingUnavailableReason = RouteCompassHeadingReason.SENSOR_STALLED)
        assertEquals("route_geometry_recheck_wait", waiting.reason)
        assertNull(waiting.instruction); assertNull(waiting.speechCueToken)
        assertNull(navigator.currentAcceptedRouteMatchFor(1_000L))
        assertNoProgress(navigator)
        val first = onRoute(2_000L)
        val recovering = navigator.update(first, 2_000L, false, filteredPosition = filtered(first),
            facingUnavailableReason = RouteCompassHeadingReason.SENSOR_STALLED)
        assertNull(recovering.speechCueToken)
        val second = onRoute(2_100L)
        val recovered = navigator.update(second, 2_100L, false, filteredPosition = filtered(second),
            facingUnavailableReason = RouteCompassHeadingReason.SENSOR_STALLED)
        val text = requireNotNull(recovered.instruction)
        assertTrue(text.contains("센서 값이 들어오지 않고 있습니다"))
        assertFalse(Regex("[0-9]+시 방향").containsMatchIn(text))
    }

    @Test fun returningToAnEarlierClockDirectionIsNewAfterADifferentReferenceCompleted() {
        val point = northOfStart(1_000L)
        val navigator = navigatorWithOrigin(point)
        val first = reference(navigator, point, 1_100L, 90.0)
        assertTrue(requireNotNull(first.instruction).contains("3시 방향"))
        assertTrue(navigator.reserveInstruction(first))
        navigator.acknowledgeInstruction(first, 1_200L)
        val opposite = stableReference(navigator, point, 8_000L, 270.0)
        assertTrue(requireNotNull(opposite.instruction).contains("9시 방향"))
        assertTrue(navigator.reserveInstruction(opposite))
        navigator.acknowledgeInstruction(opposite, 8_200L)
        navigator.acknowledgeInstruction(first, 8_300L)
        val returned = stableReference(navigator, point, 15_000L, 90.0)
        assertTrue(requireNotNull(returned.instruction).contains("3시 방향"))
        assertTrue(navigator.reserveInstruction(returned))
        assertTrue(navigator.isWaitingForInitialRouteJoin())
        assertNoProgress(navigator)
    }

    @Test fun returningToAnEarlierDistanceIsNewAfterADifferentReferenceCompleted() {
        val point = northOfStart(1_000L)
        val navigator = navigatorWithOrigin(point)
        val first = reference(navigator, point, 1_100L, 90.0)
        assertTrue(navigator.reserveInstruction(first))
        navigator.acknowledgeInstruction(first, 1_200L)
        val far = reference(navigator, point.copy(latitude = 37.0006, elapsedRealtimeMs = 8_000L), 8_000L, 90.0)
        assertTrue(requireNotNull(far.instruction).contains("약 67미터"))
        assertTrue(navigator.reserveInstruction(far))
        navigator.acknowledgeInstruction(far, 8_100L)
        val returned = reference(navigator, point.copy(elapsedRealtimeMs = 15_000L), 15_000L, 90.0)
        assertTrue(requireNotNull(returned.instruction).contains("약 22미터"))
        assertTrue(navigator.reserveInstruction(returned))
        assertTrue(navigator.isWaitingForInitialRouteJoin())
        assertNoProgress(navigator)
    }

    @Test fun failedOrUnreservedReferencesDoNotReplaceTheLastActuallyCompletedReference() {
        for (reserveThenFail in listOf(false, true)) {
            val point = northOfStart(1_000L)
            val navigator = navigatorWithOrigin(point)
            val first = reference(navigator, point, 1_100L, 90.0)
            assertTrue(navigator.reserveInstruction(first))
            navigator.acknowledgeInstruction(first, 1_200L)
            val far = reference(navigator, point.copy(latitude = 37.0006, elapsedRealtimeMs = 8_000L), 8_000L, 90.0)
            assertNotNull(far.instruction)
            if (reserveThenFail) {
                assertTrue(navigator.reserveInstruction(far))
                navigator.releaseInstruction(far, 8_100L)
            }
            navigator.acknowledgeInstruction(far, 8_200L)
            val sameAsDelivered = reference(navigator, point.copy(elapsedRealtimeMs = 15_000L), 15_000L, 90.0)
            assertEquals("guidance_already_completed", sameAsDelivered.reason)
            assertNull(sameAsDelivered.instruction)
            assertNoProgress(navigator)
        }
    }

    @Test fun aFreshManualReferenceCanResumeAfterPositionEvidenceWasInterrupted() {
        val point = northOfStart(1_000L)
        val navigator = navigatorWithOrigin(point)
        val first = reference(navigator, point, 1_100L, 90.0)
        assertTrue(navigator.reserveInstruction(first))
        navigator.acknowledgeInstruction(first, 1_200L)
        navigator.onPositioningEvidenceInterrupted()
        assertNull(navigator.currentGuidance(2_000L, facing(90.0, 2_000L)))
        val fresh = reference(navigator, point.copy(elapsedRealtimeMs = 8_000L), 8_000L, 90.0)
        assertNull(fresh.instruction)
        val resumed = requireNotNull(navigator.currentGuidance(8_100L, facing(90.0, 8_100L)))
        assertWaiting(resumed)
        assertTrue(navigator.reserveInstruction(resumed))
        assertTrue(navigator.isInstructionCurrent(resumed, 8_100L, facing(90.0, 8_100L)))
        assertTrue(navigator.isWaitingForInitialRouteJoin())
        assertNoProgress(navigator)
    }

    @Test fun anInvalidRouteCannotProvideAnInitialReference() {
        val route = straightRoute().let { it.copy(polyline = listOf(it.polyline.first(), RoutePoint(Double.NaN, 127.0))) }
        val navigator = navigator(route)
        val update = reference(navigator, northOfStart(1_000L), 1_100L, 90.0)
        assertNull(update.instruction)
        assertTrue(navigator.isWaitingForInitialRouteJoin())
        assertNoProgress(navigator)
    }

    // Isolate the opt-in join reference and connector lease; the production timer has its own default tests.
    private fun navigatorWithOrigin(origin: TrustedLocation) = RouteNavigator(RouteNavigatorConfig(directionRecheckGraceMs = 0L)).apply {
        setRoute(straightRoute(), origin = origin, waitForInitialRouteJoin = true)
    }

    private fun reference(navigator: RouteNavigator, point: TrustedLocation, nowMs: Long, heading: Double) =
        navigator.update(point, nowMs, false, filteredPosition = filtered(point), facingObservation = facing(heading, nowMs))

    private fun stableReference(navigator: RouteNavigator, point: TrustedLocation, atMs: Long, heading: Double): RouteNavigatorUpdate {
        reference(navigator, point.copy(elapsedRealtimeMs = atMs), atMs, heading)
        return reference(navigator, point.copy(elapsedRealtimeMs = atMs + 100L), atMs + 100L, heading)
    }

    private fun facing(heading: Double, atMs: Long) = RouteFacingObservation(heading, 1.0, atMs)

    private fun northOfStart(atMs: Long) = TrustedLocation(37.0002, 127.0, 1f, atMs)

    private fun assertWaiting(update: RouteNavigatorUpdate) {
        assertTrue(update.reason, update.reason in setOf("waiting_for_route_join", "route_guidance",
            "guidance_in_flight", "guidance_rate_limited", "guidance_already_completed", "guidance_retry_wait",
            "route_geometry_recheck_wait"))
        update.instruction?.let {
            assertTrue(update.directionOnly)
            assertNotNull(update.speechCueToken)
            assertFalse(it.contains("경로에 합류하면 안내를 이어갑니다"))
            assertTrue(it.contains("직선거리"))
            assertFalse(it.contains("직진하세요"))
            assertFalse(it.contains("이동하세요"))
            assertFalse(it.contains("길안내를 시작합니다"))
        }
        assertFalse(update.arrived)
        assertFalse(update.offRoute)
        assertFalse(update.shouldReroute)
        assertFalse(update.userDecisionRequired)
        assertNull(update.guideIndex)
    }

    private fun assertWaitingOutputs(navigator: RouteNavigator, point: TrustedLocation, nowMs: Long) {
        assertNull(navigator.currentBearingDeg())
        assertNull(navigator.currentProjection(point))
        assertNull(navigator.currentInstruction(point))
        assertNull(navigator.currentAcceptedRouteMatchFor(point.elapsedRealtimeMs))
        assertNull(navigator.currentTactileRouteProgress().segmentIndex)
        navigator.currentGuidance(nowMs)?.let(::assertWaiting)
        navigator.retryGuidance(nowMs)?.let(::assertWaiting)
        assertNull(navigator.pendingDecisionToken())
    }

    private fun assertNoProgress(navigator: RouteNavigator) {
        fun field(name: String) = RouteNavigator::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }.get(navigator)
        assertNull(field("progressDistanceM"))
        assertNull(field("matchedGeometricProgressM"))
        assertEquals(0, field("offRouteSampleCount"))
        assertEquals(0, field("arrivalSampleCount"))
        assertEquals(0, field("nextGuideIndex"))
    }

    private fun navigator(route: WalkingRoute = straightRoute(), degraded: Boolean = false) =
        RouteNavigator(RouteNavigatorConfig(directionRecheckGraceMs = 0L), allowDegradedRouteGuidance = degraded).apply {
            setRoute(route, waitForInitialRouteJoin = true)
        }

    private fun sample(navigator: RouteNavigator, point: TrustedLocation, nowMs: Long = point.elapsedRealtimeMs + 100L) =
        navigator.update(point, nowMs, false, filteredPosition = filtered(point))

    private fun filtered(point: TrustedLocation) = FilteredRoutePosition(
        RoutePoint(point.latitude, point.longitude), point.accuracyM.toDouble(), point.elapsedRealtimeMs,
        heading = RouteHeadingEstimate(90.0, 5.0),
    )

    private fun onRoute(atMs: Long) = TrustedLocation(37.0, 127.0003, 1f, atMs)
    private fun outside(atMs: Long) = TrustedLocation(37.00011, 127.0003, 1f, atMs)
    private fun far(atMs: Long) = TrustedLocation(37.0006, 127.0003, 1f, atMs)

    private fun straightRoute() = WalkingRoute("STAIR_AVOID", WalkingRouteSummary(200, 200),
        listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0, 127.002)),
        listOf(WalkingRouteGuidePoint(0, RoutePoint(37.0, 127.002), "도착", 200, 0, 90f)),
    )

    private fun parallelRoute() = WalkingRoute("STAIR_AVOID", WalkingRouteSummary(190, 180),
        listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0, 127.001),
            RoutePoint(37.00005, 127.001), RoutePoint(37.00005, 127.0)), emptyList(),
    )
}

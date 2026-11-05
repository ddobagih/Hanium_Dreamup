package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Actual RV measurements -> Navigator offer/reserve/current-at-START/DONE, without SDK or audio fakes. */
class RouteNavigatorBoundedClockFlowTest {
    @Test fun twoFreshReportedTwelveDegreeObservationsStartOneNominalClock() {
        val nav = navigator()
        val facing = rv(1_000L, 12f)
        assertEquals(12, RouteFacingGuidance.evaluate(0.0, facing, 1_000L).clockHour)
        assertFalse(RouteFacingGuidance.evaluate(0.0, facing, 1_000L).clockPrecisionAvailable)
        warmNominal(nav, facing)
        val cue = update(nav, 1_000L, facing)
        assertNominal(cue, 12)
        assertNotNull(nav.currentAcceptedRouteMatchFor(1_000L))
        assertFalse(nav.isDirectionRecheckPending())
        assertTrue(requireNotNull(cue.instruction).contains("약 50미터"))
        assertTrue(nav.reserveInstruction(cue))
        assertTrue(nav.isInstructionCurrent(cue, 1_000L, facing))
        nav.acknowledgeInstruction(cue, 1_000L)
        assertFalse(nav.isInstructionCurrent(cue, 1_000L, facing))
    }

    @Test fun twoFreshSectorBoundarySamplesProduceOneNominalHour() {
        val nav = navigator()
        val facing = rv(1_000L, 8f, 345.0)
        val measured = RouteFacingGuidance.evaluate(0.0, facing, 1_000L)
        assertEquals(1, measured.clockHour)
        assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK, measured.clockReferenceKind)
        assertNull(measured.clockRange)
        warmNominal(nav, facing)
        assertNominal(update(nav, 1_000L, facing), 1)
        assertFalse(nav.isDirectionRecheckPending())
    }

    @Test fun twoFreshNominalSamplesEndAnExistingRecheckBeforeItsDeadline() {
        val nav = navigator()
        val waiting = update(nav, 1_000L, null)
        assertEquals("guidance_facing_recheck_wait", waiting.reason)
        assertTrue(nav.isDirectionRecheckPending())
        assertNull(nav.retryGuidance(1_100L, rv(1_100L, 12f))?.instruction)
        assertNull(nav.retryGuidance(1_105L, rv(1_100L, 12f))?.instruction)
        val recovered = requireNotNull(nav.retryGuidance(1_110L, rv(1_110L, 12f)))
        assertNominal(recovered, 12)
        assertFalse(nav.isDirectionRecheckPending())
    }

    @Test fun nominalClockOwnsTheOriginalFiveHundredMillisecondStartDeadline() {
        val nav = navigator()
        val original = rv(800L, 12f)
        warmNominal(nav, original)
        val cue = update(nav, 1_000L, original)
        assertNominal(cue, 12)
        val token = requireNotNull(cue.speechCueToken)
        assertEquals(800L, token.facingObservedAtElapsedRealtimeMs)
        assertEquals(1_300L, token.facingValidUntilElapsedRealtimeMs)
        assertTrue(nav.reserveInstruction(cue))
        assertTrue(nav.isInstructionCurrent(cue, 1_300L, rv(1_300L, 12f)))
        assertFalse(nav.isInstructionCurrent(cue, 1_301L, rv(1_301L, 12f)))
        assertFalse("Fresh supporting observations are not invalidated by an old token deadline",
            nav.hasInvalidatedDirectionClaim(1_301L, rv(1_301L, 12f)))
        val playing = requireNotNull(nav.retryGuidance(1_301L, rv(1_301L, 12f)))
        assertEquals("guidance_in_flight", playing.reason)
        assertFalse(playing.cancelStaleNavigationSpeech)
        assertEquals(1_300L, token.facingValidUntilElapsedRealtimeMs)
    }

    @Test fun queuedNominalClockAllowsFreshSameClockIncludingReportedPrecision() {
        for (fresh in listOf(rv(1_100L, 0.1f), rv(1_100L, 8f))) {
            val nav = navigator()
            warmNominal(nav, rv(1_000L, 12f))
        val cue = update(nav, 1_000L, rv(1_000L, 12f))
            assertNominal(cue, 12)
            assertTrue(nav.reserveInstruction(cue))
            val measured = RouteFacingGuidance.evaluate(0.0, fresh, 1_100L)
            assertEquals(RouteFacingDirection.FRONT, measured.direction)
            assertTrue(measured.hasClockReference)
            assertTrue(nav.isInstructionCurrent(cue, 1_100L, fresh))
        }
    }

    @Test fun shiftedNominalClockRejectsQueuedStartEvenWithTheSameCoarseFrontDirection() {
        for (fresh in listOf(rv(1_100L, 12f), rv(1_100L, 8f, 15.0))) {
            val nav = navigator()
            warmNominal(nav, rv(1_000L, 8f, 345.0))
        val cue = update(nav, 1_000L, rv(1_000L, 8f, 345.0))
            assertNominal(cue, 1)
            assertTrue(nav.reserveInstruction(cue))
            val measured = RouteFacingGuidance.evaluate(0.0, fresh, 1_100L)
            assertEquals(RouteFacingDirection.FRONT, measured.direction)
            assertTrue(measured.hasClockReference)
            assertFalse(nav.isInstructionCurrent(cue, 1_100L, fresh))
        }
    }

    @Test fun readOnlyExpansionAndActualRetryPreservePlayingButQueuedStartRemainsStrict() {
        val nav = navigator()
        warmNominal(nav, rv(1_000L, 8f, 345.0))
        val cue = update(nav, 1_000L, rv(1_000L, 8f, 345.0))
        assertNominal(cue, 1)
        assertTrue(nav.reserveInstruction(cue))
        assertTrue(nav.isInstructionCurrent(cue, 1_000L, rv(1_000L, 8f, 345.0)))
        val expanded = rv(1_100L, 12f)
        assertFalse(nav.isDirectionRecheckPending())
        repeat(3) { assertTrue(nav.hasInvalidatedDirectionClaim(1_100L, expanded)) }
        assertFalse(nav.isDirectionRecheckPending())
        assertNotNull(nav.currentAcceptedRouteMatchFor(1_000L))
        assertNull(nav.currentAcceptedRouteMatchFor(1_100L))
        val refreshed = requireNotNull(nav.retryGuidance(1_100L, expanded))
        assertEquals("guidance_in_flight", refreshed.reason)
        assertFalse(refreshed.cancelStaleNavigationSpeech)
        assertFalse(nav.reserveInstruction(cue))
        val confirmed = rv(1_101L, 12f)
        assertFalse(requireNotNull(nav.retryGuidance(1_101L, confirmed)).cancelStaleNavigationSpeech)
        assertFalse(nav.isInstructionCurrent(cue, 1_101L, confirmed))
        assertEquals(1_000L, cue.speechCueToken?.facingObservedAtElapsedRealtimeMs)
        assertEquals(1_500L, cue.speechCueToken?.facingValidUntilElapsedRealtimeMs)
    }

    @Test fun sameConfirmedNominalClockPreservesPlayingReportedClockAndSupportsItsQueuedStart() {
        val nav = navigator()
        val exact = update(nav, 1_000L, rv(1_000L, 0.1f))
        assertEquals(RouteFacingExpression.CLOCK, exact.facingDiagnostic?.expression)
        assertTrue(nav.reserveInstruction(exact))
        assertTrue(nav.isInstructionCurrent(exact, 1_000L, rv(1_000L, 0.1f)))
        val rangeFacing = rv(1_100L, 12f)
        val measured = RouteFacingGuidance.evaluate(0.0, rangeFacing, 1_100L)
        assertEquals(12, measured.clockHour)
        assertTrue(measured.hasClockReference)
        assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK, measured.clockReferenceKind)
        assertNull(measured.clockRange)
        assertTrue(nav.hasInvalidatedDirectionClaim(1_100L, rangeFacing))
        assertFalse(nav.isInstructionCurrent(exact, 1_100L, rangeFacing))
        val refreshed = requireNotNull(nav.retryGuidance(1_100L, rangeFacing))
        assertEquals("guidance_in_flight", refreshed.reason)
        assertFalse(refreshed.cancelStaleNavigationSpeech)
        assertFalse(nav.reserveInstruction(exact))
        val confirmed = rv(1_101L, 12f)
        val retained = requireNotNull(nav.retryGuidance(1_101L, confirmed))
        assertEquals("guidance_in_flight", retained.reason)
        assertFalse(retained.cancelStaleNavigationSpeech)
        assertNull(retained.instruction)
        assertTrue(nav.isInstructionCurrent(exact, 1_101L, confirmed))
        assertTrue(nav.isInstructionCompletionCurrent(exact, 1_101L))
        assertEquals(1_000L, exact.speechCueToken?.facingObservedAtElapsedRealtimeMs)
        assertEquals(1_500L, exact.speechCueToken?.facingValidUntilElapsedRealtimeMs)
        nav.acknowledgeInstruction(exact, 1_101L)
        assertFalse(nav.isInstructionCompletionCurrent(exact, 1_101L))
        assertNominal(requireNotNull(nav.currentGuidance(1_102L, rv(1_102L, 12f))), 12)
    }

    @Test fun changingFromFallbackToNominalClockStillNeedsTwoDistinctFreshRvSamples() {
        val nav = navigator()
        val fallback = fallback(1_000L)
        assertTrue(RouteFacingGuidance.isUsableObservation(fallback, 1_000L))
        assertEquals("guidance_facing_recheck_wait", update(nav, 1_000L, fallback).reason)
        val firstRv = rv(1_100L, 12f)
        assertNull(nav.retryGuidance(1_100L, firstRv)?.instruction)
        assertNull(nav.retryGuidance(1_150L, firstRv)?.instruction)
        val confirmed = requireNotNull(nav.retryGuidance(1_200L, rv(1_200L, 12f)))
        assertNominal(confirmed, 12)
        assertFalse(nav.isDirectionRecheckPending())
    }

    @Test fun aCompletedNominalClockDeduplicatesExactClockAtTwoSecondsAndRepeatsAtFive() {
        val nav = navigator()
        warmNominal(nav, rv(1_000L, 12f))
        val range = update(nav, 1_000L, rv(1_000L, 12f))
        assertNominal(range, 12)
        assertTrue(nav.reserveInstruction(range))
        nav.acknowledgeInstruction(range, 1_000L)
        assertNull(update(nav, 1_100L, rv(1_100L, 0.1f)).instruction)
        assertNull(update(nav, 1_200L, rv(1_200L, 0.1f)).instruction)
        assertNull(update(nav, 2_999L, rv(2_999L, 0.1f)).instruction)
        val twoSeconds = update(nav, 3_000L, rv(3_000L, 0.1f))
        assertNull(twoSeconds.instruction)
        assertEquals("guidance_already_completed", twoSeconds.reason)
        assertNull(update(nav, 5_999L, rv(5_999L, 0.1f)).instruction)
        val exactFacing = rv(6_000L, 0.1f)
        val exact = update(nav, 6_000L, exactFacing)
        assertEquals("route_guidance", exact.reason)
        assertEquals(RouteFacingExpression.CLOCK, exact.facingDiagnostic?.expression)
        assertTrue(requireNotNull(exact.instruction).contains("약 12시 방향"))
        assertFalse(exact.instruction.orEmpty().contains("사이 방향"))
        assertTrue(nav.reserveInstruction(exact))
        assertTrue(nav.isInstructionCurrent(exact, 6_000L, exactFacing))
        nav.acknowledgeInstruction(exact, 6_000L)
    }

    @Test fun anActuallyCompletedExactClockUsesTheFiveSecondPeriodicRepeat() {
        val nav = navigator()
        val first = update(nav, 1_000L, rv(1_000L, 0.1f))
        assertEquals(RouteFacingExpression.CLOCK, first.facingDiagnostic?.expression)
        assertTrue(nav.reserveInstruction(first))
        nav.acknowledgeInstruction(first, 1_000L)
        val twoSeconds = update(nav, 3_000L, rv(3_000L, 0.1f))
        assertEquals("guidance_already_completed", twoSeconds.reason)
        assertNull(twoSeconds.instruction)
        val periodic = update(nav, 6_000L, rv(6_000L, 0.1f))
        assertEquals(RouteFacingExpression.CLOCK, periodic.facingDiagnostic?.expression)
        assertNotNull(periodic.instruction)
    }

    @Test fun closestPointNominalClockKeepsDistanceAndNoAcceptedWalkableConnection() {
        val origin = fix(1_000L, join = true)
        val nav = navigator(origin)
        val facing = rv(1_000L, 12f)
        nav.update(origin.copy(elapsedRealtimeMs = 999L), 999L, false,
            filteredPosition = FilteredRoutePosition(RoutePoint(origin.latitude, origin.longitude), 1.0, 999L),
            facingObservation = rv(999L, 12f))
        val cue = nav.update(origin, 1_000L, false,
            filteredPosition = FilteredRoutePosition(RoutePoint(origin.latitude, origin.longitude), 1.0, 1_000L),
            facingObservation = facing)
        assertNominal(cue, 9)
        val text = requireNotNull(cue.instruction)
        assertTrue(text, text.contains("직선거리 약 22미터"))
        assertFalse(text, text.contains("연결 통로는 확인되지 않았"))
        assertTrue(cue.directionOnly)
        assertTrue(nav.isWaitingForInitialRouteJoin())
        assertNull(nav.currentAcceptedRouteMatchFor(1_000L))
        assertFalse(cue.arrived)
        assertFalse(cue.shouldReroute)
        assertTrue(nav.reserveInstruction(cue))
        assertTrue(nav.isInstructionCurrent(cue, 1_000L, facing))
    }

    @Test fun aNewRouteRetiresTheOldNominalClockOwnership() {
        val nav = navigator()
        val facing = rv(1_000L, 12f)
        warmNominal(nav, facing)
        val old = update(nav, 1_000L, facing)
        assertNominal(old, 12)
        assertTrue(nav.reserveInstruction(old))
        nav.setRoute(route())
        assertFalse(nav.isInstructionCurrent(old, 1_100L, rv(1_100L, 12f)))
        assertFalse(nav.hasInvalidatedDirectionClaim(1_100L, null))
        warmNominal(nav, rv(1_200L, 12f))
        assertNominal(update(nav, 1_200L, rv(1_200L, 12f)), 12)
    }

    @Test fun magneticRecoveryQueuedStartIsRejectedWhenConfirmedNominalClockArrives() {
        val nav = navigator()
        assertEquals("guidance_facing_recheck_wait", update(nav, 1_000L, magneticRange(1_000L, 100.0).observation).reason)
        val anomalous = magneticRange(2_500L, 100.0)
        val recovery = update(nav, 2_500L, anomalous.observation)
        assertTrue(requireNotNull(recovery.instruction).contains("자기장 영향"))
        assertEquals(RouteFacingExpression.UNKNOWN, recovery.facingDiagnostic?.expression)
        assertFalse(requireNotNull(recovery.speechCueToken).requiresFacingEvidence)
        assertTrue(nav.reserveInstruction(recovery))
        assertTrue(nav.isInstructionCurrent(recovery, 2_500L, anomalous.observation))
        val normal = magneticRange(2_600L, 50.0)
        assertEquals(RouteFacingGuidance.evaluate(0.0, anomalous.observation, 2_500L).direction,
            RouteFacingGuidance.evaluate(0.0, normal.observation, 2_600L).direction)
        assertEquals(12, RouteFacingGuidance.evaluate(0.0, normal.observation, 2_600L).clockHour)
        assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK, RouteFacingGuidance.evaluate(0.0, normal.observation, 2_600L).clockReferenceKind)
        assertTrue(RouteFacingGuidance.evaluate(0.0, normal.observation, 2_600L).hasClockReference)
        val firstNormal = update(nav, 2_599L, magneticRange(2_599L, 50.0).observation)
        assertTrue(firstNormal.cancelStaleNavigationSpeech)
        assertFalse("The old magnetic explanation is obsolete when healthy heading arrives",
            nav.isInstructionCurrent(recovery, 2_599L, magneticRange(2_599L, 50.0).observation))
        val range = update(nav, 2_600L, normal.observation)
        assertNominal(range, 12)
        assertFalse(nav.reserveInstruction(recovery))
        assertTrue(nav.reserveInstruction(range))
        assertTrue(nav.isInstructionCurrent(range, 2_600L, normal.observation))
        // The semantic recovery exception must not bypass the existing failed-delivery backoff.
        nav.releaseInstruction(range, 2_700L)
        assertEquals("guidance_retry_wait", update(nav, 2_800L, magneticRange(2_800L, 50.0).observation).reason)
        assertEquals("guidance_retry_wait", update(nav, 4_699L, magneticRange(4_699L, 50.0).observation).reason)
        assertNominal(update(nav, 4_700L, magneticRange(4_700L, 50.0).observation), 12)
    }

    @Test fun completedMagneticRecoveryDoesNotDelayTheConfirmedNominalClock() {
        val nav = navigator()
        assertEquals("guidance_facing_recheck_wait", update(nav, 1_000L, magneticRange(1_000L, 100.0).observation).reason)
        val anomalous = magneticRange(2_500L, 100.0)
        val recovery = update(nav, 2_500L, anomalous.observation)
        assertTrue(requireNotNull(recovery.instruction).contains("자기장 영향"))
        assertTrue(nav.reserveInstruction(recovery))
        assertTrue(nav.isInstructionCurrent(recovery, 2_500L, anomalous.observation))
        nav.acknowledgeInstruction(recovery, 2_500L)
        assertFalse(nav.isInstructionCurrent(recovery, 2_500L, anomalous.observation))
        val normal = magneticRange(2_600L, 50.0)
        update(nav, 2_599L, magneticRange(2_599L, 50.0).observation)
        val firstDirection = update(nav, 2_600L, normal.observation)
        assertNominal(firstDirection, 12)
        assertTrue(nav.reserveInstruction(firstDirection))
        assertTrue(nav.isInstructionCurrent(firstDirection, 2_600L, normal.observation))
        nav.acknowledgeInstruction(firstDirection, 2_600L)
    }

    @Test fun mappedNearTurnDoesNotOwnAnUnspokenBodyClock() {
        fun cornerPoint(east: Double, north: Double = 0.0) =
            RoutePoint(north / 111_194.92664455874, east / 111_194.92664455874)
        val nav = RouteNavigator().apply {
            setRoute(WalkingRoute("CORNER", WalkingRouteSummary(100, 100),
                listOf(cornerPoint(0.0), cornerPoint(20.0), cornerPoint(20.0, -80.0)),
                listOf(WalkingRouteGuidePoint(0, cornerPoint(20.0), null, 20, 80, turnType = 13))))
        }
        fun cornerUpdate(at: Long, east: Double, facing: RouteFacingObservation): RouteNavigatorUpdate {
            val point = cornerPoint(east)
            return nav.update(TrustedLocation(point.latitude, point.longitude, 3f, at), at, false,
                facingObservation = facing)
        }
        cornerUpdate(999L, 10.0, rv(999L, 12f, 90.0))
        assertNotNull(cornerUpdate(1_000L, 10.0, rv(1_000L, 12f, 90.0)).instruction)
        val vertexFacing = rv(2_000L, 12f, 180.0)
        val mappedTurn = cornerUpdate(2_000L, 20.0, vertexFacing)
        assertEquals(RouteAlignmentReason.TURN_PASSAGE_PENDING, mappedTurn.routeAlignmentDiagnostic?.reason)
        val text = requireNotNull(mappedTurn.instruction)
        assertTrue(text, text.contains("경로 기준"))
        assertTrue(text, text.contains("3시"))
        assertFalse(text, text.contains("사이 방향"))
        assertEquals(RouteFacingExpression.NOT_SPOKEN, mappedTurn.facingDiagnostic?.expression)
        assertFalse(requireNotNull(mappedTurn.speechCueToken).requiresFacingEvidence)
        assertTrue(nav.reserveInstruction(mappedTurn))
        assertTrue(nav.isInstructionCurrent(mappedTurn, 2_000L, vertexFacing))
        val changedBodyFacing = rv(2_100L, 12f, 270.0)
        assertTrue("The mapped turn is in the route frame, not a current body-clock claim",
            nav.isInstructionCurrent(mappedTurn, 2_100L, changedBodyFacing))
        val continuing = requireNotNull(nav.retryGuidance(2_100L, changedBodyFacing))
        assertEquals("guidance_in_flight", continuing.reason)
        assertFalse(continuing.cancelStaleNavigationSpeech)
        assertTrue(nav.isInstructionCurrent(mappedTurn, 2_200L, null))
    }

    private fun magneticRange(at: Long, fieldMicrotesla: Double): RouteCompassHeadingResult {
        val primary = RouteCompassHeading.evaluate(DeviceEarthOrientation(
            RotationMatrix3(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f),
            at, 12f, EarthOrientationAccuracy.HIGH), 0.0, at)
        val checked = RouteCompassHeading.crossCheckMagneticField(primary,
            RouteCompassVectorSample(0.0, 0.0, -fieldMicrotesla, at, EarthOrientationAccuracy.HIGH),
            ChestMountedGeomagneticReference(0.0, 0.0, 50.0), at)
        assertEquals(RouteCompassHeadingReason.VALID, checked.reason)
        assertEquals(fieldMicrotesla == 100.0, requireNotNull(checked.observation).magneticFieldSuspect)
        return checked
    }

    private fun assertNominal(cue: RouteNavigatorUpdate, hour: Int) {
        assertEquals("route_guidance", cue.reason)
        val text = requireNotNull(cue.instruction)
        assertTrue(text, text.contains("바라보고 있는 방향 기준"))
        assertTrue(text, text.contains("약 ${hour}시 방향"))
        assertEquals(RouteFacingExpression.CLOCK, cue.facingDiagnostic?.expression)
        assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK, cue.facingDiagnostic?.clockReferenceKind)
        assertFalse(text, text.contains("시에서") || text.contains("사이 방향"))
        assertTrue(requireNotNull(cue.speechCueToken).requiresFacingEvidence)
        assertFalse(cue.arrived)
        assertFalse(cue.shouldReroute)
    }

    private fun warmNominal(nav: RouteNavigator, facing: RouteFacingObservation) {
        val at = facing.observedAtElapsedRealtimeMs - 1L
        val earlier = rv(at, requireNotNull(facing.reportedHeadingAccuracyDegrees).toFloat(), facing.degreesTrueNorth)
        val first = update(nav, at, earlier)
        assertFalse(first.instruction.orEmpty().contains("바라보고 있는 방향 기준 약"))
        assertEquals(at, earlier.observedAtElapsedRealtimeMs)
        assertEquals(facing.reportedHeadingAccuracyDegrees, earlier.reportedHeadingAccuracyDegrees)
    }
    private fun navigator(origin: TrustedLocation? = null) = RouteNavigator().apply {
        setRoute(route(), origin = origin, waitForInitialRouteJoin = origin != null)
    }
    private fun update(nav: RouteNavigator, at: Long, facing: RouteFacingObservation?): RouteNavigatorUpdate =
        nav.update(fix(at), at, false, filteredPosition = FilteredRoutePosition(point(50.0), 6.0, at),
            facingObservation = facing)
    private fun fix(at: Long, join: Boolean = false) = point(50.0, if (join) 22.0 else 0.0).let {
        TrustedLocation(it.latitude, it.longitude, if (join) 1f else 8f, at)
    }
    private fun route() = WalkingRoute("RANGE", WalkingRouteSummary(200, 200),
        listOf(point(0.0), point(100.0), point(100.0, 100.0)),
        listOf(WalkingRouteGuidePoint(0, point(100.0), null, 100, 100, turnType = 13)))
    private fun point(north: Double, east: Double = 0.0) = RoutePoint(north / 111_194.92664455874, east / 111_194.92664455874)
    private fun rv(at: Long, error: Float, heading: Double = 0.0): RouteFacingObservation {
        val angle = Math.toRadians(heading)
        val rotation = RotationMatrix3(cos(angle).toFloat(), 0f, (-sin(angle)).toFloat(),
            (-sin(angle)).toFloat(), 0f, (-cos(angle)).toFloat(), 0f, 1f, 0f)
        val result = RouteCompassHeading.evaluate(DeviceEarthOrientation(rotation, at, error, EarthOrientationAccuracy.HIGH), 0.0, at)
        assertEquals(RouteCompassHeadingReason.VALID, result.reason)
        return requireNotNull(result.observation)
    }
    private fun fallback(at: Long): RouteFacingObservation {
        val gravity = RouteCompassVectorSample(0.0, 9.80665, 0.0, at, EarthOrientationAccuracy.HIGH)
        return requireNotNull(RouteCompassHeading.evaluateFallback(gravity, gravity,
            RouteCompassVectorSample(0.0, 0.0, -50.0, at, EarthOrientationAccuracy.HIGH),
            ChestMountedGeomagneticReference(0.0, 0.0, 50.0), at).observation)
    }
}

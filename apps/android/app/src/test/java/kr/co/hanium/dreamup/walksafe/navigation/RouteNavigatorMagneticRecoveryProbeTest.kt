package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import org.junit.Assert.*
import org.junit.Test

/** Real Heading cross-check -> real Navigator; no SDK or native audio simulation. */
class RouteNavigatorMagneticRecoveryProbeTest {
    @Test fun currentMagneticAnomalyGivesItsRecoveryActionAfterTheOriginalGrace() {
        val nav = navigator()
        assertWaiting(update(nav, 1_000L, checked(1_000L, 100.0)))
        assertWaiting(update(nav, 2_499L, checked(2_499L, 100.0)))
        val result = checked(2_500L, 100.0)
        assertAnomaly(result, 2_500L)
        val cue = update(nav, 2_500L, result)
        assertNotNull(nav.currentAcceptedRouteMatchFor(2_500L))
        assertRecoveryOnly(cue)
        assertFalse(cue.directionOnly)
        assertTrue(nav.reserveInstruction(cue))
        assertTrue(nav.isInstructionCurrent(cue, 2_500L, result.observation))
    }

    @Test fun initialClosestRoutePointReferenceAlsoExplainsMagneticRecovery() {
        val nav = navigator(join = true)
        assertWaiting(update(nav, 1_000L, checked(1_000L, 100.0), join = true))
        assertWaiting(update(nav, 2_499L, checked(2_499L, 100.0), join = true))
        val result = checked(2_500L, 100.0)
        assertAnomaly(result, 2_500L)
        val cue = update(nav, 2_500L, result, join = true)
        assertTrue(nav.isWaitingForInitialRouteJoin())
        assertNull(nav.currentAcceptedRouteMatchFor(2_500L))
        assertTrue(cue.directionOnly)
        assertRecoveryOnly(cue)
        assertTrue(nav.reserveInstruction(cue))
        assertTrue(nav.isInstructionCurrent(cue, 2_500L, result.observation))
    }

    @Test fun normalFieldKeepsTheMeasuredClockAndDistance() = assertClock(checked(1_000L, 50.0))

    @Test fun absentMagneticSensorDoesNotInventAnAnomaly() = assertClock(checked(1_000L, null))

    @Test fun expiredAnomalousMagneticSampleDoesNotOverrideFreshRotationVector() =
        assertClock(checked(1_000L, 100.0, magneticAt = 499L))

    @Test fun normalFieldPreservesTheUnconfirmedClosestPointClockReference() {
        val nav = navigator(join = true)
        val result = checked(1_000L, 50.0)
        val cue = update(nav, 1_000L, result, join = true)
        val text = requireNotNull(cue.instruction)
        assertTrue(text, text.contains("약 9시 방향"))
        assertTrue(text, text.contains("직선거리 약 22미터"))
        assertFalse(text, text.contains("연결 통로는 확인되지 않았"))
        assertEquals(RouteFacingExpression.CLOCK, cue.facingDiagnostic?.expression)
        assertTrue(cue.directionOnly)
        assertTrue(nav.isWaitingForInitialRouteJoin())
        assertNull(nav.currentAcceptedRouteMatchFor(1_000L))
        assertFalse(text, text.contains("자석"))
        assertTrue(nav.reserveInstruction(cue))
        assertTrue(nav.isInstructionCurrent(cue, 1_000L, result.observation))
    }

    @Test fun aShortMagneticAnomalyPreservesPlayingUntilItsFixedRecoveryDeadline() {
        val nav = navigator()
        val clock = update(nav, 1_000L, checked(1_000L, 50.0))
        assertEquals(RouteFacingExpression.CLOCK, clock.facingDiagnostic?.expression)
        assertTrue(nav.reserveInstruction(clock))
        val bad = checked(1_100L, 100.0)
        val interrupted = update(nav, 1_100L, bad)
        assertEquals("guidance_in_flight", interrupted.reason)
        assertNull(interrupted.instruction)
        assertFalse(interrupted.cancelStaleNavigationSpeech)
        assertEquals(2_600L, nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        assertTrue(nav.isInstructionCurrent(clock, 1_100L, bad.observation))
        assertEquals(RouteDirectionGraceAuthorization.NORMAL_PENDING, nav.currentDirectionGraceSnapshot()?.authorizationKind)
        val retained = update(nav, 2_599L, checked(2_599L, 100.0))
        assertEquals("guidance_in_flight", retained.reason)
        assertFalse(retained.cancelStaleNavigationSpeech)
        val problem = update(nav, 2_600L, checked(2_600L, 100.0))
        assertTrue(problem.cancelStaleNavigationSpeech)
        assertRecoveryOnly(problem)
    }

    private fun assertClock(result: RouteCompassHeadingResult) {
        assertEquals(RouteCompassHeadingReason.VALID, result.reason)
        assertFalse(requireNotNull(result.observation).magneticFieldSuspect)
        assertEquals(12, RouteFacingGuidance.evaluate(0.0, result.observation, 1_000L).clockHour)
        val nav = navigator()
        val cue = update(nav, 1_000L, result)
        val text = requireNotNull(cue.instruction)
        assertNotNull(nav.currentAcceptedRouteMatchFor(1_000L))
        assertEquals(RouteFacingExpression.CLOCK, cue.facingDiagnostic?.expression)
        assertTrue(text, text.contains("약 12시 방향"))
        assertTrue(text, text.contains("약 50미터"))
        assertFalse(text, text.contains("자석") || text.contains("금속"))
        assertTrue(nav.reserveInstruction(cue))
        assertTrue(nav.isInstructionCurrent(cue, 1_000L, result.observation))
    }

    private fun assertAnomaly(result: RouteCompassHeadingResult, at: Long) {
        // The real producer deliberately retains VALID; only its field-suspect evidence changes.
        assertEquals(RouteCompassHeadingReason.VALID, result.reason)
        assertTrue(requireNotNull(result.observation).magneticFieldSuspect)
        assertEquals(RouteCompassRejectedCheck.MAGNETIC_FIELD_STRENGTH, result.checkEvidence?.rejectedCheck)
        assertTrue(RouteFacingGuidance.isUsableObservation(result.observation, at))
        assertNull(RouteFacingGuidance.evaluate(0.0, result.observation, at).clockHour)
    }

    private fun assertWaiting(cue: RouteNavigatorUpdate) {
        assertEquals("guidance_facing_recheck_wait", cue.reason)
        assertNull(cue.instruction)
    }

    private fun assertRecoveryOnly(cue: RouteNavigatorUpdate) {
        assertEquals("route_guidance", cue.reason)
        val text = requireNotNull(cue.instruction)
        assertEquals("자기장 영향으로 방향이 불안정할 수 있습니다. 방향을 다시 측정하고 있습니다.", text.trim())
        assertEquals(RouteFacingExpression.UNKNOWN, cue.facingDiagnostic?.expression)
        assertFalse(requireNotNull(cue.speechCueToken).requiresFacingEvidence)
        assertFalse(cue.arrived)
        assertFalse(cue.shouldReroute)
        assertFalse(cue.userDecisionRequired)
        assertFalse(text, text.contains("시 방향") || text.contains("다음 안내 지점") || text.contains("꺾으세요"))
    }

    private fun checked(at: Long, field: Double?, magneticAt: Long = at): RouteCompassHeadingResult {
        // Device -Z points north; error is a real reported primary error, not synthesized CLOCK data.
        val rotation = RotationMatrix3(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
        val primary = RouteCompassHeading.evaluate(
            DeviceEarthOrientation(rotation, at, 0.1f, EarthOrientationAccuracy.HIGH), 0.0, at)
        return RouteCompassHeading.crossCheckMagneticField(primary,
            field?.let { RouteCompassVectorSample(0.0, 0.0, -it, magneticAt, EarthOrientationAccuracy.HIGH) },
            ChestMountedGeomagneticReference(0.0, 0.0, 50.0), at)
    }

    private fun navigator(join: Boolean = false) = RouteNavigator().apply {
        setRoute(route(), origin = if (join) fix(1_000L, true) else null, waitForInitialRouteJoin = join)
    }
    private fun update(nav: RouteNavigator, at: Long, facing: RouteCompassHeadingResult, join: Boolean = false): RouteNavigatorUpdate {
        val location = fix(at, join)
        return nav.update(location, at, false,
            filteredPosition = FilteredRoutePosition(RoutePoint(location.latitude, location.longitude), if (join) 1.0 else 6.0, at),
            facingObservation = facing.observation, facingUnavailableReason = facing.reason)
    }
    private fun fix(at: Long, join: Boolean) = point(50.0, if (join) 22.0 else 0.0).let {
        TrustedLocation(it.latitude, it.longitude, if (join) 1f else 8f, at)
    }
    private fun point(north: Double, east: Double = 0.0) = RoutePoint(north / 111_194.92664455874, east / 111_194.92664455874)
    private fun route() = WalkingRoute("MAGNETIC_PROBE", WalkingRouteSummary(200, 200),
        listOf(point(0.0), point(100.0), point(100.0, 100.0)),
        listOf(WalkingRouteGuidePoint(0, point(100.0), null, 100, 100, turnType = 13)))
}

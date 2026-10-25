package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import org.junit.Assert.*
import org.junit.Test

/** The fallback's policy range is useful without reporting an invented angular accuracy. */
class RouteNominalHeadingGuidanceRegressionTest {
    private val reference = ChestMountedGeomagneticReference(0.0, 53.13, 50.0)
    private val northRear = RotationMatrix3(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
    private fun state() = RouteCompassSensorState().apply { start(1, 900L, RouteCompassStream.entries.toSet()) }
    private fun input(state: RouteCompassSensorState, at: Long, error: Float = Float.NaN,
        scale: Double = 1.0, withMotionCheck: Boolean = true, heading: Double = 0.0): RouteCompassHeadingResult {
        val angle = Math.toRadians(heading)
        val rotation = RotationMatrix3(kotlin.math.cos(angle).toFloat(), 0f, (-kotlin.math.sin(angle)).toFloat(),
            (-kotlin.math.sin(angle)).toFloat(), 0f, (-kotlin.math.cos(angle)).toFloat(), 0f, 1f, 0f)
        state.onRotationVector(1, rotation, error, at, EarthOrientationAccuracy.HIGH, at)
        val gravity = RouteCompassVectorSample(0.0, 9.80665, 0.0, at, EarthOrientationAccuracy.HIGH)
        state.onVector(1, RouteCompassStream.GRAVITY, gravity, at)
        if (withMotionCheck) state.onVector(1, RouteCompassStream.ACCELEROMETER, gravity, at)
        state.onVector(1, RouteCompassStream.MAGNETIC_FIELD,
            RouteCompassVectorSample(-30.0 * kotlin.math.sin(angle) * scale, -40.0 * scale,
                -30.0 * kotlin.math.cos(angle) * scale, at, EarthOrientationAccuracy.HIGH), at)
        return state.evaluate(at, reference)
    }
    private fun point(north: Double, east: Double = 0.0) = RoutePoint(north / 111194.92664455874, east / 111194.92664455874)
    private fun route() = WalkingRoute("SYNTHETIC", WalkingRouteSummary(200, 200),
        listOf(point(0.0), point(100.0), point(100.0, 100.0)),
        listOf(WalkingRouteGuidePoint(0, point(100.0), null, 100, 100, turnType = 12)))
    private fun nav() = RouteNavigator().apply { setRoute(route()) }
    private fun fix(at: Long = 1000L) = point(50.0).let { TrustedLocation(it.latitude, it.longitude, 2f, at) }

    @Test fun twoRealFreshCalibratedSamplesOfferAndCompleteANominalClockAndMetres() {
        val state = state(); val nav = nav()
        val first = input(state, 1000L)
        assertNull(first.observation!!.headingAccuracyDegrees)
        assertNull(first.observation!!.reportedHeadingAccuracyDegrees)
        assertEquals("guidance_facing_recheck_wait", nav.update(fix(), 1000L, false,
            facingObservation = first.observation, facingUnavailableReason = first.reason).reason)
        assertEquals("guidance_facing_recheck_wait", nav.retryGuidance(1100L, first.observation, first.reason)!!.reason)
        val second = input(state, 1200L)
        val action = nav.retryGuidance(1200L, second.observation, second.reason)!!
        assertNominalAction(action)
        assertFalse(nav.isDirectionRecheckPending())
        assertEquals(RouteDirectionGraceMode.NORMAL, nav.currentDirectionGraceSnapshot()!!.mode)
        assertTrue(nav.reserveInstruction(action))
        assertTrue(nav.isInstructionCurrent(action, 1200L, second.observation))
        assertEquals(1200L, action.speechCueToken!!.facingObservedAtElapsedRealtimeMs)
        assertEquals(1700L, action.speechCueToken!!.facingValidUntilElapsedRealtimeMs)
        nav.acknowledgeInstruction(action, 1201L)
        assertEquals("guidance_rate_limited", nav.retryGuidance(1202L, second.observation, second.reason)!!.reason)
        assertNull(second.observation!!.headingAccuracyDegrees)
        assertNull(second.observation!!.reportedHeadingAccuracyDegrees)
    }

    @Test fun nominalClockKeepsTheExistingTwentyDegreeGuardSeparateFromReportedError() {
        val observation = input(state(), 1000L).observation!!
        val facing = RouteFacingGuidance.evaluate(0.0, observation, 1000L)
        assertEquals(RouteFacingDirection.FRONT, facing.direction)
        assertEquals(12, facing.clockHour)
        assertNull(facing.clockRange)
        assertFalse(facing.clockPrecisionAvailable)
        assertEquals(20.0, facing.nominalClockReferenceAllowanceDegrees!!, 0.0)
        assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK, facing.clockReferenceKind)
        val wider = RouteFacingGuidance.evaluate(0.0, observation, 1000L, 5.0)
        assertEquals(25.0, wider.nominalClockReferenceAllowanceDegrees!!, 0.0)
        assertEquals(12, wider.clockHour)
        assertNull(wider.clockRange)
    }

    @Test fun aChangedNominalClockNeedsTwoFreshSamplesEvenWithinTheSameCoarseDirection() {
        val nav = nav(); val observation = input(state(), 1000L).observation!!
        nav.update(fix(), 1000L, false, facingObservation = observation)
        val changed = observation.copy(degreesTrueNorth = 20.0, observedAtElapsedRealtimeMs = 1100L)
        assertEquals(RouteFacingDirection.FRONT, RouteFacingGuidance.evaluate(0.0, changed, 1100L).direction)
        assertNotEquals(RouteFacingGuidance.evaluate(0.0, observation, 1000L).clockHour,
            RouteFacingGuidance.evaluate(0.0, changed, 1100L).clockHour)
        assertEquals("guidance_facing_recheck_wait", nav.retryGuidance(1100L, changed)!!.reason)
        assertEquals("guidance_facing_recheck_wait", nav.retryGuidance(1150L, changed)!!.reason)
        assertNominalAction(nav.retryGuidance(1200L, changed.copy(observedAtElapsedRealtimeMs = 1200L))!!)
    }

    @Test fun motionUncheckedFallbackAlsoNeedsTwoDistinctFreshSamples() {
        val state = state(); val nav = nav()
        val first = input(state, 1000L, withMotionCheck = false)
        assertEquals(RouteFacingSensorQuality.CALIBRATED_WITHOUT_MOTION_CHECK, first.observation!!.sensorQuality)
        assertEquals("guidance_facing_recheck_wait", nav.update(fix(), 1000L, false, facingObservation = first.observation).reason)
        assertEquals("guidance_facing_recheck_wait", nav.retryGuidance(1100L, first.observation)!!.reason)
        val next = input(state, 1200L, withMotionCheck = false)
        assertNominalAction(nav.retryGuidance(1200L, next.observation)!!)
    }

    @Test fun genuineFieldAnomalyStillRejectsWhileIndependentFallbackSurvivesFreshLowRvQuality() {
        val state = state(); val bad = input(state, 1000L, scale = 2.0)
        assertEquals(RouteCompassHeadingReason.MAGNETIC_FIELD_ANOMALY, bad.reason)
        assertNull(RouteFacingGuidance.evaluate(0.0, bad.observation, 1000L).clockHour)
        assertNull(RouteFacingGuidance.evaluate(0.0, bad.observation, 1000L).clockRange)
        state.onAccuracyChanged(1, RouteCompassStream.ROTATION_VECTOR, EarthOrientationAccuracy.LOW, 1100L)
        val low = input(state, 1200L)
        assertEquals(RouteCompassHeadingReason.VALID, low.reason)
        val independentlyChecked = requireNotNull(low.observation)
        assertEquals(RouteFacingSource.GRAVITY_MAGNETIC, independentlyChecked.source)
        assertEquals(1200L, independentlyChecked.observedAtElapsedRealtimeMs)
        assertNull(independentlyChecked.headingAccuracyDegrees)
        assertNull(independentlyChecked.reportedHeadingAccuracyDegrees)
        val nav = nav()
        assertEquals("guidance_facing_recheck_wait", nav.update(fix(), 1200L, false,
            facingObservation = independentlyChecked).reason)
        assertEquals("guidance_facing_recheck_wait", nav.retryGuidance(1250L, independentlyChecked)!!.reason)
        assertNominalAction(nav.retryGuidance(1300L, input(state, 1300L).observation)!!)
        val rvWithAnomaly = input(state(), 1000L, 3f, 2.0)
        assertTrue(rvWithAnomaly.observation!!.magneticFieldSuspect)
        assertNull(RouteFacingGuidance.evaluate(0.0, rvWithAnomaly.observation, 1000L).clockHour)
        assertNull(RouteFacingGuidance.evaluate(0.0, rvWithAnomaly.observation, 1000L).clockRange)
    }

    @Test fun originalAgeGeometryAndQualityStillRejectUnsafeApproximateReferences() {
        val good = input(state(), 1000L).observation!!
        for ((at, observation, bearing, spread) in listOf(
            Unsafe(1501L, good, 0.0, 0.0), Unsafe(999L, good, 0.0, 0.0),
            Unsafe(1000L, good, null, 0.0), Unsafe(1000L, good, 0.0, 25.0),
            Unsafe(1000L, good, 0.0, Double.NaN),
            Unsafe(1000L, good.copy(headingAccuracyDegrees = 3.0), 0.0, 0.0),
            Unsafe(1000L, good.copy(reportedHeadingAccuracyDegrees = 3.0), 0.0, 0.0),
            Unsafe(1000L, good.copy(sensorQuality = RouteFacingSensorQuality.REPORTED_ANGULAR_ERROR), 0.0, 0.0))) {
            val facing = RouteFacingGuidance.evaluate(bearing, observation, at, spread)
            assertNull(facing.clockHour); assertNull(facing.clockRange)
        }
        val rotated = good.copy(angularRateDegreesPerSecond = 100.0)
        assertNull(RouteFacingGuidance.evaluate(0.0, rotated, 1500L).clockHour)
        assertNull(RouteFacingGuidance.evaluate(0.0, rotated, 1500L).clockRange)
        assertEquals(1000L, good.observedAtElapsedRealtimeMs)
    }

    @Test fun actualFreshOppositeFacingRetiresThePendingApproximateCue() {
        val state = state(); val nav = nav(); val first = input(state, 1000L)
        nav.update(fix(), 1000L, false, facingObservation = first.observation)
        val second = input(state, 1200L)
        val action = nav.retryGuidance(1200L, second.observation)!!
        assertTrue(nav.reserveInstruction(action))
        val reversed = second.observation!!.copy(degreesTrueNorth = 180.0, observedAtElapsedRealtimeMs = 1300L)
        assertTrue(nav.hasHardInvalidatedDirectionClaim(1300L, reversed))
        assertFalse(nav.isInstructionCurrent(action, 1300L, reversed))
        val update = nav.retryGuidance(1300L, reversed)!!
        assertTrue(update.cancelStaleNavigationSpeech)
        assertFalse(nav.isInstructionCompletionCurrent(action, 1301L))
    }

    @Test fun badRepeatsKeepTheApproximateCueOnlyUntilTheOriginalFixedDeadline() {
        val state = state(); val nav = nav(); val first = input(state, 1000L)
        nav.update(fix(), 1000L, false, facingObservation = first.observation)
        val second = input(state, 1200L)
        val action = nav.retryGuidance(1200L, second.observation)!!
        assertTrue(nav.reserveInstruction(action))
        for (at in listOf(1300L, 1800L, 2200L, 2799L)) {
            val update = nav.retryGuidance(at, null, RouteCompassHeadingReason.QUALITY_LOW)!!
            assertFalse(update.cancelStaleNavigationSpeech)
            assertEquals(2800L, nav.currentDirectionGraceSnapshot()!!.deadlineElapsedRealtimeMs)
            assertTrue(nav.isInstructionCurrent(action, at, null))
        }
        val expired = nav.retryGuidance(2800L, null, RouteCompassHeadingReason.QUALITY_LOW)!!
        assertTrue(expired.cancelStaleNavigationSpeech)
        assertEquals(RouteDirectionGraceMode.PROBLEM, nav.currentDirectionGraceSnapshot()!!.mode)
        assertFalse(nav.isInstructionCompletionCurrent(action, 2800L))
    }

    @Test fun replacingTheRouteCannotReuseANominalClockPacket() {
        val state = state(); val nav = nav(); val first = input(state, 1000L)
        nav.update(fix(), 1000L, false, facingObservation = first.observation)
        val second = input(state, 1200L)
        val action = nav.retryGuidance(1200L, second.observation)!!
        assertTrue(nav.reserveInstruction(action))
        nav.setRoute(route())
        assertFalse(nav.isInstructionCurrent(action, 1300L, second.observation))
        assertFalse(nav.isInstructionCompletionCurrent(action, 1300L))
    }

    @Test fun realStateWobbleAndNorthWrapOfferOneRepresentativeClockWithoutNumericError() {
        val state = state(); val nav = nav()
        var latest: RouteCompassHeadingResult? = null
        for ((i, h) in listOf(338.0, 22.0, 338.0, 22.0, 338.0, 22.0, 338.0, 22.0).withIndex()) {
            val at = 1000L + i * 100L
            latest = input(state, at, heading = h)
            val update = if (i == 0) nav.update(fix(), at, false, facingObservation = latest.observation)
                else nav.currentGuidance(at, latest.observation)!!
            if (i >= 7) {
                assertTrue(update.instruction.orEmpty(), update.instruction.orEmpty().contains("약 12시 방향"))
                assertFalse(update.instruction.orEmpty().contains("시 사이"))
            }
        }
        val raw = latest!!.observation!!
        assertEquals(22.0, raw.degreesTrueNorth, 0.001)
        assertEquals(1700L, raw.observedAtElapsedRealtimeMs)
        assertNull(raw.headingAccuracyDegrees); assertNull(raw.reportedHeadingAccuracyDegrees)
        assertTrue(kotlin.math.abs((raw.headingTrend!!.representativeDegreesTrueNorth + 180.0) % 360.0 - 180.0) < 4.0)
        assertEquals(7, raw.headingTrend!!.sampleCount)
    }

    @Test fun stationaryActualTurnUsesTheNewDirectionWithoutChangingTheGpsFix() {
        val state = state(); val nav = nav()
        nav.update(fix(), 1000L, false, facingObservation = input(state, 1000L).observation)
        val straight = nav.retryGuidance(1100L, input(state, 1100L).observation)!!
        nav.reserveInstruction(straight); nav.acknowledgeInstruction(straight, 1100L)
        val firstTurn = input(state, 1200L, heading = 270.0)
        assertEquals(270.0, firstTurn.observation!!.degreesTrueNorth, 0.001)
        assertEquals(1200L, firstTurn.observation!!.observedAtElapsedRealtimeMs)
        assertTrue(firstTurn.observation!!.headingTrend!!.largeTurnPending)
        assertEquals(0.0, firstTurn.observation!!.headingTrend!!.representativeDegreesTrueNorth, 0.001)
        nav.currentGuidance(1200L, firstTurn.observation)
        val secondTurn = input(state, 1300L, heading = 270.0)
        assertTrue(secondTurn.observation!!.headingTrend!!.largeTurnPending)
        nav.currentGuidance(1300L, secondTurn.observation)
        val supportedTurn = input(state, 1500L, heading = 270.0)
        assertFalse(supportedTurn.observation!!.headingTrend!!.largeTurnPending)
        assertEquals(270.0, supportedTurn.observation!!.headingTrend!!.representativeDegreesTrueNorth, 0.001)
        nav.currentGuidance(1500L, supportedTurn.observation)
        val aligned = nav.currentGuidance(1600L, input(state, 1600L, heading = 270.0).observation)!!
        assertTrue(aligned.instruction.orEmpty(), aligned.instruction.orEmpty().contains("약 3시 방향"))
        assertFalse(aligned.instruction.orEmpty().contains("시 사이"))
        assertNotNull(nav.currentAcceptedRouteMatchFor(1000L))
    }

    @Test fun actualEastThenSouthCornerSeparatesStraightClockFromTheRoutesRightTurn() {
        fun at(east: Double, north: Double) = point(north, east)
        val corner = WalkingRoute("CORNER", WalkingRouteSummary(100, 100),
            listOf(at(0.0, 0.0), at(20.0, 0.0), at(20.0, -80.0)),
            listOf(WalkingRouteGuidePoint(0, at(20.0, 0.0), null, 20, 80, turnType = 13)))
        val nav = RouteNavigator(allowDegradedRouteGuidance = false).apply { setRoute(corner) }
        val state = state()
        fun fix(east: Double, north: Double, stamp: Long) = at(east, north).let {
            TrustedLocation(it.latitude, it.longitude, 3f, stamp)
        }
        nav.update(fix(16.0, 0.0, 1000L), 1000L, false, facingObservation = input(state, 1000L, heading = 82.0).observation)
        nav.retryGuidance(1100L, input(state, 1100L, heading = 98.0).observation)
        val before = nav.currentGuidance(1500L, input(state, 1500L, heading = 90.0).observation)!!
        assertTrue(before.instruction.orEmpty(), before.instruction.orEmpty().contains("약 12시 방향"))
        nav.reserveInstruction(before); nav.acknowledgeInstruction(before, 1500L)
        val vertex = nav.update(fix(20.0, 0.0, 2000L), 2000L, false,
            facingObservation = input(state, 2000L, heading = 178.0).observation)
        assertEquals(RouteAlignmentReason.TURN_PASSAGE_PENDING, vertex.routeAlignmentDiagnostic!!.reason)
        assertEquals("안내 지점 부근입니다. 경로 기준으로 3시 방향으로 꺾으세요.", vertex.instruction)
        nav.reserveInstruction(vertex); nav.acknowledgeInstruction(vertex, 2000L)
        nav.update(fix(20.0, -6.0, 3000L), 3000L, false,
            facingObservation = input(state, 3000L, heading = 182.0).observation)
        val passed = nav.currentGuidance(3500L, input(state, 3500L, heading = 178.0).observation)!!
        assertEquals(1, passed.routeAlignmentDiagnostic!!.segmentIndex)
        assertTrue(passed.instruction.orEmpty(), passed.instruction.orEmpty().contains("약 12시 방향"))
        for (update in listOf(before, vertex, passed)) assertFalse(update.instruction.orEmpty().contains("시 사이"))
    }

    private data class Unsafe(val at: Long, val observation: RouteFacingObservation, val bearing: Double?, val spread: Double)
    private fun assertNominalAction(action: RouteNavigatorUpdate) {
        assertEquals(RouteFacingExpression.CLOCK, action.facingDiagnostic!!.expression)
        assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK, action.facingDiagnostic!!.clockReferenceKind)
        assertTrue(action.instruction.orEmpty(), action.instruction.orEmpty().contains("시 방향"))
        assertFalse(action.instruction.orEmpty(), action.instruction.orEmpty().contains("시 사이"))
        assertTrue(action.instruction.orEmpty(), action.instruction.orEmpty().contains("미터"))
        assertTrue(action.speechCueToken!!.requiresFacingEvidence)
    }
}

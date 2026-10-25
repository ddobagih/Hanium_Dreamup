package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import org.junit.Assert.*
import org.junit.Test

/** An actual recovery DONE cannot claim that the still-unspoken mapped turn was delivered. */
class RouteNavigatorMagneticRecoveryCompletionProbeTest {
    @Test fun magneticRecoveryDoneDoesNotCoverTheUnspokenTurnAfterFieldRecovery() {
        val nav = RouteNavigator().apply {
            setRoute(WalkingRoute("CORNER", WalkingRouteSummary(100, 100),
                listOf(point(0.0), point(20.0), point(20.0, -80.0)),
                listOf(WalkingRouteGuidePoint(0, point(20.0), null, 20, 80, turnType = 13))))
        }
        val first = update(nav, 1_000L, 10.0, checked(1_000L, 50.0, eastFacing = true))
        assertEquals(RouteFacingExpression.CLOCK, first.facingDiagnostic?.expression)
        assertNotNull(first.instruction)
        // Deliberately do not reserve/START/DONE this first cue. No turn has been spoken.
        val bad = checked(2_000L, 100.0)
        assertTrue(requireNotNull(bad.observation).magneticFieldSuspect)
        val recovery = update(nav, 2_000L, 20.0, bad)
        assertEquals(RouteAlignmentReason.TURN_PASSAGE_PENDING, recovery.routeAlignmentDiagnostic?.reason)
        assertEquals("자기장 영향으로 방향이 불안정할 수 있습니다. 방향을 다시 측정하고 있습니다.",
            requireNotNull(recovery.instruction).trim())
        assertFalse(recovery.speechCueToken?.requiresFacingEvidence ?: true)
        assertTrue(nav.reserveInstruction(recovery))
        assertTrue(nav.isInstructionCurrent(recovery, 2_000L, bad.observation))
        nav.acknowledgeInstruction(recovery, 2_000L)
        assertFalse(nav.isInstructionCurrent(recovery, 2_000L, bad.observation))

        for (at in listOf(2_100L, 2_200L)) {
            val normal = checked(at, 50.0)
            assertFalse(requireNotNull(normal.observation).magneticFieldSuspect)
            val observed = update(nav, at, 20.0, normal)
            assertEquals(RouteAlignmentReason.TURN_PASSAGE_PENDING, observed.routeAlignmentDiagnostic?.reason)
            assertFalse(observed.instruction.orEmpty().contains("자석"))
            // Only observation; no delivery callback consumes any offered mapped turn.
        }
        val recoveredFacing = checked(8_000L, 50.0)
        val turn = update(nav, 8_000L, 20.0, recoveredFacing)
        assertEquals("The recovery completion must not cover the unspoken turn", "route_guidance", turn.reason)
        assertEquals("안내 지점 부근입니다. 경로 기준으로 3시 방향으로 꺾으세요.", turn.instruction)
        assertEquals(RouteFacingExpression.NOT_SPOKEN, turn.facingDiagnostic?.expression)
        assertTrue(nav.reserveInstruction(turn))
        assertTrue(nav.isInstructionCurrent(turn, 8_000L, recoveredFacing.observation))
        nav.acknowledgeInstruction(turn, 8_000L)
        assertFalse(nav.isInstructionCurrent(turn, 8_000L, recoveredFacing.observation))

        // A genuinely delivered mapped turn retains the original 6s / 10s cadence.
        assertNull(update(nav, 8_100L, 20.0, checked(8_100L, 50.0)).instruction)
        val sixSecondsAfterActualTurn = update(nav, 14_000L, 20.0, checked(14_000L, 50.0))
        assertEquals("guidance_already_completed", sixSecondsAfterActualTurn.reason)
        assertNull(sixSecondsAfterActualTurn.instruction)
        val periodic = update(nav, 18_000L, 20.0, checked(18_000L, 50.0))
        assertEquals("route_guidance", periodic.reason)
        assertEquals(turn.instruction, periodic.instruction)
    }

    @Test fun magneticRecoveryDoneDoesNotCoverAnAllowedRouteTurnWithNoHeading() {
        val nav = RouteNavigator().apply {
            setRoute(WalkingRoute("CORNER", WalkingRouteSummary(100, 100),
                listOf(point(0.0), point(20.0), point(20.0, -80.0)),
                listOf(WalkingRouteGuidePoint(0, point(20.0), null, 20, 80, turnType = 13))))
        }
        // Establish route progress without delivering the earlier approach cue.
        assertNotNull(update(nav, 1_000L, 10.0, checked(1_000L, 50.0, eastFacing = true)).instruction)
        val bad = checked(2_000L, 100.0)
        val recovery = update(nav, 2_000L, 20.0, bad)
        assertEquals(RouteAlignmentReason.TURN_PASSAGE_PENDING, recovery.routeAlignmentDiagnostic?.reason)
        assertTrue(requireNotNull(recovery.instruction).contains("자기장 영향"))
        assertTrue(nav.reserveInstruction(recovery))
        assertTrue(nav.isInstructionCurrent(recovery, 2_000L, bad.observation))
        nav.acknowledgeInstruction(recovery, 2_000L)
        assertFalse(nav.isInstructionCurrent(recovery, 2_000L, bad.observation))
        fun noHeading(at: Long): RouteNavigatorUpdate {
            val p = point(20.0)
            return nav.update(TrustedLocation(p.latitude, p.longitude, 3f, at), at, false,
                facingObservation = null, facingUnavailableReason = RouteCompassHeadingReason.ORIENTATION_MISSING)
        }
        for (at in listOf(2_100L, 2_200L)) {
            val observed = noHeading(at)
            assertEquals(RouteAlignmentReason.TURN_PASSAGE_PENDING, observed.routeAlignmentDiagnostic?.reason)
            assertFalse(observed.instruction.orEmpty().contains("자석"))
        }
        val turn = noHeading(8_000L)
        assertEquals("Recovery and a non-relative mapped turn have different completion purposes", "route_guidance", turn.reason)
        assertEquals("안내 지점 부근입니다. 경로 기준으로 3시 방향으로 꺾으세요.", turn.instruction)
        assertEquals(RouteFacingExpression.NOT_SPOKEN, turn.facingDiagnostic?.expression)
        assertFalse(requireNotNull(turn.speechCueToken).requiresFacingEvidence)
        assertTrue(nav.reserveInstruction(turn))
        assertTrue(nav.isInstructionCurrent(turn, 8_000L, null))
        nav.acknowledgeInstruction(turn, 8_000L)
        assertFalse(nav.isInstructionCurrent(turn, 8_000L, null))
    }

    private fun checked(at: Long, field: Double, eastFacing: Boolean = false): RouteCompassHeadingResult {
        val rotation = if (eastFacing) RotationMatrix3(0f, 0f, -1f, -1f, 0f, 0f, 0f, 1f, 0f)
            else RotationMatrix3(-1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f)
        return RouteCompassHeading.crossCheckMagneticField(
            RouteCompassHeading.evaluate(DeviceEarthOrientation(rotation, at, 0.1f, EarthOrientationAccuracy.HIGH), 0.0, at),
            RouteCompassVectorSample(0.0, 0.0, -field, at, EarthOrientationAccuracy.HIGH),
            ChestMountedGeomagneticReference(0.0, 0.0, 50.0), at)
    }
    private fun update(nav: RouteNavigator, at: Long, east: Double, facing: RouteCompassHeadingResult): RouteNavigatorUpdate {
        val p = point(east)
        return nav.update(TrustedLocation(p.latitude, p.longitude, 3f, at), at, false,
            facingObservation = facing.observation, facingUnavailableReason = facing.reason)
    }
    private fun point(east: Double, north: Double = 0.0) = RoutePoint(north / 111_194.92664455874, east / 111_194.92664455874)
}

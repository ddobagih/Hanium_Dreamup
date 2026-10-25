package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteCorridorMatcher
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S08-20, 08.txt:215-223: a request origin does not establish a walkway onto its snapped first leg. */
class RouteNavigatorDepartureLateralSafetyRegressionTest {
    @Test
    fun anAcceptedDepartureBesideItsRequestOriginOffersTheExistingStopAction() {
        assertBesideDeparture(shortFirstLeg = false)
    }

    @Test
    fun aContinuingMicroDepartureAlsoRetainsTheExistingLateralFact() {
        assertBesideDeparture(shortFirstLeg = true)
    }

    @Test
    fun bothDepartureSelectionsPreserveTheSameLateralFactAsOrdinaryRouteLegs() {
        for (shortFirstLeg in listOf(false, true)) {
            val route = route(shortFirstLeg)
            val origin = fix(12.0)
            val position = filtered(origin)
            val match = RouteCorridorMatcher().match("departure", route.polyline, position)
            assertEquals(RouteMatchQuality.MEDIUM, match.quality)
            val selection = RouteAlignmentSelector().apply { reset(origin) }
                .select(route, position, match, 1_000L, 35.0)
            assertEquals(RouteAlignmentReason.DEPARTURE_SEGMENT, selection.diagnostic.reason)
            assertEquals(90.0, requireNotNull(selection.bearingDegrees), 0.001)
            assertTrue("A departure anchor must retain the measured lateral offset", selection.lateralOffsetBeyondUncertainty)
        }
    }

    @Test
    fun aFreshBesideFixReplacesReservedOrCompletedDepartureCommandsWithoutLosingTheAnchor() {
        for (completeFirst in listOf(false, true)) {
            val origin = fix(0.0)
            val navigator = navigator(origin)
            val first = sample(navigator, origin)
            assertEquals(RouteAlignmentReason.DEPARTURE_SEGMENT, first.routeAlignmentDiagnostic?.reason)
            assertTrue(requireNotNull(first.instruction).contains("3시 방향으로 꺾으세요."))
            assertTrue(navigator.reserveInstruction(first))
            assertTrue(navigator.isInstructionCurrent(first, 1_000L, facing(1_000L)))
            if (completeFirst) navigator.acknowledgeInstruction(first, 1_000L)

            // 12 m is within the two fixes' combined radius, so the departure anchor remains.
            // The original facing lease is still current: cancellation is due to lateral safety.
            val beside = sample(navigator, fix(12.0, atMs = 1_100L))
            assertEquals(RouteAlignmentReason.DEPARTURE_SEGMENT, beside.routeAlignmentDiagnostic?.reason)
            assertSafe(beside)
            assertEquals(!completeFirst, beside.cancelStaleNavigationSpeech)
            assertNotEquals(first.speechCueToken, beside.speechCueToken)
            assertFalse(navigator.reserveInstruction(first))
            assertFalse(navigator.isInstructionCurrent(first, 1_100L, facing(1_100L)))
            assertTrue(navigator.reserveInstruction(beside))
            navigator.acknowledgeInstruction(first, 1_101L)
            assertTrue(navigator.isInstructionCurrent(beside, 1_101L, facing(1_101L)))
            assertEquals("guidance_in_flight", navigator.retryGuidance(1_101L, facing(1_101L))?.reason)
        }
    }

    @Test
    fun onRouteAndWithinRadiusDeparturesKeepTheirNormalCommandsIncludingMicroLegs() {
        for (shortFirstLeg in listOf(false, true)) {
            for (northM in listOf(0.0, 9.0)) {
                val origin = fix(northM)
                val navigator = navigator(origin, shortFirstLeg)
                val update = sample(navigator, origin)
                assertEquals(RouteAlignmentReason.DEPARTURE_SEGMENT, update.routeAlignmentDiagnostic?.reason)
                val instruction = requireNotNull(update.instruction)
                assertTrue(instruction, instruction.contains("3시 방향으로 꺾으세요."))
                assertFalse(instruction, instruction.contains(STOP_AND_CHECK))
                assertFalse(instruction, instruction.contains("경로 옆으로 떨어져"))
                assertTrue(navigator.reserveInstruction(update))
                assertTrue(navigator.isInstructionCurrent(update, 1_001L, facing(1_001L)))
            }
        }
    }

    @Test
    fun aClearProviderStartOffsetOutsideTheCorridorRequestsLocationRecheckImmediately() {
        // Normal guidance does not turn a geometric connector into an accepted path to the route.
        // Keep the original 30 m offset / 3 m accuracy and the production 1.5-second default.
        val origin = fix(30.0, accuracyM = 3f)
        val navigator = navigator(origin)
        val update = sample(navigator, origin)
        assertEquals("route_match_untrusted", update.reason)
        assertEquals(RouteAlignmentReason.DEPARTURE_CONNECTOR, update.routeAlignmentDiagnostic?.reason)
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, update.pendingUserDecision)
        assertTrue(update.cancelStaleNavigationSpeech)
        assertNull(navigator.currentAcceptedRouteMatchFor(1_000L))
        val instruction = requireNotNull(update.instruction)
        assertTrue(instruction, instruction.contains("위치 다시 확인이라고 말씀해 주세요."))
        assertNoMovementCommand(instruction)
        assertFalse(Regex("""약 (?:1[0-2]|[1-9])시(?: 방향|에서 (?:1[0-2]|[1-9])시 사이 방향)""").containsMatchIn(instruction))
        assertFalse(update.arrivalCandidate)
        assertFalse(update.offRoute)
        assertFalse(update.shouldReroute)
        assertTrue(navigator.hasRoute())
        assertNull(update.speechCueToken)
        assertFalse(navigator.reserveInstruction(update))
    }

    private fun assertBesideDeparture(shortFirstLeg: Boolean) {
        val origin = fix(12.0)
        assertEquals(origin, LocationTrustPolicy.trustedOrNull(origin.latitude, origin.longitude,
            origin.accuracyM, origin.elapsedRealtimeMs))
        val navigator = navigator(origin, shortFirstLeg)
        val update = sample(navigator, origin)
        assertEquals(RouteMatchQuality.MEDIUM, navigator.currentRouteMatch()?.quality)
        assertEquals(RouteAlignmentReason.DEPARTURE_SEGMENT, update.routeAlignmentDiagnostic?.reason)
        assertSafe(update)
        assertTrue(navigator.reserveInstruction(update))
        assertTrue(navigator.isInstructionCurrent(update, 1_001L, facing(1_001L)))
    }

    private fun assertSafe(update: RouteNavigatorUpdate) {
        val instruction = requireNotNull(update.instruction)
        assertNoMovementCommand(instruction)
        assertTrue(instruction, instruction.contains("현재 위치는 경로 옆으로 떨어져 있으며"))
        assertTrue(instruction, instruction.contains("걸어갈 수 있는 통로는 확인되지 않았습니다."))
        assertTrue(instruction, instruction.contains("다음 안내 지점에는 경로 기준 3시 방향 회전이 있습니다."))
        assertTrue(instruction, instruction.endsWith(STOP_AND_CHECK))
        assertFalse(update.directionOnly)
        assertFalse(update.offRoute)
        assertFalse(update.shouldReroute)
        assertFalse(update.arrived)
        assertNull(update.pendingUserDecision)
    }

    private fun assertNoMovementCommand(instruction: String) {
        for (command in listOf("꺾으세요", "이동하세요", "직진하세요", "돌아가세요", "돌리세요", "맞추세요", "걸으세요")) {
            assertFalse("$command in $instruction", instruction.contains(command))
        }
    }

    private fun navigator(origin: TrustedLocation, shortFirstLeg: Boolean = false) = RouteNavigator().apply {
        setRoute(route(shortFirstLeg), origin = origin)
    }

    private fun route(shortFirstLeg: Boolean) = WalkingRoute("TEST", WalkingRouteSummary(400, 400),
        if (shortFirstLeg) listOf(point(0.0), point(0.5), point(400.0)) else listOf(point(0.0), point(400.0)),
        listOf(WalkingRouteGuidePoint(0, point(200.0), null, 200, 200, turnType = 13)))

    private fun sample(navigator: RouteNavigator, fix: TrustedLocation) = navigator.update(
        fix, fix.elapsedRealtimeMs, false, filteredPosition = filtered(fix),
        facingObservation = facing(fix.elapsedRealtimeMs),
    )

    private fun filtered(fix: TrustedLocation) = FilteredRoutePosition(
        RoutePoint(fix.latitude, fix.longitude), fix.accuracyM.toDouble(), fix.elapsedRealtimeMs,
    )

    private fun point(eastM: Double, northM: Double = 0.0) =
        RoutePoint(northM / 111_194.92664455874, eastM / 111_194.92664455874)

    private fun fix(northM: Double, accuracyM: Float = 10f, atMs: Long = 1_000L) =
        point(0.0, northM).let { TrustedLocation(it.latitude, it.longitude, accuracyM, atMs) }

    private fun facing(atMs: Long) = RouteFacingObservation(90.0, 5.0, atMs)

    companion object {
        private const val STOP_AND_CHECK = "잠시 멈춰 주변 보행로를 확인한 뒤 다시 안내를 요청해 주세요."
    }
}

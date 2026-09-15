package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchQuality
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos

class RouteAuditRegressionTest {
    @Test fun acceptedParallelSevenMeterLegKeepsThreeOclockThroughForwardAndRetreatSamples() {
        for (debug in listOf(false, true)) {
            val route = route(point(0.0), point(100.0), point(100.0, 7.0), point(-100.0, 7.0))
            val navigator = navigator(route, fix(0.0), debug)
            for ((index, east) in listOf(0.0, 20.0, 30.0, 20.0).withIndex()) {
                val at = 1_000L + index * 1_000
                val update = navigator.update(fix(east, at = at), at, false, facingObservation = facing(0.0, at))
                assertEquals(RouteMatchQuality.HIGH, navigator.currentRouteMatch()?.quality)
                assertEquals(0, navigator.currentRouteMatch()?.segmentIndex)
                assertEquals(3, clock(update, at))
                assertTrue(update.instruction?.contains("약 3시 방향") == true)
                assertNotNull(navigator.currentAcceptedRouteMatchFor(at))
            }
        }
    }

    @Test fun anActuallyOverlappingReturnStillHasNoAcceptedBranchOrClock() {
        for (debug in listOf(false, true)) {
            val navigator = navigator(route(point(0.0), point(100.0), point(-100.0)), fix(0.0), debug)
            val update = navigator.update(fix(0.0), 1_000, false, facingObservation = facing(0.0))
            assertNull(navigator.currentAcceptedRouteMatchFor(1_000))
            assertNull(update.routeAlignmentDiagnostic?.bearingDegreesTrueNorth)
            assertFalse(update.instruction?.contains("시 방향") == true)
        }
    }

    @Test fun indistinguishableParallelLegsStillRejectDirectionButADistinctReturnUsesWest() {
        val route = route(point(0.0), point(100.0), point(100.0, 7.0), point(-100.0, 7.0))
        val uncertainFix = fix(0.0, accuracy = 4f)
        val uncertain = navigator(route, uncertainFix)
        val ambiguous = uncertain.update(uncertainFix, 1_000, false, facingObservation = facing(0.0))
        assertNull(uncertain.currentAcceptedRouteMatchFor(1_000))
        assertNull(ambiguous.routeAlignmentDiagnostic?.bearingDegreesTrueNorth)

        val returnFix = fix(0.0, 7.0)
        val returning = navigator(route, returnFix)
        val selected = returning.update(returnFix, 1_000, false, facingObservation = facing(0.0))
        assertEquals(2, returning.currentAcceptedRouteMatchFor(1_000)?.segmentIndex)
        assertEquals(9, clock(selected, 1_000))
        assertTrue(selected.instruction?.contains("약 9시 방향") == true)
    }

    @Test fun sparseAndDenseStraightRoutesProvideTheSameStrictDepartureAndProgress() {
        val sparse = navigator(route(point(0.0), point(100.0)), fix(0.0, accuracy = 20f))
        val dense = navigator(route(*(0..100).map { point(it.toDouble()) }.toTypedArray()), fix(0.0, accuracy = 20f))
        listOf(0.0, 20.0, 30.0, 20.0).forEachIndexed { index, east ->
            val at = 1_000L + index * 1_000
            val updates = listOf(sparse, dense).map { it.update(fix(east, accuracy = 20f, at = at), at, false,
                facingObservation = facing(0.0, at)) }
            updates.forEach {
                assertEquals(3, clock(it, at))
                assertTrue(it.instruction?.contains("약 3시 방향") == true)
            }
            assertEquals(sparse.currentRouteMatch()?.quality, dense.currentRouteMatch()?.quality)
            assertEquals(sparse.currentRouteMatch()!!.geometricProgressM!!,
                dense.currentRouteMatch()!!.geometricProgressM!!, 0.05)
        }
    }

    @Test fun startOffsetExplainsDirectionWithoutClaimingAPathProgressOrArrivalInEitherMode() {
        for (debug in listOf(false, true)) {
            val raw = fix(0.0, 15.0, 3f)
            val navigator = navigator(straightWithGuide(), raw, debug)
            val update = navigator.update(raw, 1_000, false, facingObservation = facing(0.0))
            assertReference(update)
            assertTrue(update.instruction!!.contains("약 6시 방향"))
            assertNull(navigator.currentAcceptedRouteMatchFor(1_000))
            assertNull(navigator.currentBearingDeg())
            assertNull(navigator.currentInstruction(raw))
            assertNull(navigator.pendingUserDecision())
            assertTrue(navigator.reserveInstruction(update))
        }
    }

    @Test fun referenceRetriesUseFreshFacingAndDoNotConsumeTheFirstFullGuidance() {
        val raw = fix(0.0, 15.0, 3f)
        val navigator = navigator(straightWithGuide(), raw)
        val initial = navigator.update(raw, 1_000, false, facingObservation = facing(0.0))
        assertTrue(navigator.reserveInstruction(initial))
        navigator.releaseInstruction(initial, 1_100)
        assertNull(navigator.retryGuidance(2_000, facing(90.0, 2_000))?.instruction)
        val retried = requireNotNull(navigator.retryGuidance(3_100, facing(90.0, 3_100)))
        assertReference(retried)
        assertTrue(retried.instruction!!.contains("약 3시 방향"))
        assertNotEquals(initial.speechCueToken, retried.speechCueToken)
        assertTrue(navigator.reserveInstruction(retried))
        navigator.acknowledgeInstruction(initial, 3_150)
        val pending = navigator.update(raw.copy(elapsedRealtimeMs = 3_200), 3_200, false,
            facingObservation = facing(180.0, 3_200))
        assertEquals("guidance_in_flight", pending.reason)
        assertFalse(pending.cancelStaleNavigationSpeech)
        navigator.acknowledgeInstruction(retried, 3_300)
        // Moving across the connector briefly supplies a cross-route movement heading.
        // A second stationary observation is the actual accepted recovery evidence.
        navigator.update(fix(0.0, at = 3_400), 3_400, false, facingObservation = facing(0.0, 3_400))
        val ready = navigator.update(fix(0.0, at = 3_500), 3_500, false,
            facingObservation = facing(0.0, 3_500))
        assertNotNull(navigator.currentAcceptedRouteMatchFor(3_500))
        assertFalse(ready.directionOnly)
        assertTrue(ready.instruction?.contains("전체 경로는") == true)
        assertTrue(navigator.reserveInstruction(ready))
    }

    @Test fun aPendingReferenceFinishesBeforeTheNowAcceptedFullGuidance() {
        val raw = fix(0.0, 15.0, 3f)
        val navigator = navigator(straightWithGuide(), raw)
        val reference = navigator.update(raw, 1_000, false, facingObservation = facing(0.0))
        assertTrue(navigator.reserveInstruction(reference))
        val recovered = navigator.update(fix(0.0, at = 2_000), 2_000, false,
            facingObservation = facing(0.0, 2_000))
        assertEquals("guidance_in_flight", recovered.reason)
        assertFalse(recovered.cancelStaleNavigationSpeech)
        val stable = navigator.update(fix(0.0, at = 2_050), 2_050, false,
            facingObservation = facing(0.0, 2_050))
        assertNotNull(navigator.currentAcceptedRouteMatchFor(2_050))
        assertEquals("guidance_in_flight", stable.reason)
        navigator.acknowledgeInstruction(reference, 2_100)
        val full = requireNotNull(navigator.retryGuidance(2_101, facing(0.0, 2_101)))
        assertFalse(full.directionOnly)
        assertTrue(full.instruction?.contains("전체 경로는") == true)
        assertTrue(navigator.reserveInstruction(full))
    }

    @Test fun staleDirectionNeverReusesAnOldClockAndInterruptionRevokesReferenceTokens() {
        val raw = fix(0.0, 15.0, 3f)
        val navigator = navigator(straightWithGuide(), raw)
        val old = navigator.update(raw, 1_000, false, facingObservation = facing(0.0))
        val staleCompass = requireNotNull(navigator.retryGuidance(1_600, facing(0.0)))
        assertFalse(staleCompass.instruction?.contains("6시") == true)
        assertFalse(navigator.reserveInstruction(old))
        navigator.onPositioningEvidenceInterrupted()
        assertFalse(navigator.reserveInstruction(staleCompass))
        assertNull(navigator.retryGuidance(1_700, facing(0.0, 1_700)))
        navigator.setRoute(straightWithGuide())
        assertFalse(navigator.reserveInstruction(staleCompass))
    }

    @Test fun losingPositionAuthorityCancelsOldDistanceSpeechBeforeOfferingOnlyDirection() {
        val navigator = navigator(straightWithGuide(), fix(0.0))
        val full = navigator.update(fix(0.0), 1_000, false, facingObservation = facing(0.0))
        assertFalse(full.directionOnly)
        assertTrue(navigator.reserveInstruction(full))
        val weak = navigator.update(fix(0.0, 5.0, at = 2_000), 2_000, false,
            facingObservation = facing(0.0, 2_000))
        assertReference(weak)
        assertTrue(weak.cancelStaleNavigationSpeech)
        assertNull(navigator.currentAcceptedRouteMatchFor(2_000))
        assertFalse(navigator.reserveInstruction(full))
        assertTrue(navigator.reserveInstruction(weak))
        navigator.acknowledgeInstruction(full, 2_100)
        assertEquals("guidance_in_flight", navigator.retryGuidance(2_101, facing(0.0, 2_101))?.reason)
        navigator.acknowledgeInstruction(weak, 2_200)
        assertNotEquals("guidance_in_flight", navigator.retryGuidance(2_201, facing(0.0, 2_201))?.reason)
    }

    @Test fun repeatedReferenceWaitsTenSecondsAfterCompletionAndKeepsPendingSpeech() {
        val raw = fix(0.0, 15.0, 3f)
        val navigator = navigator(straightWithGuide(), raw)
        val first = navigator.update(raw, 1_000, false, facingObservation = facing(0.0))
        assertTrue(navigator.reserveInstruction(first))
        val next = navigator.update(raw.copy(elapsedRealtimeMs = 2_000), 2_000, false,
            facingObservation = facing(0.0, 2_000))
        assertEquals("guidance_in_flight", next.reason)
        assertFalse(next.cancelStaleNavigationSpeech)
        navigator.acknowledgeInstruction(first, 2_100)
        for (at in listOf(8_100L, 12_099L)) {
            val quiet = navigator.update(raw.copy(elapsedRealtimeMs = at), at, false, facingObservation = facing(0.0, at))
            assertNull(quiet.instruction)
        }
        val periodic = requireNotNull(navigator.retryGuidance(12_100, facing(0.0, 12_100)))
        assertReference(periodic)
        assertTrue(navigator.reserveInstruction(periodic))
    }

    private fun assertReference(update: RouteNavigatorUpdate) {
        assertEquals("route_guidance", update.reason)
        assertTrue(update.directionOnly)
        assertTrue(update.speechCueToken?.directionOnly == true)
        assertNull(update.guideIndex)
        assertFalse(update.arrived)
        assertFalse(update.userDecisionRequired)
        val text = requireNotNull(update.instruction)
        listOf("걸으세요", "몸을 돌리", "꺾으세요", "m 앞", "전체 경로는", "m 남았").forEach {
            assertFalse("Unexpected instruction $it: $text", text.contains(it))
        }
    }

    private fun clock(update: RouteNavigatorUpdate, at: Long) = RouteFacingGuidance.evaluate(
        update.routeAlignmentDiagnostic?.bearingDegreesTrueNorth, facing(0.0, at), at).clockHour
    private fun navigator(route: WalkingRoute, origin: TrustedLocation, debug: Boolean = false) =
        RouteNavigator(allowDegradedRouteGuidance = debug).apply { setRoute(route, origin = origin) }
    private fun straightWithGuide() = route(point(0.0), point(100.0)).copy(guidePoints = listOf(
        WalkingRouteGuidePoint(0, point(0.0), "출발", 0, 100, turnType = 200, pointType = "SP"),
        WalkingRouteGuidePoint(1, point(80.0), "우회전", 80, 20, turnType = 13)))
    private fun route(vararg points: RoutePoint) = WalkingRoute("STAIR_AVOID", WalkingRouteSummary(100, 80), points.toList(), emptyList())
    private fun point(east: Double, north: Double = 0.0) = RoutePoint(37.0 + north / 111_320.0,
        127.0 + east / (111_320.0 * cos(Math.toRadians(37.0))))
    private fun fix(east: Double, north: Double = 0.0, accuracy: Float = 1f, at: Long = 1_000) =
        point(east, north).let { TrustedLocation(it.latitude, it.longitude, accuracy, at) }
    private fun facing(degrees: Double, at: Long = 1_000) = RouteFacingObservation(degrees, 3.0, at)
}

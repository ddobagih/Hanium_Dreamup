package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import org.junit.Assert.*
import org.junit.Test

/** Actual response policy/Navigator, original fix times, and geometric inputs; no SDK or TTS. */
class CurrentRouteClosestApproachBehaviorTest {
    @Test fun accurateEighteenAndTwentyTwoMeterOffsetsKeepTheValidProviderRoute() {
        for (offset in listOf(18.0, 22.0)) {
            val current = fix(50.0, offset)
            assertEquals("APPROACH_REQUIRED", routeResponseInstallLocationStatus(
                route(), current, current, 1_000L, allowInitialStartConnector = false).name)
        }
    }

    @Test fun fiftyMeterCurrentOriginDriftUsesTheCurrentClosestPoint() {
        val requested = fix(0.0, 0.0)
        val current = fix(50.0, 22.0, 2_000L)
        assertEquals("APPROACH_REQUIRED", routeResponseInstallLocationStatus(
            route(), requested, current, 2_000L, allowInitialStartConnector = false).name)
        assertEquals(RouteResponseInstallLocationStatus.CONNECTED, routeResponseInstallLocationStatus(
            route(), requested, fix(50.0, 0.0, 2_000L), 2_000L, allowInitialStartConnector = false))
    }

    @Test fun fiftyMeterProviderSnapKeepsTheBoundedApproachInsteadOfRejectingGeometry() {
        val current = fix(0.0, 50.0)
        assertEquals("APPROACH_REQUIRED", routeResponseInstallLocationStatus(
            route(), current, current, 1_000L, allowInitialStartConnector = false).name)
    }

    @Test fun closestReferencePointsToTheSegmentInteriorWithoutReturningToTheFirstPoint() {
        val nav = navigator()
        val update = nav.update(fix(50.0, 22.0), 1_000L, false, facingObservation = facing(180.0))
        assertClosestReference(update)
        assertTrue(nav.isWaitingForInitialRouteJoin())
        assertNull(nav.currentAcceptedRouteMatchFor(1_000L))
        assertFalse(update.arrived)
        assertFalse(update.userDecisionRequired)
    }

    @Test fun releasedBusyReferenceRetriesAndOnlyDoneSuppressesTheSameCue() {
        val nav = navigator()
        val first = nav.update(fix(50.0, 22.0), 1_000L, false, facingObservation = facing(180.0))
        assertClosestReference(first)
        assertTrue(nav.reserveInstruction(first))
        nav.releaseInstruction(first, 1_000L)
        val retry = requireNotNull(nav.retryGuidance(3_000L, facing(180.0, 3_000L)))
        assertClosestReference(retry)
        assertTrue(nav.reserveInstruction(retry))
        nav.acknowledgeInstruction(retry, 3_000L)
        assertNull(nav.retryGuidance(3_001L, facing(180.0, 3_001L))?.instruction)
        val repeated = nav.update(fix(50.0, 22.0, 4_000L), 4_000L, false,
            facingObservation = facing(180.0, 4_000L))
        assertNull("A new fix at the same target must not repeat the connection notice", repeated.instruction)
        assertTrue(nav.isWaitingForInitialRouteJoin())
        assertNull(nav.currentAcceptedRouteMatchFor(1_000L))
    }

    @Test fun actualOnRouteFixEndsApproachAndProducesOrdinaryGuidance() {
        val nav = navigator()
        assertClosestReference(nav.update(fix(50.0, 22.0), 1_000L, false, facingObservation = facing(180.0)))
        val current = fix(50.0, 0.0, 2_000L)
        val update = nav.update(current, 2_000L, false, facingObservation = facing(90.0, 2_000L),
            filteredPosition = FilteredRoutePosition(RoutePoint(current.latitude, current.longitude),
                3.0, 2_000L, heading = RouteHeadingEstimate(90.0, 8.0)))
        assertFalse(nav.isWaitingForInitialRouteJoin())
        assertNotNull(nav.currentAcceptedRouteMatchFor(2_000L))
        assertNotNull(update.instruction)
        assertFalse(update.directionOnly)
        assertFalse(requireNotNull(update.instruction).contains("시작점"))
        assertFalse(requireNotNull(update.instruction).contains("연결 통로"))
        assertFalse(update.arrived)
    }

    @Test fun laterSegmentInteriorIsTheClosestTargetAndTheObservationDoesNotAdvanceProgress() {
        val corner = WalkingRoute("STAIR_AVOID", WalkingRouteSummary(200, 200),
            listOf(point(0.0), point(100.0), point(100.0, 100.0)), emptyList())
        val nav = RouteNavigator().apply {
            setRoute(corner, origin = fix(0.0, 0.0), waitForInitialRouteJoin = true, announceStartSummary = false)
        }
        val update = nav.update(fix(122.0, 50.0), 1_000L, false, facingObservation = facing(270.0))
        val target = requireNotNull(nav.currentRouteApproachTarget(1_000L))
        assertEquals(point(100.0, 50.0).latitude, target.point.latitude, 1e-8)
        assertEquals(point(100.0, 50.0).longitude, target.point.longitude, 1e-8)
        assertEquals(22.0, target.distanceM, 0.1)
        assertEquals(1_000L, target.observedAtElapsedRealtimeMs)
        assertEquals(target, nav.currentRouteApproachTarget(1_000L))
        assertTrue(requireNotNull(update.instruction).contains("약 12시"))
        assertNull(nav.currentAcceptedRouteMatchFor(1_000L))
        assertNull(nav.currentProjection(fix(122.0, 50.0)))
        assertFalse(update.arrived)
    }

    @Test fun futureStaleAndClearedRouteCannotExposeAnApproachTarget() {
        val nav = navigator()
        nav.update(fix(50.0, 22.0), 1_000L, false, facingObservation = facing(180.0))
        val target = requireNotNull(nav.currentRouteApproachTarget(1_000L))
        assertNull(nav.currentRouteApproachTarget(999L))
        assertNull(nav.currentRouteApproachTarget(11_001L))
        assertEquals("Observation reads do not renew or retire the original evidence", target,
            nav.currentRouteApproachTarget(1_000L))
        nav.clear()
        assertNull(nav.currentRouteApproachTarget(1_000L))
    }

    @Test fun oppositeParallelTargetsAndNearTiesDoNotInventAPreciseClockDirection() {
        val parallel = WalkingRoute("STAIR_AVOID", WalkingRouteSummary(440, 440),
            listOf(point(0.0, -20.0), point(200.0, -20.0), point(200.0, 20.0), point(0.0, 20.0)), emptyList())
        for (north in listOf(0.0, 1.0, -1.0)) {
            val nav = RouteNavigator().apply {
                setRoute(parallel, origin = fix(0.0, 0.0), waitForInitialRouteJoin = true, announceStartSummary = false)
            }
            nav.update(fix(50.0, north), 1_000L, false, facingObservation = facing(180.0))
            val retry = requireNotNull(nav.retryGuidance(2_500L, facing(180.0, 2_500L)))
            val text = requireNotNull(retry.instruction)
            assertFalse(text, Regex("약 \\d+시").containsMatchIn(text))
            assertTrue(text, text.contains("방향을 확정하기 어렵습니다"))
            assertTrue(retry.directionOnly)
            assertNull(nav.currentAcceptedRouteMatchFor(1_000L))
            assertFalse(retry.arrived)
        }
    }

    @Test fun hundredMeterBoundProvenanceAndOriginalMeasurementRejectionsRemainHardLimits() {
        val requested = fix(0.0, 0.0)
        fun status(current: TrustedLocation?, origin: TrustedLocation = requested, now: Long = 2_000L) =
            routeResponseInstallLocationStatus(route(), origin, current, now, allowInitialStartConnector = false)
        assertEquals("APPROACH_REQUIRED", status(fix(50.0, 99.9, 2_000L)).name)
        assertEquals(RouteResponseInstallLocationStatus.ROUTE_DISCONNECTED, status(fix(50.0, 100.1, 2_000L)))
        assertEquals(RouteResponseInstallLocationStatus.ROUTE_DISCONNECTED,
            status(fix(50.0, 22.0, 2_000L), origin = fix(0.0, 101.0)))
        assertEquals(RouteResponseInstallLocationStatus.ROUTE_DISCONNECTED,
            status(fix(50.0, 22.0, 2_000L), origin = requested.copy(elapsedRealtimeMs = 2_001L)))
        assertEquals(RouteResponseInstallLocationStatus.ROUTE_DISCONNECTED,
            status(fix(50.0, 40.0, 2_000L).copy(accuracyM = 16f)))
        assertEquals("The original local uncertainty contract still permits a connected fix",
            RouteResponseInstallLocationStatus.CONNECTED,
            status(fix(50.0, 22.0, 2_000L).copy(accuracyM = 16f)))
        assertEquals(RouteResponseInstallLocationStatus.LOCATION_UNAVAILABLE, status(null))
        assertEquals(RouteResponseInstallLocationStatus.LOCATION_UNAVAILABLE, status(fix(50.0, 22.0), now = 11_001L))
        assertEquals(RouteResponseInstallLocationStatus.LOCATION_UNAVAILABLE, status(fix(50.0, 22.0, 2_001L)))
        val mock = LocationTrustPolicy.trustedOrNull(37.0, 127.0, 3f, 1_000L, mock = true)
        assertNull(mock)
        assertEquals(RouteResponseInstallLocationStatus.LOCATION_UNAVAILABLE, status(mock))
    }

    @Test fun invalidShapeAndStaleFacingCannotAuthorizeTheApproachAction() {
        val current = fix(50.0, 22.0)
        val invalid = route().copy(polyline = listOf(RoutePoint(Double.NaN, 127.0), point(200.0)))
        assertEquals(RouteResponseInstallLocationStatus.ROUTE_DISCONNECTED,
            routeResponseInstallLocationStatus(invalid, current, current, 1_000L, false))
        val nav = navigator()
        val first = nav.update(current, 1_000L, false, facingObservation = facing(180.0))
        assertClosestReference(first)
        assertFalse(nav.isInstructionCurrent(first, 1_600L, facing(180.0)))
    }

    @Test fun actualMainInstallAndInstalledStartGateAllowApproachBeforeTheOrdinaryStart() {
        val main = MainActivity()
        val origin = fix(50.0, 22.0)
        assertEquals("APPROACH_REQUIRED", main.routeResponseLocationStatus(route(), origin, origin, 1_000L).name)
        main.installRequestedRoute(route(), point(200.0), origin, null, "user_destination")
        val nav = MainActivityControllerTestFixture.field("routeNavigator").get(main) as RouteNavigator
        val start = MainActivityControllerTestFixture.field("installedRouteStartSpeechCoordinator").get(main)
            as InstalledRouteStartSpeechCoordinator
        start.install("본 경로 안내 시작") { true }
        val approach = nav.update(origin, 1_000L, false, facingObservation = facing(180.0))
        assertClosestReference(approach)
        assertFalse(main.holdRouteInstructionsForStartAnnouncement(locationReady = { false },
            submitSpeech = { _, _, _ -> fail("Approach must not dispatch the ordinary start"); false },
            speechBusy = { false },
            initialJoinPending = nav.isWaitingForInitialRouteJoin()))
        assertTrue(nav.reserveInstruction(approach))
        nav.acknowledgeInstruction(approach, 1_000L)
        val current = fix(50.0, 0.0, 2_000L)
        nav.update(current, 2_000L, false, filteredPosition = FilteredRoutePosition(
            RoutePoint(current.latitude, current.longitude), 3.0, 2_000L, heading = RouteHeadingEstimate(90.0, 8.0)),
            facingObservation = facing(90.0, 2_000L))
        assertFalse(nav.isWaitingForInitialRouteJoin())
        assertNull(nav.currentRouteApproachTarget(2_000L))
        var started = 0
        var done: (() -> Unit)? = null
        assertTrue(main.holdRouteInstructionsForStartAnnouncement(
            locationReady = { nav.currentAcceptedRouteMatchFor(2_000L) != null },
            submitSpeech = { text, currentOwner, terminal ->
                assertEquals("본 경로 안내 시작", text); assertTrue(currentOwner()); started++; done = terminal; true
            }, speechBusy = { false }))
        assertEquals(1, started)
        done!!.invoke()
        assertNull(start.pending)
    }

    private fun assertClosestReference(update: RouteNavigatorUpdate) {
        val text = requireNotNull(update.instruction)
        assertTrue(text, text.contains("가장 가까운 경로"))
        assertTrue(text, text.contains("약 12시"))
        assertTrue(text, text.contains("약 22미터"))
        assertFalse(text, text.contains("시작점"))
        assertTrue(update.directionOnly)
    }

    private fun navigator() = RouteNavigator().apply {
        setRoute(route(), destination = point(200.0), origin = fix(50.0, 22.0),
            waitForInitialRouteJoin = true, announceStartSummary = false)
    }
    private fun route() = WalkingRoute("STAIR_AVOID", WalkingRouteSummary(200, 200),
        listOf(point(0.0), point(100.0), point(200.0)), emptyList())
    private fun point(eastM: Double, northM: Double = 0.0) = RoutePoint(
        37.0 + northM / 111_194.92664455874,
        127.0 + eastM / (111_194.92664455874 * kotlin.math.cos(Math.toRadians(37.0))))
    private fun fix(eastM: Double, northM: Double, time: Long = 1_000L) = point(eastM, northM).let {
        TrustedLocation(it.latitude, it.longitude, 3f, time)
    }
    private fun facing(degrees: Double, time: Long = 1_000L) = RouteFacingObservation(degrees, 0.1, time)
}

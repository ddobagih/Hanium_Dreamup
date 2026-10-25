package kr.co.hanium.dreamup.walksafe

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Actual Main terminal/ticket/continuation and Navigator, after SDK collection admission.
 * HTTP, permission APIs, Android handler timing and physical GNSS are not executed here. */
class MainActivityDisconnectedRerouteRecoveryBehaviorTest {
    @After fun releaseSession() { GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation) }

    @Test fun aDisconnectedApprovedRerouteUsesOneNewFixAndNeverInstallsTheRejectedResponse() {
        val f = Fixture()
        val route = f.nav.currentRouteId()
        assertTrue(f.retain())
        assertEquals(1, f.freshRequests)
        assertEquals(route, f.nav.currentRouteId())
        assertEquals(RouteNavigatorUserDecision.REROUTE, f.nav.pendingUserDecision())
        assertFalse(f.pending.get())
        f.continueAt(1_100L, 1_100L)
        assertTrue("The rejecting fix is not a fresh recovery", f.requests.isEmpty())
        f.continueAt(1_200L, 1_200L)
        assertEquals(listOf(f.destination to f.evidence), f.requests)
        assertNull(f.nav.pendingUserDecision())
        assertEquals(route, f.nav.currentRouteId())
        f.continueAt(1_300L, 1_300L)
        assertEquals(1, f.requests.size)
        // A second response belongs to the continuation, not another human approval.
        f.pending.set(true)
        assertTrue(f.finish())
        assertFalse(f.retain(attempted = true))
        assertEquals(1, f.freshRequests)
        assertEquals(RouteNavigatorUserDecision.REROUTE, f.nav.pendingUserDecision())
    }

    @Test fun firstStartGeneralFailureAndLocationUnavailableDoNotAcquireRerouteTickets() {
        for (kind in listOf("initial", "ordinary", "location_missing", "completed_followup")) {
            val f = Fixture()
            val retained = when (kind) {
                "initial" -> f.retain(reason = "user_destination", preserve = false)
                "ordinary" -> f.retain(reason = "user_destination")
                "location_missing" -> f.retain(status = RouteResponseInstallLocationStatus.LOCATION_UNAVAILABLE)
                else -> f.retain(attempted = true)
            }
            assertFalse(kind, retained)
            assertEquals(kind, 0, f.freshRequests)
            f.continueAt(1_200L, 1_200L)
            assertTrue(kind, f.requests.isEmpty())
        }
    }

    @Test fun staleFutureMissingOrPositionPausedFixCannotSpendTheTicket() {
        val f = Fixture(); assertTrue(f.retain())
        f.continueAt(1_200L, null)
        f.continueAt(1_200L, 1_201L)
        f.continueAt(1_200L, 1_000L)
        f.source.set("positionGuidancePaused", true)
        f.continueAt(1_200L, 1_200L)
        assertTrue(f.requests.isEmpty())
        f.source.set("positionGuidancePaused", false)
        f.continueAt(1_300L, 1_300L)
        assertEquals(1, f.requests.size)
    }

    @Test fun exactDeadlineTimeoutKeepsTheRouteChoiceAndDoesNotDescribeGpsOrDanger() {
        val f = Fixture(); assertTrue(f.retain())
        assertEquals(3_000L, f.timeouts.single().second)
        f.continueAt(4_101L, 4_101L)
        assertTrue(f.requests.isEmpty())
        f.timeouts.single().first.run()
        assertEquals(1, f.failures.size)
        assertTrue(f.failures.single().contains("새 경로"))
        assertFalse(f.failures.single().contains("멈"))
        assertFalse(f.failures.single().contains("GPS"))
        assertEquals(RouteNavigatorUserDecision.REROUTE, f.nav.pendingUserDecision())
        f.continueAt(4_102L, 4_102L)
        f.timeouts.single().first.run()
        assertTrue(f.requests.isEmpty())
        assertEquals(1, f.failures.size)
    }

    @Test fun changedOwnerRouteChoiceOrCancellationCannotReviveTheRetiredRequest() {
        for (change in listOf("actor", "session", "foreground", "epoch", "binding", "source_generation",
            "profile", "request", "destination", "route", "choice", "stop", "cancel_selection", "cancel_pause")) {
            val f = Fixture(); assertTrue(f.retain())
            when (change) {
                "actor" -> f.source.set("reporterUserId", "actor_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                "session" -> GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
                "foreground" -> f.source.set("isActivityForeground", false)
                "epoch" -> f.source.lifecycle.handle(WalkSessionEvent.EnteredBackground)
                "binding" -> f.source.set("positioningCollectionBinding", null)
                "source_generation" -> f.source.set("locationCallbackGeneration", 4)
                "profile" -> {
                    val type = Class.forName("kr.co.hanium.dreamup.walksafe.navigation.session.ContinuousGpsSource")
                    f.source.set("continuousGpsSource", type.declaredConstructors.single().apply { isAccessible = true }
                        .newInstance(f.source.binding, f.source.epoch, f.source.profile.copy(profileId = "old-profile")))
                }
                "request" -> f.source.set("routeRequestGeneration", 8)
                "destination" -> f.source.set("currentDestination", RoutePoint(38.0, 127.0))
                "route" -> f.nav.setRoute(f.route, destination = f.destination)
                "choice" -> assertTrue(f.nav.approveReroute().shouldReroute)
                "stop" -> f.source.set("isRouteActive", false)
                "cancel_selection" -> f.main.cancelNavigationRequestsForDestinationSelection()
                "cancel_pause" -> f.main.cancelNavigationRequestsForPause()
            }
            f.continueAt(1_200L, 1_200L)
            f.timeouts.single().first.run()
            assertTrue(change, f.requests.isEmpty())
            assertTrue(change, f.failures.isEmpty())
        }
    }

    @Test fun connectionRecoverySharesTheExistingGpsQueryAndCannotRetagAnOldPassAsRecovery() {
        val f = Fixture()
        var calls = 0; var cancellations = 0
        val request: ((android.location.Location?) -> Unit) -> (() -> Unit) = { _ -> calls++; { cancellations++ } }
        f.main.requestFreshGpsForCurrentSource({ f.source.now }, request, { _, _ -> fail("No SDK result supplied") })
        assertTrue(f.retain(requestFresh = {
            f.main.requestFreshGpsForCurrentSource({ f.source.now }, request, { _, _ -> fail("No SDK result supplied") })
        }))
        assertEquals(1, calls)
        f.main.reconcileContinuousGpsRecovery({ f.source.now }, request, { _, _ -> })
        assertEquals("Same original PASS cannot cancel a new measurement request", 0, cancellations)
        f.continueAt(1_200L, 1_200L)
        assertEquals(1, f.requests.size)
        assertEquals(1, calls)
    }

    @Test fun productionRejectionAndNewFixEntriesKeepTheExistingAdmissionAndOneAttemptContract() {
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteAcquisitionController.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val request = method("requestRoute", 1)
        val reject = request.substringAfter("fun rejectDisconnectedResponse(").substringBefore("val installLocation =")
        assertTrue(reject.indexOf("finishCurrentRouteRequest()") < reject.indexOf("retainDisconnectedRerouteForFreshLocation("))
        assertTrue(reject.contains("connectionRecoveryAttempted, installLocationStatus"))
        val continuation = method("continueDisconnectedRerouteRecoveryIfReady")
        val admitted = method("continueAdmittedDisconnectedRerouteRecovery")
        assertTrue(continuation.contains("!facts.navigationAllowed"))
        assertTrue(admitted.contains("connectionRecoveryAttempted = true"))
        assertTrue(admitted.contains("LocationTrustPolicy.freshOrNull(location, nowMs)"))
        val position = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/PositioningSessionController.kt")
        val update = NavigationPolicySource.observation(source)
            .substringAfter("val positionGuidancePaused = guidance().applyPositionConfidenceDecision(")
        assertTrue(update.contains("requests().continueDisconnectedRerouteRecoveryIfReady()"))
        assertTrue(update.indexOf("requests().continueDisconnectedRerouteRecoveryIfReady()") < update.indexOf("effects.clearReportLocationStartSchedule()"))
        assertTrue(request.contains("routeResponseInstallLeaseCurrent(requestId, ::isCurrentRouteRequest)"))
        assertTrue(method("routeResponseLocationStatus").contains("allowInitialStartConnector = false"))
        assertTrue(request.contains("val origin = facts.currentLocation"))
        assertTrue(request.contains("origin = RoutePoint(origin.latitude, origin.longitude, \"현재 위치\")"))
        assertTrue(reject.contains("기존 목적지와 재탐색 선택을 유지합니다."))
        assertFalse(reject.contains("멈춰"))
        val positioningWire = MainActivityRefactorSource.composition(source, "positioningSessionController")
        assertTrue(positioningWire.contains("PositioningSessionController(run, scope, sources, mounting,"))
        assertTrue(positioningWire.contains("}, diagnostics.value, { gps.value }, { compass.value }, { guidance.value }, { acquisition.value },"))
        val requestsWire = MainActivityRefactorSource.composition(source, "routeAcquisitionController")
        assertTrue(requestsWire.contains("val activityFacts = routes.facts()"))
        assertTrue(requestsWire.contains("RouteAcquisitionController(run, archive,"))
        assertTrue(requestsWire.contains("override val navigationAllowed: Boolean get() = activityFacts.navigationAllowed"))
        assertTrue(requestsWire.contains("override val currentLocation get() = positioning.value.currentRouteLocationOrNull()"))
        val assembly = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        val routeFacts = ReportStaticSourceInspector.blockAfter(assembly.substringAfter("routes = AcquisitionAssemblyBindings("), "facts =")
        assertTrue(routeFacts.contains("override val navigationAllowed get() = currentNavigationCollectionAllowsWork()"))
        assertTrue(MainActivityRefactorSource.member(source, "currentRouteLocationOrNull")
            .contains("positioningSessionController.currentRouteLocationOrNull()"))
    }

    private class Fixture {
        val source = MainActivityContinuousLocationStartBehaviorTest.Fixture()
        val main get() = source.main
        val nav = RouteNavigator(RouteNavigatorConfig(offRouteConfirmSamples = 1), nowElapsedRealtimeMs = { source.now })
        val destination = RoutePoint(37.0009, 127.0, "목적지")
        val evidence = DestinationCoordinateEvidence(null, emptyList())
        val route = WalkingRoute("TEST", WalkingRouteSummary(100, 90), listOf(RoutePoint(37.0, 127.0), destination), emptyList())
        val pending get() = source.field("routeRequestInFlight") as AtomicBoolean
        val gateway: GatewaySessionProcessSnapshot
        var freshRequests = 0
        val requests = mutableListOf<Pair<RoutePoint, DestinationCoordinateEvidence?>>()
        val failures = mutableListOf<String>()
        val timeouts = mutableListOf<Pair<Runnable, Long>>()
        init {
            source.seedPassingMeasurement(); source.activate()
            gateway = GatewaySessionProcessCoordinator.snapshot()
            nav.setRoute(route, destination = destination)
            nav.update(TrustedLocation(37.0, 127.01, 5f, 1_000L), 1_000L, false)
            assertTrue(nav.approveReroute().shouldReroute)
            source.set("routeNavigator", nav); source.set("currentDestination", destination)
            source.set("isRouteActive", true); source.set("routeRequestGeneration", 7)
            pending.set(true)
            assertTrue(finish())
            assertEquals(RouteNavigatorUserDecision.REROUTE, nav.pendingUserDecision())
            source.now = 1_100L
        }
        fun finish() = main.finishRouteRequestIfCurrent(source.epoch, 7, gateway.generation,
            requireNotNull(gateway.session), true, "off_route")
        fun retain(reason: String = "off_route", preserve: Boolean = true, attempted: Boolean = false,
            status: RouteResponseInstallLocationStatus = RouteResponseInstallLocationStatus.ROUTE_DISCONNECTED,
            requestFresh: () -> Unit = { freshRequests++ }) = main.retainDisconnectedRerouteForFreshLocation(
            source.epoch, 7, gateway.generation, requireNotNull(gateway.session), destination, evidence,
            reason, preserve, attempted, status, 1_000L, 1_100L, { source.now }, requestFresh,
            { runnable, delay -> timeouts += runnable to delay }, { failures += it })
        fun continueAt(now: Long, measuredAt: Long?) {
            source.now = now
            main.continueAdmittedDisconnectedRerouteRecovery({ source.now }, {
                measuredAt?.let { TrustedLocation(37.0, 127.01, 5f, it) }
            }, { destination, evidence -> requests += destination to evidence })
        }
    }
}

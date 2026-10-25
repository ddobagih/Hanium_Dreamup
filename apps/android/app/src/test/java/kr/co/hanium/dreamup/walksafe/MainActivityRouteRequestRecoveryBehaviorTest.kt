package kr.co.hanium.dreamup.walksafe

import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckSnapshot
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckState
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Actual Main lease/transport bodies and real walk/session state, without HTTP, Android UI or TTS. */
class MainActivityRouteRequestRecoveryBehaviorTest {
    @After fun releaseSession() {
        GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
    }

    @Test fun temporaryEnvironmentRestrictionDoesNotRetireTheExactRequestOwner() {
        val fixture = Fixture()
        assertTrue(fixture.boolean("isWalkSessionRuntimeActive"))
        assertFalse(fixture.boolean("navigationEnvironmentOutputsAllowed"))
        val call = fixture.main.trackRouteRequest(CancellableNetworkCall(executeBlock = { "response" }))
        fixture.pending.set(true)
        fixture.main.completeRouteRequest(call)

        assertFalse(fixture.transport.hasActiveRoute())
        assertTrue("HTTP completion must still leave the queued response pending", fixture.pending.get())
        assertTrue("The same request must remain owned for terminal cleanup while outputs are blocked",
            fixture.ownsRequest())
        assertFalse("Ownership must not authorize response installation while outputs are blocked",
            fixture.main.routeResponseInstallLeaseCurrent(7) {
                fixture.ownsRequest() && fixture.boolean("navigationEnvironmentOutputsAllowed")
            })
    }

    @Test fun oldRequestOrWalkCannotAcquireTheCurrentRequestOwner() {
        val fixture = Fixture()
        fixture.pending.set(true)
        assertFalse(fixture.ownsRequest(requestId = 6))
        assertFalse(fixture.ownsRequest(epoch = fixture.epoch.copy(recoveryGeneration = 99)))
        assertTrue(fixture.pending.get())
    }

    @Test fun restrictedRerouteCompletionReleasesLoadingAndRestoresTheSameRouteChoiceWithoutSpeech() {
        val fixture = Fixture()
        val navigator = fixture.approvedReroute()
        val routeId = navigator.currentRouteId()
        val destination = fixture.field("currentDestination")
        val statusOwner = fixture.field("statusPresentationOwner")
        val speechGeneration = fixture.field("commandSpeechResponseGeneration")
        val call = fixture.main.trackRouteRequest(CancellableNetworkCall(executeBlock = { "response" }))
        fixture.main.completeRouteRequest(call)

        assertTrue(fixture.finish())

        assertFalse(fixture.pending.get())
        assertFalse(fixture.transport.hasActiveRoute())
        assertTrue(navigator.hasRoute())
        assertEquals(routeId, navigator.currentRouteId())
        assertSame(destination, fixture.field("currentDestination"))
        assertEquals(true, fixture.field("isRouteActive"))
        assertEquals(RouteNavigatorUserDecision.REROUTE, navigator.pendingUserDecision())
        assertNotNull(navigator.pendingDecisionToken())
        assertSame(statusOwner, fixture.field("statusPresentationOwner"))
        assertEquals(speechGeneration, fixture.field("commandSpeechResponseGeneration"))
        assertNull(fixture.field("feedbackActuator"))
        assertFalse(fixture.main.routeResponseInstallLeaseCurrent(7) { true })
        assertTrue("Recovered choice accepts a fresh explicit reroute", navigator.approveReroute().shouldReroute)
        assertTrue("A new request can reserve loading", fixture.pending.compareAndSet(false, true))
        fixture.set("routeRequestGeneration", 8)
        assertFalse("An old terminal cannot clear that newer request", fixture.finish())
        assertTrue(fixture.pending.get())
    }

    @Test fun initialRequestTerminalRetainsTheSelectedDestinationAndAllowsAnotherExplicitStart() {
        val fixture = Fixture()
        val destination = RoutePoint(37.001, 127.0, "목적지")
        fixture.set("currentDestination", destination)
        fixture.set("isRouteActive", true)
        fixture.pending.set(true)

        assertTrue(fixture.finish(preserveExistingRoute = false, reason = "user_destination"))

        assertFalse(fixture.pending.get())
        assertEquals(false, fixture.field("isRouteActive"))
        assertSame(destination, fixture.field("currentDestination"))
        assertTrue(fixture.pending.compareAndSet(false, true))
    }

    @Test fun staleIdEpochActorOrProcessSessionCannotFinishANewerRequest() {
        val fixture = Fixture()
        val navigator = fixture.approvedReroute()
        assertFalse(fixture.finish(requestId = 6))
        assertFalse(fixture.finish(epoch = fixture.epoch.copy(recoveryGeneration = 99)))
        fixture.set("reporterUserId", "another-actor")
        assertFalse(fixture.finish())
        fixture.set("reporterUserId", fixture.session.actorId)
        fixture.replaceSession()
        assertFalse("Even a new session for the same actor must not inherit this terminal", fixture.finish())
        assertTrue(fixture.pending.get())
        assertNull(navigator.pendingUserDecision())
        assertTrue(navigator.hasRoute())
    }

    @Test fun credentialReadinessLossStillRestoresTheOwnedRerouteBeforeSessionRenewal() {
        val fixture = Fixture()
        val navigator = fixture.approvedReroute()
        fixture.session.invalidate()
        assertFalse(fixture.session.isUsableFor(fixture.session.actorId))

        assertTrue(fixture.finish())

        assertFalse(fixture.pending.get())
        assertEquals(RouteNavigatorUserDecision.REROUTE, navigator.pendingUserDecision())
        val token = navigator.pendingDecisionToken()
        fixture.replaceSession()
        fixture.pending.set(true)
        assertFalse(fixture.finish())
        assertTrue(fixture.pending.get())
        assertEquals(token, navigator.pendingDecisionToken())
    }

    @Test fun independentSessionRenewalMustRetireThePendingRequestBeforeItsOldCallback() {
        val fixture = Fixture()
        val navigator = fixture.approvedReroute()
        val oldRoute = navigator.currentRouteId()
        val call = fixture.trackRequest()
        assertNotNull(GatewaySessionProcessCoordinator.beginGeneralSessionOperationIfCurrent(
            fixture.generation, fixture.session))
        val renewing = GatewaySessionProcessCoordinator.snapshot()
        assertSame(fixture.session, renewing.session)
        assertNotEquals(fixture.generation, renewing.generation)
        assertTrue(fixture.main.retireRouteRequestForGatewayTransition(renewing))
        assertTrue(call.isCancelled())
        fixture.main.completeRouteRequest(call)
        assertFalse("The old callback must not claim the new process generation", fixture.finish())

        assertFalse("The accepted session transition must retire its pending route", fixture.pending.get())
        assertEquals(oldRoute, navigator.currentRouteId())
        assertEquals(RouteNavigatorUserDecision.REROUTE, navigator.pendingUserDecision())
    }

    @Test fun latestSameFamilyRenewalRetiresTheRequestEvenWhenTheBeginNotificationWasSkipped() {
        val fixture = Fixture(longLived = true)
        val navigator = fixture.approvedReroute()
        val routeId = navigator.currentRouteId()
        val call = fixture.trackRequest()
        val operation = requireNotNull(GatewaySessionProcessCoordinator.beginGeneralSessionOperationIfCurrent(
            fixture.generation, fixture.session))
        val renewed = fixture.longLivedSession(rotation = 1L)
        fixture.publish(renewed, operation)

        assertTrue(fixture.main.retireRouteRequestForGatewayTransition(GatewaySessionProcessCoordinator.snapshot()))

        assertFalse(fixture.pending.get())
        assertTrue(call.isCancelled())
        assertFalse(fixture.transport.hasActiveRoute())
        assertEquals(routeId, navigator.currentRouteId())
        assertEquals(RouteNavigatorUserDecision.REROUTE, navigator.pendingUserDecision())
        assertFalse(fixture.finish())
    }

    @Test fun delayedSessionNotificationDoesNotRetireANewerRequestOrItsTransport() {
        val fixture = Fixture(longLived = true)
        fixture.approvedReroute()
        val oldCall = fixture.trackRequest()
        val operation = requireNotNull(GatewaySessionProcessCoordinator.beginGeneralSessionOperationIfCurrent(
            fixture.generation, fixture.session))
        val delayed = GatewaySessionProcessCoordinator.snapshot()
        val renewed = fixture.longLivedSession(rotation = 1L)
        fixture.publish(renewed, operation)
        val latest = GatewaySessionProcessCoordinator.snapshot()
        fixture.main.completeRouteRequest(oldCall)
        val newCall = fixture.main.trackRouteRequest(CancellableNetworkCall(executeBlock = { "new response" }))
        fixture.set("routeRequestGeneration", 8)
        fixture.rememberRequest(newCall, requestId = 8, session = renewed, generation = latest.generation)

        assertFalse(fixture.main.retireRouteRequestForGatewayTransition(delayed))
        assertFalse(fixture.main.retireRouteRequestForGatewayTransition(latest))

        assertTrue(fixture.pending.get())
        assertFalse(newCall.isCancelled())
        assertFalse(oldCall.isCancelled())
        assertTrue(fixture.transport.hasActiveRoute())
    }

    @Test fun anotherLoginOrMismatchingSnapshotCannotRestoreTheOldRouteChoice() {
        val fixture = Fixture(longLived = true)
        val navigator = fixture.approvedReroute()
        val call = fixture.trackRequest()
        val operation = requireNotNull(GatewaySessionProcessCoordinator.beginGeneralSessionOperationIfCurrent(
            fixture.generation, fixture.session))
        val renewing = GatewaySessionProcessCoordinator.snapshot()
        val inconsistent = GatewaySessionProcessSnapshot(renewing.session, renewing.restoredFirstRunSnapshot,
            renewing.generation, true, renewing.inFlightOperationId, renewing.deletionRecoveryOnly)
        assertFalse(fixture.main.retireRouteRequestForGatewayTransition(inconsistent))
        fixture.publish(fixture.longLivedSession(rotation = 1L, family = "b".repeat(32)), operation)
        assertFalse(fixture.main.retireRouteRequestForGatewayTransition(GatewaySessionProcessCoordinator.snapshot()))
        assertTrue(fixture.pending.get())
        assertFalse(call.isCancelled())
        assertNull(navigator.pendingUserDecision())
    }

    @Test fun renewalDoesNotAlterAnInstalledRouteWithoutAnOutstandingRequest() {
        val fixture = Fixture()
        val navigator = fixture.approvedReroute()
        val call = fixture.trackRequest()
        val routeId = navigator.currentRouteId()
        fixture.pending.set(false)
        fixture.main.completeRouteRequest(call)
        assertNotNull(GatewaySessionProcessCoordinator.beginGeneralSessionOperationIfCurrent(
            fixture.generation, fixture.session))

        assertFalse(fixture.main.retireRouteRequestForGatewayTransition(GatewaySessionProcessCoordinator.snapshot()))

        assertEquals(routeId, navigator.currentRouteId())
        assertNull(navigator.pendingUserDecision())
        assertEquals(true, fixture.field("isRouteActive"))
        assertFalse(call.isCancelled())
    }

    @Test fun renewalCannotRetireAnOwnerFromAnOldRequestWalkOrActor() {
        val fixture = Fixture()
        val navigator = fixture.approvedReroute()
        val call = fixture.trackRequest()
        assertNotNull(GatewaySessionProcessCoordinator.beginGeneralSessionOperationIfCurrent(
            fixture.generation, fixture.session))
        val current = GatewaySessionProcessCoordinator.snapshot()
        fixture.rememberRequest(call, requestId = 6)
        assertFalse(fixture.main.retireRouteRequestForGatewayTransition(current))
        fixture.rememberRequest(call, epoch = fixture.epoch.copy(recoveryGeneration = 99))
        assertFalse(fixture.main.retireRouteRequestForGatewayTransition(current))
        fixture.rememberRequest(call)
        fixture.set("reporterUserId", "another-actor")
        assertFalse(fixture.main.retireRouteRequestForGatewayTransition(current))
        assertTrue(fixture.pending.get())
        assertFalse(call.isCancelled())
        assertNull(navigator.pendingUserDecision())
    }

    @Test fun renewalCancelsOnlyItsCapturedTransportWhenTheCoordinatorAlreadyTracksAnotherCall() {
        val fixture = Fixture()
        fixture.approvedReroute()
        val oldCall = fixture.trackRequest()
        fixture.main.completeRouteRequest(oldCall)
        val otherCall = fixture.main.trackRouteRequest(CancellableNetworkCall(executeBlock = { "other response" }))
        assertNotNull(GatewaySessionProcessCoordinator.beginGeneralSessionOperationIfCurrent(
            fixture.generation, fixture.session))

        assertTrue(fixture.main.retireRouteRequestForGatewayTransition(GatewaySessionProcessCoordinator.snapshot()))

        assertTrue(oldCall.isCancelled())
        assertFalse(otherCall.isCancelled())
        assertTrue(fixture.transport.hasActiveRoute())
    }

    @Test fun productionSessionObserverAndRequestCreationWireTheTestedOwnerTransition() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val mainObserver = MainActivityRefactorSource.member(source, "onGatewayProcessSessionChanged")
        assertTrue(mainObserver.contains(
            "actorSessionLifecycleCoordinator.onGatewayProcessSessionChanged(snapshot, consentAuthorityChanged)"))
        val observerCore = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/session/gateway/ActorSessionLifecycleCoordinator.kt",
        ).readText()
        val observer = MainActivityRefactorSource.member(observerCore, "onGatewayProcessSessionChanged")
        assertTrue(observer.indexOf("routes.retireRequest(snapshot)") in
            0 until observer.indexOf("routes.reconcileAccount(snapshot)"))
        assertTrue(observer.contains("snapshot.generation == gateway.snapshot().generation"))
        val actorComposition = MainActivityRefactorSource.composition(source, "actorSessionLifecycleCoordinator")
        val routes = ReportStaticSourceInspector.blockAfter(actorComposition,
            "routes = object : ActorSessionLifecycleCoordinator.Routes")
        assertTrue(routes.contains(
            "override fun retireRequest(snapshot: GatewaySessionProcessSnapshot) = retireRouteRequestForGatewayTransition(snapshot)"))
        assertTrue(routes.contains(
            "override fun reconcileAccount(snapshot: GatewaySessionProcessSnapshot) { this@MainActivity.routeArchiveController.reconcileRouteSnapshotAccount(snapshot) }"))
        val archiveComposition = MainActivityRefactorSource.composition(source, "routeArchiveController")
        assertTrue(archiveComposition.contains("val lane = resources.lane()"))
        assertTrue(archiveComposition.contains("val activity = archiveFacts()"))
        assertTrue(archiveComposition.contains("override fun gatewaySnapshot(): GatewaySessionProcessSnapshot = activity.gatewaySnapshot()"))
        val assembly = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        assertTrue(assembly.contains("lane = { ROUTE_SNAPSHOT_IO_LANE }"))
        val archiveFacts = ReportStaticSourceInspector.blockAfter(assembly, "archiveFacts =")
        assertTrue(archiveFacts.contains("override fun gatewaySnapshot() = GatewaySessionProcessCoordinator.snapshot()"))
        val core = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteAcquisitionController.kt").readText()
        val composition = MainActivityRefactorSource.composition(source, "routeAcquisitionController")
        assertTrue(composition.contains("RouteAcquisitionController(run, archive,"))
        assertTrue(composition.contains("val archive = this.archive.value"))
        assertTrue(composition.contains("val activityFacts = routes.facts()"))
        assertTrue(composition.contains("override val gateway: GatewaySessionProcessSnapshot get() = activityFacts.gateway"))
        assertTrue(composition.contains("override val rawActorId: String? get() = activityFacts.rawActorId"))
        assertTrue(composition.contains("override val lifecycle: WalkSessionLifecycle? get() = activityFacts.lifecycle"))
        assertTrue(composition.contains("worker = routes.worker()"))
        val routeBindings = assembly.substringAfter("routes = AcquisitionAssemblyBindings(")
        val routeFacts = ReportStaticSourceInspector.blockAfter(routeBindings, "facts =")
        assertTrue(routeFacts.contains("override val gateway get() = GatewaySessionProcessCoordinator.snapshot()"))
        assertTrue(routeFacts.contains("override val rawActorId get() = reporterUserId"))
        assertTrue(routeFacts.contains("override val lifecycle get() = if (::walkSessionLifecycle.isInitialized) walkSessionLifecycle else null"))
        assertTrue(routeBindings.contains("worker = { routeExecutor }"))
        val routeEffects = ReportStaticSourceInspector.blockAfter(routeBindings, "effects =")
        assertTrue(composition.contains("activityEffects.failureAdmission(session, authenticationFailure)"))
        assertTrue(routeEffects.contains("gatewayFailureUiGuardOrNull(session = session,"))
        assertTrue(routeEffects.contains("expectedAuthenticationFailure = authenticationFailure"))
        assertTrue(routeEffects.contains("isGatewayFailureUiGuardCurrent(session, guard)"))
        assertTrue(MainActivityRefactorSource.member(source, "requestRoute", occurrence = 1)
            .contains("routeAcquisitionController.requestRoute(destination, reason,"))
        val request = MainActivityRefactorSource.member(core, "requestRoute", occurrence = 1)
        assertTrue(request.indexOf("activeRouteRequestOwner = PendingRouteRequestOwner(") in
            0 until request.indexOf("worker.execute {"))
        val failed = request.substringAfter("catch (error: Exception)")
        val finish = failed.indexOf("finishCurrentRouteRequest(terminalCause = cause)")
        assertTrue(finish in
            0 until failed.indexOf("effects.failureAdmission("))
        assertTrue(failed.indexOf("val currentAtFailure = isCurrentRouteRequest()") in 0 until finish)
    }

    @Test fun duplicateTerminalCannotReopenAnAlreadyHandledChoice() {
        val fixture = Fixture()
        val navigator = fixture.approvedReroute()
        assertTrue(fixture.finish())
        assertTrue(navigator.approveReroute().shouldReroute)
        assertFalse(fixture.finish())
        assertNull(navigator.pendingUserDecision())
    }

    @Test fun transportCompletionLeavesTheResponseInstallReservationUntilATerminalClaimsIt() {
        val fixture = Fixture()
        fixture.pending.set(true)
        val call = fixture.main.trackRouteRequest(CancellableNetworkCall(executeBlock = { "response" }))
        fixture.main.completeRouteRequest(call)
        assertTrue(fixture.main.routeResponseInstallLeaseCurrent(7) { true })
        assertFalse(fixture.main.routeResponseInstallLeaseCurrent(6) { true })
        assertFalse(fixture.main.routeResponseInstallLeaseCurrent(7) { false })
        assertTrue(fixture.pending.get())
        assertTrue(fixture.finish(preserveExistingRoute = false, reason = "user_destination"))
        assertFalse(fixture.main.routeResponseInstallLeaseCurrent(7) { true })
    }

    private class Fixture(longLived: Boolean = false) {
        val main = MainActivity()
        val lifecycle = WalkSessionLifecycle(sessionIdFactory = { "route-recovery-walk" },
            clock = { 100L }, elapsedRealtimeClock = { 100L })
        val epoch: WalkRuntimeEpoch
        val session: GatewayFieldSession
        val generation: Long
        val pending get() = field("routeRequestInFlight") as AtomicBoolean
        val transport get() = field("navigationRequests") as AndroidNavigationRequestCoordinator

        init {
            GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
            val actor = "actor_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            val onboarding = FirstRunOnboardingPolicy.initialEmailAccount().copy(
                stage = FirstRunOnboardingStage.COMPLETE,
                ageBand = FirstRunAgeBand.VERIFIED_14_PLUS,
                completedReceiptHashes = listOf(FirstRunOnboardingStage.VERIFIED_LOGIN,
                    FirstRunOnboardingStage.PURPOSE_AND_SAFETY, FirstRunOnboardingStage.FP004_TRAINING)
                    .associateWith { FirstRunReceiptHash.fromSha256Hex("a".repeat(64)) },
                verifiedActorBinding = FirstRunOpaqueActorBinding.fromProvider(actor),
            )
            session = if (longLived) longLivedSession() else GatewayFieldSession.verified(
                "https://gateway.example.test", actor, "walksafe_field_session=v2.fixture.session", Long.MAX_VALUE)
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, onboarding))
            generation = GatewaySessionProcessCoordinator.snapshot().generation
            set("reporterUserId", actor)
            set("firstRunOnboardingSnapshot", onboarding)
            set("postLoginDeviceCheckSnapshot", PostLoginDeviceCheckSnapshot(PostLoginDeviceCheckState.FULL,
                actor, GatewaySessionProcessCoordinator.snapshot().generation, 1L))
            set("isActivityForeground", true)
            set("officialEnvironmentOutputsAllowed", false)
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            val readiness = WalkSessionReadinessCollector(lifecycle.snapshot().epoch,
                WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL))
            readiness.plan.requiredRequirements.forEach {
                readiness.record(WalkSessionReadinessObservation(lifecycle.snapshot().epoch, it,
                    WalkSessionReadinessStatus.READY))
            }
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readiness.build(100L)))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            epoch = lifecycle.snapshot().epoch
            val authority = GatewayWalkAuthorityController(elapsedClock = { 100L })
            val start = requireNotNull(authority.beginStart(epoch))
            assertEquals(GatewayWalkAuthorityCompletion.LEASE_ACTIVE,
                authority.completeStart(start, GatewayWalkStartResult.Granted(GatewayWalkLease(
                    "GRANTED", actor, "fixture-device", epoch.walkSessionId, "fixture-lease", 1L,
                    100L, 100_000L, 100L, 100_000L))))
            set("walkSessionLifecycle", lifecycle)
            set("gatewayWalkAuthorityController", authority)
            set("routeRequestGeneration", 7)
        }

        fun approvedReroute(): RouteNavigator {
            val navigator = RouteNavigator(RouteNavigatorConfig(offRouteConfirmSamples = 1),
                nowElapsedRealtimeMs = { 1_000L })
            val destination = RoutePoint(37.0009, 127.0, "목적지")
            navigator.setRoute(WalkingRoute("STAIR_AVOID", WalkingRouteSummary(100, 90),
                listOf(RoutePoint(37.0, 127.0), destination), emptyList()), destination = destination)
            navigator.update(TrustedLocation(37.0, 127.01, 5f, 1_000L),
                nowMs = 1_000L, requestInFlight = false)
            assertTrue(navigator.approveReroute().shouldReroute)
            assertNull(navigator.pendingUserDecision())
            set("routeNavigator", navigator)
            set("currentDestination", destination)
            set("isRouteActive", true)
            pending.set(true)
            return navigator
        }

        fun trackRequest(): CancellableNetworkCall<String> =
            main.trackRouteRequest(CancellableNetworkCall(executeBlock = { "response" })).also {
                rememberRequest(it)
            }

        fun rememberRequest(call: CancellableNetworkCall<*>, epoch: WalkRuntimeEpoch = this.epoch,
            requestId: Int = 7, generation: Long = this.generation, session: GatewayFieldSession = this.session) {
            set("activeRouteRequestOwner", kr.co.hanium.dreamup.walksafe.navigation.session.RouteAcquisitionController.PendingRouteRequestOwner(epoch, requestId,
                generation, session, true, "off_route", call))
        }

        fun longLivedSession(rotation: Long = 0L, family: String = "f".repeat(32)): GatewayFieldSession =
            GatewayFieldSession.longLivedSession("https://gateway.example.test",
                "actor_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "fixture-device", family, rotation,
                "walksafe_field_session=v2.fixture.rotation.$rotation", "r".repeat(64),
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)

        fun publish(replacement: GatewayFieldSession, operation: GatewaySessionOperation) {
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, replacement,
                field("firstRunOnboardingSnapshot") as FirstRunOnboardingSnapshot))
        }

        fun finish(epoch: WalkRuntimeEpoch = this.epoch, requestId: Int = 7,
            preserveExistingRoute: Boolean = true, reason: String = "off_route"): Boolean =
            main.finishRouteRequestIfCurrent(epoch, requestId, generation, session, preserveExistingRoute, reason)

        fun replaceSession() {
            val replacement = GatewayFieldSession.verified("https://gateway.example.test", session.actorId,
                "walksafe_field_session=v2.fixture.replacement", Long.MAX_VALUE)
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, replacement,
                field("firstRunOnboardingSnapshot") as FirstRunOnboardingSnapshot))
        }

        fun ownsRequest(epoch: WalkRuntimeEpoch = this.epoch, requestId: Int = 7): Boolean =
            method("isRouteRequestLeaseCurrent", java.lang.Boolean.TYPE, WalkRuntimeEpoch::class.java,
                Integer.TYPE).invokeWithArguments(main, epoch, requestId) as Boolean

        fun boolean(name: String) = method(name, java.lang.Boolean.TYPE).invokeWithArguments(main) as Boolean
        fun field(name: String): Any? = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.get(main)
        fun set(name: String, value: Any?) = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.set(main, value)
        fun method(name: String, result: Class<*>, vararg arguments: Class<*>) =
            MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup()).findVirtual(
                MainActivity::class.java, name, MethodType.methodType(result, arguments.toList()))
    }
}

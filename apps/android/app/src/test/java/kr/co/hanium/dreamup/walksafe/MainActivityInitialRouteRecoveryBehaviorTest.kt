package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.navigation.RouteSnapshotIoLane
import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kr.co.hanium.dreamup.walksafe.security.*
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckSnapshot
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckState
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Actual Main terminal, pending-intent continuation and Navigator state. The continuation is called
 * after the SDK collection admission boundary, with a trusted-location observation and request sink.
 * This does not execute Android permissions, the sensor callback, provider HTTP or route storage.
 */
class MainActivityInitialRouteRecoveryBehaviorTest {
    @After fun releaseSession() {
        GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
    }

    @Test fun completedInitialResponseRejectedByEnvironmentRetriesOnceFromANewFix() {
        val f = Fixture()
        val call = f.admitRequest()
        val response = call.execute()
        assertEquals(f.destination, response.polyline.last())
        assertTrue(f.boolean("isWalkSessionRuntimeActive"))
        assertFalse(f.boolean("navigationEnvironmentOutputsAllowed"))
        assertFalse(f.main.routeResponseInstallLeaseCurrent(7) {
            f.ownsRequest() && f.boolean("navigationEnvironmentOutputsAllowed")
        })
        f.main.completeRouteRequest(call)
        assertTrue(f.finish(completedResponse = true))
        assertFalse(f.inFlight.get())
        assertFalse(f.transport.hasActiveRoute())
        assertFalse("The rejected response must never install a route", f.navigator.hasRoute())

        f.publishRecoveredEnvironment()
        f.continueWithFreshFix()
        f.continueWithFreshFix()

        assertEquals("The same explicit initial start must retry once after a newer admitted fix",
            listOf(f.expectedRequest()), f.requests)
        assertFalse("Only the new request may eventually install its response", f.navigator.hasRoute())
        assertNull(f.pending.currentOrNull())
    }

    @Test fun admittedPendingExplicitStartUsesTheRealContinuationExactlyOnce() {
        val f = Fixture()
        f.seedPending()
        f.continueWithFreshFix()
        f.continueWithFreshFix()
        assertEquals(listOf(f.expectedRequest()), f.requests)
        assertNull(f.pending.currentOrNull())
    }

    @Test fun ordinaryFailureAndNonExplicitInitialRequestDoNotBecomeAutomaticRetries() {
        for (explicit in listOf(true, false)) {
            val f = Fixture()
            val call = f.admitRequest(explicit = explicit)
            f.main.completeRouteRequest(call)
            assertTrue(f.finish(completedResponse = !explicit))
            f.continueWithFreshFix()
            assertTrue(f.requests.isEmpty())
            assertNull(f.pending.currentOrNull())
        }
    }

    @Test fun oldRequestEpochActorOrSessionCannotCreateANewPendingStart() {
        for (change in listOf("request", "epoch", "actor", "session")) {
            val f = Fixture()
            f.admitRequest()
            when (change) {
                "request" -> f.set("routeRequestGeneration", 8)
                "epoch" -> f.lifecycle.handle(WalkSessionEvent.EnteredBackground)
                "actor" -> f.set("reporterUserId", "actor_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                "session" -> f.replaceSession()
            }
            assertFalse(change, f.finish(completedResponse = true))
            assertTrue(change, f.inFlight.get())
            assertNull(change, f.pending.currentOrNull())
            assertFalse(change, f.navigator.hasRoute())
        }
    }

    @Test fun changedDestinationEndedWalkAndBackgroundDoNotRestartTheInitialRequest() {
        for (change in listOf("destination", "end", "background")) {
            val f = Fixture()
            val call = f.admitRequest()
            when (change) {
                "destination" -> f.set("currentDestination", RoutePoint(38.0, 128.0, "다른 목적지"))
                "end" -> f.lifecycle.handle(WalkSessionEvent.EndRequested)
                "background" -> {
                    f.set("isActivityForeground", false)
                    f.lifecycle.handle(WalkSessionEvent.EnteredBackground)
                }
            }
            f.main.completeRouteRequest(call)
            f.finish(completedResponse = true)
            f.continueWithFreshFix()
            assertTrue(change, f.requests.isEmpty())
            assertNull(change, f.pending.currentOrNull())
        }
    }

    @Test fun continuationKeepsPositionAndPendingDeadlineFences() {
        val f = Fixture()
        f.seedPending()
        f.set("positionGuidancePaused", true)
        f.continueWithFreshFix()
        assertTrue(f.requests.isEmpty())
        assertNotNull(f.pending.currentOrNull())
        f.set("positionGuidancePaused", false)
        f.set("pendingExplicitRouteStartAfterFixMs", f.fix.elapsedRealtimeMs)
        f.continueWithFreshFix()
        assertTrue(f.requests.isEmpty())
        f.set("pendingExplicitRouteStartAfterFixMs", f.fix.elapsedRealtimeMs - 1L)
        f.continueWithFreshFix()
        assertEquals(listOf(f.expectedRequest()), f.requests)

        val expired = Fixture()
        expired.seedPending(keepUntilCancelled = false)
        expired.now += 15_000L
        expired.continueWithFreshFix()
        assertTrue("An expired finite intent is not renewed by a location update", expired.requests.isEmpty())
    }

    @Test fun continuationRejectsChangedSessionDestinationOrForeground() {
        for (change in listOf("session", "destination", "foreground")) {
            val f = Fixture()
            f.seedPending()
            when (change) {
                "session" -> f.replaceSession()
                "destination" -> f.set("currentDestination", RoutePoint(38.0, 128.0))
                "foreground" -> f.set("isActivityForeground", false)
            }
            f.continueWithFreshFix()
            assertTrue(change, f.requests.isEmpty())
            assertNull(change, f.pending.currentOrNull())
        }
    }

    @Test fun cancelledTransportExpiredAuthorityAndReplacedSelectionNeverRetainTheOldStart() {
        for (change in listOf("cancelled", "lease", "selection", "purge")) {
            val f = Fixture()
            val call = f.admitRequest()
            when (change) {
                "cancelled" -> call.cancel()
                "lease" -> f.now = 100_001L
                "selection" -> f.set("pendingUiDestination",
                    (f.field("pendingUiDestination") as DestinationSearchResult).copy())
                "purge" -> f.set("routeSnapshotPreparationPending", true)
            }
            f.main.completeRouteRequest(call)
            assertTrue(change, f.finish(completedResponse = true))
            assertNull(change, f.pending.currentOrNull())
            assertFalse(change, f.navigator.hasRoute())
        }
    }

    @Test fun retainedRecoveryCannotSurviveAStopOrNewRequestGeneration() {
        val f = Fixture()
        val call = f.admitRequest()
        f.main.completeRouteRequest(call)
        assertTrue(f.finish(completedResponse = true))
        assertNotNull(f.pending.currentOrNull())
        f.main.invalidateActiveRouteRequestLease()
        f.publishRecoveredEnvironment()
        f.continueWithFreshFix()
        assertTrue(f.requests.isEmpty())
        assertNull(f.pending.currentOrNull())
    }

    @Test fun onlyOneTerminalPreservesTheOriginalStartAndFreshFixCutoff() {
        val f = Fixture()
        val call = f.admitRequest()
        f.set("latestRawRouteLocation", f.fix)
        f.main.completeRouteRequest(call)
        assertTrue(f.finish(completedResponse = true, rejectedAfterMs = f.fix.elapsedRealtimeMs))
        val retained = requireNotNull(f.pending.currentOrNull())
        assertEquals(1_100L, retained.requestedAtElapsedRealtimeMs)
        assertTrue(retained.keepUntilCancelled)
        assertFalse(f.finish(completedResponse = true))
        assertSame(retained, f.pending.currentOrNull())
        f.publishRecoveredEnvironment()
        f.continueWithFreshFix()
        assertTrue("The already observed fix cannot replay the rejected request", f.requests.isEmpty())
        f.now += 100L
        f.main.continueAdmittedExplicitRouteStartIfReady(elapsedRealtimeMs = { f.now },
            trustedLocation = { f.fix.copy(elapsedRealtimeMs = 2_050L) },
            requestNavigation = { destination, reason, retain, evidence ->
                f.requests += Request(destination, reason, retain, evidence)
            })
        assertEquals(listOf(f.expectedRequest()), f.requests)
    }

    @Test fun recoveryBeforeThePostedTerminalUsesTheAlreadyFreshFixOnce() {
        val f = Fixture()
        val call = f.admitRequest()
        call.execute()
        assertFalse(f.boolean("navigationEnvironmentOutputsAllowed"))
        val rejectedAfterMs = 1_000L
        f.main.completeRouteRequest(call)
        // The worker observed rejection first; a new admitted fix arrived before UI retirement.
        f.publishRecoveredEnvironment()
        f.set("latestTrustedLocation", f.fix)
        f.set("latestRawRouteLocation", f.fix)
        assertTrue(f.finish(completedResponse = true, rejectedAfterMs = rejectedAfterMs))
        f.continueWithFreshFix()
        f.continueWithFreshFix()
        assertEquals(listOf(f.expectedRequest()), f.requests)
        assertFalse(f.navigator.hasRoute())
    }

    @Test fun successfulResponseWithoutAnObservedEnvironmentRejectionCannotRetainAnIntent() {
        val f = Fixture()
        val call = f.admitRequest()
        f.main.completeRouteRequest(call)
        assertTrue(f.finish(completedResponse = true, rejectedAfterMs = null))
        assertNull(f.pending.currentOrNull())
    }

    @Test fun skippedArchiveAdmissionCannotBecomeAStorageFailureAfterGpsRecovers() {
        val f = Fixture()
        val call = f.admitRequest()
        val route = call.execute()
        f.main.completeRouteRequest(call)
        val queue = ArrayDeque<Runnable>()
        val lane = RouteSnapshotIoLane(Executor { queue.addLast(it) })
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
        var envelope: String? = null
        var archiveWrites = 0
        val storage = object : RouteSnapshotStorage {
            override fun read() = envelope
            override fun clear(): Boolean { envelope = null; return true }
            override fun write(value: String): Boolean { archiveWrites++; envelope = value; return true }
        }
        val keys = object : LocalAeadKeyProvider {
            private val values = mutableMapOf<String, SecretKey>()
            override fun getOrCreate(alias: String): SecretKey = values.getOrPut(alias) { fresh() }
            override fun getExisting(alias: String): SecretKey? = values[alias]
            override fun delete(alias: String): Boolean { values.remove(alias); return true }
            override fun createFreshAfterVerifiedPurge(alias: String): SecretKey = fresh().also { values[alias] = it }
            private fun fresh() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
        val (lease, attached) = lane.attach {
            EncryptedRouteSnapshotStore(storage,
                VersionedLocalAead(AeadKeyPolicy("test.initial.route.v", 1, setOf(1)), keys),
                testOnly = Unit, elapsedRealtimeMs = { f.now }, bootCount = { 7 },
                processCoordinator = RouteSnapshotProcessCoordinator())
        }
        drain()
        assertTrue(attached.get()?.purged == true)
        val owner = RouteSnapshotOwner(f.session.actorId, "account:1")
        val scope = RouteSnapshotScope(owner, f.epoch.walkSessionId)
        val ownership = f.main.submitRouteSnapshotOwnershipStorage(lane, lease, owner, scope, null,
            { lane.isCurrent(lease, lease.operation.get()) })
        drain()
        assertTrue(ownership.get()?.ready == true)
        var outputsAllowed = false
        val rejection = AtomicReference<Long?>(null)
        fun responseCurrent() = f.main.routeResponseInstallLeaseCurrent(7) {
            f.ownsRequest() && f.main.routeResponseEnvironmentCurrent(true, rejection, 1_000L, outputsAllowed)
        }
        val saved = f.main.submitRouteSnapshotResponseStorage(lane, lease, ::responseCurrent,
            scope, route, f.destination, f.evidence)
        drain()
        assertNull("The real Lane rejected admission without running archive save", saved.get())
        assertEquals(0, archiveWrites)
        assertEquals(1_000L, rejection.get())
        assertFalse(f.navigator.hasRoute())
        outputsAllowed = true
        f.publishRecoveredEnvironment()
        assertFalse("The rejected response must stay rejected before null can be treated as a storage failure",
            responseCurrent())
        assertTrue(f.finish(completedResponse = true, rejectedAfterMs = rejection.get()))
        f.continueWithFreshFix()
        f.continueWithFreshFix()
        assertEquals(listOf(f.expectedRequest()), f.requests)
        assertFalse(f.navigator.hasRoute())
        lane.detach(lease)
        drain()
    }

    @Test fun anInstalledRoutesTemporaryRestrictionDoesNotPermanentlyRetireItsAnnouncement() {
        val f = Fixture()
        val call = f.admitRequest()
        val route = call.execute()
        f.main.completeRouteRequest(call)
        f.navigator.setRoute(route, destination = f.destination)
        f.inFlight.set(false)
        val rejection = AtomicReference<Long?>(null)
        assertFalse(f.main.routeResponseEnvironmentCurrent(true, rejection, 1_000L, outputsAllowed = false))
        assertNull("Installed route announcements keep only the temporary output restriction", rejection.get())
        assertTrue(f.main.routeResponseEnvironmentCurrent(true, rejection, 1_000L, outputsAllowed = true))
        assertTrue(f.navigator.hasRoute())
    }

    @Test fun rejectedResponseCannotReenterAfterItsReservationEndsOrANewRequestStarts() {
        val f = Fixture()
        f.admitRequest()
        val rejection = AtomicReference<Long?>(null)
        assertFalse(f.main.routeResponseEnvironmentCurrent(true, rejection, 1_000L, outputsAllowed = false))
        f.inFlight.set(false)
        assertFalse(f.main.routeResponseEnvironmentCurrent(true, rejection, 1_000L, outputsAllowed = true))
        f.set("routeRequestGeneration", 8)
        f.inFlight.set(true)
        assertFalse(f.main.routeResponseEnvironmentCurrent(true, rejection, 1_000L, outputsAllowed = true))
        assertEquals(1_000L, rejection.get())
    }

    @Test fun actualNativeStartAndAcceptedLocationUseTheExplicitRequestAndGuardedContinuation() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val start = source.substringAfter("private fun startNativeDestinationGuidance()")
            .substringBefore("private fun beginGuidancePreparationSpeech")
        assertTrue(start.contains("onDestinationSelected("))
        assertTrue(start.contains("retainExplicitStartUntilTrustedLocation = true"))
        val core = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteAcquisitionController.kt").readText()
        val composition = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        val acquisition = MainActivityRefactorSource.composition(source, "routeAcquisitionController")
        assertTrue(ReportStaticSourceInspector.blockAfter(composition, "facts = { object : AcquisitionActivityFacts").contains("override val actorId get() = currentReporterUserId()"))
        assertTrue(ReportStaticSourceInspector.blockAfter(composition, "facts = { object : AcquisitionActivityFacts").contains("override val navigationAllowed get() = currentNavigationCollectionAllowsWork()"))
        assertTrue(ReportStaticSourceInspector.blockAfter(composition, "facts = { object : AcquisitionActivityFacts").contains("override val runtimeActive get() = isWalkSessionRuntimeActive()"))
        assertTrue(ReportStaticSourceInspector.blockAfter(composition, "facts = { object : AcquisitionActivityFacts").contains("override val environmentOutputsAllowed get() = navigationEnvironmentOutputsAllowed()"))
        assertTrue(acquisition.contains("override val currentLocation get() = positioning.value.currentRouteLocationOrNull()"))
        assertTrue(composition.contains("elapsedRealtimeMs = SystemClock::elapsedRealtime"))
        assertTrue(MainActivityRefactorSource.member(source, "onDestinationSelected")
            .contains("routeAcquisitionController.onDestinationSelected(result, retainExplicitStartUntilTrustedLocation)"))
        val selection = MainActivityRefactorSource.member(core, "onDestinationSelected")
        assertTrue(selection.contains("reason = \"user_destination\""))
        assertTrue(selection.contains("retainExplicitStartUntilTrustedLocation =\n                retainExplicitStartUntilTrustedLocation"))
        assertTrue(MainActivityRefactorSource.member(source, "continuePendingExplicitRouteStartIfReady")
            .contains("routeAcquisitionController.continuePendingExplicitRouteStartIfReady()"))
        val continuation = MainActivityRefactorSource.member(core, "continuePendingExplicitRouteStartIfReady")
        assertTrue(continuation.indexOf("!facts.navigationAllowed") <
            continuation.indexOf("continueAdmittedExplicitRouteStartIfReady()"))
        val admission = MainActivityRefactorSource.member(source, "continueAdmittedExplicitRouteStartIfReady")
        assertTrue(admission.contains("= SystemClock::elapsedRealtime"))
        assertTrue(admission.contains("= ::currentRouteLocationOrNull"))
        assertTrue(admission.contains("= ::requestRoute"))
        assertTrue(admission.contains("routeAcquisitionController.continueAdmittedExplicitRouteStartIfReady(elapsedRealtimeMs, trustedLocation, requestNavigation)"))
        val location = NavigationPolicySource.observation(source)
        assertTrue(location.contains("continuePendingExplicitRouteStartIfReady()"))
        assertTrue(MainActivityRefactorSource.member(source, "requestRoute", occurrence = 1)
            .contains("routeAcquisitionController.requestRoute(destination, reason,"))
        val request = MainActivityRefactorSource.member(core, "requestRoute", occurrence = 1)
        assertTrue(request.contains("initialExplicitStart = if (retainExplicitStartUntilTrustedLocation &&"))
        assertTrue(request.contains("!preserveExistingRoute && reason == \"user_destination\""))
        val completed = request.substringAfter("effects.recordStage(\"ROUTE_HTTP_COMPLETE\")")
            .substringBefore("fun rejectDisconnectedResponse")
        assertTrue(completed.contains("finishCurrentRouteRequest(completedResponse = true)"))
        assertTrue(completed.contains("routeResponseInstallLeaseCurrent(requestId, ::isCurrentRouteRequest)"))
        assertTrue(request.contains("successfulResponseReceived.set(true)"))
        val predicate = request.substringAfter("fun isCurrentRouteRequest(): Boolean")
            .substringBefore("fun finishCurrentRouteRequest")
        assertTrue(predicate.indexOf("facts.actorId == gatewaySession.actorId") <
            predicate.indexOf("routeResponseEnvironmentCurrent("))
        assertTrue(predicate.contains("routeResponseEnvironmentCurrent(successfulResponseReceived.get(),"))
        assertTrue(predicate.contains("outputsAllowed = !facts.runtimeActive || facts.environmentOutputsAllowed"))
        val terminal = request.substringAfter("fun finishCurrentRouteRequest")
            .substringBefore("presentation.navigationStatus(\"navigation=route_requesting")
        assertTrue(terminal.indexOf("finishRouteRequestIfCurrent(") <
            terminal.indexOf("continuePendingExplicitRouteStartIfReady()"))
        assertTrue(terminal.contains("pendingExplicitRouteStart.currentOrNull() != null && facts.navigationAllowed"))

    }

    private data class Request(val destination: RoutePoint, val reason: String,
        val retainExplicitStart: Boolean, val evidence: DestinationCoordinateEvidence?)

    private class Fixture {
        val main = MainActivity()
        var now = 2_000L
        val lifecycle = WalkSessionLifecycle(sessionIdFactory = { "initial-route-recovery" },
            clock = { now }, elapsedRealtimeClock = { now })
        val epoch: WalkRuntimeEpoch
        val session: GatewayFieldSession
        val generation: Long
        val destination = RoutePoint(37.0009, 127.0, "목적지")
        val evidence = DestinationCoordinateEvidence(DestinationCoordinateKind.NOOR,
            listOf(DestinationCoordinateCandidate(DestinationCoordinateKind.NOOR, destination)))
        val fix = TrustedLocation(37.0, 127.0, 5f, 1_900L)
        val inFlight get() = field("routeRequestInFlight") as AtomicBoolean
        val transport get() = field("navigationRequests") as AndroidNavigationRequestCoordinator
        val navigator get() = field("routeNavigator") as RouteNavigator
        @Suppress("UNCHECKED_CAST")
        val pending get() = field("pendingExplicitRouteStart") as PendingExplicitRouteStart<kr.co.hanium.dreamup.walksafe.navigation.session.RouteAcquisitionController.ExplicitRouteStartContext>
        val requests = mutableListOf<Request>()

        init {
            GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
            val actor = "actor_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            val onboarding = FirstRunOnboardingPolicy.initialEmailAccount().copy(
                stage = FirstRunOnboardingStage.COMPLETE, ageBand = FirstRunAgeBand.VERIFIED_14_PLUS,
                completedReceiptHashes = listOf(FirstRunOnboardingStage.VERIFIED_LOGIN,
                    FirstRunOnboardingStage.PURPOSE_AND_SAFETY, FirstRunOnboardingStage.FP004_TRAINING)
                    .associateWith { FirstRunReceiptHash.fromSha256Hex("a".repeat(64)) },
                verifiedActorBinding = FirstRunOpaqueActorBinding.fromProvider(actor))
            session = GatewayFieldSession.verified("https://gateway.example.test", actor,
                "walksafe_field_session=v2.initial-recovery", Long.MAX_VALUE)
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, onboarding))
            generation = GatewaySessionProcessCoordinator.snapshot().generation
            set("reporterUserId", actor)
            set("firstRunOnboardingSnapshot", onboarding)
            set("postLoginDeviceCheckSnapshot", PostLoginDeviceCheckSnapshot(PostLoginDeviceCheckState.FULL,
                actor, generation, now))
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            val readiness = WalkSessionReadinessCollector(lifecycle.snapshot().epoch,
                WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL))
            readiness.plan.requiredRequirements.forEach {
                readiness.record(WalkSessionReadinessObservation(lifecycle.snapshot().epoch, it,
                    WalkSessionReadinessStatus.READY))
            }
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readiness.build(now)))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            epoch = lifecycle.snapshot().epoch
            val authority = GatewayWalkAuthorityController(elapsedClock = { now })
            val start = requireNotNull(authority.beginStart(epoch))
            assertEquals(GatewayWalkAuthorityCompletion.LEASE_ACTIVE,
                authority.completeStart(start, GatewayWalkStartResult.Granted(GatewayWalkLease(
                    "GRANTED", actor, "fixture-device", epoch.walkSessionId, "fixture-lease", 1L,
                    now, 100_000L, now, 100_000L))))
            set("walkSessionLifecycle", lifecycle)
            set("gatewayWalkAuthorityController", authority)
            set("isActivityForeground", true)
            set("officialEnvironmentOutputsAllowed", false)
            set("routeSnapshotPreparationPending", false)
            set("routeRequestGeneration", 7)
            set("currentDestination", destination)
            set("pendingUiDestination", DestinationSearchResult("destination", "목적지", destination,
                null, null, null, null, coordinateEvidence = evidence))
            val page = Class.forName("kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController\$NativeUiPage")
                .enumConstants.first { (it as Enum<*>).name == "GUIDANCE" }
            set("nativeUiPage", page)
            // Controlled Android permission leaf in this already admitted session fixture.
            val acquisition = MainActivityControllerTestFixture.controller(main, "routeAcquisitionController") as kr.co.hanium.dreamup.walksafe.navigation.session.RouteAcquisitionController
            val factsField = acquisition.javaClass.getDeclaredField("facts").apply { isAccessible = true }
            val originalFacts = factsField.get(acquisition) as kr.co.hanium.dreamup.walksafe.navigation.session.RouteAcquisitionController.Facts
            factsField.set(acquisition, object : kr.co.hanium.dreamup.walksafe.navigation.session.RouteAcquisitionController.Facts by originalFacts {
                override val locationPermissionReady get() = true
                // Profile publication is outside this fixture; actor and gateway identity stay current.
                override val actorId get() = originalFacts.rawActorId?.takeIf { it == originalFacts.gateway.session?.actorId }
                override fun sessionCurrent(session: GatewayFieldSession) = originalFacts.gateway.session === session &&
                    session.isUsableFor(originalFacts.rawActorId)
            })
            val deletion = main.javaClass.getDeclaredField("accountDeletionProcessCoordinator").apply { isAccessible = true }.get(main) as AccountDeletionProcessCoordinator
            set("accountDeletionActivityLease", deletion.attach())
        }

        fun admitRequest(explicit: Boolean = true): CancellableNetworkCall<WalkingRoute> {
            val response = WalkingRoute("STAIR_AVOID", WalkingRouteSummary(100, 90),
                listOf(RoutePoint(37.0, 127.0), destination), emptyList())
            val call = main.trackRouteRequest(CancellableNetworkCall(executeBlock = { response }))
            set("isRouteActive", true)
            inFlight.set(true)
            set("activeRouteRequestOwner", kr.co.hanium.dreamup.walksafe.navigation.session.RouteAcquisitionController.PendingRouteRequestOwner(epoch, 7, generation,
                session, false, "user_destination", call,
                if (explicit) kr.co.hanium.dreamup.walksafe.navigation.session.RouteAcquisitionController.InitialExplicitRouteStart(destination, evidence, 1_000L,
                    field("pendingUiDestination") as DestinationSearchResult, 1_100L) else null))
            return call
        }

        fun seedPending(keepUntilCancelled: Boolean = true) {
            pending.replace(kr.co.hanium.dreamup.walksafe.navigation.session.RouteAcquisitionController.ExplicitRouteStartContext(epoch, generation, destination,
                evidence, true, session, field("pendingUiDestination") as DestinationSearchResult), now, keepUntilCancelled)
        }

        fun publishRecoveredEnvironment() {
            val profile = ApprovedOfficialEnvironmentProfile("initial-recovery", "camera", 15.0, 10_000L, 2)
            val guard = OfficialEnvironmentRuntimeGuard(epoch, profile)
            guard.onAssessment(OfficialEnvironmentAssessment(epoch, now, profile.profileId,
                OfficialEnvironmentFactor.entries.associateWith { EnvironmentEvidenceStatus.PASS }, true), now)
            assertTrue(guard.decision().navigationOutputsAllowed)
            set("officialEnvironmentRuntimeGuard", guard)
            set("officialEnvironmentOutputsAllowed", true)
            assertEquals(true, field("officialEnvironmentOutputsAllowed"))
        }

        fun continueWithFreshFix() {
            main.continueAdmittedExplicitRouteStartIfReady(elapsedRealtimeMs = { now },
                trustedLocation = { fix }, requestNavigation = { destination, reason, retain, evidence ->
                    requests += Request(destination, reason, retain, evidence)
                })
        }

        fun expectedRequest() = Request(destination, "user_destination", true, evidence)
        fun finish(completedResponse: Boolean, rejectedAfterMs: Long? = 1_000L) = main.finishRouteRequestIfCurrent(epoch, 7, generation,
            session, false, "user_destination", completedResponse, rejectedAfterMs)
        fun ownsRequest(): Boolean = method("isRouteRequestLeaseCurrent", java.lang.Boolean.TYPE,
            WalkRuntimeEpoch::class.java, Integer.TYPE).invokeWithArguments(main, epoch, 7) as Boolean
        fun replaceSession() {
            val replacement = GatewayFieldSession.verified("https://gateway.example.test", session.actorId,
                "walksafe_field_session=v2.replaced", Long.MAX_VALUE)
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, replacement,
                field("firstRunOnboardingSnapshot") as FirstRunOnboardingSnapshot))
        }
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

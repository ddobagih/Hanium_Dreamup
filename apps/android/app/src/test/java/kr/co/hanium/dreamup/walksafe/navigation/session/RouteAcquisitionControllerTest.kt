package kr.co.hanium.dreamup.walksafe.navigation.session

import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kr.co.hanium.dreamup.walksafe.AccountDeletionResetCoordinator
import kr.co.hanium.dreamup.walksafe.device.WalkSessionDeviceResourceSnapshot
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.security.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.voice.composition.VoiceRoutePeerAdapter
import kr.co.hanium.dreamup.walksafe.voice.interaction.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Actual request/archive owners; Android admission and effects are explicit fixture boundaries. */
class RouteAcquisitionControllerTest {
    @After fun resetCapacity() = GatewayCapacityProcessState.resetForTests()

    @Test fun constructionDoesNotReadAdmissionOrStartTransportAndStorage() {
        val f = Fixture()
        assertEquals(0, f.factReads)
        assertEquals(0, f.transportCalls)
        assertTrue(f.worker.isEmpty())
        assertTrue(f.io.isEmpty())
        assertNull(f.archive.leaseOrNull)
        assertFalse(f.controller.requestPending)
    }

    @Test fun userDestinationCancelsCandidateTransportBeforePublishingSelectionAndRequestingRoute() {
        val f = Fixture()
        f.prepareArchive()
        val previous = RoutePoint(37.001, 127.001, "이전 목적지")
        f.run.selectDestination(previous)
        f.run.updateAcceptedRouteMatch(true)
        val search = f.network.trackDestinationSearch(CancellableNetworkCall(executeBlock = { "unused" }))
        f.events.clear(); f.buttonStates.clear()

        f.controller.requestUserDestination(f.destination)

        assertTrue(search.isCancelled())
        assertFalse(f.network.hasActiveDestinationSearch())
        assertEquals("search-cancel:$previous", f.events.first())
        assertEquals("button", f.events[1])
        assertTrue(f.events.indexOf("transport") > f.events.indexOf("button"))
        assertEquals(Triple(f.destination, false, false), f.buttonStates.first())
        assertEquals(f.destination, f.run.destination)
        assertTrue(f.controller.requestPending)
        assertEquals("user_destination", f.controller.requestReason)
        assertEquals(1, f.transportCalls)
    }

    @Test fun candidateSelectionUsesTheSamePendingDestinationStateBeforeGpsWait() {
        val f = Fixture()
        f.prepareArchive()
        f.location = null
        f.run.selectDestination(RoutePoint(37.001, 127.001))
        f.run.updateAcceptedRouteMatch(true)
        f.buttonStates.clear()

        f.controller.onDestinationSelected(f.selected)

        assertEquals(Triple(f.destination, false, false), f.buttonStates.first())
        assertEquals(f.destination, f.run.destination)
        assertFalse(f.run.active)
        assertFalse(f.run.onRoute)
        assertEquals(0, f.transportCalls)
    }

    @Test fun originalHumanGpsWaitSurvivesTimeAndConsumesExactlyOnceFromFreshLocation() {
        val f = Fixture()
        f.prepareArchive()
        f.location = null
        f.controller.requestRoute(f.destination, "user_destination", true)
        val pending = requireNotNull(f.controller.explicitStart)
        assertTrue(pending.keepUntilCancelled)
        assertEquals(f.now, pending.requestedAtElapsedRealtimeMs)
        assertEquals(0, f.transportCalls)
        f.now += 60_000L
        f.location = f.fix.copy(elapsedRealtimeMs = f.now)
        val emitted = mutableListOf<RoutePoint>()
        f.controller.continueAdmittedExplicitRouteStartIfReady(requestNavigation = { point, reason, retain, _ ->
            assertEquals("user_destination", reason)
            assertTrue(retain)
            emitted += point
        })
        f.controller.continueAdmittedExplicitRouteStartIfReady(requestNavigation = { _, _, _, _ -> fail("consumed twice") })
        assertEquals(listOf(f.destination), emitted)
        assertNull(f.controller.explicitStart)
        assertEquals(0, f.transportCalls)
    }

    @Test fun successfulResponseRejectedByEnvironmentRetainsOnlyItsOriginalHumanIntent() {
        val f = Fixture()
        f.prepareArchive()
        f.controller.requestRoute(f.destination, "user_destination", true)
        val id = f.controller.requestGeneration
        f.beforeResponse = { f.outputsAllowed = false }
        f.runWorker()
        assertTrue(f.controller.requestPending)
        f.runUi()
        assertFalse(f.controller.requestPending)
        assertFalse(f.run.navigator.hasRoute())
        val pending = requireNotNull(f.controller.explicitStart)
        assertEquals(2_000L, pending.requestedAtElapsedRealtimeMs)
        assertTrue(pending.keepUntilCancelled)
        assertEquals(id, f.controller.requestGeneration)
        f.outputsAllowed = true
        f.now = 2_500L
        f.location = f.fix.copy(elapsedRealtimeMs = 2_499L)
        var emitted = 0
        f.controller.continueAdmittedExplicitRouteStartIfReady(requestNavigation = { point, reason, retain, _ ->
            assertEquals(f.destination, point)
            assertEquals("user_destination", reason)
            assertTrue(retain)
            emitted += 1
        })
        f.controller.continueAdmittedExplicitRouteStartIfReady(requestNavigation = { _, _, _, _ -> emitted += 1 })
        assertEquals(1, emitted)
        assertFalse(f.run.navigator.hasRoute())
    }

    @Test fun cancellationAndQueuedOldWorkerCannotRetireTheReplacementRequest() {
        val f = Fixture()
        f.prepareArchive()
        f.controller.requestRoute(f.destination, "user_destination", true)
        val oldCall = f.calls.single()
        val oldId = f.controller.requestGeneration
        f.controller.cancelActiveRouteRequest(false)
        assertTrue(oldCall.isCancelled())
        f.controller.requestRoute(f.destination, "user_destination", true)
        assertTrue(f.controller.requestGeneration > oldId)
        val newId = f.controller.requestGeneration
        f.worker.removeAt(0).run()
        f.runUi()
        assertTrue(f.controller.requestPending)
        assertEquals(newId, f.controller.requestGeneration)
        assertFalse(f.calls.last().isCancelled())
        assertEquals(0, f.executedCalls)
    }

    @Test fun responseEnvironmentRejectionIsMonotonicButInstalledAnnouncementCanRecover() {
        val f = Fixture()
        f.prepareArchive()
        f.controller.requestRoute(f.destination, "user_destination", true)
        val rejection = AtomicReference<Long?>(null)
        assertFalse(f.controller.routeResponseEnvironmentCurrent(true, rejection, 1_900L, false))
        assertEquals(1_900L, rejection.get())
        assertFalse(f.controller.routeResponseEnvironmentCurrent(true, rejection, 1_900L, true))
        assertTrue(f.controller.finishRouteRequestIfCurrent(f.epoch, f.controller.requestGeneration,
            f.generation, f.session, false, "user_destination"))
        assertFalse(f.controller.routeResponseEnvironmentCurrent(true, rejection, 1_900L, true))
        val installed = AtomicReference<Long?>(null)
        assertFalse(f.controller.routeResponseEnvironmentCurrent(true, installed, 1_900L, false))
        assertNull(installed.get())
        assertTrue(f.controller.routeResponseEnvironmentCurrent(true, installed, 1_900L, true))
    }

    @Test fun mismatchedEpochSessionDestinationAndStopDoNotResumeAnOldExplicitWait() {
        listOf<(Fixture) -> Unit>(
            { it.lifecycle.handle(WalkSessionEvent.EnteredBackground) },
            { it.generation += 1L },
            { it.run.selectDestination(RoutePoint(38.0, 128.0)) },
            { it.controller.clearPendingExplicitRouteStart() },
        ).forEach { invalidate ->
            val f = Fixture()
            f.prepareArchive()
            f.location = null
            f.controller.requestRoute(f.destination, "user_destination", true)
            assertNotNull(f.controller.explicitStart)
            invalidate(f)
            f.location = f.fix.copy(elapsedRealtimeMs = f.now)
            f.controller.continueAdmittedExplicitRouteStartIfReady(requestNavigation = { _, _, _, _ -> fail("old human intent replayed") })
            assertNull(f.controller.explicitStart)
            assertEquals(0, f.transportCalls)
        }
    }

    @Test fun archiveInitializationAndReplacementKeepActualLaneOwnershipAndPreparation() {
        val f = Fixture()
        f.archive.initialize(f::store)
        assertTrue(f.archive.preparationPending)
        assertNotNull(f.archive.leaseOrNull)
        f.io.removeAt(0).run()
        assertTrue(f.archive.preparationPending)
        f.runUi()
        assertFalse(f.archive.preparationPending)
        assertFalse(f.archive.purgeFailed)
        val first = requireNotNull(f.archive.leaseOrNull)
        val oldOperation = first.operation.get()
        f.archive.initialize(f::store)
        assertFalse(f.archive.routeSnapshotActivityIsCurrent(first))
        assertFalse(f.lane.commit(first, oldOperation, { true }) { fail("old archive admitted") })
        f.runIo()
        f.runUi()
        assertNotSame(first, f.archive.leaseOrNull)
        f.archive.detach()
        assertFalse(f.archive.routeSnapshotActivityIsCurrent(requireNotNull(f.archive.leaseOrNull)))
    }

    @Test fun automaticArrivalClaimsExactEvidenceAndLeavesWalkTerminationToFinalSpeech() {
        val f = Fixture()
        val token = f.prepareArrival()
        assertNull(f.controller.completeDetectedArrival(token.copy(decisionRevision = token.decisionRevision + 1)))
        assertTrue(f.run.navigator.hasRoute())
        assertNull("A latched safety owner vetoes without clearing the route", f.controller.completeDetectedArrival(token) { false })
        assertTrue(f.run.navigator.hasRoute())
        val completed = requireNotNull(f.controller.completeDetectedArrival(token))
        assertTrue(completed.arrived)
        assertEquals("도착했습니다. 안내를 종료합니다", completed.instruction)
        assertFalse(f.run.navigator.hasRoute())
        assertFalse(f.run.active)
        assertFalse(f.controller.requestPending)
        assertNull(f.controller.explicitStart)
        assertNull(f.run.destination)
        assertEquals("Dispatch acceptance cannot end the walk", WalkSessionState.ACTIVE, f.lifecycle.snapshot().state)
        assertEquals(0, f.transportCalls)
        assertNull("A duplicated candidate cannot own another ending", f.controller.completeDetectedArrival(token))
    }

    @Test fun automaticArrivalCannotClaimAnotherActorOrGatewayOrReentrantDestination() {
        for (change in listOf<(Fixture) -> Unit>({ it.actor = "replacement" }, { it.generation++ })) {
            val f = Fixture(); val token = f.prepareArrival(); change(f)
            assertNull(f.controller.completeDetectedArrival(token))
            assertTrue(f.run.navigator.hasRoute())
        }
        val f = Fixture(); val token = f.prepareArrival()
        f.onResetEffect = { f.onResetEffect = {}; f.controller.cancelActiveRouteRequest() }
        assertNull(f.controller.completeDetectedArrival(token))
    }

    @Test fun installedArrivalAllowsOnlyAVerifiedCredentialContinuationOfTheSameWalk() {
        fun credential(f: Fixture, rotation: Long, family: String = "arrival-family", actor: String = f.actor) =
            GatewayFieldSession.longLivedSession("https://gateway.example.test", actor,
                "arrival-device", family, rotation, "walksafe_field_session=v2.arrival-$rotation",
                "arrival-refresh-$rotation", Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)
        val f = Fixture(); f.session = credential(f, 1L)
        val token = f.prepareArrival()
        f.generation++ // beginGeneralOperation still has the old credential, and grants nothing.
        assertFalse(f.controller.canCompleteDetectedArrival(token))
        assertTrue(f.run.navigator.hasRoute())
        f.session = credential(f, 2L); f.generation++
        assertTrue(f.controller.canCompleteDetectedArrival(token))
        assertTrue(requireNotNull(f.controller.completeDetectedArrival(token)).arrived)

        for (change in listOf<(Fixture) -> Unit>(
            { it.session = credential(it, 2L, family = "replacement-family"); it.generation++ },
            { it.actor = "actor_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"; it.session = credential(it, 2L); it.generation++ },
            { it.lifecycle.handle(WalkSessionEvent.NewWalkRequested) },
        )) {
            val other = Fixture(); other.session = credential(other, 1L)
            val old = other.prepareArrival(); change(other)
            assertNull(other.controller.completeDetectedArrival(old))
            assertTrue(other.run.navigator.hasRoute())
        }
    }

    @Test fun committedArrivalResetFaultsRemainObservableBeforeAndAfterTheRequestGenerationChanges() {
        for (afterGeneration in listOf(false, true)) {
            val f = Fixture(); val token = f.prepareArrival()
            val failure = IllegalStateException("owned reset fault")
            if (afterGeneration) f.onResetTail = { throw failure } else f.onResetEffect = { throw failure }
            var retired = false
            try {
                f.controller.completeDetectedArrival(token) { retired = true; true }
                fail("A committed reset failure must reach the arrival cleanup owner")
            } catch (actual: IllegalStateException) {
                assertSame(failure, actual)
            }
            assertTrue(retired)
            assertFalse("The valid automatic arrival was already committed", f.run.navigator.hasRoute())
            assertEquals("No unrelated lifecycle transition may be invented by acquisition", WalkSessionState.ACTIVE,
                f.lifecycle.snapshot().state)
            assertFalse(f.controller.canCompleteDetectedArrival(token))
        }
    }

    @Test fun committedArrivalResetMayFinishAcrossPendingRollbackAndVerifiedRenewalOnly() {
        fun credential(f: Fixture, rotation: Long) = GatewayFieldSession.longLivedSession(
            "https://gateway.example.test", f.actor, "arrival-device", "arrival-family", rotation,
            "walksafe_field_session=v2.arrival-$rotation", "arrival-refresh-$rotation",
            Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)
        for (renewal in listOf("pending", "rollback", "renewed")) {
            val f = Fixture(); f.session = credential(f, 1L)
            val token = f.prepareArrival()
            val original = f.session
            var retired = false
            f.onResetEffect = {
                f.onResetEffect = {}
                assertTrue("Renewal is injected after the authoritative claim", retired)
                f.generation++
                f.inFlightOperationId = "owned-renewal"
                if (renewal != "pending") {
                    f.generation++
                    f.inFlightOperationId = null
                    if (renewal == "renewed") f.session = credential(f, 2L)
                }
            }
            val result = f.controller.completeDetectedArrival(token) { retired = true; true }
            assertTrue(renewal, requireNotNull(result).arrived)
            if (renewal != "renewed") assertSame(original, f.session)
            assertFalse(f.run.navigator.hasRoute())
            assertFalse(f.run.active)
            assertEquals("Only final speech may end the walk", WalkSessionState.ACTIVE, f.lifecycle.snapshot().state)
        }
        for (replace in listOf<(Fixture) -> Unit>(
            { it.actor = "actor_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" },
            { it.lifecycle.handle(WalkSessionEvent.EnteredBackground) },
            { it.lifecycle.handle(WalkSessionEvent.NewWalkRequested) },
            { it.selected = it.selected.copy(point = RoutePoint(38.0, 128.0)) },
            { it.controller.cancelActiveRouteRequest() },
        )) {
            val f = Fixture(); val token = f.prepareArrival()
            f.onResetEffect = { f.onResetEffect = {}; replace(f) }
            assertNull("A committed arrival never adopts a replacement scope",
                f.controller.completeDetectedArrival(token) { true })
        }
    }

    @Test fun committedArrivalEndingUsesOneSnapshotAcrossAnotherGatewayPublish() {
        for (replaceCredential in listOf(false, true)) {
            val f = Fixture()
            fun credential(rotation: Long) = GatewayFieldSession.longLivedSession(
                "https://gateway.example.test", f.actor, "arrival-device", "arrival-family", rotation,
                "walksafe_field_session=v2.arrival-$rotation", "arrival-refresh-$rotation",
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)
            f.session = credential(1L)
            val token = f.prepareArrival()
            var gatewayReads = 0
            f.onDestinationSearchUpdated = {
                f.onDestinationSearchUpdated = {}
                f.gatewayReadOverride = {
                    gatewayReads++
                    val snapshot = GatewaySessionProcessSnapshot(f.session, null, f.generation,
                        false, "owned-renewal", false)
                    f.generation++
                    if (replaceCredential) f.session = credential(2L)
                    snapshot
                }
            }
            assertTrue(requireNotNull(f.controller.completeDetectedArrival(token) { true }).arrived)
            assertEquals("Ending must not reacquire global gateway/session authority", 1, gatewayReads)
            assertFalse(f.run.navigator.hasRoute())
            assertEquals(WalkSessionState.ACTIVE, f.lifecycle.snapshot().state)
        }
    }

    @Test fun completedArrivalCannotSurviveChangedOwnerDestinationPauseEndOrExplicitCancellation() {
        val retirements = listOf<Pair<String, (Fixture) -> Unit>>(
            "actor" to { it.actor = "actor_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" },
            "gateway generation" to { it.generation++ },
            "gateway session" to { it.session = GatewayFieldSession.verified("https://gateway.example.test", it.session.actorId,
                "walksafe_field_session=v2.replacement", Long.MAX_VALUE) },
            "destination" to { it.selected = it.selected.copy(point = RoutePoint(38.0, 128.0)) },
            "background epoch" to { it.lifecycle.handle(WalkSessionEvent.EnteredBackground) },
            "end" to { it.lifecycle.handle(WalkSessionEvent.EndRequested) },
            "cancel" to { it.controller.cancelActiveRouteRequest() },
            "pause" to { it.controller.cancelNavigationRequestsForPause() },
            "destroy" to { it.controller.cancelNavigationRequestsForDestroy() },
            "account clear" to { it.controller.clearGatewayNavigationState() },
            "reset" to { it.controller.resetRouteState() },
            "resume" to { it.controller.completeAdmittedResume(it.epoch, {}) },
        )
        retirements.forEach { (name, retire) ->
            val f = Fixture()
            assertTrue(f.controller.confirmArrival(f.prepareArrival()).arrived)
            assertTrue(name, f.controller.hasCurrentConfirmedArrival)
            retire(f)
            assertFalse(name, f.controller.hasCurrentConfirmedArrival)
            assertNotEquals(name, DestinationGuidanceDisplayStage.ARRIVED, f.display().stage)
        }
    }

    @Test fun resetEffectReentryCannotPublishArrivalForAnInterruptedIntent() {
        val reentries = listOf<(Fixture) -> Unit>(
            { it.generation++ },
            { it.lifecycle.handle(WalkSessionEvent.EnteredBackground) },
            { it.controller.cancelActiveRouteRequest() },
            { it.controller.resetRouteState() },
            { it.controller.requestRoute(it.destination, "user_destination", true) },
        )
        reentries.forEach { reenter ->
            val f = Fixture()
            val token = f.prepareArrival()
            f.onResetEffect = { f.onResetEffect = {}; reenter(f) }
            assertTrue(f.controller.confirmArrival(token).arrived)
            assertFalse(f.controller.hasCurrentConfirmedArrival)
        }
    }

    private class Fixture {
        var now = 2_000L
        var factReads = 0
        var generation = 1L
        var inFlightOperationId: String? = null
        var actor = "actor_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        var onResetEffect: () -> Unit = {}
        var onResetTail: () -> Unit = {}
        var onDestinationSearchUpdated: () -> Unit = {}
        var gatewayReadOverride: (() -> GatewaySessionProcessSnapshot)? = null
        var outputsAllowed = true
        var beforeResponse: () -> Unit = {}
        var transportCalls = 0
        var executedCalls = 0
        val events = mutableListOf<String>()
        val buttonStates = mutableListOf<Triple<RoutePoint?, Boolean, Boolean>>()
        val destination = RoutePoint(37.0009, 127.0, "목적지")
        val fix = TrustedLocation(37.0, 127.0, 5f, 1_900L)
        var location: TrustedLocation? = fix
        var selected = DestinationSearchResult("destination", "목적지", destination, null, null, null, null)
        var session = GatewayFieldSession.verified("https://gateway.example.test", "actor_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "walksafe_field_session=v2.route-controller", Long.MAX_VALUE)
        val lifecycle = WalkSessionLifecycle({ "route-controller-walk" }, { now }, { now })
        val epoch: WalkRuntimeEpoch
        val run = NavigationRunState(RouteNavigator(nowElapsedRealtimeMs = { now }))
        val worker = mutableListOf<Runnable>()
        val io = mutableListOf<Runnable>()
        val ui = mutableListOf<Runnable>()
        val delayed = mutableListOf<Pair<Runnable, Long>>()
        val calls = mutableListOf<CancellableNetworkCall<WalkingRoute>>()
        val lane = RouteSnapshotIoLane(Executor { io += it })
        val network = AndroidNavigationRequestCoordinator()
        val deletion = AccountDeletionStateMachine()
        val deletionLease = deletion.attachActivity()
        val presentation = object : RoutePresentation {
            override fun status(title: String, detail: String) = Unit
            override fun navigationStatus(text: String) = Unit
            override fun speak(message: String) = Unit
            override fun speakStatus(message: String) = Unit
            override fun updateRouteButton() {
                events += "button"
                buttonStates += Triple(run.destination, run.active, run.onRoute)
            }
            override fun safetyStop(reason: String) { error("Unexpected safety stop: $reason") }
        }
        val scheduler = object : RouteCallbackScheduler {
            override fun post(task: Runnable) { ui += task }
            override fun postDelayed(task: Runnable, delayMs: Long) { delayed += task to delayMs }
            override fun remove(task: Runnable) { ui.remove(task); delayed.removeAll { it.first === task } }
        }
        fun gateway() = gatewayReadOverride?.invoke() ?:
            GatewaySessionProcessSnapshot(session, null, generation, false, inFlightOperationId, false)
        val archive = RouteArchiveController(lane, object : RouteArchiveController.Facts {
            override fun gatewaySnapshot() = gateway()
            override fun lifecycleOrNull() = lifecycle
            override fun activityCurrent() = true
            override fun deletionBlocked() = false
            override fun deletionCoordinator(): AccountDeletionResetCoordinator = error("Not an account reset fixture")
            override fun deletionActivityLease() = deletionLease
            override fun deletionIntentToken(): Any = error("Not an account reset fixture")
            override fun developmentBypass() = false
            override fun routeActive() = run.active
        }, presentation, scheduler, {}, { 100_000L })
        val controller: RouteAcquisitionController
        init {
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            val readiness = WalkSessionReadinessCollector(lifecycle.snapshot().epoch,
                WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL))
            readiness.plan.requiredRequirements.forEach { readiness.record(WalkSessionReadinessObservation(
                lifecycle.snapshot().epoch, it, WalkSessionReadinessStatus.READY)) }
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readiness.build(now)))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            epoch = lifecycle.snapshot().epoch
            controller = RouteAcquisitionController(run, archive, object : RouteAcquisitionController.Facts {
                override val foreground get() = true.also { factReads++ }
                override val navigationAllowed get() = outputsAllowed.also { factReads++ }
                override val environmentOutputsAllowed get() = outputsAllowed.also { factReads++ }
                override val runtimeActive get() = lifecycle.currentRuntimeEpochOrNull() != null
                override val lifecycle get() = this@Fixture.lifecycle
                override val gateway get() = gateway()
                override val rawActorId get() = actor
                override val actorId get() = actor
                override fun sessionCurrent(session: GatewayFieldSession) = session === this@Fixture.session
                override val resources get() = WalkSessionDeviceResourceSnapshot(true, true, true)
                override val developmentBypass = false
                override val locationPermissionReady = true
                override val selectedDestination get() = selected
                override val guidancePage = true
                override val currentLocation get() = location
                override val latestTrustedLocation get() = location
                override val latestRawLocation get() = location
                override val positionPaused = false
                override val startWaitViewReady = true
                override val stepCount = 0
                override val gpsSource: RouteAcquisitionController.RecoverySource? get() = null
                override val publishedGpsSourceKey: Any? get() = null
                override val permissionOwner get() = error("Storage response admission is not used in this fixture")
                override val deletionOwner get() = deletion
                override val deletionActivityLease get() = deletionLease
                override val activityDestroyed = false
            }, object : RouteAcquisitionController.Effects {
                override fun blockRouteMutation() = false
                override fun requireActor(reason: String) = true
                override fun networkAllowed(reason: String) = true
                override fun sessionOrNull(reason: String) = session
                override fun explainUnavailable() = Unit
                override fun requestLocationServices() = Unit
                override fun setRoutePermissionRequested(requested: Boolean) = Unit
                override fun invalidateStopConfirmation() = Unit
                override fun cancelNavigationRetry() = Unit
                override fun cancelLocationStatusSpeech() = onResetTail()
                override fun cancelNavigationSpeech() = Unit
                override fun cancelDecisionSpeech() = Unit
                override fun clearDeviationHaptic() = Unit
                override fun updateDeviationActions(decision: RouteNavigatorUserDecision?) = Unit
                override fun applyDeviationUpdate(update: RouteNavigatorUpdate) = false
                override fun markTactileInactive() = Unit
                override fun updateDestinationSearch() = onDestinationSearchUpdated()
                override fun clearNativeSearchAndShowHome() = Unit
                override fun renderMain() = Unit
                override fun resetStartSpeechState() = onResetEffect()
                override fun beginInstalledRoute(): () -> Unit = {}
                override fun recordInstalledRoute(route: WalkingRoute) = Unit
                override fun showDestinationCoordinates(destination: RoutePoint) = Unit
                override fun recordStage(stage: String) = Unit
                override fun bindCompassOwner() = Unit
                override fun updateGuidance(location: TrustedLocation, rawLocation: TrustedLocation) = Unit
                override fun requestFreshGps() = Unit
                override fun sessionNotReady(session: GatewayFieldSession) = Unit
                override fun failureAdmission(session: GatewayFieldSession, authenticationFailure: Boolean): (() -> Boolean) = { true }
            }, presentation, object : RouteAcquisitionController.Backend {
                override fun fetchRoute(session: GatewayFieldSession, request: WalkingRouteRequest): CancellableNetworkCall<WalkingRoute> {
                    transportCalls++
                    events += "transport"
                    assertEquals(RoutePoint(fix.latitude, fix.longitude, "현재 위치"), request.origin)
                    return CancellableNetworkCall(executeBlock = {
                        executedCalls++; beforeResponse()
                        WalkingRoute("STAIR_AVOID", WalkingRouteSummary(100, 90), listOf(request.origin, request.destination), emptyList())
                    }).also(calls::add)
                }
                override fun revalidate(session: GatewayFieldSession, actorId: String?, capacitySessionGeneration: Long) =
                    GatewaySessionRevalidation(GatewaySessionRevalidationStatus.READY, "fixture")
            }, network, emptySearch(), InstalledRouteStartSpeechCoordinator(), Executor { worker += it },
                { ui += Runnable(it) }, scheduler, scheduler, { now }, { 100_000L }, lane)
        }
        fun prepareArchive() { archive.initialize(::store); runIo(); runUi(); assertFalse(archive.preparationPending) }
        fun prepareArrival(): RouteNavigatorDecisionToken {
            prepareArchive()
            run.selectDestination(destination)
            run.markRequestActive()
            controller.installRequestedRoute(WalkingRoute("TEST", WalkingRouteSummary(100, 90),
                listOf(RoutePoint(fix.latitude, fix.longitude), destination), emptyList()), destination,
                fix, null, "user_destination")
            repeat(4) {
                run.navigator.update(TrustedLocation(destination.latitude, destination.longitude, 1f, now), now, false)
                if (it < 3) now += 1_000L
            }
            val token = requireNotNull(run.navigator.pendingDecisionToken())
            assertEquals(RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION, token.decision)
            return token
        }
        fun display() = DestinationGuidancePresentationPolicy.present(
            GuidanceFeatureDisplayInput(false, true, null),
            GuidanceFeatureDisplayInput(false, true, "합성 카메라 측정 진행"), true,
            arrivalConfirmed = controller.hasCurrentConfirmedArrival,
        )
        fun arrivalCommands(): VoiceCommandInteractionController {
            val route = VoiceRoutePeerAdapter({ controller }, { archive }, { error("No guidance mutation") },
                { error("No compass mutation") }, { error("No position mutation") }, { error("No start") },
                port { _, _ -> error("No admission change") }, port { _, _ -> Unit })
            return VoiceCommandInteractionController({ error("No search") }, { run },
                port { _, _ -> error("No walk transition") }, route,
                port { _, _ -> error("No identity mutation") },
                port { name, args -> if (name == "navigationStatus") events.add(args[0] as String) else Unit },
                port { _, _ -> error("No input") }, port { name, _ -> if (name == "speakInteraction") true else error(name) },
                port { _, _ -> error("No timer") }, dialog = { error("No dialog") })
        }
        private inline fun <reified T> port(crossinline call: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                call(method.name, args ?: emptyArray())
            } as T

        fun runWorker() { while (worker.isNotEmpty()) worker.removeAt(0).run() }
        fun runIo() { while (io.isNotEmpty()) io.removeAt(0).run() }
        fun runUi() { while (ui.isNotEmpty()) ui.removeAt(0).run() }
        fun store(): EncryptedRouteSnapshotStore = EncryptedRouteSnapshotStore(object : RouteSnapshotStorage {
            override fun read(): String? = null
            override fun write(envelope: String) = error("Archive write is outside this fixture")
            override fun clear() = true
        }, VersionedLocalAead(AeadKeyPolicy("test.acquisition.v", 1, setOf(1)), object : LocalAeadKeyProvider {
            override fun getOrCreate(alias: String): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            override fun getExisting(alias: String): SecretKey? = null
            override fun delete(alias: String) = true
            override fun createFreshAfterVerifiedPurge(alias: String) = getOrCreate(alias)
        }), elapsedRealtimeMs = { now }, processCoordinator = RouteSnapshotProcessCoordinator())

        private fun emptySearch(): DestinationSearchController = DestinationSearchController(
            object : DestinationSearchController.Facts {
                override fun searchAllowed() = false
                override fun rawQuery() = ""
                override fun epoch() = epoch
                override fun actorId() = session.actorId
                override fun gatewaySnapshot() = gateway()
                override fun isSessionCurrent(session: GatewayFieldSession) = false
                override fun isEpochAllowed(epoch: WalkRuntimeEpoch) = false
                override fun locationAccessAllowed() = false
                override fun locationSourceReady() = false
            }, object : DestinationSearchController.Actions {
                override fun explainUnavailable() = Unit
                override fun blockRouteMutation() = true
                override fun requireActor() = false
                override fun networkAllowed() = false
                override fun sessionOrNull(): GatewayFieldSession? = null
                override fun ensureLocationPermission() = false
                override fun render() = Unit
                override fun controls(controls: DestinationSearchController.Controls) = Unit
                override fun navigationStatus(text: String) {
                    if (text == "navigation=destination_search_cancelled") events += "search-cancel:${run.destination}"
                }
                override fun status(title: String, detail: String) = Unit
                override fun speak(message: String) = Unit
                override fun offerCandidates(state: DestinationSearchVoiceState, message: String) = Unit
                override fun sessionNotReady(session: GatewayFieldSession) = Unit
                override fun failureAdmission(session: GatewayFieldSession, error: Exception): (() -> Boolean)? = null
            }, object : DestinationSearchController.Backend {
                override fun searchCall(session: GatewayFieldSession, query: String, limit: Int, origin: RoutePoint?): CancellableNetworkCall<DestinationSearchResponse> = error("No search request")
                override fun revalidate(session: GatewayFieldSession, actorId: String?, capacitySessionGeneration: Long): GatewaySessionRevalidation = error("No search request")
            }, network, Executor { error("No search worker") }, { error("No search UI callback") }, { null })
    }
}

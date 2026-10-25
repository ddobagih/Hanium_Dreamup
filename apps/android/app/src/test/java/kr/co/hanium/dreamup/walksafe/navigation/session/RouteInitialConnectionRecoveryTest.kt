package kr.co.hanium.dreamup.walksafe.navigation.session

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
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Real acquisition/HTTP callback/archive readback, controlled Android admission and clocks. */
class RouteInitialConnectionRecoveryTest {
    @After fun resetCapacity() = GatewayCapacityProcessState.resetForTests()

    @Test fun firstRouteArchiveAndReadbackUseTheControllersSuppliedWallClock() {
        val f = Fixture()
        f.prepareArchive()
        f.controller.requestRoute(f.destination, "user_destination", true)
        f.runWorker(); f.runUi(); f.runIo(); f.runUi()
        assertTrue("The provider and storage ACK must install the route", f.run.navigator.hasRoute())
        val scope = requireNotNull(f.archive.walkScope)
        val reopened = f.store()
        assertTrue(reopened.bindAccount(scope.owner))
        assertTrue(reopened.beginWalk(scope))
        val saved = requireNotNull(reopened.load(scope, nowEpochMs = 100_000L))
        assertEquals(100_000L, saved.savedAtEpochMs)
        assertEquals(f.now, saved.savedAtElapsedRealtimeMs)
        assertEquals(100_000L + ROUTE_SNAPSHOT_TTL_MS, saved.expiresAtEpochMs)
    }

    @Test fun freshActiveReadinessCannotInvalidatePendingRouteStorageOrArrivalPurge() {
        val f = Fixture()
        f.prepareArchive()
        val scope = requireNotNull(f.archive.walkScope)
        val lease = requireNotNull(f.archive.leaseOrNull)
        f.controller.requestRoute(f.destination, "user_destination", true)
        f.runWorker(); f.runUi()
        assertFalse(f.run.navigator.hasRoute())
        val pendingStorageOperation = lease.operation.get()
        fun freshReadiness() {
            f.now += 100L
            val current = requireNotNull(f.lifecycle.snapshot().readiness)
            val transition = f.lifecycle.handle(WalkSessionEvent.RuntimeReadinessChanged(
                current.copy(assessedAtElapsedRealtimeMs = f.now)))
            assertTrue(transition.changed)
            f.archive.updateRouteSnapshotWalkOwnership(transition)
            assertEquals(scope, f.archive.walkScope)
        }
        repeat(3) { freshReadiness(); assertSame(pendingStorageOperation, lease.operation.get()) }
        f.runIo(); f.runUi()
        assertTrue("Live sensor readiness must not discard the delayed storage ACK", f.run.navigator.hasRoute())
        assertNotNull(f.stored)
        f.archive.purgeEncryptedRouteSnapshot()
        val purgeOperation = lease.operation.get()
        freshReadiness()
        assertSame("The same walk's refresh must retain its pending arrival purge", purgeOperation, lease.operation.get())
        f.runIo(); f.runUi()
        assertTrue("The actual purge must remove the encrypted route", f.stored == null)
    }

    @Test fun endAndSafetyStopStillRetireArchiveOwnershipImmediately() {
        for (event in listOf(WalkSessionEvent.EndRequested, WalkSessionEvent.SafetyStopRequested)) {
            val f = Fixture()
            f.prepareArchive()
            val lease = requireNotNull(f.archive.leaseOrNull)
            val oldOperation = lease.operation.get()
            val transition = f.lifecycle.handle(event)
            f.archive.updateRouteSnapshotWalkOwnership(transition)
            assertNotSame(oldOperation, lease.operation.get())
            assertNull(f.archive.walkScope)
            f.runIo(); f.runUi()
            assertNull(f.archive.walkScope)
        }
    }

    @Test fun freshActiveReadinessRetainsThePendingInitialOwnershipAck() {
        val f = Fixture()
        f.prepareArchive(acknowledgeOwnership = false)
        assertTrue(f.archive.preparationPending)
        assertNull(f.archive.walkScope)
        val lease = requireNotNull(f.archive.leaseOrNull)
        val pendingOwnership = lease.operation.get()
        repeat(3) {
            f.now += 100L
            val current = requireNotNull(f.lifecycle.snapshot().readiness)
            val transition = f.lifecycle.handle(WalkSessionEvent.RuntimeReadinessChanged(
                current.copy(assessedAtElapsedRealtimeMs = f.now)))
            f.archive.updateRouteSnapshotWalkOwnership(transition)
            assertSame("Live readiness must not replace the same pending ownership request",
                pendingOwnership, lease.operation.get())
        }
        f.runIo(); f.runUi()
        assertFalse(f.archive.preparationPending)
        assertNotNull(f.archive.walkScope)
    }

    @Test fun aNewGatewaySessionStillInvalidatesPendingOwnershipOnAnActiveReadinessRefresh() {
        val f = Fixture()
        f.prepareArchive(acknowledgeOwnership = false)
        val lease = requireNotNull(f.archive.leaseOrNull)
        val oldOperation = lease.operation.get()
        f.sessionOverridden = true
        f.currentSession = GatewayFieldSession.longLivedSession("https://gateway.example.test", f.session.actorId,
            "route-controller-device", "route-controller-family", 1L, "walksafe_field_session=v2.new-rotation",
            "new-route-controller-refresh", Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)
        f.generation++
        f.now += 100L
        val readiness = requireNotNull(f.lifecycle.snapshot().readiness)
        val transition = f.lifecycle.handle(WalkSessionEvent.RuntimeReadinessChanged(
            readiness.copy(assessedAtElapsedRealtimeMs = f.now)))
        f.archive.updateRouteSnapshotWalkOwnership(transition)
        assertNotSame("The readiness optimization must not retain an old Gateway operation", oldOperation, lease.operation.get())
        f.runIo(); f.runUi()
        assertFalse(f.archive.preparationPending)
        assertNotNull(f.archive.walkScope)
    }

    @Test fun expectedFirstConnectionMissRetainsTheSameExplicitStart() {
        val f = disconnected()
        assertFalse(f.run.navigator.hasRoute())
        assertNotNull("A valid provider route whose CURRENT connection changed once must retain the user's same start intent", f.controller.explicitStart)
        assertEquals(2_000L, f.controller.explicitStart!!.requestedAtElapsedRealtimeMs)
        assertTrue(f.spoken.isEmpty())
        assertTrue(f.freshRequests > 0)
    }

    @Test fun freshOriginalGpsAutomaticallyRequestsFromCurrentOriginThenArchiveAckInstallsAndStartsSpeech() {
        val f = disconnected()
        f.routeOffsetM = 0.0
        f.beforeResponse = {}
        f.now = 3_500L
        f.location = f.fix.copy(longitude = 127.0-lonForMeters(20.0), elapsedRealtimeMs = 3_400L)
        f.controller.continuePendingExplicitRouteStartIfReady()
        assertEquals(2, f.transportCalls)
        assertEquals(RoutePoint(f.location!!.latitude, f.location!!.longitude, "현재 위치"), f.requestedOrigins.last())
        f.runWorker(); f.runUi()
        assertFalse("No route before encrypted storage readback ACK", f.run.navigator.hasRoute())
        f.runIo(); f.runUi()
        assertTrue(f.run.navigator.hasRoute()); assertTrue(f.run.active)
        assertEquals(2, f.executedCalls); assertNull(f.controller.explicitStart)
        assertNotNull(f.stored); assertTrue(f.stages.contains("ROUTE_ARCHIVE_COMPLETE"))
        assertNotNull(f.startSpeech.pending)
        assertTrue(f.startSpeech.hold({ true }, { message, current, terminal ->
            assertTrue(current()); f.spoken += message; terminal(); true
        }, {}, false, { false }, {}, null))
        assertEquals(1, f.spoken.size); assertTrue(f.spoken.single().contains("목적지"))
    }

    @Test fun repeatedOldOrStaleOrFutureGpsCannotConsumeARecoveryRequest() {
        val f = disconnected(); val pending = f.controller.explicitStart
        repeat(5) { f.now += 100L; f.controller.continuePendingExplicitRouteStartIfReady() }
        assertSame(pending, f.controller.explicitStart); assertEquals(1, f.transportCalls)
        f.now = 20_000L; f.location = f.fix.copy(elapsedRealtimeMs = 3_000L)
        f.controller.continuePendingExplicitRouteStartIfReady()
        assertSame(pending, f.controller.explicitStart); assertEquals(1, f.transportCalls)
        f.location = f.fix.copy(elapsedRealtimeMs = 20_001L)
        f.controller.continuePendingExplicitRouteStartIfReady()
        assertSame(pending, f.controller.explicitStart); assertEquals(1, f.transportCalls)
    }

    @Test fun repeatedConnectionMissKeepsFirstIntentTimeAndNoticeDeadlineWithoutAudioLoop() {
        val f = disconnected(); val firstNotice = f.delayed.single { it.second == 3_000L }.first
        f.now = 3_500L; f.location = f.fix.copy(elapsedRealtimeMs = 3_400L)
        f.beforeResponse = { f.now = 4_000L; f.location = f.fix.copy(longitude = 127.0-lonForMeters(20.0), elapsedRealtimeMs = 3_900L) }
        f.controller.continuePendingExplicitRouteStartIfReady(); f.runWorker(); f.runUi()
        assertEquals(2_000L, f.controller.explicitStart!!.requestedAtElapsedRealtimeMs)
        assertSame(firstNotice, f.delayed.single { it.second == 3_000L }.first)
        f.now = 6_000L; firstNotice.run(); firstNotice.run()
        assertEquals(1, f.spokenStatus.size); assertTrue(f.spoken.isEmpty())
        assertNotNull(f.controller.explicitStart)
        assertEquals(2_000L, f.controller.explicitStart!!.requestedAtElapsedRealtimeMs)
    }

    @Test fun cancelPauseDestroyLogoutPermissionAndDestinationInvalidatePendingRecovery() {
        listOf<(Fixture) -> Unit>(
            { it.controller.cancelActiveRouteRequest(false) },
            { it.controller.cancelNavigationRequestsForPause() },
            { it.controller.cancelNavigationRequestsForDestroy() },
            { it.controller.clearGatewayNavigationState() },
            { it.permissionReady = false },
            { it.controller.cancelNavigationRequestsForDestinationSelection() },
            { it.run.selectDestination(RoutePoint(38.0, 128.0)) },
            { it.generation++ },
            { it.sessionOverridden = true; it.currentSession = GatewayFieldSession.longLivedSession("https://gateway.example.test", it.session.actorId, "route-controller-device", "route-controller-family", 1L, "walksafe_field_session=v2.route-controller", "route-controller-refresh", Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE) },
            { it.selectionOverridden = true; it.selectedCurrent = it.selected.copy() },
            { it.lifecycle.handle(WalkSessionEvent.EnteredBackground) },
            { it.lifecycle.handle(WalkSessionEvent.SafetyStopRequested) },
            { it.foreground = false },
        ).forEach { invalidate ->
            val f = disconnected(); invalidate(f)
            f.now = 3_500L; f.location = f.fix.copy(elapsedRealtimeMs = 3_400L)
            f.controller.continuePendingExplicitRouteStartIfReady(); f.runWorker(); f.runUi(); f.runIo(); f.runUi()
            assertNull(f.controller.explicitStart); assertFalse(f.run.navigator.hasRoute()); assertEquals(1, f.transportCalls)
        }
    }

    @Test fun cancelledRetryCannotInstallAfterItsProviderCallbackWasQueued() {
        val f = disconnected(); f.routeOffsetM = 0.0; f.beforeResponse = {}
        f.now = 3_500L; f.location = f.fix.copy(elapsedRealtimeMs = 3_400L)
        f.controller.continuePendingExplicitRouteStartIfReady(); f.runWorker()
        f.controller.cancelActiveRouteRequest(false)
        f.runUi(); f.runIo(); f.runUi()
        assertFalse(f.run.navigator.hasRoute()); assertNull(f.controller.explicitStart); assertNull(f.startSpeech.pending)
    }

    @Test fun badCurrentAccuracyAndProviderGeometryCannotAcquireConnectionRecovery() {
        listOf<(Fixture) -> Unit>(
            { it.beforeResponse = { it.now = 3_000L; it.location = it.fix.copy(longitude = 127.0-lonForMeters(20.0), accuracyM = 20f, elapsedRealtimeMs = 2_900L) } },
            { it.responseTransform = { route -> route.copy(polyline = listOf(RoutePoint(Double.NaN, 127.0), RoutePoint(37.1, 127.0))) } },
            { it.responseTransform = { route -> route.copy(polyline = emptyList()) } },
        ).forEach { corrupt ->
            val f = Fixture(); f.prepareArchive(); f.routeOffsetM = 90.0
            f.beforeResponse = { f.now = 3_000L; f.location = f.fix.copy(longitude = 127.0-lonForMeters(20.0), elapsedRealtimeMs = 2_900L) }
            corrupt(f); f.controller.requestRoute(f.destination, "user_destination", true)
            f.runWorker(); f.runUi()
            assertNull(f.controller.explicitStart); assertFalse(f.run.navigator.hasRoute()); assertEquals(0, f.freshRequests)
        }
    }

    @Test fun missingGpsDuringAnInitialResponseAlsoKeepsOriginalStartTime() {
        val f = Fixture(); f.prepareArchive(); f.routeOffsetM = 50.0
        f.controller.requestRoute(f.destination, "user_destination", true)
        f.beforeResponse = { f.now = 3_000L; f.location = null }
        f.runWorker(); f.runUi()
        assertEquals(2_000L, f.controller.explicitStart!!.requestedAtElapsedRealtimeMs)
        assertTrue(f.spoken.isEmpty())
    }

    private fun disconnected(): Fixture {
        val f = Fixture(); f.routeOffsetM = 90.0; f.prepareArchive()
        f.controller.requestRoute(f.destination, "user_destination", true)
        f.beforeResponse = { f.now = 3_000L; f.location = f.fix.copy(longitude = 127.0-lonForMeters(20.0), elapsedRealtimeMs = 2_900L) }
        f.runWorker(); f.runUi()
        return f
    }
    companion object { fun lonForMeters(m:Double)=Math.toDegrees(m/(6_371_000.0*kotlin.math.cos(Math.toRadians(37.0)))) }
    internal class Fixture(
        private val initialNow: Long = 2_000L,
        private val initialGeneration: Long = 1L,
        private val initialSession: GatewayFieldSession? = null,
        private val initialLifecycle: WalkSessionLifecycle? = null,
        private val initialDestination: DestinationSearchResult? = null,
    ) {
        var now = initialNow
        var factReads = 0
        var generation = initialGeneration
        var outputsAllowed = true
        var foreground = true
        var permissionReady = true
        var page = true
        var currentSession: GatewayFieldSession? = null
        var sessionOverridden = false
        var selectedCurrent: DestinationSearchResult? = null
        var selectionOverridden = false
        var responseTransform: (WalkingRoute) -> WalkingRoute = { it }
        var freshRequests = 0
        val spokenStatus = mutableListOf<String>()
        val startSpeech = InstalledRouteStartSpeechCoordinator()
        val requestedOrigins = mutableListOf<RoutePoint>()
        val requestedSessions = mutableListOf<GatewayFieldSession>()
        val requestedDestinations = mutableListOf<RoutePoint>()
        var beforeResponse: () -> Unit = {}
        var routeOffsetM = 0.0
        val spoken = mutableListOf<String>()
        val statuses = mutableListOf<String>()
        val stages = mutableListOf<String>()
        val permission = PermissionSessionPolicy()
        val keys = mutableMapOf<String, SecretKey>()
        var stored: String? = null
        var transportCalls = 0
        var executedCalls = 0
        val destination = initialDestination?.point ?: RoutePoint(37.0009, 127.0, "목적지")
        val fix = if (initialDestination == null) TrustedLocation(37.0, 127.0, 5f, 1_900L)
            else TrustedLocation(destination.latitude - 100.0 / 111_194.92664455874,
                destination.longitude, 5f, (initialNow - 100L).coerceAtLeast(0L))
        var location: TrustedLocation? = fix
        val selected = initialDestination ?: DestinationSearchResult("destination", "목적지", destination, null, null, null, null)
        val session = initialSession ?: GatewayFieldSession.longLivedSession("https://gateway.example.test", "actor_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "route-controller-device", "route-controller-family", 1L, "walksafe_field_session=v2.route-controller", "route-controller-refresh", Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)
        val lifecycle = initialLifecycle ?: WalkSessionLifecycle({ "route-controller-walk" }, { now }, { now })
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
            override fun navigationStatus(text: String) { statuses += text }
            override fun speak(message: String) { spoken += message }
            override fun speakStatus(message: String) { spokenStatus += message }
            override fun updateRouteButton() = Unit
            override fun safetyStop(reason: String) { error("Unexpected safety stop: $reason") }
        }
        val scheduler = object : RouteCallbackScheduler {
            override fun post(task: Runnable) { ui += task }
            override fun postDelayed(task: Runnable, delayMs: Long) { delayed += task to delayMs }
            override fun remove(task: Runnable) { ui.remove(task); delayed.removeAll { it.first === task } }
        }
        fun gateway() = GatewaySessionProcessSnapshot(if (sessionOverridden) currentSession else session, null, generation, false, null, false)
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
            if (initialLifecycle == null) {
                lifecycle.handle(WalkSessionEvent.EnteredForeground)
                val readiness = WalkSessionReadinessCollector(lifecycle.snapshot().epoch,
                    WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL))
                readiness.plan.requiredRequirements.forEach { readiness.record(WalkSessionReadinessObservation(
                    lifecycle.snapshot().epoch, it, WalkSessionReadinessStatus.READY)) }
                lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readiness.build(now)))
                lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            } else {
                check(lifecycle.currentRuntimeEpochOrNull() != null) { "A reused journey must already own an active walk" }
            }
            epoch = lifecycle.snapshot().epoch
            controller = RouteAcquisitionController(run, archive, object : RouteAcquisitionController.Facts {
                override val foreground get() = this@Fixture.foreground.also { factReads++ }
                override val navigationAllowed get() = outputsAllowed.also { factReads++ }
                override val environmentOutputsAllowed get() = outputsAllowed.also { factReads++ }
                override val runtimeActive get() = lifecycle.currentRuntimeEpochOrNull() != null
                override val lifecycle get() = this@Fixture.lifecycle
                override val gateway get() = gateway()
                override val rawActorId get() = session.actorId
                override val actorId get() = session.actorId
                override fun sessionCurrent(session: GatewayFieldSession) = session === this@Fixture.session
                override val resources get() = WalkSessionDeviceResourceSnapshot(true, true, true)
                override val developmentBypass = false
                override val locationPermissionReady get() = permissionReady
                override val selectedDestination get() = if (selectionOverridden) selectedCurrent else selected
                override val guidancePage get() = page
                override val currentLocation get() = location
                override val latestTrustedLocation get() = location
                override val latestRawLocation get() = location
                override val positionPaused = false
                override val startWaitViewReady = true
                override val stepCount = 0
                override val gpsSource: RouteAcquisitionController.RecoverySource? get() = null
                override val publishedGpsSourceKey: Any? get() = null
                override val permissionOwner get() = permission
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
                override fun cancelLocationStatusSpeech() = Unit
                override fun cancelNavigationSpeech() = Unit
                override fun cancelDecisionSpeech() = Unit
                override fun clearDeviationHaptic() = Unit
                override fun updateDeviationActions(decision: RouteNavigatorUserDecision?) = Unit
                override fun applyDeviationUpdate(update: RouteNavigatorUpdate) = false
                override fun markTactileInactive() = Unit
                override fun updateDestinationSearch() = Unit
                override fun clearNativeSearchAndShowHome() = Unit
                override fun renderMain() = Unit
                override fun resetStartSpeechState() = Unit
                override fun beginInstalledRoute(): () -> Unit = {}
                override fun recordInstalledRoute(route: WalkingRoute) = Unit
                override fun showDestinationCoordinates(destination: RoutePoint) = Unit
                override fun recordStage(stage: String) { stages += stage }
                override fun bindCompassOwner() = Unit
                override fun updateGuidance(location: TrustedLocation, rawLocation: TrustedLocation) = Unit
                override fun requestFreshGps() { freshRequests++ }
                override fun sessionNotReady(session: GatewayFieldSession) = Unit
                override fun failureAdmission(session: GatewayFieldSession, authenticationFailure: Boolean): (() -> Boolean) = { true }
            }, presentation, object : RouteAcquisitionController.Backend {
                override fun fetchRoute(session: GatewayFieldSession, request: WalkingRouteRequest): CancellableNetworkCall<WalkingRoute> {
                    transportCalls++
                    requestedSessions += session
                    requestedOrigins += request.origin
                    requestedDestinations += request.destination
                    return CancellableNetworkCall(executeBlock = {
                        executedCalls++
                        val start=RoutePoint(request.origin.latitude, request.origin.longitude + lonForMeters(routeOffsetM))
                        val end=RoutePoint(request.destination.latitude, request.destination.longitude + lonForMeters(routeOffsetM))
                        val route=WalkingRoute("STAIR_AVOID", WalkingRouteSummary(100, 90), listOf(start,end),
                            listOf(WalkingRouteGuidePoint(index=0,point=RoutePoint((start.latitude+end.latitude)/2,start.longitude),
                                instruction="직진",distanceFromStartM=50,remainingDistanceM=50,bearingDeg=0f,turnType=11,pointType="guide",facilityType=15)),
                            steps=listOf(WalkingRouteStep(index=0,distanceM=100,durationS=90,points=listOf(start,end),instruction="직진",roadName=null,turnType=11,facilityType=15)))
                        Class.forName("kr.co.hanium.dreamup.walksafe.navigation.BackendWalkingRouteClientKt")
                            .getDeclaredMethod("validateWalkingRoute",WalkingRoute::class.java,WalkingRouteRequest::class.java)
                            .apply{isAccessible=true}.invoke(null,route,request)
                        beforeResponse()
                        responseTransform(route)
                    }).also(calls::add)
                }
                override fun revalidate(session: GatewayFieldSession, actorId: String?, capacitySessionGeneration: Long) =
                    GatewaySessionRevalidation(GatewaySessionRevalidationStatus.READY, "fixture")
            }, network, emptySearch(), startSpeech, Executor { worker += it },
                { ui += Runnable(it) }, scheduler, scheduler, { now }, { 100_000L }, lane)
        }
        @JvmOverloads fun prepareArchive(acknowledgeOwnership: Boolean = true) {
            permission.authenticated(session.actorId)
            archive.initialize(::store); runIo(); runUi()
            archive.submitRouteSnapshotOwnership(gateway(), lifecycle.snapshot())
            if (acknowledgeOwnership) {
                runIo(); runUi()
                assertFalse(archive.preparationPending); assertNotNull(archive.walkScope)
            }
        }
        fun runWorker() { while (worker.isNotEmpty()) worker.removeAt(0).run() }
        fun runIo() { while (io.isNotEmpty()) io.removeAt(0).run() }
        fun runUi() { while (ui.isNotEmpty()) ui.removeAt(0).run() }
        fun store(): EncryptedRouteSnapshotStore = EncryptedRouteSnapshotStore(object : RouteSnapshotStorage {
            override fun read(): String? = stored
            override fun write(envelope: String): Boolean { stored = envelope; return true }
            override fun clear(): Boolean { stored = null; return true }
        }, VersionedLocalAead(AeadKeyPolicy("test.acquisition.v", 1, setOf(1)), object : LocalAeadKeyProvider {
            override fun getOrCreate(alias: String): SecretKey = keys.getOrPut(alias) { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
            override fun getExisting(alias: String): SecretKey? = keys[alias]
            override fun delete(alias: String) = true
            override fun createFreshAfterVerifiedPurge(alias: String) = getOrCreate(alias)
        }), testOnly = Unit, elapsedRealtimeMs = { now }, bootCount = { 7 }, processCoordinator = RouteSnapshotProcessCoordinator())

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
                override fun navigationStatus(text: String) { statuses += text }
                override fun status(title: String, detail: String) = Unit
                override fun speak(message: String) { spoken += message }
                override fun offerCandidates(state: DestinationSearchVoiceState, message: String) = Unit
                override fun sessionNotReady(session: GatewayFieldSession) = Unit
                override fun failureAdmission(session: GatewayFieldSession, error: Exception): (() -> Boolean)? = null
            }, object : DestinationSearchController.Backend {
                override fun searchCall(session: GatewayFieldSession, query: String, limit: Int, origin: RoutePoint?): CancellableNetworkCall<DestinationSearchResponse> = error("No search request")
                override fun revalidate(session: GatewayFieldSession, actorId: String?, capacitySessionGeneration: Long): GatewaySessionRevalidation = error("No search request")
            }, network, Executor { error("No search worker") }, { error("No search UI callback") }, { null })
    }
}

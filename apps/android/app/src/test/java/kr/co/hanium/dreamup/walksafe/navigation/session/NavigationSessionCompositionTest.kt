package kr.co.hanium.dreamup.walksafe.navigation.session

import android.app.Application
import android.location.Location
import com.google.android.gms.location.LocationCallback
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.device.RuntimeMeasuredQualityGate
import kr.co.hanium.dreamup.walksafe.device.WalkSafeEnvironmentProfiles
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningCoordinator
import kr.co.hanium.dreamup.walksafe.navigation.positioning.UserMotionEvidenceSource
import kr.co.hanium.dreamup.walksafe.navigation.positioning.UserMotionStateOwner
import kr.co.hanium.dreamup.walksafe.navigation.positioning.UserMotionStateSnapshot
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

/** Real navigation owners; only Activity/SDK ports are controlled by this fixture. */
class NavigationSessionCompositionTest {
    @Test fun eagerRunAndColdLazyGraphDoNotQueryAnyActivitySdkOrClock() {
        val f = Fixture()
        assertFalse(f.graph.run.active)
        assertTrue(f.events.isEmpty())
        assertTrue(f.calls.isEmpty())
        assertEquals(0, f.clockReads)
        assertTrue(f.holders().all { !it.isInitialized() })
    }

    @Test fun composedNavigatorExpiresDecisionAtTheInjectedClockBoundary() {
        val f = Fixture()
        f.installRoute()
        val navigator = f.graph.run.navigator
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, navigator.onUntrustedLocation()?.pendingUserDecision)
        val token = requireNotNull(navigator.pendingDecisionToken())
        f.now += 29_999L
        assertEquals(token, navigator.pendingDecisionToken())
        f.now++
        assertNull(navigator.pendingDecisionToken())
        assertEquals("route_decision_stale", navigator.selectDeviationChoice(RouteDeviationChoice.RECHECK_LOCATION, token).reason)
        assertTrue(navigator.hasRoute())
        assertTrue(f.holders().all { !it.isInitialized() })
    }

    @Test fun composedNavigatorRecoversOnlyAfterTwoFreshObservationsOnTheInjectedTimeline() {
        val f = Fixture()
        f.installRoute()
        val navigator = f.graph.run.navigator
        fun update(eastM: Double) = navigator.update(f.fix(eastM), f.now, false,
            facingObservation = RouteFacingObservation(90.0, 3.0, f.now))
        update(50.0)
        assertNotNull(navigator.currentAcceptedRouteMatchFor(f.now))
        f.now += 10_000L
        assertTrue(requireNotNull(navigator.onUntrustedLocation()).cancelStaleNavigationSpeech)
        assertTrue(navigator.canResumeAfterPositionRecovery())
        val token = requireNotNull(navigator.pendingDecisionToken())
        f.now += 1_000L
        update(51.0)
        assertNull(navigator.currentAcceptedRouteMatchFor(f.now))
        assertEquals(token, navigator.pendingDecisionToken())
        f.now += 1_000L
        update(52.0)
        assertNotNull(navigator.currentAcceptedRouteMatchFor(f.now))
        assertNull(navigator.pendingDecisionToken())
        assertTrue(f.holders().all { !it.isInitialized() })
    }

    @Test fun positioningForcesDiagnosticsButKeepsEveryCyclicPeerCold() {
        val f = Fixture(); val owner = f.graph.positioning.value
        assertEquals(listOf("positionScope", "positionSources", "mounting", "positionEffects", "diagnostics"), f.events)
        assertSame(owner, f.graph.positioning.value)
        assertSame(f.graph.run, field(owner, "run"))
        assertTrue(f.calls.isEmpty())
        assertSame(f.mountLock, field<PositioningSessionController.Mounting>(owner, "mounting").observationLock)
        assertFalse(f.graph.gps.isInitialized()); assertFalse(f.graph.compass.isInitialized())
        assertFalse(f.graph.guidance.isInitialized()); assertFalse(f.graph.acquisition.isInitialized())
    }

    @Test fun gpsConstructionRetainsTheActualGateAndDoesNotForcePositioningOrRecheck() {
        val f = Fixture(); val gps = f.graph.gps.value
        assertEquals(listOf("gpsGate", "gpsEvidence"), f.events)
        assertSame(f.gate, field(gps, "runtimeGpsQualityGate"))
        assertNull(gps.owner)
        assertFalse(f.graph.positioning.isInitialized())
        assertFalse(f.graph.acquisition.isInitialized())
        assertTrue(f.calls.isEmpty()); assertEquals(0, f.clockReads)
    }

    @Test fun guidanceConstructionSharesStartSpeechWithoutForcingPositionCompassOrRequest() {
        val f = Fixture(); val guide = f.graph.guidance.value
        assertEquals(listOf("guidanceScope", "speech", "guidanceEffects", "diagnostics", "installedSpeech", "callbacks"), f.events)
        assertSame(f.startSpeech, field(guide, "installedRouteStartSpeechCoordinator"))
        assertSame(f.graph.run, field(guide, "navigationRunState"))
        assertFalse(f.graph.positioning.isInitialized()); assertFalse(f.graph.compass.isInitialized())
        assertFalse(f.graph.acquisition.isInitialized()); assertTrue(f.calls.isEmpty())
    }

    @Test fun archiveCreationRetainsItsLaneAndDoesNotForcePendingRouteContinuation() {
        val f = Fixture(); val archive = f.graph.archive.value
        assertEquals(listOf("lane", "archiveFacts", "presentation", "callbacks"), f.events)
        assertSame(f.lane, field(archive, "lane"))
        assertTrue(archive.preparationPending)
        assertFalse(f.graph.acquisition.isInitialized()); assertTrue(f.calls.isEmpty())
    }

    @Test fun acquisitionEvaluatesOriginalArchiveAndSdkFactoriesInOrderWithoutForcingGps() {
        val f = Fixture(); val request = f.graph.acquisition.value
        assertEquals(listOf("lane", "archiveFacts", "presentation", "callbacks", "routeFacts", "routeEffects",
            "presentation", "backend", "navigationRequests", "destinationSearch", "installedSpeech", "worker",
            "startWaitCallbacks", "callbacks", "lane"), f.events)
        assertSame(f.graph.archive.value, field(request, "archive"))
        assertSame(f.graph.run, field(request, "run"))
        assertSame(f.startSpeech, field(request, "installedStartSpeech"))
        assertSame(f.search, field(request, "destinationSearch"))
        assertSame(f.requests, field(request, "navigationRequests"))
        assertSame(f.lane, field(request, "lane"))
        assertFalse(f.graph.positioning.isInitialized()); assertFalse(f.graph.gps.isInitialized())
        assertFalse(f.graph.guidance.isInitialized()); assertFalse(f.graph.compass.isInitialized())
        assertTrue(f.calls.isEmpty()); assertEquals(0, f.clockReads)
    }

    @Test fun simultaneousFirstForcePublishesOneSynchronizedOwnerAndDiagnostics() {
        val f = Fixture(); val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (0 until 16).map { pool.submit<PositioningSessionController> { start.await(); f.graph.positioning.value } }
            start.countDown(); val values = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertTrue(values.all { it === values.first() })
            assertEquals(1, f.events.count { it == "positionSources" })
            assertEquals(1, f.events.count { it == "diagnostics" })
        } finally { pool.shutdownNow() }
    }

    @Test fun guidanceAndAcquisitionObserveTheSameLivePeerStateInsteadOfActivityProjections() {
        val f = Fixture(); val guide = f.graph.guidance.value
        val scope = field<RouteGuidanceController.ScopeReader>(guide, "scope")
        assertFalse(f.graph.acquisition.isInitialized())
        assertEquals(0, scope.requestGeneration)
        val requests = f.graph.acquisition.value
        setField(requests, "routeRequestGeneration", 91)
        assertEquals(91, scope.requestGeneration)
        setField(requests, "routeRequestGeneration", 92)
        assertEquals(92, scope.requestGeneration)
        assertEquals(0, f.calls.count { it.contains("requestGeneration") })
        val facts = field<RouteAcquisitionController.Facts>(requests, "facts")
        setField(guide, "positionGuidancePaused", true)
        assertTrue(facts.positionPaused)
        setField(guide, "positionGuidancePaused", false)
        assertFalse(facts.positionPaused)
    }

    @Test fun compassCreationAndCurrentnessUseTheActualBindingAndRequestOwner() {
        val f = Fixture(); f.bind()
        val compass = f.graph.compass.value
        val source = field<RouteCompassSession.Source>(compass, "source")
        val owner = requireNotNull(source.captureOwner())
        assertSame(f.binding, owner.binding)
        assertTrue(source.ownerCurrent(owner, true, true))
        assertFalse(source.ownerCurrent(owner, false, true))
        assertFalse(source.ownerCurrent(owner, true, false))
        f.foreground = false; assertFalse(source.ownerCurrent(owner, true, true)); f.foreground = true
        f.feedbackGeneration++; assertFalse(source.ownerCurrent(owner, true, true)); f.feedbackGeneration--
        setField(f.graph.acquisition.value, "routeRequestGeneration", owner.requestGeneration + 1)
        assertFalse(source.ownerCurrent(owner, true, true))
        assertTrue(f.calls.none { it.contains("routeRequest") })
    }

    @Test fun compassQueuedLeaseRejectsAReplacedCollectionAndRetainsOriginalTime() {
        val f = Fixture(); f.bind(); val compass = f.graph.compass.value
        val source = field<RouteCompassSession.Source>(compass, "source")
        setField(compass, "orientationLocationOwnerActive", true)
        val lease = requireNotNull(source.captureSchedule(true))
        assertTrue(lease.isCurrent())
        setField(f.graph.positioning.value, "locationCallbackGeneration", f.binding.locationGeneration + 1)
        assertFalse(lease.isCurrent())
        assertEquals(0, f.clockReads)
    }

    @Test fun missingBindingReturnsBeforeActorGatewayProfileOrSdkLookup() {
        val f = Fixture()
        assertNull(f.graph.currentContinuousGpsSourceOrNull())
        assertTrue(f.calls.isEmpty())
        assertFalse(f.graph.gps.isInitialized()); assertFalse(f.graph.acquisition.isInitialized())
        assertEquals(0, f.clockReads)
    }

    @Test fun acceptedSourceRetainsOriginalBindingEpochProfileAcrossLiveQueries() {
        val f = Fixture(); f.bind()
        val first = requireNotNull(f.graph.currentContinuousGpsSourceOrNull())
        assertSame(f.binding, first.binding); assertEquals(f.walk.epoch, first.epoch); assertSame(f.profile, first.profile)
        assertEquals(first, f.graph.currentContinuousGpsSourceOrNull())
        f.walk = f.walk.copy(epoch = WalkRuntimeEpoch("next", 3))
        assertEquals(f.walk.epoch, f.graph.currentContinuousGpsSourceOrNull()?.epoch)
        assertSame(f.binding, f.graph.currentContinuousGpsSourceOrNull()?.binding)
        assertEquals(0, f.clockReads)
    }

    @Test fun gpsActorAndGatewayFailuresRejectWithoutReplacingOrResettingGate() {
        val mutations: List<Pair<String, (Fixture) -> Unit>> = listOf(
            "activity" to { it.foreground = false }, "walkForeground" to { it.walk = it.walk.copy(isForeground = false) },
            "walkEnded" to { it.walk = it.walk.copy(state = WalkSessionState.ENDED, mode = WalkSessionMode.UNAVAILABLE) },
            "rawActor" to { it.rawActor = "other" }, "verifiedActor" to { it.verifiedActor = "other" },
            "onboarding" to { it.onboarded = false }, "profile" to { it.profile = null },
            "storage" to { it.gateway = it.gateway(storage = true) },
            "recovery" to { it.gateway = it.gateway(recovery = true) },
            "generation" to { it.gateway = it.gateway(generation = it.gateway.generation + 1) },
            "sessionIdentity" to { it.gateway = it.gateway(session = it.newSession()) },
            "deletion" to { it.deletionBlocked = true }, "sessionExpired" to { it.session.invalidate() },
        )
        for ((name, mutate) in mutations) {
            val f = Fixture(); f.bind(); val gate = f.gate; val source = requireNotNull(f.graph.currentContinuousGpsSourceOrNull())
            mutate(f); assertNull(name, f.graph.currentContinuousGpsSourceOrNull())
            assertSame(name, gate, f.gate); assertSame(source.binding, f.binding)
            assertFalse(name, f.graph.gps.isInitialized()); assertEquals(name, 0, f.clockReads)
        }
    }

    @Test fun gpsBindingCallbackInputAndFieldScopeFailuresRejectCurrentSource() {
        val mutations: List<Pair<String, (Fixture) -> Unit>> = listOf(
            "callback" to { setField(it.graph.positioning.value, "locationCallback", null) },
            "callbackGeneration" to { setField(it.graph.positioning.value, "locationCallbackGeneration", 77) },
            "coordinatorIdentity" to { setField(it.graph.positioning.value, "positioningCoordinator", PositioningCoordinator()) },
            "bindingOwner" to { it.replaceOwner(it.owner.copy(actorId = "other"), changeBinding = false) },
            "fieldEpoch" to { it.replaceOwner(it.owner.copy(fieldWalkEpoch = it.walk.epoch)) },
            "fieldScope" to { it.replaceOwner(it.owner.copy(fieldScopeId = "field")) },
            "nullActor" to { it.replaceOwner(it.owner.copy(actorId = null)) },
            "nullSession" to { it.replaceOwner(it.owner.copy(gatewaySession = null)) },
            "inputGeneration" to { setField(it.graph.positioning.value, "positioningCollectionBinding",
                PositioningCollectionBinding(it.binding.coordinator, it.binding.inputGeneration + 1, it.owner, 4, 800)) },
        )
        for ((name, mutate) in mutations) {
            val f = Fixture(); f.bind(); assertNotNull(name, f.graph.currentContinuousGpsSourceOrNull())
            mutate(f); assertNull(name, f.graph.currentContinuousGpsSourceOrNull())
            assertFalse(name, f.graph.gps.isInitialized()); assertEquals(name, 0, f.clockReads)
        }
    }

    @Test fun freshLocationForUnpublishedOrRetiredSourceNeverTouchesNativeLocation() {
        val f = Fixture(); f.bind(); val unread = unread<Location>()
        f.graph.consumeFreshGpsLocation(unread, 1234)
        assertTrue(f.calls.none { it == "sdk.mock" })
        setField(f.graph.gps.value, "continuousGpsSource", requireNotNull(f.graph.currentContinuousGpsSourceOrNull()).copy(binding = Any()))
        f.graph.consumeFreshGpsLocation(unread, 1234)
        assertTrue(f.calls.none { it == "sdk.mock" }); assertEquals(0, f.clockReads)
    }

    @Test fun readySourceRecheckReadsSchedulingClockOnceAndRejectsLateActorDelivery() {
        val f = Fixture(); f.bind()
        setField(f.graph.gps.value, "continuousGpsSource", requireNotNull(f.graph.currentContinuousGpsSourceOrNull()))
        val effects = field<RouteAcquisitionController.Effects>(f.graph.acquisition.value, "effects")
        assertEquals(0, f.clockReads)
        effects.requestFreshGps()
        assertEquals(1, f.freshCallbacks.size)
        assertEquals("An admitted source reads time once to preserve the original expiry retry period", 1, f.clockReads)
        assertFalse(f.graph.guidance.isInitialized()); assertFalse(f.graph.compass.isInitialized())
        f.rawActor = "changed-actor"
        effects.requestFreshGps()
        assertEquals("A retired source cannot request another location", 1, f.freshCallbacks.size)
        assertEquals("Source rejection must precede reading the clock", 1, f.clockReads)
        f.freshCallbacks.single()(unread())
        assertNull(f.mockInput)
        assertEquals("Late delivery cannot read a receipt clock", 1, f.clockReads)
        assertTrue(f.gpsObservationTimes.isEmpty())
        assertTrue(f.gnssEvaluationTimes.isEmpty())
        assertSame(f.gate, field(f.graph.gps.value, "runtimeGpsQualityGate"))
        assertFalse(f.graph.guidance.isInitialized()); assertFalse(f.graph.compass.isInitialized())
    }

    @Test fun publishedFreshLocationPassesTheOriginalObjectAndFixTimeToTheActualEstimator() {
        val sdk = android.os.Build.VERSION::class.java.getDeclaredField("SDK_INT")
        val unsafe = unsafe(); val base = unsafe.staticFieldBase(sdk); val offset = unsafe.staticFieldOffset(sdk)
        val previous = unsafe.getInt(base, offset)
        unsafe.putInt(base, offset, 30)
        try {
            val f = Fixture(); f.bind(); val source = requireNotNull(f.graph.currentContinuousGpsSourceOrNull())
            setField(f.graph.gps.value, "continuousGpsSource", source)
            val motionOwner = UserMotionStateOwner(0L, source.epoch, null, "navigation-fixture")
            val binding = source.binding as PositioningCollectionBinding
            assertTrue(binding.coordinator.beginUserMotionEpoch(motionOwner, 800L))
            val location = OriginalLocation()
            f.graph.consumeFreshGpsLocation(location, 1950)
            assertSame(location, f.mockInput)
            assertTrue("The supplied measurement time reaches the evidence writer", f.gpsObservationTimes.contains(1800L))
            assertEquals(1800L, f.graph.positioning.value.rawRouteLocation?.elapsedRealtimeMs)
            assertSame(location, f.traceLocation)
            // Current policy evaluation is separate from both original source timestamps.
            assertEquals(listOf(2000L), f.gnssEvaluationTimes)
            val motion = requireNotNull(f.traceUserMotion)
            assertEquals(motionOwner, motion.owner)
            val gnss = motion.evidenceRefs.single { it.source == UserMotionEvidenceSource.GNSS_FIX }
            assertEquals(1800L, gnss.measuredAtElapsedRealtimeMs)
            assertEquals(1950L, gnss.receivedAtElapsedRealtimeMs)
            assertEquals(1_800_000_000L, gnss.locationEvidence?.measurementElapsedRealtimeNanos)
            assertTrue(f.graph.gps.value.shouldSkipAcceptedGnssSample(
                kr.co.hanium.dreamup.walksafe.navigation.positioning.GnssSampleFingerprint(
                    1_800_000_000L, 555L, "fused", 37.5, 127.0, 3.0, false), 1951, 1.0))
            assertSame(source.binding, f.graph.gps.value.owner?.binding)
        } finally { unsafe.putInt(base, offset, previous) }
    }

    @Test fun androidAdapterConstructorAndAvailabilityQueriesNeverReadNativeInstances() {
        var instanceReads = 0; var posted = 0; var mockInput: Location? = null
        val source = AndroidNavigationSources(unread<Application>(), { true }, { instanceReads++; sdkInterface() },
            { true }, { instanceReads++; unread() }, { true }, { instanceReads++; unread() }, { false },
            { posted++; it.run() }, 1000, 500, { mockInput = it; false })
        assertEquals(0, instanceReads); assertEquals(0, posted)
        assertTrue(source.fusedAvailable); assertTrue(source.profileStoreAvailable); assertTrue(source.orientationAvailable)
        assertTrue(source.available); assertFalse(source.orientationCanStop)
        assertEquals(0, instanceReads)
        val completed = source.completionExecutor
        assertEquals(0, posted); completed.execute {}; assertEquals(1, posted)
        val location = unread<Location>(); assertFalse(source.mock(location)); assertSame(location, mockInput)
        source.fusedSource({ true }, { _, _ -> }); assertEquals(1, instanceReads)
        source.captureRemoval(); assertEquals(2, instanceReads)
    }

    private class Fixture {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val calls = mutableListOf<String>(); var clockReads = 0; var now = 2000L
        var mockInput: Location? = null; val gpsObservationTimes = mutableListOf<Long>()
        var traceLocation: Location? = null; val gnssEvaluationTimes = mutableListOf<Long>()
        var traceUserMotion: UserMotionStateSnapshot? = null
        val freshCallbacks = mutableListOf<(Location?) -> Unit>()
        val mountLock = Any(); val gate = RuntimeMeasuredQualityGate(3000)
        val lifecycle = WalkSessionLifecycle({ "navigation" }, { 5000L }, { 2000L })
        val startSpeech = InstalledRouteStartSpeechCoordinator()
        val requests = AndroidNavigationRequestCoordinator()
        val lane = RouteSnapshotIoLane(Executor { calls += "io" })
        val session = newSession(); var rawActor: String? = session.actorId; var verifiedActor: String? = session.actorId
        var foreground = true; var onboarded = true; var deletionBlocked = false; var feedbackGeneration = 7
        var profile: ApprovedOfficialEnvironmentProfile? = requireNotNull(WalkSafeEnvironmentProfiles.active(true)).officialEnvironment
        var walk = WalkSessionSnapshot(WalkRuntimeEpoch("navigation", 2), WalkSessionState.READY, WalkSessionMode.FULL, null, true, null)
        var gateway = gateway(); lateinit var owner: LocationCollectionOwner; lateinit var binding: PositioningCollectionBinding
        val callbacks = stub<RouteCallbackScheduler>("callbacks")
        val presentation = stub<RoutePresentation>("presentation")
        val search = DestinationSearchController(stub("searchFacts"), stub("searchActions"), stub("searchBackend"),
            requests, Executor { calls += "searchWorker" }, { calls += "searchUi" }, { null })
        val graph = NavigationSessionComposition(false,
            PositioningAssemblyBindings({ make("positionScope", stub<PositioningSessionController.Scope>("positionScope") { method, _ ->
                when(method) { "currentOwner" -> if (::owner.isInitialized) owner else null; "getNavigationAllowed" -> true; "getProcessInstanceId" -> "navigation-fixture"; "getLifecycle" -> lifecycle; else -> null }
            }) }, { make("positionSources", stub<PositioningSessionController.Sources>("sdk") { method, args ->
                if (method == "mock") { mockInput = args?.firstOrNull() as Location; false } else null
            }) },
                { make("mounting", stub<PositioningSessionController.Mounting>("mounting") { method, _ -> if (method == "getObservationLock") mountLock else null }) },
                { make("positionEffects", stub<PositioningActivityEffects>("positionEffects") { method, args ->
                    if (method == "appendGnssTrace") {
                        val snapshot = args!![0] as kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningSnapshot
                        traceLocation = args[1] as Location
                        traceUserMotion = snapshot.userMotion
                        gnssEvaluationTimes += requireNotNull(snapshot.userMotion).evaluatedAtElapsedRealtimeMs
                    }
                    null
                }) }),
            GpsAssemblyBindings({ make("gpsGate", gate) }, { make("gpsEvidence", stub<ContinuousGpsSession.EvidenceWriter>("gpsEvidence") { method, args ->
                if (method == "record") gpsObservationTimes += args!![1] as Long
                null
            }) },
                { calls += "gpsActivity.nativeCheckCurrent"; true }, { calls += "gpsActivity.prewalkEvidenceChanged" },
                { result -> calls += "gpsActivity.requestFresh"; freshCallbacks += result; {} }),
            GuidanceAssemblyBindings({ make("guidanceScope", stub<GuidanceActivityScope>("guidanceScope")) },
                { calls += "stepCount"; 0 }, { calls += "stepLengthM"; 0.6f },
                { make("speech", stub<RouteGuidanceController.SpeechPort>("speech")) },
                { make("guidanceEffects", stub<GuidanceActivityEffects>("guidanceEffects")) }),
            CompassAssemblyBindings({ make("compassAuthority", object : CompassActivityAuthority {
                override val foreground get() = this@Fixture.foreground
                override val lifecycleReady = true
                override fun walkEpoch() = walk.epoch
                override fun gatewaySnapshot() = gateway
                override val feedbackGeneration get() = this@Fixture.feedbackGeneration
                override fun currentCollectionOwner() = if (::owner.isInitialized) owner else null
                override val navigationAllowed = true
                override val fieldAllowed = false
            }) }, { make("tracker", stub<RouteCompassSession.Tracker>("tracker")) },
                { calls += "compass.cancelSpeech" }, { calls += "compass.refreshPresentation" },
                { make("compassCallbacks", stub<RouteCompassSession.Callbacks>("compassCallbacks")) }),
            { make("archiveFacts", stub<ArchiveActivityFacts>("archiveFacts")) },
            AcquisitionAssemblyBindings({ make("routeFacts", stub<AcquisitionActivityFacts>("routeFacts")) },
                { make("routeEffects", stub<AcquisitionActivityEffects>("routeEffects")) },
                { make("backend", stub<RouteAcquisitionController.Backend>("backend")) },
                { make("navigationRequests", requests) }, { make("destinationSearch", search) },
                { make("worker", Executor { calls += "worker" }) }, { make("startWaitCallbacks", callbacks) }),
            NavigationAssemblyResources({ make("presentation", presentation) }, { make("callbacks", callbacks) },
                { make("installedSpeech", startSpeech) }, { make("lane", lane) },
                { make("diagnostics", stub<RouteGuidanceController.Diagnostics>("diagnostics")) }),
            object : NavigationGpsActorAuthority {
                override val foreground get() = this@Fixture.foreground
                override fun walkSnapshot() = walk.also { calls += "actor.walk" }
                override fun gatewaySnapshot() = gateway.also { calls += "actor.gateway" }
                override fun profileOrNull() = profile.also { calls += "actor.profile" }
                override val rawActorId get() = rawActor
                override val onboardingInitialized get() = onboarded
                override fun verifiedActorId() = verifiedActor
                override fun deletionBlocked() = this@Fixture.deletionBlocked
            }, { clockReads++; now }, { clockReads++; 9000L }, { true }, { calls += "mainPost" })
        fun point(eastM: Double) = RoutePoint(0.0, eastM / 111_194.92664455874)
        fun fix(eastM: Double) = point(eastM).let { TrustedLocation(it.latitude, it.longitude, 2f, now) }
        fun installRoute() {
            graph.run.navigator.setRoute(WalkingRoute("clock", WalkingRouteSummary(300, 300),
                listOf(point(0.0), point(300.0)), emptyList()))
            graph.run.selectDestination(point(300.0))
            graph.run.markRequestActive()
        }
        fun holders(): List<Lazy<*>> = listOf(graph.positioning, graph.gps, graph.diagnostics, graph.guidance, graph.compass, graph.archive, graph.acquisition)
        fun gateway(session: GatewayFieldSession = this.session, generation: Long = 3, storage: Boolean = false, recovery: Boolean = false) =
            GatewaySessionProcessSnapshot(session, null, generation, storage, null, recovery)
        fun newSession() = GatewayFieldSession.verified("https://navigation.example.test", "actor_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "walksafe_field_session=v2.navigation", Long.MAX_VALUE)
        fun bind() {
            val positions = graph.positioning.value
            owner = LocationCollectionOwner(session.actorId, session, gateway.generation)
            binding = PositioningCollectionBinding(positions.coordinator, positions.coordinator.currentInputGeneration(), owner, 4, 800)
            setField(positions, "positioningCollectionBinding", binding); setField(positions, "locationCollectionOwner", owner)
            setField(positions, "locationCallbackGeneration", 4); setField(positions, "locationCallback", object : LocationCallback() {})
        }
        fun replaceOwner(replacement: LocationCollectionOwner, changeBinding: Boolean = true) {
            owner = replacement; setField(graph.positioning.value, "locationCollectionOwner", owner)
            if (changeBinding) { binding = PositioningCollectionBinding(binding.coordinator, binding.inputGeneration, owner, 4, 800)
                setField(graph.positioning.value, "positioningCollectionBinding", binding) }
        }
        fun <T> make(name: String, value: T): T { events += name; return value }
        inline fun <reified T> stub(label: String, crossinline answer: (String, Array<out Any?>?) -> Any? = { _, _ -> null }): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
                when(method.name) {
                    "equals" -> proxy === args?.firstOrNull(); "hashCode" -> System.identityHashCode(proxy); "toString" -> label
                    else -> { calls += "$label.${method.name}"; answer(method.name, args) ?: when(method.returnType) {
                        java.lang.Boolean.TYPE -> false; java.lang.Integer.TYPE -> 0; java.lang.Long.TYPE -> 0L
                        java.lang.Float.TYPE -> 0f; java.lang.Double.TYPE -> 0.0; else -> null
                    } }
                }
            } as T
    }

    companion object {
        @Suppress("UNCHECKED_CAST") private fun <T> field(target: Any, name: String): T = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) as T
        private fun setField(target: Any, name: String, value: Any?) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
        private inline fun <reified T> sdkInterface(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            when(method.returnType) { java.lang.Boolean.TYPE -> false; java.lang.Integer.TYPE -> 0; java.lang.Long.TYPE -> 0L; else -> null }
        } as T
        private inline fun <reified T> unread(): T {
            return unsafe().allocateInstance(T::class.java) as T
        }
        private fun unsafe() = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as sun.misc.Unsafe
    }

    private class OriginalLocation : Location("fused") {
        override fun getProvider() = "fused"
        override fun getLatitude() = 37.5
        override fun getLongitude() = 127.0
        override fun hasAccuracy() = true
        override fun getAccuracy() = 3f
        override fun getElapsedRealtimeNanos() = 1_800_000_000L
        override fun getTime() = 555L
        override fun hasAltitude() = false
        override fun hasVerticalAccuracy() = false
        override fun hasSpeedAccuracy() = false
        override fun hasBearingAccuracy() = false
        override fun hasElapsedRealtimeUncertaintyNanos() = false
        override fun hasBearing() = false
        override fun hasSpeed() = false
        override fun getSpeed() = 0f
        override fun getBearing() = 0f
        override fun getAltitude() = 0.0
        override fun getSpeedAccuracyMetersPerSecond() = 0f
        override fun getBearingAccuracyDegrees() = 0f
        override fun getVerticalAccuracyMeters() = 0f
        override fun getElapsedRealtimeUncertaintyNanos() = 0.0
    }
}

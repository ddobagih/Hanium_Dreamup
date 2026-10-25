package kr.co.hanium.dreamup.walksafe.navigation

import android.Manifest
import android.app.Instrumentation
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.cos
import kotlin.math.max
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.device.environment.*
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.navigation.location.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.*
import kr.co.hanium.dreamup.walksafe.navigation.session.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.voice.CurrentStateSpeechLease
import kr.co.hanium.dreamup.walksafe.voice.interaction.*
import kr.co.hanium.dreamup.walksafe.voice.output.*
import org.junit.Assert.*

/**
 * SIMULATED SDK-decoded position/heading, route and initial ready/account context.
 * REAL production composition, estimator/gates/compass/guidance/output, elapsed clock, Main Handler,
 * native offline Korean TTS. This does not start MainActivity, authenticate, project SensorEvents,
 * register physical location/GNSS/motion sensors, save profiles, or prove audible/field safety.
 */
internal class MeasuredInputGuidanceFixture(private val initialNorthM: Double = 0.0) : AutoCloseable {
    private enum class Page { GUIDANCE }
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val main = Handler(Looper.getMainLooper())
    private val producerThread = HandlerThread("measured-input-owned-producer").apply { start() }
    private val producer = Handler(producerThread.looper)
    private val producerFailure = AtomicReference<Throwable?>()
    private val runId = UUID.randomUUID().toString().replace("-", "")
    private val actor = "actor_$runId"
    private val session = GatewayFieldSession.verified("https://simulated-measurement.example.test", actor,
        "walksafe_field_session=simulated_$runId", System.currentTimeMillis() + 600_000L)
    private val gateway = GatewaySessionProcessSnapshot(session, null, 1L, false, null, false)
    private val lifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)
    private val profile = ApprovedOfficialEnvironmentProfile("simulated-measured-input", "simulated-camera", 10.0, 10_000L, 2)
    private val gpsGate = RuntimeMeasuredQualityGate(GuidanceRecoveryTiming.GPS_RECHECK_PERIOD_MS,
        gpsFailureConfirmationPeriods = GuidanceRecoveryTiming.GPS_RECHECK_PERIODS)
    private val evidence = OfficialEnvironmentEvidenceStore(Any(), gpsGate,
        RuntimeMeasuredQualityGate(GuidanceRecoveryTiming.CAMERA_GRACE_MS))
    private val permission = ContextCompat.checkSelfPermission(instrumentation.targetContext,
        Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private val deletion = AccountDeletionStateMachine()
    private val deletionLease = deletion.attachActivity()
    private val permissionOwner = PermissionSessionPolicy()
    private val installedSpeech = InstalledRouteStartSpeechCoordinator()
    private val initialized = CountDownLatch(1)
    private val ready = AtomicReference<String?>()
    private var actuator: AndroidFeedbackActuator? = null
    @Volatile private var foreground = true
    @Volatile private var running = false
    @Volatile private var trackerRunning = false
    private var headingListener: ((RouteCompassObservationEvent) -> Unit)? = null
    private val heading = AtomicReference(RouteCompassHeadingResult(null, RouteCompassHeadingReason.ORIENTATION_MISSING))
    @Volatile private var badGpsUntilMs = 0L
    @Volatile private var badGpsOffsetM = 1_000.0
    private var activeGpsInputAtMs: Long? = null
    private var observedInputSnapshot: PositioningSnapshot? = null
    @Volatile private var badHeadingUntilMs = 0L
    @Volatile private var approachStartedMs: Long? = null
    @Volatile var recoveryStartedMs = 0L
        private set
    private var firstInputAtMs = 0L
    val rawGps = mutableListOf<GpsRecord>() // Main thread only, read through onMain.
    val speech = mutableListOf<SpeechAttempt>() // Main thread only.
    private val nativeEvents = mutableListOf<SpeechOutputEvent>()
    val statuses = mutableListOf<String>()
    private var headingFaultObserved = false
    private var headingGraceObserved = false
    private val headingGraceSnapshots = mutableListOf<RouteDirectionGraceSnapshot>()
    private var lastResult: String? = null
    private var acknowledged = 0
    private val callbacks = object : RouteCallbackScheduler {
        override fun post(task: Runnable) { check(main.post(task)) }
        override fun postDelayed(task: Runnable, delayMs: Long) { check(main.postDelayed(task, delayMs)) }
        override fun remove(task: Runnable) { main.removeCallbacks(task) }
    }
    private val scheduler = object : SpeechOutputScheduler {
        override fun elapsedRealtimeMs() = now()
        override fun isMainThread() = Looper.myLooper() == Looper.getMainLooper()
        override fun runOnMain(action: () -> Unit) { if (isMainThread()) action() else check(main.post { action() }) }
        override fun postReadiness(task: Runnable, delayMs: Long) { check(main.postDelayed(task, delayMs)) }
        override fun removeReadiness(task: Runnable) { main.removeCallbacks(task) }
        override fun postRetired(action: () -> Unit) { check(main.post { action() }) }
    }
    private val output by lazy { SpeechOutputController(
        debugDiagnostic = { report("OUTPUT", "text=$it") },
        reader = object : SpeechOutputReader {
            override fun lifetime() = SpeechOutputLifetime(1, foreground)
            override fun commandContext() = CommandSpeechContext(1, actor, 1L, lifecycle.snapshot().epoch,
                Page.GUIDANCE, true, true, false, false)
            override fun navigationOwner() = NavigationSpeechOwner(lifecycle.snapshot().epoch,
                graph.run.navigator.currentRouteId(), graph.run.navigator.pendingDecisionToken())
            override fun navigationOwnerIsCurrent(owner: NavigationSpeechOwner) = allowed() &&
                owner.epoch == lifecycle.snapshot().epoch && owner.routeId == graph.run.navigator.currentRouteId() &&
                owner.decisionToken == graph.run.navigator.pendingDecisionToken()
            override fun homeFeatureContextAvailable() = allowed()
            override fun stateSourceIsCurrent(source: CurrentStateSpeechLease) = false
            override fun guidanceVoiceInputActive() = false
            override fun screenReaderActive() = false
        },
        input = object : SpeechOutputInputEffects {
            override fun invalidateHomeWakeInput() = Unit
            override fun promptState(token: CommandResponseToken, effect: CommandPromptEffect) = Unit
            override fun cancelDeferredInput() = Unit
            override fun refreshHomeWakeListening() = Unit
            override fun cancelHomeWakeListening() = Unit
            override fun cancelCommandDialogs(token: CommandResponseToken) = Unit
        },
        presentation = object : SpeechOutputPresentation {
            override fun showLastResult(message: String) { report("OUTPUT_RESULT", "text=$message") }
            override fun showVoiceStatus(message: String) = Unit
            override fun refreshVoiceControls() = Unit
            override fun navigationStatus(message: String) { status(message) }
            override fun commandTerminalDiagnostic(completed: Boolean) = Unit
            override fun accessibilityTargetOrNull(): SpeechAccessibilityTarget? = null
            override fun canScheduleInteraction() = foreground
            override fun postInteraction(task: Runnable, delayMs: Long) { callbacks.postDelayed(task, delayMs) }
            override fun removeInteraction(task: Runnable) { callbacks.remove(task) }
        },
        readyEffects = object : SpeechOutputReadyEffects {
            override fun refreshReadyUi() = Unit
            override fun reevaluateCurrentRisk(actuator: AndroidFeedbackActuator, lifecycleGeneration: Int, nowMs: Long) = Unit
            override fun retryCurrentNavigation(nowMs: Long) { graph.guidance.value.retryCurrentNavigationSpeechAfterReady(nowMs) }
            override fun offlineSpeechUnavailable(lifecycleGeneration: Int, actuator: AndroidFeedbackActuator) = Unit
            override fun beforeCommandReplacement(previousGeneration: Long) { graph.guidance.value.failInterruptedRouteDecisionSpeech(previousGeneration) }
        },
        storage = object : kr.co.hanium.dreamup.walksafe.voice.output.SpeechOutputStorageBinding {
            override fun invalidate() = Unit
            override fun refresh(actuator: AndroidFeedbackActuator) = Unit
        },
        actuatorFactory = SpeechOutputActuatorFactory { unavailable, readyCallback ->
            AndroidFeedbackActuator(instrumentation.targetContext, hapticAllowed = { false },
                onOfflineKoreanSpeechReady = { readyCallback(); ready.set("READY"); initialized.countDown() },
                onOfflineKoreanSpeechUnavailable = { unavailable(); ready.set("UNAVAILABLE"); initialized.countDown() }).also {
                actuator = it
                it.speechOutputListener = { event ->
                    synchronized(nativeEvents) { nativeEvents += event }
                    report("NATIVE", "kind=${event.kind} outcome=${event.outcome} original_at_ms=${event.atElapsedRealtimeMs}")
                    if (event.kind == SpeechOutputKind.NAVIGATION && event.outcome == SpeechOutputOutcome.CANCELLED_STALE_NAVIGATION) {
                        scheduler.runOnMain {
                            speech.lastOrNull { attempt -> attempt.cancelObservation != null && attempt.nativeCancelledAtMs == null }
                                ?.let { attempt -> attempt.nativeCancelledAtMs = event.atElapsedRealtimeMs }
                        }
                    }
                    if (event.kind == SpeechOutputKind.NAVIGATION && event.outcome == SpeechOutputOutcome.STARTED) {
                        scheduler.runOnMain {
                            speech.firstOrNull { attempt -> attempt.startedAtMs == null && attempt.terminalCount() == 0 }
                                ?.let { attempt -> attempt.startedAtMs = event.atElapsedRealtimeMs; attempt.started.countDown() }
                        }
                    }
                }
            }
        }, scheduler = scheduler,
    ) }

    val graph: NavigationSessionComposition by lazy { NavigationSessionComposition(false,
        PositioningAssemblyBindings(
            { port<PositioningSessionController.Scope> { name, _ -> when (name) {
                "currentOwner" -> owner(); "getNavigationAllowed" -> allowed(); "getMotionObservationAllowed" -> false
                "getFineLocationGranted" -> permission; "getLifecycle" -> lifecycle; "getProcessInstanceId" -> "simulated-$runId"
                "reporterId" -> actor; else -> null
            } } },
            { object : PositioningSessionController.Sources {
                override val fusedAvailable = true
                override fun fusedSource(current: (LocationCallback) -> Boolean,
                    onEvent: (LocationCallback, FusedLocationUpdateEvent) -> Unit) = FusedLocationUpdateSource(
                    LocationServices.getFusedLocationProviderClient(instrumentation.targetContext), ::now, current, onEvent)
                override fun requestUpdates(source: FusedLocationUpdateSource) {
                    report("SOURCE_REGISTER", "test_owned=true native_fused_request=false callback_generation=${graph.positioning.value.callbackGeneration}")
                }
                override fun captureRemoval(): (LocationCallback) -> com.google.android.gms.tasks.Task<Void> = { Tasks.forResult<Void>(null) }
                override val completionExecutor = Executor { it.run() }
                override fun gnssObserver(onSnapshot: (GnssQualitySnapshot) -> Unit) = GnssQualityObserver(
                    object : GnssObservationSource {
                        override fun subscribeStatus(listener: GnssObservationListener): GnssObservationSubscription? = null
                        override fun subscribeMeasurements(listener: GnssObservationListener): GnssObservationSubscription? = null
                    }, onSnapshot = onSnapshot)
                override fun pedestrianTracker(onMotion: (PedestrianStationaryDecision) -> Unit): AndroidPedestrianMotionTracker =
                    error("Physical motion registration is outside this test")
                override val profileStoreAvailable = false
                override fun profileStore(): AndroidPedestrianProfileStore = error("Persistent profiles forbidden")
                override fun mock(location: Location): Boolean = error("This stream supplies immutable decoded observations, never Android mock locations")
                override val orientationAvailable = false
                override val orientationCanStop = false
                override fun clearGeomagneticReference() = Unit
                override fun stopOrientation() = Unit
                override fun geomagneticReference(location: Location) = Unit
                override fun chestHeading(timestampMs: Long, maximumAgeMs: Long): HeadingObservation? = null
            } },
            { port<PositioningSessionController.Mounting> { name, _ -> when (name) {
                "getObservationLock" -> evidence.observationLock; "getOutputsAllowed" -> true; else -> null
            } } },
            { port<PositioningActivityEffects> { name, args -> when (name) {
                "stopFieldSession" -> true; "navigationStatus" -> { status(args[0] as String); Unit }
                "cleanupWarning" -> throw (args[0] as RuntimeException); else -> null
            } } }),
        GpsAssemblyBindings({ gpsGate }, { object : ContinuousGpsSession.EvidenceWriter {
            override fun clearGpsEvidence() = evidence.clearGpsEvidence()
            override fun record(epoch: WalkRuntimeEpoch, observedAtMs: Long, trustedFixAvailable: Boolean?, accuracyM: Double?,
                elapsedRealtimeMs: () -> Long, singleOutlier: Boolean) {
                evidence.recordGpsObservation(lifecycle.snapshot().epoch, epoch, observedAtMs, trustedFixAvailable, accuracyM,
                    elapsedRealtimeMs, { profile }, { lifecycle.isRuntimeEpochCurrent(it) }, { graph.gps.value.owner?.epoch },
                    { false }, { lifecycle.snapshot().state == WalkSessionState.ACTIVE }, singleOutlier, { _, previous, current ->
                        report("GPS_GATE", "previous=$previous current=$current original_at_ms=$observedAtMs trusted=$trustedFixAvailable single_outlier=$singleOutlier")
                    })
            }
        } }, { false }, {}, { _ -> {} }),
        GuidanceAssemblyBindings(
            { port<GuidanceActivityScope> { name, args -> when (name) {
                "getLifecycle" -> lifecycle; "getForeground" -> foreground; "getRuntimeActive" -> active()
                "getNavigationAllowed" -> allowed(); "getFeedbackGeneration" -> 1
                "feedbackCurrent" -> foreground && args[0] == 1; else -> null
            } } }, { 0 }, { .7f }, { speechPort() },
            { port<GuidanceActivityEffects> { name, args -> when (name) {
                "navigationStatus" -> { status(args[0] as String); Unit }
                "showLastResult" -> { lastResult = args[0] as String; acknowledged++; report("GUIDANCE_ACK", "ordinal=$acknowledged text=$lastResult"); Unit }
                "interaction" -> { output.speakInteraction(args[0] as String); Unit }
                "unavailableLocationMessage" -> "현재 위치를 확인해 주세요."; else -> null
            } } }),
        CompassAssemblyBindings(
            { port<CompassActivityAuthority> { name, _ -> when (name) {
                "getForeground" -> foreground; "getLifecycleReady" -> true; "walkEpoch" -> lifecycle.snapshot().epoch
                "gatewaySnapshot" -> gateway; "getFeedbackGeneration" -> 1; "currentCollectionOwner" -> owner()
                "getNavigationAllowed" -> allowed(); else -> null
            } } },
            { object : RouteCompassSession.Tracker {
                override val available = true
                override fun listener(listener: (RouteCompassObservationEvent) -> Unit) { headingListener = listener }
                override fun start() { trackerRunning = true }
                override fun stop() { trackerRunning = false }
                override fun current(event: RouteCompassObservationEvent) = trackerRunning && event.registrationGeneration == 1
                override fun heading(nowMs: Long) = heading.get()
            } }, { actuator?.cancelNavigationSpeech() }, {},
            { object : RouteCompassSession.Callbacks {
                override val viewReady = true
                override fun post(task: Runnable) = callbacks.post(task)
                override fun postDelayed(task: Runnable, delayMs: Long): Boolean { callbacks.postDelayed(task, delayMs); return true }
                override fun remove(task: Runnable) = callbacks.remove(task)
            } }),
        { port<ArchiveActivityFacts> { name, _ -> when (name) {
            "gatewaySnapshot" -> gateway; "lifecycleOrNull" -> lifecycle; "activityCurrent" -> foreground
            "deletionActivityLease" -> deletionLease; "deletionCoordinator", "deletionIntentToken" -> error("Archive/deletion outside test")
            else -> null
        } } },
        AcquisitionAssemblyBindings(
            { port<AcquisitionActivityFacts> { name, args -> when (name) {
                "getForeground" -> foreground; "getNavigationAllowed", "getEnvironmentOutputsAllowed" -> allowed()
                "getRuntimeActive" -> active(); "getLifecycle" -> lifecycle; "getGateway" -> gateway
                "getRawActorId", "getActorId" -> actor; "sessionCurrent" -> args[0] === session
                "getResources" -> WalkSessionDeviceResourceSnapshot(true, true, true)
                "getLocationPermissionReady" -> permission; "getGuidancePage", "getStartWaitViewReady" -> true
                "getPermissionOwner" -> permissionOwner; "getDeletionOwner" -> deletion; "getDeletionActivityLease" -> deletionLease
                else -> null
            } } },
            { port<AcquisitionActivityEffects> { name, args -> when (name) {
                "requireActor" -> allowed(); "networkAllowed" -> error("HTTP forbidden")
                "sessionOrNull" -> session; "cancelNavigationSpeech" -> { actuator?.cancelNavigationSpeech(); Unit }
                "beginInstalledRoute" -> { @Suppress("UNCHECKED_CAST") (args[0] as () -> Unit).invoke(); {} }
                "resetStartSpeechState" -> { @Suppress("UNCHECKED_CAST") (args[0] as () -> Unit).invoke(); Unit }
                else -> null
            } } },
            { object : RouteAcquisitionController.Backend {
                override fun fetchRoute(session: GatewayFieldSession, request: WalkingRouteRequest): CancellableNetworkCall<WalkingRoute> = error("HTTP forbidden")
                override fun revalidate(session: GatewayFieldSession, actorId: String?, capacitySessionGeneration: Long): GatewaySessionRevalidation = error("HTTP forbidden")
            } }, { AndroidNavigationRequestCoordinator() },
            { DestinationSearchController(port(), port(), port(), AndroidNavigationRequestCoordinator(),
                Executor { error("Destination work forbidden") }, { error("Destination UI forbidden") }, { null }) },
            { Executor { error("Network worker forbidden") } }, { callbacks }),
        NavigationAssemblyResources(
            { port<RoutePresentation> { name, args -> when (name) {
                "navigationStatus" -> { status(args[0] as String); Unit }
                "speak", "speakStatus" -> { output.speakCommandResponse(args[0] as String, statusExplanation = true); Unit }
                "safetyStop" -> error("Unexpected safety stop: ${args[0]}"); else -> null
            } } }, { callbacks }, { installedSpeech },
            { RouteSnapshotIoLane(Executor { error("Persistent route IO forbidden") }) },
            { port<RouteGuidanceController.Diagnostics> { name, args -> when (name) {
                "getDebug", "getRecording" -> true
                "log", "record" -> { report("DIAGNOSTIC", "name=${args[0]} detail=${args.getOrNull(1)}"); Unit }
                "confidenceLine" -> {
                    val snapshot = args[0] as PositioningSnapshot
                    if (activeGpsInputAtMs != null && snapshot.gnssUpdate != null) observedInputSnapshot = snapshot
                    "SIMULATED_MEASUREMENT_REAL_PIPELINE original_input_ms=$activeGpsInputAtMs confidence=${snapshot.confidence} gnss=${snapshot.gnssUpdate}"
                }; else -> null
            } } }),
        port<NavigationGpsActorAuthority> { name, _ -> when (name) {
            "getForeground" -> foreground; "walkSnapshot" -> lifecycle.snapshot(); "gatewaySnapshot" -> gateway
            "profileOrNull" -> profile; "getRawActorId", "verifiedActorId" -> actor; "getOnboardingInitialized" -> true
            else -> null
        } }, ::now, System::currentTimeMillis, { Looper.myLooper() == Looper.getMainLooper() }, { check(main.post { it() }) }) }

    data class GpsRecord(val atMs: Long, val receiptMs: Long, val bad: Boolean, val northM: Double,
        val trustedBeforeMs: Long?, val trustedAfterMs: Long?, val rawBeforeMs: Long?, val rawAfterMs: Long?, val gate: String?,
        val disposition: GnssObservationDisposition?, val rejection: GnssHardRejectReason?,
        val gateWeight: Double?, val filteredDistanceFromBaselineM: Double?, val rawDistanceFromBaselineM: Double,
        val filteredUncertaintyM: Double?)
    data class CancelObservation(val atMs: Long, val pending: RouteSpeechCueToken?, val offered: RouteSpeechCueToken?,
        val routeId: String?, val routeVersion: Long?, val rawOriginalMs: Long?, val straightDistanceM: Double?)
    class SpeechAttempt(val message: String, val submittedAtMs: Long,
        val initialJoin: Boolean, val cue: RouteSpeechCueToken?, val routeId: String?, val routeVersion: Long?,
        val originalRawMs: Long?, val originalStraightDistanceM: Double?) {
        var startedAtMs: Long? = null
        var cancelObservation: CancelObservation? = null
        var nativeCancelledAtMs: Long? = null
        val started = CountDownLatch(1)
        val terminal = CountDownLatch(1)
        val completed = AtomicInteger()
        val failed = AtomicInteger()
        fun terminalCount() = completed.get() + failed.get()
        fun assertCompletedOnce() { assertEquals(message, 1, completed.get()); assertEquals(message, 0, failed.get()) }
    }

    fun start() {
        assertTrue("Existing fine location permission is required; this test never requests/grants it", permission)
        report("SCOPE", "coordinates_heading_ready_account_route=SIMULATED clock_handler_native_korean_tts=ACTUAL physical_registrations=0 profile_store=false http=false main_on_create=false human_audibility=NOT_TESTED")
        onMain {
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            val epoch = lifecycle.snapshot().epoch
            val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
            val readiness = WalkSessionReadinessCollector(epoch, plan).apply {
                plan.requiredRequirements.forEach { record(WalkSessionReadinessObservation(epoch, it,
                    WalkSessionReadinessStatus.READY, "SIMULATED initial context, not actual device readiness")) }
            }.build(now())
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readiness))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
            output.ensureFeedbackActuator()
            graph.positioning.value.startLocationUpdatesIfAllowed()
            assertNotNull(graph.positioning.value.binding)
            firstInputAtMs = now()
            running = true
        }
        check(producer.post(headingTick))
        check(producer.post(gpsTick))
        assertTrue("Native TTS initialization timeout", initialized.await(25L, TimeUnit.SECONDS))
        assertEquals("READY", ready.get())
        await("five seconds of real original GPS/heading history", 12_000L) {
            now() - firstInputAtMs >= 5_100L && rawGps.size >= 10 && graph.positioning.value.trustedLocation != null &&
                graph.compass.value.facing(now()) != null
        }
    }

    fun installRoute(approach: Boolean = false) = onMain {
        val route = WalkingRoute("STAIR_AVOID", WalkingRouteSummary(700, 700),
            listOf(point(0.0, 0.0), point(600.0, 0.0), point(600.0, 100.0)), listOf(
                WalkingRouteGuidePoint(0, point(0.0, 0.0), "출발", 0, 700, bearingDeg = 90f, turnType = 200, pointType = "SP"),
                WalkingRouteGuidePoint(1, point(600.0, 0.0), "좌회전", 600, 100, bearingDeg = 0f, turnType = 12, pointType = "guide")),
            providerRouteId = "SIMULATED_MEASURED_INPUT_$runId")
        graph.run.selectDestination(route.polyline.last())
        graph.run.markRequestActive()
        graph.acquisition.value.installRequestedRoute(route, route.polyline.last(),
            requireNotNull(graph.positioning.value.trustedLocation), null, "user_destination", approach)
        report("ROUTE_INSTALLED", "simulated=true approach=$approach original_fix_ms=${graph.positioning.value.trustedLocation?.elapsedRealtimeMs}")
    }

    fun firstStartedSpeech(): SpeechAttempt {
        await("first actual navigation START", 20_000L) { speech.firstOrNull()?.startedAtMs != null }
        return onMain { speech.first() }
    }
    fun burstGps(durationMs: Long = 1_250L, offsetM: Double = 1_000.0) {
        badGpsOffsetM = offsetM
        badGpsUntilMs = now() + durationMs
        recoveryStartedMs = badGpsUntilMs
        report("GPS_BURST", "simulated_ground_truth_east_m=50.0 simulated_ground_truth_north_m=$initialNorthM raw_offset_m=$offsetM duration_ms=$durationMs requested_period_ms=500")
    }
    fun faultHeading(durationMs: Long = 650L) { badHeadingUntilMs = now() + durationMs; recoveryStartedMs = badHeadingUntilMs }
    fun approachRoute() { approachStartedMs = now(); report("APPROACH", "test_coordinate_speed_mps=1.2 original_started_ms=$approachStartedMs") }
    fun awaitCompleted(attempt: SpeechAttempt) {
        assertTrue("Native completion timeout: ${attempt.message}", attempt.terminal.await(30L, TimeUnit.SECONDS))
        attempt.assertCompletedOnce()
    }
    fun awaitRecoveredGps() = await("fresh recovered GPS after original fault", 8_000L) {
        rawGps.count { it.bad } >= 2 && rawGps.any { !it.bad && it.atMs > recoveryStartedMs && it.trustedAfterMs == it.atMs }
    }
    fun assertGpsBurstPreservedOriginalFix() = onMain {
        val bad = rawGps.filter { it.bad }
        assertTrue("Need multiple independent corrupt coordinates", bad.size >= 2)
        bad.forEach {
            assertEquals("The estimator must actually classify each jump", GnssObservationDisposition.HARD_REJECTED, it.disposition)
            assertEquals(GnssHardRejectReason.CORRUPT_JUMP, it.rejection)
            assertTrue("Original measurement precedes its receipt", it.atMs <= it.receiptMs)
            assertEquals("Rejected input must not renew trusted measurement", it.trustedBeforeMs, it.trustedAfterMs)
            assertEquals("Rejected input must not renew route raw measurement", it.rawBeforeMs, it.rawAfterMs)
            assertNotEquals("Rejected coordinate cannot become accepted original time", it.atMs, it.trustedAfterMs)
            assertEquals("Transient jump must retain measured gate PASS", "PASS", it.gate)
        }
    }
    fun assertSoftBurstDownweightedAndRecovered(attempt: SpeechAttempt) = onMain {
        val bad = rawGps.filter { it.bad }
        assertTrue("Need independent GPS values throughout the short burst", bad.size >= 2)
        assertTrue("The actual batch must remain shorter than corrupt-burst confirmation", bad.last().atMs - bad.first().atMs < 3_000L)
        assertTrue("Raw original timestamps must strictly increase", bad.zipWithNext().all { (first, second) -> second.atMs > first.atMs })
        bad.forEach { record ->
            assertEquals("200m is softened by the actual filter, not hard rejected", GnssObservationDisposition.DOWNWEIGHTED, record.disposition)
            assertNull(record.rejection)
            assertTrue("Soft gate must apply a weight below one", requireNotNull(record.gateWeight) > 0.0 && record.gateWeight < 1.0)
            assertEquals("Accepted weighted GPS keeps its real original time", record.atMs, requireNotNull(record.trustedAfterMs))
            assertEquals("Raw route observation is accepted and timestamped, not a rejected fix", record.atMs, requireNotNull(record.rawAfterMs))
            assertTrue("Stationary controlled baseline must not jump to the raw outlier", requireNotNull(record.filteredDistanceFromBaselineM) < record.rawDistanceFromBaselineM)
            assertEquals("PASS", record.gate)
        }
        val baseline = rawGps.last { !it.bad && it.atMs < bad.first().atMs }
        val recovered = rawGps.last { !it.bad && it.atMs > recoveryStartedMs }
        val recoveryAllowance = requireNotNull(baseline.filteredUncertaintyM) + requireNotNull(recovered.filteredUncertaintyM)
        assertTrue("Fresh GPS must restore the controlled baseline within combined reported uncertainty", requireNotNull(recovered.filteredDistanceFromBaselineM) <= recoveryAllowance)
        assertFalse("Short soft burst must not enter off-route or user decision", statuses.any {
            it.contains("offRoute=true") || it.contains("decisionRequired=true") || it.startsWith("navigation=direction_paused")
        })
        assertEquals("One actual route action acknowledgement", 1, acknowledged)
        assertEquals("The original action must not be replayed", 1, speech.count { it.message == attempt.message })
        synchronized(nativeEvents) {
            listOf(SpeechOutputOutcome.REQUESTED, SpeechOutputOutcome.STARTED, SpeechOutputOutcome.DONE).forEach { outcome ->
                assertEquals("One actual native navigation $outcome", 1, nativeEvents.count { it.kind == SpeechOutputKind.NAVIGATION && it.outcome == outcome })
            }
        }
        report("SOFT_GPS_ASSERT", "ground_truth=SIMULATED_STATIONARY first_original_ms=${bad.first().atMs} last_original_ms=${bad.last().atMs} original_intervals_ms=${bad.zipWithNext().map { (a, b) -> b.atMs - a.atMs }} filter_distances_m=${bad.map { it.filteredDistanceFromBaselineM }} recovered_original_ms=${recovered.atMs} recovered_filter_error_m=${recovered.filteredDistanceFromBaselineM} combined_uncertainty_m=$recoveryAllowance native_done_once=true")
    }

    fun awaitRecoveredHeading() {
        await("fault admitted by real compass and fresh recovery", 8_000L) {
            headingFaultObserved && headingGraceObserved && now() > recoveryStartedMs + 350L &&
                graph.compass.value.facing(now()) != null && !graph.run.navigator.isDirectionRecheckPending()
        }
        onMain {
            val episode = headingGraceSnapshots.filter { it.onsetElapsedRealtimeMs != null }
            assertTrue("Actual compass must produce a direction recovery episode", episode.isNotEmpty())
            val first = episode.first()
            episode.forEach { snapshot ->
                assertEquals("Repeated fault must retain original onset", first.onsetElapsedRealtimeMs, snapshot.onsetElapsedRealtimeMs)
                assertEquals("Repeated fault must not extend the original deadline", first.deadlineElapsedRealtimeMs, snapshot.deadlineElapsedRealtimeMs)
                assertEquals(requireNotNull(snapshot.onsetElapsedRealtimeMs) + GuidanceRecoveryTiming.DIRECTION_GRACE_MS,
                    requireNotNull(snapshot.deadlineElapsedRealtimeMs))
                assertTrue("Recovery is verified before actual deadline; expiry is not simulated", now() < requireNotNull(snapshot.deadlineElapsedRealtimeMs))
            }
        }
    }
    fun awaitJoined(): Long {
        await("new GPS makes actual navigator join route", 45_000L) {
            approachStartedMs != null && rawGps.any { it.atMs > requireNotNull(approachStartedMs) && it.northM < 1.0 } &&
                !graph.run.navigator.isWaitingForInitialRouteJoin() && graph.run.onRoute
        }
        return onMain { requireNotNull(graph.positioning.value.rawRouteLocation).elapsedRealtimeMs }
    }
    fun awaitNormalSpeechAfter(earlier: SpeechAttempt): SpeechAttempt {
        await("native normal route instruction after join", 25_000L) {
            speech.any { it !== earlier && !it.message.contains("가장 가까운 경로") && it.startedAtMs != null }
        }
        return onMain { speech.first { it !== earlier && !it.message.contains("가장 가까운 경로") && it.startedAtMs != null } }
    }
    fun assertHealthyCompleted(attempt: SpeechAttempt) = onMain {
        attempt.assertCompletedOnce()
        assertTrue("Original action must be acknowledged after native DONE", acknowledged >= 1)
        assertNotNull(lastResult)
        assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
        assertTrue(graph.run.active)
        assertNull("Brief fault must not create persistent direction pause", graph.run.directionPauseReason)
        assertNull("Brief fault must not require explicit route decision", graph.run.navigator.pendingDecisionToken())
        assertEquals("PASS", evidence.gpsEvidence?.status?.name)
        synchronized(nativeEvents) {
            assertFalse("Transient inputs must not emit problem speech", nativeEvents.any { it.kind == SpeechOutputKind.INTERACTION && it.outcome == SpeechOutputOutcome.REQUESTED })
            assertFalse("Native speech interrupted/failed: $nativeEvents", nativeEvents.any { it.outcome in setOf(
                SpeechOutputOutcome.CANCELLED_BY_PRIORITY, SpeechOutputOutcome.CANCELLED_STALE_NAVIGATION,
                SpeechOutputOutcome.INTERRUPTED, SpeechOutputOutcome.STOPPED, SpeechOutputOutcome.ENGINE_ERROR,
                SpeechOutputOutcome.TERMINAL_TIMEOUT, SpeechOutputOutcome.FOCUS_DENIED, SpeechOutputOutcome.FOCUS_LOST) })
        }
        report("ASSERT", "native_action_done_once=true ack=$acknowledged lifecycle=ACTIVE gate=${evidence.gpsEvidence?.status} direction_pause=null")
    }

    fun assertApproachCompleted(first: SpeechAttempt, normal: SpeechAttempt) = onMain {
        first.assertCompletedOnce()
        normal.assertCompletedOnce()
        assertEquals(normal.message, lastResult)
        assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
        assertTrue(graph.run.active && graph.run.onRoute)
        assertNull(graph.run.directionPauseReason)
        assertNull(graph.run.navigator.pendingDecisionToken())
        assertEquals("PASS", evidence.gpsEvidence?.status?.name)
        assertEquals("Each completed action must be acknowledged exactly once", speech.count { it.completed.get() == 1 }, acknowledged)
        val retired = speech.filter { it.failed.get() > 0 }
        retired.forEach { attempt ->
            assertTrue("Only an intermediate initial-join reference can retire", attempt !== first && attempt !== normal && attempt.initialJoin)
            assertTrue(attempt.message.contains("가장 가까운 경로") && attempt.message.contains("직선거리 약"))
            assertEquals("Retired owner has no completion", 0, attempt.completed.get())
            assertEquals("Retired owner terminates once", 1, attempt.failed.get())
            val cancellation = requireNotNull(attempt.cancelObservation) { "Missing actual production cancel-port observation" }
            val nativeAt = requireNotNull(attempt.nativeCancelledAtMs) { "Missing actual native CANCELLED_STALE_NAVIGATION" }
            assertNotNull("Capture the actual reserved cue token", attempt.cue)
            assertNull("Expected boundary is an already retired pending cue", cancellation.pending)
            assertNotEquals(attempt.cue, cancellation.pending)
            assertEquals(attempt.routeId, cancellation.routeId)
            assertEquals(attempt.routeVersion, cancellation.routeVersion)
            assertTrue("Cancellation must follow its native START", requireNotNull(attempt.startedAtMs) <= cancellation.atMs)
            assertTrue("Native cancellation must belong to that forwarding call", nativeAt >= cancellation.atMs)
            assertTrue("Only references after the first completed utterance retire", attempt.submittedAtMs > first.submittedAtMs)
            assertTrue("Retirement precedes normal route speech", nativeAt < requireNotNull(normal.startedAtMs))
            assertTrue("A distinct fresh GPS owns the changed geometry", requireNotNull(cancellation.rawOriginalMs) > requireNotNull(attempt.originalRawMs))
            assertTrue("Actual current coordinate is closer to the same route", requireNotNull(cancellation.straightDistanceM) < requireNotNull(attempt.originalStraightDistanceM))
            report("APPROACH_REFERENCE_RETIRED", "actual_token=${attempt.cue} observed=$cancellation native_cancel_ms=$nativeAt explicit_cancel_predicate_false=NOT_OBSERVED extra_predicate_calls=0")
        }
        synchronized(nativeEvents) {
            assertFalse("Healthy approach must not emit a location/sensor warning", nativeEvents.any { it.kind == SpeechOutputKind.INTERACTION && it.outcome == SpeechOutputOutcome.REQUESTED })
            assertEquals("Every stale cancellation must match a bounded retired approach owner", retired.size,
                nativeEvents.count { it.kind == SpeechOutputKind.NAVIGATION && it.outcome == SpeechOutputOutcome.CANCELLED_STALE_NAVIGATION })
            assertFalse("Unexpected priority interruption or native failure", nativeEvents.any { it.outcome in setOf(
                SpeechOutputOutcome.CANCELLED_BY_PRIORITY, SpeechOutputOutcome.INTERRUPTED, SpeechOutputOutcome.STOPPED,
                SpeechOutputOutcome.ENGINE_ERROR, SpeechOutputOutcome.TERMINAL_TIMEOUT, SpeechOutputOutcome.FOCUS_DENIED, SpeechOutputOutcome.FOCUS_LOST) })
        }
        report("APPROACH_ASSERT", "first_native_done=1 final_normal_native_done=1 intermediate_reference_retirements=${retired.size} warning_requests=0 extra_predicate_calls=0")
    }

    private val gpsTick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                val originalNs = SystemClock.elapsedRealtimeNanos()
                val at = originalNs / 1_000_000L
                val bad = at < badGpsUntilMs
                val approachAt = approachStartedMs
                val north = if (bad) initialNorthM + badGpsOffsetM else if (approachAt == null) initialNorthM
                    else max(0.0, initialNorthM - (at - approachAt) / 1000.0 * 1.2)
                val p = point(50.0, north)
                val sample = GnssSampleFingerprint(originalNs, System.currentTimeMillis(), "TEST_OWNED_SIMULATED_STREAM",
                    p.latitude, p.longitude, 3.0, false,
                    speedMps = if (approachAt == null || north == 0.0) 0.0 else 1.2,
                    bearingDegrees = if (approachAt == null) 90.0 else 180.0,
                    speedAccuracyMps = 0.1, bearingAccuracyDegrees = 5.0)
                val received = now()
                check(main.post {
                    try {
                        if (!running) return@post
                        val positioning = graph.positioning.value
                        val binding = requireNotNull(positioning.binding)
                        val trustedBefore = positioning.trustedLocation?.elapsedRealtimeMs
                        val rawBefore = positioning.rawRouteLocation?.elapsedRealtimeMs
                        var actualSnapshot: PositioningSnapshot? = null
                        activeGpsInputAtMs = at
                        observedInputSnapshot = null
                        positioning.handleLocationObservation(PositioningLocationObservation(sample, received), binding.owner,
                            binding.locationGeneration, binding, {}, { snapshot, matched ->
                                actualSnapshot = snapshot
                                report("POSITION_RESULT", "input_ms=$at receiver_ms=$received matched=$matched snapshot=$snapshot")
                            })
                        val admittedSnapshot = actualSnapshot ?: observedInputSnapshot
                        activeGpsInputAtMs = null
                        observedInputSnapshot = null
                        val baseline = point(50.0, initialNorthM)
                        val filteredDistance = admittedSnapshot?.filtered?.coordinate?.let { coordinate ->
                            haversineMeters(baseline.latitude, baseline.longitude, coordinate.latitude, coordinate.longitude)
                        }
                        val record = GpsRecord(at, received, bad, north, trustedBefore, positioning.trustedLocation?.elapsedRealtimeMs,
                            rawBefore, positioning.rawRouteLocation?.elapsedRealtimeMs, evidence.gpsEvidence?.status?.name,
                            admittedSnapshot?.gnssUpdate?.disposition, admittedSnapshot?.gnssUpdate?.hardRejectReason,
                            admittedSnapshot?.gnssUpdate?.gateWeight, filteredDistance,
                            haversineMeters(baseline.latitude, baseline.longitude, sample.latitude, sample.longitude),
                            admittedSnapshot?.filtered?.horizontalUncertaintyM)
                        rawGps += record
                        report("GPS_INPUT", "simulated=true raw_ns=$originalNs receiver_ms=$received consumer_ms=${now()} east_m=50.0 north_m=$north accuracy_m=3.0 mock_field=false hardware_claim=false outcome=$record")
                    } catch (failure: Throwable) { producerFailure.compareAndSet(null, failure) }
                })
            } catch (failure: Throwable) { producerFailure.compareAndSet(null, failure) }
            if (running) producer.postDelayed(this, 500L)
        }
    }
    private val headingTick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                val originalNs = SystemClock.elapsedRealtimeNanos()
                val at = originalNs / 1_000_000L
                val bad = at < badHeadingUntilMs
                val result = RouteCompassHeadingResult(RouteFacingObservation(90.0, if (bad) 35.0 else 5.0, at),
                    if (bad) RouteCompassHeadingReason.HEADING_ACCURACY else RouteCompassHeadingReason.VALID)
                heading.set(result)
                val received = now()
                if (trackerRunning) headingListener?.invoke(RouteCompassObservationEvent(1, RouteCompassStream.ROTATION_VECTOR,
                    originalNs, received, result, bad))
                if (bad || at % 500L < 50L) report("HEADING_INPUT", "simulated_projected_heading=true raw_ns=$originalNs receiver_ms=$received degrees=90.0 accuracy=${if (bad) 35 else 5} reason=${result.reason}")
                if (bad) check(main.post {
                    headingFaultObserved = true
                    if (graph.run.navigator.isDirectionRecheckPending()) {
                        headingGraceObserved = true
                        headingGraceSnapshots += requireNotNull(graph.run.navigator.currentDirectionGraceSnapshot())
                    }
                    report("DIRECTION_RESULT", "original_at_ms=$at consumer_ms=${now()} grace=${graph.run.navigator.currentDirectionGraceSnapshot()} pause=${graph.run.directionPauseReason}")
                })
            } catch (failure: Throwable) { producerFailure.compareAndSet(null, failure) }
            if (running) producer.postDelayed(this, 50L)
        }
    }

    private fun speechPort() = object : RouteGuidanceController.SpeechPort {
        override val commandGeneration get() = output.currentCommandGeneration()
        override val commandPending get() = output.hasPendingCommandResponse()
        override val validatingStarted get() = actuator?.validatingStartedSpeech == true
        override val statusText get() = actuator?.statusText()
        override fun busy() = commandPending || outputBusy()
        override fun outputBusy() = actuator?.isAppSpeechActive() == true
        override fun inputActive() = false
        override fun canSpeakNavigation(nowMs: Long) = !busy()
        override fun cancelNavigation() {
            speech.lastOrNull { it.startedAtMs != null && it.terminalCount() == 0 }?.let { attempt ->
                val raw = graph.positioning.value.rawRouteLocation
                attempt.cancelObservation = CancelObservation(now(), navigatorCue("pendingSpeechCue"), navigatorCue("offeredSpeechCue"),
                    graph.run.navigator.currentRouteId(), graph.run.navigator.currentTactileRouteProgress().routeVersion,
                    raw?.elapsedRealtimeMs, raw?.let(::straightDistanceToControlledRoute))
                report("PRODUCTION_CANCEL_PORT", "original_token=${attempt.cue} observed=${attempt.cancelObservation} predicate_false=NOT_OBSERVED extra_predicate_calls=0")
            }
            actuator?.cancelNavigationSpeech()
        }
        override fun cancelCommand() = output.cancelCommandSpeechResponse()
        override fun failCommand(generation: Long) = output.failRegisteredCommandResponse(CommandResponseToken("command-response-$generation", generation))
        override fun submitNavigation(message: String, isInstructionStillCurrent: () -> Boolean,
            onCompleted: () -> Unit, onFailed: () -> Unit): Boolean {
            val raw = graph.positioning.value.rawRouteLocation
            val attempt = SpeechAttempt(message, now(), graph.run.navigator.isWaitingForInitialRouteJoin(),
                navigatorCue("pendingSpeechCue"), graph.run.navigator.currentRouteId(),
                graph.run.navigator.currentTactileRouteProgress().routeVersion, raw?.elapsedRealtimeMs,
                raw?.let(::straightDistanceToControlledRoute))
            speech += attempt
            report("GUIDANCE_SUBMIT", "ordinal=${speech.size} text=$message")
            return output.dispatchNavigationSpeech(message, {
                attempt.completed.incrementAndGet()
                report("GUIDANCE_NATIVE_DONE", "original_submission_ms=${attempt.submittedAtMs} completed=${attempt.completed.get()} text=$message")
                onCompleted(); attempt.terminal.countDown()
            }, {
                attempt.failed.incrementAndGet(); onFailed(); attempt.terminal.countDown()
            }, {
                // Observe only invocations made by the real output controller/actuator.
                val current = isInstructionStillCurrent()
                report("PRODUCTION_VALIDATOR", "actual_token=${attempt.cue} original_submission_ms=${attempt.submittedAtMs} current=$current observed_ms=${now()}")
                current
            })
        }
        override fun submitStatus(message: String, current: () -> Boolean, completed: () -> Unit, failed: (() -> Unit)?) =
            output.speakCommandResponse(message, statusExplanation = true, additionalCurrent = current,
                onCompleted = completed, onFailed = failed, onCancelled = failed, onRetired = failed)
        override fun submitInstalledStart(message: String, current: () -> Boolean, terminal: () -> Unit) =
            output.speakCommandResponse(message, statusExplanation = true, additionalCurrent = current,
                onCompleted = terminal, onFailed = terminal, onCancelled = terminal, onRetired = terminal)
    }
    /** Read-only metadata on this fixture-owned Navigator; no setter or policy invocation. */
    private fun navigatorCue(name: String): RouteSpeechCueToken? =
        RouteNavigator::class.java.getDeclaredField(name).apply { isAccessible = true }.get(graph.run.navigator) as? RouteSpeechCueToken
    private fun straightDistanceToControlledRoute(location: TrustedLocation): Double {
        val closest = point(50.0, 0.0)
        return haversineMeters(location.latitude, location.longitude, closest.latitude, closest.longitude)
    }
    private fun owner() = LocationCollectionOwner(actor, session, 1L).takeIf { foreground && permission && active() }
    private fun active() = lifecycle.snapshot().state == WalkSessionState.ACTIVE
    private fun allowed() = foreground && permission && active()
    private fun status(text: String) { statuses += text; report("STATUS", "text=$text") }
    fun <T> onMain(action: () -> T): T {
        val value = AtomicReference<T>()
        val failure = AtomicReference<Throwable?>()
        instrumentation.runOnMainSync { try { value.set(action()) } catch (error: Throwable) { failure.set(error) } }
        failure.get()?.let { throw it }
        return value.get()
    }
    fun await(label: String, timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = now() + timeoutMs
        while (now() < deadline) {
            producerFailure.get()?.let { throw AssertionError("Producer/consumer failed: $label", it) }
            if (onMain(predicate)) return
            // Actual scheduler wait, never a callback injection or clock modification.
            val tick = CountDownLatch(1)
            check(main.postDelayed({ tick.countDown() }, 50L))
            assertTrue("Main Handler stalled: $label", tick.await(2L, TimeUnit.SECONDS))
        }
        throw AssertionError("Timed out: $label; ${onMain { "raw=${rawGps.lastOrNull()} speech=${speech.map { "${it.message}:${it.startedAtMs}:${it.completed}:${it.failed}" }} status=${statuses.takeLast(6)}" }}")
    }
    override fun close() {
        running = false
        producer.removeCallbacksAndMessages(null)
        try {
            onMain {
                foreground = false
                graph.positioning.value.stopLocationUpdates(false)
                graph.compass.value.stopRouteCompassPresentationUpdates()
                trackerRunning = false
                headingListener = null
                output.closeActuator()
                main.removeCallbacksAndMessages(null)
            }
        } finally {
            producerThread.quitSafely()
            producerThread.join(3_000L)
            assertFalse("Test-owned producer did not stop", producerThread.isAlive)
            report("CLEANUP", "physical_registrations=0 persistent_writes=0 producer_stopped=true")
        }
    }
    companion object {
        fun now() = SystemClock.elapsedRealtime()
        fun point(east: Double, north: Double) = RoutePoint(37.5 + north / 111_195.0,
            127.0 + east / (111_195.0 * cos(Math.toRadians(37.5))))
        fun report(event: String, values: String) {
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "MEASURED_INPUT_GUIDANCE event=$event at_ms=${now()} $values\n")
            })
        }
        /** Only unused UI/context ports have harmless defaults; collection and speech are typed. */
        private inline fun <reified T> port(noinline result: (String, Array<out Any?>) -> Any? = { _, _ -> null }): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
                when (method.name) {
                    "toString" -> "SimulatedMeasuredInputPort<${T::class.java.simpleName}>"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    else -> result(method.name, args ?: emptyArray()) ?: when (method.returnType) {
                        java.lang.Boolean.TYPE -> false; java.lang.Integer.TYPE -> 0; java.lang.Long.TYPE -> 0L
                        java.lang.Float.TYPE -> 0f; java.lang.Double.TYPE -> 0.0; java.lang.Void.TYPE -> Unit; else -> null
                    }
                }
            } as T
    }
}

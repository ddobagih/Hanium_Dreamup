package kr.co.hanium.dreamup.walksafe

import android.location.Location
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.lang.reflect.InvocationTargetException
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.device.RuntimeMeasuredQualityGate
import kr.co.hanium.dreamup.walksafe.device.environment.*
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.navigation.session.*
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.session.permission.*
import kr.co.hanium.dreamup.walksafe.voice.output.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*

internal data class GuidanceSensorReplayCase(val input: JSONObject) {
    val id = input.getString("id").also { require(it.matches(Regex("[a-z0-9_]+"))) }
    val scope = input.getString("scope").also { require(it == "ADMITTED_WALK_HEADING_OBSERVATION") }
    val initial: JSONObject get() = input.getJSONObject("initial")
    override fun toString() = id
}

/** Actual Main/Positioning/Compass/Guide/Nav/Output/Actuator. SDK observations and callbacks are leaves.
 * The existing cold fixture supplies the initial admitted account/walk, initial original fixes and
 * installed route. This is expressly ADMITTED_WALK, not an exercised home/start/readiness UI.
 * Heading input is HEADING_OBSERVATION, not a physical 3D rotation-vector conversion test.
 * All later GPS packets go through the production handleLocationObservation policy; clock advance never refreshes raw fixes.
 * RouteCompassSession's actual scheduling/evaluation path runs against a controlled timer callback;
 * Android View handler registration and device callback dispatch remain outside this replay.
 */
internal class GuidanceSensorReplayHarness(private val case: GuidanceSensorReplayCase) : AutoCloseable {
    private val trace = JSONArray()
    private val assertions = JSONArray()
    private val nativeRuntimes = mutableListOf<NativeRuntime>()
    private val cold = Class.forName(MainActivityColdFirstDirectionFlowIntegrationTest::class.java.name + "\$ColdFixture")
        .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
    private val base: MainActivityContinuousLocationStartBehaviorTest.Fixture = read(cold, "base")
    private val main get() = base.main
    private val guide: RouteGuidanceController = read(cold, "guide")
    private val positions: PositioningSessionController = read(cold, "positions")
    private val output: SpeechOutputController = read(main, "speechOutputController")
    private val run: NavigationRunState = read(guide, "navigationRunState")
    private val navigator get() = run.navigator
    private val clock = ReplayClock(base)
    private lateinit var binding: PositioningCollectionBinding
    private lateinit var compass: RouteCompassSession
    private var permissionGranted = true
    private var trackerRegistered = true
    private var trackerGeneration = 1
    private var trackerObservationListener: ((RouteCompassObservationEvent) -> Unit)? = null
    private var trackerHeading = RouteCompassHeadingResult(null, RouteCompassHeadingReason.ORIENTATION_MISSING)
    private var origin: Long? = null
    private var initialRouteId: String? = null
    private var initialOriginalGpsAt: Long? = null
    private var initialCompletedCue: RouteSpeechCueToken? = null
    private var firstGpsDeadline: Long? = null
    private var firstDirectionDeadline: Long? = null
    private var firstFaultAt: Long? = null
    private var errorInjected = false
    private var freshRequests = 0
    private var freshCancels = 0
    private var assertionFailure = false
    private var retiredCompletedCue: RouteSpeechCueToken? = null
    private var retiredRouteRequests: Int? = null
    private var hardDirectionRetired = false
    private var retiredUtterances = emptyList<Spoken>()
    private var inputAdapterGap = false
    private var phase = "BOOTSTRAP"
    private var persistentResponseAt: Long? = null
    private var persistentResponseCompletedAt: Long? = null
    private var persistentRouteBaseline: Int? = null
    private var persistentRetryNotBeforeAtResponse: Long? = null
    private var persistentRecoveredRouteAt: Long? = null
    private var persistentFailedCueAtBackoff: RouteSpeechCueToken? = null
    private var persistentFailedCueInvalidatedAt: Long? = null
    private var persistentRetiredCompassOwner: RouteCompassSession.CompassObservationOwner? = null
    private var persistentReplacementCompassOwner: RouteCompassSession.CompassObservationOwner? = null
    private var persistentRecoveredCue: RouteSpeechCueToken? = null
    private var persistentRecoveryDoneTraceIndex: Int? = null
    private var persistentFreshHeadingTraceIndex: Int? = null
    private var persistentRecoveredRouteTraceIndex: Int? = null
    private var productionScheduleLease: RouteCompassSession.ScheduleLease? = null
    private val coreClasses = listOf(MainActivity::class.java, PositioningSessionController::class.java,
        RouteCompassSession::class.java, RouteGuidanceController::class.java, RouteNavigator::class.java,
        SpeechOutputController::class.java, AndroidFeedbackActuator::class.java)

    init {
        val sources = coreClasses.map(::classSource).distinct()
        check(sources.size == 1) { "Mixed product class sources: $sources" }
        log("BOUNDARY", JSONObject().put("bootstrap", "ADMITTED_WALK")
            .put("heading", "HEADING_OBSERVATION")
            .put("location_input", "IMMUTABLE_ORIGINAL_GNSS_OBSERVATION")
            .put("sdk_adapter_executed", false)
            .put("sdk_leaves", JSONArray(listOf("admitted account/walk/initial route", "location permission and registration",
                "heading observation and tracker generation", "screen-reader query", "native TTS/focus callbacks",
                "camera/common environment assessment", "Android view/storage effects"))))
        put(main, "locationPermissionCheck", { permissionGranted })
        put(main, "locationServiceEnabledReader", { true })
        put(main, "navigationStateDebugLogger", { tag: String, message: String ->
            log("NAVIGATION_DEBUG", JSONObject().put("tag", tag).put("message", message))
        })
        put(main, "permissionFlowStatusPresenter", { status: String, detail: String ->
            log("PERMISSION_STATUS", JSONObject().put("status", status).put("detail", detail))
        })
        invoke(cold, "acquire", case.initial.optDouble("gps_accuracy_m", 5.0))
        invoke(cold, "requestAndInstall", case.initial.optBoolean("approach", false))
        binding = base.binding as PositioningCollectionBinding
        initialRouteId = navigator.currentRouteId()
        check(base.lifecycle.snapshot().state == WalkSessionState.ACTIVE)
        check(read<RouteNavigatorConfig>(navigator, "config").directionRecheckGraceMs == GuidanceRecoveryTiming.DIRECTION_GRACE_MS)
        check(read<Long>(base.gate, "maximumTransientFailureMs") == GuidanceRecoveryTiming.GPS_RECHECK_PERIOD_MS)
        check(read<Int>(base.gate, "gpsFailureConfirmationPeriods") == GuidanceRecoveryTiming.GPS_RECHECK_PERIODS)
        installClockAndOutput()
        installLocationLeaves()
        installEnvironmentLeaves()
        connectHeadingObservation()
        bootstrapIntro()
    }

    fun replay() {
        data class Step(val at: Long, val assertion: Boolean, val order: Int, val value: JSONObject)
        val steps = mutableListOf<Step>()
        for ((key, assertion) in listOf("events" to false, "expectations" to true)) {
            val input = case.input.getJSONArray(key)
            for (index in 0 until input.length()) {
                val value = input.getJSONObject(index)
                val at = value.getLong("at_ms")
                require(at >= 0) { "Negative replay receipt time" }
                steps += Step(at, assertion, index, value)
            }
        }
        require(steps.any { it.assertion }) { "Replay case has no assertions" }
        for (step in steps.sortedWith(compareBy<Step> { it.at }.thenBy { it.assertion }.thenBy { it.order })) {
            clock.advanceTo(requireNotNull(origin) + step.at)
            if (step.assertion) assertContract(step.value) else event(step.value)
            clock.advanceBy(0)
            capture()
        }
    }

    private fun event(event: JSONObject) {
        log("INPUT", event)
        when (event.getString("type")) {
            "gps" -> gps(event)
            "gps_unavailable" -> if (sdkCollectionCurrent()) positions.handlePositioningUnavailable(clock.now)
            "heading" -> heading(event)
            "heading_unavailable" -> heading(event, unavailable = true)
            "permission" -> {
                if (!event.getBoolean("granted")) rememberRetiredOwner()
                permissionGranted = event.getBoolean("granted")
                put(cold, "locationGranted", permissionGranted) // same controlled Android permission leaf used by actual Guide facts
                try {
                    val flow = MainActivityControllerTestFixture.controller(main, "permissionFlowController") as PermissionFlowController<*>
                    flow.applyObservedPermissionStateChange("sensor_replay_permission_change")
                } catch (failure: RuntimeException) {
                    if (failure.message?.contains("not mocked") == true) {
                        inputAdapterGap = true
                        log("INPUT_ADAPTER_GAP", JSONObject().put("entry", "applyObservedPermissionStateChange").put("failure", failure.toString()))
                    }
                    throw failure
                }
            }
            "owner" -> { rememberRetiredOwner(); when (event.getString("change")) {
                "foreground" -> { base.set("isActivityForeground", false); base.lifecycle.handle(WalkSessionEvent.EnteredBackground) }
                "epoch" -> base.lifecycle.handle(WalkSessionEvent.EnteredBackground)
                "session" -> GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
                "actor" -> {
                    val actor="actor_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                    val login=FirstRunOnboardingEvidence.VerifiedLogin(FirstRunOpaqueActorBinding.fromProvider(actor),
                        FirstRunReceiptHash.fromSha256Hex("b".repeat(64)))
                    val restored=FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(1L,listOf(login),
                        FirstRunOnboardingEvidenceVerifier{token,value->value==login&&token.stage==login.stage})
                    check(restored.fullyRestored)
                    val replacement=kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession.verified(
                        "https://gateway.example.test",actor,"walksafe_field_session=v2.replay-replacement",Long.MAX_VALUE)
                    val operation=requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
                    check(GatewaySessionProcessCoordinator.publishVerified(operation,replacement,restored.snapshot))
                    base.set("reporterUserId",actor)
                    base.set("firstRunOnboardingSnapshot",restored.snapshot)
                    log("ACCOUNT_OWNER_PUBLICATION",JSONObject()
                        .put("gateway_generation",GatewaySessionProcessCoordinator.snapshot().generation)
                        .put("same_original_session",replacement===binding.owner.gatewaySession))
                }
                "location_generation" -> base.set("locationCallbackGeneration", positions.callbackGeneration + 1)
                "input_generation" -> binding.coordinator.reset()
                "route_reset" -> navigator.clear()
                else -> error("UNKNOWN_OWNER_CHANGE: ${event.getString("change")}")
            } }
            "tts" -> {
                val target = event.optString("utterance", "latest")
                val spoken = allSpoken().let { values -> when (target) {
                    "first_route" -> values.firstOrNull { it.role == "ROUTE" }
                    "latest" -> values.lastOrNull()
                    else -> values.firstOrNull { it.id == target }
                } } ?: error("No requested utterance: $target")
                spoken.runtime.callback(event.getString("callback"), spoken.id, event.optInt("error_code", TextToSpeech.ERROR), scripted = true)
            }
            else -> error("UNKNOWN_EVENT: ${event.getString("type")}")
        }
    }

    private fun gps(event: JSONObject) {
        val kind = event.optString("accuracy_kind", if (event.isNull("accuracy_m")) "missing" else "reported")
        val accuracy = when (kind) {
            "nan" -> Double.NaN
            "missing" -> null
            "reported" -> event.getDouble("accuracy_m").toFloat().toDouble()
            else -> error("UNKNOWN_ACCURACY_KIND: $kind")
        }
        val observation = PositioningLocationObservation(
            sample = GnssSampleFingerprint(
                elapsedRealtimeNanos = (clock.now - event.optLong("age_ms", 0)) * 1_000_000L,
                timeMillis = 0L,
                provider = "fused",
                latitude = 37.5 + event.optDouble("north_m", 0.0) / 111_195.0,
                longitude = 127.0 + event.optDouble("east_m", 0.0) / (111_195.0 * kotlin.math.cos(Math.toRadians(37.5))),
                horizontalAccuracyM = accuracy,
                mock = event.optBoolean("mock", false),
            ),
            receivedAtElapsedRealtimeMs = clock.now,
        )
        positions.handleLocationObservation(observation, binding.owner, binding.locationGeneration, binding,
            updateOrientationReference = {}, // Geomagnetic/physical 3D SDK conversion is outside this replay.
            appendGnssTrace = { snapshot, _ ->
                log("GNSS_CONSUMED", JSONObject().put("measured_at_ms", observation.sample.elapsedRealtimeNanos / 1_000_000L)
                    .put("received_at_ms", observation.receivedAtElapsedRealtimeMs).put("quality", snapshot.quality.name)
                    .put("disposition", snapshot.gnssUpdate?.disposition?.name).put("reject_reason", snapshot.gnssUpdate?.hardRejectReason?.name))
            })
        log("GPS_INPUT_RECEIPT", JSONObject().put("input_measured_at_ms", observation.sample.elapsedRealtimeNanos / 1_000_000L)
            .put("input_measured_at_ns", observation.sample.elapsedRealtimeNanos)
            .put("input_received_at_ms", observation.receivedAtElapsedRealtimeMs)
            .put("location_input", "IMMUTABLE_ORIGINAL_GNSS_OBSERVATION")
            .put("published_original_raw_at_ms", positions.rawRouteLocation?.elapsedRealtimeMs)
            .put("published_original_filtered_at_ms", positions.trustedLocation?.elapsedRealtimeMs))
    }

    private fun heading(event: JSONObject, unavailable: Boolean = false) {
        val kind = event.optString("accuracy_kind", if (event.isNull("accuracy_deg")) "missing" else "reported")
        val error = when (kind) {
            "missing" -> null
            "nan" -> Double.NaN
            "reported" -> event.getDouble("accuracy_deg")
            else -> error("UNKNOWN_ACCURACY_KIND: $kind")
        }
        val measuredAt = clock.now - event.optLong("age_ms", 0)
        val observation = if (unavailable) null else RouteFacingObservation(
            event.getDouble("degrees"), error, measuredAt, RouteFacingSource.ROTATION_VECTOR,
            RouteFacingSensorQuality.REPORTED_ANGULAR_ERROR, reportedHeadingAccuracyDegrees = error)
        val result = RouteCompassHeadingResult(observation,
            if (unavailable) RouteCompassHeadingReason.valueOf(event.getString("reason")) else RouteCompassHeadingReason.VALID)
        if (sdkCollectionCurrent() && navigator.hasHardInvalidatedDirectionClaim(clock.now, observation)) {
            rememberRetiredOwner()
            hardDirectionRetired = true
        }
        if (sdkCollectionCurrent() && trackerRegistered) put(cold, "compass", observation)
        val input = RouteCompassObservationEvent(trackerGeneration, RouteCompassStream.ROTATION_VECTOR,
            measuredAt * 1_000_000L, clock.now, result, unavailable)
        trackerHeading = result
        requireNotNull(trackerObservationListener)(input)
        clock.advanceBy(0)
    }

    private fun sdkCollectionCurrent(): Boolean {
        val account = GatewaySessionProcessCoordinator.snapshot()
        return permissionGranted && read<Boolean>(main, "isActivityForeground") && base.lifecycle.snapshot().isForeground &&
            base.lifecycle.isRuntimeEpochCurrent(base.epoch) && account.session === binding.owner.gatewaySession &&
            account.generation == binding.owner.gatewayGeneration && !account.storageBlocked && !account.deletionRecoveryOnly &&
            base.field("reporterUserId") == binding.owner.actorId && positions.binding === binding &&
            positions.callbackGeneration == binding.locationGeneration && binding.coordinator.currentInputGeneration() == binding.inputGeneration
    }

    private fun installLocationLeaves() {
        val permissions = MainActivityControllerTestFixture.controller(main, "permissionFlowController")
        val platform: PermissionPlatform = read(permissions, "platform")
        put(permissions, "platform", object : PermissionPlatform by platform {
            override fun cameraGranted() = true
            override fun microphoneGranted() = true
            override fun activityRecognitionGranted() = true
            override fun notificationGranted() = true
            override fun preciseLocationGranted() = permissionGranted
            override fun coarseLocationGranted() = permissionGranted
        })
        val readiness: PermissionWalkReadiness = read(permissions, "readiness")
        put(permissions, "readiness", object : PermissionWalkReadiness by readiness {
            override fun locationServiceEnabled() = true
        })
        val scope: PositioningSessionController.Scope = read(positions, "scope")
        put(positions, "scope", object : PositioningSessionController.Scope by scope {
            override fun currentOwner() = binding.owner.takeIf { sdkCollectionCurrent() }
            override val fineLocationGranted get() = permissionGranted
        })
        val source: PositioningSessionController.Sources = read(positions, "sources")
        put(positions, "sources", object : PositioningSessionController.Sources by source {
            override val orientationAvailable = false // no geomagnetic/physical 3D SDK conversion in this scope
        })
        put(positions, "diagnostics", read<RouteGuidanceController.Diagnostics>(guide, "diagnostics"))
        val effects: PositioningSessionController.Effects = read(positions, "effects")
        put(positions, "effects", object : PositioningSessionController.Effects by effects {
            override fun navigationStatus(text: String) { log("POSITION_STATUS", JSONObject().put("text", text)) }
        })
        val gps = MainActivityControllerTestFixture.controller(main, "continuousGpsSession")
        put(gps, "clock", { clock.now })
        val actions: ContinuousGpsSession.Actions = read(gps, "actions")
        put(gps, "actions", object : ContinuousGpsSession.Actions by actions {
            override fun requestFresh(result: (Location?) -> Unit): () -> Unit {
                freshRequests++
                log("GPS_RECHECK_REQUEST")
                val cancel = actions.requestFresh(result)
                return { freshCancels++; log("GPS_RECHECK_CANCEL"); cancel() }
            }
        })
    }

    private fun installEnvironmentLeaves() {
        val runtime = MainActivityControllerTestFixture.controller(main, "officialEnvironmentRuntimeController")
        put(runtime, "nowMs", { clock.now })
        put(runtime, "schedule", { delay: Long, action: () -> Unit -> clock.post(runtime, Runnable(action), delay) })
        val facts: OfficialEnvironmentRuntimeFactsReader = read(runtime, "facts")
        put(runtime, "facts", object : OfficialEnvironmentRuntimeFactsReader by facts {
            override fun cameraEnabled() = true
            override fun locationEnabled() = true
            override fun currentAssessment(epoch: WalkRuntimeEpoch, nowMs: Long): OfficialEnvironmentAssessment {
                val actual = facts.currentAssessment(epoch, nowMs)
                return actual.copy(factorStatuses = actual.factorStatuses.mapValues { (factor, status) ->
                    when (factor) {
                        OfficialEnvironmentFactor.GPS_QUALITY -> status
                        OfficialEnvironmentFactor.CAMERA_QUALITY -> if (case.initial.optBoolean("camera_healthy", true))
                            EnvironmentEvidenceStatus.PASS else EnvironmentEvidenceStatus.UNKNOWN
                        else -> EnvironmentEvidenceStatus.PASS
                    }
                }, usageLimitsAcknowledged = true,
                    enabledMeasuredFactors = setOf(OfficialEnvironmentFactor.GPS_QUALITY, OfficialEnvironmentFactor.CAMERA_QUALITY))
            }
        })
        val effects: OfficialEnvironmentRuntimeEffects = read(runtime, "effects")
        put(runtime, "effects", object : OfficialEnvironmentRuntimeEffects by effects {
            override fun showStatus(title: String, detail: String) { log("ENVIRONMENT_STATUS", JSONObject().put("text", "$title: $detail")) }
            override fun speakStatusExplanation(message: String) { log("PERSISTENT_RESPONSE", JSONObject().put("text", message)); effects.speakStatusExplanation(message) }
        })
    }

    private fun installClockAndOutput() {
        val reader: SpeechOutputReader = read(output, "reader")
        put(output, "reader", object : SpeechOutputReader by reader { override fun screenReaderActive() = false })
        put(output, "scheduler", clock)
        put(output, "input", object : SpeechOutputInputEffects {
            override fun invalidateHomeWakeInput() = Unit
            override fun promptState(token: CommandResponseToken, effect: CommandPromptEffect) = Unit
            override fun cancelDeferredInput() = Unit
            override fun refreshHomeWakeListening() = Unit
            override fun cancelHomeWakeListening() = Unit
            override fun cancelCommandDialogs(token: CommandResponseToken) = Unit
        })
        put(output, "presentation", object : SpeechOutputPresentation {
            override fun showLastResult(message: String) { log("OUTPUT_STATUS", JSONObject().put("text", message)) }
            override fun showVoiceStatus(message: String) = Unit
            override fun refreshVoiceControls() = Unit
            override fun navigationStatus(message: String) { log("OUTPUT_STATUS", JSONObject().put("text", message)) }
            override fun commandTerminalDiagnostic(completed: Boolean) = Unit
            override fun accessibilityTargetOrNull(): SpeechAccessibilityTarget? = null
            override fun canScheduleInteraction() = true
            override fun postInteraction(task: Runnable, delayMs: Long) = clock.post(this, task, delayMs)
            override fun removeInteraction(task: Runnable) = clock.remove(this, task)
        })
        put(output, "storage", object : kr.co.hanium.dreamup.walksafe.voice.output.SpeechOutputStorageBinding {
            override fun invalidate() = Unit
            override fun refresh(actuator: AndroidFeedbackActuator) = Unit
        })
        put(output, "debugDiagnostic", { text: String -> log("OUTPUT_DIAGNOSTIC", JSONObject().put("text", text)) })
        put(output, "readyEffects", object : SpeechOutputReadyEffects {
            override fun refreshReadyUi() = Unit
            override fun reevaluateCurrentRisk(actuator: AndroidFeedbackActuator, lifecycleGeneration: Int, nowMs: Long) = Unit
            override fun retryCurrentNavigation(nowMs: Long) = guide.retryCurrentNavigationSpeechAfterReady(nowMs)
            override fun offlineSpeechUnavailable(lifecycleGeneration: Int, actuator: AndroidFeedbackActuator) { log("ENGINE_UNAVAILABLE") }
            override fun beforeCommandReplacement(previousGeneration: Long) = guide.failInterruptedRouteDecisionSpeech(previousGeneration)
        })
        put(output, "actuatorFactory", SpeechOutputActuatorFactory { unavailable, ready ->
            val native = NativeRuntime(nativeRuntimes.size).also { nativeRuntimes += it }
            AndroidFeedbackActuator(runtime = native, onOfflineKoreanSpeechUnavailable = unavailable, onOfflineKoreanSpeechReady = ready)
                .also { native.actor = it }
        })
        val assembly: NavigationSessionComposition = read(main, "navigationSessions")
        val bindings: GuidanceAssemblyBindings = read(assembly, "guides")
        val actual = bindings.speech()
        put(guide, "speech", object : RouteGuidanceController.SpeechPort by actual {
            override fun busy() = actual.inputActive() || output.hasPendingCommandResponse() || output.currentActuatorOrNull()?.isAppSpeechActive() == true
            override fun outputBusy() = output.hasPendingCommandResponse() || output.currentActuatorOrNull()?.isAppSpeechActive() == true
        })
        put(guide, "callbacks", object : RouteCallbackScheduler {
            override fun post(task: Runnable) = clock.post(this, task, 0)
            override fun postDelayed(task: Runnable, delayMs: Long) = clock.post(this, task, delayMs)
            override fun remove(task: Runnable) = clock.remove(this, task)
        })
        // The notice owner captures the same Android leaves at construction; replace those leaves too.
        // Notice reservation, retry, terminal and expiry decisions still execute in the production owner.
        val notices: Any = read(guide, "recheckNotices")
        put(notices, "speech", read<RouteGuidanceController.SpeechPort>(guide, "speech"))
        put(notices, "callbacks", read<RouteCallbackScheduler>(guide, "callbacks"))
        put(notices, "elapsedRealtimeMs", { clock.now })
    }

    private fun connectHeadingObservation() {
        compass = MainActivityControllerTestFixture.controller(main, "routeCompassSession") as RouteCompassSession
        put(compass, "elapsedRealtimeMs", { clock.now })
        put(compass, "tracker", object : RouteCompassSession.Tracker {
            override val available = true
            override fun listener(listener: (RouteCompassObservationEvent) -> Unit) { trackerObservationListener = listener }
            override fun start() { trackerRegistered = true }
            override fun stop() { trackerRegistered = false }
            override fun current(event: RouteCompassObservationEvent) = trackerRegistered &&
                event.registrationGeneration == trackerGeneration && sdkCollectionCurrent()
            override fun heading(nowMs: Long) = trackerHeading
        })
        put(compass, "callbacks", object : RouteCompassSession.Callbacks {
            override val viewReady = true
            private val scheduled = mutableMapOf<Runnable, Runnable>()
            override fun post(task: Runnable) { clock.post(this, task, 0L) }
            override fun postDelayed(task: Runnable, delayMs: Long): Boolean {
                val callback = Runnable {
                    scheduled.remove(task)
                    log("MAIN_ROUTE_COMPASS_SCHEDULE_TICK", JSONObject().put("delay_ms", delayMs))
                    val retryBefore = read<Long?>(navigator, "speechRetryNotBeforeMs")
                    val failedBefore: RouteSpeechCueToken? = read(navigator, "failedSpeechCue")
                    val ownerBefore: RouteCompassSession.CompassObservationOwner? = read(compass, "compassObservationOwner")
                    if (persistentResponseAt != null && retryBefore != null && failedBefore != null &&
                        persistentFailedCueAtBackoff == null
                    ) persistentFailedCueAtBackoff = failedBefore
                    log("MAIN_ROUTE_COMPASS_SCHEDULE_BEFORE", core())
                    task.run()
                    log("MAIN_ROUTE_COMPASS_SCHEDULE_AFTER", core())
                    if (persistentFailedCueAtBackoff != null && persistentFailedCueInvalidatedAt == null &&
                        retryBefore != null && read<Long?>(navigator, "speechRetryNotBeforeMs") == null &&
                        read<RouteSpeechCueToken?>(navigator, "failedSpeechCue") == null
                    ) {
                        persistentFailedCueInvalidatedAt = clock.now
                        persistentRetiredCompassOwner = ownerBefore
                        persistentReplacementCompassOwner = read(compass, "compassObservationOwner")
                        log("FAILED_CUE_OWNER_RETIRED", JSONObject()
                            .put("old_feedback_generation", ownerBefore?.lifecycleGeneration)
                            .put("new_feedback_generation", persistentReplacementCompassOwner?.lifecycleGeneration)
                            .put("same_positioning_binding", ownerBefore?.binding === persistentReplacementCompassOwner?.binding))
                    }
                }
                scheduled[task] = callback
                clock.post(this, callback, delayMs)
                return true
            }
            override fun remove(task: Runnable) { scheduled.remove(task)?.let { clock.remove(this, it) } }
        })
        compass.ensureEarthOrientationForLocation()
        main.bindCompassObservationOwner()
        check(read<Any?>(compass, "compassObservationOwner") != null)
        put(guide, "compassInput", compass)
        val actualSource: RouteCompassSession.Source = read(compass, "source")
        productionScheduleLease = requireNotNull(actualSource.captureSchedule(compass.orientationActive))
        check(requireNotNull(productionScheduleLease).isCurrent())
        log("PRODUCTION_SCHEDULE_SCOPE_PROBE", JSONObject()
            .put("lease_available", true).put("lease_current", true))
    }

    private fun bootstrapIntro() {
        invoke(cold, "installIntro")
        val heading = JSONObject().put("type", "heading").put("degrees", case.initial.optDouble("heading_deg", 0.0))
            .put("accuracy_deg", case.initial.optDouble("heading_accuracy_deg", 3.0))
        heading(heading)
        guide.updateRouteGuidance(read(cold, "fix"), requireNotNull(positions.rawRouteLocation))
        val bootstrapOwner = Any()
        fun observeAgain() {
            clock.post(bootstrapOwner, Runnable { heading(heading); observeAgain() }, 300L)
        }
        observeAgain()
        val deadline = clock.now + 20_000L
        while (allSpoken().none { it.role == "ROUTE" } && clock.now < deadline) clock.advanceNext()
        clock.clear(bootstrapOwner)
        check(allSpoken().any { it.role == "INTRO" && it.done }) { "FIXTURE_BOOTSTRAP_INTRO_NOT_DONE" }
        check(allSpoken().any { it.role == "ROUTE" }) { "FIXTURE_BOOTSTRAP_FIRST_ROUTE_NOT_REQUESTED" }
        origin = allSpoken().first { it.role == "ROUTE" }.requestedAt
        check(clock.now == origin) { "Bootstrap origin must precede any replay time" }
        initialCompletedCue = read(navigator, "completedSpeechCue")
        initialOriginalGpsAt = requireNotNull(positions.rawRouteLocation).elapsedRealtimeMs
        phase = "REPLAY"
        log("REPLAY_ORIGIN", JSONObject().put("original_gps_at_ms", positions.rawRouteLocation?.elapsedRealtimeMs)
            .put("route_id", initialRouteId).put("state", base.lifecycle.snapshot().state.name))
        compass.scheduleRouteCompassPresentationUpdate()
    }

    private fun assertContract(expectation: JSONObject) {
        val assertion = expectation.getString("assert")
        val before = core()
        try {
            val routes = allSpoken().filter { it.role == "ROUTE" }
            val completed: RouteSpeechCueToken? = read(navigator, "completedSpeechCue")
            val grace = navigator.currentDirectionGraceSnapshot()
            val gps = base.gate.current(clock.now)
            val stops = nativeRuntimes.sumOf { it.stops }
            val kind = expectation.optString("kind", if (base.gate.gpsFailureStartedAtElapsedRealtimeMs() != null) "gps" else "direction")
            require(kind in setOf("gps", "direction")) { "UNKNOWN_GRACE_KIND: $kind" }
            val activeDeadline = if (kind == "gps") base.gate.pendingFailureDeadlineElapsedRealtimeMs() else grace?.deadlineElapsedRealtimeMs
            val firstDeadline = if (kind == "gps") firstGpsDeadline else firstDirectionDeadline
            when (assertion) {
                "clock_metres" -> assertTrue("Clock plus metres must be actually requested", routes.any { clockMetres(it.message) })
                "route_done" -> { assertNotNull("Native DONE must acknowledge a route cue", completed); assertTrue(routes.any { it.done && it.cue == completed }); assertNotEquals(initialCompletedCue, completed) }
                "no_route_stop" -> assertEquals("Unexpected native cancellation", 0, stops)
                "guidance_continues" -> { assertTrue(run.active); assertTrue(navigator.hasRoute()); assertEquals(WalkSessionState.ACTIVE, base.lifecycle.snapshot().state); assertFalse(guide.positionPaused); assertEquals(0, stops) }
                "grace_active" -> { assertNotNull("No $kind grace", activeDeadline); assertTrue(clock.now < requireNotNull(activeDeadline)); if (kind == "direction") assertEquals("GRACE_RETAINED", grace?.mode?.name) }
                "grace_onset_fixed" -> { assertNotNull("No first $kind deadline", firstDeadline); assertEquals(firstDeadline, activeDeadline) }
                "recovered" -> {
                    assertFalse(guide.positionPaused)
                    assertTrue(run.active)
                    assertEquals(EnvironmentEvidenceStatus.PASS, gps?.status)
                    assertNotEquals("PROBLEM", grace?.mode?.name)
                    if (persistentResponseAt != null) {
                        assertTrue("A delivered/current route cue must be retained while its retry delay is pending", routes.any {
                            it.current?.invoke() == true || (it.done && it.cue == completed)
                        } || clock.now < (read<Long?>(navigator, "speechRetryNotBeforeMs") ?: Long.MIN_VALUE))
                    } else assertTrue("Transient recovery lost its ongoing/delivered route", routes.any {
                        it.current?.invoke() == true || (it.done && it.cue == completed)
                    })
                }
                "automatic_route_resumed" -> {
                    val response = allSpoken().firstOrNull {
                        it.role == "RECOVERY" && it.requestedAt == persistentResponseAt
                    }
                    assertNotNull("No persistent recovery response preceded route recovery", response)
                    val responseDoneAt = requireNotNull(requireNotNull(response).completedAt) {
                        "Persistent recovery response did not complete"
                    }
                    val baseline = requireNotNull(persistentRouteBaseline)
                    val recoveredRoute = routes.drop(baseline).firstOrNull { it.requestedAt >= responseDoneAt }
                    assertNotNull("Persistent recovery never automatically requested a route after its recovery response completed", recoveredRoute)
                    persistentResponseCompletedAt = responseDoneAt
                    persistentRecoveredRouteAt = requireNotNull(recoveredRoute).requestedAt
                    val newCue = requireNotNull(recoveredRoute).cue
                    val failedCue = requireNotNull(persistentFailedCueAtBackoff) {
                        "The failed route cue was not captured while its retry deadline was active"
                    }
                    assertNotEquals("Recovery resent the exact failed cue before its retry deadline", failedCue, newCue)
                    val invalidatedAt = requireNotNull(persistentFailedCueInvalidatedAt) {
                        "The failed cue and its retry deadline were not observed invalidating"
                    }
                    val retiredOwner = requireNotNull(persistentRetiredCompassOwner)
                    val replacementOwner = requireNotNull(persistentReplacementCompassOwner)
                    assertSame("Recovery must retain the ongoing positioning collection", retiredOwner.binding, replacementOwner.binding)
                    assertNotEquals("An old feedback owner must retire its failed cue and retry lease",
                        retiredOwner.lifecycleGeneration, replacementOwner.lifecycleGeneration)
                    assertTrue("The retired failed cue must not be reset after its replacement was requested",
                        invalidatedAt <= requireNotNull(recoveredRoute).requestedAt)
                    assertTrue("Recovery must use an originally measured heading eligible at dispatch",
                        requireNotNull(newCue).hasCurrentFacingEvidence(requireNotNull(recoveredRoute).requestedAt))
                    persistentRecoveredCue = newCue
                    val doneIndex = (0 until trace.length()).firstOrNull { index ->
                        val item = trace.getJSONObject(index)
                        item.optString("type") == "TTS_DONE" &&
                            item.optJSONObject("values")?.optString("utterance_id") == requireNotNull(response).id
                    }
                    assertNotNull("Recovery utterance DONE was not found in the ordered trace", doneIndex)
                    val routeIndex = (0 until trace.length()).firstOrNull { index ->
                        val item = trace.getJSONObject(index)
                        item.optString("type") == "TTS_REQUEST" &&
                            item.optJSONObject("values")?.optString("utterance_id") == requireNotNull(recoveredRoute).id
                    }
                    assertNotNull("Replacement ROUTE request was not found in the ordered trace", routeIndex)
                    val headingIndex = (0 until requireNotNull(routeIndex)).lastOrNull { index ->
                        val item = trace.getJSONObject(index)
                        val input = item.optJSONObject("values")
                        item.optString("type") == "INPUT" && input?.optString("type") == "heading" &&
                            item.optLong("clock_ms") - input.optLong("age_ms", 0L) ==
                            requireNotNull(newCue).facingObservedAtElapsedRealtimeMs
                    }
                    assertNotNull("The replacement cue's original heading has no preceding input provenance", headingIndex)
                    assertTrue("Recovery DONE must precede its replacement route request",
                        requireNotNull(doneIndex) < requireNotNull(routeIndex))
                    // Preserve the original two cases' stronger post-DONE input ordering.
                    // Other cases may reuse a still-current original measurement without an extra wait.
                    if (case.id in setOf("gps_persistent_accuracy_auto_recovery", "gps_persistent_missing_auto_recovery")) {
                        assertTrue("The original recovery cases require a route request after DONE",
                            requireNotNull(recoveredRoute).requestedAt > responseDoneAt)
                        assertTrue("The replacement cue did not use a fresh post-recovery heading",
                            requireNotNull(requireNotNull(newCue).facingObservedAtElapsedRealtimeMs) >= responseDoneAt)
                        assertTrue("The fresh heading must follow recovery DONE and precede the new ROUTE request",
                            requireNotNull(doneIndex) < requireNotNull(headingIndex) &&
                                requireNotNull(headingIndex) < requireNotNull(routeIndex))
                    }
                    persistentRecoveryDoneTraceIndex = doneIndex
                    persistentFreshHeadingTraceIndex = headingIndex
                    persistentRecoveredRouteTraceIndex = routeIndex
                    assertTrue("Recovered route has no current actual output validator", requireNotNull(recoveredRoute).let {
                        it.current?.invoke() == true || it.id in it.runtime.activeIds || (it.done && it.cue == completed)
                    })
                }
                "no_false_arrival" -> { assertFalse(allSpoken().any { it.message.contains("목적지에 도착") }); assertNotEquals(RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION, navigator.pendingUserDecision()) }
                "persistent_response" -> assertNotNull("No actual response to persistent restriction", persistentResponseAt)
                "owner_retired" -> {
                    assertTrue("Old route/SDK owner still admitted", !sdkCollectionCurrent() || !navigator.hasRoute() || navigator.currentRouteId() != initialRouteId)
                    assertEquals("Old owner's DONE acknowledged a cue", retiredCompletedCue, completed)
                }
                "blocked_before_grace_end" -> {
                    assertTrue("Hard permission/owner/reversal boundary was bypassed by grace", !sdkCollectionCurrent() || !navigator.hasRoute() || hardDirectionRetired)
                    assertTrue(clock.now < (firstDeadline ?: (requireNotNull(origin) + if (kind == "gps") GuidanceRecoveryTiming.GPS_GRACE_MS else GuidanceRecoveryTiming.DIRECTION_GRACE_MS)))
                    assertFalse("Old utterance still playing speech", retiredUtterances.any { it.id in it.runtime.activeIds })
                    assertEquals("Old owner completed after retirement", retiredCompletedCue, completed)
                    if (!hardDirectionRetired) assertEquals("New route output after retirement", requireNotNull(retiredRouteRequests), routes.size)
                }
                "output_retries" -> assertTrue("No automatic output retry", routes.size >= expectation.optInt("count", 2))
                "gps_expired_before_confirmation" -> {
                    assertTrue("The original GOOD fix must actually have expired", clock.now - requireNotNull(initialOriginalGpsAt) > 10_000L)
                    assertNotEquals("Expired GOOD evidence was promoted to PASS", EnvironmentEvidenceStatus.PASS, gps?.status)
                    assertNull("An expired original location still permits route output", positions.currentRouteLocationOrNull())
                    // The environment group can close before Guide's retained presentation shadow changes.
                    assertFalse("Expired location still permits navigation output", invoke(main, "navigationEnvironmentOutputsAllowed") as Boolean)
                    assertFalse("Expired original position still confirms arrival", positions.coordinator.evaluate(clock.now).absoluteArrivalConfirmable)
                    assertFalse("Expired original route speech is still playing", routes.any { it.id in it.runtime.activeIds })
                    assertEquals(RuntimeMeasuredQualityGate.GpsRecoveryStage.RECHECKING, base.gate.gpsRecoveryStage(clock.now))
                    assertNotNull("TTL expiry discarded the original GPS recheck episode", activeDeadline)
                    assertEquals(firstGpsDeadline, activeDeadline)
                    assertTrue(clock.now < requireNotNull(activeDeadline))
                    assertTrue(run.active)
                    assertEquals(WalkSessionState.ACTIVE, base.lifecycle.snapshot().state)
                }
                "gps_not_future" -> {
                    positions.rawRouteLocation?.let { assertTrue("Raw GPS measurement is from the future", it.elapsedRealtimeMs <= clock.now) }
                    positions.trustedLocation?.let { assertTrue("Filtered GPS measurement is from the future", it.elapsedRealtimeMs <= clock.now) }
                }
                "duplicate_not_renewed" -> {
                    val measuredAt = requireNotNull(origin) + expectation.getLong("measured_at_ms")
                    assertEquals("Duplicate packet renewed original raw measurement", measuredAt, positions.rawRouteLocation?.elapsedRealtimeMs)
                }
                else -> error("UNKNOWN_ASSERT: $assertion")
            }
            assertions.put(JSONObject().put("assert", assertion).put("at_ms", relativeNow()).put("status", "PASS"))
        } catch (failure: Throwable) {
            if (failure is AssertionError) assertionFailure = true
            assertions.put(JSONObject().put("assert", assertion).put("at_ms", relativeNow()).put("status", "FAIL").put("core", before))
            throw failure
        }
    }

    private fun rememberRetiredOwner() {
        retiredCompletedCue = read(navigator, "completedSpeechCue")
        retiredRouteRequests = allSpoken().count { it.role == "ROUTE" }
        retiredUtterances = allSpoken().filter { it.role == "ROUTE" && !it.done }
    }

    private fun capture() {
        val gpsDeadline = base.gate.pendingFailureDeadlineElapsedRealtimeMs()
        val directionDeadline = navigator.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs
        if (origin != null) {
            if (firstGpsDeadline == null && gpsDeadline != null) firstGpsDeadline = gpsDeadline
            if (firstDirectionDeadline == null && directionDeadline != null) firstDirectionDeadline = directionDeadline
            if (firstFaultAt == null && (gpsDeadline != null || directionDeadline != null)) firstFaultAt = clock.now
        }
        log("CORE", core())
    }

    private fun core(): JSONObject = JSONObject().put("state", base.lifecycle.snapshot().state.name)
        .put("epoch_recovery_generation", base.lifecycle.snapshot().epoch.recoveryGeneration)
        .put("run_active", run.active).put("route_present", navigator.hasRoute()).put("route_id", navigator.currentRouteId())
        .put("position_paused", guide.positionPaused).put("direction_mode", navigator.currentDirectionGraceSnapshot()?.mode?.name)
        .put("direction_deadline_ms", navigator.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        .put("gps_original_raw_at_ms", positions.rawRouteLocation?.elapsedRealtimeMs)
        .put("gps_original_filtered_at_ms", positions.trustedLocation?.elapsedRealtimeMs)
        .put("gps_latest_evidence_at_ms", base.gate.latestRawObservation()?.observedAtElapsedRealtimeMs)
        .put("gps_first_bad_at_ms", base.gate.gpsFailureStartedAtElapsedRealtimeMs())
        .put("gps_deadline_ms", base.gate.pendingFailureDeadlineElapsedRealtimeMs())
        .put("gps_latest_status", base.gate.latestRawObservation()?.status?.name)
        .put("completed_cue_present", read<Any?>(navigator, "completedSpeechCue") != null)
        .put("pending_cue_present", read<Any?>(navigator, "pendingSpeechCue") != null)
        .put("offered_speech_cue", read<Any?>(navigator, "offeredSpeechCue")?.toString())
        .put("failed_speech_cue", read<Any?>(navigator, "failedSpeechCue")?.toString())
        .put("completed_speech_cue", read<Any?>(navigator, "completedSpeechCue")?.toString())
        .put("speech_retry_interval_ms", read<RouteNavigatorConfig>(navigator, "config").speechRetryIntervalMs)
        .put("speech_retry_not_before_clock_ms", read<Long?>(navigator, "speechRetryNotBeforeMs"))
        .put("speech_retry_not_before_relative_ms", read<Long?>(navigator, "speechRetryNotBeforeMs")?.let { it - (origin ?: 0L) })
        .put("registered_guide_retry_present", read<Any?>(guide, "navigationSpeechRetryRunnable") != null)
        .put("sdk_native_active_ids", JSONArray(nativeRuntimes.flatMap { it.activeIds }.sorted()))
        .put("native_stops", nativeRuntimes.sumOf { it.stops }).put("native_requests", allSpoken().size)

    private fun relativeNow() = origin?.let { clock.now - it } ?: clock.now
    private fun log(type: String, values: JSONObject = JSONObject()) {
        trace.put(JSONObject().put("type", type).put("phase", phase).put("clock_ms", clock.now).put("at_ms", relativeNow()).put("values", values))
    }
    private fun allSpoken() = nativeRuntimes.flatMap { it.spoken }
    private fun clockMetres(text: String) = Regex("[0-9]+시").containsMatchIn(text) && text.contains("미터")
    fun report(failure: Throwable?): JSONObject = JSONObject().put("case_id", case.id).put("scope", case.scope)
        .put("status", if (failure == null) "PASS" else if (inputAdapterGap) "INPUT_ADAPTER_GAP" else if (assertionFailure) "UNTRIAGED_ASSERTION" else "UNTRIAGED_EXECUTION")
        .put("failure", failure?.toString()).put("origin_clock_ms", origin).put("trace", trace).put("assertions", assertions)
        .put("full_home_to_arrival", false)
        .put("fixture_policy_revision", "20261010_CURRENT_GUIDANCE_RECOVERY_TIMING")
        .put("gps_grace_ms", GuidanceRecoveryTiming.GPS_GRACE_MS)
        .put("direction_grace_ms", GuidanceRecoveryTiming.DIRECTION_GRACE_MS)
        .put("original_raw_location_ttl_ms", 10_000L)
        .put("scheduler_model", "ROUTE_COMPASS_SESSION_WITH_CONTROLLED_CLOCK_CALLBACK")
        .put("route_compass_schedule_lease_and_evaluation_exercised", true)
        .put("android_view_handler_registration_and_dispatch_verified", false)
        .put("persistent_recovery_response_requested_at_clock_ms", persistentResponseAt)
        .put("persistent_recovery_response_completed_at_clock_ms", persistentResponseCompletedAt)
        .put("persistent_recovery_retry_not_before_at_response_clock_ms", persistentRetryNotBeforeAtResponse)
        .put("persistent_recovery_route_requested_at_clock_ms", persistentRecoveredRouteAt)
        .put("persistent_recovery_failed_cue_at_backoff", persistentFailedCueAtBackoff?.toString())
        .put("persistent_recovery_failed_cue_invalidated_at_clock_ms", persistentFailedCueInvalidatedAt)
        .put("persistent_recovery_replacement_cue", persistentRecoveredCue?.toString())
        .put("persistent_recovery_done_trace_index", persistentRecoveryDoneTraceIndex)
        .put("persistent_recovery_fresh_heading_trace_index", persistentFreshHeadingTraceIndex)
        .put("persistent_recovery_route_trace_index", persistentRecoveredRouteTraceIndex)
        .put("persistent_recovery_retired_feedback_generation", persistentRetiredCompassOwner?.lifecycleGeneration)
        .put("persistent_recovery_replacement_feedback_generation", persistentReplacementCompassOwner?.lifecycleGeneration)
        .put("persistent_recovery_semantics", "retired_feedback_owner_invalidates_failed_cue_then_current_owner_uses_eligible_heading")
        .put("persistent_recovery_route_baseline", persistentRouteBaseline)
        .put("open_requirements", JSONArray(listOf("full home/start/arrival path", "physical 3D sensor projection",
            "acoustic speech and device SDK execution", "Android View scheduler dispatch and device timing")))
        .put("fresh_requests", freshRequests).put("fresh_cancels", freshCancels)
        .put("actual_class_sources", JSONObject(coreClasses.associate { it.name to classSource(it) }))
    override fun close() { phase = "TEARDOWN"; output.closeActuator() }

    private data class Spoken(val runtime: NativeRuntime, val message: String, val id: String, val role: String,
        val requestedAt: Long, val cue: RouteSpeechCueToken?, val current: (() -> Boolean)?,
        var done: Boolean = false, var completedAt: Long? = null)
    private inner class NativeRuntime(private val index: Int) : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        val spoken = mutableListOf<Spoken>()
        val activeIds = mutableSetOf<String>()
        private val pendingIds = mutableSetOf<String>()
        private val automaticCallbacks = mutableMapOf<String, MutableList<Runnable>>()
        lateinit var actor: AndroidFeedbackActuator
        var stops = 0
        private var initialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized get() = initialized != null
        override fun elapsedRealtime() = clock.now
        override fun initialize(onInitialized: (Int) -> Unit) {
            initialized = onInitialized
            clock.post(this, Runnable { log("TTS_READY", JSONObject().put("runtime", index)); onInitialized(TextToSpeech.SUCCESS) },
                case.initial.optLong("engine_ready_delay_ms", 0))
        }
        override fun setLanguage(language: Locale) = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice() = kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int { this.listener = listener; return TextToSpeech.SUCCESS }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            val role = if (message.contains("길안내를 시작")) "INTRO" else if (clockMetres(message)) "ROUTE" else "RECOVERY"
            val current = output.currentActuatorOrNull()?.let { actor ->
                val ledger = read<UtteranceLedger>(actor, "pendingUtterances")
                synchronized(ledger) { ledger[utteranceId]?.startValidator }
            }
            if (role == "RECOVERY" && firstFaultAt != null && origin != null && persistentResponseAt == null) {
                persistentResponseAt = clock.now
                persistentRouteBaseline = allSpoken().count { it.role == "ROUTE" }
                persistentRetryNotBeforeAtResponse = read(navigator, "speechRetryNotBeforeMs")
            }
            spoken += Spoken(this, message, utteranceId, role, clock.now, if (role == "ROUTE") read(navigator, "pendingSpeechCue") else null, current)
            pendingIds += utteranceId
            log("TTS_REQUEST", JSONObject().put("runtime", index).put("utterance_id", utteranceId)
                .put("native_priority_prefix", utteranceId.substringBefore('-')).put("role", role).put("text", message)
                .put("actual_validator_captured", current != null))
            val startDelay = when (role) {
                "INTRO" -> case.initial.optLong("intro_start_delay_ms", 60)
                "ROUTE" -> case.initial.optLong("tts_start_delay_ms", 80)
                else -> 60L
            }
            val doneDelay = when (role) {
                "INTRO" -> case.initial.optLong("intro_done_delay_ms", 200)
                "ROUTE" -> case.initial.optLong("tts_done_delay_ms", 2_200)
                else -> 200L
            }
            scheduleAutomatic("START", utteranceId, startDelay)
            val errorDelay = case.initial.optLong("tts_error_delay_ms", -1)
            if (role == "ROUTE" && errorDelay >= 0 && !errorInjected) {
                errorInjected = true
                scheduleAutomatic("ERROR", utteranceId, errorDelay)
            } else scheduleAutomatic("DONE", utteranceId, doneDelay)
            return TextToSpeech.SUCCESS
        }
        override fun stop() {
            stops++
            activeIds.clear()
            val cancelled = pendingIds.toList()
            pendingIds.clear()
            val removed = JSONObject(cancelled.associateWith { automaticCallbacks[it]?.size ?: 0 })
            cancelled.forEach(::cancelAutomatic)
            log("TTS_CANCEL", JSONObject().put("runtime", index).put("utterance_ids", JSONArray(cancelled))
                .put("automatic_callbacks_removed", removed))
            cancelled.forEach { id -> clock.post(this, Runnable { callback("STOP", id) }, 0) }
        }
        override fun shutdown() { log("TTS_SHUTDOWN", JSONObject().put("runtime", index)) }
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener) = case.initial.optBoolean("audio_focus_granted", true)
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("Tone outside replay scope")
        override fun post(task: Runnable): Boolean = postDelayed(task, 0)
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean { clock.post(this, task, delayMs); return true }
        override fun removeCallbacks(task: Runnable) = clock.remove(this, task)
        override fun removeCallbacksAndMessages(token: Any?) = clock.clear(this)
        private fun scheduleAutomatic(type: String, id: String, delay: Long) {
            lateinit var task: Runnable
            task = Runnable {
                automaticCallbacks[id]?.remove(task)
                if (automaticCallbacks[id]?.isEmpty() == true) automaticCallbacks.remove(id)
                callback(type, id)
            }
            automaticCallbacks.getOrPut(id) { mutableListOf() } += task
            clock.post(this, task, delay)
        }
        private fun cancelAutomatic(id: String) {
            automaticCallbacks.remove(id)?.forEach { clock.remove(this, it) }
        }
        fun callback(type: String, id: String, code: Int = TextToSpeech.ERROR, scripted: Boolean = false) {
            val registryCurrent = id in read<Set<String>>(actor, "pendingUtterances")
            log("TTS_$type", JSONObject().put("runtime", index).put("utterance_id", id)
                .put("delivery", if (scripted) "SCRIPTED_CALLBACK" else "AUTOMATIC_CALLBACK")
                .put("actuator_registry_current", registryCurrent).put("native_pending", id in pendingIds))
            val callbacks = requireNotNull(listener)
            when (type) {
                "START" -> {
                    if (id in pendingIds && registryCurrent) activeIds += id
                    callbacks.onStart(id)
                }
                "DONE" -> {
                    cancelAutomatic(id); pendingIds -= id; activeIds -= id
                    spoken.firstOrNull { it.id == id }?.let { it.done = true; it.completedAt = clock.now }
                    callbacks.onDone(id)
                }
                "ERROR" -> { cancelAutomatic(id); pendingIds -= id; activeIds -= id; callbacks.onError(id, code) }
                "STOP" -> { cancelAutomatic(id); pendingIds -= id; activeIds -= id; callbacks.onStop(id, true) }
                else -> error("UNKNOWN_TTS_CALLBACK: $type")
            }
        }
    }

    private class ReplayClock(private val base: MainActivityContinuousLocationStartBehaviorTest.Fixture) : SpeechOutputScheduler {
        private data class Task(val owner: Any, val due: Long, val order: Long, val runnable: Runnable)
        private val tasks = mutableListOf<Task>()
        private var sequence = 0L
        val now get() = base.now
        override fun elapsedRealtimeMs() = now
        override fun isMainThread() = true
        override fun runOnMain(action: () -> Unit) = action()
        override fun postReadiness(task: Runnable, delayMs: Long) = post(this, task, delayMs)
        override fun removeReadiness(task: Runnable) = remove(this, task)
        override fun postRetired(action: () -> Unit) = post(this, Runnable(action), 0)
        fun post(owner: Any, task: Runnable, delayMs: Long) {
            require(delayMs >= 0)
            tasks += Task(owner, Math.addExact(now, delayMs), sequence++, task)
        }
        fun remove(owner: Any, task: Runnable) { tasks.removeAll { it.owner === owner && it.runnable === task } }
        fun clear(owner: Any) { tasks.removeAll { it.owner === owner } }
        fun advanceBy(delta: Long) = advanceTo(Math.addExact(now, delta))
        fun advanceNext() = advanceTo(tasks.minOfOrNull { it.due } ?: error("FIXTURE_NO_CALLBACK"))
        fun advanceTo(target: Long) {
            require(target >= now) { "Replay clock cannot move backwards" }
            var count = 0
            while (true) {
                check(++count < 10_000) { "FIXTURE_CALLBACK_LOOP" }
                val next = tasks.filter { it.due <= target }.minWithOrNull(compareBy<Task> { it.due }.thenBy { it.order }) ?: break
                tasks.remove(next)
                base.now = maxOf(now, next.due)
                next.runnable.run()
            }
            base.now = target
        }
    }

    companion object {
        private fun classSource(type: Class<*>): String = requireNotNull(requireNotNull(type.protectionDomain).codeSource).location.toString()
        @Suppress("UNCHECKED_CAST") private fun <T> read(owner: Any, name: String): T =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
        private fun put(owner: Any, name: String, value: Any?) {
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
        }
        private fun invoke(owner: Any, name: String, vararg arguments: Any?): Any? {
            try {
                return owner.javaClass.declaredMethods.single { it.name == name && it.parameterCount == arguments.size }
                    .apply { isAccessible = true }.invoke(owner, *arguments)
            } catch (failure: InvocationTargetException) { throw requireNotNull(failure.targetException) }
        }
    }
}

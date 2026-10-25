package kr.co.hanium.dreamup.walksafe

import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.InvocationTargetException
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import kr.co.hanium.dreamup.walksafe.voice.interaction.VoiceCommandInteractionController
import kr.co.hanium.dreamup.walksafe.voice.interaction.VoiceInteractionControllers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exact Main/private typed caller -> ACTIVE production status -> real Main Handler/READY -> Actor.
 * Context, MainLooper, permission/location observations and elapsed time are Android's real APIs.
 * Onboarding/device/walk/Gateway inputs and speech effects are controlled; onCreate, native TTS,
 * camera/AR/physical walking and recognition transcript admission are not exercised.
 * The guarded-home response intentionally does not call the walking speechAllowed callback.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityCurrentStateSpeechAndroidTest {
    @Test(timeout = 30_000L)
    fun actualTranscriptCallerParsesCurrentStateAndCompletesItsActiveStatusAfterReady() = withFixture { f ->
        val phrases = listOf("현재 상태")
        val confidenceScores = floatArrayOf(0.9f)
        assertEquals(AndroidVoiceAction.SpeakCurrentState, selectAndroidVoiceAction(phrases, confidenceScores))
        val before = onMain(f.instrumentation) { f.state() }
        val expectedNavigationDecisionToken = onMain(f.instrumentation) { f.navigator.pendingDecisionToken() }
        val expected = onMain(f.instrumentation) { f.guidanceStatus() }
        onMain(f.instrumentation) {
            val transcriptEntry = MainActivity::class.java.getDeclaredMethod("handleVoiceCommandPhrases",
                List::class.java, FloatArray::class.java, RouteNavigatorDecisionToken::class.java,
                java.lang.Boolean.TYPE, String::class.java,
                kr.co.hanium.dreamup.walksafe.voice.OfflineSpeechEngine::class.java, java.lang.Boolean.TYPE)
                .apply { isAccessible = true; assertEquals(Void.TYPE, returnType) }
            try {
                transcriptEntry.invoke(f.main, phrases, confidenceScores, expectedNavigationDecisionToken,
                    true, "device_stt", null, false)
            } catch (failure: InvocationTargetException) { throw failure.targetException }
            assertEquals("The transcript entry reaches the production ACTIVE formatter", expected, f.displayedMessage())
            assertEquals(before, f.state())
            assertEquals(1, f.registry.size())
            assertNotNull(field(f.main, "pendingCommandSpeechReadyContinuation"))
            assertTrue(f.runtime.spoken.isEmpty())
            assertEquals(0, f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.REQUESTED))
        }
        onMain(f.instrumentation) { f.runtime.ready() }
        await(f, "Transcript-selected state reaches the actual Handler/READY continuation") { f.runtime.interactions().size == 1 }
        val reply = onMain(f.instrumentation) {
            assertNull(field(f.main, "pendingCommandSpeechReadyContinuation"))
            assertNotNull(f.actor.readySpeechEngineGenerationOrNull())
            assertEquals(1, f.readyCallbacks)
            f.runtime.interactions().single().also { assertEquals(expected, it.message) }
        }
        f.finish(reply)
        onMain(f.instrumentation) {
            for (outcome in listOf(SpeechOutputOutcome.REQUESTED, SpeechOutputOutcome.STARTED, SpeechOutputOutcome.DONE))
                assertEquals(1, f.output.count(SpeechOutputKind.INTERACTION, outcome))
            assertEquals(0, f.registry.size())
            assertFalse(f.actor.isAppSpeechActive())
            assertEquals(0, f.runtime.stops)
            assertEquals(before, f.state())
            assertEquals(expected, f.displayedMessage())
            assertEquals("voice=feedback_completed", field(f.main, "latestNavigationState"))
        }
    }

    @Test(timeout = 30_000L)
    fun actualActiveCallerWaitsForReadyThenSpeaksItsProducedStatusWithoutChangingTheWalk() = withFixture { f ->
        val before = onMain(f.instrumentation) { f.state() }
        val expected = onMain(f.instrumentation) { f.guidanceStatus() }
        onMain(f.instrumentation) {
            f.requestState()
            assertEquals("The typed ACTIVE branch uses its production formatter", expected, f.displayedMessage())
            assertEquals(1, f.registry.size())
            assertNotNull(field(f.main, "pendingCommandSpeechReadyContinuation"))
            assertTrue(f.runtime.spoken.isEmpty())
            assertEquals(0, f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.REQUESTED))
        }
        // Initialization input reaches actual Actor.onInit and Main.processOfflineSpeechReady.
        onMain(f.instrumentation) { f.runtime.ready() }
        await(f, "Actual READY callback/Handler continuation dispatch") { f.runtime.interactions().size == 1 }
        val reply = onMain(f.instrumentation) {
            assertNull(field(f.main, "pendingCommandSpeechReadyContinuation"))
            assertNotNull(f.actor.readySpeechEngineGenerationOrNull())
            assertEquals(1, f.readyCallbacks)
            f.runtime.interactions().single().also { assertEquals(expected, it.message) }
        }
        f.finish(reply)
        onMain(f.instrumentation) {
            for (outcome in listOf(SpeechOutputOutcome.REQUESTED, SpeechOutputOutcome.STARTED, SpeechOutputOutcome.DONE))
                assertEquals(1, f.output.count(SpeechOutputKind.INTERACTION, outcome))
            assertEquals(0, f.registry.size())
            assertFalse(f.actor.isAppSpeechActive())
            assertEquals(0, f.runtime.stops)
            assertEquals(before, f.state())
            assertEquals(expected, f.displayedMessage())
            assertEquals("voice=feedback_completed", field(f.main, "latestNavigationState"))
        }
    }

    @Test(timeout = 30_000L)
    fun actualStateCallerProtectsAProducedStopAndAcceptsANewRequestAfterItsConfirmedCooldown() = withFixture { f ->
        onMain(f.instrumentation) { f.runtime.ready() }
        val before = onMain(f.instrumentation) { f.state() }
        val risk = onMain(f.instrumentation) { f.offerStop() }
        onMain(f.instrumentation) { f.runtime.start(risk.spoken.id) }
        await(f, "Original STOP actually starts") { f.output.count(SpeechOutputKind.RISK, SpeechOutputOutcome.STARTED) == 1 }
        onMain(f.instrumentation) {
            assertTrue(f.policy.hasClaimedFeedbackDelivery(risk.action))
            val spokenBefore = f.runtime.spoken.size
            f.requestState()
            assertEquals(spokenBefore, f.runtime.spoken.size)
            assertEquals(0, f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.REQUESTED))
            assertEquals(0, f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.STARTED))
            assertTrue(f.actor.isAppSpeechActive())
            assertTrue(f.policy.hasClaimedFeedbackDelivery(risk.action))
            assertEquals(0, f.runtime.stops)
            assertEquals(0, risk.failed + risk.cancelled)
            assertEquals(before, f.state())
        }
        val confirmedBefore = onMain(f.instrumentation) { risk.confirmed }
        val doneBefore = f.output.count(SpeechOutputKind.RISK, SpeechOutputOutcome.DONE)
        onMain(f.instrumentation) { f.runtime.done(risk.spoken.id) }
        await(f, "STOP normal confirmation, not rejection releasing the owner") { risk.confirmed == confirmedBefore + 1 }
        onMain(f.instrumentation) {
            assertEquals(doneBefore + 1, f.output.count(SpeechOutputKind.RISK, SpeechOutputOutcome.DONE))
            assertEquals(0, risk.failed + risk.cancelled)
            assertFalse(f.policy.hasClaimedFeedbackDelivery(risk.action))
        }
        // Real elapsed time and unchanged policy cooldown; no safe/reset/forced admission input.
        await(f, "Original policy permits the later request") { f.policy.canSpeakNavigation(SystemClock.elapsedRealtime()) }
        val expected = onMain(f.instrumentation) { f.guidanceStatus() }
        onMain(f.instrumentation) { f.requestState() }
        await(f, "Fresh allowed status request reaches real Actor") { f.runtime.interactions().size == 1 }
        val reply = onMain(f.instrumentation) { f.runtime.interactions().single().also { assertEquals(expected, it.message) } }
        f.finish(reply)
        onMain(f.instrumentation) {
            assertEquals(1, f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.DONE))
            assertEquals(1, risk.confirmed)
            assertEquals(0, risk.failed + risk.cancelled)
            assertEquals(0, f.runtime.stops)
            assertEquals(before, f.state())
        }
    }

    @Test(timeout = 45_000L)
    fun actualReadyWaitAndLateStartRejectOldOwnersAndOnlyANewStateRequestCompletes() {
        for (change in listOf("reinstall", "actor")) withFixture { f ->
            onMain(f.instrumentation) {
                f.requestState()
                assertEquals(1, f.registry.size())
                assertNotNull(field(f.main, "pendingCommandSpeechReadyContinuation"))
                assertTrue(f.runtime.spoken.isEmpty())
                if (change == "reinstall") {
                    val old = f.navigator.currentTactileRouteProgress()
                    f.navigator.setRoute(f.route)
                    assertNotEquals(old.routeVersion, f.navigator.currentTactileRouteProgress().routeVersion)
                } else f.replaceActor()
            }
            val changed = onMain(f.instrumentation) { f.state() }
            onMain(f.instrumentation) { f.runtime.ready() }
            await(f, "Original Handler/READY request retires its old source/Actor") {
                f.registry.size() == 0 && field(f.main, "pendingCommandSpeechReadyContinuation") == null
            }
            onMain(f.instrumentation) {
                assertEquals(0, f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.REQUESTED))
                assertTrue(f.runtimes.all { it.interactions().isEmpty() })
                assertEquals(changed, f.state())
                assertEquals(0, f.runtimes.sumOf { it.stops })
                f.requestState()
            }
            await(f, "New owner dispatches its own status") { f.runtime.interactions().size == 1 }
            val late = onMain(f.instrumentation) {
                // A retired Actor's real READY callback must not retire the new response.
                if (change == "actor") f.runtimes.first().ready()
                val result = f.runtime.interactions().single()
                val progress = f.navigator.currentTactileRouteProgress()
                f.navigator.setRoute(f.route)
                assertNotEquals(progress.routeVersion, f.navigator.currentTactileRouteProgress().routeVersion)
                result
            }
            val afterSecondReinstall = onMain(f.instrumentation) { f.state() }
            onMain(f.instrumentation) { f.runtime.start(late.id) }
            await(f, "Real native-start callback is rejected by the original captured lease") {
                f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.EXPIRED_BEFORE_START) == 1
            }
            onMain(f.instrumentation) {
                // STARTED records the received platform callback before validation; this is not valid playback.
                assertEquals(1, f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.STARTED))
                assertEquals(0, f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.DONE))
                assertEquals(1, f.runtime.stops)
                assertEquals(0, f.registry.size())
                assertFalse(f.actor.isAppSpeechActive())
                f.runtime.done(late.id)
                assertEquals(afterSecondReinstall, f.state())
                f.requestState()
            }
            await(f, "Request captured after the latest route reinstall is dispatched once") { f.runtime.interactions().size == 2 }
            val current = onMain(f.instrumentation) { f.runtime.interactions().last().also {
                assertEquals(f.displayedMessage(), it.message)
            } }
            f.finish(current)
            onMain(f.instrumentation) {
                assertEquals(1, f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.DONE))
                assertEquals(0, f.registry.size())
                assertEquals(afterSecondReinstall, f.state())
                assertEquals(1, f.runtime.stops)
            }
        }
    }

    private class RiskDelivery(val action: FeedbackAction, val evaluatedAt: Long) {
        lateinit var spoken: RecordingRuntime.Spoken
        var confirmed = 0
        var failed = 0
        var cancelled = 0
    }

    private class Fixture(val instrumentation: Instrumentation) {
        val main: MainActivity
        val lifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)
        val route = WalkingRoute("CURRENT_STATE_PROBE", WalkingRouteSummary(150, 180),
            listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0005, 127.0), RoutePoint(37.0005, 127.001)),
            listOf(WalkingRouteGuidePoint(0, RoutePoint(37.0005, 127.0), "원 경로", 55, 95, turnType = 13)),
            providerRouteId = "same-state-route")
        val navigator: RouteNavigator
        val output = SpeechOutputDiagnostics()
        val runtimes = mutableListOf<RecordingRuntime>()
        private val actors = mutableListOf<AndroidFeedbackActuator>()
        lateinit var actor: AndroidFeedbackActuator
            private set
        val runtime: RecordingRuntime get() = runtimes.last()
        val registry: UtteranceCallbackRegistry get() = field(main, "commandSpeechResponseCallbacks") as UtteranceCallbackRegistry
        val policy: WalkSafeFeedbackPolicy get() = field(main, "feedbackPolicy") as WalkSafeFeedbackPolicy
        var readyCallbacks = 0
            private set
        private var gatewayGeneration: Long? = null
        private var leaseEpoch: WalkRuntimeEpoch? = null
        private lateinit var lastResult: TextView

        init {
            check(Looper.myLooper() == Looper.getMainLooper())
            val context = instrumentation.targetContext
            val component = ComponentName(context, MainActivity::class.java)
            @Suppress("DEPRECATION")
            main = instrumentation.newActivity(MainActivity::class.java, context, Binder(),
                context.applicationContext as Application, Intent().setComponent(component),
                context.packageManager.getActivityInfo(component, 0), "Main current state caller probe",
                null, null, null) as MainActivity
            navigator = field(main, "routeNavigator") as RouteNavigator
        }

        fun prepare() {
            assertFalse(BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
            assertNull("Do not replace another process session", GatewaySessionProcessCoordinator.snapshot().session)
            assertNull(GatewaySessionProcessCoordinator.snapshot().restoredFirstRunSnapshot)
            val onboarding = completedOnboarding()
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            gatewayGeneration = operation.generation
            val session = GatewayFieldSession.verified("https://gateway.example.test", ACTOR,
                "walksafe_field_session=synthetic_state_caller_probe", System.currentTimeMillis() + 120_000L)
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, onboarding))
            gatewayGeneration = GatewaySessionProcessCoordinator.snapshot().generation
            val device = completedDeviceCheck(requireNotNull(gatewayGeneration))
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
            val readiness = WalkSessionReadinessCollector(lifecycle.snapshot().epoch, plan).apply {
                plan.requiredRequirements.forEach { record(WalkSessionReadinessObservation(lifecycle.snapshot().epoch,
                    it, WalkSessionReadinessStatus.READY, "controlled typed state-caller readiness input")) }
            }.build(SystemClock.elapsedRealtime())
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readiness))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
            val authority = field(main, "gatewayWalkAuthorityController") as GatewayWalkAuthorityController
            val epoch = lifecycle.snapshot().epoch
            val start = requireNotNull(authority.beginStart(epoch))
            val now = SystemClock.elapsedRealtime()
            assertEquals(GatewayWalkAuthorityCompletion.LEASE_ACTIVE,
                authority.completeStart(start, GatewayWalkStartResult.Granted(GatewayWalkLease(
                    "started", ACTOR, session.deviceId, epoch.walkSessionId, "synthetic-state-lease", 1L,
                    System.currentTimeMillis(), System.currentTimeMillis() + 120_000L,
                    System.currentTimeMillis(), now + 120_000L))))
            leaseEpoch = epoch
            setField(main, "firstRunOnboardingSnapshot", onboarding)
            setField(main, "postLoginDeviceCheckSnapshot", device)
            setField(main, "walkSessionLifecycle", lifecycle)
            setField(main, "isActivityForeground", lifecycle.snapshot().isForeground)
            setField(main, "reporterUserId", session.actorId)
            (field(main, "permissionSessionPolicy") as PermissionSessionPolicy).authenticated(session.actorId)
            navigator.setRoute(route)
            val update = navigator.update(TrustedLocation(37.0, 127.0, 3f, now), now, false)
            setField(main, "isRouteActive", navigator.hasRoute())
            setField(main, "latestTmapOnRoute", !update.offRoute)
            lastResult = TextView(instrumentation.targetContext)
            setField(main, "walkLastResultText", lastResult)
            replaceActor()
            assertEquals(true, invoke("isWalkSessionRuntimeActive", java.lang.Boolean.TYPE))
            assertEquals("HOME", (field(main, "nativeUiPage") as Enum<*>).name)
            assertNull("Storage creation is not part of a state request", (field(main, "speechOutputCollection") as AtomicReference<*>).get())
            // Actual OS permission/LocationManager/empty environment and mounting observations stay intact.
            assertFalse(field(main, "detectorAvailable") as Boolean)
            assertNull(field(main, "session"))
        }

        fun replaceActor() {
            val observedRuntime = RecordingRuntime()
            val generation = field(main, "feedbackLifecycleGeneration") as Int
            lateinit var created: AndroidFeedbackActuator
            created = AndroidFeedbackActuator(runtime = observedRuntime, onOfflineKoreanSpeechReady = {
                readyCallbacks++
                invoke("processOfflineSpeechReady", java.lang.Boolean.TYPE,
                    arrayOf(AndroidFeedbackActuator::class.java, Int::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!),
                    created, generation, SystemClock.elapsedRealtime())
            })
            runtimes += observedRuntime
            actors += created
            MainActivityControllerTestFixture.replaceActuator(main, created)
            created.speechOutputListener = output::record
            actor = created
        }

        fun requestState(): Any? {
            val owner = (MainActivityControllerTestFixture.controller(main, "voiceInteractionControllers")
                as VoiceInteractionControllers).commands
            val method = VoiceCommandInteractionController::class.java.getDeclaredMethod("executeVoiceAction",
                AndroidVoiceAction::class.java, RouteNavigatorDecisionToken::class.java, java.lang.Double::class.java)
                .apply { isAccessible = true; check(returnType == Void.TYPE) }
            return try { method.invoke(owner, AndroidVoiceAction.SpeakCurrentState, null, null) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        }

        fun guidanceStatus(): String {
            val presentation = invoke("currentNativeGuidancePresentation", DestinationGuidancePresentation::class.java)
            return invoke("nativeGuidanceStatusMessage", String::class.java,
                arrayOf(DestinationGuidancePresentation::class.java), presentation) as String
        }

        fun displayedMessage(): String = lastResult.text.toString().removePrefix("마지막 안내: ")

        fun state(): List<Any?> = listOf(navigator.currentRouteId(), navigator.currentTactileRouteProgress(),
            navigator.currentRouteMatch()?.geometricProgressM, navigator.pendingDecisionToken(), lifecycle.snapshot(),
            field(main, "nativeUiPage"), field(main, "routeRequestGeneration"), field(main, "isRouteActive"),
            (field(main, "routeRequestInFlight") as java.util.concurrent.atomic.AtomicBoolean).get(),
            field(main, "currentDestination"), field(main, "pendingUiDestination"))

        fun offerStop(): RiskDelivery {
            val at = SystemClock.elapsedRealtime()
            val facing = MessagePolicy().forFeedbackQueue().evaluate(MetricDepthDecision("person",
                DepthSource.ARCORE_FULL_DEPTH, .6f, trend = Trend.APPROACHING, confidenceFinal = .90f,
                trackKey = "state-stop"), at).userFacing
            assertEquals(MessageLevel.STOP, facing.messageLevel)
            val age = FeedbackPolicyConfig().maxObservationAgeMs
            val candidate = FeedbackCandidate(trackId = "state-stop", message = requireNotNull(facing.message),
                level = facing.messageLevel, source = DepthSource.ARCORE_FULL_DEPTH, confidence = .90f,
                timestampMs = SystemClock.elapsedRealtimeNanos() / 1_000_000L,
                trackAgeFrames = 10, trackStableMs = 1_000L, stale = false,
                validUntilMs = at + age, riskDistanceM = .6f,
                observedAtElapsedRealtimeMs = at, observationMaxAgeMs = age)
            val action = requireNotNull(policy.evaluate(candidate, true, at))
            assertTrue(policy.claimFeedbackDelivery(action, at, SystemClock.elapsedRealtime()))
            val delivery = RiskDelivery(action, at)
            val dispatched = actor.emit(action, onSpeechCompleted = {
                if (policy.confirmFeedbackDelivery(action, at, SystemClock.elapsedRealtime())) delivery.confirmed++
            }, onSpeechFailed = {
                delivery.failed++; policy.rejectUndeliveredFeedback(action, at)
            }, onSpeechCancelled = {
                delivery.cancelled++; policy.rejectUndeliveredFeedback(action, at)
            }, isStillValidAtStart = { policy.hasClaimedFeedbackDelivery(action) &&
                action.deliveryKey in policy.activeFeedbackDeliveryKeys(listOf(candidate), true, SystemClock.elapsedRealtime()) })
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, dispatched.speech)
            delivery.spoken = runtime.spoken.last()
            return delivery
        }

        fun finish(spoken: RecordingRuntime.Spoken) {
            val before = output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.DONE)
            val startedBefore = output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.STARTED)
            onMain(instrumentation) { runtime.start(spoken.id) }
            await(this, "Actual Actor START callback") {
                output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.STARTED) == startedBefore + 1
            }
            onMain(instrumentation) { runtime.done(spoken.id) }
            await(this, "Actual response completion and registry cleanup") {
                output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.DONE) == before + 1 && registry.size() == 0
            }
        }

        private fun invoke(name: String, returns: Class<*>, types: Array<Class<*>> = emptyArray(), vararg values: Any?): Any? {
            val method = MainActivity::class.java.getDeclaredMethod(name, *types).apply {
                isAccessible = true
                check(returnType == returns)
            }
            return try { method.invoke(main, *values) } catch (failure: InvocationTargetException) { throw failure.targetException }
        }

        fun close() {
            var first: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    if (first == null) first = failure else first!!.addSuppressed(failure)
                }
            }
            cleanup { invoke("cancelCommandSpeechResponse", Void.TYPE) }
            cleanup { MainActivityControllerTestFixture.replaceActuator(main, null) }
            actors.forEach { owned -> cleanup { owned.close() } }
            runtimes.forEach { owned -> cleanup { owned.close() } }
            cleanup { setField(main, "isActivityForeground", false); lifecycle.handle(WalkSessionEvent.EnteredBackground) }
            cleanup { leaseEpoch?.let { (field(main, "gatewayWalkAuthorityController") as GatewayWalkAuthorityController).beginEnd(it) } }
            cleanup { gatewayGeneration?.let { expected -> assertNotNull(GatewaySessionProcessCoordinator.clear(expected)) } }
            first?.let { throw it }
        }
    }

    /** Android's elapsed clock and MainLooper scheduler; only speech effects and engine callbacks are doubles. */
    private class RecordingRuntime : FeedbackSpeechRuntime {
        data class Spoken(val message: String, val id: String, val submittedAt: Long)
        private val handler = Handler(Looper.getMainLooper())
        val spoken = mutableListOf<Spoken>() // Accessed only on MainLooper.
        var stops = 0
            private set
        private var initialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        override val scheduler = object : FeedbackSpeechScheduler {
            override fun post(task: Runnable) = handler.post(task)
            override fun postDelayed(task: Runnable, delayMs: Long) = handler.postDelayed(task, delayMs)
            override fun removeCallbacks(task: Runnable) = handler.removeCallbacks(task)
            override fun removeCallbacksAndMessages(token: Any?) = handler.removeCallbacksAndMessages(token)
        }
        override val engineInitialized: Boolean get() = initialized != null
        override fun elapsedRealtime() = SystemClock.elapsedRealtime()
        override fun initialize(onInitialized: (Int) -> Unit) { initialized = onInitialized }
        override fun setLanguage(language: Locale) = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice() = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int { this.listener = listener; return TextToSpeech.SUCCESS }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            spoken += Spoken(message, utteranceId, SystemClock.elapsedRealtime())
            return TextToSpeech.SUCCESS
        }
        override fun stop() { stops++ }
        override fun shutdown() = Unit
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener) = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("No tone in current-state caller fixture")
        fun interactions() = spoken.filter { it.id.startsWith("interaction-") }
        fun ready() = requireNotNull(initialized).invoke(TextToSpeech.SUCCESS)
        fun start(id: String) = requireNotNull(listener).onStart(id)
        fun done(id: String) = requireNotNull(listener).onDone(id)
        fun close() { handler.removeCallbacksAndMessages(null) }
    }

    private companion object {
        const val FIXTURE_PACKAGE = "kr.co.hanium.dreamup.walksafe.speechstorefixture"
        const val USER_PACKAGE = "kr.co.hanium.dreamup.walksafe"
        const val ACTOR = "22222222-2222-4222-8222-222222222222"

        fun withFixture(test: (Fixture) -> Unit) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            // This guard precedes fixture-owned inputs, not Application/SDK initialization.
            assertEquals(FIXTURE_PACKAGE, context.packageName)
            assertEquals(context.applicationInfo.uid, Process.myUid())
            @Suppress("DEPRECATION")
            val sharedUid = context.packageManager.getPackageInfo(context.packageName, 0).sharedUserId
            assertNull(sharedUid)
            val descriptor = instrumentation.uiAutomation.executeShellCommand("cmd package list packages -U $USER_PACKAGE")
            val original = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
                reader.readLines().mapNotNull { Regex("^package:([A-Za-z0-9_.]+)\\s+uid:([0-9]+)\\s*$")
                    .matchEntire(it.trim())?.takeIf { value -> value.groupValues[1] == USER_PACKAGE }
                    ?.groupValues?.get(2)?.toIntOrNull() }
            }
            assertEquals(1, original.size)
            assertNotEquals(original.single(), Process.myUid())
            val f = onMain(instrumentation) { Fixture(instrumentation) }
            var first: Throwable? = null
            try { onMain(instrumentation) { f.prepare() }; test(f) }
            catch (failure: Throwable) { first = failure; throw failure }
            finally {
                try { onMain(instrumentation) { f.close() }; instrumentation.waitForIdleSync() }
                catch (cleanup: Throwable) { if (first == null) throw cleanup else first.addSuppressed(cleanup) }
            }
        }

        fun await(f: Fixture, label: String, predicate: () -> Boolean) {
            check(Looper.myLooper() != Looper.getMainLooper())
            val deadline = SystemClock.elapsedRealtime() + 6_000L
            while (!onMain(f.instrumentation, predicate) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(5L)
            assertTrue(label, onMain(f.instrumentation, predicate))
        }

        fun <T> onMain(instrumentation: Instrumentation, action: () -> T): T {
            val result = AtomicReference<T>()
            val failure = AtomicReference<Throwable?>()
            instrumentation.runOnMainSync { try { result.set(action()) } catch (problem: Throwable) { failure.set(problem) } }
            failure.get()?.let { throw it } // Fixture finally remains on the instrumentation caller.
            return result.get()
        }

        fun field(main: MainActivity, name: String): Any? = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.get(main)
        fun setField(main: MainActivity, name: String, value: Any?) = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.set(main, value)
        fun receipt(value: Int) = FirstRunReceiptHash.fromSha256Hex(value.toString(16).padStart(64, '0'))
        fun completedOnboarding(): FirstRunOnboardingSnapshot {
            val evidence = listOf(FirstRunOnboardingEvidence.VerifiedLogin(FirstRunOpaqueActorBinding.fromProvider(ACTOR), receipt(1)),
                FirstRunOnboardingEvidence.PurposeAndSafety(receipt(2)), FirstRunOnboardingEvidence.Fp004Training(receipt(3)))
            val restored = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(1L, evidence,
                FirstRunOnboardingEvidenceVerifier { token, value -> value in evidence && token.stage == value.stage })
            check(restored.fullyRestored)
            val jit = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(restored.snapshot, restored.snapshot.epoch, restored.snapshot.revision)
            check(jit.accepted)
            val device = FirstRunOnboardingPolicy.recordEmailDeviceCheckPassed(jit.current, jit.current.epoch, jit.current.revision)
            check(device.accepted && device.current.mayEnterWalk)
            return device.current
        }
        fun completedDeviceCheck(generation: Long): PostLoginDeviceCheckSnapshot {
            val bound = PostLoginDeviceCheckPolicy.bindSession(PostLoginDeviceCheckPolicy.initial(), ACTOR, generation)
            val started = PostLoginDeviceCheckPolicy.beginFromUserAction(bound, ACTOR, generation, true)
            val binding = requireNotNull(started.bindingOrNull)
            val running = PostLoginDeviceCheckPolicy.beginRunning(started, binding, true)
            val ready = PostLoginDeviceCheckSignal.READY
            return PostLoginDeviceCheckPolicy.evaluate(running, binding, true, PostLoginDeviceCheckObservation(
                ready, ready, ready, ready, ready, ready, ready, ready, ready, ready, ready,
                metricDepth = PostLoginMetricDepthState.SUPPORTED)).also { check(it.state == PostLoginDeviceCheckState.FULL) }
        }
    }
}

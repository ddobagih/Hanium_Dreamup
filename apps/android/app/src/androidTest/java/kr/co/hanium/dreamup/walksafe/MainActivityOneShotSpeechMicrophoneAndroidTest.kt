package kr.co.hanium.dreamup.walksafe

import android.Manifest
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.tts.TextToSpeech
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.voice.*
import kr.co.hanium.dreamup.walksafe.voice.input.VoiceCommandRecognizer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SOURCE-only SDK draft: real Main COMMAND entry, original prompt/READY/terminal callbacks,
 * SystemClock/MainLooper quiet/defer admission and the real Preferred recognizer adapter.
 * Platform speech effects and its Vosk backend are controlled; no microphone/audio/model success.
 * Typed completed education is setup input, not evidence of educational output delivery.
 * No onCreate, route, walk grant, hands-free controller/service, installer or server is started.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityOneShotSpeechMicrophoneAndroidTest {
    @Test(timeout = 30_000L)
    fun actualCommandWaitsForPromptReadyStartDoneAndQuietBeforeStartingItsBackend() = withFixture { f ->
        val before = onMain(f.instrumentation) { f.walkAndRouteState() }
        onMain(f.instrumentation) {
            f.assertHomeAdmission()
            for (flag in listOf(false, true, false))
                assertFalse("An accessibility observation flag alone is not app TTS busy", f.appOutputActive(flag))
            assertTrue(f.requestCommand())
            assertTrue(field(f.main, "voiceCommandPromptPending") as Boolean)
            assertNotNull(field(f.main, "pendingCommandSpeechReadyContinuation"))
            assertEquals(1, f.registry.size())
            assertTrue(f.runtime.spoken.isEmpty())
            assertEquals(0, f.backend.starts.size)
            assertTrue(f.appOutputActive(false))
        }
        onMain(f.instrumentation) { f.runtime.ready() }
        await(f, "The production READY continuation submits the original prompt") { f.runtime.interactions().size == 1 }
        val prompt = onMain(f.instrumentation) {
            f.readyFailure?.let { throw it }
            assertEquals(1, f.readyCallbacks)
            assertNotNull(f.actor.readySpeechEngineGenerationOrNull())
            assertNull(field(f.main, "pendingCommandSpeechReadyContinuation"))
            assertEquals(0, f.backend.starts.size)
            f.runtime.interactions().single().also { assertEquals(PROMPT, it.message) }
        }
        onMain(f.instrumentation) { f.runtime.start(prompt.id) }
        await(f, "Actual Actor START is observed before any input reservation") {
            f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.STARTED) == 1
        }
        onMain(f.instrumentation) {
            assertTrue(f.actor.isAppSpeechActive())
            assertTrue(f.appOutputActive(true))
            assertTrue(field(f.main, "voiceCommandPromptPending") as Boolean)
            assertFalse(field(f.main, "voiceRecognitionActive") as Boolean)
            assertEquals(0, f.backend.starts.size)
        }
        val doneInputAt = onMain(f.instrumentation) { SystemClock.elapsedRealtime().also { f.runtime.done(prompt.id) } }
        await(f, "Original DONE consumer reserves only a deferred input during the quiet tail") {
            f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.DONE) == 1 &&
                field(f.main, "deferredVoiceRecognitionStart") != null
        }
        onMain(f.instrumentation) {
            assertFalse(field(f.main, "voiceCommandPromptPending") as Boolean)
            assertEquals(field(f.main, "voiceRecognitionGeneration"), field(f.main, "voiceCommandPromptReadyGeneration"))
            assertEquals(0, f.registry.size())
            assertEquals(0, f.backend.starts.size)
            assertFalse(f.actor.isAppSpeechActive())
            assertTrue(f.actor.isSpeechRecognitionBlocked())
        }
        await(f, "Only the original Main defer poll starts the controlled backend") { f.backend.starts.size == 1 }
        onMain(f.instrumentation) {
            assertTrue(f.backend.starts.single() >= doneInputAt + 500L) // Existing Actor quiet policy.
            assertEquals(field(f.main, "handsFreeVoiceModelDirectory"), f.backend.inputDirectory)
            assertNull(field(f.main, "deferredVoiceRecognitionStart"))
            assertNull(field(f.main, "voiceCommandPromptReadyGeneration"))
            assertTrue(field(f.main, "voiceRecognitionActive") as Boolean)
            for (outcome in listOf(SpeechOutputOutcome.REQUESTED, SpeechOutputOutcome.STARTED, SpeechOutputOutcome.DONE))
                assertEquals(1, f.output.count(SpeechOutputKind.INTERACTION, outcome))
            for (flag in listOf(false, true, false)) assertFalse(f.appOutputActive(flag))
            assertEquals(0, f.runtime.stops)
            assertEquals(before, f.walkAndRouteState())
        }
    }

    @Test(timeout = 30_000L)
    fun cancelledDeferredInputAndLateActorTerminalsCannotRetireTheNewCommand() = withFixture { f ->
        onMain(f.instrumentation) { f.runtime.ready(); f.assertHomeAdmission(); assertTrue(f.requestCommand()) }
        await(f, "A uses the original prompt") { f.runtime.interactions().size == 1 }
        val a = onMain(f.instrumentation) { f.runtime.interactions().single().also { assertEquals(PROMPT, it.message) } }
        onMain(f.instrumentation) { f.runtime.start(a.id) }
        await(f, "A has an actual START") { f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.STARTED) == 1 }
        onMain(f.instrumentation) { f.runtime.done(a.id) }
        await(f, "A DONE creates its actual deferred Runnable") {
            f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.DONE) == 1 &&
                field(f.main, "deferredVoiceRecognitionStart") != null
        }
        val oldA = onMain(f.instrumentation) {
            assertEquals(0, f.backend.starts.size)
            (field(f.main, "deferredVoiceRecognitionStart") as Runnable).also {
                f.invoke("cancelVoiceCommandRecognition", Void.TYPE)
                assertNull(field(f.main, "deferredVoiceRecognitionStart"))
                assertNull(field(f.main, "voiceCommandPromptReadyGeneration"))
            }
        }
        val originalDuplicateMs = AndroidFeedbackActuator::class.java.getDeclaredField("INTERACTION_DUP_WINDOW_MS")
            .apply { isAccessible = true }.getLong(null)
        await(f, "B respects the existing duplicate window and settled output") {
            SystemClock.elapsedRealtime() >= a.submittedAt + originalDuplicateMs && !f.actor.isSpeechRecognitionBlocked()
        }
        onMain(f.instrumentation) { assertEquals(0, f.backend.starts.size); assertTrue(f.requestCommand()) }
        await(f, "B has its own original prompt") { f.runtime.interactions().size == 2 }
        val b = onMain(f.instrumentation) { f.runtime.interactions().last().also { assertEquals(PROMPT, it.message); assertNotEquals(a.id, it.id) } }
        onMain(f.instrumentation) { f.runtime.start(b.id) }
        await(f, "B has an actual START") { f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.STARTED) == 2 }
        val doneInputAt = onMain(f.instrumentation) { SystemClock.elapsedRealtime().also { f.runtime.done(b.id) } }
        await(f, "B DONE owns a new actual deferred Runnable") {
            f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.DONE) == 2 &&
                field(f.main, "deferredVoiceRecognitionStart") != null
        }
        val bState = onMain(f.instrumentation) {
            val state = f.pendingInputState()
            assertNotSame(oldA, field(f.main, "deferredVoiceRecognitionStart"))
            assertEquals(field(f.main, "voiceRecognitionGeneration"), field(f.main, "voiceCommandPromptReadyGeneration"))
            assertEquals(0, f.backend.starts.size)
            oldA.run() // Captured production Runnable; its identity admission must reject A.
            f.runtime.done(a.id)
            f.runtime.stopped(a.id) // Existing Actor listener; stale registration may reject before Main.
            assertEquals(state, f.pendingInputState())
            state
        }
        f.instrumentation.waitForIdleSync()
        onMain(f.instrumentation) {
            assertEquals(bState, f.pendingInputState())
            assertEquals(2, f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.DONE))
            assertEquals(0, f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.INTERRUPTED))
            assertEquals(0, f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.STOPPED))
            assertEquals(0, f.backend.starts.size)
        }
        await(f, "Only B survives to start one backend") { f.backend.starts.size == 1 }
        onMain(f.instrumentation) {
            assertTrue(f.backend.starts.single() >= doneInputAt + 500L)
            assertNull(field(f.main, "deferredVoiceRecognitionStart"))
            assertNull(field(f.main, "voiceCommandPromptReadyGeneration"))
            assertTrue(field(f.main, "voiceRecognitionActive") as Boolean)
            assertEquals(2, f.runtime.interactions().size)
            assertEquals(1, f.backend.starts.size)
            f.readyFailure?.let { throw it }
        }
    }

    // BEGIN MIC CONTROLS ADDITION: original da847 test bodies remain unchanged.
    /**
     * Actual private announcement admission and actual Main busy helper, with an existing controlled
     * accessibility observer. This is not the NAV outer spoken-service guard, TalkBack reading ACK,
     * HandsFree PCM/model/FGS or microphone capture. The fixture TextView remains unattached.
     */
    @Test(timeout = 30_000L)
    fun actualTalkBackNavigationReservationControlsBusyAndRejectsRetiredResponseOwner() = withFixture { f ->
        var first: Throwable? = null
        try {
            onMain(f.instrumentation) { f.runtime.ready() }
            await(f, "Original READY callback settles before an idle announcement probe") {
                f.readyCallbacks == 1 && f.actor.readySpeechEngineGenerationOrNull() != null
            }
            val beforeWalk = onMain(f.instrumentation) { f.walkAndRouteState() }
            val ownerA = onMain(f.instrumentation) {
                f.assertHomeAdmission()
                f.assertIdleTalkBackBaseline()
                f.main.captureCommandSpeechResponseOwner().also {
                    assertTrue("Positive current owner precedes the stale control", it.isCurrent(f.main))
                }
            }
            val message = "앞으로 이동하세요."
            var requestDispatchCallbacks = 0 // Main request callback only; no external reading completion.
            val holdUntil = onMain(f.instrumentation) {
                val inputGenerationBefore = f.foregroundInputGeneration()
                val callStartedAt = SystemClock.elapsedRealtime()
                assertTrue(f.announceNavigation(message, ownerA) { requestDispatchCallbacks++ })
                val callReturnedAt = SystemClock.elapsedRealtime()
                val issuedAt = field(f.main, "lastNavigationAnnouncementMs") as Long
                val deadline = field(f.main, "navigationAnnouncementHoldUntilMs") as Long
                assertTrue(issuedAt in callStartedAt..callReturnedAt)
                assertEquals(message, field(f.main, "lastNavigationAnnouncement"))
                assertEquals(issuedAt + utteranceTerminalTimeoutMs(message.length, SpeechMessageFamily.NAVIGATION), deadline)
                assertTrue("The actual reservation is observed before its original deadline", callReturnedAt < deadline)
                assertEquals(inputGenerationBefore + 1L, f.foregroundInputGeneration())
                assertEquals("Only the synchronous Main request callback is observed", 1, requestDispatchCallbacks)
                assertNull("NAV admission must not invent a pending interaction post", field(f.main, "pendingTalkBackInteraction"))
                assertFalse(f.currentActuatorOutputActive(false))
                assertTrue("Existing true observer consumes the actual reservation; it is not an OS flag", f.currentActuatorOutputActive(true))
                assertFalse(f.currentActuatorOutputActive(false))
                assertFalse(f.actor.isAppSpeechActive())
                assertTrue(f.runtime.spoken.isEmpty())
                assertEquals(0, f.backend.starts.size)
                assertEquals(beforeWalk, f.walkAndRouteState())
                deadline
            }
            // Instrumentation caller only: wait for the product's actual deadline, without a fake clock.
            SystemClock.sleep((holdUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            f.instrumentation.waitForIdleSync()
            onMain(f.instrumentation) {
                assertTrue(SystemClock.elapsedRealtime() >= holdUntil)
                assertEquals("Expiry does not rewrite the original reservation clock", holdUntil,
                    field(f.main, "navigationAnnouncementHoldUntilMs"))
                for (observation in listOf(false, true, false)) assertFalse(f.currentActuatorOutputActive(observation))
                assertNull(field(f.main, "pendingTalkBackInteraction"))
                assertEquals(1, requestDispatchCallbacks)
                assertEquals(0, f.backend.starts.size)
                f.invoke("cancelVoiceCommandRecognition", Void.TYPE) // Original owner retirement.
                assertFalse(ownerA.isCurrent(f.main))
                val ownerB = f.main.captureCommandSpeechResponseOwner()
                assertTrue("A new actual owner must be current before retrying the retired owner", ownerB.isCurrent(f.main))
                val beforeRetired = f.talkBackReservationState()
                val beforeInput = f.pendingInputState()
                val dispatchesBeforeRetired = requestDispatchCallbacks
                assertFalse(f.announceNavigation(message, ownerA) { requestDispatchCallbacks++ })
                assertEquals(beforeRetired, f.talkBackReservationState())
                assertEquals(beforeInput, f.pendingInputState())
                assertEquals(dispatchesBeforeRetired, requestDispatchCallbacks)
                assertTrue(ownerB.isCurrent(f.main))
                for (observation in listOf(false, true, false)) assertFalse(f.currentActuatorOutputActive(observation))
                assertTrue(f.runtime.spoken.isEmpty())
                assertEquals(0, f.backend.starts.size)
                assertEquals(beforeWalk, f.walkAndRouteState())
            }
        } catch (failure: Throwable) {
            first = failure
            throw failure
        } finally {
            // The outer original fixture then performs its existing owner/store/runtime cleanup.
            try { onMain(f.instrumentation) { f.removeOwnPendingTalkBackCallback() } }
            catch (cleanup: Throwable) { if (first == null) throw cleanup else first.addSuppressed(cleanup) }
        }
    }
    // END MIC CONTROLS ADDITION.

    private class Fixture(val instrumentation: Instrumentation) {
        val main: MainActivity
        val lifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)
        val runtime = RecordingRuntime()
        val output = SpeechOutputDiagnostics()
        val backend = CountingBackend()
        val registry: UtteranceCallbackRegistry get() = field(main, "commandSpeechResponseCallbacks") as UtteranceCallbackRegistry
        lateinit var actor: AndroidFeedbackActuator
            private set
        private var recognizer: PreferredOfflineSpeechRecognizer? = null
        private var recognizerHandler: Handler? = null
        private var gatewayGeneration: Long? = null
        private var preferencesName: String? = null
        private var preferences: SharedPreferences? = null
        private var directory: File? = null
        private var probe: AndroidStartupCapabilityProbe? = null
        var readyCallbacks = 0
            private set
        var readyFailure: Throwable? = null
            private set

        init {
            check(Looper.myLooper() == Looper.getMainLooper())
            val context = instrumentation.targetContext
            val component = ComponentName(context, MainActivity::class.java)
            @Suppress("DEPRECATION")
            main = instrumentation.newActivity(MainActivity::class.java, context, Binder(),
                context.applicationContext as Application, Intent().setComponent(component),
                context.packageManager.getActivityInfo(component, 0), "Main one-shot speech/microphone probe",
                null, null, null) as MainActivity
        }

        fun prepare() {
            val context = instrumentation.targetContext
            assertFalse(BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
            assertFalse(BuildConfig.DEVELOPMENT_QUICK_START)
            assertEquals("Actual fixture microphone permission is a prerequisite, not an injected guard",
                PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
            assertNull("Do not replace another process session", GatewaySessionProcessCoordinator.snapshot().session)
            assertNull(GatewaySessionProcessCoordinator.snapshot().restoredFirstRunSnapshot)
            val onboarding = completedOnboarding()
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            gatewayGeneration = operation.generation
            val session = GatewayFieldSession.verified("https://gateway.example.test", ACTOR,
                "walksafe_field_session=synthetic_one_shot_probe", System.currentTimeMillis() + 120_000L)
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, onboarding))
            gatewayGeneration = GatewaySessionProcessCoordinator.snapshot().generation
            setField(main, "firstRunOnboardingSnapshot", onboarding)
            setField(main, "postLoginDeviceCheckSnapshot", completedDeviceCheck(requireNotNull(gatewayGeneration)))
            setField(main, "reporterUserId", session.actorId)
            setField(main, "priorityUserOnboardingActorId", session.actorId)
            setField(main, "priorityUserOnboardingPolicy", completedPriorityEducation())
            (field(main, "permissionSessionPolicy") as PermissionSessionPolicy).authenticated(session.actorId)
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
            setField(main, "walkSessionLifecycle", lifecycle)
            setField(main, "isActivityForeground", lifecycle.snapshot().isForeground)
            val page = requireNotNull(field(main, "nativeUiPage"))
            setField(main, "nativeUiPage", page.javaClass.enumConstants.single { (it as Enum<*>).name == "VOICE_COMMAND" })
            preferencesName = "one-shot-speech-microphone-${UUID.randomUUID()}"
            preferences = context.getSharedPreferences(requireNotNull(preferencesName), Context.MODE_PRIVATE)
            setField(main, "stepLengthPrefs", requireNotNull(preferences))
            probe = AndroidStartupCapabilityProbe(context, {}) // Existing probe: start/recheck 0.
            setField(main, "startupCapabilityProbe", requireNotNull(probe))
            setField(main, "statusText", TextView(context))
            invoke("createAccessibilityStatusView", TextView::class.java) // Production semantic output target; remains unattached.
            setField(main, "detailText", TextView(context))
            setField(main, "gatewayVoiceStatusText", TextView(context))
            directory = File(context.cacheDir, "controlled-one-shot-${UUID.randomUUID()}").also { check(it.mkdir()) }
            setField(main, "handsFreeVoiceModelDirectory", directory) // Empty controlled-port input, not a real Vosk model.
            recognizerHandler = Handler(Looper.getMainLooper())
            val platform = object : PreferredSpeechPlatform {
                override fun canQueryOnDeviceSupport() = false // Select the existing Vosk-port path, not a platform-success observation.
                override fun isOnDeviceRecognitionAvailable() = false
                override fun hasMicrophonePermission() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                override fun createPlatform(): PlatformSpeechBackend = error("Platform SDK backend is outside this fixture")
                override fun createVosk(directory: File): OneShotSpeechBackend {
                    backend.inputDirectory = directory
                    return backend
                }
            }
            recognizer = PreferredOfflineSpeechRecognizer({ directory }, PreferredSpeechDependencies(
                runtime = HandlerVoiceCallbackRuntime(requireNotNull(recognizerHandler)), platform = platform,
                requests = PreferredOfflineSpeechRequest(), cooldown = PreferredPlatformCooldown(),
                emitDiagnostic = { action -> action() }))
            val fixtureRecognizer = requireNotNull(recognizer)
            setField(main, "speechRecognizer", object : VoiceCommandRecognizer {
                override fun setRecognitionListener(listener: RecognitionListener) =
                    fixtureRecognizer.setRecognitionListener(listener)
                override fun startListening(intent: Intent, preferPlatform: Boolean, isRequestCurrent: () -> Boolean) =
                    fixtureRecognizer.startListening(intent, preferPlatform, isRequestCurrent)
                override fun cancel() = fixtureRecognizer.cancel()
                override fun destroy() = fixtureRecognizer.destroy()
            })
            val generation = field(main, "feedbackLifecycleGeneration") as Int
            actor = AndroidFeedbackActuator(runtime = runtime, onOfflineKoreanSpeechReady = {
                readyCallbacks++
                try { invoke("processOfflineSpeechReady", java.lang.Boolean.TYPE,
                    arrayOf(AndroidFeedbackActuator::class.java, Int::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!),
                    actor, generation, SystemClock.elapsedRealtime()) }
                catch (problem: Throwable) { readyFailure = problem }
            })
            MainActivityControllerTestFixture.replaceActuator(main, actor)
            actor.speechOutputListener = output::record
            assertHomeAdmission()
            assertFalse(field(main, "isRouteActive") as Boolean)
            assertNull(field(main, "session"))
            assertNull((field(main, "speechOutputCollection") as AtomicReference<*>).get())
        }

        fun assertHomeAdmission() {
            assertTrue("Actual HOME admission must be prepared, not bypassed", invoke("nativeHomeFeatureContextAvailable", java.lang.Boolean.TYPE) as Boolean)
            assertTrue("Actual permission/capability HOME voice policy must allow input", invoke("homeVoiceCommandAvailable", java.lang.Boolean.TYPE) as Boolean)
            assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
        }
        fun requestCommand(): Boolean {
            val purpose = requireNotNull(field(main, "voiceRecognitionPurpose"))
            assertEquals("COMMAND", (purpose as Enum<*>).name)
            return invoke("startVoiceCommandRecognition", java.lang.Boolean.TYPE,
                arrayOf(purpose.javaClass, String::class.java), purpose, null) as Boolean
        }
        fun appOutputActive(screenReaderObservation: Boolean) = main.isHandsFreeVoiceSpeechOutputActive(
            observeCurrentActuator = { actor }, nowElapsedRealtimeMs = SystemClock::elapsedRealtime,
            observeScreenReaderActive = { screenReaderObservation })
        // BEGIN MIC CONTROLS HELPERS: existing fixture methods remain unchanged.
        fun assertIdleTalkBackBaseline() {
            assertSame(actor, field(main, "feedbackActuator"))
            assertFalse(actor.isAppSpeechActive())
            assertFalse(actor.isSpeechRecognitionBlocked())
            assertEquals(0, registry.size())
            assertNull(field(main, "gatewaySpeechPlayer"))
            assertFalse(field(main, "voiceRecognitionActive") as Boolean)
            assertNull(field(main, "pendingTalkBackInteraction"))
            for (name in listOf("lastRiskAnnouncement", "lastNavigationAnnouncement", "lastInteractionAnnouncement", "lastAdvisoryAnnouncement"))
                assertEquals(name, "", field(main, name))
            for (name in listOf("riskAnnouncementHoldUntilMs", "navigationAnnouncementHoldUntilMs", "lastRiskAnnouncementMs",
                "lastNavigationAnnouncementMs", "lastInteractionAnnouncementMs", "lastAdvisoryAnnouncementMs"))
                assertEquals(name, 0L, field(main, name))
            val view = field(main, "statusText") as TextView
            assertEquals(FIXTURE_PACKAGE, view.context.packageName)
            assertFalse("A detached View is not evidence of delivery to a real spoken service", view.isAttachedToWindow)
            for (observation in listOf(false, true, false)) assertFalse(currentActuatorOutputActive(observation))
            assertTrue(runtime.spoken.isEmpty())
            assertEquals(0, backend.starts.size)
        }
        fun currentActuatorOutputActive(screenReaderObservation: Boolean) = main.isHandsFreeVoiceSpeechOutputActive(
            observeCurrentActuator = { field(main, "feedbackActuator") as AndroidFeedbackActuator? },
            nowElapsedRealtimeMs = SystemClock::elapsedRealtime,
            observeScreenReaderActive = { screenReaderObservation })
        fun foregroundInputGeneration() = (field(main, "foregroundHomeWakeInputGeneration") as java.util.concurrent.atomic.AtomicLong).get()
        fun announceNavigation(message: String, owner: MainActivity.CommandSpeechResponseOwner, onRequestDispatched: () -> Unit): Boolean =
            invoke("announceForTalkBack", java.lang.Boolean.TYPE,
                arrayOf(String::class.java, MainActivity.TalkBackAnnouncementPriority::class.java,
                    kotlin.jvm.functions.Function0::class.java, java.lang.Integer::class.java, String::class.java,
                    kotlin.jvm.functions.Function0::class.java),
                message, MainActivity.TalkBackAnnouncementPriority.NAVIGATION, onRequestDispatched, null, null,
                { owner.isCurrent(main) }) as Boolean
        fun talkBackReservationState() = listOf(field(main, "lastRiskAnnouncement"), field(main, "lastRiskAnnouncementMs"),
            field(main, "lastRiskAnnouncementTrackId"), field(main, "lastRiskAnnouncementRank"), field(main, "riskAnnouncementHoldUntilMs"),
            field(main, "lastNavigationAnnouncement"), field(main, "lastNavigationAnnouncementMs"), field(main, "navigationAnnouncementHoldUntilMs"),
            field(main, "lastInteractionAnnouncement"), field(main, "lastInteractionAnnouncementMs"),
            field(main, "lastAdvisoryAnnouncement"), field(main, "lastAdvisoryAnnouncementMs"),
            field(main, "pendingTalkBackInteraction"), foregroundInputGeneration())
        fun removeOwnPendingTalkBackCallback() {
            // This Main/View belongs only to this fixture. Do not force an unattached View's post to run.
            val pending = field(main, "pendingTalkBackInteraction") as Runnable? ?: return
            val view = field(main, "statusText") as TextView
            check(view.context.packageName == FIXTURE_PACKAGE)
            view.removeCallbacks(pending) // Removal attempt, not a dispatch, reading or drain success ACK.
        }
        // END MIC CONTROLS HELPERS.

        fun walkAndRouteState(): List<Any?> {
            val navigator = field(main, "routeNavigator") as RouteNavigator
            return listOf(lifecycle.snapshot(), navigator.currentRouteId(), navigator.currentTactileRouteProgress(),
                navigator.pendingDecisionToken(), field(main, "isRouteActive"), field(main, "nativeUiPage"))
        }
        fun pendingInputState() = listOf(field(main, "deferredVoiceRecognitionStart"), field(main, "deferredVoiceRecognitionFailure"),
            field(main, "voiceRecognitionGeneration"), field(main, "commandSpeechResponseGeneration"),
            field(main, "voiceCommandPromptReadyGeneration"), field(main, "voiceCommandPromptPending"),
            field(main, "voiceRecognitionActive"), registry.size(), runtime.stops, backend.starts.size, walkAndRouteState())
        fun invoke(name: String, returns: Class<*>, types: Array<Class<*>> = emptyArray(), vararg values: Any?): Any? {
            val method = MainActivity::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true; check(returnType == returns) }
            return try { method.invoke(main, *values) } catch (failure: InvocationTargetException) { throw failure.targetException }
        }
        fun close() {
            var first: Throwable? = null
            fun cleanup(action: () -> Unit) { try { action() } catch (problem: Throwable) {
                if (first == null) first = problem else first!!.addSuppressed(problem)
            } }
            cleanup { invoke("cancelVoiceCommandRecognition", Void.TYPE) }
            cleanup { invoke("stopHandsFreeVoiceService", java.lang.Boolean.TYPE) }
            cleanup { recognizer?.destroy() }
            cleanup { if (::actor.isInitialized) actor.close() }
            cleanup { MainActivityControllerTestFixture.replaceActuator(main, null) }
            cleanup { runtime.close() }
            cleanup { recognizerHandler?.removeCallbacksAndMessages(null) }
            cleanup { probe?.close() }
            cleanup { lifecycle.handle(WalkSessionEvent.EnteredBackground); setField(main, "isActivityForeground", lifecycle.snapshot().isForeground) }
            cleanup { gatewayGeneration?.let { assertNotNull(GatewaySessionProcessCoordinator.clear(it)) } }
            cleanup { preferencesName?.let { instrumentation.targetContext.deleteSharedPreferences(it) } }
            cleanup { directory?.let { check(it.delete()) } } // Only our empty controlled input directory.
            first?.let { throw it }
        }
    }

    private class CountingBackend : OneShotSpeechBackend {
        val starts = mutableListOf<Long>() // MainLooper only; no audio or synthetic recognition callbacks.
        var inputDirectory: File? = null
        override fun setRecognitionListener(listener: RecognitionListener) = Unit
        override fun startListening(input: OfflineSpeechInput) { starts += SystemClock.elapsedRealtime() }
        override fun destroy() = Unit
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
        fun stopped(id: String) = requireNotNull(listener).onStop(id, true)
        fun close() { handler.removeCallbacksAndMessages(null) }
    }

    private companion object {
        const val PROMPT = "안내가 끝나면 말씀해 주세요."
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
            fun observed(): Boolean = onMain(f.instrumentation) { f.readyFailure?.let { throw it }; predicate() }
            while (!observed() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(5L)
            assertTrue(label, observed())
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
        fun completedPriorityEducation() = PriorityUserOnboardingPolicy().apply {
            selectAgeBand(PriorityUserAgeBand.ADULT_18_PLUS)
            reviewSafetyEducation(true)
            reviewPracticeNecessity(true)
            acceptEducationConsent()
            acknowledgeUsageConditions()
            finishAppUsageEducationPlayback(requireNotNull(beginAppUsageEducationPlayback()), completed = true)
            confirmSafePracticePlace()
            PriorityUserPractice.entries.forEach { practice ->
                val token = requireNotNull(beginPractice(practice))
                PriorityUserPracticeDeliverySignal.entries.forEach { recordPracticeDelivery(token, it) }
                if (practice == PriorityUserPractice.HAZARD_ALERT) confirmHazardResponse(token)
            }
            acceptAppUsageEducation()
            check(snapshot().nativeEducationComplete)
        }
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

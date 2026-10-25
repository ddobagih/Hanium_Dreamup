package kr.co.hanium.dreamup.walksafe

import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.security.AndroidSensitivePreferenceStore
import kr.co.hanium.dreamup.walksafe.security.SensitivePreferenceSpec
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exact Main practice/confirmation entry, real Actor ownership/timer, and native authenticated prefs.
 * Education and device observations are controlled typed inputs. Only speech/focus/vibration effects
 * use FeedbackSpeechRuntime; a successful vibration request is not physical vibration evidence.
 * No onCreate, Button click, GL, default native Actor factory, real authentication or physical output.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityPriorityUserHazardConfirmationAndroidTest {
    @Test(timeout = 30_000L)
    fun actualHazardPracticeRequiresCurrentTerminalDeliveryAndUserConfirmationBeforeEncryptedReadback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        verifyOwnFixtureUidBeforeSetup(instrumentation)
        val f = onMain(instrumentation) { Fixture(instrumentation) }
        var failure: Throwable? = null
        try {
            onMain(instrumentation) { f.prepare() }
            awaitOnMain(instrumentation, "Original Actor initializer callback installs a READY engine") {
                requireNotNull(f.actor).readySpeechEngineGenerationOrNull() != null
            }
            val a = onMain(instrumentation) {
                val baseline = f.profile()
                f.assertHazardIncomplete(baseline)
                f.performHazard()
                val delivery = f.captureAttempt()
                assertEquals(1, f.runtime.vibrations.size)
                assertArrayEquals(longArrayOf(0L, 140L, 90L, 140L), f.runtime.vibrations.single().pattern)
                assertEquals(PRIORITY_USER_TRAINING_VIBRATION_DURATION_MS, delivery.window.delayMs)
                f.cancelPractice()
                assertNull(field(f.main, "priorityUserPracticeAttemptToken"))
                assertNull(field(f.main, "priorityUserPracticeInFlight"))
                assertEquals(baseline, f.profile())
                delivery
            }
            // Original Actor haptic deduplication is 700ms. Keep its clock and policy intact.
            awaitElapsed(a.window.scheduledAt + 700L)
            val b = onMain(instrumentation) {
                f.performHazard()
                val current = f.captureAttempt()
                assertNotSame(a.token, current.token)
                assertTrue(current.generation > a.generation)
                assertEquals(2, f.runtime.vibrations.size)
                val profile = f.profile()
                val envelope = f.envelope()
                a.window.task.run() // Exact old production timer, not a copied consumer callback.
                f.runtime.start(a.spoken.id)
                f.runtime.done(a.spoken.id)
                f.runtime.stopped(a.spoken.id)
                assertSame(current.token, field(f.main, "priorityUserPracticeAttemptToken"))
                assertEquals(current.generation, field(f.main, "priorityUserTrainingGeneration"))
                assertEquals(profile, f.profile())
                assertEquals(envelope, f.envelope())
                current
            }
            // A's actual Actor listener callbacks are posted, so inspect after their Main queue turn.
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertSame(b.token, field(f.main, "priorityUserPracticeAttemptToken"))
                assertEquals(b.generation, field(f.main, "priorityUserTrainingGeneration"))
                f.assertHazardIncomplete(f.profile())
                assertFalse(f.policy.isHazardResponseReady(b.token))
                assertEquals(0, f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.DONE))
                assertEquals(PriorityUserPractice.HAZARD_ALERT.instructionKo, b.spoken.message)
                f.runtime.start(b.spoken.id)
            }
            awaitOnMain(instrumentation, "Real Actor accepted B's native-start input") {
                f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.STARTED) == 1
            }
            onMain(instrumentation) {
                f.assertHazardIncomplete(f.profile())
                f.runtime.done(b.spoken.id)
            }
            awaitOnMain(instrumentation, "B's original Handler timer and real Actor DONE reached Main") {
                f.policy.isHazardResponseReady(b.token) &&
                    f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.DONE) == 1
            }
            onMain(instrumentation) {
                assertTrue(SystemClock.elapsedRealtime() >= b.window.scheduledAt + b.window.delayMs)
                f.assertHazardIncomplete(f.profile())
                assertSame(b.token, field(f.main, "priorityUserPracticeAttemptToken"))
                assertFalse(f.policy.snapshot().interactivePracticeComplete)
                // Reuse the actual user entry: it takes Main's confirmation branch only now.
                f.performHazard()
                assertTrue(f.policy.snapshot().hazardResponseConfirmed)
                assertEquals(setOf(PriorityUserPractice.HAZARD_ALERT), f.policy.snapshot().completedPractices)
                assertEquals(PriorityUserPractice.PAUSE, f.policy.snapshot().nextRequiredPractice)
                assertFalse(f.policy.snapshot().interactivePracticeComplete)
                assertEquals(FirstRunOnboardingStage.FP004_TRAINING, f.onboarding().stage)
                assertNull(field(f.main, "priorityUserPracticeAttemptToken"))
                assertNull(field(f.main, "priorityUserPracticeInFlight"))
                assertFalse(requireNotNull(f.currentStore).contains(f.invalidKey))
                val confirmed = f.profile()
                assertTrue(JSONObject(confirmed).getBoolean("hazard_response_confirmed"))
                assertEquals("HAZARD_ALERT", JSONObject(confirmed).getJSONArray("completed_practices").getString(0))
                assertEquals(1, JSONObject(confirmed).getJSONArray("completed_practices").length())
                val snapshot = f.policy.snapshot()
                val envelope = f.envelope()
                // Duplicate may speak the next-practice notice. No zero-output requirement.
                f.performHazard()
                assertEquals(snapshot, f.policy.snapshot())
                assertEquals(confirmed, f.profile())
                assertEquals(envelope, f.envelope())
                assertEquals(FirstRunOnboardingStage.FP004_TRAINING, f.onboarding().stage)
                f.confirmedProfile = confirmed
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                val confirmed = requireNotNull(f.confirmedProfile)
                assertEquals(confirmed, f.profile())
                assertEquals(setOf(PriorityUserPractice.HAZARD_ALERT), f.policy.snapshot().completedPractices)
                // This new authenticated owner retires Main's store. All Main observations precede it.
                val previous = requireNotNull(f.currentStore)
                val reopened = AndroidSensitivePreferenceStore(requireNotNull(f.preferences), f.spec)
                f.currentStore = reopened
                assertFalse(reopened.isBlocked())
                assertEquals(confirmed, reopened.getString(f.profileKey, null))
                assertFalse(reopened.contains(f.invalidKey))
                assertTrue(previous.isBlocked())
                val physicalKeys = requireNotNull(f.preferences).all.keys
                assertEquals(setOf(f.spec.storageKey), physicalKeys)
                assertFalse(physicalKeys.contains(f.profileKey))
            }
        } catch (problem: Throwable) {
            failure = problem
            throw problem
        } finally {
            try { onMain(instrumentation) { f.close() }; instrumentation.waitForIdleSync() }
            catch (cleanup: Throwable) { if (failure == null) throw cleanup else failure.addSuppressed(cleanup) }
        }
    }

    private data class Attempt(
        val token: PriorityUserPracticeAttemptToken,
        val generation: Long,
        val spoken: RecordingRuntime.Spoken,
        val window: RecordingRuntime.Window,
    )

    private class Fixture(val instrumentation: Instrumentation) {
        val main: MainActivity
        val lifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)
        val policy = readyPolicy()
        val runtime = RecordingRuntime()
        val output = SpeechOutputDiagnostics()
        var actor: AndroidFeedbackActuator? = null
        var preferences: SharedPreferences? = null
        var currentStore: AndroidSensitivePreferenceStore? = null
        var confirmedProfile: String? = null
        lateinit var spec: SensitivePreferenceSpec
        lateinit var profileKey: String
        lateinit var invalidKey: String
        private var preferenceName: String? = null
        private var probe: AndroidStartupCapabilityProbe? = null
        private var gatewayGeneration: Long? = null

        init {
            val context = instrumentation.targetContext
            val component = ComponentName(context, MainActivity::class.java)
            @Suppress("DEPRECATION")
            main = instrumentation.newActivity(MainActivity::class.java, context, Binder(),
                context.applicationContext as Application, Intent().setComponent(component),
                context.packageManager.getActivityInfo(component, 0), "Main hazard confirmation probe",
                null, null, null) as MainActivity
        }

        fun prepare() {
            assertFalse(BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
            assertFalse(BuildConfig.DEVELOPMENT_QUICK_START)
            assertNull(GatewaySessionProcessCoordinator.snapshot().session)
            assertNull(GatewaySessionProcessCoordinator.snapshot().restoredFirstRunSnapshot)
            val onboarding = trainingOnboarding()
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            gatewayGeneration = operation.generation
            val session = GatewayFieldSession.verified("https://gateway.example.test", ACTOR,
                "walksafe_field_session=synthetic_hazard_confirmation_probe", System.currentTimeMillis() + 120_000L)
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, onboarding))
            gatewayGeneration = GatewaySessionProcessCoordinator.snapshot().generation
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            setField(main, "walkSessionLifecycle", lifecycle)
            setField(main, "isActivityForeground", lifecycle.snapshot().isForeground)
            setField(main, "firstRunOnboardingSnapshot", onboarding)
            setField(main, "postLoginDeviceCheckSnapshot", completedDeviceCheck(requireNotNull(gatewayGeneration)))
            setField(main, "reporterUserId", ACTOR)
            setField(main, "priorityUserOnboardingActorId", ACTOR)
            setField(main, "priorityUserOnboardingPolicy", policy)
            val context = instrumentation.targetContext
            probe = AndroidStartupCapabilityProbe(context, {}) // No start/recheck or result injection.
            setField(main, "startupCapabilityProbe", requireNotNull(probe))
            setField(main, "statusText", TextView(context))
            setField(main, "detailText", TextView(context))
            preferenceName = "priority-hazard-confirmation-${UUID.randomUUID()}"
            preferences = context.getSharedPreferences(requireNotNull(preferenceName), Context.MODE_PRIVATE)
            setField(main, "stepLengthPrefs", requireNotNull(preferences))
            spec = MainActivityControllerTestFixture.field("SENSITIVE_PREF_SPEC")
                .apply { isAccessible = true }.get(null) as SensitivePreferenceSpec
            currentStore = AndroidSensitivePreferenceStore(requireNotNull(preferences), spec)
            setField(main, "sensitivePrefs", requireNotNull(currentStore))
            profileKey = invoke("priorityUserProfileKey", String::class.java, arrayOf(String::class.java), ACTOR) as String
            invalidKey = invoke("priorityUserProfileInvalidKey", String::class.java, arrayOf(String::class.java), ACTOR) as String
            assertTrue(invoke("beginPriorityUserProfileMutationOrFailClosed", java.lang.Boolean.TYPE) as Boolean)
            assertTrue(invoke("persistPriorityUserOnboarding", java.lang.Boolean.TYPE) as Boolean)
            assertFalse(requireNotNull(currentStore).isBlocked())
            assertTrue(featureEnabled(PostLoginDeviceCheckFeature.VOICE_GUIDANCE))
            assertTrue(featureEnabled(PostLoginDeviceCheckFeature.HAPTIC_FEEDBACK))
            actor = AndroidFeedbackActuator(runtime,
                speechAllowed = { invoke("authorizeActuatorSpeechForHomeWakeInput", java.lang.Boolean.TYPE) as Boolean },
                hapticAllowed = { featureEnabled(PostLoginDeviceCheckFeature.HAPTIC_FEEDBACK) })
            requireNotNull(actor).speechOutputListener = output::record
            runtime.ready() // Captured initializer -> original Actor onInit, not a READY field assignment.
            MainActivityControllerTestFixture.replaceActuator(main, requireNotNull(actor))
            assertSame(actor, field(main, "feedbackActuator"))
            assertEquals(FirstRunOnboardingStage.FP004_TRAINING, onboarding.stage)
            assertNull((field(main, "speechOutputCollection") as AtomicReference<*>).get())
            assertNull(field(main, "positioningCollectionBinding"))
            assertNull(field(main, "session"))
            assertFalse(field(main, "isRouteActive") as Boolean)
        }

        fun featureEnabled(feature: PostLoginDeviceCheckFeature) = invoke("postLoginDeviceFeatureEnabled",
            java.lang.Boolean.TYPE, arrayOf(PostLoginDeviceCheckFeature::class.java), feature) as Boolean
        fun performHazard() = invoke("performPriorityUserPractice", Void.TYPE,
            arrayOf(PriorityUserPractice::class.java), PriorityUserPractice.HAZARD_ALERT)
        fun cancelPractice() = invoke("cancelPendingPriorityUserTrainingFeedback", Void.TYPE)
        fun profile(): String = requireNotNull(requireNotNull(currentStore).getString(profileKey, null))
        fun envelope(): String = requireNotNull(requireNotNull(preferences).getString(spec.storageKey, null))
        fun onboarding() = field(main, "firstRunOnboardingSnapshot") as FirstRunOnboardingSnapshot
        fun captureAttempt() = Attempt(
            field(main, "priorityUserPracticeAttemptToken") as PriorityUserPracticeAttemptToken,
            field(main, "priorityUserTrainingGeneration") as Long,
            runtime.spoken.last(), runtime.windows.last(),
        )
        fun assertHazardIncomplete(profile: String) {
            assertFalse(policy.snapshot().hazardResponseConfirmed)
            assertTrue(policy.snapshot().completedPractices.isEmpty())
            assertFalse(JSONObject(profile).getBoolean("hazard_response_confirmed"))
            assertEquals(0, JSONObject(profile).getJSONArray("completed_practices").length())
        }
        fun invoke(name: String, returns: Class<*>, types: Array<Class<*>> = emptyArray(), vararg values: Any?): Any? {
            val method = MainActivity::class.java.getDeclaredMethod(name, *types).apply {
                isAccessible = true
                check(returnType == returns)
            }
            return try { method.invoke(main, *values) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        }
        fun close() {
            var first: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (problem: Throwable) {
                    if (first == null) first = problem else first!!.addSuppressed(problem)
                }
            }
            cleanup { cancelPractice() }
            cleanup { invoke("cancelCommandSpeechResponse", Void.TYPE) }
            cleanup { actor?.close() }
            cleanup { runtime.close() }
            cleanup { MainActivityControllerTestFixture.replaceActuator(main, null) }
            cleanup { probe?.close() }
            cleanup {
                lifecycle.handle(WalkSessionEvent.EnteredBackground)
                setField(main, "isActivityForeground", lifecycle.snapshot().isForeground)
            }
            cleanup { gatewayGeneration?.let { assertNotNull(GatewaySessionProcessCoordinator.clear(it)) } }
            // Use the current store (fresh readback owner if opened); old Mainstore is then stale.
            // Remove only this UUID preference domain's actual Main keys; preserve shared key aliases.
            cleanup {
                currentStore?.let {
                    if (::profileKey.isInitialized && ::invalidKey.isInitialized) {
                        assertTrue(it.edit().remove(profileKey).remove(invalidKey).commit())
                    }
                }
            }
            cleanup { preferenceName?.let { assertTrue(instrumentation.targetContext.deleteSharedPreferences(it)) } }
            first?.let { throw it }
        }
    }

    private class RecordingRuntime : FeedbackSpeechRuntime {
        data class Spoken(val message: String, val id: String)
        data class Vibration(val pattern: LongArray)
        data class Window(val task: Runnable, val delayMs: Long, val scheduledAt: Long)
        val spoken = mutableListOf<Spoken>()
        val vibrations = mutableListOf<Vibration>()
        val windows = mutableListOf<Window>()
        private val handler = Handler(Looper.getMainLooper())
        private var initialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        override val scheduler = object : FeedbackSpeechScheduler {
            override fun post(task: Runnable) = handler.post(task)
            override fun postDelayed(task: Runnable, delayMs: Long): Boolean {
                if (delayMs == PRIORITY_USER_TRAINING_VIBRATION_DURATION_MS) {
                    windows += Window(task, delayMs, SystemClock.elapsedRealtime())
                }
                return handler.postDelayed(task, delayMs)
            }
            override fun removeCallbacks(task: Runnable) = handler.removeCallbacks(task)
            override fun removeCallbacksAndMessages(token: Any?) = handler.removeCallbacksAndMessages(token)
        }
        override val engineInitialized: Boolean get() = initialized != null
        override fun elapsedRealtime() = SystemClock.elapsedRealtime()
        override fun initialize(onInitialized: (Int) -> Unit) { initialized = onInitialized }
        override fun setLanguage(language: Locale) = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice() = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int {
            this.listener = listener
            return TextToSpeech.SUCCESS
        }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            spoken += Spoken(message, utteranceId)
            return TextToSpeech.SUCCESS
        }
        override fun vibrate(patternMs: LongArray): Boolean {
            vibrations += Vibration(patternMs.copyOf())
            return true // Controlled native effect only; Actor timer and Main consumer are unchanged.
        }
        override fun cancelVibration() = Unit
        override fun stop() = Unit
        override fun shutdown() = Unit
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener) = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("No tone in training fixture")
        fun ready() = requireNotNull(initialized).invoke(TextToSpeech.SUCCESS)
        fun start(id: String) = requireNotNull(listener).onStart(id)
        fun done(id: String) = requireNotNull(listener).onDone(id)
        fun stopped(id: String) = requireNotNull(listener).onStop(id, true)
        fun close() = handler.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val FIXTURE_PACKAGE = "kr.co.hanium.dreamup.walksafe.speechstorefixture"
        const val USER_PACKAGE = "kr.co.hanium.dreamup.walksafe"
        const val ACTOR = "22222222-2222-4222-8222-222222222222"
        fun readyPolicy() = PriorityUserOnboardingPolicy().apply {
            selectAgeBand(PriorityUserAgeBand.ADULT_18_PLUS)
            reviewSafetyEducation(true)
            reviewPracticeNecessity(true)
            acceptEducationConsent()
            acknowledgeUsageConditions()
            finishAppUsageEducationPlayback(requireNotNull(beginAppUsageEducationPlayback()), true)
            confirmSafePracticePlace()
        }
        fun trainingOnboarding(): FirstRunOnboardingSnapshot {
            val actor = FirstRunOpaqueActorBinding.fromProvider(ACTOR)
            val login = FirstRunOnboardingPolicy.recordVerifiedEmailLogin(FirstRunOnboardingPolicy.initialEmailAccount(1L),
                actor, FirstRunReceiptHash.fromSha256Hex("1".padStart(64, '0'))).current
            val request = FirstRunOnboardingAttemptRequest.forSnapshot(login,
                requestId = "req_${'a'.toString().repeat(64)}", attemptId = "att_${'b'.toString().repeat(64)}")
            val safety = FirstRunOnboardingPolicy.acknowledgePurposeAndSafety(login, request,
                FirstRunReceiptHash.fromSha256Hex("2".padStart(64, '0')))
            check(safety.accepted)
            val jit = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(safety.current,
                safety.current.epoch, safety.current.revision)
            check(jit.accepted)
            val device = FirstRunOnboardingPolicy.recordEmailDeviceCheckPassed(jit.current, jit.current.epoch, jit.current.revision)
            check(device.accepted && device.current.stage == FirstRunOnboardingStage.FP004_TRAINING)
            check(FirstRunOnboardingStage.FP004_TRAINING !in device.current.completedReceiptHashes)
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
        fun verifyOwnFixtureUidBeforeSetup(instrumentation: Instrumentation) {
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            val context = instrumentation.targetContext
            assertEquals(FIXTURE_PACKAGE, context.packageName)
            assertEquals(context.applicationInfo.uid, Process.myUid())
            @Suppress("DEPRECATION")
            assertNull(context.packageManager.getPackageInfo(context.packageName, 0).sharedUserId)
            // Metadata only, before fixture-owned setup; Application/SDK initialization precedes this.
            val descriptor = instrumentation.uiAutomation.executeShellCommand("cmd package list packages -U $USER_PACKAGE")
            val original = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
                reader.readLines().mapNotNull { Regex("^package:([A-Za-z0-9_.]+)\\s+uid:([0-9]+)\\s*$")
                    .matchEntire(it.trim())?.takeIf { it.groupValues[1] == USER_PACKAGE }
                    ?.groupValues?.get(2)?.toIntOrNull() }
            }
            assertEquals(1, original.size)
            assertNotEquals(original.single(), Process.myUid())
        }
        fun awaitElapsed(deadline: Long) {
            check(Looper.myLooper() != Looper.getMainLooper())
            while (SystemClock.elapsedRealtime() < deadline) Thread.sleep(5L)
        }
        fun awaitOnMain(instrumentation: Instrumentation, label: String, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 6_000L
            while (!onMain(instrumentation, predicate) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(5L)
            assertTrue(label, onMain(instrumentation, predicate))
        }
        fun <T> onMain(instrumentation: Instrumentation, action: () -> T): T {
            val result = AtomicReference<T>()
            val failure = AtomicReference<Throwable?>()
            instrumentation.runOnMainSync { try { result.set(action()) } catch (problem: Throwable) { failure.set(problem) } }
            failure.get()?.let { throw it }
            return result.get()
        }
        fun field(main: MainActivity, name: String): Any? = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.get(main)
        fun setField(main: MainActivity, name: String, value: Any?) = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.set(main, value)
    }
}

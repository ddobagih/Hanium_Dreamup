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
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
import kr.co.hanium.dreamup.walksafe.ui.FirstRunOnboardingPresenter
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SOURCE-only SDK draft: actual Main APP_USAGE caller, shared presentation, real Actor and native
 * authenticated prefs. START/DONE are controlled platform inputs, not engine/hearing/user evidence.
 * Spoken accessibility service enabled => positive app-TTS condition is unavailable; never override it.
 * Same isolated setup as accepted hazard SDK9828, with no onCreate, full UI graph, GL or network call.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityAppUsageEducationAndroidTest {
    @Test(timeout = 30_000L)
    fun actualAppUsageCallerConsumesSharedMeaningAndRecordsOnlyCurrentDoneInAuthenticatedPrefs() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        verifyOwnFixtureUidBeforeSetup(instrumentation)
        val f = onMain(instrumentation) { Fixture(instrumentation) }
        var failure: Throwable? = null
        try {
            assumeFalse("App-TTS positive requires the actual spoken service to be inactive",
                onMain(instrumentation) { f.screenReaderActive() })
            onMain(instrumentation) { f.prepare(initiallyReady = true) }
            awaitOnMain(instrumentation, "Original Actor initializer installed READY") {
                requireNotNull(f.actor).readySpeechEngineGenerationOrNull() != null
            }
            val baseline = onMain(instrumentation) { f.policy.snapshot() }
            val originalProfile = onMain(instrumentation) { f.profile() }
            val spoken = onMain(instrumentation) {
                f.assertUsageIncomplete()
                f.review() // Original private user caller; not direct Actor or policy completion.
                assertTrue(f.inFlight())
                assertNotNull(f.usageToken())
                assertSame(f.actor, field(f.main, "feedbackActuator"))
                val a = f.runtime.spoken.single()
                val expected = PriorityUserEducationPresentation.speechText(PriorityUserEducationPlayback.APP_USAGE)
                assertEquals(usageSegments().first(), a.message)
                assertTrue(expected.contains("같은 진동도 여러 이유로 울릴 수 있습니다"))
                assertTrue(expected.contains("진동만으로 안전이나 방향, 행동을 판단하지 말고"))
                assertTrue(expected.contains("음성이나 화면 설명으로 확인하세요"))
                f.assertBodyConsumed()
                f.assertUsageIncomplete()
                assertEquals(originalProfile, f.profile())
                assertEquals(1, f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.REQUESTED))
                assertEquals(0, f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.STARTED))
                assertEquals(0, f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.DONE))
                f.runtime.start(a.id)
                a
            }
            awaitOnMain(instrumentation, "Original Actor START reached the current owner") {
                f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.STARTED) == 1
            }
            onMain(instrumentation) {
                f.assertUsageIncomplete()
                assertEquals(originalProfile, f.profile())
                f.runtime.done(spoken.id)
            }
            finishRemainingUsageSegments(instrumentation, f)
            awaitOnMain(instrumentation, "Actual Main completion persisted APP_USAGE review") {
                f.policy.snapshot().appUsageReviewed &&
                    f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.DONE) == usageSegments().size
            }
            onMain(instrumentation) { f.assertCompletedAndReadBack(baseline, originalProfile) }
        } catch (problem: Throwable) { failure = problem; throw problem }
        finally {
            try { onMain(instrumentation) { f.close() }; instrumentation.waitForIdleSync() }
            catch (cleanup: Throwable) { if (failure == null) throw cleanup else failure.addSuppressed(cleanup) }
        }
    }

    @Test(timeout = 30_000L)
    fun actualEducationToggleRejectsOldTerminalAndReadyPollWithoutChangingNewUsageOwnerOrEnvelope() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        verifyOwnFixtureUidBeforeSetup(instrumentation)
        for (initiallyReady in listOf(true, false)) {
            val f = onMain(instrumentation) { Fixture(instrumentation) }
            var failure: Throwable? = null
            try {
                assumeFalse("App-TTS positive requires the actual spoken service to be inactive",
                    onMain(instrumentation) { f.screenReaderActive() })
                onMain(instrumentation) { f.prepare(initiallyReady) }
                if (initiallyReady) awaitOnMain(instrumentation, "Original Actor initializer installed READY") {
                    requireNotNull(f.actor).readySpeechEngineGenerationOrNull() != null
                }
                val baseline = onMain(instrumentation) { f.policy.snapshot() }
                val originalProfile = onMain(instrumentation) { f.profile() }
                val originalEnvelope = onMain(instrumentation) { f.envelope() }
                var waitingB: Delivery? = null
                val a = onMain(instrumentation) {
                    f.review()
                    val old = Delivery(requireNotNull(f.usageToken()), f.generation(), f.runtime.spoken.singleOrNull())
                    assertTrue(f.inFlight())
                    assertEquals(initiallyReady, old.spoken != null)
                    f.review() // Same original user caller's cancelPending branch.
                    assertFalse(f.inFlight())
                    assertNull(f.usageToken())
                    f.assertUsageIncomplete()
                    assertEquals("안전교육 듣기 중지", (field(f.main, "statusText") as TextView).text.toString())
                    assertEquals("이번 재생은 완료로 기록하지 않습니다. 이미 완료한 청취 기록은 유지됩니다.",
                        (field(f.main, "detailText") as TextView).text.toString())
                    assertEquals(baseline, f.policy.snapshot())
                    assertEquals(originalProfile, f.profile())
                    assertEquals(originalEnvelope, f.envelope())
                    if (!initiallyReady) {
                        // A and B register original Main ready polls in one queue turn. A's local
                        // Handler is never replaced, intercepted or cleared by this runtime fixture.
                        f.review()
                        waitingB = Delivery(requireNotNull(f.usageToken()), f.generation(), null)
                        assertNotSame(old.token, requireNotNull(waitingB).token)
                        assertTrue(requireNotNull(waitingB).generation > old.generation)
                        assertTrue(requireNotNull(f.actor).isSpeechInitializing())
                        assertTrue(f.runtime.spoken.isEmpty())
                        f.runtime.ready()
                    }
                    old
                }
                val b = if (initiallyReady) {
                    // Observe the existing quiet gate; no clock rewrite or new product duration.
                    awaitOnMain(instrumentation, "Original recognition quiet interval ended") {
                        !requireNotNull(f.actor).isSpeechRecognitionBlocked()
                    }
                    onMain(instrumentation) {
                        f.review()
                        Delivery(requireNotNull(f.usageToken()), f.generation(), f.runtime.spoken.last()).also {
                            assertNotSame(a.token, it.token)
                            assertTrue(it.generation > a.generation)
                            assertEquals(2, f.runtime.spoken.size)
                        }
                    }
                } else {
                    awaitOnMain(instrumentation, "Original Main ready-poll window dispatched B only") {
                        f.runtime.spoken.size == 1
                    }
                    onMain(instrumentation) {
                        requireNotNull(waitingB).copy(spoken = f.runtime.spoken.single())
                    }
                }
                instrumentation.waitForIdleSync()
                onMain(instrumentation) {
                    assertSame(b.token, f.usageToken())
                    assertEquals(b.generation, f.generation())
                    assertTrue(f.inFlight())
                    assertSame(f.actor, field(f.main, "feedbackActuator"))
                    assertEquals(baseline, f.policy.snapshot())
                    assertEquals(originalProfile, f.profile())
                    assertEquals(originalEnvelope, f.envelope())
                    a.spoken?.let { old ->
                        f.runtime.start(old.id)
                        f.runtime.done(old.id)
                        f.runtime.stopped(old.id)
                    }
                }
                instrumentation.waitForIdleSync()
                onMain(instrumentation) {
                    assertSame(b.token, f.usageToken())
                    assertEquals(b.generation, f.generation())
                    assertTrue(f.inFlight())
                    assertEquals(baseline, f.policy.snapshot())
                    assertEquals(originalProfile, f.profile())
                    assertEquals(originalEnvelope, f.envelope())
                    assertEquals(0, f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.STARTED))
                    assertEquals(0, f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.DONE))
                    val current = requireNotNull(b.spoken)
                    assertEquals(usageSegments().first(), current.message)
                    f.assertBodyConsumed()
                    f.runtime.start(current.id)
                }
                awaitOnMain(instrumentation, "B alone reached actual Actor START") {
                    f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.STARTED) == 1
                }
                onMain(instrumentation) {
                    f.assertUsageIncomplete()
                    f.runtime.done(requireNotNull(b.spoken).id)
                }
                finishRemainingUsageSegments(instrumentation, f)
                awaitOnMain(instrumentation, "Only current B completed APP_USAGE and persisted") {
                    f.policy.snapshot().appUsageReviewed &&
                        f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.DONE) == usageSegments().size
                }
                onMain(instrumentation) { f.assertCompletedAndReadBack(baseline, originalProfile) }
            } catch (problem: Throwable) { failure = problem; throw problem }
            finally {
                try { onMain(instrumentation) { f.close() }; instrumentation.waitForIdleSync() }
                catch (cleanup: Throwable) { if (failure == null) throw cleanup else failure.addSuppressed(cleanup) }
            }
        }
    }

    private fun usageSegments() = PriorityUserEducationPresentation.speechSegments(
        PriorityUserEducationPresentation.speechText(PriorityUserEducationPlayback.APP_USAGE),
    )

    private fun finishRemainingUsageSegments(instrumentation: Instrumentation, f: Fixture) {
        usageSegments().drop(1).forEachIndexed { index, message ->
            awaitOnMain(instrumentation, "Next education segment dispatched") {
                f.runtime.spoken.last().message == message
            }
            onMain(instrumentation) {
                f.assertUsageIncomplete()
                assertTrue(f.inFlight())
                assertEquals(index + 1, f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.DONE))
                val current = f.runtime.spoken.last()
                f.runtime.start(current.id)
                f.runtime.done(current.id)
            }
        }
    }

    private data class Delivery(
        val token: PriorityUserUsagePlaybackToken,
        val generation: Long,
        val spoken: RecordingRuntime.Spoken?,
    )

    private class Fixture(val instrumentation: Instrumentation) {
        val main: MainActivity
        val lifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)
        val policy = usagePendingPolicy()
        val runtime = RecordingRuntime()
        val output = SpeechOutputDiagnostics()
        var actor: AndroidFeedbackActuator? = null
        var preferences: SharedPreferences? = null
        var currentStore: AndroidSensitivePreferenceStore? = null
        var prepared = false
        val body: TextView get() = field(main, "priorityUserEducationBodyText") as TextView
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
                context.packageManager.getActivityInfo(component, 0), "Main app-usage education probe",
                null, null, null) as MainActivity
        }

        fun prepare(initiallyReady: Boolean) {
            assertFalse(BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
            assertFalse(BuildConfig.DEVELOPMENT_QUICK_START)
            assertNull(GatewaySessionProcessCoordinator.snapshot().session)
            assertNull(GatewaySessionProcessCoordinator.snapshot().restoredFirstRunSnapshot)
            val onboarding = trainingOnboarding()
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            gatewayGeneration = operation.generation
            val session = GatewayFieldSession.verified("https://gateway.example.test", ACTOR,
                "walksafe_field_session=synthetic_app_usage_education_probe", System.currentTimeMillis() + 120_000L)
            assertEquals(GatewaySessionScope.GENERAL, session.sessionScope)
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
            prepared = true
            val context = instrumentation.targetContext
            probe = AndroidStartupCapabilityProbe(context, {}) // No start/recheck or result injection.
            setField(main, "startupCapabilityProbe", requireNotNull(probe))
            setField(main, "statusText", TextView(context))
            setField(main, "detailText", TextView(context))
            prepareEducationWidgets(context)
            preferenceName = "priority-app-usage-education-${UUID.randomUUID()}"
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
            if (initiallyReady) runtime.ready() // Original Actor onInit callback; no READY field write.
            MainActivityControllerTestFixture.replaceActuator(main, requireNotNull(actor))
            assertSame(actor, field(main, "feedbackActuator"))
            assertEquals(FirstRunOnboardingStage.FP004_TRAINING, onboarding.stage)
            assertNull((field(main, "speechOutputCollection") as AtomicReference<*>).get())
            assertNull(field(main, "positioningCollectionBinding"))
            assertNull(field(main, "session"))
            assertFalse(field(main, "isRouteActive") as Boolean)
            assertFalse(policy.snapshot().appUsageReviewed)
            assertFalse(policy.snapshot().safePracticePlaceConfirmed)
            assertTrue(policy.snapshot().completedPractices.isEmpty())
            invoke("updateNativeSafetyEducationUi", Void.TYPE)
            assertBodyConsumed()
        }

        fun featureEnabled(feature: PostLoginDeviceCheckFeature) = invoke("postLoginDeviceFeatureEnabled",
            java.lang.Boolean.TYPE, arrayOf(PostLoginDeviceCheckFeature::class.java), feature) as Boolean
        fun review() = invoke("reviewPriorityUserSafetyEducation", Void.TYPE)
        fun cancelEducation() = invoke("cancelPendingPriorityUserTrainingFeedback", Void.TYPE)
        fun screenReaderActive() = invoke("isScreenReaderActive", java.lang.Boolean.TYPE) as Boolean
        fun profile(): String = requireNotNull(requireNotNull(currentStore).getString(profileKey, null))
        fun envelope(): String = requireNotNull(requireNotNull(preferences).getString(spec.storageKey, null))
        fun onboarding() = field(main, "firstRunOnboardingSnapshot") as FirstRunOnboardingSnapshot
        fun generation() = field(main, "priorityUserTrainingGeneration") as Long
        fun inFlight() = field(main, "priorityUserEducationInFlight") as Boolean
        fun usageToken(): PriorityUserUsagePlaybackToken? = PriorityUserOnboardingPolicy::class.java
            .getDeclaredField("pendingUsagePlayback").apply { isAccessible = true }
            .get(policy) as PriorityUserUsagePlaybackToken?
        fun assertUsageIncomplete() {
            val state = policy.snapshot()
            assertFalse(state.appUsageReviewed)
            assertFalse(state.appUsageAccepted)
            assertFalse(state.safePracticePlaceConfirmed)
            assertFalse(state.hazardResponseConfirmed)
            assertTrue(state.completedPractices.isEmpty())
            assertFalse(JSONObject(profile()).getBoolean("app_usage_reviewed"))
            assertEquals(0, JSONObject(profile()).getJSONArray("completed_practices").length())
        }
        fun assertBodyConsumed() {
            val expected = PriorityUserEducationPresentation.screenText(policy.snapshot())
            assertEquals(View.VISIBLE, body.visibility)
            assertEquals(expected, body.text.toString())
            assertEquals(expected, body.contentDescription.toString())
        }
        fun assertCompletedAndReadBack(baseline: PriorityUserOnboardingSnapshot, originalProfile: String) {
            assertEquals(baseline.copy(appUsageReviewed = true), policy.snapshot())
            assertFalse(inFlight())
            assertNull(usageToken())
            assertEquals(FirstRunOnboardingStage.FP004_TRAINING, onboarding().stage)
            assertBodyConsumed()
            val confirmed = profile()
            assertEquals(JSONObject(originalProfile).put("app_usage_reviewed", true).toString(), confirmed)
            assertFalse(requireNotNull(currentStore).contains(invalidKey))
            // After all Main assertions, reopen the real authenticated preference owner.
            val previous = requireNotNull(currentStore)
            val reopened = AndroidSensitivePreferenceStore(requireNotNull(preferences), spec)
            currentStore = reopened
            assertFalse(reopened.isBlocked())
            assertEquals(confirmed, reopened.getString(profileKey, null))
            assertFalse(reopened.contains(invalidKey))
            assertTrue(previous.isBlocked())
            assertEquals(setOf(spec.storageKey), requireNotNull(preferences).all.keys)
            assertFalse(requireNotNull(preferences).all.keys.contains(profileKey))
        }
        private fun prepareEducationWidgets(context: Context) {
            fun viewId(view: View): View = view.apply { id = View.generateViewId() }
            for (name in listOf("priorityUserOnboardingStatusText", "priorityUserEducationBodyText",
                "priorityUserEducationProgressText", "firstRunPhonePostureText")) {
                setField(main, name, viewId(TextView(context)))
            }
            setField(main, "firstRunPhonePostureControls", viewId(LinearLayout(context)))
            for (name in listOf("firstRunPhonePostureButton", "priorityUserSafePlaceButton",
                "priorityUserEducationButton", "priorityUserPracticeNecessityButton",
                "priorityUserEducationAgreeButton", "priorityUserResetButton", "loginSaveButton",
                "accountLogoutButton")) {
                setField(main, name, viewId(Button(context)))
            }
            setField(main, "loginUserIdInput", viewId(EditText(context)))
            @Suppress("UNCHECKED_CAST")
            val buttons = field(main, "priorityUserPracticeButtons") as MutableMap<PriorityUserPractice, Button>
            for (practice in PriorityUserPractice.entries) buttons[practice] =
                Button(context).apply { id = View.generateViewId() }
            val presenter = field(main, "firstRunOnboardingPresenter") as FirstRunOnboardingPresenter
            val education = presenter.educationBinding()
            assertSame(education, presenter.readinessBinding())
            assertSame(education.priorityUserEducationBodyText, body)
            assertSame(education.priorityUserPracticeButtons, buttons)
            presenter.bindLegacyLoginViews(FirstRunOnboardingPresenter.LegacyLoginViews(
                field(main, "loginUserIdInput") as EditText,
                field(main, "loginSaveButton") as Button,
                field(main, "accountLogoutButton") as Button,
            ))
            // EMAIL_ACCOUNT_V4 updatePriorityUserOnboardingUi delegates to this small owned view set.
            // No onCreate, full renderMainUi, layout/window attachment or GL graph is initialized.
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
            cleanup { if (prepared) cancelEducation() }
            cleanup { if (prepared) invoke("cancelCommandSpeechResponse", Void.TYPE) }
            cleanup { actor?.close() }
            cleanup { runtime.close() }
            cleanup { if (prepared) MainActivityControllerTestFixture.replaceActuator(main, null) }
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
        data class Spoken(val message: String, val id: String, val atElapsedMs: Long)
        data class Vibration(val pattern: LongArray)
        val spoken = mutableListOf<Spoken>()
        val vibrations = mutableListOf<Vibration>()
        private val handler = Handler(Looper.getMainLooper())
        private var initialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        override val scheduler = object : FeedbackSpeechScheduler {
            override fun post(task: Runnable) = handler.post(task)
            override fun postDelayed(task: Runnable, delayMs: Long): Boolean {
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
            spoken += Spoken(message, utteranceId, SystemClock.elapsedRealtime())
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
        fun usagePendingPolicy() = PriorityUserOnboardingPolicy().apply {
            selectAgeBand(PriorityUserAgeBand.ADULT_18_PLUS)
            reviewSafetyEducation(true)
            reviewPracticeNecessity(true)
            acceptEducationConsent()
            acknowledgeUsageConditions()
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

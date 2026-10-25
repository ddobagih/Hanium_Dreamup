package kr.co.hanium.dreamup.walksafe

import android.Manifest
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
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
import com.google.android.gms.location.LocationCallback
import java.lang.reflect.InvocationTargetException
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.navigation.session.LocationCollectionOwner
import kr.co.hanium.dreamup.walksafe.navigation.session.PositioningCollectionBinding
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeFalse
import org.junit.runner.RunWith

/**
 * SOURCE-only: production mounting policy -> current Main runtime consumer -> shared STOP request
 * plus actual cause speech/status/a11y text. Existing mount SDKb99 setup, no onCreate or full graph.
 * Camera/readiness inputs and speech platform effects are controlled, the Android clock is real.
 * Vibration requests return false: neither hardware success nor STOP/TTS/TalkBack perception is proven.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityPhoneMountingCauseOutputAndroidTest {
    @Test(timeout = 30_000L)
    fun actualUnusableMountAssessmentPairsCommonStopRequestWithCauseSpeechAndAccessibleStatus() = withFixture { f ->
        awaitOnMain(f.instrumentation, "Original Actor onInit reached READY") {
            f.actor.readySpeechEngineGenerationOrNull() != null
        }
        onMain(f.instrumentation) {
            val epoch = f.lifecycle.snapshot().epoch
            val originalOwner = requireNotNull(f.coordinator.currentUserMotionOwner())
            assertEquals(epoch, originalOwner.walkEpoch)
            assertTrue(f.collectionCurrent())
            assertEquals(WalkSessionState.ACTIVE, f.lifecycle.snapshot().state)
            assertEquals(GatewaySessionScope.GENERAL,
                requireNotNull(GatewaySessionProcessCoordinator.snapshot().session).sessionScope)
            assertTrue(f.runtime.speechRequests.isEmpty())
            assertTrue(f.runtime.vibrationRequests.isEmpty())

            // Every prior state is produced by the original policy, never by setting a success,
            // STOP flag, new timeout, or failure counter. The final attempt is evaluated by Main.
            val previous = f.prepareOriginalFailedRetryState(epoch)
            val camera = failedCamera(epoch, SystemClock.elapsedRealtime())
            set(f.main, "phoneMountingRuntimeState", previous)
            set(f.main, "latestPhoneMountingCameraAssessment", camera)
            val assessment = f.invoke("applyCurrentPhoneMountingRuntimeAssessment", PhoneMountingAssessment::class.java,
                arrayOf(java.lang.Boolean.TYPE, CameraFrameQualityAssessment::class.java,
                    java.lang.Long::class.java, java.lang.Long::class.java, java.lang.Boolean.TYPE),
                true, camera, null, null, false) as PhoneMountingAssessment
            assertEquals(PhoneMountingAssessmentPhase.ACTIVE, assessment.phase)
            assertEquals(PhoneMountingStatus.UNUSABLE, assessment.status)
            assertEquals(PhoneMountingReason.RETRY_LIMIT_REACHED, assessment.reason)
            assertEquals(epoch, assessment.nextState.epoch)
            assertEquals(PROFILE.maximumRuntimeRetryAttempts, assessment.nextState.failedRuntimeRetryAttempts)
            assertTrue(assessment.requiresSafetyStop)
            assertSame(assessment.nextState, field(f.main, "phoneMountingRuntimeState"))
            assertFalse(field(f.main, "phoneMountingOutputsAllowed") as Boolean)
            assertTrue(f.lifecycle.isRuntimeEpochCurrent(epoch))
            assertTrue(f.collectionCurrent())
            assertEquals(originalOwner, f.coordinator.currentUserMotionOwner())
            assertSame(f.actor, field(f.main, "feedbackActuator"))

            assertEquals(1, f.runtime.vibrationRequests.size)
            assertArrayEquals(requireNotNull(VibrationPatterns.forLevel(MessageLevel.STOP)),
                f.runtime.vibrationRequests.single())
            // Current Main's exact payload retains the producer's reason and action. A shared
            // pattern alone must never satisfy this cause-explanation assertion.
            val detail = "${assessment.accessibleReasonKo} ${assessment.accessibleActionKo} " +
                "카메라 인식·거리·위험 안내를 제한합니다. 위치 품질이 확인되면 경로 안내는 계속됩니다."
            val title = "휴대전화 장착 · 카메라 기능 제한"
            assertEquals(title, (field(f.main, "statusText") as TextView).text.toString())
            assertEquals(detail, (field(f.main, "detailText") as TextView).text.toString())
            assertEquals("WalkSafe 상태. $title. $detail",
                (field(f.main, "statusText") as TextView).contentDescription.toString())
            assertEquals(1, f.runtime.speechRequests.size)
            assertEquals(detail, f.runtime.speechRequests.single().message)
            assertTrue(f.runtime.speechRequests.single().message.contains(assessment.accessibleReasonKo))
            assertTrue(f.runtime.speechRequests.single().message.contains(assessment.accessibleActionKo))
            assertEquals(1, f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.REQUESTED))
            assertEquals(0, f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.STARTED))
            assertEquals(0, f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.DONE))
            // This leaf restricts camera outputs; it does not claim whole-walk termination.
            assertEquals(WalkSessionState.ACTIVE, f.lifecycle.snapshot().state)
        }
    }

    private class Fixture(val instrumentation: Instrumentation) {
        val main: MainActivity
        val lifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)
        val coordinator: PositioningCoordinator
        val runtime = RecordingRuntime()
        val output = SpeechOutputDiagnostics()
        lateinit var actor: AndroidFeedbackActuator
        var gatewayGeneration: Long? = null
        lateinit var binding: Any
        private var probe: AndroidStartupCapabilityProbe? = null
        private var prepared = false
        init {
            val context = instrumentation.targetContext
            val component = ComponentName(context, MainActivity::class.java)
            @Suppress("DEPRECATION")
            main = instrumentation.newActivity(MainActivity::class.java, context, Binder(),
                context.applicationContext as Application, Intent().setComponent(component),
                context.packageManager.getActivityInfo(component, 0), "Main mounting cause probe",
                null, null, null) as MainActivity
            coordinator = field(main, "positioningCoordinator") as PositioningCoordinator
        }
        fun prepare() {
            val context = instrumentation.targetContext
            assumeFalse("App-TTS positive requires actual spoken accessibility service inactive",
                invoke("isScreenReaderActive", java.lang.Boolean.TYPE) as Boolean)
            assertTrue("Existing test-candidate profile requires DEBUG; no release approval", BuildConfig.DEBUG)
            assertFalse(BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
            assertFalse(BuildConfig.DEVELOPMENT_QUICK_START)
            for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
                assertEquals("ENV_OS_PERMISSION $permission", PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(permission))
            assertTrue("ENV_LOCATION_ENABLED", requireNotNull(context.getSystemService(LocationManager::class.java)).isLocationEnabled)
            assertNull(GatewaySessionProcessCoordinator.snapshot().session)
            assertNull(GatewaySessionProcessCoordinator.snapshot().restoredFirstRunSnapshot)
            val onboarding = completedOnboarding(ACTOR)
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            gatewayGeneration = operation.generation
            val session = GatewayFieldSession.verified("https://gateway.example.test", ACTOR,
                "walksafe_field_session=synthetic_mount_cause_probe", System.currentTimeMillis() + 120_000L)
            assertEquals(GatewaySessionScope.GENERAL, session.sessionScope)
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, onboarding))
            gatewayGeneration = GatewaySessionProcessCoordinator.snapshot().generation
            set(main, "firstRunOnboardingSnapshot", onboarding)
            set(main, "postLoginDeviceCheckSnapshot", completedDeviceCheck(ACTOR, requireNotNull(gatewayGeneration)))
            set(main, "reporterUserId", ACTOR)
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            val epoch = lifecycle.snapshot().epoch
            val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
            val readiness = WalkSessionReadinessCollector(epoch, plan).apply {
                plan.requiredRequirements.forEach { record(WalkSessionReadinessObservation(epoch, it,
                    WalkSessionReadinessStatus.READY, "controlled typed mount-caller readiness input")) }
            }.build(SystemClock.elapsedRealtime())
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readiness))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
            set(main, "walkSessionLifecycle", lifecycle)
            set(main, "isActivityForeground", lifecycle.snapshot().isForeground)
            prepared = true
            for (name in listOf("phoneMountingStatusText", "statusText", "detailText")) set(main, name, TextView(context))
            probe = AndroidStartupCapabilityProbe(context, {}) // No start/recheck or hardware result injection.
            set(main, "startupCapabilityProbe", requireNotNull(probe))
            assertTrue(featureEnabled(PostLoginDeviceCheckFeature.HAPTIC_FEEDBACK))
            assertTrue(featureEnabled(PostLoginDeviceCheckFeature.VOICE_GUIDANCE))
            actor = AndroidFeedbackActuator(runtime,
                speechAllowed = { invoke("authorizeActuatorSpeechForHomeWakeInput", java.lang.Boolean.TYPE) as Boolean },
                hapticAllowed = { featureEnabled(PostLoginDeviceCheckFeature.HAPTIC_FEEDBACK) })
            actor.speechOutputListener = output::record
            MainActivityControllerTestFixture.replaceActuator(main, actor)
            runtime.ready() // Original onInit callback, not a READY or capability flag write.
            bindCurrentCollector()
        }
        fun featureEnabled(feature: PostLoginDeviceCheckFeature) = invoke("postLoginDeviceFeatureEnabled",
            java.lang.Boolean.TYPE, arrayOf(PostLoginDeviceCheckFeature::class.java), feature) as Boolean
        fun bindCurrentCollector() {
            val owner = requireNotNull(invoke("currentLocationCollectionOwnerOrNull", ownerClass))
            val locationGeneration = field(main, "locationCallbackGeneration") as Int
            val startedAt = SystemClock.elapsedRealtime()
            binding = bindingClass.getDeclaredConstructor(PositioningCoordinator::class.java, java.lang.Long.TYPE,
                ownerClass, Integer.TYPE, java.lang.Long.TYPE).apply { isAccessible = true }
                .newInstance(coordinator, coordinator.currentInputGeneration(), owner, locationGeneration, startedAt)
            set(main, "locationCollectionOwner", owner)
            set(main, "positioningCollectionBinding", binding)
            set(main, "locationCallback", object : LocationCallback() {}) // No Fused transport registration.
            assertEquals(true, invoke("bindPositioningUserMotionEpoch", java.lang.Boolean.TYPE,
                arrayOf(bindingClass, java.lang.Long.TYPE), binding, startedAt))
            assertTrue(collectionCurrent())
        }
        fun collectionCurrent() = invoke("isPositioningCollectionCurrent", java.lang.Boolean.TYPE,
            arrayOf(bindingClass), binding) as Boolean
        fun prepareOriginalFailedRetryState(epoch: WalkRuntimeEpoch): PhoneMountingRuntimeState {
            var state = PhoneMountingPolicy.initialState(epoch, PROFILE)
            var at = SystemClock.elapsedRealtime()
            val first = PhoneMountingPolicy.assess(PhoneMountingAssessmentPhase.ACTIVE, epoch, at,
                null, failedCamera(epoch, at), state)
            assertEquals(PhoneMountingStatus.CORRECTION_REQUIRED, first.status)
            state = first.nextState
            repeat(PROFILE.maximumRuntimeRetryAttempts - 1) {
                at = SystemClock.elapsedRealtime()
                val retry = PhoneMountingPolicy.assess(PhoneMountingAssessmentPhase.ACTIVE, epoch, at,
                    null, failedCamera(epoch, at), state, runtimeRetryRequested = true)
                assertEquals(PhoneMountingStatus.CORRECTION_REQUIRED, retry.status)
                state = retry.nextState
            }
            assertEquals(PROFILE.maximumRuntimeRetryAttempts - 1, state.failedRuntimeRetryAttempts)
            return state
        }
        fun invoke(name: String, returns: Class<*>, types: Array<Class<*>> = emptyArray(), vararg args: Any?): Any? {
            val method = MainActivity::class.java.getDeclaredMethod(name, *types).apply {
                isAccessible = true; check(returnType == returns)
            }
            return try { method.invoke(main, *args) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        }
        fun close() {
            var first: Throwable? = null
            fun cleanup(action: () -> Unit) { try { action() } catch (failure: Throwable) {
                if (first == null) first = failure else first!!.addSuppressed(failure) } }
            // Detach the Main command/ready owner before Actor.close can post terminal callbacks.
            cleanup { if (prepared) invoke("cancelCommandSpeechResponse", Void.TYPE) }
            cleanup { if (prepared) invoke("invalidatePhoneMountingEvidence", Void.TYPE,
                arrayOf(String::class.java), "mount-cause-fixture-ended") }
            cleanup { set(main, "locationCallback", null); set(main, "positioningCollectionBinding", null); set(main, "locationCollectionOwner", null) }
            cleanup { if (prepared) { lifecycle.handle(WalkSessionEvent.EnteredBackground)
                set(main, "isActivityForeground", lifecycle.snapshot().isForeground) } }
            cleanup { if (::actor.isInitialized) actor.close() }
            cleanup { runtime.close() }
            cleanup { if (prepared) MainActivityControllerTestFixture.replaceActuator(main, null) }
            cleanup { probe?.close() }
            cleanup { gatewayGeneration?.let { assertNotNull(GatewaySessionProcessCoordinator.clear(expectedGeneration = it)) } }
            first?.let { throw it }
        }
    }

    private class RecordingRuntime : FeedbackSpeechRuntime {
        data class SpeechRequest(val message: String, val queueMode: Int, val utteranceId: String)
        val speechRequests = mutableListOf<SpeechRequest>()
        val vibrationRequests = mutableListOf<LongArray>()
        private val handler = Handler(Looper.getMainLooper())
        private var initializeCallback: ((Int) -> Unit)? = null
        override val scheduler = object : FeedbackSpeechScheduler {
            override fun post(task: Runnable) = handler.post(task)
            override fun postDelayed(task: Runnable, delayMs: Long) = handler.postDelayed(task, delayMs)
            override fun removeCallbacks(task: Runnable) = handler.removeCallbacks(task)
            override fun removeCallbacksAndMessages(token: Any?) = handler.removeCallbacksAndMessages(token)
        }
        override val engineInitialized get() = initializeCallback != null
        override fun elapsedRealtime() = SystemClock.elapsedRealtime()
        override fun initialize(onInitialized: (Int) -> Unit) { initializeCallback = onInitialized }
        override fun setLanguage(language: Locale) = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice() = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener) = TextToSpeech.SUCCESS
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            speechRequests += SpeechRequest(message, queueMode, utteranceId)
            return TextToSpeech.SUCCESS // Same controlled submission result as mount SDKb99, not playback ACK.
        }
        override fun vibrate(patternMs: LongArray): Boolean {
            vibrationRequests += patternMs.copyOf()
            return false // Retain the runtime port's default unsuccessful result, do not fake hardware success.
        }
        override fun stop() = Unit
        override fun shutdown() = Unit
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener) = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("No tone in mounting fixture")
        fun ready() = requireNotNull(initializeCallback).invoke(TextToSpeech.SUCCESS)
        fun close() = handler.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val FIXTURE_PACKAGE = "kr.co.hanium.dreamup.walksafe.speechstorefixture"
        const val USER_PACKAGE = "kr.co.hanium.dreamup.walksafe"
        const val ACTOR = "22222222-2222-4222-8222-222222222222"
        val PROFILE = WalkSafeEnvironmentProfiles.testCandidate.phoneMounting
        val ownerClass by lazy { LocationCollectionOwner::class.java }
        val bindingClass by lazy { PositioningCollectionBinding::class.java }
        fun withFixture(test: (Fixture) -> Unit) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            assertEquals(FIXTURE_PACKAGE, context.packageName)
            assertEquals(context.applicationInfo.uid, Process.myUid())
            @Suppress("DEPRECATION")
            assertNull(context.packageManager.getPackageInfo(context.packageName, 0).sharedUserId)
            val descriptor = instrumentation.uiAutomation.executeShellCommand("cmd package list packages -U $USER_PACKAGE")
            val userUids = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
                reader.readLines().mapNotNull { Regex("^package:kr\\.co\\.hanium\\.dreamup\\.walksafe\\s+uid:([0-9]+)\\s*$")
                    .matchEntire(it.trim())?.groupValues?.get(1)?.toIntOrNull() }
            }
            assertEquals(1, userUids.size)
            assertNotEquals(userUids.single(), Process.myUid())
            var f: Fixture? = null
            var first: Throwable? = null
            try {
                val fixture = onMain(instrumentation) { Fixture(instrumentation) }
                f = fixture
                onMain(instrumentation) { fixture.prepare() }
                test(fixture)
            } catch (failure: Throwable) { first = failure }
            finally { f?.let { fixture -> try { onMain(instrumentation) { fixture.close() }; instrumentation.waitForIdleSync() }
                catch (failure: Throwable) { if (first == null) first = failure else first!!.addSuppressed(failure) } } }
            first?.let { throw it }
        }
        fun awaitOnMain(instrumentation: Instrumentation, label: String, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 6_000L
            while (!onMain(instrumentation, predicate) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(5L)
            assertTrue(label, onMain(instrumentation, predicate))
        }
        fun <T> onMain(instrumentation: Instrumentation, action: () -> T): T {
            val result = AtomicReference<T>(); val failure = AtomicReference<Throwable>()
            instrumentation.runOnMainSync { try { result.set(action()) } catch (caught: Throwable) { failure.set(caught) } }
            failure.get()?.let { throw it }; return result.get()
        }
        fun field(main: MainActivity, name: String) = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.get(main)
        fun set(main: MainActivity, name: String, value: Any?) = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.set(main, value)
        fun failedCamera(epoch: WalkRuntimeEpoch, at: Long) = CameraFrameQualityAssessment(
            epoch, at, PROFILE.cameraFrameQualityProfileId, PROFILE.maximumEvidenceAgeMs,
            EnvironmentEvidenceStatus.FAIL, CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE)
        fun completedOnboarding(actorId: String): FirstRunOnboardingSnapshot {
            fun receipt(value: Int) = FirstRunReceiptHash.fromSha256Hex(value.toString(16).padStart(64, '0'))
            val evidence = listOf(FirstRunOnboardingEvidence.VerifiedLogin(FirstRunOpaqueActorBinding.fromProvider(actorId), receipt(1)),
                FirstRunOnboardingEvidence.PurposeAndSafety(receipt(2)), FirstRunOnboardingEvidence.Fp004Training(receipt(3)))
            val restored = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(1L, evidence,
                FirstRunOnboardingEvidenceVerifier { token, value -> value in evidence && token.stage == value.stage })
            assertTrue(restored.fullyRestored)
            val jit = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(restored.snapshot, restored.snapshot.epoch, restored.snapshot.revision)
            assertTrue(jit.accepted)
            return FirstRunOnboardingPolicy.recordEmailDeviceCheckPassed(jit.current, jit.current.epoch, jit.current.revision).current.also { assertTrue(it.mayEnterWalk) }
        }
        fun completedDeviceCheck(actorId: String, generation: Long): PostLoginDeviceCheckSnapshot {
            val bound = PostLoginDeviceCheckPolicy.bindSession(PostLoginDeviceCheckPolicy.initial(), actorId, generation)
            val started = PostLoginDeviceCheckPolicy.beginFromUserAction(bound, actorId, generation, true)
            val binding = requireNotNull(started.bindingOrNull)
            val running = PostLoginDeviceCheckPolicy.beginRunning(started, binding, true)
            val ready = PostLoginDeviceCheckSignal.READY
            return PostLoginDeviceCheckPolicy.evaluate(running, binding, true, PostLoginDeviceCheckObservation(
                ready, ready, ready, ready, ready, ready, ready, ready, ready, ready, ready,
                metricDepth = PostLoginMetricDepthState.SUPPORTED)).also { assertEquals(PostLoginDeviceCheckState.FULL, it.state) }
        }
    }
}

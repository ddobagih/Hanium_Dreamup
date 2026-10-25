package kr.co.hanium.dreamup.walksafe

import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exact Main factory and Context-backed Actor; ERROR is a controlled public initialization input.
 * Onboarding/device/readiness inputs are synthetic typed inputs evaluated by their real policies.
 * No runtime injection, callback replacement, onCreate, walk-authority grant, HTTP or physical
 * Korean speech is exercised. The initial software admission is not an engine READY observation.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityNativeSpeechFactoryFailureAndroidTest {
    @Test(timeout = 30_000L)
    fun actualNativeFactoryFailureRevokesSpeechPermitAndRetiresTheActiveWalkOwner() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        verifyOwnFixtureUidBeforeSetup(instrumentation)
        val f = onMain(instrumentation) { Fixture(instrumentation) }
        var failure: Throwable? = null
        try {
            onMain(instrumentation) {
                f.prepare()
                assertEquals(WalkSessionState.ACTIVE, f.lifecycle.snapshot().state)
                assertNotNull(f.lifecycle.currentRuntimeEpochOrNull())
                assertNull(field(f.main, "startupCapabilityDecision"))
                assertNull(field(f.main, "offlineKoreanTextToSpeechCapabilityOverride"))
                assertTrue(f.voiceFeatureEnabled())
                assertTrue(f.navigationSpeechAllowed())
                assertNull(field(f.main, "feedbackActuator"))
                f.generationBeforeFactory = field(f.main, "feedbackLifecycleGeneration") as Int

                // One MainLooper action: posted native init callbacks cannot run between creation
                // and the controlled ERROR. Invoke the actual default factory, never its callback.
                val created = f.invoke("ensureFeedbackActuator", AndroidFeedbackActuator::class.java)
                    as AndroidFeedbackActuator
                f.ownedActor = created
                assertSame(created, field(f.main, "feedbackActuator"))
                assertEquals(f.generationBeforeFactory, field(f.main, "feedbackLifecycleGeneration"))
                assertTrue(created.isSpeechInitializing())
                assertNull(created.readySpeechEngineGenerationOrNull())
                created.onInit(TextToSpeech.ERROR)
            }
            awaitOnMain(instrumentation, "Original unavailable callback must finish Main's safety-stop cleanup") {
                field(f.main, "offlineKoreanTextToSpeechCapabilityOverride") == false &&
                    f.lifecycle.snapshot().state == WalkSessionState.SAFE_STOP &&
                    field(f.main, "feedbackActuator") == null &&
                    f.ownedActor?.statusText() == "tts=closed"
            }
            onMain(instrumentation) {
                val actor = requireNotNull(f.ownedActor)
                assertFalse(requireNotNull(field(f.main, "offlineKoreanTextToSpeechCapabilityOverride") as Boolean?))
                val disabled = f.invoke("currentPostLoginDisabledFeatures", Set::class.java) as Set<*>
                assertTrue(PostLoginDeviceCheckFeature.VOICE_GUIDANCE in disabled)
                assertFalse(f.voiceFeatureEnabled())
                val decision = field(f.main, "startupCapabilityDecision") as WalkSafeStartupCapabilityDecision
                assertTrue(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS in decision.unavailableRequirements)
                assertTrue(requireNotNull(f.preferences).getString("startup_capability_unavailable", "")
                    .orEmpty().split(',').contains(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS.name))
                assertFalse(f.navigationSpeechAllowed())
                assertFalse(f.main.authorizeNativeFeedbackSpeech(SystemClock.elapsedRealtime(), false))
                assertEquals(WalkSessionState.SAFE_STOP, f.lifecycle.snapshot().state)
                assertNull(f.lifecycle.currentRuntimeEpochOrNull())
                assertTrue((field(f.main, "feedbackLifecycleGeneration") as Int) > f.generationBeforeFactory)
                assertNull(field(f.main, "confirmedStartupCapabilityDecision"))
                assertNull(field(f.main, "feedbackActuator"))
                assertEquals("tts=closed", actor.statusText())
                assertFalse(actor.isSpeechInitializing())
                assertNull(actor.readySpeechEngineGenerationOrNull())

                // A controlled callback on this retired object must not reopen Main's permission.
                val stopped = f.lifecycle.snapshot()
                val retiredGeneration = field(f.main, "feedbackLifecycleGeneration")
                actor.onInit(TextToSpeech.SUCCESS)
                assertEquals("tts=closed", actor.statusText())
                assertNull(actor.readySpeechEngineGenerationOrNull())
                assertFalse(f.navigationSpeechAllowed())
                assertFalse(f.main.authorizeNativeFeedbackSpeech(SystemClock.elapsedRealtime(), false))
                assertNull(field(f.main, "feedbackActuator"))
                assertEquals(retiredGeneration, field(f.main, "feedbackLifecycleGeneration"))
                assertEquals(stopped, f.lifecycle.snapshot())
            }
        } catch (problem: Throwable) {
            failure = problem
            throw problem
        } finally {
            try {
                onMain(instrumentation) { f.close() }
                instrumentation.waitForIdleSync()
            } catch (cleanup: Throwable) {
                if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
            }
        }
    }

    private class Fixture(val instrumentation: Instrumentation) {
        val main: MainActivity
        val lifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)
        var ownedActor: AndroidFeedbackActuator? = null
        var generationBeforeFactory = -1
        var preferences: SharedPreferences? = null
        private var probe: AndroidStartupCapabilityProbe? = null
        private var resourceProbe: AndroidWalkSessionResourceProbe? = null
        private var preferenceName: String? = null
        private var gatewayGeneration: Long? = null
        private var renewalHandler: Handler? = null

        init {
            check(Looper.myLooper() == Looper.getMainLooper())
            val context = instrumentation.targetContext
            val component = ComponentName(context, MainActivity::class.java)
            @Suppress("DEPRECATION")
            main = instrumentation.newActivity(MainActivity::class.java, context, Binder(),
                context.applicationContext as Application, Intent().setComponent(component),
                context.packageManager.getActivityInfo(component, 0), "Main native speech failure probe",
                null, null, null) as MainActivity
        }

        fun prepare() {
            assertFalse(BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
            assertFalse(BuildConfig.DEVELOPMENT_QUICK_START)
            assertNull("Do not replace another process account", GatewaySessionProcessCoordinator.snapshot().session)
            assertNull(GatewaySessionProcessCoordinator.snapshot().restoredFirstRunSnapshot)
            val onboarding = completedOnboarding()
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            gatewayGeneration = operation.generation
            val session = GatewayFieldSession.verified("https://gateway.example.test", ACTOR,
                "walksafe_field_session=synthetic_native_failure_probe", System.currentTimeMillis() + 120_000L)
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, onboarding))
            gatewayGeneration = GatewaySessionProcessCoordinator.snapshot().generation
            setField(main, "firstRunOnboardingSnapshot", onboarding)
            setField(main, "postLoginDeviceCheckSnapshot", completedDeviceCheck(requireNotNull(gatewayGeneration)))
            setField(main, "reporterUserId", session.actorId)
            (field(main, "permissionSessionPolicy") as PermissionSessionPolicy).authenticated(session.actorId)
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
            val epoch = lifecycle.snapshot().epoch
            val readiness = WalkSessionReadinessCollector(epoch, plan).apply {
                plan.requiredRequirements.forEach { requirement -> record(WalkSessionReadinessObservation(
                    epoch, requirement, WalkSessionReadinessStatus.READY, "controlled typed native-failure readiness input")) }
            }.build(SystemClock.elapsedRealtime())
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readiness))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            setField(main, "walkSessionLifecycle", lifecycle)
            setField(main, "isActivityForeground", lifecycle.snapshot().isForeground)
            renewalHandler = Handler(Looper.getMainLooper())
            setField(main, "gatewayWalkRenewalHandler", requireNotNull(renewalHandler))
            // No GatewayWalkAuthorityController start/grant: safety stop must not send an HTTP end.
            val context = instrumentation.targetContext
            resourceProbe = AndroidWalkSessionResourceProbe(context)
            setField(main, "walkSessionResourceProbe", requireNotNull(resourceProbe))
            preferenceName = "native-speech-failure-${UUID.randomUUID()}"
            preferences = context.getSharedPreferences(requireNotNull(preferenceName), Context.MODE_PRIVATE)
            setField(main, "stepLengthPrefs", requireNotNull(preferences))
            probe = AndroidStartupCapabilityProbe(context, {}) // start/recheck deliberately not called.
            setField(main, "startupCapabilityProbe", requireNotNull(probe))
            setField(main, "priorityUserOnboardingPolicy", PriorityUserOnboardingPolicy())
            listOf("statusText", "detailText", "startupCapabilityText",
                "postLoginDeviceCheckWakePhraseInstructionText", "postLoginDeviceCheckHapticQuestionText")
                .forEach { setField(main, it, TextView(context)) }
            listOf("startupMetricPreflightButton", "postLoginDeviceCheckSettingsButton", "voiceDataInstallButton",
                "postLoginDeviceCheckWakePhraseStartButton", "postLoginDeviceCheckHapticConfirmButton",
                "postLoginDeviceCheckHapticRejectButton", "startupCapabilityConfirmButton")
                .forEach { setField(main, it, Button(context)) }
            assertNull((field(main, "speechOutputCollection") as AtomicReference<*>).get())
            assertNull(field(main, "positioningCollectionBinding"))
            assertNull(field(main, "session"))
            assertFalse(field(main, "isRouteActive") as Boolean)
        }

        fun voiceFeatureEnabled() = invoke("postLoginDeviceFeatureEnabled", java.lang.Boolean.TYPE,
            arrayOf(PostLoginDeviceCheckFeature::class.java), PostLoginDeviceCheckFeature.VOICE_GUIDANCE) as Boolean
        fun navigationSpeechAllowed() = invoke("navigationSpeechCapabilityAllowed", java.lang.Boolean.TYPE) as Boolean

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
            // Each resource is attempted even after a setup/assertion/cleanup failure.
            cleanup { (field(main, "feedbackActuator") as AndroidFeedbackActuator?)?.close() }
            cleanup { ownedActor?.close() }
            cleanup { MainActivityControllerTestFixture.replaceActuator(main, null) }
            cleanup { probe?.close() }
            cleanup { resourceProbe?.close() }
            cleanup { renewalHandler?.removeCallbacksAndMessages(null) }
            cleanup {
                lifecycle.handle(WalkSessionEvent.EnteredBackground)
                setField(main, "isActivityForeground", lifecycle.snapshot().isForeground)
            }
            cleanup { gatewayGeneration?.let { expected -> assertNotNull(GatewaySessionProcessCoordinator.clear(expected)) } }
            cleanup { preferenceName?.let { instrumentation.targetContext.deleteSharedPreferences(it) } }
            first?.let { throw it }
        }
    }

    companion object {
        private const val FIXTURE_PACKAGE = "kr.co.hanium.dreamup.walksafe.speechstorefixture"
        private const val USER_PACKAGE = "kr.co.hanium.dreamup.walksafe"
        private const val ACTOR = "22222222-2222-4222-8222-222222222222"

        private fun verifyOwnFixtureUidBeforeSetup(instrumentation: Instrumentation) {
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            val context = instrumentation.targetContext
            assertEquals(FIXTURE_PACKAGE, context.packageName)
            assertEquals(context.applicationInfo.uid, Process.myUid())
            @Suppress("DEPRECATION")
            val sharedUid = context.packageManager.getPackageInfo(context.packageName, 0).sharedUserId
            assertNull(sharedUid)
            // Metadata only; this precedes fixture-owned setup, not Application/SDK initialization.
            val descriptor = instrumentation.uiAutomation.executeShellCommand("cmd package list packages -U $USER_PACKAGE")
            val original = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
                reader.readLines().mapNotNull { Regex("^package:([A-Za-z0-9_.]+)\\s+uid:([0-9]+)\\s*$")
                    .matchEntire(it.trim())?.takeIf { it.groupValues[1] == USER_PACKAGE }
                    ?.groupValues?.get(2)?.toIntOrNull() }
            }
            assertEquals(1, original.size)
            assertNotEquals(original.single(), Process.myUid())
        }

        private fun awaitOnMain(instrumentation: Instrumentation, label: String, predicate: () -> Boolean) {
            check(Looper.myLooper() != Looper.getMainLooper())
            val deadline = SystemClock.elapsedRealtime() + 6_000L
            while (!onMain(instrumentation, predicate) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(5L)
            assertTrue(label, onMain(instrumentation, predicate))
        }

        private fun <T> onMain(instrumentation: Instrumentation, action: () -> T): T {
            val result = AtomicReference<T>()
            val failure = AtomicReference<Throwable?>()
            instrumentation.runOnMainSync { try { result.set(action()) } catch (problem: Throwable) { failure.set(problem) } }
            failure.get()?.let { throw it }
            return result.get()
        }

        private fun field(main: MainActivity, name: String): Any? = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.get(main)
        private fun setField(main: MainActivity, name: String, value: Any?) = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.set(main, value)
        private fun receipt(value: Int) = FirstRunReceiptHash.fromSha256Hex(value.toString(16).padStart(64, '0'))

        private fun completedOnboarding(): FirstRunOnboardingSnapshot {
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

        private fun completedDeviceCheck(generation: Long): PostLoginDeviceCheckSnapshot {
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

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
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.runtime.metric.RuntimeMetricSessionController
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Draft SDK counter for the mechanically extracted INSTALL_REQUESTED body, including its real
 * ACTIVE lifecycle safety stop and cleanup. Context, MainLooper, clocks and own preferences are
 * Android APIs; onboarding/device/readiness and the GENERAL session are controlled typed inputs.
 * This invokes the returned-install-status body directly. It does not execute requestInstall,
 * the outer start admission, a server walk grant, onResume, fresh AR queries or an AR Session.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityArInstallReturnAndroidTest {
    @Test(timeout = 30_000L)
    fun installRequestedRetainsThePassedDeviceBindingForAFreshReturnCheck() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        verifyOwnFixtureUidBeforeSetup(instrumentation)
        val f = onMain(instrumentation) { Fixture(instrumentation) }
        var failure: Throwable? = null
        try {
            onMain(instrumentation) {
                f.prepare()
                val passed = field(f.main, "postLoginDeviceCheckSnapshot") as PostLoginDeviceCheckSnapshot
                val originalBinding = requireNotNull(passed.bindingOrNull)
                assertEquals(PostLoginDeviceCheckState.FULL, passed.state)
                assertTrue(f.invoke("isPostLoginDeviceCheckSnapshotBindingLive", java.lang.Boolean.TYPE,
                    arrayOf(PostLoginDeviceCheckBinding::class.java), originalBinding) as Boolean)
                assertEquals(WalkSessionState.ACTIVE, f.lifecycle.snapshot().state)
                assertNotNull(f.lifecycle.currentRuntimeEpochOrNull())
                assertNull(field(f.main, "storedMetricDepthRefreshContext"))
                assertFalse(field(f.main, "storedMetricDepthRefreshPending") as Boolean)
                assertNull(field(f.main, "session"))
                assertEquals("NONE", (field(f.main, "arSessionPurpose") as Enum<*>).name)
                val originalAttempt = f.metricSession.preflightGeneration
                val originalLifecycle = field(f.main, "feedbackLifecycleGeneration") as Int

                // The original invalidator, safety stop, independent cleanup and UI body run once.
                f.invoke("handleRuntimeArInstallRequested", Void.TYPE)
                assertNull("A missing fixture resource must not be hidden as an AR counterexample",
                    field(f.main, "lastRuntimeArSessionCloseFailure"))
                assertEquals(WalkSessionState.SAFE_STOP, f.lifecycle.snapshot().state)
                assertNull(f.lifecycle.currentRuntimeEpochOrNull())
                assertTrue(field(f.main, "installRequested") as Boolean)
                assertFalse(field(f.main, "pendingSessionStartAfterInstall") as Boolean)
                assertFalse(field(f.main, "runtimeMetricOutputAllowed") as Boolean)
                assertTrue(f.metricSession.preflightGeneration > originalAttempt)
                assertTrue((field(f.main, "feedbackLifecycleGeneration") as Int) > originalLifecycle)
                assertNull(field(f.main, "session"))
                assertEquals("NONE", (field(f.main, "arSessionPurpose") as Enum<*>).name)
                assertSame(passed, field(f.main, "postLoginDeviceCheckSnapshot"))

                // Same expectation on the extraction-only baseline and future minimal recovery fix.
                val context = field(f.main, "storedMetricDepthRefreshContext")
                assertNotNull("INSTALL_REQUESTED must retain the original passed binding for the existing return refresh", context)
                val retained = requireNotNull(context)
                assertEquals(originalBinding, retained.javaClass.getDeclaredField("binding")
                    .apply { isAccessible = true }.get(retained))
                assertTrue(field(f.main, "storedMetricDepthRefreshPending") as Boolean)
                assertTrue(field(f.main, "storedMetricDepthRefreshRetryAllowed") as Boolean)
                assertTrue((field(f.main, "statusText") as TextView).text.toString().contains("ARCore 설치 필요"))
                assertEquals("NONE", f.metricSession.preflightOwner.name)
                assertNull(f.metricSession.preflightBinding)
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

    @Test(timeout = 30_000L)
    fun aRetainedCurrentContextRejectsAReplacementGeneralOwner() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        verifyOwnFixtureUidBeforeSetup(instrumentation)
        val f = onMain(instrumentation) { Fixture(instrumentation) }
        var failure: Throwable? = null
        try {
            onMain(instrumentation) {
                f.prepare()
                f.invoke("handleRuntimeArInstallRequested", Void.TYPE)
                assertNull(field(f.main, "lastRuntimeArSessionCloseFailure"))
                val context = requireNotNull(field(f.main, "storedMetricDepthRefreshContext"))
                assertTrue(f.invoke("isStoredMetricDepthRefreshContextCurrent", java.lang.Boolean.TYPE,
                    arrayOf(context.javaClass), context) as Boolean)
                val oldOwner = GatewaySessionProcessCoordinator.snapshot()
                val replacement = f.replaceOwnedGatewaySession()
                val newOwner = GatewaySessionProcessCoordinator.snapshot()
                assertSame(replacement, newOwner.session)
                assertEquals(requireNotNull(oldOwner.session).actorId, replacement.actorId)
                assertNotSame(oldOwner.session, newOwner.session)
                assertNotEquals(oldOwner.generation, newOwner.generation)
                assertFalse(f.invoke("isStoredMetricDepthRefreshContextCurrent", java.lang.Boolean.TYPE,
                    arrayOf(context.javaClass), context) as Boolean)
                assertSame(context, field(f.main, "storedMetricDepthRefreshContext"))
                assertSame(replacement, GatewaySessionProcessCoordinator.snapshot().session)
                assertEquals(newOwner.generation, GatewaySessionProcessCoordinator.snapshot().generation)
                assertTrue(GatewaySessionProcessCoordinator.isCurrentGeneralSession(newOwner.generation, replacement))
                assertFalse(field(f.main, "runtimeMetricOutputAllowed") as Boolean)
                assertNull(field(f.main, "session"))
                assertEquals("NONE", (field(f.main, "arSessionPurpose") as Enum<*>).name)
            }
        } catch (problem: Throwable) { failure = problem; throw problem }
        finally {
            try { onMain(instrumentation) { f.close() }; instrumentation.waitForIdleSync() }
            catch (cleanup: Throwable) { if (failure == null) throw cleanup else failure.addSuppressed(cleanup) }
        }
    }

    @Test(timeout = 30_000L)
    fun aNullCompletionPreservesTheCurrentContextWithoutEnablingOutput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        verifyOwnFixtureUidBeforeSetup(instrumentation)
        val f = onMain(instrumentation) { Fixture(instrumentation) }
        var failure: Throwable? = null
        try {
            onMain(instrumentation) {
                f.prepare()
                val passed = field(f.main, "postLoginDeviceCheckSnapshot") as PostLoginDeviceCheckSnapshot
                val binding = requireNotNull(passed.bindingOrNull)
                f.invoke("handleRuntimeArInstallRequested", Void.TYPE)
                assertNull(field(f.main, "lastRuntimeArSessionCloseFailure"))
                val context = requireNotNull(field(f.main, "storedMetricDepthRefreshContext"))
                assertTrue(f.invoke("isStoredMetricDepthRefreshContextCurrent", java.lang.Boolean.TYPE,
                    arrayOf(context.javaClass), context) as Boolean)
                assertEquals(binding, context.javaClass.getDeclaredField("binding")
                    .apply { isAccessible = true }.get(context))
                assertTrue(field(f.main, "storedMetricDepthRefreshPending") as Boolean)
                assertTrue(field(f.main, "storedMetricDepthRefreshRetryAllowed") as Boolean)
                assertFalse(f.invoke("completeStoredMetricDepthRefresh", java.lang.Boolean.TYPE,
                    arrayOf(Boolean::class.javaObjectType, PostLoginDeviceCheckBinding::class.java), null, binding) as Boolean)
                assertSame(context, field(f.main, "storedMetricDepthRefreshContext"))
                assertSame(passed, field(f.main, "postLoginDeviceCheckSnapshot"))
                assertTrue(field(f.main, "storedMetricDepthRefreshPending") as Boolean)
                assertTrue(field(f.main, "storedMetricDepthRefreshRetryAllowed") as Boolean)
                assertTrue(f.invoke("isStoredMetricDepthRefreshContextCurrent", java.lang.Boolean.TYPE,
                    arrayOf(context.javaClass), context) as Boolean)
                assertFalse(field(f.main, "runtimeMetricOutputAllowed") as Boolean)
                assertNull(field(f.main, "session"))
                assertEquals("NONE", (field(f.main, "arSessionPurpose") as Enum<*>).name)
            }
        } catch (problem: Throwable) { failure = problem; throw problem }
        finally {
            try { onMain(instrumentation) { f.close() }; instrumentation.waitForIdleSync() }
            catch (cleanup: Throwable) { if (failure == null) throw cleanup else failure.addSuppressed(cleanup) }
        }
    }

    private class Fixture(val instrumentation: Instrumentation) {
        val main: MainActivity
        val metricSession: RuntimeMetricSessionController<*, *, *>
            get() = MainActivityControllerTestFixture.controller(main, "runtimeMetricSession") as RuntimeMetricSessionController<*, *, *>
        val lifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)
        private var gatewayGeneration: Long? = null
        private var preferenceName: String? = null
        private var preferences: SharedPreferences? = null
        private var renewalHandler: Handler? = null
        private var probe: AndroidStartupCapabilityProbe? = null

        init {
            check(Looper.myLooper() == Looper.getMainLooper())
            val context = instrumentation.targetContext
            val component = ComponentName(context, MainActivity::class.java)
            @Suppress("DEPRECATION")
            main = instrumentation.newActivity(MainActivity::class.java, context, Binder(),
                context.applicationContext as Application, Intent().setComponent(component),
                context.packageManager.getActivityInfo(component, 0), "Main AR install-return body probe",
                null, null, null) as MainActivity
        }

        fun prepare() {
            assertFalse(BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
            assertFalse(BuildConfig.DEVELOPMENT_QUICK_START)
            assertNull("Do not replace another process session", GatewaySessionProcessCoordinator.snapshot().session)
            assertNull(GatewaySessionProcessCoordinator.snapshot().restoredFirstRunSnapshot)
            val onboarding = completedOnboarding()
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            gatewayGeneration = operation.generation
            val session = GatewayFieldSession.verified("https://gateway.example.test", ACTOR,
                "walksafe_field_session=synthetic_ar_return_body_probe", System.currentTimeMillis() + 120_000L)
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
                plan.requiredRequirements.forEach { record(WalkSessionReadinessObservation(epoch, it,
                    WalkSessionReadinessStatus.READY, "controlled typed AR install-return readiness input")) }
            }.build(SystemClock.elapsedRealtime())
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readiness))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            setField(main, "walkSessionLifecycle", lifecycle)
            setField(main, "isActivityForeground", lifecycle.snapshot().isForeground)
            renewalHandler = Handler(Looper.getMainLooper())
            setField(main, "gatewayWalkRenewalHandler", requireNotNull(renewalHandler))
            val context = instrumentation.targetContext
            preferenceName = "ar-install-return-${UUID.randomUUID()}"
            preferences = context.getSharedPreferences(requireNotNull(preferenceName), Context.MODE_PRIVATE)
            setField(main, "stepLengthPrefs", requireNotNull(preferences))
            probe = AndroidStartupCapabilityProbe(context, {}) // Existing probe; start/recheck are not called.
            setField(main, "startupCapabilityProbe", requireNotNull(probe))
            setField(main, "statusText", TextView(context))
            setField(main, "detailText", TextView(context))
            // No walk authority grant means the real safety stop cannot send an HTTP walk end.
            // No startupCapabilityText means its existing optional UI refresh returns as in other fixtures.
            assertNull((field(main, "speechOutputCollection") as AtomicReference<*>).get())
            assertNull(field(main, "positioningCollectionBinding"))
            assertNull(field(main, "feedbackActuator"))
            assertFalse(field(main, "isRouteActive") as Boolean)
        }

        fun replaceOwnedGatewaySession(): GatewayFieldSession {
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            gatewayGeneration = operation.generation
            val replacement = GatewayFieldSession.verified("https://gateway.example.test", ACTOR,
                "walksafe_field_session=synthetic_ar_return_replacement", System.currentTimeMillis() + 120_000L)
            try {
                assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, replacement,
                    field(main, "firstRunOnboardingSnapshot") as FirstRunOnboardingSnapshot))
            } finally {
                val current = GatewaySessionProcessCoordinator.snapshot()
                if (current.session === replacement) gatewayGeneration = current.generation
            }
            return replacement
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
            cleanup { invoke("clearStoredMetricDepthRefresh", Void.TYPE) }
            cleanup { probe?.close() }
            cleanup { renewalHandler?.removeCallbacksAndMessages(null) }
            cleanup {
                lifecycle.handle(WalkSessionEvent.EnteredBackground)
                setField(main, "isActivityForeground", lifecycle.snapshot().isForeground)
            }
            cleanup { gatewayGeneration?.let { assertNotNull(GatewaySessionProcessCoordinator.clear(it)) } }
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
            assertNull(context.packageManager.getPackageInfo(context.packageName, 0).sharedUserId)
            val descriptor = instrumentation.uiAutomation.executeShellCommand("cmd package list packages -U $USER_PACKAGE")
            val original = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
                reader.readLines().mapNotNull { Regex("^package:([A-Za-z0-9_.]+)\\s+uid:([0-9]+)\\s*$")
                    .matchEntire(it.trim())?.takeIf { it.groupValues[1] == USER_PACKAGE }
                    ?.groupValues?.get(2)?.toIntOrNull() }
            }
            assertEquals(1, original.size)
            assertNotEquals(original.single(), Process.myUid())
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

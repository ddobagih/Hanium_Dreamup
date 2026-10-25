package kr.co.hanium.dreamup.walksafe

import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real process retention/controller APIs -> actual Main foreground revalidation/worker/Looper.
 * Context/elapsed time/Handler are Android inputs; authentication/device/readiness and the
 * HTTP response are controlled. Does not run onCreate, OS recreation, a server or native TTS. */
@RunWith(AndroidJUnit4::class)
class MainActivityConfigurationWalkRenewalAndroidTest {
    @Test(timeout = 30_000L)
    fun heldPausedWalkIsTakenOnceAndActualMainRenewsTheSameLeaseAfterForegroundReturn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals("Isolated fixture target only", "kr.co.hanium.dreamup.walksafe.speechstorefixture",
            instrumentation.targetContext.packageName)
        val f = onMain(instrumentation) { Fixture(instrumentation) }
        var failure: Throwable? = null
        try {
            onMain(instrumentation) { f.prepareAndRevalidate() }
            val waitUntil = SystemClock.elapsedRealtime() + 5_000L
            while (SystemClock.elapsedRealtime() < waitUntil && !onMain(instrumentation) { f.completed() }) {
                Thread.sleep(20L)
            }
            onMain(instrumentation) {
                assertTrue("Actual worker result must be applied by Main's Looper callback", f.completed())
                assertEquals(1, f.transport.commands.size)
                val command = f.transport.commands.single()
                assertEquals("renew", command.getString("action"))
                assertEquals(f.lease.walkId, command.getString("walk_id"))
                assertEquals(f.lease.leaseId, command.getString("lease_id"))
                assertEquals(f.lease.fencingToken, command.getLong("fencing_token"))
                assertTrue(command.getString("request_id").isNotBlank())
                assertEquals(f.pausedEpoch, f.lifecycle.snapshot().epoch)
                assertEquals(WalkSessionState.PAUSED, f.lifecycle.snapshot().state)
                assertEquals(WalkSessionRecoveryStage.RECHECK_REQUIRED, f.lifecycle.snapshot().recoveryStage)
                assertNull(f.lifecycle.snapshot().confirmationToken)
                assertNull(f.oldAuthority.activeLeaseOrNull(f.pausedEpoch))
                assertNull(processWalkConfigurationChangeRetention.take(SystemClock.elapsedRealtime()))
                assertFalse(field(f.main, "gatewayWalkResumeRevalidationPending") as Boolean)
                assertSame(f.session, GatewaySessionProcessCoordinator.snapshot().session)
            }
        } catch (caught: Throwable) {
            failure = caught
        } finally {
            try { onMain(instrumentation) { f.close() } } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
            }
            try {
                // Main delegates to this process-owned FIFO worker; the fixture never shuts it down.
                val drained = CountDownLatch(1)
                GatewaySessionProcessCoordinator.executor.execute { drained.countDown() }
                assertTrue("Prior process work must drain outside MainLooper", drained.await(5L, TimeUnit.SECONDS))
            }
            catch (cleanup: Throwable) { if (failure == null) failure = cleanup else failure.addSuppressed(cleanup) }
        }
        failure?.let { throw it }
    }

    private class Fixture(val instrumentation: Instrumentation) {
        val main: MainActivity
        val lifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)
        val oldAuthority = GatewayWalkAuthorityController(elapsedClock = SystemClock::elapsedRealtime)
        val transport = Transport()
        lateinit var session: GatewayFieldSession
        lateinit var lease: GatewayWalkLease
        lateinit var pausedEpoch: WalkRuntimeEpoch
        private var gatewayGeneration: Long? = null
        private var retainedByFixture = false
        private val authority: GatewayWalkAuthorityController

        init {
            val context = instrumentation.targetContext
            val component = ComponentName(context, MainActivity::class.java)
            @Suppress("DEPRECATION")
            main = instrumentation.newActivity(MainActivity::class.java, context, Binder(),
                context.applicationContext as Application, Intent().setComponent(component),
                context.packageManager.getActivityInfo(component, 0), "Same walk renewal probe", null, null, null) as MainActivity
            authority = field(main, "gatewayWalkAuthorityController") as GatewayWalkAuthorityController
        }

        fun prepareAndRevalidate() {
            assertFalse(BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
            assertNull("Do not replace another login", GatewaySessionProcessCoordinator.snapshot().session)
            assertNull("Do not overwrite another retained walk", field(processWalkConfigurationChangeRetention, "retained"))
            val onboarding = restoredOnboarding()
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            gatewayGeneration = operation.generation
            session = GatewayFieldSession.longLivedSession("https://gateway.example.test", ACTOR, DEVICE,
                "f".repeat(32), 0L, "${GatewayFieldSession.COOKIE_NAME}=${"a".repeat(64)}", "b".repeat(64),
                System.currentTimeMillis() + 300_000L, GATEWAY_UNLIMITED_EXPIRY_EPOCH_MS, GATEWAY_UNLIMITED_EXPIRY_EPOCH_MS)
            assertNotNull(session.versionOrNull)
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, onboarding))
            gatewayGeneration = GatewaySessionProcessCoordinator.snapshot().generation
            val device = readyDevice(requireNotNull(gatewayGeneration))
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
            val collector = WalkSessionReadinessCollector(lifecycle.snapshot().epoch, plan)
            plan.requiredRequirements.forEach { collector.record(WalkSessionReadinessObservation(
                lifecycle.snapshot().epoch, it, WalkSessionReadinessStatus.READY, "controlled renewal setup")) }
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(collector.build(SystemClock.elapsedRealtime())))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            val activeEpoch = lifecycle.snapshot().epoch
            val start = requireNotNull(oldAuthority.beginStart(activeEpoch))
            val elapsed = SystemClock.elapsedRealtime()
            val wall = System.currentTimeMillis()
            lease = GatewayWalkLease("ACQUIRED", ACTOR, DEVICE, activeEpoch.walkSessionId, "retained-native-lease", 7L,
                wall, wall + 25_000L, wall, elapsed + 20_000L)
            assertEquals(GatewayWalkAuthorityCompletion.LEASE_ACTIVE,
                oldAuthority.completeStart(start, GatewayWalkStartResult.Granted(lease)))
            lifecycle.handle(WalkSessionEvent.EnteredBackground)
            pausedEpoch = lifecycle.snapshot().epoch
            assertTrue(oldAuthority.rebindEpoch(activeEpoch, pausedEpoch))
            retainedByFixture = processWalkConfigurationChangeRetention.retain(lifecycle, oldAuthority, SystemClock.elapsedRealtime())
            assertTrue(retainedByFixture)
            val retained = requireNotNull(processWalkConfigurationChangeRetention.take(SystemClock.elapsedRealtime()))
            assertNull(processWalkConfigurationChangeRetention.take(SystemClock.elapsedRealtime()))
            assertSame(lifecycle, retained.lifecycle)
            assertTrue(authority.adoptHeldLeaseFrom(retained.authority))
            assertFalse(authority.adoptHeldLeaseFrom(retained.authority))
            assertSame(lease, authority.activeLeaseOrNull(pausedEpoch))
            lifecycle.handle(WalkSessionEvent.EnteredForeground)

            set(main, "walkSessionLifecycle", retained.lifecycle)
            set(main, "isActivityForeground", retained.lifecycle.snapshot().isForeground)
            set(main, "firstRunOnboardingSnapshot", onboarding)
            set(main, "postLoginDeviceCheckSnapshot", device)
            set(main, "reporterUserId", session.actorId)
            set(main, "priorityUserOnboardingActorId", session.actorId)
            set(main, "priorityUserOnboardingPolicy", PriorityUserOnboardingPolicy(
                PriorityUserOnboardingSnapshot(ageBand = PriorityUserAgeBand.ADULT_18_PLUS)))
            (field(main, "permissionSessionPolicy") as PermissionSessionPolicy).authenticated(session.actorId)
            set(main, "gatewayWalkRenewalHandler", Handler(Looper.getMainLooper()))
            transport.lease = lease
            set(main, "gatewayWalkSessionClient", GatewayWalkSessionClient(transport))
            assertEquals(true, invoke(main, "isGatewaySessionReadyForCurrentActor", java.lang.Boolean.TYPE,
                arrayOf(GatewayFieldSession::class.java), session))
            invoke(main, "revalidateGatewayWalkAfterForegroundReturn", Void.TYPE)
        }

        fun completed(): Boolean {
            val renewed = authority.activeLeaseOrNull(pausedEpoch) ?: return false
            if (renewed.localDeadlineElapsedMs <= lease.localDeadlineElapsedMs) return false
            assertEquals(lease.walkId, renewed.walkId)
            assertEquals(lease.leaseId, renewed.leaseId)
            assertEquals(lease.fencingToken, renewed.fencingToken)
            assertEquals(session.actorId, renewed.actorId)
            assertEquals(session.deviceId, renewed.deviceId)
            return field(main, "gatewayWalkResumeRevalidationPending") == false
        }

        fun close() {
            var first: Throwable? = null
            fun cleanup(action: () -> Unit) { try { action() } catch (t: Throwable) {
                if (first == null) first = t else first!!.addSuppressed(t)
            } }
            cleanup { (field(main, "gatewayWalkRenewalHandler") as? Handler)?.removeCallbacksAndMessages(null) }
            cleanup { lifecycle.handle(WalkSessionEvent.EndRequested) }
            cleanup { authority.reset(); oldAuthority.reset() }
            cleanup { if (retainedByFixture) processWalkConfigurationChangeRetention.clear() }
            cleanup { gatewayGeneration?.let { expected -> assertNotNull(GatewaySessionProcessCoordinator.clear(expected)) } }
            first?.let { throw it }
        }
    }

    private class Transport : GatewaySessionTransport {
        lateinit var lease: GatewayWalkLease
        val commands = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        override fun postJson(url: String, body: String): GatewayHttpResponse = error("Authenticated POST required")
        override fun postJson(url: String, headers: Map<String, String>, body: String): GatewayHttpResponse {
            assertTrue(headers["Cookie"].orEmpty().contains(GatewayFieldSession.COOKIE_NAME))
            val command = JSONObject(body)
            assertEquals("renew", command.getString("action"))
            commands.add(command)
            val wall = System.currentTimeMillis()
            return GatewayHttpResponse(200, JSONObject().put("schema_version", "walksafe.field-walk-response.v1")
                .put("result", "RENEWED").put("walk_id", lease.walkId).put("lease_id", lease.leaseId)
                .put("fencing_token", lease.fencingToken).put("acquired_at_epoch_ms", lease.acquiredAtEpochMs)
                .put("lease_expires_at_epoch_ms", wall + 60_000L).put("server_time_epoch_ms", wall).toString())
        }
        override fun get(url: String, headers: Map<String, String>): GatewayHttpResponse = error("Unexpected GET")
        override fun delete(url: String, headers: Map<String, String>): GatewayHttpResponse = error("Unexpected DELETE")
        override fun deleteJson(url: String, body: String): GatewayHttpResponse = error("Unexpected DELETE")
    }

    private companion object {
        const val ACTOR = "22222222-2222-4222-8222-222222222222"
        const val DEVICE = "retained-native-device"
        fun restoredOnboarding(): FirstRunOnboardingSnapshot {
            fun receipt(value: Int) = FirstRunReceiptHash.fromSha256Hex(value.toString(16).padStart(64, '0'))
            val evidence = listOf(FirstRunOnboardingEvidence.VerifiedLogin(FirstRunOpaqueActorBinding.fromProvider(ACTOR), receipt(1)),
                FirstRunOnboardingEvidence.PurposeAndSafety(receipt(2)), FirstRunOnboardingEvidence.Fp004Training(receipt(3)))
            val restored = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(1L, evidence,
                FirstRunOnboardingEvidenceVerifier { token, value -> value in evidence && token.stage == value.stage })
            check(restored.fullyRestored)
            val jit = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(restored.snapshot, restored.snapshot.epoch, restored.snapshot.revision)
            check(jit.accepted)
            return FirstRunOnboardingPolicy.recordEmailDeviceCheckPassed(jit.current, jit.current.epoch, jit.current.revision)
                .also { check(it.accepted && it.current.mayEnterWalk) }.current
        }
        fun readyDevice(generation: Long): PostLoginDeviceCheckSnapshot {
            val bound = PostLoginDeviceCheckPolicy.bindSession(PostLoginDeviceCheckPolicy.initial(), ACTOR, generation)
            val started = PostLoginDeviceCheckPolicy.beginFromUserAction(bound, ACTOR, generation, true)
            val binding = requireNotNull(started.bindingOrNull)
            val running = PostLoginDeviceCheckPolicy.beginRunning(started, binding, true)
            val ready = PostLoginDeviceCheckSignal.READY
            return PostLoginDeviceCheckPolicy.evaluate(running, binding, true, PostLoginDeviceCheckObservation(
                ready, ready, ready, ready, ready, ready, ready, ready, ready, ready, ready,
                metricDepth = PostLoginMetricDepthState.SUPPORTED)).also { check(it.state == PostLoginDeviceCheckState.FULL) }
        }
        fun field(target: Any, name: String): Any? = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.get(target)
        fun set(target: Any, name: String, value: Any?) = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.set(target, value)
        fun invoke(target: MainActivity, name: String, result: Class<*>, types: Array<Class<*>> = emptyArray(), vararg args: Any?): Any? {
            val method = MainActivity::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }
            assertEquals(result, method.returnType)
            return try { method.invoke(target, *args) } catch (failure: InvocationTargetException) { throw failure.targetException }
        }
        fun <T> onMain(instrumentation: Instrumentation, action: () -> T): T {
            val value = AtomicReference<T>()
            val failure = AtomicReference<Throwable>()
            instrumentation.runOnMainSync { try { value.set(action()) } catch (t: Throwable) { failure.set(t) } }
            failure.get()?.let { throw it }
            return value.get()
        }
    }
}

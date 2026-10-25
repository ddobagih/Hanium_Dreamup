package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.startup.PrivacyStartupInspectionController
import android.Manifest
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.fieldlog.PositionFieldRecorderController
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.session.runtime.GatewayWalkSessionController
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Existing typed transport -> real client/parser/controller -> actual Main worker/UI failure body.
 * Login, device/readiness and HTTP responses are controlled inputs. No onCreate, actual server,
 * permission grant, physical speech, dataset/key access or approved offline lifetime is claimed.
 * A fresh isolated instrumentation process with no other target owner is an external prerequisite.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityGatewayWalkRenewalFailureAndroidTest {
    @Test(timeout = 90_000L)
    fun actualMainRetriesTransientWithinTheOriginalLeaseAndStopsForRejectOrExpiredCallback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        assertEquals("kr.co.hanium.dreamup.walksafe.speechstorefixture", target.packageName)
        assertEquals(Process.myUid(), target.applicationInfo.uid)
        assertNotEquals("Original USER UID", 10209, Process.myUid())
        @Suppress("DEPRECATION")
        assertNull(target.packageManager.getPackageInfo(target.packageName, 0).sharedUserId)
        assertEquals("Do not grant activity recognition for this fixture", PackageManager.PERMISSION_DENIED,
            target.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION))

        withFixture(instrumentation, Mode.RETRY_THEN_REJECT) { f ->
            f.prepareCurrentLease()
            onMain(instrumentation) { f.requestActualMainRenewal() }
            assertTrue("Original Handler retry must reach the second real client request",
                f.transport.blockedRenewal.await(5L, TimeUnit.SECONDS))
            onMain(instrumentation) {
                assertEquals(2, f.transport.renewals.get())
                assertEquals(listOf(Outcome.TRANSIENT_IO), f.transport.outcomes.toList())
                f.assertCommandBindings()
                assertEquals(f.epoch.walkSessionId to 1, field(f.main, "gatewayWalkRenewalRetries"))
                assertTrue(f.navigation.text.toString().contains(
                    "walk_lease=renew_retry attempt=1 reason=walk_lease_renew_unreachable"))
                assertEquals(WalkSessionState.PAUSED, f.lifecycle.snapshot().state)
                assertSame("Failure must not replace or extend the held lease", f.lease,
                    f.authority.activeLeaseOrNull(f.epoch))
                assertEquals(f.originalDeadline, f.lease.localDeadlineElapsedMs)
                assertTrue(requireNotNull(f.authority.safeRemainingMsOrNull(f.epoch)) > 0L)
                f.assertCurrentActorAndSession()
            }
            f.transport.release.countDown() // Controlled HTTP 409, after the original transient retry.
            awaitStopped(instrumentation, f)
            onMain(instrumentation) {
                f.assertStoppedWithDetail("서버가 이 보행의 권한을 더 이상 인정하지 않습니다.")
                assertEquals(2, f.transport.renewals.get())
                assertEquals(listOf(Outcome.TRANSIENT_IO, Outcome.SERVER_REJECT), f.transport.outcomes.toList())
                assertEquals(f.originalDeadline, f.lease.localDeadlineElapsedMs)
            }
        }

        withFixture(instrumentation, Mode.EXPIRE_BLOCKED) { f ->
            f.prepareCurrentLease()
            onMain(instrumentation) { f.requestActualMainRenewal() }
            assertTrue(f.transport.blockedRenewal.await(5L, TimeUnit.SECONDS))
            onMain(instrumentation) {
                assertSame(f.lease, f.authority.activeLeaseOrNull(f.epoch))
                assertTrue(requireNotNull(f.authority.safeRemainingMsOrNull(f.epoch)) > 0L)
                assertNull(field(f.main, "gatewayWalkRenewalRetries"))
            }
            // Do not call authority getters after expiry: they could clear the pending operation
            // before Main's original completion check. Read the captured original deadline only.
            while (SystemClock.elapsedRealtime() < f.originalDeadline) Thread.sleep(20L)
            assertTrue(SystemClock.elapsedRealtime() >= f.originalDeadline)
            f.transport.release.countDown() // IOException arrives after the real local lease expiry.
            awaitStopped(instrumentation, f)
            onMain(instrumentation) {
                f.assertStoppedWithDetail("서버의 단일 활성 보행 권한을 확인하지 못했습니다.")
                assertNull("Expired completion must not enter the transient retry branch",
                    field(f.main, "gatewayWalkRenewalRetries"))
                assertEquals(1, f.transport.renewals.get())
                assertEquals(listOf(Outcome.EXPIRED_IO), f.transport.outcomes.toList())
                f.assertCommandBindings()
                assertEquals(f.originalDeadline, f.lease.localDeadlineElapsedMs)
            }
        }
    }

    private enum class Mode { RETRY_THEN_REJECT, EXPIRE_BLOCKED }
    private enum class Outcome { TRANSIENT_IO, SERVER_REJECT, EXPIRED_IO, BARRIER_TIMEOUT }

    private class Fixture(val instrumentation: Instrumentation, val context: OwnedContext, mode: Mode) {
        val main: MainActivity
        val lifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)
        val transport = Transport(mode)
        val client = GatewayWalkSessionClient(transport)
        val handler = Handler(Looper.getMainLooper())
        val status = TextView(context)
        val detail = TextView(context)
        val navigation = TextView(context)
        val authority: GatewayWalkAuthorityController
        lateinit var session: GatewayFieldSession
        lateinit var lease: GatewayWalkLease
        lateinit var epoch: WalkRuntimeEpoch
        var originalDeadline = -1L
        private var generation: Long? = null

        init {
            check(Looper.myLooper() == Looper.getMainLooper())
            val component = ComponentName(context, MainActivity::class.java)
            @Suppress("DEPRECATION")
            main = instrumentation.newActivity(MainActivity::class.java, context, Binder(),
                instrumentation.targetContext.applicationContext as Application, Intent().setComponent(component),
                context.packageManager.getActivityInfo(component, 0), "Actual renewal failure consumer", null,
                null, null) as MainActivity
            authority = field(main, "gatewayWalkAuthorityController") as GatewayWalkAuthorityController
            set(main, "gatewayWalkRenewalHandler", handler)
            set(main, "gatewayWalkSessionClient", client)
            set(main, "statusText", status)
            set(main, "detailText", detail)
            set(main, "navigationStatusText", navigation)
        }

        fun prepareCurrentLease() {
            val operation = onMain(instrumentation) {
                assertFalse(BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
                assertFalse(BuildConfig.DEVELOPMENT_QUICK_START)
                assertNull("Do not overwrite a process account", GatewaySessionProcessCoordinator.snapshot().session)
                assertFalse(GatewaySessionProcessCoordinator.snapshot().storageBlocked)
                val onboarding = restoredOnboarding()
                val publication = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
                generation = publication.generation
                session = GatewayFieldSession.longLivedSession("https://gateway.example.test", ACTOR, DEVICE,
                    "f".repeat(32), 0L, "${GatewayFieldSession.COOKIE_NAME}=${"a".repeat(64)}", "b".repeat(64),
                    System.currentTimeMillis() + 300_000L, GATEWAY_UNLIMITED_EXPIRY_EPOCH_MS,
                    GATEWAY_UNLIMITED_EXPIRY_EPOCH_MS)
                assertTrue(GatewaySessionProcessCoordinator.publishVerified(publication, session, onboarding))
                generation = GatewaySessionProcessCoordinator.snapshot().generation
                set(main, "firstRunOnboardingSnapshot", onboarding)
                set(main, "postLoginDeviceCheckSnapshot", readyDevice(requireNotNull(generation)))
                set(main, "reporterUserId", session.actorId)
                set(main, "priorityUserOnboardingActorId", session.actorId)
                set(main, "priorityUserOnboardingPolicy", PriorityUserOnboardingPolicy(
                    PriorityUserOnboardingSnapshot(ageBand = PriorityUserAgeBand.ADULT_18_PLUS)))
                (field(main, "permissionSessionPolicy") as PermissionSessionPolicy).authenticated(session.actorId)
                lifecycle.handle(WalkSessionEvent.EnteredForeground)
                set(main, "isActivityForeground", lifecycle.snapshot().isForeground)
                val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
                val collector = WalkSessionReadinessCollector(lifecycle.snapshot().epoch, plan)
                plan.requiredRequirements.forEach { collector.record(WalkSessionReadinessObservation(
                    lifecycle.snapshot().epoch, it, WalkSessionReadinessStatus.READY, "controlled renewal setup")) }
                lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(collector.build(SystemClock.elapsedRealtime())))
                lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
                set(main, "walkSessionLifecycle", lifecycle)
                assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
                assertCurrentActorAndSession()
                requireNotNull(authority.beginStart(lifecycle.snapshot().epoch))
            }
            // Existing transport/client/parser produce the lease; no constructor/held-field injection.
            val result = client.start(session, operation.epoch.walkSessionId, operation.requestId,
                SystemClock.elapsedRealtime()) as GatewayWalkStartResult.Granted
            onMain(instrumentation) {
                assertEquals(GatewayWalkAuthorityCompletion.LEASE_ACTIVE, authority.completeStart(operation, result))
                lease = result.lease
                originalDeadline = lease.localDeadlineElapsedMs
                assertSame(lease, authority.activeLeaseOrNull(operation.epoch))
                lifecycle.handle(WalkSessionEvent.EnteredBackground)
                lifecycle.handle(WalkSessionEvent.EnteredForeground)
                epoch = lifecycle.snapshot().epoch
                assertTrue(authority.rebindEpoch(operation.epoch, epoch))
                set(main, "isActivityForeground", lifecycle.snapshot().isForeground)
                assertEquals(WalkSessionState.PAUSED, lifecycle.snapshot().state)
                assertSame(lease, authority.activeLeaseOrNull(epoch))
                assertTrue(requireNotNull(authority.safeRemainingMsOrNull(epoch)) > 0L)
                assertEquals(session.actorId, lease.actorId)
                assertEquals(session.deviceId, lease.deviceId)
                assertCurrentActorAndSession()
                assertUnusedResourceOwners()
            }
        }

        fun requestActualMainRenewal() {
            assertEquals(epoch, lifecycle.snapshot().epoch)
            assertSame(lease, authority.activeLeaseOrNull(epoch))
            assertCurrentActorAndSession()
            (MainActivityControllerTestFixture.controller(main, "gatewayWalkSessionController") as GatewayWalkSessionController)
                .requestGatewayWalkRenewal()
        }

        fun assertCurrentActorAndSession() {
            assertSame(session, GatewaySessionProcessCoordinator.snapshot().session)
            assertEquals(generation, GatewaySessionProcessCoordinator.snapshot().generation)
            assertEquals(true, invoke(main, "isGatewaySessionReadyForCurrentActor", java.lang.Boolean.TYPE,
                arrayOf(GatewayFieldSession::class.java), session))
        }

        fun assertCommandBindings() {
            assertEquals(1, transport.commands.count { it.getString("action") == "start" })
            transport.commands.filter { it.getString("action") == "renew" }.forEach {
                assertEquals(lease.walkId, it.getString("walk_id"))
                assertEquals(lease.leaseId, it.getString("lease_id"))
                assertEquals(lease.fencingToken, it.getLong("fencing_token"))
            }
        }

        fun assertStoppedWithDetail(expected: String) {
            assertEquals(WalkSessionState.SAFE_STOP, lifecycle.snapshot().state)
            assertNull(lifecycle.snapshot().confirmationToken)
            assertNull(authority.activeLeaseOrNull(epoch))
            assertFalse(field(main, "gatewayWalkResumeRevalidationPending") as Boolean)
            assertEquals("보행 안전 중지", status.text.toString())
            assertTrue(detail.text.toString().contains(expected))
            assertCurrentActorAndSession() // Walk authority ends; this branch does not log the account out.
            assertUnusedResourceOwners()
        }

        private fun assertUnusedResourceOwners() {
            listOf("feedbackActuator", "rawCollectionRuntimeCoordinator", "fieldSessionLog", "routeSnapshotIoLease",
                "fusedLocationClient", "stepTracker", "session", "frameProvider",
                "speechRecognizer").forEach { assertNull("Unstarted own resource: $it", field(main, it)) }
            val position = MainActivityControllerTestFixture.controller(main, "positionFieldController") as PositionFieldRecorderController
            // The current owner exposes recording presence without acquiring or stopping a lease.
            assertFalse("Unstarted own position recording", position.explicitChestConfirmed)
            assertNull("Unstarted own position recording state", field(position, "recording"))
            assertNull("Unstarted own native position recorder", field(position, "recorder"))
        }

        fun close() {
            transport.release.countDown() // Release own worker before any FIFO wait or owner clear.
            var first: Throwable? = null
            fun cleanup(action: () -> Unit) { try { action() } catch (caught: Throwable) {
                if (first == null) first = caught else first!!.addSuppressed(caught)
            } }
            cleanup { onMain(instrumentation) {
                (MainActivityControllerTestFixture.field("privacyStartupInspectionController").apply { isAccessible = true }
                        .get(main) as PrivacyStartupInspectionController).destroy()
                set(main, "isActivityForeground", false)
                handler.removeCallbacksAndMessages(null)
                (field(main, "handsFreeVoiceRestartRunnable") as? Runnable)?.let(status::removeCallbacks)
                (field(main, "reportCleanupCallbackHandler\$delegate") as Lazy<*>).let {
                    if (it.isInitialized()) (it.value as Handler).removeCallbacksAndMessages(null)
                }
                lifecycle.handle(WalkSessionEvent.EndRequested)
                authority.reset()
            } }
            cleanup {
                val drained = CountDownLatch(1)
                GatewaySessionProcessCoordinator.executor.execute { drained.countDown() }
                assertTrue("Process worker must drain outside MainLooper", drained.await(5L, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
            }
            cleanup { onMain(instrumentation) {
                handler.removeCallbacksAndMessages(null)
                generation?.let { assertNotNull("Clear only this publication", GatewaySessionProcessCoordinator.clear(it)) }
            } }
            first?.let { throw it }
        }
    }

    private class Transport(private val mode: Mode) : GatewaySessionTransport {
        val renewals = AtomicInteger()
        val commands = CopyOnWriteArrayList<JSONObject>()
        val outcomes = CopyOnWriteArrayList<Outcome>()
        val blockedRenewal = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun postJson(url: String, body: String): GatewayHttpResponse = error("Authenticated POST required")
        override fun postJson(url: String, headers: Map<String, String>, body: String): GatewayHttpResponse {
            assertTrue(headers["Cookie"].orEmpty().contains(GatewayFieldSession.COOKIE_NAME))
            val command = JSONObject(body)
            val action = command.getString("action")
            commands.add(command)
            if (action == "start") {
                val wall = System.currentTimeMillis()
                // Reuse the accepted normal fixture's raw 60 s/25 s response samples. These are
                // controlled response data, not a new expiry policy or approved server allowance.
                val remaining = if (mode == Mode.RETRY_THEN_REJECT) 60_000L else 25_000L
                return GatewayHttpResponse(201, JSONObject().put("schema_version", "walksafe.field-walk-response.v1")
                    .put("result", "ACQUIRED").put("walk_id", command.getString("walk_id"))
                    .put("lease_id", "retained-native-lease").put("fencing_token", 7L)
                    .put("acquired_at_epoch_ms", wall).put("lease_expires_at_epoch_ms", wall + remaining)
                    .put("server_time_epoch_ms", wall).toString())
            }
            assertEquals("renew", action)
            val attempt = renewals.incrementAndGet()
            if (mode == Mode.RETRY_THEN_REJECT && attempt == 1) {
                outcomes.add(Outcome.TRANSIENT_IO)
                throw IOException("controlled transient renewal")
            }
            assertEquals(if (mode == Mode.RETRY_THEN_REJECT) 2 else 1, attempt)
            blockedRenewal.countDown()
            if (!release.await(30L, TimeUnit.SECONDS)) {
                outcomes.add(Outcome.BARRIER_TIMEOUT)
                error("Own test did not release the controlled response")
            }
            if (mode == Mode.EXPIRE_BLOCKED) {
                outcomes.add(Outcome.EXPIRED_IO)
                throw IOException("controlled response after original expiry")
            }
            outcomes.add(Outcome.SERVER_REJECT)
            return GatewayHttpResponse(409, JSONObject().put("code", "walk_lease_stale").toString())
        }
        override fun get(url: String, headers: Map<String, String>): GatewayHttpResponse = error("Unexpected GET")
        override fun delete(url: String, headers: Map<String, String>): GatewayHttpResponse = error("Unexpected DELETE")
        override fun deleteJson(url: String, body: String): GatewayHttpResponse = error("Unexpected DELETE")
    }

    private class OwnedContext(base: Context) : ContextWrapper(base) {
        private val id = UUID.randomUUID().toString()
        val root = File(base.noBackupFilesDir, "walk-renew-failure-$id").canonicalFile
        private val files = File(root, "files")
        private val noBackup = File(root, "no-backup")
        private val names = mutableMapOf<String, String>()
        fun prepare() {
            assertFalse(root.exists())
            assertTrue(files.mkdirs())
            assertTrue(noBackup.mkdir())
            assertEquals(Process.myUid(), Os.stat(root.path).st_uid)
            assertFalse(Files.isSymbolicLink(root.toPath()))
        }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = files
        override fun getNoBackupFilesDir(): File = noBackup
        @Synchronized override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val own = names.getOrPut(name) { "walk-renew-failure-$id-$name" }
            return baseContext.getSharedPreferences(own, mode)
        }
        fun close() {
            var first: Throwable? = null
            fun cleanup(action: () -> Unit) { try { action() } catch (caught: Throwable) {
                if (first == null) first = caught else first!!.addSuppressed(caught)
            } }
            names.values.forEach { name -> cleanup { assertTrue(baseContext.deleteSharedPreferences(name)) } }
            cleanup { if (root.exists()) assertTrue(root.deleteRecursively()) }
            first?.let { throw it }
        }
    }

    private companion object {
        const val ACTOR = "22222222-2222-4222-8222-222222222222"
        const val DEVICE = "retained-native-device"
        fun withFixture(instrumentation: Instrumentation, mode: Mode, action: (Fixture) -> Unit) {
            val context = OwnedContext(instrumentation.targetContext)
            var fixture: Fixture? = null
            var first: Throwable? = null
            try {
                context.prepare()
                val f = onMain(instrumentation) { Fixture(instrumentation, context, mode) }
                fixture = f
                action(f)
            } catch (caught: Throwable) { first = caught }
            finally {
                try { fixture?.close() } catch (cleanup: Throwable) {
                    if (first == null) first = cleanup else first!!.addSuppressed(cleanup)
                }
                try { context.close() } catch (cleanup: Throwable) {
                    if (first == null) first = cleanup else first!!.addSuppressed(cleanup)
                }
            }
            first?.let { throw it }
        }
        fun awaitStopped(instrumentation: Instrumentation, fixture: Fixture) {
            val deadline = SystemClock.elapsedRealtime() + 5_000L
            while (SystemClock.elapsedRealtime() < deadline &&
                !onMain(instrumentation) { fixture.lifecycle.snapshot().state == WalkSessionState.SAFE_STOP }) Thread.sleep(20L)
            onMain(instrumentation) { assertEquals("Actual Main callback must consume the failure",
                WalkSessionState.SAFE_STOP, fixture.lifecycle.snapshot().state) }
        }
        fun restoredOnboarding(): FirstRunOnboardingSnapshot {
            fun receipt(value: Int) = FirstRunReceiptHash.fromSha256Hex(value.toString(16).padStart(64, '0'))
            val evidence = listOf(FirstRunOnboardingEvidence.VerifiedLogin(FirstRunOpaqueActorBinding.fromProvider(ACTOR), receipt(1)),
                FirstRunOnboardingEvidence.PurposeAndSafety(receipt(2)), FirstRunOnboardingEvidence.Fp004Training(receipt(3)))
            val restored = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(1L, evidence,
                FirstRunOnboardingEvidenceVerifier { token, value -> value in evidence && token.stage == value.stage })
            check(restored.fullyRestored)
            val jit = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(restored.snapshot,
                restored.snapshot.epoch, restored.snapshot.revision)
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
        fun field(target: Any, name: String): Any? = if (target is MainActivity) MainActivityControllerTestFixture.field(name).get(target)
            else target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
        fun set(target: Any, name: String, value: Any?) = if (target is MainActivity) {
            when (name) {
                "feedbackActuator" -> MainActivityControllerTestFixture.replaceActuator(target, value as kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator?)
                "postLoginDeviceCheckSnapshot" -> MainActivityControllerTestFixture.publishDeviceSnapshot(target, value as kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckSnapshot)
                else -> MainActivityControllerTestFixture.field(name).set(target, value)
            }
        } else target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
        fun invoke(target: MainActivity, name: String, result: Class<*>, types: Array<Class<*>> = emptyArray(), vararg args: Any?): Any? {
            val method = MainActivity::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }
            assertEquals(result, method.returnType)
            return try { method.invoke(target, *args) } catch (failure: InvocationTargetException) { throw failure.targetException }
        }
        fun <T> onMain(instrumentation: Instrumentation, action: () -> T): T {
            val value = AtomicReference<T>()
            val failure = AtomicReference<Throwable>()
            instrumentation.runOnMainSync { try { value.set(action()) } catch (caught: Throwable) { failure.set(caught) } }
            failure.get()?.let { throw it }
            return value.get()
        }
    }
}

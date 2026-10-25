package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.startup.PrivacyStartupInspectionController
import android.Manifest
import android.app.Activity
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
import android.os.Message
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
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
import kr.co.hanium.dreamup.walksafe.report.ReportPrivacyConsentSession
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.session.runtime.GatewayWalkSessionController
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Main renew worker/client/parser/UI callback, with owned controlled HTTP inputs.
 * Local clearGatewaySession is the cleanup also called by onAccountLogoutClicked; the
 * explicit logout UI, remote logout, onCreate/startup and physical outputs are not tested.
 * Activity.mHandler access and application-generated capture shape are native prerequisites,
 * not public Android contracts. No hidden-API access relaxation or fallback is provided.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityGatewayWalkRenewalCurrentActorAndroidTest {
    @Test(timeout = 90_000L)
    fun actualMainDiscardsDelayedRenewalAfterLocalLogoutAndPreservesTheCurrentAccountWalk() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertInitialProcessAndTarget(instrumentation)

        withFixture(instrumentation) { f ->
            val old = f.prepareCurrentLease(ACTOR_A, DEVICE_A)
            val completion = onMain(instrumentation) { f.requestActualMainRenewal(old) }
            assertTrue("Actual Main worker must enter the owned transport", f.transport.firstRenewalEntered.await(5L, TimeUnit.SECONDS))
            onMain(instrumentation) {
                f.assertCurrentLease(old)
                f.clearLocalGatewaySession(old)
                f.assertLoggedOut(old)
            }
            val cleared = onMain(instrumentation) { f.captureCurrentImage() }
            f.transport.releaseFirstRenewal.countDown()
            f.awaitActualCompletion(completion, old)
            onMain(instrumentation) {
                f.assertLoggedOut(old)
                f.assertCurrentImage(cleared)
                assertEquals(1, f.transport.renewals.get())
                assertEquals(listOf(ResponseKind.RENEWED_RETURNED), f.transport.renewalOutcomes.toList())
                assertNull(field(f.main, "gatewayWalkRenewalRetries"))
                assertEquals(GatewayWalkAuthorityCompletion.STALE, completion.beforeStatus.get())
                assertEquals(GatewayWalkAuthorityCompletion.STALE, f.authority.currentOperationStatus(completion.operation()))
                f.assertCommandBinding(old, 1)
            }
        }

        withFixture(instrumentation) { f ->
            val old = f.prepareCurrentLease(ACTOR_A, DEVICE_A)
            val oldCompletion = onMain(instrumentation) { f.requestActualMainRenewal(old) }
            assertTrue(f.transport.firstRenewalEntered.await(5L, TimeUnit.SECONDS))
            onMain(instrumentation) {
                f.assertCurrentLease(old)
                f.clearLocalGatewaySession(old)
                f.assertLoggedOut(old)
                // Existing real Main transition starts a different walk, after its cleanup retired A.
                invoke(f.main, "transitionWalkSession", WalkSessionTransition::class.java,
                    arrayOf(WalkSessionEvent::class.java), WalkSessionEvent.NewWalkRequested)
                assertEquals(WalkSessionState.READY, f.lifecycle.snapshot().state)
                assertNotEquals(old.epoch.walkSessionId, f.lifecycle.snapshot().epoch.walkSessionId)
            }
            val current = f.prepareCurrentLease(ACTOR_B, DEVICE_B)
            val currentImage = onMain(instrumentation) {
                f.assertCurrentLease(current)
                assertNotEquals(old.generation, current.generation)
                assertNotSame(old.session, current.session)
                assertFalse(old.session.isUsableFor(old.session.actorId))
                assertNull(f.authority.activeLeaseOrNull(old.epoch))
                assertFalse(f.authority.tracksWalk(old.epoch.walkSessionId))
                assertTrue("Actual cleanup has produced a navigation observation", f.navigation.text.isNotEmpty())
                f.captureCurrentImage()
            }
            f.transport.releaseFirstRenewal.countDown()
            f.awaitActualCompletion(oldCompletion, old)
            onMain(instrumentation) {
                f.assertCurrentLease(current)
                f.assertCurrentImage(currentImage)
                assertNull(f.authority.activeLeaseOrNull(old.epoch))
                assertFalse(f.authority.tracksWalk(old.epoch.walkSessionId))
                assertEquals(GatewayWalkAuthorityCompletion.STALE, oldCompletion.beforeStatus.get())
                assertEquals(GatewayWalkAuthorityCompletion.STALE, f.authority.currentOperationStatus(oldCompletion.operation()))
                assertNull(field(f.main, "gatewayWalkRenewalRetries"))
                assertEquals(1, f.transport.renewals.get())
                assertEquals(listOf(ResponseKind.RENEWED_RETURNED), f.transport.renewalOutcomes.toList())
                f.assertCommandBinding(old, 1)
            }

            // Positive control: B's actual Main renew caller still accepts its own parsed response.
            val currentCompletion = onMain(instrumentation) { f.requestActualMainRenewal(current) }
            f.awaitActualCompletion(currentCompletion, current)
            onMain(instrumentation) {
                f.assertCurrentActorAndSession(current)
                assertEquals(GatewayWalkAuthorityCompletion.LEASE_ACTIVE, currentCompletion.beforeStatus.get())
                val renewed = requireNotNull(f.authority.activeLeaseOrNull(current.epoch))
                assertSame(currentCompletion.returnedLease(), renewed)
                assertNotSame(current.lease, renewed)
                assertEquals(ACTOR_B, renewed.actorId)
                assertEquals(DEVICE_B, renewed.deviceId)
                assertTrue(renewed.localDeadlineElapsedMs > current.deadline)
                assertEquals(WalkSessionState.ACTIVE, f.lifecycle.snapshot().state)
                assertEquals(current.epoch, f.lifecycle.snapshot().epoch)
                assertNull(field(f.main, "gatewayWalkRenewalRetries"))
                assertEquals(2, f.transport.renewals.get())
                assertEquals(listOf(ResponseKind.RENEWED_RETURNED, ResponseKind.RENEWED_RETURNED),
                    f.transport.renewalOutcomes.toList())
                assertEquals(currentImage.status, f.status.text.toString())
                assertEquals(currentImage.detail, f.detail.text.toString())
                assertEquals(currentImage.navigation, f.navigation.text.toString())
                assertFalse(f.authority.tracksWalk(old.epoch.walkSessionId))
                f.assertCommandBinding(current, 1)
                f.assertUnusedResourceOwners()
            }
        }
    }

    private enum class ResponseKind { RENEWED_RETURNED, BARRIER_TIMEOUT }
    private data class LeaseScope(val session: GatewayFieldSession, val generation: Long,
        val epoch: WalkRuntimeEpoch, val lease: GatewayWalkLease, val deadline: Long)
    private data class CurrentImage(val walk: WalkSessionSnapshot, val generation: Long,
        val session: GatewayFieldSession?, val lease: GatewayWalkLease?, val status: String,
        val detail: String, val navigation: String, val retries: Any?, val resumePending: Boolean)
    private data class Command(val actorId: String, val body: JSONObject)

    private class Completion(val source: LeaseScope, val owner: GatewayWalkSessionController) {
        val finished = CountDownLatch(1)
        val dispatches = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val capturedOperation = AtomicReference<GatewayWalkAuthorityOperation?>()
        val capturedLease = AtomicReference<GatewayWalkLease?>()
        val beforeStatus = AtomicReference<GatewayWalkAuthorityCompletion?>()
        val afterStatus = AtomicReference<GatewayWalkAuthorityCompletion?>()
        fun operation() = requireNotNull(capturedOperation.get())
        fun returnedLease() = requireNotNull(capturedLease.get())
    }

    /** Observes one actual application Runnable; the original Handler dispatches it exactly once. */
    private class CompletionHandler(private val original: Handler,
        private val authority: GatewayWalkAuthorityController) : Handler(original.looper) {
        private var expected: Completion? = null
        fun arm(source: LeaseScope, owner: GatewayWalkSessionController): Completion {
            check(Looper.myLooper() === looper)
            expected?.let { check(it.finished.count == 0L) }
            return Completion(source, owner).also { expected = it }
        }
        override fun dispatchMessage(message: Message) {
            val receipt = expected
            val runnable = message.callback
            var matched = false
            if (receipt != null && runnable != null &&
                (runnable.javaClass.name.startsWith(MainActivity::class.java.name) ||
                    runnable.javaClass.name.startsWith(GatewayWalkSessionController::class.java.name))) {
                try {
                    // Main's real scheduler adapts the controller Function0 to an Activity Runnable.
                    // Inspect that single capture without invoking or replacing either callback.
                    val directCaptures = capturesOf(runnable)
                    val callback = if (runnable.javaClass.name.startsWith(GatewayWalkSessionController::class.java.name)) {
                        runnable
                    } else {
                        directCaptures.filterIsInstance<Function0<*>>().singleOrNull {
                            it.javaClass.name.startsWith(GatewayWalkSessionController::class.java.name)
                        }
                    }
                    val captures = callback?.let(::capturesOf).orEmpty()
                    val operation = captures.filterIsInstance<GatewayWalkAuthorityOperation>().singleOrNull()
                    if (operation?.action == GatewayWalkAuthorityAction.RENEW &&
                        operation.sourceLease === receipt.source.lease && captures.any { it === receipt.owner }) {
                        matched = true
                        assertTrue("Completion must retain this request's exact session", captures.any { it === receipt.source.session })
                        assertFalse("Original AOSP Activity Handler posts synchronously", message.isAsynchronous)
                        assertEquals(1, receipt.dispatches.incrementAndGet())
                        receipt.capturedOperation.set(operation)
                        receipt.capturedLease.set(captures.filterIsInstance<GatewayWalkLease>().single())
                        receipt.beforeStatus.set(authority.currentOperationStatus(operation))
                    }
                } catch (caught: Throwable) { receipt.failure.compareAndSet(null, caught) }
            }
            try {
                // Retains the original Handler's callback/handleMessage behavior and original Runnable.
                // Nothing invokes the completion body directly or substitutes its result/currentness.
                original.dispatchMessage(message)
            } catch (caught: Throwable) {
                if (matched) receipt!!.failure.compareAndSet(null, caught)
                throw caught // Preserve the original dispatch exception semantics; never convert failure to success.
            } finally {
                if (matched) {
                    try { receipt!!.afterStatus.set(authority.currentOperationStatus(receipt.operation())) }
                    catch (caught: Throwable) { receipt!!.failure.compareAndSet(null, caught) }
                    receipt!!.finished.countDown()
                }
            }
        }

        private fun capturesOf(callback: Any): List<Any?> =
            callback.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
                .map { it.isAccessible = true; it.get(callback) }
    }

    private class Fixture(val instrumentation: Instrumentation, val context: OwnedContext) {
        val lifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)
        val transport = Transport()
        val client = GatewayWalkSessionClient(transport)
        val renewalHandler = Handler(Looper.getMainLooper())
        val status = TextView(context)
        val detail = TextView(context)
        val navigation = TextView(context)
        val main: MainActivity
        val authority: GatewayWalkAuthorityController
        private val activityHandlerField = Activity::class.java.getDeclaredField("mHandler").apply { isAccessible = true }
        private val originalActivityHandler: Handler
        private val completionHandler: CompletionHandler
        private var generation: Long? = null
        private var activityLease: AccountDeletionActivityLease? = null
        private val deletionCoordinator get() = AccountDeletionProcessCoordinator.shared

        init {
            check(Looper.myLooper() == Looper.getMainLooper())
            val component = ComponentName(context, MainActivity::class.java)
            @Suppress("DEPRECATION")
            main = instrumentation.newActivity(MainActivity::class.java, context, Binder(),
                instrumentation.targetContext.applicationContext as Application, Intent().setComponent(component),
                context.packageManager.getActivityInfo(component, 0), "Actual renewal current actor consumer",
                null, null, null) as MainActivity
            assertSame(context, main.applicationContext)
            assertEquals(context.filesDir.canonicalFile, main.applicationContext.filesDir.canonicalFile)
            assertEquals(context.noBackupFilesDir.canonicalFile, main.noBackupFilesDir.canonicalFile)
            authority = field(main, "gatewayWalkAuthorityController") as GatewayWalkAuthorityController
            originalActivityHandler = activityHandlerField.get(main) as Handler
            assertEquals(Handler::class.java, originalActivityHandler.javaClass)
            assertSame(Looper.getMainLooper(), originalActivityHandler.looper)
            // Public enqueue exposes the original Handler's async flag before any owner is published.
            // Cancel only this own empty probe; a non-default Handler is an unsupported fixture premise.
            val probe = Runnable {}
            val probeMessage = Message.obtain(originalActivityHandler, probe)
            try {
                assertTrue(originalActivityHandler.sendMessageAtTime(probeMessage, SystemClock.uptimeMillis()))
                assertFalse("Retain the original synchronous Activity Handler semantics", probeMessage.isAsynchronous)
            } finally { originalActivityHandler.removeCallbacks(probe) }
            completionHandler = CompletionHandler(originalActivityHandler, authority)
            activityHandlerField.set(main, completionHandler)
            assertSame(completionHandler, activityHandlerField.get(main))
            set(main, "gatewayWalkRenewalHandler", renewalHandler)
            set(main, "gatewayWalkSessionClient", client)
            set(main, "walkSessionLifecycle", lifecycle)
            set(main, "stepLengthPrefs", context.getSharedPreferences("renew-current-step", 0))
            set(main, "statusText", status)
            set(main, "detailText", detail)
            set(main, "navigationStatusText", navigation)
        }

        fun prepareCurrentLease(actorId: String, deviceId: String): LeaseScope {
            lateinit var session: GatewayFieldSession
            lateinit var operation: GatewayWalkAuthorityOperation
            val currentGeneration = onMain(instrumentation) {
                assertFalse(BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
                assertFalse(BuildConfig.DEVELOPMENT_QUICK_START)
                assertNull(GatewaySessionProcessCoordinator.snapshot().session)
                assertFalse(GatewaySessionProcessCoordinator.snapshot().storageBlocked)
                if (activityLease == null) {
                    assertFalse(deletionCoordinator.stateMachine.processingBlocked())
                    activityLease = deletionCoordinator.attach()
                    set(main, "accountDeletionActivityLease", requireNotNull(activityLease))
                    assertTrue(deletionCoordinator.stateMachine.activityLeaseIsCurrent(requireNotNull(activityLease)))
                }
                val onboarding = restoredOnboarding(actorId)
                val publication = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
                generation = publication.generation
                session = GatewayFieldSession.longLivedSession("https://gateway.example.test", actorId, deviceId,
                    "f".repeat(32), 0L, cookie(actorId), "b".repeat(64),
                    System.currentTimeMillis() + 300_000L, GATEWAY_UNLIMITED_EXPIRY_EPOCH_MS,
                    GATEWAY_UNLIMITED_EXPIRY_EPOCH_MS)
                assertTrue(GatewaySessionProcessCoordinator.publishVerified(publication, session, onboarding))
                generation = GatewaySessionProcessCoordinator.snapshot().generation
                set(main, "firstRunOnboardingSnapshot", onboarding)
                set(main, "postLoginDeviceCheckSnapshot", readyDevice(actorId, requireNotNull(generation)))
                set(main, "reporterUserId", actorId)
                set(main, "priorityUserOnboardingActorId", actorId)
                set(main, "priorityUserOnboardingPolicy", PriorityUserOnboardingPolicy(
                    PriorityUserOnboardingSnapshot(ageBand = PriorityUserAgeBand.ADULT_18_PLUS)))
                (field(main, "permissionSessionPolicy") as PermissionSessionPolicy).authenticated(actorId)
                lifecycle.handle(WalkSessionEvent.EnteredForeground)
                set(main, "isActivityForeground", lifecycle.snapshot().isForeground)
                val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
                val collector = WalkSessionReadinessCollector(lifecycle.snapshot().epoch, plan)
                plan.requiredRequirements.forEach { collector.record(WalkSessionReadinessObservation(
                    lifecycle.snapshot().epoch, it, WalkSessionReadinessStatus.READY, "controlled renewal setup")) }
                lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(collector.build(SystemClock.elapsedRealtime())))
                lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
                assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
                assertEquals(true, invoke(main, "isGatewaySessionReadyForCurrentActor", java.lang.Boolean.TYPE,
                    arrayOf(GatewayFieldSession::class.java), session))
                operation = requireNotNull(authority.beginStart(lifecycle.snapshot().epoch))
                requireNotNull(generation)
            }
            val result = client.start(session, operation.epoch.walkSessionId, operation.requestId,
                SystemClock.elapsedRealtime()) as GatewayWalkStartResult.Granted
            return onMain(instrumentation) {
                assertEquals(GatewayWalkAuthorityCompletion.LEASE_ACTIVE, authority.completeStart(operation, result))
                LeaseScope(session, currentGeneration, lifecycle.snapshot().epoch, result.lease,
                    result.lease.localDeadlineElapsedMs).also { assertCurrentLease(it); assertUnusedResourceOwners() }
            }
        }

        fun assertCurrentActorAndSession(scope: LeaseScope) {
            val process = GatewaySessionProcessCoordinator.snapshot()
            assertSame(scope.session, process.session)
            assertEquals(scope.generation, process.generation)
            assertFalse(process.storageBlocked)
            assertEquals(true, invoke(main, "isGatewaySessionReadyForCurrentActor", java.lang.Boolean.TYPE,
                arrayOf(GatewayFieldSession::class.java), scope.session))
            assertEquals(scope.session.actorId, invoke(main, "currentReporterUserId", String::class.java))
        }
        fun assertCurrentLease(scope: LeaseScope) {
            assertCurrentActorAndSession(scope)
            assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
            assertEquals(scope.epoch, lifecycle.snapshot().epoch)
            assertSame(scope.lease, authority.activeLeaseOrNull(scope.epoch))
            assertEquals(scope.deadline, scope.lease.localDeadlineElapsedMs)
            assertEquals(scope.session.actorId, scope.lease.actorId)
            assertEquals(scope.session.deviceId, scope.lease.deviceId)
            assertTrue(scope.lease.isLocallyUsable(SystemClock.elapsedRealtime()))
        }
        fun requestActualMainRenewal(scope: LeaseScope): Completion {
            assertCurrentLease(scope)
            val owner = MainActivityControllerTestFixture.controller(main, "gatewayWalkSessionController") as GatewayWalkSessionController
            val completion = completionHandler.arm(scope, owner)
            owner.requestGatewayWalkRenewal()
            return completion
        }
        fun clearLocalGatewaySession(old: LeaseScope) {
            assertCurrentLease(old)
            assertNotNull("Actual local cleanup must enter for this exact own session/generation",
                invoke(main, "clearGatewaySession", java.lang.Long::class.java,
                    arrayOf(java.lang.Boolean.TYPE, GatewayFieldSession::class.java,
                        kotlin.jvm.functions.Function1::class.java, AccountDeletionActivityLease::class.java,
                        java.lang.Long::class.java), false, old.session, null, requireNotNull(activityLease), old.generation))
            generation = GatewaySessionProcessCoordinator.snapshot().generation
        }
        fun assertLoggedOut(old: LeaseScope) {
            val process = GatewaySessionProcessCoordinator.snapshot()
            assertNull(process.session)
            assertNull(process.restoredFirstRunSnapshot)
            assertNull(process.inFlightOperationId)
            assertFalse(process.storageBlocked)
            assertFalse(old.session.isUsableFor(old.session.actorId))
            assertNull(invoke(main, "currentReporterUserId", String::class.java))
            assertEquals(false, invoke(main, "isGatewaySessionReadyForCurrentActor", java.lang.Boolean.TYPE,
                arrayOf(GatewayFieldSession::class.java), old.session))
            assertEquals(WalkSessionState.SAFE_STOP, lifecycle.snapshot().state)
            assertNull(lifecycle.snapshot().confirmationToken)
            assertNull(authority.activeLeaseOrNull(old.epoch))
            assertFalse(authority.tracksWalk(old.epoch.walkSessionId))
            assertUnusedResourceOwners()
        }
        fun captureCurrentImage(): CurrentImage {
            val process = GatewaySessionProcessCoordinator.snapshot()
            val walk = lifecycle.snapshot()
            return CurrentImage(walk, process.generation, process.session, authority.activeLeaseOrNull(walk.epoch),
                status.text.toString(), detail.text.toString(), navigation.text.toString(),
                field(main, "gatewayWalkRenewalRetries"), field(main, "gatewayWalkResumeRevalidationPending") as Boolean)
        }
        fun assertCurrentImage(image: CurrentImage) {
            assertEquals(image, captureCurrentImage())
        }
        fun awaitActualCompletion(completion: Completion, source: LeaseScope) {
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            assertTrue("No actual UI dispatch means preparation failed, never B-invariance PASS",
                completion.finished.await(5L, TimeUnit.SECONDS))
            completion.failure.get()?.let { throw it }
            drainProcessWorkerAndUi(instrumentation)
            onMain(instrumentation) {
                assertEquals(1, completion.dispatches.get())
                val operation = completion.operation()
                val returned = completion.returnedLease()
                assertEquals(GatewayWalkAuthorityAction.RENEW, operation.action)
                assertSame(source.lease, operation.sourceLease)
                assertEquals(source.epoch, operation.epoch)
                assertEquals("RENEWED", returned.result)
                assertEquals(source.session.actorId, returned.actorId)
                assertEquals(source.session.deviceId, returned.deviceId)
                assertEquals(source.epoch.walkSessionId, returned.walkId)
                assertEquals(source.lease.leaseId, returned.leaseId)
                assertEquals(source.lease.fencingToken, returned.fencingToken)
                assertTrue("Captured real client/parser result must still be a usable success", returned.isLocallyUsable(SystemClock.elapsedRealtime()))
                assertTrue(returned.localDeadlineElapsedMs > source.deadline)
                assertEquals(GatewayWalkAuthorityCompletion.STALE, completion.afterStatus.get())
                assertEquals(GatewayWalkAuthorityCompletion.STALE, authority.currentOperationStatus(operation))
            }
        }
        fun assertCommandBinding(scope: LeaseScope, expectedRenewals: Int) {
            val starts = transport.commands.filter { it.actorId == scope.session.actorId && it.body.getString("action") == "start" }
            assertEquals(1, starts.size)
            assertEquals(scope.epoch.walkSessionId, starts.single().body.getString("walk_id"))
            val renews = transport.commands.filter { it.actorId == scope.session.actorId && it.body.getString("action") == "renew" }
            assertEquals(expectedRenewals, renews.size)
            renews.forEach {
                assertEquals(scope.epoch.walkSessionId, it.body.getString("walk_id"))
                assertEquals(scope.lease.leaseId, it.body.getString("lease_id"))
                assertEquals(scope.lease.fencingToken, it.body.getLong("fencing_token"))
            }
        }
        fun assertUnusedResourceOwners() {
            listOf("feedbackActuator", "rawCollectionRuntimeCoordinator", "fieldSessionLog", "routeSnapshotIoLease",
                "fusedLocationClient", "stepTracker", "session",
                "frameProvider", "speechRecognizer").forEach { assertNull("Unstarted own resource: $it", field(main, it)) }
            val position = MainActivityControllerTestFixture.controller(main, "positionFieldController") as PositionFieldRecorderController
            // The current owner exposes recording presence without acquiring or stopping a lease.
            assertFalse("Unstarted own position recording", position.explicitChestConfirmed)
            assertNull("Unstarted own position recording state", field(position, "recording"))
            assertNull("Unstarted own native position recorder", field(position, "recorder"))
        }
        fun close() {
            transport.releaseFirstRenewal.countDown()
            var first: Throwable? = null
            fun cleanup(action: () -> Unit) { try { action() } catch (caught: Throwable) {
                if (first == null) first = caught else first!!.addSuppressed(caught)
            } }
            cleanup { onMain(instrumentation) {
                (MainActivityControllerTestFixture.field("privacyStartupInspectionController").apply { isAccessible = true }
                        .get(main) as PrivacyStartupInspectionController).destroy()
                set(main, "isActivityForeground", false)
                renewalHandler.removeCallbacksAndMessages(null)
                (field(main, "handsFreeVoiceRestartRunnable") as? Runnable)?.let(status::removeCallbacks)
                (field(main, "reportCleanupCallbackHandler\$delegate") as Lazy<*>).let {
                    if (it.isInitialized()) (it.value as Handler).removeCallbacksAndMessages(null)
                }
                lifecycle.handle(WalkSessionEvent.EndRequested)
                authority.reset()
            } }
            cleanup { drainProcessWorkerAndUi(instrumentation) }
            cleanup { onMain(instrumentation) {
                renewalHandler.removeCallbacksAndMessages(null)
                completionHandler.removeCallbacksAndMessages(null)
                activityHandlerField.set(main, originalActivityHandler)
                assertSame(originalActivityHandler, activityHandlerField.get(main))
                generation?.let { assertNotNull("Clear only this owned publication", GatewaySessionProcessCoordinator.clear(it)) }
                activityLease?.let { assertTrue(deletionCoordinator.detach(it)) }
                activityLease = null
            } }
            first?.let { throw it }
        }
    }

    private class Transport : GatewaySessionTransport {
        val commands = CopyOnWriteArrayList<Command>()
        val renewalOutcomes = CopyOnWriteArrayList<ResponseKind>()
        val renewals = AtomicInteger()
        val firstRenewalEntered = CountDownLatch(1)
        val releaseFirstRenewal = CountDownLatch(1)
        override fun postJson(url: String, body: String): GatewayHttpResponse = error("Authenticated POST required")
        override fun postJson(url: String, headers: Map<String, String>, body: String): GatewayHttpResponse {
            val actor = when (headers["Cookie"]) { cookie(ACTOR_A) -> ACTOR_A; cookie(ACTOR_B) -> ACTOR_B
                else -> error("Unexpected account credential") }
            val command = JSONObject(body)
            val action = command.getString("action")
            commands.add(Command(actor, command))
            if (action == "renew") {
                val attempt = renewals.incrementAndGet()
                if (attempt == 1) {
                    assertEquals(ACTOR_A, actor)
                    firstRenewalEntered.countDown()
                    if (!releaseFirstRenewal.await(30L, TimeUnit.SECONDS)) {
                        renewalOutcomes.add(ResponseKind.BARRIER_TIMEOUT)
                        error("Own response was not released")
                    }
                } else {
                    assertEquals(2, attempt)
                    assertEquals(ACTOR_B, actor)
                }
                val response = success(command, "RENEWED", 200)
                renewalOutcomes.add(ResponseKind.RENEWED_RETURNED)
                return response
            }
            assertEquals("start", action)
            return success(command, "ACQUIRED", 201)
        }
        private fun success(command: JSONObject, result: String, status: Int): GatewayHttpResponse {
            val wall = System.currentTimeMillis()
            // Existing accepted normal raw response sample; no new runtime/expiry policy.
            val remaining = if (command.getString("action") == "renew") 60_000L else 25_000L
            val leaseId = if (command.getString("action") == "renew") command.getString("lease_id") else "retained-native-lease"
            val fencing = if (command.getString("action") == "renew") command.getLong("fencing_token") else 7L
            return GatewayHttpResponse(status, JSONObject().put("schema_version", "walksafe.field-walk-response.v1")
                .put("result", result).put("walk_id", command.getString("walk_id")).put("lease_id", leaseId)
                .put("fencing_token", fencing).put("acquired_at_epoch_ms", wall)
                .put("lease_expires_at_epoch_ms", wall + remaining).put("server_time_epoch_ms", wall).toString())
        }
        override fun get(url: String, headers: Map<String, String>): GatewayHttpResponse = error("Unexpected GET")
        override fun delete(url: String, headers: Map<String, String>): GatewayHttpResponse = error("Unexpected DELETE")
        override fun deleteJson(url: String, body: String): GatewayHttpResponse = error("Unexpected DELETE")
    }

    private class OwnedContext(base: Context) : ContextWrapper(base) {
        private val id = UUID.randomUUID().toString()
        val root = File(base.noBackupFilesDir, "walk-renew-current-$id").canonicalFile
        private val files = File(root, "files")
        private val noBackup = File(root, "no-backup")
        private val names = mutableMapOf<String, String>()
        fun prepare() {
            assertFalse(root.exists()); assertTrue(files.mkdirs()); assertTrue(noBackup.mkdir())
            assertEquals(Process.myUid(), Os.stat(root.path).st_uid)
            assertFalse(Files.isSymbolicLink(root.toPath()))
        }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = files
        override fun getNoBackupFilesDir(): File = noBackup
        @Synchronized override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val own = names.getOrPut(name) { "walk-renew-current-$id-$name" }
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
        const val ACTOR_A = "22222222-2222-4222-8222-222222222222"
        const val ACTOR_B = "33333333-3333-4333-8333-333333333333"
        const val DEVICE_A = "retained-native-device"
        const val DEVICE_B = "current-native-device"
        fun cookie(actorId: String) = "${GatewayFieldSession.COOKIE_NAME}=${if (actorId == ACTOR_A) "a".repeat(64) else "c".repeat(64)}"
        fun assertInitialProcessAndTarget(instrumentation: Instrumentation) {
            val target = instrumentation.targetContext
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            assertEquals("kr.co.hanium.dreamup.walksafe.speechstorefixture", target.packageName)
            assertEquals(Process.myUid(), target.applicationInfo.uid)
            assertNotEquals("Original USER UID", 10209, Process.myUid())
            @Suppress("DEPRECATION")
            assertNull(target.packageManager.getPackageInfo(target.packageName, 0).sharedUserId)
            assertEquals(PackageManager.PERMISSION_DENIED, target.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION))
            assertFalse(ReportPrivacyConsentSession.isGranted())
            assertFalse(ReportPrivacyConsentSession.isAccountDeletionBlocked())
            assertFalse(AccountDeletionProcessCoordinator.shared.stateMachine.processingBlocked())
            assertEquals(AccountDeletionPhase.IDLE, AccountDeletionProcessCoordinator.shared.stateMachine.phase())
            val process = GatewaySessionProcessCoordinator.snapshot()
            assertNull(process.session); assertNull(process.restoredFirstRunSnapshot); assertNull(process.inFlightOperationId)
            assertFalse(process.storageBlocked); assertFalse(process.deletionRecoveryOnly)
        }
        fun withFixture(instrumentation: Instrumentation, action: (Fixture) -> Unit) {
            val context = OwnedContext(instrumentation.targetContext)
            var fixture: Fixture? = null
            var first: Throwable? = null
            try {
                context.prepare()
                val f = onMain(instrumentation) { Fixture(instrumentation, context) }
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
        fun drainProcessWorkerAndUi(instrumentation: Instrumentation) {
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            val drained = CountDownLatch(1)
            GatewaySessionProcessCoordinator.executor.execute { drained.countDown() }
            assertTrue("Owned request worker must finish before readback", drained.await(5L, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
        }
        fun restoredOnboarding(actorId: String): FirstRunOnboardingSnapshot {
            fun receipt(value: Int) = FirstRunReceiptHash.fromSha256Hex(value.toString(16).padStart(64, '0'))
            val evidence = listOf(FirstRunOnboardingEvidence.VerifiedLogin(FirstRunOpaqueActorBinding.fromProvider(actorId), receipt(1)),
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
        fun readyDevice(actorId: String, generation: Long): PostLoginDeviceCheckSnapshot {
            val bound = PostLoginDeviceCheckPolicy.bindSession(PostLoginDeviceCheckPolicy.initial(), actorId, generation)
            val started = PostLoginDeviceCheckPolicy.beginFromUserAction(bound, actorId, generation, true)
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

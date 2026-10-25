package kr.co.hanium.dreamup.walksafe

import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.system.Os
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.Locale
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.fieldlog.NoopFieldSessionLog
import kr.co.hanium.dreamup.walksafe.fieldlog.PersistentFieldSessionLog.SpeechStorageAccess
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.security.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Controlled platform effects feed the real Actor's pending ENGINE_ERROR path, the same private
 * unavailable branch used by Main's default factory, and the actual Main storage binding fence.
 * Native Provider/AndroidKeyStore records must exist before the worker barrier. The delayed ERROR
 * is intentionally dropped after SAFE_STOP; observer enqueue is not a disk ACK. Typed readiness
 * and server confirmations are fixture inputs. No onCreate, real TTS service error, hearing,
 * automatic recovery or whole S13-38 completion is claimed.
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySpeechOutputEngineFailureAndroidTest {
    @Test(timeout = 90_000L)
    fun actualEngineFailureRevokesMainPermitAndDropsItsLateStorageWrite() {
        val guard = isolatedTargetBeforeFixtureStorageAccess()
        val preparation = FieldSpeechOutputStorageProvider.create(guard.context.filesDir)
        var fixture: Fixture? = null
        var failure: Throwable? = null
        try {
            assertTrue("Actual test-owned field purge before verified enrollment reset", preparation.purgeAll())
            assertTrue("Test-owned verified enrollment reset", preparation.resetForNewEnrollment())
            assertTrue("Actual reset root", guard.root.listFiles()?.isEmpty() == true)
            val f = Fixture(guard)
            fixture = f
            f.prepare()
            f.initializeOnActualPrivacyWorker()
            val collection = requireNotNull(f.collection())
            val provider = f.provider(collection)
            val store = f.store(collection)
            assertTrue(provider.aead is AndroidKeyStoreAead)
            onMain(guard.instrumentation) {
                assertSame(f.actor, f.ensure())
                assertNotNull(f.actor.readySpeechEngineGenerationOrNull())
                assertTrue(f.voiceFeatureEnabled())
                assertTrue(f.navigationSpeechAllowed())
                assertEquals(WalkSessionState.ACTIVE, f.lifecycle.snapshot().state)
                assertNotNull(f.lifecycle.currentRuntimeEpochOrNull())
                assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.actor.speakNavigation("엔진 오류 저장 경계 확인"))
            }
            val id = f.runtime.spoken.single()
            onMain(guard.instrumentation) { f.runtime.start(id) }
            guard.instrumentation.waitForIdleSync()
            onMain(guard.instrumentation) { assertTrue(f.actor.isAppSpeechActive()) }
            await("Actual REQUESTED/STARTED ciphertext before ENGINE error") { recordFiles(guard.root).sumOf { it.readLines().size } == 2 }
            val original = f.readRecords(provider)
            assertEquals(listOf("REQUESTED", "STARTED"), original.map { it.payload.getString("state") })
            val listener = requireNotNull(f.actor.speechOutputListener)
            val generationBefore = field(f.main, "feedbackLifecycleGeneration") as Int
            val dropsBefore = store.droppedEventCount()
            f.blockStoreWorker(store)
            onMain(guard.instrumentation) {
                assertTrue("Actual pending utterance at ERROR delivery", f.actor.isAppSpeechActive())
                assertNotNull(f.actor.readySpeechEngineGenerationOrNull())
                f.runtime.error(id, TextToSpeech.ERROR_SERVICE)
            }
            await("Same Main unavailable branch completes without waiting for blocked disk") {
                f.callbackFailure.get()?.let { throw it }
                onMain(guard.instrumentation) {
                    f.lifecycle.snapshot().state == WalkSessionState.SAFE_STOP &&
                        field(f.main, "feedbackActuator") == null && f.actor.statusText() == "tts=closed"
                }
            }
            assertEquals("Main STOP finished while storage remains blocked", 1L, f.releaseStore.count)
            val queuedError = f.storeExecutor(store).queue.toList().mapNotNull { task ->
                if (task.javaClass.simpleName == "EventWrite") field(task, "event") as SpeechOutputEvent else null
            }.single { it.outcome == SpeechOutputOutcome.ENGINE_ERROR }
            assertEquals(SpeechOutputKind.NAVIGATION, queuedError.kind)
            assertEquals(TextToSpeech.ERROR_SERVICE, queuedError.errorCode)
            assertTrue("Original runtime clock, not completion retag", queuedError.atElapsedRealtimeMs in f.runtime.clockReads)
            onMain(guard.instrumentation) {
                assertEquals(1, f.unavailableCalls.get())
                assertSame(f.actor, f.unavailableActor.get())
                assertNull("Actor READY retired before unavailable Main consumer", f.unavailableReadyGeneration.get())
                assertEquals("tts=init_failed", f.unavailableStatus.get())
                assertFalse(f.voiceFeatureEnabled())
                assertFalse(f.navigationSpeechAllowed())
                assertFalse(f.main.authorizeNativeFeedbackSpeech(SystemClock.elapsedRealtime(), false))
                assertEquals(false, field(f.main, "offlineKoreanTextToSpeechCapabilityOverride"))
                val disabled = f.invoke("currentPostLoginDisabledFeatures", Set::class.java) as Set<*>
                assertTrue(PostLoginDeviceCheckFeature.VOICE_GUIDANCE in disabled)
                val decision = field(f.main, "startupCapabilityDecision") as WalkSafeStartupCapabilityDecision
                assertTrue(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS in decision.unavailableRequirements)
                assertTrue(f.preferences.getString("startup_capability_unavailable", "").orEmpty().split(',')
                    .contains(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS.name))
                assertNull(f.lifecycle.currentRuntimeEpochOrNull())
                assertTrue((field(f.main, "feedbackLifecycleGeneration") as Int) > generationBefore)
                assertNull(field(f.main, "confirmedStartupCapabilityDecision"))
                assertNull((field(f.main, "speechOutputStorageAuthority") as AtomicReference<*>).get())
                assertEquals("tts=closed", f.actor.statusText())
                assertNull(f.actor.readySpeechEngineGenerationOrNull())
            }
            f.releaseStore.countDown()
            f.awaitStoreWorker(store)
            f.barrierFailure.get()?.let { throw it }
            assertTrue("Real EventWrite retirement rejection", store.droppedEventCount() > dropsBefore)
            assertEquals(original.map { it.envelope }, f.readRecords(provider).map { it.envelope })
            assertEquals("No ENGINE error append ACK", listOf("REQUESTED", "STARTED"),
                f.readRecords(provider).map { it.payload.getString("state") })
            val stopped = onMain(guard.instrumentation) { f.lifecycle.snapshot() }
            val retiredGeneration = onMain(guard.instrumentation) { field(f.main, "feedbackLifecycleGeneration") }
            val dropsAfterWorker = store.droppedEventCount()
            onMain(guard.instrumentation) {
                f.runtime.done(id)
                f.runtime.stop(id)
                f.actor.onInit(TextToSpeech.SUCCESS)
            }
            guard.instrumentation.waitForIdleSync()
            onMain(guard.instrumentation) {
                assertEquals(stopped, f.lifecycle.snapshot())
                assertEquals(retiredGeneration, field(f.main, "feedbackLifecycleGeneration"))
                assertNull(field(f.main, "feedbackActuator"))
                assertNull(f.actor.readySpeechEngineGenerationOrNull())
                assertFalse(f.navigationSpeechAllowed())
            }
            assertEquals("Retired runtime callbacks emit no new store event", dropsAfterWorker, store.droppedEventCount())
            listener(SpeechOutputEvent(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.DONE, SystemClock.elapsedRealtime()))
            assertEquals("Captured old Main listener is also fenced", dropsAfterWorker + 1L, store.droppedEventCount())
            assertEquals(original.map { it.envelope }, f.readRecords(provider).map { it.envelope })
            f.callbackFailure.get()?.let { throw it }
        } catch (problem: Throwable) {
            failure = problem
            throw problem
        } finally {
            try {
                fixture?.close() ?: assertTrue("Preparation-only owned field cleanup", preparation.purgeAll())
            } catch (cleanup: Throwable) {
                if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
            }
        }
    }

    private data class Guard(val instrumentation: Instrumentation, val context: Context, val root: File)
    private data class Record(val envelope: String, val aad: ByteArray, val payload: JSONObject)

    @Suppress("DEPRECATION")
    private fun isolatedTargetBeforeFixtureStorageAccess(): Guard {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Application/provider initialization may already have run before this test entry.
        // This guard precedes this fixture's explicit owner/provider/key preparation only.
        assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
        assertEquals(FIXTURE_PACKAGE, context.packageName)
        assertEquals(context.applicationInfo.uid, Process.myUid())
        assertNull(context.packageManager.getPackageInfo(context.packageName, 0).sharedUserId)
        val descriptor = instrumentation.uiAutomation.executeShellCommand(
            "cmd package list packages -U $USER_PACKAGE")
        val originalUids = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
            reader.readLines().mapNotNull { line ->
                Regex("^package:([A-Za-z0-9_.]+)\\s+uid:([0-9]+)\\s*$").matchEntire(line.trim())
                    ?.takeIf { it.groupValues[1] == USER_PACKAGE }?.groupValues?.get(2)?.toIntOrNull()
            }
        }
        assertEquals("Installed USER metadata must identify one UID", 1, originalUids.size)
        assertNotEquals(originalUids.single(), Process.myUid())
        val data = File(context.applicationInfo.dataDir).canonicalFile
        val files = context.filesDir.canonicalFile
        assertEquals(FIXTURE_PACKAGE, data.name)
        assertEquals(data, files.parentFile)
        assertEquals(Process.myUid(), Os.stat(data.path).st_uid)
        assertEquals(Process.myUid(), Os.stat(files.path).st_uid)
        val root = File(files, "field_sessions")
        assertEquals(files, root.canonicalFile.parentFile)
        assertFalse(Files.isSymbolicLink(root.toPath()))
        // Root/key absence is deliberately not required: verified factory reset handles the
        // retained test UID state. No USER root/key/permission is accessed or mutated.
        return Guard(instrumentation, context, root)
    }

    private class Fixture(private val guard: Guard) : AutoCloseable {
        val main = onMain(guard.instrumentation) {
            val component = ComponentName(guard.context, MainActivity::class.java)
            @Suppress("DEPRECATION")
            guard.instrumentation.newActivity(MainActivity::class.java, guard.context, Binder(),
                guard.context.applicationContext as Application, Intent().setComponent(component),
                guard.context.packageManager.getActivityInfo(component, 0), "Main engine failure consumer probe",
                null, null, null) as MainActivity
        }
        val runtime = RecordingRuntime()
        lateinit var actor: AndroidFeedbackActuator
        val lifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)
        val consent = field(main, "integratedConsentSession") as IntegratedConsentSession
        val permissions = field(main, "permissionSessionPolicy") as PermissionSessionPolicy
        val callbackFailure = AtomicReference<Throwable?>()
        val unavailableCalls = AtomicInteger()
        val unavailableActor = AtomicReference<AndroidFeedbackActuator?>()
        val unavailableReadyGeneration = AtomicReference<Long?>(-1L)
        val unavailableStatus = AtomicReference<String?>()
        val barrierFailure = AtomicReference<Throwable?>()
        val releaseStore = CountDownLatch(1)
        private val coordinator = field(main, "accountDeletionProcessCoordinator") as AccountDeletionProcessCoordinator
        private var lease: AccountDeletionActivityLease? = null
        private val preferenceName = "engine-error-consumer-${UUID.randomUUID()}"
        val preferences = guard.context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        private lateinit var sensitive: AndroidSensitivePreferenceStore
        private val session = GatewayFieldSession.verified("https://gateway.example.test", ACTOR,
            "walksafe_field_session=v2.controlled.engine.error", Long.MAX_VALUE)
        private var generation = -1L
        private lateinit var authority: IntegratedConsentAuthorityGuard
        private lateinit var actorSha: String
        private var probe: AndroidStartupCapabilityProbe? = null
        private var resourceProbe: AndroidWalkSessionResourceProbe? = null
        private var renewalHandler: Handler? = null

        fun prepare() {
            assertFalse(BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
            assertFalse(BuildConfig.DEVELOPMENT_QUICK_START)
            assertNull("Do not replace another process account", GatewaySessionProcessCoordinator.snapshot().session)
            assertFalse(coordinator.stateMachine.processingBlocked())
            lease = coordinator.attach()
            setField(main, "accountDeletionActivityLease", requireNotNull(lease))
            setField(main, "stepLengthPrefs", preferences)
            setField(main, "fieldSessionLog", NoopFieldSessionLog())
            prepareActualPrivacyOwners()
            onMain(guard.instrumentation) {
                MainActivityControllerTestFixture.controller(main, "integratedConsentController")
                invoke("initializeIntegratedConsentPersistence", Void.TYPE)
            }
            val onboarding = completedOnboarding()
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            generation = operation.generation
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, onboarding))
            generation = GatewaySessionProcessCoordinator.snapshot().generation
            setField(main, "firstRunOnboardingSnapshot", onboarding)
            authority = object : IntegratedConsentAuthorityGuard {
                override fun <T : Any> withCurrent(action: () -> T): T? =
                    GatewaySessionProcessCoordinator.withCurrentGeneralSession(generation, session, action)
            }
            actorSha = invoke("integratedConsentActorSha256", String::class.java,
                arrayOf(GatewayFieldSession::class.java), session) as String
            setField(main, "reporterUserId", session.actorId)
            assertTrue(sensitive.edit().putString("reporter_user_id", session.actorId).commit())
            val persistence = field(MainActivityControllerTestFixture.controller(main, "integratedConsentController"),
                "persistence") as IntegratedConsentPersistence
            assertEquals("Actual consent persistence shares the authenticated encrypted preferences",
                session.actorId, persistence.sensitive.getString("reporter_user_id", null))
            permissions.authenticated(session.actorId)
            assertTrue(invoke("applyIntegratedConsentConfirmation", java.lang.Boolean.TYPE,
                arrayOf(IntegratedConsentConfirmation::class.java, String::class.java,
                    IntegratedConsentAuthorityGuard::class.java), initialConfirmation(), actorSha, authority) as Boolean)
            assertTrue(permissions.snapshot().rawCollectionConsentGranted)
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
            val collector = WalkSessionReadinessCollector(lifecycle.snapshot().epoch, plan)
            plan.requiredRequirements.forEach { collector.record(WalkSessionReadinessObservation(
                lifecycle.snapshot().epoch, it, WalkSessionReadinessStatus.READY)) }
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(collector.build(SystemClock.elapsedRealtime())))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
            setField(main, "walkSessionLifecycle", lifecycle)
            onMain(guard.instrumentation) {
                invoke("setActivityForeground", Void.TYPE, arrayOf(java.lang.Boolean.TYPE), lifecycle.snapshot().isForeground)
                MainActivityControllerTestFixture.publishDeviceSnapshot(main, completedDeviceCheck(generation))
                renewalHandler = Handler(Looper.getMainLooper())
                setField(main, "gatewayWalkRenewalHandler", requireNotNull(renewalHandler))
                resourceProbe = AndroidWalkSessionResourceProbe(guard.context)
                setField(main, "walkSessionResourceProbe", requireNotNull(resourceProbe))
                probe = AndroidStartupCapabilityProbe(guard.context, {}) // start/recheck 0.
                setField(main, "startupCapabilityProbe", requireNotNull(probe))
                setField(main, "priorityUserOnboardingPolicy", PriorityUserOnboardingPolicy())
                listOf("statusText", "detailText", "startupCapabilityText",
                    "postLoginDeviceCheckWakePhraseInstructionText", "postLoginDeviceCheckHapticQuestionText")
                    .forEach { setField(main, it, TextView(guard.context)) }
                listOf("startupMetricPreflightButton", "postLoginDeviceCheckSettingsButton", "voiceDataInstallButton",
                    "postLoginDeviceCheckWakePhraseStartButton", "postLoginDeviceCheckHapticConfirmButton",
                    "postLoginDeviceCheckHapticRejectButton", "startupCapabilityConfirmButton")
                    .forEach { setField(main, it, Button(guard.context)) }
                val lifecycleGeneration = field(main, "feedbackLifecycleGeneration") as Int
                var createdActor: AndroidFeedbackActuator? = null
                actor = AndroidFeedbackActuator(runtime = runtime,
                    onOfflineKoreanSpeechUnavailable = {
                        main.runOnUiThread {
                            try {
                                unavailableActor.set(createdActor)
                                unavailableReadyGeneration.set(createdActor?.readySpeechEngineGenerationOrNull())
                                unavailableStatus.set(createdActor?.statusText())
                                unavailableCalls.incrementAndGet()
                                invoke("handleOfflineKoreanSpeechUnavailable", Void.TYPE,
                                    arrayOf(java.lang.Integer.TYPE, AndroidFeedbackActuator::class.java), lifecycleGeneration, createdActor)
                            } catch (problem: Throwable) { callbackFailure.compareAndSet(null, problem) }
                        }
                    },
                    speechAllowed = { invoke("authorizeActuatorSpeechForHomeWakeInput", java.lang.Boolean.TYPE) as Boolean },
                )
                createdActor = actor
                runtime.ready()
            }
            guard.instrumentation.waitForIdleSync()
            onMain(guard.instrumentation) {
                assertEquals("tts=ready", actor.statusText())
                MainActivityControllerTestFixture.replaceActuator(main, actor)
                assertTrue("Actual OS permission snapshot and typed owners", main.speechOutputStorageCaptureAllowed(
                    lifecycle.snapshot(), GatewaySessionProcessCoordinator.snapshot(),
                    field(main, "postLoginDeviceCheckSnapshot") as PostLoginDeviceCheckSnapshot,
                    permissions.snapshot(), invoke("currentObservedPermissionSnapshot", ObservedPermissionSnapshot::class.java)
                        as ObservedPermissionSnapshot))
                assertTrue(voiceFeatureEnabled())
                assertTrue(navigationSpeechAllowed())
                assertNull(field(main, "startupCapabilityDecision"))
                assertNull(field(main, "positioningCollectionBinding"))
                assertNull(field(main, "session"))
                assertFalse(field(main, "isRouteActive") as Boolean)
                // No GatewayWalkAuthorityController grant: failure must not issue HTTP end.
            }
        }

        private fun prepareActualPrivacyOwners() {
            val fence = AccountDeletionIntentFence(
                read = { runCatching { val values = preferences.all
                    if (!values.containsKey("account_deletion_intent_fence_v1")) false
                    else (values["account_deletion_intent_fence_v1"] as? Boolean)?.takeIf { it }
                }.getOrNull() },
                store = { preferences.edit().putBoolean("account_deletion_intent_fence_v1", true).commit() },
                clear = { preferences.edit().remove("account_deletion_intent_fence_v1").commit() },
            )
            setField(main, "accountDeletionIntentFence", fence)
            val fileOwner = FileAccountDeletionIntentAuthority(File(guard.context.noBackupFilesDir,
                "account_deletion_intent_authority"))
            val dual = AccountDeletionDualAuthority(fence, fileOwner)
            setField(main, "accountDeletionPreparedConfirmationRecovery", fileOwner)
            setField(main, "accountDeletionDualAuthority", dual)
            val reset = invoke("createAccountDeletionResetCoordinator", AccountDeletionResetCoordinator::class.java,
                arrayOf(File::class.java), guard.context.noBackupFilesDir) as AccountDeletionResetCoordinator
            setField(main, "accountDeletionResetCoordinator", reset)
            val startup = dual.startupState()
            val journal = reset.journalState()
            setField(main, "accountDeletionAuthorityAtStartup", startup)
            setField(main, "accountDeletionResetJournalStateAtStartup", journal)
            assertFalse("Actual dual startup authority", startup.blocksPrivacy)
            assertTrue("Actual reset journal", journal is AccountDeletionResetJournalState.Absent)
            val legacy = invoke("legacyAccountDeletionResetIntentState", enumType("LegacyResetIntentState")) as Enum<*>
            assertEquals("ABSENT", legacy.name)
            val spec = MainActivityControllerTestFixture.field("SENSITIVE_PREF_SPEC")
                .apply { isAccessible = true }.get(null) as SensitivePreferenceSpec
            sensitive = AndroidSensitivePreferenceStore(preferences, spec,
                initiallyBlockedForExternalReset = journal !is AccountDeletionResetJournalState.Absent || legacy.name != "ABSENT")
            setField(main, "sensitivePrefs", sensitive)
            assertFalse(sensitive.isBlocked())
            invoke("readAccountDeletionFallbackMarkerAtStartup", Void.TYPE)
            assertFalse(field(main, "accountDeletionFallbackPresentAtStartup") as Boolean)
        }

        fun initializeOnActualPrivacyWorker() = worker { invoke("initializeSpeechOutputCollectionOnPrivacyWorker", Void.TYPE) }
        fun ensure() = invoke("ensureFeedbackActuator", AndroidFeedbackActuator::class.java)
        fun collection(): Any? = (field(main, "speechOutputCollection") as AtomicReference<*>).get()
        fun provider(collection: Any) = field(collection, "provider") as FieldSpeechOutputStorageProvider
        fun store(collection: Any) = field(collection, "store") as ConsentBoundSpeechOutputStore
        fun voiceFeatureEnabled() = invoke("postLoginDeviceFeatureEnabled", java.lang.Boolean.TYPE,
            arrayOf(PostLoginDeviceCheckFeature::class.java), PostLoginDeviceCheckFeature.VOICE_GUIDANCE) as Boolean
        fun navigationSpeechAllowed() = invoke("navigationSpeechCapabilityAllowed", java.lang.Boolean.TYPE) as Boolean
        fun storeExecutor(store: ConsentBoundSpeechOutputStore) = field(store, "executor") as ThreadPoolExecutor
        fun blockStoreWorker(store: ConsentBoundSpeechOutputStore) {
            val entered = CountDownLatch(1)
            storeExecutor(store).execute {
                entered.countDown()
                try {
                    if (!releaseStore.await(15L, TimeUnit.SECONDS)) barrierFailure.set(AssertionError("Owned Store barrier timed out"))
                } catch (problem: Throwable) { barrierFailure.set(problem) }
            }
            assertTrue("Existing serial Store worker entered owned barrier", entered.await(15L, TimeUnit.SECONDS))
        }
        fun awaitStoreWorker(store: ConsentBoundSpeechOutputStore) {
            val done = CountDownLatch(1)
            storeExecutor(store).execute { done.countDown() }
            assertTrue("Original Store EventWrite and marker complete", done.await(15L, TimeUnit.SECONDS))
        }
        fun readRecords(provider: FieldSpeechOutputStorageProvider): List<Record> = recordFiles(guard.root).flatMap { file ->
            val window = file.parentFile!!.name.removePrefix("window-").toLong()
            file.readLines().mapIndexed { index, envelope ->
                val aad = SpeechStorageAccess.recordAad(window, index.toLong())
                val opened = provider.aead.open(envelope, aad, provider.limits)
                assertTrue("Native record authenticated open: $opened", opened is AeadOpenResult.Opened)
                val plaintext = (opened as AeadOpenResult.Opened).plaintext
                val row = try { JSONObject(String(plaintext, Charsets.UTF_8)) } finally { plaintext.fill(0) }
                assertEquals(setOf("metric", "state", "elapsed_realtime_ms", "result"), row.keys().asSequence().toSet())
                assertEquals("NAVIGATION", row.getString("metric"))
                assertEquals("NONE", row.getString("result"))
                assertTrue(row.getLong("elapsed_realtime_ms") in runtime.clockReads)
                Record(envelope, aad, row)
            }
        }
        private fun worker(action: () -> Unit) {
            val done = CountDownLatch(1)
            val problem = AtomicReference<Throwable?>()
            coordinator.executor.execute {
                try { action() } catch (failure: Throwable) { problem.set(failure) } finally { done.countDown() }
            }
            assertTrue("Existing privacy worker completion", done.await(15L, TimeUnit.SECONDS))
            problem.get()?.let { throw it }
        }
        fun invoke(name: String, returns: Class<*>, types: Array<Class<*>> = emptyArray(), vararg values: Any?): Any? {
            val owner = when (name) {
                "integratedConsentActorSha256", "applyIntegratedConsentConfirmation" ->
                    MainActivityControllerTestFixture.controller(main, "integratedConsentController")
                else -> main
            }
            val method = owner.javaClass.getDeclaredMethod(name, *types).apply {
                isAccessible = true
                check(returnType == returns)
            }
            return try { method.invoke(owner, *values) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        }
        override fun close() {
            releaseStore.countDown() // Always unblock our own worker before drain or other cleanup.
            var first: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (problem: Throwable) {
                    if (first == null) first = problem else first!!.addSuppressed(problem)
                }
            }
            cleanup { onMain(guard.instrumentation) {
                MainActivityControllerTestFixture.replaceActuator(main, null)
            } }
            cleanup { onMain(guard.instrumentation) { if (::actor.isInitialized) actor.close() } }
            cleanup { onMain(guard.instrumentation) { runtime.close() } }
            cleanup { worker { Unit }; guard.instrumentation.waitForIdleSync() }
            val current = collection()
            cleanup { current?.let { assertTrue(store(it).closeAndDrain().get(15L, TimeUnit.SECONDS)) } }
            cleanup {
                val storage = current?.let(::provider) ?: FieldSpeechOutputStorageProvider.create(guard.context.filesDir)
                assertTrue("Owned field Provider root/key purge after drain", storage.purgeAll())
                assertTrue(recordFiles(guard.root).isEmpty())
            }
            cleanup { onMain(guard.instrumentation) { probe?.close() } }
            cleanup { onMain(guard.instrumentation) { resourceProbe?.close() } }
            cleanup { renewalHandler?.removeCallbacksAndMessages(null) }
            cleanup { onMain(guard.instrumentation) {
                lifecycle.handle(WalkSessionEvent.EnteredBackground)
                invoke("setActivityForeground", Void.TYPE, arrayOf(java.lang.Boolean.TYPE), lifecycle.snapshot().isForeground)
            } }
            cleanup { lease?.let { assertTrue(coordinator.detach(it)) } }
            cleanup { if (generation >= 0L) assertNotNull(GatewaySessionProcessCoordinator.clear(generation)) }
            cleanup { if (::sensitive.isInitialized) assertTrue(sensitive.edit().clear().commit()) }
            cleanup { guard.context.deleteSharedPreferences(preferenceName) }
            cleanup { callbackFailure.get()?.let { throw it } }
            cleanup { barrierFailure.get()?.let { throw it } }
            first?.let { throw it }
        }
    }

    /** Real clock/main scheduler; test effects never call native speech/audio-focus APIs. */
    private class RecordingRuntime : FeedbackSpeechRuntime {
        private val handler = Handler(Looper.getMainLooper())
        val spoken = Collections.synchronizedList(mutableListOf<String>())
        val clockReads = Collections.synchronizedList(mutableListOf<Long>())
        private var initialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        override val scheduler = object : FeedbackSpeechScheduler {
            override fun post(task: Runnable) = handler.post(task)
            override fun postDelayed(task: Runnable, delayMs: Long) = handler.postDelayed(task, delayMs)
            override fun removeCallbacks(task: Runnable) = handler.removeCallbacks(task)
            override fun removeCallbacksAndMessages(token: Any?) = handler.removeCallbacksAndMessages(token)
        }
        override val engineInitialized: Boolean get() = initialized != null
        override fun elapsedRealtime() = SystemClock.elapsedRealtime().also(clockReads::add)
        override fun initialize(onInitialized: (Int) -> Unit) { initialized = onInitialized }
        override fun setLanguage(language: Locale) = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice() = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int {
            this.listener = listener
            return TextToSpeech.SUCCESS
        }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            spoken += utteranceId
            return TextToSpeech.SUCCESS
        }
        override fun stop() = Unit
        override fun shutdown() = Unit
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener) = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("No tone in engine failure fixture")
        fun ready() = requireNotNull(initialized).invoke(TextToSpeech.SUCCESS)
        fun start(id: String) = requireNotNull(listener).onStart(id)
        fun done(id: String) = requireNotNull(listener).onDone(id)
        fun error(id: String, code: Int) = requireNotNull(listener).onError(id, code)
        fun stop(id: String) = requireNotNull(listener).onStop(id, true)
        fun close() { handler.removeCallbacksAndMessages(null) }
    }

    companion object {
        private const val ACTOR = "22222222-2222-4222-8222-222222222222"
        private const val FIXTURE_PACKAGE = "kr.co.hanium.dreamup.walksafe.speechstorefixture"
        private const val USER_PACKAGE = "kr.co.hanium.dreamup.walksafe"
        private fun field(owner: Any, name: String): Any? =
            MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.get(owner)
        private fun setField(main: MainActivity, name: String, value: Any?) =
            MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.set(main, value)
        private fun enumType(name: String): Class<*> = Class.forName("kr.co.hanium.dreamup.walksafe.MainActivity\$$name")
        private fun recordFiles(root: File): List<File> = File(root, "speech-output").let { directory ->
            if (!directory.exists()) emptyList() else directory.walkTopDown()
                .filter { it.isFile && it.name.matches(Regex("records-[0-9]{4}\\.jsonl")) }
                .sortedBy(File::getAbsolutePath).toList()
        }
        private fun await(label: String, predicate: () -> Boolean) {
            check(Looper.myLooper() != Looper.getMainLooper())
            val deadline = SystemClock.elapsedRealtime() + 15_000L
            while (!predicate() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(5L)
            assertTrue(label, predicate())
        }
        private fun <T> onMain(instrumentation: Instrumentation, action: () -> T): T {
            val result = AtomicReference<T>()
            val failure = AtomicReference<Throwable?>()
            instrumentation.runOnMainSync {
                try { result.set(action()) } catch (problem: Throwable) { failure.set(problem) }
            }
            failure.get()?.let { throw it } // Caller finally still runs; MainLooper stays alive.
            return result.get()
        }
        private fun initialConfirmation() = IntegratedConsentConfirmation(
            schemaVersion = INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,
            policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
            installationId = "501e3ad4-e74f-4433-820f-72ac2fdd42ad", requestId = "main_raw_native_consent_0001",
            itemVersions = IntegratedConsentItemVersions(), clientRevision = 1L, revision = 1L,
            selections = IntegratedConsentSelections(rawSourceCollection = true, automaticReporting = true),
            confirmedAt = "2026-10-02T00:47:00.000Z", gatewayAuditRecordSha256 = "9".repeat(64),
            backendConsentReceiptSha256 = "a".repeat(64), controlSecret = "d".repeat(64),
        )
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

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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.fieldlog.NoopFieldSessionLog
import kr.co.hanium.dreamup.walksafe.fieldlog.PersistentFieldSessionLog.SpeechStorageAccess
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.security.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentController
import kr.co.hanium.dreamup.walksafe.session.consent.IntegratedConsentPersistence
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One normal Main RAW withdrawal/reconsent path in the approved separate UID. Main's public
 * Instrumentation attachment is used without onCreate; real preference/file authority reads,
 * permission checks, consent evaluator, privacy worker, Handler publication, store and
 * AndroidKeyStore are the intended native path. Speech platform effects, server confirmations,
 * and typed Lifecycle/DeviceCheck readiness observations are controlled inputs, not SDK preflight.
 * No whole startup/account HTTP, physical TTS, private SDK attachment or injected gate result.
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySpeechOutputStorageRawRecoveryAndroidTest {
    @Test(timeout = 90_000L)
    fun actualMainRawWithdrawalReconsentPublishesFreshNativeStorageAndRejectsOldOwners() {
        val guard = isolatedTargetBeforeFixtureStorageAccess()
        // Prior factory trial deliberately retained its account block/verified marker and key
        // generation metadata. Only the existing verified enrollment reset may consume them.
        val preparation = FieldSpeechOutputStorageProvider.create(guard.context.filesDir)
        var fixture: Fixture? = null
        var failure: Throwable? = null
        try {
            assertTrue("Actual test-owned field purge before verified enrollment reset", preparation.purgeAll())
            assertTrue("Actual test-owned verified enrollment reset", preparation.resetForNewEnrollment())
            assertTrue("Root after actual reset", guard.root.listFiles()?.isEmpty() == true)
            val f = Fixture(guard)
            fixture = f
            f.prepare()
            f.initializeOnActualPrivacyWorker()
            val old = requireNotNull(f.collection())
            val oldProvider = f.provider(old)
            assertTrue(oldProvider.aead is AndroidKeyStoreAead)
            onMain(guard.instrumentation) { assertSame(f.actor, f.ensure()) }
            val oldListener = requireNotNull(f.actor.speechOutputListener)
            val oldWindow = field(old, "window")
            val originalRows = f.speakAndReadRows("첫 저장 확인", oldProvider)
            val oldRecord = originalRows.first()
            assertEquals(3, originalRows.size)

            f.confirmRaw(false, "main_raw_withdrawal_0002")
            assertFalse(f.permissions.snapshot().rawCollectionConsentGranted)
            assertNull(f.consent.currentConfirmationOrNull(setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION)))
            val purge = f.future(old, "purgeFuture")
            assertTrue("Actual Store serial RAW purge Bool", purge.get(15L, TimeUnit.SECONDS))
            assertTrue(recordFiles(guard.root).isEmpty())
            assertFalse("Retired provider cannot reopen", oldProvider.appendEncrypted(oldRecord.envelope))
            val oldStore = f.store(old)
            val drops = oldStore.droppedEventCount()
            oldListener(SpeechOutputEvent(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.DONE,
                SystemClock.elapsedRealtime()))
            assertEquals(drops + 1L, oldStore.droppedEventCount())

            val receipt = f.confirmRaw(true, "main_raw_reconsent_0003")
            assertTrue(f.permissions.snapshot().rawCollectionConsentGranted)
            assertSame(receipt, f.consent.currentConfirmationOrNull(setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION)))
            onMain(guard.instrumentation) { f.ensure() }
            // Worker completion/post=true is insufficient: wait for the actual MainLooper CAS,
            // binding/listener publication and then actual fresh ciphertext below.
            await("Actual Handler fresh collection publication") {
                val current = f.collection()
                current != null && current !== old &&
                    (field(f.main, "speechOutputStorageAuthority") as AtomicReference<*>).get() != null &&
                    f.actor.speechOutputListener !== oldListener
            }
            assertTrue("Purge completion and quiescence are separate ACKs", purge.get(15L, TimeUnit.SECONDS))
            assertTrue("Actual retired Store drain Bool", f.future(old, "drainFuture").get(15L, TimeUnit.SECONDS))
            val fresh = requireNotNull(f.collection())
            val freshProvider = f.provider(fresh)
            assertNotSame(oldProvider, freshProvider)
            assertNotSame(oldWindow, field(fresh, "window"))
            assertTrue(freshProvider.aead is AndroidKeyStoreAead)
            assertFalse(oldProvider.appendEncrypted(oldRecord.envelope))
            assertEquals(AeadOpenResult.Blocked(AeadBlockReason.AUTHENTICATION_FAILED),
                freshProvider.aead.open(oldRecord.envelope, oldRecord.aad, freshProvider.limits))
            val freshRows = f.speakAndReadRows("새 저장 확인", freshProvider)
            assertEquals(3, freshRows.size)
            assertNotEquals(oldRecord.envelope, freshRows.first().envelope)

            val publishedListener = requireNotNull(f.actor.speechOutputListener)
            val publishedWindow = field(fresh, "window")
            assertTrue("Actual duplicate confirmation caller", f.applyConfirmation(receipt))
            onMain(guard.instrumentation) { f.ensure() }
            f.awaitPrivacyWorkerBarrier()
            guard.instrumentation.waitForIdleSync()
            assertSame("Same receipt must retain collection", fresh, f.collection())
            assertSame(publishedWindow, field(fresh, "window"))
            assertSame(publishedListener, f.actor.speechOutputListener)
            assertEquals(3, recordFiles(guard.root).sumOf { it.readLines().size })
        } catch (problem: Throwable) {
            failure = problem
            throw problem
        } finally {
            try {
                fixture?.close() ?: assertTrue("Test-owned preparation cleanup", preparation.purgeAll())
            } catch (cleanupFailure: Throwable) {
                if (failure == null) throw cleanupFailure else failure.addSuppressed(cleanupFailure)
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
                guard.context.packageManager.getActivityInfo(component, 0), "Main RAW recovery probe",
                null, null, null) as MainActivity
        }
        val runtime = RecordingRuntime()
        val actor = onMain(guard.instrumentation) { AndroidFeedbackActuator(runtime = runtime) }
        val consent = field(main, "integratedConsentSession") as IntegratedConsentSession
        val permissions = field(main, "permissionSessionPolicy") as PermissionSessionPolicy
        private val coordinator = field(main, "accountDeletionProcessCoordinator") as AccountDeletionProcessCoordinator
        private var lease: AccountDeletionActivityLease? = null
        private val preferences = guard.context.getSharedPreferences("walksafe", Context.MODE_PRIVATE)
        private lateinit var sensitive: AndroidSensitivePreferenceStore
        private val session = GatewayFieldSession.verified("https://gateway.example.test", "main-raw-native-actor",
            "walksafe_field_session=v2.test.session.cookie", Long.MAX_VALUE)
        private var generation: Long = -1L
        private lateinit var authority: IntegratedConsentAuthorityGuard
        private lateinit var actorSha: String
        private val consentController: IntegratedConsentController
            get() = MainActivityControllerTestFixture.controller(main, "integratedConsentController")
                as IntegratedConsentController
        private val consentPersistence: IntegratedConsentPersistence
            get() = field(consentController, "persistence") as IntegratedConsentPersistence
        fun prepare() {
            assertFalse(coordinator.stateMachine.processingBlocked())
            lease = coordinator.attach()
            setField(main, "accountDeletionActivityLease", requireNotNull(lease))
            setField(main, "stepLengthPrefs", preferences)
            setField(main, "fieldSessionLog", NoopFieldSessionLog())
            prepareActualPrivacyOwners()
            onMain(guard.instrumentation) {
                // Use Main's actual adapters to the same authenticated preferences and controller.
                consentController
                invoke("initializeIntegratedConsentPersistence", Void.TYPE)
            }
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, FirstRunOnboardingPolicy.initial()))
            generation = GatewaySessionProcessCoordinator.snapshot().generation
            authority = object : IntegratedConsentAuthorityGuard {
                override fun <T : Any> withCurrent(action: () -> T): T? =
                    GatewaySessionProcessCoordinator.withCurrentGeneralSession(generation, session, action)
            }
            actorSha = invokeConsent("integratedConsentActorSha256", String::class.java,
                arrayOf(GatewayFieldSession::class.java), session) as String
            setField(main, "reporterUserId", session.actorId)
            assertTrue(sensitive.edit().putString("reporter_user_id", session.actorId).commit())
            assertEquals("Controller persistence reads the same authenticated encrypted preferences",
                session.actorId, consentPersistence.sensitive.getString("reporter_user_id", null))
            permissions.authenticated(session.actorId)
            assertTrue(applyConfirmation(initialConfirmation()))
            assertTrue(permissions.snapshot().rawCollectionConsentGranted)
            val lifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            val collector = WalkSessionReadinessCollector(lifecycle.snapshot().epoch,
                WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL))
            collector.plan.requiredRequirements.forEach {
                collector.record(WalkSessionReadinessObservation(lifecycle.snapshot().epoch, it, WalkSessionReadinessStatus.READY))
            }
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(collector.build(SystemClock.elapsedRealtime())))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
            setField(main, "walkSessionLifecycle", lifecycle)
            onMain(guard.instrumentation) {
                invoke("setActivityForeground", Void.TYPE, arrayOf(java.lang.Boolean.TYPE), lifecycle.snapshot().isForeground)
                var device = PostLoginDeviceCheckPolicy.bindSession(PostLoginDeviceCheckPolicy.initial(), session.actorId, generation)
                device = PostLoginDeviceCheckPolicy.beginFromUserAction(device, session.actorId, generation, true)
                val binding = requireNotNull(device.bindingOrNull)
                device = PostLoginDeviceCheckPolicy.beginRunning(device, binding, true)
                val ready = PostLoginDeviceCheckSignal.READY
                device = PostLoginDeviceCheckPolicy.evaluate(device, binding, true,
                    PostLoginDeviceCheckObservation(ready, ready, ready, ready, ready, ready, ready, ready,
                        ready, ready, ready, ready, PostLoginMetricDepthState.AVAILABLE))
                assertEquals(PostLoginDeviceCheckState.FULL, device.state)
                MainActivityControllerTestFixture.publishDeviceSnapshot(main, device)
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

        fun initializeOnActualPrivacyWorker() = worker {
            invoke("initializeSpeechOutputCollectionOnPrivacyWorker", Void.TYPE)
        }
        fun ensure() = invoke("ensureFeedbackActuator", AndroidFeedbackActuator::class.java)
        fun collection(): Any? = (field(main, "speechOutputCollection") as AtomicReference<*>).get()
        fun provider(collection: Any) = field(collection, "provider") as FieldSpeechOutputStorageProvider
        fun store(collection: Any) = field(collection, "store") as ConsentBoundSpeechOutputStore
        @Suppress("UNCHECKED_CAST")
        fun future(collection: Any, name: String): CompletableFuture<Boolean> {
            await("Actual $name submission") { (field(collection, name) as AtomicReference<*>).get() != null }
            return (field(collection, name) as AtomicReference<*>).get() as CompletableFuture<Boolean>
        }
        fun applyConfirmation(value: IntegratedConsentConfirmation) = invokeConsent("applyIntegratedConsentConfirmation",
            java.lang.Boolean.TYPE, arrayOf(IntegratedConsentConfirmation::class.java, String::class.java,
                IntegratedConsentAuthorityGuard::class.java), value, actorSha, authority) as Boolean

        fun confirmRaw(granted: Boolean, requestId: String): IntegratedConsentConfirmation {
            val previous = requireNotNull(consent.cachedConfirmationOrNull())
            val mutation = PendingIntegratedConsentMutation(previous.installationId, actorSha, requestId,
                previous.policyVersion, previous.clientRevision + 1L, previous.revision,
                previous.backendConsentReceiptSha256, previous.selections.copy(rawSourceCollection = granted),
                if (granted) emptySet() else setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION), System.currentTimeMillis())
            val encoded = consentPersistence.pendingIntegratedConsentMutationJson(mutation)
            assertTrue(sensitive.edit().putString("pending_integrated_consent_mutation_v1", encoded).commit())
            assertEquals("Controller persistence reads the exact durable pending bytes", encoded,
                consentPersistence.sensitive.getString("pending_integrated_consent_mutation_v1", null))
            assertTrue(consent.restorePendingMutation(mutation))
            setField(main, "pendingIntegratedConsentMutation", mutation)
            val exact = previous.copy(requestId = requestId, clientRevision = mutation.clientRevision,
                revision = previous.revision + 1L, selections = mutation.desiredSelections,
                backendConsentReceiptSha256 = (if (granted) "c" else "b").repeat(64))
            assertTrue(mutation.isExactNewerConfirmation(exact))
            assertTrue("Actual evaluate/persist/apply/reapply caller", applyConfirmation(exact))
            assertNull(field(main, "pendingIntegratedConsentMutation"))
            return exact
        }

        fun speakAndReadRows(message: String, provider: FieldSpeechOutputStorageProvider): List<Record> {
            onMain(guard.instrumentation) {
                assertEquals(NavigationSpeechDispatchResult.ACCEPTED, actor.speakNavigation(message))
            }
            val id = runtime.spoken.last()
            onMain(guard.instrumentation) { runtime.start(id) }
            guard.instrumentation.waitForIdleSync()
            onMain(guard.instrumentation) { assertTrue(actor.isAppSpeechActive()); runtime.done(id) }
            guard.instrumentation.waitForIdleSync()
            onMain(guard.instrumentation) { assertFalse(actor.isAppSpeechActive()) }
            await("Three actual encrypted Actor events") { recordFiles(guard.root).sumOf { it.readLines().size } == 3 }
            val rows = recordFiles(guard.root).flatMap { file ->
                val window = file.parentFile!!.name.removePrefix("window-").toLong()
                file.readLines().mapIndexed { index, envelope ->
                    val aad = SpeechStorageAccess.recordAad(window, index.toLong())
                    val opened = provider.aead.open(envelope, aad, provider.limits)
                    assertTrue("Native AndroidKeyStore record decrypt: $opened", opened is AeadOpenResult.Opened)
                    val plaintext = (opened as AeadOpenResult.Opened).plaintext
                    val row = try { JSONObject(String(plaintext, Charsets.UTF_8)) } finally { plaintext.fill(0) }
                    assertEquals(setOf("metric", "state", "elapsed_realtime_ms", "result"), row.keys().asSequence().toSet())
                    assertEquals("NAVIGATION", row.getString("metric"))
                    assertEquals("NONE", row.getString("result"))
                    assertTrue("Original runtime SystemClock read", runtime.clockReads.contains(row.getLong("elapsed_realtime_ms")))
                    assertFalse(row.toString().contains(message))
                    Record(envelope, aad, row)
                }
            }
            assertEquals(listOf("REQUESTED", "STARTED", "DONE"), rows.map { it.payload.getString("state") })
            return rows
        }
        fun awaitPrivacyWorkerBarrier() = worker { Unit }
        private fun worker(action: () -> Unit) {
            val done = CountDownLatch(1)
            val problem = AtomicReference<Throwable?>()
            coordinator.executor.execute { try { action() } catch (failure: Throwable) { problem.set(failure) }
                finally { done.countDown() } }
            assertTrue("Real privacy worker completion", done.await(15L, TimeUnit.SECONDS))
            problem.get()?.let { throw it }
        }
        private fun invokeConsent(name: String, returns: Class<*>, parameters: Array<Class<*>> = emptyArray(),
            vararg args: Any?): Any? = IntegratedConsentController::class.java.getDeclaredMethod(name, *parameters)
            .apply { isAccessible = true; check(returnType == returns) }.invoke(consentController, *args)
        private fun invoke(name: String, returns: Class<*>, parameters: Array<Class<*>> = emptyArray(),
            vararg args: Any?): Any? = MainActivity::class.java.getDeclaredMethod(name, *parameters)
            .apply { isAccessible = true; check(returnType == returns) }.invoke(main, *args)
        override fun close() {
            var failure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (problem: Throwable) {
                    if (failure == null) failure = problem else failure!!.addSuppressed(problem)
                }
            }
            cleanup { onMain(guard.instrumentation) {
                MainActivityControllerTestFixture.replaceActuator(main, null)
            } }
            cleanup { onMain(guard.instrumentation) { actor.close() } }
            cleanup { onMain(guard.instrumentation) { runtime.close() } }
            cleanup { awaitPrivacyWorkerBarrier(); guard.instrumentation.waitForIdleSync() }
            val current = collection()
            cleanup { current?.let { assertTrue(store(it).closeAndDrain().get(15L, TimeUnit.SECONDS)) } }
            cleanup {
                val storage = current?.let(::provider) ?: FieldSpeechOutputStorageProvider.create(guard.context.filesDir)
                assertTrue("Actual test-owned cleanup key/root purge", storage.purgeAll())
                assertTrue(recordFiles(guard.root).isEmpty())
            }
            cleanup { lease?.let { assertTrue(coordinator.detach(it)) } }
            cleanup { if (generation >= 0L) assertNotNull(GatewaySessionProcessCoordinator.clear(generation)) }
            failure?.let { throw it }
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
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("No tone in RAW recovery fixture")
        fun ready() = requireNotNull(initialized).invoke(TextToSpeech.SUCCESS)
        fun start(id: String) = requireNotNull(listener).onStart(id)
        fun done(id: String) = requireNotNull(listener).onDone(id)
        fun close() { handler.removeCallbacksAndMessages(null) }
    }

    companion object {
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
    }
}

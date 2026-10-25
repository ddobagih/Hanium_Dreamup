package kr.co.hanium.dreamup.walksafe

import android.Manifest
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.system.Os
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.tasks.TaskCompletionSource
import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Proxy
import java.security.KeyStore
import java.security.MessageDigest
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.fieldlog.*
import kr.co.hanium.dreamup.walksafe.report.*
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import kr.co.hanium.dreamup.walksafe.navigation.StepEvent
import kr.co.hanium.dreamup.walksafe.navigation.StepEventConfidence
import kr.co.hanium.dreamup.walksafe.navigation.StepEventSource
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.security.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exact one local account preparation/fence caller in a newly isolated instrumentation process.
 * External runner must first prove USER cached state empty and no other target PID. UUID Context,
 * real native AEAD aliases and actual typed storage owners isolate all explicit fixture I/O.
 * No onCreate, server deletion call/ACK, account restore/reset, physical TTS/step or sensor grant.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityAccountDeletionStorageFenceAndroidTest {
    @Test(timeout = 90_000L)
    fun localAccountFenceRejectsCapturedInputsAndANewOwnedScopeStillStores() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertInitialProcessAndTarget(instrumentation)
        var old: Fixture? = null
        var fresh: Fixture? = null
        var first: Throwable? = null
        try {
            val f = onMain(instrumentation) { Fixture(instrumentation) }
            old = f
            onMain(instrumentation) { f.prepare() }
            f.observeReportStorageReadinessOnTestWorker()
            val captured = f.recordOwnedStepAndLateSnapshot()
            val expectedProfile = onMain(instrumentation) { f.restoreAndSubmitProfile() }
            f.awaitOwnedProfileCommit()
            assertEquals(expectedProfile, onMain(instrumentation) { f.profileStore.load(f.actorId) })
            val capturedListener = f.seedSpeechOutput()
            assertTrue(f.speechRecordFiles().isNotEmpty())
            val oldEnvelope = f.speechRecordFiles().first().readLines().first()
            val positionAlias = f.positionKeyAlias
            assertTrue(f.aliasExists(positionAlias))
            f.prepareAndFenceAccountOnPrivacyWorker()
            // The Main Boolean proves only the actual Position participant's purge/rotation.
            assertTrue(f.positionPurgeResult.get(15L, TimeUnit.SECONDS))
            assertTrue("Actual report backing storage purge Boolean", f.reportPurgeResult.get(15L, TimeUnit.SECONDS))
            assertTrue("Actual Store serial account purge Boolean", f.speechPurgeFuture().get(15L, TimeUnit.SECONDS))
            assertTrue("Quiescence is a separately checked ACK", f.speechStore.closeAndDrain().get(15L, TimeUnit.SECONDS))
            f.assertOwnedPurgeReadback()
            assertTrue("Position recorder purge did not delete its AEAD key", f.aliasExists(positionAlias))
            assertFalse("Retired provider cannot append after real account purge", f.speechProvider.appendEncrypted(oldEnvelope))
            val afterFence = onMain(instrumentation) { f.checkpoint() }
            val drops = f.speechStore.droppedEventCount()
            capturedListener(requireNotNull(f.lastObservedSpeechEvent.get()))
            assertEquals(drops + 1L, f.speechStore.droppedEventCount())
            onMain(instrumentation) {
                f.deliverStep(captured.event, captured.binding, captured.motionOwner, captured.receivedAtMs)
                f.deliverStep(captured.lateEvent, captured.binding, captured.motionOwner, captured.lateReceivedAtMs)
                assertEquals("Captured motion inputs must not recreate state/files/profile", afterFence, f.checkpoint())
            }
            f.assertOwnedPurgeReadback()
            f.releaseOwnGateway()
            f.releaseOwnDeletionLease()
            // New UUID/Main/typed owners are a normal control; the deleted account is never reset.
            val n = onMain(instrumentation) { Fixture(instrumentation, NEW_ACTOR) }
            fresh = n
            onMain(instrumentation) { n.prepare() }
            n.observeReportStorageReadinessOnTestWorker()
            val accepted = n.recordOwnedStepAndLateSnapshot()
            val newProfile = onMain(instrumentation) { n.restoreAndSubmitProfile() }
            n.awaitOwnedProfileCommit()
            n.seedSpeechOutput()
            assertNotEquals(f.root.canonicalPath, n.root.canonicalPath)
            assertNotEquals(f.fieldKeyAlias, n.fieldKeyAlias)
            onMain(instrumentation) {
                assertSame(accepted.event, field(n.main, "latestWalkingCalibrationStep"))
                assertTrue(n.bindingIsCurrent(accepted.binding))
                assertEquals(newProfile, n.profileStore.load(NEW_ACTOR))
                assertEquals(true, field(f.main, "speechOutputAccountDeletionIntent").let {
                    (it as java.util.concurrent.atomic.AtomicBoolean).get()
                })
            }
            assertEquals(3, n.speechRecordFiles().sumOf { it.readLines().size })
            f.assertOwnedPurgeReadback()
            assertEquals(PackageManager.PERMISSION_DENIED,
                instrumentation.targetContext.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION))
        } catch (failure: Throwable) {
            first = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            // The process singleton's account block is intentionally retained until this exact1
            // instrumentation process exits; no global reset manufactures an enrollment success.
            listOfNotNull(fresh, old).forEach { fixture ->
                try { fixture.drainOwnWritesForCleanup() }
                catch (problem: Throwable) { if (cleanupFailure == null) cleanupFailure = problem else cleanupFailure!!.addSuppressed(problem) }
                try { onMain(instrumentation) { fixture.close() }; instrumentation.waitForIdleSync() }
                catch (problem: Throwable) { if (cleanupFailure == null) cleanupFailure = problem else cleanupFailure!!.addSuppressed(problem) }
            }
            cleanupFailure?.let { if (first == null) throw it else first!!.addSuppressed(it) }
        }
    }

    private data class CapturedInput(val event: StepEvent, val binding: Any,
        val motionOwner: UserMotionStateOwner, val receivedAtMs: Long, val snapshot: PositioningSnapshot,
        val lateEvent: StepEvent, val lateReceivedAtMs: Long)
    private data class Checkpoint(val binding: Any?, val callback: Any?, val locationOwner: Any?,
        val locationGeneration: Int, val motionOwner: UserMotionStateOwner?, val motionStepAt: Long?, val step: Any?,
        val stationary: Any?, val stationarySince: Any?, val trusted: Any?, val raw: Any?,
        val gnssHealth: Any?, val stepTracker: Any?, val motionTracker: Any?, val healthStatus: String,
        val profile: PedestrianMotionProfileSnapshot, val traceCounts: List<Pair<String, Long>>,
        val lateRecords: List<String>, val ownedFiles: List<Pair<String, String>>, val profileEnvelope: String?,
        val positionRecording: Any?, val positionScopeId: String?)

    /** Existing profile port delegates every read/commit to the actual encrypted preference store. */
    private class NativeProfileStorage(val native: AndroidSensitivePreferenceStore) : PedestrianProfileStorage {
        val successfulCommits = AtomicInteger()
        override fun isBlocked() = native.isBlocked()
        override fun contains(key: String) = native.contains(key)
        override fun getString(key: String) = native.getString(key, null)
        override fun putString(key: String, value: String): Boolean =
            native.edit().putString(key, value).commit().also { if (it) successfulCommits.incrementAndGet() }
        override fun remove(key: String) = native.edit().remove(key).commit()
        override fun failClosed() = native.failClosed()
        override fun resetAll(): Boolean = throw AssertionError("This fixture must not call account/profile reset")
    }

    /** Main creates the real request/callback/binding; a pending registration is not a location receipt. */
    private class FusedTransport {
        val callbacks = mutableListOf<LocationCallback>()
        val client = Proxy.newProxyInstance(FusedLocationProviderClient::class.java.classLoader,
            arrayOf(FusedLocationProviderClient::class.java)) { proxy, method, arguments ->
            check(Looper.myLooper() == Looper.getMainLooper())
            when (method.name) {
                "requestLocationUpdates" -> {
                    check(arguments != null && arguments.size == 3 && arguments[0] is LocationRequest &&
                        arguments[1] is LocationCallback && arguments[2] === Looper.getMainLooper())
                    callbacks += arguments[1] as LocationCallback
                    TaskCompletionSource<Void>().task
                }
                "removeLocationUpdates" -> TaskCompletionSource<Void>().also { it.setResult(null) }.task
                "toString" -> "ControlledUserMotionFusedTransport"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.singleOrNull()
                else -> throw AssertionError("Unexpected Fused call: ${method.name}")
            }
        } as FusedLocationProviderClient
    }

    private class OwnedContext(base: Context) : ContextWrapper(base) {
        val id = UUID.randomUUID().toString()
        val rawLexicalRoot = File(base.noBackupFilesDir, "account-fence-fixture-$id")
        val root = rawLexicalRoot.canonicalFile
        private val files = File(root, "files")
        private val noBackup = File(root, "no-backup")
        private val preferenceNames = mutableMapOf<String, String>()
        init {
            assertFalse(root.exists())
            assertTrue(files.mkdirs())
            assertTrue(noBackup.mkdir())
            assertEquals(Process.myUid(), Os.stat(root.canonicalPath).st_uid)
            assertFalse(Files.isSymbolicLink(root.toPath()))
        }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = files
        override fun getNoBackupFilesDir(): File = noBackup
        @Synchronized override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val ownedName = preferenceNames.getOrPut(name) { "account-fence-$id-${sha256(name.toByteArray()).take(16)}" }
            return baseContext.getSharedPreferences(ownedName, mode)
        }
        fun deleteOwnPreferences() {
            preferenceNames.values.forEach { assertTrue(baseContext.deleteSharedPreferences(it)) }
        }
    }

    private class Fixture(val instrumentation: Instrumentation, initialActor: String = ACTOR) {
        val context = OwnedContext(instrumentation.targetContext)
        val id = context.id
        val root = context.root
        val profilePrefsName = "user-motion-profile-fixture-$id"
        val stepPrefsName = "user-motion-step-fixture-$id"
        val main: MainActivity
        val transport = FusedTransport()
        val lifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)
        val renewalHandler = Handler(Looper.getMainLooper())
        private var gatewayGeneration: Long? = null
        private var authorityEpoch: WalkRuntimeEpoch? = null
        var actorId = initialActor
            private set
        lateinit var recorder: PositionFieldSessionRecorder
        lateinit var recorderBinding: RecorderBinding
        private var recorderBindings = mutableListOf<RecorderBinding>()
        lateinit var fieldLog: PersistentFieldSessionLog
        private var fieldSessionId: String? = null
        lateinit var profileSpec: SensitivePreferenceSpec
        lateinit var profileStorage: NativeProfileStorage
        lateinit var profileStore: AndroidPedestrianProfileStore
        private val ownedAead = mutableListOf<LocalAead>()
        val fieldKeyAlias = keyPolicy("field").alias(1)
        val positionKeyAlias = keyPolicy("position").alias(1)
        private val reportKeyAlias = keyPolicy("report").alias(1)
        val positionPurgeResult = CompletableFuture<Boolean>()
        val reportPurgeResult = CompletableFuture<Boolean>()
        private lateinit var realReportStorage: FileReportQueueStorage
        private val reportKeyLifecycleContext = AtomicReference<ReportQueuePurgeContext?>()
        private val reportKeyLifecycleResult = AtomicReference<Boolean?>()
        private val reportKeyLifecycleExceptionClass = AtomicReference<String?>()
        private val reportPurgeExceptionClass = AtomicReference<String?>()
        val lastObservedSpeechEvent = AtomicReference<SpeechOutputEvent?>()
        private lateinit var sensitive: AndroidSensitivePreferenceStore
        lateinit var speechProvider: FieldSpeechOutputStorageProvider
        lateinit var speechStore: ConsentBoundSpeechOutputStore
        private lateinit var speechAdapter: SpeechOutputStoragePurgeAdapter
        private var speechCollection: Any? = null
        private val runtime = NativeRecordingRuntime()
        private var actor: AndroidFeedbackActuator? = null
        private var ownSession: GatewayFieldSession? = null
        private var deletionLease: AccountDeletionActivityLease? = null
        private var ownPurgeAndDrainSucceeded = false
        private val deletionCoordinator get() = field(main, "accountDeletionProcessCoordinator") as AccountDeletionProcessCoordinator
        private val submittedActors = mutableSetOf<String>()
        private var expectedCommitCount = 0
        private var profileWritesDrained = false
        val profilePreferences get() = context.getSharedPreferences(profilePrefsName, 0)
        val positionController get() = (field(main, "positionFieldController\$delegate") as Lazy<*>).value
            as PositionFieldRecorderController
        val positionScopeStorage get() = field(positionController, "scopeStorage") as PositionFieldScopeStorage
        val coordinator get() = field(main, "positioningCoordinator") as PositioningCoordinator
        val authority get() = field(main, "gatewayWalkAuthorityController") as GatewayWalkAuthorityController
        val binding get() = requireNotNull(field(main, "positioningCollectionBinding"))
        val motionOwner get() = requireNotNull(coordinator.currentUserMotionOwner())
        private val bindingClass = kr.co.hanium.dreamup.walksafe.navigation.session.PositioningCollectionBinding::class.java

        init {
            check(Looper.myLooper() == Looper.getMainLooper())
            val component = ComponentName(context, MainActivity::class.java)
            @Suppress("DEPRECATION")
            main = instrumentation.newActivity(MainActivity::class.java, context, Binder(),
                instrumentation.targetContext.applicationContext as Application, Intent().setComponent(component),
                context.packageManager.getActivityInfo(component, 0), "Main local account fence storage probe",
                null, null, null) as MainActivity
        }

        fun prepare() {
            assertTrue(BuildConfig.DEBUG)
            assertFalse(BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
            listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO).forEach {
                assertEquals("Existing OS grant is required; fixture never grants $it", PackageManager.PERMISSION_GRANTED,
                    context.checkSelfPermission(it))
            }
            assertEquals("No ACTIVITY_RECOGNITION grant or sensor success is supplied",
                PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION))
            assertTrue(requireNotNull(context.getSystemService(LocationManager::class.java)).isLocationEnabled)
            assertNull(GatewaySessionProcessCoordinator.snapshot().session)
            assertNull(GatewaySessionProcessCoordinator.snapshot().restoredFirstRunSnapshot)
            assertSame(context, main.applicationContext)
            assertEquals(context.filesDir.canonicalFile, main.applicationContext.filesDir.canonicalFile)
            assertEquals(context.noBackupFilesDir.canonicalFile, main.noBackupFilesDir.canonicalFile)
            val positionPreferencesName = AndroidPositionFieldScopeStorage::class.java
                .getDeclaredField("POSITION_FIELD_PREFERENCES").apply { isAccessible = true }.get(null) as String
            assertSame(context.getSharedPreferences(positionPreferencesName, 0),
                main.getSharedPreferences(positionPreferencesName, 0))
            profileSpec = AndroidPedestrianProfileStore::class.java.getDeclaredField("PROFILE_PREFERENCE_SPEC")
                .apply { isAccessible = true }.get(null) as SensitivePreferenceSpec
            profileSpec = profileSpec.copy(keyPolicy = keyPolicy("profile"))
            profileStorage = NativeProfileStorage(AndroidSensitivePreferenceStore(profilePreferences,
                profileSpec, ownAead(profileSpec.keyPolicy)))
            profileStore = AndroidPedestrianProfileStore(profileStorage)
            assertFalse(profileStorage.isBlocked())
            recorder = PositionFieldSessionRecorder(context.noBackupFilesDir, ownAead(keyPolicy("position")),
                SystemClock::elapsedRealtimeNanos)
            val fieldAead = ownAead(keyPolicy("field"))
            fieldLog = PersistentFieldSessionLog(File(context.filesDir, "field_sessions"),
                FieldSessionDeviceInfo("controlled_native_fixture", Build.VERSION.SDK_INT.toString(),
                    "fixture", "frozen-Main9f71"), aead = fieldAead)
            fieldSessionId = requireNotNull(fieldLog.startAfterUserConfirmation())
            setField(main, "stepLengthPrefs", context.getSharedPreferences(stepPrefsName, 0))
            prepareLocalAccountOwnersAndReportStorage()
            prepareSpeechStorage(fieldAead)
            setField(main, "pedestrianProfileStore", profileStore)
            val recorderStore = PositionFieldSessionRecorderStore(recorder)
            setOwnerField(positionController, "recorderFactory", { recorderStore })
            positionController.initialize()
            assertFalse(positionController.storageBlocked)
            assertSame(recorderStore, field(positionController, "recorder"))
            assertSame(recorder, field(recorderStore, "recorder"))
            assertEquals(positionScopeStorage.localScopeId(), field(positionController, "localScopeId"))
            setField(main, "fieldSessionLog", fieldLog)
            setField(main, "walkSessionLifecycle", lifecycle)
            setField(main, "gatewayWalkRenewalHandler", renewalHandler)
            setField(main, "statusText", TextView(context))
            setField(main, "detailText", TextView(context))
            setField(main, "fusedLocationClient", transport.client)
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            setField(main, "isActivityForeground", lifecycle.snapshot().isForeground)
            publishGatewayOwner(actorId)
            activateOwnedWalk()
            assertEquals(true, invoke("isWalkSessionRuntimeActive", java.lang.Boolean.TYPE))
            assertEquals(actorId, invoke("currentReporterUserId", String::class.java))
            startBindingAndRecorder()
            val session = requireNotNull(ownSession)
            val permission = field(main, "permissionSessionPolicy") as PermissionSessionPolicy
            val consent = field(main, "integratedConsentSession") as IntegratedConsentSession
            val guard = object : IntegratedConsentAuthorityGuard {
                override fun <T : Any> withCurrent(action: () -> T): T? =
                    GatewaySessionProcessCoordinator.withCurrentGeneralSession(requireNotNull(gatewayGeneration), session, action)
            }
            assertEquals(IntegratedConsentApplyResult.APPLIED, consent.applyConfirmation(confirmation(id), guard))
            permission.setRawCollectionConsent(true)
            // Real typed receipt/permission owners; no capture/currentness Boolean is injected.
            val runtimeActor = AndroidFeedbackActuator(runtime = runtime)
            actor = runtimeActor
            runtime.ready()
            MainActivityControllerTestFixture.replaceActuator(main, runtimeActor)
            assertSame(runtimeActor, invoke("ensureFeedbackActuator", AndroidFeedbackActuator::class.java))
            assertNotNull(runtimeActor.speechOutputListener)
        }

        private fun prepareLocalAccountOwnersAndReportStorage() {
            assertFalse(deletionCoordinator.stateMachine.processingBlocked())
            deletionLease = deletionCoordinator.attach()
            setField(main, "accountDeletionActivityLease", requireNotNull(deletionLease))
            val prefs = field(main, "stepLengthPrefs") as SharedPreferences
            val fence = AccountDeletionIntentFence(
                read = { val values = prefs.all; if (!values.containsKey("account_deletion_intent_fence_v1")) false
                    else (values["account_deletion_intent_fence_v1"] as? Boolean)?.takeIf { it } },
                store = { prefs.edit().putBoolean("account_deletion_intent_fence_v1", true).commit() },
                clear = { prefs.edit().remove("account_deletion_intent_fence_v1").commit() })
            val fileOwner = FileAccountDeletionIntentAuthority(File(context.noBackupFilesDir, "account_deletion_intent_authority"))
            val dual = AccountDeletionDualAuthority(fence, fileOwner)
            setField(main, "accountDeletionIntentFence", fence)
            setField(main, "accountDeletionPreparedConfirmationRecovery", fileOwner)
            setField(main, "accountDeletionDualAuthority", dual)
            val reset = invoke("createAccountDeletionResetCoordinator", AccountDeletionResetCoordinator::class.java,
                arrayOf(File::class.java), context.noBackupFilesDir) as AccountDeletionResetCoordinator
            setField(main, "accountDeletionResetCoordinator", reset)
            setField(main, "accountDeletionAuthorityAtStartup", dual.startupState())
            setField(main, "accountDeletionResetJournalStateAtStartup", reset.journalState())
            assertFalse(dual.startupState().blocksPrivacy)
            val spec = MainActivityControllerTestFixture.field("SENSITIVE_PREF_SPEC").apply { isAccessible = true }
                .get(null) as SensitivePreferenceSpec
            sensitive = AndroidSensitivePreferenceStore(prefs, spec, ownAead(keyPolicy("sensitive")))
            setField(main, "sensitivePrefs", sensitive)
            assertFalse(sensitive.isBlocked())
            val real = FileReportQueueStorage(context.filesDir)
            realReportStorage = real
            val observed = object : ReportQueueStorage by real {
                override fun purgeAllWithFence(name: String, keyLifecycle: (ReportQueuePurgeContext) -> Boolean): Boolean {
                    val purged = try {
                        real.purgeAllWithFence(name) { context ->
                            reportKeyLifecycleContext.set(context)
                            val keyResult = try {
                                keyLifecycle(context)
                            } catch (failure: Throwable) {
                                reportKeyLifecycleExceptionClass.set(failure.javaClass.name)
                                runCatching { Log.i("AccountFenceFixture", "report_key storageReady=${context.storageReady} " +
                                    "accountDeleted=${context.accountDeleted} exceptionClass=${failure.javaClass.name}") }
                                throw failure
                            }
                            reportKeyLifecycleResult.set(keyResult)
                            runCatching { Log.i("AccountFenceFixture", "report_key storageReady=${context.storageReady} " +
                                "accountDeleted=${context.accountDeleted} result=$keyResult") }
                            keyResult
                        }
                    } catch (failure: Throwable) {
                        reportPurgeExceptionClass.set(failure.javaClass.name)
                        runCatching { Log.i("AccountFenceFixture", "report_purge exceptionClass=${failure.javaClass.name}") }
                        throw failure
                    }
                    runCatching { Log.i("AccountFenceFixture", "report_purge result=$purged " +
                        "keyContext=${reportKeyLifecycleContext.get()} keyResult=${reportKeyLifecycleResult.get()} " +
                        "keyExceptionClass=${reportKeyLifecycleExceptionClass.get()} purgeExceptionClass=${reportPurgeExceptionClass.get()}") }
                    reportPurgeResult.complete(purged)
                    return purged
                }
            }
            val queue = AndroidReportQueueStore(PRODUCTION_REPORT_QUEUE_CAPACITY_PROFILE, { observed },
                ownAead(keyPolicy("report")), UUID::randomUUID, System::currentTimeMillis,
                allocatableBytes = { context.filesDir.usableSpace })
            val drain = ReportQueueDrainCoordinator(queue)
            // These exact Lazy delegates must be set before any default report root/key access.
            setField(main, "reportQueueStore\$delegate", lazyOf(queue))
            val drainController = (field(main, "reportQueueDrainController\$delegate") as Lazy<*>).value!!
            setOwnerField(drainController, "reportQueueDrainCoordinator\$delegate", lazyOf(drain))
            assertSame(queue, (field(main, "reportQueueStore\$delegate") as Lazy<*>).value)
            assertSame(drain, (field(drainController, "reportQueueDrainCoordinator\$delegate") as Lazy<*>).value)
        }

        fun observeReportStorageReadinessOnTestWorker() {
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            val lexicalFiles = File(context.rawLexicalRoot, "files")
            val canonicalFiles = context.filesDir
            assertEquals("Both representations name the same physical own directory",
                canonicalFiles.canonicalFile, lexicalFiles.canonicalFile)
            // Existing production guard consumes each own path; no USER or OS ancestor inventory.
            val lexicalUsage = FileReportQueueStorage(lexicalFiles).measureUsage()
            val canonicalUsage = realReportStorage.measureUsage()
            Log.i("AccountFenceFixture", "report_path lexicalRoot=${context.rawLexicalRoot.absolutePath} " +
                "canonicalRoot=${context.root.absolutePath} lexicalUsage=$lexicalUsage canonicalUsage=$canonicalUsage")
            val empty = ReportQueueStorageUsage(0, 0L)
            assertEquals("Canonical own report root must satisfy the unchanged production guard", empty, canonicalUsage)
            val lockedUsage = realReportStorage.withLockedAccess { it.measureUsage() }
            Log.i("AccountFenceFixture", "report_ready lockedUsage=$lockedUsage")
            assertEquals("Canonical own root creation/sync/lock must be ready before the fence", empty, lockedUsage)
        }

        private fun prepareSpeechStorage(fieldAead: LocalAead) {
            val access = PersistentFieldSessionLog.SpeechStorageAccess.create(context.filesDir, aead = fieldAead)
            assertEquals(File(context.filesDir, "field_sessions").canonicalFile, access.rootDirectory.canonicalFile)
            speechProvider = FieldSpeechOutputStorageProvider(access)
            speechAdapter = SpeechOutputStoragePurgeAdapter(speechProvider, speechProvider::purgeForRawSourceWithdrawal)
            speechStore = ConsentBoundSpeechOutputStore(speechAdapter) { value ->
                invoke("speechOutputStorageBindingIsCurrent", java.lang.Boolean.TYPE,
                    arrayOf(SpeechOutputStorageBinding::class.java), value) as Boolean
            }
            val type = Class.forName("kr.co.hanium.dreamup.walksafe.feedback.SpeechOutputCollection")
            val constructor = type.getDeclaredConstructor(File::class.java, FieldSpeechOutputStorageProvider::class.java,
                SpeechOutputStoragePurgeAdapter::class.java, ConsentBoundSpeechOutputStore::class.java)
                .apply { isAccessible = true }
            val collection = constructor.newInstance(context.filesDir, speechProvider, speechAdapter, speechStore)
            speechCollection = collection
            @Suppress("UNCHECKED_CAST")
            (field(main, "speechOutputCollection") as AtomicReference<Any?>).set(collection)
        }

        fun seedSpeechOutput(): (SpeechOutputEvent) -> Unit {
            val listener = onMain(instrumentation) {
                val actual = requireNotNull(actor)
                assertSame(actual, invoke("ensureFeedbackActuator", AndroidFeedbackActuator::class.java))
                val captured = requireNotNull(actual.speechOutputListener)
                // Observation only: replay below retains an actual Actor event and its original clock.
                actual.speechOutputListener = { event -> lastObservedSpeechEvent.set(event); captured(event) }
                assertEquals(NavigationSpeechDispatchResult.ACCEPTED, actual.speakNavigation("own account fence storage probe"))
                captured
            }
            val id = runtime.spoken.last()
            onMain(instrumentation) { runtime.start(id) }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) { runtime.done(id) }
            instrumentation.waitForIdleSync()
            await("Three actual encrypted Actor records") { speechRecordFiles().sumOf { it.readLines().size } == 3 }
            val rows = speechRecordFiles().flatMap { file ->
                val window = file.parentFile!!.name.removePrefix("window-").toLong()
                file.readLines().mapIndexed { ordinal, envelope ->
                    val opened = speechProvider.aead.open(envelope,
                        PersistentFieldSessionLog.SpeechStorageAccess.recordAad(window, ordinal.toLong()), speechProvider.limits)
                    assertTrue(opened is AeadOpenResult.Opened)
                    val plaintext = (opened as AeadOpenResult.Opened).plaintext
                    try { JSONObject(String(plaintext, Charsets.UTF_8)) } finally { plaintext.fill(0) }
                }
            }
            assertEquals(listOf("REQUESTED", "STARTED", "DONE"), rows.map { it.getString("state") })
            rows.forEach {
                assertEquals(setOf("metric", "state", "elapsed_realtime_ms", "result"), it.keys().asSequence().toSet())
                assertEquals("NAVIGATION", it.getString("metric")); assertEquals("NONE", it.getString("result"))
                assertTrue(runtime.clockReads.contains(it.getLong("elapsed_realtime_ms")))
                assertFalse(it.toString().contains("own account fence storage probe"))
            }
            return listener
        }

        fun speechRecordFiles(): List<File> = File(context.filesDir, "field_sessions/speech-output")
            .walkTopDown().filter { it.isFile && it.name.matches(Regex("records-[0-9]{4}\\.jsonl")) }.toList()

        @Suppress("UNCHECKED_CAST")
        fun speechPurgeFuture(): CompletableFuture<Boolean> =
            (field(requireNotNull(speechCollection), "purgeFuture") as AtomicReference<CompletableFuture<Boolean>?>).get()
                ?: throw AssertionError("Actual Main account caller must submit Store purge")

        fun prepareAndFenceAccountOnPrivacyWorker() {
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            val finished = CompletableFuture<Unit>()
            deletionCoordinator.executor.execute {
                try {
                    val preparationType = kr.co.hanium.dreamup.walksafe.session.deletion.runtime.AccountDeletionAuthorityPreparation::class.java
                    val preparation = requireNotNull(invoke("prepareAccountDeletionIntentAuthority", preparationType))
                    assertEquals(true, field(preparation, "confirmed"))
                    val dual = field(main, "accountDeletionDualAuthority") as AccountDeletionDualAuthority
                    assertEquals(AccountDeletionIntentAuthorityState.Confirmed, dual.startupState().fileState)
                    assertTrue(dual.startupState().blocksPrivacy)
                    val result = invoke("applyAccountDeletionRuntimeFence", java.lang.Boolean.TYPE) as Boolean
                    positionPurgeResult.complete(result)
                    finished.complete(Unit)
                } catch (failure: Throwable) {
                    positionPurgeResult.completeExceptionally(failure)
                    finished.completeExceptionally(failure)
                }
            }
            finished.get(15L, TimeUnit.SECONDS)
        }

        fun aliasExists(alias: String): Boolean = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias)

        fun assertOwnedPurgeReadback() {
            assertTrue(speechRecordFiles().isEmpty())
            assertTrue("Field datasets gone; durable account/purge markers may remain",
                File(context.filesDir, "field_sessions").listFiles().orEmpty().none { it.isDirectory })
            assertTrue("Position dataset absent; actual recorder may retain its empty root",
                File(context.noBackupFilesDir, "position_field_sessions_v1").listFiles()?.isEmpty() == true)
            assertTrue(File(context.filesDir, "report_queue_v1/reports").listFiles()?.isEmpty() == true)
            assertFalse(aliasExists(fieldKeyAlias))
            assertFalse(aliasExists(reportKeyAlias))
            assertTrue("Empty report queue purge ran the actual native key lifecycle", aliasExists("$reportKeyAlias.generation"))
            assertTrue("Existing field tombstone metadata is distinct from a live key", aliasExists("$fieldKeyAlias.generation"))
            assertTrue("Confirmed local file authority remains", (field(main, "accountDeletionDualAuthority") as
                AccountDeletionDualAuthority).startupState().blocksPrivacy)
        }

        fun releaseOwnDeletionLease() {
            val lease = deletionLease ?: return
            if (deletionCoordinator.stateMachine.activityLeaseIsCurrent(lease)) {
                assertTrue(deletionCoordinator.detach(lease))
            }
            deletionLease = null
        }

        fun releaseOwnGateway() {
            val generation = gatewayGeneration ?: return
            val snapshot = GatewaySessionProcessCoordinator.snapshot()
            assertEquals(generation, snapshot.generation)
            assertSame(ownSession, snapshot.session)
            assertNotNull(GatewaySessionProcessCoordinator.clear(generation))
            gatewayGeneration = null
        }

        private fun keyPolicy(purpose: String) = AeadKeyPolicy(
            "walksafe.fixture.account.${id.replace("-", "")}.$purpose.v", 1, setOf(1))
        private fun ownAead(policy: AeadKeyPolicy): LocalAead = AndroidKeyStoreAead(policy).also(ownedAead::add)

        private fun publishGatewayOwner(actor: String) {
            val onboarding = completedOnboarding(actor)
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            gatewayGeneration = operation.generation
            val session = GatewayFieldSession.verified("https://gateway.example.test", actor,
                "walksafe_field_session=synthetic_motion_storage_probe", System.currentTimeMillis() + 120_000L)
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, onboarding))
            gatewayGeneration = GatewaySessionProcessCoordinator.snapshot().generation
            ownSession = session
            setField(main, "firstRunOnboardingSnapshot", onboarding)
            MainActivityControllerTestFixture.publishDeviceSnapshot(main, completedDeviceCheck(actor, requireNotNull(gatewayGeneration)))
            setField(main, "reporterUserId", actor)
            setField(main, "priorityUserOnboardingActorId", actor)
            setField(main, "priorityUserOnboardingPolicy", readyPriorityPolicy())
            (field(main, "permissionSessionPolicy") as PermissionSessionPolicy).authenticated(actor)
        }

        private fun activateOwnedWalk() {
            val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
            val epoch = lifecycle.snapshot().epoch
            val readiness = WalkSessionReadinessCollector(epoch, plan).apply {
                plan.requiredRequirements.forEach { record(WalkSessionReadinessObservation(epoch, it,
                    WalkSessionReadinessStatus.READY, "controlled typed motion-storage readiness")) }
            }.build(SystemClock.elapsedRealtime())
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readiness))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
            val activeEpoch = lifecycle.snapshot().epoch
            val start = requireNotNull(authority.beginStart(activeEpoch))
            val session = requireNotNull(GatewaySessionProcessCoordinator.snapshot().session)
            val wall = System.currentTimeMillis(); val elapsed = SystemClock.elapsedRealtime()
            assertEquals(GatewayWalkAuthorityCompletion.LEASE_ACTIVE,
                authority.completeStart(start, GatewayWalkStartResult.Granted(GatewayWalkLease(
                    "started", actorId, session.deviceId, activeEpoch.walkSessionId, "synthetic-motion-storage-lease",
                    1L, wall, wall + 120_000L, wall, elapsed + 120_000L))))
            authorityEpoch = activeEpoch
        }

        fun startBindingAndRecorder() {
            // The collector sees no field lease yet, and normal navigation prerequisites remain unavailable.
            invoke("startLocationUpdatesIfAllowed", Void.TYPE, arrayOf(java.lang.Boolean.TYPE), false)
            assertTrue(bindingIsCurrent(binding))
            assertEquals(false, invoke("currentNavigationCollectionAllowsWork", java.lang.Boolean.TYPE))
            assertNull(field(main, "pedestrianMotionTracker"))
            val epoch = requireNotNull(lifecycle.currentRuntimeEpochOrNull())
            val scopeId = UUID.randomUUID().toString()
            // Same UUID/native scope fixture boundary, now durably owned by the current adapter.
            assertTrue(positionScopeStorage.storeScope(scopeId, clearPurgePending = false))
            assertEquals(scopeId, positionScopeStorage.localScopeId())
            setOwnerField(positionController, "localScopeId", scopeId)
            assertTrue(positionController.start(PositionFieldStartRequest(
                routeId = UUID.randomUUID().toString(), exactCoordinateExportConfirmed = true,
                chestCalibrationConfirmed = true, deviceModel = "controlled_native_fixture",
                androidApi = Build.VERSION.SDK_INT,
            )) { invoke("currentPositionFieldAuthority", FieldRecordAuthority::class.java) as FieldRecordAuthority })
            val recording = requireNotNull(field(positionController, "recording"))
            val lease = field(recording, "lease") as PositionFieldSessionLease
            recorderBinding = RecorderBinding(scopeId, epoch.recoveryGeneration)
            recorderBindings += recorderBinding
            assertEquals(epoch, field(recording, "walkEpoch"))
            assertEquals(GatewaySessionProcessCoordinator.snapshot().generation, field(recording, "gatewayGeneration"))
            assertEquals(scopeId, field(recording, "localScopeId"))
            assertTrue(positionController.explicitChestConfirmed)
            assertTrue(currentFieldAdmission())
            assertSame(lease, invoke("currentPositionFieldLeaseOrNull", PositionFieldSessionLease::class.java))
        }

        fun recordOwnedStepAndLateSnapshot(): CapturedInput {
            val capturedBinding = onMain(instrumentation) { assertTrue(bindingIsCurrent(binding)); binding }
            val capturedOwner = onMain(instrumentation) { motionOwner }
            val initializedAt = afterClock(onMain(instrumentation) {
                field(capturedBinding, "startedAtElapsedRealtimeMs") as Long
            })
            onMain(instrumentation) { apply(coordinator.observeGnss(
                GnssPositionObservation(37.0, 127.0, 2.0, initializedAt), coordinator.currentInputGeneration()), capturedBinding) }
            val measuredNs = afterClockNanos(initializedAt)
            val laterFixAt = afterClock(measuredNs / 1_000_000L)
            onMain(instrumentation) { apply(coordinator.observeGnss(
                GnssPositionObservation(37.0, 127.0, 2.0, laterFixAt), coordinator.currentInputGeneration()), capturedBinding) }
            val receivedNs = SystemClock.elapsedRealtimeNanos()
            val event = StepEvent(1, measuredNs / 1_000_000L, StepEventSource.STEP_DETECTOR,
                StepEventConfidence.HIGH, measuredNs, receivedNs)
            onMain(instrumentation) {
                assertTrue(currentFieldAdmission())
                val countBefore = traceCount(recorderBinding)
                val lateBefore = lateRecords().size
                deliverStep(event, capturedBinding, capturedOwner, receivedNs / 1_000_000L)
                assertSame(event, field(main, "latestWalkingCalibrationStep"))
                assertEquals(event.timestampMs, coordinator.peekUserMotion(receivedNs / 1_000_000L).stepObservedAtElapsedRealtimeMs)
                assertEquals(countBefore + 1L, traceCount(recorderBinding))
                assertEquals(lateBefore + 1, lateRecords().size)
                assertNull(event.producerRegistrationGeneration)
                assertEquals(measuredNs, event.measurementElapsedRealtimeNanos)
                assertEquals(receivedNs, event.receivedAtElapsedRealtimeNanos)
            }
            val nextMeasuredNs = afterClockNanos(laterFixAt)
            val nextFixAt = afterClock(nextMeasuredNs / 1_000_000L)
            return onMain(instrumentation) {
                apply(coordinator.observeGnss(GnssPositionObservation(37.0, 127.0, 2.0, nextFixAt),
                    coordinator.currentInputGeneration()), capturedBinding)
                val received = SystemClock.elapsedRealtimeNanos()
                val original = StepEvent(1, nextMeasuredNs / 1_000_000L, StepEventSource.STEP_DETECTOR,
                    StepEventConfidence.HIGH, nextMeasuredNs, received)
                val snapshot = coordinator.observeStep(1, PedestrianHeadingInput(original.timestampMs,
                    speed = null, gpsCourse = null, magneticTrueHeading = null, phoneForwardMounted = false), PdrStepQuality.HIGH,
                    receivedAtElapsedRealtimeMs = received / 1_000_000L, expectedGeneration = coordinator.currentInputGeneration(),
                    expectedMotionOwner = capturedOwner, stepEventSource = original.source,
                    stepEventConfidence = original.confidence, originalStepEvent = original)
                val fields = requireNotNull(snapshot.motionUpdate?.lateMotionEventFieldsOrNull())
                assertTrue((fields["measurement_lag_ms"] as Number).toLong() > 0L)
                assertNotEquals(PedestrianMotionUpdateDisposition.HARD_REJECTED, snapshot.motionUpdate?.disposition)
                assertEquals(capturedOwner, snapshot.userMotion?.owner)
                assertEquals(original.timestampMs, snapshot.userMotion?.stepObservedAtElapsedRealtimeMs)
                val before = lateRecords().size
                apply(snapshot, capturedBinding)
                val records = lateRecords()
                assertEquals(before + 1, records.size)
                val logged = JSONObject(records.last()).getJSONObject("fields")
                fields.forEach { (key, value) ->
                    if (value == null) assertFalse(logged.has(key))
                    else assertEquals(value.toString(), logged.get(key).toString())
                }
                CapturedInput(event, capturedBinding, capturedOwner, receivedNs / 1_000_000L,
                    snapshot, original, received / 1_000_000L)
            }
        }

        fun deliverStep(event: StepEvent, capturedBinding: Any, owner: UserMotionStateOwner, receivedAt: Long) =
            invoke("handlePositioningStepEvent", Void.TYPE,
                arrayOf(StepEvent::class.java, bindingClass, java.lang.Long.TYPE, UserMotionStateOwner::class.java),
                event, capturedBinding, receivedAt, owner)
        fun apply(snapshot: PositioningSnapshot, capturedBinding: Any) = invoke("applyPositioningMotionSnapshot", Void.TYPE,
            arrayOf(PositioningSnapshot::class.java, bindingClass), snapshot, capturedBinding)
        fun bindingIsCurrent(value: Any) = invoke("isPositioningCollectionCurrent", java.lang.Boolean.TYPE,
            arrayOf(bindingClass), value) as Boolean
        fun currentFieldAdmission() = invoke("currentPositionFieldCollectionAllowsWork", java.lang.Boolean.TYPE) as Boolean
        fun traceCount(value: RecorderBinding) = recorder.list(value).sumOf { it.recordCount }

        fun restoreAndSubmitProfile(): PersistedPedestrianMotionProfile {
            val restored = PersistedPedestrianMotionProfile(.70, 1.20, .16, 3L, calibrationConditionKnown = false)
            assertTrue(coordinator.restoreProfile(restored))
            val profile = requireNotNull(coordinator.snapshotProfileForPersistence())
            assertEquals(actorId, invoke("currentReporterUserId", String::class.java))
            assertEquals(actorId, field(main, "positioningProfileActorId"))
            expectedCommitCount = profileStorage.successfulCommits.get() + 1
            submittedActors += actorId
            profileWritesDrained = false
            invoke("persistPositioningProfileForCurrentActor", Void.TYPE)
            return profile
        }

        fun awaitOwnedProfileCommit() {
            await("Main's actual profile save commit and its own serial drain") {
                profileStorage.successfulCommits.get() >= expectedCommitCount &&
                    submittedActors.all { SerialPedestrianProfileWriter.shared.pendingProfileOrNull(it) == null }
            }
            profileWritesDrained = true
        }

        fun lateRecords(): List<String> {
            val sessionId = requireNotNull(fieldSessionId)
            var ordinal = 0L
            return File(File(context.filesDir, "field_sessions"), sessionId).listFiles().orEmpty()
                .filter { it.name.matches(Regex("records-[0-9]{4}\\.jsonl")) }.sortedBy { it.name }
                .flatMap { file ->
                    val segmentIndex = file.name.substring(8, 12).toInt()
                    file.readLines().filter(String::isNotBlank).map { envelope ->
                        val plain = MethodHandles.privateLookupIn(PersistentFieldSessionLog::class.java, MethodHandles.lookup())
                            .findVirtual(PersistentFieldSessionLog::class.java, "openRecord", MethodType.methodType(
                                String::class.java, String::class.java, String::class.java, java.lang.Integer.TYPE, java.lang.Long.TYPE))
                            .bindTo(fieldLog).invokeWithArguments(envelope, sessionId, segmentIndex, ordinal++) as String?
                        requireNotNull(plain) { "Actual native Field record must authenticate" }
                    }
                }.filter { JSONObject(it).optString("event_name") == "pdr_late_motion" }
        }

        fun checkpoint() = Checkpoint(field(main, "positioningCollectionBinding"), field(main, "locationCallback"),
            field(main, "locationCollectionOwner"), field(main, "locationCallbackGeneration") as Int,
            coordinator.currentUserMotionOwner(), coordinator.peekUserMotion(SystemClock.elapsedRealtime()).stepObservedAtElapsedRealtimeMs,
            field(main, "latestWalkingCalibrationStep"),
            field(main, "latestPositionStationaryState"), field(main, "latestPositionStationarySinceMs"),
            field(main, "latestTrustedLocation"), field(main, "latestRawRouteLocation"),
            field(main, "latestGnssQualitySnapshot"), field(main, "stepTracker"), field(main, "pedestrianMotionTracker"),
            invoke("currentMotionEvidenceStatusMessage", String::class.java) as String, coordinator.currentProfile(),
            recorderBindings.map { it.localAccountScopeId to traceCount(it) }, lateRecords(),
            root.walkTopDown().filter(File::isFile).sortedBy { it.relativeTo(root).path }
                .map { it.relativeTo(root).path to sha256(it.readBytes()) }.toList(),
            profilePreferences.getString(profileSpec.storageKey, null),
            field(positionController, "recording"), positionScopeStorage.localScopeId())

        fun invoke(name: String, result: Class<*>, types: Array<Class<*>> = emptyArray(), vararg args: Any?): Any? =
            MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
                .findVirtual(MainActivity::class.java, name, MethodType.methodType(result, types.toList()))
                .bindTo(main).invokeWithArguments(args.toList())

        fun drainOwnWritesForCleanup() {
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            await("Only submitted fixture actors finish; the process writer is never closed/reset") {
                submittedActors.all { SerialPedestrianProfileWriter.shared.pendingProfileOrNull(it) == null }
            }
            profileWritesDrained = true
            if (::speechStore.isInitialized) {
                // Cleanup only this UUID store, before close; do not close any process writer.
                speechAdapter.markAccountDeletion()
                val purge = (speechCollection?.let { field(it, "purgeFuture") } as? AtomicReference<*>)?.get()
                    as? CompletableFuture<*>
                val purged = if (purge != null) purge.get(15L, TimeUnit.SECONDS) == true
                    else speechStore.invalidateAndPurge().get(15L, TimeUnit.SECONDS)
                val drained = speechStore.closeAndDrain().get(15L, TimeUnit.SECONDS)
                ownPurgeAndDrainSucceeded = purged && drained
                assertTrue("Own fixture purge and drain must both succeed before key/pref/root cleanup", ownPurgeAndDrainSucceeded)
            } else {
                // Partial preparation created no event Store/observer; the profile drain above still applies.
                ownPurgeAndDrainSucceeded = true
            }
        }

        fun close() {
            var first: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (problem: Throwable) {
                    if (first == null) first = problem else first!!.addSuppressed(problem)
                }
            }
            cleanup { assertEquals(true, invoke("stopLocationUpdates", java.lang.Boolean.TYPE, arrayOf(java.lang.Boolean.TYPE), true)) }
            cleanup { renewalHandler.removeCallbacksAndMessages(null) }
            cleanup { authorityEpoch?.let(authority::invalidate) }
            cleanup { setField(main, "isActivityForeground", false); lifecycle.handle(WalkSessionEvent.EnteredBackground) }
            cleanup { releaseOwnGateway() }
            cleanup { actor?.let { MainActivityControllerTestFixture.replaceActuator(main, null); it.close() } }
            cleanup { runtime.close() }
            cleanup { releaseOwnDeletionLease() }
            cleanup { if (::fieldLog.isInitialized) fieldLog.close() }
            cleanup { if (::fieldLog.isInitialized && ownPurgeAndDrainSucceeded) assertTrue(fieldLog.purgeAll()) }
            cleanup { if (::recorder.isInitialized && ownPurgeAndDrainSucceeded) assertTrue(recorder.purge()) }
            // If the own serial drain failed, retain profile material/keys rather than racing a queued write.
            cleanup {
                if (::profileStorage.isInitialized && profileWritesDrained && ownPurgeAndDrainSucceeded) {
                    assertTrue(profileStorage.native.edit().clear().commit())
                }
            }
            cleanup { if (::sensitive.isInitialized && ownPurgeAndDrainSucceeded) assertTrue(sensitive.destroyAndClear()) }
            cleanup { if (profileWritesDrained && ownPurgeAndDrainSucceeded) context.deleteOwnPreferences() }
            if (profileWritesDrained && ownPurgeAndDrainSucceeded) ownedAead.forEach { owned -> cleanup { assertTrue(owned.destroyKnownVersions()) } }
            cleanup { if (profileWritesDrained && ownPurgeAndDrainSucceeded) assertTrue(root.deleteRecursively()) }
            first?.let { throw it }
        }
    }

    private class NativeRecordingRuntime : FeedbackSpeechRuntime, FeedbackSpeechScheduler, AutoCloseable {
        private val handler = Handler(Looper.getMainLooper())
        private var initialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        val spoken = Collections.synchronizedList(mutableListOf<String>())
        val clockReads = Collections.synchronizedList(mutableListOf<Long>())
        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized: Boolean get() = initialized != null
        override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime().also(clockReads::add)
        override fun initialize(onInitialized: (Int) -> Unit) { initialized = onInitialized }
        override fun setLanguage(language: Locale): Int = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice() = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int { this.listener = listener; return TextToSpeech.SUCCESS }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int { spoken += utteranceId; return TextToSpeech.SUCCESS }
        override fun stop() = Unit
        override fun shutdown() = Unit
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener) = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = throw AssertionError("No fixture progress tone")
        override fun post(task: Runnable) = handler.post(task)
        override fun postDelayed(task: Runnable, delayMs: Long) = handler.postDelayed(task, delayMs)
        override fun removeCallbacks(task: Runnable) = handler.removeCallbacks(task)
        override fun removeCallbacksAndMessages(token: Any?) = handler.removeCallbacksAndMessages(token)
        fun ready() { requireNotNull(initialized).invoke(TextToSpeech.SUCCESS) }
        fun start(id: String) { requireNotNull(listener).onStart(id) }
        fun done(id: String) { requireNotNull(listener).onDone(id) }
        override fun close() { handler.removeCallbacksAndMessages(null) }
    }

    private companion object {
        const val ACTOR = "77777777-7777-4777-8777-777777777777"
        const val NEW_ACTOR = "88888888-8888-4888-8888-888888888888"
        fun assertInitialProcessAndTarget(instrumentation: Instrumentation) {
            val context = instrumentation.targetContext
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            assertEquals("kr.co.hanium.dreamup.walksafe.speechstorefixture", context.packageName)
            assertEquals(context.applicationInfo.uid, Process.myUid())
            @Suppress("DEPRECATION")
            assertNull(context.packageManager.getPackageInfo(context.packageName, 0).sharedUserId)
            val descriptor = instrumentation.uiAutomation.executeShellCommand("cmd package list packages -U kr.co.hanium.dreamup.walksafe")
            val userUids = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
                reader.readLines().mapNotNull { Regex("^package:kr\\.co\\.hanium\\.dreamup\\.walksafe\\s+uid:([0-9]+)\\s*$")
                    .matchEntire(it.trim())?.groupValues?.get(1)?.toIntOrNull() }
            }
            assertEquals(1, userUids.size); assertNotEquals(userUids.single(), Process.myUid())
            assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION))
            assertFalse(ReportPrivacyConsentSession.isGranted())
            assertFalse(ReportPrivacyConsentSession.isAccountDeletionBlocked())
            assertNull(ReportPrivacyConsentSession.issueUploadPermit(ReportTransferPurpose.EXPLICIT))
            assertNull(ReportPrivacyConsentSession.issueUploadPermit(ReportTransferPurpose.AUTOMATIC))
            val gateway = GatewaySessionProcessCoordinator.snapshot()
            assertNull(gateway.session); assertNull(gateway.restoredFirstRunSnapshot); assertNull(gateway.inFlightOperationId)
            assertFalse(gateway.storageBlocked); assertFalse(gateway.deletionRecoveryOnly)
            assertFalse(AccountDeletionProcessCoordinator.shared.stateMachine.processingBlocked())
            assertEquals(AccountDeletionPhase.IDLE, AccountDeletionProcessCoordinator.shared.stateMachine.phase())
            // Existing APIs do not expose Report's private call set. A new sole PID is a required
            // external precondition, not inferred from these permission/permit observations.
        }
        fun confirmation(id: String) = IntegratedConsentConfirmation(
            schemaVersion = INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION, policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
            installationId = id, requestId = "account_fence_consent_${id.replace("-", "")}",
            itemVersions = IntegratedConsentItemVersions(), clientRevision = 1L, revision = 1L,
            selections = IntegratedConsentSelections(rawSourceCollection = true), confirmedAt = "2026-07-25T12:00:00.000Z",
            gatewayAuditRecordSha256 = "9".repeat(64), backendConsentReceiptSha256 = "a".repeat(64), controlSecret = "b".repeat(64))
        fun <T> onMain(instrumentation: Instrumentation, action: () -> T): T {
            val value = AtomicReference<T>(); val failure = AtomicReference<Throwable?>()
            instrumentation.runOnMainSync { try { value.set(action()) } catch (problem: Throwable) { failure.set(problem) } }
            failure.get()?.let { throw it }; return value.get()
        }
        fun await(description: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10_000L
            while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10L)
            assertTrue(description, condition())
        }
        fun afterClock(previous: Long): Long {
            await("Distinct real elapsed input after $previous") { SystemClock.elapsedRealtime() > previous }
            return SystemClock.elapsedRealtime()
        }
        fun afterClockNanos(previousMs: Long): Long { afterClock(previousMs); return SystemClock.elapsedRealtimeNanos() }
        fun field(target: Any, name: String): Any? = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.get(target)
        fun setField(main: MainActivity, name: String, value: Any?) = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.set(main, value)
        fun setOwnerField(owner: Any, name: String, value: Any?) = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.set(owner, value)
        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        fun receipt(value: Int) = FirstRunReceiptHash.fromSha256Hex(value.toString(16).padStart(64, '0'))
        fun completedOnboarding(actor: String): FirstRunOnboardingSnapshot {
            val evidence = listOf(FirstRunOnboardingEvidence.VerifiedLogin(FirstRunOpaqueActorBinding.fromProvider(actor), receipt(1)),
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
        fun completedDeviceCheck(actor: String, generation: Long): PostLoginDeviceCheckSnapshot {
            val bound = PostLoginDeviceCheckPolicy.bindSession(PostLoginDeviceCheckPolicy.initial(), actor, generation)
            val started = PostLoginDeviceCheckPolicy.beginFromUserAction(bound, actor, generation, true)
            val binding = requireNotNull(started.bindingOrNull)
            val running = PostLoginDeviceCheckPolicy.beginRunning(started, binding, true)
            val ready = PostLoginDeviceCheckSignal.READY
            return PostLoginDeviceCheckPolicy.evaluate(running, binding, true, PostLoginDeviceCheckObservation(
                ready, ready, ready, ready, ready, ready, ready, ready, ready, ready, ready,
                metricDepth = PostLoginMetricDepthState.SUPPORTED)).also { check(it.state == PostLoginDeviceCheckState.FULL) }
        }
        fun readyPriorityPolicy() = PriorityUserOnboardingPolicy().apply {
            selectAgeBand(PriorityUserAgeBand.ADULT_18_PLUS)
            reviewSafetyEducation(true); reviewPracticeNecessity(true); acceptEducationConsent()
            acknowledgeUsageConditions()
            finishAppUsageEducationPlayback(requireNotNull(beginAppUsageEducationPlayback()), completed = true)
            confirmSafePracticePlace()
        }
    }
}

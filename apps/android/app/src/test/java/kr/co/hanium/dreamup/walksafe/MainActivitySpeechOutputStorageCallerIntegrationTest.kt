package kr.co.hanium.dreamup.walksafe

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.fieldlog.NoopFieldSessionLog
import kr.co.hanium.dreamup.walksafe.fieldlog.PersistentFieldSessionLog.SpeechStorageAccess
import kr.co.hanium.dreamup.walksafe.fieldlog.TestFieldAead
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.security.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Actual Main existing-Actor ensure/refresh -> captured listener -> real Actor events -> accepted
 * Store/Provider/Field access -> encrypted file. Permission and speech platform effects are typed
 * doubles; the existing test AEAD supplies JVM keys. Reflection installs real memory owners and
 * storage inputs, never a ready/currentness result. No Main.onCreate, SDK dialog, AndroidKeyStore,
 * native TTS, real account or asynchronous fresh-provider recovery is executed here.
 */
class MainActivitySpeechOutputStorageCallerIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Before fun clearGateway() {
        GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
    }
    @After fun releaseGateway() {
        GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
    }

    @Test fun actualEnsureListenerEncryptsActorRequestStartDoneWithOnlyTheOriginalFourFields() {
        fixture().use { f ->
            assertSame(f.actor, f.ensure())
            val permissionReads = f.main.permissionReads
            val id = f.speakNavigation()
            f.runtime.now = 1_011L
            f.runtime.start(id)
            f.runtime.now = 1_019L
            f.runtime.done(id)
            f.awaitAppendCount(3)
            val rows = f.rows()
            assertEquals(listOf("REQUESTED", "STARTED", "DONE"), rows.map { it.getString("state") })
            assertEquals(listOf(1_000L, 1_011L, 1_019L), rows.map { it.getLong("elapsed_realtime_ms") })
            rows.forEach { row ->
                assertEquals(setOf("metric", "state", "elapsed_realtime_ms", "result"), row.keys().asSequence().toSet())
                assertEquals("NAVIGATION", row.getString("metric"))
                assertEquals("NONE", row.getString("result"))
                assertFalse(row.toString().contains("fixture spoken text"))
            }
            assertEquals("Worker currentness must never requery Android permission APIs",
                permissionReads, f.main.permissionReads)
            assertTrue(f.crypto.hasKey())
            assertEquals(1, f.recordFiles().size)
        }
    }

    @Test fun actualActorSetterFencesTheOldCapturedListenerAndNewActorKeepsTheSameUnblockedWindow() {
        fixture().use { f ->
            f.ensure()
            val oldListener = requireNotNull(f.actor.speechOutputListener)
            val first = f.speakNavigation()
            f.runtime.start(first)
            f.runtime.done(first)
            f.awaitAppendCount(3)
            val originalCollection = field(f.main, "speechOutputCollection") as AtomicReference<*>
            val originalWindow = field(originalCollection.get()!!, "window")
            val replacementRuntime = FakeRuntime()
            val replacement = AndroidFeedbackActuator(runtime = replacementRuntime).also { replacementRuntime.ready() }
            f.extraActors += replacement
            f.setActor(replacement) // Actual private Main setter, not a backing-field replacement.
            val drops = f.store.droppedEventCount()
            oldListener(SpeechOutputEvent(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.DONE, 1_001L))
            assertEquals(drops + 1L, f.store.droppedEventCount())
            assertEquals(3, f.appended.get())
            f.ensure()
            assertSame(originalWindow, field(originalCollection.get()!!, "window"))
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, replacement.speakNavigation("new owner fixture"))
            val id = replacementRuntime.spoken.single()
            replacementRuntime.start(id)
            replacementRuntime.done(id)
            f.awaitAppendCount(6)
            assertEquals(6, f.rows().size)
        }
    }

    @Test fun actualCurrentnessDropsOldEpochAndRawReceiptWithoutConsultingOsPermissionAgain() {
        listOf("epoch", "receipt").forEach { cause -> fixture().use { f ->
            f.ensure()
            val listener = requireNotNull(f.actor.speechOutputListener)
            val reads = f.main.permissionReads
            when (cause) {
                "epoch" -> f.lifecycle.handle(WalkSessionEvent.EnteredBackground) { previous, next ->
                    f.main.fenceSpeechOutputStorageBeforeWalkPublication(previous, next)
                }
                "receipt" -> assertTrue(f.consent.withdrawImmediately(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
            }
            val drops = f.store.droppedEventCount()
            listener(SpeechOutputEvent(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.REQUESTED, 1_000L))
            assertEquals(cause, drops + 1L, f.store.droppedEventCount())
            assertEquals(cause, 0, f.appended.get())
            assertEquals(reads, f.main.permissionReads)
        } }
    }

    @Test fun actualObservedMicDenialCannotInstallAListenerDespiteAuthenticatedRawPermissionPolicy() {
        fixture().use { f ->
            f.main.granted.remove(Manifest.permission.RECORD_AUDIO)
            assertTrue(f.permissions.snapshot().rawCollectionConsentGranted)
            assertSame(f.actor, f.ensure())
            assertNull(f.actor.speechOutputListener)
            f.speakNavigation()
            assertEquals(0, f.appended.get())
            assertTrue(f.recordFiles().isEmpty())
            assertFalse(f.crypto.hasKey())
        }
    }

    @Test fun actualAdmittedSynthesisErrorSurvivesReadyWhileTheSamePrivacyBindingRemainsCurrent() {
        fixture().use { f ->
            f.ensure()
            val id = f.speakNavigation()
            f.runtime.start(id)
            f.awaitAppendCount(2)
            val sameListener = f.actor.speechOutputListener
            f.blockErrorSeal = true
            f.runtime.now = 1_027L
            assertEquals(TtsRuntimeFailureDisposition.UTTERANCE,
                ttsRuntimeFailureDisposition(TextToSpeech.ERROR_SYNTHESIS, true, true))
            f.runtime.fail(id, TextToSpeech.ERROR_SYNTHESIS)
            assertTrue(f.errorSealEntered.await(2L, TimeUnit.SECONDS))
            // This is an utterance error; required engine-wide safety stop is not suppressed.
            f.runtime.ready()
            f.ensure()
            assertSame(sameListener, f.actor.speechOutputListener)
            f.releaseErrorSeal.countDown()
            f.awaitAppendCount(3)
            val error = f.rows().single { it.getString("state") == "ENGINE_ERROR" }
            assertEquals("NAVIGATION", error.getString("metric"))
            assertEquals(1_027L, error.getLong("elapsed_realtime_ms"))
            assertEquals(TextToSpeech.ERROR_SYNTHESIS, error.getInt("result"))
        }
    }

    @Test fun actualRawWithdrawalFencesTheCapturedListenerThenSeriallyPurgesTheRealRootAndKey() {
        fixture().use { f ->
            f.ensure()
            val oldListener = requireNotNull(f.actor.speechOutputListener)
            val id = f.speakNavigation()
            f.runtime.start(id)
            f.runtime.done(id)
            f.awaitAppendCount(3)
            assertTrue(f.crypto.hasKey())
            f.invoke("blockSpeechOutputStorageForRawWithdrawal", Void.TYPE)
            val drops = f.store.droppedEventCount()
            oldListener(SpeechOutputEvent(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.DONE, 1_010L))
            assertEquals(drops + 1L, f.store.droppedEventCount())
            val currentCollection = (field(f.main, "speechOutputCollection") as AtomicReference<*>).get()!!
            val future = (field(currentCollection, "purgeFuture") as AtomicReference<*>).get()
                as java.util.concurrent.CompletableFuture<*>
            assertEquals(true, future.get(2L, TimeUnit.SECONDS))
            assertTrue(f.recordFiles().isEmpty())
            assertFalse(f.crypto.hasKey())
            assertFalse((field(f.main, "speechOutputAccountDeletionIntent") as java.util.concurrent.atomic.AtomicBoolean).get())
            assertTrue(f.store.closeAndDrain().get(2L, TimeUnit.SECONDS))
            // Drain is quiescence; the separately checked purge/root/key observations prove deletion.
        }
    }

    @Test fun actualRawRecoveryWaitsForPurgeAndDrainWithoutPublishingThenRejectsTheChangedOwner() {
        val fault = RawRecoveryFault().apply { holdPurge = true }
        fixture(fault).use { f ->
            val oldListener = f.seedStoredNavigation()
            val collection = f.collection()
            val window = field(collection, "window")
            val receipt = f.withdrawAndConfirmRawReconsent()
            assertTrue(fault.purgeEntered.await(2L, TimeUnit.SECONDS))
            f.ensure()
            f.awaitDrainSubmitted(collection)
            assertSame(receipt, f.recoveryReceipt(collection))
            assertTrue(f.recoveryInFlight(collection))
            assertFalse(f.purgeFuture(collection).isDone)
            assertFalse(f.drainFuture(collection).isDone)
            f.assertNoReplacement(collection, window, oldListener)
            val replacementRuntime = FakeRuntime()
            val replacement = AndroidFeedbackActuator(runtime = replacementRuntime).also { replacementRuntime.ready() }
            f.extraActors += replacement
            f.setActor(replacement) // Actual Main first invalidation makes the pending capture stale.
            fault.releasePurge.countDown()
            f.awaitRecoveryWorker()
            assertEquals(true, f.purgeFuture(collection).get(2L, TimeUnit.SECONDS))
            assertEquals(true, f.drainFuture(collection).get(2L, TimeUnit.SECONDS))
            assertFalse(f.recoveryInFlight(collection))
            f.assertNoReplacement(collection, window, oldListener, listenerOwner = f.actor)
            assertTrue(f.recordFiles().isEmpty())
            assertFalse(f.crypto.hasKey())
            f.assertSameReceiptDoesNotRetry(collection, receipt)
            assertEquals(1, fault.purgeCalls.get())
        }
    }

    @Test fun actualRawRecoveryRejectsTheRealKeyPurgeFalseAndNeverRetriesTheSameReceipt() {
        val fault = RawRecoveryFault()
        fixture(fault).use { f ->
            val oldListener = f.seedStoredNavigation()
            val collection = f.collection()
            val window = field(collection, "window")
            f.crypto.failNextDelete()
            val receipt = f.withdrawAndConfirmRawReconsent()
            assertEquals(false, f.purgeFuture(collection).get(2L, TimeUnit.SECONDS))
            assertTrue("The actual JVM key deletion was refused", f.crypto.hasKey())
            f.ensure()
            f.awaitRecoveryWorker()
            assertEquals(true, f.drainFuture(collection).get(2L, TimeUnit.SECONDS))
            assertFalse(f.recoveryInFlight(collection))
            f.assertNoReplacement(collection, window, oldListener)
            f.assertSameReceiptDoesNotRetry(collection, receipt)
            assertEquals(1, fault.purgeCalls.get())
        }
    }

    @Test fun actualRawRecoveryConsumesTheStoresStorageThrowAsFalseWithoutExceptionalFuture() {
        val failure = IllegalStateException("test-only RAW storage boundary failure")
        val fault = RawRecoveryFault().apply { purgeFailure = failure }
        fixture(fault).use { f ->
            val oldListener = f.seedStoredNavigation()
            val collection = f.collection()
            val window = field(collection, "window")
            val receipt = f.withdrawAndConfirmRawReconsent()
            assertEquals(false, f.purgeFuture(collection).get(2L, TimeUnit.SECONDS))
            assertFalse(f.purgeFuture(collection).isCompletedExceptionally)
            assertSame(failure, fault.thrown.get())
            assertEquals(1, f.recordFiles().size)
            assertTrue(f.crypto.hasKey())
            f.ensure()
            f.awaitRecoveryWorker()
            assertEquals(true, f.drainFuture(collection).get(2L, TimeUnit.SECONDS))
            assertFalse(f.recoveryInFlight(collection))
            f.assertNoReplacement(collection, window, oldListener)
            f.assertSameReceiptDoesNotRetry(collection, receipt)
            assertEquals(1, fault.purgeCalls.get())
        }
    }

    @Test fun actualRawRecoveryRejectsTheRealDrainSubmissionFailureAfterSuccessfulPurge() {
        val fault = RawRecoveryFault()
        fixture(fault).use { f ->
            val oldListener = f.seedStoredNavigation()
            val collection = f.collection()
            val window = field(collection, "window")
            val receipt = f.withdrawAndConfirmRawReconsent()
            assertEquals(true, f.purgeFuture(collection).get(2L, TimeUnit.SECONDS))
            assertTrue(f.recordFiles().isEmpty())
            assertFalse(f.crypto.hasKey())
            // This Store belongs only to the fixture. Its actual close/drain submission rejects.
            (field(f.store, "executor") as ThreadPoolExecutor).shutdown()
            f.ensure()
            f.awaitRecoveryWorker()
            assertEquals(false, f.drainFuture(collection).get(2L, TimeUnit.SECONDS))
            assertFalse(f.recoveryInFlight(collection))
            f.assertNoReplacement(collection, window, oldListener)
            f.assertSameReceiptDoesNotRetry(collection, receipt)
            assertEquals(1, fault.purgeCalls.get())
        }
    }

    private fun fixture(fault: RawRecoveryFault? = null) = Fixture(temporary.newFolder(), fault)

    private class RawRecoveryFault {
        val purgeCalls = AtomicInteger()
        val purgeEntered = CountDownLatch(1)
        val releasePurge = CountDownLatch(1)
        val thrown = AtomicReference<RuntimeException?>()
        @Volatile var holdPurge = false
        @Volatile var purgeFailure: RuntimeException? = null
        fun purge(provider: FieldSpeechOutputStorageProvider): Boolean {
            purgeCalls.incrementAndGet()
            purgeEntered.countDown()
            if (holdPurge) check(releasePurge.await(2L, TimeUnit.SECONDS))
            purgeFailure?.let { thrown.set(it); throw it }
            return provider.purgeForRawSourceWithdrawal()
        }
    }

    private class PermissionObservedMain : MainActivity() {
        val granted = mutableSetOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO)
        var permissionReads = 0
        override fun checkSelfPermission(permission: String): Int {
            permissionReads++
            return if (permission in granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
    }

    private class Fixture(val files: File, private val fault: RawRecoveryFault? = null) : AutoCloseable {
        val main = PermissionObservedMain()
        val runtime = FakeRuntime()
        val actor = AndroidFeedbackActuator(runtime = runtime).also { runtime.ready() }
        val extraActors = mutableListOf<AndroidFeedbackActuator>()
        val crypto = TestFieldAead()
        val errorSealEntered = CountDownLatch(1)
        val releaseErrorSeal = CountDownLatch(1)
        @Volatile var blockErrorSeal = false
        val observedAead = object : LocalAead by crypto.aead {
            override fun seal(plaintext: ByteArray, associatedData: ByteArray, limits: AeadLimits): AeadSealResult {
                if (blockErrorSeal && String(plaintext, Charsets.UTF_8).contains("ENGINE_ERROR")) {
                    errorSealEntered.countDown()
                    check(releaseErrorSeal.await(2L, TimeUnit.SECONDS))
                }
                return crypto.aead.seal(plaintext, associatedData, limits)
            }
        }
        val access = SpeechStorageAccess.create(files, aead = observedAead,
            nowMillis = { 1_000L }, directorySync = { true })
        val provider = FieldSpeechOutputStorageProvider(access)
        val appended = AtomicInteger()
        val storage = object : SpeechOutputEncryptedStorage by provider {
            override fun appendEncrypted(envelope: String): Boolean = provider.appendEncrypted(envelope).also {
                if (it) appended.incrementAndGet()
            }
        }
        val adapter = SpeechOutputStoragePurgeAdapter(storage) {
            fault?.purge(provider) ?: provider.purgeForRawSourceWithdrawal()
        }
        val store = ConsentBoundSpeechOutputStore(adapter) { binding ->
            invoke("speechOutputStorageBindingIsCurrent", java.lang.Boolean.TYPE,
                arrayOf(SpeechOutputStorageBinding::class.java), binding) as Boolean
        }
        val lifecycle = WalkSessionLifecycle(sessionIdFactory = { "speech-caller-walk" },
            clock = { runtime.now }, elapsedRealtimeClock = { runtime.now })
        val consent = field(main, "integratedConsentSession") as IntegratedConsentSession
        val permissions = field(main, "permissionSessionPolicy") as PermissionSessionPolicy
        val session = GatewayFieldSession.verified("https://gateway.example.test", "speech-caller-actor",
            "walksafe_field_session=v2.test.session.cookie", Long.MAX_VALUE)
        val generation: Long
        val guard: IntegratedConsentAuthorityGuard
        init {
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            check(GatewaySessionProcessCoordinator.publishVerified(operation, session, FirstRunOnboardingPolicy.initial()))
            generation = GatewaySessionProcessCoordinator.snapshot().generation
            guard = object : IntegratedConsentAuthorityGuard {
                override fun <T : Any> withCurrent(action: () -> T): T? =
                    GatewaySessionProcessCoordinator.withCurrentGeneralSession(generation, session, action)
            }
            check(consent.applyConfirmation(confirmation(), guard) == IntegratedConsentApplyResult.APPLIED)
            permissions.authenticated(session.actorId)
            permissions.setRawCollectionConsent(true)
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            val collector = WalkSessionReadinessCollector(lifecycle.snapshot().epoch,
                WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL))
            collector.plan.requiredRequirements.forEach {
                collector.record(WalkSessionReadinessObservation(lifecycle.snapshot().epoch, it, WalkSessionReadinessStatus.READY))
            }
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(collector.build(runtime.now)))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            setField(main, "walkSessionLifecycle", lifecycle)
            setField(main, "isActivityForeground", lifecycle.snapshot().isForeground)
            setField(main, "fieldSessionLog", NoopFieldSessionLog())
            var device = PostLoginDeviceCheckPolicy.bindSession(PostLoginDeviceCheckPolicy.initial(), session.actorId, generation)
            device = PostLoginDeviceCheckPolicy.beginFromUserAction(device, session.actorId, generation,
                lifecycle.snapshot().isForeground)
            val binding = requireNotNull(device.bindingOrNull)
            device = PostLoginDeviceCheckPolicy.beginRunning(device, binding, lifecycle.snapshot().isForeground)
            val ready = PostLoginDeviceCheckSignal.READY
            device = PostLoginDeviceCheckPolicy.evaluate(device, binding, lifecycle.snapshot().isForeground,
                PostLoginDeviceCheckObservation(ready, ready, ready, ready, ready, ready, ready, ready,
                    ready, ready, ready, ready, PostLoginMetricDepthState.AVAILABLE))
            check(device.state == PostLoginDeviceCheckState.FULL)
            MainActivityControllerTestFixture.publishDeviceSnapshot(main, device)
            val collectionType = Class.forName("kr.co.hanium.dreamup.walksafe.feedback.SpeechOutputCollection")
            val constructor = collectionType.getDeclaredConstructor(File::class.java,
                FieldSpeechOutputStorageProvider::class.java, SpeechOutputStoragePurgeAdapter::class.java,
                ConsentBoundSpeechOutputStore::class.java).apply { isAccessible = true }
            val collection = constructor.newInstance(files, provider, adapter, store)
            @Suppress("UNCHECKED_CAST")
            (field(main, "speechOutputCollection") as AtomicReference<Any?>).set(collection)
            setActor(actor)
        }
        fun setActor(value: AndroidFeedbackActuator) =
            MainActivityControllerTestFixture.replaceActuator(main, value)
        fun ensure() = invoke("ensureFeedbackActuator", AndroidFeedbackActuator::class.java)
        fun collection(): Any = (field(main, "speechOutputCollection") as AtomicReference<*>).get()!!
        fun recoveryInFlight(collection: Any) = (field(collection, "recoveryInFlight") as AtomicBoolean).get()
        fun recoveryReceipt(collection: Any) = (field(collection, "recoveryReceipt") as AtomicReference<*>).get()
        @Suppress("UNCHECKED_CAST")
        fun purgeFuture(collection: Any) = (field(collection, "purgeFuture") as AtomicReference<*>).get()
            as CompletableFuture<Boolean>
        @Suppress("UNCHECKED_CAST")
        fun drainFuture(collection: Any) = (field(collection, "drainFuture") as AtomicReference<*>).get()
            as CompletableFuture<Boolean>
        fun seedStoredNavigation(): (SpeechOutputEvent) -> Unit {
            ensure()
            val listener = requireNotNull(actor.speechOutputListener)
            val id = speakNavigation()
            runtime.start(id)
            runtime.done(id)
            awaitAppendCount(3)
            assertTrue(crypto.hasKey())
            return listener
        }
        fun withdrawAndConfirmRawReconsent(): IntegratedConsentConfirmation {
            invoke("blockSpeechOutputStorageForRawWithdrawal", Void.TYPE)
            val current = requireNotNull(consent.currentConfirmationOrNull())
            val withdrawal = confirmMutation(current, granted = false,
                requestId = "speech_caller_withdrawal_0002", withdrawalItems = setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
            permissions.setRawCollectionConsent(false)
            assertNull(consent.currentConfirmationOrNull(setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION)))
            invoke("invalidateSpeechOutputStorageBinding", Void.TYPE)
            val reconsent = confirmMutation(withdrawal, granted = true,
                requestId = "speech_caller_reconsent_0003", withdrawalItems = emptySet())
            permissions.setRawCollectionConsent(true)
            assertNotSame(current, reconsent)
            assertSame(reconsent, consent.currentConfirmationOrNull(setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION)))
            return reconsent
        }
        private fun confirmMutation(previous: IntegratedConsentConfirmation, granted: Boolean,
            requestId: String, withdrawalItems: Set<IntegratedConsentItem>): IntegratedConsentConfirmation {
            val mutation = PendingIntegratedConsentMutation(previous.installationId, "c".repeat(64), requestId,
                previous.policyVersion, previous.clientRevision + 1L, previous.revision,
                previous.backendConsentReceiptSha256, previous.selections.copy(rawSourceCollection = granted),
                withdrawalItems, 1_000L)
            // Test persistence/transport input callbacks; the actual consent evaluator owns admission.
            assertEquals(IntegratedConsentSubmissionResult.APPLIED,
                consent.submitPendingMutation(mutation, persist = { true }, dispatch = { true }))
            val exact = previous.copy(requestId = requestId, clientRevision = mutation.clientRevision,
                revision = previous.revision + 1L, selections = mutation.desiredSelections)
            assertTrue(mutation.isExactNewerConfirmation(exact))
            assertEquals(IntegratedConsentApplyResult.APPLIED, consent.evaluateExactPendingConfirmation(exact))
            assertTrue(consent.applyExactPendingConfirmation(exact, guard))
            return exact
        }
        fun awaitDrainSubmitted(collection: Any) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L)
            while ((field(collection, "drainFuture") as AtomicReference<*>).get() == null &&
                System.nanoTime() < deadline) Thread.sleep(2L)
            assertNotNull((field(collection, "drainFuture") as AtomicReference<*>).get())
        }
        private fun recoveryExecutor() = invoke("getAccountDeletionCleanupExecutor", Executor::class.java) as Executor
        fun awaitRecoveryWorker() {
            val drained = CountDownLatch(1)
            recoveryExecutor().execute { drained.countDown() }
            assertTrue("The real Main serial recovery worker must finish", drained.await(2L, TimeUnit.SECONDS))
        }
        fun assertNoReplacement(collection: Any, window: Any?, listener: (SpeechOutputEvent) -> Unit,
            listenerOwner: AndroidFeedbackActuator = actor) {
            assertSame(collection, this.collection())
            assertSame(window, field(this.collection(), "window"))
            assertNull((field(main, "speechOutputStorageAuthority") as AtomicReference<*>).get())
            assertSame(listener, listenerOwner.speechOutputListener)
            val dropped = store.droppedEventCount()
            listener(SpeechOutputEvent(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.DONE, 1_027L))
            assertEquals(dropped + 1L, store.droppedEventCount())
            assertEquals(3, appended.get())
        }
        fun assertSameReceiptDoesNotRetry(collection: Any, receipt: IntegratedConsentConfirmation) {
            val gateEntered = CountDownLatch(1)
            val releaseGate = CountDownLatch(1)
            recoveryExecutor().execute { gateEntered.countDown(); check(releaseGate.await(2L, TimeUnit.SECONDS)) }
            try {
                assertTrue(gateEntered.await(2L, TimeUnit.SECONDS))
                ensure()
                ensure()
                assertSame(receipt, recoveryReceipt(collection))
                assertFalse("No same-receipt retry may queue behind the actual worker gate", recoveryInFlight(collection))
                assertSame(collection, this.collection())
            } finally { releaseGate.countDown() }
            awaitRecoveryWorker()
        }
        fun speakNavigation(): String {
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, actor.speakNavigation("fixture spoken text"))
            return runtime.spoken.last()
        }
        fun invoke(name: String, result: Class<*>, types: Array<Class<*>> = emptyArray(), vararg args: Any?): Any? =
            MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
                .findVirtual(MainActivity::class.java, name, MethodType.methodType(result, types.toList()))
                .bindTo(main).invokeWithArguments(args.toList())
        fun awaitAppendCount(expected: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L)
            while (appended.get() < expected && System.nanoTime() < deadline) Thread.sleep(2L)
            assertEquals(expected, appended.get())
        }
        fun recordFiles() = access.rootDirectory.walkTopDown().filter {
            it.isFile && it.name.startsWith("records-") && it.parentFile?.parentFile?.name == "speech-output"
        }.toList()
        fun rows(): List<JSONObject> {
            val windows = requireNotNull(FieldSpeechOutputStorageProvider.readValidWindows(
                File(access.rootDirectory, "speech-output"), crypto.aead, access.recordLimits, access.profile))
            return windows.sortedBy { it.number }.flatMap { window ->
                var ordinal = 0L
                window.directory.listFiles()!!.sortedBy(File::getName).flatMap { file -> file.readLines().map { envelope ->
                    val opened = crypto.aead.open(envelope, SpeechStorageAccess.recordAad(window.number, ordinal++),
                        access.recordLimits) as AeadOpenResult.Opened
                    try { JSONObject(String(opened.plaintext, Charsets.UTF_8)) } finally { opened.plaintext.fill(0) }
                } }
            }
        }
        override fun close() {
            releaseErrorSeal.countDown()
            fault?.releasePurge?.countDown()
            store.closeAndDrain().get(2L, TimeUnit.SECONDS)
            actor.close()
            extraActors.forEach { it.close() }
        }
    }

    private class FakeRuntime : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        private data class Task(val due: Long, val order: Long, val runnable: Runnable)
        var now = 1_000L
        val spoken = mutableListOf<String>()
        private var initialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        private val tasks = mutableListOf<Task>()
        private var sequence = 0L
        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized: Boolean get() = initialized != null
        override fun elapsedRealtime(): Long = now
        override fun initialize(onInitialized: (Int) -> Unit) { initialized = onInitialized }
        override fun setLanguage(language: Locale): Int = TextToSpeech.LANG_AVAILABLE
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
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("No progress tone in listener fixture")
        override fun post(task: Runnable) = postDelayed(task, 0L)
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean {
            tasks += Task(now + delayMs, sequence++, task)
            return true
        }
        override fun removeCallbacks(task: Runnable) { tasks.removeAll { it.runnable === task } }
        override fun removeCallbacksAndMessages(token: Any?) { tasks.clear() }
        fun ready() { requireNotNull(initialized).invoke(TextToSpeech.SUCCESS); runCurrent() }
        fun start(id: String) { requireNotNull(listener).onStart(id); runCurrent() }
        fun done(id: String) { requireNotNull(listener).onDone(id); runCurrent() }
        fun fail(id: String, code: Int) { requireNotNull(listener).onError(id, code); runCurrent() }
        private fun runCurrent() {
            while (true) {
                val next = tasks.filter { it.due <= now }.minWithOrNull(compareBy<Task> { it.due }.thenBy { it.order }) ?: break
                tasks.remove(next)
                next.runnable.run()
            }
        }
    }

    companion object {
        private fun field(owner: Any, name: String): Any? {
            return MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.get(owner)
        }
        private fun setField(owner: MainActivity, name: String, value: Any?) =
            MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.set(owner, value)
        private fun confirmation() = IntegratedConsentConfirmation(
            schemaVersion = INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,
            policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
            installationId = "501e3ad4-e74f-4433-820f-72ac2fdd42ad", requestId = "speech_caller_consent_fixture_0001",
            itemVersions = IntegratedConsentItemVersions(), clientRevision = 1L, revision = 1L,
            selections = IntegratedConsentSelections(rawSourceCollection = true), confirmedAt = "2026-07-25T12:00:00.000Z",
            gatewayAuditRecordSha256 = "9".repeat(64), backendConsentReceiptSha256 = "a".repeat(64), controlSecret = "b".repeat(64),
        )
    }
}

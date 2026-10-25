package kr.co.hanium.dreamup.walksafe.feedback

import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kr.co.hanium.dreamup.walksafe.AccountDeletionResetCoordinator
import kr.co.hanium.dreamup.walksafe.AccountDeletionResetJournal
import kr.co.hanium.dreamup.walksafe.AccountDeletionResetJournalState
import kr.co.hanium.dreamup.walksafe.AccountDeletionResetPhase
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.fieldlog.*
import kr.co.hanium.dreamup.walksafe.fieldlog.PersistentFieldSessionLog.SpeechStorageAccess
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import sun.misc.Unsafe

/** Real authority/store/provider on an isolated root; actuator is an identity input, no TTS SDK. */
internal class SpeechStorageFixture : AutoCloseable {
    val root: File = Files.createTempDirectory("speech-storage-coordinator-").toFile()
    val worker = ArrayDeque<Runnable>()
    val ui = ArrayDeque<() -> Unit>()
    val collections = mutableListOf<SpeechOutputCollection>()
    val queryEvents = mutableListOf<String>()
    var rejecting = false
    var postAccepted = true
    var destroyed = false
    var foreground = true
    var initialized = true
    var feedbackGeneration = 7
    var observed = ObservedPermissionSnapshot(true, true, true, false)
    var now = 1_000L
    var clockReads = 0
    val lifecycle = WalkSessionLifecycle(sessionIdFactory = { "speech_storage_walk" }, clock = { clockReads++; now }, elapsedRealtimeClock = { clockReads++; now })
    val session = GatewayFieldSession.verified("https://gateway.example.test", "speech-storage-actor", "walksafe_field_session=v2.test", Long.MAX_VALUE)
    val gatewayGeneration: Long
    var device: PostLoginDeviceCheckSnapshot
    val consent = IntegratedConsentSession()
    val permission = PermissionSessionPolicy(PermissionSessionSnapshot(actorId = session.actorId, authentication = AuthenticationState.ACTIVE, rawCollectionConsentGranted = true))
    val deletion = AccountDeletionStateMachine()
    var lease = deletion.attachActivity()
    var resetOwner = createResetOwner()
    var fieldParticipant: FieldSessionLog? = null
    val actuator = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null).let { it as Unsafe }
        .allocateInstance(AndroidFeedbackActuator::class.java) as AndroidFeedbackActuator
    var currentActuator: AndroidFeedbackActuator? = actuator
    val receipt = IntegratedConsentConfirmation(INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION, INTEGRATED_CONSENT_POLICY_VERSION,
        "501e3ad4-e74f-4433-820f-72ac2fdd42ad", "speech_storage_consent_fixture_0001", IntegratedConsentItemVersions(), 1L, 1L,
        IntegratedConsentSelections(rawSourceCollection = true), "2026-07-25T12:00:00.000Z", "9".repeat(64), "a".repeat(64), "b".repeat(64))
    val guard: IntegratedConsentAuthorityGuard
    init {
        GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
        val op = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
        check(GatewaySessionProcessCoordinator.publishVerified(op, session, FirstRunOnboardingPolicy.initial()))
        gatewayGeneration = GatewaySessionProcessCoordinator.snapshot().generation
        guard = object : IntegratedConsentAuthorityGuard {
            override fun <T : Any> withCurrent(action: () -> T): T? = GatewaySessionProcessCoordinator.withCurrentGeneralSession(gatewayGeneration, session, action)
        }
        check(consent.applyConfirmation(receipt, guard) == IntegratedConsentApplyResult.APPLIED)
        device = PostLoginDeviceCheckSnapshot(PostLoginDeviceCheckState.FULL, session.actorId, gatewayGeneration, 1L)
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        val collector = WalkSessionReadinessCollector(lifecycle.snapshot().epoch, WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL))
        collector.plan.requiredRequirements.forEach { collector.record(WalkSessionReadinessObservation(lifecycle.snapshot().epoch,it,WalkSessionReadinessStatus.READY)) }
        lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(collector.build(now)))
        lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
        clockReads = 0
    }
    val owner = SpeechOutputStorageCoordinator({ consent }, { permission }, { lifecycle }, { deletion },
        { queryEvents += "reset-owner"; resetOwner }, { queryEvents += "activity-lease"; lease },
        { Executor { if (rejecting) throw RejectedExecutionException(); worker.addLast(it) } },
        SpeechStorageCallbacks { if (postAccepted) { ui.addLast(it); true } else false },
        object : SpeechStorageQueries {
            override fun startupDestroyed() = destroyed
            override fun foreground() = foreground
            override fun lifecycleInitialized() = initialized
            override fun currentActuator() = currentActuator
            override fun feedbackGeneration() = feedbackGeneration
            override fun observedPermissions(): ObservedPermissionSnapshot { queryEvents += "observed"; return observed }
            override fun deviceCheck() = device
            override fun fieldParticipantOrNull(): FieldSessionLog? { queryEvents += "field-participant"; return fieldParticipant }
            override fun filesDirectory() = root
            override fun accountBlockedAtStartup() = deletion.processingBlocked()
            override fun rawBlockedAtStartup() = false
            override fun requiredObservedPermissions() = WalkStartPermissionPolicy.required(activityRecognitionRequired = false)
        }, SpeechStorageCollectionFactory { files, accountBlocked, rawBlocked, currentness ->
            val crypto = TestFieldAead()
            val access = SpeechStorageAccess.create(files, accountBlocked, rawBlocked, crypto.aead, { now }, { true })
            val provider = FieldSpeechOutputStorageProvider(access)
            val storage = SpeechOutputStoragePurgeAdapter(provider, provider::purgeForRawSourceWithdrawal)
            SpeechOutputCollection(files, provider, storage, ConsentBoundSpeechOutputStore(storage, currentness)).also {
                it.retired.set(accountBlocked || rawBlocked); collections += it
            }
        })
    fun initialize(): SpeechOutputCollection = owner.collectionOnWorker(root, false, false)
    fun runWorker() = worker.removeFirst().run()
    fun runUi() = ui.removeFirst().invoke()
    fun confirmNext(): IntegratedConsentConfirmation = receipt.copy(revision = 2L).also {
        assertEquals(IntegratedConsentApplyResult.APPLIED, consent.applyConfirmation(it, guard))
    }
    fun createResetOwner() = AccountDeletionResetCoordinator(
        object : AccountDeletionResetJournal {
            override fun read() = AccountDeletionResetJournalState.Absent
            override fun write(phase: AccountDeletionResetPhase): Boolean = error("No durable reset mutation in this fixture")
            override fun clear(): Boolean = error("No durable reset mutation in this fixture")
        }, { error("No legacy reset mutation") }, { error("No Gateway reset mutation") },
        { error("No sensitive reset mutation") }, { error("No plain reset mutation") },
        { error("No field reset coordinator mutation") }, { error("No deletion fence mutation") },
    )
    override fun close() {
        collections.forEach { check(it.store.closeAndDrain().get()) }
        GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
        check(root.deleteRecursively())
    }
}

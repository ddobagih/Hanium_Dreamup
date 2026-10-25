package kr.co.hanium.dreamup.walksafe.feedback

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.AccountDeletionResetCoordinator
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckSnapshot
import kr.co.hanium.dreamup.walksafe.fieldlog.FieldSessionLog
import kr.co.hanium.dreamup.walksafe.fieldlog.PersistentFieldSessionLog
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*

/** Owns exact collection/storage admission and account/RAW recovery; never owns a speech engine. */
internal class SpeechOutputStorageCoordinator(
    private val consentSession: () -> IntegratedConsentSession,
    private val permissionPolicy: () -> PermissionSessionPolicy,
    private val lifecycle: () -> WalkSessionLifecycle,
    private val deletionStateMachine: () -> AccountDeletionStateMachine,
    private val resetCoordinator: () -> AccountDeletionResetCoordinator,
    private val activityLease: () -> AccountDeletionActivityLease,
    private val cleanupExecutor: () -> Executor,
    private val callbacks: SpeechStorageCallbacks,
    private val queries: SpeechStorageQueries,
    private val collectionFactory: SpeechStorageCollectionFactory = DefaultSpeechStorageCollectionFactory,
) {
    // Normal epoch/actor changes reuse this unblocked collection. A purged provider is never
    // reopened; verified RAW/account recovery must create a new provider and collection window.
    private val speechOutputCollection = AtomicReference<SpeechOutputCollection?>()
    private val speechOutputCollectionCreationLock = Any()
    private val speechOutputAccountPurgeVerified = AtomicBoolean()
    private val speechOutputResetParticipant = AtomicReference<SpeechOutputAccountCleanup?>()
    private val speechOutputStorageAuthority = AtomicReference<SpeechOutputStorageAuthority?>()
    private val speechOutputAdmissionToken = AtomicReference(Any())
    private val speechOutputAccountDeletionIntent = AtomicBoolean()
    private val speechOutputAccountDeletionToken = AtomicReference(Any())
    fun collectionOrNull(): SpeechOutputCollection? = speechOutputCollection.get()
    fun authorityOrNull(): SpeechOutputStorageAuthority? = speechOutputStorageAuthority.get()
    fun accountDeletionIntentToken(): Any = speechOutputAccountDeletionToken.get()
    fun initializeOnPrivacyWorker() {
        if (speechOutputCollection.get() != null || queries.startupDestroyed()) return
        collectionOnWorker(
            queries.filesDirectory(),
            queries.accountBlockedAtStartup(),
            queries.rawBlockedAtStartup(),
        )
    }

    fun collectionOnWorker(
        filesDirectory: File,
        accountBlocked: Boolean,
        rawBlocked: Boolean,
    ): SpeechOutputCollection = synchronized(speechOutputCollectionCreationLock) {
        speechOutputCollection.get()?.let { return@synchronized it }
        // The root/flags were captured on the existing worker before this memory-only lock.
        // No provider getter is evaluated by construction or interface delegation.
        val collection = createCollection(filesDirectory, accountBlocked, rawBlocked)
        val provider = collection.provider
        val storage = collection.storage
        val store = collection.store
        speechOutputCollection.set(collection)
        if (speechOutputAccountDeletionIntent.get()) {
            collection.retired.set(true)
            provider.blockNewProcessingForAccountDeletion()
            storage.markAccountDeletion()
            collection.purgeFuture.set(store.invalidateAndPurge())
        }
        if (queries.startupDestroyed()) {
            collection.retired.set(true)
            collection.drainFuture.set(store.closeAndDrain())
        }
        collection
    }

    fun createCollection(
        filesDirectory: File,
        accountBlocked: Boolean = false,
        rawBlocked: Boolean = false,
    ): SpeechOutputCollection = collectionFactory.create(filesDirectory, accountBlocked, rawBlocked, this::bindingIsCurrent)

    fun prepareAfterVerifiedAccountReset(): SpeechOutputCollection? {
        val preparation = speechOutputResetParticipant.get() ?: return null
        if (!speechOutputAccountPurgeVerified.get() || !resetPreparationIsCurrent(preparation) ||
            deletionStateMachine().processingBlocked()
        ) return null
        // The reset coordinator already consumed the one exact verified field/key participant.
        // Construct a new unblocked provider/window; never reset/reopen the retired provider.
        return createCollection(preparation.collection.filesDirectory)
    }

    fun publishAfterVerifiedAccountReset(
        replacement: SpeechOutputCollection,
    ): Boolean {
        val installed = synchronized(speechOutputCollectionCreationLock) {
            val preparation = speechOutputResetParticipant.get()
            if (preparation == null || !speechOutputAccountPurgeVerified.get() ||
                !resetPreparationIsCurrent(preparation) || queries.startupDestroyed() ||
                deletionStateMachine().processingBlocked()
            ) false else {
                invalidateBinding()
                if (!speechOutputCollection.compareAndSet(preparation.collection, replacement)) false else {
                    speechOutputAccountDeletionIntent.set(false)
                    speechOutputAccountDeletionToken.set(Any())
                    speechOutputResetParticipant.set(null)
                    speechOutputAccountPurgeVerified.set(false)
                    true
                }
            }
        }
        if (!installed) replacement.store.closeAndDrain()
        return installed
    }

    fun awaitCleanup(
        purge: CompletableFuture<Boolean>?,
        drain: CompletableFuture<Boolean>,
        requiresPurge: Boolean,
    ): Boolean {
        // Called only on existing cleanup workers, outside Main/privacy/reset PROCESS_LOCKs.
        try {
            val purgeSucceeded = if (requiresPurge) purge?.get() == true else true
            val drained = drain.get() == true
            return purgeSucceeded && drained
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } catch (_: Exception) {
            return false
        }
    }

    fun prepareAccountPurgeOnWorker(): SpeechOutputAccountCleanup {
        blockForAccountDeletion()
        val collection = speechOutputCollection.get() ?: collectionOnWorker(
            queries.filesDirectory(), accountBlocked = true, rawBlocked = false,
        )
        collection.provider.blockNewProcessingForAccountDeletion()
        collection.storage.markAccountDeletion()
        val purge = collection.purgeFuture.get() ?: collection.store.invalidateAndPurge().also {
            collection.purgeFuture.compareAndSet(null, it)
        }
        val drain = collection.store.closeAndDrain().also { collection.drainFuture.compareAndSet(null, it) }
        return SpeechOutputAccountCleanup(collection, purge, drain,
            queries.fieldParticipantOrNull(),
            resetCoordinator(), activityLease(), speechOutputAccountDeletionToken.get())
    }

    fun resetPreparationIsCurrent(preparation: SpeechOutputAccountCleanup): Boolean =
        speechOutputCollection.get() === preparation.collection &&
            resetCoordinator() === preparation.coordinator &&
            deletionStateMachine().activityLeaseIsCurrent(preparation.activityLease) &&
            preparation.activityLease === activityLease() &&
            preparation.accountIntentToken === speechOutputAccountDeletionToken.get() &&
            preparation.fieldParticipant === (queries.fieldParticipantOrNull())

    fun awaitPreparedAccountPurge(preparation: SpeechOutputAccountCleanup): Boolean {
        val result = awaitCleanup(preparation.purge, preparation.drain, requiresPurge = true)
        speechOutputAccountPurgeVerified.set(result)
        speechOutputResetParticipant.set(if (result) preparation else null)
        return result
    }

    fun awaitAccountPurgeOnWorker(): Boolean =
        awaitPreparedAccountPurge(prepareAccountPurgeOnWorker())

    fun resetFieldStorageOnWorker(): Boolean {
        val preparation = speechOutputResetParticipant.get() ?: return false
        if (!speechOutputAccountPurgeVerified.get() ||
            !resetPreparationIsCurrent(preparation)
        ) return false
        // There is one verified root/marker. Consume its fresh-key reset exactly once through
        // the captured real DEBUG owner, or the release/uninitialized provider participant.
        val fieldParticipant = preparation.fieldParticipant
        return if (fieldParticipant is PersistentFieldSessionLog) {
            fieldParticipant.purgeAll() && fieldParticipant.resetForNewEnrollment()
        } else {
            preparation.collection.provider.purgeAll() && preparation.collection.provider.resetForNewEnrollment()
        }
    }

    /** Only Atomics and the store's memory fence. Safe from reducer/authority callbacks. */
    fun invalidateBinding() {
        speechOutputAdmissionToken.set(Any())
        val previous = speechOutputStorageAuthority.getAndSet(null) ?: return
        previous.binding.invalidate()
        speechOutputCollection.get()?.store?.invalidate(previous.binding)
    }

    fun invalidatePermissionActorIfChanged(actorId: String) {
        val captured = speechOutputStorageAuthority.get()?.permissions?.session ?: return
        if (captured.actorId != actorId.trim() || captured.authentication != AuthenticationState.ACTIVE) {
            invalidateBinding()
        }
    }

    fun blockForAccountDeletion() {
        synchronized(speechOutputCollectionCreationLock) {
            speechOutputAccountDeletionIntent.set(true)
            speechOutputAccountDeletionToken.set(Any())
        }
        invalidateBinding()
        speechOutputCollection.get()?.let { collection ->
            collection.retired.set(true)
            collection.provider.blockNewProcessingForAccountDeletion()
            collection.storage.markAccountDeletion()
            collection.purgeFuture.compareAndSet(null, collection.store.invalidateAndPurge())
        }
    }

    fun blockForRawWithdrawal() {
        retireCollection()
        speechOutputCollection.get()?.let { collection ->
            collection.storage.markRawWithdrawal()
            collection.purgeFuture.compareAndSet(null, collection.store.invalidateAndPurge())
        }
    }

    fun retireCollection() {
        speechOutputCollection.get()?.retired?.set(true)
        invalidateBinding()
        // Normal actor/epoch retirement does not purge. The close owner submits/joins drain
        // separately; RAW/account callers must submit their purge before requesting close.
    }

    fun bindingIsCurrent(binding: SpeechOutputStorageBinding): Boolean {
        val authority = speechOutputStorageAuthority.get() ?: return false
        val collection = speechOutputCollection.get() ?: return false
        if (authority.binding !== binding || collection.retired.get() ||
            speechOutputAccountDeletionIntent.get() || collection.window !== binding.window ||
            speechOutputAdmissionToken.get() !== authority.admissionToken
        ) return false
        // Every referenced owner exposes a short thread-safe read. No provider getter or disk I/O.
        return authority.privacyIsCurrent() && speechOutputStorageAuthority.get() === authority &&
            speechOutputAdmissionToken.get() === authority.admissionToken && !collection.retired.get() &&
            !speechOutputAccountDeletionIntent.get()
    }

    fun refreshBinding(actuator: AndroidFeedbackActuator) {
        val collection = speechOutputCollection.get() ?: return
        if (speechOutputAccountDeletionIntent.get() ||
            queries.startupDestroyed() || deletionStateMachine().processingBlocked() ||
            !queries.foreground() || !queries.lifecycleInitialized() || queries.currentActuator() !== actuator
        ) return
        val token = speechOutputAdmissionToken.get()
        val receipt = consentSession().currentConfirmationOrNull(
            setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        ) ?: return
        val gateway = GatewaySessionProcessCoordinator.snapshot()
        val session = gateway.session ?: return
        val walk = lifecycle().snapshot()
        val permissionOwner = permissionPolicy()
        val permissions = permissionOwner.snapshot()
        val observed = queries.observedPermissions()
        val device = queries.deviceCheck()
        if (!captureAllowed(walk, gateway, device, permissions, observed)) return
        val collectionAccount = collection.accountActorId.get()
        if (!collection.retired.get() && collectionAccount != null && collectionAccount != session.actorId) {
            // Account identity is a collection boundary, unlike a READY engine/readiness refresh.
            collection.retired.set(true)
            invalidateBinding()
            // Capture the fresh token only on the next real publication request.
            return
        }
        if (collectionAccount == null &&
            !collection.accountActorId.compareAndSet(null, session.actorId) &&
            collection.accountActorId.get() != session.actorId
        ) return
        val previous = speechOutputStorageAuthority.get()
        if (previous != null && previous.admissionToken === token &&
            previous.binding.actor === actuator && previous.feedbackGeneration == queries.feedbackGeneration() &&
            previous.binding.rawConfirmation === receipt && previous.binding.walkEpoch == walk.epoch &&
            previous.binding.gatewaySession === session && previous.binding.gatewayGeneration == gateway.generation &&
            previous.rawPermissionSessionMatches(permissions) && previous.permissions.observed == observed &&
            previous.permissions.deviceCheck == device &&
            bindingIsCurrent(previous.binding)
        ) return
        val binding = SpeechOutputStorageBinding(
            rawConfirmation = receipt,
            permissionAuthority = SpeechOutputPermissionAuthority(permissions, observed, device),
            walkEpoch = walk.epoch,
            gatewaySession = session,
            gatewayGeneration = gateway.generation,
            actor = actuator,
            feedbackLifetime = SpeechOutputFeedbackLifetime(queries.feedbackGeneration()),
            source = actuator,
            window = collection.window,
        )
        // The OS snapshot was captured above on publication. Worker currentness is memory-only;
        // permission/lifecycle callbacks fence it before the next actual observation is published.
        val authority = SpeechOutputStorageAuthority(
            binding, token, binding.permissionAuthority as SpeechOutputPermissionAuthority,
            queries.feedbackGeneration(), consentSession(), permissionOwner, lifecycle(),
            deletionStateMachine(),
        )
        if (collection.retired.get()) {
            recoverOnWorker(collection, authority, actuator)
            return
        }
        if (speechOutputAdmissionToken.get() !== token ||
            !speechOutputStorageAuthority.compareAndSet(previous, authority)
        ) { binding.invalidate(); return }
        previous?.let { collection.store.invalidate(it.binding) }
        if (!collection.store.publish(binding) || !bindingIsCurrent(binding)) {
            collection.store.invalidate(binding)
            speechOutputStorageAuthority.compareAndSet(authority, null)
            return
        }
        // The callback captures exactly this binding/store; an old actor never looks up a new owner.
        actuator.speechOutputListener = { event -> collection.store.enqueue(binding, event) }
    }

    fun recoverOnWorker(
        previousCollection: SpeechOutputCollection,
        capture: SpeechOutputStorageAuthority,
        actuator: AndroidFeedbackActuator,
    ) {
        val receipt = capture.binding.rawConfirmation as? IntegratedConsentConfirmation ?: return
        // Failure stays blocked for this confirmation. A later exact server confirmation may
        // attempt its own recovery; engine READY/polls never retry the same failed receipt.
        if (previousCollection.recoveryReceipt.get() === receipt ||
            !previousCollection.recoveryInFlight.compareAndSet(false, true)
        ) return
        previousCollection.recoveryReceipt.set(receipt)
        val deletionOwner = deletionStateMachine()
        val fieldParticipant = queries.fieldParticipantOrNull()
        try {
            cleanupExecutor().execute {
                var replacement: SpeechOutputCollection? = null
                try {
                    val purge = previousCollection.purgeFuture.get()
                    val drain = previousCollection.store.closeAndDrain()
                    previousCollection.drainFuture.compareAndSet(null, drain)
                    if (!awaitCleanup(purge, drain, requiresPurge = purge != null) ||
                        speechOutputAdmissionToken.get() !== capture.admissionToken ||
                        speechOutputAccountDeletionIntent.get() || !capture.privacyIsCurrent() ||
                        !previousCollection.provider.resetAfterConfirmedRawConsent {
                            speechOutputAccountDeletionIntent.get() || deletionOwner.processingBlocked()
                        }
                    ) return@execute
                    // Preserve the legacy DEBUG owner reset, after the shared root/key is safe.
                    fieldParticipant?.resetRawSourceAfterConfirmedConsent()
                    replacement = createCollection(previousCollection.filesDirectory)
                    val prepared = requireNotNull(replacement)
                    if (!callbacks.post {
                            if (speechOutputCollection.get() !== previousCollection ||
                                speechOutputAdmissionToken.get() !== capture.admissionToken ||
                                speechOutputAccountDeletionIntent.get() || !capture.privacyIsCurrent() ||
                                queries.startupDestroyed() || queries.currentActuator() !== actuator ||
                                queries.feedbackGeneration() != capture.feedbackGeneration ||
                                queries.deviceCheck() != capture.permissions.deviceCheck
                            ) {
                                prepared.store.closeAndDrain()
                                return@post
                            }
                            invalidateBinding()
                            if (!speechOutputCollection.compareAndSet(previousCollection, prepared)) {
                                prepared.store.closeAndDrain()
                                return@post
                            }
                            refreshBinding(actuator)
                        }
                    ) prepared.store.closeAndDrain()
                } catch (_: Exception) {
                    replacement?.store?.closeAndDrain()
                } finally {
                    previousCollection.recoveryInFlight.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            previousCollection.recoveryInFlight.set(false)
        }
    }

    /** Same admission predicates as the real capture; no engine readiness or storage success input. */
    fun captureAllowed(
        walk: WalkSessionSnapshot,
        gateway: GatewaySessionProcessSnapshot,
        device: PostLoginDeviceCheckSnapshot,
        permissions: PermissionSessionSnapshot,
        observed: ObservedPermissionSnapshot,
    ): Boolean {
        val session = gateway.session ?: return false
        val deviceBinding = device.bindingOrNull ?: return false
        return walk.state == WalkSessionState.ACTIVE && walk.isForeground &&
            device.passesFeatureGate && deviceBinding.actorId == session.actorId &&
            deviceBinding.sessionGeneration == gateway.generation &&
            permissions.authentication == AuthenticationState.ACTIVE &&
            permissions.actorId == session.actorId && permissions.rawCollectionConsentGranted &&
            queries.requiredObservedPermissions().all(observed::isGranted) &&
            GatewaySessionProcessCoordinator.isCurrentGeneralSession(gateway.generation, session)
    }
}

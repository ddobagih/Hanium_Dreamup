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

internal class SpeechOutputCollection(
    val filesDirectory: File,
    val provider: FieldSpeechOutputStorageProvider,
    val storage: SpeechOutputStoragePurgeAdapter,
    val store: ConsentBoundSpeechOutputStore,
) {
    val window = Any()
    val accountActorId = AtomicReference<String?>()
    val retired = AtomicBoolean()
    val purgeFuture = AtomicReference<CompletableFuture<Boolean>?>()
    val drainFuture = AtomicReference<CompletableFuture<Boolean>?>()
    val recoveryInFlight = AtomicBoolean()
    val recoveryReceipt = AtomicReference<IntegratedConsentConfirmation?>()
}

/** One concrete reset preparation; never an I/O callback under a Main/privacy lock. */
internal class SpeechOutputAccountCleanup(
    val collection: SpeechOutputCollection,
    val purge: CompletableFuture<Boolean>,
    val drain: CompletableFuture<Boolean>,
    val fieldParticipant: FieldSessionLog?,
    val coordinator: AccountDeletionResetCoordinator,
    val activityLease: AccountDeletionActivityLease,
    val accountIntentToken: Any,
)

internal fun interface SpeechStorageCollectionFactory {
    fun create(filesDirectory: File, accountBlocked: Boolean, rawBlocked: Boolean,
        currentness: (SpeechOutputStorageBinding) -> Boolean): SpeechOutputCollection
}
internal object DefaultSpeechStorageCollectionFactory : SpeechStorageCollectionFactory {
    override fun create(filesDirectory: File, accountBlocked: Boolean, rawBlocked: Boolean,
        currentness: (SpeechOutputStorageBinding) -> Boolean): SpeechOutputCollection {
        val provider = FieldSpeechOutputStorageProvider.create(filesDirectory, accountBlocked, rawBlocked)
        val storage = SpeechOutputStoragePurgeAdapter(provider, provider::purgeForRawSourceWithdrawal)
        return SpeechOutputCollection(filesDirectory, provider, storage, ConsentBoundSpeechOutputStore(storage, currentness)).also {
            it.retired.set(accountBlocked || rawBlocked)
        }
    }
}

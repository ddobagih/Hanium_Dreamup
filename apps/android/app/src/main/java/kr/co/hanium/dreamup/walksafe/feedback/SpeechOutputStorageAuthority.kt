package kr.co.hanium.dreamup.walksafe.feedback

import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckSnapshot
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionStateMachine
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentItem
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentSession
import kr.co.hanium.dreamup.walksafe.session.ObservedPermissionSnapshot
import kr.co.hanium.dreamup.walksafe.session.PermissionSessionPolicy
import kr.co.hanium.dreamup.walksafe.session.PermissionSessionSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkSessionLifecycle
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState

/** Only the actual Main purge caller chooses a reason; account intent can never be downgraded. */
internal class SpeechOutputStoragePurgeAdapter(
    private val storage: SpeechOutputEncryptedStorage,
    private val purgeForRawSourceWithdrawal: () -> Boolean,
) : SpeechOutputEncryptedStorage by storage {
    private enum class Reason { RAW_WITHDRAWAL, ACCOUNT_DELETION }
    private val reason = AtomicReference<Reason?>()
    fun markRawWithdrawal() { reason.compareAndSet(null, Reason.RAW_WITHDRAWAL) }
    fun markAccountDeletion() { reason.set(Reason.ACCOUNT_DELETION) }
    override fun purgeAll(): Boolean = when (reason.get()) {
        Reason.RAW_WITHDRAWAL -> purgeForRawSourceWithdrawal()
        Reason.ACCOUNT_DELETION -> storage.purgeAll()
        null -> false
    }
}

internal data class SpeechOutputPermissionAuthority(
    val session: PermissionSessionSnapshot,
    val observed: ObservedPermissionSnapshot,
    val deviceCheck: PostLoginDeviceCheckSnapshot,
)

internal data class SpeechOutputFeedbackLifetime(val generation: Int)

/** Captured owners only. Worker checks never traverse Main's mutable graph or B collectors. */
internal class SpeechOutputStorageAuthority(
    val binding: SpeechOutputStorageBinding,
    val admissionToken: Any,
    val permissions: SpeechOutputPermissionAuthority,
    val feedbackGeneration: Int,
    private val consentSession: IntegratedConsentSession,
    private val permissionPolicy: PermissionSessionPolicy,
    private val lifecycle: WalkSessionLifecycle,
    private val deletionStateMachine: AccountDeletionStateMachine,
) {
    fun rawPermissionSessionMatches(current: PermissionSessionSnapshot): Boolean =
        current.actorId == permissions.session.actorId &&
            current.authentication == permissions.session.authentication &&
            current.rawCollectionConsentGranted == permissions.session.rawCollectionConsentGranted

    fun privacyIsCurrent(): Boolean {
        if (binding.isInvalidated || deletionStateMachine.processingBlocked()) return false
        if (consentSession.currentConfirmationOrNull(
                setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
            ) !== binding.rawConfirmation ||
            !rawPermissionSessionMatches(permissionPolicy.snapshot())
        ) return false
        val walk = lifecycle.snapshot()
        if (walk.state != WalkSessionState.ACTIVE || !walk.isForeground ||
            walk.epoch != binding.walkEpoch
        ) return false
        val gateway = binding.gatewaySession as? GatewayFieldSession ?: return false
        return GatewaySessionProcessCoordinator.isCurrentGeneralSession(
            binding.gatewayGeneration, gateway,
        ) && !binding.isInvalidated
    }
}

package kr.co.hanium.dreamup.walksafe.session

internal enum class AccountDeletionAggregateAuthorityState {
    ABSENT,
    CONFIRMED,
    FAIL_CLOSED,
    UNAVAILABLE,
}

internal data class AccountDeletionAuthoritySnapshot(
    val preferenceState: AccountDeletionIntentFenceState,
    val fileState: AccountDeletionIntentAuthorityState,
    val aggregateState: AccountDeletionAggregateAuthorityState,
) {
    val blocksPrivacy: Boolean
        get() = aggregateState != AccountDeletionAggregateAuthorityState.ABSENT
}

internal enum class AccountDeletionConfirmationDecision {
    ACCEPTED,
    NOT_ACCEPTED,
}

internal data class AccountDeletionConfirmationResult(
    val decision: AccountDeletionConfirmationDecision,
    val preferenceWriteVerified: Boolean,
    val fileWriteVerified: Boolean,
    val snapshot: AccountDeletionAuthoritySnapshot,
)

internal data class AccountDeletionFailClosedResult(
    val durableSuccess: Boolean,
    val preferenceWriteVerified: Boolean,
    val fileWriteVerified: Boolean,
    val snapshot: AccountDeletionAuthoritySnapshot,
)

internal data class AccountDeletionAuthorityClearResult(
    val cleared: Boolean,
    val preferenceClearVerified: Boolean,
    val fileClearVerified: Boolean,
    val snapshot: AccountDeletionAuthoritySnapshot,
)

internal data class AccountDeletionStartupAuthorityDecision(
    val blocksPrivacy: Boolean,
    val failClosedReason: String?,
    val durableConfirmationRecoveryRequired: Boolean,
)

/** Resolves startup only after any eligible legacy journal migration has been attempted. */
internal fun accountDeletionStartupAuthorityDecision(
    snapshot: AccountDeletionAuthoritySnapshot,
    journalPresent: Boolean,
): AccountDeletionStartupAuthorityDecision = when {
    snapshot.fileState is AccountDeletionIntentAuthorityState.FailClosed ->
        AccountDeletionStartupAuthorityDecision(
            blocksPrivacy = true,
            failClosedReason = snapshot.fileState.reason,
            durableConfirmationRecoveryRequired = false,
        )
    snapshot.fileState == AccountDeletionIntentAuthorityState.Confirmed ->
        AccountDeletionStartupAuthorityDecision(
            blocksPrivacy = true,
            failClosedReason = null,
            durableConfirmationRecoveryRequired = !journalPresent,
        )
    snapshot.aggregateState == AccountDeletionAggregateAuthorityState.UNAVAILABLE ->
        AccountDeletionStartupAuthorityDecision(
            blocksPrivacy = true,
            failClosedReason = "deletion_intent_authority_unavailable",
            durableConfirmationRecoveryRequired = false,
        )
    snapshot.aggregateState == AccountDeletionAggregateAuthorityState.CONFIRMED ->
        AccountDeletionStartupAuthorityDecision(
            blocksPrivacy = true,
            failClosedReason = if (journalPresent) {
                null
            } else {
                "deletion_intent_recovery_required"
            },
            durableConfirmationRecoveryRequired = false,
        )
    journalPresent ->
        AccountDeletionStartupAuthorityDecision(
            blocksPrivacy = true,
            failClosedReason = "deletion_intent_authority_migration_failed",
            durableConfirmationRecoveryRequired = false,
        )
    else ->
        AccountDeletionStartupAuthorityDecision(
            blocksPrivacy = false,
            failClosedReason = null,
            durableConfirmationRecoveryRequired = false,
        )
}

/** Coordinates the preference fence and no-backup file without short-circuiting either write. */
internal class AccountDeletionDualAuthority(
    private val preferenceFence: AccountDeletionIntentFence,
    private val fileAuthority: AccountDeletionIntentAuthority,
) {
    fun confirm(): AccountDeletionConfirmationResult {
        val fileVerified = runCatching {
            fileAuthority.confirm()
        }.getOrDefault(false)
        val preferenceVerified = runCatching {
            preferenceFence.engage()
        }.getOrDefault(false)
        val snapshot = startupState()
        return AccountDeletionConfirmationResult(
            decision = if (fileVerified) {
                AccountDeletionConfirmationDecision.ACCEPTED
            } else {
                AccountDeletionConfirmationDecision.NOT_ACCEPTED
            },
            preferenceWriteVerified = preferenceVerified,
            fileWriteVerified = fileVerified,
            snapshot = snapshot,
        )
    }

    fun failClosed(reason: String): AccountDeletionFailClosedResult {
        val fileVerified = runCatching {
            fileAuthority.failClosed(reason)
        }.getOrDefault(false)
        val preferenceVerified = runCatching {
            preferenceFence.engage()
        }.getOrDefault(false)
        val snapshot = startupState()
        val durableSuccess =
            fileVerified &&
                snapshot.fileState is AccountDeletionIntentAuthorityState.FailClosed
        return AccountDeletionFailClosedResult(
            durableSuccess = durableSuccess,
            preferenceWriteVerified = preferenceVerified,
            fileWriteVerified = fileVerified,
            snapshot = snapshot,
        )
    }

    fun clear(): AccountDeletionAuthorityClearResult {
        val preferenceVerified = runCatching {
            preferenceFence.clear()
        }.getOrDefault(false)
        val fileVerified = runCatching {
            fileAuthority.clear()
        }.getOrDefault(false)
        val snapshot = startupState()
        val cleared =
            preferenceVerified &&
                fileVerified &&
                snapshot.preferenceState == AccountDeletionIntentFenceState.ABSENT &&
                snapshot.fileState == AccountDeletionIntentAuthorityState.Absent
        return AccountDeletionAuthorityClearResult(
            cleared = cleared,
            preferenceClearVerified = preferenceVerified,
            fileClearVerified = fileVerified,
            snapshot = snapshot,
        )
    }

    fun startupState(): AccountDeletionAuthoritySnapshot {
        val preferenceState = runCatching {
            preferenceFence.state()
        }.getOrDefault(AccountDeletionIntentFenceState.UNAVAILABLE)
        val fileState = runCatching {
            fileAuthority.read()
        }.getOrDefault(AccountDeletionIntentAuthorityState.Unavailable)
        val aggregateState = when {
            fileState is AccountDeletionIntentAuthorityState.FailClosed ->
                AccountDeletionAggregateAuthorityState.FAIL_CLOSED
            fileState == AccountDeletionIntentAuthorityState.Confirmed ->
                AccountDeletionAggregateAuthorityState.CONFIRMED
            fileState == AccountDeletionIntentAuthorityState.Corrupt ||
                fileState == AccountDeletionIntentAuthorityState.Unavailable ->
                AccountDeletionAggregateAuthorityState.UNAVAILABLE
            preferenceState == AccountDeletionIntentFenceState.UNAVAILABLE ->
                AccountDeletionAggregateAuthorityState.UNAVAILABLE
            preferenceState == AccountDeletionIntentFenceState.PRESENT ->
                AccountDeletionAggregateAuthorityState.CONFIRMED
            else -> AccountDeletionAggregateAuthorityState.ABSENT
        }
        return AccountDeletionAuthoritySnapshot(
            preferenceState = preferenceState,
            fileState = fileState,
            aggregateState = aggregateState,
        )
    }
}

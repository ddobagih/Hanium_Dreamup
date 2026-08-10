package kr.co.hanium.dreamup.walksafe.session

internal enum class AccountDeletionIntentFenceState {
    ABSENT,
    PRESENT,
    UNAVAILABLE,
}

/**
 * Identity-free durable signal that account-deletion confirmation has already taken effect.
 *
 * [read] returns `false` only for a verified absence, `true` only for the exact stored fence, and
 * `null` for corrupt or unavailable storage. [store] must synchronously commit that exact fence;
 * the clear callback must synchronously remove it. Both mutations require exact readback.
 */
internal class AccountDeletionIntentFence(
    private val read: () -> Boolean?,
    private val store: () -> Boolean,
    clear: () -> Boolean = { false },
) {
    private val clearStoredFence = clear

    fun state(): AccountDeletionIntentFenceState =
        when (runCatching(read).getOrNull()) {
            false -> AccountDeletionIntentFenceState.ABSENT
            true -> AccountDeletionIntentFenceState.PRESENT
            null -> AccountDeletionIntentFenceState.UNAVAILABLE
        }

    fun engage(): Boolean =
        runCatching(store).getOrDefault(false) &&
            state() == AccountDeletionIntentFenceState.PRESENT

    fun clear(): Boolean =
        runCatching(clearStoredFence).getOrDefault(false) &&
            state() == AccountDeletionIntentFenceState.ABSENT
}

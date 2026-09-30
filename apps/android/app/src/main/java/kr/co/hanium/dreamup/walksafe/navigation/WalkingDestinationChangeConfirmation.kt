package kr.co.hanium.dreamup.walksafe.navigation

/**
 * S06-40 (FP-026-DETAIL-01 '이 장소 선택' and '길안내 시작' are separate commands; DEC-VOICE-CONFIRMATION):
 * while walking, a spoken candidate number only selects the place. The route is requested only when a
 * separate '길안내 시작' consumes that same selection for the same owner (actor, session, walk) within
 * [timeoutMs]. Any other outcome keeps the current route untouched. Main-thread owned.
 */
class WalkingDestinationChangeConfirmation<O>(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    init {
        require(timeoutMs > 0L)
    }

    private data class Pending<O>(
        val destination: DestinationSearchResult,
        val owner: O,
        val offeredAtElapsedRealtimeMs: Long,
    )

    private var pending: Pending<O>? = null

    /** A newer selection replaces the older one; only one place can be waiting for '길안내 시작'. */
    fun offer(destination: DestinationSearchResult, owner: O, nowElapsedRealtimeMs: Long) {
        pending = Pending(destination, owner, nowElapsedRealtimeMs)
    }

    fun hasPending(): Boolean = pending != null

    /**
     * Read-only view for repeating the confirmation prompt (S06-40 x S05-11): the same owner and
     * window rules as [consume], but the selection stays pending for '길안내 시작'.
     */
    fun peek(owner: O, nowElapsedRealtimeMs: Long): DestinationSearchResult? {
        val current = pending ?: return null
        if (current.owner != owner) return null
        val age = nowElapsedRealtimeMs - current.offeredAtElapsedRealtimeMs
        if (age < 0L || age >= timeoutMs) return null
        return current.destination
    }

    /**
     * One-shot: the selection is removed on every call. Returns it only for the same owner, before the
     * timeout, and never across a clock that went backwards.
     */
    fun consume(owner: O, nowElapsedRealtimeMs: Long): DestinationSearchResult? {
        val current = pending ?: return null
        pending = null
        if (current.owner != owner) return null
        val age = nowElapsedRealtimeMs - current.offeredAtElapsedRealtimeMs
        if (age < 0L || age >= timeoutMs) return null
        return current.destination
    }

    fun clear() {
        pending = null
    }

    companion object {
        /** Same answer window as the candidate dialog (HomeDestinationVoiceDialogLease). */
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}

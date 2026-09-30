package kr.co.hanium.dreamup.walksafe.navigation

/** A HOME candidate dialog expires with its owner, search, page, or explicit close. */
internal class HomeDestinationVoiceDialogLease(
    private val actorId: String,
    private val sessionGeneration: Long,
    private val searchGeneration: Int,
    private val state: DestinationSearchVoiceState,
    private val nowElapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val timeoutMs: Long = 30_000L,
    // A caller that creates the lease as its page prompt starts sets this, so the prompt's own
    // reading time does not use up the answer time; it then calls openAnswerWindow() at the end.
    answerWindowStartsAfterPrompt: Boolean = false,
) {
    init {
        require(timeoutMs > 0L)
    }

    private var closed = false
    private val createdAtMs = nowElapsedRealtimeMs()
    private var openedAtMs: Long? = if (answerWindowStartsAfterPrompt) null else createdAtMs

    fun close() {
        closed = true
        state.invalidateVoicePrompt()
    }

    /** Starts the answer time once; a closed dialog stays closed and a started one is not extended. */
    fun openAnswerWindow() {
        if (!closed && openedAtMs == null) openedAtMs = nowElapsedRealtimeMs()
    }

    fun currentState(
        actorId: String?,
        sessionGeneration: Long,
        searchGeneration: Int,
        query: String,
        state: DestinationSearchVoiceState?,
        homeContextAvailable: Boolean,
        voicePage: Boolean,
    ): DestinationSearchVoiceState? {
        val now = nowElapsedRealtimeMs()
        val answerStartMs = openedAtMs
        if (now < createdAtMs ||
            (answerStartMs != null && (now < answerStartMs || now - answerStartMs >= timeoutMs))
        ) close()
        return if (!closed && homeContextAvailable && voicePage &&
            actorId == this.actorId &&
            sessionGeneration == this.sessionGeneration &&
            searchGeneration == this.searchGeneration &&
            query == this.state.query && state === this.state
        ) {
            this.state
        } else {
            null
        }
    }
}

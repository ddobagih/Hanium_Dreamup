package kr.co.hanium.dreamup.walksafe.navigation

/** A HOME candidate dialog expires with its owner, search, page, or explicit close. */
internal class HomeDestinationVoiceDialogLease(
    private val actorId: String,
    private val sessionGeneration: Long,
    private val searchGeneration: Int,
    private val state: DestinationSearchVoiceState,
) {
    private var closed = false

    fun close() {
        closed = true
    }

    fun currentState(
        actorId: String?,
        sessionGeneration: Long,
        searchGeneration: Int,
        query: String,
        state: DestinationSearchVoiceState?,
        homeContextAvailable: Boolean,
        voicePage: Boolean,
    ): DestinationSearchVoiceState? =
        if (!closed && homeContextAvailable && voicePage &&
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

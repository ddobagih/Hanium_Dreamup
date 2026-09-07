package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HomeDestinationVoiceDialogLeaseTest {
    private val state = DestinationSearchVoiceState(
        query = "편의점",
        results = emptyList(),
        pageIndex = 0,
        moreResultsAvailable = false,
    )

    @Test
    fun sameLiveOwnerKeepsDialogWithoutDependingOnMicSessionOrElapsedSpeechTime() {
        val lease = lease()
        assertSame(state, current(lease))
        assertSame(state, current(lease))
    }

    @Test
    fun actorAndSessionChangesRejectOldDialog() {
        val lease = lease()
        assertNull(current(lease, actorId = null))
        assertNull(current(lease, actorId = "another-test-actor"))
        assertNull(current(lease, sessionGeneration = 8L))
    }

    @Test
    fun queryGenerationAndStateIdentityChangesRejectOldDialog() {
        val lease = lease()
        assertNull(current(lease, searchGeneration = 12))
        assertNull(current(lease, query = "다른 검색"))
        assertNull(current(lease, candidateState = null))
        assertNull(current(lease, candidateState = DestinationSearchVoiceState(
            query = state.query,
            results = state.results,
            pageIndex = state.pageIndex,
            moreResultsAvailable = state.moreResultsAvailable,
        )))
    }

    @Test
    fun ActualHomeGateAndVoicePageRemainRequired() {
        val lease = lease()
        assertNull(current(lease, homeContextAvailable = false))
        assertNull(current(lease, voicePage = false))
    }

    @Test
    fun closeCannotBeReversedByAValidLateCompletionOrReturningToTheSamePage() {
        val lease = lease()
        lease.close()
        assertNull(current(lease))
        lease.close()
        assertNull(current(lease))
    }

    private fun lease() = HomeDestinationVoiceDialogLease(
        actorId = "test-actor",
        sessionGeneration = 7L,
        searchGeneration = 11,
        state = state,
    )

    private fun current(
        lease: HomeDestinationVoiceDialogLease,
        actorId: String? = "test-actor",
        sessionGeneration: Long = 7L,
        searchGeneration: Int = 11,
        query: String = state.query,
        candidateState: DestinationSearchVoiceState? = state,
        homeContextAvailable: Boolean = true,
        voicePage: Boolean = true,
    ) = lease.currentState(
        actorId = actorId,
        sessionGeneration = sessionGeneration,
        searchGeneration = searchGeneration,
        query = query,
        state = candidateState,
        homeContextAvailable = homeContextAvailable,
        voicePage = voicePage,
    )
}

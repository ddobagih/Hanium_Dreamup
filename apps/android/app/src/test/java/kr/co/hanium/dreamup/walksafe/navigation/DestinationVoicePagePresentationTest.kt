package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.*
import org.junit.Test

class DestinationVoicePagePresentationTest {
    @Test fun currentPageIsNotSelectableBeforeItsPromptCompletes() {
        val state = page()
        assertFalse(select(state, 4).accepted)
        val attempt = state.beginVoicePrompt()
        assertFalse(select(state, 4).accepted)
        assertTrue(state.completeVoicePrompt(attempt))
        assertEquals("후보 4", select(state, 4).selectedResult?.name)
        assertFalse(select(state, 1).accepted)
        assertFalse(select(state, 7).accepted)
    }

    @Test fun failedAttemptCannotGrantPermissionThroughLateCompletion() {
        val state = page()
        val attempt = state.beginVoicePrompt()
        state.failVoicePrompt(attempt)
        assertFalse(state.completeVoicePrompt(attempt))
        assertNull(select(state, 4).selectedResult)
        val retry = state.beginVoicePrompt()
        assertTrue(state.completeVoicePrompt(retry))
        assertTrue(select(state, 4).accepted)
    }

    @Test fun replacedAttemptCannotCompleteOrFailTheNewAttempt() {
        val state = page()
        val old = state.beginVoicePrompt()
        val replacement = state.beginVoicePrompt()
        assertFalse(state.completeVoicePrompt(old))
        state.failVoicePrompt(old)
        assertFalse(select(state, 4).accepted)
        assertTrue(state.completeVoicePrompt(replacement))
        state.failVoicePrompt(old)
        assertTrue(select(state, 4).accepted)
    }

    @Test fun replayWithdrawsPermissionUntilTheWholePageIsHeardAgain() {
        val state = page()
        state.completeVoicePrompt(state.beginVoicePrompt())
        val replay = state.onCommand(DestinationSearchVoiceCommand.RepeatPage)
        assertSame(state, replay.state)
        val attempt = replay.state.beginVoicePrompt()
        assertFalse(select(state, 4).accepted)
        state.failVoicePrompt(attempt)
        assertFalse(select(state, 4).accepted)
    }

    @Test fun newPageAndRecoveredCopyHaveTheirOwnUnannouncedAuthority() {
        val state = page()
        val old = state.beginVoicePrompt()
        state.completeVoicePrompt(old)
        val next = state.onCommand(DestinationSearchVoiceCommand.HearMore).state
        assertEquals(2, next.pageIndex)
        assertFalse(select(next, 7).accepted)
        val recovered = state.copy()
        val attempt = recovered.beginVoicePrompt()
        assertFalse(recovered.completeVoicePrompt(old))
        assertFalse(select(recovered, 4).accepted)
        assertTrue(recovered.completeVoicePrompt(attempt))
        assertTrue(select(recovered, 4).accepted)
        assertTrue(recovered.canHearMore)
    }

    @Test fun clearingThePageInvalidatesBothPendingAndCompletedAuthority() {
        val state = page()
        val pending = state.beginVoicePrompt()
        state.invalidateVoicePrompt()
        assertFalse(state.completeVoicePrompt(pending))
        state.completeVoicePrompt(state.beginVoicePrompt())
        state.invalidateVoicePrompt()
        assertFalse(select(state, 4).accepted)
    }

    @Test fun leaseCloseAndExpiryCannotLeaveAuthorityOnRawState() {
        for (expire in listOf(false, true)) {
            val state = page()
            var now = 0L
            val lease = HomeDestinationVoiceDialogLease("actor", 1L, 1, state, { now })
            state.completeVoicePrompt(state.beginVoicePrompt())
            if (expire) {
                now = 30_000L
                assertNull(lease.currentState("actor", 1L, 1, state.query, state, true, true))
            } else lease.close()
            assertFalse(select(state, 4).accepted)
        }
    }

    private fun select(state: DestinationSearchVoiceState, index: Int) =
        state.onCommand(DestinationSearchVoiceCommand.SelectCandidate(index))

    private fun page() = DestinationSearchVoiceState(
        "후보", (1..7).map {
            DestinationSearchResult("$it", "후보 $it", RoutePoint(37.0, 127.0), null, null, null, null)
        }, pageIndex = 1,
    )
}

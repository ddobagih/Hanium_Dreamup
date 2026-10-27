package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.feedback.CommandSpeechSequence
import org.junit.Assert.*
import org.junit.Test

class DestinationCandidateSequenceTest {
    private fun page() = DestinationSearchVoiceState("약국", (1..6).map {
        DestinationSearchResult("$it", "약국 $it", RoutePoint(37.5, 127.0), null, "시험로 12-$it", "약국", 100)
    }, pageIndex = 1)

    @Test fun actualPageSequenceWaitsForAllCandidatesAndFailureKeepsSelectionBlocked() {
        for (failPart in listOf(-1, 0, 1, 2, 3, 4)) {
            val state = page(); val attempt = state.beginVoicePrompt()
            val parts = state.voicePromptParts()
            assertEquals(5, parts.size)
            for (n in 4..6) assertTrue(parts[n - 3].startsWith("${n}번"))
            val callbacks = mutableListOf<Pair<() -> Unit, () -> Unit>>()
            CommandSpeechSequence(parts, { true }, { _, done, fail ->
                callbacks += done to fail; true
            }, {},
                { state.completeVoicePrompt(attempt) }, { state.failVoicePrompt(attempt) }).start()
            for (index in parts.indices) {
                assertFalse(state.onCommand(DestinationSearchVoiceCommand.SelectCandidate(4)).accepted)
                if (index == failPart) {
                    callbacks[index].second(); callbacks[index].first(); break
                } else callbacks[index].first()
            }
            assertEquals(failPart < 0, state.onCommand(DestinationSearchVoiceCommand.SelectCandidate(4)).accepted)
            for (n in 4..6) assertEquals(failPart < 0, state.onCommand(DestinationSearchVoiceCommand.SelectCandidate(n)).accepted)
        }
    }

    @Test fun detailIsReadOnlyAndOnlyForTheHeardCurrentPage() {
        val state = page()
        assertNull(state.onCommand(DestinationSearchVoiceCommand.SelectCandidate(4)).selectedResult?.speechAddressOrNull())
        state.completeVoicePrompt(state.beginVoicePrompt())
        assertNull(state.onCommand(DestinationSearchVoiceCommand.SelectCandidate(1)).selectedResult); assertNull(state.onCommand(DestinationSearchVoiceCommand.SelectCandidate(7)).selectedResult)
        assertTrue(state.onCommand(DestinationSearchVoiceCommand.SelectCandidate(4)).selectedResult?.speechAddressOrNull()!!.contains("시험로 12의 4"))
        assertEquals(1, state.pageIndex)
        assertTrue(state.onCommand(DestinationSearchVoiceCommand.SelectCandidate(4)).accepted)
    }

    @Test fun detailsCannotBeMistakenForSelectionOrNegatedCommands() {
        for (text in listOf("2번 자세히", "이 번 자세히 알려줘", "2번 상세히 설명해주세요")) {
            assertEquals(AndroidVoiceCommand.DestinationCandidateDetail(2), parseAndroidVoiceCommand(text))
        }
        for (text in listOf("2번 자세히 알려주지 마", "1 2번 자세히", "0번 자세히")) {
            assertNull(text, parseAndroidVoiceCommand(text))
        }
        assertEquals(AndroidVoiceAction.SpeakDestinationCandidateDetail(2), AndroidVoiceCommand.DestinationCandidateDetail(2).toAction())
        assertEquals(VoiceCommandUiDestination.CURRENT_VOICE_PAGE, AndroidVoiceAction.SpeakDestinationCandidateDetail(2).uiDestination())
    }
}

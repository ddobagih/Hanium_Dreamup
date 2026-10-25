package kr.co.hanium.dreamup.walksafe.navigation

import java.io.File
import kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S06-38 (S05-14): "이전 후보" returns to the previous page of the already fetched list. The page must
 * be read in full again before its numbers are accepted; the current-page range check is unchanged.
 */
class DestinationPreviousPageTest {
    private val results = (1..7).map { index ->
        DestinationSearchResult("p$index", "장소 $index", RoutePoint(37.5, 127.0), null, null, null, index * 100)
    }

    @Test fun previousPhrasesParseToTheirOwnCommandOnly() {
        listOf("이전 후보", "이전후보", "앞 후보", "이전 후보 들려줘", "이전 후보 다시 들려줘").forEach { phrase ->
            assertEquals(phrase, AndroidVoiceCommand.PreviousDestinationCandidates, parseAndroidVoiceCommand(phrase))
            assertEquals(phrase, DestinationSearchVoiceCommand.PreviousPage, parseDestinationSearchVoiceCommand(phrase))
        }
        assertEquals(
            AndroidVoiceAction.PreviousDestinationCandidates,
            AndroidVoiceCommand.PreviousDestinationCandidates.toAction(),
        )
        listOf("이전", "이전 경로", "이전 후보 말고").forEach { phrase ->
            assertNotEquals(phrase, AndroidVoiceCommand.PreviousDestinationCandidates, parseAndroidVoiceCommand(phrase))
        }
        assertEquals(VoiceCommandUiDestination.DESTINATION_SEARCH, AndroidVoiceAction.PreviousDestinationCandidates.uiDestination())
    }

    @Test fun previousPageIsSelectableOnlyAfterItIsReadAgain() {
        val first = DestinationSearchVoiceState("장소", results)
        first.completeVoicePrompt(first.beginVoicePrompt())
        val second = first.onCommand(DestinationSearchVoiceCommand.HearMore).state
        second.completeVoicePrompt(second.beginVoicePrompt())
        assertTrue(second.onCommand(DestinationSearchVoiceCommand.SelectCandidate(4)).accepted)

        val back = second.onCommand(DestinationSearchVoiceCommand.PreviousPage)
        assertTrue(back.accepted)
        assertEquals(0, back.state.pageIndex)
        assertEquals(second.results, back.state.results)
        // Not yet heard: neither the old page's nor this page's numbers are accepted.
        assertFalse(back.state.onCommand(DestinationSearchVoiceCommand.SelectCandidate(1)).accepted)
        assertFalse(back.state.onCommand(DestinationSearchVoiceCommand.SelectCandidate(4)).accepted)
        back.state.completeVoicePrompt(back.state.beginVoicePrompt())
        assertEquals("장소 1", back.state.onCommand(DestinationSearchVoiceCommand.SelectCandidate(1)).selectedResult?.name)
        assertFalse(back.state.onCommand(DestinationSearchVoiceCommand.SelectCandidate(4)).accepted)
        assertTrue(back.state.voicePrompt().contains("1번부터 3번까지"))
    }

    @Test fun theFirstPageHasNoPreviousPage() {
        val first = DestinationSearchVoiceState("장소", results)
        val transition = first.onCommand(DestinationSearchVoiceCommand.PreviousPage)
        assertFalse(transition.accepted)
        assertSame(first, transition.state)
    }

    @Test fun mainActivityDispatchesPreviousPageLocallyAndListsItInHelp() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val dispatch = MainActivityRefactorSource.function(source, "fun executeVoiceAction(")
        assertTrue(dispatch.contains("AndroidVoiceAction.PreviousDestinationCandidates,"))
        assertTrue(dispatch.contains("AndroidVoiceAction.PreviousDestinationCandidates -> dialog().previousVoiceDestinationCandidates()"))
        assertTrue(dispatch.contains("voiceCommandHelpKo("))
        assertTrue(voiceCommandHelpKo(paused = false).contains("더 듣기, 이전 후보, 목적지 취소"))
        val previous = MainActivityRefactorSource.function(source, "fun previousVoiceDestinationCandidates()")
        assertTrue(previous.contains("state.onCommand(DestinationSearchVoiceCommand.PreviousPage)"))
        assertTrue(previous.contains("speakHomeDestinationVoicePrompt(transition.state)"))
        assertTrue(previous.contains("첫 번째 후보 목록입니다."))
        assertFalse(previous.contains("search().search("))
        assertFalse(previous.contains("SelectCandidate"))
    }
}

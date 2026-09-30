package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S06-32: re-hearing the current candidate page states that distances are from the search position;
 * numbers, order and distances themselves are never changed or re-sorted.
 */
class DestinationCandidateRepeatDistanceBasisTest {
    @Test
    fun firstReadingHasNoBasisSentenceAndRepeatHasItOnce() {
        val state = state()
        val first = state.voicePrompt()
        assertFalse(first.contains(BASIS))
        // MainActivity calls voicePrompt() twice before beginVoicePrompt(); both must match.
        assertEquals(first, state.voicePrompt())
        state.completeVoicePrompt(state.beginVoicePrompt())

        val repeat = state.onCommand(DestinationSearchVoiceCommand.RepeatPage)
        assertTrue(repeat.accepted)
        assertSame(state, repeat.state)
        val repeated = repeat.state.voicePrompt()
        assertEquals(1, repeated.windowed(BASIS.length).count { it == BASIS })
        assertEquals(repeated, repeat.state.voicePrompt())
        repeat.state.beginVoicePrompt()
        assertEquals(1, repeat.state.voicePrompt().windowed(BASIS.length).count { it == BASIS })
    }

    @Test
    fun repeatKeepsNumbersOrderAndDistances() {
        val state = state()
        val first = state.voicePrompt()
        state.completeVoicePrompt(state.beginVoicePrompt())
        val repeated = state.onCommand(DestinationSearchVoiceCommand.RepeatPage).state.voicePrompt()
        assertEquals(first, repeated.replace("$BASIS ", ""))
        assertTrue(repeated.indexOf("1번 가게") < repeated.indexOf("2번 나게"))
        // S06-37/S06-31: distinct names are read without the address, distances as the rough policy value.
        assertTrue(repeated.contains("1번 가게, 약 100미터"))
        assertTrue(repeated.contains("2번 나게, 약 80미터"))
    }

    @Test
    fun nextPageFirstReadingHasNoBasisSentence() {
        val results = (1..4).map { index ->
            DestinationSearchResult("$index", "가게$index", RoutePoint(37.0, 127.0), "주소 $index", null, null, 100 * index)
        }
        val state = DestinationSearchVoiceState("카페", results)
        state.completeVoicePrompt(state.beginVoicePrompt())
        val next = state.onCommand(DestinationSearchVoiceCommand.HearMore)
        assertTrue(next.accepted)
        assertFalse(next.state.voicePrompt().contains(BASIS))
    }

    @Test
    fun emptyResultsNeverMentionDistanceBasis() {
        val state = DestinationSearchVoiceState("카페", emptyList())
        state.beginVoicePrompt()
        assertFalse(state.voicePrompt().contains(BASIS))
    }

    private fun state() = DestinationSearchVoiceState(
        "카페",
        listOf(
            DestinationSearchResult("1", "가게", RoutePoint(37.0, 127.0), "서울 중구 세종대로 1", null, null, 120),
            DestinationSearchResult("2", "나게", RoutePoint(37.0, 127.0), "서울 중구 세종대로 2", null, null, 80),
        ),
    )

    private companion object {
        const val BASIS = "거리는 처음 검색한 위치 기준입니다."
    }
}

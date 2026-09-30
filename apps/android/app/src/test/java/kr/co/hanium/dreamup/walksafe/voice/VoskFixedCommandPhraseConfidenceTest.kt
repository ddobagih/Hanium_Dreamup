package kr.co.hanium.dreamup.walksafe.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * voice-F7 (S04-19, 04.txt:256 "어떤 단어가 틀리면 동작이 바뀌는지 기준" / S05-23): in a fixed command
 * phrase the words that are not verbs still pick the action - "다른 경로 찾아줘" (reroute), "보행 종료"
 * (the walk) vs "안내 종료" (guidance), "목적지 취소" (destination) vs "검색 취소" (dialog). Each word of
 * such a phrase must reach 0.60 on its own; a destination's free words stay under the span average.
 */
class VoskFixedCommandPhraseConfidenceTest {
    @Test
    fun oneLowWordOfAFixedPhraseRejectsTheCommand() {
        val cases = listOf(
            listOf("다른" to 0.20, "경로" to 1.0, "찾아줘" to 1.0),
            listOf("새" to 0.20, "경로" to 1.0, "요청" to 1.0),
            listOf("보행" to 0.20, "종료" to 1.0),
            listOf("목적지" to 0.20, "취소" to 1.0),
            listOf("검색" to 0.20, "취소" to 1.0),
            listOf("후보" to 0.20, "선택" to 1.0, "취소" to 1.0),
            listOf("경로" to 0.20, "재탐색" to 1.0),
            listOf("위치" to 0.20, "다시" to 1.0, "확인" to 1.0),
            listOf("보행" to 0.20, "일시정지" to 1.0),
        )
        for (case in cases) {
            val command = case.joinToString(" ") { it.first }
            val transcript = words(*(listOf("길라잡이" to 1.0) + case).toTypedArray())
            assertTrue(command, transcript.isTrustedForCommand())
            assertNull(command, transcript.commandSpanOrNull(command))
            val clear = words(*(listOf("길라잡이" to 1.0) + case.map { it.first to 0.9 }).toTypedArray())
            assertEquals(command, clear.commandSpanOrNull(command)!!.text)
        }
    }

    @Test
    fun aDestinationsFreeWordsStayUnderTheSpanAverage() {
        val command = "서울역 근처 카페 찾아줘"
        val transcript = words("길라잡이" to 1.0, "서울역" to 0.30, "근처" to 1.0, "카페" to 1.0, "찾아줘" to 1.0)
        assertEquals(command, transcript.commandSpanOrNull(command)!!.text)
        // The command verb itself is still checked on its own (S04-19).
        assertNull(
            words("길라잡이" to 1.0, "서울역" to 1.0, "근처" to 1.0, "카페" to 1.0, "찾아줘" to 0.30)
                .commandSpanOrNull(command),
        )
    }

    private fun words(vararg pairs: Pair<String, Double>): VoskTranscript {
        val result = pairs.joinToString(",") { (word, conf) -> """{"conf":$conf,"word":"$word"}""" }
        val text = pairs.joinToString(" ") { it.first }
        return requireNotNull(parseVoskTranscript("""{"result":[$result],"text":"$text"}""", isFinal = true))
    }
}

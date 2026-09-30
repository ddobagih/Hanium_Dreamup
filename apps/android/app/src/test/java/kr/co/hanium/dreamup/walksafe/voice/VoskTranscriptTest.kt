package kr.co.hanium.dreamup.walksafe.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoskTranscriptTest {
    @Test
    fun parsesFinalTextAndAverageWordConfidence() {
        val transcript = parseVoskTranscript(
            payload = """
                {
                  "result": [
                    {"conf": 0.8, "word": "길라잡이"},
                    {"conf": 0.6, "word": "안내해줘"}
                  ],
                  "text": "  길라잡이   안내해줘  "
                }
            """.trimIndent(),
            isFinal = true,
        )

        assertEquals("길라잡이 안내해줘", transcript?.text)
        assertEquals(0.7f, transcript?.confidence ?: 0f, 0.0001f)
        assertEquals(true, transcript?.isFinal)
    }

    @Test
    fun parsesPartialWithoutInventingConfidence() {
        val transcript = parseVoskTranscript(
            payload = """{"partial":"길라 잡이"}""",
            isFinal = false,
        )

        assertEquals("길라 잡이", transcript?.text)
        assertNull(transcript?.confidence)
        assertEquals(false, transcript?.isFinal)
    }

    @Test
    fun ignoresBlankMalformedAndOversizedPayloads() {
        assertNull(parseVoskTranscript("not-json", isFinal = true))
        assertNull(parseVoskTranscript("""{"text":"  "}""", isFinal = true))
        assertNull(parseVoskTranscript("x".repeat(32_769), isFinal = false))
    }

    @Test
    fun ignoresInvalidConfidenceValues() {
        val transcript = parseVoskTranscript(
            payload = """
                {"result":[{"conf":-1},{"conf":2}],"text":"길라잡이"}
            """.trimIndent(),
            isFinal = true,
        )

        assertEquals("길라잡이", transcript?.text)
        assertNull(transcript?.confidence)
    }

    // S05-24 (05.txt:256): scores must belong to the text words, not only exist.
    @Test
    fun wordScoresThatDoNotMatchTheTextGiveNoSentenceScore() {
        val mismatched = listOf(
            // Words differ from the text: the scores were given to other words.
            """{"result":[{"conf":0.99,"word":"길라잡이"},{"conf":0.99,"word":"3번"},{"conf":0.99,"word":"선택"}],"text":"길라잡이 2번 선택"}""",
            // A text word has no score entry of its own.
            """{"result":[{"conf":0.99,"word":"길라잡이"},{"conf":0.99,"word":"선택"}],"text":"길라잡이 2번 선택"}""",
            // Only some entries name their word.
            """{"result":[{"conf":0.99,"word":"길라잡이"},{"conf":0.99},{"conf":0.99,"word":"선택"}],"text":"길라잡이 2번 선택"}""",
            // A blank word.
            """{"result":[{"conf":0.99,"word":"길라잡이"},{"conf":0.99,"word":" "},{"conf":0.99,"word":"선택"}],"text":"길라잡이 2번 선택"}""",
            // Without word fields the score count must still match the text words.
            """{"result":[{"conf":0.99},{"conf":0.99}],"text":"길라잡이 2번 선택"}""",
        )
        for (payload in mismatched) {
            val transcript = parseVoskTranscript(payload, isFinal = true)
            assertEquals(payload, "길라잡이 2번 선택", transcript?.text)
            assertNull(payload, transcript?.confidence)
            assertNull(payload, transcript?.wordConfidences)
            assertFalse(payload, transcript!!.isTrustedForCommand())
            assertNull(payload, transcript.commandSpanOrNull("2번 선택"))
        }
    }

    @Test
    fun alignedWordScoresWithOrWithoutWordFieldsKeepTheirScores() {
        val named = parseVoskTranscript(
            """{"result":[{"conf":0.9,"word":"길라잡이"},{"conf":0.8,"word":"2번"},{"conf":0.7,"word":"선택"}],"text":" 길라잡이  2번 선택 "}""",
            isFinal = true,
        )!!
        assertEquals(listOf(0.9f, 0.8f, 0.7f), named.wordConfidences)
        assertEquals(0.8f, named.confidence!!, 0.0001f)
        // The existing device fixture sends scores without word fields; the count still matches.
        val unnamed = parseVoskTranscript("""{"text":"도움말","result":[{"conf":0.9}]}""", isFinal = true)!!
        assertEquals(0.9f, unnamed.confidence!!, 0.0001f)
        assertTrue(unnamed.isTrustedForCommand())
    }

    @Test
    fun commandTrustRequiresFiniteConfidenceAtTheApprovedThreshold() {
        assertFalse(VoskTranscript("길라잡이", null, true).isTrustedForCommand())
        assertFalse(VoskTranscript("길라잡이", Float.NaN, true).isTrustedForCommand())
        assertFalse(VoskTranscript("길라잡이", 0.59f, true).isTrustedForCommand())
        assertTrue(VoskTranscript("길라잡이", 0.60f, true).isTrustedForCommand())
    }
}

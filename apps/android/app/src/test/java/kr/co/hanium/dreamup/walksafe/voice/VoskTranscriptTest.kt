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

    @Test
    fun commandTrustRequiresFiniteConfidenceAtTheApprovedThreshold() {
        assertFalse(VoskTranscript("길라잡이", null, true).isTrustedForCommand())
        assertFalse(VoskTranscript("길라잡이", Float.NaN, true).isTrustedForCommand())
        assertFalse(VoskTranscript("길라잡이", 0.59f, true).isTrustedForCommand())
        assertTrue(VoskTranscript("길라잡이", 0.60f, true).isTrustedForCommand())
    }
}

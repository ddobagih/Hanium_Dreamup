package kr.co.hanium.dreamup.walksafe.network

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewaySpeechClientTest {
    private val clientSource =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/network/GatewaySpeechClient.kt").readText()

    @Test
    fun parsesOnlyExactTranscriptAndAcousticEvidence() {
        val parsed = parseGatewaySpeechTranscript(validResponse(), REQUEST_ID)

        requireNotNull(parsed)
        assertEquals("보행 일시정지", parsed.transcript)
        assertEquals(0.81, parsed.acoustic.confidence, 0.0)
        assertEquals(-0.2, parsed.acoustic.avgLogprob!!, 0.0)
        assertNull(parsed.acoustic.noSpeechProbability)
        assertTrue(parsed.acoustic.executionAllowed)
    }

    @Test
    fun rejectsServerIntentUnknownFieldsAndUnsafeAcousticValues() {
        assertNull(
            parseGatewaySpeechTranscript(
                validResponse().dropLast(1) + ",\"intent\":\"pause\",\"action\":\"execute\"}",
                REQUEST_ID,
            ),
        )
        assertNull(
            parseGatewaySpeechTranscript(
                validResponse().replace("\"confidence\":0.81", "\"confidence\":1.1"),
                REQUEST_ID,
            ),
        )
        assertNull(parseGatewaySpeechTranscript(validResponse(), OTHER_REQUEST_ID))
    }

    @Test
    fun ttsTextRejectsControlCharactersAndCodePointOverflow() {
        assertTrue(validGatewaySpeechText("명령을 다시 말씀해 주세요."))
        assertFalse(validGatewaySpeechText("안내\u0000문장"))
        assertFalse(validGatewaySpeechText("가".repeat(SPEECH_TTS_MAX_TEXT_CODE_POINTS + 1)))
    }

    @Test
    fun ttsModelRevisionIsMandatoryAndCanonical() {
        val revision = "a".repeat(40)
        assertEquals(revision, parseGatewaySpeechModelRevision(revision))
        assertEquals(revision, parseGatewaySpeechModelRevision("  $revision  "))
        assertNull(parseGatewaySpeechModelRevision(null))
        assertNull(parseGatewaySpeechModelRevision("A".repeat(40)))
        assertNull(parseGatewaySpeechModelRevision("a".repeat(39)))
    }

    @Test
    fun callbackFenceRequiresForegroundGateActorSessionAndInteractionGeneration() {
        val fence = GatewaySpeechCallbackFence("actor", 7, "instance", 11)
        assertTrue(
            isGatewaySpeechCallbackCurrent(
                fence,
                "actor",
                7,
                "instance",
                11,
                foreground = true,
                featureGatePassed = true,
            ),
        )
        assertFalse(
            isGatewaySpeechCallbackCurrent(
                fence,
                "actor",
                8,
                "instance",
                11,
                foreground = true,
                featureGatePassed = true,
            ),
        )
        assertFalse(
            isGatewaySpeechCallbackCurrent(
                fence,
                "actor",
                7,
                "instance",
                12,
                foreground = false,
                featureGatePassed = true,
            ),
        )
    }

    @Test
    fun transportKeepsCookieServerSideTokenBoundaryRedirectAndByteLimits() {
        assertTrue(clientSource.contains("session.requestHeaders().forEach(::setRequestProperty)"))
        assertTrue(clientSource.contains("instanceFollowRedirects = false"))
        assertTrue(clientSource.contains("readBoundedResponse("))
        assertTrue(clientSource.contains("readBoundedBytes(SPEECH_TTS_MAX_RESPONSE_BYTES"))
        assertFalse(clientSource.contains("VOICE_SERVICE_TOKEN"))
        assertFalse(clientSource.contains("Authorization"))
    }

    private fun validResponse(): String =
        """{"schema_version":"$SPEECH_STT_RESPONSE_SCHEMA","request_id":"$REQUEST_ID","transcript":"보행 일시정지","acoustic":{"confidence":0.81,"avg_logprob":-0.2,"no_speech_probability":null,"execution_allowed":true},"model_revision":"${"a".repeat(40)}"}"""

    private companion object {
        const val REQUEST_ID = "018f2b63-8fb8-7cc2-98a1-4a4fd27c3001"
        const val OTHER_REQUEST_ID = "018f2b63-8fb8-7cc2-98a1-4a4fd27c3002"
    }
}

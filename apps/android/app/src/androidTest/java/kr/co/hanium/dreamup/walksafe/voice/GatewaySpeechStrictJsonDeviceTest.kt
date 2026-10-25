package kr.co.hanium.dreamup.walksafe.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import kr.co.hanium.dreamup.walksafe.network.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the production parser with Android's JSONObject, independently of the host JSON jar. */
@RunWith(AndroidJUnit4::class)
class GatewaySpeechStrictJsonDeviceTest {
    private val requestId = "018f2b63-8fb8-7cc2-98a1-4a4fd27c3001"
    private fun response() = """{"schema_version":"$SPEECH_STT_RESPONSE_SCHEMA","request_id":"$requestId","transcript":"현재 상태","acoustic":{"confidence":0.91,"avg_logprob":-0.2,"no_speech_probability":null,"execution_allowed":true},"model_revision":"${"a".repeat(40)}"}"""

    @Test fun nativeParserAcceptsCanonicalEvidenceAndKeepsExecutionDeniedExplicit() {
        val valid = requireNotNull(parseGatewaySpeechTranscript(response(), requestId))
        assertEquals("현재 상태", valid.transcript)
        assertTrue(valid.acoustic.executionAllowed)
        val denied = requireNotNull(parseGatewaySpeechTranscript(response().replace("\"execution_allowed\":true", "\"execution_allowed\":false"), requestId))
        assertFalse(denied.acoustic.executionAllowed)
    }

    @Test fun nativeParserRejectsDuplicateEscapedKeysTrailingAndNonstandardGrammar() {
        val valid = response()
        val invalid = listOf(
            valid.replace("\"transcript\":", "\"transcript\":\"안내 시작\",\"transcript\":"),
            valid.replace("\"confidence\":0.91", "\"confidence\":0.91,\"confid\\u0065nce\":0.99"),
            valid.replace("\"confidence\":0.91", "\"confidence\":true"),
            valid.replace("\"confidence\":0.91", "\"confidence\":1.1"),
            valid.replace("\"confidence\":0.91", "\"confidence\":.91"),
            valid.replace("\"confidence\":0.91", "\"confidence\":00.91"),
            valid.replace("\"transcript\":", "'transcript':"),
            valid + " {}", valid.replace("{", "{/*comment*/", false),
        )
        invalid.forEach { assertNull(parseGatewaySpeechTranscript(it, requestId)) }
        assertNull(parseGatewaySpeechTranscript(valid, "018f2b63-8fb8-7cc2-98a1-4a4fd27c3002"))
    }
}

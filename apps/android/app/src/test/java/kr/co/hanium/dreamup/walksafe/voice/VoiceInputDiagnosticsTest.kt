package kr.co.hanium.dreamup.walksafe.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceInputDiagnosticsTest {
    @Test
    fun missingUnavailableAndZeroRemainDistinct() {
        assertEquals(VoiceInputConfidenceCategory.MISSING, classifyVoiceInputConfidence(null))
        assertEquals(VoiceInputConfidenceCategory.UNAVAILABLE_MINUS_ONE, classifyVoiceInputConfidence(-1f))
        assertEquals(VoiceInputConfidenceCategory.ZERO, classifyVoiceInputConfidence(0f))
    }

    @Test
    fun nonfiniteAndOutOfRangeAreNotSuccessfulConfidence() {
        for (score in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(VoiceInputConfidenceCategory.NONFINITE, classifyVoiceInputConfidence(score))
        }
        for (score in listOf(-0.1f, -2f, 1.01f)) {
            assertEquals(VoiceInputConfidenceCategory.OUT_OF_RANGE, classifyVoiceInputConfidence(score))
        }
    }

    @Test
    fun diagnosticBinsPreserveBothExistingThresholdBoundaries() {
        assertEquals(VoiceInputConfidenceCategory.BELOW_055, classifyVoiceInputConfidence(0.549f))
        assertEquals(VoiceInputConfidenceCategory.FROM_055_TO_060, classifyVoiceInputConfidence(0.55f))
        assertEquals(VoiceInputConfidenceCategory.FROM_055_TO_060, classifyVoiceInputConfidence(0.599f))
        assertEquals(VoiceInputConfidenceCategory.AT_LEAST_060, classifyVoiceInputConfidence(0.60f))
        assertEquals(VoiceInputConfidenceCategory.AT_LEAST_060, classifyVoiceInputConfidence(1f))
    }

    @Test
    fun finalMetadataSeparatesEmptyResultFromMissingScoreWithoutInventingConfidence() {
        assertEquals(
            "event=FINAL_RECEIVED backend=PLATFORM confidence=MISSING result_count=0 " +
                "error_code=NA matched=NA selection=NA failure=NA stream_error=NA",
            formatVoiceInputDiagnostic(
                VoiceInputDiagnosticEvent.FINAL_RECEIVED,
                backend = OfflineSpeechEngine.PLATFORM,
                resultCount = 0,
            ),
        )
    }

    @Test
    fun providerZeroIsReportedAsZeroInsteadOfMissingOrAccepted() {
        assertEquals(
            "event=FINAL_RECEIVED backend=PLATFORM confidence=ZERO result_count=1 " +
                "error_code=NA matched=NA selection=NA failure=NA stream_error=NA",
            formatVoiceInputDiagnostic(
                VoiceInputDiagnosticEvent.FINAL_RECEIVED,
                backend = OfflineSpeechEngine.PLATFORM,
                confidence = 0f,
                resultCount = 1,
            ),
        )
    }

    @Test
    fun wakeTimeoutIsReportedAsFailureNotHardwareAvailabilityOrParserSuccess() {
        assertEquals(
            "event=WAKE_UI_RESULT backend=VOSK confidence=MISSING result_count=NA " +
                "error_code=NA matched=false selection=NA failure=TIMEOUT stream_error=NA",
            formatVoiceInputDiagnostic(
                VoiceInputDiagnosticEvent.WAKE_UI_RESULT,
                backend = OfflineSpeechEngine.VOSK,
                matched = false,
                failure = VoskWakePhraseProbeFailure.TIMEOUT,
            ),
        )
    }
}

package kr.co.hanium.dreamup.walksafe.feedback

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsRuntimeFailurePolicyTest {
    @Test
    fun cancelledOrPreviousEngineErrorsCannotDisableTheCurrentEngine() {
        listOf(TextToSpeech.ERROR_SERVICE, TextToSpeech.ERROR_NOT_INSTALLED_YET, TextToSpeech.ERROR_OUTPUT)
            .forEach { error ->
                assertEquals(
                    TtsRuntimeFailureDisposition.IGNORE,
                    ttsRuntimeFailureDisposition(error, utterancePending = false, currentEngine = true),
                )
                assertEquals(
                    TtsRuntimeFailureDisposition.IGNORE,
                    ttsRuntimeFailureDisposition(error, utterancePending = true, currentEngine = false),
                )
            }
    }

    @Test
    fun outputSynthesisAndUnknownErrorsFailOnlyTheUtterance() {
        listOf(TextToSpeech.ERROR_OUTPUT, TextToSpeech.ERROR_SYNTHESIS, TextToSpeech.ERROR_INVALID_REQUEST, TextToSpeech.ERROR)
            .forEach { error ->
                assertEquals(
                    TtsRuntimeFailureDisposition.UTTERANCE,
                    ttsRuntimeFailureDisposition(error, utterancePending = true, currentEngine = true),
                )
            }
    }

    @Test
    fun missingDataAndServiceFailureHaveDifferentRecoveryPaths() {
        assertEquals(
            TtsRuntimeFailureDisposition.LANGUAGE,
            ttsRuntimeFailureDisposition(TextToSpeech.ERROR_NOT_INSTALLED_YET, true, true),
        )
        assertEquals(
            TtsRuntimeFailureDisposition.ENGINE,
            ttsRuntimeFailureDisposition(TextToSpeech.ERROR_SERVICE, true, true),
        )
    }

    @Test
    fun engineInitializationAndRuntimeFailuresShareOneAutomaticRecoveryBudget() {
        val budget = TtsEngineRecoveryBudget()

        assertTrue(budget.claimRetry())
        repeat(10) { assertFalse(budget.claimRetry()) }
    }
}

package kr.co.hanium.dreamup.walksafe.session

import kr.co.hanium.dreamup.walksafe.navigation.WalkSessionVoiceControlPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class WalkSessionResumeConfidenceTest {
    private val policy = WalkSessionVoiceControlPolicy()

    private fun resolve(phrases: List<String?>, scores: FloatArray?) =
        WalkSessionResumeConfirmation.fromRecognizedText(phrases, scores, policy::acceptsConfidence)

    @Test
    fun lowConfidenceCannotConfirmResumeOrCancel() {
        for (phrase in listOf("시작", "취소")) {
            for (confidence in listOf(0f, 0.79f)) {
                assertEquals(WalkSessionResumeConfirmation.UNRECOGNIZED,
                    resolve(listOf(phrase), floatArrayOf(confidence)))
            }
        }
    }

    @Test
    fun sufficientConfidenceUsesTheExistingWalkVoiceThresholdAndWords() {
        assertEquals(WalkSessionResumeConfirmation.START, resolve(listOf("시작."), floatArrayOf(0.80f)))
        assertEquals(WalkSessionResumeConfirmation.CANCEL, resolve(listOf(" 취소! "), floatArrayOf(0.95f)))
        assertEquals(WalkSessionResumeConfirmation.UNRECOGNIZED,
            resolve(listOf("다시 시작"), floatArrayOf(1f)))
    }

    @Test
    fun firstSpokenCandidateKeepsItsOriginalConfidenceIndex() {
        assertEquals(WalkSessionResumeConfirmation.START,
            resolve(listOf("", "시작"), floatArrayOf(0f, 0.95f)))
        assertEquals(WalkSessionResumeConfirmation.UNRECOGNIZED,
            resolve(listOf("", "시작"), floatArrayOf(1f, 0.1f)))
    }

    @Test
    fun missingInvalidOrMisalignedScoresAskAgain() {
        val invalid = listOf(null, floatArrayOf(), floatArrayOf(-1f), floatArrayOf(Float.NaN),
            floatArrayOf(Float.POSITIVE_INFINITY), floatArrayOf(1.1f), floatArrayOf(1f, 1f))
        for (scores in invalid) {
            assertEquals(WalkSessionResumeConfirmation.UNRECOGNIZED, resolve(listOf("시작"), scores))
        }
    }

    @Test
    fun confidenceDoesNotOverrideConflictingOrUnrecognizedAlternatives() {
        assertEquals(WalkSessionResumeConfirmation.UNRECOGNIZED,
            resolve(listOf("시작", "취소"), floatArrayOf(0.95f, 0.95f)))
        assertEquals(WalkSessionResumeConfirmation.UNRECOGNIZED,
            resolve(listOf("다른 말", "시작"), floatArrayOf(0.95f, 0.95f)))
    }

    @Test
    fun noResponseStillDoesNotNeedRecognizerScores() {
        assertEquals(WalkSessionResumeConfirmation.NO_RESPONSE, resolve(emptyList(), null))
        assertEquals(WalkSessionResumeConfirmation.NO_RESPONSE, resolve(listOf("", "..."), null))
    }
}

package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Test

/** S03-12: punctuation/blank alternatives are normalized without widening the accepted words. */
class WalkSessionResumeConfirmationCandidatesTest {
    @Test
    fun trailingPunctuationAndSpacesDoNotHideTheExactWord() {
        listOf("시작.", "시작!", " 시작 . ", "시작?", "시작…").forEach {
            assertEquals(it, WalkSessionResumeConfirmation.START, WalkSessionResumeConfirmation.fromRecognizedText(it))
        }
        listOf("취소.", " 취소! ").forEach {
            assertEquals(it, WalkSessionResumeConfirmation.CANCEL, WalkSessionResumeConfirmation.fromRecognizedText(it))
        }
        listOf("시작해", "다시 시작", "시작 취소", ".시작", "START", "취소해").forEach {
            assertEquals(
                it,
                WalkSessionResumeConfirmation.UNRECOGNIZED,
                WalkSessionResumeConfirmation.fromRecognizedText(it),
            )
        }
        assertEquals(WalkSessionResumeConfirmation.NO_RESPONSE, WalkSessionResumeConfirmation.fromRecognizedText(" . "))
    }

    @Test
    fun blankFirstAlternativeFallsThroughToTheFirstSpokenOne() {
        assertEquals(
            WalkSessionResumeConfirmation.START,
            WalkSessionResumeConfirmation.fromRecognizedText(listOf("", " ", "시작")),
        )
        assertEquals(
            WalkSessionResumeConfirmation.CANCEL,
            WalkSessionResumeConfirmation.fromRecognizedText(listOf(null, "취소.")),
        )
    }

    @Test
    fun unrecognizedFirstAlternativeIsNeverOverriddenByALaterStart() {
        assertEquals(
            WalkSessionResumeConfirmation.UNRECOGNIZED,
            WalkSessionResumeConfirmation.fromRecognizedText(listOf("시작해", "시작")),
        )
        assertEquals(
            WalkSessionResumeConfirmation.UNRECOGNIZED,
            WalkSessionResumeConfirmation.fromRecognizedText(listOf("음", "시작")),
        )
    }

    @Test
    fun contradictingAlternativesAskAgain() {
        assertEquals(
            WalkSessionResumeConfirmation.UNRECOGNIZED,
            WalkSessionResumeConfirmation.fromRecognizedText(listOf("시작", "취소")),
        )
        assertEquals(
            WalkSessionResumeConfirmation.UNRECOGNIZED,
            WalkSessionResumeConfirmation.fromRecognizedText(listOf("취소", "", "시작.")),
        )
    }

    @Test
    fun consistentAlternativesKeepTheDecision() {
        assertEquals(
            WalkSessionResumeConfirmation.START,
            WalkSessionResumeConfirmation.fromRecognizedText(listOf("시작", "시작.", "시작해")),
        )
        assertEquals(
            WalkSessionResumeConfirmation.NO_RESPONSE,
            WalkSessionResumeConfirmation.fromRecognizedText(emptyList()),
        )
        assertEquals(
            WalkSessionResumeConfirmation.NO_RESPONSE,
            WalkSessionResumeConfirmation.fromRecognizedText(listOf("", "  ")),
        )
    }
}

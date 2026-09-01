package kr.co.hanium.dreamup.walksafe.voice

import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class WakePhraseCommandExtractorTest {
    @Test
    fun extractsCommandFollowingWakePhraseInOneFinalTranscript() {
        assertEquals(
            WakePhraseCommandExtraction.Command("서울역으로 안내해줘"),
            extract("길라잡이, 서울역으로 안내해줘"),
        )
    }

    @Test
    fun acceptsOnlyTheExplicitSpacedWakePhraseVariant() {
        assertEquals(
            WakePhraseCommandExtraction.Command("신고해줘"),
            extract("길라 잡이 신고해줘"),
        )
    }

    @Test
    fun wakePhraseAloneOpensExactlyOneExplicitFollowUpCommandMode() {
        assertEquals(
            WakePhraseCommandExtraction.AwaitingCommand,
            extract("길라잡이."),
        )
        assertEquals(
            WakePhraseCommandExtraction.Command("서울역으로 안내해줘"),
            extract(
                "서울역으로 안내해줘",
                WakePhraseCommandMode.COMMAND_AWAITED,
            ),
        )
    }

    @Test
    fun discardsNoiseBeforeTheFirstExactWakePhrase() {
        assertEquals(
            WakePhraseCommandExtraction.Command("다음 안내 알려줘"),
            extract("자동차 소리, 사람 말소리... 길라잡이! 다음 안내 알려줘"),
        )
    }

    @Test
    fun transcriptWithoutWakePhraseCannotOpenACommand() {
        assertEquals(
            WakePhraseCommandExtraction.NotAddressed,
            extract("서울역으로 안내해줘"),
        )
    }

    @Test
    fun similarWordsAndPartialMatchesDoNotTrigger() {
        listOf(
            "길라잡이로 서울역 가자",
            "길라잡이처럼 말해줘",
            "길라잡이꾼 얘기야",
            "길라 잡이꾼 얘기야",
            "길라잡 서울역으로 가자",
            "길라 잡히지 않아",
            "잡음길라잡이 신고해줘",
        ).forEach { transcript ->
            assertEquals(
                transcript,
                WakePhraseCommandExtraction.NotAddressed,
                extract(transcript),
            )
        }
    }

    @Test
    fun normalizesUnicodePunctuationAndRepeatedWhitespace() {
        val decomposedWakePhrase = Normalizer.normalize("길라잡이", Normalizer.Form.NFD)

        assertEquals(
            WakePhraseCommandExtraction.Command("서울역으로 안내해줘"),
            extract("잡음！\u3000$decomposedWakePhrase，  서울역으로\t\n안내해줘。"),
        )
    }

    @Test
    fun rejectsUnsafeFormatCharactersInsteadOfJoiningAroundThem() {
        assertEquals(
            WakePhraseCommandExtraction.NotAddressed,
            extract("길라\u200B잡이 서울역으로 안내해줘"),
        )
    }

    @Test
    fun transcriptLengthLimitIsInclusiveAndFailsClosedAboveTheLimit() {
        val maximumCommand = "가".repeat(MAX_WAKE_PHRASE_TRANSCRIPT_CODE_POINTS - 5)
        assertEquals(
            WakePhraseCommandExtraction.Command(maximumCommand),
            extract("길라잡이 $maximumCommand"),
        )

        val oversizedCommand = "가".repeat(MAX_WAKE_PHRASE_TRANSCRIPT_CODE_POINTS - 4)
        assertEquals(
            WakePhraseCommandExtraction.NotAddressed,
            extract("길라잡이 $oversizedCommand"),
        )
    }

    private fun extract(
        transcript: String,
        mode: WakePhraseCommandMode = WakePhraseCommandMode.WAKE_PHRASE_REQUIRED,
    ): WakePhraseCommandExtraction = WakePhraseCommandExtractor.extractFinalTranscript(
        transcript,
        mode,
    )
}

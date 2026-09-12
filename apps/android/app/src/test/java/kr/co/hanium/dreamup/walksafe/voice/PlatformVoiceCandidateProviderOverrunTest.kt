package kr.co.hanium.dreamup.walksafe.voice

import kr.co.hanium.dreamup.walksafe.navigation.AndroidVoiceAction
import kr.co.hanium.dreamup.walksafe.navigation.AndroidVoiceCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * On SM-A716S the platform recognizer answers a three-candidate request with four hypotheses, and
 * `EXTRA_MAX_RESULTS` is a hint Android never promised to honour. The size cap then rejected a
 * correctly recognised "도움말" before agreement was ever examined — the walker said the one word
 * the help screen teaches and heard that the app could not settle on a candidate.
 *
 * The rule worth keeping is "never claim agreement while discarding alternatives". Requiring every
 * returned hypothesis to agree enforces exactly that, and a fourth hypothesis makes it stricter
 * rather than invalid. `hasAdditionalAlternatives` still reports hypotheses we cannot see at all.
 */
class PlatformVoiceCandidateProviderOverrunTest {
    private fun assess(
        phrases: List<String>,
        scores: FloatArray? = FloatArray(phrases.size),
        hasAdditionalAlternatives: Boolean = false,
    ) = assessPlatformVoiceCandidate(
        phrases = phrases,
        confidenceScores = scores,
        backend = OfflineSpeechEngine.PLATFORM,
        isFinal = true,
        isHomeContext = true,
        hasAdditionalAlternatives = hasAdditionalAlternatives,
    )

    @Test
    fun fourAgreeingHypothesesArePreviewedLikeThree() {
        val result = assess(List(4) { "도움말" })

        assertEquals(PlatformVoiceCandidateDisposition.PREVIEW_ONLY, result.disposition)
        assertEquals(AndroidVoiceAction.SpeakVoiceHelp, result.previewAction)
    }

    @Test
    fun aProviderMayReturnManyHypothesesAndAgreementStillDecides() {
        assertEquals(
            PlatformVoiceCandidateDisposition.PREVIEW_ONLY,
            assess(List(8) { "도움말" }).disposition,
        )
    }

    @Test
    fun theExtraHypothesisIsCheckedRatherThanIgnored() {
        // The fourth disagreeing hypothesis must still block the preview.
        val result = assess(listOf("도움말", "도움말", "도움말", "서울역으로 안내해줘"))

        assertEquals(PlatformVoiceCandidateDisposition.AMBIGUOUS, result.disposition)
        assertNull(result.previewAction)
    }

    @Test
    fun spacingDifferencesBetweenHypothesesDoNotBlockAgreement() {
        // The recogniser's spacing is not a user decision; compactVoicePhrase already absorbs it.
        assertEquals(
            PlatformVoiceCandidateDisposition.PREVIEW_ONLY,
            assess(listOf("도움말", "도움 말", "도움말", "도움  말")).disposition,
        )
    }

    @Test
    fun hypothesesWeCannotSeeStillPreventAgreement() {
        assertEquals(
            PlatformVoiceCandidateDisposition.AMBIGUOUS,
            assess(List(4) { "도움말" }, hasAdditionalAlternatives = true).disposition,
        )
    }

    @Test
    fun genuinelyBrokenFinalsAreStillRejected() {
        // An empty final carries no provider zero, so the zero-confidence exception never opens.
        assertEquals(
            PlatformVoiceCandidateDisposition.NOT_APPLICABLE,
            assess(emptyList()).disposition,
        )
        assertEquals(
            PlatformVoiceCandidateDisposition.INVALID_FINAL,
            assess(listOf("도움말", "")).disposition,
        )
        assertEquals(
            PlatformVoiceCandidateDisposition.INVALID_FINAL,
            assess(List(4) { "도움말" }, floatArrayOf(0f, 0f, 0f)).disposition,
        )
    }

    /**
     * "음성 후보를 하나로 확정하지 못했습니다" describes hypotheses that disagree. It was also said to
     * a walker who simply named a dish, which is not that situation and leaves them repeating
     * themselves more clearly to a parser that will never accept the word.
     */
    @Test
    fun sayingSomethingThatIsNotACommandIsItsOwnOutcome() {
        val result = assess(listOf("김치찌개"))

        assertEquals(PlatformVoiceCandidateDisposition.UNRECOGNIZED, result.disposition)
        assertNull(result.previewAction)
        assertNull(result.candidate)
    }

    @Test
    fun everyHypothesisFailingToParseIsUnrecognizedNotAmbiguous() {
        assertEquals(
            PlatformVoiceCandidateDisposition.UNRECOGNIZED,
            assess(listOf("김치찌개", "김치 찌개", "김치찌개요")).disposition,
        )
    }

    @Test
    fun disagreementBetweenRealCommandsStaysAmbiguous() {
        // Only here is "could not settle on one candidate" a true description.
        val result = assess(listOf("도움말", "길안내 종료"))

        assertEquals(PlatformVoiceCandidateDisposition.AMBIGUOUS, result.disposition)
    }

    @Test
    fun aParsedTopHypothesisWithAnUnparsedAlternativeIsAmbiguous() {
        // Something was understood; the alternatives just do not back it up.
        val result = assess(listOf("도움말", "명령이 아닌 문장"))

        assertEquals(PlatformVoiceCandidateDisposition.AMBIGUOUS, result.disposition)
        assertNotNull(AndroidVoiceCommand.Help)
    }
}

package kr.co.hanium.dreamup.walksafe.voice

import kr.co.hanium.dreamup.walksafe.navigation.AndroidVoiceAction
import kr.co.hanium.dreamup.walksafe.navigation.AndroidVoiceCommand
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchResult
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchVoiceState
import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.navigation.selectAndroidVoiceAction
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlatformVoiceCandidatePolicyTest {
    private fun assess(
        phrases: List<String>,
        scores: FloatArray? = FloatArray(phrases.size),
        backend: OfflineSpeechEngine? = OfflineSpeechEngine.PLATFORM,
        isFinal: Boolean = true,
        isHome: Boolean = true,
        additionalAlternatives: Boolean = false,
        allowBareIndex: Boolean = false,
        dialogState: DestinationSearchVoiceState? = null,
    ) = assessPlatformVoiceCandidate(
        phrases, scores, backend, isFinal, isHome, allowBareIndex, additionalAlternatives,
        dialogState,
    )

    @Test
    fun zeroConfidenceHelpIsOnlyAPreviewAndExistingScoredSelectorStillRejectsIt() {
        val phrases = listOf("도움말")
        val scores = floatArrayOf(0f)
        assertNull(selectAndroidVoiceAction(phrases, scores))
        val result = assess(phrases, scores)
        assertEquals(PlatformVoiceCandidateDisposition.PREVIEW_ONLY, result.disposition)
        assertEquals(AndroidVoiceAction.SpeakVoiceHelp, result.previewAction)
        assertArrayEquals(floatArrayOf(0f), scores, 0f)
    }

    @Test
    fun naturalDestinationWithoutParticleCanOnlyOpenSearchCandidates() {
        val result = assess(listOf("편의점 안내해줘"))
        assertEquals(PlatformVoiceCandidateDisposition.PREVIEW_ONLY, result.disposition)
        assertEquals(AndroidVoiceCommand.SetDestination("편의점"), result.candidate)
        assertEquals(AndroidVoiceAction.SearchDestination("편의점"), result.previewAction)
    }

    @Test
    fun allSuppliedHypothesesMustAgreeOnTheSameCanonicalCommand() {
        val result = assess(listOf("편의점 안내해줘", "편의점으로 안내해줘"))
        assertEquals(PlatformVoiceCandidateDisposition.PREVIEW_ONLY, result.disposition)
        assertEquals(AndroidVoiceAction.SearchDestination("편의점"), result.previewAction)
    }

    @Test
    fun aDifferentDestinationInAnyHypothesisPreventsPreview() {
        val result = assess(listOf("편의점 안내해줘", "서울역으로 안내해줘"))
        assertEquals(PlatformVoiceCandidateDisposition.AMBIGUOUS, result.disposition)
        assertNull(result.previewAction)
    }

    @Test
    fun anUnparsedAlternativeIsNotSilentlyDiscarded() {
        val result = assess(listOf("도움말", "명령이 아닌 문장"))
        assertEquals(PlatformVoiceCandidateDisposition.AMBIGUOUS, result.disposition)
        assertNull(result.previewAction)
    }

    @Test
    fun emptyOrMisalignedResultsAreNotPromoted() {
        assertEquals(PlatformVoiceCandidateDisposition.INVALID_FINAL, assess(listOf("")).disposition)
        assertEquals(
            PlatformVoiceCandidateDisposition.INVALID_FINAL,
            assess(listOf("도움말", "도움말"), floatArrayOf(0f)).disposition,
        )
        assertEquals(
            PlatformVoiceCandidateDisposition.INVALID_FINAL,
            assess(List(4) { "도움말" }, floatArrayOf(0f, 0f, 0f)).disposition,
        )
    }

    @Test
    fun missingUnavailableNonfiniteAndOtherScoresDoNotUseTheZeroPreviewException() {
        for (scores in listOf(null, floatArrayOf(-1f), floatArrayOf(Float.NaN),
            floatArrayOf(0.2f), floatArrayOf(0.55f), floatArrayOf(1f))) {
            assertEquals(
                PlatformVoiceCandidateDisposition.NOT_APPLICABLE,
                assess(listOf("도움말"), scores).disposition,
            )
        }
    }

    @Test
    fun partialVoskUnknownBackendAndNonHomeResultsCannotEnterPreview() {
        assertEquals(PlatformVoiceCandidateDisposition.NOT_APPLICABLE, assess(listOf("도움말"), isFinal = false).disposition)
        assertEquals(PlatformVoiceCandidateDisposition.NOT_APPLICABLE, assess(listOf("도움말"), backend = OfflineSpeechEngine.VOSK).disposition)
        assertEquals(PlatformVoiceCandidateDisposition.NOT_APPLICABLE, assess(listOf("도움말"), backend = null).disposition)
        assertEquals(PlatformVoiceCandidateDisposition.NOT_APPLICABLE, assess(listOf("도움말"), isHome = false).disposition)
    }

    @Test
    fun additionalSpanHypothesesCannotBeIgnoredWhenClaimingAgreement() {
        val result = assess(listOf("도움말"), additionalAlternatives = true)
        assertEquals(PlatformVoiceCandidateDisposition.AMBIGUOUS, result.disposition)
        assertNull(result.previewAction)
    }

    @Test
    fun navigationReportAndCandidateSelectionNeverProduceAutomaticExecutionActions() {
        for (phrase in listOf("안내 시작", "신고해", "1번 선택")) {
            val result = assess(listOf(phrase))
            assertEquals(PlatformVoiceCandidateDisposition.CONFIRMATION_REQUIRED, result.disposition)
            assertNull(result.previewAction)
        }
        val bare = assess(listOf("1번"), allowBareIndex = true)
        assertEquals(PlatformVoiceCandidateDisposition.CONFIRMATION_REQUIRED, bare.disposition)
        assertNull(bare.previewAction)
    }

    @Test
    fun highConfidenceLegacySelectionIsUnchanged() {
        val phrases = listOf("도움말")
        val scores = floatArrayOf(0.9f)
        assertEquals(PlatformVoiceCandidateDisposition.NOT_APPLICABLE, assess(phrases, scores).disposition)
        assertEquals(AndroidVoiceAction.SpeakVoiceHelp, selectAndroidVoiceAction(phrases, scores))
    }

    @Test
    fun invalidAlternativeScoreRejectsPreviewWithoutMutatingTheOriginalArray() {
        val scores = floatArrayOf(0f, Float.NaN)
        val result = assess(listOf("도움말", "도움말"), scores)
        assertEquals(PlatformVoiceCandidateDisposition.INVALID_FINAL, result.disposition)
        assertArrayEquals(floatArrayOf(0f, Float.NaN), scores, 0f)
    }

    @Test
    fun currentDialogPermitsAnAgreedNumberOnlyAsADestinationConfirmationPreview() {
        val phrases = listOf("1번", "1번 선택")
        val scores = floatArrayOf(0f, 0f)
        val result = assess(phrases, scores, allowBareIndex = true, dialogState = dialog())

        assertEquals(PlatformVoiceCandidateDisposition.PREVIEW_ONLY, result.disposition)
        assertEquals(AndroidVoiceCommand.SelectDestinationCandidate(1), result.candidate)
        assertEquals(AndroidVoiceAction.SelectDestinationCandidate(1), result.previewAction)
        assertNull(selectAndroidVoiceAction(phrases, scores))
        assertArrayEquals(floatArrayOf(0f, 0f), scores, 0f)
    }

    @Test
    fun currentDialogPermitsHearMoreOnlyWhileMoreCandidatesCanBePresented() {
        val result = assess(listOf("더 듣기", "더듣기"), dialogState = dialog())
        assertEquals(PlatformVoiceCandidateDisposition.PREVIEW_ONLY, result.disposition)
        assertEquals(AndroidVoiceAction.HearMoreDestinationCandidates, result.previewAction)

        val exhausted = assess(listOf("더 듣기"), dialogState = dialog(pageIndex = 1))
        assertEquals(PlatformVoiceCandidateDisposition.CONFIRMATION_REQUIRED, exhausted.disposition)
        assertNull(exhausted.previewAction)
    }

    @Test
    fun replayIsAvailableOnTheSameLastPageWithoutBecomingHearMore() {
        val state = dialog(pageIndex = 1)
        val replay = assess(listOf("다시 듣기", "다시듣기"), dialogState = state)
        assertEquals(PlatformVoiceCandidateDisposition.PREVIEW_ONLY, replay.disposition)
        assertEquals(AndroidVoiceAction.RepeatDestinationCandidates, replay.previewAction)
        assertEquals(1, state.pageIndex)
        assertEquals(
            PlatformVoiceCandidateDisposition.CONFIRMATION_REQUIRED,
            assess(listOf("더 듣기"), dialogState = state).disposition,
        )
        assertEquals(
            PlatformVoiceCandidateDisposition.AMBIGUOUS,
            assess(listOf("다시 듣기", "더 듣기"), dialogState = state).disposition,
        )
        assertEquals(
            PlatformVoiceCandidateDisposition.AMBIGUOUS,
            assess(listOf("다시 듣기"), additionalAlternatives = true, dialogState = state).disposition,
        )
    }

    @Test
    fun missingOrEmptyDialogNeverPromotesCandidateFollowups() {
        for (state in listOf(null, dialog(candidateCount = 0, moreResultsAvailable = true))) {
            for (phrase in listOf("1번 선택", "다시 듣기", "더 듣기")) {
                val result = assess(listOf(phrase), dialogState = state)
                assertEquals(PlatformVoiceCandidateDisposition.CONFIRMATION_REQUIRED, result.disposition)
                assertNull(result.previewAction)
            }
        }
    }

    @Test
    fun selectionMustReferToTheCurrentlyPresentedPageAndExistingCandidate() {
        for ((state, phrase) in listOf(
            dialog() to "4번 선택",
            dialog(pageIndex = 1) to "1번 선택",
            dialog(pageIndex = 1) to "5번 선택",
        )) {
            val result = assess(listOf(phrase), dialogState = state)
            assertEquals(PlatformVoiceCandidateDisposition.CONFIRMATION_REQUIRED, result.disposition)
            assertNull(result.previewAction)
        }
        val currentPage = assess(listOf("4번 선택"), dialogState = dialog(pageIndex = 1))
        assertEquals(PlatformVoiceCandidateDisposition.PREVIEW_ONLY, currentPage.disposition)
        assertEquals(AndroidVoiceAction.SelectDestinationCandidate(4), currentPage.previewAction)
    }

    @Test
    fun remoteContinuationRequiresAnExistingCurrentPage() {
        val result = assess(
            listOf("더 듣기"),
            dialogState = dialog(candidateCount = 3, moreResultsAvailable = true),
        )
        assertEquals(PlatformVoiceCandidateDisposition.PREVIEW_ONLY, result.disposition)
        assertEquals(AndroidVoiceAction.HearMoreDestinationCandidates, result.previewAction)
    }

    @Test
    fun dialogCannotBypassFinalProviderZeroAgreementOrAdditionalAlternativesChecks() {
        val state = dialog()
        for (result in listOf(
            assess(listOf("1번 선택"), isFinal = false, dialogState = state),
            assess(listOf("1번 선택"), backend = OfflineSpeechEngine.VOSK, dialogState = state),
            assess(listOf("1번 선택"), isHome = false, dialogState = state),
            assess(listOf("1번 선택"), scores = floatArrayOf(0.9f), dialogState = state),
        )) {
            assertEquals(PlatformVoiceCandidateDisposition.NOT_APPLICABLE, result.disposition)
            assertNull(result.previewAction)
        }
        for (result in listOf(
            assess(listOf("1번 선택", "2번 선택"), dialogState = state),
            assess(listOf("더 듣기", "명령이 아닌 문장"), dialogState = state),
            assess(listOf("더 듣기"), additionalAlternatives = true, dialogState = state),
        )) {
            assertEquals(PlatformVoiceCandidateDisposition.AMBIGUOUS, result.disposition)
            assertNull(result.previewAction)
        }
        val invalid = assess(
            listOf("1번 선택", "1번 선택"),
            scores = floatArrayOf(0f, Float.NaN),
            dialogState = state,
        )
        assertEquals(PlatformVoiceCandidateDisposition.INVALID_FINAL, invalid.disposition)
        assertNull(invalid.previewAction)
    }

    @Test
    fun liveDialogDoesNotPromoteRouteStartReportOrDestinationCancellation() {
        for (phrase in listOf("안내 시작", "신고해", "목적지 취소")) {
            val result = assess(listOf(phrase), dialogState = dialog())
            assertEquals(PlatformVoiceCandidateDisposition.CONFIRMATION_REQUIRED, result.disposition)
            assertNull(result.previewAction)
        }
    }

    private fun dialog(
        candidateCount: Int = 4,
        pageIndex: Int = 0,
        moreResultsAvailable: Boolean = false,
    ) = DestinationSearchVoiceState(
        query = "편의점",
        results = List(candidateCount) { index ->
            DestinationSearchResult(
                id = "candidate-$index",
                name = "후보 ${index + 1}",
                point = RoutePoint(37.0, 127.0),
                address = "테스트 주소",
                roadAddress = null,
                category = null,
                distanceM = (index + 1) * 100,
            )
        },
        pageIndex = pageIndex,
        moreResultsAvailable = moreResultsAvailable,
    )
}

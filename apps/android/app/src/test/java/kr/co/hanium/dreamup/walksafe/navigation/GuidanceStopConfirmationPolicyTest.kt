package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R17 (voice-F3, S05-12/S06-21; 05.txt:157): with a candidate dialog, a search or a walking selection open
 * during guidance, "안내 취소/중지/종료/멈춰" (no "길") is confirmed once; the same phrase again or
 * "길안내 종료/취소" inside the question ends guidance. "길안내 종료/취소" is always immediate and with no
 * dialog every stop is immediate (중지 우선). The question ends with its window or the next utterance.
 */
class GuidanceStopConfirmationPolicyTest {
    @Test
    fun stopPhrasesAreSortedByWhetherTheyNameTheGuidance() {
        for (phrase in listOf("안내 취소", "안내 중지해줘", "안내 멈춰", "안내 종료해주세요", "안내를 종료", "안내 멈춰줘")) {
            assertEquals(phrase, GuidanceStopPhrase.WITHOUT_GUIDANCE_WORD, guidanceStopPhraseOrNull(phrase))
        }
        for (phrase in listOf("길안내 종료", "길안내 취소", "길안내 취소해줘", "길안내 멈춰줘", "길안내 그만", "내비 중지", "네비 중지")) {
            assertEquals(phrase, GuidanceStopPhrase.EXPLICIT, guidanceStopPhraseOrNull(phrase))
        }
        for (phrase in listOf("취소", "검색 취소", "목적지 취소", "안내 시작", "다음 안내 알려줘", "안내 취소하지 마", "보행 종료")) {
            assertNull(phrase, guidanceStopPhraseOrNull(phrase))
        }
        // The parser itself keeps "안내 취소" as the guidance stop; the question lives in this policy.
        assertEquals(AndroidVoiceCommand.StopNavigation, parseAndroidVoiceCommand("안내 취소"))
    }

    @Test
    fun aStopWithoutTheGuidanceWordIsAskedOnceWhileADialogIsOpen() {
        val policy = GuidanceStopConfirmationPolicy()
        assertFalse(policy.consumeOpenConfirmation(1_000L))
        assertEquals(GuidanceStopDecision.CONFIRM, decide(policy, GuidanceStopPhrase.WITHOUT_GUIDANCE_WORD, open = false, nowMs = 1_000L))
        // The same stop phrase again inside the question ends guidance.
        val open = policy.consumeOpenConfirmation(5_000L)
        assertTrue(open)
        assertEquals(GuidanceStopDecision.STOP, decide(policy, GuidanceStopPhrase.WITHOUT_GUIDANCE_WORD, open, nowMs = 5_000L))
        assertFalse(policy.consumeOpenConfirmation(6_000L))
    }

    @Test
    fun theExplicitPhraseEndsGuidanceInsideTheQuestionAndWithoutIt() {
        val policy = GuidanceStopConfirmationPolicy()
        assertEquals(GuidanceStopDecision.STOP, decide(policy, GuidanceStopPhrase.EXPLICIT, open = false, nowMs = 0L))
        assertEquals(GuidanceStopDecision.CONFIRM, decide(policy, GuidanceStopPhrase.WITHOUT_GUIDANCE_WORD, open = false, nowMs = 0L))
        val open = policy.consumeOpenConfirmation(2_000L)
        assertEquals(GuidanceStopDecision.STOP, decide(policy, GuidanceStopPhrase.EXPLICIT, open, nowMs = 2_000L))
    }

    @Test
    fun withoutADialogOrWithoutGuidanceEveryStopIsImmediate() {
        val policy = GuidanceStopConfirmationPolicy()
        assertEquals(
            GuidanceStopDecision.STOP,
            policy.decide(GuidanceStopPhrase.WITHOUT_GUIDANCE_WORD, false, dialogOpen = false, guidanceRunning = true, nowMs = 0L),
        )
        assertEquals(
            GuidanceStopDecision.STOP,
            policy.decide(GuidanceStopPhrase.WITHOUT_GUIDANCE_WORD, false, dialogOpen = true, guidanceRunning = false, nowMs = 0L),
        )
        assertFalse(policy.consumeOpenConfirmation(1L))
    }

    @Test
    fun anotherUtteranceOrTheTimeoutEndsTheQuestion() {
        val policy = GuidanceStopConfirmationPolicy()
        assertEquals(GuidanceStopDecision.CONFIRM, decide(policy, GuidanceStopPhrase.WITHOUT_GUIDANCE_WORD, open = false, nowMs = 0L))
        // Another command ("취소", a number, ...) consumes the question without a stop.
        assertTrue(policy.consumeOpenConfirmation(3_000L))
        // So a later stop phrase is asked again rather than ending guidance.
        val reopened = policy.consumeOpenConfirmation(4_000L)
        assertFalse(reopened)
        assertEquals(GuidanceStopDecision.CONFIRM, decide(policy, GuidanceStopPhrase.WITHOUT_GUIDANCE_WORD, reopened, nowMs = 4_000L))
        // Past the window the question is gone as well.
        val late = policy.consumeOpenConfirmation(4_000L + GUIDANCE_STOP_CONFIRMATION_WINDOW_MS + 1L)
        assertFalse(late)
        assertEquals(GuidanceStopDecision.CONFIRM, decide(policy, GuidanceStopPhrase.WITHOUT_GUIDANCE_WORD, late, nowMs = 40_000L))
        policy.invalidate()
        assertFalse(policy.consumeOpenConfirmation(40_001L))
        // A clock going backwards never counts as an answer inside the window.
        decide(policy, GuidanceStopPhrase.WITHOUT_GUIDANCE_WORD, open = false, nowMs = 50_000L)
        assertFalse(policy.consumeOpenConfirmation(49_999L))
    }

    @Test
    fun answerWindowStartsWhenTheQuestionFinishesSpeakingAndInvalidationBlocksOldAnswer() {
        val policy = GuidanceStopConfirmationPolicy()
        assertEquals(GuidanceStopDecision.CONFIRM, decide(policy, GuidanceStopPhrase.WITHOUT_GUIDANCE_WORD, false, 1_000L))
        policy.promptDelivered(50_000L)
        assertTrue(policy.consumeOpenConfirmation(50_001L))
        assertEquals(GuidanceStopDecision.CONFIRM, decide(policy, GuidanceStopPhrase.WITHOUT_GUIDANCE_WORD, false, 51_000L))
        policy.invalidate()
        policy.promptDelivered(52_000L)
        assertFalse(policy.consumeOpenConfirmation(52_001L))
    }

    @Test
    fun theQuestionNamesBothAnswersInParsedWords() {
        assertTrue(GUIDANCE_STOP_CONFIRMATION_PROMPT_KO.startsWith("길안내를 끝낼까요?"))
        assertEquals(AndroidVoiceCommand.StopNavigation, parseAndroidVoiceCommand("길안내 종료"))
        assertEquals(AndroidVoiceCommand.CancelDialog, parseAndroidVoiceCommand("취소"))
        assertTrue(GUIDANCE_STOP_CONFIRMATION_PROMPT_KO.contains("끝내려면 길안내 종료"))
        assertTrue(GUIDANCE_STOP_CONFIRMATION_PROMPT_KO.contains("후보 선택만 닫으려면 취소라고"))
    }

    private fun decide(
        policy: GuidanceStopConfirmationPolicy,
        phrase: GuidanceStopPhrase,
        open: Boolean,
        nowMs: Long,
    ): GuidanceStopDecision = policy.decide(phrase, open, dialogOpen = true, guidanceRunning = true, nowMs = nowMs)
}

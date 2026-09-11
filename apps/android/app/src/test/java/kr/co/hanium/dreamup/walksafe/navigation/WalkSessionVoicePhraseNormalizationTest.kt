package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fifteen navigation commands strip punctuation and spacing before matching; the five walk
 * controls compared the recognizer's raw string. So "보행 일시정지" paused the walk and
 * "보행 일시 정지" did nothing — a difference the speaker cannot hear themselves make.
 *
 * Both paths now normalize the same way. What stays different is the confidence bar: these five
 * still require 0.80 against 0.55 elsewhere, because a misheard pause costs a walk.
 */
class WalkSessionVoicePhraseNormalizationTest {
    private val epoch = WalkRuntimeEpoch("walk-voice-normalization", 1L)

    private fun decide(phrase: String, state: WalkSessionState) =
        WalkSessionVoiceControlPolicy().evaluate(
            phrase = phrase,
            confidence = 0.95f,
            state = state,
            epoch = epoch,
        ).action

    @Test
    fun spacingDoesNotDecideAPause() {
        listOf("보행 일시정지", "보행일시정지", "보행 일시 정지", "보행 일시정지.").forEach { phrase ->
            assertEquals(phrase, WalkSessionVoiceAction.PAUSE, decide(phrase, WalkSessionState.ACTIVE))
        }
    }

    @Test
    fun spacingDoesNotDecideAResume() {
        listOf("보행 재개", "보행재개", "보행  재개", "보행 재개!").forEach { phrase ->
            assertEquals(phrase, WalkSessionVoiceAction.RESUME, decide(phrase, WalkSessionState.PAUSED))
        }
    }

    @Test
    fun endStillTakesTwoTurns() {
        val policy = WalkSessionVoiceControlPolicy()
        fun say(phrase: String) = policy.evaluate(
            phrase = phrase,
            confidence = 0.95f,
            state = WalkSessionState.ACTIVE,
            epoch = epoch,
        ).action

        assertEquals(WalkSessionVoiceAction.REQUEST_END, say("보행 종료."))
        assertEquals(WalkSessionVoiceAction.CONFIRM_END, say("보행종료 확인"))
    }

    @Test
    fun endingIsNeverMistakenForConfirmingIt() {
        val policy = WalkSessionVoiceControlPolicy()

        // Without the request, a bare confirmation does nothing.
        assertEquals(
            WalkSessionVoiceAction.NO_OP,
            policy.evaluate("보행 종료 확인", 0.95f, WalkSessionState.ACTIVE, epoch).action,
        )
    }

    @Test
    fun theConfidenceBarStaysHigherThanForNavigation() {
        // 0.55 is enough to hear a route out; it is not enough to end a walk.
        assertEquals(
            WalkSessionVoiceAction.NO_OP,
            WalkSessionVoiceControlPolicy()
                .evaluate("보행 일시정지", 0.60f, WalkSessionState.ACTIVE, epoch)
                .action,
        )
    }
}

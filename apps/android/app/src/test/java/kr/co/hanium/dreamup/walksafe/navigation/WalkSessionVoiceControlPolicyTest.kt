package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WalkSessionVoiceControlPolicyTest {
    @Test
    fun exactPauseAndResumeRequireTheirMatchingCurrentState() {
        val policy = WalkSessionVoiceControlPolicy()

        assertAction(
            WalkSessionVoiceAction.PAUSE,
            policy.evaluate("보행 일시정지", 0.90f, WalkSessionState.ACTIVE, EPOCH_1),
        )
        assertAction(
            WalkSessionVoiceAction.NO_OP,
            policy.evaluate("보행 일시정지", 0.90f, WalkSessionState.PAUSED, EPOCH_1),
        )
        assertAction(
            WalkSessionVoiceAction.RESUME,
            policy.evaluate("보행 재개", 0.90f, WalkSessionState.PAUSED, EPOCH_1),
        )
        assertAction(
            WalkSessionVoiceAction.NO_OP,
            policy.evaluate("보행 재개", 0.90f, WalkSessionState.ACTIVE, EPOCH_1),
        )
    }

    @Test
    fun endRequiresASeparateExactConfirmationBoundToTheSameEpoch() {
        val policy = WalkSessionVoiceControlPolicy()

        val request = policy.evaluate("보행 종료", 0.95f, WalkSessionState.ACTIVE, EPOCH_1)
        assertEquals(WalkSessionVoiceAction.REQUEST_END, request.action)
        assertEquals(EPOCH_1, requireNotNull(request.endToken).epoch)

        val confirmed = policy.evaluate("보행 종료 확인", 0.95f, WalkSessionState.ACTIVE, EPOCH_1)
        assertEquals(WalkSessionVoiceAction.CONFIRM_END, confirmed.action)
        assertEquals(request.endToken, confirmed.endToken)
        assertAction(
            WalkSessionVoiceAction.NO_OP,
            policy.evaluate("보행 종료 확인", 0.95f, WalkSessionState.ACTIVE, EPOCH_1),
        )
    }

    @Test
    fun cancellationConsumesThePendingEndWithoutConfirmingIt() {
        val policy = WalkSessionVoiceControlPolicy()
        val request = policy.evaluate("보행 종료", 0.90f, WalkSessionState.PAUSED, EPOCH_1)

        val cancelled = policy.evaluate("보행 종료 취소", 0.90f, WalkSessionState.PAUSED, EPOCH_1)

        assertEquals(WalkSessionVoiceAction.CANCEL_END, cancelled.action)
        assertEquals(request.endToken, cancelled.endToken)
        assertAction(
            WalkSessionVoiceAction.NO_OP,
            policy.evaluate("보행 종료 확인", 0.90f, WalkSessionState.PAUSED, EPOCH_1),
        )
    }

    @Test
    fun explicitInvalidationPreventsAStaleEndConfirmation() {
        val policy = WalkSessionVoiceControlPolicy()
        policy.evaluate("보행 종료", 0.90f, WalkSessionState.ACTIVE, EPOCH_1)

        policy.invalidatePendingEnd()

        assertAction(
            WalkSessionVoiceAction.NO_OP,
            policy.evaluate("보행 종료 확인", 0.90f, WalkSessionState.ACTIVE, EPOCH_1),
        )
    }

    @Test
    fun staleEpochAndWrongSessionStatesInvalidatePendingEnd() {
        val policy = WalkSessionVoiceControlPolicy()
        policy.evaluate("보행 종료", 0.90f, WalkSessionState.ACTIVE, EPOCH_1)

        assertAction(
            WalkSessionVoiceAction.NO_OP,
            policy.evaluate("보행 종료 확인", 0.90f, WalkSessionState.ACTIVE, EPOCH_2),
        )
        assertAction(
            WalkSessionVoiceAction.NO_OP,
            policy.evaluate("보행 종료 확인", 0.90f, WalkSessionState.ACTIVE, EPOCH_1),
        )

        policy.evaluate("보행 종료", 0.90f, WalkSessionState.ACTIVE, EPOCH_2)
        assertAction(
            WalkSessionVoiceAction.NO_OP,
            policy.evaluate("보행 종료 확인", 0.90f, WalkSessionState.SAFE_STOP, EPOCH_2),
        )
        assertAction(
            WalkSessionVoiceAction.NO_OP,
            policy.evaluate("보행 종료 확인", 0.90f, WalkSessionState.ACTIVE, EPOCH_2),
        )
    }

    @Test
    fun negatedInexactOrLowConfidencePhrasesAreNoOp() {
        val policy = WalkSessionVoiceControlPolicy()
        listOf(
            "보행 종료하지 마" to 0.99f,
            "보행 종료" to 0.79f,
            "보행 종료" to Float.NaN,
            "보행 종료" to 1.01f,
        ).forEach { (phrase, confidence) ->
            val decision = policy.evaluate(phrase, confidence, WalkSessionState.ACTIVE, EPOCH_1)
            assertAction(WalkSessionVoiceAction.NO_OP, decision)
        }
        assertAction(
            WalkSessionVoiceAction.REQUEST_END,
            policy.evaluate("보행 종료", 0.80f, WalkSessionState.ACTIVE, EPOCH_1),
        )
    }

    @Test
    fun surroundingSpaceIsNotAUserDecision() {
        // These three were asserted NO_OP while the navigation commands stripped spacing before
        // matching, so one command set answered to the recognizer's formatting and the other did
        // not. See WalkSessionVoicePhraseNormalizationTest.
        listOf(" 보행 종료", "보행 종료 ", "보행종료").forEach { phrase ->
            val policy = WalkSessionVoiceControlPolicy()
            assertAction(
                WalkSessionVoiceAction.REQUEST_END,
                policy.evaluate(phrase, 0.99f, WalkSessionState.ACTIVE, EPOCH_1),
            )
        }
    }

    @Test
    fun readySafeStopAndEndedAlwaysProduceNoOp() {
        listOf(WalkSessionState.READY, WalkSessionState.SAFE_STOP, WalkSessionState.ENDED)
            .forEach { state ->
                val policy = WalkSessionVoiceControlPolicy()
                assertAction(
                    WalkSessionVoiceAction.NO_OP,
                    policy.evaluate("보행 종료", 0.99f, state, EPOCH_1),
                )
            }
    }

    private fun assertAction(
        expected: WalkSessionVoiceAction,
        decision: WalkSessionVoiceDecision,
    ) {
        assertEquals(expected, decision.action)
        if (expected == WalkSessionVoiceAction.NO_OP) assertNull(decision.endToken)
    }

    private companion object {
        val EPOCH_1 = WalkRuntimeEpoch("walk-voice", 1L)
        val EPOCH_2 = WalkRuntimeEpoch("walk-voice", 2L)
    }
}

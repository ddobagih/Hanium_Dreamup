package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState

internal enum class WalkSessionVoiceAction {
    PAUSE,
    RESUME,
    REQUEST_END,
    CONFIRM_END,
    CANCEL_END,
    NO_OP,
}

/** voice-F7: the whole-phrase walk session commands below; they carry no free words. */
internal fun isWalkSessionVoicePhrase(phrase: String): Boolean =
    compactVoicePhrase(phrase) in WalkSessionVoiceControlPolicy.PHRASES

internal data class PendingVoiceEndToken(
    val epoch: WalkRuntimeEpoch,
)

internal data class WalkSessionVoiceDecision(
    val action: WalkSessionVoiceAction,
    val endToken: PendingVoiceEndToken? = null,
)

/** Whole-command, confidence-gated voice policy with shared recognizer phrase normalization. */
internal class WalkSessionVoiceControlPolicy(
    private val minimumConfidence: Float = DEFAULT_MINIMUM_CONFIDENCE,
) {
    private var pendingEnd: PendingVoiceEndToken? = null

    init {
        require(minimumConfidence.isFinite() && minimumConfidence in 0f..1f)
    }

    @Synchronized
    fun evaluate(
        phrase: String,
        confidence: Float,
        state: WalkSessionState,
        epoch: WalkRuntimeEpoch,
    ): WalkSessionVoiceDecision {
        if (pendingEnd?.epoch?.let { it != epoch } == true) pendingEnd = null
        if (state !in CONTROLLABLE_STATES) {
            pendingEnd = null
            return NO_OP_DECISION
        }
        if (!acceptsConfidence(confidence)) {
            return NO_OP_DECISION
        }
        return when (compactVoicePhrase(phrase)) {
            PAUSE_PHRASE -> if (state == WalkSessionState.ACTIVE) {
                pendingEnd = null
                WalkSessionVoiceDecision(WalkSessionVoiceAction.PAUSE)
            } else {
                NO_OP_DECISION
            }

            RESUME_PHRASE -> if (state == WalkSessionState.PAUSED) {
                pendingEnd = null
                WalkSessionVoiceDecision(WalkSessionVoiceAction.RESUME)
            } else {
                NO_OP_DECISION
            }

            REQUEST_END_PHRASE -> PendingVoiceEndToken(epoch).let { token ->
                pendingEnd = token
                WalkSessionVoiceDecision(WalkSessionVoiceAction.REQUEST_END, token)
            }

            CONFIRM_END_PHRASE -> consumePending(epoch, WalkSessionVoiceAction.CONFIRM_END)
            CANCEL_END_PHRASE -> consumePending(epoch, WalkSessionVoiceAction.CANCEL_END)
            else -> NO_OP_DECISION
        }
    }

    @Synchronized
    fun invalidatePendingEnd() {
        pendingEnd = null
    }

    fun acceptsConfidence(confidence: Float): Boolean =
        confidence.isFinite() && confidence in minimumConfidence..1f

    private fun consumePending(
        epoch: WalkRuntimeEpoch,
        action: WalkSessionVoiceAction,
    ): WalkSessionVoiceDecision {
        val token = pendingEnd?.takeIf { it.epoch == epoch } ?: return NO_OP_DECISION
        pendingEnd = null
        return WalkSessionVoiceDecision(action, token)
    }

    internal companion object {
        private const val DEFAULT_MINIMUM_CONFIDENCE = 0.80f
        private const val PAUSE_PHRASE = "보행일시정지"
        private const val RESUME_PHRASE = "보행재개"
        private const val REQUEST_END_PHRASE = "보행종료"
        private const val CONFIRM_END_PHRASE = "보행종료확인"
        private const val CANCEL_END_PHRASE = "보행종료취소"
        val PHRASES = setOf(PAUSE_PHRASE, RESUME_PHRASE, REQUEST_END_PHRASE, CONFIRM_END_PHRASE, CANCEL_END_PHRASE)
        private val CONTROLLABLE_STATES = setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED)
        private val NO_OP_DECISION = WalkSessionVoiceDecision(WalkSessionVoiceAction.NO_OP)
    }
}

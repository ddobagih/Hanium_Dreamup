package kr.co.hanium.dreamup.walksafe.feedback

import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth

data class FeedbackPolicyConfig(
    val perTrackIntervalMs: Long = 2_500L,
    val globalIntervalMs: Long = 700L,
    val riskNavigationCooldownMs: Long = 2_500L,
    val clearAfterSafeFrames: Int = 2,
    val clearAfterMissingMs: Long = 1_500L,
    val minStableFrames: Int = 3,
    val minStableMs: Long = 700L,
    val minConfidence: Float = 0.55f,
)

data class FeedbackCandidate(
    val trackId: String,
    val message: String,
    val level: MessageLevel,
    val source: DepthSource,
    val confidence: Float,
    val timestampMs: Long,
    val trackAgeFrames: Int,
    val trackStableMs: Long,
    val stale: Boolean,
) {
    val alertableLevel: Boolean
        get() = level == MessageLevel.CAUTION || level == MessageLevel.WARNING || level == MessageLevel.STOP
}

data class FeedbackAction(
    val trackId: String,
    val message: String,
    val level: MessageLevel,
    val vibrationPatternMs: LongArray?,
    val reason: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FeedbackAction) return false
        return trackId == other.trackId &&
            message == other.message &&
            level == other.level &&
            vibrationPatternMs.contentEquals(other.vibrationPatternMs) &&
            reason == other.reason
    }

    override fun hashCode(): Int {
        var result = trackId.hashCode()
        result = 31 * result + message.hashCode()
        result = 31 * result + level.hashCode()
        result = 31 * result + (vibrationPatternMs?.contentHashCode() ?: 0)
        result = 31 * result + reason.hashCode()
        return result
    }
}

class WalkSafeFeedbackPolicy(
    private val config: FeedbackPolicyConfig = FeedbackPolicyConfig(),
) {
    private val lastTrackEmitAt = mutableMapOf<String, Long>()
    private var lastGlobalEmitAt: Long? = null
    private var lastRiskEmitAt: Long? = null
    private var lastCandidateSeenAt: Long? = null
    private var consecutiveSafeFrames = 0

    fun evaluate(
        candidate: FeedbackCandidate?,
        deviceGateAllowsAlerts: Boolean,
        nowMs: Long,
    ): FeedbackAction? {
        if (!deviceGateAllowsAlerts) {
            clearActiveState()
            return null
        }
        if (candidate == null || candidate.stale) {
            markMissing(nowMs)
            return null
        }
        lastCandidateSeenAt = nowMs

        if (!candidate.source.metric || candidate.confidence < config.minConfidence) {
            markSafe()
            return null
        }
        if (!candidate.alertableLevel || candidate.message.isBlank()) {
            markSafe()
            return null
        }
        if (candidate.trackAgeFrames < config.minStableFrames && candidate.trackStableMs < config.minStableMs) {
            return null
        }

        consecutiveSafeFrames = 0
        val lastTrackAt = lastTrackEmitAt[candidate.trackId]
        if (lastTrackAt != null && nowMs - lastTrackAt < config.perTrackIntervalMs) return null
        val lastGlobalAt = lastGlobalEmitAt
        if (lastGlobalAt != null && nowMs - lastGlobalAt < config.globalIntervalMs) return null

        lastTrackEmitAt[candidate.trackId] = nowMs
        lastGlobalEmitAt = nowMs
        lastRiskEmitAt = nowMs
        return FeedbackAction(
            trackId = candidate.trackId,
            message = candidate.message,
            level = candidate.level,
            vibrationPatternMs = VibrationPatterns.forLevel(candidate.level),
            reason = "fresh_metric_depth_alert",
        )
    }

    fun canSpeakNavigation(nowMs: Long): Boolean {
        val lastGlobalAt = lastGlobalEmitAt ?: return true
        val lastRiskAt = lastRiskEmitAt
        if (lastRiskAt != null && nowMs - lastRiskAt < config.riskNavigationCooldownMs) return false
        return nowMs - lastGlobalAt >= config.globalIntervalMs
    }

    private fun markSafe() {
        consecutiveSafeFrames += 1
        if (consecutiveSafeFrames >= config.clearAfterSafeFrames) {
            clearActiveState()
        }
    }

    private fun markMissing(nowMs: Long) {
        val lastSeen = lastCandidateSeenAt
        if (lastSeen == null || nowMs - lastSeen >= config.clearAfterMissingMs) {
            clearActiveState()
        }
    }

    private fun clearActiveState() {
        lastTrackEmitAt.clear()
        consecutiveSafeFrames = 0
        lastCandidateSeenAt = null
    }
}

object VibrationPatterns {
    fun forLevel(level: MessageLevel): LongArray? {
        return when (level) {
            MessageLevel.STOP -> longArrayOf(0L, 180L, 80L, 240L, 80L, 320L)
            MessageLevel.WARNING,
            MessageLevel.CAUTION,
            MessageLevel.AWARE,
            MessageLevel.INFO,
            MessageLevel.NONE,
            -> null
        }
    }
}

fun TrackedObjectDepth.toFeedbackCandidate(stale: Boolean = false): FeedbackCandidate? {
    val message = userFacing.message ?: return null
    return FeedbackCandidate(
        trackId = trackId,
        message = message,
        level = userFacing.messageLevel,
        source = source,
        confidence = confidence.finalScore,
        timestampMs = timestampMs,
        trackAgeFrames = trackAgeFrames,
        trackStableMs = trackStableMs,
        stale = stale,
    )
}

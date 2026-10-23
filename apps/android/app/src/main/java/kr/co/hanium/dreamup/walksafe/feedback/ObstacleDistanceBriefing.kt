package kr.co.hanium.dreamup.walksafe.feedback

import java.util.Locale
import kr.co.hanium.dreamup.walksafe.depth.*

/** Optional continuation of an already delivered warning, with a fresh source lease of its own. */
data class ObstacleDistanceSpeech(
    val message: String,
    val validUntilMs: Long,
    val isStillValidAtStart: () -> Boolean,
)

/** No fallback to risk/Z/median/predicted distance, and no new risk or confidence thresholds. */
internal fun TrackedObjectDepth.distanceBriefingFor(action: FeedbackAction, nowMs: Long): String? {
    val candidate = toFeedbackCandidate() ?: return null
    if (candidate.deliveryKey != action.deliveryKey ||
        action.level !in setOf(MessageLevel.WARNING, MessageLevel.STOP) ||
        nowMs > candidate.validUntilMs ||
        source !in setOf(DepthSource.ARCORE_RAW_DEPTH, DepthSource.ARCORE_FULL_DEPTH) ||
        depthAvailability != DepthAvailability.MEASURED || prediction != null ||
        !confidence.finalScore.isFinite() || confidence.finalScore < (defaultMetricSourceMinConfidence()[source] ?: 1f)
    ) return null
    val range = measuredSurfaceRangeM?.takeIf { it.isFinite() && it > 0f } ?: return null
    // One decimal is speech formatting, not a claim of 10 cm sensor accuracy.
    return "카메라에서 감지된 표면까지 약 ${String.format(Locale.ROOT, "%.1f", range)}미터입니다."
}

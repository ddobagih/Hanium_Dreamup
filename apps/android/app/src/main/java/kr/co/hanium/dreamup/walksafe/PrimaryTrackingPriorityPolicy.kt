package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth

/** Scheduling hints only. A frozen measurement is never relabelled as current-frame geometry. */
internal object PrimaryTrackingPriorityPolicy {
    fun sourceIndices(detections: List<DetectionCandidate>, outputs: List<TrackedObjectDepth>,
                      sourceFrameId: Long, sourceAgeMs: Long?): List<Int> {
        if (sourceFrameId <= 0L || sourceAgeMs == null || sourceAgeMs !in 0L..800L) return emptyList()
        return outputs.filter { output ->
            output.frameId == sourceFrameId && output.timestampMs == sourceFrameId / 1_000_000L &&
                output.source.metric && output.riskDistanceM?.let { it.isFinite() && it > 0f } == true &&
                output.walkingObstacleCandidate && output.confidence.hardGate > 0f &&
                output.confidence.freshnessQuality > 0f && output.confidence.finalScore >= .55f &&
                output.userFacing.messageLevel in setOf(MessageLevel.STOP, MessageLevel.WARNING, MessageLevel.CAUTION)
        }.sortedWith(compareByDescending<TrackedObjectDepth> { it.userFacing.messageLevel.ordinal }
            .thenBy { it.timeToCollisionMs?.takeIf { ttc -> ttc > 0L } ?: Long.MAX_VALUE }
            .thenBy { it.riskDistanceM }).mapNotNull { output ->
                // Ambiguous duplicate detections do not justify guessing which original index was measured.
                detections.indices.singleOrNull { index -> detections[index].className == output.className &&
                    detections[index].bboxNorm == output.bboxNorm }
            }.distinct()
    }
}

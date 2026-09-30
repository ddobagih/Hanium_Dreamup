package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.depth.sameAssociationClass
import kr.co.hanium.dreamup.walksafe.inference.WalkMateClassPolicy
import kr.co.hanium.dreamup.walksafe.navigation.CrosswalkReferencePolicy

/** Scheduling hints only. A frozen measurement is never relabelled as current-frame geometry. */
internal object PrimaryTrackingPriorityPolicy {
    /** Measured risks of the source capture, most urgent first. */
    fun sourceIndices(detections: List<DetectionCandidate>, outputs: List<TrackedObjectDepth>,
                      sourceFrameId: Long, sourceAgeMs: Long?): List<Int> {
        if (sourceFrameId <= 0L || sourceAgeMs == null || sourceAgeMs !in 0L..800L) return emptyList()
        return sameCapture(outputs, sourceFrameId).filter { output ->
            hasMetricDistance(output) &&
                output.walkingObstacleCandidate && output.confidence.hardGate > 0f &&
                output.confidence.freshnessQuality > 0f && output.confidence.finalScore >= .55f &&
                output.userFacing.messageLevel in setOf(MessageLevel.STOP, MessageLevel.WARNING, MessageLevel.CAUTION)
        }.sortedWith(compareByDescending<TrackedObjectDepth> { it.userFacing.messageLevel.ordinal }
            .thenBy { it.timeToCollisionMs?.takeIf { ttc -> ttc > 0L } ?: Long.MAX_VALUE }
            .thenBy { it.riskDistanceM }).mapNotNull { output -> sourceIndexOf(detections, output) }.distinct()
    }

    /**
     * S10-21: tracking schedule = every measured risk ([sourceIndices]), then obstacles in the walking
     * corridor without a metric distance. Those are "distance uncertain", not safe; they get no distance
     * or TTC wording (they have none) and precede only the tracker's screen-geometry ranking.
     */
    fun schedulingIndices(detections: List<DetectionCandidate>, outputs: List<TrackedObjectDepth>,
                          sourceFrameId: Long, sourceAgeMs: Long?): List<Int> {
        val measured = sourceIndices(detections, outputs, sourceFrameId, sourceAgeMs)
        if (sourceFrameId <= 0L || sourceAgeMs == null || sourceAgeMs !in 0L..800L) return measured
        val distanceUncertain = sameCapture(outputs, sourceFrameId).filter { output ->
            !hasMetricDistance(output) && output.walkingObstacleCandidate &&
                output.confidence.corridorQuality >= 1f && output.confidence.freshnessQuality > 0f &&
                isObstacleClass(output.className)
        }.sortedByDescending { it.detectionConfidence }.mapNotNull { output -> sourceIndexOf(detections, output) }
        return (measured + distanceUncertain).distinct()
    }

    private fun sameCapture(outputs: List<TrackedObjectDepth>, sourceFrameId: Long): List<TrackedObjectDepth> =
        outputs.filter { output -> output.frameId == sourceFrameId && output.timestampMs == sourceFrameId / 1_000_000L }

    /** The pipeline's candidate index when it names the same class; exact box equality only as a fallback. */
    private fun sourceIndexOf(detections: List<DetectionCandidate>, output: TrackedObjectDepth): Int? {
        output.sourceDetectionIndex
            ?.takeIf { it in detections.indices && sameAssociationClass(detections[it].className, output.className) }
            ?.let { return it }
        // Ambiguous duplicate detections do not justify guessing which original index was measured.
        return detections.indices.singleOrNull { index -> detections[index].className == output.className &&
            detections[index].bboxNorm == output.bboxNorm }
    }

    private fun hasMetricDistance(output: TrackedObjectDepth): Boolean =
        output.source.metric && output.riskDistanceM?.let { it.isFinite() && it > 0f } == true

    private fun isObstacleClass(className: String): Boolean =
        !CrosswalkReferencePolicy.isCrosswalkDetectorClass(className) &&
            !WalkMateClassPolicy.isTrafficLightClass(className) &&
            !WalkMateClassPolicy.isTactileClass(className)
}

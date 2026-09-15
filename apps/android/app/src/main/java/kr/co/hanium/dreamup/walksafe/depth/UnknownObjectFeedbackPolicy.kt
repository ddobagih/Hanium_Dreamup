package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import java.util.Collections

/** Frozen source evidence; these outputs are never relabelled as current-camera geometry. */
class UnknownObjectFeedbackBatch internal constructor(
    val sourceFrameId: Long,
    val sourceTimestampMs: Long,
    val sourceCapturedAtElapsedRealtimeNs: Long,
    val completedAtElapsedRealtimeMs: Long,
    val sourceEpoch: WalkRuntimeEpoch,
    outputs: List<TrackedObjectDepth>,
) {
    val outputs: List<TrackedObjectDepth> = Collections.unmodifiableList(outputs.map { output ->
        output.copy(polygonNorm = Collections.unmodifiableList(output.polygonNorm.toList()))
    })
    val validUntilElapsedRealtimeMs: Long =
        sourceCapturedAtElapsedRealtimeNs / 1_000_000L + UnknownObjectFeedbackPolicy.MAX_SOURCE_AGE_MS

    fun isFreshAt(nowElapsedRealtimeMs: Long): Boolean =
        nowElapsedRealtimeMs >= completedAtElapsedRealtimeMs &&
            nowElapsedRealtimeMs <= validUntilElapsedRealtimeMs

    /** Reused depth may retain verified regions, but never refresh their original source lease. */
    fun retainRegions(retainedTrackIds: Set<String>, nowMs: Long): UnknownObjectFeedbackBatch? {
        if (!isFreshAt(nowMs)) return null
        val retained = outputs.filter { it.trackId in retainedTrackIds }
        if (retained.isEmpty()) return null
        return UnknownObjectFeedbackBatch(
            sourceFrameId, sourceTimestampMs, sourceCapturedAtElapsedRealtimeNs,
            completedAtElapsedRealtimeMs, sourceEpoch, retained,
        )
    }
}

/**
 * Admits an independently captured unnamed obstacle into the existing shared alert queue.
 * Source freshness and speech admission use elapsed time; motion uses the source frame clock.
 * Delivery cooldown/priority/terminal ownership stays with WalkSafeFeedbackPolicy and the actuator.
 */
class UnknownObjectFeedbackPolicy(stepLengthM: Float = 0.65f) {
    private val messages = MessagePolicy(
        stepLengthM = stepLengthM,
        config = MessagePolicyConfig(rateLimit = MessageRateLimitConfig(0L, 0L, 0L)),
    )

    fun admit(
        outputs: List<TrackedObjectDepth>,
        sourceFrameId: Long,
        sourceTimestampMs: Long,
        sourceCapturedAtElapsedRealtimeNs: Long,
        completedAtElapsedRealtimeMs: Long,
        sourceEpoch: WalkRuntimeEpoch,
        currentEpoch: WalkRuntimeEpoch?,
        nowElapsedRealtimeMs: Long,
    ): UnknownObjectFeedbackBatch? {
        val capturedAtMs = sourceCapturedAtElapsedRealtimeNs / 1_000_000L
        if (sourceEpoch != currentEpoch || sourceFrameId <= 0L || sourceTimestampMs < 0L ||
            sourceFrameId / 1_000_000L != sourceTimestampMs ||
            sourceCapturedAtElapsedRealtimeNs < 0L ||
            completedAtElapsedRealtimeMs < capturedAtMs || completedAtElapsedRealtimeMs > nowElapsedRealtimeMs ||
            nowElapsedRealtimeMs - capturedAtMs !in 0L..MAX_SOURCE_AGE_MS
        ) return null
        val admitted = outputs.filter { output ->
            output.className == CLASS_NAME && output.trackId.isNotBlank() &&
                output.frameId == sourceFrameId && output.timestampMs == sourceTimestampMs &&
                output.walkingObstacleCandidate && hasMeasuredDistance(output) &&
                output.rayDistanceM?.let { it.isFinite() && it > 0f && it <= 3f } == true
        }.distinctBy(TrackedObjectDepth::trackId).map { output ->
            // A name-free proposal carries no spoken side/category. Keep only measured attribution
            // of actual object approach or user approach to a stationary object.
            val motion = output.motionEstimate
            val attributed = motion.isCurrentMeasuredMotion(sourceTimestampMs) && when (output.objectMotion) {
                ObjectMotion.OBJECT_APPROACHING -> motion.direction == ObjectMovementDirection.TOWARD_USER &&
                    (motion.relativeClosingSpeedMps ?: 0f) >= 0.25f
                ObjectMotion.USER_APPROACHING_STATIONARY -> motion.direction == ObjectMovementDirection.STATIONARY
                ObjectMotion.UNKNOWN -> false
            }
            val safeMotion = if (attributed) motion else ObjectMotionEstimate()
            val safeObjectMotion = if (attributed) output.objectMotion else ObjectMotion.UNKNOWN
            output.copy(
                userFacing = messages.buildUserFacing(
                    MetricDepthDecision(
                        className = CLASS_NAME,
                        source = output.source,
                        riskDistanceM = output.riskDistanceM,
                        trend = output.trend,
                        confidenceFinal = output.confidence.finalScore,
                        trackKey = output.trackId,
                        timeToCollisionMs = output.timeToCollisionMs,
                        objectMotion = safeObjectMotion,
                        motionEstimate = safeMotion,
                    ),
                    nowMs = sourceTimestampMs,
                ),
            )
        }
        if (admitted.isEmpty()) return null
        return UnknownObjectFeedbackBatch(
            sourceFrameId, sourceTimestampMs, sourceCapturedAtElapsedRealtimeNs,
            completedAtElapsedRealtimeMs, sourceEpoch, admitted,
        )
    }

    companion object {
        /** Keep independent leases separate when one capture contains both new and reused depth. */
        fun mergeRetainedRegions(
            previous: List<UnknownObjectFeedbackBatch>,
            fresh: UnknownObjectFeedbackBatch?,
            retainedTrackIds: Set<String>,
            currentEpoch: WalkRuntimeEpoch,
            nowMs: Long,
        ): List<UnknownObjectFeedbackBatch> {
            val current = fresh?.takeIf { it.sourceEpoch == currentEpoch && it.isFreshAt(nowMs) }
            val remaining = retainedTrackIds.toMutableSet().apply {
                current?.outputs?.forEach { remove(it.trackId) }
            }
            val retained = previous.sortedByDescending { it.sourceCapturedAtElapsedRealtimeNs }
                .mapNotNull { batch ->
                    if (batch.sourceEpoch != currentEpoch) return@mapNotNull null
                    batch.retainRegions(remaining, nowMs)?.also { subset ->
                        subset.outputs.forEach { remaining.remove(it.trackId) }
                    }
                }
            return listOfNotNull(current) + retained
        }

        internal fun hasMeasuredDistance(output: TrackedObjectDepth): Boolean {
            val minimumSamples = when (output.source) {
                DepthSource.ARCORE_RAW_DEPTH -> 30
                DepthSource.ARCORE_FULL_DEPTH -> 50
                else -> return false
            }
            return output.depthAvailability == DepthAvailability.MEASURED &&
                output.riskDistanceM?.let { it.isFinite() && it > 0f } == true &&
                output.validSampleCount >= minimumSamples &&
                output.validSampleRatio.isFinite() && output.validSampleRatio > 0f &&
                output.confidence.finalScore.isFinite() && output.confidence.hardGate > 0f &&
                output.confidence.freshnessQuality.isFinite() && output.confidence.freshnessQuality > 0f
        }

        const val CLASS_NAME = "unnamed-obstacle"
        const val MAX_SOURCE_AGE_MS = 800L
    }
}

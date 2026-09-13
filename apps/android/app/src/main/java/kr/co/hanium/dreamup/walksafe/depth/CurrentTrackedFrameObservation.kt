package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingObservation
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingResult
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingStatus

/** A current image observation derived from one completed detector capture, never a new detection. */
class CurrentTrackedFrameObservation private constructor(
    val sourceKey: VisualFrameKey,
    val targetKey: VisualFrameKey,
    val sourceDetections: List<DetectionCandidate>,
    val observations: List<VisualTrackingObservation>,
) {
    val timestampMs: Long get() = targetKey.frameId / 1_000_000L
    val trackedObservations: List<VisualTrackingObservation>
        get() = observations.filter { it.status == VisualTrackingStatus.TRACKED }

    fun isFreshAt(nowElapsedRealtimeMs: Long): Boolean =
        nowElapsedRealtimeMs >= targetKey.capturedAtElapsedRealtimeMs &&
            nowElapsedRealtimeMs - sourceKey.capturedAtElapsedRealtimeMs in 0L..MAX_SOURCE_AGE_MS

    fun matchesDepthSnapshot(snapshot: DepthFrameSnapshot): Boolean =
        snapshot.frameTimestampNs == targetKey.frameId &&
            snapshot.cameraImageTimestampNs == targetKey.cameraTimestampNs &&
            (snapshot.cameraPoseEvidence == null || snapshot.cameraPoseEvidence.timestampMs == timestampMs)

    companion object {
        const val MAX_SOURCE_AGE_MS = 800L

        fun fromTrackingResult(
            result: VisualTrackingResult,
            sourceDetections: List<DetectionCandidate>,
            sourceDetectionCompleted: Boolean,
            nowElapsedRealtimeMs: Long,
        ): CurrentTrackedFrameObservation? {
            if (!sourceDetectionCompleted) return null
            val source = result.detectorSourceKey
            val target = result.trackedTargetKey
            if (source.frameId <= 0L || target.frameId <= 0L ||
                source.cameraTimestampNs <= 0L || target.cameraTimestampNs <= 0L ||
                source.capturedAtElapsedRealtimeMs < 0L ||
                source.epoch != target.epoch || source.geometryVersion != target.geometryVersion ||
                target.frameId - source.frameId !in 0L..MAX_SOURCE_AGE_MS * 1_000_000L ||
                target.cameraTimestampNs < source.cameraTimestampNs ||
                target.capturedAtElapsedRealtimeMs < source.capturedAtElapsedRealtimeMs
            ) return null
            val observations = result.observations
            // Every authoritative source index must be accounted for, including explicit LOST.
            if (observations.map { it.sourceIndex }.sorted() != sourceDetections.indices.toList()) return null
            if (observations.any { observation ->
                    observation.detectorSourceKey != source || observation.trackedTargetKey != target ||
                        when (observation.status) {
                            VisualTrackingStatus.LOST -> observation.geometry != null
                            VisualTrackingStatus.TRACKED -> {
                                val geometry = observation.geometry
                                val original = sourceDetections[observation.sourceIndex]
                                geometry == null || observation.failure != null ||
                                    !observation.trackingQuality.isFinite() || observation.trackingQuality !in 0f..1f ||
                                    observation.trackingQuality <= 0f ||
                                    geometry.className != original.className ||
                                    geometry.detectionConfidence != original.detectionConfidence
                            }
                        }
                }
            ) return null
            return CurrentTrackedFrameObservation(
                source, target, sourceDetections.toList(), observations.toList(),
            ).takeIf { it.isFreshAt(nowElapsedRealtimeMs) }
        }
    }
}

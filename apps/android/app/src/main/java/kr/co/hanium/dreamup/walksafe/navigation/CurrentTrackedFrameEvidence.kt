package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.depth.CurrentTrackedFrameObservation
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingResult

/** Current visual/depth evidence with a separate, unchanged detector source. Contains no report image. */
internal class CurrentTrackedFrameEvidence private constructor(
    val observation: CurrentTrackedFrameObservation,
    val currentEvidence: MainActivity.DetectionFrameEvidence,
) {
    fun sourceAgeMs(nowElapsedRealtimeMs: Long): Long =
        nowElapsedRealtimeMs - observation.sourceKey.capturedAtElapsedRealtimeMs

    companion object {
        fun fromTrackingResult(
            sourceSnapshot: MainActivity.DetectionSnapshot,
            trackingResult: VisualTrackingResult,
            currentEvidence: MainActivity.DetectionFrameEvidence,
            nowElapsedRealtimeMs: Long,
        ): CurrentTrackedFrameEvidence? {
            val source = trackingResult.detectorSourceKey
            val sourceEvidence = sourceSnapshot.frameEvidence ?: return null
            if (sourceEvidence.visualFrameKey != source ||
                sourceSnapshot.captureFrameId != source.frameId ||
                sourceSnapshot.frameTimestampMs != source.frameId / 1_000_000L ||
                sourceSnapshot.capturedAtElapsedRealtimeMs != source.capturedAtElapsedRealtimeMs ||
                sourceEvidence.frameId != source.frameId ||
                sourceEvidence.frameTimestampMs != source.frameId / 1_000_000L ||
                sourceEvidence.depthSnapshot.frameTimestampNs != source.frameId ||
                sourceEvidence.depthSnapshot.cameraImageTimestampNs != source.cameraTimestampNs
            ) return null
            val observation = CurrentTrackedFrameObservation.fromTrackingResult(
                result = trackingResult,
                sourceDetections = sourceSnapshot.detections,
                sourceDetectionCompleted = !sourceSnapshot.partial,
                nowElapsedRealtimeMs = nowElapsedRealtimeMs,
            ) ?: return null
            val target = observation.targetKey
            if (currentEvidence.visualFrameKey != target ||
                currentEvidence.frameId != target.frameId ||
                currentEvidence.frameTimestampMs != observation.timestampMs ||
                currentEvidence.depthMapper?.frameId != target.frameId ||
                currentEvidence.tactileContext.captureFrameId != target.frameId ||
                !observation.matchesDepthSnapshot(currentEvidence.depthSnapshot)
            ) return null
            val projection = currentEvidence.depthSnapshot.cameraPoseEvidence?.imageProjection
            if (projection != null &&
                (projection.imageWidth != sourceSnapshot.imageWidth || projection.imageHeight != sourceSnapshot.imageHeight)
            ) return null
            return CurrentTrackedFrameEvidence(observation, currentEvidence)
        }
    }
}

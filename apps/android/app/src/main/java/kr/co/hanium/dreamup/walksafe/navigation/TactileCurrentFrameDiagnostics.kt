package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.runtime.DetectionFrameEvidence
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import kr.co.hanium.dreamup.walksafe.depth.CurrentTrackedFrameObservation
import kr.co.hanium.dreamup.walksafe.inference.WalkMateClassPolicy
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingResult
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingStatus

/** Diagnostic evidence only: it never creates a usable observation, output or feedback candidate. */
internal data class TactileCurrentFrameDiagnostics(
    val captureKey: TactileObservationFrameKey?,
    val completedTactileSource: Boolean,
    val rejection: TactileObservationRejection?,
) {
    companion object {
        /** Called with the inputs to current-frame validation, including rejected/null results. */
        fun fromCapture(
            sourceSnapshot: DetectionSnapshot,
            currentCaptureKey: VisualFrameKey?,
            currentEvidence: DetectionFrameEvidence?,
            trackingResult: VisualTrackingResult?,
            admittedFrame: CurrentTrackedFrameEvidence?,
            nowElapsedRealtimeMs: Long,
        ): TactileCurrentFrameDiagnostics {
            val tactileIndices = sourceSnapshot.detections.indices.filter {
                isRouteTactileClass(sourceSnapshot.detections[it].className)
            }
            val complete = !sourceSnapshot.partial && sourceSnapshot.completedAtMs != null
            val sourceAt = sourceSnapshot.capturedAtElapsedRealtimeMs
            val rejection = when {
                tactileIndices.isEmpty() -> null
                !complete -> TactileObservationRejection.DETECTION_INCOMPLETE
                sourceAt == null -> TactileObservationRejection.FRAME_MISMATCH
                nowElapsedRealtimeMs - sourceAt !in 0L..CurrentTrackedFrameObservation.MAX_SOURCE_AGE_MS ->
                    TactileObservationRejection.DETECTION_STALE
                currentCaptureKey == null || currentEvidence == null || sourceSnapshot.frameEvidence == null ->
                    TactileObservationRejection.CAPTURE_CONTEXT_UNAVAILABLE
                currentEvidence.visualFrameKey != currentCaptureKey ||
                    currentEvidence.frameId != currentCaptureKey.frameId ||
                    currentEvidence.frameTimestampMs != currentCaptureKey.frameId / 1_000_000L ||
                    currentEvidence.depthSnapshot.frameTimestampNs != currentCaptureKey.frameId ||
                    currentEvidence.depthSnapshot.cameraImageTimestampNs != currentCaptureKey.cameraTimestampNs ||
                    currentEvidence.tactileContext.captureFrameId != currentCaptureKey.frameId ->
                    TactileObservationRejection.FRAME_MISMATCH
                currentEvidence.depthMapper == null -> TactileObservationRejection.DEPTH_INSUFFICIENT
                trackingResult == null -> TactileObservationRejection.VISUAL_TRACKING_UNAVAILABLE
                admittedFrame == null || admittedFrame.observation.targetKey != currentCaptureKey ||
                    admittedFrame.currentEvidence !== currentEvidence -> TactileObservationRejection.FRAME_MISMATCH
                trackingResult.observations.any {
                    it.sourceIndex in tactileIndices && it.status == VisualTrackingStatus.LOST
                } -> TactileObservationRejection.VISUAL_TRACKING_UNAVAILABLE
                else -> null
            }
            return TactileCurrentFrameDiagnostics(
                captureKey = currentCaptureKey?.let { TactileObservationFrameKey(it.frameId, it.capturedAtElapsedRealtimeMs) },
                completedTactileSource = complete && tactileIndices.isNotEmpty(),
                rejection = rejection,
            )
        }

        /** Compatibility for callers that already hold admitted evidence. Includes all-LOST frames. */
        fun fromAdmitted(frame: CurrentTrackedFrameEvidence, nowElapsedRealtimeMs: Long): TactileCurrentFrameDiagnostics {
            val observation = frame.observation
            val hasTactile = observation.sourceDetections.any { isRouteTactileClass(it.className) }
            val rejection = when {
                !hasTactile -> null
                !observation.isFreshAt(nowElapsedRealtimeMs) -> TactileObservationRejection.DETECTION_STALE
                observation.observations.any {
                    isRouteTactileClass(observation.sourceDetections[it.sourceIndex].className) &&
                        it.status == VisualTrackingStatus.LOST
                } -> TactileObservationRejection.VISUAL_TRACKING_UNAVAILABLE
                else -> null
            }
            return TactileCurrentFrameDiagnostics(
                TactileObservationFrameKey(observation.targetKey.frameId, observation.targetKey.capturedAtElapsedRealtimeMs),
                hasTactile, rejection,
            )
        }

        private fun isRouteTactileClass(className: String): Boolean =
            WalkMateClassPolicy.isTraversableTactileClass(className) ||
                WalkMateClassPolicy.isTactileRouteBlockingClass(className)
    }
}

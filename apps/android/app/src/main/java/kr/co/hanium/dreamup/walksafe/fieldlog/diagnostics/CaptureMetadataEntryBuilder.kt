package kr.co.hanium.dreamup.walksafe.fieldlog.diagnostics

import kr.co.hanium.dreamup.walksafe.MetadataCaptureLogEntry
import kr.co.hanium.dreamup.walksafe.MetadataRect
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.runtime.vision.frame.FrameOverlayDiagnostics

/** Live model/view projections are read only in the same serialization order as the original. */
internal interface CaptureMetadataReadings {
    val detectorLoadedModelKey: String?
    val detectorModelFallbackUsed: Boolean
    val detectorLoadReason: String?
    val surfaceWidth: Int
    val surfaceHeight: Int
    fun displayRotation(): Int
}

/** Produces diagnostic telemetry only; it neither promotes detection evidence nor acquires a frame. */
internal class CaptureMetadataEntryBuilder(
    private val readings: CaptureMetadataReadings,
    private val maximumDepthSourceAgeMs: Long,
    private val maximumDepthFrameDeltaMs: Long,
) {
    fun buildCaptureLogEntry(
        frameTimestampMs: Long,
        nowMs: Long,
        detectionSnapshot: DetectionSnapshot,
        overlayDebugState: FrameOverlayDiagnostics,
        detectionsUsedForDepth: List<DetectionCandidate>,
        snapshot: DepthFrameSnapshot,
        bestOutput: TrackedObjectDepth?,
        depthTransformPath: String,
        depthFallbackReason: String?,
    ): MetadataCaptureLogEntry {
        val top = detectionSnapshot.detections.maxByOrNull { it.detectionConfidence }
        val depthWidth = snapshot.rawDepth?.width ?: snapshot.fullDepth?.width
        val depthHeight = snapshot.rawDepth?.height ?: snapshot.fullDepth?.height
        return MetadataCaptureLogEntry(
            frameTimestampMs = frameTimestampMs,
            detectorFrameTimestampMs = detectionSnapshot.frameTimestampMs,
            detectorAgeMs = detectionSnapshot.ageMs(nowMs),
            detectorSourceAgeMs = detectionSnapshot.sourceAgeMs(nowMs),
            detectorCompletedAgeMs = detectionSnapshot.completedAgeMs(nowMs),
            detectorFrameDeltaMs = detectionSnapshot.frameDeltaMs(frameTimestampMs),
            detectDurationMs = detectionSnapshot.detectDurationMs,
            detectorYuvDecodeMs = detectionSnapshot.detectorTiming?.yuvDecodeMs,
            detectorModelKey = detectionSnapshot.detectorTiming?.modelKey,
            detectorLoadedModelKey = readings.detectorLoadedModelKey,
            detectorModelFallbackUsed = readings.detectorModelFallbackUsed,
            detectorModelLoadReason = readings.detectorLoadReason,
            detectorModelPreprocessMs = detectionSnapshot.detectorTiming?.modelPreprocessMs,
            detectorModelInferenceMs = detectionSnapshot.detectorTiming?.modelInferenceMs,
            detectorModelParseMs = detectionSnapshot.detectorTiming?.modelParseMs,
            detectorCocoPreprocessMs = detectionSnapshot.detectorTiming?.cocoPreprocessMs,
            detectorCocoInferenceMs = detectionSnapshot.detectorTiming?.cocoInferenceMs,
            detectorCocoParseMs = detectionSnapshot.detectorTiming?.cocoParseMs,
            detectorCustomPreprocessMs = detectionSnapshot.detectorTiming?.customPreprocessMs,
            detectorCustomInferenceMs = detectionSnapshot.detectorTiming?.customInferenceMs,
            detectorCustomParseMs = detectionSnapshot.detectorTiming?.customParseMs,
            detectorCompletedModels = detectionSnapshot.detectorTiming?.completedModels ?: emptyList(),
            detectorSkippedModels = detectionSnapshot.detectorTiming?.skippedModels ?: emptyList(),
            detectorPartial = detectionSnapshot.partial,
            detectionCount = detectionSnapshot.detections.size,
            detectionsUsedForDepth = detectionsUsedForDepth.isNotEmpty(),
            overlaySelection = overlayDebugState.selection,
            overlayDetectionCount = overlayDebugState.detectionCount,
            overlayTactileDetectionCount = overlayDebugState.tactileDetectionCount,
            overlayBoxCount = overlayDebugState.boxCount,
            overlayTactileBoxCount = overlayDebugState.tactileBoxCount,
            overlayHeldTactileBoxCount = overlayDebugState.heldTactileBoxCount,
            overlaySmoothedTactileBoxCount = overlayDebugState.smoothedTactileBoxCount,
            overlayHoldApplied = overlayDebugState.holdApplied,
            overlaySnapshotPartial = overlayDebugState.snapshotPartial,
            overlaySourceAgeMs = overlayDebugState.sourceAgeMs,
            overlayFrameDeltaMs = overlayDebugState.frameDeltaMs,
            overlayStaleReason = overlayDebugState.staleReason,
            staleReason = detectionSnapshot.staleReason(
                nowMs = nowMs,
                currentFrameTimestampMs = frameTimestampMs,
                maxSourceAgeMs = maximumDepthSourceAgeMs,
                maxFrameDeltaMs = maximumDepthFrameDeltaMs,
            ),
            topDetectionClassName = top?.className,
            topDetectionConfidence = top?.detectionConfidence,
            topDetectionBbox = top?.bboxNorm?.toMetadataRect(),
            bestDepthClassName = bestOutput?.className,
            bestDepthTrackId = bestOutput?.trackId,
            bestDepthSource = bestOutput?.source?.name,
            bestDepthDetectionConfidence = bestOutput?.detectionConfidence,
            bestDepthConfidenceScore = bestOutput?.confidence?.finalScore,
            bestDepthMedianM = bestOutput?.depthMedianM,
            bestDepthP20M = bestOutput?.depthP20M,
            bestDepthRiskDistanceM = bestOutput?.riskDistanceM,
            bestDepthIqrM = bestOutput?.depthIqrM,
            bestDepthValidSampleCount = bestOutput?.validSampleCount,
            bestDepthValidSampleRatio = bestOutput?.validSampleRatio,
            bestDepthBbox = bestOutput?.bboxNorm?.toMetadataRect(),
            previewWidth = readings.surfaceWidth.takeIf { it > 0 },
            previewHeight = readings.surfaceHeight.takeIf { it > 0 },
            cameraImageWidth = detectionSnapshot.imageWidth,
            cameraImageHeight = detectionSnapshot.imageHeight,
            displayRotation = readings.displayRotation(),
            depthWidth = depthWidth,
            depthHeight = depthHeight,
            overlayTransformPath = "arcore_image_to_view",
            depthTransformPath = depthTransformPath,
            transformPath = depthTransformPath,
            fallbackReason = depthFallbackReason,
        )
    }

    private fun RectNorm.toMetadataRect(): MetadataRect {
        return MetadataRect(x = x, y = y, width = width, height = height)
    }
}

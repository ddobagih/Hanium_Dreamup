package kr.co.hanium.dreamup.walksafe.runtime.vision.capture

import kr.co.hanium.dreamup.walksafe.MetadataCaptureLogEntry
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.runtime.vision.frame.FrameOverlayDiagnostics
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectionResult
import kr.co.hanium.dreamup.walksafe.inference.ArgbImage
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject

internal interface FrameCaptureModelReadings {
    val loadedModelKey: String?
    val fallbackUsed: Boolean
    val loadReason: String?
    val reportModelKey: String?
    fun resolveReportThreshold(modelKey: String?, className: String): Float?
}

/** Only owned JPEG resources; original create/scale/try/finally boundaries are preserved. */
internal interface FrameCaptureBitmaps<B : Any> {
    fun create(image: ArgbImage): B
    fun scale(bitmap: B, width: Int, height: Int, filter: Boolean): B
    fun compress(bitmap: B, quality: Int, output: java.io.OutputStream): Boolean
    fun recycle(bitmap: B)
}

/** Capture serialization consumes the original detector result, never fresh GPS or raw depth. */
internal class FrameCaptureArtifacts<B : Any>(
    private val bitmaps: FrameCaptureBitmaps<B>,
    private val model: FrameCaptureModelReadings,
    private val captureLog: () -> kr.co.hanium.dreamup.walksafe.fieldlog.diagnostics.CaptureMetadataEntryBuilder,
    private val maximumDetections: Int,
) {
    fun toScaledJpeg(image: ArgbImage, maxDimension: Int, quality: Int): ByteArray? {
        if (image.width <= 0 || image.height <= 0 || image.pixels.isEmpty()) return null
        val bitmap = bitmaps.create(image)
        val maxSide = maxOf(image.width, image.height).coerceAtLeast(1)
        val scale = (maxDimension / maxSide.toFloat()).coerceAtMost(1f)
        val outputBitmap = if (scale < 1f) {
            bitmaps.scale(
                bitmap,
                (image.width * scale).toInt().coerceAtLeast(1),
                (image.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        return try {
            ByteArrayOutputStream().use { output ->
                bitmaps.compress(outputBitmap, quality.coerceIn(1, 100), output)
                output.toByteArray()
            }
        } finally {
            if (outputBitmap !== bitmap) bitmaps.recycle(outputBitmap)
            bitmaps.recycle(bitmap)
        }
    }

    fun buildFrameCaptureMetadataJson(
        frameTimestampMs: Long,
        imageWidth: Int,
        imageHeight: Int,
        detectDurationMs: Long,
        result: AndroidDetectionResult,
        forcedCustom: Boolean,
    ): String {
        val detections = result.detections
        val top = detections.maxByOrNull { it.detectionConfidence }
        val json = JSONObject()
            .put("frame_timestamp_ms", frameTimestampMs)
            .put("camera_image_width", imageWidth)
            .put("camera_image_height", imageHeight)
            .put("detect_duration_ms", detectDurationMs)
            .put("detection_count", detections.size)
            .put("capture_policy", "manual_jpeg_no_gps_no_depth_raw_full_detector")
            .put("force_custom_tactile", forcedCustom)
            .put("detector_completed_models", JSONArray(result.timing.completedModels))
            .put("detector_skipped_models", JSONArray(result.timing.skippedModels))
            .put("detector_partial", result.partial)
            .put("detector_loaded_model_key", model.loadedModelKey)
            .put("detector_model_fallback_used", model.fallbackUsed)
            .put("detector_model_load_reason", model.loadReason)
        result.timing.customInferenceMs?.let { json.put("detector_custom_inference_ms", it) }
        result.timing.cocoInferenceMs?.let { json.put("detector_coco_inference_ms", it) }
        result.timing.modelKey?.let { json.put("detector_model_key", it) }
        result.timing.modelPreprocessMs?.let { json.put("detector_model_preprocess_ms", it) }
        result.timing.modelInferenceMs?.let { json.put("detector_model_inference_ms", it) }
        result.timing.modelParseMs?.let { json.put("detector_model_parse_ms", it) }
        json.put(
            "detections",
            JSONArray(
                detections
                    .sortedByDescending { it.detectionConfidence }
                    .take(maximumDetections)
                    .map { detection ->
                        JSONObject()
                            .put("class_name", detection.className)
                            .put("confidence", detection.detectionConfidence)
                            .put("bbox", detection.bboxNorm.toJson())
                    },
            ),
        )
        if (top != null) {
            json.put("top_detection_class_name", top.className)
                .put("top_detection_confidence", top.detectionConfidence)
                .put("top_detection_bbox", top.bboxNorm.toJson())
            model.resolveReportThreshold(model.reportModelKey, top.className)?.let { threshold ->
                json.put("top_detection_threshold_used", threshold)
            }
        }
        return json.toString()
    }

    private fun RectNorm.toJson(): JSONObject {
        return JSONObject()
            .put("x", x)
            .put("y", y)
            .put("width", width)
            .put("height", height)
    }

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
        return captureLog().buildCaptureLogEntry(
            frameTimestampMs, nowMs, detectionSnapshot, FrameOverlayDiagnostics(
                selection = overlayDebugState.selection,
                detectionCount = overlayDebugState.detectionCount,
                tactileDetectionCount = overlayDebugState.tactileDetectionCount,
                boxCount = overlayDebugState.boxCount,
                tactileBoxCount = overlayDebugState.tactileBoxCount,
                heldTactileBoxCount = overlayDebugState.heldTactileBoxCount,
                smoothedTactileBoxCount = overlayDebugState.smoothedTactileBoxCount,
                holdApplied = overlayDebugState.holdApplied,
                snapshotPartial = overlayDebugState.snapshotPartial,
                sourceAgeMs = overlayDebugState.sourceAgeMs,
                frameDeltaMs = overlayDebugState.frameDeltaMs,
                staleReason = overlayDebugState.staleReason,
            ),
            detectionsUsedForDepth, snapshot, bestOutput, depthTransformPath, depthFallbackReason,
        )
    }
}

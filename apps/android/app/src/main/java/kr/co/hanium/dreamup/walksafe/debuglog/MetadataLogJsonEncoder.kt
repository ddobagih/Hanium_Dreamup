package kr.co.hanium.dreamup.walksafe.debuglog

import kr.co.hanium.dreamup.walksafe.MetadataCaptureLogEntry
import kr.co.hanium.dreamup.walksafe.MetadataRect
import org.json.JSONArray
import org.json.JSONObject

/** Encodes nullable debug telemetry without inventing values for measurements that were unavailable. */
object MetadataLogJsonEncoder {
    /** Encodes one allowlisted entry for local field-session persistence. */
    fun encodeEntry(entry: MetadataCaptureLogEntry): String = entry.toJson().toString()

    fun encodeBatch(
        sessionId: String,
        entries: List<MetadataCaptureLogEntry>,
        deviceModel: String? = null,
        androidVersion: String? = null,
        appVersionName: String? = null,
    ): String {
        val root = JSONObject()
            .put("schema_version", SCHEMA_VERSION)
            .put("session_id", sessionId)
            .put("entries", JSONArray(entries.map { it.toJson() }))
        root.putIfNotNull("device_model", deviceModel)
        root.putIfNotNull("android_version", androidVersion)
        root.putIfNotNull("app_version_name", appVersionName)
        return root.toString()
    }

    private fun MetadataCaptureLogEntry.toJson(): JSONObject {
        val json = JSONObject()
            .put("frame_timestamp_ms", frameTimestampMs)
            .put("detection_count", detectionCount)
            .put("detections_used_for_depth", detectionsUsedForDepth)
        json.putIfNotNull("detector_frame_timestamp_ms", detectorFrameTimestampMs)
        json.putIfNotNull("detector_age_ms", detectorAgeMs)
        json.putIfNotNull("detector_source_age_ms", detectorSourceAgeMs)
        json.putIfNotNull("detector_completed_age_ms", detectorCompletedAgeMs)
        json.putIfNotNull("detector_frame_delta_ms", detectorFrameDeltaMs)
        json.putIfNotNull("detect_duration_ms", detectDurationMs)
        json.putIfNotNull("detector_yuv_decode_ms", detectorYuvDecodeMs)
        json.putIfNotNull("detector_model_key", detectorModelKey)
        json.putIfNotNull("detector_loaded_model_key", detectorLoadedModelKey)
        json.putIfNotNull("detector_model_fallback_used", detectorModelFallbackUsed)
        json.putIfNotNull("detector_model_load_reason", detectorModelLoadReason)
        json.putIfNotNull("detector_model_preprocess_ms", detectorModelPreprocessMs)
        json.putIfNotNull("detector_model_inference_ms", detectorModelInferenceMs)
        json.putIfNotNull("detector_model_parse_ms", detectorModelParseMs)
        json.putIfNotNull("detector_coco_preprocess_ms", detectorCocoPreprocessMs)
        json.putIfNotNull("detector_coco_inference_ms", detectorCocoInferenceMs)
        json.putIfNotNull("detector_coco_parse_ms", detectorCocoParseMs)
        json.putIfNotNull("detector_custom_preprocess_ms", detectorCustomPreprocessMs)
        json.putIfNotNull("detector_custom_inference_ms", detectorCustomInferenceMs)
        json.putIfNotNull("detector_custom_parse_ms", detectorCustomParseMs)
        if (detectorCompletedModels.isNotEmpty()) {
            json.put("detector_completed_models", JSONArray(detectorCompletedModels))
        }
        if (detectorSkippedModels.isNotEmpty()) {
            json.put("detector_skipped_models", JSONArray(detectorSkippedModels))
        }
        json.put("detector_partial", detectorPartial)
        json.putIfNotNull("overlay_selection", overlaySelection)
        json.putIfNotNull("overlay_detection_count", overlayDetectionCount)
        json.putIfNotNull("overlay_tactile_detection_count", overlayTactileDetectionCount)
        json.putIfNotNull("overlay_box_count", overlayBoxCount)
        json.putIfNotNull("overlay_tactile_box_count", overlayTactileBoxCount)
        json.putIfNotNull("overlay_held_tactile_box_count", overlayHeldTactileBoxCount)
        json.putIfNotNull("overlay_smoothed_tactile_box_count", overlaySmoothedTactileBoxCount)
        json.putIfNotNull("overlay_hold_applied", overlayHoldApplied)
        json.putIfNotNull("overlay_snapshot_partial", overlaySnapshotPartial)
        json.putIfNotNull("overlay_source_age_ms", overlaySourceAgeMs)
        json.putIfNotNull("overlay_frame_delta_ms", overlayFrameDeltaMs)
        json.putIfNotNull("overlay_stale_reason", overlayStaleReason)
        json.putIfNotNull("stale_reason", staleReason)
        json.putIfNotNull("top_detection_class_name", topDetectionClassName)
        json.putIfNotNull("top_detection_confidence", topDetectionConfidence)
        json.putIfNotNull("top_detection_bbox", topDetectionBbox?.toJson())
        json.putIfNotNull("best_depth_class_name", bestDepthClassName)
        json.putIfNotNull("best_depth_track_id", bestDepthTrackId)
        json.putIfNotNull("best_depth_source", bestDepthSource)
        json.putIfNotNull("best_depth_detection_confidence", bestDepthDetectionConfidence)
        json.putIfNotNull("best_depth_confidence_score", bestDepthConfidenceScore)
        json.putIfNotNull("best_depth_median_m", bestDepthMedianM)
        json.putIfNotNull("best_depth_p20_m", bestDepthP20M)
        json.putIfNotNull("best_depth_risk_distance_m", bestDepthRiskDistanceM)
        json.putIfNotNull("best_depth_iqr_m", bestDepthIqrM)
        json.putIfNotNull("best_depth_valid_sample_count", bestDepthValidSampleCount)
        json.putIfNotNull("best_depth_valid_sample_ratio", bestDepthValidSampleRatio)
        json.putIfNotNull("best_depth_bbox", bestDepthBbox?.toJson())
        json.putIfNotNull("preview_width", previewWidth)
        json.putIfNotNull("preview_height", previewHeight)
        json.putIfNotNull("camera_image_width", cameraImageWidth)
        json.putIfNotNull("camera_image_height", cameraImageHeight)
        json.putIfNotNull("display_rotation", displayRotation)
        json.putIfNotNull("depth_width", depthWidth)
        json.putIfNotNull("depth_height", depthHeight)
        json.putIfNotNull("overlay_transform_path", overlayTransformPath)
        json.putIfNotNull("depth_transform_path", depthTransformPath)
        json.putIfNotNull("transform_path", transformPath)
        json.putIfNotNull("fallback_reason", fallbackReason)
        return json
    }

    private fun MetadataRect.toJson(): JSONObject {
        return JSONObject()
            .put("x", x)
            .put("y", y)
            .put("width", width)
            .put("height", height)
    }

    private fun JSONObject.putIfNotNull(name: String, value: Any?): JSONObject {
        if (value != null) put(name, value)
        return this
    }

    private const val SCHEMA_VERSION = "android.depth_debug.v1"
}

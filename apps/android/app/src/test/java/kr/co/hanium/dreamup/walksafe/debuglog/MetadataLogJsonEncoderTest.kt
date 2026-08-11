package kr.co.hanium.dreamup.walksafe.debuglog

import kr.co.hanium.dreamup.walksafe.MetadataCaptureLogEntry
import kr.co.hanium.dreamup.walksafe.MetadataRect
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataLogJsonEncoderTest {
    @Test
    fun encodesMetadataOnlyDepthDebugBatch() {
        val encoded = MetadataLogJsonEncoder.encodeBatch(
            sessionId = "session-1",
            deviceModel = "Pixel Test",
            androidVersion = "16",
            appVersionName = "0.1.0",
            entries = listOf(entry()),
        )

        val root = JSONObject(encoded)
        assertEquals("android.depth_debug.v1", root.getString("schema_version"))
        assertEquals("session-1", root.getString("session_id"))
        val item = root.getJSONArray("entries").getJSONObject(0)
        assertEquals(1234L, item.getLong("frame_timestamp_ms"))
        assertEquals("person", item.getString("top_detection_class_name"))
        assertEquals("identity", item.getString("transform_path"))
        assertEquals("arcore_image_to_view", item.getString("overlay_transform_path"))
        assertEquals("identity", item.getString("depth_transform_path"))
        assertEquals(160, item.getInt("depth_width"))
        assertEquals(640, item.getInt("camera_image_width"))
        assertEquals(90, item.getInt("detect_duration_ms"))
        assertEquals(34, item.getInt("detector_frame_delta_ms"))
        assertEquals("unified_walksafe", item.getString("detector_model_key"))
        assertEquals("legacy_two_model", item.getString("detector_loaded_model_key"))
        assertEquals(true, item.getBoolean("detector_model_fallback_used"))
        assertEquals("unified_unavailable_legacy_loaded", item.getString("detector_model_load_reason"))
        assertEquals(40, item.getInt("detector_model_inference_ms"))
        assertEquals("unified_walksafe", item.getJSONArray("detector_completed_models").getString(0))
        assertEquals("track-1", item.getString("best_depth_track_id"))
        assertEquals(0.80, item.getDouble("best_depth_confidence_score"), 0.0001)
        assertEquals(1.10, item.getDouble("best_depth_risk_distance_m"), 0.0001)
        assertEquals(0.1, item.getJSONObject("top_detection_bbox").getDouble("x"), 0.0001)
        assertFalse(encoded.contains("gps"))
        assertFalse(encoded.contains("image_bytes"))
        assertFalse(encoded.contains("image_file"))
        assertFalse(encoded.contains("image_base64"))
        assertFalse(encoded.contains("raw_depth_ref"))
        assertFalse(encoded.contains("screenshot"))
        assertFalse(encoded.contains("base64"))
    }

    @Test
    fun endpointPolicyRejectsFileContentAndWebsocketSchemes() {
        assertTrue(MetadataLogEndpointPolicy.isAllowed("http://127.0.0.1:8000/android/debug/depth-logs"))
        assertTrue(MetadataLogEndpointPolicy.isAllowed("https://example.test/android/debug/depth-logs"))
        assertFalse(MetadataLogEndpointPolicy.isAllowed("file:///tmp/log.json"))
        assertFalse(MetadataLogEndpointPolicy.isAllowed("content://logs"))
        assertFalse(MetadataLogEndpointPolicy.isAllowed("ws://example.test/logs"))
        assertFalse(MetadataLogEndpointPolicy.isAllowed(""))
    }

    private fun entry(): MetadataCaptureLogEntry {
        return MetadataCaptureLogEntry(
            frameTimestampMs = 1234L,
            detectorFrameTimestampMs = 1200L,
            detectorAgeMs = 34L,
            detectorSourceAgeMs = 54L,
            detectorCompletedAgeMs = 4L,
            detectorFrameDeltaMs = 34L,
            detectDurationMs = 90L,
            detectorYuvDecodeMs = 10L,
            detectorModelKey = "unified_walksafe",
            detectorLoadedModelKey = "legacy_two_model",
            detectorModelFallbackUsed = true,
            detectorModelLoadReason = "unified_unavailable_legacy_loaded",
            detectorModelPreprocessMs = 20L,
            detectorModelInferenceMs = 40L,
            detectorModelParseMs = 5L,
            detectorCompletedModels = listOf("unified_walksafe"),
            detectorSkippedModels = emptyList(),
            detectorPartial = false,
            detectionCount = 1,
            detectionsUsedForDepth = true,
            staleReason = null,
            topDetectionClassName = "person",
            topDetectionConfidence = 0.91f,
            topDetectionBbox = MetadataRect(0.10f, 0.20f, 0.30f, 0.40f),
            bestDepthClassName = "person",
            bestDepthTrackId = "track-1",
            bestDepthSource = "ARCORE_RAW_DEPTH",
            bestDepthDetectionConfidence = 0.91f,
            bestDepthConfidenceScore = 0.80f,
            bestDepthMedianM = 1.2f,
            bestDepthP20M = 1.1f,
            bestDepthRiskDistanceM = 1.1f,
            bestDepthIqrM = 0.1f,
            bestDepthValidSampleCount = 32,
            bestDepthValidSampleRatio = 0.75f,
            bestDepthBbox = MetadataRect(0.10f, 0.20f, 0.30f, 0.40f),
            previewWidth = 1080,
            previewHeight = 1920,
            cameraImageWidth = 640,
            cameraImageHeight = 480,
            displayRotation = 0,
            depthWidth = 160,
            depthHeight = 120,
            overlayTransformPath = "arcore_image_to_view",
            depthTransformPath = "identity",
            transformPath = "identity",
            fallbackReason = "arcore_mapper_not_connected",
        )
    }
}

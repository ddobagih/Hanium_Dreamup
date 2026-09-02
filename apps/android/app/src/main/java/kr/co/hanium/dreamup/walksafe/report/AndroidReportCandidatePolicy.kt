package kr.co.hanium.dreamup.walksafe.report

import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.navigation.LocationTrustConfig
import kr.co.hanium.dreamup.walksafe.navigation.LocationTrustPolicy
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import org.json.JSONObject

data class AndroidReportCandidateInput(
    val depth: TrackedObjectDepth,
    val location: TrustedLocation?,
    val modelKey: String?,
    val sourceModel: String?,
    val runtimeMode: String?,
    val modelConfigSha256: String?,
    val modelVersion: String?,
    val sourceCommit: String,
    val threshold: Float?,
    val capturedAtMs: Long,
    val requestElapsedRealtimeMs: Long,
    val trigger: String = "auto",
    val reporterUserId: String? = null,
    val traceId: String,
    val deviceGateAllowsReports: Boolean,
    val depthSampleCount: Int? = null,
    val depthValidSampleRatio: Float? = null,
    val detectionAgeMs: Long? = null,
    val coordinateGateStatus: String? = null,
    val heading: Float? = null,
    val apkSha256: String? = null,
    val fallbackUsed: Boolean? = null,
    val loadedModelKey: String? = null,
    val modelLoadReason: String? = null,
)

data class AndroidReportCandidate(
    val metadata: JSONObject,
    val reason: String,
)

/**
 * Fail-closed client policy for damage-only report candidates. It requires a trusted location and
 * known deployed model provenance; the backend remains responsible for validating client metadata.
 */
class AndroidReportCandidatePolicy {
    fun prepare(input: AndroidReportCandidateInput): AndroidReportCandidate? {
        if (!input.deviceGateAllowsReports) return null
        if (input.depth.className != DAMAGED_TACTILE_BLOCK) return null
        if (input.trigger !in ALLOWED_TRIGGERS) return null
        if (
            input.trigger == TRIGGER_AUTO &&
            (!isStableAutomaticDetection(input.depth) || !isAutomaticConfidenceAllowed(input.depth.detectionConfidence))
        ) return null
        val modelKey = input.modelKey ?: return null
        if (modelKey !in ALLOWED_MODEL_KEYS) return null
        val detectionAgeMs = input.detectionAgeMs ?: return null
        if (detectionAgeMs !in 0L..REPORT_MAX_DETECTION_AGE_MS) return null
        val location = LocationTrustPolicy.freshOrNull(
            location = input.location,
            nowElapsedRealtimeMs = input.requestElapsedRealtimeMs,
            config = REPORT_LOCATION_CONFIG,
        ) ?: return null
        if (!location.latitude.isFinite() || location.latitude !in -90.0..90.0) return null
        if (!location.longitude.isFinite() || location.longitude !in -180.0..180.0) return null
        if (!location.accuracyM.isFinite() || location.accuracyM < 0f || location.accuracyM > REPORT_LOCATION_CONFIG.maxAccuracyM) return null
        val threshold = input.threshold ?: return null
        val sourceModel = input.sourceModel?.takeIf { it.isNotBlank() } ?: return null
        val modelClassId = REPORT_CLASS_IDS[modelKey] ?: return null
        if (input.sourceCommit != "unverified" && !SOURCE_COMMIT_PATTERN.matches(input.sourceCommit)) return null

        val metadata = JSONObject()
            .put("schema_version", "detect.v2")
            .put("source", "android")
            .put("model_key", modelKey)
            .put("source_model", sourceModel)
            .put("model_class_id", modelClassId)
            .put("class_name", DAMAGED_TACTILE_BLOCK)
            .put("category", "tactile_damage")
            .put("confidence", input.depth.detectionConfidence.coerceIn(0f, 1f).toDouble())
            .put("bbox", input.depth.bboxNorm.toJson())
            .put("distance_m", input.depth.riskDistanceM?.toDouble())
            .put("distance_source", "sensor_depth")
            .put("distance_confidence", input.depth.confidence.finalScore.coerceIn(0f, 1f).toDouble())
            .put("coordinate_gate_status", input.coordinateGateStatus ?: "pass")
            .put("threshold_used", threshold.coerceIn(0f, 1f).toDouble())
            .put("captured_at", input.capturedAtMs.timestampMsToIsoUtc())
            .put("detection_age_ms", input.detectionAgeMs)
            .put("depth_sample_count", input.depthSampleCount ?: input.depth.validSampleCount)
            .put("depth_valid_sample_ratio", input.depthValidSampleRatio ?: input.depth.validSampleRatio.toDouble())
            .put("gps", location.toGpsJson())
            .put("heading", input.heading?.normalizedHeading()?.toDouble())
            .put("trigger", input.trigger)
            .put("auto_reported", input.trigger == "auto")
            .put("reporter_user_id", input.reporterUserId?.takeIf { it.isNotBlank() })
            .put("trace_id", input.traceId)
            .put("bbox_coordinate_space", "arcore_image_to_texture_normalized")
            .put("depth_coordinate_space", "arcore_depth_metric")
            .put("runtime_mode", input.runtimeMode ?: "android")
            .put("apk_sha256", input.apkSha256)
            .put("model_config_sha256", input.modelConfigSha256)
            .put("android_model_version", input.modelVersion)
            .put("source_commit", input.sourceCommit)
            .put("fallback_used", input.fallbackUsed == true)
            .put("loaded_model_key", input.loadedModelKey)
            .put("model_load_reason", input.modelLoadReason)
        return AndroidReportCandidate(metadata = metadata, reason = "damage_only_android_candidate")
    }

    private fun kr.co.hanium.dreamup.walksafe.depth.RectNorm.toJson(): JSONObject {
        return JSONObject()
            .put("x", x.toDouble())
            .put("y", y.toDouble())
            .put("width", width.toDouble())
            .put("height", height.toDouble())
    }

    private fun TrustedLocation.toGpsJson(): JSONObject {
        return JSONObject()
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("accuracy_m", accuracyM.toDouble())
    }

    private fun Long.timestampMsToIsoUtc(): String {
        return java.time.Instant.ofEpochMilli(this).toString()
    }

    private fun Float.normalizedHeading(): Float {
        val normalized = this % 360f
        return if (normalized < 0f) normalized + 360f else normalized
    }

    companion object {
        const val DAMAGED_TACTILE_BLOCK = "damaged_tactile_block"
        const val TRIGGER_AUTO = "auto"
        const val TRIGGER_ON_SCREEN = "on_screen"
        const val TRIGGER_VOICE = "voice"
        const val AUTO_REPORT_MIN_TRACK_AGE_FRAMES = 3
        const val AUTO_REPORT_MIN_TRACK_STABLE_MS = 700L
        const val AUTO_REPORT_MIN_DETECTION_CONFIDENCE = 0.70f
        const val REPORT_MAX_DETECTION_AGE_MS = 1_200L
        val ALLOWED_MODEL_KEYS = setOf("custom_tactile", "unified_walksafe")
        val ALLOWED_TRIGGERS = setOf(TRIGGER_AUTO, TRIGGER_ON_SCREEN, TRIGGER_VOICE)
        val REPORT_CLASS_IDS = mapOf(
            "custom_tactile" to 1,
            "unified_walksafe" to 8,
        )

        fun isStableAutomaticDetection(depth: TrackedObjectDepth): Boolean {
            return depth.trackAgeFrames >= AUTO_REPORT_MIN_TRACK_AGE_FRAMES &&
                depth.trackStableMs >= AUTO_REPORT_MIN_TRACK_STABLE_MS
        }

        fun isAutomaticConfidenceAllowed(confidence: Float): Boolean {
            return confidence.isFinite() && confidence >= AUTO_REPORT_MIN_DETECTION_CONFIDENCE
        }

        private val REPORT_LOCATION_CONFIG = LocationTrustConfig(maxAccuracyM = 15f, maxAgeMs = 10_000L)
        private val SOURCE_COMMIT_PATTERN = Regex("[0-9a-f]{40}")
    }
}

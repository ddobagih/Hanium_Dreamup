package kr.co.hanium.dreamup.walksafe.report

import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.DepthAvailability
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
 * Internal diagnostic reason for a refused candidate. [diagnosticCode] is a fixed token: it never
 * carries image bytes, coordinates or other input values, so it may be logged or shown in status.
 */
enum class AndroidReportCandidateRejection(val diagnosticCode: String) {
    COORDINATE_GATE_NOT_PASSED("coordinate_gate"),
    DEPTH_SOURCE_NOT_SENSOR("depth_source"),
    DEPTH_NOT_MEASURED("depth_not_measured"),
    DISTANCE_INVALID("distance_invalid"),
    DEPTH_EXPIRED("depth_expired"),
    NUMBER_INVALID("number_invalid"),
    BBOX_INVALID("bbox_invalid"),
    DEVICE_GATE_BLOCKED("device_gate"),
    CLASS_NOT_REPORTABLE("class_not_reportable"),
    TRIGGER_NOT_ALLOWED("trigger_not_allowed"),
    AUTOMATIC_TRACK_UNSTABLE("automatic_track_unstable"),
    AUTOMATIC_CONFIDENCE_LOW("automatic_confidence_low"),
    MODEL_NOT_ALLOWED("model_not_allowed"),
    DETECTION_AGE_INVALID("detection_age"),
    LOCATION_UNTRUSTED("location_untrusted"),
    THRESHOLD_MISSING("threshold_missing"),
    SOURCE_MODEL_MISSING("source_model_missing"),
    MODEL_CLASS_UNMAPPED("model_class_unmapped"),
    SOURCE_COMMIT_INVALID("source_commit_invalid"),
}

sealed interface AndroidReportCandidateResult {
    data class Ready(val candidate: AndroidReportCandidate) : AndroidReportCandidateResult

    data class Rejected(val reason: AndroidReportCandidateRejection) : AndroidReportCandidateResult
}

/**
 * Fail-closed client policy for damage-only report candidates. It requires a trusted location and
 * known deployed model provenance; the backend remains responsible for validating client metadata.
 */
class AndroidReportCandidatePolicy {
    fun prepare(input: AndroidReportCandidateInput): AndroidReportCandidate? =
        (evaluate(input) as? AndroidReportCandidateResult.Ready)?.candidate

    /** Same decision as [prepare], with the first failed check kept for diagnostics. */
    fun evaluate(input: AndroidReportCandidateInput): AndroidReportCandidateResult {
        fun rejected(reason: AndroidReportCandidateRejection) =
            AndroidReportCandidateResult.Rejected(reason)
        reportEvidenceRejectionOrNull(input)?.let { return rejected(it) }
        if (!input.deviceGateAllowsReports) {
            return rejected(AndroidReportCandidateRejection.DEVICE_GATE_BLOCKED)
        }
        if (!isReportableDamageClass(input.depth.className)) {
            return rejected(AndroidReportCandidateRejection.CLASS_NOT_REPORTABLE)
        }
        if (input.trigger !in ALLOWED_TRIGGERS) {
            return rejected(AndroidReportCandidateRejection.TRIGGER_NOT_ALLOWED)
        }
        if (input.trigger == TRIGGER_AUTO && !isStableAutomaticDetection(input.depth)) {
            return rejected(AndroidReportCandidateRejection.AUTOMATIC_TRACK_UNSTABLE)
        }
        if (input.trigger == TRIGGER_AUTO && !isAutomaticConfidenceAllowed(input.depth.detectionConfidence)) {
            return rejected(AndroidReportCandidateRejection.AUTOMATIC_CONFIDENCE_LOW)
        }
        val modelKey = input.modelKey?.takeIf { it in ALLOWED_MODEL_KEYS }
            ?: return rejected(AndroidReportCandidateRejection.MODEL_NOT_ALLOWED)
        val detectionAgeMs = input.detectionAgeMs?.takeIf { it in 0L..REPORT_MAX_DETECTION_AGE_MS }
            ?: return rejected(AndroidReportCandidateRejection.DETECTION_AGE_INVALID)
        val location = LocationTrustPolicy.freshOrNull(
            location = input.location,
            nowElapsedRealtimeMs = input.requestElapsedRealtimeMs,
            config = REPORT_LOCATION_CONFIG,
        )?.takeIf { location ->
            location.latitude.isFinite() && location.latitude in -90.0..90.0 &&
                location.longitude.isFinite() && location.longitude in -180.0..180.0 &&
                location.accuracyM.isFinite() && location.accuracyM >= 0f &&
                location.accuracyM <= REPORT_LOCATION_CONFIG.maxAccuracyM
        } ?: return rejected(AndroidReportCandidateRejection.LOCATION_UNTRUSTED)
        val threshold = input.threshold
            ?: return rejected(AndroidReportCandidateRejection.THRESHOLD_MISSING)
        val sourceModel = input.sourceModel?.takeIf { it.isNotBlank() }
            ?: return rejected(AndroidReportCandidateRejection.SOURCE_MODEL_MISSING)
        val modelClassId = REPORT_CLASS_IDS[modelKey]?.get(input.depth.className)
            ?: return rejected(AndroidReportCandidateRejection.MODEL_CLASS_UNMAPPED)
        if (input.sourceCommit != "unverified" && !SOURCE_COMMIT_PATTERN.matches(input.sourceCommit)) {
            return rejected(AndroidReportCandidateRejection.SOURCE_COMMIT_INVALID)
        }

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
            .put("coordinate_gate_status", input.coordinateGateStatus)
            .put("threshold_used", threshold.coerceIn(0f, 1f).toDouble())
            .put("captured_at", input.capturedAtMs.timestampMsToIsoUtc())
            .put("detection_age_ms", detectionAgeMs)
            .put("depth_sample_count", input.depthSampleCount ?: input.depth.validSampleCount)
            .put("depth_valid_sample_ratio", input.depthValidSampleRatio ?: input.depth.validSampleRatio.toDouble())
            .put("gps", location.toGpsJson())
            .put("location_kind", LOCATION_KIND_CAPTURE_DEVICE)
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
        return AndroidReportCandidateResult.Ready(
            AndroidReportCandidate(metadata = metadata, reason = "damage_only_android_candidate"),
        )
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

        /**
         * S15-14: `gps` is the phone's own position when the photo was taken, with its accuracy.
         * It is not the damaged facility's position; no facility position is estimated here
         * (no pose/metric-depth projection with uncertainty exists, and GPS travel direction is
         * never used as the camera's line of sight).
         */
        const val LOCATION_KIND_CAPTURE_DEVICE = "CAPTURE_DEVICE"
        const val TRIGGER_AUTO = "auto"
        const val TRIGGER_ON_SCREEN = "on_screen"
        const val TRIGGER_VOICE = "voice"
        const val AUTO_REPORT_MIN_TRACK_AGE_FRAMES = 3
        const val AUTO_REPORT_MIN_TRACK_STABLE_MS = 700L
        const val AUTO_REPORT_MIN_DETECTION_CONFIDENCE = 0.70f
        const val REPORT_MAX_DETECTION_AGE_MS = 1_200L
        val ALLOWED_TRIGGERS = setOf(TRIGGER_AUTO, TRIGGER_ON_SCREEN, TRIGGER_VOICE)
        // Keep the model-local class id while reporting a shared damage category to the backend.
        val REPORT_CLASS_IDS = mapOf(
            "custom_tactile" to mapOf(DAMAGED_TACTILE_BLOCK to 1),
            "unified_walksafe" to mapOf(DAMAGED_TACTILE_BLOCK to 8),
            "walkmate_21cls" to mapOf(
                "damaged_linear_tactile_paving" to 1,
                "damaged_dot_tactile_paving" to 3,
            ),
        )
        val ALLOWED_MODEL_KEYS = REPORT_CLASS_IDS.keys
        private val REPORTABLE_DAMAGE_CLASSES = REPORT_CLASS_IDS.values.flatMap { it.keys }.toSet()

        fun isReportableDamageClass(className: String): Boolean = className in REPORTABLE_DAMAGE_CLASSES

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


/** Validate evidence before JSON construction can normalize or throw on malformed inputs. */
private fun reportEvidenceRejectionOrNull(
    input: AndroidReportCandidateInput,
): AndroidReportCandidateRejection? {
    if (input.coordinateGateStatus != "pass") {
        return AndroidReportCandidateRejection.COORDINATE_GATE_NOT_PASSED
    }
    val depth = input.depth
    // Android's existing sensor-depth providers only; do not admit inferred or new sources.
    if (depth.source != DepthSource.ARCORE_RAW_DEPTH && depth.source != DepthSource.ARCORE_FULL_DEPTH) {
        return AndroidReportCandidateRejection.DEPTH_SOURCE_NOT_SENSOR
    }
    if (depth.depthAvailability != DepthAvailability.MEASURED) {
        return AndroidReportCandidateRejection.DEPTH_NOT_MEASURED
    }
    val distance = depth.riskDistanceM
    if (distance == null || !distance.isFinite() || distance <= 0f) {
        return AndroidReportCandidateRejection.DISTANCE_INVALID
    }
    val validUntil = depth.sourceValidUntilElapsedRealtimeMs
    if (validUntil != null && input.requestElapsedRealtimeMs > validUntil) {
        return AndroidReportCandidateRejection.DEPTH_EXPIRED
    }
    return if (hasValidReportNumbers(input)) {
        null
    } else if (!hasValidReportBox(input)) {
        AndroidReportCandidateRejection.BBOX_INVALID
    } else {
        AndroidReportCandidateRejection.NUMBER_INVALID
    }
}

private fun hasValidReportNumbers(input: AndroidReportCandidateInput): Boolean {
    val depth = input.depth
    val threshold = input.threshold ?: return false
    val confidence = depth.confidence
    if (!listOf(
            threshold,
            depth.detectionConfidence,
            confidence.sourceQuality,
            confidence.sampleQuality,
            confidence.depthQuality,
            confidence.detectionQuality,
            confidence.trackingQuality,
            confidence.motionQuality,
            confidence.freshnessQuality,
            confidence.corridorQuality,
            confidence.hardGate,
            confidence.finalScore,
        ).all { it.isFinite() && it in 0f..1f }
    ) return false
    if (input.heading?.isFinite() == false) return false
    val sampleCount = input.depthSampleCount ?: depth.validSampleCount
    val sampleRatio = input.depthValidSampleRatio ?: depth.validSampleRatio
    if (sampleCount < 0 || !sampleRatio.isFinite() || sampleRatio !in 0f..1f) return false
    return hasValidReportBox(input)
}

private fun hasValidReportBox(input: AndroidReportCandidateInput): Boolean {
    val box = input.depth.bboxNorm
    return box.x.isFinite() && box.y.isFinite() && box.width.isFinite() && box.height.isFinite() &&
        box.x in 0f..1f && box.y in 0f..1f && box.width > 0f && box.height > 0f &&
        box.width.toDouble() <= 1.0 - box.x.toDouble() &&
        box.height.toDouble() <= 1.0 - box.y.toDouble()
}

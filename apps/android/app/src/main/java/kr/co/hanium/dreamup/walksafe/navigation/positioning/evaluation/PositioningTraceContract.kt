package kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation

import java.math.BigDecimal
import java.math.BigInteger
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

const val POSITION_TRACE_EXPORT_SCHEMA_VERSION = "walksafe.positioning_trace.v2"
const val POSITION_TRACE_MOUNT = "PORTRAIT_BACK_OUT_TOP_UP_CHEST_CENTER"
const val POSITION_TRACE_SOURCE_KIND = "ANDROID_DEBUG_RECORDER"
const val POSITION_TRACE_TIMEBASE = "ANDROID_ELAPSED_REALTIME_NANOS"
internal const val POSITION_TRACE_STORAGE_SCHEMA_VERSION = "android.positioning_trace_record.v2"

data class PositioningTraceStartMetadata(
    val routeId: String,
    val scenario: String,
    val environment: String,
    val direction: String,
    val deviceModel: String,
    val androidApi: Int,
    val mount: String,
    val sourceKind: String,
    val syntheticContractOnly: Boolean,
    val timebase: String,
) {
    internal fun isValid(): Boolean =
        isCanonicalUuid(routeId) &&
            SAFE_METADATA_TOKEN.matches(scenario) &&
            SAFE_METADATA_TOKEN.matches(environment) &&
            SAFE_METADATA_TOKEN.matches(direction) &&
            SAFE_DEVICE_MODEL.matches(deviceModel) &&
            androidApi in 1..999 &&
            mount == POSITION_TRACE_MOUNT &&
            sourceKind == POSITION_TRACE_SOURCE_KIND &&
            !syntheticContractOnly &&
            timebase == POSITION_TRACE_TIMEBASE

    internal fun toHeaderJson(sessionId: String): JSONObject = JSONObject()
        .put("schema_version", POSITION_TRACE_EXPORT_SCHEMA_VERSION)
        .put("record_type", "header")
        .put("session_id", sessionId)
        .put("route_id", routeId)
        .put("scenario", scenario)
        .put("environment", environment)
        .put("direction", direction)
        .put("device_model", deviceModel)
        .put("android_api", androidApi)
        .put("mount", mount)
        .put("source_kind", sourceKind)
        .put("synthetic_contract_only", syntheticContractOnly)
        .put("timebase", timebase)
}

data class PositionTraceCoordinate(
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
) {
    init {
        require(latitudeDegrees.isFinite() && latitudeDegrees in -90.0..90.0)
        require(longitudeDegrees.isFinite() && longitudeDegrees in -180.0..180.0)
    }
}

enum class PositionGnssRisk { LOW, MEDIUM, HIGH, UNAVAILABLE }

data class PositionGnssTrace(
    val l5Available: Boolean? = null,
    val l5UsedSatelliteCount: Int? = null,
    val meanCn0DbHz: Double? = null,
    val risk: PositionGnssRisk? = null,
    val measurementNoiseMeters: Double? = null,
) {
    init {
        l5UsedSatelliteCount?.let { require(it >= 0) }
        meanCn0DbHz?.let { require(it.isFinite() && it >= 0.0) }
        measurementNoiseMeters?.let { require(it.isFinite() && it >= 0.0) }
    }
}

data class PositionStepProfileTrace(
    val stepCount: Long? = null,
    val stepDetected: Boolean? = null,
    val currentStepLengthMeters: Double? = null,
    val profileStepLengthMeters: Double? = null,
    val profileSampleCount: Int? = null,
) {
    init {
        stepCount?.let { require(it >= 0L) }
        currentStepLengthMeters?.let { require(it.isFinite() && it > 0.0) }
        profileStepLengthMeters?.let { require(it.isFinite() && it > 0.0) }
        profileSampleCount?.let { require(it >= 0) }
    }
}

enum class PositionHeadingSource {
    GNSS_COURSE,
    MAGNETIC_ROTATION_VECTOR,
    GAME_ROTATION_VECTOR,
    NONE,
}

data class PositionHeadingTrace(
    val selectedDegrees: Double? = null,
    val source: PositionHeadingSource? = null,
) {
    init {
        selectedDegrees?.let { require(it.isFinite() && it >= 0.0 && it < 360.0) }
    }
}

enum class PositionStationaryState { MOVING, CANDIDATE, STATIONARY, UNKNOWN }

data class PositionStationaryTrace(
    val stationary: Boolean? = null,
    val state: PositionStationaryState? = null,
    val zuptApplied: Boolean? = null,
)

enum class PositionTraceSource(
    internal val wireValue: String,
    internal val carriesUtc: Boolean,
    internal val positionSample: Boolean,
) {
    GNSS("gnss", carriesUtc = true, positionSample = true),
    SENSOR_GNSS_ANCHORED("sensor_gnss_anchored", carriesUtc = true, positionSample = true),
    SENSOR_MONOTONIC_ONLY("sensor_monotonic_only", carriesUtc = false, positionSample = true),
    CHECKPOINT_GNSS_ANCHORED("checkpoint_gnss_anchored", carriesUtc = true, positionSample = false),
    CHECKPOINT_MONOTONIC_ONLY("checkpoint_monotonic_only", carriesUtc = false, positionSample = false),
}

/** Raw accelerometer, gyroscope, audio, image, destination and query payloads are absent. */
data class PositioningTraceRecord(
    val elapsedRealtimeNs: Long,
    val measurementElapsedRealtimeNs: Long,
    val measurementUtcEpochMs: Long?,
    val source: PositionTraceSource,
    val rawPosition: PositionTraceCoordinate? = null,
    val filteredPosition: PositionTraceCoordinate? = null,
    val matchedPosition: PositionTraceCoordinate? = null,
    val accuracyMeters: Double? = null,
    val speedMetersPerSecond: Double? = null,
    val bearingDegrees: Double? = null,
    val gnss: PositionGnssTrace? = null,
    val stepProfile: PositionStepProfileTrace? = null,
    val heading: PositionHeadingTrace? = null,
    val stationary: PositionStationaryTrace? = null,
    /** True when this row replaces the route-match result, including an unavailable result. */
    val routeMatchEvaluated: Boolean? = null,
) {
    init {
        require(elapsedRealtimeNs >= 0L)
        require(measurementElapsedRealtimeNs in 0L..elapsedRealtimeNs)
        require(source.positionSample)
        require((measurementUtcEpochMs != null) == source.carriesUtc)
        measurementUtcEpochMs?.let { require(it >= 0L) }
        accuracyMeters?.let { require(it.isFinite() && it >= 0.0) }
        speedMetersPerSecond?.let { require(it.isFinite() && it >= 0.0) }
        bearingDegrees?.let { require(it.isFinite() && it >= 0.0 && it < 360.0) }
    }
}

data class PositioningTraceCheckpoint(
    val elapsedRealtimeNs: Long,
    val measurementElapsedRealtimeNs: Long,
    val measurementUtcEpochMs: Long?,
    val source: PositionTraceSource,
    val checkpointId: String,
    val ordinal: Int,
    val stationaryState: PositionStationaryState,
    val stationaryDurationMs: Long,
) {
    internal fun isValid(): Boolean =
        elapsedRealtimeNs >= 0L &&
            measurementElapsedRealtimeNs in 0L..elapsedRealtimeNs &&
            !source.positionSample &&
            (measurementUtcEpochMs != null) == source.carriesUtc &&
            (measurementUtcEpochMs == null || measurementUtcEpochMs >= 0L) &&
            SAFE_CHECKPOINT_ID.matches(checkpointId) &&
            ordinal >= 1 &&
            stationaryState == PositionStationaryState.STATIONARY &&
            stationaryDurationMs >= 1_500L
}

internal fun PositioningTraceRecord.toStoredTraceJson(
    sessionId: String,
    scopeSha256: String,
    generation: Long,
    walkEpoch: Long,
    sequence: Long,
): JSONObject = storedRecordPrefix(sessionId, scopeSha256, generation, walkEpoch, sequence)
    .put("record_type", "position")
    .put("elapsed_realtime_ns", elapsedRealtimeNs)
    .put("measurement_elapsed_realtime_ns", measurementElapsedRealtimeNs)
    .putOptional("measurement_utc_epoch_ms", measurementUtcEpochMs)
    .put("source", source.wireValue)
    .putOptional("raw_position", rawPosition?.toJson())
    .putOptional("filtered_position", filteredPosition?.toJson())
    .putOptional("matched_position", matchedPosition?.toJson())
    .putOptional("accuracy_m", accuracyMeters)
    .putOptional("speed_mps", speedMetersPerSecond)
    .putOptional("bearing_deg", bearingDegrees)
    .putOptional("gnss", gnss?.toJson())
    .putOptional("step_profile", stepProfile?.toJson())
    .putOptional("heading", heading?.toJson())
    .putOptional("stationary", stationary?.toJson())
    .putOptional("route_match_evaluated", routeMatchEvaluated)

internal fun PositioningTraceCheckpoint.toStoredTraceJson(
    sessionId: String,
    scopeSha256: String,
    generation: Long,
    walkEpoch: Long,
    sequence: Long,
): JSONObject = storedRecordPrefix(sessionId, scopeSha256, generation, walkEpoch, sequence)
    .put("record_type", "checkpoint")
    .put("elapsed_realtime_ns", elapsedRealtimeNs)
    .put("measurement_elapsed_realtime_ns", measurementElapsedRealtimeNs)
    .putOptional("measurement_utc_epoch_ms", measurementUtcEpochMs)
    .put("source", source.wireValue)
    .put("checkpoint_id", checkpointId)
    .put("ordinal", ordinal)
    .put("stationary_state", stationaryState.name.lowercase(Locale.ROOT))
    .put("stationary_duration_ms", stationaryDurationMs)

internal fun JSONObject.toExportRecordJson(): JSONObject? {
    val type = optString("record_type")
    val storedSequence = (opt("seq") as? Number)?.toLong()
        ?.takeIf { it in 0L..<Long.MAX_VALUE } ?: return null
    val wireType = when (type) {
        "position" -> "position_sample"
        "checkpoint" -> "checkpoint_mark"
        else -> return null
    }
    if (!has("measurement_elapsed_realtime_ns") || !has("source")) return null
    val exported = JSONObject()
        .put("record_type", wireType)
        .put("seq", storedSequence + 1L)
        .put("elapsed_realtime_ns", opt("elapsed_realtime_ns"))
        .put("measurement_elapsed_realtime_ns", opt("measurement_elapsed_realtime_ns"))
        .copyOptional(this, "measurement_utc_epoch_ms")
        .put("source", opt("source"))
    return when (type) {
        "position" -> exported
            .copyOptional(this, "raw_position")
            .copyOptional(this, "filtered_position")
            .copyOptional(this, "matched_position")
            .copyOptional(this, "accuracy_m")
            .copyOptional(this, "speed_mps")
            .copyOptional(this, "bearing_deg")
            .copyOptional(this, "gnss")
            .copyOptional(this, "step_profile")
            .copyOptional(this, "heading")
            .copyOptional(this, "stationary")
            .copyOptional(this, "route_match_evaluated")
        "checkpoint" -> exported
            .put("checkpoint_id", opt("checkpoint_id"))
            .put("ordinal", opt("ordinal"))
            .put("stationary_state", opt("stationary_state"))
            .put("stationary_duration_ms", opt("stationary_duration_ms"))
        else -> null
    }
}

internal fun JSONObject.toCanonicalJsonLine(): String = canonicalJsonValue(this)

private fun storedRecordPrefix(
    sessionId: String,
    scopeSha256: String,
    generation: Long,
    walkEpoch: Long,
    sequence: Long,
): JSONObject = JSONObject()
    .put("storage_schema_version", POSITION_TRACE_STORAGE_SCHEMA_VERSION)
    .put("session_id", sessionId)
    .put("scope_sha256", scopeSha256)
    .put("generation", generation)
    .put("walk_epoch", walkEpoch)
    .put("seq", sequence)

private fun PositionTraceCoordinate.toJson(): JSONObject = JSONObject()
    .put("latitude_deg", latitudeDegrees)
    .put("longitude_deg", longitudeDegrees)

private fun PositionGnssTrace.toJson(): JSONObject = JSONObject()
    .putOptional("l5_available", l5Available)
    .putOptional("l5_used_satellite_count", l5UsedSatelliteCount)
    .putOptional("mean_cn0_db_hz", meanCn0DbHz)
    .putOptional("risk", risk?.name?.lowercase(Locale.ROOT))
    .putOptional("measurement_noise_m", measurementNoiseMeters)

private fun PositionStepProfileTrace.toJson(): JSONObject = JSONObject()
    .putOptional("step_count", stepCount)
    .putOptional("step_detected", stepDetected)
    .putOptional("current_step_length_m", currentStepLengthMeters)
    .putOptional("profile_step_length_m", profileStepLengthMeters)
    .putOptional("profile_sample_count", profileSampleCount)

private fun PositionHeadingTrace.toJson(): JSONObject = JSONObject()
    .putOptional("selected_deg", selectedDegrees)
    .putOptional("source", source?.name?.lowercase(Locale.ROOT))

private fun PositionStationaryTrace.toJson(): JSONObject = JSONObject()
    .putOptional("stationary", stationary)
    .putOptional("state", state?.name?.lowercase(Locale.ROOT))
    .putOptional("zupt_applied", zuptApplied)

private fun JSONObject.putOptional(name: String, value: Any?): JSONObject = apply {
    if (value != null) put(name, value)
}

private fun JSONObject.copyOptional(source: JSONObject, name: String): JSONObject = apply {
    if (source.has(name) && !source.isNull(name)) put(name, source.get(name))
}

private fun canonicalJsonValue(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(
        prefix = "{",
        postfix = "}",
        separator = ",",
    ) { key -> "${JSONObject.quote(key)}:${canonicalJsonValue(value.get(key))}" }
    is JSONArray -> (0 until value.length()).joinToString(
        prefix = "[",
        postfix = "]",
        separator = ",",
    ) { index -> canonicalJsonValue(value.get(index)) }
    is String -> JSONObject.quote(value)
    is Boolean, is Byte, is Short, is Int, is Long -> value.toString()
    is BigInteger -> value.toString()
    is BigDecimal -> require(value.toDouble().isFinite()).let { value.toDouble().toString() }
    is Float -> require(value.isFinite()).let { value.toString() }
    is Double -> require(value.isFinite()).let { value.toString() }
    else -> error("unsupported JSON value: ${value::class.java.name}")
}

private fun isCanonicalUuid(value: String): Boolean =
    CANONICAL_UUID.matches(value) &&
        runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

private val CANONICAL_UUID = Regex(
    "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
)
private val SAFE_METADATA_TOKEN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
private val SAFE_DEVICE_MODEL = Regex("[A-Za-z0-9][A-Za-z0-9._+() -]{0,63}")
private val SAFE_CHECKPOINT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

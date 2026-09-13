package kr.co.hanium.dreamup.walksafe.positioneval.core

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

class TraceFormatException(val reasonCode: String, message: String) : IllegalArgumentException(message)

object TraceV2Parser {
    const val SCHEMA = "walksafe.positioning_trace.v2"
    private const val MAX_BYTES = 64L * 1024L * 1024L
    private const val MAX_LINE_CHARS = 1_048_576
    private const val MAX_RECORDS = 100_000
    private const val MAX_OFFSET_SPREAD_MS = 100.0
    private const val MAX_UTC_QUANTIZATION_MS = 1L
    private const val MOUNT = "PORTRAIT_BACK_OUT_TOP_UP_CHEST_CENTER"
    private const val SOURCE_KIND = "ANDROID_DEBUG_RECORDER"
    private const val TIMEBASE = "ANDROID_ELAPSED_REALTIME_NANOS"
    private val metadataToken = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val deviceModel = Regex("[A-Za-z0-9][A-Za-z0-9._+() -]{0,63}")
    private val checkpointId = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val sha256 = Regex("[0-9a-f]{64}")

    fun parse(file: java.io.File): TraceSession {
        if (!file.isFile || file.length() == 0L) fail("EMPTY_TRACE", "WalkSafe 기록이 비어 있습니다.")
        if (file.length() > MAX_BYTES) fail("TRACE_TOO_LARGE", "WalkSafe 기록이 64MB를 넘습니다.")

        val digest = MessageDigest.getInstance("SHA-256")
        val samples = mutableListOf<TraceSample>()
        val utcAnchors = mutableListOf<MeasurementUtcAnchor>()
        var headerSeen = false
        var footerSeen = false
        var expectedHash: String? = null
        var footerRecordCount: Long? = null
        var bodyCount = 0
        var lineNumber = 0
        var previousSequence = 0L
        var previousElapsed = -1L
        var nextCheckpointOrdinal = 1L

        try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            BufferedReader(InputStreamReader(file.inputStream(), decoder)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    lineNumber += 1
                    if (line.isEmpty()) fail("TRACE_BLANK_LINE", "trace JSONL에는 빈 줄을 넣을 수 없습니다.")
                    if (line.length > MAX_LINE_CHARS) fail("TRACE_LINE_TOO_LARGE", "trace 한 줄이 허용 크기를 넘습니다.")
                    val record = parseObject(line, lineNumber)
                    val recordType = strictString(record, "record_type")

                    if (!headerSeen) {
                        validateHeader(record)
                        headerSeen = true
                        updateDigest(digest, line)
                        continue
                    }
                    if (footerSeen) fail("TRACE_FOOTER_INVALID", "footer 뒤에 레코드가 있습니다.")
                    if (recordType == "footer") {
                        requireExactKeys(record, FOOTER_KEYS)
                        if (strictString(record, "schema_version") != SCHEMA) {
                            fail("TRACE_FOOTER_INVALID", "trace v2 footer schema가 잘못되었습니다.")
                        }
                        footerRecordCount = strictLong(record, "record_count").also {
                            if (it < 0L) fail("TRACE_FOOTER_INVALID", "footer record_count가 음수입니다.")
                        }
                        expectedHash = strictString(record, "content_sha256").lowercase().also {
                            if (!sha256.matches(it)) fail("TRACE_HASH_MISSING", "trace footer의 SHA-256이 올바르지 않습니다.")
                        }
                        footerSeen = true
                        continue
                    }

                    bodyCount += 1
                    if (bodyCount > MAX_RECORDS) fail("TRACE_RECORD_LIMIT", "trace 레코드 수가 허용 한도를 넘습니다.")
                    val sequence = strictLong(record, "seq")
                    if (sequence != previousSequence + 1L) {
                        fail("TRACE_SEQUENCE_INVALID", "trace sequence가 1부터 연속적이지 않습니다.")
                    }
                    val elapsed = strictLong(record, "elapsed_realtime_ns")
                    val measurementElapsed = strictLong(record, "measurement_elapsed_realtime_ns")
                    if (elapsed < 0L || measurementElapsed !in 0L..elapsed || elapsed <= previousElapsed) {
                        fail("TIME_ALIGNMENT_FAILED", "레코드의 단조 시각이 잘못되었습니다.")
                    }
                    val source = strictString(record, "source")
                    val utc = optionalStrictLong(record, "measurement_utc_epoch_ms")
                    if (utc != null && utc <= 0L) fail("TIME_ALIGNMENT_FAILED", "UTC 측정 시각이 잘못되었습니다.")

                    when (recordType) {
                        "position_sample" -> {
                            validatePositionRecord(record, source, utc)
                            val raw = optionalPoint(record, "raw_position")
                            val filtered = optionalPoint(record, "filtered_position")
                            val matched = optionalPoint(record, "matched_position")
                            if (utc != null) {
                                samples += TraceSample(
                                    sequence, utc, measurementElapsed, source, raw, filtered, matched,
                                    routeMatchEvaluated = optionalBoolean(record, "route_match_evaluated") ?: (source == "gnss"),
                                )
                            }
                        }
                        "checkpoint_mark" -> {
                            validateCheckpointRecord(record, source, utc, nextCheckpointOrdinal)
                            nextCheckpointOrdinal += 1L
                        }
                        else -> fail("TRACE_RECORD_UNSUPPORTED", "trace v2에 알 수 없는 레코드가 있습니다.")
                    }
                    if (utc != null) utcAnchors += MeasurementUtcAnchor(measurementElapsed, utc)
                    previousSequence = sequence
                    previousElapsed = elapsed
                    updateDigest(digest, line)
                }
            }
        } catch (_: CharacterCodingException) {
            fail("TRACE_NOT_UTF8", "WalkSafe 기록이 올바른 UTF-8이 아닙니다.")
        }

        if (!headerSeen || !footerSeen || bodyCount == 0) {
            fail("TRACE_INCOMPLETE", "header, 레코드, footer가 모두 필요합니다.")
        }
        if (footerRecordCount != bodyCount.toLong()) {
            fail("TRACE_RECORD_COUNT_MISMATCH", "footer record_count와 실제 레코드 수가 다릅니다.")
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        if (actualHash != expectedHash) fail("TRACE_HASH_MISMATCH", "WalkSafe 기록의 내용 해시가 맞지 않습니다.")
        if (samples.isEmpty()) fail("INSUFFICIENT_REFERENCE", "GNSS에 연결된 UTC 위치가 필요합니다.")

        // Check the original sealed timestamps before deriving millisecond-normalized join times.
        val offsetsMs = utcAnchors.map { it.utcEpochMs - it.elapsedRealtimeNs / 1_000_000.0 }
        val spread = offsetsMs.maxOrNull()!! - offsetsMs.minOrNull()!!
        if (spread > MAX_OFFSET_SPREAD_MS) {
            fail("TIME_ALIGNMENT_FAILED", "UTC와 단조 시각의 차이가 100ms 넘게 흔들립니다.")
        }
        val normalizedUtcByMeasurement = normalizeMeasurementUtc(utcAnchors)
        val centerPoints = samples.mapNotNull(TraceSample::filtered).ifEmpty {
            samples.mapNotNull(TraceSample::raw)
        }
        if (centerPoints.isEmpty()) {
            fail("INSUFFICIENT_REFERENCE", "기준국 선택에는 GNSS 시각에 연결된 filtered 또는 raw 위치가 필요합니다.")
        }
        val center = robustMedianPoint(centerPoints)
        // Exact measurement ties retain receipt order, including replay corrections of the same state.
        val chronologicalSamples = samples.map { sample ->
            sample.copy(utcEpochMs = normalizedUtcByMeasurement.getValue(sample.elapsedRealtimeNs))
        }.sortedWith(
            compareBy<TraceSample> { it.utcEpochMs }.thenBy { it.elapsedRealtimeNs }.thenBy { it.sequence },
        )
        return TraceSession(
            chronologicalSamples,
            chronologicalSamples.first().utcEpochMs,
            chronologicalSamples.last().utcEpochMs,
            center,
            spread,
        )
    }

    private data class MeasurementUtcAnchor(val elapsedRealtimeNs: Long, val utcEpochMs: Long)

    /**
     * Nanosecond measurements may round to neighboring UTC milliseconds after GNSS reanchoring.
     * Equal measurements share one derived UTC; a reversal may move forward by at most 1ms from
     * every original timestamp. Comparing with the prior derived time prevents cumulative drift
     * from being hidden by repeated 1ms clamps. Sealed input bytes and coordinates are untouched.
     */
    private fun normalizeMeasurementUtc(anchors: List<MeasurementUtcAnchor>): Map<Long, Long> {
        val normalized = mutableMapOf<Long, Long>()
        var previousDerivedUtc: Long? = null
        for ((measurementNs, group) in anchors.groupBy { it.elapsedRealtimeNs }.toSortedMap()) {
            val minimumUtc = group.minOf { it.utcEpochMs }
            val maximumUtc = group.maxOf { it.utcEpochMs }
            val derivedUtc = maxOf(maximumUtc, previousDerivedUtc ?: maximumUtc)
            if (derivedUtc - minimumUtc > MAX_UTC_QUANTIZATION_MS) {
                fail("TIME_ALIGNMENT_FAILED", "측정 UTC 시각의 역행 또는 동일 측정 시각 차이가 1ms를 넘습니다.")
            }
            normalized[measurementNs] = derivedUtc
            previousDerivedUtc = derivedUtc
        }
        return normalized
    }

    private fun validateHeader(record: JSONObject) {
        val schema = (record.opt("schema_version") as? String).orEmpty()
        if (schema != SCHEMA) {
            fail(
                if (schema == "walksafe.positioning_trace.v1") "MISSING_ABSOLUTE_UTC" else "UNSUPPORTED_TRACE_SCHEMA",
                "PPK 비교에는 WalkSafe trace v2가 필요합니다.",
            )
        }
        requireExactKeys(record, HEADER_KEYS)
        if (strictString(record, "record_type") != "header" ||
            !isCanonicalUuid(strictString(record, "session_id")) ||
            !isCanonicalUuid(strictString(record, "route_id")) ||
            !metadataToken.matches(strictString(record, "scenario")) ||
            !metadataToken.matches(strictString(record, "environment")) ||
            !metadataToken.matches(strictString(record, "direction")) ||
            !deviceModel.matches(strictString(record, "device_model")) ||
            strictLong(record, "android_api") !in 1L..999L ||
            strictString(record, "mount") != MOUNT ||
            strictString(record, "source_kind") != SOURCE_KIND ||
            strictBoolean(record, "synthetic_contract_only") ||
            strictString(record, "timebase") != TIMEBASE
        ) {
            fail("TRACE_HEADER_INVALID", "trace v2 header 계약이 잘못되었습니다.")
        }
    }

    private fun validatePositionRecord(record: JSONObject, source: String, utc: Long?) {
        requireAllowedAndRequiredKeys(record, POSITION_KEYS, POSITION_REQUIRED_KEYS)
        val carriesUtc = when (source) {
            "gnss", "sensor_gnss_anchored" -> true
            "sensor_monotonic_only" -> false
            else -> fail("TRACE_SOURCE_INVALID", "position_sample source가 잘못되었습니다.")
        }
        if ((utc != null) != carriesUtc) fail("TRACE_SOURCE_INVALID", "source와 UTC 측정 시각이 일치하지 않습니다.")
        optionalBoolean(record, "route_match_evaluated")
        optionalFinite(record, "accuracy_m", 0.0, null)
        optionalFinite(record, "speed_mps", 0.0, null)
        optionalFinite(record, "bearing_deg", 0.0, 360.0)
        validateGnss(record)
        validateStepProfile(record)
        validateHeading(record)
        validateStationary(record)
    }

    private fun validateCheckpointRecord(record: JSONObject, source: String, utc: Long?, expectedOrdinal: Long) {
        requireAllowedAndRequiredKeys(record, CHECKPOINT_KEYS, CHECKPOINT_REQUIRED_KEYS)
        val carriesUtc = when (source) {
            "checkpoint_gnss_anchored" -> true
            "checkpoint_monotonic_only" -> false
            else -> fail("TRACE_SOURCE_INVALID", "checkpoint_mark source가 잘못되었습니다.")
        }
        if ((utc != null) != carriesUtc) fail("TRACE_SOURCE_INVALID", "checkpoint source와 UTC 측정 시각이 일치하지 않습니다.")
        if (!checkpointId.matches(strictString(record, "checkpoint_id")) ||
            strictLong(record, "ordinal") != expectedOrdinal ||
            strictString(record, "stationary_state") != "stationary" ||
            strictLong(record, "stationary_duration_ms") < 1_500L
        ) {
            fail("TRACE_CHECKPOINT_INVALID", "checkpoint 계약이 잘못되었습니다.")
        }
    }

    private fun validateGnss(record: JSONObject) {
        val value = optionalObject(record, "gnss") ?: return
        requireAllowedAndRequiredKeys(value, GNSS_KEYS, emptySet())
        optionalBoolean(value, "l5_available")
        optionalStrictLong(value, "l5_used_satellite_count")?.let {
            if (it < 0L) fail("TRACE_FIELD_INVALID", "l5 위성 수가 잘못되었습니다.")
        }
        optionalFinite(value, "mean_cn0_db_hz", 0.0, null)
        optionalFinite(value, "measurement_noise_m", 0.0, null)
        optionalString(value, "risk")?.let {
            if (it !in setOf("low", "medium", "high", "unavailable")) fail("TRACE_FIELD_INVALID", "GNSS risk가 잘못되었습니다.")
        }
    }

    private fun validateStepProfile(record: JSONObject) {
        val value = optionalObject(record, "step_profile") ?: return
        requireAllowedAndRequiredKeys(value, STEP_KEYS, emptySet())
        optionalStrictLong(value, "step_count")?.let { if (it < 0L) fail("TRACE_FIELD_INVALID", "걸음 수가 잘못되었습니다.") }
        optionalBoolean(value, "step_detected")
        optionalFinite(value, "current_step_length_m", 0.0, null, exclusiveMinimum = true)
        optionalFinite(value, "profile_step_length_m", 0.0, null, exclusiveMinimum = true)
        optionalStrictLong(value, "profile_sample_count")?.let {
            if (it < 0L) fail("TRACE_FIELD_INVALID", "보폭 표본 수가 잘못되었습니다.")
        }
    }

    private fun validateHeading(record: JSONObject) {
        val value = optionalObject(record, "heading") ?: return
        requireAllowedAndRequiredKeys(value, HEADING_KEYS, emptySet())
        optionalFinite(value, "selected_deg", 0.0, 360.0)
        optionalString(value, "source")?.let {
            if (it !in setOf("gnss_course", "magnetic_rotation_vector", "game_rotation_vector", "none")) {
                fail("TRACE_FIELD_INVALID", "heading source가 잘못되었습니다.")
            }
        }
    }

    private fun validateStationary(record: JSONObject) {
        val value = optionalObject(record, "stationary") ?: return
        requireAllowedAndRequiredKeys(value, STATIONARY_KEYS, emptySet())
        optionalBoolean(value, "stationary")
        optionalBoolean(value, "zupt_applied")
        optionalString(value, "state")?.let {
            if (it !in setOf("moving", "candidate", "stationary", "unknown")) {
                fail("TRACE_FIELD_INVALID", "stationary state가 잘못되었습니다.")
            }
        }
    }

    private fun parseObject(line: String, lineNumber: Int): JSONObject = runCatching { JSONObject(line) }.getOrElse {
        fail("TRACE_INVALID_JSON", "${lineNumber}번째 줄이 올바른 JSON 객체가 아닙니다.")
    }

    private fun updateDigest(digest: MessageDigest, line: String) {
        digest.update(line.toByteArray(StandardCharsets.UTF_8))
        digest.update('\n'.code.toByte())
    }

    private fun requireExactKeys(value: JSONObject, keys: Set<String>) =
        requireAllowedAndRequiredKeys(value, keys, keys)

    private fun requireAllowedAndRequiredKeys(value: JSONObject, allowed: Set<String>, required: Set<String>) {
        val actual = value.keys().asSequence().toSet()
        if (!actual.containsAll(required) || !allowed.containsAll(actual)) {
            fail("TRACE_FIELD_INVALID", "trace 필드 집합이 v2 계약과 다릅니다.")
        }
    }

    private fun strictString(value: JSONObject, name: String): String = (value.opt(name) as? String)
        ?.takeIf(String::isNotEmpty) ?: fail("TRACE_FIELD_INVALID", "$name 필드가 없거나 문자열이 아닙니다.")

    private fun optionalString(value: JSONObject, name: String): String? {
        if (!value.has(name)) return null
        return value.opt(name) as? String ?: fail("TRACE_FIELD_INVALID", "$name 필드가 문자열이 아닙니다.")
    }

    private fun strictBoolean(value: JSONObject, name: String): Boolean = value.opt(name) as? Boolean
        ?: fail("TRACE_FIELD_INVALID", "$name 필드가 없거나 boolean이 아닙니다.")

    private fun optionalBoolean(value: JSONObject, name: String): Boolean? {
        if (!value.has(name)) return null
        return value.opt(name) as? Boolean ?: fail("TRACE_FIELD_INVALID", "$name 필드가 boolean이 아닙니다.")
    }

    private fun strictLong(value: JSONObject, name: String): Long {
        val raw = value.opt(name)
        if (raw !is Number) fail("TRACE_FIELD_INVALID", "$name 필드가 없거나 정수가 아닙니다.")
        return raw.toString().toLongOrNull() ?: fail("TRACE_FIELD_INVALID", "$name 필드가 정수가 아닙니다.")
    }

    private fun optionalStrictLong(value: JSONObject, name: String): Long? {
        if (!value.has(name)) return null
        return strictLong(value, name)
    }

    private fun optionalFinite(
        value: JSONObject,
        name: String,
        minimum: Double,
        maximumExclusive: Double?,
        exclusiveMinimum: Boolean = false,
    ): Double? {
        if (!value.has(name)) return null
        val number = (value.opt(name) as? Number)?.toDouble()
            ?.takeIf(Double::isFinite) ?: fail("TRACE_FIELD_INVALID", "$name 필드가 유한한 숫자가 아닙니다.")
        val belowMinimum = if (exclusiveMinimum) number <= minimum else number < minimum
        if (belowMinimum || (maximumExclusive != null && number >= maximumExclusive)) {
            fail("TRACE_FIELD_INVALID", "$name 필드 범위가 잘못되었습니다.")
        }
        return number
    }

    private fun optionalObject(value: JSONObject, name: String): JSONObject? {
        if (!value.has(name)) return null
        return value.opt(name) as? JSONObject ?: fail("TRACE_FIELD_INVALID", "$name 필드가 JSON 객체가 아닙니다.")
    }

    private fun optionalPoint(record: JSONObject, name: String): GeoPoint? {
        val value = optionalObject(record, name) ?: return null
        requireExactKeys(value, COORDINATE_KEYS)
        val latitude = (value.opt("latitude_deg") as? Number)?.toDouble()?.takeIf(Double::isFinite)
            ?: fail("TRACE_COORDINATE_INVALID", "$name 위도가 잘못되었습니다.")
        val longitude = (value.opt("longitude_deg") as? Number)?.toDouble()?.takeIf(Double::isFinite)
            ?: fail("TRACE_COORDINATE_INVALID", "$name 경도가 잘못되었습니다.")
        return runCatching { GeoPoint(latitude, longitude) }
            .getOrElse { fail("TRACE_COORDINATE_INVALID", "$name 좌표 범위가 잘못되었습니다.") }
    }

    private fun robustMedianPoint(points: List<GeoPoint>): GeoPoint {
        val referenceLongitude = points.first().longitudeDeg
        val unwrappedLongitudes = points.map { point ->
            referenceLongitude + normalizedLongitudeDelta(point.longitudeDeg - referenceLongitude)
        }
        return GeoPoint(
            median(points.map(GeoPoint::latitudeDeg)),
            normalizedLongitude(median(unwrappedLongitudes)),
        )
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    private fun normalizedLongitudeDelta(value: Double): Double = ((value + 540.0) % 360.0) - 180.0

    private fun normalizedLongitude(value: Double): Double = ((value + 540.0) % 360.0) - 180.0

    private fun isCanonicalUuid(value: String): Boolean =
        runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    private fun fail(code: String, message: String): Nothing = throw TraceFormatException(code, message)

    private val HEADER_KEYS = setOf(
        "schema_version", "record_type", "session_id", "route_id", "scenario", "environment",
        "direction", "device_model", "android_api", "mount", "source_kind", "synthetic_contract_only", "timebase",
    )
    private val FOOTER_KEYS = setOf("record_type", "schema_version", "record_count", "content_sha256")
    private val POSITION_REQUIRED_KEYS =
        setOf("record_type", "seq", "elapsed_realtime_ns", "measurement_elapsed_realtime_ns", "source")
    private val POSITION_KEYS = POSITION_REQUIRED_KEYS + setOf(
        "route_match_evaluated",
        "measurement_utc_epoch_ms", "raw_position", "filtered_position", "matched_position", "accuracy_m",
        "speed_mps", "bearing_deg", "gnss", "step_profile", "heading", "stationary",
    )
    private val CHECKPOINT_REQUIRED_KEYS = setOf(
        "record_type", "seq", "elapsed_realtime_ns", "measurement_elapsed_realtime_ns", "source",
        "checkpoint_id", "ordinal", "stationary_state", "stationary_duration_ms",
    )
    private val CHECKPOINT_KEYS = CHECKPOINT_REQUIRED_KEYS + "measurement_utc_epoch_ms"
    private val COORDINATE_KEYS = setOf("latitude_deg", "longitude_deg")
    private val GNSS_KEYS = setOf("l5_available", "l5_used_satellite_count", "mean_cn0_db_hz", "risk", "measurement_noise_m")
    private val STEP_KEYS = setOf("step_count", "step_detected", "current_step_length_m", "profile_step_length_m", "profile_sample_count")
    private val HEADING_KEYS = setOf("selected_deg", "source")
    private val STATIONARY_KEYS = setOf("stationary", "state", "zupt_applied")
}

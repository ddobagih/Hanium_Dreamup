package kr.co.hanium.dreamup.walksafe.fieldlog

import org.json.JSONArray
import org.json.JSONObject

/**
 * 16-17 #36: the encrypted archive can be retrieved but not analysed on the host, so the app keeps
 * an approved in-app statistics summary. ARCHIVE_ONLY means encrypted records exist without a
 * summary; ANALYZABLE means a summary of a completed session is available in the app.
 */
enum class FieldSessionAnalysisState { NONE, ARCHIVE_ONLY, ANALYZABLE }

/**
 * Approved statistics only (FP-050 design_rules[1]: slow cases, stale/missing frames): counts,
 * durations and fixed latency buckets. No coordinate, heading, text, class name, model output,
 * image, audio, destination, or identifier except the local field session id.
 */
data class FieldSessionStatsSummary(
    val sessionId: String,
    val sessionDurationMs: Long,
    /** False when the process restarted mid-session: only the part after the restart is covered. */
    val coversWholeSession: Boolean,
    val telemetryCount: Int,
    val staleTelemetryCount: Int,
    val detectorLatencySampleCount: Int,
    val detectorLatencyMaxMs: Long?,
    val detectorLatencyP50UpperBoundMs: Long?,
    val detectorLatencyP95UpperBoundMs: Long?,
    val detectorLatencyHistogram: List<Int>,
    val cameraNonMetricSampleCount: Int,
    val heartbeatGapRecordCounts: Map<FieldHeartbeatState, Int>,
    val longestObservationGapMs: Long,
) {
    val staleTelemetryRatio: Double?
        get() = if (telemetryCount == 0) null else staleTelemetryCount.toDouble() / telemetryCount

    fun toJson(): JSONObject = JSONObject()
        .put("schema_version", FIELD_STATS_SUMMARY_SCHEMA)
        .put("session_id", sessionId)
        .put("session_duration_ms", sessionDurationMs)
        .put("covers_whole_session", coversWholeSession)
        .put("telemetry_count", telemetryCount)
        .put("stale_telemetry_count", staleTelemetryCount)
        .put("stale_telemetry_ratio", staleTelemetryRatio ?: JSONObject.NULL)
        .put("detector_latency_sample_count", detectorLatencySampleCount)
        .put("detector_latency_max_ms", detectorLatencyMaxMs ?: JSONObject.NULL)
        .put("detector_latency_p50_upper_bound_ms", detectorLatencyP50UpperBoundMs ?: JSONObject.NULL)
        .put("detector_latency_p95_upper_bound_ms", detectorLatencyP95UpperBoundMs ?: JSONObject.NULL)
        .put(
            "detector_latency_ms_histogram",
            JSONObject()
                .put("upper_bounds_ms", JSONArray(FIELD_STATS_LATENCY_BUCKET_UPPER_BOUNDS_MS))
                .put("counts", JSONArray(detectorLatencyHistogram)),
        )
        .put("camera_non_metric_sample_count", cameraNonMetricSampleCount)
        .put(
            "heartbeat_gap_record_counts",
            JSONObject().also { counts ->
                heartbeatGapRecordCounts.toSortedMap(compareBy { it.name }).forEach { (state, count) ->
                    counts.put(state.name.lowercase(), count)
                }
            },
        )
        .put("longest_observation_gap_ms", longestObservationGapMs)

    /** Short debug-screen text; the same values as [toJson]. */
    fun displayText(): String =
        "세션 ${sessionDurationMs / 1_000L}s · 표본 $telemetryCount · 오래된 화면 $staleTelemetryCount · " +
            "지연 p50≤${detectorLatencyP50UpperBoundMs ?: "-"}ms p95≤${detectorLatencyP95UpperBoundMs ?: "-"}ms " +
            "max ${detectorLatencyMaxMs ?: "-"}ms · 최장 관측 공백 ${longestObservationGapMs}ms" +
            if (coversWholeSession) "" else " · 재시작 이후만"
}

/** Measurement buckets (exclusive upper bounds, ms), not a latency budget; last bucket is >= 800 ms. */
val FIELD_STATS_LATENCY_BUCKET_UPPER_BOUNDS_MS: List<Long> = listOf(33L, 66L, 100L, 200L, 400L, 800L)
const val FIELD_STATS_SUMMARY_SCHEMA = "walksafe.android.field-session-stats.v1"
/** Event written by [FieldSessionHeartbeat]; its state and gap length feed the summary. */
internal const val FIELD_HEARTBEAT_EVENT_NAME = "field_heartbeat"

/** Running aggregate of one field session; owned by [PersistentFieldSessionLog] under its lock. */
internal class FieldSessionStatsAccumulator(
    val sessionId: String,
    private val startedAtMs: Long,
    private val coversWholeSession: Boolean,
) {
    private var telemetry = 0
    private var stale = 0
    private var latencySamples = 0
    private var latencyMax: Long? = null
    private val latencyBuckets = IntArray(FIELD_STATS_LATENCY_BUCKET_UPPER_BOUNDS_MS.size + 1)
    private var cameraSamples = 0
    private val gapRecords = mutableMapOf<FieldHeartbeatState, Int>()
    private var longestGapMs = 0L

    fun onTelemetry(detectDurationMs: Long?, staleReason: String?) {
        telemetry = saturatingAdd(telemetry, 1)
        if (!staleReason.isNullOrBlank()) stale = saturatingAdd(stale, 1)
        detectDurationMs?.takeIf { it >= 0L }?.let(::onLatency)
    }

    fun onCameraNonMetricSample(inferenceMs: Long?) {
        cameraSamples = saturatingAdd(cameraSamples, 1)
        inferenceMs?.takeIf { it >= 0L }?.let(::onLatency)
    }

    fun onHeartbeat(state: String?, gapDurationMs: Long?) {
        val parsed = FieldHeartbeatState.entries.firstOrNull { it.name == state } ?: return
        if (parsed != FieldHeartbeatState.FRAMES_FLOWING) {
            gapRecords[parsed] = saturatingAdd(gapRecords[parsed] ?: 0, 1)
        }
        gapDurationMs?.takeIf { it >= 0L }?.let { longestGapMs = maxOf(longestGapMs, it) }
    }

    fun summary(nowMs: Long): FieldSessionStatsSummary = FieldSessionStatsSummary(
        sessionId = sessionId,
        sessionDurationMs = (nowMs - startedAtMs).coerceAtLeast(0L),
        coversWholeSession = coversWholeSession,
        telemetryCount = telemetry,
        staleTelemetryCount = stale,
        detectorLatencySampleCount = latencySamples,
        detectorLatencyMaxMs = latencyMax,
        detectorLatencyP50UpperBoundMs = percentileUpperBound(0.50),
        detectorLatencyP95UpperBoundMs = percentileUpperBound(0.95),
        detectorLatencyHistogram = latencyBuckets.toList(),
        cameraNonMetricSampleCount = cameraSamples,
        heartbeatGapRecordCounts = gapRecords.toMap(),
        longestObservationGapMs = longestGapMs,
    )

    private fun onLatency(valueMs: Long) {
        latencySamples = saturatingAdd(latencySamples, 1)
        latencyMax = maxOf(latencyMax ?: 0L, valueMs)
        val index = FIELD_STATS_LATENCY_BUCKET_UPPER_BOUNDS_MS.indexOfFirst { valueMs < it }
        latencyBuckets[if (index < 0) latencyBuckets.lastIndex else index] += 1
    }

    /** The upper bound of the bucket holding the percentile; the open last bucket reports max. */
    private fun percentileUpperBound(fraction: Double): Long? {
        if (latencySamples == 0) return null
        val rank = kotlin.math.ceil(fraction * latencySamples).toLong().coerceAtLeast(1L)
        var seen = 0L
        latencyBuckets.forEachIndexed { index, count ->
            seen += count
            if (seen >= rank) return FIELD_STATS_LATENCY_BUCKET_UPPER_BOUNDS_MS.getOrNull(index) ?: latencyMax
        }
        return latencyMax
    }

    private fun saturatingAdd(left: Int, right: Int): Int =
        if (Int.MAX_VALUE - left < right) Int.MAX_VALUE else left + right
}

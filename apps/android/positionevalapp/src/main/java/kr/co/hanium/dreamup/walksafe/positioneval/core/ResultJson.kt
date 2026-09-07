package kr.co.hanium.dreamup.walksafe.positioneval.core

import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

object ResultJson {
    fun processingFailure(
        reasonCode: String,
        message: String,
        trace: ImportedArtifact?,
        gnss: ImportedArtifact?,
        traceSession: TraceSession?,
        station: BaseStation? = null,
        downloads: List<StrictHttpsTransport.Download> = emptyList(),
        generatedAtUtc: Instant? = null,
        truthInsufficient: Boolean = false,
    ): String {
        val root = JSONObject()
            .put("schema_version", "walksafe.ppk_evaluation.v1")
            .put("status", if (truthInsufficient) "truth_insufficient" else "failed")
            .put("reason_codes", JSONArray().put(reasonCode))
            .put("message", message)
        generatedAtUtc?.let { root.put("generated_at_utc", it.toString()) }
        root.put("inputs", JSONObject().apply {
            if (trace != null) put("walksafe_trace", artifact(trace))
            if (gnss != null) put("gnss_logger", artifact(gnss))
        })
        if (traceSession != null) root.put("trace", trace(traceSession))
        if (station != null) root.put("reference_station", station(station))
        root.put("downloads", downloads(downloads))
            .put("evaluation_policy", evaluationPolicy())
            .put("reference_provenance", referenceProvenance())
            .put("components", components())
        return canonicalJson(root) + "\n"
    }

    fun evaluation(
        outcome: EvaluationOutcome,
        trace: ImportedArtifact,
        gnss: ImportedArtifact,
        station: BaseStation,
        downloads: List<StrictHttpsTransport.Download>,
        ppk: ParsedPpk? = null,
        generatedAtUtc: Instant? = null,
    ): String {
        val root = JSONObject()
            .put("schema_version", "walksafe.ppk_evaluation.v1")
            .put("status", if (outcome.status == EvaluationStatus.EVALUATED) "evaluated" else "truth_insufficient")
            .put("reason_codes", JSONArray(outcome.reasonCodes.distinct().sorted()))
        generatedAtUtc?.let { root.put("generated_at_utc", it.toString()) }
        root.put("inputs", JSONObject().put("walksafe_trace", artifact(trace)).put("gnss_logger", artifact(gnss)))
            .put("reference_station", station(station))
            .put("downloads", downloads(downloads))
            .put("truth", JSONObject().apply {
                put("input_epoch_count", ppk?.epochs?.size ?: JSONObject.NULL)
                put("fixed_epoch_count_q1", ppk?.fixedCount ?: JSONObject.NULL)
                put("evaluation_grid_count", outcome.gridCount)
                put("interpolated_fixed_grid_count", outcome.eligibleTruthCount)
                put(
                    "interpolated_fixed_grid_coverage",
                    if (outcome.gridCount == 0) 0.0 else outcome.eligibleTruthCount.toDouble() / outcome.gridCount,
                )
            })
            .put("channels", JSONArray().apply {
                outcome.metrics.sortedBy { it.channel.wireName }.forEach { metric ->
                    val enough = metric.availableCount >= PositionEvaluator.MIN_CHANNEL_SAMPLES
                    put(JSONObject().apply {
                        put("channel", metric.channel.wireName)
                        put("status", if (enough) "evaluated" else "insufficient_samples")
                        put("reason_codes", if (enough) JSONArray() else JSONArray().put("CHANNEL_SAMPLE_COUNT_TOO_LOW"))
                        put("eligible_truth_count", metric.eligibleTruthCount)
                        put("available_count", metric.availableCount)
                        putFinite("p50_error_m", metric.p50ErrorM)
                        putFinite("p95_error_m", metric.p95ErrorM)
                        putFinite("rmse_error_m", metric.rmseErrorM)
                        putFinite("max_error_m", metric.maxErrorM)
                        put("within_2m_count", metric.withinTwoMetersCount)
                        put("two_meter_coverage", metric.twoMeterCoverage)
                        put("availability", metric.availability)
                    })
                }
            })
            .put("evaluation_policy", evaluationPolicy())
            .put("reference_provenance", referenceProvenance())
            .put("components", components())
        return canonicalJson(root) + "\n"
    }

    private fun trace(value: TraceSession) = JSONObject()
        .put("schema_version", TraceV2Parser.SCHEMA)
        .put("start_utc_epoch_ms", value.startUtcEpochMs)
        .put("end_utc_epoch_ms", value.endUtcEpochMs)
        .put("utc_position_sample_count", value.samples.size)
        .put("time_offset_spread_ms", value.timeOffsetSpreadMs)

    private fun artifact(value: ImportedArtifact) = JSONObject()
        .put("display_name", value.displayName)
        .put("byte_count", value.byteCount)
        .put("sha256", value.sha256)

    private fun station(value: BaseStation) = JSONObject()
        .put("code", value.code)
        .put("name", value.name)
        .put("latitude_deg", value.location.latitudeDeg)
        .put("longitude_deg", value.location.longitudeDeg)

    private fun downloads(values: List<StrictHttpsTransport.Download>) = JSONArray().apply {
        values.sortedWith(compareBy({ it.sourceUrl }, { it.sha256 }, { it.byteCount })).forEach { item ->
            put(JSONObject().put("source_url", item.sourceUrl).put("byte_count", item.byteCount).put("sha256", item.sha256))
        }
    }

    private fun evaluationPolicy() = JSONObject()
        .put("policy_id", PositionEvaluator.POLICY_ID)
        .put("policy_version", PositionEvaluator.POLICY_VERSION)
        .put("truth_quality", "RTKLIB_Q1_FIX_ONLY")
        .put("alignment", "CAUSAL_LATEST_PRIOR_ESTIMATE")
        .put("grid_interval_ms", PositionEvaluator.GRID_INTERVAL_MS)
        .put("max_truth_interpolation_gap_ms", PositionEvaluator.MAX_TRUTH_INTERPOLATION_GAP_MS)
        .put("max_estimate_age_ms", PositionEvaluator.MAX_ESTIMATE_AGE_MS)
        .put("minimum_evaluation_window_ms", PositionEvaluator.MIN_EVALUATION_WINDOW_MS)
        .put("minimum_interpolated_fixed_grid_count", PositionEvaluator.MIN_TRUTH_SAMPLES)
        .put("minimum_truth_coverage", PositionEvaluator.MIN_TRUTH_COVERAGE)
        .put("minimum_channel_samples", PositionEvaluator.MIN_CHANNEL_SAMPLES)
        .put("time_policy_version", PpkPosParser.TIME_POLICY_VERSION)
        .put("time_policy_supported_end_exclusive_utc", PpkPosParser.SUPPORTED_END_EXCLUSIVE_UTC)

    private fun referenceProvenance() = JSONObject()
        .put("method", "SAME_PHONE_POSTPROCESSED_PPK")
        .put("independence", "NOT_INDEPENDENT")
        .put("intended_use", "DEVELOPMENT_EVALUATION_ONLY")

    private fun components() = JSONObject()
        .put("gnsslogger_txt_to_rinex", converterComponent(PinnedAndroidRinexConverter().status))
        .put("ppk_engine", engineComponent(Demo5JniPpkEngine().status))

    private fun converterComponent(status: ComponentStatus) = component(status, PinnedAndroidRinexConverter.COMMIT)
        .put("upstream", PinnedAndroidRinexConverter.UPSTREAM)
        .put("license", PinnedAndroidRinexConverter.LICENSE)

    private fun engineComponent(status: ComponentStatus) = component(status, Demo5JniPpkEngine.COMMIT)
        .put("name", Demo5JniPpkEngine().name)
        .put("version", Demo5JniPpkEngine.VERSION)
        .put("upstream", Demo5JniPpkEngine.UPSTREAM)
        .put("license", Demo5JniPpkEngine.LICENSE)

    private fun component(status: ComponentStatus, commit: String) = JSONObject()
        .put("available", status.available)
        .put("code", status.code)
        .put("source_commit", commit)

    private fun JSONObject.putFinite(name: String, value: Double?) {
        if (value == null || !value.isFinite()) put(name, JSONObject.NULL) else put(name, value)
    }

    private fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(
            separator = ",",
            prefix = "{",
            postfix = "}",
        ) { key ->
            "${JSONObject.quote(key)}:${canonicalJson(value.get(key))}"
        }
        is JSONArray -> (0 until value.length()).joinToString(
            separator = ",",
            prefix = "[",
            postfix = "]",
        ) { canonicalJson(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Boolean, is Byte, is Short, is Int, is Long, is BigInteger -> value.toString()
        is BigDecimal -> value.toPlainString()
        is Float -> if (value.isFinite()) value.toString() else error("non-finite JSON number")
        is Double -> if (value.isFinite()) value.toString() else error("non-finite JSON number")
        else -> error("unsupported JSON value: ${value::class.java.name}")
    }
}

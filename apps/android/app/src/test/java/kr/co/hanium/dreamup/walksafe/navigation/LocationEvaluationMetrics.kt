package kr.co.hanium.dreamup.walksafe.navigation

import kotlin.math.ceil
import kotlin.math.hypot
import org.json.JSONObject

data class LocationEvaluationSample(
    val scenarioId: String,
    val timestampMs: Long,
    val truthEastM: Double,
    val truthNorthM: Double,
    val estimateEastM: Double?,
    val estimateNorthM: Double?,
    val estimateAgeMs: Long?,
)

data class LocationEvaluationResult(
    val sampleCount: Int,
    val availableCount: Int,
    val p50ErrorM: Double,
    val p95ErrorM: Double,
    val twoMeterCoverage: Double,
    val availability: Double,
)

object LocationEvaluationMetrics {
    const val DEFAULT_MAX_TRUTH_GAP_MS = 1_500L
    const val DEFAULT_MAX_ESTIMATE_AGE_MS = 2_000L
    private const val TWO_METERS = 2.0

    fun calculate(
        samples: List<LocationEvaluationSample>,
        maxTruthGapMs: Long = DEFAULT_MAX_TRUTH_GAP_MS,
        maxEstimateAgeMs: Long = DEFAULT_MAX_ESTIMATE_AGE_MS,
    ): LocationEvaluationResult {
        require(samples.isNotEmpty()) { "location evaluation requires truth samples" }
        require(maxTruthGapMs > 0L) { "max truth gap must be positive" }
        require(maxEstimateAgeMs >= 0L) { "max estimate age must not be negative" }

        var previousTimestampMs: Long? = null
        val errors = ArrayList<Double>(samples.size)
        samples.forEach { sample ->
            require(sample.scenarioId.isNotBlank()) { "scenario id must not be blank" }
            require(sample.timestampMs >= 0L) { "timestamp must not be negative" }
            require(sample.truthEastM.isFinite() && sample.truthNorthM.isFinite()) {
                "truth coordinates must be finite"
            }
            previousTimestampMs?.let { previous ->
                require(sample.timestampMs > previous) { "timestamps must be strictly increasing" }
                require(sample.timestampMs - previous <= maxTruthGapMs) { "truth gap exceeds limit" }
            }
            previousTimestampMs = sample.timestampMs

            val hasEast = sample.estimateEastM != null
            val hasNorth = sample.estimateNorthM != null
            require(hasEast == hasNorth) { "estimate coordinates must be present as a pair" }
            if (!hasEast) {
                require(sample.estimateAgeMs == null) { "missing estimate must not have an age" }
                return@forEach
            }

            val east = requireNotNull(sample.estimateEastM)
            val north = requireNotNull(sample.estimateNorthM)
            val ageMs = requireNotNull(sample.estimateAgeMs) { "available estimate requires an age" }
            require(east.isFinite() && north.isFinite()) { "estimate coordinates must be finite" }
            require(ageMs >= 0L) { "estimate age must not be negative" }
            if (ageMs <= maxEstimateAgeMs) {
                errors += hypot(east - sample.truthEastM, north - sample.truthNorthM)
            }
        }
        require(errors.isNotEmpty()) { "location evaluation requires an available estimate" }

        val sortedErrors = errors.sorted()
        val withinTwoMeters = errors.count { it <= TWO_METERS }
        return LocationEvaluationResult(
            sampleCount = samples.size,
            availableCount = errors.size,
            p50ErrorM = nearestRank(sortedErrors, 0.50),
            p95ErrorM = nearestRank(sortedErrors, 0.95),
            twoMeterCoverage = withinTwoMeters.toDouble() / samples.size,
            availability = errors.size.toDouble() / samples.size,
        )
    }

    private fun nearestRank(sortedValues: List<Double>, percentile: Double): Double {
        val oneBasedRank = ceil(percentile * sortedValues.size).toInt().coerceIn(1, sortedValues.size)
        return sortedValues[oneBasedRank - 1]
    }
}

data class LocationReplayFixture(
    val schemaVersion: String,
    val coordinateFrame: String,
    val absoluteCoordinates: Boolean,
    val wallClockTime: Boolean,
    val syntheticContractOnly: Boolean,
    val scenarios: Map<String, List<LocationEvaluationSample>>,
)

object LocationReplayFixtureParser {
    private val headerKeys = setOf(
        "record_type",
        "schema_version",
        "coordinate_frame",
        "absolute_coordinates",
        "wall_clock_time",
        "synthetic_contract_only",
    )
    private val sampleKeys = setOf(
        "record_type",
        "scenario_id",
        "t_ms",
        "truth_e_m",
        "truth_n_m",
        "estimate_e_m",
        "estimate_n_m",
        "estimate_age_ms",
    )

    fun parse(text: String): LocationReplayFixture {
        val records = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        require(records.size >= 2) { "fixture requires a header and samples" }
        val header = JSONObject(records.first())
        require(header.keys().asSequence().toSet() == headerKeys) { "unexpected header fields" }
        require(header.getString("record_type") == "header") { "first record must be a header" }
        require(header.getString("coordinate_frame") == "LOCAL_ENU_METERS") {
            "fixture coordinates must use local ENU meters"
        }
        require(!header.getBoolean("absolute_coordinates")) { "absolute coordinates are forbidden" }
        require(!header.getBoolean("wall_clock_time")) { "wall-clock time is forbidden" }

        val scenarios = linkedMapOf<String, MutableList<LocationEvaluationSample>>()
        records.drop(1).forEach { line ->
            val record = JSONObject(line)
            require(record.keys().asSequence().toSet() == sampleKeys) { "unexpected sample fields" }
            require(record.getString("record_type") == "sample") { "non-header records must be samples" }
            val scenarioId = record.getString("scenario_id")
            scenarios.getOrPut(scenarioId) { mutableListOf() } += LocationEvaluationSample(
                scenarioId = scenarioId,
                timestampMs = record.getLong("t_ms"),
                truthEastM = record.getDouble("truth_e_m"),
                truthNorthM = record.getDouble("truth_n_m"),
                estimateEastM = record.nullableDouble("estimate_e_m"),
                estimateNorthM = record.nullableDouble("estimate_n_m"),
                estimateAgeMs = record.nullableLong("estimate_age_ms"),
            )
        }
        require(scenarios.isNotEmpty()) { "fixture requires scenarios" }
        scenarios.values.forEach { LocationEvaluationMetrics.calculate(it) }
        return LocationReplayFixture(
            schemaVersion = header.getString("schema_version"),
            coordinateFrame = header.getString("coordinate_frame"),
            absoluteCoordinates = header.getBoolean("absolute_coordinates"),
            wallClockTime = header.getBoolean("wall_clock_time"),
            syntheticContractOnly = header.getBoolean("synthetic_contract_only"),
            scenarios = scenarios,
        )
    }

    private fun JSONObject.nullableDouble(name: String): Double? =
        if (isNull(name)) null else getDouble(name)

    private fun JSONObject.nullableLong(name: String): Long? =
        if (isNull(name)) null else getLong(name)
}

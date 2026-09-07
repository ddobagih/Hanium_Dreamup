package kr.co.hanium.dreamup.walksafe.positioneval.core

data class GeoPoint(val latitudeDeg: Double, val longitudeDeg: Double) {
    init {
        require(latitudeDeg.isFinite() && latitudeDeg in -90.0..90.0)
        require(longitudeDeg.isFinite() && longitudeDeg in -180.0..180.0)
    }
}

enum class PositionChannel(val wireName: String) {
    RAW("raw"),
    FILTERED("filtered"),
    MATCHED("matched"),
}

data class TraceSample(
    val sequence: Long,
    val utcEpochMs: Long,
    val elapsedRealtimeNs: Long,
    val measurementTimeSource: String,
    val raw: GeoPoint?,
    val filtered: GeoPoint?,
    val matched: GeoPoint?,
) {
    fun point(channel: PositionChannel): GeoPoint? = when (channel) {
        PositionChannel.RAW -> raw
        PositionChannel.FILTERED -> filtered
        PositionChannel.MATCHED -> matched
    }
}

data class TraceSession(
    val samples: List<TraceSample>,
    val startUtcEpochMs: Long,
    val endUtcEpochMs: Long,
    val center: GeoPoint,
    val timeOffsetSpreadMs: Double,
)

data class ImportedArtifact(
    val displayName: String,
    val file: java.io.File,
    val byteCount: Long,
    val sha256: String,
)

data class BaseStation(
    val code: String,
    val name: String,
    val location: GeoPoint,
    val operational: Boolean,
    val hourlyHealthy: Boolean = false,
)

data class RinexHour(
    val portalHour: String,
    val availableCount: Int,
    val expectedCount: Int,
) {
    val complete: Boolean get() = expectedCount > 0 && availableCount == expectedCount
}

data class PpkEpoch(
    val utcEpochMs: Long,
    val point: GeoPoint,
    val heightM: Double,
    val quality: Int,
    val satelliteCount: Int,
    val stdNorthM: Double?,
    val stdEastM: Double?,
    val ratio: Double?,
)

data class ChannelMetrics(
    val channel: PositionChannel,
    val eligibleTruthCount: Int,
    val availableCount: Int,
    val p50ErrorM: Double?,
    val p95ErrorM: Double?,
    val rmseErrorM: Double?,
    val maxErrorM: Double?,
    val withinTwoMetersCount: Int,
    val twoMeterCoverage: Double,
    val availability: Double,
)

enum class EvaluationStatus { EVALUATED, TRUTH_INSUFFICIENT }

data class EvaluationOutcome(
    val status: EvaluationStatus,
    val reasonCodes: List<String>,
    val gridCount: Int,
    val eligibleTruthCount: Int,
    val metrics: List<ChannelMetrics>,
)

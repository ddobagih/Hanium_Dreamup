package kr.co.hanium.dreamup.walksafe.positioneval.core

import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.time.temporal.ChronoField
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class PpkFormatException(message: String) : IllegalArgumentException(message)

data class ParsedPpk(val timeSystem: String, val epochs: List<PpkEpoch>, val fixedCount: Int)

object PpkPosParser {
    const val TIME_POLICY_VERSION = "gps-utc-leap-table-2026a"
    const val SUPPORTED_END_EXCLUSIVE_UTC = "2027-01-01T00:00:00Z"
    private const val MAX_POS_LINES = 1_000_000
    private val supportedStart = LocalDateTime.of(1980, 1, 6, 0, 0)
    private val supportedEndExclusive = LocalDateTime.of(2027, 1, 1, 0, 0)
    private val dateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("uuuu/MM/dd HH:mm:ss")
        .optionalStart()
        .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
        .optionalEnd()
        .toFormatter()
        .withResolverStyle(ResolverStyle.STRICT)

    fun parse(text: String): ParsedPpk = parseLines(text.lineSequence())

    fun parse(file: File): ParsedPpk {
        PpkOutputContract.validate(file)
        return file.bufferedReader(Charsets.US_ASCII).use { reader -> parseLines(reader.lineSequence()) }
    }

    private fun parseLines(lines: Sequence<String>): ParsedPpk {
        var timeSystem: String? = null
        var lineCount = 0
        val epochs = mutableListOf<PpkEpoch>()
        lines.forEach { rawLine ->
            lineCount += 1
            if (lineCount > MAX_POS_LINES) throw PpkFormatException(".pos 행 수가 허용 한도를 넘습니다.")
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("%")) {
                if (line.contains("latitude(deg)") && line.contains("longitude(deg)")) {
                    val candidate = line.removePrefix("%").trim().substringBefore(' ').uppercase()
                    if (candidate !in setOf("UTC", "GPST")) throw PpkFormatException(".pos 시간계는 UTC 또는 GPST여야 합니다.")
                    if (timeSystem != null && timeSystem != candidate) throw PpkFormatException(".pos 시간계 헤더가 서로 충돌합니다.")
                    timeSystem = candidate
                }
                return@forEach
            }
            val system = timeSystem ?: throw PpkFormatException(".pos 열 헤더가 데이터보다 먼저 필요합니다.")
            val values = line.split(Regex("\\s+"))
            if (values.size < 7) throw PpkFormatException(".pos 데이터 열이 부족합니다.")
            val local = runCatching { LocalDateTime.parse("${values[0]} ${values[1]}", dateTimeFormatter) }
                .getOrElse { throw PpkFormatException(".pos 날짜 형식이 잘못되었습니다.") }
            val instant = toUtc(local, system)
            val latitude = finite(values[2], "latitude")
            val longitude = finite(values[3], "longitude")
            val height = finite(values[4], "height")
            val quality = values[5].toIntOrNull()?.takeIf { it in 1..6 }
                ?: throw PpkFormatException("Q 값은 RTKLIB 해 품질 1..6이어야 합니다.")
            val satellites = values[6].toIntOrNull()?.takeIf { it in 0..255 }
                ?: throw PpkFormatException("ns 값이 0..255 범위 정수가 아닙니다.")
            val epoch = PpkEpoch(
                utcEpochMs = instant.toEpochMilli(),
                point = runCatching { GeoPoint(latitude, longitude) }
                    .getOrElse { throw PpkFormatException(".pos 위도·경도 범위가 잘못되었습니다.") },
                heightM = height,
                quality = quality,
                satelliteCount = satellites,
                stdNorthM = values.getOrNull(7)?.let { finite(it, "sdn") },
                stdEastM = values.getOrNull(8)?.let { finite(it, "sde") },
                ratio = values.getOrNull(14)?.let { finite(it, "ratio") },
            )
            if (epochs.lastOrNull()?.utcEpochMs?.let { epoch.utcEpochMs <= it } == true) {
                throw PpkFormatException(".pos 시각이 중복되거나 역순입니다.")
            }
            epochs += epoch
        }
        val system = timeSystem ?: throw PpkFormatException("RTKLIB .pos가 LLH 위도·경도 형식이 아닙니다.")
        if (epochs.isEmpty()) throw PpkFormatException(".pos 해가 없습니다.")
        return ParsedPpk(system, epochs, epochs.count { it.quality == 1 })
    }

    internal fun toUtc(local: LocalDateTime, timeSystem: String): java.time.Instant {
        if (local < supportedStart || local >= supportedEndExclusive) {
            throw PpkFormatException("$timeSystem 시각이 검증된 윤초 표 적용 범위를 벗어났습니다.")
        }
        if (timeSystem == "UTC") return local.toInstant(ZoneOffset.UTC)
        val rule = leapRules.lastOrNull { local >= it.utcEffective.plusSeconds(it.gpstMinusUtcSeconds) }
            ?: throw PpkFormatException("GPST 윤초 표를 적용할 수 없습니다.")
        return local.toInstant(ZoneOffset.UTC).minusSeconds(rule.gpstMinusUtcSeconds)
    }

    private fun finite(raw: String, name: String): Double {
        val value = raw.toDoubleOrNull() ?: throw PpkFormatException("$name 값이 숫자가 아닙니다.")
        if (!value.isFinite()) throw PpkFormatException("$name 값이 유한하지 않습니다.")
        return value
    }

    private data class LeapRule(val utcEffective: LocalDateTime, val gpstMinusUtcSeconds: Long)

    private val leapRules = listOf(
        LeapRule(LocalDateTime.of(1980, 1, 6, 0, 0), 0),
        LeapRule(LocalDateTime.of(1981, 7, 1, 0, 0), 1),
        LeapRule(LocalDateTime.of(1982, 7, 1, 0, 0), 2),
        LeapRule(LocalDateTime.of(1983, 7, 1, 0, 0), 3),
        LeapRule(LocalDateTime.of(1985, 7, 1, 0, 0), 4),
        LeapRule(LocalDateTime.of(1988, 1, 1, 0, 0), 5),
        LeapRule(LocalDateTime.of(1990, 1, 1, 0, 0), 6),
        LeapRule(LocalDateTime.of(1991, 1, 1, 0, 0), 7),
        LeapRule(LocalDateTime.of(1992, 7, 1, 0, 0), 8),
        LeapRule(LocalDateTime.of(1993, 7, 1, 0, 0), 9),
        LeapRule(LocalDateTime.of(1994, 7, 1, 0, 0), 10),
        LeapRule(LocalDateTime.of(1996, 1, 1, 0, 0), 11),
        LeapRule(LocalDateTime.of(1997, 7, 1, 0, 0), 12),
        LeapRule(LocalDateTime.of(1999, 1, 1, 0, 0), 13),
        LeapRule(LocalDateTime.of(2006, 1, 1, 0, 0), 14),
        LeapRule(LocalDateTime.of(2009, 1, 1, 0, 0), 15),
        LeapRule(LocalDateTime.of(2012, 7, 1, 0, 0), 16),
        LeapRule(LocalDateTime.of(2015, 7, 1, 0, 0), 17),
        LeapRule(LocalDateTime.of(2017, 1, 1, 0, 0), 18),
    )
}

object PositionEvaluator {
    const val POLICY_ID = "walksafe.fix-only.causal-grid"
    const val POLICY_VERSION = "1.1.0"
    const val GRID_INTERVAL_MS = 1_000L
    const val MAX_TRUTH_INTERPOLATION_GAP_MS = 1_500L
    const val MAX_ESTIMATE_AGE_MS = 2_000L
    const val MIN_TRUTH_SAMPLES = 30
    const val MIN_EVALUATION_WINDOW_MS = 30_000L
    const val MIN_TRUTH_COVERAGE = 0.90
    const val MIN_CHANNEL_SAMPLES = 20
    private const val EARTH_RADIUS_M = 6_371_008.8

    fun evaluate(trace: TraceSession, ppk: ParsedPpk): EvaluationOutcome {
        if (trace.samples.size < 2) return insufficient("INSUFFICIENT_REFERENCE", 0, 0)
        if (trace.endUtcEpochMs - trace.startUtcEpochMs < MIN_EVALUATION_WINDOW_MS) {
            return insufficient("EVALUATION_WINDOW_TOO_SHORT", 0, 0)
        }
        if (trace.samples.zipWithNext().any { (first, second) -> second.utcEpochMs < first.utcEpochMs }) {
            return insufficient("TIME_ALIGNMENT_FAILED", 0, 0)
        }
        if (ppk.timeSystem !in setOf("UTC", "GPST") || ppk.epochs.any { it.quality !in 1..6 }) {
            return insufficient("PPK_QUALITY_OR_TIME_SYSTEM_INVALID", 0, 0)
        }
        if (ppk.fixedCount != ppk.epochs.count { it.quality == 1 }) {
            return insufficient("PPK_FIXED_COUNT_MISMATCH", 0, 0)
        }
        val gridStart = ceil(trace.startUtcEpochMs / GRID_INTERVAL_MS.toDouble()).toLong() * GRID_INTERVAL_MS
        val gridEnd = trace.endUtcEpochMs / GRID_INTERVAL_MS * GRID_INTERVAL_MS
        val grid = if (gridEnd < gridStart) emptyList() else generateSequence(gridStart) { previous ->
            (previous + GRID_INTERVAL_MS).takeIf { it <= gridEnd }
        }.toList()
        if (grid.size < MIN_TRUTH_SAMPLES) return insufficient("EVALUATION_GRID_TOO_SHORT", grid.size, 0)

        val fixedGrid = LinkedHashMap<Long, GeoPoint>()
        grid.forEach { time -> interpolateFixed(ppk.epochs, time)?.let { fixedGrid[time] = it } }
        val eligible = fixedGrid.size
        val coverage = eligible.toDouble() / grid.size
        if (eligible < MIN_TRUTH_SAMPLES || coverage < MIN_TRUTH_COVERAGE) {
            val reason = when {
                ppk.fixedCount == 0 -> "PPK_NO_FIXED_SOLUTION"
                eligible < MIN_TRUTH_SAMPLES -> "FIX_GRID_TOO_SPARSE"
                else -> "FIX_COVERAGE_TOO_LOW"
            }
            return insufficient(reason, grid.size, eligible)
        }
        val metrics = PositionChannel.entries.map { channel -> metricsFor(channel, trace.samples, fixedGrid) }
        return EvaluationOutcome(EvaluationStatus.EVALUATED, emptyList(), grid.size, eligible, metrics)
    }

    private fun insufficient(reason: String, grid: Int, truth: Int) = EvaluationOutcome(
        EvaluationStatus.TRUTH_INSUFFICIENT,
        listOf(reason),
        grid,
        truth,
        emptyList(),
    )

    private fun interpolateFixed(epochs: List<PpkEpoch>, timeMs: Long): GeoPoint? {
        val index = epochs.binarySearchBy(timeMs) { it.utcEpochMs }
        if (index >= 0) return epochs[index].takeIf { it.quality == 1 }?.point
        val insertion = -index - 1
        if (insertion == 0 || insertion >= epochs.size) return null
        val before = epochs[insertion - 1]
        val after = epochs[insertion]
        if (before.quality != 1 || after.quality != 1) return null
        val gap = after.utcEpochMs - before.utcEpochMs
        if (gap <= 0L || gap > MAX_TRUTH_INTERPOLATION_GAP_MS) return null
        val ratio = (timeMs - before.utcEpochMs).toDouble() / gap
        val longitudeDelta = normalizedLongitudeDelta(after.point.longitudeDeg - before.point.longitudeDeg)
        return GeoPoint(
            before.point.latitudeDeg + (after.point.latitudeDeg - before.point.latitudeDeg) * ratio,
            normalizedLongitude(before.point.longitudeDeg + longitudeDelta * ratio),
        )
    }

    private fun metricsFor(channel: PositionChannel, samples: List<TraceSample>, truth: Map<Long, GeoPoint>): ChannelMetrics {
        val errors = mutableListOf<Double>()
        var cursor = -1
        var latestChannelSample: TraceSample? = null
        for ((time, truthPoint) in truth) {
            while (cursor + 1 < samples.size && samples[cursor + 1].utcEpochMs <= time) {
                cursor += 1
                val candidate = samples[cursor]
                if (candidate.point(channel) != null) {
                    latestChannelSample = candidate
                } else if (channel == PositionChannel.MATCHED && candidate.routeMatchEvaluated) {
                    // Explicit re-evaluation without a match invalidates even a replayed sensor state.
                    latestChannelSample = null
                }
            }
            val sample = latestChannelSample ?: continue
            if (time - sample.utcEpochMs > MAX_ESTIMATE_AGE_MS) continue
            val estimate = sample.point(channel) ?: continue
            errors += haversineMeters(estimate, truthPoint)
        }
        val sorted = errors.sorted()
        val enough = sorted.size >= MIN_CHANNEL_SAMPLES
        val eligible = truth.size
        val withinTwo = sorted.count { it <= 2.0 }
        return ChannelMetrics(
            channel = channel,
            eligibleTruthCount = eligible,
            availableCount = sorted.size,
            p50ErrorM = sorted.takeIf { enough }?.let { percentile(it, 0.50) },
            p95ErrorM = sorted.takeIf { enough }?.let { percentile(it, 0.95) },
            rmseErrorM = sorted.takeIf { enough }?.let { values -> sqrt(values.sumOf { it * it } / values.size) },
            maxErrorM = sorted.takeIf { enough }?.last(),
            withinTwoMetersCount = withinTwo,
            twoMeterCoverage = if (eligible == 0) 0.0 else withinTwo.toDouble() / eligible,
            availability = if (eligible == 0) 0.0 else sorted.size.toDouble() / eligible,
        )
    }

    fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val latitude1 = Math.toRadians(a.latitudeDeg)
        val latitude2 = Math.toRadians(b.latitudeDeg)
        val latitudeDelta = latitude2 - latitude1
        val longitudeDelta = Math.toRadians(normalizedLongitudeDelta(b.longitudeDeg - a.longitudeDeg))
        val value = sin(latitudeDelta / 2).pow(2) + cos(latitude1) * cos(latitude2) * sin(longitudeDelta / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(value.coerceIn(0.0, 1.0)))
    }

    private fun percentile(values: List<Double>, percentile: Double): Double {
        val index = (ceil(percentile * values.size).toInt() - 1).coerceIn(0, values.lastIndex)
        return values[index]
    }

    private fun normalizedLongitudeDelta(value: Double): Double = ((value + 540.0) % 360.0) - 180.0
    private fun normalizedLongitude(value: Double): Double = ((value + 540.0) % 360.0) - 180.0
}

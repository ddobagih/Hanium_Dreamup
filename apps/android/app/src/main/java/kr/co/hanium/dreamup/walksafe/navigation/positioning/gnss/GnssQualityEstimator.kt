package kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss

import java.util.ArrayDeque
import kotlin.math.sqrt

internal data class GnssQualityEstimatorConfig(
    val windowDurationNanos: Long = 15_000_000_000L,
    val staleAfterNanos: Long = 5_000_000_000L,
    val maxEpochCount: Int = 30,
    val minEpochCountForRisk: Int = 3,
    val minSignalCountForRisk: Int = 4,
    val healthyP25Cn0DbHz: Double = 28.0,
    val cn0RiskSpanDbHz: Double = 15.0,
    val volatilityFullRiskDbHz: Double = 8.0,
    val maxMeasurementNoiseMultiplier: Double = 4.0,
) {
    init {
        require(windowDurationNanos > 0L)
        require(staleAfterNanos > 0L)
        require(maxEpochCount > 0)
        require(minEpochCountForRisk > 0)
        require(minSignalCountForRisk > 0)
        require(healthyP25Cn0DbHz.isFinite())
        require(cn0RiskSpanDbHz.isFinite() && cn0RiskSpanDbHz > 0.0)
        require(volatilityFullRiskDbHz.isFinite() && volatilityFullRiskDbHz > 0.0)
        require(maxMeasurementNoiseMultiplier.isFinite() && maxMeasurementNoiseMultiplier >= 1.0)
    }
}

/**
 * Reduces GNSS signal observations into a bounded quality window. C/N0 is only a
 * signal-environment hint, so the result can increase measurement noise but never rejects a fix.
 * Call this class from one serial execution context.
 */
internal class GnssQualityEstimator(
    private val config: GnssQualityEstimatorConfig = GnssQualityEstimatorConfig(),
) {
    private val epochs = ArrayDeque<Epoch>()
    private var clockHighWatermarkNanos: Long? = null
    private var latestEpochAtNanos: Long? = null
    private var stale = false

    fun addEpoch(
        elapsedRealtimeNanos: Long,
        signals: List<GnssSatelliteSignal>,
    ): GnssQualitySnapshot {
        require(elapsedRealtimeNanos >= 0L)
        val highWatermark = clockHighWatermarkNanos
        if (highWatermark != null && elapsedRealtimeNanos <= highWatermark) {
            return buildSnapshot(highWatermark)
        }

        val previousEpochAtNanos = latestEpochAtNanos
        if (previousEpochAtNanos != null && elapsedRealtimeNanos - previousEpochAtNanos > config.staleAfterNanos) {
            epochs.clear()
        }

        clockHighWatermarkNanos = elapsedRealtimeNanos
        latestEpochAtNanos = elapsedRealtimeNanos
        stale = false
        epochs.addLast(
            Epoch(
                elapsedRealtimeNanos = elapsedRealtimeNanos,
                cn0BySignal = finiteCn0BySignal(signals),
                frequencySummary = summarizeGnssFrequencies(signals),
            ),
        )
        trimWindow(elapsedRealtimeNanos)
        return buildSnapshot(elapsedRealtimeNanos)
    }

    fun snapshot(elapsedRealtimeNanos: Long): GnssQualitySnapshot {
        require(elapsedRealtimeNanos >= 0L)
        val highWatermark = clockHighWatermarkNanos
        if (highWatermark != null && elapsedRealtimeNanos < highWatermark) {
            return buildSnapshot(highWatermark)
        }

        clockHighWatermarkNanos = elapsedRealtimeNanos
        val lastEpoch = latestEpochAtNanos
        if (lastEpoch != null && elapsedRealtimeNanos - lastEpoch > config.staleAfterNanos) {
            epochs.clear()
            stale = true
        } else {
            trimWindow(elapsedRealtimeNanos)
        }
        return buildSnapshot(elapsedRealtimeNanos)
    }

    fun clear() {
        epochs.clear()
        clockHighWatermarkNanos = null
        latestEpochAtNanos = null
        stale = false
    }

    private fun finiteCn0BySignal(signals: List<GnssSatelliteSignal>): Map<SignalIdentity, Double> {
        val result = linkedMapOf<SignalIdentity, Double>()
        signals.forEach { signal ->
            val cn0 = signal.cn0DbHz ?: return@forEach
            if (!cn0.isFinite()) return@forEach
            val identity = SignalIdentity(
                constellation = signal.constellation,
                svid = signal.svid,
                band = GnssFrequencyClassifier.classify(signal.carrierFrequencyHz),
            )
            val previous = result[identity]
            if (previous == null || cn0 > previous) result[identity] = cn0
        }
        return result
    }

    private fun trimWindow(nowNanos: Long) {
        val cutoff = (nowNanos - config.windowDurationNanos).coerceAtLeast(0L)
        while (epochs.isNotEmpty() && epochs.first.elapsedRealtimeNanos < cutoff) {
            epochs.removeFirst()
        }
        while (epochs.size > config.maxEpochCount) {
            epochs.removeFirst()
        }
    }

    private fun buildSnapshot(evaluatedAtNanos: Long): GnssQualitySnapshot {
        val latest = epochs.peekLast()
        if (latest == null) {
            return GnssQualitySnapshot(
                evaluatedAtNanos = evaluatedAtNanos,
                latestEpochAtNanos = null,
                epochCount = 0,
                finiteSignalCount = 0,
                medianCn0DbHz = null,
                p25Cn0DbHz = null,
                dropoutRatio = null,
                volatilityDbHz = null,
                frequencySummary = unknownGnssFrequencySummary(),
                signalEnvironmentRisk = SignalEnvironmentRisk.UNKNOWN,
                signalEnvironmentRiskScore = null,
                measurementNoiseMultiplier = 1.0,
                isStale = stale,
            )
        }

        val allCn0 = epochs.flatMap { it.cn0BySignal.values }.sorted()
        val latestCn0 = latest.cn0BySignal.values.sorted()
        val medianCn0 = percentile(allCn0, 0.5)
        val p25Cn0 = percentile(allCn0, 0.25)
        val maximumSignalCount = epochs.maxOf { it.cn0BySignal.size }
        val dropoutRatio = if (maximumSignalCount == 0) {
            null
        } else {
            ((maximumSignalCount - latest.cn0BySignal.size).toDouble() / maximumSignalCount).coerceIn(0.0, 1.0)
        }
        val volatility = calculateVolatility()

        val canAssessRisk =
            epochs.size >= config.minEpochCountForRisk &&
                maximumSignalCount >= config.minSignalCountForRisk &&
                latestCn0.isNotEmpty()
        val riskScore = if (canAssessRisk) {
            val latestP25 = requireNotNull(percentile(latestCn0, 0.25))
            val weakSignalRisk =
                ((config.healthyP25Cn0DbHz - latestP25) / config.cn0RiskSpanDbHz).coerceIn(0.0, 1.0)
            val dropoutRisk = dropoutRatio ?: 0.0
            val volatilityRisk = ((volatility ?: 0.0) / config.volatilityFullRiskDbHz).coerceIn(0.0, 1.0)
            val scarcityRisk =
                ((config.minSignalCountForRisk - latest.cn0BySignal.size).toDouble() /
                    config.minSignalCountForRisk).coerceIn(0.0, 1.0)
            (0.4 * weakSignalRisk +
                0.3 * dropoutRisk +
                0.15 * volatilityRisk +
                0.15 * scarcityRisk).coerceIn(0.0, 1.0)
        } else {
            null
        }
        val risk = when {
            riskScore == null -> SignalEnvironmentRisk.UNKNOWN
            riskScore < 1.0 / 3.0 -> SignalEnvironmentRisk.LOW
            riskScore < 2.0 / 3.0 -> SignalEnvironmentRisk.MEDIUM
            else -> SignalEnvironmentRisk.HIGH
        }
        val noiseMultiplier = if (riskScore == null) {
            1.0
        } else {
            (1.0 + riskScore * (config.maxMeasurementNoiseMultiplier - 1.0))
                .coerceIn(1.0, config.maxMeasurementNoiseMultiplier)
        }

        return GnssQualitySnapshot(
            evaluatedAtNanos = evaluatedAtNanos,
            latestEpochAtNanos = latestEpochAtNanos,
            epochCount = epochs.size,
            finiteSignalCount = latest.cn0BySignal.size,
            medianCn0DbHz = medianCn0,
            p25Cn0DbHz = p25Cn0,
            dropoutRatio = dropoutRatio,
            volatilityDbHz = volatility,
            frequencySummary = latest.frequencySummary,
            signalEnvironmentRisk = risk,
            signalEnvironmentRiskScore = riskScore,
            measurementNoiseMultiplier = noiseMultiplier,
            isStale = stale,
        )
    }

    private fun calculateVolatility(): Double? {
        val previousBySignal = mutableMapOf<SignalIdentity, Double>()
        val squaredChanges = mutableListOf<Double>()
        epochs.forEach { epoch ->
            epoch.cn0BySignal.forEach { (identity, cn0) ->
                previousBySignal[identity]?.let { previous ->
                    val change = cn0 - previous
                    squaredChanges += change * change
                }
                previousBySignal[identity] = cn0
            }
        }
        return if (squaredChanges.isEmpty()) null else sqrt(squaredChanges.average())
    }

    private fun percentile(sortedValues: List<Double>, percentile: Double): Double? {
        if (sortedValues.isEmpty()) return null
        if (sortedValues.size == 1) return sortedValues.first()
        val rank = (sortedValues.lastIndex * percentile).coerceIn(0.0, sortedValues.lastIndex.toDouble())
        val lowerIndex = rank.toInt()
        val upperIndex = if (rank == lowerIndex.toDouble()) lowerIndex else lowerIndex + 1
        val fraction = rank - lowerIndex
        return sortedValues[lowerIndex] + (sortedValues[upperIndex] - sortedValues[lowerIndex]) * fraction
    }

    private data class Epoch(
        val elapsedRealtimeNanos: Long,
        val cn0BySignal: Map<SignalIdentity, Double>,
        val frequencySummary: GnssFrequencySummary,
    )

    private data class SignalIdentity(
        val constellation: Int,
        val svid: Int,
        val band: GnssFrequencyBand,
    )
}

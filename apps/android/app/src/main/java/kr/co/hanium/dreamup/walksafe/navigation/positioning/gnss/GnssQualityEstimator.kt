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
    private var latestEpochAtNanos: Long? = null

    /** First C/N0-bearing epoch since [clear]; accepted epochs are strictly increasing, so it never moves. */
    private var firstSignalAtNanos: Long? = null

    fun addEpoch(
        elapsedRealtimeNanos: Long,
        signals: List<GnssSatelliteSignal>,
        timeSource: GnssSignalTimeSource? = null,
        timestampUncertaintyNanos: Double? = null,
        receivedAtElapsedRealtimeNanos: Long? = null,
    ): GnssQualitySnapshot {
        require(elapsedRealtimeNanos >= 0L)
        require(receivedAtElapsedRealtimeNanos == null || receivedAtElapsedRealtimeNanos >= elapsedRealtimeNanos)
        val highWatermark = latestEpochAtNanos
        if (highWatermark != null && elapsedRealtimeNanos <= highWatermark) {
            return buildSnapshot(highWatermark)
        }

        val previousEpochAtNanos = latestEpochAtNanos
        if (previousEpochAtNanos != null && elapsedRealtimeNanos - previousEpochAtNanos > config.staleAfterNanos) {
            epochs.clear()
        }

        latestEpochAtNanos = elapsedRealtimeNanos
        val cn0BySignal = finiteCn0BySignal(signals)
        if (firstSignalAtNanos == null && cn0BySignal.isNotEmpty()) firstSignalAtNanos = elapsedRealtimeNanos
        epochs.addLast(
            Epoch(
                elapsedRealtimeNanos = elapsedRealtimeNanos,
                cn0BySignal = cn0BySignal,
                frequencySummary = summarizeGnssFrequencies(signals),
                timeSource = timeSource,
                timestampUncertaintyNanos = timestampUncertaintyNanos,
                receivedAtElapsedRealtimeNanos = receivedAtElapsedRealtimeNanos,
            ),
        )
        trimWindow(elapsedRealtimeNanos)
        return buildSnapshot(elapsedRealtimeNanos)
    }

    fun snapshot(elapsedRealtimeNanos: Long): GnssQualitySnapshot {
        require(elapsedRealtimeNanos >= 0L)
        // A query neither consumes source order nor applies future signal quality to an old fix.
        return buildSnapshot(elapsedRealtimeNanos)
    }

    fun clear() {
        epochs.clear()
        latestEpochAtNanos = null
        firstSignalAtNanos = null
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
        val atOrBefore = epochs.filter { it.elapsedRealtimeNanos <= evaluatedAtNanos }
        val lastAt = atOrBefore.lastOrNull()?.elapsedRealtimeNanos
        val stale = lastAt != null && evaluatedAtNanos - lastAt > config.staleAfterNanos
        val cutoff = (evaluatedAtNanos - config.windowDurationNanos).coerceAtLeast(0L)
        val epochs = if (stale) emptyList() else atOrBefore.filter { it.elapsedRealtimeNanos >= cutoff }
        val latest = epochs.lastOrNull()
        // S09-6: a query never borrows a later first signal, so a backward query stays UNSUPPORTED.
        val signalObservedByThen = firstSignalAtNanos?.let { it <= evaluatedAtNanos } == true
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
                signalAvailability = if (signalObservedByThen) {
                    GnssSignalAvailability.LOST_EXPIRED
                } else {
                    GnssSignalAvailability.UNSUPPORTED
                },
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
        val volatility = calculateVolatility(epochs)

        var lossHeld = false
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
        } else if (latestCn0.isEmpty()) {
            // An empty epoch must not erase assessed risk immediately or extend its lifetime.
            val lastSignalAt = epochs.lastOrNull { it.cn0BySignal.isNotEmpty() }?.elapsedRealtimeNanos
            if (lastSignalAt != null && evaluatedAtNanos - lastSignalAt <= config.staleAfterNanos) {
                // pos-F5: "holding" only when there is an assessed risk to hold.
                buildSnapshot(lastSignalAt).signalEnvironmentRiskScore.also { held -> lossHeld = held != null }
            } else {
                null
            }
        } else {
            null
        }
        val availability = when {
            !signalObservedByThen -> GnssSignalAvailability.UNSUPPORTED
            latestCn0.isNotEmpty() -> GnssSignalAvailability.OBSERVING
            lossHeld -> GnssSignalAvailability.LOST_HOLDING
            else -> GnssSignalAvailability.LOST_EXPIRED
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
            latestEpochAtNanos = latest.elapsedRealtimeNanos,
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
            latestEpochTimeSource = latest.timeSource,
            latestEpochTimestampUncertaintyNanos = latest.timestampUncertaintyNanos,
            signalAvailability = availability,
            latestEpochReceivedAtNanos = latest.receivedAtElapsedRealtimeNanos,
        )
    }

    private fun calculateVolatility(epochs: List<Epoch>): Double? {
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
        val timeSource: GnssSignalTimeSource?,
        val timestampUncertaintyNanos: Double?,
        val receivedAtElapsedRealtimeNanos: Long?,
    )

    private data class SignalIdentity(
        val constellation: Int,
        val svid: Int,
        val band: GnssFrequencyBand,
    )
}

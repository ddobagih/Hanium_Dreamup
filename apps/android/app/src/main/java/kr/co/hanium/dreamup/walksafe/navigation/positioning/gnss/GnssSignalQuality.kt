package kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss

import kotlin.math.abs

internal enum class GnssFrequencyBand {
    L1_FAMILY,
    L5_FAMILY,
    OTHER,
    UNKNOWN,
}

internal enum class DualFrequencyObservationState {
    UNKNOWN,
    SINGLE_FREQUENCY_OBSERVED,
    DUAL_FREQUENCY_OBSERVED,
}

internal enum class SignalEnvironmentRisk {
    UNKNOWN,
    LOW,
    MEDIUM,
    HIGH,
}

internal data class GnssSatelliteSignal(
    val constellation: Int,
    val svid: Int,
    val carrierFrequencyHz: Double?,
    val cn0DbHz: Double?,
    val usedInFix: Boolean = false,
    val usedInFixKnown: Boolean = true,
)

internal data class GnssFrequencySummary(
    val l1SignalCount: Int,
    val l5SignalCount: Int,
    val dualFrequencySatelliteCount: Int,
    val trackedSatelliteCount: Int,
    val usedInFixSatelliteCount: Int,
    val state: DualFrequencyObservationState,
    val usedInFixKnown: Boolean = false,
)

internal data class GnssQualitySnapshot(
    val evaluatedAtNanos: Long,
    val latestEpochAtNanos: Long?,
    val epochCount: Int,
    val finiteSignalCount: Int,
    val medianCn0DbHz: Double?,
    val p25Cn0DbHz: Double?,
    val dropoutRatio: Double?,
    val volatilityDbHz: Double?,
    val frequencySummary: GnssFrequencySummary,
    val signalEnvironmentRisk: SignalEnvironmentRisk,
    val signalEnvironmentRiskScore: Double?,
    val measurementNoiseMultiplier: Double,
    val isStale: Boolean,
)

internal object GnssFrequencyClassifier {
    const val L1_CENTER_HZ = 1_575_420_000.0
    const val L5_CENTER_HZ = 1_176_450_000.0
    const val DEFAULT_TOLERANCE_HZ = 2_000_000.0

    fun classify(
        carrierFrequencyHz: Double?,
        toleranceHz: Double = DEFAULT_TOLERANCE_HZ,
    ): GnssFrequencyBand {
        require(toleranceHz.isFinite() && toleranceHz >= 0.0)
        if (carrierFrequencyHz == null || !carrierFrequencyHz.isFinite() || carrierFrequencyHz <= 0.0) {
            return GnssFrequencyBand.UNKNOWN
        }
        return when {
            abs(carrierFrequencyHz - L1_CENTER_HZ) <= toleranceHz -> GnssFrequencyBand.L1_FAMILY
            abs(carrierFrequencyHz - L5_CENTER_HZ) <= toleranceHz -> GnssFrequencyBand.L5_FAMILY
            else -> GnssFrequencyBand.OTHER
        }
    }
}

internal fun summarizeGnssFrequencies(
    signals: List<GnssSatelliteSignal>,
    toleranceHz: Double = GnssFrequencyClassifier.DEFAULT_TOLERANCE_HZ,
): GnssFrequencySummary {
    val l1Satellites = linkedSetOf<SatelliteIdentity>()
    val l5Satellites = linkedSetOf<SatelliteIdentity>()
    val trackedSatellites = linkedSetOf<SatelliteIdentity>()
    val usedInFixSatellites = linkedSetOf<SatelliteIdentity>()
    var usedInFixKnown = false

    signals.forEach { signal ->
        val identity = SatelliteIdentity(signal.constellation, signal.svid)
        trackedSatellites += identity
        if (signal.usedInFixKnown) {
            usedInFixKnown = true
            if (signal.usedInFix) usedInFixSatellites += identity
        }
        when (GnssFrequencyClassifier.classify(signal.carrierFrequencyHz, toleranceHz)) {
            GnssFrequencyBand.L1_FAMILY -> l1Satellites += identity
            GnssFrequencyBand.L5_FAMILY -> l5Satellites += identity
            GnssFrequencyBand.OTHER,
            GnssFrequencyBand.UNKNOWN,
            -> Unit
        }
    }

    val dualFrequencyCount = l1Satellites.count { it in l5Satellites }
    val state = when {
        dualFrequencyCount > 0 -> DualFrequencyObservationState.DUAL_FREQUENCY_OBSERVED
        l1Satellites.isNotEmpty() || l5Satellites.isNotEmpty() ->
            DualFrequencyObservationState.SINGLE_FREQUENCY_OBSERVED
        else -> DualFrequencyObservationState.UNKNOWN
    }
    return GnssFrequencySummary(
        l1SignalCount = l1Satellites.size,
        l5SignalCount = l5Satellites.size,
        dualFrequencySatelliteCount = dualFrequencyCount,
        trackedSatelliteCount = trackedSatellites.size,
        usedInFixSatelliteCount = usedInFixSatellites.size,
        state = state,
        usedInFixKnown = usedInFixKnown,
    )
}

private data class SatelliteIdentity(
    val constellation: Int,
    val svid: Int,
)

internal fun unknownGnssFrequencySummary(): GnssFrequencySummary = GnssFrequencySummary(
    l1SignalCount = 0,
    l5SignalCount = 0,
    dualFrequencySatelliteCount = 0,
    trackedSatelliteCount = 0,
    usedInFixSatelliteCount = 0,
    state = DualFrequencyObservationState.UNKNOWN,
    usedInFixKnown = false,
)

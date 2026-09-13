package kr.co.hanium.dreamup.walksafe.fieldtest

import kotlin.math.floor

/** The nullable values are absent receiver observations, never inferred signal attributes. */
data class OutdoorRawGnssClock(
    val timeNanos: Long,
    val leapSecond: Int?,
    val timeUncertaintyNanos: Double?,
    val fullBiasNanos: Long?,
    val biasNanos: Double?,
    val biasUncertaintyNanos: Double?,
    val hardwareClockDiscontinuityCount: Int,
    val elapsedRealtimeNanos: Long?,
)

data class OutdoorRawGnssMeasurement(
    val svid: Int,
    val timeOffsetNanos: Double,
    val state: Int,
    val receivedSvTimeNanos: Long,
    val receivedSvTimeUncertaintyNanos: Long,
    val cn0DbHz: Double,
    val pseudorangeRateMetersPerSecond: Double,
    val pseudorangeRateUncertaintyMetersPerSecond: Double,
    val accumulatedDeltaRangeState: Int,
    val accumulatedDeltaRangeMeters: Double,
    val accumulatedDeltaRangeUncertaintyMeters: Double,
    val carrierFrequencyHz: Float?,
    val multipathIndicator: Int,
    val constellationType: Int,
    val codeType: String?,
)

object OutdoorRawGnssFormatter {
    private const val GPS_EPOCH_UNIX_MILLIS = 315_964_800_000L
    private const val NANOS_PER_MILLI = 1_000_000L

    val fields = listOf(
        "utcTimeMillis", "TimeNanos", "LeapSecond", "TimeUncertaintyNanos", "FullBiasNanos",
        "BiasNanos", "BiasUncertaintyNanos", "HardwareClockDiscontinuityCount", "Svid",
        "TimeOffsetNanos", "State", "ReceivedSvTimeNanos", "ReceivedSvTimeUncertaintyNanos",
        "Cn0DbHz", "PseudorangeRateMetersPerSecond", "PseudorangeRateUncertaintyMetersPerSecond",
        "AccumulatedDeltaRangeState", "AccumulatedDeltaRangeMeters",
        "AccumulatedDeltaRangeUncertaintyMeters", "CarrierFrequencyHz", "MultipathIndicator",
        "ConstellationType", "CodeType", "ChipsetElapsedRealtimeNanos", "UtcTimeSource",
        "CallbackUtcTimeMillis", "CallbackElapsedRealtimeNanos",
    )

    val rawHeader: String = "# Raw," + fields.joinToString(",")

    fun header(model: String, manufacturer: String): String = buildString {
        append("# Version: WalkSafe Outdoor Raw 1.0 Manufacturer: ")
        append(csvText(manufacturer)).append(" Model: ").append(csvText(model)).append('\n')
        append("# Local diagnostic observations; not a ground-truth trajectory.\n")
        append("# Missing receiver fields remain empty. No assumed leap second or signal code.\n")
        append("# UtcTimeSource=GNSS_CLOCK: receiver clock and reported leap second.\n")
        append("# DEVICE_UTC_PROJECTED/DEVICE_UTC_CALLBACK: observed device wall clock; UTC agreement is unverified.\n")
        append(rawHeader).append('\n')
    }

    data class UtcStamp(val millis: Long, val source: String)

    fun utcStamp(
        clock: OutdoorRawGnssClock,
        timeOffsetNanos: Double,
        callbackUtcTimeMillis: Long,
        callbackElapsedRealtimeNanos: Long,
    ): UtcStamp {
        // GnssClock's documented UTC equation; retain integer precision before splitting ms.
        if (clock.fullBiasNanos != null && clock.leapSecond != null &&
            clock.leapSecond in 0..64 && timeOffsetNanos.isFinite() &&
            (clock.biasNanos == null || clock.biasNanos.isFinite())) {
            val gpsNanos = runCatching { Math.subtractExact(clock.timeNanos, clock.fullBiasNanos) }.getOrNull()
            if (gpsNanos != null) {
                val wholeMs = Math.floorDiv(gpsNanos, NANOS_PER_MILLI)
                val remainder = Math.floorMod(gpsNanos, NANOS_PER_MILLI)
                val fractionalMs = floor((remainder + timeOffsetNanos - (clock.biasNanos ?: 0.0)) / NANOS_PER_MILLI).toLong()
                return UtcStamp(GPS_EPOCH_UNIX_MILLIS + wholeMs + fractionalMs - clock.leapSecond * 1_000L, "GNSS_CLOCK")
            }
        }
        // Preserve a measured device timestamp when the receiver cannot provide UTC. Never
        // manufacture a leap-second value to force the downstream converter's UTC check to pass.
        val measuredElapsed = clock.elapsedRealtimeNanos
        if (measuredElapsed != null && measuredElapsed in 0..callbackElapsedRealtimeNanos) {
            return UtcStamp(
                callbackUtcTimeMillis - (callbackElapsedRealtimeNanos - measuredElapsed) / NANOS_PER_MILLI,
                "DEVICE_UTC_PROJECTED",
            )
        }
        return UtcStamp(callbackUtcTimeMillis, "DEVICE_UTC_CALLBACK")
    }

    fun rawRow(
        clock: OutdoorRawGnssClock,
        measurement: OutdoorRawGnssMeasurement,
        callbackUtcTimeMillis: Long,
        callbackElapsedRealtimeNanos: Long,
    ): String {
        val utc = utcStamp(clock, measurement.timeOffsetNanos, callbackUtcTimeMillis, callbackElapsedRealtimeNanos)
        return listOf(
            "Raw", utc.millis, clock.timeNanos, clock.leapSecond, clock.timeUncertaintyNanos,
            clock.fullBiasNanos, clock.biasNanos, clock.biasUncertaintyNanos,
            clock.hardwareClockDiscontinuityCount, measurement.svid, measurement.timeOffsetNanos,
            measurement.state, measurement.receivedSvTimeNanos, measurement.receivedSvTimeUncertaintyNanos,
            measurement.cn0DbHz, measurement.pseudorangeRateMetersPerSecond,
            measurement.pseudorangeRateUncertaintyMetersPerSecond, measurement.accumulatedDeltaRangeState,
            measurement.accumulatedDeltaRangeMeters, measurement.accumulatedDeltaRangeUncertaintyMeters,
            measurement.carrierFrequencyHz, measurement.multipathIndicator, measurement.constellationType,
            measurement.codeType, clock.elapsedRealtimeNanos, utc.source,
            callbackUtcTimeMillis, callbackElapsedRealtimeNanos,
        ).joinToString(",") { value -> value?.toString()?.let(::csvText).orEmpty() } + "\n"
    }

    private fun csvText(value: String): String = value.replace(',', ' ').replace('\r', ' ').replace('\n', ' ')
}

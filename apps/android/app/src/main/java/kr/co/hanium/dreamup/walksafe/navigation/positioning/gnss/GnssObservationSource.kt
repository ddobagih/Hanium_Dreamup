package kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss

internal enum class GnssSignalTimeSource {
    MEASUREMENT_ELAPSED_REALTIME,
    CALLBACK_RECEIVED,
}

internal data class GnssSignalEpoch(
    val elapsedRealtimeNanos: Long,
    val signals: List<GnssSatelliteSignal>,
    val receivedAtElapsedRealtimeNanos: Long = elapsedRealtimeNanos,
    val timeSource: GnssSignalTimeSource = GnssSignalTimeSource.CALLBACK_RECEIVED,
    val timestampUncertaintyNanos: Double? = null,
)

/** Hardware GNSS time is not an Android elapsed-realtime timestamp. */
internal fun gnssSignalEpochOrNull(
    signals: List<GnssSatelliteSignal>,
    receivedAtElapsedRealtimeNanos: Long,
    measuredAtElapsedRealtimeNanos: Long? = null,
    timestampUncertaintyNanos: Double? = null,
): GnssSignalEpoch? {
    if (receivedAtElapsedRealtimeNanos < 0L) return null
    if (timestampUncertaintyNanos != null &&
        (!timestampUncertaintyNanos.isFinite() || timestampUncertaintyNanos < 0.0)
    ) return null
    val observedAt = measuredAtElapsedRealtimeNanos ?: receivedAtElapsedRealtimeNanos
    // A supplied but invalid clock must not become a fresh receipt-time observation.
    if (observedAt < 0L || observedAt > receivedAtElapsedRealtimeNanos) return null
    return GnssSignalEpoch(
        elapsedRealtimeNanos = observedAt,
        signals = signals,
        receivedAtElapsedRealtimeNanos = receivedAtElapsedRealtimeNanos,
        timeSource = if (measuredAtElapsedRealtimeNanos != null) {
            GnssSignalTimeSource.MEASUREMENT_ELAPSED_REALTIME
        } else {
            GnssSignalTimeSource.CALLBACK_RECEIVED
        },
        timestampUncertaintyNanos = timestampUncertaintyNanos.takeIf {
            measuredAtElapsedRealtimeNanos != null
        },
    )
}

internal fun interface GnssObservationListener {
    fun onObservation(epoch: GnssSignalEpoch)
}

internal fun interface GnssObservationSubscription {
    fun cancel()
}

internal interface GnssObservationSource {
    fun subscribeStatus(listener: GnssObservationListener): GnssObservationSubscription?

    fun subscribeMeasurements(listener: GnssObservationListener): GnssObservationSubscription?
}

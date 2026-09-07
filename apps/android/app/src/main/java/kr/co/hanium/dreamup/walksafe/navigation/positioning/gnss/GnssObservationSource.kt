package kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss

internal data class GnssSignalEpoch(
    val elapsedRealtimeNanos: Long,
    val signals: List<GnssSatelliteSignal>,
)

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

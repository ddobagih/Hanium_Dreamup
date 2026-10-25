package kr.co.hanium.dreamup.walksafe.navigation.session

import kr.co.hanium.dreamup.walksafe.navigation.positioning.GnssSampleFingerprint
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.GnssQualitySnapshot

/** Immutable original measurement and receipt; collection ownership is checked by the consumer. */
internal data class PositioningLocationObservation(
    val sample: GnssSampleFingerprint,
    val receivedAtElapsedRealtimeMs: Long,
    val signalQuality: GnssQualitySnapshot? = null,
)

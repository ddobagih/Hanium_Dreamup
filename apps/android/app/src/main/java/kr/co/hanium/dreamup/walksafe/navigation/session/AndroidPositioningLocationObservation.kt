package kr.co.hanium.dreamup.walksafe.navigation.session

import android.location.Location
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.GnssQualitySnapshot
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnssSampleFingerprint

/** SDK copying only: no receipt-time substitution, validation, filtering or owner admission. */
internal fun Location.toPositioningObservation(
    receivedAtElapsedRealtimeMs: Long,
    mock: Boolean,
    signalQualityAt: (Long) -> GnssQualitySnapshot?,
): PositioningLocationObservation {
    val original = gnssSampleFingerprint(mock)
    return PositioningLocationObservation(
        sample = original,
        receivedAtElapsedRealtimeMs = receivedAtElapsedRealtimeMs,
        signalQuality = signalQualityAt(original.elapsedRealtimeNanos),
    )
}

package kr.co.hanium.dreamup.walksafe.navigation.positioning

import android.location.Location
import android.os.Build

/** Copies the original measurement; does not invent a new timestamp or apply a quality policy. */
internal fun Location.gnssSampleFingerprint(mock: Boolean): GnssSampleFingerprint = GnssSampleFingerprint(
    elapsedRealtimeNanos = elapsedRealtimeNanos,
    timeMillis = time,
    provider = provider,
    latitude = latitude,
    longitude = longitude,
    horizontalAccuracyM = if (hasAccuracy()) accuracy.toDouble() else null,
    mock = mock,
    altitudeM = if (hasAltitude()) altitude else null,
    speedMps = if (hasSpeed()) speed.toDouble() else null,
    bearingDegrees = if (hasBearing()) bearing.toDouble() else null,
    verticalAccuracyM = if (hasVerticalAccuracy()) verticalAccuracyMeters.toDouble() else null,
    speedAccuracyMps = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond.toDouble() else null,
    bearingAccuracyDegrees = if (hasBearingAccuracy()) bearingAccuracyDegrees.toDouble() else null,
    elapsedRealtimeUncertaintyNanos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        hasElapsedRealtimeUncertaintyNanos()) elapsedRealtimeUncertaintyNanos else null,
    mslAltitudeM = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        hasMslAltitude()) mslAltitudeMeters else null,
    mslAltitudeAccuracyM = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        hasMslAltitudeAccuracy()) mslAltitudeAccuracyMeters.toDouble() else null,
)

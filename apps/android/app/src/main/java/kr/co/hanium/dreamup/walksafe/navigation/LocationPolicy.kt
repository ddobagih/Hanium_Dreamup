package kr.co.hanium.dreamup.walksafe.navigation

// Validates fresh, accurate fixes and bounds route-distance calculations.

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class TrustedLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
    val elapsedRealtimeMs: Long,
)

data class LocationTrustConfig(
    val maxAccuracyM: Float = 15f,
    val maxJumpSpeedMps: Float = 8f,
    val maxAgeMs: Long = 10_000L,
)

object LocationTrustPolicy {
    fun trustedOrNull(
        latitude: Double,
        longitude: Double,
        accuracyM: Float?,
        elapsedRealtimeMs: Long,
        previous: TrustedLocation? = null,
        config: LocationTrustConfig = LocationTrustConfig(),
    ): TrustedLocation? {
        if (!latitude.isFinite() || latitude !in -90.0..90.0) return null
        if (!longitude.isFinite() || longitude !in -180.0..180.0) return null
        if (elapsedRealtimeMs < 0L) return null
        val accuracy = accuracyM ?: return null
        if (!accuracy.isFinite() || accuracy < 0f || accuracy > config.maxAccuracyM) return null
        val candidate = TrustedLocation(latitude, longitude, accuracy, elapsedRealtimeMs)
        if (previous != null) {
            val elapsedSeconds = (elapsedRealtimeMs - previous.elapsedRealtimeMs) / 1000.0
            if (elapsedSeconds <= 0.0) return null
            val distanceM = haversineMeters(previous.latitude, previous.longitude, latitude, longitude)
            val accuracyUncertaintyM = previous.accuracyM.toDouble() + accuracy.toDouble()
            val maxPlausibleDistanceM = config.maxJumpSpeedMps.toDouble() * elapsedSeconds + accuracyUncertaintyM
            if (distanceM > maxPlausibleDistanceM) return null
        }
        return candidate
    }

    fun freshOrNull(
        location: TrustedLocation?,
        nowElapsedRealtimeMs: Long,
        config: LocationTrustConfig = LocationTrustConfig(),
    ): TrustedLocation? {
        val trusted = location ?: return null
        val ageMs = nowElapsedRealtimeMs - trusted.elapsedRealtimeMs
        return trusted.takeIf { ageMs in 0L..config.maxAgeMs }
    }
}

fun haversineMeters(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double,
): Double {
    val earthRadiusM = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val rLat1 = Math.toRadians(lat1)
    val rLat2 = Math.toRadians(lat2)
    val a = sin(dLat / 2).pow(2) + cos(rLat1) * cos(rLat2) * sin(dLon / 2).pow(2)
    return earthRadiusM * 2 * atan2(sqrt(a), sqrt(1 - a))
}

fun bearingDegrees(
    fromLat: Double,
    fromLon: Double,
    toLat: Double,
    toLon: Double,
): Float {
    val fromLatRad = Math.toRadians(fromLat)
    val toLatRad = Math.toRadians(toLat)
    val deltaLon = Math.toRadians(toLon - fromLon)
    val x = kotlin.math.sin(deltaLon) * kotlin.math.cos(toLatRad)
    val y = kotlin.math.cos(fromLatRad) * kotlin.math.sin(toLatRad) -
        kotlin.math.sin(fromLatRad) * kotlin.math.cos(toLatRad) * kotlin.math.cos(deltaLon)
    val raw = Math.toDegrees(kotlin.math.atan2(x, y))
    return ((raw + 360.0) % 360.0).toFloat()
}

fun reliableMovementHeadingDegrees(
    reportedBearingDeg: Float?,
    speedMps: Float?,
    bearingAccuracyDeg: Float?,
    previous: TrustedLocation?,
    current: TrustedLocation,
    minimumSpeedMps: Float = 0.3f,
    maximumBearingAccuracyDeg: Float = 35f,
): Float? {
    val bearing = reportedBearingDeg?.takeIf { it.isFinite() && it in 0f..<360f }
    val speed = speedMps?.takeIf { it.isFinite() && it >= minimumSpeedMps }
    val bearingAccuracyTrusted = bearingAccuracyDeg == null ||
        (bearingAccuracyDeg.isFinite() && bearingAccuracyDeg in 0f..maximumBearingAccuracyDeg)
    if (bearing != null && speed != null && bearingAccuracyTrusted) return bearing
    val baseline = previous ?: return null
    val movedM = haversineMeters(
        baseline.latitude,
        baseline.longitude,
        current.latitude,
        current.longitude,
    )
    val minimumMovementM = maxOf(3.0, baseline.accuracyM.toDouble(), current.accuracyM.toDouble())
    if (movedM < minimumMovementM) return null
    return bearingDegrees(baseline.latitude, baseline.longitude, current.latitude, current.longitude)
}

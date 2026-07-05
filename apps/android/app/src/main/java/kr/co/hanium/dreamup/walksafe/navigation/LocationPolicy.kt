package kr.co.hanium.dreamup.walksafe.navigation

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
    val maxAccuracyM: Float = 25f,
    val maxJumpSpeedMps: Float = 8f,
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
        val accuracy = accuracyM ?: return null
        if (!accuracy.isFinite() || accuracy > config.maxAccuracyM) return null
        val candidate = TrustedLocation(latitude, longitude, accuracy, elapsedRealtimeMs)
        if (previous != null) {
            val elapsedSeconds = (elapsedRealtimeMs - previous.elapsedRealtimeMs) / 1000.0
            if (elapsedSeconds > 0.5) {
                val speedMps = haversineMeters(previous.latitude, previous.longitude, latitude, longitude) / elapsedSeconds
                if (speedMps > config.maxJumpSpeedMps) return null
            }
        }
        return candidate
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

package kr.co.hanium.dreamup.walksafe.navigation

/** Magnetic declination needs geographic location, independently of metre-scale route/PDR trust. */
object RouteCompassLocationReference {
    fun accepts(
        latitude: Double,
        longitude: Double,
        observedAtElapsedRealtimeMs: Long,
        nowElapsedRealtimeMs: Long,
        mock: Boolean,
    ): Boolean = !mock && latitude.isFinite() && latitude in -90.0..90.0 &&
        longitude.isFinite() && longitude in -180.0..180.0 &&
        observedAtElapsedRealtimeMs >= 0L && nowElapsedRealtimeMs >= observedAtElapsedRealtimeMs &&
        nowElapsedRealtimeMs - observedAtElapsedRealtimeMs <= 10_000L
}

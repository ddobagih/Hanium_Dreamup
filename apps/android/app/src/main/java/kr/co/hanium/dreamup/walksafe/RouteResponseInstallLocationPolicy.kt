package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.navigation.WalkingRoute
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.LocationTrustPolicy
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorConfig
import kr.co.hanium.dreamup.walksafe.navigation.LocationTrustConfig
import kr.co.hanium.dreamup.walksafe.navigation.MAXIMUM_ROUTE_APPROACH_DISTANCE_M
import kr.co.hanium.dreamup.walksafe.navigation.distanceToPolylineMeters
import kr.co.hanium.dreamup.walksafe.navigation.haversineMeters

internal enum class RouteResponseInstallLocationStatus {
    CONNECTED,
    LOCATION_UNAVAILABLE,
    ROUTE_DISCONNECTED,
    APPROACH_REQUIRED,
}

/** Recovery requires intact provider provenance; current distance alone must not admit a bad route. */
internal fun routeResponseHasRecoverableCurrentConnection(
    route: WalkingRoute,
    requestOrigin: TrustedLocation,
    location: TrustedLocation?,
    nowElapsedRealtimeMs: Long,
): Boolean {
    if (!requestOrigin.latitude.isFinite() || requestOrigin.latitude !in -90.0..90.0 ||
        !requestOrigin.longitude.isFinite() || requestOrigin.longitude !in -180.0..180.0 ||
        !requestOrigin.accuracyM.isFinite() || requestOrigin.accuracyM !in 0f..LocationTrustConfig().maxAccuracyM ||
        requestOrigin.elapsedRealtimeMs !in 0L..nowElapsedRealtimeMs || route.polyline.size < 2 ||
        route.polyline.any { !it.latitude.isFinite() || it.latitude !in -90.0..90.0 ||
            !it.longitude.isFinite() || it.longitude !in -180.0..180.0 } ||
        route.polyline.zipWithNext().none { (start, end) ->
            haversineMeters(start.latitude, start.longitude, end.latitude, end.longitude) > 0.01
        }
    ) return false
    val start = route.polyline.first()
    val originToStartM = haversineMeters(requestOrigin.latitude, requestOrigin.longitude,
        start.latitude, start.longitude)
    if (!originToStartM.isFinite() || originToStartM > MAXIMUM_ROUTE_APPROACH_DISTANCE_M) return false
    val fresh = LocationTrustPolicy.freshOrNull(location, nowElapsedRealtimeMs) ?: return false
    if (!fresh.latitude.isFinite() || fresh.latitude !in -90.0..90.0 ||
        !fresh.longitude.isFinite() || fresh.longitude !in -180.0..180.0 ||
        !fresh.accuracyM.isFinite() || fresh.accuracyM !in 0f..LocationTrustConfig().maxAccuracyM ||
        fresh.elapsedRealtimeMs < requestOrigin.elapsedRealtimeMs
    ) return false
    val currentDistanceM = distanceToPolylineMeters(fresh, route.polyline)
    return currentDistanceM.isFinite() && currentDistanceM > MAXIMUM_ROUTE_APPROACH_DISTANCE_M
}

/** A fresh fix can follow the route or approach its nearest point without inventing route progress. */
@Suppress("UNUSED_PARAMETER") // Retain the old caller signature; neither value restores a first-point connector.
internal fun routeResponseInstallLocationStatus(
    route: WalkingRoute,
    requestOrigin: TrustedLocation,
    location: TrustedLocation?,
    nowElapsedRealtimeMs: Long,
    allowInitialStartConnector: Boolean = true,
): RouteResponseInstallLocationStatus {
    val fresh = LocationTrustPolicy.freshOrNull(location, nowElapsedRealtimeMs)
        ?: return RouteResponseInstallLocationStatus.LOCATION_UNAVAILABLE
    val distanceM = distanceToPolylineMeters(fresh, route.polyline)
    val maximumRouteDistanceM = RouteNavigatorConfig().offRouteDistanceM
    if (!distanceM.isFinite() ||
        !fresh.accuracyM.isFinite() || fresh.accuracyM !in 0f..100f
    ) return RouteResponseInstallLocationStatus.ROUTE_DISCONNECTED
    // Match RouteAlignmentSelector's local search radius before accepting a route as usable.
    if (distanceM <= maximumRouteDistanceM &&
        distanceM <= maxOf(8.0, fresh.accuracyM.toDouble() * 2.0)) {
        return RouteResponseInstallLocationStatus.CONNECTED
    }
    if (distanceM > MAXIMUM_ROUTE_APPROACH_DISTANCE_M || fresh.accuracyM > LocationTrustConfig().maxAccuracyM ||
        !requestOrigin.latitude.isFinite() || requestOrigin.latitude !in -90.0..90.0 ||
        !requestOrigin.longitude.isFinite() || requestOrigin.longitude !in -180.0..180.0 ||
        !requestOrigin.accuracyM.isFinite() || requestOrigin.accuracyM !in 0f..100f ||
        requestOrigin.elapsedRealtimeMs !in 0L..fresh.elapsedRealtimeMs ||
        route.polyline.any { !it.latitude.isFinite() || it.latitude !in -90.0..90.0 ||
            !it.longitude.isFinite() || it.longitude !in -180.0..180.0 } ||
        route.polyline.zipWithNext().none { (start, end) ->
            haversineMeters(start.latitude, start.longitude, end.latitude, end.longitude) > 0.01
        }
    ) return RouteResponseInstallLocationStatus.ROUTE_DISCONNECTED
    // The request origin remains provider provenance, not a bound on subsequent pedestrian drift.
    // Match the backend's endpoint acceptance; the approach target is always the CURRENT closest point.
    val start = route.polyline.first()
    val originToStartM = haversineMeters(requestOrigin.latitude, requestOrigin.longitude,
        start.latitude, start.longitude)
    return if (originToStartM.isFinite() && originToStartM <= MAXIMUM_ROUTE_APPROACH_DISTANCE_M)
        RouteResponseInstallLocationStatus.APPROACH_REQUIRED
    else RouteResponseInstallLocationStatus.ROUTE_DISCONNECTED
}

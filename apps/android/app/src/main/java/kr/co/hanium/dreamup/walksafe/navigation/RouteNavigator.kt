package kr.co.hanium.dreamup.walksafe.navigation

import kotlin.math.roundToInt

data class RouteNavigatorConfig(
    val arrivalRadiusM: Double = 12.0,
    val offRouteDistanceM: Double = 35.0,
    val offRouteConfirmSamples: Int = 2,
    val rerouteCooldownMs: Long = 20_000L,
    val maxRerouteCount: Int = 3,
    val guidanceIntervalMs: Long = 6_000L,
)

data class RouteNavigatorUpdate(
    val instruction: String?,
    val arrived: Boolean,
    val offRoute: Boolean,
    val shouldReroute: Boolean,
    val reason: String,
)

class RouteNavigator(
    private val config: RouteNavigatorConfig = RouteNavigatorConfig(),
) {
    private var route: WalkingRoute? = null
    private var nextGuideIndex = 0
    private var lastGuidanceAtMs: Long? = null
    private var lastRerouteAtMs: Long? = null
    private var rerouteCount = 0
    private var offRouteSampleCount = 0

    fun setRoute(route: WalkingRoute) {
        this.route = route
        nextGuideIndex = 0
        lastGuidanceAtMs = null
        lastRerouteAtMs = null
        rerouteCount = 0
        offRouteSampleCount = 0
    }

    fun clear() {
        route = null
        nextGuideIndex = 0
        lastGuidanceAtMs = null
        lastRerouteAtMs = null
        rerouteCount = 0
        offRouteSampleCount = 0
    }

    fun currentBearingDeg(): Float? {
        val currentRoute = route ?: return null
        return currentRoute.guidePoints.getOrNull(nextGuideIndex)?.bearingDeg
            ?: currentRoute.guidePoints.firstOrNull { it.bearingDeg != null }?.bearingDeg
    }

    fun update(
        location: TrustedLocation,
        nowMs: Long,
        requestInFlight: Boolean,
    ): RouteNavigatorUpdate {
        val currentRoute = route ?: return RouteNavigatorUpdate(null, false, false, false, "route_missing")
        val destination = currentRoute.polyline.lastOrNull() ?: return RouteNavigatorUpdate(null, false, false, false, "polyline_missing")
        val remainingM = haversineMeters(location.latitude, location.longitude, destination.latitude, destination.longitude)
        if (remainingM <= config.arrivalRadiusM) {
            clear()
            return RouteNavigatorUpdate("목적지에 도착했습니다.", arrived = true, offRoute = false, shouldReroute = false, reason = "arrival_radius")
        }

        val distanceToRouteM = distanceToPolylineMeters(location, currentRoute.polyline)
        val offRouteCandidate = distanceToRouteM - location.accuracyM > config.offRouteDistanceM
        offRouteSampleCount = if (offRouteCandidate) offRouteSampleCount + 1 else 0
        val offRoute = offRouteSampleCount >= config.offRouteConfirmSamples.coerceAtLeast(1)
        val rerouteAllowed = offRoute &&
            !requestInFlight &&
            rerouteCount < config.maxRerouteCount &&
            (lastRerouteAtMs == null || nowMs - requireNotNull(lastRerouteAtMs) >= config.rerouteCooldownMs)
        if (rerouteAllowed) {
            rerouteCount += 1
            lastRerouteAtMs = nowMs
            return RouteNavigatorUpdate("경로를 벗어났습니다. 경로를 다시 찾습니다.", false, true, true, "off_route_reroute")
        }
        if (offRouteCandidate && !offRoute) {
            return RouteNavigatorUpdate(null, arrived = false, offRoute = false, shouldReroute = false, reason = "off_route_pending")
        }

        val lastGuidance = lastGuidanceAtMs
        if (lastGuidance != null && nowMs - lastGuidance < config.guidanceIntervalMs) {
            return RouteNavigatorUpdate(null, false, offRoute, false, "guidance_rate_limited")
        }

        val guideInstruction = nextInstruction(currentRoute, location)
        lastGuidanceAtMs = nowMs
        return RouteNavigatorUpdate(guideInstruction, arrived = false, offRoute = offRoute, shouldReroute = false, reason = "route_guidance")
    }

    private fun nextInstruction(route: WalkingRoute, location: TrustedLocation): String {
        while (nextGuideIndex < route.guidePoints.size) {
            val guide = route.guidePoints[nextGuideIndex]
            val distanceM = haversineMeters(location.latitude, location.longitude, guide.point.latitude, guide.point.longitude)
            if (distanceM <= 10.0) nextGuideIndex += 1 else break
        }
        val guide = route.guidePoints.getOrNull(nextGuideIndex)
        if (guide != null) {
            val distanceM = haversineMeters(location.latitude, location.longitude, guide.point.latitude, guide.point.longitude).roundToInt()
            val text = guide.instruction?.takeIf { it.isNotBlank() } ?: "전방 ${distanceM}m 지점까지 이동하세요."
            return if (distanceM > 0 && guide.instruction != null) "${distanceM}m 앞, $text" else text
        }
        val destination = route.polyline.last()
        val remainingM = haversineMeters(location.latitude, location.longitude, destination.latitude, destination.longitude).roundToInt()
        return "목적지까지 약 ${remainingM}m 남았습니다."
    }
}

fun distanceToPolylineMeters(location: TrustedLocation, polyline: List<RoutePoint>): Double {
    if (polyline.isEmpty()) return Double.POSITIVE_INFINITY
    if (polyline.size == 1) {
        val point = polyline.single()
        return haversineMeters(location.latitude, location.longitude, point.latitude, point.longitude)
    }
    return polyline.zipWithNext().minOf { (a, b) -> distanceToSegmentMeters(location, a, b) }
}

private fun distanceToSegmentMeters(location: TrustedLocation, start: RoutePoint, end: RoutePoint): Double {
    val latScale = 111_320.0
    val lonScale = 111_320.0 * kotlin.math.cos(Math.toRadians(location.latitude))
    val px = 0.0
    val py = 0.0
    val ax = (start.longitude - location.longitude) * lonScale
    val ay = (start.latitude - location.latitude) * latScale
    val bx = (end.longitude - location.longitude) * lonScale
    val by = (end.latitude - location.latitude) * latScale
    val abx = bx - ax
    val aby = by - ay
    val denominator = abx * abx + aby * aby
    if (denominator <= 0.000001) return kotlin.math.hypot(ax, ay)
    val t = (((px - ax) * abx + (py - ay) * aby) / denominator).coerceIn(0.0, 1.0)
    val x = ax + abx * t
    val y = ay + aby * t
    return kotlin.math.hypot(x, y)
}

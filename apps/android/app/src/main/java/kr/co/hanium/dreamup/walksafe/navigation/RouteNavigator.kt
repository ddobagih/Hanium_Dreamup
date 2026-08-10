package kr.co.hanium.dreamup.walksafe.navigation

import java.security.MessageDigest
import kotlin.math.roundToInt

data class RouteNavigatorConfig(
    val arrivalRadiusM: Double = 12.0,
    val arrivalMaxAccuracyM: Float = 10f,
    val arrivalConfirmSamples: Int = 2,
    val offRouteDistanceM: Double = 35.0,
    val offRouteConfirmSamples: Int = 2,
    val rerouteCooldownMs: Long = 20_000L,
    val maxRerouteCount: Int = 3,
    val guidanceIntervalMs: Long = 6_000L,
    val maximumProgressBacktrackM: Double = 8.0,
    val maximumProgressAdvanceM: Double = 80.0,
)

data class RouteNavigatorUpdate(
    val instruction: String?,
    val arrived: Boolean,
    val offRoute: Boolean,
    val shouldReroute: Boolean,
    val reason: String,
    val guideIndex: Int? = null,
)

/** Frame-independent identity and nearest-segment evidence for the currently installed TMAP route. */
data class ActiveRouteProjection(
    val routeId: String,
    val segmentIndex: Int,
    val segmentStart: RoutePoint,
    val segmentEnd: RoutePoint,
    val distanceToRouteM: Double,
    val bearingDeg: Float,
)

/**
 * Stateful GPS route progress and reroute policy. Off-route confirmation uses successive trusted
 * fixes and subtracts reported accuracy before comparing the fix with the route corridor.
 */
class RouteNavigator(
    private val config: RouteNavigatorConfig = RouteNavigatorConfig(),
) {
    private var route: WalkingRoute? = null
    private var activeRouteId: String? = null
    private var requestedDestination: RoutePoint? = null
    private var nextGuideIndex = 0
    private var lastGuidanceAtMs: Long? = null
    private var lastRerouteAtMs: Long? = null
    private var rerouteCount = 0
    private var offRouteSampleCount = 0
    private var arrivalSampleCount = 0
    private var progressDistanceM: Double? = null
    private var latestRouteBearingDeg: Float? = null
    private var announcedGuideIndex: Int? = null
    private var closestDistanceToAnnouncedGuideM: Double? = null

    @Synchronized
    fun setRoute(
        route: WalkingRoute,
        destination: RoutePoint? = null,
        resetRerouteBudget: Boolean = true,
    ) {
        this.route = route
        activeRouteId = route.providerRouteId?.trim()?.takeIf(String::isNotEmpty) ?: route.localRouteFingerprint()
        requestedDestination = destination
        nextGuideIndex = 0
        lastGuidanceAtMs = null
        offRouteSampleCount = 0
        arrivalSampleCount = 0
        progressDistanceM = null
        latestRouteBearingDeg = null
        announcedGuideIndex = null
        closestDistanceToAnnouncedGuideM = null
        if (resetRerouteBudget) {
            lastRerouteAtMs = null
            rerouteCount = 0
        }
    }

    @Synchronized
    fun clear() {
        route = null
        activeRouteId = null
        requestedDestination = null
        nextGuideIndex = 0
        lastGuidanceAtMs = null
        lastRerouteAtMs = null
        rerouteCount = 0
        offRouteSampleCount = 0
        arrivalSampleCount = 0
        progressDistanceM = null
        latestRouteBearingDeg = null
        announcedGuideIndex = null
        closestDistanceToAnnouncedGuideM = null
    }

    @Synchronized
    fun currentBearingDeg(): Float? {
        val currentRoute = route ?: return null
        return latestRouteBearingDeg
            ?: currentRoute.guidePoints.getOrNull(nextGuideIndex)?.bearingDeg
            ?: currentRoute.guidePoints.firstOrNull { it.bearingDeg != null }?.bearingDeg
    }

    @Synchronized
    fun hasRoute(): Boolean = route != null

    @Synchronized
    fun currentRouteId(): String? = activeRouteId

    @Synchronized
    fun currentProjection(location: TrustedLocation): ActiveRouteProjection? {
        val currentRoute = route ?: return null
        val routeId = activeRouteId ?: return null
        val projection = projectToRoute(
            location = location,
            route = currentRoute,
            previousDistanceFromStartM = progressDistanceM,
            maximumBacktrackM = config.maximumProgressBacktrackM,
            maximumAdvanceM = config.maximumProgressAdvanceM,
        ) ?: return null
        return ActiveRouteProjection(
            routeId = routeId,
            segmentIndex = projection.segmentIndex,
            segmentStart = projection.segmentStart,
            segmentEnd = projection.segmentEnd,
            distanceToRouteM = projection.distanceToRouteM,
            bearingDeg = projection.bearingDeg,
        )
    }

    @Synchronized
    fun currentInstruction(location: TrustedLocation): String? {
        val currentRoute = route ?: return null
        if (offRouteSampleCount >= config.offRouteConfirmSamples.coerceAtLeast(1)) return null
        return instructionForCurrentGuide(currentRoute, location).text
    }

    /** Commits cooldown and guide consumption only after the caller accepted the speech. */
    @Synchronized
    fun acknowledgeInstruction(update: RouteNavigatorUpdate, spokenAtMs: Long) {
        if (update.instruction == null) return
        if (update.arrived && update.reason == "arrival_radius") {
            clear()
            return
        }
        lastGuidanceAtMs = spokenAtMs
        if (update.reason == "route_guidance" && update.guideIndex == nextGuideIndex) {
            announcedGuideIndex = update.guideIndex
            closestDistanceToAnnouncedGuideM = null
        }
    }

    @Synchronized
    fun acknowledgeCurrentInstruction(spokenAtMs: Long) {
        val currentRoute = route ?: return
        lastGuidanceAtMs = spokenAtMs
        if (currentRoute.guidePoints.getOrNull(nextGuideIndex) != null) {
            announcedGuideIndex = nextGuideIndex
            closestDistanceToAnnouncedGuideM = null
        }
    }

    @Synchronized
    fun update(
        location: TrustedLocation,
        nowMs: Long,
        requestInFlight: Boolean,
    ): RouteNavigatorUpdate {
        val currentRoute = route ?: return RouteNavigatorUpdate(null, false, false, false, "route_missing")
        val routeEndpoint = currentRoute.polyline.lastOrNull()
            ?: return RouteNavigatorUpdate(null, false, false, false, "polyline_missing")
        val destination = requestedDestination ?: routeEndpoint
        val projection = projectToRoute(
            location = location,
            route = currentRoute,
            previousDistanceFromStartM = progressDistanceM,
            maximumBacktrackM = config.maximumProgressBacktrackM,
            maximumAdvanceM = config.maximumProgressAdvanceM,
        )
        val distanceToRouteM = projection?.distanceToRouteM ?: distanceToPolylineMeters(location, currentRoute.polyline)
        if (projection != null) {
            val previousProgress = progressDistanceM
            val boundedProgress = if (previousProgress == null) {
                projection.distanceFromStartM
            } else {
                projection.distanceFromStartM.coerceAtMost(previousProgress + config.maximumProgressAdvanceM)
            }
            progressDistanceM = maxOf(previousProgress ?: 0.0, boundedProgress)
            latestRouteBearingDeg = projection.bearingDeg
        }
        advanceAnnouncedPassedGuides(currentRoute, location, progressDistanceM)
        val remainingM = haversineMeters(location.latitude, location.longitude, destination.latitude, destination.longitude)
        val remainingRouteM = progressDistanceM?.let { currentRoute.summary.distanceM - it }
        val nearRouteEnd = remainingRouteM != null &&
            remainingRouteM <= maxOf(20.0, config.arrivalRadiusM + location.accuracyM)
        val arrivalEvidence = nearRouteEnd &&
            location.accuracyM <= config.arrivalMaxAccuracyM &&
            remainingM + location.accuracyM <= config.arrivalRadiusM
        arrivalSampleCount = if (arrivalEvidence) arrivalSampleCount + 1 else 0
        if (arrivalSampleCount >= config.arrivalConfirmSamples.coerceAtLeast(1)) {
            return RouteNavigatorUpdate("목적지에 도착했습니다.", arrived = true, offRoute = false, shouldReroute = false, reason = "arrival_radius")
        }
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

        if (offRoute) {
            val lastGuidance = lastGuidanceAtMs
            if (lastGuidance != null && nowMs - lastGuidance < config.guidanceIntervalMs) {
                return RouteNavigatorUpdate(null, false, true, false, "off_route_waiting_rate_limited")
            }
            return RouteNavigatorUpdate(
                "경로를 벗어났습니다. 안전한 위치에서 재탐색을 기다려 주세요.",
                arrived = false,
                offRoute = true,
                shouldReroute = false,
                reason = "off_route_waiting",
            )
        }

        val lastGuidance = lastGuidanceAtMs
        if (lastGuidance != null && nowMs - lastGuidance < config.guidanceIntervalMs) {
            return RouteNavigatorUpdate(null, false, offRoute, false, "guidance_rate_limited")
        }

        val guideInstruction = instructionForCurrentGuide(currentRoute, location)
        return RouteNavigatorUpdate(
            instruction = guideInstruction.text,
            arrived = false,
            offRoute = offRoute,
            shouldReroute = false,
            reason = "route_guidance",
            guideIndex = guideInstruction.guideIndex,
        )
    }

    private fun advanceAnnouncedPassedGuides(
        route: WalkingRoute,
        location: TrustedLocation,
        progressM: Double?,
    ) {
        while (announcedGuideIndex == nextGuideIndex) {
            val guide = route.guidePoints.getOrNull(nextGuideIndex) ?: break
            if (!hasPassedGuide(guide, location, progressM)) break
            nextGuideIndex += 1
            announcedGuideIndex = null
            closestDistanceToAnnouncedGuideM = null
        }
    }

    private fun hasPassedGuide(
        guide: WalkingRouteGuidePoint,
        location: TrustedLocation,
        progressM: Double?,
    ): Boolean {
        val distanceM = haversineMeters(location.latitude, location.longitude, guide.point.latitude, guide.point.longitude)
        val previousClosestM = closestDistanceToAnnouncedGuideM
        closestDistanceToAnnouncedGuideM = minOf(previousClosestM ?: distanceM, distanceM)
        val passedByProgress = guide.distanceFromStartM?.let { distance ->
            progressM != null && progressM >= distance + maxOf(3.0, location.accuracyM.toDouble() / 2.0)
        } ?: false
        val passedByApproachAndRecede = previousClosestM != null &&
            previousClosestM <= maxOf(8.0, location.accuracyM.toDouble()) &&
            distanceM >= previousClosestM + maxOf(4.0, location.accuracyM.toDouble() / 2.0)
        return passedByProgress || passedByApproachAndRecede
    }

    private fun instructionForCurrentGuide(route: WalkingRoute, location: TrustedLocation): RouteInstruction {
        val guide = route.guidePoints.getOrNull(nextGuideIndex)
        if (guide != null) {
            CrosswalkReferencePolicy.noticeFor(guide)?.let { notice ->
                return RouteInstruction(text = notice, guideIndex = nextGuideIndex)
            }
            val distanceM = haversineMeters(location.latitude, location.longitude, guide.point.latitude, guide.point.longitude).roundToInt()
            val text = guide.instruction?.takeIf { it.isNotBlank() } ?: "전방 ${distanceM}m 지점까지 이동하세요."
            return RouteInstruction(
                text = if (distanceM > 0 && guide.instruction != null) "${distanceM}m 앞, $text" else text,
                guideIndex = nextGuideIndex,
            )
        }
        val endpoint = route.polyline.last()
        val destination = requestedDestination ?: endpoint
        val endpointGapM = haversineMeters(endpoint.latitude, endpoint.longitude, destination.latitude, destination.longitude)
        val remainingM = haversineMeters(location.latitude, location.longitude, destination.latitude, destination.longitude).roundToInt()
        val endpointRemainingM = haversineMeters(location.latitude, location.longitude, endpoint.latitude, endpoint.longitude).roundToInt()
        val text = if (endpointGapM > config.arrivalRadiusM) {
            if (endpointRemainingM <= maxOf(20, location.accuracyM.roundToInt())) {
                "TMAP 경로 종점입니다. 목적지가 약 ${remainingM}m 떨어져 있어 최종 접근을 확인하세요."
            } else {
                "TMAP 경로 종점까지 약 ${endpointRemainingM}m, 목적지까지 약 ${remainingM}m 남았습니다."
            }
        } else {
            "목적지까지 약 ${remainingM}m 남았습니다."
        }
        return RouteInstruction(text = text, guideIndex = null)
    }
}

private data class RouteInstruction(
    val text: String,
    val guideIndex: Int?,
)

private data class RouteProjection(
    val distanceToRouteM: Double,
    val distanceFromStartM: Double,
    val bearingDeg: Float,
    val segmentIndex: Int,
    val segmentStart: RoutePoint,
    val segmentEnd: RoutePoint,
)

private fun projectToRoute(
    location: TrustedLocation,
    route: WalkingRoute,
    previousDistanceFromStartM: Double?,
    maximumBacktrackM: Double,
    maximumAdvanceM: Double,
): RouteProjection? {
    if (route.polyline.size < 2) return null
    val segmentLengths = route.polyline.zipWithNext().map { (start, end) ->
        haversineMeters(start.latitude, start.longitude, end.latitude, end.longitude)
    }
    val geometricTotalM = segmentLengths.sum()
    if (geometricTotalM <= 0.0 || !geometricTotalM.isFinite()) return null
    val routeScale = route.summary.distanceM.toDouble().takeIf { it > 0.0 }?.div(geometricTotalM) ?: 1.0
    var cumulativeM = 0.0
    var best: RouteProjection? = null
    var continuousBest: RouteProjection? = null
    val candidates = mutableListOf<RouteProjection>()
    val minimumContinuousM = previousDistanceFromStartM?.minus(maximumBacktrackM)?.coerceAtLeast(0.0)
    val maximumContinuousM = previousDistanceFromStartM?.plus(maximumAdvanceM)
    route.polyline.zipWithNext().forEachIndexed { index, (start, end) ->
        val projected = projectToSegment(location, start, end)
        val candidate = RouteProjection(
            distanceToRouteM = projected.distanceM,
            distanceFromStartM = (cumulativeM + segmentLengths[index] * projected.fraction) * routeScale,
            bearingDeg = bearingDegrees(start.latitude, start.longitude, end.latitude, end.longitude),
            segmentIndex = index,
            segmentStart = start,
            segmentEnd = end,
        )
        candidates += candidate
        if (best == null || candidate.distanceToRouteM < requireNotNull(best).distanceToRouteM) best = candidate
        if (
            minimumContinuousM != null &&
            maximumContinuousM != null &&
            candidate.distanceFromStartM in minimumContinuousM..maximumContinuousM &&
            (continuousBest == null || candidate.distanceToRouteM < requireNotNull(continuousBest).distanceToRouteM)
        ) {
            continuousBest = candidate
        }
        cumulativeM += segmentLengths[index]
    }
    val globalBest = best ?: return null
    if (previousDistanceFromStartM == null) {
        val start = route.polyline.first()
        val distanceToStartM = haversineMeters(location.latitude, location.longitude, start.latitude, start.longitude)
        if (distanceToStartM <= maxOf(15.0, location.accuracyM.toDouble() * 2.0)) {
            return candidates
                .filter { it.distanceToRouteM <= globalBest.distanceToRouteM + 15.0 }
                .minByOrNull(RouteProjection::distanceFromStartM)
                ?: globalBest
        }
    }
    val continuous = continuousBest
    return if (continuous != null && continuous.distanceToRouteM <= globalBest.distanceToRouteM + 25.0) {
        continuous
    } else {
        globalBest
    }
}

private data class SegmentProjection(val fraction: Double, val distanceM: Double)

private fun projectToSegment(location: TrustedLocation, start: RoutePoint, end: RoutePoint): SegmentProjection {
    val latScale = 111_320.0
    val lonScale = 111_320.0 * kotlin.math.cos(Math.toRadians(location.latitude))
    val ax = (start.longitude - location.longitude) * lonScale
    val ay = (start.latitude - location.latitude) * latScale
    val bx = (end.longitude - location.longitude) * lonScale
    val by = (end.latitude - location.latitude) * latScale
    val abx = bx - ax
    val aby = by - ay
    val denominator = abx * abx + aby * aby
    val fraction = if (denominator <= 0.000001) 0.0 else (-(ax * abx + ay * aby) / denominator).coerceIn(0.0, 1.0)
    return SegmentProjection(
        fraction = fraction,
        distanceM = kotlin.math.hypot(ax + abx * fraction, ay + aby * fraction),
    )
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
    return projectToSegment(location, start, end).distanceM
}

private fun WalkingRoute.localRouteFingerprint(): String {
    val canonical = buildString {
        append(priority).append('|')
        append(summary.distanceM).append('|').append(summary.durationS)
        polyline.forEach { point ->
            append('|').append(point.latitude).append(',').append(point.longitude)
        }
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
    return "tmap-local:" + digest.take(12).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

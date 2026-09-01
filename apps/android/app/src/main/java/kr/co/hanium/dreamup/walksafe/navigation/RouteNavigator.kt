package kr.co.hanium.dreamup.walksafe.navigation

import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt

data class RouteNavigatorConfig(
    val arrivalRadiusM: Double = 12.0,
    val arrivalMaxAccuracyM: Float = 10f,
    val arrivalConfirmSamples: Int = 2,
    val offRouteDistanceM: Double = 35.0,
    val offRouteConfirmSamples: Int = 2,
    val maximumOffRouteSampleGapMs: Long = 10_000L,
    val guidanceIntervalMs: Long = 6_000L,
    val maximumProgressBacktrackM: Double = 8.0,
    val maximumProgressAdvanceM: Double = 80.0,
)

enum class RouteNavigatorUserDecision {
    REROUTE,
    LOCATION_RECHECK,
    ARRIVAL_CONFIRMATION,
}

enum class RouteDeviationChoice {
    NEW_ROUTE,
    RECHECK_LOCATION,
    END_NAVIGATION,
}

data class RouteNavigatorDecisionToken(
    val routeRevision: Long,
    val decisionRevision: Long,
    val routeId: String,
    val decision: RouteNavigatorUserDecision,
)

data class RouteNavigatorUpdate(
    val instruction: String?,
    val arrived: Boolean,
    val offRoute: Boolean,
    val shouldReroute: Boolean,
    val reason: String,
    val guideIndex: Int? = null,
    val pendingUserDecision: RouteNavigatorUserDecision? = null,
    val stepProgressConsistent: Boolean? = null,
    val cancelStaleNavigationSpeech: Boolean = false,
) {
    val userDecisionRequired: Boolean
        get() = pendingUserDecision != null

    val arrivalCandidate: Boolean
        get() = pendingUserDecision == RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION
}

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
    private var routeRevision = 0L
    private var decisionRevision = 0L
    private var activeRouteId: String? = null
    private var requestedDestination: RoutePoint? = null
    private var nextGuideIndex = 0
    private var lastGuidanceAtMs: Long? = null
    private var offRouteSampleCount = 0
    private var lastOffRouteCandidateAtMs: Long? = null
    private var arrivalSampleCount = 0
    private var pendingDecision: RouteNavigatorUserDecision? = null
    private var offRouteGuidanceSuspended = false
    private var deviationSuspectedLatched = false
    private var confirmedDeviationLatched = false
    private var locationRecheckAuthorized = false
    private var rerouteApprovedForCurrentDeviation = false
    private var progressDistanceM: Double? = null
    private var latestRouteBearingDeg: Float? = null
    private var announcedGuideIndex: Int? = null
    private var closestDistanceToAnnouncedGuideM: Double? = null

    @Synchronized
    fun setRoute(
        route: WalkingRoute,
        destination: RoutePoint? = null,
    ) {
        routeRevision += 1L
        this.route = route
        activeRouteId = route.providerRouteId?.trim()?.takeIf(String::isNotEmpty) ?: route.localRouteFingerprint()
        requestedDestination = destination
        nextGuideIndex = 0
        lastGuidanceAtMs = null
        offRouteSampleCount = 0
        lastOffRouteCandidateAtMs = null
        arrivalSampleCount = 0
        updatePendingDecision(null)
        offRouteGuidanceSuspended = false
        deviationSuspectedLatched = false
        confirmedDeviationLatched = false
        locationRecheckAuthorized = false
        rerouteApprovedForCurrentDeviation = false
        progressDistanceM = null
        latestRouteBearingDeg = null
        announcedGuideIndex = null
        closestDistanceToAnnouncedGuideM = null
    }

    @Synchronized
    fun clear() {
        routeRevision += 1L
        route = null
        activeRouteId = null
        requestedDestination = null
        nextGuideIndex = 0
        lastGuidanceAtMs = null
        offRouteSampleCount = 0
        lastOffRouteCandidateAtMs = null
        arrivalSampleCount = 0
        updatePendingDecision(null)
        offRouteGuidanceSuspended = false
        deviationSuspectedLatched = false
        confirmedDeviationLatched = false
        locationRecheckAuthorized = false
        rerouteApprovedForCurrentDeviation = false
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
    fun pendingUserDecision(): RouteNavigatorUserDecision? = pendingDecision

    /** An untrusted fix breaks confirmation evidence but never releases suspended guidance. */
    @Synchronized
    fun onUntrustedLocation(): RouteNavigatorUpdate? {
        offRouteSampleCount = 0
        lastOffRouteCandidateAtMs = null
        arrivalSampleCount = 0
        if (route == null) return null
        offRouteGuidanceSuspended = true
        locationRecheckAuthorized = false
        if (confirmedDeviationLatched) {
            updatePendingDecision(RouteNavigatorUserDecision.REROUTE)
            return deviationDecisionRequiredUpdate()
        }
        deviationSuspectedLatched = true
        updatePendingDecision(RouteNavigatorUserDecision.LOCATION_RECHECK)
        return suspectedDeviationUpdate(
            reason = "location_untrusted_recheck_required",
            instruction = "현재 위치 정확도를 신뢰할 수 없어 오래된 방향 안내를 중지했습니다. " +
                "위치 다시 확인을 선택할 때까지 다음 방향 안내를 하지 않습니다.",
        )
    }

    @Synchronized
    fun pendingDecisionToken(): RouteNavigatorDecisionToken? {
        val decision = pendingDecision ?: return null
        val routeId = activeRouteId ?: return null
        return RouteNavigatorDecisionToken(routeRevision, decisionRevision, routeId, decision)
    }

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
        if (offRouteGuidanceSuspended || pendingDecision != null) return null
        return instructionForCurrentGuide(currentRoute, location).text
    }

    /** Commits cooldown and guide consumption only after the caller accepted the speech. */
    @Synchronized
    fun acknowledgeInstruction(update: RouteNavigatorUpdate, spokenAtMs: Long) {
        if (update.instruction == null) return
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
    fun approveReroute(): RouteNavigatorUpdate =
        selectDeviationChoice(RouteDeviationChoice.NEW_ROUTE)

    @Synchronized
    fun selectDeviationChoice(choice: RouteDeviationChoice): RouteNavigatorUpdate {
        if (route == null) {
            return RouteNavigatorUpdate(null, false, false, false, "reroute_decision_missing")
        }
        return when (choice) {
            RouteDeviationChoice.NEW_ROUTE -> {
                if (!confirmedDeviationLatched || pendingDecision != RouteNavigatorUserDecision.REROUTE) {
                    return RouteNavigatorUpdate(null, false, false, false, "reroute_decision_missing")
                }
                updatePendingDecision(null)
                rerouteApprovedForCurrentDeviation = true
                RouteNavigatorUpdate(
                    instruction = "사용자 선택으로 새 경로를 찾습니다.",
                    arrived = false,
                    offRoute = true,
                    shouldReroute = true,
                    reason = "off_route_reroute_approved",
                )
            }
            RouteDeviationChoice.RECHECK_LOCATION -> {
                if (
                    !confirmedDeviationLatched &&
                    deviationSuspectedLatched &&
                    pendingDecision == RouteNavigatorUserDecision.LOCATION_RECHECK
                ) {
                    offRouteSampleCount = 0
                    locationRecheckAuthorized = true
                    updatePendingDecision(null)
                    return RouteNavigatorUpdate(
                        instruction = "현재 위치를 다시 확인합니다. 새 위치가 정상일 때만 방향 안내를 재개합니다.",
                        arrived = false,
                        offRoute = false,
                        shouldReroute = false,
                        reason = "off_route_location_recheck_requested",
                    )
                }
                if (!confirmedDeviationLatched || pendingDecision != RouteNavigatorUserDecision.REROUTE) {
                    return RouteNavigatorUpdate(null, false, false, false, "reroute_decision_missing")
                }
                rerouteApprovedForCurrentDeviation = false
                decisionRevision += 1L
                RouteNavigatorUpdate(
                    instruction = "현재 위치를 다시 확인합니다. 기존 경로는 자동으로 재개하지 않습니다.",
                    arrived = false,
                    offRoute = true,
                    shouldReroute = false,
                    reason = "off_route_location_recheck_requested",
                    pendingUserDecision = RouteNavigatorUserDecision.REROUTE,
                )
            }
            RouteDeviationChoice.END_NAVIGATION -> {
                if (!confirmedDeviationLatched || pendingDecision != RouteNavigatorUserDecision.REROUTE) {
                    return RouteNavigatorUpdate(null, false, false, false, "reroute_decision_missing")
                }
                clear()
                RouteNavigatorUpdate(
                    instruction = "사용자 선택으로 길안내를 종료합니다.",
                    arrived = false,
                    offRoute = true,
                    shouldReroute = false,
                    reason = "off_route_navigation_ended",
                )
            }
        }
    }

    @Synchronized
    fun rerouteRequestFailed(): RouteNavigatorUpdate? {
        if (route == null || !confirmedDeviationLatched) return null
        rerouteApprovedForCurrentDeviation = false
        updatePendingDecision(RouteNavigatorUserDecision.REROUTE)
        return deviationDecisionRequiredUpdate()
    }

    @Synchronized
    fun confirmArrival(): RouteNavigatorUpdate {
        if (route == null || pendingDecision != RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION) {
            return RouteNavigatorUpdate(null, false, false, false, "arrival_confirmation_missing")
        }
        clear()
        return RouteNavigatorUpdate(
            instruction = "도착을 확인했습니다. 길안내를 종료합니다.",
            arrived = true,
            offRoute = false,
            shouldReroute = false,
            reason = "arrival_confirmed",
        )
    }

    @Synchronized
    fun confirmArrival(expectedToken: RouteNavigatorDecisionToken): RouteNavigatorUpdate {
        if (pendingDecisionToken() != expectedToken) {
            return staleArrivalDecisionUpdate()
        }
        return confirmArrival()
    }

    @Synchronized
    fun rejectArrival(): RouteNavigatorUpdate {
        if (route == null || pendingDecision != RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION) {
            return RouteNavigatorUpdate(null, false, false, false, "arrival_confirmation_missing")
        }
        updatePendingDecision(null)
        arrivalSampleCount = 0
        return RouteNavigatorUpdate(
            instruction = "도착하지 않은 것으로 확인했습니다. 현재 경로를 유지합니다.",
            arrived = false,
            offRoute = false,
            shouldReroute = false,
            reason = "arrival_rejected_route_retained",
        )
    }

    @Synchronized
    fun rejectArrival(expectedToken: RouteNavigatorDecisionToken): RouteNavigatorUpdate {
        if (pendingDecisionToken() != expectedToken) {
            return staleArrivalDecisionUpdate()
        }
        return rejectArrival()
    }

    @Synchronized
    fun update(
        location: TrustedLocation,
        nowMs: Long,
        requestInFlight: Boolean,
        stepProgressM: Double? = null,
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
        val remainingToDestinationM = haversineMeters(
            location.latitude,
            location.longitude,
            destination.latitude,
            destination.longitude,
        )
        val remainingRouteM = progressDistanceM?.let { (currentRoute.summary.distanceM - it).coerceAtLeast(0.0) }
        val nearRouteEnd = remainingRouteM != null &&
            remainingRouteM <= maxOf(20.0, config.arrivalRadiusM + location.accuracyM)
        val authoritativeArrivalEvidence = nearRouteEnd &&
            location.accuracyM <= config.arrivalMaxAccuracyM &&
            remainingToDestinationM + location.accuracyM <= config.arrivalRadiusM
        val stepProgressConsistent = stepProgressM?.let { stepProgress ->
            stepProgress.isFinite() &&
                stepProgress >= 0.0 &&
                progressDistanceM?.let { routeProgress ->
                    abs(stepProgress - routeProgress) <= maxOf(
                        config.maximumProgressAdvanceM,
                        location.accuracyM.toDouble() * 2.0,
                    )
                } != false
        }
        val arrivalEvidence = authoritativeArrivalEvidence
        val offRouteCandidate = distanceToRouteM - location.accuracyM > config.offRouteDistanceM
        if (confirmedDeviationLatched) {
            offRouteGuidanceSuspended = true
            if (rerouteApprovedForCurrentDeviation) {
                return RouteNavigatorUpdate(
                    instruction = null,
                    arrived = false,
                    offRoute = true,
                    shouldReroute = false,
                    reason = if (requestInFlight) {
                        "off_route_reroute_approved_in_flight"
                    } else {
                        "off_route_reroute_approved_waiting"
                    },
                )
            }
            updatePendingDecision(RouteNavigatorUserDecision.REROUTE)
            return deviationDecisionRequiredUpdate()
        }
        if (deviationSuspectedLatched) {
            observeOffRouteCandidate(offRouteCandidate, nowMs)
            if (offRouteSampleCount >= config.offRouteConfirmSamples.coerceAtLeast(1)) {
                confirmedDeviationLatched = true
                locationRecheckAuthorized = false
                updatePendingDecision(RouteNavigatorUserDecision.REROUTE)
                return deviationDecisionRequiredUpdate()
            }
            if (!locationRecheckAuthorized || offRouteCandidate) {
                locationRecheckAuthorized = false
                updatePendingDecision(RouteNavigatorUserDecision.LOCATION_RECHECK)
                return suspectedDeviationUpdate(
                    if (offRouteCandidate) "off_route_pending" else "off_route_location_recheck_required",
                )
            }
            deviationSuspectedLatched = false
            locationRecheckAuthorized = false
            offRouteGuidanceSuspended = false
            rerouteApprovedForCurrentDeviation = false
            updatePendingDecision(null)
        }
        if (pendingDecision == RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION) {
            return arrivalConfirmationRequiredUpdate(stepProgressConsistent)
        }
        arrivalSampleCount = if (arrivalEvidence) arrivalSampleCount + 1 else 0
        if (arrivalSampleCount >= config.arrivalConfirmSamples.coerceAtLeast(1)) {
            updatePendingDecision(RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION)
            return arrivalConfirmationRequiredUpdate(stepProgressConsistent)
        }
        observeOffRouteCandidate(offRouteCandidate, nowMs)
        val offRoute = offRouteSampleCount >= config.offRouteConfirmSamples.coerceAtLeast(1)
        if (offRouteCandidate) {
            offRouteGuidanceSuspended = true
            deviationSuspectedLatched = true
            if (offRoute) {
                confirmedDeviationLatched = true
                updatePendingDecision(RouteNavigatorUserDecision.REROUTE)
                return deviationDecisionRequiredUpdate()
            }
            updatePendingDecision(RouteNavigatorUserDecision.LOCATION_RECHECK)
            return suspectedDeviationUpdate("off_route_pending")
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
            stepProgressConsistent = stepProgressConsistent,
        )
    }

    private fun arrivalConfirmationRequiredUpdate(stepProgressConsistent: Boolean?): RouteNavigatorUpdate {
        return RouteNavigatorUpdate(
            instruction = "도착 후보입니다. 실제로 도착했다면 확인하고, 아니면 거절해 주세요.",
            arrived = false,
            offRoute = false,
            shouldReroute = false,
            reason = "arrival_confirmation_required",
            pendingUserDecision = RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION,
            stepProgressConsistent = stepProgressConsistent,
        )
    }

    private fun deviationDecisionRequiredUpdate(): RouteNavigatorUpdate {
        return RouteNavigatorUpdate(
            instruction = "경로를 벗어나 현재 방향 안내를 중지했습니다. 사용자 선택이 필요합니다. " +
                "새 경로 요청, 위치 다시 확인, 길안내 종료 중에서 선택해 주세요.",
            arrived = false,
            offRoute = true,
            shouldReroute = false,
            reason = "off_route_user_decision_required",
            pendingUserDecision = RouteNavigatorUserDecision.REROUTE,
            cancelStaleNavigationSpeech = true,
        )
    }

    private fun suspectedDeviationUpdate(
        reason: String,
        instruction: String =
            "경로 이탈이 의심되어 오래된 방향 안내를 중지했습니다. 위치 다시 확인이라고 말씀해 주세요.",
    ): RouteNavigatorUpdate {
        return RouteNavigatorUpdate(
            instruction = instruction,
            arrived = false,
            offRoute = false,
            shouldReroute = false,
            reason = reason,
            pendingUserDecision = RouteNavigatorUserDecision.LOCATION_RECHECK,
            cancelStaleNavigationSpeech = true,
        )
    }

    private fun observeOffRouteCandidate(candidate: Boolean, nowMs: Long) {
        if (!candidate) {
            offRouteSampleCount = 0
            lastOffRouteCandidateAtMs = null
            return
        }
        val previousAtMs = lastOffRouteCandidateAtMs
        val isConsecutive = previousAtMs != null &&
            nowMs >= previousAtMs &&
            nowMs - previousAtMs <= config.maximumOffRouteSampleGapMs.coerceAtLeast(0L)
        offRouteSampleCount = if (isConsecutive) offRouteSampleCount + 1 else 1
        lastOffRouteCandidateAtMs = nowMs
    }

    private fun updatePendingDecision(decision: RouteNavigatorUserDecision?) {
        if (pendingDecision == decision) return
        pendingDecision = decision
        decisionRevision += 1L
    }

    private fun staleArrivalDecisionUpdate(): RouteNavigatorUpdate = RouteNavigatorUpdate(
        instruction = null,
        arrived = false,
        offRoute = confirmedDeviationLatched,
        shouldReroute = false,
        reason = "arrival_confirmation_stale",
        pendingUserDecision = pendingDecision,
    )

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
            val routeDistanceToGuideM = progressDistanceM?.let { progressM ->
                guide.distanceFromStartM?.let { guideProgressM ->
                    (guideProgressM - progressM).coerceAtLeast(0.0).roundToInt()
                }
            }
            val instruction = guide.instruction?.takeIf { it.isNotBlank() }
            val text = instruction ?: routeDistanceToGuideM?.let { distanceM ->
                "TMAP 경로 기준 전방 ${distanceM}m 안내 지점까지 이동하세요."
            } ?: "다음 TMAP 안내 지점까지 이동하세요."
            return RouteInstruction(
                text = if (routeDistanceToGuideM != null && routeDistanceToGuideM > 0 && instruction != null) {
                    "TMAP 경로 기준 ${routeDistanceToGuideM}m 앞, $text"
                } else {
                    text
                },
                guideIndex = nextGuideIndex,
            )
        }
        val endpoint = route.polyline.last()
        val destination = requestedDestination ?: endpoint
        val endpointGapM = haversineMeters(endpoint.latitude, endpoint.longitude, destination.latitude, destination.longitude)
        val routeRemainingM = progressDistanceM?.let { progressM ->
            (route.summary.distanceM - progressM).coerceAtLeast(0.0).roundToInt()
        }
        val text = if (endpointGapM > config.arrivalRadiusM) {
            if (routeRemainingM == null) {
                "저장된 TMAP 경로 기준 남은 거리를 확인 중입니다."
            } else if (routeRemainingM <= maxOf(20, location.accuracyM.roundToInt())) {
                "TMAP 경로 종점입니다. 요청한 목적지의 최종 접근을 확인하세요."
            } else {
                "저장된 TMAP 경로 기준 종점까지 약 ${routeRemainingM}m 남았습니다."
            }
        } else {
            routeRemainingM?.let { remainingM ->
                "저장된 TMAP 경로 기준 목적지까지 약 ${remainingM}m 남았습니다."
            } ?: "저장된 TMAP 경로 기준 남은 거리를 확인 중입니다."
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

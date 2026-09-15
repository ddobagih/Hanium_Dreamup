package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteCorridorMatchResult
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteCorridorMatcher
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteCorridorMatcherConfig
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteHeadingEstimate
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchQuality
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchReason
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
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
    val speechRetryIntervalMs: Long = 2_000L,
    val periodicGuidanceIntervalMs: Long = 10_000L,
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
    val speechCueToken: RouteSpeechCueToken? = null,
    val routeAlignmentDiagnostic: RouteAlignmentDiagnostic? = null,
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
    private val allowDegradedRouteGuidance: Boolean = false,
) {
    private val routeAlignmentSelector = RouteAlignmentSelector()
    private val routeCorridorMatcher = RouteCorridorMatcher(
        RouteCorridorMatcherConfig(
            progressSigmaM = config.maximumProgressAdvanceM.coerceAtLeast(1.0),
            allowReverseTravel = true,
        ),
    )
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
    private var arrivalRecheckArmed = true
    private var arrivalDepartureSampleCount = 0
    private var arrivalDepartureSampleAtMs: Long? = null
    private var pendingDecision: RouteNavigatorUserDecision? = null
    private var offRouteGuidanceSuspended = false
    private var deviationSuspectedLatched = false
    private var confirmedDeviationLatched = false
    private var locationRecheckAuthorized = false
    private var rerouteApprovedForCurrentDeviation = false
    private var progressDistanceM: Double? = null
    private var rewindGuideIndex: Int? = null
    private var rewindSampleCount = 0
    private var rewindSampleAtMs: Long? = null
    private var matchedGeometricProgressM: Double? = null
    private var latestRouteMatch: RouteCorridorMatchResult? = null
    private var latestRouteMatchUsableForGuidance = false
    // Speech-only test projection must never become accepted evidence for other consumers.
    private var latestGuidanceSourceAllowed = false
    private var latestLocationSample: RouteLocationSample? = null
    private var latestRouteMatchInputElapsedRealtimeMs: Long? = null
    private var latestRouteMatchRouteRevision: Long? = null
    private var positioningEvidenceInterrupted = false
    private var previousFilteredPosition: FilteredRoutePosition? = null
    private var latestUpdateNowMs: Long? = null
    private var latestRouteBearingDeg: Float? = null
    private var guideProgressDistancesM: List<Double?> = emptyList()
    private var cueRevision = 0L
    private var offeredSpeechCue: RouteSpeechCueToken? = null
    private var offeredFacingMessage: String? = null
    private var offeredPositionEstimateUncertain: Boolean? = null
    private var pendingSpeechCue: RouteSpeechCueToken? = null
    private var failedSpeechCue: RouteSpeechCueToken? = null
    private var completedSpeechCue: RouteSpeechCueToken? = null
    private val completedSpeechCues = mutableSetOf<Pair<Int?, Int>>()
    private var speechRetryNotBeforeMs: Long? = null
    private var includeRouteStartSummary = false
    private var manualGuidancePending = false
    private var latestGuidanceContext: RouteGuidanceContext? = null

    @Synchronized
    fun setRoute(
        route: WalkingRoute,
        destination: RoutePoint? = null,
        origin: TrustedLocation? = null,
    ) {
        routeRevision += 1L
        this.route = route
        activeRouteId = route.providerRouteId?.trim()?.takeIf(String::isNotEmpty) ?: route.localRouteFingerprint()
        requestedDestination = destination
        routeAlignmentSelector.reset(origin)
        nextGuideIndex = 0
        lastGuidanceAtMs = null
        offRouteSampleCount = 0
        lastOffRouteCandidateAtMs = null
        arrivalSampleCount = 0
        arrivalRecheckArmed = true
        arrivalDepartureSampleCount = 0
        arrivalDepartureSampleAtMs = null
        resetRewindEvidence()
        updatePendingDecision(null)
        offRouteGuidanceSuspended = false
        deviationSuspectedLatched = false
        confirmedDeviationLatched = false
        locationRecheckAuthorized = false
        rerouteApprovedForCurrentDeviation = false
        progressDistanceM = null
        matchedGeometricProgressM = null
        latestRouteMatch = null
        latestRouteMatchUsableForGuidance = false
        latestGuidanceSourceAllowed = false
        latestLocationSample = null
        latestGuidanceContext = null
        positioningEvidenceInterrupted = false
        previousFilteredPosition = null
        latestUpdateNowMs = null
        routeCorridorMatcher.reset()
        latestRouteBearingDeg = null
        guideProgressDistancesM = resolveGuideProgressDistances(route)
        includeRouteStartSummary = route.guidePoints.withIndex().any { (index, guide) ->
            isDepartureGuide(guide, guideProgressDistancesM.getOrNull(index))
        }
        completedSpeechCues.clear()
        invalidateSpeechCue()
    }

    @Synchronized
    fun clear() {
        routeRevision += 1L
        route = null
        activeRouteId = null
        requestedDestination = null
        routeAlignmentSelector.reset()
        nextGuideIndex = 0
        lastGuidanceAtMs = null
        offRouteSampleCount = 0
        lastOffRouteCandidateAtMs = null
        arrivalSampleCount = 0
        arrivalRecheckArmed = true
        arrivalDepartureSampleCount = 0
        arrivalDepartureSampleAtMs = null
        resetRewindEvidence()
        updatePendingDecision(null)
        offRouteGuidanceSuspended = false
        deviationSuspectedLatched = false
        confirmedDeviationLatched = false
        locationRecheckAuthorized = false
        rerouteApprovedForCurrentDeviation = false
        progressDistanceM = null
        matchedGeometricProgressM = null
        latestRouteMatch = null
        latestRouteMatchUsableForGuidance = false
        latestGuidanceSourceAllowed = false
        latestLocationSample = null
        latestGuidanceContext = null
        positioningEvidenceInterrupted = false
        previousFilteredPosition = null
        latestUpdateNowMs = null
        routeCorridorMatcher.reset()
        latestRouteBearingDeg = null
        guideProgressDistancesM = emptyList()
        includeRouteStartSummary = false
        completedSpeechCues.clear()
        invalidateSpeechCue()
    }

    @Synchronized
    fun currentBearingDeg(): Float? {
        if (
            positioningEvidenceInterrupted ||
            (latestRouteMatch != null && !latestRouteMatchUsableForGuidance)
        ) {
            return null
        }
        val currentRoute = route ?: return null
        return latestRouteBearingDeg
            ?: currentRoute.guidePoints.getOrNull(nextGuideIndex)?.bearingDeg
            ?: currentRoute.guidePoints.firstOrNull { it.bearingDeg != null }?.bearingDeg
    }

    @Synchronized
    fun hasRoute(): Boolean = route != null

    @Synchronized
    fun pendingUserDecision(): RouteNavigatorUserDecision? = pendingDecision

    /** An untrusted fix breaks evidence; test speech can resume only from a new actual fix. */
    @Synchronized
    fun onUntrustedLocation(): RouteNavigatorUpdate? {
        routeAlignmentSelector.reset()
        resetRewindEvidence()
        arrivalDepartureSampleCount = 0
        arrivalDepartureSampleAtMs = null
        latestGuidanceContext = null
        invalidateSpeechCue()
        offRouteSampleCount = 0
        lastOffRouteCandidateAtMs = null
        arrivalSampleCount = 0
        matchedGeometricProgressM = null
        latestRouteMatch = null
        latestRouteMatchUsableForGuidance = false
        latestGuidanceSourceAllowed = false
        positioningEvidenceInterrupted = true
        previousFilteredPosition = null
        routeCorridorMatcher.reset()
        if (route == null) return null
        if (allowDegradedRouteGuidance) {
            return RouteNavigatorUpdate(
                null, false, false, false, "location_untrusted_waiting_for_fix",
                cancelStaleNavigationSpeech = true,
            )
        }
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

    /**
     * Breaks evidence that must be consecutive without changing the installed route, stable
     * progress, or any suspected/confirmed deviation latch.
     */
    @Synchronized
    fun onPositioningEvidenceInterrupted() {
        routeAlignmentSelector.reset()
        resetRewindEvidence()
        arrivalDepartureSampleCount = 0
        arrivalDepartureSampleAtMs = null
        latestGuidanceContext = null
        invalidateSpeechCue()
        offRouteSampleCount = 0
        lastOffRouteCandidateAtMs = null
        arrivalSampleCount = 0
        if (pendingDecision == RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION) {
            updatePendingDecision(null)
        }
        latestRouteMatchUsableForGuidance = false
        latestGuidanceSourceAllowed = false
        positioningEvidenceInterrupted = true
        previousFilteredPosition = null
        routeCorridorMatcher.onPositioningEvidenceInterrupted()
    }

    @Synchronized
    fun pendingDecisionToken(): RouteNavigatorDecisionToken? {
        val decision = pendingDecision ?: return null
        val routeId = activeRouteId ?: return null
        return RouteNavigatorDecisionToken(routeRevision, decisionRevision, routeId, decision)
    }

    @Synchronized
    fun currentRouteId(): String? = activeRouteId

    /** Latest route-corridor evidence. Its filtered and matched coordinates remain separate. */
    @Synchronized
    fun currentRouteMatch(): RouteCorridorMatchResult? = latestRouteMatch

    @Synchronized
    fun currentAcceptedRouteMatchFor(
        inputElapsedRealtimeMs: Long,
    ): RouteCorridorMatchResult? {
        if (
            inputElapsedRealtimeMs < 0L ||
            positioningEvidenceInterrupted ||
            !latestRouteMatchUsableForGuidance ||
            latestRouteMatchInputElapsedRealtimeMs != inputElapsedRealtimeMs ||
            latestRouteMatchRouteRevision != routeRevision
        ) return null
        return latestRouteMatch
    }

    @Synchronized
    fun currentProjection(location: TrustedLocation): ActiveRouteProjection? {
        if (
            positioningEvidenceInterrupted ||
            (latestRouteMatch != null && !latestRouteMatchUsableForGuidance)
        ) {
            return null
        }
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
        if (allowDegradedRouteGuidance) {
            if (!latestGuidanceSourceAllowed ||
                !location.hasCurrentCoordinates(latestUpdateNowMs ?: return null)
            ) return null
        } else if (
            positioningEvidenceInterrupted ||
            (latestRouteMatch != null && !latestRouteMatchUsableForGuidance)
        ) {
            return null
        }
        if (offRouteGuidanceSuspended || pendingDecision != null) return null
        return instructionForCurrentGuide(currentRoute, location).text
    }

    /** Reserves a current cue before dispatch; reservation is not successful delivery. */
    @Synchronized
    fun reserveInstruction(update: RouteNavigatorUpdate): Boolean {
        val token = update.speechCueToken ?: return false
        if (update.instruction == null || token != offeredSpeechCue || token == completedSpeechCue ||
            pendingSpeechCue != null
        ) return false
        if (token.routeRevision != routeRevision || !canDeliverRouteGuidance()
        ) return false
        pendingSpeechCue = token
        return true
    }

    /** A rejected, interrupted or failed utterance remains eligible after a short retry delay. */
    @Synchronized
    fun releaseInstruction(update: RouteNavigatorUpdate, failedAtMs: Long) {
        val token = update.speechCueToken ?: return
        if (token != pendingSpeechCue) return
        pendingSpeechCue = null
        failedSpeechCue = token
        speechRetryNotBeforeMs = failedAtMs + config.speechRetryIntervalMs.coerceAtLeast(0L)
    }

    /** Only the actual terminal completion consumes a cue, never an enqueue result. */
    @Synchronized
    fun acknowledgeInstruction(update: RouteNavigatorUpdate, spokenAtMs: Long) {
        if (update.instruction == null) return
        if (update.reason != "route_guidance") {
            lastGuidanceAtMs = spokenAtMs
            return
        }
        val token = update.speechCueToken ?: return
        if (token != offeredSpeechCue || token == failedSpeechCue || token == completedSpeechCue ||
            token.routeRevision != routeRevision ||
            !canDeliverRouteGuidance()
        ) return
        completedSpeechCue = token
        completedSpeechCues += token.guideIndex to token.distanceBand
        if (pendingSpeechCue == token) pendingSpeechCue = null
        speechRetryNotBeforeMs = null
        manualGuidancePending = false
        lastGuidanceAtMs = spokenAtMs
        includeRouteStartSummary = false
    }

    @Synchronized
    fun acknowledgeCurrentInstruction(spokenAtMs: Long) {
        if (route == null || offRouteGuidanceSuspended ||
            (allowDegradedRouteGuidance && !latestGuidanceSourceAllowed) ||
            (!allowDegradedRouteGuidance && positioningEvidenceInterrupted)
        ) return
        lastGuidanceAtMs = spokenAtMs
    }

    private fun canDeliverRouteGuidance(): Boolean =
        latestGuidanceSourceAllowed &&
            (allowDegradedRouteGuidance || !positioningEvidenceInterrupted) &&
            !offRouteGuidanceSuspended && pendingDecision == null

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
        arrivalRecheckArmed = false
        arrivalDepartureSampleCount = 0
        arrivalDepartureSampleAtMs = null
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
    ): RouteNavigatorUpdate = update(location, nowMs, requestInFlight, stepProgressM, facingObservation = null)

    @Synchronized
    fun update(
        location: TrustedLocation,
        nowMs: Long,
        requestInFlight: Boolean,
        stepProgressM: Double? = null,
        facingObservation: RouteFacingObservation?,
    ): RouteNavigatorUpdate {
        return updateInternal(
            location = location,
            nowMs = nowMs,
            requestInFlight = requestInFlight,
            stepProgressM = stepProgressM,
            filteredPosition = null,
            facingObservation = facingObservation,
        )
    }

    @Synchronized
    fun update(
        location: TrustedLocation,
        nowMs: Long,
        requestInFlight: Boolean,
        filteredPosition: FilteredRoutePosition,
        stepProgressM: Double? = null,
        facingObservation: RouteFacingObservation? = null,
    ): RouteNavigatorUpdate {
        return updateInternal(
            location = location,
            nowMs = nowMs,
            requestInFlight = requestInFlight,
            stepProgressM = stepProgressM,
            filteredPosition = filteredPosition,
            facingObservation = facingObservation,
        )
    }

    private fun updateInternal(
        location: TrustedLocation,
        nowMs: Long,
        requestInFlight: Boolean,
        stepProgressM: Double?,
        filteredPosition: FilteredRoutePosition?,
        facingObservation: RouteFacingObservation?,
    ): RouteNavigatorUpdate {
        val currentRoute = route ?: return RouteNavigatorUpdate(null, false, false, false, "route_missing")
        val routeEndpoint = currentRoute.polyline.lastOrNull()
            ?: return RouteNavigatorUpdate(null, false, false, false, "polyline_missing")
        val unsnappedPosition = filteredPosition ?: FilteredRoutePosition(
            point = RoutePoint(location.latitude, location.longitude),
            horizontalAccuracyM = location.accuracyM.toDouble(),
            elapsedRealtimeMs = location.elapsedRealtimeMs,
        )
        val sample = RouteLocationSample(location, unsnappedPosition)
        val latestUpdateTime = latestUpdateNowMs
        val previousSample = latestLocationSample
        val timeRegressed = latestUpdateTime != null && nowMs < latestUpdateTime
        val currentSample = sample.hasCurrentCoordinates(nowMs)
        if (!timeRegressed && currentSample && sample == previousSample) {
            // A repeated observation may retry speech, but must not become a new position,
            // arrival/deviation confirmation, or reason to stop an in-progress sentence.
            return retryGuidance(nowMs, facingObservation) ?: RouteNavigatorUpdate(
                null, false, confirmedDeviationLatched, false, "location_sample_duplicate",
                pendingUserDecision = pendingDecision,
            )
        }
        latestGuidanceContext = null
        if (!timeRegressed && !currentSample) {
            onPositioningEvidenceInterrupted()
            return RouteNavigatorUpdate(
                null, false, confirmedDeviationLatched, false, "location_sample_invalid_or_stale",
                cancelStaleNavigationSpeech = true,
                pendingUserDecision = pendingDecision,
            )
        }
        val sampleNotNewer = previousSample != null && (
            unsnappedPosition.elapsedRealtimeMs <= previousSample.filteredPosition.elapsedRealtimeMs ||
                location.elapsedRealtimeMs < previousSample.rawLocation.elapsedRealtimeMs ||
                (location.elapsedRealtimeMs == previousSample.rawLocation.elapsedRealtimeMs && location != previousSample.rawLocation)
            )
        if (timeRegressed || (latestUpdateTime != null && nowMs == latestUpdateTime) || sampleNotNewer) {
            routeAlignmentSelector.reset()
            resetRewindEvidence()
            arrivalDepartureSampleCount = 0
            arrivalDepartureSampleAtMs = null
            latestRouteMatchUsableForGuidance = false
            latestGuidanceSourceAllowed = false
            invalidateSpeechCue()
            return RouteNavigatorUpdate(
                instruction = null,
                arrived = false,
                offRoute = confirmedDeviationLatched,
                shouldReroute = false,
                reason = "location_sample_not_newer",
                cancelStaleNavigationSpeech = true,
                pendingUserDecision = pendingDecision,
            )
        }
        latestLocationSample = sample
        latestUpdateNowMs = nowMs
        val destination = requestedDestination ?: routeEndpoint
        val matcherPosition = unsnappedPosition
            .withMovementHeading(previousFilteredPosition)
            .copy(elapsedRealtimeMs = nowMs)
        previousFilteredPosition = unsnappedPosition
        val routeMatch = routeCorridorMatcher.match(
            routeId = activeRouteId ?: currentRoute.localRouteFingerprint(),
            polyline = currentRoute.polyline,
            position = matcherPosition,
            previousProgressM = matchedGeometricProgressM,
        )
        latestRouteMatch = routeMatch
        latestRouteMatchInputElapsedRealtimeMs = unsnappedPosition.elapsedRealtimeMs
        latestRouteMatchRouteRevision = routeRevision
        val acceptedMatch = routeMatch.takeIf {
            it.quality == RouteMatchQuality.HIGH || it.quality == RouteMatchQuality.MEDIUM
        }
        latestRouteMatchUsableForGuidance = acceptedMatch != null
        if (acceptedMatch != null) positioningEvidenceInterrupted = false
        val fallbackProjection = if (routeMatch.crossTrackDistanceM == null) {
            projectToRoute(
                location = location,
                route = currentRoute,
                previousDistanceFromStartM = progressDistanceM,
                maximumBacktrackM = config.maximumProgressBacktrackM,
                maximumAdvanceM = config.maximumProgressAdvanceM,
            )
        } else {
            null
        }
        val distanceToRouteM = routeMatch.crossTrackDistanceM
            ?: fallbackProjection?.distanceToRouteM
            ?: distanceToPolylineMeters(location, currentRoute.polyline)
        val acceptedGeometricProgressM = acceptedMatch?.geometricProgressM
        val testGuidanceProjection = if (allowDegradedRouteGuidance && acceptedMatch == null &&
            routeMatch.reason !in setOf(
                RouteMatchReason.INVALID_INPUT, RouteMatchReason.INVALID_ROUTE, RouteMatchReason.STALE_SAMPLE,
            )
        ) {
            projectToRoute(
                location = TrustedLocation(
                    unsnappedPosition.point.latitude,
                    unsnappedPosition.point.longitude,
                    unsnappedPosition.horizontalAccuracyM.toFloat(),
                    unsnappedPosition.elapsedRealtimeMs,
                ),
                route = currentRoute,
                previousDistanceFromStartM = progressDistanceM,
                maximumBacktrackM = config.maximumProgressAdvanceM,
                maximumAdvanceM = config.maximumProgressAdvanceM,
                continuityDistanceToleranceM = 0.0,
            )
        } else null
        val ambiguousGuidanceEstimate = testGuidanceProjection != null && routeMatch.reason in setOf(
            RouteMatchReason.AMBIGUOUS_CANDIDATES, RouteMatchReason.ACTIVE_BRANCH_RETAINED,
            RouteMatchReason.BRANCH_SWITCH_PENDING,
        )
        latestGuidanceSourceAllowed = acceptedMatch != null || testGuidanceProjection != null
        if (acceptedGeometricProgressM != null) {
            matchedGeometricProgressM = acceptedGeometricProgressM
            latestRouteBearingDeg = acceptedMatch.bearingDeg
        }
        val guidanceProgressM = acceptedGeometricProgressM?.let {
            scaleGeometricProgressToRouteSummary(
                route = currentRoute,
                geometricProgressM = it,
            )
        } ?: testGuidanceProjection?.distanceFromStartM
        if (guidanceProgressM != null) {
            val previousProgress = progressDistanceM
            val boundedProgress = if (previousProgress == null) {
                guidanceProgressM
            } else {
                guidanceProgressM.coerceAtMost(previousProgress + config.maximumProgressAdvanceM)
            }
            // Remaining distance describes the current position, not the furthest point ever
            // reached. Passed-guide evidence is maintained separately with rewind hysteresis.
            progressDistanceM = boundedProgress
        }
        val guidanceLocation = acceptedMatch?.matchedPoint?.let { matchedPoint ->
            TrustedLocation(
                latitude = matchedPoint.latitude,
                longitude = matchedPoint.longitude,
                accuracyM = unsnappedPosition.horizontalAccuracyM.toFloat(),
                elapsedRealtimeMs = unsnappedPosition.elapsedRealtimeMs,
            )
        } ?: TrustedLocation(
            latitude = unsnappedPosition.point.latitude,
            longitude = unsnappedPosition.point.longitude,
            accuracyM = unsnappedPosition.horizontalAccuracyM.toFloat(),
            elapsedRealtimeMs = unsnappedPosition.elapsedRealtimeMs,
        )
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
        observeArrivalDeparture(location, unsnappedPosition, destination)
        if (arrivalDepartureSampleCount >= 2) {
            arrivalRecheckArmed = true
            if (pendingDecision == RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION) {
                updatePendingDecision(null)
                arrivalSampleCount = 0
            }
        }
        val arrivalEvidence = authoritativeArrivalEvidence && acceptedMatch != null && arrivalRecheckArmed
        val offRouteCandidate = distanceToRouteM - unsnappedPosition.horizontalAccuracyM > config.offRouteDistanceM
        if (!allowDegradedRouteGuidance) {
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
        } else {
            // Keep diagnostic observations without requiring a decision or claiming arrival.
            arrivalSampleCount = if (arrivalEvidence) arrivalSampleCount + 1 else 0
            observeOffRouteCandidate(offRouteCandidate, nowMs)
        }
        val diagnosticOffRoute = allowDegradedRouteGuidance &&
            offRouteSampleCount >= config.offRouteConfirmSamples.coerceAtLeast(1)

        if (!latestGuidanceSourceAllowed) {
            resetRewindEvidence()
            invalidateSpeechCue()
            return RouteNavigatorUpdate(
                instruction = null,
                arrived = false,
                offRoute = false,
                shouldReroute = false,
                reason = "route_match_untrusted",
                cancelStaleNavigationSpeech = true,
            )
        }

        val guideRestored = restorePassedGuides(
            currentRoute, guidanceLocation, progressDistanceM,
            minOf(location.elapsedRealtimeMs, unsnappedPosition.elapsedRealtimeMs),
        )
        val guideAdvanced = advancePassedGuides(currentRoute, guidanceLocation, progressDistanceM) || guideRestored
        val cancelPassedSpeech = guideAdvanced && pendingSpeechCue != null
        if (guideAdvanced) invalidateSpeechCue()
        val guideInstruction = instructionForCurrentGuide(currentRoute, guidanceLocation)
        val context = RouteGuidanceContext(
            routeRevision = routeRevision,
            rawLocation = location,
            guidanceLocation = guidanceLocation,
            instruction = guideInstruction,
            alignment = routeAlignmentSelector.select(currentRoute, unsnappedPosition, routeMatch, nowMs, config.offRouteDistanceM),
            positionEstimateUncertain = ambiguousGuidanceEstimate,
            offRoute = diagnosticOffRoute,
            stepProgressConsistent = stepProgressConsistent,
        )
        latestGuidanceContext = context
        return offerGuidance(currentRoute, context, nowMs, facingObservation, guideAdvanced, cancelPassedSpeech)
    }

    /** Retries delivery from existing evidence without observing another position or advancing it. */
    @Synchronized
    fun retryGuidance(nowMs: Long, facingObservation: RouteFacingObservation? = null): RouteNavigatorUpdate? {
        val currentRoute = route ?: return null
        val context = currentGuidanceContext(nowMs) ?: return null
        return offerGuidance(currentRoute, context, nowMs, facingObservation)
    }

    /** A user request bypasses only completed-cue cadence, never evidence or speech ownership. */
    @Synchronized
    fun currentGuidance(nowMs: Long, facingObservation: RouteFacingObservation? = null): RouteNavigatorUpdate? {
        val currentRoute = route ?: return null
        val context = currentGuidanceContext(nowMs) ?: return null
        return offerGuidance(currentRoute, context, nowMs, facingObservation, forceRepeat = true)
    }

    private fun currentGuidanceContext(nowMs: Long): RouteGuidanceContext? {
        val currentRoute = route ?: return null
        val context = latestGuidanceContext ?: return null
        val currentGuideIndex = currentRoute.guidePoints.getOrNull(nextGuideIndex)?.let { nextGuideIndex }
        if (context.routeRevision != routeRevision || context.instruction.guideIndex != currentGuideIndex ||
            !canDeliverRouteGuidance() || latestUpdateNowMs?.let { nowMs < it } == true ||
            !context.rawLocation.hasCurrentCoordinates(nowMs) || !context.guidanceLocation.hasCurrentCoordinates(nowMs)
        ) {
            latestGuidanceContext = null
            invalidateSpeechCue()
            return null
        }
        return context
    }

    private fun offerGuidance(
        currentRoute: WalkingRoute,
        context: RouteGuidanceContext,
        nowMs: Long,
        facingObservation: RouteFacingObservation?,
        guideAdvanced: Boolean = false,
        cancelPassedSpeech: Boolean = false,
        forceRepeat: Boolean = false,
    ): RouteNavigatorUpdate {
        val guideInstruction = context.instruction
        // Position/order selects the target. Current compass renders it, including stationary
        // manual requests and retries; it must never choose the route branch.
        val facing = RouteFacingGuidance.evaluate(context.alignment.bearingDegrees, facingObservation, nowMs)
        val alignmentDiagnostic = context.alignment.diagnostic
        val facingMessage = facingInstruction(
            facing.direction, RouteFacingGuidance.isUsableObservation(facingObservation, nowMs),
            context.alignment.bearingDegrees != null,
            facing.clockHour, alignmentDiagnostic.reason,
        )
        val distanceBand = routeGuidanceDistanceBand(guideInstruction.distanceM)
        val cueKey = guideInstruction.guideIndex to distanceBand
        if (pendingSpeechCue?.let {
                it.routeRevision == context.routeRevision && it.guideIndex == guideInstruction.guideIndex
            } == true
        ) {
            return RouteNavigatorUpdate(null, false, context.offRoute, false, "guidance_in_flight", routeAlignmentDiagnostic = alignmentDiagnostic)
        }
        val previousCue = offeredSpeechCue
        if (previousCue?.guideIndex == guideInstruction.guideIndex &&
            speechRetryNotBeforeMs?.let { nowMs < it } == true
        ) {
            return RouteNavigatorUpdate(null, false, context.offRoute, false, "guidance_retry_wait", routeAlignmentDiagnostic = alignmentDiagnostic)
        }
        val sameCue = previousCue != null && previousCue.guideIndex == guideInstruction.guideIndex &&
            previousCue.distanceBand == distanceBand && offeredFacingMessage == facingMessage &&
            offeredPositionEstimateUncertain == context.positionEstimateUncertain
        val requestedRepeat = forceRepeat ||
            (manualGuidancePending && previousCue?.guideIndex == guideInstruction.guideIndex)
        val cancelChangedSpeech = !sameCue && pendingSpeechCue != null
        if (!sameCue) invalidateSpeechCue()
        manualGuidancePending = requestedRepeat
        val cancelStaleSpeech = cancelPassedSpeech || cancelChangedSpeech
        fun noInstruction(reason: String) = RouteNavigatorUpdate(
            null, false, context.offRoute, false, reason,
            cancelStaleNavigationSpeech = cancelStaleSpeech,
            routeAlignmentDiagnostic = alignmentDiagnostic,
        )
        if (pendingSpeechCue != null) return noInstruction("guidance_in_flight")
        val lastGuidance = lastGuidanceAtMs
        // The cadence begins after delivery finishes, and never replaces an in-flight sentence.
        val periodicGuidanceDue = lastGuidance != null &&
            nowMs - lastGuidance >= config.periodicGuidanceIntervalMs
        if (lastGuidance != null && nowMs - lastGuidance < config.guidanceIntervalMs &&
            !periodicGuidanceDue && !manualGuidancePending && !guideAdvanced && !(distanceBand in 0..1 && !sameCue)
        ) return noInstruction("guidance_rate_limited")
        if (cueKey in completedSpeechCues && !periodicGuidanceDue && !manualGuidancePending) {
            return noInstruction("guidance_already_completed")
        }
        if (speechRetryNotBeforeMs?.let { nowMs < it } == true) {
            return noInstruction("guidance_retry_wait")
        }
        val token = offeredSpeechCue?.takeUnless { it == failedSpeechCue || it == completedSpeechCue } ?: RouteSpeechCueToken(
            routeRevision = routeRevision,
            cueRevision = ++cueRevision,
            guideIndex = guideInstruction.guideIndex,
            distanceBand = distanceBand,
        ).also { offeredSpeechCue = it }
        offeredFacingMessage = facingMessage
        offeredPositionEstimateUncertain = context.positionEstimateUncertain
        val summary = if (includeRouteStartSummary) {
            "길안내를 시작합니다. 전체 경로는 약 ${currentRoute.summary.distanceM}m입니다. "
        } else ""
        val positionNotice = if (context.positionEstimateUncertain) {
            "현재 경로 위치가 불확실하여 남은 거리와 안내 지점을 추정합니다. "
        } else ""
        return RouteNavigatorUpdate(
            instruction = summary + positionNotice + facingMessage + guideInstruction.text,
            arrived = false,
            offRoute = context.offRoute,
            shouldReroute = false,
            reason = "route_guidance",
            guideIndex = guideInstruction.guideIndex,
            stepProgressConsistent = context.stepProgressConsistent,
            cancelStaleNavigationSpeech = cancelStaleSpeech,
            speechCueToken = token,
            routeAlignmentDiagnostic = alignmentDiagnostic,
        )
    }

    private fun arrivalConfirmationRequiredUpdate(stepProgressConsistent: Boolean?): RouteNavigatorUpdate {
        return RouteNavigatorUpdate(
            instruction = "도착 후보입니다. 실제로 도착했다면 확인하고, 아니면 거절해 주세요.",
            arrived = false,
            offRoute = false,
            shouldReroute = false,
            reason = "arrival_confirmation_required",
            cancelStaleNavigationSpeech = true,
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
        if (decision != null) {
            latestGuidanceContext = null
            invalidateSpeechCue()
        }
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

    private fun invalidateSpeechCue() {
        offeredSpeechCue = null
        offeredFacingMessage = null
        offeredPositionEstimateUncertain = null
        pendingSpeechCue = null
        failedSpeechCue = null
        completedSpeechCue = null
        speechRetryNotBeforeMs = null
        manualGuidancePending = false
    }

    private fun facingInstruction(
        direction: RouteFacingDirection,
        compassAvailable: Boolean,
        routeBearingAvailable: Boolean,
        clockHour: Int?,
        alignmentReason: RouteAlignmentReason,
    ): String {
        val clock = clockHour?.let { "약 ${it}시 방향" }
        if (clock != null && alignmentReason in setOf(RouteAlignmentReason.DEPARTURE_SEGMENT, RouteAlignmentReason.DEPARTURE_CONNECTOR)) {
            val subject = if (alignmentReason == RouteAlignmentReason.DEPARTURE_CONNECTOR) "경로 시작점은" else "출발 경로는"
            val align = when (direction) {
                RouteFacingDirection.LEFT -> "먼저 왼쪽으로 방향을 맞추세요. "
                RouteFacingDirection.RIGHT -> "먼저 오른쪽으로 방향을 맞추세요. "
                RouteFacingDirection.BEHIND -> "몸을 돌려 경로 방향을 맞추세요. "
                else -> ""
            }
            return "$subject ${clock}입니다. $align" + "경로 기준으로 "
        }
        val clockNotice = clock?.let { "경로는 ${it}입니다. " } ?: ""
        return when (direction) {
            RouteFacingDirection.FRONT -> clockNotice + "현재 바라보는 앞쪽이 경로 진행 방향입니다. 경로 기준으로 "
            RouteFacingDirection.LEFT -> clockNotice + "먼저 왼쪽 경로 방향으로 몸을 돌리세요. 경로 기준으로 "
            RouteFacingDirection.RIGHT -> clockNotice + "먼저 오른쪽 경로 방향으로 몸을 돌리세요. 경로 기준으로 "
            RouteFacingDirection.BEHIND -> clockNotice + "경로 진행 방향이 뒤쪽입니다. 경로 방향으로 몸을 돌리세요. 경로 기준으로 "
            RouteFacingDirection.UNKNOWN -> when {
                !routeBearingAvailable -> "현재 위치에서 경로의 진행 방향을 정확히 구분하기 어렵습니다. 다음은 경로 기준 안내입니다. "
                compassAvailable -> "나침반 방향은 측정되지만 앞뒤와 좌우를 확실히 구분하기 어렵습니다. 다음은 경로 기준 안내입니다. "
                else -> "현재 바라보는 방향을 확인할 수 없습니다. 다음은 경로 기준 안내입니다. "
            }
        }
    }

    private fun resetRewindEvidence() {
        rewindGuideIndex = null
        rewindSampleCount = 0
        rewindSampleAtMs = null
    }

    private fun observeArrivalDeparture(raw: TrustedLocation, filtered: FilteredRoutePosition, destination: RoutePoint) {
        val rawDistanceM = haversineMeters(raw.latitude, raw.longitude, destination.latitude, destination.longitude)
        val filteredDistanceM = haversineMeters(
            filtered.point.latitude, filtered.point.longitude, destination.latitude, destination.longitude,
        )
        // Leaving the destination area does not require a successful match to the old route.
        // Both original and filtered observations must independently support that departure.
        val outside = raw.accuracyM <= config.arrivalMaxAccuracyM &&
            filtered.horizontalAccuracyM <= config.arrivalMaxAccuracyM &&
            rawDistanceM - raw.accuracyM > config.arrivalRadiusM + 5.0 &&
            filteredDistanceM - filtered.horizontalAccuracyM > config.arrivalRadiusM + 5.0
        if (!outside) {
            arrivalDepartureSampleCount = 0
            arrivalDepartureSampleAtMs = null
            return
        }
        val sourceAtMs = minOf(raw.elapsedRealtimeMs, filtered.elapsedRealtimeMs)
        val previousAtMs = arrivalDepartureSampleAtMs
        if (previousAtMs != null && sourceAtMs <= previousAtMs) return
        arrivalDepartureSampleCount = if (previousAtMs != null &&
            sourceAtMs - previousAtMs <= config.maximumOffRouteSampleGapMs
        ) arrivalDepartureSampleCount + 1 else 1
        arrivalDepartureSampleAtMs = sourceAtMs
    }

    /** A single displaced fix may change distance, but cannot restore a previously passed turn. */
    private fun restorePassedGuides(route: WalkingRoute, location: TrustedLocation, progressM: Double?, nowMs: Long): Boolean {
        if (progressM == null || location.accuracyM > LocationTrustConfig().maxAccuracyM || nextGuideIndex == 0) {
            resetRewindEvidence()
            return false
        }
        val marginM = maxOf(config.maximumProgressBacktrackM, location.accuracyM.toDouble() * 2.0, 5.0)
        val candidate = (0 until nextGuideIndex).firstOrNull { index ->
            val guide = route.guidePoints[index]
            val guideProgress = guideProgressDistancesM.getOrNull(index)
            guideProgress != null && !isDepartureGuide(guide, guideProgress) && progressM < guideProgress - marginM
        }
        if (candidate == null) {
            resetRewindEvidence()
            return false
        }
        val previousAtMs = rewindSampleAtMs
        rewindSampleCount = if (rewindGuideIndex == candidate && previousAtMs != null &&
            nowMs > previousAtMs && nowMs - previousAtMs <= config.maximumOffRouteSampleGapMs
        ) rewindSampleCount + 1 else 1
        rewindGuideIndex = candidate
        rewindSampleAtMs = nowMs
        if (rewindSampleCount < 2) return false
        nextGuideIndex = candidate
        // A reapproach is a new traversal of this guide; old approach completions must not
        // suppress its fresh maneuver. Speech tokens are retired by the caller.
        completedSpeechCues.removeAll { (index, _) -> index != null && index >= candidate }
        resetRewindEvidence()
        return true
    }

    private fun resolveGuideProgressDistances(route: WalkingRoute): List<Double?> {
        var previousDistanceM = 0.0
        return route.guidePoints.map { guide ->
            val distanceM = guide.distanceFromStartM?.toDouble()
                ?: guide.remainingDistanceM?.let { route.summary.distanceM - it.toDouble() }
                ?: projectToRoute(
                location = TrustedLocation(guide.point.latitude, guide.point.longitude, 0f, 0L),
                route = route,
                previousDistanceFromStartM = previousDistanceM,
                maximumBacktrackM = 0.0,
                maximumAdvanceM = Double.POSITIVE_INFINITY,
            )?.takeIf { it.distanceToRouteM <= 30.0 }?.distanceFromStartM
            // The validated backend contract allows 10 m of guide ordering/rounding noise.
            // Keep that accepted point traversable instead of replacing its progress with null.
            distanceM?.coerceAtLeast(previousDistanceM)?.also { previousDistanceM = it }
        }
    }

    private fun isDepartureGuide(guide: WalkingRouteGuidePoint, progressM: Double?): Boolean =
        (guide.pointType.equals("SP", ignoreCase = true) || guide.turnType == 200) &&
            progressM != null && progressM <= 15.0

    /** Location progress consumes passed points even if their old utterance was suppressed. */
    private fun advancePassedGuides(
        route: WalkingRoute,
        location: TrustedLocation,
        progressM: Double?,
    ): Boolean {
        val previousIndex = nextGuideIndex
        while (true) {
            val guide = route.guidePoints.getOrNull(nextGuideIndex) ?: break
            val guideProgressM = guideProgressDistancesM.getOrNull(nextGuideIndex)
            val passedMarginM = if (allowDegradedRouteGuidance) 3.0 else maxOf(3.0, location.accuracyM.toDouble())
            val passed = progressM != null && guideProgressM != null &&
                progressM >= guideProgressM + passedMarginM
            if (!isDepartureGuide(guide, guideProgressM) && !passed) break
            nextGuideIndex += 1
        }
        return previousIndex != nextGuideIndex
    }

    private fun instructionForCurrentGuide(route: WalkingRoute, location: TrustedLocation): RouteInstruction {
        val guide = route.guidePoints.getOrNull(nextGuideIndex)
        if (guide != null) {
            val routeDistanceToGuideM = progressDistanceM?.let { progressM ->
                guideProgressDistancesM.getOrNull(nextGuideIndex)?.let { guideProgressM ->
                    (guideProgressM - progressM).coerceAtLeast(0.0).roundToInt()
                }
            }
            CrosswalkReferencePolicy.noticeFor(guide)?.let { notice ->
                // Only mapped ordinary maneuvers survive a crossing marker, never provider
                // crossing commands or signal-based permission to cross.
                val maneuver = guide.takeIf { it.turnType in setOf(11, 12, 13, 14, 16, 17, 18, 19) }
                    ?.maneuverInstruction() ?: "횡단보도 안내 지점입니다."
                val prefix = routeDistanceToGuideM?.let { "${it}m 앞, " } ?: ""
                return RouteInstruction(
                    text = "$prefix$maneuver $notice",
                    guideIndex = nextGuideIndex,
                    distanceM = routeDistanceToGuideM,
                )
            }
            val instruction = guide.maneuverInstruction()
            val text = instruction ?: routeDistanceToGuideM?.let { distanceM ->
                "전방 ${distanceM}m 안내 지점까지 이동하세요."
            } ?: "다음 안내 지점까지 이동하세요."
            return RouteInstruction(
                text = if (routeDistanceToGuideM != null && routeDistanceToGuideM > 0 && instruction != null) {
                    "${routeDistanceToGuideM}m 앞, $text"
                } else {
                    text
                },
                guideIndex = nextGuideIndex,
                distanceM = routeDistanceToGuideM,
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
                "남은 거리를 확인 중입니다."
            } else if (routeRemainingM <= maxOf(20, location.accuracyM.roundToInt())) {
                "경로 종점입니다. 요청한 목적지의 최종 접근을 확인하세요."
            } else {
                "종점까지 약 ${routeRemainingM}m 남았습니다."
            }
        } else {
            routeRemainingM?.let { remainingM ->
                "목적지까지 약 ${remainingM}m 남았습니다."
            } ?: "남은 거리를 확인 중입니다."
        }
        return RouteInstruction(text = text, guideIndex = null, distanceM = routeRemainingM)
    }
}

private fun TrustedLocation.hasCurrentCoordinates(nowMs: Long): Boolean =
    latitude.isFinite() && latitude in -90.0..90.0 &&
        longitude.isFinite() && longitude in -180.0..180.0 &&
        accuracyM.isFinite() && accuracyM >= 0f && elapsedRealtimeMs >= 0L &&
        nowMs >= elapsedRealtimeMs && nowMs - elapsedRealtimeMs <= LocationTrustConfig().maxAgeMs

private data class RouteInstruction(
    val text: String,
    val guideIndex: Int?,
    val distanceM: Int? = null,
)

private data class RouteGuidanceContext(
    val routeRevision: Long,
    val rawLocation: TrustedLocation,
    val guidanceLocation: TrustedLocation,
    val instruction: RouteInstruction,
    val alignment: RouteAlignmentSelection,
    val positionEstimateUncertain: Boolean,
    val offRoute: Boolean,
    val stepProgressConsistent: Boolean?,
)

private data class RouteLocationSample(
    val rawLocation: TrustedLocation,
    val filteredPosition: FilteredRoutePosition,
) {
    fun hasCurrentCoordinates(nowMs: Long): Boolean = rawLocation.hasCurrentCoordinates(nowMs) &&
        TrustedLocation(
            filteredPosition.point.latitude,
            filteredPosition.point.longitude,
            filteredPosition.horizontalAccuracyM.toFloat(),
            filteredPosition.elapsedRealtimeMs,
        ).hasCurrentCoordinates(nowMs)
}

private data class RouteProjection(
    val distanceToRouteM: Double,
    val distanceFromStartM: Double,
    val bearingDeg: Float,
    val segmentIndex: Int,
    val segmentStart: RoutePoint,
    val segmentEnd: RoutePoint,
)

private fun scaleGeometricProgressToRouteSummary(
    route: WalkingRoute,
    geometricProgressM: Double,
): Double {
    val geometricTotalM = route.polyline.zipWithNext().sumOf { (start, end) ->
        haversineMeters(start.latitude, start.longitude, end.latitude, end.longitude)
    }
    if (geometricTotalM <= 0.0 || !geometricTotalM.isFinite()) return geometricProgressM
    val declaredDistanceM = route.summary.distanceM.toDouble()
    val scale = if (declaredDistanceM > 0.0) declaredDistanceM / geometricTotalM else 1.0
    return (geometricProgressM * scale).coerceIn(0.0, maxOf(declaredDistanceM, geometricTotalM * scale))
}

private fun FilteredRoutePosition.withMovementHeading(
    previous: FilteredRoutePosition?,
): FilteredRoutePosition {
    if (heading != null || previous == null) return this
    val movementDistanceM = haversineMeters(
        previous.point.latitude,
        previous.point.longitude,
        point.latitude,
        point.longitude,
    )
    val combinedAccuracyM = hypot(previous.horizontalAccuracyM, horizontalAccuracyM)
    if (movementDistanceM <= combinedAccuracyM || movementDistanceM <= 0.0) return this
    val headingStandardDeviationDeg = Math.toDegrees(atan2(combinedAccuracyM, movementDistanceM))
    if (!headingStandardDeviationDeg.isFinite() || headingStandardDeviationDeg > 45.0) return this
    return copy(
        heading = RouteHeadingEstimate(
            degrees = bearingDegrees(
                previous.point.latitude,
                previous.point.longitude,
                point.latitude,
                point.longitude,
            ).toDouble(),
            standardDeviationDeg = headingStandardDeviationDeg.coerceAtLeast(8.0),
        ),
    )
}

private fun projectToRoute(
    location: TrustedLocation,
    route: WalkingRoute,
    previousDistanceFromStartM: Double?,
    maximumBacktrackM: Double,
    maximumAdvanceM: Double,
    continuityDistanceToleranceM: Double = 25.0,
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
    return if (continuous != null && continuous.distanceToRouteM <= globalBest.distanceToRouteM + continuityDistanceToleranceM) {
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

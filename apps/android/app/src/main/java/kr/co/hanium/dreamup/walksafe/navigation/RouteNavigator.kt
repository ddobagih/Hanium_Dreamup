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
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

// Reject only grossly impossible turn-to-turn progress, not ordinary sparse fixes.
private const val MAX_IMPORTANT_TURN_EVIDENCE_SPEED_MPS = 80.0
// Declared guide distance usable for turn timing: the backend ordering/rounding noise bound.
// Larger (but still accepted) declared errors are replaced by the stored-shape position.
private const val TURN_GUIDE_DISTANCE_TOLERANCE_M = 10.0
// Smallest route progress beyond a guide that passes it (the whole margin in degraded guidance).
// S08-18-F2: the route bearing selector bounds its near-vertex hold with the same margin.
internal const val MINIMUM_GUIDE_PASSAGE_MARGIN_M = 3.0
// Less than one step: steps did not advance. A larger GNSS progress change is then only a claim.
private const val STATIONARY_STEP_PROGRESS_M = 0.5
private const val STATIONARY_GNSS_JUMP_MIN_M = 15.0
// S07-23: engineering default. A jump stays held while the steps do not explain it, for at most
// this long after the jump (step-counter batching); then GNSS progress is used again.
private const val STATIONARY_JUMP_HOLD_MS = 10_000L
// Used only when no recent movement was observed for a stored context (brisk walking pace).
private const val ASSUMED_CONTEXT_WALKING_SPEED_MPS = 1.5
// A just-offered cue must survive ordinary playback latency even right at a branch.
private const val MINIMUM_CONTEXT_TRAVEL_ALLOWANCE_M = 3.0
private val CLOCK_DIRECTION_REFERENCE = Regex("""(?<!\d)(?:1[0-2]|[1-9])시\s*방향""")
// S08-18-F1: the turn sentence while the bearing is held near an unpassed guide. It is relative to
// the route, like the ordinary "경로 기준으로 ..." turn, and carries no clock or facing claim.
private const val NEAR_PENDING_TURN_PREFIX = "안내 지점 부근입니다. 경로 기준으로 "
// S08-20 (nav-F9): beside the route the only action is one that does not move the user. It asks for
// guidance again ("다음 안내 알려줘"), which needs no pending decision, like the unknown-facing request.
private const val BESIDE_ROUTE_ACTION = "잠시 멈춰 주변 보행로를 확인한 뒤 다시 안내를 요청해 주세요."

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
    val decisionTimeoutMs: Long = 30_000L,
    val maximumArrivalSampleGapMs: Long = 10_000L,
    /**
     * S07-32 (R6, 07.txt:303/305): consecutive arrival evidence must also have lasted this long
     * (measurement time) before the arrival question is asked. The user still confirms the end,
     * and an explicit "arrived" request is not delayed.
     */
    val minimumArrivalDwellMs: Long = 3_000L,
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
    val directionOnly: Boolean = false,
    /** S08-34: relative angle and expression level of the facing part of this sentence. */
    val facingDiagnostic: RouteFacingSpeechDiagnostic? = null,
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
    /** Install revision of the route this projection used; a re-installed route gets a new one. */
    val routeRevision: Long? = null,
)

/**
 * Stateful GPS route progress and reroute policy. Off-route confirmation uses successive trusted
 * fixes and subtracts reported accuracy before comparing the fix with the route corridor.
 */
class RouteNavigator(
    private val config: RouteNavigatorConfig = RouteNavigatorConfig(),
    private val allowDegradedRouteGuidance: Boolean = false,
    private val nowElapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    init {
        require(config.decisionTimeoutMs > 0L)
        require(config.maximumArrivalSampleGapMs > 0L)
        require(config.minimumArrivalDwellMs >= 0L)
    }
    private val routeAlignmentSelector = RouteAlignmentSelector()
    private val routeCorridorMatcher = RouteCorridorMatcher(
        RouteCorridorMatcherConfig(
            progressSigmaM = config.maximumProgressAdvanceM.coerceAtLeast(1.0),
            allowReverseTravel = true,
            timeScaledProgressPrior = true,
        ),
    )
    // S07-15 (R7): the matcher's time-scaled progress prior terms above (its default speed bound, this floor).
    private val progressPriorSpeedBoundMps = RouteCorridorMatcherConfig().maximumTravelSpeedMps
    private val progressPriorFloorM = config.maximumProgressAdvanceM.coerceAtLeast(1.0)
    private var route: WalkingRoute? = null
    private var routeRevision = 0L
    private var decisionRevision = 0L
    private var activeRouteId: String? = null
    private var requestedDestination: RoutePoint? = null
    private var nextGuideIndex = 0
    private var passageConfirmationGuideIndex: Int? = null
    private var lastPassedImportantTurnProgressM: Double? = null
    private var lastPassedImportantTurnGuideProgressM: Double? = null
    private var lastPassedImportantTurnRawAtMs: Long? = null
    private var lastPassedImportantTurnFilteredAtMs: Long? = null
    private var passedImportantTurnEvidenceStage = 0
    private var passedImportantTurnEvidenceRawAtMs: Long? = null
    private var passedImportantTurnEvidenceFilteredAtMs: Long? = null
    private var passedImportantTurnBeyondUpperBoundM: Double? = null
    private var speechOnlyImportantTurnPassed = false
    private var lastGuidanceAtMs: Long? = null
    private var offRouteSampleCount = 0
    private var lastOffRouteCandidateAtMs: Long? = null
    private var lastOffRouteCandidateFilteredAtMs: Long? = null
    private var arrivalSampleCount = 0
    private var lastArrivalRawSampleAtMs: Long? = null
    private var lastArrivalFilteredSampleAtMs: Long? = null
    /** S07-32: measurement times of the first sample of the current consecutive arrival evidence. */
    private var firstArrivalRawSampleAtMs: Long? = null
    private var firstArrivalFilteredSampleAtMs: Long? = null
    private var arrivalRecheckArmed = true
    private var arrivalDepartureSampleCount = 0
    private var arrivalDepartureSampleAtMs: Long? = null
    private var pendingDecision: RouteNavigatorUserDecision? = null
    private var decisionOpenedAtMs: Long? = null
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
    private var offeredFacing: RouteFacingGuidanceResult? = null
    private var confirmedClockHour: Int? = null
    private var pendingClockHour: Int? = null
    private var pendingClockObservationCount = 0
    private var lastClockObservationAtMs: Long? = null
    private var offeredPositionEstimateUncertain: Boolean? = null
    private var pendingSpeechCue: RouteSpeechCueToken? = null
    private var failedSpeechCue: RouteSpeechCueToken? = null
    private var completedSpeechCue: RouteSpeechCueToken? = null
    private val completedSpeechCues = mutableSetOf<CompletedRouteSpeechCue>()
    private var speechRetryNotBeforeMs: Long? = null
    private var includeRouteStartSummary = false
    private var manualGuidancePending = false
    private var reservedManualGuidance = false
    private var latestGuidanceContext: RouteGuidanceContext? = null
    private var lastStepProgressM: Double? = null
    /** S07-23: route and step progress before a stationary GNSS jump, and when it was measured. */
    private var stationaryJumpAnchor: StationaryJumpAnchor? = null
    /** S07-15: measurement time of the accepted progress passed to the matcher as its prior reference. */
    private var lastAcceptedProgress: Pair<Double, Long>? = null
    /** S07-15 (R7): the last accepted sample came after a long gap and only realigned. */
    private var realigningAfterGap = false
    // Facing-source continuity: the previous source is comparison evidence, never the facing.
    private var facingSourceAnchor: RouteFacingSource? = null
    private var facingSourceSwitchPending = false
    private var facingConfirmationDirection: RouteFacingDirection? = null
    private var facingConfirmationCount = 0
    private var facingConfirmationObservedAtMs: Long? = null

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
        passageConfirmationGuideIndex = null
        resetPassedTurnEvidence()
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
        resetClockSector()
        resetFacingSourceGate()
        lastStepProgressM = null
        stationaryJumpAnchor = null
        lastAcceptedProgress = null
        realigningAfterGap = false
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
        resetPassedTurnEvidence()
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
        resetClockSector()
        resetFacingSourceGate()
        lastStepProgressM = null
        stationaryJumpAnchor = null
        lastAcceptedProgress = null
        realigningAfterGap = false
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
    fun pendingUserDecision(): RouteNavigatorUserDecision? {
        expirePendingDecision()
        return pendingDecision
    }

    /** An untrusted fix breaks evidence; test speech can resume only from a new actual fix. */
    @Synchronized
    fun onUntrustedLocation(): RouteNavigatorUpdate? {
        routeAlignmentSelector.reset()
        resetRewindEvidence()
        interruptPassedTurnEvidence()
        arrivalDepartureSampleCount = 0
        arrivalDepartureSampleAtMs = null
        latestGuidanceContext = null
        resetClockSector()
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
        interruptPassedTurnEvidence()
        arrivalDepartureSampleCount = 0
        arrivalDepartureSampleAtMs = null
        latestGuidanceContext = null
        resetClockSector()
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
        expirePendingDecision()
        val decision = pendingDecision ?: return null
        val routeId = activeRouteId ?: return null
        return RouteNavigatorDecisionToken(routeRevision, decisionRevision, routeId, decision)
    }

    @Synchronized
    fun currentRouteId(): String? = activeRouteId

    @Synchronized
    fun remainingDistanceM(): Double? {
        val current = route ?: return null
        if (positioningEvidenceInterrupted || !latestRouteMatchUsableForGuidance ||
            offRouteGuidanceSuspended || pendingDecision != null) return null
        return progressDistanceM?.let { (current.summary.distanceM - it).coerceAtLeast(0.0) }
    }

    /**
     * S12-12: install revision and latest accepted matched segment for tactile route checks.
     * The segment is null unless a usable match exists for this exact route revision.
     */
    @Synchronized
    fun currentTactileRouteProgress(): TactileActiveRouteProgress {
        val match = latestRouteMatch?.takeIf {
            route != null && !positioningEvidenceInterrupted && latestRouteMatchUsableForGuidance &&
                latestRouteMatchRouteRevision == routeRevision
        }
        return TactileActiveRouteProgress(
            routeVersion = routeRevision.takeIf { route != null },
            segmentIndex = match?.segmentIndex,
        )
    }

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
            routeRevision = routeRevision,
        )
    }

    @Synchronized
    fun currentInstruction(location: TrustedLocation): String? {
        val currentRoute = route ?: return null
        if (latestGuidanceContext?.directionOnly == true) return null
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
        // Consume the request being dispatched, not a new request made during its playback.
        reservedManualGuidance = manualGuidancePending
        manualGuidancePending = false
        return true
    }

    /** A rejected, interrupted or failed utterance remains eligible after a short retry delay. */
    @Synchronized
    fun releaseInstruction(update: RouteNavigatorUpdate, failedAtMs: Long) {
        val token = update.speechCueToken ?: return
        if (token != pendingSpeechCue) return
        pendingSpeechCue = null
        // A failed delivery did not satisfy its manual request. Preserve a newer request too.
        manualGuidancePending = manualGuidancePending || reservedManualGuidance
        reservedManualGuidance = false
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
        completedSpeechCues += CompletedRouteSpeechCue(token.guideIndex, token.distanceBand, token.directionOnly,
            offeredFacing?.direction, offeredFacing?.clockHour)
        if (pendingSpeechCue == token) {
            pendingSpeechCue = null
            reservedManualGuidance = false
        }
        speechRetryNotBeforeMs = null
        lastGuidanceAtMs = spokenAtMs
        if (!token.directionOnly) includeRouteStartSummary = false
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
        ((latestGuidanceSourceAllowed && (allowDegradedRouteGuidance || !positioningEvidenceInterrupted)) ||
            latestGuidanceContext?.directionOnly == true) &&
            !offRouteGuidanceSuspended && pendingDecision == null

    /** The output owner must call this with fresh sensor evidence immediately before playback. */
    @Synchronized
    fun isInstructionCurrent(
        update: RouteNavigatorUpdate,
        nowMs: Long,
        facingObservation: RouteFacingObservation?,
    ): Boolean {
        val token = update.speechCueToken ?: return false
        if (token != offeredSpeechCue || token == failedSpeechCue || token == completedSpeechCue ||
            token.routeRevision != routeRevision
        ) return false
        val context = currentGuidanceContext(nowMs) ?: return false
        val current = stabilizeClockSector(
            gateFacingSource(
                RouteFacingGuidance.evaluate(
                    context.alignment.bearingDegrees, facingObservation, nowMs,
                    context.alignment.bearingUncertaintyDegrees,
                ),
                facingObservation,
            ),
            facingObservation,
        )
        // A coarse cue stays true when a stable clock sector is confirmed later; a spoken number
        // must still match the current confirmed sector.
        return offeredFacing?.let {
            it.direction == current.direction && (it.clockHour == null || it.clockHour == current.clockHour)
        } == true
    }

    @Synchronized
    fun approveReroute(): RouteNavigatorUpdate =
        selectDeviationChoice(RouteDeviationChoice.NEW_ROUTE)

    @Synchronized
    fun selectDeviationChoice(choice: RouteDeviationChoice): RouteNavigatorUpdate {
        expirePendingDecision()
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
                    // R5: the user's recheck ends an unresolved stationary-jump hold.
                    stationaryJumpAnchor = null
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
    fun selectDeviationChoice(
        choice: RouteDeviationChoice,
        expectedToken: RouteNavigatorDecisionToken,
    ): RouteNavigatorUpdate {
        if (pendingDecisionToken() != expectedToken) {
            return RouteNavigatorUpdate(
                null, false, confirmedDeviationLatched, false, "route_decision_stale",
                pendingUserDecision = pendingDecision,
            )
        }
        return selectDeviationChoice(choice)
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
        expirePendingDecision()
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
        expirePendingDecision()
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

    /**
     * A user's own "arrived" request when no question is open. It opens one new confirmation
     * without re-arming the automatic question that a rejection suppressed, and only while the
     * latest fresh fix is not already outside the area that [observeArrivalDeparture] treats as
     * leaving the destination. It never ends guidance by itself.
     */
    @Synchronized
    fun requestArrivalConfirmation(nowMs: Long): RouteNavigatorUpdate {
        expirePendingDecision()
        val currentRoute = route
        if (currentRoute == null || allowDegradedRouteGuidance || pendingDecision != null ||
            confirmedDeviationLatched || deviationSuspectedLatched || offRouteGuidanceSuspended
        ) {
            return RouteNavigatorUpdate(
                null, false, confirmedDeviationLatched, false, "arrival_request_unavailable",
                pendingUserDecision = pendingDecision,
            )
        }
        val sample = latestLocationSample?.takeIf {
            !positioningEvidenceInterrupted && latestUpdateNowMs?.let { nowMs >= it } == true &&
                it.hasCurrentCoordinates(nowMs)
        } ?: return RouteNavigatorUpdate(
            instruction = "현재 위치를 확인한 뒤 다시 도착 확인을 요청해 주세요.",
            arrived = false,
            offRoute = false,
            shouldReroute = false,
            reason = "arrival_request_location_unavailable",
        )
        val destination = requestedDestination ?: currentRoute.polyline.last()
        val raw = sample.rawLocation
        val filtered = sample.filteredPosition
        val areaM = config.arrivalRadiusM + 5.0
        val maxAccuracyM = LocationTrustConfig().maxAccuracyM
        val nearDestination = raw.accuracyM <= maxAccuracyM &&
            filtered.horizontalAccuracyM <= maxAccuracyM && haversineMeters(
            raw.latitude, raw.longitude, destination.latitude, destination.longitude,
        ) - raw.accuracyM <= areaM && haversineMeters(
            filtered.point.latitude, filtered.point.longitude, destination.latitude, destination.longitude,
        ) - filtered.horizontalAccuracyM <= areaM
        if (!nearDestination) {
            return RouteNavigatorUpdate(
                instruction = "현재 위치가 목적지 근처로 확인되지 않아 도착 확인을 열지 않았습니다. " +
                    "길안내를 끝내려면 길안내 종료라고 말씀해 주세요.",
                arrived = false,
                offRoute = false,
                shouldReroute = false,
                reason = "arrival_request_not_near_destination",
            )
        }
        arrivalSampleCount = 0
        lastArrivalRawSampleAtMs = null
        lastArrivalFilteredSampleAtMs = null
        updatePendingDecision(RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION)
        val name = destination.name?.trim()?.takeIf(String::isNotEmpty)
        return RouteNavigatorUpdate(
            instruction = (name?.let { "$it 도착 확인을 요청하셨습니다. " } ?: "도착 확인을 요청하셨습니다. ") +
                // Both phrases are ARRIVAL_CONFIRM_COMMANDS / ARRIVAL_REJECT_COMMANDS entries.
                "실제로 도착했다면 도착 확인, 아니면 도착 아니야라고 말씀해 주세요.",
            arrived = false,
            offRoute = false,
            shouldReroute = false,
            reason = "arrival_confirmation_requested_by_user",
            cancelStaleNavigationSpeech = true,
            pendingUserDecision = RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION,
        )
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
        /** S08-8: why the compass gave no usable observation, for the spoken recovery step. */
        facingUnavailableReason: RouteCompassHeadingReason? = null,
    ): RouteNavigatorUpdate {
        return updateInternal(
            location = location,
            nowMs = nowMs,
            requestInFlight = requestInFlight,
            stepProgressM = stepProgressM,
            filteredPosition = null,
            facingObservation = facingObservation,
            facingUnavailableReason = facingUnavailableReason,
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
        facingUnavailableReason: RouteCompassHeadingReason? = null,
    ): RouteNavigatorUpdate {
        return updateInternal(
            location = location,
            nowMs = nowMs,
            requestInFlight = requestInFlight,
            stepProgressM = stepProgressM,
            filteredPosition = filteredPosition,
            facingObservation = facingObservation,
            facingUnavailableReason = facingUnavailableReason,
        )
    }

    private fun updateInternal(
        location: TrustedLocation,
        nowMs: Long,
        requestInFlight: Boolean,
        stepProgressM: Double?,
        filteredPosition: FilteredRoutePosition?,
        facingObservation: RouteFacingObservation?,
        facingUnavailableReason: RouteCompassHeadingReason?,
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
            onPositioningEvidenceInterrupted()
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
        previousFilteredPosition = unsnappedPosition
        val routeMatch = routeCorridorMatcher.match(
            routeId = activeRouteId ?: currentRoute.localRouteFingerprint(),
            polyline = currentRoute.polyline,
            position = matcherPosition,
            previousProgressM = matchedGeometricProgressM,
            previousProgressAtElapsedRealtimeMs = lastAcceptedProgress?.second,
            // S09-50: the matcher checks age and future time itself, with the same 10 s sample age.
            evaluatedAtElapsedRealtimeMs = nowMs,
            maximumSampleAgeMs = LocationTrustConfig().maxAgeMs,
        )
        latestRouteMatch = routeMatch
        latestRouteMatchInputElapsedRealtimeMs = unsnappedPosition.elapsedRealtimeMs
        latestRouteMatchRouteRevision = routeRevision
        val acceptedMatch = routeMatch.takeIf {
            it.quality == RouteMatchQuality.HIGH || it.quality == RouteMatchQuality.MEDIUM
        }
        if (acceptedMatch == null) interruptPassedTurnEvidence()
        latestRouteMatchUsableForGuidance = acceptedMatch != null
        if (acceptedMatch != null) positioningEvidenceInterrupted = false
        val alignment = routeAlignmentSelector.select(
            currentRoute, unsnappedPosition, routeMatch, nowMs, config.offRouteDistanceM,
        )
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
            alignment.diagnostic.reason != RouteAlignmentReason.DEPARTURE_CONNECTOR &&
            routeMatch.reason !in setOf(
                RouteMatchReason.INVALID_INPUT, RouteMatchReason.INVALID_ROUTE, RouteMatchReason.STALE_SAMPLE,
                RouteMatchReason.EXPIRED_SAMPLE, RouteMatchReason.FUTURE_SAMPLE,
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
        val previousProgressM = progressDistanceM
        // S07-15 (R7): after a position gap long enough that the time-scaled progress prior exceeds its
        // fixed floor (speed bound x gap + 2 x radius; ~23 s at a 5 m radius), the first accepted sample
        // only realigns: no guide passage or arrival evidence. An interval within the consecutive-sample
        // limit is not a gap even when a large radius alone exceeds the floor. The next continuous sample
        // reachable from it at the speed bound resumes and passes at most one guide point; an
        // unreachable one keeps realigning.
        val gapRealignment = acceptedGeometricProgressM?.let { progressM ->
            val reference = lastAcceptedProgress ?: return@let GapRealignment.NONE
            val gapMs = unsnappedPosition.elapsedRealtimeMs - reference.second
            val reachM = gapMs / 1_000.0 * progressPriorSpeedBoundMps + 2.0 * unsnappedPosition.horizontalAccuracyM
            when {
                reachM > progressPriorFloorM && gapMs > config.maximumOffRouteSampleGapMs ->
                    GapRealignment.HOLD.also { realigningAfterGap = true }
                !realigningAfterGap -> GapRealignment.NONE
                abs(progressM - reference.first) <= reachM -> GapRealignment.RESUME.also { realigningAfterGap = false }
                else -> GapRealignment.HOLD
            }
        } ?: GapRealignment.NONE
        if (acceptedGeometricProgressM != null) {
            lastAcceptedProgress = acceptedGeometricProgressM to unsnappedPosition.elapsedRealtimeMs
        }
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
            // Remaining distance describes the current position, not the furthest point ever
            // reached. An accepted match must also drive guide passage; capping only its
            // spoken progress leaves a passed turn active while the matcher has moved on.
            // Keep the advance cap for speech-only projections, which are not accepted matches.
            progressDistanceM = if (acceptedGeometricProgressM != null) {
                guidanceProgressM
            } else {
                progressDistanceM?.let { previousProgress ->
                    guidanceProgressM.coerceAtMost(previousProgress + config.maximumProgressAdvanceM)
                } ?: guidanceProgressM
            }
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
        val currentStepProgressM = stepProgressM?.takeIf { it.isFinite() && it >= 0.0 }
        val stepDeltaM = currentStepProgressM?.let { current -> lastStepProgressM?.let { current - it } }
        currentStepProgressM?.let { lastStepProgressM = it }
        val routeProgressDeltaM = previousProgressM?.let { previous -> progressDistanceM?.let { it - previous } }
        // Steps are auxiliary evidence only: they never move the position, but when they show the
        // user did not walk, a large GNSS progress change does not confirm guide passage or
        // arrival. Missing step data keeps GNSS-only behaviour.
        val stationaryJumpThresholdM = maxOf(STATIONARY_GNSS_JUMP_MIN_M, location.accuracyM.toDouble() * 2.0)
        if (stationaryJumpAnchor == null && stepDeltaM != null && routeProgressDeltaM != null && previousProgressM != null &&
            currentStepProgressM != null && stepDeltaM <= STATIONARY_STEP_PROGRESS_M &&
            abs(routeProgressDeltaM) > stationaryJumpThresholdM
        ) {
            stationaryJumpAnchor = StationaryJumpAnchor(previousProgressM, currentStepProgressM - stepDeltaM, location.elapsedRealtimeMs)
        }
        // S07-23: the hold is measured from the progress before the jump, not the previous sample,
        // so a persisting jumped position stays held until the steps explain it or the hold expires.
        // R5 (nav-F4): an expired hold whose steps still show no walking since the jump is
        // unresolved: ordinary progress uses GNSS again, but protected guide passage and arrival
        // stay held and the user is asked to recheck the location.
        var stationaryJumpUnresolved = false
        val stationaryGnssJump = stationaryJumpAnchor?.let { anchor ->
            val walkedM = currentStepProgressM?.let { it - anchor.stepProgressM }?.coerceAtLeast(0.0)
            val claimedM = progressDistanceM?.let { it - anchor.progressM }
            val unexplained = walkedM != null && claimedM != null && abs(claimedM) - walkedM > stationaryJumpThresholdM
            val withinHold = location.elapsedRealtimeMs - anchor.atElapsedRealtimeMs <= STATIONARY_JUMP_HOLD_MS
            stationaryJumpUnresolved = unexplained && !withinHold && walkedM != null && walkedM <= STATIONARY_STEP_PROGRESS_M
            if (!unexplained || (!withinHold && !stationaryJumpUnresolved)) stationaryJumpAnchor = null
            unexplained && withinHold
        } == true
        val recentMovementSpeedMps = previousSample?.let { previous ->
            val intervalS = (unsnappedPosition.elapsedRealtimeMs - previous.filteredPosition.elapsedRealtimeMs) / 1_000.0
            if (intervalS <= 0.0) return@let null
            val gnssSpeed = (haversineMeters(
                previous.filteredPosition.point.latitude, previous.filteredPosition.point.longitude,
                unsnappedPosition.point.latitude, unsnappedPosition.point.longitude,
            ) - hypot(previous.filteredPosition.horizontalAccuracyM, unsnappedPosition.horizontalAccuracyM))
                .coerceAtLeast(0.0) / intervalS
            val stepSpeed = stepDeltaM?.let { it.coerceAtLeast(0.0) / intervalS }
            // A displacement faster than the trusted-location jump limit is not pedestrian travel.
            listOfNotNull(gnssSpeed, stepSpeed).filter { it.isFinite() }.maxOrNull()
                ?.coerceAtMost(LocationTrustConfig().maxJumpSpeedMps.toDouble())
        }
        val stepProgressConsistent = if (stationaryGnssJump || stationaryJumpUnresolved) false else stepProgressM?.let { stepProgress ->
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
        val arrivalEvidence = authoritativeArrivalEvidence && acceptedMatch != null && arrivalRecheckArmed &&
            !stationaryGnssJump && !stationaryJumpUnresolved && gapRealignment != GapRealignment.HOLD
        val offRouteCandidate = distanceToRouteM - unsnappedPosition.horizontalAccuracyM > config.offRouteDistanceM
        if (offRouteCandidate) interruptPassedTurnEvidence()
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
                observeOffRouteCandidate(offRouteCandidate, location.elapsedRealtimeMs, unsnappedPosition.elapsedRealtimeMs)
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
            if (stationaryJumpUnresolved && authoritativeArrivalEvidence && acceptedMatch != null && arrivalRecheckArmed) {
                return stationaryJumpLocationRecheckUpdate()
            }
            observeArrivalCandidate(arrivalEvidence, location.elapsedRealtimeMs, unsnappedPosition.elapsedRealtimeMs)
            if (arrivalSampleCount >= config.arrivalConfirmSamples.coerceAtLeast(1) &&
                arrivalDwellMs() >= config.minimumArrivalDwellMs
            ) {
                updatePendingDecision(RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION)
                return arrivalConfirmationRequiredUpdate(stepProgressConsistent)
            }
            observeOffRouteCandidate(offRouteCandidate, location.elapsedRealtimeMs, unsnappedPosition.elapsedRealtimeMs)
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
            observeArrivalCandidate(arrivalEvidence, location.elapsedRealtimeMs, unsnappedPosition.elapsedRealtimeMs)
            observeOffRouteCandidate(offRouteCandidate, location.elapsedRealtimeMs, unsnappedPosition.elapsedRealtimeMs)
        }
        val diagnosticOffRoute = allowDegradedRouteGuidance &&
            offRouteSampleCount >= config.offRouteConfirmSamples.coerceAtLeast(1)

        if (!latestGuidanceSourceAllowed || alignment.diagnostic.reason == RouteAlignmentReason.DEPARTURE_CONNECTOR) {
            resetRewindEvidence()
            interruptPassedTurnEvidence()
            if (alignment.bearingDegrees != null) {
                // A geometric direction reference is not accepted position/progress evidence.
                // This runs after deviation/arrival decisions and never advances guide points.
                val cancelProgressSpeech = pendingSpeechCue?.directionOnly == false
                if (offeredSpeechCue?.directionOnly == false) invalidateSpeechCue()
                val context = RouteGuidanceContext(
                    routeRevision, location, guidanceLocation, RouteInstruction("", null), alignment,
                    positionEstimateUncertain = true, offRoute = false, stepProgressConsistent = null,
                    directionOnly = true, recentMovementSpeedMps = recentMovementSpeedMps,
                )
                latestGuidanceContext = context
                return offerGuidance(currentRoute, context, nowMs, facingObservation,
                    cancelPassedSpeech = cancelProgressSpeech, facingUnavailableReason = facingUnavailableReason)
            }
            invalidateSpeechCue()
            return RouteNavigatorUpdate(
                instruction = null,
                arrived = false,
                offRoute = false,
                shouldReroute = false,
                reason = "route_match_untrusted",
                cancelStaleNavigationSpeech = true,
                routeAlignmentDiagnostic = alignment.diagnostic,
            )
        }

        val guideRestored = !stationaryGnssJump && gapRealignment != GapRealignment.HOLD && restorePassedGuides(
            currentRoute, guidanceLocation, progressDistanceM,
            minOf(location.elapsedRealtimeMs, unsnappedPosition.elapsedRealtimeMs),
        )
        val guideAdvanced = (!stationaryGnssJump && gapRealignment != GapRealignment.HOLD && advancePassedGuides(
            currentRoute, guidanceLocation, location, progressDistanceM, acceptedMatch != null,
            testGuidanceProjection != null, holdProtectedGuides = stationaryJumpUnresolved,
            maximumGuidesPassed = if (gapRealignment == GapRealignment.RESUME) 1 else Int.MAX_VALUE,
        )) || guideRestored
        if (stationaryJumpUnresolved && !allowDegradedRouteGuidance &&
            protectedGuidePassageClaimed(currentRoute, guidanceLocation)
        ) return stationaryJumpLocationRecheckUpdate()
        val guideInstruction = instructionForCurrentGuide(currentRoute, guidanceLocation)
        val confirmationGuideIndex = guideInstruction.guideIndex?.takeIf { guideInstruction.passageConfirmation }
        val enteredPassageConfirmation = confirmationGuideIndex != null &&
            confirmationGuideIndex != passageConfirmationGuideIndex
        val leftPassageConfirmation = passageConfirmationGuideIndex != null && confirmationGuideIndex == null
        val passageConfirmationChanged = enteredPassageConfirmation || leftPassageConfirmation
        passageConfirmationGuideIndex = confirmationGuideIndex
        val cancelPassedSpeech = (guideAdvanced && pendingSpeechCue?.directionOnly == false) ||
            enteredPassageConfirmation || (leftPassageConfirmation && pendingSpeechCue?.directionOnly == false)
        if ((guideAdvanced && pendingSpeechCue?.directionOnly != true) || passageConfirmationChanged) {
            invalidateSpeechCue()
        }
        // S08-18: turn speech and its compared bearing share the vertex phase of the guide that is
        // still unpassed after this fix (so a guide passed or restored now is already reflected).
        // A direction reference without turn speech above keeps the unheld selection.
        val selectedGuidanceAlignment = routeAlignmentSelector.select(
            currentRoute, unsnappedPosition, routeMatch, nowMs, config.offRouteDistanceM,
            pendingRouteGuide(currentRoute, guidanceLocation),
        )
        // S08-18-F2: past the selector's near-vertex zone the outgoing leg is compared again, but
        // never beside the unpassed guide's turn sentence (only beside its passage confirmation).
        val guidanceAlignment = if (selectedGuidanceAlignment.beyondPendingTurnVertex &&
            !guideInstruction.passageConfirmation
        ) {
            RouteAlignmentSelection(
                RouteAlignmentDiagnostic(RouteAlignmentReason.TURN_PASSAGE_PENDING),
                beyondPendingTurnVertex = true,
            )
        } else selectedGuidanceAlignment
        val context = RouteGuidanceContext(
            routeRevision = routeRevision,
            rawLocation = location,
            guidanceLocation = guidanceLocation,
            instruction = guideInstruction,
            alignment = guidanceAlignment,
            positionEstimateUncertain = ambiguousGuidanceEstimate,
            offRoute = diagnosticOffRoute,
            stepProgressConsistent = stepProgressConsistent,
            recentMovementSpeedMps = recentMovementSpeedMps,
        )
        latestGuidanceContext = context
        return offerGuidance(currentRoute, context, nowMs, facingObservation, guideAdvanced, cancelPassedSpeech,
            passageConfirmationChanged = passageConfirmationChanged, facingUnavailableReason = facingUnavailableReason)
    }

    /** Retries delivery from existing evidence without observing another position or advancing it. */
    @Synchronized
    fun retryGuidance(
        nowMs: Long,
        facingObservation: RouteFacingObservation? = null,
        facingUnavailableReason: RouteCompassHeadingReason? = null,
    ): RouteNavigatorUpdate? {
        val currentRoute = route ?: return null
        val context = currentGuidanceContext(nowMs) ?: return null
        return offerGuidance(currentRoute, context, nowMs, facingObservation, facingUnavailableReason = facingUnavailableReason)
    }

    /** A user request bypasses only completed-cue cadence, never evidence or speech ownership. */
    @Synchronized
    fun currentGuidance(
        nowMs: Long,
        facingObservation: RouteFacingObservation? = null,
        facingUnavailableReason: RouteCompassHeadingReason? = null,
    ): RouteNavigatorUpdate? {
        val currentRoute = route ?: return null
        val context = currentGuidanceContext(nowMs) ?: return null
        return offerGuidance(currentRoute, context, nowMs, facingObservation, forceRepeat = true,
            facingUnavailableReason = facingUnavailableReason)
    }

    private fun currentGuidanceContext(nowMs: Long): RouteGuidanceContext? {
        val currentRoute = route ?: return null
        val context = latestGuidanceContext ?: return null
        val currentGuideIndex = currentRoute.guidePoints.getOrNull(nextGuideIndex)?.let { nextGuideIndex }
        if (context.routeRevision != routeRevision ||
            (!context.directionOnly && context.instruction.guideIndex != currentGuideIndex) ||
            !canDeliverRouteGuidance() || latestUpdateNowMs?.let { nowMs < it } == true ||
            !context.rawLocation.hasCurrentCoordinates(nowMs) || !context.guidanceLocation.hasCurrentCoordinates(nowMs) ||
            context.mayHaveReachedTheNextBranch(nowMs)
        ) {
            latestGuidanceContext = null
            resetClockSector()
            invalidateSpeechCue()
            return null
        }
        return context
    }

    /**
     * The location age limit alone does not keep the stored route bearing current: movement since
     * the fix (measured recent speed, or a walking pace when none was measured) may already have
     * reached the next branch, which can be nearer than it seems by the reported accuracy.
     */
    private fun RouteGuidanceContext.mayHaveReachedTheNextBranch(nowMs: Long): Boolean {
        if (directionOnly || instruction.guideIndex == null || instruction.passageConfirmation) return false
        val branchDistanceM = instruction.distanceM?.toDouble() ?: return false
        val ageMs = nowMs - minOf(rawLocation.elapsedRealtimeMs, guidanceLocation.elapsedRealtimeMs)
        if (ageMs <= 0L) return false
        val possibleTravelM = (recentMovementSpeedMps ?: ASSUMED_CONTEXT_WALKING_SPEED_MPS) * ageMs / 1_000.0
        val allowanceM = maxOf(
            branchDistanceM - maxOf(rawLocation.accuracyM, guidanceLocation.accuracyM).toDouble(),
            branchDistanceM / 2.0,
            MINIMUM_CONTEXT_TRAVEL_ALLOWANCE_M,
        )
        return possibleTravelM > allowanceM
    }

    private fun offerGuidance(
        currentRoute: WalkingRoute,
        context: RouteGuidanceContext,
        nowMs: Long,
        facingObservation: RouteFacingObservation?,
        guideAdvanced: Boolean = false,
        cancelPassedSpeech: Boolean = false,
        forceRepeat: Boolean = false,
        passageConfirmationChanged: Boolean = false,
        facingUnavailableReason: RouteCompassHeadingReason? = null,
    ): RouteNavigatorUpdate {
        val guideInstruction = context.instruction
        // Position/order selects the target. Current compass renders it, including stationary
        // manual requests and retries; it must never choose the route branch.
        if (guideAdvanced || passageConfirmationChanged) resetClockSector()
        val measuredFacing = gateFacingSource(
            RouteFacingGuidance.evaluate(
                context.alignment.bearingDegrees, facingObservation, nowMs,
                context.alignment.bearingUncertaintyDegrees,
            ),
            facingObservation,
        )
        val facing = stabilizeClockSector(measuredFacing, facingObservation)
        val alignmentDiagnostic = context.alignment.diagnostic
        // S08-18-F1: the held bearing only waits for passage; the turn itself is still due.
        val nearPendingTurn = !context.directionOnly && !guideInstruction.passageConfirmation &&
            alignmentDiagnostic.reason == RouteAlignmentReason.TURN_PASSAGE_PENDING
        // S08-20: beside the route the progress direction is only described; no movement or turn
        // command is given from a position whose way onto the route is unknown.
        val besideRoute = !context.directionOnly && !guideInstruction.passageConfirmation && !nearPendingTurn &&
            context.alignment.lateralOffsetBeyondUncertainty
        val facingClockHour = facing.clockHour.takeUnless {
            guideInstruction.passageConfirmation ||
            !context.directionOnly && !besideRoute && facing.direction == RouteFacingDirection.FRONT &&
                CLOCK_DIRECTION_REFERENCE.containsMatchIn(guideInstruction.text)
        }
        val compassAvailable = RouteFacingGuidance.isUsableObservation(facingObservation, nowMs)
        // S08-8: without a usable observation the compass cause selects one recovery step.
        val unavailableRecovery = if (compassAvailable) "" else facingUnavailableReason?.spokenRecovery().orEmpty()
        val facingMessage = when {
            guideInstruction.passageConfirmation -> ""
            nearPendingTurn -> NEAR_PENDING_TURN_PREFIX
            context.directionOnly -> directionReferenceInstruction(
                facing, compassAvailable, alignmentDiagnostic.reason, unavailableRecovery,
            )
            besideRoute -> directionDescription(
                "경로 진행 방향은", facing.direction, facingClockHour, compassAvailable, unavailableRecovery,
            ) + "현재 위치는 경로 옆으로 떨어져 있으며, 현재 위치에서 경로까지 걸어갈 수 있는 통로는 확인되지 않았습니다."
            else -> facingInstruction(
                facing.direction, compassAvailable,
                context.alignment.bearingDegrees != null,
                facingClockHour, alignmentDiagnostic.reason, unavailableRecovery,
            )
        }
        val distanceBand = routeGuidanceDistanceBand(guideInstruction.distanceM)
        val cueKey = CompletedRouteSpeechCue(guideInstruction.guideIndex, distanceBand, context.directionOnly,
            facing.direction.takeUnless { guideInstruction.passageConfirmation }, facingClockHour)
        // Fresh, usable turns must not restart an already playing sentence on the same guide.
        // The playback-start guard still rejects a queued cue with a changed direction.
        // Losing direction or the precision needed by an offered clock cue revokes it immediately.
        // A clock sector change that is only awaiting its second fresh observation is not a
        // precision loss (S08-5): the measured facing still supports a clock number. Neither is a
        // small turn that only nears a sector edge while the sensor precision still supports one.
        // S08-23: a fresh turn that reverses the offered command's meaning (ahead <-> behind,
        // left <-> right) is handled like lost evidence; adjacent changes still finish speaking.
        // nav-F2: the reversal is also judged by angle, not only by the opposite coarse sector.
        val facingEvidenceLost = !guideInstruction.passageConfirmation && offeredFacing?.let {
            (it.direction != RouteFacingDirection.UNKNOWN && facing.direction == RouteFacingDirection.UNKNOWN) ||
                (it.clockHour != null && measuredFacing.clockHour == null && !measuredFacing.clockPrecisionAvailable) ||
                facing.direction == it.direction.reversed() || it.commandReversedBy(facing)
        } == true
        if (pendingSpeechCue?.let {
                it.routeRevision == context.routeRevision &&
                    (it.directionOnly || it.guideIndex == guideInstruction.guideIndex)
            } == true && !facingEvidenceLost
        ) {
            if (forceRepeat) manualGuidancePending = true
            return RouteNavigatorUpdate(null, false, context.offRoute, false, "guidance_in_flight", routeAlignmentDiagnostic = alignmentDiagnostic)
        }
        val previousCue = offeredSpeechCue
        if (previousCue?.guideIndex == guideInstruction.guideIndex &&
            speechRetryNotBeforeMs?.let { nowMs < it } == true
        ) {
            return RouteNavigatorUpdate(null, false, context.offRoute, false, "guidance_retry_wait", routeAlignmentDiagnostic = alignmentDiagnostic)
        }
        val sameCue = previousCue != null && previousCue.guideIndex == guideInstruction.guideIndex &&
            previousCue.distanceBand == distanceBand && previousCue.directionOnly == context.directionOnly &&
            offeredFacingMessage == facingMessage &&
            offeredPositionEstimateUncertain == context.positionEstimateUncertain
        val requestedRepeat = forceRepeat ||
            (manualGuidancePending && previousCue?.guideIndex == guideInstruction.guideIndex)
        val cancelChangedSpeech = !sameCue && pendingSpeechCue != null
        val fullGuidanceAfterReference = previousCue?.directionOnly == true && !context.directionOnly
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
            !periodicGuidanceDue && !manualGuidancePending && !guideAdvanced && !passageConfirmationChanged &&
            !fullGuidanceAfterReference &&
            !(distanceBand in 0..1 && !sameCue)
        ) return noInstruction("guidance_rate_limited")
        // Only a changed coarse direction or a changed confirmed clock number is new information.
        if (completedSpeechCues.any { it.covers(cueKey) } && !periodicGuidanceDue && !manualGuidancePending) {
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
            directionOnly = context.directionOnly,
        ).also { offeredSpeechCue = it }
        offeredFacingMessage = facingMessage
        offeredFacing = facing.takeUnless { guideInstruction.passageConfirmation }
        offeredPositionEstimateUncertain = context.positionEstimateUncertain
        // S08-30: the relative direction and the action are computed now and go stale while speaking, so
        // they come right after the short start cue; the long-lived route total and the position notice
        // follow them instead of delaying them.
        val routeStart = includeRouteStartSummary && !context.directionOnly
        val startCue = if (routeStart) "길안내를 시작합니다. " else ""
        val summary = if (routeStart) " 전체 경로는 약 ${currentRoute.summary.distanceM}m입니다." else ""
        val positionNotice = if (context.positionEstimateUncertain && !context.directionOnly) {
            " 현재 경로 위치가 불확실하여 남은 거리와 안내 지점을 추정합니다."
        } else ""
        val guidanceText = if (guideInstruction.passageConfirmation) {
            guideInstruction.text
        } else {
            facingMessage + when {
                context.directionOnly -> ""
                // S08-20: only the next guide as an overview, never a command from here.
                besideRoute -> nextGuideOverview(currentRoute, context).trimEnd().let { if (it.isEmpty()) "" else " $it" } +
                    " $BESIDE_ROUTE_ACTION"
                nearPendingTurn || facing.direction == RouteFacingDirection.FRONT -> guideInstruction.text
                facing.direction == RouteFacingDirection.UNKNOWN ->
                    nextGuideOverview(currentRoute, context) + "방향과 위치를 확인한 뒤 다시 안내를 요청해 주세요."
                else -> "경로 방향을 맞춘 뒤 다음 안내를 요청해 주세요."
            }
        }
        return RouteNavigatorUpdate(
            instruction = startCue + guidanceText + summary + positionNotice,
            arrived = false,
            offRoute = context.offRoute,
            shouldReroute = false,
            reason = "route_guidance",
            guideIndex = guideInstruction.guideIndex,
            stepProgressConsistent = context.stepProgressConsistent,
            cancelStaleNavigationSpeech = cancelStaleSpeech,
            speechCueToken = token,
            routeAlignmentDiagnostic = alignmentDiagnostic,
            directionOnly = context.directionOnly,
            facingDiagnostic = RouteFacingSpeechDiagnostic(
                relativeAngleDegrees = measuredFacing.signedDifferenceDegrees?.roundToInt(),
                expression = when {
                    guideInstruction.passageConfirmation || nearPendingTurn -> RouteFacingExpression.NOT_SPOKEN
                    facing.direction == RouteFacingDirection.UNKNOWN -> RouteFacingExpression.UNKNOWN
                    (if (context.directionOnly) facing.clockHour else facingClockHour) != null -> RouteFacingExpression.CLOCK
                    else -> RouteFacingExpression.COARSE
                },
            ),
        )
    }

    /**
     * S08-27: with an unknown facing only the route overview of the next guide is spoken, never an
     * action to execute now. It needs a known route bearing (the route side is resolved) and is not
     * given for passage checks. S08-20 uses it beside the route as well. nav-F3: a crossing marker
     * is an overview with its reference notice too, so the notice is not lost in these states.
     */
    private fun nextGuideOverview(currentRoute: WalkingRoute, context: RouteGuidanceContext): String {
        val instruction = context.instruction
        if (context.directionOnly || instruction.passageConfirmation || context.alignment.bearingDegrees == null) return ""
        val guide = instruction.guideIndex?.let { currentRoute.guidePoints.getOrNull(it) } ?: return ""
        return guide.maneuverOverview(instruction.distanceM)?.let { "$it " } ?: ""
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

    /**
     * R5 (nav-F4): an unresolved stationary GNSS jump claims a protected passage or arrival. It
     * uses the existing location-recheck decision; the user's recheck ends the jump hold.
     */
    private fun stationaryJumpLocationRecheckUpdate(): RouteNavigatorUpdate {
        offRouteGuidanceSuspended = true
        deviationSuspectedLatched = true
        locationRecheckAuthorized = false
        updatePendingDecision(RouteNavigatorUserDecision.LOCATION_RECHECK)
        return suspectedDeviationUpdate(
            reason = "stationary_jump_location_recheck_required",
            instruction = "걸음 변화 없이 위치만 크게 바뀌어 안내 지점 통과와 도착을 확정하지 않았습니다. " +
                "위치 다시 확인이라고 말씀해 주세요.",
        )
    }

    /** The next unpassed guide is protected, not a departure guide, and GNSS progress is past it. */
    private fun protectedGuidePassageClaimed(route: WalkingRoute, location: TrustedLocation): Boolean {
        val guide = route.guidePoints.getOrNull(nextGuideIndex) ?: return false
        val guideProgressM = guideProgressDistancesM.getOrNull(nextGuideIndex) ?: return false
        val progressM = progressDistanceM ?: return false
        return isProtectedGuide(guide) && !isDepartureGuide(guide, guideProgressM) &&
            progressM >= guideProgressM + guidePassageMarginM(location)
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

    /**
     * Consecutive deviation evidence uses measurement times, like arrival evidence: processing
     * time can compress or stretch the gap, and a reprojected old raw fix is not a new observation.
     */
    private fun observeOffRouteCandidate(candidate: Boolean, rawAtMs: Long, filteredAtMs: Long) {
        if (!candidate) {
            offRouteSampleCount = 0
            lastOffRouteCandidateAtMs = null
            lastOffRouteCandidateFilteredAtMs = null
            return
        }
        val previousRawAtMs = lastOffRouteCandidateAtMs
        val previousFilteredAtMs = lastOffRouteCandidateFilteredAtMs
        if (offRouteSampleCount > 0 && previousRawAtMs != null && previousFilteredAtMs != null) {
            if (rawAtMs <= previousRawAtMs || filteredAtMs <= previousFilteredAtMs) return
            val gapLimitMs = config.maximumOffRouteSampleGapMs.coerceAtLeast(0L)
            val isConsecutive = rawAtMs - previousRawAtMs <= gapLimitMs &&
                filteredAtMs - previousFilteredAtMs <= gapLimitMs
            offRouteSampleCount = if (isConsecutive) offRouteSampleCount + 1 else 1
        } else {
            offRouteSampleCount = 1
        }
        lastOffRouteCandidateAtMs = rawAtMs
        lastOffRouteCandidateFilteredAtMs = filteredAtMs
    }

    private fun updatePendingDecision(decision: RouteNavigatorUserDecision?) {
        if (pendingDecision == decision) return
        pendingDecision = decision
        decisionOpenedAtMs = if (decision == null) null else nowElapsedRealtimeMs()
        if (decision != null) {
            latestGuidanceContext = null
            invalidateSpeechCue()
        }
        decisionRevision += 1L
    }

    private fun expirePendingDecision() {
        val openedAt = decisionOpenedAtMs ?: return
        val now = nowElapsedRealtimeMs()
        if (now >= openedAt && now - openedAt < config.decisionTimeoutMs) return
        updatePendingDecision(null)
        arrivalSampleCount = 0
        lastArrivalRawSampleAtMs = null
        lastArrivalFilteredSampleAtMs = null
    }

    private fun observeArrivalCandidate(candidate: Boolean, rawAtMs: Long, filteredAtMs: Long) {
        if (!candidate) {
            arrivalSampleCount = 0
            lastArrivalRawSampleAtMs = null
            lastArrivalFilteredSampleAtMs = null
            return
        }
        val previousRawAt = lastArrivalRawSampleAtMs
        val previousFilteredAt = lastArrivalFilteredSampleAtMs
        if (arrivalSampleCount > 0 && previousRawAt != null && previousFilteredAt != null) {
            if (rawAtMs <= previousRawAt || filteredAtMs <= previousFilteredAt) return
            val consecutive = rawAtMs - previousRawAt <= config.maximumArrivalSampleGapMs &&
                filteredAtMs - previousFilteredAt <= config.maximumArrivalSampleGapMs
            arrivalSampleCount = if (consecutive) arrivalSampleCount + 1 else 1
        } else {
            arrivalSampleCount = 1
        }
        if (arrivalSampleCount == 1) {
            firstArrivalRawSampleAtMs = rawAtMs
            firstArrivalFilteredSampleAtMs = filteredAtMs
        }
        lastArrivalRawSampleAtMs = rawAtMs
        lastArrivalFilteredSampleAtMs = filteredAtMs
    }

    /** S07-32: how long (measurement time) the current consecutive arrival evidence has lasted. */
    private fun arrivalDwellMs(): Long {
        val raw = lastArrivalRawSampleAtMs?.let { last -> firstArrivalRawSampleAtMs?.let { last - it } } ?: return 0L
        val filtered = lastArrivalFilteredSampleAtMs?.let { last -> firstArrivalFilteredSampleAtMs?.let { last - it } } ?: return 0L
        return minOf(raw, filtered)
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
        offeredFacing = null
        offeredPositionEstimateUncertain = null
        pendingSpeechCue = null
        failedSpeechCue = null
        completedSpeechCue = null
        speechRetryNotBeforeMs = null
        manualGuidancePending = false
        reservedManualGuidance = false
    }

    private fun stabilizeClockSector(
        facing: RouteFacingGuidanceResult,
        observation: RouteFacingObservation?,
    ): RouteFacingGuidanceResult {
        val hour = facing.clockHour
        if (hour == null || observation == null) {
            // S08-5 (nav-F1): only an absent, UNKNOWN or imprecise observation clears the spoken
            // sector. A usable, precise sample withheld only because it is near a sector edge keeps
            // the confirmed sector, so the next sector still needs its own consecutive fresh samples;
            // it only interrupts a pending change.
            if (observation == null || facing.direction == RouteFacingDirection.UNKNOWN || !facing.clockPrecisionAvailable) {
                resetClockSector()
            } else if (lastClockObservationAtMs?.let { observation.observedAtElapsedRealtimeMs > it } != false) {
                lastClockObservationAtMs = observation.observedAtElapsedRealtimeMs
                pendingClockHour = null
                pendingClockObservationCount = 0
            }
            return facing
        }
        val observedAtMs = observation.observedAtElapsedRealtimeMs
        val lastObservedAtMs = lastClockObservationAtMs
        if (lastObservedAtMs != null && observedAtMs < lastObservedAtMs) {
            return facing.copy(clockDirectionAllowed = false)
        }
        if (lastObservedAtMs == null || observedAtMs > lastObservedAtMs) {
            lastClockObservationAtMs = observedAtMs
            if (pendingClockHour == hour) {
                pendingClockObservationCount += 1
            } else {
                pendingClockHour = hour
                pendingClockObservationCount = 1
            }
            // Only a change of the spoken sector is stabilized: with no confirmed sector (first fix,
            // or after an invalid/absent observation reset it) the first fresh sample is spoken.
            // A repeated call with the same sensor sample cannot confirm a sector change.
            if (confirmedClockHour == null || pendingClockObservationCount >= 2) confirmedClockHour = hour
        }
        return facing.copy(clockDirectionAllowed = hour == confirmedClockHour)
    }

    private fun resetClockSector() {
        confirmedClockHour = null
        pendingClockHour = null
        pendingClockObservationCount = 0
        lastClockObservationAtMs = null
    }

    /**
     * After a primary/fallback compass switch, or for a fallback whose motion check could not run,
     * a direction is spoken only after two distinct fresh observations of that source agree.
     * The previous source detects the switch; its heading is never reused as the current facing.
     */
    private fun gateFacingSource(
        facing: RouteFacingGuidanceResult,
        observation: RouteFacingObservation?,
    ): RouteFacingGuidanceResult {
        if (observation == null || facing.direction == RouteFacingDirection.UNKNOWN) {
            facingConfirmationDirection = null
            facingConfirmationCount = 0
            return facing
        }
        val anchor = facingSourceAnchor
        if (anchor != null && observation.source != anchor) {
            facingSourceSwitchPending = true
            facingConfirmationDirection = null
            facingConfirmationCount = 0
            facingConfirmationObservedAtMs = null
        }
        facingSourceAnchor = observation.source
        val observedAtMs = observation.observedAtElapsedRealtimeMs
        val lastObservedAtMs = facingConfirmationObservedAtMs
        if (lastObservedAtMs == null || observedAtMs > lastObservedAtMs) {
            facingConfirmationObservedAtMs = observedAtMs
            if (facingConfirmationDirection == facing.direction) {
                facingConfirmationCount += 1
            } else {
                facingConfirmationDirection = facing.direction
                facingConfirmationCount = 1
            }
        }
        val confirmed = facingConfirmationDirection == facing.direction && facingConfirmationCount >= 2
        if (facingSourceSwitchPending && confirmed) facingSourceSwitchPending = false
        val motionUnchecked = observation.sensorQuality == RouteFacingSensorQuality.CALIBRATED_WITHOUT_MOTION_CHECK
        return if ((facingSourceSwitchPending || motionUnchecked) && !confirmed) {
            facing.copy(direction = RouteFacingDirection.UNKNOWN)
        } else facing
    }

    private fun resetFacingSourceGate() {
        facingSourceAnchor = null
        facingSourceSwitchPending = false
        facingConfirmationDirection = null
        facingConfirmationCount = 0
        facingConfirmationObservedAtMs = null
    }

    private fun directionReferenceInstruction(
        facing: RouteFacingGuidanceResult,
        compassAvailable: Boolean,
        reason: RouteAlignmentReason,
        unavailableRecovery: String = "",
    ): String {
        val subject = when (reason) {
            RouteAlignmentReason.DEPARTURE_CONNECTOR -> "경로 시작점은"
            RouteAlignmentReason.DEPARTURE_SEGMENT -> "출발 경로는"
            else -> "경로 진행 방향은"
        }
        val direction = directionDescription(subject, facing.direction, facing.clockHour, compassAvailable, unavailableRecovery)
        return direction + if (reason == RouteAlignmentReason.DEPARTURE_CONNECTOR) {
            "현재 위치에서 경로 시작점까지 걸어갈 수 있는 통로는 확인되지 않았습니다."
        } else {
            "현재 경로 위치를 확인 중이며, 남은 거리와 회전 지점은 아직 안내할 수 없습니다."
        }
    }

    /** Where the route lies relative to the phone's rear camera, as a description without an action. */
    private fun directionDescription(
        subject: String,
        direction: RouteFacingDirection,
        clockHour: Int?,
        compassAvailable: Boolean,
        unavailableRecovery: String = "",
    ): String {
        val coarse = when (direction) {
            RouteFacingDirection.FRONT -> "앞쪽"
            RouteFacingDirection.LEFT -> "왼쪽"
            RouteFacingDirection.RIGHT -> "오른쪽"
            RouteFacingDirection.BEHIND -> "뒤쪽"
            RouteFacingDirection.UNKNOWN -> null
        }
        return clockHour?.let { "$subject 휴대전화 후면 카메라 방향 기준 약 ${it}시 방향입니다. " }
            ?: coarse?.let { "$subject 휴대전화 후면 카메라 기준 ${it}입니다. " }
            // A measured but ambiguous direction is not the same as a missing camera direction.
            ?: if (compassAvailable) {
                "나침반 방향은 측정되지만 앞뒤와 좌우를 확실히 구분하기 어렵습니다. "
            } else {
                "휴대전화 후면 카메라 방향을 확인할 수 없습니다. $unavailableRecovery"
            }
    }

    private fun facingInstruction(
        direction: RouteFacingDirection,
        compassAvailable: Boolean,
        routeBearingAvailable: Boolean,
        clockHour: Int?,
        alignmentReason: RouteAlignmentReason,
        unavailableRecovery: String = "",
    ): String {
        val clock = clockHour?.let { "약 ${it}시 방향" }
        if (clock != null && alignmentReason in setOf(RouteAlignmentReason.DEPARTURE_SEGMENT, RouteAlignmentReason.DEPARTURE_CONNECTOR)) {
            val subject = if (alignmentReason == RouteAlignmentReason.DEPARTURE_CONNECTOR) "경로 시작점은" else "출발 경로는"
            val align = when (direction) {
                RouteFacingDirection.LEFT -> "휴대전화 후면 카메라를 몸 정면에 맞춘 상태에서만 먼저 왼쪽으로 방향을 맞추세요. "
                RouteFacingDirection.RIGHT -> "휴대전화 후면 카메라를 몸 정면에 맞춘 상태에서만 먼저 오른쪽으로 방향을 맞추세요. "
                RouteFacingDirection.BEHIND -> "휴대전화 후면 카메라를 몸 정면에 맞춘 상태에서만 몸을 돌려 경로 방향을 맞추세요. "
                else -> ""
            }
            return "휴대전화 후면 카메라 기준 $subject ${clock}입니다. $align" + "경로 기준으로 "
        }
        val clockNotice = clock?.let { "경로는 ${it}입니다. " } ?: ""
        return when (direction) {
            RouteFacingDirection.FRONT -> "휴대전화 후면 카메라 기준 " + clockNotice + "앞쪽이 경로 진행 방향입니다. 경로 기준으로 "
            RouteFacingDirection.LEFT -> "휴대전화 후면 카메라 기준 " + clockNotice + "경로는 왼쪽입니다. 휴대전화 후면 카메라를 몸 정면에 맞춘 상태에서만 먼저 왼쪽 경로 방향으로 몸을 돌리세요. 경로 기준으로 "
            RouteFacingDirection.RIGHT -> "휴대전화 후면 카메라 기준 " + clockNotice + "경로는 오른쪽입니다. 휴대전화 후면 카메라를 몸 정면에 맞춘 상태에서만 먼저 오른쪽 경로 방향으로 몸을 돌리세요. 경로 기준으로 "
            RouteFacingDirection.BEHIND -> "휴대전화 후면 카메라 기준 " + clockNotice + "경로 진행 방향이 뒤쪽입니다. 휴대전화 후면 카메라를 몸 정면에 맞춘 상태에서만 경로 방향으로 몸을 돌리세요. 경로 기준으로 "
            RouteFacingDirection.UNKNOWN -> when {
                // No route command follows an UNKNOWN direction, so do not announce one.
                !routeBearingAvailable -> "현재 위치에서 경로의 진행 방향을 정확히 구분하기 어렵습니다. "
                compassAvailable -> "나침반 방향은 측정되지만 앞뒤와 좌우를 확실히 구분하기 어렵습니다. "
                else -> "휴대전화 후면 카메라 방향을 확인할 수 없습니다. $unavailableRecovery"
            }
        }
    }

    private fun resetRewindEvidence() {
        rewindGuideIndex = null
        rewindSampleCount = 0
        rewindSampleAtMs = null
    }

    private fun resetPassedTurnEvidence() {
        lastPassedImportantTurnProgressM = null
        lastPassedImportantTurnGuideProgressM = null
        lastPassedImportantTurnRawAtMs = null
        lastPassedImportantTurnFilteredAtMs = null
        speechOnlyImportantTurnPassed = false
        interruptPassedTurnEvidence()
    }

    private fun interruptPassedTurnEvidence() {
        passedImportantTurnEvidenceStage = 0
        passedImportantTurnEvidenceRawAtMs = null
        passedImportantTurnEvidenceFilteredAtMs = null
        passedImportantTurnBeyondUpperBoundM = null
    }

    private fun observeArrivalDeparture(raw: TrustedLocation, filtered: FilteredRoutePosition, destination: RoutePoint) {
        val rawDistanceM = haversineMeters(raw.latitude, raw.longitude, destination.latitude, destination.longitude)
        val filteredDistanceM = haversineMeters(
            filtered.point.latitude, filtered.point.longitude, destination.latitude, destination.longitude,
        )
        // Leaving the destination area does not require a successful match to the old route.
        // Both original and filtered observations must independently support that departure.
        // S07-35: departure is proven by distance minus the reported radius, so any trusted-location
        // radius (not only the tighter arrival bound) may withdraw a question it no longer fits.
        val trustedAccuracyM = LocationTrustConfig().maxAccuracyM
        val outside = raw.accuracyM <= trustedAccuracyM &&
            filtered.horizontalAccuracyM <= trustedAccuracyM &&
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
        resetPassedTurnEvidence()
        // A reapproach is a new traversal of this guide; old approach completions must not
        // suppress its fresh maneuver. Speech tokens are retired by the caller.
        completedSpeechCues.removeAll { (index, _) -> index != null && index >= candidate }
        resetRewindEvidence()
        return true
    }

    private fun resolveGuideProgressDistances(route: WalkingRoute): List<Double?> {
        var previousDistanceM = 0.0
        return route.guidePoints.map { guide ->
            val declaredM = guide.distanceFromStartM?.toDouble()
                ?: guide.remainingDistanceM?.let { route.summary.distanceM - it.toDouble() }
            val projectedM = if (declaredM == null) {
                projectToRoute(
                    location = TrustedLocation(guide.point.latitude, guide.point.longitude, 0f, 0L),
                    route = route,
                    previousDistanceFromStartM = previousDistanceM,
                    maximumBacktrackM = 0.0,
                    maximumAdvanceM = Double.POSITIVE_INFINITY,
                )?.takeIf { it.distanceToRouteM <= 30.0 }?.distanceFromStartM
            } else {
                orderedGuideShapeProgressM(route, guide.point, declaredM, previousDistanceM)
            }
            // A response may be accepted with a larger declared-vs-shape error than turn timing
            // can use: user progress is measured on the stored shape, so a guide whose declared
            // distance exceeds the turn-distance bound is timed at its shape position instead.
            val distanceM = when {
                declaredM == null -> projectedM
                projectedM != null && abs(declaredM - projectedM) > TURN_GUIDE_DISTANCE_TOLERANCE_M -> projectedM
                else -> declaredM
            }
            // The validated backend contract allows 10 m of guide ordering/rounding noise.
            // Keep that accepted point traversable instead of replacing its progress with null.
            distanceM?.coerceAtLeast(previousDistanceM)?.also { previousDistanceM = it }
        }
    }

    /** Summary-scaled shape progress of the on-route visit closest to the declared progress. */
    private fun orderedGuideShapeProgressM(
        route: WalkingRoute,
        point: RoutePoint,
        declaredM: Double,
        minimumM: Double,
    ): Double? {
        if (route.polyline.size < 2) return null
        val lengths = route.polyline.zipWithNext().map { (start, end) ->
            haversineMeters(start.latitude, start.longitude, end.latitude, end.longitude)
        }
        val geometricTotalM = lengths.sum()
        if (geometricTotalM <= 0.0 || !geometricTotalM.isFinite()) return null
        val scale = route.summary.distanceM.toDouble().takeIf { it > 0.0 }?.div(geometricTotalM) ?: 1.0
        val location = TrustedLocation(point.latitude, point.longitude, 0f, 0L)
        var cumulativeM = 0.0
        var best: Double? = null
        route.polyline.zipWithNext().forEachIndexed { index, (start, end) ->
            val projected = projectToSegment(location, start, end)
            val progressM = (cumulativeM + lengths[index] * projected.fraction) * scale
            cumulativeM += lengths[index]
            if (projected.distanceM > 30.0 || progressM < minimumM - TURN_GUIDE_DISTANCE_TOLERANCE_M) return@forEachIndexed
            if (best?.let { abs(progressM - declaredM) < abs(it - declaredM) } != false) best = progressM
        }
        return best
    }

    /**
     * S08-18: the next unpassed non-departure guide in the selector's geometric progress. The
     * selector may use a leg beyond its vertex only after advancePassedGuides has consumed it.
     */
    private fun pendingRouteGuide(route: WalkingRoute, location: TrustedLocation): PendingRouteGuide? {
        val guide = route.guidePoints.getOrNull(nextGuideIndex) ?: return null
        val progressM = guideProgressDistancesM.getOrNull(nextGuideIndex)?.takeIf { it.isFinite() } ?: return null
        if (isDepartureGuide(guide, progressM)) return null
        val geometricTotalM = route.polyline.zipWithNext().sumOf { (start, end) ->
            haversineMeters(start.latitude, start.longitude, end.latitude, end.longitude)
        }
        if (!geometricTotalM.isFinite() || geometricTotalM <= 0.0) return null
        val declaredM = route.summary.distanceM.toDouble()
        val scale = if (declaredM > 0.0) declaredM / geometricTotalM else 1.0
        return PendingRouteGuide(
            progressM / scale, TURN_GUIDE_DISTANCE_TOLERANCE_M / scale, guidePassageMarginM(location) / scale,
        )
    }

    /** Route progress beyond a guide that passes it; S08-18-F2 shares it with the bearing hold. */
    private fun guidePassageMarginM(location: TrustedLocation): Double =
        if (allowDegradedRouteGuidance) MINIMUM_GUIDE_PASSAGE_MARGIN_M
        else maxOf(MINIMUM_GUIDE_PASSAGE_MARGIN_M, location.accuracyM.toDouble())

    private fun isProtectedGuide(guide: WalkingRouteGuidePoint): Boolean =
        guide.turnType in setOf(12, 13, 14, 16, 17, 18, 19) || CrosswalkReferencePolicy.isCrosswalkGuide(guide)

    private fun isDepartureGuide(guide: WalkingRouteGuidePoint, progressM: Double?): Boolean =
        (guide.pointType.equals("SP", ignoreCase = true) || guide.turnType == 200) &&
            progressM != null && progressM <= 15.0

    /** Location progress consumes passed points even if their old utterance was suppressed. */
    private fun advancePassedGuides(
        route: WalkingRoute,
        location: TrustedLocation,
        rawLocation: TrustedLocation,
        progressM: Double?,
        matchedForTurnEvidence: Boolean,
        speechOnlyProjection: Boolean,
        /** R5 (nav-F4): an unresolved stationary jump may pass ordinary guides, never protected ones. */
        holdProtectedGuides: Boolean = false,
        /** S07-15 (R7): the sample resuming after a long-gap realignment passes one guide at most. */
        maximumGuidesPassed: Int = Int.MAX_VALUE,
    ): Boolean {
        val previousIndex = nextGuideIndex
        if (speechOnlyProjection && !matchedForTurnEvidence) {
            val guide = route.guidePoints.getOrNull(nextGuideIndex) ?: return false
            val guideProgressM = guideProgressDistancesM.getOrNull(nextGuideIndex)
            val passed = progressM != null && guideProgressM != null &&
                progressM >= guideProgressM + 3.0
            if (!isDepartureGuide(guide, guideProgressM) && !passed) return false
            val protectedGuide = isProtectedGuide(guide)
            if (holdProtectedGuides && protectedGuide && !isDepartureGuide(guide, guideProgressM)) return false
            // LOW test guidance changes only the displayed guide. It cannot supply the
            // accepted movement evidence needed to consume a subsequent protected guide.
            if (protectedGuide && (lastPassedImportantTurnRawAtMs != null ||
                    lastPassedImportantTurnProgressM != null || speechOnlyImportantTurnPassed)) {
                return false
            }
            nextGuideIndex += 1
            if (protectedGuide) {
                speechOnlyImportantTurnPassed = true
                lastPassedImportantTurnGuideProgressM = guideProgressM
                interruptPassedTurnEvidence()
            }
            return true
        }
        var passedImportantTurn = false
        var subsequentTurnEvidenceComplete = false
        var subsequentTurnObservedProgressM: Double? = null
        if (speechOnlyImportantTurnPassed && matchedForTurnEvidence && progressM != null) {
            // The first accepted fix after a speech-only turn starts, but does not advance,
            // the evidence sequence for the next important turn.
        lastPassedImportantTurnProgressM = progressM
        lastPassedImportantTurnRawAtMs = rawLocation.elapsedRealtimeMs
        lastPassedImportantTurnFilteredAtMs = latestLocationSample?.filteredPosition?.elapsedRealtimeMs
        interruptPassedTurnEvidence()
            passedImportantTurnEvidenceRawAtMs = rawLocation.elapsedRealtimeMs
            passedImportantTurnEvidenceFilteredAtMs = latestLocationSample?.filteredPosition?.elapsedRealtimeMs
            speechOnlyImportantTurnPassed = false
        }
        val lastProtectedRawAtMs = lastPassedImportantTurnRawAtMs
        val lastProtectedFilteredAtMs = lastPassedImportantTurnFilteredAtMs
        val currentFilteredAtMs = latestLocationSample?.takeIf { it.rawLocation == rawLocation }
            ?.filteredPosition?.elapsedRealtimeMs
        if (lastPassedImportantTurnProgressM == null &&
            lastPassedImportantTurnGuideProgressM != null &&
            lastProtectedRawAtMs != null && lastProtectedFilteredAtMs != null &&
            matchedForTurnEvidence && progressM != null && currentFilteredAtMs != null &&
            rawLocation.elapsedRealtimeMs > lastProtectedRawAtMs &&
            currentFilteredAtMs > lastProtectedFilteredAtMs
        ) {
            // A protected departure can be consumed before progress is available. Its
            // first later accepted fix only anchors evidence for the next protected guide.
            lastPassedImportantTurnProgressM = progressM
            lastPassedImportantTurnRawAtMs = rawLocation.elapsedRealtimeMs
            lastPassedImportantTurnFilteredAtMs = currentFilteredAtMs
            interruptPassedTurnEvidence()
            passedImportantTurnEvidenceRawAtMs = rawLocation.elapsedRealtimeMs
            passedImportantTurnEvidenceFilteredAtMs = currentFilteredAtMs
        }
        if (lastPassedImportantTurnProgressM != null && lastPassedImportantTurnGuideProgressM != null) {
            val sample = latestLocationSample?.takeIf { it.rawLocation == rawLocation }
            val acceptedMatch = sample?.takeIf { matchedForTurnEvidence }?.let {
                currentAcceptedRouteMatchFor(it.filteredPosition.elapsedRealtimeMs)
            }?.takeUnless {
                it.reason in setOf(
                    RouteMatchReason.AMBIGUOUS_CANDIDATES,
                    RouteMatchReason.ACTIVE_BRANCH_RETAINED,
                    RouteMatchReason.BRANCH_SWITCH_PENDING,
                )
            }
            val matchedProgressM = acceptedMatch?.geometricProgressM?.takeIf { it.isFinite() }
            val accuracyM = sample?.filteredPosition?.horizontalAccuracyM?.takeIf { it.isFinite() && it >= 0.0 }
            val rawAccuracyM = sample?.rawLocation?.accuracyM?.takeIf { it.isFinite() && it >= 0f }?.toDouble()
            val rawFilteredGapM = sample?.takeIf {
                it.rawLocation.elapsedRealtimeMs == it.filteredPosition.elapsedRealtimeMs
            }?.let {
                haversineMeters(
                    it.rawLocation.latitude, it.rawLocation.longitude,
                    it.filteredPosition.point.latitude, it.filteredPosition.point.longitude,
                )
            }?.takeIf { it.isFinite() }
            // A filtered route match cannot corroborate movement when its paired raw fix
            // is farther away than the uncertainty reported by the two observations.
            val spatiallyCorroborated = rawAccuracyM != null && accuracyM != null &&
                rawFilteredGapM != null && rawFilteredGapM <= rawAccuracyM + accuracyM
            val nextProtectedGuideIndex = (nextGuideIndex until route.guidePoints.size).firstOrNull {
                isProtectedGuide(route.guidePoints[it])
            }
            val nextTurnProgressM = nextProtectedGuideIndex?.let { guideProgressDistancesM.getOrNull(it) }
            val geometricRouteDistanceM = route.polyline.zipWithNext().sumOf { (start, end) ->
                haversineMeters(start.latitude, start.longitude, end.latitude, end.longitude)
            }
            val previousTurnProgressM = lastPassedImportantTurnGuideProgressM!!
            if (matchedProgressM == null || accuracyM == null || rawAccuracyM == null ||
                rawFilteredGapM == null || nextTurnProgressM == null || !spatiallyCorroborated ||
                !geometricRouteDistanceM.isFinite() || geometricRouteDistanceM <= 0.0 ||
                route.summary.distanceM <= 0 || !previousTurnProgressM.isFinite() ||
                previousTurnProgressM < 0.0 || !nextTurnProgressM.isFinite() ||
                nextTurnProgressM < previousTurnProgressM ||
                nextTurnProgressM > route.summary.distanceM
            ) {
                interruptPassedTurnEvidence()
            } else {
                val effectiveRadiusM = maxOf(accuracyM, rawAccuracyM + rawFilteredGapM)
                // Display progress stops at the endpoint; passage evidence must still describe
                // observed movement and its full uncertainty interval. Only an accepted final
                // segment match can extend that evidence along the terminal segment direction.
                val terminalBearingDeg = acceptedMatch?.bearingDeg
                val terminalAdvanceM = if (acceptedMatch?.segmentIndex == route.polyline.lastIndex - 1 &&
                    acceptedMatch.segmentFraction == 1.0 && terminalBearingDeg != null
                ) {
                    val endpoint = route.polyline.last()
                    val observed = sample.filteredPosition.point
                    val endpointDistanceM = haversineMeters(
                        endpoint.latitude, endpoint.longitude, observed.latitude, observed.longitude,
                    )
                    val offsetBearingDeg = bearingDegrees(
                        endpoint.latitude, endpoint.longitude, observed.latitude, observed.longitude,
                    )
                    (endpointDistanceM * cos(Math.toRadians(
                        (offsetBearingDeg - terminalBearingDeg).toDouble(),
                    ))).coerceAtLeast(0.0)
                } else 0.0
                val evidenceProgressM = matchedProgressM + terminalAdvanceM
                val summaryScale = route.summary.distanceM / geometricRouteDistanceM
                val lowerBoundM = (evidenceProgressM - effectiveRadiusM) * summaryScale
                val centerProgressM = evidenceProgressM * summaryScale
                val upperBoundM = (evidenceProgressM + effectiveRadiusM) * summaryScale
                if (!lowerBoundM.isFinite() || !centerProgressM.isFinite() ||
                    !upperBoundM.isFinite() || lowerBoundM > upperBoundM
                ) {
                    interruptPassedTurnEvidence()
                    return previousIndex != nextGuideIndex
                }
                subsequentTurnObservedProgressM = centerProgressM
                val filteredAtMs = sample.filteredPosition.elapsedRealtimeMs
                val previousRawAtMs = passedImportantTurnEvidenceRawAtMs
                val previousFilteredAtMs = passedImportantTurnEvidenceFilteredAtMs
                if (previousRawAtMs == null || previousFilteredAtMs == null) {
                    // The first accepted fix after an interruption anchors time, not passage.
                    passedImportantTurnEvidenceRawAtMs = rawLocation.elapsedRealtimeMs
                    passedImportantTurnEvidenceFilteredAtMs = filteredAtMs
                } else if (rawLocation.elapsedRealtimeMs > previousRawAtMs && filteredAtMs > previousFilteredAtMs) {
                    when (passedImportantTurnEvidenceStage) {
                        0 -> if (lowerBoundM > previousTurnProgressM &&
                            (upperBoundM < nextTurnProgressM ||
                                // No full uncertainty interval fits between closer turns.
                                // Its center may anchor the approach, but the next two
                                // observations still must clear the turn and each other.
                                (nextTurnProgressM - previousTurnProgressM <= upperBoundM - lowerBoundM &&
                                    centerProgressM < nextTurnProgressM) ||
                                // After an overshoot (including coincident guides), a fresh
                                // beyond-guide fix may anchor the same three-fix sequence.
                                lowerBoundM > nextTurnProgressM)
                        ) {
                            passedImportantTurnEvidenceStage = 1
                            passedImportantTurnEvidenceRawAtMs = rawLocation.elapsedRealtimeMs
                            passedImportantTurnEvidenceFilteredAtMs = filteredAtMs
                        }
                        1 -> if (lowerBoundM > nextTurnProgressM) {
                            passedImportantTurnEvidenceStage = 2
                            passedImportantTurnBeyondUpperBoundM = upperBoundM
                            passedImportantTurnEvidenceRawAtMs = rawLocation.elapsedRealtimeMs
                            passedImportantTurnEvidenceFilteredAtMs = filteredAtMs
                        }
                        2 -> subsequentTurnEvidenceComplete =
                            lowerBoundM > (passedImportantTurnBeyondUpperBoundM ?: Double.POSITIVE_INFINITY)
                    }
                }
            }
        }
        while (true) {
            if (nextGuideIndex - previousIndex >= maximumGuidesPassed) break
            val guide = route.guidePoints.getOrNull(nextGuideIndex) ?: break
            val guideProgressM = guideProgressDistancesM.getOrNull(nextGuideIndex)
            val protectedGuide = isProtectedGuide(guide)
            // A completed protected passage uses the same accepted observation as its
            // evidence sequence, including real forward movement beyond the endpoint.
            val passageProgressM = if (protectedGuide && subsequentTurnEvidenceComplete) {
                subsequentTurnObservedProgressM
            } else progressM
            val passedMarginM = guidePassageMarginM(location)
            val passed = passageProgressM != null && guideProgressM != null &&
                passageProgressM >= guideProgressM + passedMarginM
            if (!isDepartureGuide(guide, guideProgressM) && !passed) break
            if (holdProtectedGuides && protectedGuide && !isDepartureGuide(guide, guideProgressM)) break
            // One accepted fix can jump across nearby guides; the next protected guide needs another fix.
            if (protectedGuide && passedImportantTurn) break
            if (protectedGuide && !matchedForTurnEvidence) break
        val lastTurnProgressM = lastPassedImportantTurnProgressM
        val lastTurnGuideProgressM = lastPassedImportantTurnGuideProgressM
        val lastTurnRawAtMs = lastPassedImportantTurnRawAtMs
        val lastTurnFilteredAtMs = lastPassedImportantTurnFilteredAtMs
        val filteredAtMs = latestLocationSample?.filteredPosition?.elapsedRealtimeMs
        // A protected departure without progress must first establish a later accepted anchor.
        if (protectedGuide && lastTurnProgressM == null && lastTurnRawAtMs != null) break
        val plausibleTurnTravel = if (protectedGuide && lastTurnProgressM != null) {
            guideProgressM != null && lastTurnGuideProgressM != null &&
                guideProgressM.isFinite() && lastTurnGuideProgressM.isFinite() &&
                guideProgressM >= lastTurnGuideProgressM &&
                lastTurnRawAtMs != null && lastTurnFilteredAtMs != null && filteredAtMs != null &&
                rawLocation.elapsedRealtimeMs > lastTurnRawAtMs && filteredAtMs > lastTurnFilteredAtMs &&
                guideProgressM - lastTurnGuideProgressM <=
                minOf(
                    rawLocation.elapsedRealtimeMs - lastTurnRawAtMs,
                    filteredAtMs - lastTurnFilteredAtMs,
                ) * MAX_IMPORTANT_TURN_EVIDENCE_SPEED_MPS / 1_000.0
        } else true
        val observedTurnProgressM = subsequentTurnObservedProgressM ?: progressM
        if (protectedGuide && lastTurnProgressM != null &&
            (observedTurnProgressM == null || observedTurnProgressM <= lastTurnProgressM ||
                !subsequentTurnEvidenceComplete || !plausibleTurnTravel)
        ) break
            nextGuideIndex += 1
            if (protectedGuide) {
                passedImportantTurn = true
            lastPassedImportantTurnProgressM = progressM
            lastPassedImportantTurnGuideProgressM = guideProgressM
            lastPassedImportantTurnRawAtMs = rawLocation.elapsedRealtimeMs
            lastPassedImportantTurnFilteredAtMs = latestLocationSample?.filteredPosition?.elapsedRealtimeMs
            interruptPassedTurnEvidence()
                passedImportantTurnEvidenceRawAtMs = rawLocation.elapsedRealtimeMs
                passedImportantTurnEvidenceFilteredAtMs = latestLocationSample?.filteredPosition?.elapsedRealtimeMs
            }
        }
        return previousIndex != nextGuideIndex
    }

    private fun instructionForCurrentGuide(route: WalkingRoute, location: TrustedLocation): RouteInstruction {
        val guide = route.guidePoints.getOrNull(nextGuideIndex)
        if (guide != null) {
            val guideProgressM = guideProgressDistancesM.getOrNull(nextGuideIndex)
            if (progressDistanceM?.let { progressM -> guideProgressM?.let { progressM > it } } == true) {
                return RouteInstruction(
                    text = "안내 지점 통과 여부를 확인 중입니다.",
                    guideIndex = nextGuideIndex,
                    passageConfirmation = true,
                )
            }
            val routeDistanceToGuideM = progressDistanceM?.let { progressM ->
                guideProgressM?.let { (it - progressM).coerceAtLeast(0.0).roundToInt() }
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
                // S20-27: the route end is not the destination. Say how far apart they are (10 m steps),
                // as information only: there is no route for that gap, so it is never a move command.
                val gapM = (((endpointGapM + 5.0) / 10.0).toInt() * 10).coerceAtLeast(10)
                "경로 종점입니다. 요청한 목적지는 종점에서 직선으로 약 ${gapM}m 떨어져 있습니다. " +
                    "요청한 목적지의 최종 접근을 확인하세요."
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
/**
 * S08-23 (nav-F2): a fresh usable facing reverses an offered command beyond the margin it was
 * classified with: an "ahead" cue once the route is more than a right angle off either side, a
 * left/right cue once the route is on the other side of the phone's front (not across the back).
 */
private fun RouteFacingGuidanceResult.commandReversedBy(fresh: RouteFacingGuidanceResult): Boolean {
    if (fresh.direction == RouteFacingDirection.UNKNOWN) return false
    val difference = fresh.signedDifferenceDegrees ?: return false
    val margin = fresh.directionMarginDegrees ?: return false
    return when (direction) {
        RouteFacingDirection.FRONT -> abs(difference) - margin > 90.0
        RouteFacingDirection.LEFT -> difference - margin > 0.0 && difference + margin < 180.0
        RouteFacingDirection.RIGHT -> difference + margin < 0.0 && difference - margin > -180.0
        RouteFacingDirection.BEHIND, RouteFacingDirection.UNKNOWN -> false
    }
}

/** S08-23: the direction whose body-turn command means the opposite; UNKNOWN has none. */
private fun RouteFacingDirection.reversed(): RouteFacingDirection? = when (this) {
    RouteFacingDirection.FRONT -> RouteFacingDirection.BEHIND
    RouteFacingDirection.BEHIND -> RouteFacingDirection.FRONT
    RouteFacingDirection.LEFT -> RouteFacingDirection.RIGHT
    RouteFacingDirection.RIGHT -> RouteFacingDirection.LEFT
    RouteFacingDirection.UNKNOWN -> null
}

private data class StationaryJumpAnchor(val progressM: Double, val stepProgressM: Double, val atElapsedRealtimeMs: Long)

/** S07-15 (R7): how an accepted sample relates to a long position gap. */
private enum class GapRealignment { NONE, HOLD, RESUME }

private data class RouteInstruction(
    val text: String,
    val guideIndex: Int?,
    val distanceM: Int? = null,
    val passageConfirmation: Boolean = false,
)

private data class CompletedRouteSpeechCue(
    val guideIndex: Int?,
    val distanceBand: Int,
    val directionOnly: Boolean,
    val facingDirection: RouteFacingDirection?,
    val clockHour: Int?,
) {
    /** A clock number appearing or disappearing alone does not restart the completed cadence. */
    fun covers(other: CompletedRouteSpeechCue): Boolean =
        guideIndex == other.guideIndex && distanceBand == other.distanceBand &&
            directionOnly == other.directionOnly && facingDirection == other.facingDirection &&
            (clockHour == other.clockHour || clockHour == null || other.clockHour == null)
}

private data class RouteGuidanceContext(
    val routeRevision: Long,
    val rawLocation: TrustedLocation,
    val guidanceLocation: TrustedLocation,
    val instruction: RouteInstruction,
    val alignment: RouteAlignmentSelection,
    val positionEstimateUncertain: Boolean,
    val offRoute: Boolean,
    val stepProgressConsistent: Boolean?,
    val directionOnly: Boolean = false,
    /** Observed speed between the last two samples (GNSS beyond uncertainty, or steps); null if none. */
    val recentMovementSpeedMps: Double? = null,
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
    // S09-52: wrapped longitude differences (shared convention); the route projection scale is unchanged.
    val latScale = 111_320.0
    val lonScale = 111_320.0 * kotlin.math.cos(Math.toRadians(location.latitude))
    val wrap = kr.co.hanium.dreamup.walksafe.navigation.positioning.LocalGeodesy::wrapLongitudeDegrees
    val ax = wrap(start.longitude - location.longitude) * lonScale
    val ay = (start.latitude - location.latitude) * latScale
    val bx = wrap(end.longitude - location.longitude) * lonScale
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

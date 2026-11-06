package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteCorridorMatchResult
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteCorridorMatcher
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteCorridorMatcherConfig
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteHeadingEstimate
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchQuality
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchReason
import kr.co.hanium.dreamup.walksafe.navigation.positioning.LocalGeodesy
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.asin
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
// Same bounded local approach as the backend's accepted provider-origin endpoint offset.
internal const val MAXIMUM_ROUTE_APPROACH_DISTANCE_M = 100.0
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
// A small comparison band around a nominal clock's rounding boundary, not sensor accuracy.
// Keep the current measured clock; continuity and an offered nominal START may cross its edge.
// S08-18-F1: the turn sentence while the bearing is held near an unpassed guide. It is relative to
// the route, like the ordinary "경로 기준으로 ..." turn, and carries no clock or facing claim.
private const val NEAR_PENDING_TURN_PREFIX = "안내 지점 부근입니다. 경로 기준으로 "
// S08-20 (nav-F9): beside the route the only action is one that does not move the user. It asks for
// guidance again ("다음 안내 알려줘"), which needs no pending decision, like the unknown-facing request.
private const val BESIDE_ROUTE_ACTION = "잠시 멈춰 주변 보행로를 확인한 뒤 다시 안내를 요청해 주세요."
// S07-37: an overlapping accuracy area is possible proximity, not a known entrance or arrival.
private const val POSSIBLE_DESTINATION_APPROACH = "목적지 근처일 수 있습니다. 출입구와 최종 접근을 실제로 확인해 주세요."

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
     * (measurement time) before automatic arrival completion can begin.
     */
    val minimumArrivalDwellMs: Long = 3_000L,
    /** Base confirmation window; the Android walking composition selects three consecutive windows. */
    val directionRecheckGraceMs: Long = 1_500L,
    /** Shorter silence after a delivered clock direction; recovery/other notices keep their cadence. */
    val facingGuidanceIntervalMs: Long = 2_000L,
    val periodicFacingGuidanceIntervalMs: Long = 5_000L,
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
    /** S08-30: optional non-relative explanation, offered only after this action completes. */
    val infoUtterance: RouteInformationUtterance? = null,
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

/** Current geometric reference only; this is never matched progress or permission to output. */
data class RouteApproachTarget(
    val point: RoutePoint,
    val distanceM: Double,
    val observedAtElapsedRealtimeMs: Long,
    val routeRevision: Long,
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
    private var routeGeometry: RouteGeometryCache? = null
    private var routeRevision = 0L
    private var decisionRevision = 0L
    private var activeRouteId: String? = null
    private var initialRouteJoinPending = false
    private var requestedDestination: RoutePoint? = null
    private var requestedDestinationCoordinateEvidence: DestinationCoordinateEvidence? = null
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
    private var offeredCompletion: CompletedRouteSpeechCue? = null
    private var directionRecheckEpisode: DirectionRecheckEpisode? = null
    private var lastGoodDirection: RetainedRouteDirection? = null
    private var recoveredDirectionGracePacket: RecoveredDirectionGracePacket? = null
    private var normalDirectionGracePacket: NormalDirectionGracePacket? = null
    private var freshDirectionStartAuthorization: FreshDirectionStartAuthorization? = null
    private var firstDirectionFaultReceiptMs: Long? = null
    private var directionEpisodeSequence = 0L
    private var lastDirectionCheckedAtMs: Long? = null
    private var latestDirectionUnavailableReason: RouteCompassHeadingReason? = null
    private var offeredDestinationNotice = false
    private var offeredInitialJoinReference = false
    private var offeredInitialJoinContext: RouteGuidanceContext? = null
    private var offeredGuidanceContext: RouteGuidanceContext? = null
    // The last offered action's safety fact outlives a transient speech-cue invalidation.
    private var lastOfferedBesideRoute: Boolean? = null
    private val facingContinuityGate = RouteFacingContinuityGate()
    private var offeredPositionEstimateUncertain: Boolean? = null
    private var pendingSpeechCue: RouteSpeechCueToken? = null
    private var failedSpeechCue: RouteSpeechCueToken? = null
    private var completedSpeechCue: RouteSpeechCueToken? = null
    private var offeredInformationUtterance: RouteInformationUtterance? = null
    private var pendingInformationUtterance: RouteInformationUtterance? = null
    private var completedInformationActionCue: RouteSpeechCueToken? = null
    private var informationActivatedAtMs: Long? = null
    private var reservedInformationCue: RouteInformationCueToken? = null
    private var informationSpeechToCancel: RouteInformationCueToken? = null
    private var expiredContextActionSpeechToCancel = false
    private val completedSpeechCues = mutableSetOf<CompletedRouteSpeechCue>()
    private var lastCompletedInitialJoinReference: CompletedRouteSpeechCue? = null
    private var lastDeliveredDirectionCue: CompletedRouteSpeechCue? = null
    private var routeGeometryRecovery: RouteGeometryRecoveryEpisode? = null
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

    @Synchronized
    fun setRoute(
        route: WalkingRoute,
        destination: RoutePoint? = null,
        origin: TrustedLocation? = null,
        destinationCoordinateEvidence: DestinationCoordinateEvidence? = null,
        /** An initial explicit route can wait for an accepted match without declaring deviation. */
        waitForInitialRouteJoin: Boolean = false,
        /** False when the installed-route output owner already announces the start and total distance. */
        announceStartSummary: Boolean = true,
    ) {
        routeRevision += 1L
        expiredContextActionSpeechToCancel = false
        // Own the installed lists so external replacements cannot leave cached measurements,
        // guides and the route shape describing different install revisions.
        val installedRoute = route.copy(
            polyline = route.polyline.toList(),
            guidePoints = route.guidePoints.toList(),
            steps = route.steps.map { it.copy(points = it.points.toList()) },
        )
        this.route = installedRoute
        initialRouteJoinPending = waitForInitialRouteJoin
        routeGeometry = RouteGeometryCache(installedRoute.polyline)
        activeRouteId = installedRoute.providerRouteId?.trim()?.takeIf(String::isNotEmpty) ?: installedRoute.localRouteFingerprint()
        requestedDestination = destination
        requestedDestinationCoordinateEvidence = destinationCoordinateEvidence
            ?.takeIf { destination != null }
            ?.let { it.copy(candidates = it.candidates.toList()) }
        routeAlignmentSelector.reset(origin)
        routeAlignmentSelector.installGeometry(requireNotNull(routeGeometry))
        nextGuideIndex = 0
        passageConfirmationGuideIndex = null
        resetPassedTurnEvidence()
        lastGuidanceAtMs = null
        lastDeliveredDirectionCue = null
        routeGeometryRecovery = null
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
        lastOfferedBesideRoute = null
        positioningEvidenceInterrupted = false
        previousFilteredPosition = null
        latestUpdateNowMs = null
        routeCorridorMatcher.reset()
        latestRouteBearingDeg = null
        guideProgressDistancesM = resolveGuideProgressDistances(installedRoute)
        includeRouteStartSummary = announceStartSummary && installedRoute.guidePoints.withIndex().any { (index, guide) ->
            isDepartureGuide(guide, guideProgressDistancesM.getOrNull(index))
        }
        completedSpeechCues.clear()
        lastCompletedInitialJoinReference = null
        resetClockSector()
        resetFacingSourceGate()
        lastStepProgressM = null
        stationaryJumpAnchor = null
        lastAcceptedProgress = null
        realigningAfterGap = false
        invalidateSpeechCue()
    }

    /** Only the installed destination owns these provider labels; they do not prove arrival. */
    @Synchronized
    fun destinationCoordinateEvidenceFor(destination: RoutePoint): DestinationCoordinateEvidence? {
        val installedDestination = requestedDestination ?: return null
        if (installedDestination != destination) return null
        return requestedDestinationCoordinateEvidence?.let { it.copy(candidates = it.candidates.toList()) }
    }

    @Synchronized
    fun clear() {
        routeRevision += 1L
        expiredContextActionSpeechToCancel = false
        route = null
        initialRouteJoinPending = false
        routeGeometry = null
        activeRouteId = null
        requestedDestination = null
        requestedDestinationCoordinateEvidence = null
        routeAlignmentSelector.reset()
        routeAlignmentSelector.clearGeometry()
        nextGuideIndex = 0
        resetPassedTurnEvidence()
        lastGuidanceAtMs = null
        lastDeliveredDirectionCue = null
        routeGeometryRecovery = null
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
        lastOfferedBesideRoute = null
        positioningEvidenceInterrupted = false
        previousFilteredPosition = null
        latestUpdateNowMs = null
        routeCorridorMatcher.reset()
        latestRouteBearingDeg = null
        guideProgressDistancesM = emptyList()
        includeRouteStartSummary = false
        completedSpeechCues.clear()
        lastCompletedInitialJoinReference = null
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
            initialRouteJoinPending || positioningEvidenceInterrupted ||
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

    /** Scope is the current install revision; only a new accepted position completes this wait. */
    @Synchronized
    fun isWaitingForInitialRouteJoin(): Boolean = initialRouteJoinPending

    /** Read-only UI observation; callers retain their live runtime, GPS and output admission. */
    @Synchronized
    fun currentRouteApproachTarget(nowMs: Long): RouteApproachTarget? {
        if (!initialRouteJoinPending) return null
        val currentRoute = route ?: return null
        val context = latestGuidanceContext?.takeIf {
            it.initialJoinReference && isGuidanceContextCurrent(currentRoute, it, nowMs)
        } ?: return null
        val target = context.alignment.target ?: return null
        return RouteApproachTarget(target,
            haversineMeters(context.guidanceLocation.latitude, context.guidanceLocation.longitude,
                target.latitude, target.longitude),
            minOf(context.rawLocation.elapsedRealtimeMs, context.guidanceLocation.elapsedRealtimeMs), routeRevision)
    }

    /** Latest evaluated direction is still unconfirmed, even when speech cadence hides its cue. */
    @Synchronized
    fun isDirectionRecheckPending(): Boolean = directionRecheckEpisode != null

    @Synchronized
    fun currentDirectionGraceSnapshot(): RouteDirectionGraceSnapshot? {
        val episode = directionRecheckEpisode
        val good = episode?.retained ?: lastGoodDirection
        if (episode == null && good == null) return null
        return RouteDirectionGraceSnapshot(
            mode = when {
                episode == null -> RouteDirectionGraceMode.NORMAL
                episode.lastCheckedAtMs >= episode.deadlineMs -> RouteDirectionGraceMode.PROBLEM
                episode.retained != null && episode.retentionUsable -> RouteDirectionGraceMode.GRACE_RETAINED
                else -> RouteDirectionGraceMode.RECHECK_WAIT
            },
            episodeId = episode?.id,
            onsetElapsedRealtimeMs = episode?.startedAtMs,
            deadlineElapsedRealtimeMs = episode?.deadlineMs,
            reason = latestDirectionUnavailableReason,
            retainedObservedAtElapsedRealtimeMs = good?.observation?.observedAtElapsedRealtimeMs,
            retainedSource = good?.observation?.source,
            retainedReportedHeadingAccuracyDegrees = good?.observation?.reportedHeadingAccuracyDegrees,
            authorizedCueRevision = when {
                freshDirectionStartAuthorization?.token == pendingSpeechCue -> freshDirectionStartAuthorization?.token?.cueRevision
                normalDirectionGracePacket?.token == pendingSpeechCue -> normalDirectionGracePacket?.token?.cueRevision
                recoveredDirectionGracePacket?.token == pendingSpeechCue -> recoveredDirectionGracePacket?.token?.cueRevision
                episode != null && pendingSpeechCue?.directionGraceEpisodeId == episode.id -> pendingSpeechCue?.cueRevision
                else -> null
            },
            authorizationKind = when {
                freshDirectionStartAuthorization != null -> RouteDirectionGraceAuthorization.NORMAL_FRESH_REVALIDATED
                normalDirectionGracePacket != null -> RouteDirectionGraceAuthorization.NORMAL_PENDING
                recoveredDirectionGracePacket != null -> RouteDirectionGraceAuthorization.RECOVERED_GRACE_PACKET
                episode != null && pendingSpeechCue?.directionGraceEpisodeId == episode.id ->
                    RouteDirectionGraceAuthorization.GRACE_PACKET
                else -> null
            },
            authorizationEpisodeId = normalDirectionGracePacket?.episodeId ?: recoveredDirectionGracePacket?.episodeId ?:
                pendingSpeechCue?.directionGraceEpisodeId,
            authorizationDeadlineElapsedRealtimeMs = freshDirectionStartAuthorization?.validUntilMs ?: recoveredDirectionGracePacket?.deadlineMs ?:
                episode?.deadlineMs?.takeIf { normalDirectionGracePacket != null || pendingSpeechCue?.directionGraceEpisodeId == episode.id },
            authorizationObservedAtElapsedRealtimeMs = freshDirectionStartAuthorization?.observation?.observedAtElapsedRealtimeMs,
        )
    }

    /** Fresh measured reversal is hard; loss of precision or a coarse UNKNOWN alone is soft. */
    @Synchronized
    fun hasHardInvalidatedDirectionClaim(nowMs: Long, observation: RouteFacingObservation?): Boolean {
        val currentRoute = route ?: return false
        val context = latestGuidanceContext ?: return false
        if (context.routeRevision != routeRevision || nowMs < 0L || latestUpdateNowMs?.let { nowMs < it } == true) return true
        // Expired position/bearing context revokes output through the normal current-context
        // guard. It is not a measured body reversal and must not restart a sensor fault timer.
        if (!isGuidanceContextCurrent(currentRoute, context, nowMs)) return false
        if (!RouteFacingGuidance.isUsableObservation(observation, nowMs)) return false
        val measured = requireNotNull(observation)
        if (measured.magneticFieldSuspect || measured.headingTrend?.largeTurnPending == true) return false
        val comparison = (lastGoodDirection ?: directionRecheckEpisode?.retained)?.takeIf { it.matches(context) }
        val rawChange = comparison?.let {
            abs((measured.degreesTrueNorth - it.observation.degreesTrueNorth + 540.0) % 360.0 - 180.0)
        }
        if (rawChange != null && rawChange < 135.0 &&
            (facingSourceChanged(measured) || facingContinuityGate.facingSourceSwitchPending ||
                (measured.headingTrend?.let { it.sampleCount < 2 && !it.followsActualLargeTurn } == true))
        ) return false
        // Freshness was checked against the real receipt above. Judge a measured reversal at
        // its original sample time; extrapolating a fast turn must not hide that reversal.
        val fresh = RouteFacingGuidance.evaluateRaw(context.alignment.bearingDegrees, observation,
            measured.observedAtElapsedRealtimeMs,
            context.alignment.bearingUncertaintyDegrees)
        // An adjacent GOOD may update the retained comparison while an older command is still
        // playing. A real reversal must also retire that exact command, not only the latest GOOD.
        val pendingCommandReversed = offeredSpeechCue?.takeIf {
            it == pendingSpeechCue && it.requiresFacingEvidence &&
                measured.observedAtElapsedRealtimeMs > (it.facingObservedAtElapsedRealtimeMs ?: Long.MAX_VALUE)
        }?.let { offeredFacing?.commandReversedBy(fresh) == true || fresh.direction == offeredFacing?.direction?.reversed() } == true
        val previous = comparison
        val supportedLargeTurn = measured.headingTrend?.let {
            (it.followsActualLargeTurn || (it.sampleCount >= 2 && it.observedHeadingSpanDegrees <= 20.0)) &&
                previous != null && measured.observedAtElapsedRealtimeMs > previous.observation.observedAtElapsedRealtimeMs &&
                abs((it.representativeDegreesTrueNorth - previous.observation.degreesTrueNorth + 540.0) % 360.0 - 180.0) >= 60.0 - 0.000001
        } == true
        return supportedLargeTurn || pendingCommandReversed || (previous != null &&
            measured.observedAtElapsedRealtimeMs > previous.observation.observedAtElapsedRealtimeMs &&
            (fresh.direction == previous.facing.direction.reversed() || previous.facing.commandReversedBy(fresh)))
    }

    /** Retire claims before an owner/hard-fault cancellation can deliver a late DONE callback. */
    @Synchronized
    fun invalidateDirectionClaim() {
        resetDirectionRecheckGrace()
        resetClockSector()
        invalidateSpeechCue()
    }

    /** A replaced audio actuator retires its output, while the same sensor fault keeps its age. */
    @Synchronized
    fun invalidateDirectionSpeechClaims() {
        clearDirectionSpeechAuthorizations()
        resetClockSector()
        invalidateSpeechCue()
    }

    /** The owner-admitted sensor event records time only; it never consumes samples or authorizes output. */
    @Synchronized
    fun recordFirstDirectionFaultReceipt(receivedAtMs: Long, expectedRouteRevision: Long?, isOwnerCurrent: () -> Boolean) {
        if (expectedRouteRevision != routeRevision || !isOwnerCurrent()) return
        val good = lastGoodDirection ?: return
        if (receivedAtMs < good.observation.observedAtElapsedRealtimeMs || receivedAtMs > good.normalValidUntilMs)
            return
        firstDirectionFaultReceiptMs = minOf(firstDirectionFaultReceiptMs ?: receivedAtMs, receivedAtMs)
    }

    /**
     * A sensor listener may classify loss before a later good observation replaces it. This read
     * neither consumes a confirmation sample nor changes speech/context ownership. The UI actor
     * must still evaluate the retained event through retryGuidance before changing any output.
     */
    @Synchronized
    fun hasInvalidatedDirectionClaim(nowMs: Long, facingObservation: RouteFacingObservation?): Boolean {
        val token = offeredSpeechCue ?: return false
        val offered = offeredFacing ?: return false
        val currentRoute = route ?: return false
        if (!token.requiresFacingEvidence || !offered.hasClockReference ||
            token.routeRevision != routeRevision || token.routeId == null || token.routeId != activeRouteId
        ) return false
        val context = latestGuidanceContext ?: return true
        if (!isGuidanceContextCurrent(currentRoute, context, nowMs)) return true
        val evaluated = RouteFacingGuidance.evaluate(
            context.alignment.bearingDegrees, facingObservation, nowMs,
            context.alignment.bearingUncertaintyDegrees,
        )
        // A read cannot count this sample as the second confirmation of a new source. It only
        // reports whether the actor's already accepted source can support the offered claim.
        val sourceUnconfirmed = facingObservation?.let {
            facingSourceChanged(it) || facingContinuityGate.facingSourceSwitchPending ||
                (it.sensorQuality == RouteFacingSensorQuality.CALIBRATED_WITHOUT_MOTION_CHECK &&
                    !hasConfirmedFacingDirection(evaluated)) ||
                (evaluated.clockReferenceKind == RouteClockReferenceKind.NOMINAL_TREND_CLOCK &&
                    !hasConfirmedFacingDirection(evaluated))
        } == true
        val measured = if (sourceUnconfirmed) evaluated.copy(direction = RouteFacingDirection.UNKNOWN) else evaluated
        // The original token's START lease may have elapsed while playback continues. Only the
        // new observation's actual age/precision and meaning are relevant to this loss check.
        return offered.losesEvidenceTo(measured, measured)
    }

    @Synchronized
    fun pendingUserDecision(): RouteNavigatorUserDecision? {
        expirePendingDecision()
        return pendingDecision
    }

    /** Expiring a question does not authorize recovery from its latched deviation. */
    @Synchronized
    fun canResumeAfterPositionRecovery(): Boolean = route != null && !confirmedDeviationLatched &&
        (!deviationSuspectedLatched || locationRecheckAuthorized || routeGeometryRecovery != null)

    /** An untrusted fix breaks evidence; test speech can resume only from a new actual fix. */
    @Synchronized
    fun onUntrustedLocation(): RouteNavigatorUpdate? {
        beginAutomaticPositionRecovery()
        resetDirectionRecheckGrace()
        routeAlignmentSelector.reset()
        resetRewindEvidence()
        interruptPassedTurnEvidence()
        arrivalDepartureSampleCount = 0
        arrivalDepartureSampleAtMs = null
        latestGuidanceContext = null
        lastOfferedBesideRoute = null
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
        if (initialRouteJoinPending) return waitingForRouteJoinUpdate()
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
            instruction = if (routeGeometryRecovery != null)
                "현재 위치를 다시 확인하고 있습니다. 위치가 확인되면 길 안내를 자동으로 이어갑니다."
            else "현재 위치 정확도를 신뢰할 수 없어 오래된 방향 안내를 중지했습니다. " +
                "위치 다시 확인을 선택할 때까지 다음 방향 안내를 하지 않습니다.",
        )
    }

    /**
     * Breaks evidence that must be consecutive without changing the installed route, stable
     * progress, or any suspected/confirmed deviation latch.
     */
    @Synchronized
    fun onPositioningEvidenceInterrupted() {
        beginAutomaticPositionRecovery()
        resetDirectionRecheckGrace()
        routeAlignmentSelector.reset()
        resetRewindEvidence()
        interruptPassedTurnEvidence()
        arrivalDepartureSampleCount = 0
        arrivalDepartureSampleAtMs = null
        latestGuidanceContext = null
        lastOfferedBesideRoute = null
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

    /** Speech remains reserved until its actual terminal callback retires this exact cue. */
    @Synchronized
    internal fun hasPendingInstruction(): Boolean = pendingSpeechCue != null

    @Synchronized
    internal fun hasPendingInformation(): Boolean = reservedInformationCue != null

    internal val instructionRetryIntervalMs: Long get() = config.speechRetryIntervalMs.coerceAtLeast(0L)

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
            latestLocationSample?.navigationOnly != true &&
                route != null && !initialRouteJoinPending && !positioningEvidenceInterrupted && latestRouteMatchUsableForGuidance &&
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
            latestLocationSample?.navigationOnly == true ||
            initialRouteJoinPending ||
            positioningEvidenceInterrupted ||
            !latestRouteMatchUsableForGuidance ||
            latestRouteMatchInputElapsedRealtimeMs != inputElapsedRealtimeMs ||
            latestRouteMatchRouteRevision != routeRevision
        ) return null
        return latestRouteMatch
    }

    /** Speech readiness of a coarse raw reference, never a route-progress or arrival grant. */
    @Synchronized
    fun hasCurrentNavigationOnlyReference(nowMs: Long): Boolean {
        val sample = latestLocationSample ?: return false
        val context = latestGuidanceContext ?: return false
        return sample.navigationOnly && route != null && context.routeRevision == routeRevision &&
            !initialRouteJoinPending && !offRouteGuidanceSuspended && pendingDecision == null &&
            latestGuidanceSourceAllowed && sample.hasCurrentCoordinates(nowMs) &&
            context.rawLocation.hasCurrentCoordinates(nowMs) && context.guidanceLocation.hasCurrentCoordinates(nowMs)
    }

    /** UI callers avoid a platform-clock read when there is no coarse reference. */
    @Synchronized
    fun hasCurrentNavigationOnlyReference(elapsedRealtimeMs: () -> Long): Boolean =
        latestLocationSample?.navigationOnly == true && hasCurrentNavigationOnlyReference(elapsedRealtimeMs())

    @Synchronized
    fun currentProjection(location: TrustedLocation): ActiveRouteProjection? {
        if (
            location.navigationOnly || latestLocationSample?.navigationOnly == true ||
            initialRouteJoinPending || positioningEvidenceInterrupted ||
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
        if (initialRouteJoinPending) return null
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
        latestGuidanceContext?.takeIf {
            it.instruction.destinationApproach && it.rawLocation.hasSameObservationAs(location) &&
                location.hasCurrentCoordinates(latestUpdateNowMs ?: return null)
        }?.let { return it.instruction.text }
        return instructionForCurrentGuide(currentRoute, location).text
    }

    /** Reserves a current cue before dispatch; reservation is not successful delivery. */
    @Synchronized
    fun reserveInstruction(update: RouteNavigatorUpdate): Boolean {
        val token = update.speechCueToken ?: return false
        if (update.instruction == null || token != offeredSpeechCue || token == completedSpeechCue ||
            pendingSpeechCue != null
        ) return false
        if (token.routeRevision != routeRevision || token.routeId == null || token.routeId != activeRouteId ||
            !canDeliverRouteGuidance()
        ) return false
        clearInformation()
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
        recoveredDirectionGracePacket = null
        normalDirectionGracePacket = null
        freshDirectionStartAuthorization = null
        // A failed delivery did not satisfy its manual request. Preserve a newer request too.
        manualGuidancePending = manualGuidancePending || reservedManualGuidance
        reservedManualGuidance = false
        failedSpeechCue = token
        speechRetryNotBeforeMs = failedAtMs + config.speechRetryIntervalMs.coerceAtLeast(0L)
    }

    /** Queue/START guards may admit a soft episode before its queued sensor UI update runs. */
    @Synchronized
    fun prepareRetainedDirectionStart(
        update: RouteNavigatorUpdate,
        nowMs: Long,
        observation: RouteFacingObservation?,
    ): Boolean {
        val token = update.speechCueToken ?: return false
        if (token != offeredSpeechCue || token != pendingSpeechCue || !token.requiresFacingEvidence ||
            hasHardInvalidatedDirectionClaim(nowMs, observation)
        ) return false
        retryGuidance(nowMs, observation)
        return token == offeredSpeechCue && token == pendingSpeechCue && directionRecheckEpisode?.let {
            nowMs < it.deadlineMs && it.retained?.facingFor(currentGuidanceContext(nowMs) ?: return false) != null
        } == true
    }

    /** A new actual GOOD can authorize this exact NORMAL packet without changing its old lease. */
    @Synchronized
    fun prepareFreshDirectionStart(update: RouteNavigatorUpdate, nowMs: Long, observation: RouteFacingObservation?): Boolean {
        val token = update.speechCueToken ?: return false
        if (token != pendingSpeechCue || token != offeredSpeechCue || token == failedSpeechCue ||
            token == completedSpeechCue || !token.requiresFacingEvidence || token.directionGraceEpisodeId != null ||
            directionRecheckEpisode != null || firstDirectionFaultReceiptMs != null ||
            !RouteFacingGuidance.isUsableObservation(observation, nowMs) ||
            requireNotNull(observation).observedAtElapsedRealtimeMs <= (token.facingObservedAtElapsedRealtimeMs ?: Long.MAX_VALUE) ||
            hasHardInvalidatedDirectionClaim(nowMs, observation)
        ) return false
        val current = currentGuidanceContext(nowMs) ?: return false
        if (!offeredContextStillSupports(current)) return false
        val fresh = gateFacingSource(RouteFacingGuidance.evaluate(current.alignment.bearingDegrees,
            observation, nowMs, current.alignment.bearingUncertaintyDegrees), observation)
        if (!fresh.hasClockReference || offeredFacing?.fullySupportedBy(fresh) != true) return false
        freshDirectionStartAuthorization = FreshDirectionStartAuthorization(token, observation, nowMs,
            requireNotNull(RouteFacingGuidance.observationValidUntilElapsedRealtimeMs(observation, nowMs)))
        return true
    }

    /** A sensor START rejection may issue a new GRACE packet; a real transport failure keeps backoff. */
    @Synchronized
    fun releaseInstructionAfterDirectionStartRejection(update: RouteNavigatorUpdate, failedAtMs: Long) {
        val token = update.speechCueToken ?: return
        if (token != pendingSpeechCue) return
        val mayRetryRetained = directionRecheckEpisode?.let {
            failedAtMs < it.deadlineMs && it.retained != null
        } == true
        releaseInstruction(update, failedAtMs)
        if (mayRetryRetained && failedSpeechCue == token) speechRetryNotBeforeMs = null
    }

    /** Only the actual terminal completion consumes a cue, never an enqueue result. */
    @Synchronized
    fun isInstructionCompletionCurrent(update: RouteNavigatorUpdate, nowMs: Long): Boolean {
        val token = update.speechCueToken ?: return false
        if (token != pendingSpeechCue || token != offeredSpeechCue || token == failedSpeechCue ||
            token == completedSpeechCue || token.routeRevision != routeRevision || token.routeId != activeRouteId ||
            !canDeliverRouteGuidance()
        ) return false
        return !token.requiresFacingEvidence || (directionRecheckEpisode?.let { nowMs < it.deadlineMs } != false &&
            routeGeometryRecovery?.let { nowMs < it.deadlineMs } != false)
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
            token.routeRevision != routeRevision || token.routeId == null || token.routeId != activeRouteId ||
            !canDeliverRouteGuidance()
        ) return
        // DONE is delivery, never a measurement or an extension of the sensor grace.
        if (token.requiresFacingEvidence && (directionRecheckEpisode?.let { spokenAtMs >= it.deadlineMs } == true ||
                routeGeometryRecovery?.let { spokenAtMs >= it.deadlineMs } == true)
        ) return
        // Only a matching reserved action can enable its captured information. An enqueue,
        // rejected ACK or a late completion from an old action cannot enable another cue.
        if (pendingSpeechCue == token) {
            pendingInformationUtterance = offeredInformationUtterance
            completedInformationActionCue = token.takeIf { pendingInformationUtterance != null }
            informationActivatedAtMs = spokenAtMs.takeIf { pendingInformationUtterance != null }
            reservedInformationCue = null
        }
        val completedCue = offeredCompletion ?: return
        completedSpeechCue = token
        completedSpeechCues += completedCue
        lastDeliveredDirectionCue = completedCue
        if (offeredInitialJoinReference && pendingSpeechCue == token) {
            lastCompletedInitialJoinReference = completedCue
        }
        if (pendingSpeechCue == token) {
            pendingSpeechCue = null
            recoveredDirectionGracePacket = null
            normalDirectionGracePacket = null
            freshDirectionStartAuthorization = null
            reservedManualGuidance = false
        }
        speechRetryNotBeforeMs = null
        lastGuidanceAtMs = spokenAtMs
        if (!token.directionOnly) includeRouteStartSummary = false
    }

    /** One attempt after action completion; rejected or interrupted information is never retried. */
    @Synchronized
    fun reserveInformation(information: RouteInformationUtterance, nowMs: Long): Boolean {
        if (pendingInformationUtterance != information || reservedInformationCue != null) return false
        if (!isInformationCurrent(information, nowMs)) {
            finishInformation(information)
            return false
        }
        reservedInformationCue = information.cueToken
        return true
    }

    /** Route/location/notice evidence remains required, independently of the action's facing age. */
    @Synchronized
    fun isInformationCurrent(information: RouteInformationUtterance, nowMs: Long): Boolean {
        if (pendingInformationUtterance != information) return false
        val token = information.cueToken
        val action = completedInformationActionCue ?: return false
        val activatedAt = informationActivatedAtMs ?: return false
        if (activatedAt < 0L || nowMs < activatedAt || token.routeRevision != routeRevision || token.routeId != activeRouteId ||
            token.routeRevision != action.routeRevision || token.routeId != action.routeId ||
            token.actionCueRevision != action.cueRevision || token.guideIndex != action.guideIndex ||
            action.directionOnly
        ) return false
        val context = currentGuidanceContext(nowMs) ?: return false
        return !context.directionOnly && context.instruction.guideIndex == token.guideIndex &&
            context.positionEstimateUncertain == token.positionEstimateUncertain
    }

    /** Completion, suppression and cancellation all consume only this optional information. */
    @Synchronized
    fun finishInformation(information: RouteInformationUtterance) {
        if (informationSpeechToCancel == information.cueToken) informationSpeechToCancel = null
        if (pendingInformationUtterance == information) clearInformation()
    }

    private fun clearInformation(cancelSpeech: Boolean = false) {
        if (cancelSpeech && reservedInformationCue != null) informationSpeechToCancel = reservedInformationCue
        pendingInformationUtterance = null
        completedInformationActionCue = null
        informationActivatedAtMs = null
        reservedInformationCue = null
    }

    /** Canonical invalidation alone cannot stop ACTION/INFO that already entered native TTS. */
    private fun RouteNavigatorUpdate.withInformationCancellation(): RouteNavigatorUpdate {
        if (informationSpeechToCancel == null && !expiredContextActionSpeechToCancel) return this
        informationSpeechToCancel = null
        expiredContextActionSpeechToCancel = false
        return copy(cancelStaleNavigationSpeech = true)
    }

    private fun informationCancellationUpdateOrNull(): RouteNavigatorUpdate? {
        if (informationSpeechToCancel == null && !expiredContextActionSpeechToCancel) return null
        val reason = if (expiredContextActionSpeechToCancel) "stale_route_context" else "stale_information_context"
        return RouteNavigatorUpdate(null, false, confirmedDeviationLatched, false,
            reason).withInformationCancellation()
    }

    @Synchronized
    fun acknowledgeCurrentInstruction(spokenAtMs: Long) {
        if (route == null || initialRouteJoinPending || offRouteGuidanceSuspended ||
            (allowDegradedRouteGuidance && !latestGuidanceSourceAllowed) ||
            (!allowDegradedRouteGuidance && positioningEvidenceInterrupted)
        ) return
        lastGuidanceAtMs = spokenAtMs
    }

    private fun canDeliverRouteGuidance(): Boolean =
        (!initialRouteJoinPending || latestGuidanceContext?.initialJoinReference == true) &&
            ((latestGuidanceSourceAllowed && (allowDegradedRouteGuidance || !positioningEvidenceInterrupted)) ||
                latestGuidanceContext?.directionOnly == true) &&
            !offRouteGuidanceSuspended && pendingDecision == null

    /** The output owner must call this with fresh sensor evidence immediately before playback. */
    @Synchronized
    fun isInstructionStartContextCurrent(update: RouteNavigatorUpdate, nowMs: Long): Boolean {
        val token = update.speechCueToken ?: return false
        return token == pendingSpeechCue && token == offeredSpeechCue &&
            currentGuidanceContext(nowMs)?.let(::offeredContextStillSupports) == true
    }

    /** Live direction/ownership checks also used after START; spoken distance is checked separately. */
    @Synchronized
    fun isInstructionCurrent(
        update: RouteNavigatorUpdate,
        nowMs: Long,
        facingObservation: RouteFacingObservation?,
    ): Boolean {
        val token = update.speechCueToken ?: return false
        if (token != offeredSpeechCue || token == failedSpeechCue || token == completedSpeechCue ||
            token.routeRevision != routeRevision || token.routeId == null || token.routeId != activeRouteId ||
            !(token.hasCurrentFacingEvidence(nowMs) || hasCurrentNormalDirectionGracePacket(token, nowMs) ||
                hasCurrentDirectionGrace(token, nowMs) ||
                hasRecoveredDirectionGracePacket(token, nowMs) || hasFreshDirectionStartAuthorization(token, nowMs))
        ) return false
        val context = currentGuidanceContext(nowMs) ?: return false
        if (offeredInitialJoinReference != context.initialJoinReference ||
            (context.initialJoinReference && token.distanceBand != routeGuidanceDistanceBand(context.instruction.distanceM) &&
                !initialJoinDistanceStillSupported(context))
        ) return false
        // This sentence has no relative direction or movement claim. All route, decision,
        // location-age and context checks above still apply; compass rotation cannot falsify it.
        if (context.instruction.destinationNotice) return offeredDestinationNotice
        if (context.instruction.passageConfirmation) {
            return !token.requiresFacingEvidence && offeredFacing == null && !offeredDestinationNotice
        }
        if (hasFreshDirectionStartAuthorization(token, nowMs)) {
            // A proof is never a cached permission to START against a newer BAD or different cue.
            if (!offeredContextStillSupports(context) || hasHardInvalidatedDirectionClaim(nowMs, facingObservation) ||
                !RouteFacingGuidance.isUsableObservation(facingObservation, nowMs) ||
                requireNotNull(facingObservation).observedAtElapsedRealtimeMs <
                    requireNotNull(freshDirectionStartAuthorization).observation.observedAtElapsedRealtimeMs
            ) return false
            val live = gateFacingSource(RouteFacingGuidance.evaluate(context.alignment.bearingDegrees,
                facingObservation, nowMs, context.alignment.bearingUncertaintyDegrees), facingObservation)
            return live.hasClockReference && offeredFacing?.fullySupportedBy(live) == true
        }
        if (hasCurrentNormalDirectionGracePacket(token, nowMs)) {
            if (hasHardInvalidatedDirectionClaim(nowMs, facingObservation)) return false
            val live = gateFacingSource(RouteFacingGuidance.evaluate(context.alignment.bearingDegrees,
                facingObservation, nowMs, context.alignment.bearingUncertaintyDegrees), facingObservation)
            val cached = normalDirectionGracePacket?.retained?.facingFor(context) ?: return false
            return offeredFacing?.fullySupportedBy(
                if (live.hasClockReference && !live.isWeakerNominalClockFor(offeredFacing)) live else cached) == true
        }
        if (hasRecoveredDirectionGracePacket(token, nowMs)) {
            val live = gateFacingSource(RouteFacingGuidance.evaluate(context.alignment.bearingDegrees,
                facingObservation, nowMs, context.alignment.bearingUncertaintyDegrees), facingObservation)
            return !hasHardInvalidatedDirectionClaim(nowMs, facingObservation) &&
                recoveredDirectionGracePacket?.retained?.matches(context) == true &&
                live.hasClockReference && offeredFacing?.fullySupportedBy(live) == true
        }
        if (token.directionGraceEpisodeId != null) {
            if (hasHardInvalidatedDirectionClaim(nowMs, facingObservation)) return false
            val live = gateFacingSource(RouteFacingGuidance.evaluate(context.alignment.bearingDegrees,
                facingObservation, nowMs, context.alignment.bearingUncertaintyDegrees), facingObservation)
            val cached = directionRecheckEpisode?.retained?.facingFor(context) ?: return false
            return hasCurrentDirectionGrace(token, nowMs) &&
                offeredFacing?.fullySupportedBy(
                    if (live.hasClockReference && !live.isWeakerNominalClockFor(offeredFacing)) live else cached) == true
        }
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
        // Keep the measured classification to reject an obsolete recovery context. A direction
        // recheck is no longer current once a precise clock is available. An approximate nominal
        // number also accepts a tiny rounding-boundary change against its original offered angle.
        return offeredFacing?.let {
            val pendingTurn = context.alignment.diagnostic.reason == RouteAlignmentReason.TURN_PASSAGE_PENDING
            it.direction == current.direction && when {
                pendingTurn -> it.clockHour == null || it.clockHour == current.clockHour
                it.clockRange != null -> it.rangeCovers(current)
                else -> (it.clockHour == current.clockHour && current.clockRange == null) ||
                    it.sharesNominalClockBoundaryWith(current)
            }
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

    /** Read-only preflight: explicit requests cannot replace precise consecutive arrival evidence. */
    @Synchronized
    fun canCompleteDetectedArrival(expectedToken: RouteNavigatorDecisionToken): Boolean {
        val openedAt = decisionOpenedAtMs ?: return false
        val now = nowElapsedRealtimeMs()
        val routeId = activeRouteId ?: return false
        return route?.let { !hasUnpassedProtectedGuide(it) } == true && pendingDecision == RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION &&
            expectedToken == RouteNavigatorDecisionToken(routeRevision, decisionRevision, routeId, RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION) &&
            now >= openedAt && now - openedAt < config.decisionTimeoutMs &&
            arrivalSampleCount >= config.arrivalConfirmSamples.coerceAtLeast(1) &&
            arrivalDwellMs() >= config.minimumArrivalDwellMs
    }

    @Synchronized
    fun completeDetectedArrival(
        expectedToken: RouteNavigatorDecisionToken,
        beforeCommit: () -> Boolean = { true },
    ): RouteNavigatorUpdate {
        if (!canCompleteDetectedArrival(expectedToken) || !beforeCommit()) return staleArrivalDecisionUpdate()
        // beforeCommit is memory-only ownership retirement. Do not re-expire the accepted evidence.
        return clearAndCompleteArrival()
    }

    @Synchronized
    fun confirmArrival(): RouteNavigatorUpdate {
        expirePendingDecision()
        if (route?.let(::hasUnpassedProtectedGuide) != false || pendingDecision != RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION) {
            return RouteNavigatorUpdate(null, false, false, false, "arrival_confirmation_missing")
        }
        return clearAndCompleteArrival()
    }

    private fun clearAndCompleteArrival(): RouteNavigatorUpdate {
        clear()
        return RouteNavigatorUpdate(
            instruction = "도착했습니다. 안내를 종료합니다",
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

    /** Legacy state query: a spoken arrival request never creates or replaces arrival evidence. */
    @Synchronized
    fun requestArrivalConfirmation(nowMs: Long): RouteNavigatorUpdate {
        expirePendingDecision()
        if (initialRouteJoinPending) return waitingForRouteJoinUpdate()
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
            instruction = "현재 위치를 확인하고 있습니다. 위치가 확인되면 도착 여부를 자동으로 판단합니다.",
            arrived = false,
            offRoute = false,
            shouldReroute = false,
            reason = "arrival_request_location_unavailable",
        )
        val destination = requestedDestination ?: currentRoute.polyline.last()
        if (!sample.possiblyNearDestination(destination, config.arrivalRadiusM)) {
            return RouteNavigatorUpdate(
                instruction = "현재 위치가 목적지 근처로 확인되지 않았습니다.",
                arrived = false,
                offRoute = false,
                shouldReroute = false,
                reason = "arrival_request_not_near_destination",
            )
        }
        return RouteNavigatorUpdate(
            instruction = "목적지 근처입니다. 위치가 확인되면 도착 안내 후 길안내를 자동으로 종료합니다.",
            arrived = false,
            offRoute = false,
            shouldReroute = false,
            reason = "arrival_waiting_for_trusted_evidence",
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
        ).withInformationCancellation()
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
        ).withInformationCancellation()
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
            uncertaintyKind = location.uncertaintyKind,
            navigationOnly = location.navigationOnly,
        )
        val sample = RouteLocationSample(location, unsnappedPosition)
        val navigationOnlyPosition = location.navigationOnly || unsnappedPosition.navigationOnly
        val latestUpdateTime = latestUpdateNowMs
        val previousSample = latestLocationSample
        val previousGuidanceContext = latestGuidanceContext
        val previousRouteMatch = latestRouteMatch
        val timeRegressed = latestUpdateTime != null && nowMs < latestUpdateTime
        val currentSample = sample.hasCurrentCoordinates(nowMs)
        if (!timeRegressed && currentSample && sample.hasSameObservationAs(previousSample)) {
            // A repeated observation may retry speech, but must not become a new position,
            // arrival/deviation confirmation, or reason to stop an in-progress sentence.
            return retryGuidance(nowMs, facingObservation, facingUnavailableReason) ?: RouteNavigatorUpdate(
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
                (location.elapsedRealtimeMs == previousSample.rawLocation.elapsedRealtimeMs && !location.hasSameObservationAs(previousSample.rawLocation))
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
        if (acceptedMatch == null || navigationOnlyPosition) interruptPassedTurnEvidence()
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
        routeGeometryRecoveryUpdate(currentRoute, sample, nowMs, routeMatch, alignment.diagnostic,
            previousGuidanceContext, previousRouteMatch, facingObservation, facingUnavailableReason)?.let { return it }
        if (initialRouteJoinPending) {
            val newRawAndFilteredObservation = previousSample == null ||
                (location.elapsedRealtimeMs > previousSample.rawLocation.elapsedRealtimeMs &&
                    unsnappedPosition.elapsedRealtimeMs > previousSample.filteredPosition.elapsedRealtimeMs)
            if (acceptedMatch != null && newRawAndFilteredObservation && !navigationOnlyPosition) {
                initialRouteJoinPending = false
            } else {
                // A speech-only projection or direction reference is not initial route joining.
                return initialRouteJoinReferenceUpdate(currentRoute, location, unsnappedPosition,
                    nowMs, facingObservation, facingUnavailableReason, alignment.diagnostic)
            }
        }
        val acceptedGeometricProgressM = acceptedMatch?.geometricProgressM
        val testGuidanceProjection = if (allowDegradedRouteGuidance && !initialRouteJoinPending && acceptedMatch == null &&
            alignment.diagnostic.reason != RouteAlignmentReason.DEPARTURE_CONNECTOR &&
            routeMatch.reason !in setOf(
                RouteMatchReason.INVALID_INPUT, RouteMatchReason.INVALID_ROUTE, RouteMatchReason.STALE_SAMPLE,
                RouteMatchReason.EXPIRED_SAMPLE, RouteMatchReason.FUTURE_SAMPLE,
            )
        ) {
            projectToRoute(
                location = unsnappedPosition.toTrustedLocation(),
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
        if (acceptedGeometricProgressM != null && !navigationOnlyPosition) {
            lastAcceptedProgress = acceptedGeometricProgressM to unsnappedPosition.elapsedRealtimeMs
        }
        if (acceptedGeometricProgressM != null) {
            if (!navigationOnlyPosition) matchedGeometricProgressM = acceptedGeometricProgressM
            latestRouteBearingDeg = acceptedMatch.bearingDeg
        }
        val guidanceProgressM = acceptedGeometricProgressM?.let {
            scaleGeometricProgressToRouteSummary(
                route = currentRoute,
                geometry = requireNotNull(routeGeometry),
                geometricProgressM = it,
            )
        } ?: testGuidanceProjection?.distanceFromStartM
        if (guidanceProgressM != null && !navigationOnlyPosition) {
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
        val guidanceLocation = unsnappedPosition.toTrustedLocation(
            point = acceptedMatch?.matchedPoint ?: unsnappedPosition.point,
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
        val authoritativeArrivalEvidence = !navigationOnlyPosition && nearRouteEnd &&
            !hasUnpassedProtectedGuide(currentRoute) &&
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
        if (!navigationOnlyPosition) observeArrivalDeparture(location, unsnappedPosition, destination)
        else {
            arrivalDepartureSampleCount = 0
            arrivalDepartureSampleAtMs = null
        }
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
                if ((routeMatch.quality == RouteMatchQuality.LOW &&
                        routeMatch.reason == RouteMatchReason.AMBIGUOUS_CANDIDATES) ||
                    (!offRouteCandidate && routeMatch.reason == RouteMatchReason.CORRIDOR_OUTSIDE)
                ) return routeMatchLocationRecheckUpdate(alignment.diagnostic, routeMatch.reason)
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
            if (!allowDegradedRouteGuidance &&
                ((routeMatch.quality == RouteMatchQuality.LOW &&
                    routeMatch.reason == RouteMatchReason.AMBIGUOUS_CANDIDATES) ||
                    routeMatch.reason == RouteMatchReason.CORRIDOR_OUTSIDE)
            ) return routeMatchLocationRecheckUpdate(alignment.diagnostic, routeMatch.reason)
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

        val guideRestored = !navigationOnlyPosition && !stationaryGnssJump && gapRealignment != GapRealignment.HOLD && restorePassedGuides(
            currentRoute, guidanceLocation, progressDistanceM,
            minOf(location.elapsedRealtimeMs, unsnappedPosition.elapsedRealtimeMs),
        )
        val guideAdvanced = (!navigationOnlyPosition && !stationaryGnssJump && gapRealignment != GapRealignment.HOLD && advancePassedGuides(
            currentRoute, guidanceLocation, location, progressDistanceM, acceptedMatch != null,
            testGuidanceProjection != null, holdProtectedGuides = stationaryJumpUnresolved,
            maximumGuidesPassed = if (gapRealignment == GapRealignment.RESUME) 1 else Int.MAX_VALUE,
        )) || guideRestored
        if (stationaryJumpUnresolved && !allowDegradedRouteGuidance &&
            protectedGuidePassageClaimed(currentRoute, guidanceLocation)
        ) return stationaryJumpLocationRecheckUpdate()
        val guideInstruction = instructionForCurrentGuide(currentRoute, guidanceLocation,
            speechProgressM = if (navigationOnlyPosition) guidanceProgressM else progressDistanceM,
            allowPassageConfirmation = !navigationOnlyPosition).let { instruction ->
            val possibleApproach = !navigationOnlyPosition && mayOfferDestinationApproach(currentRoute, instruction) &&
                !allowDegradedRouteGuidance && nearRouteEnd && acceptedMatch != null &&
                !stationaryGnssJump && !stationaryJumpUnresolved && gapRealignment != GapRealignment.HOLD &&
                haversineMeters(routeEndpoint.latitude, routeEndpoint.longitude,
                    destination.latitude, destination.longitude) <= config.arrivalRadiusM &&
                sample.possiblyNearDestination(destination, config.arrivalRadiusM)
            if (possibleApproach) instruction.copy(
                text = "경로 종점 부근입니다. " + unconfirmedDestinationAccessWarning() + POSSIBLE_DESTINATION_APPROACH,
                destinationApproach = true,
            )
            else instruction
        }
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
                lateralOffsetBeyondUncertainty = selectedGuidanceAlignment.lateralOffsetBeyondUncertainty,
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
    ): RouteNavigatorUpdate? = retryGuidanceFromCurrentEvidence(nowMs, facingObservation, facingUnavailableReason)

    /** The caller binds the first bad receipt to the current route/context before delivering it. */
    @Synchronized
    fun retryGuidance(
        nowMs: Long,
        facingObservation: RouteFacingObservation?,
        facingUnavailableReason: RouteCompassHeadingReason?,
        firstBadAtMs: Long,
    ): RouteNavigatorUpdate? = retryGuidanceFromCurrentEvidence(
        nowMs, facingObservation, facingUnavailableReason, firstBadAtMs,
    )

    private fun retryGuidanceFromCurrentEvidence(
        nowMs: Long,
        facingObservation: RouteFacingObservation?,
        facingUnavailableReason: RouteCompassHeadingReason?,
        firstBadAtMs: Long? = null,
    ): RouteNavigatorUpdate? {
        val currentRoute = route ?: return informationCancellationUpdateOrNull()
        val context = currentGuidanceContext(nowMs) ?: return informationCancellationUpdateOrNull()
        return offerGuidance(currentRoute, context, nowMs, facingObservation,
            facingUnavailableReason = facingUnavailableReason, firstBadAtMs = firstBadAtMs)
            .withInformationCancellation()
    }

    /** A user request bypasses only completed-cue cadence, never evidence or speech ownership. */
    @Synchronized
    fun currentGuidance(
        nowMs: Long,
        facingObservation: RouteFacingObservation? = null,
        facingUnavailableReason: RouteCompassHeadingReason? = null,
    ): RouteNavigatorUpdate? {
        val currentRoute = route ?: return informationCancellationUpdateOrNull()
        val context = currentGuidanceContext(nowMs) ?: return informationCancellationUpdateOrNull()
        return offerGuidance(currentRoute, context, nowMs, facingObservation, forceRepeat = true,
            facingUnavailableReason = facingUnavailableReason).withInformationCancellation()
    }

    private fun currentGuidanceContext(nowMs: Long): RouteGuidanceContext? {
        val currentRoute = route ?: return null
        val context = latestGuidanceContext ?: return null
        if (routeGeometryRecovery != null && !hasCurrentRouteGeometryGrace(nowMs)) {
            if (pendingSpeechCue != null) expiredContextActionSpeechToCancel = true
            latestGuidanceContext = null
            resetDirectionRecheckGrace()
            invalidateSpeechCue()
            return null
        }
        if (!isGuidanceContextCurrent(currentRoute, context, nowMs)) {
            // Invalidate first, then return an actuator cancellation through the next update.
            // An obsolete near-branch ACTION must not finish silently without a Navigator ACK.
            if (pendingSpeechCue != null) expiredContextActionSpeechToCancel = true
            latestGuidanceContext = null
            resetClockSector()
            // Drop speech grants, while the same source's unresolved BAD keeps its first
            // receipt and deadline. A new qualified direction or a real owner reset ends it.
            if (context.routeRevision != routeRevision || nowMs < 0L || latestUpdateNowMs?.let { nowMs < it } == true) {
                resetDirectionRecheckGrace()
            } else {
                clearDirectionSpeechAuthorizations()
            }
            invalidateSpeechCue()
            return null
        }
        return context
    }

    private fun isGuidanceContextCurrent(
        currentRoute: WalkingRoute,
        context: RouteGuidanceContext,
        nowMs: Long,
    ): Boolean {
        val currentGuideIndex = currentRoute.guidePoints.getOrNull(nextGuideIndex)?.let { nextGuideIndex }
        return context.routeRevision == routeRevision &&
            (context.directionOnly || context.instruction.guideIndex == currentGuideIndex) &&
            canDeliverRouteGuidance() && latestUpdateNowMs?.let { nowMs < it } != true &&
            context.rawLocation.hasCurrentCoordinates(nowMs) && context.guidanceLocation.hasCurrentCoordinates(nowMs) &&
            !context.mayHaveReachedTheNextBranch(nowMs)
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
        firstBadAtMs: Long? = null,
    ): RouteNavigatorUpdate {
        val guideInstruction = context.instruction
        val hadPendingDirection = pendingSpeechCue != null
        val hardDirectionChange = hasHardInvalidatedDirectionClaim(nowMs, facingObservation)
        if (hardDirectionChange) {
            resetDirectionRecheckGrace()
            invalidateSpeechCue()
        }
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
        val freshFacing = stabilizeClockSector(measuredFacing, facingObservation)
        val alignmentDiagnostic = context.alignment.diagnostic
        // S08-18-F1: the held bearing only waits for passage; the turn itself is still due.
        val nearPendingTurn = !context.directionOnly && !guideInstruction.passageConfirmation &&
            alignmentDiagnostic.reason == RouteAlignmentReason.TURN_PASSAGE_PENDING
        // S08-20: beside the route the progress direction is only described; no movement or turn
        // command is given from a position whose way onto the route is unknown.
        val besideRoute = !context.directionOnly && !guideInstruction.passageConfirmation &&
            context.alignment.lateralOffsetBeyondUncertainty
        val relativeDirectionEligible = !guideInstruction.passageConfirmation &&
            !guideInstruction.destinationNotice && !nearPendingTurn && !besideRoute
        val grace = observeDirectionContinuity(context, nowMs, facingObservation, freshFacing,
            relativeDirectionEligible, firstBadAtMs, facingUnavailableReason)
        val facing = grace.retainedFacing ?: if (grace.withholdFreshReference)
            freshFacing.copy(direction = RouteFacingDirection.UNKNOWN) else freshFacing
        val facingClockHour = facing.clockHour.takeUnless {
            guideInstruction.passageConfirmation || guideInstruction.destinationNotice
        }
        val facingClockRange = facing.clockRange.takeUnless {
            guideInstruction.passageConfirmation || guideInstruction.destinationNotice || nearPendingTurn
        }
        val hasFacingReference = facingClockHour != null || facingClockRange != null
        // Observe the episode even while a previous utterance is playing or its delivery is in
        // backoff. Neither repeated checks nor speech retries may restart this waiting period.
        val waitingForDirectionRecheck = grace.waiting && !hasFacingReference
        val compassAvailable = grace.retained != null || RouteFacingGuidance.isUsableObservation(facingObservation, nowMs)
        // A usable RV can retain a detected magnetic anomaly while withholding numeric clocks.
        val magneticRecovery = if (grace.retained == null && compassAvailable && facingObservation?.magneticFieldSuspect == true)
            RouteCompassHeadingReason.MAGNETIC_FIELD_ANOMALY.spokenRecovery() else ""
        // S08-8: without a usable observation the compass cause selects one recovery step.
        val unavailableRecovery = if (compassAvailable) "" else facingUnavailableReason?.spokenRecovery().orEmpty()
        val facingMessage = when {
            guideInstruction.passageConfirmation || guideInstruction.destinationNotice -> ""
            magneticRecovery.isNotEmpty() -> magneticRecovery
            context.initialJoinReference -> initialRouteJoinReferenceInstruction(
                facing, compassAvailable, guideInstruction.distanceM,
                context.alignment.bearingDegrees != null, facingUnavailableReason,
            )
            context.directionOnly -> directionReferenceInstruction(
                facing, compassAvailable, alignmentDiagnostic.reason, unavailableRecovery,
            )
            besideRoute -> directionDescription(
                "경로 진행 방향은", facingClockHour, compassAvailable, unavailableRecovery,
            ) + "현재 위치는 경로 옆으로 떨어져 있으며, 현재 위치에서 경로까지 걸어갈 수 있는 통로는 확인되지 않았습니다."
            nearPendingTurn -> NEAR_PENDING_TURN_PREFIX
            else -> facingInstruction(
                facing.direction, compassAvailable,
                context.alignment.bearingDegrees != null,
                facingClockHour, alignmentDiagnostic.reason, unavailableRecovery,
            )
        }
        val distanceBand = routeGuidanceDistanceBand(guideInstruction.distanceM)
        val spokenFacingDirection = when {
            guideInstruction.passageConfirmation || guideInstruction.destinationNotice -> null
            magneticRecovery.isNotEmpty() -> RouteFacingDirection.UNKNOWN
            !nearPendingTurn && !hasFacingReference -> RouteFacingDirection.UNKNOWN
            else -> facing.direction
        }
        val cueKey = CompletedRouteSpeechCue(guideInstruction.guideIndex, distanceBand, context.directionOnly,
            spokenFacingDirection,
            facingClockHour, guideInstruction.destinationNotice, besideRoute,
            recoveryExplanation = magneticRecovery.isNotEmpty() &&
                !guideInstruction.passageConfirmation && !guideInstruction.destinationNotice,
            clockRange = facingClockRange)
        // Fresh, usable turns must not restart an already playing sentence on the same guide.
        // The playback-start guard still rejects a queued cue with a changed direction.
        // Losing a supported direction revokes it immediately. A neighbouring supported range
        // is ordinary measurement drift, not loss; START still checks exact/range coverage.
        // A clock sector change that is only awaiting its second fresh observation is not a
        // precision loss (S08-5): the measured facing still supports a clock number. Neither is a
        // small turn that only nears a sector edge while the sensor precision still supports one.
        // S08-23: a fresh turn that reverses the offered command's meaning (ahead <-> behind,
        // left <-> right) is handled like lost evidence; adjacent changes still finish speaking.
        // nav-F2: the reversal is also judged by angle, not only by the opposite coarse sector.
        // A cadence-band crossing inside position uncertainty is ordinary distance drift.
        // Keep substantial distance changes and leaving the reference phase as revocations.
        // START also requires current distance support and the original evidence deadline.
        val initialReferenceChanged = offeredInitialJoinReference &&
            (!context.initialJoinReference ||
                (offeredSpeechCue?.distanceBand != distanceBand && !initialJoinDistanceStillSupported(context)))
        // A recovery sentence did not deliver the now-supported direction. Cancel a queued one,
        // and do not make the first recovered direction wait for a completed explanation's cadence.
        val staleRecoveryExplanation = !nearPendingTurn && !guideInstruction.passageConfirmation &&
            !guideInstruction.destinationNotice && offeredFacing?.hasClockReference == false && hasFacingReference
        val facingEvidenceLost = hardDirectionChange || grace.revoked || grace.problemTransition || initialReferenceChanged || staleRecoveryExplanation ||
            (!guideInstruction.passageConfirmation && !guideInstruction.destinationNotice &&
                grace.retained == null && offeredFacing?.losesEvidenceTo(measuredFacing, facing) == true)
        if (grace.problemTransition) invalidateSpeechCue()
        if (pendingSpeechCue?.let {
                it.routeRevision == context.routeRevision &&
                    (it.directionOnly || it.guideIndex == guideInstruction.guideIndex)
            } == true && !facingEvidenceLost && offeredDestinationNotice == guideInstruction.destinationNotice &&
            lastOfferedBesideRoute == besideRoute
        ) {
            if (forceRepeat) manualGuidancePending = true
            return RouteNavigatorUpdate(null, false, context.offRoute, false, "guidance_in_flight", routeAlignmentDiagnostic = alignmentDiagnostic)
        }
        val previousCue = offeredSpeechCue
        // S08-20: a changed beside-route action fact revokes movement speech and makes its
        // replacement available even when the previous sentence completed or awaits retry.
        val besideSafetyChanged = lastOfferedBesideRoute?.let { it != besideRoute } == true
        if (previousCue?.guideIndex == guideInstruction.guideIndex &&
            speechRetryNotBeforeMs?.let { nowMs < it } == true && !besideSafetyChanged && !grace.problemTransition
        ) {
            return RouteNavigatorUpdate(null, false, context.offRoute, false, "guidance_retry_wait",
                cancelStaleNavigationSpeech = facingEvidenceLost,
                routeAlignmentDiagnostic = alignmentDiagnostic)
        }
        val sameCue = previousCue != null && previousCue.guideIndex == guideInstruction.guideIndex &&
            previousCue.distanceBand == distanceBand && previousCue.directionOnly == context.directionOnly &&
            offeredDestinationNotice == guideInstruction.destinationNotice &&
            lastOfferedBesideRoute == besideRoute &&
            offeredFacingMessage == facingMessage &&
            offeredPositionEstimateUncertain == context.positionEstimateUncertain
        val requestedRepeat = forceRepeat ||
            (manualGuidancePending && previousCue?.guideIndex == guideInstruction.guideIndex)
        val cancelChangedSpeech = !sameCue && pendingSpeechCue != null
        val fullGuidanceAfterReference = previousCue?.directionOnly == true && !context.directionOnly
        if (!sameCue) invalidateSpeechCue(preserveInformation = pendingInformationUtterance?.cueToken?.let {
            it.routeRevision == context.routeRevision && it.guideIndex == guideInstruction.guideIndex &&
                !context.directionOnly && it.positionEstimateUncertain == context.positionEstimateUncertain
        } == true)
        manualGuidancePending = requestedRepeat
        val cancelStaleSpeech = cancelPassedSpeech || cancelChangedSpeech ||
            (hadPendingDirection && (hardDirectionChange || grace.revoked || grace.problemTransition))
        fun noInstruction(reason: String) = RouteNavigatorUpdate(
            null, false, context.offRoute, false, reason,
            cancelStaleNavigationSpeech = cancelStaleSpeech,
            routeAlignmentDiagnostic = alignmentDiagnostic,
        )
        if (pendingSpeechCue != null) return noInstruction("guidance_in_flight")
        // Old relative speech was invalidated above before we suppress a replacement explanation.
        if (waitingForDirectionRecheck) return noInstruction("guidance_facing_recheck_wait")
        val lastGuidance = lastGuidanceAtMs
        // A first recovery sample can invalidate the offered explanation before its second
        // sample confirms a clock. The actual completed explanation still delivered no direction.
        val recoveredDirectionAfterCompletedExplanation = relativeDirectionEligible && hasFacingReference &&
            pendingSpeechCue == null && lastDeliveredDirectionCue?.let {
                it.guideIndex == guideInstruction.guideIndex && it.directionOnly == context.directionOnly &&
                    it.besideRoute == besideRoute && !it.destinationNotice &&
                    it.facingDirection == RouteFacingDirection.UNKNOWN && it.clockHour == null && it.clockRange == null
            } == true
        // A supported clock direction needs faster feedback than recovery/overview notices.
        // Keep explicit shorter caller intervals and all evidence/START guards above and below.
        val facingGuidance = relativeDirectionEligible && hasFacingReference && magneticRecovery.isEmpty()
        val minimumIntervalMs = if (facingGuidance)
            minOf(config.guidanceIntervalMs, config.facingGuidanceIntervalMs) else config.guidanceIntervalMs
        val periodicIntervalMs = if (facingGuidance)
            minOf(config.periodicGuidanceIntervalMs, config.periodicFacingGuidanceIntervalMs) else config.periodicGuidanceIntervalMs
        val directionChangedSinceDelivery = facingGuidance && lastGuidance != null &&
            nowMs - lastGuidance >= minimumIntervalMs && lastDeliveredDirectionCue?.let {
                it.guideIndex == cueKey.guideIndex && it.directionOnly == cueKey.directionOnly &&
                    it.besideRoute == cueKey.besideRoute && !it.destinationNotice &&
                    (it.clockHour != null || it.clockRange != null) &&
                    (it.facingDirection != cueKey.facingDirection ||
                        it.clockHour != null && cueKey.clockHour != null && it.clockHour != cueKey.clockHour)
            } == true
        // The cadence begins after delivery finishes, and never replaces an in-flight sentence.
        val periodicGuidanceDue = !context.initialJoinReference && lastGuidance != null &&
            nowMs - lastGuidance >= periodicIntervalMs
        if (lastGuidance != null && nowMs - lastGuidance < minimumIntervalMs &&
            !periodicGuidanceDue && !manualGuidancePending && !guideAdvanced && !passageConfirmationChanged &&
            !fullGuidanceAfterReference && !besideSafetyChanged && !staleRecoveryExplanation &&
            !recoveredDirectionAfterCompletedExplanation &&
            !grace.problemTransition &&
            !(distanceBand in 0..1 && !sameCue)
        ) return noInstruction("guidance_rate_limited")
        // A changed action safety fact is new information even with the same direction and guide.
        val alreadyCompleted = if (context.initialJoinReference) {
            lastCompletedInitialJoinReference?.covers(cueKey) == true
        } else completedSpeechCues.any { it.covers(cueKey) }
        if (alreadyCompleted && !periodicGuidanceDue && !manualGuidancePending &&
            !besideSafetyChanged && !grace.problemTransition && !recoveredDirectionAfterCompletedExplanation &&
            !directionChangedSinceDelivery
        ) {
            return noInstruction("guidance_already_completed")
        }
        if (speechRetryNotBeforeMs?.let { nowMs < it } == true) {
            return noInstruction("guidance_retry_wait")
        }
        // Only a newly offered action may preempt optional INFO; the in-flight ACTION and cadence
        // returns above retain their existing behavior. Main applies this cancellation before submit.
        clearInformation(cancelSpeech = true)
        // An overview, arrival/passage notice or route-relative near-turn cue makes no claim
        // about the current camera facing. Only a spoken relative direction owns a facing lease.
        val requiresFacingEvidence = !guideInstruction.passageConfirmation && !guideInstruction.destinationNotice &&
            !nearPendingTurn && hasFacingReference
        val directionObservation = grace.retained?.observation ?: facingObservation?.takeIf {
            requiresFacingEvidence && RouteFacingGuidance.isUsableObservation(it, nowMs)
        }
        val token = offeredSpeechCue?.takeUnless {
            it == failedSpeechCue || it == completedSpeechCue ||
                !(it.hasCurrentFacingEvidence(nowMs) || hasCurrentDirectionGrace(it, nowMs)) ||
                it.directionGraceEpisodeId != grace.retained?.let { directionRecheckEpisode?.id }
        } ?: RouteSpeechCueToken(
            routeRevision = routeRevision,
            cueRevision = ++cueRevision,
            guideIndex = guideInstruction.guideIndex,
            distanceBand = distanceBand,
            directionOnly = context.directionOnly,
            routeId = activeRouteId,
            requiresFacingEvidence = requiresFacingEvidence,
            facingObservedAtElapsedRealtimeMs = directionObservation?.observedAtElapsedRealtimeMs,
            facingValidUntilElapsedRealtimeMs =
                grace.retained?.normalValidUntilMs ?:
                    RouteFacingGuidance.observationValidUntilElapsedRealtimeMs(directionObservation, nowMs),
            directionGraceEpisodeId = grace.retained?.let { directionRecheckEpisode?.id },
            directionGraceStartedAtElapsedRealtimeMs = grace.retained?.let { directionRecheckEpisode?.startedAtMs },
            directionGraceDeadlineElapsedRealtimeMs = grace.retained?.let { directionRecheckEpisode?.deadlineMs },
        ).also { offeredSpeechCue = it }
        offeredFacingMessage = facingMessage
        offeredFacing = facing.takeUnless { guideInstruction.passageConfirmation || guideInstruction.destinationNotice }
            ?.let { if (nearPendingTurn) it.copy(boundedClockRange = null) else it }
        // A completed request to recheck did not deliver a direction action. Retain the raw
        // measured facing separately for the in-flight reversal/precision and playback guards.
        offeredCompletion = cueKey
        offeredDestinationNotice = guideInstruction.destinationNotice
        offeredInitialJoinReference = context.initialJoinReference
        offeredInitialJoinContext = context.takeIf { it.initialJoinReference }
        offeredGuidanceContext = context
        lastOfferedBesideRoute = besideRoute
        offeredPositionEstimateUncertain = context.positionEstimateUncertain
        // S08-30: deliver the short direction/action separately. Capture non-relative explanation
        // now, but enable its own route/context validator only after this action actually completes.
        val routeStart = includeRouteStartSummary && !context.directionOnly
        val startCue = if (routeStart) "길안내를 시작합니다. " else ""
        val summary = if (routeStart) " 전체 경로는 약 ${currentRoute.summary.distanceM}m입니다." else ""
        val guidanceText = if (guideInstruction.passageConfirmation || guideInstruction.destinationNotice) {
            guideInstruction.text
        } else {
            facingMessage + when {
                magneticRecovery.isNotEmpty() -> ""
                context.directionOnly -> ""
                // S08-20: only the next guide as an overview, never a command from here.
                besideRoute -> nextGuideOverview(currentRoute, context).trimEnd().let { if (it.isEmpty()) "" else " $it" } +
                    " $BESIDE_ROUTE_ACTION"
                nearPendingTurn -> guideInstruction.text
                !hasFacingReference -> nextGuideOverview(currentRoute, context) +
                    "방향이 확인되면 안내를 이어갑니다."
                else -> clockGuidanceContinuation(guideInstruction, facing.direction)
            }
        }
        offeredInformationUtterance = summary.trim().takeIf(String::isNotEmpty)?.let {
            RouteInformationUtterance(it, RouteInformationCueToken(
                routeRevision = token.routeRevision,
                routeId = requireNotNull(token.routeId),
                actionCueRevision = token.cueRevision,
                guideIndex = token.guideIndex,
                positionEstimateUncertain = context.positionEstimateUncertain,
            ))
        }
        return RouteNavigatorUpdate(
            instruction = startCue + guidanceText,
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
                    guideInstruction.passageConfirmation || guideInstruction.destinationNotice || nearPendingTurn -> RouteFacingExpression.NOT_SPOKEN
                    (if (context.directionOnly) facing.clockHour else facingClockHour) != null -> RouteFacingExpression.CLOCK
                    facingClockRange != null -> RouteFacingExpression.CLOCK_RANGE
                    else -> RouteFacingExpression.UNKNOWN
                },
                clockReferenceKind = facing.clockReferenceKind.takeIf {
                    !guideInstruction.passageConfirmation && !guideInstruction.destinationNotice && !nearPendingTurn
                },
            ),
            infoUtterance = offeredInformationUtterance,
        )
    }

    private fun initialJoinDistanceStillSupported(current: RouteGuidanceContext): Boolean {
        val previous = offeredInitialJoinContext ?: return false
        return distanceStillSupported(previous, current)
    }

    private fun offeredContextStillSupports(current: RouteGuidanceContext): Boolean {
        val previous = offeredGuidanceContext ?: return false
        return previous.routeRevision == current.routeRevision &&
            previous.instruction.guideIndex == current.instruction.guideIndex &&
            previous.directionOnly == current.directionOnly && previous.initialJoinReference == current.initialJoinReference &&
            sameOrderedStraightSegment(routeGeometry, previous.alignment.diagnostic.segmentIndex,
                current.alignment.diagnostic.segmentIndex) &&
            previous.alignment.diagnostic.reason == current.alignment.diagnostic.reason &&
            previous.alignment.lateralOffsetBeyondUncertainty == current.alignment.lateralOffsetBeyondUncertainty &&
            previous.alignment.beyondPendingTurnVertex == current.alignment.beyondPendingTurnVertex &&
            previous.instruction.passageConfirmation == current.instruction.passageConfirmation &&
            previous.instruction.destinationNotice == current.instruction.destinationNotice &&
            previous.positionEstimateUncertain == current.positionEstimateUncertain && previous.offRoute == current.offRoute &&
            distanceStillSupported(previous, current)
    }

    private fun distanceStillSupported(previous: RouteGuidanceContext, current: RouteGuidanceContext): Boolean {
        if (previous.instruction.distanceM == null || current.instruction.distanceM == null)
            return previous.instruction.distanceM == current.instruction.distanceM
        val previousDistance = previous.instruction.distanceM ?: return false
        val currentDistance = current.instruction.distanceM ?: return false
        fun positionAllowance(context: RouteGuidanceContext): Double =
            maxOf(context.rawLocation.accuracyM.toDouble(), context.guidanceLocation.accuracyM.toDouble()) +
                haversineMeters(context.rawLocation.latitude, context.rawLocation.longitude,
                    context.guidanceLocation.latitude, context.guidanceLocation.longitude)
        // Both displayed distances were rounded to metres. This is the same positional
        // allowance used for the nearest-point bearing, not a newly claimed GPS accuracy.
        val allowance = positionAllowance(previous) + positionAllowance(current) + 1.0
        return allowance.isFinite() && allowance >= 0.0 &&
            abs(previousDistance.toDouble() - currentDistance) <= allowance
    }

    private fun observeDirectionContinuity(
        context: RouteGuidanceContext,
        nowMs: Long,
        observation: RouteFacingObservation?,
        facing: RouteFacingGuidanceResult,
        eligible: Boolean,
        firstBadAtMs: Long?,
        unavailableReason: RouteCompassHeadingReason?,
    ): DirectionContinuity {
        val timeRegressed = nowMs < 0L || lastDirectionCheckedAtMs?.let { nowMs < it } == true
        val targetChanged = lastGoodDirection?.matches(context) == false
        val revoked = timeRegressed || targetChanged
        if (revoked) {
            resetDirectionRecheckGrace()
            invalidateSpeechCue()
        }
        lastDirectionCheckedAtMs = nowMs
        latestDirectionUnavailableReason = unavailableReason
        val previous = lastGoodDirection
        val episode = directionRecheckEpisode
        val newRecoverySample = observation != null &&
            observation.observedAtElapsedRealtimeMs > (previous?.observation?.observedAtElapsedRealtimeMs ?: -1L) &&
            observation.observedAtElapsedRealtimeMs > (episode?.latestRejectedObservedAtMs ?: -1L)
        // An overlapping nominal clock that does not fully support the same spoken command
        // remains weaker evidence. Full same-clock support can recover without promoting precision.
        val weakerRetainedNominalClock = episode?.retained != null &&
            pendingSpeechCue?.requiresFacingEvidence == true && facing.isWeakerNominalClockFor(offeredFacing)
        val confirmed = !weakerRetainedNominalClock && facing.hasClockReference && RouteFacingGuidance.isUsableObservation(observation, nowMs) &&
            (episode == null || newRecoverySample)
        if (confirmed && !timeRegressed) {
            val queued = offeredSpeechCue?.takeIf { it == pendingSpeechCue }
            if (episode?.retained != null && queued != null &&
                (hasCurrentDirectionGrace(queued, nowMs) || hasCurrentNormalDirectionGracePacket(queued, nowMs)) &&
                episode.retained.matches(context) && offeredFacing?.fullySupportedBy(facing) == true
            ) {
                recoveredDirectionGracePacket = RecoveredDirectionGracePacket(queued, episode.retained,
                    episode.id, episode.startedAtMs, episode.deadlineMs, nowMs)
            } else if (recoveredDirectionGracePacket?.let {
                    it.token == queued && it.retained.matches(context) &&
                        offeredFacing?.fullySupportedBy(facing) == true
                } != true
            ) recoveredDirectionGracePacket = null
            normalDirectionGracePacket = null
            firstDirectionFaultReceiptMs = null
            if (eligible && (previous == null || newRecoverySample)) {
                lastGoodDirection = RetainedRouteDirection(context.routeRevision, context.instruction.guideIndex,
                    context.directionOnly, context.initialJoinReference, context.alignment.bearingDegrees,
                    context.alignment.diagnostic.segmentIndex, routeGeometry, context.alignment.diagnostic.reason, nowMs,
                    requireNotNull(observation), facing,
                    requireNotNull(RouteFacingGuidance.observationValidUntilElapsedRealtimeMs(observation, nowMs)))
            }
            directionRecheckEpisode = null
            return DirectionContinuity(revoked = revoked)
        }
        recoveredDirectionGracePacket = null
        val freshProofAtFault = freshDirectionStartAuthorization
        freshDirectionStartAuthorization = null
        if (config.directionRecheckGraceMs <= 0L || nowMs < 0L) {
            directionRecheckEpisode = null
            return DirectionContinuity(waiting = nowMs < 0L && eligible, revoked = revoked)
        }
        // Only the original normal lease can authorize retention. A late sample-gap discovery
        // starts at lease+1, not at discovery, so an old observation cannot be revived.
        val firstReceipt = listOfNotNull(firstBadAtMs, firstDirectionFaultReceiptMs).filter {
            !timeRegressed && it in 0L..nowMs &&
                (previous == null || it >= previous.observation.observedAtElapsedRealtimeMs)
        }.minOrNull()
        val onset = previous?.let {
            if (firstReceipt != null && firstReceipt <= it.normalValidUntilMs) firstReceipt
            else minOf(nowMs, it.normalValidUntilMs + 1L)
        } ?: firstReceipt ?: nowMs
        val current = episode?.takeIf { it.routeRevision == context.routeRevision }?.let { it.copy(
            startedAtMs = minOf(it.startedAtMs, firstReceipt ?: it.startedAtMs),
            deadlineMs = if (firstReceipt != null && firstReceipt < it.startedAtMs)
                if (firstReceipt > Long.MAX_VALUE - config.directionRecheckGraceMs) Long.MAX_VALUE
                else firstReceipt + config.directionRecheckGraceMs else it.deadlineMs,
            lastCheckedAtMs = nowMs,
            latestRejectedObservedAtMs = maxOf(it.latestRejectedObservedAtMs,
                observation?.observedAtElapsedRealtimeMs ?: -1L),
        ) } ?: if (eligible) DirectionRecheckEpisode(
            id = ++directionEpisodeSequence, routeRevision = context.routeRevision,
            startedAtMs = onset,
            deadlineMs = if (onset > Long.MAX_VALUE - config.directionRecheckGraceMs)
                Long.MAX_VALUE else onset + config.directionRecheckGraceMs,
            lastCheckedAtMs = nowMs,
            retained = previous?.takeIf { !revoked && it.matches(context) },
            latestRejectedObservedAtMs = observation?.observedAtElapsedRealtimeMs ?: -1L,
        ) else null
        val problemTransition = current != null && nowMs >= current.deadlineMs && !current.problemPublished
        val cachedFacing = current?.retained?.facingFor(context)
        directionRecheckEpisode = current?.copy(problemPublished = current.problemPublished || problemTransition,
            retentionUsable = cachedFacing != null)
        val geometryUnsupported = eligible && current?.retained != null && cachedFacing == null
        normalDirectionGracePacket = pendingSpeechCue?.takeIf { token ->
            token.requiresFacingEvidence && token.directionGraceEpisodeId == null && current?.retained != null &&
                token.routeRevision == current.routeRevision && nowMs < current.deadlineMs &&
                (current.startedAtMs <= (token.facingValidUntilElapsedRealtimeMs ?: -1L) ||
                    normalDirectionGracePacket?.let { it.token == token && it.episodeId == current.id } == true ||
                    freshProofAtFault?.let { it.token == token && it.authorizedAtMs <= current.startedAtMs &&
                        current.startedAtMs <= it.validUntilMs } == true) &&
                cachedFacing != null && offeredFacing?.fullySupportedBy(cachedFacing) == true
        }?.let { NormalDirectionGracePacket(it, requireNotNull(current).id, requireNotNull(current.retained)) }
        val geometryClaimRetired = geometryUnsupported && offeredSpeechCue?.requiresFacingEvidence == true
        if (geometryClaimRetired) invalidateSpeechCue()
        return DirectionContinuity(
            retained = current?.retained?.takeIf { eligible && nowMs < current.deadlineMs && cachedFacing != null },
            retainedFacing = cachedFacing?.takeIf { eligible && current != null && nowMs < current.deadlineMs },
            waiting = eligible && current != null && nowMs < current.deadlineMs,
            problemTransition = problemTransition,
            revoked = revoked || geometryClaimRetired,
            withholdFreshReference = current != null && facing.hasClockReference,
        )
    }

    private fun hasCurrentDirectionGrace(token: RouteSpeechCueToken, nowMs: Long): Boolean {
        val episode = directionRecheckEpisode ?: return false
        val retained = episode.retained ?: return false
        return token.requiresFacingEvidence && token.directionGraceEpisodeId == episode.id &&
            token.directionGraceStartedAtElapsedRealtimeMs == episode.startedAtMs &&
            token.directionGraceDeadlineElapsedRealtimeMs == episode.deadlineMs &&
            token.facingObservedAtElapsedRealtimeMs == retained.observation.observedAtElapsedRealtimeMs &&
            token.facingValidUntilElapsedRealtimeMs == retained.normalValidUntilMs &&
            token.routeRevision == episode.routeRevision && nowMs >= episode.startedAtMs &&
            nowMs >= episode.lastCheckedAtMs && nowMs < episode.deadlineMs
    }

    private fun hasRecoveredDirectionGracePacket(token: RouteSpeechCueToken, nowMs: Long): Boolean {
        val packet = recoveredDirectionGracePacket ?: return false
        return token == packet.token && token == pendingSpeechCue &&
            directionRecheckEpisode == null && token.routeRevision == routeRevision &&
            nowMs >= packet.recoveredAtMs && nowMs >= (lastDirectionCheckedAtMs ?: packet.recoveredAtMs) &&
            nowMs < packet.deadlineMs
    }

    private fun hasCurrentNormalDirectionGracePacket(token: RouteSpeechCueToken, nowMs: Long): Boolean {
        val grant = normalDirectionGracePacket ?: return false
        val episode = directionRecheckEpisode ?: return false
        return token == grant.token && token == pendingSpeechCue && token.directionGraceEpisodeId == null &&
            grant.episodeId == episode.id && token.routeRevision == episode.routeRevision &&
            nowMs >= episode.lastCheckedAtMs &&
            nowMs >= episode.startedAtMs && nowMs < episode.deadlineMs
    }

    private fun hasFreshDirectionStartAuthorization(token: RouteSpeechCueToken, nowMs: Long): Boolean {
        val proof = freshDirectionStartAuthorization ?: return false
        return token == proof.token && token == pendingSpeechCue && token.routeRevision == routeRevision &&
            token.routeId == activeRouteId && token.directionGraceEpisodeId == null && directionRecheckEpisode == null &&
            nowMs >= proof.authorizedAtMs && nowMs >= (lastDirectionCheckedAtMs ?: proof.authorizedAtMs) &&
            nowMs <= proof.validUntilMs
    }

    private fun resetDirectionRecheckGrace() {
        directionRecheckEpisode = null
        clearDirectionSpeechAuthorizations()
        firstDirectionFaultReceiptMs = null
        lastDirectionCheckedAtMs = null
        latestDirectionUnavailableReason = null
    }

    private fun clearDirectionSpeechAuthorizations() {
        lastGoodDirection = null
        recoveredDirectionGracePacket = null
        normalDirectionGracePacket = null
        freshDirectionStartAuthorization = null
    }

    /**
     * S08-27: with an unknown facing only the route overview of the next guide is spoken, never an
     * action to execute now. It needs a known route bearing (the route side is resolved) and is not
     * given for passage checks. The final segment retains its non-directional distance notice.
     * S08-20 uses it beside the route as well. nav-F3: a crossing marker is an overview with its
     * reference notice too, so the notice is not lost in these states.
     */
    private fun nextGuideOverview(currentRoute: WalkingRoute, context: RouteGuidanceContext): String {
        val instruction = context.instruction
        if (context.directionOnly || instruction.passageConfirmation ||
            (context.alignment.bearingDegrees == null && !context.alignment.lateralOffsetBeyondUncertainty)
        ) return ""
        // On the final segment there is no maneuver to describe, but accepted route progress
        // still supports remaining-distance information without knowing the camera facing.
        if (instruction.guideIndex == null) return "${instruction.text} "
        val guide = currentRoute.guidePoints.getOrNull(instruction.guideIndex) ?: return ""
        return guide.maneuverOverview(instruction.distanceM)?.let { "$it " } ?: ""
    }

    private fun arrivalConfirmationRequiredUpdate(stepProgressConsistent: Boolean?): RouteNavigatorUpdate {
        return RouteNavigatorUpdate(
            instruction = "도착했습니다. 안내를 종료합니다",
            arrived = false,
            offRoute = false,
            shouldReroute = false,
            reason = "arrival_confirmation_required",
            cancelStaleNavigationSpeech = true,
            pendingUserDecision = RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION,
            stepProgressConsistent = stepProgressConsistent,
        )
    }

    private fun waitingForRouteJoinUpdate(alignment: RouteAlignmentDiagnostic? = null): RouteNavigatorUpdate {
        latestGuidanceContext = null
        latestRouteMatchUsableForGuidance = false
        latestGuidanceSourceAllowed = false
        resetRewindEvidence()
        interruptPassedTurnEvidence()
        invalidateSpeechCue()
        return RouteNavigatorUpdate(
            instruction = null,
            arrived = false,
            offRoute = false,
            shouldReroute = false,
            reason = "waiting_for_route_join",
            cancelStaleNavigationSpeech = true,
            routeAlignmentDiagnostic = alignment,
        )
    }

    /** A point reference is neither a walkable connector nor accepted route progress. */
    private fun initialRouteJoinReferenceUpdate(
        currentRoute: WalkingRoute,
        rawLocation: TrustedLocation,
        position: FilteredRoutePosition,
        nowMs: Long,
        facingObservation: RouteFacingObservation?,
        facingUnavailableReason: RouteCompassHeadingReason?,
        matchAlignment: RouteAlignmentDiagnostic,
    ): RouteNavigatorUpdate {
        latestRouteMatchUsableForGuidance = false
        latestGuidanceSourceAllowed = false
        resetRewindEvidence()
        interruptPassedTurnEvidence()
        if (currentRoute.polyline.size < 2 || currentRoute.polyline.any {
                !it.latitude.isFinite() || it.latitude !in -90.0..90.0 ||
                    !it.longitude.isFinite() || it.longitude !in -180.0..180.0
            } || latestRouteMatch?.reason in setOf(
                RouteMatchReason.INVALID_INPUT, RouteMatchReason.INVALID_ROUTE,
                RouteMatchReason.STALE_SAMPLE, RouteMatchReason.EXPIRED_SAMPLE, RouteMatchReason.FUTURE_SAMPLE,
            )
        ) return waitingForRouteJoinUpdate(matchAlignment)
        val maximumAccuracyM = LocationTrustConfig().maxAccuracyM
        if (rawLocation.accuracyM > maximumAccuracyM || position.horizontalAccuracyM > maximumAccuracyM
        ) return waitingForRouteJoinUpdate(matchAlignment)
        val guidanceLocation = position.toTrustedLocation()
        val projections = requireNotNull(routeGeometry).segments.filter { it.lengthM > 0.01 && it.lengthM.isFinite() }
            .map { it to projectToSegment(guidanceLocation, it.start, it.end) }
        val nearest = projections.minByOrNull { it.second.distanceM }
            ?: return waitingForRouteJoinUpdate(matchAlignment)
        val segment = nearest.first
        val projection = nearest.second
        if (!projection.distanceM.isFinite() || projection.distanceM > MAXIMUM_ROUTE_APPROACH_DISTANCE_M)
            return waitingForRouteJoinUpdate(matchAlignment)
        val target = RoutePoint(
            segment.start.latitude + (segment.end.latitude - segment.start.latitude) * projection.fraction,
            LocalGeodesy.wrapLongitudeDegrees(segment.start.longitude +
                LocalGeodesy.wrapLongitudeDegrees(segment.end.longitude - segment.start.longitude) * projection.fraction),
        )
        val distanceM = haversineMeters(position.point.latitude, position.point.longitude,
            target.latitude, target.longitude)
        // Include disagreement between raw and filtered positions; never turn a position error
        // circle containing the target into a precise clock direction.
        val uncertaintyM = maxOf(rawLocation.accuracyM.toDouble(), position.horizontalAccuracyM) +
            haversineMeters(rawLocation.latitude, rawLocation.longitude,
                position.point.latitude, position.point.longitude)
        val bearing = if (distanceM > uncertaintyM) bearingDegrees(
            position.point.latitude, position.point.longitude, target.latitude, target.longitude,
        ).toDouble() else null
        // Other equally near legs inside the position error may lie on the opposite side.
        // A deterministic nearest point is a reference, not evidence for choosing that direction.
        val candidateSpreadDegrees = if (bearing != null) projections.filter {
            it.second.distanceM <= projection.distanceM + uncertaintyM * 2.0
        }.maxOf { (candidate, candidateProjection) ->
            val candidateTarget = RoutePoint(
                candidate.start.latitude + (candidate.end.latitude - candidate.start.latitude) * candidateProjection.fraction,
                LocalGeodesy.wrapLongitudeDegrees(candidate.start.longitude +
                    LocalGeodesy.wrapLongitudeDegrees(candidate.end.longitude - candidate.start.longitude) * candidateProjection.fraction),
            )
            val candidateDistance = haversineMeters(position.point.latitude, position.point.longitude,
                candidateTarget.latitude, candidateTarget.longitude)
            if (candidateDistance <= uncertaintyM) 180.0 else {
                val candidateBearing = bearingDegrees(position.point.latitude, position.point.longitude,
                    candidateTarget.latitude, candidateTarget.longitude).toDouble()
                abs((candidateBearing - bearing + 540.0) % 360.0 - 180.0)
            }
        } else 0.0
        val selection = RouteAlignmentSelection(
            RouteAlignmentDiagnostic(RouteAlignmentReason.DEPARTURE_CONNECTOR, segmentIndex = segment.index,
                bearingDegreesTrueNorth = bearing),
            target = target,
            bearingUncertaintyDegrees = if (bearing != null) {
                Math.toDegrees(asin((uncertaintyM / distanceM).coerceIn(0.0, 1.0))) + candidateSpreadDegrees
            } else 0.0,
        )
        val context = RouteGuidanceContext(
            routeRevision, rawLocation, guidanceLocation, RouteInstruction("", null, distanceM.roundToInt()),
            selection, positionEstimateUncertain = true, offRoute = false, stepProgressConsistent = null,
            directionOnly = true, initialJoinReference = true,
        )
        latestGuidanceContext = context
        return offerGuidance(currentRoute, context, nowMs, facingObservation,
            facingUnavailableReason = facingUnavailableReason)
    }

    private fun initialRouteJoinReferenceInstruction(
        facing: RouteFacingGuidanceResult,
        compassAvailable: Boolean,
        distanceM: Int?,
        bearingAvailable: Boolean,
        unavailableReason: RouteCompassHeadingReason?,
    ): String {
        val direction = when {
            !compassAvailable -> initialRouteJoinFacingRecovery(unavailableReason)
            !bearingAvailable -> "현재 위치 오차로 가장 가까운 경로 지점의 방향을 확정하기 어렵습니다. "
            facing.clockHour != null ->
                "가장 가까운 경로 지점은 바라보고 있는 방향 기준 약 ${facing.clockHour}시 방향, "
            else -> "가장 가까운 경로 지점의 방향을 확정하기 어렵습니다. 방향을 다시 확인해 주세요. "
        }
        val distance = distanceM?.let { "직선거리 약 ${it}미터입니다. " }.orEmpty()
        return (direction + distance).trim()
    }

    private fun initialRouteJoinFacingRecovery(reason: RouteCompassHeadingReason?): String = when (reason) {
        null -> "휴대전화 방향을 확인할 수 없습니다. 휴대전화를 앞으로 향하게 해 주세요. "
        RouteCompassHeadingReason.REAR_AXIS_VERTICAL, RouteCompassHeadingReason.GRAVITY_UNSTABLE,
        RouteCompassHeadingReason.QUALITY_LOW, RouteCompassHeadingReason.HEADING_ACCURACY,
        RouteCompassHeadingReason.MAGNETIC_FIELD_ANOMALY -> reason.spokenRecovery()
        else -> reason.spokenRecovery() + "잠시 멈춰 방향을 기다려 주세요. "
    }

    private fun deviationDecisionRequiredUpdate(): RouteNavigatorUpdate {
        return RouteNavigatorUpdate(
            instruction = "경로를 벗어나 방향 안내를 멈췄습니다. " +
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

    /** Only current installed-route geometry may retain speech; this never accepts a position. */
    @Synchronized
    fun hasCurrentRouteGeometryGrace(nowMs: Long): Boolean {
        val episode = routeGeometryRecovery ?: return false
        val context = episode.retained ?: return false
        return episode.routeRevision == routeRevision && nowMs >= episode.startedAtMs && nowMs < episode.deadlineMs &&
            !confirmedDeviationLatched && context.rawLocation.hasCurrentCoordinates(nowMs) &&
            context.guidanceLocation.hasCurrentCoordinates(nowMs) && !context.mayHaveReachedTheNextBranch(nowMs) &&
            context.instruction.guideIndex == route?.guidePoints?.getOrNull(nextGuideIndex)?.let { nextGuideIndex }
    }

    /** Read the clock only when this presentation actually has retained geometry to evaluate. */
    @Synchronized
    fun hasCurrentRouteGeometryGrace(elapsedRealtimeMs: () -> Long): Boolean =
        routeGeometryRecovery?.retained != null && hasCurrentRouteGeometryGrace(elapsedRealtimeMs())

    private fun beginAutomaticPositionRecovery() {
        if (route == null || confirmedDeviationLatched ||
            (deviationSuspectedLatched && routeGeometryRecovery == null)) return
        val now = nowElapsedRealtimeMs()
        // Position authority is gone: no extra retention period or old numeric playback is granted.
        routeGeometryRecovery = RouteGeometryRecoveryEpisode(routeRevision, now, now, null,
            latestLocationSample?.rawLocation?.elapsedRealtimeMs ?: -1L,
            latestLocationSample?.filteredPosition?.elapsedRealtimeMs ?: -1L, problemPublished = true)
    }

    private fun routeGeometryRecoveryUpdate(
        currentRoute: WalkingRoute, sample: RouteLocationSample, nowMs: Long,
        match: RouteCorridorMatchResult, diagnostic: RouteAlignmentDiagnostic,
        previousContext: RouteGuidanceContext?, previousMatch: RouteCorridorMatchResult?,
        facing: RouteFacingObservation?, unavailable: RouteCompassHeadingReason?,
    ): RouteNavigatorUpdate? {
        if (allowDegradedRouteGuidance || confirmedDeviationLatched ||
            pendingDecision in setOf(RouteNavigatorUserDecision.REROUTE, RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION)) return null
        val ambiguous = match.quality == RouteMatchQuality.LOW && match.reason == RouteMatchReason.AMBIGUOUS_CANDIDATES
        if (match.reason == RouteMatchReason.CORRIDOR_OUTSIDE ||
            (match.crossTrackDistanceM?.minus(sample.filteredPosition.horizontalAccuracyM) ?: 0.0) > config.offRouteDistanceM) {
            routeGeometryRecovery = null
            return null
        }
        var episode = routeGeometryRecovery
        if (episode == null) {
            if (!ambiguous || deviationSuspectedLatched) return null
            val retained = previousContext?.takeIf {
                previousMatch?.quality in setOf(RouteMatchQuality.HIGH, RouteMatchQuality.MEDIUM) &&
                    it.routeRevision == routeRevision && nowMs >= it.rawLocation.elapsedRealtimeMs &&
                    nowMs - it.rawLocation.elapsedRealtimeMs <= 3000L && !it.instruction.destinationNotice &&
                    !it.instruction.passageConfirmation
            }
            episode = RouteGeometryRecoveryEpisode(routeRevision, nowMs,
                if (nowMs > Long.MAX_VALUE - 9000L) Long.MAX_VALUE else nowMs + 9000L,
                retained, sample.rawLocation.elapsedRealtimeMs, sample.filteredPosition.elapsedRealtimeMs)
        }
        if (episode.routeRevision != routeRevision) { routeGeometryRecovery = null; return null }
        if (ambiguous) episode = episode.copy(lastBadRawAtMs = sample.rawLocation.elapsedRealtimeMs,
            lastBadFilteredAtMs = sample.filteredPosition.elapsedRealtimeMs, firstRecovery = null)
        else if (!sample.rawLocation.navigationOnly && !sample.filteredPosition.navigationOnly &&
            match.quality in setOf(RouteMatchQuality.HIGH, RouteMatchQuality.MEDIUM) &&
            sample.rawLocation.accuracyM <= LocationTrustConfig().maxAccuracyM &&
            sample.filteredPosition.horizontalAccuracyM <= LocationTrustConfig().maxAccuracyM &&
            sample.rawLocation.elapsedRealtimeMs > episode.lastBadRawAtMs &&
            sample.filteredPosition.elapsedRealtimeMs > episode.lastBadFilteredAtMs
        ) {
            val first = episode.firstRecovery
            val reachable = first?.let {
                val elapsed = minOf(sample.rawLocation.elapsedRealtimeMs - it.sample.rawLocation.elapsedRealtimeMs,
                    sample.filteredPosition.elapsedRealtimeMs - it.sample.filteredPosition.elapsedRealtimeMs)
                val reach = elapsed / 1000.0 * progressPriorSpeedBoundMps +
                    sample.filteredPosition.horizontalAccuracyM + it.sample.filteredPosition.horizontalAccuracyM
                // The distance along this installed route, not its arbitrary vertex spacing,
                // bounds recovery. A nearby parallel return leg has a different path distance.
                elapsed > 0L && match.segmentIndex != null && it.match.segmentIndex != null &&
                    routeGeometry?.segments?.getOrNull(match.segmentIndex) != null &&
                    routeGeometry?.segments?.getOrNull(it.match.segmentIndex) != null &&
                    match.geometricProgressM != null && it.match.geometricProgressM != null &&
                    abs(match.geometricProgressM - it.match.geometricProgressM) <= reach &&
                    haversineMeters(sample.rawLocation.latitude, sample.rawLocation.longitude,
                        it.sample.rawLocation.latitude, it.sample.rawLocation.longitude) <= reach
            } == true
            if (reachable) {
                routeGeometryRecovery = null
                deviationSuspectedLatched = false; offRouteGuidanceSuspended = false
                locationRecheckAuthorized = false; rerouteApprovedForCurrentDeviation = false
                updatePendingDecision(null)
                return null
            }
            val changedBranch = episode.retained?.alignment?.diagnostic?.segmentIndex?.let {
                match.segmentIndex != null && !sameOrderedStraightSegment(routeGeometry, it, match.segmentIndex)
            } == true
            if (changedBranch) invalidateSpeechCue()
            episode = episode.copy(firstRecovery = RouteGeometryRecoverySample(sample, match),
                retained = episode.retained.takeUnless { changedBranch })
        } else episode = episode.copy(firstRecovery = null)
        routeGeometryRecovery = episode
        // The current matcher observation remains diagnostic only until recovery is confirmed.
        latestRouteMatchUsableForGuidance = false
        resetRewindEvidence(); interruptPassedTurnEvidence(); arrivalSampleCount = 0; lastArrivalRawSampleAtMs = null; lastArrivalFilteredSampleAtMs = null; firstArrivalRawSampleAtMs = null; firstArrivalFilteredSampleAtMs = null
        if (hasCurrentRouteGeometryGrace(nowMs)) {
            latestGuidanceContext = episode.retained
            latestGuidanceSourceAllowed = true
            return offerGuidance(currentRoute, requireNotNull(episode.retained), nowMs, facing,
                facingUnavailableReason = unavailable)
        }
        latestGuidanceContext = null
        latestGuidanceSourceAllowed = false
        val expired = nowMs >= episode.deadlineMs
        val publishProblem = expired && !episode.problemPublished
        if (expired) {
            offRouteGuidanceSuspended = true; deviationSuspectedLatched = true; locationRecheckAuthorized = false
            updatePendingDecision(RouteNavigatorUserDecision.LOCATION_RECHECK)
            if (publishProblem) invalidateSpeechCue()
            routeGeometryRecovery = episode.copy(problemPublished = true)
        }
        return RouteNavigatorUpdate(
            instruction = if (publishProblem) "가까운 경로를 다시 확인하고 있습니다. 확인되면 길 안내를 자동으로 이어갑니다." else null,
            arrived = false, offRoute = false, shouldReroute = false,
            reason = if (expired) "route_geometry_recheck_required" else "route_geometry_recheck_wait",
            pendingUserDecision = pendingDecision, cancelStaleNavigationSpeech = publishProblem,
            routeAlignmentDiagnostic = diagnostic,
        )
    }

    /** S07-25/26: an unresolved/outside position match needs a recheck, not confirmed deviation. */
    private fun routeMatchLocationRecheckUpdate(
        alignment: RouteAlignmentDiagnostic,
        matchReason: RouteMatchReason,
    ): RouteNavigatorUpdate {
        val alreadyPending = pendingDecision == RouteNavigatorUserDecision.LOCATION_RECHECK
        offRouteGuidanceSuspended = true
        deviationSuspectedLatched = true
        locationRecheckAuthorized = false
        resetRewindEvidence()
        interruptPassedTurnEvidence()
        invalidateSpeechCue()
        updatePendingDecision(RouteNavigatorUserDecision.LOCATION_RECHECK)
        val update = suspectedDeviationUpdate(
            reason = "route_match_untrusted",
            instruction = (if (matchReason == RouteMatchReason.CORRIDOR_OUTSIDE) {
                "현재 위치가 경로에 있는지 확인하기 어려워 방향 안내를 중지했습니다. "
            } else {
                "현재 위치에서 가까운 경로를 구분하기 어려워 방향 안내를 중지했습니다. "
            }) + "위치 다시 확인이라고 말씀해 주세요.",
        )
        return update.copy(
            instruction = update.instruction.takeUnless { alreadyPending },
            routeAlignmentDiagnostic = alignment,
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
        resetDirectionRecheckGrace()
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

    private fun invalidateSpeechCue(preserveInformation: Boolean = false) {
        recoveredDirectionGracePacket = null
        normalDirectionGracePacket = null
        freshDirectionStartAuthorization = null
        offeredSpeechCue = null
        offeredInformationUtterance = null
        if (!preserveInformation) clearInformation(cancelSpeech = true)
        offeredFacingMessage = null
        offeredFacing = null
        offeredCompletion = null
        offeredDestinationNotice = false
        offeredInitialJoinReference = false
        offeredInitialJoinContext = null
        offeredGuidanceContext = null
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
    ): RouteFacingGuidanceResult = facingContinuityGate.stabilizeClockSector(facing, observation)

    private fun resetClockSector() = facingContinuityGate.resetClockSector()

    private fun gateFacingSource(
        facing: RouteFacingGuidanceResult,
        observation: RouteFacingObservation?,
    ): RouteFacingGuidanceResult = facingContinuityGate.gateFacingSource(facing, observation)

    private fun facingSourceChanged(observation: RouteFacingObservation): Boolean =
        facingContinuityGate.facingSourceChanged(observation)

    private fun hasConfirmedFacingDirection(facing: RouteFacingGuidanceResult): Boolean =
        facingContinuityGate.hasConfirmedFacingDirection(facing)

    private fun resetFacingSourceGate() {
        // Retire the navigator's retained speech claims before clearing source confirmation.
        resetDirectionRecheckGrace()
        facingContinuityGate.resetFacingSourceGate()
    }

    private fun directionReferenceInstruction(
        facing: RouteFacingGuidanceResult,
        compassAvailable: Boolean,
        reason: RouteAlignmentReason,
        unavailableRecovery: String = "",
    ): String {
        val subject = when (reason) {
            RouteAlignmentReason.DEPARTURE_CONNECTOR -> "가장 가까운 경로 지점은"
            RouteAlignmentReason.DEPARTURE_SEGMENT -> "출발 경로는"
            else -> "경로 진행 방향은"
        }
        val direction = directionDescription(subject, facing.clockHour, compassAvailable, unavailableRecovery)
        return direction + if (reason == RouteAlignmentReason.DEPARTURE_CONNECTOR) {
            "현재 위치에서 경로까지 걸어갈 수 있는 통로는 확인되지 않았습니다."
        } else {
            "현재 경로 위치를 확인 중이며, 남은 거리와 회전 지점은 아직 안내할 수 없습니다."
        }
    }

    /** Where the route lies relative to the phone's rear camera, as a description without an action. */
    private fun directionDescription(
        subject: String,
        clockHour: Int?,
        compassAvailable: Boolean,
        unavailableRecovery: String = "",
    ): String {
        return clockHour?.let { "약 ${it}시 방향" }
            ?.let { "$subject 바라보고 있는 방향 기준 ${it}입니다. " }
            // A measured but ambiguous direction is not the same as a missing camera direction.
            ?: if (compassAvailable) {
                "나침반 방향은 측정되지만 $subject 시계 방향을 확정하기 어렵습니다. 방향을 다시 확인해 주세요. "
            } else {
                "사용자 방향을 확인할 수 없어 다음 방향 안내를 보류합니다. $unavailableRecovery"
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
        if (clock != null) {
            val subject = when (alignmentReason) {
                RouteAlignmentReason.DEPARTURE_CONNECTOR -> "가장 가까운 경로 지점은 "
                RouteAlignmentReason.DEPARTURE_SEGMENT -> "출발 경로는 "
                else -> ""
            }
            // Distance is appended only to the final sentence, not the facing-message cue key.
            return "바라보고 있는 방향 기준 $subject$clock"
        }
        return when {
            !routeBearingAvailable -> "현재 위치에서 경로의 진행 방향을 정확히 구분하기 어렵습니다. "
            !compassAvailable -> "사용자 방향을 확인할 수 없어 다음 방향 안내를 보류합니다. $unavailableRecovery"
            direction == RouteFacingDirection.UNKNOWN -> "나침반 방향은 측정되지만 시계 방향을 확정하기 어렵습니다. "
            else -> "시계 방향을 확정하기 어렵습니다. "
        }
    }

    /** The clock describes the current camera frame; the distance and next turn use the route. */
    private fun clockGuidanceContinuation(instruction: RouteInstruction, direction: RouteFacingDirection): String {
        val distance = when {
            instruction.guideIndex == null -> "입니다. ${instruction.text} "
            instruction.distanceM != null ->
                ", 다음 안내 지점까지 경로를 따라 약 ${instruction.distanceM}미터입니다. "
            else -> "입니다. 다음 안내 지점까지 거리를 확인 중입니다. "
        }
        val action = if (direction == RouteFacingDirection.FRONT) {
            instruction.actionText.orEmpty()
        } else {
            "안내한 방향으로 몸을 돌려주세요."
        }
        return distance + action
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

    private fun projectToRoute(
        location: TrustedLocation,
        route: WalkingRoute,
        previousDistanceFromStartM: Double?,
        maximumBacktrackM: Double,
        maximumAdvanceM: Double,
        continuityDistanceToleranceM: Double = 25.0,
    ): RouteProjection? = projectToCachedRoute(
        location, route, requireNotNull(routeGeometry), previousDistanceFromStartM,
        maximumBacktrackM, maximumAdvanceM, continuityDistanceToleranceM,
        latestRouteMatch?.segmentIndex,
    )

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
        val geometry = requireNotNull(routeGeometry)
        val geometricTotalM = geometry.totalM
        if (geometricTotalM <= 0.0 || !geometricTotalM.isFinite()) return null
        val scale = route.summary.distanceM.toDouble().takeIf { it > 0.0 }?.div(geometricTotalM) ?: 1.0
        val location = TrustedLocation(point.latitude, point.longitude, 0f, 0L)
        val longitudeScale = kr.co.hanium.dreamup.walksafe.navigation.positioning.LocalGeodesy.metersPerLongitudeDegree(point.latitude)
        var best: Double? = null
        for (segment in geometry.segments) {
            val startM = segment.cumulativeM * scale
            val endM = (segment.cumulativeM + segment.lengthM) * scale
            if (endM < minimumM - TURN_GUIDE_DISTANCE_TOLERANCE_M) continue
            val currentBest = best
            val possibleErrorM = maxOf(0.0, startM - declaredM, declaredM - endM)
            if (currentBest != null && possibleErrorM >= abs(currentBest - declaredM)) continue
            if (projectionBoundsDistance(location, segment.start, segment.end, longitudeScale) > 30.0 + 1e-9) continue
            val projected = projectToSegment(location, segment.start, segment.end, longitudeScale)
            val progressM = (segment.cumulativeM + segment.lengthM * projected.fraction) * scale
            if (projected.distanceM > 30.0 || progressM < minimumM - TURN_GUIDE_DISTANCE_TOLERANCE_M) continue
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
        val geometricTotalM = requireNotNull(routeGeometry).totalM
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
        guide.turnType in setOf(12, 13, 14, 16, 17, 18, 19, 125, 126, 128, 218) || CrosswalkReferencePolicy.isCrosswalkGuide(guide)

    private fun hasUnpassedProtectedGuide(route: WalkingRoute): Boolean =
        (nextGuideIndex until route.guidePoints.size).any { index ->
            val guide = route.guidePoints[index]
            isProtectedGuide(guide) && !isDepartureGuide(guide, guideProgressDistancesM.getOrNull(index))
        }

    /** The first protected guide can itself be the endpoint: display progress is clamped there.
     * Require both paired observations, including their uncertainty, beyond that guide. */
    private fun firstProtectedTerminalGuidePassed(
        route: WalkingRoute, raw: TrustedLocation, guideProgressM: Double?, matched: Boolean,
    ): Boolean {
        if (!matched || guideProgressM == null || lastPassedImportantTurnRawAtMs != null) return false
        val sample = latestLocationSample?.takeIf { it.rawLocation.hasSameObservationAs(raw) } ?: return false
        val filtered = sample.filteredPosition
        if (raw.elapsedRealtimeMs != filtered.elapsedRealtimeMs) return false
        val match = currentAcceptedRouteMatchFor(filtered.elapsedRealtimeMs) ?: return false
        if (match.segmentIndex != route.polyline.lastIndex - 1 || match.segmentFraction != 1.0 ||
            match.reason in setOf(RouteMatchReason.AMBIGUOUS_CANDIDATES,
                RouteMatchReason.ACTIVE_BRANCH_RETAINED, RouteMatchReason.BRANCH_SWITCH_PENDING)) return false
        val bearing = match.bearingDeg ?: return false
        val geometry = routeGeometry ?: return false
        if (geometry.totalM <= 0.0 || route.summary.distanceM <= 0) return false
        val gap = haversineMeters(raw.latitude, raw.longitude, filtered.point.latitude, filtered.point.longitude)
        if (gap > raw.accuracyM + filtered.horizontalAccuracyM) return false
        val end = route.polyline.last()
        fun lowerProgress(point: RoutePoint, accuracy: Double): Double {
            val distance = haversineMeters(end.latitude, end.longitude, point.latitude, point.longitude)
            val heading = bearingDegrees(end.latitude, end.longitude, point.latitude, point.longitude)
            val advance = distance * cos(Math.toRadians((heading - bearing).toDouble()))
            return (geometry.totalM + advance - maxOf(MINIMUM_GUIDE_PASSAGE_MARGIN_M, accuracy)) *
                route.summary.distanceM / geometry.totalM
        }
        return lowerProgress(RoutePoint(raw.latitude, raw.longitude), raw.accuracyM.toDouble()) > guideProgressM &&
            lowerProgress(filtered.point, filtered.horizontalAccuracyM) > guideProgressM
    }

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
        val currentFilteredAtMs = latestLocationSample?.takeIf { it.rawLocation.hasSameObservationAs(rawLocation) }
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
            val sample = latestLocationSample?.takeIf { it.rawLocation.hasSameObservationAs(rawLocation) }
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
            val geometricRouteDistanceM = requireNotNull(routeGeometry).totalM
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
            val passed = (passageProgressM != null && guideProgressM != null &&
                passageProgressM >= guideProgressM + passedMarginM) ||
                (protectedGuide && firstProtectedTerminalGuidePassed(route, rawLocation, guideProgressM, matchedForTurnEvidence))
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

    /** An entrance check cannot replace a turn, crossing, facility guide or its passage check. */
    private fun mayOfferDestinationApproach(route: WalkingRoute, instruction: RouteInstruction): Boolean {
        if (instruction.passageConfirmation || instruction.destinationNotice) return false
        val guide = route.guidePoints.getOrNull(nextGuideIndex) ?: return true
        return guide.turnType == 201 && !CrosswalkReferencePolicy.isCrosswalkGuide(guide)
    }

    /** Provider coordinate families/alternatives never prove a walkable entrance or final link. */
    private fun unconfirmedDestinationAccessWarning(): String {
        val candidates = requestedDestinationCoordinateEvidence?.candidates
        val provenance = if (!candidates.isNullOrEmpty()) {
            "지도에서 받은 목적지 위치는"
        } else "목적지 위치는"
        return provenance + " 실제 출입구와 다를 수 있습니다. " +
            "출입구까지의 통로는 확인되지 않았습니다. "
    }

    private fun instructionForCurrentGuide(
        route: WalkingRoute, location: TrustedLocation,
        speechProgressM: Double? = progressDistanceM,
        allowPassageConfirmation: Boolean = true,
    ): RouteInstruction {
        val guide = route.guidePoints.getOrNull(nextGuideIndex)
        if (guide != null) {
            val guideProgressM = guideProgressDistancesM.getOrNull(nextGuideIndex)
            if (allowPassageConfirmation && speechProgressM?.let { progressM -> guideProgressM?.let { progressM > it } } == true) {
                return RouteInstruction(
                    text = "안내 지점 통과 여부를 확인 중입니다.",
                    guideIndex = nextGuideIndex,
                    passageConfirmation = true,
                )
            }
            val routeDistanceToGuideM = speechProgressM?.let { progressM ->
                guideProgressM?.let { (it - progressM).coerceAtLeast(0.0).roundToInt() }
            }
            CrosswalkReferencePolicy.noticeFor(guide)?.let { notice ->
                // Only mapped ordinary maneuvers and directional crossing references survive
                // a crossing marker, never provider commands or signal-based permission to cross.
                val maneuver = guide.takeIf { it.turnType in setOf(11, 12, 13, 14, 16, 17, 18, 19, 212, 213, 214, 215, 216, 217) }
                    ?.maneuverInstruction() ?: "횡단보도 안내 지점입니다."
                val prefix = routeDistanceToGuideM?.let { "${it}m 앞, " } ?: ""
                return RouteInstruction(
                    text = "$prefix$maneuver $notice",
                    guideIndex = nextGuideIndex,
                    distanceM = routeDistanceToGuideM,
                    actionText = "다음 안내 지점에서 경로 기준으로 $maneuver $notice",
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
                actionText = instruction?.let {
                    guide.facilityNameKo()?.let { facility -> "다음 안내 지점은 $facility 진입 지점입니다." }
                        ?: "다음 안내 지점에서 경로 기준으로 $it"
                },
            )
        }
        val endpoint = route.polyline.last()
        val destination = requestedDestination ?: endpoint
        val endpointGapM = haversineMeters(endpoint.latitude, endpoint.longitude, destination.latitude, destination.longitude)
        val routeRemainingM = speechProgressM?.let { progressM ->
            (route.summary.distanceM - progressM).coerceAtLeast(0.0).roundToInt()
        }
        val routeEndGapNotice = endpointGapM > config.arrivalRadiusM && routeRemainingM != null &&
            routeRemainingM <= maxOf(20, location.accuracyM.roundToInt())
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
        return RouteInstruction(text = text, guideIndex = null, distanceM = routeRemainingM,
            routeEndGapNotice = routeEndGapNotice)
    }
}

private fun TrustedLocation.hasCurrentCoordinates(nowMs: Long): Boolean =
    latitude.isFinite() && latitude in -90.0..90.0 &&
        longitude.isFinite() && longitude in -180.0..180.0 &&
        accuracyM.isFinite() && accuracyM >= 0f && elapsedRealtimeMs >= 0L &&
        nowMs >= elapsedRealtimeMs && nowMs - elapsedRealtimeMs <= LocationTrustConfig().maxAgeMs

/** Shared by the actor and the read-only callback classifier; sector confirmation is separate. */
private fun RouteFacingGuidanceResult.losesEvidenceTo(
    measured: RouteFacingGuidanceResult,
    current: RouteFacingGuidanceResult,
): Boolean =
    (direction != RouteFacingDirection.UNKNOWN && current.direction == RouteFacingDirection.UNKNOWN) ||
        (clockRange != null && !clockReferenceOverlaps(measured)) ||
        (clockHour != null && measured.clockHour == null && !measured.clockPrecisionAvailable &&
            !clockReferenceOverlaps(measured)) ||
        current.direction == direction.reversed() || commandReversedBy(current)

/** Full support for a not-yet-started packet, independently of adjacent PLAYING continuity. */
private fun RouteFacingGuidanceResult.fullySupportedBy(current: RouteFacingGuidanceResult): Boolean =
    direction == current.direction && if (clockRange != null) rangeCovers(current)
    else (clockHour != null && clockHour == current.clockHour && current.clockRange == null) ||
        sharesNominalClockBoundaryWith(current)

/** Compare with the original offered representative, never accumulate small later turns. */
private fun RouteFacingGuidanceResult.sharesNominalClockBoundaryWith(current: RouteFacingGuidanceResult): Boolean {
    if (direction != current.direction || clockReferenceKind != RouteClockReferenceKind.NOMINAL_TREND_CLOCK ||
        current.clockReferenceKind != RouteClockReferenceKind.NOMINAL_TREND_CLOCK
    ) return false
    val offeredHour = clockHour ?: return false
    val currentHour = current.clockHour ?: return false
    val clockwiseSteps = (currentHour - offeredHour + 12) % 12
    if (clockwiseSteps != 1 && clockwiseSteps != 11) return false
    val offeredAngle = signedDifferenceDegrees ?: return false
    val currentAngle = current.signedDifferenceDegrees ?: return false
    return abs((currentAngle - offeredAngle + 540.0) % 360.0 - 180.0) <=
        NOMINAL_CLOCK_BOUNDARY_CONTINUITY_DEGREES
}

/** Partial nominal overlap retains grace; full same-command support can recover. */
private fun RouteFacingGuidanceResult.isWeakerNominalClockFor(offered: RouteFacingGuidanceResult?): Boolean =
    clockReferenceKind == RouteClockReferenceKind.NOMINAL_TREND_CLOCK &&
        offered?.clockReferenceKind == RouteClockReferenceKind.REPORTED_ERROR &&
        offered.clockReferenceOverlaps(this) && !offered.fullySupportedBy(this)

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

enum class RouteDirectionGraceMode { NORMAL, GRACE_RETAINED, RECHECK_WAIT, PROBLEM }
enum class RouteDirectionGraceAuthorization { NORMAL_PENDING, GRACE_PACKET, RECOVERED_GRACE_PACKET, NORMAL_FRESH_REVALIDATED }

/** Read-only diagnostic values. Times and quality come from the original evidence, never a new fix. */
data class RouteDirectionGraceSnapshot(
    val mode: RouteDirectionGraceMode,
    val episodeId: Long?,
    val onsetElapsedRealtimeMs: Long?,
    val deadlineElapsedRealtimeMs: Long?,
    val reason: RouteCompassHeadingReason?,
    val retainedObservedAtElapsedRealtimeMs: Long?,
    val retainedSource: RouteFacingSource?,
    val retainedReportedHeadingAccuracyDegrees: Double?,
    val authorizedCueRevision: Long? = null,
    val authorizationKind: RouteDirectionGraceAuthorization? = null,
    val authorizationEpisodeId: Long? = null,
    val authorizationDeadlineElapsedRealtimeMs: Long? = null,
    val authorizationObservedAtElapsedRealtimeMs: Long? = null,
)

private data class RetainedRouteDirection(
    val routeRevision: Long,
    val guideIndex: Int?,
    val directionOnly: Boolean,
    val initialJoinReference: Boolean,
    val bearingDegrees: Double?,
    val segmentIndex: Int?,
    val geometry: RouteGeometryCache?,
    val referencePhase: RouteAlignmentReason,
    val classifiedAtMs: Long,
    val observation: RouteFacingObservation,
    val facing: RouteFacingGuidanceResult,
    val normalValidUntilMs: Long,
) {
    fun matches(context: RouteGuidanceContext): Boolean = routeRevision == context.routeRevision &&
        guideIndex == context.instruction.guideIndex && directionOnly == context.directionOnly &&
        initialJoinReference == context.initialJoinReference &&
        sameOrderedStraightSegment(geometry, segmentIndex, context.alignment.diagnostic.segmentIndex) &&
        referencePhase == context.alignment.diagnostic.reason

    fun facingFor(context: RouteGuidanceContext): RouteFacingGuidanceResult? {
        if (!matches(context)) return null
        val cached = RouteFacingGuidance.evaluateRetainedReference(context.alignment.bearingDegrees,
            observation, classifiedAtMs, context.alignment.bearingUncertaintyDegrees)
        return cached.takeIf { it.hasClockReference && it.direction == facing.direction &&
            facing.clockReferenceOverlaps(it) }
    }
}

/** A provider's split of one ordered straight leg is not a new movement target.
 * Require every intervening leg to keep the same bearing; a parallel return branch or corner
 * cannot qualify merely because its endpoint bearing happens to agree. The one-degree bound
 * is the existing RouteCorridorMatcher straight-run classification, not a facing grace change.
 */
private fun sameOrderedStraightSegment(geometry: RouteGeometryCache?, previous: Int?, current: Int?): Boolean {
    if (previous == current) return true
    if (geometry == null || previous == null || current == null) return false
    val first = minOf(previous, current)
    val last = maxOf(previous, current)
    if (first < 0 || last >= geometry.segments.size) return false
    val reference = geometry.segments[first].bearingDeg.toDouble()
    if (!reference.isFinite()) return false
    return (first..last).all { index ->
        val segment = geometry.segments[index]
        segment.lengthM.isFinite() && segment.lengthM > 0.01 && segment.bearingDeg.isFinite() &&
            abs((segment.bearingDeg.toDouble() - reference + 540.0) % 360.0 - 180.0) <= 1.0
    }
}

private data class RecoveredDirectionGracePacket(
    val token: RouteSpeechCueToken,
    val retained: RetainedRouteDirection,
    val episodeId: Long,
    val startedAtMs: Long,
    val deadlineMs: Long,
    val recoveredAtMs: Long,
)

private data class NormalDirectionGracePacket(
    val token: RouteSpeechCueToken,
    val episodeId: Long,
    val retained: RetainedRouteDirection,
)

private data class FreshDirectionStartAuthorization(
    val token: RouteSpeechCueToken,
    val observation: RouteFacingObservation,
    val authorizedAtMs: Long,
    val validUntilMs: Long,
)

private data class DirectionRecheckEpisode(
    val id: Long,
    val routeRevision: Long,
    val startedAtMs: Long,
    val deadlineMs: Long,
    val lastCheckedAtMs: Long,
    val retained: RetainedRouteDirection?,
    val latestRejectedObservedAtMs: Long,
    val problemPublished: Boolean = false,
    val retentionUsable: Boolean = true,
)

private data class DirectionContinuity(
    val retained: RetainedRouteDirection? = null,
    val retainedFacing: RouteFacingGuidanceResult? = null,
    val waiting: Boolean = false,
    val problemTransition: Boolean = false,
    val revoked: Boolean = false,
    val withholdFreshReference: Boolean = false,
)

/** S07-15 (R7): how an accepted sample relates to a long position gap. */
private enum class GapRealignment { NONE, HOLD, RESUME }

private data class RouteInstruction(
    val text: String,
    val guideIndex: Int?,
    val distanceM: Int? = null,
    val passageConfirmation: Boolean = false,
    val destinationApproach: Boolean = false,
    val routeEndGapNotice: Boolean = false,
    /** Action at the guide only; never an approach-distance sentence reused as an action. */
    val actionText: String? = null,
) {
    // Both explain destination context without a relative direction or a movement claim.
    val destinationNotice: Boolean get() = destinationApproach || routeEndGapNotice
}

private data class CompletedRouteSpeechCue(
    val guideIndex: Int?,
    val distanceBand: Int,
    val directionOnly: Boolean,
    val facingDirection: RouteFacingDirection?,
    val clockHour: Int?,
    val destinationNotice: Boolean = false,
    val besideRoute: Boolean = false,
    val recoveryExplanation: Boolean = false,
    val clockRange: RouteClockRange? = null,
) {
    /** A clock number appearing or disappearing alone does not restart the completed cadence. */
    fun covers(other: CompletedRouteSpeechCue): Boolean =
        guideIndex == other.guideIndex && distanceBand == other.distanceBand &&
            directionOnly == other.directionOnly && facingDirection == other.facingDirection &&
            destinationNotice == other.destinationNotice &&
            besideRoute == other.besideRoute && recoveryExplanation == other.recoveryExplanation &&
            clockRange == other.clockRange &&
            (clockHour == other.clockHour || clockHour == null || other.clockHour == null)
}

private data class RouteGeometryRecoveryEpisode(
    val routeRevision: Long, val startedAtMs: Long, val deadlineMs: Long,
    val retained: RouteGuidanceContext?, val lastBadRawAtMs: Long, val lastBadFilteredAtMs: Long,
    val firstRecovery: RouteGeometryRecoverySample? = null, val problemPublished: Boolean = false,
)
private data class RouteGeometryRecoverySample(val sample: RouteLocationSample, val match: RouteCorridorMatchResult)

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
    val initialJoinReference: Boolean = false,
    /** Observed speed between the last two samples (GNSS beyond uncertainty, or steps); null if none. */
    val recentMovementSpeedMps: Double? = null,
)

private data class RouteLocationSample(
    val rawLocation: TrustedLocation,
    val filteredPosition: FilteredRoutePosition,
) {
    val navigationOnly: Boolean get() = rawLocation.navigationOnly || filteredPosition.navigationOnly
    /** Uncertainty metadata alone must not become a new position or cancel duplicate speech retries. */
    fun hasSameObservationAs(other: RouteLocationSample?): Boolean = other != null &&
        rawLocation.hasSameObservationAs(other.rawLocation) &&
        filteredPosition.copy(uncertaintyKind = other.filteredPosition.uncertaintyKind) == other.filteredPosition

    /** Shared with the user's arrival request; overlap is deliberately not a precise position. */
    fun possiblyNearDestination(destination: RoutePoint, arrivalRadiusM: Double): Boolean {
        val areaM = arrivalRadiusM + 5.0
        val maxAccuracyM = LocationTrustConfig().maxAccuracyM
        return rawLocation.accuracyM <= maxAccuracyM && filteredPosition.horizontalAccuracyM <= maxAccuracyM &&
            haversineMeters(rawLocation.latitude, rawLocation.longitude,
                destination.latitude, destination.longitude) - rawLocation.accuracyM <= areaM &&
            haversineMeters(filteredPosition.point.latitude, filteredPosition.point.longitude,
                destination.latitude, destination.longitude) - filteredPosition.horizontalAccuracyM <= areaM
    }

    fun hasCurrentCoordinates(nowMs: Long): Boolean = rawLocation.hasCurrentCoordinates(nowMs) &&
        filteredPosition.toTrustedLocation().hasCurrentCoordinates(nowMs)
}

private fun TrustedLocation.hasSameObservationAs(other: TrustedLocation): Boolean =
    copy(uncertaintyKind = other.uncertaintyKind) == other

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
    geometry: RouteGeometryCache,
    geometricProgressM: Double,
): Double {
    val geometricTotalM = geometry.totalM
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

private fun projectToCachedRoute(
    location: TrustedLocation,
    route: WalkingRoute,
    geometry: RouteGeometryCache,
    previousDistanceFromStartM: Double?,
    maximumBacktrackM: Double,
    maximumAdvanceM: Double,
    continuityDistanceToleranceM: Double,
    preferredSegmentIndex: Int?,
): RouteProjection? {
    val geometricTotalM = geometry.totalM
    if (geometricTotalM <= 0.0 || !geometricTotalM.isFinite()) return null
    val routeScale = route.summary.distanceM.toDouble().takeIf { it > 0.0 }?.div(geometricTotalM) ?: 1.0
    val longitudeScale = kr.co.hanium.dreamup.walksafe.navigation.positioning.LocalGeodesy.metersPerLongitudeDegree(location.latitude)
    var best: RouteProjection? = null
    var continuousBest: RouteProjection? = null
    val minimumContinuousM = previousDistanceFromStartM?.minus(maximumBacktrackM)?.coerceAtLeast(0.0)
    val maximumContinuousM = previousDistanceFromStartM?.plus(maximumAdvanceM)
    fun project(segment: RouteGeometrySegment): RouteProjection {
        val projected = projectToSegment(location, segment.start, segment.end, longitudeScale)
        return RouteProjection(
            distanceToRouteM = projected.distanceM,
            distanceFromStartM = (segment.cumulativeM + segment.lengthM * projected.fraction) * routeScale,
            bearingDeg = segment.bearingDeg,
            segmentIndex = segment.index,
            segmentStart = segment.start,
            segmentEnd = segment.end,
        )
    }
    fun closer(candidate: RouteProjection, current: RouteProjection?): Boolean = current == null ||
        candidate.distanceToRouteM < current.distanceToRouteM ||
        (candidate.distanceToRouteM == current.distanceToRouteM && candidate.segmentIndex < current.segmentIndex)
    fun consider(segment: RouteGeometrySegment) {
        val boundM = projectionBoundsDistance(location, segment.start, segment.end, longitudeScale)
        val canBeContinuous = minimumContinuousM != null && maximumContinuousM != null &&
            (segment.cumulativeM + segment.lengthM) * routeScale >= minimumContinuousM &&
            segment.cumulativeM * routeScale <= maximumContinuousM
        if (best != null && boundM > requireNotNull(best).distanceToRouteM + 1e-9 &&
            (!canBeContinuous || (continuousBest != null &&
                boundM > requireNotNull(continuousBest).distanceToRouteM + 1e-9))
        ) return
        val candidate = project(segment)
        if (closer(candidate, best)) best = candidate
        if (minimumContinuousM != null && maximumContinuousM != null &&
            candidate.distanceFromStartM in minimumContinuousM..maximumContinuousM && closer(candidate, continuousBest)
        ) continuousBest = candidate
    }
    val nearbyStart = preferredSegmentIndex?.minus(12)?.coerceAtLeast(0)
    val nearbyEnd = preferredSegmentIndex?.plus(12)?.coerceAtMost(geometry.segments.lastIndex)
    if (nearbyStart != null && nearbyEnd != null) {
        for (index in nearbyStart..nearbyEnd) consider(geometry.segments[index])
    }
    // Keep the global minimum and continuous alternative even after a local candidate was found.
    // This restores displacement/re-entry and preserves loops and overlapping route visits.
    for (segment in geometry.segments) {
        if (nearbyStart == null || nearbyEnd == null || segment.index !in nearbyStart..nearbyEnd) consider(segment)
    }
    val globalBest = best ?: return null
    if (previousDistanceFromStartM == null) {
        val start = route.polyline.first()
        val distanceToStartM = haversineMeters(location.latitude, location.longitude, start.latitude, start.longitude)
        if (distanceToStartM <= maxOf(15.0, location.accuracyM.toDouble() * 2.0)) {
            var earliest: RouteProjection? = null
            for (segment in geometry.segments) {
                if (projectionBoundsDistance(location, segment.start, segment.end, longitudeScale) > globalBest.distanceToRouteM + 15.0 + 1e-9) continue
                val candidate = project(segment)
                if (candidate.distanceToRouteM <= globalBest.distanceToRouteM + 15.0 &&
                    (earliest == null || candidate.distanceFromStartM < requireNotNull(earliest).distanceFromStartM)
                ) earliest = candidate
            }
            return earliest ?: globalBest
        }
    }
    val continuous = continuousBest
    return if (continuous != null && continuous.distanceToRouteM <= globalBest.distanceToRouteM + continuityDistanceToleranceM) {
        continuous
    } else {
        globalBest
    }
}

/** A conservative bound in exactly the longitude frame used by this file's projection. */
private fun projectionBoundsDistance(
    location: TrustedLocation,
    start: RoutePoint,
    end: RoutePoint,
    longitudeScale: Double,
): Double {
    val wrap = kr.co.hanium.dreamup.walksafe.navigation.positioning.LocalGeodesy::wrapLongitudeDegrees
    val ax = -wrap(location.longitude - start.longitude) * longitudeScale
    val bx = ax + wrap(end.longitude - start.longitude) * longitudeScale
    val ay = (start.latitude - location.latitude) * kr.co.hanium.dreamup.walksafe.navigation.positioning.LocalGeodesy.METERS_PER_DEGREE
    val by = (end.latitude - location.latitude) * kr.co.hanium.dreamup.walksafe.navigation.positioning.LocalGeodesy.METERS_PER_DEGREE
    val east = maxOf(0.0, minOf(ax, bx), -maxOf(ax, bx))
    val north = maxOf(0.0, minOf(ay, by), -maxOf(ay, by))
    return hypot(east, north)
}

private data class SegmentProjection(val fraction: Double, val distanceM: Double)

private fun projectToSegment(
    location: TrustedLocation,
    start: RoutePoint,
    end: RoutePoint,
    longitudeScale: Double = kr.co.hanium.dreamup.walksafe.navigation.positioning.LocalGeodesy.metersPerLongitudeDegree(location.latitude),
): SegmentProjection {
    val latScale = kr.co.hanium.dreamup.walksafe.navigation.positioning.LocalGeodesy.METERS_PER_DEGREE
    val lonScale = longitudeScale
    val wrap = kr.co.hanium.dreamup.walksafe.navigation.positioning.LocalGeodesy::wrapLongitudeDegrees
    val ax = -wrap(location.longitude - start.longitude) * lonScale
    val ay = (start.latitude - location.latitude) * latScale
    val bx = ax + wrap(end.longitude - start.longitude) * lonScale
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

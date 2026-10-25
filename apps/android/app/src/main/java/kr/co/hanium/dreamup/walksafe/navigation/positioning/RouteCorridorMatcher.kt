package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class EnuPositionCovariance(
    val eastVarianceM2: Double,
    val northVarianceM2: Double,
    val eastNorthCovarianceM2: Double = 0.0,
)

data class RouteHeadingEstimate(
    val degrees: Double,
    val standardDeviationDeg: Double,
)

/** A filtered position. The matcher never mutates or replaces this value. */
data class FilteredRoutePosition(
    val point: RoutePoint,
    val horizontalAccuracyM: Double,
    val elapsedRealtimeMs: Long,
    val covarianceEnu: EnuPositionCovariance? = null,
    val heading: RouteHeadingEstimate? = null,
    val uncertaintyKind: HorizontalUncertaintyKind = HorizontalUncertaintyKind.LEGACY_UNSPECIFIED,
    val navigationOnly: Boolean = false,
) {
    val reportedAccuracyRadius68M: ReportedAccuracyRadius68M?
        get() = horizontalAccuracyM.takeIf {
            uncertaintyKind == HorizontalUncertaintyKind.ANDROID_RADIUS_68 && it.isFinite() && it >= 0.0
        }?.let(::ReportedAccuracyRadius68M)

    val positionMaxAxisSigmaM: PositionMaxAxisSigmaM?
        get() = horizontalAccuracyM.takeIf {
            uncertaintyKind == HorizontalUncertaintyKind.FILTER_MAX_AXIS_SIGMA && it.isFinite() && it >= 0.0
        }?.let(::PositionMaxAxisSigmaM)

    internal fun toTrustedLocation(point: RoutePoint = this.point): TrustedLocation = TrustedLocation(
        latitude = point.latitude,
        longitude = point.longitude,
        accuracyM = horizontalAccuracyM.toFloat(),
        elapsedRealtimeMs = elapsedRealtimeMs,
        uncertaintyKind = uncertaintyKind,
        navigationOnly = navigationOnly,
    )
}

enum class RouteMatchQuality {
    HIGH,
    MEDIUM,
    LOW,
    UNMATCHED,
}

enum class RouteMatchReason {
    MATCHED,
    LOW_CONFIDENCE,
    AMBIGUOUS_CANDIDATES,
    ACTIVE_BRANCH_RETAINED,
    BRANCH_SWITCH_PENDING,
    CORRIDOR_OUTSIDE,

    /** Order error: the sample is not newer than the previous sample of this matcher. */
    STALE_SAMPLE,

    /** S09-50: the sample is older than the caller's maximum age at the evaluation time. */
    EXPIRED_SAMPLE,

    /** S09-50: the sample is measured after the evaluation time. */
    FUTURE_SAMPLE,
    INVALID_INPUT,
    INVALID_ROUTE,
}

data class RouteCorridorMatchResult(
    val routeId: String,
    val filteredPoint: RoutePoint,
    val matchedPoint: RoutePoint?,
    val segmentIndex: Int?,
    val segmentFraction: Double?,
    val crossTrackDistanceM: Double?,
    val geometricProgressM: Double?,
    val bearingDeg: Float?,
    val confidence: Double,
    val quality: RouteMatchQuality,
    val reason: RouteMatchReason,
)

data class RouteCorridorMatcherConfig(
    val baseCorridorWidthM: Double = 8.0,
    val corridorSigmaMultiplier: Double = 2.5,
    val maximumCorridorWidthM: Double = 35.0,
    val minimumPositionSigmaM: Double = 1.0,
    val progressSigmaM: Double = 20.0,
    val minimumHeadingSigmaDeg: Double = 8.0,
    val maximumHeadingSigmaDeg: Double = 45.0,
    val headingWeight: Double = 0.45,
    val progressWeight: Double = 0.35,
    val huberDelta: Double = 2.5,
    val highConfidenceThreshold: Double = 0.7,
    val mediumConfidenceThreshold: Double = 0.4,
    val branchSwitchMinimumCostAdvantage: Double = 1.5,
    val branchSwitchConfirmations: Int = 2,
    val allowReverseTravel: Boolean = false,
    /** Pedestrian speed bound for visit-order reachability; same bound as the motion profile. */
    val maximumTravelSpeedMps: Double = 3.0,
    /**
     * S07-15: widen the progress prior to what the pedestrian speed bound can cover since the
     * previous sample plus twice the reported radius, with [progressSigmaM] as its floor. Off keeps
     * one fixed per-update prior.
     */
    val timeScaledProgressPrior: Boolean = false,
)

/**
 * Stateful matcher for one active route corridor. It does not search a sidewalk graph and does not
 * use future observations. Non-adjacent branch changes are confirmed across successive calls.
 */
class RouteCorridorMatcher(
    private val config: RouteCorridorMatcherConfig = RouteCorridorMatcherConfig(),
) {
    private var activeRouteId: String? = null
    private var activeRouteGeometry: CachedRouteGeometry? = null
    private var activeSegmentIndex: Int? = null
    private var activeCorridorIndex: Int? = null
    private var activeProgressM: Double? = null
    private var activeProgressAtElapsedRealtimeMs: Long? = null
    private var activeBearingDeg: Double? = null
    private var pendingCorridorIndex: Int? = null
    private var pendingSegmentCount = 0
    private var latestSampleElapsedRealtimeMs: Long? = null

    @Synchronized
    fun reset() {
        activeRouteId = null
        activeRouteGeometry = null
        activeSegmentIndex = null
        activeCorridorIndex = null
        activeProgressM = null
        activeProgressAtElapsedRealtimeMs = null
        activeBearingDeg = null
        latestSampleElapsedRealtimeMs = null
        clearPendingSwitch()
    }

    /** Drops only evidence that requires uninterrupted successive samples. */
    @Synchronized
    fun onPositioningEvidenceInterrupted() {
        clearPendingSwitch()
    }

    @Synchronized
    fun match(
        routeId: String,
        polyline: List<RoutePoint>,
        position: FilteredRoutePosition,
        previousProgressM: Double? = null,
        /** S07-15: measurement time of [previousProgressM], for the time-scaled progress prior. */
        previousProgressAtElapsedRealtimeMs: Long? = null,
        /**
         * S09-50: evaluation time of the caller. With it a future sample is FUTURE_SAMPLE and, with
         * [maximumSampleAgeMs], an old one is EXPIRED_SAMPLE, separately from the order error
         * STALE_SAMPLE. Neither consumes the order watermark.
         */
        evaluatedAtElapsedRealtimeMs: Long? = null,
        maximumSampleAgeMs: Long? = null,
    ): RouteCorridorMatchResult {
        if (!position.isValid()) {
            clearPendingSwitch()
            return unmatched(routeId, position.point, RouteMatchReason.INVALID_INPUT)
        }
        if (evaluatedAtElapsedRealtimeMs != null) {
            if (position.elapsedRealtimeMs > evaluatedAtElapsedRealtimeMs) {
                clearPendingSwitch()
                return unmatched(routeId, position.point, RouteMatchReason.FUTURE_SAMPLE)
            }
            if (maximumSampleAgeMs != null && evaluatedAtElapsedRealtimeMs - position.elapsedRealtimeMs > maximumSampleAgeMs) {
                clearPendingSwitch()
                return unmatched(routeId, position.point, RouteMatchReason.EXPIRED_SAMPLE)
            }
        }
        if (polyline.size < 2 || polyline.any { !it.isValid() }) {
            clearPendingSwitch()
            return unmatched(routeId, position.point, RouteMatchReason.INVALID_ROUTE)
        }
        // Compare coordinates without allocating per fix. The cached list owns its snapshot:
        // the public API still detects callers replacing points in place, even with the same id.
        if (activeRouteId != routeId || activeRouteGeometry?.matchesCoordinates(polyline) != true) {
            reset()
            activeRouteId = routeId
            activeRouteGeometry = CachedRouteGeometry(polyline)
        }
        val latestSampleTime = latestSampleElapsedRealtimeMs
        if (latestSampleTime != null && position.elapsedRealtimeMs <= latestSampleTime) {
            return unmatched(routeId, position.point, RouteMatchReason.STALE_SAMPLE)
        }
        val sampleGapMs = latestSampleTime?.let { position.elapsedRealtimeMs - it }
        latestSampleElapsedRealtimeMs = position.elapsedRealtimeMs

        val covariance = position.effectiveCovariance()
        // A straight route is one geometric alternative regardless of its sampling density.
        // Choose its nearest projection before applying the progress prior, so subdividing it
        // cannot introduce extra endpoint choices that pull the match back toward old progress.
        val candidates = buildCandidates(requireNotNull(activeRouteGeometry), position, covariance)
        if (candidates.isEmpty()) {
            clearPendingSwitch()
            return unmatched(routeId, position.point, RouteMatchReason.INVALID_ROUTE)
        }

        val suppliedReference = previousProgressM?.takeIf { it.isFinite() && it >= 0.0 }
        val progressReference = suppliedReference ?: activeProgressM
        // S07-15: the prior covers the time since the reference progress was measured, so samples
        // that were not accepted in between do not shrink it back to one update.
        val referenceAtMs = if (suppliedReference != null) previousProgressAtElapsedRealtimeMs else activeProgressAtElapsedRealtimeMs
        val progressSigmaM = progressPriorSigmaM(
            referenceAtMs?.let { position.elapsedRealtimeMs - it } ?: sampleGapMs, position,
        )
        val scored = candidates.map { candidate ->
            ScoredCandidate(candidate, score(candidate, position, progressReference, progressSigmaM))
        }
        val eligible = scored.filter { scoredCandidate ->
            scoredCandidate.candidate.crossTrackDistanceM <= corridorWidth(scoredCandidate.candidate.crossTrackSigmaM)
        }
        if (eligible.isEmpty()) {
            clearPendingSwitch()
            activeSegmentIndex = null
            val nearest = candidates.minByOrNull(Candidate::crossTrackDistanceM)
            return unmatched(
                routeId = routeId,
                filteredPoint = position.point,
                reason = RouteMatchReason.CORRIDOR_OUTSIDE,
                crossTrackDistanceM = nearest?.crossTrackDistanceM,
            )
        }

        val best = eligible.minByOrNull(ScoredCandidate::cost) ?: error("eligible candidates are non-empty")
        val resolution = resolveBranch(best, eligible, sampleGapMs, position.elapsedRealtimeMs)
        val selected = resolution.selected
        val normalConfidence = confidence(selected, eligible)
        val ambiguousReverseBranch = config.allowReverseTravel && eligible.any { other ->
            competingBranch(selected, other) &&
                abs(selected.candidate.bearingDeg - other.candidate.bearingDeg).let { min(it, 360f - it) } > 135f &&
                other.candidate.crossTrackDistanceM <= max(
                    selected.candidate.crossTrackSigmaM, other.candidate.crossTrackSigmaM,
                ) * 2.0 &&
                !visitOrderExcludes(selected.candidate, other.candidate, position.elapsedRealtimeMs)
        }
        val confidence = if (resolution.forceLowConfidence || ambiguousReverseBranch) {
            min(normalConfidence, config.mediumConfidenceThreshold - 0.001)
        } else {
            normalConfidence
        }.coerceIn(0.0, 1.0)
        val quality = when {
            resolution.forceLowConfidence || ambiguousReverseBranch -> RouteMatchQuality.LOW
            confidence >= config.highConfidenceThreshold -> RouteMatchQuality.HIGH
            confidence >= config.mediumConfidenceThreshold -> RouteMatchQuality.MEDIUM
            else -> RouteMatchQuality.LOW
        }
        val reason = if (ambiguousReverseBranch) RouteMatchReason.AMBIGUOUS_CANDIDATES else resolution.reason ?: when {
            quality != RouteMatchQuality.LOW -> RouteMatchReason.MATCHED
            isAmbiguous(selected, eligible) -> RouteMatchReason.AMBIGUOUS_CANDIDATES
            else -> RouteMatchReason.LOW_CONFIDENCE
        }

        if (
            !resolution.forceLowConfidence && !ambiguousReverseBranch &&
            (quality == RouteMatchQuality.HIGH || quality == RouteMatchQuality.MEDIUM)
        ) {
            activeCorridorIndex = selected.candidate.corridorIndex
            activeProgressM = selected.candidate.geometricProgressM
            activeProgressAtElapsedRealtimeMs = position.elapsedRealtimeMs
            activeBearingDeg = selected.candidate.bearingDeg.toDouble()
            activeSegmentIndex = selected.candidate.segmentIndex
        }

        return RouteCorridorMatchResult(
            routeId = routeId,
            filteredPoint = position.point,
            matchedPoint = selected.candidate.matchedPoint,
            segmentIndex = selected.candidate.segmentIndex,
            segmentFraction = selected.candidate.fraction,
            crossTrackDistanceM = selected.candidate.crossTrackDistanceM,
            geometricProgressM = selected.candidate.geometricProgressM,
            bearingDeg = selected.candidate.bearingDeg,
            confidence = confidence,
            quality = quality,
            reason = reason,
        )
    }

    private fun resolveBranch(
        best: ScoredCandidate,
        eligible: List<ScoredCandidate>,
        sampleGapMs: Long?,
        nowElapsedRealtimeMs: Long,
    ): BranchResolution {
        val activeIndex = activeCorridorIndex ?: return BranchResolution(best)
        if (connectedToActive(best.candidate, activeIndex, nowElapsedRealtimeMs)) {
            clearPendingSwitch()
            return BranchResolution(best)
        }

        val activeBranch = eligible
            .filter { connectedToActive(it.candidate, activeIndex, nowElapsedRealtimeMs) }
            .minByOrNull(ScoredCandidate::cost)
        val advantage = (activeBranch?.cost ?: Double.POSITIVE_INFINITY) - best.cost
        if (advantage < config.branchSwitchMinimumCostAdvantage) {
            clearPendingSwitch()
            return if (activeBranch != null) {
                BranchResolution(
                    selected = activeBranch,
                    reason = RouteMatchReason.ACTIVE_BRANCH_RETAINED,
                    forceLowConfidence = true,
                )
            } else {
                BranchResolution(
                    selected = best,
                    reason = RouteMatchReason.BRANCH_SWITCH_PENDING,
                    forceLowConfidence = true,
                )
            }
        }

        if (
            pendingCorridorIndex == best.candidate.corridorIndex &&
            sampleGapMs != null && sampleGapMs <= MAXIMUM_BRANCH_CONFIRMATION_GAP_MS
        ) {
            pendingSegmentCount += 1
        } else {
            pendingCorridorIndex = best.candidate.corridorIndex
            pendingSegmentCount = 1
        }
        if (pendingSegmentCount >= config.branchSwitchConfirmations.coerceAtLeast(1)) {
            clearPendingSwitch()
            return BranchResolution(best)
        }
        return BranchResolution(
            selected = activeBranch ?: best,
            reason = RouteMatchReason.BRANCH_SWITCH_PENDING,
            forceLowConfidence = true,
        )
    }

    /** S07-15: the progress change reachable in the measured time between samples, not per call. */
    private fun progressPriorSigmaM(sampleGapMs: Long?, position: FilteredRoutePosition): Double {
        if (!config.timeScaledProgressPrior || sampleGapMs == null || sampleGapMs <= 0L ||
            !config.maximumTravelSpeedMps.isFinite() || config.maximumTravelSpeedMps <= 0.0
        ) return config.progressSigmaM
        return max(config.progressSigmaM, sampleGapMs / 1_000.0 * config.maximumTravelSpeedMps + 2.0 * position.horizontalAccuracyM)
    }

    private fun score(
        candidate: Candidate,
        position: FilteredRoutePosition,
        progressReferenceM: Double?,
        progressSigmaM: Double,
    ): Double {
        var cost = robustSquare(candidate.crossTrackDistanceM / candidate.crossTrackSigmaM)
        val heading = position.heading?.takeIf {
            it.degrees.isFinite() &&
                it.standardDeviationDeg.isFinite() &&
                it.standardDeviationDeg > 0.0 &&
                it.standardDeviationDeg <= config.maximumHeadingSigmaDeg
        }
        if (heading != null) {
            val sigma = max(heading.standardDeviationDeg, config.minimumHeadingSigmaDeg)
            val difference = angleDifferenceDeg(heading.degrees, candidate.bearingDeg.toDouble())
            // Corridor membership is still valid while walking back along it. Facing is not
            // a travel heading and is never an input to this matcher.
            val travelDifference = if (config.allowReverseTravel) min(difference, 180.0 - difference) else difference
            cost += config.headingWeight * robustSquare(travelDifference / sigma)
        }
        if (progressReferenceM != null) {
            val sigma = hypot(progressSigmaM, candidate.alongTrackSigmaM)
            cost += config.progressWeight * robustSquare((candidate.geometricProgressM - progressReferenceM) / sigma)
        }
        return cost
    }

    private fun confidence(
        selected: ScoredCandidate,
        candidates: List<ScoredCandidate>,
    ): Double {
        val fit = exp(-0.5 * selected.cost.coerceAtMost(40.0))
        val competitor = candidates
            .filter { competingBranch(selected, it) }
            .minByOrNull(ScoredCandidate::cost)
        val separation = competitor?.let {
            1.0 - exp(-0.5 * (it.cost - selected.cost).coerceAtLeast(0.0))
        } ?: 1.0
        return fit * separation
    }

    private fun isAmbiguous(
        selected: ScoredCandidate,
        candidates: List<ScoredCandidate>,
    ): Boolean {
        val competitor = candidates
            .filter { competingBranch(selected, it) }
            .minByOrNull(ScoredCandidate::cost)
            ?: return false
        return competitor.cost - selected.cost < config.branchSwitchMinimumCostAdvantage
    }

    private fun competingBranch(selected: ScoredCandidate, other: ScoredCandidate): Boolean {
        val a = selected.candidate
        val b = other.candidate
        if (a.corridorIndex == b.corridorIndex) return false
        val farAlongRoute = abs(a.geometricProgressM - b.geometricProgressM) >
            connectionDistanceM(a.alongTrackSigmaM, b.alongTrackSigmaM)
        // Straight runs split at every 1 degree of bearing change, so a smooth curve becomes many
        // short corridors. Pieces within the connection distance along the route are one branch
        // however many corridor ids lie between them (S07-20); far-apart pieces still compete.
        if (abs(a.corridorIndex - b.corridorIndex) > 1) {
            return farAlongRoute || angleDifferenceDeg(a.bearingDeg.toDouble(), b.bearingDeg.toDouble()) > 135.0
        }
        // Adjacent return legs can overlap physically while being far apart along the route.
        return config.allowReverseTravel &&
            angleDifferenceDeg(a.bearingDeg.toDouble(), b.bearingDeg.toDouble()) > 135.0 &&
            farAlongRoute
    }

    /** Along-route distance within which two projections are the same place on the route. */
    private fun connectionDistanceM(firstAlongSigmaM: Double, secondAlongSigmaM: Double): Double =
        max(MINIMUM_CONNECTION_DISTANCE_M, 3.0 * hypot(firstAlongSigmaM, secondAlongSigmaM))

    /**
     * Continuing along the route from the last accepted match is not a branch change: the along-route
     * distance may grow by the pedestrian speed bound over the confirmation cadence (capped at
     * [MAXIMUM_BRANCH_CONFIRMATION_GAP_MS]) beyond the connection distance. Farther jumps still need
     * branch confirmation, as before.
     */
    private fun connectedToActive(candidate: Candidate, activeIndex: Int, nowElapsedRealtimeMs: Long): Boolean {
        if (abs(candidate.corridorIndex - activeIndex) <= 1) return true
        val activeProgress = activeProgressM ?: return false
        // A reversal through a short connector (U-turn across a crossing) is a real turn structure.
        val activeBearing = activeBearingDeg ?: return false
        if (angleDifferenceDeg(candidate.bearingDeg.toDouble(), activeBearing) > 135.0) return false
        val elapsedMs = activeProgressAtElapsedRealtimeMs
            ?.let { (nowElapsedRealtimeMs - it).coerceIn(0L, MAXIMUM_BRANCH_CONFIRMATION_GAP_MS) }
            ?: 0L
        val travelM = if (config.maximumTravelSpeedMps.isFinite() && config.maximumTravelSpeedMps > 0.0) {
            elapsedMs / 1_000.0 * config.maximumTravelSpeedMps
        } else {
            0.0
        }
        return abs(candidate.geometricProgressM - activeProgress) <=
            connectionDistanceM(candidate.alongTrackSigmaM, candidate.alongTrackSigmaM) + travelM
    }

    /**
     * S07-21: [other] cannot be the leg being walked when, since the last accepted match, reaching
     * it would need more than the pedestrian speed bound while [selected] is reachable. Without a
     * last accepted match, or once enough time has passed for both, nothing is excluded.
     */
    private fun visitOrderExcludes(selected: Candidate, other: Candidate, nowElapsedRealtimeMs: Long): Boolean {
        val reference = activeProgressM ?: return false
        val referenceAt = activeProgressAtElapsedRealtimeMs ?: return false
        if (nowElapsedRealtimeMs < referenceAt || !config.maximumTravelSpeedMps.isFinite() ||
            config.maximumTravelSpeedMps <= 0.0
        ) return false
        val reachM = (nowElapsedRealtimeMs - referenceAt) / 1_000.0 * config.maximumTravelSpeedMps +
            connectionDistanceM(selected.alongTrackSigmaM, other.alongTrackSigmaM)
        return abs(selected.geometricProgressM - reference) <= reachM &&
            abs(other.geometricProgressM - reference) > reachM
    }

    private fun buildCandidates(
        geometry: CachedRouteGeometry,
        position: FilteredRoutePosition,
        covariance: EnuPositionCovariance,
    ): List<Candidate> {
        val nearest = arrayOfNulls<Candidate>(geometry.corridorCount)
        val longitudeScale = LocalGeodesy.metersPerLongitudeDegree(position.point.latitude)
        fun consider(segment: CachedSegment) {
            val current = nearest[segment.corridorIndex]
            // The bounding distance is a lower bound in the same wrapped projection frame.
            // This global check preserves every corridor's nearest candidate, including parallel
            // and returning legs, while avoiding full projection/variance/point allocation.
            if (current != null && projectionBoundsDistance(
                    position.point, segment.start, segment.end, longitudeScale,
                ) > current.crossTrackDistanceM + 1e-9
            ) return
            val projection = projectToSegment(position.point, segment.start, segment.end, longitudeScale)
            if (current != null && (projection.distanceM > current.crossTrackDistanceM ||
                    (projection.distanceM == current.crossTrackDistanceM && segment.index > current.segmentIndex))
            ) return
            val tangentEast = projection.tangentEast
            val tangentNorth = projection.tangentNorth
            val crossVariance = projectedVariance(covariance, -tangentNorth, tangentEast)
            val alongVariance = projectedVariance(covariance, tangentEast, tangentNorth)
            nearest[segment.corridorIndex] = Candidate(
                segmentIndex = segment.index,
                corridorIndex = segment.corridorIndex,
                fraction = projection.fraction,
                matchedPoint = RoutePoint(
                    latitude = segment.start.latitude + (segment.end.latitude - segment.start.latitude) * projection.fraction,
                    longitude = wrapLongitudeDegrees(
                        segment.start.longitude +
                            wrapLongitudeDegrees(segment.end.longitude - segment.start.longitude) * projection.fraction,
                    ),
                ),
                crossTrackDistanceM = projection.distanceM,
                geometricProgressM = segment.cumulativeM + segment.lengthM * projection.fraction,
                bearingDeg = segment.bearingDeg,
                crossTrackSigmaM = max(config.minimumPositionSigmaM, sqrt(max(0.0, crossVariance))),
                alongTrackSigmaM = max(config.minimumPositionSigmaM, sqrt(max(0.0, alongVariance))),
            )
        }
        val preferred = activeSegmentIndex
        val nearbyStart = preferred?.minus(NEARBY_SEGMENT_RADIUS)?.coerceAtLeast(0)
        val nearbyEnd = preferred?.plus(NEARBY_SEGMENT_RADIUS)?.coerceAtMost(geometry.points.size - 2)
        if (nearbyStart != null && nearbyEnd != null) {
            for (index in nearbyStart..nearbyEnd) geometry.segmentsByIndex[index]?.let(::consider)
        }
        // First fix, displacement, corridor exit/re-entry and other branches always retain the
        // global search. Nearby candidates only tighten its safe distance bound.
        for (segment in geometry.segments) {
            if (nearbyStart == null || nearbyEnd == null || segment.index !in nearbyStart..nearbyEnd) consider(segment)
        }
        return nearest.filterNotNull()
    }

    /** Fixed measurements and straight-run membership, computed once per coordinate snapshot. */
    private class CachedRouteGeometry(polyline: List<RoutePoint>) {
        val points = polyline.toList()
        val segmentsByIndex = arrayOfNulls<CachedSegment>((points.size - 1).coerceAtLeast(0))
        val segments: List<CachedSegment>
        val corridorCount: Int

        init {
            val measured = mutableListOf<CachedSegment>()
            var cumulativeM = 0.0
            var corridorIndex = -1
            var corridorBearingDeg = 0.0
            for (index in 0 until points.lastIndex) {
                val start = points[index]
                val end = points[index + 1]
                val lengthM = haversineMeters(start, end)
                if (lengthM > ZERO_LENGTH_M && lengthM.isFinite()) {
                    val bearingDeg = bearingDegrees(start, end)
                    if (corridorIndex < 0 || angleDifferenceDeg(corridorBearingDeg, bearingDeg.toDouble()) > STRAIGHT_BEARING_TOLERANCE_DEG) {
                        corridorIndex += 1
                        corridorBearingDeg = bearingDeg.toDouble()
                    }
                    // Compare to the first bearing of this run so small turns cannot merge a loop.
                    val segment = CachedSegment(index, start, end, lengthM, cumulativeM, bearingDeg, corridorIndex)
                    measured += segment
                    segmentsByIndex[index] = segment
                }
                if (lengthM.isFinite()) cumulativeM += lengthM
            }
            segments = measured
            corridorCount = corridorIndex + 1
        }

        fun matchesCoordinates(polyline: List<RoutePoint>): Boolean {
            if (points.size != polyline.size) return false
            for (index in points.indices) {
                if (points[index].latitude.toBits() != polyline[index].latitude.toBits() ||
                    points[index].longitude.toBits() != polyline[index].longitude.toBits()
                ) return false
            }
            return true
        }
    }

    private data class CachedSegment(
        val index: Int,
        val start: RoutePoint,
        val end: RoutePoint,
        val lengthM: Double,
        val cumulativeM: Double,
        val bearingDeg: Float,
        val corridorIndex: Int,
    )

    private fun corridorWidth(crossTrackSigmaM: Double): Double {
        return min(
            config.maximumCorridorWidthM,
            config.baseCorridorWidthM + config.corridorSigmaMultiplier * crossTrackSigmaM,
        )
    }

    private fun robustSquare(value: Double): Double {
        val magnitude = abs(value)
        return if (magnitude <= config.huberDelta) {
            magnitude * magnitude
        } else {
            2.0 * config.huberDelta * magnitude - config.huberDelta * config.huberDelta
        }
    }

    private fun clearPendingSwitch() {
        pendingCorridorIndex = null
        pendingSegmentCount = 0
    }

    private data class Candidate(
        val segmentIndex: Int,
        val corridorIndex: Int,
        val fraction: Double,
        val matchedPoint: RoutePoint,
        val crossTrackDistanceM: Double,
        val geometricProgressM: Double,
        val bearingDeg: Float,
        val crossTrackSigmaM: Double,
        val alongTrackSigmaM: Double,
    )

    private data class ScoredCandidate(
        val candidate: Candidate,
        val cost: Double,
    )

    private data class BranchResolution(
        val selected: ScoredCandidate,
        val reason: RouteMatchReason? = null,
        val forceLowConfidence: Boolean = false,
    )

    private companion object {
        const val NEARBY_SEGMENT_RADIUS = 12
        const val ZERO_LENGTH_M = 0.01
        // Geometry tolerance only; unrelated to the location uncertainty or match thresholds.
        const val STRAIGHT_BEARING_TOLERANCE_DEG = 1.0
        // Existing adjacent-leg overlap distance, now used for any connected corridor pair.
        const val MINIMUM_CONNECTION_DISTANCE_M = 15.0
        // Match the 5 s high-confidence recovery cadence; slower observations start new evidence.
        const val MAXIMUM_BRANCH_CONFIRMATION_GAP_MS = 5_000L
    }
}

private data class SegmentProjection(
    val fraction: Double,
    val distanceM: Double,
    val tangentEast: Double,
    val tangentNorth: Double,
)

// S09-52: the shared LocalGeodesy longitude wrap instead of a local copy.
private fun wrapLongitudeDegrees(value: Double): Double = LocalGeodesy.wrapLongitudeDegrees(value)

private fun projectToSegment(
    point: RoutePoint,
    start: RoutePoint,
    end: RoutePoint,
    longitudeScale: Double,
): SegmentProjection {
    val latitudeScale = LocalGeodesy.METERS_PER_DEGREE
    // The query and segment share the same wrapped longitude frame rooted at start.
    val startEast = -wrapLongitudeDegrees(point.longitude - start.longitude) * longitudeScale
    val startNorth = (start.latitude - point.latitude) * latitudeScale
    val endNorth = (end.latitude - point.latitude) * latitudeScale
    val segmentEast = wrapLongitudeDegrees(end.longitude - start.longitude) * longitudeScale
    val segmentNorth = endNorth - startNorth
    val length = hypot(segmentEast, segmentNorth)
    val denominator = length * length
    val fraction = if (denominator <= 0.000001) {
        0.0
    } else {
        (-(startEast * segmentEast + startNorth * segmentNorth) / denominator).coerceIn(0.0, 1.0)
    }
    return SegmentProjection(
        fraction = fraction,
        distanceM = hypot(startEast + segmentEast * fraction, startNorth + segmentNorth * fraction),
        tangentEast = segmentEast / length,
        tangentNorth = segmentNorth / length,
    )
}

/** Distance to the segment's axis-aligned bounds; never exceeds its projection distance. */
private fun projectionBoundsDistance(
    point: RoutePoint,
    start: RoutePoint,
    end: RoutePoint,
    longitudeScale: Double,
): Double {
    val startEast = -wrapLongitudeDegrees(point.longitude - start.longitude) * longitudeScale
    val endEast = startEast + wrapLongitudeDegrees(end.longitude - start.longitude) * longitudeScale
    val startNorth = (start.latitude - point.latitude) * LocalGeodesy.METERS_PER_DEGREE
    val endNorth = (end.latitude - point.latitude) * LocalGeodesy.METERS_PER_DEGREE
    val east = max(0.0, max(min(startEast, endEast), -max(startEast, endEast)))
    val north = max(0.0, max(min(startNorth, endNorth), -max(startNorth, endNorth)))
    return hypot(east, north)
}

private fun projectedVariance(covariance: EnuPositionCovariance, east: Double, north: Double): Double {
    return east * east * covariance.eastVarianceM2 +
        2.0 * east * north * covariance.eastNorthCovarianceM2 +
        north * north * covariance.northVarianceM2
}

private fun FilteredRoutePosition.effectiveCovariance(): EnuPositionCovariance {
    val fallbackVariance = horizontalAccuracyM * horizontalAccuracyM
    val candidate = covarianceEnu ?: return EnuPositionCovariance(fallbackVariance, fallbackVariance)
    val determinant = candidate.eastVarianceM2 * candidate.northVarianceM2 -
        candidate.eastNorthCovarianceM2 * candidate.eastNorthCovarianceM2
    val valid = candidate.eastVarianceM2.isFinite() &&
        candidate.northVarianceM2.isFinite() &&
        candidate.eastNorthCovarianceM2.isFinite() &&
        candidate.eastVarianceM2 >= 0.0 &&
        candidate.northVarianceM2 >= 0.0 &&
        determinant >= -0.000001
    return if (valid) candidate else EnuPositionCovariance(fallbackVariance, fallbackVariance)
}

private fun FilteredRoutePosition.isValid(): Boolean {
    return point.isValid() && horizontalAccuracyM.isFinite() && horizontalAccuracyM >= 0.0 &&
        elapsedRealtimeMs >= 0L
}

private fun RoutePoint.isValid(): Boolean {
    return latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
}

private fun bearingDegrees(start: RoutePoint, end: RoutePoint): Float {
    val startLatitude = Math.toRadians(start.latitude)
    val endLatitude = Math.toRadians(end.latitude)
    val longitudeDelta = Math.toRadians(wrapLongitudeDegrees(end.longitude - start.longitude))
    val y = sin(longitudeDelta) * cos(endLatitude)
    val x = cos(startLatitude) * sin(endLatitude) -
        sin(startLatitude) * cos(endLatitude) * cos(longitudeDelta)
    return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
}

private fun angleDifferenceDeg(first: Double, second: Double): Double {
    return abs((first - second + 540.0) % 360.0 - 180.0)
}

private fun haversineMeters(first: RoutePoint, second: RoutePoint): Double {
    val earthRadiusM = LocalGeodesy.EARTH_RADIUS_M
    val latitudeDelta = Math.toRadians(second.latitude - first.latitude)
    val longitudeDelta = Math.toRadians(wrapLongitudeDegrees(second.longitude - first.longitude))
    val firstLatitude = Math.toRadians(first.latitude)
    val secondLatitude = Math.toRadians(second.latitude)
    val a = sin(latitudeDelta / 2.0) * sin(latitudeDelta / 2.0) +
        cos(firstLatitude) * cos(secondLatitude) *
        sin(longitudeDelta / 2.0) * sin(longitudeDelta / 2.0)
    return earthRadiusM * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
}

private fun unmatched(
    routeId: String,
    filteredPoint: RoutePoint,
    reason: RouteMatchReason,
    crossTrackDistanceM: Double? = null,
): RouteCorridorMatchResult {
    return RouteCorridorMatchResult(
        routeId = routeId,
        filteredPoint = filteredPoint,
        matchedPoint = null,
        segmentIndex = null,
        segmentFraction = null,
        crossTrackDistanceM = crossTrackDistanceM,
        geometricProgressM = null,
        bearingDeg = null,
        confidence = 0.0,
        quality = RouteMatchQuality.UNMATCHED,
        reason = reason,
    )
}

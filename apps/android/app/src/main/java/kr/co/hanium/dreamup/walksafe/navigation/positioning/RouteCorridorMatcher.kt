package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
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
)

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
    STALE_SAMPLE,
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
)

/**
 * Stateful matcher for one active route corridor. It does not search a sidewalk graph and does not
 * use future observations. Non-adjacent branch changes are confirmed across successive calls.
 */
class RouteCorridorMatcher(
    private val config: RouteCorridorMatcherConfig = RouteCorridorMatcherConfig(),
) {
    private var activeRouteId: String? = null
    private var activeSegmentIndex: Int? = null
    private var activeProgressM: Double? = null
    private var pendingSegmentIndex: Int? = null
    private var pendingSegmentCount = 0
    private var latestSampleElapsedRealtimeMs: Long? = null

    fun reset() {
        activeRouteId = null
        activeSegmentIndex = null
        activeProgressM = null
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
    ): RouteCorridorMatchResult {
        if (activeRouteId != routeId) {
            reset()
            activeRouteId = routeId
        }
        if (!position.isValid()) {
            return unmatched(routeId, position.point, RouteMatchReason.INVALID_INPUT)
        }
        if (polyline.size < 2 || polyline.any { !it.isValid() }) {
            return unmatched(routeId, position.point, RouteMatchReason.INVALID_ROUTE)
        }
        val latestSampleTime = latestSampleElapsedRealtimeMs
        if (latestSampleTime != null && position.elapsedRealtimeMs <= latestSampleTime) {
            return unmatched(routeId, position.point, RouteMatchReason.STALE_SAMPLE)
        }
        latestSampleElapsedRealtimeMs = position.elapsedRealtimeMs

        val covariance = position.effectiveCovariance()
        val candidates = buildCandidates(polyline, position, covariance)
        if (candidates.isEmpty()) {
            return unmatched(routeId, position.point, RouteMatchReason.INVALID_ROUTE)
        }

        val progressReference = previousProgressM
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?: activeProgressM
        val scored = candidates.map { candidate ->
            ScoredCandidate(candidate, score(candidate, position, progressReference))
        }
        val eligible = scored.filter { scoredCandidate ->
            scoredCandidate.candidate.crossTrackDistanceM <= corridorWidth(scoredCandidate.candidate.crossTrackSigmaM)
        }
        if (eligible.isEmpty()) {
            clearPendingSwitch()
            val nearest = candidates.minByOrNull(Candidate::crossTrackDistanceM)
            return unmatched(
                routeId = routeId,
                filteredPoint = position.point,
                reason = RouteMatchReason.CORRIDOR_OUTSIDE,
                crossTrackDistanceM = nearest?.crossTrackDistanceM,
            )
        }

        val best = eligible.minByOrNull(ScoredCandidate::cost) ?: error("eligible candidates are non-empty")
        val resolution = resolveBranch(best, eligible)
        val selected = resolution.selected
        val normalConfidence = confidence(selected, eligible)
        val ambiguousReverseBranch = config.allowReverseTravel && eligible.any { other ->
            competingBranch(selected, other) &&
                abs(selected.candidate.bearingDeg - other.candidate.bearingDeg).let { min(it, 360f - it) } > 135f &&
                other.candidate.crossTrackDistanceM <= max(
                    selected.candidate.crossTrackSigmaM, other.candidate.crossTrackSigmaM,
                ) * 2.0
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
            activeSegmentIndex = selected.candidate.segmentIndex
            activeProgressM = selected.candidate.geometricProgressM
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
    ): BranchResolution {
        val activeIndex = activeSegmentIndex ?: return BranchResolution(best)
        if (abs(best.candidate.segmentIndex - activeIndex) <= 1) {
            clearPendingSwitch()
            return BranchResolution(best)
        }

        val activeBranch = eligible
            .filter { abs(it.candidate.segmentIndex - activeIndex) <= 1 }
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

        if (pendingSegmentIndex == best.candidate.segmentIndex) {
            pendingSegmentCount += 1
        } else {
            pendingSegmentIndex = best.candidate.segmentIndex
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

    private fun score(
        candidate: Candidate,
        position: FilteredRoutePosition,
        progressReferenceM: Double?,
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
            val sigma = hypot(config.progressSigmaM, candidate.alongTrackSigmaM)
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
        if (abs(a.segmentIndex - b.segmentIndex) > 1) return true
        // Adjacent return legs can overlap physically while being far apart along the route.
        return config.allowReverseTravel && a.segmentIndex != b.segmentIndex &&
            angleDifferenceDeg(a.bearingDeg.toDouble(), b.bearingDeg.toDouble()) > 135.0 &&
            abs(a.geometricProgressM - b.geometricProgressM) > max(15.0, 3.0 * hypot(a.alongTrackSigmaM, b.alongTrackSigmaM))
    }

    private fun buildCandidates(
        polyline: List<RoutePoint>,
        position: FilteredRoutePosition,
        covariance: EnuPositionCovariance,
    ): List<Candidate> {
        val result = mutableListOf<Candidate>()
        var cumulativeM = 0.0
        polyline.zipWithNext().forEachIndexed { index, (start, end) ->
            val segmentLengthM = haversineMeters(start, end)
            if (segmentLengthM > ZERO_LENGTH_M && segmentLengthM.isFinite()) {
                val projection = projectToSegment(position.point, start, end)
                val tangentEast = projection.tangentEast
                val tangentNorth = projection.tangentNorth
                val normalEast = -tangentNorth
                val normalNorth = tangentEast
                val crossVariance = projectedVariance(covariance, normalEast, normalNorth)
                val alongVariance = projectedVariance(covariance, tangentEast, tangentNorth)
                val accuracyFloor = max(position.horizontalAccuracyM, config.minimumPositionSigmaM)
                result += Candidate(
                    segmentIndex = index,
                    fraction = projection.fraction,
                    matchedPoint = RoutePoint(
                        latitude = start.latitude + (end.latitude - start.latitude) * projection.fraction,
                        longitude = start.longitude + (end.longitude - start.longitude) * projection.fraction,
                    ),
                    crossTrackDistanceM = projection.distanceM,
                    geometricProgressM = cumulativeM + segmentLengthM * projection.fraction,
                    bearingDeg = bearingDegrees(start, end),
                    crossTrackSigmaM = max(accuracyFloor, sqrt(max(0.0, crossVariance))),
                    alongTrackSigmaM = max(accuracyFloor, sqrt(max(0.0, alongVariance))),
                )
            }
            if (segmentLengthM.isFinite()) cumulativeM += segmentLengthM
        }
        return result
    }

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
        pendingSegmentIndex = null
        pendingSegmentCount = 0
    }

    private data class Candidate(
        val segmentIndex: Int,
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
        const val ZERO_LENGTH_M = 0.01
    }
}

private data class SegmentProjection(
    val fraction: Double,
    val distanceM: Double,
    val tangentEast: Double,
    val tangentNorth: Double,
)

private fun projectToSegment(point: RoutePoint, start: RoutePoint, end: RoutePoint): SegmentProjection {
    val latitudeScale = 111_320.0
    val longitudeScale = latitudeScale * cos(Math.toRadians(point.latitude))
    val startEast = (start.longitude - point.longitude) * longitudeScale
    val startNorth = (start.latitude - point.latitude) * latitudeScale
    val endEast = (end.longitude - point.longitude) * longitudeScale
    val endNorth = (end.latitude - point.latitude) * latitudeScale
    val segmentEast = endEast - startEast
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
    return point.isValid() && horizontalAccuracyM.isFinite() && horizontalAccuracyM >= 0.0
}

private fun RoutePoint.isValid(): Boolean {
    return latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
}

private fun bearingDegrees(start: RoutePoint, end: RoutePoint): Float {
    val startLatitude = Math.toRadians(start.latitude)
    val endLatitude = Math.toRadians(end.latitude)
    val longitudeDelta = Math.toRadians(end.longitude - start.longitude)
    val y = sin(longitudeDelta) * cos(endLatitude)
    val x = cos(startLatitude) * sin(endLatitude) -
        sin(startLatitude) * cos(endLatitude) * cos(longitudeDelta)
    return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
}

private fun angleDifferenceDeg(first: Double, second: Double): Double {
    return abs((first - second + 540.0) % 360.0 - 180.0)
}

private fun haversineMeters(first: RoutePoint, second: RoutePoint): Double {
    val earthRadiusM = 6_371_000.0
    val latitudeDelta = Math.toRadians(second.latitude - first.latitude)
    val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
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

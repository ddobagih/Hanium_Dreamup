package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.LocalGeodesy
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteCorridorMatchResult
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchQuality
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchReason
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

enum class RouteAlignmentReason {
    DEPARTURE_SEGMENT, DEPARTURE_CONNECTOR, CURRENT_SEGMENT, AGREED_CANDIDATES,
    POSITION_INVALID, POSITION_TOO_COARSE, ROUTE_TOO_FAR, ROUTE_INVALID,
    BRANCH_AMBIGUOUS, BRANCH_CHANGE_PENDING, NEAR_ROUTE_END, NO_ROUTE_BEARING,
    /** S08-18: at or past the vertex of a guide the navigator has not yet passed. */
    TURN_PASSAGE_PENDING,
}

data class RouteAlignmentDiagnostic(
    val reason: RouteAlignmentReason,
    val segmentIndex: Int? = null,
    val bearingDegreesTrueNorth: Double? = null,
)

internal data class RouteAlignmentSelection(
    val diagnostic: RouteAlignmentDiagnostic,
    val target: RoutePoint? = null,
    /** Largest angle from the selected bearing to another plausible candidate segment. */
    val bearingUncertaintyDegrees: Double = 0.0,
    /**
     * S08-18-F2: the position is at or beyond the vertex of the navigator's still unpassed guide,
     * whether the bearing is held (near the vertex) or already the outgoing leg (past the zone).
     */
    val beyondPendingTurnVertex: Boolean = false,
    /**
     * S08-20: the position lies beside the selected leg by more than its reported radius and the
     * 8 m search floor. The leg's progress bearing is then not a walkable heading from there.
     */
    val lateralOffsetBeyondUncertainty: Boolean = false,
) {
    val bearingDegrees: Double? get() = diagnostic.bearingDegreesTrueNorth
}

/**
 * S08-18: the navigator's next unpassed guide, in this selector's geometric route progress.
 * [vertexToleranceM] is the navigator's accepted guide-to-shape timing bound.
 * [passageMarginM] (S08-18-F2) is the navigator's own passage margin for that guide: the bearing is
 * held only this far past the vertex, so a passage that is not yet confirmed cannot withhold it
 * along the following legs.
 */
internal data class PendingRouteGuide(
    val geometricProgressM: Double,
    val vertexToleranceM: Double,
    val passageMarginM: Double = MINIMUM_GUIDE_PASSAGE_MARGIN_M,
)

/** Selects a route-ordered target for speech without changing the position match or arrival state. */
internal class RouteAlignmentSelector {
    private var requestOrigin: TrustedLocation? = null
    private var firstPosition: FilteredRoutePosition? = null
    private var observedMovement = false
    private var geometry: RouteGeometryCache? = null
    private var cachedSegments: List<Segment> = emptyList()

    fun reset(origin: TrustedLocation? = null) {
        requestOrigin = origin
        firstPosition = null
        observedMovement = false
    }

    /** Evidence interruptions retain the installed shape; route removal releases it explicitly. */
    fun clearGeometry() {
        geometry = null
        cachedSegments = emptyList()
    }

    /** Called at navigator installation; direct selector callers lazily install their snapshot. */
    fun installGeometry(geometry: RouteGeometryCache) {
        this.geometry = geometry
        var cumulativeM = 0.0
        cachedSegments = geometry.segments.mapNotNull { segment ->
            // Preserve the selector's original convention: sub-centimetre/invalid legs do not
            // contribute to its progress, even though navigator summary scaling includes them.
            if (segment.lengthM <= 0.01 || !segment.lengthM.isFinite()) return@mapNotNull null
            Segment(segment, cumulativeM).also { cumulativeM += segment.lengthM }
        }
    }

    fun select(
        route: WalkingRoute,
        position: FilteredRoutePosition,
        match: RouteCorridorMatchResult,
        nowMs: Long,
        maximumDistanceToRouteM: Double,
        pendingGuide: PendingRouteGuide? = null,
    ): RouteAlignmentSelection {
        if (!position.point.valid() || !position.horizontalAccuracyM.isFinite() || position.horizontalAccuracyM < 0.0 ||
            position.elapsedRealtimeMs < 0L || nowMs < position.elapsedRealtimeMs ||
            nowMs - position.elapsedRealtimeMs > LocationTrustConfig().maxAgeMs
        ) return unavailable(RouteAlignmentReason.POSITION_INVALID)
        if (position.horizontalAccuracyM > 100.0) return unavailable(RouteAlignmentReason.POSITION_TOO_COARSE)
        if (route.polyline.size < 2 || route.polyline.any { !it.valid() }) return unavailable(RouteAlignmentReason.ROUTE_INVALID)

        if (geometry?.matchesPoints(route.polyline) != true) installGeometry(RouteGeometryCache(route.polyline))
        val segments = cachedSegments
        val longitudeScale = LocalGeodesy.metersPerLongitudeDegree(position.point.latitude)
        for (segment in segments) {
            segment.projection = project(position.point, segment.start, segment.end, longitudeScale)
        }
        val first = segments.firstOrNull() ?: return unavailable(RouteAlignmentReason.ROUTE_INVALID)
        val nearest = segments.minByOrNull { it.projection.distanceM } ?: return unavailable(RouteAlignmentReason.NO_ROUTE_BEARING)
        if (nearest.projection.distanceM > maximumDistanceToRouteM) return unavailable(RouteAlignmentReason.ROUTE_TOO_FAR)

        // Twice the reported radius is a search allowance, not a statistical coverage guarantee.
        val searchRadiusM = maxOf(8.0, position.horizontalAccuracyM * 2.0)
        val reference = firstPosition ?: position.also { firstPosition = it }
        if (position.elapsedRealtimeMs > reference.elapsedRealtimeMs &&
            distance(reference.point, position.point) > maxOf(8.0, hypot(reference.horizontalAccuracyM, position.horizontalAccuracyM))
        ) observedMovement = true

        val accepted = match.takeIf { it.quality == RouteMatchQuality.HIGH || it.quality == RouteMatchQuality.MEDIUM }
            ?.segmentIndex?.let { index -> segments.firstOrNull { it.index == index } }
        // A request anchor is provenance, not a refreshed location. Current evidence on another leg
        // ends the departure role even when the network response arrived late.
        if (accepted != null && accepted.index != first.index) requestOrigin = null
        val departureAnchor = requestOrigin
        if (accepted == first && accepted.projection.fraction >= 1.0 && departureAnchor != null &&
            position.elapsedRealtimeMs > departureAnchor.elapsedRealtimeMs &&
            distance(departureAnchor.point(), position.point) > hypot(departureAnchor.accuracyM.toDouble(), position.horizontalAccuracyM)
        ) {
            // Reaching a short first leg can be real progress below the general 8 m movement
            // allowance. Require a new accepted fix outside the origin's combined reported error;
            // a vertex projection inside that uncertainty alone does not end departure.
            requestOrigin = null
        }
        if (observedMovement) requestOrigin = null

        val origin = requestOrigin?.takeIf {
            it.point().valid() && it.accuracyM.isFinite() && it.accuracyM in 0f..100f &&
                it.elapsedRealtimeMs >= 0L && it.elapsedRealtimeMs <= position.elapsedRealtimeMs &&
                distance(it.point(), first.start) <= maximumDistanceToRouteM &&
                project(it.point(), first.start, first.end).fraction * first.lengthM <= maxOf(8.0, it.accuracyM.toDouble()) &&
                distance(it.point(), position.point) <= maxOf(8.0, hypot(it.accuracyM.toDouble(), position.horizontalAccuracyM)) &&
                // Without an accepted branch, a closer later leg must use the normal local
                // candidate/ambiguity rules rather than the request's first-leg departure role.
                (accepted != null || nearest.index == first.index)
        }
        val local = segments.filter { it.projection.distanceM <= searchRadiusM }.ifEmpty {
            // The provider may snap the requested origin to a path outside the GPS error area.
            // Only the validated request anchor can authorize an explicit start-point connector.
            if (origin != null) listOf(first) else emptyList()
        }
        if (local.isEmpty()) return unavailable(RouteAlignmentReason.NO_ROUTE_BEARING)

        // The 8 m search floor finds nearby geometry, not equally plausible user positions.
        // Keep a matched branch when the opposite leg lies outside the reported position area.
        // An actual overlap (or an unresolved match) still has competing direction evidence.
        val plausible = if (accepted != null) local.filter {
            it.index == accepted.index || it.projection.distanceM <= maxOf(1.0, position.horizontalAccuracyM) * 2.0
        } else local
        val competing = plausible.any { a -> plausible.any { b ->
            a.index != b.index && (
                angleDistance(a.bearing, b.bearing) > 135.0 ||
                    (angleDistance(a.bearing, b.bearing) > 45.0 &&
                        abs(a.progressM - b.progressM) > maxOf(30.0, searchRadiusM * 2.0))
                )
        } }
        if (competing) return unavailable(RouteAlignmentReason.BRANCH_AMBIGUOUS)
        if (match.reason == RouteMatchReason.ACTIVE_BRANCH_RETAINED || match.reason == RouteMatchReason.BRANCH_SWITCH_PENDING) {
            return unavailable(RouteAlignmentReason.BRANCH_CHANGE_PENDING)
        }

        if (origin != null && (accepted == null || accepted.index == first.index)) {
            // A provider snap can leave the user beside the first leg. Reference its closest
            // point, never send the user backwards to the provider's original start point.
            if (first.projection.distanceM > maxOf(8.0, 2.0 * hypot(origin.accuracyM.toDouble(), position.horizontalAccuracyM))) {
                return selected(first, RouteAlignmentReason.DEPARTURE_CONNECTOR, position.point,
                    first.pointAt(first.projection.fraction))
            }
            // A request anchor preserves the departure role, not a walkway onto the selected leg.
            val lateralOffset = first.projection.distanceM > maxOf(8.0, position.horizontalAccuracyM)
            // Describe the planned departure leg, not certainty that the user is at its first point.
            if (first.lengthM < DEPARTURE_MINIMUM_BEARING_BASELINE_M) {
                // A sub-metre first leg is below positioning/walking resolution: it may be
                // coordinate noise or a real micro-entry. Look along continuing segments only;
                // never aim across a following corner, and do not guess when one follows.
                val target = continuingDepartureTarget(segments)
                    ?: return unavailable(RouteAlignmentReason.NO_ROUTE_BEARING)
                return selected(first, RouteAlignmentReason.DEPARTURE_SEGMENT, first.start, target)
                    .copy(lateralOffsetBeyondUncertainty = lateralOffset)
            }
            val target = first.pointAt(minOf(15.0, first.lengthM) / first.lengthM)
            return selected(first, RouteAlignmentReason.DEPARTURE_SEGMENT, first.start, target)
                .copy(lateralOffsetBeyondUncertainty = lateralOffset)
        }

        if (distance(position.point, segments.last().end) <= searchRadiusM && !observedMovement) {
            return unavailable(RouteAlignmentReason.NEAR_ROUTE_END)
        }
        var current = accepted
        var reason = RouteAlignmentReason.CURRENT_SEGMENT
        var candidateSpreadDegrees = 0.0
        if (current == null) {
            // Dense collinear samples can be a low-quality position match but share one direction.
            if (local.any { angleDistance(it.bearing, nearest.bearing) > 15.0 }) {
                return unavailable(RouteAlignmentReason.BRANCH_AMBIGUOUS)
            }
            current = nearest
            reason = RouteAlignmentReason.AGREED_CANDIDATES
            // Similar is not identical: the speech decision must cover every candidate bearing.
            candidateSpreadDegrees = local.maxOf { angleDistance(it.bearing, nearest.bearing) }
        }
        // Advance only when the current projection reaches the vertex; do not aim across a turn
        // merely because its outgoing segment falls inside the position uncertainty area.
        if (current.projection.fraction >= 1.0 && current != segments.last()) {
            current = segments[segments.indexOf(current) + 1]
        }
        // S08-20: withholding a turn bearing does not establish a walkway onto the selected leg.
        val lateralOffset = current.projection.distanceM > maxOf(8.0, position.horizontalAccuracyM)
        // S08-18: bearing selection and turn speech share one phase. Before the navigator passes
        // the guide at this vertex (with its accuracy margin), a leg beyond that vertex may not
        // supply the bearing: the turn sentence still describes the vertex. At or past the vertex
        // the turn is near, so the bearing is withheld rather than replaced by the incoming leg.
        // S08-18-F2: "near" is the navigator's passage margin past the vertex, the same bound it
        // uses to pass the guide, so the hold cannot outlast it along later legs.
        val here = current
        val heldIncoming = pendingGuide?.let { heldIncomingSegment(segments, it) }?.takeIf {
            segments.indexOf(here) > segments.indexOf(it) && angleDistance(here.bearing, it.bearing) > SAME_DIRECTION_DEGREES
        }
        val beyondPendingVertex = heldIncoming != null
        if (heldIncoming != null && pendingGuide != null) {
            val nearVertexM = pendingGuide.passageMarginM.takeIf { it.isFinite() && it >= 0.0 }
                ?: MINIMUM_GUIDE_PASSAGE_MARGIN_M
            if (here.progressM - (heldIncoming.startProgressM + heldIncoming.lengthM) <= nearVertexM) {
                return unavailable(RouteAlignmentReason.TURN_PASSAGE_PENDING).copy(
                    beyondPendingTurnVertex = true, lateralOffsetBeyondUncertainty = lateralOffset,
                )
            }
        }
        val remainingM = current.lengthM * (1.0 - current.projection.fraction)
        if (current == segments.last() && remainingM <= 1.0) {
            return unavailable(RouteAlignmentReason.NEAR_ROUTE_END).copy(beyondPendingTurnVertex = beyondPendingVertex)
        }
        val from = current.pointAt(current.projection.fraction)
        // S08-17: a sub-metre leg (coordinate noise or a micro vertex) cannot define a walkable
        // direction, and its own 15 m look-ahead would end within it. Look along the legs that
        // continue it instead; never across a corner, and give no bearing when one follows.
        val target = if (current.lengthM < DEPARTURE_MINIMUM_BEARING_BASELINE_M) {
            microLegTarget(segments, current)
                ?: return unavailable(RouteAlignmentReason.NO_ROUTE_BEARING).copy(beyondPendingTurnVertex = beyondPendingVertex)
        } else {
            current.pointAt((current.projection.fraction + minOf(15.0, remainingM) / current.lengthM).coerceAtMost(1.0))
        }
        // S08-20: an accepted leg can lie farther away than the 8 m search floor when the reported
        // radius is large. An offset beyond both is not explained by the position uncertainty, so
        // the user is beside the leg and its progress bearing is not a way onto it.
        return selected(current, reason, from, target, candidateSpreadDegrees)
            .copy(beyondPendingTurnVertex = beyondPendingVertex, lateralOffsetBeyondUncertainty = lateralOffset)
    }

    private fun selected(
        segment: Segment,
        reason: RouteAlignmentReason,
        from: RoutePoint,
        target: RoutePoint,
        bearingUncertaintyDegrees: Double = 0.0,
    ): RouteAlignmentSelection {
        if (distance(from, target) <= 0.01) return unavailable(RouteAlignmentReason.NO_ROUTE_BEARING)
        return RouteAlignmentSelection(
            RouteAlignmentDiagnostic(reason, segment.index, bearing(from, target)), target, bearingUncertaintyDegrees,
        )
    }

    /** The leg ending at the interior vertex closest to the pending guide, within its tolerance. */
    private fun heldIncomingSegment(segments: List<Segment>, guide: PendingRouteGuide): Segment? {
        if (!guide.geometricProgressM.isFinite() || !guide.vertexToleranceM.isFinite() || guide.vertexToleranceM < 0.0) {
            return null
        }
        return segments.dropLast(1)
            .filter { abs(it.startProgressM + it.lengthM - guide.geometricProgressM) <= guide.vertexToleranceM }
            .minByOrNull { abs(it.startProgressM + it.lengthM - guide.geometricProgressM) }
    }

    private fun continuingDepartureTarget(segments: List<Segment>): RoutePoint? {
        val first = segments.first()
        var remainingM = DEPARTURE_LOOKAHEAD_M
        var target = first.start
        for (segment in segments) {
            if (angleDistance(segment.bearing, first.bearing) > DEPARTURE_CONTINUATION_DEGREES) break
            if (segment.lengthM >= remainingM) return segment.pointAt(remainingM / segment.lengthM)
            remainingM -= segment.lengthM
            target = segment.end
        }
        return target.takeIf { DEPARTURE_LOOKAHEAD_M - remainingM >= DEPARTURE_MINIMUM_BEARING_BASELINE_M }
    }

    /**
     * S08-17: the look-ahead target for a sub-metre current leg. A kink between legs that continue
     * the preceding (>= 1 m) leg is coordinate noise, so the following legs within the continuation
     * angle of that leg give the direction. Otherwise the micro leg's own direction must continue
     * for at least the minimum baseline (a micro vertex into a real turn), else there is no bearing.
     */
    private fun microLegTarget(segments: List<Segment>, current: Segment): RoutePoint? {
        val index = segments.indexOf(current)
        val previous = segments.getOrNull(index - 1)?.takeIf {
            it.lengthM >= DEPARTURE_MINIMUM_BEARING_BASELINE_M && distance(it.end, current.start) <= 0.05
        }
        val references = listOfNotNull(previous?.let { it.bearing to 0.0 },
            current.bearing to current.lengthM * (1.0 - current.projection.fraction))
        for ((reference, initialM) in references) {
            var consistentM = initialM
            var target: RoutePoint? = null
            for (segment in segments.drop(index + 1)) {
                if (angleDistance(segment.bearing, reference) > DEPARTURE_CONTINUATION_DEGREES) break
                val neededM = DEPARTURE_LOOKAHEAD_M - consistentM
                if (segment.lengthM >= neededM) return segment.pointAt(neededM / segment.lengthM)
                consistentM += segment.lengthM
                target = segment.end
            }
            if (target != null && consistentM >= DEPARTURE_MINIMUM_BEARING_BASELINE_M) return target
        }
        return null
    }

    private fun unavailable(reason: RouteAlignmentReason) = RouteAlignmentSelection(RouteAlignmentDiagnostic(reason))
    private data class Projection(val fraction: Double, val distanceM: Double)
    private class Segment(
        geometry: RouteGeometrySegment,
        val startProgressM: Double,
    ) {
        val index = geometry.index
        val start = geometry.start
        val end = geometry.end
        val lengthM = geometry.lengthM
        val bearing = geometry.bearingDeg.toDouble() % 360.0
        // Per-observation projection stays separate from the fixed shape measurements. These
        // private segments never escape this stateful selector or the navigator's lock.
        var projection = Projection(0.0, Double.POSITIVE_INFINITY)
        val progressM: Double get() = startProgressM + lengthM * projection.fraction
        fun pointAt(fraction: Double) = RoutePoint(
            start.latitude + (end.latitude - start.latitude) * fraction,
            LocalGeodesy.wrapLongitudeDegrees(start.longitude +
                LocalGeodesy.wrapLongitudeDegrees(end.longitude - start.longitude) * fraction),
        )
    }

    private companion object {
        const val DEPARTURE_LOOKAHEAD_M = 15.0
        // Engineering defaults pending provider fixtures (08.txt #17): legs shorter than this do
        // not define a walkable direction; continuing legs within this angle are one direction.
        const val DEPARTURE_MINIMUM_BEARING_BASELINE_M = 1.0
        const val DEPARTURE_CONTINUATION_DEGREES = 30.0
        // S08-18: legs within the same 15 degrees used for AGREED_CANDIDATES share one direction,
        // so holding the bearing at such a vertex would only remove guidance.
        const val SAME_DIRECTION_DEGREES = 15.0
        fun TrustedLocation.point() = RoutePoint(latitude, longitude)
        fun RoutePoint.valid() = latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0
        fun distance(a: RoutePoint, b: RoutePoint) = haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        fun bearing(a: RoutePoint, b: RoutePoint) = bearingDegrees(a.latitude, a.longitude, b.latitude, b.longitude).toDouble() % 360.0
        fun angleDistance(a: Double, b: Double) = abs((a - b + 540.0) % 360.0 - 180.0)
        fun project(
            point: RoutePoint,
            start: RoutePoint,
            end: RoutePoint,
            longitudeScale: Double = LocalGeodesy.metersPerLongitudeDegree(point.latitude),
        ): Projection {
            val latScale = LocalGeodesy.METERS_PER_DEGREE
            val lonScale = longitudeScale
            val wrap = LocalGeodesy::wrapLongitudeDegrees
            val ax = wrap(start.longitude - point.longitude) * lonScale
            val ay = (start.latitude - point.latitude) * latScale
            val dx = wrap(end.longitude - start.longitude) * lonScale
            val dy = (end.latitude - start.latitude) * latScale
            val denominator = dx * dx + dy * dy
            val fraction = if (denominator <= 0.000001) 0.0 else (-(ax * dx + ay * dy) / denominator).coerceIn(0.0, 1.0)
            return Projection(fraction, hypot(ax + fraction * dx, ay + fraction * dy))
        }
    }
}

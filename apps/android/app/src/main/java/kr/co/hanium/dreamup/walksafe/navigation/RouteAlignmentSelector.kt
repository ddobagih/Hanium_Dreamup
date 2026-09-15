package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
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
}

data class RouteAlignmentDiagnostic(
    val reason: RouteAlignmentReason,
    val segmentIndex: Int? = null,
    val bearingDegreesTrueNorth: Double? = null,
)

internal data class RouteAlignmentSelection(
    val diagnostic: RouteAlignmentDiagnostic,
    val target: RoutePoint? = null,
) {
    val bearingDegrees: Double? get() = diagnostic.bearingDegreesTrueNorth
}

/** Selects a route-ordered target for speech without changing the position match or arrival state. */
internal class RouteAlignmentSelector {
    private var requestOrigin: TrustedLocation? = null
    private var firstPosition: FilteredRoutePosition? = null
    private var observedMovement = false

    fun reset(origin: TrustedLocation? = null) {
        requestOrigin = origin
        firstPosition = null
        observedMovement = false
    }

    fun select(
        route: WalkingRoute,
        position: FilteredRoutePosition,
        match: RouteCorridorMatchResult,
        nowMs: Long,
        maximumDistanceToRouteM: Double,
    ): RouteAlignmentSelection {
        if (!position.point.valid() || !position.horizontalAccuracyM.isFinite() || position.horizontalAccuracyM < 0.0 ||
            position.elapsedRealtimeMs < 0L || nowMs < position.elapsedRealtimeMs ||
            nowMs - position.elapsedRealtimeMs > LocationTrustConfig().maxAgeMs
        ) return unavailable(RouteAlignmentReason.POSITION_INVALID)
        if (position.horizontalAccuracyM > 100.0) return unavailable(RouteAlignmentReason.POSITION_TOO_COARSE)
        if (route.polyline.size < 2 || route.polyline.any { !it.valid() }) return unavailable(RouteAlignmentReason.ROUTE_INVALID)

        var cumulativeM = 0.0
        val segments = route.polyline.zipWithNext().mapIndexedNotNull { index, (start, end) ->
            val lengthM = distance(start, end)
            if (lengthM <= 0.01 || !lengthM.isFinite()) return@mapIndexedNotNull null
            Segment(index, start, end, lengthM, cumulativeM, project(position.point, start, end)).also { cumulativeM += lengthM }
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
                distance(it.point(), position.point) <= maxOf(8.0, hypot(it.accuracyM.toDouble(), position.horizontalAccuracyM))
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
            // A lateral provider snap remains a connector even if the location projects a few
            // metres inside the first leg. Along-route progress alone must not send the user back.
            if (first.projection.distanceM > maxOf(8.0, 2.0 * hypot(origin.accuracyM.toDouble(), position.horizontalAccuracyM))) {
                return selected(first, RouteAlignmentReason.DEPARTURE_CONNECTOR, position.point, first.start)
            }
            // Describe the planned departure leg, not certainty that the user is at its first point.
            val target = first.pointAt(minOf(15.0, first.lengthM) / first.lengthM)
            return selected(first, RouteAlignmentReason.DEPARTURE_SEGMENT, first.start, target)
        }

        if (distance(position.point, segments.last().end) <= searchRadiusM && !observedMovement) {
            return unavailable(RouteAlignmentReason.NEAR_ROUTE_END)
        }
        var current = accepted
        var reason = RouteAlignmentReason.CURRENT_SEGMENT
        if (current == null) {
            // Dense collinear samples can be a low-quality position match but share one direction.
            if (local.any { angleDistance(it.bearing, nearest.bearing) > 15.0 }) {
                return unavailable(RouteAlignmentReason.BRANCH_AMBIGUOUS)
            }
            current = nearest
            reason = RouteAlignmentReason.AGREED_CANDIDATES
        }
        // Advance only when the current projection reaches the vertex; do not aim across a turn
        // merely because its outgoing segment falls inside the position uncertainty area.
        if (current.projection.fraction >= 1.0 && current != segments.last()) {
            current = segments[segments.indexOf(current) + 1]
        }
        val remainingM = current.lengthM * (1.0 - current.projection.fraction)
        if (current == segments.last() && remainingM <= 1.0) return unavailable(RouteAlignmentReason.NEAR_ROUTE_END)
        val from = current.pointAt(current.projection.fraction)
        val target = current.pointAt((current.projection.fraction + minOf(15.0, remainingM) / current.lengthM).coerceAtMost(1.0))
        return selected(current, reason, from, target)
    }

    private fun selected(segment: Segment, reason: RouteAlignmentReason, from: RoutePoint, target: RoutePoint): RouteAlignmentSelection {
        if (distance(from, target) <= 0.01) return unavailable(RouteAlignmentReason.NO_ROUTE_BEARING)
        return RouteAlignmentSelection(RouteAlignmentDiagnostic(reason, segment.index, bearing(from, target)), target)
    }

    private fun unavailable(reason: RouteAlignmentReason) = RouteAlignmentSelection(RouteAlignmentDiagnostic(reason))
    private data class Projection(val fraction: Double, val distanceM: Double)
    private data class Segment(
        val index: Int, val start: RoutePoint, val end: RoutePoint, val lengthM: Double,
        val startProgressM: Double, val projection: Projection,
    ) {
        val bearing: Double get() = bearing(start, end)
        val progressM: Double get() = startProgressM + lengthM * projection.fraction
        fun pointAt(fraction: Double) = RoutePoint(
            start.latitude + (end.latitude - start.latitude) * fraction,
            start.longitude + (end.longitude - start.longitude) * fraction,
        )
    }

    private companion object {
        fun TrustedLocation.point() = RoutePoint(latitude, longitude)
        fun RoutePoint.valid() = latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0
        fun distance(a: RoutePoint, b: RoutePoint) = haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        fun bearing(a: RoutePoint, b: RoutePoint) = bearingDegrees(a.latitude, a.longitude, b.latitude, b.longitude).toDouble() % 360.0
        fun angleDistance(a: Double, b: Double) = abs((a - b + 540.0) % 360.0 - 180.0)
        fun project(point: RoutePoint, start: RoutePoint, end: RoutePoint): Projection {
            val lonScale = 111_320.0 * cos(Math.toRadians(point.latitude))
            val ax = (start.longitude - point.longitude) * lonScale
            val ay = (start.latitude - point.latitude) * 111_320.0
            val dx = (end.longitude - start.longitude) * lonScale
            val dy = (end.latitude - start.latitude) * 111_320.0
            val denominator = dx * dx + dy * dy
            val fraction = if (denominator <= 0.000001) 0.0 else (-(ax * dx + ay * dy) / denominator).coerceIn(0.0, 1.0)
            return Projection(fraction, hypot(ax + fraction * dx, ay + fraction * dy))
        }
    }
}

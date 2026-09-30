package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.inference.WalkMateClassPolicy
import kotlin.math.abs

data class TactileRoutePolicyConfig(
    val minimumConfidence: Float = 0.55f,
    val minimumStableFrames: Int = 3,
    val minimumStableMs: Long = 700L,
    val maximumDetectionAgeMs: Long = 1_200L,
    val maximumRouteHeadingDeltaDeg: Float = 35f,
    val maximumGpsAccuracyM: Float = 25f,
    val steeringDeadZone: Float = 0.15f,
)

enum class LocalRouteMode(val wireValue: String) {
    TMAP("tmap"),
    TACTILE_LOCAL("tactile_local"),
}

enum class TactileSteering(val wireValue: String) {
    LEFT("left"),
    STRAIGHT("straight"),
    RIGHT("right"),
}

enum class TmapCorridorProjectionEvidence {
    UNAVAILABLE,
    OUTSIDE,
    OVERLAPS,
}

data class TactileRouteObservation(
    val candidateId: String? = null,
    val className: String,
    val confidence: Float,
    val stableFrames: Int,
    val stableMs: Long,
    val ageMs: Long,
    /** Legacy camera-to-route angle, not evidence of the tactile block's own axis. */
    val routeHeadingDeltaDeg: Float?,
    val tmapCorridorProjection: TmapCorridorProjectionEvidence,
    val centerXNormalized: Float,
    val routeId: String? = null,
    val routeSegmentIndex: Int? = null,
    /** Route install revision at capture ([ActiveRouteProjection.routeRevision]). */
    val routeVersion: Long? = null,
)

/**
 * S12-12: route progress at evaluation time. Null fields mean "unknown now" and reject every
 * normal block; the caller passes null for the whole value only when it has no progress contract.
 */
data class TactileActiveRouteProgress(
    val routeVersion: Long?,
    val segmentIndex: Int?,
)

data class TactileRoutePolicyInput(
    val navigationActive: Boolean,
    val tmapOnRoute: Boolean,
    val gpsAccuracyM: Float?,
    val tactile: TactileRouteObservation?,
    val activeRouteId: String? = null,
    val activeRouteProgress: TactileActiveRouteProgress? = null,
)

data class TactileRouteDecision(
    val mode: LocalRouteMode,
    val steering: TactileSteering?,
    val reason: String,
    val instruction: String?,
)

/**
 * Keeps TMAP as the global route. Observation eligibility is not permission to follow a block:
 * these inputs do not prove its axis, user-relative motion, or measured contact depth.
 */
class TactileRoutePolicy(
    private val config: TactileRoutePolicyConfig = TactileRoutePolicyConfig(),
) {
    /** Damage or a dot warning tile in the corridor blocks a directional local-path choice. */
    fun selectCandidate(
        candidates: List<TactileRouteObservation>,
        activeRouteId: String? = null,
        activeRouteProgress: TactileActiveRouteProgress? = null,
    ): TactileRouteObservation? {
        val inCorridor = candidates.filter {
            it.tmapCorridorProjection == TmapCorridorProjectionEvidence.OVERLAPS
        }
        // Rank normal observations only after eligibility. Blockers deliberately keep their
        // existing conservative priority instead of inheriting normal-path acceptance gates.
        val eligibleNormal = inCorridor.filter {
            WalkMateClassPolicy.isTraversableTactileClass(it.className) &&
                observationRejectionReason(it, activeRouteId, activeRouteProgress) == null
        }
        return inCorridor
            .filter { WalkMateClassPolicy.isTactileRouteBlockingClass(it.className) }
            .maxByOrNull(TactileRouteObservation::confidence)
            ?: eligibleNormal.maxByOrNull(TactileRouteObservation::confidence)
            ?: candidates
                .filter { WalkMateClassPolicy.isTactileRouteBlockingClass(it.className) }
                .maxByOrNull(TactileRouteObservation::confidence)
            // No eligible path observation remains. Keep a rejected observation for evaluate's
            // diagnostic instead of reporting an observed block as absent. This fallback is
            // not eligibility or movement authorization and cannot displace an eligible normal.
            ?: candidates
                .filter { WalkMateClassPolicy.isTraversableTactileClass(it.className) }
                .maxByOrNull(TactileRouteObservation::confidence)
    }

    fun evaluate(input: TactileRoutePolicyInput): TactileRouteDecision {
        if (!input.navigationActive) return tmap("navigation_inactive")
        if (!input.tmapOnRoute) return tmap("tmap_off_route")
        val gpsAccuracy = input.gpsAccuracyM
        if (gpsAccuracy == null || !gpsAccuracy.isFinite() || gpsAccuracy < 0f || gpsAccuracy > config.maximumGpsAccuracyM) {
            return tmap("gps_untrusted")
        }
        val tactile = input.tactile ?: return tmap("tactile_not_visible")
        observationRejectionReason(tactile, input.activeRouteId, input.activeRouteProgress)?.let { return tmap(it) }
        // Camera alignment, image side and representative-depth association cannot authorize
        // a walking maneuver. No producer currently supplies the missing independent evidence.
        return tmap("tactile_motion_evidence_unavailable")
    }

    private fun observationRejectionReason(
        tactile: TactileRouteObservation,
        activeRouteId: String?,
        activeRouteProgress: TactileActiveRouteProgress?,
    ): String? {
        if (
            (activeRouteId != null || tactile.routeId != null) &&
            (activeRouteId == null || tactile.routeId != activeRouteId)
        ) {
            return "tactile_route_identity_mismatch"
        }
        routeProgressRejectionReason(tactile, activeRouteProgress)?.let { return it }
        when (tactile.tmapCorridorProjection) {
            TmapCorridorProjectionEvidence.UNAVAILABLE -> return "tmap_corridor_projection_unavailable"
            TmapCorridorProjectionEvidence.OUTSIDE -> return "tactile_outside_tmap_corridor"
            TmapCorridorProjectionEvidence.OVERLAPS -> Unit
        }
        if (!WalkMateClassPolicy.isTraversableTactileClass(tactile.className)) {
            return "tactile_not_traversable"
        }
        if (!tactile.confidence.isFinite() || tactile.confidence < config.minimumConfidence) {
            return "tactile_confidence_low"
        }
        if (tactile.stableFrames < config.minimumStableFrames || tactile.stableMs < config.minimumStableMs) {
            return "tactile_not_stable"
        }
        if (tactile.ageMs !in 0L..config.maximumDetectionAgeMs) return "tactile_stale"
        val headingDelta = tactile.routeHeadingDeltaDeg
        if (headingDelta == null || !headingDelta.isFinite() || abs(headingDelta) > config.maximumRouteHeadingDeltaDeg) {
            return "route_heading_mismatch"
        }
        if (!tactile.centerXNormalized.isFinite() || tactile.centerXNormalized !in 0f..1f) {
            return "tactile_geometry_invalid"
        }
        return null
    }

    /**
     * Same route ID is not the same guidance segment. Only the current matched segment and the
     * next one in travel direction are continuous; a previous or skipped segment is rejected.
     */
    private fun routeProgressRejectionReason(
        tactile: TactileRouteObservation,
        progress: TactileActiveRouteProgress?,
    ): String? {
        progress ?: return null
        val activeVersion = progress.routeVersion
        val activeSegment = progress.segmentIndex
        if (activeVersion == null || activeSegment == null || activeSegment < 0) {
            return "tactile_route_progress_unavailable"
        }
        if (tactile.routeVersion != activeVersion) return "tactile_route_version_mismatch"
        val observedSegment = tactile.routeSegmentIndex ?: return "tactile_segment_mismatch"
        if (observedSegment - activeSegment !in 0..MAX_FORWARD_SEGMENT_STEP) return "tactile_segment_mismatch"
        return null
    }

    private fun tmap(reason: String): TactileRouteDecision {
        return TactileRouteDecision(
            mode = LocalRouteMode.TMAP,
            steering = null,
            reason = reason,
            instruction = null,
        )
    }

    private companion object {
        /** Adjacent-boundary continuity only; going back a segment is never continuous. */
        const val MAX_FORWARD_SEGMENT_STEP = 1
    }
}

fun routeHeadingDeltaDegrees(deviceHeadingDeg: Float?, tmapBearingDeg: Float?): Float? {
    val deviceHeading = deviceHeadingDeg?.takeIf(Float::isFinite) ?: return null
    val tmapBearing = tmapBearingDeg?.takeIf(Float::isFinite) ?: return null
    return abs(((deviceHeading - tmapBearing + 540f) % 360f) - 180f)
}

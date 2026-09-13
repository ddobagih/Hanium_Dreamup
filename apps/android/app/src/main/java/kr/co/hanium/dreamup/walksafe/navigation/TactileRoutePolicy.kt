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
    val routeHeadingDeltaDeg: Float?,
    val tmapCorridorProjection: TmapCorridorProjectionEvidence,
    val centerXNormalized: Float,
    val routeId: String? = null,
    val routeSegmentIndex: Int? = null,
)

data class TactileRoutePolicyInput(
    val navigationActive: Boolean,
    val tmapOnRoute: Boolean,
    val gpsAccuracyM: Float?,
    val tactile: TactileRouteObservation?,
    val activeRouteId: String? = null,
)

data class TactileRouteDecision(
    val mode: LocalRouteMode,
    val steering: TactileSteering?,
    val reason: String,
    val instruction: String?,
)

/**
 * Keeps TMAP as the global route and admits a detected linear tactile block only as a short-range
 * walking corridor. Every missing or uncertain input fails back to TMAP.
 */
class TactileRoutePolicy(
    private val config: TactileRoutePolicyConfig = TactileRoutePolicyConfig(),
) {
    /** Damage or a dot warning tile in the corridor blocks a directional local-path choice. */
    fun selectCandidate(candidates: List<TactileRouteObservation>): TactileRouteObservation? {
        val inCorridor = candidates.filter {
            it.tmapCorridorProjection == TmapCorridorProjectionEvidence.OVERLAPS
        }
        return inCorridor
            .filter { WalkMateClassPolicy.isTactileRouteBlockingClass(it.className) }
            .maxByOrNull(TactileRouteObservation::confidence)
            ?: inCorridor
                .filter { WalkMateClassPolicy.isTraversableTactileClass(it.className) }
                .maxByOrNull(TactileRouteObservation::confidence)
            ?: candidates
                .filter { WalkMateClassPolicy.isTactileRouteBlockingClass(it.className) }
                .maxByOrNull(TactileRouteObservation::confidence)
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
        if (
            (input.activeRouteId != null || tactile.routeId != null) &&
            (input.activeRouteId == null || tactile.routeId != input.activeRouteId)
        ) {
            return tmap("tactile_route_identity_mismatch")
        }
        when (tactile.tmapCorridorProjection) {
            TmapCorridorProjectionEvidence.UNAVAILABLE -> return tmap("tmap_corridor_projection_unavailable")
            TmapCorridorProjectionEvidence.OUTSIDE -> return tmap("tactile_outside_tmap_corridor")
            TmapCorridorProjectionEvidence.OVERLAPS -> Unit
        }
        if (!WalkMateClassPolicy.isTraversableTactileClass(tactile.className)) {
            return tmap("tactile_not_traversable")
        }
        if (!tactile.confidence.isFinite() || tactile.confidence < config.minimumConfidence) {
            return tmap("tactile_confidence_low")
        }
        if (tactile.stableFrames < config.minimumStableFrames || tactile.stableMs < config.minimumStableMs) {
            return tmap("tactile_not_stable")
        }
        if (tactile.ageMs !in 0L..config.maximumDetectionAgeMs) return tmap("tactile_stale")
        val headingDelta = tactile.routeHeadingDeltaDeg
        if (headingDelta == null || !headingDelta.isFinite() || abs(headingDelta) > config.maximumRouteHeadingDeltaDeg) {
            return tmap("route_heading_mismatch")
        }
        if (!tactile.centerXNormalized.isFinite() || tactile.centerXNormalized !in 0f..1f) {
            return tmap("tactile_geometry_invalid")
        }

        val steering = when {
            tactile.centerXNormalized < 0.5f - config.steeringDeadZone -> TactileSteering.LEFT
            tactile.centerXNormalized > 0.5f + config.steeringDeadZone -> TactileSteering.RIGHT
            else -> TactileSteering.STRAIGHT
        }
        val instruction = when (steering) {
            TactileSteering.LEFT -> "왼쪽 점자블록을 따라 이동하세요."
            TactileSteering.RIGHT -> "오른쪽 점자블록을 따라 이동하세요."
            TactileSteering.STRAIGHT -> "전방 점자블록을 따라 이동하세요."
        }
        return TactileRouteDecision(
            mode = LocalRouteMode.TACTILE_LOCAL,
            steering = steering,
            reason = "stable_aligned_tactile",
            instruction = instruction,
        )
    }

    private fun tmap(reason: String): TactileRouteDecision {
        return TactileRouteDecision(
            mode = LocalRouteMode.TMAP,
            steering = null,
            reason = reason,
            instruction = null,
        )
    }
}

fun routeHeadingDeltaDegrees(deviceHeadingDeg: Float?, tmapBearingDeg: Float?): Float? {
    val deviceHeading = deviceHeadingDeg?.takeIf(Float::isFinite) ?: return null
    val tmapBearing = tmapBearingDeg?.takeIf(Float::isFinite) ?: return null
    return abs(((deviceHeading - tmapBearing + 540f) % 360f) - 180f)
}

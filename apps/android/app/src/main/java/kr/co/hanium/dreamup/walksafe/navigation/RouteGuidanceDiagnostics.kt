package kr.co.hanium.dreamup.walksafe.navigation

import kotlin.math.roundToInt

/** S08-34: how the facing part of a route sentence was expressed. */
enum class RouteFacingExpression { CLOCK, COARSE, UNKNOWN, NOT_SPOKEN }

/** S08-34: relative facing-to-route angle (whole degrees, positive clockwise) and its expression. */
data class RouteFacingSpeechDiagnostic(
    val relativeAngleDegrees: Int?,
    val expression: RouteFacingExpression,
)

/**
 * S08-34: one diagnostic chain for route guidance in the consented field log -- sensor input ->
 * accepted/rejected cause -> route bearing -> relative angle -> expression level -> generation and
 * playback-start time of the sentence, joined by the cue id. Keys are the field log's allowlisted
 * snake_case names; values are enum names, whole degrees, metres and milliseconds only: no
 * coordinates and no spoken text.
 */
object RouteGuidanceDiagnostics {
    fun compassState(result: RouteCompassHeadingResult, nowMs: Long): Map<String, Any?> {
        val observation = result.observation
        return mapOf(
            "reason" to result.reason.name,
            "compass_source" to observation?.source?.name,
            "rear_heading_deg" to observation?.degreesTrueNorth?.roundToInt(),
            "reported_heading_accuracy_deg" to observation?.reportedHeadingAccuracyDegrees?.roundToInt(),
            "heading_accuracy_deg" to observation?.headingAccuracyDegrees?.roundToInt(),
            "compass_age_ms" to observation?.let { nowMs - it.observedAtElapsedRealtimeMs },
        )
    }

    fun alignment(
        diagnostic: RouteAlignmentDiagnostic,
        facing: RouteFacingObservation?,
        positionUncertaintyM: Float,
        nowMs: Long,
    ): Map<String, Any?> = mapOf(
        "reason" to diagnostic.reason.name,
        "segment_index" to diagnostic.segmentIndex,
        "route_bearing_deg" to diagnostic.bearingDegreesTrueNorth?.roundToInt(),
        "rear_heading_deg" to facing?.degreesTrueNorth?.roundToInt(),
        "compass_source" to facing?.source?.name,
        "compass_age_ms" to facing?.let { nowMs - it.observedAtElapsedRealtimeMs },
        "position_uncertainty_m" to positionUncertaintyM.takeIf { it.isFinite() }?.roundToInt(),
    )

    fun guidanceGenerated(update: RouteNavigatorUpdate, nowMs: Long): Map<String, Any?> = mapOf(
        "cue_id" to update.speechCueToken?.cueId(),
        "elapsed_realtime_ms" to nowMs,
        "reason" to update.routeAlignmentDiagnostic?.reason?.name,
        "route_bearing_deg" to update.routeAlignmentDiagnostic?.bearingDegreesTrueNorth?.roundToInt(),
        "relative_angle_deg" to update.facingDiagnostic?.relativeAngleDegrees,
        "expression_level" to update.facingDiagnostic?.expression?.name,
    )

    /** The playback-start check of the same cue; [current] false means it was revoked before speaking. */
    fun guidanceStartChecked(update: RouteNavigatorUpdate, nowMs: Long, current: Boolean): Map<String, Any?> = mapOf(
        "cue_id" to update.speechCueToken?.cueId(),
        "elapsed_realtime_ms" to nowMs,
        "result" to if (current) "started" else "revoked",
    )

    private fun RouteSpeechCueToken.cueId() = "$routeRevision:$cueRevision"
}

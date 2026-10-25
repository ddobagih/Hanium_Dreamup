package kr.co.hanium.dreamup.walksafe.navigation

import kotlin.math.roundToInt

/** S08-34: how the facing part of a route sentence was expressed. */
enum class RouteFacingExpression { CLOCK, CLOCK_RANGE, COARSE, UNKNOWN, NOT_SPOKEN }

/** S08-34: relative facing-to-route angle (whole degrees, positive clockwise) and its expression. */
data class RouteFacingSpeechDiagnostic(
    val relativeAngleDegrees: Int?,
    val expression: RouteFacingExpression,
    val clockReferenceKind: RouteClockReferenceKind? = null,
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
        val evidence = result.checkEvidence
        return mapOf(
            "reason" to result.reason.name,
            "compass_source" to (evidence?.source ?: observation?.source)?.name,
            "rear_heading_deg" to observation?.degreesTrueNorth.whole(),
            "reported_heading_accuracy_deg" to (evidence?.reportedHeadingAccuracyDegrees
                ?: observation?.reportedHeadingAccuracyDegrees).whole(),
            "heading_accuracy_deg" to (evidence?.projectedHeadingAccuracyDegrees ?: observation?.headingAccuracyDegrees).whole(),
            "compass_age_ms" to observation?.let { nowMs - it.observedAtElapsedRealtimeMs },
            "compass_input_age_ms" to evidence?.observedAtElapsedRealtimeMs?.takeIf { it >= 0L && nowMs >= it }?.let { nowMs - it },
            "compass_rejected_check" to evidence?.rejectedCheck?.name,
            "rear_horizontal_permille" to evidence?.forwardHorizontal?.times(1_000.0).whole(),
            "magnetic_field_ut" to evidence?.magneticFieldMicrotesla.whole(),
            "expected_magnetic_field_ut" to evidence?.expectedMagneticFieldMicrotesla.whole(),
            "magnetic_field_deviation_ut" to evidence?.magneticFieldDeviationMicrotesla.whole(),
            "magnetic_field_allowance_ut" to evidence?.magneticFieldAllowanceMicrotesla.whole(),
            "magnetic_horizontal_permille" to evidence?.magneticHorizontalRatio?.times(1_000.0).whole(),
            "magnetic_inclination_deg" to evidence?.magneticInclinationDegrees.whole(),
            "expected_magnetic_inclination_deg" to evidence?.expectedMagneticInclinationDegrees.whole(),
            "magnetic_inclination_residual_deg" to evidence?.magneticInclinationResidualDegrees.whole(),
            "magnetic_inclination_allowance_deg" to evidence?.magneticInclinationAllowanceDegrees.whole(),
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

    fun guidanceGenerated(
        update: RouteNavigatorUpdate,
        nowMs: Long,
        compass: RouteCompassHeadingResult? = null,
    ): Map<String, Any?> {
        val compassFields = compass?.let { compassState(it, nowMs) }?.toMutableMap() ?: mutableMapOf()
        compassFields.remove("reason")
        if (compass != null) compassFields["compass_reason"] = compass.reason.name
        return compassFields + mapOf(
        "cue_id" to update.speechCueToken?.cueId(),
        "elapsed_realtime_ms" to nowMs,
        "reason" to update.routeAlignmentDiagnostic?.reason?.name,
        "route_bearing_deg" to update.routeAlignmentDiagnostic?.bearingDegreesTrueNorth?.roundToInt(),
        "relative_angle_deg" to update.facingDiagnostic?.relativeAngleDegrees,
        "expression_level" to update.facingDiagnostic?.expression?.name,
        "clock_reference_kind" to update.facingDiagnostic?.clockReferenceKind?.name,
        )
    }

    /** The playback-start check of the same cue; [current] false means it was revoked before speaking. */
    fun guidanceStartChecked(update: RouteNavigatorUpdate, nowMs: Long, current: Boolean): Map<String, Any?> = mapOf(
        "cue_id" to update.speechCueToken?.cueId(),
        "elapsed_realtime_ms" to nowMs,
        "result" to if (current) "started" else "revoked",
    )

    private fun RouteSpeechCueToken.cueId() = "$routeRevision:$cueRevision"

    private fun Double?.whole(): Int? = this?.takeIf { it.isFinite() }?.roundToInt()
}

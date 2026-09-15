package kr.co.hanium.dreamup.walksafe.navigation

import kotlin.math.abs
import kotlin.math.roundToInt

enum class RouteFacingSource {
    ROTATION_VECTOR,
    GRAVITY_MAGNETIC,
    ACCELEROMETER_MAGNETIC,
}

enum class RouteFacingSensorQuality {
    REPORTED_ANGULAR_ERROR,
    CALIBRATED_SENSOR_CHECKS,
}

/** A rear-camera facing direction already projected onto the horizontal true-north plane. */
data class RouteFacingObservation(
    val degreesTrueNorth: Double,
    /** Reported-error-based angular allowance; null when the source reports no angular error. */
    val headingAccuracyDegrees: Double?,
    val observedAtElapsedRealtimeMs: Long,
    val source: RouteFacingSource = RouteFacingSource.ROTATION_VECTOR,
    val sensorQuality: RouteFacingSensorQuality = RouteFacingSensorQuality.REPORTED_ANGULAR_ERROR,
    /** Original sensor angular error, before the rear-axis projection allowance. */
    val reportedHeadingAccuracyDegrees: Double? = headingAccuracyDegrees,
)

/** The route's direction relative to the current facing, distinct from a route maneuver. */
enum class RouteFacingDirection {
    FRONT,
    LEFT,
    RIGHT,
    BEHIND,
    UNKNOWN,
}

data class RouteFacingGuidanceResult(
    val direction: RouteFacingDirection,
    /** Signed facing-to-route angle in [-180, 180); positive is clockwise/right. */
    val signedDifferenceDegrees: Double?,
) {
    /** A coarse clock position for a usable direction, never a new sensor accuracy claim. */
    val clockHour: Int?
        get() = signedDifferenceDegrees?.takeIf { direction != RouteFacingDirection.UNKNOWN }?.let {
            ((it + 360.0) % 360.0 / 30.0).roundToInt().let { hour -> if (hour % 12 == 0) 12 else hour % 12 }
        }
}

/** Classifies only the supplied fresh observation; never substitutes travel course or past facing. */
object RouteFacingGuidance {
    private const val MAXIMUM_OBSERVATION_AGE_MS = 500L
    private const val MAXIMUM_HEADING_ACCURACY_DEGREES = 30.0
    private const val BOUNDARY_MARGIN_DEGREES = 5.0
    // A coarse-direction decision guard for the calibrated magnetic fallback. This is a policy
    // margin, not an angular accuracy measurement or a statistical confidence interval.
    private const val FALLBACK_SECTOR_MARGIN_DEGREES = 20.0

    fun isUsableObservation(observation: RouteFacingObservation?, nowMs: Long): Boolean =
        observation != null && observation.degreesTrueNorth.isBearing() &&
            observation.hasAcceptedSourceQuality() &&
            nowMs >= 0L && observation.observedAtElapsedRealtimeMs >= 0L &&
            observation.observedAtElapsedRealtimeMs <= nowMs &&
            nowMs - observation.observedAtElapsedRealtimeMs <= MAXIMUM_OBSERVATION_AGE_MS

    fun evaluate(
        routeBearingDegreesTrueNorth: Double?,
        facingObservation: RouteFacingObservation?,
        nowElapsedRealtimeMs: Long,
    ): RouteFacingGuidanceResult {
        val observation = facingObservation
        if (routeBearingDegreesTrueNorth == null || !routeBearingDegreesTrueNorth.isBearing() ||
            observation == null || !isUsableObservation(observation, nowElapsedRealtimeMs)
        ) return RouteFacingGuidanceResult(RouteFacingDirection.UNKNOWN, null)

        val difference = (routeBearingDegreesTrueNorth - observation.degreesTrueNorth + 540.0) % 360.0 - 180.0
        // Include a small margin with the reported error before saying the route is ahead/behind.
        // Near a sector edge a consistent correction side is still useful, even for diagonal routes.
        // Do not retain a previous direction here: the user may have turned while standing still.
        val margin = observation.headingAccuracyDegrees?.let { it + BOUNDARY_MARGIN_DEGREES }
            ?: FALLBACK_SECTOR_MARGIN_DEGREES
        val direction = when {
            abs(difference) + margin < 45.0 -> RouteFacingDirection.FRONT
            abs(difference) - margin > 135.0 -> RouteFacingDirection.BEHIND
            difference - margin > 0.0 && difference + margin < 180.0 -> RouteFacingDirection.RIGHT
            difference - margin > -180.0 && difference + margin < 0.0 -> RouteFacingDirection.LEFT
            else -> RouteFacingDirection.UNKNOWN
        }
        return RouteFacingGuidanceResult(direction, difference)
    }

    private fun RouteFacingObservation.hasAcceptedSourceQuality(): Boolean = when (source) {
        RouteFacingSource.ROTATION_VECTOR ->
            sensorQuality == RouteFacingSensorQuality.REPORTED_ANGULAR_ERROR &&
                headingAccuracyDegrees?.let { it.isFinite() && it in 0.0..MAXIMUM_HEADING_ACCURACY_DEGREES } == true &&
                reportedHeadingAccuracyDegrees?.let { it.isFinite() && it in 0.0..MAXIMUM_HEADING_ACCURACY_DEGREES } == true
        RouteFacingSource.GRAVITY_MAGNETIC, RouteFacingSource.ACCELEROMETER_MAGNETIC ->
            sensorQuality == RouteFacingSensorQuality.CALIBRATED_SENSOR_CHECKS &&
                headingAccuracyDegrees == null && reportedHeadingAccuracyDegrees == null
    }

    private fun Double.isBearing(): Boolean = isFinite() && this >= 0.0 && this < 360.0
}

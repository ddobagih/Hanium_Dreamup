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
    /** Gravity/magnetic checks passed, but no contemporaneous raw acceleration could check motion. */
    CALIBRATED_WITHOUT_MOTION_CHECK,
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
    /**
     * Recent device rotation speed measured by the sensor stream (a lower bound for fallback
     * sources); null when no rate could be measured. It widens uncertainty with observation age;
     * it never moves the observation time or the heading forward.
     */
    val angularRateDegreesPerSecond: Double? = null,
    /** A fresh magnetometer sample disagreed strongly with the expected field for this place. */
    val magneticFieldSuspect: Boolean = false,
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
    val clockDirectionAllowed: Boolean = true,
    /**
     * The sensor evidence alone (error, rotation since the sample, route-bearing uncertainty,
     * magnetic check) supports a clock number at a sector centre. A number withheld only because
     * the angle is near a sector edge keeps this true.
     */
    val clockPrecisionAvailable: Boolean = false,
    /**
     * S08-23 (nav-F2): the angular margin [direction] was decided with (sensor, rotation since the
     * sample and route-bearing uncertainty); null when no usable observation was classified.
     */
    val directionMarginDegrees: Double? = null,
) {
    /** A coarse clock position for a usable direction, never a new sensor accuracy claim. */
    val clockHour: Int?
        get() = signedDifferenceDegrees?.takeIf { direction != RouteFacingDirection.UNKNOWN && clockDirectionAllowed }?.let {
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
            observation.angularRateDegreesPerSecond?.let { it.isFinite() && it >= 0.0 } != false &&
            nowMs >= 0L && observation.observedAtElapsedRealtimeMs >= 0L &&
            observation.observedAtElapsedRealtimeMs <= nowMs &&
            nowMs - observation.observedAtElapsedRealtimeMs <= MAXIMUM_OBSERVATION_AGE_MS

    fun evaluate(
        routeBearingDegreesTrueNorth: Double?,
        facingObservation: RouteFacingObservation?,
        nowElapsedRealtimeMs: Long,
        /** Largest angle between the selected route bearing and other plausible route candidates. */
        routeBearingUncertaintyDegrees: Double = 0.0,
    ): RouteFacingGuidanceResult {
        val observation = facingObservation
        if (routeBearingDegreesTrueNorth == null || !routeBearingDegreesTrueNorth.isBearing() ||
            !routeBearingUncertaintyDegrees.isFinite() || routeBearingUncertaintyDegrees < 0.0 ||
            observation == null || !isUsableObservation(observation, nowElapsedRealtimeMs)
        ) return RouteFacingGuidanceResult(RouteFacingDirection.UNKNOWN, null)

        val difference = (routeBearingDegreesTrueNorth - observation.degreesTrueNorth + 540.0) % 360.0 - 180.0
        // The phone may have turned since the sample: rotation speed times sample age is added
        // as uncertainty (no forward correction), so fast turning withholds the direction.
        val rotationSinceObservation = observation.angularRateDegreesPerSecond?.let {
            it * (nowElapsedRealtimeMs - observation.observedAtElapsedRealtimeMs) / 1_000.0
        } ?: 0.0
        // Include a small margin with the reported error before saying the route is ahead/behind.
        // Near a sector edge a consistent correction side is still useful, even for diagonal routes.
        // Do not retain a previous direction here: the user may have turned while standing still.
        val sensorMargin = observation.headingAccuracyDegrees
            ?.let { it + BOUNDARY_MARGIN_DEGREES }
            ?.let { if (observation.magneticFieldSuspect) maxOf(it, FALLBACK_SECTOR_MARGIN_DEGREES) else it }
            ?: FALLBACK_SECTOR_MARGIN_DEGREES
        // A direction is kept only when every plausible route candidate requires the same action.
        val margin = sensorMargin + rotationSinceObservation + routeBearingUncertaintyDegrees
        val direction = when {
            abs(difference) + margin < 45.0 -> RouteFacingDirection.FRONT
            abs(difference) - margin > 135.0 -> RouteFacingDirection.BEHIND
            difference - margin > 0.0 && difference + margin < 180.0 -> RouteFacingDirection.RIGHT
            difference - margin > -180.0 && difference + margin < 0.0 -> RouteFacingDirection.LEFT
            else -> RouteFacingDirection.UNKNOWN
        }
        val clockCenter = (difference / 30.0).roundToInt() * 30.0
        val clockPrecisionError = observation.headingAccuracyDegrees
            ?.takeUnless { observation.magneticFieldSuspect }
            ?.let { error -> error + rotationSinceObservation + routeBearingUncertaintyDegrees + BOUNDARY_MARGIN_DEGREES }
        val clockAllowed = clockPrecisionError?.let { abs(difference - clockCenter) + it < 15.0 } == true
        return RouteFacingGuidanceResult(direction, difference, clockAllowed, clockPrecisionError?.let { it < 15.0 } == true, margin)
    }

    private fun RouteFacingObservation.hasAcceptedSourceQuality(): Boolean = when (source) {
        RouteFacingSource.ROTATION_VECTOR ->
            sensorQuality == RouteFacingSensorQuality.REPORTED_ANGULAR_ERROR &&
                headingAccuracyDegrees?.let { it.isFinite() && it in 0.0..MAXIMUM_HEADING_ACCURACY_DEGREES } == true &&
                reportedHeadingAccuracyDegrees?.let { it.isFinite() && it in 0.0..MAXIMUM_HEADING_ACCURACY_DEGREES } == true
        RouteFacingSource.GRAVITY_MAGNETIC ->
            (sensorQuality == RouteFacingSensorQuality.CALIBRATED_SENSOR_CHECKS ||
                sensorQuality == RouteFacingSensorQuality.CALIBRATED_WITHOUT_MOTION_CHECK) &&
                headingAccuracyDegrees == null && reportedHeadingAccuracyDegrees == null
        // The raw-acceleration basis always runs its own short stability window.
        RouteFacingSource.ACCELEROMETER_MAGNETIC ->
            sensorQuality == RouteFacingSensorQuality.CALIBRATED_SENSOR_CHECKS &&
                headingAccuracyDegrees == null && reportedHeadingAccuracyDegrees == null
    }

    private fun Double.isBearing(): Boolean = isFinite() && this >= 0.0 && this < 360.0
}

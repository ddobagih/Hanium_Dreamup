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
    /** Gravity/magnetic checks passed; the raw acceleration quiet-motion check failed. */
    CALIBRATED_DURING_MOTION,
}

/**
 * A poor RV episode detected by the producer. The start is the original callback receipt, not a
 * renewed observation time. Its sequence distinguishes episodes even within the same millisecond
 * and survives producer stop/start. It is not evidence that sensor accuracy has recovered.
 */
data class RouteCompassRecoveryEpisode(
    val sequence: Long,
    val startedAtElapsedRealtimeMs: Long,
    /** Latest actual poor callback; a new rejection invalidates earlier continuity confirmations. */
    val lastRejectedAtElapsedRealtimeMs: Long = startedAtElapsedRealtimeMs,
)

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
    /** Fallback after a poor RV needs fresh continuity even when the consumer missed the rejection. */
    val fallbackRecoveryEpisode: RouteCompassRecoveryEpisode? = null,
    /** Separate route representative; raw yaw, error and measurement time above stay unchanged. */
    val headingTrend: RouteHeadingTrendEstimate? = null,
)

/** The route's direction relative to the current facing, distinct from a route maneuver. */
enum class RouteFacingDirection {
    FRONT,
    LEFT,
    RIGHT,
    BEHIND,
    UNKNOWN,
}

/** Whole 30-degree sectors under the supplied reported error or separate policy allowance. */
data class RouteClockRange(val hours: List<Int>) {
    val firstHour: Int get() = hours.first()
    val lastHour: Int get() = hours.last()
    fun spoken(): String = "약 ${firstHour}시에서 ${lastHour}시 사이 방향"
}

enum class RouteClockReferenceKind { REPORTED_ERROR, NOMINAL_TREND_CLOCK }

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
    private val boundedClockRange: RouteClockRange? = null,
    /** A nominal clock's qualitative policy guard, never a measured angular error. */
    val nominalClockReferenceAllowanceDegrees: Double? = null,
) {
    /** A coarse clock position for a usable direction, never a new sensor accuracy claim. */
    val clockHour: Int?
        get() = signedDifferenceDegrees?.takeIf { direction != RouteFacingDirection.UNKNOWN && clockDirectionAllowed }?.let {
            ((it + 360.0) % 360.0 / 30.0).roundToInt().let { hour -> if (hour % 12 == 0) 12 else hour % 12 }
        }

    val clockRange: RouteClockRange?
        get() = boundedClockRange.takeIf { direction != RouteFacingDirection.UNKNOWN }

    val hasClockReference: Boolean get() = clockHour != null || clockRange != null

    val clockReferenceKind: RouteClockReferenceKind?
        get() = when {
            !hasClockReference -> null
            nominalClockReferenceAllowanceDegrees != null -> RouteClockReferenceKind.NOMINAL_TREND_CLOCK
            else -> RouteClockReferenceKind.REPORTED_ERROR
        }

    /** A new observation may narrow the offered range, but cannot extend it or move outside it. */
    fun rangeCovers(fresh: RouteFacingGuidanceResult): Boolean {
        val offered = clockRange ?: return false
        val current = fresh.clockHour?.let { listOf(it) } ?: fresh.clockRange?.hours ?: return false
        return current.all { it in offered.hours }
    }

    /** Playback may finish across adjacent supported sectors; START still requires full coverage. */
    fun clockReferenceOverlaps(fresh: RouteFacingGuidanceResult): Boolean {
        val offered = clockHour?.let { listOf(it) } ?: clockRange?.hours ?: return false
        val current = fresh.clockHour?.let { listOf(it) } ?: fresh.clockRange?.hours ?: return false
        return offered.any { it in current }
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
            observation.fallbackRecoveryEpisode?.let {
                observation.source != RouteFacingSource.ROTATION_VECTOR && it.sequence > 0L &&
                    it.startedAtElapsedRealtimeMs in 0L..it.lastRejectedAtElapsedRealtimeMs &&
                    it.startedAtElapsedRealtimeMs <= observation.observedAtElapsedRealtimeMs &&
                    it.lastRejectedAtElapsedRealtimeMs <= nowMs
            } != false &&
            isObservationAgeCurrent(observation.observedAtElapsedRealtimeMs, nowMs)

    internal fun isObservationAgeCurrent(observedAtMs: Long, nowMs: Long): Boolean =
        nowMs >= 0L && observedAtMs >= 0L && observedAtMs <= nowMs &&
            nowMs - observedAtMs <= MAXIMUM_OBSERVATION_AGE_MS

    /** The original observation owns its finite deadline; rendering a cue never renews it. */
    internal fun observationValidUntilElapsedRealtimeMs(observation: RouteFacingObservation?, nowMs: Long): Long? {
        if (!isUsableObservation(observation, nowMs)) return null
        val observedAt = requireNotNull(observation).observedAtElapsedRealtimeMs
        return if (observedAt > Long.MAX_VALUE - 1L - MAXIMUM_OBSERVATION_AGE_MS) Long.MAX_VALUE - 1L
        else observedAt + MAXIMUM_OBSERVATION_AGE_MS
    }

    /** Reclassifies cached evidence for current geometry; this does not admit a fresh sensor sample. */
    internal fun evaluateRetainedReference(
        routeBearingDegreesTrueNorth: Double?,
        originalObservation: RouteFacingObservation,
        originallyClassifiedAtMs: Long,
        routeBearingUncertaintyDegrees: Double,
    ): RouteFacingGuidanceResult = evaluate(routeBearingDegreesTrueNorth, originalObservation,
        originallyClassifiedAtMs, routeBearingUncertaintyDegrees)

    fun evaluate(
        routeBearingDegreesTrueNorth: Double?,
        facingObservation: RouteFacingObservation?,
        nowElapsedRealtimeMs: Long,
        /** Largest angle between the selected route bearing and other plausible route candidates. */
        routeBearingUncertaintyDegrees: Double = 0.0,
    ): RouteFacingGuidanceResult = evaluateReference(routeBearingDegreesTrueNorth, facingObservation,
        nowElapsedRealtimeMs, routeBearingUncertaintyDegrees, useTrend = true)

    /** The actual current sensor direction precedes any historical representative for revocation. */
    internal fun evaluateRaw(
        bearing: Double?, observation: RouteFacingObservation?, nowMs: Long, uncertainty: Double = 0.0,
    ): RouteFacingGuidanceResult = evaluateReference(bearing, observation, nowMs, uncertainty, useTrend = false)

    private fun evaluateReference(
        routeBearingDegreesTrueNorth: Double?,
        facingObservation: RouteFacingObservation?,
        nowElapsedRealtimeMs: Long,
        routeBearingUncertaintyDegrees: Double,
        useTrend: Boolean,
    ): RouteFacingGuidanceResult {
        val observation = facingObservation
        if (routeBearingDegreesTrueNorth == null || !routeBearingDegreesTrueNorth.isBearing() ||
            !routeBearingUncertaintyDegrees.isFinite() || routeBearingUncertaintyDegrees < 0.0 ||
            observation == null || !isUsableObservation(observation, nowElapsedRealtimeMs)
        ) return RouteFacingGuidanceResult(RouteFacingDirection.UNKNOWN, null)

        val rawDifference = (routeBearingDegreesTrueNorth - observation.degreesTrueNorth + 540.0) % 360.0 - 180.0
        val representative = observation.headingTrend?.takeIf { useTrend && it.isBoundTo(observation) }
        val difference = representative?.let {
            (routeBearingDegreesTrueNorth - it.representativeDegreesTrueNorth + 540.0) % 360.0 - 180.0
        } ?: rawDifference
        // The phone may have turned since the sample: rotation speed times sample age is added
        // as uncertainty (no forward correction), so fast turning withholds the direction.
        val rotationSinceObservation = (representative?.representativeAngularRateDegreesPerSecond
            ?: observation.angularRateDegreesPerSecond)?.let {
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
        val rawRotationSinceObservation = observation.angularRateDegreesPerSecond?.let {
            it * (nowElapsedRealtimeMs - observation.observedAtElapsedRealtimeMs) / 1000.0
        } ?: 0.0
        val clockPrecisionError = observation.headingAccuracyDegrees
            ?.takeUnless { observation.magneticFieldSuspect }
            ?.let { error -> error + rawRotationSinceObservation + routeBearingUncertaintyDegrees + BOUNDARY_MARGIN_DEGREES }
        val rawClockCenter = (rawDifference / 30.0).roundToInt() * 30.0
        val reportedClock = representative?.largeTurnPending != true && clockPrecisionError?.let {
            abs(rawDifference - rawClockCenter) + it < 15.0 && clockCenter == rawClockCenter
        } == true
        // A nominal clock describes the representative, not a measured error interval. Direct
        // callers without history supply one real sample; Nav still requires two fresh clocks.
        // A wide partial cycle can describe its sway phase instead of the user's representative.
        // Warm that window before its first nominal claim; real large turns already reset it.
        val representativeReady = representative == null || representative.followsActualLargeTurn ||
            representative.observedHeadingSpanDegrees < 20.0 || representative.hasCompleteTimeWindow
        val motionQualified = observation.sensorQuality != RouteFacingSensorQuality.CALIBRATED_DURING_MOTION ||
            (representative != null && representative.hasCompleteTimeWindow && representative.sampleCount >= 2 &&
                representative.observedHeadingSpanDegrees <= 60.0)
        val nominalClock = useTrend && representative?.largeTurnPending != true && representativeReady && motionQualified && !reportedClock && direction != RouteFacingDirection.UNKNOWN &&
            !observation.magneticFieldSuspect
        return RouteFacingGuidanceResult(direction, difference, reportedClock || nominalClock,
            reportedClock, margin, boundedClockRange = null,
            nominalClockReferenceAllowanceDegrees = margin.takeIf { nominalClock })
    }

    private fun RouteFacingObservation.hasAcceptedSourceQuality(): Boolean = when (source) {
        RouteFacingSource.ROTATION_VECTOR ->
            sensorQuality == RouteFacingSensorQuality.REPORTED_ANGULAR_ERROR &&
                headingAccuracyDegrees?.let { it.isFinite() && it in 0.0..MAXIMUM_HEADING_ACCURACY_DEGREES } == true &&
                reportedHeadingAccuracyDegrees?.let { it.isFinite() && it in 0.0..MAXIMUM_HEADING_ACCURACY_DEGREES } == true
        RouteFacingSource.GRAVITY_MAGNETIC ->
            (sensorQuality == RouteFacingSensorQuality.CALIBRATED_SENSOR_CHECKS ||
                sensorQuality == RouteFacingSensorQuality.CALIBRATED_WITHOUT_MOTION_CHECK ||
                sensorQuality == RouteFacingSensorQuality.CALIBRATED_DURING_MOTION) &&
                headingAccuracyDegrees == null && reportedHeadingAccuracyDegrees == null
        // The raw-acceleration basis always runs its own short stability window.
        RouteFacingSource.ACCELEROMETER_MAGNETIC ->
            sensorQuality == RouteFacingSensorQuality.CALIBRATED_SENSOR_CHECKS &&
                headingAccuracyDegrees == null && reportedHeadingAccuracyDegrees == null
    }

    private fun Double.isBearing(): Boolean = isFinite() && this >= 0.0 && this < 360.0
}

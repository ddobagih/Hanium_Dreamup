package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * One unmodified platform location fix and its own speed/course evidence.
 *
 * "Raw" distinguishes this input from this app's filtered/PDR/map-matched output;
 * it does not mean Android raw satellite measurements. Callers must explicitly
 * trust the raw fix, and must never construct this from EstimatedLocation.
 */
data class RawWalkingCalibrationFix(
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyM: Double?,
    val elapsedRealtimeMs: Long,
    val receivedAtElapsedRealtimeMs: Long,
    val speedMps: Double?,
    val speedAccuracyMps: Double?,
    val courseDegrees: Double?,
    val courseAccuracyDegrees: Double?,
    val trustedRawFix: Boolean = false,
)

/**
 * Collects short, uninterrupted straight walks before allowing profile learning.
 * Distance is the raw segment's endpoint displacement, never the sum of noisy
 * fix-to-fix lengths. Raw speed/course and all intervening fixes must agree.
 *
 * totalStepCount must describe steps at the fix timestamp, not at receipt; use
 * TimestampAlignedWalkingCalibration for live timestamped detector events.
 * The 500 ms raw-fix freshness limit and other thresholds are conservative
 * evidence gates, not a measured accuracy claim.
 * Call reset on actor, collection, or walking-session changes, including early
 * returns that prevent an untrusted observation from reaching observe.
 */
class WalkingCalibrationSegmentPolicy {
    private data class Anchor(val fix: RawWalkingCalibrationFix, val totalStepCount: Int)

    private var anchor: Anchor? = null
    private var previousFix: RawWalkingCalibrationFix? = null
    private var previousStepCount = 0
    private var lastStepProgressAtMs = 0L
    private var speedDistanceM = 0.0
    private var minimumCourseOffsetDegrees = 0.0
    private var maximumCourseOffsetDegrees = 0.0

    fun reset() {
        anchor = null
        previousFix = null
        previousStepCount = 0
        lastStepProgressAtMs = 0L
        speedDistanceM = 0.0
        minimumCourseOffsetDegrees = 0.0
        maximumCourseOffsetDegrees = 0.0
    }

    fun observe(fix: RawWalkingCalibrationFix, totalStepCount: Int): WalkingCalibrationSample? {
        if (!isCredible(fix) || totalStepCount < 0) {
            reset()
            return null
        }
        val start = anchor
        val previous = previousFix
        if (start == null || previous == null) {
            begin(fix, totalStepCount)
            return null
        }

        val gapMs = fix.elapsedRealtimeMs - previous.elapsedRealtimeMs
        if (gapMs <= 0L) {
            // A duplicate or older fix must not become a replacement anchor.
            reset()
            return null
        }
        val durationMs = fix.elapsedRealtimeMs - start.fix.elapsedRealtimeMs
        if (gapMs > MAXIMUM_FIX_GAP_MS || durationMs > MAXIMUM_SEGMENT_DURATION_MS ||
            totalStepCount < previousStepCount
        ) {
            begin(fix, totalStepCount)
            return null
        }
        if (totalStepCount > previousStepCount) lastStepProgressAtMs = fix.elapsedRealtimeMs
        if (fix.elapsedRealtimeMs - lastStepProgressAtMs > MAXIMUM_STEP_SILENCE_MS) {
            reset()
            return null
        }

        val courseOffset = signedAngleDifference(fix.courseDegrees!!, start.fix.courseDegrees!!)
        minimumCourseOffsetDegrees = minOf(minimumCourseOffsetDegrees, courseOffset)
        maximumCourseOffsetDegrees = maxOf(maximumCourseOffsetDegrees, courseOffset)
        if (maximumCourseOffsetDegrees - minimumCourseOffsetDegrees > MAXIMUM_COURSE_SPREAD_DEGREES) {
            reset()
            return null
        }

        val intervalSpeedDistanceM = (previous.speedMps!! + fix.speedMps!!) * 0.5 * gapMs / 1_000.0
        val intervalAccuracyM = hypot(previous.horizontalAccuracyM!!, fix.horizontalAccuracyM!!)
        if (displacement(previous, fix).distanceM > intervalSpeedDistanceM + 2.0 * intervalAccuracyM) {
            reset()
            return null
        }
        speedDistanceM += intervalSpeedDistanceM

        val displacement = displacement(start.fix, fix)
        val endpointAccuracyM = hypot(start.fix.horizontalAccuracyM!!, fix.horizontalAccuracyM)
        val courseRadians = Math.toRadians(start.fix.courseDegrees)
        val forwardM = displacement.eastM * sin(courseRadians) + displacement.northM * cos(courseRadians)
        val lateralM = displacement.eastM * cos(courseRadians) - displacement.northM * sin(courseRadians)
        val corridorAllowanceM = max(2.5, 2.0 * endpointAccuracyM)
        val directionCheckDistanceM = max(8.0, 3.0 * endpointAccuracyM)
        if (forwardM < -corridorAllowanceM || abs(lateralM) > corridorAllowanceM ||
            (displacement.distanceM >= directionCheckDistanceM &&
                abs(signedAngleDifference(displacement.courseDegrees, start.fix.courseDegrees)) >
                MAXIMUM_DISPLACEMENT_COURSE_DIFFERENCE_DEGREES)
        ) {
            reset()
            return null
        }

        previousFix = fix
        previousStepCount = totalStepCount
        val steps = totalStepCount - start.totalStepCount
        val minimumDistanceM = max(MINIMUM_SEGMENT_DISTANCE_M, 5.0 * endpointAccuracyM)
        if (steps < MINIMUM_SEGMENT_STEPS || durationMs < MINIMUM_SEGMENT_DURATION_MS ||
            displacement.distanceM < minimumDistanceM
        ) return null

        // Doppler/platform speed is only a consistency check, not the learned distance.
        if (abs(displacement.distanceM - speedDistanceM) > max(2.0, 0.25 * speedDistanceM)) {
            reset()
            return null
        }
        val sample = WalkingCalibrationSample(
            distanceM = displacement.distanceM,
            stepCount = steps,
            durationMs = durationMs,
            trustedPositionSegment = true,
        )
        // Adjacent samples may share an endpoint, but never share steps or intervals.
        begin(fix, totalStepCount)
        return sample
    }

    private fun begin(fix: RawWalkingCalibrationFix, totalStepCount: Int) {
        reset()
        anchor = Anchor(fix, totalStepCount)
        previousFix = fix
        previousStepCount = totalStepCount
        lastStepProgressAtMs = fix.elapsedRealtimeMs
    }

    internal fun isCredible(fix: RawWalkingCalibrationFix): Boolean {
        if (!fix.trustedRawFix || !fix.latitude.isFinite() || fix.latitude !in -90.0..90.0 ||
            !fix.longitude.isFinite() || fix.longitude !in -180.0..180.0 ||
            fix.elapsedRealtimeMs < 0L || fix.receivedAtElapsedRealtimeMs < fix.elapsedRealtimeMs ||
            fix.receivedAtElapsedRealtimeMs - fix.elapsedRealtimeMs > MAXIMUM_FIX_AGE_MS
        ) return false
        val accuracyM = fix.horizontalAccuracyM ?: return false
        val speedMps = fix.speedMps ?: return false
        val speedAccuracyMps = fix.speedAccuracyMps ?: return false
        val courseDegrees = fix.courseDegrees ?: return false
        val courseAccuracyDegrees = fix.courseAccuracyDegrees ?: return false
        return accuracyM.isFinite() && accuracyM > 0.0 && accuracyM <= 3.0 &&
            speedMps.isFinite() && speedMps in 0.6..2.4 &&
            speedAccuracyMps.isFinite() && speedAccuracyMps in 0.0..0.35 &&
            speedMps - speedAccuracyMps >= 0.4 &&
            courseDegrees.isFinite() && courseDegrees >= 0.0 && courseDegrees < 360.0 &&
            courseAccuracyDegrees.isFinite() && courseAccuracyDegrees in 0.0..15.0
    }

    private data class Displacement(val eastM: Double, val northM: Double) {
        val distanceM: Double get() = hypot(eastM, northM)
        val courseDegrees: Double get() = (Math.toDegrees(atan2(eastM, northM)) + 360.0) % 360.0
    }

    private fun displacement(from: RawWalkingCalibrationFix, to: RawWalkingCalibrationFix): Displacement {
        // A local tangent approximation is adequate for the bounded walking segment.
        val longitudeDelta = signedAngleDifference(to.longitude, from.longitude)
        return Displacement(
            eastM = EARTH_RADIUS_M * Math.toRadians(longitudeDelta) *
                cos(Math.toRadians((from.latitude + to.latitude) * 0.5)),
            northM = EARTH_RADIUS_M * Math.toRadians(to.latitude - from.latitude),
        )
    }

    private fun signedAngleDifference(first: Double, second: Double): Double =
        (first - second + 540.0) % 360.0 - 180.0

    private companion object {
        const val EARTH_RADIUS_M = 6_371_000.0
        const val MAXIMUM_FIX_AGE_MS = 500L
        const val MAXIMUM_FIX_GAP_MS = 2_500L
        const val MAXIMUM_STEP_SILENCE_MS = 2_000L
        const val MINIMUM_SEGMENT_DURATION_MS = 10_000L
        const val MAXIMUM_SEGMENT_DURATION_MS = 60_000L
        const val MINIMUM_SEGMENT_DISTANCE_M = 15.0
        const val MINIMUM_SEGMENT_STEPS = 20
        const val MAXIMUM_COURSE_SPREAD_DEGREES = 15.0
        const val MAXIMUM_DISPLACEMENT_COURSE_DIFFERENCE_DEGREES = 20.0
    }
}

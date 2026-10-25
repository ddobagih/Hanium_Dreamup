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
    private data class Anchor(
        val fix: RawWalkingCalibrationFix,
        val totalStepCount: Int,
        val stepBracket: WalkingCalibrationStepBracket?,
    )

    private var anchor: Anchor? = null
    private var previousFix: RawWalkingCalibrationFix? = null
    private var previousStepCount = 0
    private var lastStepProgressAtMs = 0L
    private var speedDistanceM = 0.0
    private var minimumCourseOffsetDegrees = 0.0
    private var maximumCourseOffsetDegrees = 0.0

    var lastDecision: WalkingCalibrationDecision? = null
        private set

    fun reset() {
        anchor = null
        previousFix = null
        previousStepCount = 0
        lastStepProgressAtMs = 0L
        speedDistanceM = 0.0
        minimumCourseOffsetDegrees = 0.0
        maximumCourseOffsetDegrees = 0.0
        lastDecision = null
    }

    fun observe(fix: RawWalkingCalibrationFix, totalStepCount: Int): WalkingCalibrationSample? =
        observe(fix, totalStepCount, stepBracket = null)

    fun observe(
        fix: RawWalkingCalibrationFix,
        totalStepCount: Int,
        stepBracket: WalkingCalibrationStepBracket?,
    ): WalkingCalibrationSample? {
        val invalidReason = credibilityRejectionReason(fix)
            ?: WalkingCalibrationReason.INVALID_TOTAL_STEP_COUNT.takeIf { totalStepCount < 0 }
        if (invalidReason != null) {
            reset()
            return finish(WalkingCalibrationOutcome.REJECTED, invalidReason)
        }
        // Optional contradictory metadata is unknown; it does not change the original numeric gates.
        val endpointStepBracket = stepBracket?.takeIf { it.containsFix(fix.elapsedRealtimeMs) }
        val start = anchor
        val previous = previousFix
        if (start == null || previous == null) {
            begin(fix, totalStepCount, endpointStepBracket)
            return finish(WalkingCalibrationOutcome.WAITING, WalkingCalibrationReason.NEW_SEGMENT_ANCHOR)
        }

        val gapMs = fix.elapsedRealtimeMs - previous.elapsedRealtimeMs
        if (gapMs <= 0L) {
            // A duplicate or older fix must not become a replacement anchor.
            reset()
            return finish(WalkingCalibrationOutcome.REJECTED, WalkingCalibrationReason.FIX_ORDER)
        }
        val durationMs = fix.elapsedRealtimeMs - start.fix.elapsedRealtimeMs
        val continuityFailure = when {
            gapMs > MAXIMUM_FIX_GAP_MS -> WalkingCalibrationReason.FIX_GAP
            durationMs > MAXIMUM_SEGMENT_DURATION_MS -> WalkingCalibrationReason.SEGMENT_DURATION_EXCEEDED
            totalStepCount < previousStepCount -> WalkingCalibrationReason.STEP_COUNT_REGRESSED
            else -> null
        }
        if (continuityFailure != null) {
            begin(fix, totalStepCount, endpointStepBracket)
            return finish(WalkingCalibrationOutcome.REJECTED, continuityFailure)
        }
        if (totalStepCount > previousStepCount) lastStepProgressAtMs = fix.elapsedRealtimeMs
        if (fix.elapsedRealtimeMs - lastStepProgressAtMs > MAXIMUM_STEP_SILENCE_MS) {
            reset()
            return finish(WalkingCalibrationOutcome.REJECTED, WalkingCalibrationReason.STEP_SILENCE)
        }

        val courseOffset = signedAngleDifference(fix.courseDegrees!!, start.fix.courseDegrees!!)
        minimumCourseOffsetDegrees = minOf(minimumCourseOffsetDegrees, courseOffset)
        maximumCourseOffsetDegrees = maxOf(maximumCourseOffsetDegrees, courseOffset)
        if (maximumCourseOffsetDegrees - minimumCourseOffsetDegrees > MAXIMUM_COURSE_SPREAD_DEGREES) {
            reset()
            return finish(WalkingCalibrationOutcome.REJECTED, WalkingCalibrationReason.COURSE_SPREAD)
        }

        val intervalSpeedDistanceM = (previous.speedMps!! + fix.speedMps!!) * 0.5 * gapMs / 1_000.0
        val intervalAccuracyM = hypot(previous.horizontalAccuracyM!!, fix.horizontalAccuracyM!!)
        if (displacement(previous, fix).distanceM > intervalSpeedDistanceM + 2.0 * intervalAccuracyM) {
            reset()
            return finish(WalkingCalibrationOutcome.REJECTED, WalkingCalibrationReason.POSITION_JUMP)
        }
        speedDistanceM += intervalSpeedDistanceM

        val displacement = displacement(start.fix, fix)
        val endpointAccuracyM = hypot(start.fix.horizontalAccuracyM!!, fix.horizontalAccuracyM)
        val courseRadians = Math.toRadians(start.fix.courseDegrees)
        val forwardM = displacement.eastM * sin(courseRadians) + displacement.northM * cos(courseRadians)
        val lateralM = displacement.eastM * cos(courseRadians) - displacement.northM * sin(courseRadians)
        val corridorAllowanceM = max(2.5, 2.0 * endpointAccuracyM)
        val directionCheckDistanceM = max(8.0, 3.0 * endpointAccuracyM)
        val corridorFailure = when {
            forwardM < -corridorAllowanceM -> WalkingCalibrationReason.REVERSED_DISPLACEMENT
            abs(lateralM) > corridorAllowanceM -> WalkingCalibrationReason.LATERAL_DISPLACEMENT
            displacement.distanceM >= directionCheckDistanceM &&
                abs(signedAngleDifference(displacement.courseDegrees, start.fix.courseDegrees)) >
                    MAXIMUM_DISPLACEMENT_COURSE_DIFFERENCE_DEGREES -> WalkingCalibrationReason.DISPLACEMENT_COURSE_MISMATCH
            else -> null
        }
        if (corridorFailure != null) {
            reset()
            return finish(WalkingCalibrationOutcome.REJECTED, corridorFailure)
        }

        previousFix = fix
        previousStepCount = totalStepCount
        val steps = totalStepCount - start.totalStepCount
        val minimumDistanceM = max(MINIMUM_SEGMENT_DISTANCE_M, 5.0 * endpointAccuracyM)
        val waitingReason = when {
            steps < MINIMUM_SEGMENT_STEPS -> WalkingCalibrationReason.INSUFFICIENT_STEPS
            durationMs < MINIMUM_SEGMENT_DURATION_MS -> WalkingCalibrationReason.INSUFFICIENT_DURATION
            displacement.distanceM < minimumDistanceM -> WalkingCalibrationReason.INSUFFICIENT_DISTANCE
            else -> null
        }
        if (waitingReason != null) return finish(WalkingCalibrationOutcome.WAITING, waitingReason)

        // Doppler/platform speed is only a consistency check, not the learned distance.
        if (abs(displacement.distanceM - speedDistanceM) > max(2.0, 0.25 * speedDistanceM)) {
            reset()
            return finish(WalkingCalibrationOutcome.REJECTED, WalkingCalibrationReason.SPEED_DISTANCE_MISMATCH)
        }
        val sample = WalkingCalibrationSample(
            distanceM = displacement.distanceM,
            stepCount = steps,
            durationMs = durationMs,
            trustedPositionSegment = true,
            startEndpointEvidence = start.fix.endpointEvidence(start.stepBracket),
            endEndpointEvidence = fix.endpointEvidence(endpointStepBracket),
        )
        // Adjacent samples may share an endpoint, but never share steps or intervals.
        begin(fix, totalStepCount, endpointStepBracket)
        return finish(WalkingCalibrationOutcome.SAMPLE_READY, WalkingCalibrationReason.STRAIGHT_SEGMENT_READY, sample)
    }

    private fun finish(
        outcome: WalkingCalibrationOutcome,
        reason: WalkingCalibrationReason,
        sample: WalkingCalibrationSample? = null,
    ): WalkingCalibrationSample? {
        lastDecision = WalkingCalibrationDecision(WalkingCalibrationStage.STRAIGHT_SEGMENT, outcome, reason)
        return sample
    }

    private fun begin(
        fix: RawWalkingCalibrationFix,
        totalStepCount: Int,
        stepBracket: WalkingCalibrationStepBracket?,
    ) {
        reset()
        anchor = Anchor(fix, totalStepCount, stepBracket)
        previousFix = fix
        previousStepCount = totalStepCount
        lastStepProgressAtMs = fix.elapsedRealtimeMs
    }

    private fun RawWalkingCalibrationFix.endpointEvidence(stepBracket: WalkingCalibrationStepBracket?) =
        WalkingCalibrationEndpointEvidence(
            elapsedRealtimeMs = elapsedRealtimeMs,
            gateAccuracyProxyM = requireNotNull(horizontalAccuracyM),
            stepBracket = stepBracket,
        )

    internal fun isCredible(fix: RawWalkingCalibrationFix): Boolean = credibilityRejectionReason(fix) == null

    /** First evaluated failure, in the original credibility check order; no state is changed. */
    internal fun credibilityRejectionReason(fix: RawWalkingCalibrationFix): WalkingCalibrationReason? {
        if (!fix.trustedRawFix) return WalkingCalibrationReason.UNTRUSTED_RAW_FIX
        if (!fix.latitude.isFinite() || fix.latitude !in -90.0..90.0 ||
            !fix.longitude.isFinite() || fix.longitude !in -180.0..180.0
        ) return WalkingCalibrationReason.INVALID_FIX_COORDINATE
        if (fix.elapsedRealtimeMs < 0L || fix.receivedAtElapsedRealtimeMs < fix.elapsedRealtimeMs) {
            return WalkingCalibrationReason.INVALID_FIX_TIME
        }
        if (fix.receivedAtElapsedRealtimeMs - fix.elapsedRealtimeMs > MAXIMUM_FIX_AGE_MS) {
            return WalkingCalibrationReason.STALE_FIX
        }
        val accuracyM = fix.horizontalAccuracyM ?: return WalkingCalibrationReason.MISSING_FIX_ACCURACY
        val speedMps = fix.speedMps ?: return WalkingCalibrationReason.MISSING_SPEED
        val speedAccuracyMps = fix.speedAccuracyMps ?: return WalkingCalibrationReason.MISSING_SPEED_ACCURACY
        val courseDegrees = fix.courseDegrees ?: return WalkingCalibrationReason.MISSING_COURSE
        val courseAccuracyDegrees = fix.courseAccuracyDegrees ?: return WalkingCalibrationReason.MISSING_COURSE_ACCURACY
        if (!accuracyM.isFinite() || accuracyM <= 0.0 || accuracyM > 3.0) return WalkingCalibrationReason.UNSUITABLE_FIX_ACCURACY
        if (!speedMps.isFinite() || speedMps !in 0.6..2.4) return WalkingCalibrationReason.UNSUITABLE_SPEED
        if (!speedAccuracyMps.isFinite() || speedAccuracyMps !in 0.0..0.35 || speedMps - speedAccuracyMps < 0.4) {
            return WalkingCalibrationReason.UNSUITABLE_SPEED_ACCURACY
        }
        if (!courseDegrees.isFinite() || courseDegrees < 0.0 || courseDegrees >= 360.0) return WalkingCalibrationReason.UNSUITABLE_COURSE
        if (!courseAccuracyDegrees.isFinite() || courseAccuracyDegrees !in 0.0..15.0) return WalkingCalibrationReason.UNSUITABLE_COURSE_ACCURACY
        return null
    }

    private data class Displacement(val eastM: Double, val northM: Double) {
        val distanceM: Double get() = hypot(eastM, northM)
        val courseDegrees: Double get() = (Math.toDegrees(atan2(eastM, northM)) + 360.0) % 360.0
    }

    private fun displacement(from: RawWalkingCalibrationFix, to: RawWalkingCalibrationFix): Displacement {
        // A local tangent approximation is adequate for the bounded walking segment.
        val longitudeDelta = LocalGeodesy.wrapLongitudeDegrees(to.longitude - from.longitude)
        return Displacement(
            eastM = LocalGeodesy.METERS_PER_DEGREE * longitudeDelta *
                cos(Math.toRadians((from.latitude + to.latitude) * 0.5)),
            northM = LocalGeodesy.METERS_PER_DEGREE * (to.latitude - from.latitude),
        )
    }

    private fun signedAngleDifference(first: Double, second: Double): Double =
        (first - second + 540.0) % 360.0 - 180.0

    private companion object {
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

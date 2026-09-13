package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.HeadingObservation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.WalkingSpeedObservation

/** Quality evidence from the same location fix as its speed and course observations. */
data class UserMotionLocationEvidence(
    val observedAtElapsedRealtimeMs: Long,
    val horizontalAccuracyM: Double?,
    val isMock: Boolean,
)

/**
 * Independently accepted observations: travel course is not the direction the phone faces.
 * Missing or rejected observations remain null, including speed when movement is unknown.
 * Each accepted observation retains its source timestamp for downstream freshness checks.
 */
data class UserMotionEstimate(
    val speed: WalkingSpeedObservation? = null,
    val course: HeadingObservation? = null,
    val phoneHeading: HeadingObservation? = null,
) {
    val speedMps: Double?
        get() = speed?.speedMps

    val courseDegreesTrueNorth: Double?
        get() = course?.headingDegreesTrueNorth

    val phoneHeadingDegreesTrueNorth: Double?
        get() = phoneHeading?.headingDegreesTrueNorth
}

/**
 * Validates current user motion without inferring a travel direction from phone orientation.
 *
 * Both headings must already use true north. The caller must supply phone heading only after
 * calibration and mounting checks (for example, ChestMountedHeading); this helper rechecks its
 * numeric quality and freshness, but HeadingObservation itself carries no calibration state.
 */
object UserMotionEstimateFactory {
    fun estimate(
        nowElapsedRealtimeMs: Long,
        speed: WalkingSpeedObservation?,
        course: HeadingObservation?,
        phoneHeading: HeadingObservation?,
        locationEvidence: UserMotionLocationEvidence?,
    ): UserMotionEstimate {
        val location = locationEvidence?.takeIf {
            !it.isMock &&
                validAge(nowElapsedRealtimeMs, it.observedAtElapsedRealtimeMs, MAX_LOCATION_AGE_MS) &&
                validAccuracy(it.horizontalAccuracyM, MAX_HORIZONTAL_ACCURACY_M)
        }
        val acceptedSpeed = speed?.takeIf {
            location != null &&
                it.elapsedRealtimeMs == location.observedAtElapsedRealtimeMs &&
                it.speedMps.isFinite() && it.speedMps in 0.0..MAX_WALKING_SPEED_MPS &&
                validAccuracy(it.accuracyMps, MAX_SPEED_ACCURACY_MPS) &&
                (it.speedMps != 0.0 ||
                    validAccuracy(it.accuracyMps, MAX_ZERO_SPEED_ACCURACY_MPS))
        }
        val acceptedCourse = course?.takeIf {
            acceptedSpeed != null &&
                it.elapsedRealtimeMs == acceptedSpeed.elapsedRealtimeMs &&
                acceptedSpeed.speedMps - requireNotNull(acceptedSpeed.accuracyMps) >=
                MIN_COURSE_SPEED_LOWER_BOUND_MPS &&
                validHeading(it, nowElapsedRealtimeMs, MAX_LOCATION_AGE_MS, MAX_COURSE_ACCURACY_DEGREES)
        }?.normalized()
        val acceptedPhoneHeading = phoneHeading?.takeIf {
            validHeading(it, nowElapsedRealtimeMs, MAX_PHONE_HEADING_AGE_MS, MAX_PHONE_HEADING_ACCURACY_DEGREES)
        }?.normalized()

        return UserMotionEstimate(acceptedSpeed, acceptedCourse, acceptedPhoneHeading)
    }

    private fun validHeading(
        observation: HeadingObservation,
        nowElapsedRealtimeMs: Long,
        maximumAgeMs: Long,
        maximumAccuracyDegrees: Double,
    ): Boolean = observation.headingDegreesTrueNorth.isFinite() &&
        validAccuracy(observation.accuracyDegrees, maximumAccuracyDegrees) &&
        validAge(nowElapsedRealtimeMs, observation.elapsedRealtimeMs, maximumAgeMs)

    private fun validAge(now: Long, observedAt: Long, maximumAgeMs: Long): Boolean =
        now >= 0L && observedAt >= 0L && observedAt <= now && now - observedAt <= maximumAgeMs

    private fun validAccuracy(accuracy: Double?, maximum: Double): Boolean =
        accuracy != null && accuracy.isFinite() && accuracy in 0.0..maximum

    private fun HeadingObservation.normalized(): HeadingObservation {
        val remainder = headingDegreesTrueNorth % 360.0
        return copy(headingDegreesTrueNorth = if (remainder < 0.0) remainder + 360.0 else remainder)
    }

    private const val MAX_LOCATION_AGE_MS = 2_000L
    private const val MAX_HORIZONTAL_ACCURACY_M = 15.0
    private const val MAX_WALKING_SPEED_MPS = 3.5
    private const val MAX_SPEED_ACCURACY_MPS = 0.8
    private const val MAX_ZERO_SPEED_ACCURACY_MPS = 0.2
    private const val MIN_COURSE_SPEED_LOWER_BOUND_MPS = 0.5
    private const val MAX_COURSE_ACCURACY_DEGREES = 35.0
    private const val MAX_PHONE_HEADING_AGE_MS = 500L
    private const val MAX_PHONE_HEADING_ACCURACY_DEGREES = 30.0
}

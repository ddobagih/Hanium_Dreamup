package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class RouteCompassHeadingReason {
    VALID,
    SENSOR_UNAVAILABLE,
    ORIENTATION_MISSING,
    REFERENCE_MISSING,
    STALE,
    INVALID_TIMESTAMP,
    QUALITY_LOW,
    HEADING_ACCURACY_UNAVAILABLE,
    HEADING_ACCURACY,
    INVALID_ROTATION,
    REAR_AXIS_VERTICAL,
    GRAVITY_MISSING,
    MAGNETIC_FIELD_MISSING,
    SENSOR_SKEW,
    GRAVITY_UNSTABLE,
    MAGNETIC_FIELD_ANOMALY,
}

data class RouteCompassHeadingResult(
    val observation: RouteFacingObservation?,
    val reason: RouteCompassHeadingReason,
)

/** Device-coordinate SI acceleration or microtesla magnetic sample, with sensor time/quality. */
data class RouteCompassVectorSample(
    val x: Double,
    val y: Double,
    val z: Double,
    val observedAtMs: Long,
    val accuracy: EarthOrientationAccuracy,
) {
    internal fun norm(): Double = sqrt(x * x + y * y + z * z)
}

/** Route-only compass policy. The fallback never supplies orientation to AR or fixed-mount PDR. */
object RouteCompassHeading {
    private const val MAXIMUM_AGE_MS = 500L
    private const val MAXIMUM_SENSOR_SKEW_MS = 250L
    private const val MAXIMUM_HEADING_ERROR_DEGREES = 30.0
    private const val MINIMUM_FORWARD_HORIZONTAL = 0.5
    private const val STANDARD_GRAVITY = 9.80665
    // These motion/field checks are rejection heuristics, not angular accuracy estimates.
    private const val MAXIMUM_GRAVITY_DEVIATION = 2.5
    private const val MAXIMUM_LINEAR_ACCELERATION = 3.0
    private const val MINIMUM_HORIZONTAL_FIELD_RATIO = 0.1

    fun evaluate(
        orientation: DeviceEarthOrientation?,
        declinationDegrees: Double?,
        nowMs: Long,
    ): RouteCompassHeadingResult {
        orientation ?: return invalid(RouteCompassHeadingReason.ORIENTATION_MISSING)
        if (declinationDegrees == null || !declinationDegrees.isFinite()) {
            return invalid(RouteCompassHeadingReason.REFERENCE_MISSING)
        }
        timeFailure(orientation.observedAtElapsedRealtimeMs, nowMs)?.let { return invalid(it) }
        if (!orientation.accuracy.isAccepted()) return invalid(RouteCompassHeadingReason.QUALITY_LOW)
        val rawError = orientation.headingErrorDeg.toDouble()
        if (rawError.isNaN() || rawError < 0.0) {
            return invalid(RouteCompassHeadingReason.HEADING_ACCURACY_UNAVAILABLE)
        }
        if (!rawError.isFinite() || rawError > MAXIMUM_HEADING_ERROR_DEGREES) {
            return invalid(RouteCompassHeadingReason.HEADING_ACCURACY)
        }
        return project(
            orientation.deviceToMagneticEnu, declinationDegrees,
            orientation.observedAtElapsedRealtimeMs, RouteFacingSource.ROTATION_VECTOR, rawError,
        )
    }

    /** A fresh explicit poor-quality RV must not be bypassed with an unbounded magnetic estimate. */
    fun permitsFallback(primary: RouteCompassHeadingResult): Boolean = primary.observation == null &&
        primary.reason in setOf(
            RouteCompassHeadingReason.SENSOR_UNAVAILABLE,
            RouteCompassHeadingReason.ORIENTATION_MISSING,
            RouteCompassHeadingReason.HEADING_ACCURACY_UNAVAILABLE,
            RouteCompassHeadingReason.STALE,
        )

    /** Prefer gravity, but an absent/stale/invalid gravity stream must not hide usable acceleration. */
    fun evaluateFallback(
        gravity: RouteCompassVectorSample?,
        accelerometer: RouteCompassVectorSample?,
        magnetic: RouteCompassVectorSample?,
        reference: ChestMountedGeomagneticReference?,
        nowMs: Long,
    ): RouteCompassHeadingResult {
        val gravityResult = evaluateMagneticBasis(
            gravity, magnetic, reference, nowMs, RouteFacingSource.GRAVITY_MAGNETIC,
        )
        if (gravityResult.observation != null) {
            // When a contemporaneous raw acceleration exists, reject large motion even though the
            // fused gravity vector itself remains close to g. An absent raw sensor is not invented.
            if (accelerometer != null && timeFailure(accelerometer.observedAtMs, nowMs) == null &&
                abs(accelerometer.observedAtMs - requireNotNull(gravity).observedAtMs) <= MAXIMUM_SENSOR_SKEW_MS
            ) {
                val residual = sqrt(
                    (accelerometer.x - gravity.x) * (accelerometer.x - gravity.x) +
                        (accelerometer.y - gravity.y) * (accelerometer.y - gravity.y) +
                        (accelerometer.z - gravity.z) * (accelerometer.z - gravity.z),
                )
                if (!accelerometer.norm().isFinite() ||
                    abs(accelerometer.norm() - STANDARD_GRAVITY) > MAXIMUM_GRAVITY_DEVIATION ||
                    !residual.isFinite() || residual > MAXIMUM_LINEAR_ACCELERATION
                ) return invalid(RouteCompassHeadingReason.GRAVITY_UNSTABLE)
            }
            return gravityResult
        }
        // Shared magnetic/reference failures cannot be repaired by changing the gravity source.
        if (gravityResult.reason in setOf(
                RouteCompassHeadingReason.REFERENCE_MISSING,
                RouteCompassHeadingReason.MAGNETIC_FIELD_MISSING,
                RouteCompassHeadingReason.MAGNETIC_FIELD_ANOMALY,
                RouteCompassHeadingReason.REAR_AXIS_VERTICAL,
            )
        ) return gravityResult
        if (accelerometer == null) return gravityResult
        return evaluateMagneticBasis(
            accelerometer, magnetic, reference, nowMs, RouteFacingSource.ACCELEROMETER_MAGNETIC,
        )
    }

    private fun evaluateMagneticBasis(
        gravity: RouteCompassVectorSample?,
        magnetic: RouteCompassVectorSample?,
        reference: ChestMountedGeomagneticReference?,
        nowMs: Long,
        source: RouteFacingSource,
    ): RouteCompassHeadingResult {
        if (reference == null || !reference.declinationDegrees.isFinite() ||
            !reference.expectedFieldStrengthMicrotesla.isFinite() || reference.expectedFieldStrengthMicrotesla <= 0.0
        ) return invalid(RouteCompassHeadingReason.REFERENCE_MISSING)
        magnetic ?: return invalid(RouteCompassHeadingReason.MAGNETIC_FIELD_MISSING)
        gravity ?: return invalid(RouteCompassHeadingReason.GRAVITY_MISSING)
        timeFailure(magnetic.observedAtMs, nowMs)?.let { return invalid(it) }
        timeFailure(gravity.observedAtMs, nowMs)?.let { return invalid(it) }
        if (abs(gravity.observedAtMs - magnetic.observedAtMs) > MAXIMUM_SENSOR_SKEW_MS) {
            return invalid(RouteCompassHeadingReason.SENSOR_SKEW)
        }
        if (!magnetic.accuracy.isAccepted() || !gravity.accuracy.isAccepted()) {
            return invalid(RouteCompassHeadingReason.QUALITY_LOW)
        }
        val g = gravity.norm()
        if (!g.isFinite() || abs(g - STANDARD_GRAVITY) > MAXIMUM_GRAVITY_DEVIATION) {
            return invalid(RouteCompassHeadingReason.GRAVITY_UNSTABLE)
        }
        val field = magnetic.norm()
        val expectedField = reference.expectedFieldStrengthMicrotesla
        if (!field.isFinite() || field !in 15.0..100.0 ||
            abs(field - expectedField) > max(10.0, expectedField * 0.25)
        ) return invalid(RouteCompassHeadingReason.MAGNETIC_FIELD_ANOMALY)
        // Same ENU basis as Android SensorManager.getRotationMatrix: E = magnetic x gravity,
        // U = normalized gravity, N = U x E. Compute afresh; no old matrix survives failure.
        val hx = magnetic.y * gravity.z - magnetic.z * gravity.y
        val hy = magnetic.z * gravity.x - magnetic.x * gravity.z
        val hz = magnetic.x * gravity.y - magnetic.y * gravity.x
        val h = sqrt(hx * hx + hy * hy + hz * hz)
        // Reject nearly parallel vectors too: Android's absolute h threshold alone only rejects
        // an almost exact singularity and cannot establish a stable horizontal north basis.
        if (!h.isFinite() || h / (g * field) < MINIMUM_HORIZONTAL_FIELD_RATIO) {
            return invalid(RouteCompassHeadingReason.INVALID_ROTATION)
        }
        val ex = hx / h
        val ey = hy / h
        val ez = hz / h
        val ux = gravity.x / g
        val uy = gravity.y / g
        val uz = gravity.z / g
        val rotation = RotationMatrix3(
            ex.toFloat(), ey.toFloat(), ez.toFloat(),
            (uy * ez - uz * ey).toFloat(), (uz * ex - ux * ez).toFloat(), (ux * ey - uy * ex).toFloat(),
            ux.toFloat(), uy.toFloat(), uz.toFloat(),
        )
        return project(rotation, reference.declinationDegrees, min(gravity.observedAtMs, magnetic.observedAtMs), source, null)
    }

    private fun project(
        rotation: RotationMatrix3,
        declinationDegrees: Double,
        observedAt: Long,
        source: RouteFacingSource,
        reportedError: Double?,
    ): RouteCompassHeadingResult {
        if (!rotation.isOrthonormal()) return invalid(RouteCompassHeadingReason.INVALID_ROTATION)
        val forwardEast = -rotation.m02.toDouble()
        val forwardNorth = -rotation.m12.toDouble()
        val forwardHorizontal = sqrt(forwardEast * forwardEast + forwardNorth * forwardNorth)
        if (forwardHorizontal < MINIMUM_FORWARD_HORIZONTAL) return invalid(RouteCompassHeadingReason.REAR_AXIS_VERTICAL)
        // Projection allowance is a conservative heuristic, not a calibrated 95% interval.
        val projectedError = reportedError?.let { max(it, it / forwardHorizontal) }
        if (projectedError != null && projectedError > MAXIMUM_HEADING_ERROR_DEGREES) {
            return invalid(RouteCompassHeadingReason.HEADING_ACCURACY)
        }
        val magneticHeading = Math.toDegrees(atan2(forwardEast, forwardNorth))
        val trueHeading = ((magneticHeading + declinationDegrees % 360.0) % 360.0 + 360.0) % 360.0
        return RouteCompassHeadingResult(
            RouteFacingObservation(
                trueHeading, projectedError, observedAt, source,
                if (reportedError == null) RouteFacingSensorQuality.CALIBRATED_SENSOR_CHECKS
                else RouteFacingSensorQuality.REPORTED_ANGULAR_ERROR,
                reportedError,
            ),
            RouteCompassHeadingReason.VALID,
        )
    }

    private fun timeFailure(observedAt: Long, nowMs: Long): RouteCompassHeadingReason? = when {
        nowMs < 0L || observedAt < 0L || observedAt > nowMs -> RouteCompassHeadingReason.INVALID_TIMESTAMP
        nowMs - observedAt > MAXIMUM_AGE_MS -> RouteCompassHeadingReason.STALE
        else -> null
    }

    private fun EarthOrientationAccuracy.isAccepted() =
        this == EarthOrientationAccuracy.MEDIUM || this == EarthOrientationAccuracy.HIGH

    private fun invalid(reason: RouteCompassHeadingReason) = RouteCompassHeadingResult(null, reason)
}

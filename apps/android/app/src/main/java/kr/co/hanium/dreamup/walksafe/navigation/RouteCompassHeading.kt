package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import kotlin.math.abs
import kotlin.math.acos
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
    /** Registered sensors delivered no first event, or stopped delivering events. */
    SENSOR_STALLED,
}

/**
 * S08-8: one spoken recovery step for a compass result without a usable observation, grouped like
 * the navigation status screen (posture, sensor update wait, magnetic disturbance, position
 * reference). It ends with a space so a following sentence can be appended.
 */
internal fun RouteCompassHeadingReason.spokenRecovery(): String = when (this) {
    RouteCompassHeadingReason.REAR_AXIS_VERTICAL -> "휴대전화 후면을 걷는 방향으로 들어 주세요. "
    RouteCompassHeadingReason.GRAVITY_UNSTABLE -> "휴대전화 움직임이 커서 방향을 다시 확인하고 있으니 잠시 안정적으로 잡아 주세요. "
    RouteCompassHeadingReason.QUALITY_LOW, RouteCompassHeadingReason.HEADING_ACCURACY,
    RouteCompassHeadingReason.MAGNETIC_FIELD_ANOMALY -> "나침반 방향이 불안정합니다. 자석이나 금속에서 떨어져 휴대전화를 천천히 움직여 주세요. "
    RouteCompassHeadingReason.REFERENCE_MISSING -> "현재 위치를 확인해 나침반의 진북 기준을 맞추고 있습니다. "
    RouteCompassHeadingReason.SENSOR_UNAVAILABLE -> "나침반 센서를 시작하지 못했습니다. "
    RouteCompassHeadingReason.SENSOR_STALLED -> "나침반 센서 값이 들어오지 않고 있습니다. "
    RouteCompassHeadingReason.HEADING_ACCURACY_UNAVAILABLE, RouteCompassHeadingReason.GRAVITY_MISSING,
    RouteCompassHeadingReason.MAGNETIC_FIELD_MISSING -> "나침반 센서 값이 준비되기를 기다리고 있습니다. "
    RouteCompassHeadingReason.VALID, RouteCompassHeadingReason.ORIENTATION_MISSING, RouteCompassHeadingReason.STALE,
    RouteCompassHeadingReason.INVALID_TIMESTAMP, RouteCompassHeadingReason.INVALID_ROTATION,
    RouteCompassHeadingReason.SENSOR_SKEW -> "새 나침반 방향을 기다리고 있습니다. "
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
    // Two vectors combined from different moments are allowed to disagree by at most this much
    // device rotation; while turning, the tolerated time difference shrinks accordingly.
    private const val MAXIMUM_SKEW_ROTATION_DEGREES = 10.0
    private const val MAXIMUM_HEADING_ERROR_DEGREES = 30.0
    private const val MINIMUM_FORWARD_HORIZONTAL = 0.5
    private const val STANDARD_GRAVITY = 9.80665
    // These motion/field checks are rejection heuristics, not angular accuracy estimates.
    private const val MAXIMUM_GRAVITY_DEVIATION = 2.5
    private const val MAXIMUM_LINEAR_ACCELERATION = 3.0
    private const val MINIMUM_HORIZONTAL_FIELD_RATIO = 0.1
    // Raw acceleration used as gravity must be steady over a short window, not only at one instant.
    internal const val ACCELEROMETER_WINDOW_MS = 200L
    private const val MINIMUM_ACCELEROMETER_WINDOW_SAMPLES = 3
    private const val MAXIMUM_ACCELEROMETER_WINDOW_TILT_DEGREES = 10.0

    fun evaluate(
        orientation: DeviceEarthOrientation?,
        declinationDegrees: Double?,
        nowMs: Long,
        angularRateDegreesPerSecond: Double? = null,
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
            RouteFacingSensorQuality.REPORTED_ANGULAR_ERROR, angularRateDegreesPerSecond,
        )
    }

    /**
     * The rotation vector also fuses the magnetometer. A fresh, strongly anomalous field keeps the
     * RV observation (no blanket block, no extra UNKNOWN when the magnetometer is absent) but marks
     * it so that only coarse directions with the fallback margin remain.
     */
    fun crossCheckMagneticField(
        primary: RouteCompassHeadingResult,
        magnetic: RouteCompassVectorSample?,
        reference: ChestMountedGeomagneticReference?,
        nowMs: Long,
    ): RouteCompassHeadingResult {
        val observation = primary.observation ?: return primary
        if (observation.source != RouteFacingSource.ROTATION_VECTOR || magnetic == null ||
            reference == null || !reference.expectedFieldStrengthMicrotesla.isFinite() ||
            reference.expectedFieldStrengthMicrotesla <= 0.0 || timeFailure(magnetic.observedAtMs, nowMs) != null
        ) return primary
        if (!isMagneticFieldAnomalous(magnetic, reference.expectedFieldStrengthMicrotesla)) return primary
        return primary.copy(observation = observation.copy(magneticFieldSuspect = true))
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
    ): RouteCompassHeadingResult = evaluateFallback(
        gravity, listOfNotNull(accelerometer), listOfNotNull(magnetic), reference, nowMs, null,
    )

    /**
     * [accelerometerWindow] and [magneticHistory] are recent samples in any order. Each basis is
     * paired with the magnetic sample closest to its own sensor time; the permitted time
     * difference shrinks with the measured rotation speed.
     */
    fun evaluateFallback(
        gravity: RouteCompassVectorSample?,
        accelerometerWindow: List<RouteCompassVectorSample>,
        magneticHistory: List<RouteCompassVectorSample>,
        reference: ChestMountedGeomagneticReference?,
        nowMs: Long,
        angularRateDegreesPerSecond: Double?,
    ): RouteCompassHeadingResult {
        val skewLimitMs = maximumSkewMs(angularRateDegreesPerSecond)
        val accelerometer = accelerometerWindow.maxByOrNull { it.observedAtMs }
        val gravityResult = evaluateMagneticBasis(
            gravity, closest(magneticHistory, gravity?.observedAtMs ?: nowMs), reference, nowMs,
            RouteFacingSource.GRAVITY_MAGNETIC, skewLimitMs, angularRateDegreesPerSecond,
        )
        if (gravityResult.observation != null) {
            // When a contemporaneous raw acceleration exists, reject large motion even though the
            // fused gravity vector itself remains close to g. An absent raw sensor is not invented,
            // and the missing check is reported instead of being presented as a passed check.
            if (accelerometer != null && timeFailure(accelerometer.observedAtMs, nowMs) == null &&
                abs(accelerometer.observedAtMs - requireNotNull(gravity).observedAtMs) <= skewLimitMs
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
                return gravityResult
            }
            return gravityResult.copy(
                observation = gravityResult.observation.copy(
                    sensorQuality = RouteFacingSensorQuality.CALIBRATED_WITHOUT_MOTION_CHECK,
                ),
            )
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
        timeFailure(accelerometer.observedAtMs, nowMs)?.let { return invalid(it) }
        val window = accelerometerWindow.filter {
            it.observedAtMs <= accelerometer.observedAtMs &&
                accelerometer.observedAtMs - it.observedAtMs <= ACCELEROMETER_WINDOW_MS
        }.sortedBy { it.observedAtMs }
        val steady = steadyAcceleration(window) ?: run {
            // Report a more specific single-sample failure (quality, field, skew) first; otherwise
            // the missing short-window stability itself rejects the basis.
            val instant = evaluateMagneticBasis(
                accelerometer, closest(magneticHistory, accelerometer.observedAtMs), reference, nowMs,
                RouteFacingSource.ACCELEROMETER_MAGNETIC, skewLimitMs, angularRateDegreesPerSecond,
            )
            return if (instant.observation == null) instant else invalid(RouteCompassHeadingReason.GRAVITY_UNSTABLE)
        }
        // Pair and combine at the window centre, but report the oldest contributing time: the
        // window is a filter, and its delay counts against the 500 ms freshness limit.
        return evaluateMagneticBasis(
            steady, closest(magneticHistory, steady.observedAtMs), reference, nowMs,
            RouteFacingSource.ACCELEROMETER_MAGNETIC, skewLimitMs, angularRateDegreesPerSecond,
            reportedNotAfterMs = window.first().observedAtMs,
        )
    }

    /** Maximum sensor time difference for a given measured rotation speed; 250 ms when unknown. */
    fun maximumSkewMs(angularRateDegreesPerSecond: Double?): Long {
        val rate = angularRateDegreesPerSecond?.takeIf { it.isFinite() && it > 0.0 } ?: return MAXIMUM_SENSOR_SKEW_MS
        return min(MAXIMUM_SENSOR_SKEW_MS.toDouble(), MAXIMUM_SKEW_ROTATION_DEGREES * 1_000.0 / rate).toLong()
    }

    /** The sample whose sensor time is closest to [targetMs]; a tie keeps the newer sample. */
    fun closest(samples: List<RouteCompassVectorSample>, targetMs: Long): RouteCompassVectorSample? =
        samples.minWithOrNull(
            compareBy<RouteCompassVectorSample> { abs(it.observedAtMs - targetMs) }.thenByDescending { it.observedAtMs },
        )

    private fun steadyAcceleration(window: List<RouteCompassVectorSample>): RouteCompassVectorSample? {
        if (window.size < MINIMUM_ACCELEROMETER_WINDOW_SAMPLES) return null
        if (window.any { !it.norm().isFinite() || abs(it.norm() - STANDARD_GRAVITY) > MAXIMUM_GRAVITY_DEVIATION }) {
            return null
        }
        val mean = RouteCompassVectorSample(
            window.sumOf { it.x } / window.size,
            window.sumOf { it.y } / window.size,
            window.sumOf { it.z } / window.size,
            (window.first().observedAtMs + window.last().observedAtMs) / 2L,
            window.minOf { it.accuracy },
        )
        val meanNorm = mean.norm()
        if (!meanNorm.isFinite() || meanNorm <= 0.0) return null
        val steady = window.all { sample ->
            val cosine = (sample.x * mean.x + sample.y * mean.y + sample.z * mean.z) / (sample.norm() * meanNorm)
            Math.toDegrees(acos(cosine.coerceIn(-1.0, 1.0))) <= MAXIMUM_ACCELEROMETER_WINDOW_TILT_DEGREES
        }
        return mean.takeIf { steady }
    }

    private fun evaluateMagneticBasis(
        gravity: RouteCompassVectorSample?,
        magnetic: RouteCompassVectorSample?,
        reference: ChestMountedGeomagneticReference?,
        nowMs: Long,
        source: RouteFacingSource,
        skewLimitMs: Long,
        angularRateDegreesPerSecond: Double?,
        reportedNotAfterMs: Long? = null,
    ): RouteCompassHeadingResult {
        if (reference == null || !reference.declinationDegrees.isFinite() ||
            !reference.expectedFieldStrengthMicrotesla.isFinite() || reference.expectedFieldStrengthMicrotesla <= 0.0
        ) return invalid(RouteCompassHeadingReason.REFERENCE_MISSING)
        magnetic ?: return invalid(RouteCompassHeadingReason.MAGNETIC_FIELD_MISSING)
        gravity ?: return invalid(RouteCompassHeadingReason.GRAVITY_MISSING)
        timeFailure(magnetic.observedAtMs, nowMs)?.let { return invalid(it) }
        timeFailure(gravity.observedAtMs, nowMs)?.let { return invalid(it) }
        if (abs(gravity.observedAtMs - magnetic.observedAtMs) > skewLimitMs) {
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
        if (isMagneticFieldAnomalous(magnetic, reference.expectedFieldStrengthMicrotesla)) {
            return invalid(RouteCompassHeadingReason.MAGNETIC_FIELD_ANOMALY)
        }
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
        val observedAt = minOf(gravity.observedAtMs, magnetic.observedAtMs, reportedNotAfterMs ?: Long.MAX_VALUE)
        timeFailure(observedAt, nowMs)?.let { return invalid(it) }
        return project(
            rotation, reference.declinationDegrees, observedAt, source, null,
            RouteFacingSensorQuality.CALIBRATED_SENSOR_CHECKS, angularRateDegreesPerSecond,
        )
    }

    private fun isMagneticFieldAnomalous(magnetic: RouteCompassVectorSample, expectedField: Double): Boolean {
        val field = magnetic.norm()
        return !field.isFinite() || field !in 15.0..100.0 || abs(field - expectedField) > max(10.0, expectedField * 0.25)
    }

    private fun project(
        rotation: RotationMatrix3,
        declinationDegrees: Double,
        observedAt: Long,
        source: RouteFacingSource,
        reportedError: Double?,
        quality: RouteFacingSensorQuality,
        angularRateDegreesPerSecond: Double?,
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
                trueHeading, projectedError, observedAt, source, quality, reportedError,
                angularRateDegreesPerSecond = angularRateDegreesPerSecond?.takeIf { it.isFinite() && it >= 0.0 },
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

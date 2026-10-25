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
    RouteCompassHeadingReason.QUALITY_LOW -> "방향을 정확히 확인하기 어렵습니다. 휴대전화 뒷면을 앞쪽으로 유지해 주세요. "
    RouteCompassHeadingReason.HEADING_ACCURACY -> "방향의 측정 오차가 큽니다. 휴대전화 뒷면을 앞쪽으로 유지해 주세요. "
    RouteCompassHeadingReason.MAGNETIC_FIELD_ANOMALY -> "자기장 영향으로 방향이 불안정할 수 있습니다. 방향을 다시 측정하고 있습니다. "
    RouteCompassHeadingReason.REFERENCE_MISSING -> "현재 위치를 기다리고 있습니다. 위치가 확인되면 방향 안내를 이어갑니다. "
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
    val checkEvidence: RouteCompassCheckEvidence? = null,
)

/** Diagnostic facts only; a missing value means that check was not performed. */
data class RouteCompassCheckEvidence(
    val source: RouteFacingSource,
    val observedAtElapsedRealtimeMs: Long,
    val rejectedCheck: RouteCompassRejectedCheck? = null,
    val reportedHeadingAccuracyDegrees: Double? = null,
    val projectedHeadingAccuracyDegrees: Double? = null,
    val forwardHorizontal: Double? = null,
    val magneticFieldMicrotesla: Double? = null,
    val expectedMagneticFieldMicrotesla: Double? = null,
    val magneticFieldDeviationMicrotesla: Double? = null,
    val magneticFieldAllowanceMicrotesla: Double? = null,
    val magneticHorizontalRatio: Double? = null,
    val magneticInclinationDegrees: Double? = null,
    val expectedMagneticInclinationDegrees: Double? = null,
    val magneticInclinationResidualDegrees: Double? = null,
    val magneticInclinationAllowanceDegrees: Double? = null,
)

enum class RouteCompassRejectedCheck {
    REPORTED_HEADING_ERROR,
    REAR_AXIS_PROJECTION,
    MAGNETIC_FIELD_STRENGTH,
    MAGNETIC_HORIZONTAL_BASIS,
    MAGNETIC_FIELD_INCLINATION,
}

/** Device-coordinate SI acceleration or microtesla magnetic sample, with sensor time/quality. */
data class RouteCompassVectorSample(
    val x: Double,
    val y: Double,
    val z: Double,
    val observedAtMs: Long,
    val accuracy: EarthOrientationAccuracy,
    /** Original SensorEvent timestamp; null for callers that only carry millisecond precision. */
    val sensorTimestampNanos: Long? = null,
) {
    internal fun norm(): Double = sqrt(x * x + y * y + z * z)

    internal fun hasValidSensorTimestamp(): Boolean = sensorTimestampNanos?.let {
        it >= 0L && it / 1_000_000L == observedAtMs
    } ?: true
}

/** Route projection only. TOP_EDGE is for posture-excluded stationary instrumentation, not walking. */
enum class RouteCompassForwardAxis {
    REAR_CAMERA,
    TOP_EDGE;

    internal fun east(rotation: RotationMatrix3): Double = when (this) {
        REAR_CAMERA -> -rotation.m02.toDouble()
        TOP_EDGE -> rotation.m01.toDouble()
    }

    internal fun north(rotation: RotationMatrix3): Double = when (this) {
        REAR_CAMERA -> -rotation.m12.toDouble()
        TOP_EDGE -> rotation.m11.toDouble()
    }
}

/** Route-only compass policy. The fallback never supplies orientation to AR or fixed-mount PDR. */
object RouteCompassHeading {
    private const val MAXIMUM_AGE_MS = 500L
    private const val MAXIMUM_SENSOR_SKEW_MS = 250L
    // Two vectors combined from different moments are allowed to disagree by at most this much
    // device rotation; while turning, the tolerated time difference shrinks accordingly.
    private const val MAXIMUM_SKEW_ROTATION_DEGREES = 10.0
    private const val MAXIMUM_HEADING_ERROR_DEGREES = 30.0
    private const val MINIMUM_FORWARD_HORIZONTAL = 0.3
    private const val STANDARD_GRAVITY = 9.80665
    // These motion/field checks are rejection heuristics, not angular accuracy estimates.
    private const val MAXIMUM_GRAVITY_DEVIATION = 2.5
    private const val MAXIMUM_LINEAR_ACCELERATION = 3.0
    private const val MINIMUM_HORIZONTAL_FIELD_RATIO = 0.1
    // Route-only dip tolerance: a dip mismatch does not measure horizontal bearing error.
    // Keep the independent horizontal-basis check; strict chest/PDR policy remains separate.
    private const val MAXIMUM_INCLINATION_RESIDUAL_DEGREES = 30.0
    // Raw acceleration used as gravity must be steady over a short window, not only at one instant.
    internal const val ACCELEROMETER_WINDOW_MS = 200L
    private const val MINIMUM_ACCELEROMETER_WINDOW_SAMPLES = 3
    private const val MAXIMUM_ACCELEROMETER_WINDOW_TILT_DEGREES = 10.0

    fun evaluate(
        orientation: DeviceEarthOrientation?,
        declinationDegrees: Double?,
        nowMs: Long,
        angularRateDegreesPerSecond: Double? = null,
        forwardAxis: RouteCompassForwardAxis = RouteCompassForwardAxis.REAR_CAMERA,
    ): RouteCompassHeadingResult {
        orientation ?: return invalid(RouteCompassHeadingReason.ORIENTATION_MISSING)
        val rawError = orientation.headingErrorDeg.toDouble()
        val evidence = RouteCompassCheckEvidence(
            source = RouteFacingSource.ROTATION_VECTOR,
            observedAtElapsedRealtimeMs = orientation.observedAtElapsedRealtimeMs,
            reportedHeadingAccuracyDegrees = rawError.takeIf { it.isFinite() && it >= 0.0 },
        )
        if (declinationDegrees == null || !declinationDegrees.isFinite()) {
            return invalid(RouteCompassHeadingReason.REFERENCE_MISSING, evidence)
        }
        timeFailure(orientation.observedAtElapsedRealtimeMs, nowMs)?.let { return invalid(it, evidence) }
        if (!orientation.accuracy.isAccepted()) return invalid(RouteCompassHeadingReason.QUALITY_LOW, evidence)
        if (rawError.isNaN() || rawError < 0.0) {
            return invalid(RouteCompassHeadingReason.HEADING_ACCURACY_UNAVAILABLE,
                evidence.copy(rejectedCheck = RouteCompassRejectedCheck.REPORTED_HEADING_ERROR))
        }
        if (!rawError.isFinite() || rawError > MAXIMUM_HEADING_ERROR_DEGREES) {
            return invalid(RouteCompassHeadingReason.HEADING_ACCURACY,
                evidence.copy(rejectedCheck = RouteCompassRejectedCheck.REPORTED_HEADING_ERROR))
        }
        return project(
            orientation.deviceToMagneticEnu, declinationDegrees,
            orientation.observedAtElapsedRealtimeMs, RouteFacingSource.ROTATION_VECTOR, rawError,
            RouteFacingSensorQuality.REPORTED_ANGULAR_ERROR, angularRateDegreesPerSecond,
            evidence, forwardAxis,
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
        val evidence = magneticFieldEvidence(
            primary.checkEvidence ?: RouteCompassCheckEvidence(observation.source, observation.observedAtElapsedRealtimeMs,
                reportedHeadingAccuracyDegrees = observation.reportedHeadingAccuracyDegrees,
                projectedHeadingAccuracyDegrees = observation.headingAccuracyDegrees),
            magnetic, reference.expectedFieldStrengthMicrotesla,
        )
        val anomalous = isMagneticFieldAnomalous(magnetic, reference.expectedFieldStrengthMicrotesla)
        return primary.copy(
            observation = if (anomalous) observation.copy(magneticFieldSuspect = true) else observation,
            checkEvidence = evidence.copy(rejectedCheck = if (anomalous) RouteCompassRejectedCheck.MAGNETIC_FIELD_STRENGTH else null),
        )
    }

    /** A route-only fallback must pass its own checks; explicit invalid numeric RV data stays rejected. */
    fun permitsFallback(primary: RouteCompassHeadingResult): Boolean = primary.observation == null &&
        primary.reason in setOf(
            RouteCompassHeadingReason.SENSOR_UNAVAILABLE,
            RouteCompassHeadingReason.ORIENTATION_MISSING,
            RouteCompassHeadingReason.HEADING_ACCURACY_UNAVAILABLE,
            RouteCompassHeadingReason.STALE,
            RouteCompassHeadingReason.QUALITY_LOW,
        )

    /** Prefer gravity, but an absent/stale/invalid gravity stream must not hide usable acceleration. */
    fun evaluateFallback(
        gravity: RouteCompassVectorSample?,
        accelerometer: RouteCompassVectorSample?,
        magnetic: RouteCompassVectorSample?,
        reference: ChestMountedGeomagneticReference?,
        nowMs: Long,
        forwardAxis: RouteCompassForwardAxis = RouteCompassForwardAxis.REAR_CAMERA,
    ): RouteCompassHeadingResult = evaluateFallback(
        gravity, listOfNotNull(accelerometer), listOfNotNull(magnetic), reference, nowMs, null, forwardAxis,
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
        forwardAxis: RouteCompassForwardAxis = RouteCompassForwardAxis.REAR_CAMERA,
    ): RouteCompassHeadingResult {
        val skewLimitMs = maximumSkewMs(angularRateDegreesPerSecond)
        val latestMagneticAtMs = magneticHistory.maxOfOrNull { it.observedAtMs }
        val latestMagnetic = magneticHistory.lastOrNull { it.observedAtMs == latestMagneticAtMs }
        // Pairing preserves original timing, but a current independently bad field cannot hide
        // behind an older qualified pair and move the consumer's first BAD receipt into the future.
        if (latestMagnetic != null && latestMagnetic.hasValidSensorTimestamp() &&
            timeFailure(latestMagnetic.observedAtMs, nowMs) == null && reference != null &&
            reference.declinationDegrees.isFinite() && reference.inclinationDegrees.isFinite() &&
            reference.inclinationDegrees in -90.0..90.0 &&
            reference.expectedFieldStrengthMicrotesla.isFinite() && reference.expectedFieldStrengthMicrotesla > 0.0 &&
            isMagneticFieldAnomalous(latestMagnetic, reference.expectedFieldStrengthMicrotesla)
        ) {
            val evidence = magneticFieldEvidence(
                RouteCompassCheckEvidence(RouteFacingSource.GRAVITY_MAGNETIC, latestMagnetic.observedAtMs),
                latestMagnetic, reference.expectedFieldStrengthMicrotesla,
            )
            return invalid(RouteCompassHeadingReason.MAGNETIC_FIELD_ANOMALY,
                evidence.copy(rejectedCheck = RouteCompassRejectedCheck.MAGNETIC_FIELD_STRENGTH))
        }
        val accelerometer = accelerometerWindow.maxByOrNull { it.observedAtMs }
        val gravityResult = evaluateMagneticBasis(
            gravity, closest(magneticHistory, gravity?.observedAtMs ?: nowMs), reference, nowMs,
            RouteFacingSource.GRAVITY_MAGNETIC, skewLimitMs, angularRateDegreesPerSecond, forwardAxis,
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
                if (!accelerometer.norm().isFinite() || !residual.isFinite())
                    return invalid(RouteCompassHeadingReason.GRAVITY_UNSTABLE, gravityResult.checkEvidence)
                if (abs(accelerometer.norm() - STANDARD_GRAVITY) > MAXIMUM_GRAVITY_DEVIATION ||
                    residual > MAXIMUM_LINEAR_ACCELERATION
                ) {
                    // The fused gravity and magnetic basis passed their own checks. Raw movement
                    // failed the quiet-motion heuristic; preserve that distinction for route-only
                    // trend qualification rather than claiming this was a passed motion check.
                    return gravityResult.copy(observation = gravityResult.observation.copy(
                        sensorQuality = RouteFacingSensorQuality.CALIBRATED_DURING_MOTION))
                }
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
        if (window.any { !it.hasValidSensorTimestamp() }) return invalid(RouteCompassHeadingReason.INVALID_TIMESTAMP)
        val steady = steadyAcceleration(window) ?: run {
            // Report a more specific single-sample failure (quality, field, skew) first; otherwise
            // the missing short-window stability itself rejects the basis.
            val instant = evaluateMagneticBasis(
                accelerometer, closest(magneticHistory, accelerometer.observedAtMs), reference, nowMs,
                RouteFacingSource.ACCELEROMETER_MAGNETIC, skewLimitMs, angularRateDegreesPerSecond, forwardAxis,
            )
            return if (instant.observation == null) instant else invalid(RouteCompassHeadingReason.GRAVITY_UNSTABLE, instant.checkEvidence)
        }
        // Pair and combine at the window centre, but report the oldest contributing time: the
        // window is a filter, and its delay counts against the 500 ms freshness limit.
        return evaluateMagneticBasis(
            steady, closest(magneticHistory, steady.observedAtMs), reference, nowMs,
            RouteFacingSource.ACCELEROMETER_MAGNETIC, skewLimitMs, angularRateDegreesPerSecond, forwardAxis,
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
        // Same-ms Android events remain independent only when every input carries its original
        // nanosecond timestamp. Legacy/mixed windows conservatively use millisecond identity.
        val independentSamples = if (window.all { it.sensorTimestampNanos != null }) {
            window.map { it.sensorTimestampNanos }.distinct().size
        } else {
            window.map { it.observedAtMs }.distinct().size
        }
        if (independentSamples < MINIMUM_ACCELEROMETER_WINDOW_SAMPLES) return null
        // Keep every entry for the motion and worst-accuracy checks, including bad duplicates.
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
        forwardAxis: RouteCompassForwardAxis,
        reportedNotAfterMs: Long? = null,
    ): RouteCompassHeadingResult {
        val inputEvidence = (gravity ?: magnetic)?.let { RouteCompassCheckEvidence(source, it.observedAtMs) }
        if (reference == null || !reference.declinationDegrees.isFinite() ||
            !reference.inclinationDegrees.isFinite() || reference.inclinationDegrees !in -90.0..90.0 ||
            !reference.expectedFieldStrengthMicrotesla.isFinite() || reference.expectedFieldStrengthMicrotesla <= 0.0
        ) return invalid(RouteCompassHeadingReason.REFERENCE_MISSING, inputEvidence)
        magnetic ?: return invalid(RouteCompassHeadingReason.MAGNETIC_FIELD_MISSING, inputEvidence)
        gravity ?: return invalid(RouteCompassHeadingReason.GRAVITY_MISSING, inputEvidence)
        var evidence = RouteCompassCheckEvidence(source, minOf(gravity.observedAtMs, magnetic.observedAtMs))
        timeFailure(magnetic.observedAtMs, nowMs)?.let { return invalid(it, evidence) }
        timeFailure(gravity.observedAtMs, nowMs)?.let { return invalid(it, evidence) }
        if (abs(gravity.observedAtMs - magnetic.observedAtMs) > skewLimitMs) {
            return invalid(RouteCompassHeadingReason.SENSOR_SKEW, evidence)
        }
        if (!magnetic.accuracy.isAccepted() || !gravity.accuracy.isAccepted()) {
            return invalid(RouteCompassHeadingReason.QUALITY_LOW, evidence)
        }
        val g = gravity.norm()
        if (!g.isFinite() || abs(g - STANDARD_GRAVITY) > MAXIMUM_GRAVITY_DEVIATION) {
            return invalid(RouteCompassHeadingReason.GRAVITY_UNSTABLE, evidence)
        }
        val field = magnetic.norm()
        evidence = magneticFieldEvidence(evidence, magnetic, reference.expectedFieldStrengthMicrotesla)
        if (isMagneticFieldAnomalous(magnetic, reference.expectedFieldStrengthMicrotesla)) {
            return invalid(RouteCompassHeadingReason.MAGNETIC_FIELD_ANOMALY,
                evidence.copy(rejectedCheck = RouteCompassRejectedCheck.MAGNETIC_FIELD_STRENGTH))
        }
        // Same ENU basis as Android SensorManager.getRotationMatrix: E = magnetic x gravity,
        // U = normalized gravity, N = U x E. Compute afresh; no old matrix survives failure.
        val hx = magnetic.y * gravity.z - magnetic.z * gravity.y
        val hy = magnetic.z * gravity.x - magnetic.x * gravity.z
        val hz = magnetic.x * gravity.y - magnetic.y * gravity.x
        val h = sqrt(hx * hx + hy * hy + hz * hz)
        evidence = evidence.copy(magneticHorizontalRatio = h / (g * field))
        // Reject nearly parallel vectors too: Android's absolute h threshold alone only rejects
        // an almost exact singularity and cannot establish a stable horizontal north basis.
        if (!h.isFinite() || h / (g * field) < MINIMUM_HORIZONTAL_FIELD_RATIO) {
            return invalid(RouteCompassHeadingReason.INVALID_ROTATION,
                evidence.copy(rejectedCheck = RouteCompassRejectedCheck.MAGNETIC_HORIZONTAL_BASIS))
        }
        // Gravity is Earth-up. The signed dip is positive downwards and invariant under a
        // common device-pose rotation of both vectors. A matching dip cannot rule out a stable
        // horizontal bearing bias, so this check never supplies a numeric heading accuracy.
        val fieldUp = (magnetic.x * gravity.x + magnetic.y * gravity.y + magnetic.z * gravity.z) / g
        val inclination = Math.toDegrees(atan2(-fieldUp, h / g))
        val inclinationResidual = abs(inclination - reference.inclinationDegrees)
        evidence = evidence.copy(
            magneticInclinationDegrees = inclination,
            expectedMagneticInclinationDegrees = reference.inclinationDegrees,
            magneticInclinationResidualDegrees = inclinationResidual,
            magneticInclinationAllowanceDegrees = MAXIMUM_INCLINATION_RESIDUAL_DEGREES,
        )
        if (!inclination.isFinite() || inclinationResidual > MAXIMUM_INCLINATION_RESIDUAL_DEGREES) {
            return invalid(RouteCompassHeadingReason.MAGNETIC_FIELD_ANOMALY,
                evidence.copy(rejectedCheck = RouteCompassRejectedCheck.MAGNETIC_FIELD_INCLINATION))
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
        evidence = evidence.copy(observedAtElapsedRealtimeMs = observedAt)
        timeFailure(observedAt, nowMs)?.let { return invalid(it, evidence) }
        return project(
            rotation, reference.declinationDegrees, observedAt, source, null,
            RouteFacingSensorQuality.CALIBRATED_SENSOR_CHECKS, angularRateDegreesPerSecond,
            evidence, forwardAxis,
        )
    }

    private fun isMagneticFieldAnomalous(magnetic: RouteCompassVectorSample, expectedField: Double): Boolean {
        val field = magnetic.norm()
        return !field.isFinite() || field !in 15.0..100.0 ||
            abs(field - expectedField) > magneticFieldAllowance(field, expectedField)
    }

    // Route-only upper-strength tolerance: a stronger field alone does not measure azimuth error.
    // Keep the lower-strength allowance, absolute range and independent inclination check intact.
    // This is an admission heuristic, not a claim about the resulting heading's accuracy.
    private fun magneticFieldAllowance(field: Double, expectedField: Double): Double {
        val relativeAllowance = if (field > expectedField) 0.60 else 0.25
        return max(10.0, expectedField * relativeAllowance)
    }

    private fun magneticFieldEvidence(
        evidence: RouteCompassCheckEvidence,
        magnetic: RouteCompassVectorSample,
        expectedField: Double,
    ): RouteCompassCheckEvidence {
        val field = magnetic.norm()
        return evidence.copy(
            magneticFieldMicrotesla = field,
            expectedMagneticFieldMicrotesla = expectedField,
            magneticFieldDeviationMicrotesla = abs(field - expectedField),
            magneticFieldAllowanceMicrotesla = magneticFieldAllowance(field, expectedField),
        )
    }

    private fun project(
        rotation: RotationMatrix3,
        declinationDegrees: Double,
        observedAt: Long,
        source: RouteFacingSource,
        reportedError: Double?,
        quality: RouteFacingSensorQuality,
        angularRateDegreesPerSecond: Double?,
        checkEvidence: RouteCompassCheckEvidence,
        forwardAxis: RouteCompassForwardAxis,
    ): RouteCompassHeadingResult {
        if (!rotation.isOrthonormal()) return invalid(RouteCompassHeadingReason.INVALID_ROTATION, checkEvidence)
        val forwardEast = forwardAxis.east(rotation)
        val forwardNorth = forwardAxis.north(rotation)
        val forwardHorizontal = sqrt(forwardEast * forwardEast + forwardNorth * forwardNorth)
        var evidence = checkEvidence.copy(forwardHorizontal = forwardHorizontal)
        if (forwardHorizontal < MINIMUM_FORWARD_HORIZONTAL) return invalid(RouteCompassHeadingReason.REAR_AXIS_VERTICAL, evidence)
        // Projection allowance is a conservative heuristic, not a calibrated 95% interval.
        val projectedError = reportedError?.let { max(it, it / forwardHorizontal) }
        evidence = evidence.copy(projectedHeadingAccuracyDegrees = projectedError)
        if (projectedError != null && projectedError > MAXIMUM_HEADING_ERROR_DEGREES) {
            return invalid(RouteCompassHeadingReason.HEADING_ACCURACY,
                evidence.copy(rejectedCheck = RouteCompassRejectedCheck.REAR_AXIS_PROJECTION))
        }
        val magneticHeading = Math.toDegrees(atan2(forwardEast, forwardNorth))
        val trueHeading = ((magneticHeading + declinationDegrees % 360.0) % 360.0 + 360.0) % 360.0
        return RouteCompassHeadingResult(
            RouteFacingObservation(
                trueHeading, projectedError, observedAt, source, quality, reportedError,
                angularRateDegreesPerSecond = angularRateDegreesPerSecond?.takeIf { it.isFinite() && it >= 0.0 },
            ),
            RouteCompassHeadingReason.VALID,
            evidence,
        )
    }

    private fun timeFailure(observedAt: Long, nowMs: Long): RouteCompassHeadingReason? = when {
        nowMs < 0L || observedAt < 0L || observedAt > nowMs -> RouteCompassHeadingReason.INVALID_TIMESTAMP
        nowMs - observedAt > MAXIMUM_AGE_MS -> RouteCompassHeadingReason.STALE
        else -> null
    }

    private fun EarthOrientationAccuracy.isAccepted() =
        this == EarthOrientationAccuracy.MEDIUM || this == EarthOrientationAccuracy.HIGH

    private fun invalid(reason: RouteCompassHeadingReason, evidence: RouteCompassCheckEvidence? = null) =
        RouteCompassHeadingResult(null, reason, evidence)
}

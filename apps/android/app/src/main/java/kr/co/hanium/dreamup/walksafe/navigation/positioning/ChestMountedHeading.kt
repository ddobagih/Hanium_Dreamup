package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.EarthOrientationAccuracy
import kr.co.hanium.dreamup.walksafe.navigation.RotationMatrix3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

enum class ChestMountedHeadingSource {
    ROTATION_VECTOR_AND_MAGNETIC_FIELD,
}

enum class ChestMountedHeadingGateReason {
    VALID,
    MAGNETIC_SENSOR_UNAVAILABLE,
    ROTATION_MISSING,
    MAGNETIC_FIELD_MISSING,
    GEOMAGNETIC_REFERENCE_MISSING,
    INVALID_TIMESTAMP,
    ROTATION_STALE,
    MAGNETIC_FIELD_STALE,
    SENSOR_SKEW,
    ROTATION_ACCURACY,
    MAGNETIC_FIELD_ACCURACY,
    HEADING_ACCURACY,
    INVALID_ROTATION,
    INVALID_MAGNETIC_FIELD,
    INVALID_GEOMAGNETIC_REFERENCE,
    UNSUPPORTED_POSTURE,
    FIELD_ANOMALY,
    FIELD_INCLINATION_ANOMALY,
    FIELD_HORIZONTAL_DIRECTION_ANOMALY,
    FIELD_RECOVERING,
}

data class ChestMountedRotationSample(
    val deviceToMagneticEnu: RotationMatrix3,
    val observedAtMs: Long,
    val headingAccuracyDegrees: Double,
    val accuracy: EarthOrientationAccuracy,
)

data class ChestMountedMagneticFieldSample(
    val xMicrotesla: Double,
    val yMicrotesla: Double,
    val zMicrotesla: Double,
    val observedAtMs: Long,
    val accuracy: EarthOrientationAccuracy,
)

data class ChestMountedGeomagneticReference(
    val declinationDegrees: Double,
    val inclinationDegrees: Double,
    val expectedFieldStrengthMicrotesla: Double,
)

data class ChestMountedHeadingConfig(
    val maximumInclinationResidualDegrees: Double = 20.0,
    val maximumHorizontalEastResidualDegrees: Double = 15.0,
) {
    init {
        require(maximumInclinationResidualDegrees in 0.0..90.0)
        require(maximumHorizontalEastResidualDegrees in 0.0..90.0)
    }
}

data class ChestMountedHeadingResult(
    val trueHeadingDegrees: Double?,
    val accuracyDegrees: Double?,
    val observedAtMs: Long?,
    val gateReason: ChestMountedHeadingGateReason,
    val source: ChestMountedHeadingSource =
        ChestMountedHeadingSource.ROTATION_VECTOR_AND_MAGNETIC_FIELD,
) {
    val isValid: Boolean
        get() = gateReason == ChestMountedHeadingGateReason.VALID && trueHeadingDegrees != null
}

/**
 * Computes true-north walking heading for the fixed chest mount: portrait, screen toward the
 * body, back outward, and phone top upward. The mount's walking-forward axis is device -Z.
 */
class ChestMountedHeading(
    private val config: ChestMountedHeadingConfig = ChestMountedHeadingConfig(),
) {
    private var fieldAnomalyActive = false
    private var normalRecoveryCount = 0
    private var recoveryStartedAtMs: Long? = null
    private var lastRecoverySampleAtMs: Long? = null

    @Synchronized
    fun reset() {
        fieldAnomalyActive = false
        resetRecoveryEvidence()
    }

    @Synchronized
    fun evaluate(
        nowElapsedRealtimeMs: Long,
        rotation: ChestMountedRotationSample?,
        magneticField: ChestMountedMagneticFieldSample?,
        geomagneticReference: ChestMountedGeomagneticReference?,
        magneticSensorAvailable: Boolean = true,
    ): ChestMountedHeadingResult {
        if (!magneticSensorAvailable) {
            return invalid(ChestMountedHeadingGateReason.MAGNETIC_SENSOR_UNAVAILABLE, rotation)
        }
        rotation ?: return invalid(ChestMountedHeadingGateReason.ROTATION_MISSING)
        magneticField ?: return invalid(ChestMountedHeadingGateReason.MAGNETIC_FIELD_MISSING, rotation)
        geomagneticReference
            ?: return invalid(ChestMountedHeadingGateReason.GEOMAGNETIC_REFERENCE_MISSING, rotation)

        if (
            nowElapsedRealtimeMs < 0L ||
            rotation.observedAtMs < 0L ||
            magneticField.observedAtMs < 0L ||
            rotation.observedAtMs > nowElapsedRealtimeMs ||
            magneticField.observedAtMs > nowElapsedRealtimeMs
        ) {
            return invalid(ChestMountedHeadingGateReason.INVALID_TIMESTAMP, rotation)
        }
        if (nowElapsedRealtimeMs - rotation.observedAtMs > MAX_SAMPLE_AGE_MS) {
            return invalid(ChestMountedHeadingGateReason.ROTATION_STALE, rotation)
        }
        if (nowElapsedRealtimeMs - magneticField.observedAtMs > MAX_SAMPLE_AGE_MS) {
            return invalid(ChestMountedHeadingGateReason.MAGNETIC_FIELD_STALE, rotation)
        }
        if (abs(rotation.observedAtMs - magneticField.observedAtMs) > MAX_SENSOR_SKEW_MS) {
            return invalid(ChestMountedHeadingGateReason.SENSOR_SKEW, rotation)
        }
        if (!rotation.accuracy.isMediumOrBetter()) {
            return invalid(ChestMountedHeadingGateReason.ROTATION_ACCURACY, rotation)
        }
        if (!magneticField.accuracy.isMediumOrBetter()) {
            return invalid(ChestMountedHeadingGateReason.MAGNETIC_FIELD_ACCURACY, rotation)
        }
        if (
            !rotation.headingAccuracyDegrees.isFinite() ||
            rotation.headingAccuracyDegrees < 0.0 ||
            rotation.headingAccuracyDegrees > MAX_OUTPUT_ACCURACY_DEGREES
        ) {
            return invalid(ChestMountedHeadingGateReason.HEADING_ACCURACY, rotation)
        }
        if (!rotation.deviceToMagneticEnu.isOrthonormal()) {
            return invalid(ChestMountedHeadingGateReason.INVALID_ROTATION, rotation)
        }
        val deviceToEnu = rotation.deviceToMagneticEnu
        val deviceUpEarthUp = deviceToEnu.m21.toDouble()
        val forwardEast = -deviceToEnu.m02.toDouble()
        val forwardNorth = -deviceToEnu.m12.toDouble()
        val forwardHorizontal = sqrt(forwardEast * forwardEast + forwardNorth * forwardNorth)
        if (
            deviceUpEarthUp < POSTURE_ALIGNMENT_THRESHOLD ||
            forwardHorizontal < POSTURE_ALIGNMENT_THRESHOLD
        ) {
            return invalid(ChestMountedHeadingGateReason.UNSUPPORTED_POSTURE, rotation)
        }
        if (
            !magneticField.xMicrotesla.isFinite() ||
            !magneticField.yMicrotesla.isFinite() ||
            !magneticField.zMicrotesla.isFinite()
        ) {
            return invalid(ChestMountedHeadingGateReason.INVALID_MAGNETIC_FIELD, rotation)
        }
        if (
            !geomagneticReference.declinationDegrees.isFinite() ||
            !geomagneticReference.inclinationDegrees.isFinite() ||
            geomagneticReference.inclinationDegrees !in -90.0..90.0 ||
            !geomagneticReference.expectedFieldStrengthMicrotesla.isFinite() ||
            geomagneticReference.expectedFieldStrengthMicrotesla <= 0.0
        ) {
            return invalid(ChestMountedHeadingGateReason.INVALID_GEOMAGNETIC_REFERENCE, rotation)
        }

        val measuredFieldStrength = sqrt(
            magneticField.xMicrotesla * magneticField.xMicrotesla +
                magneticField.yMicrotesla * magneticField.yMicrotesla +
                magneticField.zMicrotesla * magneticField.zMicrotesla,
        )
        if (!measuredFieldStrength.isFinite()) {
            return invalid(ChestMountedHeadingGateReason.INVALID_MAGNETIC_FIELD, rotation)
        }
        val allowedFieldDifference = max(
            MINIMUM_FIELD_DIFFERENCE_MICROTESLA,
            geomagneticReference.expectedFieldStrengthMicrotesla * MAXIMUM_FIELD_DIFFERENCE_RATIO,
        )
        if (
            abs(measuredFieldStrength - geomagneticReference.expectedFieldStrengthMicrotesla) >
            allowedFieldDifference
        ) {
            fieldAnomalyActive = true
            resetRecoveryEvidence()
            return invalid(ChestMountedHeadingGateReason.FIELD_ANOMALY, rotation)
        }

        val fieldEast =
            deviceToEnu.m00 * magneticField.xMicrotesla +
                deviceToEnu.m01 * magneticField.yMicrotesla +
                deviceToEnu.m02 * magneticField.zMicrotesla
        val fieldNorth =
            deviceToEnu.m10 * magneticField.xMicrotesla +
                deviceToEnu.m11 * magneticField.yMicrotesla +
                deviceToEnu.m12 * magneticField.zMicrotesla
        val fieldUp =
            deviceToEnu.m20 * magneticField.xMicrotesla +
                deviceToEnu.m21 * magneticField.yMicrotesla +
                deviceToEnu.m22 * magneticField.zMicrotesla
        val horizontalFieldStrength = sqrt(fieldEast * fieldEast + fieldNorth * fieldNorth)
        val measuredInclinationDegrees = Math.toDegrees(
            atan2(-fieldUp, horizontalFieldStrength),
        )
        if (
            abs(measuredInclinationDegrees - geomagneticReference.inclinationDegrees) >
            config.maximumInclinationResidualDegrees
        ) {
            fieldAnomalyActive = true
            resetRecoveryEvidence()
            return invalid(ChestMountedHeadingGateReason.FIELD_INCLINATION_ANOMALY, rotation)
        }
        val horizontalEastResidualDegrees = abs(Math.toDegrees(atan2(fieldEast, fieldNorth)))
        if (horizontalEastResidualDegrees > config.maximumHorizontalEastResidualDegrees) {
            fieldAnomalyActive = true
            resetRecoveryEvidence()
            return invalid(
                ChestMountedHeadingGateReason.FIELD_HORIZONTAL_DIRECTION_ANOMALY,
                rotation,
            )
        }
        if (fieldAnomalyActive && !recordNormalRecoverySample(magneticField.observedAtMs)) {
            return invalid(ChestMountedHeadingGateReason.FIELD_RECOVERING, rotation)
        }

        val magneticHeadingDegrees = Math.toDegrees(atan2(forwardEast, forwardNorth))
        val trueHeadingDegrees = normalizeDegrees(
            magneticHeadingDegrees + geomagneticReference.declinationDegrees,
        )
        return ChestMountedHeadingResult(
            trueHeadingDegrees = trueHeadingDegrees,
            accuracyDegrees = rotation.headingAccuracyDegrees,
            observedAtMs = rotation.observedAtMs,
            gateReason = ChestMountedHeadingGateReason.VALID,
        )
    }

    private fun recordNormalRecoverySample(observedAtMs: Long): Boolean {
        val lastSampleAtMs = lastRecoverySampleAtMs
        if (lastSampleAtMs == null || observedAtMs > lastSampleAtMs) {
            if (recoveryStartedAtMs == null) recoveryStartedAtMs = observedAtMs
            lastRecoverySampleAtMs = observedAtMs
            normalRecoveryCount += 1
        }
        val recoveredByCount = normalRecoveryCount >= REQUIRED_NORMAL_RECOVERY_SAMPLES
        val recoveredByTime = recoveryStartedAtMs?.let {
            observedAtMs - it >= REQUIRED_NORMAL_RECOVERY_DURATION_MS
        } == true
        if (!recoveredByCount || !recoveredByTime) return false

        fieldAnomalyActive = false
        resetRecoveryEvidence()
        return true
    }

    private fun resetRecoveryEvidence() {
        normalRecoveryCount = 0
        recoveryStartedAtMs = null
        lastRecoverySampleAtMs = null
    }

    private fun invalid(
        reason: ChestMountedHeadingGateReason,
        rotation: ChestMountedRotationSample? = null,
    ) = ChestMountedHeadingResult(
        trueHeadingDegrees = null,
        accuracyDegrees = rotation?.headingAccuracyDegrees?.takeIf(Double::isFinite),
        observedAtMs = rotation?.observedAtMs,
        gateReason = reason,
    )

    private fun EarthOrientationAccuracy.isMediumOrBetter(): Boolean =
        this == EarthOrientationAccuracy.MEDIUM || this == EarthOrientationAccuracy.HIGH

    private fun normalizeDegrees(degrees: Double): Double {
        val normalized = degrees % FULL_CIRCLE_DEGREES
        return if (normalized < 0.0) normalized + FULL_CIRCLE_DEGREES else normalized
    }

    private companion object {
        const val MAX_SAMPLE_AGE_MS = 500L
        const val MAX_SENSOR_SKEW_MS = 250L
        const val MAX_OUTPUT_ACCURACY_DEGREES = 30.0
        const val POSTURE_ALIGNMENT_THRESHOLD = 0.85
        const val MINIMUM_FIELD_DIFFERENCE_MICROTESLA = 10.0
        const val MAXIMUM_FIELD_DIFFERENCE_RATIO = 0.25
        const val REQUIRED_NORMAL_RECOVERY_SAMPLES = 5
        const val REQUIRED_NORMAL_RECOVERY_DURATION_MS = 1_000L
        const val FULL_CIRCLE_DEGREES = 360.0
    }
}

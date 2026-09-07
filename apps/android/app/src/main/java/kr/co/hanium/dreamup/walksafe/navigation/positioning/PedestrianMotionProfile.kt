package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

data class WalkingCalibrationSample(
    val distanceM: Double,
    val stepCount: Int,
    val durationMs: Long,
    val trustedPositionSegment: Boolean,
)

enum class MotionCalibrationStatus {
    REJECTED,
    ACCEPTED_WARMUP,
    UPDATED,
}

enum class MotionCalibrationRejectionReason {
    UNTRUSTED_POSITION,
    INVALID_DISTANCE,
    INVALID_STEP_COUNT,
    INVALID_DURATION,
    IMPLAUSIBLE_STEP_LENGTH,
    IMPLAUSIBLE_WALKING_SPEED,
}

data class PedestrianMotionProfileSnapshot(
    val stepLengthM: Double,
    val meanWalkingSpeedMps: Double,
    val speedVarianceMps2: Double,
    val acceptedSampleCount: Long,
    val rejectedSampleCount: Long,
    val walkingProcessAccelerationSigmaMps2: Double,
    val isCalibrated: Boolean,
) {
    /** Compatibility alias used by positioning dynamics. */
    val averageWalkingSpeedMps: Double
        get() = meanWalkingSpeedMps
}

data class PersistedPedestrianMotionProfile(
    val stepLengthM: Double,
    val meanWalkingSpeedMps: Double,
    val speedVarianceMps2: Double,
    val acceptedSampleCount: Long,
)

data class PedestrianMotionCalibrationResult(
    val status: MotionCalibrationStatus,
    val rejectionReason: MotionCalibrationRejectionReason? = null,
    val snapshot: PedestrianMotionProfileSnapshot,
)

data class PedestrianMotionProfileConfig(
    val defaultStepLengthM: Double = 0.65,
    val defaultWalkingSpeedMps: Double = 1.20,
    val defaultSpeedVarianceMps2: Double = 0.16,
    val minimumAcceptedSamples: Int = 3,
    val recentSampleWindowSize: Int = 7,
    val ewmaAlpha: Double = 0.25,
    val maximumStepLengthChangePerUpdateM: Double = 0.05,
    val maximumWalkingSpeedChangePerUpdateMps: Double = 0.15,
    val maximumSpeedVarianceChangePerUpdateMps2: Double = 0.10,
    val minimumCalibrationDistanceM: Double = 1.0,
    val maximumCalibrationDistanceM: Double = 500.0,
    val minimumCalibrationStepCount: Int = 2,
    val maximumCalibrationStepCount: Int = 2_000,
    val minimumCalibrationDurationMs: Long = 1_000L,
    val maximumCalibrationDurationMs: Long = 600_000L,
    val minimumStepLengthM: Double = 0.30,
    val maximumStepLengthM: Double = 1.20,
    val minimumWalkingSpeedMps: Double = 0.20,
    val maximumWalkingSpeedMps: Double = 3.00,
    val minimumSpeedVarianceMps2: Double = 0.0025,
    val maximumSpeedVarianceMps2: Double = 1.00,
    val minimumWalkingProcessAccelerationSigmaMps2: Double = 0.50,
    val maximumWalkingProcessAccelerationSigmaMps2: Double = 3.00,
    val speedVariationResponseSeconds: Double = 0.40,
) {
    init {
        require(defaultStepLengthM.isFinite() && defaultStepLengthM in minimumStepLengthM..maximumStepLengthM)
        require(defaultWalkingSpeedMps.isFinite() && defaultWalkingSpeedMps in minimumWalkingSpeedMps..maximumWalkingSpeedMps)
        require(defaultSpeedVarianceMps2.isFinite() && defaultSpeedVarianceMps2 in minimumSpeedVarianceMps2..maximumSpeedVarianceMps2)
        require(minimumAcceptedSamples >= 2)
        require(recentSampleWindowSize >= minimumAcceptedSamples)
        require(ewmaAlpha.isFinite() && ewmaAlpha in 0.0..1.0 && ewmaAlpha > 0.0)
        require(maximumStepLengthChangePerUpdateM.isFinite() && maximumStepLengthChangePerUpdateM > 0.0)
        require(maximumWalkingSpeedChangePerUpdateMps.isFinite() && maximumWalkingSpeedChangePerUpdateMps > 0.0)
        require(maximumSpeedVarianceChangePerUpdateMps2.isFinite() && maximumSpeedVarianceChangePerUpdateMps2 > 0.0)
        require(minimumCalibrationDistanceM.isFinite() && minimumCalibrationDistanceM > 0.0)
        require(maximumCalibrationDistanceM.isFinite() && maximumCalibrationDistanceM >= minimumCalibrationDistanceM)
        require(minimumCalibrationStepCount > 0)
        require(maximumCalibrationStepCount >= minimumCalibrationStepCount)
        require(minimumCalibrationDurationMs > 0L)
        require(maximumCalibrationDurationMs >= minimumCalibrationDurationMs)
        require(minimumStepLengthM.isFinite() && minimumStepLengthM > 0.0)
        require(maximumStepLengthM.isFinite() && maximumStepLengthM >= minimumStepLengthM)
        require(minimumWalkingSpeedMps.isFinite() && minimumWalkingSpeedMps > 0.0)
        require(maximumWalkingSpeedMps.isFinite() && maximumWalkingSpeedMps >= minimumWalkingSpeedMps)
        require(minimumSpeedVarianceMps2.isFinite() && minimumSpeedVarianceMps2 >= 0.0)
        require(maximumSpeedVarianceMps2.isFinite() && maximumSpeedVarianceMps2 >= minimumSpeedVarianceMps2)
        require(minimumWalkingProcessAccelerationSigmaMps2.isFinite() && minimumWalkingProcessAccelerationSigmaMps2 > 0.0)
        require(maximumWalkingProcessAccelerationSigmaMps2.isFinite() && maximumWalkingProcessAccelerationSigmaMps2 >= minimumWalkingProcessAccelerationSigmaMps2)
        require(speedVariationResponseSeconds.isFinite() && speedVariationResponseSeconds > 0.0)
    }
}

/**
 * Learns pedestrian motion parameters only from trusted position segments.
 *
 * Effective values remain at safe defaults until enough independent samples
 * exist. Afterwards, recent medians reject isolated outliers and bounded EWMA
 * updates keep each accepted segment from causing an abrupt profile change.
 */
class PedestrianMotionProfile(
    private val config: PedestrianMotionProfileConfig = PedestrianMotionProfileConfig(),
) {
    private val recentStepLengthsM = mutableListOf<Double>()
    private val recentWalkingSpeedsMps = mutableListOf<Double>()
    private var calibratedStepLengthM = config.defaultStepLengthM
    private var calibratedWalkingSpeedMps = config.defaultWalkingSpeedMps
    private var calibratedSpeedVarianceMps2 = config.defaultSpeedVarianceMps2
    private var acceptedSampleCount = 0L
    private var rejectedSampleCount = 0L

    fun calibrate(sample: WalkingCalibrationSample): PedestrianMotionCalibrationResult {
        val rejection = validate(sample)
        if (rejection != null) {
            rejectedSampleCount = saturatedIncrement(rejectedSampleCount)
            return PedestrianMotionCalibrationResult(
                status = MotionCalibrationStatus.REJECTED,
                rejectionReason = rejection,
                snapshot = currentSnapshot(),
            )
        }

        val stepLengthM = sample.distanceM / sample.stepCount
        val walkingSpeedMps = sample.distanceM / (sample.durationMs / MILLIS_PER_SECOND)
        appendRecent(recentStepLengthsM, stepLengthM)
        appendRecent(recentWalkingSpeedsMps, walkingSpeedMps)
        acceptedSampleCount = saturatedIncrement(acceptedSampleCount)

        val status = if (acceptedSampleCount < config.minimumAcceptedSamples) {
            MotionCalibrationStatus.ACCEPTED_WARMUP
        } else {
            updateCalibratedValues()
            MotionCalibrationStatus.UPDATED
        }
        return PedestrianMotionCalibrationResult(status = status, snapshot = currentSnapshot())
    }

    fun currentSnapshot(): PedestrianMotionProfileSnapshot {
        val calibrated = acceptedSampleCount >= config.minimumAcceptedSamples
        val stepLengthM = if (calibrated) calibratedStepLengthM else config.defaultStepLengthM
        val walkingSpeedMps = if (calibrated) calibratedWalkingSpeedMps else config.defaultWalkingSpeedMps
        val speedVarianceMps2 = if (calibrated) calibratedSpeedVarianceMps2 else config.defaultSpeedVarianceMps2
        return PedestrianMotionProfileSnapshot(
            stepLengthM = stepLengthM,
            meanWalkingSpeedMps = walkingSpeedMps,
            speedVarianceMps2 = speedVarianceMps2,
            acceptedSampleCount = acceptedSampleCount,
            rejectedSampleCount = rejectedSampleCount,
            walkingProcessAccelerationSigmaMps2 = processAccelerationSigma(speedVarianceMps2),
            isCalibrated = calibrated,
        )
    }

    fun snapshotForPersistence(): PersistedPedestrianMotionProfile? {
        if (acceptedSampleCount < config.minimumAcceptedSamples) return null
        return PersistedPedestrianMotionProfile(
            stepLengthM = calibratedStepLengthM,
            meanWalkingSpeedMps = calibratedWalkingSpeedMps,
            speedVarianceMps2 = calibratedSpeedVarianceMps2,
            acceptedSampleCount = acceptedSampleCount,
        )
    }

    /** Restores atomically; invalid or insufficient persisted evidence changes nothing. */
    fun restore(persisted: PersistedPedestrianMotionProfile): Boolean {
        if (!isValidPersistedProfile(persisted)) return false

        calibratedStepLengthM = persisted.stepLengthM
        calibratedWalkingSpeedMps = persisted.meanWalkingSpeedMps
        calibratedSpeedVarianceMps2 = persisted.speedVarianceMps2
        acceptedSampleCount = persisted.acceptedSampleCount
        recentStepLengthsM.clear()
        recentWalkingSpeedsMps.clear()
        repeat(min(config.minimumAcceptedSamples, config.recentSampleWindowSize)) {
            recentStepLengthsM += persisted.stepLengthM
            recentWalkingSpeedsMps += persisted.meanWalkingSpeedMps
        }
        return true
    }

    private fun validate(sample: WalkingCalibrationSample): MotionCalibrationRejectionReason? {
        if (!sample.distanceM.isFinite() || sample.distanceM !in config.minimumCalibrationDistanceM..config.maximumCalibrationDistanceM) {
            return MotionCalibrationRejectionReason.INVALID_DISTANCE
        }
        if (sample.stepCount !in config.minimumCalibrationStepCount..config.maximumCalibrationStepCount) {
            return MotionCalibrationRejectionReason.INVALID_STEP_COUNT
        }
        if (sample.durationMs !in config.minimumCalibrationDurationMs..config.maximumCalibrationDurationMs) {
            return MotionCalibrationRejectionReason.INVALID_DURATION
        }
        if (!sample.trustedPositionSegment) return MotionCalibrationRejectionReason.UNTRUSTED_POSITION

        val stepLengthM = sample.distanceM / sample.stepCount
        if (!stepLengthM.isFinite() || stepLengthM !in config.minimumStepLengthM..config.maximumStepLengthM) {
            return MotionCalibrationRejectionReason.IMPLAUSIBLE_STEP_LENGTH
        }
        val walkingSpeedMps = sample.distanceM / (sample.durationMs / MILLIS_PER_SECOND)
        if (!walkingSpeedMps.isFinite() || walkingSpeedMps !in config.minimumWalkingSpeedMps..config.maximumWalkingSpeedMps) {
            return MotionCalibrationRejectionReason.IMPLAUSIBLE_WALKING_SPEED
        }
        return null
    }

    private fun updateCalibratedValues() {
        calibratedStepLengthM = boundedEwma(
            current = calibratedStepLengthM,
            target = median(recentStepLengthsM),
            maximumChange = config.maximumStepLengthChangePerUpdateM,
        ).coerceIn(config.minimumStepLengthM, config.maximumStepLengthM)
        calibratedWalkingSpeedMps = boundedEwma(
            current = calibratedWalkingSpeedMps,
            target = median(recentWalkingSpeedsMps),
            maximumChange = config.maximumWalkingSpeedChangePerUpdateMps,
        ).coerceIn(config.minimumWalkingSpeedMps, config.maximumWalkingSpeedMps)

        val speedMedian = median(recentWalkingSpeedsMps)
        val medianAbsoluteDeviation = median(recentWalkingSpeedsMps.map { abs(it - speedMedian) })
        val robustVariance = (MAD_TO_SIGMA * medianAbsoluteDeviation).let { it * it }
            .coerceIn(config.minimumSpeedVarianceMps2, config.maximumSpeedVarianceMps2)
        calibratedSpeedVarianceMps2 = boundedEwma(
            current = calibratedSpeedVarianceMps2,
            target = robustVariance,
            maximumChange = config.maximumSpeedVarianceChangePerUpdateMps2,
        ).coerceIn(config.minimumSpeedVarianceMps2, config.maximumSpeedVarianceMps2)
    }

    private fun boundedEwma(current: Double, target: Double, maximumChange: Double): Double {
        val ewma = current + config.ewmaAlpha * (target - current)
        return ewma.coerceIn(current - maximumChange, current + maximumChange)
    }

    private fun processAccelerationSigma(speedVarianceMps2: Double): Double =
        (config.minimumWalkingProcessAccelerationSigmaMps2 +
            sqrt(speedVarianceMps2) / config.speedVariationResponseSeconds)
            .coerceIn(
                config.minimumWalkingProcessAccelerationSigmaMps2,
                config.maximumWalkingProcessAccelerationSigmaMps2,
            )

    private fun isValidPersistedProfile(profile: PersistedPedestrianMotionProfile): Boolean =
        profile.stepLengthM.isFinite() &&
            profile.stepLengthM in config.minimumStepLengthM..config.maximumStepLengthM &&
            profile.meanWalkingSpeedMps.isFinite() &&
            profile.meanWalkingSpeedMps in config.minimumWalkingSpeedMps..config.maximumWalkingSpeedMps &&
            profile.speedVarianceMps2.isFinite() &&
            profile.speedVarianceMps2 in config.minimumSpeedVarianceMps2..config.maximumSpeedVarianceMps2 &&
            profile.acceptedSampleCount >= config.minimumAcceptedSamples

    private fun appendRecent(values: MutableList<Double>, value: Double) {
        if (values.size == config.recentSampleWindowSize) values.removeAt(0)
        values += value
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private fun saturatedIncrement(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1L

    private companion object {
        const val MILLIS_PER_SECOND = 1_000.0
        const val MAD_TO_SIGMA = 1.4826
    }
}

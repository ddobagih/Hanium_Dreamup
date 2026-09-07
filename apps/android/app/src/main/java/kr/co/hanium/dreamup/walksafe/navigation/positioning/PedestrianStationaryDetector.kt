package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kotlin.math.abs

data class PedestrianStationaryDetectorConfig(
    val expectedGravityMps2: Double = 9.80665,
    val maximumStationaryAccelerationMeanDeviationMps2: Double = 0.35,
    val maximumStationaryAccelerationVarianceMps4: Double = 0.08,
    val movingAccelerationMeanDeviationMps2: Double = 0.80,
    val movingAccelerationVarianceMps4: Double = 0.25,
    val maximumStationaryGyroscopeRmsRadPerSecond: Double = 0.08,
    val maximumStationaryGyroscopePeakRadPerSecond: Double = 0.18,
    val movingGyroscopeRmsRadPerSecond: Double = 0.20,
    val movingGyroscopePeakRadPerSecond: Double = 0.45,
    val stationaryDwellMs: Long = 1_500L,
    val missingGyroscopeDwellMs: Long = 3_000L,
) {
    init {
        require(expectedGravityMps2 > 0.0)
        require(maximumStationaryAccelerationMeanDeviationMps2 >= 0.0)
        require(maximumStationaryAccelerationVarianceMps4 >= 0.0)
        require(movingAccelerationMeanDeviationMps2 > maximumStationaryAccelerationMeanDeviationMps2)
        require(movingAccelerationVarianceMps4 > maximumStationaryAccelerationVarianceMps4)
        require(maximumStationaryGyroscopeRmsRadPerSecond >= 0.0)
        require(maximumStationaryGyroscopePeakRadPerSecond >= 0.0)
        require(movingGyroscopeRmsRadPerSecond > maximumStationaryGyroscopeRmsRadPerSecond)
        require(movingGyroscopePeakRadPerSecond > maximumStationaryGyroscopePeakRadPerSecond)
        require(stationaryDwellMs > 0L)
        require(missingGyroscopeDwellMs >= stationaryDwellMs)
    }
}

/** Motion statistics calculated over a window ending at [elapsedRealtimeMs]. */
data class PedestrianMotionSample(
    val elapsedRealtimeMs: Long,
    val stepDetected: Boolean = false,
    val accelerationMagnitudeMeanMps2: Double? = null,
    val accelerationMagnitudeVarianceMps4: Double? = null,
    val gyroscopeRmsRadPerSecond: Double? = null,
    val gyroscopePeakRadPerSecond: Double? = null,
)

enum class PedestrianStationaryState {
    MOVING,
    CANDIDATE,
    STATIONARY,
}

enum class PedestrianMotionSampleRejectionReason {
    INVALID_TIME,
    INVALID_MEASUREMENT,
    OUT_OF_ORDER,
}

data class PedestrianStationaryDecision(
    val state: PedestrianStationaryState,
    val previousState: PedestrianStationaryState,
    val accepted: Boolean,
    val candidateSinceElapsedRealtimeMs: Long? = null,
    val rejectionReason: PedestrianMotionSampleRejectionReason? = null,
) {
    val stateChanged: Boolean
        get() = state != previousState

    val shouldApplyZeroVelocityUpdate: Boolean
        get() = accepted && state == PedestrianStationaryState.STATIONARY
}

/**
 * Conservative stationary detector for pedestrian dead reckoning.
 *
 * No-step is never sufficient evidence. Quiet accelerometer statistics are
 * mandatory, and missing gyroscope data only permits entry after a longer dwell.
 */
class PedestrianStationaryDetector(
    private val config: PedestrianStationaryDetectorConfig = PedestrianStationaryDetectorConfig(),
) {
    private var state = PedestrianStationaryState.MOVING
    private var lastSampleAtMs: Long? = null
    private var candidateSinceMs: Long? = null

    fun reset() {
        state = PedestrianStationaryState.MOVING
        lastSampleAtMs = null
        candidateSinceMs = null
    }

    fun currentState(): PedestrianStationaryState = state

    fun observe(sample: PedestrianMotionSample): PedestrianStationaryDecision {
        val previousState = state
        val rejectionReason = validate(sample)
        if (rejectionReason != null) {
            return PedestrianStationaryDecision(
                state = state,
                previousState = previousState,
                accepted = false,
                candidateSinceElapsedRealtimeMs = candidateSinceMs,
                rejectionReason = rejectionReason,
            )
        }
        lastSampleAtMs = sample.elapsedRealtimeMs

        if (sample.stepDetected || hasMovementEvidence(sample)) {
            moveToMoving()
            return decision(previousState)
        }

        val requiredDwellMs = stillnessDwellMs(sample)
        if (requiredDwellMs == null) {
            moveToMoving()
            return decision(previousState)
        }

        when (state) {
            PedestrianStationaryState.MOVING -> {
                state = PedestrianStationaryState.CANDIDATE
                candidateSinceMs = sample.elapsedRealtimeMs
            }
            PedestrianStationaryState.CANDIDATE -> {
                val candidateStart = requireNotNull(candidateSinceMs)
                if (sample.elapsedRealtimeMs - candidateStart >= requiredDwellMs) {
                    state = PedestrianStationaryState.STATIONARY
                }
            }
            PedestrianStationaryState.STATIONARY -> Unit
        }
        return decision(previousState)
    }

    private fun stillnessDwellMs(sample: PedestrianMotionSample): Long? {
        val accelerationMean = sample.accelerationMagnitudeMeanMps2 ?: return null
        val accelerationVariance = sample.accelerationMagnitudeVarianceMps4 ?: return null
        val accelerationQuiet =
            abs(accelerationMean - config.expectedGravityMps2) <=
                config.maximumStationaryAccelerationMeanDeviationMps2 &&
                accelerationVariance <= config.maximumStationaryAccelerationVarianceMps4
        if (!accelerationQuiet) return null

        val gyroscopeValues = listOfNotNull(
            sample.gyroscopeRmsRadPerSecond,
            sample.gyroscopePeakRadPerSecond,
        )
        if (gyroscopeValues.isEmpty()) return config.missingGyroscopeDwellMs

        val gyroscopeQuiet =
            (sample.gyroscopeRmsRadPerSecond ?: 0.0) <=
                config.maximumStationaryGyroscopeRmsRadPerSecond &&
                (sample.gyroscopePeakRadPerSecond ?: 0.0) <=
                config.maximumStationaryGyroscopePeakRadPerSecond
        return if (gyroscopeQuiet) config.stationaryDwellMs else null
    }

    private fun hasMovementEvidence(sample: PedestrianMotionSample): Boolean {
        val meanDeviation = sample.accelerationMagnitudeMeanMps2?.let {
            abs(it - config.expectedGravityMps2)
        }
        return meanDeviation?.let { it >= config.movingAccelerationMeanDeviationMps2 } == true ||
            sample.accelerationMagnitudeVarianceMps4?.let {
                it >= config.movingAccelerationVarianceMps4
            } == true ||
            sample.gyroscopeRmsRadPerSecond?.let {
                it >= config.movingGyroscopeRmsRadPerSecond
            } == true ||
            sample.gyroscopePeakRadPerSecond?.let {
                it >= config.movingGyroscopePeakRadPerSecond
            } == true
    }

    private fun validate(sample: PedestrianMotionSample): PedestrianMotionSampleRejectionReason? {
        if (sample.elapsedRealtimeMs < 0L) {
            return PedestrianMotionSampleRejectionReason.INVALID_TIME
        }
        if (lastSampleAtMs?.let { sample.elapsedRealtimeMs <= it } == true) {
            return PedestrianMotionSampleRejectionReason.OUT_OF_ORDER
        }
        val measurements = listOfNotNull(
            sample.accelerationMagnitudeMeanMps2,
            sample.accelerationMagnitudeVarianceMps4,
            sample.gyroscopeRmsRadPerSecond,
            sample.gyroscopePeakRadPerSecond,
        )
        if (measurements.any { !it.isFinite() || it < 0.0 }) {
            return PedestrianMotionSampleRejectionReason.INVALID_MEASUREMENT
        }
        return null
    }

    private fun moveToMoving() {
        state = PedestrianStationaryState.MOVING
        candidateSinceMs = null
    }

    private fun decision(previousState: PedestrianStationaryState) = PedestrianStationaryDecision(
        state = state,
        previousState = previousState,
        accepted = true,
        candidateSinceElapsedRealtimeMs = candidateSinceMs,
    )
}

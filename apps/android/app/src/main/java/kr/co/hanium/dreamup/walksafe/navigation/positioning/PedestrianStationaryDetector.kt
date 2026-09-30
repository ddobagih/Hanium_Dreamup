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
    val maximumSampleGapMs: Long = 250L,
    val minimumWindowSamples: Int = 5,
    val minimumWindowDurationMs: Long = 200L,
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
        require(maximumSampleGapMs > 0L)
        require(minimumWindowSamples >= 2)
        require(minimumWindowDurationMs > 0L)
    }
}

/** Coverage is measured from actual samples, never from callback or evaluation time. */
data class PedestrianMotionWindowCoverage(
    val sampleCount: Int,
    val startedAtElapsedRealtimeMs: Long,
    val endedAtElapsedRealtimeMs: Long,
    val maximumGapMs: Long,
) {
    fun isSufficient(
        nowMs: Long,
        minimumSamples: Int,
        minimumDurationMs: Long,
        maximumAllowedGapMs: Long,
    ): Boolean = sampleCount >= minimumSamples && startedAtElapsedRealtimeMs >= 0L &&
        endedAtElapsedRealtimeMs >= startedAtElapsedRealtimeMs &&
        endedAtElapsedRealtimeMs - startedAtElapsedRealtimeMs >= minimumDurationMs &&
        maximumGapMs in 0L..maximumAllowedGapMs && nowMs >= endedAtElapsedRealtimeMs &&
        nowMs - endedAtElapsedRealtimeMs <= maximumAllowedGapMs
}

/** Motion statistics calculated over a window ending at [elapsedRealtimeMs]. */
data class PedestrianMotionSample(
    val elapsedRealtimeMs: Long,
    val stepDetected: Boolean = false,
    val accelerationMagnitudeMeanMps2: Double? = null,
    val accelerationMagnitudeVarianceMps4: Double? = null,
    val gyroscopeRmsRadPerSecond: Double? = null,
    val gyroscopePeakRadPerSecond: Double? = null,
    val accelerationWindow: PedestrianMotionWindowCoverage? = null,
    val gyroscopeWindow: PedestrianMotionWindowCoverage? = null,
)

enum class PedestrianStationaryState {
    UNKNOWN,
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
    val sample: PedestrianMotionSample? = null,
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
    private var state = PedestrianStationaryState.UNKNOWN
    private var lastSampleAtMs: Long? = null
    private var candidateSinceMs: Long? = null
    private var lastAccelerationAtMs: Long? = null

    fun reset() {
        state = PedestrianStationaryState.UNKNOWN
        lastSampleAtMs = null
        candidateSinceMs = null
        lastAccelerationAtMs = null
    }

    fun currentState(): PedestrianStationaryState = state

    fun expire(nowElapsedRealtimeMs: Long): PedestrianStationaryState {
        if (lastSampleAtMs?.let { nowElapsedRealtimeMs >= it &&
                nowElapsedRealtimeMs - it > config.maximumSampleGapMs } == true
        ) {
            state = PedestrianStationaryState.UNKNOWN
            candidateSinceMs = null
        }
        return state
    }

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
        if (lastSampleAtMs?.let { sample.elapsedRealtimeMs - it > config.maximumSampleGapMs } == true) {
            state = PedestrianStationaryState.UNKNOWN
            candidateSinceMs = null
        }
        lastSampleAtMs = sample.elapsedRealtimeMs

        if (sample.stepDetected || hasMovementEvidence(sample)) {
            moveToMoving()
            return decision(previousState, sample)
        }

        val requiredDwellMs = stillnessDwellMs(sample)
        if (requiredDwellMs == null) {
            state = PedestrianStationaryState.UNKNOWN
            candidateSinceMs = null
            return decision(previousState, sample)
        }

        val independentAt = requireNotNull(sample.accelerationWindow).endedAtElapsedRealtimeMs
        val previousAccelerationAt = lastAccelerationAtMs
        if (previousAccelerationAt != null && independentAt <= previousAccelerationAt) {
            return decision(previousState, sample)
        }
        if (previousAccelerationAt != null && independentAt - previousAccelerationAt > config.maximumSampleGapMs) {
            state = PedestrianStationaryState.UNKNOWN
            candidateSinceMs = null
        }
        lastAccelerationAtMs = independentAt

        when (state) {
            PedestrianStationaryState.UNKNOWN, PedestrianStationaryState.MOVING -> {
                state = PedestrianStationaryState.CANDIDATE
                candidateSinceMs = independentAt
            }
            PedestrianStationaryState.CANDIDATE -> {
                val candidateStart = requireNotNull(candidateSinceMs)
                if (independentAt - candidateStart >= requiredDwellMs) {
                    state = PedestrianStationaryState.STATIONARY
                }
            }
            PedestrianStationaryState.STATIONARY -> Unit
        }
        return decision(previousState, sample)
    }

    private fun stillnessDwellMs(sample: PedestrianMotionSample): Long? {
        if (!sufficientWindow(sample.accelerationWindow, sample.elapsedRealtimeMs)) return null
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
        if (!sufficientWindow(sample.gyroscopeWindow, sample.elapsedRealtimeMs)) return null

        val gyroscopeQuiet =
            (sample.gyroscopeRmsRadPerSecond ?: 0.0) <=
                config.maximumStationaryGyroscopeRmsRadPerSecond &&
                (sample.gyroscopePeakRadPerSecond ?: 0.0) <=
                config.maximumStationaryGyroscopePeakRadPerSecond
        return if (gyroscopeQuiet) config.stationaryDwellMs else null
    }

    private fun sufficientWindow(coverage: PedestrianMotionWindowCoverage?, nowMs: Long): Boolean =
        coverage?.isSufficient(
            nowMs,
            config.minimumWindowSamples,
            config.minimumWindowDurationMs,
            config.maximumSampleGapMs,
        ) == true

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

    private fun decision(
        previousState: PedestrianStationaryState,
        sample: PedestrianMotionSample,
    ) = PedestrianStationaryDecision(
        state = state,
        previousState = previousState,
        accepted = true,
        candidateSinceElapsedRealtimeMs = candidateSinceMs,
        sample = sample,
    )
}

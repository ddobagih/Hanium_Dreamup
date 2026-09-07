package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class GnssPositionObservation(
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyM: Double?,
    val elapsedRealtimeMs: Long,
    val receivedAtElapsedRealtimeMs: Long = elapsedRealtimeMs,
    val mock: Boolean = false,
)

enum class PdrStepQuality {
    HIGH,
    MEDIUM,
    LOW,
}

data class PdrStepObservation(
    val stepCount: Int,
    val stepLengthM: Double,
    val stepLengthSigmaM: Double = 0.08,
    val headingDegreesTrueNorth: Double?,
    val headingAccuracyDegrees: Double?,
    val quality: PdrStepQuality,
    val elapsedRealtimeMs: Long,
)

data class ZuptObservation(
    val elapsedRealtimeMs: Long,
    val velocitySigmaMps: Double = 0.15,
)

data class PedestrianDynamics(
    val processAccelerationSigmaMps2: Double,
    val averageWalkingSpeedMps: Double = 1.2,
)

enum class PositionQuality {
    HIGH,
    MEDIUM,
    LOW,
    UNAVAILABLE,
}

enum class GnssObservationDisposition {
    INITIALIZED,
    REINITIALIZED,
    APPLIED,
    DOWNWEIGHTED,
    HARD_REJECTED,
}

enum class GnssHardRejectReason {
    MOCK,
    INVALID_COORDINATE,
    INVALID_TIME,
    STALE,
    OUT_OF_ORDER,
    INVALID_ACCURACY,
    CORRUPT_JUMP,
    NUMERICAL_FAILURE,
}

enum class PedestrianMotionUpdateDisposition {
    APPLIED,
    COVARIANCE_ONLY,
    HARD_REJECTED,
}

enum class PedestrianMotionHardRejectReason {
    UNINITIALIZED,
    OUT_OF_ORDER,
    INVALID_STEP,
    INVALID_ZUPT,
    NUMERICAL_FAILURE,
}

data class EstimatedLocation(
    val latitude: Double,
    val longitude: Double,
    val horizontalUncertaintyM: Double,
    val velocityEastMps: Double,
    val velocityNorthMps: Double,
    val estimatedAtElapsedRealtimeMs: Long,
    val lastInformativeGnssAtElapsedRealtimeMs: Long,
    val quality: PositionQuality,
)

data class PedestrianPositionEstimatorUpdate(
    val estimate: EstimatedLocation?,
    val disposition: GnssObservationDisposition,
    val gateWeight: Double? = null,
    val hardRejectReason: GnssHardRejectReason? = null,
)

data class PedestrianMotionEstimatorUpdate(
    val estimate: EstimatedLocation?,
    val disposition: PedestrianMotionUpdateDisposition,
    val hardRejectReason: PedestrianMotionHardRejectReason? = null,
)

data class PedestrianPositionEstimatorConfig(
    val processAccelerationSigmaMps2: Double = 1.5,
    val initialVelocitySigmaMps: Double = 1.5,
    val minimumGnssSigmaM: Double = 1.5,
    val missingAccuracySigmaM: Double = 50.0,
    val maximumObservationAgeMs: Long = 10_000L,
    val maximumRetainedStateGapMs: Long = 30_000L,
    val maximumPredictStepSeconds: Double = 1.0,
    val softGateMahalanobisSquared: Double = 5.991,
    val informativeGateWeight: Double = 0.25,
    val corruptJumpMinimumDistanceM: Double = 500.0,
    val corruptJumpSpeedMps: Double = 80.0,
    val corruptJumpSigmaMultiplier: Double = 10.0,
    val highQualityMaximumUncertaintyM: Double = 2.0,
    val mediumQualityMaximumUncertaintyM: Double = 5.0,
    val lowQualityMaximumUncertaintyM: Double = 15.0,
    val maximumPdrHeadingAccuracyDegrees: Double = 45.0,
    val minimumDynamicProcessAccelerationSigmaMps2: Double = 0.05,
    val maximumDynamicProcessAccelerationSigmaMps2: Double = 10.0,
    val referenceWalkingSpeedMps: Double = 1.2,
    val minimumAverageWalkingSpeedMps: Double = 0.1,
    val maximumAverageWalkingSpeedMps: Double = 5.0,
    val minimumWalkingSpeedProcessNoiseScale: Double = 0.5,
    val maximumWalkingSpeedProcessNoiseScale: Double = 2.0,
) {
    init {
        require(processAccelerationSigmaMps2 > 0.0)
        require(initialVelocitySigmaMps > 0.0)
        require(minimumGnssSigmaM > 0.0)
        require(missingAccuracySigmaM >= minimumGnssSigmaM)
        require(maximumObservationAgeMs >= 0L)
        require(maximumRetainedStateGapMs > 0L)
        require(maximumPredictStepSeconds > 0.0)
        require(softGateMahalanobisSquared > 0.0)
        require(informativeGateWeight in 0.0..1.0)
        require(corruptJumpMinimumDistanceM > 0.0)
        require(corruptJumpSpeedMps > 0.0)
        require(corruptJumpSigmaMultiplier > 0.0)
        require(highQualityMaximumUncertaintyM > 0.0)
        require(mediumQualityMaximumUncertaintyM >= highQualityMaximumUncertaintyM)
        require(lowQualityMaximumUncertaintyM >= mediumQualityMaximumUncertaintyM)
        require(maximumPdrHeadingAccuracyDegrees > 0.0)
        require(minimumDynamicProcessAccelerationSigmaMps2 > 0.0)
        require(
            maximumDynamicProcessAccelerationSigmaMps2 >=
                minimumDynamicProcessAccelerationSigmaMps2,
        )
        require(referenceWalkingSpeedMps > 0.0)
        require(minimumAverageWalkingSpeedMps > 0.0)
        require(maximumAverageWalkingSpeedMps >= minimumAverageWalkingSpeedMps)
        require(minimumWalkingSpeedProcessNoiseScale > 0.0)
        require(maximumWalkingSpeedProcessNoiseScale >= minimumWalkingSpeedProcessNoiseScale)
    }
}

/**
 * Pure Kotlin pedestrian position estimator in a local East-North frame.
 *
 * The state is [east, north, velocityEast, velocityNorth]. Raw accelerometer
 * integration is deliberately excluded; later PDR updates can be added as a
 * separate velocity-observation boundary without changing the state model.
 */
class PedestrianPositionEstimator(
    private val config: PedestrianPositionEstimatorConfig = PedestrianPositionEstimatorConfig(),
) {
    private var tangentPlane: LocalTangentPlane? = null
    private var state: DoubleArray? = null
    private var covariance: DoubleArray? = null
    private var stateElapsedRealtimeMs: Long? = null
    private var lastInformativeGnssElapsedRealtimeMs: Long? = null
    private var lastPdrStepElapsedRealtimeMs: Long? = null
    private var lastZuptElapsedRealtimeMs: Long? = null
    private var activeProcessAccelerationSigmaMps2 = config.processAccelerationSigmaMps2
    private var activeAverageWalkingSpeedMps = config.referenceWalkingSpeedMps

    fun reset() {
        tangentPlane = null
        state = null
        covariance = null
        stateElapsedRealtimeMs = null
        lastInformativeGnssElapsedRealtimeMs = null
        lastPdrStepElapsedRealtimeMs = null
        lastZuptElapsedRealtimeMs = null
    }

    /** Updates the walking process noise without resetting the current position state. */
    fun setPedestrianDynamics(dynamics: PedestrianDynamics): Boolean {
        val sigma = dynamics.processAccelerationSigmaMps2
        if (
            !sigma.isFinite() ||
            sigma !in config.minimumDynamicProcessAccelerationSigmaMps2..
                config.maximumDynamicProcessAccelerationSigmaMps2
        ) return false
        val averageSpeed = dynamics.averageWalkingSpeedMps
        if (
            !averageSpeed.isFinite() ||
            averageSpeed !in config.minimumAverageWalkingSpeedMps..
                config.maximumAverageWalkingSpeedMps
        ) return false
        activeProcessAccelerationSigmaMps2 = sigma
        activeAverageWalkingSpeedMps = averageSpeed
        return true
    }

    fun observeGnss(observation: GnssPositionObservation): PedestrianPositionEstimatorUpdate {
        validateObservation(observation)?.let { reason ->
            return rejected(reason)
        }

        val currentTime = stateElapsedRealtimeMs
        if (currentTime != null && observation.elapsedRealtimeMs <= currentTime) {
            return rejected(GnssHardRejectReason.OUT_OF_ORDER)
        }

        val gnssSigmaM = max(
            observation.horizontalAccuracyM ?: config.missingAccuracySigmaM,
            config.minimumGnssSigmaM,
        )
        if (state == null || currentTime == null) {
            return initialize(observation, gnssSigmaM, GnssObservationDisposition.INITIALIZED)
        }

        val elapsedMs = observation.elapsedRealtimeMs - currentTime
        if (elapsedMs > config.maximumRetainedStateGapMs) {
            reset()
            return initialize(observation, gnssSigmaM, GnssObservationDisposition.REINITIALIZED)
        }

        val predicted = predict(
            requireNotNull(state),
            requireNotNull(covariance),
            elapsedMs / MILLIS_PER_SECOND,
        ) ?: return rejected(GnssHardRejectReason.NUMERICAL_FAILURE)
        val plane = requireNotNull(tangentPlane)
        val observedLocal = plane.toLocal(observation.latitude, observation.longitude)
        val innovationEast = observedLocal.eastM - predicted.state[EAST]
        val innovationNorth = observedLocal.northM - predicted.state[NORTH]
        val innovationDistanceM = sqrt(
            innovationEast * innovationEast + innovationNorth * innovationNorth,
        )
        val predictedUncertaintyM = horizontalUncertainty(predicted.covariance)
        val corruptDistanceBoundaryM = max(
            config.corruptJumpMinimumDistanceM,
            config.corruptJumpSpeedMps * (elapsedMs / MILLIS_PER_SECOND),
        )
        if (
            innovationDistanceM > corruptDistanceBoundaryM &&
            innovationDistanceM >
            config.corruptJumpSigmaMultiplier * (predictedUncertaintyM + gnssSigmaM)
        ) {
            return rejected(GnssHardRejectReason.CORRUPT_JUMP)
        }

        val nominalInnovation = innovationStatistics(
            predicted.covariance,
            innovationEast,
            innovationNorth,
            gnssSigmaM * gnssSigmaM,
        ) ?: return rejected(GnssHardRejectReason.NUMERICAL_FAILURE)
        val gateWeight = if (nominalInnovation.mahalanobisSquared <= config.softGateMahalanobisSquared) {
            1.0
        } else {
            sqrt(config.softGateMahalanobisSquared / nominalInnovation.mahalanobisSquared)
                .coerceIn(MINIMUM_GATE_WEIGHT, 1.0)
        }
        val effectiveMeasurementVariance =
            gnssSigmaM * gnssSigmaM / (gateWeight * gateWeight)
        val corrected = correctPosition(
            predicted.state,
            predicted.covariance,
            innovationEast,
            innovationNorth,
            effectiveMeasurementVariance,
        ) ?: return rejected(GnssHardRejectReason.NUMERICAL_FAILURE)

        state = corrected.state
        covariance = corrected.covariance
        stateElapsedRealtimeMs = observation.elapsedRealtimeMs
        if (gateWeight >= config.informativeGateWeight) {
            lastInformativeGnssElapsedRealtimeMs = observation.elapsedRealtimeMs
        }
        return PedestrianPositionEstimatorUpdate(
            estimate = buildEstimate(
                corrected.state,
                corrected.covariance,
                observation.elapsedRealtimeMs,
            ),
            disposition = if (gateWeight < 1.0) {
                GnssObservationDisposition.DOWNWEIGHTED
            } else {
                GnssObservationDisposition.APPLIED
            },
            gateWeight = gateWeight,
        )
    }

    /** Returns a non-mutating prediction so UI reads cannot make later GNSS fixes out of order. */
    fun estimateAt(elapsedRealtimeMs: Long): EstimatedLocation? {
        val currentTime = stateElapsedRealtimeMs ?: return null
        if (elapsedRealtimeMs < currentTime) return null
        val elapsedMs = elapsedRealtimeMs - currentTime
        if (elapsedMs > config.maximumRetainedStateGapMs) {
            return buildEstimate(
                requireNotNull(state),
                requireNotNull(covariance),
                currentTime,
                forceUnavailable = true,
            )
        }
        val predicted = predict(
            requireNotNull(state),
            requireNotNull(covariance),
            elapsedMs / MILLIS_PER_SECOND,
        ) ?: return null
        return buildEstimate(predicted.state, predicted.covariance, elapsedRealtimeMs)
    }

    /**
     * Applies step displacement only when both step and true-north heading evidence are usable.
     * Missing/low-quality direction evidence leaves the mean untouched and increases covariance.
     */
    fun observePdrStep(observation: PdrStepObservation): PedestrianMotionEstimatorUpdate {
        val currentTime = stateElapsedRealtimeMs
            ?: return motionRejected(PedestrianMotionHardRejectReason.UNINITIALIZED)
        if (
            observation.elapsedRealtimeMs < currentTime ||
            lastPdrStepElapsedRealtimeMs?.let { observation.elapsedRealtimeMs <= it } == true
        ) return motionRejected(PedestrianMotionHardRejectReason.OUT_OF_ORDER)
        if (
            observation.stepCount <= 0 ||
            !observation.stepLengthM.isFinite() || observation.stepLengthM <= 0.0 ||
            !observation.stepLengthSigmaM.isFinite() || observation.stepLengthSigmaM < 0.0
        ) return motionRejected(PedestrianMotionHardRejectReason.INVALID_STEP)

        val predicted = predictMotionControlledInterval(
            requireNotNull(state),
            requireNotNull(covariance),
            (observation.elapsedRealtimeMs - currentTime) / MILLIS_PER_SECOND,
        ) ?: return motionRejected(PedestrianMotionHardRejectReason.NUMERICAL_FAILURE)
        val headingDegrees = observation.headingDegreesTrueNorth
        val headingAccuracyDegrees = observation.headingAccuracyDegrees
        val directionUsable = observation.quality != PdrStepQuality.LOW &&
            headingDegrees != null && headingDegrees.isFinite() &&
            headingAccuracyDegrees != null && headingAccuracyDegrees.isFinite() &&
            headingAccuracyDegrees in 0.0..config.maximumPdrHeadingAccuracyDegrees
        val distanceM = observation.stepCount * observation.stepLengthM
        val updated = if (directionUsable) {
            applyDirectedStep(
                predicted,
                distanceM,
                observation.stepCount,
                observation.stepLengthSigmaM,
                requireNotNull(headingDegrees),
                requireNotNull(headingAccuracyDegrees),
                observation.quality,
            )
        } else {
            applyUnresolvedStep(predicted, distanceM, observation.stepCount, observation.stepLengthSigmaM)
        }
        if (!updated.isFinite()) {
            return motionRejected(PedestrianMotionHardRejectReason.NUMERICAL_FAILURE)
        }
        state = updated.state
        covariance = updated.covariance
        stateElapsedRealtimeMs = observation.elapsedRealtimeMs
        lastPdrStepElapsedRealtimeMs = observation.elapsedRealtimeMs
        return PedestrianMotionEstimatorUpdate(
            estimate = buildEstimate(updated.state, updated.covariance, observation.elapsedRealtimeMs),
            disposition = if (directionUsable) {
                PedestrianMotionUpdateDisposition.APPLIED
            } else {
                PedestrianMotionUpdateDisposition.COVARIANCE_ONLY
            },
        )
    }

    /** Applies a zero-velocity observation without directly changing position coordinates. */
    fun observeZupt(observation: ZuptObservation): PedestrianMotionEstimatorUpdate {
        val currentTime = stateElapsedRealtimeMs
            ?: return motionRejected(PedestrianMotionHardRejectReason.UNINITIALIZED)
        if (
            observation.elapsedRealtimeMs < currentTime ||
            lastZuptElapsedRealtimeMs?.let { observation.elapsedRealtimeMs <= it } == true
        ) return motionRejected(PedestrianMotionHardRejectReason.OUT_OF_ORDER)
        if (!observation.velocitySigmaMps.isFinite() || observation.velocitySigmaMps <= 0.0) {
            return motionRejected(PedestrianMotionHardRejectReason.INVALID_ZUPT)
        }
        val predicted = predictMotionControlledInterval(
            requireNotNull(state),
            requireNotNull(covariance),
            (observation.elapsedRealtimeMs - currentTime) / MILLIS_PER_SECOND,
        ) ?: return motionRejected(PedestrianMotionHardRejectReason.NUMERICAL_FAILURE)
        val corrected = correctVelocityOnly(
            predicted.state,
            predicted.covariance,
            -predicted.state[VELOCITY_EAST],
            -predicted.state[VELOCITY_NORTH],
            observation.velocitySigmaMps * observation.velocitySigmaMps,
        ) ?: return motionRejected(PedestrianMotionHardRejectReason.NUMERICAL_FAILURE)
        state = corrected.state
        covariance = corrected.covariance
        stateElapsedRealtimeMs = observation.elapsedRealtimeMs
        lastZuptElapsedRealtimeMs = observation.elapsedRealtimeMs
        return PedestrianMotionEstimatorUpdate(
            estimate = buildEstimate(corrected.state, corrected.covariance, observation.elapsedRealtimeMs),
            disposition = PedestrianMotionUpdateDisposition.APPLIED,
        )
    }

    internal fun covarianceSnapshot(): DoubleArray = covariance?.copyOf() ?: DoubleArray(0)

    private fun motionRejected(reason: PedestrianMotionHardRejectReason) =
        PedestrianMotionEstimatorUpdate(
            estimate = stateElapsedRealtimeMs?.let { time ->
                buildEstimate(requireNotNull(state), requireNotNull(covariance), time)
            },
            disposition = PedestrianMotionUpdateDisposition.HARD_REJECTED,
            hardRejectReason = reason,
        )

    private fun initialize(
        observation: GnssPositionObservation,
        gnssSigmaM: Double,
        disposition: GnssObservationDisposition,
    ): PedestrianPositionEstimatorUpdate {
        tangentPlane = LocalTangentPlane(observation.latitude, observation.longitude)
        state = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        covariance = DoubleArray(MATRIX_SIZE).also { initial ->
            initial[index(EAST, EAST)] = gnssSigmaM * gnssSigmaM
            initial[index(NORTH, NORTH)] = gnssSigmaM * gnssSigmaM
            val velocityVariance = config.initialVelocitySigmaMps * config.initialVelocitySigmaMps
            initial[index(VELOCITY_EAST, VELOCITY_EAST)] = velocityVariance
            initial[index(VELOCITY_NORTH, VELOCITY_NORTH)] = velocityVariance
        }
        stateElapsedRealtimeMs = observation.elapsedRealtimeMs
        lastInformativeGnssElapsedRealtimeMs = observation.elapsedRealtimeMs
        return PedestrianPositionEstimatorUpdate(
            estimate = buildEstimate(
                requireNotNull(state),
                requireNotNull(covariance),
                observation.elapsedRealtimeMs,
            ),
            disposition = disposition,
            gateWeight = 1.0,
        )
    }

    private fun validateObservation(observation: GnssPositionObservation): GnssHardRejectReason? {
        if (observation.mock) return GnssHardRejectReason.MOCK
        if (
            !observation.latitude.isFinite() ||
            observation.latitude <= -90.0 || observation.latitude >= 90.0 ||
            !observation.longitude.isFinite() || observation.longitude !in -180.0..180.0
        ) return GnssHardRejectReason.INVALID_COORDINATE
        if (observation.elapsedRealtimeMs < 0L || observation.receivedAtElapsedRealtimeMs < 0L) {
            return GnssHardRejectReason.INVALID_TIME
        }
        val ageMs = observation.receivedAtElapsedRealtimeMs - observation.elapsedRealtimeMs
        if (ageMs < 0L) return GnssHardRejectReason.INVALID_TIME
        if (ageMs > config.maximumObservationAgeMs) return GnssHardRejectReason.STALE
        observation.horizontalAccuracyM?.let { accuracy ->
            if (!accuracy.isFinite() || accuracy < 0.0) {
                return GnssHardRejectReason.INVALID_ACCURACY
            }
        }
        return null
    }

    private fun rejected(reason: GnssHardRejectReason) = PedestrianPositionEstimatorUpdate(
        estimate = stateElapsedRealtimeMs?.let { time ->
            buildEstimate(requireNotNull(state), requireNotNull(covariance), time)
        },
        disposition = GnssObservationDisposition.HARD_REJECTED,
        hardRejectReason = reason,
    )

    private fun predict(
        initialState: DoubleArray,
        initialCovariance: DoubleArray,
        elapsedSeconds: Double,
    ): FilterState? {
        if (!elapsedSeconds.isFinite() || elapsedSeconds < 0.0) return null
        val predictedState = initialState.copyOf()
        var predictedCovariance = initialCovariance.copyOf()
        var remainingSeconds = elapsedSeconds
        while (remainingSeconds > NUMERICAL_EPSILON) {
            val dt = min(remainingSeconds, config.maximumPredictStepSeconds)
            predictedState[EAST] += predictedState[VELOCITY_EAST] * dt
            predictedState[NORTH] += predictedState[VELOCITY_NORTH] * dt
            predictedCovariance = predictCovariance(predictedCovariance, dt)
            remainingSeconds -= dt
        }
        return FilterState(predictedState, predictedCovariance)
            .takeIf { it.isFinite() }
    }

    /**
     * Advances a PDR/ZUPT interval without applying the constant-velocity position mean.
     * Step displacement or the stationary constraint is authoritative for these intervals.
     */
    private fun predictMotionControlledInterval(
        initialState: DoubleArray,
        initialCovariance: DoubleArray,
        elapsedSeconds: Double,
    ): FilterState? {
        if (!elapsedSeconds.isFinite() || elapsedSeconds < 0.0) return null
        val predictedState = initialState.copyOf()
        var predictedCovariance = initialCovariance.copyOf()
        var remainingSeconds = elapsedSeconds
        while (remainingSeconds > NUMERICAL_EPSILON) {
            val dt = min(remainingSeconds, config.maximumPredictStepSeconds)
            predictedCovariance = predictMotionControlledCovariance(predictedCovariance, dt)
            remainingSeconds -= dt
        }
        return FilterState(predictedState, predictedCovariance)
            .takeIf { it.isFinite() }
    }

    private fun predictCovariance(previous: DoubleArray, dt: Double): DoubleArray {
        val transition = identityMatrix().also { matrix ->
            matrix[index(EAST, VELOCITY_EAST)] = dt
            matrix[index(NORTH, VELOCITY_NORTH)] = dt
        }
        val predicted = multiply(multiply(transition, previous), transpose(transition))
        val accelerationVariance = activeProcessAccelerationVariance()
        val dt2 = dt * dt
        val dt3 = dt2 * dt
        val dt4 = dt2 * dt2
        addAxisProcessNoise(predicted, EAST, VELOCITY_EAST, accelerationVariance, dt2, dt3, dt4)
        addAxisProcessNoise(predicted, NORTH, VELOCITY_NORTH, accelerationVariance, dt2, dt3, dt4)
        return stabilizeCovariance(predicted)
    }

    private fun predictMotionControlledCovariance(previous: DoubleArray, dt: Double): DoubleArray {
        val predicted = previous.copyOf()
        val accelerationVariance = activeProcessAccelerationVariance()
        val dt2 = dt * dt
        val dt3 = dt2 * dt
        val dt4 = dt2 * dt2
        addAxisProcessNoise(predicted, EAST, VELOCITY_EAST, accelerationVariance, dt2, dt3, dt4)
        addAxisProcessNoise(predicted, NORTH, VELOCITY_NORTH, accelerationVariance, dt2, dt3, dt4)
        return stabilizeCovariance(predicted)
    }

    private fun activeProcessAccelerationVariance(): Double {
        val walkingSpeedScale = (activeAverageWalkingSpeedMps / config.referenceWalkingSpeedMps)
            .coerceIn(
                config.minimumWalkingSpeedProcessNoiseScale,
                config.maximumWalkingSpeedProcessNoiseScale,
            )
        val effectiveSigma = activeProcessAccelerationSigmaMps2 * sqrt(walkingSpeedScale)
        return effectiveSigma * effectiveSigma
    }

    private fun applyDirectedStep(
        predicted: FilterState,
        distanceM: Double,
        stepCount: Int,
        stepLengthSigmaM: Double,
        headingDegreesTrueNorth: Double,
        headingAccuracyDegrees: Double,
        quality: PdrStepQuality,
    ): FilterState {
        val headingRad = Math.toRadians(normalizeDegrees(headingDegreesTrueNorth))
        val headingSigmaRad = Math.toRadians(headingAccuracyDegrees) * when (quality) {
            PdrStepQuality.HIGH -> 1.0
            PdrStepQuality.MEDIUM -> 1.5
            PdrStepQuality.LOW -> error("Low-quality steps must not reach directed updates")
        }
        val sinHeading = sin(headingRad)
        val cosHeading = cos(headingRad)
        val updatedState = predicted.state.copyOf()
        updatedState[EAST] += distanceM * sinHeading
        updatedState[NORTH] += distanceM * cosHeading

        val distanceVariance = stepCount * stepLengthSigmaM * stepLengthSigmaM
        val headingVariance = headingSigmaRad * headingSigmaRad
        val eastHeadingDerivative = distanceM * cosHeading
        val northHeadingDerivative = -distanceM * sinHeading
        val updatedCovariance = predicted.covariance.copyOf()
        updatedCovariance[index(EAST, EAST)] +=
            sinHeading * sinHeading * distanceVariance +
                eastHeadingDerivative * eastHeadingDerivative * headingVariance
        updatedCovariance[index(NORTH, NORTH)] +=
            cosHeading * cosHeading * distanceVariance +
                northHeadingDerivative * northHeadingDerivative * headingVariance
        val eastNorthCovariance =
            sinHeading * cosHeading * distanceVariance +
                eastHeadingDerivative * northHeadingDerivative * headingVariance
        updatedCovariance[index(EAST, NORTH)] += eastNorthCovariance
        updatedCovariance[index(NORTH, EAST)] += eastNorthCovariance
        return FilterState(updatedState, stabilizeCovariance(updatedCovariance))
    }

    private fun applyUnresolvedStep(
        predicted: FilterState,
        distanceM: Double,
        stepCount: Int,
        stepLengthSigmaM: Double,
    ): FilterState {
        val updatedCovariance = predicted.covariance.copyOf()
        val distanceVariance = stepCount * stepLengthSigmaM * stepLengthSigmaM
        val unknownDirectionVariance = 0.5 * distanceM * distanceM + distanceVariance
        updatedCovariance[index(EAST, EAST)] += unknownDirectionVariance
        updatedCovariance[index(NORTH, NORTH)] += unknownDirectionVariance
        return FilterState(predicted.state.copyOf(), stabilizeCovariance(updatedCovariance))
    }

    private fun correctVelocityOnly(
        predictedState: DoubleArray,
        predictedCovariance: DoubleArray,
        innovationEast: Double,
        innovationNorth: Double,
        measurementVariance: Double,
    ): FilterState? {
        val s00 = predictedCovariance[index(VELOCITY_EAST, VELOCITY_EAST)] + measurementVariance
        val s01 = predictedCovariance[index(VELOCITY_EAST, VELOCITY_NORTH)]
        val s10 = predictedCovariance[index(VELOCITY_NORTH, VELOCITY_EAST)]
        val s11 = predictedCovariance[index(VELOCITY_NORTH, VELOCITY_NORTH)] + measurementVariance
        val inverse = invert2x2(s00, s01, s10, s11) ?: return null
        val gain = DoubleArray(STATE_SIZE * MEASUREMENT_SIZE)
        for (row in VELOCITY_EAST..VELOCITY_NORTH) {
            val p0 = predictedCovariance[index(row, VELOCITY_EAST)]
            val p1 = predictedCovariance[index(row, VELOCITY_NORTH)]
            gain[row * MEASUREMENT_SIZE] = p0 * inverse[0] + p1 * inverse[2]
            gain[row * MEASUREMENT_SIZE + 1] = p0 * inverse[1] + p1 * inverse[3]
        }
        val correctedState = predictedState.copyOf()
        for (row in VELOCITY_EAST..VELOCITY_NORTH) {
            correctedState[row] +=
                gain[row * MEASUREMENT_SIZE] * innovationEast +
                    gain[row * MEASUREMENT_SIZE + 1] * innovationNorth
        }

        val identityMinusKh = identityMatrix()
        for (row in 0 until STATE_SIZE) {
            identityMinusKh[index(row, VELOCITY_EAST)] -= gain[row * MEASUREMENT_SIZE]
            identityMinusKh[index(row, VELOCITY_NORTH)] -=
                gain[row * MEASUREMENT_SIZE + 1]
        }
        val correctedCovariance = multiply(
            multiply(identityMinusKh, predictedCovariance),
            transpose(identityMinusKh),
        )
        for (row in 0 until STATE_SIZE) {
            for (column in 0 until STATE_SIZE) {
                correctedCovariance[index(row, column)] += measurementVariance * (
                    gain[row * MEASUREMENT_SIZE] * gain[column * MEASUREMENT_SIZE] +
                        gain[row * MEASUREMENT_SIZE + 1] * gain[column * MEASUREMENT_SIZE + 1]
                    )
            }
        }
        return FilterState(correctedState, stabilizeCovariance(correctedCovariance))
            .takeIf { it.isFinite() }
    }

    private fun normalizeDegrees(degrees: Double): Double {
        val normalized = degrees % FULL_CIRCLE_DEGREES
        return if (normalized < 0.0) normalized + FULL_CIRCLE_DEGREES else normalized
    }

    private fun addAxisProcessNoise(
        matrix: DoubleArray,
        positionIndex: Int,
        velocityIndex: Int,
        accelerationVariance: Double,
        dt2: Double,
        dt3: Double,
        dt4: Double,
    ) {
        matrix[index(positionIndex, positionIndex)] += 0.25 * dt4 * accelerationVariance
        matrix[index(positionIndex, velocityIndex)] += 0.5 * dt3 * accelerationVariance
        matrix[index(velocityIndex, positionIndex)] += 0.5 * dt3 * accelerationVariance
        matrix[index(velocityIndex, velocityIndex)] += dt2 * accelerationVariance
    }

    private fun innovationStatistics(
        predictedCovariance: DoubleArray,
        innovationEast: Double,
        innovationNorth: Double,
        measurementVariance: Double,
    ): InnovationStatistics? {
        val s00 = predictedCovariance[index(EAST, EAST)] + measurementVariance
        val s01 = predictedCovariance[index(EAST, NORTH)]
        val s10 = predictedCovariance[index(NORTH, EAST)]
        val s11 = predictedCovariance[index(NORTH, NORTH)] + measurementVariance
        val inverse = invert2x2(s00, s01, s10, s11) ?: return null
        val mahalanobisSquared =
            innovationEast * (inverse[0] * innovationEast + inverse[1] * innovationNorth) +
                innovationNorth * (inverse[2] * innovationEast + inverse[3] * innovationNorth)
        if (!mahalanobisSquared.isFinite() || mahalanobisSquared < 0.0) return null
        return InnovationStatistics(mahalanobisSquared)
    }

    private fun correctPosition(
        predictedState: DoubleArray,
        predictedCovariance: DoubleArray,
        innovationEast: Double,
        innovationNorth: Double,
        measurementVariance: Double,
    ): FilterState? {
        val s00 = predictedCovariance[index(EAST, EAST)] + measurementVariance
        val s01 = predictedCovariance[index(EAST, NORTH)]
        val s10 = predictedCovariance[index(NORTH, EAST)]
        val s11 = predictedCovariance[index(NORTH, NORTH)] + measurementVariance
        val inverse = invert2x2(s00, s01, s10, s11) ?: return null
        val gain = DoubleArray(STATE_SIZE * MEASUREMENT_SIZE)
        for (row in 0 until STATE_SIZE) {
            val p0 = predictedCovariance[index(row, EAST)]
            val p1 = predictedCovariance[index(row, NORTH)]
            gain[row * MEASUREMENT_SIZE] = p0 * inverse[0] + p1 * inverse[2]
            gain[row * MEASUREMENT_SIZE + 1] = p0 * inverse[1] + p1 * inverse[3]
        }

        val correctedState = predictedState.copyOf()
        for (row in 0 until STATE_SIZE) {
            correctedState[row] +=
                gain[row * MEASUREMENT_SIZE] * innovationEast +
                gain[row * MEASUREMENT_SIZE + 1] * innovationNorth
        }

        val identityMinusKh = identityMatrix()
        for (row in 0 until STATE_SIZE) {
            identityMinusKh[index(row, EAST)] -= gain[row * MEASUREMENT_SIZE]
            identityMinusKh[index(row, NORTH)] -= gain[row * MEASUREMENT_SIZE + 1]
        }
        val josephLeft = multiply(
            multiply(identityMinusKh, predictedCovariance),
            transpose(identityMinusKh),
        )
        val correctedCovariance = josephLeft.copyOf()
        for (row in 0 until STATE_SIZE) {
            for (column in 0 until STATE_SIZE) {
                val measurementTerm = measurementVariance * (
                    gain[row * MEASUREMENT_SIZE] * gain[column * MEASUREMENT_SIZE] +
                        gain[row * MEASUREMENT_SIZE + 1] * gain[column * MEASUREMENT_SIZE + 1]
                    )
                correctedCovariance[index(row, column)] += measurementTerm
            }
        }
        val stabilized = stabilizeCovariance(correctedCovariance)
        return FilterState(correctedState, stabilized).takeIf { it.isFinite() }
    }

    private fun buildEstimate(
        state: DoubleArray,
        covariance: DoubleArray,
        estimatedAtElapsedRealtimeMs: Long,
        forceUnavailable: Boolean = false,
    ): EstimatedLocation {
        val point = requireNotNull(tangentPlane).toGeodetic(state[EAST], state[NORTH])
        val uncertaintyM = horizontalUncertainty(covariance)
        val lastInformativeTime = requireNotNull(lastInformativeGnssElapsedRealtimeMs)
        val ageMs = estimatedAtElapsedRealtimeMs - lastInformativeTime
        val quality = if (forceUnavailable || ageMs !in 0L..config.maximumObservationAgeMs) {
            PositionQuality.UNAVAILABLE
        } else {
            when {
                uncertaintyM <= config.highQualityMaximumUncertaintyM -> PositionQuality.HIGH
                uncertaintyM <= config.mediumQualityMaximumUncertaintyM -> PositionQuality.MEDIUM
                uncertaintyM <= config.lowQualityMaximumUncertaintyM -> PositionQuality.LOW
                else -> PositionQuality.UNAVAILABLE
            }
        }
        return EstimatedLocation(
            latitude = point.latitude,
            longitude = point.longitude,
            horizontalUncertaintyM = uncertaintyM,
            velocityEastMps = state[VELOCITY_EAST],
            velocityNorthMps = state[VELOCITY_NORTH],
            estimatedAtElapsedRealtimeMs = estimatedAtElapsedRealtimeMs,
            lastInformativeGnssAtElapsedRealtimeMs = lastInformativeTime,
            quality = quality,
        )
    }

    private fun horizontalUncertainty(covariance: DoubleArray): Double {
        val eastVariance = covariance[index(EAST, EAST)]
        val northVariance = covariance[index(NORTH, NORTH)]
        val covarianceEastNorth = 0.5 * (
            covariance[index(EAST, NORTH)] + covariance[index(NORTH, EAST)]
            )
        val halfTrace = 0.5 * (eastVariance + northVariance)
        val discriminant =
            0.25 * (eastVariance - northVariance) * (eastVariance - northVariance) +
                covarianceEastNorth * covarianceEastNorth
        return sqrt(max(halfTrace + sqrt(max(0.0, discriminant)), MINIMUM_VARIANCE))
    }

    private fun invert2x2(a: Double, b: Double, c: Double, d: Double): DoubleArray? {
        val determinant = a * d - b * c
        if (!determinant.isFinite() || determinant <= NUMERICAL_EPSILON) return null
        return doubleArrayOf(d / determinant, -b / determinant, -c / determinant, a / determinant)
            .takeIf { values -> values.all(Double::isFinite) }
    }

    private fun stabilizeCovariance(matrix: DoubleArray): DoubleArray {
        for (row in 0 until STATE_SIZE) {
            for (column in row + 1 until STATE_SIZE) {
                val symmetric = 0.5 * (matrix[index(row, column)] + matrix[index(column, row)])
                matrix[index(row, column)] = symmetric
                matrix[index(column, row)] = symmetric
            }
            matrix[index(row, row)] = max(matrix[index(row, row)], MINIMUM_VARIANCE)
        }
        return matrix
    }

    private fun multiply(left: DoubleArray, right: DoubleArray): DoubleArray {
        val result = DoubleArray(MATRIX_SIZE)
        for (row in 0 until STATE_SIZE) {
            for (column in 0 until STATE_SIZE) {
                var value = 0.0
                for (inner in 0 until STATE_SIZE) {
                    value += left[index(row, inner)] * right[index(inner, column)]
                }
                result[index(row, column)] = value
            }
        }
        return result
    }

    private fun transpose(matrix: DoubleArray): DoubleArray = DoubleArray(MATRIX_SIZE) { offset ->
        val row = offset / STATE_SIZE
        val column = offset % STATE_SIZE
        matrix[index(column, row)]
    }

    private fun identityMatrix(): DoubleArray = DoubleArray(MATRIX_SIZE).also { matrix ->
        for (axis in 0 until STATE_SIZE) matrix[index(axis, axis)] = 1.0
    }

    private data class InnovationStatistics(val mahalanobisSquared: Double)

    private data class FilterState(
        val state: DoubleArray,
        val covariance: DoubleArray,
    ) {
        fun isFinite(): Boolean = state.all(Double::isFinite) && covariance.all(Double::isFinite)
    }

    private data class LocalPoint(val eastM: Double, val northM: Double)

    private data class GeodeticPoint(val latitude: Double, val longitude: Double)

    private class LocalTangentPlane(latitude: Double, longitude: Double) {
        private val originLatitudeRad = Math.toRadians(latitude)
        private val originLongitudeRad = Math.toRadians(longitude)
        private val longitudeScale = EARTH_RADIUS_M * cos(originLatitudeRad)

        fun toLocal(latitude: Double, longitude: Double): LocalPoint {
            val latitudeRad = Math.toRadians(latitude)
            val longitudeRad = Math.toRadians(longitude)
            val longitudeDelta = normalizeRadians(longitudeRad - originLongitudeRad)
            return LocalPoint(
                eastM = longitudeScale * longitudeDelta,
                northM = EARTH_RADIUS_M * (latitudeRad - originLatitudeRad),
            )
        }

        fun toGeodetic(eastM: Double, northM: Double): GeodeticPoint {
            val latitude = Math.toDegrees(originLatitudeRad + northM / EARTH_RADIUS_M)
            val longitude = Math.toDegrees(
                normalizeRadians(originLongitudeRad + eastM / longitudeScale),
            )
            return GeodeticPoint(latitude, longitude)
        }

        private fun normalizeRadians(value: Double): Double {
            var normalized = value
            while (normalized > PI) normalized -= TWO_PI
            while (normalized < -PI) normalized += TWO_PI
            return normalized
        }
    }

    private companion object {
        const val STATE_SIZE = 4
        const val MEASUREMENT_SIZE = 2
        const val MATRIX_SIZE = STATE_SIZE * STATE_SIZE
        const val EAST = 0
        const val NORTH = 1
        const val VELOCITY_EAST = 2
        const val VELOCITY_NORTH = 3
        const val EARTH_RADIUS_M = 6_371_000.0
        const val MILLIS_PER_SECOND = 1_000.0
        const val TWO_PI = 2.0 * PI
        const val NUMERICAL_EPSILON = 1e-12
        const val MINIMUM_VARIANCE = 1e-9
        const val MINIMUM_GATE_WEIGHT = 1e-6
        const val FULL_CIRCLE_DEGREES = 360.0

        fun index(row: Int, column: Int): Int = row * STATE_SIZE + column
    }
}

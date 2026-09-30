package kr.co.hanium.dreamup.walksafe.navigation.positioning

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
    val measurementNoiseMultiplier: Double = 1.0,
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
    val receivedAtElapsedRealtimeMs: Long = elapsedRealtimeMs,
    /** Only true for a batch whose complete direction history is actually known. */
    val headingCoversWholeBatch: Boolean = false,
)

data class ZuptObservation(
    val elapsedRealtimeMs: Long,
    val velocitySigmaMps: Double = 0.15,
    val receivedAtElapsedRealtimeMs: Long = elapsedRealtimeMs,
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
    REPLAY_WINDOW_EXCEEDED,
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
    INVALID_TIME,
    STALE,
    GENERATION_MISMATCH,
    MOTION_NOT_CONFIRMED,
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
    /** S09-45: the filter's 2x2 east/north position covariance (m²) behind [horizontalUncertaintyM]. */
    val positionCovarianceEnu: EnuPositionCovariance? = null,
)

data class PedestrianPositionEstimatorUpdate(
    val estimate: EstimatedLocation?,
    val disposition: GnssObservationDisposition,
    val gateWeight: Double? = null,
    val hardRejectReason: GnssHardRejectReason? = null,
    /** Posterior at the actual GNSS measurement time, before any later motion is replayed. */
    val estimateAtGnssMeasurement: EstimatedLocation? = null,
    val replayedMotionEventCount: Int = 0,
    /** Measurement lag behind the pre-update committed state, independent of callback age. */
    val measurementLagMs: Long = 0L,
)

data class PedestrianMotionEstimatorUpdate(
    val estimate: EstimatedLocation?,
    val disposition: PedestrianMotionUpdateDisposition,
    val hardRejectReason: PedestrianMotionHardRejectReason? = null,
    /** How far a late step/ZUPT was behind the committed state (0 when it arrived in order). */
    val measurementLagMs: Long = 0L,
    /** Retained events re-applied after a late step/ZUPT was inserted in time order. */
    val replayedEventCount: Int = 0,
) {
    /**
     * S09-9 (originals/09.txt:34): field-log fields for a step/ZUPT that arrived behind the committed
     * state, or null for an in-order update. Only enum names and whole numbers (no position or time of day).
     */
    internal fun lateMotionEventFieldsOrNull(): Map<String, Any?>? {
        if (measurementLagMs <= 0L) return null
        val replayed = disposition != PedestrianMotionUpdateDisposition.HARD_REJECTED
        return mapOf(
            "result" to if (replayed) "REPLAYED" else "REJECTED",
            "reason" to hardRejectReason?.name,
            "measurement_lag_ms" to measurementLagMs,
            "replayed_event_count" to replayedEventCount,
        )
    }
}

/** Late step/ZUPT accounting for field logs: what was re-ordered and what was still refused. */
data class LateMotionStatistics(
    val replayedCount: Long = 0L,
    val rejectedCount: Long = 0L,
    val maximumReplayedLagMs: Long = 0L,
    val maximumRejectedLagMs: Long = 0L,
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
    val maximumGnssReplayLagMs: Long = 5_000L,
    /**
     * A step or ZUPT at most this far behind the committed state is inserted in time order and the
     * later retained events are re-applied; older ones stay OUT_OF_ORDER and are counted. Matches the
     * documented Step Detector delivery latency (below 2 s).
     */
    val maximumLateMotionReplayLagMs: Long = 2_000L,
    val maximumRetainedMotionEvents: Int = 128,
    val maximumStepBatchSize: Int = 64,
    /**
     * S09-10 follow-up (w25 review pos-F3, Root R16): a step interval longer than this multiple of the recent
     * per-step interval may contain a pause without ZUPT. Fixes measured before the last recent-step duration
     * of such an interval stay where they were measured (no share of the step is placed before them); only
     * fixes inside that final motion window get the matching share of the step.
     */
    val maximumPlacementStepIntervalRatio: Double = 2.0,
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
        require(maximumGnssReplayLagMs >= 0L)
        require(maximumLateMotionReplayLagMs in 0L..maximumGnssReplayLagMs)
        require(maximumRetainedMotionEvents > 0)
        require(maximumStepBatchSize > 0)
        require(maximumPlacementStepIntervalRatio.isFinite() && maximumPlacementStepIntervalRatio >= 1.0)
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
    private var lastAcceptedGnssElapsedRealtimeMs: Long? = null
    private var velocityExtrapolationAllowed = true

    /**
     * S09-10: fraction of the next directed step's mean displacement that is already in the mean
     * because a GNSS fix inside that step's interval was placed on the step (see [placeFixesInsideStep]).
     */
    private var stepFractionInMean = 0.0

    /** Per-step duration of the last step interval (t_{k-1} - t_{k-2}) / n_{k-1}; null until two steps. */
    private var recentStepIntervalMs: Double? = null
    private val motionHistory = mutableListOf<RetainedMotion>()
    private var activeProcessAccelerationSigmaMps2 = config.processAccelerationSigmaMps2
    private var activeAverageWalkingSpeedMps = config.referenceWalkingSpeedMps
    private var lateMotionStatistics = LateMotionStatistics()

    fun reset() {
        tangentPlane = null
        state = null
        covariance = null
        stateElapsedRealtimeMs = null
        lastInformativeGnssElapsedRealtimeMs = null
        lastPdrStepElapsedRealtimeMs = null
        lastZuptElapsedRealtimeMs = null
        lastAcceptedGnssElapsedRealtimeMs = null
        velocityExtrapolationAllowed = true
        stepFractionInMean = 0.0
        recentStepIntervalMs = null
        motionHistory.clear()
        lateMotionStatistics = LateMotionStatistics()
    }

    fun lateMotionStatistics(): LateMotionStatistics = lateMotionStatistics

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

        if (lastAcceptedGnssElapsedRealtimeMs?.let { observation.elapsedRealtimeMs <= it } == true) {
            return rejected(GnssHardRejectReason.OUT_OF_ORDER)
        }
        val currentTime = stateElapsedRealtimeMs
        if (currentTime != null && observation.elapsedRealtimeMs <= currentTime) {
            return replayWithGnss(observation, currentTime)
        }
        val before = currentTime?.let { captureState() }
        val update = applyGnssInOrder(observation)
        if (update.disposition == GnssObservationDisposition.HARD_REJECTED) return update
        lastAcceptedGnssElapsedRealtimeMs = observation.elapsedRealtimeMs
        if (before == null || update.disposition == GnssObservationDisposition.REINITIALIZED) {
            motionHistory.clear()
        } else {
            // Kept so a slightly late step or ZUPT can still be placed before this fix.
            retainMotion(MotionObservation.Fix(observation), before)
        }
        return update.copy(estimateAtGnssMeasurement = update.estimate)
    }

    private fun applyGnssInOrder(observation: GnssPositionObservation): PedestrianPositionEstimatorUpdate {
        val currentTime = stateElapsedRealtimeMs
        val gnssSigmaM = max(
            observation.horizontalAccuracyM ?: config.missingAccuracySigmaM,
            config.minimumGnssSigmaM,
        ) * sqrt(observation.measurementNoiseMultiplier)
        if (!gnssSigmaM.isFinite() || !(gnssSigmaM * gnssSigmaM).isFinite()) {
            return rejected(GnssHardRejectReason.NUMERICAL_FAILURE)
        }
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
            velocityExtrapolationAllowed = true
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
        validateMotionTime(observation.elapsedRealtimeMs, observation.receivedAtElapsedRealtimeMs)?.let {
            return motionRejected(it)
        }
        val currentTime = stateElapsedRealtimeMs
            ?: return motionRejected(PedestrianMotionHardRejectReason.UNINITIALIZED)
        if (lastPdrStepElapsedRealtimeMs?.let { observation.elapsedRealtimeMs <= it } == true) {
            return motionRejected(PedestrianMotionHardRejectReason.OUT_OF_ORDER)
        }
        if (
            observation.stepCount !in 1..config.maximumStepBatchSize ||
            !observation.stepLengthM.isFinite() || observation.stepLengthM <= 0.0 ||
            !observation.stepLengthSigmaM.isFinite() || observation.stepLengthSigmaM < 0.0
        ) return motionRejected(PedestrianMotionHardRejectReason.INVALID_STEP)
        if (observation.elapsedRealtimeMs < currentTime) {
            return replayWithLateMotion(MotionObservation.Step(observation), currentTime)
        }

        val headingDegrees = observation.headingDegreesTrueNorth
        val headingAccuracyDegrees = observation.headingAccuracyDegrees
        val directionUsable = (observation.stepCount == 1 || observation.headingCoversWholeBatch) &&
            observation.quality != PdrStepQuality.LOW &&
            headingDegrees != null && headingDegrees.isFinite() &&
            headingAccuracyDegrees != null && headingAccuracyDegrees.isFinite() &&
            headingAccuracyDegrees in 0.0..config.maximumPdrHeadingAccuracyDegrees
        val distanceM = observation.stepCount * observation.stepLengthM
        val undoPlacement = if (directionUsable) {
            placeFixesInsideStep(observation, distanceM, requireNotNull(headingDegrees))
        } else {
            null
        }
        val stepStartTime = requireNotNull(stateElapsedRealtimeMs)
        val before = captureState()
        val predicted = predictMotionControlledInterval(
            requireNotNull(state),
            requireNotNull(covariance),
            (observation.elapsedRealtimeMs - stepStartTime) / MILLIS_PER_SECOND,
        ) ?: return rejectStepAfterPlacement(undoPlacement)
        val updated = if (directionUsable) {
            applyDirectedStep(
                predicted,
                distanceM,
                observation.stepCount,
                observation.stepLengthSigmaM,
                requireNotNull(headingDegrees),
                requireNotNull(headingAccuracyDegrees),
                observation.quality,
                // S09-10: the part of this step already placed before an interior fix is not added twice.
                meanDistanceM = distanceM * (1.0 - stepFractionInMean),
            )
        } else {
            applyUnresolvedStep(predicted, distanceM, observation.stepCount, observation.stepLengthSigmaM)
        }
        if (!updated.isFinite()) {
            return rejectStepAfterPlacement(undoPlacement)
        }
        state = updated.state
        covariance = updated.covariance
        stateElapsedRealtimeMs = observation.elapsedRealtimeMs
        recentStepIntervalMs = lastPdrStepElapsedRealtimeMs?.let { previous ->
            (observation.elapsedRealtimeMs - previous).toDouble() / observation.stepCount
        }
        lastPdrStepElapsedRealtimeMs = observation.elapsedRealtimeMs
        stepFractionInMean = 0.0
        if (directionUsable) {
            // A directed displacement does not establish a replacement velocity or stationarity.
            // Retain the velocity state and its covariance, but stop extrapolating its old direction.
            velocityExtrapolationAllowed = false
        }
        retainMotion(MotionObservation.Step(observation), before)
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
        validateMotionTime(observation.elapsedRealtimeMs, observation.receivedAtElapsedRealtimeMs)?.let {
            return motionRejected(it)
        }
        val currentTime = stateElapsedRealtimeMs
            ?: return motionRejected(PedestrianMotionHardRejectReason.UNINITIALIZED)
        if (lastZuptElapsedRealtimeMs?.let { observation.elapsedRealtimeMs <= it } == true) {
            return motionRejected(PedestrianMotionHardRejectReason.OUT_OF_ORDER)
        }
        if (!observation.velocitySigmaMps.isFinite() || observation.velocitySigmaMps <= 0.0) {
            return motionRejected(PedestrianMotionHardRejectReason.INVALID_ZUPT)
        }
        if (observation.elapsedRealtimeMs < currentTime) {
            return replayWithLateMotion(MotionObservation.Zupt(observation), currentTime)
        }
        val before = captureState()
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
        retainMotion(MotionObservation.Zupt(observation), before)
        return PedestrianMotionEstimatorUpdate(
            estimate = buildEstimate(corrected.state, corrected.covariance, observation.elapsedRealtimeMs),
            disposition = PedestrianMotionUpdateDisposition.APPLIED,
        )
    }

    internal fun covarianceSnapshot(): DoubleArray = covariance?.copyOf() ?: DoubleArray(0)

    private fun validateMotionTime(measuredAtMs: Long, receivedAtMs: Long): PedestrianMotionHardRejectReason? {
        if (measuredAtMs < 0L || receivedAtMs < measuredAtMs) return PedestrianMotionHardRejectReason.INVALID_TIME
        if (receivedAtMs - measuredAtMs > config.maximumObservationAgeMs ||
            stateElapsedRealtimeMs?.let { measuredAtMs > it && measuredAtMs - it > config.maximumRetainedStateGapMs } == true
        ) return PedestrianMotionHardRejectReason.STALE
        return null
    }

    /**
     * GNSS remains monotonic relative to accepted GNSS, while motion may arrive ahead of a fix.
     * Rewind only retained motion; a GNSS sharing a timestamp is applied before that motion.
     * The provisional replay is rolled back in full if either correction or replay fails.
     */
    private fun replayWithGnss(
        observation: GnssPositionObservation,
        currentTime: Long,
    ): PedestrianPositionEstimatorUpdate {
        val lagMs = currentTime - observation.elapsedRealtimeMs
        val replayIndex = motionHistory.indexOfFirst {
            it.observation.elapsedRealtimeMs >= observation.elapsedRealtimeMs
        }
        if (
            lagMs > config.maximumGnssReplayLagMs || replayIndex < 0 ||
            motionHistory[replayIndex].before.elapsedRealtimeMs >= observation.elapsedRealtimeMs
        ) {
            return rejected(GnssHardRejectReason.REPLAY_WINDOW_EXCEEDED)
                .copy(measurementLagMs = lagMs)
        }
        val retainedState = captureState()
        val retainedHistory = motionHistory.toList()
        val replay = retainedHistory.drop(replayIndex)
        restoreState(replay.first().before)
        val update = applyGnssInOrder(observation)
        if (update.disposition == GnssObservationDisposition.HARD_REJECTED) {
            restoreState(retainedState)
            return rejected(requireNotNull(update.hardRejectReason)).copy(measurementLagMs = lagMs)
        }
        lastAcceptedGnssElapsedRealtimeMs = observation.elapsedRealtimeMs
        motionHistory.clear()
        motionHistory.addAll(retainedHistory.take(replayIndex))
        motionHistory.add(RetainedMotion(MotionObservation.Fix(observation), replay.first().before))
        if (!replayRetained(replay)) {
            restoreState(retainedState)
            motionHistory.clear()
            motionHistory.addAll(retainedHistory)
            return rejected(GnssHardRejectReason.NUMERICAL_FAILURE).copy(measurementLagMs = lagMs)
        }
        activeProcessAccelerationSigmaMps2 = retainedState.processAccelerationSigmaMps2
        activeAverageWalkingSpeedMps = retainedState.averageWalkingSpeedMps
        return update.copy(
            estimate = buildEstimate(requireNotNull(state), requireNotNull(covariance), currentTime),
            estimateAtGnssMeasurement = update.estimate,
            replayedMotionEventCount = replay.size,
            measurementLagMs = lagMs,
        )
    }

    /**
     * Re-applies retained events in time order. Profile changes have no sensor timestamp, so each
     * interval keeps the dynamics that originally governed it rather than the latest profile.
     */
    private fun replayRetained(replay: List<RetainedMotion>): Boolean {
        for (entry in replay) {
            activeProcessAccelerationSigmaMps2 = entry.before.processAccelerationSigmaMps2
            activeAverageWalkingSpeedMps = entry.before.averageWalkingSpeedMps
            if (!applyRetained(entry.observation)) return false
        }
        return true
    }

    private fun applyRetained(observation: MotionObservation): Boolean = when (observation) {
        is MotionObservation.Step ->
            observePdrStep(observation.value).disposition != PedestrianMotionUpdateDisposition.HARD_REJECTED
        is MotionObservation.Zupt ->
            observeZupt(observation.value).disposition != PedestrianMotionUpdateDisposition.HARD_REJECTED
        is MotionObservation.Fix -> {
            val before = captureState()
            val update = applyGnssInOrder(observation.value)
            if (update.disposition == GnssObservationDisposition.HARD_REJECTED ||
                update.disposition == GnssObservationDisposition.REINITIALIZED
            ) {
                false
            } else {
                lastAcceptedGnssElapsedRealtimeMs = observation.value.elapsedRealtimeMs
                retainMotion(observation, before)
                true
            }
        }
    }

    /**
     * S09-9: a step or ZUPT slightly behind the committed state (typically a GNSS fix delivered
     * first) is inserted in time order and the later retained events are re-applied, giving the same
     * result as in-order arrival. Anything later than [PedestrianPositionEstimatorConfig
     * .maximumLateMotionReplayLagMs], or older than the retained checkpoints, is still refused as
     * OUT_OF_ORDER and counted. A failed replay restores the previous state and history.
     */
    private fun replayWithLateMotion(late: MotionObservation, currentTime: Long): PedestrianMotionEstimatorUpdate {
        val lagMs = currentTime - late.elapsedRealtimeMs
        val replayIndex = motionHistory.indexOfFirst { it.observation.elapsedRealtimeMs > late.elapsedRealtimeMs }
        if (
            lagMs > config.maximumLateMotionReplayLagMs || replayIndex < 0 ||
            motionHistory[replayIndex].before.elapsedRealtimeMs > late.elapsedRealtimeMs
        ) return rejectLateMotion(lagMs)
        // pos-F1: a checkpoint taken inside a placed step interval already holds part of that step. Replaying
        // from it would keep a placement the new history may no longer allow (a ZUPT inside the interval), so
        // restart from the interval's first unplaced checkpoint and let the step decide the placement again.
        val startIndex = unplacedReplayStart(replayIndex) ?: return rejectLateMotion(lagMs)
        val retainedState = captureState()
        val retainedHistory = motionHistory.toList()
        val replay = retainedHistory.drop(replayIndex)
        restoreState(retainedHistory[startIndex].before)
        motionHistory.clear()
        motionHistory.addAll(retainedHistory.take(startIndex))
        val precedingReplayed = replayRetained(retainedHistory.subList(startIndex, replayIndex))
        val inserted = if (!precedingReplayed) {
            null
        } else {
            // The late event belongs to the interval that ends at the first replayed entry.
            activeProcessAccelerationSigmaMps2 = replay.first().before.processAccelerationSigmaMps2
            activeAverageWalkingSpeedMps = replay.first().before.averageWalkingSpeedMps
            when (late) {
                is MotionObservation.Step -> observePdrStep(late.value)
                is MotionObservation.Zupt -> observeZupt(late.value)
                is MotionObservation.Fix -> null
            }
        }
        if (inserted == null || inserted.disposition == PedestrianMotionUpdateDisposition.HARD_REJECTED ||
            !replayRetained(replay)
        ) {
            restoreState(retainedState)
            motionHistory.clear()
            motionHistory.addAll(retainedHistory)
            return rejectLateMotion(lagMs)
        }
        activeProcessAccelerationSigmaMps2 = retainedState.processAccelerationSigmaMps2
        activeAverageWalkingSpeedMps = retainedState.averageWalkingSpeedMps
        lateMotionStatistics = lateMotionStatistics.copy(
            replayedCount = lateMotionStatistics.replayedCount + 1L,
            maximumReplayedLagMs = max(lateMotionStatistics.maximumReplayedLagMs, lagMs),
        )
        return PedestrianMotionEstimatorUpdate(
            estimate = buildEstimate(requireNotNull(state), requireNotNull(covariance), requireNotNull(stateElapsedRealtimeMs)),
            disposition = inserted.disposition,
            measurementLagMs = lagMs,
            replayedEventCount = replay.size,
        )
    }

    /**
     * Index of the retained entry whose checkpoint has no step fraction in the mean, walking back from
     * [replayIndex] through the placed fixes of the same step interval; null if that checkpoint is no longer
     * retained.
     */
    private fun unplacedReplayStart(replayIndex: Int): Int? {
        var index = replayIndex
        while (motionHistory[index].before.stepFractionInMean > 0.0) {
            if (index == 0) return null
            index -= 1
        }
        return index
    }

    /** pos-F4: a step rejected after its interval was re-placed leaves the state and history as they were. */
    private fun rejectStepAfterPlacement(
        undoPlacement: Pair<StateCheckpoint, List<RetainedMotion>>?,
    ): PedestrianMotionEstimatorUpdate {
        if (undoPlacement != null) {
            restoreState(undoPlacement.first)
            motionHistory.clear()
            motionHistory.addAll(undoPlacement.second)
        }
        return motionRejected(PedestrianMotionHardRejectReason.NUMERICAL_FAILURE)
    }

    /**
     * S09-10 interval definition: a directed step at t_k carries the displacement of
     * (t_{k-1}, t_k], spread uniformly over that interval, and a GNSS fix at t updates the state at t.
     * When fixes were applied inside the step interval, they are re-applied (from the retained
     * checkpoint at t_{k-1}) with the matching fraction of this step already in the mean and without
     * constant-velocity mean extrapolation, and the step then adds only the remaining fraction. When the
     * interval is longer than [PedestrianPositionEstimatorConfig.maximumPlacementStepIntervalRatio] times the
     * recent step duration (a possible pause without ZUPT, Root R16), the displacement is spread only over the
     * final recent step duration, so fixes measured during the pause get no share of the step. Before two
     * steps are known the whole interval is used. Without a complete retained interval (or with a ZUPT inside
     * it) nothing is rewound; the remaining fraction in the state is used as is. The placement adds no
     * covariance term itself; the posterior covariance is the same as without it only while every soft-gate
     * weight is unchanged, because the gate weight of a re-applied fix depends on its innovation.
     *
     * Returns the state and history before the rewind (so a step rejected afterwards can undo it), or null
     * when nothing was changed.
     */
    private fun placeFixesInsideStep(
        observation: PdrStepObservation,
        distanceM: Double,
        headingDegrees: Double,
    ): Pair<StateCheckpoint, List<RetainedMotion>>? {
        val previousStepAt = lastPdrStepElapsedRealtimeMs ?: return null
        val stepAt = observation.elapsedRealtimeMs
        if (stepAt <= previousStepAt) return null
        // R16: after a possible pause only the final recent-step duration carries the step's displacement.
        val recentStepDurationMs = recentStepIntervalMs?.let { it * observation.stepCount }
        val motionStartAt = if (
            recentStepDurationMs != null &&
            stepAt - previousStepAt > config.maximumPlacementStepIntervalRatio * recentStepDurationMs
        ) {
            stepAt - recentStepDurationMs
        } else {
            previousStepAt.toDouble()
        }
        val interiorIndex = motionHistory.indexOfFirst { it.observation.elapsedRealtimeMs > previousStepAt }
        if (interiorIndex < 0) return null
        val interior = motionHistory.subList(interiorIndex, motionHistory.size).toList()
        val first = interior.first()
        if (
            interior.any { it.observation !is MotionObservation.Fix || it.observation.elapsedRealtimeMs > stepAt } ||
            first.before.elapsedRealtimeMs != previousStepAt ||
            first.before.lastPdrStepElapsedRealtimeMs != previousStepAt
        ) return null
        val retainedState = captureState()
        val retainedHistory = motionHistory.toList()
        restoreState(first.before)
        motionHistory.clear()
        motionHistory.addAll(retainedHistory.take(interiorIndex))
        val headingRad = Math.toRadians(normalizeDegrees(headingDegrees))
        for (entry in interior) {
            val fix = (entry.observation as MotionObservation.Fix).value
            val before = captureState()
            val fraction = ((fix.elapsedRealtimeMs - motionStartAt) / (stepAt - motionStartAt))
                .coerceIn(stepFractionInMean, 1.0)
            val shiftM = distanceM * (fraction - stepFractionInMean)
            val shifted = requireNotNull(state).copyOf()
            shifted[EAST] += shiftM * sin(headingRad)
            shifted[NORTH] += shiftM * cos(headingRad)
            state = shifted
            stepFractionInMean = fraction
            // Inside a step interval the step is the mean motion model; no velocity x dt on top of it.
            velocityExtrapolationAllowed = false
            activeProcessAccelerationSigmaMps2 = entry.before.processAccelerationSigmaMps2
            activeAverageWalkingSpeedMps = entry.before.averageWalkingSpeedMps
            val update = applyGnssInOrder(fix)
            if (update.disposition == GnssObservationDisposition.HARD_REJECTED ||
                update.disposition == GnssObservationDisposition.REINITIALIZED
            ) {
                restoreState(retainedState)
                motionHistory.clear()
                motionHistory.addAll(retainedHistory)
                return null
            }
            lastAcceptedGnssElapsedRealtimeMs = fix.elapsedRealtimeMs
            motionHistory.add(RetainedMotion(entry.observation, before))
        }
        activeProcessAccelerationSigmaMps2 = retainedState.processAccelerationSigmaMps2
        activeAverageWalkingSpeedMps = retainedState.averageWalkingSpeedMps
        return retainedState to retainedHistory
    }

    private fun rejectLateMotion(lagMs: Long): PedestrianMotionEstimatorUpdate {
        lateMotionStatistics = lateMotionStatistics.copy(
            rejectedCount = lateMotionStatistics.rejectedCount + 1L,
            maximumRejectedLagMs = max(lateMotionStatistics.maximumRejectedLagMs, lagMs),
        )
        return motionRejected(PedestrianMotionHardRejectReason.OUT_OF_ORDER).copy(measurementLagMs = lagMs)
    }

    private fun retainMotion(observation: MotionObservation, before: StateCheckpoint) {
        motionHistory.add(RetainedMotion(observation, before))
        val oldestTime = observation.elapsedRealtimeMs - config.maximumGnssReplayLagMs
        while (
            motionHistory.size > config.maximumRetainedMotionEvents ||
            motionHistory.firstOrNull()?.let { it.observation.elapsedRealtimeMs < oldestTime } == true
        ) {
            motionHistory.removeAt(0)
        }
    }

    private fun captureState() = StateCheckpoint(
        plane = requireNotNull(tangentPlane),
        state = requireNotNull(state).copyOf(),
        covariance = requireNotNull(covariance).copyOf(),
        elapsedRealtimeMs = requireNotNull(stateElapsedRealtimeMs),
        lastInformativeGnssElapsedRealtimeMs = lastInformativeGnssElapsedRealtimeMs,
        lastAcceptedGnssElapsedRealtimeMs = lastAcceptedGnssElapsedRealtimeMs,
        lastPdrStepElapsedRealtimeMs = lastPdrStepElapsedRealtimeMs,
        lastZuptElapsedRealtimeMs = lastZuptElapsedRealtimeMs,
        processAccelerationSigmaMps2 = activeProcessAccelerationSigmaMps2,
        averageWalkingSpeedMps = activeAverageWalkingSpeedMps,
        velocityExtrapolationAllowed = velocityExtrapolationAllowed,
        stepFractionInMean = stepFractionInMean,
        recentStepIntervalMs = recentStepIntervalMs,
    )

    private fun restoreState(checkpoint: StateCheckpoint) {
        tangentPlane = checkpoint.plane
        state = checkpoint.state.copyOf()
        covariance = checkpoint.covariance.copyOf()
        stateElapsedRealtimeMs = checkpoint.elapsedRealtimeMs
        lastInformativeGnssElapsedRealtimeMs = checkpoint.lastInformativeGnssElapsedRealtimeMs
        lastAcceptedGnssElapsedRealtimeMs = checkpoint.lastAcceptedGnssElapsedRealtimeMs
        lastPdrStepElapsedRealtimeMs = checkpoint.lastPdrStepElapsedRealtimeMs
        lastZuptElapsedRealtimeMs = checkpoint.lastZuptElapsedRealtimeMs
        activeProcessAccelerationSigmaMps2 = checkpoint.processAccelerationSigmaMps2
        activeAverageWalkingSpeedMps = checkpoint.averageWalkingSpeedMps
        velocityExtrapolationAllowed = checkpoint.velocityExtrapolationAllowed
        stepFractionInMean = checkpoint.stepFractionInMean
        recentStepIntervalMs = checkpoint.recentStepIntervalMs
    }

    private data class StateCheckpoint(
        val plane: LocalTangentPlane,
        val state: DoubleArray,
        val covariance: DoubleArray,
        val elapsedRealtimeMs: Long,
        val lastInformativeGnssElapsedRealtimeMs: Long?,
        val lastAcceptedGnssElapsedRealtimeMs: Long?,
        val lastPdrStepElapsedRealtimeMs: Long?,
        val lastZuptElapsedRealtimeMs: Long?,
        val processAccelerationSigmaMps2: Double,
        val averageWalkingSpeedMps: Double,
        val velocityExtrapolationAllowed: Boolean,
        val stepFractionInMean: Double,
        val recentStepIntervalMs: Double?,
    )

    private sealed interface MotionObservation {
        val elapsedRealtimeMs: Long

        data class Step(val value: PdrStepObservation) : MotionObservation {
            override val elapsedRealtimeMs: Long get() = value.elapsedRealtimeMs
        }

        data class Zupt(val value: ZuptObservation) : MotionObservation {
            override val elapsedRealtimeMs: Long get() = value.elapsedRealtimeMs
        }

        /** An accepted fix, retained only so a late step/ZUPT can be replayed before it. */
        data class Fix(val value: GnssPositionObservation) : MotionObservation {
            override val elapsedRealtimeMs: Long get() = value.elapsedRealtimeMs
        }
    }

    private data class RetainedMotion(val observation: MotionObservation, val before: StateCheckpoint)

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
        stepFractionInMean = 0.0
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
        if (!observation.measurementNoiseMultiplier.isFinite() || observation.measurementNoiseMultiplier < 1.0) {
            return GnssHardRejectReason.INVALID_ACCURACY
        }
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
            if (velocityExtrapolationAllowed) {
                predictedState[EAST] += predictedState[VELOCITY_EAST] * dt
                predictedState[NORTH] += predictedState[VELOCITY_NORTH] * dt
            }
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
        addAxisProcessNoise(predicted, EAST, VELOCITY_EAST, accelerationVariance, dt, dt2, dt3)
        addAxisProcessNoise(predicted, NORTH, VELOCITY_NORTH, accelerationVariance, dt, dt2, dt3)
        return stabilizeCovariance(predicted)
    }

    private fun predictMotionControlledCovariance(previous: DoubleArray, dt: Double): DoubleArray {
        val predicted = previous.copyOf()
        val accelerationVariance = activeProcessAccelerationVariance()
        // The step-controlled transition is identity, so its diffusion must be additive in time.
        // This is process uncertainty, not another displacement or a stationary observation.
        for (axis in listOf(EAST, NORTH)) predicted[index(axis, axis)] += accelerationVariance * dt / 3.0
        for (axis in listOf(VELOCITY_EAST, VELOCITY_NORTH)) predicted[index(axis, axis)] += accelerationVariance * dt
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
        meanDistanceM: Double = distanceM,
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
        updatedState[EAST] += meanDistanceM * sinHeading
        updatedState[NORTH] += meanDistanceM * cosHeading

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
        dt: Double,
        dt2: Double,
        dt3: Double,
    ) {
        // Continuous white acceleration: Q(t1+t2) = F(t2)Q(t1)F(t2)' + Q(t2).
        matrix[index(positionIndex, positionIndex)] += dt3 * accelerationVariance / 3.0
        matrix[index(positionIndex, velocityIndex)] += 0.5 * dt2 * accelerationVariance
        matrix[index(velocityIndex, positionIndex)] += 0.5 * dt2 * accelerationVariance
        matrix[index(velocityIndex, velocityIndex)] += dt * accelerationVariance
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
            positionCovarianceEnu = EnuPositionCovariance(
                eastVarianceM2 = covariance[index(EAST, EAST)],
                northVarianceM2 = covariance[index(NORTH, NORTH)],
                eastNorthCovarianceM2 = 0.5 * (covariance[index(EAST, NORTH)] + covariance[index(NORTH, EAST)]),
            ),
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

    /** S09-52: the shared [LocalGeodesy] frame, so the estimator and the session ENU agree. */
    private class LocalTangentPlane(latitude: Double, longitude: Double) {
        private val frame = LocalGeodesy.Frame(latitude, longitude)

        fun toLocal(latitude: Double, longitude: Double): LocalPoint =
            frame.toLocal(latitude, longitude).let { LocalPoint(it.eastM, it.northM) }

        fun toGeodetic(eastM: Double, northM: Double): GeodeticPoint =
            frame.toCoordinate(eastM, northM).let { GeodeticPoint(it.latitude, it.longitude) }
    }

    private companion object {
        const val STATE_SIZE = 4
        const val MEASUREMENT_SIZE = 2
        const val MATRIX_SIZE = STATE_SIZE * STATE_SIZE
        const val EAST = 0
        const val NORTH = 1
        const val VELOCITY_EAST = 2
        const val VELOCITY_NORTH = 3
        const val MILLIS_PER_SECOND = 1_000.0
        const val NUMERICAL_EPSILON = 1e-12
        const val MINIMUM_VARIANCE = 1e-9
        const val MINIMUM_GATE_WEIGHT = 1e-6
        const val FULL_CIRCLE_DEGREES = 360.0

        fun index(row: Int, column: Int): Int = row * STATE_SIZE + column
    }
}

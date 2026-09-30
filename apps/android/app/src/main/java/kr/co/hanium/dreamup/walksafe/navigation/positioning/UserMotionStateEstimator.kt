package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import kotlin.math.abs

enum class UserMovementState { UNKNOWN, STATIONARY, MOVING }

enum class UserLocomotionState { UNKNOWN, WALKING }

/** Device rotation is not proof that the user's body is turning. */
enum class UserDeviceRotationState { UNKNOWN, STABLE, TURNING }

enum class UserMotionStateReason {
    NO_EVIDENCE,
    INVALID_TIME,
    EXPIRED_EVIDENCE,
    INSUFFICIENT_EVIDENCE,
    STEP_OBSERVED,
    GNSS_MOVEMENT,
    GNSS_MOVEMENT_WITH_QUIET_DEVICE,

    /**
     * S09-23: a published STATIONARY state met one accurate GNSS speed that conflicts with it. The
     * state is kept (one spike does not release stillness) but ZUPT is withheld until the conflict
     * either persists for [UserMotionStateEstimator.CONFIRMING_MOVING_SPEED_OBSERVATIONS] distinct fixes
     * (then MOVING) or a stationary-consistent speed returns.
     */
    GNSS_MOVEMENT_UNCONFIRMED,
    STATIONARY_CONFIRMED,
    GENERATION_MISMATCH,
}

data class UserMotionStateSnapshot(
    val generation: Long,
    val movement: UserMovementState,
    val locomotion: UserLocomotionState,
    val deviceRotation: UserDeviceRotationState,
    val reason: UserMotionStateReason,
    val stateSinceElapsedRealtimeMs: Long?,
    val evaluatedAtElapsedRealtimeMs: Long,
    val expiresAtElapsedRealtimeMs: Long?,
    val motionObservedAtElapsedRealtimeMs: Long?,
    val speedObservedAtElapsedRealtimeMs: Long?,
    val stepObservedAtElapsedRealtimeMs: Long?,
    val stationaryCandidateSinceElapsedRealtimeMs: Long?,
    val zeroVelocityUpdateAllowed: Boolean,
)

/** Initial conservative policy values, not an approved field-accuracy profile. */
data class UserMotionStateConfig(
    val maximumMotionAgeMs: Long = 500L,
    val maximumSpeedAgeMs: Long = 2_000L,
    val maximumStepAgeMs: Long = 2_000L,
    val maximumWalkingStepGapMs: Long = 3_000L,
    val stationarySpeedUpperBoundMps: Double = 0.3,
    val movingSpeedLowerBoundMps: Double = 0.5,
    val maximumSpeedAccuracyMps: Double = 0.8,
    val maximumHorizontalAccuracyM: Double = 15.0,
    val maximumSpeedMps: Double = 80.0,
    val maximumStepBatchSize: Int = 64,
    val stableGyroscopeRmsRadPerSecond: Double = 0.08,
    val turningGyroscopeRmsRadPerSecond: Double = 0.20,
) {
    init {
        require(maximumMotionAgeMs > 0L && maximumSpeedAgeMs > 0L && maximumStepAgeMs > 0L)
        require(maximumWalkingStepGapMs > 0L && maximumStepBatchSize > 0)
        require(listOf(
            stationarySpeedUpperBoundMps, movingSpeedLowerBoundMps, maximumSpeedAccuracyMps,
            maximumHorizontalAccuracyM, maximumSpeedMps, stableGyroscopeRmsRadPerSecond,
            turningGyroscopeRmsRadPerSecond,
        ).all { it.isFinite() && it >= 0.0 })
        require(movingSpeedLowerBoundMps > stationarySpeedUpperBoundMps)
        require(maximumSpeedMps > movingSpeedLowerBoundMps)
        require(turningGyroscopeRmsRadPerSecond > stableGyroscopeRmsRadPerSecond)
    }
}

/**
 * Physical motion only: ACTIVE/PAUSED and phone orientation cannot establish ground stillness.
 * One owner resets this instance with its location/walk lifecycle. Synchronized reads also serve
 * the rendering thread; evaluating freshness never advances any source's acceptance watermark.
 */
internal class UserMotionStateEstimator(
    private val config: UserMotionStateConfig = UserMotionStateConfig(),
    private val stationaryConfig: PedestrianStationaryDetectorConfig = PedestrianStationaryDetectorConfig(),
) {
    private var generation = 0L
    private var lastMotion: PedestrianStationaryDecision? = null
    private var lastSpeed: WalkingSpeedObservation? = null
    private var lastSpeedFixAtMs: Long? = null
    private var lastStepAtMs: Long? = null
    private var consecutiveStepEvents = 0
    private var lastPublishedAtMs: Long? = null
    private var publishedMovement = UserMovementState.UNKNOWN
    private var stateSinceMs: Long? = null
    private var lastSpeedObservationId: Long? = null
    private var accelerationQuietSinceMs: Long? = null
    private var lastAccelerationAtMs: Long? = null
    private var lastZuptWindowEndMs: Long? = null
    private var lastZuptSpeedAtMs: Long? = null
    private var consecutiveMovingSpeedObservations = 0
    private var lastMovingSpeedAtMs: Long? = null

    @Synchronized
    fun inputGeneration(): Long = generation

    @Synchronized
    fun reset() {
        generation += 1L
        lastMotion = null
        lastSpeed = null
        lastSpeedFixAtMs = null
        lastStepAtMs = null
        consecutiveStepEvents = 0
        lastPublishedAtMs = null
        publishedMovement = UserMovementState.UNKNOWN
        stateSinceMs = null
        lastSpeedObservationId = null
        accelerationQuietSinceMs = null
        lastAccelerationAtMs = null
        lastZuptWindowEndMs = null
        lastZuptSpeedAtMs = null
        consecutiveMovingSpeedObservations = 0
        lastMovingSpeedAtMs = null
    }

    @Synchronized
    fun observeMotion(decision: PedestrianStationaryDecision, receivedAtMs: Long, expectedGeneration: Long = generation) {
        if (expectedGeneration != generation) return
        val sample = decision.sample ?: return
        if (!decision.accepted || !fresh(receivedAtMs, sample.elapsedRealtimeMs, config.maximumMotionAgeMs)) return
        if (lastMotion?.sample?.elapsedRealtimeMs?.let { sample.elapsedRealtimeMs <= it } == true) return
        if (listOfNotNull(sample.accelerationMagnitudeMeanMps2, sample.accelerationMagnitudeVarianceMps4,
                sample.gyroscopeRmsRadPerSecond, sample.gyroscopePeakRadPerSecond).any { !it.isFinite() || it < 0.0 }
        ) return
        val coverage = sample.accelerationWindow
        val accelerationAt = coverage?.endedAtElapsedRealtimeMs
        val quiet = coverage?.isSufficient(sample.elapsedRealtimeMs, MINIMUM_WINDOW_SAMPLES,
            MINIMUM_WINDOW_DURATION_MS, MAXIMUM_WINDOW_GAP_MS) == true && !sample.stepDetected &&
            sample.accelerationMagnitudeMeanMps2?.let {
                abs(it - stationaryConfig.expectedGravityMps2) <= stationaryConfig.maximumStationaryAccelerationMeanDeviationMps2
            } == true && sample.accelerationMagnitudeVarianceMps4?.let {
                it <= stationaryConfig.maximumStationaryAccelerationVarianceMps4
            } == true
        if (!quiet) accelerationQuietSinceMs = null
        else if (accelerationAt != null && (lastAccelerationAtMs == null || accelerationAt > requireNotNull(lastAccelerationAtMs))) {
            if (accelerationQuietSinceMs == null || lastAccelerationAtMs?.let {
                    accelerationAt - it > MAXIMUM_WINDOW_GAP_MS
                } == true
            ) accelerationQuietSinceMs = accelerationAt
        }
        if (accelerationAt != null && accelerationAt <= sample.elapsedRealtimeMs && accelerationAt >= 0L) {
            lastAccelerationAtMs = maxOf(lastAccelerationAtMs ?: 0L, accelerationAt)
        }
        lastMotion = decision
    }

    @Synchronized
    fun observeSpeed(
        speed: WalkingSpeedObservation?,
        location: UserMotionLocationEvidence,
        receivedAtMs: Long,
        expectedGeneration: Long = generation,
    ) {
        if (expectedGeneration != generation) return
        val at = location.observedAtElapsedRealtimeMs
        if (!fresh(receivedAtMs, at, config.maximumSpeedAgeMs) ||
            lastSpeedFixAtMs?.let { at <= it } == true || location.observationId < 0L ||
            lastSpeedObservationId?.let { location.observationId <= it } == true
        ) return
        val locationAccuracy = location.horizontalAccuracyM
        if (location.isMock || locationAccuracy == null || !locationAccuracy.isFinite() ||
            locationAccuracy !in 0.0..config.maximumHorizontalAccuracyM
        ) return
        if (speed != null && (speed.elapsedRealtimeMs != at || !speed.speedMps.isFinite() ||
                speed.speedMps !in 0.0..config.maximumSpeedMps || speed.accuracyMps?.let {
                    it.isFinite() && it in 0.0..config.maximumSpeedAccuracyMps
                } != true)
        ) return
        lastSpeedFixAtMs = at
        lastSpeedObservationId = location.observationId
        lastSpeed = speed
        // S09-23: persistence of accurate ground movement counts distinct accepted fixes only.
        val moving = speed != null && speed.speedMps - requireNotNull(speed.accuracyMps) >= config.movingSpeedLowerBoundMps
        consecutiveMovingSpeedObservations = when {
            !moving -> 0
            lastMovingSpeedAtMs?.let { at - it <= config.maximumSpeedAgeMs } == true && consecutiveMovingSpeedObservations > 0 ->
                (consecutiveMovingSpeedObservations + 1).coerceAtMost(CONFIRMING_MOVING_SPEED_OBSERVATIONS)
            else -> 1
        }
        lastMovingSpeedAtMs = if (moving) at else null
    }

    @Synchronized
    fun observeSteps(stepCount: Int, quality: PdrStepQuality, measuredAtMs: Long, receivedAtMs: Long,
        expectedGeneration: Long = generation) {
        if (expectedGeneration != generation || quality == PdrStepQuality.LOW || stepCount !in 1..config.maximumStepBatchSize ||
            !fresh(receivedAtMs, measuredAtMs, config.maximumStepAgeMs) ||
            lastStepAtMs?.let { measuredAtMs <= it } == true
        ) return
        consecutiveStepEvents = if (lastStepAtMs?.let {
                measuredAtMs - it <= config.maximumWalkingStepGapMs
            } == true
        ) (consecutiveStepEvents + 1).coerceAtMost(2) else 1
        lastStepAtMs = measuredAtMs
        accelerationQuietSinceMs = null
    }

    /** Publishes: advances lastPublished/stateSince. Only the navigation owner should call this. */
    @Synchronized
    fun snapshot(nowMs: Long): UserMotionStateSnapshot {
        if (nowMs < 0L || lastPublishedAtMs?.let { nowMs < it } == true) {
            return emptySnapshot(nowMs, UserMotionStateReason.INVALID_TIME)
        }
        return evaluate(nowMs, publish = true)
    }

    /**
     * ADD-MOTION-045-F1 / AM43-F2: the same classification as [snapshot] without publishing it.
     * Diagnostics, RAW metadata and transfer admission read with this, so they never move
     * lastPublished/stateSince and never turn a slightly earlier navigation evaluation into
     * INVALID_TIME or cost it a ZUPT. The state-since time comes from the last publication.
     */
    @Synchronized
    fun peek(nowMs: Long): UserMotionStateSnapshot {
        if (nowMs < 0L) return emptySnapshot(nowMs, UserMotionStateReason.INVALID_TIME)
        return evaluate(nowMs, publish = false)
    }

    /** Caller holds this monitor. */
    private fun evaluate(nowMs: Long, publish: Boolean): UserMotionStateSnapshot {
        val decision = lastMotion?.takeIf {
            it.sample?.let { sample -> fresh(nowMs, sample.elapsedRealtimeMs, config.maximumMotionAgeMs) } == true
        }
        val sample = decision?.sample
        val accelerationFresh = sample?.accelerationWindow?.isSufficient(
            nowMs, MINIMUM_WINDOW_SAMPLES, MINIMUM_WINDOW_DURATION_MS, MAXIMUM_WINDOW_GAP_MS,
        ) == true
        val gyroscopeFresh = sample?.gyroscopeWindow?.isSufficient(
            nowMs, MINIMUM_WINDOW_SAMPLES, MINIMUM_WINDOW_DURATION_MS, MAXIMUM_WINDOW_GAP_MS,
        ) == true
        val speed = lastSpeed?.takeIf { fresh(nowMs, it.elapsedRealtimeMs, config.maximumSpeedAgeMs) }
        val stepAt = lastStepAtMs?.takeIf { fresh(nowMs, it, config.maximumStepAgeMs) }
        val speedMoving = speed?.let {
            it.speedMps - requireNotNull(it.accuracyMps) >= config.movingSpeedLowerBoundMps
        } == true
        val speedStationary = speed?.let {
            it.speedMps + requireNotNull(it.accuracyMps) <= config.stationarySpeedUpperBoundMps
        } == true
        val quietDevice = accelerationFresh && accelerationQuietSinceMs?.let { since ->
            sample?.accelerationWindow?.endedAtElapsedRealtimeMs?.let {
                it - since >= stationaryConfig.stationaryDwellMs
            }
        } == true
        // S09-23: one accurate GNSS speed spike cannot release a published stillness; it only withholds ZUPT.
        val gnssMovementUnconfirmed = speedMoving && stepAt == null && quietDevice &&
            publishedMovement == UserMovementState.STATIONARY &&
            consecutiveMovingSpeedObservations < CONFIRMING_MOVING_SPEED_OBSERVATIONS
        val movement = when {
            stepAt != null -> UserMovementState.MOVING
            gnssMovementUnconfirmed -> UserMovementState.STATIONARY
            speedMoving -> UserMovementState.MOVING
            quietDevice && speedStationary -> UserMovementState.STATIONARY
            else -> UserMovementState.UNKNOWN
        }
        val rotation = if (gyroscopeFresh) {
            when {
                sample?.gyroscopeRmsRadPerSecond?.let {
                    it >= config.turningGyroscopeRmsRadPerSecond
                } == true -> UserDeviceRotationState.TURNING
                sample?.gyroscopeRmsRadPerSecond?.let {
                    it <= config.stableGyroscopeRmsRadPerSecond
                } == true -> UserDeviceRotationState.STABLE
                else -> UserDeviceRotationState.UNKNOWN
            }
        } else UserDeviceRotationState.UNKNOWN
        val reason = when {
            stepAt != null -> UserMotionStateReason.STEP_OBSERVED
            gnssMovementUnconfirmed -> UserMotionStateReason.GNSS_MOVEMENT_UNCONFIRMED
            speedMoving && quietDevice -> UserMotionStateReason.GNSS_MOVEMENT_WITH_QUIET_DEVICE
            speedMoving -> UserMotionStateReason.GNSS_MOVEMENT
            movement == UserMovementState.STATIONARY -> UserMotionStateReason.STATIONARY_CONFIRMED
            lastMotion == null && lastSpeedFixAtMs == null && lastStepAtMs == null -> UserMotionStateReason.NO_EVIDENCE
            decision == null && speed == null -> UserMotionStateReason.EXPIRED_EVIDENCE
            else -> UserMotionStateReason.INSUFFICIENT_EVIDENCE
        }
        val deadlines = mutableListOf<Long>()
        if (stepAt != null) deadlines += expires(stepAt, config.maximumStepAgeMs)
        if (speed != null && (speedMoving || movement == UserMovementState.STATIONARY)) {
            deadlines += expires(speed.elapsedRealtimeMs, config.maximumSpeedAgeMs)
        }
        if (movement == UserMovementState.STATIONARY) {
            sample?.accelerationWindow?.let {
                deadlines += expires(it.endedAtElapsedRealtimeMs, MAXIMUM_WINDOW_GAP_MS)
            }
            sample?.let { deadlines += expires(it.elapsedRealtimeMs, config.maximumMotionAgeMs) }
        }
        if (gyroscopeFresh && rotation != UserDeviceRotationState.UNKNOWN) {
            sample?.gyroscopeWindow?.let {
                deadlines += expires(it.endedAtElapsedRealtimeMs, MAXIMUM_WINDOW_GAP_MS)
            }
        }
        val stateSince = stateSinceMs?.takeIf { movement == publishedMovement } ?: nowMs
        if (publish) {
            stateSinceMs = stateSince
            publishedMovement = movement
            lastPublishedAtMs = nowMs
        }
        return UserMotionStateSnapshot(
            generation = generation,
            movement = movement,
            locomotion = if (stepAt != null && consecutiveStepEvents >= 2) {
                UserLocomotionState.WALKING
            } else UserLocomotionState.UNKNOWN,
            deviceRotation = rotation,
            reason = reason,
            stateSinceElapsedRealtimeMs = stateSince,
            evaluatedAtElapsedRealtimeMs = nowMs,
            expiresAtElapsedRealtimeMs = deadlines.minOrNull(),
            motionObservedAtElapsedRealtimeMs = sample?.elapsedRealtimeMs,
            speedObservedAtElapsedRealtimeMs = speed?.elapsedRealtimeMs,
            stepObservedAtElapsedRealtimeMs = stepAt,
            stationaryCandidateSinceElapsedRealtimeMs = accelerationQuietSinceMs
                .takeIf { accelerationFresh && movement != UserMovementState.MOVING },
            zeroVelocityUpdateAllowed = movement == UserMovementState.STATIONARY && !gnssMovementUnconfirmed &&
                rotation == UserDeviceRotationState.STABLE && decision?.shouldApplyZeroVelocityUpdate == true,
        )
    }

    /** Correlated overlapping windows and the same GNSS speed cannot be independent ZUPTs. */
    @Synchronized
    fun claimZeroVelocityUpdate(measuredAtMs: Long, receivedAtMs: Long): Boolean {
        val sample = lastMotion?.sample ?: return false
        if (sample.elapsedRealtimeMs != measuredAtMs) return false
        val state = snapshot(receivedAtMs)
        if (!state.zeroVelocityUpdateAllowed) return false
        val window = sample.accelerationWindow ?: return false
        val speedAt = state.speedObservedAtElapsedRealtimeMs ?: return false
        if (lastZuptWindowEndMs?.let { window.startedAtElapsedRealtimeMs <= it } == true ||
            lastZuptSpeedAtMs?.let { speedAt <= it } == true
        ) return false
        lastZuptWindowEndMs = window.endedAtElapsedRealtimeMs
        lastZuptSpeedAtMs = speedAt
        return true
    }

    @Synchronized
    fun rejectedGenerationSnapshot(nowMs: Long): UserMotionStateSnapshot =
        emptySnapshot(nowMs, UserMotionStateReason.GENERATION_MISMATCH)

    private fun emptySnapshot(nowMs: Long, reason: UserMotionStateReason) = UserMotionStateSnapshot(
        generation, UserMovementState.UNKNOWN, UserLocomotionState.UNKNOWN,
        UserDeviceRotationState.UNKNOWN, reason, null, nowMs, null, null, null, null, null, false,
    )

    private fun fresh(nowMs: Long, atMs: Long, maximumAgeMs: Long): Boolean =
        atMs >= 0L && nowMs >= atMs && nowMs - atMs <= maximumAgeMs

    private fun expires(atMs: Long, ttlMs: Long): Long =
        if (atMs > Long.MAX_VALUE - ttlMs) Long.MAX_VALUE else atMs + ttlMs

    internal companion object {
        /** Same "two distinct measurements" rule the confidence recovery uses; not a field value. */
        const val CONFIRMING_MOVING_SPEED_OBSERVATIONS = 2
        private const val MINIMUM_WINDOW_SAMPLES = 5
        private const val MINIMUM_WINDOW_DURATION_MS = 200L
        private const val MAXIMUM_WINDOW_GAP_MS = 250L
    }
}

package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.StepEventConfidence
import kr.co.hanium.dreamup.walksafe.navigation.StepEvent
import kr.co.hanium.dreamup.walksafe.navigation.StepEventSource
import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import java.util.Collections
import kotlin.math.abs

enum class UserMovementState { UNKNOWN, STATIONARY, MOVING }

enum class UserLocomotionState { UNKNOWN, WALKING }

/** Device rotation is not proof that the user's body is turning. */
enum class UserDeviceRotationState { UNKNOWN, STABLE, TURNING }

/**
 * Explicit device-body relation supplied by the current owner, not inferred from motion sensors.
 * Even SUPPORTED_MOUNT does not establish forward walking: sideways/backward steps and a constant
 * phone-body heading bias remain unobserved without independent evidence.
 */
enum class UserDeviceBodyRelationState { UNKNOWN, SUPPORTED_MOUNT, UNSUPPORTED_OR_CHANGED }

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
    OWNER_MISMATCH,

    /** Diagnostic only: an accepted step coexists with an accurate stationary GNSS speed. */
    STEP_WITH_STATIONARY_GNSS,
}

/** Actual boot identity may be unavailable; a process token is a separate, explicit boundary. */
data class UserMotionStateOwner(
    val actorGeneration: Long,
    val walkEpoch: WalkRuntimeEpoch,
    val bootId: String?,
    val processInstanceId: String,
) {
    init {
        require(actorGeneration >= 0L)
        require(bootId == null || bootId.isNotBlank())
        require(processInstanceId.isNotBlank())
    }
}

enum class UserMotionEvidenceHealth { WARMING_UP, VALID, DEGRADED, EXPIRED }

enum class UserMotionEvidenceSource {
    GNSS_FIX, ACCELEROMETER_WINDOW, GYROSCOPE_WINDOW, UNKNOWN_STEP_SOURCE,
    STEP_DETECTOR, STEP_COUNTER, ACCELEROMETER_STEP_FALLBACK,
}

/** Supplied facts from one accepted callback; [stepCount] is its batch size, not independent event count. */
data class UserMotionStepEvidence(
    val stepCount: Int,
    val quality: PdrStepQuality,
    val source: StepEventSource? = null,
    val confidence: StepEventConfidence? = null,
    val originalStepEvent: StepEvent? = null,
)

/** Consumers may lower quality, but cannot exceed the original producer's confidence. */
internal fun StepEvent.supportsPdrQuality(quality: PdrStepQuality): Boolean = when (quality) {
    PdrStepQuality.HIGH -> confidence == StepEventConfidence.HIGH
    PdrStepQuality.MEDIUM -> confidence != StepEventConfidence.LOW
    PdrStepQuality.LOW -> true
}

/** Actual statistics for this source's window; absent statistics remain unknown. */
data class UserMotionSensorEvidence(
    val accelerationMagnitudeMeanMps2: Double? = null,
    val accelerationMagnitudeVarianceMps4: Double? = null,
    val gyroscopeRmsRadPerSecond: Double? = null,
    val gyroscopePeakRadPerSecond: Double? = null,
)

/** Input facts, not inferred accuracy or reception health. A missing original source ID stays null. */
data class UserMotionEvidenceRef(
    val source: UserMotionEvidenceSource,
    val observationId: Long?,
    val measuredAtElapsedRealtimeMs: Long,
    val receivedAtElapsedRealtimeMs: Long,
    val windowCoverage: PedestrianMotionWindowCoverage? = null,
    val locationEvidence: UserMotionLocationEvidence? = null,
    val speedObservation: WalkingSpeedObservation? = null,
    val stepEvidence: UserMotionStepEvidence? = null,
    val sensorEvidence: UserMotionSensorEvidence? = null,
)

/** Per-source input coverage; overlapping windows and a step batch do not prove independent samples. */
data class UserMotionSourceSupport(val evidenceRef: UserMotionEvidenceRef) {
    val source: UserMotionEvidenceSource get() = evidenceRef.source
    val windowCoverage: PedestrianMotionWindowCoverage? get() = evidenceRef.windowCoverage
    val sampleCount: Int? get() = windowCoverage?.sampleCount
    val maximumGapMs: Long? get() = windowCoverage?.maximumGapMs
}

/** Opposing, currently accepted source facts; this does not select a different movement state. */
data class UserMotionEvidenceConflict(
    val reason: UserMotionStateReason,
    val evidenceRefs: List<UserMotionEvidenceRef>,
)

data class UserMotionSupport(
    val sourceCoverage: List<UserMotionSourceSupport> = emptyList(),
    val conflicts: List<UserMotionEvidenceConflict> = emptyList(),
)

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
    val owner: UserMotionStateOwner? = null,
    val evidenceHealth: UserMotionEvidenceHealth = UserMotionEvidenceHealth.WARMING_UP,
    /** Accepted source facts within the existing freshness limits; insufficient coverage stays inspectable. */
    val evidenceRefs: List<UserMotionEvidenceRef> = emptyList(),
    /** Current facts may coexist. [reason] remains the existing primary classification reason. */
    val reasonCodes: Set<UserMotionStateReason> = setOf(reason),
    val support: UserMotionSupport = UserMotionSupport(),
    val mount: UserDeviceBodyRelationState = UserDeviceBodyRelationState.UNKNOWN,
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
    private var owner: UserMotionStateOwner? = null
    private var notBeforeElapsedRealtimeMs = 0L
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
    private var lastGyroscopeAtMs: Long? = null
    private var lastZuptWindowEndMs: Long? = null
    private var lastZuptSpeedAtMs: Long? = null
    private var consecutiveMovingSpeedObservations = 0
    private var lastMovingSpeedAtMs: Long? = null
    private var lastGnssEvidence: UserMotionEvidenceRef? = null
    private var lastAccelerationEvidence: UserMotionEvidenceRef? = null
    private var lastGyroscopeEvidence: UserMotionEvidenceRef? = null
    private var lastStepEvidence: UserMotionEvidenceRef? = null
    private var lastMountRelation = UserDeviceBodyRelationState.UNKNOWN
    private var lastMountObservedAtMs: Long? = null
    private var lastMountReceivedAtMs: Long? = null

    @Synchronized
    fun inputGeneration(): Long = generation

    @Synchronized
    fun currentOwner(): UserMotionStateOwner? = owner

    /** Does not rotate the location collection generation. Old source watermarks belong to the old owner. */
    @Synchronized
    fun beginEpoch(newOwner: UserMotionStateOwner, notBeforeMs: Long): Boolean {
        if (notBeforeMs < 0L) return false
        val previous = owner
        if (previous != null && previous.bootId == newOwner.bootId &&
            previous.processInstanceId == newOwner.processInstanceId
        ) {
            if (notBeforeMs < notBeforeElapsedRealtimeMs || newOwner.actorGeneration < previous.actorGeneration ||
                (newOwner.actorGeneration == previous.actorGeneration &&
                    newOwner.walkEpoch.walkSessionId == previous.walkEpoch.walkSessionId &&
                    newOwner.walkEpoch.recoveryGeneration < previous.walkEpoch.recoveryGeneration)
            ) return false
        }
        if (newOwner == previous && notBeforeMs == notBeforeElapsedRealtimeMs) return true
        owner = newOwner
        notBeforeElapsedRealtimeMs = notBeforeMs
        clearEvidence()
        return true
    }

    @Synchronized
    fun ownsInput(measuredAtMs: Long, expectedOwner: UserMotionStateOwner?): Boolean =
        expectedOwner == owner && measuredAtMs >= notBeforeElapsedRealtimeMs

    @Synchronized
    fun reset() {
        generation += 1L
        owner = null
        notBeforeElapsedRealtimeMs = 0L
        clearEvidence()
    }

    private fun clearEvidence() {
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
        lastGyroscopeAtMs = null
        lastZuptWindowEndMs = null
        lastZuptSpeedAtMs = null
        consecutiveMovingSpeedObservations = 0
        lastMovingSpeedAtMs = null
        lastGnssEvidence = null
        lastAccelerationEvidence = null
        lastGyroscopeEvidence = null
        lastStepEvidence = null
        lastMountRelation = UserDeviceBodyRelationState.UNKNOWN
        lastMountObservedAtMs = null
        lastMountReceivedAtMs = null
    }

    /**
     * Orthogonal owner input: never renews motion evidence, health, expiry or a ZUPT allowance.
     * It persists in this epoch until explicitly changed/cleared. Equal times follow serialized
     * caller order so an immediate invalidation can clear support in the same millisecond.
     */
    @Synchronized
    fun observeMount(
        mount: UserDeviceBodyRelationState,
        observedAtMs: Long,
        receivedAtMs: Long,
        expectedGeneration: Long = generation,
        expectedOwner: UserMotionStateOwner? = owner,
    ): Boolean {
        if (expectedGeneration != generation || !ownsInput(observedAtMs, expectedOwner) ||
            observedAtMs < 0L || receivedAtMs < observedAtMs ||
            lastMountObservedAtMs?.let { observedAtMs < it } == true ||
            lastMountReceivedAtMs?.let { receivedAtMs < it } == true
        ) return false
        lastMountRelation = mount
        lastMountObservedAtMs = observedAtMs
        lastMountReceivedAtMs = receivedAtMs
        return true
    }

    @Synchronized
    fun observeMotion(
        decision: PedestrianStationaryDecision,
        receivedAtMs: Long,
        expectedGeneration: Long = generation,
        expectedOwner: UserMotionStateOwner? = owner,
    ) {
        if (expectedGeneration != generation || expectedOwner != owner) return
        val sample = decision.sample ?: return
        if (!decision.accepted || !fresh(receivedAtMs, sample.elapsedRealtimeMs, config.maximumMotionAgeMs)) return
        // A recent envelope must not recycle a window spanning the previous walk or a future window.
        if (!windowBelongsToEpoch(sample.accelerationWindow, sample.elapsedRealtimeMs, lastAccelerationAtMs) ||
            !windowBelongsToEpoch(sample.gyroscopeWindow, sample.elapsedRealtimeMs, lastGyroscopeAtMs)
        ) return
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
        sample.gyroscopeWindow?.let { lastGyroscopeAtMs = it.endedAtElapsedRealtimeMs }
        lastMotion = decision
        lastAccelerationEvidence = windowEvidence(UserMotionEvidenceSource.ACCELEROMETER_WINDOW,
            sample.accelerationWindow, receivedAtMs, lastAccelerationEvidence,
            UserMotionSensorEvidence(accelerationMagnitudeMeanMps2 = sample.accelerationMagnitudeMeanMps2,
                accelerationMagnitudeVarianceMps4 = sample.accelerationMagnitudeVarianceMps4))
        lastGyroscopeEvidence = windowEvidence(UserMotionEvidenceSource.GYROSCOPE_WINDOW,
            sample.gyroscopeWindow, receivedAtMs, lastGyroscopeEvidence,
            UserMotionSensorEvidence(gyroscopeRmsRadPerSecond = sample.gyroscopeRmsRadPerSecond,
                gyroscopePeakRadPerSecond = sample.gyroscopePeakRadPerSecond))
    }

    @Synchronized
    fun observeSpeed(
        speed: WalkingSpeedObservation?,
        location: UserMotionLocationEvidence,
        receivedAtMs: Long,
        expectedGeneration: Long = generation,
        expectedOwner: UserMotionStateOwner? = owner,
    ) {
        if (expectedGeneration != generation || expectedOwner != owner) return
        val at = location.observedAtElapsedRealtimeMs
        val observationId = location.observationId
        if (!fresh(receivedAtMs, at, config.maximumSpeedAgeMs) ||
            lastSpeedFixAtMs?.let { at <= it } == true ||
            (observationId != null && (observationId < 0L ||
                lastSpeedObservationId?.let { observationId <= it } == true))
        ) return
        val locationAccuracy = location.horizontalAccuracyM
        if (location.isMock || locationAccuracy == null || !locationAccuracy.isFinite() ||
            locationAccuracy !in 0.0..config.maximumHorizontalAccuracyM
        ) return
        // Optional legacy metadata stays absent; supplied original facts must be valid before
        // consuming either the timestamp or the provider identity.
        if (location.latitude?.let { !it.isFinite() || it !in -90.0..90.0 } == true ||
            location.longitude?.let { !it.isFinite() || it !in -180.0..180.0 } == true ||
            location.measurementElapsedRealtimeNanos?.let { it < 0L || it / 1_000_000L != at } == true
        ) return
        if (speed != null && (speed.elapsedRealtimeMs != at || !speed.speedMps.isFinite() ||
                speed.speedMps !in 0.0..config.maximumSpeedMps || speed.accuracyMps?.let {
                    it.isFinite() && it in 0.0..config.maximumSpeedAccuracyMps
                } != true)
        ) return
        lastSpeedFixAtMs = at
        // Missing identity must not erase the last known fix's duplicate guard.
        if (observationId != null) lastSpeedObservationId = observationId
        lastSpeed = speed
        lastGnssEvidence = UserMotionEvidenceRef(
            UserMotionEvidenceSource.GNSS_FIX, observationId, at, receivedAtMs,
            locationEvidence = location, speedObservation = speed,
        )
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
        expectedGeneration: Long = generation, expectedOwner: UserMotionStateOwner? = owner,
        stepEventSource: StepEventSource? = null, stepEventConfidence: StepEventConfidence? = null,
        originalStepEvent: StepEvent? = null) {
        if (expectedGeneration != generation || expectedOwner != owner || quality == PdrStepQuality.LOW || stepCount !in 1..config.maximumStepBatchSize ||
            !fresh(receivedAtMs, measuredAtMs, config.maximumStepAgeMs) ||
            lastStepAtMs?.let { measuredAtMs <= it } == true
        ) return
        if (originalStepEvent != null && (!originalStepEvent.isConsistentWith(
                stepCount, measuredAtMs, receivedAtMs, stepEventSource, stepEventConfidence,
            ) || !originalStepEvent.supportsPdrQuality(quality))
        ) return
        consecutiveStepEvents = if (lastStepAtMs?.let {
                measuredAtMs - it <= config.maximumWalkingStepGapMs
            } == true
        ) (consecutiveStepEvents + 1).coerceAtMost(2) else 1
        lastStepAtMs = measuredAtMs
        accelerationQuietSinceMs = null
        // No original event ID is supplied. Source/confidence stay unknown for legacy callers.
        val eventSource = originalStepEvent?.source ?: stepEventSource
        val eventConfidence = originalStepEvent?.confidence ?: stepEventConfidence
        val source = when (eventSource) {
            StepEventSource.STEP_DETECTOR -> UserMotionEvidenceSource.STEP_DETECTOR
            StepEventSource.STEP_COUNTER -> UserMotionEvidenceSource.STEP_COUNTER
            StepEventSource.ACCELEROMETER_FALLBACK -> UserMotionEvidenceSource.ACCELEROMETER_STEP_FALLBACK
            null -> UserMotionEvidenceSource.UNKNOWN_STEP_SOURCE
        }
        lastStepEvidence = UserMotionEvidenceRef(
            source, null, measuredAtMs, receivedAtMs,
            stepEvidence = UserMotionStepEvidence(stepCount, quality, eventSource, eventConfidence, originalStepEvent),
        )
    }

    /** Publishes: advances lastPublished/stateSince. Only the navigation owner should call this. */
    @Synchronized
    fun snapshot(nowMs: Long): UserMotionStateSnapshot {
        if (nowMs < notBeforeElapsedRealtimeMs || lastPublishedAtMs?.let { nowMs < it } == true) {
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
        if (nowMs < notBeforeElapsedRealtimeMs) return emptySnapshot(nowMs, UserMotionStateReason.INVALID_TIME)
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
        val refs = currentEvidenceRefs(nowMs)
        val support = currentSupport(refs, stepAt != null && speedStationary, speedMoving && quietDevice)
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
            owner = owner,
            evidenceHealth = when {
                reason == UserMotionStateReason.NO_EVIDENCE -> UserMotionEvidenceHealth.WARMING_UP
                reason == UserMotionStateReason.EXPIRED_EVIDENCE -> UserMotionEvidenceHealth.EXPIRED
                movement == UserMovementState.UNKNOWN -> UserMotionEvidenceHealth.DEGRADED
                else -> UserMotionEvidenceHealth.VALID
            },
            evidenceRefs = refs,
            reasonCodes = currentReasonCodes(reason, stepAt != null, speedMoving, quietDevice,
                gnssMovementUnconfirmed, support.conflicts),
            support = support,
            mount = if (lastMountObservedAtMs?.let { it <= nowMs } == true) lastMountRelation
                else UserDeviceBodyRelationState.UNKNOWN,
        )
    }

    private fun windowEvidence(source: UserMotionEvidenceSource, window: PedestrianMotionWindowCoverage?,
        receivedAtMs: Long, previous: UserMotionEvidenceRef?, sensorEvidence: UserMotionSensorEvidence): UserMotionEvidenceRef? {
        // Keep only this source's most recent identity across a missing window. Publication below
        // still requires the latest accepted sample to contain that window.
        if (window == null) return previous
        // A later envelope around the identical window is not its first receipt or a new original ID.
        if (previous?.windowCoverage == window) return previous
        return UserMotionEvidenceRef(source, null, window.endedAtElapsedRealtimeMs, receivedAtMs,
            windowCoverage = window, sensorEvidence = sensorEvidence)
    }

    private fun currentEvidenceRefs(nowMs: Long): List<UserMotionEvidenceRef> {
        val sample = lastMotion?.sample
        val refs = listOfNotNull(lastGnssEvidence,
            lastAccelerationEvidence.takeIf { sample?.accelerationWindow != null },
            lastGyroscopeEvidence.takeIf { sample?.gyroscopeWindow != null }, lastStepEvidence)
            .filter { ref ->
                val maximumAgeMs = when (ref.source) {
                    UserMotionEvidenceSource.GNSS_FIX -> config.maximumSpeedAgeMs
                    UserMotionEvidenceSource.UNKNOWN_STEP_SOURCE, UserMotionEvidenceSource.STEP_DETECTOR,
                    UserMotionEvidenceSource.STEP_COUNTER, UserMotionEvidenceSource.ACCELEROMETER_STEP_FALLBACK ->
                        config.maximumStepAgeMs
                    UserMotionEvidenceSource.ACCELEROMETER_WINDOW, UserMotionEvidenceSource.GYROSCOPE_WINDOW ->
                        minOf(config.maximumMotionAgeMs, MAXIMUM_WINDOW_GAP_MS)
                }
                fresh(nowMs, ref.measuredAtElapsedRealtimeMs, maximumAgeMs)
            }
        return Collections.unmodifiableList(refs)
    }

    private fun currentSupport(refs: List<UserMotionEvidenceRef>, stepWithStationaryGnss: Boolean,
        groundMovementWithQuietDevice: Boolean): UserMotionSupport {
        val conflicts = mutableListOf<UserMotionEvidenceConflict>()
        val gnss = refs.firstOrNull { it.source == UserMotionEvidenceSource.GNSS_FIX }
        if (gnss != null) {
            val step = refs.firstOrNull { it.stepEvidence != null }
            if (stepWithStationaryGnss && step != null) {
                conflicts += UserMotionEvidenceConflict(UserMotionStateReason.STEP_WITH_STATIONARY_GNSS,
                    Collections.unmodifiableList(listOf(step, gnss)))
            }
            val acceleration = refs.firstOrNull { it.source == UserMotionEvidenceSource.ACCELEROMETER_WINDOW }
            if (groundMovementWithQuietDevice && acceleration != null) {
                conflicts += UserMotionEvidenceConflict(UserMotionStateReason.GNSS_MOVEMENT_WITH_QUIET_DEVICE,
                    Collections.unmodifiableList(listOf(acceleration, gnss)))
            }
        }
        return UserMotionSupport(Collections.unmodifiableList(refs.map { UserMotionSourceSupport(it) }),
            Collections.unmodifiableList(conflicts))
    }

    private fun currentReasonCodes(primary: UserMotionStateReason, stepFresh: Boolean, speedMoving: Boolean,
        quietDevice: Boolean, gnssMovementUnconfirmed: Boolean,
        conflicts: List<UserMotionEvidenceConflict>): Set<UserMotionStateReason> {
        val reasons = linkedSetOf(primary)
        if (stepFresh) reasons += UserMotionStateReason.STEP_OBSERVED
        if (speedMoving) {
            reasons += if (gnssMovementUnconfirmed) UserMotionStateReason.GNSS_MOVEMENT_UNCONFIRMED
                else UserMotionStateReason.GNSS_MOVEMENT
            if (quietDevice) reasons += UserMotionStateReason.GNSS_MOVEMENT_WITH_QUIET_DEVICE
        }
        conflicts.forEach { reasons += it.reason }
        return Collections.unmodifiableSet(reasons)
    }

    /** Correlated overlapping windows and the same GNSS speed cannot be independent ZUPTs. */
    @Synchronized
    fun claimZeroVelocityUpdate(measuredAtMs: Long, receivedAtMs: Long,
        expectedOwner: UserMotionStateOwner? = owner): Boolean {
        if (!ownsInput(measuredAtMs, expectedOwner)) return false
        val sample = lastMotion?.sample ?: return false
        if (sample.elapsedRealtimeMs != measuredAtMs) return false
        val state = snapshot(receivedAtMs)
        if (!state.zeroVelocityUpdateAllowed) return false
        val window = sample.accelerationWindow ?: return false
        val speedAt = state.speedObservedAtElapsedRealtimeMs ?: return false
        // A later GNSS speed may support current stillness, but not this historical ZUPT.
        if (speedAt > measuredAtMs) return false
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

    @Synchronized
    fun rejectedOwnerSnapshot(nowMs: Long): UserMotionStateSnapshot =
        emptySnapshot(nowMs, UserMotionStateReason.OWNER_MISMATCH)

    private fun windowBelongsToEpoch(window: PedestrianMotionWindowCoverage?, measuredAtMs: Long,
        lastSourceAtMs: Long?): Boolean = window == null ||
        (window.startedAtElapsedRealtimeMs >= notBeforeElapsedRealtimeMs &&
            window.endedAtElapsedRealtimeMs >= window.startedAtElapsedRealtimeMs &&
            window.endedAtElapsedRealtimeMs <= measuredAtMs &&
            (lastSourceAtMs == null || window.endedAtElapsedRealtimeMs >= lastSourceAtMs))

    private fun emptySnapshot(nowMs: Long, reason: UserMotionStateReason) = UserMotionStateSnapshot(
        generation, UserMovementState.UNKNOWN, UserLocomotionState.UNKNOWN,
        UserDeviceRotationState.UNKNOWN, reason, null, nowMs, null, null, null, null, null, false,
        owner = owner, evidenceHealth = UserMotionEvidenceHealth.DEGRADED,
    )

    private fun fresh(nowMs: Long, atMs: Long, maximumAgeMs: Long): Boolean =
        atMs >= notBeforeElapsedRealtimeMs && nowMs >= atMs && nowMs - atMs <= maximumAgeMs

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

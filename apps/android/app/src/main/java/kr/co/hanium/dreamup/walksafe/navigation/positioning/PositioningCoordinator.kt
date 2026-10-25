package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.StepEventConfidence
import kr.co.hanium.dreamup.walksafe.navigation.StepEvent
import kr.co.hanium.dreamup.walksafe.navigation.StepEventSource
import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.GnssQualityEstimator
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.GnssQualitySnapshot
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.GnssSatelliteSignal
import kotlin.math.sqrt

// One existing GPS recheck interval distinguishes a callback burst from a persistent jump.
private const val CORRUPT_JUMP_BURST_CONFIRMATION_MS = 3_000L

internal data class PositionCoordinate(
    val latitude: Double,
    val longitude: Double,
)

internal data class LocalEnuPosition(
    val eastM: Double,
    val northM: Double,
)

internal data class PositioningRawFix(
    val coordinate: PositionCoordinate,
    val localEnu: LocalEnuPosition?,
    val reportedHorizontalAccuracyM: Double?,
    /** Legacy weighted reported-radius proxy; excludes the model floor/default and soft gate. */
    val effectiveHorizontalAccuracyM: Double?,
    val elapsedRealtimeMs: Long,
    /** Nominal model sigma from this coordinator's estimator/config, before innovation gating. */
    val nominalMeasurementAxisStdDevM: MeasurementAxisStdDevM? = null,
) {
    val reportedAccuracyRadius68M: ReportedAccuracyRadius68M?
        get() = reportedHorizontalAccuracyM?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let(::ReportedAccuracyRadius68M)
}

internal data class PositioningFilteredFix(
    val coordinate: PositionCoordinate,
    val localEnu: LocalEnuPosition,
    /** Posterior maximum-axis standard deviation, in metres. */
    val horizontalUncertaintyM: Double,
    val velocityEastMps: Double,
    val velocityNorthMps: Double,
    val elapsedRealtimeMs: Long,
    /**
     * S09-45: east/north position covariance (m²) of this estimate for direction-aware route matching;
     * [horizontalUncertaintyM] is its largest-axis scalar and stays the fallback.
     */
    val covarianceEnu: EnuPositionCovariance? = null,
) {
    val positionMaxAxisSigmaM: PositionMaxAxisSigmaM?
        get() = horizontalUncertaintyM.takeIf { it.isFinite() && it >= 0.0 }
            ?.let(::PositionMaxAxisSigmaM)
}

internal data class PositioningSnapshot(
    val raw: PositioningRawFix?,
    val filtered: PositioningFilteredFix?,
    val quality: PositionQuality,
    val confidence: PositionConfidenceDecision,
    val gnssQuality: GnssQualitySnapshot,
    val gnssUpdate: PedestrianPositionEstimatorUpdate? = null,
    val motionUpdate: PedestrianMotionEstimatorUpdate? = null,
    /** Selector candidate for diagnostics; [motionUpdate] reports actual step integration admission. */
    val selectedHeading: SelectedPedestrianHeading? = null,
    val selectedHeadingAccuracyDegrees: Double? = null,
    val motionTrigger: PositioningMotionTrigger? = null,
    val filteredAtGnssMeasurement: PositioningFilteredFix? = null,
    val userMotion: UserMotionStateSnapshot? = null,
    val inputGeneration: Long = 0L,
    /**
     * S09-36 follow-up (w25 review pos-F8, Root R15): distinct GNSS measurements refused as CORRUPT_JUMP
     * since the last accepted fix, this one included (0 when [gnssUpdate] is not such a refusal).
     */
    val consecutiveCorruptJumpCount: Int = 0,
    /** Time spanned by the same rejection run, using both measurement and receipt clocks. */
    val consecutiveCorruptJumpDurationMs: Long? = null,
) {
    /**
     * S09-34 (originals/09.txt:94): absolute-position capability. Destination arrival or any absolute
     * claim needs a filtered fix whose quality is usable and whose confidence has not paused guidance
     * (e.g. more than 10 s without informative GNSS).
     */
    val absoluteArrivalConfirmable: Boolean
        get() = filtered != null && quality in setOf(PositionQuality.HIGH, PositionQuality.MEDIUM) &&
            !confidence.guidancePaused && confidence.failureReason == null

    /**
     * S09-34: relative-motion capability, separate from the absolute one: fresh non-LOW step evidence
     * on an initialized filter still tells how far the user just walked while absolute GNSS is
     * unavailable. It never makes an absolute position or an arrival trustworthy.
     */
    val relativeMotionEstimable: Boolean
        get() = filtered != null && userMotion != null && userMotion.generation == inputGeneration &&
            userMotion.stepObservedAtElapsedRealtimeMs != null

    /**
     * S09-36 (originals/09.txt:96): the fix was refused only as a single far outlier against the
     * filter (CORRUPT_JUMP) while the existing estimate is still within its confidence age. Such a
     * fix neither replaces nor invalidates that estimate. Time, mock (spoofing suspicion), coordinate,
     * accuracy, order and numerical rejections, or an expired estimate, stay fail-closed. From the second
     * distinct CORRUPT_JUMP measurement in a row the jump is no longer "single" (Root R15): the caller's
     * existing fail-closed path applies.
     */
    val singleOutlierAgainstFreshEstimate: Boolean
        get() = gnssUpdate?.disposition == GnssObservationDisposition.HARD_REJECTED &&
            gnssUpdate.hardRejectReason == GnssHardRejectReason.CORRUPT_JUMP &&
            consecutiveCorruptJumpCount <= 1 &&
            filtered != null && quality != PositionQuality.UNAVAILABLE && !confidence.guidancePaused

    /**
     * Several fixes from one brief multipath burst are still rejected measurements, not proof
     * that the retained position has failed. Never publish their coordinates or extend its age.
     * A run lasting one GPS recheck interval, an expired estimate, or other rejection stays hard.
     */
    val transientOutlierAgainstFreshEstimate: Boolean
        get() = singleOutlierAgainstFreshEstimate ||
            (gnssUpdate?.disposition == GnssObservationDisposition.HARD_REJECTED &&
                gnssUpdate.hardRejectReason == GnssHardRejectReason.CORRUPT_JUMP &&
                consecutiveCorruptJumpCount > 1 &&
                consecutiveCorruptJumpDurationMs?.let { it in 0L until CORRUPT_JUMP_BURST_CONFIRMATION_MS } == true &&
                filtered != null && quality != PositionQuality.UNAVAILABLE && !confidence.guidancePaused)
}

internal enum class PositioningMotionTrigger {
    STEP,
    ZUPT,
}

/**
 * Serial, Android-free boundary joining the pedestrian positioning components.
 *
 * S09-58 (originals/09.txt:158): the owner of the positioning state is the app's main looper. Every
 * input (location callback, step events posted to the UI thread, motion-sensor and GNSS-status
 * callbacks delivered on the main looper) mutates it there, one at a time; every public method is
 * also synchronized so a stray caller cannot interleave. Off-main callers only use [peekUserMotion],
 * which never publishes. Thread serialization is separate from measurement-time order: the estimator
 * still re-orders late GNSS and late steps/ZUPT by measurement time within bounded replay windows.
 */
internal class PositioningCoordinator(
    private val estimator: PedestrianPositionEstimator = PedestrianPositionEstimator(),
    private val confidencePolicy: PositionConfidencePolicy = PositionConfidencePolicy(),
    private val headingSelector: PedestrianHeadingSelector = PedestrianHeadingSelector(),
    private val motionProfile: PedestrianMotionProfile = PedestrianMotionProfile(),
    private val gnssQualityEstimator: GnssQualityEstimator = GnssQualityEstimator(),
    private val userMotionEstimator: UserMotionStateEstimator = UserMotionStateEstimator(),
) {
    private var localFrame: LocalGeodesy.Frame? = null
    private var lastConfidenceDecision: PositionConfidenceDecision? = null
    private var lastConfidenceSampleAtMs: Long? = null
    private var lastMotionConfidenceSampleAtMs: Long? = null
    private var generation = 0L
    private var currentCalibrationCondition: PedestrianCalibrationCondition? = null
    private var consecutiveCorruptJumpCount = 0
    private var corruptJumpStartedAtMeasurementMs: Long? = null
    private var corruptJumpStartedAtReceiptMs: Long? = null
    private var consecutiveCorruptJumpDurationMs: Long? = null

    /** Identity (measurement time, latitude, longitude) of the last counted CORRUPT_JUMP measurement. */
    private var lastCorruptJumpMeasurement: Triple<Long, Double, Double>? = null

    init {
        applyProfileDynamics(motionProfile.currentSnapshot())
    }

    @Synchronized
    fun currentInputGeneration(): Long = generation

    @Synchronized
    fun currentUserMotionOwner(): UserMotionStateOwner? = userMotionEstimator.currentOwner()

    /** Producer admission before updating heading caches, recording a step, or posting a delayed output. */
    @Synchronized
    fun isUserMotionInputCurrent(measuredAtElapsedRealtimeMs: Long, expectedMotionOwner: UserMotionStateOwner?): Boolean =
        userMotionEstimator.ownsInput(measuredAtElapsedRealtimeMs, expectedMotionOwner)

    /** ADD-MOTION-015/022: retain absolute positioning and callback generation, clear physical motion only. */
    @Synchronized
    fun beginUserMotionEpoch(owner: UserMotionStateOwner, notBeforeElapsedRealtimeMs: Long): Boolean {
        // beginEpoch also accepts an identical boundary. Only a new owner or an advanced
        // accepted boundary clears heading history; repeating the current epoch keeps smoothing.
        val epochChanged = owner != userMotionEstimator.currentOwner() ||
            (notBeforeElapsedRealtimeMs > 0L &&
                userMotionEstimator.ownsInput(notBeforeElapsedRealtimeMs - 1L, owner))
        if (!userMotionEstimator.beginEpoch(owner, notBeforeElapsedRealtimeMs)) return false
        if (epochChanged) headingSelector.reset()
        return true
    }

    @Synchronized
    fun addGnssSignalEpoch(
        elapsedRealtimeNanos: Long,
        signals: List<GnssSatelliteSignal>,
        receivedAtElapsedRealtimeNanos: Long? = null,
        expectedGeneration: Long = generation,
    ): GnssQualitySnapshot {
        if (expectedGeneration != generation || elapsedRealtimeNanos < 0L ||
            receivedAtElapsedRealtimeNanos?.let { it < elapsedRealtimeNanos } == true
        ) return gnssQualityEstimator.snapshot((receivedAtElapsedRealtimeNanos ?: elapsedRealtimeNanos).coerceAtLeast(0L))
        return gnssQualityEstimator.addEpoch(
            elapsedRealtimeNanos, signals, receivedAtElapsedRealtimeNanos = receivedAtElapsedRealtimeNanos,
        )
    }

    @Synchronized
    fun observeGnss(
        observation: GnssPositionObservation,
        expectedGeneration: Long = generation,
        nowElapsedRealtimeMs: Long = observation.receivedAtElapsedRealtimeMs,
    ): PositioningSnapshot {
        if (expectedGeneration != generation) return rejectedSnapshot(nowElapsedRealtimeMs,
            PedestrianMotionHardRejectReason.GENERATION_MISMATCH)
        val gnssQuality = gnssQualityEstimator.snapshot(toElapsedRealtimeNanos(observation.elapsedRealtimeMs))
        // The Android observer may own the signal feed. Both weights describe the same environment,
        // so the stronger one is applied once (also to the missing-accuracy default sigma).
        // An invalid caller weight is passed through so the estimator rejects it without state change.
        val callerNoiseMultiplier = observation.measurementNoiseMultiplier
        val noiseMultiplier = if (callerNoiseMultiplier.isFinite() && callerNoiseMultiplier >= 1.0) {
            maxOf(callerNoiseMultiplier, gnssQuality.measurementNoiseMultiplier)
        } else {
            callerNoiseMultiplier
        }
        val effectiveAccuracyM = observation.horizontalAccuracyM?.let { accuracyM ->
            if (accuracyM.isFinite() && noiseMultiplier.isFinite() && noiseMultiplier >= 1.0) {
                accuracyM * sqrt(noiseMultiplier)
            } else {
                accuracyM
            }
        }
        val adjustedObservation = observation.copy(measurementNoiseMultiplier = noiseMultiplier)
        // Receipt validates the original measurement at the source; consumption evaluates its
        // current capability. A queued callback must not rewind a preceding watchdog evaluation.
        // Keep the original receipt in the estimator so processing cannot legitimize a future fix.
        val update = if (nowElapsedRealtimeMs < 0L || nowElapsedRealtimeMs < observation.receivedAtElapsedRealtimeMs) {
            PedestrianPositionEstimatorUpdate(
                estimate = null,
                disposition = GnssObservationDisposition.HARD_REJECTED,
                hardRejectReason = GnssHardRejectReason.INVALID_TIME,
            )
        } else {
            estimator.observeGnss(adjustedObservation)
        }
        val corruptJumpsInARow = countConsecutiveCorruptJumps(observation, update)

        if (
            localFrame == null &&
            update.disposition != GnssObservationDisposition.HARD_REJECTED &&
            update.estimate != null
        ) {
            localFrame = LocalGeodesy.Frame(observation.latitude, observation.longitude)
        }

        val rawCoordinate = PositionCoordinate(observation.latitude, observation.longitude)
        val raw = PositioningRawFix(
            coordinate = rawCoordinate,
            localEnu = localFrame?.takeIf { validCoordinate(rawCoordinate) }
                ?.toLocal(rawCoordinate.latitude, rawCoordinate.longitude),
            reportedHorizontalAccuracyM = observation.horizontalAccuracyM,
            effectiveHorizontalAccuracyM = effectiveAccuracyM,
            elapsedRealtimeMs = observation.elapsedRealtimeMs,
            nominalMeasurementAxisStdDevM = estimator.nominalGnssAxisStdDevM(adjustedObservation),
        )
        val confidence = when {
            update.disposition != GnssObservationDisposition.HARD_REJECTED && update.estimate != null ->
                observeConfidence(
                    update.estimate.quality,
                    observation.elapsedRealtimeMs,
                    nowElapsedRealtimeMs,
                    informativeGnss = update.estimateAtGnssMeasurement
                        ?.lastInformativeGnssAtElapsedRealtimeMs == observation.elapsedRealtimeMs,
                )
            else -> currentOrUnavailableConfidence(nowElapsedRealtimeMs)
        }
        return snapshot(
            raw = raw,
            estimate = update.estimate,
            confidence = confidence,
            gnssQuality = gnssQuality,
            gnssUpdate = update,
            evaluatedAtElapsedRealtimeMs = nowElapsedRealtimeMs,
        ).copy(consecutiveCorruptJumpCount = corruptJumpsInARow,
            consecutiveCorruptJumpDurationMs = consecutiveCorruptJumpDurationMs.takeIf { corruptJumpsInARow > 0 })
    }

    /**
     * R15: an accepted fix ends a run of CORRUPT_JUMP refusals; the same measurement delivered again is not
     * counted twice. Other refusals neither count nor end the run.
     */
    private fun countConsecutiveCorruptJumps(
        observation: GnssPositionObservation,
        update: PedestrianPositionEstimatorUpdate,
    ): Int {
        if (update.disposition != GnssObservationDisposition.HARD_REJECTED) {
            consecutiveCorruptJumpCount = 0
            lastCorruptJumpMeasurement = null
            corruptJumpStartedAtMeasurementMs = null
            corruptJumpStartedAtReceiptMs = null
            consecutiveCorruptJumpDurationMs = null
            return 0
        }
        if (update.hardRejectReason != GnssHardRejectReason.CORRUPT_JUMP) return 0
        val measurement = Triple(observation.elapsedRealtimeMs, observation.latitude, observation.longitude)
        if (corruptJumpStartedAtMeasurementMs == null) {
            corruptJumpStartedAtMeasurementMs = observation.elapsedRealtimeMs
            corruptJumpStartedAtReceiptMs = observation.receivedAtElapsedRealtimeMs
        }
        consecutiveCorruptJumpDurationMs = maxOf(
            observation.elapsedRealtimeMs - requireNotNull(corruptJumpStartedAtMeasurementMs),
            observation.receivedAtElapsedRealtimeMs - requireNotNull(corruptJumpStartedAtReceiptMs),
        )
        if (measurement != lastCorruptJumpMeasurement) {
            consecutiveCorruptJumpCount += 1
            lastCorruptJumpMeasurement = measurement
        }
        return consecutiveCorruptJumpCount
    }

    @Synchronized
    fun observeStep(
        stepCount: Int,
        headingInput: PedestrianHeadingInput,
        quality: PdrStepQuality,
        /** Null derives the sigma from the profile state (uncalibrated, calibrated, unknown condition). */
        stepLengthSigmaM: Double? = null,
        receivedAtElapsedRealtimeMs: Long = headingInput.nowElapsedRealtimeMs,
        expectedGeneration: Long = generation,
        headingCoversWholeBatch: Boolean = false,
        expectedMotionOwner: UserMotionStateOwner? = userMotionEstimator.currentOwner(),
        stepEventSource: StepEventSource? = null,
        stepEventConfidence: StepEventConfidence? = null,
        originalStepEvent: StepEvent? = null,
    ): PositioningSnapshot {
        if (expectedGeneration != generation) return rejectedSnapshot(receivedAtElapsedRealtimeMs,
            PedestrianMotionHardRejectReason.GENERATION_MISMATCH)
        val measuredAtMs = headingInput.nowElapsedRealtimeMs
        if (expectedMotionOwner != userMotionEstimator.currentOwner()) {
            return rejectedOwnerSnapshot(receivedAtElapsedRealtimeMs)
        }
        if (!userMotionEstimator.ownsInput(measuredAtMs, expectedMotionOwner)) {
            return rejectedSnapshot(receivedAtElapsedRealtimeMs, PedestrianMotionHardRejectReason.INVALID_TIME)
        }
        if (measuredAtMs < 0L || receivedAtElapsedRealtimeMs < measuredAtMs) {
            return rejectedSnapshot(receivedAtElapsedRealtimeMs, PedestrianMotionHardRejectReason.INVALID_TIME)
        }
        if (originalStepEvent != null && (!originalStepEvent.isConsistentWith(
                stepCount, measuredAtMs, receivedAtElapsedRealtimeMs, stepEventSource, stepEventConfidence,
            ) || !originalStepEvent.supportsPdrQuality(quality))
        ) return rejectedSnapshot(receivedAtElapsedRealtimeMs, PedestrianMotionHardRejectReason.INVALID_STEP)
        if (receivedAtElapsedRealtimeMs - measuredAtMs > 2_000L) {
            return rejectedSnapshot(receivedAtElapsedRealtimeMs, PedestrianMotionHardRejectReason.STALE)
        }
        val profile = motionProfile.currentSnapshot()
        val effectiveStepLengthSigmaM = stepLengthSigmaM ?: stepLengthSigmaFor(profile)
        if (stepCount !in 1..64 || !effectiveStepLengthSigmaM.isFinite() || effectiveStepLengthSigmaM < 0.0) {
            return rejectedSnapshot(receivedAtElapsedRealtimeMs, PedestrianMotionHardRejectReason.INVALID_STEP)
        }
        userMotionEstimator.observeSteps(
            stepCount, quality, headingInput.nowElapsedRealtimeMs, receivedAtElapsedRealtimeMs,
            expectedGeneration, expectedMotionOwner, stepEventSource, stepEventConfidence, originalStepEvent,
        )
        val currentStepEvidence = UserMotionStepEvidence(
            stepCount, quality, originalStepEvent?.source ?: stepEventSource,
            originalStepEvent?.confidence ?: stepEventConfidence, originalStepEvent,
        )
        val userMotionForStep = userMotionEstimator.peek(receivedAtElapsedRealtimeMs)
        val currentStepHasStationaryGnssConflict = userMotionForStep.support.conflicts.any { conflict ->
                conflict.reason == UserMotionStateReason.STEP_WITH_STATIONARY_GNSS &&
                    conflict.evidenceRefs.any { ref ->
                        ref.measuredAtElapsedRealtimeMs == measuredAtMs &&
                            ref.receivedAtElapsedRealtimeMs == receivedAtElapsedRealtimeMs &&
                            ref.stepEvidence == currentStepEvidence
                    } && conflict.evidenceRefs.any { ref ->
                        ref.source == UserMotionEvidenceSource.GNSS_FIX &&
                            ref.measuredAtElapsedRealtimeMs <= measuredAtMs
                    }
            }
        val ownedHeadingInput = headingInput.copy(
            // A declared unsupported mount cannot validate phone direction as travel direction.
            // Later declarations must not retroactively constrain an earlier step measurement.
            phoneForwardMounted = headingInput.phoneForwardMounted &&
                userMotionEstimator.peek(measuredAtMs).mount != UserDeviceBodyRelationState.UNSUPPORTED_OR_CHANGED,
            speed = headingInput.speed?.takeIf {
                userMotionEstimator.ownsInput(it.elapsedRealtimeMs, expectedMotionOwner)
            },
            gpsCourse = headingInput.gpsCourse?.takeIf {
                userMotionEstimator.ownsInput(it.elapsedRealtimeMs, expectedMotionOwner)
            },
            magneticTrueHeading = headingInput.magneticTrueHeading?.takeIf {
                userMotionEstimator.ownsInput(it.elapsedRealtimeMs, expectedMotionOwner)
            },
            magneticTrueHeadingAtGpsCourse = headingInput.magneticTrueHeadingAtGpsCourse?.takeIf {
                userMotionEstimator.ownsInput(it.elapsedRealtimeMs, expectedMotionOwner)
            },
        )
        val selectedHeading = headingSelector.select(ownedHeadingInput)
        val selectedAccuracy = selectedHeading?.accuracyDegrees ?: when (selectedHeading?.source) {
            PedestrianHeadingSource.GPS_COURSE -> ownedHeadingInput.gpsCourse?.accuracyDegrees
            PedestrianHeadingSource.MAGNETIC_TRUE -> ownedHeadingInput.magneticTrueHeading?.accuracyDegrees
            null -> null
        }
        // Gyro evidence reports device rotation, not a replacement body or travel heading.
        // A selector-validated rotation may still make an older absolute course usable.
        val currentTurningGyro = userMotionForStep.takeIf {
            it.deviceRotation == UserDeviceRotationState.TURNING
        }?.evidenceRefs?.firstOrNull {
            it.source == UserMotionEvidenceSource.GYROSCOPE_WINDOW &&
                it.measuredAtElapsedRealtimeMs <= measuredAtMs
        }
        val headingPredatesCurrentDeviceTurn = currentTurningGyro?.let { gyro ->
            selectedHeading?.let { heading ->
                heading.sourceObservationAtElapsedRealtimeMs < gyro.measuredAtElapsedRealtimeMs &&
                    (heading.rotationObservationAtElapsedRealtimeMs ?: heading.sourceObservationAtElapsedRealtimeMs) <
                    gyro.measuredAtElapsedRealtimeMs
            } == true
        } == true
        val withholdDirectedHeading = currentStepHasStationaryGnssConflict || headingPredatesCurrentDeviceTurn
        val update = estimator.observePdrStep(
            PdrStepObservation(
                stepCount = stepCount,
                stepLengthM = profile.stepLengthM,
                stepLengthSigmaM = effectiveStepLengthSigmaM,
                headingDegreesTrueNorth = selectedHeading?.headingDegreesTrueNorth
                    .takeUnless { withholdDirectedHeading },
                headingAccuracyDegrees = selectedAccuracy.takeUnless { withholdDirectedHeading },
                quality = quality,
                elapsedRealtimeMs = headingInput.nowElapsedRealtimeMs,
                receivedAtElapsedRealtimeMs = receivedAtElapsedRealtimeMs,
                headingCoversWholeBatch = headingCoversWholeBatch,
            ),
        )
        return motionSnapshot(
            update = update,
            eventAtMs = headingInput.nowElapsedRealtimeMs,
            motionTrigger = PositioningMotionTrigger.STEP,
            selectedHeading = selectedHeading,
            selectedHeadingAccuracyDegrees = selectedAccuracy,
            receivedAtMs = receivedAtElapsedRealtimeMs,
        )
    }

    @Synchronized
    fun observeZupt(
        observation: ZuptObservation,
        receivedAtElapsedRealtimeMs: Long = observation.receivedAtElapsedRealtimeMs,
        expectedGeneration: Long = generation,
        expectedMotionOwner: UserMotionStateOwner? = userMotionEstimator.currentOwner(),
    ): PositioningSnapshot {
        if (expectedGeneration != generation) return rejectedSnapshot(receivedAtElapsedRealtimeMs,
            PedestrianMotionHardRejectReason.GENERATION_MISMATCH)
        if (expectedMotionOwner != userMotionEstimator.currentOwner()) {
            return rejectedOwnerSnapshot(receivedAtElapsedRealtimeMs)
        }
        if (!userMotionEstimator.ownsInput(observation.elapsedRealtimeMs, expectedMotionOwner)) {
            return rejectedSnapshot(receivedAtElapsedRealtimeMs, PedestrianMotionHardRejectReason.INVALID_TIME)
        }
        if (!observation.velocitySigmaMps.isFinite() || observation.velocitySigmaMps <= 0.0 ||
            observation.elapsedRealtimeMs < 0L || receivedAtElapsedRealtimeMs < observation.elapsedRealtimeMs
        ) return rejectedSnapshot(receivedAtElapsedRealtimeMs, PedestrianMotionHardRejectReason.INVALID_ZUPT)
        val update = if (userMotionEstimator.claimZeroVelocityUpdate(observation.elapsedRealtimeMs, receivedAtElapsedRealtimeMs, expectedMotionOwner)) {
            estimator.observeZupt(observation.copy(receivedAtElapsedRealtimeMs = receivedAtElapsedRealtimeMs))
        } else {
            PedestrianMotionEstimatorUpdate(estimator.estimateAt(receivedAtElapsedRealtimeMs),
                PedestrianMotionUpdateDisposition.HARD_REJECTED, PedestrianMotionHardRejectReason.MOTION_NOT_CONFIRMED)
        }
        return motionSnapshot(
            update = update,
            eventAtMs = observation.elapsedRealtimeMs,
            motionTrigger = PositioningMotionTrigger.ZUPT,
            receivedAtMs = receivedAtElapsedRealtimeMs,
        )
    }

    @Synchronized
    fun observeUserMotion(
        decision: PedestrianStationaryDecision,
        receivedAtElapsedRealtimeMs: Long,
        expectedGeneration: Long = generation,
        expectedMotionOwner: UserMotionStateOwner? = userMotionEstimator.currentOwner(),
    ): UserMotionStateSnapshot {
        if (expectedGeneration != generation) return userMotionEstimator.rejectedGenerationSnapshot(receivedAtElapsedRealtimeMs)
        if (expectedMotionOwner != userMotionEstimator.currentOwner()) {
            return userMotionEstimator.rejectedOwnerSnapshot(receivedAtElapsedRealtimeMs)
        }
        userMotionEstimator.observeMotion(decision, receivedAtElapsedRealtimeMs, expectedGeneration, expectedMotionOwner)
        return userMotionEstimator.snapshot(receivedAtElapsedRealtimeMs)
    }

    @Synchronized
    fun observeUserSpeed(
        speed: WalkingSpeedObservation?,
        evidence: UserMotionLocationEvidence,
        receivedAtElapsedRealtimeMs: Long,
        expectedGeneration: Long = generation,
        expectedMotionOwner: UserMotionStateOwner? = userMotionEstimator.currentOwner(),
    ) = userMotionEstimator.observeSpeed(speed, evidence, receivedAtElapsedRealtimeMs, expectedGeneration, expectedMotionOwner)

    /** Recovery input only: records original steps without PDR displacement or calibration. */
    @Synchronized
    fun observeUserSteps(
        event: StepEvent,
        receivedAtElapsedRealtimeMs: Long,
        expectedGeneration: Long = generation,
        expectedMotionOwner: UserMotionStateOwner? = userMotionEstimator.currentOwner(),
    ): UserMotionStateSnapshot {
        if (expectedGeneration != generation) return userMotionEstimator.rejectedGenerationSnapshot(receivedAtElapsedRealtimeMs)
        if (expectedMotionOwner != userMotionEstimator.currentOwner()) return userMotionEstimator.rejectedOwnerSnapshot(receivedAtElapsedRealtimeMs)
        val quality = when (event.confidence) {
            StepEventConfidence.HIGH -> PdrStepQuality.HIGH
            StepEventConfidence.MEDIUM -> PdrStepQuality.MEDIUM
            StepEventConfidence.LOW -> PdrStepQuality.LOW
        }
        userMotionEstimator.observeSteps(event.deltaSteps, quality, event.timestampMs, receivedAtElapsedRealtimeMs,
            expectedGeneration, expectedMotionOwner, event.source, event.confidence, event)
        return userMotionEstimator.snapshot(receivedAtElapsedRealtimeMs)
    }

    /** Explicit mount input reaches the same snapshots used by current/evaluate motion consumers. */
    @Synchronized
    fun observeUserMount(
        mount: UserDeviceBodyRelationState,
        observedAtElapsedRealtimeMs: Long,
        receivedAtElapsedRealtimeMs: Long,
        expectedGeneration: Long = generation,
        expectedMotionOwner: UserMotionStateOwner? = userMotionEstimator.currentOwner(),
    ): Boolean {
        if (expectedGeneration != generation) return false
        return userMotionEstimator.observeMount(mount, observedAtElapsedRealtimeMs,
            receivedAtElapsedRealtimeMs, expectedGeneration, expectedMotionOwner)
    }

    @Synchronized
    fun currentUserMotion(nowElapsedRealtimeMs: Long): UserMotionStateSnapshot =
        userMotionEstimator.snapshot(nowElapsedRealtimeMs)

    /** ADD-MOTION-045-F1 / AM43-F2: read-only; never publishes (see [UserMotionStateEstimator.peek]). */
    @Synchronized
    fun peekUserMotion(nowElapsedRealtimeMs: Long): UserMotionStateSnapshot =
        userMotionEstimator.peek(nowElapsedRealtimeMs)

    @Synchronized
    fun evaluate(nowElapsedRealtimeMs: Long, expectedGeneration: Long = generation): PositioningSnapshot {
        if (expectedGeneration != generation) return rejectedSnapshot(nowElapsedRealtimeMs,
            PedestrianMotionHardRejectReason.GENERATION_MISMATCH)
        val estimate = estimator.estimateAt(nowElapsedRealtimeMs)
        return snapshot(null, estimate,
            currentOrUnavailableConfidence(nowElapsedRealtimeMs, estimate?.quality),
            gnssQualityEstimator.snapshot(toElapsedRealtimeNanos(nowElapsedRealtimeMs)),
            evaluatedAtElapsedRealtimeMs = nowElapsedRealtimeMs)
    }

    /**
     * S09-40/S09-43: the usage condition the next steps and calibration samples are walked under
     * (step input kind, confirmed mounting). A calibration from a different or unknown condition
     * uses the wider step-length sigma until a calibration under the current condition.
     */
    @Synchronized
    fun setCurrentCalibrationCondition(condition: PedestrianCalibrationCondition?) {
        currentCalibrationCondition = condition?.takeIf { it.isValid() }
    }

    @Synchronized
    fun calibrateProfile(sample: WalkingCalibrationSample): PedestrianMotionCalibrationResult {
        val result = motionProfile.calibrate(sample, currentCalibrationCondition)
        applyProfileDynamics(result.snapshot)
        return result
    }

    @Synchronized
    fun restoreProfile(profile: PersistedPedestrianMotionProfile): Boolean {
        if (!motionProfile.restore(profile)) return false
        applyProfileDynamics(motionProfile.currentSnapshot())
        return true
    }

    @Synchronized
    fun currentProfile(): PedestrianMotionProfileSnapshot = motionProfile.currentSnapshot()

    @Synchronized
    fun snapshotProfileForPersistence(): PersistedPedestrianMotionProfile? =
        motionProfile.snapshotForPersistence()

    @Synchronized
    fun isConfidenceAnnouncementCurrent(token: Long): Boolean = confidencePolicy.isAnnouncementCurrent(token)

    @Synchronized
    fun commitAnnouncementDelivered(token: Long, deliveredAtElapsedRealtimeMs: Long): Boolean {
        if (!confidencePolicy.commitAnnouncementDelivered(token, deliveredAtElapsedRealtimeMs)) return false
        lastConfidenceDecision = confidencePolicy.evaluate(deliveredAtElapsedRealtimeMs)
        return true
    }

    @Synchronized
    fun reset() {
        generation += 1L
        estimator.reset()
        confidencePolicy.reset()
        headingSelector.reset()
        gnssQualityEstimator.clear()
        userMotionEstimator.reset()
        localFrame = null
        lastConfidenceDecision = null
        lastConfidenceSampleAtMs = null
        lastMotionConfidenceSampleAtMs = null
        consecutiveCorruptJumpCount = 0
        lastCorruptJumpMeasurement = null
        corruptJumpStartedAtMeasurementMs = null
        corruptJumpStartedAtReceiptMs = null
        consecutiveCorruptJumpDurationMs = null
        applyProfileDynamics(motionProfile.currentSnapshot())
    }

    private fun motionSnapshot(
        update: PedestrianMotionEstimatorUpdate,
        eventAtMs: Long,
        motionTrigger: PositioningMotionTrigger,
        selectedHeading: SelectedPedestrianHeading? = null,
        selectedHeadingAccuracyDegrees: Double? = null,
        receivedAtMs: Long = eventAtMs,
    ): PositioningSnapshot {
        val confidence = update.estimate?.let { estimate ->
            if (estimate.quality == PositionQuality.HIGH) {
                currentOrUnavailableConfidence(receivedAtMs)
            } else if (estimate.estimatedAtElapsedRealtimeMs > (lastMotionConfidenceSampleAtMs ?: -1L)) {
                observeMotionConfidence(estimate.quality, estimate.estimatedAtElapsedRealtimeMs, receivedAtMs)
            } else {
                currentOrUnavailableConfidence(receivedAtMs)
            }
        } ?: currentOrUnavailableConfidence(receivedAtMs)
        return snapshot(
            raw = null,
            estimate = update.estimate,
            confidence = confidence,
            gnssQuality = gnssQualityEstimator.snapshot(toElapsedRealtimeNanos(eventAtMs)),
            motionUpdate = update,
            selectedHeading = selectedHeading,
            selectedHeadingAccuracyDegrees = selectedHeadingAccuracyDegrees,
            motionTrigger = motionTrigger,
            evaluatedAtElapsedRealtimeMs = receivedAtMs,
        )
    }

    private fun rejectedOwnerSnapshot(nowMs: Long): PositioningSnapshot =
        rejectedSnapshot(nowMs, PedestrianMotionHardRejectReason.GENERATION_MISMATCH)
            .copy(userMotion = userMotionEstimator.rejectedOwnerSnapshot(nowMs))

    private fun rejectedSnapshot(nowMs: Long, reason: PedestrianMotionHardRejectReason): PositioningSnapshot =
        PositioningSnapshot(raw = null, filtered = null, quality = PositionQuality.UNAVAILABLE,
            confidence = PositionConfidenceDecision(PositionQuality.UNAVAILABLE, null,
                PositionConfidenceTransition.FAIL_CLOSED, true, null, 0),
            gnssQuality = gnssQualityEstimator.snapshot(toElapsedRealtimeNanos(nowMs)),
            motionUpdate = PedestrianMotionEstimatorUpdate(null, PedestrianMotionUpdateDisposition.HARD_REJECTED, reason),
            userMotion = userMotionEstimator.rejectedGenerationSnapshot(nowMs), inputGeneration = generation)

    private fun snapshot(
        raw: PositioningRawFix?,
        estimate: EstimatedLocation?,
        confidence: PositionConfidenceDecision,
        gnssQuality: GnssQualitySnapshot,
        gnssUpdate: PedestrianPositionEstimatorUpdate? = null,
        motionUpdate: PedestrianMotionEstimatorUpdate? = null,
        selectedHeading: SelectedPedestrianHeading? = null,
        selectedHeadingAccuracyDegrees: Double? = null,
        motionTrigger: PositioningMotionTrigger? = null,
        evaluatedAtElapsedRealtimeMs: Long,
    ): PositioningSnapshot {
        fun filteredFix(estimated: EstimatedLocation?): PositioningFilteredFix? = estimated?.let {
            localFrame?.let { frame ->
                val enu = frame.toLocal(estimated.latitude, estimated.longitude)
                val coordinate = frame.toCoordinate(enu.eastM, enu.northM)
                PositioningFilteredFix(
                    coordinate = coordinate,
                    localEnu = enu,
                    horizontalUncertaintyM = estimated.horizontalUncertaintyM,
                    velocityEastMps = estimated.velocityEastMps,
                    velocityNorthMps = estimated.velocityNorthMps,
                    elapsedRealtimeMs = estimated.estimatedAtElapsedRealtimeMs,
                    covarianceEnu = estimated.positionCovarianceEnu,
                )
            }
        }
        return PositioningSnapshot(
            raw = raw,
            filtered = filteredFix(estimate),
            quality = estimate?.quality ?: PositionQuality.UNAVAILABLE,
            confidence = confidence,
            gnssQuality = gnssQuality,
            gnssUpdate = gnssUpdate,
            motionUpdate = motionUpdate,
            selectedHeading = selectedHeading,
            selectedHeadingAccuracyDegrees = selectedHeadingAccuracyDegrees,
            motionTrigger = motionTrigger,
            filteredAtGnssMeasurement = filteredFix(gnssUpdate?.estimateAtGnssMeasurement),
            userMotion = userMotionEstimator.snapshot(evaluatedAtElapsedRealtimeMs),
            inputGeneration = generation,
        )
    }

    private fun observeConfidence(
        quality: PositionQuality,
        sampleAtMs: Long,
        nowMs: Long,
        informativeGnss: Boolean = true,
    ): PositionConfidenceDecision {
        val decision = confidencePolicy.observe(quality, sampleAtMs, nowMs, informativeGnss)
        lastConfidenceDecision = decision
        if (sampleAtMs >= 0L) lastConfidenceSampleAtMs = maxOf(lastConfidenceSampleAtMs ?: -1L, sampleAtMs)
        return decision
    }

    private fun currentOrUnavailableConfidence(
        nowMs: Long,
        observedQuality: PositionQuality? = null,
    ): PositionConfidenceDecision =
        confidencePolicy.evaluate(nowMs, observedQuality).also { lastConfidenceDecision = it }

    private fun observeMotionConfidence(
        quality: PositionQuality,
        sampleAtMs: Long,
        nowMs: Long,
    ): PositionConfidenceDecision {
        val decision = confidencePolicy.observeMotion(quality, sampleAtMs, nowMs)
        lastConfidenceDecision = decision
        if (sampleAtMs >= 0L) lastMotionConfidenceSampleAtMs = maxOf(lastMotionConfidenceSampleAtMs ?: -1L, sampleAtMs)
        return decision
    }

    /**
     * Per-step length sigma by profile state (S09-40). The calibrated value is the former fixed
     * default; the general-average default step, a record of unknown calibration time, and a
     * calibration whose usage condition differs from the current one (S09-43) use a wider,
     * provisional sigma until logged step residuals calibrate these values.
     */
    private fun stepLengthSigmaFor(profile: PedestrianMotionProfileSnapshot): Double =
        if (profile.isCalibrated && profile.calibrationConditionKnown &&
            profile.calibrationCondition?.stepInput != null &&
            profile.calibrationCondition.mounting != null &&
            profile.calibrationCondition == currentCalibrationCondition
        ) {
            CALIBRATED_STEP_LENGTH_SIGMA_M
        } else {
            UNVERIFIED_STEP_LENGTH_SIGMA_M
        }

    private fun applyProfileDynamics(profile: PedestrianMotionProfileSnapshot) {
        estimator.setPedestrianDynamics(
            PedestrianDynamics(
                processAccelerationSigmaMps2 = profile.walkingProcessAccelerationSigmaMps2,
                averageWalkingSpeedMps = profile.averageWalkingSpeedMps,
            ),
        )
    }

    private fun toElapsedRealtimeNanos(elapsedRealtimeMs: Long): Long = when {
        elapsedRealtimeMs <= 0L -> 0L
        elapsedRealtimeMs > Long.MAX_VALUE / NANOS_PER_MILLISECOND -> Long.MAX_VALUE
        else -> elapsedRealtimeMs * NANOS_PER_MILLISECOND
    }

    private fun validCoordinate(coordinate: PositionCoordinate): Boolean =
        coordinate.latitude.isFinite() && coordinate.latitude in -90.0..90.0 &&
            coordinate.longitude.isFinite() && coordinate.longitude in -180.0..180.0

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val CALIBRATED_STEP_LENGTH_SIGMA_M = 0.08
        const val UNVERIFIED_STEP_LENGTH_SIGMA_M = 0.15
    }
}

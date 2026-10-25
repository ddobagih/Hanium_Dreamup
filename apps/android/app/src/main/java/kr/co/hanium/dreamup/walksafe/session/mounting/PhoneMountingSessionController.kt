package kr.co.hanium.dreamup.walksafe.session.mounting

import java.util.Locale
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentEvidenceStore
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentMountingObservationPort
import kr.co.hanium.dreamup.walksafe.session.*

internal interface PositioningMountBridge<Target, Receipt> {
    fun captureTarget(): Target?
    /** Caller holds the original observation lock; the Positioning owner evaluates checkRequest=null. */
    fun captureLocked(target: Target?, epoch: WalkRuntimeEpoch, observedAtMs: Long,
        phase: PhoneMountingAssessmentPhase, previousState: PhoneMountingRuntimeState?,
        cameraOverride: CameraFrameQualityAssessment?, retryRequested: Boolean,
        facts: MountingPositionFacts): Receipt?
    fun invalidatedLocked(target: Target, generation: Long, observedAtMs: Long): Receipt
    fun consume(receipt: Receipt?, receivedAtMs: Long)
}

internal fun interface MountingPreflightEvidence {
    fun resetCameraObservations()
}

internal interface MountingSensorLifecycle {
    fun start(): Boolean
    fun stop()
}

internal interface MountingScheduler {
    fun postMounting(action: () -> Unit, delayMs: Long): Boolean
    fun postStatus(delayMs: Long, action: () -> Unit): Boolean
}

/** UI/output effects stay with their actual owners; none exposes mutable Main fields. */
internal interface MountingEffects {
    /** Invoked under observationLock, preserving observation-lock -> upload-safety-lock ordering. */
    fun revokeReportOutputs()
    fun updateMountingUi()
    fun observeUnavailableCamera(epoch: WalkRuntimeEpoch, observedAtMs: Long)
    fun sensorStartFailed()
    fun cameraHandoffStarted()
    fun reassessOfficialEnvironment()
    fun cameraHandoffTimedOut()
    fun correction(assessment: PhoneMountingAssessment, announce: Boolean)
    fun unusable(assessment: PhoneMountingAssessment, announce: Boolean)
    fun invalidated(reason: String)
}

/**
 * One mount session owns confirmation identity, camera sequences, output leases and retry tickets.
 * R2 camera observer, raw quality gates and Positioning state are composed, never duplicated.
 * Constructor is inert; all live suppliers are read only at the original operation point.
 */
internal class PhoneMountingSessionController<Target, Receipt>(
    val observationLock: Any,
    private val evidence: OfficialEnvironmentEvidenceStore,
    private val preflight: MountingPreflightEvidence,
    private val currentLifecycle: () -> WalkSessionLifecycle,
    private val profile: () -> ApprovedPhoneMountingProfile?,
    private val cameraEnabled: () -> Boolean,
    private val bypass: () -> Boolean,
    private val nativeCheck: (WalkRuntimeEpoch) -> PhoneMountingCheckRequest?,
    private val sensor: MountingSensorLifecycle,
    private val positioning: PositioningMountBridge<Target, Receipt>,
    private val scheduler: MountingScheduler,
    private val effects: MountingEffects,
    private val clock: () -> Long,
    private val handoffTimeoutMs: Long,
) : OfficialEnvironmentMountingObservationPort {
    @Volatile private var phoneMountingUserConfirmation: PhoneMountingUserConfirmation? = null
    private var explicitPositioningMountConfirmation: PhoneMountingUserConfirmation? = null
    @Volatile private var latestPhoneMountingCameraAssessment: CameraFrameQualityAssessment? = null
    @Volatile private var phoneMountingRuntimeState: PhoneMountingRuntimeState? = null
    @Volatile private var phoneMountingOutputsAllowed = false
    @Volatile private var phoneMountingObservationGeneration = 0L
    private var phoneMountingObservationSequence = 0L
    private var phoneMountingPendingFaultSequence = 0L
    private var phoneMountingAppliedFaultSequence = 0L
    private var phoneMountingWatchdogGeneration = 0L
    private var phoneMountingRuntimeRetryGeneration = 0L
    private var phoneMountingRuntimeRetryArmedAtElapsedRealtimeMs: Long? = null
    @Volatile private var runtimeCameraHandoffEpoch: WalkRuntimeEpoch? = null
    @Volatile private var runtimeCameraHandoffStartedAtElapsedRealtimeMs: Long? = null
    private var runtimeCameraHandoffGeneration = 0L

    init { require(evidence.observationLock === observationLock) }

    val confirmation: PhoneMountingUserConfirmation? get() = phoneMountingUserConfirmation
    val explicitConfirmation: PhoneMountingUserConfirmation? get() = explicitPositioningMountConfirmation
    val runtimeState: PhoneMountingRuntimeState? get() = phoneMountingRuntimeState
    val outputsAllowed: Boolean get() = phoneMountingOutputsAllowed
    val handoffEpoch: WalkRuntimeEpoch? get() = runtimeCameraHandoffEpoch
    val handoffStartedAt: Long? get() = runtimeCameraHandoffStartedAtElapsedRealtimeMs
    private val activePhoneMountingProfile get() = profile()
    private val developmentGuidanceStartBypassEnabled get() = bypass()
    private fun cameraAnalysisFeaturesEnabled() = cameraEnabled()
    private fun lifecycle() = currentLifecycle()

    /** Under original observationLock; the references are shallow, including explicit identity. */
    fun positioningFacts() = MountingPositionFacts(phoneMountingObservationGeneration,
        phoneMountingUserConfirmation, explicitPositioningMountConfirmation, latestPhoneMountingCameraAssessment)

    fun confirmExplicit(epoch: WalkRuntimeEpoch, method: PhoneMountingMethod, activeCorrection: Boolean): Pair<Long, Receipt?> {
        val target = positioning.captureTarget()
        return synchronized(observationLock) {
            val value = clock()
            val confirmation = PhoneMountingUserConfirmation(epoch, value, method, activeCorrection)
            phoneMountingUserConfirmation = confirmation
            explicitPositioningMountConfirmation = confirmation
            if (activeCorrection) {
                phoneMountingRuntimeRetryArmedAtElapsedRealtimeMs = value
                phoneMountingAppliedFaultSequence = maxOf(phoneMountingAppliedFaultSequence, phoneMountingPendingFaultSequence)
            }
            value to capturePositioningObservationLocked(target, epoch, value,
                if (activeCorrection) PhoneMountingAssessmentPhase.ACTIVE else PhoneMountingAssessmentPhase.PREFLIGHT,
                phoneMountingRuntimeState)
        }
    }

    fun consumeConfirmation(receipt: Receipt?) { positioning.consume(receipt, clock()) }

    fun assumeInitialConfirmation(epoch: WalkRuntimeEpoch) {
        val target = positioning.captureTarget()
        val observation = synchronized(observationLock) {
            val assumedAtMs = clock()
            explicitPositioningMountConfirmation = null
            phoneMountingUserConfirmation = PhoneMountingUserConfirmation(epoch, assumedAtMs, PhoneMountingMethod.CHEST_FORWARD, false)
            capturePositioningObservationLocked(target, epoch, assumedAtMs, PhoneMountingAssessmentPhase.PREFLIGHT, phoneMountingRuntimeState)
        }
        positioning.consume(observation, clock())
    }

    /** Caller already admitted the new motion owner, and holds this same observation lock. */
    fun retireExplicitForChangedMotionOwner() { explicitPositioningMountConfirmation = null }
    /** Caller admitted an actor/profile-owner replacement under the original observation lock. */
    fun retireExplicitForChangedActor() { explicitPositioningMountConfirmation = null }
    /** R2 evidence store invokes this in the original shared continuation transaction, not a new sensor sample. */
    fun retainCameraAssessmentForContinuation(assessment: CameraFrameQualityAssessment) { latestPhoneMountingCameraAssessment = assessment }

    fun resetPrewalkCameraEvidenceForNewAttempt() {
        synchronized(observationLock) {
            evidence.resetRuntimeRawCameraAssessment()
            phoneMountingObservationGeneration += 1L
            preflight.resetCameraObservations()
            latestPhoneMountingCameraAssessment = null
            phoneMountingObservationSequence = 0L
            phoneMountingPendingFaultSequence = 0L
            phoneMountingAppliedFaultSequence = 0L
        }
        evidence.clearCameraEvidence()
    }

    fun invalidatePhoneMountingEvidence(reason: String) {
        val target = positioning.captureTarget()
        val invalidatedAtMs = clock()
        cancelRuntimeCameraHandoff()
        val observation = synchronized(observationLock) {
            evidence.resetRuntimeRawCameraAssessment()
            phoneMountingObservationGeneration += 1L
            latestPhoneMountingCameraAssessment = null
            phoneMountingObservationSequence = 0L
            phoneMountingPendingFaultSequence = 0L
            phoneMountingAppliedFaultSequence = 0L
            phoneMountingOutputsAllowed = false
            phoneMountingRuntimeRetryGeneration += 1L
            phoneMountingRuntimeRetryArmedAtElapsedRealtimeMs = null
            phoneMountingUserConfirmation = null
            explicitPositioningMountConfirmation = null
            target?.let { positioning.invalidatedLocked(it, phoneMountingObservationGeneration, invalidatedAtMs) }
        }
        positioning.consume(observation, clock())
        phoneMountingWatchdogGeneration += 1L
        phoneMountingRuntimeState = null
        effects.invalidated(reason)
    }

    fun activatePhoneMountingRuntime(): Boolean {
        if (developmentGuidanceStartBypassEnabled) { phoneMountingOutputsAllowed = true; return true }
        val epoch = lifecycle().currentRuntimeEpochOrNull() ?: return false
        if (!cameraAnalysisFeaturesEnabled()) {
            cancelRuntimeCameraHandoff()
            synchronized(observationLock) {
                phoneMountingRuntimeState = null
                latestPhoneMountingCameraAssessment = null
                phoneMountingObservationGeneration += 1L
                phoneMountingObservationSequence = 0L
                phoneMountingPendingFaultSequence = 0L
                phoneMountingAppliedFaultSequence = 0L
                phoneMountingOutputsAllowed = true
                phoneMountingRuntimeRetryGeneration += 1L
                phoneMountingRuntimeRetryArmedAtElapsedRealtimeMs = null
            }
            phoneMountingWatchdogGeneration += 1L
            sensor.stop()
            return true
        }
        if (!sensor.start()) {
            phoneMountingRuntimeState = PhoneMountingPolicy.initialState(epoch, activePhoneMountingProfile)
            effects.observeUnavailableCamera(epoch, clock())
            effects.sensorStartFailed()
            return lifecycle().isRuntimeEpochCurrent(epoch)
        }
        val initialState = PhoneMountingPolicy.initialState(epoch, activePhoneMountingProfile)
        val target = positioning.captureTarget()
        val (assessment, observation) = synchronized(observationLock) {
            phoneMountingRuntimeState = initialState
            val evaluated = currentPhoneMountingAssessment(epoch = epoch, phase = PhoneMountingAssessmentPhase.ACTIVE, previousState = initialState)
            val captured = evaluated.assessedAtElapsedRealtimeMs?.let {
                capturePositioningObservationLocked(target, epoch, it, PhoneMountingAssessmentPhase.ACTIVE, initialState)
            }
            phoneMountingRuntimeState = evaluated.nextState
            evaluated to captured
        }
        applyPhoneMountingRuntimeAssessment(assessment, assessment.status == PhoneMountingStatus.CORRECTION_REQUIRED, false, observation)
        return lifecycle().isRuntimeEpochCurrent(epoch)
    }

    fun beginRuntimeCameraHandoff(epoch: WalkRuntimeEpoch) {
        if (developmentGuidanceStartBypassEnabled) { phoneMountingOutputsAllowed = true; return }
        val startedAtMs = clock()
        val generation = synchronized(observationLock) {
            runtimeCameraHandoffEpoch = epoch
            runtimeCameraHandoffStartedAtElapsedRealtimeMs = startedAtMs
            evidence.clearCameraEvidence()
            latestPhoneMountingCameraAssessment = null
            evidence.resetRuntimeRawCameraAssessment()
            phoneMountingOutputsAllowed = false
            phoneMountingObservationSequence = 0L
            phoneMountingPendingFaultSequence = 0L
            phoneMountingAppliedFaultSequence = 0L
            runtimeCameraHandoffGeneration += 1L
            runtimeCameraHandoffGeneration
        }
        // Official watchdog invalidation precedes mounting watchdog invalidation in the original.
        effects.cameraHandoffStarted()
        phoneMountingWatchdogGeneration += 1L
        effects.reassessOfficialEnvironment()
        scheduler.postStatus(handoffTimeoutMs) {
            val timedOut = synchronized(observationLock) {
                if (generation != runtimeCameraHandoffGeneration || runtimeCameraHandoffEpoch != epoch || !lifecycle().isRuntimeEpochCurrent(epoch)) false
                else {
                    runtimeCameraHandoffEpoch = null
                    runtimeCameraHandoffStartedAtElapsedRealtimeMs = null
                    runtimeCameraHandoffGeneration += 1L
                    true
                }
            }
            if (!timedOut) return@postStatus
            effects.observeUnavailableCamera(epoch, clock())
            effects.cameraHandoffTimedOut()
        }
    }

    fun cancelRuntimeCameraHandoff(expectedEpoch: WalkRuntimeEpoch? = null) = synchronized(observationLock) {
        if (expectedEpoch != null && runtimeCameraHandoffEpoch != expectedEpoch) return@synchronized
        runtimeCameraHandoffEpoch = null
        runtimeCameraHandoffStartedAtElapsedRealtimeMs = null
        runtimeCameraHandoffGeneration += 1L
    }

    fun revokeReportOutputLease() {
        if (developmentGuidanceStartBypassEnabled) return
        synchronized(observationLock) {
            phoneMountingOutputsAllowed = false
            effects.revokeReportOutputs()
        }
    }

    fun applyPhoneMountingRuntimeAssessment(assessment: PhoneMountingAssessment, announceCorrection: Boolean,
        outputsAlreadyRevoked: Boolean, positioningMountObservation: Receipt? = null) {
        positioningMountObservation?.let { positioning.consume(it, clock()) }
        if (developmentGuidanceStartBypassEnabled) { phoneMountingOutputsAllowed = true; return }
        val epoch = assessment.nextState.epoch
        if (!lifecycle().isRuntimeEpochCurrent(epoch)) return
        if (assessment.status != PhoneMountingStatus.SUITABLE && !outputsAlreadyRevoked) revokeReportOutputLease()
        when (assessment.status) {
            PhoneMountingStatus.SUITABLE -> {
                val enabled = synchronized(observationLock) {
                    if (phoneMountingPendingFaultSequence > phoneMountingAppliedFaultSequence) false
                    else { phoneMountingOutputsAllowed = true; true }
                }
                if (!enabled) return
                schedulePhoneMountingEvidenceWatchdog(epoch)
                effects.updateMountingUi()
            }
            PhoneMountingStatus.CORRECTION_REQUIRED -> {
                phoneMountingOutputsAllowed = false
                phoneMountingWatchdogGeneration += 1L
                effects.correction(assessment, announceCorrection)
                effects.updateMountingUi()
            }
            PhoneMountingStatus.UNUSABLE -> {
                phoneMountingOutputsAllowed = false
                effects.unusable(assessment, announceCorrection)
                effects.updateMountingUi()
            }
        }
    }

    private fun capturePositioningObservationLocked(target: Target?, epoch: WalkRuntimeEpoch, observedAtElapsedRealtimeMs: Long,
        phase: PhoneMountingAssessmentPhase, previousState: PhoneMountingRuntimeState?,
        cameraFrameQualityOverride: CameraFrameQualityAssessment? = null, runtimeRetryRequested: Boolean = false): Receipt? =
        positioning.captureLocked(target, epoch, observedAtElapsedRealtimeMs, phase, previousState,
            cameraFrameQualityOverride, runtimeRetryRequested, positioningFacts())

    override fun observationGeneration() = phoneMountingObservationGeneration
    override fun cameraHandoffStartedAt(epoch: WalkRuntimeEpoch) = runtimeCameraHandoffStartedAtElapsedRealtimeMs.takeIf { runtimeCameraHandoffEpoch == epoch }
    override fun completeCameraHandoffIfCurrent(epoch: WalkRuntimeEpoch) {
        if (runtimeCameraHandoffEpoch == epoch) {
            runtimeCameraHandoffEpoch = null
            runtimeCameraHandoffStartedAtElapsedRealtimeMs = null
            runtimeCameraHandoffGeneration += 1L
        }
    }
    override fun latestCameraAssessment() = latestPhoneMountingCameraAssessment
    override fun commitCameraAssessment(epoch: WalkRuntimeEpoch, assessment: CameraFrameQualityAssessment,
        stability: CameraPreflightStabilityDecision, mountingFault: Boolean): Long {
        phoneMountingObservationSequence += 1L
        val sequence = phoneMountingObservationSequence
        val latestAt = latestPhoneMountingCameraAssessment?.observedAtElapsedRealtimeMs
        val currentAt = assessment.observedAtElapsedRealtimeMs
        if (stability != CameraPreflightStabilityDecision.RETAIN_PREVIOUS_PASS && currentAt != null && (latestAt == null || currentAt >= latestAt)) latestPhoneMountingCameraAssessment = assessment
        if (mountingFault && phoneMountingRuntimeState?.epoch == epoch) {
            phoneMountingOutputsAllowed = false
            val armedAt = phoneMountingRuntimeRetryArmedAtElapsedRealtimeMs
            if (armedAt != null && currentAt != null && currentAt <= armedAt) phoneMountingAppliedFaultSequence = maxOf(phoneMountingAppliedFaultSequence, sequence)
            else phoneMountingPendingFaultSequence = maxOf(phoneMountingPendingFaultSequence, sequence)
        }
        return sequence
    }
    override fun runtimeRetryArmedAt() = phoneMountingRuntimeRetryArmedAtElapsedRealtimeMs
    override fun applyRuntimeCameraAssessment(retryRequested: Boolean, assessment: CameraFrameQualityAssessment,
        sequence: Long, generation: Long, isFault: Boolean) = applyCurrentPhoneMountingRuntimeAssessment(retryRequested, assessment, sequence, generation, isFault) != null
    override fun finishRuntimeRetryIfCurrent(armedAtMs: Long?) {
        if (phoneMountingRuntimeRetryArmedAtElapsedRealtimeMs == armedAtMs) {
            phoneMountingRuntimeRetryArmedAtElapsedRealtimeMs = null
            phoneMountingRuntimeRetryGeneration += 1L
        }
    }
    override fun recordAppliedFaultIfCurrent(generation: Long, sequence: Long) {
        if (phoneMountingObservationGeneration == generation) phoneMountingAppliedFaultSequence = maxOf(phoneMountingAppliedFaultSequence, sequence)
    }

    fun currentPhoneMountingAssessment(
        epoch: WalkRuntimeEpoch = lifecycle().snapshot().epoch,
        nowElapsedRealtimeMs: Long = clock(),
        phase: PhoneMountingAssessmentPhase = PhoneMountingAssessmentPhase.PREFLIGHT,
        previousState: PhoneMountingRuntimeState? = null,
        cameraFrameQualityOverride: CameraFrameQualityAssessment? = null,
        runtimeRetryRequested: Boolean = false,
    ): PhoneMountingAssessment {
        val cameraFrameQuality =
            cameraFrameQualityOverride ?: latestPhoneMountingCameraAssessment
        return PhoneMountingPolicy.assess(
            phase = phase,
            currentEpoch = epoch,
            nowElapsedRealtimeMs = nowElapsedRealtimeMs,
            userConfirmation = phoneMountingUserConfirmation,
            cameraFrameQuality = cameraFrameQuality,
            previousState = previousState ?: PhoneMountingPolicy.initialState(
                epoch = epoch,
                approvedProfile = activePhoneMountingProfile,
            ),
            runtimeRetryRequested = runtimeRetryRequested,
            checkRequest = nativeCheck(epoch),
        )
    }

    fun phoneMountingReadiness(
        epoch: WalkRuntimeEpoch,
        assessmentOverride: PhoneMountingAssessment? = null,
    ): Pair<WalkSessionReadinessStatus, String> {
        if (developmentGuidanceStartBypassEnabled) return WalkSessionReadinessStatus.READY to ""
        if (!cameraAnalysisFeaturesEnabled()) {
            return WalkSessionReadinessStatus.READY to ""
        }
        val snapshot = lifecycle().snapshot()
        val runtimeState = phoneMountingRuntimeState
        if (
            snapshot.state == WalkSessionState.ACTIVE &&
            runtimeState != null &&
            runtimeState.epoch == epoch &&
            lifecycle().isRuntimeEpochCurrent(epoch)
        ) {
            // A mounting fault restricts camera outputs; GPS guidance has its own gate.
            return WalkSessionReadinessStatus.READY to ""
        }
        val assessment = assessmentOverride ?: currentPhoneMountingAssessment(epoch)
        if (activePhoneMountingProfile == null) {
            return WalkSessionReadinessStatus.UNAVAILABLE to
                "phone_mounting:approved_profile_unavailable"
        }
        return when (assessment.status) {
            PhoneMountingStatus.SUITABLE ->
                WalkSessionReadinessStatus.READY to ""
            PhoneMountingStatus.CORRECTION_REQUIRED ->
                WalkSessionReadinessStatus.PENDING to
                    "phone_mounting:${assessment.reason.name.lowercase(Locale.US)}"
            PhoneMountingStatus.UNUSABLE ->
                WalkSessionReadinessStatus.UNAVAILABLE to
                    "phone_mounting:${assessment.reason.name.lowercase(Locale.US)}"
        }
    }

    fun applyCurrentPhoneMountingRuntimeAssessment(
        runtimeRetryRequested: Boolean,
        cameraFrameQualityOverride: CameraFrameQualityAssessment? = null,
        observationSequence: Long? = null,
        observationGeneration: Long? = null,
        observationIsFault: Boolean = false,
    ): PhoneMountingAssessment? {
        val previousState = phoneMountingRuntimeState ?: return null
        if (!lifecycle().isRuntimeEpochCurrent(previousState.epoch)) return null
        val mountTarget = positioning.captureTarget()
        val transition = synchronized(observationLock) {
            if (phoneMountingRuntimeState !== previousState) return@synchronized null
            if (observationSequence != null) {
                if (
                    observationGeneration != phoneMountingObservationGeneration ||
                    (
                        observationIsFault &&
                            observationSequence <= phoneMountingAppliedFaultSequence
                    ) ||
                    (
                        !observationIsFault &&
                            (
                                phoneMountingPendingFaultSequence >
                                    phoneMountingAppliedFaultSequence ||
                                    observationSequence <=
                                    phoneMountingAppliedFaultSequence
                            )
                    )
                ) {
                    return@synchronized null
                }
            }
            val assessment = currentPhoneMountingAssessment(
                epoch = previousState.epoch,
                phase = PhoneMountingAssessmentPhase.ACTIVE,
                previousState = previousState,
                cameraFrameQualityOverride = cameraFrameQualityOverride,
                runtimeRetryRequested = runtimeRetryRequested,
            )
            val firstFault =
                previousState.correctionRequiredSinceElapsedRealtimeMs == null &&
                    assessment.nextState.correctionRequiredSinceElapsedRealtimeMs != null
            val outputsRevoked = assessment.status != PhoneMountingStatus.SUITABLE
            if (outputsRevoked) revokeReportOutputLease()
            val mountObservation = assessment.assessedAtElapsedRealtimeMs?.let { evaluatedAt ->
                capturePositioningObservationLocked(mountTarget, previousState.epoch, evaluatedAt,
                    PhoneMountingAssessmentPhase.ACTIVE, previousState, cameraFrameQualityOverride,
                    runtimeRetryRequested)
            }
            phoneMountingRuntimeState = assessment.nextState
            Triple(assessment, firstFault, outputsRevoked) to mountObservation
        } ?: return null
        val (assessment, firstFault, outputsRevoked) = transition.first
        applyPhoneMountingRuntimeAssessment(
            assessment = assessment,
            announceCorrection = assessment.reason != PhoneMountingReason.CAMERA_ANGLE_RECHECK &&
                (firstFault || runtimeRetryRequested),
            outputsAlreadyRevoked = outputsRevoked,
            positioningMountObservation = transition.second,
        )
        return assessment
    }

    fun schedulePhoneMountingEvidenceWatchdog(epoch: WalkRuntimeEpoch) {
        val state = phoneMountingRuntimeState ?: return
        val profile = state.approvedProfile ?: return
        if (
            state.epoch != epoch ||
            !phoneMountingOutputsAllowed ||
            !lifecycle().isRuntimeEpochCurrent(epoch)
        ) return
        val confirmation = phoneMountingUserConfirmation
        val camera = latestPhoneMountingCameraAssessment
        val nowMs = clock()
        val expiryDelays = buildList {
            if (confirmation?.epoch == epoch) {
                add(
                    remainingEvidenceLifetimeMs(
                        observedAtMs = confirmation.confirmedAtElapsedRealtimeMs,
                        nowMs = nowMs,
                        maximumAgeMs = profile.maximumUserConfirmationAgeMs,
                    ),
                )
            }
            if (
                camera?.epoch == epoch &&
                camera.observedAtElapsedRealtimeMs != null &&
                camera.maximumEvidenceAgeMs != null
            ) {
                add(
                    remainingEvidenceLifetimeMs(
                        observedAtMs = checkNotNull(camera.observedAtElapsedRealtimeMs),
                        nowMs = nowMs,
                        maximumAgeMs = minOf(
                            profile.maximumEvidenceAgeMs,
                            checkNotNull(camera.maximumEvidenceAgeMs),
                        ),
                    ),
                )
            }
        }
        val delayMs = expiryDelays.minOrNull() ?: 1L
        val generation = ++phoneMountingWatchdogGeneration
        scheduler.postMounting(
            {
                if (
                    generation == phoneMountingWatchdogGeneration &&
                    lifecycle().isRuntimeEpochCurrent(epoch)
                ) {
                    applyCurrentPhoneMountingRuntimeAssessment(
                        runtimeRetryRequested = false,
                    )
                }
            },
            delayMs,
        )
    }

    fun schedulePhoneMountingRuntimeRetryTimeout(
        epoch: WalkRuntimeEpoch,
        armedAtElapsedRealtimeMs: Long,
    ) {
        val profile = phoneMountingRuntimeState?.approvedProfile ?: return
        val generation = ++phoneMountingRuntimeRetryGeneration
        val delayMs = remainingEvidenceLifetimeMs(
            observedAtMs = armedAtElapsedRealtimeMs,
            nowMs = clock(),
            maximumAgeMs = profile.maximumEvidenceAgeMs,
        )
        scheduler.postMounting(
            {
                if (!lifecycle().isRuntimeEpochCurrent(epoch)) {
                    return@postMounting
                }
                val timeoutClaimed = synchronized(observationLock) {
                    if (
                        generation != phoneMountingRuntimeRetryGeneration ||
                        phoneMountingRuntimeRetryArmedAtElapsedRealtimeMs !=
                        armedAtElapsedRealtimeMs
                    ) {
                        false
                    } else {
                        phoneMountingRuntimeRetryArmedAtElapsedRealtimeMs = null
                        phoneMountingRuntimeRetryGeneration += 1L
                        true
                    }
                }
                if (!timeoutClaimed) return@postMounting
                applyCurrentPhoneMountingRuntimeAssessment(
                    runtimeRetryRequested = true,
                )
            },
            delayMs,
        )
    }

    fun remainingEvidenceLifetimeMs(
        observedAtMs: Long,
        nowMs: Long,
        maximumAgeMs: Long,
    ): Long {
        if (observedAtMs < 0L || nowMs < observedAtMs) return 1L
        val remainingMs = maximumAgeMs - (nowMs - observedAtMs)
        return when {
            remainingMs < 1L -> 1L
            remainingMs == Long.MAX_VALUE -> Long.MAX_VALUE
            else -> remainingMs + 1L
        }
    }
}

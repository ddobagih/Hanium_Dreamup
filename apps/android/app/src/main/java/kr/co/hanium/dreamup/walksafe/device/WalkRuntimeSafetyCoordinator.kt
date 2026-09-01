package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

data class ApprovedWalkRuntimeSafetyThresholdProfile(
    val approvalProfileId: String,
    val maximumFrameAgeMs: Long,
    val maximumInferenceLatencyMs: Long,
) {
    init {
        require(approvalProfileId.isNotBlank() && approvalProfileId == approvalProfileId.trim()) {
            "approvalProfileId must be a trimmed non-blank value"
        }
        require(maximumFrameAgeMs > 0L) { "maximumFrameAgeMs must be positive" }
        require(maximumInferenceLatencyMs > 0L) {
            "maximumInferenceLatencyMs must be positive"
        }
    }
}

enum class WalkRuntimeDeferredFailure {
    QUEUE,
    NETWORK,
    RAW_TRANSFER,
}

enum class WalkRuntimeSafetyStopCause {
    CAMERA_TRUST_LOST,
    DEPTH_TRUST_LOST,
    GPS_TRUST_LOST,
    RISK_TRUST_LOST,
    TTS_TRUST_LOST,
    BATTERY_CRITICAL,
    STORAGE_CRITICAL,
    THERMAL_CRITICAL,
    FRAME_AGE_EXCEEDED,
    INFERENCE_LATENCY_EXCEEDED,
    EPOCH_MISMATCH,
    INVALID_TIME,
}

enum class WalkRuntimeSafetyDisposition {
    NOT_CONFIGURED,
    ALLOW_SAFETY_OUTPUT,
    SAFE_STOP_LATCHED,
}

data class WalkRuntimeSafetyObservation(
    val epoch: WalkRuntimeEpoch,
    val observedAtElapsedRealtimeMs: Long,
    val cameraTrusted: Boolean? = null,
    val depthTrusted: Boolean? = null,
    val gpsTrusted: Boolean? = null,
    val riskTrusted: Boolean? = null,
    val ttsTrusted: Boolean? = null,
    val batteryCritical: Boolean? = null,
    val storageCritical: Boolean? = null,
    val thermalCritical: Boolean? = null,
    val frameCapturedAtElapsedRealtimeMs: Long? = null,
    val inferenceLatencyMs: Long? = null,
    val deferredFailures: Set<WalkRuntimeDeferredFailure> = emptySet(),
)

data class WalkRuntimeSafetyStop(
    val epoch: WalkRuntimeEpoch,
    val causes: Set<WalkRuntimeSafetyStopCause>,
)

data class WalkRuntimeSafetyDecision(
    val disposition: WalkRuntimeSafetyDisposition,
    val epoch: WalkRuntimeEpoch?,
    val causes: Set<WalkRuntimeSafetyStopCause> = emptySet(),
    val deferredFailures: Set<WalkRuntimeDeferredFailure> = emptySet(),
) {
    val safetyOutputsAllowed: Boolean
        get() = disposition != WalkRuntimeSafetyDisposition.SAFE_STOP_LATCHED
}

/**
 * Pure, epoch-scoped safety arbiter. It emits one callback for the first unsafe observation in an
 * epoch and deliberately has no recovery transition; the existing walk-session lifecycle remains
 * the owner of the actual SAFE_STOP state and new-walk recovery.
 */
class WalkRuntimeSafetyCoordinator(
    private val thresholdProfile: ApprovedWalkRuntimeSafetyThresholdProfile? =
        productionThresholdProfile,
    private val onSafeStop: (WalkRuntimeSafetyStop) -> Unit,
) {
    private val lock = Any()
    private var activeEpoch: WalkRuntimeEpoch? = null
    private var latchedEpoch: WalkRuntimeEpoch? = null
    private var latchedCauses: Set<WalkRuntimeSafetyStopCause> = emptySet()
    private var lastObservedAtElapsedRealtimeMs: Long? = null

    val configured: Boolean
        get() = thresholdProfile != null

    fun beginEpoch(epoch: WalkRuntimeEpoch): WalkRuntimeSafetyDecision = synchronized(lock) {
        if (activeEpoch != epoch) {
            activeEpoch = epoch
            if (latchedEpoch != epoch) {
                latchedEpoch = null
                latchedCauses = emptySet()
            }
            lastObservedAtElapsedRealtimeMs = null
        }
        if (latchedEpoch == epoch) {
            latchedDecision(epoch, emptySet())
        } else if (thresholdProfile == null) {
            notConfigured(epoch)
        } else {
            WalkRuntimeSafetyDecision(
                disposition = WalkRuntimeSafetyDisposition.ALLOW_SAFETY_OUTPUT,
                epoch = epoch,
            )
        }
    }

    fun observe(observation: WalkRuntimeSafetyObservation): WalkRuntimeSafetyDecision {
        val evaluation = synchronized(lock) {
            val profile = thresholdProfile
            val epochForStop = activeEpoch ?: observation.epoch
            val currentEpochObservation = activeEpoch == observation.epoch
            val newCauses = buildCauses(
                observation = observation,
                profile = profile,
                previousObservedAtElapsedRealtimeMs = if (currentEpochObservation) {
                    lastObservedAtElapsedRealtimeMs
                } else {
                    null
                },
            ).toMutableSet().apply {
                if (activeEpoch == null || activeEpoch != observation.epoch) {
                    add(WalkRuntimeSafetyStopCause.EPOCH_MISMATCH)
                }
            }
            if (
                currentEpochObservation &&
                observation.observedAtElapsedRealtimeMs >= 0L &&
                (
                    lastObservedAtElapsedRealtimeMs == null ||
                        observation.observedAtElapsedRealtimeMs >=
                        checkNotNull(lastObservedAtElapsedRealtimeMs)
                )
            ) {
                lastObservedAtElapsedRealtimeMs = observation.observedAtElapsedRealtimeMs
            }
            if (latchedEpoch == epochForStop) {
                Evaluation(latchedDecision(epochForStop, newCauses, observation.deferredFailures))
            } else if (newCauses.isEmpty()) {
                Evaluation(
                    if (profile == null) {
                        notConfigured(epochForStop, observation.deferredFailures)
                    } else {
                        WalkRuntimeSafetyDecision(
                            disposition = WalkRuntimeSafetyDisposition.ALLOW_SAFETY_OUTPUT,
                            epoch = epochForStop,
                            deferredFailures = observation.deferredFailures,
                        )
                    },
                )
            } else {
                latchedEpoch = epochForStop
                latchedCauses = newCauses.toSet()
                val stop = WalkRuntimeSafetyStop(epochForStop, latchedCauses)
                Evaluation(
                    decision = WalkRuntimeSafetyDecision(
                        disposition = WalkRuntimeSafetyDisposition.SAFE_STOP_LATCHED,
                        epoch = epochForStop,
                        causes = latchedCauses,
                        deferredFailures = observation.deferredFailures,
                    ),
                    stop = stop,
                )
            }
        }
        evaluation.stop?.let(onSafeStop)
        return evaluation.decision
    }

    private fun buildCauses(
        observation: WalkRuntimeSafetyObservation,
        profile: ApprovedWalkRuntimeSafetyThresholdProfile?,
        previousObservedAtElapsedRealtimeMs: Long?,
    ): Set<WalkRuntimeSafetyStopCause> = buildSet {
        if (observation.cameraTrusted == false) add(WalkRuntimeSafetyStopCause.CAMERA_TRUST_LOST)
        if (observation.depthTrusted == false) add(WalkRuntimeSafetyStopCause.DEPTH_TRUST_LOST)
        if (observation.gpsTrusted == false) add(WalkRuntimeSafetyStopCause.GPS_TRUST_LOST)
        if (observation.riskTrusted == false) add(WalkRuntimeSafetyStopCause.RISK_TRUST_LOST)
        if (observation.ttsTrusted == false) add(WalkRuntimeSafetyStopCause.TTS_TRUST_LOST)
        if (observation.batteryCritical == true) add(WalkRuntimeSafetyStopCause.BATTERY_CRITICAL)
        if (observation.storageCritical == true) add(WalkRuntimeSafetyStopCause.STORAGE_CRITICAL)
        if (observation.thermalCritical == true) add(WalkRuntimeSafetyStopCause.THERMAL_CRITICAL)

        val observedAtMs = observation.observedAtElapsedRealtimeMs
        val capturedAtMs = observation.frameCapturedAtElapsedRealtimeMs
        val inferenceLatencyMs = observation.inferenceLatencyMs
        val invalidTime = observedAtMs < 0L ||
            (
                previousObservedAtElapsedRealtimeMs != null &&
                    observedAtMs < previousObservedAtElapsedRealtimeMs
            ) ||
            (capturedAtMs != null && (capturedAtMs < 0L || capturedAtMs > observedAtMs)) ||
            (inferenceLatencyMs != null && inferenceLatencyMs < 0L)
        if (invalidTime) {
            add(WalkRuntimeSafetyStopCause.INVALID_TIME)
        } else {
            if (
                profile != null &&
                capturedAtMs != null &&
                observedAtMs - capturedAtMs > profile.maximumFrameAgeMs
            ) {
                add(WalkRuntimeSafetyStopCause.FRAME_AGE_EXCEEDED)
            }
            if (
                profile != null &&
                inferenceLatencyMs != null &&
                inferenceLatencyMs > profile.maximumInferenceLatencyMs
            ) {
                add(WalkRuntimeSafetyStopCause.INFERENCE_LATENCY_EXCEEDED)
            }
        }
    }

    private fun latchedDecision(
        epoch: WalkRuntimeEpoch,
        additionalCauses: Set<WalkRuntimeSafetyStopCause>,
        deferredFailures: Set<WalkRuntimeDeferredFailure> = emptySet(),
    ): WalkRuntimeSafetyDecision {
        latchedCauses = latchedCauses + additionalCauses
        return WalkRuntimeSafetyDecision(
            disposition = WalkRuntimeSafetyDisposition.SAFE_STOP_LATCHED,
            epoch = epoch,
            causes = latchedCauses,
            deferredFailures = deferredFailures,
        )
    }

    private fun notConfigured(
        epoch: WalkRuntimeEpoch,
        deferredFailures: Set<WalkRuntimeDeferredFailure> = emptySet(),
    ) = WalkRuntimeSafetyDecision(
        disposition = WalkRuntimeSafetyDisposition.NOT_CONFIGURED,
        epoch = epoch,
        deferredFailures = deferredFailures,
    )

    private data class Evaluation(
        val decision: WalkRuntimeSafetyDecision,
        val stop: WalkRuntimeSafetyStop? = null,
    )

    companion object {
        /** No operating thresholds may be invented before an external approval is bound. */
        val productionThresholdProfile: ApprovedWalkRuntimeSafetyThresholdProfile? = null
    }
}

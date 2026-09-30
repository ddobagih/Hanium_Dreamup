package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import kr.co.hanium.dreamup.walksafe.session.WalkSessionTransition
import kr.co.hanium.dreamup.walksafe.session.WalkSessionTransitionReason

data class ApprovedWalkRuntimeSafetyThresholdProfile(
    val approvalProfileId: String,
    val maximumFrameAgeMs: Long,
    val maximumInferenceLatencyMs: Long,
    val maximumEvidenceAgeMs: Long = maximumFrameAgeMs,
) {
    init {
        require(approvalProfileId.isNotBlank() && approvalProfileId == approvalProfileId.trim()) {
            "approvalProfileId must be a trimmed non-blank value"
        }
        require(maximumFrameAgeMs > 0L) { "maximumFrameAgeMs must be positive" }
        require(maximumInferenceLatencyMs > 0L) {
            "maximumInferenceLatencyMs must be positive"
        }
        require(maximumEvidenceAgeMs > 0L) { "maximumEvidenceAgeMs must be positive" }
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
    EVIDENCE_EXPIRED,
}

enum class WalkRuntimeSafetyDisposition {
    NOT_CONFIGURED,
    AWAITING_EVIDENCE,
    IGNORED_OBSERVATION,
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
    /** Receipt uses the same monotonic clock; never replace the source observation time. */
    val receivedAtElapsedRealtimeMs: Long = observedAtElapsedRealtimeMs,
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
        get() = disposition == WalkRuntimeSafetyDisposition.ALLOW_SAFETY_OUTPUT
}

/**
 * Epoch-scoped safety arbiter. The latch closes before dispatch, and completion-aware delivery
 * remains pending until the caller acknowledges actual local cleanup.
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
    private val trustEvidence = mutableMapOf<TrustSource, TimedTrust>()
    private var timingEvidence: WalkRuntimeSafetyObservation? = null
    private var lastEvaluatedAtMs: Long? = null
    private var stopDelivered = false
    private var stopDeliveryInProgress = false
    private var stopRetryScheduled = false
    private var stopDeliveryExhausted = false
    private var stopDeliveryAttempts = 0
    private var stopDeliveryGeneration = 0L
    private var closed = false
    private var completionCallback: ((WalkRuntimeSafetyStop, (Boolean) -> Unit) -> Unit)? = null
    private var retryScheduler: ((Runnable) -> Boolean)? = null
    private var exhaustionCallback: ((WalkRuntimeSafetyStop) -> Unit)? = null
    private var causesChangedCallback: ((WalkRuntimeSafetyStop) -> Unit)? = null

    /**
     * [onStopCausesChanged] receives the full cause set when a cause is added to a stop whose
     * cleanup delivery already started. It never re-runs cleanup; the stop callback stays one-shot.
     */
    constructor(
        thresholdProfile: ApprovedWalkRuntimeSafetyThresholdProfile? = productionThresholdProfile,
        onSafeStopWithCompletion: (WalkRuntimeSafetyStop, (Boolean) -> Unit) -> Unit,
        scheduleStopRetry: (Runnable) -> Boolean,
        onStopDeliveryExhausted: (WalkRuntimeSafetyStop) -> Unit,
        onStopCausesChanged: ((WalkRuntimeSafetyStop) -> Unit)? = null,
    ) : this(thresholdProfile, {}) {
        completionCallback = onSafeStopWithCompletion
        retryScheduler = scheduleStopRetry
        exhaustionCallback = onStopDeliveryExhausted
        causesChangedCallback = onStopCausesChanged
    }

    val configured: Boolean
        get() = thresholdProfile != null

    val stopCleanupBlocksNewWalk: Boolean
        get() = synchronized(lock) { closed || (latchedEpoch != null && !stopDelivered) }

    val stopCleanupExhausted: Boolean
        get() = synchronized(lock) { stopDeliveryExhausted }

    /** Current latch: the first stop causes plus every cause added afterwards, for one epoch. */
    fun latchedStop(): WalkRuntimeSafetyStop? = synchronized(lock) {
        latchedEpoch?.let { WalkRuntimeSafetyStop(it, latchedCauses) }
    }

    fun close() = synchronized(lock) {
        closed = true
        stopDeliveryGeneration += 1L
        stopDeliveryInProgress = false
        stopRetryScheduled = false
    }

    fun beginEpoch(epoch: WalkRuntimeEpoch): WalkRuntimeSafetyDecision {
        val decision = synchronized(lock) {
            if (closed || (activeEpoch != epoch && latchedEpoch != null && !stopDelivered)) {
                return@synchronized WalkRuntimeSafetyDecision(
                    disposition = WalkRuntimeSafetyDisposition.SAFE_STOP_LATCHED,
                    epoch = epoch,
                    causes = latchedCauses,
                )
            }
            if (activeEpoch != epoch) {
                activeEpoch = epoch
                if (latchedEpoch != epoch) {
                    latchedEpoch = null
                    latchedCauses = emptySet()
                }
                lastObservedAtElapsedRealtimeMs = null
                trustEvidence.clear()
                timingEvidence = null
                lastEvaluatedAtMs = null
                stopDelivered = false
                stopDeliveryInProgress = false
                stopRetryScheduled = false
                stopDeliveryExhausted = false
                stopDeliveryAttempts = 0
                stopDeliveryGeneration += 1L
            }
            if (latchedEpoch == epoch) {
                latchedDecision(epoch, emptySet())
            } else if (thresholdProfile == null) {
                notConfigured(epoch)
            } else {
                awaitingEvidence(epoch)
            }
        }
        deliverPendingStop(epoch)
        return decision
    }

    fun observe(observation: WalkRuntimeSafetyObservation): WalkRuntimeSafetyDecision {
        val evaluation = synchronized(lock) {
            val profile = thresholdProfile
            val epochForStop = activeEpoch
            if (epochForStop == null || epochForStop != observation.epoch) {
                return@synchronized Evaluation(
                    WalkRuntimeSafetyDecision(
                        disposition = WalkRuntimeSafetyDisposition.IGNORED_OBSERVATION,
                        epoch = epochForStop,
                        causes = setOf(WalkRuntimeSafetyStopCause.EPOCH_MISMATCH),
                    ),
                )
            }
            val invalidTime = WalkRuntimeSafetyStopCause.INVALID_TIME in buildCauses(
                observation = observation,
                profile = profile,
                previousObservedAtElapsedRealtimeMs = null,
            )
            val newCauses = if (invalidTime) {
                setOf(WalkRuntimeSafetyStopCause.INVALID_TIME)
            } else {
                val accepted = retainCurrentEvidence(observation)
                lastObservedAtElapsedRealtimeMs = maxOf(
                    lastObservedAtElapsedRealtimeMs ?: 0L,
                    observation.observedAtElapsedRealtimeMs,
                )
                lastEvaluatedAtMs = maxOf(lastEvaluatedAtMs ?: 0L, observation.receivedAtElapsedRealtimeMs)
                buildCauses(accepted, profile, previousObservedAtElapsedRealtimeMs = null)
            }
            if (latchedEpoch == epochForStop) {
                val causesBefore = latchedCauses
                val decision = latchedDecision(epochForStop, newCauses, observation.deferredFailures)
                Evaluation(
                    decision = decision,
                    causesChanged = WalkRuntimeSafetyStop(epochForStop, latchedCauses).takeIf {
                        !closed && latchedCauses != causesBefore && stopDeliveryAttempts > 0
                    },
                )
            } else if (newCauses.isEmpty()) {
                Evaluation(
                    decisionAt(
                        epochForStop,
                        lastEvaluatedAtMs ?: 0L,
                        observation.deferredFailures,
                    ),
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
        deliverPendingStop(evaluation.decision.epoch)
        evaluation.causesChanged?.let { changed ->
            try {
                causesChangedCallback?.invoke(changed)
            } catch (_: Exception) {
                // The latch already holds every cause; latchedStop() remains the source of truth.
            }
        }
        return evaluation.decision
    }

    /** A read does not refresh any observation or acknowledge a safe-stop side effect. */
    fun evaluate(nowElapsedRealtimeMs: Long): WalkRuntimeSafetyDecision {
        val decision = synchronized(lock) {
            val epoch = activeEpoch ?: return@synchronized WalkRuntimeSafetyDecision(
                disposition = WalkRuntimeSafetyDisposition.NOT_CONFIGURED,
                epoch = null,
            )
            if (latchedEpoch == epoch) {
                latchedDecision(epoch, emptySet())
            } else if (nowElapsedRealtimeMs < 0L ||
                lastEvaluatedAtMs?.let { nowElapsedRealtimeMs < it } == true
            ) {
                awaitingEvidence(epoch).copy(causes = setOf(WalkRuntimeSafetyStopCause.INVALID_TIME))
            } else {
                lastEvaluatedAtMs = nowElapsedRealtimeMs
                decisionAt(epoch, nowElapsedRealtimeMs)
            }
        }
        deliverPendingStop(decision.epoch)
        return decision
    }

    /** Neither the UI callback nor the retry scheduler runs while holding the arbiter lock. */
    private fun deliverPendingStop(expectedEpoch: WalkRuntimeEpoch?) {
        val delivery = synchronized(lock) {
            if (closed || expectedEpoch == null || activeEpoch != expectedEpoch ||
                latchedEpoch != expectedEpoch || stopDelivered || stopDeliveryInProgress ||
                stopRetryScheduled || stopDeliveryExhausted
            ) return@synchronized null
            stopDeliveryInProgress = true
            stopDeliveryAttempts += 1
            StopDelivery(
                WalkRuntimeSafetyStop(expectedEpoch, latchedCauses),
                stopDeliveryGeneration,
                stopDeliveryAttempts,
            )
        } ?: return
        val acknowledge: (Boolean) -> Unit = { succeeded -> finishStopDelivery(delivery, succeeded) }
        try {
            val callback = completionCallback
            if (callback != null) {
                callback(delivery.stop, acknowledge)
            } else {
                onSafeStop(delivery.stop)
                acknowledge(true)
            }
        } catch (_: Exception) {
            acknowledge(false)
        }
    }

    private fun finishStopDelivery(delivery: StopDelivery, succeeded: Boolean) {
        var retry: Runnable? = null
        var exhausted: WalkRuntimeSafetyStop? = null
        synchronized(lock) {
            if (closed || delivery.generation != stopDeliveryGeneration ||
                activeEpoch != delivery.stop.epoch || !stopDeliveryInProgress ||
                delivery.attempt != stopDeliveryAttempts
            ) return
            stopDeliveryInProgress = false
            if (succeeded) {
                stopDelivered = true
            } else if (completionCallback != null) {
                if (stopDeliveryAttempts >= MAX_STOP_DELIVERY_ATTEMPTS) {
                    stopDeliveryExhausted = true
                    exhausted = delivery.stop
                } else {
                    stopRetryScheduled = true
                    retry = Runnable {
                        val current = synchronized(lock) {
                            if (closed || delivery.generation != stopDeliveryGeneration ||
                                !stopRetryScheduled || stopDeliveryExhausted
                            ) false else {
                                stopRetryScheduled = false
                                true
                            }
                        }
                        if (current) deliverPendingStop(delivery.stop.epoch)
                    }
                }
            }
        }
        retry?.let { pending ->
            val posted = try {
                retryScheduler?.invoke(pending) == true
            } catch (_: Exception) {
                false
            }
            if (!posted) {
                exhausted = synchronized(lock) {
                    if (closed || delivery.generation != stopDeliveryGeneration ||
                        !stopRetryScheduled || stopDelivered
                    ) null else {
                        stopRetryScheduled = false
                        stopDeliveryExhausted = true
                        delivery.stop
                    }
                }
            }
        }
        exhausted?.let { stop -> runCatching { exhaustionCallback?.invoke(stop) } }
    }

    private data class StopDelivery(
        val stop: WalkRuntimeSafetyStop,
        val generation: Long,
        val attempt: Int,
    )

    private fun retainCurrentEvidence(
        observation: WalkRuntimeSafetyObservation,
    ): WalkRuntimeSafetyObservation {
        fun retain(source: TrustSource, trusted: Boolean?): Boolean? {
            if (trusted == null) return null
            val previous = trustEvidence[source]
            val at = observation.observedAtElapsedRealtimeMs
            if (previous != null && (
                    at < previous.observedAtMs ||
                        (at == previous.observedAtMs && (trusted || trusted == previous.trusted))
                    )
            ) return null
            trustEvidence[source] = TimedTrust(trusted, at)
            return trusted
        }
        val capturedAt = observation.frameCapturedAtElapsedRealtimeMs
        val previousTiming = timingEvidence
        val acceptTiming = capturedAt != null && observation.inferenceLatencyMs != null &&
            (previousTiming?.frameCapturedAtElapsedRealtimeMs?.let { capturedAt > it } ?: true)
        if (acceptTiming) timingEvidence = observation
        return observation.copy(
            cameraTrusted = retain(TrustSource.CAMERA, observation.cameraTrusted),
            depthTrusted = retain(TrustSource.DEPTH, observation.depthTrusted),
            gpsTrusted = retain(TrustSource.GPS, observation.gpsTrusted),
            riskTrusted = retain(TrustSource.RISK, observation.riskTrusted),
            ttsTrusted = retain(TrustSource.TTS, observation.ttsTrusted),
            batteryCritical = retain(TrustSource.BATTERY, observation.batteryCritical?.not())?.not(),
            storageCritical = retain(TrustSource.STORAGE, observation.storageCritical?.not())?.not(),
            thermalCritical = retain(TrustSource.THERMAL, observation.thermalCritical?.not())?.not(),
            frameCapturedAtElapsedRealtimeMs = capturedAt.takeIf { acceptTiming },
            inferenceLatencyMs = observation.inferenceLatencyMs.takeIf { acceptTiming },
        )
    }

    private fun decisionAt(
        epoch: WalkRuntimeEpoch,
        nowMs: Long,
        deferredFailures: Set<WalkRuntimeDeferredFailure> = emptySet(),
    ): WalkRuntimeSafetyDecision {
        val profile = thresholdProfile ?: return notConfigured(epoch, deferredFailures)
        val timing = timingEvidence ?: return awaitingEvidence(epoch, deferredFailures)
        val capturedAt = timing.frameCapturedAtElapsedRealtimeMs
            ?: return awaitingEvidence(epoch, deferredFailures)
        if (trustEvidence.size != TrustSource.entries.size || trustEvidence.values.any { !it.trusted }) {
            return awaitingEvidence(epoch, deferredFailures)
        }
        val fresh = nowMs >= 0L && trustEvidence.values.all {
            nowMs >= it.observedAtMs && nowMs - it.observedAtMs <= profile.maximumEvidenceAgeMs
        } && nowMs >= capturedAt && nowMs - capturedAt <= profile.maximumFrameAgeMs &&
            nowMs >= timing.observedAtElapsedRealtimeMs &&
            nowMs - timing.observedAtElapsedRealtimeMs <= profile.maximumEvidenceAgeMs
        return if (fresh) {
            WalkRuntimeSafetyDecision(
                disposition = WalkRuntimeSafetyDisposition.ALLOW_SAFETY_OUTPUT,
                epoch = epoch,
                deferredFailures = deferredFailures,
            )
        } else {
            awaitingEvidence(epoch, deferredFailures).copy(
                causes = setOf(WalkRuntimeSafetyStopCause.EVIDENCE_EXPIRED),
            )
        }
    }

    private fun awaitingEvidence(
        epoch: WalkRuntimeEpoch,
        deferredFailures: Set<WalkRuntimeDeferredFailure> = emptySet(),
    ) = WalkRuntimeSafetyDecision(
        disposition = WalkRuntimeSafetyDisposition.AWAITING_EVIDENCE,
        epoch = epoch,
        deferredFailures = deferredFailures,
    )

    private enum class TrustSource { CAMERA, DEPTH, GPS, RISK, TTS, BATTERY, STORAGE, THERMAL }

    private data class TimedTrust(val trusted: Boolean, val observedAtMs: Long)

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
        val invalidTime = observedAtMs < 0L || observation.receivedAtElapsedRealtimeMs < observedAtMs ||
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
        val causesChanged: WalkRuntimeSafetyStop? = null,
    )

    companion object {
        private const val MAX_STOP_DELIVERY_ATTEMPTS = 3
        /** No operating thresholds may be invented before an external approval is bound. */
        val productionThresholdProfile: ApprovedWalkRuntimeSafetyThresholdProfile? = null
    }
}

/** A failed non-idempotent stage is uncertain and is never replayed in the same stop. */
class WalkRuntimeStopCleanupCheckpoints {
    private enum class StageState { IN_PROGRESS, COMPLETE, UNCERTAIN }

    private val stages = mutableMapOf<String, StageState>()
    private var safetyStopSourceEpoch: WalkRuntimeEpoch? = null
    private var safetyStoppedEpoch: WalkRuntimeEpoch? = null

    /** Bind the actual lifecycle transition before its UI/resource side effects can fail. */
    fun recordSafetyStopTransition(transition: WalkSessionTransition): Boolean {
        if (transition.reason != WalkSessionTransitionReason.SAFETY_STOP_REQUESTED ||
            transition.current.state != WalkSessionState.SAFE_STOP
        ) return false
        if (safetyStopSourceEpoch != null) {
            return safetyStopSourceEpoch == transition.previous.epoch &&
                safetyStoppedEpoch == transition.current.epoch
        }
        safetyStopSourceEpoch = transition.previous.epoch
        safetyStoppedEpoch = transition.current.epoch
        return true
    }

    /** A retry may cross only the SAFE_STOP transition owned by this cleanup. */
    fun ownsStoppedSession(sourceEpoch: WalkRuntimeEpoch, current: WalkSessionSnapshot): Boolean =
        safetyStopSourceEpoch == sourceEpoch && current.state == WalkSessionState.SAFE_STOP &&
            safetyStoppedEpoch == current.epoch

    fun runStage(key: String, idempotent: Boolean = false, action: () -> Unit): Boolean {
        when (stages[key]) {
            StageState.COMPLETE -> return true
            StageState.IN_PROGRESS -> return false
            StageState.UNCERTAIN -> if (!idempotent) return false
            null -> Unit
        }
        stages[key] = StageState.IN_PROGRESS
        return try {
            action()
            stages[key] = StageState.COMPLETE
            true
        } catch (_: Exception) {
            stages[key] = StageState.UNCERTAIN
            false
        }
    }

    fun beginAsync(key: String): Boolean {
        if (stages.containsKey(key)) return false
        stages[key] = StageState.IN_PROGRESS
        return true
    }

    fun finishAsync(key: String, succeeded: Boolean): Boolean {
        if (stages[key] != StageState.IN_PROGRESS) return false
        stages[key] = if (succeeded) StageState.COMPLETE else StageState.UNCERTAIN
        return true
    }

    fun isComplete(key: String): Boolean = stages[key] == StageState.COMPLETE

    fun isUncertain(key: String): Boolean = stages[key] == StageState.UNCERTAIN
}

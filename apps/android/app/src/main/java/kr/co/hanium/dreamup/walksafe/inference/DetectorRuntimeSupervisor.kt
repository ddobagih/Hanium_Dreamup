package kr.co.hanium.dreamup.walksafe.inference

import java.util.ArrayDeque

enum class DetectorRuntimeFailureAction {
    KEEP_CURRENT,
    SAFE_STOP,
    LOAD_LEGACY_FOR_NEXT_WALK,
    DISABLE,
}

/** Whether retrying the same model and configuration can succeed (S14-37, FP-043/FP-042). */
enum class DetectorRuntimeFailureKind {
    RETRYABLE,
    NON_RETRYABLE,
}

/**
 * S10-4: only unavailable calls or old captures arrived for longer than the freshness bound. Waiting
 * for a failure count would keep walking on stale detection (FP-043-DETAIL-01), so it stops at once.
 */
internal class DetectorValidOutputExpired :
    IllegalStateException("no fresh completed detector output within the freshness bound")

/** Tensor contract, verified-asset and native cleanup failures repeat identically on every frame. */
internal fun detectorRuntimeFailureKind(error: Throwable): DetectorRuntimeFailureKind = when (error) {
    is DetectorTensorContractViolation,
    is DetectorAssetIntegrityFailure,
    is DetectorNativeCleanupFailure,
    is DetectorValidOutputExpired,
    -> DetectorRuntimeFailureKind.NON_RETRYABLE
    else -> DetectorRuntimeFailureKind.RETRYABLE
}

/**
 * S14-36: one admitted detector call. [detectorGeneration] and [walkRun] (the walk epoch) are the run the
 * call was admitted under; [ticket] is the pacing ticket that owns the single detector slot.
 */
class DetectorWorkClaim(
    val detectorGeneration: Int,
    val walkRun: Any,
    val ticket: InferencePacingTicket,
) {
    /** Same key the failure path passes as `run`: detector generation and walk epoch. */
    val run: Any get() = detectorGeneration to walkRun
}

/** [freshOutputAccepted] is true only for a current-ticket, completed and fresh inference. */
data class DetectorOutputDecision(
    val freshOutputAccepted: Boolean,
    val action: DetectorRuntimeFailureAction,
)

/** Prevents model replacement during a walk while retaining an offline next-walk fallback choice. */
class DetectorRuntimeSupervisor(
    private val maximumConsecutiveFailures: Int = 3,
    /** Detection freshness bound (VisualTrackingConfig.maxDetectionAgeMs upper bound). */
    private val maximumValidOutputGapMs: Long = 800L,
    /**
     * S14-35: time window for the failure rate, next to the count window. The initial value covers the
     * count window at the slowest nominal pacing (5 x 600 ms); it is an engineering default until the
     * window and rate are measured on supported devices.
     */
    private val failureRateWindowMs: Long = maximumValidOutputGapMs * FAILURE_RATE_WINDOW_FRESHNESS_MULTIPLE,
    /**
     * S14-36: the ownership check of an admitted call (current detector run and pacing ticket). The
     * supervisor applies it itself, so a late output of an earlier call cannot count. Default: none owns.
     */
    private val ownsCurrentWork: (DetectorWorkClaim) -> Boolean = { false },
) {
    private val failureThreshold = maximumConsecutiveFailures.coerceAtLeast(1)
    private val recentOutcomeLimit = failureThreshold.toLong() * 2L - 1L
    private val recentOutcomes = ArrayDeque<Boolean>()
    private var recentFailureCount = 0

    /** Timed outcomes within [failureRateWindowMs] (elapsed realtime, failed). */
    private val timedOutcomes = ArrayDeque<Pair<Long, Boolean>>()
    private var timedFailureCount = 0

    /** Last fresh completed inference, or the first failure/no-output event that has no valid output yet. */
    private var validOutputClockStartMs: Long? = null

    /** Detector generation and walk epoch of the last event that carried one. */
    private var currentRun: Any? = null

    var consecutiveFailures: Int = 0
        private set

    /**
     * New detector run (walk start/resume, camera or detector restart, stale generation): the previous
     * run's last output is not this run's freshness origin, so its first failure re-arms the clock.
     * This is a reset, never a success.
     */
    fun disarmValidOutputClock() {
        validOutputClockStartMs = null
        // Both failure windows belong to the previous run; only the new run's outcomes may stop it.
        consecutiveFailures = 0
        recentOutcomes.clear()
        recentFailureCount = 0
        timedOutcomes.clear()
        timedFailureCount = 0
    }

    /**
     * The only way an inference outcome reaches the supervisor (S14-36, S10-4). A call that no longer
     * owns the current run and pacing ticket changes nothing. A completed inference whose capture is
     * within the freshness bound is a valid output. An unavailable call or an old capture is neither a
     * success nor a failure: it keeps the no-valid-output clock running, and during an active walk the
     * clock past the freshness bound stops without waiting for a count.
     */
    fun onDetectorOutput(
        claim: DetectorWorkClaim,
        result: AndroidDetectionResult,
        capturedAtElapsedRealtimeMs: Long,
        completedAtElapsedRealtimeMs: Long,
        activeWalk: Boolean = true,
    ): DetectorOutputDecision {
        if (!ownsCurrentWork(claim)) {
            return DetectorOutputDecision(false, DetectorRuntimeFailureAction.KEEP_CURRENT)
        }
        enterRun(claim.run)
        val captureAgeMs = completedAtElapsedRealtimeMs - capturedAtElapsedRealtimeMs
        if (result.inferenceCompleted && captureAgeMs in 0L..maximumValidOutputGapMs) {
            consecutiveFailures = 0
            rememberOutcome(failed = false, atElapsedRealtimeMs = completedAtElapsedRealtimeMs)
            validOutputClockStartMs = completedAtElapsedRealtimeMs
            return DetectorOutputDecision(true, DetectorRuntimeFailureAction.KEEP_CURRENT)
        }
        val clockStart = validOutputClockStartMs
            ?: completedAtElapsedRealtimeMs.also { validOutputClockStartMs = it }
        val expired = completedAtElapsedRealtimeMs - clockStart > maximumValidOutputGapMs
        return DetectorOutputDecision(
            freshOutputAccepted = false,
            action = if (activeWalk && expired) {
                DetectorRuntimeFailureAction.SAFE_STOP
            } else {
                DetectorRuntimeFailureAction.KEEP_CURRENT
            },
        )
    }

    /** [run] is the detector generation and walk epoch of the failed call; a new run re-arms the clock once. */
    fun onFailure(
        activeModelKey: String?,
        legacyFallbackAvailable: Boolean,
        activeWalk: Boolean,
        failureKind: DetectorRuntimeFailureKind = DetectorRuntimeFailureKind.RETRYABLE,
        atElapsedRealtimeMs: Long? = null,
        run: Any? = null,
    ): DetectorRuntimeFailureAction {
        enterRun(run)
        consecutiveFailures += 1
        rememberOutcome(failed = true, atElapsedRealtimeMs = atElapsedRealtimeMs)
        val frequentFailures =
            (recentOutcomes.size.toLong() == recentOutcomeLimit && recentFailureCount >= failureThreshold) ||
                frequentTimedFailures()
        // FP-043-DETAIL-01: at a low inference rate the count alone would keep walking on a
        // detection result older than its freshness bound; that stops without waiting.
        val clockStart = validOutputClockStartMs
            ?: atElapsedRealtimeMs?.takeIf { consecutiveFailures == 1 }?.also { validOutputClockStartMs = it }
        val validOutputExpired = atElapsedRealtimeMs != null && clockStart != null &&
            atElapsedRealtimeMs - clockStart > maximumValidOutputGapMs
        if (
            failureKind == DetectorRuntimeFailureKind.RETRYABLE &&
            consecutiveFailures < failureThreshold && !frequentFailures && !validOutputExpired
        ) {
            return DetectorRuntimeFailureAction.KEEP_CURRENT
        }
        if (activeWalk) return DetectorRuntimeFailureAction.SAFE_STOP
        return if (
            activeModelKey == TwoModelRuntimeConfig.UNIFIED_MODEL_KEY &&
            legacyFallbackAvailable
        ) {
            DetectorRuntimeFailureAction.LOAD_LEGACY_FOR_NEXT_WALK
        } else {
            DetectorRuntimeFailureAction.DISABLE
        }
    }

    private fun enterRun(run: Any?) {
        if (run == null || run == currentRun) return
        currentRun = run
        disarmValidOutputClock()
    }

    /** Same rate as the count window (threshold of 2n-1), over the outcomes of the recent time window. */
    private fun frequentTimedFailures(): Boolean =
        timedFailureCount >= failureThreshold &&
            timedFailureCount.toLong() * recentOutcomeLimit >= failureThreshold.toLong() * timedOutcomes.size

    private fun rememberOutcome(failed: Boolean, atElapsedRealtimeMs: Long?) {
        recentOutcomes.addLast(failed)
        if (failed) recentFailureCount += 1
        if (recentOutcomes.size.toLong() > recentOutcomeLimit && recentOutcomes.removeFirst()) {
            recentFailureCount -= 1
        }
        if (atElapsedRealtimeMs == null) return
        // A clock step backwards starts a new time window rather than mixing two time bases.
        if (timedOutcomes.isNotEmpty() && atElapsedRealtimeMs < timedOutcomes.peekLast().first) {
            timedOutcomes.clear()
            timedFailureCount = 0
        }
        timedOutcomes.addLast(atElapsedRealtimeMs to failed)
        if (failed) timedFailureCount += 1
        while (timedOutcomes.isNotEmpty() && atElapsedRealtimeMs - timedOutcomes.peekFirst().first > failureRateWindowMs) {
            if (timedOutcomes.removeFirst().second) timedFailureCount -= 1
        }
    }

    private companion object {
        const val FAILURE_RATE_WINDOW_FRESHNESS_MULTIPLE = 4L
    }
}

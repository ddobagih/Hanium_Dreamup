package kr.co.hanium.dreamup.walksafe.inference

import kotlin.math.ceil
import kotlin.math.max

/** One admitted frame; no image or queued replacement frame is retained by the scheduler. */
class InferencePacingTicket internal constructor(
    val startedAtElapsedRealtimeMs: Long,
    internal val generation: Long,
)

enum class InferencePacingReason {
    INITIAL,
    MEASURED_BACKOFF,
    MEASURED_HOLD,
    RECOVERING,
    THERMAL_BACKOFF,
    OBSERVED_LOAD_BACKOFF,
    OVER_FRESHNESS_BUDGET,
    INVALID_TIMING,
}

data class InferencePacingSnapshot(
    val targetIntervalMs: Long,
    val cooldownMs: Long,
    val nextEligibleAtElapsedRealtimeMs: Long,
    val inFlight: Boolean,
    val completedSamples: Long,
    val staleSamples: Long,
    val invalidSamples: Long,
    val recoverySamples: Int,
    val lastEndToEndMs: Long?,
    val lastInferenceMs: Long?,
    val smoothedEndToEndMs: Double?,
    val smoothedInferenceMs: Double?,
    val thermalThrottled: Boolean?,
    val thermalHoldActive: Boolean,
    val overFreshnessBudget: Boolean?,
    val reason: InferencePacingReason,
    val observedLoadPressure: Double = 0.0,
    val loadRecoverySamples: Int = 0,
    val workHeadroomMultiplier: Double = 1.0,
)

/**
 * Durations and budgets from the same active camera/display mode, measured with elapsed realtime.
 * A missing pair is unknown, not healthy. Pass only measurements collected at [observedAtElapsedRealtimeMs];
 * do not re-stamp cached timings. Camera frame timestamps, display timestamps and detector source
 * timestamps must not be subtracted from one another to construct these processing durations.
 */
data class InferenceLoadObservation(
    val observedAtElapsedRealtimeMs: Long,
    val cameraFrameIntervalMs: Long? = null,
    val expectedCameraFrameIntervalMs: Long? = null,
    val depthProcessingMs: Long? = null,
    val depthBudgetMs: Long? = null,
    val trackingProcessingMs: Long? = null,
    val trackingBudgetMs: Long? = null,
    val uiDispatchDelayMs: Long? = null,
    val uiDispatchBudgetMs: Long? = null,
)

/**
 * Admits a current frame only when the previous entire worker task has finished and its measured
 * budget permits another start. Call [complete] after preprocessing, inference and publication,
 * using the same monotonic elapsed-realtime clock as [tryStart]. Partial results are not samples.
 *
 * Faster samples need sustained headroom before increasing frequency; slower samples can reduce
 * frequency immediately. A bounded completion cooldown also leaves processing headroom when a
 * single inference takes longer than the maximum requested start interval.
 *
 * The 100..600 ms target interval, work-time headroom and 25..200 ms cooldown are engineering defaults,
 * not field-validated safety thresholds or promised frame rates. Actual cadence can be slower than
 * 600 ms when work is slow. [maximumFreshFrameAgeMs] only prevents stale work from earning faster
 * pacing; callers must independently retain all existing freshness and safety-output checks.
 * Observed delays relative to each active feature's budget continuously increase work-time headroom.
 * Neither the original 0.8 work fraction nor this multiplier reserves CPU/GPU utilization; admitting
 * less work also does not establish that individual inference latency or sensing accuracy improved.
 */
class AdaptiveInferencePacingPolicy(
    private val maximumFreshFrameAgeMs: Long = 800L,
) {
    init {
        require(maximumFreshFrameAgeMs > 0L)
    }

    private var generation = 0L
    private var activeTicket: InferencePacingTicket? = null
    private var targetIntervalMs = INITIAL_INTERVAL_MS
    private var cooldownMs = MINIMUM_COOLDOWN_MS
    private var nextEligibleAtElapsedRealtimeMs = 0L
    private var completedSamples = 0L
    private var staleSamples = 0L
    private var invalidSamples = 0L
    private var recoverySamples = 0
    private var lastEndToEndMs: Long? = null
    private var lastInferenceMs: Long? = null
    private var smoothedEndToEndMs: Double? = null
    private var smoothedInferenceMs: Double? = null
    private var thermalThrottled: Boolean? = null
    private var thermalHoldActive = false
    private var overFreshnessBudget: Boolean? = null
    private var reason = InferencePacingReason.INITIAL
    private val componentPressure = DoubleArray(4)
    private val componentRecoverySamples = IntArray(4)
    private val lastComponentObservationAtMs = LongArray(4) { -1L }
    private var observedLoadPressure = 0.0
    private var loadRecoverySamples = 0

    @Synchronized
    fun isDue(nowElapsedRealtimeMs: Long): Boolean =
        activeTicket == null && nowElapsedRealtimeMs >= nextEligibleAtElapsedRealtimeMs

    @Synchronized
    fun tryStart(nowElapsedRealtimeMs: Long): InferencePacingTicket? {
        if (!isDue(nowElapsedRealtimeMs)) return null
        return InferencePacingTicket(nowElapsedRealtimeMs, generation).also {
            activeTicket = it
            nextEligibleAtElapsedRealtimeMs = addSaturated(nowElapsedRealtimeMs, targetIntervalMs)
        }
    }

    /** Returns false for an obsolete/duplicate ticket or an invalid monotonic timing sample. */
    @Synchronized
    fun complete(
        ticket: InferencePacingTicket,
        completedAtElapsedRealtimeMs: Long,
        inferenceDurationMs: Long? = null,
        thermalThrottled: Boolean? = null,
        loadObservation: InferenceLoadObservation? = null,
    ): Boolean {
        if (activeTicket !== ticket) return false
        activeTicket = null
        if (ticket.generation != generation) return false
        if (
            completedAtElapsedRealtimeMs < ticket.startedAtElapsedRealtimeMs ||
            (inferenceDurationMs != null && inferenceDurationMs < 0L)
        ) {
            invalidSamples += 1L
            recoverySamples = 0
            loadRecoverySamples = 0
            componentRecoverySamples.fill(0)
            reason = InferencePacingReason.INVALID_TIMING
            return false
        }

        val endToEndMs = completedAtElapsedRealtimeMs - ticket.startedAtElapsedRealtimeMs
        completedSamples += 1L
        lastEndToEndMs = endToEndMs
        lastInferenceMs = inferenceDurationMs
        smoothedEndToEndMs = smooth(smoothedEndToEndMs, endToEndMs)
        smoothedInferenceMs = inferenceDurationMs?.let { smooth(smoothedInferenceMs, it) }
        this.thermalThrottled = thermalThrottled
        // Missing sensor information cannot be interpreted as recovery from observed throttling.
        if (thermalThrottled != null) thermalHoldActive = thermalThrottled
        val freshSample = endToEndMs <= maximumFreshFrameAgeMs
        overFreshnessBudget = !freshSample
        if (!freshSample) staleSamples += 1L
        updateLoadPressure(loadObservation, completedAtElapsedRealtimeMs, allowRecovery = freshSample)

        val measuredWorkMs = max(
            checkNotNull(smoothedEndToEndMs),
            smoothedInferenceMs ?: 0.0,
        )
        val desiredIntervalMs = max(
            quantizedInterval(measuredWorkMs * workHeadroomMultiplier() / TARGET_BUSY_FRACTION),
            if (thermalHoldActive) THERMAL_MINIMUM_INTERVAL_MS else MINIMUM_INTERVAL_MS,
        )
        val previousIntervalMs = targetIntervalMs
        when {
            desiredIntervalMs >= targetIntervalMs + INTERVAL_STEP_MS -> {
                targetIntervalMs = desiredIntervalMs
                recoverySamples = 0
            }
            freshSample && desiredIntervalMs <= targetIntervalMs - INTERVAL_STEP_MS -> {
                recoverySamples += 1
                if (recoverySamples >= RECOVERY_SAMPLE_COUNT) {
                    targetIntervalMs = max(desiredIntervalMs, targetIntervalMs - MAXIMUM_RECOVERY_STEP_MS)
                    recoverySamples = 0
                }
            }
            else -> recoverySamples = 0
        }
        reason = when {
            !freshSample -> InferencePacingReason.OVER_FRESHNESS_BUDGET
            thermalHoldActive -> InferencePacingReason.THERMAL_BACKOFF
            observedLoadPressure > 0.0 -> InferencePacingReason.OBSERVED_LOAD_BACKOFF
            targetIntervalMs > previousIntervalMs -> InferencePacingReason.MEASURED_BACKOFF
            recoverySamples > 0 || targetIntervalMs < previousIntervalMs -> InferencePacingReason.RECOVERING
            else -> InferencePacingReason.MEASURED_HOLD
        }
        cooldownMs = ceil(measuredWorkMs * (workHeadroomMultiplier() / TARGET_BUSY_FRACTION - 1.0))
            .toLong().coerceIn(MINIMUM_COOLDOWN_MS, MAXIMUM_COOLDOWN_MS)
        nextEligibleAtElapsedRealtimeMs = max(
            addSaturated(ticket.startedAtElapsedRealtimeMs, targetIntervalMs),
            addSaturated(completedAtElapsedRealtimeMs, cooldownMs),
        )
        return true
    }

    /** Releases an acquisition, admission or executor failure without treating it as a fast run. */
    @Synchronized
    fun cancel(ticket: InferencePacingTicket): Boolean {
        if (activeTicket !== ticket) return false
        activeTicket = null
        recoverySamples = 0
        loadRecoverySamples = 0
        componentRecoverySamples.fill(0)
        return true
    }

    /**
     * Clears learned timing on a detector/session generation change. An already running physical
     * task still owns its ticket until complete/cancel; resetting cannot admit a concurrent frame.
     * Known thermal throttling outlives a detector/session change until an explicit false sample.
     */
    @Synchronized
    fun reset() {
        generation += 1L
        targetIntervalMs = if (thermalHoldActive) THERMAL_MINIMUM_INTERVAL_MS else INITIAL_INTERVAL_MS
        cooldownMs = MINIMUM_COOLDOWN_MS
        nextEligibleAtElapsedRealtimeMs = 0L
        completedSamples = 0L
        staleSamples = 0L
        invalidSamples = 0L
        recoverySamples = 0
        lastEndToEndMs = null
        lastInferenceMs = null
        smoothedEndToEndMs = null
        smoothedInferenceMs = null
        overFreshnessBudget = null
        componentPressure.fill(0.0)
        componentRecoverySamples.fill(0)
        lastComponentObservationAtMs.fill(-1L)
        observedLoadPressure = 0.0
        loadRecoverySamples = 0
        reason = if (thermalHoldActive) InferencePacingReason.THERMAL_BACKOFF else InferencePacingReason.INITIAL
    }

    @Synchronized
    fun snapshot(): InferencePacingSnapshot = InferencePacingSnapshot(
        targetIntervalMs = targetIntervalMs,
        cooldownMs = cooldownMs,
        nextEligibleAtElapsedRealtimeMs = nextEligibleAtElapsedRealtimeMs,
        inFlight = activeTicket != null,
        completedSamples = completedSamples,
        staleSamples = staleSamples,
        invalidSamples = invalidSamples,
        recoverySamples = recoverySamples,
        lastEndToEndMs = lastEndToEndMs,
        lastInferenceMs = lastInferenceMs,
        smoothedEndToEndMs = smoothedEndToEndMs,
        smoothedInferenceMs = smoothedInferenceMs,
        thermalThrottled = thermalThrottled,
        thermalHoldActive = thermalHoldActive,
        overFreshnessBudget = overFreshnessBudget,
        reason = reason,
        observedLoadPressure = observedLoadPressure,
        loadRecoverySamples = loadRecoverySamples,
        workHeadroomMultiplier = workHeadroomMultiplier(),
    )

    private fun updateLoadPressure(observation: InferenceLoadObservation?, completedAtMs: Long, allowRecovery: Boolean) {
        if (
            observation == null || observation.observedAtElapsedRealtimeMs < 0L ||
            observation.observedAtElapsedRealtimeMs > completedAtMs ||
            completedAtMs - observation.observedAtElapsedRealtimeMs > MAXIMUM_LOAD_OBSERVATION_AGE_MS
        ) {
            return
        }
        val measurements = arrayOf(
            observation.cameraFrameIntervalMs to observation.expectedCameraFrameIntervalMs,
            observation.depthProcessingMs to observation.depthBudgetMs,
            observation.trackingProcessingMs to observation.trackingBudgetMs,
            observation.uiDispatchDelayMs to observation.uiDispatchBudgetMs,
        )
        val previousPressure = observedLoadPressure
        measurements.forEachIndexed { index, (durationMs, budgetMs) ->
            if (observation.observedAtElapsedRealtimeMs <= lastComponentObservationAtMs[index]) return@forEachIndexed
            if (durationMs != null && durationMs >= 0L && budgetMs != null && budgetMs > 0L) {
                val pressure = (durationMs.toDouble() / budgetMs - 1.0)
                    .coerceIn(0.0, MAXIMUM_LOAD_PRESSURE)
                lastComponentObservationAtMs[index] = observation.observedAtElapsedRealtimeMs
                val heldPressure = componentPressure[index]
                when {
                    pressure > heldPressure -> {
                        componentPressure[index] = pressure
                        componentRecoverySamples[index] = 0
                    }
                    allowRecovery && (pressure <= heldPressure - LOAD_RECOVERY_HYSTERESIS || pressure == 0.0 && heldPressure > 0.0) -> {
                        componentRecoverySamples[index] += 1
                        if (componentRecoverySamples[index] >= RECOVERY_SAMPLE_COUNT) {
                            componentPressure[index] = max(pressure, heldPressure - MAXIMUM_LOAD_RECOVERY_STEP)
                            componentRecoverySamples[index] = 0
                        }
                    }
                    else -> componentRecoverySamples[index] = 0
                }
            } else if (durationMs != null || budgetMs != null) {
                componentRecoverySamples[index] = 0
            }
        }
        // Each independently timed feature needs its own new recovery evidence. Missing channels
        // neither earn recovery nor erase evidence already measured by another asynchronous source.
        observedLoadPressure = componentPressure.max()
        loadRecoverySamples = componentRecoverySamples.max()
        if (observedLoadPressure > previousPressure) recoverySamples = 0
    }

    private fun workHeadroomMultiplier(): Double = 1.0 + observedLoadPressure

    private fun smooth(previous: Double?, sample: Long): Double = previous?.let {
        it + EWMA_WEIGHT * (sample.toDouble() - it)
    } ?: sample.toDouble()

    private fun quantizedInterval(intervalMs: Double): Long =
        (ceil(intervalMs / INTERVAL_STEP_MS) * INTERVAL_STEP_MS).toLong()
            .coerceIn(MINIMUM_INTERVAL_MS, MAXIMUM_INTERVAL_MS)

    private fun addSaturated(timeMs: Long, durationMs: Long): Long =
        if (timeMs > Long.MAX_VALUE - durationMs) Long.MAX_VALUE else timeMs + durationMs

    companion object {
        const val INITIAL_INTERVAL_MS = 250L
        const val MINIMUM_INTERVAL_MS = 100L
        const val MAXIMUM_INTERVAL_MS = 600L
        const val MINIMUM_COOLDOWN_MS = 25L
        const val MAXIMUM_COOLDOWN_MS = 200L
        const val THERMAL_MINIMUM_INTERVAL_MS = 500L
        private const val TARGET_BUSY_FRACTION = 0.8
        private const val EWMA_WEIGHT = 0.25
        private const val INTERVAL_STEP_MS = 25L
        private const val MAXIMUM_RECOVERY_STEP_MS = 50L
        private const val RECOVERY_SAMPLE_COUNT = 4
        private const val MAXIMUM_LOAD_OBSERVATION_AGE_MS = 1_000L
        private const val MAXIMUM_LOAD_PRESSURE = 3.0
        private const val LOAD_RECOVERY_HYSTERESIS = 0.1
        private const val MAXIMUM_LOAD_RECOVERY_STEP = 0.25
    }
}

package kr.co.hanium.dreamup.walksafe.inference

import kotlin.math.abs
import kotlin.math.max

/** Accepted live results only; reused display geometry is not a new detector completion. */
data class RuntimeLoadCompletion(
    val endToEndMs: Long,
    val staleOrDropped: Boolean = false,
    val sourceTimestampMatches: Boolean = true,
    val liveCameraInput: Boolean = true,
)

enum class RuntimeLoadPendingReason {
    SUSTAINED_LATENCY_DEGRADATION,
    SUSTAINED_STALE_OR_DROP_INCREASE,
    NO_COMPLETIONS_WITH_INPUT,
    NO_FRESH_RESULTS_WITH_INPUT,
}

enum class RuntimeLoadWindowStatus {
    COLLECTING,
    BASELINE_LEARNED,
    NORMAL,
    DEGRADED,
    INSUFFICIENT_SAMPLES,
    INACTIVE_ENVIRONMENT,
    INVALID_TIMING,
    OBSERVATION_GAP,
    SOURCE_TIMING_MISMATCH,
    RESULT_INTERRUPTION,
}

data class RuntimeLoadBaseline(
    val medianEndToEndMs: Double,
    val medianAbsoluteDeviationMs: Double,
    val staleOrDropFraction: Double,
    val completedSamples: Int,
)

data class RuntimeLoadSnapshot(
    val environmentKey: String?,
    val baseline: RuntimeLoadBaseline?,
    val consecutiveDegradedWindows: Int,
    val completedWindows: Long,
    val pendingReason: RuntimeLoadPendingReason?,
    val lastWindowStatus: RuntimeLoadWindowStatus,
    val sourceTimingMismatchSamples: Long,
)

/**
 * Passive live monitoring, independent of Android and wall time. Call [observe] on current input
 * frames even without a completion; otherwise a stuck worker cannot be distinguished from inactivity.
 * The environment key supplied to observe binds the actual delegate/thread and active feature scope.
 * Normal environment means comparable thermal/power/camera/ARCore conditions, not a CPU load guess.
 *
 * Three non-overlapping 20-second windows with >= 10 completions must exceed live baseline latency
 * and its noise margin, or stale/drop fraction. First healthy live window learns the baseline; it
 * cannot itself prove optimum performance. Input with no fresh results for a whole window is a
 * separate diagnostic even without enough samples. No completion-rate threshold penalizes pacing.
 *
 * Timing mismatch is diagnostic data, not latency, depth accuracy or successful detection evidence.
 * The policy emits a sticky pending reason only; it never changes a profile or runs a benchmark.
 * Call [reset] when a new calibration/profile is accepted, and [pause] on inactive lifecycle/lease.
 * All defaults are engineering detection thresholds, not measured accuracy or safety guarantees.
 */
class RuntimeLoadMonitor(
    private val windowDurationMs: Long = 20_000L,
    private val requiredDegradedWindows: Int = 3,
    private val minimumCompletedSamples: Int = 10,
) {
    init {
        require(windowDurationMs > 0L)
        require(requiredDegradedWindows > 0)
        require(minimumCompletedSamples in 1..MAXIMUM_TIMING_SAMPLES)
    }

    private var environmentKey: String? = null
    private var baseline: RuntimeLoadBaseline? = null
    private var window: Window? = null
    private var lastObservedAtMs: Long? = null
    private var consecutiveDegradedWindows = 0
    private var completedWindows = 0L
    private var pendingReason: RuntimeLoadPendingReason? = null
    private var lastWindowStatus = RuntimeLoadWindowStatus.COLLECTING
    private var sourceTimingMismatchSamples = 0L
    private var noFreshResultSinceMs: Long? = null

    /**
     * Each completion is submitted exactly once after the owner's ticket/generation check. Supply
     * only one monotonic time domain; [RuntimeLoadCompletion.endToEndMs] is actual capture-to-finish,
     * not fixture timing. Unknown required environment observations must set normalEnvironment false.
     */
    @Synchronized
    fun observe(
        nowElapsedRealtimeMs: Long,
        environmentKey: String,
        normalEnvironment: Boolean,
        inputObserved: Boolean = false,
        completion: RuntimeLoadCompletion? = null,
    ): RuntimeLoadSnapshot {
        val previousObservedAtMs = lastObservedAtMs
        if (nowElapsedRealtimeMs < 0L || previousObservedAtMs != null && nowElapsedRealtimeMs < previousObservedAtMs) {
            breakContinuity(RuntimeLoadWindowStatus.INVALID_TIMING)
            return snapshot()
        }
        lastObservedAtMs = nowElapsedRealtimeMs
        if (environmentKey.isBlank() || !normalEnvironment) {
            breakContinuity(RuntimeLoadWindowStatus.INACTIVE_ENVIRONMENT)
            return snapshot()
        }
        if (this.environmentKey != environmentKey) {
            this.environmentKey = environmentKey
            baseline = null
            pendingReason = null
            breakContinuity(RuntimeLoadWindowStatus.COLLECTING)
        }
        if (previousObservedAtMs != null && nowElapsedRealtimeMs - previousObservedAtMs > windowDurationMs) {
            // Missing callbacks are not evidence of normal inputs during the unobserved interval.
            breakContinuity(RuntimeLoadWindowStatus.OBSERVATION_GAP)
        }
        var currentWindow = window ?: Window(nowElapsedRealtimeMs).also { window = it }
        if (nowElapsedRealtimeMs - currentWindow.startedAtMs >= windowDurationMs) {
            if ((nowElapsedRealtimeMs - currentWindow.startedAtMs) / windowDurationMs > 1L) {
                breakContinuity(RuntimeLoadWindowStatus.OBSERVATION_GAP)
                currentWindow = Window(nowElapsedRealtimeMs).also { window = it }
            } else {
                finishWindow(currentWindow)
                currentWindow = Window(currentWindow.startedAtMs + windowDurationMs).also { window = it }
            }
        }
        if (inputObserved) {
            currentWindow.inputObserved = true
            if (noFreshResultSinceMs == null) noFreshResultSinceMs = nowElapsedRealtimeMs
        }
        if (completion != null) {
            when {
                !completion.liveCameraInput -> {
                    breakContinuity(RuntimeLoadWindowStatus.INACTIVE_ENVIRONMENT)
                }
                completion.endToEndMs < 0L -> {
                    breakContinuity(RuntimeLoadWindowStatus.INVALID_TIMING)
                }
                !completion.sourceTimestampMatches -> {
                    sourceTimingMismatchSamples += 1L
                    breakContinuity(RuntimeLoadWindowStatus.SOURCE_TIMING_MISMATCH)
                }
                else -> {
                    currentWindow.completedSamples += 1L
                    if (completion.staleOrDropped) currentWindow.staleOrDropped += 1L
                    else noFreshResultSinceMs = null
                    if (currentWindow.timings.size < MAXIMUM_TIMING_SAMPLES) {
                        currentWindow.timings.add(completion.endToEndMs.toDouble())
                    }
                }
            }
        }
        return snapshot()
    }

    @Synchronized
    fun pause() {
        breakContinuity(RuntimeLoadWindowStatus.INACTIVE_ENVIRONMENT)
    }

    @Synchronized
    fun reset() {
        environmentKey = null
        baseline = null
        window = null
        lastObservedAtMs = null
        consecutiveDegradedWindows = 0
        completedWindows = 0L
        pendingReason = null
        lastWindowStatus = RuntimeLoadWindowStatus.COLLECTING
        sourceTimingMismatchSamples = 0L
        noFreshResultSinceMs = null
    }

    @Synchronized
    fun snapshot(): RuntimeLoadSnapshot = RuntimeLoadSnapshot(
        environmentKey, baseline, consecutiveDegradedWindows, completedWindows, pendingReason,
        lastWindowStatus, sourceTimingMismatchSamples,
    )

    private fun finishWindow(finished: Window) {
        completedWindows += 1L
        val noFreshSinceMs = noFreshResultSinceMs
        val wholeWindowWithoutFreshResult = noFreshSinceMs != null &&
            finished.startedAtMs + windowDurationMs - noFreshSinceMs >= windowDurationMs
        if (finished.inputObserved && finished.completedSamples == finished.staleOrDropped && wholeWindowWithoutFreshResult) {
            pendingReason = pendingReason ?: if (finished.completedSamples == 0L) {
                RuntimeLoadPendingReason.NO_COMPLETIONS_WITH_INPUT
            } else {
                RuntimeLoadPendingReason.NO_FRESH_RESULTS_WITH_INPUT
            }
            consecutiveDegradedWindows = 0
            lastWindowStatus = RuntimeLoadWindowStatus.RESULT_INTERRUPTION
            return
        }
        if (finished.completedSamples < minimumCompletedSamples || !finished.inputObserved) {
            consecutiveDegradedWindows = 0
            lastWindowStatus = RuntimeLoadWindowStatus.INSUFFICIENT_SAMPLES
            return
        }
        val medianMs = median(finished.timings)
        val madMs = median(finished.timings.map { abs(it - medianMs) })
        val staleFraction = finished.staleOrDropped.toDouble() / finished.completedSamples
        val reference = baseline
        if (reference == null) {
            // A heavily stale initial window is unsuitable as a "healthy" live baseline.
            if (staleFraction > MAXIMUM_BASELINE_STALE_FRACTION) {
                consecutiveDegradedWindows = 0
                lastWindowStatus = RuntimeLoadWindowStatus.INSUFFICIENT_SAMPLES
                return
            }
            baseline = RuntimeLoadBaseline(medianMs, madMs, staleFraction, finished.timings.size)
            lastWindowStatus = RuntimeLoadWindowStatus.BASELINE_LEARNED
            return
        }
        val latencyMarginMs = max(
            MINIMUM_LATENCY_MARGIN_MS,
            max(reference.medianEndToEndMs * LATENCY_MARGIN_FRACTION, 2.0 * max(reference.medianAbsoluteDeviationMs, madMs)),
        )
        val latencyDegraded = medianMs > reference.medianEndToEndMs + latencyMarginMs
        val staleDegraded = staleFraction > reference.staleOrDropFraction + STALE_FRACTION_MARGIN
        if (!latencyDegraded && !staleDegraded) {
            consecutiveDegradedWindows = 0
            lastWindowStatus = RuntimeLoadWindowStatus.NORMAL
            return
        }
        consecutiveDegradedWindows = (consecutiveDegradedWindows + 1).coerceAtMost(requiredDegradedWindows)
        lastWindowStatus = RuntimeLoadWindowStatus.DEGRADED
        if (consecutiveDegradedWindows >= requiredDegradedWindows && pendingReason == null) {
            pendingReason = if (latencyDegraded) RuntimeLoadPendingReason.SUSTAINED_LATENCY_DEGRADATION
            else RuntimeLoadPendingReason.SUSTAINED_STALE_OR_DROP_INCREASE
        }
    }

    private fun breakContinuity(status: RuntimeLoadWindowStatus) {
        window = null
        noFreshResultSinceMs = null
        consecutiveDegradedWindows = 0
        lastWindowStatus = status
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    private class Window(val startedAtMs: Long) {
        var inputObserved = false
        var completedSamples = 0L
        var staleOrDropped = 0L
        val timings = ArrayList<Double>()
    }

    companion object {
        private const val MAXIMUM_TIMING_SAMPLES = 1_024
        private const val MINIMUM_LATENCY_MARGIN_MS = 25.0
        private const val LATENCY_MARGIN_FRACTION = 0.25
        private const val STALE_FRACTION_MARGIN = 0.1
        private const val MAXIMUM_BASELINE_STALE_FRACTION = 0.1
    }
}

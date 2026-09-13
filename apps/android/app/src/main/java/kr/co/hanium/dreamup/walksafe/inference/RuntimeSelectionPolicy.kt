package kr.co.hanium.dreamup.walksafe.inference

import kotlin.math.abs
import kotlin.math.max

/** Conservative exploration rule, not a confidence interval or an accuracy guarantee. */
object RuntimeSelectionPolicy {
    const val VERSION = "walksafe-runtime-selection-1"
    const val MAX_BUDGET_MS = 300_000L
    const val MIN_PAIRS_PER_BLOCK = 4
    const val MIN_LIVE_COMPLETIONS = 10
    const val MIN_LIVE_DURATION_MS = 10_000L
    private val HASH = Regex("^[0-9a-f]{64}$")

    fun processorLimit(availableProcessors: Int): Int = max(1, availableProcessors)

    fun initialCpuCandidate(availableProcessors: Int, defaultThreads: Int = 4): RuntimeCandidate =
        RuntimeCandidate(RuntimeBackend.CPU, defaultThreads.coerceIn(1, processorLimit(availableProcessors)))

    fun isExecutable(candidate: RuntimeCandidate, availableProcessors: Int): Boolean =
        candidate.numThreads in 1..processorLimit(availableProcessors)

    fun deadline(startElapsedMs: Long, budgetMs: Long = MAX_BUDGET_MS): Long {
        require(startElapsedMs >= 0 && budgetMs > 0)
        val boundedBudget = budgetMs.coerceAtMost(MAX_BUDGET_MS)
        return if (startElapsedMs > Long.MAX_VALUE - boundedBudget) Long.MAX_VALUE else startElapsedMs + boundedBudget
    }

    /** Reserve measured/estimated confirmation and cleanup costs before native work starts. */
    fun canStartCandidate(nowElapsedMs: Long, deadlineElapsedMs: Long, estimatedWorkMs: Long, reserveMs: Long): Boolean =
        nowElapsedMs >= 0 && deadlineElapsedMs > nowElapsedMs && estimatedWorkMs >= 0 && reserveMs >= 0 &&
            estimatedWorkMs < deadlineElapsedMs - nowElapsedMs &&
            reserveMs < deadlineElapsedMs - nowElapsedMs - estimatedWorkMs

    fun createCandidateQueue(
        availableProcessors: Int,
        baseline: RuntimeCandidate,
        gpuSupported: Boolean,
        initialGpuThreads: Int = 4,
        restored: RuntimeCandidateQueueSnapshot? = null,
    ): RuntimeCandidateQueue = RuntimeCandidateQueue(
        processorLimit(availableProcessors), baseline, gpuSupported, initialGpuThreads, restored,
    )

    fun evaluate(
        baseline: RuntimeCandidate,
        candidate: RuntimeCandidate,
        summary: RuntimeMeasurementSummary,
        nowElapsedMs: Long,
        deadlineElapsedMs: Long,
        baselinePreviouslyConfirmed: Boolean = true,
    ): RuntimeSelectionDecision {
        fun result(status: RuntimeSelectionStatus, reason: String, selected: RuntimeCandidate = baseline) = RuntimeSelectionDecision(
            status, selected, reason, summary, baseline, candidate,
        )
        if (nowElapsedMs < 0 || deadlineElapsedMs <= nowElapsedMs) return result(RuntimeSelectionStatus.PARTIAL, "deadline")
        when (summary.fixture.failure) {
            RuntimeExecutionFailure.UNSUPPORTED -> return result(RuntimeSelectionStatus.UNSUPPORTED, "unsupported")
            RuntimeExecutionFailure.BACKEND_FALLBACK -> return result(RuntimeSelectionStatus.REJECTED, "backend_fallback")
            RuntimeExecutionFailure.OUTPUT_MISMATCH -> return result(RuntimeSelectionStatus.REJECTED, "output_mismatch")
            RuntimeExecutionFailure.EXECUTION_ERROR -> return result(RuntimeSelectionStatus.REJECTED, "execution_error")
            null -> Unit
        }
        val fixture = summary.fixture
        if (fixture.version.isBlank() || !HASH.matches(fixture.hash) || fixture.requiredCaseCount <= 0 ||
            fixture.completedCaseCount != fixture.requiredCaseCount || fixture.requiredCaseIds.size != fixture.requiredCaseCount ||
            fixture.completedCaseIds != fixture.requiredCaseIds || fixture.requiredCaseIds.any { it.isBlank() }
        ) return result(RuntimeSelectionStatus.PARTIAL, "fixture_incomplete")
        if (!fixture.outputEquivalent) return result(RuntimeSelectionStatus.REJECTED, "output_mismatch")
        if (fixture.baselineActualBackend != baseline.backend || fixture.candidateActualBackend != candidate.backend) {
            return result(RuntimeSelectionStatus.REJECTED, "backend_fallback")
        }
        if (!summary.clockResolutionMs.isFinite() || summary.clockResolutionMs <= 0) {
            return result(RuntimeSelectionStatus.INCONCLUSIVE, "clock_resolution")
        }
        val blocks = listOf(summary.ab, summary.ba, summary.confirmation)
        if (blocks.map { it.order } != listOf(RuntimeComparisonOrder.AB, RuntimeComparisonOrder.BA, RuntimeComparisonOrder.CONFIRMATION)) {
            return result(RuntimeSelectionStatus.INCONCLUSIVE, "comparison_order")
        }
        if (blocks.any { it.pairs.size < MIN_PAIRS_PER_BLOCK || it.aaVariationMs.isEmpty() }) {
            return result(RuntimeSelectionStatus.PARTIAL, "insufficient_pairs")
        }
        if (blocks.map { block -> block.pairs.map { it.fixtureCaseId } }.distinct().size != 1) {
            return result(RuntimeSelectionStatus.INCONCLUSIVE, "fixture_order_changed")
        }
        val environment = summary.baselineLive.environment
        if (!validEnvironment(environment) || !isExecutable(baseline, environment.availableProcessors) ||
            !isExecutable(candidate, environment.availableProcessors) || summary.candidateLive.environment != environment
        ) return result(RuntimeSelectionStatus.INCONCLUSIVE, "environment_changed")
        if (blocks.any { block ->
                block.aaVariationMs.any { !it.isFinite() || it < 0 } || block.pairs.any { pair ->
                    pair.fixtureCaseId !in fixture.requiredCaseIds || !positive(pair.baselineMs) || !positive(pair.candidateMs) ||
                        !pair.baselineCompleted || !pair.candidateCompleted || pair.speechActive ||
                        pair.baselineEnvironment != environment || pair.candidateEnvironment != environment
                }
            }
        ) return result(RuntimeSelectionStatus.INCONCLUSIVE, "invalid_or_incomparable_sample")
        if (!validLive(summary.baselineLive, baseline) || !validLive(summary.candidateLive, candidate)) {
            return result(RuntimeSelectionStatus.INCONCLUSIVE, "live_load_unverified")
        }
        val uncertainty = blocks.maxOf { uncertainty(it, summary.clockResolutionMs) }
        if (!uncertainty.isFinite()) return result(RuntimeSelectionStatus.INCONCLUSIVE, "unbounded_noise")
        val gains = blocks.map { median(it.pairs.map { pair -> pair.baselineMs - pair.candidateMs }) }
        val improved = gains.all { it > uncertainty }
        // Only the initial CPU tie may prefer fewer threads; this is not a power claim.
        val initialCpuTie = !baselinePreviouslyConfirmed && baseline.backend == RuntimeBackend.CPU &&
            candidate.backend == RuntimeBackend.CPU && candidate.numThreads < baseline.numThreads &&
            gains.all { abs(it) <= uncertainty }
        val baselineSuperior = gains.all { it < -uncertainty }
        val equivalent = gains.all { abs(it) <= uncertainty }
        if (!improved && !initialCpuTie && !baselineSuperior && !equivalent) {
            return result(RuntimeSelectionStatus.INCONCLUSIVE, "improvement_within_noise")
        }
        val selectCandidate = improved || initialCpuTie
        val selectedLive = if (selectCandidate) summary.candidateLive else summary.baselineLive
        val otherLive = if (selectCandidate) summary.baselineLive else summary.candidateLive
        if (!liveNotWorse(otherLive, selectedLive, summary.clockResolutionMs)) {
            return result(RuntimeSelectionStatus.INCONCLUSIVE, "live_regression")
        }
        val liveGain = median(otherLive.captureToCompleteMs) - median(selectedLive.captureToCompleteMs)
        if (if (initialCpuTie || equivalent) liveGain < -summary.clockResolutionMs else liveGain <= uncertainty) {
            return result(RuntimeSelectionStatus.INCONCLUSIVE, "live_improvement_unconfirmed")
        }
        val reason = when {
            initialCpuTie -> "initial_cpu_tie_fewer_threads"
            improved -> "repeated_improvement"
            baselineSuperior -> "baseline_superior"
            else -> "equivalent_baseline_retained"
        }
        return result(RuntimeSelectionStatus.CONFIRMED, reason, if (selectCandidate) candidate else baseline)
    }

    internal fun profileFromConfirmed(decision: RuntimeSelectionDecision, measuredAtEpochMs: Long): RuntimeTuningProfile? {
        if (decision.status != RuntimeSelectionStatus.CONFIRMED || measuredAtEpochMs <= 0) return null
        val baseline = decision.baselineCandidate ?: return null
        val candidate = decision.comparedCandidate ?: return null
        val summary = decision.measurement ?: return null
        // Revalidate evidence instead of allowing a caller-created status to bypass the checks.
        val checked = evaluate(baseline, candidate, summary, 0, 1,
            baselinePreviouslyConfirmed = decision.reason != "initial_cpu_tie_fewer_threads")
        if (checked.status != RuntimeSelectionStatus.CONFIRMED || checked.selectedCandidate != decision.selectedCandidate) return null
        val blocks = listOf(summary.ab, summary.ba, summary.confirmation)
        val live = if (decision.selectedCandidate == baseline) summary.baselineLive else summary.candidateLive
        val measurement = RuntimeConfirmedMeasurement(
            live.environment.bindingHash, live.environment.featureScope, live.actualBackend,
            summary.ab.pairs.size + summary.ba.pairs.size, summary.confirmation.pairs.size,
            median(summary.baselineLive.captureToCompleteMs), median(live.captureToCompleteMs),
            blocks.maxOf { uncertainty(it, summary.clockResolutionMs) }, summary.fixture.version, summary.fixture.hash,
            live.validCompletions, live.observationDurationMs,
        )
        return RuntimeTuningProfile(
            measurement.bindingHash, decision.selectedCandidate, measurement.featureScope,
            measurement.fixtureVersion, measurement.fixtureHash, VERSION, measuredAtEpochMs, measurement,
        )
    }

    internal fun isValidProfile(profile: RuntimeTuningProfile, expectedBindingHash: String, availableProcessors: Int): Boolean {
        val m = profile.measurement
        return HASH.matches(expectedBindingHash) && profile.bindingHash == expectedBindingHash &&
            m.bindingHash == expectedBindingHash && profile.featureScope == m.featureScope &&
            isExecutable(profile.candidate, availableProcessors) && profile.candidate.backend == m.actualBackend &&
            profile.policyVersion == VERSION && m.policyVersion == VERSION && profile.measuredAtEpochMs > 0 &&
            profile.fixtureVersion.isNotBlank() && profile.fixtureVersion == m.fixtureVersion &&
            HASH.matches(profile.fixtureHash) && profile.fixtureHash == m.fixtureHash &&
            m.comparedPairCount >= MIN_PAIRS_PER_BLOCK * 2 && m.confirmedPairCount >= MIN_PAIRS_PER_BLOCK &&
            positive(m.baselineMedianMs) && positive(m.selectedMedianMs) && positive(m.uncertaintyMs) &&
            m.selectedMedianMs - m.baselineMedianMs <= m.uncertaintyMs &&
            m.liveValidCompletions >= MIN_LIVE_COMPLETIONS && m.liveObservationDurationMs >= MIN_LIVE_DURATION_MS
    }

    private fun validEnvironment(e: RuntimeComparisonEnvironment): Boolean =
        HASH.matches(e.bindingHash) && e.availableProcessors > 0 && e.thermalStatus >= 0 &&
            e.cameraState == "ACTIVE" && e.arCoreState.isNotBlank() && e.cadenceMs > 0 && e.workloadVersion.isNotBlank()

    private fun validLive(live: RuntimeLiveMetrics, candidate: RuntimeCandidate): Boolean =
        live.actualBackend == candidate.backend && live.observationDurationMs >= MIN_LIVE_DURATION_MS &&
            live.validCompletions >= MIN_LIVE_COMPLETIONS && live.submittedFrames >= live.validCompletions &&
            live.captureToCompleteMs.size == live.validCompletions && live.captureToCompleteMs.all(::positive) &&
            live.staleFrames in 0..live.submittedFrames && live.droppedFrames in 0..live.submittedFrames &&
            live.staleFrames.toLong() + live.droppedFrames <= live.submittedFrames.toLong() - live.validCompletions &&
            live.uniqueCameraFrames >= live.submittedFrames && positive(live.cameraFrameIntervalMs) &&
            nonnegative(live.trackingCostMs) && nonnegative(live.uiFrameDelayMs) &&
            positive(live.longestCompletionGapMs) && live.adaptivePacing &&
            (live.environment.featureScope != RuntimeFeatureScope.CAMERA_TRACKING_METRIC_DEPTH ||
                (live.environment.arCoreState == "TRACKING" && live.poseValid && live.depthTimestampFresh && live.positiveDepthSamples > 0))

    private fun liveNotWorse(a: RuntimeLiveMetrics, b: RuntimeLiveMetrics, clock: Double): Boolean =
        b.validCompletions.toDouble() / b.submittedFrames >= a.validCompletions.toDouble() / a.submittedFrames &&
            b.validCompletions.toDouble() / b.observationDurationMs >= a.validCompletions.toDouble() / a.observationDurationMs &&
            b.staleFrames.toDouble() / b.submittedFrames <= a.staleFrames.toDouble() / a.submittedFrames &&
            b.droppedFrames.toDouble() / b.submittedFrames <= a.droppedFrames.toDouble() / a.submittedFrames &&
            b.captureToCompleteMs.max() <= a.captureToCompleteMs.max() + clock &&
            b.longestCompletionGapMs <= a.longestCompletionGapMs + clock &&
            b.cameraFrameIntervalMs <= a.cameraFrameIntervalMs + clock &&
            b.trackingCostMs <= a.trackingCostMs + clock && b.uiFrameDelayMs <= a.uiFrameDelayMs + clock

    private fun uncertainty(block: RuntimeComparisonBlock, clock: Double): Double {
        val differences = block.pairs.map { it.baselineMs - it.candidateMs }
        val center = median(differences)
        return maxOf(clock, block.aaVariationMs.max(), 2 * median(differences.map { abs(it - center) }))
    }

    private fun positive(value: Double): Boolean = value.isFinite() && value > 0
    private fun nonnegative(value: Double): Boolean = value.isFinite() && value >= 0
    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else sorted[middle - 1] / 2 + sorted[middle] / 2
    }
}

/** Creates at most the requested next value. No interpreter or 1..N collection is allocated. */
class RuntimeCandidateQueue internal constructor(
    private val limit: Int,
    private var baseline: RuntimeCandidate,
    private val gpuSupported: Boolean,
    initialGpuThreads: Int,
    restored: RuntimeCandidateQueueSnapshot?,
) {
    private val initialGpuThreads = initialGpuThreads.coerceIn(1, limit)
    private val cpuBaseline = if (baseline.backend == RuntimeBackend.CPU) baseline else RuntimeSelectionPolicy.initialCpuCandidate(limit)
    private var preferred: RuntimeBackend? = null
    private val anchors = mutableMapOf(RuntimeBackend.CPU to cpuBaseline.numThreads,
        RuntimeBackend.GPU to if (baseline.backend == RuntimeBackend.GPU) baseline.numThreads else this.initialGpuThreads)
    private val cursors = mutableMapOf(RuntimeBackend.CPU to 1, RuntimeBackend.GPU to 1)
    private val outcomes = linkedMapOf<RuntimeCandidate, RuntimeSelectionStatus>()
    private var gpuVisited = !gpuSupported
    private var baselineVisited = false
    private var alternate = RuntimeBackend.GPU
    private var inFlight: RuntimeCandidate? = null
    private var invalidated = false

    init {
        require(RuntimeSelectionPolicy.isExecutable(baseline, limit))
        if (restored != null && restored.availableProcessors == limit && restored.baseline == baseline &&
            restored.gpuSupported == gpuSupported && restored.initialGpuThreads == this.initialGpuThreads &&
            listOf(restored.cpuAnchor, restored.gpuAnchor, restored.cpuCursor, restored.gpuCursor).all { it in 1..limit } &&
            restored.outcomes.all { RuntimeSelectionPolicy.isExecutable(it.candidate, limit) } &&
            restored.outcomes.map { it.candidate }.distinct().size == restored.outcomes.size
        ) {
            preferred = restored.preferredBackend
            anchors[RuntimeBackend.CPU] = restored.cpuAnchor
            anchors[RuntimeBackend.GPU] = restored.gpuAnchor
            cursors[RuntimeBackend.CPU] = restored.cpuCursor
            cursors[RuntimeBackend.GPU] = restored.gpuCursor
            gpuVisited = restored.initialGpuVisited || !gpuSupported
            baselineVisited = restored.baselineVisited
            alternate = restored.nextAlternatingBackend
            restored.outcomes.forEach { outcomes[it.candidate] = it.status }
        }
    }

    fun nextCandidate(currentAvailableProcessors: Int): RuntimeCandidate? {
        if (RuntimeSelectionPolicy.processorLimit(currentAvailableProcessors) != limit) invalidated = true
        if (invalidated) return null
        inFlight?.let { return it }
        val next = when {
            !gpuVisited -> { gpuVisited = true; RuntimeCandidate(RuntimeBackend.GPU, initialGpuThreads) }
            !baselineVisited -> { baselineVisited = true; cpuBaseline }
            else -> nextUntried() ?: retryIn(null)
        }
        inFlight = next
        return next
    }

    fun recordOutcome(
        candidate: RuntimeCandidate,
        status: RuntimeSelectionStatus,
        preferredBackend: RuntimeBackend? = null,
        selectedCandidate: RuntimeCandidate? = if (status == RuntimeSelectionStatus.CONFIRMED) candidate else null,
    ) {
        require(candidate == inFlight) { "only the in-flight candidate can complete" }
        require(selectedCandidate == null || RuntimeSelectionPolicy.isExecutable(selectedCandidate, limit))
        outcomes.remove(candidate)
        outcomes[candidate] = status
        if (preferredBackend != null && (gpuSupported || preferredBackend == RuntimeBackend.CPU)) preferred = preferredBackend
        if (status == RuntimeSelectionStatus.CONFIRMED && selectedCandidate != null) {
            anchors[selectedCandidate.backend] = selectedCandidate.numThreads
            baseline = selectedCandidate
        }
        if (status == RuntimeSelectionStatus.REJECTED && candidate.backend == RuntimeBackend.GPU && preferred == null) {
            preferred = RuntimeBackend.CPU
        }
        if (status == RuntimeSelectionStatus.UNSUPPORTED && candidate.backend == RuntimeBackend.GPU) preferred = RuntimeBackend.CPU
        inFlight = null
    }

    /** Final re-created candidate confirmation after the screening worker has already closed B. */
    fun recordFinalDecision(decision: RuntimeSelectionDecision): Boolean {
        val compared = decision.comparedCandidate ?: return false
        if (invalidated || inFlight != null || decision.baselineCandidate != baseline || compared !in outcomes ||
            decision.measurement?.baselineLive?.environment?.availableProcessors != limit ||
            RuntimeTuningProfile.fromConfirmed(decision, 1) == null ||
            !RuntimeSelectionPolicy.isExecutable(decision.selectedCandidate, limit)
        ) return false
        outcomes.remove(compared)
        outcomes[compared] = RuntimeSelectionStatus.CONFIRMED
        baseline = decision.selectedCandidate
        anchors[baseline.backend] = baseline.numThreads
        if (decision.reason != "equivalent_baseline_retained") preferred = baseline.backend
        return true
    }

    /** Unvisited values alone are not an unfinished comparison or a retry request. */
    fun hasPendingReevaluation(): Boolean = invalidated || inFlight != null || outcomes.any {
        (it.value == RuntimeSelectionStatus.PARTIAL || it.value == RuntimeSelectionStatus.INCONCLUSIVE) &&
            (preferred == null || it.key.backend == preferred)
    }

    fun snapshot(): RuntimeCandidateQueueSnapshot {
        val saved = outcomes.map { RuntimeCandidateOutcome(it.key, it.value) }.toMutableList()
        inFlight?.let { candidate ->
            saved.removeAll { it.candidate == candidate }
            saved.add(RuntimeCandidateOutcome(candidate, RuntimeSelectionStatus.PARTIAL))
        }
        return RuntimeCandidateQueueSnapshot(
            limit, baseline, gpuSupported, initialGpuThreads, preferred,
            anchors.getValue(RuntimeBackend.CPU), anchors.getValue(RuntimeBackend.GPU),
            cursors.getValue(RuntimeBackend.CPU), cursors.getValue(RuntimeBackend.GPU),
            gpuVisited, baselineVisited, alternate, saved,
        )
    }

    private fun nextUntried(): RuntimeCandidate? {
        val first = preferred ?: alternate
        val second = if (first == RuntimeBackend.CPU) RuntimeBackend.GPU else RuntimeBackend.CPU
        if (preferred == null) alternate = second
        return untriedIn(first) ?: (if (preferred != null) retryIn(first) else null) ?: untriedIn(second)
    }

    private fun retryIn(backend: RuntimeBackend?): RuntimeCandidate? = outcomes.entries.firstOrNull {
        (backend == null || it.key.backend == backend) && !unsupported(it.key.backend) &&
            (it.value == RuntimeSelectionStatus.PARTIAL || it.value == RuntimeSelectionStatus.INCONCLUSIVE)
    }?.key

    private fun untriedIn(backend: RuntimeBackend): RuntimeCandidate? {
        if (unsupported(backend)) return null
        val anchor = anchors.getValue(backend)
        // Long intermediates retain the full positive Int domain at the upper boundary.
        for (neighbor in listOf(anchor.toLong() - 1, anchor.toLong() + 1)) {
            if (neighbor in 1L..limit.toLong()) {
                val candidate = RuntimeCandidate(backend, neighbor.toInt())
                if (candidate !in outcomes) return candidate
            }
        }
        val start = cursors.getValue(backend)
        var value = start
        do {
            val candidate = RuntimeCandidate(backend, value)
            value = if (value == limit) 1 else value + 1
            cursors[backend] = value
            if (candidate !in outcomes) return candidate
        } while (value != start)
        return null
    }

    private fun unsupported(backend: RuntimeBackend): Boolean = backend == RuntimeBackend.GPU &&
        (!gpuSupported || outcomes.any { it.key.backend == backend && it.value == RuntimeSelectionStatus.UNSUPPORTED })
}

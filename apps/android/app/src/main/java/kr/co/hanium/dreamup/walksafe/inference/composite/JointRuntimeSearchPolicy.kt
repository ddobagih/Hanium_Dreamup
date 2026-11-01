package kr.co.hanium.dreamup.walksafe.inference.composite

import kr.co.hanium.dreamup.walksafe.inference.RuntimeBackend
import kr.co.hanium.dreamup.walksafe.inference.RuntimeCandidate
import kotlin.math.ceil

enum class JointRuntimeSearchPhase { PROVISIONAL, REFERENCE, FINAL, CLEANUP }
enum class JointRuntimeSearchFailureKind {
    NATIVE_TIMEOUT, NATIVE_FAILED, BUDGET_SKIPPED, PERFORMANCE_FAILED,
    ACCURACY_UNCONFIRMED, RESOURCE_BUDGET, CANCELLED,
}
data class JointRuntimeSearchFailure(val kind: JointRuntimeSearchFailureKind, val detail: String)
data class JointRuntimePhaseBudget(val phase: JointRuntimeSearchPhase, val startedAtMs: Long, val deadlineMs: Long)

/** Timing screen only: no reference equivalence, sustained workload or usable-choice claim. */
data class JointRuntimeProvisionalMeasurement(
    val candidate: JointRuntimeCandidate,
    val durationMs: Long,
    val primarySubmittedFrames: Int,
    val auxiliarySubmittedFrames: Int,
    val primaryTurnaroundMs: List<Double>,
    val auxiliaryTurnaroundMs: List<Double>,
    val actualPrimary: RuntimeCandidate,
    val actualAuxiliary: RuntimeCandidate,
    val fallbackUsed: Boolean,
    val thermalStatus: Int,
)
data class JointRuntimeProvisionalAssessment(
    val measurement: JointRuntimeProvisionalMeasurement?, val failure: JointRuntimeSearchFailure?,
)
data class JointRuntimeFinalPlan(val candidate: JointRuntimeCandidate, val budget: JointRuntimePhaseBudget,
    val provisional: JointRuntimeProvisionalMeasurement)

/**
 * Plans work inside the original check's elapsed-realtime deadline. A model's native watchdog and
 * physical cleanup remain the caller's responsibility. A budget expiry is never a release receipt.
 * Reference output generation has no 800ms performance gate: a slow CPU1 may still establish exact
 * reference outputs. Only final strict equivalence and sustained evidence can issue a validated
 * choice. After one final plan is issued, its failure ends this attempt; there is no hidden retry.
 */
class JointRuntimeSearchPolicy(
    private val startedAtMs: Long,
    private val deadlineMs: Long,
    availableProcessors: Int,
) {
    init {
        require(startedAtMs >= 0 && deadlineMs > startedAtMs && deadlineMs - startedAtMs <= TOTAL_MAXIMUM_MS)
        require(availableProcessors > 0)
    }
    val candidates: List<JointRuntimeCandidate> = buildCandidates(availableProcessors)
    private var phase = JointRuntimeSearchPhase.PROVISIONAL
    private var lastNowMs = startedAtMs
    private var clockInvalid = false
    private var referenceBudget: JointRuntimePhaseBudget? = null
    private var referenceFinished = false
    private var referenceVerified = false
    private var finalIssued = false
    private var cleanupPlan: JointRuntimePhaseBudget? = null
    @Volatile var lastFailure: JointRuntimeSearchFailure? = null
        private set

    /** Each returned absolute deadline must be captured once by that trial, never restarted by polling. */
    @Synchronized fun provisionalBudget(nowMs: Long): JointRuntimePhaseBudget? {
        if (!observeTime(nowMs) || phase != JointRuntimeSearchPhase.PROVISIONAL) return null
        val end = deadlineMs - REFERENCE_MS - FINAL_MS - CLEANUP_MS
        if (end < nowMs || end - nowMs < MINIMUM_PROVISIONAL_REMAINING_MS) return budgetFailure("provisional_budget_reserved")
        return JointRuntimePhaseBudget(JointRuntimeSearchPhase.PROVISIONAL, nowMs, end)
    }

    /** CPU1 references run once after timing screens, before the final candidate is created. */
    @Synchronized fun beginReference(nowMs: Long): JointRuntimePhaseBudget? {
        if (!observeTime(nowMs) || phase != JointRuntimeSearchPhase.PROVISIONAL || referenceBudget != null) return null
        if (deadlineMs - nowMs < REFERENCE_MS + FINAL_MS + CLEANUP_MS) return budgetFailure("reference_reservation_missing")
        phase = JointRuntimeSearchPhase.REFERENCE
        return JointRuntimePhaseBudget(phase, nowMs, minOf(add(nowMs, REFERENCE_MS), deadlineMs - FINAL_MS - CLEANUP_MS))
            .also { referenceBudget = it }
    }

    @Synchronized fun finishReference(succeeded: Boolean, completedAtMs: Long): Boolean {
        if (!observeTime(completedAtMs) || phase != JointRuntimeSearchPhase.REFERENCE || referenceFinished) return false
        referenceFinished = true
        referenceVerified = succeeded && completedAtMs < requireNotNull(referenceBudget).deadlineMs
        if (!referenceVerified) lastFailure = failure(JointRuntimeSearchFailureKind.ACCURACY_UNCONFIRMED, "reference_unverified")
        return referenceVerified
    }

    /** Selects one timing-ranked candidate; a provisional DTO is never converted to a validated choice. */
    @Synchronized fun beginFinal(nowMs: Long,
        measurements: Iterable<JointRuntimeProvisionalMeasurement>): JointRuntimeFinalPlan? {
        if (!observeTime(nowMs) || finalIssued || phase != JointRuntimeSearchPhase.REFERENCE || !referenceFinished || !referenceVerified) return null
        if (deadlineMs - nowMs < FINAL_MS + CLEANUP_MS) return budgetFailure("final_reservation_missing")
        val assessed = measurements.take(candidates.size + 1).map(::assessProvisional)
        if (assessed.size > candidates.size) {
            lastFailure = failure(JointRuntimeSearchFailureKind.ACCURACY_UNCONFIRMED, "provisional_count_invalid")
            return null
        }
        val measured = assessed.mapNotNull { it.measurement }
        if (measured.map { it.candidate }.distinct().size != measured.size) {
            lastFailure = failure(JointRuntimeSearchFailureKind.ACCURACY_UNCONFIRMED, "provisional_candidate_duplicate")
            return null
        }
        val selected = measured.minWithOrNull(compareBy<JointRuntimeProvisionalMeasurement>(
            { percentile95(it.primaryTurnaroundMs) },
            { -it.auxiliaryTurnaroundMs.count { t -> t <= JointRuntimeChoicePolicy.MAXIMUM_TURNAROUND_MS }.toDouble() / it.durationMs },
            { percentile95(it.auxiliaryTurnaroundMs) },
            { it.candidate.primary.numThreads + it.candidate.auxiliary.numThreads },
        )) ?: run {
            lastFailure = failure(if (assessed.isNotEmpty() && assessed.all { it.failure?.kind == JointRuntimeSearchFailureKind.PERFORMANCE_FAILED })
                JointRuntimeSearchFailureKind.PERFORMANCE_FAILED else JointRuntimeSearchFailureKind.ACCURACY_UNCONFIRMED,
                "no_measured_provisional_candidate")
            return null
        }
        finalIssued = true
        phase = JointRuntimeSearchPhase.FINAL
        return JointRuntimeFinalPlan(selected.candidate,
            JointRuntimePhaseBudget(phase, nowMs, minOf(add(nowMs, FINAL_MS), deadlineMs - CLEANUP_MS)), selected)
    }

    /** May stop any phase. After expiry, the caller must still continue independent EXIT/drain work. */
    @Synchronized fun cleanupBudget(nowMs: Long): JointRuntimePhaseBudget? {
        if (!observeTime(nowMs) || nowMs >= deadlineMs) return null
        cleanupPlan?.let { return it.takeIf { budget -> nowMs < budget.deadlineMs } }
        phase = JointRuntimeSearchPhase.CLEANUP
        return JointRuntimePhaseBudget(phase, nowMs, minOf(add(nowMs, CLEANUP_MS), deadlineMs)).also { cleanupPlan = it }
    }

    fun assessProvisional(measurement: JointRuntimeProvisionalMeasurement): JointRuntimeProvisionalAssessment {
        val m = measurement.copy(primaryTurnaroundMs = measurement.primaryTurnaroundMs.take(MAXIMUM_PROVISIONAL_SAMPLES + 1),
            auxiliaryTurnaroundMs = measurement.auxiliaryTurnaroundMs.take(MAXIMUM_PROVISIONAL_SAMPLES + 1))
        fun reject(kind: JointRuntimeSearchFailureKind, reason: String) = JointRuntimeProvisionalAssessment(null, failure(kind, reason))
        if (m.candidate !in candidates || m.actualPrimary != m.candidate.primary || m.actualAuxiliary != m.candidate.auxiliary || m.fallbackUsed)
            return reject(JointRuntimeSearchFailureKind.ACCURACY_UNCONFIRMED, "provisional_runtime_unverified")
        if (m.durationMs !in MINIMUM_PROVISIONAL_DURATION_MS..MAXIMUM_PROVISIONAL_DURATION_MS ||
            !validTimings(m.primaryTurnaroundMs, m.durationMs) || !validTimings(m.auxiliaryTurnaroundMs, m.durationMs) ||
            m.primarySubmittedFrames !in m.primaryTurnaroundMs.size..MAXIMUM_PROVISIONAL_SAMPLES ||
            m.auxiliarySubmittedFrames !in m.auxiliaryTurnaroundMs.size..MAXIMUM_PROVISIONAL_SAMPLES)
            return reject(JointRuntimeSearchFailureKind.ACCURACY_UNCONFIRMED, "provisional_timing_incomplete")
        if (m.thermalStatus !in -1..1 || m.primaryTurnaroundMs.any { it > JointRuntimeChoicePolicy.MAXIMUM_TURNAROUND_MS } ||
            m.primaryTurnaroundMs.size.toDouble() / m.primarySubmittedFrames < JointRuntimeChoicePolicy.MINIMUM_COMPLETION_FRACTION ||
            m.auxiliaryTurnaroundMs.count { it <= JointRuntimeChoicePolicy.MAXIMUM_TURNAROUND_MS }.toDouble() /
                m.auxiliarySubmittedFrames < JointRuntimeChoicePolicy.MINIMUM_COMPLETION_FRACTION)
            return reject(JointRuntimeSearchFailureKind.PERFORMANCE_FAILED, "provisional_performance_limit")
        if (percentile95(m.primaryTurnaroundMs) > JointRuntimeChoicePolicy.MAXIMUM_SAMPLE_P95_MS ||
            percentile95(m.auxiliaryTurnaroundMs) > JointRuntimeChoicePolicy.MAXIMUM_SAMPLE_P95_MS)
            return reject(JointRuntimeSearchFailureKind.PERFORMANCE_FAILED, "provisional_runtime_headroom")
        return JointRuntimeProvisionalAssessment(m, null)
    }

    private fun observeTime(nowMs: Long): Boolean {
        if (clockInvalid || nowMs < startedAtMs || nowMs < lastNowMs) {
            clockInvalid = true
            lastFailure = failure(JointRuntimeSearchFailureKind.ACCURACY_UNCONFIRMED, "monotonic_time_invalid")
            return false
        }
        lastNowMs = nowMs
        return true
    }
    private fun <T> budgetFailure(detail: String): T? {
        lastFailure = failure(JointRuntimeSearchFailureKind.BUDGET_SKIPPED, detail)
        return null
    }
    private fun failure(kind: JointRuntimeSearchFailureKind, detail: String) = JointRuntimeSearchFailure(kind, detail)
    private fun validTimings(values: List<Double>, durationMs: Long) = values.size in MINIMUM_PROVISIONAL_COMPLETIONS..MAXIMUM_PROVISIONAL_SAMPLES &&
        values.all { it.isFinite() && it > 0 && it <= durationMs }
    private fun percentile95(values: List<Double>) = values.sorted()[(ceil(values.size * .95).toInt() - 1).coerceAtLeast(0)]
    private fun add(at: Long, amount: Long) = if (at > Long.MAX_VALUE - amount) Long.MAX_VALUE else at + amount

    companion object {
        const val TOTAL_MAXIMUM_MS = 300_000L
        const val REFERENCE_MS = 70_000L
        const val FINAL_MS = 90_000L
        const val CLEANUP_MS = 10_000L
        const val MINIMUM_PROVISIONAL_DURATION_MS = 2_000L
        const val MAXIMUM_PROVISIONAL_DURATION_MS = 10_000L
        const val MINIMUM_PROVISIONAL_COMPLETIONS = 2
        const val MAXIMUM_PROVISIONAL_SAMPLES = 64
        private const val MINIMUM_PROVISIONAL_REMAINING_MS = 5_000L

        private fun buildCandidates(processors: Int): List<JointRuntimeCandidate> {
            val cpu1 = RuntimeCandidate(RuntimeBackend.CPU, 1)
            val gpu1 = RuntimeCandidate(RuntimeBackend.GPU, 1)
            val cpu2 = RuntimeCandidate(RuntimeBackend.CPU, 2)
            return buildList {
                add(JointRuntimeCandidate(gpu1, gpu1, 250, 500))
                add(JointRuntimeCandidate(gpu1, cpu1, 250, 500))
                add(JointRuntimeCandidate(cpu1, gpu1, 250, 500))
                add(JointRuntimeCandidate(cpu1, cpu1, 400, 600))
                if (processors >= 2) add(JointRuntimeCandidate(cpu2, cpu2, 250, 500))
                if (processors >= 2) {
                    add(JointRuntimeCandidate(gpu1, cpu2, 250, 500))
                    add(JointRuntimeCandidate(cpu2, gpu1, 250, 500))
                }
                add(JointRuntimeCandidate(gpu1, gpu1, 200, 400))
            }
        }
    }
}

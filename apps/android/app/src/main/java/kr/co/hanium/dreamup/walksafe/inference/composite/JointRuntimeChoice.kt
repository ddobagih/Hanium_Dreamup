package kr.co.hanium.dreamup.walksafe.inference.composite

import kr.co.hanium.dreamup.walksafe.inference.RuntimeCandidate
import kr.co.hanium.dreamup.walksafe.inference.RuntimeFeatureScope
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** The two execution settings and cadences actually exercised together by the device check. */
data class JointRuntimeCandidate(
    val primary: RuntimeCandidate,
    val auxiliary: RuntimeCandidate,
    val primaryIntervalMs: Long = 200L,
    val auxiliaryIntervalMs: Long = 400L,
)

data class JointCameraMode(val width: Int, val height: Int, val fps: Int, val displayRefreshHz: Double) {
    fun isValid(): Boolean = width in 1..8192 && height in 1..8192 && fps in 1..240 &&
        displayRefreshHz.isFinite() && displayRefreshHz in 1.0..360.0
}

/**
 * Only live, source-qualified inputs belong here. Ages are capture-to-consumer completion, not
 * native invocation time. submittedFrames counts auxiliary submissions; primary may run faster.
 * sourceQualified means the runner checked the scope's actual pose/tracking and distance-source
 * freshness before collecting the inputs; a model invocation alone does not qualify a frame.
 * No image, account identifier, past scheduler ticket or mutable EWMA state is retained.
 */
data class JointRuntimeEvidence(
    val durationMs: Long,
    val submittedFrames: Int,
    val primaryAgesMs: List<Double>,
    val auxiliaryAgesMs: List<Double>,
    val freshAuxiliaryFrames: Int,
    val staleAuxiliaryFrames: Int,
    val primaryLongestGapMs: Double,
    val auxiliaryLongestGapMs: Double,
    val uiDelayMs: List<Double>,
    val uiBudgetMs: Double,
    val cameraIntervalMs: List<Double>,
    val cameraBudgetMs: Double,
    val fixtureEquivalent: Boolean,
    val sourceQualified: Boolean,
    val fallbackUsed: Boolean,
    val thermalStatus: Int,
    val cameraWidth: Int,
    val cameraHeight: Int,
    val cameraFps: Int,
    val displayRefreshHz: Double,
    val actualPrimary: RuntimeCandidate,
    val actualAuxiliary: RuntimeCandidate,
    val primarySubmittedFrames: Int = primaryAgesMs.size,
) {
    val cameraMode: JointCameraMode get() = JointCameraMode(cameraWidth, cameraHeight, cameraFps, displayRefreshHz)
    internal fun snapshot() = copy(primaryAgesMs = primaryAgesMs.toList(), auxiliaryAgesMs = auxiliaryAgesMs.toList(),
        uiDelayMs = uiDelayMs.toList(), cameraIntervalMs = cameraIntervalMs.toList())
}

/** A usable measured combination, never a comparative-improvement RuntimeTuningProfile. */
@ConsistentCopyVisibility
data class ValidatedJointRuntimeChoice internal constructor(
    val bindingHash: String,
    val candidate: JointRuntimeCandidate,
    val scope: RuntimeFeatureScope,
    val evidence: JointRuntimeEvidence,
    val policyVersion: String = JointRuntimeChoicePolicy.VERSION,
) {
    val cameraMode: JointCameraMode get() = evidence.cameraMode

    fun isValid(expectedBinding: String, availableProcessors: Int): Boolean =
        bindingHash == expectedBinding && policyVersion == JointRuntimeChoicePolicy.VERSION &&
            JointRuntimeChoicePolicy.validHash(bindingHash) &&
            JointRuntimeChoicePolicy.validateCandidate(candidate, scope, evidence, availableProcessors)
}

/** Engineering admission budgets for a measured workload; they do not establish field accuracy. */
object JointRuntimeChoicePolicy {
    const val VERSION = "joint-camera-runtime-768-v1"
    const val MAXIMUM_SOURCE_AGE_MS = 800L
    const val MINIMUM_DURATION_MS = 10_000L
    const val MINIMUM_COMPLETIONS = 10
    const val MAXIMUM_SAMPLES = 4096
    const val MINIMUM_FRESH_FRACTION = 0.8
    private val hashPattern = Regex("[a-f0-9]{64}")

    fun validHash(value: String): Boolean = hashPattern.matches(value)

    fun validateCandidate(candidate: JointRuntimeCandidate, scope: RuntimeFeatureScope,
        evidence: JointRuntimeEvidence, availableProcessors: Int): Boolean =
        rejectionReason(candidate, scope, evidence, availableProcessors) == null

    fun rejectionReason(candidate: JointRuntimeCandidate, scope: RuntimeFeatureScope,
        e: JointRuntimeEvidence, availableProcessors: Int): String? {
        if (availableProcessors < 1 || listOf(candidate.primary, candidate.auxiliary).any {
                it.numThreads !in 1..minOf(4, availableProcessors)
            } || candidate.primaryIntervalMs !in 100L..600L || candidate.auxiliaryIntervalMs !in 100L..600L) return "candidate"
        if (candidate.primary != e.actualPrimary || candidate.auxiliary != e.actualAuxiliary) return "actual_runtime"
        if (!e.fixtureEquivalent || !e.sourceQualified || e.fallbackUsed) return "execution_evidence"
        // Android THERMAL_STATUS_MODERATE and above are not a stable calibration environment.
        if (e.thermalStatus !in 0..1) return "thermal"
        if (e.durationMs !in MINIMUM_DURATION_MS..300_000L || !e.cameraMode.isValid()) return "observation"
        if (!validSamples(e.primaryAgesMs, positive = true) || !validSamples(e.auxiliaryAgesMs, positive = true) ||
            e.primaryAgesMs.size < MINIMUM_COMPLETIONS || e.auxiliaryAgesMs.size < MINIMUM_COMPLETIONS ||
            e.primarySubmittedFrames !in e.primaryAgesMs.size..MAXIMUM_SAMPLES ||
            e.submittedFrames !in e.auxiliaryAgesMs.size..MAXIMUM_SAMPLES) return "completion_count"
        if (e.primaryAgesMs.any { it > MAXIMUM_SOURCE_AGE_MS }) return "primary_age"
        val fresh = e.auxiliaryAgesMs.count { it <= MAXIMUM_SOURCE_AGE_MS }
        val stale = e.auxiliaryAgesMs.size - fresh
        if (e.freshAuxiliaryFrames != fresh || e.staleAuxiliaryFrames != stale || fresh < MINIMUM_COMPLETIONS ||
            fresh.toDouble() / e.submittedFrames < MINIMUM_FRESH_FRACTION) return "auxiliary_age"
        if (!positive(e.primaryLongestGapMs) || !positive(e.auxiliaryLongestGapMs) ||
            e.primaryLongestGapMs > candidate.primaryIntervalMs + MAXIMUM_SOURCE_AGE_MS ||
            e.auxiliaryLongestGapMs > candidate.auxiliaryIntervalMs + MAXIMUM_SOURCE_AGE_MS) return "completion_gap"
        if (!positive(e.uiBudgetMs) || !positive(e.cameraBudgetMs) ||
            abs(e.uiBudgetMs - 1000.0 / e.displayRefreshHz) > 1.0 ||
            abs(e.cameraBudgetMs - 1000.0 / e.cameraFps) > 1.0) return "mode_budget"
        if (!validSamples(e.uiDelayMs, positive = false) || !validSamples(e.cameraIntervalMs, positive = true) ||
            e.uiDelayMs.size < MINIMUM_COMPLETIONS || e.cameraIntervalMs.size < MINIMUM_COMPLETIONS) return "load_evidence"
        if (percentile95(e.uiDelayMs) > e.uiBudgetMs * 2.0 || e.uiDelayMs.max() > 250.0) return "ui_load"
        if (percentile95(e.cameraIntervalMs) > e.cameraBudgetMs * 2.0 ||
            e.cameraIntervalMs.max() > max(250.0, e.cameraBudgetMs * 4.0)) return "camera_load"
        return null
    }

    fun evaluate(bindingHash: String, candidate: JointRuntimeCandidate, scope: RuntimeFeatureScope,
        evidence: JointRuntimeEvidence, availableProcessors: Int): ValidatedJointRuntimeChoice? =
        if (validHash(bindingHash) && validateCandidate(candidate, scope, evidence, availableProcessors))
            ValidatedJointRuntimeChoice(bindingHash, candidate, scope, evidence.snapshot()) else null

    /** Rejected candidates are ignored independently; an unusable CPU result cannot block GPU. */
    fun select(choices: Iterable<ValidatedJointRuntimeChoice>, availableProcessors: Int): ValidatedJointRuntimeChoice? =
        choices.filter { it.isValid(it.bindingHash, availableProcessors) }
            .minWithOrNull(compareBy<ValidatedJointRuntimeChoice>(
                { percentile95(it.evidence.uiDelayMs) / it.evidence.uiBudgetMs },
                { percentile95(it.evidence.primaryAgesMs) },
                { -it.evidence.freshAuxiliaryFrames.toDouble() / it.evidence.durationMs },
                { percentile95(it.evidence.auxiliaryAgesMs) },
                { it.candidate.primary.numThreads + it.candidate.auxiliary.numThreads },
            ))

    internal fun percentile95(values: List<Double>): Double = values.sorted()[(ceil(values.size * 0.95).toInt() - 1).coerceAtLeast(0)]
    private fun positive(value: Double) = value.isFinite() && value > 0.0
    private fun validSamples(values: List<Double>, positive: Boolean): Boolean =
        values.size in 1..MAXIMUM_SAMPLES && values.all { it.isFinite() && (if (positive) it > 0.0 else it >= 0.0) }
}

package kr.co.hanium.dreamup.walksafe.inference.unknown.tuning

import kr.co.hanium.dreamup.walksafe.inference.*
import java.security.MessageDigest
import kotlin.math.abs

/** Allows a warmed service to move from calibration to the production callback without reloading. */
interface UnknownRuntimeAttachment

/** Immutable workload identity. Runtime choices being compared belong in the candidate, not this hash. */
data class UnknownRuntimeBinding(
    val primarySha256: String,
    val fastSha256: String,
    val primaryPreprocessing: String,
    val fastPreprocessing: String,
    val primaryDecoder: String,
    val fastDecoder: String,
    val primaryRuntime: RuntimeCandidate,
    val scheduleVersion: String,
    val deviceIdentity: String,
    val runtimeVersion: String,
    val environmentVersion: String,
    val fixtureVersion: String,
    val fixtureSha256: String,
    val featureScope: RuntimeFeatureScope,
) {
    fun hashOrNull(): String? {
        if (!HASH.matches(primarySha256) || !HASH.matches(fastSha256) || !HASH.matches(fixtureSha256)) return null
        val fields = listOf(primarySha256, fastSha256, primaryPreprocessing, fastPreprocessing,
            primaryDecoder, fastDecoder, primaryRuntime.backend.name, primaryRuntime.numThreads.toString(),
            scheduleVersion, deviceIdentity, runtimeVersion, environmentVersion, fixtureVersion,
            fixtureSha256, featureScope.name, UnknownRuntimeTuning.POLICY_VERSION)
        if (fields.any { it.isBlank() || it.length > 4096 }) return null
        return MessageDigest.getInstance("SHA-256").digest(
            fields.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8),
        ).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    companion object { private val HASH = Regex("[0-9a-f]{64}") }
}

/** Primary stream while the same FastSAM workload is enabled. Never populate this from primary-only. */
data class UnknownPrimaryLoad(
    val durationMs: Long,
    val captureToCompleteMs: List<Double>,
    val longestCompletionGapMs: Double,
    val staleOrDropped: Int,
    val repeatedLatencyVariationMs: Double = 0.0,
    val repeatedGapVariationMs: Double = 0.0,
    val repeatedRateVariationPerMs: Double = 0.0,
    val repeatedStaleFractionVariation: Double = 0.0,
) {
    internal fun valid(): Boolean = durationMs >= RuntimeSelectionPolicy.MIN_LIVE_DURATION_MS &&
        captureToCompleteMs.size >= RuntimeSelectionPolicy.MIN_LIVE_COMPLETIONS &&
        captureToCompleteMs.all { it.isFinite() && it > 0 } &&
        longestCompletionGapMs.isFinite() && longestCompletionGapMs > 0 &&
        staleOrDropped in 0..captureToCompleteMs.size &&
        listOf(repeatedLatencyVariationMs, repeatedGapVariationMs, repeatedRateVariationPerMs,
            repeatedStaleFractionVariation).all { it.isFinite() && it >= 0 } && repeatedStaleFractionVariation <= 1
}

@ConsistentCopyVisibility
data class UnknownRuntimeProfile internal constructor(
    val runtime: RuntimeTuningProfile,
    val primaryBaseline: UnknownPrimaryLoad,
    val primarySelected: UnknownPrimaryLoad,
    val policyVersion: String = UnknownRuntimeTuning.POLICY_VERSION,
) {
    val candidate: RuntimeCandidate get() = runtime.candidate
    fun isValid(binding: UnknownRuntimeBinding, processors: Int): Boolean {
        val hash = binding.hashOrNull() ?: return false
        return policyVersion == UnknownRuntimeTuning.POLICY_VERSION && runtime.isValid(hash, processors) &&
            RuntimeSelectionPolicy.isExecutable(binding.primaryRuntime, processors) &&
            runtime.featureScope == binding.featureScope && runtime.fixtureVersion == binding.fixtureVersion &&
            runtime.fixtureHash == binding.fixtureSha256 &&
            UnknownRuntimeTuning.primaryNotWorse(primaryBaseline, primarySelected)
    }
}

/** Extends the existing selection gates only with the primary stream's independent non-regression. */
object UnknownRuntimeTuning {
    const val POLICY_VERSION = "unknown-composition-768-v2"
    const val MAX_BUDGET_MS = RuntimeSelectionPolicy.MAX_BUDGET_MS
    const val CLEANUP_RESERVE_MS = 20_000L

    fun confirm(
        binding: UnknownRuntimeBinding,
        baseline: RuntimeCandidate,
        candidate: RuntimeCandidate,
        summary: RuntimeMeasurementSummary,
        primaryBaseline: UnknownPrimaryLoad,
        primaryCandidate: UnknownPrimaryLoad,
        nowElapsedMs: Long,
        deadlineElapsedMs: Long,
        measuredAtEpochMs: Long,
    ): UnknownRuntimeProfile? {
        val hash = binding.hashOrNull() ?: return null
        if (summary.baselineLive.environment.bindingHash != hash ||
            summary.candidateLive.environment.bindingHash != hash ||
            summary.baselineLive.environment.featureScope != binding.featureScope ||
            summary.candidateLive.environment.featureScope != binding.featureScope ||
            summary.fixture.version != binding.fixtureVersion || summary.fixture.hash != binding.fixtureSha256) return null
        val decision = RuntimeSelectionPolicy.evaluate(baseline, candidate, summary,
            nowElapsedMs, deadlineElapsedMs, baselinePreviouslyConfirmed = true)
        val selectedPrimary = if (decision.selectedCandidate == baseline) primaryBaseline else primaryCandidate
        if (!primaryNotWorse(primaryBaseline, selectedPrimary)) return null
        val profile = RuntimeTuningProfile.fromConfirmed(decision, measuredAtEpochMs) ?: return null
        return UnknownRuntimeProfile(profile, primaryBaseline, selectedPrimary)
            .takeIf { it.isValid(binding, summary.baselineLive.environment.availableProcessors) }
    }

    fun primaryNotWorse(reference: UnknownPrimaryLoad, selected: UnknownPrimaryLoad): Boolean {
        if (!reference.valid() || !selected.valid()) return false
        return selected.captureToCompleteMs.size.toDouble() / selected.durationMs >=
            reference.captureToCompleteMs.size.toDouble() / reference.durationMs - reference.repeatedRateVariationPerMs &&
            selected.staleOrDropped.toDouble() / selected.captureToCompleteMs.size <=
            reference.staleOrDropped.toDouble() / reference.captureToCompleteMs.size + reference.repeatedStaleFractionVariation &&
            selected.captureToCompleteMs.max() <= reference.captureToCompleteMs.max() + maxOf(1.0, reference.repeatedLatencyVariationMs) &&
            selected.longestCompletionGapMs <= reference.longestCompletionGapMs + maxOf(1.0, reference.repeatedGapVariationMs)
    }

    /** Warmup ends only after three consecutive timings stabilize; there is no fixed sleep. */
    fun warmupStable(timingsMs: List<Double>): Boolean {
        val recent = timingsMs.takeLast(3)
        if (recent.size < 3 || recent.any { !it.isFinite() || it <= 0 }) return false
        return recent.max() - recent.min() <= maxOf(1.0, recent.average() * .10)
    }

    /** Raw evidence uses the very same Android-preprocessed fixture for CPU and candidate. */
    fun equivalentRaw(reference: Array<FloatArray>, candidate: Array<FloatArray>): Boolean =
        reference.size == 2 && candidate.size == 2 && reference.indices.all { output ->
            val a = reference[output]
            val b = candidate[output]
            a.isNotEmpty() && a.size == b.size && a.indices.all { i ->
                a[i].isFinite() && b[i].isFinite() && abs(a[i] - b[i]) <= 0.0001f + .001f * abs(a[i])
            }
        }
}

/** Reuses passive monitoring: streams are kept separate, and only a pending recheck is emitted. */
class UnknownRuntimeLoadControl {
    private val primary = RuntimeLoadMonitor()
    private val auxiliary = RuntimeLoadMonitor()

    fun observe(
        nowMs: Long, binding: String, normalEnvironment: Boolean, inputObserved: Boolean,
        primaryCompletion: RuntimeLoadCompletion? = null, auxiliaryCompletion: RuntimeLoadCompletion? = null,
    ): Boolean {
        val a = primary.observe(nowMs, "$binding:primary", normalEnvironment, inputObserved, primaryCompletion)
        val b = auxiliary.observe(nowMs, "$binding:fast", normalEnvironment, inputObserved, auxiliaryCompletion)
        return a.pendingReason != null || b.pendingReason != null
    }

    fun pause() { primary.pause(); auxiliary.pause() }
    fun reset() { primary.reset(); auxiliary.reset() }
}

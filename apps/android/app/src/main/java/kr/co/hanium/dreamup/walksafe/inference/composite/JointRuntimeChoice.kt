package kr.co.hanium.dreamup.walksafe.inference.composite

import kr.co.hanium.dreamup.walksafe.inference.RuntimeCandidate
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamModelContract
import java.util.UUID
import kotlin.math.ceil

/** The two execution settings and cadences actually exercised together by the device check. */
data class JointRuntimeCandidate(
    val primary: RuntimeCandidate,
    val auxiliary: RuntimeCandidate,
    val primaryIntervalMs: Long = 200L,
    val auxiliaryIntervalMs: Long = 400L,
)

/** Runtime camera geometry only. A sample-image benchmark never verifies this observation. */
data class JointCameraMode(val width: Int, val height: Int, val fps: Int, val displayRefreshHz: Double) {
    fun isValid(): Boolean = width in 1..8192 && height in 1..8192 && fps in 1..240 &&
        displayRefreshHz.isFinite() && displayRefreshHz in 1.0..360.0
}

enum class JointRuntimeMeasurementKind { SAMPLE_IMAGES_TWO_MODEL }

/** Strict gates run against CPU1 reference outputs for each pinned sample; no tensors are stored. */
data class JointFixtureQuality(
    val fixtureId: String,
    val primaryRawValueCount: Int,
    val auxiliaryDetectionValueCount: Int,
    val auxiliaryPrototypeValueCount: Int,
    val primaryOutputEquivalent: Boolean,
    val primaryFixtureRolesMatched: Boolean,
    val auxiliaryRawEquivalent: Boolean,
    val auxiliaryMasksEquivalent: Boolean,
)

/**
 * The measured window starts after both models have initialized and passed their fixture gates.
 * Turnaround is accepted fixture RGB submission through model preprocessing/inference/postprocessing
 * completion on one monotonic clock. It excludes asset decoding, initialization and warmup.
 * Submitted counts are accepted work, not requests skipped by a busy single-flight owner; the
 * difference from the corresponding turnaround-list size is incomplete/dropped accepted work.
 * Longest gaps include the start/end edges of this same shared measurement window.
 * These values establish neither camera capture latency, ARCore/depth quality nor preview/UI load.
 */
data class JointFixtureRuntimeEvidence(
    val durationMs: Long,
    val primarySubmittedFrames: Int,
    val auxiliarySubmittedFrames: Int,
    val primaryTurnaroundMs: List<Double>,
    val auxiliaryTurnaroundMs: List<Double>,
    val primaryLongestGapMs: Double,
    val auxiliaryLongestGapMs: Double,
    val fixtureQuality: List<JointFixtureQuality>,
    val actualPrimary: RuntimeCandidate,
    val actualAuxiliary: RuntimeCandidate,
    val fallbackUsed: Boolean,
    val thermalStatus: Int,
) {
    internal fun snapshot() = copy(primaryTurnaroundMs = primaryTurnaroundMs.toList(),
        auxiliaryTurnaroundMs = auxiliaryTurnaroundMs.toList(), fixtureQuality = fixtureQuality.toList())
}

/** A usable sample-measured combination, never proof of a physical camera/feature configuration. */
@ConsistentCopyVisibility
data class ValidatedJointRuntimeChoice internal constructor(
    val bindingHash: String,
    val candidate: JointRuntimeCandidate,
    val evidence: JointFixtureRuntimeEvidence,
    val measurementKind: JointRuntimeMeasurementKind = JointRuntimeMeasurementKind.SAMPLE_IMAGES_TWO_MODEL,
    val policyVersion: String = JointRuntimeChoicePolicy.VERSION,
    /** One logical measurement; identical timings from another check still get a different ID. */
    val measurementId: String = UUID.randomUUID().toString(),
) {
    fun isValid(expectedBinding: String, availableProcessors: Int): Boolean =
        bindingHash == expectedBinding && policyVersion == JointRuntimeChoicePolicy.VERSION &&
            measurementKind == JointRuntimeMeasurementKind.SAMPLE_IMAGES_TWO_MODEL &&
            JointRuntimeChoicePolicy.validMeasurementId(measurementId) &&
            JointRuntimeChoicePolicy.validHash(bindingHash) &&
            JointRuntimeChoicePolicy.validateCandidate(candidate, evidence, availableProcessors)
}

/** Engineering admission limits for two-model sample processing, not field-accuracy thresholds. */
object JointRuntimeChoicePolicy {
    const val VERSION = "joint-fixture-runtime-768-v3-headroom600"
    const val MAXIMUM_TURNAROUND_MS = 800L
    /** Sample P95 leaves 200ms engineering headroom; it promises neither camera latency nor CPU/GPU utilization. */
    const val MAXIMUM_SAMPLE_P95_MS = 600L
    const val MINIMUM_DURATION_MS = 10_000L
    const val MINIMUM_COMPLETIONS = 10
    const val MAXIMUM_SAMPLES = 4096
    const val MINIMUM_COMPLETION_FRACTION = 0.8
    const val PRIMARY_RAW_VALUE_COUNT = 300 * 6
    val REQUIRED_FIXTURE_IDS: Set<String> = setOf("coco_785", "coco_872", "coco_11149", "black_768")
    private val hashPattern = Regex("[a-f0-9]{64}")

    fun validHash(value: String): Boolean = hashPattern.matches(value)

    fun validMeasurementId(value: String): Boolean = runCatching {
        val uuid = UUID.fromString(value)
        uuid.toString() == value && uuid.version() == 4 && uuid.variant() == 2
    }.getOrDefault(false)

    fun validateCandidate(candidate: JointRuntimeCandidate, evidence: JointFixtureRuntimeEvidence,
        availableProcessors: Int): Boolean = rejectionReason(candidate, evidence, availableProcessors) == null

    fun rejectionReason(candidate: JointRuntimeCandidate, e: JointFixtureRuntimeEvidence,
        availableProcessors: Int): String? {
        if (availableProcessors < 1 || listOf(candidate.primary, candidate.auxiliary).any {
                it.numThreads !in 1..minOf(4, availableProcessors)
            } || candidate.primaryIntervalMs !in 100L..600L || candidate.auxiliaryIntervalMs !in 100L..600L) return "candidate"
        if (candidate.primary != e.actualPrimary || candidate.auxiliary != e.actualAuxiliary || e.fallbackUsed) return "actual_runtime"
        if (e.fixtureQuality.size != REQUIRED_FIXTURE_IDS.size ||
            e.fixtureQuality.map { it.fixtureId }.toSet() != REQUIRED_FIXTURE_IDS || e.fixtureQuality.any {
                it.primaryRawValueCount != PRIMARY_RAW_VALUE_COUNT ||
                    it.auxiliaryDetectionValueCount != FastSamModelContract.DETECTION_FLOATS ||
                    it.auxiliaryPrototypeValueCount != FastSamModelContract.PROTOTYPE_FLOATS ||
                    !it.primaryOutputEquivalent || !it.primaryFixtureRolesMatched ||
                    !it.auxiliaryRawEquivalent || !it.auxiliaryMasksEquivalent
            }) return "fixture_quality"
        // -1 remains explicitly unobserved on platforms without the thermal-status API.
        // The Android store separately requires an actual 0..1 reading when that API exists.
        if (e.thermalStatus !in -1..1) return "thermal"
        if (e.durationMs !in MINIMUM_DURATION_MS..300_000L) return "observation"
        if (!validSamples(e.primaryTurnaroundMs, e.durationMs) || !validSamples(e.auxiliaryTurnaroundMs, e.durationMs) ||
            e.primarySubmittedFrames !in e.primaryTurnaroundMs.size..MAXIMUM_SAMPLES ||
            e.auxiliarySubmittedFrames !in e.auxiliaryTurnaroundMs.size..MAXIMUM_SAMPLES) return "completion_count"
        if (e.primaryTurnaroundMs.any { it > MAXIMUM_TURNAROUND_MS } ||
            e.primaryTurnaroundMs.size.toDouble() / e.primarySubmittedFrames < MINIMUM_COMPLETION_FRACTION) return "primary_turnaround"
        val timelyAuxiliary = e.auxiliaryTurnaroundMs.count { it <= MAXIMUM_TURNAROUND_MS }
        if (timelyAuxiliary < MINIMUM_COMPLETIONS ||
            timelyAuxiliary.toDouble() / e.auxiliarySubmittedFrames < MINIMUM_COMPLETION_FRACTION) return "auxiliary_turnaround"
        if (!positive(e.primaryLongestGapMs) || !positive(e.auxiliaryLongestGapMs) ||
            e.primaryLongestGapMs > minOf(e.durationMs, candidate.primaryIntervalMs + MAXIMUM_TURNAROUND_MS) ||
            e.auxiliaryLongestGapMs > minOf(e.durationMs, candidate.auxiliaryIntervalMs + MAXIMUM_TURNAROUND_MS)) return "completion_gap"
        if (percentile95(e.primaryTurnaroundMs) > MAXIMUM_SAMPLE_P95_MS ||
            percentile95(e.auxiliaryTurnaroundMs) > MAXIMUM_SAMPLE_P95_MS) return "runtime_headroom"
        return null
    }

    fun evaluate(bindingHash: String, candidate: JointRuntimeCandidate, evidence: JointFixtureRuntimeEvidence,
        availableProcessors: Int): ValidatedJointRuntimeChoice? =
        if (validHash(bindingHash) && validateCandidate(candidate, evidence, availableProcessors))
            ValidatedJointRuntimeChoice(bindingHash, candidate, evidence.snapshot()) else null

    /** A failed CPU candidate cannot block a separately qualified GPU candidate. */
    fun select(choices: Iterable<ValidatedJointRuntimeChoice>, availableProcessors: Int): ValidatedJointRuntimeChoice? =
        choices.filter { it.isValid(it.bindingHash, availableProcessors) }
            .minWithOrNull(compareBy<ValidatedJointRuntimeChoice>(
                { percentile95(it.evidence.primaryTurnaroundMs) },
                { -it.evidence.auxiliaryTurnaroundMs.count { age -> age <= MAXIMUM_TURNAROUND_MS }.toDouble() / it.evidence.durationMs },
                { percentile95(it.evidence.auxiliaryTurnaroundMs) },
                { it.candidate.primary.numThreads + it.candidate.auxiliary.numThreads },
            ))

    internal fun percentile95(values: List<Double>): Double = values.sorted()[(ceil(values.size * 0.95).toInt() - 1).coerceAtLeast(0)]
    private fun positive(value: Double) = value.isFinite() && value > 0.0
    private fun validSamples(values: List<Double>, durationMs: Long): Boolean =
        values.size in MINIMUM_COMPLETIONS..MAXIMUM_SAMPLES && values.all { positive(it) && it <= durationMs }
}

package kr.co.hanium.dreamup.walksafe.inference.composite

import kr.co.hanium.dreamup.walksafe.inference.ModelRuntimeConfig
import kr.co.hanium.dreamup.walksafe.inference.ModelRuntimeOptions
import kr.co.hanium.dreamup.walksafe.inference.RuntimeBackend
import kr.co.hanium.dreamup.walksafe.inference.RuntimeCandidate
import kr.co.hanium.dreamup.walksafe.inference.TwoModelRuntimeConfig
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamModelContract

internal object JointRuntimeTestFixtures {
    val binding = "a".repeat(64)
    val candidate = JointRuntimeCandidate(RuntimeCandidate(RuntimeBackend.GPU, 2), RuntimeCandidate(RuntimeBackend.CPU, 2))
    val config = TwoModelRuntimeConfig("joint-test", null, TwoModelRuntimeConfig.UNIFIED_MODEL_KEY,
        null, ModelRuntimeConfig(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, "models/test.tflite", 768,
            listOf("person", "bicycle"), setOf("person", "bicycle"), mapOf("default" to .5f),
            artifactSha256 = binding, runtime = ModelRuntimeOptions("cpu", 2, false)), null, null)

    fun quality() = JointRuntimeChoicePolicy.REQUIRED_FIXTURE_IDS.map {
        JointFixtureQuality(it, JointRuntimeChoicePolicy.PRIMARY_RAW_VALUE_COUNT,
            FastSamModelContract.DETECTION_FLOATS, FastSamModelContract.PROTOTYPE_FLOATS,
            primaryOutputEquivalent = true, primaryFixtureRolesMatched = true,
            auxiliaryRawEquivalent = true, auxiliaryMasksEquivalent = true)
    }

    fun evidence(candidate: JointRuntimeCandidate = this.candidate) = JointFixtureRuntimeEvidence(
        durationMs = 10_000, primarySubmittedFrames = 50, auxiliarySubmittedFrames = 25,
        primaryTurnaroundMs = List(50) { 110.0 }, auxiliaryTurnaroundMs = List(25) { 350.0 },
        primaryLongestGapMs = 210.0, auxiliaryLongestGapMs = 420.0, fixtureQuality = quality(),
        actualPrimary = candidate.primary, actualAuxiliary = candidate.auxiliary, fallbackUsed = false, thermalStatus = 0,
    )

    fun choice(candidate: JointRuntimeCandidate = this.candidate,
        evidence: JointFixtureRuntimeEvidence = evidence(candidate)) = requireNotNull(JointRuntimeChoicePolicy.evaluate(
        binding, candidate, evidence, availableProcessors = 8,
    ))
}

package kr.co.hanium.dreamup.walksafe.inference.composite

import kr.co.hanium.dreamup.walksafe.inference.RuntimeBackend
import kr.co.hanium.dreamup.walksafe.inference.RuntimeCandidate
import kr.co.hanium.dreamup.walksafe.inference.RuntimeFeatureScope
import kr.co.hanium.dreamup.walksafe.inference.TwoModelRuntimeConfig

internal object JointRuntimeTestFixtures {
    val binding = "a".repeat(64)
    val scope = RuntimeFeatureScope.CAMERA_TRACKING_METRIC_DEPTH
    val mode = JointCameraMode(640, 480, 30, 60.0)
    val candidate = JointRuntimeCandidate(RuntimeCandidate(RuntimeBackend.GPU, 2), RuntimeCandidate(RuntimeBackend.CPU, 2))
    val config = TwoModelRuntimeConfig("joint-test", null, TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, null, null, null, null)

    fun evidence(candidate: JointRuntimeCandidate = this.candidate) = JointRuntimeEvidence(
        durationMs = 10_000, submittedFrames = 25, primaryAgesMs = List(50) { 110.0 },
        auxiliaryAgesMs = List(25) { 350.0 }, freshAuxiliaryFrames = 25, staleAuxiliaryFrames = 0,
        primaryLongestGapMs = 210.0, auxiliaryLongestGapMs = 420.0, uiDelayMs = List(60) { 2.0 },
        uiBudgetMs = 1000.0 / mode.displayRefreshHz, cameraIntervalMs = List(60) { 34.0 },
        cameraBudgetMs = 1000.0 / mode.fps, fixtureEquivalent = true, sourceQualified = true,
        fallbackUsed = false, thermalStatus = 0, cameraWidth = mode.width, cameraHeight = mode.height,
        cameraFps = mode.fps, displayRefreshHz = mode.displayRefreshHz,
        actualPrimary = candidate.primary, actualAuxiliary = candidate.auxiliary, primarySubmittedFrames = 50,
    )

    fun choice(candidate: JointRuntimeCandidate = this.candidate,
        evidence: JointRuntimeEvidence = evidence(candidate)) = requireNotNull(JointRuntimeChoicePolicy.evaluate(
        binding, candidate, scope, evidence, availableProcessors = 8,
    ))
}

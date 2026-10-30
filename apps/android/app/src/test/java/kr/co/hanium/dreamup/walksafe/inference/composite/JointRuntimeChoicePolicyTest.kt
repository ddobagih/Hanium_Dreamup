package kr.co.hanium.dreamup.walksafe.inference.composite

import kr.co.hanium.dreamup.walksafe.inference.RuntimeBackend
import kr.co.hanium.dreamup.walksafe.inference.RuntimeCandidate
import org.junit.Assert.*
import org.junit.Test

class JointRuntimeChoicePolicyTest {
    private val f = JointRuntimeTestFixtures

    @Test fun failedCpuCandidateDoesNotBlockAnIndependentlyUsableGpuCandidate() {
        val cpu = f.candidate.copy(primary = RuntimeCandidate(RuntimeBackend.CPU, 4))
        val slowCpu = f.evidence(cpu).copy(primaryAgesMs = List(50) { 950.0 })
        assertNull(JointRuntimeChoicePolicy.evaluate(f.binding, cpu, f.scope, slowCpu, 8))
        val gpu = f.choice()
        // Even an invalid candidate supplied by a corrupt/old caller cannot stop selection.
        val invalidCpu = gpu.copy(candidate = cpu, evidence = slowCpu)
        assertEquals(gpu, JointRuntimeChoicePolicy.select(listOf(invalidCpu, gpu), 8))
        assertEquals(gpu, JointRuntimeChoicePolicy.select(listOf(gpu, invalidCpu), 8))
    }

    @Test fun actualBackendAndThreadIdentityCannotBeClaimedByTheRequestedCandidate() {
        val e = f.evidence()
        listOf(
            e.copy(actualPrimary = RuntimeCandidate(RuntimeBackend.CPU, 2)),
            e.copy(actualPrimary = e.actualPrimary.copy(numThreads = 1)),
            e.copy(actualAuxiliary = RuntimeCandidate(RuntimeBackend.GPU, 2)),
            e.copy(actualAuxiliary = e.actualAuxiliary.copy(numThreads = 1)),
            e.copy(fallbackUsed = true), e.copy(fixtureEquivalent = false), e.copy(sourceQualified = false),
        ).forEach { assertFalse(JointRuntimeChoicePolicy.validateCandidate(f.candidate, f.scope, it, 8)) }
    }

    @Test fun bothModelsNeedCompletionsAndTenSecondsOfFullLoadEvidence() {
        val e = f.evidence()
        listOf(
            e.copy(durationMs = 9_999), e.copy(primaryAgesMs = List(9) { 110.0 }),
            e.copy(auxiliaryAgesMs = List(9) { 350.0 }, freshAuxiliaryFrames = 9),
            e.copy(primarySubmittedFrames = 49), e.copy(submittedFrames = 24),
            e.copy(uiDelayMs = emptyList()), e.copy(cameraIntervalMs = List(9) { 34.0 }),
            e.copy(primaryAgesMs = List(50) { Double.NaN }),
            e.copy(auxiliaryAgesMs = List(25) { Double.POSITIVE_INFINITY }),
        ).forEach { assertNull(JointRuntimeChoicePolicy.evaluate(f.binding, f.candidate, f.scope, it, 8)) }
    }

    @Test fun captureAgeFreshnessAndCompletionGapsAreEnforcedSeparately() {
        val e = f.evidence()
        listOf(
            e.copy(primaryAgesMs = e.primaryAgesMs + 800.01, primarySubmittedFrames = 51),
            e.copy(freshAuxiliaryFrames = 24, staleAuxiliaryFrames = 1),
            e.copy(submittedFrames = 32), // 25 fresh completions / 32 submissions is below 80%.
            e.copy(auxiliaryAgesMs = List(19) { 350.0 } + List(6) { 801.0 },
                freshAuxiliaryFrames = 19, staleAuxiliaryFrames = 6),
            e.copy(primaryLongestGapMs = 1_001.0), e.copy(auxiliaryLongestGapMs = 1_201.0),
        ).forEach { assertFalse(JointRuntimeChoicePolicy.validateCandidate(f.candidate, f.scope, it, 8)) }
        val boundary = e.copy(auxiliaryAgesMs = List(20) { 800.0 } + List(5) { 801.0 },
            freshAuxiliaryFrames = 20, staleAuxiliaryFrames = 5)
        assertTrue(JointRuntimeChoicePolicy.validateCandidate(f.candidate, f.scope, boundary, 8))
    }

    @Test fun uiAndCameraPressureThermalAndFabricatedBudgetsRejectTheCombination() {
        val e = f.evidence()
        listOf(
            e.copy(uiDelayMs = List(60) { 35.0 }), e.copy(uiDelayMs = List(59) { 2.0 } + 251.0),
            e.copy(cameraIntervalMs = List(60) { 68.0 }),
            e.copy(cameraIntervalMs = List(59) { 34.0 } + 251.0),
            e.copy(uiBudgetMs = 1000.0), e.copy(cameraBudgetMs = 1000.0),
            e.copy(displayRefreshHz = Double.NaN), e.copy(cameraFps = 0),
            e.copy(thermalStatus = 2), e.copy(thermalStatus = -1),
        ).forEach { assertFalse(JointRuntimeChoicePolicy.validateCandidate(f.candidate, f.scope, it, 8)) }
    }

    @Test fun threadCountsCannotExceedFourOrTheCurrentProcessorCount() {
        for (threads in listOf(5, 9)) {
            val candidate = f.candidate.copy(primary = f.candidate.primary.copy(numThreads = threads))
            assertNull(JointRuntimeChoicePolicy.evaluate(f.binding, candidate, f.scope, f.evidence(candidate), 8))
        }
        assertNull(JointRuntimeChoicePolicy.evaluate(f.binding, f.candidate, f.scope, f.evidence(), 1))
        assertNotNull(JointRuntimeChoicePolicy.evaluate(f.binding, f.candidate, f.scope, f.evidence(), 2))
    }

    @Test fun selectionPrioritizesUiHeadroomBeforePrimaryLatencyAndAuxiliaryThroughput() {
        val uiResponsive = f.choice(evidence = f.evidence().copy(uiDelayMs = List(60) { 1.0 },
            primaryAgesMs = List(50) { 300.0 }))
        val fasterModel = f.choice(evidence = f.evidence().copy(uiDelayMs = List(60) { 4.0 },
            primaryAgesMs = List(50) { 80.0 }))
        assertEquals(uiResponsive, JointRuntimeChoicePolicy.select(listOf(fasterModel, uiResponsive), 8))
        val freshThroughput = f.choice(evidence = f.evidence().copy(submittedFrames = 30,
            auxiliaryAgesMs = List(30) { 350.0 }, freshAuxiliaryFrames = 30))
        assertEquals(freshThroughput, JointRuntimeChoicePolicy.select(listOf(f.choice(), freshThroughput), 8))
    }

    @Test fun evaluationSnapshotsMeasurementsAndRejectsBadBindingAndOldPolicy() {
        val ages = MutableList(50) { 110.0 }
        val choice = f.choice(evidence = f.evidence().copy(primaryAgesMs = ages))
        ages[0] = 900.0
        assertEquals(110.0, choice.evidence.primaryAgesMs.first(), 0.0)
        assertTrue(choice.isValid(f.binding, 8))
        assertFalse(choice.isValid("b".repeat(64), 8))
        assertFalse(choice.copy(policyVersion = "previous").isValid(f.binding, 8))
        assertNull(JointRuntimeChoicePolicy.evaluate("invalid", f.candidate, f.scope, f.evidence(), 8))
    }
}

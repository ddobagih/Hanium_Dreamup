package kr.co.hanium.dreamup.walksafe.inference.composite

import kr.co.hanium.dreamup.walksafe.inference.RuntimeBackend
import kr.co.hanium.dreamup.walksafe.inference.RuntimeCandidate
import org.junit.Assert.*
import org.junit.Test

class JointRuntimeChoicePolicyTest {
    private val f = JointRuntimeTestFixtures

    @Test fun failedCpuCandidateDoesNotBlockAnIndependentlyUsableGpuCandidate() {
        val cpu = f.candidate.copy(primary = RuntimeCandidate(RuntimeBackend.CPU, 4))
        val slowCpu = f.evidence(cpu).copy(primaryTurnaroundMs = List(50) { 950.0 })
        assertNull(JointRuntimeChoicePolicy.evaluate(f.binding, cpu, slowCpu, 8))
        val gpu = f.choice()
        val invalidCpu = gpu.copy(candidate = cpu, evidence = slowCpu)
        assertEquals(gpu, JointRuntimeChoicePolicy.select(listOf(invalidCpu, gpu), 8))
        assertEquals(gpu, JointRuntimeChoicePolicy.select(listOf(gpu, invalidCpu), 8))
        assertEquals(JointRuntimeMeasurementKind.SAMPLE_IMAGES_TWO_MODEL, gpu.measurementKind)
    }

    @Test fun requestedSettingsCannotStandInForObservedBackendThreadOrFallbackEvidence() {
        val e = f.evidence()
        listOf(
            e.copy(actualPrimary = RuntimeCandidate(RuntimeBackend.CPU, 2)),
            e.copy(actualPrimary = e.actualPrimary.copy(numThreads = 1)),
            e.copy(actualAuxiliary = RuntimeCandidate(RuntimeBackend.GPU, 2)),
            e.copy(actualAuxiliary = e.actualAuxiliary.copy(numThreads = 1)), e.copy(fallbackUsed = true),
        ).forEach { assertFalse(JointRuntimeChoicePolicy.validateCandidate(f.candidate, it, 8)) }
    }

    @Test fun allFourPinnedFixturesNeedCompleteRawShapesAndBothStrictOutputGates() {
        val e = f.evidence()
        val first = e.fixtureQuality.first()
        val invalid = listOf(
            e.fixtureQuality.dropLast(1), List(4) { first },
            e.fixtureQuality.drop(1) + first.copy(fixtureId = "unknown"),
            e.fixtureQuality.drop(1) + first.copy(primaryRawValueCount = 1),
            e.fixtureQuality.drop(1) + first.copy(auxiliaryDetectionValueCount = 1),
            e.fixtureQuality.drop(1) + first.copy(auxiliaryPrototypeValueCount = 1),
            e.fixtureQuality.drop(1) + first.copy(primaryOutputEquivalent = false),
            e.fixtureQuality.drop(1) + first.copy(primaryFixtureRolesMatched = false),
            e.fixtureQuality.drop(1) + first.copy(auxiliaryRawEquivalent = false),
            e.fixtureQuality.drop(1) + first.copy(auxiliaryMasksEquivalent = false),
        )
        invalid.forEach { assertFalse(JointRuntimeChoicePolicy.validateCandidate(f.candidate, e.copy(fixtureQuality = it), 8)) }
    }

    @Test fun bothModelsNeedTenSecondsAndTenActualCompletionsFromTheJointWindow() {
        val e = f.evidence()
        listOf(
            e.copy(durationMs = 9_999), e.copy(primaryTurnaroundMs = List(9) { 110.0 }),
            e.copy(auxiliaryTurnaroundMs = List(9) { 350.0 }),
            e.copy(primarySubmittedFrames = 49), e.copy(auxiliarySubmittedFrames = 24),
            e.copy(primaryTurnaroundMs = emptyList()), e.copy(auxiliaryTurnaroundMs = List(25) { Double.NaN }),
            e.copy(primaryTurnaroundMs = List(50) { Double.POSITIVE_INFINITY }),
            e.copy(auxiliaryTurnaroundMs = List(25) { 10_001.0 }),
        ).forEach { assertNull(JointRuntimeChoicePolicy.evaluate(f.binding, f.candidate, it, 8)) }
    }

    @Test fun submissionToCompletionBudgetDerivesTimelyFractionFromAcceptedWork() {
        val e = f.evidence()
        listOf(
            e.copy(primaryTurnaroundMs = e.primaryTurnaroundMs + 800.01, primarySubmittedFrames = 51),
            e.copy(primarySubmittedFrames = 63), // 50/63 is below 80%.
            e.copy(auxiliarySubmittedFrames = 32), // 25/32 is below 80%.
            e.copy(auxiliaryTurnaroundMs = List(19) { 350.0 } + List(6) { 801.0 }),
            e.copy(primaryLongestGapMs = 1_001.0), e.copy(auxiliaryLongestGapMs = 1_201.0),
        ).forEach { assertFalse(JointRuntimeChoicePolicy.validateCandidate(f.candidate, it, 8)) }
        val boundary = e.copy(auxiliaryTurnaroundMs = List(20) { 350.0 }) // 20 completed/25 accepted is exactly 80%.
        assertTrue(JointRuntimeChoicePolicy.validateCandidate(f.candidate, boundary, 8))
        // Callers cannot submit a separate fabricated "fresh count": the policy derives it.
        assertEquals(20, boundary.auxiliaryTurnaroundMs.count { it <= 800.0 })
        val freshButWithoutHeadroom = e.copy(auxiliaryTurnaroundMs = List(20) { 800.0 } + List(5) { 801.0 })
        assertEquals("runtime_headroom", JointRuntimeChoicePolicy.rejectionReason(f.candidate, freshButWithoutHeadroom, 8))
    }

    @Test fun bothSampleP95sNeedOperationalHeadroomAndSixHundredIsInclusive() {
        val e = headroomBoundary()
        assertNotNull(JointRuntimeChoicePolicy.evaluate(f.binding, f.candidate, e, 8))
        for (primary in listOf(true, false)) {
            val over = if (primary) e.copy(primaryTurnaroundMs = List(20) { 600.01 })
                else e.copy(auxiliaryTurnaroundMs = List(20) { 600.01 })
            assertEquals("runtime_headroom", JointRuntimeChoicePolicy.rejectionReason(f.candidate, over, 8))
            assertNull(JointRuntimeChoicePolicy.evaluate(f.binding, f.candidate, over, 8))
        }
        assertEquals(800L, JointRuntimeChoicePolicy.MAXIMUM_TURNAROUND_MS)
    }

    @Test fun p95HeadroomAllowsASingleTailSampleButKeepsTheSeparateFreshnessLimits() {
        val e = headroomBoundary().copy(primaryTurnaroundMs = List(19) { 600.0 } + 800.0,
            auxiliaryTurnaroundMs = List(19) { 600.0 } + 801.0)
        assertTrue(JointRuntimeChoicePolicy.validateCandidate(f.candidate, e, 8))
        val twoPrimaryTails = e.copy(primaryTurnaroundMs = List(18) { 600.0 } + List(2) { 601.0 })
        val twoAuxiliaryTails = e.copy(auxiliaryTurnaroundMs = List(18) { 600.0 } + List(2) { 601.0 })
        assertEquals("runtime_headroom", JointRuntimeChoicePolicy.rejectionReason(f.candidate, twoPrimaryTails, 8))
        assertEquals("runtime_headroom", JointRuntimeChoicePolicy.rejectionReason(f.candidate, twoAuxiliaryTails, 8))
        assertEquals("primary_turnaround", JointRuntimeChoicePolicy.rejectionReason(f.candidate,
            e.copy(primaryTurnaroundMs = List(19) { 600.0 } + 800.01), 8))
    }

    @Test fun nearestRankP95AtTheMinimumTenCompletionsIncludesTheSingleSlowestSample() {
        val e = headroomBoundary().copy(durationMs = 10_000, primarySubmittedFrames = 10,
            auxiliarySubmittedFrames = 10, primaryTurnaroundMs = List(9) { 600.0 } + 601.0,
            auxiliaryTurnaroundMs = List(10) { 600.0 })
        assertEquals("runtime_headroom", JointRuntimeChoicePolicy.rejectionReason(f.candidate, e, 8))
        assertTrue(JointRuntimeChoicePolicy.validateCandidate(f.candidate,
            e.copy(primaryTurnaroundMs = List(10) { 600.0 }), 8))
    }

    @Test fun observedThermalPressureRejectsButUnavailableObservationStaysExplicitlyUnknown() {
        val e = f.evidence()
        assertTrue(JointRuntimeChoicePolicy.validateCandidate(f.candidate, e.copy(thermalStatus = -1), 8))
        assertEquals(-1, f.choice(evidence = e.copy(thermalStatus = -1)).evidence.thermalStatus)
        for (status in listOf(-2, 2, 3, 4)) {
            assertFalse(JointRuntimeChoicePolicy.validateCandidate(f.candidate, e.copy(thermalStatus = status), 8))
        }
    }

    @Test fun threadCountsAndCadencesRemainWithinMeasuredPolicyLimits() {
        for (threads in listOf(5, 9)) {
            val candidate = f.candidate.copy(primary = f.candidate.primary.copy(numThreads = threads))
            assertNull(JointRuntimeChoicePolicy.evaluate(f.binding, candidate, f.evidence(candidate), 8))
        }
        for (cadence in listOf(99L, 601L)) {
            assertNull(JointRuntimeChoicePolicy.evaluate(f.binding, f.candidate.copy(primaryIntervalMs = cadence), f.evidence(), 8))
        }
        assertNull(JointRuntimeChoicePolicy.evaluate(f.binding, f.candidate, f.evidence(), 1))
        assertNotNull(JointRuntimeChoicePolicy.evaluate(f.binding, f.candidate, f.evidence(), 2))
    }

    @Test fun selectionUsesPrimaryTurnaroundThenTimelyAuxiliaryThroughputWithinAdmittedCandidates() {
        val fastPrimary = f.choice(evidence = f.evidence().copy(primaryTurnaroundMs = List(50) { 80.0 }))
        val fastAuxiliary = f.choice(evidence = f.evidence().copy(auxiliarySubmittedFrames = 30,
            auxiliaryTurnaroundMs = List(30) { 200.0 }))
        assertEquals(fastPrimary, JointRuntimeChoicePolicy.select(listOf(fastAuxiliary, fastPrimary), 8))
        assertEquals(fastAuxiliary, JointRuntimeChoicePolicy.select(listOf(f.choice(), fastAuxiliary), 8))
    }

    @Test fun evaluationSnapshotsSampleMeasurementsAndRejectsBadBindingOrOldPolicy() {
        val timings = MutableList(50) { 110.0 }; val quality = f.quality().toMutableList()
        val choice = f.choice(evidence = f.evidence().copy(primaryTurnaroundMs = timings, fixtureQuality = quality))
        timings[0] = 900.0; quality.clear()
        assertEquals(110.0, choice.evidence.primaryTurnaroundMs.first(), 0.0)
        assertEquals(4, choice.evidence.fixtureQuality.size)
        assertTrue(choice.isValid(f.binding, 8)); assertFalse(choice.isValid("b".repeat(64), 8))
        assertFalse(choice.copy(policyVersion = "joint-camera-runtime-768-v1").isValid(f.binding, 8))
        assertNull(JointRuntimeChoicePolicy.evaluate("invalid", f.candidate, f.evidence(), 8))
    }

    @Test fun eachLogicalMeasurementGetsAUniqueValidatedIdEvenWithIdenticalMetrics() {
        val first = f.choice(); val second = f.choice()
        assertEquals(first.candidate, second.candidate)
        assertEquals(first.evidence, second.evidence)
        assertNotEquals(first.measurementId, second.measurementId)
        assertTrue(JointRuntimeChoicePolicy.validMeasurementId(first.measurementId))
        assertFalse(first.copy(measurementId = "invalid").isValid(f.binding, 8))
        assertFalse(first.copy(measurementId = "00000000-0000-0000-0000-000000000000").isValid(f.binding, 8))
    }

    private fun headroomBoundary() = f.evidence().copy(durationMs = 20_000,
        primarySubmittedFrames = 20, auxiliarySubmittedFrames = 20,
        primaryTurnaroundMs = List(20) { 600.0 }, auxiliaryTurnaroundMs = List(20) { 600.0 },
        primaryLongestGapMs = 1_000.0, auxiliaryLongestGapMs = 1_200.0)
}

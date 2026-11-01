package kr.co.hanium.dreamup.walksafe.inference.composite

import kr.co.hanium.dreamup.walksafe.inference.RuntimeBackend
import org.junit.Assert.*
import org.junit.Test

class JointRuntimeSearchPolicyTest {
    @Test fun bothGpuRunBeforeAnyCpuPreparationAndLowThreadCpuCandidatesRemainEarly() {
        val policy = JointRuntimeSearchPolicy(0, 300_000, 8)
        val candidates = policy.candidates
        assertEquals(RuntimeBackend.GPU, candidates[0].primary.backend)
        assertEquals(RuntimeBackend.GPU, candidates[0].auxiliary.backend)
        assertEquals(250L, candidates[0].primaryIntervalMs)
        assertEquals(500L, candidates[0].auxiliaryIntervalMs)
        assertEquals(RuntimeBackend.GPU, candidates[1].primary.backend)
        assertEquals(RuntimeBackend.CPU, candidates[1].auxiliary.backend)
        assertEquals(RuntimeBackend.CPU, candidates[2].primary.backend)
        assertEquals(RuntimeBackend.GPU, candidates[2].auxiliary.backend)
        assertTrue(candidates.take(5).any { it.primary.backend == RuntimeBackend.CPU && it.primary.numThreads == 1 &&
            it.auxiliary.backend == RuntimeBackend.CPU && it.auxiliary.numThreads == 1 &&
            it.primaryIntervalMs == 400L && it.auxiliaryIntervalMs == 600L })
        assertTrue(candidates.take(5).any { it.primary.backend == RuntimeBackend.CPU && it.primary.numThreads == 2 &&
            it.auxiliary.backend == RuntimeBackend.CPU && it.auxiliary.numThreads == 2 })
        assertTrue(candidates.all { it.primary.numThreads <= 2 && it.auxiliary.numThreads <= 2 })
        assertTrue(JointRuntimeSearchPolicy(0, 300_000, 1).candidates.all { it.primary.numThreads == 1 && it.auxiliary.numThreads == 1 })
    }

    @Test fun searchCannotBorrowReferenceFinalOrCleanupReservations() {
        val p = JointRuntimeSearchPolicy(0, 300_000, 4)
        assertEquals(130_000L, p.provisionalBudget(0)!!.deadlineMs)
        assertNull(p.provisionalBudget(129_000))
        assertEquals(JointRuntimeSearchFailureKind.BUDGET_SKIPPED, p.lastFailure!!.kind)
        assertEquals(200_000L, p.beginReference(130_000)!!.deadlineMs)
        assertTrue(p.finishReference(true, 199_999))
        val final = p.beginFinal(200_000, listOf(measurement(p.candidates[0])))!!
        assertEquals(290_000L, final.budget.deadlineMs)
        assertEquals(300_000L, p.cleanupBudget(290_000)!!.deadlineMs)
    }

    @Test fun earlyPhasesKeepTheirOwnBoundsAndDoNotSpendUnusedSearchTime() {
        val p = JointRuntimeSearchPolicy(1_000, 301_000, 4)
        assertEquals(76_000L, p.beginReference(6_000)!!.deadlineMs)
        assertTrue(p.finishReference(true, 26_000))
        assertEquals(116_000L, p.beginFinal(26_000, listOf(measurement(p.candidates[0])))!!.budget.deadlineMs)
        assertEquals(40_000L, p.cleanupBudget(30_000)!!.deadlineMs)
        assertEquals(40_000L, p.cleanupBudget(35_000)!!.deadlineMs)
        assertNull(p.cleanupBudget(40_000))
    }

    @Test fun referenceOutputFailureIsAccuracyUnconfirmedAndCannotBecomeCpuPerformanceFallback() {
        val p = JointRuntimeSearchPolicy(0, 300_000, 2)
        p.beginReference(10_000)
        assertFalse(p.finishReference(false, 20_000))
        assertEquals(JointRuntimeSearchFailureKind.ACCURACY_UNCONFIRMED, p.lastFailure!!.kind)
        assertNull(p.beginFinal(20_000, listOf(measurement(p.candidates[0]))))
    }

    @Test fun referenceAtItsExactDeadlineIsNotAnAcceptedReference() {
        val p = JointRuntimeSearchPolicy(0, 300_000, 4)
        val budget = p.beginReference(10_000)!!
        assertFalse(p.finishReference(true, budget.deadlineMs))
        assertNull(p.beginFinal(budget.deadlineMs, listOf(measurement(p.candidates[0]))))
    }

    @Test fun rollbackOrLateReferenceCannotExtendAnyPhase() {
        val rollback = JointRuntimeSearchPolicy(1_000, 301_000, 4)
        assertNotNull(rollback.provisionalBudget(2_000))
        assertNull(rollback.beginReference(1_999))
        assertEquals(JointRuntimeSearchFailureKind.ACCURACY_UNCONFIRMED, rollback.lastFailure!!.kind)
        assertNull(rollback.beginReference(3_000))
        val late = JointRuntimeSearchPolicy(0, 300_000, 4)
        assertNull(late.beginReference(130_001))
        assertEquals(JointRuntimeSearchFailureKind.BUDGET_SKIPPED, late.lastFailure!!.kind)
    }

    @Test fun finalSelectsTheBestMeasuredProvisionalOnceAndOffersNoHiddenRetry() {
        val p = JointRuntimeSearchPolicy(0, 300_000, 4)
        p.beginReference(0); assertTrue(p.finishReference(true, 1_000))
        val slower = measurement(p.candidates[0], 250.0)
        val timings = mutableListOf(100.0, 100.0)
        val faster = measurement(p.candidates[1], 100.0).copy(primaryTurnaroundMs = timings)
        val result = p.beginFinal(1_000, listOf(slower, faster))!!
        assertEquals(faster.candidate, result.candidate)
        assertNull(p.beginFinal(2_000, listOf(slower)))
        timings[0] = 9_999.0
        assertEquals(100.0, result.provisional.primaryTurnaroundMs[0], 0.0)
    }

    @Test fun aFasterPrimaryWithInsufficientAuxiliaryHeadroomCannotDisplaceAQualifiablePair() {
        val p = JointRuntimeSearchPolicy(0, 300_000, 4)
        val insufficient = measurement(p.candidates[1], 150.0).copy(auxiliaryTurnaroundMs = listOf(700.0, 700.0))
        val usableTiming = measurement(p.candidates[2], 180.0).copy(auxiliaryTurnaroundMs = listOf(250.0, 250.0))
        assertEquals(JointRuntimeSearchFailureKind.PERFORMANCE_FAILED, p.assessProvisional(insufficient).failure!!.kind)
        assertEquals("provisional_runtime_headroom", p.assessProvisional(insufficient).failure!!.detail)
        p.beginReference(0); assertTrue(p.finishReference(true, 1_000))
        val final = requireNotNull(p.beginFinal(1_000, listOf(insufficient, usableTiming)))
        assertEquals(usableTiming.candidate, final.candidate)

        val noTiming = JointRuntimeSearchPolicy(0, 300_000, 4)
        noTiming.beginReference(0); assertTrue(noTiming.finishReference(true, 1_000))
        assertNull(noTiming.beginFinal(1_000, emptyList()))
        assertEquals(JointRuntimeSearchFailureKind.ACCURACY_UNCONFIRMED, noTiming.lastFailure!!.kind)

        val onlyRejected = JointRuntimeSearchPolicy(0, 300_000, 4)
        onlyRejected.beginReference(0); assertTrue(onlyRejected.finishReference(true, 1_000))
        assertNull(onlyRejected.beginFinal(1_000, listOf(insufficient,
            usableTiming.copy(primaryTurnaroundMs = listOf(700.0, 700.0)))))
        assertEquals(JointRuntimeSearchFailureKind.PERFORMANCE_FAILED, onlyRejected.lastFailure!!.kind)
    }

    @Test fun incompleteInvalidRuntimeAndSlowTimingRemainDistinctFromAnUnmeasuredCandidate() {
        val p = JointRuntimeSearchPolicy(0, 300_000, 4)
        val valid = measurement(p.candidates[0])
        assertNull(p.assessProvisional(valid).failure)
        assertEquals(JointRuntimeSearchFailureKind.ACCURACY_UNCONFIRMED,
            p.assessProvisional(valid.copy(primaryTurnaroundMs = listOf(Double.NaN, 100.0))).failure!!.kind)
        assertEquals(JointRuntimeSearchFailureKind.ACCURACY_UNCONFIRMED,
            p.assessProvisional(valid.copy(fallbackUsed = true)).failure!!.kind)
        assertEquals(JointRuntimeSearchFailureKind.PERFORMANCE_FAILED,
            p.assessProvisional(valid.copy(primaryTurnaroundMs = listOf(801.0, 100.0))).failure!!.kind)
        assertEquals(JointRuntimeSearchFailureKind.ACCURACY_UNCONFIRMED,
            p.assessProvisional(valid.copy(primaryTurnaroundMs = listOf(100.0))).failure!!.kind)
        assertEquals(JointRuntimeSearchFailureKind.ACCURACY_UNCONFIRMED,
            p.assessProvisional(valid.copy(primaryTurnaroundMs = List(65) { 100.0 })).failure!!.kind)
    }

    @Test fun deadlinesNearLongMaximumAreExactAndShortRemainingBudgetNeverCreatesAChoice() {
        val p = JointRuntimeSearchPolicy(Long.MAX_VALUE - 300_000, Long.MAX_VALUE, 4)
        assertEquals(Long.MAX_VALUE - 170_000, p.provisionalBudget(Long.MAX_VALUE - 300_000)!!.deadlineMs)
        assertEquals(Long.MAX_VALUE - 100_000, p.beginReference(Long.MAX_VALUE - 170_000)!!.deadlineMs)
        val short = JointRuntimeSearchPolicy(0, 100_000, 4)
        assertNull(short.provisionalBudget(0))
        assertNull(short.beginReference(0))
    }

    private fun measurement(candidate: JointRuntimeCandidate, primaryMs: Double = 100.0) =
        JointRuntimeProvisionalMeasurement(candidate, 2_000, 2, 2, listOf(primaryMs, primaryMs), listOf(200.0, 200.0),
            candidate.primary, candidate.auxiliary, false, 0)
}

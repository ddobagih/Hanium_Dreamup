package kr.co.hanium.dreamup.walksafe.inference.composite

import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionPurpose
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class JointRuntimeFinalPairPreparationTest {
    private val candidate = JointRuntimeCandidate(RuntimeCandidate(RuntimeBackend.GPU, 1), RuntimeCandidate(RuntimeBackend.CPU, 1), 250, 500)

    @Test fun allFourStrictFixturesAndASustainedWindowQualifyTheSameRetainedModelsWithoutAnotherOpen() = pair { reference, final, refs ->
        val host = final.host()
        val result = JointRuntimeFinalPairPreparation.prepare(host, candidate, refs, final.budget(JointRuntimeSearchPhase.FINAL), { final.current })
        assertEquals(1, reference.primaryCreates); assertEquals(1, final.primaryCreates)
        assertEquals(1, final.auxiliaryFactory.creates.get()); assertEquals(0, final.primaryCloses)
        assertFalse(final.primaryPhysical.isDone); assertFalse(final.nativePhysical.isDone)
        assertFalse(host.actualReleasedFuture.toCompletableFuture().isDone)
        assertEquals(4, result.prepared.evidence.size); assertEquals(8, result.qualification.strictEvidence.size)
        assertTrue(result.qualification.soakEvidence.durationMs >= 10_000)
        assertEquals(result.prepared.choice.measurementId, result.qualification.measurementId)
        assertTrue(result.qualification.strictEvidence.all { it.modelInstanceId in setOf(final.primaryId, final.auxiliaryId) })
        assertTrue(result.qualification.matches(result.prepared.choice, result.prepared.proof.snapshot))
        assertSame(result.qualification, result.prepared.proof.qualification)
        assertFalse(JointPairReadyProof.ownsQualifiedProof(result.prepared.proof))
        result.prepared.proof.bindConsumerGateOnce { true }
        assertTrue(JointPairReadyProof.ownsQualifiedProof(result.prepared.proof))
        assertTrue(final.primaryRequests.none { it.first == VisionPurpose.REFERENCE })
        assertTrue(final.auxiliaryFactory.requests.drop(5).all { !it.captureRawOutputs && it.purpose == VisionPurpose.BENCHMARK })
        assertThrows(UnsupportedOperationException::class.java) { (result.qualification.strictEvidence as MutableList).clear() }
    }

    @Test fun aStrictMismatchFailsTheChosenCandidateWithoutPreparingASecondPair() = pair { _, final, refs ->
        final.corruptFixture = "coco_872"
        val host = final.host()
        val error = runCatching { JointRuntimeFinalPairPreparation.prepare(host, candidate, refs,
            final.budget(JointRuntimeSearchPhase.FINAL), { final.current }) }.exceptionOrNull()
        assertTrue(error is JointRuntimeSampleFailure)
        assertEquals(JointRuntimeSearchFailureKind.ACCURACY_UNCONFIRMED, (error as JointRuntimeSampleFailure).failure.kind)
        host.actualReleasedFuture.toCompletableFuture().get(3, TimeUnit.SECONDS)
        assertEquals(1, final.primaryCreates); assertEquals(1, final.auxiliaryFactory.creates.get())
        assertEquals(1, final.primaryCloses); assertTrue(final.primaryCalls.get() < 6)
        assertTrue(runCatching { refs.claimForFinal(JointRuntimeTestFixtures.binding) }.isFailure)
    }

    @Test fun changedInputDigestOrCanonicalHandleOptionsCannotReachSustainedQualification() {
        for (handleMismatch in listOf(false, true)) pair { _, final, refs ->
            if (handleMismatch) final.corruptHandle = true else final.inputDigest = "c".repeat(64)
            val host = final.host()
            assertTrue(runCatching { JointRuntimeFinalPairPreparation.prepare(host, candidate, refs,
                final.budget(JointRuntimeSearchPhase.FINAL), { true }) }.isFailure)
            host.actualReleasedFuture.toCompletableFuture().get(3, TimeUnit.SECONDS)
            assertEquals(1, final.primaryCreates); assertTrue(final.primaryCalls.get() <= 2)
        }
    }

    @Test fun aClockCrossingDuringTheSameInstancesSoakCannotIssueReadiness() = pair { _, final, refs ->
        val budget = final.budget(JointRuntimeSearchPhase.FINAL)
        final.onPrimarySample = { if (final.primaryCalls.get() == 6) final.clock.set(budget.deadlineMs) }
        val host = final.host()
        val failure = runCatching { JointRuntimeFinalPairPreparation.prepare(host, candidate, refs, budget, { true }) }.exceptionOrNull()
        assertTrue(failure is TimeoutException)
        host.actualReleasedFuture.toCompletableFuture().get(3, TimeUnit.SECONDS)
        assertEquals(1, final.primaryCreates); assertEquals(0, final.liveResults.get())
    }

    private fun pair(action: (JointRuntimeSampleTestFixture, JointRuntimeSampleTestFixture, JointRuntimeReferenceSet) -> Unit) {
        val ledger = JointRuntimeMemoryLedger(); val bundle = JointRuntimeSampleTestFixture.sampleFixtures()
        JointRuntimeSampleTestFixture("reference", ledger, bundle).use { reference ->
            val refs = JointRuntimeReferenceSet.capture(reference.host(), reference.budget(JointRuntimeSearchPhase.REFERENCE, 70_000)) { true }
            try { JointRuntimeSampleTestFixture("final", ledger, bundle).use { final -> action(reference, final, refs) } }
            finally { refs.close() }
        }
    }
}

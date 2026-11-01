package kr.co.hanium.dreamup.walksafe.inference.composite

import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.InstanceMask
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionPurpose
import org.junit.Assert.*
import org.junit.Test
import java.util.BitSet
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamResult
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.UnknownRuntimeTuning
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

class JointRuntimeReferenceSetTest {
    @Test fun aSlowCpu1ReferenceRunsOnceWithReferencePurposeAndIsNotRejectedAsAn800msPerformanceFailure() =
        JointRuntimeSampleTestFixture("slow-reference").use { f ->
            f.referenceDelayMs = 1_000
            val host = f.host(); val budget = f.budget(JointRuntimeSearchPhase.REFERENCE, 70_000)
            val references = JointRuntimeReferenceSet.capture(host, budget) { f.current }
            try {
                assertEquals(1, f.primaryCreates); assertEquals(1, f.auxiliaryFactory.creates.get())
                assertEquals(4, f.primaryRequests.count { it.first == VisionPurpose.REFERENCE })
                assertEquals(4, f.auxiliaryFactory.requests.count { it.purpose == VisionPurpose.REFERENCE && it.captureRawOutputs })
                assertTrue(f.primaryRequests.all { it.second == budget.deadlineMs })
                assertTrue(runCatching { JointRuntimeReferenceSet.capture(host, budget) { true } }.isFailure)
                assertEquals(1, f.primaryCreates)
                val verifier = references.claimForFinal(JointRuntimeTestFixtures.binding)
                assertTrue(runCatching { references.claimForFinal(JointRuntimeTestFixtures.binding) }.isFailure)
                verifier.close()
            } finally { references.close() }
            assertEquals(0, f.ledger.snapshot().activeReservations)
        }

    @Test fun referenceBindingAndCloseAreCheckedBeforeAnyVerification() {
        var releases = 0
        val references = JointRuntimeReferenceSet.fromOwnedForTest(JointRuntimeTestFixtures.binding,
            requireNotNull(JointRuntimeTestFixtures.config.unifiedWalksafe), emptyMap()) { releases++ }
        assertTrue(runCatching { references.claimForFinal("f".repeat(64)) }.isFailure)
        references.close(); references.close()
        assertEquals(1, releases)
        assertTrue(runCatching { references.claimForFinal(JointRuntimeTestFixtures.binding) }.isFailure)
    }

    @Test fun aDeclaredNearThresholdRoleRequiresAnActualReferenceAndCandidateObservationAtTheExistingWindow() {
        val model = requireNotNull(JointRuntimeTestFixtures.config.unifiedWalksafe)
        val fixture = RuntimeCalibrationFixture("near", RuntimeCalibrationFixtureKind.JPEG, "unused", 2, 2,
            "a".repeat(64), true, setOf(RuntimeCalibrationFixtureRole.NEAR_THRESHOLD), emptyList())
        fun raw(score: Float) = FloatArray(1800).also { it[2] = 1f; it[3] = 1f; it[4] = score }
        assertTrue(JointRuntimeReferenceSet.roles(fixture, emptyList(), emptyList(), raw(.51f), raw(.51f), model))
        assertFalse(JointRuntimeReferenceSet.roles(fixture, emptyList(), emptyList(), raw(.53f), raw(.51f), model))
        assertFalse(JointRuntimeReferenceSet.roles(fixture, emptyList(), emptyList(), raw(.51f), raw(.53f), model))
    }

    @Test fun masksRequireTheSameAnchorGeometryAndEveryPackedBit() {
        fun mask(bits: BitSet, anchor: Int = 7): InstanceMask = InstanceMask::class.java.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(2, 2, 0, 0, 2, 2, bits.cardinality(), anchor, 0f, 0f, 2f, 2f, 1f, bits) as InstanceMask
        val first = mask(BitSet().apply { set(0) })
        val reference = listOf(JointReferenceMask(7, 2, 2, 0, 0, 2, 2, first.copyPackedBits()))
        assertTrue(JointRuntimeReferenceSet.sameMasks(reference, listOf(first)))
        assertFalse(JointRuntimeReferenceSet.sameMasks(reference, listOf(mask(BitSet().apply { set(1) }))))
        assertFalse(JointRuntimeReferenceSet.sameMasks(reference, listOf(mask(BitSet().apply { set(0) }, 8))))
        assertFalse(JointRuntimeReferenceSet.sameMasks(reference, listOf(first, first)))
    }

    @Test fun streamedRawComparisonPreservesTheExistingToleranceAndFiniteRulesAtItsBoundary() {
        val reference = arrayOf(floatArrayOf(0f, 1f, -2f), floatArrayOf(.5f))
        for (difference in listOf(.000099f, .0001f, .000101f, Float.NaN)) {
            val candidate = arrayOf(floatArrayOf(difference, 1f, -2f), floatArrayOf(.5f))
            val constructor = FastSamResult.RawOutputs::class.java.declaredConstructors.single().apply { isAccessible = true }
            val raw = constructor.newInstance(candidate as Any) as FastSamResult.RawOutputs
            assertEquals(UnknownRuntimeTuning.equivalentRaw(reference, candidate), JointRuntimeReferenceSet.equivalentRaw(reference, raw))
        }
    }

    @Test fun completedReferencePiecesDoNotRemainPendingOrDoubleSubtractA128MiBHeapAndStayRetainedUntilClose() =
        JointRuntimeSampleTestFixture("incremental-refs").use { f ->
            val pending = mutableListOf<Long>()
            f.heapObservation = {
                pending += f.ledger.snapshot().pendingManagedBytes
                val referencesOrCurrentSource = f.auxiliaryFactory.requests.count { it.purpose == VisionPurpose.REFERENCE }
                val maximum = 128L * 1024 * 1024
                val used = 48L * 1024 * 1024 + referencesOrCurrentSource * 6_508_800L
                JointRuntimeManagedHeapSnapshot(maximum, maximum, maximum - used)
            }
            val host = f.host()
            val references = JointRuntimeReferenceSet.capture(host, f.budget(JointRuntimeSearchPhase.REFERENCE, 70_000)) { true }
            assertEquals(4, f.primaryRequests.count { it.first == VisionPurpose.REFERENCE })
            assertTrue(pending.isNotEmpty()); assertTrue(pending.all { it == 0L })
            val retained = f.ledger.snapshot()
            assertEquals(0L, retained.pendingManagedBytes)
            assertTrue(retained.managedBytes in 26_064_000L until 27L * 1024 * 1024)
            references.close(); references.close()
            assertEquals(0L, f.ledger.snapshot().managedBytes); assertEquals(0, f.ledger.snapshot().activeReservations)
        }

    @Test fun partialReferenceBudgetFailureOrCancellationDropsOnlyCompletedPiecesAndDrainsTheSourceFrames() {
        for (cancel in listOf(false, true)) JointRuntimeSampleTestFixture("partial-reference-$cancel").use { f ->
            f.heapObservation = {
                val enough = JointRuntimeManagedHeapSnapshot(512L * 1024 * 1024, 128L * 1024 * 1024, 96L * 1024 * 1024)
                if (f.auxiliaryFactory.requests.count { it.purpose == VisionPurpose.REFERENCE } >= 2) {
                    if (cancel) { f.current = false; enough }
                    else JointRuntimeManagedHeapSnapshot(64L * 1024 * 1024, 64L * 1024 * 1024, 0)
                } else enough
            }
            val host = f.host()
            val error = runCatching { JointRuntimeReferenceSet.capture(host, f.budget(JointRuntimeSearchPhase.REFERENCE, 70_000)) { f.current } }.exceptionOrNull()
            if (cancel) assertTrue(error is CancellationException)
            else { assertTrue(error is JointRuntimeSampleFailure)
                assertEquals(JointRuntimeSearchFailureKind.RESOURCE_BUDGET, (error as JointRuntimeSampleFailure).failure.kind) }
            host.actualReleasedFuture.toCompletableFuture().get(3, TimeUnit.SECONDS)
            assertEquals(2, f.auxiliaryFactory.requests.count { it.purpose == VisionPurpose.REFERENCE })
            assertEquals(0L, f.ledger.snapshot().managedBytes); assertEquals(0L, f.ledger.snapshot().sharedBytes)
            assertEquals(0, f.ledger.snapshot().activeReservations)
            val next = requireNotNull(f.ledger.beginCandidate("explicit-after-partial-cleanup")); assertTrue(f.ledger.endCandidate(next))
        }
    }
}

package kr.co.hanium.dreamup.walksafe.inference.composite

import org.junit.Assert.*
import org.junit.Test

class JointRuntimeMemoryLedgerTest {
    @Test fun rawReferenceAloneDoesNotImposeAnImpossibleTwentySixMbTotalCap() {
        val ledger = JointRuntimeMemoryLedger()
        val raw = grant(ledger.reservePersistent(JointRuntimeMemoryBytes(managedBytes = 26_064_000), heap()))
        assertTrue(ledger.allocationStarted(raw)); assertTrue(ledger.allocationCompleted(raw))
        val imageAndMaskBound = 9_437_184L + 29_491_200L
        val more = grant(ledger.reservePersistent(JointRuntimeMemoryBytes(managedBytes = imageAndMaskBound), heap(used = 26_064_000)))
        assertTrue(ledger.allocationStarted(more)); assertTrue(ledger.allocationCompleted(more))
        assertEquals(26_064_000L + imageAndMaskBound, ledger.snapshot().managedBytes)
    }

    @Test fun pendingManagedReservationsCannotOverbookTheSameHeapHeadroom() {
        val ledger = JointRuntimeMemoryLedger()
        val snapshot = heap(max = 128 * MIB, used = 32 * MIB)
        grant(ledger.reservePersistent(JointRuntimeMemoryBytes(managedBytes = 40 * MIB), snapshot))
        assertResource(ledger.reservePersistent(JointRuntimeMemoryBytes(managedBytes = 25 * MIB), snapshot))
    }

    @Test fun allocatedManagedBytesAreNotSubtractedTwiceButDiscardedGarbageIsStillInTheFreshHeapReading() {
        val ledger = JointRuntimeMemoryLedger()
        val first = grant(ledger.reservePersistent(JointRuntimeMemoryBytes(managedBytes = 40 * MIB), heap(128 * MIB, 32 * MIB)))
        ledger.allocationStarted(first); ledger.allocationCompleted(first)
        grant(ledger.reservePersistent(JointRuntimeMemoryBytes(managedBytes = 20 * MIB), heap(128 * MIB, 72 * MIB)))
        assertTrue(ledger.releaseAfterDrain(first))
        assertResource(ledger.reservePersistent(JointRuntimeMemoryBytes(managedBytes = 5 * MIB), heap(128 * MIB, 72 * MIB)))
    }

    @Test fun heapReserveUsesTheLargerOfThirtyTwoMibAndTwentyFivePercent() {
        val small = JointRuntimeMemoryLedger()
        assertResource(small.reservePersistent(JointRuntimeMemoryBytes(managedBytes = 17 * MIB), heap(128 * MIB, 80 * MIB)))
        val larger = JointRuntimeMemoryLedger()
        grant(larger.reservePersistent(JointRuntimeMemoryBytes(managedBytes = 100 * MIB), heap(256 * MIB, 80 * MIB)))
    }

    @Test fun directAllocationRemainsChargedAfterDrainAndLedgerHasNoGcBasedReset() {
        val ledger = JointRuntimeMemoryLedger()
        val reserved = grant(ledger.reservePersistent(JointRuntimeMemoryBytes(directBytes = 64 * MIB), heap()))
        assertTrue(ledger.allocationStarted(reserved))
        assertFalse(ledger.abortBeforeAllocation(reserved))
        assertTrue(ledger.releaseAfterDrain(reserved))
        assertEquals(64 * MIB, ledger.snapshot().directBytes)
        assertResource(ledger.reservePersistent(JointRuntimeMemoryBytes(directBytes = 1), heap()))
        assertFalse(ledger.releaseAfterDrain(reserved))
    }

    @Test fun onlyAnAllocationWhichNeverStartedCanReturnItsDirectReservation() {
        val ledger = JointRuntimeMemoryLedger()
        val reserved = grant(ledger.reservePersistent(JointRuntimeMemoryBytes(directBytes = 64 * MIB), heap()))
        assertTrue(ledger.abortBeforeAllocation(reserved))
        assertEquals(0L, ledger.snapshot().directBytes)
        grant(ledger.reservePersistent(JointRuntimeMemoryBytes(directBytes = 64 * MIB), heap()))
    }

    @Test fun oneCandidateAndOneFramePerRoleStayReservedThroughTheWholeDrain() {
        val ledger = JointRuntimeMemoryLedger(); val candidate = requireNotNull(ledger.beginCandidate("gpu-cpu1"))
        assertNull(ledger.beginCandidate("cpu1-gpu"))
        val p = grant(ledger.beginFrame(candidate, JointRuntimeMemoryRole.PRIMARY, JointRuntimeMemoryBytes(sharedBytes = 8 * MIB), heap()))
        val a = grant(ledger.beginFrame(candidate, JointRuntimeMemoryRole.AUXILIARY, JointRuntimeMemoryBytes(sharedBytes = 8 * MIB), heap()))
        assertTrue(ledger.beginFrame(candidate, JointRuntimeMemoryRole.PRIMARY, JointRuntimeMemoryBytes(), heap()) is JointRuntimeMemoryAdmission.Rejected)
        assertFalse(ledger.endCandidate(candidate))
        ledger.allocationStarted(p); ledger.allocationStarted(a)
        assertFalse(ledger.abortBeforeAllocation(p))
        assertEquals(16 * MIB, ledger.snapshot().sharedBytes)
        ledger.releaseAfterDrain(p); ledger.releaseAfterDrain(a)
        assertTrue(ledger.endCandidate(candidate))
        val fresh = requireNotNull(ledger.beginCandidate("cpu1-gpu"))
        assertTrue(ledger.beginFrame(candidate, JointRuntimeMemoryRole.PRIMARY, JointRuntimeMemoryBytes(), heap()) is JointRuntimeMemoryAdmission.Rejected)
        assertFalse(ledger.endCandidate(candidate))
        assertTrue(ledger.endCandidate(fresh))
    }

    @Test fun sharedAndCombinedCapsApplyEvenWhenManagedHeapHasPlentyOfSpace() {
        val ledger = JointRuntimeMemoryLedger()
        assertResource(ledger.reservePersistent(JointRuntimeMemoryBytes(sharedBytes = 32 * MIB + 1), heap()))
        grant(ledger.reservePersistent(JointRuntimeMemoryBytes(managedBytes = 96 * MIB, directBytes = 64 * MIB,
            sharedBytes = 32 * MIB), heap(512 * MIB, 0)))
        assertResource(ledger.reservePersistent(JointRuntimeMemoryBytes(managedBytes = 1), heap(512 * MIB, 0)))
    }

    @Test fun malformedSnapshotsNegativeSizesAndOverflowFailBeforeAnyOwnershipIsReserved() {
        val ledger = JointRuntimeMemoryLedger()
        assertTrue(ledger.reservePersistent(JointRuntimeMemoryBytes(managedBytes = -1), heap()) is JointRuntimeMemoryAdmission.Rejected)
        assertTrue(ledger.reservePersistent(JointRuntimeMemoryBytes(managedBytes = Long.MAX_VALUE, directBytes = 1), heap()) is JointRuntimeMemoryAdmission.Rejected)
        assertTrue(ledger.reservePersistent(JointRuntimeMemoryBytes(), JointRuntimeManagedHeapSnapshot(100, 50, 51)) is JointRuntimeMemoryAdmission.Rejected)
        assertEquals(0, ledger.snapshot().activeReservations)
    }

    private fun heap(max: Long = 256 * MIB, used: Long = 0) = JointRuntimeManagedHeapSnapshot(max, used, 0)
    private fun grant(value: JointRuntimeMemoryAdmission): JointRuntimeMemoryReservation {
        assertTrue("Expected reservation: $value", value is JointRuntimeMemoryAdmission.Granted)
        return (value as JointRuntimeMemoryAdmission.Granted).reservation
    }
    private fun assertResource(value: JointRuntimeMemoryAdmission) {
        assertEquals("resource_budget", (value as JointRuntimeMemoryAdmission.Rejected).reason)
    }
    companion object { private const val MIB = 1_048_576L }
}

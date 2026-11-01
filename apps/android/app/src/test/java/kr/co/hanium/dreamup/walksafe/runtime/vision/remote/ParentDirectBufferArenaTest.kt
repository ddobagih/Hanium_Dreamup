package kr.co.hanium.dreamup.walksafe.runtime.vision.remote

import kr.co.hanium.dreamup.walksafe.inference.composite.JointRuntimeManagedHeapSnapshot
import kr.co.hanium.dreamup.walksafe.inference.composite.JointRuntimeMemoryLedger
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ParentDirectBufferArenaTest {
    private val heap = JointRuntimeManagedHeapSnapshot(256L * 1024 * 1024, 64L * 1024 * 1024, 32L * 1024 * 1024)

    @Test fun repeatedChecksReuseTheBackingAndChargeDirectMemoryOnlyOnce() {
        val ledger = JointRuntimeMemoryLedger(); var allocated = 0
        val arena = ParentDirectBufferArena(ledger) { bytes -> allocated++; ByteBuffer.allocateDirect(bytes) }
        repeat(35) { index ->
            val lease = arena.acquire("same-layout", 16, intArrayOf(8, 12), heap)
            val input = lease.inputView()
            if (index > 0) assertEquals(index - 1, input.getInt(0))
            input.putInt(0, index)
            assertEquals(ByteOrder.LITTLE_ENDIAN, input.order())
            assertEquals(36L, ledger.snapshot().directBytes)
            assertTrue(lease.releaseAfterHostDrain())
        }
        assertEquals(3, allocated)
        assertEquals(36L, ledger.snapshot().directBytes)
        assertEquals(0, ledger.snapshot().activeReservations)
    }

    @Test fun aBusyOrChangedLayoutCannotAllocateAnotherBackingAndRetiredViewsCannotBeObtained() {
        var allocated = 0
        val arena = ParentDirectBufferArena(JointRuntimeMemoryLedger()) { bytes -> allocated++; ByteBuffer.allocateDirect(bytes) }
        val first = arena.acquire("aux", 16, intArrayOf(8), heap)
        assertThrows(IllegalStateException::class.java) { arena.acquire("aux", 16, intArrayOf(8), heap) }
        assertThrows(IllegalStateException::class.java) { arena.acquire("aux", 32, intArrayOf(8), heap) }
        assertEquals(2, allocated)
        assertTrue(first.releaseAfterHostDrain())
        assertFalse(first.releaseAfterHostDrain())
        assertThrows(IllegalStateException::class.java) { first.inputView() }
        val second = arena.acquire("aux", 16, intArrayOf(8), heap)
        assertEquals(8, second.inputView(8).capacity())
        assertEquals(8, second.inputView(8).remaining())
        assertEquals(16, second.inputView().capacity())
        assertEquals(2, allocated)
        second.releaseAfterHostDrain()
    }

    @Test fun inputOnlyAndOutputOnlyPrimaryLayoutsShareTheSameLifetimeBudget() {
        val ledger = JointRuntimeMemoryLedger(); val arena = ParentDirectBufferArena(ledger)
        val input = arena.acquire("primary-input", 16, intArrayOf(), heap)
        val output = arena.acquire("primary-output", 0, intArrayOf(8), heap)
        assertTrue(input.outputViews().isEmpty())
        assertEquals(8, output.outputViews().single().capacity())
        assertThrows(IllegalArgumentException::class.java) { output.inputView() }
        input.releaseAfterHostDrain(); output.releaseAfterHostDrain()
        assertEquals(24L, ledger.snapshot().directBytes)
    }

    @Test fun allocationFailurePreservesTheChargeAndPoisonsOnlyThatLayout() {
        val ledger = JointRuntimeMemoryLedger(); var calls = 0
        val marker = OutOfMemoryError("controlled direct allocation failure")
        val arena = ParentDirectBufferArena(ledger) { bytes ->
            calls++; if (calls == 2) throw marker else ByteBuffer.allocateDirect(bytes)
        }
        assertSame(marker, runCatching { arena.acquire("partial", 16, intArrayOf(8), heap) }.exceptionOrNull())
        assertEquals(24L, ledger.snapshot().directBytes)
        assertEquals(0, ledger.snapshot().activeReservations)
        assertThrows(IllegalStateException::class.java) { arena.acquire("partial", 16, intArrayOf(8), heap) }
        assertEquals(2, calls)
        val other = arena.acquire("other", 4, intArrayOf(), heap)
        assertEquals(28L, ledger.snapshot().directBytes)
        other.releaseAfterHostDrain()
    }

    @Test fun theProcessDirectCapRejectsBeforeAnyAllocationAndLayoutCountIsBounded() {
        var allocated = 0
        val arena = ParentDirectBufferArena(JointRuntimeMemoryLedger()) { bytes -> allocated++; ByteBuffer.allocateDirect(bytes) }
        assertThrows(IllegalStateException::class.java) {
            arena.acquire("over-budget", (JointRuntimeMemoryLedger.MAXIMUM_DIRECT_BYTES + 1).toInt(), intArrayOf(), heap)
        }
        assertEquals(0, allocated)
        repeat(16) { index -> arena.acquire("layout-$index", 1, intArrayOf(), heap).releaseAfterHostDrain() }
        assertThrows(IllegalStateException::class.java) { arena.acquire("layout-17", 1, intArrayOf(), heap) }
        assertEquals(16, allocated)
    }
}

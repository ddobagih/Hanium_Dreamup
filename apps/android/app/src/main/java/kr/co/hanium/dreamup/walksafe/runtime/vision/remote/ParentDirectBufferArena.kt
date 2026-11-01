package kr.co.hanium.dreamup.walksafe.runtime.vision.remote

import kr.co.hanium.dreamup.walksafe.inference.composite.JointRuntimeManagedHeapSnapshot
import kr.co.hanium.dreamup.walksafe.inference.composite.JointRuntimeMemoryAdmission
import kr.co.hanium.dreamup.walksafe.inference.composite.JointRuntimeMemoryBytes
import kr.co.hanium.dreamup.walksafe.inference.composite.JointRuntimeMemoryLedger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One process-lifetime pool. Direct backing stays retained/charged even after its consumer drains. */
class ParentDirectBufferArena internal constructor(
    val memoryLedger: JointRuntimeMemoryLedger,
    private val allocate: (Int) -> ByteBuffer = ByteBuffer::allocateDirect,
) {
    private class Entry(val inputBytes: Int, val outputBytes: IntArray) {
        val backing = mutableListOf<ByteBuffer>()
        var poisoned = false
        var lease: Lease? = null
    }
    /** Views are borrowed only until the owning model/copy/post/listener barrier has finished. */
    class Lease internal constructor(private val arena: ParentDirectBufferArena, internal val key: String,
        private val inputBytes: Int, private val outputBytes: IntArray) {
        fun inputView(size: Int = inputBytes): ByteBuffer = arena.input(this, size)
        fun outputViews(): Array<ByteBuffer> = arena.outputs(this)
        fun releaseAfterHostDrain(): Boolean = arena.release(this)
    }
    private val entries = linkedMapOf<String, Entry>()

    /** Reserve before the first allocation. A busy or changed layout never allocates a duplicate. */
    @Synchronized fun acquire(key: String, inputCapacity: Int, outputCapacities: IntArray,
        heap: JointRuntimeManagedHeapSnapshot = currentManagedHeap()): Lease {
        require(key.isNotBlank() && key.length <= 200 && inputCapacity >= 0 && outputCapacities.size <= 2 &&
            outputCapacities.all { it > 0 }) { "invalid_direct_arena_layout" }
        val size = outputCapacities.fold(inputCapacity.toLong()) { sum, value -> Math.addExact(sum, value.toLong()) }
        require(size > 0) { "empty_direct_arena_layout" }
        val entry = entries[key]?.also {
            check(it.inputBytes == inputCapacity && it.outputBytes.contentEquals(outputCapacities)) { "direct_arena_layout_changed" }
            check(!it.poisoned) { "direct_arena_allocation_unconfirmed" }
            check(it.lease == null) { "direct_arena_busy" }
        } ?: run {
            check(entries.size < MAXIMUM_LAYOUTS) { "direct_arena_layout_budget" }
            val admitted = memoryLedger.reservePersistent(JointRuntimeMemoryBytes(directBytes = size), heap)
            val reservation = (admitted as? JointRuntimeMemoryAdmission.Granted)?.reservation
                ?: throw IllegalStateException("direct_arena_resource_budget")
            val created = Entry(inputCapacity, outputCapacities.copyOf())
            entries[key] = created
            check(memoryLedger.allocationStarted(reservation))
            try {
                fun retain(bytes: Int) {
                    val buffer = allocate(bytes)
                    created.backing += buffer // Even an invalid/partial allocation stays owned.
                    buffer.checked(bytes)
                }
                if (inputCapacity > 0) retain(inputCapacity)
                outputCapacities.forEach(::retain)
                check(memoryLedger.allocationCompleted(reservation))
                // Only bookkeeping is released. The ledger deliberately retains every started
                // direct byte and this arena retains all backing buffers for the process lifetime.
                check(memoryLedger.releaseAfterDrain(reservation))
                created
            } catch (error: Throwable) {
                created.poisoned = true
                memoryLedger.releaseAfterDrain(reservation)
                throw error
            }
        }
        return Lease(this, key, inputCapacity, outputCapacities.copyOf()).also { entry.lease = it }
    }

    @Synchronized private fun input(lease: Lease, size: Int): ByteBuffer {
        val entry = current(lease)
        require(entry.inputBytes > 0 && size in 1..entry.inputBytes)
        return entry.backing.first().duplicate().apply { clear(); limit(size) }.slice().order(ByteOrder.LITTLE_ENDIAN)
    }
    @Synchronized private fun outputs(lease: Lease): Array<ByteBuffer> {
        val entry = current(lease)
        val offset = if (entry.inputBytes > 0) 1 else 0
        return entry.outputBytes.indices.map { index ->
            entry.backing[offset + index].duplicate().apply { clear() }.order(ByteOrder.LITTLE_ENDIAN)
        }.toTypedArray()
    }
    @Synchronized private fun release(lease: Lease): Boolean {
        val entry = entries[lease.key] ?: return false
        if (entry.lease !== lease) return false
        entry.lease = null
        return true
    }
    private fun current(lease: Lease): Entry = requireNotNull(entries[lease.key]).also {
        check(it.lease === lease && !it.poisoned) { "direct_arena_lease_retired" }
    }
    private fun ByteBuffer.checked(size: Int): ByteBuffer {
        require(isDirect && !isReadOnly && capacity() == size) { "direct_arena_allocator_contract" }
        clear(); order(ByteOrder.LITTLE_ENDIAN)
        return this
    }
    companion object {
        private const val MAXIMUM_LAYOUTS = 16
        private val processArena by lazy { ParentDirectBufferArena(JointRuntimeMemoryLedger()) }
        @JvmStatic fun process(): ParentDirectBufferArena = processArena
        @JvmStatic fun currentManagedHeap(): JointRuntimeManagedHeapSnapshot = Runtime.getRuntime().let {
            JointRuntimeManagedHeapSnapshot(it.maxMemory(), it.totalMemory(), it.freeMemory())
        }
    }
}

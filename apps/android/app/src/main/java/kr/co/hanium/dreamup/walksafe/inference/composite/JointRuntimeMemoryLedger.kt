package kr.co.hanium.dreamup.walksafe.inference.composite

/** Runtime.maxMemory/totalMemory/freeMemory from one fresh worker-side observation, in bytes. */
data class JointRuntimeManagedHeapSnapshot(val maximumBytes: Long, val totalBytes: Long, val freeBytes: Long)
data class JointRuntimeMemoryBytes(val managedBytes: Long = 0, val directBytes: Long = 0, val sharedBytes: Long = 0)
enum class JointRuntimeMemoryRole { PRIMARY, AUXILIARY }
class JointRuntimeMemoryCandidate internal constructor(internal val ledger: Any, val key: String)
class JointRuntimeMemoryReservation internal constructor(internal val ledger: Any, internal val bytes: JointRuntimeMemoryBytes,
    internal val candidate: JointRuntimeMemoryCandidate?, internal val role: JointRuntimeMemoryRole?)
sealed interface JointRuntimeMemoryAdmission {
    data class Granted(val reservation: JointRuntimeMemoryReservation) : JointRuntimeMemoryAdmission
    data class Rejected(val reason: String) : JointRuntimeMemoryAdmission
}
data class JointRuntimeMemoryLedgerSnapshot(val managedBytes: Long, val pendingManagedBytes: Long,
    val directBytes: Long, val sharedBytes: Long, val activeReservations: Int)

/**
 * A process-wide owner of known buffers, not an estimate or guarantee of all native/GPU RAM.
 * Keep the same ledger/arena across repeated checks. Constructing another ledger, nulling a field,
 * closing a Java wrapper or requesting GC is NOT evidence that earlier direct buffers were freed.
 * A reused arena charges its backing allocation once; later frames request zero NEW direct bytes.
 * Started direct allocations remain charged for this ledger's whole process lifetime.
 *
 * Reserve before allocating, call allocationStarted immediately before the first allocation/dispatch,
 * and allocationCompleted after success. Managed objects already in the fresh Runtime reading are
 * not subtracted twice. Only pending managed requests are additionally deducted from headroom.
 * releaseAfterDrain requires the whole CPU consumer to finish and every shared mapping/FD to have
 * an actual release ACK; timeout/cancel/unbind alone cannot supply that fact. Releasing managed
 * ownership also does not claim GC: the next fresh heap reading still includes unreclaimed garbage.
 */
class JointRuntimeMemoryLedger {
    private class State(var started: Boolean = false, var allocated: Boolean = false)
    private val identity = Any()
    private var currentCandidate: JointRuntimeMemoryCandidate? = null
    private val reservations = linkedMapOf<JointRuntimeMemoryReservation, State>()
    private val flights = linkedMapOf<JointRuntimeMemoryRole, JointRuntimeMemoryReservation>()
    private var managedBytes = 0L
    private var pendingManagedBytes = 0L
    private var directBytes = 0L
    private var sharedBytes = 0L

    @Synchronized fun beginCandidate(key: String): JointRuntimeMemoryCandidate? {
        if (key.isBlank() || key.length > 200 || currentCandidate != null) return null
        return JointRuntimeMemoryCandidate(identity, key).also { currentCandidate = it }
    }
    @Synchronized fun endCandidate(candidate: JointRuntimeMemoryCandidate): Boolean {
        if (!owns(candidate) || reservations.keys.any { it.candidate === candidate }) return false
        currentCandidate = null
        return true
    }
    @Synchronized fun reservePersistent(bytes: JointRuntimeMemoryBytes,
        heap: JointRuntimeManagedHeapSnapshot): JointRuntimeMemoryAdmission = reserve(null, null, bytes, heap)
    @Synchronized fun reserveCandidate(candidate: JointRuntimeMemoryCandidate, bytes: JointRuntimeMemoryBytes,
        heap: JointRuntimeManagedHeapSnapshot): JointRuntimeMemoryAdmission {
        if (!owns(candidate)) return rejected("stale_candidate")
        return reserve(candidate, null, bytes, heap)
    }
    @Synchronized fun beginFrame(candidate: JointRuntimeMemoryCandidate, role: JointRuntimeMemoryRole,
        bytes: JointRuntimeMemoryBytes, heap: JointRuntimeManagedHeapSnapshot): JointRuntimeMemoryAdmission {
        if (!owns(candidate)) return rejected("stale_candidate")
        if (flights.containsKey(role)) return rejected("role_in_flight")
        return reserve(candidate, role, bytes, heap)
    }
    @Synchronized fun allocationStarted(reservation: JointRuntimeMemoryReservation): Boolean {
        val state = state(reservation) ?: return false
        if (state.started) return false
        state.started = true
        return true
    }
    @Synchronized fun allocationCompleted(reservation: JointRuntimeMemoryReservation): Boolean {
        val state = state(reservation) ?: return false
        if (!state.started || state.allocated) return false
        state.allocated = true
        pendingManagedBytes -= reservation.bytes.managedBytes
        return true
    }
    /** The caller has not allocated or dispatched any part of this reservation. */
    @Synchronized fun abortBeforeAllocation(reservation: JointRuntimeMemoryReservation): Boolean {
        val state = state(reservation) ?: return false
        if (state.started) return false
        release(reservation, state, directNeverAllocated = true)
        return true
    }
    /** Trusted drain boundary; direct charge deliberately outlives this reservation. */
    @Synchronized fun releaseAfterDrain(reservation: JointRuntimeMemoryReservation): Boolean {
        val state = state(reservation) ?: return false
        if (!state.started) return false
        release(reservation, state, directNeverAllocated = false)
        return true
    }
    @Synchronized fun snapshot() = JointRuntimeMemoryLedgerSnapshot(managedBytes, pendingManagedBytes,
        directBytes, sharedBytes, reservations.size)

    private fun reserve(candidate: JointRuntimeMemoryCandidate?, role: JointRuntimeMemoryRole?,
        bytes: JointRuntimeMemoryBytes, heap: JointRuntimeManagedHeapSnapshot): JointRuntimeMemoryAdmission {
        if (bytes.managedBytes < 0 || bytes.directBytes < 0 || bytes.sharedBytes < 0 ||
            sum(bytes.managedBytes, bytes.directBytes, bytes.sharedBytes) == null) return rejected("invalid_memory_request")
        if (heap.maximumBytes <= 0 || heap.maximumBytes == Long.MAX_VALUE || heap.totalBytes !in 0..heap.maximumBytes ||
            heap.freeBytes !in 0..heap.totalBytes) return rejected("invalid_memory_snapshot")
        val reserve = maxOf(MINIMUM_HEAP_RESERVE_BYTES, heap.maximumBytes / 4 + if (heap.maximumBytes % 4 == 0L) 0 else 1)
        val available = (heap.maximumBytes - (heap.totalBytes - heap.freeBytes) - reserve - pendingManagedBytes).coerceAtLeast(0)
        val newManaged = sum(managedBytes, bytes.managedBytes) ?: return rejected("resource_budget")
        val newDirect = sum(directBytes, bytes.directBytes) ?: return rejected("resource_budget")
        val newShared = sum(sharedBytes, bytes.sharedBytes) ?: return rejected("resource_budget")
        val total = sum(newManaged, newDirect, newShared) ?: return rejected("resource_budget")
        if (reservations.size >= MAXIMUM_RESERVATIONS || bytes.managedBytes > available ||
            newDirect > MAXIMUM_DIRECT_BYTES || newShared > MAXIMUM_SHARED_BYTES || total > MAXIMUM_TRACKED_BYTES)
            return rejected("resource_budget")
        val token = JointRuntimeMemoryReservation(identity, bytes, candidate, role)
        reservations[token] = State()
        managedBytes = newManaged; pendingManagedBytes += bytes.managedBytes
        directBytes = newDirect; sharedBytes = newShared
        if (role != null) flights[role] = token
        return JointRuntimeMemoryAdmission.Granted(token)
    }
    private fun release(token: JointRuntimeMemoryReservation, state: State, directNeverAllocated: Boolean) {
        reservations.remove(token)
        if (token.role != null && flights[token.role] === token) flights.remove(token.role)
        managedBytes -= token.bytes.managedBytes
        if (!state.allocated) pendingManagedBytes -= token.bytes.managedBytes
        if (directNeverAllocated) directBytes -= token.bytes.directBytes
        sharedBytes -= token.bytes.sharedBytes
    }
    private fun state(token: JointRuntimeMemoryReservation) = if (token.ledger !== identity) null else reservations[token]
    private fun owns(candidate: JointRuntimeMemoryCandidate) = candidate.ledger === identity && currentCandidate === candidate
    private fun sum(vararg values: Long): Long? = try { values.fold(0L, Math::addExact) } catch (_: ArithmeticException) { null }
    private fun rejected(reason: String) = JointRuntimeMemoryAdmission.Rejected(reason)

    companion object {
        private const val MIB = 1_048_576L
        const val MINIMUM_HEAP_RESERVE_BYTES = 32 * MIB
        const val MAXIMUM_DIRECT_BYTES = 64 * MIB
        const val MAXIMUM_SHARED_BYTES = 32 * MIB
        const val MAXIMUM_TRACKED_BYTES = 192 * MIB
        private const val MAXIMUM_RESERVATIONS = 256
    }
}

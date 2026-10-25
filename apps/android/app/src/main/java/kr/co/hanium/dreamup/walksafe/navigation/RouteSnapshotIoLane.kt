package kr.co.hanium.dreamup.walksafe.navigation

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One process FIFO for this archive only. The admission monitor never encloses Store I/O. */
internal class RouteSnapshotIoLane(private val executor: Executor) {
    internal class Lease {
        val operation = AtomicReference(Any())
        val response = AtomicReference(Any())
        val closed = AtomicBoolean()
        @Volatile var store: EncryptedRouteSnapshotStore? = null
        // Only the single I/O worker writes the following persisted ownership facts.
        var boundOwner: RouteSnapshotOwner? = null
        var bound = false
        var purgeFailed = false
        var walkScope: RouteSnapshotScope? = null
    }
    private val admissionLock = Any()
    private val latest = AtomicReference<Lease?>()
    private var attached: Lease? = null // I/O worker only.

    fun attach(create: () -> EncryptedRouteSnapshotStore): Pair<Lease, CompletableFuture<RouteSnapshotStartupIoResult?>> {
        val lease = Lease()
        val result = CompletableFuture<RouteSnapshotStartupIoResult?>()
        val rejected = synchronized(admissionLock) {
            latest.set(lease)
            enqueue(result, null) {
                // Retire the exact previous Store before this Activity can claim its lease.
                attached?.store?.detach()
                attached = lease
                val store = create()
                lease.store = store
                val nowEpochMs = System.currentTimeMillis()
                val purged = store.purgeExpired(nowEpochMs)
                lease.purgeFailed = !purged
                RouteSnapshotStartupIoResult(purged,
                    if (purged) store.expiryMetadata(nowEpochMs) else null)
            }
        }
        if (rejected) result.complete(null)
        return lease to result
    }

    fun invalidate(lease: Lease, publish: () -> Unit = {}): Any = synchronized(admissionLock) {
        lease.response.set(Any())
        Any().also { lease.operation.set(it); publish() }
    }

    fun commit(lease: Lease, operation: Any, current: () -> Boolean, publish: () -> Unit): Boolean =
        synchronized(admissionLock) {
            // Recheck after the owner read, including any reentrant memory invalidation.
            if (!current() || !isCurrent(lease, operation)) false else { publish(); true }
        }

    fun invalidateResponse(lease: Lease) { lease.response.set(Any()) }

    fun isCurrent(lease: Lease, operation: Any): Boolean =
        latest.get() === lease && !lease.closed.get() && lease.operation.get() === operation

    fun <T> submit(
        lease: Lease, failed: T, current: () -> Boolean,
        mandatoryCleanup: Boolean = false, onAdmission: () -> Unit = {},
        action: (EncryptedRouteSnapshotStore) -> T,
    ): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        val rejected = synchronized(admissionLock) {
            onAdmission() // Memory only; invalidation and queue order are one admission.
            enqueue(result, failed) {
                val store = lease.store
                if (attached !== lease || store == null ||
                    (!mandatoryCleanup && (latest.get() !== lease || lease.closed.get() || !current()))
                ) failed else action(store)
            }
        }
        if (rejected) result.complete(failed)
        return result
    }

    fun detach(lease: Lease) {
        val result = CompletableFuture<Boolean>()
        synchronized(admissionLock) {
            lease.closed.set(true)
            lease.operation.set(Any())
            latest.compareAndSet(lease, null)
            enqueue(result, false) {
                if (attached === lease) {
                    lease.store?.detach()
                    attached = null
                }
                true
            }
        }.also { rejected -> if (rejected) result.complete(false) }
    }

    private fun <T> enqueue(result: CompletableFuture<T>, failed: T, action: () -> T): Boolean =
        try {
            executor.execute {
                val value = runCatching(action).getOrDefault(failed)
                result.complete(value) // Worker completion; no admission/store lock held.
            }
            false
        } catch (_: RejectedExecutionException) { true }
}

internal data class RouteSnapshotStartupIoResult(
    val purged: Boolean, val metadata: RouteSnapshotExpiryMetadata?,
)
internal data class RouteSnapshotOwnershipIoResult(
    val ready: Boolean, val scope: RouteSnapshotScope?, val metadata: RouteSnapshotExpiryMetadata?,
)

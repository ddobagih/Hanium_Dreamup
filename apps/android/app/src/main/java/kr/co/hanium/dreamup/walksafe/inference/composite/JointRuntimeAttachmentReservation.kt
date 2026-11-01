package kr.co.hanium.dreamup.walksafe.inference.composite

import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectorLoadResult
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeService
import java.util.concurrent.CompletableFuture

/** Activity-local creation ledger, reserved before enqueue. A global ticket stores only hostDrained. */
internal class JointRuntimeAttachmentReservation<T> {
    private val lock = Any()
    @Volatile var stopping = false
        private set
    private var primary: AndroidDetectorLoadResult? = null
    private var auxiliary: FastSamRuntimeService<T>? = null
    private var closeStarted = false
    val hostDrained = CompletableFuture<Unit>()

    fun retainPrimary(created: AndroidDetectorLoadResult): Boolean = synchronized(lock) {
        check(primary == null && !closeStarted)
        primary = created
        !stopping
    }
    fun retainAuxiliary(created: FastSamRuntimeService<T>): Boolean = synchronized(lock) {
        check(auxiliary == null && !closeStarted)
        auxiliary = created
        !stopping
    }
    /** Nonblocking admission fence. Call closeHost on the creation/consumer worker barrier. */
    fun revokeAdmission() { stopping = true }

    /** The caller's serial barrier proves creation has returned and no further wrapper can appear. */
    fun closeHost(): CompletableFuture<Unit> {
        val retired = synchronized(lock) {
            if (closeStarted) return hostDrained
            closeStarted = true
            stopping = true
            (primary to auxiliary).also { primary = null; auxiliary = null }
        }
        val auxiliaryDrained = try { retired.second?.closeAsync()?.handle { _, _ -> Unit }
            ?: CompletableFuture.completedFuture(Unit) }
        catch (error: Throwable) { CompletableFuture<Unit>().also { it.completeExceptionally(error) } }
        // Returning/throwing from close has crossed the primary's host lock. Native success is
        // deliberately not inferred here; the independent client receipt remains authoritative.
        runCatching { retired.first?.detector?.close() }
        val drained = hostDrained
        auxiliaryDrained.whenComplete { _, error ->
            if (error == null) drained.complete(Unit) else drained.completeExceptionally(error)
        }
        return drained
    }
}

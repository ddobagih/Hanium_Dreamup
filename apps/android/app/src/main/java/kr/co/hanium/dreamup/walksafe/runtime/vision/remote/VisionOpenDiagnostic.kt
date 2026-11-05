package kr.co.hanium.dreamup.walksafe.runtime.vision.remote

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException

/** Diagnostics only: no owner/model identifiers, messages, paths, or authority. */
internal enum class VisionOpenEvent {
    PARENT_DEADLINE_CLAMPED, PARENT_RPC_CLAMPED, CHILD_DEADLINE_VALIDATED, PARENT_ENTERED, PARENT_FINISHED, PARENT_FAILED, PARENT_AWAIT_TIMEOUT, SOURCE_CANCEL, PARENT_RETIRE_REQUEST,
    CHILD_QUEUED, CHILD_ENTERED, NATIVE_ENTERED, CHILD_FINISHED, NATIVE_EXCEPTION, SERVICE_WATCHDOG,
    PARENT_EXIT, PARENT_DIED, UNBIND, DESTROY, CALLBACK_FAILED, CHILD_EXIT,
}
internal enum class VisionFailureKind { NONE, TIMEOUT, CANCELLED, CHILD_DIED, OUT_OF_MEMORY, SECURITY, ARGUMENT, STATE, LINKAGE, OTHER }
internal enum class VisionOpenBackend { CPU, GPU, NNAPI }
internal class VisionNativeCallbackTimeout : TimeoutException("vision_native_timeout")
internal class VisionNativeFailure(val kind: VisionFailureKind) : RuntimeException("vision_native_failed")

/** Exact enum allowlist; untrusted wire text is never retained in an exception/log. */
internal fun nativeCallbackFailure(token: String?): Throwable = when (val kind = VisionFailureKind.entries.firstOrNull { it.name == token }) {
    VisionFailureKind.TIMEOUT -> VisionNativeCallbackTimeout()
    VisionFailureKind.CANCELLED -> CancellationException("vision_native_cancelled")
    else -> VisionNativeFailure(kind ?: VisionFailureKind.OTHER)
}

internal fun visionFailureKind(error: Throwable?): VisionFailureKind {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current = error
    var result = VisionFailureKind.NONE
    repeat(16) {
        val value = current ?: return result
        if (!seen.add(value)) return result
        val kind = when (value) {
            is TimeoutException -> VisionFailureKind.TIMEOUT
            is CancellationException, is InterruptedException -> VisionFailureKind.CANCELLED
            is VisionNativeFailure -> value.kind
            is VisionRuntimeException -> if (value.reason in setOf("vision_child_died", "vision_service_disconnected", "vision_binding_died")) VisionFailureKind.CHILD_DIED else VisionFailureKind.OTHER
            is OutOfMemoryError -> VisionFailureKind.OUT_OF_MEMORY
            is SecurityException -> VisionFailureKind.SECURITY
            is IllegalArgumentException -> VisionFailureKind.ARGUMENT
            is IllegalStateException -> VisionFailureKind.STATE
            is LinkageError -> VisionFailureKind.LINKAGE
            else -> VisionFailureKind.OTHER
        }
        if (kind == VisionFailureKind.TIMEOUT || kind == VisionFailureKind.CANCELLED) return kind
        if (result == VisionFailureKind.NONE || result == VisionFailureKind.OTHER) result = kind
        current = value.cause
    }
    return result
}

internal data class VisionOpenSnapshot(val event: VisionOpenEvent, val auxiliary: Boolean, val backend: VisionOpenBackend,
    val durationMs: Long, val remainingMs: Long, val budgetMs: Long, val failure: VisionFailureKind) {
    override fun toString(): String = "event=VISION_OPEN operation=OPEN role=" + (if (auxiliary) "AUXILIARY" else "PRIMARY") +
        " requested_backend=" + backend + " stage=" + event + " failure=" + failure +
        " duration_ms=" + durationMs.coerceIn(0, 300_000) + " remaining_ms=" + remainingMs.coerceIn(0, 300_000) +
        " budget_ms=" + budgetMs.coerceIn(0, 300_000)
}

/** Best effort, no scheduling/admission/cancellation/release effects. Clock/sink injectable for host tests. */
internal class VisionOpenTrace(private val auxiliary: Boolean, private val backend: VisionOpenBackend,
    private val started: Long, private val deadline: Long, private val sink: (VisionOpenSnapshot) -> Unit) {
    fun emit(event: VisionOpenEvent, now: Long, error: Throwable? = null) {
        runCatching { sink(VisionOpenSnapshot(event, auxiliary, backend, boundedDifference(now, started),
            boundedDifference(deadline, now), boundedDifference(deadline, started), visionFailureKind(error))) }
    }
    private fun boundedDifference(end: Long, start: Long): Long =
        if (end <= start) 0 else if (start < 0 && end > Long.MAX_VALUE + start) 300_000 else (end - start).coerceAtMost(300_000)
}

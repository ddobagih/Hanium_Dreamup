package kr.co.hanium.dreamup.walksafe.runtime.metric

import android.content.Context
import kr.co.hanium.dreamup.walksafe.inference.composite.FixtureRuntimeProtocol

internal enum class MetricSupportFailure {
    ASYNC_REQUIRED, WRONG_PARENT_PROCESS, BUSY, BIND_REJECTED, HANDSHAKE_TIMEOUT,
    IDENTITY_MISMATCH, QUERY_TIMEOUT, NATIVE_FAILED, CLOSE_FAILED, CHILD_DIED,
    RELEASE_UNCONFIRMED, CANCELLED, DEADLINE_REACHED, PROTOCOL_ERROR,
}

internal class MetricSupportProbeException(val reason: MetricSupportFailure, cause: Throwable? = null) :
    Exception("metric_support_${reason.name.lowercase()}", cause)

/** Small bounded control messages only. No camera, frames, account data or model tensors cross IPC. */
internal object MetricSupportProtocol {
    const val VERSION = 1
    const val DESCRIPTOR = "walksafe.metric.support.v1"
    const val CALLBACK_DESCRIPTOR = "walksafe.metric.support.callback.v1"
    const val PROCESS_SUFFIX = ":metric_support"
    const val HELLO = 1
    const val QUERY = 2
    const val EXIT = 3
    const val RESULT = 1
    const val SUPPORTED = 1
    const val UNSUPPORTED = 0
    const val FAILED = -1
    const val HANDSHAKE_MS = 5_000L
    const val NATIVE_MS = 10_000L
    const val DEATH_ACK_MS = 5_000L
    const val MAX_PARCEL_BYTES = 2_048
    const val FAULT_NONE = 0
    const val FAULT_CREATE_HANG = 1
    const val FAULT_QUERY_HANG = 2
    const val FAULT_CLOSE_HANG = 3
    const val FAULT_SUPPORTED = 4
    const val FAULT_UNSUPPORTED = 5
    const val FAULT_QUERY_ERROR = 6

    fun validNonce(value: String?) = value != null && value.length == 64 && value.all { it in 'a'..'f' || it in '0'..'9' }
    fun validIdentity(expectedName: String, actualName: String?, parentPid: Int, workerPid: Int,
        expectedUid: Int, actualUid: Int): Boolean = expectedName.endsWith(PROCESS_SUFFIX) &&
        expectedName == actualName && parentPid > 0 && workerPid > 0 && workerPid != parentPid && expectedUid == actualUid
    fun validFault(value: Int, debug: Boolean) = value == FAULT_NONE || debug && value in FAULT_CREATE_HANG..FAULT_QUERY_ERROR
    fun processName(context: Context): String? = FixtureRuntimeProtocol.processName(context)
}

/** One worker process admits exactly one parent nonce and one native query. */
internal class MetricSupportAttemptGate {
    private var nonce: String? = null
    private var parentPid = -1
    private var started = false
    @Synchronized fun hello(value: String, callerPid: Int): Boolean {
        if (!MetricSupportProtocol.validNonce(value) || callerPid <= 0 ||
            nonce != null && (nonce != value || parentPid != callerPid)) return false
        nonce = value; parentPid = callerPid
        return true
    }
    @Synchronized fun accepts(value: String, callerPid: Int): Boolean = nonce == value && parentPid == callerPid
    @Synchronized fun start(value: String, callerPid: Int): Boolean {
        if (!accepts(value, callerPid) || started) return false
        started = true
        return true
    }
}

/** This fence is released by actual endpoint death/unbind or revoked pre-QUERY native authority. */
internal object MetricSupportSupervisor {
    private var owner: Any? = null
    @Synchronized fun acquire(value: Any): Boolean = if (owner == null) { owner = value; true } else false
    @Synchronized fun owns(value: Any) = owner === value
    @Synchronized fun release(value: Any, confirmed: Boolean): Boolean =
        if (confirmed && owner === value) { owner = null; true } else false
}

package kr.co.hanium.dreamup.walksafe.device

import java.util.concurrent.atomic.AtomicLong

/** Process-local sequence only; never derived from an account, device or native owner ID. */
internal object DeviceCheckTraceIds {
    private val next = AtomicLong()

    fun next(): Long = next.updateAndGet { previous -> if (previous == Long.MAX_VALUE) 1L else previous + 1L }
}

internal enum class DeviceCheckTraceStage {
    UI_ACTION, AUTHORITY_READ, JOURNAL_BEGIN, AUTHORITY_RECONCILE, LEASE_PREPARATION,
    PRIMARY_DRAIN, PRIMARY_CLOSE, AUXILIARY_DRAIN, NATIVE_RELEASE, ALLOCATION_AVAILABLE,
    CONSUMER_RELEASE, HOST_RESUME, LEASE_BUDGET, CANDIDATE_SEARCH, FINAL_PREPARATION,
    SAVING, PUBLISH, ATTEMPT,
}

internal enum class DeviceCheckTraceOutcome {
    STARTED, WAITING, SUCCEEDED, FAILED, BLOCKED, CANCELLED, STALE, ACQUIRED,
    RELEASED, RETAINED, OWNER_GONE, JOINED,
}

internal enum class DeviceCheckTraceReason {
    NONE, READ_FAILED, WRITER_PENDING, STORAGE_UNCERTAIN, RECONCILE_FAILED, SCHEMA_BLOCKED,
    NATIVE_RELEASE_PENDING, STALE_SESSION, STALE_BINDING, NOT_CURRENT, HOST_UNPROVEN,
    AUXILIARY_UNPROVEN, NATIVE_UNPROVEN, JOURNAL_PENDING, OWNER_GONE, CANCELLED,
    SUPERSEDED, QUEUE_REJECTED, FAILED, TIMED_OUT,
}

/** Only generated correlation numbers and compile-time enums may enter local diagnostics. */
internal data class DeviceCheckTraceEvent(
    val flowId: Long,
    val stage: DeviceCheckTraceStage,
    val outcome: DeviceCheckTraceOutcome,
    val reason: DeviceCheckTraceReason = DeviceCheckTraceReason.NONE,
    val sessionGeneration: Long? = null,
    val attemptGeneration: Long? = null,
    val state: PostLoginDeviceCheckState? = null,
    val failure: PostLoginDeviceCheckFailure? = null,
)

internal fun interface DeviceCheckTraceSink {
    fun record(event: DeviceCheckTraceEvent)

    companion object {
        val NONE = DeviceCheckTraceSink { }
    }
}

/** Logging cannot change admission, cleanup, or completion even if a diagnostic channel fails. */
internal fun DeviceCheckTraceSink.emit(event: DeviceCheckTraceEvent) {
    runCatching { record(event) }
}

/** Fixed, bounded fields; no free text, exception, actor, source, image or storage token API. */
internal fun formatDeviceCheckTrace(event: DeviceCheckTraceEvent): String {
    val flow = event.flowId.takeIf { it > 0L }?.toString() ?: "NA"
    val session = event.sessionGeneration?.takeIf { it >= 0L }?.toString() ?: "NA"
    val attempt = event.attemptGeneration?.takeIf { it >= 0L }?.toString() ?: "NA"
    return "event=DEVICE_CHECK flow_id=" + flow + " stage=" + event.stage.name +
        " outcome=" + event.outcome.name + " reason=" + event.reason.name +
        " session_generation=" + session + " attempt_generation=" + attempt +
        " state=" + (event.state?.name ?: "NA") + " failure=" + (event.failure?.name ?: "NA")
}

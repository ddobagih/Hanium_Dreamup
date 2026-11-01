package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.inference.composite.ValidatedJointRuntimeChoice
import java.util.concurrent.CompletionStage

/** No Vision/Binder/Activity types cross the durable state boundary. */
data class DeviceCheckAuthorityBinding(val actorBindingHash: String, val resultBindingHash: String,
    val jointBindingHash: String, val runtimeBuildId: String)

sealed interface DeviceCheckAuthorityOutcome<out T> {
    data class Committed<T>(val value: T, val revision: Long, val commandId: String) : DeviceCheckAuthorityOutcome<T>
    data class Rejected(val reason: String) : DeviceCheckAuthorityOutcome<Nothing>
    data class NotPublished(val reason: String) : DeviceCheckAuthorityOutcome<Nothing>
    data class Indeterminate(val operationId: String) : DeviceCheckAuthorityOutcome<Nothing>
}

sealed interface DeviceCheckAuthorityRead {
    /** Historical evidence only. The caller must acquire a new, current two-model runtime proof. */
    data class Ready(val bundleRef: DeviceCheckBundleRef, val result: PersistedPostLoginDeviceCheckResult,
        val choice: ValidatedJointRuntimeChoice) : DeviceCheckAuthorityRead
    data class NeedsCheck(val reason: String) : DeviceCheckAuthorityRead
    data class Blocked(val reason: String, val operationId: String? = null) : DeviceCheckAuthorityRead
}

sealed interface DeviceCheckAuthoritySource { val binding: DeviceCheckAuthorityBinding }
class DeviceCheckAttemptRef internal constructor(val attemptId: String, override val binding: DeviceCheckAuthorityBinding,
    val startedAtMs: Long, val deadlineMs: Long, internal val authorityId: String) : DeviceCheckAuthoritySource
class DeviceCheckBundleRef internal constructor(val attemptId: String, val measurementId: String,
    override val binding: DeviceCheckAuthorityBinding, val revision: Long, internal val authorityId: String) : DeviceCheckAuthoritySource
class DeviceCheckOwnerRef internal constructor(val ownerId: String, internal var source: DeviceCheckAuthoritySource,
    internal val sessionGeneration: Long, internal val authorityId: String)

data class DeviceCheckNativeLease(val cohortId: String, val ownerId: String, val ownerEpoch: Long)
data class DeviceCheckNativeProcess(val pid: Int, val uid: Int, val processName: String,
    val startTicks: Long, val bootIdentity: String)
enum class DeviceCheckProcessObservation { ABSENT, SAME_PROCESS, UNKNOWN }
fun interface DeviceCheckProcessObserver {
    /** ABSENT is positive OS evidence about the saved child, never merely missing /proc access. */
    fun observe(process: DeviceCheckNativeProcess): DeviceCheckProcessObservation
}
data class DeviceCheckNativeSpec(val role: String, val modelKey: String, val modelSha256: String,
    val delegate: String, val numThreads: Int, val optionsHash: String)
enum class DeviceCheckNativeReleaseProof { NO_NATIVE_START, CHILD_DEATH }
/** Cleanup receipts only: none supplies a UI pass, model readiness or a new native permit. */
enum class DeviceCheckNativeReleaseSettlement { RECORDED_RELEASE, ALREADY_RELEASED, ABSENT_AFTER_SETTLED_NO_NATIVE }

/** Process-local one-use authority; a decoded journal row is never a permit. */
class DeviceCheckNativePermit internal constructor(val loadId: String, val lease: DeviceCheckNativeLease,
    val spec: DeviceCheckNativeSpec, internal val leaseIdentity: Any, internal val owner: DeviceCheckOwnerRef,
    internal val deadlineMs: Long, internal val authorityId: String) {
    @Volatile internal var consumed = false
    @Volatile internal var revoked = false
}
class DeviceCheckNativeModelRef internal constructor(val loadId: String, val lease: DeviceCheckNativeLease,
    val spec: DeviceCheckNativeSpec, val modelId: String, val actualRuntimeHash: String,
    internal val authorityId: String)

/** Issued only by actual pair preparation; sample success flags/JSON cannot implement this token. */
interface DeviceCheckPairProof {
    val measurementId: String
    val primary: DeviceCheckNativeModelRef
    val auxiliary: DeviceCheckNativeModelRef
    /** Remains usable as an output gate after the one-time commit consumption. */
    fun isCurrent(): Boolean
    fun consume(): Boolean
}

/** All disk/Keystore operations use the asynchronous adapter. Cached queries perform no I/O. */
interface DeviceCheckAuthority {
    /** Capture the session when reserving the request; never look up a newer session on the worker. */
    fun read(binding: DeviceCheckAuthorityBinding,
        expectedSessionGeneration: Long? = null): CompletionStage<DeviceCheckAuthorityRead>
    fun cachedSnapshot(binding: DeviceCheckAuthorityBinding): DeviceCheckAuthorityRead
    fun beginAttempt(binding: DeviceCheckAuthorityBinding, commandId: String, startedAtMs: Long,
        deadlineMs: Long, expectedSessionGeneration: Long? = null): CompletionStage<DeviceCheckAuthorityOutcome<DeviceCheckAttemptRef>>
    fun cancelAttempt(attempt: DeviceCheckAttemptRef, commandId: String,
        reason: String): CompletionStage<DeviceCheckAuthorityOutcome<Unit>>
    /** In-memory binding only; the first LOAD_INTENT commit also persists this binding. */
    fun registerOwner(ownerId: String, source: DeviceCheckAuthoritySource, sessionGeneration: Long): DeviceCheckOwnerRef?
    fun ownerRef(ownerId: String): DeviceCheckOwnerRef?
    fun retireOwner(owner: DeviceCheckOwnerRef)
    fun retireOwnersOutsideSession(sessionGeneration: Long?)
    fun checkAttempt(attempt: DeviceCheckAttemptRef, nowMs: Long): Boolean
    fun prepareNativeOpen(owner: DeviceCheckOwnerRef, lease: DeviceCheckNativeLease, actualLeaseIdentity: Any,
        commandId: String, spec: DeviceCheckNativeSpec, process: DeviceCheckNativeProcess,
        deadlineMs: Long): CompletionStage<DeviceCheckAuthorityOutcome<DeviceCheckNativePermit>>
    fun consumeNativeOpen(permit: DeviceCheckNativePermit, actualLeaseIdentity: Any, nowMs: Long): Boolean
    fun recordModelOpened(permit: DeviceCheckNativePermit, modelId: String,
        actualRuntimeHash: String): CompletionStage<DeviceCheckAuthorityOutcome<DeviceCheckNativeModelRef>>
    fun modelRef(cohortId: String, ownerEpoch: Long, modelId: String): DeviceCheckNativeModelRef?
    fun recordModelCloseAck(model: DeviceCheckNativeModelRef, childPid: Int,
        processDeath: Boolean): CompletionStage<DeviceCheckAuthorityOutcome<Unit>>
    fun recordCohortReleased(cohortId: String, childPid: Int,
        proof: DeviceCheckNativeReleaseProof): CompletionStage<DeviceCheckAuthorityOutcome<Unit>>
    /**
     * Join the exact prepare writer, then confirm release against a fresh canonical disk read.
     * Capture source with the original request. Retired owners may clean up their own request;
     * a new/current owner cannot borrow it. Missing records require positive NO_NATIVE_START.
     * This call immediately fences all new native admission, including other runtime roles.
     */
    fun settleNativeOpenRelease(source: DeviceCheckAuthoritySource, owner: DeviceCheckOwnerRef,
        lease: DeviceCheckNativeLease, actualLeaseIdentity: Any, prepareCommandId: String,
        spec: DeviceCheckNativeSpec, process: DeviceCheckNativeProcess, proof: DeviceCheckNativeReleaseProof):
        CompletionStage<DeviceCheckAuthorityOutcome<DeviceCheckNativeReleaseSettlement>>
    /**
     * An actually dispatched native hard fault, before publishing its cleanup or completing callers.
     * Admission revokes the captured owner/lease immediately; physical cleanup never waits for I/O.
     * Busy, soft freshness limits, user/actor cancellation and ordinary transfer are not hard faults.
     * An unselected candidate loses only its capabilities, so the same check may try another candidate.
     */
    fun retireRuntime(owner: DeviceCheckOwnerRef, cohortId: String, actualLeaseIdentity: Any,
        reason: String): CompletionStage<DeviceCheckAuthorityOutcome<Unit>>
    /** Child TRANSFER has completed; persist the new binding before ADOPT reopens admission. */
    fun recordOwnerTransfer(source: DeviceCheckNativeLease, target: DeviceCheckNativeLease,
        targetOwner: DeviceCheckOwnerRef, actualTargetLeaseIdentity: Any, commandId: String,
        modelRuntimeHashes: Map<String, String>): CompletionStage<DeviceCheckAuthorityOutcome<List<DeviceCheckNativeModelRef>>>
    /** Root has already won the single success-decision CAS; this never publishes a UI PASS. */
    fun commitSuccess(attempt: DeviceCheckAttemptRef, commandId: String, acceptedAtMs: Long,
        result: PersistedPostLoginDeviceCheckResult, choice: ValidatedJointRuntimeChoice,
        pairProof: DeviceCheckPairProof): CompletionStage<DeviceCheckAuthorityOutcome<DeviceCheckBundleRef>>
    fun updateResult(bundle: DeviceCheckBundleRef, commandId: String,
        expectedResult: PersistedPostLoginDeviceCheckResult,
        updatedResult: PersistedPostLoginDeviceCheckResult): CompletionStage<DeviceCheckAuthorityOutcome<DeviceCheckBundleRef>>
    /** Metadata-only process fence; never treats timer/Future cancellation as completed disk I/O. */
    fun publicationUnconfirmed(commandId: String)
    /** Only after the original I/O really returned. A deadline/Future cancellation is insufficient. */
    fun reconcileAfterSettled(operationId: String): CompletionStage<DeviceCheckAuthorityRead>
}

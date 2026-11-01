package kr.co.hanium.dreamup.walksafe.device

import android.content.Context
import android.os.Build
import android.os.SystemClock
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.inference.composite.JointRuntimeChoiceCodec
import kr.co.hanium.dreamup.walksafe.inference.composite.ValidatedJointRuntimeChoice
import kr.co.hanium.dreamup.walksafe.inference.composite.JointPairReadyProof
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.AndroidVisionProcessObserver
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AndroidKeyStoreAead
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One process-shared writer and uncertainty fence per canonical storage namespace. */
class AndroidDeviceCheckAuthority internal constructor(
    private val core: DeviceCheckAuthorityCore,
    private val diagnostic: Boolean,
    private val timeoutMs: Long = 15_000L,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "DeviceCheckAuthorityIo").apply { isDaemon = true } },
) : DeviceCheckAuthority {
    private val lock = Any()
    private val watch = ScheduledThreadPoolExecutor(1) { Thread(it, "DeviceCheckAuthorityDeadline").apply { isDaemon = true } }.apply {
        removeOnCancelPolicy = true
    }
    private class Ticket(val fingerprint: String) {
        val phase = AtomicInteger(0) // queued/running/unconfirmed/done
        val settled = CompletableFuture<Unit>()
        val future = CompletableFuture<DeviceCheckAuthorityOutcome<Any?>>()
    }
    private val tickets = linkedMapOf<String, Ticket>()
    private class NativePrepare(val capture: DeviceCheckNativePrepareCapture) {
        val settled = CompletableFuture<Unit>()
        val result = CompletableFuture<DeviceCheckAuthorityOutcome<DeviceCheckNativePermit>>()
        var cleanupRequested = false // guarded by adapter lock
    }
    // A public timeout cannot discard the capability needed to settle its real writer later.
    // Active/uncertain captures are never evicted; physical cohort ACK frees the whole cohort.
    private val nativePrepares = linkedMapOf<String, NativePrepare>()

    override fun read(binding: DeviceCheckAuthorityBinding,
        expectedSessionGeneration: Long?): CompletionStage<DeviceCheckAuthorityRead> {
        if (!core.admit(binding, expectedSessionGeneration))
            return CompletableFuture.completedFuture(DeviceCheckAuthorityRead.Blocked("binding_or_session_not_current"))
        val id = UUID.randomUUID().toString()
        return submit(id, hash("read", binding, expectedSessionGeneration)) {
            DeviceCheckAuthorityOutcome.Committed(core.read(binding, expectedSessionGeneration), 0, id)
        }.thenApply(::readOutcome)
    }
    override fun cachedSnapshot(binding: DeviceCheckAuthorityBinding) = core.cachedSnapshot(binding)
    override fun beginAttempt(binding: DeviceCheckAuthorityBinding, commandId: String, startedAtMs: Long,
        deadlineMs: Long, expectedSessionGeneration: Long?): CompletionStage<DeviceCheckAuthorityOutcome<DeviceCheckAttemptRef>> {
        if (!core.requestSessionCurrent(expectedSessionGeneration)) return rejected("session_not_current")
        val fingerprint = hash("begin", binding, startedAtMs, deadlineMs, expectedSessionGeneration)
        existing<DeviceCheckAttemptRef>(commandId, fingerprint)?.let { return it }
        if (!core.admit(binding, expectedSessionGeneration, commandId)) return rejected("binding_or_session_not_current")
        return submit(commandId, fingerprint) {
            core.beginAttempt(binding, commandId, startedAtMs, deadlineMs, expectedSessionGeneration)
        }
    }
    override fun cancelAttempt(attempt: DeviceCheckAttemptRef, commandId: String,
        reason: String): CompletionStage<DeviceCheckAuthorityOutcome<Unit>> {
        if (!core.reserveCancellation(attempt)) return rejected("success_reserved_or_stale_attempt")
        return submit(commandId, hash("cancel", attempt.attemptId, reason)) { core.cancelAttempt(attempt, commandId, reason) }
    }
    override fun registerOwner(ownerId: String, source: DeviceCheckAuthoritySource, sessionGeneration: Long) =
        core.registerOwner(ownerId, source, sessionGeneration)
    override fun ownerRef(ownerId: String) = core.ownerRef(ownerId)
    override fun retireOwner(owner: DeviceCheckOwnerRef) = core.retireOwner(owner)
    override fun retireOwnersOutsideSession(sessionGeneration: Long?) = core.retireOwnersOutsideSession(sessionGeneration)
    override fun checkAttempt(attempt: DeviceCheckAttemptRef, nowMs: Long) = core.checkAttempt(attempt, nowMs)
    override fun prepareNativeOpen(owner: DeviceCheckOwnerRef, lease: DeviceCheckNativeLease, actualLeaseIdentity: Any,
        commandId: String, spec: DeviceCheckNativeSpec, process: DeviceCheckNativeProcess,
        deadlineMs: Long): CompletionStage<DeviceCheckAuthorityOutcome<DeviceCheckNativePermit>> {
        val fingerprint = hash("open", owner, lease, System.identityHashCode(actualLeaseIdentity), spec, process, deadlineMs)
        synchronized(lock) { nativePrepares[commandId] }?.let {
            return if (matches(it.capture, it.capture.source, owner, lease, actualLeaseIdentity, spec, process) &&
                it.capture.deadlineMs == deadlineMs) it.result else rejected("command_payload_changed")
        }
        existing<DeviceCheckNativePermit>(commandId, fingerprint)?.let { return it }
        val capture = core.captureNativePrepare(owner, lease, actualLeaseIdentity, commandId, spec, process, deadlineMs)
            ?: return rejected("native_open_not_current")
        val entry = synchronized(lock) {
            nativePrepares[commandId]?.let {
                return if (matches(it.capture, capture.source, owner, lease, actualLeaseIdentity, spec, process) &&
                    it.capture.deadlineMs == deadlineMs) it.result else rejected("command_payload_changed")
            }
            if (nativePrepares.size >= 64) return rejected("native_prepare_capacity")
            NativePrepare(capture).also { nativePrepares[commandId] = it }
        }
        submit(commandId, fingerprint, actualSettled = entry.settled) {
            core.prepareNativeOpen(owner, lease, actualLeaseIdentity, commandId, spec, process, deadlineMs, capture)
        }.whenComplete { outcome, error ->
            if (error == null && outcome != null) {
                if (outcome is DeviceCheckAuthorityOutcome.Rejected || outcome is DeviceCheckAuthorityOutcome.NotPublished)
                    synchronized(lock) { if (nativePrepares[commandId] === entry && !entry.cleanupRequested) nativePrepares.remove(commandId) }
                entry.result.complete(outcome)
            } else {
                core.publicationUnconfirmed(commandId)
                entry.result.complete(DeviceCheckAuthorityOutcome.Indeterminate(core.unsettledOperation() ?: commandId))
            }
        }
        return entry.result
    }
    override fun consumeNativeOpen(permit: DeviceCheckNativePermit, actualLeaseIdentity: Any, nowMs: Long) =
        core.consumeNativeOpen(permit, actualLeaseIdentity, nowMs)
    override fun recordModelOpened(permit: DeviceCheckNativePermit, modelId: String,
        actualRuntimeHash: String): CompletionStage<DeviceCheckAuthorityOutcome<DeviceCheckNativeModelRef>> =
        UUID.randomUUID().toString().let { id -> submit(id, hash("opened", permit.loadId, modelId, actualRuntimeHash)) {
            core.recordModelOpened(permit, modelId, actualRuntimeHash, id)
        } }
    override fun modelRef(cohortId: String, ownerEpoch: Long, modelId: String) = core.modelRef(cohortId, ownerEpoch, modelId)
    override fun recordModelCloseAck(model: DeviceCheckNativeModelRef, childPid: Int,
        processDeath: Boolean): CompletionStage<DeviceCheckAuthorityOutcome<Unit>> {
        // Retain actual cleanup observations even when another publication has become uncertain.
        if (!core.noteModelClose(model, childPid)) return rejected("model_receipt_mismatch")
        val id = UUID.randomUUID().toString()
        return submit(id, hash("model_close", model.loadId, childPid, processDeath)) {
            core.recordModelCloseAck(model, childPid, processDeath, id)
        }
    }
    override fun recordCohortReleased(cohortId: String, childPid: Int,
        proof: DeviceCheckNativeReleaseProof): CompletionStage<DeviceCheckAuthorityOutcome<Unit>> {
        if (!core.noteCohortRelease(cohortId, childPid, proof)) return rejected("cohort_receipt_mismatch")
        val id = UUID.randomUUID().toString()
        return submit(id, hash("cohort_release", cohortId, childPid, proof)) {
            core.recordCohortReleased(cohortId, childPid, proof, id)
        }.thenApply { outcome ->
            if (outcome is DeviceCheckAuthorityOutcome.Committed) forgetNativePrepares(cohortId)
            outcome
        }
    }
    override fun settleNativeOpenRelease(source: DeviceCheckAuthoritySource, owner: DeviceCheckOwnerRef,
        lease: DeviceCheckNativeLease, actualLeaseIdentity: Any, prepareCommandId: String,
        spec: DeviceCheckNativeSpec, process: DeviceCheckNativeProcess, proof: DeviceCheckNativeReleaseProof):
        CompletionStage<DeviceCheckAuthorityOutcome<DeviceCheckNativeReleaseSettlement>> {
        val entry = synchronized(lock) {
            val value = nativePrepares[prepareCommandId] ?: return rejected("native_prepare_capture_missing")
            if (!matches(value.capture, source, owner, lease, actualLeaseIdentity, spec, process))
                return rejected("native_prepare_capture_mismatch")
            value.cleanupRequested = true
            value
        }
        // This is metadata-only and precedes the writer join: every runtime role loses admission now.
        if (!core.reserveNativeCleanup(entry.capture)) return rejected("native_prepare_capture_unissued")
        return entry.settled.thenCompose {
            // A new operation ID allows a fresh confirmation after a previous uncertain I/O settled.
            val id = UUID.randomUUID().toString()
            submit(id, hash("settle_native_open", prepareCommandId, proof)) {
                core.settleNativeOpenRelease(entry.capture, proof, id)
            }.thenApply { outcome ->
                if (outcome is DeviceCheckAuthorityOutcome.Committed) forgetNativePrepares(lease.cohortId)
                outcome
            }
        }
    }
    private fun matches(capture: DeviceCheckNativePrepareCapture, source: DeviceCheckAuthoritySource,
        owner: DeviceCheckOwnerRef, lease: DeviceCheckNativeLease, identity: Any,
        spec: DeviceCheckNativeSpec, process: DeviceCheckNativeProcess) = capture.source === source &&
        capture.owner === owner && capture.lease == lease && capture.identity === identity &&
        capture.spec == spec && capture.process == process
    private fun forgetNativePrepares(cohort: String) = synchronized(lock) {
        nativePrepares.entries.removeAll { it.value.capture.lease.cohortId == cohort }
    }
    override fun retireRuntime(owner: DeviceCheckOwnerRef, cohortId: String, actualLeaseIdentity: Any,
        reason: String): CompletionStage<DeviceCheckAuthorityOutcome<Unit>> {
        val retirement = core.reserveRuntimeRetirement(owner, cohortId, actualLeaseIdentity, reason)
            ?: return rejected("runtime_fault_owner_or_lease_stale")
        val id = UUID.randomUUID().toString()
        return submit(id, hash("runtime_retired", retirement.lease, retirement.attemptId, reason)) {
            core.retireRuntime(retirement, id)
        }
    }
    override fun recordOwnerTransfer(source: DeviceCheckNativeLease, target: DeviceCheckNativeLease,
        targetOwner: DeviceCheckOwnerRef, actualTargetLeaseIdentity: Any, commandId: String,
        modelRuntimeHashes: Map<String, String>): CompletionStage<DeviceCheckAuthorityOutcome<List<DeviceCheckNativeModelRef>>> =
        submit(commandId, hash("transfer", source, target, targetOwner, System.identityHashCode(actualTargetLeaseIdentity), modelRuntimeHashes.toSortedMap())) {
            core.recordOwnerTransfer(source, target, targetOwner, actualTargetLeaseIdentity, commandId, modelRuntimeHashes.toMap())
        }
    override fun commitSuccess(attempt: DeviceCheckAttemptRef, commandId: String, acceptedAtMs: Long,
        result: PersistedPostLoginDeviceCheckResult, choice: ValidatedJointRuntimeChoice,
        pairProof: DeviceCheckPairProof): CompletionStage<DeviceCheckAuthorityOutcome<DeviceCheckBundleRef>> {
        val fingerprint = hash("success", attempt.attemptId, acceptedAtMs, result, JointRuntimeChoiceCodec.encode(choice),
            pairProof.primary.loadId, pairProof.auxiliary.loadId)
        existing<DeviceCheckBundleRef>(commandId, fingerprint)?.let { return it }
        // No disk/Keystore work here. Root's winning decision freezes its actual proof before cleanup.
        val frozen = runCatching { core.freezeSuccess(attempt, acceptedAtMs, result, choice, pairProof) }.getOrNull()
            ?: return rejected("pair_proof_or_success_decision_stale")
        return submit(commandId, fingerprint) { core.commitSuccess(commandId, frozen) }
    }
    override fun updateResult(bundle: DeviceCheckBundleRef, commandId: String,
        expectedResult: PersistedPostLoginDeviceCheckResult,
        updatedResult: PersistedPostLoginDeviceCheckResult): CompletionStage<DeviceCheckAuthorityOutcome<DeviceCheckBundleRef>> =
        submit(commandId, hash("update", bundle.attemptId, bundle.measurementId, bundle.revision, expectedResult, updatedResult)) {
            core.updateResult(bundle, commandId, expectedResult, updatedResult)
        }
    override fun publicationUnconfirmed(commandId: String) {
        core.publicationUnconfirmed(commandId)
        synchronized(lock) { tickets[commandId] }?.let { ticket ->
            val previous = ticket.phase.getAndSet(2)
            if (previous == 0) ticket.settled.complete(Unit) // It can never enter I/O after losing queue authority.
            ticket.future.complete(DeviceCheckAuthorityOutcome.Indeterminate(core.unsettledOperation() ?: commandId))
        }
    }
    override fun reconcileAfterSettled(operationId: String): CompletionStage<DeviceCheckAuthorityRead> {
        val unsettled = synchronized(lock) { tickets.values.filter { !it.settled.isDone }.map { it.settled } }
        return CompletableFuture.allOf(*unsettled.toTypedArray()).thenCompose {
            val id = UUID.randomUUID().toString()
            submit(id, hash("reconcile", operationId), recovery = true) {
                DeviceCheckAuthorityOutcome.Committed(core.reconcile(operationId), 0, id)
            }.thenApply(::readOutcome)
        }
    }
    internal fun cachedChoice(jointHash: String): ValidatedJointRuntimeChoice? {
        val binding = core.currentBinding()?.takeIf { it.jointBindingHash == jointHash } ?: return null
        return (core.cachedSnapshot(binding) as? DeviceCheckAuthorityRead.Ready)?.choice
    }
    internal fun cachedResult(resultHash: String): PersistedPostLoginDeviceCheckResult? {
        val binding = core.currentBinding()?.takeIf { it.resultBindingHash == resultHash } ?: return null
        return (core.cachedSnapshot(binding) as? DeviceCheckAuthorityRead.Ready)?.result
    }
    internal fun hasCachedResult(): Boolean = core.currentBinding()?.let { core.cachedSnapshot(it) is DeviceCheckAuthorityRead.Ready } == true
    internal fun retireChoice(choice: ValidatedJointRuntimeChoice): CompletionStage<DeviceCheckAuthorityOutcome<Unit>> {
        if (!core.reserveChoiceRetirement(choice)) return rejected("choice_obsolete_or_changed")
        val id = UUID.randomUUID().toString()
        return submit(id, hash("retire_choice", choice.measurementId, JointRuntimeChoiceCodec.encode(choice))) {
            core.retireChoice(choice, id)
        }
    }
    internal fun retireChoiceBlocking(choice: ValidatedJointRuntimeChoice, requireMatch: Boolean): Boolean {
        val binding = core.currentBinding() ?: return false
        return when (val view = core.cachedSnapshot(binding)) {
            is DeviceCheckAuthorityRead.Blocked -> false
            is DeviceCheckAuthorityRead.NeedsCheck -> !requireMatch
            is DeviceCheckAuthorityRead.Ready -> {
                if (view.choice.measurementId != choice.measurementId) !requireMatch
                else if (JointRuntimeChoiceCodec.encode(view.choice) != JointRuntimeChoiceCodec.encode(choice)) false
                else runCatching {
                    val outcome = retireChoice(choice).toCompletableFuture().get(timeoutMs + 100L, TimeUnit.MILLISECONDS)
                    outcome is DeviceCheckAuthorityOutcome.Committed
                }.getOrDefault(false)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> existing(id: String, fingerprint: String): CompletionStage<DeviceCheckAuthorityOutcome<T>>? = synchronized(lock) {
        tickets[id]?.let { if (it.fingerprint == fingerprint) it.future as CompletionStage<DeviceCheckAuthorityOutcome<T>>
            else CompletableFuture.completedFuture(DeviceCheckAuthorityOutcome.Rejected("command_payload_changed")) }
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> submit(id: String, fingerprint: String, recovery: Boolean = false,
        actualSettled: CompletableFuture<Unit>? = null,
        action: () -> DeviceCheckAuthorityOutcome<T>): CompletionStage<DeviceCheckAuthorityOutcome<T>> {
        if (!DeviceCheckAuthorityValidation.command(id)) { actualSettled?.complete(Unit); return rejected("invalid_command") }
        existing<T>(id, fingerprint)?.let {
            synchronized(lock) { tickets[id] }?.settled?.whenComplete { _, _ -> actualSettled?.complete(Unit) }
            return it
        }
        if (!recovery) core.unsettledOperation()?.let {
            actualSettled?.complete(Unit) // This request never acquired a writer ticket.
            return CompletableFuture.completedFuture(DeviceCheckAuthorityOutcome.Indeterminate(it))
        }
        val ticket = synchronized(lock) {
            tickets[id]?.let {
                it.settled.whenComplete { _, _ -> actualSettled?.complete(Unit) }
                return existing<T>(id, fingerprint)!!
            }
            if (tickets.values.count { !it.settled.isDone } >= 64) {
                actualSettled?.complete(Unit); return rejected("writer_queue_full")
            }
            Ticket(fingerprint).also { value ->
                value.settled.whenComplete { _, _ -> actualSettled?.complete(Unit) }
                tickets[id] = value
                while (tickets.size > 256) {
                    val removable = tickets.entries.firstOrNull { it.value.settled.isDone && it.key != id } ?: break
                    tickets.remove(removable.key)
                }
            }
        }
        val timer = try { watch.schedule({ publicationUnconfirmed(id) }, timeoutMs, TimeUnit.MILLISECONDS) }
        catch (_: Throwable) {
            publicationUnconfirmed(id); ticket.settled.complete(Unit)
            return ticket.future as CompletionStage<DeviceCheckAuthorityOutcome<T>>
        }
        try { worker.execute {
            if (!ticket.phase.compareAndSet(0, 1)) { ticket.settled.complete(Unit); timer.cancel(false); return@execute }
            var outcome: DeviceCheckAuthorityOutcome<T>
            if (!core.enterWork(id, recovery)) {
                outcome = DeviceCheckAuthorityOutcome.Indeterminate(core.unsettledOperation() ?: id)
            } else {
                try { outcome = action() }
                catch (_: Throwable) { core.publicationUnconfirmed(id); outcome = DeviceCheckAuthorityOutcome.Indeterminate(id) }
                finally { core.leaveWork(id) }
            }
            ticket.settled.complete(Unit)
            if (ticket.phase.compareAndSet(1, 3)) ticket.future.complete(outcome as DeviceCheckAuthorityOutcome<Any?>)
            timer.cancel(false)
        } } catch (_: Throwable) {
            ticket.phase.set(3); ticket.settled.complete(Unit); timer.cancel(false)
            ticket.future.complete(DeviceCheckAuthorityOutcome.Rejected("writer_rejected_before_io"))
        }
        return ticket.future as CompletionStage<DeviceCheckAuthorityOutcome<T>>
    }
    private fun <T> rejected(reason: String): CompletionStage<DeviceCheckAuthorityOutcome<T>> =
        CompletableFuture.completedFuture(DeviceCheckAuthorityOutcome.Rejected(reason))
    private fun readOutcome(outcome: DeviceCheckAuthorityOutcome<DeviceCheckAuthorityRead>): DeviceCheckAuthorityRead = when (outcome) {
        is DeviceCheckAuthorityOutcome.Committed -> outcome.value
        is DeviceCheckAuthorityOutcome.Indeterminate -> DeviceCheckAuthorityRead.Blocked("publication_unconfirmed", outcome.operationId)
        is DeviceCheckAuthorityOutcome.Rejected -> DeviceCheckAuthorityRead.Blocked(outcome.reason)
        is DeviceCheckAuthorityOutcome.NotPublished -> DeviceCheckAuthorityRead.Blocked(outcome.reason)
    }
    private fun hash(vararg fields: Any?) = DeviceCheckAuthorityValidation.digest(fields.joinToString("\u0000") {
        if (it is PersistedPostLoginDeviceCheckResult) DeviceCheckAuthorityCodec.resultText(it) else it.toString()
    })
    internal fun shutdownForTests() { worker.shutdownNow(); watch.shutdownNow() }

    companion object {
        private val instances = linkedMapOf<String, AndroidDeviceCheckAuthority>()
        /** Restoration evidence is insufficient to commit a newly measured production check. */
        internal fun proofOriginVerifier(diagnostic: Boolean): (DeviceCheckPairProof) -> Boolean =
            if (diagnostic) JointPairReadyProof::ownsProof else JointPairReadyProof::ownsQualifiedProof
        @JvmStatic fun forApp(context: Context): AndroidDeviceCheckAuthority = instance(context, "production", false)
        @JvmStatic fun forDiagnostics(context: Context, namespace: String): AndroidDeviceCheckAuthority {
            check(BuildConfig.DEBUG)
            require(namespace.matches(Regex("[A-Za-z0-9_-]{1,80}")))
            return instance(context, namespace, true)
        }
        @JvmStatic fun isDiagnostic(authority: DeviceCheckAuthority): Boolean = (authority as? AndroidDeviceCheckAuthority)?.diagnostic == true
        private fun instance(context: Context, namespace: String, diagnostic: Boolean): AndroidDeviceCheckAuthority {
            val app = context.applicationContext
            check(kr.co.hanium.dreamup.walksafe.inference.composite.FixtureRuntimeProtocol.processName(app) == app.applicationInfo.processName) {
                "device_check_authority_requires_parent_process"
            }
            // Construct the framework's no_backup path without creating directories on the UI lane.
            val root = File(app.applicationInfo.dataDir, "no_backup")
            val directory = if (diagnostic) File(root, "device-check-diagnostics/$namespace") else File(root, "device-check-authority")
            val key = directory.absolutePath
            return synchronized(instances) { instances.getOrPut(key) {
                val purpose = if (diagnostic) "walksafe.devicecheck.diagnostic.${DeviceCheckAuthorityValidation.digest(namespace).take(24)}.key."
                    else "walksafe.devicecheck.authority.key."
                val storage = AndroidDeviceCheckAuthorityStorage(directory, AndroidKeyStoreAead(AeadKeyPolicy(purpose, 1, setOf(1))),
                    if (diagnostic) "diagnostic:$namespace" else "production")
                AndroidDeviceCheckAuthority(DeviceCheckAuthorityCore(storage, AndroidVisionProcessObserver(app),
                    SystemClock::elapsedRealtime, { Runtime.getRuntime().availableProcessors() }, Build.VERSION.SDK_INT >= 29,
                    verifyProofOrigin = proofOriginVerifier(diagnostic), requireSessionObservation = !diagnostic), diagnostic)
            } }
        }
    }
}

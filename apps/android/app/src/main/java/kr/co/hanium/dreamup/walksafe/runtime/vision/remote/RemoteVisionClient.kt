package kr.co.hanium.dreamup.walksafe.runtime.vision.remote

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.os.Looper
import kr.co.hanium.dreamup.walksafe.device.AndroidDeviceCheckAuthority
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckAuthority
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckAuthorityOutcome
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckOwnerRef
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckNativeLease
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckNativeProcess
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckNativeSpec
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckNativePermit
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckNativeModelRef
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckNativeReleaseProof
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckAuthoritySource
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor

/** Process-scoped owner. Public lease/handle copies cannot grant admission or retire another epoch. */
class RemoteVisionClient private constructor(private val context: Context) {
    private val lock = Any()
    private val ipc = lane("VisionParentControl", 2, 4)
    private val binding = lane("VisionParentBind", 1, 1)
    private val admission = lane("VisionParentAdmission", 1, 1)
    private val exiting = lane("VisionParentExit", 1, 1)
    private val unbinding = lane("VisionParentUnbind", 1, 2)
    private val watch = ScheduledThreadPoolExecutor(1) { Thread(it, "VisionParentWatchdog").apply { isDaemon = true } }.apply { removeOnCancelPolicy = true }
    private val records = LinkedHashMap<String, Cohort>()
    private val completedCohorts = LinkedHashMap<String, CompletableFuture<VisionReleaseReceipt>>()
    private val ownerRetirements = LinkedHashMap<DeviceCheckOwnerRef, VisionRetirementSnapshot>()
    private val completedModelIds = java.util.ArrayDeque<String>()
    private val cleanupPending = LinkedHashMap<String, Cohort>()
    private var cleanupAvailability: CompletableFuture<VisionReleaseReceipt>? = null
    private var active: Cohort? = null
    private val ids = AtomicLong()
    @Volatile internal var debugBeforeHandleDispatch: ((VisionModelHandle) -> Unit)? = null
    @Volatile internal var debugBeforeExitRetirement: ((VisionCohortLease) -> Unit)? = null
    @Volatile internal var debugBeforeFaultMetadata: ((VisionCohortLease) -> Unit)? = null
    @Volatile internal var debugBeforeJournalCleanup: ((VisionCohortLease) -> Unit)? = null
    private class Pending(val epoch: Long, val deadline: Long) {
        val result = CompletableFuture<String>()
        val transportFinished = CompletableFuture<Unit>()
    }
    private class NativeFault(val owner: DeviceCheckOwnerRef, val lease: VisionCohortLease, val reason: String) {
        val applying = AtomicBoolean(false)
        var metadataApplied = false
    }
    private class Model(val spec: VisionModelSpec) {
        var handle: VisionModelHandle? = null
        var nativeRef: DeviceCheckNativeModelRef? = null
        val operation = ReentrantLock()
        val resources = mutableSetOf<VisionSharedMemory>()
        val released = CompletableFuture<VisionModelReleaseReceipt>()
    }
    private class OpenIntent(val source: DeviceCheckAuthoritySource, val owner: DeviceCheckOwnerRef,
        val lease: VisionCohortLease, val command: String, val spec: DeviceCheckNativeSpec,
        val process: DeviceCheckNativeProcess) {
        var outcome: DeviceCheckAuthorityOutcome<*>? = null
        var invocationFinished = false
    }
    private class Cohort(@Volatile var lease: VisionCohortLease, val nonce: String, val bindDeadline: Long,
        var owner: DeviceCheckOwnerRef, val journal: DeviceCheckAuthority) {
        val ready = CompletableFuture<IBinder>()
        val released = CompletableFuture<VisionReleaseReceipt>()
        // Physical cleanup and durable permission to allocate the next cohort are different receipts.
        val available = CompletableFuture<VisionReleaseReceipt>()
        val parent = VisionParentReleaseTracker()
        val startup = requireNotNull(parent.reserve())
        val openIntents = mutableListOf<OpenIntent>()
        var journalCleanupRunning = false
        var availabilityClaimed = false
        var physicalReceipt: VisionReleaseReceipt? = null
        val retirementSignal = CompletableFuture<Unit>()
        val pending = ConcurrentHashMap<Long, Pending>()
        val models = LinkedHashMap<Pair<VisionRole, String>, Model>()
        val roleFlights = VisionRole.entries.associateWith { ReentrantLock() }
        val endpoints = LinkedHashMap<IBinder, Boolean>()
        var binder: IBinder? = null
        @Volatile var pid = -1
        @Volatile var verified = false
        @Volatile var process: DeviceCheckNativeProcess? = null
        var bindEntered = false
        var bindFinished = false
        var unbindClaimed = false
        var unbound = false
        val authority = VisionDispatchAuthority()
        @Volatile var retired = false
        @Volatile var parked = false
        var awaitingAdoption = false
        var adoptionMappingCommitted = false
        var exitQueued = false
        var unbindQueued = false
        var releaseClaimed = false
        var parkDeadlineMs = 0L
        var fdTransports = 0
        var expectedRetirement = false
        var fault: NativeFault? = null
        @Volatile var handshakeWatch: java.util.concurrent.ScheduledFuture<*>? = null
        lateinit var connection: ServiceConnection
    }

    /** Returns the stable receipt owner before binding/INIT can run. OPEN awaits authentication. */
    fun acquire(owner: DeviceCheckOwnerRef, deadlineElapsedMs: Long): VisionCohortLease =
        acquireOwned(owner, deadlineElapsedMs, AndroidDeviceCheckAuthority.forApp(context))

    /** Diagnostics must explicitly create a separate persisted realm and register its immutable owner. */
    fun acquireForDiagnostics(owner: DeviceCheckOwnerRef, deadlineElapsedMs: Long,
        authority: DeviceCheckAuthority): VisionCohortLease {
        check(AndroidDeviceCheckAuthority.isDiagnostic(authority)) { "vision_diagnostic_authority_required" }
        return acquireOwned(owner, deadlineElapsedMs, authority)
    }

    @Deprecated("Production requires an immutable owner scope; diagnostics inject their authority explicitly")
    fun acquire(ownerId: String, deadlineElapsedMs: Long): VisionCohortLease =
        throw VisionStaleAuthorityException()

    private fun acquireOwned(owner: DeviceCheckOwnerRef, deadlineElapsedMs: Long,
        journal: DeviceCheckAuthority): VisionCohortLease {
        requireEnvironment()
        val ownerId = owner.ownerId
        require(VisionRuntimeProtocol.KEY.matches(ownerId))
        if (journal.ownerRef(ownerId) !== owner) throw VisionStaleAuthorityException()
        retryJournalCleanup()
        val deadline = VisionRuntimeProtocol.deadline(deadlineElapsedMs, now(), VisionRuntimeProtocol.HANDSHAKE_MS)
        val record = synchronized(lock) {
            active?.let {
                if (!it.retired && it.owner === owner && it.journal === journal && !it.parked) return it.lease
                throw VisionCohortBusyException(it.available)
            }
            if (cleanupPending.size >= MAX_PENDING_BINDINGS) {
                val wait = cleanupAvailability ?: CompletableFuture<VisionReleaseReceipt>().also { cleanupAvailability = it }
                throw VisionCohortBusyException(wait)
            }
            val value = Cohort(VisionCohortLease(randomToken(), ownerId, 1), randomToken(), deadline, owner, journal)
            value.connection = connection(value)
            records[value.lease.cohortId] = value; cleanupPending[value.lease.cohortId] = value; active = value
            rememberOwnerRetirement(value)
            value
        }
        var bindEnqueueAttempted = false
        try {
            val timer = watch.schedule({ if (!record.ready.isDone) abort(record, "vision_handshake_timeout") },
                (deadline - now()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
            record.handshakeWatch = timer
            if (record.retired || record.ready.isDone) timer.cancel(false)
            bindEnqueueAttempted = true
            execute(record, binding) { bind(record) }
        } catch (error: Throwable) {
            synchronized(lock) { if (!record.bindEntered &&
                (!bindEnqueueAttempted || error is java.util.concurrent.RejectedExecutionException)) {
                record.bindFinished = true; record.parent.bindingFinished()
            } }
            abort(record, "vision_startup_rejected", error)
        } finally { record.startup.finish(); confirmRelease(record) }
        return record.lease
    }

    fun open(lease: VisionCohortLease, spec: VisionModelSpec, deadlineElapsedMs: Long): VisionModelHandle {
        requireWorker()
        VisionRuntimeProtocol.validateSpec(spec)
        val immutable = spec.copy(inputShape = spec.inputShape.copyOf(), outputShapes = spec.outputShapes.map { it.copyOf() }.toTypedArray())
        val record = requireLease(lease)
        val model = synchronized(lock) {
            checkCurrent(record, lease)
            require(!record.parked && !record.models.containsKey(spec.role to spec.modelKey)) { "ALREADY_OPEN" }
            require(record.models.values.count { it.spec.role == spec.role } < if (spec.role == VisionRole.PRIMARY) 2 else 1)
            Model(immutable).also { record.models[spec.role to spec.modelKey] = it }
        }
        try {
            val openBudget = VisionRuntimeProtocol.openBudget(immutable)
            val deadline = VisionRuntimeProtocol.deadline(deadlineElapsedMs, now(), openBudget)
            val permit = onAdmission(record, deadline) {
                await(record.ready, minOf(deadline, record.bindDeadline))
                val source = synchronized(lock) { checkCurrent(record, lease); record.owner to requireNotNull(record.process) }
                require(VisionProcessIdentity.captureVerified(context, source.second) != null) { "vision_process_identity_changed" }
                val intent = OpenIntent(source.first.source, source.first, lease, java.util.UUID.randomUUID().toString(),
                    nativeSpec(immutable), source.second)
                synchronized(lock) {
                    checkCurrent(record, lease)
                    check(record.openIntents.size < 32) { "vision_open_intent_capacity" }
                    record.openIntents += intent
                }
                val preparing = try {
                    record.journal.prepareNativeOpen(intent.owner, nativeLease(lease), lease, intent.command,
                        intent.spec, intent.process, deadline)
                } catch (error: Throwable) {
                    synchronized(lock) { intent.invocationFinished = true }
                    throw error
                }
                preparing.whenComplete { outcome, _ ->
                    synchronized(lock) { intent.outcome = outcome; intent.invocationFinished = true }
                    settleJournalCleanup(record)
                }
                val value = committed(awaitAdmission(record, preparing.toCompletableFuture(), deadline))
                synchronized(lock) { checkCurrent(record, lease) }
                value
            }
            val payload = rpc(record, lease, VisionRuntimeProtocol.OPEN, deadline, openBudget, native = permit) {
                VisionRuntimeProtocol.writeSpec(this, immutable)
            }
            val body: JSONObject
            val runtime: String
            try { body = JSONObject(payload); runtime = checkedRuntime(record, immutable, body.getString("runtimeJson")) }
            catch (error: Throwable) { markNativeFault(record, lease, "open_runtime_contract"); throw error }
            val handle = VisionModelHandle(lease.cohortId, body.getString("modelInstanceId"), lease.ownerEpoch,
                spec.role, spec.modelKey, runtime)
            val reference = onAdmission(record, deadline) {
                committed(awaitAdmission(record, record.journal.recordModelOpened(permit, handle.modelInstanceId,
                    VisionRuntimeCodec.runtimeHash(runtime)).toCompletableFuture(), deadline))
            }
            synchronized(lock) {
                checkCurrent(record, lease); model.handle = handle; model.nativeRef = reference
                modelReceipts[handle.modelInstanceId] = model.released
            }
            return handle
        } catch (error: Throwable) {
            if (error is VisionStaleAuthorityException) {
                abort(record, "vision_open_authority_stale", error, lease)
                throw error
            }
            abort(record, "vision_open_failed", error, lease); throw failure(record, "vision_open_failed", error)
        }
    }

    fun invoke(handle: VisionModelHandle, input: ByteBuffer, outputs: Array<ByteBuffer>, width: Int,
        height: Int, purpose: VisionPurpose, deadlineElapsedMs: Long): VisionInvokeResult {
        requireWorker()
        val (record, model, lease) = requireHandle(handle)
        validateOwner(record.owner, lease)
        if (kr.co.hanium.dreamup.walksafe.BuildConfig.DEBUG) debugBeforeHandleDispatch?.invoke(handle)
        synchronized(lock) { checkHandle(record, model, lease, handle) }
        synchronized(lock) { check(!record.parked) { "vision_cohort_parked" } }
        val roleFlight = record.roleFlights.getValue(handle.role)
        require(roleFlight.tryLock()) { "vision_role_single_flight" }
        if (!model.operation.tryLock()) { roleFlight.unlock(); throw IllegalStateException("vision_model_single_flight") }
        var memory: VisionSharedMemory? = null
        try {
            synchronized(lock) { checkHandle(record, model, lease, handle) }
            val flight = VisionSharedMemory.parent(input, outputs, VisionRuntimeProtocol.inputBytes(model.spec, width, height),
                VisionRuntimeProtocol.outputBytes(model.spec), register = { created ->
                    memory = created
                    synchronized(lock) { checkHandle(record, model, lease, handle); model.resources += created }
                })
            synchronized(lock) { checkHandle(record, model, lease, handle); require(!record.parked) }
            val payload = rpc(record, lease, VisionRuntimeProtocol.INVOKE, deadlineElapsedMs,
                VisionRuntimeProtocol.operationBudget(purpose)) {
                writeString(handle.modelInstanceId); writeInt(width); writeInt(height); writeString(purpose.name); flight.write(this)
            }
            val result = try {
                val body = JSONObject(payload)
                VisionInvokeResult(checkedRuntime(record, model.spec, body.getString("runtimeJson")),
                    body.getDouble("preprocessingMs"), body.getDouble("inferenceMs"), body.getDouble("outputReadMs")).also { value ->
                    require(listOf(value.preprocessingMs, value.inferenceMs, value.outputReadMs).all { it.isFinite() && it >= 0 })
                }
            } catch (error: Throwable) { markNativeFault(record, lease, "invoke_runtime_contract"); throw error }
            synchronized(lock) { checkHandle(record, model, lease, handle) }
            flight.copyOutputs(outputs)
            return result
        } catch (error: Throwable) {
            if (error is VisionStaleAuthorityException) throw error
            abort(record, "vision_invoke_failed", error, lease); throw failure(record, "vision_invoke_failed", error)
        }
        finally {
            memory?.let { releaseMemory(record, model, it) }
            model.operation.unlock(); roleFlight.unlock(); confirmRelease(record)
        }
    }

    fun close(handle: VisionModelHandle, deadlineElapsedMs: Long): VisionCloseResult {
        requireWorker()
        val (record, model, lease) = requireHandle(handle)
        if (kr.co.hanium.dreamup.walksafe.BuildConfig.DEBUG) debugBeforeHandleDispatch?.invoke(handle)
        synchronized(lock) { checkHandle(record, model, lease, handle) }
        val deadline = VisionRuntimeProtocol.deadline(deadlineElapsedMs, now(), VisionRuntimeProtocol.CLOSE_MS)
        if (!model.operation.tryLock((deadline - now()).coerceAtLeast(1), TimeUnit.MILLISECONDS)) {
            abort(record, "vision_close_barrier_timeout", expectedLease = lease); throw failure(record, "vision_close_barrier_timeout")
        }
        try {
            synchronized(lock) { checkHandle(record, model, lease, handle) }
            val body = JSONObject(rpc(record, lease, VisionRuntimeProtocol.CLOSE, deadline, VisionRuntimeProtocol.CLOSE_MS) {
                writeString(handle.modelInstanceId)
            })
            require(body.getString("modelInstanceId") == handle.modelInstanceId && body.getBoolean("nativeCloseConfirmed"))
            val physical = synchronized(lock) {
                checkHandle(record, model, lease, handle); require(model.resources.isEmpty())
                record.models.remove(handle.role to handle.modelKey)
                retainModelReceipt(handle.modelInstanceId)
                VisionModelReleaseReceipt(record.lease.cohortId, handle.modelInstanceId, record.pid, false)
            }
            try { model.nativeRef?.let { reference -> record.journal.recordModelCloseAck(reference, record.pid, false) } }
            finally { model.released.complete(physical) }
            return VisionCloseResult(handle.modelInstanceId, true)
        } catch (error: Throwable) {
            if (error is VisionStaleAuthorityException) throw error
            abort(record, "vision_close_failed", error, lease); throw failure(record, "vision_close_failed", error)
        }
        finally { model.operation.unlock() }
    }

    fun park(lease: VisionCohortLease, deadlineElapsedMs: Long) {
        requireWorker()
        val record = requireLease(lease)
        synchronized(lock) {
            checkCurrent(record, lease); check(!record.parked); record.parked = true
            record.parkDeadlineMs = minOf(deadlineElapsedMs, now() + VisionRuntimeProtocol.CLOSE_MS)
        }
        rpc(record, lease, VisionRuntimeProtocol.PARK, record.parkDeadlineMs, VisionRuntimeProtocol.CLOSE_MS)
    }
    @Deprecated("Capture the target owner scope before asynchronous transfer")
    fun transfer(lease: VisionCohortLease, newOwnerId: String, deadlineElapsedMs: Long): VisionTransferResult =
        throw VisionStaleAuthorityException()

    fun transfer(lease: VisionCohortLease, targetOwner: DeviceCheckOwnerRef, deadlineElapsedMs: Long): VisionTransferResult {
        requireWorker()
        val newOwnerId = targetOwner.ownerId
        require(VisionRuntimeProtocol.KEY.matches(newOwnerId))
        val record = requireLease(lease)
        if (record.journal.ownerRef(newOwnerId) !== targetOwner) throw VisionStaleAuthorityException()
        synchronized(lock) { checkCurrent(record, lease); require(record.parked && record.models.values.all { it.resources.isEmpty() }) }
        var recoveryLease = lease
        try {
          val deadline = synchronized(lock) {
              checkCurrent(record, lease)
              minOf(deadlineElapsedMs, record.parkDeadlineMs).also { record.parkDeadlineMs = it }
          }
          val body = JSONObject(rpc(record, lease, VisionRuntimeProtocol.TRANSFER,
              deadline, VisionRuntimeProtocol.CLOSE_MS) {
              writeString(newOwnerId)
          })
          val result = synchronized(lock) {
            checkCurrent(record, lease)
            val epoch = Math.incrementExact(lease.ownerEpoch)
            require(body.getLong("ownerEpoch") == epoch && body.getString("ownerId") == newOwnerId)
            val array = body.getJSONArray("handles")
            require(array.length() == record.models.size)
            val seen = mutableSetOf<Pair<VisionRole, String>>()
            val replacements = (0 until array.length()).map { index ->
                val value = array.getJSONObject(index)
                val role = VisionRole.valueOf(value.getString("role")); val key = value.getString("modelKey")
                require(seen.add(role to key))
                val model = requireNotNull(record.models[role to key])
                require(model.handle?.modelInstanceId == value.getString("modelInstanceId"))
                VisionModelHandle(lease.cohortId, value.getString("modelInstanceId"), epoch, role, key,
                    checkedRuntime(record, model.spec, value.getString("runtimeJson"))) to model
            }
            require(record.authority.transfer(lease.ownerEpoch) == epoch)
            record.lease = VisionCohortLease(lease.cohortId, newOwnerId, epoch)
            record.owner = targetOwner
            rememberOwnerRetirement(record)
            recoveryLease = record.lease
            replacements.forEach { (handle, model) -> model.handle = handle }
            record.awaitingAdoption = true
            record.adoptionMappingCommitted = false
            VisionTransferResult(record.lease, replacements.map { it.first })
          }
          val references = onAdmission(record, deadline) {
              committed(awaitAdmission(record, record.journal.recordOwnerTransfer(nativeLease(lease), nativeLease(result.lease),
                  targetOwner, result.lease, java.util.UUID.randomUUID().toString(),
                  result.handles.associate { it.modelInstanceId to VisionRuntimeCodec.runtimeHash(it.runtimeJson) })
                  .toCompletableFuture(), deadline))
          }
          synchronized(lock) {
              checkCurrent(record, result.lease)
              require(references.size == record.models.size && references.map { it.modelId }.toSet() == result.handles.map { it.modelInstanceId }.toSet())
              result.handles.forEach { handle ->
                  val reference = references.single { it.modelId == handle.modelInstanceId }
                  require(reference.lease == nativeLease(result.lease) && reference.actualRuntimeHash == VisionRuntimeCodec.runtimeHash(handle.runtimeJson))
                  requireNotNull(record.models[handle.role to handle.modelKey]).nativeRef = reference
              }
              record.adoptionMappingCommitted = true
          }
          return result
        } catch (error: Throwable) {
            if (error is VisionStaleAuthorityException) throw error
            abort(record, "vision_transfer_failed", error, recoveryLease)
            throw failure(record, "vision_transfer_failed", error)
        }
    }

    /** Call only after both Java wrappers/consumer registry adopt; transfer itself keeps admission closed. */
    fun confirmAdoption(lease: VisionCohortLease, deadlineElapsedMs: Long) {
        requireWorker()
        val record = requireLease(lease)
        val owner = synchronized(lock) {
            checkCurrent(record, lease)
            require(record.parked && record.awaitingAdoption && record.adoptionMappingCommitted)
            record.owner
        }
        if (record.journal.ownerRef(owner.ownerId) !== owner) throw VisionStaleAuthorityException()
        rpc(record, lease, VisionRuntimeProtocol.ADOPT, minOf(deadlineElapsedMs, record.parkDeadlineMs), VisionRuntimeProtocol.DEATH_MS)
        synchronized(lock) { checkCurrent(record, lease); record.awaitingAdoption = false; record.parked = false }
    }

    /** Never blocks a UI/consumer lane and never lets an old lease stop its transferred cohort. */
    fun requestExit(lease: VisionCohortLease, reason: String) {
        val record = synchronized(lock) { records[lease.cohortId]?.takeIf { it.lease === lease } } ?: return
        if (kr.co.hanium.dreamup.walksafe.BuildConfig.DEBUG) debugBeforeExitRetirement?.invoke(lease)
        abort(record, reason.take(160), expectedLease = lease, expectedTermination = true)
    }
    /** Only verified model-output contract failures belong here, never freshness/geometry/cancellation. */
    fun reportNativeFault(owner: DeviceCheckOwnerRef, lease: VisionCohortLease, reason: VisionNativeFaultReason) {
        val record = requireLease(lease)
        validateOwner(owner, lease)
        markNativeFault(record, lease, "output_${reason.name.lowercase()}", allowParked = true)
        abort(record, "vision_native_output_failed", expectedLease = lease)
    }
    fun actualReleasedFuture(cohortId: String): CompletionStage<VisionReleaseReceipt> = synchronized(lock) {
        records[cohortId]?.released ?: completedCohorts[cohortId] ?: throw VisionReceiptExpiredException()
    }
    /** Permission to allocate again also joins durable cleanup; this is never a physical receipt. */
    fun whenAvailable(cohortId: String): CompletionStage<VisionReleaseReceipt> {
        val record = synchronized(lock) { records[cohortId] }
        if (record != null) { settleJournalCleanup(record); return record.available }
        return synchronized(lock) { completedCohorts[cohortId] ?: throw VisionReceiptExpiredException() }
    }
    /** Snapshot only: no bind/OPEN and no authority to read another owner's current lease. */
    fun peekLease(ownerId: String): VisionCohortLease? = synchronized(lock) {
        active?.takeIf { !it.retired && it.lease.ownerId == ownerId }?.lease
    }
    /** Capture once after consumer drain. A retired-but-unreleased cohort still owns its receipt. */
    fun retirementSnapshot(ownerId: String): VisionRetirementSnapshot? = synchronized(lock) {
        active?.takeIf { it.lease.ownerId == ownerId }?.let { VisionRetirementSnapshot(it.lease, it.released, it.available) }
    }
    /** Production retirement uses the captured source ref even after that source has been revoked. */
    fun retirementSnapshot(owner: DeviceCheckOwnerRef): VisionRetirementSnapshot? = synchronized(lock) {
        active?.takeIf { it.owner === owner }?.let { VisionRetirementSnapshot(it.lease, it.released, it.available) }
            ?: ownerRetirements.entries.firstOrNull { it.key === owner }?.value?.takeIf {
                val receipt = it.actualReleased.toCompletableFuture()
                // An old transferred owner must not start waiting for a still-live target cohort.
                receipt.isDone && !receipt.isCompletedExceptionally && !receipt.isCancelled
            }
    }
    /** Bind a wrapper's immutable allocation scope; checking a same-named newer owner is insufficient. */
    fun validateOwner(owner: DeviceCheckOwnerRef, lease: VisionCohortLease) {
        val record = requireLease(lease)
        synchronized(lock) {
            checkCurrent(record, lease)
            if (record.owner !== owner) throw VisionStaleAuthorityException()
        }
        if (record.journal.ownerRef(owner.ownerId) !== owner) throw VisionStaleAuthorityException()
        synchronized(lock) {
            checkCurrent(record, lease)
            if (record.owner !== owner) throw VisionStaleAuthorityException()
        }
    }
    /** Parent-only validation for dormant wrappers; no native operation or admission change occurs. */
    fun validateAttach(lease: VisionCohortLease, handle: VisionModelHandle) {
        synchronized(lock) {
            val record = requireNotNull(records[lease.cohortId])
            checkCurrent(record, lease)
            if (handle.cohortId != lease.cohortId) throw VisionStaleAuthorityException()
            val model = record.models[handle.role to handle.modelKey] ?: throw VisionStaleAuthorityException()
            checkHandle(record, model, lease, handle)
            checkedRuntime(record, model.spec, handle.runtimeJson)
        }
    }
    /** The consumer may activate only after exact-epoch adoption has reopened admission. */
    fun validateAdopted(lease: VisionCohortLease, handle: VisionModelHandle) {
        synchronized(lock) {
            validateAttach(lease, handle)
            val record = requireNotNull(records[lease.cohortId])
            check(!record.parked && !record.awaitingAdoption) { "vision_adoption_pending" }
        }
    }
    /** Exact two-model metadata snapshot only; sample/numerical proof is produced by the pair owner. */
    fun validatePair(lease: VisionCohortLease, primaryModelInstanceId: String,
        auxiliaryModelInstanceId: String): VisionPairSnapshot = synchronized(lock) {
        val record = requireNotNull(records[lease.cohortId])
        checkCurrent(record, lease)
        check(record.verified && record.pid > 0 && record.models.size == 2)
        val primary = record.models.values.single { it.spec.role == VisionRole.PRIMARY }
        val auxiliary = record.models.values.single { it.spec.role == VisionRole.FASTSAM }
        val p = requireNotNull(primary.handle); val a = requireNotNull(auxiliary.handle)
        if (p.modelInstanceId != primaryModelInstanceId || a.modelInstanceId != auxiliaryModelInstanceId)
            throw VisionStaleAuthorityException()
        checkHandle(record, primary, lease, p); checkHandle(record, auxiliary, lease, a)
        checkedRuntime(record, primary.spec, p.runtimeJson); checkedRuntime(record, auxiliary.spec, a.runtimeJson)
        VisionPairSnapshot(lease, p, a, record.pid)
    }
    fun modelReleasedFuture(modelInstanceId: String): CompletionStage<VisionModelReleaseReceipt> = synchronized(lock) {
        modelReceipts[modelInstanceId] ?: throw VisionReceiptExpiredException()
    }
    private val modelReceipts = LinkedHashMap<String, CompletableFuture<VisionModelReleaseReceipt>>()

    /** The service independently enforces DEBUG; this API cannot enable faults in a release APK. */
    fun injectDebugFault(lease: VisionCohortLease, value: String, deadlineElapsedMs: Long) {
        check(kr.co.hanium.dreamup.walksafe.BuildConfig.DEBUG)
        rpc(requireLease(lease), lease, VisionRuntimeProtocol.FAULT, deadlineElapsedMs, 5_000) { writeString(value) }
    }

    private fun bind(record: Cohort) {
        try {
            if (synchronized(lock) { record.retired }) return
            val intent = Intent(context, VisionRuntimeService::class.java)
            synchronized(lock) {
                if (record.retired) return
                record.bindEntered = true; record.parent.bindingStarted()
            }
            context.bindService(intent, record.connection, Context.BIND_AUTO_CREATE)
                .also { if (!it) abort(record, "vision_bind_false") }
        } catch (error: Throwable) { abort(record, "vision_bind_exception", error) }
        finally {
            synchronized(lock) { record.bindFinished = true; record.parent.bindingFinished() }
            if (record.retired) queueUnbind(record)
        }
    }
    private fun connection(record: Cohort) = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            val work = synchronized(lock) { record.parent.reserve() } ?: return
            try {
                synchronized(lock) {
                    if (record.endpoints.containsKey(service)) return
                    record.endpoints[service] = false
                }
                try { service.linkToDeath({ died(record, service) }, 0) }
                catch (_: DeadObjectException) { died(record, service); return }
                catch (error: Throwable) { abort(record, "vision_link_death_failed", error); return }
                try { execute(record, ipc) { authenticate(record, service) } }
                catch (error: Throwable) { abort(record, "vision_control_lane_rejected", error) }
            } finally { work.finish(); confirmRelease(record) }
        }
        override fun onServiceDisconnected(name: ComponentName?) { unexpected(record, "vision_service_disconnected") }
        override fun onNullBinding(name: ComponentName?) { abort(record, "vision_null_binding") }
        override fun onBindingDied(name: ComponentName?) { unexpected(record, "vision_binding_died") }
    }
    private fun authenticate(record: Cohort, service: IBinder) {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            synchronized(lock) {
                if (record.retired) return // Empty late endpoints must not steal a new cohort's HELLO.
                // A reconnect never replaces the immutable authenticated endpoint.
                require(record.binder == null || record.binder === service) { "vision_unexpected_reconnect" }
                record.binder = service
            }
            header(data, record, record.lease.ownerEpoch, 0, record.bindDeadline)
            data.writeString(record.lease.ownerId); data.writeStrongBinder(callback(record))
            require(service.transact(VisionRuntimeProtocol.HELLO, data, reply, 0)); reply.readException()
            require(reply.readInt() == VisionRuntimeProtocol.VERSION && reply.readString() == record.nonce)
            val pid = reply.readInt(); val uid = reply.readInt(); val name = reply.readString()
            val ticks = reply.readLong(); val boot = requireNotNull(reply.readString())
            require(pid > 0 && pid != Process.myPid() && uid == Process.myUid() &&
                name == context.packageName + VisionRuntimeProtocol.PROCESS_SUFFIX && reply.dataAvail() == 0)
            val process = DeviceCheckNativeProcess(pid, uid, requireNotNull(name), ticks, boot)
            require(VisionProcessIdentity.captureVerified(context, process) != null) { "vision_process_identity_mismatch" }
            synchronized(lock) { record.binder = service; record.pid = pid; record.process = process; record.verified = true }
            if (record.retired || now() >= record.bindDeadline) abort(record, "vision_handshake_retired")
            else { record.handshakeWatch?.cancel(false); record.ready.complete(service) }
        } catch (error: Throwable) { abort(record, "vision_handshake_failed", error) }
        finally { data.recycle(); reply.recycle() }
    }
    private fun callback(record: Cohort) = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != VisionRuntimeProtocol.RESULT) return super.onTransact(code, data, reply, flags)
            val work = synchronized(lock) { record.parent.reserve() }
            if (work == null) { reply?.writeNoException(); return true }
            try {
            require(data.dataSize() <= VisionRuntimeProtocol.MAX_CONTROL_BYTES && Binder.getCallingUid() == Process.myUid())
            require(Binder.getCallingPid() == record.pid && record.verified)
            data.enforceInterface(VisionRuntimeProtocol.CALLBACK)
            require(data.readInt() == VisionRuntimeProtocol.VERSION && data.readString() == record.nonce)
            val epoch = data.readLong(); val id = data.readLong(); val success = data.readInt() == 1
            val payload = requireNotNull(data.readString()); require(data.dataAvail() == 0)
            val pending = record.pending[id]
            if (pending != null && pending.epoch == epoch && !record.retired && now() < pending.deadline) {
                if (success) pending.result.complete(payload)
                else pending.result.completeExceptionally(failure(record, JSONObject(payload).optString("reason", "vision_native_failed")))
            }
            reply!!.writeNoException(); return true
            } finally { work.finish(); confirmRelease(record) }
        }
    }
    private fun rpc(record: Cohort, lease: VisionCohortLease, code: Int, callerDeadline: Long,
        maximum: Long, native: DeviceCheckNativePermit? = null, write: Parcel.() -> Unit = {}): String {
        val dispatched = AtomicBoolean(false)
        try {
            val deadline = VisionRuntimeProtocol.deadline(callerDeadline, now(), maximum)
            val service = await(record.ready, minOf(deadline, record.bindDeadline))
            val id = ids.incrementAndGet(); val pending = Pending(lease.ownerEpoch, deadline)
            synchronized(lock) {
                checkCurrent(record, lease); record.pending[id] = pending
                if (code == VisionRuntimeProtocol.INVOKE) record.fdTransports++
            }
            var submitted = false
            try { execute(record, ipc) {
                val data = Parcel.obtain(); val reply = Parcel.obtain()
                try {
                    header(data, record, lease.ownerEpoch, id, deadline); data.write()
                    require(data.dataSize() <= VisionRuntimeProtocol.MAX_CONTROL_BYTES)
                    // No external authority callback runs under the client metadata lock.
                    if (native != null && !record.journal.consumeNativeOpen(native, lease, now()))
                        throw VisionStaleAuthorityException()
                    synchronized(lock) {
                        checkCurrent(record, lease)
                        check(now() < deadline && record.pending[id] === pending)
                        check(record.authority.accepts(lease.ownerEpoch))
                        if (native != null) check(record.authority.grantOpen(lease.ownerEpoch)) // Before INIT transport.
                        dispatched.set(true)
                    }
                    require(service.transact(code, data, reply, 0)); reply.readException()
                } catch (error: Throwable) { pending.result.completeExceptionally(error) }
                finally {
                    data.recycle(); reply.recycle()
                    if (code == VisionRuntimeProtocol.INVOKE) synchronized(lock) { record.fdTransports-- }
                    pending.transportFinished.complete(Unit)
                }
            }
              submitted = true
              val result = await(pending.result, deadline)
              await(pending.transportFinished, deadline)
              return result
            } finally {
                record.pending.remove(id, pending)
                if (!submitted) {
                    if (code == VisionRuntimeProtocol.INVOKE) synchronized(lock) { record.fdTransports-- }
                    pending.transportFinished.complete(Unit)
                    confirmRelease(record)
                }
            }
        } catch (error: Throwable) {
            if (error is VisionStaleAuthorityException) throw error
            val cancellation = error is InterruptedException || error is java.util.concurrent.CancellationException
            if (dispatched.get() && code in setOf(VisionRuntimeProtocol.OPEN, VisionRuntimeProtocol.INVOKE, VisionRuntimeProtocol.CLOSE) && !cancellation)
                markNativeFault(record, lease, "native_operation_${code}_${error.javaClass.simpleName}")
            abort(record, "vision_operation_failed", error, lease, expectedTermination = cancellation)
            throw failure(record, "vision_operation_failed", error)
        }
    }
    private fun abort(record: Cohort, reason: String, cause: Throwable? = null, expectedLease: VisionCohortLease? = null,
        expectedTermination: Boolean = false) {
        var exit: VisionParentReleaseTracker.Work? = null
        val first = synchronized(lock) {
          if (expectedLease != null && record.lease !== expectedLease) return
          if (record.releaseClaimed) return
          // Native/unexpected faults are classified before abort. Our own ensuing EXIT is expected.
          if (record.fault == null) record.expectedRetirement = true
          if (!record.exitQueued) {
              record.exitQueued = true; exit = requireNotNull(record.parent.reserve())
          }
          if (record.retired) false else {
            record.authority.retire(record.lease.ownerEpoch); record.retired = true; true
          }
        }
        if (first) {
            record.handshakeWatch?.cancel(false)
            val error = failure(record, reason, cause)
            record.ready.completeExceptionally(error); record.pending.values.forEach { it.result.completeExceptionally(error) }
            record.retirementSignal.complete(Unit)
        }
        // Reserve EXIT before publishing retirement callbacks: reentry cannot release ahead of it.
        exit?.let { work -> try { executeReserved(record, exiting, work) {
            try {
                val service = synchronized(lock) { record.binder.takeIf { record.verified } }
                if (service != null) {
                    val data = Parcel.obtain()
                    try {
                        header(data, record, record.lease.ownerEpoch, 0, now() + VisionRuntimeProtocol.DEATH_MS)
                        service.transact(VisionRuntimeProtocol.EXIT, data, null, IBinder.FLAG_ONEWAY)
                    } catch (_: Throwable) { /* Sending EXIT is not physical-release evidence. */ }
                    finally { data.recycle() }
                }
            } finally { queueUnbind(record) }
        } } catch (_: Throwable) {
            // The independent child operation/park watchdog remains authoritative; no new thread is spawned.
            queueUnbind(record)
        } }
        confirmRelease(record)
    }
    private fun queueUnbind(record: Cohort) {
        val submit = synchronized(lock) {
            if (record.releaseClaimed || !record.bindFinished || record.unbindClaimed || record.unbindQueued) false
            else { record.unbindQueued = true; true }
        }
        if (submit) try { execute(record, unbinding) { unbind(record) } }
        catch (_: Throwable) { synchronized(lock) { record.unbindQueued = false } }
        confirmRelease(record)
    }
    private fun unbind(record: Cohort) {
        val perform = synchronized(lock) {
            record.unbindQueued = false
            if (!record.bindFinished || record.unbindClaimed) false
            else { record.unbindClaimed = true; true }
        }
        if (perform) {
            try {
                if (record.bindEntered) {
                    context.unbindService(record.connection)
                    synchronized(lock) { record.unbound = true; record.parent.unbindingSucceeded() }
                }
            } catch (_: Throwable) { /* An attempted bind requires a positive SDK unbind ACK, even without INIT. */ }
        }
        // A previously rejected cleanup is retried only when a bounded lane actually makes progress.
        val retry = synchronized(lock) { cleanupPending.values.filter { it.retired && it.bindFinished && !it.unbindClaimed && !it.unbindQueued } }
        retry.forEach(::queueUnbind)
    }
    private fun died(record: Cohort, endpoint: IBinder) {
        val work = synchronized(lock) { record.parent.reserve() } ?: return
        try {
            val observed = synchronized(lock) {
                record.endpoints[endpoint] = true
                record.lease.let { it to reserveNativeFault(record, it, "vision_child_died") }
            }
            observed.second?.let { applyNativeFault(record, it) }
            abort(record, "vision_child_died", expectedLease = observed.first)
        } finally { work.finish(); confirmRelease(record) }
    }
    private fun unexpected(record: Cohort, reason: String) {
        val work = synchronized(lock) { record.parent.reserve() } ?: return
        try {
            val observed = synchronized(lock) {
                record.lease.let { it to reserveNativeFault(record, it, reason) }
            }
            observed.second?.let { applyNativeFault(record, it) }
            abort(record, reason, expectedLease = observed.first)
        } finally { work.finish(); confirmRelease(record) }
    }
    private fun markNativeFault(record: Cohort, lease: VisionCohortLease, reason: String, allowParked: Boolean = false) {
        val fault = synchronized(lock) { reserveNativeFault(record, lease, reason, allowParked) } ?: return
        applyNativeFault(record, fault)
    }
    /** Called under the metadata lock, atomically with the event that first observed the fault. */
    private fun reserveNativeFault(record: Cohort, lease: VisionCohortLease, reason: String,
        allowParked: Boolean = false): NativeFault? {
        if (active !== record || record.lease !== lease || record.releaseClaimed || record.expectedRetirement ||
            (record.parked && !allowParked) || !record.authority.nativeStartPossible()) return null
        return record.fault ?: NativeFault(record.owner, lease, reason.take(120)).also {
            record.fault = it
            // The external metadata hook can wait. No peer may grant another OPEN in that gap.
            record.authority.retire(lease.ownerEpoch)
        }
    }
    private fun applyNativeFault(record: Cohort, fault: NativeFault) {
        if (!fault.applying.compareAndSet(false, true)) return
        try {
            if (kr.co.hanium.dreamup.walksafe.BuildConfig.DEBUG) debugBeforeFaultMetadata?.invoke(fault.lease)
            record.journal.retireRuntime(fault.owner, fault.lease.cohortId, fault.lease, fault.reason)
        }
        catch (_: Throwable) { runCatching { record.journal.publicationUnconfirmed(java.util.UUID.randomUUID().toString()) } }
        finally { synchronized(lock) { fault.metadataApplied = true }; confirmRelease(record) }
    }
    private fun releaseMemory(record: Cohort, model: Model, memory: VisionSharedMemory) {
        try { memory.close(); synchronized(lock) { model.resources.remove(memory) } }
        catch (_: Throwable) { abort(record, "vision_parent_mapping_release_unconfirmed") }
    }
    private fun confirmRelease(record: Cohort) {
        val publication = synchronized(lock) {
            if (record.releaseClaimed || record.fault?.metadataApplied == false) return
            val dead = record.endpoints.isNotEmpty() && record.endpoints.values.all { it }
            val neverStarted = record.authority.provesNoNativeStart()
            if (!record.retired || record.fdTransports != 0 || record.models.values.any { it.resources.isNotEmpty() } ||
                !record.parent.claimRelease(neverStarted || dead)) return
            val receipt = VisionReleaseReceipt(record.lease.cohortId, if (neverStarted) 0 else record.pid,
                if (neverStarted) VisionReleaseProof.NO_NATIVE_START else VisionReleaseProof.CHILD_DEATH)
            val models = record.models.values.toList()
            record.releaseClaimed = true
            record.physicalReceipt = receipt
            models.forEach { model -> model.handle?.let { retainModelReceipt(it.modelInstanceId) } }
            record.models.clear()
            receipt to models.mapNotNull { model -> model.handle?.let {
                model.released to VisionModelReleaseReceipt(it.cohortId, it.modelInstanceId, record.pid, true)
            } }
        }
        record.released.complete(publication.first)
        publication.second.forEach { (future, receipt) -> future.complete(receipt) }
        settleJournalCleanup(record)
    }

    private fun retryJournalCleanup() {
        val pending = synchronized(lock) { cleanupPending.values.filter { it.releaseClaimed && !it.available.isDone } }
        pending.forEach(::settleJournalCleanup)
    }

    /** Cleanup can use the captured, revoked capability. It can never grant another native OPEN. */
    private fun settleJournalCleanup(record: Cohort) {
        val cleanup = synchronized(lock) {
            val receipt = record.physicalReceipt ?: return
            if (record.availabilityClaimed || record.journalCleanupRunning || record.openIntents.any { !it.invocationFinished }) return
            record.journalCleanupRunning = true
            receipt to record.openIntents.toList()
        }
        val receipt = cleanup.first
        val proof = if (receipt.releaseProof == VisionReleaseProof.NO_NATIVE_START) DeviceCheckNativeReleaseProof.NO_NATIVE_START
            else DeviceCheckNativeReleaseProof.CHILD_DEATH
        val uncertain = cleanup.second.firstOrNull { it.outcome == null || it.outcome is DeviceCheckAuthorityOutcome.Indeterminate }
        val writes = mutableListOf<CompletableFuture<Boolean>>()
        if (kr.co.hanium.dreamup.walksafe.BuildConfig.DEBUG) debugBeforeJournalCleanup?.invoke(record.lease)
        if (cleanup.second.any { it.outcome is DeviceCheckAuthorityOutcome.Committed }) {
            writes += cleanupWrite(record) { record.journal.recordCohortReleased(receipt.cohortId, receipt.childPid, proof) }
        } else if (uncertain != null) {
            val intent = uncertain
            writes += cleanupWrite(record) {
                record.journal.settleNativeOpenRelease(intent.source, intent.owner, nativeLease(intent.lease), intent.lease,
                    intent.command, intent.spec, intent.process, proof)
            }
        }
        CompletableFuture.allOf(*writes.toTypedArray()).whenComplete { _, error ->
            val confirmed = error == null && writes.all { it.getNow(false) }
            if (confirmed) publishAvailable(record, receipt)
            else synchronized(lock) { record.journalCleanupRunning = false }
        }
    }

    /** At most one settled-I/O reconciliation per cleanup attempt; unknown writes retain admission. */
    private fun cleanupWrite(record: Cohort,
        write: () -> CompletionStage<out DeviceCheckAuthorityOutcome<*>>): CompletableFuture<Boolean> {
        val complete = CompletableFuture<Boolean>()
        fun submit(reconciled: Boolean) {
            val stage = try { write() } catch (_: Throwable) { complete.complete(false); return }
            stage.whenComplete { outcome, error ->
                if (error == null && outcome is DeviceCheckAuthorityOutcome.Committed) complete.complete(true)
                else if (!reconciled && outcome is DeviceCheckAuthorityOutcome.Indeterminate) {
                    val recovery = try { record.journal.reconcileAfterSettled(outcome.operationId) }
                    catch (_: Throwable) { complete.complete(false); return@whenComplete }
                    recovery.whenComplete { _, recoveryError ->
                        if (recoveryError == null) submit(true) else complete.complete(false)
                    }
                } else complete.complete(false)
            }
        }
        submit(false)
        return complete
    }

    private fun publishAvailable(record: Cohort, receipt: VisionReleaseReceipt) {
        val waiting = synchronized(lock) {
            if (record.availabilityClaimed || record.physicalReceipt !== receipt) return
            record.availabilityClaimed = true; record.journalCleanupRunning = false
            if (active === record) active = null
            records.remove(record.lease.cohortId)
            cleanupPending.remove(record.lease.cohortId)
            record.openIntents.clear()
            completedCohorts[record.lease.cohortId] = record.released
            while (completedCohorts.size > MAX_COHORT_RECEIPTS) completedCohorts.remove(completedCohorts.keys.first())
            if (cleanupPending.size < MAX_PENDING_BINDINGS) cleanupAvailability.also { cleanupAvailability = null } else null
        }
        record.available.complete(receipt)
        waiting?.complete(receipt)
    }

    /** Reserve before enqueue. Cancellation/timeouts never drain an accepted Runnable. */
    private fun execute(record: Cohort, executor: ThreadPoolExecutor, action: () -> Unit) {
        val work = synchronized(lock) { record.parent.reserve() } ?: throw VisionStaleAuthorityException()
        executeReserved(record, executor, work, action)
    }
    private fun executeReserved(record: Cohort, executor: ThreadPoolExecutor,
        work: VisionParentReleaseTracker.Work, action: () -> Unit) {
        try { executor.execute {
            try { action() }
            finally { work.finish(); confirmRelease(record) }
        } } catch (error: Throwable) {
            // AbortPolicy positively rejects this wrapper; an unknown submit failure proves nothing.
            if (error is java.util.concurrent.RejectedExecutionException) { work.finish(); confirmRelease(record) }
            throw error
        }
    }
    /** Called only under the metadata lock; already returned futures survive LRU eviction. */
    private fun retainModelReceipt(id: String) {
        completedModelIds.addLast(id)
        while (completedModelIds.size > MAX_MODEL_RECEIPTS) modelReceipts.remove(completedModelIds.removeFirst())
    }
    /** Metadata lock only. Opaque refs use exact identity; one ref retains only its latest epoch. */
    private fun rememberOwnerRetirement(record: Cohort) {
        ownerRetirements.remove(record.owner)
        ownerRetirements[record.owner] = VisionRetirementSnapshot(record.lease, record.released, record.available)
        while (ownerRetirements.size > MAX_OWNER_RETIREMENTS) ownerRetirements.remove(ownerRetirements.keys.first())
    }
    private fun checkedRuntime(record: Cohort, spec: VisionModelSpec, json: String): String {
        VisionRuntimeCodec.validateRuntimeJson(json)
        val actual = VisionRuntimeCodec.objectJson(json); val requested = VisionRuntimeCodec.decodeOptions(spec.runtimeJson)
        require(actual.getInt("modelOwnerPid") == record.pid && actual.getString("requestedDelegate") == requested.delegate &&
            actual.getString("activeDelegate") == requested.delegate && actual.getInt("numThreads") == requested.numThreads &&
            (requested.delegate != "gpu" || actual.getBoolean("gpuPrecisionLossAllowed") == requested.gpuPrecisionLossAllowed))
        return json
    }
    private fun requireLease(lease: VisionCohortLease): Cohort = synchronized(lock) {
        requireNotNull(records[lease.cohortId]).also { checkCurrent(it, lease) }
    }
    private fun requireHandle(handle: VisionModelHandle): Triple<Cohort, Model, VisionCohortLease> = synchronized(lock) {
        val record = requireNotNull(records[handle.cohortId]); checkCurrent(record, record.lease)
        val model = requireNotNull(record.models[handle.role to handle.modelKey])
        checkHandle(record, model, record.lease, handle)
        Triple(record, model, record.lease)
    }
    private fun checkHandle(record: Cohort, model: Model, lease: VisionCohortLease, handle: VisionModelHandle) {
        checkCurrent(record, lease)
        if (model.handle !== handle || handle.ownerEpoch != lease.ownerEpoch || record.models[handle.role to handle.modelKey] !== model)
            throw VisionStaleAuthorityException()
    }
    private fun checkCurrent(record: Cohort, lease: VisionCohortLease) {
        if (active !== record || record.retired || record.fault != null || record.lease !== lease)
            throw VisionStaleAuthorityException()
    }
    private fun header(data: Parcel, record: Cohort, epoch: Long, id: Long, deadline: Long) {
        data.writeInterfaceToken(VisionRuntimeProtocol.DESCRIPTOR); data.writeInt(VisionRuntimeProtocol.VERSION)
        data.writeString(record.nonce); data.writeLong(epoch); data.writeLong(id); data.writeLong(deadline)
    }
    private fun <T> await(future: CompletableFuture<T>, deadline: Long): T = try {
        future.get((deadline - now()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
    } catch (error: java.util.concurrent.ExecutionException) { throw error.cause ?: error }
    private fun <T> onAdmission(record: Cohort, deadline: Long, operation: () -> T): T {
        val future = CompletableFuture<T>()
        execute(record, admission) {
            try { check(now() < deadline); future.complete(operation()) }
            catch (error: Throwable) { future.completeExceptionally(error) }
        }
        await(CompletableFuture.anyOf(future, record.retirementSignal), deadline)
        if (record.retirementSignal.isDone) throw VisionStaleAuthorityException()
        return await(future, deadline)
    }
    private fun <T> awaitAdmission(record: Cohort, future: CompletableFuture<T>, deadline: Long): T {
        // Stop this parent waiter on retirement without cancelling the original durable writer.
        await(CompletableFuture.anyOf(future, record.retirementSignal), deadline)
        if (record.retirementSignal.isDone) throw VisionStaleAuthorityException()
        return await(future, deadline)
    }
    private fun <T> committed(outcome: DeviceCheckAuthorityOutcome<T>): T = when (outcome) {
        is DeviceCheckAuthorityOutcome.Committed -> outcome.value
        else -> throw VisionJournalException(outcome)
    }
    private fun nativeLease(lease: VisionCohortLease) = DeviceCheckNativeLease(lease.cohortId, lease.ownerId, lease.ownerEpoch)
    private fun nativeSpec(spec: VisionModelSpec): DeviceCheckNativeSpec {
        val options = VisionRuntimeCodec.decodeOptions(spec.runtimeJson)
        return DeviceCheckNativeSpec(spec.role.name, spec.modelKey, spec.assetSha256, options.delegate,
            options.numThreads, VisionRuntimeCodec.optionsHash(spec.runtimeJson))
    }
    private fun requireWorker() { check(Looper.myLooper() != Looper.getMainLooper()) { "vision_blocking_call_on_main" } }
    private fun failure(record: Cohort, reason: String, cause: Throwable? = null) =
        VisionRuntimeException(reason, cause, record.released, record.retired)
    private fun requireEnvironment() {
        require(Build.VERSION.SDK_INT >= 31) { "vision_android_12_required" }
        require(Application.getProcessName() == context.applicationInfo.processName) { "vision_client_main_process_only" }
    }
    private fun now() = SystemClock.elapsedRealtime()
    private fun randomToken() = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_COHORT_RECEIPTS = 16
        private const val MAX_OWNER_RETIREMENTS = 16
        private const val MAX_MODEL_RECEIPTS = 32
        private const val MAX_PENDING_BINDINGS = 4
        private fun lane(name: String, threads: Int, queued: Int) = ThreadPoolExecutor(threads, threads, 0L,
            TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(queued),
            java.util.concurrent.ThreadFactory { Thread(it, name).apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
        @Volatile private var instance: RemoteVisionClient? = null
        /** Cleanup-only: no new client, bind, OPEN, attempt or historical PASS is created. */
        @JvmStatic fun recheckPendingRelease() { instance?.retryJournalCleanup() }
        /** Exercises the actual client queues/cleanup against controlled Android binding outcomes. */
        internal fun forBindingTests(context: Context): RemoteVisionClient {
            check(kr.co.hanium.dreamup.walksafe.BuildConfig.DEBUG)
            return RemoteVisionClient(context)
        }
        @JvmStatic fun forMainProcess(context: Context): RemoteVisionClient = synchronized(this) {
            require(Build.VERSION.SDK_INT >= 31 && Application.getProcessName() == context.applicationInfo.processName)
            instance ?: RemoteVisionClient(context.applicationContext).also { instance = it }
        }
    }
}

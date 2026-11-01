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
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/** Process-scoped owner. Public lease/handle copies cannot grant admission or retire another epoch. */
class RemoteVisionClient private constructor(private val context: Context) {
    private val lock = Any()
    private val ipc = Executors.newCachedThreadPool { Thread(it, "VisionParentControl").apply { isDaemon = true } }
    private val watch = ScheduledThreadPoolExecutor(1) { Thread(it, "VisionParentWatchdog").apply { isDaemon = true } }.apply { removeOnCancelPolicy = true }
    private val records = LinkedHashMap<String, Cohort>()
    private var active: Cohort? = null
    private val ids = AtomicLong()
    @Volatile internal var debugBeforeHandleDispatch: ((VisionModelHandle) -> Unit)? = null
    @Volatile internal var debugBeforeExitRetirement: ((VisionCohortLease) -> Unit)? = null
    private class Pending(val epoch: Long, val deadline: Long) { val result = CompletableFuture<String>() }
    private class Model(val spec: VisionModelSpec) {
        var handle: VisionModelHandle? = null
        val operation = ReentrantLock()
        val resources = mutableSetOf<VisionSharedMemory>()
        val released = CompletableFuture<VisionModelReleaseReceipt>()
    }
    private class Cohort(@Volatile var lease: VisionCohortLease, val nonce: String, val bindDeadline: Long) {
        val ready = CompletableFuture<IBinder>()
        val released = CompletableFuture<VisionReleaseReceipt>()
        val pending = ConcurrentHashMap<Long, Pending>()
        val models = LinkedHashMap<Pair<VisionRole, String>, Model>()
        val roleFlights = VisionRole.entries.associateWith { ReentrantLock() }
        val endpoints = LinkedHashMap<IBinder, Boolean>()
        var binder: IBinder? = null
        @Volatile var pid = -1
        @Volatile var verified = false
        var bindEntered = false
        var bindFinished = false
        var unbindClaimed = false
        var unbound = false
        val authority = VisionDispatchAuthority()
        @Volatile var retired = false
        @Volatile var parked = false
        var awaitingAdoption = false
        lateinit var connection: ServiceConnection
    }

    /** Returns the stable receipt owner before binding/INIT can run. OPEN awaits authentication. */
    fun acquire(ownerId: String, deadlineElapsedMs: Long): VisionCohortLease {
        requireEnvironment()
        require(VisionRuntimeProtocol.KEY.matches(ownerId))
        val deadline = VisionRuntimeProtocol.deadline(deadlineElapsedMs, now(), VisionRuntimeProtocol.HANDSHAKE_MS)
        val record = synchronized(lock) {
            active?.let {
                if (!it.retired && it.lease.ownerId == ownerId && !it.parked) return it.lease
                throw VisionCohortBusyException(it.released)
            }
            val value = Cohort(VisionCohortLease(randomToken(), ownerId, 1), randomToken(), deadline)
            value.connection = connection(value)
            records[value.lease.cohortId] = value; active = value
            value
        }
        try {
            watch.schedule({ if (!record.ready.isDone) abort(record, "vision_handshake_timeout") },
                (deadline - now()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
            ipc.execute { bind(record) }
        } catch (error: Throwable) { abort(record, "vision_startup_rejected", error) }
        return record.lease
    }

    fun open(lease: VisionCohortLease, spec: VisionModelSpec, deadlineElapsedMs: Long): VisionModelHandle {
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
            val payload = rpc(record, lease, VisionRuntimeProtocol.OPEN, deadlineElapsedMs, VisionRuntimeProtocol.OPEN_MS, native = true) {
                VisionRuntimeProtocol.writeSpec(this, immutable)
            }
            val body = JSONObject(payload)
            val runtime = checkedRuntime(record, immutable, body.getString("runtimeJson"))
            val handle = VisionModelHandle(lease.cohortId, body.getString("modelInstanceId"), lease.ownerEpoch,
                spec.role, spec.modelKey, runtime)
            synchronized(lock) {
                checkCurrent(record, lease); model.handle = handle
                modelReceipts[handle.modelInstanceId] = model.released
            }
            return handle
        } catch (error: Throwable) {
            if (error is VisionStaleAuthorityException) throw error
            abort(record, "vision_open_failed", error, lease); throw failure(record, "vision_open_failed", error)
        }
    }

    fun invoke(handle: VisionModelHandle, input: ByteBuffer, outputs: Array<ByteBuffer>, width: Int,
        height: Int, purpose: VisionPurpose, deadlineElapsedMs: Long): VisionInvokeResult {
        val (record, model, lease) = requireHandle(handle)
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
            val body = JSONObject(payload)
            val result = VisionInvokeResult(checkedRuntime(record, model.spec, body.getString("runtimeJson")),
                body.getDouble("preprocessingMs"), body.getDouble("inferenceMs"), body.getDouble("outputReadMs"))
            require(listOf(result.preprocessingMs, result.inferenceMs, result.outputReadMs).all { it.isFinite() && it >= 0 })
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
            synchronized(lock) {
                checkHandle(record, model, lease, handle); require(model.resources.isEmpty())
                record.models.remove(handle.role to handle.modelKey)
                model.released.complete(VisionModelReleaseReceipt(record.lease.cohortId, handle.modelInstanceId, record.pid, false))
            }
            return VisionCloseResult(handle.modelInstanceId, true)
        } catch (error: Throwable) {
            if (error is VisionStaleAuthorityException) throw error
            abort(record, "vision_close_failed", error, lease); throw failure(record, "vision_close_failed", error)
        }
        finally { model.operation.unlock() }
    }

    fun park(lease: VisionCohortLease, deadlineElapsedMs: Long) {
        val record = requireLease(lease)
        synchronized(lock) { checkCurrent(record, lease); check(!record.parked); record.parked = true }
        rpc(record, lease, VisionRuntimeProtocol.PARK, deadlineElapsedMs, VisionRuntimeProtocol.CLOSE_MS)
    }
    fun transfer(lease: VisionCohortLease, newOwnerId: String, deadlineElapsedMs: Long): VisionTransferResult {
        require(VisionRuntimeProtocol.KEY.matches(newOwnerId))
        val record = requireLease(lease)
        synchronized(lock) { checkCurrent(record, lease); require(record.parked && record.models.values.all { it.resources.isEmpty() }) }
        var recoveryLease = lease
        try {
          val body = JSONObject(rpc(record, lease, VisionRuntimeProtocol.TRANSFER, deadlineElapsedMs, VisionRuntimeProtocol.CLOSE_MS) {
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
            recoveryLease = record.lease
            replacements.forEach { (handle, model) -> model.handle = handle }
            VisionTransferResult(record.lease, replacements.map { it.first })
          }
          synchronized(lock) { record.awaitingAdoption = true }
          return result
        } catch (error: Throwable) {
            if (error is VisionStaleAuthorityException) throw error
            abort(record, "vision_transfer_failed", error, recoveryLease)
            throw failure(record, "vision_transfer_failed", error)
        }
    }

    /** Call only after both Java wrappers/consumer registry adopt; transfer itself keeps admission closed. */
    fun confirmAdoption(lease: VisionCohortLease, deadlineElapsedMs: Long) {
        val record = requireLease(lease)
        synchronized(lock) { checkCurrent(record, lease); require(record.parked && record.awaitingAdoption) }
        rpc(record, lease, VisionRuntimeProtocol.ADOPT, deadlineElapsedMs, VisionRuntimeProtocol.DEATH_MS)
        synchronized(lock) { checkCurrent(record, lease); record.awaitingAdoption = false; record.parked = false }
    }

    /** Never blocks a UI/consumer lane and never lets an old lease stop its transferred cohort. */
    fun requestExit(lease: VisionCohortLease, reason: String) {
        val record = synchronized(lock) { records[lease.cohortId]?.takeIf { it.lease === lease } } ?: return
        if (kr.co.hanium.dreamup.walksafe.BuildConfig.DEBUG) debugBeforeExitRetirement?.invoke(lease)
        abort(record, reason.take(160), expectedLease = lease)
    }
    fun actualReleasedFuture(cohortId: String): CompletionStage<VisionReleaseReceipt> = synchronized(lock) {
        requireNotNull(records[cohortId]) { "vision_unknown_cohort" }.released
    }
    /** Snapshot only: no bind/OPEN and no authority to read another owner's current lease. */
    fun peekLease(ownerId: String): VisionCohortLease? = synchronized(lock) {
        active?.takeIf { !it.retired && it.lease.ownerId == ownerId }?.lease
    }
    /** Capture once after consumer drain. A retired-but-unreleased cohort still owns its receipt. */
    fun retirementSnapshot(ownerId: String): VisionRetirementSnapshot? = synchronized(lock) {
        active?.takeIf { it.lease.ownerId == ownerId }?.let { VisionRetirementSnapshot(it.lease, it.released) }
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
    fun modelReleasedFuture(modelInstanceId: String): CompletionStage<VisionModelReleaseReceipt> = synchronized(lock) {
        requireNotNull(modelReceipts[modelInstanceId]) { "vision_unknown_model" }
    }
    private val modelReceipts = LinkedHashMap<String, CompletableFuture<VisionModelReleaseReceipt>>()

    /** The service independently enforces DEBUG; this API cannot enable faults in a release APK. */
    fun injectDebugFault(lease: VisionCohortLease, value: String, deadlineElapsedMs: Long) {
        check(kr.co.hanium.dreamup.walksafe.BuildConfig.DEBUG)
        rpc(requireLease(lease), lease, VisionRuntimeProtocol.FAULT, deadlineElapsedMs, 5_000) { writeString(value) }
    }

    private fun bind(record: Cohort) {
        try {
            synchronized(lock) { if (record.retired) return; record.bindEntered = true }
            context.bindService(Intent(context, VisionRuntimeService::class.java), record.connection, Context.BIND_AUTO_CREATE)
                .also { if (!it) abort(record, "vision_bind_false") }
        } catch (error: Throwable) { abort(record, "vision_bind_exception", error) }
        finally {
            synchronized(lock) { record.bindFinished = true }
            if (record.retired) unbind(record)
        }
    }
    private fun connection(record: Cohort) = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            synchronized(lock) {
                if (record.endpoints.containsKey(service)) return
                record.endpoints[service] = false
            }
            try {
                service.linkToDeath({ died(record, service) }, 0)
            } catch (_: DeadObjectException) { died(record, service); return }
            catch (error: Throwable) { abort(record, "vision_link_death_failed", error); return }
            ipc.execute { authenticate(record, service) }
        }
        override fun onServiceDisconnected(name: ComponentName?) { abort(record, "vision_service_disconnected") }
        override fun onNullBinding(name: ComponentName?) { abort(record, "vision_null_binding") }
        override fun onBindingDied(name: ComponentName?) { abort(record, "vision_binding_died") }
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
            require(pid > 0 && pid != Process.myPid() && uid == Process.myUid() &&
                name == context.packageName + VisionRuntimeProtocol.PROCESS_SUFFIX && reply.dataAvail() == 0)
            synchronized(lock) { record.binder = service; record.pid = pid; record.verified = true }
            if (record.retired || now() >= record.bindDeadline) abort(record, "vision_handshake_retired")
            else record.ready.complete(service)
        } catch (error: Throwable) { abort(record, "vision_handshake_failed", error) }
        finally { data.recycle(); reply.recycle() }
    }
    private fun callback(record: Cohort) = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != VisionRuntimeProtocol.RESULT) return super.onTransact(code, data, reply, flags)
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
        }
    }
    private fun rpc(record: Cohort, lease: VisionCohortLease, code: Int, callerDeadline: Long,
        maximum: Long, native: Boolean = false, write: Parcel.() -> Unit = {}): String {
        try {
            val deadline = VisionRuntimeProtocol.deadline(callerDeadline, now(), maximum)
            val service = await(record.ready, minOf(deadline, record.bindDeadline))
            val id = ids.incrementAndGet(); val pending = Pending(lease.ownerEpoch, deadline)
            synchronized(lock) { checkCurrent(record, lease); record.pending[id] = pending }
            ipc.execute {
                val data = Parcel.obtain(); val reply = Parcel.obtain()
                try {
                    header(data, record, lease.ownerEpoch, id, deadline); data.write()
                    require(data.dataSize() <= VisionRuntimeProtocol.MAX_CONTROL_BYTES)
                    synchronized(lock) {
                        checkCurrent(record, lease)
                        check(record.authority.accepts(lease.ownerEpoch))
                        if (native) check(record.authority.grantOpen(lease.ownerEpoch)) // Before INIT transport.
                    }
                    require(service.transact(code, data, reply, 0)); reply.readException()
                } catch (error: Throwable) { pending.result.completeExceptionally(error) }
                finally { data.recycle(); reply.recycle() }
            }
            try { return await(pending.result, deadline) } finally { record.pending.remove(id, pending) }
        } catch (error: Throwable) {
            if (error is VisionStaleAuthorityException) throw error
            abort(record, "vision_operation_failed", error, lease); throw failure(record, "vision_operation_failed", error)
        }
    }
    private fun abort(record: Cohort, reason: String, cause: Throwable? = null, expectedLease: VisionCohortLease? = null) {
        val first = synchronized(lock) {
          if (expectedLease != null && record.lease !== expectedLease) return
          if (record.retired) false else {
            record.authority.retire(record.lease.ownerEpoch); record.retired = true; true
          }
        }
        if (first) {
            val error = failure(record, reason, cause)
            record.ready.completeExceptionally(error); record.pending.values.forEach { it.result.completeExceptionally(error) }
        }
        ipc.execute {
            val service = synchronized(lock) { record.binder.takeIf { record.verified } }
            if (service != null) {
                val data = Parcel.obtain(); val reply = Parcel.obtain()
                try { header(data, record, record.lease.ownerEpoch, 0, now() + VisionRuntimeProtocol.DEATH_MS)
                    service.transact(VisionRuntimeProtocol.EXIT, data, reply, 0)
                } catch (_: Throwable) { /* Only death observation can confirm an initialized owner. */ }
                finally { data.recycle(); reply.recycle() }
            }
            unbind(record)
        }
    }
    private fun unbind(record: Cohort) {
        val perform = synchronized(lock) {
            if (!record.bindFinished || record.unbindClaimed) false
            else { record.unbindClaimed = true; true }
        }
        if (perform) {
            try {
                if (record.bindEntered) context.unbindService(record.connection)
                synchronized(lock) { record.unbound = true }
            } catch (_: Throwable) { /* After INIT an uncertain unbind retains the fence. */ }
        }
        confirmRelease(record)
    }
    private fun died(record: Cohort, endpoint: IBinder) {
        synchronized(lock) { record.endpoints[endpoint] = true }
        abort(record, "vision_child_died")
        confirmRelease(record)
    }
    private fun releaseMemory(record: Cohort, model: Model, memory: VisionSharedMemory) {
        try { memory.close(); synchronized(lock) { model.resources.remove(memory) } }
        catch (_: Throwable) { abort(record, "vision_parent_mapping_release_unconfirmed") }
    }
    private fun confirmRelease(record: Cohort) {
        synchronized(lock) {
            if (record.released.isDone) return
            val dead = record.endpoints.isNotEmpty() && record.endpoints.values.all { it }
            val neverStarted = record.authority.provesNoNativeStart()
            if (!record.retired || record.models.values.any { it.resources.isNotEmpty() } ||
                !neverStarted && (!record.unbound || !dead)) return
            val receipt = VisionReleaseReceipt(record.lease.cohortId, if (neverStarted) 0 else record.pid,
                if (neverStarted) VisionReleaseProof.NO_NATIVE_START else VisionReleaseProof.CHILD_DEATH)
            val models = record.models.values.toList()
            if (active === record) active = null
            record.models.clear()
            models.forEach { model -> model.handle?.let {
                model.released.complete(VisionModelReleaseReceipt(it.cohortId, it.modelInstanceId, record.pid, true))
            } }
            record.released.complete(receipt)
        }
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
        if (active !== record || record.retired || record.lease !== lease) throw VisionStaleAuthorityException()
    }
    private fun header(data: Parcel, record: Cohort, epoch: Long, id: Long, deadline: Long) {
        data.writeInterfaceToken(VisionRuntimeProtocol.DESCRIPTOR); data.writeInt(VisionRuntimeProtocol.VERSION)
        data.writeString(record.nonce); data.writeLong(epoch); data.writeLong(id); data.writeLong(deadline)
    }
    private fun <T> await(future: CompletableFuture<T>, deadline: Long): T =
        future.get((deadline - now()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
    private fun failure(record: Cohort, reason: String, cause: Throwable? = null) =
        VisionRuntimeException(reason, cause, record.released, record.retired)
    private fun requireEnvironment() {
        require(Build.VERSION.SDK_INT >= 31) { "vision_android_12_required" }
        require(Application.getProcessName() == context.applicationInfo.processName) { "vision_client_main_process_only" }
    }
    private fun now() = SystemClock.elapsedRealtime()
    private fun randomToken() = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    companion object {
        @Volatile private var instance: RemoteVisionClient? = null
        @JvmStatic fun forMainProcess(context: Context): RemoteVisionClient = synchronized(this) {
            require(Build.VERSION.SDK_INT >= 31 && Application.getProcessName() == context.applicationInfo.processName)
            instance ?: RemoteVisionClient(context.applicationContext).also { instance = it }
        }
    }
}

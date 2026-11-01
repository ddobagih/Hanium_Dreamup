package kr.co.hanium.dreamup.walksafe.runtime.metric

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionProcessIdentity
import kr.co.hanium.dreamup.walksafe.inference.composite.FixtureRuntimeConnectionLease
import kr.co.hanium.dreamup.walksafe.inference.composite.FixtureRuntimeEndpointRole
import kr.co.hanium.dreamup.walksafe.inference.composite.FixtureRuntimeReleaseProof
import java.security.SecureRandom
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** No ARCore Session is created here. Success also requires parent transport drain and journal ACK. */
internal class RemoteMetricSupportProbe internal constructor(
    context: Context,
    private val absoluteDeadlineMs: () -> Long? = { null },
    private val fault: Int = MetricSupportProtocol.FAULT_NONE,
    private val nativeBudgetMs: Long = MetricSupportProtocol.NATIVE_MS,
    private val ownerRef: () -> DeviceCheckOwnerRef? = { null },
    private val authority: DeviceCheckAuthority = AndroidDeviceCheckAuthority.forApp(context),
) : RuntimeMetricDepthSupportProbe {
    private val context = context.applicationContext
    private val lock = Any()
    private var active: MetricSupportRemoteAttempt? = null
    private var lastRelease = CompletableFuture.completedFuture(Unit)

    init {
        require(MetricSupportProtocol.validFault(fault, BuildConfig.DEBUG))
        require(nativeBudgetMs in 1L..MetricSupportProtocol.NATIVE_MS)
        require(BuildConfig.DEBUG || nativeBudgetMs == MetricSupportProtocol.NATIVE_MS)
        require(fault == MetricSupportProtocol.FAULT_NONE && nativeBudgetMs == MetricSupportProtocol.NATIVE_MS ||
            AndroidDeviceCheckAuthority.isDiagnostic(authority)) { "metric_faults_require_explicit_diagnostic_authority" }
    }
    override fun automaticDepthSupported(): Boolean = throw MetricSupportProbeException(MetricSupportFailure.ASYNC_REQUIRED)

    override fun query(onResult: (Result<Boolean>) -> Unit) {
        if (MetricSupportProtocol.processName(context) != context.applicationInfo.processName) {
            onResult(Result.failure(MetricSupportProbeException(MetricSupportFailure.WRONG_PARENT_PROCESS)))
            return
        }
        // An explicit retry may reconcile an old cleanup receipt; it never replays its metric
        // result or borrows that source's native authority, even before a new owner is registered.
        MetricSupportSupervisor.recheckRelease()
        // Capture at reservation, before any worker/Service callback can outlive this UI source.
        val capturedOwner = runCatching(ownerRef).getOrNull()
        if (capturedOwner == null || authority.ownerRef(capturedOwner.ownerId) !== capturedOwner) {
            onResult(Result.failure(MetricSupportProbeException(MetricSupportFailure.OWNER_NOT_REGISTERED)))
            return
        }
        val deadline = runCatching(absoluteDeadlineMs).getOrElse {
            onResult(Result.failure(MetricSupportProbeException(MetricSupportFailure.DEADLINE_REACHED, it))); return
        }
        val weakProbe = WeakReference(this)
        val attempt = synchronized(lock) {
            if (active != null) null else MetricSupportRemoteAttempt(context, deadline, fault, nativeBudgetMs, capturedOwner, authority) { owner ->
                weakProbe.get()?.clearReleased(owner)
            }.takeIf { MetricSupportSupervisor.acquire(it) }?.also { active = it; lastRelease = it.actualReleased }
        }
        if (attempt == null) {
            onResult(Result.failure(MetricSupportProbeException(MetricSupportFailure.BUSY)))
            return
        }
        try {
            MetricParentExecution.process().submit(MetricParentLane.COORDINATOR, action = Callable {
                val outcome = try { attempt.run() }
                catch (failure: Throwable) {
                    Result.failure(MetricSupportProbeException(MetricSupportFailure.PROTOCOL_ERROR, failure))
                } finally { attempt.workerFinished() }
                // No system IPC is performed after workerFinished. A timeout can still leave its
                // actual release receipt pending; delivering that failure never clears the fence.
                onResult(if (outcome.isSuccess && !attempt.actualReleased.isDone)
                    Result.failure(MetricSupportProbeException(MetricSupportFailure.RELEASE_UNCONFIRMED)) else outcome)
            })
        } catch (failure: Throwable) {
            attempt.cancel()
            attempt.workerFinished()
            onResult(Result.failure(MetricSupportProbeException(MetricSupportFailure.BIND_REJECTED, failure)))
        }
    }
    override fun cancel() { synchronized(lock) { active }?.cancel() }
    internal fun releasePending(): Boolean = MetricSupportSupervisor.pending()
    internal fun recheckRelease() { MetricSupportSupervisor.recheckRelease() }
    internal fun actualReleasedFuture(): CompletableFuture<Unit> = synchronized(lock) { lastRelease.thenApply { it } }
    private fun clearReleased(owner: MetricSupportRemoteAttempt) {
        synchronized(lock) { if (active === owner) active = null }
    }
    companion object {
        /** Works after Activity recreation without constructing a new probe or allocating native. */
        fun recheckPendingRelease() { MetricSupportSupervisor.recheckRelease() }
        fun hasPendingRelease(): Boolean = MetricSupportSupervisor.pending()
    }
}

private class MetricSupportRemoteAttempt(
    private val context: Context,
    private val absoluteDeadlineMs: Long?,
    private val fault: Int,
    private val nativeBudgetMs: Long,
    private val owner: DeviceCheckOwnerRef,
    private val authority: DeviceCheckAuthority,
    private val onReleased: (MetricSupportRemoteAttempt) -> Unit,
) : MetricSupportPendingRelease {
    private val nonce = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val lease = FixtureRuntimeConnectionLease<IBinder>()
    private val connected = CompletableFuture<IBinder>()
    private val result = CompletableFuture<Result<Boolean>>()
    private val unbound = CompletableFuture<Boolean>()
    private val endpoints = ConcurrentHashMap<IBinder, CompletableFuture<Unit>>()
    private val exitEnqueued = ConcurrentHashMap.newKeySet<IBinder>()
    private val cancelled = AtomicBoolean(false)
    private val execution = MetricParentExecution.process()
    private val transport = MetricParentTransport(::notifyReleased)
    val actualReleased = CompletableFuture<Unit>()
    @Volatile private var workerPid = -1
    @Volatile private var authenticated = false
    @Volatile private var workDeadline = 0L
    @Volatile private var nativeDeadline = 0L
    @Volatile private var childIdentity: DeviceCheckNativeProcess? = null
    private val actualLeaseIdentity = Any()
    private val capturedSource = owner.source
    private val journalLease = DeviceCheckNativeLease(nonce, owner.ownerId, 1L)
    private val available = AtomicBoolean(false)
    private val journal = MetricReleaseJournal(
        write = { receipt -> authority.recordCohortReleased(journalLease.cohortId, receipt.childPid, receipt.proof) },
        settleExactRelease = { command, receipt ->
            authority.settleNativeOpenRelease(capturedSource, owner, journalLease, actualLeaseIdentity, command,
                DeviceCheckMetricSupportContract.spec, requireNotNull(childIdentity), receipt.proof).thenApply { outcome ->
                when (outcome) {
                    is DeviceCheckAuthorityOutcome.Committed -> DeviceCheckAuthorityOutcome.Committed(Unit, outcome.revision, outcome.commandId)
                    is DeviceCheckAuthorityOutcome.Indeterminate -> outcome
                    is DeviceCheckAuthorityOutcome.Rejected -> outcome
                    is DeviceCheckAuthorityOutcome.NotPublished -> outcome
                }
            }
        },
        reconcile = authority::reconcileAfterSettled,
        markUnconfirmed = { marker ->
            authority.publicationUnconfirmed(marker)
            (authority.cachedSnapshot(capturedSource.binding) as? DeviceCheckAuthorityRead.Blocked)?.operationId ?: marker
        },
        changed = ::notifyReleased,
    )

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val role = lease.endpointConnected(service)
            if (role == FixtureRuntimeEndpointRole.DUPLICATE) return
            val proof = CompletableFuture<Unit>()
            endpoints[service] = proof
            val death = IBinder.DeathRecipient {
                lease.endpointDied(service); proof.complete(Unit)
                if (service == lease.firstEndpoint) {
                    connected.completeExceptionally(MetricSupportProbeException(MetricSupportFailure.CHILD_DIED))
                }
                notifyReleased()
            }
            try {
                service.linkToDeath(death, 0)
                if (role == FixtureRuntimeEndpointRole.RECONNECT || lease.retired) {
                    retire(); requestExit(service)
                } else connected.complete(service)
            } catch (error: Throwable) {
                if (error is DeadObjectException) death.binderDied()
                connected.completeExceptionally(error)
            }
        }
        override fun onServiceDisconnected(name: ComponentName) = connectionFailed()
        override fun onBindingDied(name: ComponentName) = connectionFailed()
        override fun onNullBinding(name: ComponentName) = connectionFailed()
    }

    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != MetricSupportProtocol.RESULT) return super.onTransact(code, data, reply, flags)
            if (Binder.getCallingUid() != Process.myUid() || Binder.getCallingPid() != workerPid)
                throw SecurityException("metric_support_callback_identity")
            require(data.dataSize() <= MetricSupportProtocol.MAX_PARCEL_BYTES)
            data.enforceInterface(MetricSupportProtocol.CALLBACK_DESCRIPTOR)
            require(data.readInt() == MetricSupportProtocol.VERSION && data.readString() == nonce)
            val status = data.readInt(); val reason = data.readString()
            require(data.dataAvail() == 0 && status in MetricSupportProtocol.FAILED..MetricSupportProtocol.SUPPORTED)
            val observed = when (status) {
                MetricSupportProtocol.SUPPORTED -> { require(reason == null); Result.success(true) }
                MetricSupportProtocol.UNSUPPORTED -> { require(reason == null); Result.success(false) }
                else -> {
                    val failure = MetricSupportFailure.entries.firstOrNull { it.name == reason }
                    require(failure == MetricSupportFailure.NATIVE_FAILED || failure == MetricSupportFailure.CLOSE_FAILED)
                    Result.failure(MetricSupportProbeException(requireNotNull(failure)))
                }
            }
            if (authenticated && lease.acceptsMetrics && !cancelled.get() && now() < nativeDeadline) result.complete(observed)
            reply?.writeNoException()
            return true
        }
    }

    fun run(): Result<Boolean> {
        var outcome: Result<Boolean>
        try {
            val started = now()
            if (absoluteDeadlineMs != null && absoluteDeadlineMs <= started)
                throw MetricSupportProbeException(MetricSupportFailure.DEADLINE_REACHED)
            workDeadline = minOf(started + MetricSupportProtocol.HANDSHAKE_MS + nativeBudgetMs,
                absoluteDeadlineMs?.minus(MetricSupportProtocol.DEATH_ACK_MS) ?: Long.MAX_VALUE)
            checkCurrent()
            val handshakeUntil = minOf(workDeadline, started + MetricSupportProtocol.HANDSHAKE_MS)
            val bindIntent = Intent(context, MetricSupportService::class.java).putExtra("nonce", nonce)
                .putExtra("deadline", workDeadline).putExtra("fault", fault).putExtra("nativeBudget", nativeBudgetMs)
            val accepted = await(submitIpc {
                lease.bind({ context.bindService(bindIntent, connection, Context.BIND_AUTO_CREATE) }, ::requestUnbind)
            }, handshakeUntil, MetricSupportFailure.HANDSHAKE_TIMEOUT)
            if (!accepted) throw MetricSupportProbeException(MetricSupportFailure.BIND_REJECTED)
            val endpoint = await(connected, handshakeUntil, MetricSupportFailure.HANDSHAKE_TIMEOUT)
            await(submitIpc { authenticate(endpoint) }, handshakeUntil, MetricSupportFailure.HANDSHAKE_TIMEOUT)
            checkCurrent()
            val journalCommand = UUID.randomUUID().toString()
            journal.intentStarting(journalCommand)
            val prepareIntent = try {
                authority.prepareNativeOpen(owner, journalLease, actualLeaseIdentity, journalCommand,
                    DeviceCheckMetricSupportContract.spec, requireNotNull(childIdentity), workDeadline)
            } catch (failure: Throwable) { journal.intentInvocationFailed(); throw failure }
            journal.observeIntent(prepareIntent)
            val admission = awaitJournal(prepareIntent, workDeadline, journalCommand)
            val permit = when (admission) {
                is DeviceCheckAuthorityOutcome.Committed -> admission.value
                is DeviceCheckAuthorityOutcome.Rejected, is DeviceCheckAuthorityOutcome.NotPublished -> {
                    throw MetricSupportProbeException(MetricSupportFailure.JOURNAL_UNCONFIRMED)
                }
                is DeviceCheckAuthorityOutcome.Indeterminate -> throw MetricSupportProbeException(MetricSupportFailure.JOURNAL_UNCONFIRMED)
            }
            checkCurrent()
            nativeDeadline = minOf(workDeadline, now() + nativeBudgetMs)
            await(submitIpc {
                if (!authority.consumeNativeOpen(permit, actualLeaseIdentity, now()))
                    throw MetricSupportProbeException(MetricSupportFailure.CANCELLED)
                lease.dispatchStart(endpoint, { !cancelled.get() && now() < nativeDeadline }) {
                    transact(endpoint, MetricSupportProtocol.QUERY, true)
                }
            }, nativeDeadline, MetricSupportFailure.QUERY_TIMEOUT)
            outcome = await(result, nativeDeadline, MetricSupportFailure.QUERY_TIMEOUT)
        } catch (error: Throwable) {
            val cause = if (error is ExecutionException) error.cause ?: error else error
            outcome = Result.failure(if (cause is MetricSupportProbeException) cause else
                MetricSupportProbeException(MetricSupportFailure.PROTOCOL_ERROR, cause))
        } finally { journal.sealIntent(); retire() }
        endpoints.keys.forEach(::requestExit)
        val releaseUntil = minOf(now() + MetricSupportProtocol.DEATH_ACK_MS, absoluteDeadlineMs ?: Long.MAX_VALUE)
        if (lease.needsDeath) {
            try {
                val remaining = releaseUntil - now()
                if (remaining <= 0L || !unbound.get(remaining, TimeUnit.MILLISECONDS) || endpoints.isEmpty())
                    throw TimeoutException("metric_support_unbind_unconfirmed")
                endpoints.values.forEach { proof ->
                    if (proof.isDone) proof.get() else {
                        val left = releaseUntil - now()
                        if (left <= 0L) throw TimeoutException("metric_support_death_unconfirmed")
                        proof.get(left, TimeUnit.MILLISECONDS)
                    }
                }
            } catch (failure: Throwable) {
                outcome = Result.failure(MetricSupportProbeException(MetricSupportFailure.RELEASE_UNCONFIRMED, failure))
            }
        }
        // Even NO_NATIVE_START cannot release a blocked bind/HELLO/unbind parent thread. Do not
        // await outer finalization here: workerFinished performs that final join after run returns.
        try {
            if (!transport.awaitDrained((releaseUntil - now()).coerceAtLeast(0L)))
                outcome = Result.failure(MetricSupportProbeException(MetricSupportFailure.RELEASE_UNCONFIRMED))
        } catch (failure: Throwable) {
            outcome = Result.failure(MetricSupportProbeException(MetricSupportFailure.RELEASE_UNCONFIRMED, failure))
        }
        notifyReleased()
        if (lease.releaseProof == FixtureRuntimeReleaseProof.NONE ||
            lease.bindWasAttempted && !unbound.getNow(false) || transport.pendingTasks() != 0)
            return Result.failure(MetricSupportProbeException(MetricSupportFailure.RELEASE_UNCONFIRMED))
        try {
            val remaining = minOf(now() + 15_000L, absoluteDeadlineMs ?: Long.MAX_VALUE) - now()
            if (remaining <= 0L) throw TimeoutException("metric_cleanup_wait_deadline")
            // This is an observer deadline, not a new storage operation. The original authority
            // write owns uncertainty; a racing, already-confirmed ACK must not be dirtied again.
            val settled = journal.firstOutcome.get(remaining, TimeUnit.MILLISECONDS)
            if (settled !is DeviceCheckAuthorityOutcome.Committed)
                return Result.failure(MetricSupportProbeException(MetricSupportFailure.JOURNAL_UNCONFIRMED))
        } catch (_: Throwable) {
            return Result.failure(MetricSupportProbeException(MetricSupportFailure.JOURNAL_UNCONFIRMED))
        }
        if (cancelled.get()) return Result.failure(MetricSupportProbeException(MetricSupportFailure.CANCELLED))
        if (absoluteDeadlineMs != null && now() >= absoluteDeadlineMs)
            return Result.failure(MetricSupportProbeException(MetricSupportFailure.DEADLINE_REACHED))
        return outcome
    }

    fun cancel() { cancelled.set(true); retire(); endpoints.keys.forEach(::requestExit) }
    fun workerFinished() { journal.sealIntent(); retire(); transport.outerFinished() }
    override fun recheckRelease() { journal.recheck() }
    private fun <T> submitIpc(action: () -> T): Future<T> =
        execution.submit(MetricParentLane.IPC, transport, Callable(action))
    private fun authenticate(endpoint: IBinder, record: Boolean = true) {
        transact(endpoint, MetricSupportProtocol.HELLO, false, { writeStrongBinder(callback) }) { reply ->
            require(reply.dataSize() <= MetricSupportProtocol.MAX_PARCEL_BYTES && reply.readInt() == MetricSupportProtocol.VERSION)
            val response = reply.readString(); val pid = reply.readInt(); val uid = reply.readInt(); val name = reply.readString()
            val ticks = reply.readLong(); val boot = reply.readString()
            if (reply.dataAvail() != 0 || response != nonce || !MetricSupportProtocol.validIdentity(
                    context.packageName + MetricSupportProtocol.PROCESS_SUFFIX, name, Process.myPid(), pid, Process.myUid(), uid))
                throw MetricSupportProbeException(MetricSupportFailure.IDENTITY_MISMATCH)
            val verifiedProcess = VisionProcessIdentity.captureVerified(context,
                DeviceCheckNativeProcess(pid, uid, requireNotNull(name), ticks, requireNotNull(boot)))
                ?: throw MetricSupportProbeException(MetricSupportFailure.IDENTITY_MISMATCH)
            if (record) {
                if (!lease.authenticate(endpoint)) throw MetricSupportProbeException(MetricSupportFailure.CANCELLED)
                workerPid = pid; authenticated = true; childIdentity = verifiedProcess
            }
        }
    }
    private fun transact(endpoint: IBinder, code: Int, oneWay: Boolean,
        write: Parcel.() -> Unit = {}, read: (Parcel) -> Unit = {}) {
        val data = Parcel.obtain(); val reply = if (oneWay) null else Parcel.obtain()
        try {
            data.writeInterfaceToken(MetricSupportProtocol.DESCRIPTOR)
            data.writeInt(MetricSupportProtocol.VERSION); data.writeString(nonce); data.write()
            check(endpoint.transact(code, data, reply, if (oneWay) IBinder.FLAG_ONEWAY else 0))
            if (reply != null) { reply.readException(); read(reply) }
        } finally { reply?.recycle(); data.recycle() }
    }
    private fun retire() { lease.retire(); requestUnbind(); notifyReleased() }
    private fun requestUnbind() {
        if (!lease.bindWasAttempted) { unbound.complete(true); return }
        if (lease.claimUnbind()) cleanup(MetricParentLane.UNBIND) {
            var success = false
            try { context.unbindService(connection); success = true } catch (_: Exception) { }
            finally { lease.finishUnbind(success); unbound.complete(success); notifyReleased() }
        }
    }
    private fun requestExit(endpoint: IBinder) {
        if (!exitEnqueued.add(endpoint)) return
        cleanup(MetricParentLane.EXIT) {
            // Revoke AUTO_CREATE first, then terminate only an authenticated private endpoint.
            unbound.get(MetricSupportProtocol.HANDSHAKE_MS, TimeUnit.MILLISECONDS)
            if (endpoint != lease.firstEndpoint || !authenticated) authenticate(endpoint, record = false)
            transact(endpoint, MetricSupportProtocol.EXIT, true)
        }
    }
    private fun cleanup(lane: MetricParentLane, action: () -> Unit) {
        try { execution.submit(lane, transport, Callable { action() }) }
        catch (_: RejectedExecutionException) {
            // Bounded rejection cannot create another thread. A required unbind/native death
            // without an ACK stays fenced; late callbacks cannot reopen retired START authority.
        }
    }
    private fun connectionFailed() {
        connected.completeExceptionally(MetricSupportProbeException(MetricSupportFailure.CHILD_DIED))
        // ServiceConnection loss is not a Binder death receipt.
        retire(); endpoints.keys.forEach(::requestExit)
    }
    private fun notifyReleased() {
        val nativeProof = lease.releaseProof
        val bindingClean = !lease.bindWasAttempted || unbound.getNow(false)
        val physicallyReady = nativeProof != FixtureRuntimeReleaseProof.NONE && bindingClean
        if (physicallyReady && transport.pendingTasks() == 0) {
            val proof = if (nativeProof == FixtureRuntimeReleaseProof.NO_NATIVE_START)
                DeviceCheckNativeReleaseProof.NO_NATIVE_START else DeviceCheckNativeReleaseProof.CHILD_DEATH
            journal.observePhysical(MetricJournalReleaseReceipt(
                if (proof == DeviceCheckNativeReleaseProof.NO_NATIVE_START) 0 else workerPid, proof))
        }
        if (transport.claimRelease(nativeProof != FixtureRuntimeReleaseProof.NONE, bindingClean)) actualReleased.complete(Unit)
        // Native release is a fact even if persistence is still uncertain. Availability additionally
        // requires the captured journal cleanup; explicit retries can recheck it without new native.
        if (actualReleased.isDone && journal.confirmed.isDone && available.compareAndSet(false, true)) {
            MetricSupportSupervisor.release(this, true)
            onReleased(this)
        }
    }
    private fun checkCurrent() {
        if (cancelled.get()) throw MetricSupportProbeException(MetricSupportFailure.CANCELLED)
        if (now() >= workDeadline) throw MetricSupportProbeException(MetricSupportFailure.DEADLINE_REACHED)
    }
    private fun <T> await(future: Future<T>, until: Long, timeout: MetricSupportFailure): T {
        while (true) {
            checkCurrent()
            val remaining = until - now()
            if (remaining <= 0L) throw MetricSupportProbeException(timeout)
            if (!future.isDone && !result.isDone && lease.needsDeath && endpoints.values.any { it.isDone })
                throw MetricSupportProbeException(MetricSupportFailure.CHILD_DIED)
            try { return future.get(minOf(50L, remaining), TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { }
        }
    }
    private fun <T> awaitJournal(future: java.util.concurrent.CompletionStage<DeviceCheckAuthorityOutcome<T>>,
        until: Long, marker: String): DeviceCheckAuthorityOutcome<T> {
        try {
            val remaining = until - now()
            if (remaining <= 0) throw TimeoutException("metric_journal_deadline")
            return future.toCompletableFuture().get(remaining, TimeUnit.MILLISECONDS)
        } catch (failure: Throwable) {
            // A get timeout/cancellation does not stop the storage thread or prove non-publication.
            authority.publicationUnconfirmed(marker)
            throw MetricSupportProbeException(MetricSupportFailure.JOURNAL_UNCONFIRMED, failure)
        }
    }
    private fun now() = SystemClock.elapsedRealtime()
}

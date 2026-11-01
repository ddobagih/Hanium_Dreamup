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
import kr.co.hanium.dreamup.walksafe.inference.composite.FixtureRuntimeConnectionLease
import kr.co.hanium.dreamup.walksafe.inference.composite.FixtureRuntimeEndpointRole
import kr.co.hanium.dreamup.walksafe.inference.composite.FixtureRuntimeReleaseProof
import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** No ARCore Session is created here. A result is usable only after remote death and unbind ACK. */
internal class RemoteMetricSupportProbe internal constructor(
    context: Context,
    private val absoluteDeadlineMs: () -> Long? = { null },
    private val fault: Int = MetricSupportProtocol.FAULT_NONE,
    private val nativeBudgetMs: Long = MetricSupportProtocol.NATIVE_MS,
) : RuntimeMetricDepthSupportProbe {
    private val context = context.applicationContext
    private val lock = Any()
    private var active: MetricSupportRemoteAttempt? = null

    init {
        require(MetricSupportProtocol.validFault(fault, BuildConfig.DEBUG))
        require(nativeBudgetMs in 1L..MetricSupportProtocol.NATIVE_MS)
        require(BuildConfig.DEBUG || nativeBudgetMs == MetricSupportProtocol.NATIVE_MS)
    }
    override fun automaticDepthSupported(): Boolean = throw MetricSupportProbeException(MetricSupportFailure.ASYNC_REQUIRED)

    override fun query(onResult: (Result<Boolean>) -> Unit) {
        if (MetricSupportProtocol.processName(context) != context.applicationInfo.processName) {
            onResult(Result.failure(MetricSupportProbeException(MetricSupportFailure.WRONG_PARENT_PROCESS)))
            return
        }
        val deadline = runCatching(absoluteDeadlineMs).getOrElse {
            onResult(Result.failure(MetricSupportProbeException(MetricSupportFailure.DEADLINE_REACHED, it))); return
        }
        val attempt = synchronized(lock) {
            if (active != null) null else MetricSupportRemoteAttempt(context, deadline, fault, nativeBudgetMs) { owner ->
                synchronized(lock) { if (active === owner) active = null }
            }.takeIf { MetricSupportSupervisor.acquire(it) }?.also { active = it }
        }
        if (attempt == null) {
            onResult(Result.failure(MetricSupportProbeException(MetricSupportFailure.BUSY)))
            return
        }
        try {
            Thread({ onResult(attempt.run()) }, "WalkSafeMetricSupportParent").apply { isDaemon = true }.start()
        } catch (failure: Throwable) {
            attempt.cancel()
            onResult(Result.failure(MetricSupportProbeException(MetricSupportFailure.BIND_REJECTED, failure)))
        }
    }
    override fun cancel() { synchronized(lock) { active }?.cancel() }
    internal fun releasePending(): Boolean = synchronized(lock) { active != null }
}

private class MetricSupportRemoteAttempt(
    private val context: Context,
    private val absoluteDeadlineMs: Long?,
    private val fault: Int,
    private val nativeBudgetMs: Long,
    private val onReleased: (MetricSupportRemoteAttempt) -> Unit,
) {
    private val nonce = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val lease = FixtureRuntimeConnectionLease<IBinder>()
    private val connected = CompletableFuture<IBinder>()
    private val result = CompletableFuture<Result<Boolean>>()
    private val unbound = CompletableFuture<Boolean>()
    private val endpoints = ConcurrentHashMap<IBinder, CompletableFuture<Unit>>()
    private val exitEnqueued = ConcurrentHashMap.newKeySet<IBinder>()
    private val released = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val ipc = Executors.newCachedThreadPool { Thread(it, "WalkSafeMetricSupportIpc").apply { isDaemon = true } }
    @Volatile private var workerPid = -1
    @Volatile private var authenticated = false
    @Volatile private var workDeadline = 0L
    @Volatile private var nativeDeadline = 0L

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
            val intent = Intent(context, MetricSupportService::class.java).putExtra("nonce", nonce)
                .putExtra("deadline", workDeadline).putExtra("fault", fault).putExtra("nativeBudget", nativeBudgetMs)
            val accepted = await(ipc.submit(Callable {
                lease.bind({ context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }, ::requestUnbind)
            }), handshakeUntil, MetricSupportFailure.HANDSHAKE_TIMEOUT)
            if (!accepted) throw MetricSupportProbeException(MetricSupportFailure.BIND_REJECTED)
            val endpoint = await(connected, handshakeUntil, MetricSupportFailure.HANDSHAKE_TIMEOUT)
            await(ipc.submit(Callable { authenticate(endpoint) }), handshakeUntil, MetricSupportFailure.HANDSHAKE_TIMEOUT)
            nativeDeadline = minOf(workDeadline, now() + nativeBudgetMs)
            await(ipc.submit(Callable {
                lease.dispatchStart(endpoint, { !cancelled.get() && now() < nativeDeadline }) {
                    transact(endpoint, MetricSupportProtocol.QUERY, true)
                }
            }), nativeDeadline, MetricSupportFailure.QUERY_TIMEOUT)
            outcome = await(result, nativeDeadline, MetricSupportFailure.QUERY_TIMEOUT)
        } catch (error: Throwable) {
            val cause = if (error is ExecutionException) error.cause ?: error else error
            outcome = Result.failure(if (cause is MetricSupportProbeException) cause else
                MetricSupportProbeException(MetricSupportFailure.PROTOCOL_ERROR, cause))
        } finally { retire() }
        endpoints.keys.forEach(::requestExit)
        if (lease.needsDeath) {
            val until = minOf(now() + MetricSupportProtocol.DEATH_ACK_MS, absoluteDeadlineMs ?: Long.MAX_VALUE)
            try {
                val remaining = until - now()
                if (remaining <= 0L || !unbound.get(remaining, TimeUnit.MILLISECONDS) || endpoints.isEmpty())
                    throw TimeoutException("metric_support_unbind_unconfirmed")
                endpoints.values.forEach { proof ->
                    if (proof.isDone) proof.get() else {
                        val left = until - now()
                        if (left <= 0L) throw TimeoutException("metric_support_death_unconfirmed")
                        proof.get(left, TimeUnit.MILLISECONDS)
                    }
                }
            } catch (failure: Throwable) {
                outcome = Result.failure(MetricSupportProbeException(MetricSupportFailure.RELEASE_UNCONFIRMED, failure))
            }
        }
        notifyReleased()
        if (lease.releaseProof == FixtureRuntimeReleaseProof.NONE)
            return Result.failure(MetricSupportProbeException(MetricSupportFailure.RELEASE_UNCONFIRMED))
        if (cancelled.get()) return Result.failure(MetricSupportProbeException(MetricSupportFailure.CANCELLED))
        if (absoluteDeadlineMs != null && now() >= absoluteDeadlineMs)
            return Result.failure(MetricSupportProbeException(MetricSupportFailure.DEADLINE_REACHED))
        return outcome
    }

    fun cancel() { cancelled.set(true); retire(); endpoints.keys.forEach(::requestExit) }
    private fun authenticate(endpoint: IBinder, record: Boolean = true) {
        transact(endpoint, MetricSupportProtocol.HELLO, false, { writeStrongBinder(callback) }) { reply ->
            require(reply.dataSize() <= MetricSupportProtocol.MAX_PARCEL_BYTES && reply.readInt() == MetricSupportProtocol.VERSION)
            val response = reply.readString(); val pid = reply.readInt(); val uid = reply.readInt(); val name = reply.readString()
            if (reply.dataAvail() != 0 || response != nonce || !MetricSupportProtocol.validIdentity(
                    context.packageName + MetricSupportProtocol.PROCESS_SUFFIX, name, Process.myPid(), pid, Process.myUid(), uid))
                throw MetricSupportProbeException(MetricSupportFailure.IDENTITY_MISMATCH)
            if (record) {
                if (!lease.authenticate(endpoint)) throw MetricSupportProbeException(MetricSupportFailure.CANCELLED)
                workerPid = pid; authenticated = true
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
        if (lease.claimUnbind()) cleanup {
            var success = false
            try { context.unbindService(connection); success = true } catch (_: Exception) { }
            finally { lease.finishUnbind(success); unbound.complete(success); notifyReleased() }
        }
    }
    private fun requestExit(endpoint: IBinder) {
        if (!exitEnqueued.add(endpoint)) return
        cleanup {
            // Revoke AUTO_CREATE first, then terminate only an authenticated private endpoint.
            unbound.get(MetricSupportProtocol.HANDSHAKE_MS, TimeUnit.MILLISECONDS)
            if (endpoint != lease.firstEndpoint || !authenticated) authenticate(endpoint, record = false)
            transact(endpoint, MetricSupportProtocol.EXIT, true)
        }
    }
    private fun cleanup(action: () -> Unit) {
        val task = Runnable { runCatching(action) }
        try { ipc.execute(task) } catch (_: RejectedExecutionException) {
            Thread(task, "WalkSafeMetricSupportLateCleanup").apply { isDaemon = true }.start()
        }
    }
    private fun connectionFailed() {
        connected.completeExceptionally(MetricSupportProbeException(MetricSupportFailure.CHILD_DIED))
        // ServiceConnection loss is not a Binder death receipt.
        retire(); endpoints.keys.forEach(::requestExit)
    }
    private fun notifyReleased() {
        if (lease.releaseProof != FixtureRuntimeReleaseProof.NONE && released.compareAndSet(false, true)) {
            MetricSupportSupervisor.release(this, true); onReleased(this); ipc.shutdown()
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
    private fun now() = SystemClock.elapsedRealtime()
}

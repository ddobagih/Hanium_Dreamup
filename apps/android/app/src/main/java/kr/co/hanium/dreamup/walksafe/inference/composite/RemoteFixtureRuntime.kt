package kr.co.hanium.dreamup.walksafe.inference.composite

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
import java.security.SecureRandom
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class FixtureRuntimeOutcome(val evidence: JointFixtureRuntimeEvidence?, val reason: String,
    val releaseConfirmed: Boolean)

/** Parent-side bounded IPC only. No detector/native model is ever constructed in this process. */
internal class RemoteFixtureRuntime(private val context: Context, private val candidate: JointRuntimeCandidate,
    private val bindingHash: String, private val deadlineMs: Long, private val current: () -> Boolean,
    private val progress: (String) -> Unit, private val onReleased: () -> Unit) {
    private val nonce = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val connected = CompletableFuture<IBinder>()
    private val died = CompletableFuture<Unit>()
    private val result = CompletableFuture<Pair<JointFixtureRuntimeEvidence?, String>>()
    private val bound = AtomicBoolean(false)
    private val bindReturned = AtomicBoolean(false)
    private val authority = FixtureRuntimeAuthority(nonce)
    private val endpoints = ConcurrentHashMap<IBinder, CompletableFuture<Unit>>()
    private val releaseNotified = AtomicBoolean(false)
    private val identity = AtomicReference<IBinder?>()
    private val stage = AtomicReference(FixtureRuntimeStage.REFERENCES)
    @Volatile private var stageDeadline = 0L
    @Volatile private var lastStage = -1
    private val ipc = Executors.newCachedThreadPool { task -> Thread(task, "WalkSafeFixtureIpc").apply { isDaemon = true } }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val proof = CompletableFuture<Unit>()
            if (endpoints.putIfAbsent(service, proof) != null) return
            val first = identity.compareAndSet(null, service)
            val death = IBinder.DeathRecipient {
                if (identity.get() == service) {
                    // Authority is recorded before waking a waiter that may immediately adopt.
                    authority.publishDeath(proof) { unbind(); died.complete(Unit) }
                } else proof.complete(Unit)
                notifyReleased()
            }
            try {
                // Observe every concrete endpoint, but never replace this attempt's first identity.
                service.linkToDeath(death, 0)
                if (!first) {
                    authority.retire(); unbind()
                    ipc.execute { runCatching { authenticate(service, record = false); requestExit(service) } }
                    return
                }
                if (!connected.complete(service) || authority.retired) {
                    ipc.execute { runCatching { authenticate(service); requestExit(service) } }
                }
            } catch (error: Throwable) {
                // linkToDeath's explicit dead-object reply is an actual death receipt, not unbind.
                if (error is DeadObjectException) death.binderDied()
                connected.completeExceptionally(error)
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { /* Only actual Binder death proves exit. */ }
        override fun onBindingDied(name: ComponentName) { if (!connected.isDone) connected.completeExceptionally(IllegalStateException("binding_died")) }
        override fun onNullBinding(name: ComponentName) { connected.completeExceptionally(IllegalStateException("null_binding")) }
    }

    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in FixtureRuntimeProtocol.STAGE..FixtureRuntimeProtocol.RESULT)
                return super.onTransact(code, data, reply, flags)
            if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("Fixture callback UID mismatch")
            data.enforceInterface(FixtureRuntimeProtocol.CALLBACK_DESCRIPTOR)
            require(data.dataSize() <= FixtureRuntimeProtocol.MAX_JSON_BYTES + 1024)
            require(data.readInt() == FixtureRuntimeProtocol.VERSION && data.readString() == nonce)
            if (!authority.accepts(nonce) || !current() || now() >= deadlineMs) return true
            when (code) {
                FixtureRuntimeProtocol.STAGE -> {
                    val next = requireNotNull(FixtureRuntimeStage.parse(data.readString()))
                    require(data.dataAvail() == 0)
                    synchronized(this@RemoteFixtureRuntime) {
                        if (next.ordinal > lastStage) {
                            lastStage = next.ordinal
                            stage.set(next)
                            stageDeadline = minOf(deadlineMs - FixtureRuntimeProtocol.DEATH_ACK_MS, now() + next.budgetMs)
                            progress(next.token)
                        }
                    }
                }
                FixtureRuntimeProtocol.RESULT -> {
                    val closeFlag = data.readInt().also { require(it in 0..1) }
                    val nativeClosed = closeFlag == 1
                    val reason = requireNotNull(data.readString()).also { require(it.length <= FixtureRuntimeProtocol.MAX_REASON_CHARS) }
                    val json = data.readString()
                    require(data.dataAvail() == 0 && (json == null || json.toByteArray(Charsets.UTF_8).size <= FixtureRuntimeProtocol.MAX_JSON_BYTES))
                    val evidence = json?.let(JointRuntimeChoiceCodec::decodeEvidence)
                    if (authority.recordResult(nonce, nativeClosed)) result.complete((if (nativeClosed) evidence else null) to reason)
                }
            }
            return true
        }
    }

    fun run(): FixtureRuntimeOutcome {
        var measured: JointFixtureRuntimeEvidence? = null
        var reason = "fixture_worker_unavailable"
        var boundAttempted = false
        try {
            checkCurrent()
            val intent = Intent(context, FixtureRuntimeService::class.java).putExtra("nonce", nonce).putExtra("deadline", deadlineMs)
            boundAttempted = true
            bound.set(true)
            val accepted = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            bindReturned.set(true)
            if (!accepted) {
                bound.set(false)
                ipc.shutdown()
                return FixtureRuntimeOutcome(null, "fixture_binding_rejected", true)
            }
            if (authority.retired) unbind()
            val endpoint = await(connected, minOf(deadlineMs, now() + FixtureRuntimeProtocol.HANDSHAKE_MS))
            await(ipc.submit(Callable { authenticate(endpoint) }), minOf(deadlineMs, now() + FixtureRuntimeProtocol.HANDSHAKE_MS))
            checkCurrent()
            stageDeadline = minOf(deadlineMs - FixtureRuntimeProtocol.DEATH_ACK_MS, now() + FixtureRuntimeStage.REFERENCES.budgetMs)
            await(ipc.submit(Callable {
                transact(endpoint, FixtureRuntimeProtocol.START, true, write = {
                    writeString(bindingHash)
                    writeString(candidate.primary.backend.name); writeInt(candidate.primary.numThreads)
                    writeString(candidate.auxiliary.backend.name); writeInt(candidate.auxiliary.numThreads)
                    writeLong(candidate.primaryIntervalMs); writeLong(candidate.auxiliaryIntervalMs)
                    writeStrongBinder(callback)
                })
            }), minOf(deadlineMs, now() + FixtureRuntimeProtocol.HANDSHAKE_MS))
            while (!result.isDone) {
                checkCurrent()
                if (died.isDone) throw IllegalStateException("fixture_worker_died")
                if (now() >= stageDeadline) throw TimeoutException("fixture_${stage.get().token}_timeout")
                try { result.get(50L, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { }
            }
            val value = result.get()
            measured = value.first
            reason = value.second
        } catch (error: Throwable) {
            reason = (error.cause?.message ?: error.message ?: error.javaClass.simpleName).take(FixtureRuntimeProtocol.MAX_REASON_CHARS)
        } finally {
            bindReturned.set(true)
            authority.retire()
            unbind() // Retire AUTO_CREATE before requesting self-exit; never count unbind as release.
            notifyReleased()
        }
        val endpoint = identity.get()
        if (endpoint != null && authority.verified && !died.isDone) ipc.execute { runCatching { requestExit(endpoint) } }
        val released = if (!boundAttempted && !bound.get()) true else runCatching {
            awaitDeath(minOf(deadlineMs, now() + FixtureRuntimeProtocol.DEATH_ACK_MS)); true
        }.getOrDefault(false) && allEndpointsDead()
        if (released) { notifyReleased(); ipc.shutdown() }
        return FixtureRuntimeOutcome(measured.takeIf { released && authority.canAdopt && current() && now() < deadlineMs }, reason, released)
    }

    fun cancel() {
        authority.retire()
        unbind()
        identity.get()?.takeIf { authority.verified }?.let { endpoint ->
            runCatching { ipc.execute { runCatching { requestExit(endpoint) } } }
        }
    }

    private fun authenticate(endpoint: IBinder, record: Boolean = true) {
        transact(endpoint, FixtureRuntimeProtocol.HELLO, false, {}, { reply ->
            require(reply.dataSize() <= 2048)
            require(reply.readInt() == FixtureRuntimeProtocol.VERSION)
            val responseNonce = reply.readString()
            val pid = reply.readInt(); val uid = reply.readInt(); val name = reply.readString()
            val valid = FixtureRuntimeProtocol.validIdentity(context.packageName + FixtureRuntimeProtocol.PROCESS_SUFFIX,
                name, Process.myPid(), pid, Process.myUid(), uid)
            require(reply.dataAvail() == 0 && responseNonce == nonce && valid &&
                (!record || authority.authenticate(responseNonce, valid))) { "fixture_identity_mismatch" }
        })
    }

    private fun requestExit(endpoint: IBinder) = transact(endpoint, FixtureRuntimeProtocol.EXIT, true, write = {})

    private fun transact(endpoint: IBinder, code: Int, oneWay: Boolean, write: Parcel.() -> Unit,
        read: (Parcel) -> Unit = {}) {
        val data = Parcel.obtain(); val reply = if (oneWay) null else Parcel.obtain()
        try {
            data.writeInterfaceToken(FixtureRuntimeProtocol.DESCRIPTOR)
            data.writeInt(FixtureRuntimeProtocol.VERSION); data.writeString(nonce); data.write()
            check(endpoint.transact(code, data, reply, if (oneWay) IBinder.FLAG_ONEWAY else 0)) { "fixture_transaction_rejected" }
            if (reply != null) { reply.readException(); read(reply) }
        } finally { reply?.recycle(); data.recycle() }
    }

    private fun unbind() {
        if (bindReturned.get() && bound.compareAndSet(true, false)) runCatching { context.unbindService(connection) }
    }
    private fun checkCurrent() { if (!current() || now() >= deadlineMs - FixtureRuntimeProtocol.DEATH_ACK_MS) throw CancellationException("fixture_deadline_or_cancelled") }
    private fun <T> await(future: Future<T>, until: Long): T {
        while (true) {
            checkCurrent()
            val remaining = until - now()
            if (remaining <= 0L) throw TimeoutException("fixture_ipc_timeout")
            try { return future.get(minOf(100L, remaining), TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { }
        }
    }
    private fun awaitDeath(until: Long) {
        // An accepted bind with no endpoint is not an empty-set release receipt.
        if (endpoints.isEmpty()) throw TimeoutException("fixture_endpoint_unconfirmed")
        for (proof in endpoints.values) {
            if (proof.isDone) { proof.get(); continue }
            val remaining = until - now()
            if (remaining <= 0L) throw TimeoutException("fixture_death_unconfirmed")
            proof.get(remaining, TimeUnit.MILLISECONDS)
        }
    }
    private fun allEndpointsDead() = endpoints.isNotEmpty() && endpoints.values.all { it.isDone && !it.isCompletedExceptionally }
    private fun notifyReleased() {
        if (authority.retired && allEndpointsDead() && releaseNotified.compareAndSet(false, true)) {
            onReleased()
            ipc.shutdown()
        }
    }
    private fun now() = SystemClock.elapsedRealtime()
}

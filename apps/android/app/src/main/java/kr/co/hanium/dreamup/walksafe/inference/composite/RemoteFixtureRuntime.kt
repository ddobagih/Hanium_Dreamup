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
    val releaseConfirmed: Boolean, val releaseProof: FixtureRuntimeReleaseProof = FixtureRuntimeReleaseProof.NONE)

/** Parent-side bounded IPC only. No detector/native model is ever constructed in this process. */
internal class RemoteFixtureRuntime(private val context: Context, private val candidate: JointRuntimeCandidate,
    private val bindingHash: String, private val deadlineMs: Long, private val current: () -> Boolean,
    private val progress: (String) -> Unit, private val onReleased: () -> Unit) {
    private val nonce = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val connected = CompletableFuture<IBinder>()
    private val died = CompletableFuture<Unit>()
    private val result = CompletableFuture<Pair<JointFixtureRuntimeEvidence?, String>>()
    private val lease = FixtureRuntimeConnectionLease<IBinder>()
    private val unbindReceipt = CompletableFuture<Boolean>()
    private val cleanupEnqueued = ConcurrentHashMap.newKeySet<IBinder>()
    private val authority = FixtureRuntimeAuthority(nonce)
    private val endpoints = ConcurrentHashMap<IBinder, CompletableFuture<Unit>>()
    private val releaseNotified = AtomicBoolean(false)
    private val stage = AtomicReference(FixtureRuntimeStage.REFERENCES)
    @Volatile private var stageDeadline = 0L
    @Volatile private var lastStage = -1
    private val ipc = Executors.newCachedThreadPool { task -> Thread(task, "WalkSafeFixtureIpc").apply { isDaemon = true } }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val role = lease.endpointConnected(service)
            if (role == FixtureRuntimeEndpointRole.DUPLICATE) return
            val proof = CompletableFuture<Unit>()
            endpoints[service] = proof
            val death = IBinder.DeathRecipient {
                if (lease.firstEndpoint == service) {
                    // Authority is recorded before waking a waiter that may immediately adopt.
                    authority.publishDeath(proof) {
                        lease.endpointDied(service); retireBinding(); died.complete(Unit)
                    }
                } else { lease.endpointDied(service); proof.complete(Unit) }
                notifyReleased()
            }
            try {
                // Observe every concrete endpoint, but never replace this attempt's first identity.
                service.linkToDeath(death, 0)
                if (role == FixtureRuntimeEndpointRole.RECONNECT || lease.retired) {
                    retireBinding(); requestEndpointExit(service)
                } else if (!connected.complete(service)) {
                    retireBinding(); requestEndpointExit(service)
                }
            } catch (error: Throwable) {
                // linkToDeath's explicit dead-object reply is an actual death receipt, not unbind.
                if (error is DeadObjectException) death.binderDied()
                connected.completeExceptionally(error)
            }
        }
        override fun onServiceDisconnected(name: ComponentName) = connectionFailed("fixture_disconnected")
        override fun onBindingDied(name: ComponentName) = connectionFailed("binding_died")
        override fun onNullBinding(name: ComponentName) = connectionFailed("null_binding")
    }

    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in FixtureRuntimeProtocol.STAGE..FixtureRuntimeProtocol.RESULT)
                return super.onTransact(code, data, reply, flags)
            if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("Fixture callback UID mismatch")
            data.enforceInterface(FixtureRuntimeProtocol.CALLBACK_DESCRIPTOR)
            require(data.dataSize() <= FixtureRuntimeProtocol.MAX_JSON_BYTES + 1024)
            require(data.readInt() == FixtureRuntimeProtocol.VERSION && data.readString() == nonce)
            if (!lease.acceptsMetrics || !authority.accepts(nonce) || !current() || now() >= deadlineMs) return true
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
        try {
            checkCurrent()
            val intent = Intent(context, FixtureRuntimeService::class.java).putExtra("nonce", nonce).putExtra("deadline", deadlineMs)
            val handshakeUntil = minOf(deadlineMs, now() + FixtureRuntimeProtocol.HANDSHAKE_MS)
            val accepted = await(ipc.submit(Callable {
                lease.bind({ context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }, ::requestUnbind)
            }), handshakeUntil)
            check(accepted) { "fixture_binding_rejected" }
            val endpoint = await(connected, handshakeUntil)
            await(ipc.submit(Callable { authenticate(endpoint) }), handshakeUntil)
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
            retireBinding()
        }
        endpoints.keys.forEach(::requestEndpointExit)
        if (lease.needsDeath) runCatching {
            val until = minOf(deadlineMs, now() + FixtureRuntimeProtocol.DEATH_ACK_MS)
            val remaining = until - now()
            if (remaining <= 0L || !unbindReceipt.get(remaining, TimeUnit.MILLISECONDS))
                throw IllegalStateException("fixture_unbind_unconfirmed")
            awaitDeath(until)
        }.onFailure { reason = (it.message ?: "fixture_release_unconfirmed").take(FixtureRuntimeProtocol.MAX_REASON_CHARS) }
        val proof = lease.releaseProof
        val released = proof != FixtureRuntimeReleaseProof.NONE
        if (released) { notifyReleased(); ipc.shutdown() }
        return FixtureRuntimeOutcome(measured.takeIf { proof == FixtureRuntimeReleaseProof.WORKER_DEATH &&
            authority.canAdopt && current() && now() < deadlineMs }, reason, released, proof)
    }

    fun cancel() {
        retireBinding()
        endpoints.keys.forEach(::requestEndpointExit)
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
            if (record && !lease.authenticate(endpoint)) throw CancellationException("fixture_hello_retired")
        })
    }

    private fun requestExit(endpoint: IBinder) = transact(endpoint, FixtureRuntimeProtocol.EXIT, true, write = {})

    private fun transact(endpoint: IBinder, code: Int, oneWay: Boolean, write: Parcel.() -> Unit,
        read: (Parcel) -> Unit = {}) {
        val data = Parcel.obtain(); val reply = if (oneWay) null else Parcel.obtain()
        try {
            data.writeInterfaceToken(FixtureRuntimeProtocol.DESCRIPTOR)
            data.writeInt(FixtureRuntimeProtocol.VERSION); data.writeString(nonce); data.write()
            val sent = if (code == FixtureRuntimeProtocol.START) lease.dispatchStart(endpoint,
                { current() && now() < deadlineMs - FixtureRuntimeProtocol.DEATH_ACK_MS }) {
                endpoint.transact(code, data, reply, if (oneWay) IBinder.FLAG_ONEWAY else 0)
            } else endpoint.transact(code, data, reply, if (oneWay) IBinder.FLAG_ONEWAY else 0)
            check(sent) { "fixture_transaction_rejected" }
            if (reply != null) { reply.readException(); read(reply) }
        } finally { reply?.recycle(); data.recycle() }
    }

    private fun retireBinding() {
        authority.retire(); lease.retire()
        requestUnbind()
        notifyReleased()
    }
    private fun requestUnbind() {
        if (!lease.bindWasAttempted) { unbindReceipt.complete(true); return }
        if (lease.claimUnbind()) executeCleanup {
            var success = false
            try { context.unbindService(connection); success = true }
            catch (_: Exception) { /* False/throwing bind can have no registered dispatcher. */ }
            finally { lease.finishUnbind(success); unbindReceipt.complete(success); notifyReleased() }
        }
    }
    private fun requestEndpointExit(endpoint: IBinder) {
        if (!cleanupEnqueued.add(endpoint)) return
        executeCleanup {
            // Retire AUTO_CREATE before EXIT. This wait is never on the UI/deadline thread.
            unbindReceipt.get(FixtureRuntimeProtocol.HANDSHAKE_MS, TimeUnit.MILLISECONDS)
            if (endpoint != lease.firstEndpoint || !authority.verified) authenticate(endpoint, record = false)
            requestExit(endpoint)
        }
    }
    private fun executeCleanup(action: () -> Unit) {
        val task = Runnable { runCatching(action) }
        try { ipc.execute(task) }
        catch (_: RejectedExecutionException) {
            // A late callback after a pre-START retirement still gets cleanup, never another START.
            Thread(task, "WalkSafeFixtureLateCleanup").apply { isDaemon = true }.start()
        }
    }
    private fun connectionFailed(reason: String) {
        retireBinding()
        val error = IllegalStateException(reason)
        connected.completeExceptionally(error)
        result.completeExceptionally(error)
        endpoints.keys.forEach(::requestEndpointExit)
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
    private fun notifyReleased() {
        if (lease.releaseProof != FixtureRuntimeReleaseProof.NONE && releaseNotified.compareAndSet(false, true)) {
            onReleased()
            ipc.shutdown()
        }
    }
    private fun now() = SystemClock.elapsedRealtime()
}

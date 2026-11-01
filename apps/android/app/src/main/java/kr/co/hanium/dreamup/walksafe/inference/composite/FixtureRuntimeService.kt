package kr.co.hanium.dreamup.walksafe.inference.composite

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledFuture

/** Native code lives only here. Binder/exit/watchdog lanes never wait for native initialization. */
class FixtureRuntimeService : Service() {
    private val attempt = FixtureRuntimeAttemptGate()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "WalkSafeFixtureNative") }
    private val watchdog = ScheduledThreadPoolExecutor(1) { Thread(it, "WalkSafeFixtureChildDeadline") }.apply {
        removeOnCancelPolicy = true
    }
    @Volatile private var nonce: String? = null
    @Volatile private var deadlineMs = 0L
    @Volatile private var parentPid = -1
    private var parentCallback: IBinder? = null
    private var admissionWatch: ScheduledFuture<*>? = null
    private val parentDeath = IBinder.DeathRecipient { exitWorker() }

    override fun onCreate() {
        super.onCreate()
        admissionWatch = watchdog.schedule(::exitWorker, FixtureRuntimeProtocol.HANDSHAKE_MS, TimeUnit.MILLISECONDS)
    }

    override fun onBind(intent: Intent?): IBinder {
        val requested = intent?.getStringExtra("nonce")
        val deadline = intent?.getLongExtra("deadline", 0L) ?: 0L
        require(FixtureRuntimeProtocol.validNonce(requested) && deadline - now() in 1L..300_000L)
        val previous = nonce
        require(previous == null || previous == requested)
        nonce = requested
        deadlineMs = deadline
        return endpoint
    }

    private val endpoint = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in FixtureRuntimeProtocol.HELLO..FixtureRuntimeProtocol.EXIT)
                return super.onTransact(code, data, reply, flags)
            if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("Fixture caller UID mismatch")
            require(data.dataSize() <= 2048)
            data.enforceInterface(FixtureRuntimeProtocol.DESCRIPTOR)
            require(data.readInt() == FixtureRuntimeProtocol.VERSION)
            val requested = requireNotNull(data.readString())
            require(requested == nonce && FixtureRuntimeProtocol.validNonce(requested))
            require(isWorkerProcess()) { "Fixture worker process mismatch" }
            when (code) {
                FixtureRuntimeProtocol.HELLO -> {
                    require(data.dataAvail() == 0 && attempt.hello(requested) && now() < deadlineMs)
                    parentPid = Binder.getCallingPid()
                    require(parentPid > 0 && parentPid != Process.myPid())
                    admissionWatch?.cancel(false)
                    admissionWatch = watchdog.schedule(::exitWorker, FixtureRuntimeProtocol.HANDSHAKE_MS, TimeUnit.MILLISECONDS)
                    requireNotNull(reply).apply {
                        writeNoException(); writeInt(FixtureRuntimeProtocol.VERSION); writeString(requested)
                        writeInt(Process.myPid()); writeInt(Process.myUid())
                        writeString(FixtureRuntimeProtocol.processName(this@FixtureRuntimeService))
                    }
                    watchdog.schedule(::exitWorker, (deadlineMs - now()).coerceAtLeast(1L), TimeUnit.MILLISECONDS)
                }
                FixtureRuntimeProtocol.START -> {
                    require((Binder.getCallingPid() == parentPid || Binder.getCallingPid() == 0 && flags and IBinder.FLAG_ONEWAY != 0) &&
                        attempt.start(requested) && now() < deadlineMs)
                    admissionWatch?.cancel(false)
                    val binding = requireNotNull(data.readString())
                    require(FixtureRuntimeProtocol.validNonce(binding))
                    val primary = FixtureRuntimeProtocol.candidate(data.readString(), data.readInt())
                    val auxiliary = FixtureRuntimeProtocol.candidate(data.readString(), data.readInt())
                    val candidate = JointRuntimeCandidate(primary, auxiliary, data.readLong(), data.readLong())
                    require(candidate.primaryIntervalMs in 100L..600L && candidate.auxiliaryIntervalMs in 100L..600L)
                    val callback = requireNotNull(data.readStrongBinder())
                    require(data.dataAvail() == 0)
                    // Parent death is observed before any native allocation is queued.
                    callback.linkToDeath(parentDeath, 0)
                    parentCallback = callback
                    worker.execute {
                        FixtureRuntimeWorker(applicationContext, candidate, binding, deadlineMs,
                            progress = { sendStage(callback, requested, it) },
                            finished = { evidence, reason, nativeClosed -> sendResult(callback, requested, evidence, reason, nativeClosed) }).run()
                    }
                }
                FixtureRuntimeProtocol.EXIT -> {
                    require(data.dataAvail() == 0 && (Binder.getCallingPid() == parentPid ||
                        Binder.getCallingPid() == 0 && flags and IBinder.FLAG_ONEWAY != 0) && attempt.accepts(requested))
                    exitWorker()
                }
            }
            return true
        }
    }

    private fun sendStage(callback: IBinder, value: String, stage: FixtureRuntimeStage) = send(callback,
        FixtureRuntimeProtocol.STAGE) { writeString(value); writeString(stage.token) }

    private fun sendResult(callback: IBinder, value: String, evidence: JointFixtureRuntimeEvidence?,
        reason: String, closed: Boolean) {
        val encoded = evidence?.let(JointRuntimeChoiceCodec::encodeEvidence)
        send(callback, FixtureRuntimeProtocol.RESULT) {
            writeString(value); writeInt(if (closed) 1 else 0)
            writeString(reason.take(FixtureRuntimeProtocol.MAX_REASON_CHARS)); writeString(encoded)
        }
    }

    private fun send(callback: IBinder, code: Int, write: Parcel.() -> Unit) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(FixtureRuntimeProtocol.CALLBACK_DESCRIPTOR)
            data.writeInt(FixtureRuntimeProtocol.VERSION); data.write()
            if (!callback.transact(code, data, null, IBinder.FLAG_ONEWAY)) exitWorker()
        } catch (_: Exception) { exitWorker() } finally { data.recycle() }
    }

    override fun onUnbind(intent: Intent?): Boolean { exitWorker(); return false }
    override fun onDestroy() { exitWorker(); super.onDestroy() }

    private fun isWorkerProcess() = FixtureRuntimeProtocol.processName(this) == packageName + FixtureRuntimeProtocol.PROCESS_SUFFIX
    private fun exitWorker() {
        // Never use a received PID; this component may terminate only its own private process.
        if (isWorkerProcess()) Process.killProcess(Process.myPid())
    }
    private fun now() = SystemClock.elapsedRealtime()
}

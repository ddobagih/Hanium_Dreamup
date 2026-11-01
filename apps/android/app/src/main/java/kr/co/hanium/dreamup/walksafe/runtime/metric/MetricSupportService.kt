package kr.co.hanium.dreamup.walksafe.runtime.metric

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import com.google.ar.core.Config
import com.google.ar.core.Session
import kr.co.hanium.dreamup.walksafe.BuildConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One non-resumed Session in a disposable sibling process; control never joins native work. */
class MetricSupportService : Service() {
    private val attempt = MetricSupportAttemptGate()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "WalkSafeMetricSupportNative") }
    private val watchdog = ScheduledThreadPoolExecutor(1) { Thread(it, "WalkSafeMetricSupportDeadline") }.apply {
        removeOnCancelPolicy = true
    }
    private var admissionWatch: ScheduledFuture<*>? = null
    @Volatile private var nonce: String? = null
    @Volatile private var deadlineMs = 0L
    @Volatile private var parentPid = -1
    @Volatile private var parentCallback: IBinder? = null
    @Volatile private var nativeBudgetMs = MetricSupportProtocol.NATIVE_MS
    @Volatile private var fault = MetricSupportProtocol.FAULT_NONE
    private val parentDeath = IBinder.DeathRecipient { exitWorker() }

    override fun onCreate() {
        super.onCreate()
        // A bad manifest must never turn a watchdog into a main-process kill switch.
        if (isWorkerProcess()) admissionWatch = watchdog.schedule(::exitWorker,
            MetricSupportProtocol.HANDSHAKE_MS, TimeUnit.MILLISECONDS)
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (!isWorkerProcess()) return null
        val requested = intent?.getStringExtra("nonce")
        val until = intent?.getLongExtra("deadline", 0L) ?: 0L
        val requestedFault = intent?.getIntExtra("fault", MetricSupportProtocol.FAULT_NONE) ?: MetricSupportProtocol.FAULT_NONE
        val budget = intent?.getLongExtra("nativeBudget", MetricSupportProtocol.NATIVE_MS) ?: MetricSupportProtocol.NATIVE_MS
        if (!MetricSupportProtocol.validNonce(requested) || until - now() !in 1L..20_000L ||
            !MetricSupportProtocol.validFault(requestedFault, BuildConfig.DEBUG) ||
            budget !in 1L..MetricSupportProtocol.NATIVE_MS ||
            !BuildConfig.DEBUG && budget != MetricSupportProtocol.NATIVE_MS || nonce != null && nonce != requested) return null
        nonce = requested; deadlineMs = until; fault = requestedFault; nativeBudgetMs = budget
        return endpoint
    }

    private val endpoint = object : Binder() {
        @Synchronized
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in MetricSupportProtocol.HELLO..MetricSupportProtocol.EXIT)
                return super.onTransact(code, data, reply, flags)
            require(isWorkerProcess()) { "metric_support_process_mismatch" }
            if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("metric_support_uid_mismatch")
            require(data.dataSize() <= MetricSupportProtocol.MAX_PARCEL_BYTES)
            data.enforceInterface(MetricSupportProtocol.DESCRIPTOR)
            require(data.readInt() == MetricSupportProtocol.VERSION)
            val value = requireNotNull(data.readString())
            require(MetricSupportProtocol.validNonce(value) && value == nonce)
            val callerPid = Binder.getCallingPid()
            when (code) {
                MetricSupportProtocol.HELLO -> {
                    val callback = requireNotNull(data.readStrongBinder())
                    require(data.dataAvail() == 0 && callerPid != Process.myPid() && now() < deadlineMs &&
                        attempt.hello(value, callerPid))
                    if (parentCallback == null) {
                        callback.linkToDeath(parentDeath, 0)
                        parentCallback = callback
                        parentPid = callerPid
                    } else require(parentCallback == callback)
                    admissionWatch?.cancel(false)
                    admissionWatch = watchdog.schedule(::exitWorker,
                        minOf(MetricSupportProtocol.HANDSHAKE_MS, deadlineMs - now()).coerceAtLeast(1L), TimeUnit.MILLISECONDS)
                    requireNotNull(reply).apply {
                        writeNoException(); writeInt(MetricSupportProtocol.VERSION); writeString(value)
                        writeInt(Process.myPid()); writeInt(Process.myUid())
                        writeString(MetricSupportProtocol.processName(this@MetricSupportService))
                    }
                }
                MetricSupportProtocol.QUERY -> {
                    require(data.dataAvail() == 0 && (callerPid == parentPid ||
                        callerPid == 0 && flags and IBinder.FLAG_ONEWAY != 0) && now() < deadlineMs &&
                        attempt.start(value, parentPid))
                    admissionWatch?.cancel(false)
                    watchdog.schedule(::exitWorker, minOf(nativeBudgetMs, deadlineMs - now()).coerceAtLeast(1L), TimeUnit.MILLISECONDS)
                    worker.execute { queryNative(requireNotNull(parentCallback), value) }
                    reply?.writeNoException()
                }
                MetricSupportProtocol.EXIT -> {
                    require(data.dataAvail() == 0 && (callerPid == parentPid ||
                        callerPid == 0 && flags and IBinder.FLAG_ONEWAY != 0) && attempt.accepts(value, parentPid))
                    exitWorker()
                }
            }
            return true
        }
    }

    private fun queryNative(callback: IBinder, value: String) {
        if (!isWorkerProcess()) return
        var session: Session? = null
        var supported: Boolean? = null
        var failure: MetricSupportFailure? = null
        try {
            if (fault == MetricSupportProtocol.FAULT_CREATE_HANG) hangForFault()
            // Fault paths are accepted only by debug APKs and never call the camera/native SDK.
            if (fault == MetricSupportProtocol.FAULT_NONE) session = Session(applicationContext)
            if (fault == MetricSupportProtocol.FAULT_QUERY_HANG) hangForFault()
            supported = when (fault) {
                MetricSupportProtocol.FAULT_SUPPORTED -> true
                MetricSupportProtocol.FAULT_UNSUPPORTED -> false
                MetricSupportProtocol.FAULT_QUERY_ERROR -> throw IllegalStateException("metric_support_test_query")
                MetricSupportProtocol.FAULT_NONE -> requireNotNull(session).isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                else -> false
            }
        } catch (_: Throwable) { failure = MetricSupportFailure.NATIVE_FAILED }
        finally {
            try {
                if (fault == MetricSupportProtocol.FAULT_CLOSE_HANG) hangForFault()
                session?.close()
            } catch (_: Throwable) { failure = MetricSupportFailure.CLOSE_FAILED }
        }
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(MetricSupportProtocol.CALLBACK_DESCRIPTOR)
            data.writeInt(MetricSupportProtocol.VERSION); data.writeString(value)
            data.writeInt(if (failure != null) MetricSupportProtocol.FAILED else
                if (supported == true) MetricSupportProtocol.SUPPORTED else MetricSupportProtocol.UNSUPPORTED)
            data.writeString(failure?.name)
            callback.transact(MetricSupportProtocol.RESULT, data, reply, 0)
        } catch (_: Exception) { /* Parent death/timeout cannot retain the worker. */ }
        finally { reply.recycle(); data.recycle(); exitWorker() }
    }

    private fun hangForFault() { check(BuildConfig.DEBUG); CountDownLatch(1).await() }
    override fun onUnbind(intent: Intent?): Boolean { exitWorker(); return false }
    override fun onDestroy() {
        exitWorker()
        // Relevant only for a manifest mistake: never allocate/kill native in the main process.
        worker.shutdownNow(); watchdog.shutdownNow()
        super.onDestroy()
    }
    private fun isWorkerProcess() = MetricSupportProtocol.processName(this) == packageName + MetricSupportProtocol.PROCESS_SUFFIX
    private fun exitWorker() { if (isWorkerProcess()) Process.killProcess(Process.myPid()) }
    private fun now() = SystemClock.elapsedRealtime()
}

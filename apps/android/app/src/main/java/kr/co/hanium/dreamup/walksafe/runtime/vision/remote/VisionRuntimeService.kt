package kr.co.hanium.dreamup.walksafe.runtime.vision.remote

import android.app.ActivityManager
import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.inference.PrimaryTensorEngines
import kr.co.hanium.dreamup.walksafe.inference.TwoModelRuntimeConfig
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamModelContract
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamNativeBridge
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Only this process constructs native engines. Its Binder/watchdog lanes never run native code. */
class VisionRuntimeService : Service() {
    private val lock = Any()
    private val primary = Executors.newSingleThreadExecutor { Thread(it, "VisionPrimaryNative") }
    private val auxiliary = Executors.newSingleThreadExecutor { Thread(it, "VisionFastSamNative") }
    private val watch = ScheduledThreadPoolExecutor(1) { Thread(it, "VisionNativeWatchdog") }.apply { removeOnCancelPolicy = true }
    private val models = LinkedHashMap<String, Model>()
    private val requests = ConcurrentHashMap<Long, Long>()
    private var nonce: String? = null
    private var owner = ""
    private var epoch = 1L
    private var parentPid = -1
    private var callback: IBinder? = null
    private var parked = false
    private var parking = false
    private var awaitingAdoption = false
    private var parkToken: Any? = null
    private var parkDeadlineMs = 0L
    private var parkWatch: java.util.concurrent.ScheduledFuture<*>? = null
    private var parkWatchRevision = 0L
    private var retired = false
    @Volatile private var fault: String? = null
    @Volatile private var fakeEngine = false
    private val parentDeath = IBinder.DeathRecipient { exitSelf() }
    private var admissionWatch: java.util.concurrent.ScheduledFuture<*>? = null
    private class Model(val id: String, val spec: VisionModelSpec) { @Volatile var engine: VisionNativeEngine? = null; var busy = false }

    override fun onCreate() {
        super.onCreate()
        admissionWatch = watch.schedule(::exitSelf, VisionRuntimeProtocol.HANDSHAKE_MS, TimeUnit.MILLISECONDS)
    }
    override fun onBind(intent: Intent?): IBinder {
        require(Build.VERSION.SDK_INT >= 31 && isChild())
        return endpoint
    }

    private val endpoint = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in VisionRuntimeProtocol.HELLO..VisionRuntimeProtocol.ADOPT) return super.onTransact(code, data, reply, flags)
            require(Build.VERSION.SDK_INT >= 31 && isChild())
            require(Binder.getCallingUid() == Process.myUid())
            val callerPid = Binder.getCallingPid()
            val oneWayExit = code == VisionRuntimeProtocol.EXIT && flags and IBinder.FLAG_ONEWAY != 0 && callerPid == 0
            require(callerPid > 0 || oneWayExit)
            require(data.dataSize() <= VisionRuntimeProtocol.MAX_CONTROL_BYTES)
            data.enforceInterface(VisionRuntimeProtocol.DESCRIPTOR)
            require(data.readInt() == VisionRuntimeProtocol.VERSION)
            val token = requireNotNull(data.readString())
            require(VisionRuntimeProtocol.TOKEN.matches(token))
            val requestEpoch = data.readLong()
            val id = data.readLong()
            val deadline = data.readLong()
            if (code == VisionRuntimeProtocol.HELLO) {
                require(flags and IBinder.FLAG_ONEWAY == 0)
                val requestedOwner = requireNotNull(data.readString())
                val parent = requireNotNull(data.readStrongBinder())
                require(data.dataAvail() == 0 && now() < deadline && deadline - now() <= VisionRuntimeProtocol.HANDSHAKE_MS)
                require(VisionRuntimeProtocol.KEY.matches(requestedOwner) && requestEpoch == 1L)
                val caller = Binder.getCallingPid()
                require(getSystemService(ActivityManager::class.java).runningAppProcesses?.any {
                    it.pid == caller && it.uid == Process.myUid() && it.processName == applicationInfo.processName
                } == true) { "vision_parent_process_mismatch" }
                val identity = requireNotNull(VisionProcessIdentity.captureSelf(this@VisionRuntimeService)) {
                    "vision_child_process_identity_unavailable"
                }
                require(identity.pid == Process.myPid() && identity.uid == Process.myUid() &&
                    identity.processName == packageName + VisionRuntimeProtocol.PROCESS_SUFFIX)
                synchronized(lock) {
                    require(!retired && nonce == null && callback == null && parentPid == -1)
                    parent.linkToDeath(parentDeath, 0); callback = parent
                    nonce = token; owner = requestedOwner; parentPid = caller
                }
                admissionWatch?.cancel(false)
                reply!!.writeNoException(); reply.writeInt(VisionRuntimeProtocol.VERSION); reply.writeString(token)
                reply.writeInt(Process.myPid()); reply.writeInt(Process.myUid()); reply.writeString(Application.getProcessName())
                reply.writeLong(identity.startTicks); reply.writeString(identity.bootIdentity)
                return true
            }
            synchronized(lock) { require(!retired && VisionRuntimeProtocol.acceptsCaller(code, flags, callerPid,
                Binder.getCallingUid(), parentPid, Process.myUid(), token == nonce, requestEpoch == epoch, callback != null)) }
            if (code == VisionRuntimeProtocol.EXIT) {
                require(data.dataAvail() == 0)
                reply?.writeNoException(); exitSelf(); return true
            }
            require(now() < deadline) { "vision_operation_expired" }
            when (code) {
                VisionRuntimeProtocol.OPEN -> open(id, requestEpoch, deadline, VisionRuntimeProtocol.readSpec(data))
                VisionRuntimeProtocol.INVOKE -> {
                    val model = synchronized(lock) { requireNotNull(models[data.readString()]) }
                    val width = data.readInt(); val height = data.readInt()
                    val purpose = VisionPurpose.valueOf(requireNotNull(data.readString()))
                    require(deadline - now() <= VisionRuntimeProtocol.operationBudget(purpose))
                    synchronized(lock) {
                        require(!parked && !parking && model.engine != null && models.values.none { it.spec.role == model.spec.role && it.busy })
                        model.busy = true
                    }
                    var memory: VisionSharedMemory? = null
                    try {
                        memory = VisionSharedMemory.child(data, VisionRuntimeProtocol.inputBytes(model.spec, width, height),
                            VisionRuntimeProtocol.outputBytes(model.spec))
                        invoke(id, requestEpoch, deadline, model, memory, width, height)
                    } catch (error: Throwable) {
                        memory?.close(); synchronized(lock) { model.busy = false }; throw error
                    }
                }
                VisionRuntimeProtocol.CLOSE -> close(id, requestEpoch, deadline, requireNotNull(data.readString()))
                VisionRuntimeProtocol.PARK -> park(id, requestEpoch, deadline)
                VisionRuntimeProtocol.TRANSFER -> transfer(id, requestEpoch, deadline, requireNotNull(data.readString()))
                VisionRuntimeProtocol.ADOPT -> {
                    synchronized(lock) {
                        require(awaitingAdoption && parked && now() < parkDeadlineMs)
                        awaitingAdoption = false; parked = false; parkToken = null
                        parkWatch?.cancel(false); parkWatch = null
                    }
                    result(id, requestEpoch, true, "{}")
                }
                VisionRuntimeProtocol.FAULT -> {
                    require(BuildConfig.DEBUG) { "vision_faults_disabled" }
                    val value = requireNotNull(data.readString())
                    require(value in setOf("OPEN_HANG", "INVOKE_HANG", "CLOSE_HANG", "DIE", "CLEAR", "FAKE_ENGINE", "DROP_TRANSFER_REPLY", "ASSERT_NO_MODELS"))
                    if (value == "ASSERT_NO_MODELS") synchronized(lock) { require(models.isEmpty()) { "native_open_preceded_journal_ack" } }
                    if (value == "FAKE_ENGINE") fakeEngine = true
                    fault = value.takeUnless { it == "CLEAR" }
                    if (value == "DIE") exitSelf() else result(id, requestEpoch, true, "{}")
                }
            }
            require(data.dataAvail() == 0)
            reply!!.writeNoException()
            return true
        }
    }

    private fun open(id: Long, atEpoch: Long, deadline: Long, spec: VisionModelSpec) {
        require(deadline - now() <= VisionRuntimeProtocol.openBudget(spec))
        val model = synchronized(lock) {
            require(!parked && !parking && models.values.none { it.spec.role == spec.role && it.spec.modelKey == spec.modelKey }) { "ALREADY_OPEN" }
            require(models.values.count { it.spec.role == spec.role } < if (spec.role == VisionRole.PRIMARY) 2 else 1)
            Model(UUID.randomUUID().toString(), spec).also { models[it.id] = it }
        }
        execute(id, atEpoch, deadline, model.spec.role) {
            validatePinnedSpec(spec)
            hang("OPEN_HANG")
            verifyAsset(spec)
            check(now() < deadline)
            model.engine = if (BuildConfig.DEBUG && fakeEngine) debugEngine(spec)
                else if (spec.role == VisionRole.PRIMARY) PrimaryTensorEngines.createLocal(applicationContext, spec)
                else FastSamNativeBridge.createLocal(applicationContext, spec)
            val runtime = VisionRuntimeCodec.validateRuntimeJson(requireNotNull(model.engine).runtimeJson)
            JSONObject().put("modelInstanceId", model.id).put("runtimeJson", runtime).toString()
        }
    }
    private fun invoke(id: Long, atEpoch: Long, deadline: Long, model: Model, memory: VisionSharedMemory, width: Int, height: Int) {
        execute(id, atEpoch, deadline, model.spec.role) {
            try {
                hang("INVOKE_HANG")
                val value = requireNotNull(model.engine).invoke(requireNotNull(memory.input), memory.outputs, width, height)
                require(listOf(value.preprocessingMs, value.inferenceMs, value.outputReadMs).all { it.isFinite() && it >= 0 })
                JSONObject().put("runtimeJson", VisionRuntimeCodec.validateRuntimeJson(value.runtimeJson))
                    .put("preprocessingMs", value.preprocessingMs).put("inferenceMs", value.inferenceMs)
                    .put("outputReadMs", value.outputReadMs).toString()
            } finally { memory.close(); synchronized(lock) { model.busy = false } }
        }
    }
    private fun close(id: Long, atEpoch: Long, deadline: Long, modelId: String) {
        require(deadline - now() <= VisionRuntimeProtocol.CLOSE_MS)
        val model = synchronized(lock) { requireNotNull(models[modelId]) }
        execute(id, atEpoch, deadline, model.spec.role) {
            hang("CLOSE_HANG")
            requireNotNull(model.engine).close()
            synchronized(lock) { model.engine = null; models.remove(model.id) }
            JSONObject().put("modelInstanceId", model.id).put("nativeCloseConfirmed", true).toString()
        }
    }
    private fun park(id: Long, atEpoch: Long, deadline: Long) {
        require(deadline - now() <= VisionRuntimeProtocol.CLOSE_MS)
        val ticket = synchronized(lock) {
            require(!parked && !parking); parking = true; parkDeadlineMs = deadline
            Any().also { parkToken = it }
        }
        armParkDeadline(ticket, deadline)
        arm(id, atEpoch, deadline)
        val remaining = AtomicInteger(2)
        val barrier = Runnable {
            if (remaining.decrementAndGet() == 0) {
                synchronized(lock) { if (epoch != atEpoch || retired) return@Runnable; parked = true; parking = false }
                result(id, atEpoch, true, "{}")
            }
        }
        primary.execute(barrier); auxiliary.execute(barrier)
    }
    private fun transfer(id: Long, atEpoch: Long, deadline: Long, newOwner: String) {
        require(deadline - now() <= VisionRuntimeProtocol.CLOSE_MS)
        require(VisionRuntimeProtocol.KEY.matches(newOwner))
        val payload = synchronized(lock) {
            require(parked && !parking && requests.isEmpty() && deadline <= parkDeadlineMs)
            parkDeadlineMs = deadline
            epoch = Math.incrementExact(epoch); owner = newOwner; awaitingAdoption = true
            JSONObject().put("ownerEpoch", epoch).put("ownerId", owner).put("handles", JSONArray().also { array ->
                models.values.forEach { model -> array.put(JSONObject().put("modelInstanceId", model.id)
                    .put("role", model.spec.role.name).put("modelKey", model.spec.modelKey)
                    .put("runtimeJson", requireNotNull(model.engine).runtimeJson)) }
            }).toString()
        }
        synchronized(lock) { parkToken?.let { armParkDeadline(it, parkDeadlineMs) } }
        // The original PARK ticket/deadline spans transfer and adoption; no transition extends it.
        // This response authenticates the old epoch that requested the transfer, not an old watchdog.
        if (!(BuildConfig.DEBUG && fault == "DROP_TRANSFER_REPLY")) send(id, atEpoch, true, payload)
    }
    private fun execute(id: Long, atEpoch: Long, deadline: Long, role: VisionRole, operation: () -> String) {
        arm(id, atEpoch, deadline)
        (if (role == VisionRole.PRIMARY) primary else auxiliary).execute {
            try { result(id, atEpoch, true, operation()) }
            catch (error: Throwable) {
                result(id, atEpoch, false, JSONObject().put("reason", error.javaClass.simpleName + ":" + error.message.orEmpty().take(120)).toString())
                exitSelf() // A failed native operation cannot leave a half-built reusable owner.
            }
        }
    }
    private fun arm(id: Long, atEpoch: Long, deadline: Long) {
        require(deadline > now() && requests.putIfAbsent(id, atEpoch) == null)
        watch.schedule({ synchronized(lock) {
            if (!retired && epoch == atEpoch && requests[id] == atEpoch) exitSelf()
        } }, (deadline - now()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
    }
    private fun armParkDeadline(ticket: Any, deadline: Long) {
        val plan = synchronized(lock) {
            if (retired || parkToken !== ticket) return
            parkWatch?.cancel(false)
            parkWatchRevision = Math.incrementExact(parkWatchRevision)
            parkWatchRevision to minOf(deadline, parkDeadlineMs)
        }
        try { val timer = watch.schedule({
            val due = synchronized(lock) { !retired && parkToken === ticket && (parking || parked || awaitingAdoption) }
            if (due) {
                if (now() < plan.second) armParkDeadline(ticket, plan.second) else {
                    val expire = synchronized(lock) {
                        if (!retired && parkToken === ticket && (parking || parked || awaitingAdoption) && now() >= plan.second) {
                            retired = true; true
                        } else false
                    }
                    if (expire) exitSelf()
                }
            }
        }, (plan.second - now()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
          synchronized(lock) {
              if (retired || parkToken !== ticket || parkWatchRevision != plan.first) timer.cancel(false) else parkWatch = timer
          }
        }
        catch (_: Throwable) { exitSelf() }
    }
    private fun result(id: Long, atEpoch: Long, success: Boolean, payload: String) {
        val current = synchronized(lock) { !retired && epoch == atEpoch && now() >= 0 }
        requests.remove(id, atEpoch)
        if (current) send(id, atEpoch, success, payload)
    }
    private fun send(id: Long, atEpoch: Long, success: Boolean, payload: String) {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(VisionRuntimeProtocol.CALLBACK); data.writeInt(VisionRuntimeProtocol.VERSION)
            data.writeString(nonce); data.writeLong(atEpoch); data.writeLong(id); data.writeInt(if (success) 1 else 0)
            data.writeString(payload)
            require(data.dataSize() <= VisionRuntimeProtocol.MAX_CONTROL_BYTES)
            require(requireNotNull(callback).transact(VisionRuntimeProtocol.RESULT, data, reply, 0)); reply.readException()
        } catch (_: Throwable) { exitSelf() } finally { data.recycle(); reply.recycle() }
    }
    private fun validatePinnedSpec(spec: VisionModelSpec) {
        VisionRuntimeProtocol.validateSpec(spec)
        if (spec.role == VisionRole.FASTSAM) {
            require(spec.modelKey == FastSamModelContract.MODEL_ID && spec.assetPath == FastSamModelContract.ASSET)
            FastSamModelContract.validate(spec.assetSha256, spec.inputShape, spec.outputShapes, true)
        } else {
            val config = TwoModelRuntimeConfig.load(applicationContext)
            val model = listOfNotNull(config.unifiedWalksafe, config.customTactile, config.cocoGeneral)
                .singleOrNull { it.asset == spec.assetPath } ?: error("vision_primary_asset_not_allowed")
            require(model.artifactSha256 == spec.assetSha256 && spec.inputShape.contentEquals(intArrayOf(1, model.inputSize, model.inputSize, 3)))
            require(spec.outputShapes.size == 1 && spec.outputShapes[0].contentEquals(model.outputTensorShape()))
            val expected = when (model) { config.unifiedWalksafe -> TwoModelRuntimeConfig.UNIFIED_MODEL_KEY
                config.customTactile -> "custom_tactile"; else -> "coco_general" }
            require(spec.modelKey == expected)
        }
    }
    private fun verifyAsset(spec: VisionModelSpec) {
        val digest = MessageDigest.getInstance("SHA-256")
        assets.open(spec.assetPath).use { input -> val block = ByteArray(65536)
            while (true) { val count = input.read(block); if (count < 0) break; digest.update(block, 0, count) } }
        require(digest.digest().joinToString("") { "%02x".format(it) } == spec.assetSha256)
    }
    private fun hang(value: String) { if (BuildConfig.DEBUG && fault == value) while (true) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000_000) }
    /** Transport fault tests only; fixture positive/ground-truth gates reject these zero outputs. */
    private fun debugEngine(spec: VisionModelSpec): VisionNativeEngine {
        check(BuildConfig.DEBUG)
        val options = VisionRuntimeCodec.decodeOptions(spec.runtimeJson)
        val json = JSONObject().put("requestedDelegate", options.delegate).put("activeDelegate", options.delegate)
            .put("numThreads", options.numThreads).put("fallbackUsed", false).put("fallbackReason", JSONObject.NULL)
            .put("gpuPrecisionLossAllowed", options.gpuPrecisionLossAllowed).put("gpuSerializationCacheStatus", "debug_fake")
            .put("modelOwnerPid", Process.myPid()).put("modelOwnerThreadId", Thread.currentThread().id)
            .put("nativeRuntimeVersion", "debug_transport_fake").put("loadMs", 0.0).toString()
        return object : VisionNativeEngine {
            override val runtimeJson = json
            override fun invoke(input: java.nio.ByteBuffer, outputs: Array<java.nio.ByteBuffer>, width: Int, height: Int): VisionInvokeResult {
                outputs.forEach { output -> output.clear(); while (output.remaining() >= 4) output.putFloat(0f); output.rewind() }
                return VisionInvokeResult(json, 0.0, 0.0, 0.0)
            }
            override fun close() = Unit
        }
    }
    override fun onUnbind(intent: Intent?): Boolean { exitSelf(); return false }
    override fun onDestroy() { exitSelf(); super.onDestroy() }
    private fun now() = SystemClock.elapsedRealtime()
    private fun isChild() = Application.getProcessName() == packageName + VisionRuntimeProtocol.PROCESS_SUFFIX
    private fun exitSelf() { synchronized(lock) { retired = true }; if (isChild()) Process.killProcess(Process.myPid()) }
}

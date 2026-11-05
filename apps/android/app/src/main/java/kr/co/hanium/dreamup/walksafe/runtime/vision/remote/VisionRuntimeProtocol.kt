package kr.co.hanium.dreamup.walksafe.runtime.vision.remote

import android.os.Parcel
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage

enum class VisionRole { PRIMARY, FASTSAM }
enum class VisionPurpose { LIVE, BENCHMARK, WARMUP, REFERENCE }
enum class VisionNativeFaultReason { NON_FINITE_OUTPUT, OUTPUT_SHAPE_MISMATCH, INVALID_NATIVE_OUTPUT }

data class VisionModelSpec(val role: VisionRole, val modelKey: String, val assetPath: String,
    val assetSha256: String, val runtimeJson: String, val inputShape: IntArray,
    val outputShapes: Array<IntArray>)
data class VisionInvokeResult(val runtimeJson: String, val preprocessingMs: Double,
    val inferenceMs: Double, val outputReadMs: Double)
interface VisionNativeEngine : AutoCloseable {
    val runtimeJson: String
    fun invoke(input: ByteBuffer, outputs: Array<ByteBuffer>, width: Int, height: Int): VisionInvokeResult
    override fun close()
}
data class VisionCohortLease(val cohortId: String, val ownerId: String, val ownerEpoch: Long)
data class VisionModelHandle(val cohortId: String, val modelInstanceId: String, val ownerEpoch: Long,
    val role: VisionRole, val modelKey: String, val runtimeJson: String)
data class VisionCloseResult(val modelInstanceId: String, val nativeCloseConfirmed: Boolean)
data class VisionTransferResult(val lease: VisionCohortLease, val handles: List<VisionModelHandle>)
enum class VisionReleaseProof { NO_NATIVE_START, CHILD_DEATH }
data class VisionReleaseReceipt(val cohortId: String, val childPid: Int,
    val releaseProof: VisionReleaseProof = VisionReleaseProof.CHILD_DEATH)
data class VisionRetirementSnapshot(val lease: VisionCohortLease,
    val actualReleased: CompletionStage<VisionReleaseReceipt>,
    val allocationAvailable: CompletionStage<VisionReleaseReceipt> = actualReleased)
data class VisionPairSnapshot(val lease: VisionCohortLease, val primaryHandle: VisionModelHandle,
    val auxiliaryHandle: VisionModelHandle, val childPid: Int)
data class VisionModelReleaseReceipt(val cohortId: String, val modelInstanceId: String,
    val childPid: Int, val processDeath: Boolean)

/** Actual transport uses this gate immediately before OPEN. Retirement revokes queued work. */
internal class VisionDispatchAuthority {
    private var epoch = 1L
    private var retired = false
    private var nativePossible = false
    @Synchronized fun accepts(value: Long) = !retired && epoch == value
    @Synchronized fun grantOpen(value: Long): Boolean {
        if (!accepts(value)) return false
        nativePossible = true
        return true
    }
    @Synchronized fun retire(value: Long): Boolean {
        if (epoch != value) return false
        retired = true
        return true
    }
    @Synchronized fun transfer(value: Long): Long {
        check(accepts(value))
        epoch = Math.incrementExact(epoch)
        return epoch
    }
    @Synchronized fun provesNoNativeStart() = retired && !nativePossible
    @Synchronized fun nativeStartPossible() = nativePossible
}

/** A semantic failure never completes the independent physical-release receipt exceptionally. */
class VisionRuntimeException(val reason: String, cause: Throwable? = null,
    val releaseReceipt: CompletionStage<VisionReleaseReceipt>? = null,
    val cohortRetired: Boolean = true) : RuntimeException(reason, cause)
/** No lease/native permit was issued to this caller. Availability is never its physical receipt. */
class VisionCohortBusyException(val availabilityReceipt: CompletionStage<VisionReleaseReceipt>) :
    IllegalStateException("vision_cohort_busy_before_lease")
class VisionStaleAuthorityException : IllegalStateException("vision_stale_authority")
class VisionReceiptExpiredException : IllegalStateException("vision_completed_receipt_history_expired")
class VisionJournalException(val outcome: kr.co.hanium.dreamup.walksafe.device.DeviceCheckAuthorityOutcome<*>) :
    IllegalStateException("vision_native_journal_not_committed:${outcome.javaClass.simpleName}")

data class VisionRuntimeOptions @JvmOverloads constructor(val delegate: String, val numThreads: Int,
    val fallbackToCpu: Boolean = false, val gpuPrecisionLossAllowed: Boolean = false,
    val gpuSerializationCacheEnabled: Boolean = true)

/** Wire options and observations use separate JSON objects; neither object grants native authority. */
object VisionRuntimeCodec {
    @JvmStatic fun optionsHash(json: String): String = hashBytes(encodeOptions(decodeOptions(json)))
    /** Hash the original observed JSON; rebuilding a DTO may discard native observation fields. */
    @JvmStatic fun runtimeHash(json: String): String {
        validateRuntimeJson(json)
        return hashBytes(json)
    }
    private fun hashBytes(value: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    @JvmStatic fun encodeOptions(value: VisionRuntimeOptions): String {
        require(value.delegate in setOf("cpu", "gpu", "nnapi") && value.numThreads in 1..4)
        return JSONObject().put("delegate", value.delegate).put("numThreads", value.numThreads)
            .put("fallbackToCpu", value.fallbackToCpu).put("gpuPrecisionLossAllowed", value.gpuPrecisionLossAllowed)
            .put("gpuSerializationCacheEnabled", value.gpuSerializationCacheEnabled).toString()
    }
    @JvmStatic fun decodeOptions(json: String): VisionRuntimeOptions {
        val o = objectJson(json)
        return VisionRuntimeOptions(o.getString("delegate"), o.getInt("numThreads"),
            o.getBoolean("fallbackToCpu"), o.getBoolean("gpuPrecisionLossAllowed"),
            o.getBoolean("gpuSerializationCacheEnabled")).also {
            require(it.delegate in setOf("cpu", "gpu", "nnapi") && it.numThreads in 1..4)
            require(!it.fallbackToCpu) { "remote_hidden_fallback_forbidden" }
        }
    }
    @JvmStatic fun objectJson(json: String): JSONObject {
        require(json.toByteArray(Charsets.UTF_8).size <= VisionRuntimeProtocol.MAX_RUNTIME_JSON)
        return JSONObject(json)
    }
    @JvmStatic fun validateRuntimeJson(json: String): String {
        val o = objectJson(json)
        require(o.getString("requestedDelegate") in setOf("cpu", "gpu", "nnapi"))
        require(o.getString("activeDelegate") in setOf("cpu", "gpu", "nnapi"))
        require(o.getInt("numThreads") in 1..4 && !o.getBoolean("fallbackUsed"))
        require(o.getLong("modelOwnerPid") > 0 && o.getLong("modelOwnerThreadId") > 0)
        require(o.getDouble("loadMs").isFinite() && o.getDouble("loadMs") >= 0)
        return json
    }
}

/** Small scalar control parcels only. Pixel/tensor bytes travel through bounded SharedMemory. */
object VisionRuntimeProtocol {
    const val VERSION = 2
    const val DESCRIPTOR = "walksafe.vision.runtime.v1"
    const val CALLBACK = "walksafe.vision.runtime.callback.v1"
    const val PROCESS_SUFFIX = ":vision_runtime"
    const val HELLO = 1; const val OPEN = 2; const val INVOKE = 3; const val CLOSE = 4
    const val PARK = 5; const val TRANSFER = 6; const val EXIT = 7; const val FAULT = 8; const val ADOPT = 9
    const val RESULT = 1
    const val MAX_CONTROL_BYTES = 16 * 1024
    const val MAX_RUNTIME_JSON = 4096
    const val MAX_INPUT_BYTES = 32 * 1024 * 1024
    const val MAX_OUTPUT_BYTES = 8 * 1024 * 1024
    const val MAX_REQUEST_BYTES = 40 * 1024 * 1024
    const val HANDSHAKE_MS = 5_000L; const val OPEN_MS = 20_000L; const val CLOSE_MS = 5_000L
    private const val FASTSAM_GPU_OPEN_MS = 80_000L
    const val DEATH_MS = 5_000L
    val TOKEN = Regex("[a-f0-9]{64}")
    val KEY = Regex("[a-zA-Z0-9_.-]{1,80}")
    /** Only authenticated EXIT is one-way; Binder deliberately supplies no PID for that call. */
    internal fun acceptsCaller(code: Int, flags: Int, pid: Int, uid: Int, parentPid: Int, expectedUid: Int,
        nonceMatches: Boolean, epochMatches: Boolean, parentBound: Boolean): Boolean {
        if (uid != expectedUid || parentPid <= 0 || !parentBound || !nonceMatches || !epochMatches) return false
        val oneWay = flags and android.os.IBinder.FLAG_ONEWAY != 0
        if (oneWay) return code == EXIT && pid == 0
        return pid > 0 && pid == parentPid
    }
    /** Cold GPU kernel compilation must fit the caller's existing check deadline. */
    fun openBudget(spec: VisionModelSpec): Long =
        if (spec.role == VisionRole.FASTSAM && VisionRuntimeCodec.decodeOptions(spec.runtimeJson).delegate == "gpu")
            FASTSAM_GPU_OPEN_MS else OPEN_MS

    fun operationBudget(purpose: VisionPurpose) = when (purpose) {
        VisionPurpose.LIVE, VisionPurpose.BENCHMARK -> 2_000L
        VisionPurpose.WARMUP -> 15_000L
        VisionPurpose.REFERENCE -> 10_000L
    }
    fun deadline(caller: Long, now: Long, maximum: Long): Long {
        require(caller > now && maximum > 0) { "vision_deadline_expired" }
        return minOf(caller, if (now > Long.MAX_VALUE - maximum) Long.MAX_VALUE else now + maximum)
    }
    fun tensorBytes(shape: IntArray): Int {
        require(shape.size in 1..4 && shape.all { it in 1..100_000 })
        return Math.multiplyExact(shape.fold(1, Math::multiplyExact), 4)
    }
    fun inputBytes(spec: VisionModelSpec, width: Int, height: Int): Int {
        require(width in 1..2048 && height in 1..2048)
        return if (spec.role == VisionRole.FASTSAM) Math.multiplyExact(Math.multiplyExact(width, height), 4)
            else tensorBytes(spec.inputShape)
    }
    fun outputBytes(spec: VisionModelSpec) = spec.outputShapes.map(::tensorBytes).toIntArray()
    fun validateSpec(spec: VisionModelSpec) {
        require(KEY.matches(spec.modelKey) && TOKEN.matches(spec.assetSha256))
        require(spec.assetPath.startsWith("models/") && spec.assetPath.endsWith(".tflite") &&
            !spec.assetPath.contains("..") && spec.assetPath.length <= 160)
        require(spec.inputShape.size == 4 && spec.inputShape[0] == 1 && spec.inputShape[3] == 3)
        require(tensorBytes(spec.inputShape) <= MAX_INPUT_BYTES)
        require(spec.outputShapes.size in 1..2)
        val outputs = outputBytes(spec)
        require(outputs.fold(0, Math::addExact) <= MAX_OUTPUT_BYTES)
        VisionRuntimeCodec.decodeOptions(spec.runtimeJson)
    }
    internal fun writeSpec(p: Parcel, s: VisionModelSpec) {
        p.writeString(s.role.name); p.writeString(s.modelKey); p.writeString(s.assetPath)
        p.writeString(s.assetSha256); p.writeString(s.runtimeJson); p.writeIntArray(s.inputShape)
        p.writeInt(s.outputShapes.size); s.outputShapes.forEach(p::writeIntArray)
    }
    internal fun readSpec(p: Parcel): VisionModelSpec = VisionModelSpec(VisionRole.valueOf(requireNotNull(p.readString())),
        requireNotNull(p.readString()), requireNotNull(p.readString()), requireNotNull(p.readString()),
        requireNotNull(p.readString()), requireNotNull(p.createIntArray()),
        Array(p.readInt().also { require(it in 1..2) }) { requireNotNull(p.createIntArray()) }).also(::validateSpec)
}

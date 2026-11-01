package kr.co.hanium.dreamup.walksafe.inference.unknown

import android.app.Application
import android.content.Context
import android.os.Build
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.*
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Only the isolated child owner may map or create a FastSAM native model. */
object FastSamNativeBridge {
    @JvmStatic fun createLocal(context: Context, spec: VisionModelSpec): VisionNativeEngine {
        require(Build.VERSION.SDK_INT >= 31) { "vision_android_api_unsupported" }
        requireProcess(Build.VERSION.SDK_INT, Application.getProcessName(), context.packageName, false)
        validateSpec(spec)
        val selected = VisionRuntimeCodec.decodeOptions(spec.runtimeJson)
        require(selected.delegate in setOf("cpu", "gpu"))
        val options = FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.valueOf(selected.delegate.uppercase()),
            selected.numThreads, selected.fallbackToCpu, Long.MAX_VALUE, 800L, false,
            selected.gpuPrecisionLossAllowed, selected.gpuSerializationCacheEnabled)
        val engine = TfliteFastSamEngine(context.applicationContext, options)
        try { return LocalEngine(engine, spec) } catch (failure: Throwable) {
            try { engine.close() } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
                throw TfliteFastSamEngine.NativeReleaseFailure(failure)
            }
            throw failure
        }
    }

    @JvmStatic fun requireNativeProcess(context: Context) {
        require(Build.VERSION.SDK_INT >= 31) { "vision_android_api_unsupported" }
        requireProcess(Build.VERSION.SDK_INT, Application.getProcessName(), context.packageName, true)
    }

    @JvmStatic fun requireFixtureProcess(context: Context) {
        require(Build.VERSION.SDK_INT >= 31) { "vision_android_api_unsupported" }
        require(Application.getProcessName() == context.packageName + ":fixture_runtime") { "fastsam_fixture_process_required" }
    }

    internal fun requireProcess(sdk: Int, process: String?, packageName: String, allowFixture: Boolean) {
        require(sdk >= 31) { "vision_android_api_unsupported" }
        require(process == packageName + VisionRuntimeProtocol.PROCESS_SUFFIX ||
            allowFixture && process == packageName + ":fixture_runtime") { "fastsam_native_child_process_required" }
    }

    internal fun spec(options: FastSamRuntimeOptions) = VisionModelSpec(VisionRole.FASTSAM,
        FastSamModelContract.MODEL_ID, FastSamModelContract.ASSET, FastSamModelContract.SHA256,
        VisionRuntimeCodec.encodeOptions(VisionRuntimeOptions(options.requestedBackend.name.lowercase(),
            options.numThreads, options.allowCpuFallback, options.gpuPrecisionLossAllowed,
            options.gpuSerializationCacheEnabled)), intArrayOf(1, 768, 768, 3),
        arrayOf(intArrayOf(1, 37, 12096), intArrayOf(1, 192, 192, 32)))

    internal fun validateSpec(spec: VisionModelSpec) {
        VisionRuntimeProtocol.validateSpec(spec)
        require(spec.role == VisionRole.FASTSAM && spec.modelKey == FastSamModelContract.MODEL_ID &&
            spec.assetPath == FastSamModelContract.ASSET) { "fastsam_role_contract_mismatch" }
        FastSamModelContract.validate(spec.assetSha256, spec.inputShape, spec.outputShapes, true)
        require(VisionRuntimeCodec.decodeOptions(spec.runtimeJson).delegate in setOf("cpu", "gpu"))
    }

    internal class LocalEngine(private val engine: FastSamEngine, private val spec: VisionModelSpec) : VisionNativeEngine {
        private val owner = Thread.currentThread().id
        init { validateSpec(spec) }
        override val runtimeJson: String = encodeObserved(engine.runtime())
        override fun invoke(input: ByteBuffer, outputs: Array<ByteBuffer>, width: Int, height: Int): VisionInvokeResult {
            check(Thread.currentThread().id == owner) { "FastSAM native owner changed" }
            val bytes = VisionRuntimeProtocol.inputBytes(spec, width, height)
            val outputBytes = VisionRuntimeProtocol.outputBytes(spec)
            require(input.remaining() == bytes && outputs.size == outputBytes.size)
            outputs.forEachIndexed { index, output -> require(!output.isReadOnly && output.remaining() == outputBytes[index]) }
            val argb = IntArray(bytes / 4)
            input.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(argb)
            val result = engine.invoke(argb, width, height)
            require(result.ownedTensors.size == outputs.size && result.ownedTensors.indices.all {
                result.ownedTensors[it].size == outputBytes[it] / 4
            })
            require(listOf(result.preprocessingMs, result.inferenceMs, result.outputReadMs).all { it.isFinite() && it >= 0 })
            val copyStarted = System.nanoTime()
            outputs.forEachIndexed { index, output ->
                output.duplicate().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(result.ownedTensors[index])
            }
            return VisionInvokeResult(runtimeJson, result.preprocessingMs, result.inferenceMs,
                result.outputReadMs + (System.nanoTime() - copyStarted) / 1e6)
        }
        override fun close() {
            check(Thread.currentThread().id == owner) { "FastSAM native owner changed" }
            engine.close()
        }
    }

    internal fun encodeObserved(info: FastSamRuntimeInfo): String = VisionRuntimeCodec.validateRuntimeJson(JSONObject()
        .put("requestedDelegate", info.requestedBackend.name.lowercase())
        .put("activeDelegate", info.actualBackend.name.lowercase()).put("numThreads", info.numThreads)
        .put("fallbackUsed", info.fallbackUsed).put("modelOwnerPid", info.modelOwnerProcessId)
        .put("modelOwnerThreadId", info.modelOwnerThreadId).put("loadMs", info.loadMs)
        .put("gpuDelegateAttached", info.gpuDelegateAttached).put("gpuPrecisionLossAllowed", info.gpuPrecisionLossAllowed)
        .put("gpuPrecisionLossRequested", info.gpuPrecisionLossRequested)
        .put("gpuSerializationCacheRequested", info.gpuSerializationCacheRequested)
        .put("gpuSerializationCacheConfigured", info.gpuSerializationCacheConfigured)
        .put("gpuSerializationCacheStatus", info.gpuSerializationCacheStatus)
        .put("gpuSerializationCacheToken", info.gpuSerializationCacheToken ?: JSONObject.NULL)
        .put("gpuSerializationCacheFailureReason", info.gpuSerializationCacheFailureReason ?: JSONObject.NULL)
        .put("gpuInitializationAttempts", info.gpuInitializationAttempts)
        .put("fallbackReason", info.fallbackReason ?: JSONObject.NULL).toString())
}

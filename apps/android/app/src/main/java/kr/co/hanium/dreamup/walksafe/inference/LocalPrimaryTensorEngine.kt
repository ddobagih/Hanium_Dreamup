package kr.co.hanium.dreamup.walksafe.inference

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionInvokeResult
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionModelSpec
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionNativeEngine
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeCodec
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.TensorFlowLite
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory

/** Constructed only through the child factory, on the child's dedicated primary native owner. */
internal class LocalPrimaryTensorEngine(context: Context, private val spec: VisionModelSpec) : VisionNativeEngine {
    init { PrimaryTensorEngines.requireLocalAuthority(context) }
    private val ownerThread = Thread.currentThread()
    private val startedNs = System.nanoTime()
    private val nativeRuntimeVersion = requireNotNull(TensorFlowLite.runtimeVersion()).also { require(it.isNotBlank()) }
    private val recorder = DetectorLoadTimingRecorder()
    private val options = VisionRuntimeCodec.decodeOptions(spec.runtimeJson)
    private val inputSize = spec.inputShape[1]
    private val outputShape = spec.outputShapes.single()
    private val model = recorder.measure(AndroidDetectorLoadPhase.ASSET_INTEGRITY, spec.modelKey) {
        ModelAssetIntegrity.check(spec.assetSha256) { context.assets.open(spec.assetPath) }?.let {
            throw DetectorAssetIntegrityFailure(it, "primary model asset ${it.name.lowercase()}")
        }
        recorder.measure(AndroidDetectorLoadPhase.ASSET_MAP, spec.modelKey) { loadMappedAsset(context, spec.assetPath) }
    }
    private val cache = if (options.delegate == "gpu") {
        GpuSerializationCache.prepare(options.gpuSerializationCacheEnabled) {
            // CompatibilityList is advisory eligibility for OPTIONAL serialization, not native GPU proof.
            // Keep failures inside prepare's cache-only recovery; uncached creation still verifies the GPU.
            recorder.measure(AndroidDetectorLoadPhase.GPU_COMPATIBILITY, spec.modelKey, options.delegate) {
                observe(NativeGpuDiagnostic.Stage.COMPATIBILITY, false) {
                    CompatibilityList().use {
                        check(it.isDelegateSupportedOnThisDevice) { "GPU delegate unsupported on this device" }
                    }
                }
            }
            recorder.measure(AndroidDetectorLoadPhase.GPU_CACHE_PREPARE, spec.modelKey, "gpu") {
                val contract = GpuSerializationContract(inputSize, options.numThreads, options.gpuPrecisionLossAllowed,
                    nativeRuntimeVersion, Build.FINGERPRINT, Build.SUPPORTED_ABIS.toList(), Build.VERSION.SDK_INT)
                GpuSerializationParameters(GpuSerializationCache.privateDirectory(context.noBackupFilesDir), contract.modelToken(model))
            }
        }
    } else null
    private val handle: PrimaryInterpreterHandle = if (options.delegate == "gpu" && cache != null) {
        cache.create(::createInterpreter)
    } else createInterpreter(null)
    private val loadMs = (System.nanoTime() - startedNs) / 1_000_000.0
    private var closed = false

    override val runtimeJson: String
        get() = PrimaryTensorRuntime.encodeObserved(AndroidDetectorRuntime(options.delegate, options.delegate,
            options.numThreads, gpuPrecisionLossAllowed = options.gpuPrecisionLossAllowed.takeIf { handle.gpuDelegateAttached },
            gpuSerializationCacheStatus = cache?.status, gpuSerializationCacheToken = cache?.modelToken,
            gpuSerializationCacheFailureReason = cache?.failureReason, modelOwnerPid = Process.myPid(),
            modelOwnerThreadId = ownerThread.id, nativeRuntimeVersion = nativeRuntimeVersion, nativeLoadMs = loadMs),
            options, handle.gpuDelegateAttached)

    override fun invoke(input: ByteBuffer, outputs: Array<ByteBuffer>, width: Int, height: Int): VisionInvokeResult {
        check(Thread.currentThread() === ownerThread) { "primary invoke must remain on its native owner" }
        check(!closed) { "primary native model is closed" }
        if (outputs.size != 1) throw DetectorTensorContractViolation(DetectorLoadFailureCode.OUTPUT_CONTRACT_MISMATCH,
            "primary requires exactly one output tensor")
        PrimaryTensorContract.requireBuffers(input, outputs[0], inputSize, outputShape, width, height)
        val started = System.nanoTime()
        handle.interpreter.run(input, outputs[0])
        return VisionInvokeResult(runtimeJson, 0.0, (System.nanoTime() - started) / 1_000_000.0, 0.0)
    }

    override fun close() {
        check(Thread.currentThread() === ownerThread) { "primary close must remain on its native owner" }
        if (closed) return
        closed = true
        recorder.measure(AndroidDetectorLoadPhase.NATIVE_CLOSE, spec.modelKey, options.delegate) { handle.close() }
    }

    private fun <T> observe(stage: NativeGpuDiagnostic.Stage, serialized: Boolean, block: () -> T): T =
        NativeGpuDiagnostic.measure(NativeGpuDiagnostic.Role.PRIMARY, stage, options.delegate == "gpu",
            serialized, Thread.currentThread() === ownerThread, { block() },
            kr.co.hanium.dreamup.walksafe.diagnostics.BoundedRuntimeDiagnosticLog::recordNativeGpu)

    private fun createInterpreter(serialization: GpuSerializationParameters?): PrimaryInterpreterHandle {
        var gpu: GpuDelegate? = null
        var interpreter: Interpreter? = null
        try {
            @Suppress("DEPRECATION")
            val nativeOptions = Interpreter.Options().setNumThreads(options.numThreads).setUseNNAPI(options.delegate == "nnapi")
            if (options.delegate == "gpu") {
                val gpuOptions = GpuDelegateFactory.Options()
                    .setPrecisionLossAllowed(options.gpuPrecisionLossAllowed)
                    .setInferencePreference(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
                    .setQuantizedModelsAllowed(true)
                serialization?.let { gpuOptions.setSerializationParams(it.directory.absolutePath, it.modelToken) }
                gpu = recorder.measure(AndroidDetectorLoadPhase.DELEGATE_INIT, spec.modelKey, options.delegate) {
                    observe(NativeGpuDiagnostic.Stage.DELEGATE_INIT, serialization != null) { GpuDelegate(gpuOptions) }
                }
                nativeOptions.addDelegate(gpu)
            }
            val created = recorder.measure(AndroidDetectorLoadPhase.INTERPRETER_INIT, spec.modelKey, options.delegate) {
                observe(NativeGpuDiagnostic.Stage.INTERPRETER_INIT, serialization != null) { Interpreter(model, nativeOptions) }
            }
            interpreter = created
            recorder.measure(AndroidDetectorLoadPhase.TENSOR_CHECK, spec.modelKey, options.delegate) {
                val input = if (created.inputTensorCount == 1) created.getInputTensor(0) else null
                val output = if (created.outputTensorCount == 1) created.getOutputTensor(0) else null
                DetectorTensorContract.requireInterpreterContract(created.inputTensorCount, created.outputTensorCount,
                    input?.dataType() == DataType.FLOAT32, output?.dataType() == DataType.FLOAT32,
                    input?.shape() ?: IntArray(0), output?.shape() ?: IntArray(0), inputSize, outputShape)
            }
            return PrimaryInterpreterHandle(created, gpu)
        } catch (failure: Throwable) {
            var cleanupFailed = false
            try { interpreter?.close() } catch (release: Throwable) {
                cleanupFailed = true; failure.addSuppressed(release)
            }
            try { gpu?.close() } catch (release: Throwable) {
                cleanupFailed = true; failure.addSuppressed(release)
            }
            if (cleanupFailed) throw DetectorNativeCleanupFailure(failure)
            throw failure
        }
    }

    private fun loadMappedAsset(context: Context, path: String): MappedByteBuffer = context.assets.openFd(path).use { descriptor ->
        descriptor.createInputStream().channel.use { channel ->
            channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.length)
        }
    }
}

private class PrimaryInterpreterHandle(val interpreter: Interpreter, private val gpu: GpuDelegate?) : Closeable {
    val gpuDelegateAttached get() = gpu != null
    override fun close() {
        try { interpreter.close() } finally { gpu?.close() }
    }
}

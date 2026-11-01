package kr.co.hanium.dreamup.walksafe.inference

import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionPurpose

/** Native tensors only; image preprocessing and output parsing remain with the caller. */
internal interface PrimaryTensorEngine : Closeable {
    val runtime: AndroidDetectorRuntime
    fun invoke(input: ByteBuffer, output: ByteBuffer, width: Int, height: Int,
        purpose: VisionPurpose): PrimaryTensorResult
}

internal data class PrimaryTensorResult(val runtime: AndroidDetectorRuntime,
    val inferenceMs: Long, val outputReadMs: Long)

/** The receipt exists before OPEN and survives an OPEN failure without a returned engine. */
internal interface PrimaryTensorEngineFactory : Closeable {
    val actualReleasedFuture: CompletionStage<Unit>
    val requiresOwnerThread: Boolean get() = false
    fun open(model: ModelRuntimeConfig, delegate: String, recorder: DetectorLoadTimingRecorder,
        modelKey: String): PrimaryTensorEngine
}

internal object PrimaryTensorContract {
    fun requireBuffers(input: ByteBuffer, output: ByteBuffer, inputSize: Int, outputShape: IntArray,
        width: Int, height: Int) {
        val inputBytes = inputSize.toLong() * inputSize * 3L * Float.SIZE_BYTES
        if (width != inputSize || height != inputSize || !input.isDirect ||
            input.capacity().toLong() != inputBytes || input.remaining().toLong() != inputBytes) {
            throw DetectorTensorContractViolation(DetectorLoadFailureCode.INPUT_CONTRACT_MISMATCH,
                "primary input must be a complete direct $inputSize x $inputSize FLOAT32 RGB tensor")
        }
        val outputBytes = outputShape.fold(1L) { size, dimension ->
            require(dimension > 0) { "primary output dimensions must be positive" }
            Math.multiplyExact(size, dimension.toLong())
        } * Float.SIZE_BYTES
        if (!output.isDirect || output.isReadOnly || output.capacity().toLong() != outputBytes ||
            output.remaining().toLong() != outputBytes) {
            throw DetectorTensorContractViolation(DetectorLoadFailureCode.OUTPUT_CONTRACT_MISMATCH,
                "primary output must be a complete writable direct FLOAT32 tensor")
        }
    }

    /** Overlay only host fallback provenance; actual backend/cache/owner facts come from execution. */
    fun observedRuntime(observed: AndroidDetectorRuntime, hostSelection: AndroidDetectorRuntime): AndroidDetectorRuntime {
        check(observed.requestedDelegate == hostSelection.activeDelegate &&
            observed.activeDelegate == hostSelection.activeDelegate && !observed.fallbackUsed &&
            observed.numThreads == hostSelection.numThreads &&
            (observed.activeDelegate != "gpu" || observed.gpuPrecisionLossAllowed == hostSelection.gpuPrecisionLossAllowed)) {
            "primary execution differs from the explicitly opened runtime"
        }
        return observed.copy(requestedDelegate = hostSelection.requestedDelegate,
            fallbackUsed = hostSelection.fallbackUsed, fallbackReason = hostSelection.fallbackReason)
    }
}

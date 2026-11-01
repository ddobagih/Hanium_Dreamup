package kr.co.hanium.dreamup.walksafe.inference

import java.io.Closeable
import java.util.concurrent.CompletionStage
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeFailures

/** Native cleanup failed, so opening a replacement could retain two live native runtimes. */
internal class DetectorNativeCleanupFailure(cause: Throwable, actualReleasedFuture: CompletionStage<*>? = null) :
    RuntimeException("native runtime cleanup failed", cause) {
    val actualReleasedFuture: CompletionStage<Unit>? = actualReleasedFuture?.thenApply { Unit }
}

/** Owns one native runtime at a time; callers serialize creation, use and close on their owner. */
internal class DelegateFallbackRuntime<T : Closeable>(
    private val options: ModelRuntimeOptions,
    private val create: (delegate: String) -> T,
) : Closeable {
    private var activeDelegate = options.delegate
    private var fallbackReason: String? = null
    private var closed = false
    private var resource: T = try {
        create(options.delegate)
    } catch (error: Throwable) {
        if (!canFallback(error)) throw error
        activateCpu("initialization", error)
    }

    val runtime: AndroidDetectorRuntime
        get() = AndroidDetectorRuntime(
            requestedDelegate = options.delegate,
            activeDelegate = activeDelegate,
            numThreads = options.numThreads,
            fallbackUsed = fallbackReason != null,
            fallbackReason = fallbackReason,
            gpuPrecisionLossAllowed = options.gpuPrecisionLossAllowed.takeIf { activeDelegate == "gpu" },
        )

    fun <R> run(block: (T) -> R): R {
        check(!closed) { "detector runtime is closed" }
        return try {
            block(resource)
        } catch (error: Throwable) {
            if (!canFallback(error)) throw error
            // The failed delegate must not retain native buffers when the CPU replacement starts.
            try {
                resource.close()
            } catch (closeError: Throwable) {
                error.addSuppressed(closeError)
                closed = true
                throw error
            }
            closed = true
            resource = activateCpu("invoke", error)
            closed = false
            block(resource)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        resource.close()
    }

    // Delegate/native failures stay broad (TFLite reports delegate application as runtime errors),
    // but a model/input tensor contract violation is identical on CPU and is never retried.
    private fun canFallback(error: Throwable): Boolean =
        activeDelegate != "cpu" && options.fallbackToCpu &&
            error !is DetectorNativeCleanupFailure &&
            error !is DetectorCohortRetired &&
            error !is DetectorAttachmentInactive &&
            VisionRuntimeFailures.busy(error) == null &&
            error !is DetectorTensorContractViolation &&
            (error is RuntimeException || error is LinkageError)

    private fun activateCpu(phase: String, cause: Throwable): T {
        val cpu = try {
            create("cpu")
        } catch (cpuError: Throwable) {
            cpuError.addSuppressed(cause)
            throw cpuError
        }
        activeDelegate = "cpu"
        fallbackReason = "${options.delegate}_${phase}_${cause.javaClass.simpleName}"
        return cpu
    }
}

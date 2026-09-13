package kr.co.hanium.dreamup.walksafe.inference

import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

internal data class GpuSerializationParameters(val directory: File, val modelToken: String)

/** Invalidate compiled programs whenever the model, device/runtime or configured GPU contract changes. */
internal data class GpuSerializationContract(
    val inputSize: Int,
    val numThreads: Int,
    val precisionLossAllowed: Boolean,
    val nativeRuntimeVersion: String,
    val deviceFingerprint: String,
    val supportedAbis: List<String>,
    val sdkInt: Int,
    // Bump this when changing GPU options or the tensor contract in createInterpreter.
    val optionsVersion: String = "walksafe-gpu-options-v1",
    // Keep this aligned with the pinned GPU dependency, whose version can differ from nativeRuntimeVersion.
    val gpuArtifactVersion: String = "com.google.ai.edge.litert:litert-gpu:1.4.0",
) {
    fun modelToken(model: ByteBuffer): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val modelDigest = MessageDigest.getInstance("SHA-256").apply {
            update(model.asReadOnlyBuffer().apply { clear() })
        }.digest()
        digest.update(modelDigest)
        listOf(
            optionsVersion, gpuArtifactVersion, nativeRuntimeVersion,
            "FLOAT32:[1,$inputSize,$inputSize,3]->FLOAT32:[1,300,6]",
            "threads=$numThreads;precisionLoss=$precisionLossAllowed;sustainedSpeed=1;quantized=true;backend=UNSET",
            deviceFingerprint, sdkInt.toString(), supportedAbis.size.toString(),
        ).plus(supportedAbis).forEach { field ->
            val bytes = field.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/** Cache setup is optional; an unsuccessful cached initialization retries once without serialization. */
internal class GpuSerializationCache private constructor(
    private val parameters: GpuSerializationParameters?,
    initialStatus: String,
    initialFailureReason: String? = null,
) {
    var status: String = initialStatus
        private set
    var failureReason: String? = initialFailureReason
        private set
    private var bypassed = false
    val modelToken: String? get() = parameters?.modelToken

    fun <T> create(createRuntime: (GpuSerializationParameters?) -> T): T {
        if (parameters == null || bypassed) return createRuntime(null)
        return try {
            status = "configured"
            createRuntime(parameters)
        } catch (error: Throwable) {
            if (!isRecoverable(error)) throw error
            // createRuntime must release failed native resources before throwing.
            bypassed = true
            status = "bypassed_after_initialization_failure"
            failureReason = error.javaClass.simpleName
            try {
                createRuntime(null)
            } catch (retryError: Throwable) {
                if (retryError !== error) retryError.addSuppressed(error)
                throw retryError
            }
        }
    }

    companion object {
        fun prepare(enabled: Boolean, prepareParameters: () -> GpuSerializationParameters): GpuSerializationCache {
            if (!enabled) return GpuSerializationCache(null, "disabled")
            return try {
                // A device compatibility failure can still bypass the GPU attempt entirely.
                GpuSerializationCache(prepareParameters(), "prepared")
            } catch (error: Throwable) {
                if (!isRecoverable(error)) throw error
                GpuSerializationCache(null, "unavailable", error.javaClass.simpleName)
            }
        }

        fun privateDirectory(codeCacheDir: File): File {
            val directory = File(codeCacheDir, "walksafe-gpu-v1")
            check((directory.isDirectory || directory.mkdirs()) && directory.isDirectory && directory.canWrite()) {
                "GPU serialization directory is unavailable"
            }
            return directory
        }

        private fun isRecoverable(error: Throwable): Boolean =
            error !is DetectorNativeCleanupFailure && (error is Exception || error is LinkageError)
    }
}

package kr.co.hanium.dreamup.walksafe.inference

import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Specific reason a configured detector model did not load. Developer/field diagnostics only: it is
 * never a user-facing safety claim, and integrity checks are not relaxed for any code.
 */
enum class DetectorLoadFailureCode {
    /** The model entry is disabled in the runtime config itself. */
    MODEL_DISABLED,
    /** The config carries no artifact hash, so the bytes cannot be verified. */
    HASH_NOT_CONFIGURED,
    ASSET_MISSING,
    ASSET_UNREADABLE,
    HASH_MISMATCH,
    INPUT_CONTRACT_MISMATCH,
    OUTPUT_CONTRACT_MISMATCH,
    /** Interpreter or delegate creation failed after any permitted CPU fallback. */
    RUNTIME_INIT_FAILED,
}

/**
 * The model file's own tensor contract, or a caller's prepared input, is wrong. A different delegate
 * cannot repair it, so [DelegateFallbackRuntime] never retries it on CPU.
 */
internal class DetectorTensorContractViolation(
    val code: DetectorLoadFailureCode,
    message: String,
) : IllegalArgumentException(message) {
    init {
        require(code == DetectorLoadFailureCode.INPUT_CONTRACT_MISMATCH ||
            code == DetectorLoadFailureCode.OUTPUT_CONTRACT_MISMATCH)
    }
}

/** The asset bytes failed verification before any interpreter was created. */
internal class DetectorAssetIntegrityFailure(
    val code: DetectorLoadFailureCode,
    message: String,
) : IllegalArgumentException(message)

internal object ModelAssetIntegrity {
    /** Returns null only when the asset bytes match the configured SHA-256. */
    fun check(expectedSha256: String?, open: () -> InputStream): DetectorLoadFailureCode? {
        if (expectedSha256 == null) return DetectorLoadFailureCode.HASH_NOT_CONFIGURED
        val actualSha256 = try {
            open().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            }
        } catch (_: FileNotFoundException) {
            return DetectorLoadFailureCode.ASSET_MISSING
        } catch (_: Throwable) {
            // Same fail-closed breadth as the previous runCatching check; only the cause is kept.
            return DetectorLoadFailureCode.ASSET_UNREADABLE
        }
        return if (actualSha256 == expectedSha256) null else DetectorLoadFailureCode.HASH_MISMATCH
    }

    /** Maps a model-creation failure to its preserved cause. */
    fun classify(error: Throwable): DetectorLoadFailureCode = when (error) {
        is DetectorTensorContractViolation -> error.code
        is DetectorAssetIntegrityFailure -> error.code
        is FileNotFoundException -> DetectorLoadFailureCode.ASSET_MISSING
        is IOException -> DetectorLoadFailureCode.ASSET_UNREADABLE
        else -> DetectorLoadFailureCode.RUNTIME_INIT_FAILED
    }
}

internal object DetectorTensorContract {
    /** Same checks and messages as the interpreter validation; only the failure type is explicit. */
    fun requireInterpreterContract(
        inputTensorCount: Int,
        outputTensorCount: Int,
        inputFloat32: Boolean,
        outputFloat32: Boolean,
        inputShape: IntArray,
        outputShape: IntArray,
        inputSize: Int,
        expectedOutputShape: IntArray,
    ) {
        requireInput(inputTensorCount == 1) { "model must have exactly one input tensor" }
        requireOutput(outputTensorCount == 1) { "model must have exactly one output tensor" }
        requireInput(inputFloat32) { "model input tensor must be float32" }
        requireOutput(outputFloat32) { "model output tensor must be float32" }
        requireInput(inputShape.contentEquals(intArrayOf(1, inputSize, inputSize, 3))) {
            "model input tensor must be [1,$inputSize,$inputSize,3]"
        }
        requireOutput(outputShape.contentEquals(expectedOutputShape)) {
            "model output tensor must be ${expectedOutputShape.contentToString()}"
        }
    }

    /** A prepared tensor for another model size is a caller contract error, not a GPU failure. */
    fun requireModelInput(input: PreprocessedImage, inputSize: Int) {
        requireInput(input.transform.modelSize == inputSize) {
            "prepared input is for model size ${input.transform.modelSize}, not $inputSize"
        }
        val requiredBytes = inputSize.toLong() * inputSize * 3 * Float.SIZE_BYTES
        // TFLite copies a ByteBuffer only when its capacity equals the tensor size exactly.
        requireInput(input.inputBuffer.capacity().toLong() == requiredBytes) {
            "prepared input buffer has ${input.inputBuffer.capacity()} bytes, needs $requiredBytes"
        }
    }

    private inline fun requireInput(value: Boolean, message: () -> String) {
        if (!value) throw DetectorTensorContractViolation(DetectorLoadFailureCode.INPUT_CONTRACT_MISMATCH, message())
    }

    private inline fun requireOutput(value: Boolean, message: () -> String) {
        if (!value) throw DetectorTensorContractViolation(DetectorLoadFailureCode.OUTPUT_CONTRACT_MISMATCH, message())
    }
}

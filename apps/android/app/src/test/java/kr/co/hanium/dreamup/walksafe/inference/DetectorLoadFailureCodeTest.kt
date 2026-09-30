package kr.co.hanium.dreamup.walksafe.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** S10-6 (originals/10.txt:84): load failures keep a specific cause; integrity is never relaxed. */
class DetectorLoadFailureCodeTest {
    private val bytes = "walkmate-model-bytes".toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test fun assetIntegrityDistinguishesMatchMissingUnreadableMismatchAndUnconfiguredHash() {
        assertNull(ModelAssetIntegrity.check(sha) { ByteArrayInputStream(bytes) })
        assertEquals(DetectorLoadFailureCode.HASH_MISMATCH,
            ModelAssetIntegrity.check(sha) { ByteArrayInputStream(bytes + 0.toByte()) })
        assertEquals(DetectorLoadFailureCode.ASSET_MISSING,
            ModelAssetIntegrity.check(sha) { throw FileNotFoundException("models/missing.tflite") })
        assertEquals(DetectorLoadFailureCode.ASSET_UNREADABLE, ModelAssetIntegrity.check(sha) {
            object : InputStream() { override fun read(): Int = throw IOException("truncated") }
        })
        assertEquals(DetectorLoadFailureCode.HASH_NOT_CONFIGURED,
            ModelAssetIntegrity.check(null) { ByteArrayInputStream(bytes) })
    }

    @Test fun loaderKeepsTheSpecificCauseInsteadOfAGenericUnavailableReason() {
        fun code(error: Exception) = TfliteAndroidFrameDetector.loadModel<Any> { throw error }.failureCode
        assertEquals(DetectorLoadFailureCode.HASH_MISMATCH,
            code(DetectorAssetIntegrityFailure(DetectorLoadFailureCode.HASH_MISMATCH, "unified model asset hash_mismatch")))
        assertEquals(DetectorLoadFailureCode.ASSET_MISSING, code(FileNotFoundException("models/x.tflite")))
        assertEquals(DetectorLoadFailureCode.INPUT_CONTRACT_MISMATCH,
            code(DetectorTensorContractViolation(DetectorLoadFailureCode.INPUT_CONTRACT_MISMATCH, "input")))
        assertEquals(DetectorLoadFailureCode.OUTPUT_CONTRACT_MISMATCH,
            code(DetectorTensorContractViolation(DetectorLoadFailureCode.OUTPUT_CONTRACT_MISMATCH, "output")))
        assertEquals(DetectorLoadFailureCode.RUNTIME_INIT_FAILED,
            code(IllegalArgumentException("Internal error: Failed to apply delegate")))
        assertEquals(42, TfliteAndroidFrameDetector.loadModel { 42 }.value)
        assertNull(TfliteAndroidFrameDetector.loadModel { 42 }.failureCode)
        // Cleanup failure still stops any replacement model instead of becoming a load code.
        val cleanup = DetectorNativeCleanupFailure(IllegalStateException("close"))
        assertSame(cleanup, assertThrows(DetectorNativeCleanupFailure::class.java) {
            TfliteAndroidFrameDetector.loadModel<Any> { throw cleanup }
        })
    }

    @Test fun unavailableReasonKeepsTheHistoricalPrefixWithTheCause() {
        assertEquals("detector_assets_unavailable:HASH_MISMATCH",
            TfliteAndroidFrameDetector.unavailableReason(false, DetectorLoadFailureCode.HASH_MISMATCH))
        assertEquals("detector_assets_unavailable",
            TfliteAndroidFrameDetector.unavailableReason(false, null))
        assertEquals("unified_unavailable_legacy_loaded",
            TfliteAndroidFrameDetector.unavailableReason(true, DetectorLoadFailureCode.ASSET_MISSING))
        // Backend metadata allows up to 160 characters for model_load_reason.
        assertEquals(true, DetectorLoadFailureCode.entries.all {
            TfliteAndroidFrameDetector.unavailableReason(false, it).length <= 160
        })
    }

    @Test fun interpreterTensorContractNamesInputAndOutputMismatchesSeparately() {
        val output = intArrayOf(1, 25, 12096)
        fun check(
            inputs: Int = 1, outputs: Int = 1, inFloat: Boolean = true, outFloat: Boolean = true,
            inShape: IntArray = intArrayOf(1, 768, 768, 3), outShape: IntArray = output,
        ) = DetectorTensorContract.requireInterpreterContract(inputs, outputs, inFloat, outFloat, inShape, outShape, 768, output)
        check()
        fun codeOf(block: () -> Unit) = assertThrows(DetectorTensorContractViolation::class.java, block).code
        assertEquals(DetectorLoadFailureCode.INPUT_CONTRACT_MISMATCH, codeOf { check(inputs = 2) })
        assertEquals(DetectorLoadFailureCode.INPUT_CONTRACT_MISMATCH, codeOf { check(inFloat = false) })
        assertEquals(DetectorLoadFailureCode.INPUT_CONTRACT_MISMATCH, codeOf { check(inShape = intArrayOf(1, 640, 640, 3)) })
        assertEquals(DetectorLoadFailureCode.OUTPUT_CONTRACT_MISMATCH, codeOf { check(outputs = 2) })
        assertEquals(DetectorLoadFailureCode.OUTPUT_CONTRACT_MISMATCH, codeOf { check(outFloat = false) })
        assertEquals(DetectorLoadFailureCode.OUTPUT_CONTRACT_MISMATCH, codeOf { check(outShape = intArrayOf(1, 300, 6)) })
        // Still an IllegalArgumentException for existing callers, with the original message.
        val error = assertThrows(IllegalArgumentException::class.java) { check(inFloat = false) }
        assertEquals("model input tensor must be float32", error.message)
    }

    @Test fun preparedInputForAnotherModelSizeIsAContractErrorBeforeInvoke() {
        val tensor = ByteBuffer.allocateDirect(768 * 768 * 3 * 4)
        val transform = LetterboxTransform(1920, 1080, 768, 0.4f, 0f, 168f, 768, 432)
        DetectorTensorContract.requireModelInput(PreprocessedImage(tensor, transform), 768)
        assertEquals(DetectorLoadFailureCode.INPUT_CONTRACT_MISMATCH, assertThrows(DetectorTensorContractViolation::class.java) {
            DetectorTensorContract.requireModelInput(PreprocessedImage(tensor, transform.copy(modelSize = 640)), 768)
        }.code)
        assertThrows(DetectorTensorContractViolation::class.java) {
            DetectorTensorContract.requireModelInput(PreprocessedImage(ByteBuffer.allocateDirect(16), transform), 768)
        }
        // An oversized buffer would pass a >= check and then fail inside the delegate run.
        assertEquals(DetectorLoadFailureCode.INPUT_CONTRACT_MISMATCH, assertThrows(DetectorTensorContractViolation::class.java) {
            DetectorTensorContract.requireModelInput(
                PreprocessedImage(ByteBuffer.allocateDirect(768 * 768 * 3 * 4 + 4), transform),
                768,
            )
        }.code)
    }

    @Test fun modelInputContractRunsBeforeTheInterpreterInvoke() {
        val source = java.io.File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/inference/TfliteAndroidFrameDetector.kt",
        ).readText()
        val detect = source.substringAfter("fun detect(input: PreprocessedImage, validImageCrop: YuvCropRect? = null)")
        val contract = detect.indexOf("DetectorTensorContract.requireModelInput(input, inputSize)")
        val invoke = detect.indexOf("interpreterRuntime.run")
        assertTrue(contract >= 0 && invoke > contract)
    }
}

package kr.co.hanium.dreamup.walksafe.inference

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

@RunWith(AndroidJUnit4::class)
class UnifiedTfliteDeviceSmokeTest {
    @Test
    fun unifiedAssetLoadsAndInvokesOnTheAndroidRuntime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = TwoModelRuntimeConfig.load(context)
        val model = requireNotNull(config.unifiedWalksafe?.takeIf { it.enabled })
        invokeNeutralInput(context, model)
    }

    // Compatibility of the old assets only. This forced config is not the product fallback policy.
    @Test
    fun declaredLegacyFallbackAssetsPassHashAndInterpreterLoadContracts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = TwoModelRuntimeConfig.load(context).copy(
            primaryModelKey = TwoModelRuntimeConfig.LEGACY_TWO_MODEL_KEY,
            unifiedWalksafe = null,
        )

        val result = TfliteAndroidFrameDetector.createWithStatus(context, config)

        assertTrue(result.detectorAvailable)
        assertEquals(TwoModelRuntimeConfig.LEGACY_TWO_MODEL_KEY, result.modelKey)
        assertEquals("legacy_loaded", result.reason)
        result.detector?.close()
        invokeNeutralInput(context, requireNotNull(config.customTactile))
        invokeNeutralInput(context, requireNotNull(config.cocoGeneral))
    }

    @Test
    fun currentProductRejectsMissingOrMismatchedPrimaryWithoutSelectingLegacy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = TwoModelRuntimeConfig.load(context)
        assertEquals(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, config.primaryModelKey)
        assertNull("This test must use the actual no-fallback product policy", config.fallbackModelKey)
        val primary = requireNotNull(config.unifiedWalksafe?.takeIf { it.enabled })
        val failures = listOf(
            primary.copy(asset = "models/nonexistent-primary-for-test.tflite") to DetectorLoadFailureCode.ASSET_MISSING,
            primary.copy(artifactSha256 = "0".repeat(64)) to DetectorLoadFailureCode.HASH_MISMATCH,
        )
        for ((failedPrimary, expectedFailure) in failures) {
            // Change only this in-memory test configuration; bundled assets remain untouched.
            val result = TfliteAndroidFrameDetector.createWithStatus(
                context, config.copy(unifiedWalksafe = failedPrimary),
            )
            try {
                assertTrue(result.configLoaded)
                assertFalse(result.detectorAvailable)
                assertFalse(result.fallbackUsed)
                assertNull(result.detector)
                assertNull(result.modelKey)
                assertEquals(expectedFailure, result.failureCode)
                assertEquals("detector_assets_unavailable:${expectedFailure.name}", result.reason)
            } finally { result.detector?.close() }
        }
    }

    private fun invokeNeutralInput(context: Context, model: ModelRuntimeConfig) {
        assertTrue("asset hash must match the selected model", TwoModelRuntimeConfig.assetMatches(context, model))
        val mappedModel = context.assets.openFd(model.asset).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    descriptor.startOffset,
                    descriptor.declaredLength,
                )
            }
        }
        Interpreter(mappedModel, Interpreter.Options().setNumThreads(4)).use { interpreter ->
            val inputTensor = interpreter.getInputTensor(0)
            val outputTensor = interpreter.getOutputTensor(0)
            assertArrayEquals(intArrayOf(1, model.inputSize, model.inputSize, 3), inputTensor.shape())
            val outputShape = outputTensor.shape()
            // Same config-derived [1, 4 + classes, anchors] or [1, 300, 6] contract that the
            // production detector enforces before inference; no fixed 768-only anchor count.
            assertArrayEquals(model.outputTensorShape(), outputShape)
            assertTrue(inputTensor.dataType() == DataType.FLOAT32)
            assertTrue(outputTensor.dataType() == DataType.FLOAT32)

            val valueCount = model.inputSize * model.inputSize * 3
            val input = ByteBuffer.allocateDirect(valueCount * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
            repeat(valueCount) { input.putFloat(114f / 255f) }
            input.rewind()
            val output = Array(1) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }
            interpreter.run(input, output)

            assertTrue(output[0].all { row -> row.all(Float::isFinite) })
            if (model.outputFormat.isRaw) {
                output[0].drop(4).forEach { scores -> assertTrue(scores.all { it in 0f..1f }) }
                assertTrue(output[0][2].all { it >= 0f })
                assertTrue(output[0][3].all { it >= 0f })
            } else {
                output[0].filter { row -> row[4] > 0f }.forEach { row ->
                    assertTrue(row[4] in 0f..1f)
                    assertTrue(row[5] % 1f == 0f && row[5].toInt() in model.classes.indices)
                }
            }
        }
    }
}

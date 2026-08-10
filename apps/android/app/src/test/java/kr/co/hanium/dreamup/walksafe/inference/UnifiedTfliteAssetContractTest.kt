package kr.co.hanium.dreamup.walksafe.inference

import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import org.tensorflow.lite.schema.Model
import org.tensorflow.lite.schema.TensorType

class UnifiedTfliteAssetContractTest {
    @Test
    fun unified768ModelMatchesAndroidTensorAndArtifactContract() {
        val model = System.getenv("WALKSAFE_TFLITE_CANDIDATE")
            ?.let(::File)
            ?: File("src/main/assets/models/walksafe_unified_yolo26n_768_float32.tflite")
        require(model.isFile) { "unified TFLite model not found: $model" }
        assertEquals(EXPECTED_ARTIFACT_SHA256, model.sha256())
        val configured = JSONObject(File("src/main/assets/model-config/two_model_runtime.json").readText())
            .getJSONObject("models")
            .getJSONObject("unified_walksafe")
        assertEquals(EXPECTED_ARTIFACT_SHA256, configured.getString("artifact_sha256"))
        assertEquals(768, configured.getInt("input_size"))
        val export = configured.getJSONObject("export")
        assertEquals("8.4.48", export.getString("ultralytics_version"))
        assertEquals("2.19.0", export.getString("tensorflow_version"))
        assertTrue(export.getBoolean("end_to_end_output_verified"))
        assertEquals(model.length(), export.getLong("artifact_size_bytes"))
        assertEquals("float32", export.getString("input_dtype"))
        assertEquals("float32", export.getString("output_dtype"))

        val buffer = ByteBuffer.wrap(model.readBytes())
        assertTrue(Model.ModelBufferHasIdentifier(buffer))
        val graph = requireNotNull(Model.getRootAsModel(buffer).subgraphs(0))
        assertEquals(1, graph.inputsLength())
        assertEquals(1, graph.outputsLength())
        val input = requireNotNull(graph.tensors(graph.inputs(0)))
        val output = requireNotNull(graph.tensors(graph.outputs(0)))
        assertEquals(TensorType.FLOAT32, input.type())
        assertEquals(TensorType.FLOAT32, output.type())
        assertArrayEquals(intArrayOf(1, 768, 768, 3), IntArray(input.shapeLength(), input::shape))
        assertArrayEquals(intArrayOf(1, 300, 6), IntArray(output.shapeLength(), output::shape))
        assertEquals(export.getInt("flatbuffer_operator_count"), graph.operatorsLength())
    }

    @Test
    fun packagedFallbackAssetsMatchDeclaredHashesAndTensorContracts() {
        val modelsRoot = File("src/main/assets/models")
        val configuredModels = JSONObject(File("src/main/assets/model-config/two_model_runtime.json").readText())
            .getJSONObject("models")
        val expected = mapOf(
            "unified_walksafe" to "walksafe_unified_yolo26n_768_float32.tflite",
            "custom_tactile" to "custom_tactile_yolo26s_float32.tflite",
            "coco_general" to "coco_yolo26n_float32.tflite",
        )
        assertEquals(expected.values.toSet(), modelsRoot.listFiles().orEmpty().filter { it.extension == "tflite" }.map { it.name }.toSet())

        expected.forEach { (modelKey, fileName) ->
            val configured = configuredModels.getJSONObject(modelKey)
            val model = File(modelsRoot, fileName)
            assertEquals("models/$fileName", configured.getString("asset"))
            assertEquals(configured.getString("artifact_sha256"), model.sha256())

            val graph = requireNotNull(Model.getRootAsModel(ByteBuffer.wrap(model.readBytes())).subgraphs(0))
            val input = requireNotNull(graph.tensors(graph.inputs(0)))
            val output = requireNotNull(graph.tensors(graph.outputs(0)))
            val inputSize = configured.getInt("input_size")
            assertEquals(TensorType.FLOAT32, input.type())
            assertEquals(TensorType.FLOAT32, output.type())
            assertArrayEquals(intArrayOf(1, inputSize, inputSize, 3), IntArray(input.shapeLength(), input::shape))
            assertArrayEquals(intArrayOf(1, 300, 6), IntArray(output.shapeLength(), output::shape))
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private companion object {
        const val EXPECTED_ARTIFACT_SHA256 = "92b39d3b24d97519038db5ef8ea613c32a46aaefabc5080fd989b7ab5c1fbb19"
    }
}

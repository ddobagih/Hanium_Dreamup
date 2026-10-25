package kr.co.hanium.dreamup.walksafe.inference

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/** Real host-model outputs; replay is separate from native Android calibration. */
class CurrentModelBoundaryReferenceTest {
    private val assets = File("src/main/assets")
    private val manifest = JSONObject(resource("manifest.json").toString(Charsets.UTF_8))
    private val configBytes = File(assets, "model-config/two_model_runtime.json").readBytes()
    private val model = requireNotNull(TwoModelRuntimeConfig.parse(configBytes.toString(Charsets.UTF_8)).unifiedWalksafe)

    @Test
    fun measuredReferencesBelongToCurrentModelConfigAndDeclaredPhotographs() {
        assertEquals(manifest.getString("config_sha256"), sha(configBytes))
        assertEquals(manifest.getString("model_sha256"), model.artifactSha256)
        assertEquals(model.artifactSha256, sha(File(assets, model.asset).readBytes()))
        assertEquals(manifest.getString("source_model_sha256"), model.sourceModelSha256)
        assertEquals("FLOAT32_LITTLE_ENDIAN", manifest.getString("tensor_encoding"))
        assertEquals(RuntimeCalibrationOutputGate.NEAR_THRESHOLD_WINDOW, manifest.getDouble("near_threshold_window").toFloat(), 0f)
        assertFalse(manifest.getBoolean("android_runtime_verified"))
        val declared = JSONObject(File(assets, "runtime-calibration/manifest.json").readText())
            .getJSONObject("coverage").getJSONArray("near_threshold_fixture_ids")
        val declaredIds = (0 until declared.length()).map { declared.getString(it) }.toSet()
        assertEquals(setOf("coco_885", "coco_11149"), rows().filter { it.getInt("near_count") > 0 }
            .map { it.getString("fixture_id") }.toSet())
        for (row in rows()) {
            assertEquals(row.getString("source_sha256"), sha(File(assets, row.getString("source_asset")).readBytes()))
            if (row.getInt("near_count") > 0) assertTrue(row.getString("fixture_id") in declaredIds)
        }
    }

    @Test
    fun productionBilinearReproducesEveryMeasuredModelInputBit() {
        for (row in rows()) {
            val rgb = compressed(row.getString("rgb_file"))
            assertEquals(row.getString("rgb_sha256"), sha(rgb))
            val dimensions = row.getJSONArray("source_dimensions")
            val width = dimensions.getInt(0)
            val height = dimensions.getInt(1)
            assertEquals(width * height * 3, rgb.size)
            val pixels = IntArray(width * height) { index ->
                val offset = index * 3
                (0xff shl 24) or ((rgb[offset].toInt() and 255) shl 16) or
                    ((rgb[offset + 1].toInt() and 255) shl 8) or (rgb[offset + 2].toInt() and 255)
            }
            val image = ArgbImage(width, height, pixels)
            val input = YuvImagePreprocessor().preprocessBilinear(image, model.inputSize).inputBuffer
            val floats = input.order(ByteOrder.nativeOrder()).asFloatBuffer()
            val littleEndian = ByteBuffer.allocate(floats.remaining() * 4).order(ByteOrder.LITTLE_ENDIAN)
            while (floats.hasRemaining()) littleEndian.putFloat(floats.get())
            assertEquals(row.getString("fixture_id"), row.getString("input_sha256"), sha(littleEndian.array()))
        }
    }

    @Test
    fun uneditedNativeOutputsSupplyActualTopClassBoundaryCoverage() {
        val anchors = model.outputTensorShape()[2]
        for (row in rows()) {
            val rawBytes = compressed(row.getString("raw_file"))
            assertEquals(row.getString("raw_sha256"), sha(rawBytes))
            assertEquals(model.outputTensorShape().fold(1) { size, axis -> size * axis } * 4, rawBytes.size)
            val buffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val raw = FloatArray(buffer.remaining()).also { buffer.get(it) }
            assertTrue(raw.all { it.isFinite() })
            assertEquals(row.getString("fixture_id"), row.getInt("near_count") > 0,
                RuntimeCalibrationOutputGate.hasNearThresholdCandidate(raw, model))
            val examples = row.getJSONArray("examples")
            assertEquals(row.getInt("near_count"), examples.length())
            for (index in 0 until examples.length()) {
                val example = examples.getJSONObject(index)
                val anchor = example.getInt("anchor")
                val classId = example.getInt("class_id")
                val className = requireNotNull(model.classNameForId(classId))
                val score = raw[(4 + classId) * anchors + anchor]
                assertEquals(className, example.getString("class_name"))
                assertTrue(model.isAllowedClass(className))
                assertEquals(example.getDouble("score").toFloat(), score, 0f)
                assertEquals(example.getDouble("threshold").toFloat(), model.thresholdForClass(className), 0f)
                assertTrue(abs(score - model.thresholdForClass(className)) <= RuntimeCalibrationOutputGate.NEAR_THRESHOLD_WINDOW)
                for (otherClass in model.classes.indices) assertTrue(raw[(4 + otherClass) * anchors + anchor] <= score)
                val box = example.getJSONArray("box_xywh_normalized")
                val w = raw[2 * anchors + anchor]
                val h = raw[3 * anchors + anchor]
                val x = raw[anchor] - w / 2f
                val y = raw[anchors + anchor] - h / 2f
                for ((axis, value) in listOf(x, y, w, h).withIndex()) assertEquals(box.getDouble(axis).toFloat(), value, 0f)
                assertTrue(w > 0f && h > 0f && x < 1f && y < 1f && x + w > 0f && y + h > 0f)
            }
        }
    }

    private fun rows(): List<JSONObject> = manifest.getJSONArray("rows").let { rows ->
        (0 until rows.length()).map { rows.getJSONObject(it) }
    }

    private fun resource(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream(
        "/inference/current-model-boundary/$name",
    )).use { it.readBytes() }

    private fun compressed(name: String): ByteArray = GZIPInputStream(resource(name).inputStream()).use { it.readBytes() }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

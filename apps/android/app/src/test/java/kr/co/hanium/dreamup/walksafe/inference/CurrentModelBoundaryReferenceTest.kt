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
import kotlin.math.roundToInt
import kotlin.math.abs

/** Current YOLO26 host-model outputs; replay is separate from native Android calibration. */
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
        assertEquals(declaredIds, rows().filter { it.getInt("near_count") > 0 }
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
        for (row in rows()) {
            val rawBytes = compressed(row.getString("raw_file"))
            assertEquals(row.getString("raw_sha256"), sha(rawBytes))
            assertEquals(1 * 300 * 6 * 4, rawBytes.size)
            val buffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val raw = FloatArray(buffer.remaining()).also { buffer.get(it) }
            assertTrue(raw.all { it.isFinite() })
            assertEquals(row.getInt("near_count") > 0, RuntimeCalibrationOutputGate.hasNearThresholdCandidate(raw, model))
            val parser = YoloEndToEndOutputParser(model.inputSize, model::classNameForId,
                model::thresholdForClass, model::isAllowedClass, model::thresholdForClassId, pixelCoordinates = true)
            val detections = parser.parse(raw)
            assertTrue("positive fixture must yield actual accepted detections", detections.isNotEmpty())
            assertTrue(RuntimeCalibrationOutputGate.compare(raw, raw.copyOf(), detections, detections.toList(), model))
            val fixture = RuntimeCalibrationFixtures { File(assets, it).readBytes() }.loadManifest()
                .fixtures.single { it.id == row.getString("fixture_id") }
            val scale = minOf(model.inputSize.toFloat() / fixture.width, model.inputSize.toFloat() / fixture.height)
            val transform = LetterboxTransform(fixture.width, fixture.height, model.inputSize, scale,
                ((model.inputSize - (fixture.width * scale).roundToInt()) / 2).toFloat(),
                ((model.inputSize - (fixture.height * scale).roundToInt()) / 2).toFloat())
            val imageDetections = detections.mapNotNull(transform::modelDetectionToImageDetection)
            assertTrue("Current model must actually match a ground-truth object for ${fixture.id}",
                RuntimeCalibrationOutputGate.matchesFixtureRoles(imageDetections, imageDetections,
                    expectPositive = true, expectEmpty = false,
                    groundTruth = fixture.groundTruth.filterNot { it.isCrowd }.map { it.className to it.bboxNorm }))
            val examples = row.getJSONArray("examples")
            assertEquals(row.getInt("near_count"), examples.length())
            for (index in 0 until examples.length()) {
                val example = examples.getJSONObject(index)
                val offset = example.getInt("row") * 6
                val classId = example.getInt("class_id")
                assertEquals(classId.toFloat(), raw[offset + 5], 0f)
                assertEquals(example.getString("class_name"), model.classNameForId(classId))
                assertEquals(example.getDouble("score").toFloat(), raw[offset + 4], 0f)
                assertEquals(example.getDouble("threshold").toFloat(), model.thresholdForClassId(classId), 0f)
                assertTrue(abs(raw[offset + 4] - model.thresholdForClassId(classId)) <= RuntimeCalibrationOutputGate.NEAR_THRESHOLD_WINDOW)
            }
        }
    }

    private fun rows(): List<JSONObject> = manifest.getJSONArray("rows").let { rows ->
        (0 until rows.length()).map { rows.getJSONObject(it) }
    }

    private fun resource(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream(
        "/inference/yolo26-model-boundary/$name",
    )).use { it.readBytes() }

    private fun compressed(name: String): ByteArray = GZIPInputStream(resource(name).inputStream()).use { it.readBytes() }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

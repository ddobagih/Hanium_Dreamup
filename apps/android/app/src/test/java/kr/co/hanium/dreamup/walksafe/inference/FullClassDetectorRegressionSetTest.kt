package kr.co.hanium.dreamup.walksafe.inference

import kr.co.hanium.dreamup.walksafe.depth.RectNorm
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

/** Bounded real-photo regression coverage, separate from delegate equivalence and field accuracy. */
class FullClassDetectorRegressionSetTest {
    private val manifest = JSONObject(resource("manifest.json").toString(Charsets.UTF_8))
    private val configBytes = File("src/main/assets/model-config/two_model_runtime.json").readBytes()
    private val config = requireNotNull(TwoModelRuntimeConfig.parse(configBytes.toString(Charsets.UTF_8)).unifiedWalksafe)

    @Test
    fun coversAllTwentyOneClassesWithReviewedPhotographsAndExplicitTargetBoxes() {
        assertEquals(manifest.getString("config_sha256"), sha(configBytes))
        assertEquals(manifest.getString("model_sha256"), config.artifactSha256)
        assertEquals(config.artifactSha256, sha(File("src/main/assets", config.asset).readBytes()))
        assertEquals(manifest.getString("source_model_sha256"), config.sourceModelSha256)
        assertFalse(manifest.getBoolean("android_runtime_verified"))
        val positives = rows().filter { it.getString("kind") == "CLASS_POSITIVE" }
        assertEquals(21, positives.size)
        assertEquals(config.classes.indices.toSet(), positives.map { it.getInt("target_class_id") }.toSet())
        assertEquals(rows().size, rows().map { it.getString("id") }.toSet().size)
        for (row in positives) {
            val classId = row.getInt("target_class_id")
            assertEquals(config.classes[classId], row.getString("target_class_name"))
            assertEquals(row.getString("photo_sha256"), sha(resource(row.getString("photo"))))
            assertEquals(row.getString("photo_sha256"), row.getString("original_source_sha256"))
            assertTrue(row.getString("manual_review").isNotBlank())
            assertTrue(row.getString("annotation_scope").contains("incompletely annotated"))
            val targets = targetBoxes(row)
            assertTrue(targets.isNotEmpty())
            assertTrue(targets.all { box ->
                listOf(box.x, box.y, box.width, box.height).all { it.isFinite() } &&
                    box.width > 0f && box.height > 0f && box.x < 1f && box.y < 1f &&
                    box.x + box.width > 0f && box.y + box.height > 0f
            })
        }
    }

    @Test
    fun realBackgroundCropsPreserveTheirSourceAndDoNotInferNegativesFromMissingLabels() {
        val negatives = rows().filter { it.getString("kind") == "REAL_BACKGROUND_NEGATIVE" }
        assertEquals(setOf("wall_pattern", "pavement_shadow", "lane_marking"),
            negatives.map { it.getString("negative_kind") }.toSet())
        for (row in negatives) {
            assertEquals(row.getString("photo_sha256"), sha(resource(row.getString("photo"))))
            val parent = rows().single { it.getString("id") == row.getString("parent_positive_id") }
            assertEquals(parent.getString("original_source_sha256"), row.getString("original_source_sha256"))
            assertEquals(parent.getString("original_source"), row.getString("original_source"))
            val crop = row.getJSONArray("crop_xyxy_pixels")
            val left = crop.getInt(0)
            val top = crop.getInt(1)
            val right = crop.getInt(2)
            val bottom = crop.getInt(3)
            assertTrue(left >= 0 && top >= 0 && right <= parent.getInt("width") && bottom <= parent.getInt("height"))
            assertEquals(right - left, row.getInt("width"))
            assertEquals(bottom - top, row.getInt("height"))
            assertTrue(targetBoxes(row).isEmpty())
            assertTrue(row.getString("manual_review").contains("not a full negative scene or zero-label inference"))
            val region = RectNorm(left.toFloat() / parent.getInt("width"), top.toFloat() / parent.getInt("height"),
                (right - left).toFloat() / parent.getInt("width"), (bottom - top).toFloat() / parent.getInt("height"))
            assertTrue(targetBoxes(parent).all { iou(region, it) == 0f })
        }
    }

    @Test
    fun replaysEveryNativeOutputThroughProductionParsingAndReportsActualBaseline() {
        val parser = YoloRawOutputParser(config.inputSize, config.classes.size, config::classNameForId,
            config::thresholdForClass, config::isAllowedClass, config.nmsIouThreshold, config.maxDetections,
            normalizedCoordinates = config.outputFormat == YoloOutputFormat.RAW_XYWH_NORMALIZED)
        var positiveHits = 0
        var negativeFalsePositiveCases = 0
        for (row in rows()) {
            val bytes = GZIPInputStream(resource(row.getString("raw_file")).inputStream()).use { it.readBytes() }
            assertEquals(row.getString("raw_sha256"), sha(bytes))
            assertEquals(config.outputTensorShape().fold(1) { size, axis -> size * axis } * 4, bytes.size)
            val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val raw = FloatArray(floats.remaining()).also { floats.get(it) }
            assertTrue(raw.all { it.isFinite() })
            val prep = row.getJSONObject("preprocessing")
            val transform = LetterboxTransform(row.getInt("width"), row.getInt("height"), config.inputSize,
                prep.getDouble("scale").toFloat(), prep.getDouble("pad_x").toFloat(), prep.getDouble("pad_y").toFloat(),
                prep.getInt("resized_width"), prep.getInt("resized_height"))
            val detections = parser.parse(raw, transform).mapNotNull(transform::modelDetectionToImageDetection)
            assertTrue(detections.all { detection ->
                config.isAllowedClass(detection.className) && detection.detectionConfidence.isFinite() &&
                    detection.detectionConfidence >= config.thresholdForClass(detection.className) &&
                    detection.bboxNorm.width > 0f && detection.bboxNorm.height > 0f
            })
            val measured = JSONObject().put("id", row.getString("id")).put("kind", row.getString("kind"))
                .put("detected_count", detections.size)
            if (row.getString("kind") == "CLASS_POSITIVE") {
                val target = row.getString("target_class_name")
                val overlap = detections.filter { it.className == target }.maxOfOrNull { detection ->
                    targetBoxes(row).maxOf { iou(detection.bboxNorm, it) }
                } ?: 0f
                // 0.5 is an evaluation matching convention, not a navigation/safety threshold.
                val hit = overlap >= 0.5f
                if (hit) positiveHits += 1
                measured.put("target_class", target).put("target_hit_at_iou_0_5", hit)
                    .put("best_target_iou", overlap.toDouble())
            } else {
                if (detections.isNotEmpty()) negativeFalsePositiveCases += 1
                measured.put("false_positive_classes", detections.map { it.className }.distinct())
            }
            // Log misses and false positives honestly; coverage is not a claim of perfect accuracy.
            println("VISION_21_CLASS_BASELINE $measured")
        }
        println("VISION_21_CLASS_BASELINE_SUMMARY positives=21 hits=$positiveHits negatives=3 falsePositiveCases=$negativeFalsePositiveCases")
    }

    private fun targetBoxes(row: JSONObject): List<RectNorm> = row.getJSONArray("target_boxes_xywh_normalized").let { boxes ->
        (0 until boxes.length()).map { index ->
            val box = boxes.getJSONArray(index)
            val cx = box.getDouble(0).toFloat()
            val cy = box.getDouble(1).toFloat()
            val width = box.getDouble(2).toFloat()
            val height = box.getDouble(3).toFloat()
            RectNorm(cx - width / 2f, cy - height / 2f, width, height)
        }
    }

    private fun iou(a: RectNorm, b: RectNorm): Float {
        val area = (minOf(a.x + a.width, b.x + b.width) - maxOf(a.x, b.x)).coerceAtLeast(0f) *
            (minOf(a.y + a.height, b.y + b.height) - maxOf(a.y, b.y)).coerceAtLeast(0f)
        return area / (a.width * a.height + b.width * b.height - area).coerceAtLeast(1e-12f)
    }

    private fun rows(): List<JSONObject> = manifest.getJSONArray("rows").let { rows ->
        (0 until rows.length()).map { rows.getJSONObject(it) }
    }

    private fun resource(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream(
        "/inference/full-class-regression/$name",
    )).use { it.readBytes() }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

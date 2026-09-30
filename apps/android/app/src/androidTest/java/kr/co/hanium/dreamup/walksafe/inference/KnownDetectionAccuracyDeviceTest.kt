package kr.co.hanium.dreamup.walksafe.inference

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * Fixed public photos and real class/box annotations, no camera or account UI.
 *
 * The fixed floors (22/110 matched, 1/63 person) are a regression floor against the original-model
 * reference, not a product acceptance line or a safety figure (FP-038-DETAIL-02, FP-037). Product
 * criteria (per-class recall, false alerts, distance bands) come from the independent evaluation and
 * owner approval. The paired checks below fail when the current preprocessing is worse than the
 * previous one on the same fixed images; they are asserted on the deterministic CPU delegate only.
 */
@RunWith(AndroidJUnit4::class)
class KnownDetectionAccuracyDeviceTest {
    @Test fun referencePreprocessingPreservesFixedGroundTruthMatches() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val photos = JSONObject(assets.open("positive-fixtures/gt.json").bufferedReader().use { it.readText() }).getJSONArray("images")
        val alias = mapOf("car" to "passenger_car", "traffic light" to "traffic_light")
        for (backend in listOf("gpu", "cpu")) {
            val load = TfliteAndroidFrameDetector.createWithStatus(instrumentation.targetContext,
                runtimeOverride = ModelRuntimeOptions(delegate = backend, numThreads = 4, fallbackToCpu = false))
            val detector = requireNotNull(load.detector) { load.reason }
            var total = 0; var matched = 0; var people = 0; var matchedPeople = 0
            var oldMatched = 0; var oldPeople = 0
            var detections = 0; var oldDetections = 0
            val unmatchedByClass = mutableMapOf<String, Int>()
            val oldUnmatchedByClass = mutableMapOf<String, Int>()
            val previousPreprocessor = YuvImagePreprocessor()
            detector.use {
                for (index in 0 until photos.length()) {
                    val photo = photos.getJSONObject(index)
                    val bytes = assets.open("positive-fixtures/${photo.getString("file_name")}").use { it.readBytes() }
                    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                    assertEquals(photo.getString("sha256"), digest)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val image = ArgbImage(bitmap.width, bitmap.height, pixels)
                    bitmap.recycle()
                    val result = detector.detectCalibrationImage(image)
                    val previous = detector.inferPreparedUnifiedForCalibration(
                        previousPreprocessor.preprocess(image, 768), 0L,
                        YuvPreprocessingStrategy.LEGACY_TWO_PASS, copyRawOutput = false).result
                    assertEquals(backend, result.timing.modelRuntime!!.activeDelegate)
                    val used = mutableSetOf<Int>()
                    val oldUsed = mutableSetOf<Int>()
                    val annotations = photo.getJSONArray("annotations")
                    for (a in 0 until annotations.length()) {
                        val ann = annotations.getJSONObject(a)
                        if (ann.getInt("iscrowd") != 0) continue
                        val name = ann.getString("app_class_name").let { alias[it] ?: it }
                        val box = ann.getJSONArray("bbox_xywh")
                        val rect = RectNorm((box.getDouble(0) / image.width).toFloat(),
                            (box.getDouble(1) / image.height).toFloat(),
                            (box.getDouble(2) / image.width).toFloat(), (box.getDouble(3) / image.height).toFloat())
                        val best = result.detections.indices.filter { it !in used && result.detections[it].className == name }
                            .map { it to iou(rect, result.detections[it].bboxNorm) }.maxByOrNull { it.second }
                        val ok = best != null && best.second >= 0.5f
                        total++; if (name == "person") people++
                        if (ok) { used.add(best!!.first); matched++; if (name == "person") matchedPeople++ }
                        val oldBest = previous.detections.indices.filter { it !in oldUsed && previous.detections[it].className == name }
                            .map { it to iou(rect, previous.detections[it].bboxNorm) }.maxByOrNull { it.second }
                        if (oldBest != null && oldBest.second >= 0.5f) {
                            oldUsed.add(oldBest.first); oldMatched++; if (name == "person") oldPeople++
                        }
                    }
                    // Detections not used by any ground-truth match (S21-15); counted, not capped.
                    detections += result.detections.size
                    oldDetections += previous.detections.size
                    result.detections.forEachIndexed { i, d ->
                        if (i !in used) unmatchedByClass.merge(d.className, 1, Int::plus)
                    }
                    previous.detections.forEachIndexed { i, d ->
                        if (i !in oldUsed) oldUnmatchedByClass.merge(d.className, 1, Int::plus)
                    }
                    Log.i("KnownAccuracyTest", "$backend image=${photo.getInt("image_id")} predictions=${result.detections.size} inferenceMs=${result.timing.modelInferenceMs}")
                }
            }
            Log.i("KnownAccuracyTest", "$backend matched=$matched/$total person=$matchedPeople/$people")
            Log.i("KnownAccuracyTest", "$backend paired nearest=$oldMatched/$total person=$oldPeople/$people bilinear=$matched/$total person=$matchedPeople/$people")
            val unmatched = detections - matched
            val oldUnmatched = oldDetections - oldMatched
            Log.i("KnownAccuracyTest", "$backend unmatched detections current=$unmatched/$detections previous=$oldUnmatched/$oldDetections")
            Log.i("KnownAccuracyTest", "$backend unmatched by class current=${unmatchedByClass.toSortedMap()} previous=${oldUnmatchedByClass.toSortedMap()}")
            assertEquals(110, total)
            assertEquals(63, people)
            assertTrue("Regression floor, not a product acceptance line (FP-038-DETAIL-02): $backend matched=$matched", matched >= 22)
            assertTrue("Person regression floor, not a product acceptance line (FP-038-DETAIL-02): $backend matchedPeople=$matchedPeople", matchedPeople >= 1)
            if (backend == "cpu") {
                // S21-14: paired, same images and delegate, zero allowance fixed before the run.
                assertTrue("Current preprocessing matched fewer than previous: cpu matched=$matched previous=$oldMatched", matched >= oldMatched)
                assertTrue("Current preprocessing matched fewer people than previous: cpu matchedPeople=$matchedPeople previous=$oldPeople", matchedPeople >= oldPeople)
                // S21-15: precision must not drop (integer cross-multiplication, no float rounding).
                assertTrue(
                    "Current preprocessing added unmatched detections: cpu matched=$matched/$detections previous=$oldMatched/$oldDetections",
                    matched.toLong() * oldDetections.toLong() >= oldMatched.toLong() * detections.toLong(),
                )
            }
        }
    }

    private fun iou(a: RectNorm, b: RectNorm): Float {
        val overlap = (minOf(a.x + a.width, b.x + b.width) - maxOf(a.x, b.x)).coerceAtLeast(0f) *
            (minOf(a.y + a.height, b.y + b.height) - maxOf(a.y, b.y)).coerceAtLeast(0f)
        return overlap / (a.width * a.height + b.width * b.height - overlap + 1e-9f)
    }
}

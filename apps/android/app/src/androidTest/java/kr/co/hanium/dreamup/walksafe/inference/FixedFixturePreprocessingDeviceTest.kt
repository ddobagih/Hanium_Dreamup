package kr.co.hanium.dreamup.walksafe.inference

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Same pinned model/CPU/photos/GT. Paired preprocessing regression, not a field accuracy claim. */
@RunWith(AndroidJUnit4::class)
class FixedFixturePreprocessingDeviceTest {
    @Test fun currentBilinearPreservesPreviousNearestMatchesAndPrecision() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = TwoModelRuntimeConfig.load(context)
        val primary = requireNotNull(original.unifiedWalksafe)
        val model = primary.copy(runtime = primary.runtime.copy(delegate = "cpu", numThreads = 2, fallbackToCpu = false))
        val config = original.copy(unifiedWalksafe = model, fallbackModelKey = null,
            customTactile = null, cocoGeneral = null, personSupplement = null)
        val fixtures = RuntimeCalibrationFixtures(context)
        val manifest = fixtures.loadManifest()
        val load = TfliteAndroidFrameDetector.createWithStatus(context, config,
            preprocessingStrategy = YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR)
        val detector = requireNotNull(load.detector) { load.reason }
        val current = Counts()
        val previous = Counts()
        val entries = JSONArray()
        val report = JSONObject().put("modelSha256", model.artifactSha256)
            .put("fixtureSha256", manifest.sha256).put("cpuThreads", 2)
            .put("currentPreprocessing", "bilinear").put("previousPreprocessing", "nearest")
            .put("cameraUsed", false).put("thresholdsChanged", false).put("fixtures", entries)
        try {
            assertEquals(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, load.modelKey)
            assertTrue(!load.fallbackUsed)
            val oldPreprocessor = YuvImagePreprocessor()
            for (fixture in manifest.fixtures.filter { it.kind == RuntimeCalibrationFixtureKind.JPEG }) {
                // RuntimeCalibrationFixtures verifies the JPEG SHA before decoding.
                val image = fixtures.loadArgb(fixture)
                val now = detector.detectCalibrationImage(image)
                val before = detector.inferPreparedUnifiedForCalibration(
                    oldPreprocessor.preprocess(image, model.inputSize), 0L,
                    YuvPreprocessingStrategy.LEGACY_TWO_PASS, copyRawOutput = false).result
                assertEquals("cpu", now.timing.modelRuntime?.activeDelegate)
                assertEquals("cpu", before.timing.modelRuntime?.activeDelegate)
                val truth = fixture.groundTruth.filter { !it.isCrowd }
                val nowMatches = matches(now.detections, truth)
                val oldMatches = matches(before.detections, truth)
                current.add(now.detections.size, truth, nowMatches)
                previous.add(before.detections.size, truth, oldMatches)
                entries.put(JSONObject().put("id", fixture.id).put("sha256", fixture.sha256)
                    .put("groundTruth", truth.size).put("currentMatches", nowMatches.count { it })
                    .put("previousMatches", oldMatches.count { it })
                    .put("currentDetections", now.detections.size).put("previousDetections", before.detections.size))
            }
            report.put("currentMatches", current.matched).put("previousMatches", previous.matched)
                .put("currentDetections", current.predictions).put("previousDetections", previous.predictions)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PAIRED_PREPROCESSING $report\n") })
            assertTrue("Previous preprocessing has no annotation matches; comparison is inconclusive", previous.matched > 0)
            assertTrue("Paired annotation recall decreased: $report", current.matched >= previous.matched)
            previous.byClass.forEach { (name, count) ->
                assertTrue("Paired $name annotation recall decreased: $report", (current.byClass[name] ?: 0) >= count)
            }
            assertTrue("Paired annotation precision decreased: $report",
                current.matched.toLong() * previous.predictions >= previous.matched.toLong() * current.predictions)
        } finally {
            detector.close()
        }
    }

    private class Counts {
        var matched = 0
        var predictions = 0
        val byClass = mutableMapOf<String, Int>()
        fun add(count: Int, truth: List<RuntimeCalibrationGroundTruth>, matches: BooleanArray) {
            predictions += count
            matches.forEachIndexed { index, found -> if (found) {
                matched++
                val name = truth[index].className
                byClass[name] = (byClass[name] ?: 0) + 1
            } }
        }
    }

    private fun matches(predictions: List<DetectionCandidate>, truth: List<RuntimeCalibrationGroundTruth>): BooleanArray {
        val aliases = mapOf("car" to "passenger_car", "traffic light" to "traffic_light")
        val edges = truth.map { annotation -> predictions.indices.filter { index ->
            val name = aliases[annotation.className] ?: annotation.className
            predictions[index].className == name && iou(predictions[index].bboxNorm, annotation.bboxNorm) >= 0.5f
        }.sortedByDescending { iou(predictions[it].bboxNorm, annotation.bboxNorm) } }
        val assigned = IntArray(predictions.size) { -1 }
        fun match(index: Int, visited: BooleanArray): Boolean {
            for (prediction in edges[index]) if (!visited[prediction]) {
                visited[prediction] = true
                if (assigned[prediction] < 0 || match(assigned[prediction], visited)) {
                    assigned[prediction] = index
                    return true
                }
            }
            return false
        }
        return BooleanArray(truth.size) { match(it, BooleanArray(predictions.size)) }
    }

    private fun iou(a: RectNorm, b: RectNorm): Float {
        val width = (minOf(a.x + a.width, b.x + b.width) - maxOf(a.x, b.x)).coerceAtLeast(0f)
        val height = (minOf(a.y + a.height, b.y + b.height) - maxOf(a.y, b.y)).coerceAtLeast(0f)
        val overlap = width * height
        return overlap / maxOf(a.width * a.height + b.width * b.height - overlap, 1e-12f)
    }
}

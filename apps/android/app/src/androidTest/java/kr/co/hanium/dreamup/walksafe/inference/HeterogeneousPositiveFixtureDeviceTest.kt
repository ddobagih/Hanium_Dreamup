package kr.co.hanium.dreamup.walksafe.inference

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory

/**
 * Opt-in, fixed public COCO fixture comparison. This exercises the shared ARGB preprocessor,
 * direct TFLite interpreters and production output parser, not the camera/Image detector or Depth.
 * Twenty selected images at one score threshold and IoU 0.5 are a sanity check, not COCO mAP.
 * Every GPU create/invoke/close stays on this JUnit thread. No delegate/model fallback is installed.
 * Native delegate logs must establish actual delegated nodes; attachment alone is not that proof.
 * Forty invocations are bounded by a deadline between pairs; native calls are never forcibly closed.
 */
@RunWith(AndroidJUnit4::class)
class HeterogeneousPositiveFixtureDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val runId = UUID.randomUUID().toString()
    private var sequence = 0L

    @Test
    fun groundTruthSanityUsesOneToOneMatchingAndCrowdPredictionArea() {
        val broad = GroundTruth(1, "person", RectNorm(0.1f, 0f, 0.4f, 0.5f), false)
        val restricted = GroundTruth(2, "person", RectNorm(0.2f, 0f, 0.4f, 0.5f), false)
        val fixture = Fixture(1, "", 100, 100, "", listOf(broad, restricted))
        val predictions = listOf(
            DetectionCandidate("person", 0.9f, RectNorm(0.1f, 0f, 0.4f, 0.5f)),
            DetectionCandidate("person", 0.9f, RectNorm(0f, 0f, 0.4f, 0.5f)),
        )
        val matched = evaluateGroundTruth(fixture, predictions, setOf("person", "car"))
        assertEquals(2, matched.matches.size)
        assertTrue(matched.missed.isEmpty() && matched.extra.isEmpty())
        val duplicate = evaluateGroundTruth(fixture, predictions.take(1), setOf("person", "car"))
        assertEquals(1, duplicate.matches.size)
        assertEquals(1, duplicate.missed.size)

        val crowd = fixture.copy(annotations = listOf(GroundTruth(3, "person", RectNorm(0f, 0f, 1f, 1f), true)))
        val crowdResult = evaluateGroundTruth(crowd, listOf(
            DetectionCandidate("person", 0.9f, RectNorm(0.1f, 0.1f, 0.1f, 0.1f)),
            DetectionCandidate("car", 0.9f, RectNorm(0.1f, 0.1f, 0.1f, 0.1f)),
            DetectionCandidate("crosswalk", 0.9f, RectNorm(0f, 0f, 1f, 1f)),
        ), setOf("person", "car"))
        assertEquals(1, crowdResult.crowdIgnored)
        assertEquals(1, crowdResult.extra.size)
        assertEquals(1, crowdResult.unmappedPredictions)
        assertTrue(crowdResult.missed.isEmpty())
        assertTrue(crowdResult.json().isNull("recall"))
    }

    @Test
    fun compareOriginalCpuAndStrictGpuOnPositiveFixtures() {
        assumeTrue("Requires -e allowHeterogeneousPositiveFixtureTest true",
            InstrumentationRegistry.getArguments().getString("allowHeterogeneousPositiveFixtureTest") == "true")
        val startedMs = SystemClock.elapsedRealtime()
        var stage = "fixture_load"
        var completed = 0
        var rawFailures = 0
        var rawSetFailures = 0
        var detectionFailures = 0
        var cpuPositiveImages = 0
        var gpuPositiveImages = 0
        var cpuMatchedImages = 0
        var gpuMatchedImages = 0
        var lostReferenceMatches = 0
        var gainedCandidateMatches = 0
        var failure: Throwable? = null
        var cpu: FixtureInterpreter? = null
        var gpu: FixtureInterpreter? = null
        val cpuTotals = SanityTotals()
        val gpuTotals = SanityTotals()
        val cleanupFailures = JSONObject()
        emit("setup", JSONObject().put("purpose", "PUBLIC_POSITIVE_FIXTURE_AGREEMENT_AND_GT_SANITY")
            .put("fixtureCount", 20).put("maximumInvocations", 40).put("maximumElapsedMs", 120_000)
            .put("reference", "ORIGINAL_FLOAT32_CPU_4").put("candidate", "GPU_COMPATIBLE_768_STRICT_GPU")
            .put("gpuPrecisionLossAllowed", false).put("fallbackAllowed", false)
            .put("inputContract", "SAME_FLOAT32_NHWC_1_768_768_3_TENSOR")
            .put("cameraUsed", false).put("imagesSaved", false).put("cocoMapComputed", false)
            .put("nativeGpuDelegationEvidenceRequired", true))
        try {
            val config = TwoModelRuntimeConfig.load(instrumentation.targetContext)
            val reference = requireNotNull(config.unifiedWalksafe).copy(
                asset = "models/walksafe_unified_yolo26n_768_float32.tflite",
                artifactSha256 = ORIGINAL_SHA,
            )
            val candidate = requireNotNull(HeterogeneousModelCandidates.select(config, "gpu_compatible_768").unifiedWalksafe)
            check(reference.enabled && reference.inputSize == 768 && candidate.inputSize == 768)
            check(reference.artifactSha256 == ORIGINAL_SHA) { "Reference must remain the approved original model" }
            val manifestBytes = instrumentation.context.assets.open("$ASSET_ROOT/gt.json").use { it.readBytes() }
            check(sha256(manifestBytes) == FIXTURE_GT_SHA) { "Expected the approved 20-image GT manifest" }
            val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
            val mappedClasses = parseMappedClasses(manifest.getJSONArray("category_mapping"), reference)
            val fixtures = parseFixtures(manifest.getJSONArray("images"), mappedClasses)
            check(fixtures.size == 20 && fixtures.map { it.id }.toSet().size == 20) { "Exactly 20 unique fixtures required" }
            check(fixtures.all { item -> item.annotations.any { !it.crowd } }) { "Every fixture requires non-crowd GT" }
            emit("manifest", JSONObject().put("sha256", sha256(manifestBytes)).put("version", manifest.get("version"))
                .put("mappedClasses", JSONArray(mappedClasses.values.toList()))
                .put("classScoreThresholds", JSONObject(reference.classes.associateWith { reference.thresholdForClass(it) }))
                .put("groundTruthCount", fixtures.sumOf { fixture -> fixture.annotations.count { !it.crowd } })
                .put("crowdGroundTruthCount", fixtures.sumOf { fixture -> fixture.annotations.count { it.crowd } })
                .put("iouThreshold", IOU_THRESHOLD).put("crowdHandling", "SAME_CLASS_INTERSECTION_OVER_PREDICTION_AREA_GE_0_5")
                .put("matching", "MAXIMUM_CARDINALITY_SAME_CLASS_BIPARTITE_AT_FIXED_THRESHOLD")
                .put("gtAnnotationIdChangeInterpretation", "ASSIGNMENT_CHANGE_NOT_AUTOMATIC_COUNT_REGRESSION")
                .put("unmappedPredictions", "EXCLUDED_FROM_GT_SANITY_RETAINED_IN_OUTPUT_AGREEMENT"))
            stage = "cpu_create"
            val cpuHandle = createInterpreter(instrumentation.targetContext, reference, useGpu = false).also { cpu = it }
            emit("runtime", cpuHandle.json("cpu", reference))
            stage = "gpu_create"
            val gpuHandle = createInterpreter(instrumentation.context, candidate, useGpu = true).also { gpu = it }
            emit("runtime", gpuHandle.json("gpu", candidate))
            val preprocessor = YuvImagePreprocessor()
            for ((index, fixture) in fixtures.withIndex()) {
                check(SystemClock.elapsedRealtime() - startedMs < 120_000L) { "Fixture deadline exceeded between pairs" }
                stage = "fixture_${fixture.id}_decode"
                val image = decodeFixture(fixture)
                val input = preprocessor.preprocess(image, 768)
                check(input.inputBuffer.remaining() == 768 * 768 * 3 * 4)
                val inputHash = sha256(input.inputBuffer)
                stage = "fixture_${fixture.id}_cpu_invoke"
                val cpuOutput = cpuHandle.infer(input.inputBuffer)
                check(sha256(input.inputBuffer) == inputHash) { "CPU modified the shared input tensor" }
                stage = "fixture_${fixture.id}_gpu_invoke"
                val gpuOutput = gpuHandle.infer(input.inputBuffer)
                check(sha256(input.inputBuffer) == inputHash) { "GPU modified the shared input tensor" }
                val cpuDetections = parseDetections(cpuOutput.raw, reference, input.transform)
                val gpuDetections = parseDetections(gpuOutput.raw, candidate, input.transform)
                val rawComparison = HeterogeneousOutputComparison.compareRaw(cpuOutput.raw, gpuOutput.raw, candidate)
                val rawSetComparison = HeterogeneousOutputComparison.compareRawSet(cpuOutput.raw, gpuOutput.raw, candidate)
                val detectionComparison = HeterogeneousOutputComparison.compareDetections(cpuDetections, gpuDetections)
                val cpuSanity = evaluateGroundTruth(fixture, cpuDetections, mappedClasses.values.toSet())
                val gpuSanity = evaluateGroundTruth(fixture, gpuDetections, mappedClasses.values.toSet())
                if (!rawComparison.getBoolean("withinTolerance")) rawFailures += 1
                if (!rawSetComparison.getBoolean("withinTolerance")) rawSetFailures += 1
                if (!detectionComparison.getBoolean("allDetectionsMatched")) detectionFailures += 1
                if (cpuDetections.isNotEmpty()) cpuPositiveImages += 1
                if (gpuDetections.isNotEmpty()) gpuPositiveImages += 1
                if (cpuSanity.matches.isNotEmpty()) cpuMatchedImages += 1
                if (gpuSanity.matches.isNotEmpty()) gpuMatchedImages += 1
                val cpuIds = cpuSanity.matches.map { it.truth.id }.toSet()
                val gpuIds = gpuSanity.matches.map { it.truth.id }.toSet()
                lostReferenceMatches += (cpuIds - gpuIds).size
                gainedCandidateMatches += (gpuIds - cpuIds).size
                cpuTotals.add(cpuSanity)
                gpuTotals.add(gpuSanity)
                completed += 1
                emit("fixture", JSONObject().put("index", index).put("imageId", fixture.id)
                    .put("fixtureSha256", fixture.sha256).put("sharedInputSha256", inputHash)
                    .put("cpuInvocationMs", cpuOutput.elapsedMs).put("gpuInvocationMs", gpuOutput.elapsedMs)
                    .put("invocationsAreWarmedBenchmark", false).put("gpuDelegateAttachedAndInvokeSucceeded", true)
                    .put("appFallbackUsed", false).put("raw", rawComparison).put("filtered", detectionComparison)
                    .put("cpuGroundTruth", cpuSanity.json()).put("gpuGroundTruth", gpuSanity.json())
                    .put("lostReferenceMatchedAnnotationIds", JSONArray((cpuIds - gpuIds).toList()))
                    .put("gainedCandidateMatchedAnnotationIds", JSONArray((gpuIds - cpuIds).toList())))
                emit("raw_set", JSONObject().put("imageId", fixture.id).put("rawSet", rawSetComparison)
                    .put("strictSameRowWithinTolerance", rawComparison.getBoolean("withinTolerance")))
                emitGroundTruthDetails("cpu", fixture, cpuSanity)
                emitGroundTruthDetails("gpu", fixture, gpuSanity)
            }
            stage = "complete"
        } catch (error: Throwable) {
            failure = error
            emit("error", JSONObject().put("stage", stage).put("exceptionClass", error.javaClass.simpleName)
                .put("suppressedExceptionClasses", JSONArray(error.suppressed.map { it.javaClass.simpleName })))
        } finally {
            listOf("gpu" to gpu, "cpu" to cpu).forEach { (label, handle) ->
                try { handle?.close() } catch (error: Throwable) {
                    cleanupFailures.put(label, error.javaClass.simpleName)
                    if (failure == null) failure = error else failure?.addSuppressed(error)
                }
            }
        }
        // TopK row order is diagnostic; the functional contract preserves all rows, filters and GT matches.
        val functionalAgreement = rawSetFailures == 0 && detectionFailures == 0 &&
            lostReferenceMatches == 0 && gainedCandidateMatches == 0
        val outcome = when {
            failure != null || completed != 20 || !functionalAgreement -> "FAIL"
            cpuPositiveImages == 0 || cpuMatchedImages == 0 -> "INCONCLUSIVE_NO_REFERENCE_GT_POSITIVES"
            gpuMatchedImages == 0 -> "INCONCLUSIVE_NO_CANDIDATE_GT_POSITIVES"
            else -> "OUTPUT_AGREEMENT_PASS_WITH_GT_SANITY"
        }
        emit("summary", JSONObject().put("outcome", outcome).put("completedFixtures", completed)
            .put("filteredOutputAgreement", when {
                completed != 20 -> "INCOMPLETE"
                detectionFailures > 0 -> "FAIL"
                cpuPositiveImages == 0 && gpuPositiveImages == 0 -> "INCONCLUSIVE_NO_POSITIVE_DETECTIONS"
                else -> "PASS"
            })
            .put("rawComparisonFailures", rawFailures).put("filteredComparisonFailures", detectionFailures)
            .put("rawSetComparisonFailures", rawSetFailures).put("strictRawSameRowDiagnosticsPreserved", true)
            .put("rawOrderingRequirement", "UNORDERED_300_ROWS_ONE_TO_ONE_SAME_TOLERANCES_CLASS_AND_THRESHOLD_GATE")
            .put("functionalAgreementRequires", "RAW_SET_AND_FILTERED_AGREEMENT_AND_UNCHANGED_GT_MATCHES")
            .put("strictRawOrderingRole", "DIAGNOSTIC_ONLY")
            .put("cpuPositiveImages", cpuPositiveImages).put("gpuPositiveImages", gpuPositiveImages)
            .put("cpuGtMatchedImages", cpuMatchedImages).put("gpuGtMatchedImages", gpuMatchedImages)
            .put("lostReferenceGtMatches", lostReferenceMatches).put("gainedCandidateGtMatches", gainedCandidateMatches)
            .put("gtTruePositiveDelta", gpuTotals.truePositives - cpuTotals.truePositives)
            .put("cpuGroundTruth", cpuTotals.json()).put("gpuGroundTruth", gpuTotals.json())
            .put("cleanupFailures", cleanupFailures)
            .put("allInterpretersClosed", cleanupFailures.length() == 0 && failure?.suppressed?.isEmpty() != false)
            .put("elapsedMs", SystemClock.elapsedRealtime() - startedMs).put("cocoMapComputed", false)
            .put("productionCameraDetectorValidated", false).put("nativeGpuDelegationEvidenceRequired", true))
        failure?.let { throw it }
        assertTrue("Expected 20 comparisons; inspect WalkSafePositiveTest evidence", completed == 20)
        assertTrue("Functional agreement failed; inspect raw set, filtered output and GT matches", functionalAgreement)
        assumeTrue("Inconclusive: original CPU produced no GT-matched positive fixture", cpuMatchedImages > 0)
        assumeTrue("Inconclusive: candidate GPU produced no GT-matched positive fixture", gpuMatchedImages > 0)
    }

    private fun createInterpreter(context: Context, config: ModelRuntimeConfig, useGpu: Boolean): FixtureInterpreter {
        val model = context.assets.openFd(config.asset).use { descriptor ->
            descriptor.createInputStream().channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.length)
            }
        }
        check(sha256(model) == config.artifactSha256) { "Model asset SHA256 mismatch" }
        var delegate: GpuDelegate? = null
        var interpreter: Interpreter? = null
        try {
            @Suppress("DEPRECATION")
            val options = Interpreter.Options().setNumThreads(4).setUseNNAPI(false)
            if (useGpu) {
                CompatibilityList().use { check(it.isDelegateSupportedOnThisDevice) { "GPU unsupported; fallback forbidden" } }
                delegate = GpuDelegate(GpuDelegateFactory.Options().setPrecisionLossAllowed(false)
                    .setInferencePreference(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED))
                options.addDelegate(delegate)
            }
            interpreter = Interpreter(model, options)
            check(interpreter.inputTensorCount == 1 && interpreter.outputTensorCount == 1)
            val input = interpreter.getInputTensor(0)
            val output = interpreter.getOutputTensor(0)
            check(input.dataType() == DataType.FLOAT32 && output.dataType() == DataType.FLOAT32)
            check(input.shape().contentEquals(intArrayOf(1, 768, 768, 3)))
            check(output.shape().contentEquals(intArrayOf(1, 300, 6)))
            return FixtureInterpreter(interpreter, delegate)
        } catch (error: Throwable) {
            try { interpreter?.close() } catch (closeError: Throwable) { error.addSuppressed(closeError) }
            try { delegate?.close() } catch (closeError: Throwable) { error.addSuppressed(closeError) }
            throw error
        }
    }

    private class FixtureInterpreter(private val interpreter: Interpreter, private val delegate: GpuDelegate?) : Closeable {
        private val owner = Thread.currentThread()
        private val output = Array(1) { Array(300) { FloatArray(6) } }
        fun infer(input: ByteBuffer): FixtureOutput {
            check(Thread.currentThread() === owner)
            val startedNs = System.nanoTime()
            interpreter.run(input.duplicate().apply { rewind() }, output)
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000.0
            return FixtureOutput(FloatArray(1_800) { output[0][it / 6][it % 6] }, elapsedMs)
        }
        fun json(lane: String, config: ModelRuntimeConfig): JSONObject = JSONObject().put("lane", lane)
            .put("assetSha256", config.artifactSha256).put("inputShape", JSONArray(listOf(1, 768, 768, 3)))
            .put("outputShape", JSONArray(listOf(1, 300, 6))).put("inputDtype", "FLOAT32").put("outputDtype", "FLOAT32")
            .put("numThreads", 4).put("gpuDelegateAttached", delegate != null).put("gpuPrecisionLossAllowed", false)
            .put("appFallbackUsed", false).put("nativeDelegatedNodeCount", JSONObject.NULL)
            .put("creationThreadId", owner.id)
        override fun close() {
            check(Thread.currentThread() === owner)
            try { interpreter.close() } finally { delegate?.close() }
        }
    }

    private fun decodeFixture(fixture: Fixture): ArgbImage {
        val bytes = instrumentation.context.assets.open("$ASSET_ROOT/${fixture.path}").use { it.readBytes() }
        check(sha256(bytes) == fixture.sha256) { "Fixture SHA256 mismatch" }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inScaled = false })) { "Fixture decode failed" }
        try {
            check(bitmap.width == fixture.width && bitmap.height == fixture.height) { "Decoded fixture dimensions differ from GT" }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return ArgbImage(bitmap.width, bitmap.height, pixels)
        } finally { bitmap.recycle() }
    }

    private fun parseDetections(raw: FloatArray, config: ModelRuntimeConfig, transform: LetterboxTransform): List<DetectionCandidate> =
        YoloEndToEndOutputParser(config.inputSize, config::classNameForId, config::thresholdForClass, config::isAllowedClass)
            .parse(raw).map { it.copy(bboxNorm = transform.modelRectToImageRect(it.bboxNorm)) }

    private fun parseMappedClasses(mapping: JSONArray, config: ModelRuntimeConfig): Map<Int, String> =
        (0 until mapping.length()).associate { index ->
            val item = mapping.getJSONObject(index)
            val id = item.getInt("app_class_id")
            val name = item.getString("app_class_name")
            check(config.classNameForId(id) == name && config.isAllowedClass(name)) { "GT/model class mapping mismatch" }
            id to name
        }.also { check(it.size == 7 && it.keys == (0..6).toSet()) { "Expected all seven mapped COCO classes" } }

    private fun parseFixtures(images: JSONArray, classes: Map<Int, String>): List<Fixture> = (0 until images.length()).map { index ->
        val item = images.getJSONObject(index)
        val width = item.getInt("width")
        val height = item.getInt("height")
        check(width > 0 && height > 0)
        val path = item.getString("file_name")
        check(path.matches(Regex("images/[0-9]{12}\\.jpg"))) { "Unexpected public fixture path" }
        val annotations = item.getJSONArray("annotations")
        Fixture(item.getLong("image_id"), path, width, height, item.getString("sha256"),
            (0 until annotations.length()).map { annotationIndex ->
                val annotation = annotations.getJSONObject(annotationIndex)
                val box = annotation.getJSONArray("bbox_xywh")
                check(box.length() == 4)
                val values = (0..3).map { box.getDouble(it).toFloat() }
                check(values.all { it.isFinite() } && values[2] > 0f && values[3] > 0f)
                GroundTruth(annotation.getLong("annotation_id"), requireNotNull(classes[annotation.getInt("app_class_id")]),
                    RectNorm(values[0] / width, values[1] / height, values[2] / width, values[3] / height),
                    annotation.getInt("iscrowd") != 0)
            })
    }

    private fun evaluateGroundTruth(fixture: Fixture, predictions: List<DetectionCandidate>, classes: Set<String>): SanityResult {
        val truths = fixture.annotations.filter { !it.crowd }
        val crowds = fixture.annotations.filter { it.crowd }
        val evaluated = predictions.filter { it.className in classes }
        val assigned = IntArray(evaluated.size) { -1 }
        fun match(truthIndex: Int, visited: BooleanArray): Boolean {
            val truth = truths[truthIndex]
            val candidates = evaluated.indices.filter {
                evaluated[it].className == truth.className && iou(truth.box, evaluated[it].bboxNorm) >= IOU_THRESHOLD
            }.sortedByDescending { iou(truth.box, evaluated[it].bboxNorm) }
            for (predictionIndex in candidates) {
                if (visited[predictionIndex]) continue
                visited[predictionIndex] = true
                if (assigned[predictionIndex] < 0 || match(assigned[predictionIndex], visited)) {
                    assigned[predictionIndex] = truthIndex
                    return true
                }
            }
            return false
        }
        truths.indices.forEach { match(it, BooleanArray(evaluated.size)) }
        val matches = evaluated.indices.filter { assigned[it] >= 0 }.map { index ->
            val truth = truths[assigned[index]]
            GroundTruthMatch(truth, evaluated[index], iou(truth.box, evaluated[index].bboxNorm))
        }
        val matchedIds = matches.map { it.truth.id }.toSet()
        val unmatched = evaluated.indices.filter { assigned[it] < 0 }.map { evaluated[it] }
        val (ignored, extras) = unmatched.partition { prediction ->
            crowds.any { it.className == prediction.className &&
                intersection(it.box, prediction.bboxNorm) / maxOf(prediction.bboxNorm.area, 1e-12f) >= IOU_THRESHOLD }
        }
        return SanityResult(matches, truths.filter { it.id !in matchedIds }, extras, ignored.size,
            predictions.count { it.className !in classes })
    }

    private fun emitGroundTruthDetails(lane: String, fixture: Fixture, result: SanityResult) {
        fun details(kind: String, className: String): JSONObject = JSONObject().put("lane", lane)
            .put("imageId", fixture.id).put("matchStatus", kind).put("className", className)
            .put("coordinateSpace", "NORMALIZED_ORIGINAL_PUBLIC_IMAGE_XYWH")
        result.matches.forEach { match -> emit("gt_box", details("MATCHED", match.truth.className)
            .put("annotationId", match.truth.id).put("gtBox", boxJson(match.truth.box))
            .put("predictionBox", boxJson(match.prediction.bboxNorm)).put("score", match.prediction.detectionConfidence).put("iou", match.iou)) }
        result.missed.forEach { truth -> emit("gt_box", details("MISSED", truth.className)
            .put("annotationId", truth.id).put("gtBox", boxJson(truth.box))) }
        result.extra.forEach { prediction -> emit("gt_box", details("EXTRA", prediction.className)
            .put("predictionBox", boxJson(prediction.bboxNorm)).put("score", prediction.detectionConfidence)) }
    }

    private data class Fixture(val id: Long, val path: String, val width: Int, val height: Int,
        val sha256: String, val annotations: List<GroundTruth>)
    private data class GroundTruth(val id: Long, val className: String, val box: RectNorm, val crowd: Boolean)
    private data class GroundTruthMatch(val truth: GroundTruth, val prediction: DetectionCandidate, val iou: Float)
    private data class FixtureOutput(val raw: FloatArray, val elapsedMs: Double)
    private data class SanityResult(val matches: List<GroundTruthMatch>, val missed: List<GroundTruth>,
        val extra: List<DetectionCandidate>, val crowdIgnored: Int, val unmappedPredictions: Int) {
        fun json(): JSONObject = SanityTotals().also { it.add(this) }.json()
    }
    private class SanityTotals {
        var truePositives = 0
        var falseNegatives = 0
        var falsePositives = 0
        var crowdIgnored = 0
        var unmappedPredictions = 0
        var iouTotal = 0.0
        fun add(result: SanityResult) {
            truePositives += result.matches.size
            falseNegatives += result.missed.size
            falsePositives += result.extra.size
            crowdIgnored += result.crowdIgnored
            unmappedPredictions += result.unmappedPredictions
            iouTotal += result.matches.sumOf { it.iou.toDouble() }
        }
        fun json(): JSONObject = JSONObject().put("tp", truePositives).put("fn", falseNegatives).put("fp", falsePositives)
            .put("crowdIgnored", crowdIgnored).put("unmappedPredictions", unmappedPredictions)
            .put("precision", ratio(truePositives, truePositives + falsePositives))
            .put("recall", ratio(truePositives, truePositives + falseNegatives))
            .put("meanMatchedIou", if (truePositives > 0) iouTotal / truePositives else JSONObject.NULL)
            .put("cocoMapComputed", false)
        private fun ratio(numerator: Int, denominator: Int): Any =
            if (denominator > 0) numerator.toDouble() / denominator else JSONObject.NULL
    }

    private fun emit(kind: String, details: JSONObject) {
        val line = details.put("schemaVersion", 1).put("kind", kind).put("runId", runId)
            .put("sequence", sequence++).put("elapsedRealtimeMs", SystemClock.elapsedRealtime()).toString()
        Log.i("WalkSafePositiveTest", line)
        instrumentation.sendStatus(0, Bundle().apply { putString("walksafe_positive_fixture", line) })
    }

    private companion object {
        const val ASSET_ROOT = "positive-fixtures"
        const val ORIGINAL_SHA = "92b39d3b24d97519038db5ef8ea613c32a46aaefabc5080fd989b7ab5c1fbb19"
        const val FIXTURE_GT_SHA = "4988b11b6a764cdb91ac75f9a2af8967255f83515623fa08e8918a017aac34f9"
        const val IOU_THRESHOLD = 0.5f
        fun boxJson(box: RectNorm): JSONArray = JSONArray(listOf(box.x, box.y, box.width, box.height))
        fun intersection(a: RectNorm, b: RectNorm): Float =
            (minOf(a.x + a.width, b.x + b.width) - maxOf(a.x, b.x)).coerceAtLeast(0f) *
                (minOf(a.y + a.height, b.y + b.height) - maxOf(a.y, b.y)).coerceAtLeast(0f)
        fun iou(a: RectNorm, b: RectNorm): Float {
            val overlap = intersection(a, b)
            return overlap / maxOf(a.area + b.area - overlap, 1e-12f)
        }
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
        fun sha256(buffer: ByteBuffer): String = MessageDigest.getInstance("SHA-256").apply {
            update(buffer.duplicate().apply { rewind() })
        }.digest().toHex()
        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

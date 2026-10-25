package kr.co.hanium.dreamup.walksafe.inference

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Static bundled COCO fixtures only. Captures real product Float32 bytes, never camera/user images. */
@RunWith(AndroidJUnit4::class)
class NamedModelTensorParityCaptureDeviceTest {
    @Test
    fun captureActualCpuTensorsWithExistingPathExactControl() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val threadDiagnostic = arguments.getString("allowNamedModelThreadDiagnostic") == "true"
        val cpuThreads = (arguments.getString("namedModelParityCpuThreads") ?: "4").toIntOrNull()
        require(cpuThreads in setOf(1, 2, 4)) { "namedModelParityCpuThreads must be 1, 2 or 4" }
        require(cpuThreads == 4 || threadDiagnostic) { "CPU1/2 requires allowNamedModelThreadDiagnostic=true" }
        val requestedThreads = requireNotNull(cpuThreads)
        val config = TwoModelRuntimeConfig.load(context)
        val model = requireNotNull(config.unifiedWalksafe)
        assertEquals(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, config.primaryModelKey)
        assertEquals(null, config.fallbackModelKey)
        assertEquals(MODEL_SHA, model.artifactSha256)
        assertEquals(PT_SHA, model.sourceModelSha256)
        assertEquals(MODEL_SHA, context.assets.open(model.asset).use { sha256(it.readBytes()) })
        assertEquals(768, model.inputSize)
        assertEquals(listOf(1, 25, 12096), model.outputTensorShape().toList())
        assertEquals("person", model.classes[20])
        assertEquals("moving_e_scooter", model.classes[10])
        assertEquals("bicycle", model.classes[8])
        val fixtures = RuntimeCalibrationFixtures(context)
        val manifest = fixtures.loadManifest()
        val selected = listOf("coco_785", "coco_885", "coco_11149").map { id ->
            manifest.fixtures.single { it.id == id }.also {
                assertTrue(it.required && RuntimeCalibrationFixtureRole.POSITIVE in it.roles)
            }
        }
        val directory = File(context.cacheDir, "named-model-parity-${SystemClock.elapsedRealtimeNanos()}")
        check(directory.mkdir()) { "Refuse to overwrite an existing capture directory" }
        val fixtureRows = JSONArray()
        val metadata = JSONObject().put("format", "named-model-tensor-parity-v1")
            .put("complete", false).put("fixtureManifestVersion", manifest.version)
            .put("fixtureManifestSha256", manifest.sha256).put("configVersion", config.bundleVersion)
            .put("configSha256", context.assets.open("model-config/two_model_runtime.json").use { sha256(it.readBytes()) })
            .put("modelAsset", model.asset).put("modelSha256", MODEL_SHA).put("sourcePtSha256", PT_SHA)
            .put("inputDtype", "FLOAT32").put("inputLayout", "NHWC").put("colorOrder", "RGB")
            .put("inputNormalization", "production uint8 bilinear / 255f")
            .put("inputShape", JSONArray(listOf(1, 768, 768, 3)))
            .put("outputDtype", "FLOAT32").put("outputLayout", "batch_channels_anchors")
            .put("outputShape", JSONArray(model.outputTensorShape().toList()))
            .put("classNames", JSONArray(model.classes)).put("requestedDelegate", "cpu").put("numThreads", requestedThreads)
            .put("threadDiagnostic", threadDiagnostic).put("fallbackToCpu", !threadDiagnostic)
            .put("processPid", android.os.Process.myPid()).put("callerTid", android.os.Process.myTid())
            .put("callerJavaPriority", Thread.currentThread().priority).put("availableProcessors", Runtime.getRuntime().availableProcessors())
            .put("cameraUsed", false).put("ttsContentionControlled", false).put("mainTailValidated", false)
            .put("fixtureRows", fixtureRows).put("accuracyOrGtMatchVerified", false)
        fun save() = File(directory, "capture.json").writeText(metadata.toString(2) + "\n")
        save()
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "PARITY_CAPTURE relativeCacheDirectory=${directory.name}\n")
        })
        var detector: TfliteAndroidFrameDetector? = null
        var primaryFailure: Throwable? = null
        try {
            val loadStartedNs = SystemClock.elapsedRealtimeNanos()
            val load = TfliteAndroidFrameDetector.createWithStatus(context, config,
                runtimeOverride = ModelRuntimeOptions(delegate = "cpu", numThreads = requestedThreads, fallbackToCpu = !threadDiagnostic),
                preprocessingStrategy = YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR)
            metadata.put("loadStartedElapsedNs", loadStartedNs).put("loadCompletedElapsedNs", SystemClock.elapsedRealtimeNanos())
                .put("loadModelKey", load.modelKey ?: JSONObject.NULL).put("loadFallbackUsed", load.fallbackUsed)
            val active = requireNotNull(load.detector) { load.reason }
            detector = active
            if (threadDiagnostic) assertTrue(load.detectorAvailable && !load.fallbackUsed &&
                load.modelKey == TwoModelRuntimeConfig.UNIFIED_MODEL_KEY)
            assertTrue(load.detectorAvailable)
            assertTrue("Actual interpreter tensor contract must pass", active.lifecycleTiming().phases.any {
                it.phase == AndroidDetectorLoadPhase.TENSOR_CHECK && it.modelKey == model.key &&
                    it.delegate == "cpu" && it.outcome == AndroidDetectorPhaseOutcome.SUCCEEDED
            })
            metadata.put("nativeTensorContractVerified", true)
            val preprocessor = YuvImagePreprocessor()
            for (fixture in selected) {
                val image = fixtures.loadArgb(fixture)
                val inputPreparationStartedNs = SystemClock.elapsedRealtimeNanos()
                val startedNs = System.nanoTime()
                val input = preprocessor.preprocessBilinear(image, model.inputSize)
                val preprocessingMs = (System.nanoTime() - startedNs) / 1_000_000L
                val inputPreparationCompletedNs = SystemClock.elapsedRealtimeNanos()
                val byteOrder = input.inputBuffer.order()
                assertEquals(ByteOrder.nativeOrder(), byteOrder)
                val inputBytes = tensorBytes(input.inputBuffer)
                assertEquals(768 * 768 * 3 * Float.SIZE_BYTES, inputBytes.size)
                val inputHash = sha256(inputBytes)
                File(directory, "${fixture.id}.input.f32").writeBytes(inputBytes)
                val row = JSONObject().put("fixtureId", fixture.id).put("fixtureJpegSha256", fixture.sha256)
                    .put("fixtureAsset", fixture.asset).put("width", image.width).put("height", image.height)
                    .put("inputFile", "${fixture.id}.input.f32").put("inputBytes", inputBytes.size)
                    .put("byteOrder", byteOrder.toString()).put("inputBeforeSha256", inputHash)
                    .put("inputPreparationStartedElapsedNs", inputPreparationStartedNs)
                    .put("inputPreparationCompletedElapsedNs", inputPreparationCompletedNs)
                    .put("geometry", geometry(input.transform))
                fixtureRows.put(row)
                save()

                // Same image instance, same model size and the same production bilinear algorithm.
                // Only the existing raw snapshot seam is used; no private detector graph is inspected.
                val existingStartedNs = SystemClock.elapsedRealtimeNanos()
                val existing = active.detectCalibrationImage(image)
                val existingCompletedNs = SystemClock.elapsedRealtimeNanos()
                val existingRaw = requireNotNull(active.copyLastUnifiedOutputForTest())
                row.put("inputAfterExistingSha256", sha256(tensorBytes(input.inputBuffer)))
                val preparedStartedNs = SystemClock.elapsedRealtimeNanos()
                val captured = active.inferPreparedUnifiedForCalibration(input, preprocessingMs,
                    YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR, copyRawOutput = !threadDiagnostic)
                val preparedCompletedNs = SystemClock.elapsedRealtimeNanos()
                // New component timing excludes raw-copy/file I/O; CPU4's original capture path remains unchanged.
                val preparedRaw = if (threadDiagnostic) requireNotNull(active.copyLastUnifiedOutputForTest())
                    else requireNotNull(captured.rawOutput)
                row.put("existingInvocationStartedElapsedNs", existingStartedNs).put("existingInvocationCompletedElapsedNs", existingCompletedNs)
                    .put("preparedInvocationStartedElapsedNs", preparedStartedNs).put("preparedInvocationCompletedElapsedNs", preparedCompletedNs)
                    .put("existingFirstHarnessInvocation", fixtureRows.length() == 1).put("preparedFirstHarnessInvocation", false)
                    .put("preparedTimedCallIncludesRawCopy", !threadDiagnostic)
                    .put("existingModelPreprocessMs", existing.timing.modelPreprocessMs ?: JSONObject.NULL)
                    .put("existingModelInferenceMs", existing.timing.modelInferenceMs ?: JSONObject.NULL)
                    .put("existingModelParseMs", existing.timing.modelParseMs ?: JSONObject.NULL)
                    .put("preparedModelInferenceMs", captured.result.timing.modelInferenceMs ?: JSONObject.NULL)
                    .put("preparedModelParseMs", captured.result.timing.modelParseMs ?: JSONObject.NULL)
                if (threadDiagnostic) {
                    listOf(existing, captured.result).forEach { result ->
                        val actual = requireNotNull(result.timing.modelRuntime)
                        assertTrue(!result.partial && actual.requestedDelegate == "cpu" && actual.activeDelegate == "cpu" &&
                            actual.numThreads == requestedThreads && !actual.fallbackUsed)
                    }
                }
                row.put("inputAfterPreparedSha256", sha256(tensorBytes(input.inputBuffer)))
                assertEquals(25 * 12096, existingRaw.size)
                assertEquals(existingRaw.size, preparedRaw.size)
                writeOutput(directory, fixture.id, "existing", existingRaw, byteOrder, row)
                writeOutput(directory, fixture.id, "prepared", preparedRaw, byteOrder, row)
                val existingNonFinite = existingRaw.count { !it.isFinite() }
                val preparedNonFinite = preparedRaw.count { !it.isFinite() }
                row.put("existingRawNonFiniteCount", existingNonFinite)
                    .put("preparedRawNonFiniteCount", preparedNonFinite)
                save()
                assertEquals("Existing raw output must be finite for ${fixture.id}", 0, existingNonFinite)
                assertEquals("Prepared raw output must be finite for ${fixture.id}", 0, preparedNonFinite)
                var differentBits = 0
                var maxFiniteDifference = 0.0
                for (index in existingRaw.indices) {
                    if (existingRaw[index].toRawBits() != preparedRaw[index].toRawBits()) differentBits++
                    if (existingRaw[index].isFinite() && preparedRaw[index].isFinite()) {
                        maxFiniteDifference = maxOf(maxFiniteDifference,
                            abs(existingRaw[index].toDouble() - preparedRaw[index].toDouble()))
                    }
                }
                row.put("rawBitDifferenceCount", differentBits).put("rawMaxFiniteAbsoluteDifference", maxFiniteDifference)
                    .put("existingDetections", detections(existing, model.classes))
                    .put("preparedDetections", detections(captured.result, model.classes))
                    .put("existingRuntime", runtime(existing)).put("preparedRuntime", runtime(captured.result))
                    .put("existingStrategy", existing.timing.preprocessingStrategy)
                    .put("preparedStrategyLabel", captured.result.timing.preprocessingStrategy)
                    .put("actualInputPreparation", "FIXED_ARGB_BILINEAR")
                save() // Keep both real raw outputs even when the exact control fails.
                assertEquals(inputHash, row.getString("inputAfterExistingSha256"))
                assertEquals(inputHash, row.getString("inputAfterPreparedSha256"))
                assertEquals("Existing/prepared raw control must be bit-exact for ${fixture.id}", 0, differentBits)
                assertTrue(existing.inferenceCompleted && captured.result.inferenceCompleted)
                assertEquals(existing.detections, captured.result.detections)
                assertEquals("cpu", requireNotNull(existing.timing.modelRuntime).activeDelegate)
                assertEquals("cpu", requireNotNull(captured.result.timing.modelRuntime).activeDelegate)
                row.put("exactControlPassed", true)
                save()
            }
            metadata.put("complete", true)
        } catch (failure: Throwable) {
            primaryFailure = failure
            metadata.put("failure", failure.toString())
            throw failure
        } finally {
            try {
                detector?.close()
                val phases = detector?.lifecycleTiming()?.phases.orEmpty()
                metadata.put("nativePhases", JSONArray(phases.map { phase ->
                    JSONObject().put("phase", phase.phase.name).put("outcome", phase.outcome.name)
                        .put("model", phase.modelKey ?: JSONObject.NULL).put("delegate", phase.delegate ?: JSONObject.NULL)
                        .put("failure", phase.failureType ?: JSONObject.NULL)
                }))
                if (detector != null) assertTrue("Real native close must succeed",
                    phases.any { it.phase == AndroidDetectorLoadPhase.NATIVE_CLOSE &&
                        it.outcome == AndroidDetectorPhaseOutcome.SUCCEEDED })
            } catch (closeFailure: Throwable) {
                metadata.put("complete", false).put("closeFailure", closeFailure.toString())
                val originalFailure = primaryFailure
                if (originalFailure != null) originalFailure.addSuppressed(closeFailure)
                else { primaryFailure = closeFailure; throw closeFailure }
            } finally {
                try { save() }
                catch (saveFailure: Throwable) {
                    val originalFailure = primaryFailure
                    if (originalFailure != null) originalFailure.addSuppressed(saveFailure) else throw saveFailure
                }
            }
        }
    }

    private fun tensorBytes(buffer: ByteBuffer): ByteArray = buffer.duplicate().apply { clear() }.let {
        ByteArray(it.remaining()).also { bytes -> it.get(bytes) }
    }

    private fun writeOutput(directory: File, fixtureId: String, path: String, values: FloatArray,
        order: ByteOrder, row: JSONObject) {
        val bytes = ByteBuffer.allocate(values.size * Float.SIZE_BYTES).order(order)
        bytes.asFloatBuffer().put(values)
        val name = "$fixtureId.$path.output.f32"
        File(directory, name).writeBytes(bytes.array())
        row.put("${path}OutputFile", name).put("${path}OutputSha256", sha256(bytes.array()))
    }

    private fun geometry(transform: LetterboxTransform): JSONObject = JSONObject()
        .put("imageWidth", transform.imageWidth).put("imageHeight", transform.imageHeight)
        .put("modelSize", transform.modelSize).put("scale", transform.scale)
        .put("padX", transform.padX).put("padY", transform.padY)
        .put("resizedWidth", transform.resizedWidth).put("resizedHeight", transform.resizedHeight)

    private fun detections(result: AndroidDetectionResult, classes: List<String>): JSONArray =
        JSONArray(result.detections.map { detection ->
            val classId = classes.indexOf(detection.className)
            check(classId >= 0) { "Parsed class is absent from the actual ordered model classes" }
            JSONObject().put("classIndex", classId).put("className", detection.className)
                .put("score", detection.detectionConfidence)
                .put("bboxNormXywh", JSONArray(listOf(detection.bboxNorm.x, detection.bboxNorm.y,
                    detection.bboxNorm.width, detection.bboxNorm.height)))
        })

    private fun runtime(result: AndroidDetectionResult): JSONObject {
        val runtime = requireNotNull(result.timing.modelRuntime)
        return JSONObject().put("requestedDelegate", runtime.requestedDelegate)
            .put("activeDelegate", runtime.activeDelegate).put("numThreads", runtime.numThreads)
            .put("fallbackUsed", runtime.fallbackUsed).put("fallbackReason", runtime.fallbackReason ?: JSONObject.NULL)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val MODEL_SHA = "d10aa174a2ceb8ae116a09b7b570e27ff71c296fbd98f240c11e69dbdb434b57"
        private const val PT_SHA = "6f8ae9d2bb82f91e4391e42957a1b374bd4b14efa4702a86f7283454bc38cee0"
    }
}

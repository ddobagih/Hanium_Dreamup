package kr.co.hanium.dreamup.walksafe.inference

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.device.*

/** Fixed bundled public images only; does not open the camera or alter model thresholds. */
@RunWith(AndroidJUnit4::class)
class RuntimeCalibrationFixtureDeviceTest {
    @Test fun invalidBaselineStopsBeforeChallengerOrLiveMeasurement() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowCalibrationFixtureAudit") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = TwoModelRuntimeConfig.load(context)
        val model = requireNotNull(original.unifiedWalksafe).let {
            it.copy(runtime = it.runtime.copy(delegate = "cpu", numThreads = 4, fallbackToCpu = false))
        }
        val config = original.copy(unifiedWalksafe = model, fallbackModelKey = null,
            customTactile = null, cocoGeneral = null)
        val owner = Executors.newSingleThreadExecutor()
        val result = CompletableFuture<RuntimeCalibrationResult>()
        val drained = CountDownLatch(1)
        val stages = mutableListOf<RuntimeCalibrationProgress>()
        val detector = owner.submit<TfliteAndroidFrameDetector> {
            requireNotNull(TfliteAndroidFrameDetector.createWithStatus(context, config).detector)
        }.get(30, TimeUnit.SECONDS)
        val runner = AndroidRuntimeCalibrationRunner(context, config, detector, owner,
            RuntimeCandidate(RuntimeBackend.CPU, 4), "fixed-fixture-negative-path", RuntimeFeatureScope.CAMERA_TRACKING,
            YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR,
            environmentProvider = { error("A failing baseline must not reach environment measurement") },
            isCurrent = { true }, callbackExecutor = Executor { it.run() },
            onProgress = { synchronized(stages) { stages += it } },
            onSelectionReady = { it.reject(); error("A failing baseline must not offer selection") },
            onFinished = { result.complete(it) })
        runner.whenDrained { drained.countDown() }
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            assertTrue(runner.start())
            val value = result.get(30, TimeUnit.SECONDS)
            org.junit.Assert.assertEquals("baseline_fixture_role_mismatch", value.reason)
            org.junit.Assert.assertEquals(RuntimeSelectionStatus.INCONCLUSIVE, value.status)
            assertTrue(!value.adopted && value.pending && value.queueSnapshot == null)
            assertTrue(synchronized(stages) { stages.none { it.candidate?.backend == RuntimeBackend.GPU } })
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "BASELINE_PREFLIGHT reason=${value.reason} challenger_created=false camera_used=false elapsed_ms=${android.os.SystemClock.elapsedRealtime()-started}\n")
            })
        } finally {
            runner.cancel("test_cleanup")
            check(drained.await(60, TimeUnit.SECONDS)) { "Do not close native owner before runner drains" }
            owner.submit { detector.close() }.get(30, TimeUnit.SECONDS)
            owner.shutdown()
            check(owner.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun shippedCpuModelMeetsEachDeclaredFixtureRole() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowCalibrationFixtureAudit") == "true")
        val context = instrumentation.targetContext
        val original = TwoModelRuntimeConfig.load(context)
        val model = requireNotNull(original.unifiedWalksafe).let {
            it.copy(runtime = it.runtime.copy(delegate = "cpu", numThreads = 4, fallbackToCpu = false))
        }
        val config = original.copy(unifiedWalksafe = model, fallbackModelKey = null,
            customTactile = null, cocoGeneral = null)
        val fixtures = RuntimeCalibrationFixtures(context)
        val manifest = fixtures.loadManifest()
        val entries = JSONArray()
        val report = JSONObject().put("modelSha256", model.artifactSha256)
            .put("modelAsset", model.asset).put("fixtureHash", manifest.sha256)
            .put("cameraUsed", false).put("thresholdsChanged", false).put("fixtures", entries)
        val load = TfliteAndroidFrameDetector.createWithStatus(context, config,
            preprocessingStrategy = YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR)
        val detector = requireNotNull(load.detector)
        assertTrue(!load.fallbackUsed && load.modelKey == TwoModelRuntimeConfig.UNIFIED_MODEL_KEY)
        var passed = true
        try {
            val preprocessor = YuvImagePreprocessor()
            for (fixture in manifest.fixtures.filter { it.required }) {
                val input = preprocessor.preprocessBilinear(fixtures.loadArgb(fixture), model.inputSize)
                val a = detector.inferPreparedUnifiedForCalibration(input, 0L,
                    YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR, copyRawOutput = true)
                val b = detector.inferPreparedUnifiedForCalibration(input, 0L,
                    YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR, copyRawOutput = true)
                val equivalent = RuntimeCalibrationOutputGate.compare(a.rawOutput, b.rawOutput,
                    a.result.detections, b.result.detections, model)
                val roles = RuntimeCalibrationOutputGate.matchesFixtureRoles(a.result.detections, b.result.detections,
                    RuntimeCalibrationFixtureRole.POSITIVE in fixture.roles,
                    RuntimeCalibrationFixtureRole.EMPTY in fixture.roles,
                    fixture.groundTruth.filter { !it.isCrowd }.map { it.className to it.bboxNorm })
                val near = RuntimeCalibrationOutputGate.hasNearThresholdCandidate(a.rawOutput, model)
                val detections = JSONArray(a.result.detections.map {
                    JSONObject().put("class", it.className).put("score", it.detectionConfidence)
                        .put("box", JSONArray(listOf(it.bboxNorm.x, it.bboxNorm.y, it.bboxNorm.width, it.bboxNorm.height)))
                })
                val entry = JSONObject().put("id", fixture.id).put("roles", JSONArray(fixture.roles.map { it.name }))
                    .put("repeatOutputEquivalent", equivalent).put("fixtureRolesMatch", roles)
                    .put("nearThresholdObserved", near).put("detections", detections)
                entries.put(entry)
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "CALIBRATION_FIXTURE $entry\n") })
                passed = passed && equivalent && roles
            }
        } finally {
            detector.close()
            report.put("detectorClosed", true)
            File(context.cacheDir, "calibration-fixture-audit.json").writeText(report.toString(2))
        }
        assertTrue("Shipped model does not satisfy the declared fixture roles; see calibration-fixture-audit.json", passed)
    }
}

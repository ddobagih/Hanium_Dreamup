package kr.co.hanium.dreamup.walksafe.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kr.co.hanium.dreamup.walksafe.device.AndroidRuntimeCalibrationRunner
import kr.co.hanium.dreamup.walksafe.device.RuntimeCalibrationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real CPU A and pinned photos, with an impossible in-memory class only in the negative fixture. */
@RunWith(AndroidJUnit4::class)
class CalibrationBaselinePreflightDeviceTest {
    @Test fun controlledInvalidFixtureStopsBeforeQueueOrEnvironmentAndDrains() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = TwoModelRuntimeConfig.load(context)
        val primary = requireNotNull(original.unifiedWalksafe)
        val config = original.copy(fallbackModelKey = null, customTactile = null, cocoGeneral = null,
            personSupplement = null, unifiedWalksafe = primary.copy(
                runtime = primary.runtime.copy(delegate = "cpu", numThreads = 2, fallbackToCpu = false)))
        val fixtures = RuntimeCalibrationFixtures(context)
        val manifest = fixtures.loadManifest()
        val positive = manifest.fixtures.first { RuntimeCalibrationFixtureRole.POSITIVE in it.roles }
        val invalid = positive.copy(groundTruth = positive.groundTruth.map {
            it.copy(className = "controlled_class_absent_from_model", isCrowd = false)
        })
        assertFalse(primary.classes.contains("controlled_class_absent_from_model"))
        val source = object : AndroidRuntimeCalibrationRunner.FixtureSource {
            override fun loadManifest() = manifest.copy(fixtures = listOf(invalid))
            override fun loadArgb(fixture: RuntimeCalibrationFixture): ArgbImage {
                assertEquals(invalid, fixture)
                return fixtures.loadArgb(positive)
            }
        }
        val owner = Executors.newSingleThreadExecutor()
        val baseline = owner.submit<TfliteAndroidFrameDetector> {
            val load = TfliteAndroidFrameDetector.createWithStatus(context, config)
            assertFalse(load.fallbackUsed)
            requireNotNull(load.detector) { load.reason }
        }.get(30, TimeUnit.SECONDS)
        val result = CompletableFuture<RuntimeCalibrationResult>()
        val drained = CountDownLatch(1)
        val processors = AtomicInteger()
        val environments = AtomicInteger()
        val selections = AtomicInteger()
        val runner = AndroidRuntimeCalibrationRunner(context, config, baseline, owner,
            RuntimeCandidate(RuntimeBackend.CPU, 2), "controlled-invalid-fixture",
            RuntimeFeatureScope.CAMERA_TRACKING, YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR,
            environmentProvider = { environments.incrementAndGet(); error("No environment measurement allowed") },
            isCurrent = { true }, callbackExecutor = Executor { it.run() }, onProgress = {},
            onSelectionReady = { selections.incrementAndGet(); it.reject() }, onFinished = { result.complete(it) },
            availableProcessors = { processors.incrementAndGet(); 8 }, budgetMs = 90_000,
            fixtureSource = source)
        runner.whenDrained { drained.countDown() }
        try {
            assertTrue(runner.start())
            val outcome = result.get(45, TimeUnit.SECONDS)
            assertTrue(drained.await(5, TimeUnit.SECONDS))
            assertEquals(RuntimeSelectionStatus.INCONCLUSIVE, outcome.status)
            assertEquals("baseline_fixture_role_mismatch", outcome.reason)
            assertNull(outcome.queueSnapshot)
            assertNull(outcome.selectedProfile)
            assertFalse(outcome.adopted)
            assertTrue(outcome.pending)
            assertEquals(0, processors.get())
            assertEquals(0, environments.get())
            assertEquals(0, selections.get())
        } finally {
            runner.cancel("test_cleanup")
            check(drained.await(60, TimeUnit.SECONDS)) { "Baseline owner must survive native drain" }
            try { owner.submit { baseline.close() }.get(30, TimeUnit.SECONDS) }
            finally { owner.shutdown(); check(owner.awaitTermination(5, TimeUnit.SECONDS)) }
        }
    }
}

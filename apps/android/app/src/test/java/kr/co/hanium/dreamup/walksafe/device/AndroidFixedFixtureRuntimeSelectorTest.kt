package kr.co.hanium.dreamup.walksafe.device

import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.inference.*
import org.junit.Assert.*
import org.junit.Test

class AndroidFixedFixtureRuntimeSelectorTest {
    private val binding = "a".repeat(64)
    private val box = RectNorm(.1f, .1f, .3f, .3f)
    private val model = ModelRuntimeConfig(
        TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, "models/test.tflite", 32,
        listOf("person"), setOf("person"), mapOf("default" to .5f),
        artifactSha256 = "b".repeat(64), outputFormat = YoloOutputFormat.RAW_XYWH_PIXELS,
        runtime = ModelRuntimeOptions(delegate = "gpu", gpuPrecisionLossAllowed = true),
    )
    private val config = TwoModelRuntimeConfig("test", "test", TwoModelRuntimeConfig.UNIFIED_MODEL_KEY,
        null, model, null, null)

    @Test fun cacheOnlyMissWarmsCpuOnceWithoutWritingOrConsumingLaterComparison() {
        val io = FakeIo()
        val selector = selector(io)
        val first = load(selector, io, false)
        assertSame(io.detectors.single().result, first)
        assertEquals(listOf("cpu"), io.detectors.map { it.options.delegate })
        assertEquals(listOf(0), io.detectors.single().fixtures)
        assertEquals(0, io.writes)
        assertTrue(io.messages.first().contains("기본"))
        io.detectors.single().close()

        val selected = load(selector, io, true)
        assertSame(io.detectors.last().result, selected)
        assertEquals(listOf("cpu", "cpu", "gpu"), io.detectors.map { it.options.delegate })
        assertEquals(1, io.writes)
        assertEquals("gpu", stored(io)?.backend)
    }

    @Test fun comparesAllFourFixturesWithEqualTimedCountsAndExcludesWarmups() {
        val io = FakeIo()
        val result = load(selector(io), io, true)
        val cpu = io.detectors[0]
        val gpu = io.detectors[1]
        val expected = listOf(0) + (0..3).toList() + (0..3).toList() + (0..3).toList()
        assertEquals(expected, cpu.fixtures)
        assertEquals(expected, gpu.fixtures)
        assertEquals(10.0, stored(io)!!.cpuMs, 0.0)
        assertEquals(5.0, stored(io)!!.gpuMs, 0.0)
        assertSame(gpu.result, result)
        assertEquals(1, cpu.closed)
        assertEquals(0, gpu.closed)
        assertEquals(2, io.maximumOwned)
        assertTrue(io.detectors.all { it.options.numThreads == 4 && !it.options.gpuPrecisionLossAllowed })
        assertEquals(listOf("comparison", "persisted", "measured", "gpu"), io.logs.single().take(4))
    }

    @Test fun diskChoiceSurvivesNewSelectorAndBothCallModesOnlyWarmSelectedRuntime() {
        val original = FakeIo()
        load(selector(original), original, true)
        listOf(false, true).forEach { allow ->
            val restored = FakeIo().apply { record = original.record }
            load(selector(restored), restored, allow)
            assertEquals("gpu", restored.detectors.single().options.delegate)
            assertEquals(1, restored.detectors.single().fixtures.size)
            assertEquals(0, restored.writes)
            assertEquals(listOf("cache", "persisted", "skipped", "gpu"), restored.logs.single().take(4))
        }
    }

    @Test fun equalOrSlowerGpuRetainsCpuAndClosesGpu() {
        listOf(10L, 20L).forEach { gpuMs ->
            val io = FakeIo().apply { this.gpuMs = gpuMs }
            val result = load(selector(io), io, true)
            assertSame(io.detectors.first().result, result)
            assertEquals("cpu", stored(io)?.backend)
            assertEquals(1, io.detectors.last().closed)
        }
    }

    @Test fun wrongGpuOutputCannotWinEvenWithFasterTiming() {
        val io = FakeIo().apply {
            transform = { detector, output ->
                if (detector.options.delegate == "gpu") output.copy(rawOutput = output.rawOutput!!.copyOf().apply {
                    this[0] += 3f
                }) else output
            }
        }
        assertSame(ioResultCpu(io), io.detectors.first().result)
        assertEquals("gpu_rejected", stored(io)?.comparison)
        assertEquals("cpu", stored(io)?.backend)
        assertEquals(1, io.detectors.last().closed)
    }

    @Test fun unavailableGpuIsRejectedAndItsHandleClosed() {
        val io = FakeIo().apply { unavailableBackend = "gpu" }
        assertSame(ioResultCpu(io), io.detectors.first().result)
        assertEquals("cpu", stored(io)?.backend)
        assertTrue(io.detectors.last().fixtures.isEmpty())
        assertEquals(1, io.detectors.last().closed)
    }

    @Test fun actualCpuFallbackDuringWarmupOrMeasurementNeverRecordsGpu() {
        listOf(1, 6, 13).forEach { failingCall ->
            val io = FakeIo().apply {
                transform = { detector, output ->
                    if (detector.options.delegate == "gpu" && detector.fixtures.size >= failingCall) output.copy(
                        result = output.result.copy(timing = output.result.timing.copy(modelRuntime =
                            output.result.timing.modelRuntime!!.copy(activeDelegate = "cpu", fallbackUsed = true))))
                    else output
                }
            }
            load(selector(io), io, true)
            assertEquals("cpu", stored(io)?.backend)
            assertEquals("gpu_rejected", stored(io)?.comparison)
        }
    }

    @Test fun nonFiniteRawOrParsedGpuOutputFailsClosed() {
        listOf<(AndroidCalibrationDetectionResult) -> AndroidCalibrationDetectionResult>(
            { it.copy(rawOutput = it.rawOutput!!.copyOf().apply { this[0] = Float.NaN }) },
            { it.copy(rawOutput = it.rawOutput!!.copyOf().apply { this[0] = Float.POSITIVE_INFINITY }) },
            { it.copy(rawOutput = null) },
            { it.copy(result = it.result.copy(detections = listOf(DetectionCandidate("person", Float.NaN, box)))) },
        ).forEach { corrupt ->
            val io = FakeIo().apply { transform = { d, o -> if (d.options.delegate == "gpu") corrupt(o) else o } }
            load(selector(io), io, true)
            assertEquals("cpu", stored(io)?.backend)
            assertEquals(1, io.detectors.last().closed)
        }
    }

    @Test fun baselineFailureDuringTimedCallsInvalidatesInsteadOfSavingBadCpu() {
        val io = FakeIo().apply {
            beforeInfer = { detector ->
                if (detector.options.delegate == "cpu" && detector.fixtures.size == 6) error("cpu inference failed")
            }
        }
        expectFailure<IllegalStateException> { load(selector(io), io, true) }
        assertEquals(FixedFixtureChoice.invalid(binding), io.record)
        assertTrue(io.detectors.all { it.closed == 1 })
    }

    @Test fun missingPositiveGroundTruthMeasuresBothRuntimesWithoutAuthorizingGpu() {
        val io = FakeIo().apply { transform = { _, output -> output.copy(result = output.result.copy(detections = emptyList())) } }
        val result = load(selector(io), io, true)
        assertEquals(listOf("cpu", "gpu"), io.detectors.map { it.options.delegate })
        assertEquals(io.detectors.first().result.copy(reason = AndroidFixedFixtureRuntimeSelector.BASELINE_REJECTED_REASON), result)
        val expected = listOf(0) + (0..3).toList() + (0..3).toList() + (0..3).toList()
        assertTrue(io.detectors.all { it.fixtures == expected })
        assertEquals(FixedFixtureChoice(binding, "cpu", 4, "baseline_rejected", 10.0, 5.0), stored(io))
        assertEquals(listOf("comparison", "persisted", "baseline_rejected", "cpu"), io.logs.single().take(4))
        assertEquals(0, io.detectors.first().closed)
        assertEquals(1, io.detectors.last().closed)
        assertTrue(result.detectorAvailable)
        assertTrue(io.messages.last().contains("정확도 기준을 충족하지 못해"))
    }

    @Test fun wrongClassOrNonemptyNegativeFixtureCannotAuthorizeGpu() {
        listOf(false, true).forEach { falsePositive ->
            val io = FakeIo().apply { transform = { detector, output ->
                val fixture = detector.fixtures.last()
                val detections = when {
                    falsePositive && fixture == 1 -> listOf(DetectionCandidate("person", .9f, box))
                    !falsePositive -> output.result.detections.map { it.copy(className = "moving_e_scooter") }
                    else -> output.result.detections
                }
                output.copy(result = output.result.copy(detections = detections))
            } }
            load(selector(io), io, true)
            assertEquals(listOf("cpu", "gpu"), io.detectors.map { it.options.delegate })
            assertEquals("baseline_rejected", stored(io)?.comparison)
            assertTrue(io.detectors.all { it.fixtures.size == 13 })
            assertEquals("cpu", stored(io)?.backend)
        }
    }

    @Test fun rejectedBaselineSurvivesRestartAndCameraWithoutRepeatingComparison() {
        val original = FakeIo().apply { transform = { _, output -> output.copy(result = output.result.copy(detections = emptyList())) } }
        load(selector(original), original, true)
        listOf(false, true).forEach { allow ->
            val restored = FakeIo().apply { record = original.record }
            val result = load(selector(restored), restored, allow)
            assertEquals("cpu", restored.detectors.single().options.delegate)
            assertEquals(listOf(0), restored.detectors.single().fixtures)
            assertEquals(0, restored.writes)
            assertEquals("baseline_rejected", stored(restored)?.comparison)
            assertEquals(listOf("cache", "persisted", "skipped", "cpu"), restored.logs.single().take(4))
            assertEquals(AndroidFixedFixtureRuntimeSelector.BASELINE_REJECTED_REASON, result.reason)
        }
    }

    @Test fun legacyUnmeasuredBaselineRefusalOnlyRetriesDuringAnExplicitDeviceCheck() {
        val legacy = FixedFixtureChoice(binding, "cpu", 4, "baseline_rejected", 0.0, 0.0).encode()
        val io = FakeIo().apply { record = legacy }
        val selector = selector(io)
        val warmed = load(selector, io, false)
        assertEquals(AndroidFixedFixtureRuntimeSelector.BASELINE_REJECTED_REASON, warmed.reason)
        assertEquals(legacy, io.record)
        assertEquals(0, io.writes)
        assertEquals(listOf(0), io.detectors.single().fixtures)
        io.detectors.single().close()

        val measured = load(selector, io, true)
        assertEquals("measured", stored(io)?.comparison)
        assertEquals("gpu", stored(io)?.backend)
        assertEquals(listOf("cpu", "cpu", "gpu"), io.detectors.map { it.options.delegate })
        assertEquals(1, io.writes)
        io.detectors.last().close()
        assertSame(io.detectors.last().result, measured)

        load(selector, io, true)
        assertEquals(1, io.writes)
        assertEquals(listOf(0), io.detectors.last().fixtures)
        assertEquals("gpu", io.detectors.last().options.delegate)
    }

    @Test fun rejectedBaselineRecordsCpuMeasurementOnlyAfterGpuWasActuallyAttempted() {
        val io = FakeIo().apply {
            unavailableBackend = "gpu"
            transform = { _, output -> output.copy(result = output.result.copy(detections = emptyList())) }
        }
        val result = load(selector(io), io, true)
        assertEquals(listOf("cpu", "gpu"), io.detectors.map { it.options.delegate })
        assertEquals(13, io.detectors.first().fixtures.size)
        assertTrue(io.detectors.last().fixtures.isEmpty())
        assertEquals(1, io.detectors.last().closed)
        assertEquals(FixedFixtureChoice(binding, "cpu", 4, "baseline_rejected", 10.0, 0.0), stored(io))
        assertEquals(AndroidFixedFixtureRuntimeSelector.BASELINE_REJECTED_REASON, result.reason)

        val restored = FakeIo().apply { record = io.record }
        load(selector(restored), restored, true)
        assertEquals(listOf("cpu"), restored.detectors.map { it.options.delegate })
        assertEquals(listOf(0), restored.detectors.single().fixtures)
        assertEquals(0, restored.writes)
    }

    @Test fun invalidNativeOutputAfterRoleMismatchStillFailsAndInvalidates() {
        val io = FakeIo().apply { transform = { detector, output ->
            if (detector.fixtures.last() == 3) output.copy(rawOutput = null)
            else output.copy(result = output.result.copy(detections = emptyList()))
        } }
        expectFailure<IllegalStateException> { load(selector(io), io, true) }
        assertEquals(FixedFixtureChoice.invalid(binding), io.record)
        assertEquals(1, io.detectors.single().closed)
    }

    @Test fun allFourRequiredFixturesMustBePresentBeforeAllocation() {
        val io = FakeIo().apply { omitLastFixture = true }
        expectFailure<IllegalStateException> { load(selector(io), io, true) }
        assertTrue(io.detectors.isEmpty())
        assertNull(stored(io))
    }

    @Test fun invalidRecordAllowsRetryAndBindingChangeAllowsFreshComparison() {
        val io = FakeIo().apply { record = FixedFixtureChoice.invalid(binding) }
        val selector = selector(io)
        load(selector, io, true)
        assertEquals(1, io.detectors.count { it.options.delegate == "gpu" })
        load(selector, io, true, newBinding = "c".repeat(64))
        assertEquals(2, io.detectors.count { it.options.delegate == "gpu" })
        assertEquals(2, io.writes)
    }

    @Test fun failedCommitKeepsProcessChoiceWithoutRemeasurementOrPersistedLabel() {
        val io = FakeIo().apply { saveSucceeds = false }
        val selector = selector(io)
        load(selector, io, true)
        val firstRecord = io.record // Simulates SharedPreferences updating memory despite commit(false).
        load(selector, io, true)
        load(selector, io, false)
        assertEquals(1, io.writes)
        assertEquals(firstRecord, io.record)
        assertEquals(listOf(13, 13, 1, 1), io.detectors.map { it.fixtures.size })
        assertTrue(io.logs.all { it[1] == "memory_only" })
        assertTrue(io.messages.any { it.contains("저장하지 못해") })
    }

    @Test fun thrownDiskWriteAlsoKeepsMemoryChoice() {
        val io = FakeIo().apply { writeThrows = true }
        val selector = selector(io)
        load(selector, io, true)
        load(selector, io, true)
        assertEquals(1, io.writes)
        assertEquals(1, io.detectors.last().fixtures.size)
        assertEquals("memory_only", io.logs.last()[1])
    }

    @Test fun failedCachedGpuWarmupUsesCpuWithoutComparisonAndNextCheckCanRetry() {
        val io = FakeIo().apply {
            record = choice().encode()
            transform = { detector, output ->
                if (detector.options.delegate == "gpu") output.copy(rawOutput = null) else output
            }
        }
        val selector = selector(io)
        val result = load(selector, io, true)
        assertSame(io.detectors.last().result, result)
        assertEquals(listOf("gpu", "cpu"), io.detectors.map { it.options.delegate })
        assertEquals(listOf(1, 1), io.detectors.map { it.fixtures.size })
        assertEquals(1, io.detectors.first().closed)
        assertEquals(FixedFixtureChoice.invalid(binding), io.record)
        io.transform = { _, output -> output }
        load(selector, io, true)
        assertEquals("gpu", stored(io)?.backend)
        assertEquals(13, io.detectors.last().fixtures.size)
    }

    @Test fun cancellationBeforeAndAfterNativeCreationReleasesExactlyOwnedDetectors() {
        val never = FakeIo()
        expectFailure<CancellationException> { load(selector(never), never, true, current = { false }) }
        assertTrue(never.detectors.isEmpty())
        val current = AtomicBoolean(true)
        val io = FakeIo().apply { afterCreate = { current.set(false) } }
        expectFailure<CancellationException> { load(selector(io), io, true, current = current::get) }
        assertEquals(1, io.detectors.single().closed)
        assertEquals(0, io.writes)
    }

    @Test fun cancellationDuringGpuMeasurementClosesBothAndDoesNotSave() {
        val current = AtomicBoolean(true)
        val io = FakeIo().apply { beforeInfer = { d ->
            if (d.options.delegate == "gpu" && d.fixtures.size == 7) current.set(false)
        } }
        expectFailure<CancellationException> { load(selector(io), io, true, current = current::get) }
        assertTrue(io.detectors.all { it.closed == 1 })
        assertEquals(0, io.writes)
    }

    @Test fun finalProgressCancellationClosesSelectedDetectorAfterLoserWasClosed() {
        val current = AtomicBoolean(true)
        val io = FakeIo()
        expectFailure<CancellationException> {
            load(selector(io), io, true, current = current::get, progress = {
                if (it == "인식 방식 준비 완료") current.set(false)
            })
        }
        assertTrue(io.detectors.all { it.closed == 1 })
        assertEquals("gpu", stored(io)?.backend)
    }

    @Test fun finalProgressOrLogExceptionDoesNotLeakWinnerOrEraseValidChoice() {
        listOf(false, true).forEach { logThrows ->
            val io = FakeIo().apply { this.logThrows = logThrows }
            expectFailure<IllegalStateException> {
                load(selector(io), io, true, progress = {
                    if (!logThrows && it == "인식 방식 준비 완료") error("progress failed")
                })
            }
            assertTrue(io.detectors.all { it.closed == 1 })
            assertEquals("gpu", stored(io)?.backend)
        }
    }

    @Test fun cachedGpuCompletionProgressFailurePreservesCacheForCameraAndNextDeviceCheck() {
        assertCachedGpuObserverFailurePreservesChoice(logThrows = false)
    }

    @Test fun cachedGpuLogFailurePreservesCacheForCameraAndNextDeviceCheck() {
        assertCachedGpuObserverFailurePreservesChoice(logThrows = true)
    }

    private fun assertCachedGpuObserverFailurePreservesChoice(logThrows: Boolean) {
        listOf(false, true).forEach { allowComparison ->
            val io = FakeIo().apply { record = choice().encode(); this.logThrows = logThrows }
            val originalRecord = io.record
            val sameProcessSelector = selector(io)
            expectFailure<IllegalStateException> {
                load(sameProcessSelector, io, allowComparison, progress = {
                    if (!logThrows && it == "인식 방식 준비 완료") error("completion progress failed")
                })
            }
            assertEquals(originalRecord, io.record)
            assertEquals(0, io.writes)
            assertEquals(1, io.detectors.single().closed)
            assertEquals(listOf(0), io.detectors.single().fixtures)

            io.logThrows = false
            // Same-process camera and a new process's device check must both retain the GPU cache.
            load(sameProcessSelector, io, false)
            load(selector(io), io, true)
            assertEquals(originalRecord, io.record)
            assertEquals(0, io.writes)
            assertEquals(listOf("gpu", "gpu", "gpu"), io.detectors.map { it.options.delegate })
            assertEquals(listOf(1, 1, 1), io.detectors.map { it.fixtures.size })
            assertEquals(listOf(1, 0, 0), io.detectors.map { it.closed })
            assertTrue(io.logs.all { it.take(4) == listOf("cache", "persisted", "skipped", "gpu") })
        }
    }

    @Test fun closeFailureStillClosesOtherDetectorAndCannotPublishASelection() {
        val io = FakeIo().apply { closeThrowsBackend = "cpu" }
        expectFailure<IllegalStateException> { load(selector(io), io, true) }
        assertTrue(io.detectors.all { it.closed == 1 })
        assertNull(stored(io))
        assertTrue(io.logs.isEmpty())
    }

    @Test fun uiCallIsRejectedBeforeDiskFixtureOrNativeWork() {
        val io = FakeIo()
        val selector = FixedFixtureRuntimeSelection({ io.clock }, { true })
        expectFailure<IllegalStateException> { load(selector, io, true) }
        assertEquals(0, io.reads)
        assertEquals(0, io.manifestReads)
        assertTrue(io.detectors.isEmpty())
    }

    @Test fun concurrentChecksShareOneComparisonAndWaiterUsesFreshCache() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val io = FakeIo().apply { beforeInfer = { detector ->
            if (detector.options.delegate == "cpu" && detector.fixtures.size == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        } }
        val selector = selector(io)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<AndroidDetectorLoadResult> { load(selector, io, true) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = executor.submit<AndroidDetectorLoadResult> { load(selector, io, true) }
            release.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            assertEquals(1, io.writes)
            assertEquals(listOf(13, 13, 1), io.detectors.map { it.fixtures.size })
            assertEquals(listOf("comparison", "cache"), io.logs.map { it[0] })
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun cancelledWaiterDoesNotAllocateWhileAnotherWorkerIsComparing() {
        val entered = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val io = FakeIo().apply { beforeInfer = { detector ->
            if (detector.options.delegate == "cpu" && detector.fixtures.size == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        } }
        val selector = selector(io)
        val current = AtomicBoolean(true)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<AndroidDetectorLoadResult> { load(selector, io, true) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = executor.submit<Boolean> {
                expectFailure<CancellationException> { load(selector, io, true, current = {
                    waiting.countDown(); current.get()
                }) }
                true
            }
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            current.set(false)
            assertTrue(second.get(5, TimeUnit.SECONDS))
            release.countDown()
            first.get(5, TimeUnit.SECONDS)
            assertEquals(2, io.detectors.size)
            assertEquals(1, io.writes)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun bindingCoversConfigDeviceOsRuntimeAndFixtureButIgnoresMapOrder() {
        fun hash(c: TwoModelRuntimeConfig = config, device: List<String> = listOf("device", "os"),
            runtime: String = "runtime", fixture: String = "a".repeat(64), threads: Int = 4) =
            FixedFixtureBinding.create(c, device, threads, runtime, fixture)
        val initial = hash()
        assertNotEquals(initial, hash(device = listOf("other-device", "os")))
        assertNotEquals(initial, hash(device = listOf("device", "other-os")))
        assertNotEquals(initial, hash(runtime = "updated"))
        assertNotEquals(initial, hash(fixture = "c".repeat(64)))
        assertNotEquals(initial, hash(threads = 2))
        assertNotEquals(initial, hash(config.copy(bundleVersion = "updated")))
        assertNotEquals(initial, hash(config.copy(unifiedWalksafe = model.copy(artifactSha256 = "d".repeat(64)))))
        assertNotEquals(initial, hash(config.copy(unifiedWalksafe = model.copy(runtime = model.runtime.copy(numThreads = 2)))))
        assertNotEquals(initial, hash(config.copy(unifiedWalksafe = model.copy(thresholds = mapOf("default" to .6f)))))
        val first = config.copy(unifiedWalksafe = model.copy(thresholds = linkedMapOf("default" to .5f, "person" to .4f)))
        val reordered = config.copy(unifiedWalksafe = model.copy(thresholds = linkedMapOf("person" to .4f, "default" to .5f)))
        assertEquals(hash(first), hash(reordered))
    }

    @Test fun malformedOrNonFiniteCacheCannotClaimGpuWin() {
        val good = choice()
        assertEquals(good, FixedFixtureChoice.decode(good.encode(), binding, 4))
        listOf(0.0 to 0.0, 10.0 to 0.0, 10.0 to 5.0).forEach { (cpu, gpu) ->
            val rejected = FixedFixtureChoice(binding, "cpu", 4, "baseline_rejected", cpu, gpu)
            assertEquals(rejected, FixedFixtureChoice.decode(rejected.encode(), binding, 4))
        }
        listOf(good.copy(gpuMs = Double.NaN), good.copy(cpuMs = Double.POSITIVE_INFINITY),
            good.copy(backend = "nnapi"), good.copy(threads = 3), good.copy(binding = "b".repeat(64)),
            good.copy(gpuMs = 20.0), good.copy(comparison = "gpu_rejected"), good.copy(cpuMs = -1.0),
            good.copy(comparison = "baseline_rejected", cpuMs = 0.0, gpuMs = 0.0),
            good.copy(backend = "cpu", comparison = "baseline_rejected", cpuMs = 0.0),
        ).forEach { assertNull(FixedFixtureChoice.decode(it.encode(), binding, 4)) }
        assertNull(FixedFixtureChoice.decode(good.encode() + "|extra", binding, 4))
        assertNull(FixedFixtureChoice.decode(FixedFixtureChoice.invalid(binding), binding, 4))
    }

    private fun selector(io: FakeIo) = FixedFixtureRuntimeSelection({ io.clock }, { false })
    private fun stored(io: FakeIo) = FixedFixtureChoice.decode(io.record, binding, 4)
    private fun choice() = FixedFixtureChoice(binding, "gpu", 4, "measured", 10.0, 5.0)
    private fun ioResultCpu(io: FakeIo) = load(selector(io), io, true)
    private fun load(selector: FixedFixtureRuntimeSelection, io: FakeIo, compare: Boolean,
        current: () -> Boolean = { true }, progress: (String) -> Unit = {}, newBinding: String = binding): AndroidDetectorLoadResult =
        selector.load(config, newBinding, 4, compare, io, current) { io.messages += it; progress(it) }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit) {
        try { block() } catch (error: Throwable) {
            if (error is T) return
            throw AssertionError("Expected ${T::class.java.simpleName}, got ${error.javaClass.simpleName}", error)
        }
        fail("Expected ${T::class.java.simpleName}")
    }

    private inner class FakeIo : FixedFixtureRuntimeIo {
        var record: String? = null
        var reads = 0
        var writes = 0
        var manifestReads = 0
        var saveSucceeds = true
        var writeThrows = false
        var logThrows = false
        var omitLastFixture = false
        var clock = 0L
        var gpuMs = 5L
        var maximumOwned = 0
        var unavailableBackend: String? = null
        var closeThrowsBackend: String? = null
        var afterCreate: () -> Unit = {}
        var beforeInfer: (FakeDetector) -> Unit = {}
        var transform: (FakeDetector, AndroidCalibrationDetectionResult) -> AndroidCalibrationDetectionResult = { _, o -> o }
        val detectors = mutableListOf<FakeDetector>()
        val logs = mutableListOf<List<String>>()
        val messages = mutableListOf<String>()
        val fixtures = (0..3).map { index ->
            val roles = when (index) {
                1 -> setOf(RuntimeCalibrationFixtureRole.EMPTY)
                3 -> setOf(RuntimeCalibrationFixtureRole.POSITIVE, RuntimeCalibrationFixtureRole.NEAR_THRESHOLD)
                else -> setOf(RuntimeCalibrationFixtureRole.POSITIVE)
            }
            RuntimeCalibrationFixture(index.toString(), RuntimeCalibrationFixtureKind.SOLID_BLACK, null,
                32, 32, "b".repeat(64), true, roles, if (index == 1) emptyList() else listOf(
                    RuntimeCalibrationGroundTruth(index.toLong(), 0, "person", box, false)))
        }
        override fun read(): String? { reads++; return record }
        override fun write(value: String): Boolean {
            writes++
            if (writeThrows) error("disk failure")
            record = value
            return saveSucceeds
        }
        override fun manifest(): RuntimeCalibrationFixtureManifest {
            manifestReads++
            return RuntimeCalibrationFixtureManifest(RuntimeCalibrationFixtures.VERSION,
                RuntimeCalibrationFixtures.MANIFEST_SHA256, if (omitLastFixture) fixtures.dropLast(1) else fixtures,
                RuntimeCalibrationFixtureCoverage(setOf("0", "2", "3"), setOf("1"), setOf("3"), "test"))
        }
        override fun prepare(fixture: RuntimeCalibrationFixture) = PreprocessedImage(
            ByteBuffer.allocate(4).apply { putInt(0, fixture.id.toInt()) }, LetterboxTransform(32, 32, 32, 1f, 0f, 0f))
        override fun create(options: ModelRuntimeOptions): FixedFixtureDetector = FakeDetector(options, this).also {
            detectors += it
            maximumOwned = maxOf(maximumOwned, detectors.count { d -> d.closed == 0 })
            afterCreate()
        }
        override fun log(source: String, cache: String, comparison: String, backend: String, ms: Double) {
            if (logThrows) error("log failed")
            logs += listOf(source, cache, comparison, backend, ms.toString())
        }
    }

    private inner class FakeDetector(val options: ModelRuntimeOptions, val io: FakeIo) : FixedFixtureDetector {
        val fixtures = mutableListOf<Int>()
        var closed = 0
        override val result = AndroidDetectorLoadResult(null, true, options.delegate != io.unavailableBackend,
            TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, false, "fake-${options.delegate}-${io.detectors.size}")
        override fun infer(input: PreprocessedImage): AndroidCalibrationDetectionResult {
            check(closed == 0)
            val fixture = input.inputBuffer.getInt(0)
            fixtures += fixture
            io.beforeInfer(this)
            // Deliberately huge warmup cost must never enter the saved measurement.
            io.clock += (if (fixtures.size == 1) 100_000L else if (options.delegate == "cpu") 10L else io.gpuMs) * 1_000_000L
            val raw = FloatArray(model.outputTensorShape().fold(1) { a, b -> a * b })
            if (fixture != 1) {
                val anchors = model.outputTensorShape()[2]
                raw[0] = 8f; raw[anchors] = 8f; raw[anchors * 2] = 10f; raw[anchors * 3] = 10f
                raw[anchors * 4] = if (fixture == 3) .51f else .9f
            }
            val detections = if (fixture == 1) emptyList() else listOf(DetectionCandidate("person", .9f, box))
            return io.transform(this, AndroidCalibrationDetectionResult(AndroidDetectionResult(detections,
                AndroidDetectorTiming(modelKey = TwoModelRuntimeConfig.UNIFIED_MODEL_KEY,
                    completedModels = listOf(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY),
                    modelRuntime = AndroidDetectorRuntime(options.delegate, options.delegate, options.numThreads))), raw))
        }
        override fun close() {
            closed++
            check(closed == 1) { "closed twice" }
            if (options.delegate == io.closeThrowsBackend) error("native close failed")
        }
    }
}

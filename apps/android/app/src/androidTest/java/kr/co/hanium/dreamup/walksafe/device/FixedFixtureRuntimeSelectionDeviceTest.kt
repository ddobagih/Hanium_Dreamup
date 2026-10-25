package kr.co.hanium.dreamup.walksafe.device

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.co.hanium.dreamup.walksafe.inference.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual bundled RGB/native runtimes and original accuracy gates; no camera or live accuracy claim.
 * read/write acknowledge DISPOSABLE_MEMORY_STORAGE, not SharedPreferences or a process restart.
 * Native GPU serialization caches keep production options. No account/device-check/choice prefs are used.
 */
@RunWith(AndroidJUnit4::class)
class FixedFixtureRuntimeSelectionDeviceTest {
    @Test(timeout = 300_000L)
    fun nativeComparisonCacheWarmupAndDifferentDeviceBindingRespectAccuracyGate() {
        assumeTrue("Explicit native comparison opt-in required", InstrumentationRegistry.getArguments()
            .getString("allowFixedFixtureRuntimeSelectionTest") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = TwoModelRuntimeConfig.load(context)
        val threads = RuntimeSelectionPolicy.initialCpuCandidate(Runtime.getRuntime().availableProcessors()).numThreads
        val actualIdentity = listOf(Build.FINGERPRINT, Build.MANUFACTURER, Build.MODEL, Build.HARDWARE,
            Build.VERSION.SDK_INT.toString(), Build.VERSION.RELEASE, Build.VERSION.INCREMENTAL,
            android.os.Process.is64Bit().toString(), System.getProperty("os.arch").orEmpty()) + Build.SUPPORTED_ABIS
        val actualBinding = FixedFixtureBinding.create(config, actualIdentity, threads)
        val otherIdentity = actualIdentity.toMutableList().apply { this[2] = "${this[2]}#CONTROLLED_OTHER_DEVICE" }
        val otherBinding = FixedFixtureBinding.create(config, otherIdentity, threads)
        assertNotEquals("A changed device identity must produce a distinct binding", actualBinding, otherBinding)
        val storage = DisposableStorage()
        emit("SCOPE", "storage=DISPOSABLE_MEMORY_STORAGE io_ack_is_not_disk_persistence=true process_restart_proof=false actual_build_binding=$actualBinding other_device_binding=$otherBinding other_device_identity=SIMULATED physical_device_identity=UNCHANGED accuracy_thresholds=UNCHANGED")
        val first = select(context, config, threads, FixedFixtureRuntimeSelection(), storage, actualBinding,
            "ACTUAL_DEVICE_COMPARISON", compare = true)
        val storedActual = storage.value
        val restoredSelector = FixedFixtureRuntimeSelection()
        val warm = select(context, config, threads, restoredSelector, storage, actualBinding,
            "SAME_DEVICE_CONTROLLED_RECORD_READ", compare = false)
        assertEquals("Same-device native warmup must preserve the choice", first, warm)
        assertEquals("Cache warmup must not rewrite the controlled record", storedActual, storage.value)
        val other = select(context, config, threads, restoredSelector, storage, otherBinding,
            "SIMULATED_OTHER_DEVICE_BINDING_COMPARISON", compare = true)
        assertEquals(otherBinding, other.binding)
        assertNotEquals(first.binding, other.binding)
        emit("BOUNDARY_OBSERVED", "real_native_comparison_attempts=2 controlled_cache_warmups=1 account_prefs_used=false device_identity_written=false actual_other_phone_test=false first_accuracy=${first.comparison} other_accuracy=${other.comparison}")
    }

    private fun select(context: Context, config: TwoModelRuntimeConfig, threads: Int,
        selector: FixedFixtureRuntimeSelection, storage: DisposableStorage, binding: String,
        stage: String, compare: Boolean): FixedFixtureChoice {
        val io = NativeIo(context, config, storage, stage)
        var failure: Throwable? = null
        try {
            val load = selector.load(config, binding, threads, compare, io,
                { !Thread.currentThread().isInterrupted }, { emit("PROGRESS", "stage=$stage message=$it") })
            val choice = requireNotNull(FixedFixtureChoice.decode(storage.value, binding, threads)) { "No valid controlled choice: stage=$stage" }
            assertTrue("Selected native detector must exist", load.detectorAvailable && load.detector != null)
            assertFalse("Selected load must not hide a fallback", load.fallbackUsed)
            assertEquals(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, load.modelKey)
            val log = io.logs.single()
            assertEquals(choice.backend, log.backend)
            assertEquals("persisted", log.cache) // Only an acknowledgement by our disposable IO.
            io.calls.filter { it.backend == "cpu" || runtimeMatches(it, threads) }.forEach { call ->
                val runtime = requireNotNull(call.result.timing.modelRuntime)
                assertTrue(call.result.inferenceCompleted && !call.result.partial)
                assertEquals(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, call.result.timing.modelKey)
                assertEquals(listOf(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY), call.result.timing.completedModels)
                assertEquals(call.backend, runtime.requestedDelegate)
                assertEquals(call.backend, runtime.activeDelegate)
                assertEquals(threads, runtime.numThreads)
                assertFalse(runtime.fallbackUsed)
                assertTrue("Native call must have positive real elapsed time", call.endNs > call.startNs)
            }
            if (compare) {
                assertEquals("comparison", log.source)
                assertEquals(choice.comparison, log.comparison)
                assertEquals(1, io.writes)
                assertEquals("Both native backend factories must be attempted", listOf("cpu", "gpu"), io.detectors.map { it.backend })
                val required = io.manifest().fixtures.filter { it.required }
                val cpu = io.calls.filter { it.backend == "cpu" }
                val gpu = io.calls.filter { it.backend == "gpu" }
                assertTrue("CPU must execute all four real fixtures", cpu.map { it.fixture.id }.toSet().containsAll(required.map { it.id }))
                // A warmup is not a fixture-role oracle or a timed comparison sample.
                val cpuRolesPass = cpu.drop(1).all { matchesRoles(it) }
                val gpuRawValid = required.all { fixture ->
                    val output = io.lastOutputs["gpu" to fixture.id]
                    output != null && RuntimeCalibrationOutputGate.compare(output.rawOutput, output.rawOutput,
                        output.result.detections, output.result.detections, requireNotNull(config.unifiedWalksafe))
                }
                val pairedCompleted = cpu.size == 13 && gpu.size == 13 && gpu.all { runtimeMatches(it, threads) } && gpuRawValid
                val equivalent = required.all { fixture ->
                    val a = io.lastOutputs["cpu" to fixture.id]
                    val b = io.lastOutputs["gpu" to fixture.id]
                    a != null && b != null && RuntimeCalibrationOutputGate.compare(a.rawOutput, b.rawOutput,
                        a.result.detections, b.result.detections, requireNotNull(config.unifiedWalksafe))
                }
                emit("GATE_RESULT", "stage=$stage cpu_calls=${cpu.size} gpu_calls=${gpu.size} cpu_fixture_roles_pass=$cpuRolesPass paired_native_completed=$pairedCompleted last_fixture_output_equivalent=$equivalent choice=$choice selected_reason=${load.reason} actual_gpu_factory_result=${io.detectors.last().result.reason} field_accuracy_proof=false")
                when (choice.comparison) {
                    "measured" -> {
                        assertTrue("Measured choice requires real CPU/GPU calls and the original accuracy/equivalence gates", pairedCompleted && cpuRolesPass && equivalent && gpu.drop(1).all { matchesRoles(it) })
                        assertTrue(choice.cpuMs > 0.0 && choice.gpuMs > 0.0)
                        assertEquals(if (choice.gpuMs < choice.cpuMs) "gpu" else "cpu", choice.backend)
                    }
                    "baseline_rejected" -> {
                        assertFalse("Accuracy refusal must correspond to actual fixture-role failure", cpuRolesPass)
                        assertEquals("cpu", choice.backend)
                        assertEquals(AndroidFixedFixtureRuntimeSelector.BASELINE_REJECTED_REASON, load.reason)
                        assertTrue("New refusal must retain an actual CPU measurement", choice.cpuMs > 0.0)
                        if (choice.gpuMs > 0.0) assertTrue("A GPU duration needs completed paired native calls", pairedCompleted)
                        else assertFalse("No GPU duration may be invented", pairedCompleted)
                    }
                    "gpu_rejected" -> {
                        assertTrue("GPU refusal must retain a valid CPU baseline", cpuRolesPass)
                        assertEquals("cpu", choice.backend)
                        assertEquals(0.0, choice.cpuMs, 0.0)
                        assertEquals(0.0, choice.gpuMs, 0.0)
                        assertFalse("GPU refusal must have an observed execution/output/fixture failure",
                            pairedCompleted && equivalent && gpu.drop(1).all { matchesRoles(it) })
                    }
                    else -> fail("Unrecognized comparison result: ${choice.comparison}")
                }
            } else {
                assertEquals("cache", log.source)
                assertEquals("skipped", log.comparison)
                assertEquals(1, io.reads)
                assertEquals(0, io.writes)
                assertEquals("Cache use must only warm the selected real backend", listOf(choice.backend), io.detectors.map { it.backend })
                assertEquals("Cache use must not repeat fixture comparison", 1, io.calls.size)
                if (choice.comparison == "baseline_rejected")
                    assertEquals(AndroidFixedFixtureRuntimeSelector.BASELINE_REJECTED_REASON, load.reason)
            }
            return choice
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            try { io.close() } catch (close: Throwable) { if (failure != null) failure.addSuppressed(close) else throw close }
            finally { io.report(); io.lastOutputs.clear() }
        }
    }

    private class DisposableStorage { var value: String? = null }
    private data class SelectionLog(val source: String, val cache: String, val comparison: String, val backend: String)
    private data class NativeCall(val backend: String, val fixture: RuntimeCalibrationFixture,
        val startNs: Long, val endNs: Long, val result: AndroidDetectionResult, val rawCount: Int?)

    private class NativeIo(private val context: Context, private val config: TwoModelRuntimeConfig,
        private val storage: DisposableStorage, private val stage: String) : FixedFixtureRuntimeIo, AutoCloseable {
        private val fixtures = RuntimeCalibrationFixtures(context)
        private val preprocessor = YuvImagePreprocessor()
        private var prepared: RuntimeCalibrationFixture? = null
        val detectors = mutableListOf<OwnedDetector>()
        val calls = mutableListOf<NativeCall>()
        val logs = mutableListOf<SelectionLog>()
        private val nativeErrors = mutableListOf<String>()
        val lastOutputs = mutableMapOf<Pair<String, String>, AndroidCalibrationDetectionResult>()
        var reads = 0; private set
        var writes = 0; private set
        override fun read(): String? { reads++; return storage.value }
        override fun write(value: String): Boolean { writes++; storage.value = value; return true }
        override fun manifest() = fixtures.loadManifest()
        override fun prepare(fixture: RuntimeCalibrationFixture): PreprocessedImage {
            prepared = fixture
            return preprocessor.preprocessBilinear(fixtures.loadArgb(fixture), requireNotNull(config.unifiedWalksafe).inputSize)
        }
        override fun create(options: ModelRuntimeOptions): FixedFixtureDetector {
            val load = TfliteAndroidFrameDetector.createWithStatus(context,
                config.copy(fallbackModelKey = null, customTactile = null, cocoGeneral = null), options,
                YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR)
            return OwnedDetector(options.delegate, load).also { detectors += it }
        }
        inner class OwnedDetector(val backend: String, override val result: AndroidDetectorLoadResult) : FixedFixtureDetector {
            private var closed = false
            override fun infer(input: PreprocessedImage): AndroidCalibrationDetectionResult {
                val fixture = requireNotNull(prepared)
                val start = SystemClock.elapsedRealtimeNanos()
                val output = try { requireNotNull(result.detector).inferPreparedUnifiedForCalibration(input, 0L,
                    YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR)
                } catch (error: Throwable) {
                    nativeErrors += "backend=$backend fixture=${fixture.id} start_ns=$start end_ns=${SystemClock.elapsedRealtimeNanos()} failure=$error"
                    throw error
                }
                val end = SystemClock.elapsedRealtimeNanos()
                // No diagnostic IPC/accuracy scan in the selector's timed native call.
                calls += NativeCall(backend, fixture, start, end, output.result, output.rawOutput?.size)
                lastOutputs[backend to fixture.id] = output
                return output
            }
            override fun close() { if (!closed) { closed = true; result.detector?.close() } }
        }
        override fun log(source: String, cache: String, comparison: String, backend: String, ms: Double) {
            logs += SelectionLog(source, cache, comparison, backend)
            emit("SELECTED", "stage=$stage source=$source cache=$cache cache_scope=DISPOSABLE_MEMORY_STORAGE comparison=$comparison backend=$backend selected_ms=$ms")
        }
        override fun close() {
            var failure: Throwable? = null
            detectors.forEach { owned ->
                try { owned.close() } catch (error: Throwable) { if (failure == null) failure = error else failure?.addSuppressed(error) }
            }
            detectors.forEach { owned ->
                owned.result.detector?.let { native ->
                    val phases = native.lifecycleTiming().phases.filter { it.phase == AndroidDetectorLoadPhase.NATIVE_CLOSE }
                    emit("NATIVE_RELEASE", "stage=$stage backend=${owned.backend} close_phases=$phases")
                    if (phases.none { it.outcome == AndroidDetectorPhaseOutcome.SUCCEEDED } || phases.any { it.outcome == AndroidDetectorPhaseOutcome.FAILED }) {
                        val error = AssertionError("Native close is not confirmed: stage=$stage backend=${owned.backend}")
                        if (failure == null) failure = error else failure?.addSuppressed(error)
                    }
                }
            }
            failure?.let { throw it }
        }
        fun report() {
            calls.forEachIndexed { index, call -> emit("NATIVE_CALL", "stage=$stage index=$index requested=${call.backend} fixture=${call.fixture.id} fixture_sha=${call.fixture.sha256} start_ns=${call.startNs} end_ns=${call.endNs} native_elapsed_ns=${call.endNs - call.startNs} runtime=${call.result.timing.modelRuntime} model_inference_ms=${call.result.timing.modelInferenceMs} raw_elements=${call.rawCount} original_fixture_roles_pass=${matchesRoles(call)} detections=${call.result.detections}") }
            detectors.forEach { emit("FACTORY_RESULT", "stage=$stage requested=${it.backend} available=${it.result.detectorAvailable} fallback=${it.result.fallbackUsed} reason=${it.result.reason} failure=${it.result.failureCode}") }
            nativeErrors.forEach { emit("NATIVE_FAILURE", "stage=$stage $it") }
        }
    }

    companion object {
        private fun runtimeMatches(call: NativeCall, threads: Int): Boolean {
            val runtime = call.result.timing.modelRuntime ?: return false
            return call.result.inferenceCompleted && !call.result.partial &&
                call.result.timing.modelKey == TwoModelRuntimeConfig.UNIFIED_MODEL_KEY &&
                call.result.timing.completedModels == listOf(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY) &&
                runtime.requestedDelegate == call.backend && runtime.activeDelegate == call.backend &&
                runtime.numThreads == threads && !runtime.fallbackUsed
        }
        private fun matchesRoles(call: NativeCall) = RuntimeCalibrationOutputGate.matchesFixtureRoles(
            call.result.detections, call.result.detections, RuntimeCalibrationFixtureRole.POSITIVE in call.fixture.roles,
            RuntimeCalibrationFixtureRole.EMPTY in call.fixture.roles,
            call.fixture.groundTruth.filter { !it.isCrowd }.map { it.className to it.bboxNorm })
        private fun emit(event: String, details: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "FIXED_FIXTURE_SELECTION event=$event at_ms=${SystemClock.elapsedRealtime()} $details\n")
        })
    }
}

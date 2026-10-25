package kr.co.hanium.dreamup.walksafe.device

import android.content.Context
import android.os.Build
import android.os.Looper
import android.util.Log
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kr.co.hanium.dreamup.walksafe.inference.*

/** Fixed-image preference only. This never issues a live RuntimeTuningProfile or opens a camera. */
object AndroidFixedFixtureRuntimeSelector {
    const val BASELINE_REJECTED_REASON = "FixedFixtureBaselineRejected"
    private val selector = FixedFixtureRuntimeSelection()
    internal const val PREFERENCES_NAME = "walksafe_fixed_fixture_runtime_v1"

    /** Blocking worker API. The caller owns the returned detector, including its eventual close. */
    fun load(
        context: Context,
        config: TwoModelRuntimeConfig,
        allowComparison: Boolean,
        isCurrent: () -> Boolean = { true },
        onProgress: (String) -> Unit = {},
    ): AndroidDetectorLoadResult {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Fixed runtime loading requires a worker" }
        val app = context.applicationContext
        val threads = RuntimeSelectionPolicy.initialCpuCandidate(Runtime.getRuntime().availableProcessors()).numThreads
        val binding = FixedFixtureBinding.create(config, listOf(
            Build.FINGERPRINT, Build.MANUFACTURER, Build.MODEL, Build.HARDWARE,
            Build.VERSION.SDK_INT.toString(), Build.VERSION.RELEASE, Build.VERSION.INCREMENTAL,
            android.os.Process.is64Bit().toString(), System.getProperty("os.arch").orEmpty(),
        ) + Build.SUPPORTED_ABIS, threads)
        val preferences = app.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val fixtures = RuntimeCalibrationFixtures(app)
        val preprocessor = YuvImagePreprocessor()
        return selector.load(config, binding, threads, allowComparison, object : FixedFixtureRuntimeIo {
            override fun read(): String? = preferences.getString("record", null)
            override fun write(value: String): Boolean = preferences.edit().putString("record", value).commit()
            override fun manifest() = fixtures.loadManifest()
            override fun prepare(fixture: RuntimeCalibrationFixture): PreprocessedImage =
                preprocessor.preprocessBilinear(fixtures.loadArgb(fixture), requireNotNull(config.unifiedWalksafe).inputSize)
            override fun create(options: ModelRuntimeOptions): FixedFixtureDetector {
                // The fixed contract requires the unified model, never a silently substituted legacy pair.
                val load = TfliteAndroidFrameDetector.createWithStatus(app,
                    config.copy(fallbackModelKey = null, customTactile = null, cocoGeneral = null), options,
                    YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR)
                return object : FixedFixtureDetector {
                    override val result = load
                    override fun infer(input: PreprocessedImage) = requireNotNull(load.detector)
                        .inferPreparedUnifiedForCalibration(input, 0L, YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR)
                    override fun close() { load.detector?.close() }
                }
            }
            override fun log(source: String, cache: String, comparison: String, backend: String, ms: Double) {
                Log.i("WalkSafeRuntimeChoice",
                    "source=$source cache=$cache comparison=$comparison backend=$backend ms=$ms")
            }
        }, isCurrent, onProgress)
    }
}

/** Native and disk boundaries are replaceable for JVM ownership/concurrency tests. */
internal interface FixedFixtureDetector : AutoCloseable {
    val result: AndroidDetectorLoadResult
    fun infer(input: PreprocessedImage): AndroidCalibrationDetectionResult
}

internal interface FixedFixtureRuntimeIo {
    fun read(): String?
    fun write(value: String): Boolean
    fun manifest(): RuntimeCalibrationFixtureManifest
    fun prepare(fixture: RuntimeCalibrationFixture): PreprocessedImage
    fun create(options: ModelRuntimeOptions): FixedFixtureDetector
    fun log(source: String, cache: String, comparison: String, backend: String, ms: Double)
}

/** No time/boot/session expiry. Explicit checks may finish a legacy, unmeasured baseline refusal. */
internal data class FixedFixtureChoice(
    val binding: String,
    val backend: String,
    val threads: Int,
    val comparison: String,
    val cpuMs: Double,
    val gpuMs: Double,
) {
    fun encode(): String = listOf(VERSION, binding, backend, threads, comparison, cpuMs, gpuMs).joinToString("|")

    companion object {
        const val VERSION = "fixed-fixture-choice-v1"
        fun invalid(binding: String): String = "$VERSION|$binding|INVALID"
        fun decode(value: String?, binding: String, threads: Int): FixedFixtureChoice? {
            val parts = value?.split('|') ?: return null
            if (parts.size != 7 || parts[0] != VERSION || parts[1] != binding ||
                !binding.matches(Regex("[a-f0-9]{64}")) || parts[2] !in setOf("cpu", "gpu") ||
                parts[3] != threads.toString() || parts[4] !in setOf("measured", "gpu_rejected", "baseline_rejected")) return null
            val cpu = parts[5].toDoubleOrNull() ?: return null
            val gpu = parts[6].toDoubleOrNull() ?: return null
            if (!cpu.isFinite() || !gpu.isFinite() || cpu < 0 || gpu < 0 ||
                (parts[4] == "measured" && (cpu <= 0 || gpu <= 0)) ||
                (parts[4] != "measured" && parts[2] != "cpu") ||
                (parts[4] == "gpu_rejected" && (cpu != 0.0 || gpu != 0.0)) ||
                // Legacy 0/0 was not a comparison. Positive CPU time records an actual new
                // comparison attempt; GPU zero means its execution could not be completed.
                (parts[4] == "baseline_rejected" && cpu == 0.0 && gpu != 0.0) ||
                (parts[2] == "gpu" && (parts[4] != "measured" || gpu >= cpu))) return null
            return FixedFixtureChoice(binding, parts[2], threads, parts[4], cpu, gpu)
        }
    }
}

internal object FixedFixtureBinding {
    fun create(
        config: TwoModelRuntimeConfig,
        deviceAndOs: List<String>,
        threads: Int,
        runtimeVersion: String = AndroidDeviceTuningProfileStore.RUNTIME_CONTRACT_VERSION,
        fixtureHash: String = RuntimeCalibrationFixtures.MANIFEST_SHA256,
    ): String {
        val fields = buildList {
            add(FixedFixtureChoice.VERSION)
            add(runtimeVersion)
            add(AndroidDeviceTuningProfileStore.INPUT_CONTRACT_VERSION)
            add("fixed-bilinear-argb;live=fused-yuv;warmup=1;rounds=2;output-gate=1")
            add(RuntimeCalibrationFixtures.VERSION); add(fixtureHash); add(threads.toString())
            add(deviceAndOs.size.toString()); addAll(deviceAndOs)
            add(config.bundleVersion); add(config.runtimeSourceModel.orEmpty())
            add(config.primaryModelKey); add(config.fallbackModelKey.orEmpty())
            listOf(config.unifiedWalksafe, config.customTactile, config.cocoGeneral).forEach { model ->
                add((model != null).toString())
                if (model != null) {
                    add(model.key); add(model.asset); add(model.artifactSha256.orEmpty())
                    add(model.sourceModel.orEmpty()); add(model.sourceModelSha256.orEmpty())
                    add(model.inputSize.toString()); add(model.enabled.toString()); add(model.integrityFailure?.name.orEmpty())
                    add(model.outputFormat.name); add(model.nmsIouThreshold.toString()); add(model.maxDetections.toString())
                    add(model.classes.size.toString()); addAll(model.classes)
                    add(model.allowlist.size.toString()); addAll(model.allowlist.sorted())
                    add(model.thresholds.size.toString())
                    model.thresholds.toSortedMap().forEach { (key, value) -> add(key); add(value.toString()) }
                    add(model.runtime.delegate); add(model.runtime.numThreads.toString())
                    add(model.runtime.fallbackToCpu.toString()); add(model.runtime.gpuPrecisionLossAllowed.toString())
                    add(model.runtime.gpuSerializationCacheEnabled.toString())
                }
            }
        }
        val bytes = fields.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

internal class FixedFixtureRuntimeSelection(
    private val nowNs: () -> Long = System::nanoTime,
    private val isMainThread: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() },
) {
    // All waiters are workers. Cancellation is checked while waiting, before native allocation.
    private val workerLock = ReentrantLock()
    private data class Cached(val choice: FixedFixtureChoice?, val persisted: Boolean)
    private val memory = mutableMapOf<String, Cached>()

    fun load(
        config: TwoModelRuntimeConfig,
        binding: String,
        threads: Int,
        allowComparison: Boolean,
        io: FixedFixtureRuntimeIo,
        isCurrent: () -> Boolean,
        onProgress: (String) -> Unit,
    ): AndroidDetectorLoadResult {
        check(!isMainThread()) { "Fixed runtime loading requires a worker" }
        fun active() {
            if (Thread.currentThread().isInterrupted || !isCurrent()) throw CancellationException("Fixed runtime load cancelled")
        }
        active()
        try {
            while (!workerLock.tryLock(50, TimeUnit.MILLISECONDS)) active()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Fixed runtime wait cancelled").apply { initCause(error) }
        }
        try {
            active()
            return loadOwned(config, binding, threads, allowComparison, io, ::active, onProgress)
        } finally {
            workerLock.unlock()
        }
    }

    private fun loadOwned(
        config: TwoModelRuntimeConfig,
        binding: String,
        threads: Int,
        allowComparison: Boolean,
        io: FixedFixtureRuntimeIo,
        active: () -> Unit,
        progress: (String) -> Unit,
    ): AndroidDetectorLoadResult {
        val owned = mutableListOf<FixedFixtureDetector>()
        var failure: Throwable? = null
        var recorded = false
        fun notifyObserver(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                // UI/diagnostic delivery failure says nothing about the verified runtime choice.
                throw FixedFixtureObserverFailure(error)
            }
        }
        fun release(detector: FixedFixtureDetector) {
            if (owned.remove(detector)) {
                try { detector.close() } catch (error: Throwable) { throw FixedFixtureCloseFailure(error) }
            }
        }
        fun write(choice: FixedFixtureChoice?) {
            active()
            val saved = try { io.write(choice?.encode() ?: FixedFixtureChoice.invalid(binding)) }
                catch (error: Exception) { if (error is CancellationException) throw error else false }
            // commit(false) can still mutate SharedPreferences memory. Never call it persisted.
            memory[binding] = Cached(choice, saved)
            recorded = true
            active()
        }
        fun create(backend: String): FixedFixtureDetector {
            active()
            check(owned.size < 2)
            val options = (config.unifiedWalksafe?.runtime ?: ModelRuntimeOptions()).copy(
                delegate = backend, numThreads = threads, fallbackToCpu = true, gpuPrecisionLossAllowed = false)
            val detector = io.create(options)
            owned += detector
            active()
            if (!detector.result.detectorAvailable || detector.result.modelKey != TwoModelRuntimeConfig.UNIFIED_MODEL_KEY ||
                detector.result.fallbackUsed) {
                val failedLoad = detector.result
                release(detector)
                if (failedLoad.reason == "DetectorNativeCleanupFailure") {
                    throw FixedFixtureCloseFailure(IllegalStateException("Native allocation cleanup failed"))
                }
                throw FixedFixtureLoadFailure(failedLoad.copy(detector = null, detectorAvailable = false))
            }
            return detector
        }
        fun infer(detector: FixedFixtureDetector, input: PreprocessedImage, backend: String): AndroidCalibrationDetectionResult {
            active()
            val output = detector.infer(input)
            active()
            val result = output.result
            val runtime = result.timing.modelRuntime
            check(result.inferenceCompleted && !result.partial &&
                result.timing.modelKey == TwoModelRuntimeConfig.UNIFIED_MODEL_KEY &&
                result.timing.completedModels == listOf(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY) &&
                runtime?.activeDelegate == backend && runtime.requestedDelegate == backend &&
                runtime.numThreads == threads && !runtime.fallbackUsed) { "Fixed runtime backend mismatch" }
            val model = requireNotNull(config.unifiedWalksafe)
            check(RuntimeCalibrationOutputGate.compare(output.rawOutput, output.rawOutput,
                result.detections, result.detections, model)) { "Invalid fixed runtime output" }
            return output
        }
        fun finish(detector: FixedFixtureDetector, source: String, cache: String, comparison: String,
            backend: String, ms: Double, baselineRejected: Boolean = comparison == "baseline_rejected"): AndroidDetectorLoadResult {
            owned.toList().filter { it !== detector }.forEach(::release)
            active()
            notifyObserver {
                progress(if (baselineRejected) "내장 점검 자료의 정확도 기준을 충족하지 못해 기본 인식 방식을 사용합니다." else when (cache) {
                    "memory_only" -> "인식 방식 준비 완료 · 저장하지 못해 이번 실행에서 사용합니다."
                    else -> "인식 방식 준비 완료"
                })
            }
            active()
            notifyObserver { io.log(source, cache, comparison, backend, ms) }
            active()
            val result = detector.result
            owned.remove(detector) // Transfer only after every callback and cancellation check.
            return if (baselineRejected) result.copy(reason = AndroidFixedFixtureRuntimeSelector.BASELINE_REJECTED_REASON) else result
        }
        try {
            val model = requireNotNull(config.unifiedWalksafe) { "Fixed runtime requires unified model" }
            check(config.primaryModelKey == TwoModelRuntimeConfig.UNIFIED_MODEL_KEY && model.enabled)
            require(threads in 1..4)
            val cached = memory[binding] ?: run {
                val encoded = try { io.read() } catch (_: Exception) { null }
                FixedFixtureChoice.decode(encoded, binding, threads)?.let { Cached(it, true) }
            }
            val choice = cached?.choice?.takeUnless {
                allowComparison && it.comparison == "baseline_rejected" && it.cpuMs == 0.0 && it.gpuMs == 0.0
            }
            notifyObserver {
                progress(if (choice != null) "저장된 인식 방식을 준비하고 있습니다."
                    else if (allowComparison) "내장 시험 자료로 인식 방식을 비교하고 있습니다."
                    else "기본 인식 방식을 준비하고 있습니다.")
            }
            active()
            val manifest = io.manifest()
            val required = manifest.fixtures.filter { it.required }
            check(manifest.version == RuntimeCalibrationFixtures.VERSION &&
                manifest.sha256 == RuntimeCalibrationFixtures.MANIFEST_SHA256 &&
                required.size == 4 && required.map { it.id }.distinct().size == 4)
            fun prepare(fixture: RuntimeCalibrationFixture): PreprocessedImage {
                active()
                return io.prepare(fixture).also { active() }
            }
            val warmInput = prepare(required.first())
            if (choice != null) {
                var loaded: FixedFixtureDetector? = null
                try {
                    loaded = create(choice.backend)
                    infer(loaded, warmInput, choice.backend)
                } catch (error: Exception) {
                    if (error is CancellationException || error is FixedFixtureCloseFailure ||
                        error is DetectorNativeCleanupFailure) throw error
                    loaded?.let(::release)
                    // A cached backend which no longer executes is explicitly invalid, retry next check.
                    write(null)
                }
                if (loaded != null && loaded in owned) return finish(loaded, "cache",
                    if (cached.persisted) "persisted" else "memory_only", "skipped", choice.backend,
                    if (choice.backend == "cpu") choice.cpuMs else choice.gpuMs,
                    baselineRejected = choice.comparison == "baseline_rejected")
                // Never start a surprise comparison in a cache-hit invocation, even on failure.
                val cpu = create("cpu")
                infer(cpu, warmInput, "cpu")
                return finish(cpu, "default", "invalid", "skipped", "cpu", 0.0)
            }

            val cpu = create("cpu")
            infer(cpu, warmInput, "cpu") // Exactly one warmup, excluded from measurements.
            if (!allowComparison) return finish(cpu, "default", "miss", "skipped", "cpu", 0.0)

            val references = mutableListOf<AndroidCalibrationDetectionResult>()
            var baselineRolesMatch = true
            for (fixture in required) {
                val output = infer(cpu, prepare(fixture), "cpu")
                val rolesMatch = RuntimeCalibrationOutputGate.matchesFixtureRoles(output.result.detections, output.result.detections,
                    RuntimeCalibrationFixtureRole.POSITIVE in fixture.roles,
                    RuntimeCalibrationFixtureRole.EMPTY in fixture.roles,
                    fixture.groundTruth.filter { !it.isCrowd }.map { it.className to it.bboxNorm })
                baselineRolesMatch = baselineRolesMatch && rolesMatch
                references += output
            }
            var gpu: FixedFixtureDetector? = null
            val cpuTimes = mutableListOf<Double>()
            val gpuTimes = mutableListOf<Double>()
            var gpuEquivalent = true
            var gpuRolesMatch = true
            var timingCompleted = false
            fun checkOutput(index: Int, output: AndroidCalibrationDetectionResult, backend: String) {
                check(RuntimeCalibrationOutputGate.compare(output.rawOutput, output.rawOutput,
                    output.result.detections, output.result.detections, model)) { "Invalid fixed runtime output" }
                val reference = references[index]
                val fixture = required[index]
                val equivalent = RuntimeCalibrationOutputGate.compare(reference.rawOutput, output.rawOutput,
                    reference.result.detections, output.result.detections, model)
                val rolesMatch = RuntimeCalibrationOutputGate.matchesFixtureRoles(reference.result.detections, output.result.detections,
                    RuntimeCalibrationFixtureRole.POSITIVE in fixture.roles,
                    RuntimeCalibrationFixtureRole.EMPTY in fixture.roles,
                    fixture.groundTruth.filter { !it.isCrowd }.map { it.className to it.bboxNorm })
                if (backend == "cpu") {
                    check(equivalent) { "Fixed CPU reference output mismatch" }
                    baselineRolesMatch = baselineRolesMatch && rolesMatch
                } else {
                    gpuEquivalent = gpuEquivalent && equivalent
                    gpuRolesMatch = gpuRolesMatch && rolesMatch
                }
            }
            fun timed(detector: FixedFixtureDetector, backend: String, index: Int,
                input: PreprocessedImage, times: MutableList<Double>) {
                try {
                    active()
                    val start = nowNs()
                    val output = detector.infer(input)
                    val ms = (nowNs() - start) / 1_000_000.0
                    active()
                    check(ms.isFinite() && ms > 0) { "Invalid fixed runtime duration" }
                    val runtime = output.result.timing.modelRuntime
                    check(output.result.inferenceCompleted && !output.result.partial &&
                        output.result.timing.modelKey == TwoModelRuntimeConfig.UNIFIED_MODEL_KEY &&
                        output.result.timing.completedModels == listOf(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY) &&
                        runtime?.activeDelegate == backend && runtime.requestedDelegate == backend &&
                        runtime.numThreads == threads && !runtime.fallbackUsed)
                    checkOutput(index, output, backend)
                    times += ms
                } catch (error: Exception) {
                    if (error is CancellationException || backend != "cpu") throw error
                    throw FixedFixtureCpuFailure(error)
                }
            }
            notifyObserver { progress("CPU와 GPU의 인식 시간을 비교하고 있습니다.") }
            active()
            try {
                val challenger = create("gpu")
                gpu = challenger
                // The preprocessor reuses its buffer; prepare the warm image again after CPU checks.
                infer(challenger, prepare(required.first()), "gpu")
                required.forEachIndexed { index, fixture -> checkOutput(index, infer(challenger, prepare(fixture), "gpu"), "gpu") }
                // AB then BA: same four inputs, counts and raw-copy work. Decode/prepare/gate are untimed.
                repeat(2) { round ->
                    required.forEachIndexed { index, fixture ->
                        val input = prepare(fixture)
                        if (round == 0) {
                            timed(cpu, "cpu", index, input, cpuTimes); timed(challenger, "gpu", index, input, gpuTimes)
                        } else {
                            timed(challenger, "gpu", index, input, gpuTimes); timed(cpu, "cpu", index, input, cpuTimes)
                        }
                    }
                }
                timingCompleted = true
            } catch (error: Exception) {
                if (error is CancellationException || error is FixedFixtureCpuFailure ||
                    error is FixedFixtureCloseFailure || error is DetectorNativeCleanupFailure) throw error
                active()
            }
            if (!baselineRolesMatch && cpuTimes.isEmpty()) {
                // GPU creation/warmup was attempted but failed before the paired rounds. Keep
                // actual CPU timing, without inventing a successful GPU duration or comparison.
                repeat(2) {
                    required.forEachIndexed { index, fixture -> timed(cpu, "cpu", index, prepare(fixture), cpuTimes) }
                }
            }
            val equivalent = timingCompleted && baselineRolesMatch && gpuEquivalent && gpuRolesMatch
            val cpuMs = if (equivalent || !baselineRolesMatch) cpuTimes.average() else 0.0
            val gpuMs = if (timingCompleted && (equivalent || !baselineRolesMatch)) gpuTimes.average() else 0.0
            val backend = if (equivalent && gpuMs < cpuMs) "gpu" else "cpu"
            val selected = if (backend == "gpu") requireNotNull(gpu) else cpu
            val comparison = if (!baselineRolesMatch) "baseline_rejected" else if (equivalent) "measured" else "gpu_rejected"
            // Close rejected runtimes before recording a successful choice.
            owned.toList().filter { it !== selected }.forEach(::release)
            write(FixedFixtureChoice(binding, backend, threads, comparison, cpuMs, gpuMs))
            return finish(selected, "comparison", if (memory.getValue(binding).persisted) "persisted" else "memory_only",
                comparison, backend, if (backend == "cpu") cpuMs else gpuMs,
                baselineRejected = comparison == "baseline_rejected")
        } catch (error: Throwable) {
            val reported = if (error is FixedFixtureObserverFailure) requireNotNull(error.cause) else error
            failure = reported
            if (error !is FixedFixtureObserverFailure && error !is CancellationException && !recorded) {
                try { write(null) } catch (writeError: Throwable) { reported.addSuppressed(writeError) }
            }
            if (error is FixedFixtureLoadFailure) return error.load
            throw reported
        } finally {
            var closeFailure: Throwable? = null
            owned.toList().forEach { detector ->
                try { release(detector) } catch (error: Throwable) {
                    if (closeFailure == null) closeFailure = error else closeFailure?.addSuppressed(error)
                }
            }
            closeFailure?.let { if (failure != null) failure?.addSuppressed(it) else throw it }
        }
    }

    private class FixedFixtureLoadFailure(val load: AndroidDetectorLoadResult) : IllegalStateException()
    private class FixedFixtureObserverFailure(cause: Throwable) : RuntimeException(cause)
    private class FixedFixtureCpuFailure(cause: Throwable) : IllegalStateException(cause)
    private class FixedFixtureCloseFailure(cause: Throwable) : IllegalStateException(cause)
}

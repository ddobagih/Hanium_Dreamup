package kr.co.hanium.dreamup.walksafe.inference.composite

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.InstanceMask
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.UnknownRuntimeTuning
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** All images, tensors and native owners stay in the disposable private process. */
internal class FixtureRuntimeWorker(private val context: Context, private val candidate: JointRuntimeCandidate,
    private val binding: String, private val deadlineMs: Long, private val progress: (FixtureRuntimeStage) -> Unit,
    private val finished: (JointFixtureRuntimeEvidence?, String, Boolean) -> Unit) {
    private val fixtures = RuntimeCalibrationFixtures(context)
    private val primaryLane = Executors.newSingleThreadExecutor { Thread(it, "WalkSafeFixturePrimary") }
    private val ids = AtomicLong(0)
    private val epoch = SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)
    private val waiters = ConcurrentHashMap<Long, CompletableFuture<FastSamResult<Attachment>>>()
    private val timing = ConcurrentHashMap<Long, Long>()
    private val failure = AtomicReference<Throwable?>()
    private val primaryBusy = AtomicBoolean(false)
    private val auxiliaryBusy = AtomicBoolean(false)
    private val lock = Object()
    private var primary: TfliteAndroidFrameDetector? = null
    private var fast: FastSamRuntimeService<Attachment>? = null
    private var primaryRuntime = RuntimeCandidate(RuntimeBackend.CPU, 1)
    private var auxiliaryRuntime = RuntimeCandidate(RuntimeBackend.CPU, 1)
    private lateinit var config: TwoModelRuntimeConfig
    private lateinit var cases: List<RuntimeCalibrationFixture>
    private val images = linkedMapOf<String, ArgbImage>()
    private val primaryReference = linkedMapOf<String, AndroidCalibrationDetectionResult>()
    private val auxiliaryReference = linkedMapOf<String, AuxiliaryOutput>()
    private val qualities = mutableListOf<JointFixtureQuality>()
    @Volatile private var measuring = false
    private var nativeCloseUncertain = false
    private var primaryAccepted = 0
    private var auxiliaryAccepted = 0
    private val primaryTimes = mutableListOf<Double>()
    private val auxiliaryTimes = mutableListOf<Double>()
    private val primaryCompleted = mutableListOf<Long>()
    private val auxiliaryCompleted = mutableListOf<Long>()

    fun run() {
        var evidence: JointFixtureRuntimeEvidence? = null
        var reason = "fixture_execution_unverified"
        var released = false
        try {
            checkBudget()
            config = TwoModelRuntimeConfig.load(context)
            check(AndroidJointRuntimeChoiceStore.createBinding(context, config) == binding) { "fixture_binding_mismatch" }
            check(config.primaryModelKey == TwoModelRuntimeConfig.UNIFIED_MODEL_KEY && requireNotNull(config.unifiedWalksafe).enabled)
            val manifest = fixtures.loadManifest()
            cases = manifest.fixtures.filter { it.required }
            check(manifest.version == RuntimeCalibrationFixtures.VERSION && manifest.sha256 == RuntimeCalibrationFixtures.MANIFEST_SHA256 &&
                cases.size == 4 && cases.map { it.id }.toSet() == JointRuntimeChoicePolicy.REQUIRED_FIXTURE_IDS)
            cases.forEach { images[it.id] = fixtures.loadArgb(it) }
            progress(FixtureRuntimeStage.REFERENCES)
            val cpu1 = RuntimeCandidate(RuntimeBackend.CPU, 1)
            openPrimary(cpu1)
            primaryOutput(cases.first()) // One untimed warmup on every fresh native owner.
            cases.forEach { primaryReference[it.id] = primaryOutput(it) }
            closePrimary()
            openFast(cpu1)
            fastOutput(cases.first())
            cases.forEach { auxiliaryReference[it.id] = fastOutput(it) }
            closeFast()

            progress(FixtureRuntimeStage.PRIMARY_LOAD)
            openPrimary(candidate.primary)
            primaryOutput(cases.first())
            progress(FixtureRuntimeStage.AUXILIARY_LOAD)
            openFast(candidate.auxiliary)
            fastOutput(cases.first())
            progress(FixtureRuntimeStage.OUTPUT_CHECK)
            cases.forEach { fixture ->
                val p = primaryOutput(fixture)
                val a = fastOutput(fixture)
                val reference = requireNotNull(primaryReference[fixture.id])
                val auxiliary = requireNotNull(auxiliaryReference[fixture.id])
                val equivalent = RuntimeCalibrationOutputGate.compare(reference.rawOutput, p.rawOutput,
                    reference.result.detections, p.result.detections, requireNotNull(config.unifiedWalksafe))
                val roles = rolesMatch(fixture, reference, p)
                val raw = UnknownRuntimeTuning.equivalentRaw(auxiliary.raw, a.raw)
                val masks = sameMasks(auxiliary.masks, a.masks)
                qualities += JointFixtureQuality(fixture.id, p.rawOutput?.size ?: 0, a.raw[0].size,
                    a.raw[1].size, equivalent, roles, raw, masks)
                check(equivalent && roles && raw && masks) { "fixture_output_mismatch_${fixture.id}" }
            }
            progress(FixtureRuntimeStage.BENCHMARK)
            evidence = measure()
            reason = "fixture_candidate_measured"
        } catch (error: Throwable) {
            reason = (error.cause?.message ?: error.message ?: error.javaClass.simpleName).take(FixtureRuntimeProtocol.MAX_REASON_CHARS)
        } finally {
            measuring = false
            progress(FixtureRuntimeStage.CLOSING)
            var closeFailure: Throwable? = null
            try { closeFast() } catch (error: Throwable) { closeFailure = error }
            try { closePrimary() } catch (error: Throwable) { closeFailure = error }
            primaryLane.shutdown()
            released = closeFailure == null && !nativeCloseUncertain
            if (!released) { evidence = null; reason = "fixture_native_close_unconfirmed" }
            finished(evidence, reason, released)
        }
    }

    private fun openPrimary(value: RuntimeCandidate) {
        checkBudget(); check(primary == null)
        primaryRuntime = value
        val loaded = TfliteAndroidFrameDetector.createWithStatusLocal(context,
            config.copy(fallbackModelKey = null, customTactile = null, cocoGeneral = null),
            ModelRuntimeOptions(value.backend.delegate, value.numThreads, fallbackToCpu = false,
                gpuPrecisionLossAllowed = false, gpuSerializationCacheEnabled = true), YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR)
        primary = loaded.detector
        if (loaded.reason == "DetectorNativeCleanupFailure") nativeCloseUncertain = true
        check(loaded.reason != "DetectorNativeCleanupFailure" && loaded.detectorAvailable && primary != null && !loaded.fallbackUsed) {
            "fixture_primary_unavailable_${loaded.reason}"
        }
    }

    private fun primaryOutput(fixture: RuntimeCalibrationFixture): AndroidCalibrationDetectionResult {
        checkBudget()
        val prepared = YuvImagePreprocessor().preprocessBilinear(requireNotNull(images[fixture.id]), requireNotNull(config.unifiedWalksafe).inputSize)
        val output = requireNotNull(primary).inferPreparedUnifiedForCalibration(prepared, 0L, YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR)
        requirePrimary(output.result)
        check(RuntimeCalibrationOutputGate.compare(output.rawOutput, output.rawOutput,
            output.result.detections, output.result.detections, requireNotNull(config.unifiedWalksafe))) { "fixture_primary_raw_invalid" }
        check(rolesMatch(fixture, output, output)) { "fixture_primary_roles_rejected" }
        return output
    }

    private fun rolesMatch(fixture: RuntimeCalibrationFixture, reference: AndroidCalibrationDetectionResult,
        candidate: AndroidCalibrationDetectionResult) = RuntimeCalibrationOutputGate.matchesFixtureRoles(
        reference.result.detections, candidate.result.detections, RuntimeCalibrationFixtureRole.POSITIVE in fixture.roles,
        RuntimeCalibrationFixtureRole.EMPTY in fixture.roles, fixture.groundTruth.filter { !it.isCrowd }.map { it.className to it.bboxNorm })

    private fun requirePrimary(result: AndroidDetectionResult) {
        val runtime = requireNotNull(result.timing.modelRuntime)
        check(result.inferenceCompleted && !result.partial && result.timing.modelKey == TwoModelRuntimeConfig.UNIFIED_MODEL_KEY &&
            result.timing.completedModels == listOf(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY) && !runtime.fallbackUsed &&
            runtime.activeDelegate == primaryRuntime.backend.delegate && runtime.requestedDelegate == primaryRuntime.backend.delegate &&
            runtime.numThreads == primaryRuntime.numThreads && runtime.gpuPrecisionLossAllowed != true) { "fixture_primary_runtime_mismatch" }
    }

    private fun closePrimary() {
        // The child can block here; the parent's independent EXIT lane can still terminate it.
        primaryLane.submit {}.get()
        primary?.close()
        primary = null
    }

    private fun openFast(value: RuntimeCandidate) {
        checkBudget(); check(fast == null)
        auxiliaryRuntime = value; failure.set(null)
        val options = FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.valueOf(value.backend.name), value.numThreads,
            false, deadlineMs * 1_000_000L, 800L, true, false, true,
            (deadlineMs - FixtureRuntimeProtocol.DEATH_ACK_MS) * 1_000_000L)
        val service = FastSamRuntimeService.createLocalForFixture(context, epoch, options, object : FastSamRuntimeService.Listener<Attachment> {
            override fun onResult(result: FastSamResult<Attachment>) {
                try {
                    requireFast(result.runtime)
                    val start = timing.remove(result.token.frameId)
                    if (start != null) {
                        val completed = nowNs()
                        synchronized(lock) { auxiliaryTimes += (completed - start) / 1_000_000.0; auxiliaryCompleted += now() }
                        auxiliaryBusy.set(false)
                    } else waiters.remove(result.token.frameId)?.complete(result)
                } catch (error: Throwable) { fail(error) }
                finally { synchronized(lock) { lock.notifyAll() } }
            }
            override fun onError(error: Throwable) { fail(error) }
            override fun onDiscard(token: FastSamFrameToken, reason: String) {
                waiters.remove(token.frameId)?.completeExceptionally(IllegalStateException(reason))
                if (timing.remove(token.frameId) != null) auxiliaryBusy.set(false)
                synchronized(lock) { lock.notifyAll() }
            }
        })
        fast = service
        requireFast(await(service.start()))
    }

    private fun requireFast(runtime: FastSamRuntimeInfo) {
        check(runtime.modelSha256 == FastSamModelContract.SHA256 && !runtime.fallbackUsed && !runtime.gpuPrecisionLossAllowed &&
            runtime.actualBackend.name == auxiliaryRuntime.backend.name && runtime.requestedBackend.name == auxiliaryRuntime.backend.name &&
            runtime.numThreads == auxiliaryRuntime.numThreads) { "fixture_auxiliary_runtime_mismatch" }
    }

    private fun fastOutput(fixture: RuntimeCalibrationFixture): AuxiliaryOutput {
        val future = CompletableFuture<FastSamResult<Attachment>>()
        val id = ids.incrementAndGet(); waiters[id] = future
        val image = requireNotNull(images[fixture.id])
        check(requireNotNull(fast).submitArgb(image.width, image.height, image.pixels,
            token(id, fixture.id, image), Attachment()) == FastSamRuntimeService.Admission.ACCEPTED)
        val result = await(future)
        val raw = requireNotNull(result.rawOutputs)
        fun copy(buffer: java.nio.FloatBuffer) = FloatArray(buffer.remaining()).also { buffer.get(it) }
        val value = AuxiliaryOutput(arrayOf(copy(raw.detections()), copy(raw.prototypes())), result.masks)
        check(UnknownRuntimeTuning.equivalentRaw(value.raw, value.raw)) { "fixture_auxiliary_raw_invalid" }
        return value
    }

    private fun closeFast() {
        val service = fast ?: return
        service.closeAsync()
        service.releasedFuture().get()
        fast = null
        waiters.clear(); timing.clear()
    }

    private fun measure(): JointFixtureRuntimeEvidence {
        val start = now()
        val stop = minOf(deadlineMs - FixtureRuntimeProtocol.DEATH_ACK_MS, start + 24_000L)
        check(stop - start >= 10_000L) { "fixture_benchmark_budget" }
        var nextPrimary = start; var nextAuxiliary = start
        var primaryIndex = 0; var auxiliaryIndex = 0
        var maximumThermal = thermal()
        val pPacing = AdaptiveInferencePacingPolicy().apply { configureMinimumInterval(candidate.primaryIntervalMs) }
        val aPacing = AdaptiveInferencePacingPolicy().apply { configureMinimumInterval(candidate.auxiliaryIntervalMs) }
        measuring = true
        while (now() < stop) {
            checkBudget(); failure.get()?.let { throw it }
            maximumThermal = maxOf(maximumThermal, thermal())
            val at = now()
            if (at >= nextPrimary && primaryBusy.compareAndSet(false, true)) {
                val ticket = pPacing.tryStart(at)
                if (ticket == null) primaryBusy.set(false) else {
                    nextPrimary = at + candidate.primaryIntervalMs
                    val fixture = cases[primaryIndex++ % cases.size]
                    val image = requireNotNull(images[fixture.id])
                    synchronized(lock) { primaryAccepted++ }
                    val submitted = nowNs()
                    primaryLane.execute {
                        try {
                            val output = requireNotNull(primary).detectCalibrationImage(image)
                            requirePrimary(output)
                            val completed = now()
                            pPacing.complete(ticket, completed, output.timing.modelInferenceMs, maximumThermal >= 3)
                            synchronized(lock) { primaryTimes += (nowNs() - submitted) / 1_000_000.0; primaryCompleted += completed }
                        } catch (error: Throwable) { fail(error) }
                        finally { pPacing.cancel(ticket); primaryBusy.set(false); synchronized(lock) { lock.notifyAll() } }
                    }
                }
            }
            val fastState = requireNotNull(fast).stats()
            if (at >= nextAuxiliary && !fastState.inflight && !fastState.pending && auxiliaryBusy.compareAndSet(false, true)) {
                val ticket = aPacing.tryStart(at)
                if (ticket == null) auxiliaryBusy.set(false) else {
                    val fixture = cases[auxiliaryIndex % cases.size]
                    val image = requireNotNull(images[fixture.id])
                    val id = ids.incrementAndGet(); val submitted = nowNs(); timing[id] = submitted
                    val admission = requireNotNull(fast).submitArgb(image.width, image.height, image.pixels,
                        token(id, fixture.id, image, submitted), Attachment())
                    if (admission == FastSamRuntimeService.Admission.ACCEPTED || admission == FastSamRuntimeService.Admission.REPLACED_PENDING) {
                        auxiliaryIndex++
                        synchronized(lock) { auxiliaryAccepted++ }
                        nextAuxiliary = at + candidate.auxiliaryIntervalMs
                    } else { timing.remove(id); auxiliaryBusy.set(false) }
                    // The service owns one whole flight. Its completion reopens auxiliaryBusy;
                    // cadence remains a lower bound without manufacturing queued submissions.
                    aPacing.cancel(ticket)
                }
            }
            val enough = synchronized(lock) { now() - start >= 10_000L &&
                ((primaryTimes.size >= 10 && auxiliaryTimes.size >= 10) || primaryTimes.any { it > 800.0 }) }
            if (enough) break
            synchronized(lock) { lock.wait(5L) }
        }
        measuring = false
        while (primaryBusy.get() || auxiliaryBusy.get()) { checkBudget(); failure.get()?.let { throw it }; synchronized(lock) { lock.wait(20L) } }
        val duration = now() - start
        synchronized(lock) {
            return JointFixtureRuntimeEvidence(duration, primaryAccepted, auxiliaryAccepted, primaryTimes.toList(), auxiliaryTimes.toList(),
                gap(primaryCompleted, start, duration), gap(auxiliaryCompleted, start, duration), qualities.toList(),
                primaryRuntime, auxiliaryRuntime, false, maximumThermal)
        }
    }

    private fun thermal(): Int {
        if (Build.VERSION.SDK_INT < 29) return -1
        return requireNotNull(context.getSystemService(PowerManager::class.java)) { "fixture_thermal_unobserved" }.currentThermalStatus
    }
    private fun token(id: Long, name: String, image: ArgbImage, capturedNs: Long = nowNs()) = FastSamFrameToken(
        FastSamFrameToken.Source.CALIBRATION_FIXTURE, epoch, id, 0L, 0L, capturedNs, "fixture-$name", image.width, image.height)
    private fun fail(error: Throwable) { failure.compareAndSet(null, error); waiters.values.forEach { it.completeExceptionally(error) }; synchronized(lock) { lock.notifyAll() } }
    private fun checkBudget() { check(now() < deadlineMs - FixtureRuntimeProtocol.DEATH_ACK_MS) { "fixture_total_budget" } }
    private fun <T> await(value: CompletableFuture<T>): T {
        while (true) { checkBudget(); try { return value.get(100L, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { } }
    }
    private fun now() = SystemClock.elapsedRealtime()
    private fun nowNs() = SystemClock.elapsedRealtimeNanos()
    private fun gap(values: List<Long>, start: Long, duration: Long) =
        (listOf(start) + values + (start + duration)).zipWithNext { a, b -> (b - a).toDouble() }.maxOrNull() ?: duration.toDouble()
    private class Attachment
    private data class AuxiliaryOutput(val raw: Array<FloatArray>, val masks: List<InstanceMask>)
    private fun sameMasks(a: List<InstanceMask>, b: List<InstanceMask>): Boolean = a.size == b.size &&
        a.sortedBy { it.anchorIndex() }.zip(b.sortedBy { it.anchorIndex() }).all { (x, y) ->
            x.anchorIndex() == y.anchorIndex() && x.maskLeft() == y.maskLeft() && x.maskTop() == y.maskTop() &&
                x.maskRightExclusive() == y.maskRightExclusive() && x.maskBottomExclusive() == y.maskBottomExclusive() &&
                x.copyPackedBits().contentEquals(y.copyPackedBits())
        }
}

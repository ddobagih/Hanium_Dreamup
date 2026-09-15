package kr.co.hanium.dreamup.walksafe.device

import android.content.Context
import android.media.Image
import android.os.SystemClock
import kr.co.hanium.dreamup.walksafe.inference.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Values copied on the AR render thread. No Frame, Image plane, pose array or scene is retained. */
data class RuntimeCalibrationLiveEvidence(
    val capturedAtElapsedRealtimeMs: Long,
    val cameraTimestampNs: Long,
    val sourceFrameTimestampNs: Long,
    val environment: RuntimeComparisonEnvironment,
    val cameraFrameIntervalMs: Double,
    val trackingCostMs: Double,
    val uiFrameDelayMs: Double,
    val depthTimestampFresh: Boolean,
    val positiveDepthSamples: Int,
    val poseValid: Boolean,
    val speechActive: Boolean = false,
    val depthSnapshot: kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot? = null,
    val imageToDepthUv: DoubleArray? = null,
    val mapperFrameId: Long? = null,
)

enum class RuntimeCalibrationStage { PREPARING, OUTPUT_CHECK, COMPARING, LIVE_BASELINE, LIVE_CANDIDATE, SELECTING, DRAINING }

data class RuntimeCalibrationProgress(
    val stage: RuntimeCalibrationStage,
    val elapsedMs: Long,
    val budgetMs: Long,
    val candidate: RuntimeCandidate?,
)

data class RuntimeCalibrationResult(
    val status: RuntimeSelectionStatus,
    val reason: String,
    val summary: RuntimeMeasurementSummary?,
    val selectedProfile: RuntimeTuningProfile?,
    val adopted: Boolean,
    val pending: Boolean,
    val queueSnapshot: RuntimeCandidateQueueSnapshot?,
)

/** Camera ownership is shared by calibration workloads; native execution stays with each owner. */
interface RuntimeCalibrationWorkController {
    fun start(): Boolean
    fun cancel(reason: String = "cancelled")
    fun whenDrained(callback: () -> Unit)
    fun offerLiveImage(
        image: Image,
        evidence: RuntimeCalibrationLiveEvidence,
        releaseImage: () -> Unit = { image.close() },
    ): Boolean
}

/**
 * The runner retains B until tryAdopt's actual swap returns true. Call tryAdopt on the production
 * detector executor after any profile persistence. A rejected/expired proposal cannot transfer B.
 */
class RuntimeCalibrationSelection internal constructor(
    val profile: RuntimeTuningProfile,
    val loadResult: AndroidDetectorLoadResult,
    val isBaseline: Boolean,
    private val adopt: ((AndroidDetectorLoadResult) -> Boolean) -> Boolean,
    private val rejectSelection: () -> Unit,
) {
    fun tryAdopt(swap: (AndroidDetectorLoadResult) -> Boolean): Boolean = adopt(swap)
    fun reject() = rejectSelection()
}

/**
 * One attempt, one 300-second deadline, current A borrowed through its existing executor, and at
 * most one owned challenger B. The worker may wait for A; A's executor never waits for this worker.
 * Native work is never interrupted. onFinished/whenDrained run after image and B ownership drain.
 */
class AndroidRuntimeCalibrationRunner(
    context: Context,
    private val config: TwoModelRuntimeConfig,
    private val baselineDetector: TfliteAndroidFrameDetector,
    private val baselineExecutor: Executor,
    private val baselineCandidate: RuntimeCandidate,
    private val bindingHash: String,
    private val featureScope: RuntimeFeatureScope,
    private val preprocessingStrategy: YuvPreprocessingStrategy,
    private val environmentProvider: () -> RuntimeComparisonEnvironment,
    private val isCurrent: () -> Boolean,
    private val callbackExecutor: Executor,
    private val onProgress: (RuntimeCalibrationProgress) -> Unit,
    private val onSelectionReady: (RuntimeCalibrationSelection) -> Unit,
    private val onFinished: (RuntimeCalibrationResult) -> Unit,
    private val onLiveDetection: (AndroidDetectionResult, RuntimeCalibrationLiveEvidence) -> Unit = { _, _ -> },
    private val speechActive: () -> Boolean = { false },
    private val baselinePreviouslyConfirmed: Boolean = false,
    private val availableProcessors: () -> Int = { Runtime.getRuntime().availableProcessors() },
    private val gpuSupported: Boolean = true,
    private val restoredQueue: RuntimeCandidateQueueSnapshot? = null,
    private val budgetMs: Long = MAXIMUM_BUDGET_MS,
) : RuntimeCalibrationWorkController {
    init { require(budgetMs in 1L..MAXIMUM_BUDGET_MS) }

    private val appContext = context.applicationContext
    private val monitor = Object()
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "WalkSafeCalibration") }
    private val deadlineTimer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "WalkSafeCalibrationDeadline") }
    private var deadlineTask: ScheduledFuture<*>? = null
    private var started = false
    private var drained = false
    private var draining = false
    private var cancelledReason: String? = null
    private var startedAtMs = 0L
    private var deadlineMs = 0L
    private var candidateLoad: AndroidDetectorLoadResult? = null
    private var currentCandidate: RuntimeCandidate? = null
    private var liveWindow: LiveWindow? = null
    private var liveImage: LiveImage? = null
    private var imageInFlight = false
    private var pendingSelection: RuntimeCalibrationSelection? = null
    private var adoptionDecided = false
    private var adopted = false
    private var selectedProfile: RuntimeTuningProfile? = null
    private val drainCallbacks = mutableListOf<() -> Unit>()
    private var estimatedScreeningMs = 20_000L
    private var imageReleaseFailed = false
    private var candidateReleaseFailed = false
    private val fixturePreprocessor = YuvImagePreprocessor()

    override fun start(): Boolean = synchronized(monitor) {
        if (started || drained) return false
        started = true
        startedAtMs = nowMs()
        deadlineMs = startedAtMs + budgetMs
        deadlineTask = deadlineTimer.schedule({ cancel("deadline") }, budgetMs, TimeUnit.MILLISECONDS)
        worker.execute(::runAttempt)
        true
    }

    /** Cancels admissions and adoption immediately; native calls already running drain naturally. */
    override fun cancel(reason: String) {
        var unstartedCallbacks: List<() -> Unit>? = null
        synchronized(monitor) {
            if (drained) return
            if (cancelledReason == null) cancelledReason = reason
            pendingSelection = null
            adoptionDecided = true
            if (!started) {
                drained = true
                unstartedCallbacks = drainCallbacks.toList()
                drainCallbacks.clear()
            }
            monitor.notifyAll()
        }
        unstartedCallbacks?.let { callbacks ->
            shutdownDeadlineTimer()
            worker.shutdown()
            dispatchCleanup { onFinished(RuntimeCalibrationResult(RuntimeSelectionStatus.PARTIAL,
                reason, null, null, false, true, null)) }
            callbacks.forEach(::dispatchCleanup)
        }
    }

    override fun whenDrained(callback: () -> Unit) {
        val dispatchNow = synchronized(monitor) {
            if (drained) true else { drainCallbacks += callback; false }
        }
        if (dispatchNow) dispatchCleanup(callback)
    }

    /** True transfers this Image only. False leaves it with the caller, who must close it. */
    override fun offerLiveImage(
        image: Image,
        evidence: RuntimeCalibrationLiveEvidence,
        releaseImage: () -> Unit,
    ): Boolean = synchronized(monitor) {
        val window = liveWindow ?: return false
        if (evidence.speechActive) window.speechObserved = true
        if (!activeLocked() || imageInFlight || evidence.speechActive) return false
        val ticket = window.pacing.tryStart(nowMs()) ?: return false
        imageInFlight = true
        window.submitted += 1
        window.cameraFrames += evidence.cameraTimestampNs
        liveImage = LiveImage(image, evidence, ticket, releaseImage)
        monitor.notifyAll()
        true
    }

    private fun runAttempt() {
        var queue: RuntimeCandidateQueue? = null
        var summary: RuntimeMeasurementSummary? = null
        var status = RuntimeSelectionStatus.PARTIAL
        var reason = "insufficient_budget"
        try {
            checkActive()
            val unified = requireNotNull(config.unifiedWalksafe)
            require(config.primaryModelKey == TwoModelRuntimeConfig.UNIFIED_MODEL_KEY && unified.enabled)
            require(bindingHash.isNotBlank())
            progress(RuntimeCalibrationStage.PREPARING)
            val fixtures = RuntimeCalibrationFixtures(appContext)
            val manifest = fixtures.loadManifest()
            val required = manifest.fixtures.filter { it.required }
            require(required.isNotEmpty() && manifest.coverage.positiveFixtureIds.isNotEmpty() &&
                manifest.coverage.emptyFixtureIds.isNotEmpty() && manifest.coverage.nearThresholdFixtureIds.isNotEmpty())
            queue = RuntimeSelectionPolicy.createCandidateQueue(
                availableProcessors(), baselineCandidate, gpuSupported, restored = restoredQueue,
            )
            var best: ScreenedCandidate? = null
            val screened = mutableSetOf<RuntimeCandidate>()
            // Reserve final reconstruction, repeated output checks, live A/B and cleanup. Screening
            // retains scalar timings/options only, so no third interpreter is ever kept alive.
            while (remainingMs() > FINAL_VERIFICATION_RESERVE_MS + estimatedScreeningMs) {
                checkActive()
                val candidate = queue.nextCandidate(availableProcessors()) ?: break
                if (candidate == baselineCandidate) {
                    queue.recordOutcome(candidate, RuntimeSelectionStatus.CONFIRMED, selectedCandidate = baselineCandidate)
                    continue
                }
                if (!screened.add(candidate)) {
                    queue.recordOutcome(candidate, RuntimeSelectionStatus.INCONCLUSIVE)
                    break // Never re-use this attempt's incomplete samples or screen a value twice.
                }
                currentCandidate = candidate
                val screenStarted = nowMs()
                try {
                    val detector = prepareCandidate(candidate)
                    val fixed = measureFixedCandidate(fixtures, manifest, required, detector, candidate)
                    val score = screenScore(fixed)
                    val item = ScreenedCandidate(candidate, score)
                    val initialCpuTie = !baselinePreviouslyConfirmed && baselineCandidate.backend == RuntimeBackend.CPU &&
                        candidate.backend == RuntimeBackend.CPU && candidate.numThreads < baselineCandidate.numThreads &&
                        score == 0.0 && best?.score == 0.0 &&
                        (best.candidate.backend != RuntimeBackend.CPU || candidate.numThreads < best.candidate.numThreads)
                    if (best == null || score > best.score || initialCpuTie) best = item
                    // A clear GPU advantage allocates remaining screening time to its CPU thread
                    // settings. Uncertain/GPU-worse results expand the CPU integer search instead.
                    val preferred = if (candidate.backend == RuntimeBackend.GPU && score > 0.0)
                        RuntimeBackend.GPU else if (best?.candidate?.backend == RuntimeBackend.GPU && best.score > 0.0)
                        RuntimeBackend.GPU else RuntimeBackend.CPU
                    queue.recordOutcome(candidate, RuntimeSelectionStatus.INCONCLUSIVE, preferredBackend = preferred)
                } catch (_: CandidateUnavailable) {
                    queue.recordOutcome(candidate, RuntimeSelectionStatus.UNSUPPORTED, preferredBackend = RuntimeBackend.CPU)
                } catch (error: CandidateRejected) {
                    queue.recordOutcome(candidate, RuntimeSelectionStatus.REJECTED, preferredBackend = RuntimeBackend.CPU)
                    reason = error.message ?: "candidate_rejected"
                } catch (error: CandidateInconclusive) {
                    queue.recordOutcome(candidate, RuntimeSelectionStatus.INCONCLUSIVE, preferredBackend = RuntimeBackend.CPU)
                    reason = error.message ?: "screening_unverified"
                } finally {
                    closeCandidate()
                    estimatedScreeningMs = maxOf(estimatedScreeningMs, nowMs() - screenStarted)
                }
            }
            checkMeasurementBudget()
            // With no executable challenger (including N=1/GPU unsupported), validate the single
            // current configuration against independent A repetitions without allocating another A.
            val finalCandidate = best?.candidate ?: baselineCandidate
            currentCandidate = finalCandidate
            val baselineOnly = finalCandidate == baselineCandidate
            val finalDetector = if (baselineOnly) baselineDetector else prepareCandidate(finalCandidate)
            val fixed = measureFixedCandidate(fixtures, manifest, required, finalDetector, finalCandidate,
                challengerBorrowed = baselineOnly)
            val aLive = measureLive(baselineDetector, baselineCandidate, borrowed = true)
            val bLive = measureLive(finalDetector, finalCandidate, borrowed = baselineOnly, candidatePhase = true)
            summary = RuntimeMeasurementSummary(fixed.ab, fixed.ba, fixed.confirmation,
                aLive, bLive, fixed.fixture, 1.0)
            checkActive()
            val decision = RuntimeSelectionPolicy.evaluate(
                baselineCandidate, finalCandidate, summary, nowMs(), deadlineMs,
                baselinePreviouslyConfirmed = baselinePreviouslyConfirmed,
            )
            status = decision.status
            reason = decision.reason
            if (status == RuntimeSelectionStatus.CONFIRMED) {
                val profile = RuntimeTuningProfile.fromConfirmed(decision, System.currentTimeMillis())
                if (profile != null && profile.bindingHash == bindingHash && profile.featureScope == featureScope) {
                    val isBaseline = decision.selectedCandidate == baselineCandidate
                    val load = if (isBaseline) AndroidDetectorLoadResult(
                        baselineDetector, true, true, TwoModelRuntimeConfig.UNIFIED_MODEL_KEY,
                        false, "borrowed_calibration_baseline",
                    ) else checkNotNull(candidateLoad)
                    if (offerSelection(profile, load, isBaseline)) {
                        queue.recordFinalDecision(decision)
                        reason = "selection_adopted"
                    }
                    else { status = RuntimeSelectionStatus.PARTIAL; reason = "selection_not_adopted" }
                } else { status = RuntimeSelectionStatus.INCONCLUSIVE; reason = "profile_binding_not_confirmed" }
            }
        } catch (_: AttemptStopped) {
            status = RuntimeSelectionStatus.PARTIAL
            reason = synchronized(monitor) { cancelledReason } ?: "insufficient_budget_or_obsolete_attempt"
        } catch (error: CandidateInconclusive) {
            status = RuntimeSelectionStatus.INCONCLUSIVE
            reason = error.message ?: "comparison_unverified"
        } catch (error: CandidateRejected) {
            status = RuntimeSelectionStatus.REJECTED
            reason = error.message ?: "candidate_rejected"
        } catch (_: OutOfMemoryError) {
            status = RuntimeSelectionStatus.PARTIAL
            reason = "memory_pressure"
        } catch (_: Exception) {
            status = RuntimeSelectionStatus.PARTIAL
            reason = "calibration_unavailable"
        } finally {
            val remainingImage = synchronized(monitor) {
                // Fence queued phase/selection callbacks before touching resources. A delayed
                // progress callback cannot reopen admission while native close is still draining.
                draining = true
                pendingSelection = null
                liveWindow = null
                liveImage.also { liveImage = null }
            }
            dispatchCleanup {
                if (isCurrent()) onProgress(RuntimeCalibrationProgress(RuntimeCalibrationStage.DRAINING,
                    nowMs() - startedAtMs, budgetMs, currentCandidate))
            }
            remainingImage?.let { runCatching { releaseLiveImage(it) } }
            try { closeCandidate() } catch (_: Throwable) {
                // A failed native close is not proof of drain; no new camera flow is released.
                shutdownDeadlineTimer()
                worker.shutdown()
                return
            }
            shutdownDeadlineTimer()
            if (imageReleaseFailed) { worker.shutdown(); return }
            val callbacks: List<() -> Unit>
            val result: RuntimeCalibrationResult
            synchronized(monitor) {
                imageInFlight = false
                pendingSelection = null
                drained = true
                val cancelled = cancelledReason != null || !isCurrent() || nowMs() >= deadlineMs
                result = RuntimeCalibrationResult(
                    status = if (cancelled && !adopted) RuntimeSelectionStatus.PARTIAL else status,
                    reason = if (cancelled && !adopted) cancelledReason ?: "obsolete_attempt" else reason,
                    summary = summary,
                    selectedProfile = selectedProfile,
                    adopted = adopted,
                    pending = !adopted,
                    queueSnapshot = queue?.snapshot(),
                )
                callbacks = drainCallbacks.toList()
                drainCallbacks.clear()
                monitor.notifyAll()
            }
            worker.shutdown()
            // Cleanup notifications intentionally survive a screen change. Main fences progression.
            dispatchCleanup { onFinished(result) }
            callbacks.forEach(::dispatchCleanup)
        }
    }

    private fun prepareCandidate(candidate: RuntimeCandidate): TfliteAndroidFrameDetector {
        checkMeasurementBudget()
        check(synchronized(monitor) { candidateLoad == null }) { "previous candidate must close before creation" }
        progress(RuntimeCalibrationStage.PREPARING)
        val unified = checkNotNull(config.unifiedWalksafe)
        val options = unified.runtime.copy(delegate = candidate.backend.delegate,
            numThreads = candidate.numThreads, fallbackToCpu = false)
        val onlyUnified = config.copy(unifiedWalksafe = unified.copy(runtime = options),
            fallbackModelKey = null, customTactile = null, cocoGeneral = null)
        val load = TfliteAndroidFrameDetector.createWithStatus(appContext, onlyUnified,
            preprocessingStrategy = preprocessingStrategy)
        synchronized(monitor) { candidateLoad = load }
        checkActive()
        if (load.detector == null || load.modelKey != TwoModelRuntimeConfig.UNIFIED_MODEL_KEY || load.fallbackUsed)
            throw CandidateUnavailable()
        return load.detector
    }

    private fun measureFixedCandidate(
        fixtures: RuntimeCalibrationFixtures,
        manifest: RuntimeCalibrationFixtureManifest,
        required: List<RuntimeCalibrationFixture>,
        detector: TfliteAndroidFrameDetector,
        candidate: RuntimeCandidate,
        challengerBorrowed: Boolean = false,
    ): FixedCandidateEvidence {
        val warmImage = fixtures.loadArgb(required.first())
        repeat(2) {
            fixedInference(warmImage, baselineDetector, baselineCandidate, true, false)
            fixedInference(warmImage, detector, candidate, challengerBorrowed, false)
        }
        progress(RuntimeCalibrationStage.OUTPUT_CHECK)
        val completed = mutableSetOf<String>()
        var nearThresholdObserved = false
        for (fixture in required) {
            val image = fixtures.loadArgb(fixture)
            // Both outputs consume this exact tensor before the reusable preparation buffer can
            // be touched again. Correctness snapshots are separate from the timing blocks below.
            val input = fixturePreprocessor.preprocessBilinear(image, checkNotNull(config.unifiedWalksafe).inputSize)
            val a = correctnessOutput(input, baselineDetector, baselineCandidate, true)
            val b = correctnessOutput(input, detector, candidate, challengerBorrowed)
            if (!RuntimeCalibrationOutputGate.compare(a.rawOutput, b.rawOutput,
                    a.result.detections, b.result.detections, checkNotNull(config.unifiedWalksafe)) ||
                !RuntimeCalibrationOutputGate.matchesFixtureRoles(
                    a.result.detections, b.result.detections,
                    RuntimeCalibrationFixtureRole.POSITIVE in fixture.roles,
                    RuntimeCalibrationFixtureRole.EMPTY in fixture.roles,
                    fixture.groundTruth.filter { !it.isCrowd }.map { it.className to it.bboxNorm },
                )) throw CandidateRejected("fixture_output_mismatch")
            if (RuntimeCalibrationFixtureRole.NEAR_THRESHOLD in fixture.roles &&
                RuntimeCalibrationOutputGate.hasNearThresholdCandidate(a.rawOutput, checkNotNull(config.unifiedWalksafe))) {
                nearThresholdObserved = true
            }
            completed += fixture.id
        }
        if (!nearThresholdObserved) throw CandidateInconclusive("near_threshold_not_observed")
        val fixtureEvidence = RuntimeFixtureEvidence(
            version = manifest.version, hash = manifest.sha256,
            requiredCaseCount = required.size, completedCaseCount = completed.size, outputEquivalent = true,
            baselineActualBackend = baselineCandidate.backend, candidateActualBackend = candidate.backend,
            requiredCaseIds = required.map { it.id }.toSet(), completedCaseIds = completed.toSet(),
        )
        progress(RuntimeCalibrationStage.COMPARING)
        return FixedCandidateEvidence(
            comparisonBlock(RuntimeComparisonOrder.AB, required, fixtures, detector, candidate, challengerBorrowed),
            comparisonBlock(RuntimeComparisonOrder.BA, required, fixtures, detector, candidate, challengerBorrowed),
            comparisonBlock(RuntimeComparisonOrder.CONFIRMATION, required, fixtures, detector, candidate, challengerBorrowed),
            fixtureEvidence,
        )
    }

    /** Screening ranks work for final live validation; it can never issue a profile or adoption. */
    private fun screenScore(fixed: FixedCandidateEvidence): Double {
        val blocks = listOf(fixed.ab, fixed.ba, fixed.confirmation)
        val environments = blocks.flatMap { block -> block.pairs.flatMap { listOf(it.baselineEnvironment, it.candidateEnvironment) } }
        if (environments.distinct().size != 1 || blocks.any { block -> block.pairs.any {
                !it.baselineCompleted || !it.candidateCompleted || it.speechActive ||
                    !it.baselineMs.isFinite() || !it.candidateMs.isFinite() || it.baselineMs <= 0 || it.candidateMs <= 0
            } }) throw CandidateInconclusive("incomparable_screening")
        val gains = blocks.map { block -> median(block.pairs.map { it.baselineMs - it.candidateMs }) }
        val uncertainty = blocks.maxOf { block ->
            val deltas = block.pairs.map { it.baselineMs - it.candidateMs }
            maxOf(1.0, block.aaVariationMs.max(), 2 * median(deltas.map { kotlin.math.abs(it - median(deltas)) }))
        }
        if (gains.any { kotlin.math.abs(it) <= uncertainty } || gains.any { it > 0 } && gains.any { it < 0 }) return 0.0
        return blocks.minOf { block -> median(block.pairs.map { it.baselineMs - it.candidateMs }) /
            median(block.pairs.map { it.baselineMs }) }
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else sorted[sorted.size / 2 - 1] / 2 + sorted[sorted.size / 2] / 2
    }

    private data class ScreenedCandidate(val candidate: RuntimeCandidate, val score: Double)
    private data class FixedCandidateEvidence(
        val ab: RuntimeComparisonBlock,
        val ba: RuntimeComparisonBlock,
        val confirmation: RuntimeComparisonBlock,
        val fixture: RuntimeFixtureEvidence,
    )
    private class CandidateUnavailable : RuntimeException()

    private fun correctnessOutput(
        input: PreprocessedImage,
        detector: TfliteAndroidFrameDetector,
        candidate: RuntimeCandidate,
        borrowed: Boolean,
    ): AndroidCalibrationDetectionResult {
        checkMeasurementBudget()
        val output = invokeDetector(borrowed) {
            detector.inferPreparedUnifiedForCalibration(input, 0L, preprocessingStrategy, copyRawOutput = true)
        }
        requireActualRuntime(output.result, candidate)
        checkActive()
        return output
    }

    private fun comparisonBlock(
        order: RuntimeComparisonOrder,
        required: List<RuntimeCalibrationFixture>,
        fixtures: RuntimeCalibrationFixtures,
        detector: TfliteAndroidFrameDetector,
        candidate: RuntimeCandidate,
        challengerBorrowed: Boolean,
    ): RuntimeComparisonBlock {
        val pairs = mutableListOf<RuntimePairedSample>()
        val variation = mutableListOf<Double>()
        repeat(maxOf(MINIMUM_PAIRS, required.size)) { index ->
            val fixture = required[index % required.size]
            val image = fixtures.loadArgb(fixture)
            val quietBefore = !speechActive()
            val a: FixedMeasurement
            val b: FixedMeasurement
            if (order == RuntimeComparisonOrder.BA) {
                b = fixedInference(image, detector, candidate, challengerBorrowed, false)
                a = fixedInference(image, baselineDetector, baselineCandidate, true, false)
            } else {
                a = fixedInference(image, baselineDetector, baselineCandidate, true, false)
                b = fixedInference(image, detector, candidate, challengerBorrowed, false)
            }
            val repeatedA = fixedInference(image, baselineDetector, baselineCandidate, true, false)
            variation += kotlin.math.abs(a.durationMs - repeatedA.durationMs)
            pairs += RuntimePairedSample(
                fixture.id, a.durationMs, b.durationMs, a.environment, b.environment,
                baselineCompleted = a.environment == repeatedA.environment,
                speechActive = !quietBefore || speechActive(),
            )
        }
        return RuntimeComparisonBlock(order, pairs, variation)
    }

    private fun fixedInference(
        image: ArgbImage,
        detector: TfliteAndroidFrameDetector,
        candidate: RuntimeCandidate,
        borrowed: Boolean,
        copyRaw: Boolean,
    ): FixedMeasurement {
        checkMeasurementBudget()
        val before = environmentProvider()
        val startNs = System.nanoTime()
        val prepared = fixturePreprocessor.preprocessBilinear(image, checkNotNull(config.unifiedWalksafe).inputSize)
        val preparationMs = (System.nanoTime() - startNs) / 1_000_000L
        val output = invokeDetector(borrowed) {
            detector.inferPreparedUnifiedForCalibration(prepared, preparationMs, preprocessingStrategy, copyRaw)
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        requireActualRuntime(output.result, candidate)
        checkActive()
        val after = environmentProvider()
        if (before != after) throw CandidateRejected("environment_changed")
        return FixedMeasurement(output, elapsedMs, after)
    }

    private fun <T> invokeDetector(borrowed: Boolean, operation: () -> T): T {
        checkActive()
        if (!borrowed) return operation()
        val result = CompletableFuture<T>()
        baselineExecutor.execute {
            try {
                checkActive()
                result.complete(operation())
            } catch (error: Throwable) { result.completeExceptionally(error) }
        }
        // Never cancel/interrupt a submitted native invoke. Its natural return precedes cleanup.
        return try { result.get() } catch (error: java.util.concurrent.ExecutionException) {
            throw (error.cause ?: error)
        }
    }

    private fun requireActualRuntime(result: AndroidDetectionResult, expected: RuntimeCandidate) {
        val runtime = result.timing.modelRuntime
        if (result.partial || result.timing.modelKey != TwoModelRuntimeConfig.UNIFIED_MODEL_KEY ||
            result.timing.completedModels != listOf(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY) ||
            runtime == null || runtime.activeDelegate != expected.backend.delegate ||
            runtime.numThreads != expected.numThreads || runtime.fallbackUsed) {
            throw CandidateRejected("actual_runtime_mismatch")
        }
    }

    private fun measureLive(
        detector: TfliteAndroidFrameDetector,
        candidate: RuntimeCandidate,
        borrowed: Boolean,
        candidatePhase: Boolean = false,
    ): RuntimeLiveMetrics {
        checkMeasurementBudget()
        val stage = if (candidatePhase || !borrowed) RuntimeCalibrationStage.LIVE_CANDIDATE else RuntimeCalibrationStage.LIVE_BASELINE
        val window = beginLiveWindow(stage)
        try {
            while (true) {
                checkMeasurementBudget()
                val elapsed = nowMs() - window.startedAtMs
                if (elapsed >= MAXIMUM_LIVE_DURATION_MS) throw CandidateInconclusive("insufficient_live_samples")
                if (elapsed >= LIVE_DURATION_MS && window.latencies.size >= MINIMUM_LIVE_COMPLETIONS) break
                val frame = synchronized(monitor) {
                    while (liveImage == null && activeLocked() && nowMs() - window.startedAtMs < MAXIMUM_LIVE_DURATION_MS) {
                        monitor.wait(100L)
                        if (remainingMs() <= CLEANUP_RESERVE_MS) break
                    }
                    liveImage.also { liveImage = null }
                } ?: continue
                var completion: AndroidDetectionResult? = null
                try {
                    checkActive()
                    val evidence = frame.evidence
                    if (evidence.environment != window.environment || speechActive() || evidence.speechActive) {
                        window.dropped += 1
                        throw CandidateInconclusive("live_environment_changed")
                    }
                    val aligned = evidence.cameraTimestampNs > 0L &&
                        evidence.cameraTimestampNs == frame.image.timestamp &&
                        evidence.sourceFrameTimestampNs > 0L &&
                        kotlin.math.abs(evidence.sourceFrameTimestampNs - evidence.cameraTimestampNs) <= 50_000_000L
                    if (!aligned || evidence.cameraTimestampNs <= window.lastCameraNs ||
                        evidence.capturedAtElapsedRealtimeMs !in window.startedAtMs..nowMs()) {
                        window.dropped += 1
                        continue
                    }
                    window.lastCameraNs = evidence.cameraTimestampNs
                    val detected = invokeDetector(borrowed) {
                        detector.detect(frame.image, evidence.capturedAtElapsedRealtimeMs)
                    }
                    completion = detected
                    requireActualRuntime(detected, candidate)
                    val completedMs = nowMs()
                    if (completedMs - window.startedAtMs >= MAXIMUM_LIVE_DURATION_MS) {
                        window.dropped += 1
                        throw CandidateInconclusive("insufficient_live_samples")
                    }
                    val latency = completedMs - evidence.capturedAtElapsedRealtimeMs
                    if (latency !in 0L..MAXIMUM_FRESH_AGE_MS) {
                        window.stale += 1
                        continue
                    }
                    checkActive()
                    if (environmentProvider() != window.environment || speechActive()) {
                        window.dropped += 1
                        throw CandidateInconclusive("live_environment_changed")
                    }
                    if (!evidence.cameraFrameIntervalMs.isFinite() || evidence.cameraFrameIntervalMs <= 0.0 ||
                        !evidence.trackingCostMs.isFinite() || evidence.trackingCostMs < 0.0 ||
                        !evidence.uiFrameDelayMs.isFinite() || evidence.uiFrameDelayMs < 0.0) {
                        window.dropped += 1
                        throw CandidateInconclusive("live_tracking_unverified")
                    }
                    if (featureScope == RuntimeFeatureScope.CAMERA_TRACKING_METRIC_DEPTH) {
                        if (evidence.environment.arCoreState != "TRACKING") {
                            window.dropped += 1
                            throw CandidateInconclusive("metric_depth_load_unverified")
                        }
                        if (!evidence.poseValid || !evidence.depthTimestampFresh || evidence.positiveDepthSamples <= 0) {
                            // Depth/pose observations can be absent on an individual camera frame.
                            // Keep its submitted/drop denominator and gap cost, but require enough
                            // later valid observations within this same bounded live window.
                            window.dropped += 1
                            continue
                        }
                    }
                    window.latencies += latency.toDouble()
                    window.completionTimes += completedMs
                    window.evidence += evidence
                    dispatchActive {
                        if (synchronized(monitor) { liveWindow === window }) onLiveDetection(detected, evidence)
                    }
                } finally {
                    try { releaseLiveImage(frame) } finally {
                        if (completion == null) window.pacing.cancel(frame.ticket)
                        else window.pacing.complete(frame.ticket, nowMs(), completion.timing.modelInferenceMs,
                            frame.evidence.environment.thermalStatus >= 2)
                        synchronized(monitor) {
                            val elapsed = nowMs() - window.startedAtMs
                            if ((elapsed >= LIVE_DURATION_MS && window.latencies.size >= MINIMUM_LIVE_COMPLETIONS) ||
                                elapsed >= MAXIMUM_LIVE_DURATION_MS) {
                                // Close admissions before releasing the slot; an accepted tail frame
                                // must not be counted as a drop merely because the window just ended.
                                if (liveWindow === window) liveWindow = null
                            }
                            imageInFlight = false
                            monitor.notifyAll()
                        }
                    }
                }
            }
        } finally {
            // Prevent a new admission before draining one accepted just as the window ended.
            val queued = synchronized(monitor) {
                if (liveWindow === window) liveWindow = null
                liveImage.also { liveImage = null }
            }
            queued?.let {
                try { releaseLiveImage(it) } finally {
                    window.pacing.cancel(it.ticket)
                    window.dropped += 1
                    synchronized(monitor) { imageInFlight = false }
                }
            }
        }
        if (window.speechObserved) throw CandidateInconclusive("speech_during_live_window")
        val duration = nowMs() - window.startedAtMs
        val times = listOf(window.startedAtMs) + window.completionTimes + nowMs()
        return RuntimeLiveMetrics(
            environment = window.environment,
            actualBackend = candidate.backend,
            captureToCompleteMs = window.latencies.toList(),
            observationDurationMs = duration,
            validCompletions = window.latencies.size,
            submittedFrames = window.submitted,
            staleFrames = window.stale,
            droppedFrames = window.dropped,
            cameraFrameIntervalMs = window.evidence.map { it.cameraFrameIntervalMs }.average(),
            trackingCostMs = window.evidence.map { it.trackingCostMs }.average(),
            uiFrameDelayMs = window.evidence.map { it.uiFrameDelayMs }.average(),
            depthTimestampFresh = window.evidence.all { it.depthTimestampFresh },
            positiveDepthSamples = window.evidence.minOfOrNull { it.positiveDepthSamples } ?: 0,
            poseValid = window.evidence.all { it.poseValid },
            adaptivePacing = true,
            uniqueCameraFrames = window.cameraFrames.count { it > 0L },
            longestCompletionGapMs = times.zipWithNext { a, b -> (b - a).toDouble() }.maxOrNull() ?: duration.toDouble(),
        )
    }

    private fun beginLiveWindow(stage: RuntimeCalibrationStage): LiveWindow {
        // Session phase-reset work runs synchronously inside onProgress. Open admissions only after
        // it returns; queued UI callbacks cannot mix an old tracker's cost into the new A/B window.
        dispatchActive {
            onProgress(RuntimeCalibrationProgress(stage, nowMs() - startedAtMs, budgetMs, currentCandidate))
            synchronized(monitor) {
                if (activeLocked()) liveWindow = LiveWindow(environmentProvider(), nowMs())
                monitor.notifyAll()
            }
        }
        synchronized(monitor) {
            while (liveWindow == null && activeLocked() && remainingMs() > CLEANUP_RESERVE_MS) monitor.wait(100L)
            checkActive()
            return liveWindow ?: throw AttemptStopped()
        }
    }

    private fun offerSelection(profile: RuntimeTuningProfile, load: AndroidDetectorLoadResult, isBaseline: Boolean): Boolean {
        checkActive()
        progress(RuntimeCalibrationStage.SELECTING)
        lateinit var proposal: RuntimeCalibrationSelection
        proposal = RuntimeCalibrationSelection(profile, load, isBaseline,
            adopt = { swap ->
                synchronized(monitor) {
                    if (!activeLocked() || pendingSelection !== proposal || adoptionDecided) false
                    else {
                        val swapped = try { swap(load) } catch (_: Exception) { false }
                        if (swapped) {
                            if (!isBaseline) candidateLoad = null
                            selectedProfile = profile
                            adopted = true
                        }
                        adoptionDecided = true
                        pendingSelection = null
                        monitor.notifyAll()
                        swapped
                    }
                }
            },
            rejectSelection = {
                synchronized(monitor) {
                    if (pendingSelection === proposal) {
                        pendingSelection = null
                        adoptionDecided = true
                        monitor.notifyAll()
                    }
                }
            },
        )
        synchronized(monitor) {
            checkActive()
            pendingSelection = proposal
            adoptionDecided = false
        }
        dispatchActive { onSelectionReady(proposal) }
        synchronized(monitor) {
            while (activeLocked() && !adoptionDecided) monitor.wait(minOf(100L, remainingMs().coerceAtLeast(1L)))
            return adopted
        }
    }

    private fun closeCandidate() {
        // The runner worker is the sole closer; tryAdopt atomically clears only a transferred B.
        // TFLite marks itself closed before native release. A second no-op close cannot repair a
        // first failure or turn an unknown native-resource state into successful drain evidence.
        check(!candidateReleaseFailed) { "candidate native release remains unconfirmed" }
        val load = synchronized(monitor) { candidateLoad }
        try { load?.detector?.close() } catch (error: Throwable) {
            candidateReleaseFailed = true
            throw error
        }
        synchronized(monitor) { if (candidateLoad === load) candidateLoad = null }
    }

    private fun progress(stage: RuntimeCalibrationStage) {
        val progress = RuntimeCalibrationProgress(stage, (nowMs() - startedAtMs).coerceAtLeast(0L), budgetMs, currentCandidate)
        dispatchActive { onProgress(progress) }
    }

    private fun dispatchActive(callback: () -> Unit) {
        try {
            callbackExecutor.execute {
                val active = synchronized(monitor) { activeLocked() }
                if (active) try { callback() } catch (_: Exception) { cancel("callback_failed") }
            }
        } catch (_: RuntimeException) { cancel("callback_executor_unavailable") }
    }

    private fun remainingMs(): Long = deadlineMs - nowMs()
    private fun checkMeasurementBudget() {
        checkActive()
        if (remainingMs() <= CLEANUP_RESERVE_MS) throw AttemptStopped()
    }

    private data class FixedMeasurement(
        val output: AndroidCalibrationDetectionResult,
        val durationMs: Double,
        val environment: RuntimeComparisonEnvironment,
    )
    private class CandidateRejected(reason: String) : RuntimeException(reason)
    private class CandidateInconclusive(reason: String) : RuntimeException(reason)

    private fun releaseLiveImage(frame: LiveImage) {
        try { frame.close() } catch (error: Throwable) {
            imageReleaseFailed = true
            cancel("image_release_failed")
            throw error
        }
    }

    private fun shutdownDeadlineTimer() {
        // Scheduled executors normally retain delayed tasks after shutdown. Remove this attempt's
        // deadline first so early completion releases its timer thread without waiting five minutes.
        // A deadline callback already running may return naturally; native work is never interrupted.
        deadlineTask?.cancel(false)
        deadlineTimer.shutdown()
    }

    private fun nowMs(): Long = SystemClock.elapsedRealtime()
    private fun activeLocked(): Boolean = started && !draining && !drained && cancelledReason == null && nowMs() < deadlineMs && isCurrent()
    private fun checkActive() {
        synchronized(monitor) { if (!activeLocked()) throw AttemptStopped() }
    }

    private fun dispatchCleanup(callback: () -> Unit) {
        val work = Runnable {
            // A consumer exception must not replay a cleanup callback or prevent the remaining
            // drain observers from being notified.
            try { callback() } catch (_: Exception) { }
        }
        try { callbackExecutor.execute(work) } catch (_: RuntimeException) { work.run() }
    }

    private class AttemptStopped : RuntimeException()
    private class LiveImage(
        val image: Image,
        val evidence: RuntimeCalibrationLiveEvidence,
        val ticket: InferencePacingTicket,
        private val releaseImage: () -> Unit,
    ) {
        private var closed = false
        fun close() {
            if (closed) return
            // Only the worker owns release. A failed release is never reported as drained.
            closed = true
            releaseImage()
        }
    }
    private class LiveWindow(val environment: RuntimeComparisonEnvironment, val startedAtMs: Long) {
        val pacing = AdaptiveInferencePacingPolicy()
        var submitted = 0
        var stale = 0
        var dropped = 0
        var lastCameraNs = 0L
        @Volatile var speechObserved = false
        val latencies = mutableListOf<Double>()
        val completionTimes = mutableListOf<Long>()
        val evidence = mutableListOf<RuntimeCalibrationLiveEvidence>()
        val cameraFrames = mutableSetOf<Long>()
    }

    companion object {
        const val MAXIMUM_BUDGET_MS = 300_000L
        private const val CLEANUP_RESERVE_MS = 20_000L
        private const val FINAL_VERIFICATION_RESERVE_MS = 90_000L
        private const val LIVE_DURATION_MS = 10_000L
        private const val MAXIMUM_LIVE_DURATION_MS = 25_000L
        private const val MINIMUM_LIVE_COMPLETIONS = 10
        private const val MINIMUM_PAIRS = 4
        private const val MAXIMUM_FRESH_AGE_MS = 800L
    }
}

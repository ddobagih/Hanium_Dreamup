package kr.co.hanium.dreamup.walksafe.inference.unknown.tuning

import android.app.Activity
import android.media.Image
import android.os.SystemClock
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.InstanceMask
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

interface UnknownCalibrationAttachment : UnknownRuntimeAttachment {
    val primaryResult: AndroidDetectionResult
}

/** A null profile is an executable tentative choice, never a confirmed measurement. */
class UnknownRuntimeSelection internal constructor(
    val profile: UnknownRuntimeProfile?,
    val readyService: FastSamRuntimeService<UnknownRuntimeAttachment>,
    val candidate: RuntimeCandidate,
    val minimumNextFrameId: Long,
    private val adopt: (() -> Boolean) -> Boolean,
    private val rejectSelection: () -> Unit,
) {
    /** Main performs its final flow/epoch check inside swap. False keeps cleanup with calibration. */
    fun tryAdopt(swap: () -> Boolean): Boolean = adopt(swap)
    fun reject() = rejectSelection()
}

/** One camera session and one auxiliary slot. The primary's typed detector and owner are borrowed. */
class UnknownRuntimeCalibration(
    private val activity: Activity,
    private val binding: UnknownRuntimeBinding,
    private val primaryDetector: TfliteAndroidFrameDetector,
    private val primaryExecutor: Executor,
    private val sessionEpoch: Long,
    private val requestDeadlineMs: Long,
    private val isCurrent: () -> Boolean,
    private val speechActive: () -> Boolean,
    private val onSelected: (UnknownRuntimeSelection) -> Unit,
    private val onClosed: (RuntimeCalibrationResult?) -> Unit,
    private val onProductionResult: (FastSamResult<UnknownRuntimeAttachment>) -> Unit,
    private val onCalibrationResult: (FastSamResult<UnknownRuntimeAttachment>, RuntimeCalibrationLiveEvidence) -> Boolean,
    private val onProductionError: (Throwable) -> Unit = {},
    private val onProductionDiscard: (FastSamFrameToken, String) -> Unit = { _, _ -> },
) {
    @Volatile var isRunning = false
        private set
    @Volatile var isDraining = false
        private set
    private var session: AndroidRuntimeCalibrationSession? = null
    private var controller: Work? = null
    private val cancelled = AtomicBoolean(false)
    private val hasStarted = AtomicBoolean(false)

    fun start(): Boolean {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        val remaining = requestDeadlineMs - now()
        if (isRunning || cancelled.get() || !isCurrent() || binding.hashOrNull() == null ||
            remaining !in 1..UnknownRuntimeTuning.MAX_BUDGET_MS || sessionEpoch <= 0) return false
        if (!hasStarted.compareAndSet(false, true)) return false
        isRunning = true
        session = AndroidRuntimeCalibrationSession(activity, requireNotNull(binding.hashOrNull()), binding.featureScope,
            speechActive, runnerFactory = { environment, progress, finished, detection ->
                Work(environment, progress, finished, detection).also { controller = it }
            }, onClosed = { result ->
                isRunning = false
                isDraining = false
                session = null
                controller = null
                onClosed(result)
            }, budgetMs = remaining, startRunnerBeforeCameraReady = true)
        session!!.start()
        return true
    }

    fun cancel(reason: String = "cancelled") {
        cancelled.set(true)
        isDraining = isRunning
        controller?.cancel(reason)
        session?.cancel(reason)
    }

    private fun now() = SystemClock.elapsedRealtime()
    private fun current() = !cancelled.get() && isCurrent() && now() < requestDeadlineMs

    private inner class Work(
        private val environment: () -> RuntimeComparisonEnvironment,
        private val progress: (RuntimeCalibrationProgress) -> Unit,
        private val finished: (RuntimeCalibrationResult) -> Unit,
        private val detection: (AndroidDetectionResult, RuntimeCalibrationLiveEvidence) -> Unit,
    ) : RuntimeCalibrationWorkController {
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "walksafe-unknown-calibration") }
        private val lock = Object()
        private val imageBusy = AtomicBoolean(false)
        private val frameIds = AtomicLong(0)
        private val fixtureWaiters = ConcurrentHashMap<Long, CompletableFuture<FastSamResult<UnknownRuntimeAttachment>>>()
        private val liveTickets = ConcurrentHashMap<Long, Pair<LiveWindow, InferencePacingTicket>>()
        private var started = false
        private var drained = false
        private val drainCallbacks = mutableListOf<() -> Unit>()
        @Volatile private var service: FastSamRuntimeService<UnknownRuntimeAttachment>? = null
        @Volatile private var candidate = RuntimeCandidate(RuntimeBackend.CPU, 1)
        @Volatile private var window: LiveWindow? = null
        @Volatile private var adopted = false
        @Volatile private var serviceFailure: Throwable? = null
        private var proposalDecided = false
        private var selectedProfile: UnknownRuntimeProfile? = null
        private var releaseFailed = false
        private var estimatedPairMs = 180_000L
        private val startedAt = now()
        private val store = AndroidUnknownRuntimeProfileStore(activity)
        private val fixtures = RuntimeCalibrationFixtures(activity)
        private lateinit var cases: List<RuntimeCalibrationFixture>
        private val references = linkedMapOf<String, FixtureOutput>()

        override fun start(): Boolean = synchronized(lock) {
            if (started) return true
            if (drained || !current()) return false
            started = true
            worker.execute(::run)
            true
        }

        override fun cancel(reason: String) {
            isDraining = true
            var ownedAtCancellation: FastSamRuntimeService<UnknownRuntimeAttachment>? = null
            val unstarted = synchronized(lock) {
                // Transfer and cancellation have one owner decision. Never close a service that
                // finished adoption while this cancellation was waiting for the proposal lock.
                cancelled.set(true)
                ownedAtCancellation = service
                window = null
                proposalDecided = true
                lock.notifyAll()
                if (!started && !drained) {
                    drained = true
                    drainCallbacks.toList().also { drainCallbacks.clear() }
                } else null
            }
            ownedAtCancellation?.closeAsync()
            if (unstarted != null) {
                worker.shutdown()
                activity.runOnUiThread {
                    finished(RuntimeCalibrationResult(RuntimeSelectionStatus.PARTIAL, reason, null, null,
                        false, true, null))
                    unstarted.forEach { it() }
                }
            }
        }

        override fun whenDrained(callback: () -> Unit) {
            val dispatch = synchronized(lock) {
                if (drained) true else { drainCallbacks += callback; false }
            }
            if (dispatch) activity.runOnUiThread(callback)
        }

        override fun offerLiveImage(image: Image, evidence: RuntimeCalibrationLiveEvidence, releaseImage: () -> Unit): Boolean {
            val live = synchronized(lock) {
                val activeWindow = window ?: return false
                if (drained || !current() || !validLiveEnvironment(evidence) ||
                    !imageBusy.compareAndSet(false, true)) return false
                activeWindow
            }
            val primaryTicket = live.primaryPacing.tryStart(evidence.capturedAtElapsedRealtimeMs)
            if (primaryTicket == null) { imageBusy.set(false); return false }
            try {
                primaryExecutor.execute {
                    try {
                        if (!current() || window !== live) return@execute
                        val output = primaryDetector.detect(image, evidence.capturedAtElapsedRealtimeMs)
                        val primaryCompleted = now()
                        live.primaryPacing.complete(primaryTicket, primaryCompleted, output.timing.modelInferenceMs,
                            evidence.environment.thermalStatus >= 3)
                        val runtime = output.timing.modelRuntime
                        if (output.partial || runtime == null || runtime.fallbackUsed ||
                            runtime.activeDelegate != binding.primaryRuntime.backend.delegate ||
                            runtime.numThreads != binding.primaryRuntime.numThreads) return@execute
                        synchronized(live) {
                            live.primaryTimes += (primaryCompleted - evidence.capturedAtElapsedRealtimeMs).toDouble()
                            live.primaryCompleted += primaryCompleted
                        }
                        activity.runOnUiThread { if (current() && window === live) detection(output, evidence) }
                        val currentService = service ?: return@execute
                        val sourceId = evidence.sourceFrameTimestampNs
                        if (sourceId <= frameIds.get()) return@execute
                        frameIds.set(sourceId)
                        val auxiliaryTicket = live.pacing.tryStart(evidence.capturedAtElapsedRealtimeMs) ?: return@execute
                        val token = FastSamFrameToken(FastSamFrameToken.Source.LIVE_CAMERA, sessionEpoch,
                            sourceId, evidence.sourceFrameTimestampNs, image.timestamp,
                            evidence.capturedAtElapsedRealtimeMs * 1_000_000L, "calibration-$sessionEpoch",
                            image.width, image.height)
                        liveTickets[token.frameId] = live to auxiliaryTicket
                        val admitted = currentService.submit(image, token, LiveAttachment(live, evidence, auxiliaryTicket, output))
                        synchronized(live) {
                            live.inputs += evidence.cameraTimestampNs
                            if (admitted == FastSamRuntimeService.Admission.ACCEPTED ||
                                admitted == FastSamRuntimeService.Admission.REPLACED_PENDING) live.submitted++
                            else { liveTickets.remove(token.frameId); live.pacing.cancel(auxiliaryTicket) }
                        }
                    } catch (_: Throwable) {
                        cancel("primary_or_auxiliary_execution_failed")
                    } finally {
                        live.primaryPacing.cancel(primaryTicket)
                        try { releaseImage() } catch (_: Throwable) { releaseFailed = true; cancel("image_close_failed") }
                        imageBusy.set(false)
                        synchronized(lock) { lock.notifyAll() }
                    }
                }
            } catch (_: RejectedExecutionException) {
                live.primaryPacing.cancel(primaryTicket); imageBusy.set(false); return false
            }
            return true
        }

        private fun run() {
            var status = RuntimeSelectionStatus.PARTIAL
            var reason = "live_or_comparison_unverified"
            var summary: RuntimeMeasurementSummary? = null
            val queue = RuntimeSelectionPolicy.createCandidateQueue(Runtime.getRuntime().availableProcessors(),
                RuntimeCandidate(RuntimeBackend.CPU, 1), true, restored = store.loadCursor(binding))
            try {
                checkCurrent()
                check(store.markStarted(binding)) { "attempt_store_failed" }
                val manifest = fixtures.loadManifest()
                require(manifest.sha256 == binding.fixtureSha256 && manifest.version == binding.fixtureVersion)
                // All declared required inputs are checked; their YOLO roles are not FastSAM ground truth.
                cases = manifest.fixtures.filter { it.required }
                require(cases.isNotEmpty())
                open(RuntimeCandidate(RuntimeBackend.CPU, 1))
                cases.forEach { fixture -> references[fixture.id] = output(fixture, checkReference = false).second }
                closeService()
                var baseline = RuntimeCandidate(RuntimeBackend.CPU, 1)
                while (current()) {
                    val next = queue.nextCandidate(Runtime.getRuntime().availableProcessors()) ?: break
                    if (next == baseline) { queue.recordOutcome(next, RuntimeSelectionStatus.CONFIRMED); continue }
                    val comparisonStarted = now()
                    try {
                        // GPU is the first execution candidate after the one-thread CPU output reference.
                        closeService()
                        open(next)
                        warmup()
                        if (!enough(estimatedPairMs)) {
                            offer(null)
                            break
                        }
                        closeService()
                        val a1 = block(baseline, RuntimeCalibrationStage.LIVE_BASELINE)
                        val b1 = block(next, RuntimeCalibrationStage.LIVE_CANDIDATE)
                        val b2 = block(next, RuntimeCalibrationStage.LIVE_CANDIDATE)
                        val a2 = block(baseline, RuntimeCalibrationStage.LIVE_BASELINE)
                        val a3 = block(baseline, RuntimeCalibrationStage.LIVE_BASELINE)
                        val b3 = block(next, RuntimeCalibrationStage.LIVE_CANDIDATE)
                        val baselineLive = combineLive(listOf(a1, a2, a3), baseline)
                        val candidateLive = combineLive(listOf(b1, b2, b3), next)
                        val ids = cases.map { it.id }.toSet()
                        summary = RuntimeMeasurementSummary(
                            paired(RuntimeComparisonOrder.AB, a1, b1, a1, a2),
                            paired(RuntimeComparisonOrder.BA, a2, b2, a2, a3),
                            paired(RuntimeComparisonOrder.CONFIRMATION, a3, b3, a1, a3),
                            baselineLive, candidateLive,
                            RuntimeFixtureEvidence(binding.fixtureVersion, binding.fixtureSha256, ids.size, ids.size,
                                true, baseline.backend, next.backend, requiredCaseIds = ids, completedCaseIds = ids), 1.0,
                        )
                        val decision = RuntimeSelectionPolicy.evaluate(baseline, next, summary, now(), requestDeadlineMs)
                        val profile = UnknownRuntimeTuning.confirm(binding, baseline, next, summary,
                            combinePrimary(listOf(a1, a2, a3)), combinePrimary(listOf(b1, b2, b3)),
                            now(), requestDeadlineMs, System.currentTimeMillis())
                        queue.recordOutcome(next, if (profile == null) decision.status else RuntimeSelectionStatus.CONFIRMED,
                            selectedCandidate = profile?.candidate)
                        estimatedPairMs = (now() - comparisonStarted).coerceAtLeast(60_000L)
                        if (profile != null) {
                            baseline = profile.candidate
                            if (candidate != baseline) { closeService(); open(baseline); warmup() }
                            offer(profile)
                            status = if (selectedProfile != null) RuntimeSelectionStatus.CONFIRMED else RuntimeSelectionStatus.PARTIAL
                            reason = if (selectedProfile != null) "confirmed_fixed_primary_composition" else "adoption_not_confirmed"
                            break
                        }
                    } catch (_: Incomplete) {
                        queue.recordOutcome(next, RuntimeSelectionStatus.PARTIAL)
                        if (service != null && current()) offer(null)
                        break
                    } catch (_: Rejected) {
                        queue.recordOutcome(next, RuntimeSelectionStatus.REJECTED)
                        closeService()
                    } catch (error: ExecutionException) {
                        val outcome = when (error.cause) {
                            is UnsupportedOperationException -> RuntimeSelectionStatus.UNSUPPORTED
                            is Incomplete, is TimeoutException -> RuntimeSelectionStatus.PARTIAL
                            else -> RuntimeSelectionStatus.REJECTED
                        }
                        queue.recordOutcome(next, outcome)
                        closeService()
                    }
                }
            } catch (_: Incomplete) { reason = "deadline_or_cancelled" }
            catch (_: Throwable) { reason = "execution_or_output_unverified" }
            finally {
                isDraining = true
                synchronized(lock) { window = null; while (imageBusy.get()) lock.wait(50L) }
                try { closeService() } catch (_: Throwable) { releaseFailed = true }
                if (!releaseFailed) {
                    fixtureWaiters.clear()
                    liveTickets.clear()
                    references.clear()
                    if (::cases.isInitialized) cases = emptyList()
                    if (!adopted) selectedProfile = null
                    store.markPending(binding, if (selectedProfile != null && adopted) null else
                        DeviceTuningRecheckReason.INCOMPLETE_COMPARISON, if (selectedProfile == null) queue.snapshot() else null)
                    val callbacks = synchronized(lock) { drained = true; drainCallbacks.toList().also { drainCallbacks.clear() } }
                    activity.runOnUiThread {
                        finished(RuntimeCalibrationResult(status, reason, summary, null, adopted,
                            selectedProfile == null, queue.snapshot()))
                        callbacks.forEach { it() }
                    }
                }
                worker.shutdown()
            }
        }

        private fun open(value: RuntimeCandidate) {
            checkCurrent()
            check(!imageBusy.get() && window == null) { "fixture must not overlap a borrowed primary image" }
            check(service == null) { "previous auxiliary owner must release before load" }
            synchronized(lock) { serviceFailure = null }
            candidate = value
            emit(RuntimeCalibrationStage.PREPARING)
            val options = FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.valueOf(value.backend.name),
                value.numThreads, false, requestDeadlineMs * 1_000_000L, 5_000L, true, false, true)
            val created = FastSamRuntimeService(activity, sessionEpoch, options,
                object : FastSamRuntimeService.Listener<UnknownRuntimeAttachment> {
                    override fun onResult(result: FastSamResult<UnknownRuntimeAttachment>) {
                        when (val attachment = result.attachment) {
                            is FixtureAttachment -> fixtureWaiters.remove(result.token.frameId)?.complete(result)
                            is LiveAttachment -> receiveLive(result, attachment)
                            else -> onProductionResult(result)
                        }
                    }
                    override fun onError(error: Throwable) {
                        val production = synchronized(lock) {
                            fixtureWaiters.values.forEach { it.completeExceptionally(error) }
                            if (adopted) true else {
                                serviceFailure = error
                                proposalDecided = true
                                lock.notifyAll()
                                false
                            }
                        }
                        if (production) onProductionError(error)
                    }
                    override fun onDiscard(token: FastSamFrameToken, reason: String) {
                        fixtureWaiters.remove(token.frameId)?.completeExceptionally(Incomplete())
                        liveTickets.remove(token.frameId)?.let { (live, ticket) -> live.pacing.cancel(ticket) }
                        if (synchronized(lock) { adopted }) onProductionDiscard(token, reason)
                    }
                })
            val readiness = synchronized(lock) {
                // Register even an unstarted owner so every obsolete/cancelled path can release it.
                service = created
                checkCurrent()
                created.start()
            }
            val runtime = await(readiness)
            requireRuntime(runtime)
            // A future paired check reconstructs this backend twice; cold load is measured, not free.
            if (value.backend == RuntimeBackend.GPU) estimatedPairMs = maxOf(estimatedPairMs,
                (runtime.loadMs * 2).toLong() + 80_000L)
            checkCurrent()
        }

        private fun closeService() {
            val previous = service ?: return
            previous.closeAsync()
            // A load/invoke failure may be recoverable, but a failed native release never frees the slot.
            try { previous.releasedFuture().get() } catch (error: Throwable) { releaseFailed = true; throw error }
            service = null
        }

        private fun warmup() {
            val times = mutableListOf<Double>()
            val limit = minOf(requestDeadlineMs - UnknownRuntimeTuning.CLEANUP_RESERVE_MS, now() + 15_000L)
            while (now() < limit) {
                val measured = output(cases.first())
                times += measured.first
                if (UnknownRuntimeTuning.warmupStable(times)) return
            }
            throw Incomplete()
        }

        private fun output(fixture: RuntimeCalibrationFixture, checkReference: Boolean = true): Pair<Double, FixtureOutput> {
            checkCurrent()
            check(!imageBusy.get() && window == null) { "fixture must not overlap a borrowed primary image" }
            val environmentBefore = environment()
            val speakingBefore = speechActive()
            val image = fixtures.loadArgb(fixture)
            val id = frameIds.incrementAndGet()
            val future = CompletableFuture<FastSamResult<UnknownRuntimeAttachment>>()
            fixtureWaiters[id] = future
            val captured = SystemClock.elapsedRealtimeNanos()
            val token = FastSamFrameToken(FastSamFrameToken.Source.CALIBRATION_FIXTURE, sessionEpoch, id,
                0L, 0L, captured, "fixture-${fixture.id}", image.width, image.height)
            val start = System.nanoTime()
            val admission = requireNotNull(service).submitArgb(image.width, image.height, image.pixels, token, FixtureAttachment())
            if (admission != FastSamRuntimeService.Admission.ACCEPTED) { fixtureWaiters.remove(id); throw Incomplete() }
            val result = await(future)
            if (checkReference && environmentBefore != environment()) throw Incomplete()
            requireRuntime(result.runtime)
            val raw = result.rawOutputs ?: throw Rejected()
            fun copy(buffer: java.nio.FloatBuffer) = FloatArray(buffer.remaining()).also { buffer.get(it) }
            val value = FixtureOutput(arrayOf(copy(raw.detections()), copy(raw.prototypes())), result.masks,
                environmentBefore, speakingBefore || speechActive())
            if (checkReference) {
                val reference = references[fixture.id] ?: throw Rejected()
                if (!UnknownRuntimeTuning.equivalentRaw(reference.raw, value.raw) || !sameMasks(reference.masks, value.masks)) throw Rejected()
            }
            return (System.nanoTime() - start) / 1_000_000.0 to value
        }

        private fun block(value: RuntimeCandidate, stage: RuntimeCalibrationStage): Block {
            if (candidate != value || service == null) { closeService(); open(value); warmup() }
            val timings = cases.map { fixture ->
                val sample = output(fixture)
                FixedTiming(fixture.id, sample.first, sample.second.environment, sample.second.speechActive)
            }
            val boundary = CompletableFuture<Unit>()
            activity.runOnUiThread {
                if (current()) progress(RuntimeCalibrationProgress(stage, now() - startedAt,
                    (requestDeadlineMs - startedAt).coerceAtLeast(1), candidate))
                boundary.complete(Unit)
            }
            await(boundary)
            val live = LiveWindow(environment(), now())
            synchronized(lock) { checkCurrent(); window = live }
            try {
                val until = minOf(requestDeadlineMs - UnknownRuntimeTuning.CLEANUP_RESERVE_MS, live.start + 25_000L)
                while (current() && now() < until) {
                    serviceFailure?.let { throw ExecutionException(it) }
                    val complete = synchronized(live) {
                        now() - live.start >= 10_000L && live.times.size >= 10 && live.primaryTimes.size >= 10
                    }
                    if (complete) break
                    synchronized(lock) { lock.wait(50L) }
                }
            } finally { synchronized(lock) { window = null } }
            synchronized(lock) { while (imageBusy.get()) lock.wait(50L) }
            while (service?.stats()?.inflight == true || service?.stats()?.pending == true) {
                checkCurrent(); synchronized(lock) { lock.wait(20L) }
            }
            live.duration = now() - live.start
            if (live.times.size < 10 || live.primaryTimes.size < 10 || live.duration < 10_000L ||
                live.evidence.isEmpty() || live.evidence.any { it.environment != live.environment }) throw Incomplete()
            return Block(timings, live)
        }

        private fun receiveLive(result: FastSamResult<UnknownRuntimeAttachment>, attachment: LiveAttachment) {
            try {
                if (!current() || window !== attachment.window) return
                requireRuntime(result.runtime)
                val evidence = attachment.evidence
                if (!onCalibrationResult(result, evidence)) return
                val completed = now()
                attachment.window.pacing.complete(attachment.ticket, completed, result.inferenceMs.toLong(),
                    evidence.environment.thermalStatus >= 3)
                val elapsed = completed - evidence.capturedAtElapsedRealtimeMs
                if (elapsed !in 1L..800L) return
                synchronized(attachment.window) {
                    attachment.window.times += elapsed.toDouble()
                    attachment.window.completed += completed
                    attachment.window.evidence += evidence
                }
                synchronized(lock) { lock.notifyAll() }
            } finally {
                liveTickets.remove(result.token.frameId)
                attachment.window.pacing.cancel(attachment.ticket)
            }
        }

        private fun paired(order: RuntimeComparisonOrder, a: Block, b: Block, aa: Block, ab: Block): RuntimeComparisonBlock {
            val pairs = a.timings.zip(b.timings).map { (x, y) ->
                require(x.caseId == y.caseId)
                RuntimePairedSample(x.caseId, x.elapsedMs, y.elapsedMs, x.environment, y.environment,
                    speechActive = x.speechActive || y.speechActive)
            }
            val variation = aa.timings.zip(ab.timings).map { (x, y) -> abs(x.elapsedMs - y.elapsedMs) }
            return RuntimeComparisonBlock(order, pairs, variation)
        }

        private fun combineLive(blocks: List<Block>, value: RuntimeCandidate): RuntimeLiveMetrics {
            val observations = blocks.flatMap { it.live.evidence }
            val times = blocks.flatMap { it.live.times }
            val submitted = blocks.sumOf { it.live.submitted }
            val gaps = blocks.map { gap(it.live.completed, it.live.start, it.live.duration) }
            return RuntimeLiveMetrics(blocks.first().live.environment, value.backend, times,
                blocks.sumOf { it.live.duration }, times.size, submitted, 0, (submitted - times.size).coerceAtLeast(0),
                observations.maxOf { it.cameraFrameIntervalMs }, observations.maxOf { it.trackingCostMs },
                observations.maxOf { it.uiFrameDelayMs }, observations.all { it.depthTimestampFresh },
                observations.minOf { it.positiveDepthSamples }, observations.all { it.poseValid }, true,
                blocks.sumOf { it.live.inputs.size }, gaps.max())
        }

        private fun combinePrimary(blocks: List<Block>): UnknownPrimaryLoad {
            fun variation(values: List<Double>) = values.max() - values.min()
            val gaps = blocks.map { gap(it.live.primaryCompleted, it.live.start, it.live.duration) }
            return UnknownPrimaryLoad(blocks.sumOf { it.live.duration }, blocks.flatMap { it.live.primaryTimes },
                gaps.max(), blocks.sumOf { block -> block.live.primaryTimes.count { it > 800.0 } },
                variation(blocks.map { it.live.primaryTimes.max() }), variation(gaps),
                variation(blocks.map { it.live.primaryTimes.size.toDouble() / it.live.duration }),
                variation(blocks.map { block -> block.live.primaryTimes.count { it > 800.0 }.toDouble() / block.live.primaryTimes.size }))
        }

        private fun gap(values: List<Long>, start: Long, duration: Long): Double =
            (listOf(start) + values + (start + duration)).zipWithNext { a, b -> (b - a).toDouble() }.maxOrNull() ?: duration.toDouble()

        private fun offer(profile: UnknownRuntimeProfile?) {
            checkCurrent()
            val ready = service ?: return
            synchronized(lock) { window = null; while (imageBusy.get()) lock.wait(50L) }
            ready.discardPending("calibration_selection")
            while (ready.stats().inflight) { checkCurrent(); synchronized(lock) { lock.wait(20L) } }
            if (!healthy(ready)) throw Rejected()
            val confirmed = profile?.takeIf { current() && store.save(binding, it) }
            proposalDecided = false
            val proposal = UnknownRuntimeSelection(confirmed, ready, candidate, frameIds.get() + 1,
                adopt = { swap -> synchronized(lock) {
                    if (!current() || proposalDecided || service !== ready || !healthy(ready)) false else {
                        val accepted = runCatching(swap).getOrDefault(false)
                        proposalDecided = true
                        if (accepted) { service = null; adopted = true; selectedProfile = confirmed }
                        lock.notifyAll()
                        accepted
                    }
                } }, rejectSelection = { synchronized(lock) { proposalDecided = true; lock.notifyAll() } })
            activity.runOnUiThread { if (current()) onSelected(proposal) else proposal.reject() }
            synchronized(lock) { while (current() && !proposalDecided) lock.wait(50L) }
        }

        private fun requireRuntime(runtime: FastSamRuntimeInfo) {
            if (runtime.modelSha256 != binding.fastSha256 || runtime.fallbackUsed || runtime.gpuPrecisionLossAllowed ||
                runtime.actualBackend.name != candidate.backend.name || runtime.numThreads != candidate.numThreads) throw Rejected()
        }
        private fun healthy(value: FastSamRuntimeService<UnknownRuntimeAttachment>): Boolean {
            val state = value.stats()
            return serviceFailure == null && !state.stopping && state.failure == null &&
                state.modelOwnerAlive && state.cpuPostOwnerAlive
        }
        private fun validLiveEnvironment(e: RuntimeCalibrationLiveEvidence): Boolean =
            e.environment.bindingHash == binding.hashOrNull() && e.environment.featureScope == binding.featureScope &&
                e.environment.cameraState == "ACTIVE" && !e.speechActive && e.cameraTimestampNs > 0 &&
                e.sourceFrameTimestampNs > 0 && abs(e.cameraTimestampNs - e.sourceFrameTimestampNs) <= 50_000_000L &&
                (binding.featureScope != RuntimeFeatureScope.CAMERA_TRACKING_METRIC_DEPTH ||
                    e.poseValid && e.depthTimestampFresh && e.positiveDepthSamples > 0 && e.imageToDepthUv != null)
        private fun enough(workMs: Long) = RuntimeSelectionPolicy.canStartCandidate(now(), requestDeadlineMs,
            workMs, UnknownRuntimeTuning.CLEANUP_RESERVE_MS)
        private fun checkCurrent() { if (!current() || requestDeadlineMs - now() <= UnknownRuntimeTuning.CLEANUP_RESERVE_MS) throw Incomplete() }
        private fun <T> await(future: CompletableFuture<T>): T {
            while (true) {
                checkCurrent()
                try { return future.get(100L, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { }
            }
        }
        private fun emit(stage: RuntimeCalibrationStage) = activity.runOnUiThread {
            if (current()) progress(RuntimeCalibrationProgress(stage, now() - startedAt,
                (requestDeadlineMs - startedAt).coerceAtLeast(1), candidate))
        }
    }

    private class FixtureAttachment : UnknownRuntimeAttachment
    private data class FixtureOutput(val raw: Array<FloatArray>, val masks: List<InstanceMask>,
        val environment: RuntimeComparisonEnvironment, val speechActive: Boolean)
    private data class FixedTiming(val caseId: String, val elapsedMs: Double,
        val environment: RuntimeComparisonEnvironment, val speechActive: Boolean)
    private class LiveAttachment(val window: LiveWindow, val evidence: RuntimeCalibrationLiveEvidence,
        val ticket: InferencePacingTicket, override val primaryResult: AndroidDetectionResult) : UnknownCalibrationAttachment
    private class LiveWindow(val environment: RuntimeComparisonEnvironment, val start: Long) {
        var duration = 0L
        var submitted = 0
        val times = mutableListOf<Double>()
        val completed = mutableListOf<Long>()
        val primaryTimes = mutableListOf<Double>()
        val primaryCompleted = mutableListOf<Long>()
        val inputs = mutableSetOf<Long>()
        val evidence = mutableListOf<RuntimeCalibrationLiveEvidence>()
        val primaryPacing = AdaptiveInferencePacingPolicy()
        val pacing = AdaptiveInferencePacingPolicy()
    }
    private data class Block(val timings: List<FixedTiming>, val live: LiveWindow)
    private class Incomplete : RuntimeException()
    private class Rejected : RuntimeException()

    private fun sameMasks(a: List<InstanceMask>, b: List<InstanceMask>): Boolean = a.size == b.size &&
        a.sortedBy { it.anchorIndex() }.zip(b.sortedBy { it.anchorIndex() }).all { (x, y) ->
            x.anchorIndex() == y.anchorIndex() && x.maskLeft() == y.maskLeft() && x.maskTop() == y.maskTop() &&
                x.maskRightExclusive() == y.maskRightExclusive() && x.maskBottomExclusive() == y.maskBottomExclusive() &&
                x.copyPackedBits().contentEquals(y.copyPackedBits())
        }
}

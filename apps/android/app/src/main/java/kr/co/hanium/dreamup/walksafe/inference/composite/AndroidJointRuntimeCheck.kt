package kr.co.hanium.dreamup.walksafe.inference.composite

import android.app.Activity
import android.media.Image
import android.os.Looper
import android.os.SystemClock
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import kr.co.hanium.dreamup.walksafe.CameraObjectPresentation
import kr.co.hanium.dreamup.walksafe.CameraScenePresentation
import kr.co.hanium.dreamup.walksafe.UprightCameraImage
import kr.co.hanium.dreamup.walksafe.depth.ObjectDepthRuntimePipeline
import kr.co.hanium.dreamup.walksafe.depth.UnknownObjectFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.depth.unknown.CapturedDepthMapper
import kr.co.hanium.dreamup.walksafe.depth.unknown.FrozenUnknownDepthCapture
import kr.co.hanium.dreamup.walksafe.depth.unknown.UnknownObjectDepthPipeline
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.InstanceMask
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.UnknownRuntimeTuning
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.UnknownRuntimeAttachment
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

data class JointRuntimeCheckResult(
    val choice: ValidatedJointRuntimeChoice?,
    val scope: RuntimeFeatureScope?,
    val reason: String,
    val releaseConfirmed: Boolean,
)

/** The terminal decision is independent of UI availability and cannot be overwritten by late work. */
internal class JointRuntimeCompletionGate(private val deadlineMs: Long, private val nowMs: () -> Long) {
    private val claimed = AtomicBoolean(false)
    fun claim(releaseConfirmed: Boolean): Boolean? =
        if (claimed.compareAndSet(false, true)) releaseConfirmed && nowMs() < deadlineMs else null
}

/**
 * One existing device check, one camera lease and independently qualified two-model candidates.
 * CPU1 fixed outputs are numerical references only: a slow CPU live run never gates a GPU run.
 * A result transfers no native owner; the caller applies/saves it only after onFinished.
 */
class AndroidJointRuntimeCheck(
    private val activity: Activity,
    private val config: TwoModelRuntimeConfig,
    private val baselinePrimary: RuntimeCandidate,
    private val deadlineMs: Long,
    private val isCurrent: () -> Boolean,
    private val speechActive: () -> Boolean,
    private val onFinished: (JointRuntimeCheckResult) -> Unit,
) {
    @Volatile var running = false
        private set
    @Volatile var draining = false
        private set
    private val started = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val completion = JointRuntimeCompletionGate(deadlineMs, ::now)
    private val preparation = Executors.newSingleThreadExecutor { Thread(it, "WalkSafeJointPrepare") }
    private val deadlineWatcher = ScheduledThreadPoolExecutor(1) { Thread(it, "WalkSafeJointDeadline") }.apply {
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        removeOnCancelPolicy = true
    }
    @Volatile private var deadlineWatch: ScheduledFuture<*>? = null
    @Volatile private var session: AndroidRuntimeCalibrationSession? = null
    @Volatile private var work: Work? = null
    @Volatile private var scope: RuntimeFeatureScope? = null
    @Volatile private var selected: ValidatedJointRuntimeChoice? = null
    @Volatile private var reason = "joint_runtime_unverified"
    // Kept on a failed native close, so a failed release cannot look like a free camera slot.
    @Volatile private var preparationSession: Session? = null

    fun start(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        val remaining = deadlineMs - now()
        if (remaining !in 1L..300_000L || !isCurrent() || cancelled.get() || activity.isFinishing ||
            activity.isDestroyed || !started.compareAndSet(false, true)) return false
        running = true
        deadlineWatch = deadlineWatcher.schedule({
            if (report(false, "deadline_release_unconfirmed")) cancel("deadline_release_unconfirmed")
        }, (deadlineMs - now()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        val refreshHz = activity.window.decorView.display?.refreshRate?.toDouble()
        preparation.execute {
            var mode: JointCameraMode? = null
            var releaseConfirmed = true
            try {
                checkCurrent()
                if (ArCoreApk.getInstance().checkAvailability(activity) != ArCoreApk.Availability.SUPPORTED_INSTALLED)
                    throw CandidateRejected("arcore_camera_unavailable")
                val camera = Session(activity.applicationContext)
                preparationSession = camera
                val depth = camera.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                scope = if (depth) RuntimeFeatureScope.CAMERA_TRACKING_METRIC_DEPTH else RuntimeFeatureScope.ARCORE_CAMERA_TRACKING
                val actual = camera.cameraConfig
                mode = JointCameraMode(actual.imageSize.width, actual.imageSize.height, actual.fpsRange.lower,
                    refreshHz ?: throw CandidateRejected("display_mode_unavailable"))
                if (!mode.isValid()) throw CandidateRejected("camera_mode_unavailable")
            } catch (error: Throwable) {
                mode = null
                reason = error.message ?: "camera_mode_unavailable"
            } finally {
                try { preparationSession?.close(); preparationSession = null }
                catch (_: Throwable) { releaseConfirmed = false; reason = "camera_probe_release_unconfirmed" }
            }
            if (!releaseConfirmed) report(false)
            else if (mode == null || !current()) report(true)
            else {
                val observed = requireNotNull(mode)
                val actualScope = requireNotNull(scope)
                val binding = runCatching { AndroidJointRuntimeChoiceStore.createBinding(activity, config, actualScope, observed) }.getOrNull()
                activity.runOnUiThread {
                    if (!current() || binding == null) {
                        reason = if (binding == null) "joint_binding_unavailable" else reason
                        report(true)
                    } else {
                        val remainingMs = deadlineMs - now()
                        if (remainingMs <= CLEANUP_RESERVE_MS) { reason = "deadline"; report(true) }
                        else {
                            session = AndroidRuntimeCalibrationSession(activity, binding, actualScope, speechActive,
                                runnerFactory = { environment, progress, finished, detection ->
                                    Work(binding, actualScope, observed, environment, progress, finished, detection).also { work = it }
                                }, onClosed = {
                                    session = null
                                    work = null
                                    report(true)
                                }, cadenceMs = 200L, budgetMs = remainingMs,
                                startRunnerBeforeCameraReady = true, fullCameraPreview = true,
                                onReleaseBlocked = { failure -> reason = failure; report(false) })
                            session!!.start()
                        }
                    }
                }
            }
            preparation.shutdown()
        }
        return true
    }

    fun cancel(reason: String) {
        this.reason = reason
        cancelled.set(true)
        draining = running
        work?.cancel(reason)
        session?.cancel(reason)
    }

    private fun report(released: Boolean, outcomeReason: String = reason): Boolean {
        // Claim outside main: a blocked GL pause/native close cannot defer the deadline decision.
        val confirmed = completion.claim(released) ?: return false
        val terminalReason = if (released && !confirmed) "deadline_release_unconfirmed" else outcomeReason
        reason = terminalReason
        draining = !confirmed
        running = !confirmed
        deadlineWatch?.cancel(false)
        deadlineWatcher.shutdown()
        val accepted = selected.takeIf { confirmed && current() }
        val actualScope = scope
        android.util.Log.i("WalkSafeJointRuntime", "finished reason=$terminalReason released=$confirmed " +
            "scope=$actualScope selected=${accepted?.candidate} mode=${accepted?.cameraMode}")
        activity.runOnUiThread {
            val delivered = accepted.takeIf { current() }
            val deliveryReason = if (accepted != null && delivered == null && now() >= deadlineMs)
                "deadline_before_callback" else terminalReason
            onFinished(JointRuntimeCheckResult(delivered, actualScope, deliveryReason, confirmed))
        }
        return true
    }

    private fun current() = !cancelled.get() && isCurrent() && now() < deadlineMs
    private fun checkCurrent() {
        if (!current() || deadlineMs - now() <= CLEANUP_RESERVE_MS) throw StopCheck()
    }

    private inner class Work(
        private val binding: String,
        private val featureScope: RuntimeFeatureScope,
        private val mode: JointCameraMode,
        private val environment: () -> RuntimeComparisonEnvironment,
        private val progress: (RuntimeCalibrationProgress) -> Unit,
        private val finished: (RuntimeCalibrationResult) -> Unit,
        private val detection: (AndroidDetectionResult, RuntimeCalibrationLiveEvidence) -> Unit,
    ) : RuntimeCalibrationWorkController {
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "WalkSafeJointCheck") }
        private val primaryLane = Executors.newSingleThreadExecutor { Thread(it, "WalkSafeJointPrimary") }
        private val lock = Object()
        private val imageBusy = AtomicBoolean(false)
        private val frameIds = AtomicLong(0L)
        private val epoch = SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)
        private val presentationEpoch = epoch.hashCode() and Int.MAX_VALUE
        private val waiters = ConcurrentHashMap<Long, CompletableFuture<FastSamResult<UnknownRuntimeAttachment>>>()
        private val liveTickets = ConcurrentHashMap<Long, Pair<LiveWindow, InferencePacingTicket>>()
        private val callbacks = mutableListOf<() -> Unit>()
        private val fixtures = RuntimeCalibrationFixtures(activity)
        private val primaryReferences = linkedMapOf<String, AndroidCalibrationDetectionResult>()
        private val auxiliaryReferences = linkedMapOf<String, AuxiliaryOutput>()
        private val choices = mutableListOf<ValidatedJointRuntimeChoice>()
        private val startMs = now()
        private var hasStarted = false
        private var drained = false
        @Volatile private var releaseFailed = false
        private var primaryReleaseFailed = false
        @Volatile private var primary: TfliteAndroidFrameDetector? = null
        @Volatile private var primaryLoad: CompletableFuture<AndroidDetectorLoadResult>? = null
        private var primaryClose: CompletableFuture<Void>? = null
        @Volatile private var auxiliary: FastSamRuntimeService<UnknownRuntimeAttachment>? = null
        @Volatile private var primaryCandidate = RuntimeCandidate(RuntimeBackend.CPU, 1)
        @Volatile private var auxiliaryCandidate = RuntimeCandidate(RuntimeBackend.CPU, 1)
        @Volatile private var actualPrimary: RuntimeCandidate? = null
        @Volatile private var actualAuxiliary: RuntimeCandidate? = null
        @Volatile private var serviceFailure: Throwable? = null
        @Volatile private var window: LiveWindow? = null
        private lateinit var cases: List<RuntimeCalibrationFixture>

        override fun start(): Boolean = synchronized(lock) {
            if (hasStarted || drained || !current()) false else {
                hasStarted = true
                worker.execute(::run)
                true
            }
        }

        override fun cancel(reason: String) {
            this@AndroidJointRuntimeCheck.reason = reason
            cancelled.set(true)
            draining = true
            val unstarted = synchronized(lock) {
                window = null
                lock.notifyAll()
                if (!hasStarted && !drained) {
                    drained = true
                    callbacks.toList().also { callbacks.clear() }
                } else null
            }
            auxiliary?.closeAsync()
            if (unstarted != null) {
                primaryLane.shutdown(); worker.shutdown()
                activity.runOnUiThread { unstarted.forEach { it() } }
            }
        }

        override fun whenDrained(callback: () -> Unit) {
            val ready = synchronized(lock) { if (drained) true else { callbacks += callback; false } }
            if (ready) activity.runOnUiThread(callback)
        }

        override fun offerLiveImage(image: Image, evidence: RuntimeCalibrationLiveEvidence, releaseImage: () -> Unit): Boolean {
            val live = synchronized(lock) {
                val value = window ?: return false
                if (!value.accepting || !current() || !qualified(image, evidence)) return false
                synchronized(value) {
                    // Retain scalar load evidence, never hundreds of full/raw depth images.
                    value.observations += LiveObservation(evidence.environment, evidence.uiFrameDelayMs,
                        evidence.cameraFrameIntervalMs)
                    value.thermal = maxOf(value.thermal, evidence.environment.thermalStatus)
                }
                if (!imageBusy.compareAndSet(false, true)) return false
                value
            }
            val primaryTicket = live.primaryPacing.tryStart(evidence.capturedAtElapsedRealtimeMs)
            if (primaryTicket == null) { imageBusy.set(false); return false }
            synchronized(live) { live.primarySubmitted++ }
            try {
                primaryLane.execute {
                    try {
                        if (!current() || window !== live) return@execute
                        val turns = UprightCameraImage.quarterTurns(requireNotNull(evidence.viewBasis))
                            ?: throw CandidateRejected("view_orientation_unavailable")
                        val geometryId = "joint-$epoch-${mode.width}x${mode.height}-$turns"
                        val token = FastSamFrameToken(FastSamFrameToken.Source.LIVE_CAMERA, epoch,
                            evidence.sourceFrameTimestampNs, evidence.sourceFrameTimestampNs, image.timestamp,
                            evidence.capturedAtElapsedRealtimeMs * 1_000_000L, geometryId, image.width, image.height)
                        val output = requireNotNull(primary).detectOriented(image, evidence.sourceFrameTimestampNs / 1_000_000L, turns)
                        requirePrimary(output)
                        val capture = FrozenUnknownDepthCapture.freeze(token, requireNotNull(evidence.depthSnapshot),
                            evidence.imageToDepthUv, requireNotNull(evidence.mapperFrameId), imageQuarterTurns = turns)
                            ?: throw CandidateRejected("capture_freeze_failed")
                        submitAuxiliary(live, image, evidence, token, capture)
                        val primaryOutputs = capture.calibration?.let { calibration ->
                            live.primaryDepth.process(requireNotNull(evidence.depthSnapshot), evidence.sourceFrameTimestampNs,
                                evidence.sourceFrameTimestampNs / 1_000_000L, output.detections,
                                detectionCompleted = !output.partial, mapper = CapturedDepthMapper(calibration),
                                mapperFrameId = evidence.sourceFrameTimestampNs, imageQuarterTurns = turns,
                                capturedAtElapsedRealtimeMs = evidence.capturedAtElapsedRealtimeMs)
                        } ?: emptyList()
                        val presentationCapture = CameraObjectPresentation.Capture(presentationEpoch,
                            token.frameId, evidence.capturedAtElapsedRealtimeMs, geometryId)
                        val publication = CompletableFuture<Boolean>()
                        activity.runOnUiThread {
                            try {
                                val accepted = if (current() && window === live) {
                                    detection(output, evidence)
                                    session?.publishJointPrimary(presentationCapture, output, evidence, primaryOutputs) == true
                                } else false
                                publication.complete(accepted)
                            } catch (error: Throwable) { publication.completeExceptionally(error) }
                        }
                        if (!await(publication)) throw CandidateRejected("primary_ui_publication_rejected")
                        val completed = now()
                        live.primaryPacing.complete(primaryTicket, completed, output.timing.modelInferenceMs,
                            evidence.environment.thermalStatus >= 3)
                        synchronized(live) {
                            live.primaryAges += (completed - evidence.capturedAtElapsedRealtimeMs).coerceAtLeast(1L).toDouble()
                            live.primaryCompleted += completed
                        }
                    } catch (error: StopCheck) { /* Shared cancellation/deadline owns cleanup. */ }
                    catch (error: Throwable) { synchronized(live) { live.failure = error }; synchronized(lock) { lock.notifyAll() } }
                    finally {
                        live.primaryPacing.cancel(primaryTicket)
                        try { releaseImage() } catch (_: Throwable) { releaseFailed = true; cancel("image_release_unconfirmed") }
                        imageBusy.set(false)
                        synchronized(lock) { lock.notifyAll() }
                    }
                }
            } catch (_: RejectedExecutionException) { live.primaryPacing.cancel(primaryTicket); imageBusy.set(false); return false }
            return true
        }

        private fun submitAuxiliary(live: LiveWindow, image: Image, evidence: RuntimeCalibrationLiveEvidence,
            token: FastSamFrameToken, capture: FrozenUnknownDepthCapture) {
            if (!current() || window !== live) return
            val ticket = live.auxiliaryPacing.tryStart(evidence.capturedAtElapsedRealtimeMs) ?: return
            try {
                liveTickets[token.frameId] = live to ticket
                val admission = requireNotNull(auxiliary).submit(image, token, LiveAttachment(live, evidence, capture, ticket))
                if (admission == FastSamRuntimeService.Admission.ACCEPTED || admission == FastSamRuntimeService.Admission.REPLACED_PENDING)
                    synchronized(live) { live.submitted++ }
                else { liveTickets.remove(token.frameId); live.auxiliaryPacing.cancel(ticket) }
            } catch (error: Throwable) {
                liveTickets.remove(token.frameId); live.auxiliaryPacing.cancel(ticket)
                throw error
            }
        }

        private fun run() {
            try {
                emit(RuntimeCalibrationStage.OUTPUT_CHECK)
                val manifest = fixtures.loadManifest()
                cases = manifest.fixtures.filter { it.required }
                check(manifest.version == RuntimeCalibrationFixtures.VERSION &&
                    manifest.sha256 == RuntimeCalibrationFixtures.MANIFEST_SHA256 && cases.size == 4)
                check(config.primaryModelKey == TwoModelRuntimeConfig.UNIFIED_MODEL_KEY &&
                    requireNotNull(config.unifiedWalksafe).enabled)
                // These untimed references do not need to meet any live latency budget.
                openPrimary(RuntimeCandidate(RuntimeBackend.CPU, 1))
                primaryOutput(cases.first(), reference = true) // Consistent one-call native warmup.
                cases.forEach { primaryReferences[it.id] = primaryOutput(it, reference = true) }
                closePrimary()
                openAuxiliary(RuntimeCandidate(RuntimeBackend.CPU, 1))
                auxiliaryOutput(cases.first(), reference = true)
                cases.forEach { auxiliaryReferences[it.id] = auxiliaryOutput(it, reference = true) }
                closeAuxiliary()
                for (candidate in candidates()) {
                    checkCurrent()
                    if (deadlineMs - now() < CLEANUP_RESERVE_MS + MINIMUM_CANDIDATE_REMAINING_MS) break
                    try {
                        openPrimary(candidate.primary)
                        openAuxiliary(candidate.auxiliary)
                        // Every backend is warmed and checked against the same four strict outputs.
                        emit(RuntimeCalibrationStage.OUTPUT_CHECK)
                        primaryOutput(cases.first())
                        auxiliaryOutput(cases.first())
                        cases.forEach { primaryOutput(it); auxiliaryOutput(it) }
                        // The service exposes raw tensors only for fixture tokens. This exact
                        // output-verified native owner therefore continues live without raw copies.
                        val evidence = observe(candidate)
                        val choice = JointRuntimeChoicePolicy.evaluate(binding, candidate, featureScope, evidence,
                            Runtime.getRuntime().availableProcessors())
                        if (choice != null) choices += choice
                        logEvidence(candidate, evidence, choice != null)
                    } catch (error: StopCheck) { throw error }
                    catch (error: Throwable) {
                        if (releaseFailed || error is DetectorNativeCleanupFailure) throw error
                        android.util.Log.i("WalkSafeJointRuntime", "candidate=$candidate scope=$featureScope mode=$mode " +
                            "rejected=${error.javaClass.simpleName} reason=${error.message}")
                    } finally {
                        drainAndCloseOwners()
                    }
                }
                checkCurrent()
                emit(RuntimeCalibrationStage.SELECTING)
                selected = JointRuntimeChoicePolicy.select(choices, Runtime.getRuntime().availableProcessors())
                reason = if (selected == null) "no_usable_joint_candidate" else "joint_candidate_verified"
            } catch (_: StopCheck) {
                // Only a fully completed earlier window can survive exhaustion of the work budget.
                selected = if (!cancelled.get() && isCurrent() && now() < deadlineMs)
                    JointRuntimeChoicePolicy.select(choices, Runtime.getRuntime().availableProcessors()) else null
                if (!cancelled.get()) reason = if (selected != null) "joint_candidate_verified_budget_end" else "deadline_or_obsolete"
            } catch (error: Throwable) {
                selected = null
                reason = if (releaseFailed) "native_release_unconfirmed" else "joint_reference_or_execution_unverified"
                android.util.Log.w("WalkSafeJointRuntime", reason, error)
            } finally {
                draining = true
                try { drainAndCloseOwners() }
                catch (_: Throwable) { releaseFailed = true }
                primaryLane.shutdown()
                worker.shutdown()
                if (releaseFailed) {
                    selected = null
                    reason = "native_or_image_release_unconfirmed"
                    session?.cancel(reason)
                    report(false)
                } else {
                    primaryReferences.clear(); auxiliaryReferences.clear(); waiters.clear()
                    val notify = synchronized(lock) { drained = true; callbacks.toList().also { callbacks.clear() } }
                    activity.runOnUiThread {
                        finished(RuntimeCalibrationResult(if (selected == null) RuntimeSelectionStatus.PARTIAL else RuntimeSelectionStatus.CONFIRMED,
                            reason, null, null, false, selected == null, null))
                        notify.forEach { it() }
                    }
                }
            }
        }

        private fun candidates(): List<JointRuntimeCandidate> {
            val threads = minOf(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)
            val baseline = baselinePrimary.copy(numThreads = baselinePrimary.numThreads.coerceIn(1, threads))
            val primaryPool = listOf(baseline,
                RuntimeCandidate(if (baseline.backend == RuntimeBackend.CPU) RuntimeBackend.GPU else RuntimeBackend.CPU, threads))
            val auxiliaryPool = listOf(RuntimeCandidate(RuntimeBackend.GPU, threads), RuntimeCandidate(RuntimeBackend.CPU, threads))
            return buildList {
                primaryPool.forEach { p -> auxiliaryPool.forEach { a -> add(JointRuntimeCandidate(p, a)) } }
                if (threads >= 2) {
                    val cpu2 = RuntimeCandidate(RuntimeBackend.CPU, 2)
                    add(JointRuntimeCandidate(cpu2, auxiliaryPool.first(), 250L, 500L))
                    add(JointRuntimeCandidate(primaryPool.first(), cpu2, 250L, 500L))
                }
            }.distinct()
        }

        private fun openPrimary(candidate: RuntimeCandidate) {
            checkCurrent()
            check(primary == null)
            primaryCandidate = candidate
            actualPrimary = null
            primaryClose = null
            emit(RuntimeCalibrationStage.PREPARING)
            val load = CompletableFuture.supplyAsync({
                TfliteAndroidFrameDetector.createWithStatus(activity.applicationContext,
                    config.copy(fallbackModelKey = null, customTactile = null, cocoGeneral = null),
                    ModelRuntimeOptions(candidate.backend.delegate, candidate.numThreads, fallbackToCpu = false,
                        gpuPrecisionLossAllowed = false), YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR)
                    .also { primary = it.detector }
            }, primaryLane)
            primaryLoad = load
            val loaded = await(load)
            if (loaded.reason == "DetectorNativeCleanupFailure") {
                releaseFailed = true
                throw CandidateRejected("primary_native_cleanup_failed")
            }
            if (!loaded.detectorAvailable || loaded.detector == null || loaded.fallbackUsed)
                throw CandidateRejected("primary_backend_unavailable")
        }

        private fun requirePrimary(result: AndroidDetectionResult) {
            val runtime = result.timing.modelRuntime ?: throw CandidateRejected("primary_runtime_missing")
            if (!result.inferenceCompleted || result.partial || result.timing.modelKey != TwoModelRuntimeConfig.UNIFIED_MODEL_KEY ||
                result.timing.completedModels != listOf(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY) || runtime.fallbackUsed ||
                runtime.activeDelegate != primaryCandidate.backend.delegate || runtime.requestedDelegate != primaryCandidate.backend.delegate ||
                runtime.numThreads != primaryCandidate.numThreads || runtime.gpuPrecisionLossAllowed == true)
                throw CandidateRejected("primary_runtime_mismatch")
            actualPrimary = RuntimeCandidate(RuntimeBackend.valueOf(runtime.activeDelegate.uppercase()), runtime.numThreads)
        }

        private fun primaryOutput(fixture: RuntimeCalibrationFixture, reference: Boolean = false): AndroidCalibrationDetectionResult {
            checkCurrent()
            val output = await(CompletableFuture.supplyAsync({
                val prepared = YuvImagePreprocessor().preprocessBilinear(fixtures.loadArgb(fixture), requireNotNull(config.unifiedWalksafe).inputSize)
                requireNotNull(primary).inferPreparedUnifiedForCalibration(prepared, 0L, YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR)
            }, primaryLane))
            requirePrimary(output.result)
            val expected = if (reference) output else requireNotNull(primaryReferences[fixture.id])
            if (!RuntimeCalibrationOutputGate.compare(expected.rawOutput, output.rawOutput,
                    expected.result.detections, output.result.detections, requireNotNull(config.unifiedWalksafe)) ||
                !RuntimeCalibrationOutputGate.matchesFixtureRoles(expected.result.detections, output.result.detections,
                    RuntimeCalibrationFixtureRole.POSITIVE in fixture.roles, RuntimeCalibrationFixtureRole.EMPTY in fixture.roles,
                    fixture.groundTruth.filter { !it.isCrowd }.map { it.className to it.bboxNorm }))
                throw CandidateRejected("primary_fixture_mismatch")
            return output
        }

        private fun closePrimary() {
            if (primaryReleaseFailed) throw CandidateRejected("primary_release_unconfirmed")
            if (primaryLoad == null && primary == null && primaryClose == null) return
            // Enqueue the close before waiting. A late native load/invoke still reaches this serial
            // barrier even after a deadline report; shutdown permits this queued cleanup to run.
            val barrier = primaryClose ?: CompletableFuture.runAsync({
                val loaded = primaryLoad?.join()
                if (loaded?.reason == "DetectorNativeCleanupFailure")
                    throw CandidateRejected("primary_native_cleanup_failed")
                primary?.close()
                primary = null
                primaryLoad = null
            }, primaryLane).also { primaryClose = it }
            try {
                awaitRelease(barrier)
                primaryClose = null
            } catch (error: Throwable) { releaseFailed = true; primaryReleaseFailed = true; throw error }
        }

        private fun openAuxiliary(candidate: RuntimeCandidate) {
            checkCurrent()
            check(auxiliary == null)
            auxiliaryCandidate = candidate
            actualAuxiliary = null
            serviceFailure = null
            val value = FastSamRuntimeService(activity, epoch,
                FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.valueOf(candidate.backend.name), candidate.numThreads,
                    false, deadlineMs * 1_000_000L, 800L, true, false, true,
                    (deadlineMs - CLEANUP_RESERVE_MS) * 1_000_000L),
                object : FastSamRuntimeService.Listener<UnknownRuntimeAttachment> {
                    override fun onResult(result: FastSamResult<UnknownRuntimeAttachment>) {
                        when (val attachment = result.attachment) {
                            is FixtureAttachment -> waiters.remove(result.token.frameId)?.complete(result)
                            is LiveAttachment -> receiveLive(result, attachment)
                        }
                    }
                    override fun onError(error: Throwable) {
                        serviceFailure = error
                        waiters.values.forEach { it.completeExceptionally(error) }
                        synchronized(lock) { lock.notifyAll() }
                    }
                    override fun onDiscard(token: FastSamFrameToken, reason: String) {
                        waiters.remove(token.frameId)?.completeExceptionally(CandidateRejected(reason))
                        liveTickets.remove(token.frameId)?.let { (live, ticket) -> live.auxiliaryPacing.cancel(ticket) }
                        synchronized(lock) { lock.notifyAll() }
                    }
                })
            auxiliary = value
            requireAuxiliary(await(value.start()))
        }

        private fun requireAuxiliary(runtime: FastSamRuntimeInfo) {
            if (runtime.modelSha256 != FastSamModelContract.SHA256 || runtime.fallbackUsed || runtime.gpuPrecisionLossAllowed ||
                runtime.requestedBackend.name != auxiliaryCandidate.backend.name || runtime.actualBackend.name != auxiliaryCandidate.backend.name ||
                runtime.numThreads != auxiliaryCandidate.numThreads) throw CandidateRejected("auxiliary_runtime_mismatch")
            actualAuxiliary = RuntimeCandidate(RuntimeBackend.valueOf(runtime.actualBackend.name), runtime.numThreads)
        }

        private fun auxiliaryOutput(fixture: RuntimeCalibrationFixture, reference: Boolean = false): AuxiliaryOutput {
            val result = auxiliaryResult(fixture)
            val raw = result.rawOutputs ?: throw CandidateRejected("auxiliary_raw_missing")
            fun copy(buffer: java.nio.FloatBuffer) = FloatArray(buffer.remaining()).also { buffer.get(it) }
            val output = AuxiliaryOutput(arrayOf(copy(raw.detections()), copy(raw.prototypes())), result.masks)
            val expected = if (reference) output else requireNotNull(auxiliaryReferences[fixture.id])
            if (!UnknownRuntimeTuning.equivalentRaw(expected.raw, output.raw) || !sameMasks(expected.masks, output.masks))
                throw CandidateRejected("auxiliary_fixture_mismatch")
            return output
        }

        private fun auxiliaryResult(fixture: RuntimeCalibrationFixture): FastSamResult<UnknownRuntimeAttachment> {
            checkCurrent()
            val image = fixtures.loadArgb(fixture)
            val id = frameIds.incrementAndGet()
            val future = CompletableFuture<FastSamResult<UnknownRuntimeAttachment>>()
            waiters[id] = future
            val token = FastSamFrameToken(FastSamFrameToken.Source.CALIBRATION_FIXTURE, epoch, id, 0L, 0L,
                SystemClock.elapsedRealtimeNanos(), "fixture-${fixture.id}", image.width, image.height)
            val admission = requireNotNull(auxiliary).submitArgb(image.width, image.height, image.pixels, token, FixtureAttachment())
            if (admission != FastSamRuntimeService.Admission.ACCEPTED) { waiters.remove(id); throw CandidateRejected("fixture_admission") }
            val result = await(future)
            requireAuxiliary(result.runtime)
            return result
        }

        private fun closeAuxiliary() {
            val previous = auxiliary ?: return
            previous.closeAsync()
            try { awaitRelease(previous.releasedFuture()); auxiliary = null }
            catch (error: Throwable) { releaseFailed = true; throw error }
            waiters.clear()
        }

        private fun closeOwners() {
            var failure: Throwable? = null
            try { closeAuxiliary() } catch (error: Throwable) { failure = error }
            try { closePrimary() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
            failure?.let { throw it }
        }

        private fun drainAndCloseOwners() {
            var failure: Throwable? = null
            try { retireWindow() } catch (error: Throwable) { failure = error }
            // A borrowed image timeout must still request both asynchronous native closes.
            try { closeOwners() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
            failure?.let { releaseFailed = true; throw it }
        }

        private fun observe(candidate: JointRuntimeCandidate): JointRuntimeEvidence {
            val cameraDeadline = minOf(deadlineMs - CLEANUP_RESERVE_MS - 10_000L, now() + 30_000L)
            while (now() < cameraDeadline && !cameraReady()) { checkCurrent(); synchronized(lock) { lock.wait(50L) } }
            if (!cameraReady()) throw CandidateRejected("qualified_camera_unavailable")
            val boundary = CompletableFuture<Unit>()
            activity.runOnUiThread {
                session?.resetJointPresentation()
                progress(RuntimeCalibrationProgress(RuntimeCalibrationStage.LIVE_CANDIDATE,
                    now() - startMs, deadlineMs - startMs, candidate.primary))
                boundary.complete(Unit)
            }
            await(boundary)
            val live = LiveWindow(candidate, now(), environment())
            synchronized(lock) { checkCurrent(); window = live }
            try {
                val until = minOf(deadlineMs - CLEANUP_RESERVE_MS, live.startMs + 25_000L)
                while (now() < until) {
                    checkCurrent()
                    if (!modeMatches()) throw CandidateRejected("camera_mode_changed")
                    serviceFailure?.let { throw CandidateRejected("auxiliary_live_failed") }
                    val complete = synchronized(live) {
                        live.failure?.let { throw it }
                        now() - live.startMs >= 10_000L &&
                            ((live.primaryAges.size >= 10 && live.auxiliaryAges.size >= 10) ||
                                live.primaryAges.any { it > 800.0 })
                    }
                    if (complete) break
                    synchronized(lock) { lock.wait(50L) }
                }
            } finally { retireWindow() }
            val duration = now() - live.startMs
            synchronized(live) {
                val fresh = live.auxiliaryAges.count { it <= 800.0 }
                return JointRuntimeEvidence(duration, live.submitted, live.primaryAges.toList(), live.auxiliaryAges.toList(),
                    fresh, live.auxiliaryAges.size - fresh, longestGap(live.primaryCompleted, live.startMs, duration),
                    longestGap(live.auxiliaryCompleted, live.startMs, duration), live.observations.map { it.uiFrameDelayMs },
                    1000.0 / mode.displayRefreshHz, live.observations.map { it.cameraFrameIntervalMs }, 1000.0 / mode.fps,
                    fixtureEquivalent = true, sourceQualified = live.observations.isNotEmpty() && live.observations.all {
                        it.environment == live.environment
                    }, fallbackUsed = false, thermalStatus = maxOf(live.thermal, environment().thermalStatus),
                    cameraWidth = mode.width, cameraHeight = mode.height,
                    cameraFps = mode.fps, displayRefreshHz = mode.displayRefreshHz, primarySubmittedFrames = live.primarySubmitted,
                    actualPrimary = requireNotNull(actualPrimary), actualAuxiliary = requireNotNull(actualAuxiliary))
            }
        }

        private fun cameraReady(): Boolean {
            val env = environment()
            return modeMatches() && env.cameraState == "ACTIVE" && env.arCoreState == "TRACKING" && !speechActive()
        }

        private fun modeMatches(): Boolean {
            val actual = session?.cameraModeSnapshot() ?: return false
            val actualRefreshHz = activity.window.decorView.display?.refreshRate?.toDouble() ?: return false
            return actual.width == mode.width && actual.height == mode.height && actual.fps == mode.fps &&
                actualRefreshHz == mode.displayRefreshHz
        }

        private fun qualified(image: Image, evidence: RuntimeCalibrationLiveEvidence): Boolean {
            if (!modeMatches() || image.width != mode.width || image.height != mode.height) {
                window?.let { synchronized(it) { it.failure = CandidateRejected("camera_mode_changed") } }
                return false
            }
            return cameraReady() && image.timestamp == evidence.cameraTimestampNs && validSource(evidence)
        }

        private fun validSource(e: RuntimeCalibrationLiveEvidence): Boolean =
            e.environment.bindingHash == binding && e.environment.featureScope == featureScope &&
                e.environment.cameraState == "ACTIVE" && e.environment.arCoreState == "TRACKING" && e.poseValid &&
                !e.speechActive && !speechActive() && e.cameraTimestampNs > 0L && e.sourceFrameTimestampNs > 0L &&
                e.viewBasis?.let { it.size == 10 && it.all(Float::isFinite) && UprightCameraImage.quarterTurns(it) != null } == true &&
                abs(e.cameraTimestampNs - e.sourceFrameTimestampNs) <= 50_000_000L && e.mapperFrameId == e.sourceFrameTimestampNs &&
                e.depthSnapshot?.cameraImageTimestampNs == e.cameraTimestampNs &&
                (featureScope != RuntimeFeatureScope.CAMERA_TRACKING_METRIC_DEPTH ||
                    (e.depthTimestampFresh && e.positiveDepthSamples > 0 && e.depthSnapshot?.hasFreshMetricRawDepth == true && e.imageToDepthUv != null))

        private fun receiveLive(result: FastSamResult<UnknownRuntimeAttachment>, attachment: LiveAttachment) {
            val live = attachment.window
            try {
                if (!current() || window !== live) return
                requireAuxiliary(result.runtime)
                // The same production mask/depth/tracking consumer runs before stopping the clock.
                val depth = live.depth.process(attachment.capture, result.token, result.masks, now())
                if (depth.rejectionReason != null && depth.rejectionReason != "source_frame_expired")
                    throw CandidateRejected("depth_consumer_${depth.rejectionReason}")
                val processed = depth.forDisplayAt(now())
                val outputs = processed.objects + processed.proximityObjects
                val feedbackEpoch = WalkRuntimeEpoch("joint-calibration", epoch)
                val admitted = live.feedback.admit(outputs, result.token.frameId,
                    result.token.cameraTimestampNs / 1_000_000L, result.token.capturedElapsedNs, now(),
                    feedbackEpoch, feedbackEpoch.takeIf { current() && window === live }, now())
                val candidates = processed.observations.filter { it.walkingSelection?.show == true }
                val capture = CameraObjectPresentation.Capture(presentationEpoch, result.token.frameId,
                    attachment.evidence.capturedAtElapsedRealtimeMs, result.token.geometryId)
                val prepared = CameraScenePresentation.prepareUnknown(capture, candidates, outputs,
                    admitted?.outputs ?: emptyList(), requireNotNull(attachment.evidence.viewBasis))
                val publication = CompletableFuture<Boolean>()
                activity.runOnUiThread {
                    try {
                        publication.complete(current() && window === live &&
                            session?.publishJointUnknown(prepared, result.masks.size, candidates.size, result.inferenceMs) == true)
                    } catch (error: Throwable) { publication.completeExceptionally(error) }
                }
                if (!await(publication)) throw CandidateRejected("auxiliary_ui_publication_rejected")
                val completed = now()
                live.auxiliaryPacing.complete(attachment.ticket, completed, result.inferenceMs.toLong(),
                    attachment.evidence.environment.thermalStatus >= 3)
                synchronized(live) {
                    live.auxiliaryAges += (completed - attachment.evidence.capturedAtElapsedRealtimeMs).coerceAtLeast(1L).toDouble()
                    live.auxiliaryCompleted += completed
                }
            } catch (error: Throwable) { synchronized(live) { live.failure = error } }
            finally {
                liveTickets.remove(result.token.frameId)
                live.auxiliaryPacing.cancel(attachment.ticket)
                synchronized(lock) { lock.notifyAll() }
            }
        }

        private fun retireWindow() {
            val retiring = synchronized(lock) {
                window?.also { it.accepting = false }.also {
                    while (imageBusy.get()) waitForRelease(50L)
                }
            }
            // Close is the model/post-owner barrier. Pending copies never cross into a new candidate.
            auxiliary?.discardPending("joint_candidate_end")
            while (auxiliary?.stats()?.inflight == true || auxiliary?.stats()?.pending == true)
                synchronized(lock) { waitForRelease(20L) }
            synchronized(lock) { if (window === retiring) window = null }
        }

        /** Cleanup owns only the shared reserve; a timeout retains the owner/fence and reports false. */
        private fun <T> awaitRelease(future: CompletableFuture<T>): T {
            if (future.isDone) return future.get()
            val remaining = deadlineMs - now()
            if (remaining <= 0L) throw TimeoutException("shared native release deadline")
            return future.get(remaining, TimeUnit.MILLISECONDS)
        }

        private fun waitForRelease(maximumMs: Long) {
            val remaining = deadlineMs - now()
            if (remaining <= 0L) throw TimeoutException("shared image/post release deadline")
            lock.wait(minOf(maximumMs, remaining))
        }

        private fun <T> await(future: CompletableFuture<T>): T {
            while (true) {
                checkCurrent()
                try { return future.get(100L, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { }
            }
        }
        private fun logEvidence(candidate: JointRuntimeCandidate, e: JointRuntimeEvidence, accepted: Boolean) {
            fun p95(samples: List<Double>) = if (samples.isEmpty()) "none" else JointRuntimeChoicePolicy.percentile95(samples).toLong().toString()
            android.util.Log.i("WalkSafeJointRuntime", "candidate=$candidate scope=$featureScope mode=$mode " +
                "accepted=$accepted reason=${JointRuntimeChoicePolicy.rejectionReason(candidate, featureScope, e, Runtime.getRuntime().availableProcessors())} " +
                "durationMs=${e.durationMs} primary=${e.primaryAgesMs.size}/${e.primarySubmittedFrames} " +
                "aux=${e.auxiliaryAgesMs.size}/${e.submittedFrames} fresh=${e.freshAuxiliaryFrames} stale=${e.staleAuxiliaryFrames} " +
                "primaryP95Ms=${p95(e.primaryAgesMs)} auxP95Ms=${p95(e.auxiliaryAgesMs)} " +
                "primaryGapMs=${e.primaryLongestGapMs.toLong()} auxGapMs=${e.auxiliaryLongestGapMs.toLong()} " +
                "uiP95Ms=${p95(e.uiDelayMs)} cameraP95Ms=${p95(e.cameraIntervalMs)} thermal=${e.thermalStatus}")
        }
        private fun emit(stage: RuntimeCalibrationStage) = activity.runOnUiThread {
            if (current()) progress(RuntimeCalibrationProgress(stage, now() - startMs, deadlineMs - startMs, primaryCandidate))
        }
    }

    private class FixtureAttachment : UnknownRuntimeAttachment
    private data class AuxiliaryOutput(val raw: Array<FloatArray>, val masks: List<InstanceMask>)
    private class LiveAttachment(val window: LiveWindow, val evidence: RuntimeCalibrationLiveEvidence,
        val capture: FrozenUnknownDepthCapture, val ticket: InferencePacingTicket) : UnknownRuntimeAttachment
    private data class LiveObservation(val environment: RuntimeComparisonEnvironment, val uiFrameDelayMs: Double,
        val cameraFrameIntervalMs: Double)
    private class LiveWindow(val candidate: JointRuntimeCandidate, val startMs: Long, val environment: RuntimeComparisonEnvironment) {
        @Volatile var accepting = true
        val depth = UnknownObjectDepthPipeline(::now, maximumSourceAgeMs = 800L)
        val primaryDepth = ObjectDepthRuntimePipeline(rawDepthMotionOnly = true)
        val feedback = UnknownObjectFeedbackPolicy()
        val primaryPacing = AdaptiveInferencePacingPolicy().apply { configureMinimumInterval(candidate.primaryIntervalMs) }
        val auxiliaryPacing = AdaptiveInferencePacingPolicy().apply { configureMinimumInterval(candidate.auxiliaryIntervalMs) }
        var primarySubmitted = 0
        var submitted = 0
        var thermal = environment.thermalStatus
        var failure: Throwable? = null
        val primaryAges = mutableListOf<Double>()
        val auxiliaryAges = mutableListOf<Double>()
        val primaryCompleted = mutableListOf<Long>()
        val auxiliaryCompleted = mutableListOf<Long>()
        val observations = mutableListOf<LiveObservation>()
    }
    private class StopCheck : RuntimeException()
    private class CandidateRejected(reason: String) : RuntimeException(reason)

    companion object {
        private const val CLEANUP_RESERVE_MS = 20_000L
        private const val MINIMUM_CANDIDATE_REMAINING_MS = 25_000L
        private fun now() = SystemClock.elapsedRealtime()
        private fun longestGap(values: List<Long>, start: Long, duration: Long): Double =
            (listOf(start) + values + (start + duration)).zipWithNext { a, b -> (b - a).toDouble() }.maxOrNull() ?: duration.toDouble()
        private fun sameMasks(a: List<InstanceMask>, b: List<InstanceMask>): Boolean = a.size == b.size &&
            a.sortedBy { it.anchorIndex() }.zip(b.sortedBy { it.anchorIndex() }).all { (x, y) ->
                x.anchorIndex() == y.anchorIndex() && x.maskLeft() == y.maskLeft() && x.maskTop() == y.maskTop() &&
                    x.maskRightExclusive() == y.maskRightExclusive() && x.maskBottomExclusive() == y.maskBottomExclusive() &&
                    x.copyPackedBits().contentEquals(y.copyPackedBits())
            }
    }
}

package kr.co.hanium.dreamup.walksafe.inference

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.media.Image
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Debug
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kr.co.hanium.dreamup.walksafe.FrozenImageToTextureCoordinateMapper
import kr.co.hanium.dreamup.walksafe.depth.ArCoreFrameProvider
import kr.co.hanium.dreamup.walksafe.depth.CoordinateMapper
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.ImageSize
import kr.co.hanium.dreamup.walksafe.depth.ObjectDepthRuntimePipeline
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.device.AndroidWalkSessionResourceProbe
import kr.co.hanium.dreamup.walksafe.navigation.FrozenImageToDepthTransform
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit opt-in, production component benchmark. Never launches an Activity, grants permission,
 * installs ARCore, emits speech, or writes camera images, coordinates, account or location data.
 * ARCore update and EGL stay on the test thread; each detector's create/invoke/close stays on its
 * single worker. The wall-clock observation is bounded, but a stalled native call cannot safely be
 * forcibly interrupted. A drain timeout reports incomplete cleanup and leaves owned resources live.
 * Component throughput and same-input output agreement do not establish accuracy or walking safety.
 */
@RunWith(AndroidJUnit4::class)
class HeterogeneousRuntimeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val runId = UUID.randomUUID().toString()
    private var sequence = 0L

    @Test
    fun observeRuntimeWithRealCameraAndDepth() {
        assumeTrue("Requires -e allowHeterogeneousRuntimeTest true",
            arguments.getString("allowHeterogeneousRuntimeTest") == "true")
        observe(sameInputComparison = false)
    }

    @Test
    fun compareSameCameraInputAcrossRuntimes() {
        assumeTrue("Requires -e allowHeterogeneousSameInputTest true",
            arguments.getString("allowHeterogeneousSameInputTest") == "true")
        observe(sameInputComparison = true)
    }

    private fun observe(sameInputComparison: Boolean) {
        val options = Options.parse(arguments)
        val context = instrumentation.targetContext
        val process = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
        emit("setup", JSONObject().put("options", options.json())
            .put("purpose", if (sameInputComparison) "SAME_INPUT_AGREEMENT" else "COMPONENT_BENCHMARK")
            .put("processImportance", process.importance).put("activityLaunched", false)
            .put("deviceModel", Build.MODEL).put("deviceManufacturer", Build.MANUFACTURER).put("androidSdk", Build.VERSION.SDK_INT)
            .put("imagesSaved", false).put("modelAccuracyValidated", false))
        require(!sameInputComparison || options.scheduling == "sequential") { "Same-input comparison requires sequential scheduling" }
        if (options.scheduling == "overlap") {
            observeOverlap(options)
            return
        }
        val lanes = if (options.scheduling == "dual") listOf(
            InferenceLane("cpu", ModelRuntimeOptions(delegate = "cpu", numThreads = options.dualCpuThreads)),
            InferenceLane("gpu", options.runtime().copy(delegate = "gpu")),
        ) else listOf(InferenceLane("single", options.runtime()))
        val resources = AndroidWalkSessionResourceProbe(context)
        val retainedImages = AtomicInteger()
        val maxRetainedImages = AtomicInteger()
        val latestFrameNs = AtomicLong()
        val latestCameraNs = AtomicLong()
        val completionGate = HeterogeneousResultGate()
        val errors = AtomicInteger()
        val sourceMismatches = AtomicInteger()
        val measuredInferences = AtomicInteger()
        val measuredComparisons = AtomicInteger()
        val positiveComparisons = AtomicInteger()
        val failedComparisons = AtomicInteger()
        val rawFailedComparisons = AtomicInteger()
        val rawAvailableComparisons = AtomicInteger()
        val cleanupFailures = linkedMapOf<String, String>()
        val pipeline = ObjectDepthRuntimePipeline()
        var comparisonConfig: ModelRuntimeConfig? = null
        var referenceConfig: ModelRuntimeConfig? = null
        var session: Session? = null
        var provider: ArCoreFrameProvider? = null
        var egl: OffscreenEgl? = null
        var resumeAttempted = false
        var stage = "camera_permission"
        var outcome = "BLOCKED"
        var measurementElapsedMs = 0L
        try {
            check(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                "Preexisting camera permission is required"
            }
            stage = "detector_load"
            val productionConfig = TwoModelRuntimeConfig.load(context)
            referenceConfig = productionConfig.unifiedWalksafe
            val selectedConfig = HeterogeneousModelCandidates.select(productionConfig, options.model)
            comparisonConfig = selectedConfig.unifiedWalksafe
            val selectedContext = contextForModel(options.model)
            lanes.forEach { lane ->
                val loaded = lane.worker.submit<AndroidDetectorLoadResult> {
                    if (sameInputComparison) {
                        lane.reference = TfliteAndroidFrameDetector.createWithStatus(
                            context, productionConfig, ModelRuntimeOptions(delegate = "cpu", numThreads = 4),
                            YuvPreprocessingStrategy.LEGACY_TWO_PASS,
                        ).detector
                        check(lane.reference != null) { "Reference detector unavailable" }
                    }
                    TfliteAndroidFrameDetector.createWithStatus(
                        selectedContext, selectedConfig, lane.runtime, options.preprocessing,
                    ).also { lane.detector = it.detector }
                }.get(30L, TimeUnit.SECONDS)
                emit("runtime", JSONObject().put("laneId", lane.id)
                    .put("modelKey", loaded.modelKey ?: JSONObject.NULL).put("modelCandidate", options.model)
                    .put("available", loaded.detectorAvailable).put("modelFallbackUsed", loaded.fallbackUsed)
                    .put("loadReason", loaded.reason).put("requestedDelegate", lane.runtime.delegate)
                    .put("numThreads", lane.runtime.numThreads)
                    .put("configuredUnifiedAssetSha256", comparisonConfig?.artifactSha256 ?: JSONObject.NULL)
                    .put("configuredUnifiedInputSize", comparisonConfig?.inputSize ?: JSONObject.NULL)
                    .put("gpuPrecisionLossAllowed", lane.runtime.gpuPrecisionLossAllowed))
                check(loaded.detectorAvailable) { "Detector unavailable for ${lane.id}" }
            }
            stage = "session_setup"
            val activeSession = Session(context).also { session = it }
            val activeProvider = ArCoreFrameProvider(activeSession).also { provider = it }
            emit("support", JSONObject().put("automaticDepthSupported", activeProvider.configureDepthMode()))
            val activeEgl = OffscreenEgl().also { egl = it }
            activeEgl.open()
            activeSession.setCameraTextureNames(intArrayOf(activeEgl.textureId))
            activeSession.setDisplayGeometry(0, 640, 480)
            stage = "session_resume"
            resumeAttempted = true
            onMain { activeSession.resume() }
            stage = "observation"
            val startedAtMs = SystemClock.elapsedRealtime()
            val measurementStartMs = startedAtMs + options.warmupMs
            val measurementEndMs = measurementStartMs + options.measurementMs
            emit("window", JSONObject().put("startedElapsedMs", startedAtMs)
                .put("measurementStartElapsedMs", measurementStartMs)
                .put("measurementEndElapsedMs", measurementEndMs))
            var nextThermalMs = startedAtMs
            var lastAdmittedCameraNs = 0L
            var nextLaneIndex = 0
            while (SystemClock.elapsedRealtime() < measurementEndMs && errors.get() == 0) {
                val updateStartedNs = System.nanoTime()
                val frame = activeSession.update()
                val updateMs = elapsedMs(updateStartedNs)
                val admittedMs = SystemClock.elapsedRealtime()
                if (admittedMs >= measurementEndMs) break
                val phase = if (admittedMs < measurementStartMs) "warmup" else "measurement"
                val frameNs = frame.timestamp
                if (frameNs > 0L) latestFrameNs.set(frameNs)
                val depthStartedNs = System.nanoTime()
                val snapshot = activeProvider.acquireDepthSnapshot(frame, includeFullDepthWhenRawAvailable = true)
                val depthMs = elapsedMs(depthStartedNs)
                snapshot.cameraImageTimestampNs?.takeIf { it > 0L }?.let { latestCameraNs.set(it) }
                val diagnosticsStartedNs = System.nanoTime()
                val positiveRaw = snapshot.rawDepth?.millimeters?.count { it > 0 } ?: 0
                val positiveFull = snapshot.fullDepth?.millimeters?.count { it > 0 } ?: 0
                val validMetric = snapshot.validMetricSampleCount(0.35, 0.2, 8.0, requireFreshRaw = true)
                val diagnosticsMs = elapsedMs(diagnosticsStartedNs)
                emit("frame", JSONObject().put("phase", phase)
                    .put("frameTimestampNs", frameNs)
                    .put("cameraTimestampNs", snapshot.cameraImageTimestampNs ?: JSONObject.NULL)
                    .put("rawDepthTimestampNs", snapshot.rawDepthTimestampNs ?: JSONObject.NULL)
                    .put("fullDepthTimestampNs", snapshot.fullDepthTimestampNs ?: JSONObject.NULL)
                    .put("rawFresh", snapshot.hasFreshMetricRawDepth)
                    .put("fullFresh", snapshot.hasFreshFullDepth)
                    .put("rawReprojected", snapshot.hasMetricRawDepth &&
                        (snapshot.cameraImageTimestampNs ?: 0L) > 0L &&
                        (snapshot.rawDepthTimestampNs ?: 0L) > 0L && !snapshot.rawDepthMatchesCameraImage)
                    .put("positiveRawPixels", positiveRaw).put("positiveFullPixels", positiveFull)
                    .put("validMetricSamples", validMetric).put("trackingState", frame.camera.trackingState.name)
                    .put("frameUpdateMs", updateMs).put("depthCaptureMs", depthMs)
                    .put("diagnosticsMs", diagnosticsMs))
                val pairLimitReached = sameInputComparison && phase == "measurement" && measuredComparisons.get() >= 3
                val cameraNs = snapshot.cameraImageTimestampNs ?: 0L
                var selectedLane: InferenceLane? = null
                if (!pairLimitReached && frameNs > 0L && cameraNs > lastAdmittedCameraNs) {
                    for (offset in lanes.indices) {
                        val index = (nextLaneIndex + offset) % lanes.size
                        if (lanes[index].busy.compareAndSet(false, true)) {
                            selectedLane = lanes[index]
                            nextLaneIndex = (index + 1) % lanes.size
                            break
                        }
                    }
                }
                val lane = selectedLane
                if (lane != null) {
                    var acquiredImage: Image? = null
                    var counted = false
                    var submitted = false
                    try {
                        // Recheck after admission ownership: the worker may have completed pair 3
                        // between the optimistic limit read above and acquiring busy.
                        val atPairLimit = sameInputComparison && phase == "measurement" && measuredComparisons.get() >= 3
                        val image = if (atPairLimit) null else activeProvider.acquireCameraImageOrNull(frame)
                        acquiredImage = image
                        if (image != null) {
                            val sourceAligned = snapshot.frameTimestampNs == frameNs && image.timestamp == cameraNs
                            if (!sourceAligned) sourceMismatches.incrementAndGet()
                            val mapperCapture = frozenMapper(frame, image, snapshot)
                            val mapper = mapperCapture.mapper
                            lastAdmittedCameraNs = image.timestamp
                            val sourceImageNs = image.timestamp
                            val retained = retainedImages.incrementAndGet()
                            maxRetainedImages.updateAndGet { maxOf(it, retained) }
                            val laneRetained = lane.retainedImages.incrementAndGet()
                            lane.maxRetainedImages.updateAndGet { maxOf(it, laneRetained) }
                            counted = true
                            lane.worker.execute {
                                try {
                                    val referenceResult = lane.reference?.detect(image, frameNs / NANOS_PER_MS)
                                    val referenceRaw = lane.reference?.copyLastUnifiedOutputForTest()
                                    val invocationStartedNs = SystemClock.elapsedRealtimeNanos()
                                    val result = checkNotNull(lane.detector).detect(image, frameNs / NANOS_PER_MS)
                                    val invocationCompletedNs = SystemClock.elapsedRealtimeNanos()
                                    val candidateRaw = if (sameInputComparison) lane.detector?.copyLastUnifiedOutputForTest() else null
                                    val completion = synchronized(completionGate) {
                                        val gateMs = SystemClock.elapsedRealtime()
                                        val frameDeltaMs = (latestFrameNs.get() - frameNs) / NANOS_PER_MS
                                        val cameraDeltaMs = (latestCameraNs.get() - sourceImageNs) / NANOS_PER_MS
                                        val discardReason = completionGate.admit(sourceImageNs, frameNs,
                                            latestCameraNs.get(), latestFrameNs.get(), admittedMs, gateMs, sourceAligned)
                                        val fresh = discardReason != "STALE" && discardReason != "SOURCE_MISMATCH"
                                        val pipelineStartedNs = System.nanoTime()
                                        val objects = if (discardReason == "NONE") {
                                            if (mapper != null) pipeline.process(snapshot, frameNs, frameNs / NANOS_PER_MS,
                                                detections = result.detections, mapper = mapper) else emptyList()
                                        } else emptyList()
                                        Completion(gateMs, frameDeltaMs, cameraDeltaMs, fresh, discardReason, objects,
                                            elapsedMs(pipelineStartedNs))
                                    }
                                    val completedMs = SystemClock.elapsedRealtime()
                                    val frameDeltaMs = completion.frameDeltaMs
                                    val endToEndMs = completedMs - admittedMs
                                    val fresh = completion.fresh && sourceAligned
                                    val objects = completion.objects
                                    val pipelineMs = completion.pipelineMs
                                    val duringMeasurement = completedMs in measurementStartMs until measurementEndMs
                                    if (phase == "measurement") measuredInferences.incrementAndGet()
                                    val event = JSONObject().put("phase", phase).put("laneId", lane.id)
                                        .put("invocationStartedElapsedNs", invocationStartedNs)
                                        .put("invocationCompletedElapsedNs", invocationCompletedNs)
                                        .put("usefulResult", completion.discardReason == "NONE")
                                        .put("discardReason", completion.discardReason)
                                        .put("captureToGateMs", completion.gateMs - admittedMs)
                                        .put("completionCameraDeltaMs", completion.cameraDeltaMs)
                                        .put("frameTimestampNs", frameNs)
                                        .put("cameraTimestampNs", sourceImageNs).put("admittedElapsedMs", admittedMs)
                                        .put("completedElapsedMs", completedMs).put("completionDuringMeasurement", duringMeasurement)
                                        .put("endToEndMs", endToEndMs).put("completionFrameDeltaMs", frameDeltaMs)
                                        .put("sourceAligned", sourceAligned).put("fresh", fresh)
                                        .put("detectionCount", result.detections.size).put("pipelineMs", pipelineMs)
                                        .put("depthMapperAvailable", mapper != null).put("mapperDiagnostics", mapperCapture.diagnostics)
                                        .put("trackedObjectCount", objects.size)
                                        .put("metricObjectCount", objects.count { it.source.metric && (it.riskDistanceM ?: 0f) > 0f })
                                        .put("trackAgeAtLeastThreeCount", objects.count { it.trackAgeFrames >= 3 })
                                        .put("validMetricSamples", validMetric)
                                        .put("detectorTotalMs", result.timing.totalMs ?: JSONObject.NULL)
                                        .put("yuvDecodeMs", result.timing.yuvDecodeMs ?: JSONObject.NULL)
                                        .put("modelPreprocessMs", result.timing.modelPreprocessMs ?: JSONObject.NULL)
                                        .put("modelInferenceMs", result.timing.modelInferenceMs ?: JSONObject.NULL)
                                        .put("modelParseMs", result.timing.modelParseMs ?: JSONObject.NULL)
                                        .put("preprocessingStrategy", result.timing.preprocessingStrategy ?: JSONObject.NULL)
                                        .put("runtime", runtimeJson(result.timing.modelRuntime))
                                    if (referenceResult != null) {
                                        val agreement = HeterogeneousOutputComparison.compareDetections(referenceResult.detections, result.detections)
                                        val rawAgreement = HeterogeneousOutputComparison.compareRaw(referenceRaw, candidateRaw, comparisonConfig,
                                            referenceInputSize = referenceConfig?.inputSize)
                                        event.put("sameInputComparison", agreement).put("rawOutputComparison", rawAgreement)
                                            .put("referenceDetectorTotalMs", referenceResult.timing.totalMs ?: JSONObject.NULL)
                                            .put("referenceRuntime", runtimeJson(referenceResult.timing.modelRuntime))
                                        if (phase == "measurement") {
                                            measuredComparisons.incrementAndGet()
                                            if (rawAgreement.getBoolean("available")) rawAvailableComparisons.incrementAndGet()
                                            if (!rawAgreement.getBoolean("withinTolerance")) rawFailedComparisons.incrementAndGet()
                                            if (referenceResult.detections.isNotEmpty() || result.detections.isNotEmpty()) positiveComparisons.incrementAndGet()
                                            if (!agreement.getBoolean("allDetectionsMatched")) failedComparisons.incrementAndGet()
                                        }
                                    }
                                    emit("inference", event)
                                } catch (error: Exception) {
                                    errors.incrementAndGet()
                                    emit("inference_error", JSONObject().put("phase", phase).put("laneId", lane.id)
                                        .put("errorClass", error.javaClass.simpleName))
                                } finally {
                                    try { image.close() } catch (error: Exception) {
                                        errors.incrementAndGet()
                                        emit("cleanup_error", JSONObject().put("resource", "cameraImage")
                                            .put("errorClass", error.javaClass.simpleName))
                                    } finally {
                                        retainedImages.decrementAndGet()
                                        lane.retainedImages.decrementAndGet()
                                        lane.busy.set(false)
                                    }
                                }
                            }
                            submitted = true
                        }
                    } finally {
                        if (!submitted) {
                            try { acquiredImage?.close() } finally {
                                if (counted) { retainedImages.decrementAndGet(); lane.retainedImages.decrementAndGet() }
                                lane.busy.set(false)
                            }
                        }
                    }
                }
                val nowMs = SystemClock.elapsedRealtime()
                measurementElapsedMs = (nowMs - measurementStartMs).coerceIn(0L, options.measurementMs)
                if (nowMs >= nextThermalMs) {
                    emitResourceSample(resources, when {
                        nowMs < measurementStartMs -> "warmup"
                        nowMs < measurementEndMs -> "measurement"
                        else -> "tail"
                    }, nowMs)
                    nextThermalMs = nowMs + 1_000L
                }
                SystemClock.sleep(5L)
            }
            measurementElapsedMs = (SystemClock.elapsedRealtime() - measurementStartMs).coerceIn(0L, options.measurementMs)
            outcome = "COMPLETED"
        } catch (error: Exception) {
            emit("blocked", JSONObject().put("stage", stage).put("errorClass", error.javaClass.simpleName))
        } finally {
            // A timed-out load/native invoke is drained, never cancelled and closed concurrently.
            val detectorCloseErrors = AtomicInteger()
            lanes.forEach { lane ->
                lane.worker.execute {
                    listOf(lane.detector, lane.reference).forEach { current ->
                        try { current?.close() } catch (_: Exception) { detectorCloseErrors.incrementAndGet() }
                    }
                    lane.detector = null
                    lane.reference = null
                }
                lane.worker.shutdown()
            }
            val drainDeadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(30L)
            val joined = lanes.map { lane ->
                try { lane.worker.awaitTermination((drainDeadlineNs - System.nanoTime()).coerceAtLeast(0L), TimeUnit.NANOSECONDS) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
            }.all { it }
            fun cleanup(name: String, operation: () -> Unit) {
                runCatching(operation).onFailure { cleanupFailures[name] = it.javaClass.simpleName }
            }
            if (joined) {
                cleanup("providerClose") { provider?.close() }
                if (resumeAttempted) cleanup("sessionPause") { session?.let { active -> onMain { active.pause() } } }
                cleanup("sessionClose") { session?.close() }
                cleanup("eglClose") { egl?.close() }
            } else cleanupFailures["workerDrain"] = "ResourcesStillOwnedByWorker"
            cleanup("resourceProbeClose") { resources.close() }
            if (detectorCloseErrors.get() > 0) cleanupFailures["detectorClose"] = "CloseFailed"
            val invariants = JSONObject().put("workerTerminated", joined)
                .put("imagesReleased", retainedImages.get() == 0)
                .put("maxInFlightWithinLimit", maxRetainedImages.get() <= lanes.size)
                .put("eachLaneMaxOneInFlight", lanes.all { it.maxRetainedImages.get() <= 1 })
                .put("sourceAligned", sourceMismatches.get() == 0).put("noInferenceErrors", errors.get() == 0)
                .put("noCleanupErrors", cleanupFailures.isEmpty())
                .put("measurementBudgetCompleted", measurementElapsedMs >= options.measurementMs)
                .put("sameInputOutputsWithinTolerance", failedComparisons.get() == 0 && rawFailedComparisons.get() == 0)
            if (lanes.size == 1) invariants.put("maxOneInFlight", maxRetainedImages.get() <= 1)
            outcome = when {
                !joined -> "WORKER_DRAIN_TIMEOUT"
                cleanupFailures.isNotEmpty() -> "CLEANUP_FAILED"
                errors.get() > 0 -> "INFERENCE_FAILED"
                outcome == "COMPLETED" && measuredInferences.get() == 0 -> "NO_MEASURED_INFERENCES"
                else -> outcome
            }
            emit("summary", JSONObject().put("outcome", outcome).put("workerTerminated", joined)
                .put("measurementElapsedMs", measurementElapsedMs).put("invariants", invariants)
                .put("cleanupFailures", JSONObject(cleanupFailures as Map<*, *>))
                .put("retainedImagesAtEnd", retainedImages.get()).put("maxInFlight", maxRetainedImages.get())
                .put("queuedImages", 0).put("maxInFlightLimit", lanes.size)
                .put("measuredInferences", measuredInferences.get()).put("sameInputPairs", measuredComparisons.get())
                .put("rawOutputAvailablePairs", rawAvailableComparisons.get())
                .put("rawOutputFailedPairs", rawFailedComparisons.get())
                .put("rawOutputAgreement", when {
                    !sameInputComparison -> "NOT_RUN"
                    rawAvailableComparisons.get() == 0 -> "UNAVAILABLE"
                    rawFailedComparisons.get() > 0 -> "STRICT_ROW_WISE_MISMATCH"
                    else -> "OBSERVED_WITHIN_TOLERANCE"
                })
                .put("sameInputPositivePairs", positiveComparisons.get()).put("sameInputFailedPairs", failedComparisons.get())
                .put("detectionOutputAgreement", when {
                    !sameInputComparison -> "NOT_RUN"
                    measuredComparisons.get() == 0 -> "NO_PAIRS"
                    failedComparisons.get() > 0 -> "MISMATCH"
                    positiveComparisons.get() == 0 -> "INCONCLUSIVE_NO_POSITIVE_DETECTIONS"
                    else -> "OBSERVED_WITHIN_TOLERANCE"
                }).put("mainActivityE2eValidated", false).put("modelAccuracyValidated", false)
                .put("metricAccuracyValidated", false).put("imagesSaved", false)
                .put("freshnessLimitMs", FRESHNESS_MS).put("splitUnsupportedWarnings", 0))
            assertTrue("Observation failed: $outcome", outcome == "COMPLETED")
            assertTrue("Runtime invariant failure: $invariants", invariants.keys().asSequence().all { invariants.getBoolean(it) })
            assertTrue("Same-input filtered detections differed beyond declared tolerances", failedComparisons.get() == 0)
        }
    }

    /** Two owned tensors overlap CPU preparation with one inference consumer; Images never queue. */
    private fun observeOverlap(options: Options) {
        val context = instrumentation.targetContext
        val producer = InferenceLane("prepare", ModelRuntimeOptions())
        val consumer = InferenceLane("infer", options.runtime().copy(delegate = "gpu"))
        val resources = AndroidWalkSessionResourceProbe(context)
        val evidence = ConcurrentHashMap<OverlapFrameKey, CapturedEvidence>()
        val maximumEvidence = AtomicInteger()
        val producerFinished = AtomicBoolean()
        val abortConsumer = AtomicBoolean()
        val latestFrame = AtomicLong()
        val latestCamera = AtomicLong()
        val errors = AtomicInteger()
        val measured = AtomicInteger()
        val sourceMismatches = AtomicInteger()
        val closeErrors = AtomicInteger()
        val gate = HeterogeneousResultGate()
        val pipeline = ObjectDepthRuntimePipeline()
        val sessionId = SystemClock.elapsedRealtimeNanos()
        var experiment: UnifiedFrameOverlapExperiment? = null
        var session: Session? = null
        var provider: ArCoreFrameProvider? = null
        var egl: OffscreenEgl? = null
        var resumeAttempted = false
        var stage = "camera_permission"
        var outcome = "BLOCKED"
        var measurementElapsedMs = 0L
        val cleanupFailures = linkedMapOf<String, String>()
        try {
            check(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            stage = "overlap_load"
            val config = HeterogeneousModelCandidates.select(TwoModelRuntimeConfig.load(context), options.model)
            val selectedContext = contextForModel(options.model)
            consumer.worker.submit {
                experiment = TfliteAndroidFrameDetector.createUnifiedOverlapForTest(selectedContext, config,
                    consumer.runtime, options.preprocessing, sessionId, FRESHNESS_MS)
            }.get(30L, TimeUnit.SECONDS)
            val active = checkNotNull(experiment)
            emit("runtime", JSONObject().put("laneId", "infer").put("modelCandidate", options.model)
                .put("requestedDelegate", "gpu").put("available", true)
                .put("configuredUnifiedAssetSha256", config.unifiedWalksafe?.artifactSha256 ?: JSONObject.NULL)
                .put("configuredUnifiedInputSize", config.unifiedWalksafe?.inputSize ?: JSONObject.NULL)
                .put("tensorBufferCount", active.tensorBufferCount).put("ownedTensorBytes", active.ownedTensorBytes)
                .put("preprocessingTensorBytes", active.preprocessingTensorBytes))
            stage = "session_setup"
            val activeSession = Session(context).also { session = it }
            val activeProvider = ArCoreFrameProvider(activeSession).also { provider = it }
            emit("support", JSONObject().put("automaticDepthSupported", activeProvider.configureDepthMode()))
            val activeEgl = OffscreenEgl().also { egl = it }
            activeEgl.open()
            activeSession.setCameraTextureNames(intArrayOf(activeEgl.textureId))
            activeSession.setDisplayGeometry(0, 640, 480)
            resumeAttempted = true
            onMain { activeSession.resume() }
            val startedMs = SystemClock.elapsedRealtime()
            val measurementStartMs = startedMs + options.warmupMs
            val measurementEndMs = measurementStartMs + options.measurementMs
            emit("window", JSONObject().put("startedElapsedMs", startedMs)
                .put("measurementStartElapsedMs", measurementStartMs).put("measurementEndElapsedMs", measurementEndMs))
            consumer.worker.execute {
                try {
                    while (errors.get() == 0 && !abortConsumer.get()) {
                        val invocationStartedNs = SystemClock.elapsedRealtimeNanos()
                        val observation = active.inferLatest(copyRawOutput = false)
                        val invocationCompletedNs = SystemClock.elapsedRealtimeNanos()
                        if (abortConsumer.get()) break
                        if (observation.status == OverlapFrameStatus.FAILED) errors.incrementAndGet()
                        val key = observation.key
                        if (key == null) {
                            if (producerFinished.get() && observation.status == OverlapFrameStatus.NO_READY_FRAME) break
                            check(observation.status == OverlapFrameStatus.NO_READY_FRAME || observation.status == OverlapFrameStatus.BUSY) {
                                "Rejected overlap frame must preserve its key: ${observation.status}"
                            }
                            SystemClock.sleep(2L)
                            continue
                        }
                        val source = checkNotNull(evidence.remove(key)) { "Overlap depth evidence missing" }
                        if (observation.observedTiming == null) {
                            emit("overlap_status", JSONObject().put("status", observation.status.name)
                                .put("phase", source.phase).put("elapsedMs", observation.completedAtElapsedRealtimeMs)
                                .put("cameraTimestampNs", key.cameraImageTimestampNs))
                            continue
                        }
                        val timing = observation.observedTiming
                        val result = observation.result
                        val gateMs = SystemClock.elapsedRealtime()
                        val discard = when {
                            observation.status != OverlapFrameStatus.ACCEPTED -> observation.status.name
                            result == null -> "MISSING_ACCEPTED_RESULT"
                            else -> gate.admit(key.cameraImageTimestampNs, key.arFrameTimestampNs,
                                latestCamera.get(), latestFrame.get(), source.admittedMs, gateMs, source.sourceAligned)
                        }
                        val pipelineStart = System.nanoTime()
                        val objects = if (discard == "NONE" && result != null && source.mapper != null) {
                            pipeline.process(source.snapshot, key.arFrameTimestampNs, key.arFrameTimestampNs / NANOS_PER_MS,
                                detections = result.detections, mapper = source.mapper)
                        } else emptyList()
                        val pipelineMs = elapsedMs(pipelineStart)
                        val completedMs = SystemClock.elapsedRealtime()
                        if (source.phase == "measurement") measured.incrementAndGet()
                        emit("inference", JSONObject().put("phase", source.phase).put("laneId", "infer")
                            .put("frameTimestampNs", key.arFrameTimestampNs).put("cameraTimestampNs", key.cameraImageTimestampNs)
                            .put("admittedElapsedMs", source.admittedMs).put("completedElapsedMs", completedMs)
                            .put("completionDuringMeasurement", completedMs in measurementStartMs until measurementEndMs)
                            .put("invocationStartedElapsedNs", invocationStartedNs).put("invocationCompletedElapsedNs", invocationCompletedNs)
                            .put("overlapStatus", observation.status.name).put("usefulResult", discard == "NONE")
                            .put("discardReason", discard).put("fresh", discard == "NONE")
                            .put("sourceAligned", source.sourceAligned).put("captureToGateMs", gateMs - source.admittedMs)
                            .put("endToEndMs", completedMs - source.admittedMs)
                            .put("sourceAgeMs", observation.sourceAgeMs ?: JSONObject.NULL)
                            .put("readyWaitMs", observation.readyWaitMs ?: JSONObject.NULL)
                            .put("invocationWallMs", observation.invocationWallMs ?: JSONObject.NULL)
                            .put("detectorTotalMs", timing.totalMs ?: JSONObject.NULL)
                            .put("modelPreprocessMs", timing.modelPreprocessMs ?: JSONObject.NULL)
                            .put("modelInferenceMs", timing.modelInferenceMs ?: JSONObject.NULL)
                            .put("modelParseMs", timing.modelParseMs ?: JSONObject.NULL)
                            .put("ownedTensorCopyMs", timing.ownedTensorCopyMs ?: JSONObject.NULL)
                            .put("runtime", runtimeJson(timing.modelRuntime)).put("preprocessingStrategy", timing.preprocessingStrategy ?: JSONObject.NULL)
                            .put("detectionCount", result?.detections?.size ?: JSONObject.NULL)
                            .put("pipelineMs", pipelineMs).put("depthMapperAvailable", source.mapper != null)
                            .put("mapperDiagnostics", source.mapperDiagnostics)
                            .put("trackedObjectCount", objects.size)
                            .put("metricObjectCount", objects.count { it.source.metric && (it.riskDistanceM ?: 0f) > 0f })
                            .put("trackAgeAtLeastThreeCount", objects.count { it.trackAgeFrames >= 3 })
                            .put("validMetricSamples", source.validMetricSamples))
                    }
                } catch (error: Exception) {
                    errors.incrementAndGet()
                    emit("inference_error", JSONObject().put("laneId", "infer").put("errorClass", error.javaClass.simpleName))
                }
            }
            stage = "observation"
            var lastAdmittedCameraNs = 0L
            var nextThermalMs = startedMs
            while (SystemClock.elapsedRealtime() < measurementEndMs && errors.get() == 0) {
                val updateStart = System.nanoTime()
                val frame = activeSession.update()
                val updateMs = elapsedMs(updateStart)
                val admittedMs = SystemClock.elapsedRealtime()
                if (admittedMs >= measurementEndMs) break
                val phase = if (admittedMs < measurementStartMs) "warmup" else "measurement"
                val frameNs = frame.timestamp
                if (frameNs > 0L) latestFrame.set(frameNs)
                val depthStart = System.nanoTime()
                val snapshot = activeProvider.acquireDepthSnapshot(frame, includeFullDepthWhenRawAvailable = true)
                val depthMs = elapsedMs(depthStart)
                val cameraNs = snapshot.cameraImageTimestampNs ?: 0L
                if (cameraNs > 0L) latestCamera.set(cameraNs)
                val diagnosticsStart = System.nanoTime()
                val valid = snapshot.validMetricSampleCount(0.35, 0.2, 8.0, requireFreshRaw = true)
                val positiveRaw = snapshot.rawDepth?.millimeters?.count { it > 0 } ?: 0
                val positiveFull = snapshot.fullDepth?.millimeters?.count { it > 0 } ?: 0
                emit("frame", JSONObject().put("phase", phase).put("frameTimestampNs", frameNs)
                    .put("cameraTimestampNs", snapshot.cameraImageTimestampNs ?: JSONObject.NULL)
                    .put("rawDepthTimestampNs", snapshot.rawDepthTimestampNs ?: JSONObject.NULL)
                    .put("fullDepthTimestampNs", snapshot.fullDepthTimestampNs ?: JSONObject.NULL)
                    .put("rawFresh", snapshot.hasFreshMetricRawDepth).put("fullFresh", snapshot.hasFreshFullDepth)
                    .put("rawReprojected", snapshot.hasMetricRawDepth && cameraNs > 0L &&
                        (snapshot.rawDepthTimestampNs ?: 0L) > 0L && !snapshot.rawDepthMatchesCameraImage)
                    .put("positiveRawPixels", positiveRaw).put("positiveFullPixels", positiveFull)
                    .put("validMetricSamples", valid).put("trackingState", frame.camera.trackingState.name)
                    .put("frameUpdateMs", updateMs).put("depthCaptureMs", depthMs).put("diagnosticsMs", elapsedMs(diagnosticsStart)))
                if (frameNs > 0L && cameraNs > lastAdmittedCameraNs && producer.busy.compareAndSet(false, true)) {
                    var image: Image? = null
                    var submitted = false
                    var counted = false
                    try {
                        val acquired = activeProvider.acquireCameraImageOrNull(frame)
                        image = acquired
                        if (acquired != null) {
                            lastAdmittedCameraNs = acquired.timestamp
                            val key = OverlapFrameKey(sessionId, frameNs, acquired.timestamp)
                            val aligned = snapshot.frameTimestampNs == frameNs && cameraNs == acquired.timestamp
                            if (!aligned) sourceMismatches.incrementAndGet()
                            val mapperCapture = frozenMapper(frame, acquired, snapshot)
                            val source = CapturedEvidence(phase, admittedMs, snapshot,
                                mapperCapture.mapper, aligned, valid, mapperCapture.diagnostics)
                            val retained = producer.retainedImages.incrementAndGet()
                            producer.maxRetainedImages.updateAndGet { maxOf(it, retained) }
                            counted = true
                            producer.worker.execute {
                                try {
                                    evidence[key] = source
                                    maximumEvidence.updateAndGet { maxOf(it, evidence.size) }
                                    check(evidence.size <= 3) { "Overlap evidence exceeded two tensors plus one preparation" }
                                    val prepStart = SystemClock.elapsedRealtimeNanos()
                                    val preparation = active.tryPrepare(acquired, key, admittedMs)
                                    val prepEnd = SystemClock.elapsedRealtimeNanos()
                                    preparation.replacedReadyKey?.let { evidence.remove(it) }
                                    if (preparation.status != OverlapFrameStatus.ACCEPTED) evidence.remove(key)
                                    emit("preparation", JSONObject().put("phase", phase)
                                        .put("frameTimestampNs", frameNs).put("cameraTimestampNs", key.cameraImageTimestampNs)
                                        .put("preparationStartedElapsedNs", prepStart).put("preparationCompletedElapsedNs", prepEnd)
                                        .put("status", preparation.status.name).put("replacedReadyFrame", preparation.replacedReadyKey != null)
                                        .put("preprocessingMs", preparation.preprocessingMs ?: JSONObject.NULL)
                                        .put("tensorCopyMs", preparation.tensorCopyMs ?: JSONObject.NULL)
                                        .put("preparationMs", preparation.preparationMs ?: JSONObject.NULL))
                                    if (preparation.status == OverlapFrameStatus.FAILED) errors.incrementAndGet()
                                } catch (error: Exception) {
                                    errors.incrementAndGet()
                                    emit("inference_error", JSONObject().put("laneId", "prepare").put("errorClass", error.javaClass.simpleName))
                                } finally {
                                    try { acquired.close() } catch (_: Exception) { closeErrors.incrementAndGet() }
                                    finally { producer.retainedImages.decrementAndGet(); producer.busy.set(false) }
                                }
                            }
                            submitted = true
                        }
                    } finally {
                        if (!submitted) {
                            try { image?.close() } finally {
                                if (counted) producer.retainedImages.decrementAndGet()
                                producer.busy.set(false)
                            }
                        }
                    }
                }
                val nowMs = SystemClock.elapsedRealtime()
                measurementElapsedMs = (nowMs - measurementStartMs).coerceIn(0L, options.measurementMs)
                if (nowMs >= nextThermalMs) {
                    emitResourceSample(resources, when {
                        nowMs < measurementStartMs -> "warmup"
                        nowMs < measurementEndMs -> "measurement"
                        else -> "tail"
                    }, nowMs)
                    nextThermalMs = nowMs + 1_000L
                }
                SystemClock.sleep(5L)
            }
            measurementElapsedMs = (SystemClock.elapsedRealtime() - measurementStartMs).coerceIn(0L, options.measurementMs)
            outcome = "COMPLETED"
        } catch (error: Exception) {
            emit("blocked", JSONObject().put("stage", stage).put("errorClass", error.javaClass.simpleName))
        } finally {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30L)
            fun await(lane: InferenceLane): Boolean = try {
                lane.worker.awaitTermination((deadline - System.nanoTime()).coerceAtLeast(0L), TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
            producer.worker.shutdown()
            val producerJoined = await(producer)
            if (producerJoined) {
                producerFinished.set(true)
                consumer.worker.execute { try { experiment?.close() } catch (_: Exception) { closeErrors.incrementAndGet() } }
                consumer.worker.shutdown()
            } else {
                // Stop idle polling without closing a detector/tensor still used by preparation.
                abortConsumer.set(true)
                consumer.worker.shutdown()
            }
            val consumerJoined = producerJoined && await(consumer)
            val joined = producerJoined && consumerJoined
            val evidenceRetainedAtDrain = evidence.size
            fun cleanup(name: String, operation: () -> Unit) {
                runCatching(operation).onFailure { cleanupFailures[name] = it.javaClass.simpleName }
            }
            if (joined) {
                evidence.clear()
                cleanup("providerClose") { provider?.close() }
                if (resumeAttempted) cleanup("sessionPause") { session?.let { active -> onMain { active.pause() } } }
                cleanup("sessionClose") { session?.close() }
                cleanup("eglClose") { egl?.close() }
            } else cleanupFailures["workerDrain"] = "ResourcesStillOwnedByWorker"
            cleanup("resourceProbeClose") { resources.close() }
            if (closeErrors.get() > 0) cleanupFailures["ownedResourceClose"] = "CloseFailed"
            val invariants = JSONObject().put("workerTerminated", joined)
                .put("imagesReleased", producer.retainedImages.get() == 0)
                .put("maxOneInFlight", producer.maxRetainedImages.get() <= 1)
                .put("sourceAligned", sourceMismatches.get() == 0).put("noInferenceErrors", errors.get() == 0)
                .put("noCleanupErrors", cleanupFailures.isEmpty())
                .put("measurementBudgetCompleted", measurementElapsedMs >= options.measurementMs)
                .put("boundedEvidence", maximumEvidence.get() <= 3)
                .put("evidenceDrained", joined && evidenceRetainedAtDrain == 0)
            outcome = when {
                !joined -> "WORKER_DRAIN_TIMEOUT"
                cleanupFailures.isNotEmpty() -> "CLEANUP_FAILED"
                errors.get() > 0 -> "INFERENCE_FAILED"
                outcome == "COMPLETED" && measured.get() == 0 -> "NO_MEASURED_INFERENCES"
                else -> outcome
            }
            emit("summary", JSONObject().put("outcome", outcome).put("workerTerminated", joined)
                .put("measurementElapsedMs", measurementElapsedMs).put("cleanupFailures", JSONObject(cleanupFailures as Map<*, *>))
                .put("invariants", invariants).put("maxInFlight", producer.maxRetainedImages.get()).put("maxInFlightLimit", 1)
                .put("retainedImagesAtEnd", producer.retainedImages.get()).put("queuedImages", 0)
                .put("tensorBufferCount", experiment?.tensorBufferCount ?: JSONObject.NULL)
                .put("ownedTensorBytes", experiment?.ownedTensorBytes ?: JSONObject.NULL)
                .put("preprocessingTensorBytes", experiment?.preprocessingTensorBytes ?: JSONObject.NULL)
                .put("evidenceMaxEntries", maximumEvidence.get()).put("evidenceRetainedAtDrain", evidenceRetainedAtDrain)
                .put("measuredInferences", measured.get())
                .put("mainActivityE2eValidated", false).put("modelAccuracyValidated", false)
                .put("metricAccuracyValidated", false).put("imagesSaved", false).put("splitUnsupportedWarnings", 0))
            assertTrue("Overlap observation failed: $outcome", outcome == "COMPLETED")
            assertTrue("Overlap invariant failure: $invariants", invariants.keys().asSequence().all { invariants.getBoolean(it) })
        }
    }

    private data class CapturedEvidence(val phase: String, val admittedMs: Long, val snapshot: DepthFrameSnapshot,
        val mapper: CoordinateMapper?, val sourceAligned: Boolean, val validMetricSamples: Int,
        val mapperDiagnostics: JSONObject)

    private fun emitResourceSample(resources: AndroidWalkSessionResourceProbe, phase: String, elapsedMs: Long) {
        val context = instrumentation.targetContext
        val thermal = resources.snapshot()
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(PowerManager::class.java)?.currentThermalStatus
        } else null
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val processMemory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val vm = Runtime.getRuntime()
        emit("thermal", JSONObject().put("phase", phase).put("elapsedMs", elapsedMs)
            .put("thermalStatus", status ?: JSONObject.NULL).put("thermalThrottled", thermal.thermalThrottled ?: JSONObject.NULL)
            .put("thermalBelowCritical", thermal.thermalBelowCritical ?: JSONObject.NULL)
            .put("javaUsedBytes", vm.totalMemory() - vm.freeMemory()).put("javaTotalBytes", vm.totalMemory())
            .put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize()).put("processPssKb", processMemory.totalPss)
            .put("availSystemMemoryBytes", memory.availMem))
    }

    private data class FrozenMapperCapture(val mapper: CoordinateMapper?, val diagnostics: JSONObject)

    private fun frozenMapper(frame: Frame, image: Image, snapshot: DepthFrameSnapshot): FrozenMapperCapture {
        val width = snapshot.rawDepth?.width ?: snapshot.fullDepth?.width
        val height = snapshot.rawDepth?.height ?: snapshot.fullDepth?.height
        val diagnostics = JSONObject().put("cameraWidth", image.width).put("cameraHeight", image.height)
            .put("rawDepthWidth", snapshot.rawDepth?.width ?: JSONObject.NULL)
            .put("rawDepthHeight", snapshot.rawDepth?.height ?: JSONObject.NULL)
            .put("fullDepthWidth", snapshot.fullDepth?.width ?: JSONObject.NULL)
            .put("fullDepthHeight", snapshot.fullDepth?.height ?: JSONObject.NULL)
            .put("outOfBoundsCornerCount", JSONObject.NULL).put("nonFinitePointCount", JSONObject.NULL)
            .put("centerResidual", JSONObject.NULL)
        if (width == null || height == null) {
            return FrozenMapperCapture(null, diagnostics.put("failureReason", "DEPTH_DIMENSIONS_UNAVAILABLE"))
        }
        val points = floatArrayOf(0f, 0f, image.width.toFloat(), 0f, 0f, image.height.toFloat(),
            image.width.toFloat(), image.height.toFloat(), image.width / 2f, image.height / 2f)
        val mapped = FloatArray(points.size)
        var stage = "TRANSFORM_CALL"
        return try {
            frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, points, Coordinates2d.TEXTURE_NORMALIZED, mapped)
            val transformed = mapped.toList().chunked(2).map { Point2(it[0], it[1]) }
            val corners = transformed.take(4)
            val nonFiniteCount = transformed.count { !it.x.isFinite() || !it.y.isFinite() }
            diagnostics.put("nonFinitePointCount", nonFiniteCount)
                .put("outOfBoundsCornerCount", corners.count {
                    it.x.isFinite() && it.y.isFinite() && (it.x !in 0f..1f || it.y !in 0f..1f)
                })
            if (nonFiniteCount == 0) {
                val center = transformed[4]
                val residual = maxOf(kotlin.math.abs(corners.sumOf { it.x.toDouble() } / 4.0 - center.x),
                    kotlin.math.abs(corners.sumOf { it.y.toDouble() } / 4.0 - center.y))
                diagnostics.put("centerResidual", residual)
            }
            stage = "TRANSFORM_HELPER"
            val frozen = FrozenImageToDepthTransform.create(frame.timestamp, corners, transformed[4])
            if (frozen == null) {
                FrozenMapperCapture(null, diagnostics.put("failureReason", "TRANSFORM_HELPER_REJECTED"))
            } else {
                FrozenMapperCapture(FrozenImageToTextureCoordinateMapper(frame.timestamp, frozen, ImageSize(width, height)),
                    diagnostics.put("failureReason", "NONE"))
            }
        } catch (error: Exception) {
            FrozenMapperCapture(null, diagnostics.put("failureReason", "${stage}_EXCEPTION")
                .put("exceptionClass", error.javaClass.simpleName))
        }
    }

    /** Candidate assets belong to the test APK; writable runtime directories belong to the target UID. */
    private fun contextForModel(model: String): Context {
        val targetContext = instrumentation.targetContext
        if (model == "production") return targetContext
        return object : ContextWrapper(targetContext) {
            override fun getAssets() = instrumentation.context.assets
        }
    }

    private fun runtimeJson(runtime: AndroidDetectorRuntime?): Any = runtime?.let {
        JSONObject().put("requestedDelegate", it.requestedDelegate).put("activeDelegate", it.activeDelegate)
            .put("numThreads", it.numThreads).put("fallbackUsed", it.fallbackUsed)
            .put("fallbackReason", it.fallbackReason ?: JSONObject.NULL)
            .put("gpuPrecisionLossAllowed", it.gpuPrecisionLossAllowed ?: JSONObject.NULL)
            .put("gpuSerializationCacheStatus", it.gpuSerializationCacheStatus ?: JSONObject.NULL)
            .put("gpuSerializationCacheToken", it.gpuSerializationCacheToken ?: JSONObject.NULL)
            .put("gpuSerializationCacheFailureReason", it.gpuSerializationCacheFailureReason ?: JSONObject.NULL)
    } ?: JSONObject.NULL

    @Synchronized private fun emit(kind: String, values: JSONObject) {
        val line = values.put("schemaVersion", 1).put("runId", runId).put("seq", sequence++)
            .put("kind", kind).toString()
        Log.i("WalkSafeHeteroTest", line)
        instrumentation.sendStatus(0, Bundle().apply { putString("walksafe_heterogeneous_runtime", line) })
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private data class Options(
        val mode: String,
        val scheduling: String,
        val preprocessing: YuvPreprocessingStrategy,
        val warmupMs: Long,
        val measurementMs: Long,
        val gpuPrecisionLossAllowed: Boolean,
        val dualCpuThreads: Int,
        val model: String,
    ) {
        fun runtime() = ModelRuntimeOptions(
            delegate = if (mode == "gpu") "gpu" else "cpu",
            numThreads = when (mode) { "cpu2" -> 2; "cpu6" -> 6; else -> 4 },
            fallbackToCpu = true,
            gpuPrecisionLossAllowed = gpuPrecisionLossAllowed,
        )
        fun json() = JSONObject().put("mode", mode).put("scheduling", scheduling)
            .put("preprocessing", preprocessing.name).put("warmupMs", warmupMs)
            .put("measurementMs", measurementMs).put("gpuPrecisionLossAllowed", gpuPrecisionLossAllowed)
            .put("dualCpuThreads", dualCpuThreads).put("model", model)
        companion object {
            fun parse(args: Bundle): Options {
                val mode = args.getString("heterogeneousMode") ?: "cpu4"
                require(mode in setOf("cpu2", "cpu4", "cpu6", "gpu")) { "Invalid heterogeneousMode" }
                val scheduling = args.getString("heterogeneousScheduling") ?: "sequential"
                require(scheduling in setOf("sequential", "overlap", "dual")) { "Invalid heterogeneousScheduling" }
                val dualCpuThreads = (args.getString("heterogeneousDualCpuThreads") ?: "2").toIntOrNull()
                require(dualCpuThreads in setOf(2, 4, 6)) { "Invalid heterogeneousDualCpuThreads" }
                val model = args.getString("heterogeneousModel") ?: "production"
                require(model in HeterogeneousModelCandidates.names) { "Invalid heterogeneousModel" }
                val strategy = args.getString("heterogeneousPreprocessing") ?: "legacy"
                require(strategy in setOf("legacy", "fused")) { "Invalid heterogeneousPreprocessing" }
                val precision = args.getString("heterogeneousGpuPrecisionLossAllowed") ?: "false"
                require(precision in setOf("true", "false")) { "Invalid heterogeneousGpuPrecisionLossAllowed" }
                fun duration(name: String, default: Long, minimum: Long, maximum: Long): Long {
                    val text = args.getString(name) ?: return default
                    val value = requireNotNull(text.toLongOrNull()) { "Invalid $name" }
                    require(value in minimum..maximum) { "$name out of bounds" }
                    return value
                }
                return Options(mode, scheduling,
                    if (strategy == "fused") YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR else YuvPreprocessingStrategy.LEGACY_TWO_PASS,
                    duration("heterogeneousWarmupMs", 5_000L, 1_000L, 30_000L),
                    duration("heterogeneousMeasurementMs", 18_000L, 5_000L, 120_000L), precision == "true", requireNotNull(dualCpuThreads), model)
            }
        }
    }

    private class InferenceLane(val id: String, val runtime: ModelRuntimeOptions) {
        val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "walksafe-runtime-$id").apply { isDaemon = true }
        }
        val busy = AtomicBoolean()
        val retainedImages = AtomicInteger()
        val maxRetainedImages = AtomicInteger()
        var detector: TfliteAndroidFrameDetector? = null // Accessed only by this lane worker.
        var reference: TfliteAndroidFrameDetector? = null
    }

    private data class Completion(
        val gateMs: Long,
        val frameDeltaMs: Long,
        val cameraDeltaMs: Long,
        val fresh: Boolean,
        val discardReason: String,
        val objects: List<TrackedObjectDepth>,
        val pipelineMs: Double,
    )

    private fun elapsedMs(startedNs: Long): Double = (System.nanoTime() - startedNs) / 1_000_000.0

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val FRESHNESS_MS = 800L
    }

    // Same thread-owned offscreen camera EGL contract as the existing Adaptive device test.
    private class OffscreenEgl : Closeable {
        private var display = EGL14.EGL_NO_DISPLAY
        private var context = EGL14.EGL_NO_CONTEXT
        private var surface = EGL14.EGL_NO_SURFACE
        private var initialized = false
        var textureId = 0
            private set

        fun open() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
            initialized = true
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val attributes = intArrayOf(
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE,
            )
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0)
            val config = requireNotNull(configs[0])
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
            surface = EGL14.eglCreatePbufferSurface(display, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(surface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
            val names = IntArray(1)
            GLES20.glGenTextures(1, names, 0)
            textureId = names[0]
            check(textureId != 0) { "glGenTextures failed" }
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "External camera texture setup failed" }
        }

        override fun close() {
            if (display == EGL14.EGL_NO_DISPLAY) return
            if (!initialized) {
                display = EGL14.EGL_NO_DISPLAY
                return
            }
            val failures = mutableListOf<String>()
            fun release(name: String, operation: () -> Boolean) {
                if (!operation()) failures += name
            }
            if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            release("makeNoCurrent") {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            }
            if (surface != EGL14.EGL_NO_SURFACE) release("destroySurface") { EGL14.eglDestroySurface(display, surface) }
            if (context != EGL14.EGL_NO_CONTEXT) release("destroyContext") { EGL14.eglDestroyContext(display, context) }
            release("releaseThread") { EGL14.eglReleaseThread() }
            release("terminate") { EGL14.eglTerminate(display) }
            textureId = 0
            surface = EGL14.EGL_NO_SURFACE
            context = EGL14.EGL_NO_CONTEXT
            display = EGL14.EGL_NO_DISPLAY
            initialized = false
            check(failures.isEmpty()) { "EGL cleanup failed: $failures" }
        }
    }

}

package kr.co.hanium.dreamup.walksafe.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.co.hanium.dreamup.walksafe.inference.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Opt-in, real camera/model test. Does not enter MainActivity, change permissions/preferences,
 * inject Depth/pose, save camera content, or claim improvement from an incomplete observation.
 * Run with -e allowRuntimeCalibrationSessionDeviceTest true. The metric attempt has the actual
 * 300 s session budget; the two shorter camera-only attempts cover cancellation and re-entry.
 * Native draining may extend wall time. A drain timeout is a failure, never a release signal.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeCalibrationSessionDeviceTest {
    @Test
    fun realMetricSessionThenCameraCancellationAndReentry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Explicit real-device opt-in required", InstrumentationRegistry.getArguments()
            .getString("allowRuntimeCalibrationSessionDeviceTest") == "true")
        val context = instrumentation.targetContext
        assumeTrue("Existing target CAMERA permission required; test never changes permission",
            context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        val report = JSONObject().put("schemaVersion", 1)
            .put("test", "actual_runtime_calibration_session")
            .put("metricBudgetMs", METRIC_BUDGET_MS)
            .put("supplementalScope", "camera_only_cancel_drain_reentry")
            .put("performancePassIsNotLifecyclePass", true)
            .put("availableProcessors", Runtime.getRuntime().availableProcessors())
        val attempts = mutableListOf<Attempt>()
        val baselineExecutor = Executors.newSingleThreadExecutor { Thread(it, "CalibrationTestBaseline") }
        val callbackExecutor = Executor { Handler(Looper.getMainLooper()).post(it) }
        val scenario = ActivityScenario.launch<RuntimeCalibrationTestHostActivity>(
            Intent(context, RuntimeCalibrationTestHostActivity::class.java))
        lateinit var host: RuntimeCalibrationTestHostActivity
        scenario.onActivity { host = it }
        val currentCandidate = AtomicReference(RuntimeCandidate(RuntimeBackend.CPU,
            minOf(4, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))))
        var allSessionsClosed = true
        val detectorInstalled = AtomicBoolean(false)
        try {
            val setupStart = now()
            val originalConfig = TwoModelRuntimeConfig.load(context)
            val model = requireNotNull(originalConfig.unifiedWalksafe)
            check(originalConfig.primaryModelKey == TwoModelRuntimeConfig.UNIFIED_MODEL_KEY && model.enabled)
            val config = originalConfig.copy(
                unifiedWalksafe = model.copy(runtime = model.runtime.copy(
                    delegate = "cpu", numThreads = currentCandidate.get().numThreads, fallbackToCpu = false)),
                fallbackModelKey = null, customTactile = null, cocoGeneral = null,
            )
            val manifest = RuntimeCalibrationFixtures(context).loadManifest()
            report.put("fixtureVersion", manifest.version).put("fixtureHash", manifest.sha256)
                .put("configBundleVersion", config.bundleVersion)
                .put("baseline", candidateJson(currentCandidate.get()))
                .put("preprocessing", YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR.name)
            val load = baselineExecutor.submit<AndroidDetectorLoadResult> {
                TfliteAndroidFrameDetector.createWithStatus(context, config,
                    preprocessingStrategy = YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR).also { loaded ->
                    loaded.detector?.let {
                        host.installDetector(it)
                        detectorInstalled.set(true)
                    }
                }
            }.get(NATIVE_DRAIN_ALLOWANCE_MS, TimeUnit.MILLISECONDS)
            report.put("baselineSetupMsOutsideSessionBudget", now() - setupStart)
                .put("baselineLoad", JSONObject().put("available", load.detectorAvailable)
                    .put("fallback", load.fallbackUsed).put("modelKey", load.modelKey)
                    .put("reason", load.reason ?: JSONObject.NULL))
            requireNotNull(load.detector)
            assertTrue("Real unified CPU baseline must load without fallback",
                load.detectorAvailable && !load.fallbackUsed && load.modelKey == TwoModelRuntimeConfig.UNIFIED_MODEL_KEY)

            fun newAttempt(name: String, scope: RuntimeFeatureScope, budgetMs: Long): Attempt {
                val binding = sha256(context.assets.open("model-config/two_model_runtime.json").use { it.readBytes() } +
                    (manifest.sha256 + scope.name + RuntimeSelectionPolicy.VERSION).toByteArray())
                return Attempt(name, context, host, config, currentCandidate, baselineExecutor,
                    callbackExecutor, binding, scope, budgetMs).also { attempt ->
                    attempts += attempt
                    allSessionsClosed = false
                    scenario.onActivity {
                        it.onHostUnavailable = { attempt.cancel("test_host_paused") }
                        attempt.session.start()
                    }
                }
            }

            val metric = newAttempt("metric_300s", RuntimeFeatureScope.CAMERA_TRACKING_METRIC_DEPTH, METRIC_BUDGET_MS)
            waitFor(metric, METRIC_BUDGET_MS + NATIVE_DRAIN_ALLOWANCE_MS, report, attempts, context) { metric.isClosed() }
            metric.verifyClosed()
            allSessionsClosed = true
            metric.verifyDecisionEvidence()

            // 90 s leaves no candidate-screening budget: exercises the real baseline fixtures/live
            // path without loading a succession of GPU challengers solely to test cancellation.
            val paused = newAttempt("camera_inflight_pause", RuntimeFeatureScope.CAMERA_TRACKING, CANCELLATION_BUDGET_MS)
            waitFor(paused, CANCELLATION_BUDGET_MS, report, attempts, context) {
                paused.imageInFlight() || paused.isClosed()
            }
            assertTrue("Pause coverage requires a real owned camera Image", paused.imageInFlight())
            scenario.moveToState(Lifecycle.State.CREATED)
            waitFor(paused, NATIVE_DRAIN_ALLOWANCE_MS, report, attempts, context) { paused.isClosed() }
            paused.verifyClosed()
            allSessionsClosed = true
            assertTrue("Host onPause must cancel with an actual in-flight image", paused.cancelledWithImage.get())

            scenario.moveToState(Lifecycle.State.RESUMED)
            val reentry = newAttempt("camera_reentry_explicit_cancel", RuntimeFeatureScope.CAMERA_TRACKING, CANCELLATION_BUDGET_MS)
            waitFor(reentry, CANCELLATION_BUDGET_MS, report, attempts, context) {
                reentry.imageInFlight() || reentry.isClosed()
            }
            assertTrue("Re-entry must acquire a new real camera Image", reentry.imageInFlight())
            reentry.cancel("test_explicit_cancel")
            waitFor(reentry, NATIVE_DRAIN_ALLOWANCE_MS, report, attempts, context) { reentry.isClosed() }
            reentry.verifyClosed()
            allSessionsClosed = true
            assertTrue("Explicit cancellation must observe image ownership", reentry.cancelledWithImage.get())
            report.put("lifecycleAssertions", "PASS")
        } catch (error: Throwable) {
            report.put("lifecycleAssertions", "FAIL").put("failureType", error.javaClass.simpleName)
            throw error
        } finally {
            // Cancellation is not preemption. Do not close borrowed A or re-open a camera until
            // the real session callback has proved runner/image/camera draining completed.
            attempts.filterNot { it.isClosed() }.forEach { it.cancel("test_finally_cleanup") }
            attempts.filterNot { it.isClosed() }.forEach { attempt ->
                runCatching {
                    waitFor(attempt, NATIVE_DRAIN_ALLOWANCE_MS, report, attempts, context) { attempt.isClosed() }
                    attempt.verifyClosed()
                }.onFailure { report.put("cleanupFailureType", it.javaClass.simpleName) }
            }
            allSessionsClosed = allSessionsClosed || attempts.all { it.isClosed() && it.closeVerified.get() }
            report.put("allSessionsClosed", allSessionsClosed)
            runCatching { scenario.onActivity { it.onHostUnavailable = null } }
                .onFailure { report.put("hostCallbackDetachFailureType", it.javaClass.simpleName) }
            if (allSessionsClosed) {
                runCatching {
                    val closed = baselineExecutor.submit<Boolean> {
                        val owned = host.detachDetector() ?: return@submit false
                        owned.close()
                        readField(owned, "closed") == true
                    }.get(NATIVE_DRAIN_ALLOWANCE_MS, TimeUnit.MILLISECONDS)
                    report.put("finalDetectorActualClosed", closed)
                    check(closed)
                }.onFailure { report.put("finalDetectorCloseFailureType", it.javaClass.simpleName) }
            } else report.put("finalDetectorCloseDeferredForNativeOwnership", detectorInstalled.get())
            baselineExecutor.shutdown()
            report.put("baselineExecutorTerminated", baselineExecutor.awaitTermination(5L, TimeUnit.SECONDS))
            runCatching { scenario.close() }
                .onFailure { report.put("hostDestroyFailureType", it.javaClass.simpleName) }
            val cleanupPassed = allSessionsClosed && report.optBoolean("finalDetectorActualClosed") &&
                report.optBoolean("baselineExecutorTerminated") &&
                !report.has("hostCallbackDetachFailureType") && !report.has("hostDestroyFailureType")
            report.put("resourceCleanupAssertions", if (cleanupPassed) "PASS" else "FAIL")
            if (!cleanupPassed) report.put("lifecycleAssertions", "FAIL")
            writeReport(context, report, attempts)
        }
        assertTrue("All cameras/runners must actually drain", report.optBoolean("allSessionsClosed"))
        assertTrue("Final real detector must close", report.optBoolean("finalDetectorActualClosed"))
        assertTrue("Baseline owner executor must terminate", report.optBoolean("baselineExecutorTerminated"))
        assertTrue("Host must detach and destroy", !report.has("hostCallbackDetachFailureType") && !report.has("hostDestroyFailureType"))
    }

    private class Attempt(
        val name: String,
        context: Context,
        private val host: RuntimeCalibrationTestHostActivity,
        config: TwoModelRuntimeConfig,
        private val currentCandidate: AtomicReference<RuntimeCandidate>,
        private val baselineExecutor: ExecutorService,
        callbackExecutor: Executor,
        binding: String,
        private val scope: RuntimeFeatureScope,
        val budgetMs: Long,
    ) {
        private val current = AtomicBoolean(true)
        private val closedLatch = CountDownLatch(1)
        private val closedCount = AtomicInteger()
        private val finishedCount = AtomicInteger()
        private val drainedCount = AtomicInteger()
        private val selectionCount = AtomicInteger()
        private val adoptedCount = AtomicInteger()
        private val oldDetectorClosedCount = AtomicInteger()
        private val callbackError = AtomicReference<String?>(null)
        private val dataLock = Any()
        private val stages = JSONArray()
        private val environments = linkedSetOf<String>()
        private val observedFrameTimestamps = hashSetOf<Long>()
        private val startedAtMs = now()
        private var closedAtMs = 0L
        private var lastLogAtMs = 0L
        @Volatile private var lastStage = "WAITING_FOR_ACTUAL_CAMERA_EVIDENCE"
        @Volatile private var result: RuntimeCalibrationResult? = null
        @Volatile private var runner: AndroidRuntimeCalibrationRunner? = null
        @Volatile private var requestedCancellation: String? = null
        private val liveCallbackCount = AtomicInteger()
        private val liveFramesWithObjects = AtomicInteger()
        private val liveDetectedObjectTotal = AtomicInteger()
        private val liveDetectedObjectMax = AtomicInteger()
        private val metricCallbackCount = AtomicInteger()
        private val poseCallbackCount = AtomicInteger()
        val cancelledWithImage = AtomicBoolean(false)
        private val nativeInvocationObservedAtCancellation = AtomicBoolean(false)
        val closeVerified = AtomicBoolean(false)
        val session = AndroidRuntimeCalibrationSession(
            activity = host,
            bindingHash = binding,
            featureScope = scope,
            speechActive = { false },
            runnerFactory = { environment, progress, finished, live ->
                val borrowed = host.borrowDetector()
                val baseline = currentCandidate.get()
                AndroidRuntimeCalibrationRunner(
                    context = context, config = config, baselineDetector = borrowed,
                    baselineExecutor = baselineExecutor, baselineCandidate = baseline,
                    bindingHash = binding, featureScope = scope,
                    preprocessingStrategy = YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR,
                    environmentProvider = environment, isCurrent = { current.get() },
                    callbackExecutor = callbackExecutor,
                    onProgress = { value ->
                        progress(value)
                        lastStage = value.stage.name
                        synchronized(dataLock) { stages.put(JSONObject()
                            .put("stage", value.stage.name).put("elapsedMs", value.elapsedMs)
                            .put("budgetMs", value.budgetMs)
                            .put("candidate", value.candidate?.let(::candidateJson) ?: JSONObject.NULL)) }
                    },
                    onSelectionReady = { selection ->
                        selectionCount.incrementAndGet()
                        baselineExecutor.execute {
                            try {
                                val transferred = selection.tryAdopt { selected ->
                                    selected.detector?.let { host.replaceDetector(borrowed, it) } ?: false
                                }
                                if (transferred) {
                                    adoptedCount.incrementAndGet()
                                    currentCandidate.set(selection.profile.candidate)
                                    if (host.borrowDetector() !== borrowed) {
                                        borrowed.close()
                                        check(readField(borrowed, "closed") == true)
                                        oldDetectorClosedCount.incrementAndGet()
                                    }
                                }
                            } catch (error: Throwable) {
                                callbackError.set(error.javaClass.simpleName)
                                selection.reject()
                            }
                        }
                    },
                    onFinished = { value ->
                        result = value
                        finishedCount.incrementAndGet()
                        finished(value)
                    },
                    onLiveDetection = { detection, evidence ->
                        liveCallbackCount.incrementAndGet()
                        val objectCount = detection.detections.size
                        if (objectCount > 0) liveFramesWithObjects.incrementAndGet()
                        liveDetectedObjectTotal.addAndGet(objectCount)
                        liveDetectedObjectMax.updateAndGet { previous -> maxOf(previous, objectCount) }
                        if (evidence.poseValid) poseCallbackCount.incrementAndGet()
                        if (evidence.depthTimestampFresh && evidence.positiveDepthSamples > 0)
                            metricCallbackCount.incrementAndGet()
                        live(detection, evidence)
                    },
                    // The host produces no speech. No camera/environment evidence is substituted.
                    speechActive = { false }, budgetMs = budgetMs,
                ).also { value ->
                    runner = value
                    value.whenDrained { drainedCount.incrementAndGet() }
                }
            },
            onClosed = { value ->
                result = value
                closedAtMs = now()
                closedCount.incrementAndGet()
                current.set(false)
                closedLatch.countDown()
            },
            budgetMs = budgetMs,
        )

        fun isClosed() = closedLatch.count == 0L

        fun imageInFlight(): Boolean = runner?.let { value ->
            synchronized(requireNotNull(readField(value, "monitor"))) { readField(value, "imageInFlight") == true }
        } ?: false

        fun cancel(reason: String) {
            if (isClosed()) return
            if (imageInFlight()) {
                cancelledWithImage.set(true)
                // Read-only snapshot at cancellation, outside the performance measurement path.
                // Image ownership alone does not prove native inference has started.
                val invoking = Thread.getAllStackTraces().values.any { frames ->
                    frames.any { it.className.startsWith(
                        "kr.co.hanium.dreamup.walksafe.inference.TfliteAndroidFrameDetector") } &&
                        frames.any { it.className.startsWith("org.tensorflow.lite.") &&
                            (it.methodName == "run" || it.methodName.startsWith("invoke")) }
                }
                if (invoking) nativeInvocationObservedAtCancellation.set(true)
            }
            requestedCancellation = requestedCancellation ?: reason
            current.set(false)
            session.cancel(reason)
        }

        fun sample(): Boolean {
            synchronized(requireNotNull(readField(session, "trackerLock"))) {
                val frameKeys = readField(session, "frameKeys") as Map<*, *>
                synchronized(dataLock) { frameKeys.keys.filterIsInstance<Long>().forEach(observedFrameTimestamps::add) }
            }
            val method = session.javaClass.getDeclaredMethod("environment").apply { isAccessible = true }
            val environment = method.invoke(session) as RuntimeComparisonEnvironment
            synchronized(dataLock) { environments += environmentJson(environment).toString() }
            val elapsed = now() - startedAtMs
            if (elapsed - lastLogAtMs >= 10_000L) {
                lastLogAtMs = elapsed
                Log.i(TAG, "$name elapsedMs=$elapsed stage=$lastStage camera=${environment.cameraState} " +
                    "ar=${environment.arCoreState} liveCallbacks=${liveCallbackCount.get()} " +
                    "metricCallbacks=${metricCallbackCount.get()} imageInFlight=${imageInFlight()}")
                return true
            }
            return false
        }

        fun verifyClosed() {
            assertTrue("$name actual session close callback required", isClosed())
            assertEquals("$name duplicate close callback", 1, closedCount.get())
            assertEquals("$name camera close state", "CLOSED", readField(session, "cameraState"))
            assertEquals(true, readField(session, "closed"))
            assertEquals(true, readField(session, "runnerDrained"))
            for (field in listOf("arSession", "arProvider", "cameraXCamera", "cameraXInfo", "cameraXAnalysis", "cameraXPreview", "cameraXProvider"))
                assertEquals("$name retained $field", null, readField(session, field))
            verifyExecutorStopped(session, "cameraWorker")
            verifyExecutorStopped(session, "deadlineWorker")
            runner?.let { value ->
                assertEquals(1, finishedCount.get())
                // Callback executor ordering is drained independently of camera closure.
                val until = now() + 5_000L
                while (drainedCount.get() == 0 && now() < until) SystemClock.sleep(10L)
                assertEquals(1, drainedCount.get())
                synchronized(requireNotNull(readField(value, "monitor"))) {
                    assertEquals(true, readField(value, "drained"))
                    assertEquals(false, readField(value, "imageInFlight"))
                    assertEquals(null, readField(value, "liveImage"))
                    assertEquals(null, readField(value, "candidateLoad"))
                    assertEquals(false, readField(value, "imageReleaseFailed"))
                    assertEquals(false, readField(value, "candidateReleaseFailed"))
                }
                verifyExecutorStopped(value, "worker")
                verifyExecutorStopped(value, "deadlineTimer")
            }
            // Flush an accepted swap/old-A close on its original owner before a new B is allowed.
            baselineExecutor.submit {}.get(NATIVE_DRAIN_ALLOWANCE_MS, TimeUnit.MILLISECONDS)
            assertEquals("$name adoption/native close callback failed", null, callbackError.get())
            closeVerified.set(true)
        }

        fun verifyDecisionEvidence() {
            val value = result ?: return // No runner means no selection status exists to invent.
            if (value.status == RuntimeSelectionStatus.CONFIRMED) {
                val summary = requireNotNull(value.summary)
                assertTrue(summary.fixture.outputEquivalent)
                assertTrue(summary.fixture.completedCaseIds.containsAll(summary.fixture.requiredCaseIds))
                assertTrue(summary.fixture.requiredCaseIds.isNotEmpty())
                assertTrue(summary.baselineLive.validCompletions > 0 && summary.candidateLive.validCompletions > 0)
                if (scope == RuntimeFeatureScope.CAMERA_TRACKING_METRIC_DEPTH) {
                    for (live in listOf(summary.baselineLive, summary.candidateLive)) {
                        assertTrue(live.poseValid && live.depthTimestampFresh && live.positiveDepthSamples > 0)
                    }
                }
            } else {
                assertTrue("Incomplete selection must not adopt a detector", !value.adopted && adoptedCount.get() == 0)
            }
            if (value.adopted) assertEquals(1, adoptedCount.get())
        }

        fun json(): JSONObject = synchronized(dataLock) {
            val value = result
            JSONObject().put("name", name).put("featureScope", scope.name).put("budgetMs", budgetMs)
                .put("elapsedMs", (if (isClosed()) closedAtMs else now()) - startedAtMs)
                .put("runnerStarted", runner != null).put("stage", lastStage)
                .put("stages", JSONArray(stages.toString()))
                .put("observedEnvironments", JSONArray(environments.map { JSONObject(it) }))
                .put("sampledCameraTimestampCountLowerBound", observedFrameTimestamps.size)
                .put("cameraCountMeaning", "distinct timestamps sampled from the actual private tracker; not total frames")
                .put("liveDetectionCallbacks", liveCallbackCount.get())
                .put("liveFramesWithObjects", liveFramesWithObjects.get())
                .put("liveDetectedObjectTotal", liveDetectedObjectTotal.get())
                .put("liveDetectedObjectMax", liveDetectedObjectMax.get())
                .put("liveCallbacksWithValidPose", poseCallbackCount.get())
                .put("liveCallbacksWithFreshPositiveDepth", metricCallbackCount.get())
                .put("status", value?.status?.name ?: "NOT_EVALUATED")
                .put("reason", value?.reason ?: JSONObject.NULL)
                .put("observationLimit", if (value == null) "No runner result; inspect actual camera/AR states" else JSONObject.NULL)
                .put("requestedCancellation", requestedCancellation ?: JSONObject.NULL)
                .put("budgetElapsedObserved", (if (isClosed()) closedAtMs else now()) - startedAtMs >= budgetMs)
                .put("resultDeadlineReason", value?.reason == "deadline")
                .put("cancelledWithActualImageInFlight", cancelledWithImage.get())
                .put("nativeInvocationObservedImmediatelyBeforeCancellation", nativeInvocationObservedAtCancellation.get())
                .put("nativeCancellationCoverage", if (nativeInvocationObservedAtCancellation.get())
                    "INVOKE_STACK_OBSERVED_BEFORE_CANCEL" else "NOT_EXERCISED_OR_NOT_OBSERVABLE")
                .put("nativeInstancePeakCoverage", "A_B_OWNERSHIP_PATH_CHECKED; NOT_AN_EXHAUSTIVE_NATIVE_ALLOCATION_CENSUS")
                .put("closeCallbacks", closedCount.get()).put("runnerFinishedCallbacks", finishedCount.get())
                .put("runnerDrainCallbacks", drainedCount.get()).put("closeVerified", closeVerified.get())
                .put("selectionOffers", selectionCount.get()).put("actualReferenceAdoptions", adoptedCount.get())
                .put("replacedBaselineActualCloses", oldDetectorClosedCount.get())
                .put("adoptionCoverage", if (selectionCount.get() == 0) "NOT_EXERCISED" else "ACTUAL_TRY_ADOPT")
                .put("callbackFailureType", callbackError.get() ?: JSONObject.NULL)
                .put("pending", value?.pending ?: JSONObject.NULL)
                .put("selectedCandidate", value?.selectedProfile?.candidate?.let(::candidateJson) ?: JSONObject.NULL)
                .put("measurement", value?.summary?.let(::summaryJson) ?: JSONObject.NULL)
                .put("performanceConclusion", if (value?.status == RuntimeSelectionStatus.CONFIRMED)
                    "CONFIRMED_COMPARISON_ONLY_NOT_A_GENERAL_IMPROVEMENT_CLAIM" else "NO_PERFORMANCE_CONCLUSION")
        }
    }

    companion object {
        private const val TAG = "RuntimeCalibrationDeviceTest"
        private const val METRIC_BUDGET_MS = 300_000L
        private const val CANCELLATION_BUDGET_MS = 90_000L
        private const val NATIVE_DRAIN_ALLOWANCE_MS = 90_000L

        private fun now() = SystemClock.elapsedRealtime()
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }

        /** Read-only observability, deliberately fails if ownership fields change. */
        private fun readField(target: Any, name: String): Any? = target.javaClass
            .getDeclaredField(name).apply { isAccessible = true }.get(target)

        private fun verifyExecutorStopped(owner: Any, name: String) {
            val executor = readField(owner, name) as ExecutorService
            assertTrue("$name must be shut down", executor.isShutdown)
            assertTrue("$name must terminate after drain", executor.awaitTermination(5L, TimeUnit.SECONDS))
        }

        private fun waitFor(attempt: Attempt, timeoutMs: Long, report: JSONObject, attempts: List<Attempt>,
                            context: Context, condition: () -> Boolean) {
            val deadline = now() + timeoutMs
            while (!condition() && now() < deadline) {
                if (attempt.sample()) writeReport(context, report, attempts)
                SystemClock.sleep(20L)
            }
            attempt.sample()
            assertTrue("${attempt.name}: actual event not observed before test wait limit", condition())
        }

        private fun writeReport(context: Context, report: JSONObject, attempts: List<Attempt>) {
            report.put("attempts", JSONArray(attempts.map { it.json() }))
            File(context.filesDir, "runtime-calibration-device-report.json").writeText(report.toString(2))
        }

        private fun candidateJson(value: RuntimeCandidate) = JSONObject()
            .put("backend", value.backend.name).put("numThreads", value.numThreads)

        private fun environmentJson(value: RuntimeComparisonEnvironment) = JSONObject()
            .put("featureScope", value.featureScope.name).put("availableProcessors", value.availableProcessors)
            .put("thermalStatus", value.thermalStatus).put("powerSaveMode", value.powerSaveMode)
            .put("cameraState", value.cameraState).put("arCoreState", value.arCoreState)
            .put("cadenceMs", value.cadenceMs).put("workloadVersion", value.workloadVersion)

        private fun number(value: Double): Any = if (value.isFinite()) value else JSONObject.NULL

        private fun liveJson(value: RuntimeLiveMetrics) = JSONObject()
            .put("environment", environmentJson(value.environment)).put("actualBackend", value.actualBackend.name)
            .put("captureToCompleteMs", JSONArray(value.captureToCompleteMs.map(::number)))
            .put("observationDurationMs", value.observationDurationMs).put("validCompletions", value.validCompletions)
            .put("submittedFrames", value.submittedFrames).put("staleFrames", value.staleFrames)
            .put("droppedFrames", value.droppedFrames).put("uniqueCameraFrames", value.uniqueCameraFrames)
            .put("cameraFrameIntervalMs", number(value.cameraFrameIntervalMs))
            .put("trackingCostMs", number(value.trackingCostMs)).put("uiFrameDelayMs", number(value.uiFrameDelayMs))
            .put("longestCompletionGapMs", number(value.longestCompletionGapMs))
            .put("poseValid", value.poseValid).put("depthTimestampFresh", value.depthTimestampFresh)
            .put("positiveDepthSamples", value.positiveDepthSamples).put("adaptivePacing", value.adaptivePacing)

        private fun blockJson(value: RuntimeComparisonBlock) = JSONObject().put("order", value.order.name)
            .put("aaVariationMs", JSONArray(value.aaVariationMs.map(::number)))
            .put("pairs", JSONArray(value.pairs.map { pair -> JSONObject()
                .put("fixtureCaseId", pair.fixtureCaseId).put("baselineMs", number(pair.baselineMs))
                .put("candidateMs", number(pair.candidateMs)).put("baselineCompleted", pair.baselineCompleted)
                .put("candidateCompleted", pair.candidateCompleted).put("speechActive", pair.speechActive)
                .put("baselineEnvironment", environmentJson(pair.baselineEnvironment))
                .put("candidateEnvironment", environmentJson(pair.candidateEnvironment)) }))

        private fun summaryJson(value: RuntimeMeasurementSummary) = JSONObject()
            .put("ab", blockJson(value.ab)).put("ba", blockJson(value.ba))
            .put("confirmation", blockJson(value.confirmation))
            .put("baselineLive", liveJson(value.baselineLive)).put("candidateLive", liveJson(value.candidateLive))
            .put("fixture", JSONObject().put("version", value.fixture.version).put("hash", value.fixture.hash)
                .put("requiredCaseCount", value.fixture.requiredCaseCount)
                .put("completedCaseCount", value.fixture.completedCaseCount)
                .put("requiredCaseIds", JSONArray(value.fixture.requiredCaseIds.toList()))
                .put("completedCaseIds", JSONArray(value.fixture.completedCaseIds.toList()))
                .put("outputEquivalent", value.fixture.outputEquivalent)
                .put("baselineActualBackend", value.fixture.baselineActualBackend?.name ?: JSONObject.NULL)
                .put("candidateActualBackend", value.fixture.candidateActualBackend?.name ?: JSONObject.NULL)
                .put("failure", value.fixture.failure?.name ?: JSONObject.NULL))
            .put("clockResolutionMs", number(value.clockResolutionMs))
    }
}

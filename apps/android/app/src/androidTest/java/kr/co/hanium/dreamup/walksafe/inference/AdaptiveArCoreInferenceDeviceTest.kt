package kr.co.hanium.dreamup.walksafe.inference

import android.Manifest
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.media.Image
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ar.core.Session
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.depth.ArCoreFrameProvider
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.device.AndroidWalkSessionResourceProbe
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in production-component observation, without launching MainActivity or changing user state.
 * No images, pose coordinates, location, account data or danger speech are saved or emitted.
 * This measures scheduling/latency, not MainActivity E2E, distance truth or model accuracy.
 * The 18-second observation budget does not bound a stalled native call; a drain timeout is an
 * explicit incomplete-cleanup failure, never permission to close resources still used by a worker.
 */
@RunWith(AndroidJUnit4::class)
class AdaptiveArCoreInferenceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun observeAdaptiveProductionInferenceWithoutLaunchingMainActivity() {
        assumeTrue(
            "Requires -e allowAdaptiveArCoreInferenceTest true",
            InstrumentationRegistry.getArguments().getString("allowAdaptiveArCoreInferenceTest") == "true",
        )
        val durationMs = InstrumentationRegistry.getArguments().getString("adaptiveArCoreObservationMs")
            ?.toLongOrNull()?.coerceIn(15_000L, 20_000L) ?: 18_000L
        val context = instrumentation.targetContext
        val stats = ObservationStats()
        val pacing = AdaptiveInferencePacingPolicy(maximumFreshFrameAgeMs = FRESHNESS_MS)
        val latestFrameNs = AtomicLong()
        val retainedInferenceImages = AtomicInteger()
        val thermalThrottled = AtomicReference<Boolean?>(null)
        val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "walksafe-adaptive-device-test").apply { isDaemon = true }
        }
        // Only the worker accesses this variable, including after a model-load wait timeout.
        var detector: TfliteAndroidFrameDetector? = null
        val resources = AndroidWalkSessionResourceProbe(context)
        var session: Session? = null
        var provider: ArCoreFrameProvider? = null
        var egl: OffscreenEgl? = null
        var resumeAttempted = false
        var stage = "permission"
        var outcome = "SETUP_NOT_COMPLETED"
        var observationMs = 0L
        val cleanupFailures = linkedMapOf<String, String>()
        try {
            val process = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
            emit("setup", JSONObject().put("observationBudgetMs", durationMs)
                .put("modelLoadTimeoutMs", MODEL_LOAD_TIMEOUT_SECONDS * 1_000L)
                .put("preprocessingStrategy", YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR.name)
                .put("processImportance", process.importance).put("activityLaunched", false))
            if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                outcome = "CAMERA_PERMISSION_REQUIRED"
                return
            }
            stage = "detector_load"
            // Cold GPU compilation has a separate budget before the bounded camera observation starts.
            val loaded = worker.submit<AndroidDetectorLoadResult> {
                TfliteAndroidFrameDetector.createWithStatus(context,
                    preprocessingStrategy = YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR,
                ).also { detector = it.detector }
            }.get(MODEL_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            emit("detector", JSONObject().put("available", loaded.detectorAvailable)
                .put("modelKey", loaded.modelKey ?: JSONObject.NULL)
                .put("fallbackUsed", loaded.fallbackUsed).put("reason", loaded.reason))
            if (!loaded.detectorAvailable) {
                outcome = "DETECTOR_UNAVAILABLE"
                return
            }
            stage = "session_setup"
            val activeSession = Session(context).also { session = it }
            val activeProvider = ArCoreFrameProvider(activeSession).also { provider = it }
            val depthSupported = activeProvider.configureDepthMode()
            emit("support", JSONObject().put("automaticDepthSupported", depthSupported))
            val activeEgl = OffscreenEgl().also { egl = it }
            activeEgl.open()
            activeSession.setCameraTextureNames(intArrayOf(activeEgl.textureId))
            activeSession.setDisplayGeometry(0, 640, 480)
            stage = "session_resume"
            resumeAttempted = true
            onMain { activeSession.resume() }
            stage = "frame_capture"
            val startedAtMs = SystemClock.elapsedRealtime()
            var nextReportAtMs = startedAtMs
            var lastAdmittedFrameNs = 0L
            while (SystemClock.elapsedRealtime() - startedAtMs < durationMs) {
                val updateStartedNs = System.nanoTime()
                val frame = activeSession.update()
                stats.measure("frameUpdateMs", (System.nanoTime() - updateStartedNs) / 1_000_000.0)
                val frameNs = frame.timestamp
                latestFrameNs.set(frameNs)
                stats.increment("tracking_${frame.camera.trackingState.name}")
                val capturedAtMs = SystemClock.elapsedRealtime()
                val due = frameNs > lastAdmittedFrameNs && pacing.isDue(capturedAtMs)
                val captureStartedNs = System.nanoTime()
                val snapshot = activeProvider.acquireDepthSnapshot(frame, includeFullDepthWhenRawAvailable = due)
                stats.measure("depthCaptureMs", (System.nanoTime() - captureStartedNs) / 1_000_000.0)
                if (due) {
                    val ticket = pacing.tryStart(capturedAtMs)
                    if (ticket != null) {
                        var acquiredImage: Image? = null
                        var submitted = false
                        var imageCounted = false
                        try {
                            val image = activeProvider.acquireCameraImageOrNull(frame)
                            acquiredImage = image
                            if (image == null) {
                                stats.increment("cameraUnavailable")
                            } else {
                                val sourceAligned = stats.recordSource(frameNs, image.timestamp, snapshot)
                                val validMetricSamples = snapshot.validMetricSampleCount(0.35, 0.2, 8.0, requireFreshRaw = true)
                                stats.measure("validMetricSamples", validMetricSamples.toDouble())
                                lastAdmittedFrameNs = frameNs
                                val currentImages = retainedInferenceImages.incrementAndGet()
                                imageCounted = true
                                stats.measure("retainedInferenceImages", currentImages.toDouble())
                                stats.measure("pacingTargetIntervalMs", pacing.snapshot().targetIntervalMs.toDouble())
                                worker.execute {
                                    var inferenceMs: Long? = null
                                    var invoked = false
                                    var succeeded = false
                                    try {
                                        invoked = true
                                        val inferenceStartedMs = SystemClock.elapsedRealtime()
                                        val result = checkNotNull(detector).detect(image, frameNs / NANOS_PER_MS) {
                                            stats.increment("partialResults")
                                        }
                                        inferenceMs = result.timing.totalMs
                                            ?: (SystemClock.elapsedRealtime() - inferenceStartedMs)
                                        stats.increment("detectionCount", result.detections.size.toLong())
                                        stats.increment("successfulInferences")
                                        stats.measure("detectorTotalMs", inferenceMs.toDouble())
                                        result.timing.yuvDecodeMs?.let { stats.measure("yuvDecodeMs", it.toDouble()) }
                                        result.timing.modelPreprocessMs?.let { stats.measure("modelPreprocessMs", it.toDouble()) }
                                        result.timing.modelInferenceMs?.let { stats.measure("modelInferenceMs", it.toDouble()) }
                                        result.timing.modelParseMs?.let { stats.measure("modelParseMs", it.toDouble()) }
                                        result.timing.modelRuntime?.let { runtime ->
                                            stats.increment("activeDelegate_${runtime.activeDelegate}")
                                            stats.increment("configuredThreads_${runtime.numThreads}")
                                        }
                                        succeeded = true
                                    } catch (error: Exception) {
                                        stats.increment("inferenceErrors")
                                        stats.increment("inferenceError_${error.javaClass.simpleName}")
                                    } finally {
                                        try {
                                            image.close()
                                        } catch (error: Exception) {
                                            stats.increment("imageCloseErrors")
                                        } finally {
                                            retainedInferenceImages.decrementAndGet()
                                            val completedAtMs = SystemClock.elapsedRealtime()
                                            val endToEndMs = completedAtMs - ticket.startedAtElapsedRealtimeMs
                                            try {
                                                if (invoked) {
                                                    stats.measure("endToEndMs", endToEndMs.toDouble())
                                                    if (endToEndMs > FRESHNESS_MS) stats.increment("over800Ms")
                                                }
                                                val frameDeltaNs = latestFrameNs.get() - frameNs
                                                val fresh = endToEndMs in 0L..FRESHNESS_MS &&
                                                    frameDeltaNs in 0L..FRESHNESS_MS * NANOS_PER_MS
                                                if (succeeded && fresh) {
                                                    stats.increment("freshCompletedResults")
                                                    if (sourceAligned && validMetricSamples > 0) {
                                                        stats.increment("freshMetricSourceResults")
                                                    }
                                                }
                                                stats.measure("completionFrameDeltaMs", frameDeltaNs / 1_000_000.0)
                                            } finally {
                                                // Image work and statistics finish before admission can reopen.
                                                if (invoked) pacing.complete(ticket, SystemClock.elapsedRealtime(), inferenceMs, thermalThrottled.get())
                                                else pacing.cancel(ticket)
                                            }
                                        }
                                    }
                                }
                                submitted = true
                            }
                        } finally {
                            if (!submitted) {
                                try { acquiredImage?.close() } finally {
                                    if (imageCounted) retainedInferenceImages.decrementAndGet()
                                    pacing.cancel(ticket)
                                }
                            }
                        }
                    }
                } else stats.increment(if (pacing.snapshot().inFlight) "busyFrameSkipped" else "cadenceFrameSkipped")
                val nowMs = SystemClock.elapsedRealtime()
                observationMs = nowMs - startedAtMs
                if (nowMs >= nextReportAtMs) {
                    thermalThrottled.set(runCatching { resources.snapshot().thermalThrottled }.getOrNull())
                    emit("sample", stats.json().put("observationMs", nowMs - startedAtMs)
                        .put("pacing", pacingJson(pacing.snapshot())))
                    nextReportAtMs = nowMs + 1_000L
                }
                if (stats.count("inferenceErrors") > 0L) break
                SystemClock.sleep(5L)
            }
            observationMs = SystemClock.elapsedRealtime() - startedAtMs
            outcome = "OBSERVATION_FINISHED"
        } catch (error: Exception) {
            outcome = "BLOCKED_AT_$stage"
            emit("blocked", JSONObject().put("stage", stage).put("errorClass", error.javaClass.simpleName))
        } finally {
            // Do not cancel native inference or destroy its image while it is still running.
            worker.execute {
                try { detector?.close() } catch (error: Exception) { stats.increment("detectorCloseErrors") }
                finally { detector = null }
            }
            worker.shutdown()
            val joined = try { worker.awaitTermination(15L, TimeUnit.SECONDS) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            fun cleanup(name: String, operation: () -> Unit) {
                runCatching(operation).onFailure { cleanupFailures[name] = it.javaClass.simpleName }
            }
            if (joined) {
                cleanup("providerClose") { provider?.close() }
                if (resumeAttempted) cleanup("sessionPause") { session?.let { active -> onMain { active.pause() } } }
                cleanup("sessionClose") { session?.close() }
                cleanup("eglClose") { egl?.close() }
            } else {
                outcome = "WORKER_DRAIN_TIMEOUT"
                cleanupFailures["workerDrain"] = "ResourcesStillOwnedByWorker"
            }
            cleanup("resourceProbeClose") { resources.close() }
            if (joined && outcome == "OBSERVATION_FINISHED") {
                outcome = when {
                    stats.count("inferenceErrors") > 0L -> "INFERENCE_FAILED"
                    stats.count("successfulInferences") == 0L -> "OBSERVED_NO_INFERENCE_RESULTS"
                    stats.count("freshCompletedResults") == 0L -> "OBSERVED_ONLY_STALE_INFERENCE_RESULTS"
                    else -> "OBSERVED_INFERENCE_WITH_FRESH_RESULTS"
                }
            }
            emit("summary", stats.json().put("outcome", outcome).put("workerTerminated", joined)
                .put("observationMs", observationMs)
                .put("maxInFlight", stats.maximum("retainedInferenceImages").toInt())
                .put("retainedInferenceImagesAtEnd", retainedInferenceImages.get())
                .put("pacing", pacingJson(pacing.snapshot()))
                .put("cleanupFailures", JSONObject(cleanupFailures as Map<*, *>))
                .put("freshnessLimitMs", FRESHNESS_MS).put("stationaryFloorMayPreventDepth", true)
                .put("mainRuntimeValidated", false).put("metricAccuracyValidated", false)
                .put("modelAccuracyValidated", false).put("dangerSpeechEmitted", false))
            assertTrue("Worker did not terminate; shared resources were not forcibly closed", joined)
            assertTrue("Cleanup failed: $cleanupFailures", cleanupFailures.isEmpty())
            assertTrue("Observation did not complete: $outcome", outcome.startsWith("OBSERVED_"))
            assertTrue("Minimum observation budget was not completed: $observationMs", observationMs >= 15_000L)
            assertTrue("No production inference succeeded: $outcome", stats.count("successfulInferences") > 0L)
            assertEquals(0, retainedInferenceImages.get())
            assertTrue("More than one retained inference image", stats.maximum("retainedInferenceImages") <= 1.0)
            assertEquals(0L, stats.count("inferenceErrors") + stats.count("imageCloseErrors") + stats.count("detectorCloseErrors"))
            assertEquals(0L, stats.count("frameSourceMismatch") + stats.count("cpuSourceMismatch") + stats.count("poseSourceMismatch"))
        }
    }

    private fun emit(kind: String, values: JSONObject) {
        val line = values.put("kind", kind).toString()
        Log.i("WalkSafeAdaptiveTest", line)
        instrumentation.sendStatus(0, Bundle().apply { putString("walksafe_adaptive_arcore_inference", line) })
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private fun pacingJson(value: InferencePacingSnapshot): JSONObject = JSONObject()
        .put("targetIntervalMs", value.targetIntervalMs).put("cooldownMs", value.cooldownMs)
        .put("nextEligibleAtElapsedRealtimeMs", value.nextEligibleAtElapsedRealtimeMs)
        .put("lastEndToEndMs", value.lastEndToEndMs ?: JSONObject.NULL)
        .put("lastDetectorTotalMs", value.lastInferenceMs ?: JSONObject.NULL)
        .put("thermalThrottled", value.thermalThrottled ?: JSONObject.NULL)
        .put("thermalHoldActive", value.thermalHoldActive).put("completedSamples", value.completedSamples)
        .put("overFreshnessBudget", value.overFreshnessBudget ?: JSONObject.NULL).put("reason", value.reason.name)

    private class ObservationStats {
        private val counts = linkedMapOf<String, Long>()
        private val measurements = linkedMapOf<String, DoubleArray>()
        @Synchronized fun increment(key: String, amount: Long = 1L) { counts[key] = count(key) + amount }
        @Synchronized fun count(key: String): Long = counts[key] ?: 0L
        @Synchronized fun maximum(key: String): Double = measurements[key]?.get(2) ?: 0.0
        @Synchronized fun measure(key: String, value: Double) {
            val data = measurements.getOrPut(key) { doubleArrayOf(0.0, 0.0, Double.NEGATIVE_INFINITY) }
            data[0] += 1.0
            data[1] += value
            data[2] = maxOf(data[2], value)
        }
        fun recordSource(frameNs: Long, cpuImageNs: Long, snapshot: DepthFrameSnapshot): Boolean {
            val frameMatches = snapshot.frameTimestampNs == frameNs
            increment(if (frameMatches) "frameSourceMatches" else "frameSourceMismatch")
            val cpuAnchor = snapshot.cameraImageTimestampNs
            val cpuMatches = cpuImageNs > 0L && cpuAnchor == cpuImageNs
            increment(when {
                cpuAnchor == null || cpuAnchor <= 0L || cpuImageNs <= 0L -> "cpuSourceUnknown"
                cpuMatches -> "cpuSourceMatches"
                else -> "cpuSourceMismatch"
            })
            snapshot.cameraPoseEvidence?.let {
                increment(if (it.timestampMs == frameNs / NANOS_PER_MS) "poseSourceMatches" else "poseSourceMismatch")
            } ?: increment("poseUnavailable")
            increment(if (snapshot.hasFreshMetricRawDepth) "rawMatchesCpuSource" else "rawNotFreshOrUnavailable")
            increment(if (snapshot.hasFreshFullDepth) "fullMatchesCpuSource" else "fullNotFreshOrUnavailable")
            measure("positiveRawDepthPixels", (snapshot.rawDepth?.millimeters?.count { it > 0 } ?: 0).toDouble())
            measure("positiveFullDepthPixels", (snapshot.fullDepth?.millimeters?.count { it > 0 } ?: 0).toDouble())
            return frameMatches && cpuMatches
        }
        @Synchronized fun json(): JSONObject = JSONObject().put("counts", JSONObject(counts as Map<*, *>))
            .put("measurements", JSONObject().apply {
                measurements.forEach { (key, values) -> put(key, JSONObject()
                    .put("samples", values[0].toLong()).put("mean", values[1] / values[0]).put("max", values[2])) }
            })
    }

    // EGL implementation copied from ArCoreFrameTimingDeviceTest; this thread owns every update.
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

    private companion object {
        const val MODEL_LOAD_TIMEOUT_SECONDS = 45L
        const val FRESHNESS_MS = 800L
        const val NANOS_PER_MS = 1_000_000L
    }
}

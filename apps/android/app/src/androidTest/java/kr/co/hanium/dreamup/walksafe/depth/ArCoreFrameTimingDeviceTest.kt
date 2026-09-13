package kr.co.hanium.dreamup.walksafe.depth

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
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.io.Closeable
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit opt-in, offscreen ARCore observation. No Activity, account/preferences, images on disk,
 * detector, or walk state is used. A completed observation is not a Depth-quality or walking PASS.
 * Background camera/sensor restrictions may prevent capture; the summary reports that separately.
 */
@RunWith(AndroidJUnit4::class)
class ArCoreFrameTimingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun observeProductionDepthFramesWithoutLaunchingMainActivity() {
        assumeTrue(
            "Requires -e allowArCoreFrameTimingTest true",
            InstrumentationRegistry.getArguments().getString("allowArCoreFrameTimingTest") == "true",
        )
        val observationDurationMs = InstrumentationRegistry.getArguments()
            .getString("arCoreObservationMs")?.toLongOrNull()?.coerceIn(5_000L, 60_000L) ?: DURATION_MS
        val context = instrumentation.targetContext
        val stats = FrameStats()
        var session: Session? = null
        var provider: ArCoreFrameProvider? = null
        var egl: OffscreenEgl? = null
        var resumeAttempted = false
        var stage = "permission"
        var outcome = "SETUP_NOT_COMPLETED"
        val cleanupFailures = linkedMapOf<String, String>()
        try {
            val process = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
            emit("setup", JSONObject()
                .put("processImportance", process.importance)
                .put("arCoreAvailability", ArCoreApk.getInstance().checkAvailability(context).name)
                .put("durationMs", observationDurationMs)
                .put("activityLaunched", false)
                .put("inferenceIncluded", false))
            if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                outcome = "CAMERA_PERMISSION_REQUIRED"
                return
            }
            stage = "session_create"
            val activeSession = Session(context).also { session = it }
            val activeProvider = ArCoreFrameProvider(activeSession).also { provider = it }
            stage = "configure_depth"
            val supported = activeProvider.configureDepthMode()
            emit("support", JSONObject().put("automaticDepthSupported", supported))
            if (!supported) {
                outcome = "DEPTH_MODE_EXPLICITLY_UNSUPPORTED"
                return
            }
            stage = "egl_setup"
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
            while (SystemClock.elapsedRealtime() - startedAtMs < observationDurationMs) {
                val updateStartedNs = System.nanoTime()
                val frame = activeSession.update()
                stats.updateTimeNs += System.nanoTime() - updateStartedNs
                stats.updates += 1
                val timestamp = frame.timestamp
                val androidCameraTimestamp = frame.androidCameraTimestamp
                if (timestamp == 0L) stats.increment("zeroFrames")
                else when {
                    timestamp == stats.lastFrameNs -> stats.increment("duplicateFrames")
                    timestamp < stats.lastFrameNs -> stats.increment("regressedFrames")
                    else -> stats.increment("distinctFrames")
                }
                stats.lastFrameNs = timestamp
                stats.increment("tracking_${frame.camera.trackingState.name}")
                if (frame.camera.trackingState != TrackingState.TRACKING) {
                    stats.increment("trackingReason_${frame.camera.trackingFailureReason.name}")
                }
                val captureStartedNs = System.nanoTime()
                val snapshot = activeProvider.acquireDepthSnapshot(frame, includeFullDepthWhenRawAvailable = true)
                val cameraTimestamp = activeProvider.acquireCameraImageOrNull(frame)?.use { it.timestamp }
                stats.captureTimeNs += System.nanoTime() - captureStartedNs
                stats.recordTimestamps(timestamp, androidCameraTimestamp, cameraTimestamp, snapshot)
                val pixelCounts = stats.recordDepthPixels(snapshot)
                val samples = snapshot.validMetricSampleCount(0.35, 0.2, 8.0)
                // Compare the old clock policy using the exact same captured pixels. Diagnostic only.
                val legacySamples = snapshot.copy(cameraImageTimestampNs = timestamp)
                    .validMetricSampleCount(0.35, 0.2, 8.0)
                stats.maxValidSamples = maxOf(stats.maxValidSamples, samples)
                if (samples >= 30) stats.increment("framesWithThirtyMetricSamples")
                if (legacySamples >= 30) stats.increment("framesWithThirtyMetricSamplesUsingLegacyClock")
                if (samples >= 30 && legacySamples < 30) stats.increment("framesRecoveredByCpuClock")
                val nowMs = SystemClock.elapsedRealtime()
                stats.observationMs = nowMs - startedAtMs
                if (nowMs >= nextReportAtMs) {
                    // The production provider intentionally returns null for acquisition failures.
                    // Sample separate retry outcomes; these are not the original call's exceptions.
                    if (cameraTimestamp == null) diagnoseRetry("camera", stats) { frame.acquireCameraImage() }
                    if (!snapshot.hasMetricRawDepth) {
                        diagnoseRetry("raw", stats) { frame.acquireRawDepthImage16Bits() }
                        diagnoseRetry("confidence", stats) { frame.acquireRawDepthConfidenceImage() }
                    }
                    if (!snapshot.hasFullDepth) diagnoseRetry("full", stats) { frame.acquireDepthImage16Bits() }
                    emit("sample", stats.json()
                        .put("frameNs", timestamp)
                        .put("androidCameraNs", androidCameraTimestamp)
                        .put("cameraImageNs", cameraTimestamp ?: JSONObject.NULL)
                        .put("snapshotCameraImageNs", snapshot.cameraImageTimestampNs ?: JSONObject.NULL)
                        .put("rawDepthNs", snapshot.rawDepthTimestampNs ?: JSONObject.NULL)
                        .put("fullDepthNs", snapshot.fullDepthTimestampNs ?: JSONObject.NULL)
                        .put("tracking", frame.camera.trackingState.name)
                        .put("depthPixelsBeforeTimestampPolicy", pixelCounts)
                        .put("diagnosticLegacyClockMetricSamples", legacySamples)
                        .put("validMetricSamples", samples))
                    nextReportAtMs = nowMs + 1_000L
                }
                // Avoid a tight loop while ARCore returns an initial/unchanged frame.
                SystemClock.sleep(5L)
            }
            outcome = when {
                stats.count("cameraImages") == 0 -> "OBSERVED_NO_CAMERA_IMAGES"
                stats.count("framesWithThirtyMetricSamples") == 0 -> "OBSERVED_CAMERA_WITHOUT_USABLE_DEPTH"
                else -> "OBSERVED_CAMERA_AND_METRIC_DEPTH"
            }
        } catch (error: Exception) {
            outcome = "BLOCKED_AT_$stage"
            stats.increment("exception_${error.javaClass.simpleName}")
            emit("blocked", JSONObject().put("stage", stage).put("errorClass", error.javaClass.simpleName))
        } finally {
            fun cleanup(name: String, operation: () -> Unit) {
                runCatching(operation).onFailure { cleanupFailures[name] = it.javaClass.simpleName }
            }
            cleanup("providerClose") { provider?.close() }
            if (resumeAttempted) cleanup("sessionPause") { session?.let { active -> onMain { active.pause() } } }
            cleanup("sessionClose") { session?.close() }
            cleanup("eglClose") { egl?.close() }
            emit("summary", stats.json()
                .put("outcome", outcome)
                .put("cleanupFailures", JSONObject(cleanupFailures as Map<*, *>))
                .put("stationaryFloorMayPreventDepth", true)
                .put("mainRuntimeValidated", false))
            assertTrue("ARCore/EGL cleanup failed: $cleanupFailures", cleanupFailures.isEmpty())
        }
    }

    private fun diagnoseRetry(source: String, stats: FrameStats, acquire: () -> Image) {
        try {
            acquire().use { stats.increment("diagnosticRetry_${source}_available") }
        } catch (error: Exception) {
            stats.increment("diagnosticRetry_${source}_${error.javaClass.simpleName}")
        }
    }

    private fun emit(kind: String, values: JSONObject) {
        val line = values.put("kind", kind).toString()
        Log.i(LOG_TAG, line)
        instrumentation.sendStatus(0, Bundle().apply { putString(STATUS_KEY, line) })
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private class FrameStats {
        private val counts = linkedMapOf<String, Int>()
        var updates = 0
        var observationMs = 0L
        var lastFrameNs = 0L
        var updateTimeNs = 0L
        var captureTimeNs = 0L
        var maxValidSamples = 0
        private var lastRawNs: Long? = null
        private var lastFullNs: Long? = null
        private val maximumDepthPixels = linkedMapOf<String, Int>()

        fun count(key: String): Int = counts[key] ?: 0
        fun increment(key: String) { counts[key] = count(key) + 1 }

        fun recordTimestamps(frameNs: Long, androidCameraNs: Long, cameraNs: Long?, snapshot: DepthFrameSnapshot) {
            increment(when {
                androidCameraNs <= 0L -> "androidCameraTimestampUnavailable"
                androidCameraNs == frameNs -> "androidCameraMatchesFrame"
                else -> "androidCameraDiffersFromFrame"
            })
            if (cameraNs == null) increment("cameraMissing") else {
                increment("cameraImages")
                increment(when {
                    cameraNs == 0L -> "cameraZeroTimestamp"
                    cameraNs == frameNs -> "cameraMatchesFrame"
                    else -> "cameraDiffersFromFrame"
                })
                increment(if (androidCameraNs > 0L && cameraNs == androidCameraNs)
                    "cameraMatchesAndroidCamera" else "cameraDiffersFromAndroidCamera")
                increment(if (cameraNs > 0L && cameraNs == snapshot.cameraImageTimestampNs)
                    "snapshotAnchorMatchesCameraImage" else "snapshotAnchorDiffersFromCameraImage")
            }
            if (!snapshot.hasMetricRawDepth) increment("rawPairMissing") else {
                increment("rawPairs")
                if (frameNs > 0L && snapshot.rawDepthTimestampNs == frameNs) increment("rawMatchesFrame")
                else increment("rawDiffersFromFrame")
                increment(if (androidCameraNs > 0L && snapshot.rawDepthTimestampNs == androidCameraNs)
                    "rawMatchesAndroidCamera" else "rawDiffersFromAndroidCamera")
                if (cameraNs != null && cameraNs > 0L) increment(if (snapshot.rawDepthTimestampNs == cameraNs)
                    "rawMatchesCameraImage" else "rawDiffersFromCameraImage")
                snapshot.rawDepthTimestampNs?.let {
                    if (it != lastRawNs) increment("rawTimestampChanges")
                    if (lastRawNs?.let { previous -> it < previous } == true) increment("rawTimestampRegressions")
                    lastRawNs = it
                }
            }
            if (!snapshot.hasFullDepth) increment("fullMissing") else {
                increment("fullImages")
                val fullNs = snapshot.fullDepthTimestampNs
                increment(when {
                    fullNs == null -> "fullTimestampUnknown"
                    fullNs == 0L -> "fullZeroTimestamp"
                    fullNs == frameNs -> "fullMatchesFrame"
                    fullNs < frameNs -> "fullOlderThanFrame"
                    else -> "fullNewerThanFrame"
                })
                increment(if (androidCameraNs > 0L && fullNs == androidCameraNs)
                    "fullMatchesAndroidCamera" else "fullDiffersFromAndroidCamera")
                if (cameraNs != null && cameraNs > 0L) increment(if (fullNs == cameraNs)
                    "fullMatchesCameraImage" else "fullDiffersFromCameraImage")
                if (fullNs != null && fullNs != lastFullNs) increment("fullTimestampChanges")
                if (fullNs != null && lastFullNs?.let { fullNs < it } == true) increment("fullTimestampRegressions")
                lastFullNs = fullNs
            }
        }

        fun recordDepthPixels(snapshot: DepthFrameSnapshot): JSONObject {
            val values = linkedMapOf<String, Int>()
            fun record(source: String, depth: DepthImage16?) {
                if (depth == null) return
                values["${source}Positive"] = depth.millimeters.count { it > 0 }
                values["${source}InRange200To8000Mm"] = depth.millimeters.count { it in 200..8_000 }
            }
            // Raw pixel counts here deliberately omit confidence and all timestamp policies.
            record("raw", snapshot.rawDepth)
            record("full", snapshot.fullDepth)
            values.forEach { (key, value) ->
                maximumDepthPixels[key] = maxOf(maximumDepthPixels[key] ?: 0, value)
                if (value > 0) increment("framesWith_$key")
            }
            return JSONObject(values as Map<*, *>)
        }

        fun json(): JSONObject = JSONObject()
            .put("observationMs", observationMs)
            .put("updates", updates)
            .put("counts", JSONObject(counts as Map<*, *>))
            .put("meanUpdateMs", if (updates == 0) 0.0 else updateTimeNs / updates / 1_000_000.0)
            .put("meanProviderAndCameraAcquireMs", if (updates == 0) 0.0 else captureTimeNs / updates / 1_000_000.0)
            .put("maxValidMetricSamples", maxValidSamples)
            .put("maxDepthPixelsBeforeTimestampPolicy", JSONObject(maximumDepthPixels as Map<*, *>))
    }

    /** The test thread owns this EGL context and all Session.update calls. */
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
        const val DURATION_MS = 25_000L
        const val LOG_TAG = "WalkSafeFrameTiming"
        const val STATUS_KEY = "walksafe_arcore_frame_timing"
    }
}

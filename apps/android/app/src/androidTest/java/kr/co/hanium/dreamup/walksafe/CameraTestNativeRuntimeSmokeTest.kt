package kr.co.hanium.dreamup.walksafe

import android.Manifest
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectorLoadPhase
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectorLoadTiming
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectorPhaseOutcome
import kr.co.hanium.dreamup.walksafe.inference.TfliteAndroidFrameDetector
import kr.co.hanium.dreamup.walksafe.inference.TwoModelRuntimeConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Run only on a dedicated temporary emulator with fresh userdata and preinstalled AR services.
 * Opens the real nonexported debug Activity in the target UID; no account or product gates change.
 * Native execution and resource release are observed twice. Depth, detector accuracy, OpenCV LK,
 * physical output and field performance remain separate tests.
 */
@RunWith(AndroidJUnit4::class)
class CameraTestNativeRuntimeSmokeTest {
    @Test
    fun realRgbInferenceReopensAndClosesNativeResources() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("Dedicated emulator required", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertTrue("CameraTestActivity belongs to the debug app", BuildConfig.DEBUG)
        assertEquals("Launch and permission preparation must use the target UID",
            context.applicationInfo.uid, Process.myUid())
        val activityInfo = context.packageManager.getActivityInfo(
            ComponentName(context, CameraTestActivity::class.java), 0)
        assertFalse("The real CameraTestActivity must remain nonexported", activityInfo.exported)
        val arService = context.packageManager.getPackageInfo("com.google.ar.core", 0)
        assertTrue("Preinstall and enable Google Play Services for AR",
            requireNotNull(arService.applicationInfo).enabled)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.CAMERA))
        emit(instrumentation, JSONObject().put("stage", "preflight")
            .put("pid", Process.myPid()).put("uid", Process.myUid())
            .put("hardware", Build.HARDWARE).put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("arServiceVersion", arService.versionName).put("cameraGranted", true))

        var openedActivity: CameraTestActivity? = null
        var previousSessionEpoch = 0L
        var previousLoadGeneration = 0L
        var testFailure: Throwable? = null
        try {
            for (ordinal in 1..2) {
                val activity = instrumentation.startActivitySync(
                    Intent(context, CameraTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ) as CameraTestActivity
                openedActivity = activity
                val running = awaitRunning(instrumentation, activity)
                assertTrue("Reentry needs a new ARCore Session epoch", running.sessionEpoch > previousSessionEpoch)
                assertTrue("Reentry needs a new native model load", running.timing.loadGeneration > previousLoadGeneration)
                val startup = assertCameraStages(running.sessionEpoch, listOf("session_create", "session_resume"))
                emit(instrumentation, timingEvidence(ordinal, "rgb_inference", running)
                    .put("frameId", running.frameId).put("geometryId", running.geometryId)
                    .put("cameraStages", JSONArray(startup)))
                onMain(instrumentation) { activity.finish() }
                awaitClosed(instrumentation, activity, running)
                val shutdown = assertCameraStages(running.sessionEpoch,
                    listOf("session_create", "session_resume", "session_pause", "provider_close", "session_close"))
                emit(instrumentation, timingEvidence(ordinal, "native_closed", running)
                    .put("activityDestroyed", true).put("workerTerminated", true).put("inFlightBusy", false)
                    .put("cameraStages", JSONArray(shutdown)))
                previousSessionEpoch = running.sessionEpoch
                previousLoadGeneration = running.timing.loadGeneration
                openedActivity = null
            }
        } catch (failure: Throwable) {
            testFailure = failure
            throw failure
        } finally {
            openedActivity?.let { activity ->
                try {
                    onMain(instrumentation) { if (!activity.isDestroyed) activity.finish() }
                    awaitCleanup(instrumentation, activity)
                    emit(instrumentation, JSONObject().put("stage", "failure_cleanup_completed")
                        .put("activityDestroyed", true).put("workerTerminated", true).put("inFlightBusy", false))
                } catch (cleanupFailure: Throwable) {
                    runCatching {
                        emit(instrumentation, JSONObject().put("stage", "cleanup_failed")
                            .put("failure", cleanupFailure.toString()))
                    }.exceptionOrNull()?.let { cleanupFailure.addSuppressed(it) }
                    val originalFailure = testFailure
                    if (originalFailure != null) originalFailure.addSuppressed(cleanupFailure)
                    else throw cleanupFailure
                }
            }
        }
    }

    @Test
    fun cameraStageOrderRejectsInvertedCloseAndPreservesActualLogOrder() {
        val phases = listOf("session_create", "session_resume", "session_pause", "provider_close", "session_close")
        val lines = phases.map { "cameraSessionEpoch=7 phase=$it ms=1 outcome=SUCCEEDED failure=null" }
        assertEquals(lines, assertCameraStageOrder(lines, 7L, phases))
        val inverted = lines.take(3) + listOf(lines[4], lines[3])
        val failure = runCatching { assertCameraStageOrder(inverted, 7L, phases) }.exceptionOrNull()
        assertTrue("Inverted actual session/provider close must fail", failure is AssertionError)
        assertTrue("Order failure must retain the observed sequence",
            failure?.message?.contains(inverted.toString()) == true)
    }

    private fun awaitRunning(instrumentation: Instrumentation, activity: CameraTestActivity): Running {
        val deadline = SystemClock.elapsedRealtime() + NATIVE_TIMEOUT_MS
        var observed = "No published camera frame"
        while (SystemClock.elapsedRealtime() < deadline) {
            val candidate = onMain(instrumentation) {
                val detector = field(activity, "detector") as? TfliteAndroidFrameDetector
                val timing = detector?.lifecycleTiming()
                val capture = field(activity, "primaryOverlayCapture")
                val sessionEpoch = field(activity, "cameraSessionEpoch") as? Long
                val status = (field(activity, "status") as TextView).text.toString()
                observed = "session=$sessionEpoch status=$status phases=${timing?.phases?.map { it.phase to it.outcome }}"
                val first = timing?.phases?.firstOrNull {
                    it.phase == AndroidDetectorLoadPhase.FIRST_INFERENCE &&
                        it.modelKey == TwoModelRuntimeConfig.UNIFIED_MODEL_KEY &&
                        it.outcome == AndroidDetectorPhaseOutcome.SUCCEEDED && !it.delegate.isNullOrBlank()
                }
                if (detector == null || timing == null || timing.detectorAvailable != true || first == null ||
                    capture == null || sessionEpoch == null || sessionEpoch <= 0L ||
                    field(activity, "session") == null || field(activity, "provider") == null ||
                    field(activity, "foreground") != true || activity.isFinishing || activity.isDestroyed ||
                    !status.startsWith("테스트 카메라 · ARCore ") || !status.contains(" · 추론 ")) null
                else {
                    val frameId = field(capture, "frameId") as Long
                    val captureEpoch = field(capture, "epoch") as Int
                    if (frameId <= 0L || captureEpoch != field(activity, "generation")) null
                    else Running(sessionEpoch, frameId, field(capture, "geometryId") as String,
                        detector, field(activity, "worker") as ExecutorService,
                        field(activity, "busy") as AtomicBoolean, timing)
                }
            }
            if (candidate != null) return candidate
            SystemClock.sleep(100L)
        }
        throw AssertionError("No actual camera RGB publication and named FIRST_INFERENCE success: $observed")
    }

    private fun awaitClosed(instrumentation: Instrumentation, activity: CameraTestActivity, running: Running) {
        val deadline = SystemClock.elapsedRealtime() + NATIVE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val timing = running.detector.lifecycleTiming()
            val close = timing.phases.filter { it.phase == AndroidDetectorLoadPhase.NATIVE_CLOSE }
            assertFalse("Native model close failed: $close",
                close.any { it.outcome == AndroidDetectorPhaseOutcome.FAILED })
            if (onMain(instrumentation) { activity.isDestroyed } && running.worker.isTerminated &&
                !running.busy.get() && close.any { it.outcome == AndroidDetectorPhaseOutcome.SUCCEEDED }) return
            SystemClock.sleep(100L)
        }
        throw AssertionError("Activity/native worker did not close: ${running.detector.lifecycleTiming()}")
    }

    /** Cleanup also applies before awaitRunning has produced a detector/session record. */
    private fun awaitCleanup(instrumentation: Instrumentation, activity: CameraTestActivity) {
        val worker = field(activity, "worker") as ExecutorService
        val busy = field(activity, "busy") as AtomicBoolean
        val deadline = SystemClock.elapsedRealtime() + NATIVE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain(instrumentation) { activity.isDestroyed } && worker.isTerminated && !busy.get()) return
            SystemClock.sleep(100L)
        }
        throw AssertionError("Cleanup timeout: destroyed=${onMain(instrumentation) { activity.isDestroyed }} " +
            "workerTerminated=${worker.isTerminated} busy=${busy.get()}")
    }

    private fun assertCameraStages(sessionEpoch: Long, phases: List<String>): List<String> {
        // Own PID and one metadata tag only. Never clear or collect other apps' log buffers.
        val process = ProcessBuilder("logcat", "-d", "-b", "main", "-v", "brief",
            "--pid=${Process.myPid()}", "WalkSafeCameraTest:I", "*:S").redirectErrorStream(true).start()
        val reader = Executors.newSingleThreadExecutor()
        val lines = try {
            reader.submit<List<String>> { process.inputStream.bufferedReader().use { it.readLines() } }
                .get(5L, TimeUnit.SECONDS)
        } finally {
            process.destroy()
            reader.shutdownNow()
        }
        return assertCameraStageOrder(lines, sessionEpoch, phases)
    }

    private fun assertCameraStageOrder(lines: List<String>, sessionEpoch: Long, phases: List<String>): List<String> {
        val events = lines.withIndex().filter { event ->
            phases.any { event.value.contains("cameraSessionEpoch=$sessionEpoch phase=$it ") }
        }
        var previousIndex = -1
        for (phase in phases) {
            val matching = events.filter { it.value.contains("cameraSessionEpoch=$sessionEpoch phase=$phase ") }
            assertFalse("ARCore $phase failed for session $sessionEpoch: $matching",
                matching.any { it.value.contains("outcome=FAILED") })
            val success = matching.firstOrNull { it.value.contains("outcome=SUCCEEDED") }
            assertTrue("Missing actual ARCore $phase success for session $sessionEpoch: $matching",
                success != null)
            val index = requireNotNull(success).index
            assertTrue("ARCore phase order invalid for session $sessionEpoch: ${events.map { it.value }}",
                index > previousIndex)
            previousIndex = index
        }
        // Preserve observed chronology; never reconstruct evidence in the expected phase order.
        return events.filter { it.value.contains("outcome=SUCCEEDED") }.map { it.value }
    }

    private fun timingEvidence(ordinal: Int, stage: String, running: Running): JSONObject {
        val timing = running.detector.lifecycleTiming()
        return JSONObject().put("ordinal", ordinal).put("stage", stage)
            .put("sessionEpoch", running.sessionEpoch).put("loadGeneration", timing.loadGeneration)
            .put("phases", JSONArray(timing.phases.map { phase ->
                JSONObject().put("phase", phase.phase.name).put("outcome", phase.outcome.name)
                    .put("model", phase.modelKey ?: JSONObject.NULL).put("delegate", phase.delegate ?: JSONObject.NULL)
                    .put("ms", phase.elapsedMs).put("thread", "${phase.threadId}/${phase.threadName}")
                    .put("failure", phase.failureType ?: JSONObject.NULL)
            }))
    }

    private fun emit(instrumentation: Instrumentation, evidence: JSONObject) {
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "NATIVE_SMOKE $evidence\n") })
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)

    private fun <T> onMain(instrumentation: Instrumentation, action: () -> T): T {
        var result: T? = null
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try { result = action() } catch (caught: Throwable) { failure = caught }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private data class Running(
        val sessionEpoch: Long,
        val frameId: Long,
        val geometryId: String,
        val detector: TfliteAndroidFrameDetector,
        val worker: ExecutorService,
        val busy: AtomicBoolean,
        val timing: AndroidDetectorLoadTiming,
    )

    companion object {
        // Execution timeout, not a device latency/accuracy acceptance threshold.
        private const val NATIVE_TIMEOUT_MS = 120_000L
    }
}

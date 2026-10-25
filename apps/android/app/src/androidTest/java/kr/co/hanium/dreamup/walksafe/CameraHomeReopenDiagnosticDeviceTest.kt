package kr.co.hanium.dreamup.walksafe

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.inference.AdaptiveInferencePacingPolicy
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectorLoadPhase
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectorPhaseOutcome
import kr.co.hanium.dreamup.walksafe.inference.TfliteAndroidFrameDetector
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeService
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Main.onCreate/restored HOME/View click. No synthetic account/readiness or sensor input.
 * Diagnostic success proves UI connection/reentry only; missing RGB/FastSAM remains NOT_OBSERVED.
 * Explicit directCamera mode is COMPONENT_ONLY, never restored-HOME evidence. Root controls cold-process starts. Existing permissions/data/settings are never changed here.
 */
@RunWith(AndroidJUnit4::class)
class CameraHomeReopenDiagnosticDeviceTest {
    @Test(timeout = 240_000L)
    fun actualEntryOpensClosesAndReopensTheCameraWithHeartbeatEvidence() {
        assumeTrue("Explicit opt-in required", InstrumentationRegistry.getArguments()
            .getString("allowCameraHomeReopenDiagnostic") == "true")
        val entry = InstrumentationRegistry.getArguments().getString("cameraDiagnosticEntry") ?: "restoredHome"
        require(entry in setOf("restoredHome", "directCamera")) { "Unknown cameraDiagnosticEntry" }
        val throughHome = entry == "restoredHome"
        val scope = if (throughHome) "ACTUAL_RESTORED_HOME" else "COMPONENT_ONLY"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Debug camera is required", BuildConfig.DEBUG)
        assertTrue("Pre-onCreate lifecycle observation requires API29+", Build.VERSION.SDK_INT >= 29)
        assertEquals("PRECONDITION: existing CAMERA permission required; never granted by this test",
            PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.CAMERA))
        val probe = Probe(context.applicationContext as Application, throughHome)
        try {
            probe.start()
            emit("LAUNCH_REQUEST", "ENTRY_SCOPE=$scope entry=$entry pid=${Process.myPid()} account_and_readiness=UNCHANGED cold_process=HOST_CONTROLLED")
            if (throughHome) {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                probe.await("PRECONDITION: actual Main.onCreate and restored HOME required", 60_000L) {
                    probe.main.get() != null && probe.resumed.get() === probe.main.get() && probe.home.get()
                }
            }
            val main = probe.main.get()
            repeat(2) { ordinal ->
                val before = probe.cameras.size
                if (throughHome) probe.action("HOME_CAMERA_CLICK_${ordinal + 1}") {
                    val actualMain = requireNotNull(main)
                    assertTrue("PRECONDITION: real HOME gate must remain satisfied", homeAvailable(actualMain))
                    val button = requireNotNull(button(actualMain.window.decorView, "카메라 테스트")) { "Real HOME camera View missing" }
                    assertTrue("Real camera button must be shown/enabled", button.isShown && button.isEnabled)
                    emit("HOME_CAMERA_CLICK", "ordinal=${ordinal + 1} actual_view=true ENTRY_SCOPE=$scope")
                    assertTrue(button.performClick())
                } else {
                    emit("DIRECT_CAMERA_REQUEST", "ordinal=${ordinal + 1} ENTRY_SCOPE=COMPONENT_ONLY main_home_success=false")
                    context.startActivity(Intent(context, CameraTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                probe.await("Camera Activity must be created/resumed through the selected actual entry", 30_000L) {
                    probe.cameras.size > before && probe.resumed.get() === probe.cameras.last()
                }
                val camera = probe.cameras.last()
                val observation = probe.observe(camera, ordinal + 1)
                probe.action("CAMERA_CLOSE_BUTTON_${ordinal + 1}") {
                    val close = requireNotNull(button(camera.window.decorView, "카메라 닫기"))
                    assertTrue(close.isShown && close.isEnabled)
                    assertTrue(close.performClick())
                }
                probe.await("Actual camera destruction and HOME return after close View", 30_000L) {
                    probe.destroyed.contains(camera) && (!throughHome || probe.resumed.get() === main && probe.home.get())
                }
                probe.observeRelease(camera, observation, ordinal + 1)
            }
            emit("FLOW_OBSERVED", "ENTRY_SCOPE=$scope actual_main_home_success=$throughHome real_home_clicks=${if (throughHome) 2 else 0} actual_camera_instances=2 ui_connection=true rgb_and_fastsam_qualification=false heartbeat=${probe.heartbeatSummary()}")
        } finally { probe.close() }
    }

    private class Observation(val detector: TfliteAndroidFrameDetector?, val worker: java.util.concurrent.ExecutorService,
        val cameraWorker: java.util.concurrent.ExecutorService, val auxiliary: FastSamRuntimeService<*>?)

    private class Probe(private val application: Application, private val ownsMain: Boolean) : Application.ActivityLifecycleCallbacks, AutoCloseable {
        val main = AtomicReference<MainActivity?>()
        val cameras = CopyOnWriteArrayList<CameraTestActivity>()
        val destroyed = CopyOnWriteArrayList<Activity>()
        val resumed = AtomicReference<Activity?>()
        val home = AtomicBoolean(false)
        private val handler = Handler(Looper.getMainLooper())
        private val executionDeadlineMs = now() + 210_000L
        private val lastBeat = AtomicLong(now())
        private val largestGap = AtomicLong()
        private val beats = AtomicLong()
        private val beatTimes = ConcurrentLinkedQueue<String>()
        private val stopped = AtomicBoolean(false)
        private val samplePending = AtomicBoolean(false)
        private val sample = AtomicReference<Map<String, Any?>>(emptyMap())
        private var lastStackAtMs = 0L
        private val heartbeat = object : Runnable {
            override fun run() {
                if (stopped.get()) return
                val at = now(); val gap = at - lastBeat.getAndSet(at)
                largestGap.updateAndGet { maxOf(it, gap) }; beats.incrementAndGet()
                beatTimes.add("$at:$gap"); while (beatTimes.size > 60) beatTimes.poll()
                handler.postDelayed(this, 50L)
            }
        }
        fun start() { application.registerActivityLifecycleCallbacks(this); handler.post(heartbeat) }
        fun heartbeatSummary() = "beats=${beats.get()} last_ms=${lastBeat.get()} max_gap_ms=${largestGap.get()} current_gap_ms=${now() - lastBeat.get()} recent_original_ms_gap=${beatTimes.toList().takeLast(12)}"
        fun action(label: String, block: () -> Unit) {
            val acknowledged = CountDownLatch(1); val failure = AtomicReference<Throwable?>()
            check(handler.post {
                try { block() } catch (error: Throwable) { failure.set(error) } finally { acknowledged.countDown() }
            })
            await("Main action acknowledgement: $label", 20_000L) { acknowledged.count == 0L }
            failure.get()?.let { throw it }
            emit("ACTION_ACK", "action=$label heartbeat=${heartbeatSummary()}")
        }
        fun await(label: String, timeoutMs: Long, ready: () -> Boolean) {
            val deadline = minOf(executionDeadlineMs, now() + timeoutMs)
            while (now() < deadline) {
                requestSample()
                if (ready()) return
                val gap = now() - lastBeat.get()
                if (gap >= 500L && now() - lastStackAtMs >= 2_000L) dump("MAIN_STALL_OBSERVED", label)
                SystemClock.sleep(100L)
            }
            dump("EXECUTION_TIMEOUT", label)
            throw AssertionError("$label; execution watchdog, not startup qualification; ${heartbeatSummary()}; sample=${sample.get()}")
        }
        private fun requestSample(camera: CameraTestActivity? = cameras.lastOrNull()) {
            if (!samplePending.compareAndSet(false, true)) return
            handler.post {
                try {
                    if (ownsMain) main.get()?.let { home.set(!it.isDestroyed && homeAvailable(it)) }
                    if (camera != null && !camera.isDestroyed) {
                        val capture = field(camera, "primaryOverlayCapture") as? CameraObjectPresentation.Capture
                        val runtime = field(camera, "unknownRuntime") as CameraUnknownRuntime<*>
                        sample.set(linkedMapOf("observed_ms" to now(), "camera_identity" to System.identityHashCode(camera),
                            "foreground" to field(camera, "foreground"), "session_epoch" to field(camera, "cameraSessionEpoch"),
                            "session_present" to (field(camera, "session") != null), "provider_present" to (field(camera, "provider") != null),
                            "preview_frame_ns" to field(camera, "lastFrame"), "rgb_frame_ns" to capture?.frameId,
                            "rgb_captured_ms" to capture?.capturedAtMs, "rgb_epoch" to capture?.epoch,
                            "generation" to field(camera, "generation"), "primary_present" to (field(camera, "detector") != null),
                            "primary_busy" to (field(camera, "busy") as AtomicBoolean).get(),
                            "pacing_snapshot" to pacingSnapshot(camera),
                            "fastsam_completed" to runtime.service?.stats()?.completed, "fastsam_status" to runtime.status.name,
                            "fastsam_backend" to runtime.backendName, "status" to (field(camera, "status") as TextView).text.toString()))
                    }
                } catch (error: Throwable) { sample.set(mapOf("sampling_error" to error.toString())) }
                finally { samplePending.set(false) }
            }
        }
        fun observe(camera: CameraTestActivity, ordinal: Int): Observation {
            val deadline = minOf(executionDeadlineMs, now() + 30_000L) // Observation only, not startup qualification.
            val seen = mutableSetOf<String>()
            var preview = 0L; var rgbFrame = 0L; var previewChanges = 0; var rgbChanges = 0
            var previewProgress = now(); var rgbProgress = now(); var progressReport = 0L
            while (now() < deadline) {
                requestSample(camera)
                val current = sample.get()
                if (current["camera_identity"] == System.identityHashCode(camera)) {
                    fun stage(name: String, present: Boolean) {
                        if (present && seen.add(name)) emit(name, "ordinal=$ordinal observed=$current heartbeat=${heartbeatSummary()}")
                    }
                    stage("SESSION_PUBLISHED", current["session_present"] == true && current["provider_present"] == true)
                    val observed = current["observed_ms"] as Long
                    val previewFrame = current["preview_frame_ns"] as? Long ?: 0L
                    val published = current["rgb_frame_ns"] as? Long ?: 0L
                    if (previewFrame > preview) { preview = previewFrame; previewChanges++; previewProgress = observed }
                    if (published > rgbFrame) { rgbFrame = published; rgbChanges++; rgbProgress = observed }
                    stage("PREVIEW_FRAME_OBSERVED", previewFrame > 0L)
                    val rgb = published > 0L
                    if (rgb) {
                        assertTrue("RGB must follow real Session/preview", "SESSION_PUBLISHED" in seen && "PREVIEW_FRAME_OBSERVED" in seen)
                        assertEquals("Only the current actual capture counts", current["generation"], current["rgb_epoch"])
                    }
                    stage("ACTUAL_RGB_PRIMARY_PUBLICATION", rgb) // Empty detection list remains valid RGB inference.
                    stage("FASTSAM_READY", current["fastsam_status"] == "READY")
                    if (observed - progressReport >= 1_000L) {
                        progressReport = observed
                        emit("SAMPLED_PROGRESS", "ordinal=$ordinal sampled_preview_changes=$previewChanges sampled_rgb_changes=$rgbChanges exact_camera_fps=false preview_no_progress_ms=${observed - previewProgress} rgb_no_progress_ms=${observed - rgbProgress} observed=$current heartbeat=${heartbeatSummary()}")
                    }
                }
                if (now() - lastBeat.get() >= 500L && now() - lastStackAtMs >= 2_000L) dump("MAIN_STALL_OBSERVED", "camera observation $ordinal")
                SystemClock.sleep(100L)
            }
            emit("CAMERA_OBSERVATION", "ordinal=$ordinal observed_stages=$seen missing=${setOf("SESSION_PUBLISHED", "PREVIEW_FRAME_OBSERVED", "ACTUAL_RGB_PRIMARY_PUBLICATION", "FASTSAM_READY") - seen} missing_means=NOT_OBSERVED_NOT_CPU_GPU_FAILURE last=${sample.get()} heartbeat=${heartbeatSummary()}")
            val retained = AtomicReference<Observation?>()
            action("READ_OWNED_RESOURCES_$ordinal") {
                val detector = field(camera, "detector") as? TfliteAndroidFrameDetector
                emit("MODEL_METADATA", "ordinal=$ordinal warmup_first_inference_is_not_live=true timing=${detector?.lifecycleTiming()}")
                retained.set(Observation(detector, field(camera, "worker") as java.util.concurrent.ExecutorService,
                    field(camera, "cameraWorker") as java.util.concurrent.ExecutorService,
                    (field(camera, "unknownRuntime") as CameraUnknownRuntime<*>).service))
            }
            return requireNotNull(retained.get())
        }
        fun observeRelease(camera: CameraTestActivity, owned: Observation, ordinal: Int) {
            val deadline = minOf(executionDeadlineMs, now() + 20_000L)
            fun releasePending(): Boolean {
                val auxiliary = owned.auxiliary?.stats()
                return !owned.worker.isTerminated || !owned.cameraWorker.isTerminated ||
                    owned.auxiliary?.releasedFuture()?.isDone == false ||
                    auxiliary?.modelOwnerAlive == true || auxiliary?.cpuPostOwnerAlive == true
            }
            while (now() < deadline && releasePending()) {
                if (now() - lastBeat.get() >= 500L && now() - lastStackAtMs >= 2_000L) dump("MAIN_STALL_OBSERVED", "release $ordinal")
                SystemClock.sleep(100L)
            }
            if (releasePending()) dump("NATIVE_RELEASE_UNCONFIRMED", "release execution observation window $ordinal")
            val releaseRead = runCatching { field(camera, "cameraRelease") as? CameraSessionRelease }
            val release = releaseRead.getOrNull()
            val cameraReleaseStatus = when {
                release == null -> "NOT_CONFIRMED"
                release.failure != null -> "FAILED"
                release.blocksStart -> "NOT_CONFIRMED"
                else -> "CONFIRMED"
            }
            val timingRead = runCatching { owned.detector?.lifecycleTiming() }
            val closePhases = timingRead.getOrNull()?.phases.orEmpty().filter { it.phase == AndroidDetectorLoadPhase.NATIVE_CLOSE }
            val primaryReleaseStatus = when {
                closePhases.any { it.outcome == AndroidDetectorPhaseOutcome.FAILED } -> "FAILED"
                closePhases.any { it.outcome == AndroidDetectorPhaseOutcome.SUCCEEDED } -> "CONFIRMED"
                else -> "NOT_CONFIRMED"
            }
            val auxiliary = owned.auxiliary?.stats()
            val auxiliaryReleaseStatus = when {
                owned.auxiliary == null -> "NOT_OBSERVED_NO_RETAINED_SERVICE"
                owned.auxiliary.releasedFuture().isCompletedExceptionally -> "FAILED"
                owned.auxiliary.releasedFuture().isDone && auxiliary?.nativeReleaseConfirmed == true &&
                    !auxiliary.modelOwnerAlive && !auxiliary.cpuPostOwnerAlive -> "CONFIRMED"
                else -> "NOT_CONFIRMED"
            }
            emit("RELEASE_OBSERVATION", "ordinal=$ordinal destroyed=${destroyed.contains(camera)} primary_worker_terminated=${owned.worker.isTerminated} camera_worker_terminated=${owned.cameraWorker.isTerminated} camera_release=$cameraReleaseStatus camera_blocks_start=${release?.blocksStart} camera_failure=${release?.failure} camera_read_error=${releaseRead.exceptionOrNull()} primary_native_release=$primaryReleaseStatus primary_timing=${timingRead.getOrNull()} primary_read_error=${timingRead.exceptionOrNull()} auxiliary_native_release=$auxiliaryReleaseStatus auxiliary_model_owner_alive=${auxiliary?.modelOwnerAlive} auxiliary_post_owner_alive=${auxiliary?.cpuPostOwnerAlive} auxiliary_inflight=${auxiliary?.inflight} auxiliary_stopping=${auxiliary?.stopping} auxiliary_failure=${auxiliary?.failure} auxiliary_release_done=${owned.auxiliary?.releasedFuture()?.isDone} heartbeat=${heartbeatSummary()}")
            assertTrue("Pending native owner release must not be followed by reentry", owned.worker.isTerminated && owned.cameraWorker.isTerminated)
            assertEquals("Camera provider/session release must be confirmed before reentry", "CONFIRMED", cameraReleaseStatus)
            assertEquals("Actual primary NATIVE_CLOSE must succeed before reentry", "CONFIRMED", primaryReleaseStatus)
            owned.auxiliary?.let { assertEquals("Native auxiliary close and both owner thread terminations must be confirmed", "CONFIRMED", auxiliaryReleaseStatus) }
        }
        private fun dump(event: String, reason: String) {
            lastStackAtMs = now()
            val stacks = Thread.getAllStackTraces().filterKeys { it === Looper.getMainLooper().thread ||
                it.name.contains("Camera") || it.name.contains("inference") || it.name.contains("fastsam") }
                .entries.joinToString("\n") { (thread, stack) -> "${thread.name}/${thread.state}\n${stack.take(18).joinToString("\n")}" }
            emit(event, "reason=$reason heartbeat=${heartbeatSummary()} last=${sample.get()} stacks=$stacks")
        }
        override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity is MainActivity) { main.set(activity); emit("MAIN_ON_CREATE_ENTRY", "identity=${System.identityHashCode(activity)}") }
            if (activity is CameraTestActivity) { cameras += activity; emit("CAMERA_ON_CREATE_ENTRY", "ordinal=${cameras.size}") }
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) { if (activity is MainActivity || activity is CameraTestActivity) emit("ACTIVITY_CREATED", "class=${activity.javaClass.simpleName}") }
        override fun onActivityResumed(activity: Activity) { resumed.set(activity); if (activity is MainActivity || activity is CameraTestActivity) emit("ACTIVITY_RESUMED", "class=${activity.javaClass.simpleName}") }
        override fun onActivityPaused(activity: Activity) { if (resumed.get() === activity) resumed.set(null); emit("ACTIVITY_PAUSED", "class=${activity.javaClass.simpleName}") }
        override fun onActivityDestroyed(activity: Activity) { destroyed += activity; emit("ACTIVITY_DESTROYED", "class=${activity.javaClass.simpleName}") }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun close() {
            stopped.set(true); handler.removeCallbacksAndMessages(null)
            application.unregisterActivityLifecycleCallbacks(this)
            handler.post { cameras.filter { !it.isDestroyed }.forEach { it.finish() }; if (ownsMain) main.get()?.takeUnless { it.isDestroyed }?.finish() }
            emit("DIAGNOSTIC_CLEANUP_REQUESTED", "actual_finish_only=true callbacks_unregistered=true heartbeat=${heartbeatSummary()}")
        }
    }
    companion object {
        private fun now() = SystemClock.elapsedRealtime()
        private fun homeAvailable(activity: MainActivity) = MainActivity::class.java.getDeclaredMethod("shouldShowNativeHome").apply { isAccessible = true }.invoke(activity) as Boolean
        private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        private fun pacingSnapshot(camera: CameraTestActivity): Map<String, Any?> = try {
            val pacing = field(camera, "primaryInferencePacing") as? AdaptiveInferencePacingPolicy
            val state = requireNotNull(pacing) { "Unexpected primaryInferencePacing type" }.snapshot()
            linkedMapOf("status" to "OBSERVED", "completedSamples" to state.completedSamples,
                "targetIntervalMs" to state.targetIntervalMs, "cooldownMs" to state.cooldownMs,
                "inFlight" to state.inFlight, "lastEndToEndMs" to state.lastEndToEndMs,
                "lastInferenceMs" to state.lastInferenceMs)
        } catch (missing: NoSuchFieldException) { mapOf("status" to "NOT_AVAILABLE_FIELD_ABSENT")
        } catch (failure: Exception) { mapOf("status" to "NOT_CONFIRMED", "failure" to failure.toString()) }
        private fun button(view: View, title: String): View? {
            if (view is TextView && view.isShown && view.isClickable && view.text.toString().lineSequence().firstOrNull() == title) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) button(view.getChildAt(index), title)?.let { return it }
            return null
        }
        private fun emit(event: String, detail: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "CAMERA_HOME_REOPEN event=$event at_ms=${now()} $detail\n")
        })
    }
}

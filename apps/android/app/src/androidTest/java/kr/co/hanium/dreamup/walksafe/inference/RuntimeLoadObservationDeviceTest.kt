package kr.co.hanium.dreamup.walksafe.inference

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Handler signal -> pacing integration only; no Activity, camera, model or user session. */
@RunWith(AndroidJUnit4::class)
class RuntimeLoadObservationDeviceTest {
    @Test
    fun actualUiDispatchDelayBacksOffAndRecoversWithoutReusingOldObservations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val handler = Handler(Looper.getMainLooper())
        val startedAtMs = SystemClock.elapsedRealtime()
        val report = JSONObject()
            .put("scope", "actual_handler_signal_to_pacing")
            .put("activityLaunched", false).put("cameraOrDepthLoadMeasured", false)
            .put("modelInvoked", false).put("syntheticInferenceDurationMs", SYNTHETIC_WORK_MS)
            .put("syntheticFrameUsedOnlyForScopeBindingAndStaleCheck", true)
        val samples = JSONArray()
        var maximumPressure = 0.0
        var maximumIntervalMs = 0L
        var maximumUiDelayMs = 0L
        val blockStartedAtMs = AtomicLong(-1L)
        val blockFinishedAtMs = AtomicLong(-1L)
        val blockFinished = CountDownLatch(1)
        val blocker = Runnable {
            blockStartedAtMs.set(SystemClock.elapsedRealtime())
            SystemClock.sleep(UI_BLOCK_MS)
            blockFinishedAtMs.set(SystemClock.elapsedRealtime())
            blockFinished.countDown()
        }
        var source: AndroidRuntimeLoadObservationSource? = null
        try {
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            val refreshRateHz = checkNotNull(display) { "A current display is required" }.refreshRate
            assertTrue("Display refresh rate must be available", refreshRateHz.isFinite() && refreshRateHz > 0f)
            val uiBudgetMs = ceil(1_000.0 / refreshRateHz).toLong().coerceAtLeast(1L)
            report.put("displayRefreshRateHz", refreshRateHz.toDouble()).put("uiBudgetMs", uiBudgetMs)
            val observedSource = AndroidRuntimeLoadObservationSource(handler, { null }, { uiBudgetMs })
            source = observedSource
            observedSource.start()
            observedSource.recordFrame(1L, SystemClock.elapsedRealtime(), null, null, null, SCOPE)
            val pacing = AdaptiveInferencePacingPolicy()
            var lastObservation: InferenceLoadObservation? = null

            fun consume(phase: String, deadlineMs: Long): InferencePacingSnapshot {
                val observation = awaitObservation(observedSource, deadlineMs)
                assertTrue("A consumed UI observation must not be replayed",
                    lastObservation == null || observation.observedAtElapsedRealtimeMs > checkNotNull(lastObservation).observedAtElapsedRealtimeMs)
                lastObservation = observation
                assertTrue("Expected a UI-only measurement", observation.uiDispatchDelayMs != null)
                assertNull(observation.cameraFrameIntervalMs)
                // Deliberate policy input, not a timed model invocation or measured frame latency.
                val completedAtMs = SystemClock.elapsedRealtime()
                val ticket = checkNotNull(pacing.tryStart(completedAtMs - SYNTHETIC_WORK_MS))
                assertTrue(pacing.complete(ticket, completedAtMs, SYNTHETIC_WORK_MS, loadObservation = observation))
                return pacing.snapshot().also { snapshot ->
                    maximumPressure = maxOf(maximumPressure, snapshot.observedLoadPressure)
                    maximumIntervalMs = maxOf(maximumIntervalMs, snapshot.targetIntervalMs)
                    maximumUiDelayMs = maxOf(maximumUiDelayMs, checkNotNull(observation.uiDispatchDelayMs))
                    samples.put(JSONObject().put("phase", phase)
                        .put("observedAtElapsedRealtimeMs", observation.observedAtElapsedRealtimeMs)
                        .put("uiDispatchDelayMs", observation.uiDispatchDelayMs)
                        .put("pressure", snapshot.observedLoadPressure)
                        .put("targetIntervalMs", snapshot.targetIntervalMs)
                        .put("cooldownMs", snapshot.cooldownMs))
                }
            }

            repeat(4) { consume("baseline", SystemClock.elapsedRealtime() + 2_000L) }
            val baseline = pacing.snapshot()
            assertEquals("Baseline UI queue must be quiet for this comparison", 0.0, baseline.observedLoadPressure, 0.001)
            report.put("baselineIntervalMs", baseline.targetIntervalMs)
            // Begin once, shortly before the next 500 ms probe, so its actual dispatch is delayed.
            val untilBlockMs = (checkNotNull(lastObservation).observedAtElapsedRealtimeMs + 400L - SystemClock.elapsedRealtime())
                .coerceAtLeast(0L)
            assertTrue(handler.postDelayed(blocker, untilBlockMs))
            val loaded = consume("controlled_ui_delay", SystemClock.elapsedRealtime() + 3_000L)
            assertTrue("The short test-only UI block must finish", blockFinished.await(1L, TimeUnit.SECONDS))
            report.put("peakPressure", loaded.observedLoadPressure).put("peakIntervalMs", loaded.targetIntervalMs)
            assertTrue("Actual UI dispatch must increase pressure", loaded.observedLoadPressure > baseline.observedLoadPressure)
            assertTrue("Pressure must increase pacing headroom", loaded.targetIntervalMs > baseline.targetIntervalMs)

            val recoveryStartedAtMs = SystemClock.elapsedRealtime()
            val recoveryDeadlineMs = recoveryStartedAtMs + 35_000L
            var recovered = loaded
            var recoverySamples = 0
            while (SystemClock.elapsedRealtime() < recoveryDeadlineMs &&
                (recovered.observedLoadPressure > 0.0 || recovered.targetIntervalMs >= loaded.targetIntervalMs)
            ) {
                recovered = consume("recovery", minOf(recoveryDeadlineMs, SystemClock.elapsedRealtime() + 2_000L))
                recoverySamples += 1
            }
            report.put("recoveryDurationMs", SystemClock.elapsedRealtime() - recoveryStartedAtMs)
                .put("recoverySamples", recoverySamples).put("recoveredPressure", recovered.observedLoadPressure)
                .put("recoveredIntervalMs", recovered.targetIntervalMs)
            assertEquals("Quiet UI observations must recover pressure", 0.0, recovered.observedLoadPressure, 0.001)
            assertTrue("Admission interval must recover gradually", recovered.targetIntervalMs < loaded.targetIntervalMs)

            // A synthetic frame's old timing must not be re-stamped by a newer real UI callback.
            val staleFrameAtMs = SystemClock.elapsedRealtime()
            observedSource.recordFrame(2L, staleFrameAtMs, null, 5L, 10L, SCOPE)
            SystemClock.sleep(600L)
            val afterStale = checkNotNull(observedSource.latestObservation(SystemClock.elapsedRealtime())) {
                "A newer actual UI callback is required for the stale timing check"
            }
            assertTrue(afterStale.uiDispatchDelayMs != null && afterStale.observedAtElapsedRealtimeMs > staleFrameAtMs)
            assertNull("Expired frame timing must not accompany a newer UI sample", afterStale.trackingProcessingMs)
            report.put("staleSyntheticFrameAgeMs", SystemClock.elapsedRealtime() - staleFrameAtMs)
                .put("staleFrameTimingAbsent", true)
            observedSource.pause()
            SystemClock.sleep(650L)
            assertNull("Paused source must not publish its previously scheduled probe",
                observedSource.latestObservation(SystemClock.elapsedRealtime()))
            report.put("pausePendingCallbackSuppressed", true).put("consumedUiTimestampsStrictlyIncreasing", true)
                .put("outcome", "PASS")
        } catch (failure: Throwable) {
            report.put("outcome", "FAIL").put("failure", failure.message ?: failure.javaClass.simpleName)
            throw failure
        } finally {
            handler.removeCallbacks(blocker)
            source?.close()
            report.put("samples", samples).put("requestedUiBlockMs", UI_BLOCK_MS)
                .put("peakPressure", maximumPressure).put("peakIntervalMs", maximumIntervalMs)
                .put("maximumUiDispatchDelayMs", maximumUiDelayMs)
                .put("observedUiBlockMs", if (blockFinishedAtMs.get() >= blockStartedAtMs.get() && blockStartedAtMs.get() >= 0L)
                    blockFinishedAtMs.get() - blockStartedAtMs.get() else JSONObject.NULL)
                .put("totalDurationMs", SystemClock.elapsedRealtime() - startedAtMs)
            File(context.filesDir, "runtime-load-observation-device-report.json").writeText(report.toString(2))
        }
    }

    private fun awaitObservation(source: AndroidRuntimeLoadObservationSource, deadlineMs: Long): InferenceLoadObservation {
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            source.latestObservation(SystemClock.elapsedRealtime())?.let { return it }
            SystemClock.sleep(10L)
        }
        throw AssertionError("No fresh actual Handler observation before the test deadline")
    }

    companion object {
        private const val SCOPE = "instrumentation_handler_only"
        private const val SYNTHETIC_WORK_MS = 80L
        private const val UI_BLOCK_MS = 180L
    }
}

package kr.co.hanium.dreamup.walksafe

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.HandlerThread
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckDetectorExecutionPolicy
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckLocationFixPolicy
import kr.co.hanium.dreamup.walksafe.inference.TfliteAndroidFrameDetector
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reports whether the tightened device-check thresholds are reachable on this handset.
 *
 * The proposed numbers are borrowed from gates the app already applies elsewhere, so they have a
 * basis, but nobody has confirmed they admit a working phone. This measures the two that could
 * exclude one: the GPS accuracy actually achieved against the 15 m the walk gate wants, and the
 * detector's per-frame success rate and latency against ten frames at eighty percent.
 */
@RunWith(AndroidJUnit4::class)
class DeviceCheckThresholdFeasibilityDeviceTest {

    @Test
    fun measureLocationAccuracyAgainstTheWalkGate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val samples = mutableListOf<Location>()
        val lock = Any()
        val enough = CountDownLatch(1)

        // The device check acquires through Play Services, not LocationManager, so this mirrors
        // that request exactly; a LocationManager reading would measure a different path.
        val client = LocationServices.getFusedLocationProviderClient(context)
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val size = synchronized(lock) {
                    samples.addAll(result.locations)
                    samples.size
                }
                if (size >= MAX_LOCATION_SAMPLES) enough.countDown()
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .setMaxUpdateAgeMillis(DeviceCheckLocationFixPolicy.MAX_AGE_MS)
            .setMinUpdateIntervalMillis(1_000L)
            .setWaitForAccurateLocation(false)
            .setDurationMillis(LOCATION_WINDOW_SECONDS * 1_000L)
            .build()

        // Android 10+ hands location only to a foreground process, and instrumentation alone is
        // not foreground — without a visible activity every request returns nothing at all.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            android.content.Intent(context, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        instrumentation.waitForIdleSync()

        val thread = HandlerThread("walksafe-location-measure").apply { start() }
        val requested = manager.getProviders(true).orEmpty()
        runCatching { client.requestLocationUpdates(request, callback, thread.looper) }
        enough.await(LOCATION_WINDOW_SECONDS, TimeUnit.SECONDS)
        runCatching { client.removeLocationUpdates(callback) }
        thread.quitSafely()
        instrumentation.runOnMainSync { runCatching { activity.finish() } }

        // Separates "the request path is broken" from "this room has no fix": a last known
        // location proves the device can fix somewhere, just not fresh and not here.
        val lastKnown = requested.mapNotNull { provider ->
            val fix = runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                ?: return@mapNotNull null
            val ageMs = System.currentTimeMillis() - fix.time
            // Accuracy and age only; coordinates are not reported.
            "$provider(acc=${if (fix.hasAccuracy()) "%.1f".format(fix.accuracy) else "none"}," +
                "ageMin=${ageMs / 60_000})"
        }

        val observed = synchronized(lock) { samples.toList() }
        val accuracies = observed.filter { it.hasAccuracy() }.map { it.accuracy }
        val best = accuracies.minOrNull()
        val summary = buildString {
            append("providers=").append(requested)
            append(" lastKnown=").append(lastKnown.ifEmpty { "none" })
            append(" samples=").append(observed.size)
            append(" bestAccuracyM=").append(best?.let { "%.1f".format(it) } ?: "none")
            append(" worstAccuracyM=")
            append(accuracies.maxOrNull()?.let { "%.1f".format(it) } ?: "none")
            append(" providersUsed=").append(observed.map { it.provider }.distinct())
            append(" | currentGate=").append(DeviceCheckLocationFixPolicy.MAX_ACCURACY_METERS)
            append("m proposedGate=").append(PROPOSED_ACCURACY_METERS).append("m")
            append(" passesCurrent=")
            append(best != null && best <= DeviceCheckLocationFixPolicy.MAX_ACCURACY_METERS)
            append(" passesProposed=")
            append(best != null && best <= PROPOSED_ACCURACY_METERS)
        }
        report(LOCATION_STATUS_KEY, summary)
        assertTrue(summary.contains("samples="))
    }

    @Test
    fun measureDetectorSuccessRateAndLatency() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val load = TfliteAndroidFrameDetector.createWithStatus(context)
        val detector = load.detector
        if (detector == null) {
            report(
                CAMERA_STATUS_KEY,
                "detectorLoaded=false reason=${load.reason} configLoaded=${load.configLoaded}",
            )
            return
        }

        val provider = ProcessCameraProvider.getInstance(context).get(20L, TimeUnit.SECONDS)
        val executor = Executors.newSingleThreadExecutor()
        val attempted = AtomicInteger()
        val passed = AtomicInteger()
        val latencies = mutableListOf<Long>()
        val lock = Any()
        val done = CountDownLatch(1)

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor) { proxy ->
            if (attempted.get() >= FRAME_BUDGET) {
                proxy.close()
                done.countDown()
                return@setAnalyzer
            }
            val image = proxy.image
            if (image == null) {
                proxy.close()
                return@setAnalyzer
            }
            val startedAt = System.nanoTime()
            val result = runCatching {
                detector.detect(cameraImage = image, timestampMs = proxy.imageInfo.timestamp / 1_000_000L)
            }.getOrNull()
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            proxy.close()

            attempted.incrementAndGet()
            if (result != null && DeviceCheckDetectorExecutionPolicy.passes(result)) {
                passed.incrementAndGet()
                synchronized(lock) { latencies.add(elapsedMs) }
            }
            if (attempted.get() >= FRAME_BUDGET) done.countDown()
        }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            provider.unbindAll()
            provider.bindToLifecycle(
                TestLifecycleOwner(),
                CameraSelector.DEFAULT_BACK_CAMERA,
                analysis,
            )
        }
        val finished = done.await(CAMERA_WINDOW_SECONDS, TimeUnit.SECONDS)
        instrumentation.runOnMainSync { provider.unbindAll() }
        executor.shutdown()
        runCatching { detector.close() }

        val observed = synchronized(lock) { latencies.sorted() }
        val tried = attempted.get()
        val ok = passed.get()
        val summary = buildString {
            append("modelKey=").append(load.modelKey)
            append(" finished=").append(finished)
            append(" framesAttempted=").append(tried)
            append(" framesPassed=").append(ok)
            append(" passRatePercent=").append(if (tried > 0) ok * 100 / tried else -1)
            append(" latencyMsMin=").append(observed.firstOrNull() ?: -1)
            append(" latencyMsMedian=").append(observed.getOrNull(observed.size / 2) ?: -1)
            append(" latencyMsMax=").append(observed.lastOrNull() ?: -1)
            append(" | currentGate=1of5 proposedGate=")
            append(PROPOSED_PASS_PERCENT).append("%of").append(PROPOSED_FRAMES)
            append(" passesProposed=")
            append(tried > 0 && ok * 100 / tried >= PROPOSED_PASS_PERCENT)
        }
        report(CAMERA_STATUS_KEY, summary)
        assertTrue(summary.contains("framesAttempted="))
    }

    private fun report(key: String, summary: String) {
        Log.i(LOG_TAG, "$key $summary")
        InstrumentationRegistry.getInstrumentation()
            .sendStatus(0, Bundle().apply { putString(key, summary) })
    }

    private class TestLifecycleOwner : androidx.lifecycle.LifecycleOwner {
        private val registry = androidx.lifecycle.LifecycleRegistry(this).apply {
            currentState = androidx.lifecycle.Lifecycle.State.RESUMED
        }
        override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
    }

    private companion object {
        const val PROPOSED_ACCURACY_METERS = 15f
        const val PROPOSED_FRAMES = 10
        const val PROPOSED_PASS_PERCENT = 80
        const val MAX_LOCATION_SAMPLES = 15
        // A cold GNSS start indoors can take minutes; 45 s could not tell "slow" from "never".
        const val LOCATION_WINDOW_SECONDS = 120L
        const val FRAME_BUDGET = 30
        const val CAMERA_WINDOW_SECONDS = 60L
        const val LOG_TAG = "WalkSafeThresholdFeasibility"
        const val LOCATION_STATUS_KEY = "walksafe_location_feasibility"
        const val CAMERA_STATUS_KEY = "walksafe_camera_feasibility"
    }
}

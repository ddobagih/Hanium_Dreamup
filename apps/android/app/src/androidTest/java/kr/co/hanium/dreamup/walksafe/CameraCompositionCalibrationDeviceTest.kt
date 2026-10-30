package kr.co.hanium.dreamup.walksafe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.co.hanium.dreamup.walksafe.depth.unknown.FrozenUnknownDepthCapture
import kr.co.hanium.dreamup.walksafe.depth.unknown.UnknownObjectDepthPipeline
import kr.co.hanium.dreamup.walksafe.device.AndroidFixedFixtureRuntimeSelector
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeService
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real combined-camera component check. It never manufactures login, consent or HOME readiness. */
@RunWith(AndroidJUnit4::class)
class CameraCompositionCalibrationDeviceTest {
    @Test(timeout = 420_000L)
    fun liveCombinedWorkloadMeasuresBothModelsAndReleasesItsCamera() {
        assumeTrue("Explicit opt-in required", InstrumentationRegistry.getArguments()
            .getString("allowCameraCompositionCalibration") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("Existing camera permission required", PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.CAMERA))
        val owner = Executors.newSingleThreadExecutor()
        val current = AtomicBoolean(true)
        val observations = AtomicInteger()
        val metricObservations = AtomicInteger()
        val selected = AtomicReference<UnknownRuntimeSelection?>()
        val transferred = AtomicReference<FastSamRuntimeService<UnknownRuntimeAttachment>?>()
        val closed = CountDownLatch(1)
        val terminal = AtomicReference<String>()
        val host = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var calibration: UnknownRuntimeCalibration? = null
        var detector: TfliteAndroidFrameDetector? = null
        try {
            val config = TwoModelRuntimeConfig.load(context)
            val prepared = owner.submit<Pair<TfliteAndroidFrameDetector, RuntimeCandidate>> {
                val load = AndroidFixedFixtureRuntimeSelector.load(context, config, allowComparison = false)
                val model = requireNotNull(load.detector)
                detector = model
                val fixtures = RuntimeCalibrationFixtures(context)
                val result = model.detectCalibrationImage(fixtures.loadArgb(fixtures.loadManifest().fixtures.first { it.required }))
                val runtime = requireNotNull(result.timing.modelRuntime)
                check(result.inferenceCompleted && !result.partial && !runtime.fallbackUsed)
                model to RuntimeCandidate(if (runtime.activeDelegate == "gpu") RuntimeBackend.GPU else RuntimeBackend.CPU,
                    runtime.numThreads)
            }.get(90, TimeUnit.SECONDS)
            val binding = requireNotNull(AndroidUnknownRuntimeProfileStore.createBinding(context, config,
                prepared.second, RuntimeFeatureScope.CAMERA_TRACKING_METRIC_DEPTH))
            // A new opted-in physical comparison starts a new search; preserve any saved profile.
            owner.submit { AndroidUnknownRuntimeProfileStore(context).markStarted(binding) }.get(5, TimeUnit.SECONDS)
            val depth = UnknownObjectDepthPipeline(maximumSourceAgeMs = 800L,
                elapsedRealtimeMs = SystemClock::elapsedRealtime)
            val deadline = SystemClock.elapsedRealtime() + 270_000L
            instrumentation.runOnMainSync {
                calibration = UnknownRuntimeCalibration(host, binding, prepared.first, owner,
                    SystemClock.elapsedRealtime(), deadline, current::get, { false },
                    onSelected = { proposal ->
                        selected.set(proposal)
                        emit("SELECTION", "candidate=${proposal.candidate} confirmed=${proposal.profile != null}")
                        proposal.tryAdopt {
                            if (!current.get()) false else { transferred.set(proposal.readyService); true }
                        }
                    },
                    onClosed = { result ->
                        terminal.set("status=${result?.status} adopted=${result?.adopted} reason=${result?.reason}")
                        emit("CLOSED", terminal.get())
                        closed.countDown()
                    },
                    onProductionResult = {},
                    onCalibrationResult = { result, evidence ->
                        observations.incrementAndGet()
                        val snapshot = evidence.depthSnapshot
                        val mapper = evidence.mapperFrameId
                        val capture = if (snapshot != null && mapper != null) FrozenUnknownDepthCapture.freeze(
                            result.token, snapshot, evidence.imageToDepthUv, mapper) else null
                        val valid = capture != null && depth.process(capture, result.token, result.masks,
                            SystemClock.elapsedRealtime()).rejectionReason == null &&
                            snapshot?.hasFreshMetricRawDepth == true && evidence.positiveDepthSamples > 0 && evidence.poseValid
                        if (valid) metricObservations.incrementAndGet()
                        if (observations.get() % 10 == 1) emit("LIVE", "count=${observations.get()} metric=${metricObservations.get()} " +
                            "backend=${result.runtime.actualBackend} threads=${result.runtime.numThreads} " +
                            "totalMs=${result.captureToDecodeCompleteMs} cameraMs=${evidence.cameraFrameIntervalMs} " +
                            "uiMs=${evidence.uiFrameDelayMs} rawFresh=${snapshot?.hasFreshMetricRawDepth}")
                        valid
                    })
                assertTrue(requireNotNull(calibration).start())
            }
            assertTrue("Combined comparison must reach a terminal result and return the camera", closed.await(300, TimeUnit.SECONDS))
            emit("RESULT", "component_only=true combined=${observations.get()} metric=${metricObservations.get()} " +
                "profileConfirmed=${selected.get()?.profile != null} ${terminal.get()}")
            assertTrue("Both real models must complete on live camera frames", observations.get() > 0)
            assertTrue("Real pose and fresh Raw Depth must reach the combined workload", metricObservations.get() > 0)
        } finally {
            current.set(false)
            instrumentation.runOnMainSync { calibration?.cancel("device_test_finished") }
            assertTrue("Camera/native calibration cleanup must complete", calibration == null || closed.await(30, TimeUnit.SECONDS))
            transferred.get()?.let { it.closeAsync(); it.releasedFuture().get(30, TimeUnit.SECONDS) }
            owner.submit { detector?.close() }.get(30, TimeUnit.SECONDS)
            owner.shutdown()
            assertTrue(owner.awaitTermination(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { if (!host.isDestroyed) host.finish() }
        }
    }

    private fun emit(stage: String, detail: String?) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0,
            Bundle().apply { putString("stream", "CAMERA_COMPOSITION stage=$stage $detail\n") })
    }
}

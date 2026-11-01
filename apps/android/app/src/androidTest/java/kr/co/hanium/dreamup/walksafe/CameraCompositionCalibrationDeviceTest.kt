package kr.co.hanium.dreamup.walksafe

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.composite.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Explicit future-device opt-in only. Uses bundled images without opening any camera or HOME. */
@RunWith(AndroidJUnit4::class)
class CameraCompositionCalibrationDeviceTest {
    @Test(timeout = 360_000L)
    fun bundledImagesMeasureBothModelsAndReleaseTheWorkerProcess() {
        assumeTrue("Explicit sample-benchmark opt-in required", InstrumentationRegistry.getArguments()
            .getString("allowFixtureCompositionCalibration") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        val worker = Executors.newSingleThreadExecutor()
        val current = AtomicBoolean(true)
        val finished = CountDownLatch(1)
        val result = AtomicReference<JointRuntimeCheckResult>()
        var check: AndroidJointRuntimeCheck? = null
        try {
            val config = worker.submit<TwoModelRuntimeConfig> { TwoModelRuntimeConfig.load(context) }.get(30, TimeUnit.SECONDS)
            val candidate = RuntimeSelectionPolicy.initialCpuCandidate(Runtime.getRuntime().availableProcessors(), defaultThreads = 2)
            val deadline = SystemClock.elapsedRealtime() + 300_000L
            instrumentation.runOnMainSync {
                check = AndroidJointRuntimeCheck(context, config, candidate, deadline,
                    current::get, { false }, onFinished = {
                        result.set(it)
                        emit("FINISHED", "released=${it.releaseConfirmed} reason=${it.reason} candidate=${it.choice?.candidate}")
                        finished.countDown()
                    }, onProgress = { emit("PROGRESS", it) })
                assertTrue(requireNotNull(check).start())
            }
            assertTrue("Sample check must reach a terminal result", finished.await(310, TimeUnit.SECONDS))
            val completed = requireNotNull(result.get())
            assertTrue("Native worker death must be confirmed", completed.releaseConfirmed)
            val choice = requireNotNull(completed.choice) { "No usable sample combination: ${completed.reason}" }
            assertEquals(JointRuntimeMeasurementKind.SAMPLE_IMAGES_TWO_MODEL, choice.measurementKind)
            assertTrue(JointRuntimeChoicePolicy.validateCandidate(choice.candidate, choice.evidence,
                Runtime.getRuntime().availableProcessors()))
            assertTrue(worker.submit<Boolean> { AndroidJointRuntimeChoiceStore(context).save(config, choice) }.get(10, TimeUnit.SECONDS))
            val restored = worker.submit<ValidatedJointRuntimeChoice?> { AndroidJointRuntimeChoiceStore(context).load(config) }
                .get(10, TimeUnit.SECONDS)
            assertEquals(choice, restored)
            emit("SAVED", "sample_only=true actual_camera_evidence=false choice=${choice.candidate}")
        } finally {
            current.set(false)
            instrumentation.runOnMainSync { check?.cancel("sample_test_finished") }
            assertTrue("Cancellation must finish without owning the camera", check == null || finished.await(15, TimeUnit.SECONDS))
            worker.shutdown()
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
    private fun emit(stage: String, detail: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0,
            Bundle().apply { putString("stream", "FIXTURE_COMPOSITION stage=$stage $detail\n") })
    }
}

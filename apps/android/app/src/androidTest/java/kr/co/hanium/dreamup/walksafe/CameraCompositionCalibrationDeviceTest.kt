package kr.co.hanium.dreamup.walksafe

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.composite.*
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionDiagnosticScope
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Explicit diagnostic opt-in. Exercises candidate search only; never saves a production PASS.
 * The separate JointRuntimeSampleHostAndroidTest covers actual final strict/soak qualification.
 */
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
            val scope = worker.submit<VisionDiagnosticScope> { VisionDiagnosticScope(context) }.get(30, TimeUnit.SECONDS)
            val runtimeContext = scope.context("composition-search")
            val owner = VisionRuntimeContext.ownerRef(runtimeContext)
            val candidate = RuntimeSelectionPolicy.initialCpuCandidate(Runtime.getRuntime().availableProcessors(), defaultThreads = 2)
            val deadline = SystemClock.elapsedRealtime() + 300_000L
            instrumentation.runOnMainSync {
                check = AndroidJointRuntimeCheck(runtimeContext, config, candidate, deadline,
                    current::get, { false }, onFinished = {
                        result.set(it)
                        emit("FINISHED", "released=${it.releaseConfirmed} reason=${it.reason} candidate=${it.selection?.plan?.candidate}")
                        finished.countDown()
                    }, onProgress = { emit("PROGRESS", it) })
                assertTrue(requireNotNull(check).start())
            }
            assertTrue("Sample check must reach a terminal result", finished.await(310, TimeUnit.SECONDS))
            val completed = requireNotNull(result.get())
            assertTrue("Native worker death must be confirmed", completed.releaseConfirmed)
            val selection = requireNotNull(completed.selection) { "No usable sample combination: ${completed.reason}" }
            requireNotNull(selection.claimForFinal(owner.source, owner.sessionGeneration, SystemClock.elapsedRealtime())).use { claim ->
                assertEquals(JointRuntimeSearchPhase.FINAL, claim.plan.budget.phase)
                assertEquals(AndroidJointRuntimeChoiceStore.createBinding(context, config), claim.bindingHash)
                emit("PROVISIONAL", "sample_only=true final_qualified=false production_saved=false candidate=${claim.plan.candidate}")
            }
        } finally {
            current.set(false)
            result.get()?.selection?.discardIfUnclaimed()
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

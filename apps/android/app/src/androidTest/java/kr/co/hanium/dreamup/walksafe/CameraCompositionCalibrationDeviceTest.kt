package kr.co.hanium.dreamup.walksafe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.composite.*
import kr.co.hanium.dreamup.walksafe.runtime.vision.primary.PrimaryDetectorLifecycleController
import kr.co.hanium.dreamup.walksafe.runtime.vision.primary.PrimaryRuntimeCalibrationOwner
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Physical component proof only; account, consent, education and HOME gates remain unchanged. */
@RunWith(AndroidJUnit4::class)
class CameraCompositionCalibrationDeviceTest {
    @Test(timeout = 420_000L)
    fun liveCombinedWorkloadMeasuresBothModelsAndReleasesItsCamera() {
        assumeTrue("Explicit opt-in required", InstrumentationRegistry.getArguments().getString("allowCameraCompositionCalibration") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("Existing camera permission required", PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.CAMERA))
        val worker = Executors.newSingleThreadExecutor()
        val current = AtomicBoolean(true)
        val closed = CountDownLatch(1)
        val selected = AtomicReference<JointRuntimeCheckResult>()
        val host = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val lifecycle = getter(host, "getPrimaryDetectorLifecycleController") as PrimaryDetectorLifecycleController<*>
        val calibration = getter(host, "getPrimaryRuntimeCalibrationOwner") as PrimaryRuntimeCalibrationOwner<*>
        var check: AndroidJointRuntimeCheck? = null
        var restored: AndroidJointRuntimeLoader.Prepared? = null
        val deadline = SystemClock.elapsedRealtime() + 300_000L
        try {
            val config = worker.submit<TwoModelRuntimeConfig> { TwoModelRuntimeConfig.load(context) }.get(45, TimeUnit.SECONDS)
            instrumentation.runOnMainSync { lifecycle.loadForCurrentProcess() }
            val readyBy = minOf(deadline - 30_000L, SystemClock.elapsedRealtime() + 90_000L)
            while (SystemClock.elapsedRealtime() < readyBy && !lifecycle.verifiedForOutput()) SystemClock.sleep(100L)
            assertTrue("Actual primary must verify before native retirement", lifecycle.verifiedForOutput())
            val candidate = requireNotNull(calibration.projection().activeCandidate)
            val retired = CountDownLatch(1)
            val released = AtomicBoolean(false)
            instrumentation.runOnMainSync { lifecycle.releaseForCompositeCheck(current::get) { released.set(it); retired.countDown() } }
            assertTrue("Existing Main primary must release before trials", retired.await(30, TimeUnit.SECONDS))
            assertTrue("Native retirement must acknowledge release", released.get())
            emit("BEGIN", "component_only=true primary_retired=true deadline_ms=$deadline")
            instrumentation.runOnMainSync {
                check = AndroidJointRuntimeCheck(host, config, candidate, deadline, current::get, { false }) {
                    selected.set(it)
                    emit("CLOSED", "released=${it.releaseConfirmed} scope=${it.scope} reason=${it.reason} candidate=${it.choice?.candidate}")
                    closed.countDown()
                }
                assertTrue(requireNotNull(check).start())
            }
            assertTrue("Joint check and camera/native release must finish", closed.await(310, TimeUnit.SECONDS))
            val result = requireNotNull(selected.get())
            assertTrue("Native/camera owners must release", result.releaseConfirmed)
            val choice = requireNotNull(result.choice) { "No usable joint configuration: ${result.reason}" }
            val evidence = choice.evidence
            emit("RESULT", "component_only=true choice_usable=true primary_completed=${evidence.primaryAgesMs.size} aux_completed=${evidence.auxiliaryAgesMs.size} fresh=${evidence.freshAuxiliaryFrames} scope=${choice.scope} mode=${choice.cameraMode}")
            assertTrue(JointRuntimeChoicePolicy.validateCandidate(choice.candidate, choice.scope, evidence, Runtime.getRuntime().availableProcessors()))
            assertTrue("Only an actual usable choice may persist", worker.submit<Boolean> { AndroidJointRuntimeChoiceStore(context).save(config, choice) }.get(10, TimeUnit.SECONDS))
            restored = worker.submit<AndroidJointRuntimeLoader.Prepared?> { AndroidJointRuntimeLoader.restore(context, config, choice.scope, choice.cameraMode, current::get) }.get(60, TimeUnit.SECONDS)
            assertNotNull("Exact joint choice must actually restore", restored)
            assertEquals(choice.candidate, restored!!.choice.candidate)
            assertFalse(restored!!.load.fallbackUsed)
            emit("RESTORED", "actual_primary_warm_verified=true paired_choice=${restored!!.choice.candidate}")
        } finally {
            current.set(false)
            instrumentation.runOnMainSync { check?.cancel("device_test_finished") }
            assertTrue("Trial cleanup must complete", check == null || closed.await(30, TimeUnit.SECONDS))
            worker.submit { restored?.load?.detector?.close() }.get(30, TimeUnit.SECONDS)
            instrumentation.runOnMainSync { lifecycle.finishCompositeCheck(reload = false); if (!host.isDestroyed) host.finish() }
            worker.shutdown()
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
    private fun getter(target: Any, name: String): Any = target.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(target)!!
    private fun emit(stage: String, detail: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "CAMERA_JOINT stage=$stage $detail\n") })
    }
}

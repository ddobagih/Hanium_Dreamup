package kr.co.hanium.dreamup.walksafe.runtime.vision.remote

import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.CameraRuntimeHandoff
import kr.co.hanium.dreamup.walksafe.inference.ModelRuntimeOptions
import kr.co.hanium.dreamup.walksafe.inference.RuntimeCalibrationFixtures
import kr.co.hanium.dreamup.walksafe.inference.TfliteAndroidFrameDetector
import kr.co.hanium.dreamup.walksafe.inference.TwoModelRuntimeConfig
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamExecutionRequest
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamResult
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeOptions
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeService
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Real bundled CPU models through production adapters. No camera, account or permission mutation. */
@RunWith(AndroidJUnit4::class)
class VisionProductionModelsAndroidTest {
    @Test(timeout = 120_000L)
    fun realCpuPairTransfersBothWaysWithoutOpeningOrWarmingReplacementModels() {
        assumeTrue(BuildConfig.DEBUG && InstrumentationRegistry.getArguments()
            .getString("visionProductionModelTests") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val config = TwoModelRuntimeConfig.load(app).copy(fallbackModelKey = null, customTactile = null,
            cocoGeneral = null, personSupplement = null)
        val fixtures = RuntimeCalibrationFixtures(app)
        val fixture = fixtures.loadManifest().fixtures.first { it.required }
        val image = fixtures.loadArgb(fixture)
        val scope = VisionDiagnosticScope(app)
        val client = RemoteVisionClient.forMainProcess(app)
        var ownerId = "roundtrip-${java.util.UUID.randomUUID()}"
        var context = scope.context(ownerId)
        val originalMainId = ownerId
        val originalMainOwner = scope.owner(ownerId)
        var lease = client.acquireForDiagnostics(scope.owner(ownerId), SystemClock.elapsedRealtime() + 5_000L, scope.authority)
        val physical = client.actualReleasedFuture(lease.cohortId)
        var primary: TfliteAndroidFrameDetector? = null
        var auxiliary: FastSamRuntimeService<String>? = null
        val results = LinkedBlockingQueue<FastSamResult<String>>()
        val errors = LinkedBlockingQueue<Throwable>()
        fun listener() = object : FastSamRuntimeService.Listener<String> {
            override fun onResult(result: FastSamResult<String>) { results.add(result) }
            override fun onError(error: Throwable) { errors.add(error) }
        }
        fun options(): FastSamRuntimeOptions {
            val end = SystemClock.elapsedRealtimeNanos() + TimeUnit.SECONDS.toNanos(60)
            return FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.CPU, 1, false, end,
                800, true, false, true, end)
        }
        try {
            val initialPrimary = requireNotNull(TfliteAndroidFrameDetector.createWithStatus(context, config,
                ModelRuntimeOptions("cpu", 1, fallbackToCpu = false)).detector)
            primary = initialPrimary
            val initialAuxiliary = FastSamRuntimeService(context, 1L, options(), listener())
            auxiliary = initialAuxiliary
            val auxiliaryRuntime = initialAuxiliary.start().get(25, TimeUnit.SECONDS)
            val primaryRuntime = requireNotNull(initialPrimary.detectCalibrationImage(image).timing.modelRuntime)
            val primaryId = requireNotNull(primaryRuntime.modelInstanceId)
            val auxiliaryId = requireNotNull(auxiliaryRuntime.modelInstanceId)
            val childPid = primaryRuntime.modelOwnerPid
            repeat(2) { index ->
                val oldPrimary = requireNotNull(primary)
                val oldAuxiliary = requireNotNull(auxiliary)
                val oldLease = lease
                client.validatePair(oldLease, primaryId, auxiliaryId)
                val primaryToken = oldPrimary.detachForTransfer()
                val auxiliaryToken = oldAuxiliary.drainForTransfer().get(10, TimeUnit.SECONDS)
                assertFalse(physical.toCompletableFuture().isDone)
                ownerId = if (index == 0) "roundtrip-${java.util.UUID.randomUUID()}" else originalMainId
                if (index == 1) scope.authority.retireOwner(originalMainOwner)
                context = scope.context(ownerId)
                val deadline = SystemClock.elapsedRealtime() + 5_000L
                client.park(oldLease, deadline)
                val moved = client.transfer(oldLease, scope.owner(ownerId), deadline)
                lease = moved.lease
                assertEquals(oldLease.ownerEpoch + 1, lease.ownerEpoch)
                assertEquals(setOf(primaryId, auxiliaryId), moved.handles.map { it.modelInstanceId }.toSet())
                val attachedPrimary = requireNotNull(TfliteAndroidFrameDetector.attachTransferred(context, config,
                    primaryToken, lease, moved.handles).detector)
                primary = attachedPrimary
                val attachedAuxiliary = FastSamRuntimeService.attachTransferred(context, index + 2L, options(), listener(),
                    auxiliaryToken, lease, moved.handles.single { it.role == VisionRole.FASTSAM })
                auxiliary = attachedAuxiliary
                attachedAuxiliary.start().get(5, TimeUnit.SECONDS)
                assertFalse(attachedPrimary.isRuntimeAdmissionCurrent())
                assertFalse(attachedAuxiliary.isReady)
                client.confirmAdoption(lease, deadline)
                attachedPrimary.activateTransferred()
                assertTrue(attachedAuxiliary.activateTransferred())
                if (index == 1) {
                    assertNotSame(originalMainOwner, scope.owner(originalMainId))
                    assertThrows(VisionStaleAuthorityException::class.java) {
                        client.acquireForDiagnostics(originalMainOwner, SystemClock.elapsedRealtime() + 5_000, scope.authority)
                    }
                }
                oldPrimary.close()
                oldAuxiliary.closeAsync().get(5, TimeUnit.SECONDS)
                client.requestExit(oldLease, "old_owner_cannot_exit_transferred_pair")
                assertFalse(oldPrimary.isRuntimeAdmissionCurrent())
                assertFalse(oldAuxiliary.isReady)
                val next = attachedPrimary.detectCalibrationImage(image)
                assertTrue(next.inferenceCompleted)
                assertEquals(primaryId, next.timing.modelRuntime?.modelInstanceId)
                assertEquals(childPid, next.timing.modelRuntime?.modelOwnerPid)
                val token = FastSamFrameToken(FastSamFrameToken.Source.CALIBRATION_FIXTURE,
                    attachedAuxiliary.sessionEpoch, index + 1L, 0, 0, SystemClock.elapsedRealtimeNanos(), fixture.id,
                    image.width, image.height)
                assertEquals(FastSamRuntimeService.Admission.ACCEPTED,
                    attachedAuxiliary.submitArgb(image.width, image.height, image.pixels, token, "target-$index"))
                val result = results.poll(20, TimeUnit.SECONDS)
                assertNotNull("Target auxiliary result, error=${errors.peek()}", result)
                assertEquals("target-$index", result!!.attachment)
                assertEquals(auxiliaryId, result.runtime.modelInstanceId)
                assertEquals(childPid!!.toLong(), result.runtime.modelOwnerProcessId)
                attachedAuxiliary.consumerIdleFuture().get(5, TimeUnit.SECONDS)
                assertTrue(primaryToken.isConsumed)
                assertTrue(auxiliaryToken.isConsumed)
                assertSame(lease, client.peekLease(ownerId))
                assertFalse(physical.toCompletableFuture().isDone)
            }
            assertTrue(errors.isEmpty())
        } finally {
            auxiliary?.closeAsync()
            runCatching { primary?.close() }
            client.requestExit(lease, "real_cpu_pair_roundtrip_finished")
            physical.toCompletableFuture().get(15, TimeUnit.SECONDS)
            client.whenAvailable(lease.cohortId).toCompletableFuture().get(15, TimeUnit.SECONDS)
        }
    }

    @Test(timeout = 120_000L)
    fun realPrimaryAndFastSamKeepTheirInstancesAndRunInTwoOwnersOfOnePrivateChild() {
        assumeTrue(BuildConfig.DEBUG && InstrumentationRegistry.getArguments()
            .getString("visionProductionModelTests") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val config = context.assets.open("model-config/two_model_runtime.json").bufferedReader()
            .use { TwoModelRuntimeConfig.parse(it.readText()) }
        val fixtures = RuntimeCalibrationFixtures(context)
        val client = RemoteVisionClient.forMainProcess(context)
        val ownerId = "production-${java.util.UUID.randomUUID()}"
        val scope = VisionDiagnosticScope(context)
        val scopedContext = scope.context(ownerId)
        val lease = client.acquireForDiagnostics(scope.owner(ownerId), SystemClock.elapsedRealtime() + 5_000L, scope.authority)
        var detector: TfliteAndroidFrameDetector? = null
        var auxiliary: FastSamRuntimeService<String>? = null
        try {
            val loaded = TfliteAndroidFrameDetector.createWithStatus(scopedContext,
                config.copy(fallbackModelKey = null, customTactile = null, cocoGeneral = null),
                ModelRuntimeOptions("cpu", 1, fallbackToCpu = false))
            assertTrue("Real primary OPEN: ${loaded.reason}", loaded.detectorAvailable)
            detector = requireNotNull(loaded.detector)
            val deadlineNs = SystemClock.elapsedRealtimeNanos() + TimeUnit.SECONDS.toNanos(60L)
            val results = LinkedBlockingQueue<FastSamResult<String>>()
            val errors = LinkedBlockingQueue<Throwable>()
            auxiliary = FastSamRuntimeService(scopedContext, 1L,
                FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.CPU, 1, false, deadlineNs,
                    800L, true, false, true, deadlineNs),
                object : FastSamRuntimeService.Listener<String> {
                    override fun onResult(result: FastSamResult<String>) { results.add(result) }
                    override fun onError(error: Throwable) { errors.add(error) }
                })
            val ready = auxiliary.start().get(25L, TimeUnit.SECONDS)
            assertSame(lease, client.peekLease(ownerId))
            assertNull(client.peekLease("foreground"))
            assertFalse(ready.fallbackUsed)
            assertEquals(FastSamRuntimeOptions.Backend.CPU, ready.actualBackend)
            var primaryInstance: String? = null
            for ((index, fixture) in fixtures.loadManifest().fixtures.filter { it.required }.take(2).withIndex()) {
                val image = fixtures.loadArgb(fixture)
                val primary = detector.detectCalibrationImage(image)
                assertTrue(primary.inferenceCompleted)
                val runtime = requireNotNull(primary.timing.modelRuntime)
                assertEquals("cpu", runtime.activeDelegate)
                assertFalse(runtime.fallbackUsed)
                assertNotEquals(Process.myPid(), runtime.modelOwnerPid)
                assertEquals(ready.modelOwnerProcessId, requireNotNull(runtime.modelOwnerPid).toLong())
                assertEquals(lease.cohortId, runtime.cohortId)
                assertEquals(runtime.cohortId, ready.cohortId)
                assertNotEquals(runtime.modelOwnerThreadId, ready.modelOwnerThreadId)
                if (primaryInstance == null) primaryInstance = runtime.modelInstanceId
                assertNotNull(primaryInstance)
                assertEquals(primaryInstance, runtime.modelInstanceId)
                val token = FastSamFrameToken(FastSamFrameToken.Source.CALIBRATION_FIXTURE,
                    1L, index.toLong(), 0L, 0L, SystemClock.elapsedRealtimeNanos(), fixture.id,
                    image.width, image.height)
                assertEquals(FastSamRuntimeService.Admission.ACCEPTED,
                    auxiliary.submitArgb(image.width, image.height, image.pixels, token, fixture.id,
                        FastSamExecutionRequest(VisionPurpose.REFERENCE,
                            SystemClock.elapsedRealtimeNanos() + TimeUnit.SECONDS.toNanos(10), true)))
                val result = results.poll(20L, TimeUnit.SECONDS)
                assertNotNull("Real FastSAM result, error=${errors.peek()}", result)
                assertEquals(fixture.id, result!!.attachment)
                assertEquals(ready.modelInstanceId, result.runtime.modelInstanceId)
                assertEquals(ready.modelOwnerProcessId, result.runtime.modelOwnerProcessId)
                assertEquals(447552, result.rawOutputs.detections().remaining())
                assertEquals(1179648, result.rawOutputs.prototypes().remaining())
            }
            assertTrue(errors.isEmpty())
            auxiliary.closeAsync().get(10L, TimeUnit.SECONDS)
            auxiliary.releasedFuture().get(10L, TimeUnit.SECONDS)
            assertFalse("Closing one model must not destroy the retained primary cohort",
                client.actualReleasedFuture(lease.cohortId).toCompletableFuture().isDone)
            val again = detector.detectCalibrationImage(fixtures.loadArgb(fixtures.loadManifest().fixtures.first { it.required }))
            assertTrue(again.inferenceCompleted)
            assertEquals(primaryInstance, again.timing.modelRuntime?.modelInstanceId)
        } finally {
            // Exercise the same finalizer used by Activity teardown, including late aux drain.
            val registry = CameraRuntimeHandoff.Registry()
            val participant = registry.joinRetirement()
            val primaryDrain = CompletableFuture<Unit>()
            val retainedAuxiliary = auxiliary?.releasedFuture()
            VisionOwnerRetirement.begin(ownerId, participant, primaryDrain,
                { listOfNotNull(retainedAuxiliary) }, { client.retirementSnapshot(ownerId) },
                { captured -> client.requestExit(captured, "production_model_test_finished") })
            auxiliary?.closeAsync()
            runCatching { detector?.close() }
            primaryDrain.complete(Unit)
            participant.released.get(15L, TimeUnit.SECONDS)
            assertTrue(client.actualReleasedFuture(lease.cohortId).toCompletableFuture().isDone)
            client.whenAvailable(lease.cohortId).toCompletableFuture().get(15, TimeUnit.SECONDS)
        }
    }
}

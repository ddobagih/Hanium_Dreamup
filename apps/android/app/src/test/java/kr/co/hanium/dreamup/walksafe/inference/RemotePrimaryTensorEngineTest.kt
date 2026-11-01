package kr.co.hanium.dreamup.walksafe.inference

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemotePrimaryTensorEngineTest {
    @Test fun productionTensorFacadeParsesTheRemoteOutputAndRetainsItsActualOwner() {
        val port = Port()
        val factory = factory(port)
        val model = TfliteSingleModelDetector(model(), DetectorLoadTimingRecorder(), "unified_walksafe", factory)
        val result = model.detect(input())
        assertEquals("person", result.detections.single().className)
        assertEquals("gpu", result.runtime.activeDelegate)
        assertEquals(234, result.runtime.modelOwnerPid)
        assertEquals(55L, result.runtime.modelOwnerThreadId)
        assertEquals(port.lease.cohortId, result.runtime.cohortId)
        assertEquals("model-1", result.runtime.modelInstanceId)
        assertEquals("configured", result.runtime.gpuSerializationCacheStatus)
        assertEquals(300 * 6, model.copyLastOutput()!!.size)
        model.detect(input())
        assertEquals(listOf(VisionPurpose.WARMUP, VisionPurpose.LIVE), port.purposes)
        model.close(); factory.close()
        assertEquals(1, port.closes)
        assertTrue(factory.actualReleasedFuture.toCompletableFuture().isDone)
        assertFalse(port.death.isDone) // Other roles may still use this cohort.
    }

    @Test fun failedOpenCannotStartPartialCpuFallbackAndItsStableReceiptWaitsForLateDeath() {
        val port = Port().apply { openFailure = true }
        val factory = factory(port)
        val receipt = factory.actualReleasedFuture
        val failure = runCatching {
            TfliteSingleModelDetector(model(fallback = true), DetectorLoadTimingRecorder(), "unified_walksafe", factory)
        }.exceptionOrNull()
        assertTrue(failure is DetectorCohortRetired)
        assertEquals(listOf("gpu"), port.options.map { it.delegate })
        factory.close()
        assertFalse(receipt.toCompletableFuture().isDone)
        port.death.complete(Unit)
        assertEquals(Unit, receipt.toCompletableFuture().getNow(null))
        assertSame(receipt, (failure as DetectorCohortRetired).actualReleasedFuture)
    }

    @Test fun aRetiredCohortInvalidatesBothLegacyModelsWithoutOpeningACpuReplacement() {
        val port = Port()
        val factory = factory(port)
        val first = factory.open(model(), "gpu", DetectorLoadTimingRecorder(), "custom_tactile")
        val second = factory.open(model(), "gpu", DetectorLoadTimingRecorder(), "coco_general")
        port.invokeFailure = true
        assertTrue(runCatching { first.invoke(input().inputBuffer, output(), 2, 2, VisionPurpose.LIVE) }
            .exceptionOrNull() is DetectorCohortRetired)
        assertTrue(runCatching { second.invoke(input().inputBuffer, output(), 2, 2, VisionPurpose.LIVE) }
            .exceptionOrNull() is DetectorCohortRetired)
        assertEquals(2, port.options.size)
        assertEquals(1, port.invokes)
        assertTrue(runCatching { factory.close() }.exceptionOrNull() is DetectorCohortRetired)
        assertFalse(factory.actualReleasedFuture.toCompletableFuture().isDone)
        port.death.complete(Unit)
        assertEquals(Unit, factory.actualReleasedFuture.toCompletableFuture().getNow(null))
    }

    @Test fun aSuccessfulCloseResultCannotReplaceTheIndependentModelReleaseReceipt() {
        val port = Port().apply { completeModelReceiptOnClose = false }
        val factory = factory(port)
        factory.open(model(), "gpu", DetectorLoadTimingRecorder(), "unified_walksafe")
        factory.close()
        assertFalse(factory.actualReleasedFuture.toCompletableFuture().isDone)
        assertEquals(1, port.closes)
        port.modelReceipts.values.single().complete(Unit)
        assertTrue(factory.actualReleasedFuture.toCompletableFuture().isDone)
        assertFalse(port.death.isDone)
    }

    @Test fun mismatchedOpenRuntimeOrLaterNativeOwnerNeverPublishesTheRequestedMetadataAsObserved() {
        val wrongOpen = Port().apply { actualDelegate = "cpu" }
        val openFactory = factory(wrongOpen)
        assertTrue(runCatching { openFactory.open(model(), "gpu", DetectorLoadTimingRecorder(), "unified_walksafe") }
            .exceptionOrNull() is DetectorCohortRetired)
        openFactory.close()
        assertFalse(openFactory.actualReleasedFuture.toCompletableFuture().isDone)
        wrongOpen.death.complete(Unit)
        assertTrue(openFactory.actualReleasedFuture.toCompletableFuture().isDone)

        val changed = Port()
        val factory = factory(changed)
        val engine = factory.open(model(), "gpu", DetectorLoadTimingRecorder(), "unified_walksafe")
        changed.ownerThread = 99L
        assertTrue(runCatching { engine.invoke(input().inputBuffer, output(), 2, 2, VisionPurpose.LIVE) }
            .exceptionOrNull() is DetectorCohortRetired)
        assertEquals(55L, engine.runtime.modelOwnerThreadId)
        runCatching { factory.close() }
        changed.death.complete(Unit)
        assertTrue(factory.actualReleasedFuture.toCompletableFuture().isDone)
    }

    @Test fun previousCohortFailureBeforeOpenStillRetainsItsReleaseReceipt() {
        val port = Port().apply { acquireFailure = true }
        val factory = factory(port)
        assertTrue(runCatching { factory.open(model(), "gpu", DetectorLoadTimingRecorder(), "unified_walksafe") }
            .exceptionOrNull() is DetectorCohortRetired)
        factory.close()
        assertEquals(0, port.options.size)
        assertFalse(factory.actualReleasedFuture.toCompletableFuture().isDone)
        port.death.complete(Unit)
        assertTrue(factory.actualReleasedFuture.toCompletableFuture().isDone)
    }

    @Test fun aBusyFactoryPreservesTheOriginalExceptionAndDoesNotOwnTheOtherCohort() {
        val port = Port()
        val busy = VisionCohortBusyException(port.death.thenApply { VisionReleaseReceipt(port.lease.cohortId, 234) })
        port.busy = busy
        val factory = factory(port)
        assertSame(busy, runCatching {
            TfliteSingleModelDetector(model(fallback = true), DetectorLoadTimingRecorder(), "unified_walksafe", factory)
        }.exceptionOrNull())
        assertTrue(port.options.isEmpty())
        factory.close()
        assertEquals(Unit, factory.actualReleasedFuture.toCompletableFuture().getNow(null))
        assertFalse("Availability is somebody else's resource ownership", port.death.isDone)
        assertFalse(port.retired)
        assertSame(busy, runCatching { TfliteAndroidFrameDetector.loadModel<Unit> { throw busy } }.exceptionOrNull())
    }

    private fun factory(port: Port) = RemotePrimaryTensorFactory(port, { 1_000L }, 20_000L)
    private fun model(fallback: Boolean = false) = ModelRuntimeConfig("unified_walksafe", "models/test.tflite", 2,
        listOf("person"), setOf("person"), mapOf("person" to 0.5f), artifactSha256 = "a".repeat(64),
        runtime = ModelRuntimeOptions("gpu", 2, fallback))
    private fun input() = PreprocessedImage(ByteBuffer.allocateDirect(2 * 2 * 3 * 4).order(ByteOrder.nativeOrder()),
        LetterboxTransform(2, 2, 2, 1f, 0f, 0f))
    private fun output() = ByteBuffer.allocateDirect(300 * 6 * 4).order(ByteOrder.nativeOrder())

    private class Port : PrimaryTensorRemotePort {
        val lease = VisionCohortLease("a".repeat(64), "foreground", 1L)
        val death = CompletableFuture<Unit>()
        val options = mutableListOf<VisionRuntimeOptions>()
        val modelReceipts = linkedMapOf<String, CompletableFuture<Unit>>()
        val requested = linkedMapOf<String, VisionRuntimeOptions>()
        val purposes = mutableListOf<VisionPurpose>()
        var acquireFailure = false; var openFailure = false; var invokeFailure = false
        var busy: VisionCohortBusyException? = null
        var actualDelegate: String? = null; var ownerThread = 55L
        var completeModelReceiptOnClose = true
        var retired = false; var closes = 0; var invokes = 0
        override fun acquire(deadlineMs: Long): VisionCohortLease {
            busy?.let { throw it }
            if (acquireFailure) throw VisionRuntimeException("previous cohort", releaseReceipt = death.thenApply { VisionReleaseReceipt(lease.cohortId, 234) })
            return lease
        }
        override fun released(lease: VisionCohortLease) = death
        override fun open(lease: VisionCohortLease, spec: VisionModelSpec, deadlineMs: Long): VisionModelHandle {
            val runtime = VisionRuntimeCodec.decodeOptions(spec.runtimeJson)
            assertFalse(runtime.fallbackToCpu)
            options += runtime
            if (openFailure) throw VisionRuntimeException("OPEN failed")
            val id = "model-${options.size}"
            requested[id] = runtime
            modelReceipts[id] = CompletableFuture()
            return VisionModelHandle(lease.cohortId, id, lease.ownerEpoch, spec.role, spec.modelKey, runtimeJson(runtime))
        }
        override fun modelReleased(handle: VisionModelHandle) = modelReceipts.getValue(handle.modelInstanceId)
        override fun invoke(handle: VisionModelHandle, input: ByteBuffer, output: ByteBuffer, width: Int, height: Int,
            purpose: VisionPurpose, deadlineMs: Long): VisionInvokeResult {
            invokes++; purposes += purpose
            if (invokeFailure) throw VisionRuntimeException("invoke failed")
            output.asFloatBuffer().put(floatArrayOf(0f, 0f, 1f, 1f, 0.9f, 0f))
            return VisionInvokeResult(runtimeJson(requested.getValue(handle.modelInstanceId)), 0.0, 2.0, 0.0)
        }
        override fun close(handle: VisionModelHandle, deadlineMs: Long): VisionCloseResult {
            closes++
            if (retired) throw VisionRuntimeException("cohort retired")
            if (completeModelReceiptOnClose) modelReceipts.getValue(handle.modelInstanceId).complete(Unit)
            return VisionCloseResult(handle.modelInstanceId, true)
        }
        override fun exit(lease: VisionCohortLease, reason: String) { retired = true }
        private fun runtimeJson(value: VisionRuntimeOptions) = JSONObject().put("requestedDelegate", value.delegate)
            .put("activeDelegate", actualDelegate ?: value.delegate).put("numThreads", value.numThreads)
            .put("fallbackUsed", false).put("gpuPrecisionLossAllowed", if (value.delegate == "gpu") false else JSONObject.NULL)
            .put("gpuSerializationCacheStatus", if (value.delegate == "gpu") "configured" else JSONObject.NULL)
            .put("gpuSerializationCacheToken", if (value.delegate == "gpu") "observed-token" else JSONObject.NULL)
            .put("modelOwnerPid", 234).put("modelOwnerThreadId", ownerThread).put("loadMs", 3.0).toString()
    }
}

package kr.co.hanium.dreamup.walksafe.inference.unknown

import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture

class RemoteFastSamEngineTest {
    @Test fun remoteRawOutputsAreOwnedAndChildIdentityNeverBecomesTheParentProxyThread() {
        val f = Fixture()
        val first = f.engine.invoke(intArrayOf(0xff102030.toInt(), 0xff405060.toInt()), 2, 1)
        assertArrayEquals(intArrayOf(0xff102030.toInt(), 0xff405060.toInt()), f.pixels)
        assertEquals(2.25f, first.ownedTensors[0][0], 0f)
        f.value = 7.5f
        f.engine.invoke(intArrayOf(0, 0), 2, 1)
        assertEquals(2.25f, first.ownedTensors[0][0], 0f)
        val info = f.engine.runtime()
        assertEquals(1234L, info.modelOwnerProcessId)
        assertEquals(17L, info.modelOwnerThreadId)
        assertEquals(f.handle.modelInstanceId, info.modelInstanceId)
        assertEquals(f.handle.cohortId, info.cohortId)
        assertFalse(f.physical.isDone)
        f.engine.close(); assertTrue(f.physical.isDone)
        assertEquals(1, f.closes)
        f.engine.close(); assertEquals(1, f.closes)
    }

    @Test fun actualMetadataMismatchCannotBePublishedAsTheSelectedRuntime() {
        for (field in listOf("activeDelegate", "modelOwnerPid", "modelOwnerThreadId", "gpuPrecisionLossAllowed")) {
            val f = Fixture()
            val json = VisionRuntimeCodec.objectJson(f.handle.runtimeJson)
            when (field) {
                "activeDelegate" -> json.put(field, "gpu")
                "gpuPrecisionLossAllowed" -> json.put(field, true)
                else -> json.put(field, 999L)
            }
            f.resultJson = json.toString()
            assertThrows(RuntimeException::class.java) { f.engine.invoke(intArrayOf(0, 0), 2, 1) }
            assertFalse(f.physical.isDone)
            f.engine.close()
        }
    }

    @Test fun failedCloseOrWrongAckCannotCompletePhysicalReleaseButLateDeathCan() {
        for (wrongAck in listOf(false, true)) {
            val f = Fixture()
            f.closeFails = !wrongAck
            f.wrongCloseId = wrongAck
            assertThrows(IllegalStateException::class.java) { f.engine.close() }
            assertEquals(listOf("fastsam_close_failed"), f.exits)
            assertFalse(f.physical.isDone)
            f.physical.complete(null)
            assertTrue(f.physical.isDone)
        }
    }

    @Test fun fixtureTtlCannotChooseReferenceAndExplicitRequestsUseTheirOwnPurposeAndDeadline() {
        for (reference in listOf(false, true)) {
            val f = Fixture(reference)
            val fixture = FastSamFrameToken(FastSamFrameToken.Source.CALIBRATION_FIXTURE, 1, 1, 0, 0, 1_000_000_000L, "geometry", 2, 1)
            f.engine.invoke(intArrayOf(0, 0), 2, 1, fixture)
            assertEquals(VisionPurpose.BENCHMARK, f.purpose)
            assertEquals(3_000L, f.deadline)
            f.engine.invoke(intArrayOf(0, 0), 2, 1, fixture,
                FastSamExecutionRequest(VisionPurpose.REFERENCE, 20_000_000_000L, true))
            assertEquals(VisionPurpose.REFERENCE, f.purpose)
            assertEquals(11_000L, f.deadline)
            f.engine.invoke(intArrayOf(0, 0), 2, 1, fixture,
                FastSamExecutionRequest(VisionPurpose.WARMUP, 5_000_000_000L, false))
            assertEquals(VisionPurpose.WARMUP, f.purpose)
            assertEquals(5_000L, f.deadline)
            f.engine.invoke(intArrayOf(0, 0), 2, 1, fixture,
                FastSamExecutionRequest(VisionPurpose.BENCHMARK, 2_000_000_000L, true))
            assertEquals(VisionPurpose.BENCHMARK, f.purpose)
            assertEquals(2_000L, f.deadline)
            assertEquals(1_000_000_000L, fixture.capturedElapsedNs)
            val live = FastSamFrameToken(FastSamFrameToken.Source.LIVE_CAMERA, 1, 2, 1, 1, 1_000_000_000L, "geometry", 2, 1)
            f.engine.invoke(intArrayOf(0, 0), 2, 1, live)
            assertEquals(VisionPurpose.LIVE, f.purpose)
            assertEquals(3_000L, f.deadline)
            f.engine.close()
        }
    }

    @Test fun expiredExplicitJobCannotDispatchOrClaimThatItsNativeModelWasReleased() {
        val f = Fixture()
        val token = FastSamFrameToken(FastSamFrameToken.Source.CALIBRATION_FIXTURE, 1, 1, 0, 0,
            1_000_000_000L, "geometry", 2, 1)
        assertThrows(FastSamExecutionRequest.Expired::class.java) { f.engine.invoke(intArrayOf(0, 0), 2, 1, token,
            FastSamExecutionRequest(VisionPurpose.REFERENCE, 1_000_000_000L, true)) }
        assertNull(f.purpose)
        assertTrue(f.exits.isEmpty())
        assertFalse(f.physical.isDone)
        f.engine.close()
    }

    @Test fun liveHardDeadlineIsTwoSecondsAndA900msResponseDoesNotKillTheCohort() {
        val f = Fixture()
        val token = FastSamFrameToken(FastSamFrameToken.Source.LIVE_CAMERA, 1, 1, 1, 1,
            1_000_000_000L, "geometry", 2, 1)
        f.inferenceMs = 900.0
        f.onInvoke = { f.now = 1_900L }
        val result = f.engine.invoke(intArrayOf(0, 0), 2, 1, token)
        assertEquals(VisionPurpose.LIVE, f.purpose)
        assertEquals(3_000L, f.deadline)
        assertEquals(900.0, result.inferenceMs, 0.0)
        assertEquals(1_000_000_000L, token.capturedElapsedNs)
        assertTrue(f.exits.isEmpty())
        assertEquals(0, f.closes)
        assertFalse(f.physical.isDone)
        f.engine.close()
        val withoutToken = Fixture()
        withoutToken.engine.invoke(intArrayOf(0, 0), 2, 1)
        assertEquals(3_000L, withoutToken.deadline)
        withoutToken.engine.close()
    }

    @Test fun smallerFramesAfterLargerOnesUseAnExactCapacityWireView() {
        val f = Fixture()
        f.engine.invoke(intArrayOf(1, 2, 3, 4), 4, 1)
        f.engine.invoke(intArrayOf(5, 6), 2, 1)
        assertArrayEquals(intArrayOf(5, 6), f.pixels)
        f.engine.close()
    }

    @Test fun repeatedProxyLifetimesReuseTheSameProcessBackingWithoutRechargingDirectBytes() {
        val first = Fixture()
        val charged = ParentDirectBufferArena.process().memoryLedger.snapshot().directBytes
        first.engine.close()
        repeat(35) {
            val next = Fixture()
            assertEquals(charged, ParentDirectBufferArena.process().memoryLedger.snapshot().directBytes)
            next.engine.close()
        }
        assertEquals(charged, ParentDirectBufferArena.process().memoryLedger.snapshot().directBytes)
    }

    @Test fun detachRevokesTheOldProxyWithoutClosingNativeOrPublishingPhysicalRelease() {
        val f = Fixture()
        val token = f.engine.detachForTransfer()
        assertEquals(f.handle.cohortId, token.cohortId)
        assertEquals(f.handle.modelInstanceId, token.modelInstanceId)
        f.engine.close()
        assertEquals(0, f.closes)
        assertFalse(f.physical.isDone)
        assertThrows(IllegalStateException::class.java) { f.engine.invoke(intArrayOf(0, 0), 2, 1) }
        assertThrows(IllegalStateException::class.java) { f.engine.detachForTransfer() }
        assertTrue(f.exits.isEmpty())
        f.physical.complete(null)
    }

    @Test fun finalNativeCloseCannotBeChangedIntoTransfer() {
        val f = Fixture()
        f.engine.close()
        assertThrows(IllegalStateException::class.java) { f.engine.detachForTransfer() }
        assertEquals(1, f.closes)
    }

    private class Fixture(reference: Boolean = false) {
        val options = FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.CPU, 1, false, 20_000_000_000L,
            800, false, false, false, if (reference) 15_000_000_000L else 0L)
        private val info = FastSamRuntimeInfo(options, FastSamRuntimeOptions.Backend.CPU, null, 17L, 4.0,
            "not_applicable", null, null, 0, 1234L, null, null)
        val handle = VisionModelHandle("a".repeat(64), "model-1", 1, VisionRole.FASTSAM,
            FastSamModelContract.MODEL_ID, FastSamNativeBridge.encodeObserved(info))
        val physical = CompletableFuture<Void>()
        var pixels = intArrayOf()
        var value = 2.25f
        var resultJson = handle.runtimeJson
        var purpose: VisionPurpose? = null
        var deadline = 0L
        var closes = 0
        var closeFails = false
        var wrongCloseId = false
        var now = 1_000L
        var inferenceMs = 2.0
        var onInvoke: () -> Unit = {}
        val exits = mutableListOf<String>()
        val engine = RemoteFastSamEngine(options, VisionCohortLease(handle.cohortId, "foreground", handle.ownerEpoch), handle, { actual, input, outputs, width, height, mode, at ->
            assertSame(handle, actual)
            assertEquals(width * height * 4, input.capacity())
            assertEquals(width * height * 4, input.remaining())
            pixels = IntArray(width * height).also { input.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(it) }
            outputs[0].order(ByteOrder.LITTLE_ENDIAN).putFloat(0, value)
            outputs[1].order(ByteOrder.LITTLE_ENDIAN).putFloat(outputs[1].capacity() - 4, -value)
            purpose = mode; deadline = at
            onInvoke()
            VisionInvokeResult(resultJson, 1.0, inferenceMs, 3.0)
        }, { _, _ ->
            closes++
            if (closeFails) throw IllegalStateException("close unconfirmed")
            VisionCloseResult(if (wrongCloseId) "other-model" else handle.modelInstanceId, true)
        }, { exits += it }, physical, { now })
    }
}

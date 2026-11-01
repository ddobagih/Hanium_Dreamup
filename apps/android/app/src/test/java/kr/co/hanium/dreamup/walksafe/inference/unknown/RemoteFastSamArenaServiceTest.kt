package kr.co.hanium.dreamup.walksafe.inference.unknown

import kr.co.hanium.dreamup.walksafe.inference.composite.JointRuntimeMemoryLedger
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The real Java post/listener drain controls a pooled parent buffer; no Binder/native work here. */
class RemoteFastSamArenaServiceTest {
    @Test fun aBlockedPostConsumerKeepsTheArenaBusyUntilItsActualHostBarrierFinishes() {
        var allocations = 0
        val ledger = JointRuntimeMemoryLedger()
        val arena = ParentDirectBufferArena(ledger) { bytes -> allocations++; ByteBuffer.allocateDirect(bytes) }
        val sizes = intArrayOf(FastSamModelContract.DETECTION_FLOATS * 4, FastSamModelContract.PROTOTYPE_FLOATS * 4)
        val options = FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.CPU, 1, false,
            System.nanoTime() + TimeUnit.SECONDS.toNanos(30), 800, false)
        val info = FastSamRuntimeInfo(options, FastSamRuntimeOptions.Backend.CPU, null, 42, 1.0,
            "not_applicable", null, null, 0, 321, "auxiliary", "c".repeat(64))
        val handle = VisionModelHandle("c".repeat(64), "auxiliary", 1, VisionRole.FASTSAM,
            FastSamModelContract.MODEL_ID, FastSamNativeBridge.encodeObserved(info))
        val lease = VisionCohortLease(handle.cohortId, "unit-host", 1)
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val service = FastSamRuntimeService<Void>(12, options, FastSamRuntimeService.Listener { _ ->
            entered.countDown(); check(resume.await(5, TimeUnit.SECONDS))
        }, FastSamEngine.Factory {
            RemoteFastSamEngine(options, lease, handle, { _, _, _, _, _, _, _ ->
                VisionInvokeResult(handle.runtimeJson, 0.0, 0.0, 0.0)
            }, { _, _ -> VisionCloseResult(handle.modelInstanceId, true) }, {}, CompletableFuture(),
                nowMs = { System.nanoTime() / 1_000_000L },
                buffers = arena.acquire("aux", 8, sizes))
        }, System::nanoTime)
        try {
            service.start().get(3, TimeUnit.SECONDS)
            val token = FastSamFrameToken(FastSamFrameToken.Source.CALIBRATION_FIXTURE, 12, 1, 0, 0,
                System.nanoTime(), "fixture", 2, 1)
            assertEquals(FastSamRuntimeService.Admission.ACCEPTED, service.submitArgb(2, 1, intArrayOf(0, 0), token, null))
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val closed = service.closeAsync()
            assertFalse(closed.isDone)
            assertThrows(IllegalStateException::class.java) { arena.acquire("aux", 8, sizes) }
            assertEquals(3, allocations)
            resume.countDown(); closed.get(3, TimeUnit.SECONDS)
            val next = arena.acquire("aux", 8, sizes)
            assertEquals(3, allocations)
            assertEquals(8L + sizes.sum(), ledger.snapshot().directBytes)
            next.releaseAfterHostDrain()
        } finally { resume.countDown(); service.closeAsync().get(5, TimeUnit.SECONDS) }
    }
}

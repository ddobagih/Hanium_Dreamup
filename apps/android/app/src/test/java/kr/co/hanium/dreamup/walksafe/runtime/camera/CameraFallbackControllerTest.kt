package kr.co.hanium.dreamup.walksafe.runtime.camera

import androidx.lifecycle.Lifecycle
import com.google.ar.core.ArCoreApk
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kr.co.hanium.dreamup.walksafe.inference.OwnedYuvFrame
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

class CameraFallbackControllerTest {
    @Test fun unbindExceptionTerminatesOnceAndKeepsTheUncertainCameraBlocked() {
        assertReleaseExceptionIsTerminal { it.unbindFailure = true }
    }

    @Test fun closeObserverExceptionTerminatesOnceAndIgnoresItsLateProof() {
        assertReleaseExceptionIsTerminal { it.awaitFailure = true }
    }

    @Test fun analyzerClearExceptionCannotAcknowledgeReleaseEvenWithClosedProof() {
        val f = Fixture()
        val resources = f.bind().also { it.clearFailure = true }
        val completed = mutableListOf<String>()
        f.controller.stop(f.epoch, onReleased = { completed += "released" },
            onReleaseFailed = { completed += "failed" })
        f.detector.drain()
        resources.completeClose(true)
        assertEquals(listOf("failed"), completed)
        assertTrue(f.controller.state.releaseBlocked)
        assertEquals(1, resources.operations.count { it == "unbind" })
    }

    @Test fun rejectedBarrierDeadlineDoesNotStrandTheReleaseAttempt() {
        val f = Fixture()
        val resources = f.bind()
        f.scheduler.rejected = true
        val completed = mutableListOf<String>()
        f.controller.stop(f.epoch, onReleased = { completed += "released" },
            onReleaseFailed = { completed += "failed" })
        assertEquals(listOf("failed"), completed)
        assertTrue(f.controller.state.releaseBlocked)
        assertFalse(f.controller.state.cleanupPending)
        assertEquals(listOf("clear"), resources.operations)
    }

    private fun assertReleaseExceptionIsTerminal(configure: (FakeResources) -> Unit) {
        val f = Fixture()
        val resources = f.bind().also(configure)
        val completed = mutableListOf<String>()
        f.controller.stop(f.epoch, onReleased = { completed += "released" },
            onReleaseFailed = { completed += "failed" })
        f.detector.drain()
        assertEquals(listOf("failed"), completed)
        assertFalse(f.controller.state.cleanupPending)
        assertTrue(f.controller.state.releaseBlocked)
        resources.completeCloseIfRegistered(true)
        f.scheduler.fire()
        assertEquals(listOf("failed"), completed)
        f.start()
        assertEquals("No new bind without actual release proof", 1, f.source.requests.size)
        f.controller.stop(f.epoch, onReleased = { completed += "false release" },
            onReleaseFailed = { completed += "still blocked" })
        assertEquals(listOf("failed", "still blocked"), completed)
        assertEquals(1, resources.operations.count { it == "unbind" })
    }

    @Test fun rejectedMainReleaseDispatchTerminatesWithoutClaimingNativeRelease() {
        val f = Fixture()
        val resources = f.bind()
        val completed = mutableListOf<String>()
        f.mainRejected = true
        f.controller.stop(f.epoch, onReleased = { completed += "released" },
            onReleaseFailed = { completed += "failed" })
        f.detector.drain()
        assertEquals(listOf("failed"), completed)
        assertEquals(listOf("clear"), resources.operations)
        assertFalse(f.controller.state.cleanupPending)
        assertTrue(f.controller.state.releaseBlocked)
        f.scheduler.fire()
        assertEquals(listOf("failed"), completed)
    }

    @Test fun duplicateOldCloseProofCannotCompleteTheSuccessorsRelease() {
        val f = Fixture()
        val old = f.bind()
        val completed = mutableListOf<String>()
        f.controller.stop(f.epoch, onReleased = { completed += "old" })
        f.detector.drain()
        old.completeClose(true)
        val next = f.bind()
        f.controller.stop(f.epoch, onReleased = { completed += "next" })
        old.completeClose(true)
        assertEquals(listOf("old"), completed)
        assertTrue(f.controller.state.cleanupPending)
        f.detector.drain()
        next.completeClose(true)
        old.completeClose(false)
        next.completeClose(true)
        assertEquals(listOf("old", "next"), completed)
        assertFalse(f.controller.state.releaseBlocked)
    }

    @Test fun synchronousCloseMayReenterWithANewStopWithoutLosingItsContinuation() {
        val f = Fixture()
        val old = f.bind().also { it.synchronousClose = true }
        var next: FakeResources? = null
        val completed = mutableListOf<String>()
        f.controller.stop(f.epoch, onReleased = {
            completed += "old"
            next = f.bind()
            f.controller.stop(f.epoch, onReleased = { completed += "next" })
        })
        f.detector.drain()
        old.completeClose(true)
        assertEquals(listOf("old"), completed)
        assertTrue(f.controller.state.cleanupPending)
        requireNotNull(next).completeClose(true)
        assertEquals(listOf("old", "next"), completed)
    }

    @Test fun retiredProviderCallbackDoesNotBindAfterANewRequestOwnsTheSource() {
        val f = Fixture()
        f.start()
        val retired = f.source.requests.single()
        f.controller.stop(f.epoch)
        f.start()
        assertFalse(retired.isCurrent())
        assertEquals(0, f.rotationReads)
        f.rotation = 3
        retired.complete(FakeResources())
        assertEquals("A retired provider must not read display rotation", 0, f.rotationReads)
        assertFalse(f.controller.state.running)
        val current = FakeResources()
        f.source.requests.last().complete(current)
        assertTrue(f.controller.state.running)
        assertTrue(f.controller.state.hasCamera)
        assertEquals(listOf(3, 3), f.source.rotations)
        assertEquals("Analysis and preview read the live display rotation at their own build points", 2, f.rotationReads)
        assertEquals(1, f.events.filterIsInstance<CameraFallbackEvent.Bound>().size)
    }

    @Test fun stopRetiresLeaseAndClearsAnalyzerBeforeTheOriginalExecutorBarrierUnbinds() {
        val f = Fixture()
        val resources = f.bind()
        val lease = (f.events.last() as CameraFallbackEvent.Bound).lease
        val completed = mutableListOf<Boolean>()
        f.controller.stop(f.epoch, onReleased = { completed += true })
        assertFalse(f.controller.isCurrent(lease))
        assertEquals(listOf("clear"), resources.operations)
        assertTrue(f.controller.state.cleanupPending)
        assertFalse(f.controller.state.hasCamera)
        f.detector.drain()
        assertEquals(listOf("clear", "unbind", "awaitClosed"), resources.operations)
        assertTrue(completed.isEmpty())
        resources.completeClose(true)
        assertEquals(listOf(true), completed)
        assertFalse(f.controller.state.cleanupPending)
        assertFalse(f.controller.state.releaseBlocked)
    }

    @Test fun closedProofFailureBlocksAnotherCameraAndNeverCallsReleased() {
        val f = Fixture()
        val resources = f.bind()
        val completed = mutableListOf<String>()
        f.controller.stop(f.epoch, onReleased = { completed += "released" },
            onReleaseFailed = { completed += "failed" })
        f.detector.drain()
        resources.completeClose(false)
        assertEquals(listOf("failed"), completed)
        assertTrue(f.controller.state.releaseBlocked)
        assertFalse(f.controller.state.cleanupPending)
    }

    @Test fun analyzerBarrierTimeoutCannotBecomeSuccessEvenIfTheCameraLaterCloses() {
        val f = Fixture()
        val resources = f.bind()
        val completed = mutableListOf<String>()
        f.controller.stop(f.epoch, onReleased = { completed += "released" },
            onReleaseFailed = { completed += "failed" })
        f.scheduler.fire()
        f.detector.drain()
        assertEquals(1, resources.operations.count { it == "unbind" })
        resources.completeClose(true)
        assertEquals(listOf("failed"), completed)
        assertTrue(f.controller.state.releaseBlocked)
    }

    @Test fun lifecyclePreparationFailureDoesNotAcknowledgeClosedResources() {
        val f = Fixture()
        val resources = f.bind()
        f.lifecycleFailure = true
        val completed = mutableListOf<String>()
        f.controller.stop(f.epoch, onReleased = { completed += "released" },
            onReleaseFailed = { completed += "failed" })
        f.detector.drain()
        resources.completeClose(true)
        assertEquals(listOf("failed"), completed)
        assertTrue(f.controller.state.releaseBlocked)
    }

    @Test fun aSecondStopReplacesOnlyThePendingContinuationWithoutUnbindingTwice() {
        val f = Fixture()
        val resources = f.bind()
        val completed = mutableListOf<String>()
        f.controller.stop(f.epoch, onReleased = { completed += "retired" })
        f.controller.stop(f.epoch, onReleased = { completed += "current" })
        f.detector.drain()
        resources.completeClose(true)
        assertEquals(listOf("current"), completed)
        assertEquals(1, resources.operations.count { it == "unbind" })
    }

    @Test fun admittedFrameCopiesOnlyWhenRequestedAndClosesBeforeNativeConsumerRuns() {
        val f = Fixture()
        val image = FakeImage()
        var owned: CameraFallbackFrameInput? = null
        f.consumer = { capture, _ ->
            assertEquals(812_000_003L, capture.imageTimestampNs)
            assertEquals(3, capture.imageQuarterTurns)
            assertTrue(capture.hasImage())
            capture.inspectLuma { buffer, row, pixel ->
                assertNotNull(buffer); assertEquals(2, row); assertEquals(1, pixel)
            }
            assertEquals(0, image.copies)
            owned = capture.copyOwnedInputAndClose()
            assertEquals(1, image.closes)
            assertFalse(capture.hasImage())
        }
        f.controller.consumeImage(image, CameraFallbackLease(f.epoch, 0))
        assertSame(image.owned, requireNotNull(owned).cameraFrame)
        assertEquals(812_000_003L, requireNotNull(owned).imageTimestampNs)
        assertEquals(3, requireNotNull(owned).imageQuarterTurns)
        assertEquals(1, image.copies)
        assertEquals(1, image.closes)
    }

    @Test fun rejectedAndThrowingConsumersCloseTheOriginalImageWithoutInventingPixels() {
        val f = Fixture()
        val rejected = FakeImage()
        f.consumer = { _, _ -> }
        f.controller.consumeImage(rejected, CameraFallbackLease(f.epoch, 0))
        assertEquals(0, rejected.copies)
        assertEquals(1, rejected.closes)
        val failed = FakeImage()
        val cause = IllegalStateException("controlled policy failure")
        f.consumer = { _, _ -> throw cause }
        assertSame(cause, assertThrows(IllegalStateException::class.java) {
            f.controller.consumeImage(failed, CameraFallbackLease(f.epoch, 0))
        })
        assertEquals(0, failed.copies)
        assertEquals(1, failed.closes)
    }

    @Test fun analyzedFrameNoticeBelongsToItsExactCurrentGenerationOnce() {
        val f = Fixture()
        f.bind()
        val lease = (f.events.last() as CameraFallbackEvent.Bound).lease
        assertTrue(f.controller.markFrameAnalyzed(lease))
        assertFalse(f.controller.markFrameAnalyzed(lease))
        f.controller.stop(f.epoch)
        assertFalse(f.controller.markFrameAnalyzed(lease))
    }

    private class Fixture {
        val epoch = WalkRuntimeEpoch("camera-fallback-test", 0L)
        val source = FakeSource()
        val detector = QueuedExecutor()
        val scheduler = FakeScheduler()
        val events = mutableListOf<CameraFallbackEvent>()
        var lifecycleFailure = false
        var mainRejected = false
        var rotation = 0
        var rotationReads = 0
        var consumer: (CameraFallbackFrameCapture, CameraFallbackLease) -> Unit = { _, _ -> }
        val controller = CameraFallbackController(source, {
            if (lifecycleFailure) throw IllegalStateException("controlled lifecycle failure")
        }, detector, Executor { if (mainRejected) throw RejectedExecutionException("main closed") else it.run() }, scheduler, { it == epoch }, { true },
            { events += it }, { frame, lease -> consumer(frame, lease) })
        fun start() {
            controller.request(CameraFallbackStartReason.ARCORE_DEPTH_UNSUPPORTED,
                ArCoreApk.Availability.SUPPORTED_INSTALLED)
            controller.start(epoch) { rotationReads += 1; rotation }
        }
        fun bind(): FakeResources = FakeResources().also { start(); source.requests.last().complete(it) }
    }

    private class FakeSource : CameraFallbackSource {
        data class Request(val isCurrent: () -> Boolean, val bind: (CameraFallbackResources) -> Unit) {
            fun complete(resources: CameraFallbackResources) { if (isCurrent()) bind(resources) }
        }
        val requests = mutableListOf<Request>()
        val rotations = mutableListOf<Int>()
        override fun requestBind(rotation: () -> Int, isCurrent: () -> Boolean,
            onImage: (CameraFallbackImageAccess) -> Unit, onBound: (CameraFallbackResources) -> Unit,
            onFailure: (Exception) -> Unit) { requests += Request(isCurrent) { resources ->
                rotations += rotation()
                rotations += rotation()
                onBound(resources)
            }
        }
    }

    private class FakeResources : CameraFallbackResources {
        override val hasCamera = true
        override val hasAnalysis = true
        override val hasResources = true
        val operations = mutableListOf<String>()
        var unbindFailure = false
        var clearFailure = false
        var awaitFailure = false
        var synchronousClose = false
        private var completion: ((Boolean) -> Unit)? = null
        override fun clearAnalyzer() {
            operations += "clear"
            if (clearFailure) throw IllegalStateException("clear")
        }
        override fun unbindOwned() {
            operations += "unbind"
            if (unbindFailure) throw IllegalStateException("unbind")
        }
        override fun awaitClosed(onComplete: (Boolean) -> Unit) {
            operations += "awaitClosed"; completion = onComplete
            if (awaitFailure) throw IllegalStateException("awaitClosed")
            if (synchronousClose) onComplete(true)
        }
        fun completeClose(closed: Boolean) { requireNotNull(completion).invoke(closed) }
        fun completeCloseIfRegistered(closed: Boolean) { completion?.invoke(closed) }
    }

    private class FakeImage : CameraFallbackImageAccess {
        override val timestampNs = 812_000_003L
        override val quarterTurns = 3
        override val width = 2
        override val height = 2
        val owned = OwnedYuvFrame.copyOf(2, 2, ByteBuffer.wrap(byteArrayOf(80, 80, 80, 80)),
            ByteBuffer.wrap(byteArrayOf(120)), ByteBuffer.wrap(byteArrayOf(120)), 2, 1, 1, 1)
        var copies = 0
        var closes = 0
        override fun hasImage() = true
        override fun <T> inspectLuma(block: (ByteBuffer?, Int?, Int?) -> T): T =
            block(ByteBuffer.wrap(byteArrayOf(80, 80, 80, 80)), 2, 1)
        override fun copyOwnedFrame(): OwnedYuvFrame { copies += 1; return owned }
        override fun close() { closes += 1 }
    }

    private class QueuedExecutor : Executor {
        private val queued = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queued += command }
        fun drain() { while (queued.isNotEmpty()) queued.removeFirst().run() }
    }

    private class FakeScheduler : CameraFallbackScheduler {
        private val queued = mutableListOf<Runnable>()
        var rejected = false
        override fun postDelayed(action: Runnable, delayMs: Long) {
            assertEquals(2_000L, delayMs)
            if (rejected) throw RejectedExecutionException("scheduler closed")
            queued += action
        }
        override fun remove(action: Runnable) { queued.remove(action) }
        fun fire() { queued.toList().forEach { it.run() } }
    }
}

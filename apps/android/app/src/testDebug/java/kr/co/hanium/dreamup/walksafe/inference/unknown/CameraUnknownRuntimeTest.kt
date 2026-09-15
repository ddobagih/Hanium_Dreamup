package kr.co.hanium.dreamup.walksafe.inference.unknown

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kr.co.hanium.dreamup.walksafe.CameraUnknownRuntime
import org.junit.Assert.*
import org.junit.Test

class CameraUnknownRuntimeTest {
    @Test fun explicitRetryReplacesFailedEngineAndAcceptsFramesWithUniqueEpoch() = Fixture().use { f ->
        val old = f.start()
        f.fail(old)
        old.releasedFuture().get(3, TimeUnit.SECONDS)
        runCatching { old.closeAsync().get(3, TimeUnit.SECONDS) }
        f.drain()
        assertEquals(CameraUnknownRuntime.Status.FAILED, f.runtime.status)
        f.runtime.retry()
        f.drain()
        val replacement = requireNotNull(f.runtime.service)
        replacement.start().get(3, TimeUnit.SECONDS)
        f.drain()
        assertNotSame(old, replacement)
        assertTrue(replacement.sessionEpoch > old.sessionEpoch)
        assertEquals(2, f.services.size)
        assertFalse(f.runtime.accepts(old.sessionEpoch))
        assertTrue(f.runtime.accepts(replacement.sessionEpoch))
        assertEquals(FastSamRuntimeService.Admission.ACCEPTED, f.submit(replacement))
        assertTrue(f.result.await(3, TimeUnit.SECONDS))
        assertEquals(1, f.maximumNative.get())
        f.runtime.failed(old.sessionEpoch)
        f.drain()
        assertEquals(CameraUnknownRuntime.Status.READY, f.runtime.status)
    }

    @Test fun repeatedRetryWaitsForNativeReleaseAndCreatesOnlyOneReplacement() = Fixture(blockClose = true).use { f ->
        val old = f.start()
        f.fail(old)
        assertTrue(f.closeEntered.await(3, TimeUnit.SECONDS))
        repeat(3) { f.runtime.retry() }
        f.drain()
        assertEquals(1, f.services.size)
        assertEquals(CameraUnknownRuntime.Status.WAITING_RELEASE, f.runtime.status)
        f.closeGate.countDown()
        old.releasedFuture().get(3, TimeUnit.SECONDS)
        runCatching { old.closeAsync().get(3, TimeUnit.SECONDS) }
        f.drain()
        assertEquals(2, f.services.size)
        repeat(3) { f.runtime.retry() }
        assertEquals(2, f.services.size)
        assertEquals(1, f.maximumNative.get())
    }

    @Test fun pauseCancelsWaitingRetryAndResumeDoesNotAutomaticallyReplaceFailure() = Fixture(blockClose = true).use { f ->
        val old = f.start()
        f.fail(old)
        f.runtime.retry()
        f.runtime.pause()
        val pausedUpdates = f.changes
        assertFalse(f.runtime.accepts(old.sessionEpoch))
        f.closeGate.countDown()
        old.releasedFuture().get(3, TimeUnit.SECONDS)
        runCatching { old.closeAsync().get(3, TimeUnit.SECONDS) }
        f.drain()
        assertEquals(pausedUpdates, f.changes)
        assertEquals(1, f.services.size)
        f.runtime.resume()
        f.drain()
        assertSame(old, f.runtime.service)
        f.runtime.retry()
        f.drain()
        assertEquals(2, f.services.size)
    }

    @Test fun destroyCancelsWaitingRetryAndLateCallbacksNeverUpdateUi() = Fixture(blockClose = true).use { f ->
        val old = f.start()
        f.fail(old)
        f.runtime.retry()
        f.runtime.destroy()
        val destroyedUpdates = f.changes
        f.closeGate.countDown()
        old.releasedFuture().get(3, TimeUnit.SECONDS)
        runCatching { old.closeAsync().get(3, TimeUnit.SECONDS) }
        f.runtime.failed(old.sessionEpoch)
        f.drain()
        f.runtime.resume()
        f.runtime.retry()
        assertEquals(destroyedUpdates, f.changes)
        assertEquals(1, f.services.size)
        assertNull(f.runtime.service)
        assertEquals(0, f.nativeCount.get())
    }

    @Test fun failedNativeReleaseNeverCreatesAnotherEngine() = Fixture(failClose = true).use { f ->
        val old = f.start()
        f.fail(old)
        runCatching { old.releasedFuture().get(3, TimeUnit.SECONDS) }
        runCatching { old.closeAsync().get(3, TimeUnit.SECONDS) }
        repeat(3) { f.runtime.retry(); f.drain() }
        assertFalse(old.stats().nativeReleaseConfirmed)
        assertEquals(CameraUnknownRuntime.Status.RELEASE_FAILED, f.runtime.status)
        assertEquals(1, f.services.size)
        assertSame(old, f.runtime.service)
    }

    @Test fun healthyPauseResumeKeepsModelAndPausesCallbackPublication() = Fixture().use { f ->
        val service = f.start()
        f.runtime.pause()
        assertFalse(f.runtime.accepts(service.sessionEpoch))
        f.runtime.resume()
        f.runtime.retry()
        assertSame(service, f.runtime.service)
        assertTrue(f.runtime.accepts(service.sessionEpoch))
        assertEquals(1, f.services.size)
    }

    @Test fun constructorFailureWaitsForExplicitRetryAndEpochsRemainUnique() {
        val epochs = mutableListOf<Long>()
        val runtime = CameraUnknownRuntime<Void>(create = { epoch ->
            epochs += epoch
            throw IllegalStateException("factory failed")
        }, dispatch = { it() }, changed = {})
        runtime.resume()
        assertEquals(CameraUnknownRuntime.Status.FAILED, runtime.status)
        runtime.pause()
        runtime.resume()
        assertEquals(1, epochs.size)
        runtime.retry()
        assertEquals(2, epochs.size)
        assertTrue(epochs[1] > epochs[0])
        runtime.destroy()
    }

    private class Fixture(val blockClose: Boolean = false, val failClose: Boolean = false) : AutoCloseable {
        val closeGate = CountDownLatch(if (blockClose) 1 else 0)
        val closeEntered = CountDownLatch(1)
        val error = CountDownLatch(1)
        val result = CountDownLatch(1)
        val nativeCount = AtomicInteger()
        val maximumNative = AtomicInteger()
        val services = mutableListOf<FastSamRuntimeService<Void>>()
        private val ui = LinkedBlockingQueue<() -> Unit>()
        var changes = 0
        val runtime = CameraUnknownRuntime<Void>(create = ::create, dispatch = { ui.add(it) }, changed = { changes++ })

        fun start(): FastSamRuntimeService<Void> {
            runtime.resume()
            return requireNotNull(runtime.service).also { it.start().get(3, TimeUnit.SECONDS); drain() }
        }

        fun drain() { while (true) (ui.poll() ?: return).invoke() }

        fun fail(service: FastSamRuntimeService<Void>) {
            assertEquals(FastSamRuntimeService.Admission.ACCEPTED, submit(service))
            assertTrue(error.await(3, TimeUnit.SECONDS))
            drain()
        }

        fun submit(service: FastSamRuntimeService<Void>): FastSamRuntimeService.Admission {
            val token = FastSamFrameToken(FastSamFrameToken.Source.LIVE_CAMERA, service.sessionEpoch,
                1L, 100L, 100L, System.nanoTime(), "camera", 2, 1)
            return service.submitArgb(2, 1, IntArray(2), token, null)
        }

        private fun create(epoch: Long): FastSamRuntimeService<Void> {
            val first = services.isEmpty()
            val options = FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.GPU, 1, true,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(10), 5_000L, false)
            return FastSamRuntimeService(epoch, options, object : FastSamRuntimeService.Listener<Void> {
                override fun onResult(value: FastSamResult<Void>) { result.countDown() }
                override fun onError(value: Throwable) { runtime.failed(epoch); error.countDown() }
            }, {
                val count = nativeCount.incrementAndGet()
                maximumNative.accumulateAndGet(count, ::maxOf)
                object : FastSamEngine {
                    private val info = FastSamRuntimeInfo(options, FastSamRuntimeOptions.Backend.GPU,
                        null, Thread.currentThread().id, 0.0)
                    override fun runtime() = info
                    override fun invoke(argb: IntArray, width: Int, height: Int): FastSamEngine.Output {
                        if (first) throw IllegalStateException("one GPU invoke failure")
                        return FastSamEngine.Output(arrayOf(FloatArray(FastSamModelContract.DETECTION_FLOATS),
                            FloatArray(FastSamModelContract.PROTOTYPE_FLOATS)), 1.0, 2.0, 3.0)
                    }
                    override fun close() {
                        if (first) {
                            closeEntered.countDown()
                            check(closeGate.await(3, TimeUnit.SECONDS))
                            if (failClose) throw IllegalStateException("native close failed")
                        }
                        nativeCount.decrementAndGet()
                    }
                }
            }, System::nanoTime).also { services += it }
        }

        override fun close() {
            closeGate.countDown()
            runtime.destroy()
            services.forEach { runCatching { it.closeAsync().get(3, TimeUnit.SECONDS) } }
        }
    }
}

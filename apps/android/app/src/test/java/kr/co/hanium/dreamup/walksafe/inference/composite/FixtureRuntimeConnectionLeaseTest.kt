package kr.co.hanium.dreamup.walksafe.inference.composite

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class FixtureRuntimeConnectionLeaseTest {
    /** Uses the same bind/finally and final START-dispatch adapters as RemoteFixtureRuntime. */
    private class Driver {
        val lease = FixtureRuntimeConnectionLease<String>()
        var bindCalls = 0
        var unbindCalls = 0
        var startCalls = 0
        fun bind(operation: () -> Boolean = { true }) = lease.bind({ bindCalls++; operation() }, { unbind() })
        fun unbind(success: Boolean = true) {
            if (lease.claimUnbind()) { unbindCalls++; lease.finishUnbind(success) }
        }
        fun retire(success: Boolean = true) { lease.retire(); unbind(success) }
        fun hello() {
            assertEquals(FixtureRuntimeEndpointRole.FIRST, lease.endpointConnected("worker"))
            assertTrue(lease.authenticate("worker"))
        }
        fun start(operation: () -> Unit = {}) = lease.dispatchStart("worker", { true }) { startCalls++; operation() }
    }

    @Test fun cancelBeforeQueuedBindNeverCallsSdkOrStart() {
        val driver = Driver()
        driver.retire()
        assertThrows(CancellationException::class.java) { driver.bind() }
        assertEquals(0, driver.bindCalls)
        assertEquals(0, driver.unbindCalls)
        assertEquals(FixtureRuntimeReleaseProof.NO_NATIVE_START, driver.lease.releaseProof)
    }

    @Test fun falseBindStillUnbindsExactlyOnceWithoutInventingWorkerDeath() {
        val driver = Driver()
        assertFalse(driver.bind { false })
        driver.retire(success = false) // Android may report an already-unregistered dispatcher.
        driver.unbind()
        assertEquals(1, driver.bindCalls)
        assertEquals(1, driver.unbindCalls)
        assertEquals(FixtureRuntimeReleaseProof.NO_NATIVE_START, driver.lease.releaseProof)
        assertFalse(driver.lease.needsDeath)
    }

    @Test fun synchronousBindExceptionStillUnbindsExactlyOnce() {
        val driver = Driver()
        val marker = IllegalStateException("SDK bind failed")
        try { driver.bind { throw marker }; fail("bind succeeded") }
        catch (failure: IllegalStateException) { assertSame(marker, failure) }
        driver.retire(success = false)
        driver.unbind()
        assertEquals(1, driver.bindCalls)
        assertEquals(1, driver.unbindCalls)
        assertEquals(FixtureRuntimeReleaseProof.NO_NATIVE_START, driver.lease.releaseProof)
    }

    @Test fun acceptedBindWithoutAnyEndpointCanRetireItsNeverIssuedStart() {
        val driver = Driver()
        assertTrue(driver.bind())
        assertEquals(FixtureRuntimeReleaseProof.NONE, driver.lease.releaseProof)
        driver.retire()
        assertEquals(1, driver.unbindCalls)
        assertEquals(FixtureRuntimeReleaseProof.NO_NATIVE_START, driver.lease.releaseProof)
        assertThrows(CancellationException::class.java) { driver.start() }
        assertEquals(0, driver.startCalls)
    }

    @Test fun lateCallbackAfterFiveSecondHandshakeRetirementCannotAuthenticateOrStart() {
        val driver = Driver()
        driver.bind()
        driver.retire() // The handshake timeout has elapsed before the SDK delivered a callback.
        assertEquals(FixtureRuntimeEndpointRole.FIRST, driver.lease.endpointConnected("late-worker"))
        assertFalse(driver.lease.authenticate("late-worker"))
        assertThrows(CancellationException::class.java) {
            driver.lease.dispatchStart("late-worker", { true }) { driver.startCalls++ }
        }
        assertEquals(0, driver.startCalls)
        assertEquals(FixtureRuntimeReleaseProof.NO_NATIVE_START, driver.lease.releaseProof)
    }

    @Test fun cancelDuringInFlightBindDefersItsSingleUnbindUntilReturn() {
        val driver = Driver()
        val entered = CountDownLatch(1)
        val complete = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val bound = executor.submit<Boolean> { driver.bind {
                entered.countDown(); assertTrue(complete.await(3L, TimeUnit.SECONDS)); true
            } }
            assertTrue(entered.await(3L, TimeUnit.SECONDS))
            driver.retire()
            assertEquals(0, driver.unbindCalls)
            assertEquals(FixtureRuntimeReleaseProof.NO_NATIVE_START, driver.lease.releaseProof)
            complete.countDown()
            assertTrue(bound.get(3L, TimeUnit.SECONDS))
            assertEquals(1, driver.unbindCalls)
            driver.unbind()
            assertEquals(1, driver.unbindCalls)
        } finally { complete.countDown(); executor.shutdownNow() }
    }

    @Test fun queuedStartTaskRechecksRetiredPermitOnItsActualExecution() {
        val driver = Driver()
        driver.bind(); driver.hello()
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit { entered.countDown(); release.await(3L, TimeUnit.SECONDS) }
            assertTrue(entered.await(3L, TimeUnit.SECONDS))
            val queued = executor.submit { driver.start() }
            driver.retire()
            assertEquals(FixtureRuntimeReleaseProof.NO_NATIVE_START, driver.lease.releaseProof)
            release.countDown()
            try { queued.get(3L, TimeUnit.SECONDS); fail("retired START ran") }
            catch (failure: ExecutionException) { assertTrue(failure.cause is CancellationException) }
            assertEquals(0, driver.startCalls)
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun cancelAfterStartPermitRequiresRealDeathEvenIfTransportHasNotReturned() {
        val driver = Driver()
        driver.bind(); driver.hello()
        driver.start {
            driver.retire()
            assertTrue(driver.lease.needsDeath)
            assertEquals(FixtureRuntimeReleaseProof.NONE, driver.lease.releaseProof)
        }
        assertEquals(1, driver.startCalls)
        driver.lease.endpointDied("worker")
        assertEquals(FixtureRuntimeReleaseProof.WORKER_DEATH, driver.lease.releaseProof)
    }

    @Test fun throwingStartTransportCannotRollBackPossiblySentNativeWork() {
        val driver = Driver()
        driver.bind(); driver.hello()
        assertThrows(IllegalStateException::class.java) { driver.start { error("IPC delivery unknown") } }
        driver.retire()
        assertEquals(FixtureRuntimeReleaseProof.NONE, driver.lease.releaseProof)
        driver.lease.endpointDied("worker")
        assertEquals(FixtureRuntimeReleaseProof.WORKER_DEATH, driver.lease.releaseProof)
    }

    @Test fun deathBeforeUnbindCompletionCannotReleaseAnAutoCreateBinding() {
        val driver = Driver()
        driver.bind(); driver.hello(); driver.start()
        driver.lease.retire()
        assertTrue(driver.lease.claimUnbind())
        driver.lease.endpointDied("worker")
        assertEquals(FixtureRuntimeReleaseProof.NONE, driver.lease.releaseProof)
        driver.lease.finishUnbind(true)
        assertEquals(FixtureRuntimeReleaseProof.WORKER_DEATH, driver.lease.releaseProof)
    }

    @Test fun failedUnbindAfterStartKeepsFenceDespiteActualWorkerDeath() {
        val driver = Driver()
        driver.bind(); driver.hello(); driver.start(); driver.retire(success = false)
        driver.lease.endpointDied("worker")
        assertEquals(FixtureRuntimeReleaseProof.NONE, driver.lease.releaseProof)
    }

    @Test fun reconnectBeforeUnbindReceiptRequiresEveryObservedDeathAndNeverRestarts() {
        val driver = Driver()
        driver.bind(); driver.hello(); driver.start(); driver.lease.retire()
        driver.lease.endpointDied("worker")
        assertEquals(FixtureRuntimeEndpointRole.RECONNECT, driver.lease.endpointConnected("reconnected"))
        assertTrue(driver.lease.claimUnbind()); driver.lease.finishUnbind(true)
        assertFalse(driver.lease.authenticate("reconnected"))
        assertThrows(CancellationException::class.java) {
            driver.lease.dispatchStart("reconnected", { true }) { driver.startCalls++ }
        }
        assertEquals(FixtureRuntimeReleaseProof.NONE, driver.lease.releaseProof)
        driver.lease.endpointDied("reconnected")
        assertEquals(FixtureRuntimeReleaseProof.WORKER_DEATH, driver.lease.releaseProof)
        assertEquals(FixtureRuntimeEndpointRole.DUPLICATE, driver.lease.endpointConnected("worker"))
        assertEquals(FixtureRuntimeReleaseProof.WORKER_DEATH, driver.lease.releaseProof)
        assertEquals(1, driver.startCalls)
    }

    @Test fun authorityLossInsideQueuedStartPreventsTransportAdmission() {
        val driver = Driver()
        driver.bind(); driver.hello()
        assertThrows(CancellationException::class.java) {
            driver.lease.dispatchStart("worker", { false }) { driver.startCalls++ }
        }
        driver.retire()
        assertEquals(0, driver.startCalls)
        assertEquals(FixtureRuntimeReleaseProof.NO_NATIVE_START, driver.lease.releaseProof)
    }

    @Test fun schedulingFailureReleasesSupervisorAndNeverExecutesWorker() {
        val owner = Any(); val replacement = Any()
        val executed = AtomicInteger(); val aborted = AtomicInteger()
        val marker = RejectedExecutionException("scheduler stopped")
        try {
            val result = FixtureRuntimeStartup.launch(owner, { throw marker }, { executed.incrementAndGet() }, { aborted.incrementAndGet() })
            assertTrue(result.admitted); assertSame(marker, result.failure)
            assertEquals(0, executed.get()); assertEquals(1, aborted.get())
            assertTrue(FixtureRuntimeSupervisor.acquire(replacement))
        } finally { FixtureRuntimeSupervisor.release(owner, true); FixtureRuntimeSupervisor.release(replacement, true) }
    }

    @Test fun executorFailureCancelsScheduledWatchAndRollsBackEvenIfAbortThrows() {
        val owner = Any(); val replacement = Any(); val scheduled = AtomicInteger()
        val marker = RejectedExecutionException("worker stopped")
        try {
            val result = FixtureRuntimeStartup.launch(owner, { scheduled.incrementAndGet() }, { throw marker }, { error("cleanup observer") })
            assertTrue(result.admitted); assertSame(marker, result.failure); assertEquals(1, scheduled.get())
            assertTrue(FixtureRuntimeSupervisor.acquire(replacement))
        } finally { FixtureRuntimeSupervisor.release(owner, true); FixtureRuntimeSupervisor.release(replacement, true) }
    }

    @Test fun busyStartupDoesNotTouchAnotherChecksOwnershipOrLaunchAnyTask() {
        val previous = Any(); val next = Any(); val tasks = AtomicInteger()
        try {
            assertTrue(FixtureRuntimeSupervisor.acquire(previous))
            val result = FixtureRuntimeStartup.launch(next, { tasks.incrementAndGet() }, { tasks.incrementAndGet() }, { tasks.incrementAndGet() })
            assertFalse(result.admitted); assertNull(result.failure); assertEquals(0, tasks.get())
            assertTrue(FixtureRuntimeSupervisor.owns(previous))
        } finally { FixtureRuntimeSupervisor.release(previous, true); FixtureRuntimeSupervisor.release(next, true) }
    }
}

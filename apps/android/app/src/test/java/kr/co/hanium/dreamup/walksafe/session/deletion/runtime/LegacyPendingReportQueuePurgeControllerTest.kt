package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

class LegacyPendingReportQueuePurgeControllerTest {
    @Test fun constructorHasNoWorkerCallbackOrStoreSideEffect() {
        val f = Fixture()
        assertEquals(0L, f.controller.currentGeneration)
        assertFalse(f.controller.isDestroyed)
        assertEquals(0, f.purgeCalls)
        assertTrue(f.worker.isEmpty() && f.ui.isEmpty())
    }

    @Test fun concurrentRequestsCoalesceAndCallbacksRunOutsideTheSameOriginalLock() {
        val f = Fixture()
        val results = mutableListOf<Boolean>()
        val callback = { value: Boolean -> assertFalse(Thread.holdsLock(f.lock)); results += value; Unit }
        assertFalse(f.controller.schedule(callback))
        assertFalse(f.controller.schedule(callback))
        assertEquals(1, f.worker.size)
        assertEquals(1L, f.controller.currentGeneration)
        f.runWorker()
        assertEquals(listOf(false), f.postUnderLock)
        assertTrue(results.isEmpty())
        f.runUi()
        assertEquals(listOf(true, true), results)
        assertEquals(1, f.purgeCalls)
    }

    @Test fun succeededFastPathKeepsItsOriginalPostInsideLockAndCallbackOutsideLock() {
        val f = Fixture()
        f.controller.schedule(); f.runWorker(); f.runUi()
        var callback = false
        assertTrue(f.controller.schedule { value -> assertFalse(Thread.holdsLock(f.lock)); callback = value })
        assertEquals(listOf(false, true), f.postUnderLock)
        assertFalse(callback)
        f.runUi()
        assertTrue(callback)
        assertEquals(1, f.purgeCalls)
    }

    @Test fun staleCompletionDoesNotRetireTheNewGenerationOrItsWaiters() {
        val f = Fixture()
        var callback: Boolean? = null
        f.controller.schedule { callback = it }
        f.controller.complete(0L, false)
        assertTrue(f.ui.isEmpty())
        f.runWorker(); f.runUi()
        assertEquals(true, callback)
        assertEquals(1L, f.controller.currentGeneration)
    }

    @Test fun rejectedExecutorReleasesWaitersWithFalseWithoutClaimingStoreSuccess() {
        val f = Fixture(); f.rejected = true
        var callback: Boolean? = null
        assertFalse(f.controller.schedule { callback = it })
        assertEquals(0, f.purgeCalls)
        f.runUi()
        assertEquals(false, callback)
        f.rejected = false
        assertFalse(f.controller.schedule())
        assertEquals(2L, f.controller.currentGeneration)
    }

    @Test fun persistentQueueBranchDoesNotDeleteTheLegacyStoreOrReportSuccess() {
        val f = Fixture(persistent = true)
        var callback: Boolean? = null
        assertFalse(f.controller.schedule { callback = it })
        assertTrue(f.worker.isEmpty())
        f.runUi()
        assertEquals(false, callback)
        assertEquals(0, f.purgeCalls)
        assertEquals(0L, f.controller.currentGeneration)
    }

    @Test fun destroyedLifetimeDropsAlreadyPostedCallbacksAndRejectsFutureScheduling() {
        val f = Fixture()
        var callbacks = 0
        f.controller.schedule { callbacks++ }; f.runWorker()
        f.controller.destroy(); f.runUi()
        assertEquals(0, callbacks)
        assertTrue(f.controller.isDestroyed)
        assertEquals(2L, f.controller.currentGeneration)
        assertFalse(f.controller.schedule { callbacks++ })
        assertTrue(f.worker.isEmpty() && f.ui.isEmpty())
    }

    @Test fun falseStoreResultUsesExactlyThreeAttemptsAndTheOriginalBackoffs() {
        val f = Fixture(); f.succeeds = false
        assertFalse(f.controller.purgeOnWorker())
        assertEquals(3, f.purgeCalls)
        assertEquals(listOf(250L, 500L), f.delays)
    }

    @Test fun runtimeFailureIsRetriedButCheckedIoFailureStillPropagates() {
        val runtime = Fixture(); runtime.failure = IllegalStateException("controlled runtime failure")
        assertFalse(runtime.controller.purgeOnWorker())
        assertEquals(3, runtime.purgeCalls)
        val checked = Fixture(); checked.failure = IOException("controlled checked failure")
        assertThrows(IOException::class.java) { checked.controller.purgeOnWorker() }
        assertEquals(1, checked.purgeCalls)
        assertTrue(checked.delays.isEmpty())
    }

    @Test fun interruptedBackoffRestoresInterruptAndStopsBeforeAnotherStoreAttempt() {
        val f = Fixture(); f.succeeds = false; f.interrupted = true
        assertFalse(Thread.currentThread().isInterrupted)
        try {
            assertFalse(f.controller.purgeOnWorker())
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, f.purgeCalls)
        } finally { Thread.interrupted() }
    }

    private class Fixture(persistent: Boolean = false) {
        val worker = ArrayDeque<Runnable>()
        val ui = ArrayDeque<() -> Unit>()
        val postUnderLock = mutableListOf<Boolean>()
        val delays = mutableListOf<Long>()
        var rejected = false
        var interrupted = false
        var succeeds = true
        var failure: Exception? = null
        var purgeCalls = 0
        val controller: LegacyPendingReportQueuePurgeController = LegacyPendingReportQueuePurgeController("cleanup_activity_0001", persistent,
            { Executor { if (rejected) throw RejectedExecutionException(); worker.addLast(it) } },
            LegacyQueuePurgeCallbacks { callback -> postUnderLock += Thread.holdsLock(lock); ui.addLast(callback); true },
            { purgeCalls++; failure?.let { throw it }; succeeds }, { delay -> delays += delay; if (interrupted) throw InterruptedException() })
        val lock: Any get() = controller.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(controller)
        fun runWorker() = worker.removeFirst().run()
        fun runUi() = ui.removeFirst().invoke()
    }
}

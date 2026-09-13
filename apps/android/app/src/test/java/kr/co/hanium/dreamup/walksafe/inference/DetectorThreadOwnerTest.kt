package kr.co.hanium.dreamup.walksafe.inference

import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorThreadOwnerTest {
    @Test
    fun createInvokeAndReleaseStayOnOneThreadAcrossConcurrentCallers() {
        val owner = DetectorThreadOwner("test-detector-owner")
        val threads = ConcurrentLinkedQueue<Thread>()
        val callers = ConcurrentLinkedQueue<Thread>()
        val activeCalls = AtomicInteger()
        val start = CountDownLatch(1)
        val createdOn = owner.call { Thread.currentThread() }
        try {
            val invocations = (1..8).map { index ->
                background("caller-$index") {
                    await(start)
                    callers.add(Thread.currentThread())
                    owner.call {
                        assertEquals(1, activeCalls.incrementAndGet())
                        try {
                            threads.add(Thread.currentThread())
                            index * 2
                        } finally {
                            activeCalls.decrementAndGet()
                        }
                    }
                }
            }
            start.countDown()
            assertEquals((1..8).map { it * 2 }, invocations.map { it.result() })
            assertEquals(8, callers.toSet().size)
            owner.close {
                assertEquals(0, activeCalls.get())
                threads.add(Thread.currentThread())
            }
            assertEquals(9, threads.size)
            assertTrue(threads.all { it === createdOn })
            assertEquals("test-detector-owner", createdOn.name)
        } finally {
            start.countDown()
            owner.close {}
        }
    }

    @Test
    fun closeWaitsForRunningAndQueuedWorkThenReleasesOnce() {
        val owner = DetectorThreadOwner()
        val running = CountDownLatch(1)
        val finishRunning = CountDownLatch(1)
        val releasing = CountDownLatch(1)
        val finishRelease = CountDownLatch(1)
        val events = ConcurrentLinkedQueue<String>()
        try {
            val first = background("first-caller") {
                owner.call {
                    running.countDown()
                    await(finishRunning)
                    events.add("first")
                }
            }
            await(running)
            val second = background("second-caller") {
                owner.call { events.add("second") }
            }
            awaitWaiting(second.thread)
            val closing = background("first-closer") {
                owner.close {
                    events.add("release")
                    releasing.countDown()
                    await(finishRelease)
                }
            }
            awaitWaiting(closing.thread)
            assertThrows(RejectedExecutionException::class.java) { owner.call {} }
            val duplicate = background("second-closer") {
                owner.close { throw AssertionError("Duplicate release must not run") }
            }
            awaitWaiting(duplicate.thread)
            assertFalse(closing.task.isDone)
            assertFalse(duplicate.task.isDone)
            assertEquals(0, events.size)

            finishRunning.countDown()
            await(releasing)
            assertEquals(listOf("first", "second", "release"), events.toList())
            assertFalse(closing.task.isDone)
            assertFalse(duplicate.task.isDone)
            finishRelease.countDown()
            first.result()
            second.result()
            closing.result()
            duplicate.result()
            owner.close { throw AssertionError("Completed close must not release twice") }
        } finally {
            finishRunning.countDown()
            finishRelease.countDown()
            owner.close {}
        }
    }

    @Test
    fun interruptedCallerKeepsItsImageAliveUntilAcceptedWorkFinishes() {
        val owner = DetectorThreadOwner()
        val running = CountDownLatch(1)
        val finishWork = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val imageClosed = AtomicBoolean()
        try {
            val caller = background("interrupted-caller") {
                try {
                    val result = owner.call {
                        running.countDown()
                        await(finishWork)
                        assertFalse(imageClosed.get())
                        42
                    }
                    result to Thread.currentThread().isInterrupted
                } finally {
                    imageClosed.set(true)
                    returned.countDown()
                }
            }
            await(running)
            caller.thread.interrupt()
            assertFalse(returned.await(100, TimeUnit.MILLISECONDS))
            assertFalse(imageClosed.get())
            finishWork.countDown()
            assertEquals(42 to true, caller.result())
            assertTrue(imageClosed.get())
        } finally {
            finishWork.countDown()
            owner.close {}
        }
    }

    @Test
    fun interruptedQueuedCallerStillRunsAndRestoresInterruptWhenWorkFails() {
        val owner = DetectorThreadOwner()
        val running = CountDownLatch(1)
        val finishWork = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val failure = IllegalArgumentException("inference failure")
        try {
            val blocker = background("blocking-caller") {
                owner.call {
                    running.countDown()
                    await(finishWork)
                }
            }
            await(running)
            val caller = background("queued-interrupted-caller") {
                try {
                    val caught = assertThrows(IllegalArgumentException::class.java) {
                        owner.call { throw failure }
                    }
                    caught to Thread.currentThread().isInterrupted
                } finally {
                    returned.countDown()
                }
            }
            awaitWaiting(caller.thread)
            caller.thread.interrupt()
            assertFalse(returned.await(100, TimeUnit.MILLISECONDS))
            finishWork.countDown()
            blocker.result()
            val (caught, interrupted) = caller.result()
            assertSame(failure, caught)
            assertTrue(interrupted)
        } finally {
            finishWork.countDown()
            owner.close {}
        }
    }

    @Test
    fun interruptedCloseWaitsForReleaseAndRestoresInterrupt() {
        val owner = DetectorThreadOwner()
        val releasing = CountDownLatch(1)
        val finishRelease = CountDownLatch(1)
        val returned = CountDownLatch(1)
        try {
            val closing = background("interrupted-closer") {
                try {
                    owner.close {
                        releasing.countDown()
                        await(finishRelease)
                    }
                    Thread.currentThread().isInterrupted
                } finally {
                    returned.countDown()
                }
            }
            await(releasing)
            closing.thread.interrupt()
            assertFalse(returned.await(100, TimeUnit.MILLISECONDS))
            finishRelease.countDown()
            assertTrue(closing.result())
        } finally {
            finishRelease.countDown()
            owner.close {}
        }
    }

    @Test
    fun nestedCallsRunInlineWithoutDeadlock() {
        val owner = DetectorThreadOwner()
        try {
            val caller = background("nested-caller") {
                owner.call {
                    val outerThread = Thread.currentThread()
                    owner.call {
                        assertSame(outerThread, Thread.currentThread())
                        owner.call { 42 }
                    }
                }
            }
            assertEquals(42, caller.result())
        } finally {
            owner.close {}
        }
    }

    @Test
    fun ownerCannotInitiateCloseAndRejectionLeavesItOpen() {
        val owner = DetectorThreadOwner()
        val releases = AtomicInteger()
        try {
            owner.call {
                assertThrows(IllegalStateException::class.java) {
                    owner.close { releases.incrementAndGet() }
                }
            }
            assertEquals(42, owner.call { 42 })
            assertEquals(0, releases.get())
            owner.close { releases.incrementAndGet() }
            assertEquals(1, releases.get())
        } finally {
            owner.close {}
        }
    }

    @Test
    fun runningTaskMayRepeatAnExternalCloseBeforeReleaseStarts() {
        val owner = DetectorThreadOwner()
        val running = CountDownLatch(1)
        val finishWork = CountDownLatch(1)
        val events = ConcurrentLinkedQueue<String>()
        try {
            val caller = background("reentrant-closing-caller") {
                owner.call {
                    running.countDown()
                    await(finishWork)
                    owner.close { throw AssertionError("Owner must not replace queued release") }
                    events.add("work")
                }
            }
            await(running)
            val closing = background("external-closer") {
                owner.close { events.add("release") }
            }
            awaitWaiting(closing.thread)
            finishWork.countDown()
            caller.result()
            closing.result()
            assertEquals(listOf("work", "release"), events.toList())
        } finally {
            finishWork.countDown()
            owner.close {}
        }
    }

    @Test
    fun releaseMayCloseReentrantlyWithoutDeadlockOrDuplicateRelease() {
        val owner = DetectorThreadOwner()
        val releases = AtomicInteger()
        try {
            val closing = background("reentrant-closer") {
                owner.close {
                    releases.incrementAndGet()
                    owner.close { releases.incrementAndGet() }
                    assertThrows(RejectedExecutionException::class.java) { owner.call {} }
                }
            }
            closing.result()
            assertEquals(1, releases.get())
        } finally {
            owner.close {}
        }
    }

    @Test
    fun taskFailureIsUnwrappedAndDoesNotReplaceTheOwnerThread() {
        val owner = DetectorThreadOwner()
        try {
            val createdOn = owner.call { Thread.currentThread() }
            val failure = AssertionError("native invocation failed")
            val caught = assertThrows(AssertionError::class.java) { owner.call { throw failure } }
            assertSame(failure, caught)
            assertSame(createdOn, owner.call { Thread.currentThread() })
        } finally {
            owner.close {}
        }
    }

    @Test
    fun closeFailureIsUnwrappedAndSharedWithoutReleasingAgain() {
        val owner = DetectorThreadOwner()
        val failure = IllegalStateException("release failed")
        val releases = AtomicInteger()
        val first = assertThrows(IllegalStateException::class.java) {
            owner.close {
                releases.incrementAndGet()
                throw failure
            }
        }
        val second = assertThrows(IllegalStateException::class.java) {
            owner.close { releases.incrementAndGet() }
        }
        assertSame(failure, first)
        assertSame(failure, second)
        assertEquals(1, releases.get())
        assertThrows(RejectedExecutionException::class.java) { owner.call {} }
    }

    private fun await(latch: CountDownLatch) {
        assertTrue("Timed out waiting for test coordination", latch.await(5, TimeUnit.SECONDS))
    }

    private fun awaitWaiting(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state != Thread.State.WAITING && System.nanoTime() < deadline) {
            assertTrue("Caller exited before waiting for its accepted task", thread.isAlive)
            Thread.yield()
        }
        assertEquals("Caller should wait outside the state monitor", Thread.State.WAITING, thread.state)
    }

    private fun <T> background(name: String, block: () -> T): BackgroundCall<T> {
        val task = FutureTask(Callable { block() })
        val thread = Thread(task, name).apply {
            isDaemon = true
            start()
        }
        return BackgroundCall(thread, task)
    }

    private data class BackgroundCall<T>(val thread: Thread, val task: FutureTask<T>) {
        fun result(): T = task.get(5, TimeUnit.SECONDS)
    }
}

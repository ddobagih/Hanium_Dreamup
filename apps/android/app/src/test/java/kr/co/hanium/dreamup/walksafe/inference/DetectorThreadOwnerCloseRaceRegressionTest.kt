package kr.co.hanium.dreamup.walksafe.inference

import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorThreadOwnerCloseRaceRegressionTest {
    @Test
    fun acceptedWorkMayCallReentrantlyAfterCloseStartsButReleaseCannot() {
        val owner = DetectorThreadOwner()
        val running = CountDownLatch(1)
        val continueWork = CountDownLatch(1)
        val events = ConcurrentLinkedQueue<String>()
        try {
            val caller = background("accepted-caller") {
                owner.call {
                    running.countDown()
                    await(continueWork)
                    assertEquals(42, owner.call {
                        events.add("nested")
                        42
                    })
                    events.add("work")
                }
            }
            await(running)
            val closing = background("external-closer") {
                owner.close {
                    assertThrows(RejectedExecutionException::class.java) { owner.call {} }
                    events.add("release")
                }
            }
            awaitWaiting(closing.thread)
            assertThrows(RejectedExecutionException::class.java) { owner.call {} }

            continueWork.countDown()
            caller.result()
            closing.result()
            assertEquals(listOf("nested", "work", "release"), events.toList())
        } finally {
            continueWork.countDown()
            owner.close {}
        }
    }

    private fun await(latch: CountDownLatch) {
        assertTrue("Timed out waiting for test coordination", latch.await(5, TimeUnit.SECONDS))
    }

    private fun awaitWaiting(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state != Thread.State.WAITING && System.nanoTime() < deadline) {
            assertTrue("Closer exited before waiting for release", thread.isAlive)
            Thread.yield()
        }
        assertEquals("Closer should wait for queued release", Thread.State.WAITING, thread.state)
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

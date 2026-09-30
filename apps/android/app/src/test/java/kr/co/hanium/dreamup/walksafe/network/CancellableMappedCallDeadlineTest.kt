package kr.co.hanium.dreamup.walksafe.network

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellableMappedCallDeadlineTest {
    @Test(timeout = 5_000)
    fun deadlineDuringParsingRejectsTheLateResultAsTimeout() {
        val parsing = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val scheduler = ManualScheduler()
        val executor = Executors.newSingleThreadExecutor()
        val mapped = CancellableNetworkCall.blocking { "response" }.map {
            parsing.countDown()
            resume.await(2, TimeUnit.SECONDS)
            "parsed"
        }
        val call = mapped.withDeadline(10_000L, scheduler)
        try {
            val future = executor.submit<String> { call.execute() }
            assertTrue(parsing.await(1, TimeUnit.SECONDS))
            scheduler.fire()
            resume.countDown()
            val failure = assertThrows(ExecutionException::class.java) {
                future.get(2, TimeUnit.SECONDS)
            }.cause
            assertTrue(failure is NetworkDeadlineExceededException)
            assertEquals(10_000L, (failure as NetworkDeadlineExceededException).deadlineMs)
            assertTrue(mapped.isCancelled())
            assertEquals(1, scheduler.released.get())
        } finally {
            call.cancel()
            resume.countDown()
            executor.shutdownNow()
        }
    }

    @Test(timeout = 5_000)
    fun explicitCancellationBeforeDeadlineRemainsCancellationDuringParsing() {
        val parsing = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val scheduler = ManualScheduler()
        val executor = Executors.newSingleThreadExecutor()
        val call = CancellableNetworkCall.blocking { "response" }.map {
            parsing.countDown()
            resume.await(2, TimeUnit.SECONDS)
            "parsed"
        }.withDeadline(10_000L, scheduler)
        try {
            val future = executor.submit<String> { call.execute() }
            assertTrue(parsing.await(1, TimeUnit.SECONDS))
            call.cancel()
            scheduler.fire()
            resume.countDown()
            assertTrue(
                assertThrows(ExecutionException::class.java) { future.get(2, TimeUnit.SECONDS) }
                    .cause is CancellationException,
            )
            assertEquals(1, scheduler.released.get())
        } finally {
            call.cancel()
            resume.countDown()
            executor.shutdownNow()
        }
    }

    private class ManualScheduler : NetworkDeadlineScheduler {
        private var action: (() -> Unit)? = null
        val released = AtomicInteger(0)

        override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
            assertEquals(10_000L, delayMs)
            this.action = action
            return { released.incrementAndGet() }
        }

        fun fire() {
            action?.invoke()
        }
    }
}

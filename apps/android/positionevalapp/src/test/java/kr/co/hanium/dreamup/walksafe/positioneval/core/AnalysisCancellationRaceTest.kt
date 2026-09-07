package kr.co.hanium.dreamup.walksafe.positioneval.core

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisCancellationRaceTest {
    @Test
    fun cancelledOldCompletionCannotOverwriteImmediatelyQueuedNewRun() {
        val executor = Executors.newSingleThreadExecutor()
        val run1 = AnalysisRunGeneration.next()
        val run2 = AnalysisRunGeneration.next()
        val cancel1 = AnalysisCancellation()
        val activeGeneration = AtomicLong(run1)
        val run1Started = CountDownLatch(1)
        val allowRun1Cleanup = CountDownLatch(1)
        val run2Finished = CountDownLatch(1)
        val cleanupFinished = AtomicBoolean(false)
        val committed = Collections.synchronizedList(mutableListOf<Long>())

        try {
            val old = executor.submit {
                run1Started.countDown()
                while (!cancel1.isCancelled()) Thread.yield()
                allowRun1Cleanup.await(2, TimeUnit.SECONDS)
                cleanupFinished.set(true)
                if (acceptsAnalysisCallback(activeGeneration.get(), run1, cancel1.isCancelled())) committed += run1
            }
            assertTrue(run1Started.await(2, TimeUnit.SECONDS))

            cancel1.cancel()
            activeGeneration.set(run2)
            val fresh = executor.submit {
                assertTrue(cleanupFinished.get())
                if (acceptsAnalysisCallback(activeGeneration.get(), run2, false)) committed += run2
                run2Finished.countDown()
            }
            allowRun1Cleanup.countDown()

            assertTrue(run2Finished.await(2, TimeUnit.SECONDS))
            old.get(2, TimeUnit.SECONDS)
            fresh.get(2, TimeUnit.SECONDS)
            assertFalse(committed.contains(run1))
            assertEquals(listOf(run2), committed)
        } finally {
            executor.shutdownNow()
        }
    }
}

package kr.co.hanium.dreamup.walksafe.voice

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoskStreamingStopOrderingTest {
    @Test
    fun stopRequestReturnsWhileReleaseIsBlockedAndNextStartWaitsForRelease() {
        val worker = Executors.newSingleThreadExecutor()
        val releaseEntered = CountDownLatch(1)
        val allowRelease = CountDownLatch(1)
        val requestReturned = CountDownLatch(1)
        val nextStarted = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        try {
            val caller = Thread {
                requestVoskResourceRelease(worker) {
                    releaseEntered.countDown()
                    allowRelease.await()
                    events += "microphone_released"
                }
                requestReturned.countDown()
            }
            caller.start()
            assertTrue("stop request must not wait for native release", requestReturned.await(1, TimeUnit.SECONDS))
            assertTrue(releaseEntered.await(1, TimeUnit.SECONDS))

            submitReservedVoskStreamingRun(
                runId = 2L,
                onRunReserved = {},
                executor = worker,
                task = Runnable {
                    events += "next_recording"
                    nextStarted.countDown()
                },
            )
            assertFalse("new recording cannot precede microphone release", nextStarted.await(100, TimeUnit.MILLISECONDS))
            allowRelease.countDown()
            assertTrue(nextStarted.await(1, TimeUnit.SECONDS))
            assertEquals(listOf("microphone_released", "next_recording"), events)
        } finally {
            allowRelease.countDown()
            worker.shutdown()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        }
    }
}

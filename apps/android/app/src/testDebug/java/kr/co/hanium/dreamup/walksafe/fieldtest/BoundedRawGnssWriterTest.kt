package kr.co.hanium.dreamup.walksafe.fieldtest

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
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

class BoundedRawGnssWriterTest {
    @Test fun slowRawOpenKeepsPreviewOutOfTraceUntilStepReset() {
        assertDelayedRawReadiness(blockHeaderFlush = false)
    }

    @Test fun slowRawHeaderFlushKeepsPreviewOutOfTraceUntilStepReset() {
        assertDelayedRawReadiness(blockHeaderFlush = true)
    }

    @Test fun stoppedOrSupersededRawStartCannotEnableTraceOrResetNewSessionSteps() {
        val readiness = OutdoorPositionTestActivity.RecordingReadiness()
        val old = readiness.begin()
        readiness.stop()
        var resets = 0
        assertFalse(readiness.finish(old) { resets++ })
        val current = readiness.begin()
        assertFalse(readiness.finish(old) { resets++ })
        assertEquals(0, resets)
        assertTrue(readiness.finish(current) { assertFalse(readiness.isReady); resets++ })
        assertTrue(readiness.isReady)
        assertFalse(readiness.finish(current) { resets++ })
        assertEquals(1, resets)
        readiness.stop()
        assertFalse(readiness.isReady)
    }

    @Test fun slowOpenDoesNotBlockStatusOrStopAndCannotStartAfterStop() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val opened = CountDownLatch(1)
        val success = AtomicBoolean(true)
        val writer = BoundedRawGnssWriter({ entered.countDown(); await(release); ByteArrayOutputStream() },
            byteArrayOf(), 4096, onClosed = { closed.countDown() })
        writer.start { ok, _ -> success.set(ok); opened.countDown() }
        try {
            await(entered)
            responsive {
                assertEquals(BoundedRawGnssWriter.State.STARTING, writer.snapshot.state)
                writer.stop()
                assertEquals(BoundedRawGnssWriter.State.STOPPING, writer.snapshot.state)
                assertFalse(writer.snapshot.closed)
            }
        } finally { release.countDown() }
        await(opened); await(closed)
        assertFalse(success.get())
    }

    @Test fun slowWriteKeepsInFlightBudgetAndPreservesOrderWithoutBlockingControl() {
        val sink = SlowSink(block = "write")
        val closed = CountDownLatch(1)
        val writer = newWriter(sink, closed)
        val token = start(writer)
        try {
            assertTrue(writer.append(token, batch("first\n")))
            await(sink.entered)
            responsive {
                assertEquals(1, writer.snapshot.pendingBatches)
                assertEquals(6, writer.snapshot.pendingBytes)
                assertTrue(writer.append(token, batch("second\n")))
                writer.stop()
                assertFalse(writer.snapshot.closed)
                assertFalse(writer.append(token, batch("late\n")))
            }
        } finally { sink.release.countDown() }
        await(closed)
        assertTrue(sink.text().startsWith("header\nfirst\nsecond\n# RawSessionEnd,COMPLETE"))
        assertEquals(2L, writer.snapshot.measurements)
        assertEquals(0, writer.snapshot.pendingBytes)
        assertTrue(writer.snapshot.closed)
    }

    @Test fun queueCountOverflowStopsWithVisibleOmissionAndNeverPublishesCompleteFile() {
        val sink = SlowSink(block = "write")
        val closed = CountDownLatch(1)
        val published = AtomicBoolean(false)
        val writer = newWriter(sink, closed, count = 1, publish = { published.set(true) })
        val token = start(writer)
        try {
            assertTrue(writer.append(token, batch("first\n")))
            await(sink.entered)
            assertFalse(writer.append(token, batch("lost\n", 5)))
            assertEquals(5L, writer.snapshot.droppedMeasurements)
            assertTrue(writer.snapshot.error!!.contains("queue overflow"))
            assertFalse(writer.append(token, batch("after-overflow\n")))
        } finally { sink.release.countDown() }
        await(closed)
        assertEquals(BoundedRawGnssWriter.State.ERROR, writer.snapshot.state)
        assertTrue(sink.text().contains("INCOMPLETE,omitted=5,PPK_UNVERIFIED"))
        assertFalse(published.get())
    }

    @Test fun byteBudgetIncludesBlockedFlushAndQueueOwnsAcceptedPayload() {
        val sink = SlowSink(block = "flush")
        val closed = CountDownLatch(1)
        val writer = newWriter(sink, closed, bytes = 12)
        val token = start(writer)
        try {
            assertTrue(writer.append(token, batch("first\n")))
            await(sink.entered)
            val mutable = "second".toByteArray()
            assertTrue(writer.append(token, BoundedRawGnssWriter.Batch(mutable, 1)))
            mutable.fill('x'.code.toByte())
            responsive {
                assertEquals(12, writer.snapshot.pendingBytes)
                assertFalse(writer.append(token, batch("!")))
                assertFalse(writer.snapshot.closed)
            }
        } finally { sink.release.countDown() }
        await(closed)
        assertTrue(sink.text().contains("first\nsecond# RawSessionEnd,INCOMPLETE"))
        assertFalse(sink.text().contains("xxxxxx"))
    }

    @Test fun closeAndPublicationMustFinishBeforeStoppedOrExportReadiness() {
        val sink = SlowSink(block = "close")
        val closed = CountDownLatch(1)
        val publicationEntered = CountDownLatch(1)
        val publicationRelease = CountDownLatch(1)
        val writer = newWriter(sink, closed, publish = {
            assertTrue(sink.closed)
            publicationEntered.countDown()
            await(publicationRelease)
        })
        start(writer)
        try {
            writer.stop()
            await(sink.entered)
            responsive {
                writer.stop()
                assertEquals(BoundedRawGnssWriter.State.STOPPING, writer.snapshot.state)
                assertFalse(writer.snapshot.closed)
                assertEquals(1L, closed.count)
            }
            sink.release.countDown()
            await(publicationEntered)
            assertFalse(writer.snapshot.closed)
            assertEquals(BoundedRawGnssWriter.State.STOPPING, writer.snapshot.state)
        } finally { sink.release.countDown(); publicationRelease.countDown() }
        await(closed)
        assertEquals(BoundedRawGnssWriter.State.STOPPED, writer.snapshot.state)
    }

    @Test fun consentWithdrawalRejectsNewAndStaleCallbacksWhileDrainingOnlyAdmittedRows() {
        val sink = SlowSink(block = "write")
        val closed = CountDownLatch(1)
        val writer = newWriter(sink, closed)
        val token = start(writer)
        try {
            assertFalse(writer.append(token - 1, batch("stale-before-stop\n")))
            assertTrue(writer.append(token, batch("consented\n")))
            await(sink.entered)
            writer.stop() // Activity's unchecked consent handler closes admission synchronously.
            assertFalse(writer.accepts(token))
            assertFalse(writer.append(token, batch("after-withdrawal\n")))
            assertFalse(writer.append(writer.snapshot.generation, batch("new-token-after-stop\n")))
        } finally { sink.release.countDown() }
        await(closed)
        assertEquals(1L, writer.snapshot.measurements)
        assertFalse(sink.text().contains("after-withdrawal"))
        val restart = AtomicBoolean(true)
        writer.start { ok, _ -> restart.set(ok) }
        assertFalse(restart.get())
    }

    @Test fun fileLimitReservesFooterAndRetainsIncompletePrivateFile() {
        val sink = ByteArrayOutputStream()
        val closed = CountDownLatch(1)
        val published = AtomicBoolean(false)
        val writer = BoundedRawGnssWriter({ sink }, byteArrayOf(), 300,
            publishClosedSink = { published.set(true) }, onClosed = { closed.countDown() })
        val token = start(writer)
        assertFalse(writer.append(token, BoundedRawGnssWriter.Batch(ByteArray(45), 7)))
        await(closed)
        assertEquals(7L, writer.snapshot.droppedMeasurements)
        assertTrue(writer.snapshot.error!!.contains("byte limit"))
        assertTrue(sink.size() <= 300)
        assertTrue(sink.toString("UTF-8").contains("INCOMPLETE,omitted=7"))
        assertFalse(published.get())
    }

    @Test fun writeFlushCloseAndPublicationFailuresNeverReportSuccessfulCompletion() {
        for (operation in listOf("open", "write", "flush", "close", "publish")) {
            val closed = CountDownLatch(1)
            val published = AtomicBoolean(false)
            val sink = object : OutputStream() {
                var writes = 0
                override fun write(value: Int) = Unit
                override fun write(bytes: ByteArray) {
                    writes++
                    if (operation == "write" && writes > 1) throw IOException("write")
                }
                override fun flush() { if (operation == "flush" && writes > 1) throw IOException("flush") }
                override fun close() { if (operation == "close") throw IOException("close") }
            }
            val writer = BoundedRawGnssWriter({ if (operation == "open") throw IOException("open") else sink },
                "header\n".toByteArray(), 4096, publishClosedSink = {
                    if (operation == "publish") throw IOException("publish")
                    published.set(true)
                }, onClosed = { closed.countDown() })
            val opened = CountDownLatch(1)
            val token = AtomicLong()
            writer.start { _, value -> token.set(value); opened.countDown() }
            await(opened)
            writer.append(token.get(), batch("row\n"))
            writer.stop()
            await(closed)
            assertEquals(operation, BoundedRawGnssWriter.State.ERROR, writer.snapshot.state)
            assertTrue(writer.snapshot.closed)
            assertFalse(published.get())
            assertTrue(writer.snapshot.error != null)
        }
    }

    @Test fun everySinkOperationRunsOnOneWorkerNotOnTheCaller() {
        val caller = Thread.currentThread().id
        val threads = Collections.synchronizedSet(mutableSetOf<Long>())
        val closed = CountDownLatch(1)
        val sink = object : OutputStream() {
            override fun write(value: Int) { threads.add(Thread.currentThread().id) }
            override fun flush() { threads.add(Thread.currentThread().id) }
            override fun close() { threads.add(Thread.currentThread().id) }
        }
        val writer = BoundedRawGnssWriter({ threads.add(Thread.currentThread().id); sink },
            "header\n".toByteArray(), 4096,
            publishClosedSink = { threads.add(Thread.currentThread().id) }, onClosed = { closed.countDown() })
        val token = start(writer)
        writer.append(token, batch("row\n"))
        writer.stop()
        await(closed)
        assertEquals(1, threads.size)
        assertFalse(threads.contains(caller))
    }

    private fun assertDelayedRawReadiness(blockHeaderFlush: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val openedSuccessfully = AtomicBoolean(false)
        val readiness = OutdoorPositionTestActivity.RecordingReadiness()
        val startToken = readiness.begin()
        var steps = 37 // Preview has accumulated steps while the session lease is already owned.
        val observations = mutableListOf<String>()
        fun observe(source: String) {
            if (readiness.isReady) observations += "$source:$steps"
        }
        fun blockPreparation() { entered.countDown(); await(release) }
        val sink = object : ByteArrayOutputStream() {
            private var firstFlush = true
            override fun flush() {
                if (blockHeaderFlush && firstFlush) {
                    firstFlush = false
                    blockPreparation()
                }
            }
        }
        val writer = BoundedRawGnssWriter({
            if (!blockHeaderFlush) blockPreparation()
            sink
        }, "header\n".toByteArray(), 4096, onClosed = { closed.countDown() })
        writer.start { success, _ -> openedSuccessfully.set(success); opened.countDown() }
        try {
            await(entered)
            assertEquals(BoundedRawGnssWriter.State.STARTING, writer.snapshot.state)
            listOf("gnss", "step", "heartbeat", "checkpoint").forEach(::observe)
            assertFalse(readiness.isReady)
            assertTrue(observations.isEmpty())
            assertEquals(1L, opened.count)
            release.countDown()
            await(opened)
            assertTrue(openedSuccessfully.get())
            // The Activity handles RAW completion on main; opening alone must not enable trace.
            observe("before-main-start-callback")
            assertTrue(observations.isEmpty())
            assertTrue(readiness.finish(startToken) {
                observe("synchronous-step-reset-callback")
                assertFalse(readiness.isReady)
                steps = 0
            })
            listOf("gnss", "step", "heartbeat", "checkpoint").forEach(::observe)
            assertEquals(listOf("gnss:0", "step:0", "heartbeat:0", "checkpoint:0"), observations)
        } finally {
            release.countDown()
            readiness.stop()
            writer.stop()
            await(closed)
        }
    }

    private fun newWriter(sink: OutputStream, closed: CountDownLatch, count: Int = 16, bytes: Int = 1024,
        publish: () -> Unit = {}) = BoundedRawGnssWriter({ sink }, "header\n".toByteArray(), 4096,
        maxPendingBatches = count, maxPendingBytes = bytes, publishClosedSink = publish,
        onClosed = { closed.countDown() })

    private fun start(writer: BoundedRawGnssWriter): Long {
        val ready = CountDownLatch(1)
        val success = AtomicBoolean(false)
        val token = AtomicLong()
        writer.start { ok, value -> success.set(ok); token.set(value); ready.countDown() }
        await(ready)
        assertTrue(success.get())
        return token.get()
    }

    private fun batch(text: String, count: Long = 1) = BoundedRawGnssWriter.Batch(text.toByteArray(), count)

    private fun responsive(action: () -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        try { executor.submit(action).get(1, TimeUnit.SECONDS) }
        finally { executor.shutdownNow() }
    }

    private class SlowSink(private val block: String) : OutputStream() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val buffer = ByteArrayOutputStream()
        private var writes = 0
        @Volatile var closed = false
        override fun write(value: Int) { buffer.write(value) }
        override fun write(bytes: ByteArray) {
            writes++
            if (block == "write" && writes == 2) pause()
            buffer.write(bytes)
        }
        override fun flush() { if (block == "flush" && writes == 2) pause() }
        override fun close() { if (block == "close") pause(); closed = true }
        fun text(): String = buffer.toString("UTF-8")
        private fun pause() { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
    }

    private companion object {
        fun await(latch: CountDownLatch) { assertTrue("worker did not reach expected boundary", latch.await(5, TimeUnit.SECONDS)) }
    }
}

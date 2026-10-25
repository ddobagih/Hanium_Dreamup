package kr.co.hanium.dreamup.walksafe.diagnostics

import java.io.Closeable
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BoundedRuntimeDiagnosticLogTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun publicRecordingApiDoesNotAcceptArbitraryTextOrExceptions() {
        val methods = BoundedRuntimeDiagnosticLog::class.java.declaredMethods.filter {
            it.name.startsWith("record")
        }
        assertTrue(methods.isNotEmpty())
        methods.forEach { method ->
            assertFalse(method.parameterTypes.any {
                it == String::class.java || Throwable::class.java.isAssignableFrom(it)
            })
        }
    }

    @Test
    fun rotationRetainsOnlyTwoBoundedFiles() {
        val directory = temporary.newFolder("rotation")
        WriterHarness(directory).use { writer ->
            repeat(400) { index ->
                // Private writer byte-bound fixture, not a production message API.
                writer.enqueue("event=ROTATION_FIXTURE index=$index padding=" + "A".repeat(850))
                if (index % 16 == 15) writer.awaitIdle()
            }
            writer.awaitIdle()
            assertEquals(0L, writer.dropped)
            assertEquals(
                setOf("events.log", "events.previous.log"),
                directory.listFiles().orEmpty().map { it.name }.toSet(),
            )
            directory.listFiles().orEmpty().forEach {
                assertTrue(it.length() in 1L..(128L * 1024L))
            }
            assertTrue(File(directory, "events.log").readText().contains("index=399 "))
            assertFalse(File(directory, "events.previous.log").readText().contains("index=0 "))
        }
    }

    @Test
    fun aFullQueueDropsNewMetadataWithoutWaitingForTheWorker() {
        WriterHarness(temporary.newFolder("queue")).use { writer ->
            val workerEntered = CountDownLatch(1)
            val releaseWorker = CountDownLatch(1)
            writer.executor.execute {
                workerEntered.countDown()
                releaseWorker.await(5L, TimeUnit.SECONDS)
            }
            assertTrue(workerEntered.await(2L, TimeUnit.SECONDS))
            try {
                repeat(65) { writer.enqueue("event=QUEUE_FIXTURE") }
                assertEquals(64, writer.executor.queue.size)
                assertEquals(1L, writer.dropped)
            } finally {
                releaseWorker.countDown()
            }
            writer.executor.shutdown()
            assertTrue(writer.executor.awaitTermination(5L, TimeUnit.SECONDS))
            assertTrue(writer.current.readText().contains("dropped_total=1 "))
        }
    }

    @Test
    fun anIoFailureDoesNotEscapeAndTheWorkerCanWriteAfterRecovery() {
        val unavailableDirectory = temporary.newFile("not-a-directory")
        WriterHarness(unavailableDirectory).use { writer ->
            writer.enqueue("event=IO_FIXTURE")
            writer.awaitIdle()
            assertEquals(1L, writer.dropped)
            assertTrue(unavailableDirectory.delete())
            assertTrue(unavailableDirectory.mkdir())
            writer.enqueue("event=IO_RECOVERED")
            writer.awaitIdle()
            assertTrue(writer.current.readText().contains("event=IO_RECOVERED"))
        }
    }

    @Test
    fun anOversizedEntryAndAStoppedWorkerFailClosedWithoutThrowing() {
        WriterHarness(temporary.newFolder("rejected")).use { writer ->
            writer.enqueue("X".repeat(1_025))
            writer.awaitIdle()
            assertEquals(1L, writer.dropped)
            assertFalse(writer.current.exists())
            writer.executor.shutdown()
            writer.enqueue("event=STOPPED_FIXTURE")
            assertEquals(2L, writer.dropped)
        }
    }

    @Test
    fun everyRecordCarriesAMemoryOnlyRunIdAndTheRunStartsWithAStartRecord() {
        // S1617-38 (originals/16-17.txt:85): separate runs whose elapsed_ms restarts.
        val directory = temporary.newFolder("runs")
        val firstRunId = WriterHarness(directory).use { writer ->
            writer.enqueueRunStart()
            writer.enqueue("event=RUN_FIXTURE index=1")
            writer.awaitIdle()
            writer.runId
        }
        val secondRunId = WriterHarness(directory).use { writer ->
            writer.enqueueRunStart()
            writer.enqueue("event=RUN_FIXTURE index=2")
            writer.awaitIdle()
            writer.runId
        }
        assertTrue(firstRunId.matches(Regex("[0-9a-f]{16}")))
        assertTrue(secondRunId.matches(Regex("[0-9a-f]{16}")))
        assertFalse(firstRunId == secondRunId)
        val lines = File(directory, "events.log").readLines()
        assertEquals(4, lines.size)
        lines.forEach { assertTrue(it, it.matches(Regex("run_id=[0-9a-f]{16} elapsed_ms=\\d+ .*"))) }
        assertEquals(
            listOf(
                "run_id=$firstRunId elapsed_ms=123 dropped_total=0 " +
                    "event=RUN_START clock=ELAPSED_REALTIME_SINCE_BOOT",
                "run_id=$firstRunId elapsed_ms=123 dropped_total=0 event=RUN_FIXTURE index=1",
                "run_id=$secondRunId elapsed_ms=123 dropped_total=0 " +
                    "event=RUN_START clock=ELAPSED_REALTIME_SINCE_BOOT",
                "run_id=$secondRunId elapsed_ms=123 dropped_total=0 event=RUN_FIXTURE index=2",
            ),
            lines,
        )
        // The run ID is never persisted outside the two bounded log files.
        assertEquals(setOf("events.log"), directory.listFiles().orEmpty().map { it.name }.toSet())
    }

    @Test
    fun initializeWritesTheStartRecordRightAfterCreatingTheWriter() {
        val source = listOf(
            "src/main/java/kr/co/hanium/dreamup/walksafe/diagnostics/BoundedRuntimeDiagnosticLog.kt",
            "app/src/main/java/kr/co/hanium/dreamup/walksafe/diagnostics/BoundedRuntimeDiagnosticLog.kt",
        ).map(::File).first { it.isFile }.readText()
        val initialize = source.substringAfter("fun initialize(context: Context)")
            .substringBefore("fun recordState(")
        assertTrue(initialize.contains("if (!BuildConfig.DEBUG || writer != null) return"))
        val start = initialize.indexOf("created.enqueueRunStart(")
        assertTrue(start >= 0 && start < initialize.indexOf("writer = created"))
    }

    @Test fun purgeDropsQueuedRecordsAndLateAppendCannotRecreateDirectory() {
        val directory = temporary.newFolder("purge-queued")
        WriterHarness(directory).use { writer ->
            writer.enqueue("event=BEFORE_PURGE"); writer.awaitIdle()
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            writer.executor.execute { entered.countDown(); release.await(3L, TimeUnit.SECONDS) }
            assertTrue(entered.await(2L, TimeUnit.SECONDS))
            repeat(10) { writer.enqueue("event=QUEUED") }
            try {
                assertTrue(writer.purge())
                assertFalse(directory.exists())
                writer.enqueue("event=LATE")
            } finally { release.countDown() }
            assertTrue(writer.executor.awaitTermination(2L, TimeUnit.SECONDS))
            assertFalse(directory.exists())
        }
    }

    @Test fun purgeSerializesWithTheRunningWriteLockAndRejectsRemainingCopies() {
        val directory = temporary.newFolder("purge-running")
        WriterHarness(directory).use { writer ->
            writer.enqueue("event=FIRST"); writer.awaitIdle()
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val running = Thread { synchronized(writer.writeLock) {
                entered.countDown(); release.await(3L, TimeUnit.SECONDS)
                writer.current.appendText("event=IN_FLIGHT\n")
            } }
            running.start(); assertTrue(entered.await(2L, TimeUnit.SECONDS))
            val completed = CountDownLatch(1)
            var purged = false
            val cleanup = Thread { purged = writer.purge(); completed.countDown() }
            cleanup.start()
            assertFalse(completed.await(50L, TimeUnit.MILLISECONDS))
            release.countDown(); running.join(2_000L); cleanup.join(2_000L)
            assertTrue(purged); assertFalse(directory.exists())
        }
        val copies = temporary.newFolder("purge-copy")
        File(copies, "unexpected-copy").writeText("controlled fixture")
        WriterHarness(copies).use { writer ->
            assertFalse(writer.purge())
            writer.enqueue("event=LATE_FAILED_PURGE")
            assertTrue(File(copies, "unexpected-copy").exists())
            assertFalse(writer.current.exists())
        }
    }

    private class WriterHarness(directory: File) : Closeable {
        private val type = Class.forName(
            "kr.co.hanium.dreamup.walksafe.diagnostics.RuntimeDiagnosticFileWriter",
        )
        private val instance = type.getDeclaredConstructor(File::class.java).apply {
            isAccessible = true
        }.newInstance(directory)
        private val enqueueMethod = type.getDeclaredMethod(
            "enqueue", Long::class.javaPrimitiveType, String::class.java,
        ).apply { isAccessible = true }
        val executor = type.getDeclaredField("executor").apply { isAccessible = true }
            .get(instance) as ThreadPoolExecutor
        private val droppedCounter = type.getDeclaredField("dropped").apply { isAccessible = true }
            .get(instance) as AtomicLong
        private val runStartMethod = type.getDeclaredMethod(
            "enqueueRunStart", Long::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val runId: String = type.getDeclaredField("runId").apply { isAccessible = true }
            .get(instance) as String
        val writeLock = type.getDeclaredField("writeLock").apply { isAccessible = true }.get(instance)!!
        private val purgeMethod = type.getDeclaredMethod("blockAndPurge").apply { isAccessible = true }
        fun purge(): Boolean = purgeMethod.invoke(instance) as Boolean
        val current = File(directory, "events.log")
        val dropped: Long get() = droppedCounter.get()

        fun enqueue(metadata: String) {
            enqueueMethod.invoke(instance, 123L, metadata)
        }

        fun enqueueRunStart() {
            runStartMethod.invoke(instance, 123L)
        }

        fun awaitIdle() {
            executor.submit {}.get(5L, TimeUnit.SECONDS)
        }

        override fun close() {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5L, TimeUnit.SECONDS))
        }
    }
}

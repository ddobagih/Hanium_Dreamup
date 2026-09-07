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
        val current = File(directory, "events.log")
        val dropped: Long get() = droppedCounter.get()

        fun enqueue(metadata: String) {
            enqueueMethod.invoke(instance, 123L, metadata)
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

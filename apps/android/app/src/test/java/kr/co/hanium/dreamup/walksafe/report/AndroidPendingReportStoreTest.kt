package kr.co.hanium.dreamup.walksafe.report

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPendingReportStoreTest {
    @Test
    fun purgePersistsMarkerBeforeKeyDestructionThenDeletesAllBytesAndMarker() =
        withStoreRoot { root ->
            val queueDirectory = File(root, "pending_reports_v1").apply { mkdirs() }
            File(queueDirectory, "queue.enc").writeBytes(byteArrayOf(0x00, 0x7f))
            File(queueDirectory, "queue.enc.new").writeBytes(byteArrayOf(0x01))
            File(queueDirectory, "queue.enc.bak").writeBytes(byteArrayOf(0x02))
            File(queueDirectory, "unknown-corrupt.bin").writeBytes(byteArrayOf(0x03))
            File(queueDirectory, "nested").apply { mkdirs() }
                .resolve("orphaned-fragment")
                .writeBytes(byteArrayOf(0x04))
            val lockFile = File(root, "pending_reports_v1.lock")
            val markerFile = markerFile(root)
            var markerExistedWhenKeyWasDestroyed = false
            var ciphertextExistedWhenKeyWasDestroyed = false

            val purged =
                AndroidPendingReportStore.purgeAllWithoutLoading(
                    queueDirectory = queueDirectory,
                    lockFile = lockFile,
                    destroyKey = {
                        markerExistedWhenKeyWasDestroyed = markerFile.isFile
                        ciphertextExistedWhenKeyWasDestroyed =
                            File(queueDirectory, "queue.enc").exists()
                    },
                )

            assertTrue(purged)
            assertTrue(markerExistedWhenKeyWasDestroyed)
            assertTrue(ciphertextExistedWhenKeyWasDestroyed)
            assertFalse(queueDirectory.exists())
            assertFalse(markerFile.exists())
            assertTrue(lockFile.isFile)
        }

    @Test
    fun successfulPurgeCanBeRetriedIdempotently() = withStoreRoot { root ->
        val queueDirectory = File(root, "pending_reports_v1")
        val lockFile = File(root, "pending_reports_v1.lock")
        val markerFile = markerFile(root)
        val destroyCalls = AtomicInteger()

        assertTrue(
            AndroidPendingReportStore.purgeAllWithoutLoading(
                queueDirectory = queueDirectory,
                lockFile = lockFile,
                destroyKey = destroyCalls::incrementAndGet,
            ),
        )
        assertFalse(markerFile.exists())
        assertTrue(
            AndroidPendingReportStore.purgeAllWithoutLoading(
                queueDirectory = queueDirectory,
                lockFile = lockFile,
                destroyKey = destroyCalls::incrementAndGet,
            ),
        )

        assertEquals(2, destroyCalls.get())
        assertFalse(queueDirectory.exists())
        assertFalse(markerFile.exists())
    }

    @Test
    fun keyFailureStillDeletesQueueAndRetainsExistingMarkerUntilRetry() =
        withStoreRoot { root ->
            val queueDirectory = File(root, "pending_reports_v1").apply { mkdirs() }
            File(queueDirectory, "queue.enc").writeBytes(byteArrayOf(0x11, 0x22))
            val lockFile = File(root, "pending_reports_v1.lock")
            val markerFile = markerFile(root).apply {
                writeText("existing-purge-request")
            }
            val originalMarker = markerFile.readBytes()
            val destroyCalls = AtomicInteger()

            val failed =
                AndroidPendingReportStore.purgeAllWithoutLoading(
                    queueDirectory = queueDirectory,
                    lockFile = lockFile,
                    destroyKey = {
                        destroyCalls.incrementAndGet()
                        throw IllegalStateException("keystore unavailable")
                    },
                )

            assertFalse(failed)
            assertFalse(queueDirectory.exists())
            assertTrue(markerFile.isFile)
            assertTrue(originalMarker.contentEquals(markerFile.readBytes()))

            assertTrue(
                AndroidPendingReportStore.purgeAllWithoutLoading(
                    queueDirectory = queueDirectory,
                    lockFile = lockFile,
                    destroyKey = destroyCalls::incrementAndGet,
                ),
            )
            assertEquals(2, destroyCalls.get())
            assertFalse(markerFile.exists())
        }

    @Test
    fun queueDeleteFailureStillDestroysKeyAndRetainsMarkerUntilRetry() =
        withStoreRoot { root ->
            val queueDirectory = File(root, "pending_reports_v1").apply { mkdirs() }
            File(queueDirectory, "queue.enc").writeBytes(byteArrayOf(0x31))
            val lockFile = File(root, "pending_reports_v1.lock")
            val markerFile = markerFile(root)
            val destroyCalls = AtomicInteger()

            val failed =
                AndroidPendingReportStore.purgeAllWithoutLoading(
                    queueDirectory = queueDirectory,
                    lockFile = lockFile,
                    destroyKey = destroyCalls::incrementAndGet,
                    deleteQueue = { false },
                )

            assertFalse(failed)
            assertEquals(1, destroyCalls.get())
            assertTrue(queueDirectory.exists())
            assertTrue(markerFile.isFile)

            assertTrue(
                AndroidPendingReportStore.purgeAllWithoutLoading(
                    queueDirectory = queueDirectory,
                    lockFile = lockFile,
                    destroyKey = destroyCalls::incrementAndGet,
                ),
            )
            assertEquals(2, destroyCalls.get())
            assertFalse(queueDirectory.exists())
            assertFalse(markerFile.exists())
        }

    @Test
    fun processLockTimeoutReturnsFalseWithMarkerAndLaterRetrySucceeds() =
        withStoreRoot { root ->
            val firstRoot = File(root, "first").apply { mkdirs() }
            val secondRoot = File(root, "second").apply { mkdirs() }
            val firstQueue = File(firstRoot, "pending_reports_v1").apply { mkdirs() }
            val secondQueue = File(secondRoot, "pending_reports_v1").apply { mkdirs() }
            File(firstQueue, "queue.enc").writeBytes(byteArrayOf(0x41))
            File(secondQueue, "queue.enc").writeBytes(byteArrayOf(0x42))
            val firstEntered = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val firstResult = AtomicBoolean()
            val secondDestroyCalls = AtomicInteger()

            val firstThread =
                thread {
                    firstResult.set(
                        AndroidPendingReportStore.purgeAllWithoutLoading(
                            queueDirectory = firstQueue,
                            lockFile = File(firstRoot, "pending_reports_v1.lock"),
                            destroyKey = {
                                firstEntered.countDown()
                                check(releaseFirst.await(5, TimeUnit.SECONDS))
                            },
                        ),
                    )
                }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))

            val startedAtNanos = System.nanoTime()
            val timedOut =
                AndroidPendingReportStore.purgeAllWithoutLoading(
                    queueDirectory = secondQueue,
                    lockFile = File(secondRoot, "pending_reports_v1.lock"),
                    destroyKey = secondDestroyCalls::incrementAndGet,
                    lockTimeoutMillis = 50L,
                    fileLockRetryMillis = 5L,
                )
            val elapsedMillis =
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)

            try {
                assertFalse(timedOut)
                assertTrue(elapsedMillis < 1_000L)
                assertEquals(0, secondDestroyCalls.get())
                assertTrue(secondQueue.exists())
                assertTrue(markerFile(secondRoot).isFile)
            } finally {
                releaseFirst.countDown()
            }
            firstThread.join(5_000L)

            assertFalse(firstThread.isAlive)
            assertTrue(firstResult.get())
            assertTrue(
                AndroidPendingReportStore.purgeAllWithoutLoading(
                    queueDirectory = secondQueue,
                    lockFile = File(secondRoot, "pending_reports_v1.lock"),
                    destroyKey = secondDestroyCalls::incrementAndGet,
                ),
            )
            assertEquals(1, secondDestroyCalls.get())
            assertFalse(secondQueue.exists())
            assertFalse(markerFile(secondRoot).exists())
        }

    @Test
    fun fileLockTimeoutReturnsFalseWithMarkerAndLaterRetrySucceeds() =
        withStoreRoot { root ->
            val queueDirectory = File(root, "pending_reports_v1").apply { mkdirs() }
            File(queueDirectory, "queue.enc").writeBytes(byteArrayOf(0x51))
            val lockFile = File(root, "pending_reports_v1.lock")
            val markerFile = markerFile(root)
            val destroyCalls = AtomicInteger()
            val fakeNanos = AtomicLong()

            RandomAccessFile(lockFile, "rw").use { externalHandle ->
                val externalLock = externalHandle.channel.lock()
                try {
                    val startedAtNanos = System.nanoTime()
                    val timedOut =
                        AndroidPendingReportStore.purgeAllWithoutLoading(
                            queueDirectory = queueDirectory,
                            lockFile = lockFile,
                            destroyKey = destroyCalls::incrementAndGet,
                            lockTimeoutMillis = 50L,
                            fileLockRetryMillis = 5L,
                            nanoTime = fakeNanos::get,
                            sleepNanos = fakeNanos::addAndGet,
                        )
                    val elapsedMillis =
                        TimeUnit.NANOSECONDS.toMillis(
                            System.nanoTime() - startedAtNanos,
                        )

                    assertFalse(timedOut)
                    assertTrue(elapsedMillis < 1_000L)
                    assertEquals(0, destroyCalls.get())
                    assertTrue(queueDirectory.exists())
                    assertTrue(markerFile.isFile)
                    assertTrue(
                        fakeNanos.get() >= TimeUnit.MILLISECONDS.toNanos(50L),
                    )
                } finally {
                    externalLock.release()
                }
            }

            assertTrue(
                AndroidPendingReportStore.purgeAllWithoutLoading(
                    queueDirectory = queueDirectory,
                    lockFile = lockFile,
                    destroyKey = destroyCalls::incrementAndGet,
                ),
            )
            assertEquals(1, destroyCalls.get())
            assertFalse(queueDirectory.exists())
            assertFalse(markerFile.exists())
        }

    private fun markerFile(root: File): File =
        File(
            root,
            AndroidPendingReportStore.PURGE_PENDING_MARKER_FILE_NAME,
        )

    private fun withStoreRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("walksafe-pending-report-cleanup").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}

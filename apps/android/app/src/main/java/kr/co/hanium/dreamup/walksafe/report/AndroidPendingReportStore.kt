package kr.co.hanium.dreamup.walksafe.report

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Deletes legacy pending-report ciphertext without opening or decrypting it.
 *
 * Persistent report retry remains disabled. The lock and purge-pending marker intentionally live
 * outside the queue directory so deleting corrupt queue contents cannot remove either boundary.
 */
internal object AndroidPendingReportStore {
    fun purgeAllWithoutLoading(context: Context): Boolean =
        purgeAllWithoutLoading(
            queueDirectory = File(context.noBackupFilesDir, DIRECTORY_NAME),
            lockFile = File(context.noBackupFilesDir, LOCK_FILE_NAME),
            destroyKey = ::destroyLegacyKey,
        )

    internal fun purgeAllWithoutLoading(
        queueDirectory: File,
        lockFile: File,
        destroyKey: () -> Unit,
        markerFile: File =
            File(lockFile.absoluteFile.parentFile, PURGE_PENDING_MARKER_FILE_NAME),
        lockTimeoutMillis: Long = LOCK_TIMEOUT_MILLIS,
        fileLockRetryMillis: Long = FILE_LOCK_RETRY_MILLIS,
        deleteQueue: (File) -> Boolean = { directory ->
            !directory.exists() || directory.deleteRecursively()
        },
        nanoTime: () -> Long = { System.nanoTime() },
        sleepNanos: (Long) -> Unit = { duration ->
            TimeUnit.NANOSECONDS.sleep(duration)
        },
    ): Boolean {
        if (!ensurePendingMarker(markerFile)) return false

        val startedAtNanos = nanoTime()
        val timeoutNanos =
            TimeUnit.MILLISECONDS.toNanos(lockTimeoutMillis.coerceAtLeast(0L))
        val processLockAcquired =
            try {
                PROCESS_LOCK.tryLock(
                    remainingNanos(startedAtNanos, timeoutNanos, nanoTime),
                    TimeUnit.NANOSECONDS,
                )
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        if (!processLockAcquired) return false

        return try {
            purgeUnderProcessLock(
                queueDirectory = queueDirectory,
                lockFile = lockFile,
                markerFile = markerFile,
                destroyKey = destroyKey,
                deleteQueue = deleteQueue,
                startedAtNanos = startedAtNanos,
                timeoutNanos = timeoutNanos,
                fileLockRetryMillis = fileLockRetryMillis,
                nanoTime = nanoTime,
                sleepNanos = sleepNanos,
            )
        } finally {
            PROCESS_LOCK.unlock()
        }
    }

    private fun purgeUnderProcessLock(
        queueDirectory: File,
        lockFile: File,
        markerFile: File,
        destroyKey: () -> Unit,
        deleteQueue: (File) -> Boolean,
        startedAtNanos: Long,
        timeoutNanos: Long,
        fileLockRetryMillis: Long,
        nanoTime: () -> Long,
        sleepNanos: (Long) -> Unit,
    ): Boolean {
        val lockParent = lockFile.parentFile ?: return false
        if (!ensureDirectory(lockParent)) return false

        return runCatching {
            RandomAccessFile(lockFile, "rw").use { lockHandle ->
                val fileLock =
                    acquireFileLock(
                        channel = lockHandle.channel,
                        startedAtNanos = startedAtNanos,
                        timeoutNanos = timeoutNanos,
                        retryNanos =
                            TimeUnit.MILLISECONDS.toNanos(
                                fileLockRetryMillis.coerceAtLeast(1L),
                            ),
                        nanoTime = nanoTime,
                        sleepNanos = sleepNanos,
                    ) ?: return@use false
                try {
                    // Another cleanup may have removed the marker while this caller waited.
                    if (!ensurePendingMarker(markerFile)) return@use false

                    val keyDestroyed = runCatching { destroyKey() }.isSuccess
                    val queueDeleted =
                        runCatching {
                            deleteQueue(queueDirectory) && !queueDirectory.exists()
                        }.getOrDefault(false)
                    if (!keyDestroyed || !queueDeleted) return@use false

                    clearPendingMarker(markerFile)
                } finally {
                    runCatching { fileLock.release() }
                }
            }
        }.getOrDefault(false)
    }

    private fun acquireFileLock(
        channel: FileChannel,
        startedAtNanos: Long,
        timeoutNanos: Long,
        retryNanos: Long,
        nanoTime: () -> Long,
        sleepNanos: (Long) -> Unit,
    ): FileLock? {
        while (true) {
            val acquired =
                try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                } catch (_: Exception) {
                    return null
                }
            if (acquired != null) return acquired

            val remaining = remainingNanos(startedAtNanos, timeoutNanos, nanoTime)
            if (remaining <= 0L) return null
            try {
                sleepNanos(minOf(remaining, retryNanos))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            } catch (_: Exception) {
                return null
            }
        }
    }

    private fun remainingNanos(
        startedAtNanos: Long,
        timeoutNanos: Long,
        nanoTime: () -> Long,
    ): Long {
        val elapsed = nanoTime() - startedAtNanos
        return (timeoutNanos - elapsed).coerceAtLeast(0L)
    }

    private fun ensurePendingMarker(markerFile: File): Boolean =
        runCatching {
            val parent = markerFile.parentFile ?: return@runCatching false
            if (!ensureDirectory(parent)) return@runCatching false
            if (markerFile.exists()) {
                if (!markerFile.isFile) return@runCatching false
                syncFile(markerFile)
                syncDirectory(parent)
                return@runCatching true
            }

            val temporary =
                File.createTempFile("${markerFile.name}.", ".new", parent)
            try {
                RandomAccessFile(temporary, "rw").use { handle ->
                    handle.setLength(0L)
                    handle.write(PURGE_MARKER_BYTES)
                    handle.fd.sync()
                }
                Files.move(
                    temporary.toPath(),
                    markerFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                syncFile(markerFile)
                syncDirectory(parent)
                true
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }.getOrDefault(false)

    private fun clearPendingMarker(markerFile: File): Boolean =
        runCatching {
            val parent = markerFile.parentFile ?: return@runCatching false
            if (!markerFile.isFile || !markerFile.delete()) return@runCatching false
            syncDirectory(parent)
            true
        }.getOrDefault(false)

    private fun ensureDirectory(directory: File): Boolean =
        if (directory.exists()) directory.isDirectory else directory.mkdirs()

    private fun syncFile(file: File) {
        RandomAccessFile(file, "rw").use { it.fd.sync() }
    }

    private fun syncDirectory(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use {
            it.force(true)
        }
    }

    private fun destroyLegacyKey() {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    internal const val PURGE_PENDING_MARKER_FILE_NAME =
        "pending_reports_v1.purge_pending"
    internal const val LOCK_TIMEOUT_MILLIS = 2_000L
    private const val FILE_LOCK_RETRY_MILLIS = 25L
    private const val DIRECTORY_NAME = "pending_reports_v1"
    private const val LOCK_FILE_NAME = "pending_reports_v1.lock"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "walksafe_pending_reports_v1"
    private val PURGE_MARKER_BYTES =
        "pending-report-purge-v1\n".toByteArray(Charsets.US_ASCII)
    private val PROCESS_LOCK = ReentrantLock()
}

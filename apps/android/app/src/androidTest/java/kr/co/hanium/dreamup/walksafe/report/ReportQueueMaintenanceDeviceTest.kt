package kr.co.hanium.dreamup.walksafe.report

import android.graphics.Bitmap
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AndroidKeyStoreAead
import org.junit.Assert.*
import org.junit.Test

/** Isolated real encrypted files/Keystore. Only the queue's injected clock advances. */
class ReportQueueMaintenanceDeviceTest {
    @Test fun disabledUploadProfilePrunesAtTheExactStoredBoundary() = Fixture().use { f ->
        val first = f.enqueue()
        f.now++
        val second = f.enqueue()
        f.now = 1_000L + REPORT_QUEUE_TTL_MS - 1
        assertEquals(0, f.cleanup().pruneExpired())
        f.now++
        assertEquals(1, f.cleanup().pruneExpired())
        assertFalse(f.file(first).exists())
        assertTrue(f.file(second).exists())
        f.now++
        assertEquals(1, f.cleanup().pruneExpired())
        assertEquals(0, f.cleanup().pruneExpired())
    }

    @Test fun actualDirectoryWriteDenialIsReportedAndCleanupRecoversAfterRestore() = Fixture().use { f ->
        val id = f.enqueue()
        f.now += REPORT_QUEUE_TTL_MS
        try {
            Os.chmod(f.reports.absolutePath, 0b101000000) // 0500: readable, not writable by this UID.
            assertThrows(IOException::class.java) { f.cleanup().pruneExpired() }
            assertTrue("Failure must leave the encrypted report on disk", f.file(id).isFile)
        } finally {
            Os.chmod(f.reports.absolutePath, 0b111000000) // 0700 before retry and fixture teardown.
        }
        assertEquals(1, f.cleanup().pruneExpired())
        assertFalse(f.file(id).exists())
    }

    private class Fixture : Closeable {
        private val app = InstrumentationRegistry.getInstrumentation().targetContext
        private val token = UUID.randomUUID().toString()
        private val root = File(app.noBackupFilesDir.canonicalFile, "queue-maintenance-probe-$token")
        private val keyPolicy = AeadKeyPolicy("walksafe.instrumentation.queue-maintenance.$token.v", 1, setOf(1))
        private val aead = AndroidKeyStoreAead(keyPolicy)
        private val capacity = ApprovedReportQueueCapacityProfile(8, 4096, 32768, 131072, 6, 98304)
        val reports get() = File(root, "report_queue_v1/reports")
        var now = 1_000L
        init { check(root.mkdirs()) }
        fun file(id: String) = File(reports, "$id.aead")
        fun cleanup() = AndroidReportQueueStore(root, capacityProfile = null, aead = aead, nowMillis = { now })
        fun enqueue(): String {
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            val image = try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray() }
                finally { bitmap.recycle() }
            return try {
                val queue = AndroidReportQueueStore(root, capacityProfile = capacity, aead = aead, nowMillis = { now })
                requireNotNull(queue.enqueue("maintenance-test", """{"reporter_user_id":"maintenance-test","trigger":"voice","auto_reported":false}""".toByteArray(),
                    image, ReportQueuePriority.EXPLICIT, "123e4567-e89b-42d3-a456-426614174001", "c".repeat(64), 1)).use { it.payload.reportId }
            } finally { image.fill(0) }
        }
        override fun close() {
            if (reports.isDirectory) Os.chmod(reports.absolutePath, 0b111000000)
            check(root.deleteRecursively())
            check(aead.destroyKnownVersions())
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(keyPolicy.alias(1) + ".generation") }
        }
    }
}

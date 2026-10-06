package kr.co.hanium.dreamup.walksafe.report

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AndroidKeyStoreAead
import org.junit.Assert.*
import org.junit.Test

/** Real ART, files and Keystore; synthetic JPEG comments exercise the 4 MiB payload boundary.
 * No camera, network upload, battery/thermal soak, or claim of profiling peak heap. */
class ReportStoragePressureDeviceTest {
    @Test fun maximumPayloadSequentialRestoreQuarantineAndPurge() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(instrumentation.targetContext.noBackupFilesDir.canonicalFile, "report-probe-${UUID.randomUUID()}")
        val policy = AeadKeyPolicy("walksafe.instrumentation.report.${UUID.randomUUID()}.v", 1, setOf(1))
        val aead = AndroidKeyStoreAead(policy)
        val profile = ApprovedReportQueueCapacityProfile(8, 4*1024*1024, 12L*1024*1024, 64L*1024*1024, 6, 48L*1024*1024)
        fun store() = AndroidReportQueueStore(root, profile, aead)
        val actor = "device-storage-probe"
        val metadata = """{"reporter_user_id":"$actor","trigger":"voice","auto_reported":false}""".toByteArray()
        val jpeg = paddedJpeg(profile.maxPayloadBytes - metadata.size)
        val walk = UUID.randomUUID().toString()
        val receipt = "a".repeat(64)
        val started = SystemClock.elapsedRealtime()
        val runtime = Runtime.getRuntime()
        var maxObservedHeap = 0L
        try {
            assertTrue(root.mkdirs())
            assertTrue(JpegStructureValidator.isStructurallyValid(jpeg))
            val queue = store()
            assertTrue(queue.payloadExceedsLimit(metadata.size, jpeg.size + 1))
            repeat(3) {
                val report = requireNotNull(queue.enqueue(actor, metadata, jpeg, ReportQueuePriority.EXPLICIT, walk, receipt, 1L))
                assertEquals(profile.maxPayloadBytes.toLong(), report.payload.payloadBytes)
                val owned = FrozenReportPayload::class.java.getDeclaredField("frozenImage").apply { isAccessible = true }.get(report.payload) as ByteArray
                report.close()
                assertTrue("Close must wipe the owned image on ART", owned.all { it == 0.toByte() })
                maxObservedHeap = maxOf(maxObservedHeap, runtime.totalMemory() - runtime.freeMemory())
            }
            val restored = store()
            assertEquals(3, restored.countForDrain(walk, receipt))
            repeat(3) {
                requireNotNull(restored.nextForDrain(walk, receipt)).use { report ->
                    assertEquals(profile.maxPayloadBytes.toLong(), report.payload.payloadBytes)
                    assertTrue(restored.recordUploadFailure(report, permanent = true))
                }
                maxObservedHeap = maxOf(maxObservedHeap, runtime.totalMemory() - runtime.freeMemory())
            }
            assertNull(restored.nextForDrain(walk, receipt))
            assertEquals(3, requireNotNull(restored.quarantinedReports(actor, 1L)).size)
            assertTrue(restored.onAccountDeleted())
            assertNull(restored.enqueue(actor, metadata, jpeg, ReportQueuePriority.EXPLICIT, walk, receipt, 1L))
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "REPORT_STORAGE payload_bytes=${profile.maxPayloadBytes} entries=3 native_keystore=true restored=true quarantined=true wiped=true purge_fenced=true elapsed_ms=${SystemClock.elapsedRealtime()-started} sampled_heap_bytes=$maxObservedHeap peak_heap=NOT_MEASURED\n") })
        } finally {
            jpeg.fill(0)
            root.deleteRecursively()
            aead.destroyKnownVersions()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(policy.alias(1) + ".generation") }
        }
    }

    private fun paddedJpeg(bytes: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val basic = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        bitmap.recycle()
        val output = ByteArrayOutputStream(bytes)
        output.write(basic, 0, 2)
        var remaining = bytes - basic.size
        while (remaining > 0) {
            var segment = minOf(remaining, 65_537)
            if (remaining - segment in 1..3) segment -= 4
            require(segment >= 4)
            output.write(0xff); output.write(0xfe)
            val length = segment - 2
            output.write(length shr 8); output.write(length and 255)
            output.write(ByteArray(segment - 4))
            remaining -= segment
        }
        output.write(basic, 2, basic.size - 2)
        return output.toByteArray()
    }
}

package kr.co.hanium.dreamup.walksafe.report

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidReportUploaderTest {
    @Test
    fun multipartBodyContainsMetadataAndJpegImageParts() {
        val uploader = AndroidReportUploader()
        val output = ByteArrayOutputStream()
        val boundary = "----walksafe-test"
        val metadata = """{"source":"android","class_name":"damaged_tactile_block"}"""
        val image = byteArrayOf(0x31, 0x32, 0x33)

        val writer = AndroidReportUploader::class.java.getDeclaredMethod(
            "writeMultipart",
            OutputStream::class.java,
            String::class.java,
            String::class.java,
            ByteArray::class.java,
        )
        writer.isAccessible = true
        writer.invoke(uploader, output, boundary, metadata, image)

        val body = output.toString(Charsets.UTF_8)
        assertTrue(body.contains("--$boundary\r\n"))
        assertTrue(body.contains("Content-Disposition: form-data; name=\"metadata\""))
        assertTrue(body.contains("Content-Type: application/json"))
        assertTrue(body.contains(metadata))
        assertTrue(body.contains("Content-Disposition: form-data; name=\"image\"; filename=\"report.jpg\""))
        assertTrue(body.contains("Content-Type: image/jpeg"))
        assertTrue(body.contains("123"))
        assertTrue(body.endsWith("--$boundary--\r\n"))
    }

    @Test
    fun parsesDuplicateReportIdsFromUploadResponse() {
        val body = """{"duplicate_report_ids":["a","b"],"metadata":{"duplicate_report_ids":["fallback"]}}"""

        assertEquals(listOf("a", "b"), duplicateReportIdsFromBody(body))
    }

    @Test
    fun parsesDuplicateReportIdsFromMetadataFallback() {
        val body = """{"metadata":{"duplicate_report_ids":["fallback"]}}"""

        assertEquals(listOf("fallback"), duplicateReportIdsFromBody(body))
        assertEquals(emptyList<String>(), duplicateReportIdsFromBody("not-json"))
    }
}

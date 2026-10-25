package kr.co.hanium.dreamup.walksafe.report

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kr.co.hanium.dreamup.walksafe.support.*
import kr.co.hanium.dreamup.walksafe.testing.syntheticBoundaryContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/** Native SDK HTTP; all authority, images and receipts belong to the isolated loopback fixture. */
@RunWith(AndroidJUnit4::class)
class ReportNativeHttpStreamingDeviceTest {
    @Test fun httpErrorsKeepStatusBodyAndRetryAfter() {
        syntheticBoundaryContext()
        try {
            for (status in listOf(401, 403, 422, 429, 503)) {
                val body = """{"detail":{"code":"synthetic_transfer_error"}}"""
                NativeTransferServer { TransferHttpReply(status, body, mapOf("Retry-After" to "600")) }.use { server ->
                    allowSyntheticReport()
                    val failure = assertThrows(ReportUploadHttpException::class.java) {
                        upload(server.baseUrl, byteArrayOf(1, 2, 3)).execute()
                    }
                    assertEquals(status, failure.error.statusCode)
                    assertEquals(body, failure.error.errorBody)
                    assertEquals(600_000L, failure.error.retryAfterMs)
                    assertFalse(failure.toString().contains("synthetic_transfer_error"))
                    assertEquals(1, server.requests.size)
                    assertEquals("POST", server.requests.single().method)
                    assertEquals("/api/reports/v2", server.requests.single().path)
                }
            }
        } finally { ReportPrivacyConsentSession.resetForNewEnrollment() }
    }

    @Test fun serverReceivesTheDeclaredLengthAndTheCompleteBody() {
        syntheticBoundaryContext()
        val image = ByteArray(4 * 1_024 * 1_024 - 1_024) { (it % 253).toByte() }
        try {
            NativeTransferServer { TransferHttpReply(201, successBody(image)) }.use { server ->
                allowSyntheticReport()
                val response = upload(server.baseUrl, image).execute()
                assertEquals(ReportUploadReceiptOutcome.BOUND, response.receiptOutcome)
                val request = server.requests.single()
                assertFalse(request.headers.containsKey("transfer-encoding"))
                assertEquals(request.body.size, request.headers.getValue("content-length").toInt())
                val boundary = request.headers.getValue("content-type").substringAfter("boundary=")
                val expected = ByteArrayOutputStream().also {
                    writeReportMultipart(it, boundary, metadata().toByteArray(Charsets.UTF_8), image)
                }.toByteArray()
                assertArrayEquals(expected, request.body)
                assertEquals("wifi", request.headers[kr.co.hanium.dreamup.walksafe.network.CONSENT_NETWORK_TRANSPORT_HEADER])
            }
        } finally {
            image.fill(0)
            ReportPrivacyConsentSession.resetForNewEnrollment()
        }
    }

    private fun allowSyntheticReport() {
        ReportPrivacyConsentSession.resetForNewEnrollment()
        check(ReportPrivacyConsentSession.grantFromServerConfirmedIntegratedConsent())
    }

    private fun upload(origin: String, image: ByteArray) = AndroidReportUploader().uploadCall(
        permit = requireNotNull(ReportPrivacyConsentSession.issueUploadPermit(ReportTransferPurpose.EXPLICIT)),
        session = transferSession(origin, ACTOR), consentConfirmation = transferConsent(),
        networkBinding = transferWifiBinding(), transferPurpose = ReportTransferPurpose.EXPLICIT,
        metadataJson = metadata(), imageJpeg = image, stableTraceId = TRACE,
        expectedGatewayActorId = ACTOR)

    private fun metadata() = """{"auto_reported":false,"trace_id":"$TRACE"}"""

    private fun successBody(image: ByteArray) = JSONObject()
        .put("id", "123e4567-e89b-42d3-a456-426614174000")
        .put("status", "new").put("class_name", "damaged_tactile_block").put("source", "android")
        .put("confidence", 0.91)
        .put("bbox", JSONObject().put("x", 0.1).put("y", 0.2).put("width", 0.3).put("height", 0.4))
        .put("image_path", "/uploads/synthetic-report.jpg")
        .put("metadata", JSONObject().put("trace_id", TRACE).put("ingested_by_actor_id", ACTOR)
            .put("image_sha256", MessageDigest.getInstance("SHA-256").digest(image)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }))
        .toString()

    private companion object {
        const val ACTOR = "native-report-tester"
        const val TRACE = "report:native-report-tester:123e4567-e89b-42d3-a456-426614174000"
    }
}

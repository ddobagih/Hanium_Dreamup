package kr.co.hanium.dreamup.walksafe.report

import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkBinding
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkTransport
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_POLICY_VERSION
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentConfirmation
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentItemVersions
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentSelections
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual Android HTTP uploader to a phone-loopback fixture only. Synthetic body/receipt, no external report. */
class ReportNativeHttpStreamingDeviceTest {
    @After fun reset() {
        ReportPrivacyConsentSession.resetForNewEnrollment()
    }

    @Test fun malformedNativeRepliesCannotConfirmReportAcceptance() {
        for (variant in listOf("short", "ambiguous", "duplicate_length", "encoding", "utf8", "extra_json", "unfinished_chunks")) {
            nativeReceiptBoundary(variant, accepted = false)
        }
    }

    @Test fun nativeValidFixedChunkedAndCloseDelimitedReceiptsRemainAccepted() {
        for (variant in listOf("fixed", "chunked", "close")) nativeReceiptBoundary(variant, accepted = true)
    }

    private fun nativeReceiptBoundary(variant: String, accepted: Boolean) {
        val image = byteArrayOf(1, 2, 3)
        val valid = successBody(image)
        val bytes = when (variant) {
            "extra_json" -> (valid + "{}").toByteArray()
            "utf8" -> (valid.dropLast(1) + ",\"unused\":\"").toByteArray() +
                byteArrayOf(0xc3.toByte(), 0x28) + "\"}".toByteArray()
            else -> valid.toByteArray()
        }
        ServerSocket(0).use { server ->
            server.soTimeout = 10_000
            val executor = Executors.newSingleThreadExecutor()
            try {
                val sent = executor.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val input = socket.getInputStream()
                        val head = ByteArrayOutputStream()
                        while (!head.toString("US-ASCII").endsWith("\r\n\r\n")) {
                            val value = input.read()
                            check(value >= 0 && head.size() < 16_384)
                            head.write(value)
                        }
                        val length = head.toString("US-ASCII").lineSequence()
                            .first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                        check(length in 0..16_384)
                        repeat(length) { check(input.read() >= 0) }
                        val framing = when (variant) {
                            "short" -> "Content-Length: ${bytes.size + 5}\r\n"
                            "ambiguous" -> "Content-Length: ${bytes.size}\r\nTransfer-Encoding: chunked\r\n"
                            "duplicate_length" -> "Content-Length: ${bytes.size}\r\nContent-Length: ${bytes.size}\r\n"
                            "encoding" -> "Content-Length: ${bytes.size}\r\nContent-Encoding: gzip\r\n"
                            "chunked", "unfinished_chunks" -> "Transfer-Encoding: chunked\r\n"
                            "close" -> ""
                            else -> "Content-Length: ${bytes.size}\r\n"
                        }
                        // Send one complete fixture buffer; a native early rejection may close its peer.
                        val response = ByteArrayOutputStream().apply {
                            write(("HTTP/1.1 201 Created\r\nContent-Type: application/json\r\n" + framing +
                                "Connection: close\r\n\r\n").toByteArray())
                            if (variant in listOf("ambiguous", "chunked", "unfinished_chunks")) {
                                write("${bytes.size.toString(16)}\r\n".toByteArray()); write(bytes); write("\r\n".toByteArray())
                                if (variant != "unfinished_chunks") write("0\r\n\r\n".toByteArray())
                            } else write(bytes)
                        }.toByteArray()
                        socket.getOutputStream().apply { write(response); flush() }
                    }
                }
                ReportPrivacyConsentSession.resetForNewEnrollment()
                check(ReportPrivacyConsentSession.grantFromServerConfirmedIntegratedConsent())
                val result = runCatching {
                    AndroidReportUploader().uploadCall(
                        requireNotNull(ReportPrivacyConsentSession.issueUploadPermit(ReportTransferPurpose.EXPLICIT)),
                        GatewayFieldSession.verified("http://127.0.0.1:${server.localPort}", ACTOR_ID,
                            "walksafe_field_session=v2.test.session.cookie", Long.MAX_VALUE),
                        consentConfirmation(), IntegratedConsentNetworkBinding.forTest(IntegratedConsentNetworkTransport.WIFI) { it.openConnection() },
                        ReportTransferPurpose.EXPLICIT, """{"auto_reported":false,"trace_id":"$TRACE_ID"}""",
                        image, TRACE_ID, ACTOR_ID,
                    ).execute()
                }
                sent.get(10, TimeUnit.SECONDS)
                if (accepted) assertEquals(ReportUploadReceiptOutcome.BOUND, result.getOrThrow().receiptOutcome)
                else assertTrue("$variant must reject receipt, got $result",
                    result.exceptionOrNull() is java.io.IOException || result.exceptionOrNull() is ReportUploadProtocolException)
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0,
                    android.os.Bundle().apply { putString("stream", "NATIVE_REPORT_BOUNDARY variant=$variant accepted=$accepted external_upload=false\n") })
            } finally {
                server.close()
                executor.shutdownNow()
                check(executor.awaitTermination(15, TimeUnit.SECONDS))
                ReportPrivacyConsentSession.resetForNewEnrollment()
            }
        }
    }

    @Test fun httpErrorsKeepStatusBodyAndRetryAfter() {
        for (status in listOf(401, 403, 422, 429, 503)) {
            val errorBody = """{"detail":{"code":"device_test_failure"}}"""
            ServerSocket(0).use { server ->
                server.soTimeout = 10_000
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val sent = executor.submit {
                        server.accept().use { socket ->
                            socket.soTimeout = 10_000
                            val input = socket.getInputStream()
                            val head = ByteArrayOutputStream()
                            while (!head.toString("US-ASCII").endsWith("\r\n\r\n")) {
                                val value = input.read()
                                check(value >= 0 && head.size() < 16_384)
                                head.write(value)
                            }
                            val length = head.toString("US-ASCII").lineSequence()
                                .first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                            repeat(length) { check(input.read() >= 0) }
                            val response = errorBody.toByteArray()
                            socket.getOutputStream().apply {
                                write(("HTTP/1.1 $status Test Error\r\nContent-Type: application/json\r\n" +
                                    "Retry-After: 600\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n").toByteArray())
                                write(response); flush()
                            }
                        }
                    }
                    ReportPrivacyConsentSession.resetForNewEnrollment()
                    check(ReportPrivacyConsentSession.grantFromServerConfirmedIntegratedConsent())
                    val failure = runCatching {
                        AndroidReportUploader().uploadCall(
                            permit = requireNotNull(ReportPrivacyConsentSession.issueUploadPermit(ReportTransferPurpose.EXPLICIT)),
                            session = GatewayFieldSession.verified("http://127.0.0.1:${server.localPort}",
                                ACTOR_ID, "walksafe_field_session=v2.test.session.cookie", Long.MAX_VALUE),
                            consentConfirmation = consentConfirmation(),
                            networkBinding = IntegratedConsentNetworkBinding.forTest(IntegratedConsentNetworkTransport.WIFI) { it.openConnection() },
                            transferPurpose = ReportTransferPurpose.EXPLICIT,
                            metadataJson = """{"auto_reported":false,"trace_id":"$TRACE_ID"}""",
                            imageJpeg = byteArrayOf(1, 2, 3), stableTraceId = TRACE_ID, expectedGatewayActorId = ACTOR_ID,
                        ).execute()
                    }.exceptionOrNull()
                    sent.get(10, TimeUnit.SECONDS)
                    assertTrue("Expected typed HTTP failure for $status, got $failure", failure is ReportUploadHttpException)
                    val error = (failure as ReportUploadHttpException).error
                    assertEquals(status, error.statusCode)
                    assertEquals(errorBody, error.errorBody)
                    assertEquals(600_000L, error.retryAfterMs)
                    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0,
                        android.os.Bundle().apply { putString("stream", "NATIVE_HTTP_ERROR status=$status body_preserved=true retry_after_ms=600000 external_upload=false\n") })
                } finally { executor.shutdownNow() }
            }
        }
    }

    @Test
    fun serverReceivesTheDeclaredLengthAndTheCompleteBody() {
        val image = ByteArray(4 * 1024 * 1024 - 1024) { (it % 253).toByte() }
        val metadata = """{"auto_reported":false,"trace_id":"$TRACE_ID"}"""
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val captured = executor.submit<Pair<String, ByteArray>> {
                    server.accept().use { socket ->
                        val input = socket.getInputStream()
                        val head = ByteArrayOutputStream()
                        while (!head.toString(Charsets.US_ASCII.name()).endsWith("\r\n\r\n")) {
                            val value = input.read()
                            check(value >= 0)
                            head.write(value)
                        }
                        val headers = head.toString(Charsets.US_ASCII.name())
                        val length = headers.lineSequence()
                            .first { it.startsWith("Content-Length:", ignoreCase = true) }
                            .substringAfter(':').trim().toInt()
                        val body = ByteArray(length)
                        var offset = 0
                        while (offset < length) {
                            val read = input.read(body, offset, length - offset)
                            check(read >= 0)
                            offset += read
                        }
                        val response = successBody(image).toByteArray()
                        socket.getOutputStream().apply {
                            write(
                                (
                                    "HTTP/1.1 201 Created\r\nContent-Type: application/json\r\n" +
                                        "Content-Length: ${response.size}\r\nConnection: close\r\n\r\n"
                                    ).toByteArray(Charsets.US_ASCII),
                            )
                            write(response)
                            flush()
                        }
                        headers to body
                    }
                }
                ReportPrivacyConsentSession.resetForNewEnrollment()
                check(ReportPrivacyConsentSession.grantFromServerConfirmedIntegratedConsent())
                val response = AndroidReportUploader().uploadCall(
                    permit = requireNotNull(
                        ReportPrivacyConsentSession.issueUploadPermit(ReportTransferPurpose.EXPLICIT),
                    ),
                    session = GatewayFieldSession.verified(
                        gatewayBaseUrl = "http://127.0.0.1:${server.localPort}",
                        actorId = ACTOR_ID,
                        cookiePair = "walksafe_field_session=v2.test.session.cookie",
                        expiresAtEpochMs = Long.MAX_VALUE,
                    ),
                    consentConfirmation = consentConfirmation(),
                    networkBinding = IntegratedConsentNetworkBinding.forTest(
                        IntegratedConsentNetworkTransport.WIFI,
                    ) { url -> url.openConnection() },
                    transferPurpose = ReportTransferPurpose.EXPLICIT,
                    metadataJson = metadata,
                    imageJpeg = image,
                    stableTraceId = TRACE_ID,
                    expectedGatewayActorId = ACTOR_ID,
                ).execute()
                val (headers, body) = captured.get(10, TimeUnit.SECONDS)
                assertEquals(ReportUploadReceiptOutcome.BOUND, response.receiptOutcome)
                assertFalse(headers.contains("Transfer-Encoding", ignoreCase = true))
                val boundary = headers.lineSequence()
                    .first { it.startsWith("Content-Type:", ignoreCase = true) }
                    .substringAfter("boundary=").trim()
                val expected = ByteArrayOutputStream().also {
                    writeReportMultipart(it, boundary, metadata.toByteArray(), image)
                }.toByteArray()
                assertArrayEquals(expected, body)
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0,
                    android.os.Bundle().apply { putString("stream", "NATIVE_HTTP synthetic_image_bytes=${image.size} fixed_length=true body_exact=true receipt_bound=true external_upload=false\n") })
            } finally {
                executor.shutdownNow()
            }
        }
    }

    private fun successBody(image: ByteArray): String = JSONObject()
        .put("id", "123e4567-e89b-12d3-a456-426614174000")
        .put("status", "new")
        .put("class_name", "damaged_tactile_block")
        .put("source", "android")
        .put("confidence", 0.91)
        .put("bbox", JSONObject().put("x", 0.1).put("y", 0.2).put("width", 0.3).put("height", 0.4))
        .put("image_path", "/uploads/report.jpg")
        .put(
            "metadata",
            JSONObject()
                .put("trace_id", TRACE_ID)
                .put("ingested_by_actor_id", ACTOR_ID)
                .put(
                    "image_sha256",
                    MessageDigest.getInstance("SHA-256").digest(image)
                        .joinToString("") { "%02x".format(it.toInt() and 0xff) },
                ),
        )
        .toString()

    private fun consentConfirmation() = IntegratedConsentConfirmation(
        schemaVersion = INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,
        policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
        installationId = "501e3ad4-e74f-4433-820f-72ac2fdd42ad",
        requestId = "integrated_consent_request_0001",
        itemVersions = IntegratedConsentItemVersions(),
        clientRevision = 1L,
        revision = 1L,
        selections = IntegratedConsentSelections(),
        confirmedAt = "2026-07-25T12:00:00.000Z",
        gatewayAuditRecordSha256 = "9".repeat(64),
        backendConsentReceiptSha256 = "a".repeat(64),
        controlSecret = "b".repeat(64),
    )

    private companion object {
        const val ACTOR_ID = "report-tester"
        const val TRACE_ID = "report:report-tester:123e4567-e89b-12d3-a456-426614174000"
    }
}

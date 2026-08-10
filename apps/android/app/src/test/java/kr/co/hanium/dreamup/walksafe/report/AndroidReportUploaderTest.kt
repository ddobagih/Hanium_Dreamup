package kr.co.hanium.dreamup.walksafe.report

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.network.ActiveNetworkCalls
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkTransport
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkBinding
import kr.co.hanium.dreamup.walksafe.network.LocalHttpTestServer
import kr.co.hanium.dreamup.walksafe.network.NetworkResponseTooLargeException
import kr.co.hanium.dreamup.walksafe.network.awaitPeerDisconnect
import kr.co.hanium.dreamup.walksafe.network.writeChunkedResponse
import kr.co.hanium.dreamup.walksafe.network.writeStalledChunkedHeaders
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_POLICY_VERSION
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentConfirmation
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentItemVersions
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentSelections
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    @Test
    fun retryBackoffIsBoundedAndHonorsAuthAndServerDelays() {
        assertEquals(2_000L, reportRetryDelayMs(consecutiveFailures = 1))
        assertEquals(16_000L, reportRetryDelayMs(consecutiveFailures = 4))
        assertEquals(30_000L, reportRetryDelayMs(consecutiveFailures = 20))
        assertEquals(60_000L, reportRetryDelayMs(consecutiveFailures = 1, statusCode = 401))
        assertEquals(45_000L, reportRetryDelayMs(consecutiveFailures = 1, statusCode = 429, retryAfterMs = 45_000L))
        assertEquals(300_000L, reportRetryDelayMs(consecutiveFailures = 1, retryAfterMs = 900_000L))
    }

    @Test
    fun exactReceiptFieldsAreAcceptedAsBound() {
        val response =
            validatedReportUploadResponseOrNull(
                statusCode = 201,
                body = structuredSuccessBody(),
                receiptExpectation = receiptExpectation(),
            )

        assertNotNull(response)
        assertEquals(ReportUploadReceiptOutcome.BOUND, response?.receiptOutcome)
    }

    @Test
    fun actorReceiptFieldMustAlwaysBePresentAndExact() {
        listOf(OMITTED_RECEIPT_FIELD, JSONObject.NULL, "another-actor").forEach { actorId ->
            ReportTransferPurpose.entries.forEach { purpose ->
                assertThrows(ReportUploadProtocolException::class.java) {
                    validatedReportUploadResponseOrNull(
                        statusCode = 201,
                        body = structuredSuccessBody(actorId = actorId),
                        receiptExpectation = receiptExpectation(purpose),
                    )
                }
            }
        }
    }

    @Test
    fun missingAndJsonNullReceiptFieldsAreAmbiguousOnlyForAutomaticReports() {
        val absentBodies =
            listOf(
                structuredSuccessBody(traceId = OMITTED_RECEIPT_FIELD),
                structuredSuccessBody(traceId = JSONObject.NULL),
                structuredSuccessBody(imageSha256 = OMITTED_RECEIPT_FIELD),
                structuredSuccessBody(imageSha256 = JSONObject.NULL),
            )

        absentBodies.forEach { body ->
            assertEquals(
                ReportUploadReceiptOutcome.AUTOMATIC_COOLDOWN_AMBIGUOUS,
                validatedReportUploadResponseOrNull(
                    statusCode = 201,
                    body = body,
                    receiptExpectation = receiptExpectation(ReportTransferPurpose.AUTOMATIC),
                )?.receiptOutcome,
            )
            assertThrows(ReportUploadProtocolException::class.java) {
                validatedReportUploadResponseOrNull(
                    statusCode = 201,
                    body = body,
                    receiptExpectation = receiptExpectation(ReportTransferPurpose.EXPLICIT),
                )
            }
        }
    }

    @Test
    fun nonNullTraceAndImageReceiptMismatchesAreProtocolFailures() {
        val mismatchedBodies =
            listOf(
                structuredSuccessBody(traceId = "different-trace"),
                structuredSuccessBody(traceId = 7),
                structuredSuccessBody(imageSha256 = "f".repeat(64)),
                structuredSuccessBody(imageSha256 = false),
            )

        mismatchedBodies.forEach { body ->
            ReportTransferPurpose.entries.forEach { purpose ->
                assertThrows(ReportUploadProtocolException::class.java) {
                    validatedReportUploadResponseOrNull(
                        statusCode = 201,
                        body = body,
                        receiptExpectation = receiptExpectation(purpose),
                    )
                }
            }
        }
    }

    @Test
    fun receiptExpectationHashesExactImageBytesAsLowercaseSha256() {
        val expectation = receiptExpectation(imageJpeg = byteArrayOf(1, 2, 3))

        assertEquals(
            "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
            expectation.imageSha256,
        )
        assertThrows(ReportUploadProtocolException::class.java) {
            validatedReportUploadResponseOrNull(
                statusCode = 201,
                body = structuredSuccessBody(),
                receiptExpectation = receiptExpectation(imageJpeg = byteArrayOf(3, 2, 1)),
            )
        }
    }

    @Test
    fun acceptsOnlyStructuredBackendReportSuccess() {
        val expectation = receiptExpectation()

        assertNotNull(validatedReportUploadResponseOrNull(201, structuredSuccessBody(), expectation))
        assertNull(validatedReportUploadResponseOrNull(204, "", expectation))
        assertNull(validatedReportUploadResponseOrNull(200, "<html>ok</html>", expectation))
        assertNull(
            validatedReportUploadResponseOrNull(
                200,
                """{"id":"not-a-uuid"}""",
                expectation,
            ),
        )
        assertNull(
            validatedReportUploadResponseOrNull(
                201,
                JSONObject(structuredSuccessBody())
                    .put("bbox", JSONObject().put("x", 0.1))
                    .toString(),
                expectation,
            ),
        )
    }

    @Test
    fun lifecycleCancellationUnblocksActiveReportSocketBeforeReadTimeout() {
        val stalledResponseStarted = CountDownLatch(1)
        LocalHttpTestServer { _, socket ->
            socket.writeStalledChunkedHeaders()
            stalledResponseStarted.countDown()
            socket.awaitPeerDisconnect(3_000)
        }.use { server ->
            val calls = ActiveNetworkCalls()
            val call = calls.track(
                AndroidReportUploader().uploadCall(
                    permit = uploadPermit(),
                    session = session(server.baseUrl),
                    consentConfirmation = consentConfirmation(),
                    networkBinding = testNetworkBinding(),
                    transferPurpose = ReportTransferPurpose.EXPLICIT,
                    metadataJson = """{"source":"android","auto_reported":false,"trace_id":"$TEST_TRACE_ID"}""",
                    imageJpeg = byteArrayOf(1, 2, 3),
                    stableTraceId = TEST_TRACE_ID,
                    expectedGatewayActorId = TEST_ACTOR_ID,
                ),
            )
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit<ReportUploadResponse> { call.execute() }
                assertTrue(stalledResponseStarted.await(2, TimeUnit.SECONDS))

                calls.cancelAll()

                val failure = assertThrows(ExecutionException::class.java) {
                    future.get(2, TimeUnit.SECONDS)
                }
                assertTrue(failure.cause is CancellationException)
            } finally {
                calls.complete(call)
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun rejectsOversizedErrorFromContentLengthBeforeReadingBody() {
        val releaseServer = CountDownLatch(1)
        LocalHttpTestServer { _, socket ->
            socket.writeHeadersWithLength(422, REPORT_UPLOAD_MAX_RESPONSE_BYTES.toLong() + 1L)
            releaseServer.await(3, TimeUnit.SECONDS)
        }.use { server ->
            try {
                assertThrows(NetworkResponseTooLargeException::class.java) {
                    AndroidReportUploader().uploadCall(
                        permit = uploadPermit(),
                        session = session(server.baseUrl),
                        consentConfirmation = consentConfirmation(),
                        networkBinding = testNetworkBinding(),
                        transferPurpose = ReportTransferPurpose.EXPLICIT,
                        metadataJson = """{"auto_reported":false,"trace_id":"$TEST_TRACE_ID"}""",
                        imageJpeg = byteArrayOf(1),
                        stableTraceId = TEST_TRACE_ID,
                        expectedGatewayActorId = TEST_ACTOR_ID,
                    ).execute()
                }
            } finally {
                releaseServer.countDown()
            }
        }
    }

    @Test
    fun rejectsOversizedChunkedSuccessBeforeReportJsonParsing() {
        LocalHttpTestServer { _, socket ->
            socket.writeChunkedResponse(201, ByteArray(REPORT_UPLOAD_MAX_RESPONSE_BYTES + 1) { 'x'.code.toByte() })
        }.use { server ->
            assertThrows(NetworkResponseTooLargeException::class.java) {
                AndroidReportUploader().uploadCall(
                    permit = uploadPermit(),
                    session = session(server.baseUrl),
                    consentConfirmation = consentConfirmation(),
                    networkBinding = testNetworkBinding(),
                    transferPurpose = ReportTransferPurpose.EXPLICIT,
                    metadataJson = """{"auto_reported":false,"trace_id":"$TEST_TRACE_ID"}""",
                    imageJpeg = byteArrayOf(1),
                    stableTraceId = TEST_TRACE_ID,
                    expectedGatewayActorId = TEST_ACTOR_ID,
                ).execute()
            }
        }
    }

    @Test
    fun rejectsPurposeMismatchBeforeOpeningBoundNetwork() {
        var opened = false
        val binding = IntegratedConsentNetworkBinding.forTest(
            IntegratedConsentNetworkTransport.WIFI,
        ) { url ->
            opened = true
            url.openConnection()
        }

        assertThrows(IllegalArgumentException::class.java) {
            AndroidReportUploader().uploadCall(
                permit = uploadPermit(ReportTransferPurpose.AUTOMATIC),
                session = session("http://127.0.0.1:1"),
                consentConfirmation = consentConfirmation(),
                networkBinding = binding,
                transferPurpose = ReportTransferPurpose.AUTOMATIC,
                metadataJson = """{"auto_reported":false,"trace_id":"$TEST_TRACE_ID"}""",
                imageJpeg = byteArrayOf(1),
                stableTraceId = TEST_TRACE_ID,
                expectedGatewayActorId = TEST_ACTOR_ID,
            )
        }
        assertFalse(opened)
    }

    @Test
    fun rejectsForgedCallerActorBeforeOpeningBoundNetwork() {
        var opened = false
        val binding = IntegratedConsentNetworkBinding.forTest(
            IntegratedConsentNetworkTransport.WIFI,
        ) { url ->
            opened = true
            url.openConnection()
        }

        assertThrows(IllegalArgumentException::class.java) {
            AndroidReportUploader().uploadCall(
                permit = uploadPermit(),
                session = session("http://127.0.0.1:1"),
                consentConfirmation = consentConfirmation(),
                networkBinding = binding,
                transferPurpose = ReportTransferPurpose.EXPLICIT,
                metadataJson = """{"auto_reported":false,"trace_id":"$TEST_TRACE_ID"}""",
                imageJpeg = TEST_IMAGE,
                stableTraceId = TEST_TRACE_ID,
                expectedGatewayActorId = "forged-actor",
            )
        }
        assertFalse(opened)
    }

    @Test
    fun uploadReceiptIsBoundToExactVerifiedSessionActor() {
        LocalHttpTestServer { _, socket ->
            socket.writeChunkedResponse(
                201,
                structuredSuccessBody(actorId = TEST_ACTOR_ID).toByteArray(Charsets.UTF_8),
            )
        }.use { server ->
            val response =
                AndroidReportUploader().uploadCall(
                    permit = uploadPermit(),
                    session = session(server.baseUrl),
                    consentConfirmation = consentConfirmation(),
                    networkBinding = testNetworkBinding(),
                    transferPurpose = ReportTransferPurpose.EXPLICIT,
                    metadataJson = """{"auto_reported":false,"trace_id":"$TEST_TRACE_ID"}""",
                    imageJpeg = TEST_IMAGE,
                    stableTraceId = TEST_TRACE_ID,
                    expectedGatewayActorId = TEST_ACTOR_ID,
                ).execute()

            assertEquals(ReportUploadReceiptOutcome.BOUND, response.receiptOutcome)
        }
    }

    @Test
    fun stalePermitFailsBeforeBoundNetworkOpenerRuns() {
        var opened = false
        val binding = IntegratedConsentNetworkBinding.forTest(
            IntegratedConsentNetworkTransport.WIFI,
        ) { url ->
            opened = true
            url.openConnection()
        }
        val consent = ReportPrivacyConsentSession
        consent.resetForNewEnrollment()
        assertTrue(consent.grantFromServerConfirmedIntegratedConsent())
        val permit =
            requireNotNull(
                consent.issueUploadPermit(ReportTransferPurpose.EXPLICIT),
            )
        val call = AndroidReportUploader().uploadCall(
            permit = permit,
            session = session("http://127.0.0.1:1"),
            consentConfirmation = consentConfirmation(),
            networkBinding = binding,
            transferPurpose = ReportTransferPurpose.EXPLICIT,
            metadataJson = """{"auto_reported":false,"trace_id":"$TEST_TRACE_ID"}""",
            imageJpeg = byteArrayOf(1),
            stableTraceId = TEST_TRACE_ID,
            expectedGatewayActorId = TEST_ACTOR_ID,
        )

        assertTrue(consent.withdraw())
        assertTrue(consent.grantFromServerConfirmedIntegratedConsent())
        assertThrows(IllegalStateException::class.java) { call.execute() }
        assertFalse(opened)
    }

    private fun testNetworkBinding(): IntegratedConsentNetworkBinding =
        IntegratedConsentNetworkBinding.forTest(
            IntegratedConsentNetworkTransport.WIFI,
        ) { url -> url.openConnection() }

    private fun uploadPermit(
        purpose: ReportTransferPurpose = ReportTransferPurpose.EXPLICIT,
    ): ReportUploadPermit {
        val consent = ReportPrivacyConsentSession
        consent.resetForNewEnrollment()
        check(consent.grantFromServerConfirmedIntegratedConsent())
        return requireNotNull(consent.issueUploadPermit(purpose))
    }

    private fun session(baseUrl: String): GatewayFieldSession {
        return GatewayFieldSession.verified(
            gatewayBaseUrl = baseUrl,
            actorId = TEST_ACTOR_ID,
            cookiePair = "walksafe_field_session=v2.test.session.cookie",
            expiresAtEpochMs = Long.MAX_VALUE,
        )
    }

    private fun receiptExpectation(
        purpose: ReportTransferPurpose = ReportTransferPurpose.EXPLICIT,
        imageJpeg: ByteArray = TEST_IMAGE,
    ): ReportUploadReceiptExpectation =
        reportUploadReceiptExpectation(
            stableTraceId = TEST_TRACE_ID,
            expectedGatewayActorId = TEST_ACTOR_ID,
            imageJpeg = imageJpeg,
            transferPurpose = purpose,
        )

    private fun structuredSuccessBody(
        traceId: Any? = TEST_TRACE_ID,
        actorId: Any? = TEST_ACTOR_ID,
        imageSha256: Any? = TEST_IMAGE_SHA256,
    ): String {
        val metadata = JSONObject()
        if (traceId !== OMITTED_RECEIPT_FIELD) metadata.put("trace_id", traceId)
        if (actorId !== OMITTED_RECEIPT_FIELD) metadata.put("ingested_by_actor_id", actorId)
        if (imageSha256 !== OMITTED_RECEIPT_FIELD) metadata.put("image_sha256", imageSha256)
        return JSONObject()
            .put("id", "123e4567-e89b-12d3-a456-426614174000")
            .put("status", "new")
            .put("class_name", "damaged_tactile_block")
            .put("source", "android")
            .put("confidence", 0.91)
            .put(
                "bbox",
                JSONObject()
                    .put("x", 0.1)
                    .put("y", 0.2)
                    .put("width", 0.3)
                    .put("height", 0.4),
            )
            .put("image_path", "/uploads/report.jpg")
            .put("metadata", metadata)
            .toString()
    }

    private fun consentConfirmation() = IntegratedConsentConfirmation(
        schemaVersion = INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,
        policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
        installationId = "501e3ad4-e74f-4433-820f-72ac2fdd42ad",
        requestId = "integrated_consent_request_0001",
        itemVersions = IntegratedConsentItemVersions(),
        clientRevision = 1L,
        revision = 1L,
        selections = IntegratedConsentSelections(rawSourceCollection = true),
        confirmedAt = "2026-07-25T12:00:00.000Z",
        receiptSha256 = "a".repeat(64),
        controlSecret = "b".repeat(64),
    )

    private fun Socket.writeHeadersWithLength(statusCode: Int, contentLength: Long) {
        getOutputStream().apply {
            write(
                "HTTP/1.1 $statusCode Test\r\nContent-Type: application/json\r\nContent-Length: $contentLength\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII),
            )
            flush()
        }
    }

    private companion object {
        const val TEST_TRACE_ID = "report:report-tester:123e4567-e89b-12d3-a456-426614174000"
        const val TEST_ACTOR_ID = "report-tester"
        const val TEST_IMAGE_SHA256 =
            "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81"
        val TEST_IMAGE = byteArrayOf(1, 2, 3)
        val OMITTED_RECEIPT_FIELD = Any()
    }
}

package kr.co.hanium.dreamup.walksafe.report

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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
    fun realBackendRequestBytesBindCreatedAndReplayReceiptsAndCooldownError() {
        val fixtures = requireNotNull(javaClass.getResourceAsStream("/report/s20-6-transport-outcomes.json"))
            .bufferedReader().use { JSONObject(it.readText()) }
        listOf("created", "replayed").forEach { name ->
            val fixture = fixtures.getJSONObject(name)
            val request = fixture.getJSONObject("request")
            val payload = requireNotNull(FrozenReportPayload.freeze(
                request.getString("report_id"),
                java.util.Base64.getDecoder().decode(request.getString("metadata_utf8_base64")),
                java.util.Base64.getDecoder().decode(request.getString("image_jpeg_base64")),
            ))
            val report = queuedReport().copy(payload = payload)
            val result = requireNotNull(parseQueuedUploadResultOrNull(fixture.getJSONObject("body").toString(), report))
            assertEquals(name.uppercase(Locale.ROOT), result.creationOutcome?.name)
            assertEquals(payload.payloadSha256, result.receipt.payloadSha256)
            assertEquals(payload.payloadBytes, result.receipt.payloadBytes)
        }
        val suppressed = fixtures.getJSONObject("suppressed")
        val error = ReportUploadError(
            suppressed.getInt("status"), suppressed.getJSONObject("body").toString(),
            parseReportRetryAfterMs(suppressed.getString("retry_after")),
        )
        assertTrue(error.isAutomaticCooldownSuppression())
        assertEquals(600_000L, error.retryAfterMs)
    }

    @Test
    fun exactReceiptRetainsCreatedAndReplayedWithoutInventingLegacyOutcome() {
        val report = queuedReport()
        listOf("CREATED", "REPLAYED", null, JSONObject.NULL).forEach { literal ->
            val body = JSONObject().put("transport_receipt", transportReceipt(report))
            if (literal != null) body.put("creation_outcome", literal)
            val result = requireNotNull(parseQueuedUploadResultOrNull(body.toString(), report))
            assertEquals(literal.takeIf { it is String }, result.creationOutcome?.name)
            assertEquals(report.payload.reportId, result.receipt.reportId)
        }
    }

    @Test
    fun successOutcomeCannotHideMalformedUnknownOrSuppressedResult() {
        val report = queuedReport()
        listOf("UNKNOWN", "", true, 1, "SUPPRESSED_BY_COOLDOWN").forEach { literal ->
            val body = JSONObject().put("transport_receipt", transportReceipt(report))
                .put("creation_outcome", literal)
            assertNull(parseQueuedUploadReceiptOrNull(body.toString(), report))
            assertThrows(ReportUploadProtocolException::class.java) {
                validatedReportUploadResponseOrNull(
                    201, JSONObject(structuredSuccessBody()).put("creation_outcome", literal).toString(),
                    receiptExpectation(),
                )
            }
        }
    }

    @Test
    fun createdMetadataNeverOverridesExactReceiptOrLegacyBindingChecks() {
        val report = queuedReport()
        val body = JSONObject().put("creation_outcome", "CREATED")
            .put("transport_receipt", transportReceipt(report).put("payload_bytes", report.payload.payloadBytes + 1))
        assertNull(parseQueuedUploadResultOrNull(body.toString(), report))
        assertThrows(ReportUploadProtocolException::class.java) {
            validatedReportUploadResponseOrNull(
                201, JSONObject(structuredSuccessBody(traceId = OMITTED_RECEIPT_FIELD))
                    .put("creation_outcome", "CREATED").toString(),
                receiptExpectation(ReportTransferPurpose.AUTOMATIC),
            )
        }
    }

    @Test
    fun cooldownClassificationRequiresExactStatusCodeAndOutcome() {
        val detail = JSONObject().put("code", "automatic_report_cooldown_active")
            .put("creation_outcome", "SUPPRESSED_BY_COOLDOWN")
        val body = JSONObject().put("detail", detail).toString()
        assertTrue(ReportUploadError(429, body, 600_000L).isAutomaticCooldownSuppression())
        assertFalse(ReportUploadError(503, body, 600_000L).isAutomaticCooldownSuppression())
        listOf("CREATED", "REPLAYED", "UNKNOWN", true, JSONObject.NULL).forEach { outcome ->
            detail.put("creation_outcome", outcome)
            assertFalse(ReportUploadError(429, JSONObject().put("detail", detail).toString()).isAutomaticCooldownSuppression())
        }
        detail.put("creation_outcome", "SUPPRESSED_BY_COOLDOWN").put("code", "rate_limited")
        assertFalse(ReportUploadError(429, JSONObject().put("detail", detail).toString()).isAutomaticCooldownSuppression())
    }

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
    fun retryBackoffIsBoundedButLongServerDelaysArePreserved() {
        assertEquals(2_000L, reportRetryDelayMs(consecutiveFailures = 1))
        assertEquals(16_000L, reportRetryDelayMs(consecutiveFailures = 4))
        assertEquals(30_000L, reportRetryDelayMs(consecutiveFailures = 20))
        assertEquals(60_000L, reportRetryDelayMs(consecutiveFailures = 1, statusCode = 401))
        assertEquals(45_000L, reportRetryDelayMs(consecutiveFailures = 1, statusCode = 429, retryAfterMs = 45_000L))
        assertEquals(900_000L, reportRetryDelayMs(consecutiveFailures = 1, retryAfterMs = 900_000L))
        assertEquals(3_600_000L, reportRetryDelayMs(consecutiveFailures = 1, statusCode = 429, retryAfterMs = 3_600_000L))
    }

    @Test
    fun retryAfterParsesNumericSecondsAndHttpDateWithoutOverflow() {
        assertEquals(3_600_000L, parseReportRetryAfterMs("3600"))
        assertEquals(
            3_600_000L,
            parseReportRetryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT", 1_445_408_880_000L),
        )
        assertEquals(Long.MAX_VALUE / 2L, parseReportRetryAfterMs("9".repeat(100)))
        assertNull(parseReportRetryAfterMs("not-a-date"))
    }

    @Test
    fun uploadErrorPreservesNumericAndDateRetryAfterHeaders() {
        val dateHeader = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .format(Date(System.currentTimeMillis() + 3_600_000L))
        listOf("3600", dateHeader).forEach { header ->
            LocalHttpTestServer { _, socket ->
                socket.getOutputStream().apply {
                    write(
                        ("HTTP/1.1 429 Too Many Requests\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Retry-After: $header\r\n" +
                            "Content-Length: 2\r\nConnection: close\r\n\r\n{}").toByteArray(Charsets.US_ASCII),
                    )
                    flush()
                }
                socket.awaitPeerDisconnect(3_000)
            }.use { server ->
                val error = assertThrows(ReportUploadHttpException::class.java) {
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
                }.error
                val delay = requireNotNull(error.retryAfterMs)
                if (header == "3600") {
                    assertEquals(3_600_000L, delay)
                } else {
                    assertTrue(delay in 3_590_000L..3_600_000L)
                }
                assertEquals(delay, reportRetryDelayMs(1, error.statusCode, delay))
            }
        }
    }

    @Test
    fun automaticCooldownResponsePreservesTheFullServerRetryInterval() {
        assertTrue(isTransientReportHttpStatus(429))
        assertEquals(
            AndroidReportCooldownPolicy.AUTOMATIC_COOLDOWN_MS,
            reportRetryDelayMs(consecutiveFailures = 1, statusCode = 429, retryAfterMs = 600_000L),
        )
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
    fun clientMetadataActorFieldIsNotUsedAsTransportReceipt() {
        listOf(OMITTED_RECEIPT_FIELD, JSONObject.NULL, "another-actor").forEach { actorId ->
            ReportTransferPurpose.entries.forEach { purpose ->
                assertNotNull(
                    validatedReportUploadResponseOrNull(
                        statusCode = 201,
                        body = structuredSuccessBody(actorId = actorId),
                        receiptExpectation = receiptExpectation(purpose),
                    ),
                )
            }
        }
    }

    @Test
    fun queueTransportReceiptAndStatusMustMatchExactFrozenPayload() {
        val report = queuedReport()
        val receipt = transportReceipt(report)
        val uploadBody = JSONObject().put("transport_receipt", receipt).toString()
        val statusBody = JSONObject()
            .put("persistence_state", "PERSISTED")
            .put("user_status", "RECEIVED")
            .put("transport_receipt", receipt)
            .toString()

        assertNotNull(parseQueuedUploadReceiptOrNull(uploadBody, report))
        assertNotNull(parseQueuedStatusReceiptOrNull(statusBody, report))
        assertNull(
            parseQueuedUploadReceiptOrNull(
                JSONObject(uploadBody)
                    .put(
                        "transport_receipt",
                        JSONObject(receipt.toString()).put("payload_bytes", report.payload.payloadBytes + 1),
                    )
                    .toString(),
                report,
            ),
        )
        assertNull(
            parseQueuedStatusReceiptOrNull(
                JSONObject(statusBody).put("unexpected", true).toString(),
                report,
            ),
        )
    }

    @Test
    fun queuedReportActorMismatchFailsBeforeNetworkOpener() {
        var openerCalls = 0
        val binding = IntegratedConsentNetworkBinding.forTest(
            IntegratedConsentNetworkTransport.WIFI,
        ) { url ->
            openerCalls += 1
            url.openConnection()
        }

        assertThrows(IllegalArgumentException::class.java) {
            AndroidReportUploader().queuedStatusCall(
                permit = uploadPermit(),
                session = session("http://127.0.0.1:8081"),
                consentConfirmation = consentConfirmation(),
                networkBinding = binding,
                report = queuedReport().copy(reporterActorId = "another-actor"),
                approvedGatewayOrigin = "http://127.0.0.1:8081",
            )
        }
        assertEquals(0, openerCalls)
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

    private fun queuedReport(): QueuedReport {
        val payload = requireNotNull(
            FrozenReportPayload.freeze(
                "123e4567-e89b-42d3-a456-426614174010",
                "{}".toByteArray(),
                reportTestJpeg(),
            ),
        )
        return QueuedReport(
            payload = payload,
            priority = ReportQueuePriority.EXPLICIT,
            reporterActorId = TEST_ACTOR_ID,
            walkSessionId = "123e4567-e89b-42d3-a456-426614174011",
            consentReceiptSha256 = "c".repeat(64),
            createdAtEpochMs = 1_000L,
            expiresAtEpochMs = 1_000L + REPORT_QUEUE_TTL_MS,
        )
    }

    private fun transportReceipt(report: QueuedReport) = JSONObject()
        .put("marker", REPORT_RECEIPT_MARKER)
        .put("report_id", report.payload.reportId)
        .put("persistence_marker", "123e4567-e89b-42d3-a456-426614174012")
        .put("payload_sha256", report.payload.payloadSha256)
        .put("payload_bytes", report.payload.payloadBytes)

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

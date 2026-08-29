package kr.co.hanium.dreamup.walksafe.network

import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.report.UserReportContentCategory
import kr.co.hanium.dreamup.walksafe.report.UserReportCorrectionIntent
import kr.co.hanium.dreamup.walksafe.report.UserReportCorrectionPatch
import kr.co.hanium.dreamup.walksafe.report.UserReportDeletionState
import kr.co.hanium.dreamup.walksafe.report.UserReportRequestIntent
import kr.co.hanium.dreamup.walksafe.report.UserReportRequestType
import kr.co.hanium.dreamup.walksafe.report.UserReportStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidUserReportClientNetworkTest {
    @Test
    fun listAndCreateUseOnlyExactGatewayApiPathsCookieNoStoreAndExactBody() {
        CapturingServer(
            responses = listOf(
                LIST_RESPONSE,
                DETAIL_RESPONSE,
                REQUEST_RESPONSE,
            ),
        ).use { server ->
            val session = session(server.baseUrl)
            val client = AndroidUserReportClient()

            val page = client.listReportsCall(
                session = session,
                limit = 25,
                cursor = "cursor_1",
                userStatus = UserReportStatus.REJECTED,
            ).execute()
            val detail = client.reportDetailCall(session, REPORT_ID).execute()
            val created = client.createRequestCall(
                session = session,
                intent = UserReportRequestIntent(
                    clientRequestId = CLIENT_REQUEST_ID,
                    reportId = REPORT_ID,
                    requestType = UserReportRequestType.DELETE,
                    requestText = REQUEST_TEXT,
                ),
            ).execute()
            assertTrue(server.awaitRequests())

            assertEquals(REPORT_ID, page.items.single().reportId)
            assertEquals(REPORT_ID, detail.reportId)
            assertEquals(UserReportRequestType.DELETE, created.requestType)
            val listRequest = server.requests[0]
            assertEquals(
                "GET /api/reports/mine?limit=25&cursor=cursor_1&user_status=REJECTED HTTP/1.1",
                listRequest.startLine,
            )
            assertEquals(COOKIE, listRequest.headers["cookie"])
            assertEquals("no-store", listRequest.headers["cache-control"])
            val detailRequest = server.requests[1]
            assertEquals(
                "GET /api/reports/mine/$REPORT_ID HTTP/1.1",
                detailRequest.startLine,
            )
            assertEquals(COOKIE, detailRequest.headers["cookie"])
            val createRequest = server.requests[2]
            assertEquals(
                "POST /api/reports/mine/$REPORT_ID/requests HTTP/1.1",
                createRequest.startLine,
            )
            assertEquals(COOKIE, createRequest.headers["cookie"])
            val body = JSONObject(createRequest.body)
            assertEquals(
                setOf("client_request_id", "request_type", "request_text"),
                body.keys().asSequence().toSet(),
            )
            assertEquals(CLIENT_REQUEST_ID, body.getString("client_request_id"))
            assertEquals("DELETE", body.getString("request_type"))
            assertEquals(REQUEST_TEXT, body.getString("request_text"))
            assertFalse(server.requests.any { it.startLine.contains(" 127.0.0.1:8000") })
        }
    }

    @Test
    fun responseIsBoundedBeforeJsonParsing() {
        val releaseServer = CountDownLatch(1)
        LocalHttpTestServer { _, socket ->
            socket.getOutputStream().apply {
                write(
                    "HTTP/1.1 200 Test\r\nContent-Type: application/json\r\nContent-Length: ${USER_REPORT_MAX_RESPONSE_BYTES + 1}\r\n\r\n"
                        .toByteArray(Charsets.US_ASCII),
                )
                flush()
            }
            releaseServer.await(3, TimeUnit.SECONDS)
        }.use { server ->
            try {
                assertThrows(NetworkResponseTooLargeException::class.java) {
                    AndroidUserReportClient().listReportsCall(
                        session(server.baseUrl),
                        25,
                        null,
                        null,
                    ).execute()
                }
            } finally {
                releaseServer.countDown()
            }
        }
    }

    @Test
    fun structuredCorrectionPreservesOmissionAndExplicitNullAndDeletionUsesTrackedPath() {
        CapturingServer(
            responses = listOf(
                CONTENT_RESPONSE,
                CORRECTION_CATEGORY_RESPONSE,
                CORRECTION_CLEAR_RESPONSE,
                DELETION_RESPONSE,
            ),
        ).use { server ->
            val session = session(server.baseUrl)
            val client = AndroidUserReportClient()

            val content = client.reportContentCall(session, REPORT_ID).execute()
            val categoryRevision = client.correctReportContentCall(
                session = session,
                intent = UserReportCorrectionIntent(
                    reportId = REPORT_ID,
                    expectedRevision = 1,
                    idempotencyKey = CORRECTION_ID,
                    userDescription = UserReportCorrectionPatch.Omitted,
                    categoryHint = UserReportCorrectionPatch.Value(
                        UserReportContentCategory.ROAD_DAMAGE,
                    ),
                ),
            ).execute()
            val clearRevision = client.correctReportContentCall(
                session = session,
                intent = UserReportCorrectionIntent(
                    reportId = REPORT_ID,
                    expectedRevision = 2,
                    idempotencyKey = SECOND_CORRECTION_ID,
                    userDescription = UserReportCorrectionPatch.Value("  cafe\u0301   파손  "),
                    categoryHint = UserReportCorrectionPatch.Clear,
                ),
            ).execute()
            val deletion = client.reportDeletionStatusCall(session, REQUEST_ID).execute()
            assertTrue(server.awaitRequests())

            assertEquals(1L, content.revision)
            assertEquals(2L, categoryRevision.revision)
            assertNull(clearRevision.categoryHint)
            assertEquals(UserReportDeletionState.DELETED, deletion.state)
            assertEquals(
                "GET /api/reports/mine/$REPORT_ID/content HTTP/1.1",
                server.requests[0].startLine,
            )
            val categoryRequest = server.requests[1]
            assertEquals(
                "POST /api/reports/mine/$REPORT_ID/corrections HTTP/1.1",
                categoryRequest.startLine,
            )
            val categoryBody = JSONObject(categoryRequest.body)
            assertEquals(
                setOf("expected_revision", "idempotency_key", "category_hint"),
                categoryBody.keys().asSequence().toSet(),
            )
            assertFalse(categoryBody.has("user_description"))
            assertEquals(1L, categoryBody.getLong("expected_revision"))
            assertEquals(CORRECTION_ID, categoryBody.getString("idempotency_key"))
            assertEquals("ROAD_DAMAGE", categoryBody.getString("category_hint"))
            val clearBody = JSONObject(server.requests[2].body)
            assertEquals(
                setOf(
                    "expected_revision",
                    "idempotency_key",
                    "user_description",
                    "category_hint",
                ),
                clearBody.keys().asSequence().toSet(),
            )
            assertEquals(2L, clearBody.getLong("expected_revision"))
            assertEquals(SECOND_CORRECTION_ID, clearBody.getString("idempotency_key"))
            assertEquals("café 파손", clearBody.getString("user_description"))
            assertTrue(clearBody.isNull("category_hint"))
            assertEquals(
                "GET /api/reports/mine/deletions/$REQUEST_ID HTTP/1.1",
                server.requests[3].startLine,
            )
            server.requests.forEach { request ->
                assertEquals(COOKIE, request.headers["cookie"])
                assertEquals("no-store", request.headers["cache-control"])
                assertFalse(request.headers.keys.any { it.contains("consent") })
                assertFalse(request.headers.keys.any { it.contains("audit") })
            }
        }
    }

    @Test
    fun representativeUserReportCallsRejectLegacySessionBeforeNetworkUse() {
        val legacy = GatewayFieldSession.verified(
            gatewayBaseUrl = "https://gateway.example.test",
            actorId = "report-user",
            cookiePair = "walksafe_field_session=legacy.report.user.session",
            expiresAtEpochMs = Long.MAX_VALUE,
        )
        val client = AndroidUserReportClient()

        assertThrows(IllegalArgumentException::class.java) {
            client.listReportsCall(legacy, 25, null, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.reportContentCall(legacy, REPORT_ID)
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.reportDeletionStatusCall(legacy, REQUEST_ID)
        }
    }

    @Test
    fun responseIdentifiersMustEchoTheRequestedContentCorrectionAndDeletionBinding() {
        CapturingServer(
            responses = listOf(
                CONTENT_RESPONSE.replace(REPORT_ID, OTHER_REPORT_ID),
                CORRECTION_CATEGORY_RESPONSE.replace(CORRECTION_ID, SECOND_CORRECTION_ID),
                DELETION_RESPONSE.replace(REQUEST_ID, OTHER_REQUEST_ID),
            ),
        ).use { server ->
            val session = session(server.baseUrl)
            val client = AndroidUserReportClient()

            assertThrows(UserReportProtocolException::class.java) {
                client.reportContentCall(session, REPORT_ID).execute()
            }
            assertThrows(UserReportProtocolException::class.java) {
                client.correctReportContentCall(
                    session,
                    UserReportCorrectionIntent(
                        reportId = REPORT_ID,
                        expectedRevision = 1,
                        idempotencyKey = CORRECTION_ID,
                        categoryHint = UserReportCorrectionPatch.Value(
                            UserReportContentCategory.ROAD_DAMAGE,
                        ),
                    ),
                ).execute()
            }
            assertThrows(UserReportProtocolException::class.java) {
                client.reportDeletionStatusCall(session, REQUEST_ID).execute()
            }
            assertTrue(server.awaitRequests())
        }
    }

    private fun session(baseUrl: String): GatewayFieldSession =
        GatewayFieldSession.backendAccountDeviceSession(
            gatewayBaseUrl = baseUrl,
            binding = BackendAccountDeviceCookieBinding(
                actorId = ACTOR_ID,
                accountGeneration = ACCOUNT_GENERATION,
                authEpoch = AUTH_EPOCH,
                deviceId = DEVICE_ID,
                sessionId = SESSION_ID,
                expiresAtEpochMs = EXPIRES_AT_EPOCH_MS,
            ),
            cookiePair = COOKIE,
            expiresAtEpochMs = EXPIRES_AT_EPOCH_MS,
        )

    private data class CapturedRequest(
        val startLine: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private class CapturingServer(
        private val responses: List<String>,
    ) : AutoCloseable {
        private val serverSocket = ServerSocket(0)
        private val complete = CountDownLatch(responses.size)
        private val thread = Thread({ acceptAll() }, "user-report-http-test").apply {
            isDaemon = true
            start()
        }
        val baseUrl = "http://127.0.0.1:${serverSocket.localPort}"
        val requests = mutableListOf<CapturedRequest>()

        fun awaitRequests(): Boolean = complete.await(2, TimeUnit.SECONDS)

        override fun close() {
            serverSocket.close()
            thread.join(1_000)
        }

        private fun acceptAll() {
            responses.forEach { body ->
                val socket = runCatching { serverSocket.accept() }.getOrNull() ?: return
                socket.use {
                    requests += readRequest(it)
                    it.writeFixedResponse(200, body.toByteArray(Charsets.UTF_8))
                }
                complete.countDown()
            }
        }

        private fun readRequest(socket: Socket): CapturedRequest {
            val input = socket.getInputStream()
            val rawHeaders = ByteArrayOutputStream()
            var matched = 0
            val terminator = byteArrayOf(13, 10, 13, 10)
            while (matched < terminator.size) {
                val next = input.read()
                check(next >= 0)
                rawHeaders.write(next)
                matched = if (next.toByte() == terminator[matched]) matched + 1 else 0
            }
            val lines = rawHeaders.toString(Charsets.US_ASCII.name())
                .split("\r\n")
            val headers = lines.drop(1)
                .filter(String::isNotBlank)
                .associate { line ->
                    line.substringBefore(':').lowercase() to line.substringAfter(':').trim()
                }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(body, offset, length - offset)
                check(read >= 0)
                offset += read
            }
            return CapturedRequest(
                startLine = lines.first(),
                headers = headers,
                body = body.toString(Charsets.UTF_8),
            )
        }
    }

    private companion object {
        const val REPORT_ID = "44444444-4444-4444-8444-444444444444"
        const val REQUEST_ID = "55555555-5555-4555-8555-555555555555"
        const val OTHER_REQUEST_ID = "55555555-5555-4555-8555-555555555556"
        const val OTHER_REPORT_ID = "44444444-4444-4444-8444-444444444445"
        const val CLIENT_REQUEST_ID = "66666666-6666-4666-8666-666666666666"
        const val CORRECTION_ID = "77777777-7777-4777-8777-777777777777"
        const val SECOND_CORRECTION_ID = "88888888-8888-4888-8888-888888888888"
        const val TIMESTAMP = "2026-08-29T01:02:03.000000Z"
        const val REQUEST_TEXT = "이 신고만 삭제해 주세요."
        const val SHA256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val ACTOR_ID = "123e4567-e89b-42d3-a456-426614174000"
        const val DEVICE_ID = "android-report-device"
        const val ACCOUNT_GENERATION = 7L
        const val AUTH_EPOCH = 3L
        val EXPIRES_AT_EPOCH_SECONDS = System.currentTimeMillis() / 1_000L + 3_600L
        val EXPIRES_AT_EPOCH_MS = EXPIRES_AT_EPOCH_SECONDS * 1_000L
        val SESSION_ID = "i".repeat(43)
        val COOKIE = "walksafe_field_session=" + listOf(
            "v7",
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ACTOR_ID.toByteArray(Charsets.UTF_8)),
            ACCOUNT_GENERATION.toString(),
            AUTH_EPOCH.toString(),
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(DEVICE_ID.toByteArray(Charsets.UTF_8)),
            "general",
            EXPIRES_AT_EPOCH_SECONDS.toString(),
            SESSION_ID,
            "s".repeat(43),
        ).joinToString(".")
        const val LIST_RESPONSE =
            """{"schema_version":"walksafe.user-report-list.v1","items":[{"report_id":"$REPORT_ID","created_at":"$TIMESTAMP","user_status":"RECEIVED","public_rejection_reason":null,"latest_request":null}],"next_cursor":null}"""
        const val DETAIL_RESPONSE =
            """{"schema_version":"walksafe.user-report-detail.v1","report_id":"$REPORT_ID","created_at":"$TIMESTAMP","user_status":"RECEIVED","public_rejection_reason":null,"latest_request":null}"""
        const val REQUEST_RESPONSE =
            """{"request_id":"$REQUEST_ID","request_type":"DELETE","status":"RECEIVED","status_version":1,"public_response":null,"created_at":"$TIMESTAMP","updated_at":"$TIMESTAMP"}"""
        const val CONTENT_RESPONSE =
            """{"schema_version":"walksafe.report-content-current.v1","report_id":"$REPORT_ID","revision":1,"content_sha256":"$SHA256","user_description":"기존 설명","category_hint":null,"corrected_at":"$TIMESTAMP"}"""
        const val CORRECTION_CATEGORY_RESPONSE =
            """{"schema_version":"walksafe.report-content-revision.v1","report_id":"$REPORT_ID","revision":2,"expected_revision":1,"idempotency_key":"$CORRECTION_ID","content_sha256":"$SHA256","user_description":"기존 설명","category_hint":"ROAD_DAMAGE","corrected_at":"$TIMESTAMP"}"""
        const val CORRECTION_CLEAR_RESPONSE =
            """{"schema_version":"walksafe.report-content-revision.v1","report_id":"$REPORT_ID","revision":3,"expected_revision":2,"idempotency_key":"$SECOND_CORRECTION_ID","content_sha256":"$SHA256","user_description":"café 파손","category_hint":null,"corrected_at":"$TIMESTAMP"}"""
        const val DELETION_RESPONSE =
            """{"schema_version":"walksafe.report-deletion-status.v1","request_id":"$REQUEST_ID","report_id":"$REPORT_ID","state":"DELETED","request_status_version":3,"external_copy_count":0,"updated_at":"$TIMESTAMP"}"""
    }
}

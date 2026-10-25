package kr.co.hanium.dreamup.walksafe.report

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Base64
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.concurrent.thread
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.network.ActiveNetworkTransport
import kr.co.hanium.dreamup.walksafe.network.AndroidNetworkTransferPolicy
import kr.co.hanium.dreamup.walksafe.network.BackendAccountDeviceCookieBinding
import kr.co.hanium.dreamup.walksafe.network.CONSENT_RECEIPT_HEADER
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkBinding
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkTransport
import kr.co.hanium.dreamup.walksafe.network.MobileNetworkPreference
import kr.co.hanium.dreamup.walksafe.network.writeFixedResponse
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.LocalAeadKeyProvider
import kr.co.hanium.dreamup.walksafe.security.VersionedLocalAead
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_POLICY_VERSION
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentConfirmation
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentItemVersions
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentSelections
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual Android transport/TCP and file queue; scripted replies are not a live Backend/Gateway. */
class AndroidReportPostcommitRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun unknownPostOutcomeIsRecoveredByStatusWithFreshAutomaticOptOutWithoutRepeatingPost() {
        val fixture = fixture()
        val original = fixture.report.payload
        val originalDiskBytes = diskBytes(fixture.root)
        PostcommitHttpServer { index ->
            when (index) {
                0 -> 404 to """{"detail":{"code":"report_transport_status_not_found"}}"""
                1 -> 409 to """{"detail":{"code":"report_transport_outcome_unknown","retry":"status_query_before_retry"}}"""
                2 -> 200 to persistedStatus(fixture.report)
                else -> 500 to "{}"
            }
        }.use { server ->
            ReportPrivacyConsentSession.resetForNewEnrollment()
            assertTrue(ReportPrivacyConsentSession.grantFromServerConfirmedIntegratedConsent())
            try {
                val opened = AtomicInteger()
                val binding = binding(server, opened)
                var current = context()
                val coordinator = ReportQueueDrainCoordinator(fixture.store)
                val first = requireNotNull(coordinator.startNextWithResult(
                    { current }, transport(binding, confirmation(automatic = true)),
                )).execute()
                assertEquals(ReportQueueDrainOutcome.RETRY_DEFERRED, first.outcome)
                assertNull(first.creationOutcome)
                assertFalse(first.walkInProgress)
                assertEquals(ReportQueuePriority.AUTOMATIC, first.reportPriority)
                assertEquals(2, opened.get())
                // The payload stays frozen; UNKNOWN persists retry state in a newly sealed envelope.
                val postUnknownDiskBytes = diskBytes(fixture.root)
                assertEquals(originalDiskBytes.keys, postUnknownDiskBytes.keys)
                assertNotEquals(originalDiskBytes, postUnknownDiskBytes)
                val reopened = fixture.reopen()
                val retryAt = reopened.queuedReports().single().use { stored ->
                    assertFrozenRecoveryIdentity(fixture.report, stored)
                    assertEquals(1, stored.retryAttempts)
                    assertEquals(3_000L, stored.retryNotBeforeEpochMs)
                    assertFalse(stored.quarantined)
                    stored.retryNotBeforeEpochMs
                }
                assertEquals(retryAt, reopened.nextRecoveryRetryEpochMs(ACTOR, ACCOUNT_GENERATION))
                assertMultipartIsFrozen(server.requests[1], original)

                val blocked = ReportQueueDrainCoordinator(reopened)
                assertNull(blocked.startNextWithResult({ current }, transport(binding, confirmation(automatic = true))))
                assertEquals(2, opened.get())
                assertEquals(postUnknownDiskBytes, diskBytes(fixture.root))

                // Reauthorization keeps automatic OFF and does not bypass the persisted deadline.
                assertTrue(ReportPrivacyConsentSession.withdraw())
                assertTrue(ReportPrivacyConsentSession.grantFromServerConfirmedIntegratedConsent())
                val fresh = confirmation(automatic = false, revision = 2L, receipt = FRESH_RECEIPT)
                assertFalse(fresh.selections.automaticReporting)
                current = current.copy(automaticReportingAllowed = false, consentReceiptSha256 = FRESH_RECEIPT)
                fixture.nowMillis = retryAt - 1L
                assertNull(blocked.startNextWithResult({ current }, transport(binding, fresh)))
                assertEquals(2, opened.get())
                assertEquals(postUnknownDiskBytes, diskBytes(fixture.root))
                fixture.reopen().queuedReports().single().use { stored ->
                    assertFrozenRecoveryIdentity(fixture.report, stored)
                    assertEquals(1, stored.retryAttempts)
                    assertEquals(retryAt, stored.retryNotBeforeEpochMs)
                }
                fixture.nowMillis = retryAt

                // Missing current consent/authority or another account generation opens no socket.
                listOf(
                    current.copy(consentAllowed = false, automaticReportingAllowed = false),
                    current.copy(authorityAllowed = false, automaticReportingAllowed = false),
                    current.copy(accountGeneration = ACCOUNT_GENERATION + 1, automaticReportingAllowed = false),
                ).forEach { denied ->
                    assertNull(blocked.startNext({ denied }, transport(binding, fresh)))
                    assertEquals(2, opened.get())
                    assertEquals(postUnknownDiskBytes, diskBytes(fixture.root))
                }
                val cellularAllowed = AndroidNetworkTransferPolicy.isAllowed(
                    MobileNetworkPreference.WIFI_ONLY, ActiveNetworkTransport.CELLULAR,
                )
                assertFalse(cellularAllowed)
                assertNull(blocked.startNext(
                    { current.copy(networkAllowed = cellularAllowed, automaticReportingAllowed = false) },
                    transport(binding(server, opened, IntegratedConsentNetworkTransport.CELLULAR),
                        fresh),
                ))
                assertEquals(2, opened.get())
                assertEquals(postUnknownDiskBytes, diskBytes(fixture.root))

                // A fresh server confirmation may keep automatic OFF. GET must not turn it back on.
                var localReceiptCommits = 0
                val recovered = requireNotNull(blocked.startNextWithResult(
                    { current }, transport(binding, fresh),
                    beforeDeleteAfterReceipt = { stored ->
                        assertFrozenRecoveryIdentity(fixture.report, stored)
                        assertEquals(1, stored.retryAttempts)
                        assertEquals(retryAt, stored.retryNotBeforeEpochMs)
                        localReceiptCommits++
                        true
                    },
                )).execute()
                assertEquals(ReportQueueDrainOutcome.DELETED_AFTER_STATUS, recovered.outcome)
                assertEquals(1, localReceiptCommits)
                assertTrue(fixture.reopen().queuedReports().isEmpty())
                assertEquals(3, opened.get())
                assertEquals(listOf("GET", "POST", "GET"), server.requests.map { it.method })
                assertEquals(1, server.requests.count { it.method == "POST" })
                assertEquals(FRESH_RECEIPT, server.requests.last().headers[CONSENT_RECEIPT_HEADER])
                assertFalse(current.automaticReportingAllowed)
                assertEquals(ACCOUNT_GENERATION, current.accountGeneration)
                assertTransportIdentity(server.requests, original)
                server.assertHealthy()
            } finally { ReportPrivacyConsentSession.resetForNewEnrollment() }
        }
    }

    @Test
    fun automaticOptOutStatus404CreatesNoPostAndPreservesEncryptedFileAndPayloadAfterReopen() {
        val fixture = fixture()
        val original = fixture.report.payload
        val originalDiskBytes = diskBytes(fixture.root)
        PostcommitHttpServer { 404 to "{}" }.use { server ->
            ReportPrivacyConsentSession.resetForNewEnrollment()
            assertTrue(ReportPrivacyConsentSession.grantFromServerConfirmedIntegratedConsent())
            try {
                val opened = AtomicInteger()
                val result = requireNotNull(ReportQueueDrainCoordinator(fixture.store).startNextWithResult(
                    { context().copy(automaticReportingAllowed = false, consentReceiptSha256 = FRESH_RECEIPT) },
                    transport(binding(server, opened), confirmation(automatic = false, revision = 2L, receipt = FRESH_RECEIPT)),
                )).execute()
                assertEquals(ReportQueueDrainOutcome.RETRY_DEFERRED, result.outcome)
                assertEquals(1, opened.get())
                assertEquals(listOf("GET"), server.requests.map { it.method })
                assertEquals(originalDiskBytes, diskBytes(fixture.root))
                val stored = fixture.reopen().queuedReports().single()
                assertEquals(original.reportId, stored.payload.reportId)
                assertTrue(original.contentEquals(stored.payload))
                assertTransportIdentity(server.requests, original)
                server.assertHealthy()
            } finally { ReportPrivacyConsentSession.resetForNewEnrollment() }
        }
    }

    private fun fixture(): Fixture {
        assumeTrue("Actual queued transport requires the existing enabled DEBUG queue profile",
            BuildConfig.DEBUG && BuildConfig.WALKSAFE_REPORT_QUEUE_ENABLED)
        return Fixture(temporary.newFolder())
    }

    private class Fixture(val root: File) {
        var nowMillis = 1_000L
        private val aead = VersionedLocalAead(AeadKeyPolicy("test.report.postcommit.aead.v", 1, setOf(1)),
            PostcommitKeys())
        fun reopen() = AndroidReportQueueStore(
            rootDirectory = root,
            capacityProfile = ApprovedReportQueueCapacityProfile(8, 1_024, 4_096L, 32_768L, 6, 24_576L),
            aead = aead,
            idFactory = { UUID.fromString(REPORT_ID) },
            nowMillis = { nowMillis },
        )
        val store = reopen()
        val report = requireNotNull(store.enqueue(
            expectedReporterActorId = ACTOR,
            metadataUtf8 = """{"reporter_user_id":"$ACTOR","trigger":"auto","auto_reported":true}""".toByteArray(),
            imageJpeg = reportTestJpeg(),
            priority = ReportQueuePriority.AUTOMATIC,
            walkSessionId = WALK,
            consentReceiptSha256 = ORIGINAL_RECEIPT,
            accountGeneration = ACCOUNT_GENERATION,
        ))
    }

    private fun binding(
        server: PostcommitHttpServer,
        opened: AtomicInteger,
        network: IntegratedConsentNetworkTransport = IntegratedConsentNetworkTransport.WIFI,
    ) = IntegratedConsentNetworkBinding.forTest(network) { approvedUrl ->
            opened.incrementAndGet()
            // Preserve the actual endpoint path while routing the approved test origin to local TCP.
            URL(server.baseUrl + approvedUrl.file).openConnection()
        }

    private fun transport(binding: IntegratedConsentNetworkBinding, confirmed: IntegratedConsentConfirmation) =
        AndroidReportQueueTransport(AndroidReportUploader(), session(), confirmed, binding,
            ReportPrivacyConsentSession::issueUploadPermit)

    private fun session(): GatewayFieldSession {
        val origin = requireNotNull(approvedReportQueueGatewayOriginOrNull(BuildConfig.WALKSAFE_REPORT_QUEUE_TEST_ORIGIN))
        return GatewayFieldSession.backendAccountDeviceSession(origin,
            BackendAccountDeviceCookieBinding(ACTOR, ACCOUNT_GENERATION, 1L, "device-postcommit", WALK, Long.MAX_VALUE),
            "walksafe_field_session=v7.test.postcommit.cookie", Long.MAX_VALUE)
    }

    private fun context() = ReportQueueDrainContext(
        WalkSessionState.ENDED, true, true, true, true, true, true,
        ACTOR, WALK, ORIGINAL_RECEIPT, 1L, ACCOUNT_GENERATION, 7L,
    )

    private fun confirmation(automatic: Boolean, revision: Long = 1L, receipt: String = ORIGINAL_RECEIPT) =
        IntegratedConsentConfirmation(INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION, INTEGRATED_CONSENT_POLICY_VERSION,
            "501e3ad4-e74f-4433-820f-72ac2fdd42ad", "integrated_consent_request_0001", IntegratedConsentItemVersions(),
            revision, revision, IntegratedConsentSelections(automaticReporting = automatic),
            "2026-07-25T12:00:00.000Z", "9".repeat(64), receipt, "b".repeat(64))

    private fun persistedStatus(report: QueuedReport) = JSONObject()
        .put("persistence_state", "PERSISTED").put("user_status", "RECEIVED")
        .put("transport_receipt", JSONObject().put("marker", REPORT_RECEIPT_MARKER)
            .put("report_id", report.payload.reportId).put("payload_sha256", report.payload.payloadSha256)
            .put("payload_bytes", report.payload.payloadBytes).put("persistence_marker", PERSISTENCE_MARKER))
        .toString()

    private fun assertTransportIdentity(requests: List<PostcommitRequest>, payload: FrozenReportPayload) {
        requests.forEach { request ->
            assertEquals(payload.reportId, request.headers[REPORT_ID_HEADER])
            assertEquals(payload.payloadSha256, request.headers[REPORT_PAYLOAD_SHA256_HEADER])
            assertEquals(payload.payloadBytes.toString(), request.headers[REPORT_PAYLOAD_BYTES_HEADER])
            assertEquals("automatic", request.headers[REPORT_PURPOSE_HEADER])
            assertEquals(if (request.method == "GET") "/api/reports/v2/${payload.reportId}/status" else "/api/reports/v2", request.path)
        }
    }

    private fun assertMultipartIsFrozen(request: PostcommitRequest, payload: FrozenReportPayload) {
        assertEquals("POST", request.method)
        val boundary = request.headers.getValue("content-type").substringAfter("boundary=")
        val body = request.body.toString(Charsets.ISO_8859_1)
        fun part(type: String) = body.substringAfter("Content-Type: $type\r\n\r\n")
            .substringBefore("\r\n--$boundary").toByteArray(Charsets.ISO_8859_1)
        assertArrayEquals(payload.metadataUtf8(), part("application/json"))
        assertArrayEquals(payload.imageJpeg(), part("image/jpeg"))
    }

    private fun assertFrozenRecoveryIdentity(original: QueuedReport, stored: QueuedReport) {
        assertEquals(original.payload.reportId, stored.payload.reportId)
        assertEquals(original.payload.payloadSha256, stored.payload.payloadSha256)
        assertEquals(original.payload.payloadBytes, stored.payload.payloadBytes)
        original.payload.useExactBytes { metadata, image ->
            stored.payload.useExactBytes { recoveredMetadata, recoveredImage ->
                assertArrayEquals(metadata, recoveredMetadata)
                assertArrayEquals(image, recoveredImage)
            }
        }
        assertEquals(original.copy(payload = stored.payload,
            retryAttempts = stored.retryAttempts, retryNotBeforeEpochMs = stored.retryNotBeforeEpochMs), stored)
    }

    private fun diskBytes(root: File) = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).path to Base64.getEncoder().encodeToString(it.readBytes())
    }

    private companion object {
        const val ACTOR = "walker-1"
        const val ACCOUNT_GENERATION = 7L
        const val REPORT_ID = "123e4567-e89b-42d3-a456-426614174000"
        const val WALK = "123e4567-e89b-42d3-a456-426614174001"
        const val PERSISTENCE_MARKER = "123e4567-e89b-42d3-a456-426614174002"
        val ORIGINAL_RECEIPT = "c".repeat(64)
        val FRESH_RECEIPT = "d".repeat(64)
    }
}

private data class PostcommitRequest(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

/** Same fixed-body TCP response pattern as LocalHttpTestServer, retaining request bytes for assertions. */
private class PostcommitHttpServer(private val response: (Int) -> Pair<Int, String>) : Closeable {
    private val server = ServerSocket(0)
    val baseUrl = "http://127.0.0.1:${server.localPort}"
    val requests: MutableList<PostcommitRequest> = Collections.synchronizedList(mutableListOf())
    private val failure = AtomicReference<Throwable?>()
    private val worker = thread(name = "report-postcommit-test-tcp", isDaemon = true) {
        try {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    it.soTimeout = 5_000
                    val request = readRequest(it)
                    val index = requests.size
                    requests.add(request)
                    val (status, body) = response(index)
                    it.writeFixedResponse(status, body.toByteArray(Charsets.UTF_8))
                }
            }
        } catch (error: Throwable) { if (!server.isClosed) failure.set(error) }
    }
    fun assertHealthy() { failure.get()?.let { throw AssertionError("TCP fixture failed", it) } }
    override fun close() { server.close(); worker.join(1_000L); assertHealthy() }

    private fun readRequest(socket: Socket): PostcommitRequest {
        val input = socket.getInputStream()
        fun line(): String {
            val result = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                check(next >= 0) { "Unexpected request EOF" }
                if (next == '\r'.code) { check(input.read() == '\n'.code); return result.toString(Charsets.US_ASCII.name()) }
                result.write(next)
                check(result.size() <= 64 * 1_024)
            }
        }
        val start = line().split(' ')
        val headers = mutableMapOf<String, String>()
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            headers[header.substringBefore(':').lowercase(java.util.Locale.ROOT)] = header.substringAfter(':').trim()
        }
        check(!headers.containsKey("transfer-encoding")) { "Uploader fixture expects its fixed-length multipart" }
        val length = headers["content-length"]?.toInt() ?: 0
        check(length in 0..(128 * 1_024))
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(body, offset, length - offset)
            check(n > 0)
            offset += n
        }
        return PostcommitRequest(start[0], start[1], headers, body)
    }
}

private class PostcommitKeys : LocalAeadKeyProvider {
    private val keys = mutableMapOf<String, SecretKey>()
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    override fun getOrCreate(alias: String) = keys.getOrPut(alias, ::key)
    override fun getExisting(alias: String) = keys[alias]
    override fun delete(alias: String): Boolean { keys.remove(alias); return true }
    override fun createFreshAfterVerifiedPurge(alias: String) = key().also { keys[alias] = it }
}

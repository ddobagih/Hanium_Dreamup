package kr.co.hanium.dreamup.walksafe.rawcollection

import java.net.ServerSocket
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

/** Native HTTP against phone loopback only; synthetic authority is never sent to a real server. */
class RawHttpFailureDeviceTest {
    @Test fun nativeHttpErrorsPreserveRetryMetadataWithoutExceptionDisclosure() {
        val cases = listOf(401 to null, 403 to null, 409 to null, 429 to "60",
            503 to "Wed, 21 Oct 2015 07:28:00 GMT", 503 to "not-a-delay")
        for ((status, retryAfter) in cases) {
            withResponse(status, "synthetic-private-error", retryAfter) { base ->
                val error = assertThrows(RawCollectionHttpException::class.java) { execute(base) }
                assertEquals(status, error.statusCode)
                assertEquals(retryAfter, error.retryAfterHeader)
                assertEquals("synthetic-private-error", error.errorBody)
                assertFalse(error.toString().contains("synthetic-private-error"))
                assertEquals(RawCollectionHttpException(status).message, error.message)
            }
            report("RAW_NATIVE_HTTP status=$status header_preserved=true exception_body_hidden=true loopback_only=true")
        }
    }

    @Test fun malformedSuccessIsNotAcceptedAsUploadReceipt() {
        withResponse(201, "{}", null) { base ->
            assertThrows(RawCollectionProtocolException::class.java) { execute(base) }
        }
        report("RAW_NATIVE_HTTP malformed_success=rejected no_commit=true loopback_only=true")
    }

    @Test fun excessiveErrorBodyIsBoundedBeforeItsContentEscapes() {
        withResponse(429, "x".repeat(512 * 1_024 + 1), "60", allowEarlyDisconnect = true) { base ->
            assertThrows(NetworkResponseTooLargeException::class.java) { execute(base) }
        }
        report("RAW_NATIVE_HTTP oversized_error=rejected loopback_only=true")
    }

    private fun execute(base: String): RawCollectionReceipt {
        val local = localManifest()
        return AndroidRawCollectionClient().uploadCall(v7Session(base), confirmation(), wifiBinding(),
            local, checkNotNull(local.toBackendManifest()), plaintextChunks(local), { true }).execute()
    }

    private fun withResponse(status: Int, body: String, retry: String?, allowEarlyDisconnect: Boolean = false, action: (String) -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 10_000
                val served = executor.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val input = socket.getInputStream()
                        val head = ByteArrayOutputStream()
                        while (!head.toString("US-ASCII").endsWith("\r\n\r\n")) {
                            val value = input.read()
                            check(value >= 0 && head.size() < 16_384)
                            head.write(value)
                        }
                        val headers = head.toString("US-ASCII")
                        val length = headers.lineSequence().first { it.startsWith("Content-Length:", true) }
                            .substringAfter(':').trim().toInt()
                        check(length in 1..1_000_000)
                        repeat(length) { check(input.read() >= 0) }
                        val bytes = body.toByteArray()
                        socket.getOutputStream().apply {
                            write(("HTTP/1.1 $status Test\r\nContent-Type: application/json\r\n" +
                                (retry?.let { "Retry-After: $it\r\n" } ?: "") +
                                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                            try { write(bytes); flush() }
                            catch (closed: java.net.SocketException) { if (!allowEarlyDisconnect) throw closed }
                        }
                    }
                }
                action("http://127.0.0.1:${server.localPort}")
                served.get(15, TimeUnit.SECONDS)
            }
        } finally { executor.shutdownNow() }
    }
    private fun report(message: String) = InstrumentationRegistry.getInstrumentation()
        .sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
    private fun sha256(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)
        .joinToString("") { "%02x".format(it) }

    private fun localManifest(): RawCollectionManifest {
        return RawCollectionManifest(
            collectionId = COLLECTION_ID,
            owner = RawCollectionOwner(ACTOR_ID, 1L, DEVICE_ID),
            walkSessionId = WALK_ID,
            consentReceiptSha256 = CONSENT_SHA,
            capturedStartedAtEpochMs = CAPTURE_STARTED_AT,
            capturedEndedAtEpochMs = CAPTURE_STARTED_AT + 5_000L,
            expiresAtEpochMs = CAPTURE_STARTED_AT + RAW_COLLECTION_TTL_MS,
            state = RawManifestState.COMPLETE,
            chunks = listOf(
                RawChunkMetadata(
                    ordinal = 0,
                    type = RawChunkType.DETECTION,
                    capturedAtEpochMs = CAPTURE_STARTED_AT + 1_000L,
                    sizeBytes = DETECTION_PAYLOAD.size,
                    sha256 = sha256(DETECTION_PAYLOAD),
                ),
                RawChunkMetadata(
                    ordinal = 1,
                    type = RawChunkType.PERFORMANCE,
                    capturedAtEpochMs = CAPTURE_STARTED_AT + 1_000L,
                    sizeBytes = PERFORMANCE_PAYLOAD.size,
                    sha256 = sha256(PERFORMANCE_PAYLOAD),
                ),
            ),
        )
    }

    private fun plaintextChunks(local: RawCollectionManifest): List<RawPlaintextChunk> = listOf(
        RawPlaintextChunk(local.chunks[0], DETECTION_PAYLOAD.copyOf()),
        RawPlaintextChunk(local.chunks[1], PERFORMANCE_PAYLOAD.copyOf()),
    )

    private fun v7Session(gatewayBaseUrl: String): GatewayFieldSession {
        val expiresAt = System.currentTimeMillis() + 3_600_000L
        return GatewayFieldSession.backendAccountDeviceSession(
            gatewayBaseUrl = gatewayBaseUrl,
            binding = BackendAccountDeviceCookieBinding(
                actorId = ACTOR_ID,
                accountGeneration = 1L,
                authEpoch = 1L,
                deviceId = DEVICE_ID,
                sessionId = "s".repeat(32),
                expiresAtEpochMs = expiresAt,
            ),
            cookiePair = "${GatewayFieldSession.COOKIE_NAME}=test-v7-cookie",
            expiresAtEpochMs = expiresAt,
        )
    }

    private fun confirmation() = IntegratedConsentConfirmation(
        schemaVersion = INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,
        policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
        installationId = DEVICE_ID,
        requestId = "request_id_1234567890",
        itemVersions = IntegratedConsentItemVersions(),
        clientRevision = 1L,
        revision = 1L,
        selections = IntegratedConsentSelections(rawSourceCollection = true),
        confirmedAt = "2026-08-29T00:00:00.000Z",
        gatewayAuditRecordSha256 = "a".repeat(64),
        backendConsentReceiptSha256 = CONSENT_SHA,
        controlSecret = "b".repeat(64),
    )

    private fun wifiBinding(): IntegratedConsentNetworkBinding =
        IntegratedConsentNetworkBinding.forTest(IntegratedConsentNetworkTransport.WIFI) { url ->
            url.openConnection()
        }

    private companion object {
        const val COLLECTION_ID = "123e4567-e89b-42d3-a456-426614174000"
        const val WALK_ID = "123e4567-e89b-42d3-a456-426614174001"
        const val ACTOR_ID = "123e4567-e89b-42d3-a456-426614174002"
        const val DEVICE_ID = "device-installation-00000001"
        const val CAPTURE_STARTED_AT = 1_787_961_600_000L
        val CONSENT_SHA = "c".repeat(64)
        val DETECTION_PAYLOAD = "{\"aggregate\":true}".toByteArray()
        val PERFORMANCE_PAYLOAD = "{\"frame_count\":30}".toByteArray()
    }
}

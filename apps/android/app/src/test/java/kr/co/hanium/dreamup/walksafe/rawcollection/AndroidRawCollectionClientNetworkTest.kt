package kr.co.hanium.dreamup.walksafe.rawcollection

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.network.BackendAccountDeviceCookieBinding
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkBinding
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkTransport
import kr.co.hanium.dreamup.walksafe.network.LocalHttpTestServer
import kr.co.hanium.dreamup.walksafe.network.NetworkResponseTooLargeException
import kr.co.hanium.dreamup.walksafe.network.awaitPeerDisconnect
import kr.co.hanium.dreamup.walksafe.network.writeFixedResponse
import kr.co.hanium.dreamup.walksafe.network.writeStalledChunkedHeaders
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_POLICY_VERSION
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentConfirmation
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentItemVersions
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentSelections
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidRawCollectionClientNetworkTest {
    @Test
    fun uploadsManifestThenGetsStatusAndOnlyMissingChunkBeforeCommit() {
        val local = localManifest()
        val backend = requireNotNull(local.toBackendManifest())
        val observed = mutableListOf<Int>()
        LocalHttpTestServer { index, socket ->
            synchronized(observed) { observed += index }
            val response = when (index) {
                0, 1 -> statusJson(backend, received = false)
                2 -> chunkAckJson(backend)
                3 -> statusJson(backend, received = true)
                4 -> receiptJson(exactClientReceipt(backend))
                else -> error("unexpected raw request $index")
            }.toString().toByteArray(Charsets.UTF_8)
            socket.writeFixedResponse(
                statusCode = if (index == 0 || index == 2) 201 else 200,
                body = response,
                headers = mapOf("Cache-Control" to "no-store"),
            )
        }.use { server ->
            val plaintext = PAYLOAD.copyOf()
            val receipt = AndroidRawCollectionClient().uploadCall(
                session = v7Session(server.baseUrl),
                consent = confirmation(),
                networkBinding = wifiBinding(),
                localManifest = local,
                backendManifest = backend,
                chunk = RawPlaintextChunk(local.chunks.single(), plaintext),
                isCurrent = { true },
            ).execute()

            assertEquals(exactClientReceipt(backend), receipt)
            assertEquals(listOf(0, 1, 2, 3, 4), synchronized(observed) { observed.toList() })
        }
    }

    @Test
    fun refusesRedirectInsteadOfFollowingIt() {
        val local = localManifest()
        val backend = requireNotNull(local.toBackendManifest())
        LocalHttpTestServer { _, socket ->
            socket.writeFixedResponse(
                statusCode = 302,
                body = "{}".toByteArray(),
                headers = mapOf(
                    "Cache-Control" to "no-store",
                    "Location" to "http://127.0.0.1/redirected",
                ),
            )
        }.use { server ->
            val error = assertThrows(RawCollectionHttpException::class.java) {
                AndroidRawCollectionClient().uploadCall(
                    session = v7Session(server.baseUrl),
                    consent = confirmation(),
                    networkBinding = wifiBinding(),
                    localManifest = local,
                    backendManifest = backend,
                    chunk = RawPlaintextChunk(local.chunks.single(), PAYLOAD.copyOf()),
                    isCurrent = { true },
                ).execute()
            }
            assertEquals(302, error.statusCode)
        }
    }

    @Test
    fun responseSizeIsBoundedBeforeJsonParsing() {
        val releaseServer = CountDownLatch(1)
        val local = localManifest()
        val backend = requireNotNull(local.toBackendManifest())
        LocalHttpTestServer { _, socket ->
            socket.writeFixedResponse(
                statusCode = 201,
                body = byteArrayOf(),
                declaredLength = 512L * 1_024L + 1L,
                headers = mapOf("Cache-Control" to "no-store"),
            )
            releaseServer.await(3, TimeUnit.SECONDS)
        }.use { server ->
            try {
                assertThrows(NetworkResponseTooLargeException::class.java) {
                    AndroidRawCollectionClient().uploadCall(
                        session = v7Session(server.baseUrl),
                        consent = confirmation(),
                        networkBinding = wifiBinding(),
                        localManifest = local,
                        backendManifest = backend,
                        chunk = RawPlaintextChunk(local.chunks.single(), PAYLOAD.copyOf()),
                        isCurrent = { true },
                    ).execute()
                }
            } finally {
                releaseServer.countDown()
            }
        }
    }

    @Test
    fun cancellationDisconnectsAnActiveRawRequest() {
        val responseStarted = CountDownLatch(1)
        val local = localManifest()
        val backend = requireNotNull(local.toBackendManifest())
        LocalHttpTestServer { _, socket ->
            socket.writeStalledChunkedHeaders(statusCode = 201)
            responseStarted.countDown()
            socket.awaitPeerDisconnect(3_000)
        }.use { server ->
            val call = AndroidRawCollectionClient().uploadCall(
                session = v7Session(server.baseUrl),
                consent = confirmation(),
                networkBinding = wifiBinding(),
                localManifest = local,
                backendManifest = backend,
                chunk = RawPlaintextChunk(local.chunks.single(), PAYLOAD.copyOf()),
                isCurrent = { true },
            )
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<RawCollectionReceipt> { call.execute() }
                assertTrue(responseStarted.await(2, TimeUnit.SECONDS))
                call.cancel()
                val failure = assertThrows(ExecutionException::class.java) {
                    result.get(2, TimeUnit.SECONDS)
                }
                assertTrue(failure.cause is CancellationException)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun sourceKeepsFixedLengthBoundsCancellationAndExactHeaderMatrix() {
        val source = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/rawcollection/AndroidRawCollectionClient.kt",
        ).readText()
        listOf(
            "instanceFollowRedirects = false",
            "setFixedLengthStreamingMode(body.size)",
            "readBoundedResponse(RAW_RESPONSE_MAX_BYTES, cancellation)",
            "cancellation.attach(connection)",
            "cancellation.attach(output)",
            "CONSENT_INSTALLATION_HEADER",
            "CONSENT_CONTROL_SECRET_HEADER",
            "CONSENT_NETWORK_TRANSPORT_HEADER",
            "RAW_CHUNK_SHA256_HEADER",
            "RAW_COMMIT_SHA256_HEADER",
        ).forEach { required -> assertTrue(source.contains(required)) }
        assertTrue(source.contains("consent = null"))
        assertTrue(source.contains("method = \"GET\""))
    }

    private fun localManifest(): RawCollectionManifest {
        val sha = sha256(PAYLOAD)
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
                    sizeBytes = PAYLOAD.size,
                    sha256 = sha,
                ),
            ),
        )
    }

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
        confirmedAt = "2026-08-29T00:00:00Z",
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
        val PAYLOAD = "{\"aggregate\":true}".toByteArray()
    }
}

private fun statusJson(manifest: BackendRawManifest, received: Boolean): JSONObject {
    val objectValue = manifest.objects.single()
    val missing = if (received) JSONArray() else JSONArray().put(
        JSONObject().put("start", 0).put("end", 0),
    )
    return JSONObject()
        .put("schema_version", "walksafe.raw-collection-status.v1")
        .put("collection_id", manifest.collectionId)
        .put("manifest_sha256", manifest.manifestSha256)
        .put("purpose", BACKEND_RAW_PURPOSE)
        .put("state", if (received) "READY_TO_COMMIT" else "MANIFEST_ACCEPTED")
        .put("object_count", 1)
        .put("chunk_count", 1)
        .put("total_bytes", manifest.totalBytes)
        .put("received_chunk_count", if (received) 1 else 0)
        .put("received_bytes", if (received) manifest.totalBytes else 0L)
        .put(
            "objects",
            JSONArray().put(
                JSONObject()
                    .put("object_id", objectValue.objectId)
                    .put("kind", objectValue.kind)
                    .put("sha256", objectValue.sha256)
                    .put("chunk_count", 1)
                    .put("received_chunk_count", if (received) 1 else 0)
                    .put("size_bytes", objectValue.sizeBytes)
                    .put("received_bytes", if (received) objectValue.sizeBytes else 0L)
                    .put("missing_ranges", missing),
            ),
        )
        .put("receipt", JSONObject.NULL)
}

private fun chunkAckJson(manifest: BackendRawManifest): JSONObject {
    val binding = manifest.chunkBindings.single()
    return JSONObject()
        .put("schema_version", "walksafe.raw-collection-chunk-ack.v1")
        .put("collection_id", manifest.collectionId)
        .put("object_id", binding.objectId)
        .put("index", binding.chunkIndex)
        .put("size_bytes", binding.sizeBytes)
        .put("sha256", binding.sha256)
        .put("state", "READY_TO_COMMIT")
        .put("stored_at", "2026-08-29T00:00:06Z")
}

private fun exactClientReceipt(manifest: BackendRawManifest): RawCollectionReceipt {
    val unsigned = RawCollectionReceipt(
        schemaVersion = BACKEND_RECEIPT_SCHEMA,
        collectionId = manifest.collectionId,
        manifestSha256 = manifest.manifestSha256,
        purpose = BACKEND_RAW_PURPOSE,
        persistenceMarker = RAW_RECEIPT_PERSISTENCE_MARKER,
        objectCount = 1,
        chunkCount = 1,
        totalBytes = manifest.totalBytes,
        objects = manifest.objects,
        retentionClass = BACKEND_RETENTION_CLASS,
        committedAt = "2026-08-29T00:01:00Z",
        quarantineExpiresAt = "2026-09-12T00:01:00Z",
        receiptSha256 = "0".repeat(64),
    )
    return unsigned.copy(receiptSha256 = rawReceiptSha256(unsigned))
}

private fun receiptJson(receipt: RawCollectionReceipt): JSONObject = JSONObject()
    .put("schema_version", receipt.schemaVersion)
    .put("collection_id", receipt.collectionId)
    .put("manifest_sha256", receipt.manifestSha256)
    .put("purpose", receipt.purpose)
    .put("persistence_marker", receipt.persistenceMarker)
    .put("object_count", receipt.objectCount)
    .put("chunk_count", receipt.chunkCount)
    .put("total_bytes", receipt.totalBytes)
    .put(
        "objects",
        JSONArray().also { array ->
            receipt.objects.forEach { item ->
                array.put(
                    JSONObject()
                        .put("object_id", item.objectId)
                        .put("kind", item.kind)
                        .put("size_bytes", item.sizeBytes)
                        .put("sha256", item.sha256)
                        .put("chunk_count", item.chunkCount),
                )
            }
        },
    )
    .put("retention_class", receipt.retentionClass)
    .put("committed_at", receipt.committedAt)
    .put("quarantine_expires_at", receipt.quarantineExpiresAt)
    .put("receipt_sha256", receipt.receiptSha256)

private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

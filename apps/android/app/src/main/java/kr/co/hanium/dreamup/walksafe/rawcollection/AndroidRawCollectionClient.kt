package kr.co.hanium.dreamup.walksafe.rawcollection

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CancellationException
import kr.co.hanium.dreamup.walksafe.network.CONSENT_CONTROL_SECRET_HEADER
import kr.co.hanium.dreamup.walksafe.network.CONSENT_INSTALLATION_HEADER
import kr.co.hanium.dreamup.walksafe.network.CONSENT_NETWORK_TRANSPORT_HEADER
import kr.co.hanium.dreamup.walksafe.network.CONSENT_POLICY_HEADER
import kr.co.hanium.dreamup.walksafe.network.CONSENT_RECEIPT_HEADER
import kr.co.hanium.dreamup.walksafe.network.CONSENT_REVISION_HEADER
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionScope
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkBinding
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkTransport
import kr.co.hanium.dreamup.walksafe.network.cancellableHttpCall
import kr.co.hanium.dreamup.walksafe.network.readBoundedResponse
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_POLICY_VERSION
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentConfirmation
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentItem
import org.json.JSONObject

internal interface RawCollectionNetworkClient {
    fun uploadCall(
        session: GatewayFieldSession,
        consent: IntegratedConsentConfirmation,
        networkBinding: IntegratedConsentNetworkBinding,
        localManifest: RawCollectionManifest,
        backendManifest: BackendRawManifest,
        chunk: RawPlaintextChunk,
        isCurrent: () -> Boolean,
    ): CancellableNetworkCall<RawCollectionReceipt>
}

/** Strict Gateway client for the currently implemented one-object/one-chunk raw shape. */
internal class AndroidRawCollectionClient : RawCollectionNetworkClient {
    override fun uploadCall(
        session: GatewayFieldSession,
        consent: IntegratedConsentConfirmation,
        networkBinding: IntegratedConsentNetworkBinding,
        localManifest: RawCollectionManifest,
        backendManifest: BackendRawManifest,
        chunk: RawPlaintextChunk,
        isCurrent: () -> Boolean,
    ): CancellableNetworkCall<RawCollectionReceipt> {
        val binding = requireNotNull(backendManifest.chunkBindings.singleOrNull())
        requireValidUploadBinding(
            session,
            consent,
            networkBinding,
            localManifest,
            backendManifest,
            binding,
            chunk,
        )
        val commit = requireNotNull(backendManifest.commitPayload())
        require(backendManifest.jsonUtf8.size in 1..RAW_MANIFEST_REQUEST_MAX_BYTES)
        require(commit.jsonUtf8.size in 1..RAW_COMMIT_REQUEST_MAX_BYTES)

        return cancellableHttpCall { cancellation ->
            fun currentOrCancel() {
                cancellation.throwIfCancelled()
                if (!isCurrent()) throw CancellationException("raw collection binding changed")
            }

            currentOrCancel()
            requestJson(
                session = session,
                consent = consent,
                networkBinding = networkBinding,
                localManifest = localManifest,
                backendManifest = backendManifest,
                path = "/manifest",
                method = "PUT",
                body = backendManifest.jsonUtf8,
                operationHeaders = emptyMap(),
                acceptedStatusCodes = MANIFEST_SUCCESS_CODES,
                cancellation = cancellation,
                isCurrent = ::currentOrCancel,
            ).let { body ->
                parseStatus(body, backendManifest)
                    ?: throw RawCollectionProtocolException()
            }

            var status = requestStatus(
                session,
                networkBinding,
                localManifest,
                backendManifest,
                cancellation,
                ::currentOrCancel,
            )
            status.receipt?.let { return@cancellableHttpCall it }

            if (status.chunkMissing) {
                requestJson(
                    session = session,
                    consent = consent,
                    networkBinding = networkBinding,
                    localManifest = localManifest,
                    backendManifest = backendManifest,
                    path = "/objects/${binding.objectId}/chunks/${binding.chunkIndex}",
                    method = "PUT",
                    body = chunk.plaintext,
                    contentType = RAW_CHUNK_CONTENT_TYPE,
                    operationHeaders = mapOf(RAW_CHUNK_SHA256_HEADER to binding.sha256),
                    acceptedStatusCodes = CHUNK_SUCCESS_CODES,
                    cancellation = cancellation,
                    isCurrent = ::currentOrCancel,
                ).let { body ->
                    if (!validChunkAck(body, backendManifest, binding)) {
                        throw RawCollectionProtocolException()
                    }
                }
                status = requestStatus(
                    session,
                    networkBinding,
                    localManifest,
                    backendManifest,
                    cancellation,
                    ::currentOrCancel,
                )
                status.receipt?.let { return@cancellableHttpCall it }
            }
            if (!status.readyToCommit) throw RawCollectionProtocolException()

            val receipt = requestJson(
                session = session,
                consent = consent,
                networkBinding = networkBinding,
                localManifest = localManifest,
                backendManifest = backendManifest,
                path = "/commit",
                method = "POST",
                body = commit.jsonUtf8,
                operationHeaders = mapOf(RAW_COMMIT_SHA256_HEADER to commit.commitSha256),
                acceptedStatusCodes = COMMIT_SUCCESS_CODES,
                cancellation = cancellation,
                isCurrent = ::currentOrCancel,
            ).let { parseReceipt(it, backendManifest) }
                ?.takeIf { it.schemaVersion == BACKEND_RECEIPT_SCHEMA }
                ?: throw RawCollectionProtocolException()
            currentOrCancel()
            receipt
        }
    }

    private fun requestStatus(
        session: GatewayFieldSession,
        networkBinding: IntegratedConsentNetworkBinding,
        localManifest: RawCollectionManifest,
        backendManifest: BackendRawManifest,
        cancellation: kr.co.hanium.dreamup.walksafe.network.HttpConnectionCancellation,
        isCurrent: () -> Unit,
    ): ParsedRawStatus = requestJson(
        session = session,
        consent = null,
        networkBinding = networkBinding,
        localManifest = localManifest,
        backendManifest = backendManifest,
        path = "",
        method = "GET",
        body = null,
        operationHeaders = emptyMap(),
        acceptedStatusCodes = STATUS_SUCCESS_CODES,
        cancellation = cancellation,
        isCurrent = isCurrent,
    ).let { body ->
        parseStatus(body, backendManifest) ?: throw RawCollectionProtocolException()
    }

    private fun requestJson(
        session: GatewayFieldSession,
        consent: IntegratedConsentConfirmation?,
        networkBinding: IntegratedConsentNetworkBinding,
        localManifest: RawCollectionManifest,
        backendManifest: BackendRawManifest,
        path: String,
        method: String,
        body: ByteArray?,
        contentType: String = RAW_JSON_CONTENT_TYPE,
        operationHeaders: Map<String, String>,
        acceptedStatusCodes: Set<Int>,
        cancellation: kr.co.hanium.dreamup.walksafe.network.HttpConnectionCancellation,
        isCurrent: () -> Unit,
    ): String {
        isCurrent()
        val endpoint = session.gatewayBaseUrl.trimEnd('/') +
            "/api/raw-collections/${backendManifest.collectionId}$path"
        require(endpoint.startsWith(session.gatewayBaseUrl.trimEnd('/') + RAW_GATEWAY_PATH))
        val opened = networkBinding.openConnection(URL(endpoint))
        val connection = opened as? HttpURLConnection ?: throw RawCollectionProtocolException()
        cancellation.attach(connection)
        try {
            connection.apply {
                requestMethod = method
                connectTimeout = RAW_CONNECT_TIMEOUT_MS
                readTimeout = RAW_READ_TIMEOUT_MS
                doInput = true
                doOutput = body != null
                useCaches = false
                instanceFollowRedirects = false
                setRequestProperty("Accept", RAW_JSON_CONTENT_TYPE)
                setRequestProperty("Cache-Control", "no-store")
                setRequestProperty("Connection", "close")
                session.requestHeaders().forEach(::setRequestProperty)
                setRequestProperty(RAW_PURPOSE_HEADER, BACKEND_RAW_PURPOSE)
                setRequestProperty(RAW_WALK_ID_HEADER, localManifest.walkSessionId)
                setRequestProperty(RAW_MANIFEST_SHA256_HEADER, backendManifest.manifestSha256)
                operationHeaders.forEach(::setRequestProperty)
                if (body != null) {
                    val confirmed = requireNotNull(consent)
                    setRequestProperty("Content-Type", contentType)
                    setFixedLengthStreamingMode(body.size)
                    setRequestProperty(CONSENT_INSTALLATION_HEADER, confirmed.installationId)
                    setRequestProperty(CONSENT_POLICY_HEADER, confirmed.policyVersion)
                    setRequestProperty(CONSENT_REVISION_HEADER, confirmed.revision.toString())
                    setRequestProperty(
                        CONSENT_RECEIPT_HEADER,
                        confirmed.backendConsentReceiptSha256,
                    )
                    setRequestProperty(CONSENT_CONTROL_SECRET_HEADER, confirmed.controlSecret)
                    setRequestProperty(
                        CONSENT_NETWORK_TRANSPORT_HEADER,
                        IntegratedConsentNetworkTransport.WIFI.wireValue,
                    )
                }
            }
            if (body != null) {
                connection.outputStream.use { output ->
                    cancellation.attach(output)
                    try {
                        var offset = 0
                        while (offset < body.size) {
                            cancellation.throwIfCancelled()
                            val length = minOf(RAW_WRITE_BUFFER_BYTES, body.size - offset)
                            output.write(body, offset, length)
                            offset += length
                        }
                        output.flush()
                    } finally {
                        cancellation.detach(output)
                    }
                }
            }
            val response = connection.readBoundedResponse(RAW_RESPONSE_MAX_BYTES, cancellation)
            if (response.statusCode !in acceptedStatusCodes) {
                throw RawCollectionHttpException(response.statusCode)
            }
            val responseContentType = connection.getHeaderField("Content-Type")
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
            val cacheControl = connection.getHeaderField("Cache-Control")
                ?.split(',')
                ?.map(String::trim)
                .orEmpty()
            if (
                responseContentType != RAW_JSON_CONTENT_TYPE ||
                cacheControl.none { it.equals("no-store", ignoreCase = true) }
            ) throw RawCollectionProtocolException()
            isCurrent()
            return response.body
        } finally {
            cancellation.detach(connection)
            connection.disconnect()
        }
    }

    private fun requireValidUploadBinding(
        session: GatewayFieldSession,
        consent: IntegratedConsentConfirmation,
        networkBinding: IntegratedConsentNetworkBinding,
        localManifest: RawCollectionManifest,
        backendManifest: BackendRawManifest,
        binding: BackendRawChunkBinding,
        chunk: RawPlaintextChunk,
    ) {
        val owner = localManifest.owner
        require(session.sessionScope == GatewaySessionScope.GENERAL)
        require(session.isBackendAccountDeviceBound)
        require(session.isUsableFor(owner.actorId))
        require(session.actorId == owner.actorId)
        require(session.backendAccountGeneration == owner.accountGeneration)
        require(session.deviceId == owner.deviceId)
        require(consent.installationId == owner.deviceId)
        require(consent.policyVersion == INTEGRATED_CONSENT_POLICY_VERSION)
        require(consent.selections.isGranted(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        require(consent.backendConsentReceiptSha256 == localManifest.consentReceiptSha256)
        require(networkBinding.transport == IntegratedConsentNetworkTransport.WIFI)
        val rebuilt = requireNotNull(localManifest.toBackendManifest())
        require(rebuilt.manifestSha256 == backendManifest.manifestSha256)
        require(rebuilt.collectionId == backendManifest.collectionId)
        require(rebuilt.objects == backendManifest.objects)
        require(rebuilt.chunkBindings == backendManifest.chunkBindings)
        require(rebuilt.chunkCount == backendManifest.chunkCount)
        require(rebuilt.totalBytes == backendManifest.totalBytes)
        require(rebuilt.jsonUtf8.contentEquals(backendManifest.jsonUtf8))
        require(backendManifest.objects.size == RAW_BACKEND_OBJECT_COUNT)
        require(backendManifest.chunkCount == RAW_MAX_CHUNKS)
        require(binding.localOrdinal == 0 && binding.chunkIndex == 0)
        require(chunk.metadata == localManifest.chunks.single())
        require(chunk.metadata.ordinal == binding.localOrdinal)
        require(chunk.metadata.sizeBytes == binding.sizeBytes)
        require(chunk.metadata.sha256 == binding.sha256)
        require(chunk.plaintext.size == binding.sizeBytes)
        require(
            MessageDigest.getInstance("SHA-256")
                .digest(chunk.plaintext)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) } ==
                binding.sha256,
        )
    }
}

private data class ParsedRawStatus(
    val state: String,
    val chunkMissing: Boolean,
    val receipt: RawCollectionReceipt?,
) {
    val readyToCommit: Boolean
        get() = state == RAW_STATE_READY_TO_COMMIT && !chunkMissing && receipt == null
}

private fun parseStatus(body: String, manifest: BackendRawManifest): ParsedRawStatus? = runCatching {
    val root = JSONObject(body)
    require(root.exactKeys() == RAW_STATUS_FIELDS)
    require(root.getString("schema_version") == RAW_STATUS_SCHEMA)
    require(root.getString("collection_id") == manifest.collectionId)
    require(root.getString("manifest_sha256") == manifest.manifestSha256)
    require(root.getString("purpose") == BACKEND_RAW_PURPOSE)
    val state = root.getString("state")
    require(state in RAW_STATUS_STATES)
    require(root.strictInt("object_count") == RAW_BACKEND_OBJECT_COUNT)
    require(root.strictInt("chunk_count") == RAW_MAX_CHUNKS)
    require(root.strictLong("total_bytes") == manifest.totalBytes)
    val receivedChunks = root.strictInt("received_chunk_count")
    val receivedBytes = root.strictLong("received_bytes")
    val statusObjects = root.getJSONArray("objects")
    require(statusObjects.length() == RAW_BACKEND_OBJECT_COUNT)
    val statusObject = statusObjects.getJSONObject(0)
    require(statusObject.exactKeys() == RAW_STATUS_OBJECT_FIELDS)
    val expectedObject = manifest.objects.single()
    require(statusObject.getString("object_id") == expectedObject.objectId)
    require(statusObject.getString("kind") == expectedObject.kind)
    require(statusObject.getString("sha256") == expectedObject.sha256)
    require(statusObject.strictInt("chunk_count") == RAW_MAX_CHUNKS)
    require(statusObject.strictLong("size_bytes") == expectedObject.sizeBytes)
    require(statusObject.strictInt("received_chunk_count") == receivedChunks)
    require(statusObject.strictLong("received_bytes") == receivedBytes)
    require(receivedChunks in 0..RAW_MAX_CHUNKS)
    require(receivedBytes in 0L..manifest.totalBytes)
    val missingRanges = statusObject.getJSONArray("missing_ranges")
    val chunkMissing = when (missingRanges.length()) {
        0 -> false
        1 -> missingRanges.getJSONObject(0).let { range ->
            require(range.exactKeys() == RAW_MISSING_RANGE_FIELDS)
            require(range.strictInt("start") == 0)
            require(range.strictInt("end") == 0)
            true
        }
        else -> error("invalid missing ranges")
    }
    require(chunkMissing == (receivedChunks == 0))
    require((receivedBytes == 0L) == chunkMissing)
    if (!chunkMissing) require(receivedBytes == manifest.totalBytes)
    val receipt = if (root.isNull("receipt")) {
        null
    } else {
        parseReceipt(root.getJSONObject("receipt").toString(), manifest)
            ?: error("invalid receipt")
    }
    when (state) {
        RAW_STATE_MANIFEST_ACCEPTED -> require(chunkMissing && receipt == null)
        RAW_STATE_READY_TO_COMMIT -> require(!chunkMissing && receipt == null)
        RAW_STATE_COMMITTED -> require(
            !chunkMissing && receipt?.schemaVersion == LEGACY_BACKEND_RECEIPT_SCHEMA,
        )
        RAW_STATE_RECEIVING -> error("one-chunk collection cannot be partially receiving")
        RAW_STATE_QUARANTINED -> require(
            !chunkMissing && receipt?.schemaVersion == BACKEND_RECEIPT_SCHEMA,
        )
    }
    ParsedRawStatus(state, chunkMissing, receipt)
}.getOrNull()

private fun validChunkAck(
    body: String,
    manifest: BackendRawManifest,
    binding: BackendRawChunkBinding,
): Boolean = runCatching {
    val root = JSONObject(body)
    require(root.exactKeys() == RAW_CHUNK_ACK_FIELDS)
    require(root.getString("schema_version") == RAW_CHUNK_ACK_SCHEMA)
    require(root.getString("collection_id") == manifest.collectionId)
    require(root.getString("object_id") == binding.objectId)
    require(root.strictInt("index") == binding.chunkIndex)
    require(root.strictInt("size_bytes") == binding.sizeBytes)
    require(root.getString("sha256") == binding.sha256)
    require(root.getString("state") == RAW_STATE_READY_TO_COMMIT)
    require(canonicalUtcSecond(root.getString("stored_at")))
    true
}.getOrDefault(false)

private fun parseReceipt(
    body: String,
    manifest: BackendRawManifest,
): RawCollectionReceipt? = runCatching {
    val root = JSONObject(body)
    val schemaVersion = root.getString("schema_version")
    require(
        root.exactKeys() == when (schemaVersion) {
            BACKEND_RECEIPT_SCHEMA -> RAW_RECEIPT_V2_FIELDS
            LEGACY_BACKEND_RECEIPT_SCHEMA -> RAW_RECEIPT_V1_FIELDS
            else -> error("unsupported raw receipt schema")
        },
    )
    val objectValues = root.getJSONArray("objects")
    require(objectValues.length() == RAW_BACKEND_OBJECT_COUNT)
    val objects = (0 until objectValues.length()).map { index ->
        val item = objectValues.getJSONObject(index)
        require(item.exactKeys() == RAW_RECEIPT_OBJECT_FIELDS)
        RawReceiptObject(
            objectId = item.getString("object_id"),
            kind = item.getString("kind"),
            sizeBytes = item.strictLong("size_bytes"),
            sha256 = item.getString("sha256"),
            chunkCount = item.strictInt("chunk_count"),
        )
    }
    val receipt = RawCollectionReceipt(
        schemaVersion = schemaVersion,
        collectionId = root.getString("collection_id"),
        manifestSha256 = root.getString("manifest_sha256"),
        purpose = root.getString("purpose"),
        persistenceMarker = root.getString("persistence_marker"),
        objectCount = root.strictInt("object_count"),
        chunkCount = root.strictInt("chunk_count"),
        totalBytes = root.strictLong("total_bytes"),
        objects = objects,
        retentionClass = root.getString("retention_class"),
        committedAt = root.getString("committed_at"),
        retentionExpiresAt = root.optString("retention_expires_at").takeIf(String::isNotEmpty),
        quarantineExpiresAt = root.optString("quarantine_expires_at").takeIf(String::isNotEmpty),
        receiptSha256 = root.getString("receipt_sha256"),
    )
    receipt.takeIf { validBackendReceipt(it, manifest) }
}.getOrNull()

private fun JSONObject.exactKeys(): Set<String> = keys().asSequence().toSet()

private fun JSONObject.strictInt(name: String): Int = (get(name) as? Int)
    ?: error("$name must be an integer")

private fun JSONObject.strictLong(name: String): Long = when (val value = get(name)) {
    is Int -> value.toLong()
    is Long -> value
    else -> error("$name must be an integer")
}

private fun canonicalUtcSecond(value: String): Boolean = runCatching {
    require(RAW_UTC_SECOND.matches(value))
    val parsed = Instant.parse(value)
    !value.contains('.') && parsed.toString() == value
}.getOrDefault(false)

internal class RawCollectionHttpException(
    val statusCode: Int,
) : IllegalStateException("raw collection request failed: $statusCode")

internal class RawCollectionProtocolException :
    IllegalStateException("raw collection response is malformed")

private const val RAW_GATEWAY_PATH = "/api/raw-collections/"
private const val RAW_JSON_CONTENT_TYPE = "application/json"
private const val RAW_CHUNK_CONTENT_TYPE = "application/octet-stream"
private const val RAW_PURPOSE_HEADER = "x-walksafe-raw-purpose"
private const val RAW_WALK_ID_HEADER = "x-walksafe-raw-walk-id"
private const val RAW_MANIFEST_SHA256_HEADER = "x-walksafe-raw-manifest-sha256"
private const val RAW_CHUNK_SHA256_HEADER = "x-walksafe-chunk-sha256"
private const val RAW_COMMIT_SHA256_HEADER = "x-walksafe-raw-commit-sha256"
private const val RAW_STATUS_SCHEMA = "walksafe.raw-collection-status.v1"
private const val RAW_CHUNK_ACK_SCHEMA = "walksafe.raw-collection-chunk-ack.v1"
private const val RAW_STATE_MANIFEST_ACCEPTED = "MANIFEST_ACCEPTED"
private const val RAW_STATE_RECEIVING = "RECEIVING"
private const val RAW_STATE_READY_TO_COMMIT = "READY_TO_COMMIT"
private const val RAW_STATE_COMMITTED = "COMMITTED"
private const val RAW_STATE_QUARANTINED = "QUARANTINED"
private const val RAW_MANIFEST_REQUEST_MAX_BYTES = 512 * 1_024
private const val RAW_COMMIT_REQUEST_MAX_BYTES = 16 * 1_024
private const val RAW_RESPONSE_MAX_BYTES = 512 * 1_024
private const val RAW_CONNECT_TIMEOUT_MS = 8_000
private const val RAW_READ_TIMEOUT_MS = 20_000
private const val RAW_WRITE_BUFFER_BYTES = 16 * 1_024
private val MANIFEST_SUCCESS_CODES = setOf(200, 201)
private val CHUNK_SUCCESS_CODES = setOf(200, 201)
private val STATUS_SUCCESS_CODES = setOf(200)
private val COMMIT_SUCCESS_CODES = setOf(200)
private val RAW_STATUS_STATES = setOf(
    RAW_STATE_MANIFEST_ACCEPTED,
    RAW_STATE_RECEIVING,
    RAW_STATE_READY_TO_COMMIT,
    RAW_STATE_COMMITTED,
    RAW_STATE_QUARANTINED,
)
private val RAW_UTC_SECOND = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")
private val RAW_STATUS_FIELDS = setOf(
    "schema_version",
    "collection_id",
    "manifest_sha256",
    "purpose",
    "state",
    "object_count",
    "chunk_count",
    "total_bytes",
    "received_chunk_count",
    "received_bytes",
    "objects",
    "receipt",
)
private val RAW_STATUS_OBJECT_FIELDS = setOf(
    "object_id",
    "kind",
    "sha256",
    "chunk_count",
    "received_chunk_count",
    "size_bytes",
    "received_bytes",
    "missing_ranges",
)
private val RAW_MISSING_RANGE_FIELDS = setOf("start", "end")
private val RAW_CHUNK_ACK_FIELDS = setOf(
    "schema_version",
    "collection_id",
    "object_id",
    "index",
    "size_bytes",
    "sha256",
    "state",
    "stored_at",
)
private val RAW_RECEIPT_COMMON_FIELDS = setOf(
    "schema_version",
    "collection_id",
    "manifest_sha256",
    "purpose",
    "persistence_marker",
    "object_count",
    "chunk_count",
    "total_bytes",
    "objects",
    "retention_class",
    "committed_at",
    "receipt_sha256",
)
private val RAW_RECEIPT_V1_FIELDS = RAW_RECEIPT_COMMON_FIELDS + "retention_expires_at"
private val RAW_RECEIPT_V2_FIELDS = RAW_RECEIPT_COMMON_FIELDS + "quarantine_expires_at"
private val RAW_RECEIPT_OBJECT_FIELDS = setOf(
    "object_id",
    "kind",
    "size_bytes",
    "sha256",
    "chunk_count",
)

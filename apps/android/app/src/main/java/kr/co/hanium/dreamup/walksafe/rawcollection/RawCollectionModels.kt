package kr.co.hanium.dreamup.walksafe.rawcollection

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal enum class RawChunkType(val wireName: String, val contentType: String) {
    VIDEO("VIDEO", "video/mp4"),
    AUDIO("AUDIO", "audio/mp4"),
    EXACT_LOCATION("EXACT_LOCATION", "application/json"),
    SENSOR("SENSOR", "application/octet-stream"),
    ROUTE("ROUTE", "application/json"),
    DETECTION("DETECTION", "application/json"),
    REPORT("REPORT", "application/octet-stream"),
    PERFORMANCE("PERFORMANCE", "application/json"),
}

internal enum class RawManifestState {
    PARTIAL,
    COMPLETE,
}

internal data class RawChunkMetadata(
    val ordinal: Int,
    val type: RawChunkType,
    val capturedAtEpochMs: Long,
    val sizeBytes: Int,
    val sha256: String,
)

internal data class RawCollectionOwner(
    val actorId: String,
    val accountGeneration: Long,
    val deviceId: String,
) {
    init {
        require(isCanonicalUuid(actorId))
        require(accountGeneration > 0L)
        require(RAW_DEVICE_ID.matches(deviceId))
    }
}

internal data class RawCollectionManifest(
    val collectionId: String,
    val owner: RawCollectionOwner,
    val walkSessionId: String,
    val consentReceiptSha256: String,
    val capturedStartedAtEpochMs: Long,
    val capturedEndedAtEpochMs: Long?,
    val expiresAtEpochMs: Long,
    val state: RawManifestState,
    val chunks: List<RawChunkMetadata>,
) {
    val uploadReady: Boolean
        get() = state == RawManifestState.COMPLETE && chunks.isCompleteRuntimeBatch()
}

internal data class RawCollectionReceipt(
    val schemaVersion: String,
    val collectionId: String,
    val manifestSha256: String,
    val purpose: String,
    val persistenceMarker: String,
    val objectCount: Int,
    val chunkCount: Int,
    val totalBytes: Long,
    val objects: List<RawReceiptObject>,
    val retentionClass: String,
    val committedAt: String,
    val retentionExpiresAt: String? = null,
    val quarantineExpiresAt: String? = null,
    val receiptSha256: String,
)

internal data class RawReceiptObject(
    val objectId: String,
    val kind: String,
    val sizeBytes: Long,
    val sha256: String,
    val chunkCount: Int,
)

internal data class BackendRawManifest(
    val jsonUtf8: ByteArray,
    val manifestSha256: String,
    val collectionId: String,
    val objects: List<RawReceiptObject>,
    val chunkBindings: List<BackendRawChunkBinding>,
    val chunkCount: Int,
    val totalBytes: Long,
)

/** Explicitly maps a local collection ordinal to the Backend object/chunk address. */
internal data class BackendRawChunkBinding(
    val localOrdinal: Int,
    val objectId: String,
    val chunkIndex: Int,
    val sizeBytes: Int,
    val sha256: String,
)

internal data class BackendRawCommit(
    val jsonUtf8: ByteArray,
    val commitSha256: String,
)

internal enum class RawCollectionFence {
    CONSENT_REVOKED,
    ACCOUNT_DELETED,
}

internal fun encodeRawManifest(manifest: RawCollectionManifest): ByteArray = JSONObject()
    .put("schema_version", RAW_MANIFEST_SCHEMA)
    .put("collection_id", manifest.collectionId)
    .put("actor_id", manifest.owner.actorId)
    .put("account_generation", manifest.owner.accountGeneration)
    .put("device_id", manifest.owner.deviceId)
    .put("walk_session_id", manifest.walkSessionId)
    .put("consent_receipt_sha256", manifest.consentReceiptSha256)
    .put("captured_started_at_epoch_ms", manifest.capturedStartedAtEpochMs)
    .put("captured_ended_at_epoch_ms", manifest.capturedEndedAtEpochMs ?: JSONObject.NULL)
    .put("expires_at_epoch_ms", manifest.expiresAtEpochMs)
    .put("state", manifest.state.name)
    .put("chunks", JSONArray().also { chunks ->
        manifest.chunks.forEach { chunk ->
            chunks.put(
                JSONObject()
                    .put("ordinal", chunk.ordinal)
                    .put("type", chunk.type.wireName)
                    .put("captured_at_epoch_ms", chunk.capturedAtEpochMs)
                    .put("size_bytes", chunk.sizeBytes)
                    .put("sha256", chunk.sha256),
            )
        }
    })
    .toString()
    .toByteArray(Charsets.UTF_8)

internal fun decodeRawManifest(bytes: ByteArray): RawCollectionManifest? = runCatching {
    val root = JSONObject(String(bytes, Charsets.UTF_8))
    require(root.keysAsSet() == RAW_MANIFEST_FIELDS)
    require(root.getString("schema_version") == RAW_MANIFEST_SCHEMA)
    val collectionId = root.getString("collection_id")
    val owner = RawCollectionOwner(
        actorId = root.getString("actor_id"),
        accountGeneration = root.strictLong("account_generation"),
        deviceId = root.getString("device_id"),
    )
    val walkSessionId = root.getString("walk_session_id")
    val consentReceiptSha256 = root.getString("consent_receipt_sha256")
    require(isCanonicalUuid(collectionId))
    require(isCanonicalUuid(walkSessionId))
    require(SHA256_HEX.matches(consentReceiptSha256))
    val startedAt = root.strictLong("captured_started_at_epoch_ms")
    val endedAt = if (root.isNull("captured_ended_at_epoch_ms")) {
        null
    } else {
        root.strictLong("captured_ended_at_epoch_ms")
    }
    val expiresAt = root.strictLong("expires_at_epoch_ms")
    require(startedAt >= 0L)
    require(expiresAt - startedAt == RAW_COLLECTION_TTL_MS)
    require(endedAt == null || endedAt >= startedAt)
    val state = RawManifestState.valueOf(root.getString("state"))
    require((state == RawManifestState.COMPLETE) == (endedAt != null))
    val chunkValues = root.getJSONArray("chunks")
    require(chunkValues.length() <= RAW_MAX_CHUNKS)
    val chunks = mutableListOf<RawChunkMetadata>()
    repeat(chunkValues.length()) { index ->
        val chunk = chunkValues.getJSONObject(index)
        require(chunk.keysAsSet() == RAW_CHUNK_FIELDS)
        val ordinal = chunk.strictInt("ordinal")
        val capturedAt = chunk.strictLong("captured_at_epoch_ms")
        val sizeBytes = chunk.strictInt("size_bytes")
        val sha256 = chunk.getString("sha256")
        require(ordinal == index)
        require(capturedAt >= startedAt)
        require(index == 0 || capturedAt >= chunks[index - 1].capturedAtEpochMs)
        require(sizeBytes in 1..RAW_MAX_CHUNK_BYTES)
        require(SHA256_HEX.matches(sha256))
        chunks += RawChunkMetadata(
            ordinal = ordinal,
            type = RawChunkType.entries.single { it.wireName == chunk.getString("type") },
            capturedAtEpochMs = capturedAt,
            sizeBytes = sizeBytes,
            sha256 = sha256,
        )
    }
    require(chunks.all { it.type in RAW_RUNTIME_CHUNK_TYPES })
    require(chunks.map(RawChunkMetadata::type).distinct().size == chunks.size)
    require(chunks.size < RAW_MAX_CHUNKS || chunks.isCompleteRuntimeBatch())
    require(chunks.all { it.capturedAtEpochMs < expiresAt })
    if (endedAt != null) {
        require(chunks.lastOrNull()?.capturedAtEpochMs?.let { endedAt >= it } ?: true)
        require(endedAt < expiresAt)
    }
    RawCollectionManifest(
        collectionId = collectionId,
        owner = owner,
        walkSessionId = walkSessionId,
        consentReceiptSha256 = consentReceiptSha256,
        capturedStartedAtEpochMs = startedAt,
        capturedEndedAtEpochMs = endedAt,
        expiresAtEpochMs = expiresAt,
        state = state,
        chunks = chunks,
    )
}.getOrNull()

internal fun isCanonicalUuid(value: String): Boolean = runCatching {
    java.util.UUID.fromString(value).toString() == value
}.getOrDefault(false)

internal fun RawCollectionManifest.toBackendManifest(): BackendRawManifest? {
    if (
        !uploadReady ||
        chunks.size != RAW_BACKEND_OBJECT_COUNT ||
        !chunks.isCompleteRuntimeBatch()
    ) return null
    val endedAt = capturedEndedAtEpochMs ?: return null
    val backendObjects = chunks.map { chunk ->
        val objectId = deterministicUuid("$collectionId|object|${chunk.ordinal}")
        JSONObject()
            .put("object_id", objectId)
            .put("kind", chunk.type.wireName)
            .put("content_type", chunk.type.contentType)
            .put("size_bytes", chunk.sizeBytes)
            .put("sha256", chunk.sha256)
            .put(
                "chunks",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put("size_bytes", chunk.sizeBytes)
                        .put("sha256", chunk.sha256),
                ),
            )
    }.sortedBy { it.getString("object_id") }
    val withoutDigest = JSONObject()
        .put("schema_version", BACKEND_MANIFEST_SCHEMA)
        .put("collection_id", collectionId)
        .put("walk_id", walkSessionId)
        .put("segment_id", deterministicUuid("$collectionId|segment"))
        .put("purpose", BACKEND_RAW_PURPOSE)
        .put("captured_started_at", utcSeconds(capturedStartedAtEpochMs))
        .put("captured_ended_at", utcSeconds(endedAt))
        .put("consent_receipt_sha256", consentReceiptSha256)
        .put("object_count", backendObjects.size)
        .put("chunk_count", chunks.size)
        .put("total_bytes", chunks.sumOf { it.sizeBytes.toLong() })
        .put("objects", JSONArray().also { array -> backendObjects.forEach(array::put) })
    val manifestSha = domainHash(BACKEND_MANIFEST_DOMAIN, withoutDigest)
    val complete = JSONObject(withoutDigest.toString()).put("manifest_sha256", manifestSha)
    val receiptObjects = backendObjects.map { value ->
        RawReceiptObject(
            objectId = value.getString("object_id"),
            kind = value.getString("kind"),
            sizeBytes = value.getLong("size_bytes"),
            sha256 = value.getString("sha256"),
            chunkCount = value.getJSONArray("chunks").length(),
        )
    }
    val chunkBindings = chunks.map { chunk ->
        BackendRawChunkBinding(
            localOrdinal = chunk.ordinal,
            objectId = deterministicUuid("$collectionId|object|${chunk.ordinal}"),
            chunkIndex = 0,
            sizeBytes = chunk.sizeBytes,
            sha256 = chunk.sha256,
        )
    }
    return BackendRawManifest(
        jsonUtf8 = canonicalJson(complete).toByteArray(Charsets.UTF_8),
        manifestSha256 = manifestSha,
        collectionId = collectionId,
        objects = receiptObjects,
        chunkBindings = chunkBindings,
        chunkCount = chunks.size,
        totalBytes = chunks.sumOf { it.sizeBytes.toLong() },
    )
}

internal fun BackendRawManifest.commitPayload(): BackendRawCommit? {
    if (
        objects.size != RAW_BACKEND_OBJECT_COUNT ||
        chunkBindings.size != RAW_MAX_CHUNKS ||
        chunkCount != RAW_MAX_CHUNKS ||
        totalBytes !in 1..RAW_MAX_COLLECTION_BYTES
    ) {
        return null
    }
    val body = JSONObject()
        .put("schema_version", BACKEND_COMMIT_SCHEMA)
        .put("collection_id", collectionId)
        .put("manifest_sha256", manifestSha256)
        .put("object_count", objects.size)
        .put("chunk_count", chunkCount)
        .put("total_bytes", totalBytes)
    return BackendRawCommit(
        jsonUtf8 = canonicalJson(body).toByteArray(Charsets.UTF_8),
        commitSha256 = domainHash(BACKEND_COMMIT_DOMAIN, body),
    )
}

internal fun rawReceiptSha256(receipt: RawCollectionReceipt): String = domainHash(
    when (receipt.schemaVersion) {
        BACKEND_RECEIPT_SCHEMA -> BACKEND_RECEIPT_DOMAIN
        LEGACY_BACKEND_RECEIPT_SCHEMA -> LEGACY_BACKEND_RECEIPT_DOMAIN
        else -> error("unsupported raw collection receipt schema")
    },
    receipt.toJson(includeDigest = false),
)

internal fun validBackendReceipt(
    receipt: RawCollectionReceipt,
    manifest: BackendRawManifest,
): Boolean = runCatching {
    require(receipt.schemaVersion in BACKEND_RECEIPT_SCHEMAS)
    require(receipt.collectionId == manifest.collectionId)
    require(receipt.manifestSha256 == manifest.manifestSha256)
    require(receipt.purpose == BACKEND_RAW_PURPOSE)
    require(receipt.persistenceMarker == RAW_RECEIPT_PERSISTENCE_MARKER)
    require(receipt.objectCount == manifest.objects.size)
    require(receipt.chunkCount == manifest.chunkCount)
    require(receipt.totalBytes == manifest.totalBytes)
    require(receipt.objects == manifest.objects)
    val committedAt = Instant.parse(receipt.committedAt)
    require(receipt.committedAt == utcSeconds(committedAt.toEpochMilli()))
    when (receipt.schemaVersion) {
        BACKEND_RECEIPT_SCHEMA -> {
            require(receipt.retentionClass == BACKEND_RETENTION_CLASS)
            require(receipt.retentionExpiresAt == null)
            val expiresAt = Instant.parse(requireNotNull(receipt.quarantineExpiresAt))
            require(receipt.quarantineExpiresAt == utcSeconds(expiresAt.toEpochMilli()))
            require(expiresAt == committedAt.plusSeconds(BACKEND_RETENTION_SECONDS))
        }
        LEGACY_BACKEND_RECEIPT_SCHEMA -> {
            require(receipt.retentionClass == LEGACY_BACKEND_RETENTION_CLASS)
            require(receipt.quarantineExpiresAt == null)
            val expiresAt = Instant.parse(requireNotNull(receipt.retentionExpiresAt))
            require(receipt.retentionExpiresAt == utcSeconds(expiresAt.toEpochMilli()))
            require(expiresAt == committedAt.plusSeconds(LEGACY_BACKEND_RETENTION_SECONDS))
        }
    }
    require(REPORT_SHA256.matches(receipt.receiptSha256))
    require(receipt.receiptSha256 == rawReceiptSha256(receipt))
    true
}.getOrDefault(false)

private fun RawCollectionReceipt.toJson(includeDigest: Boolean): JSONObject = JSONObject()
    .put("schema_version", schemaVersion)
    .put("collection_id", collectionId)
    .put("manifest_sha256", manifestSha256)
    .put("purpose", purpose)
    .put("persistence_marker", persistenceMarker)
    .put("object_count", objectCount)
    .put("chunk_count", chunkCount)
    .put("total_bytes", totalBytes)
    .put(
        "objects",
        JSONArray().also { array ->
            objects.forEach { item ->
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
    .put("retention_class", retentionClass)
    .put("committed_at", committedAt)
    .also { value ->
        when (schemaVersion) {
            BACKEND_RECEIPT_SCHEMA -> value.put(
                "quarantine_expires_at",
                requireNotNull(quarantineExpiresAt),
            )
            LEGACY_BACKEND_RECEIPT_SCHEMA -> value.put(
                "retention_expires_at",
                requireNotNull(retentionExpiresAt),
            )
            else -> error("unsupported raw collection receipt schema")
        }
    }
    .also { if (includeDigest) it.put("receipt_sha256", receiptSha256) }

private fun domainHash(domain: ByteArray, value: JSONObject): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(domain + canonicalJson(value).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun canonicalJson(value: Any): String = when (value) {
    is JSONObject -> value.keysAsSet().sorted().joinToString(",", "{", "}") { key ->
        "${JSONObject.quote(key)}:${canonicalJson(value.get(key))}"
    }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { index ->
        canonicalJson(value.get(index))
    }
    is String -> JSONObject.quote(value)
    is Number, is Boolean -> value.toString()
    JSONObject.NULL -> "null"
    else -> error("unsupported canonical JSON value")
}

private fun deterministicUuid(value: String): String =
    UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()

private fun utcSeconds(epochMs: Long): String = UTC_SECONDS.format(Instant.ofEpochMilli(epochMs))

private fun JSONObject.keysAsSet(): Set<String> = buildSet { keys().forEach(::add) }

private fun JSONObject.strictLong(name: String): Long = when (val value = get(name)) {
    is Int -> value.toLong()
    is Long -> value
    else -> error("$name must be an integer")
}

private fun JSONObject.strictInt(name: String): Int = when (val value = get(name)) {
    is Int -> value
    else -> error("$name must be an integer")
}

internal const val RAW_MANIFEST_SCHEMA = "walksafe.android.raw-collection-manifest.v2"
internal const val RAW_RECEIPT_PERSISTENCE_MARKER = "DATABASE_AND_ENCRYPTED_CHUNK_STORE"
internal const val BACKEND_MANIFEST_SCHEMA = "walksafe.raw-collection-manifest.v1"
internal const val BACKEND_COMMIT_SCHEMA = "walksafe.raw-collection-commit.v1"
internal const val BACKEND_RECEIPT_SCHEMA = "walksafe.raw-collection-receipt.v2"
internal const val LEGACY_BACKEND_RECEIPT_SCHEMA = "walksafe.raw-collection-receipt.v1"
internal const val BACKEND_RAW_PURPOSE = "GENERAL_RAW"
internal const val BACKEND_RETENTION_CLASS = "RAW_QUARANTINE_14D"
internal const val LEGACY_BACKEND_RETENTION_CLASS = "RAW_ORIGINAL_180D"
internal const val RAW_MAX_CHUNK_BYTES = 64 * 1_024
internal const val RAW_MAX_CHUNKS = 2
internal const val RAW_BACKEND_OBJECT_COUNT = 2
internal const val RAW_MAX_COLLECTION_BYTES = 128L * 1_024L
internal const val RAW_COLLECTION_TTL_MS = 30L * 24L * 60L * 60L * 1_000L
internal val SHA256_HEX = Regex("[0-9a-f]{64}")
internal val RAW_RUNTIME_CHUNK_TYPE_ORDER = listOf(
    RawChunkType.DETECTION,
    RawChunkType.PERFORMANCE,
)
internal val RAW_RUNTIME_CHUNK_TYPES = RAW_RUNTIME_CHUNK_TYPE_ORDER.toSet()

internal fun List<RawChunkMetadata>.isCompleteRuntimeBatch(): Boolean =
    map(RawChunkMetadata::type) == RAW_RUNTIME_CHUNK_TYPE_ORDER &&
        withIndex().all { (index, chunk) ->
            chunk.ordinal == index && chunk.sizeBytes in 1..RAW_MAX_CHUNK_BYTES
        } &&
        map(RawChunkMetadata::capturedAtEpochMs).distinct().size == 1 &&
        sumOf { it.sizeBytes.toLong() } in 1..RAW_MAX_COLLECTION_BYTES
private val REPORT_SHA256 = Regex("[0-9a-f]{64}")
private val BACKEND_MANIFEST_DOMAIN = "walksafe/raw-collection-manifest/v1\u0000".toByteArray()
private val BACKEND_COMMIT_DOMAIN = "walksafe/raw-collection-commit/v1\u0000".toByteArray()
private val BACKEND_RECEIPT_DOMAIN = "walksafe/raw-collection-receipt/v2\u0000".toByteArray()
private val LEGACY_BACKEND_RECEIPT_DOMAIN =
    "walksafe/raw-collection-receipt/v1\u0000".toByteArray()
private const val BACKEND_RETENTION_SECONDS = 14L * 24L * 60L * 60L
private const val LEGACY_BACKEND_RETENTION_SECONDS = 180L * 24L * 60L * 60L
private val BACKEND_RECEIPT_SCHEMAS = setOf(
    BACKEND_RECEIPT_SCHEMA,
    LEGACY_BACKEND_RECEIPT_SCHEMA,
)
private val UTC_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
    .withZone(ZoneOffset.UTC)

private val RAW_MANIFEST_FIELDS = setOf(
    "schema_version",
    "collection_id",
    "actor_id",
    "account_generation",
    "device_id",
    "walk_session_id",
    "consent_receipt_sha256",
    "captured_started_at_epoch_ms",
    "captured_ended_at_epoch_ms",
    "expires_at_epoch_ms",
    "state",
    "chunks",
)
private val RAW_CHUNK_FIELDS = setOf(
    "ordinal",
    "type",
    "captured_at_epoch_ms",
    "size_bytes",
    "sha256",
)
private val RAW_DEVICE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}")

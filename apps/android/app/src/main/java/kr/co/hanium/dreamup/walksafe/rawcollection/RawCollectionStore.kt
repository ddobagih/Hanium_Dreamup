package kr.co.hanium.dreamup.walksafe.rawcollection

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.AndroidKeyStoreAead
import kr.co.hanium.dreamup.walksafe.security.LocalAead

internal enum class RawWalkState {
    ACTIVE,
    PAUSED,
    ENDED,
}

internal data class RawPlaintextChunk(
    val metadata: RawChunkMetadata,
    val plaintext: ByteArray,
)

internal data class RawPlaintextBatchItem(
    val type: RawChunkType,
    val capturedAtEpochMs: Long,
    val plaintext: ByteArray,
)

/** Crash-safe local raw collection core. Network upload is intentionally outside this class. */
internal class RawCollectionStore private constructor(
    private val storage: RawCollectionStorage,
    private val aead: LocalAead,
    private val nowMillis: () -> Long,
) {
    constructor(
        rootDirectory: File,
        aead: LocalAead = AndroidKeyStoreAead(RAW_COLLECTION_KEY_POLICY),
        nowMillis: () -> Long = System::currentTimeMillis,
    ) : this(FileRawCollectionStorage(rootDirectory), aead, nowMillis)

    internal constructor(
        storage: RawCollectionStorage,
        aead: LocalAead,
        nowMillis: () -> Long,
        testOnly: Unit = Unit,
    ) : this(storage, aead, nowMillis)

    @Synchronized
    fun open(
        collectionId: String,
        owner: RawCollectionOwner,
        walkSessionId: String,
        consentReceiptSha256: String,
        capturedStartedAtEpochMs: Long? = null,
        rawConsentGranted: Boolean,
        walkState: RawWalkState,
    ): Boolean {
        val now = nowMillis()
        val startedAt = capturedStartedAtEpochMs ?: now
        if (
            now < 0L ||
            startedAt !in 0L..now ||
            !isCanonicalUuid(collectionId) ||
            !isCanonicalUuid(walkSessionId) ||
            !SHA256_HEX.matches(consentReceiptSha256) ||
            !rawConsentGranted ||
            walkState != RawWalkState.ACTIVE ||
            storage.hasFence(GLOBAL_DELETE_FENCE) ||
            storage.hasFence(revocationFence(consentReceiptSha256))
        ) {
            return false
        }
        pruneExpiredLocked(now)
        val activeId = reconcileActivePointer()
        if (activeId != null) {
            val active = readManifest(activeId) ?: return false
            return active.collectionId == collectionId &&
                active.owner == owner &&
                active.walkSessionId == walkSessionId &&
                active.consentReceiptSha256 == consentReceiptSha256 &&
                active.state == RawManifestState.PARTIAL &&
                now >= active.capturedStartedAtEpochMs &&
                now < active.expiresAtEpochMs
        }
        if (storage.readManifest(collectionId) != null) return false
        val expiresAt = startedAt + RAW_COLLECTION_TTL_MS
        if (expiresAt < startedAt || expiresAt <= now) return false
        val manifest = RawCollectionManifest(
            collectionId = collectionId,
            owner = owner,
            walkSessionId = walkSessionId,
            consentReceiptSha256 = consentReceiptSha256,
            capturedStartedAtEpochMs = startedAt,
            capturedEndedAtEpochMs = null,
            expiresAtEpochMs = expiresAt,
            state = RawManifestState.PARTIAL,
            chunks = emptyList(),
        )
        if (!writeManifest(manifest)) return false
        if (storage.writeActiveCollectionIdAtomically(collectionId)) return true
        storage.deleteCollection(collectionId)
        return false
    }

    @Synchronized
    fun appendBatch(
        owner: RawCollectionOwner,
        walkSessionId: String,
        rawConsentGranted: Boolean,
        walkState: RawWalkState,
        items: List<RawPlaintextBatchItem>,
    ): List<RawChunkMetadata>? {
        if (!rawConsentGranted || walkState != RawWalkState.ACTIVE) return null
        val now = nowMillis()
        if (
            now < 0L ||
            items.size != RAW_MAX_CHUNKS ||
            items.map(RawPlaintextBatchItem::type) != RAW_RUNTIME_CHUNK_TYPE_ORDER ||
            items.map(RawPlaintextBatchItem::type).distinct().size != items.size ||
            items.any { it.plaintext.isEmpty() || it.plaintext.size > RAW_MAX_CHUNK_BYTES } ||
            items.sumOf { it.plaintext.size.toLong() } > RAW_MAX_COLLECTION_BYTES ||
            items.map(RawPlaintextBatchItem::capturedAtEpochMs).distinct().size != 1 ||
            storage.hasFence(GLOBAL_DELETE_FENCE)
        ) {
            return null
        }
        val collectionId = reconcileActivePointer() ?: return null
        val manifest = readManifest(collectionId) ?: run {
            discardCollectionAndPointer(collectionId)
            return null
        }
        val capturedAtEpochMs = items.first().capturedAtEpochMs
        if (
            manifest.owner != owner ||
            manifest.walkSessionId != walkSessionId ||
            storage.hasFence(revocationFence(manifest.consentReceiptSha256)) ||
            manifest.state != RawManifestState.PARTIAL ||
            now >= manifest.expiresAtEpochMs ||
            manifest.chunks.isNotEmpty() ||
            capturedAtEpochMs < manifest.capturedStartedAtEpochMs ||
            capturedAtEpochMs > now
        ) {
            if (now >= manifest.expiresAtEpochMs) {
                discardCollectionAndPointer(collectionId)
            }
            return null
        }
        val metadata = items.mapIndexed { ordinal, item ->
            RawChunkMetadata(
                ordinal = ordinal,
                type = item.type,
                capturedAtEpochMs = item.capturedAtEpochMs,
                sizeBytes = item.plaintext.size,
                sha256 = sha256Hex(item.plaintext),
            )
        }
        val envelopes = items.zip(metadata).map { (item, chunkMetadata) ->
            val sealed = aead.seal(
                item.plaintext,
                chunkAad(manifest.collectionId, chunkMetadata),
                CHUNK_LIMITS,
            )
            (sealed as? AeadSealResult.Sealed)?.envelope ?: run {
                discardCollectionAndPointer(collectionId)
                return null
            }
        }
        metadata.zip(envelopes).forEach { (chunkMetadata, envelope) ->
            if (!storage.writeChunkAtomically(collectionId, chunkMetadata.ordinal, envelope)) {
                discardCollectionAndPointer(collectionId)
                return null
            }
        }
        if (!writeManifest(manifest.copy(chunks = metadata))) {
            discardCollectionAndPointer(collectionId)
            return null
        }
        return metadata
    }

    @Synchronized
    fun activePartialManifest(
        owner: RawCollectionOwner,
        walkSessionId: String,
    ): RawCollectionManifest? {
        val collectionId = reconcileActivePointer() ?: return null
        return readManifest(collectionId)?.takeIf {
            it.owner == owner &&
                it.walkSessionId == walkSessionId &&
                it.state == RawManifestState.PARTIAL
        }
    }

    @Synchronized
    fun latestCapturedAtEpochMs(
        owner: RawCollectionOwner,
        walkSessionId: String,
    ): Long? = storage.listCollectionIds()
        .mapNotNull(::readManifest)
        .filter { it.owner == owner && it.walkSessionId == walkSessionId }
        .flatMap(RawCollectionManifest::chunks)
        .maxOfOrNull(RawChunkMetadata::capturedAtEpochMs)

    @Synchronized
    fun discardActivePartial(
        owner: RawCollectionOwner,
        walkSessionId: String,
    ): Boolean {
        val collectionId = storage.readActiveCollectionId() ?: return true
        val manifest = readManifest(collectionId) ?: return discardCollectionAndPointer(collectionId)
        if (
            manifest.owner != owner ||
            manifest.walkSessionId != walkSessionId ||
            manifest.state != RawManifestState.PARTIAL
        ) return false
        return discardCollectionAndPointer(collectionId)
    }

    @Synchronized
    fun sealActiveSegment(
        owner: RawCollectionOwner,
        walkSessionId: String,
    ): RawCollectionManifest? {
        val collectionId = storage.readActiveCollectionId() ?: return null
        val manifest = readManifest(collectionId) ?: run {
            storage.clearActiveCollectionId()
            return null
        }
        if (
            manifest.owner != owner ||
            manifest.walkSessionId != walkSessionId ||
            manifest.state != RawManifestState.PARTIAL
        ) return null
        if (!manifest.chunks.isCompleteRuntimeBatch()) return null
        if (manifest.chunks.any { !isStoredChunkValid(manifest, it) }) return null
        val now = nowMillis()
        if (now < manifest.capturedStartedAtEpochMs || now >= manifest.expiresAtEpochMs) {
            storage.clearActiveCollectionId()
            if (now >= manifest.expiresAtEpochMs) storage.deleteCollection(collectionId)
            return null
        }
        val capturedEndedAtEpochMs = manifest.chunks.first().capturedAtEpochMs
        val complete = manifest.copy(
            capturedEndedAtEpochMs = capturedEndedAtEpochMs,
            state = RawManifestState.COMPLETE,
        )
        if (!writeManifest(complete)) return null
        if (!storage.clearActiveCollectionId()) return null
        return complete
    }

    /** Ends local admission without sealing; callers may explicitly discard the residual PARTIAL. */
    @Synchronized
    fun onSessionEnded(
        owner: RawCollectionOwner,
        walkSessionId: String,
    ): Boolean {
        val collectionId = storage.readActiveCollectionId() ?: return true
        val manifest = readManifest(collectionId) ?: return storage.clearActiveCollectionId()
        if (manifest.owner != owner || manifest.walkSessionId != walkSessionId) return false
        return storage.clearActiveCollectionId()
    }

    @Synchronized
    fun onConsentRevoked(consentReceiptSha256: String): Boolean {
        if (!SHA256_HEX.matches(consentReceiptSha256)) return false
        val fenced = storage.writeFenceAtomically(
            revocationFence(consentReceiptSha256),
            RawCollectionFence.CONSENT_REVOKED.name,
        )
        val closed = closePartialForConsentReceipt(consentReceiptSha256)
        return fenced && closed
    }

    @Synchronized
    fun onAccountDeleted(): Boolean {
        val fenced = storage.writeFenceAtomically(
            GLOBAL_DELETE_FENCE,
            RawCollectionFence.ACCOUNT_DELETED.name,
        )
        val closed = storage.clearActiveCollectionId()
        val deleted = storage.deleteAllCollections()
        val keysDestroyed = aead.destroyKnownVersions()
        val purgeVerified = fenced && closed && deleted && keysDestroyed &&
            storage.writeFenceAtomically(KEY_PURGE_VERIFIED_FENCE, KEY_PURGE_VERIFIED_VALUE)
        return fenced && closed && deleted && keysDestroyed && purgeVerified
    }

    @Synchronized
    fun uploadReadyManifest(
        collectionId: String,
        owner: RawCollectionOwner,
    ): RawCollectionManifest? {
        if (storage.hasFence(GLOBAL_DELETE_FENCE)) return null
        val manifest = readManifest(collectionId) ?: return null
        if (manifest.owner != owner) return null
        if (storage.hasFence(revocationFence(manifest.consentReceiptSha256))) return null
        val now = nowMillis()
        if (now < manifest.capturedStartedAtEpochMs) return null
        if (now >= manifest.expiresAtEpochMs) {
            if (storage.readActiveCollectionId() == collectionId) storage.clearActiveCollectionId()
            storage.deleteCollection(collectionId)
            return null
        }
        return manifest.takeIf(RawCollectionManifest::uploadReady)
    }

    @Synchronized
    fun readyManifests(owner: RawCollectionOwner): List<RawCollectionManifest> {
        if (storage.hasFence(GLOBAL_DELETE_FENCE)) return emptyList()
        val now = nowMillis()
        if (now < 0L) return emptyList()
        pruneExpiredLocked(now)
        return storage.listCollectionIds()
            .mapNotNull(::readManifest)
            .filter { manifest ->
                manifest.owner == owner &&
                    manifest.uploadReady &&
                    !storage.hasFence(revocationFence(manifest.consentReceiptSha256))
            }
            .sortedWith(
                compareBy<RawCollectionManifest>(RawCollectionManifest::capturedStartedAtEpochMs)
                    .thenBy(RawCollectionManifest::collectionId),
            )
    }

    @Synchronized
    fun backendUploadManifest(
        collectionId: String,
        owner: RawCollectionOwner,
    ): BackendRawManifest? = uploadReadyManifest(collectionId, owner)?.toBackendManifest()

    /** Returns one verified plaintext chunk; the caller owns and must clear the returned bytes. */
    @Synchronized
    fun readUploadChunk(
        collectionId: String,
        owner: RawCollectionOwner,
        ordinal: Int,
    ): RawPlaintextChunk? {
        val manifest = uploadReadyManifest(collectionId, owner) ?: return null
        val metadata = manifest.chunks.getOrNull(ordinal) ?: return null
        val envelope = storage.readChunk(collectionId, ordinal) ?: return null
        val opened = aead.open(envelope, chunkAad(collectionId, metadata), CHUNK_LIMITS)
            as? AeadOpenResult.Opened
            ?: return null
        if (
            opened.plaintext.size != metadata.sizeBytes ||
            sha256Hex(opened.plaintext) != metadata.sha256
        ) {
            opened.plaintext.fill(0)
            return null
        }
        return RawPlaintextChunk(metadata, opened.plaintext)
    }

    @Synchronized
    fun pruneExpired(): Int = pruneExpiredLocked(nowMillis())

    @Synchronized
    fun deleteAfterReceipt(
        owner: RawCollectionOwner,
        receipt: RawCollectionReceipt,
    ): Boolean {
        if (
            receipt.schemaVersion != BACKEND_RECEIPT_SCHEMA ||
            !isCanonicalUuid(receipt.collectionId) ||
            !SHA256_HEX.matches(receipt.manifestSha256) ||
            receipt.persistenceMarker != RAW_RECEIPT_PERSISTENCE_MARKER
        ) {
            return false
        }
        val local = readManifest(receipt.collectionId)?.takeIf { it.owner == owner } ?: return false
        val manifest = local.toBackendManifest() ?: return false
        if (!validBackendReceipt(receipt, manifest)) return false
        if (
            storage.readActiveCollectionId() == receipt.collectionId &&
            !storage.clearActiveCollectionId()
        ) {
            return false
        }
        return storage.deleteCollection(receipt.collectionId)
    }

    @Synchronized
    fun discardPartials(
        owner: RawCollectionOwner,
        walkSessionId: String? = null,
    ): Int {
        if (walkSessionId != null && !isCanonicalUuid(walkSessionId)) return 0
        var discarded = 0
        storage.listCollectionIds().forEach { collectionId ->
            val manifest = readManifest(collectionId) ?: return@forEach
            if (
                manifest.owner == owner &&
                manifest.state == RawManifestState.PARTIAL &&
                (walkSessionId == null || manifest.walkSessionId == walkSessionId) &&
                storage.deleteCollection(collectionId)
            ) {
                if (storage.readActiveCollectionId() == collectionId) {
                    storage.clearActiveCollectionId()
                }
                discarded += 1
            }
        }
        return discarded
    }

    @Synchronized
    fun resetForNewEnrollment(owner: RawCollectionOwner): Boolean {
        if (
            storage.readFence(GLOBAL_DELETE_FENCE) != RawCollectionFence.ACCOUNT_DELETED.name ||
            storage.readFence(KEY_PURGE_VERIFIED_FENCE) != KEY_PURGE_VERIFIED_VALUE ||
            !storage.verifiedEmptyAfterAccountPurge() ||
            storage.listFenceNames().any { name ->
                name != GLOBAL_DELETE_FENCE &&
                    name != KEY_PURGE_VERIFIED_FENCE &&
                    (
                        !name.startsWith(CONSENT_REVOCATION_FENCE_PREFIX) ||
                            storage.readFence(name) != RawCollectionFence.CONSENT_REVOKED.name
                        )
            }
        ) return false
        if (!aead.createFreshAfterVerifiedPurge()) return false
        if (!storage.clearAllFences()) {
            storage.writeFenceAtomically(GLOBAL_DELETE_FENCE, RawCollectionFence.ACCOUNT_DELETED.name)
            return false
        }
        return owner.accountGeneration > 0L
    }

    private fun closePartialForConsentReceipt(consentReceiptSha256: String): Boolean {
        val collectionId = storage.readActiveCollectionId() ?: return true
        val manifest = readManifest(collectionId) ?: return storage.clearActiveCollectionId()
        if (manifest.consentReceiptSha256 != consentReceiptSha256) return true
        return storage.clearActiveCollectionId()
    }

    private fun reconcileActivePointer(): String? {
        val collectionId = storage.readActiveCollectionId() ?: return null
        val manifest = readManifest(collectionId)
        if (manifest?.state == RawManifestState.PARTIAL) return collectionId
        if (manifest == null) {
            discardCollectionAndPointer(collectionId)
            return null
        }
        return if (storage.clearActiveCollectionId()) null else collectionId
    }

    private fun isStoredChunkValid(
        manifest: RawCollectionManifest,
        metadata: RawChunkMetadata,
    ): Boolean {
        val envelope = storage.readChunk(manifest.collectionId, metadata.ordinal) ?: return false
        val opened = aead.open(
            envelope,
            chunkAad(manifest.collectionId, metadata),
            CHUNK_LIMITS,
        ) as? AeadOpenResult.Opened ?: return false
        return try {
            opened.plaintext.size == metadata.sizeBytes &&
                sha256Hex(opened.plaintext) == metadata.sha256
        } finally {
            opened.plaintext.fill(0)
        }
    }

    private fun discardCollectionAndPointer(collectionId: String): Boolean {
        if (!storage.deleteCollection(collectionId)) return false
        return storage.readActiveCollectionId() != collectionId || storage.clearActiveCollectionId()
    }

    private fun pruneExpiredLocked(now: Long): Int {
        if (now < 0L) return 0
        var deleted = 0
        storage.listCollectionIds().forEach { collectionId ->
            val manifest = readManifest(collectionId) ?: return@forEach
            if (now >= manifest.expiresAtEpochMs && storage.deleteCollection(collectionId)) {
                if (storage.readActiveCollectionId() == collectionId) {
                    storage.clearActiveCollectionId()
                }
                deleted += 1
            }
        }
        return deleted
    }

    private fun readManifest(collectionId: String): RawCollectionManifest? {
        val envelope = storage.readManifest(collectionId) ?: return null
        val opened = aead.open(envelope, manifestAad(collectionId), MANIFEST_LIMITS)
            as? AeadOpenResult.Opened
            ?: return null
        val manifest = decodeRawManifest(opened.plaintext)
        opened.plaintext.fill(0)
        return manifest?.takeIf { it.collectionId == collectionId }
    }

    private fun writeManifest(manifest: RawCollectionManifest): Boolean {
        val plaintext = encodeRawManifest(manifest)
        val sealed = aead.seal(plaintext, manifestAad(manifest.collectionId), MANIFEST_LIMITS)
        plaintext.fill(0)
        val envelope = (sealed as? AeadSealResult.Sealed)?.envelope ?: return false
        return storage.writeManifestAtomically(manifest.collectionId, envelope)
    }

    private companion object {
        fun manifestAad(collectionId: String): ByteArray =
            "$AAD_PREFIX|raw-collection|manifest|schema=2|collection=$collectionId"
                .toByteArray(Charsets.UTF_8)

        fun chunkAad(collectionId: String, metadata: RawChunkMetadata): ByteArray =
            (
                "$AAD_PREFIX|raw-collection|chunk|schema=2|collection=$collectionId" +
                    "|ordinal=${metadata.ordinal}|type=${metadata.type.wireName}"
                ).toByteArray(Charsets.UTF_8)

        fun revocationFence(consentReceiptSha256: String): String =
            "$CONSENT_REVOCATION_FENCE_PREFIX$consentReceiptSha256"

        fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

        const val AAD_PREFIX = "kr.co.hanium.dreamup.walksafe|USER"
        const val GLOBAL_DELETE_FENCE = "account-deleted"
        const val KEY_PURGE_VERIFIED_FENCE = "key-purge-verified"
        const val KEY_PURGE_VERIFIED_VALUE = "KEY_PURGE_VERIFIED"
        const val CONSENT_REVOCATION_FENCE_PREFIX = "consent-revoked-"
        val CHUNK_LIMITS = AeadLimits(
            maxPlaintextBytes = RAW_MAX_CHUNK_BYTES,
            maxCiphertextBytes = RAW_MAX_CHUNK_BYTES + 16,
            maxEnvelopeChars = 12 * 1_024 * 1_024,
        )
        val MANIFEST_LIMITS = AeadLimits(
            maxPlaintextBytes = 1 * 1_024 * 1_024,
            maxCiphertextBytes = 1 * 1_024 * 1_024 + 16,
            maxEnvelopeChars = 2 * 1_024 * 1_024,
        )
    }
}

internal val RAW_COLLECTION_KEY_POLICY = AeadKeyPolicy(
    aliasPrefix = "walksafe.user.raw_collection.aead.v",
    currentVersion = 1,
    readableVersions = setOf(1),
)

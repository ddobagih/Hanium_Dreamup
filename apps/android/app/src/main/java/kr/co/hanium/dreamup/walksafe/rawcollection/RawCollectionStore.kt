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

internal enum class RawReceiptCompletion {
    DELETED,
    INVALID_RECEIPT,
    ACKNOWLEDGED_PURGE_PENDING,
}

/** Crash-safe local raw collection core. Network upload is intentionally outside this class. */
internal class RawCollectionStore private constructor(
    private val storage: RawCollectionStorage,
    private val aead: LocalAead,
    private val nowMillis: () -> Long,
    private val capacityProfile: ApprovedRawQueueCapacityProfile?,
) {
    constructor(
        rootDirectory: File,
        aead: LocalAead = AndroidKeyStoreAead(RAW_COLLECTION_KEY_POLICY),
        nowMillis: () -> Long = System::currentTimeMillis,
        capacityProfile: ApprovedRawQueueCapacityProfile? = PRODUCTION_RAW_QUEUE_CAPACITY_PROFILE,
    ) : this(FileRawCollectionStorage(rootDirectory), aead, nowMillis, capacityProfile)

    internal constructor(
        storage: RawCollectionStorage,
        aead: LocalAead,
        nowMillis: () -> Long,
        testOnly: Unit = Unit,
        capacityProfile: ApprovedRawQueueCapacityProfile? = PRODUCTION_RAW_QUEUE_CAPACITY_PROFILE,
    ) : this(storage, aead, nowMillis, capacityProfile)

    private var expiredDiscards = 0L

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

    /**
     * 16-17 #18 (NPC-PHONE-QUEUE-CAPACITY rules[1..3]): before a new segment is created the store
     * cleans up in the policy order: temporary data (a non-active PARTIAL of another walk), then
     * server-acknowledged copies, then expired collections. Then it deletes what this device may
     * no longer keep or send (S1617-18-F1): collections of a revoked receipt (FP-015/FP-034: a
     * withdrawal stops collection and drops what was not sent), collections the server refused
     * for their bound consent, and, when [currentReceiptSha256] is known, collections whose
     * receipt is neither current, a continued predecessor, nor in [undecidedReceipts] (a receipt
     * change whose RAW continuity cannot be shown). It never deletes an unexpired, unsent COMPLETE
     * segment of a selectable receipt for space; if the queue is still full the new segment is
     * held instead.
     *
     * With an approved profile every live and quarantined collection counts against it. Without
     * one the uploadable sealed segments of every walk count ([RAW_NO_PROFILE_RETAINED_SEGMENTS],
     * the new ACTIVE one included): owner decision 3(b) sends an earlier walk's segment after the
     * walk under its own walk id, so it holds the slot until it is sent or expires. Quarantined
     * copies have their own bound ([RAW_NO_PROFILE_MAX_QUARANTINED_COLLECTIONS]).
     */
    @Synchronized
    fun admitNewSegment(
        walkSessionId: String,
        currentReceiptSha256: String? = null,
        undecidedReceipts: Set<String> = emptySet(),
    ): RawCapacityAdmission = admitNewSegmentLocked(walkSessionId, currentReceiptSha256, undecidedReceipts) {}

    /**
     * transfer-F3: [admitNewSegment] that also reports what happened to an earlier walk's unsealed
     * segment (sealed and kept, or deleted), so the caller can count the reason (16-17 #13).
     */
    @Synchronized
    fun admitNewSegmentReportingStale(
        walkSessionId: String,
        currentReceiptSha256: String? = null,
        undecidedReceipts: Set<String> = emptySet(),
    ): RawSegmentAdmission {
        var stale: RawStaleSegmentOutcome? = null
        val capacity = admitNewSegmentLocked(walkSessionId, currentReceiptSha256, undecidedReceipts) { sealed ->
            stale = if (sealed) RawStaleSegmentOutcome.SEALED else RawStaleSegmentOutcome.DISCARDED
        }
        return RawSegmentAdmission(capacity, stale)
    }

    private fun admitNewSegmentLocked(
        walkSessionId: String,
        currentReceiptSha256: String?,
        undecidedReceipts: Set<String>,
        onStaleSegment: (sealed: Boolean) -> Unit,
    ): RawCapacityAdmission {
        val now = nowMillis()
        if (now < 0L || storage.hasFence(GLOBAL_DELETE_FENCE)) return RawCapacityAdmission.HOLD_USAGE_UNKNOWN
        // S1617-18-F1: an ACTIVE aggregate of another walk is the unsealed rest of a walk that never
        // reached ENDED (the process ended first). S1617-13 / owner decision 3(b): that walk has
        // ended, so its segment is sealed for the post-walk upload as ENDED would seal it; one that
        // cannot be sealed is dropped. Either way its pointer no longer refuses later segments.
        storage.readActiveCollectionId()?.let { staleId ->
            val stale = readManifest(staleId)
            if (
                stale != null &&
                stale.state == RawManifestState.PARTIAL &&
                stale.walkSessionId != walkSessionId
            ) {
                val sealed = sealActiveSegment(stale.owner, stale.walkSessionId) != null
                if (!sealed) discardCollectionAndPointer(staleId)
                onStaleSegment(sealed)
            }
        }
        val activeId = storage.readActiveCollectionId()
        val manifests = storage.listCollectionIds().filter { it != activeId }.mapNotNull { id ->
            readManifest(id) ?: run {
                checkQuarantineUnreadable(id)
                null
            }
        }
        manifests.filter { it.state == RawManifestState.PARTIAL && it.walkSessionId != walkSessionId }
            .forEach { storage.deleteCollection(it.collectionId) }
        manifests.filter { it.uploadDisposition == RawUploadDisposition.ACKNOWLEDGED }
            .forEach { storage.deleteCollection(it.collectionId) }
        pruneExpiredLocked(now)
        val keptReceipts = currentReceiptSha256?.let { selectableReceiptsLocked(it) + undecidedReceipts }
        storage.listCollectionIds().filter { it != activeId }.forEach { id ->
            val manifest = readManifest(id) ?: run {
                checkQuarantineUnreadable(id)
                return@forEach
            }
            if (
                storage.hasFence(revocationFence(manifest.consentReceiptSha256)) ||
                manifest.uploadDisposition == RawUploadDisposition.CONSENT_REJECTED ||
                manifest.uploadDisposition == RawUploadDisposition.WALK_REJECTED ||
                (keptReceipts != null && manifest.consentReceiptSha256 !in keptReceipts)
            ) {
                storage.deleteCollection(id)
            }
        }
        val quarantined = storage.listQuarantinedCollectionIds().size
        val profile = capacityProfile
        if (profile == null) {
            if (quarantined >= RAW_NO_PROFILE_MAX_QUARANTINED_COLLECTIONS) {
                return RawCapacityAdmission.HOLD_QUARANTINE_LIMIT
            }
            val sealed = uploadableSealedCountLocked(currentReceiptSha256, activeId)
            return if (sealed < RAW_NO_PROFILE_RETAINED_SEGMENTS) RawCapacityAdmission.ALLOWED
            else RawCapacityAdmission.HOLD_NO_APPROVED_PROFILE
        }
        val live = storage.listCollectionIds().size
        if (live + quarantined + 1 > profile.maxSegments) return RawCapacityAdmission.HOLD_SEGMENT_LIMIT
        val usage = storage.measureUsage() ?: return RawCapacityAdmission.HOLD_USAGE_UNKNOWN
        if (usage.totalBytes > profile.maxTotalBytes - RAW_MAX_COLLECTION_BYTES) {
            return RawCapacityAdmission.HOLD_BYTE_LIMIT
        }
        return RawCapacityAdmission.ALLOWED
    }

    /**
     * S1617-18-F2: without an approved profile, whether sealing the ACTIVE aggregate now would
     * leave no slot for the next segment ([RAW_NO_PROFILE_RETAINED_SEGMENTS], all walks). The caller
     * then keeps merging instead of sealing at a boundary. Always false with an approved profile.
     */
    @Synchronized
    fun sealWouldUseNoProfileWalkBudget(walkSessionId: String, currentReceiptSha256: String): Boolean {
        if (capacityProfile != null) return false
        val sealed = uploadableSealedCountLocked(
            currentReceiptSha256,
            storage.readActiveCollectionId(),
        )
        return sealed + 1 >= RAW_NO_PROFILE_RETAINED_SEGMENTS
    }

    /** Receipts whose segments may still be uploaded: the current one and its continued predecessors. */
    private fun selectableReceiptsLocked(currentReceiptSha256: String): Set<String> =
        continuedReceiptsLocked() + currentReceiptSha256

    /**
     * Sealed, unsent segments the post-walk upload can still send (any walk, a selectable receipt).
     * With an unknown current receipt every non-revoked receipt counts (never under-counts).
     */
    private fun uploadableSealedCountLocked(
        currentReceiptSha256: String?,
        activeId: String?,
    ): Int {
        val receipts = currentReceiptSha256?.let(::selectableReceiptsLocked)
        return storage.listCollectionIds().filter { it != activeId }.count { id ->
            val manifest = readManifest(id) ?: return@count false
            manifest.uploadReady &&
                manifest.uploadDisposition == RawUploadDisposition.READY &&
                (receipts == null || manifest.consentReceiptSha256 in receipts) &&
                !storage.hasFence(revocationFence(manifest.consentReceiptSha256))
        }
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
            !validRuntimeBatch(items) ||
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

    /**
     * Write-new-then-drop-old replacement of the complete active PARTIAL (RAW-CORE-F1). The merged
     * [items] are stored completely under [newCollectionId] (same owner, walk, receipt, start and
     * expiry as the old aggregate, so a rewrite never extends retention), then the active pointer is
     * switched atomically and only then the old collection is deleted. On any failure before the
     * switch the old PARTIAL stays active and sealable and the new collection is removed. A crash
     * leaves at most one non-active PARTIAL, which is never uploaded and is discarded with the walk.
     */
    @Synchronized
    fun replaceActiveBatch(
        newCollectionId: String,
        owner: RawCollectionOwner,
        walkSessionId: String,
        consentReceiptSha256: String,
        rawConsentGranted: Boolean,
        walkState: RawWalkState,
        items: List<RawPlaintextBatchItem>,
    ): List<RawChunkMetadata>? {
        if (!rawConsentGranted || walkState != RawWalkState.ACTIVE) return null
        val now = nowMillis()
        if (
            now < 0L ||
            !isCanonicalUuid(newCollectionId) ||
            !validRuntimeBatch(items) ||
            storage.hasFence(GLOBAL_DELETE_FENCE) ||
            storage.hasFence(revocationFence(consentReceiptSha256))
        ) {
            return null
        }
        val oldId = reconcileActivePointer() ?: return null
        if (oldId == newCollectionId || storage.readManifest(newCollectionId) != null) return null
        val old = readManifest(oldId) ?: return null
        val capturedAtEpochMs = items.first().capturedAtEpochMs
        if (
            old.owner != owner ||
            old.walkSessionId != walkSessionId ||
            old.consentReceiptSha256 != consentReceiptSha256 ||
            old.state != RawManifestState.PARTIAL ||
            !old.chunks.isCompleteRuntimeBatch() ||
            now < old.capturedStartedAtEpochMs ||
            now >= old.expiresAtEpochMs ||
            capturedAtEpochMs < old.capturedStartedAtEpochMs ||
            capturedAtEpochMs > now
        ) {
            return null
        }
        val empty = old.copy(collectionId = newCollectionId, chunks = emptyList())
        if (!writeManifest(empty)) {
            storage.deleteCollection(newCollectionId)
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
        val written = items.zip(metadata).all { (item, chunkMetadata) ->
            val sealed = aead.seal(
                item.plaintext,
                chunkAad(newCollectionId, chunkMetadata),
                CHUNK_LIMITS,
            ) as? AeadSealResult.Sealed
            sealed != null &&
                storage.writeChunkAtomically(newCollectionId, chunkMetadata.ordinal, sealed.envelope)
        }
        if (!written || !writeManifest(empty.copy(chunks = metadata))) {
            storage.deleteCollection(newCollectionId)
            return null
        }
        if (!storage.writeActiveCollectionIdAtomically(newCollectionId)) {
            if (storage.readActiveCollectionId() != newCollectionId) {
                storage.deleteCollection(newCollectionId)
                return null
            }
            // The rename happened but its durability is unknown: keep the old collection too, so a
            // reverted pointer still finds a sealable aggregate.
            return metadata
        }
        // Best effort: an undeleted old PARTIAL is no longer active and is never uploaded.
        storage.deleteCollection(oldId)
        return metadata
    }

    @Synchronized
    fun activePartialManifest(
        owner: RawCollectionOwner,
        walkSessionId: String,
    ): RawCollectionManifest? {
        val now = nowMillis()
        if (now < 0L || storage.hasFence(GLOBAL_DELETE_FENCE)) return null
        pruneExpiredLocked(now)
        val collectionId = reconcileActivePointer() ?: return null
        return readManifest(collectionId)?.takeIf {
            it.owner == owner &&
                it.walkSessionId == walkSessionId &&
                it.state == RawManifestState.PARTIAL &&
                now < it.expiresAtEpochMs &&
                !storage.hasFence(revocationFence(it.consentReceiptSha256))
        }
    }

    /**
     * S1617-13 / owner decision 3(b): after the walk the active PARTIAL of [owner] belongs to an
     * ended walk (this one, or an earlier one whose process ended first), whatever its walk id.
     */
    @Synchronized
    fun activePartialManifestOfAnyWalk(owner: RawCollectionOwner): RawCollectionManifest? {
        val now = nowMillis()
        if (now < 0L || storage.hasFence(GLOBAL_DELETE_FENCE)) return null
        pruneExpiredLocked(now)
        val collectionId = reconcileActivePointer() ?: return null
        return readManifest(collectionId)?.takeIf {
            it.owner == owner &&
                it.state == RawManifestState.PARTIAL &&
                now < it.expiresAtEpochMs &&
                !storage.hasFence(revocationFence(it.consentReceiptSha256))
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
        val manifest = readManifest(collectionId) ?: return quarantineUnreadable(collectionId)
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
            quarantineUnreadable(collectionId)
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
        // Only retention is judged on the wall clock; a clock moved back after capture must not
        // turn an already captured segment into an unsealable orphan (16-17 #8).
        if (now >= manifest.expiresAtEpochMs) {
            storage.clearActiveCollectionId()
            storage.deleteCollection(collectionId)
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
        val manifest = readManifest(collectionId) ?: return quarantineUnreadable(collectionId)
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
        // 16-17 #14: every earlier receipt that continued into this chain loses its continuity too.
        // An unreadable fence listing still leaves this receipt fenced but reports failure.
        val predecessors = try {
            continuedReceiptsLocked()
        } catch (_: RawCollectionStorageReadException) {
            return false
        }
        val predecessorsFenced = predecessors.map { receipt ->
            storage.writeFenceAtomically(revocationFence(receipt), RawCollectionFence.CONSENT_REVOKED.name) &&
                closePartialForConsentReceipt(receipt)
        }.all { it }
        // S1617-14-F2 / S1617-18-F1 (FP-015, FP-034: a withdrawal stops collection and drops what
        // was not sent): the chain's stored segments are deleted now, not kept until retention
        // ends. The fences stay, so a failed delete is finished by the next admission cleanup.
        val deleted = deleteCollectionsOfReceiptsLocked(predecessors + consentReceiptSha256)
        return fenced && closed && predecessorsFenced && deleted
    }

    /**
     * S1617-18-F1: the server refused [collectionId]'s bound RAW consent (409), or (owner decision
     * 3(b)) will never accept its walk. The segment is marked so it is never selected again, then
     * deleted; a failed delete is finished by the next admission cleanup. A server-acknowledged
     * copy is left to its own purge.
     */
    @Synchronized
    fun discardConsentRejected(owner: RawCollectionOwner, collectionId: String): Boolean =
        discardRejected(owner, collectionId, RawUploadDisposition.CONSENT_REJECTED)

    /** transfer-F8: the Gateway will never accept the segment's walk; marked apart from a consent refusal. */
    @Synchronized
    fun discardWalkRejected(owner: RawCollectionOwner, collectionId: String): Boolean =
        discardRejected(owner, collectionId, RawUploadDisposition.WALK_REJECTED)

    private fun discardRejected(
        owner: RawCollectionOwner,
        collectionId: String,
        marker: RawUploadDisposition,
    ): Boolean {
        val local = readManifest(collectionId)?.takeIf { it.owner == owner } ?: return false
        if (local.uploadDisposition == RawUploadDisposition.ACKNOWLEDGED) return false
        if (local.uploadDisposition != marker) {
            writeManifest(local.copy(uploadDisposition = marker))
        }
        return discardCollectionAndPointer(collectionId)
    }

    private fun deleteCollectionsOfReceiptsLocked(receipts: Set<String>): Boolean = try {
        storage.listCollectionIds().map { collectionId ->
            val manifest = readManifest(collectionId) ?: return@map quarantineUnreadable(collectionId)
            manifest.consentReceiptSha256 !in receipts || discardCollectionAndPointer(collectionId)
        }.all { it }
    } catch (_: RawCollectionStorageReadException) {
        false
    }

    /**
     * 16-17 #14 (server contract, NPC-PERMISSION-SESSION-LIFECYCLE): a new receipt that keeps RAW
     * consent under the same policy and item versions does not end [previousReceipt]. Its stored
     * segments keep their original receipt and stay selectable until a later RAW withdrawal,
     * which revokes the whole chain. No manifest is rewritten with the new receipt.
     */
    @Synchronized
    fun onConsentContinued(previousReceipt: String, currentReceipt: String): Boolean {
        if (
            !SHA256_HEX.matches(previousReceipt) ||
            !SHA256_HEX.matches(currentReceipt) ||
            previousReceipt == currentReceipt ||
            storage.hasFence(GLOBAL_DELETE_FENCE) ||
            storage.hasFence(revocationFence(previousReceipt)) ||
            storage.hasFence(revocationFence(currentReceipt))
        ) return false
        return storage.writeFenceAtomically(
            continuityFence(previousReceipt),
            RawCollectionFence.CONSENT_CONTINUED.name,
        )
    }

    /** Earlier receipts whose RAW consent continued into the current chain (never revoked). */
    @Synchronized
    fun continuedReceipts(): Set<String> = continuedReceiptsLocked()

    /**
     * transfer-F1 (Root decision R3, FP-013 1.2.0): RAW continuity never spans a consent policy
     * version; the server ends it at any version change (`first_raw_collection_consent_interruption`).
     * The first call under [policyVersion] ends every continuity recorded before it (under an
     * earlier version, or by a build before this marker existed): those receipts are no longer
     * selectable, so their segments are held and never sent, and leave only by the ordinary cleanup
     * or the 30-day expiry. Nothing is deleted here. False when the store cannot be read or written.
     */
    @Synchronized
    fun alignConsentPolicy(policyVersion: String): Boolean {
        val marker = rawConsentPolicyFence(policyVersion) ?: return false
        if (storage.hasFence(marker)) return true
        val earlier = try {
            continuedReceiptsLocked()
        } catch (_: RawCollectionStorageReadException) {
            return false
        }
        val ended = earlier.map { receipt ->
            storage.writeFenceAtomically(revocationFence(receipt), RawCollectionFence.CONSENT_REVOKED.name)
        }.all { it }
        return ended &&
            storage.writeFenceAtomically(marker, RawCollectionFence.CONSENT_POLICY_ALIGNED.name)
    }

    private fun continuedReceiptsLocked(): Set<String> = storage.listFenceNames()
        .filter { it.startsWith(CONSENT_CONTINUITY_FENCE_PREFIX) }
        .map { it.removePrefix(CONSENT_CONTINUITY_FENCE_PREFIX) }
        .filter { SHA256_HEX.matches(it) && !storage.hasFence(revocationFence(it)) }
        .toSet()

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

    /** Collections deleted because their local retention ended, since this store was created. */
    @Synchronized
    fun expiredDiscardCount(): Long = expiredDiscards

    @Synchronized
    fun quarantinedCollectionCount(): Int = storage.listQuarantinedCollectionIds().size

    @Synchronized
    fun deleteAfterReceipt(
        owner: RawCollectionOwner,
        receipt: RawCollectionReceipt,
    ): Boolean = completeAfterReceipt(owner, receipt) == RawReceiptCompletion.DELETED

    @Synchronized
    fun completeAfterReceipt(
        owner: RawCollectionOwner,
        receipt: RawCollectionReceipt,
    ): RawReceiptCompletion {
        if (
            receipt.schemaVersion != BACKEND_RECEIPT_SCHEMA ||
            !isCanonicalUuid(receipt.collectionId) ||
            !SHA256_HEX.matches(receipt.manifestSha256) ||
            receipt.persistenceMarker != RAW_RECEIPT_PERSISTENCE_MARKER
        ) {
            return RawReceiptCompletion.INVALID_RECEIPT
        }
        val local = readManifest(receipt.collectionId)?.takeIf { it.owner == owner }
            ?: return RawReceiptCompletion.INVALID_RECEIPT
        val manifest = local.toBackendManifest() ?: return RawReceiptCompletion.INVALID_RECEIPT
        if (!validBackendReceipt(receipt, manifest)) return RawReceiptCompletion.INVALID_RECEIPT
        if (!writeManifest(local.copy(uploadDisposition = RawUploadDisposition.ACKNOWLEDGED))) {
            return RawReceiptCompletion.ACKNOWLEDGED_PURGE_PENDING
        }
        return if (deleteAcknowledged(owner, receipt.collectionId)) RawReceiptCompletion.DELETED
        else RawReceiptCompletion.ACKNOWLEDGED_PURGE_PENDING
    }

    @Synchronized
    fun deleteAcknowledged(owner: RawCollectionOwner, collectionId: String): Boolean {
        val local = readManifest(collectionId) ?: return false
        if (local.owner != owner || local.uploadDisposition != RawUploadDisposition.ACKNOWLEDGED) {
            return false
        }
        if (
            storage.readActiveCollectionId() == collectionId &&
            !storage.clearActiveCollectionId()
        ) {
            return false
        }
        return storage.deleteCollection(collectionId)
    }

    @Synchronized
    fun quarantine(owner: RawCollectionOwner, collectionId: String): Boolean {
        val local = uploadReadyManifest(collectionId, owner) ?: return false
        if (local.uploadDisposition == RawUploadDisposition.ACKNOWLEDGED) return false
        return writeManifest(local.copy(uploadDisposition = RawUploadDisposition.QUARANTINED))
    }

    @Synchronized
    fun discardPartials(
        owner: RawCollectionOwner,
        walkSessionId: String? = null,
    ): Int {
        if (walkSessionId != null && !isCanonicalUuid(walkSessionId)) return 0
        var discarded = 0
        storage.listCollectionIds().forEach { collectionId ->
            val manifest = readManifest(collectionId) ?: run {
                checkQuarantineUnreadable(collectionId)
                return@forEach
            }
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
        val fenceNames = try {
            storage.listFenceNames()
        } catch (_: RawCollectionStorageReadException) {
            return false
        }
        if (
            storage.readFence(GLOBAL_DELETE_FENCE) != RawCollectionFence.ACCOUNT_DELETED.name ||
            storage.readFence(KEY_PURGE_VERIFIED_FENCE) != KEY_PURGE_VERIFIED_VALUE ||
            !storage.verifiedEmptyAfterAccountPurge() ||
            fenceNames.any { name ->
                name != GLOBAL_DELETE_FENCE &&
                    name != KEY_PURGE_VERIFIED_FENCE &&
                    (
                        !name.startsWith(CONSENT_REVOCATION_FENCE_PREFIX) ||
                            storage.readFence(name) != RawCollectionFence.CONSENT_REVOKED.name
                        ) &&
                    (
                        !name.startsWith(CONSENT_CONTINUITY_FENCE_PREFIX) ||
                            storage.readFence(name) != RawCollectionFence.CONSENT_CONTINUED.name
                        ) &&
                    (
                        !name.startsWith(RAW_CONSENT_POLICY_FENCE_PREFIX) ||
                            storage.readFence(name) != RawCollectionFence.CONSENT_POLICY_ALIGNED.name
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
        val manifest = readManifest(collectionId) ?: return quarantineUnreadable(collectionId)
        if (manifest.consentReceiptSha256 != consentReceiptSha256) return true
        return storage.clearActiveCollectionId()
    }

    private fun validRuntimeBatch(items: List<RawPlaintextBatchItem>): Boolean =
        items.size == RAW_MAX_CHUNKS &&
            items.map(RawPlaintextBatchItem::type) == RAW_RUNTIME_CHUNK_TYPE_ORDER &&
            items.map(RawPlaintextBatchItem::type).distinct().size == items.size &&
            items.none { it.plaintext.isEmpty() || it.plaintext.size > RAW_MAX_CHUNK_BYTES } &&
            items.sumOf { it.plaintext.size.toLong() } <= RAW_MAX_COLLECTION_BYTES &&
            items.map(RawPlaintextBatchItem::capturedAtEpochMs).distinct().size == 1

    private fun reconcileActivePointer(): String? {
        val collectionId = storage.readActiveCollectionId() ?: return null
        val manifest = readManifest(collectionId)
        if (manifest?.state == RawManifestState.PARTIAL) return collectionId
        if (manifest == null) {
            checkQuarantineUnreadable(collectionId)
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

    private fun quarantineUnreadable(collectionId: String): Boolean =
        storage.quarantineCollection(collectionId) &&
            (storage.readActiveCollectionId() != collectionId || storage.clearActiveCollectionId())

    private fun checkQuarantineUnreadable(collectionId: String) {
        if (!quarantineUnreadable(collectionId)) {
            throw RawCollectionStorageReadException("unreadable collection quarantine")
        }
    }

    private fun pruneExpiredLocked(now: Long): Int {
        if (now < 0L) return 0
        var deleted = 0
        storage.listCollectionIds().forEach { collectionId ->
            val manifest = readManifest(collectionId) ?: run {
                checkQuarantineUnreadable(collectionId)
                return@forEach
            }
            if (now >= manifest.expiresAtEpochMs && storage.deleteCollection(collectionId)) {
                if (storage.readActiveCollectionId() == collectionId) {
                    storage.clearActiveCollectionId()
                }
                deleted += 1
            }
        }
        expiredDiscards += deleted
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

        fun continuityFence(consentReceiptSha256: String): String =
            "$CONSENT_CONTINUITY_FENCE_PREFIX$consentReceiptSha256"

        fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

        const val AAD_PREFIX = "kr.co.hanium.dreamup.walksafe|USER"
        const val GLOBAL_DELETE_FENCE = "account-deleted"
        const val KEY_PURGE_VERIFIED_FENCE = "key-purge-verified"
        const val KEY_PURGE_VERIFIED_VALUE = "KEY_PURGE_VERIFIED"
        const val CONSENT_REVOCATION_FENCE_PREFIX = "consent-revoked-"
        const val CONSENT_CONTINUITY_FENCE_PREFIX = "consent-continued-"
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

/** transfer-F1 (R3): marker fence of the consent policy version the RAW continuity was aligned to. */
internal const val RAW_CONSENT_POLICY_FENCE_PREFIX = "consent-policy-"

/** `FP-013-1.2.0` -> `consent-policy-fp-013-1-2-0`; null when it is not a bounded fence name. */
internal fun rawConsentPolicyFence(policyVersion: String): String? =
    (RAW_CONSENT_POLICY_FENCE_PREFIX + policyVersion.lowercase().replace(Regex("[^a-z0-9]"), "-"))
        .takeIf { policyVersion.isNotBlank() && Regex("[a-z0-9-]{1,96}").matches(it) }

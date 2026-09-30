package kr.co.hanium.dreamup.walksafe.rawcollection

import java.util.Base64
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.security.AeadBlockReason
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 16-17 #18 (NPC-PHONE-QUEUE-CAPACITY): policy cleanup order before a new segment, approved
 * limits from build injection only, and a hold instead of deleting unexpired unsent segments.
 */
class RawQueueCapacityStoreTest {
    @Test
    fun withoutAnApprovedProfileAWalkKeepsOneSealedSegmentPlusItsActiveOne() {
        // S1617-18-F1/F2: "sealed 1 + active 1" per walk; nothing unsent is deleted for space.
        val f = Fixture(profile = null)
        assertEquals(RawCapacityAdmission.ALLOWED, f.store.admitNewSegment(WALK_A, CONSENT))
        val first = f.completeSegment(WALK_A)
        assertEquals(RawCapacityAdmission.ALLOWED, f.store.admitNewSegment(WALK_A, CONSENT))
        assertTrue(f.store.sealWouldUseNoProfileWalkBudget(WALK_A, CONSENT))
        val second = f.completeSegment(WALK_A)
        assertEquals(RawCapacityAdmission.HOLD_NO_APPROVED_PROFILE, f.store.admitNewSegment(WALK_A, CONSENT))
        assertEquals(RawCapacityAdmission.HOLD_NO_APPROVED_PROFILE, f.store.admitNewSegment(WALK_A))
        assertEquals(setOf(first, second), f.store.readyManifests(OWNER).map { it.collectionId }.toSet())
    }

    @Test
    fun withoutAnApprovedProfileEarlierWalksSegmentsCountTowardTheRetainedBound() {
        // S1617-18 / owner decision 3(b): walk B's segment is sent after the walk under walk B's id,
        // so it holds a slot until it is sent or expires; it is never deleted for space.
        val f = Fixture(profile = null)
        val earlier = f.completeSegment(WALK_B)
        assertEquals(RawCapacityAdmission.ALLOWED, f.store.admitNewSegment(WALK_A, CONSENT))
        assertTrue(f.store.sealWouldUseNoProfileWalkBudget(WALK_A, CONSENT))
        val later = f.completeSegment(WALK_A)
        assertEquals(RawCapacityAdmission.HOLD_NO_APPROVED_PROFILE, f.store.admitNewSegment(WALK_A, CONSENT))
        assertEquals(setOf(earlier, later), f.storage.manifests.keys)
        assertEquals(RAW_NO_PROFILE_WALK_SEGMENTS, RAW_NO_PROFILE_RETAINED_SEGMENTS)
    }

    @Test
    fun revokedRefusedAndUnprovenReceiptCollectionsAreDeletedBeforeCounting() {
        val f = Fixture(profile = null)
        val revokedReceipt = "e".repeat(64)
        val otherReceipt = "d".repeat(64)
        f.completeSegment(WALK_A, receipt = revokedReceipt)
        check(f.storage.writeFenceAtomically("consent-revoked-$revokedReceipt", "CONSENT_REVOKED"))
        val refused = f.completeSegment(WALK_A)
        f.storage.failDeletes = true
        assertTrue(!f.store.discardConsentRejected(OWNER, refused))
        f.storage.failDeletes = false
        // A refused segment is never selected again, even before the delete is finished.
        assertTrue(f.store.readyManifests(OWNER).none { it.collectionId == refused })
        val unproven = f.completeSegment(WALK_A, receipt = otherReceipt)
        val kept = f.completeSegment(WALK_A)
        // An unknown current receipt deletes only what is revoked or refused (and counts the rest).
        assertEquals(RawCapacityAdmission.HOLD_NO_APPROVED_PROFILE, f.store.admitNewSegment(WALK_A))
        assertEquals(setOf(unproven, kept), f.storage.manifests.keys)
        // A receipt whose decision is still pending is kept but is not selectable, so not counted.
        assertEquals(
            RawCapacityAdmission.ALLOWED,
            f.store.admitNewSegment(WALK_A, CONSENT, undecidedReceipts = setOf(otherReceipt)),
        )
        assertEquals(setOf(unproven, kept), f.storage.manifests.keys)
        assertEquals(RawCapacityAdmission.ALLOWED, f.store.admitNewSegment(WALK_A, CONSENT))
        assertEquals(setOf(kept), f.storage.manifests.keys)
    }

    @Test
    fun aContinuedPredecessorsSegmentIsKeptAndCountsForTheWalk() {
        val f = Fixture(profile = null)
        val earlier = "d".repeat(64)
        val continued = f.completeSegment(WALK_A, receipt = earlier)
        assertTrue(f.store.onConsentContinued(earlier, CONSENT))
        f.completeSegment(WALK_A)
        assertEquals(RawCapacityAdmission.HOLD_NO_APPROVED_PROFILE, f.store.admitNewSegment(WALK_A, CONSENT))
        assertTrue(continued in f.storage.manifests)
    }

    @Test
    fun consentRevocationDeletesTheWholeChainsStoredSegments() {
        val f = Fixture(profile = null)
        val earlier = "d".repeat(64)
        f.completeSegment(WALK_A, receipt = earlier)
        assertTrue(f.store.onConsentContinued(earlier, CONSENT))
        f.completeSegment(WALK_A)
        val unrelated = f.completeSegment(WALK_B, receipt = "f".repeat(64))
        assertTrue(f.store.onConsentRevoked(CONSENT))
        assertEquals(setOf(unrelated), f.storage.manifests.keys)
        f.storage.failDeletes = true
        f.completeSegment(WALK_B, receipt = "f".repeat(64)).also {
            assertTrue(!f.store.onConsentRevoked("f".repeat(64)))
        }
        f.storage.failDeletes = false
        // The fence stays, so the next admission finishes the delete.
        f.store.admitNewSegment(WALK_A)
        assertTrue(f.storage.manifests.isEmpty())
    }

    @Test
    fun segmentLimitHoldsWithoutDeletingUnexpiredUnsentSegments() {
        val f = Fixture(profile = ApprovedRawQueueCapacityProfile(2, 64L * RAW_MAX_COLLECTION_BYTES))
        val first = f.completeSegment(WALK_A)
        assertEquals(RawCapacityAdmission.ALLOWED, f.store.admitNewSegment(WALK_A))
        val second = f.completeSegment(WALK_A)
        assertEquals(RawCapacityAdmission.HOLD_SEGMENT_LIMIT, f.store.admitNewSegment(WALK_A))
        assertEquals(setOf(first, second), f.store.readyManifests(OWNER).map { it.collectionId }.toSet())
    }

    @Test
    fun temporaryAcknowledgedAndExpiredCollectionsAreCleanedBeforeTheLimitIsJudged() {
        val f = Fixture(profile = ApprovedRawQueueCapacityProfile(2, 64L * RAW_MAX_COLLECTION_BYTES))
        // 1) temporary: a non-active PARTIAL left by another walk.
        f.partialLeftBy(WALK_B)
        // 2) a server-acknowledged copy whose local purge is still pending.
        val acknowledged = f.completeSegment(WALK_A)
        f.storage.failDeletes = true
        assertEquals(
            RawReceiptCompletion.ACKNOWLEDGED_PURGE_PENDING,
            f.store.completeAfterReceipt(OWNER, f.receiptFor(acknowledged)),
        )
        f.storage.failDeletes = false
        // 3) a segment that expires before the next admission.
        val expired = f.completeSegment(WALK_A, startedAt = BASE_EPOCH_MS - RAW_COLLECTION_TTL_MS + 30_000L)
        // 4) one unexpired, unsent segment that must survive.
        val unsent = f.completeSegment(WALK_A)
        assertEquals(4, f.storage.manifests.size)
        f.now = BASE_EPOCH_MS + 60_000L

        assertEquals(RawCapacityAdmission.ALLOWED, f.store.admitNewSegment(WALK_A))
        assertEquals(setOf(unsent), f.storage.manifests.keys)
        assertTrue(expired !in f.storage.manifests && acknowledged !in f.storage.manifests)
    }

    @Test
    fun quarantinedCollectionsAreOutsideTheNoProfileSlotButBoundedByCount() {
        // S1617-18-F1(c): an unreadable copy can never be uploaded, so it does not take the slot;
        // RAW_NO_PROFILE_MAX_QUARANTINED_COLLECTIONS of them hold new segments (fail closed).
        val f = Fixture(profile = null)
        f.storage.manifests[UUID.randomUUID().toString()] = "unreadable"
        assertEquals(RawCapacityAdmission.ALLOWED, f.store.admitNewSegment(WALK_A))
        assertEquals(0, f.storage.manifests.size)
        assertEquals(1, f.storage.quarantined.size)
        repeat(RAW_NO_PROFILE_MAX_QUARANTINED_COLLECTIONS - 1) {
            f.storage.manifests[UUID.randomUUID().toString()] = "unreadable"
        }
        assertEquals(RawCapacityAdmission.HOLD_QUARANTINE_LIMIT, f.store.admitNewSegment(WALK_A))
        assertEquals(RAW_NO_PROFILE_MAX_QUARANTINED_COLLECTIONS, f.storage.quarantined.size)
    }

    @Test
    fun withAnApprovedProfileQuarantinedCollectionsCountAgainstTheLimit() {
        val f = Fixture(profile = ApprovedRawQueueCapacityProfile(1, 64L * RAW_MAX_COLLECTION_BYTES))
        f.storage.manifests[UUID.randomUUID().toString()] = "unreadable"
        assertEquals(RawCapacityAdmission.HOLD_SEGMENT_LIMIT, f.store.admitNewSegment(WALK_A))
        assertEquals(1, f.storage.quarantined.size)
    }

    @Test
    fun byteLimitHoldsAndUnknownUsageFailsClosed() {
        val f = Fixture(profile = ApprovedRawQueueCapacityProfile(8, RAW_MAX_COLLECTION_BYTES))
        assertEquals(RawCapacityAdmission.ALLOWED, f.store.admitNewSegment(WALK_A))
        f.completeSegment(WALK_A)
        assertEquals(RawCapacityAdmission.HOLD_BYTE_LIMIT, f.store.admitNewSegment(WALK_A))
        val unknown = Fixture(profile = ApprovedRawQueueCapacityProfile(8, 64L * RAW_MAX_COLLECTION_BYTES))
        unknown.storage.usageUnknown = true
        assertEquals(RawCapacityAdmission.HOLD_USAGE_UNKNOWN, unknown.store.admitNewSegment(WALK_A))
    }

    @Test
    fun profileComesOnlyFromBuildInjectionAndInvalidValuesMeanNoProfile() {
        assertNull(approvedRawQueueCapacityProfile(enabled = false, maxSegments = 4, maxTotalBytes = 1L shl 30))
        assertNull(approvedRawQueueCapacityProfile(enabled = true, maxSegments = 0, maxTotalBytes = 1L shl 30))
        assertNull(approvedRawQueueCapacityProfile(enabled = true, maxSegments = 4, maxTotalBytes = RAW_MAX_COLLECTION_BYTES - 1L))
        assertNotNull(approvedRawQueueCapacityProfile(enabled = true, maxSegments = 4, maxTotalBytes = RAW_MAX_COLLECTION_BYTES))
        if (BuildConfig.WALKSAFE_RAW_QUEUE_MAX_SEGMENTS == 0 || BuildConfig.WALKSAFE_RAW_QUEUE_MAX_TOTAL_BYTES == 0L) {
            assertNull(PRODUCTION_RAW_QUEUE_CAPACITY_PROFILE)
        }
    }

    private class Fixture(profile: ApprovedRawQueueCapacityProfile?) {
        var now = BASE_EPOCH_MS
        val storage = CapacityFakeStorage()
        val store = RawCollectionStore(storage, CapacityFakeAead(), { now }, testOnly = Unit, capacityProfile = profile)

        fun completeSegment(walk: String, startedAt: Long = now - 30_000L, receipt: String = CONSENT): String {
            val id = UUID.randomUUID().toString()
            check(store.open(id, OWNER, walk, receipt, startedAt, rawConsentGranted = true, walkState = RawWalkState.ACTIVE))
            checkNotNull(store.appendBatch(OWNER, walk, true, RawWalkState.ACTIVE, items()))
            checkNotNull(store.sealActiveSegment(OWNER, walk))
            return id
        }

        fun partialLeftBy(walk: String): String {
            val id = UUID.randomUUID().toString()
            check(store.open(id, OWNER, walk, CONSENT, now - 30_000L, rawConsentGranted = true, walkState = RawWalkState.ACTIVE))
            checkNotNull(store.appendBatch(OWNER, walk, true, RawWalkState.ACTIVE, items()))
            check(store.onSessionEnded(OWNER, walk))
            return id
        }

        fun receiptFor(collectionId: String): RawCollectionReceipt {
            val manifest = checkNotNull(store.backendUploadManifest(collectionId, OWNER))
            val unsigned = RawCollectionReceipt(
                schemaVersion = BACKEND_RECEIPT_SCHEMA,
                collectionId = manifest.collectionId,
                manifestSha256 = manifest.manifestSha256,
                purpose = BACKEND_RAW_PURPOSE,
                persistenceMarker = RAW_RECEIPT_PERSISTENCE_MARKER,
                objectCount = manifest.objects.size,
                chunkCount = manifest.chunkCount,
                totalBytes = manifest.totalBytes,
                objects = manifest.objects,
                retentionClass = BACKEND_RETENTION_CLASS,
                committedAt = "2027-01-15T08:00:00Z",
                quarantineExpiresAt = "2027-01-29T08:00:00Z",
                receiptSha256 = "0".repeat(64),
            )
            return unsigned.copy(receiptSha256 = rawReceiptSha256(unsigned))
        }

        private fun items() = listOf(
            RawPlaintextBatchItem(RawChunkType.DETECTION, now - 1_000L, """{"kind":"DETECTION"}""".toByteArray()),
            RawPlaintextBatchItem(RawChunkType.PERFORMANCE, now - 1_000L, """{"kind":"PERFORMANCE"}""".toByteArray()),
        )
    }

    private class CapacityFakeStorage : RawCollectionStorage {
        var activeId: String? = null
        val manifests = mutableMapOf<String, String>()
        val chunks = mutableMapOf<Pair<String, Int>, String>()
        val quarantined = mutableSetOf<String>()
        private val fences = mutableMapOf<String, String>()
        var failDeletes = false
        var usageUnknown = false

        override fun readActiveCollectionId(): String? = activeId
        override fun writeActiveCollectionIdAtomically(collectionId: String): Boolean {
            activeId = collectionId
            return true
        }
        override fun clearActiveCollectionId(): Boolean {
            activeId = null
            return true
        }
        override fun readManifest(collectionId: String): String? = manifests[collectionId]
        override fun writeManifestAtomically(collectionId: String, envelope: String): Boolean {
            manifests[collectionId] = envelope
            return true
        }
        override fun readChunk(collectionId: String, ordinal: Int): String? = chunks[collectionId to ordinal]
        override fun writeChunkAtomically(collectionId: String, ordinal: Int, envelope: String): Boolean {
            chunks[collectionId to ordinal] = envelope
            return true
        }
        override fun deleteChunk(collectionId: String, ordinal: Int): Boolean = chunks.remove(collectionId to ordinal) != null
        override fun listCollectionIds(): Set<String> = manifests.keys.toSet()
        override fun quarantineCollection(collectionId: String): Boolean {
            if (manifests.remove(collectionId) == null) return collectionId in quarantined
            chunks.keys.removeAll { it.first == collectionId }
            quarantined += collectionId
            if (activeId == collectionId) activeId = null
            return true
        }
        override fun listQuarantinedCollectionIds(): Set<String> = quarantined.toSet()
        override fun deleteCollection(collectionId: String): Boolean {
            if (failDeletes) return false
            manifests.remove(collectionId)
            chunks.keys.removeAll { it.first == collectionId }
            return true
        }
        override fun deleteAllCollections(): Boolean {
            manifests.clear()
            chunks.clear()
            quarantined.clear()
            return true
        }
        override fun verifiedEmptyAfterAccountPurge(): Boolean = manifests.isEmpty() && chunks.isEmpty()
        override fun hasFence(name: String): Boolean = name in fences
        override fun readFence(name: String): String? = fences[name]
        override fun writeFenceAtomically(name: String, value: String): Boolean {
            fences[name] = value
            return true
        }
        override fun listFenceNames(): Set<String> = fences.keys.toSet()
        override fun clearAllFences(): Boolean {
            fences.clear()
            return true
        }
        override fun measureUsage(): RawStorageUsage? = if (usageUnknown) null else RawStorageUsage(
            liveCollections = manifests.size,
            quarantinedCollections = quarantined.size,
            totalBytes = (manifests.values + chunks.values).sumOf { it.length.toLong() } +
                // Make every stored collection weigh at least one maximum segment.
                manifests.size * RAW_MAX_COLLECTION_BYTES,
        )
    }

    private class CapacityFakeAead : LocalAead {
        override fun seal(plaintext: ByteArray, domainAad: ByteArray, limits: AeadLimits): AeadSealResult =
            AeadSealResult.Sealed("fake:" + Base64.getEncoder().encodeToString(plaintext))
        override fun open(envelope: String, domainAad: ByteArray, limits: AeadLimits): AeadOpenResult =
            if (!envelope.startsWith("fake:")) AeadOpenResult.Blocked(AeadBlockReason.MALFORMED_ENVELOPE)
            else AeadOpenResult.Opened(Base64.getDecoder().decode(envelope.removePrefix("fake:")), 1, false)
        override fun destroyVersion(version: Int): Boolean = true
        override fun destroyKnownVersions(): Boolean = true
        override fun createFreshAfterVerifiedPurge(): Boolean = true
    }

    private companion object {
        const val WALK_A = "123e4567-e89b-42d3-a456-426614174001"
        const val WALK_B = "123e4567-e89b-42d3-a456-426614174003"
        const val BASE_EPOCH_MS = 1_800_000_000_000L
        val CONSENT = "c".repeat(64)
        val OWNER = RawCollectionOwner("123e4567-e89b-42d3-a456-426614174002", 1L, "device-installation-00000001")
    }
}

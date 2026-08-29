package kr.co.hanium.dreamup.walksafe.rawcollection

import kr.co.hanium.dreamup.walksafe.security.AeadBlockReason
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawCollectionStoreTest {
    @Test
    fun ownerBoundActiveWalkAcceptsOnlyOneDetectionOrPerformanceMetadataChunk() {
        val fixture = fixture()
        assertTrue(fixture.open())

        assertNull(fixture.append(type = RawChunkType.VIDEO))
        assertNull(fixture.append(type = RawChunkType.AUDIO))
        assertNull(fixture.append(type = RawChunkType.EXACT_LOCATION))
        assertNull(fixture.append(type = RawChunkType.ROUTE))
        assertNull(fixture.append(type = RawChunkType.SENSOR))
        assertNull(fixture.append(type = RawChunkType.REPORT))
        assertEquals(
            setOf(RawChunkType.DETECTION, RawChunkType.PERFORMANCE),
            RAW_RUNTIME_CHUNK_TYPES,
        )
        val first = fixture.append(type = RawChunkType.DETECTION)

        assertEquals(0, first?.ordinal)
        assertEquals(RawChunkType.DETECTION, first?.type)
        assertNull(fixture.append(type = RawChunkType.PERFORMANCE))
        assertNull(
            fixture.store.append(
                owner = OWNER.copy(deviceId = OTHER_DEVICE_ID),
                walkSessionId = WALK_ID,
                rawConsentGranted = true,
                walkState = RawWalkState.ACTIVE,
                type = RawChunkType.DETECTION,
                capturedAtEpochMs = fixture.now,
                plaintext = "wrong-owner".toByteArray(),
            ),
        )
        assertNull(
            fixture.store.append(
                owner = OWNER,
                walkSessionId = WALK_ID,
                rawConsentGranted = true,
                walkState = RawWalkState.ENDED,
                type = RawChunkType.DETECTION,
                capturedAtEpochMs = fixture.now,
                plaintext = "after-end".toByteArray(),
            ),
        )
        assertEquals(COLLECTION_1, fixture.storage.activeCollectionId)
    }

    @Test
    fun pauseSealProducesExactlyOneBackendObjectAndExplicitOrdinalMapping() {
        val fixture = fixture()
        assertTrue(fixture.open())
        assertNotNull(fixture.append(type = RawChunkType.PERFORMANCE))
        fixture.now += 1L

        val sealed = fixture.store.sealActiveSegment(OWNER, WALK_ID)
        assertNotNull(sealed)
        assertNull(fixture.storage.activeCollectionId)
        assertEquals(listOf(COLLECTION_1), fixture.store.readyManifests(OWNER).map { it.collectionId })
        assertTrue(fixture.store.readyManifests(OTHER_OWNER).isEmpty())

        val backend = requireNotNull(fixture.store.backendUploadManifest(COLLECTION_1, OWNER))
        assertEquals(1, backend.objects.size)
        assertEquals(1, backend.chunkCount)
        assertEquals(1, backend.chunkBindings.size)
        assertEquals(0, backend.chunkBindings.single().localOrdinal)
        assertEquals(0, backend.chunkBindings.single().chunkIndex)
        val json = org.json.JSONObject(String(backend.jsonUtf8, Charsets.UTF_8))
        assertEquals(1, json.getInt("object_count"))
        assertEquals(1, json.getInt("chunk_count"))
        assertEquals(1, json.getJSONArray("objects").length())
        assertEquals(1, json.getJSONArray("objects").getJSONObject(0).getJSONArray("chunks").length())

        val restored = requireNotNull(
            fixture.store.readUploadChunk(COLLECTION_1, OWNER, backend.chunkBindings.single().localOrdinal),
        )
        assertArrayEquals(PAYLOAD.toByteArray(), restored.plaintext)
        restored.plaintext.fill(0)
    }

    @Test
    fun endedWalkNeverSealsAndResidualPartialRequiresExplicitDiscard() {
        val fixture = fixture()
        assertTrue(fixture.open())
        assertNotNull(fixture.append())

        assertTrue(fixture.store.onSessionEnded(OWNER, WALK_ID))
        assertNull(fixture.storage.activeCollectionId)
        assertNull(fixture.store.uploadReadyManifest(COLLECTION_1, OWNER))
        assertTrue(fixture.storage.manifests.containsKey(COLLECTION_1))
        assertEquals(1, fixture.store.discardPartials(OWNER, WALK_ID))
        assertFalse(fixture.storage.manifests.containsKey(COLLECTION_1))
        assertEquals(0, fixture.store.discardPartials(OWNER, WALK_ID))
    }

    @Test
    fun completeCollectionDeletesOnlyAfterExactBackendReceiptForSameOwner() {
        val fixture = fixture()
        assertTrue(fixture.open())
        assertNotNull(fixture.append())
        fixture.now += 1L
        assertNotNull(fixture.store.sealActiveSegment(OWNER, WALK_ID))
        val manifest = requireNotNull(fixture.store.backendUploadManifest(COLLECTION_1, OWNER))
        val receipt = exactReceipt(manifest)

        assertFalse(fixture.store.deleteAfterReceipt(OTHER_OWNER, receipt))
        assertFalse(
            fixture.store.deleteAfterReceipt(
                OWNER,
                signReceipt(receipt.copy(totalBytes = receipt.totalBytes + 1L)),
            ),
        )
        assertNotNull(fixture.store.uploadReadyManifest(COLLECTION_1, OWNER))
        assertTrue(fixture.store.deleteAfterReceipt(OWNER, receipt))
        assertNull(fixture.store.uploadReadyManifest(COLLECTION_1, OWNER))
    }

    @Test
    fun legacyCommittedReceiptRemainsReadableForExactLocalCleanup() {
        val fixture = fixture()
        assertTrue(fixture.open())
        assertNotNull(fixture.append())
        fixture.now += 1L
        assertNotNull(fixture.store.sealActiveSegment(OWNER, WALK_ID))
        val manifest = requireNotNull(fixture.store.backendUploadManifest(COLLECTION_1, OWNER))
        val legacy = signReceipt(
            exactReceipt(manifest).copy(
                schemaVersion = LEGACY_BACKEND_RECEIPT_SCHEMA,
                retentionClass = LEGACY_BACKEND_RETENTION_CLASS,
                retentionExpiresAt = "2027-02-25T00:00:00Z",
                quarantineExpiresAt = null,
                receiptSha256 = "0".repeat(64),
            ),
        )

        assertTrue(validBackendReceipt(legacy, manifest))
        assertTrue(fixture.store.deleteAfterReceipt(OWNER, legacy))
    }

    @Test
    fun consentAndAccountFencesBlockReadsAndNewEnrollmentNeedsVerifiedPurge() {
        val consentFixture = fixture()
        assertTrue(consentFixture.open())
        assertNotNull(consentFixture.append())
        consentFixture.now += 1L
        assertNotNull(consentFixture.store.sealActiveSegment(OWNER, WALK_ID))
        assertTrue(consentFixture.store.onConsentRevoked(CONSENT_SHA))
        assertNull(consentFixture.store.uploadReadyManifest(COLLECTION_1, OWNER))

        val fixture = fixture()
        assertTrue(fixture.open())
        assertNotNull(fixture.append())
        assertTrue(fixture.store.onAccountDeleted())
        assertTrue(fixture.aead.destroyed)
        assertTrue(fixture.storage.manifests.isEmpty())
        assertFalse(fixture.open(collectionId = COLLECTION_2, owner = OTHER_OWNER))
        assertTrue(fixture.store.resetForNewEnrollment(OTHER_OWNER))
        assertTrue(fixture.aead.freshCreated)
        assertTrue(fixture.open(collectionId = COLLECTION_2, owner = OTHER_OWNER))
    }

    @Test
    fun resetRejectsMissingOrUnknownVerifiedPurgeArtifacts() {
        listOf("missing-marker", "unknown-fence", "collection-artifact").forEach { case ->
            val fixture = fixture()
            assertTrue(fixture.store.onAccountDeleted())
            when (case) {
                "missing-marker" -> fixture.storage.fences.remove("key-purge-verified")
                "unknown-fence" -> fixture.storage.fences["unexpected"] = "UNKNOWN"
                "collection-artifact" -> fixture.storage.purgeVerified = false
            }
            assertFalse(fixture.store.resetForNewEnrollment(OTHER_OWNER))
        }
    }

    private fun fixture(): Fixture {
        val storage = FakeRawCollectionStorage()
        val aead = FakeRawAead()
        val fixture = Fixture(storage, aead, STARTED_AT)
        fixture.store = RawCollectionStore(
            storage = storage,
            aead = aead,
            nowMillis = { fixture.now },
            testOnly = Unit,
        )
        return fixture
    }

    private fun exactReceipt(manifest: BackendRawManifest): RawCollectionReceipt = signReceipt(
        RawCollectionReceipt(
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
            committedAt = "2026-08-29T00:00:00Z",
            quarantineExpiresAt = "2026-09-12T00:00:00Z",
            receiptSha256 = "0".repeat(64),
        ),
    )

    private fun signReceipt(receipt: RawCollectionReceipt): RawCollectionReceipt =
        receipt.copy(receiptSha256 = rawReceiptSha256(receipt))

    private companion object {
        const val STARTED_AT = 10_000L
        const val COLLECTION_1 = "123e4567-e89b-42d3-a456-426614174000"
        const val COLLECTION_2 = "123e4567-e89b-42d3-a456-426614174010"
        const val WALK_ID = "123e4567-e89b-42d3-a456-426614174001"
        const val ACTOR_ID = "123e4567-e89b-42d3-a456-426614174002"
        const val DEVICE_ID = "device-installation-00000001"
        const val OTHER_DEVICE_ID = "device-installation-00000002"
        const val PAYLOAD = "aggregate-metadata"
        val CONSENT_SHA = "c".repeat(64)
        val OWNER = RawCollectionOwner(ACTOR_ID, 1L, DEVICE_ID)
        val OTHER_OWNER = RawCollectionOwner(ACTOR_ID, 2L, OTHER_DEVICE_ID)
    }
}

private class Fixture(
    val storage: FakeRawCollectionStorage,
    val aead: FakeRawAead,
    var now: Long,
) {
    lateinit var store: RawCollectionStore

    fun open(
        collectionId: String = "123e4567-e89b-42d3-a456-426614174000",
        owner: RawCollectionOwner = DEFAULT_OWNER,
    ): Boolean = store.open(
        collectionId = collectionId,
        owner = owner,
        walkSessionId = DEFAULT_WALK_ID,
        consentReceiptSha256 = "c".repeat(64),
        rawConsentGranted = true,
        walkState = RawWalkState.ACTIVE,
    )

    fun append(type: RawChunkType = RawChunkType.DETECTION): RawChunkMetadata? = store.append(
        owner = DEFAULT_OWNER,
        walkSessionId = DEFAULT_WALK_ID,
        rawConsentGranted = true,
        walkState = RawWalkState.ACTIVE,
        type = type,
        capturedAtEpochMs = now,
        plaintext = "aggregate-metadata".toByteArray(),
    )

    private companion object {
        const val DEFAULT_WALK_ID = "123e4567-e89b-42d3-a456-426614174001"
        val DEFAULT_OWNER = RawCollectionOwner(
            "123e4567-e89b-42d3-a456-426614174002",
            1L,
            "device-installation-00000001",
        )
    }
}

private class FakeRawCollectionStorage : RawCollectionStorage {
    var activeCollectionId: String? = null
    val manifests = mutableMapOf<String, String>()
    val chunks = mutableMapOf<Pair<String, Int>, String>()
    val fences = mutableMapOf<String, String>()
    var purgeVerified = true

    override fun readActiveCollectionId(): String? = activeCollectionId
    override fun writeActiveCollectionIdAtomically(collectionId: String): Boolean {
        activeCollectionId = collectionId
        return true
    }
    override fun clearActiveCollectionId(): Boolean {
        activeCollectionId = null
        return true
    }
    override fun readManifest(collectionId: String): String? = manifests[collectionId]
    override fun writeManifestAtomically(collectionId: String, envelope: String): Boolean {
        manifests[collectionId] = envelope
        return true
    }
    override fun readChunk(collectionId: String, ordinal: Int): String? =
        chunks[collectionId to ordinal]
    override fun writeChunkAtomically(collectionId: String, ordinal: Int, envelope: String): Boolean {
        chunks[collectionId to ordinal] = envelope
        return true
    }
    override fun deleteChunk(collectionId: String, ordinal: Int): Boolean =
        chunks.remove(collectionId to ordinal) != null
    override fun listCollectionIds(): Set<String> = manifests.keys.toSet()
    override fun deleteCollection(collectionId: String): Boolean {
        manifests.remove(collectionId)
        chunks.keys.removeAll { it.first == collectionId }
        return true
    }
    override fun deleteAllCollections(): Boolean {
        manifests.clear()
        chunks.clear()
        activeCollectionId = null
        return true
    }
    override fun verifiedEmptyAfterAccountPurge(): Boolean =
        purgeVerified && activeCollectionId == null && manifests.isEmpty() && chunks.isEmpty()
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
}

private class FakeRawAead : LocalAead {
    private val values = mutableMapOf<String, ByteArray>()
    private var nextId = 0
    var destroyed = false
    var freshCreated = false

    override fun seal(
        plaintext: ByteArray,
        domainAad: ByteArray,
        limits: AeadLimits,
    ): AeadSealResult {
        if (plaintext.size > limits.maxPlaintextBytes) {
            return AeadSealResult.Blocked(AeadBlockReason.PLAINTEXT_TOO_LARGE)
        }
        val envelope = "fake-aead-${nextId++}"
        values[envelope] = plaintext.copyOf()
        return AeadSealResult.Sealed(envelope)
    }

    override fun open(
        envelope: String,
        domainAad: ByteArray,
        limits: AeadLimits,
    ): AeadOpenResult {
        val plaintext = values[envelope]?.copyOf()
            ?: return AeadOpenResult.Blocked(AeadBlockReason.MALFORMED_ENVELOPE)
        return AeadOpenResult.Opened(plaintext, keyVersion = 1, needsRewrap = false)
    }

    override fun destroyVersion(version: Int): Boolean = true
    override fun destroyKnownVersions(): Boolean {
        destroyed = true
        values.clear()
        return true
    }
    override fun createFreshAfterVerifiedPurge(): Boolean {
        freshCreated = true
        return true
    }
}

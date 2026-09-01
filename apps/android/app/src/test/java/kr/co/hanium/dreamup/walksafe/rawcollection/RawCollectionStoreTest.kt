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
    fun ownerBoundActiveWalkAcceptsOnlyExactDetectionPerformanceBatch() {
        val fixture = fixture()
        assertTrue(fixture.open())

        listOf(
            RawChunkType.VIDEO,
            RawChunkType.AUDIO,
            RawChunkType.EXACT_LOCATION,
            RawChunkType.ROUTE,
            RawChunkType.SENSOR,
            RawChunkType.REPORT,
        ).forEach { sensitiveType ->
            assertNull(
                fixture.appendBatch(
                    types = listOf(RawChunkType.DETECTION, sensitiveType),
                ),
            )
        }
        assertEquals(
            setOf(RawChunkType.DETECTION, RawChunkType.PERFORMANCE),
            RAW_RUNTIME_CHUNK_TYPES,
        )
        val batch = fixture.appendBatch()

        assertEquals(listOf(0, 1), batch?.map(RawChunkMetadata::ordinal))
        assertEquals(RAW_RUNTIME_CHUNK_TYPE_ORDER, batch?.map(RawChunkMetadata::type))
        assertNull(fixture.appendBatch())
        assertNull(
            fixture.store.appendBatch(
                owner = OWNER.copy(deviceId = OTHER_DEVICE_ID),
                walkSessionId = WALK_ID,
                rawConsentGranted = true,
                walkState = RawWalkState.ACTIVE,
                items = fixture.items(),
            ),
        )
        assertNull(
            fixture.store.appendBatch(
                owner = OWNER,
                walkSessionId = WALK_ID,
                rawConsentGranted = true,
                walkState = RawWalkState.ENDED,
                items = fixture.items(),
            ),
        )
        assertEquals(COLLECTION_1, fixture.storage.activeCollectionId)
    }

    @Test
    fun duplicateThirdAndOversizedBatchItemsAreRejectedWithoutWritingChunks() {
        listOf(
            listOf(RawChunkType.DETECTION, RawChunkType.DETECTION),
            listOf(RawChunkType.DETECTION, RawChunkType.PERFORMANCE, RawChunkType.DETECTION),
        ).forEach { types ->
            val fixture = fixture()
            assertTrue(fixture.open())
            assertNull(fixture.appendBatch(types = types))
            assertTrue(fixture.storage.chunks.isEmpty())
        }

        val oversized = fixture()
        assertTrue(oversized.open())
        assertNull(
            oversized.store.appendBatch(
                owner = OWNER,
                walkSessionId = WALK_ID,
                rawConsentGranted = true,
                walkState = RawWalkState.ACTIVE,
                items = listOf(
                    RawPlaintextBatchItem(
                        RawChunkType.DETECTION,
                        oversized.now,
                        ByteArray(RAW_MAX_CHUNK_BYTES + 1),
                    ),
                    RawPlaintextBatchItem(
                        RawChunkType.PERFORMANCE,
                        oversized.now,
                        byteArrayOf(1),
                    ),
                ),
            ),
        )
        assertTrue(oversized.storage.chunks.isEmpty())
    }

    @Test
    fun secondChunkWriteFailureDiscardsTheWholePartialBatch() {
        val fixture = fixture()
        assertTrue(fixture.open())
        fixture.storage.failChunkOrdinal = 1

        assertNull(fixture.appendBatch())

        assertNull(fixture.storage.activeCollectionId)
        assertTrue(fixture.storage.manifests.isEmpty())
        assertTrue(fixture.storage.chunks.isEmpty())
    }

    @Test
    fun pauseSealProducesTwoBackendObjectsAndExplicitOrdinalMappings() {
        val fixture = fixture()
        assertTrue(fixture.open())
        assertNotNull(fixture.appendBatch())
        fixture.now += 1L

        val sealed = fixture.store.sealActiveSegment(OWNER, WALK_ID)
        assertNotNull(sealed)
        assertNull(fixture.storage.activeCollectionId)
        assertEquals(listOf(COLLECTION_1), fixture.store.readyManifests(OWNER).map { it.collectionId })
        assertTrue(fixture.store.readyManifests(OTHER_OWNER).isEmpty())

        val backend = requireNotNull(fixture.store.backendUploadManifest(COLLECTION_1, OWNER))
        assertEquals(2, backend.objects.size)
        assertEquals(2, backend.chunkCount)
        assertEquals(2, backend.chunkBindings.size)
        assertEquals(listOf(0, 1), backend.chunkBindings.map { it.localOrdinal })
        assertEquals(listOf(0, 0), backend.chunkBindings.map { it.chunkIndex })
        assertEquals(
            listOf("DETECTION", "PERFORMANCE"),
            backend.chunkBindings.sortedBy(BackendRawChunkBinding::localOrdinal).map { binding ->
                backend.objects.single { it.objectId == binding.objectId }.kind
            },
        )
        val json = org.json.JSONObject(String(backend.jsonUtf8, Charsets.UTF_8))
        assertEquals(2, json.getInt("object_count"))
        assertEquals(2, json.getInt("chunk_count"))
        assertEquals(2, json.getJSONArray("objects").length())
        assertTrue(
            (0 until 2).all { index ->
                json.getJSONArray("objects")
                    .getJSONObject(index)
                    .getJSONArray("chunks")
                    .length() == 1
            },
        )

        backend.chunkBindings.forEach { binding ->
            val restored = requireNotNull(
                fixture.store.readUploadChunk(COLLECTION_1, OWNER, binding.localOrdinal),
            )
            assertArrayEquals(PAYLOAD.toByteArray(), restored.plaintext)
            restored.plaintext.fill(0)
        }
    }

    @Test
    fun endedWalkNeverSealsAndResidualPartialRequiresExplicitDiscard() {
        val fixture = fixture()
        assertTrue(fixture.open())
        assertNotNull(fixture.appendBatch())

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
        assertNotNull(fixture.appendBatch())
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
    fun legacyReceiptNeverDeletesACollectionThatRequiresAnExactV2Receipt() {
        val fixture = fixture()
        assertTrue(fixture.open())
        assertNotNull(fixture.appendBatch())
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
        assertFalse(fixture.store.deleteAfterReceipt(OWNER, legacy))
        assertNotNull(fixture.store.uploadReadyManifest(COLLECTION_1, OWNER))
    }

    @Test
    fun legacySingleObjectV2ManifestRemainsDecodableButNeverUploadReady() {
        val legacy = RawCollectionManifest(
            collectionId = COLLECTION_1,
            owner = OWNER,
            walkSessionId = WALK_ID,
            consentReceiptSha256 = CONSENT_SHA,
            capturedStartedAtEpochMs = STARTED_AT,
            capturedEndedAtEpochMs = STARTED_AT + 1L,
            expiresAtEpochMs = STARTED_AT + RAW_COLLECTION_TTL_MS,
            state = RawManifestState.COMPLETE,
            chunks = listOf(
                RawChunkMetadata(
                    ordinal = 0,
                    type = RawChunkType.DETECTION,
                    capturedAtEpochMs = STARTED_AT,
                    sizeBytes = PAYLOAD.length,
                    sha256 = "a".repeat(64),
                ),
            ),
        )

        val decoded = requireNotNull(decodeRawManifest(encodeRawManifest(legacy)))

        assertFalse(decoded.uploadReady)
        assertNull(decoded.toBackendManifest())
    }

    @Test
    fun consentAndAccountFencesBlockReadsAndNewEnrollmentNeedsVerifiedPurge() {
        val consentFixture = fixture()
        assertTrue(consentFixture.open())
        assertNotNull(consentFixture.appendBatch())
        consentFixture.now += 1L
        assertNotNull(consentFixture.store.sealActiveSegment(OWNER, WALK_ID))
        assertTrue(consentFixture.store.onConsentRevoked(CONSENT_SHA))
        assertNull(consentFixture.store.uploadReadyManifest(COLLECTION_1, OWNER))

        val fixture = fixture()
        assertTrue(fixture.open())
        assertNotNull(fixture.appendBatch())
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

    fun items(
        types: List<RawChunkType> = RAW_RUNTIME_CHUNK_TYPE_ORDER,
    ): List<RawPlaintextBatchItem> = types.map { type ->
        RawPlaintextBatchItem(
            type = type,
            capturedAtEpochMs = now,
            plaintext = "aggregate-metadata".toByteArray(),
        )
    }

    fun appendBatch(
        types: List<RawChunkType> = RAW_RUNTIME_CHUNK_TYPE_ORDER,
    ): List<RawChunkMetadata>? = store.appendBatch(
        owner = DEFAULT_OWNER,
        walkSessionId = DEFAULT_WALK_ID,
        rawConsentGranted = true,
        walkState = RawWalkState.ACTIVE,
        items = items(types),
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
    var failChunkOrdinal: Int? = null

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
        if (ordinal == failChunkOrdinal) return false
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

package kr.co.hanium.dreamup.walksafe.rawcollection

import java.io.File
import java.nio.file.Files
import java.util.Base64
import kr.co.hanium.dreamup.walksafe.security.AeadBlockReason
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RawCollectionRecoveryStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun malformedOrphanIsIsolatedWithoutLosingCiphertextOrBlockingValidCollection() {
        val root = temporary.newFolder()
        val storage = FileRawCollectionStorage(root)
        val store = RawCollectionStore(root, RecoveryStorageAead(), { NOW })
        seedComplete(store)
        assertTrue(storage.writeManifestAtomically(ORPHAN, "not-an-envelope"))
        assertTrue(storage.writeChunkAtomically(ORPHAN, 0, "encrypted-orphan-chunk"))

        assertEquals(listOf(VALID), store.readyManifests(OWNER).map { it.collectionId })
        assertEquals(setOf(ORPHAN), storage.listQuarantinedCollectionIds())
        assertEquals(1, store.quarantinedCollectionCount())
        assertEquals("not-an-envelope", isolated(root, "manifest.aead").readText())
        assertEquals("encrypted-orphan-chunk", isolated(root, "chunk-0000.aead").readText())
        assertNull(storage.readManifest(ORPHAN))
        assertEquals(0, store.pruneExpired())
        assertEquals(setOf(ORPHAN), FileRawCollectionStorage(root).listQuarantinedCollectionIds())
    }

    @Test
    fun missingManifestAndActivePointerAreQuarantinedNotDeleted() {
        val root = temporary.newFolder()
        val storage = FileRawCollectionStorage(root)
        assertTrue(storage.writeChunkAtomically(ORPHAN, 0, "retained-ciphertext"))
        assertTrue(storage.writeActiveCollectionIdAtomically(ORPHAN))
        val store = RawCollectionStore(root, RecoveryStorageAead(), { NOW })

        assertNull(store.activePartialManifest(OWNER, WALK))
        assertNull(storage.readActiveCollectionId())
        assertEquals("retained-ciphertext", isolated(root, "chunk-0000.aead").readText())
        assertTrue(store.open(VALID, OWNER, WALK, CONSENT, rawConsentGranted = true, walkState = RawWalkState.ACTIVE))
    }

    @Test
    fun quarantineCollisionIsExplicitFailureAndNeitherCopyIsOverwritten() {
        val root = temporary.newFolder()
        val storage = FileRawCollectionStorage(root)
        assertTrue(storage.writeManifestAtomically(ORPHAN, "source-ciphertext"))
        isolated(root, "manifest.aead").apply { parentFile.mkdirs(); writeText("prior-ciphertext") }
        val store = RawCollectionStore(root, RecoveryStorageAead(), { NOW })

        assertThrows(RawCollectionStorageReadException::class.java) { store.pruneExpired() }
        assertEquals("source-ciphertext", storage.readManifest(ORPHAN))
        assertEquals("prior-ciphertext", isolated(root, "manifest.aead").readText())
    }

    @Test
    fun unreadableCollectionAndFencePathsDoNotBecomeEmptyLists() {
        val root = temporary.newFolder()
        val rawRoot = root.resolve("raw_collections_v2").apply { mkdir() }
        rawRoot.resolve("collections").writeText("not-a-directory")
        rawRoot.resolve("fences").writeText("not-a-directory")
        val storage = FileRawCollectionStorage(root)
        val store = RawCollectionStore(root, RecoveryStorageAead(), { NOW })

        assertThrows(RawCollectionStorageReadException::class.java) { storage.listCollectionIds() }
        assertThrows(RawCollectionStorageReadException::class.java) { storage.listFenceNames() }
        assertThrows(RawCollectionStorageReadException::class.java) { store.readyManifests(OWNER) }
        assertFalse(store.resetForNewEnrollment(OWNER))
        assertFalse(storage.deleteAllCollections())
        assertFalse(storage.verifiedEmptyAfterAccountPurge())
    }

    @Test
    fun freshMissingDirectoriesAreEmptyRatherThanReadFailures() {
        val storage = FileRawCollectionStorage(temporary.newFolder())
        assertTrue(storage.listCollectionIds().isEmpty())
        assertTrue(storage.listQuarantinedCollectionIds().isEmpty())
        assertTrue(storage.listFenceNames().isEmpty())
    }

    @Test
    fun accountPurgeIncludesQuarantineBeforeNewEnrollmentCanReset() {
        val root = temporary.newFolder()
        val storage = FileRawCollectionStorage(root)
        val store = RawCollectionStore(root, RecoveryStorageAead(), { NOW })
        assertTrue(storage.writeManifestAtomically(ORPHAN, "unreadable"))
        store.pruneExpired()
        assertEquals(1, store.quarantinedCollectionCount())
        assertFalse(storage.verifiedEmptyAfterAccountPurge())

        assertTrue(store.onAccountDeleted())
        assertTrue(storage.verifiedEmptyAfterAccountPurge())
        assertTrue(storage.listQuarantinedCollectionIds().isEmpty())
        assertTrue(store.resetForNewEnrollment(OWNER.copy(accountGeneration = 2L)))
    }

    @Test
    fun symlinkCollectionRootCannotReadOrPurgeAnExternalDirectory() {
        val root = temporary.newFolder()
        val external = temporary.newFolder()
        external.resolve("keep.aead").writeText("external-ciphertext")
        val rawRoot = root.resolve("raw_collections_v2").apply { mkdir() }
        Files.createSymbolicLink(rawRoot.resolve("collections").toPath(), external.toPath())
        val storage = FileRawCollectionStorage(root)

        assertThrows(RawCollectionStorageReadException::class.java) { storage.listCollectionIds() }
        assertFalse(storage.deleteAllCollections())
        assertEquals("external-ciphertext", external.resolve("keep.aead").readText())
    }

    @Test
    fun rejectionFallbackSurvivesReconstructionThenAdmissionDeletesOnlyRejectedData() {
        for (consent in listOf(true, false)) {
            val root = temporary.newFolder()
            val files = FileRawCollectionStorage(root)
            var deny = false
            val failing = object : RawCollectionStorage by files {
                override fun writeManifestAtomically(collectionId: String, envelope: String) =
                    !deny && files.writeManifestAtomically(collectionId, envelope)
                override fun deleteCollection(collectionId: String) = !deny && files.deleteCollection(collectionId)
            }
            val cipher = RecoveryStorageAead()
            val store = RawCollectionStore(failing, cipher, { NOW })
            seedComplete(store)
            deny = true
            assertFalse(if (consent) store.discardConsentRejected(OWNER, VALID) else store.discardWalkRejected(OWNER, VALID))
            val restored = RawCollectionStore(root, cipher, { NOW })
            assertTrue(restored.readyManifests(OWNER).isEmpty())
            assertNull(restored.readUploadChunk(VALID, OWNER, 0))
            assertNotNull(files.readManifest(VALID))
            assertEquals(RawCapacityAdmission.ALLOWED, restored.admitNewSegment(WALK, CONSENT))
            assertNull(files.readManifest(VALID))
            assertTrue(restored.onAccountDeleted())
            assertTrue(restored.resetForNewEnrollment(OWNER.copy(accountGeneration = 2)))
            assertTrue(files.listFenceNames().isEmpty())
        }
    }

    private fun isolated(root: File, name: String) =
        root.resolve("raw_collections_v2/quarantine/$ORPHAN/$name")

    private fun seedComplete(store: RawCollectionStore) {
        assertTrue(store.open(VALID, OWNER, WALK, CONSENT, rawConsentGranted = true, walkState = RawWalkState.ACTIVE))
        assertNotNull(store.appendBatch(OWNER, WALK, true, RawWalkState.ACTIVE,
            RAW_RUNTIME_CHUNK_TYPE_ORDER.map { RawPlaintextBatchItem(it, NOW, "metadata".toByteArray()) }))
        assertNotNull(store.sealActiveSegment(OWNER, WALK))
    }

    private companion object {
        const val NOW = 10_000L
        const val VALID = "123e4567-e89b-42d3-a456-426614174000"
        const val ORPHAN = "123e4567-e89b-42d3-a456-426614174010"
        const val WALK = "123e4567-e89b-42d3-a456-426614174001"
        val CONSENT = "c".repeat(64)
        val OWNER = RawCollectionOwner("123e4567-e89b-42d3-a456-426614174002", 1L, "device-installation-00000001")
    }
}

private class RecoveryStorageAead : LocalAead {
    override fun seal(plaintext: ByteArray, domainAad: ByteArray, limits: AeadLimits) =
        AeadSealResult.Sealed("aead:" + Base64.getEncoder().encodeToString(plaintext))
    override fun open(envelope: String, domainAad: ByteArray, limits: AeadLimits): AeadOpenResult =
        if (!envelope.startsWith("aead:")) AeadOpenResult.Blocked(AeadBlockReason.MALFORMED_ENVELOPE)
        else AeadOpenResult.Opened(Base64.getDecoder().decode(envelope.removePrefix("aead:")), 1, false)
    override fun destroyVersion(version: Int) = true
    override fun destroyKnownVersions() = true
    override fun createFreshAfterVerifiedPurge() = true
}

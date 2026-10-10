package kr.co.hanium.dreamup.walksafe.rawcollection

import java.io.File
import java.nio.file.Files
import androidx.test.platform.app.InstrumentationRegistry
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AndroidKeyStoreAead
import java.util.UUID
import java.security.KeyStore
import org.junit.After
import kr.co.hanium.dreamup.walksafe.security.AeadBlockReason
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import org.junit.Assert.*
import org.junit.Test

/** Real Android private files and Keystore, isolated synthetic owners/data and fixed test clock. */
class RawRecoveryDeviceTest {
    private val roots = mutableListOf<File>()
    private val keys = mutableListOf<AndroidKeyStoreAead>()
    private val aliases = mutableListOf<String>()
    private fun newRoot() = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir.canonicalFile,
        "raw-recovery-device-${UUID.randomUUID()}").apply { check(mkdir()); roots += this }
    private fun aead(): AndroidKeyStoreAead {
        val policy = AeadKeyPolicy("walksafe.instrumentation.raw.${UUID.randomUUID()}.v", 1, setOf(1))
        aliases += policy.alias(1)
        return AndroidKeyStoreAead(policy).also { keys += it }
    }
    @After fun cleanTestOwnedResources() {
        roots.forEach { assertTrue(it.deleteRecursively()) }
        keys.forEach { assertTrue(it.destroyKnownVersions()) }
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        aliases.forEach { keyStore.deleteEntry("$it.generation"); assertFalse(keyStore.containsAlias(it)) }
    }

    @Test
    fun malformedOrphanIsIsolatedWithoutLosingCiphertextOrBlockingValidCollection() {
        val root = newRoot()
        val storage = FileRawCollectionStorage(root)
        val store = RawCollectionStore(root, aead(), { NOW })
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
        val root = newRoot()
        val storage = FileRawCollectionStorage(root)
        assertTrue(storage.writeChunkAtomically(ORPHAN, 0, "retained-ciphertext"))
        assertTrue(storage.writeActiveCollectionIdAtomically(ORPHAN))
        val store = RawCollectionStore(root, aead(), { NOW })

        assertNull(store.activePartialManifest(OWNER, WALK))
        assertNull(storage.readActiveCollectionId())
        assertEquals("retained-ciphertext", isolated(root, "chunk-0000.aead").readText())
        assertTrue(store.open(VALID, OWNER, WALK, CONSENT, rawConsentGranted = true, walkState = RawWalkState.ACTIVE))
    }

    @Test
    fun quarantineCollisionIsExplicitFailureAndNeitherCopyIsOverwritten() {
        val root = newRoot()
        val storage = FileRawCollectionStorage(root)
        assertTrue(storage.writeManifestAtomically(ORPHAN, "source-ciphertext"))
        isolated(root, "manifest.aead").apply { checkNotNull(parentFile).mkdirs(); writeText("prior-ciphertext") }
        val store = RawCollectionStore(root, aead(), { NOW })

        assertThrows(RawCollectionStorageReadException::class.java) { store.pruneExpired() }
        assertEquals("source-ciphertext", storage.readManifest(ORPHAN))
        assertEquals("prior-ciphertext", isolated(root, "manifest.aead").readText())
    }

    @Test
    fun unreadableCollectionAndFencePathsDoNotBecomeEmptyLists() {
        val root = newRoot()
        val rawRoot = root.resolve("raw_collections_v2").apply { mkdir() }
        rawRoot.resolve("collections").writeText("not-a-directory")
        rawRoot.resolve("fences").writeText("not-a-directory")
        val storage = FileRawCollectionStorage(root)
        val store = RawCollectionStore(root, aead(), { NOW })

        assertThrows(RawCollectionStorageReadException::class.java) { storage.listCollectionIds() }
        assertThrows(RawCollectionStorageReadException::class.java) { storage.listFenceNames() }
        assertThrows(RawCollectionStorageReadException::class.java) { store.readyManifests(OWNER) }
        assertFalse(store.resetForNewEnrollment(OWNER))
        assertFalse(storage.deleteAllCollections())
        assertFalse(storage.verifiedEmptyAfterAccountPurge())
    }

    @Test
    fun freshMissingDirectoriesAreEmptyRatherThanReadFailures() {
        val storage = FileRawCollectionStorage(newRoot())
        assertTrue(storage.listCollectionIds().isEmpty())
        assertTrue(storage.listQuarantinedCollectionIds().isEmpty())
        assertTrue(storage.listFenceNames().isEmpty())
    }

    @Test
    fun accountPurgeIncludesQuarantineBeforeNewEnrollmentCanReset() {
        val root = newRoot()
        val storage = FileRawCollectionStorage(root)
        val store = RawCollectionStore(root, aead(), { NOW })
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
        val root = newRoot()
        val external = newRoot()
        external.resolve("keep.aead").writeText("external-ciphertext")
        val rawRoot = root.resolve("raw_collections_v2").apply { mkdir() }
        Files.createSymbolicLink(rawRoot.resolve("collections").toPath(), external.toPath())
        val storage = FileRawCollectionStorage(root)

        assertThrows(RawCollectionStorageReadException::class.java) { storage.listCollectionIds() }
        assertFalse(storage.deleteAllCollections())
        assertEquals("external-ciphertext", external.resolve("keep.aead").readText())
        Files.delete(rawRoot.resolve("collections").toPath())
    }

    @Test fun noProfileQueueHoldsAcrossWalksWithoutDeletingUnsentData() {
        val root = newRoot()
        val cipher = aead()
        val store = RawCollectionStore(root, cipher, { NOW }, capacityProfile = null)
        seedComplete(store)
        assertEquals(RawCapacityAdmission.ALLOWED, store.admitNewSegment(WALK, CONSENT))
        assertTrue(store.open(ORPHAN, OWNER, WALK, CONSENT, rawConsentGranted = true, walkState = RawWalkState.ACTIVE))
        assertNotNull(store.appendBatch(OWNER, WALK, true, RawWalkState.ACTIVE,
            RAW_RUNTIME_CHUNK_TYPE_ORDER.map { RawPlaintextBatchItem(it, NOW, "metadata".toByteArray()) }))
        assertNotNull(store.sealActiveSegment(OWNER, WALK))
        val reopened = RawCollectionStore(root, cipher, { NOW }, capacityProfile = null)
        assertEquals(RawCapacityAdmission.HOLD_NO_APPROVED_PROFILE,
            reopened.admitNewSegment(UUID.randomUUID().toString(), CONSENT))
        assertEquals(setOf(VALID, ORPHAN), reopened.readyManifests(OWNER).map { it.collectionId }.toSet())
    }

    @Test fun corruptQuarantineLimitHoldsNewCollectionInsteadOfDeletingEvidence() {
        val root = newRoot()
        val storage = FileRawCollectionStorage(root)
        val store = RawCollectionStore(root, aead(), { NOW }, capacityProfile = null)
        val ids = (1..RAW_NO_PROFILE_MAX_QUARANTINED_COLLECTIONS).map { UUID.randomUUID().toString() }
        ids.forEach { assertTrue(storage.writeManifestAtomically(it, "corrupt-test-data")) }
        assertEquals(RawCapacityAdmission.HOLD_QUARANTINE_LIMIT, store.admitNewSegment(WALK, CONSENT))
        assertEquals(ids.toSet(), storage.listQuarantinedCollectionIds())
        assertEquals(RawCapacityAdmission.HOLD_QUARANTINE_LIMIT, store.admitNewSegment(WALK, CONSENT))
    }

    @Test fun expiryRequiresMaintenanceAndRetainsDataUntilExactBoundary() {
        val root = newRoot()
        var now = NOW
        val store = RawCollectionStore(root, aead(), { now })
        seedComplete(store)
        val storage = FileRawCollectionStorage(root)
        now = NOW + RAW_COLLECTION_TTL_MS - 1
        assertEquals(0, store.pruneExpired())
        assertEquals(setOf(VALID), storage.listCollectionIds())
        now += 1
        assertEquals("No autonomous deletion before maintenance", setOf(VALID), storage.listCollectionIds())
        assertEquals(1, store.pruneExpired())
        assertTrue(storage.listCollectionIds().isEmpty())
    }

    /** Actual Linux permission denial in a fresh test directory; never changes app-owned data. */
    @Test fun actualWriteAndDeletionDenialPreserveEvidenceUntilExplicitRetry() {
        val mode = InstrumentationRegistry.getArguments().getString("rawPermissionCase")
        org.junit.Assume.assumeTrue(mode in setOf("manifest", "chunk", "fence", "purge"))
        val root = newRoot()
        val storage = FileRawCollectionStorage(root)
        val store = RawCollectionStore(root, aead(), { NOW })
        seedComplete(store)
        val target = if(mode == "fence") root.resolve("raw_collections_v2/fences")
            else root.resolve("raw_collections_v2/collections/$VALID")
        val fenceName = "test-denial"
        if(mode == "fence") assertTrue(storage.writeFenceAtomically(fenceName,"BEFORE"))
        val manifest = storage.readManifest(VALID)
        val chunk = storage.readChunk(VALID,0)
        try {
            android.system.Os.chmod(target.absolutePath,0b101000000)
            when(mode) {
                "manifest" -> { assertFalse(storage.writeManifestAtomically(VALID,"REPLACEMENT"));assertEquals(manifest,storage.readManifest(VALID)) }
                "chunk" -> { assertFalse(storage.writeChunkAtomically(VALID,0,"REPLACEMENT"));assertEquals(chunk,storage.readChunk(VALID,0)) }
                "fence" -> { assertFalse(storage.writeFenceAtomically(fenceName,"REPLACEMENT"));assertEquals("BEFORE",storage.readFence(fenceName)) }
                "purge" -> { assertFalse(store.onAccountDeleted());assertFalse(storage.verifiedEmptyAfterAccountPurge());assertEquals(manifest,storage.readManifest(VALID)) }
            }
        } finally { android.system.Os.chmod(target.absolutePath,0b111000000) }
        when(mode) {
            "manifest" -> {assertTrue(storage.writeManifestAtomically(VALID,requireNotNull(manifest)));assertEquals(manifest,storage.readManifest(VALID))}
            "chunk" -> {assertTrue(storage.writeChunkAtomically(VALID,0,requireNotNull(chunk)));assertEquals(chunk,storage.readChunk(VALID,0))}
            "fence" -> {assertTrue(storage.writeFenceAtomically(fenceName,"AFTER"));assertEquals("AFTER",storage.readFence(fenceName))}
            "purge" -> {assertTrue(store.onAccountDeleted());assertTrue(storage.verifiedEmptyAfterAccountPurge())}
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {
            putString("stream","RAW_NATIVE_DENIAL case=$mode actual_chmod=true denial_reported=true evidence_preserved=true restored_permission_retry=true synthetic_owner=true actual_account_deletion=false\n")
        })
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

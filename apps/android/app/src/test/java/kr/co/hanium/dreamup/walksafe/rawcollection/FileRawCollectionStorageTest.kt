package kr.co.hanium.dreamup.walksafe.rawcollection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileRawCollectionStorageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun durableWritesExposeOnlyRenamedFilesAndScopedDeletion() {
        val root = temporaryFolder.newFolder("raw-storage")
        val storage = FileRawCollectionStorage(root)

        assertTrue(storage.writeManifestAtomically(COLLECTION_ID, "manifest-1"))
        assertTrue(storage.writeManifestAtomically(COLLECTION_ID, "manifest-2"))
        assertTrue(storage.writeChunkAtomically(COLLECTION_ID, 0, "chunk-envelope"))
        assertTrue(storage.writeActiveCollectionIdAtomically(COLLECTION_ID))
        assertTrue(storage.writeFenceAtomically("account-deleted", "ACCOUNT_DELETED"))

        assertEquals("manifest-2", storage.readManifest(COLLECTION_ID))
        assertEquals("chunk-envelope", storage.readChunk(COLLECTION_ID, 0))
        assertEquals(COLLECTION_ID, storage.readActiveCollectionId())
        assertTrue(storage.hasFence("account-deleted"))
        assertEquals("ACCOUNT_DELETED", storage.readFence("account-deleted"))
        assertEquals(setOf("account-deleted"), storage.listFenceNames())
        assertFalse(
            root.walkTopDown().any { file -> file.name.endsWith(".tmp") },
        )

        assertTrue(storage.deleteCollection(COLLECTION_ID))
        assertNull(storage.readManifest(COLLECTION_ID))
        assertNull(storage.readChunk(COLLECTION_ID, 0))
        assertTrue(storage.clearAllFences())
        assertTrue(storage.listFenceNames().isEmpty())
    }

    @Test
    fun accountPurgeRemovesUnknownCollectionArtifactsBeforeResetVerification() {
        val root = temporaryFolder.newFolder("raw-purge")
        val storage = FileRawCollectionStorage(root)
        assertTrue(storage.writeManifestAtomically(COLLECTION_ID, "manifest"))
        val rawRoot = root.resolve("raw_collections_v2")
        rawRoot.resolve("collections/malformed-old-account").apply {
            mkdirs()
            resolve("payload.aead").writeText("old")
        }

        assertTrue(storage.deleteAllCollections())
        assertTrue(storage.writeFenceAtomically("account-deleted", "ACCOUNT_DELETED"))
        assertTrue(storage.writeFenceAtomically("key-purge-verified", "KEY_PURGE_VERIFIED"))
        assertTrue(storage.verifiedEmptyAfterAccountPurge())
    }

    private companion object {
        const val COLLECTION_ID = "123e4567-e89b-42d3-a456-426614174000"
    }
}

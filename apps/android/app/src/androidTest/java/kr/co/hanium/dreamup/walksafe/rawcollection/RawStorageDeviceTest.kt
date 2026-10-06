package kr.co.hanium.dreamup.walksafe.rawcollection

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class RawStorageDeviceTest {
    @Test fun freshPrivateStorageCanListWriteReadAndPurge() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.noBackupFilesDir.canonicalFile, "raw-device-test-${UUID.randomUUID()}")
        try {
            val storage = FileRawCollectionStorage(root)
            assertTrue(storage.listCollectionIds().isEmpty())
            val id = UUID.randomUUID().toString()
            assertTrue(storage.writeManifestAtomically(id, "test-envelope"))
            assertEquals("test-envelope", storage.readManifest(id))
            assertEquals(setOf(id), storage.listCollectionIds())
            assertTrue(storage.deleteAllCollections())
            assertTrue(storage.verifiedEmptyAfterAccountPurge())
            val outside = File(root, "outside").apply { mkdirs() }
            val collections = File(root, "raw_collections_v2/collections")
            collections.delete()
            Files.createSymbolicLink(collections.toPath(), outside.toPath())
            assertThrows(RawCollectionStorageReadException::class.java) { storage.listCollectionIds() }
            assertFalse(storage.writeManifestAtomically(id, "must-not-write"))
            assertTrue(outside.listFiles()!!.isEmpty())
            Files.delete(collections.toPath())
        } finally { root.deleteRecursively() }
    }
}

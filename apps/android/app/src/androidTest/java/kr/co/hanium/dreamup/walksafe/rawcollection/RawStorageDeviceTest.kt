package kr.co.hanium.dreamup.walksafe.rawcollection

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.testing.IsolatedBoundaryContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class RawStorageDeviceTest {
    @Test fun freshPrivateStorageCanListWriteReadAndPurge() {
        val context = IsolatedBoundaryContext.create("raw-storage")
        val root = context.root
        val collections = File(root, "raw_collections_v2/collections")
        try {
            val storage = FileRawCollectionStorage(root)
            assertTrue(storage.listCollectionIds().isEmpty())
            val id = UUID.randomUUID().toString()
            assertTrue(storage.writeManifestAtomically(id, "synthetic-manifest-envelope"))
            assertTrue(storage.writeChunkAtomically(id, 0, "synthetic-chunk-envelope"))
            assertTrue(storage.writeActiveCollectionIdAtomically(id))
            assertTrue(storage.writeFenceAtomically("fixture-owner", "BLOCKED"))
            val reopened = FileRawCollectionStorage(root)
            assertEquals("synthetic-manifest-envelope", reopened.readManifest(id))
            assertEquals("synthetic-chunk-envelope", reopened.readChunk(id, 0))
            assertEquals(id, reopened.readActiveCollectionId())
            assertEquals("BLOCKED", reopened.readFence("fixture-owner"))
            assertEquals(setOf(id), reopened.listCollectionIds())
            assertTrue(reopened.deleteAllCollections())
            assertFalse("A live pointer is not a verified account purge", reopened.verifiedEmptyAfterAccountPurge())
            assertTrue(reopened.clearActiveCollectionId())
            assertTrue(reopened.clearAllFences())
            assertTrue(reopened.verifiedEmptyAfterAccountPurge())

            val outside = File(root, "outside-sentinel").apply { check(mkdir()) }
            val sentinel = File(outside, "keep").apply { writeText("fixture-only-preserved") }
            Files.deleteIfExists(collections.toPath())
            Files.createSymbolicLink(collections.toPath(), outside.toPath())
            assertThrows(RawCollectionStorageReadException::class.java) { reopened.listCollectionIds() }
            assertFalse(reopened.writeManifestAtomically(id, "must-not-write"))
            assertFalse(reopened.deleteAllCollections())
            assertEquals("fixture-only-preserved", sentinel.readText())
            assertEquals(listOf("keep"), outside.listFiles()!!.map { it.name })
        } finally {
            if (Files.isSymbolicLink(collections.toPath())) Files.delete(collections.toPath())
            context.dispose()
        }
    }
}

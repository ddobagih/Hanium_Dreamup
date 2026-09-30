package kr.co.hanium.dreamup.walksafe.fieldlog

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** S21-50: a full deletion removes the debug outdoor test store without following links. */
class DebugCollectionArtifactsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun outdoorRawGnssAndEncryptedSessionsAreDeleted() {
        val noBackup = temporaryFolder.newFolder("no_backup")
        val store = File(noBackup, DebugCollectionArtifacts.OUTDOOR_POSITION_TEST_DIRECTORY)
        File(store, "position_field_sessions/session-1").mkdirs()
        File(store, "raw_session-1.txt").writeText("Raw,0,0,0\n")
        File(store, "position_field_sessions/session-1/manifest.aead").writeText("envelope")
        val unrelated = File(noBackup, "raw_collections").also { it.mkdirs() }

        assertTrue(DebugCollectionArtifacts.purgeOutdoorPositionTest(noBackup))
        assertFalse(store.exists())
        assertTrue("other stores have their own purge", unrelated.isDirectory)
        // Already absent is already purged.
        assertTrue(DebugCollectionArtifacts.purgeOutdoorPositionTest(noBackup))
    }

    @Test
    fun aLinkInsideTheStoreIsRemovedWithoutDeletingItsTarget() {
        val noBackup = temporaryFolder.newFolder("no_backup_links")
        val outside = temporaryFolder.newFolder("outside")
        val keep = File(outside, "keep.txt").also { it.writeText("keep") }
        val store = File(noBackup, DebugCollectionArtifacts.OUTDOOR_POSITION_TEST_DIRECTORY).also { it.mkdirs() }
        Files.createSymbolicLink(File(store, "linked").toPath(), outside.toPath())

        assertTrue(DebugCollectionArtifacts.purgeOutdoorPositionTest(noBackup))
        assertFalse(store.exists())
        assertTrue(keep.isFile)
    }
}

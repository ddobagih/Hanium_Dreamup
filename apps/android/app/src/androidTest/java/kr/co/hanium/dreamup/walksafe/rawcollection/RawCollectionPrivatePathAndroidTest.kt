package kr.co.hanium.dreamup.walksafe.rawcollection

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Android private storage path checks; the fixture never reads existing user collections. */
@RunWith(AndroidJUnit4::class)
class RawCollectionPrivatePathAndroidTest {
    @Test
    fun emptyCollectionListingUsesTheTrustedPrivateDirectory() {
        val supplied = InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir
        val trusted = supplied.canonicalFile
        Log.d("WalkSafeRawPathTest", "event=PRIVATE_ROOT canonical_changed=${trusted != supplied.absoluteFile}")
        var suppliedHasAlias = false
        for ((label, anchor) in listOf("supplied" to supplied, "canonical" to trusted)) {
            var current = anchor.toPath().toAbsolutePath().root
            anchor.toPath().toAbsolutePath().forEachIndexed { index, part ->
                current = current.resolve(part)
                val metadata = Files.readAttributes(current, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (label == "supplied" && metadata.isSymbolicLink) suppliedHasAlias = true
                Log.d("WalkSafeRawPathTest", "anchor=$label index=$index directory=${metadata.isDirectory} symlink=${metadata.isSymbolicLink}")
            }
        }
        val fixture = File(trusted, "raw-path-test-${UUID.randomUUID()}")
        try {
            if (suppliedHasAlias) {
                assertThrows(RawCollectionStorageReadException::class.java) {
                    FileRawCollectionStorage(File(supplied, fixture.name)).listCollectionIds()
                }
            }
            assertEquals(emptySet<String>(), FileRawCollectionStorage(fixture).listCollectionIds())
        } finally {
            fixture.deleteRecursively()
        }
    }

    @Test
    fun normalizingTheTrustedRootStillRejectsRawChildSymlinks() {
        val trusted = InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir.canonicalFile
        val fixture = File(trusted, "raw-path-test-${UUID.randomUUID()}")
        try {
            val external = File(fixture, "external").apply { assertTrue(mkdirs()) }
            File(external, "keep.aead").writeText("preserved-ciphertext")
            val rawRoot = File(fixture, "raw_collections_v2").apply { assertTrue(mkdir()) }
            Files.createSymbolicLink(File(rawRoot, "collections").toPath(), external.toPath())
            val storage = FileRawCollectionStorage(fixture)
            assertThrows(RawCollectionStorageReadException::class.java) { storage.listCollectionIds() }
            assertFalse(storage.deleteAllCollections())
            assertEquals("preserved-ciphertext", File(external, "keep.aead").readText())
        } finally {
            fixture.deleteRecursively()
        }
    }
}

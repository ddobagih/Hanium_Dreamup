package kr.co.hanium.dreamup.walksafe.fieldlog

import java.nio.file.Files
import kr.co.hanium.dreamup.walksafe.security.*
import org.junit.Assert.*
import org.junit.Test

class GuidanceDiagnosticLocalStorePurgeTest {
    @Test fun knownStorePurgeErasesOldKeyAndLeavesNeighbourDataAlone() {
        val root = Files.createTempDirectory("diagnostic-purge").toFile()
        try {
            val store = root.resolve("guidance_diagnostics").apply { mkdir() }
            store.resolve("a".repeat(64)+".aead").writeText("encrypted")
            val neighbour = root.resolve("account.txt").apply { writeText("preserve") }
            val keys = Keys()
            assertTrue(GuidanceDiagnosticLocalStorePurge(store, keys, { true }).purgeAndRotate())
            assertTrue(store.listFiles()!!.isEmpty()); assertEquals("preserve", neighbour.readText())
            assertEquals(1, keys.destroyed); assertEquals(1, keys.rotated)
        } finally { root.deleteRecursively() }
    }

    @Test fun deletionFailureCannotBecomeDeletedAndStillErasesTheOldDecryptingKey() {
        val root = Files.createTempDirectory("diagnostic-purge-fail").toFile()
        try {
            root.resolve("a".repeat(64)+".aead").writeText("encrypted")
            val keys = Keys()
            assertFalse(GuidanceDiagnosticLocalStorePurge(root, keys, { true }, { false }).purgeAndRotate())
            assertEquals(1, keys.destroyed); assertEquals(0, keys.rotated)
        } finally { root.deleteRecursively() }
    }

    @Test fun unknownFilesAndExternalSymlinksPreventSuccessfulPurgeWithoutTouchingTargets() {
        val parent = Files.createTempDirectory("diagnostic-purge-link").toFile()
        try {
            val target = parent.resolve("keep.txt").apply { writeText("private") }
            val root = parent.resolve("guidance_diagnostics").apply { mkdir() }
            Files.createSymbolicLink(root.resolve("a".repeat(64)+".aead").toPath(), target.toPath())
            val keys = Keys()
            assertFalse(GuidanceDiagnosticLocalStorePurge(root, keys, { true }).purgeAndRotate())
            assertEquals("private", target.readText()); assertEquals(0, keys.rotated)
        } finally { parent.deleteRecursively() }
    }

    private class Keys : LocalAead {
        var destroyed = 0; var rotated = 0
        override fun seal(plaintext: ByteArray, domainAad: ByteArray, limits: AeadLimits) = AeadSealResult.Blocked(AeadBlockReason.KEY_ACCESS_FAILED)
        override fun open(envelope: String, domainAad: ByteArray, limits: AeadLimits) = AeadOpenResult.Blocked(AeadBlockReason.KEY_ACCESS_FAILED)
        override fun destroyVersion(version: Int) = true
        override fun destroyKnownVersions(): Boolean { destroyed++; return true }
        override fun createFreshAfterVerifiedPurge(): Boolean { rotated++; return true }
    }
}

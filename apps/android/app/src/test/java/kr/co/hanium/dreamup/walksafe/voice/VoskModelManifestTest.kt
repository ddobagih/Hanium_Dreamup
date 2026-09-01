package kr.co.hanium.dreamup.walksafe.voice

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VoskModelManifestTest {
    @Test
    fun parsesSortedStyleSha256Entries() {
        val manifest = parseBundledVoskModelManifest(
            ("a".repeat(64) + "  am/final.mdl\n" +
                "b".repeat(64) + "  conf/model.conf\n").toByteArray(),
        )

        assertEquals(listOf("am/final.mdl", "conf/model.conf"), manifest.files.map { it.relativePath })
        assertEquals(64, manifest.manifestSha256.length)
    }

    @Test
    fun rejectsTraversalAbsoluteBackslashAndDuplicatePaths() {
        listOf(
            "../outside",
            "/absolute",
            "graph\\HCLr.fst",
            "graph//HCLr.fst",
        ).forEach { path ->
            assertThrows(IOException::class.java) {
                parseBundledVoskModelManifest(("a".repeat(64) + "  $path\n").toByteArray())
            }
        }
        assertThrows(IOException::class.java) {
            parseBundledVoskModelManifest(
                ("a".repeat(64) + "  am/final.mdl\n" +
                    "b".repeat(64) + "  am/final.mdl\n").toByteArray(),
            )
        }
    }

    @Test
    fun rejectsMalformedDigestAndOversizedManifest() {
        assertThrows(IOException::class.java) {
            parseBundledVoskModelManifest("not-a-manifest".toByteArray())
        }
        assertThrows(IOException::class.java) {
            parseBundledVoskModelManifest(ByteArray(MAX_VOSK_MANIFEST_BYTES + 1))
        }
    }
}

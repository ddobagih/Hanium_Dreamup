package kr.co.hanium.dreamup.walksafe.voice

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoskModelInstallCleanupRegressionTest {
    @Test
    fun nextInstallRemovesKilledStagingAndOldModelVersionsWithoutFollowingLinks() {
        val parent = Files.createTempDirectory("vosk-cleanup-parent-").toFile()
        val outside = Files.createTempDirectory("vosk-cleanup-outside-").toFile()
        try {
            // A process killed mid-install leaves this behind; finally never ran.
            val killedStaging = File(parent, ".install-0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0").apply {
                File(this, "am").mkdirs()
                File(this, "am/final.mdl").writeBytes(ByteArray(64))
            }
            // A previous APK's manifest published a different model directory.
            val oldVersion = File(parent, "vosk-model-small-ko-0.22-0123456789abcdef").apply {
                File(this, "graph").mkdirs()
                File(this, "graph/HCLr.fst").writeBytes(ByteArray(64))
                File(this, ".verified-model").writeText("old-manifest")
            }
            val unrelated = File(parent, "user-owned-notes").apply {
                mkdirs()
                File(this, "keep.txt").writeText("keep")
            }
            val outsideFile = File(outside, "precious.bin").apply { writeText("outside") }
            val linkedStaging = File(parent, ".install-11111111-2222-3333-4444-555555555555")
            val linkedOldVersion = File(parent, "vosk-model-small-ko-0.22-fedcba9876543210")
            Files.createSymbolicLink(linkedStaging.toPath(), outside.toPath())
            Files.createSymbolicLink(linkedOldVersion.toPath(), outside.toPath())
            val nestedLink = File(oldVersion, "graph/escape")
            Files.createSymbolicLink(nestedLink.toPath(), outside.toPath())

            val (manifest, openAsset) = fixture()
            val installed = BundledVoskModelInstaller.install(parent, manifest, openAsset)

            assertEquals(installed, BundledVoskModelInstaller.installedModelOrNull(parent, manifest))
            assertFalse("killed staging survived", killedStaging.exists())
            assertFalse("old model version survived", oldVersion.exists())
            assertFalse(Files.exists(linkedStaging.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.exists(linkedOldVersion.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertTrue("links must not be followed", outsideFile.isFile)
            assertEquals("outside", outsideFile.readText())
            assertTrue("unrelated names are not installer-owned", File(unrelated, "keep.txt").isFile)
            assertEquals(
                setOf(installed.name, unrelated.name),
                parent.listFiles()!!.map(File::getName).toSet(),
            )
        } finally {
            parent.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun cleanupNeverRemovesTheModelThatIsAlreadyInstalledOrBeingRepaired() {
        val parent = Files.createTempDirectory("vosk-cleanup-current-").toFile()
        try {
            val (manifest, openAsset) = fixture()
            val installed = BundledVoskModelInstaller.install(parent, manifest, openAsset)
            val original = File(installed, "am/final.mdl").readBytes()
            // Same-size corruption forces the repair path, which runs the cleanup first.
            File(installed, "am/final.mdl").writeBytes(ByteArray(original.size))
            val repaired = BundledVoskModelInstaller.install(parent, manifest, openAsset)
            assertEquals(installed, repaired)
            assertArrayEquals(original, File(repaired, "am/final.mdl").readBytes())
            assertEquals(listOf(installed.name), parent.listFiles()!!.map(File::getName))
        } finally {
            parent.deleteRecursively()
        }
    }

    private fun fixture(): Pair<BundledVoskModelManifest, (String) -> ByteArrayInputStream> {
        val modelFiles = mapOf(
            "am/final.mdl" to "acoustic-model".toByteArray(),
            "conf/mfcc.conf" to "mfcc-config".toByteArray(),
            "conf/model.conf" to "model-config".toByteArray(),
            "graph/Gr.fst" to "grammar-graph".toByteArray(),
            "graph/HCLr.fst" to "lexicon-graph".toByteArray(),
        )
        val manifestBytes = modelFiles.entries.joinToString("\n", postfix = "\n") { (path, bytes) ->
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            "$hash  $path"
        }.toByteArray()
        val assets = modelFiles + ("MODEL_FILES.sha256" to manifestBytes)
        return parseBundledVoskModelManifest(manifestBytes) to { path -> ByteArrayInputStream(assets.getValue(path)) }
    }
}

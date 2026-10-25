package kr.co.hanium.dreamup.walksafe.voice
import java.nio.file.Files
import java.io.File
import org.junit.Assert.*
import org.junit.Test
class TemporaryVoiceAudioTest {
    @Test fun cleanupIsDirectOwnedFilesOnlyAndProtectsActiveAndSymlinkTargets() {
        val root = Files.createTempDirectory("voice-cleanup").toFile()
        val outside = Files.createTempFile("voice-preserved", ".wav").toFile()
        try {
            val remove = File(root, "walksafe-command-old.m4a").apply { writeText("old") }
            val active = File(root, "walksafe-voice-active.wav").apply { writeText("active") }
            val keep = File(root, "report-photo.jpg").apply { writeText("keep") }
            val nested = File(root, "nested").apply { mkdir() }
            val nestedAudio = File(nested, "walksafe-voice-old.wav").apply { writeText("nested") }
            val link = File(root, "walksafe-voice-link.wav")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertEquals(TemporaryVoiceAudio.Cleanup(1,0), TemporaryVoiceAudio.cleanupAtProcessStart(root, setOf(active)))
            assertFalse(remove.exists()); assertTrue(active.isFile); assertTrue(keep.isFile)
            assertTrue(nestedAudio.isFile); assertTrue(Files.isSymbolicLink(link.toPath())); assertTrue(outside.isFile)
        } finally { root.deleteRecursively(); outside.delete() }
    }
}

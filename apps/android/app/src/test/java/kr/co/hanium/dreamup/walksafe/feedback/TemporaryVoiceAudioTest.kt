package kr.co.hanium.dreamup.walksafe.feedback

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TemporaryVoiceAudioTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun clearsOnlyKnownEphemeralAudioAndCanRunAgain() {
        val cache = temporary.newFolder("cache")
        val audio = listOf("walksafe-command-123.m4a", "walksafe-voice-456.wav", "walksafe-korean-tts-789.wav")
            .map { File(cache, it).apply { writeText("fixture") } }
        val unrelated = listOf("recording.m4a", "walksafe-command-123.json", "walksafe-command-.m4a", "walksafe-voice-123.wav.backup")
            .map { File(cache, it).apply { writeText("keep") } }
        val directory = File(cache, "walksafe-command-directory.m4a").apply { mkdir() }
        val nested = File(directory, "walksafe-command-nested.m4a").apply { writeText("keep") }
        assertTrue(clearPreviousProcessVoiceAudio(cache))
        assertTrue(audio.none { it.exists() })
        assertTrue(unrelated.all { it.readText() == "keep" })
        assertEquals("keep", nested.readText())
        assertTrue(clearPreviousProcessVoiceAudio(cache))
    }

    @Test fun unreadableListingIsNotReportedAsSuccessfulCleanup() {
        assertFalse(clearPreviousProcessVoiceAudio(File(temporary.root, "missing")))
        assertFalse(clearPreviousProcessVoiceAudio(temporary.newFile("not-directory")))
    }
}

package kr.co.hanium.dreamup.walksafe.feedback

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayVoiceAudioStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/feedback/GatewayVoiceAudio.kt").readText()

    @Test
    fun recordingIsBoundedForegroundAacInCacheAndCancellationDeletesIt() {
        assertTrue(source.contains("MediaRecorder.OutputFormat.MPEG_4"))
        assertTrue(source.contains("MediaRecorder.AudioEncoder.AAC"))
        assertTrue(source.contains("setMaxDuration(MAX_RECORDING_DURATION_MS)"))
        assertTrue(source.contains("setMaxFileSize(MAX_RECORDING_BYTES.toLong())"))
        assertTrue(source.contains("File.createTempFile(\"walksafe-command-\", \".m4a\", cacheDirectory)"))
        assertTrue(source.contains("recording.file.delete()"))
    }

    @Test
    fun wavPlaybackOwnsAndDeletesOnlyItsTemporaryFile() {
        assertTrue(source.contains("temporaryFile.absolutePath"))
        assertTrue(source.contains("releaseActive(deleteFile = true)"))
        assertTrue(source.contains("file?.delete()"))
    }
}

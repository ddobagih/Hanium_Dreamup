package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityGatewaySpeechHapticStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/GatewayVoiceCaptureController.kt").readText()

    @Test
    fun successfulServerRecordingStartSignalsListeningOnce() {
        val start = MainActivityRefactorSource.member(source, "startGatewayVoiceCapture")

        assertInOrder(
            start,
            "val started = recorder.start()",
            "if (activeGatewaySpeechInteraction !== active) return false",
            "if (!started)",
            "return false",
            "if (activeGatewaySpeechInteraction !== active || !recorder.isRecording) return false",
            "output.playListeningStarted()",
            "return true",
        )
        assertTrue(occurrences(start, "output.playListeningStarted()") == 1)
    }

    @Test
    fun normalServerRecordingStopClaimsRecorderBeforeEndSignal() {
        val stop = MainActivityRefactorSource.member(source, "stopGatewayVoiceCapture")

        assertInOrder(
            stop,
            "val recorder = gatewayVoiceRecorder ?: return",
            "gatewayVoiceRecorder = null",
            "recorder.stop()",
            "output.playListeningEnded()",
        )
        assertTrue(occurrences(stop, "output.playListeningEnded()") == 1)
    }

    @Test
    fun cancellationSignalsOnlyAnActiveRecordingAndProcessingDoesNotDuplicateIt() {
        val cancel = MainActivityRefactorSource.member(source, "cancelGatewaySpeechInteraction")
        val finish = MainActivityRefactorSource.member(source, "finishGatewaySpeechInteraction")
        val upload = MainActivityRefactorSource.member(source, "submitGatewayVoiceRecording")

        assertInOrder(
            cancel,
            "val recorder = gatewayVoiceRecorder",
            "val wasRecording = recorder?.isRecording == true",
            "gatewayVoiceRecorder = null",
            "recorder?.cancel()",
            "if (wasRecording)",
            "output.playListeningEnded()",
        )
        assertTrue(occurrences(cancel, "output.playListeningEnded()") == 1)
        listOf(finish, upload).forEach { processing ->
            assertFalse(processing.contains("output.playListeningStarted()"))
            assertFalse(processing.contains("output.playListeningEnded()"))
        }
    }

    private fun section(startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        check(start >= 0) { "Missing start marker: $startMarker" }
        val end = source.indexOf(endMarker, start + startMarker.length)
        check(end > start) { "Missing end marker: $endMarker" }
        return source.substring(start, end)
    }

    private fun occurrences(text: String, value: String): Int =
        Regex(Regex.escape(value)).findAll(text).count()

    private fun assertInOrder(text: String, vararg values: String) {
        var cursor = -1
        values.forEach { value ->
            val next = text.indexOf(value, cursor + 1)
            assertTrue("Missing or out-of-order marker: $value", next > cursor)
            cursor = next
        }
    }
}

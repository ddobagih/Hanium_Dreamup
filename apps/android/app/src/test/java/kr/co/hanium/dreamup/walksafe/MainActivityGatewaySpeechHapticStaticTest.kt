package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityGatewaySpeechHapticStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun successfulServerRecordingStartSignalsListeningOnce() {
        val start = section(
            "private fun startGatewayVoiceCapture()",
            "private fun stopGatewayVoiceCapture(",
        )

        assertInOrder(
            start,
            "if (!recorder.start())",
            "return false",
            "playVoiceListeningStartVibration()",
            "return true",
        )
        assertTrue(occurrences(start, "playVoiceListeningStartVibration()") == 1)
    }

    @Test
    fun normalServerRecordingStopClaimsRecorderBeforeEndSignal() {
        val stop = section(
            "private fun stopGatewayVoiceCapture(",
            "private fun submitGatewayVoiceRecording(",
        )

        assertInOrder(
            stop,
            "val recorder = gatewayVoiceRecorder ?: return",
            "gatewayVoiceRecorder = null",
            "recorder.stop()",
            "playVoiceListeningEndVibration()",
        )
        assertTrue(occurrences(stop, "playVoiceListeningEndVibration()") == 1)
    }

    @Test
    fun cancellationSignalsOnlyAnActiveRecordingAndProcessingDoesNotDuplicateIt() {
        val cancel = section(
            "private fun cancelGatewaySpeechInteraction(",
            "private fun updateGatewayVoiceStatus(",
        )
        val finish = section(
            "private fun finishGatewaySpeechInteraction(",
            "private fun cancelGatewaySpeechInteraction(",
        )
        val upload = section(
            "private fun submitGatewayVoiceRecording(",
            "private fun handleGatewaySpeechTranscript(",
        )

        assertInOrder(
            cancel,
            "val recorder = gatewayVoiceRecorder",
            "val wasRecording = recorder?.isRecording == true",
            "gatewayVoiceRecorder = null",
            "recorder?.cancel()",
            "if (wasRecording)",
            "playVoiceListeningEndVibration()",
        )
        assertTrue(occurrences(cancel, "playVoiceListeningEndVibration()") == 1)
        listOf(finish, upload).forEach { processing ->
            assertFalse(processing.contains("playVoiceListeningStartVibration()"))
            assertFalse(processing.contains("playVoiceListeningEndVibration()"))
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

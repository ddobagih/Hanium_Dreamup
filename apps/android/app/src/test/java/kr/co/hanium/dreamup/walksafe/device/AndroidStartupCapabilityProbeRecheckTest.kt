package kr.co.hanium.dreamup.walksafe.device

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AndroidStartupCapabilityProbeRecheckTest {
    @Test
    fun recheckPublishesPendingWithoutInvalidatingTheDistanceGeneration() {
        val state = OfflineKoreanTextToSpeechProbeState()
        val initialGeneration = state.begin()
        assertTrue(state.complete(initialGeneration, available = false))

        state.begin()

        assertNull(state.available)

        val source = startupProbeSource()
        val recheck = source.substringAfter("fun recheckOfflineKoreanTextToSpeech()")
            .substringBefore("fun snapshot()")
        assertTrue(recheck.contains("val currentGeneration = generation"))
        assertFalse(recheck.contains("++generation"))
        assertFalse(recheck.contains("generation +="))
        assertTrue(
            recheck.contains(
                "probeOfflineKoreanTextToSpeech(currentGeneration, notifyPending = true)",
            ),
        )
    }

    @Test
    fun latestResultWinsAgainstStaleAndDuplicateCallbacks() {
        val state = OfflineKoreanTextToSpeechProbeState()
        val staleGeneration = state.begin()
        val latestGeneration = state.begin()

        assertTrue(state.complete(latestGeneration, available = true))
        assertFalse(state.complete(staleGeneration, available = false))
        assertFalse(state.complete(latestGeneration, available = false))
        assertTrue(state.available == true)
    }

    @Test
    fun closeInvalidatesCallbacksAndRejectsAnotherProbe() {
        val state = OfflineKoreanTextToSpeechProbeState()
        val activeGeneration = state.begin()

        state.close()

        assertFalse(state.complete(activeGeneration, available = true))
        assertNull(state.available)
        try {
            state.begin()
            fail("A closed state must reject another probe")
        } catch (_: IllegalStateException) {
            // Expected.
        }

        val source = startupProbeSource()
        val close = source.substringAfter("override fun close()")
            .substringBefore("private companion object")
        assertTrue(close.contains("offlineKoreanTextToSpeechProbeState.close()"))
        assertTrue(
            close.indexOf("offlineKoreanTextToSpeechProbeState.close()") <
                close.indexOf("previousProbe?.close()"),
        )
    }

    private fun startupProbeSource(): String = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/device/AndroidStartupCapabilityProbe.kt",
    ).readText()
}

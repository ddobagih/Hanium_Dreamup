package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S06-31 (06.txt:233): the search origin keeps its fix time (DestinationSearchLocationController), and
 * a page prompt or the confirmation read long after the search says once that distances may have
 * changed. Nothing is re-measured or re-sorted.
 */
class MainActivityDestinationOriginAgeStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun thePagePromptAndTheConfirmationCarryTheOriginAgeNotice() {
        val prompt = source.substringAfter("private fun speakHomeDestinationVoicePrompt(")
            .substringBefore("val attempt = state.beginVoicePrompt()")
        // voice-F6 (test moved): once per searched list, inside the page next to the distance basis, so the
        // direct answer (message) stays first; it no longer leads every page prompt.
        assertTrue(prompt.contains("destinationOriginAgeNoticeOnce.noticeOrNull(state.origin, SystemClock.elapsedRealtime())"))
        assertTrue(prompt.contains("originAgeNotice?.let { state.voicePrompt(originAgeNotice = it) } ?: candidatePrompt"))
        assertTrue(prompt.contains("message.replace(candidatePrompt, pagePrompt)"))
        assertTrue(prompt.contains("\"\$message \$pagePrompt\""))
        assertFalse(prompt.contains("\"\$it \$prompt\""))
        assertTrue(source.contains("private val destinationOriginAgeNoticeOnce = kr.co.hanium.dreamup.walksafe.navigation.DestinationOriginAgeNoticeOnce()"))
        val delivery = source.substringAfter("val attempt = state.beginVoicePrompt()")
            .substringBefore("private fun previousVoiceDestinationCandidates()")
        assertTrue(delivery.contains("if (current && originAgeNotice != null) destinationOriginAgeNoticeOnce.markDelivered(state.origin)"))
        assertFalse(delivery.substringAfter("onFailed = {").contains("destinationOriginAgeNoticeOnce.markDelivered"))
        val confirmation = source.substringAfter("private fun nativeDestinationConfirmationMessage(): String {")
            .substringBefore("private fun selectedDestinationSpeechKo(")
        assertTrue(confirmation.contains("navigation.destinationOriginAgeNoticeKo(\n            destinationSearchOrigin,"))
        assertTrue(confirmation.contains("distanceKind, originNotice, originAgeNotice)"))
        // The list is not re-searched or re-sorted for an old origin.
        assertFalse(prompt.contains("performDestinationSearch("))
        assertFalse(confirmation.contains("sortedBy"))
    }
}

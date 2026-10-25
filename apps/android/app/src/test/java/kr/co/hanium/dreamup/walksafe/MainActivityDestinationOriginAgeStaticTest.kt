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
        val voicePrompt = MainActivityRefactorSource.function(source, "fun speakHomeDestinationVoicePrompt(")
        val binding = MainActivityRefactorSource.composition(source, "voiceInteractionControllers.destinations")
        assertTrue(binding.contains("scheduler = voiceCallbacks"))
        val callbacks = MainActivityRefactorSource.property(source, "voiceCallbacks")
        assertTrue(callbacks.contains("AndroidVoiceCallbacks("))
        assertFalse(callbacks.contains("clock ="))
        val scheduler = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/AndroidVoiceCallbacks.kt").readText()
        assertTrue(scheduler.contains("private val clock: () -> Long = SystemClock::elapsedRealtime"))
        assertTrue(scheduler.contains("override fun nowMs() = clock()"))
        val prompt = voicePrompt.substringBefore("val attempt = state.beginVoicePrompt()")
        // voice-F6 (test moved): once per searched list, inside the page next to the distance basis, so the
        // direct answer (message) stays first; it no longer leads every page prompt.
        assertTrue(prompt.contains("destinationOriginAgeNoticeOnce.noticeOrNull(state.origin, scheduler.nowMs())"))
        val parts = voicePrompt.substringAfter("val attempt = state.beginVoicePrompt()")
            .substringBefore("onCompleted = {")
        assertTrue(parts.contains("val pageParts = state.voicePromptParts(originAgeNotice)"))
        assertTrue(parts.contains("message.substringBefore(candidatePrompt).trim()"))
        assertTrue(parts.contains("message.substringAfter(candidatePrompt).trim()"))
        assertTrue(parts.contains("val parts = (listOf(prefix) + pageParts + suffix).filter(String::isNotBlank)"))
        assertTrue(parts.contains("output.speakCommandParts("))
        assertTrue(parts.contains("parts = parts,"))
        val state = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidVoiceCommand.kt").readText()
        assertTrue(MainActivityRefactorSource.member(state, "voicePromptParts").contains("val prompt = voicePrompt(originAgeNotice)"))
        assertFalse(prompt.contains("\"\$it \$prompt\""))
        assertTrue(File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/interaction/DestinationVoiceDialogController.kt").readText().contains("private val destinationOriginAgeNoticeOnce = kr.co.hanium.dreamup.walksafe.navigation.DestinationOriginAgeNoticeOnce()"))
        val delivery = voicePrompt.substringAfter("val attempt = state.beginVoicePrompt()")
        assertTrue(delivery.contains("if (current && originAgeNotice != null) destinationOriginAgeNoticeOnce.markDelivered(state.origin)"))
        assertFalse(delivery.substringAfter("onFailed = {").contains("destinationOriginAgeNoticeOnce.markDelivered"))
        assertTrue(MainActivityRefactorSource.member(source, "nativeDestinationConfirmationMessage")
            .contains("NativeGuidanceTextPresentation.destinationConfirmation(pendingUiDestination, { destinationSearchOrigin }, SystemClock::elapsedRealtime)"))
        val confirmation = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeGuidanceTextPresentation.kt").readText()
        assertTrue(confirmation.contains("destinationOriginAgeNoticeKo(origin(), elapsedRealtime())"))
        assertTrue(confirmation.contains("distanceKind, originNotice, originAgeNotice)"))
        // The list is not re-searched or re-sorted for an old origin.
        assertFalse(prompt.contains("search().search("))
        assertFalse(confirmation.contains("sortedBy"))
    }
}

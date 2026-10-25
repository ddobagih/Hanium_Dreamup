package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S13-30: status changes use their view's live region, and the only direct announcement left is the
 * priority-aware TalkBack channel that lets an immediate risk interrupt screen reading (FP-028-DETAIL-01,
 * FP-030-DETAIL-03). No status is announced twice.
 */
class MainActivityAccessibilityAnnouncementStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun onlyTheCentralTalkBackChannelCallsAnnounceForAccessibility() {
        val calls = Regex("""\.presentIfCurrent\(""").findAll(MainActivityRefactorSource.speech).map { it.range.first }.toList()
        assertEquals("direct announcements: ${calls.size}", 1, calls.size)
        val channel = functionBlock("private fun announceForTalkBack(")
        assertTrue(channel.contains("target.presentIfCurrent(message, priority)"))
        assertTrue(channel.contains("riskAnnouncementHoldUntilMs"))
    }

    @Test
    fun accountStatusIsReadOnceThroughItsPoliteLiveRegion() {
        val build = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/AccountAccessPanel.kt").readText()
            .substringAfter("accountAccessStatusText = TextView(context).apply {").substringBefore("ViewCompat.setAccessibilityHeading(accountAccessStatusText, true)")
        assertTrue(build.contains("accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE"))
        val message = functionBlock("private fun showAccountMessage(")
        assertTrue(message.contains("accountAccessPresenter.recordAccountMessage("))
        assertTrue(MainActivityRefactorSource.member(accountPresenter, "recordAccountMessage").contains("accountAccessStatusText.text = message"))
        assertFalse(message.contains("announceForAccessibility("))
    }

    @Test
    fun repeatedOrHiddenAccountMessagesFallBackToTheTalkBackChannel() {
        // S13-30-F1: a live region is silent for an unchanged text or a view that is not shown.
        val message = functionBlock("private fun showAccountMessage(")
        val recording = MainActivityRefactorSource.member(accountPresenter, "recordAccountMessage")
        val changed = recording.indexOf("val changed = accountAccessStatusText.text?.toString() != message")
        val assign = recording.indexOf("accountAccessStatusText.text = message")
        val fallback = message.indexOf("!presentation.changed || !presentation.isShown")
        val announce = message.indexOf("announceForTalkBack(message, TalkBackAnnouncementPriority.INTERACTION)")
        assertTrue("change is measured before the text is replaced", changed in 0 until assign)
        assertTrue(fallback > message.indexOf("accountAccessPresenter.recordAccountMessage("))
        assertTrue(announce > fallback)
    }

    @Test
    fun runtimeSpeechCapabilityFailureUsesThePriorityAwareChannel() {
        val failure = functionBlock("private fun handleRuntimeSpeechCapabilityFailure(")
        assertFalse(failure.contains("announceForAccessibility("))
        assertTrue(failure.contains("if (isScreenReaderActive()) announceTalkBackInteractionAfterVoiceRecognition(detail)"))
    }

    @Test
    fun speechFailureMessageWaitsForRecognitionToEndInsteadOfBeingDropped() {
        // S13-30-F2: after the app TTS failed TalkBack is the only channel; recognition only delays it.
        val helper = functionBlock("private fun announceTalkBackInteractionAfterVoiceRecognition(")
        assertTrue(helper.contains("if (!voiceRecognitionActive) return announceForTalkBack(message, TalkBackAnnouncementPriority.INTERACTION)"))
        assertTrue(helper.contains("scheduleTalkBackInteraction("))
        assertTrue(helper.contains("waitForVoiceRecognitionEnd = true"))
        val schedule = functionBlock("private fun scheduleTalkBackInteraction(")
        val wait = schedule.indexOf("if (waitForVoiceRecognitionEnd && reader.commandContext().recognitionActive)")
        val deliver = schedule.indexOf("announceForTalkBack(")
        assertTrue(wait >= 0)
        assertTrue("recognition is re-checked before delivery", deliver > wait)
        assertTrue(schedule.substring(wait, deliver).contains("scheduleTalkBackInteraction("))
    }

    private val accountPresenter = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/AccountAccessPresenter.kt").readText()

    private fun functionBlock(signature: String): String = MainActivityRefactorSource.function(source, signature)
}

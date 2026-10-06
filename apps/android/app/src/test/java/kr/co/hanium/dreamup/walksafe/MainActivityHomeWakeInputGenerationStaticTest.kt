package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Main wiring checks complement the queued transcript behavior tests in VoskWakePhraseProbeTest. */
class MainActivityHomeWakeInputGenerationStaticTest {
    private val main = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val actuator = File("src/main/java/kr/co/hanium/dreamup/walksafe/feedback/AndroidFeedbackActuator.kt").readText()

    @Test fun homeProbeReadsThePersistentAtomicInputGeneration() {
        assertTrue(main.contains("foregroundHomeWakeInputGeneration = AtomicLong(0L)"))
        val home = method("refreshForegroundHomeWakeListening")
        assertTrue(home.contains("inputGeneration = foregroundHomeWakeInputGeneration::get"))
        assertTrue(home.contains("voiceRecognitionActive || isHandsFreeVoiceRecognitionBlocked()"))
        assertTrue(method("invalidateForegroundHomeWakeInput").contains("foregroundHomeWakeInputGeneration.incrementAndGet()"))
        before(method("cancelForegroundHomeWakeListening"), "invalidateForegroundHomeWakeInput()", "probe?.cancel()")
    }

    @Test fun everyActuatorSpeechUsesTheMainInvalidationBoundary() {
        assertTrue(method("ensureFeedbackActuator").contains("speechAllowed = ::authorizeActuatorSpeechForHomeWakeInput"))
        val authorize = method("authorizeActuatorSpeechForHomeWakeInput")
        before(authorize, "if (!navigationSpeechCapabilityAllowed() && !educationSpeechAllowed) return false", "invalidateForegroundHomeWakeInput()")
        before(authorize, "invalidateForegroundHomeWakeInput()", "return true")
        // Both speech submission and progress beep consult the injected Main boundary.
        assertTrue(actuator.contains("if (!speechAllowed()) return NavigationSpeechDispatchResult.UNAVAILABLE"))
        assertTrue(actuator.contains("if (!outputGate.isCurrent() || !speechAllowed()) return"))
    }

    @Test fun talkBackAndQueuedInteractionInvalidateBeforeTheyBlockInput() {
        val talkBack = method("announceForTalkBack")
        listOf(
            "riskAnnouncementHoldUntilMs = nowMs",
            "lastInteractionAnnouncementMs = nowMs",
            "lastAdvisoryAnnouncementMs = nowMs",
            "navigationAnnouncementHoldUntilMs = nowMs",
        ).forEach { assignment ->
            assertTrue("TalkBack $assignment must invalidate old PCM before holding input",
                Regex("invalidateForegroundHomeWakeInput\\(\\)\\s+" + Regex.escape(assignment)).containsMatchIn(talkBack))
        }
        before(method("scheduleTalkBackInteraction"), "invalidateForegroundHomeWakeInput()", "pendingTalkBackInteraction = delivery")
    }

    @Test fun acknowledgementCommandGatewayAndRecognitionInvalidateBeforeStarting() {
        before(method("acknowledgeForegroundHomeWake"), "invalidateForegroundHomeWakeInput()", "player.play")
        before(method("speakCommandResponse"), "invalidateForegroundHomeWakeInput()", "++commandSpeechResponseGeneration")
        assertTrue(Regex("invalidateForegroundHomeWakeInput\\(\\)\\s+gatewaySpeechPlayer = player").containsMatchIn(main))
        val starts = Regex("voiceRecognitionActive = true").findAll(main).count()
        val invalidatedStarts = Regex("invalidateForegroundHomeWakeInput\\(\\)\\s+voiceRecognitionActive = true").findAll(main).count()
        assertTrue("Every actual recognition start must invalidate queued HOME input", starts > 0 && starts == invalidatedStarts)
    }

    private fun method(name: String): String {
        val start = main.indexOf("    private fun $name(")
        check(start >= 0) { "Missing Main method $name" }
        val next = main.indexOf("\n    private ", start + 1).let { if (it < 0) main.length else it }
        return main.substring(start, next)
    }

    private fun before(source: String, first: String, second: String) {
        val firstIndex = source.indexOf(first)
        val secondIndex = source.indexOf(second)
        assertTrue("$first must run before $second", firstIndex >= 0 && secondIndex > firstIndex)
    }
}

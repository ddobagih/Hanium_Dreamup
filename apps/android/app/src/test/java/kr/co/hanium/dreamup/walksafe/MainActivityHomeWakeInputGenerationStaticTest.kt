package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Main wiring checks complement the queued transcript behavior tests in VoskWakePhraseProbeTest. */
class MainActivityHomeWakeInputGenerationStaticTest {
    private val main = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val actuator = File("src/main/java/kr/co/hanium/dreamup/walksafe/feedback/AndroidFeedbackActuator.kt").readText()

    @Test fun homeProbeReadsThePersistentAtomicInputGeneration() {
        val wake = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/ForegroundWakeInputController.kt").readText()
        assertTrue(MainActivityRefactorSource.composition(main, "voiceInputController.wake").contains("VoiceInputSessionController("))
        assertTrue(wake.contains("foregroundHomeWakeInputGeneration = AtomicLong(0L)"))
        val home = method("refreshForegroundHomeWakeListening")
        assertTrue(home.contains("inputGeneration = foregroundHomeWakeInputGeneration::get"))
        assertTrue(home.contains("session.state().recognitionActive || session.recognitionBlocked()"))
        assertTrue(method("invalidateForegroundHomeWakeInput").contains("foregroundHomeWakeInputGeneration.incrementAndGet()"))
        before(method("cancelForegroundHomeWakeListening"), "invalidateForegroundHomeWakeInput()", "probe?.cancel()")
    }

    @Test fun everyActuatorSpeechUsesTheMainInvalidationBoundary() {
        assertTrue(method("ensureFeedbackActuator").contains("actuatorFactory.create("))
        assertTrue(method("createSpeechOutputController").contains("speechAllowed = ::authorizeActuatorSpeechForHomeWakeInput"))
        assertTrue(method("authorizeActuatorSpeechForHomeWakeInput").contains("authorizeNativeFeedbackSpeech(SystemClock.elapsedRealtime(), isScreenReaderActive())"))
        val authorize = main.substringAfter("internal fun authorizeNativeFeedbackSpeech(").substringBefore("private fun ")
        before(authorize, "if (!navigationSpeechCapabilityAllowed()) return false", "invalidateForegroundHomeWakeInput()")
        before(authorize, "invalidateForegroundHomeWakeInput()", "return true")
        // Both speech submission and progress beep consult the injected Main boundary.
        assertTrue(actuator.contains("if (!speechAllowed()) return NavigationSpeechDispatchResult.UNAVAILABLE"))
        assertTrue(actuator.contains("if (!outputGate.isCurrent() || !speechAllowed()) return"))
    }

    @Test fun talkBackAndQueuedInteractionInvalidateBeforeTheyBlockInput() {
        val talkBack = method("recordAcceptedTalkBackAnnouncement")
        val peers = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/VoiceControllerPeerAdapters.kt").readText()
        assertTrue(method("createSpeechOutputController").contains("input = SpeechInputPeerAdapter({ voiceInputController }, { voiceInteractionControllers })"))
        assertTrue(peers.contains("private val input: () -> VoiceInputSessionController"))
        assertTrue(MainActivityRefactorSource.member(peers, "invalidateHomeWakeInput").contains("input().invalidateForegroundHomeWakeInput()"))
        assertTrue(method("announceForTalkBack").contains("recordAcceptedTalkBackAnnouncement("))
        listOf(
            "riskAnnouncementHoldUntilMs = nowMs",
            "lastInteractionAnnouncementMs = nowMs",
            "lastAdvisoryAnnouncementMs = nowMs",
            "navigationAnnouncementHoldUntilMs = nowMs",
        ).forEach { assignment ->
            assertTrue("TalkBack $assignment must invalidate old PCM before holding input",
                Regex("input\\.invalidateHomeWakeInput\\(\\)\\s+" + Regex.escape(assignment)).containsMatchIn(talkBack))
        }
        before(method("scheduleTalkBackInteraction"), "input.invalidateHomeWakeInput()", "pendingTalkBackInteraction = delivery")
    }

    @Test fun acknowledgementCommandGatewayAndRecognitionInvalidateBeforeStarting() {
        before(method("acknowledgeForegroundHomeWake"), "invalidateForegroundHomeWakeInput()", "player.play")
        before(method("speakCommandResponse"), "input.invalidateHomeWakeInput()", "++commandSpeechResponseGeneration")
        val gateway = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/GatewayVoiceCaptureController.kt").readText()
        val input = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/VoiceInputSessionController.kt").readText()
        assertTrue(MainActivityRefactorSource.composition(main, "voiceInputController.gateway").contains("VoiceInputSessionController("))
        assertTrue(Regex("inputSession.invalidateHomeWakeInput\\(\\)\\s+gatewaySpeechPlayer = player").containsMatchIn(gateway))
        val starts = Regex("voiceRecognitionActive = true").findAll(input).count()
        val invalidatedStarts = Regex("wake.invalidateForegroundHomeWakeInput\\(\\)\\s+voiceRecognitionActive = true").findAll(input).count()
        assertTrue("Every actual recognition start must invalidate queued HOME input", starts > 0 && starts == invalidatedStarts)
    }

    private fun method(name: String): String =
        MainActivityRefactorSource.function(main, "private fun $name(")

    private fun before(source: String, first: String, second: String) {
        val firstIndex = source.indexOf(first)
        val secondIndex = source.indexOf(second)
        assertTrue("$first must run before $second", firstIndex >= 0 && secondIndex > firstIndex)
    }
}

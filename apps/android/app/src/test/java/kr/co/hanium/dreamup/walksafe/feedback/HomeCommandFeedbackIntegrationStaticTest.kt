package kr.co.hanium.dreamup.walksafe.feedback

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wiring checks supplement the behavioral input policy and real-device speech tests. */
class HomeCommandFeedbackIntegrationStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/feedback/AndroidFeedbackActuator.kt",
    ).readText()

    @Test
    fun homeResponseRequiresActualReadyVoiceAndKeepsStrictTerminalAndPriorityHandling() {
        val method = source.substringAfter("fun speakHomeCommandInteraction(")
            .substringBefore("\n    fun speakInteraction(")

        assertTrue(method.contains("if (message.isBlank()) return NavigationSpeechDispatchResult.SUPPRESSED"))
        assertTrue(method.contains("if (ttsState != TtsState.READY) return NavigationSpeechDispatchResult.UNAVAILABLE"))
        assertTrue(method.contains("return speakReady("))
        assertTrue(method.contains("priority = SpeechPriority.INTERACTION"))
        assertTrue(method.contains("onFailed = onFailed"))
        assertTrue(method.contains("requiresExplicitTerminalCallback = true"))
        assertFalse(method.contains("speechAllowed()"))
        assertFalse(method.contains("pendingSpeechQueue.offer"))
    }

    @Test
    fun ordinaryInteractionAndTrainingRetainTheirOriginalGlobalSpeechPolicy() {
        val ordinary = source.substringAfter("fun speakInteraction(message: String): Boolean =")
            .substringBefore("\n\n")
        val training = source.substringAfter("fun speakPriorityUserTraining(")
            .substringBefore("\n    /**")
        val speak = source.substringAfter("private fun speak(")
            .substringBefore("private fun speakReady(")

        assertTrue(ordinary.contains("speak(message, SpeechPriority.INTERACTION)"))
        assertTrue(training.contains("NavigationSpeechDispatchResult = speak("))
        assertTrue(training.contains("requiresExplicitTerminalCallback = true"))
        assertTrue(speak.contains("if (!speechAllowed()) return NavigationSpeechDispatchResult.UNAVAILABLE"))
    }
}

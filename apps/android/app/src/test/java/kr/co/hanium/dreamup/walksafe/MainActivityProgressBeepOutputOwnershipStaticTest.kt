package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityProgressBeepOutputOwnershipStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun progressBeepYieldsToAppGatewayAndTalkBackSpeechOwnership() {
        val beep = source.substringAfter("private fun maybePlayProgressBeep(")
            .substringBefore("private fun updateDebugUploadButton(")
        val guard = beep.indexOf("if (isHandsFreeVoiceSpeechOutputActive()) return")
        val play = beep.indexOf("ensureFeedbackActuator().playProgressBeep(")
        assertTrue("beep must check shared speech ownership", guard >= 0)
        assertTrue("ownership check must precede the native beep", play > guard)
        assertTrue(beep.contains("if (!isWalkSessionRuntimeActive() || !navigationEnvironmentOutputsAllowed()) return"))
        assertTrue(beep.contains("shouldSuppressFeedbackDuringVoiceRecognition(voiceRecognitionActive, isRisk = false)"))

        val owner = source.substringAfter("private fun isHandsFreeVoiceSpeechOutputActive(): Boolean {")
            .substringBefore("private fun handleHandsFreeVoiceCommandListeningStarted(")
        listOf(
            "gatewaySpeechPlayer != null",
            "feedbackActuator?.isAppSpeechActive() == true",
            "if (!isScreenReaderActive()) return false",
            "riskAnnouncementHoldUntilMs",
            "navigationAnnouncementHoldUntilMs",
        ).forEach { token -> assertTrue(token, owner.contains(token)) }
    }
}

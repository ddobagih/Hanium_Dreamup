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
        assertTrue(beep.contains("if (isGuidanceVoiceInputActive()) return"))

        assertTrue(MainActivityRefactorSource.member(source, "isGuidanceVoiceInputActive")
            .contains("guidanceVoiceInputActive { voiceInputController }"))
        val peer = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/VoiceControllerPeerAdapters.kt").readText()
        assertTrue(MainActivityRefactorSource.member(peer, "guidanceVoiceInputActive", occurrence = 1)
            .contains("input().state.recognitionActive"))
        val voice = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/VoiceInputSessionController.kt").readText()
        assertTrue(MainActivityRefactorSource.property(voice, "state").contains("voiceRecognitionActive"))
        val owner = MainActivityRefactorSource.member(source, "isHandsFreeVoiceSpeechOutputActive", occurrence = 1)
        listOf(
            "voiceInputController.gateway.hasPlayer()",
            "currentActuator?.isAppSpeechActive() == true",
            "externalFallbackSpeechOutputActive(nowElapsedRealtimeMs(), observeScreenReaderActive())",
        ).forEach { token -> assertTrue(token, owner.contains(token)) }
        val gateway = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/GatewayVoiceCaptureController.kt").readText()
        assertTrue(MainActivityRefactorSource.member(gateway, "hasPlayer").contains("gatewaySpeechPlayer != null"))
        val fallback = MainActivityRefactorSource.function(source, "internal fun externalFallbackSpeechOutputActive(")
        listOf(
            "if (!screenReaderActive) return false",
            "riskAnnouncementHoldUntilMs",
            "navigationAnnouncementHoldUntilMs",
        ).forEach { token -> assertTrue(token, fallback.contains(token)) }
    }
}

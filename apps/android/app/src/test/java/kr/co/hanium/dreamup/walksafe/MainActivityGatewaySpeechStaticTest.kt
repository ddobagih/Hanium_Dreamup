package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityGatewaySpeechStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val wakeWord =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/WakeWordController.kt").readText()

    @Test
    fun buttonUsesForegroundServerCaptureBehindEmailAndDeviceGate() {
        assertTrue(source.contains("toggleGatewayVoiceCapture()"))
        assertTrue(source.contains("FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4"))
        assertTrue(source.contains("postLoginDeviceCheckPassesFeatureGate()"))
        assertTrue(source.contains("ForegroundAacRecorder("))
        assertTrue(source.contains("transcribeCall("))
    }

    @Test
    fun lifecycleRiskAndSessionChangesCancelTemporarySpeechWork() {
        assertTrue(source.contains("cancelGatewaySpeechInteraction(\"app_paused\")"))
        assertTrue(source.contains("cancelGatewaySpeechInteraction(\"risk_feedback\")"))
        assertTrue(source.contains("cancelGatewaySpeechInteraction(\"gateway_session_changed\")"))
        assertTrue(source.contains("isGatewaySpeechCallbackCurrent("))
    }

    @Test
    fun serverIntentIsNotConsumedAndWakeWordRemainsDefaultOff() {
        assertFalse(source.contains("speechTranscript.intent"))
        assertFalse(source.contains("speechTranscript.action"))
        assertTrue(source.contains("handleVoiceCommandPhrases("))
        assertTrue(wakeWord.contains("PRODUCTION_WAKE_WORD_PROFILE: ApprovedWakeWordProfile? = null"))
    }

    @Test
    fun statusIsLiveAndServerFailureKeepsLocalInteractionFallback() {
        assertTrue(source.contains("ACCESSIBILITY_LIVE_REGION_POLITE"))
        assertTrue(source.contains("speakGatewayInteractionOrLocalFallback("))
        assertTrue(source.contains("speakInteraction(message)"))
    }
}

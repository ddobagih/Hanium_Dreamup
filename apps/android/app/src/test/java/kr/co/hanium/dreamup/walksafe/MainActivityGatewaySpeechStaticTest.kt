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
    fun onScreenButtonUsesOnlyTheOnDeviceRecognizerDuringAnActiveWalk() {
        val button = source.substringAfter("voiceReportButton = Button(this).apply")
            .substringBefore("gatewayVoiceStatusText = TextView(this).apply")
        val walkButton = source.substringAfter("walkSafetyVoiceButton = Button(this).apply")
            .substringBefore("walkSafetyVoiceStatusText = TextView(this).apply")
        val startFromButton = functionBlock("private fun ensureVoicePermissionThenListen")
        val permissionResult = functionBlock("private fun handleVoicePermissionResult")
        val recognition = functionBlock("private fun startVoiceCommandRecognition")
        val commandEligibility = recognition.substringAfter("VoiceRecognitionPurpose.COMMAND -> {")
            .substringBefore("VoiceRecognitionPurpose.WALK_SESSION_RESUME -> {")
        val controls = functionBlock("private fun updateVoiceCommandButton")

        assertTrue(button.contains("기기 내 음성 명령"))
        assertTrue(button.contains("ensureVoicePermissionThenListen()"))
        assertTrue(walkButton.contains("기기 내 음성 명령"))
        assertTrue(walkButton.contains("ensureVoicePermissionThenListen()"))
        assertTrue(startFromButton.contains("startVoiceCommandRecognition()"))
        assertTrue(permissionResult.contains("startVoiceCommandRecognition()"))
        assertFalse(permissionResult.contains("toggleGatewayVoiceCapture()"))
        assertTrue(startFromButton.contains("snapshot.state != WalkSessionState.ACTIVE"))
        assertFalse(startFromButton.contains("toggleGatewayVoiceCapture()"))
        assertFalse(startFromButton.contains("currentGatewaySpeechSessionOrNull()"))
        assertFalse(startFromButton.contains("WalkSessionState.PAUSED"))
        assertTrue(commandEligibility.contains("sessionSnapshot.state == WalkSessionState.ACTIVE"))
        assertFalse(commandEligibility.contains("WalkSessionState.PAUSED"))
        val lease = functionBlock("private fun isVoiceRecognitionLeaseCurrent")
        val commandLease = lease.substringAfter("VoiceRecognitionPurpose.COMMAND ->")
            .substringBefore("VoiceRecognitionPurpose.WALK_SESSION_RESUME ->")
        assertTrue(commandLease.contains("snapshot.state == WalkSessionState.ACTIVE"))
        assertFalse(commandLease.contains("WalkSessionState.PAUSED"))
        assertTrue(
            recognition.indexOf("SpeechRecognizer.isOnDeviceRecognitionAvailable") <
                recognition.indexOf("stopHandsFreeVoiceService()"),
        )
        assertFalse(controls.contains("currentGatewaySpeechSessionOrNull()"))
        assertTrue(controls.contains("snapshot.state == WalkSessionState.ACTIVE"))

        val resumeAvailability = functionBlock("private fun voiceResumeConfirmationAvailable")
        assertTrue(resumeAvailability.contains("oneShotSpeechRecognitionLimited"))
        assertTrue(
            resumeAvailability.contains("SpeechRecognizer.isOnDeviceRecognitionAvailable"),
        )
        assertFalse(resumeAvailability.contains("PostLoginDeviceCheckFeature.HANDS_FREE_VOICE"))
        assertFalse(resumeAvailability.contains("WalkSafeStartupRequirement.ON_DEVICE_STT"))
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

    private fun functionBlock(signature: String): String =
        ReportStaticSourceInspector.functionBlock(source, signature)
}

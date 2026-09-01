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
    fun onScreenButtonUsesOnlyTheOnDeviceRecognizerDuringAnActiveOrPausedWalk() {
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
        assertTrue(
            startFromButton.contains(
                "snapshot.state !in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED)",
            ),
        )
        assertFalse(startFromButton.contains("toggleGatewayVoiceCapture()"))
        assertFalse(startFromButton.contains("currentGatewaySpeechSessionOrNull()"))
        assertTrue(commandEligibility.contains("WalkSessionState.ACTIVE"))
        assertTrue(commandEligibility.contains("WalkSessionState.PAUSED"))
        assertTrue(commandEligibility.contains("voiceResumeConfirmationAvailable()"))
        val lease = functionBlock("private fun isVoiceRecognitionLeaseCurrent")
        val commandLease = lease.substringAfter("VoiceRecognitionPurpose.COMMAND ->")
            .substringBefore("VoiceRecognitionPurpose.WALK_SESSION_RESUME ->")
        assertTrue(commandLease.contains("WalkSessionState.ACTIVE"))
        assertTrue(commandLease.contains("WalkSessionState.PAUSED"))
        assertTrue(
            recognition.indexOf("SpeechRecognizer.isOnDeviceRecognitionAvailable") <
                recognition.indexOf("stopHandsFreeVoiceService()"),
        )
        assertFalse(controls.contains("currentGatewaySpeechSessionOrNull()"))
        assertTrue(controls.contains("WalkSessionState.ACTIVE"))
        assertTrue(controls.contains("WalkSessionState.PAUSED"))
        val buttonEligibility = controls
            .substringAfter("val voiceCommandAvailable =")
            .substringBefore("val enabled =")
        assertTrue(buttonEligibility.contains("WalkSessionState.ACTIVE ->"))
        assertTrue(buttonEligibility.contains("!oneShotSpeechRecognitionLimited"))
        assertTrue(buttonEligibility.contains("WalkSessionState.PAUSED ->"))
        assertTrue(buttonEligibility.contains("voiceResumeConfirmationAvailable()"))
        assertTrue(controls.contains("snapshot.isForeground &&\n                voiceCommandAvailable"))

        val resumeAvailability = functionBlock("private fun voiceResumeConfirmationAvailable")
        assertTrue(resumeAvailability.contains("oneShotSpeechRecognitionLimited"))
        assertTrue(
            resumeAvailability.contains("SpeechRecognizer.isOnDeviceRecognitionAvailable"),
        )
        assertTrue(resumeAvailability.contains("hasRecordAudioPermission()"))
        assertFalse(resumeAvailability.contains("PostLoginDeviceCheckFeature.HANDS_FREE_VOICE"))
        assertFalse(resumeAvailability.contains("WalkSafeStartupRequirement.ON_DEVICE_STT"))
    }

    @Test
    fun notificationAndDisclosureLimitOnlyHandsFreeVoice() {
        val disabledFeatures = functionBlock("private fun currentPostLoginDisabledFeatures")
        val disabledFeatureText =
            functionBlock("private fun postLoginDeviceCheckDisabledFeatureText")
        val restrictions = functionBlock("private fun applyPostLoginDeviceFeatureRestrictions")
        val handsFreeRestriction = restrictions
            .substringAfter("PostLoginDeviceCheckFeature.HANDS_FREE_VOICE ->")
            .substringBefore("PostLoginDeviceCheckFeature.VOICE_GUIDANCE ->")
        val handsFreeStart = functionBlock("private fun maybeStartHandsFreeVoiceService")
        val permissionChange = functionBlock("private fun applyObservedPermissionStateChange")
        val notificationBranch = permissionChange
            .substringAfter("if (!hasHandsFreeNotificationPermission()) {")
            .substringBefore("if (!hasRecordAudioPermission()) {")
        val microphoneBranch = permissionChange
            .substringAfter("if (!hasRecordAudioPermission()) {")
            .substringBefore("if (!hasActivityRecognitionPermission()) {")

        assertTrue(disabledFeatures.contains("!hasHandsFreeNotificationPermission()"))
        assertTrue(disabledFeatures.contains("!isHandsFreeVoiceDisclosureAccepted()"))
        assertTrue(
            handsFreeRestriction.contains(
                "feature in postLoginDeviceCheckSnapshot.disabledFeatures",
            ),
        )
        assertTrue(handsFreeRestriction.contains("WalkSafeStartupRequirement.MICROPHONE"))
        assertTrue(handsFreeRestriction.contains("WalkSafeStartupRequirement.ON_DEVICE_STT"))
        assertTrue(handsFreeStart.contains("!hasHandsFreeNotificationPermission()"))
        assertTrue(handsFreeStart.contains("!isHandsFreeVoiceDisclosureAccepted()"))
        assertTrue(disabledFeatureText.contains("postLoginDeviceCheckSnapshot.disabledFeatures"))
        assertTrue(disabledFeatureText.contains("!hasRecordAudioPermission()"))
        assertTrue(disabledFeatureText.contains("\"호출어·음성 명령\""))
        assertTrue(disabledFeatureText.contains("\"호출어 대기\""))
        assertTrue(notificationBranch.contains("stopHandsFreeVoiceService()"))
        assertFalse(notificationBranch.contains("cancelVoiceCommandRecognition()"))
        assertTrue(microphoneBranch.contains("stopHandsFreeVoiceService()"))
        assertTrue(microphoneBranch.contains("cancelVoiceCommandRecognition()"))
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

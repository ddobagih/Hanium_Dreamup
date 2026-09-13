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
    fun onScreenButtonPrefersInstalledKoreanPlatformOnlyAtHomeAndKeepsVoskForWalkFlows() {
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
        val homeContext = functionBlock("private fun nativeHomeFeatureContextAvailable")

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
        assertTrue(startFromButton.contains("nativeUiPage.isVoiceInteractionPage()"))
        val voicePages = source.substringAfter("private fun NativeUiPage.isVoiceInteractionPage()")
            .substringBefore("\n    private fun ")
        assertTrue(voicePages.contains("this == NativeUiPage.VOICE_COMMAND || this == NativeUiPage.DESTINATION_SEARCH"))
        assertFalse(voicePages.contains("NativeUiPage.SETTINGS"))
        assertFalse(voicePages.contains("NativeUiPage.HOME"))
        assertTrue(startFromButton.contains("homeVoiceCommandAvailable()"))
        assertTrue(commandEligibility.contains("WalkSessionState.ACTIVE"))
        assertTrue(commandEligibility.contains("WalkSessionState.PAUSED"))
        assertTrue(commandEligibility.contains("WalkSessionState.READY"))
        assertTrue(commandEligibility.contains("WalkSessionState.SAFE_STOP"))
        assertTrue(commandEligibility.contains("WalkSessionState.ENDED"))
        assertTrue(commandEligibility.contains("sessionSnapshot.isForeground"))
        assertTrue(commandEligibility.contains("nativeUiPage.isVoiceInteractionPage()"))
        assertTrue(commandEligibility.contains("homeVoiceCommandAvailable()"))
        assertTrue(commandEligibility.contains("voiceResumeConfirmationAvailable()"))
        val lease = functionBlock("private fun isVoiceRecognitionLeaseCurrent")
        val commandLease = lease.substringAfter("VoiceRecognitionPurpose.COMMAND ->")
            .substringBefore("VoiceRecognitionPurpose.WALK_SESSION_RESUME ->")
        assertTrue(commandLease.contains("WalkSessionState.ACTIVE"))
        assertTrue(commandLease.contains("WalkSessionState.PAUSED"))
        assertTrue(commandLease.contains("snapshot.epoch == expectedWalkEpoch"))
        assertTrue(commandLease.contains("snapshot.isForeground"))
        assertTrue(commandLease.contains("nativeUiPage.isVoiceInteractionPage()"))
        assertTrue(commandLease.contains("homeVoiceCommandAvailable()"))
        assertTrue(homeContext.contains("firstRunOnboardingComplete()"))
        assertTrue(homeContext.contains("isActivityForeground"))
        assertTrue(homeContext.contains("WalkSessionState.READY"))
        assertTrue(homeContext.contains("WalkSessionState.SAFE_STOP"))
        assertTrue(homeContext.contains("WalkSessionState.ENDED"))
        assertFalse(homeContext.contains("WalkSessionState.ACTIVE"))
        assertFalse(homeContext.contains("WalkSessionState.PAUSED"))
        assertTrue(
            recognition.contains(
                "val preferPlatform = purpose == VoiceRecognitionPurpose.COMMAND &&",
            ),
        )
        assertTrue(recognition.contains("nativeHomeFeatureContextAvailable()"))
        assertTrue(
            recognition.contains(
                "if (handsFreeVoiceModelDirectory == null && !preferPlatform)",
            ),
        )
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                recognition,
                "val preferPlatform = purpose == VoiceRecognitionPurpose.COMMAND &&",
                "if (handsFreeVoiceModelDirectory == null) prepareHandsFreeVoiceModel()",
                "if (handsFreeVoiceModelDirectory == null && !preferPlatform)",
                "stopHandsFreeVoiceService()",
                "PreferredOfflineSpeechRecognizer(this) { handsFreeVoiceModelDirectory }",
                "preferPlatform = preferPlatform",
                "isRequestCurrent = {",
            ),
        )
        assertFalse(recognition.contains("SpeechRecognizer.isOnDeviceRecognitionAvailable"))
        assertFalse(recognition.contains("SpeechRecognizer.createOnDeviceSpeechRecognizer"))
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
        assertTrue(buttonEligibility.contains("homeVoiceCommandAvailable()"))
        assertTrue(controls.contains("!handsFreeVoiceModelPreparing"))
        assertTrue(controls.contains("snapshot.isForeground &&\n                voiceCommandAvailable"))

        val resumeAvailability = functionBlock("private fun voiceResumeConfirmationAvailable")
        assertTrue(resumeAvailability.contains("oneShotSpeechRecognitionLimited"))
        assertTrue(resumeAvailability.contains("handsFreeVoiceModelDirectory != null"))
        assertTrue(resumeAvailability.contains("!handsFreeVoiceDestroyed"))
        assertFalse(resumeAvailability.contains("SpeechRecognizer.isOnDeviceRecognitionAvailable"))
        assertTrue(resumeAvailability.contains("hasRecordAudioPermission()"))
        assertFalse(resumeAvailability.contains("PostLoginDeviceCheckFeature.HANDS_FREE_VOICE"))
        assertTrue(resumeAvailability.contains("WalkSafeStartupRequirement.ON_DEVICE_STT"))
        assertTrue(resumeAvailability.contains("WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS"))
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
        // This expression-bodied helper cannot be read by the brace-only functionBlock.
        val homeVoiceAvailability = source
            .substringAfter("private fun homeVoiceCommandAvailable", missingDelimiterValue = "")
            .substringBefore("\n    private fun ")
        val capabilityResolution = functionBlock("private fun resolveCurrentStartupCapabilityDecision")
        val oneShotPolicy = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/voice/OneShotVoiceInputPolicy.kt",
        ).readText()
        val permissionChange = functionBlock("private fun applyObservedPermissionStateChange")
        val notificationBranch = permissionChange
            .substringAfter("if (!hasHandsFreeNotificationPermission()) {")
            .substringBefore("if (!hasRecordAudioPermission()) {")
        val microphoneBranch = permissionChange
            .substringAfter("if (!hasRecordAudioPermission()) {")
            .substringBefore("if (!hasActivityRecognitionPermission()) {")

        assertTrue(disabledFeatures.contains("!hasHandsFreeNotificationPermission()"))
        assertTrue(disabledFeatures.contains("!isHandsFreeVoiceDisclosureAccepted()"))
        assertFalse(handsFreeRestriction.contains("WalkSafeStartupRequirement.MICROPHONE"))
        assertFalse(handsFreeRestriction.contains("WalkSafeStartupRequirement.ON_DEVICE_STT"))
        assertTrue(
            handsFreeStart.contains(
                "!postLoginDeviceFeatureEnabled(PostLoginDeviceCheckFeature.HANDS_FREE_VOICE)",
            ),
        )
        assertTrue(homeVoiceAvailability.contains("OneShotVoiceInputPolicy.isAvailable("))
        assertTrue(homeVoiceAvailability.contains("microphoneGranted = hasRecordAudioPermission()"))
        assertTrue(homeVoiceAvailability.contains("capabilityDecision = startupCapabilityDecision"))
        assertTrue(capabilityResolution.contains("startupCapabilityProbe.decision("))
        assertTrue(
            capabilityResolution.contains(
                "onDeviceSpeechRecognitionOverride = onDeviceSpeechRecognitionCapabilityOverride",
            ),
        )
        assertTrue(oneShotPolicy.contains("if (!contextAvailable || !microphoneGranted) return false"))
        assertTrue(oneShotPolicy.contains("return INPUT_REQUIREMENTS.none {"))
        assertTrue(oneShotPolicy.contains("it in capabilityDecision.unavailableRequirements ||"))
        assertTrue(oneShotPolicy.contains("it in capabilityDecision.pendingRequirements"))
        assertTrue(oneShotPolicy.contains("WalkSafeStartupRequirement.MICROPHONE,"))
        assertTrue(oneShotPolicy.contains("WalkSafeStartupRequirement.ON_DEVICE_STT,"))
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

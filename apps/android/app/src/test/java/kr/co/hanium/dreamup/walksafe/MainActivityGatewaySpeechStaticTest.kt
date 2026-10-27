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
    private val destinationPanel =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DestinationGuidancePanel.kt").readText()
    private val runtimeComposer =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRuntimeViewComposer.kt").readText()
    private val voicePlatform =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/AndroidVoiceInputPlatform.kt").readText()
    private val voiceInputComposition
        get() = MainActivityRefactorSource.composition(source, "voiceInputController")

    @Test
    fun commandsUseConfiguredServerWhileOfflineConfirmationAndOwnershipRemainGuarded() {
        val button = MainActivityRefactorSource.property(destinationPanel, "voiceReportButton")
        assertTrue(button.contains("음성 명령 듣기 시작"))
        assertTrue(button.contains("actions.onVoiceStart()"))
        val start = functionBlock("private fun ensureVoicePermissionThenListen")
        assertTrue(start.contains("environment.microphoneGranted()"))
        assertTrue(start.contains("environment.walkAreaCommandAvailable()"))
        assertTrue(start.contains("startVoiceCommandRecognition()"))
        val recognition = functionBlock("private fun startVoiceCommandRecognition")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(recognition,
            "if (!mayListen) return false", "output.prepareForRecognition()",
            "environment.serverCommandRecognitionEnabled() && purpose == VoiceRecognitionPurpose.COMMAND",
            "if (!promptCompletedForThisRequest) return prepareOneShotVoiceCommandPrompt()",
            "gateway.startGatewayVoiceCapture()", "return started", "val preferPlatform"))
        assertTrue(recognition.contains("platform.createRecognizer { handsFreeRuntime.modelDirectory }"))
        assertTrue(recognition.contains("isRequestCurrent = {"))
        assertTrue(voiceInputComposition.contains("override fun homeFeatureContextAvailable() = nativeHomeFeatureContextAvailable()"))
        assertTrue(voiceInputComposition.contains("override fun walkAreaCommandAvailable() = walkAreaVoiceCommandAvailable()"))
        val resume = functionBlock("private fun voiceResumeConfirmationAvailable")
        assertTrue(resume.contains("environment.microphoneGranted()"))
        assertTrue(resume.contains("WalkSafeStartupRequirement.ON_DEVICE_STT"))
        assertTrue(resume.contains("WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS"))
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
        val homeVoiceAvailability = functionBlock("private fun homeVoiceCommandAvailable")
        val capabilityResolution = functionBlock("private fun resolveCurrentStartupCapabilityDecision")
        val capabilityComposition = MainActivityRefactorSource.composition(source, "startupCapabilityCoordinator")
        val oneShotPolicy = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/voice/OneShotVoiceInputPolicy.kt",
        ).readText()
        val permissionChange = functionBlock("private fun applyObservedPermissionStateChange")
        val notificationBranch = permissionChange
            .substringAfter("if (!platform.notificationGranted()) {")
            .substringBefore("if (!platform.microphoneGranted()) {")
        val microphoneBranch = permissionChange
            .substringAfter("if (!platform.microphoneGranted()) {")
            .substringBefore("if (!platform.activityRecognitionGranted()) {")

        assertTrue(disabledFeatures.contains("!facts.notificationPermission()"))
        assertTrue(disabledFeatures.contains("!facts.disclosureAccepted()"))
        val deviceInteraction = MainActivityRefactorSource.composition(source, "postLoginDeviceInteraction")
        assertTrue(deviceInteraction.contains("override fun notificationPermission() = hasHandsFreeNotificationPermission()"))
        assertTrue(deviceInteraction.contains("override fun disclosureAccepted() = isHandsFreeVoiceDisclosureAccepted()"))
        assertFalse(handsFreeRestriction.contains("WalkSafeStartupRequirement.MICROPHONE"))
        assertFalse(handsFreeRestriction.contains("WalkSafeStartupRequirement.ON_DEVICE_STT"))
        assertTrue(
            handsFreeStart.contains(
                "!environment.handsFreeFeatureEnabled()",
            ),
        )
        assertTrue(homeVoiceAvailability.contains("OneShotVoiceInputPolicy.isAvailable("))
        assertTrue(voiceInputComposition.contains("override fun handsFreeFeatureEnabled() = postLoginDeviceFeatureEnabled(PostLoginDeviceCheckFeature.HANDS_FREE_VOICE)"))
        assertTrue(homeVoiceAvailability.contains("microphoneGranted = environment.microphoneGranted()"))
        assertTrue(homeVoiceAvailability.contains("capabilityDecision = environment.capabilityDecision()"))
        assertTrue(voiceInputComposition.contains("override fun microphoneGranted() = hasRecordAudioPermission()"))
        assertTrue(voiceInputComposition.contains("override fun capabilityDecision() = startupCapabilityDecision"))
        assertTrue(capabilityResolution.contains("evaluation.probeDecision(metricDistanceOverride)"))
        assertTrue(capabilityComposition.contains("startupCapabilityProbe.decision("))
        assertTrue(
            capabilityComposition.contains(
                "onDeviceSpeechRecognitionOverride = onDeviceSpeechRecognitionCapabilityOverride",
            ),
        )
        assertTrue(oneShotPolicy.contains("if (!contextAvailable || !microphoneGranted) return false"))
        assertTrue(oneShotPolicy.contains("return INPUT_REQUIREMENTS.none {"))
        assertTrue(oneShotPolicy.contains("it in capabilityDecision.unavailableRequirements ||"))
        assertTrue(oneShotPolicy.contains("it in capabilityDecision.pendingRequirements"))
        assertTrue(oneShotPolicy.contains("WalkSafeStartupRequirement.MICROPHONE,"))
        assertTrue(oneShotPolicy.contains("WalkSafeStartupRequirement.ON_DEVICE_STT,"))
        assertTrue(handsFreeStart.contains("!environment.notificationGranted()"))
        assertTrue(handsFreeStart.contains("!environment.disclosureAccepted()"))
        assertTrue(voiceInputComposition.contains("override fun notificationGranted() = hasHandsFreeNotificationPermission()"))
        assertTrue(voiceInputComposition.contains("override fun disclosureAccepted() = isHandsFreeVoiceDisclosureAccepted()"))
        assertTrue(disabledFeatureText.contains("facts.snapshot.disabledFeatures"))
        assertTrue(disabledFeatureText.contains("!facts.recordAudioPermission()"))
        assertTrue(deviceInteraction.contains("override val snapshot get() = postLoginDeviceCheckSnapshot"))
        assertTrue(deviceInteraction.contains("override fun recordAudioPermission() = hasRecordAudioPermission()"))
        assertTrue(disabledFeatureText.contains("DeviceCheckPresenter.postLoginDeviceCheckDisabledFeatureText("))
        val featurePresentation = ReportStaticSourceInspector.functionBlock(
            File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DeviceCheckPresenter.kt").readText(),
            "fun postLoginDeviceCheckDisabledFeatureText",
        )
        assertTrue(featurePresentation.contains("\"호출어·음성 명령\""))
        assertTrue(featurePresentation.contains("\"호출어 대기\""))
        assertTrue(notificationBranch.contains("runtime.stopHandsFree()"))
        assertFalse(notificationBranch.contains("runtime.cancelVoiceRecognition()"))
        assertTrue(microphoneBranch.contains("runtime.stopHandsFree()"))
        assertTrue(microphoneBranch.contains("runtime.cancelVoiceRecognition()"))
        val permissions = MainActivityRefactorSource.composition(source, "permissionFlowController")
        assertTrue(permissions.contains("override fun stopHandsFree() { stopHandsFreeVoiceService() }"))
        assertTrue(permissions.contains("override fun cancelVoiceRecognition() { cancelVoiceCommandRecognition() }"))
    }

    @Test
    fun lifecycleRiskAndSessionChangesCancelTemporarySpeechWork() {
        assertTrue(source.contains("cancelGatewaySpeechInteraction(\"app_paused\")"))
        assertTrue(functionBlock("private fun emitFeedbackAction").contains("cancelGatewaySpeechInteraction(\"risk_feedback\")"))
        val gatewayOwner = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/GatewayVoiceCaptureController.kt").readText()
        val gatewayNotification = ReportStaticSourceInspector.functionBlock(gatewayOwner, "fun onProcessSessionChanged")
        assertTrue(gatewayNotification.contains("cancelGatewaySpeechInteraction(\"gateway_session_changed\")"))
        assertTrue(functionBlock("private fun isGatewaySpeechInteractionCurrent").contains("isGatewaySpeechCallbackCurrent("))
        val actorSession = MainActivityRefactorSource.composition(source, "actorSessionLifecycleCoordinator")
        val notifyInput = ReportStaticSourceInspector.maskNonCode(
            ReportStaticSourceInspector.functionBlock(actorSession, "override fun voiceGatewayChanged"),
        ).replace(Regex("\\s+"), " ").trim()
        assertTrue(notifyInput == "{ voiceInputController.onProcessSessionChanged(snapshot) }")
        val inputOwner = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/VoiceInputSessionController.kt").readText()
        val inputNotification = ReportStaticSourceInspector.maskNonCode(
            ReportStaticSourceInspector.functionBlock(inputOwner, "fun onProcessSessionChanged"),
        )
        assertTrue(Regex(Regex.escape("gateway.onProcessSessionChanged(snapshot)")).findAll(inputNotification).count() == 1)
        assertTrue(delegatedOwner("onGatewayProcessSessionChanged", "actorSessionLifecycleCoordinator", "session/gateway/ActorSessionLifecycleCoordinator.kt", "onGatewayProcessSessionChanged", "{ actorSessionLifecycleCoordinator.onGatewayProcessSessionChanged(snapshot, consentAuthorityChanged) refreshGuidanceDiagnosticAuthority() }").contains("runtime.voiceGatewayChanged(snapshot)"))
        val risk = MainActivityRefactorSource.composition(source, "riskFeedbackComponents.delivery")
        assertTrue(risk.contains("RiskVoicePeerAdapter({ speechOutputController }, { voiceInputController })"))
        val peers = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/VoiceControllerPeerAdapters.kt").readText()
        assertTrue(peers.contains("override fun cancelGatewaySpeechInteraction(reason: String) = input().gateway.cancelGatewaySpeechInteraction(reason)"))
    }

    @Test
    fun serverIntentIsNotConsumedAndWakeWordRemainsDefaultOff() {
        assertFalse(source.contains("speechTranscript.intent"))
        assertFalse(source.contains("speechTranscript.action"))
        assertTrue(source.contains("handleVoiceCommandPhrases("))
        val gatewayTranscript = functionBlock("private fun handleGatewaySpeechTranscript")
        assertFalse(gatewayTranscript.contains("speechTranscript.intent"))
        assertFalse(gatewayTranscript.contains("speechTranscript.action"))
        assertTrue(wakeWord.contains("PRODUCTION_WAKE_WORD_PROFILE: ApprovedWakeWordProfile? = null"))
    }

    @Test
    fun statusIsLiveAndServerFailureKeepsLocalInteractionFallback() {
        assertTrue(source.contains("ACCESSIBILITY_LIVE_REGION_POLITE"))
        val fallback = functionBlock("private fun speakGatewayInteractionOrLocalFallback")
        assertTrue(fallback.contains("output.speakInteraction(message)"))
        assertTrue(functionBlock("private fun handleGatewaySpeechTranscript").contains("output.speakInteraction("))
        assertTrue(source.contains("speakInteraction(message)"))
    }

    private fun functionBlock(signature: String): String =
        MainActivityRefactorSource.function(source, signature)

    // Structural wiring and owner policy are checked separately; this does not execute Main.
    private fun delegatedOwner(name: String, owner: String, path: String, target: String, expectedBody: String): String {
        val caller = MainActivityRefactorSource.factory(source, name)
        // Match the whole caller, including additional effects; a guarded call or lambda is different.
        val declaration = caller.filterNot(Char::isWhitespace)
        val body = Regex.escape(expectedBody.filterNot(Char::isWhitespace))
        val expected = Regex("""^(?:private|internal|public|override)*fun$name\([^{}]*\)(?::[\w?<>]+)?$body$""")
        assertTrue("Main caller changed for $owner.$target: $caller", expected.matches(declaration))
        assertTrue(expectedBody.contains("$owner.$target("))
        MainActivityRefactorSource.composition(source, owner)
        return MainActivityRefactorSource.member(
            File("src/main/java/kr/co/hanium/dreamup/walksafe/$path").readText(), target,
        )
    }
}

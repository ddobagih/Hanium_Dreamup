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
    fun onScreenButtonPrefersInstalledKoreanPlatformOnlyAtHomeAndKeepsVoskForWalkFlows() {
        val button = MainActivityRefactorSource.property(destinationPanel, "voiceReportButton")
        val walkButton = MainActivityRefactorSource.member(runtimeComposer, "createWalkVoiceAction")
        val destinationActions = source.substringAfter("val destinationPanel = DestinationGuidancePanel(")
            .substringBefore("destinationSearchPresenter.bindPanel(destinationPanel)")
        val walkButtonBinding = source.substringAfter("walkSafetyVoiceButton = runtimeComposer.createWalkVoiceAction(")
            .substringBefore("walkSafetyVoiceStatusText = runtimeComposer.createWalkVoiceStatus()")
        val startFromButton = functionBlock("private fun ensureVoicePermissionThenListen")
        val permissionResult = functionBlock("private fun handleVoicePermissionResult")
        val recognition = functionBlock("private fun startVoiceCommandRecognition")
        val commandEligibility = recognition.substringAfter("VoiceRecognitionPurpose.COMMAND -> {")
            .substringBefore("VoiceRecognitionPurpose.WALK_SESSION_RESUME -> {")
        val controls = functionBlock("private fun updateVoiceCommandButton")
        val homeContext = functionBlock("private fun nativeHomeFeatureContextAvailable")

        assertTrue(button.contains("기기 내 음성 명령"))
        assertTrue(button.contains("actions.onVoiceStart()"))
        assertTrue(ReportStaticSourceInspector.blockAfter(destinationActions, "onVoiceStart =").contains("ensureVoicePermissionThenListen()"))
        assertTrue(walkButton.contains("기기 내 음성 명령"))
        assertTrue(walkButton.contains("setOnClickListener { onClick() }"))
        assertTrue(walkButtonBinding.contains("ensureVoicePermissionThenListen()"))
        assertTrue(source.contains("val runtimeComposer = NativeRuntimeViewComposer(this, viewStyle)"))
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
        assertTrue(startFromButton.contains("environment.page().voiceInteraction"))
        assertTrue(voiceInputComposition.contains("VoiceInputPage(nativeUiPage, nativeUiPage.isVoiceInteractionPage(),"))
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
        assertTrue(commandEligibility.contains("environment.page().voiceInteraction"))
        assertTrue(commandEligibility.contains("homeVoiceCommandAvailable()"))
        assertTrue(commandEligibility.contains("voiceResumeConfirmationAvailable()"))
        val lease = functionBlock("private fun isVoiceRecognitionLeaseCurrent")
        val commandLease = lease.substringAfter("VoiceRecognitionPurpose.COMMAND ->")
            .substringBefore("VoiceRecognitionPurpose.WALK_SESSION_RESUME ->")
        assertTrue(commandLease.contains("WalkSessionState.ACTIVE"))
        assertTrue(commandLease.contains("WalkSessionState.PAUSED"))
        assertTrue(commandLease.contains("snapshot.epoch == expectedWalkEpoch"))
        assertTrue(commandLease.contains("snapshot.isForeground"))
        assertTrue(commandLease.contains("environment.page().voiceInteraction"))
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
        assertTrue(recognition.contains("environment.homeFeatureContextAvailable()"))
        assertTrue(voiceInputComposition.contains("override fun homeFeatureContextAvailable() = nativeHomeFeatureContextAvailable()"))
        assertTrue(
            recognition.contains(
                "if (handsFreeRuntime.modelDirectory == null && !preferPlatform)",
            ),
        )
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                recognition,
                "val preferPlatform = purpose == VoiceRecognitionPurpose.COMMAND &&",
                "if (handsFreeRuntime.modelDirectory == null) prepareHandsFreeVoiceModel()",
                "if (handsFreeRuntime.modelDirectory == null && !preferPlatform)",
                "stopHandsFreeVoiceService()",
                "platform.createRecognizer { handsFreeRuntime.modelDirectory }",
                "preferPlatform = preferPlatform",
                "isRequestCurrent = {",
            ),
        )
        assertFalse(recognition.contains("SpeechRecognizer.isOnDeviceRecognitionAvailable"))
        assertFalse(recognition.contains("SpeechRecognizer.createOnDeviceSpeechRecognizer"))
        assertTrue(voiceInputComposition.contains("val platform = AndroidVoiceInputPlatform(this)"))
        assertTrue(voiceInputComposition.contains("platform = platform,"))
        val createRecognizer = MainActivityRefactorSource.member(voicePlatform, "createRecognizer")
        assertTrue(createRecognizer.contains("PreferredOfflineSpeechRecognizer(context, modelDirectory)"))
        assertTrue(createRecognizer.contains("delegate.startListening(intent, preferPlatform, isRequestCurrent)"))
        assertFalse(createRecognizer.contains("SpeechRecognizer.isOnDeviceRecognitionAvailable"))
        assertFalse(createRecognizer.contains("SpeechRecognizer.createOnDeviceSpeechRecognizer"))
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
        assertTrue(resumeAvailability.contains("handsFreeRuntime.modelDirectory != null"))
        assertTrue(resumeAvailability.contains("!handsFreeRuntime.destroyed"))
        assertFalse(resumeAvailability.contains("SpeechRecognizer.isOnDeviceRecognitionAvailable"))
        assertTrue(resumeAvailability.contains("environment.microphoneGranted()"))
        assertTrue(voiceInputComposition.contains("override fun microphoneGranted() = hasRecordAudioPermission()"))
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
        assertTrue(functionBlock("private fun handleGatewaySpeechTranscript").contains("speakGatewayInteractionOrLocalFallback("))
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

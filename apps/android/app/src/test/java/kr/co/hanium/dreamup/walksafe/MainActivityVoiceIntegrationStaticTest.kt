package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityVoiceIntegrationStaticTest {
    private val source = sequenceOf(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        File("app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
    ).first { it.isFile }.readText()

    private fun method(name: String): String {
        val start = source.indexOf("    private fun $name(")
        check(start >= 0)
        val end = source.indexOf("\n    private fun ", start + 1).let {
            if (it < 0) source.length else it
        }
        return source.substring(start, end)
    }

    @Test fun homePrefersInstalledPlatformWhileResumeKeepsBundledFallback() {
        val start = method("startVoiceCommandRecognition")
        assertTrue(start.contains("PreferredOfflineSpeechRecognizer(this) { handsFreeVoiceModelDirectory }"))
        assertTrue(start.contains("purpose == VoiceRecognitionPurpose.COMMAND"))
        assertTrue(start.contains("nativeHomeFeatureContextAvailable()"))
        assertTrue(start.contains("handsFreeVoiceModelDirectory == null && !preferPlatform"))
        assertTrue(start.contains("preferPlatform = preferPlatform"))
        assertTrue(start.contains("isRequestCurrent = {"))
        assertFalse(start.contains("createOnDeviceSpeechRecognizer"))
        assertTrue(method("voiceResumeConfirmationAvailable").contains("handsFreeVoiceModelDirectory != null"))
        assertFalse(method("voiceResumeConfirmationAvailable").contains("isOnDeviceRecognitionAvailable"))
    }

    @Test fun readyHapticRunsOnlyInsideTheCurrentRecognitionLease() {
        val ready = method("buildVoiceCommandRecognitionListener")
            .substringAfter("override fun onReadyForSpeech(params: Bundle?) {")
            .substringBefore("override fun onBeginningOfSpeech()")
        val leaseCheck = ready.indexOf("isVoiceRecognitionLeaseCurrent(")
        val guardStart = ready.indexOf(") {", leaseCheck)
        val guardEnd = ready.indexOf("\n                }", guardStart)
        val cue = ready.indexOf("ensureFeedbackActuator().playVoiceListeningStartVibration()")
        val prompt = ready.indexOf("updateVoiceRecognitionSignal(\"말씀하세요\")")
        assertTrue(leaseCheck >= 0)
        assertTrue(guardStart > leaseCheck)
        assertTrue(guardEnd > guardStart)
        assertTrue(cue in guardStart until guardEnd)
        assertTrue(prompt in (cue + 1) until guardEnd)
        assertFalse(ready.contains("speakInteraction("))
        assertFalse(ready.contains("playProgressBeep("))
    }

    @Test fun homeInputDoesNotDependOnOutputEngine() {
        val home = method("homeVoiceCommandAvailable")
        assertFalse(home.contains("VOICE_GUIDANCE"))
        assertFalse(home.contains("VERSION_CODES.S"))
        assertFalse(home.contains("OFFLINE_KOREAN_TTS"))
        assertFalse(home.contains("allowsRequirement("))
        assertTrue(home.contains("OneShotVoiceInputPolicy.isAvailable("))
        assertTrue(home.contains("contextAvailable = nativeHomeFeatureContextAvailable()"))
        assertTrue(home.contains("microphoneGranted = hasRecordAudioPermission()"))
        assertTrue(home.contains("capabilityDecision = startupCapabilityDecision"))

        val start = method("startVoiceCommandRecognition")
        assertTrue(start.contains("if (!hasRecordAudioPermission())"))
        assertTrue(start.contains("!OneShotVoiceInputPolicy.isAvailable("))
        assertTrue(start.contains("homeVoiceCommandAvailable()"))

        val controls = method("updateVoiceCommandButton")
        assertTrue(
            Regex(
                "WalkSessionState\\.READY,\\s*WalkSessionState\\.SAFE_STOP,\\s*" +
                    "WalkSessionState\\.ENDED\\s*->\\s*homeVoiceCommandAvailable\\(\\)",
            ).containsMatchIn(controls),
        )
        assertTrue(controls.contains("listOf(voiceReportButton, walkSafetyVoiceButton)"))
        assertTrue(controls.contains("button.isEnabled = enabled"))
    }

    @Test fun spokenConfirmationsWaitForAppOutputCompletion() {
        listOf("requestVoiceWalkEndConfirmation", "requestGatewayWalkTakeoverConfirmation",
            "requestWalkSessionResumeConfirmation").forEach {
            assertTrue(method(it).contains("onCompleted = delivered"))
            assertFalse(method(it).contains("announceForTalkBack("))
        }
    }

    @Test fun retryableRecognitionErrorsKeepTheCurrentDestinationDialogue() {
        val error = method("buildVoiceCommandRecognitionListener")
            .substringAfter("override fun onError(error: Int) {")
            .substringBefore("override fun onResults(results: Bundle?) {")
        val retryable = error.substringAfter("error == SpeechRecognizer.ERROR_NO_MATCH")
            .substringBefore("speechRecognizer?.destroy()")
        assertTrue(retryable.contains("error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT"))
        assertTrue(retryable.contains("VoiceRecognitionPurpose.COMMAND -> {"))
        assertTrue(retryable.contains("if (!retryHomeDestinationVoiceDialog("))
        assertTrue(retryable.contains("scheduleHandsFreeVoiceRestart()"))
        assertFalse(retryable.contains("clearHomeDestinationVoiceDialog("))
        assertFalse(retryable.contains("clearNativeDestinationSearchState("))
        assertFalse(retryable.contains("showNativeUiPage("))
    }

    @Test fun rejectedDestinationSelectionsKeepThePageAndUseShortRetryWithoutStartingARoute() {
        val retry = method("retryHomeDestinationVoiceDialog")
        val nullContext = retry.substringAfter("if (state == null) {", missingDelimiterValue = "")
            .substringBefore("\n        }", missingDelimiterValue = "")
        assertTrue(nullContext.contains("return false"))
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                retry,
                "val state = currentVoiceDestinationDialogState()",
                "if (state == null)",
                "return false",
                "speakHomeDestinationVoicePrompt(",
                "인식하지 못하였습니다. 다시 듣기 또는 해당 번호를 말해주세요.",
                "return true",
            ),
        )
        assertTrue(retry.contains("speakHomeDestinationVoicePrompt("))
        assertFalse(retry.contains("state.voicePrompt()"))
        assertTrue(retry.contains("인식하지 못하였습니다. 다시 듣기 또는 해당 번호를 말해주세요."))
        assertTrue(retry.contains("return true"))
        assertFalse(retry.contains("state.copy("))
        assertFalse(retry.contains("clearNativeDestinationSearchState("))
        assertFalse(retry.contains("showNativeUiPage("))

        val handler = method("handleVoiceCommandPhrases")
        val confirmation = handler.substringAfter("PlatformVoiceCandidateDisposition.CONFIRMATION_REQUIRED")
            .substringBefore("PlatformVoiceCandidateDisposition.AMBIGUOUS")
        val ambiguous = handler.substringAfter("PlatformVoiceCandidateDisposition.AMBIGUOUS")
            .substringBefore("PlatformVoiceCandidateDisposition.PREVIEW_ONLY")
        val unmatched = handler.substringAfter("if (action == null) {")
            .substringBefore("expectedNavigationDecisionToken !=")
        listOf(confirmation, ambiguous, unmatched).forEach { branch ->
            assertTrue(branch.contains("retryHomeDestinationVoiceDialog("))
        }
        assertTrue(confirmation.contains("showExistingDestinationConfirmation || !retryHomeDestinationVoiceDialog("))
        val invalidNumber = method("selectVoiceDestinationCandidate")
            .substringAfter("if (selected == null) {")
            .substringBefore("if (nativeHomeFeatureContextAvailable()) {")
        assertTrue(invalidNumber.contains("if (!retryHomeDestinationVoiceDialog(message))"))
        assertFalse(invalidNumber.contains("onDestinationSelected("))
        assertFalse(invalidNumber.contains("startNativeDestinationGuidance("))
        val replay = method("repeatVoiceDestinationCandidates")
        assertTrue(replay.contains("onCommand(DestinationSearchVoiceCommand.RepeatPage)"))
        assertTrue(replay.contains("speakHomeDestinationVoicePrompt(transition.state)"))
        assertFalse(replay.contains("performDestinationSearch("))
        assertFalse(replay.contains("onDestinationSelected("))
        val more = method("hearMoreVoiceDestinationCandidates")
        assertTrue(more.contains("onCommand(DestinationSearchVoiceCommand.HearMore)"))
        assertFalse(more.contains("DestinationSearchVoiceCommand.RepeatPage"))
    }

    @Test fun candidateFollowUpKeepsDestinationResultsAndRetryControlsVisible() {
        val start = method("startVoiceCommandRecognition")
        val handsFree = method("handleHandsFreeVoiceCommandListeningStarted")
        assertFalse(start.contains("showNativeUiPage(NativeUiPage.VOICE_COMMAND"))
        assertTrue(handsFree.contains("nativeUiPage != NativeUiPage.DESTINATION_SEARCH"))
        assertTrue(source.contains(
            "if (featureVisible && nativeUiPage.isVoiceInteractionPage()) View.VISIBLE else View.GONE",
        ))
        assertTrue(method("speakHomeDestinationVoicePrompt").contains("!nativeUiPage.isVoiceInteractionPage()"))
    }

    @Test fun bareCandidateSelectionRequiresCurrentSearchContext() {
        val handler = method("handleVoiceCommandPhrases")
        val compactHandler = handler.replace(Regex("\\s+"), "")
        val expectedGuard = """
            val allowBareDestinationIndex = destinationDialogState != null ||
                (!nativeHomeFeatureContextAvailable() &&
                    destinationSearchVoiceState != null &&
                    destinationSearchVoiceState?.query == destinationSearchQuery &&
                    !destinationSearchInFlight && currentDestinationSearchAllowsWork())
        """.replace(Regex("\\s+"), "")
        assertTrue(compactHandler.contains(expectedGuard))
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                handler,
                "val destinationDialogState = currentVoiceDestinationDialogState()",
                "val allowBareDestinationIndex =",
                "selectAndroidVoiceAction(",
                "assessPlatformVoiceCandidate(",
                "destinationDialogState = destinationDialogState,",
            ),
        )

        val liveContext = method("currentVoiceDestinationDialogState")
        listOf(
            "homeDestinationVoiceDialogLease?.currentState(",
            "actorId = reporterUserId,",
            "sessionGeneration = GatewaySessionProcessCoordinator.snapshot().generation,",
            "searchGeneration = destinationSearchGeneration,",
            "query = destinationSearchQuery,",
            "state = destinationSearchVoiceState,",
            "homeContextAvailable = nativeHomeFeatureContextAvailable(),",
            "voicePage = nativeUiPage.isVoiceInteractionPage(),",
        ).forEach { binding ->
            assertTrue("Missing live destination dialogue binding: $binding", liveContext.contains(binding))
        }
    }
}

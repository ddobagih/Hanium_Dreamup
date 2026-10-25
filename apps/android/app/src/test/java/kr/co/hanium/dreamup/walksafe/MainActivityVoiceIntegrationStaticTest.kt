package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityVoiceIntegrationStaticTest {
    private val source = sequenceOf(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        File("app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
    ).first { it.isFile }.readText()

    private fun method(name: String): String =
        MainActivityRefactorSource.function(source, "private fun $name(")

    @Test fun homePrefersInstalledPlatformWhileResumeKeepsBundledFallback() {
        val start = method("startVoiceCommandRecognition")
        val sdk = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/AndroidVoiceInputPlatform.kt").readText()
        assertTrue(start.contains("platform.createRecognizer { handsFreeRuntime.modelDirectory }"))
        assertTrue(MainActivityRefactorSource.member(sdk, "createRecognizer").contains("PreferredOfflineSpeechRecognizer(context, modelDirectory)"))
        assertTrue(start.contains("purpose == VoiceRecognitionPurpose.COMMAND"))
        assertTrue(start.contains("environment.homeFeatureContextAvailable()"))
        assertTrue(start.contains("handsFreeRuntime.modelDirectory == null && !preferPlatform"))
        assertTrue(start.contains("preferPlatform = preferPlatform"))
        assertTrue(start.contains("isRequestCurrent = {"))
        assertFalse(start.contains("createOnDeviceSpeechRecognizer"))
        assertTrue(method("voiceResumeConfirmationAvailable").contains("handsFreeRuntime.modelDirectory != null"))
        assertFalse(method("voiceResumeConfirmationAvailable").contains("isOnDeviceRecognitionAvailable"))
    }

    @Test fun readyHapticRunsOnlyInsideTheCurrentRecognitionLease() {
        val voice = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/VoiceInputSessionController.kt").readText()
        val listener = method("buildVoiceCommandRecognitionListener")
        assertEquals(
            "val source = recognitionSource?.takeIf { it.generation == generation } " +
                "?: RecognitionSource(generation, environment.identity(), environment.feedbackLifetimeGeneration())",
            listener.substringAfter("): RecognitionListener {").substringBefore("fun currentLease():")
                .trim().replace(Regex("\\s+"), " "),
        )
        assertEquals(
            "recognitionSourceIsCurrent(source) && isVoiceRecognitionLeaseCurrent(generation, purpose, " +
                "expectedWalkEpoch, expectedResumeToken, expectedGatewayWalkOperationId)",
            listener.substringAfter("fun currentLease(): Boolean =").substringBefore("return object : RecognitionListener")
                .trim().replace(Regex("\\s+"), " "),
        )
        val sourceCurrent = MainActivityRefactorSource.member(voice, "recognitionSourceIsCurrent")
        assertEquals(
            "voiceRecognitionGeneration == source.generation && environment.identity() == source.identity && " +
                "environment.feedbackLifetimeGeneration() == source.lifecycleGeneration && " +
                "environment.foreground() && environment.microphoneGranted()",
            sourceCurrent.substringAfter("): Boolean =").trim().replace(Regex("\\s+"), " "),
        )
        val ready = listener.substringAfter("override fun onReadyForSpeech(params: Bundle?) {")
            .substringBefore("override fun onBeginningOfSpeech()")
        assertEquals(
            "if ( currentLease() ) { diagnostics.input(VoiceInputDiagnosticEvent.ONE_SHOT_UI_READY) " +
                "output.playListeningStarted() presentation.recognitionSignal(\"말씀하세요\") } }",
            ready.trim().replace(Regex("\\s+"), " "),
        )
        val leaseCheck = ready.indexOf("currentLease()")
        val guardStart = ready.indexOf(") {", leaseCheck)
        val guardEnd = ready.indexOf("\n                }", guardStart)
        val cue = ready.indexOf("output.playListeningStarted()")
        val prompt = ready.indexOf("presentation.recognitionSignal(\"말씀하세요\")")
        assertTrue(leaseCheck >= 0)
        assertTrue(guardStart > leaseCheck)
        assertTrue(guardEnd > guardStart)
        assertTrue(cue in guardStart until guardEnd)
        assertTrue(prompt in (cue + 1) until guardEnd)
        assertFalse(ready.contains("speakInteraction("))
        assertFalse(ready.contains("playProgressBeep("))
        val peers = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/VoiceControllerPeerAdapters.kt").readText()
        assertTrue(MainActivityRefactorSource.member(peers, "playListeningStarted").contains("speech().ensureFeedbackActuator().playVoiceListeningStartVibration()"))
        assertTrue(MainActivityRefactorSource.function(source, "private fun createVoiceInputController(").contains("VoiceInputSpeechPeerAdapter"))
    }

    @Test fun homeInputDoesNotDependOnOutputEngine() {
        val home = method("homeVoiceCommandAvailable")
        assertFalse(home.contains("VOICE_GUIDANCE"))
        assertFalse(home.contains("VERSION_CODES.S"))
        assertFalse(home.contains("OFFLINE_KOREAN_TTS"))
        assertFalse(home.contains("allowsRequirement("))
        assertTrue(home.contains("OneShotVoiceInputPolicy.isAvailable("))
        assertTrue(home.contains("contextAvailable = environment.homeFeatureContextAvailable()"))
        assertTrue(home.contains("microphoneGranted = environment.microphoneGranted()"))
        assertTrue(home.contains("capabilityDecision = environment.capabilityDecision()"))

        val start = method("startVoiceCommandRecognition")
        assertTrue(start.contains("if (!environment.microphoneGranted())"))
        assertTrue(start.contains("!OneShotVoiceInputPolicy.isAvailable("))
        assertTrue(start.contains("homeVoiceCommandAvailable()"))

        val controls = method("updateVoiceCommandButton")
        assertTrue(
            Regex(
                "WalkSessionState\\.READY,\\s*WalkSessionState\\.SAFE_STOP,\\s*" +
                    "WalkSessionState\\.ENDED\\s*->\\s*homeVoiceCommandAvailable\\(\\)",
            ).containsMatchIn(controls),
        )
        assertTrue(controls.contains("listOf(nativeScreenPresenter.voiceBinding().voiceReportButton, walkSafetyVoiceButton)"))
        assertTrue(controls.contains("button.isEnabled = enabled"))
    }

    @Test fun spokenConfirmationsWaitForAppOutputCompletion() {
        val end = method("requestVoiceWalkEndConfirmation")
        assertTrue(end.contains("onCompleted = delivered"))
        assertFalse(end.contains("announceForTalkBack("))
        val takeover = method("requestGatewayWalkTakeoverConfirmation")
        assertTrue(takeover.contains("output.speakTakeover(prompt, delivered, failed)"))
        assertFalse(takeover.contains("announceForTalkBack("))
        val resume = method("requestWalkSessionResumeConfirmation")
        assertTrue(resume.contains("effects.speakResume(prompt, delivered, failed)"))
        assertFalse(resume.contains("announceForTalkBack("))
        val resumeWire = method("createWalkSessionResumeController")
        val takeoverWire = method("createGatewayWalkSessionController")
        listOf(resumeWire, takeoverWire).forEach { wire ->
            assertTrue(wire.contains("ensureFeedbackActuator().speakInteraction(message, onCompleted, onFailed) == NavigationSpeechDispatchResult.ACCEPTED"))
        }
        val peers = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/VoiceControllerPeerAdapters.kt").readText()
        val question = MainActivityRefactorSource.member(peers, "speakWalkEndQuestion")
        assertTrue(question.contains("speech().observeNativeCommandSpeech(onCompleted, onFailed) { done, failed ->"))
        assertTrue(question.contains("speech().ensureFeedbackActuator().speakInteraction(message, done, failed)"))
        val speechOwner = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/output/SpeechOutputController.kt").readText()
        val observation = MainActivityRefactorSource.member(speechOwner, "observeNativeCommandSpeech")
        assertTrue(observation.contains("commandSpeechTracker.terminal(response, true); onCompleted()"))
        assertTrue(observation.contains("commandSpeechTracker.terminal(response, false); onFailed()"))
    }

    @Test fun retryableRecognitionErrorsKeepTheCurrentDestinationDialogue() {
        val error = method("buildVoiceCommandRecognitionListener")
            .substringAfter("override fun onError(error: Int) {")
            .substringBefore("override fun onResults(results: Bundle?) {")
        val retryable = error.substringAfter("error == SpeechRecognizer.ERROR_NO_MATCH")
            .substringBefore("speechRecognizer?.destroy()")
        assertTrue(retryable.contains("error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT"))
        assertTrue(retryable.contains("VoiceRecognitionPurpose.COMMAND -> {"))
        assertTrue(retryable.contains("commands.commandInputMiss(error)"))
        val peers = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/VoiceControllerPeerAdapters.kt").readText()
        val miss = MainActivityRefactorSource.member(peers, "commandInputMiss")
        assertTrue(miss.contains("if (!interaction().destinations.retryHomeDestinationVoiceDialog("))
        assertFalse(miss.contains("clearHomeDestinationVoiceDialog("))
        assertFalse(miss.contains("clearNativeDestinationSearchState("))
        assertFalse(miss.contains("showNativeUiPage("))
        assertTrue(retryable.contains("scheduleHandsFreeVoiceRestart()"))
        assertFalse(retryable.contains("clearHomeDestinationVoiceDialog("))
        assertFalse(retryable.contains("clearNativeDestinationSearchState("))
        assertFalse(retryable.contains("showNativeUiPage("))
    }

    @Test fun rejectedDestinationSelectionsKeepThePageAndReplayCandidatesWithoutStartingARoute() {
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
                "message,",
                "return true",
            ),
        )
        assertTrue(retry.contains("speakHomeDestinationVoicePrompt("))
        assertFalse(retry.contains("state.voicePrompt()"))
        // Preserve the caller's specific retry guidance instead of substituting fixed text.
        assertFalse(retry.contains("인식하지 못하였습니다. 다시 듣기 또는 해당 번호를 말해주세요."))
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
        assertTrue(confirmation.contains("showExistingDestinationConfirmation || !dialog().retryHomeDestinationVoiceDialog("))
        val invalidNumber = method("selectVoiceDestinationCandidate")
            .substringAfter("if (selected == null) {")
            .substringBefore("if (ui.homeContextAvailable()) {")
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

    @Test fun routeInstructionChecksLatestFacingAtTheActualSpeechStart() {
        val dispatch = method("tryDispatchRouteInstruction")
        val validator = dispatch.substringAfter("isActionCurrent = {")
            .substringBefore("isInformationCurrent = {")
        assertTrue(validator.contains("val queryAtMs = elapsedRealtimeMs()"))
        assertTrue(validator.contains("val facing = if (compassOwned) compassInput.facing(queryAtMs) else null"))
        assertTrue(validator.contains("val nowMs = maxOf(queryAtMs, elapsedRealtimeMs())"))
        assertTrue(validator.contains(
            "routeNavigator.isInstructionCurrent(update, nowMs, facing)",
        ))
        val speech = method("dispatchNavigationSpeech")
        assertTrue(speech.contains(
            "isStillValidAtStart = { outputCurrent() && isInstructionStillCurrent() }",
        ))
        val fallback = speech.substringAfter("fallBackToTalkBack = { text, _ ->")
        assertTrue(fallback.contains("!isInstructionStillCurrent()"))
        assertTrue(fallback.contains(
            "isStillValid = { outputCurrent() && isInstructionStillCurrent() }",
        ))
        val completion = speech.substringAfter("speakWithTts = { text, completed, failed ->")
            .substringAfter("onCompleted = {").substringBefore("onFailed = {")
        assertFalse(completion.contains("isInstructionStillCurrent()"))
        val dispatchCompletion = speech.substringAfter("onCompleted = {")
            .substringBefore("isStillValid = ::outputCurrent")
        assertFalse(dispatchCompletion.contains("isInstructionStillCurrent()"))
        assertTrue(speech.contains("onCancelled = {"))
        assertTrue(dispatch.contains("routeNavigator.releaseInstruction(update, elapsedRealtimeMs())"))
    }

    @Test fun candidatePageAuthorityComesFromTheCompletedFullPromptAndIsRevokedOnClear() {
        val manual = method("ensureVoicePermissionThenListen")
        assertTrue(manual.contains("commands.handleDestinationPromptIfPresent()"))
        val peers = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/VoiceControllerPeerAdapters.kt").readText()
        assertTrue(MainActivityRefactorSource.member(peers, "handleDestinationPromptIfPresent").contains("interaction().destinations.speakHomeDestinationVoicePrompt(dialog, listenAfterPrompt = true)"))
        val prompt = method("speakHomeDestinationVoicePrompt")
        assertTrue(prompt.contains("val candidatePrompt = state.voicePrompt()"))
        assertTrue(prompt.contains("val pageParts = state.voicePromptParts(originAgeNotice)"))
        assertTrue(prompt.contains("val parts = (listOf(prefix) + pageParts + suffix).filter(String::isNotBlank)"))
        assertTrue(prompt.contains("output.speakCommandParts("))
        assertTrue(prompt.contains("parts = parts,"))
        val partsAdapter = MainActivityRefactorSource.member(peers, "speakCommandParts")
        assertTrue(partsAdapter.contains("speech().speakCommandResponse(parts.joinToString(\" \"), preparingInput, onCompleted, onFailed,"))
        assertTrue(partsAdapter.contains("onCancelled = onCancelled, additionalCurrent = isCurrent, speechParts = parts"))
        val output = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/output/SpeechOutputController.kt").readText()
        val response = MainActivityRefactorSource.member(output, "speakCommandResponse")
        assertTrue(response.contains("dispatchCommandSpeechResponse(actuator, nativeParts[index]"))
        assertTrue(response.contains("partTerminal.compareAndSet(false, true)"))
        assertTrue(response.contains("commandSpeechTracker.terminalPart(trackedResponse, index, true)"))
        assertTrue(response.contains("if (partIndex == nativeParts.size) completed()"))
        assertTrue(response.contains("else dispatchPart(partIndex)"))
        assertTrue(prompt.contains("preparingInput = listenAfterPrompt"))
        assertTrue(prompt.contains("val attempt = state.beginVoicePrompt()"))
        val completed = prompt.substringAfter("onCompleted = {").substringBefore("onFailed = {")
        assertTrue(completed.contains("pageCurrent()"))
        assertTrue(completed.contains("if (listenAfterPrompt &&"))
        assertTrue(completed.contains("input.startVoiceCommandRecognition()"))
        assertTrue(completed.contains("state.completeVoicePrompt(attempt)"))
        assertTrue(prompt.substringAfter("onFailed = {").contains("state.failVoicePrompt(attempt)"))
        assertFalse(prompt.substringAfter("onFailed = {").substringBefore("onCancelled = {").contains("homeDestinationVoiceDialogLease = null"))
        assertTrue(method("cancelCommandSpeechResponse").contains("input.cancelCommandDialogs(token)"))
        assertTrue(method("createSpeechOutputController").contains("SpeechInputPeerAdapter({ voiceInputController }, { voiceInteractionControllers })"))
        assertTrue(MainActivityRefactorSource.member(peers, "cancelCommandDialogs").contains("interaction().destinations.cancelCommandDialogs()"))
        val dialog = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/interaction/DestinationVoiceDialogController.kt").readText()
        assertTrue(MainActivityRefactorSource.member(dialog, "cancelCommandDialogs").contains("search().state.voicePage?.invalidateVoicePrompt()"))
        assertTrue(MainActivityRefactorSource.member(dialog, "clearHomeDestinationVoiceDialog", occurrence = 1)
            .contains("search().state.voicePage?.invalidateVoicePrompt()"))
    }

    @Test fun candidateFollowUpKeepsDestinationResultsAndRetryControlsVisible() {
        val start = method("startVoiceCommandRecognition")
        val handsFree = method("handleHandsFreeVoiceCommandListeningStarted")
        assertFalse(start.contains("showNativeUiPage(NativeUiPage.VOICE_COMMAND"))
        assertTrue(handsFree.contains("!environment.page().destinationSearch && !environment.page().guidance"))
        val presenter = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeScreenPresenter.kt").readText()
        val begin = MainActivityRefactorSource.member(presenter, "beginFrame").replace(Regex("\\s+"), "")
        assertTrue(begin.contains("show(destination.nativeDestinationControls,facts.featureVisible&&facts.pageName==\"DESTINATION_SEARCH\")"))
        assertTrue(begin.contains("show(destination.nativeVoiceControls,facts.featureVisible&&facts.pageName==\"VOICE_COMMAND\")"))
        assertTrue(MainActivityRefactorSource.composition(source, "nativePagePresentationController").contains("screen = { nativeScreenPresenter }"))
        val panel = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DestinationGuidancePanel.kt").readText()
        assertTrue(panel.contains("voiceReportButton,"))
        assertTrue(panel.contains("setOnClickListener { actions.onVoiceStart() }"))
        assertTrue(source.contains("onVoiceStart = {\n                    ensureVoicePermissionThenListen()"))
        assertTrue(method("speakHomeDestinationVoicePrompt").contains("ui.voiceInteractionPage()"))
    }

    @Test fun bareCandidateSelectionRequiresCurrentSearchContext() {
        val handler = method("handleVoiceCommandPhrases")
        val compactHandler = handler.replace(Regex("\\s+"), "")
        val expectedGuard = """
            val allowBareDestinationIndex = destinationDialogState != null ||
                (!ui.homeContextAvailable() &&
                    search().state.voicePage != null &&
                    search().state.voicePage?.query == search().state.query &&
                    !search().state.inFlight && route.destinationSearchAllowsWork())
        """.replace(Regex("\\s+"), "")
        assertTrue(compactHandler.contains(expectedGuard))
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                handler,
                "val destinationDialogState = dialog().currentVoiceDestinationDialogState()",
                "val allowBareDestinationIndex =",
                "selectAndroidVoiceAction(",
                "assessPlatformVoiceCandidate(",
                "destinationDialogState = destinationDialogState,",
            ),
        )

        val liveContext = method("currentVoiceDestinationDialogState")
        assertTrue(liveContext.contains("val lease = homeDestinationVoiceDialogLease ?: return null"))
        listOf(
            "lease.currentState(",
            "actorId = identity.actorId(),",
            "sessionGeneration = identity.processGeneration(),",
            "searchGeneration = search().state.generation,",
            "query = search().state.query,",
            "state = search().state.voicePage,",
            "homeContextAvailable = ui.homeContextAvailable(),",
            "voicePage = ui.voiceInteractionPage(),",
        ).forEach { binding ->
            assertTrue("Missing live destination dialogue binding: $binding", liveContext.contains(binding))
        }
    }
}

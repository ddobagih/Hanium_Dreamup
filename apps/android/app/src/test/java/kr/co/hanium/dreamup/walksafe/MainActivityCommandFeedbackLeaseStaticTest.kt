package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityCommandFeedbackLeaseStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun onlyLatestResponseOwnsCompletionFailureAndVisibleStatus() {
        val response = functionBlock("private fun speakCommandResponse(")
        assertTrue(response.contains("val responseGeneration = ++commandSpeechResponseGeneration"))
        assertTrue(response.contains("commandSpeechResponseCallbacks.registerLatest("))
        assertTrue(response.contains("captureCommandSpeechResponseOwner(stateSource, responseGeneration, lifecycleGeneration)"))
        assertTrue(response.contains("responseOwner.isCurrent(this)"))
        val owner = MainActivityRefactorSource.speech.substringAfter("internal class CommandSpeechResponseOwner").substringBefore("fun captureCommandSpeechResponseOwner(")
        assertTrue(owner.contains("responseGeneration != controller.commandSpeechResponseGeneration"))
        assertTrue(owner.contains("current.recognitionGeneration == captured.recognitionGeneration"))
        assertTrue(owner.contains("current.actorId == captured.actorId"))
        assertTrue(owner.contains("current.page == captured.page"))
        assertTrue(owner.contains("current.gatewayGeneration == captured.gatewayGeneration"))
        assertTrue(owner.contains("current.walkEpoch == captured.walkEpoch"))
        assertTrue(response.contains("commandSpeechResponseCallbacks.takeTerminalCallback("))
        assertTrue(response.contains("if (dispatch != NavigationSpeechDispatchResult.ACCEPTED) failed()"))
        assertFalse(response.contains("announceForAccessibility("))
        assertFalse(response.contains("startVoiceCommandRecognition("))
    }

    @Test
    fun recordingBlocksAppSpeechBeforeCallbackRegistrationAndDispatch() {
        val response = functionBlock("private fun speakCommandResponse(")
        val suppression = response.indexOf("shouldSuppressFeedbackDuringVoiceRecognition(")
        val registration = response.indexOf("commandSpeechResponseCallbacks.registerLatest(")
        val dispatch = response.indexOf("val dispatch = dispatchCommandSpeechResponse(")
        assertTrue(suppression >= 0 && suppression < registration && registration < dispatch)
        assertTrue(response.contains("it.recognitionActive || it.gatewayRecording"))
    }

    @Test
    fun onlyAnExplicitInputCueCompletionCanContinueToRecordingOnce() {
        val start = functionBlock("private fun startVoiceCommandRecognition(")
        val preparation = functionBlock("private fun prepareOneShotVoiceCommandPrompt()")
        val success = preparation.substringAfter("onCompleted = {").substringBefore("onFailed = {")
        val failure = preparation.substringAfter("onFailed = {")
        assertTrue(start.contains("voiceCommandPromptReadyGeneration = null"))
        assertTrue(start.contains("if (voiceRecognitionActive || voiceCommandPromptPending) return false"))
        assertTrue(start.indexOf("return prepareOneShotVoiceCommandPrompt()") <
            start.indexOf("recognizer.startListening("))
        assertTrue(preparation.contains("output.speakPreparingInput("))
        val inputFactory = MainActivityRefactorSource.member(source, "createVoiceInputController")
        assertTrue(inputFactory.contains("output = VoiceInputSpeechPeerAdapter({ speechOutputController }"))
        val preparing = MainActivityRefactorSource.member(peerSource, "speakPreparingInput")
        assertTrue(preparing.contains("speech().speakCommandResponse(message, preparingInput = true"))
        assertTrue(preparing.contains("onCompleted = onCompleted, onFailed = onFailed"))
        assertTrue(success.contains("voiceRecognitionGeneration == preparationGeneration"))
        assertTrue(success.contains("voiceCommandPromptReadyGeneration = preparationGeneration"))
        assertEquals(2, success.split("startVoiceCommandRecognition()").size)
        assertFalse(failure.contains("startVoiceCommandRecognition("))
        assertFalse(preparation.contains("postDelayed("))
    }

    @Test
    fun terminalCallbackOrCancellationReleasesPendingButtonWithoutAutoListening() {
        val button = functionBlock("private fun updateVoiceCommandButton(")
        val cancel = functionBlock("private fun cancelVoiceCommandRecognition()")
        assertTrue(button.contains("speechOutputController.hasPendingCommandResponse() || voiceCommandPromptPending"))
        assertTrue(button.contains("!responsePending"))
        val speechCancellation = functionBlock("private fun cancelCommandSpeechResponse()")
        assertTrue(cancel.contains("output.cancelCommandResponse()"))
        assertTrue(MainActivityRefactorSource.member(peerSource, "cancelCommandResponse")
            .contains("speech().cancelCommandSpeechResponseForInput()"))
        val inputCleanup = MainActivityRefactorSource.member(MainActivityRefactorSource.speech, "cancelCommandSpeechResponseForInput")
        assertTrue(inputCleanup.contains("cancelCommandSpeechResponse(cancelDialogs = hasPendingCommandResponse())"))
        assertTrue(MainActivityRefactorSource.speech.contains("fun cancelCommandSpeechResponse(cancelDialogs: Boolean = true)"))
        assertTrue(speechCancellation.contains("if (cancelDialogs) input.cancelCommandDialogs(token)"))
        assertTrue(speechCancellation.contains("commandSpeechResponseGeneration += 1L"))
        assertTrue(speechCancellation.contains("commandSpeechResponseCallbacks.clear()"))
        assertTrue(speechCancellation.contains("input.promptState(token, CommandPromptEffect.CLEAR)"))
        assertTrue(MainActivityRefactorSource.member(source, "createSpeechOutputController")
            .contains("input = SpeechInputPeerAdapter({ voiceInputController }"))
        assertTrue(MainActivityRefactorSource.member(peerSource, "promptState")
            .contains("input().applyCommandPromptEffect(effect)"))
        val clearPrompt = functionBlock("fun applyCommandPromptEffect(")
            .substringAfter("CommandPromptEffect.ORDINARY_RESPONSE, CommandPromptEffect.CLEAR ->")
        assertTrue(clearPrompt.contains("voiceCommandPromptPending = false"))
        assertTrue(clearPrompt.contains("voiceCommandPromptReadyGeneration = null"))
        assertTrue(speechCancellation.indexOf("commandSpeechResponseCallbacks.clear()") <
            speechCancellation.indexOf("feedbackActuator?.cancelCommandInteraction()"))
        assertFalse(cancel.contains("startVoiceCommandRecognition("))
    }

    @Test
    fun pageExitCancelsCommandSpeechAndBackgroundStillClosesTheActualEngine() {
        val page = functionBlock("private fun showNativeUiPage(page: NativeUiPage, preserveVoiceInteraction: Boolean)")
        val pageChange = page.substringBefore("nativeUiPage = page")
        assertTrue(pageChange.contains("if (nativeUiPage != page)"))
        assertTrue(pageChange.contains("if (!preserveVoiceInteraction)"))
        assertTrue(pageChange.contains("pages.retireOutgoingVoiceInteraction(nativeUiPage.isVoiceInteractionPage())"))
        val pagePort = MainActivityRefactorSource.property(source, "nativeWorkflowController")
        val retire = ReportStaticSourceInspector.functionBlock(pagePort, "override fun retireOutgoingVoiceInteraction")
        assertTrue(retire.contains("if (wasVoicePage)"))
        assertTrue(retire.contains("stopHandsFreeVoiceService()"))
        assertTrue(retire.contains("cancelVoiceCommandRecognition()"))
        assertTrue(retire.contains("else this@MainActivity.cancelCommandSpeechResponse()"))
        assertFalse(retire.contains("feedbackActuator?.close()"))
        assertFalse(retire.contains("cancelPriorityUserTrainingFeedback()"))
        assertFalse(pageChange.contains("feedbackActuator?.close()"))
        assertFalse(pageChange.contains("cancelPriorityUserTrainingFeedback()"))
        val pause = source.substringAfter("override fun onPause()")
            .substringBefore("internal fun pauseWalkSafeRuntime()")
        val runtimePause = functionBlock("internal fun pauseWalkSafeRuntime()")
        assertTrue(pause.contains("pauseWalkSafeRuntime()"))
        assertTrue(runtimePause.contains("cancelVoiceCommandRecognition()"))
        assertTrue(runtimePause.contains("speechOutputController.closeActuator()"))
    }

    private val peerSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/VoiceControllerPeerAdapters.kt",
    ).readText()

    private fun functionBlock(signature: String): String =
        MainActivityRefactorSource.function(source, signature)
}

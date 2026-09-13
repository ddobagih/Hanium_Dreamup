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
        assertTrue(response.contains("responseGeneration == commandSpeechResponseGeneration"))
        assertTrue(response.contains("recognitionGeneration == voiceRecognitionGeneration"))
        assertTrue(response.contains("responseActor == reporterUserId"))
        assertTrue(response.contains("nativeUiPage == responsePage"))
        assertTrue(response.contains("GatewaySessionProcessCoordinator.snapshot().generation == gatewayGeneration"))
        assertTrue(response.contains("walkSessionLifecycle.snapshot().epoch == responseEpoch"))
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
        val dispatch = response.indexOf("actuator.speakHomeCommandInteraction(")
        assertTrue(suppression >= 0 && suppression < registration && registration < dispatch)
        assertTrue(response.contains("voiceRecognitionActive || gatewayVoiceRecorder?.isRecording == true"))
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
        assertTrue(preparation.contains("preparingInput = true"))
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
        assertTrue(button.contains("commandSpeechResponseCallbacks.size() > 0 || voiceCommandPromptPending"))
        assertTrue(button.contains("!responsePending"))
        val speechCancellation = functionBlock("private fun cancelCommandSpeechResponse()")
        assertTrue(cancel.contains("cancelCommandSpeechResponse()"))
        assertTrue(speechCancellation.contains("commandSpeechResponseGeneration += 1L"))
        assertTrue(speechCancellation.contains("commandSpeechResponseCallbacks.clear()"))
        assertTrue(speechCancellation.contains("voiceCommandPromptPending = false"))
        assertTrue(speechCancellation.contains("voiceCommandPromptReadyGeneration = null"))
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
        assertTrue(pageChange.contains("stopHandsFreeVoiceService()"))
        assertTrue(pageChange.contains("cancelVoiceCommandRecognition()"))
        assertTrue(pageChange.contains("cancelCommandSpeechResponse()"))
        assertFalse(pageChange.contains("feedbackActuator?.close()"))
        assertFalse(pageChange.contains("cancelPriorityUserTrainingFeedback()"))
        val pause = source.substringAfter("override fun onPause()")
            .substringBefore("internal fun pauseWalkSafeRuntime()")
        val runtimePause = functionBlock("internal fun pauseWalkSafeRuntime()")
        assertTrue(pause.contains("pauseWalkSafeRuntime()"))
        assertTrue(runtimePause.contains("cancelVoiceCommandRecognition()"))
        assertTrue(runtimePause.contains("feedbackActuator?.close()"))
    }

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
        val end = source.indexOf("\n    private fun ", start + signature.length)
        return if (end < 0) source.substring(start) else source.substring(start, end)
    }
}

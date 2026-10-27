package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class GuidanceCheckRecoveryStaticTest {
    private fun read(path: String) = File("src/main/java/kr/co/hanium/dreamup/walksafe/$path").readText()
    @Test fun failureHasTwoLargeControlsAndOneSpeechOwner() {
        val ui = MainActivityRefactorSource.member(read("ui/NativeScreenPresenter.kt"), "renderFailedGuidanceCheck")
        for (control in listOf("nativeGuidanceVoiceButton", "nativeGuidancePauseButton", "guidanceTouchGuardButton")) assertTrue(ui.contains("show($control, false)"))
        assertTrue(ui.contains("nativeGuidanceRetryButton.text = \"재점검\""))
        assertTrue(ui.contains("nativeGuidanceCancelButton.text = \"취소\""))
        assertTrue(ui.contains("applyWsButtonStyle(nativeGuidanceRetryButton, 144f"))
        assertTrue(ui.contains("applyWsButtonStyle(nativeGuidanceCancelButton, 144f"))
        val render = MainActivityRefactorSource.member(read("MainActivity.kt"), "applyNativeVoiceFirstPresentation")
        assertTrue(render.contains("nativePrewalkController::announceFailure"))
        assertFalse(render.contains("speakInteraction("))
        val prewalk = MainActivityRefactorSource.member(read("navigation/start/NativePrewalkController.kt"), "announceFailure")
        assertTrue(prewalk.contains("failureSpeech.begin(request.checkRequest.requestId) ?: return"))
        assertTrue(prewalk.contains("speech.submit(reason, ::current"))
    }
    @Test fun failureDoesNotBypassReadinessAndButtonsRetainTheirActions() {
        val prewalk = MainActivityRefactorSource.member(read("navigation/start/NativePrewalkController.kt"), "continueIfReady")
        assertTrue(prewalk.contains("checks.environmentReadiness(epoch) != WalkSessionReadinessStatus.READY"))
        assertTrue(prewalk.contains("checks.mountingReadiness(epoch) != WalkSessionReadinessStatus.READY"))
        assertTrue(prewalk.contains("checks.preflightFailedOrTimedOut() || checks.startupRetryRequired()"))
        val main = read("MainActivity.kt")
        assertTrue(main.contains("onStart = ::startNativeDestinationGuidance"))
        assertTrue(main.contains("onGuidanceCancel = ::showWalkExitConfirmationDialog"))
        assertTrue(main.contains("nativeUiPage == NativeUiPage.GUIDANCE && nativeGuidanceCheckFailed()"))
    }
}

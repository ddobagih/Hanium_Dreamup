package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Executable source wiring only. Actual Android button text/visibility is a separate boundary. */
class MainGuidanceResumeWiringStaticTest {
    private fun source(name: String) = File("src/main/java/kr/co/hanium/dreamup/walksafe/$name").readText()
    private fun code(value: String) = ReportStaticSourceInspector.maskNonCode(value).filterNot(Char::isWhitespace)
    @Test fun guidanceButtonRetainsItsPanelActionAndMainRoutesResumeBeforeStartingPreparation() {
        val panel = MainActivityRefactorSource.property(source("ui/DestinationGuidancePanel.kt"), "nativeGuidanceRetryButton")
        assertTrue(code(panel).contains("onClick=actions.onStart"))
        val main = source("MainActivity.kt")
        val start = code(MainActivityRefactorSource.member(main, "startNativeDestinationGuidance"))
        assertTrue(start.contains("{if(handleNativeGuidanceResumeAction())returnrefreshGuidanceDiagnosticAuthority()"))
        val action = code(MainActivityRefactorSource.member(main, "handleNativeGuidanceResumeAction"))
        assertTrue(action.contains("confirm:()->Unit=::confirmNativeGuidanceResumeFromTouch"))
        assertTrue(action.contains("DestinationGuidanceResumeAction.CONFIRM,DestinationGuidanceResumeAction.NOTICE->confirm()"))
        assertTrue(action.contains("DestinationGuidanceResumeAction.NONE->returnfalse"))
        val current = code(MainActivityRefactorSource.member(main, "currentNativeGuidanceResumeAction"))
        assertTrue(current.contains("if(!nativeGuidanceCalibrationSession.isReady(request))returnDestinationGuidanceResumeAction.PREPARING"))
        assertTrue(current.contains("if(isStartupCapabilityConfirmed())DestinationGuidanceResumeAction.CONFIRMelseDestinationGuidanceResumeAction.NOTICE"))
        val touch = code(MainActivityRefactorSource.member(main, "confirmNativeGuidanceResumeFromTouch"))
        assertTrue(touch.contains("if(walkSessionResumeController.awaitingResponseToDeliveredPrompt){cancelVoiceCommandRecognition()}"))
        assertTrue(touch.contains("startupConfirmationInteraction.handleStartupCapabilityConfirmAction(preferTouchResume=true)"))
        assertTrue(current.contains("walkSessionResumeController.promptPending&&!walkSessionResumeController.awaitingResponseToDeliveredPrompt"))
        val prewalk = MainActivityRefactorSource.property(main, "nativePrewalkController")
        val completion = code(ReportStaticSourceInspector.functionBlock(prewalk, "override fun completeStartupConfirmation"))
        assertTrue(completion.contains("if(walkSessionLifecycle.snapshot().state==WalkSessionState.PAUSED){this@MainActivity.startupConfirmationInteraction.confirmStartupCapabilityDecision()}else{this@MainActivity.startupConfirmationInteraction.completeStartupCapabilityConfirmation(decision)}"))
        assertFalse(action.contains("ResumeConfirmationReceived"))
        assertFalse(action.contains("activateRuntime"))
    }

    @Test fun presenterConsumesTheActualResumeModeAndDisablesPendingSpeech() {
        assertPresenter(source("ui/NativeScreenPresenter.kt"))
        val original = source("ui/NativeScreenPresenter.kt")
        val guard = "resume !in setOf(DestinationGuidanceResumeAction.PREPARING, DestinationGuidanceResumeAction.PENDING)"
        for (replacement in listOf("true", "true /* $guard */")) {
            assertThrows(AssertionError::class.java) { assertPresenter(original.replace(guard, replacement)) }
        }
        val frame = code(MainActivityRefactorSource.member(source("ui/NativePagePresentationController.kt"), "renderFrame"))
        assertTrue(frame.contains("screen().renderGuidanceActions(presentation,queries.activityForeground())"))
    }

    private fun assertPresenter(value: String) {
        val actual = code(MainActivityRefactorSource.member(value, "renderGuidanceActions"))
        assertTrue(actual.contains("valresume=presentation.resumeAction"))
        assertTrue(actual.contains("valavailable=presentation.retryAvailable||resume!=DestinationGuidanceResumeAction.NONE"))
        assertTrue(actual.contains("show(nativeGuidanceRetryButton,available&&!active&&resume==DestinationGuidanceResumeAction.NONE)"))
        assertTrue(actual.contains("nativeGuidanceRetryButton.isEnabled=available&&activityForeground&&resume!insetOf(DestinationGuidanceResumeAction.PREPARING,DestinationGuidanceResumeAction.PENDING)"))
        assertTrue(actual.contains("valretryLabel=when(resume)"))
        assertTrue(actual.contains("if(nativeGuidanceRetryButton.text?.toString()!=retryLabel)nativeGuidanceRetryButton.text=retryLabel"))
    }
}

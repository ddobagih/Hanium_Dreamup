package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Wiring regressions: opening/returning cannot end or resume a walking session. */
class GuidanceExitIntegrationTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private fun body(name: String) = source.substringAfter("private fun $name(").substringBefore("\n    private fun ").substringBefore("\n    override fun ")

    @Test fun backInGuidanceOpensConfirmationInsteadOfEndingActiveWalk() {
        val general = body("handleWalkScreenBackPressed")
        assertTrue(general.contains("isInWalkInteractionArea()"))
        assertFalse(general.contains("EndRequested"))
        assertTrue(general.contains("showWalkExitConfirmationDialog()"))
        assertTrue(general.contains("finishWalkExitConfirmation(false)"))

    }
    @Test fun openingIsNonMutatingAndControlsAreAccessible() {
        val open = body("showWalkExitConfirmationDialog")
        assertFalse(open.contains("transitionWalkSession("))
        assertFalse(open.contains("cancelWalkSessionOutputs("))
        assertTrue(open.contains("listOf(\"안내로 돌아가기\", \"안내 종료\")"))
        assertTrue(open.contains("applyWsButtonStyle(button, 144f"))
        assertTrue(open.contains("IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS"))
        assertTrue(open.contains("snapshot().epoch == snapshot.epoch"))
        assertFalse(open.contains("finish()"))
    }
    @Test fun returnPreservesStateAndStaleConfirmationCannotEnd() {
        val finish = body("finishWalkExitConfirmation")
        assertTrue(finish.contains("if (end && current)"))
        assertTrue(finish.contains("cancelNativeGuidanceAndReturnHome()"))
        val returning = finish.substringAfter("} else {")
        assertFalse(returning.contains("transitionWalkSession("))
        assertFalse(returning.contains("RESUME"))
        assertFalse(returning.contains("CONFIRM_END"))
        assertTrue(returning.contains("showNativeUiPage(NativeUiPage.GUIDANCE)"))
    }
    @Test fun exitChoicesAreHandledBeforeGeneralNavigationCommands() {
        val handler = body("handleOverlayVoiceInput")
        assertTrue(source.contains("interceptRecognized = ::handleOverlayVoiceInput"))
        assertTrue(handler.contains("GuidanceExitVoicePolicy.parse("))
        val pause = source.substringAfter("override fun onPause() {").substringBefore("override fun ")
        assertTrue(pause.contains("dismissWalkExitConfirmation()"))
    }
}

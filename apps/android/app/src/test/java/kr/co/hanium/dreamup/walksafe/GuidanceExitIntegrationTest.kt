package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Wiring regressions: opening/returning cannot end or resume a walking session. */
class GuidanceExitIntegrationTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private fun body(name: String) = source.substringAfter("private fun $name(").substringBefore("\n    private fun ").substringBefore("\n    override fun ")

    @Test fun backInGuidanceOpensConfirmationInsteadOfEndingActiveWalk() {
        val back = body("handleNativeFeatureBackPressed").substringAfter("NativeUiPage.GUIDANCE ->")
            .substringBefore("NativeUiPage.VOICE_COMMAND ->")
        for (state in listOf("ACTIVE", "PAUSED", "SAFE_STOP")) assertTrue(back.contains("WalkSessionState.$state"))
        assertTrue(back.contains("showWalkExitConfirmationDialog()"))
        val general = body("handleWalkScreenBackPressed")
        assertFalse(general.contains("EndRequested"))
        assertTrue(general.contains("finishWalkExitConfirmation(end = false)"))
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
        assertTrue(finish.contains("WalkSessionState.ENDED"))
        val returning = finish.substringAfter("} else {")
        assertFalse(returning.contains("transitionWalkSession("))
        assertFalse(returning.contains("RESUME"))
        assertFalse(returning.contains("CONFIRM_END"))
        assertTrue(returning.contains("showNativeUiPage(NativeUiPage.GUIDANCE)"))
    }
    @Test fun exitChoicesAreHandledBeforeGeneralNavigationCommands() {
        val handler = body("handleVoiceCommandPhrases")
        assertTrue(handler.indexOf("walkExitConfirmationScreen != null") < handler.indexOf("walkSessionVoiceControlPolicy.evaluate"))
        assertTrue(handler.contains("GuidanceExitVoicePolicy.parse("))
        val pause = source.substringAfter("override fun onPause() {").substringBefore("override fun ")
        assertTrue(pause.contains("dismissWalkExitConfirmation()"))
    }
}

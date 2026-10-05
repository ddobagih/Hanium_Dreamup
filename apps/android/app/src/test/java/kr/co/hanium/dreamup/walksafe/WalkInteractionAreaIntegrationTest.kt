package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class WalkInteractionAreaIntegrationTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private fun body(name: String) = source.substringAfter("private fun $name(")
        .substringBefore("\n    private fun ").substringBefore("\n    override fun ")

    @Test fun ownershipIsDerivedFromSessionFactsNotTheVisiblePage() {
        val area = body("isInWalkInteractionArea")
        assertTrue(area.contains("WalkInteractionAreaPolicy.resolve("))
        assertTrue(area.contains("destinationSelected = pendingUiDestination != null"))
        assertTrue(area.contains("preparationPending = nativePrewalkStartEpoch != null"))
        assertFalse(area.contains("nativeUiPage"))
        assertTrue(body("nativeHomeFeatureContextAvailable").contains("isInWalkInteractionArea()"))
    }
    @Test fun homeAndWalkWakeAreSeparateAndOldInputCannotCrossAreas() {
        assertTrue(body("foregroundHomeWakeContextAvailable").contains("homeVoiceCommandAvailable()"))
        val walk = body("foregroundWalkWakeContextAvailable")
        assertTrue(walk.contains("walkAreaVoiceCommandAvailable()"))
        assertTrue(walk.contains("walkExitConfirmationScreen == null"))
        val capture = body("refreshForegroundHomeWakeListening")
        assertTrue(capture.contains("isInWalkInteractionArea() == inputAreaIsWalk"))
        assertTrue(capture.contains("snapshot().epoch == inputEpoch"))
        assertTrue(capture.contains("nativeUiPage == inputPage"))
        assertFalse(body("handleHandsFreeVoiceCommandListeningStarted").contains("showNativeUiPage("))
    }
    @Test fun settingsAndVoiceCancelReturnWithoutCancellingPreparation() {
        val returning = body("returnToCurrentInteractionArea").substringBefore("} else {")
        assertTrue(returning.contains("NativeUiPage.GUIDANCE"))
        assertFalse(returning.contains("cancelNativePendingFeatureEntry"))
        val cancel = body("cancelNativeVoiceCommandAndReturnHome")
        assertTrue(cancel.contains("returnToCurrentInteractionArea()"))
        assertFalse(cancel.contains("cancelNativePendingFeatureEntry()"))
        assertTrue(source.contains("nativeSettingsHomeButton.text = if (isInWalkInteractionArea())"))
    }
    @Test fun readinessCallbackCannotSkipConfirmationWhileSettingsAreOpen() {
        val ready = body("continueNativePrewalkIfReady")
        assertTrue(ready.contains("nativeUiPage != NativeUiPage.GUIDANCE || voiceHelpScreen != null"))
        val posted = ready.substringAfter("startupCapabilityText.post {")
        assertTrue(posted.contains("nativeUiPage != NativeUiPage.GUIDANCE"))
    }
    @Test fun endCleansResourcesBeforeReleasingHomeOwnership() {
        val end = body("executeWalkSessionVoiceAction").substringAfter("WalkSessionVoiceAction.CONFIRM_END ->")
            .substringBefore("WalkSessionVoiceAction.CANCEL_END ->")
        val release = end.indexOf("walkAreaExitInProgress = false")
        for (step in listOf("walkAreaExitInProgress = true", "WalkSessionEvent.EndRequested",
            "cancelWalkSessionOutputs(", "stopDepthSession(", "clearNativeDestinationSearchState()")) {
            assertTrue(step, end.indexOf(step) in 0 until release)
        }
        assertTrue(end.indexOf("showNativeUiPage(NativeUiPage.HOME)") > release)
    }
}

package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source wiring; state transitions and stale callbacks are also covered by controller behavior tests. */
class WalkInteractionAreaIntegrationTest {
    private val source = read("MainActivity.kt")
    private fun read(path: String) = File("src/main/java/kr/co/hanium/dreamup/walksafe/$path").readText()
    private fun body(name: String) = MainActivityRefactorSource.member(source, name)
    @Test fun ownershipIsDerivedFromSessionFactsNotTheVisiblePage() {
        val area = body("isInWalkInteractionArea")
        for (fact in listOf("WalkInteractionAreaPolicy.resolve(", "walkSessionLifecycle.snapshot().state", "pendingUiDestination != null", "nativePrewalkStartEpoch != null", "walkAreaExitInProgress")) assertTrue(fact, area.contains(fact))
        assertFalse(area.contains("nativeUiPage"))
        assertTrue(body("nativeHomeFeatureContextAvailable").contains("!isInWalkInteractionArea()"))
    }
    @Test fun homeAndWalkWakeAreSeparateAndOldInputCannotCrossAreas() {
        val binding = body("createVoiceInputController")
        assertTrue(binding.contains("override fun walkWakeContextAvailable() = walkAreaVoiceCommandAvailable()"))
        assertTrue(binding.contains("walkExitConfirmationScreen == null"))
        val owner = read("voice/input/ForegroundWakeInputController.kt")
        val capture = MainActivityRefactorSource.member(owner, "refreshForegroundHomeWakeListening")
        for (guard in listOf("environment.page().owner == page", "environment.walkAreaCommandAvailable() == walkArea", "requireNotNull(environment.walkOrNull()).epoch == epoch")) assertTrue(guard, capture.contains(guard))
        val handoff = MainActivityRefactorSource.member(owner, "acknowledgeForegroundHomeWake")
        assertTrue(handoff.contains("if (!walkArea) presentation.showCommandPage()"))
        assertTrue(handoff.contains("environment.page().owner == inputPage"))
    }
    @Test fun settingsAndVoiceCancelReturnWithoutCancellingPreparation() {
        val returning = body("returnToCurrentInteractionArea").substringBefore("} else {")
        assertTrue(returning.contains("NativeUiPage.GUIDANCE"))
        assertFalse(returning.contains("cancelNativePendingFeatureEntry"))
        val cancel = body("cancelNativeVoiceCommandAndReturnHome").substringBefore("cancelNativePendingFeatureEntry()")
        assertTrue(cancel.contains("isInWalkInteractionArea()"))
        assertTrue(cancel.contains("showNativeUiPage(NativeUiPage.GUIDANCE)"))
        assertTrue(cancel.contains("return"))
        assertTrue(source.contains("settingsPrivacyPresenter.homeActionTarget().text = if (isInWalkInteractionArea())"))
    }
    @Test fun readinessCallbackCannotSkipConfirmationWhileSettingsAreOpen() {
        val ready = MainActivityRefactorSource.property(source, "nativePrewalkController")
        assertTrue(ready.contains("nativeUiPage != NativeUiPage.GUIDANCE || voiceHelpScreen != null || walkExitConfirmationScreen != null"))
        assertTrue(ready.contains("requestNativeGuidanceStartConfirmation {"))
        assertTrue(ready.contains("if (current()) confirmed()"))
        val prewalk = read("navigation/start/NativePrewalkController.kt")
        assertTrue(prewalk.contains("actions.requestStartConfirmation(current)"))
    }
    @Test fun endCleansResourcesBeforeReleasingHomeOwnership() {
        val end = body("endCurrentWalk")
        val release = end.indexOf("walkAreaExitInProgress = false")
        for (step in listOf("walkAreaExitInProgress = true", "WalkSessionEvent.EndRequested", "cancelWalkSessionOutputs(", "stopDepthSession(")) assertTrue(step, end.indexOf(step) in 0 until release)
        assertTrue(end.indexOf("refreshStartupCapabilityUi()") > release)
        val cancel = body("cancelNativeGuidanceAndReturnHome")
        assertTrue(cancel.indexOf("executeWalkSessionVoiceAction(") < cancel.indexOf("cancelNativeDestinationSearchAndReturnHome()"))
    }
}

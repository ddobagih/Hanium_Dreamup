package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityWalkVoiceControlStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun walkCommandsAreReducedBeforeNavigationAndReportCommands() {
        val commands = MainActivityRefactorSource.function(
            source,
            "private fun handleVoiceCommandPhrases",
        )
        assertTrue(commands.contains("val recognized = phrases.firstOrNull().orEmpty()"))
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                commands,
                "walkSessionVoiceControlPolicy.evaluate(",
                "if (walkDecision.action != WalkSessionVoiceAction.NO_OP)",
                "executeWalkSessionVoiceAction(walkDecision.action, snapshot.epoch)",
                "return",
                "val destinationDialogState = dialog().currentVoiceDestinationDialogState()",
                "val allowBareDestinationIndex = destinationDialogState != null ||",
                "selectAndroidVoiceAction(",
                "assessPlatformVoiceCandidate(",
                "destinationDialogState = destinationDialogState,",
                "val action = scoredAction ?: platformCandidate?.previewAction",
            ),
        )
        assertTrue(
            Regex(
                "destinationDialogState != null \\|\\|\\s*" +
                    "\\(!ui.homeContextAvailable\\(\\) &&\\s*" +
                    Regex.escape("search().state.voicePage != null &&"),
            ).containsMatchIn(commands),
        )
        assertTrue(commands.contains("search().state.voicePage?.query == search().state.query"))
        assertTrue(commands.contains("!search().state.inFlight && route.destinationSearchAllowsWork()"))
    }

    @Test
    fun irreversibleEndRequiresASecondRecognitionAndExactConfirmation() {
        val actions = MainActivityRefactorSource.function(source, "private fun executeWalkSessionVoiceAction(")
        val prompt = MainActivityRefactorSource.function(source, "private fun requestVoiceWalkEndConfirmation(")
        assertTrue(actions.contains("requestVoiceWalkEndConfirmation(expectedEpoch)"))
        assertTrue(actions.contains("WalkSessionVoiceAction.CONFIRM_END -> {"))
        assertTrue(actions.contains("walk.confirmWalkEnd()"))
        val assembly = MainActivityRefactorSource.function(source, "private fun createVoiceInteractionControllers(")
        assertTrue(assembly.contains("endCurrentWalk(\"voice_walk_end_confirmed\""))
        val end = MainActivityRefactorSource.function(source, "private fun endCurrentWalk(")
        assertTrue(end.contains("transitionWalkSession(WalkSessionEvent.EndRequested)"))
        assertTrue(end.contains("walkSessionCleanupController.cancelWalkSessionOutputs(reason)"))
        assertTrue(prompt.contains("보행 종료 확인 또는 보행 종료 취소"))
        assertTrue(prompt.contains("onCompleted = delivered"))
    }

    @Test
    fun releaseSafetyOverlayKeepsAnAccessibleVoiceControl() {
        val composer = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRuntimeViewComposer.kt").readText()
        val button = MainActivityRefactorSource.member(composer, "createWalkVoiceAction")
        val scroll = MainActivityRefactorSource.member(composer, "createWalkSafetyScroll")
        assertTrue(source.contains("walkSafetyVoiceButton = runtimeComposer.createWalkVoiceAction("))
        assertTrue(button.contains("Button(context).apply"))
        assertTrue(button.contains("minimumHeight = style.accessibilityTargetSizePx()"))
        assertTrue(button.contains("minimumWidth = style.accessibilityTargetSizePx()"))
        assertTrue(source.contains("walkSafetyScroll = runtimeComposer.createWalkSafetyScroll(walkSafetyOverlay)"))
        assertTrue(scroll.contains("ScrollView(context).apply"))
        assertTrue(
            source.contains(
                "walkSafetyVoiceButton.accessibilityTraversalAfter = walkSafetyVoiceStatusText.id",
            ),
        )
        assertTrue(source.contains("runtimeComposer.createWalkSafetyOverlay(overlayHorizontalPaddingPx, overlayTopPaddingPx, overlayBottomPaddingPx, walkSafetyVoiceStatusText, walkSafetyVoiceButton)"))
        assertTrue(MainActivityRefactorSource.member(composer, "createWalkSafetyOverlay").contains("addView(voiceAction)"))
    }
}

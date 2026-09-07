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
        val commands = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun handleVoiceCommandPhrases",
        )
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                commands,
                "walkSessionVoiceControlPolicy.evaluate(",
                "if (walkDecision.action != WalkSessionVoiceAction.NO_OP)",
                "executeWalkSessionVoiceAction(walkDecision.action, snapshot.epoch)",
                "return",
                "val destinationDialogState = currentVoiceDestinationDialogState()",
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
                    "\\(!nativeHomeFeatureContextAvailable\\(\\) &&\\s*" +
                    "destinationSearchVoiceState != null &&",
            ).containsMatchIn(commands),
        )
        assertTrue(commands.contains("destinationSearchVoiceState?.query == destinationSearchQuery"))
        assertTrue(commands.contains("!destinationSearchInFlight && currentDestinationSearchAllowsWork()"))
    }

    @Test
    fun irreversibleEndRequiresASecondRecognitionAndExactConfirmation() {
        assertTrue(source.contains("requestVoiceWalkEndConfirmation(expectedEpoch)"))
        assertTrue(source.contains("WalkSessionVoiceAction.CONFIRM_END -> {"))
        assertTrue(source.contains("transitionWalkSession(WalkSessionEvent.EndRequested)"))
        assertTrue(source.contains("보행 종료 확인 또는 보행 종료 취소"))
    }

    @Test
    fun releaseSafetyOverlayKeepsAnAccessibleVoiceControl() {
        assertTrue(source.contains("walkSafetyVoiceButton = Button(this).apply"))
        assertTrue(source.contains("minimumHeight = accessibilityTargetSizePx()"))
        assertTrue(source.contains("minimumWidth = accessibilityTargetSizePx()"))
        assertTrue(source.contains("walkSafetyScroll = ScrollView(this).apply"))
        assertTrue(
            source.contains(
                "walkSafetyVoiceButton.accessibilityTraversalAfter = walkSafetyVoiceStatusText.id",
            ),
        )
        assertTrue(source.contains("addView(walkSafetyVoiceButton)"))
    }
}

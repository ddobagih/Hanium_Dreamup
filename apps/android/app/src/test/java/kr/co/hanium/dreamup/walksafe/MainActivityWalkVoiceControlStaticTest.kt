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
        val walkPolicy = source.indexOf("walkSessionVoiceControlPolicy.evaluate(")
        val legacyPolicy = source.indexOf("selectAndroidVoiceAction(phrases, confidenceScores)")
        assertTrue(walkPolicy >= 0)
        assertTrue(legacyPolicy > walkPolicy)
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

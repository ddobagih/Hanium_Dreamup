package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 종합분석 6.6: UNKNOWN·이동 상태 때문에 중지/취소/도움말 음성 명령을 차단하지 않는다. */
class MainActivityVoiceMotionIndependenceStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/interaction/VoiceCommandInteractionController.kt").readText()
    private val motionTokens = listOf(
        "latestPositionStationaryState",
        "latestPositionStationarySinceMs",
        "currentUserMotion(",
        "UserMovementState",
        "userMotion",
        "zeroVelocityUpdateAllowed",
    )

    @Test
    fun stopCancelHelpAndWalkEndVoicePathsDoNotConsultUserMotionState() {
        val paths = listOf(
            "private fun handleVoiceCommandPhrases(",
            "private fun executeWalkSessionVoiceAction(",
            "private fun requestVoiceWalkEndConfirmation(",
            "private fun executeVoiceAction(",
            "private fun cancelDestinationFromVoice(",
            "private fun stopNavigationFromVoice(expectedToken: RouteNavigatorDecisionToken?)",
        )
        for (marker in paths) {
            val block = functionBlock(marker)
            for (token in motionTokens) assertFalse("$marker uses $token", block.contains(token))
        }
        val dispatch = functionBlock("private fun executeVoiceAction(")
        assertTrue(dispatch.contains("AndroidVoiceAction.SpeakVoiceHelp, AndroidVoiceAction.OpenHelp ->"))
        assertTrue(dispatch.contains("AndroidVoiceAction.CancelDestination -> dialog().cancelDestinationFromVoice()"))
        assertTrue(dispatch.contains("AndroidVoiceAction.StopNavigation -> stopNavigationFromVoice(expectedNavigationDecisionToken)"))
    }

    private fun functionBlock(marker: String): String {
        val name = marker.substringAfter("fun ").substringBefore("(")
        val owner = if (name == "cancelDestinationFromVoice") File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/voice/interaction/DestinationVoiceDialogController.kt").readText() else source
        return MainActivityRefactorSource.member(owner, name,
            occurrence = if (name == "stopNavigationFromVoice") 1 else 0)
    }
}

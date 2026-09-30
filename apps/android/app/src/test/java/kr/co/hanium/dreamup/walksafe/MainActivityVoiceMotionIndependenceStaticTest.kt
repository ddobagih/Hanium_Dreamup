package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 종합분석 6.6: UNKNOWN·이동 상태 때문에 중지/취소/도움말 음성 명령을 차단하지 않는다. */
class MainActivityVoiceMotionIndependenceStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
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
        assertTrue(dispatch.contains("AndroidVoiceAction.SpeakVoiceHelp ->"))
        assertTrue(dispatch.contains("AndroidVoiceAction.CancelDestination -> cancelDestinationFromVoice()"))
        assertTrue(dispatch.contains("AndroidVoiceAction.StopNavigation -> stopNavigationFromVoice(expectedNavigationDecisionToken)"))
    }

    private fun functionBlock(marker: String): String {
        val markerIndex = source.indexOf(marker)
        require(markerIndex >= 0) { "missing function marker: $marker" }
        val openingBrace = source.indexOf('{', source.indexOf(')', markerIndex))
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(markerIndex, index + 1)
                }
            }
        }
        error("missing closing brace: $marker")
    }
}

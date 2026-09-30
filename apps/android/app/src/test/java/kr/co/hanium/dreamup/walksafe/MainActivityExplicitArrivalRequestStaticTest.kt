package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 07.txt #36: "도착했어" without an open question opens one new confirmation, never an end. */
class MainActivityExplicitArrivalRequestStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun voiceArrivalWithoutAnOpenQuestionAsksAgainInsteadOfDeadEnding() {
        val confirm = functionBlock("private fun confirmArrivalFromVoice(expectedToken: RouteNavigatorDecisionToken?)")
        val nullBranch = confirm.substringAfter("if (expectedToken == null) {").substringBefore("}")
        assertTrue(nullBranch.contains("requestArrivalConfirmationFromVoice()"))
        assertFalse(nullBranch.contains("아직 도착 안내가 없습니다."))

        val request = functionBlock("private fun requestArrivalConfirmationFromVoice()")
        assertTrue(request.contains("routeNavigator.pendingUserDecision() != null"))
        assertTrue(request.contains("routeNavigator.requestArrivalConfirmation(SystemClock.elapsedRealtime())"))
        assertTrue(request.contains("applyRouteDeviationSafetyUpdate(update)"))
        assertTrue(request.indexOf("RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION") <
            request.indexOf("applyRouteDeviationSafetyUpdate(update)"))
        // Opening a question is not an arrival: only a later token-bound confirmation ends guidance.
        assertFalse(request.contains("resetRouteState("))
        assertFalse(request.contains("confirmArrival("))
    }

    private fun functionBlock(marker: String): String {
        val markerIndex = source.indexOf(marker)
        require(markerIndex >= 0) { "missing function marker: $marker" }
        val openingBrace = source.indexOf('{', markerIndex + marker.length)
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

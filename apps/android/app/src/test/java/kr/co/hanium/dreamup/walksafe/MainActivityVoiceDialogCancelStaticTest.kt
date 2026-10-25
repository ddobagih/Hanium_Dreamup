package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S05-12 (05.txt:157 "후보 검색만 취소했는데 기존 안내까지 종료되는 상황이 없는지") / S06-21: a bare "취소"
 * closes the open candidate dialog or running search only. The route, a route request, the chosen
 * destination and the walk are never touched.
 */
class MainActivityVoiceDialogCancelStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/interaction/VoiceCommandInteractionController.kt").readText()
    private val cancel = source.substringAfter("private fun cancelVoiceDialogFromVoice() {")
        .substringBefore("\n    }\n")

    @Test
    fun onlyTheDialogAndTheSearchAreClosed() {
        assertTrue(cancel.contains("if (searchCancelled) search().cancel()"))
        assertTrue(cancel.contains("search().clearVoiceSelection()"))
        assertTrue(cancel.contains("dialog().clearHomeDestinationVoiceDialog()"))
        assertTrue(cancel.contains("dialog().clearWalkingSelection()"))
        for (forbidden in listOf(
            "resetRouteState(", "cancelActiveRouteRequest(", "stopNavigationFromVoice(", "endNavigationAfterDeviation(",
            "currentDestination =", "pendingUiDestination =", "destinationQueryInput.setText(", "routeNavigator.",
            "transitionWalkSession(", "requestRoute(", "routeRun().navigator.", "ui.clearPendingDestination()",
            "routeRun().destination =", "walk.pauseWalk(", "walk.confirmWalkEnd(",
        )) {
            assertFalse(forbidden, cancel.contains(forbidden))
        }
    }

    @Test
    fun theAnswerSaysWhatWasClosedAndThatGuidanceContinues() {
        assertTrue(cancel.contains("\"목적지 검색을 취소했습니다.\""))
        assertTrue(cancel.contains("\"후보 선택을 닫았습니다.\""))
        assertTrue(cancel.contains("(if (routeRun().active) \" 진행 중인 길안내는 계속됩니다.\" else \"\")"))
        // With nothing open, the explicit phrases are named instead of acting.
        assertTrue(cancel.contains("routeRun().active -> \"취소할 대화가 없습니다. 길안내를 끝내려면 길안내 종료라고 말해 주세요.\""))
        assertTrue(cancel.contains("\"취소할 대화가 없습니다. 목적지를 지우려면 목적지 취소라고 말해 주세요.\""))
    }

    @Test
    fun theCommandIsDispatchedOnHomeAndNotGatedAsARouteDecision() {
        val execute = source.substringAfter("fun executeVoiceAction(")
            .substringBefore("private fun unrecognizedCommandHintKo()")
        assertTrue(execute.contains("AndroidVoiceAction.CancelDialog -> cancelVoiceDialogFromVoice()"))
        val homeGate = execute.substringBefore("ui.navigationStatus(\"voice=walk_action_requires_active_walk\")")
        assertTrue(homeGate.contains("AndroidVoiceAction.CancelDialog,"))
        val handler = source.substringAfter("fun handleVoiceCommandPhrases(")
            .substringBefore("executeVoiceAction(action, expectedNavigationDecisionToken)")
        val decisionGate = handler.substringAfter("if (\n            action in setOf(").substringBefore(") && expectedNavigationDecisionToken")
        assertTrue(decisionGate.contains("AndroidVoiceAction.StopNavigation"))
        assertFalse(decisionGate.contains("AndroidVoiceAction.CancelDialog"))
    }
}

package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R17 (voice-F3, S05-12/S06-21; 05.txt:157 "후보 검색만 취소했는데 기존 안내까지 종료되는 상황이 없는지"):
 * the voice handler asks "길안내를 끝낼까요?" once before a stop phrase without "길" ends guidance while
 * a candidate dialog, a search or a walking selection is open. Every utterance ends an earlier question.
 */
class MainActivityGuidanceStopConfirmationStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val handler = MainActivityRefactorSource.function(source, "private fun handleVoiceCommandPhrases(")
    private fun body(name: String) = MainActivityRefactorSource.function(source, "private fun $name(")


    @Test
    fun everyUtteranceEndsTheQuestionBeforeAnythingElseRuns() {
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                handler,
                "val confirmationWasOpen = guidanceStopConfirmation.consumeOpenConfirmation(scheduler.nowMs())",
                "guidanceStopConfirmationRouteGeneration == route.requestGeneration()",
                "guidanceStopConfirmationWalkEpoch == snapshot.epoch && routeRun().active",
                "walkSessionVoiceControlPolicy.evaluate(",
                "if (voiceEndConfirmationPromptPending) {",
            ),
        )
    }

    @Test
    fun theQuestionIsAskedAfterTheStaleDecisionGateAndBeforeTheStopRuns() {
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                handler,
                "ui.navigationStatus(\"voice=navigation_decision_stale\")",
                "if (action == AndroidVoiceAction.StopNavigation &&",
                "confirmGuidanceStopFirst(recognized, guidanceStopConfirmationOpen)",
                ") return",
                "executeVoiceAction(action, expectedNavigationDecisionToken, confidenceScores?.getOrNull(0)?.toDouble())",
            ),
        )
    }

    @Test
    fun theQuestionReopensInputAfterSpeechAndNeverTouchesTheRoute() {
        val confirm = body("confirmGuidanceStopFirst")
        assertTrue(confirm.contains("guidanceStopPhraseOrNull(recognized) ?: return false"))
        assertTrue(confirm.contains("dialogOpen = voiceDestinationDialogOpen(),"))
        assertTrue(confirm.contains("guidanceRunning = routeRun().active,"))
        assertTrue(confirm.contains("message = kr.co.hanium.dreamup.walksafe.navigation.GUIDANCE_STOP_CONFIRMATION_PROMPT_KO"))
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                confirm,
                "preparingInput = true,",
                "onCompleted = {",
                "guidanceStopConfirmation.promptDelivered(scheduler.nowMs())",
                "input.completePreparedPrompt(preparationGeneration)",
                "if (!input.startVoiceCommandRecognition()) {",
            ),
        )
        assertTrue(confirm.contains("onFailed = {"))
        assertTrue(confirm.contains("route.requestGeneration() == expectedRouteGeneration && routeRun().active"))
        assertTrue(confirm.contains("walk.snapshot().epoch == expectedWalkEpoch"))
        assertTrue(confirm.contains("invalidateGuidanceStopConfirmation()"))
        for (forbidden in listOf("resetRouteState(", "cancelActiveRouteRequest(", "stopNavigationFromVoice(", "routeNavigator.")) {
            assertFalse(forbidden, confirm.contains(forbidden))
        }
        val open = body("voiceDestinationDialogOpen")
        for (part in listOf(
            "search().state.inFlight", "search().state.voicePage != null", "dialog().hasHomeLease()",
            "dialog().hasWalkingSelection()", "search().state.pendingVoiceQuery != null",
            "search().isAcquiring",
        )) {
            assertTrue(part, open.contains(part))
        }
    }

    @Test
    fun theStopItselfIsUnchanged() {
        val commandOwner = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/interaction/VoiceCommandInteractionController.kt").readText()
        val stop = MainActivityRefactorSource.member(commandOwner, "stopNavigationFromVoice", 1)
        assertTrue(stop.contains("cancelActiveRouteRequest(preserveExistingRoute = false)"))
        assertTrue(stop.contains("resetRouteState()"))
        assertFalse(stop.contains("guidanceStopConfirmation"))
    }

    @Test
    fun routeTouchAndWalkTransitionsInvalidatePendingConfirmation() {
        val invalidation = body("invalidateGuidanceStopConfirmation")
        assertTrue(invalidation.contains("guidanceStopConfirmation.invalidate()"))
        assertTrue(body("resetRouteState").contains("effects.invalidateStopConfirmation()"))
        assertTrue(MainActivityRefactorSource.function(source, "private fun showNativeUiPage(page: NativeUiPage, preserveVoiceInteraction: Boolean)").contains("if (nativeUiPage != page && !preserveVoiceInteraction) pages.invalidateGuidanceStopConfirmation()"))
        assertTrue(body("transitionWalkSession").contains("if (transition.changed) effects.invalidateGuidanceStopConfirmation()"))
    }
}

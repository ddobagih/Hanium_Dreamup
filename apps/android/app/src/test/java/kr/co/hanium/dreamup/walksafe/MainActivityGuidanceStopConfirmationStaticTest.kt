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
    private val handler = source.substringAfter("private fun handleVoiceCommandPhrases(")
        .substringBefore("private fun executeWalkSessionVoiceAction(")

    @Test
    fun everyUtteranceEndsTheQuestionBeforeAnythingElseRuns() {
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                handler,
                "val confirmationWasOpen = guidanceStopConfirmation.consumeOpenConfirmation(SystemClock.elapsedRealtime())",
                "guidanceStopConfirmationRouteGeneration == routeRequestGeneration",
                "guidanceStopConfirmationWalkEpoch == snapshot.epoch && isRouteActive",
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
                "updateNavigationStatus(\"voice=navigation_decision_stale\")",
                "if (action == AndroidVoiceAction.StopNavigation &&",
                "confirmGuidanceStopFirst(recognized, guidanceStopConfirmationOpen)",
                ") return",
                "executeVoiceAction(action, expectedNavigationDecisionToken)",
            ),
        )
    }

    @Test
    fun theQuestionReopensInputAfterSpeechAndNeverTouchesTheRoute() {
        val confirm = source.substringAfter("private fun confirmGuidanceStopFirst(")
            .substringBefore("\n    }\n")
        assertTrue(confirm.contains("guidanceStopPhraseOrNull(recognized) ?: return false"))
        assertTrue(confirm.contains("dialogOpen = voiceDestinationDialogOpen(),"))
        assertTrue(confirm.contains("guidanceRunning = isRouteActive,"))
        assertTrue(confirm.contains("message = kr.co.hanium.dreamup.walksafe.navigation.GUIDANCE_STOP_CONFIRMATION_PROMPT_KO"))
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                confirm,
                "preparingInput = true,",
                "onCompleted = {",
                "guidanceStopConfirmation.promptDelivered(SystemClock.elapsedRealtime())",
                "voiceCommandPromptPending = false",
                "voiceCommandPromptReadyGeneration = preparationGeneration",
                "if (!startVoiceCommandRecognition()) {",
            ),
        )
        assertTrue(confirm.contains("onFailed = {"))
        assertTrue(confirm.contains("routeRequestGeneration == expectedRouteGeneration && isRouteActive"))
        assertTrue(confirm.contains("walkSessionLifecycle.snapshot().epoch == expectedWalkEpoch"))
        assertTrue(confirm.contains("invalidateGuidanceStopConfirmation()"))
        for (forbidden in listOf("resetRouteState(", "cancelActiveRouteRequest(", "stopNavigationFromVoice(", "routeNavigator.")) {
            assertFalse(forbidden, confirm.contains(forbidden))
        }
        val open = source.substringAfter("private fun voiceDestinationDialogOpen(): Boolean =")
            .substringBefore("private var homeDestinationVoiceDialogLease")
        for (part in listOf(
            "destinationSearchInFlight", "destinationSearchVoiceState != null", "homeDestinationVoiceDialogLease != null",
            "walkingDestinationChange.hasPending()", "pendingVoiceDestinationQuery != null",
            "destinationSearchLocationController?.isAcquiring == true",
        )) {
            assertTrue(part, open.contains(part))
        }
    }

    @Test
    fun theStopItselfIsUnchanged() {
        val stop = source.substringAfter("private fun stopNavigationFromVoice(expectedToken: RouteNavigatorDecisionToken?) {")
            .substringBefore("\n    }\n")
        assertTrue(stop.contains("cancelActiveRouteRequest(preserveExistingRoute = false)"))
        assertTrue(stop.contains("resetRouteState()"))
        assertFalse(stop.contains("guidanceStopConfirmation"))
    }

    @Test
    fun routeTouchAndWalkTransitionsInvalidatePendingConfirmation() {
        val invalidation = source.substringAfter("private fun invalidateGuidanceStopConfirmation() {")
            .substringBefore("\n    }\n")
        assertTrue(invalidation.contains("guidanceStopConfirmation.invalidate()"))
        assertTrue(source.substringAfter("private fun resetRouteState(").substringBefore("\n    }\n")
            .contains("invalidateGuidanceStopConfirmation()"))
        assertTrue(source.substringAfter("private fun showNativeUiPage(requestedPage: NativeUiPage, preserveVoiceInteraction: Boolean) {")
            .substringBefore("\n    }\n").contains("if (nativeUiPage != page && !preserveVoiceInteraction) invalidateGuidanceStopConfirmation()"))
        assertTrue(source.substringAfter("private fun transitionWalkSession(").substringBefore("\n    }\n")
            .contains("if (transition.changed) invalidateGuidanceStopConfirmation()"))
    }
}

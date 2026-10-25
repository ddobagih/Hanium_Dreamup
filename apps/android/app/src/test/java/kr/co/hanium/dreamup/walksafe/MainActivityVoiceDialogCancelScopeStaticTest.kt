package kr.co.hanium.dreamup.walksafe

import java.io.File
import kr.co.hanium.dreamup.walksafe.navigation.AndroidVoiceCommand
import kr.co.hanium.dreamup.walksafe.navigation.parseAndroidVoiceCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * voice-F2/F5 (S05-12/S06-21; 05.txt:155-157): a bare "취소" answers for what is actually open. A walking
 * switch to another place being routed is named with "목적지 취소" (which keeps the running route) instead
 * of "길안내 종료" (which would end it too); an explicit report waiting for confirmation is dropped; a route
 * question names its own answers; a search still waiting for its location fix is cancelled as a search.
 */
class MainActivityVoiceDialogCancelScopeStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/interaction/VoiceCommandInteractionController.kt").readText()
    private val cancel = source.substringAfter("private fun cancelVoiceDialogFromVoice() {")
        .substringBefore("\n    }\n")

    @Test
    fun aRoutedPlaceSwitchIsNamedWithTheDestinationCancelThatKeepsTheRoute() {
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                cancel,
                "if (!dialogOpen) {",
                "destinationReplacementRequestPending() ->",
                "\"새 목적지 경로를 준비하고 있습니다. 바꾸지 않으려면 목적지 취소라고 말해 주세요. \" +",
                "\"진행 중인 길안내는 계속됩니다.\"",
                "routeRun().active -> \"취소할 대화가 없습니다. 길안내를 끝내려면 길안내 종료라고 말해 주세요.\"",
            ),
        )
        val pending = source.substringAfter("private fun destinationReplacementRequestPending(): Boolean =")
            .substringBefore("\n\n")
        for (part in listOf("routeRun().active", "routeRun().navigator.hasRoute()", "route.requestPending()", "route.requestReason() == \"user_destination\"")) {
            assertTrue(part, pending.contains(part))
        }
        // The named phrase is the destination cancel, and that one keeps the running route.
        assertEquals(AndroidVoiceCommand.CancelDestination, parseAndroidVoiceCommand("목적지 취소"))
        val destinationCancel = MainActivityRefactorSource.member(File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/voice/interaction/DestinationVoiceDialogController.kt").readText(), "cancelDestinationFromVoice")
        assertTrue(destinationCancel.contains("if (replacementPending) route.cancelActiveRouteRequest(preserveExistingRoute = true)"))
    }

    @Test
    fun anExplicitReportWaitingForConfirmationIsDroppedFirst() {
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                cancel,
                "if (walk.cancelExplicitReportIfOpen()) {",
                "speakInteraction(\"신고를 취소했습니다. 저장하지 않았습니다.\")",
                "return",
                "val searchCancelled =",
            ),
        )
    }

    @Test
    fun reportCancellationPortStillInvalidatesTheOwnedReportBeforeReturning() {
        val report = File("src/main/java/kr/co/hanium/dreamup/walksafe/report/runtime/ReportCandidateController.kt").readText()
        val cancelled = MainActivityRefactorSource.member(report, "cancelExplicitReportIfOpen")
        assertTrue(cancelled.contains("if (!explicitReportConfirmationPolicy.invalidate()) return false"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(cancelled, "invalidatePendingExplicitReport()", "return true"))
    }

    @Test
    fun aRouteQuestionNamesItsOwnAnswers() {
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                cancel,
                "destinationReplacementRequestPending() ->",
                "voiceRouteDecisionPending() -> \"취소할 대화가 없습니다. \${unrecognizedCommandHintKo()}\"",
                "routeRun().active ->",
            ),
        )
        val pending = source.substringAfter("private fun voiceRouteDecisionPending(): Boolean =")
            .substringBefore("\n\n")
        assertTrue(pending.contains("routeRun().navigator.pendingUserDecision() in setOf("))
        assertTrue(pending.contains("RouteNavigatorUserDecision.REROUTE"))
        assertTrue(pending.contains("RouteNavigatorUserDecision.LOCATION_RECHECK"))
        assertFalse(pending.contains("RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION"))
    }

    @Test
    fun aSearchWaitingForItsLocationIsCancelledAsASearch() {
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                cancel,
                "val searchCancelled = search().state.inFlight ||",
                "search().isAcquiring ||",
                "(search().state.pendingVoiceQuery != null && search().state.voicePage == null)",
                "val dialogOpen = searchCancelled || voiceDestinationDialogOpen()",
                "if (searchCancelled) search().cancel()",
                "if (searchCancelled) ui.renderSearch()",
            ),
        )
        // cancelDestinationSearch() ends the location wait itself, so its late fix cannot start the search.
        val cancelSearch = MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/DestinationSearchController.kt").readText(), "cancel")
        assertTrue(cancelSearch.contains("cancelLocation()"))
        for (forbidden in listOf("resetRouteState(", "cancelActiveRouteRequest(", "routeNavigator.", "pendingUiDestination =", "routeRun().navigator.", "ui.clearPendingDestination()")) {
            assertFalse(forbidden, cancel.contains(forbidden))
        }
    }
}

package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandUiDestinationPolicyTest {
    @Test
    fun destinationSearchSelectionAndControlsOpenTheDestinationPage() {
        listOf("서울역으로 안내해줘", "1번 선택", "더 듣기", "안내 시작")
            .forEach { phrase ->
                assertEquals(
                    phrase,
                    VoiceCommandUiDestination.DESTINATION_SEARCH,
                    selectAndroidVoiceAction(listOf(phrase), floatArrayOf(0.9f))?.uiDestination(),
                )
            }
    }

    @Test
    fun settingsOpenTheirPageWhileAPlaceContainingSettingsOpensDestinationSearch() {
        assertEquals(
            VoiceCommandUiDestination.SETTINGS,
            selectAndroidVoiceAction(listOf("설정 열어줘"), floatArrayOf(0.9f))?.uiDestination(),
        )
        assertEquals(
            VoiceCommandUiDestination.DESTINATION_SEARCH,
            selectAndroidVoiceAction(listOf("설정역으로 안내해줘"), floatArrayOf(0.9f))?.uiDestination(),
        )
    }

    @Test
    fun theReportFlowUsesTheVoiceCommandPage() {
        assertEquals(
            VoiceCommandUiDestination.VOICE_COMMAND,
            selectAndroidVoiceAction(listOf("신고해줘"), floatArrayOf(0.9f))?.uiDestination(),
        )
    }

    /**
     * S05-33 (originals/05.txt:321 "명령 실행에 화면 이동이 필요한 경우만 이동"): a state change whose result is
     * only spoken has nothing to choose on another screen. "목적지 취소" used to open DESTINATION_SEARCH and
     * the route decisions and "길안내 중지" VOICE_COMMAND; they now keep the current page.
     */
    @Test
    fun spokenOnlyStateChangesKeepTheCurrentPage() {
        listOf(
            "목적지 취소", "취소", "경로 다시 찾아줘",
            "위치 확인해줘", "도착 확인", "아직 도착 아니야", "길안내 중지",
        ).forEach { phrase ->
            assertEquals(
                phrase,
                VoiceCommandUiDestination.CURRENT_VOICE_PAGE,
                selectAndroidVoiceAction(listOf(phrase), floatArrayOf(0.9f))?.uiDestination(),
            )
        }
    }

    /**
     * S05-33 (originals/05.txt:319): repeating an answer must not move the screen or TalkBack focus.
     * "다시 듣기", "다시 말해줘", "도움말", "다음 경로 뭐야" used to open DESTINATION_SEARCH/VOICE_COMMAND.
     */
    @Test
    fun readOnlyAnswersKeepTheCurrentVoicePage() {
        listOf("다시 듣기", "다시 말해줘", "도움말", "다음 경로 뭐야").forEach { phrase ->
            assertEquals(
                phrase,
                VoiceCommandUiDestination.CURRENT_VOICE_PAGE,
                selectAndroidVoiceAction(listOf(phrase), floatArrayOf(0.9f))?.uiDestination(),
            )
        }
    }

    @Test
    fun everyActionHasOneExplicitDestination() {
        val expected = mapOf(
            AndroidVoiceAction.CreateReport to VoiceCommandUiDestination.VOICE_COMMAND,
            AndroidVoiceAction.OpenSettings to VoiceCommandUiDestination.SETTINGS,
            AndroidVoiceAction.SearchDestination("서울역") to VoiceCommandUiDestination.DESTINATION_SEARCH,
            AndroidVoiceAction.SelectDestinationCandidate(1) to VoiceCommandUiDestination.DESTINATION_SEARCH,
            AndroidVoiceAction.RepeatDestinationCandidates to VoiceCommandUiDestination.CURRENT_VOICE_PAGE,
            AndroidVoiceAction.HearMoreDestinationCandidates to VoiceCommandUiDestination.DESTINATION_SEARCH,
            AndroidVoiceAction.PreviousDestinationCandidates to VoiceCommandUiDestination.DESTINATION_SEARCH,
            AndroidVoiceAction.CancelDestination to VoiceCommandUiDestination.CURRENT_VOICE_PAGE,
            AndroidVoiceAction.CancelDialog to VoiceCommandUiDestination.CURRENT_VOICE_PAGE,
            AndroidVoiceAction.StartNavigation to VoiceCommandUiDestination.DESTINATION_SEARCH,
            AndroidVoiceAction.SpeakCurrentGuidance to VoiceCommandUiDestination.CURRENT_VOICE_PAGE,
            AndroidVoiceAction.SpeakVoiceHelp to VoiceCommandUiDestination.CURRENT_VOICE_PAGE,
            AndroidVoiceAction.SpeakNextNavigationInstruction to VoiceCommandUiDestination.CURRENT_VOICE_PAGE,
            AndroidVoiceAction.RequestReroute to VoiceCommandUiDestination.CURRENT_VOICE_PAGE,
            AndroidVoiceAction.RecheckLocation to VoiceCommandUiDestination.CURRENT_VOICE_PAGE,
            AndroidVoiceAction.ConfirmArrival to VoiceCommandUiDestination.CURRENT_VOICE_PAGE,
            AndroidVoiceAction.RejectArrival to VoiceCommandUiDestination.CURRENT_VOICE_PAGE,
            AndroidVoiceAction.StopNavigation to VoiceCommandUiDestination.CURRENT_VOICE_PAGE,
        )
        // One entry per AndroidVoiceAction subtype (w25: PreviousDestinationCandidates and CancelDialog
        // added); uiDestination()'s exhaustive when covers new ones.
        assertEquals(18, expected.keys.map { it::class.java }.toSet().size)
        expected.forEach { (action, destination) -> assertEquals(action.toString(), destination, action.uiDestination()) }
    }
}

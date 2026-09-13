package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandUiDestinationPolicyTest {
    @Test
    fun destinationSearchSelectionAndControlsOpenTheDestinationPage() {
        listOf("서울역으로 안내해줘", "1번 선택", "다시 듣기", "더 듣기", "목적지 취소", "안내 시작")
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
    fun otherCommandsUseTheVoiceCommandPage() {
        listOf(
            "신고해줘", "다시 말해줘", "도움말", "다음 경로 뭐야", "경로 다시 찾아줘",
            "위치 확인해줘", "도착 확인", "아직 도착 아니야", "길안내 중지",
        ).forEach { phrase ->
            assertEquals(
                phrase,
                VoiceCommandUiDestination.VOICE_COMMAND,
                selectAndroidVoiceAction(listOf(phrase), floatArrayOf(0.9f))?.uiDestination(),
            )
        }
    }
}

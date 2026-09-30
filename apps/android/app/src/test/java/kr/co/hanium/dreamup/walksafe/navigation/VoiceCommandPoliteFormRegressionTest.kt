package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandPoliteFormRegressionTest {
    @Test
    fun politeAndScreenNamingSettingsRequestsOpenSettings() {
        listOf("설정 열어주세요", "설정 화면 열어줘", "설정 화면 열어주세요", "설정 열어줘", "설정").forEach { phrase ->
            assertEquals(phrase, AndroidVoiceCommand.OpenSettings, parseAndroidVoiceCommand(phrase))
            assertEquals(phrase, AndroidVoiceAction.OpenSettings, selectAndroidVoiceAction(listOf(phrase), floatArrayOf(0.9f)))
        }
    }

    @Test
    fun askingForHelpByNameReadsHelp() {
        listOf("도움말 알려줘", "도움말 알려주세요", "도움말").forEach { phrase ->
            assertEquals(phrase, AndroidVoiceCommand.Help, parseAndroidVoiceCommand(phrase))
        }
    }

    // S05-10 (05.txt:128 "실제 신고 기능 범위를 정한 뒤 필요한 표현만"): the explicit damaged tactile paving report.
    @Test
    fun reportRequestsNamingTheReportedDamageFileTheReport() {
        listOf(
            "신고 접수해줘", "신고 접수해 주세요", "신고 접수", "점자블록 신고해줘", "점자블록을 신고해줘", "손상 신고해줘",
            "점자블록 손상 신고해줘", "손상된 점자블록 신고해줘", "신고해줘", "이거 신고해",
        ).forEach { phrase ->
            assertEquals(phrase, AndroidVoiceCommand.CreateReport, parseAndroidVoiceCommand(phrase))
            assertEquals(phrase, AndroidVoiceAction.CreateReport, selectAndroidVoiceAction(listOf(phrase), floatArrayOf(0.9f)))
        }
        // Another object is not a report this app files; negated or combined forms do nothing.
        listOf(
            "장애물 신고해줘", "점자블록 신고하지 마", "신고 접수하지 마세요", "손상 신고 안 해",
            "점자블록 신고해줘 그리고 서울역 찾아줘", "신고 접수해주고 편의점 찾아줘",
        ).forEach { phrase -> assertNull(phrase, parseAndroidVoiceCommand(phrase)) }
    }

    // S05-10 (05.txt:139 "재측정 요청과 위치 설명 요청을 구분"): re-measure is a command, a description is not offered.
    @Test
    fun reMeasuringTheLocationIsTheRecheckCommandWhileADescriptionRequestIsRecognisedAsUnsupported() {
        listOf("내 위치 확인해줘", "내 위치 다시 확인해줘", "내 위치 확인", "위치 다시 확인", "위치 확인해줘").forEach { phrase ->
            assertEquals(phrase, AndroidVoiceCommand.RecheckLocation, parseAndroidVoiceCommand(phrase))
            assertFalse(phrase, isLocationDescriptionRequest(phrase))
        }
        listOf("현재 위치 알려줘", "현재 위치 알려주세요", "내 위치 알려줘", "여기가 어디야", "지금 어디야").forEach { phrase ->
            assertNull(phrase, parseAndroidVoiceCommand(phrase))
            assertTrue(phrase, isLocationDescriptionRequest(phrase))
        }
        listOf("현재 위치 알려주지 마", "서울역 위치 알려줘", "내 위치 확인하지 마").forEach { phrase ->
            assertFalse(phrase, isLocationDescriptionRequest(phrase))
        }
        assertNull(parseAndroidVoiceCommand("내 위치 확인하지 마"))
        assertTrue(LOCATION_DESCRIPTION_UNSUPPORTED_KO.contains("기능은 없습니다"))
    }

    @Test
    fun negatedOrCombinedFormsStillDoNothing() {
        listOf(
            "설정 열어주지 마", "도움말 알려주지 마세요", "설정 화면 열어줘 그리고 편의점 찾아줘",
            "설정 화면", "도움말 화면 열어줘",
        ).forEach { phrase -> assertNull(phrase, parseAndroidVoiceCommand(phrase)) }
    }
}

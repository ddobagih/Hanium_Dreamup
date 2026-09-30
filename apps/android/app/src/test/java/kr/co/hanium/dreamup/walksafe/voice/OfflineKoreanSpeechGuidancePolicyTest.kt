package kr.co.hanium.dreamup.walksafe.voice

import android.speech.tts.TextToSpeech
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S13-15: the unsupported notice separates installing Korean data from changing the preferred engine. */
class OfflineKoreanSpeechGuidancePolicyTest {
    @Test
    fun languageStatusSeparatesMissingDataFromAnEngineWithoutKorean() {
        assertEquals(OfflineKoreanSpeechUnavailableCause.KOREAN_VOICE_DATA_MISSING,
            OfflineKoreanSpeechGuidancePolicy.classifyLanguageStatus(TextToSpeech.LANG_MISSING_DATA, installedEngineCount = 1))
        assertEquals(OfflineKoreanSpeechUnavailableCause.KOREAN_VOICE_DATA_MISSING,
            OfflineKoreanSpeechGuidancePolicy.classifyLanguageStatus(TextToSpeech.LANG_MISSING_DATA, installedEngineCount = 3))
        assertEquals(OfflineKoreanSpeechUnavailableCause.PREFERRED_ENGINE_LACKS_KOREAN,
            OfflineKoreanSpeechGuidancePolicy.classifyLanguageStatus(TextToSpeech.LANG_NOT_SUPPORTED, installedEngineCount = 2))
        assertEquals(OfflineKoreanSpeechUnavailableCause.NO_KOREAN_CAPABLE_ENGINE,
            OfflineKoreanSpeechGuidancePolicy.classifyLanguageStatus(TextToSpeech.LANG_NOT_SUPPORTED, installedEngineCount = 1))
        assertEquals(OfflineKoreanSpeechUnavailableCause.NO_KOREAN_CAPABLE_ENGINE,
            OfflineKoreanSpeechGuidancePolicy.classifyLanguageStatus(TextToSpeech.LANG_NOT_SUPPORTED, installedEngineCount = 0))
        assertNull(OfflineKoreanSpeechGuidancePolicy.classifyLanguageStatus(TextToSpeech.LANG_AVAILABLE, 2))
        assertNull(OfflineKoreanSpeechGuidancePolicy.classifyLanguageStatus(TextToSpeech.LANG_COUNTRY_AVAILABLE, 2))
    }

    @Test
    fun guidanceChoosesTheSettingsScreenByCauseAndKeepsTheOldTextForUnknownCauses() {
        val data = OfflineKoreanSpeechGuidancePolicy.guidance(OfflineKoreanSpeechUnavailableCause.KOREAN_VOICE_DATA_MISSING)
        val engine = OfflineKoreanSpeechGuidancePolicy.guidance(OfflineKoreanSpeechUnavailableCause.PREFERRED_ENGINE_LACKS_KOREAN)
        val none = OfflineKoreanSpeechGuidancePolicy.guidance(OfflineKoreanSpeechUnavailableCause.NO_KOREAN_CAPABLE_ENGINE)
        val unknown = OfflineKoreanSpeechGuidancePolicy.guidance(null)
        assertEquals(OfflineKoreanSpeechSettingsAction.INSTALL_VOICE_DATA, data.settingsAction)
        assertEquals(OfflineKoreanSpeechSettingsAction.CHOOSE_PREFERRED_ENGINE, engine.settingsAction)
        assertEquals(OfflineKoreanSpeechSettingsAction.CHOOSE_PREFERRED_ENGINE, none.settingsAction)
        assertEquals(OfflineKoreanSpeechSettingsAction.INSTALL_VOICE_DATA, unknown.settingsAction)
        assertTrue(data.detail.contains("한국어 음성 데이터를 설치"))
        assertTrue(engine.detail.contains("기본 엔진으로 선택"))
        assertFalse("engine change is not described as a data install", engine.detail.contains("데이터를 설치"))
        assertTrue(none.detail.contains("보행 안내를 시작할 수 없습니다"))
        assertEquals("한국어 안내 음성을 실제로 만들지 못했습니다. 한국어 음성 데이터를 설치한 뒤 다시 점검하세요.", unknown.detail)
        assertEquals("한국어 음성 데이터 설치", unknown.actionLabel)
        assertEquals("한국어 음성 데이터 설치 열기", unknown.settingsButtonText)
        for (guidance in listOf(data, engine, none, unknown)) {
            assertTrue(guidance.openedDetail.contains("돌아오면"))
            assertTrue(guidance.actionContentDescription.startsWith(guidance.settingsButtonText))
            for (text in listOf(guidance.title, guidance.detail, guidance.actionLabel, guidance.openedDetail)) {
                assertFalse("no network or online voice is offered: $text", text.contains("온라인") || text.contains("네트워크 음성"))
            }
        }
    }

    @Test
    fun networkOnlyOrNotInstalledKoreanVoicesNeverCountAsOffline() {
        assertFalse(isInstalledOfflineKoreanVoice(Locale.KOREAN, networkRequired = true, notInstalled = false))
        assertFalse(isInstalledOfflineKoreanVoice(Locale.KOREAN, networkRequired = false, notInstalled = true))
        assertTrue(isInstalledOfflineKoreanVoice(Locale.KOREAN, networkRequired = false, notInstalled = false))
    }
}

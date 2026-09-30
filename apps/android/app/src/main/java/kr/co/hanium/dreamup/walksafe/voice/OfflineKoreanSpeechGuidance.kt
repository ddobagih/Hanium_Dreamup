package kr.co.hanium.dreamup.walksafe.voice

import android.speech.tts.TextToSpeech

/**
 * Why the system-preferred TTS engine could not provide an installed, network-free Korean voice.
 * The app never switches engines or falls back to a network voice by itself (FP-027-DETAIL-01,
 * FP-027 prohibited); the user changes the preferred engine or installs voice data in Android settings.
 */
enum class OfflineKoreanSpeechUnavailableCause {
    /** The engine knows Korean but has no installed offline Korean voice (missing or network-only data). */
    KOREAN_VOICE_DATA_MISSING,
    /** The preferred engine does not support Korean while another TTS engine is installed. */
    PREFERRED_ENGINE_LACKS_KOREAN,
    /** The only installed engine does not support Korean. */
    NO_KOREAN_CAPABLE_ENGINE,
}

enum class OfflineKoreanSpeechSettingsAction { INSTALL_VOICE_DATA, CHOOSE_PREFERRED_ENGINE }

data class OfflineKoreanSpeechGuidance(
    val cause: OfflineKoreanSpeechUnavailableCause?,
    val title: String,
    val detail: String,
    val actionLabel: String,
    val settingsButtonText: String,
    val actionContentDescription: String,
    /** Status shown after the settings screen opened; the probe runs again when the user returns. */
    val openedDetail: String,
    val settingsAction: OfflineKoreanSpeechSettingsAction,
)

object OfflineKoreanSpeechGuidancePolicy {
    fun classifyLanguageStatus(languageStatus: Int, installedEngineCount: Int): OfflineKoreanSpeechUnavailableCause? =
        when (languageStatus) {
            TextToSpeech.LANG_MISSING_DATA -> OfflineKoreanSpeechUnavailableCause.KOREAN_VOICE_DATA_MISSING
            TextToSpeech.LANG_NOT_SUPPORTED -> if (installedEngineCount > 1) {
                OfflineKoreanSpeechUnavailableCause.PREFERRED_ENGINE_LACKS_KOREAN
            } else {
                OfflineKoreanSpeechUnavailableCause.NO_KOREAN_CAPABLE_ENGINE
            }
            else -> null
        }

    /** Unknown cause keeps the previous voice-data guidance. No text offers a network voice. */
    fun guidance(cause: OfflineKoreanSpeechUnavailableCause?): OfflineKoreanSpeechGuidance = when (cause) {
        OfflineKoreanSpeechUnavailableCause.PREFERRED_ENGINE_LACKS_KOREAN -> OfflineKoreanSpeechGuidance(
            cause = cause,
            title = "기본 음성 엔진 변경 필요",
            detail = "현재 기본 음성 엔진은 한국어를 지원하지 않습니다. 휴대폰 설정의 텍스트 음성 변환에서 " +
                "한국어 오프라인 음성을 지원하는 엔진을 기본 엔진으로 선택한 뒤 WalkSafe로 돌아오면 다시 확인합니다.",
            actionLabel = "기본 음성 엔진 설정",
            settingsButtonText = "기본 음성 엔진 설정 열기",
            actionContentDescription = "기본 음성 엔진 설정 열기. 휴대폰 텍스트 음성 변환 설정으로 이동합니다.",
            openedDetail = "한국어 오프라인 음성을 지원하는 엔진을 기본 엔진으로 선택한 뒤 WalkSafe로 돌아오면 사용 가능 여부를 다시 확인합니다.",
            settingsAction = OfflineKoreanSpeechSettingsAction.CHOOSE_PREFERRED_ENGINE,
        )
        OfflineKoreanSpeechUnavailableCause.NO_KOREAN_CAPABLE_ENGINE -> OfflineKoreanSpeechGuidance(
            cause = cause,
            title = "한국어 음성 엔진 필요",
            detail = "설치된 음성 엔진이 한국어를 지원하지 않습니다. 한국어 오프라인 음성을 지원하는 엔진을 설치해 " +
                "기본 엔진으로 선택한 뒤 WalkSafe로 돌아오면 다시 확인합니다. 그 전에는 보행 안내를 시작할 수 없습니다.",
            actionLabel = "음성 엔진 설정",
            settingsButtonText = "음성 엔진 설정 열기",
            actionContentDescription = "음성 엔진 설정 열기. 휴대폰 텍스트 음성 변환 설정으로 이동합니다.",
            openedDetail = "한국어 오프라인 음성을 지원하는 엔진을 기본 엔진으로 선택한 뒤 WalkSafe로 돌아오면 사용 가능 여부를 다시 확인합니다.",
            settingsAction = OfflineKoreanSpeechSettingsAction.CHOOSE_PREFERRED_ENGINE,
        )
        OfflineKoreanSpeechUnavailableCause.KOREAN_VOICE_DATA_MISSING -> OfflineKoreanSpeechGuidance(
            cause = cause,
            title = "한국어 음성 데이터 설치 필요",
            detail = "기본 음성 엔진에 네트워크 없이 쓰는 한국어 음성 데이터가 없습니다. 한국어 음성 데이터를 설치한 뒤 " +
                "WalkSafe로 돌아오면 다시 확인합니다.",
            actionLabel = "한국어 음성 데이터 설치",
            settingsButtonText = "한국어 음성 데이터 설치 열기",
            actionContentDescription = "한국어 음성 데이터 설치 열기. 휴대폰 음성 설정으로 이동합니다.",
            openedDetail = "한국어 음성 데이터를 설치한 뒤 WalkSafe로 돌아오면 사용 가능 여부를 다시 확인합니다.",
            settingsAction = OfflineKoreanSpeechSettingsAction.INSTALL_VOICE_DATA,
        )
        null -> OfflineKoreanSpeechGuidance(
            cause = null,
            title = "한국어 음성 데이터 설치",
            detail = "한국어 안내 음성을 실제로 만들지 못했습니다. 한국어 음성 데이터를 설치한 뒤 다시 점검하세요.",
            actionLabel = "한국어 음성 데이터 설치",
            settingsButtonText = "한국어 음성 데이터 설치 열기",
            actionContentDescription = "한국어 음성 데이터 설치 열기. 휴대폰 음성 설정으로 이동합니다.",
            openedDetail = "한국어 음성 데이터를 설치한 뒤 WalkSafe로 돌아오면 사용 가능 여부를 다시 확인합니다.",
            settingsAction = OfflineKoreanSpeechSettingsAction.INSTALL_VOICE_DATA,
        )
    }
}

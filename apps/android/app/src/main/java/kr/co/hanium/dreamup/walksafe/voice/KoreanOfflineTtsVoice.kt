package kr.co.hanium.dreamup.walksafe.voice

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/** Engines may expose Korean with the ISO 639-1 code ko or ISO 639-2 code kor. */
internal fun isKoreanLocale(locale: Locale): Boolean =
    locale.language.equals("ko", ignoreCase = true) || locale.language.equals("kor", ignoreCase = true)

internal fun isInstalledOfflineKoreanVoice(
    locale: Locale,
    networkRequired: Boolean,
    notInstalled: Boolean,
): Boolean = isKoreanLocale(locale) && !networkRequired && !notInstalled

internal fun isInstalledOfflineKoreanVoice(voice: Voice): Boolean = isInstalledOfflineKoreanVoice(
    locale = voice.locale,
    networkRequired = voice.isNetworkConnectionRequired,
    notInstalled = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in voice.features.orEmpty(),
)

internal enum class KoreanOfflineVoiceSelection { SELECTED, UNAVAILABLE, REJECTED }

/** A valid current voice also works when the engine omits it from its voice inventory. */
internal fun selectInstalledOfflineKoreanVoice(engine: TextToSpeech): KoreanOfflineVoiceSelection {
    val current = runCatching { engine.voice }.getOrNull()
    if (current?.let(::isInstalledOfflineKoreanVoice) == true) return KoreanOfflineVoiceSelection.SELECTED
    val candidates = runCatching { engine.voices }.getOrNull().orEmpty()
        .filter(::isInstalledOfflineKoreanVoice)
        .sortedBy { it.name }
    if (candidates.isEmpty()) return KoreanOfflineVoiceSelection.UNAVAILABLE
    val selected = candidates.any { candidate ->
        runCatching { engine.setVoice(candidate) }.getOrDefault(TextToSpeech.ERROR) == TextToSpeech.SUCCESS &&
            runCatching { engine.voice }.getOrNull()?.let(::isInstalledOfflineKoreanVoice) == true
    }
    return if (selected) KoreanOfflineVoiceSelection.SELECTED else KoreanOfflineVoiceSelection.REJECTED
}

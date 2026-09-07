package kr.co.hanium.dreamup.walksafe.voice

import android.os.Bundle
import android.os.Parcelable

private const val OFFLINE_RESULT_ENGINE_KEY = "kr.co.hanium.dreamup.walksafe.offline_result_engine"
private const val RESULT_ALTERNATIVES_KEY = "results_alternatives"

/** Tag only final delivery, leaving provider candidates and confidence values unchanged. */
internal fun withOfflineSpeechResultEngine(
    results: Bundle?,
    engine: OfflineSpeechEngine,
): Bundle? = results?.let {
    Bundle(it).apply { putString(OFFLINE_RESULT_ENGINE_KEY, engine.name) }
}

internal fun offlineSpeechResultEngine(results: Bundle?): OfflineSpeechEngine? =
    when (results?.getString(OFFLINE_RESULT_ENGINE_KEY)) {
        OfflineSpeechEngine.PLATFORM.name -> OfflineSpeechEngine.PLATFORM
        OfflineSpeechEngine.VOSK.name -> OfflineSpeechEngine.VOSK
        else -> null
    }

/** Optional SDK span alternatives are not expanded into invented full-sentence candidates. */
@Suppress("DEPRECATION")
internal fun hasAdditionalOfflineSpeechAlternatives(results: Bundle?): Boolean {
    if (results == null || !results.containsKey(RESULT_ALTERNATIVES_KEY)) return false
    val alternatives = runCatching {
        results.getParcelableArrayList<Parcelable>(RESULT_ALTERNATIVES_KEY)
    }.getOrNull()
    return alternatives == null || alternatives.isNotEmpty()
}

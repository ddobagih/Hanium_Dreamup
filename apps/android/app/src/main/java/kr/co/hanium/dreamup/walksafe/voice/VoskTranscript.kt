package kr.co.hanium.dreamup.walksafe.voice

import org.json.JSONObject

internal data class VoskTranscript(
    val text: String,
    val confidence: Float?,
    val isFinal: Boolean,
)

internal fun VoskTranscript.isTrustedForCommand(): Boolean =
    confidence?.let { it.isFinite() && it >= MINIMUM_VOSK_COMMAND_CONFIDENCE } == true

/** Parses only the bounded text and word confidence fields used by the voice controller. */
internal fun parseVoskTranscript(
    payload: String,
    isFinal: Boolean,
): VoskTranscript? {
    if (payload.length !in 2..MAX_VOSK_RESULT_JSON_CHARS) return null
    val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
    val key = if (isFinal) "text" else "partial"
    val text = json.optString(key)
        .replace(TRANSCRIPT_WHITESPACE, " ")
        .trim()
        .take(MAX_VOSK_TRANSCRIPT_CHARS)
    if (text.isBlank()) return null

    val words = if (isFinal) json.optJSONArray("result") else null
    val confidences = buildList {
        if (words == null) return@buildList
        for (index in 0 until words.length()) {
            val confidence = words.optJSONObject(index)?.optDouble("conf", Double.NaN)
            if (confidence != null && confidence.isFinite() && confidence in 0.0..1.0) {
                add(confidence)
            }
        }
    }
    return VoskTranscript(
        text = text,
        confidence = confidences.takeIf { it.isNotEmpty() }
            ?.average()
            ?.toFloat(),
        isFinal = isFinal,
    )
}

private val TRANSCRIPT_WHITESPACE = Regex("\\s+")
private const val MAX_VOSK_RESULT_JSON_CHARS = 32_768
private const val MAX_VOSK_TRANSCRIPT_CHARS = 256
private const val MINIMUM_VOSK_COMMAND_CONFIDENCE = 0.60f

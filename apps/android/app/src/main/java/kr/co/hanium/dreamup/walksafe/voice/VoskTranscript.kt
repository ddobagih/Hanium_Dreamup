package kr.co.hanium.dreamup.walksafe.voice

import kr.co.hanium.dreamup.walksafe.navigation.isFixedVoiceCommandPhrase
import org.json.JSONArray
import org.json.JSONObject

internal data class VoskTranscript(
    val text: String,
    val confidence: Float?,
    val isFinal: Boolean,
    /**
     * Per-word Vosk confidences aligned with the space-separated [text] tokens; set only when every
     * word had a valid score. Null means only the aggregate [confidence] is known.
     */
    val wordConfidences: List<Float>? = null,
)

internal fun VoskTranscript.isTrustedForCommand(): Boolean =
    confidence?.let { it.isFinite() && it in MINIMUM_VOSK_COMMAND_CONFIDENCE..1f } == true

/**
 * S04-19/S05-23: the command part of a final transcript, judged on its own words.
 *
 * The utterance average can hide one badly heard word (0.10 among nine 1.00 words averages 0.91),
 * and a clearly heard wake phrase can lift a doubtful command. So the command words (after the wake
 * phrase) must average the same approved threshold on their own, and every decisive word - a
 * number/ordinal, a negation or a command action word, where one wrong word changes the action -
 * must reach it too.
 * Returns null when that cannot be shown; the caller then asks again instead of acting
 * (FP-025 design_rules[3], FP-026 failure_behavior[0]). The threshold itself is unchanged.
 */
internal fun VoskTranscript.commandSpanOrNull(commandText: String): VoskTranscript? =
    commandSpanOrNull(commandText, requireWholeUtteranceConfidence = true)

/** Used only to decide whether an addressed but doubtful utterance deserves a spoken re-ask. */
internal fun VoskTranscript.hasTrustedCommandWordsForReprompt(commandText: String): Boolean =
    wordConfidences != null && commandSpanOrNull(commandText, requireWholeUtteranceConfidence = false) != null

private fun VoskTranscript.commandSpanOrNull(
    commandText: String,
    requireWholeUtteranceConfidence: Boolean,
): VoskTranscript? {
    if (!isFinal || (requireWholeUtteranceConfidence && !isTrustedForCommand())) return null
    val tokens = text.split(' ')
    val commandTokens = commandText.split(' ')
    if (commandText.isBlank() || commandTokens.size > tokens.size) return null
    val offset = tokens.size - commandTokens.size
    if (tokens.subList(offset, tokens.size) != commandTokens) return null
    val scores = wordConfidences
        ?: List(tokens.size) { requireNotNull(confidence) }
    if (scores.size != tokens.size) return null
    val span = scores.subList(offset, scores.size)
    if (span.any { !it.isFinite() || it !in 0f..1f }) return null
    val spanConfidence = span.average().toFloat()
    if (spanConfidence < MINIMUM_VOSK_COMMAND_CONFIDENCE) return null
    // voice-F7: in a fixed command phrase every word is decisive ("다른 경로", "보행 종료", "검색 취소");
    // only a destination's free words stay under the span average.
    val fixedPhrase = isFixedVoiceCommandPhrase(commandText)
    val decisiveBelowThreshold = commandTokens.indices.any { index ->
        val token = commandTokens[index]
        (fixedPhrase || isDecisiveCommandToken(token) || isCommandActionToken(token)) &&
            span[index] < MINIMUM_VOSK_COMMAND_CONFIDENCE
    }
    if (decisiveBelowThreshold) return null
    return VoskTranscript(
        text = commandText,
        confidence = spanConfidence,
        isFinal = true,
        wordConfidences = wordConfidences?.let { span.toList() },
    )
}

/**
 * S04-19: the wake phrase words in front of [commandText] are judged on their own, so a clearly
 * heard command cannot lift a doubtful wake phrase into an addressed utterance. With only the
 * aggregate score the existing whole-utterance rule stays (the wake-only utterance rule is the same).
 * False also when the words cannot be aligned with the text.
 */
internal fun VoskTranscript.wakeSpanTrustedBefore(commandText: String): Boolean =
    wakeSpanTrustedBefore(commandText, requireWholeUtteranceConfidence = true)

/** A clear wake span can trigger a re-ask even when a doubtful command lowered the whole average. */
internal fun VoskTranscript.hasTrustedWakeWordsForReprompt(commandText: String): Boolean =
    wordConfidences != null && wakeSpanTrustedBefore(commandText, requireWholeUtteranceConfidence = false)

private fun VoskTranscript.wakeSpanTrustedBefore(
    commandText: String,
    requireWholeUtteranceConfidence: Boolean,
): Boolean {
    if (!isFinal || (requireWholeUtteranceConfidence && !isTrustedForCommand())) return false
    val scores = wordConfidences ?: return true
    val tokens = text.split(' ')
    val commandTokens = commandText.split(' ')
    if (commandText.isBlank() || commandTokens.size >= tokens.size || scores.size != tokens.size) return false
    val offset = tokens.size - commandTokens.size
    if (tokens.subList(offset, tokens.size) != commandTokens) return false
    val wake = scores.subList(0, offset)
    if (wake.any { !it.isFinite() || it !in 0f..1f }) return false
    return wake.average().toFloat() >= MINIMUM_VOSK_COMMAND_CONFIDENCE
}

/** Numbers/ordinals ("2번", "두 번째", "셋") and negations ("말고", "아니", "아냐", "안", "마", "싫어"). */
internal fun isDecisiveCommandToken(token: String): Boolean =
    DECISIVE_NUMBER_TOKEN.matches(token) || DECISIVE_NEGATION_TOKEN.containsMatchIn(token)

/**
 * S04-19/S05-23: the fixed action words of the command grammar (AndroidVoiceCommand and the walk
 * session phrases) - verbs such as "시작", "종료", "일시정지", "재개", "취소", "선택", "신고", "안내",
 * "찾아줘", "열어줘" with their spoken endings, and the words that pick between fixed phrases
 * ("다시"/"다음"/"더"/"이전"/"앞": "다시 듣기" vs "더 듣기", "다음 안내" vs "다시 안내").
 * A misheard one changes the action, so like a number or a negation it must reach the threshold
 * on its own. Place names and free words are not listed; they stay under the span average.
 */
internal fun isCommandActionToken(token: String): Boolean = COMMAND_ACTION_TOKEN.matches(token)

/** Parses only the bounded text and word confidence fields used by the voice controller. */
internal fun parseVoskTranscript(
    payload: String,
    isFinal: Boolean,
): VoskTranscript? {
    if (payload.length !in 2..MAX_VOSK_RESULT_JSON_CHARS) return null
    val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
    val key = if (isFinal) "text" else "partial"
    val rawText = json.opt(key) as? String ?: return null
    val text = rawText
        .replace(TRANSCRIPT_WHITESPACE, " ")
        .trim()
    if (text.isBlank() || text.codePointCount(0, text.length) > MAX_VOSK_TRANSCRIPT_CHARS) return null

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
    val complete = confidences.takeIf {
        words != null && it.isNotEmpty() && it.size == words.length() && wordsAlignWithText(words, text)
    }
    return VoskTranscript(
        text = text,
        confidence = complete?.average()?.toFloat(),
        isFinal = isFinal,
        wordConfidences = complete?.map(Double::toFloat),
    )
}

/**
 * S05-24: word scores count only when they belong to the words of [text]. If every entry names its
 * word, those words must be the text words in order; without word fields the score count must
 * equal the text word count. Mixed or blank entries are not trusted, so the sentence gets no score.
 */
private fun wordsAlignWithText(words: JSONArray, text: String): Boolean {
    val tokens = text.split(' ')
    val named = (0 until words.length()).map { index ->
        (words.optJSONObject(index)?.opt("word") as? String)?.replace(TRANSCRIPT_WHITESPACE, " ")?.trim()
    }
    return when {
        named.all { it == null } -> words.length() == tokens.size
        named.any { it.isNullOrEmpty() } -> false
        else -> named == tokens
    }
}

private val TRANSCRIPT_WHITESPACE = Regex("\\s+")
private val DECISIVE_NUMBER_TOKEN = Regex(
    "^(?:(?:[0-9]+|일|이|삼|사|오|육|칠|팔|구|십|하나|둘|셋|넷|다섯|여섯|일곱|여덟|아홉|열|첫|두|세|네|한)" +
        "(?:번째|번|째)?|번째|번)(?:으로|로|을|를|이|가|은|는|요)?$",
)
private val DECISIVE_NEGATION_TOKEN = Regex("^안$|않|아니|아냐|말고|말아|^마$|^그만|^취소|싫")
private const val COMMAND_ACTION_NOUNS =
    "시작|종료|중지|중단|정지|일시정지|재개|취소|삭제|선택|신고|싱고|접수|안내|설정|지정|변경|이동|확인|재탐색|탐색|요청|도착"
private val COMMAND_ACTION_TOKEN = Regex(
    "^(?:" +
        "[가-힣]*(?:$COMMAND_ACTION_NOUNS)" +
        "(?:해|해줘|해주세요|해줄래|해요|해라|하자|하기|하고|할래|합니다|했어|했어요|했습니다|을|를|으로|로)?" +
        "|[가-힣]*(?:찾아|열어|멈춰|바꿔|골라|들려|말해|알려|지워|없애|듣기)(?:줘|주세요|줄래|요)?" +
        "|가자|가고|다시|다음|더|이전|앞" +
        ")$",
)
private const val MAX_VOSK_RESULT_JSON_CHARS = 32_768
private const val MAX_VOSK_TRANSCRIPT_CHARS = 256
private const val MINIMUM_VOSK_COMMAND_CONFIDENCE = 0.60f

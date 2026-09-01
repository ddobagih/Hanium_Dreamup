package kr.co.hanium.dreamup.walksafe.voice

import java.text.Normalizer

internal enum class WakePhraseCommandMode {
    WAKE_PHRASE_REQUIRED,
    COMMAND_AWAITED,
}

internal sealed interface WakePhraseCommandExtraction {
    data object NotAddressed : WakePhraseCommandExtraction

    data object AwaitingCommand : WakePhraseCommandExtraction

    data class Command(val text: String) : WakePhraseCommandExtraction
}

/** Extracts a command only after the exact Korean wake phrase has opened a command window. */
internal object WakePhraseCommandExtractor {
    fun extractFinalTranscript(
        transcript: String,
        mode: WakePhraseCommandMode = WakePhraseCommandMode.WAKE_PHRASE_REQUIRED,
    ): WakePhraseCommandExtraction {
        val tokens = normalizedTokens(transcript)
            ?: return WakePhraseCommandExtraction.NotAddressed
        val wakePhraseEnd = wakePhraseEnd(tokens)

        if (
            mode == WakePhraseCommandMode.WAKE_PHRASE_REQUIRED &&
            wakePhraseEnd == null
        ) {
            return WakePhraseCommandExtraction.NotAddressed
        }

        val commandStart = wakePhraseEnd ?: 0
        val command = tokens.drop(commandStart).joinToString(" ")
        return if (command.isEmpty()) {
            WakePhraseCommandExtraction.AwaitingCommand
        } else {
            WakePhraseCommandExtraction.Command(command)
        }
    }

    private fun wakePhraseEnd(tokens: List<String>): Int? {
        tokens.indices.forEach { index ->
            if (tokens[index] == WAKE_PHRASE) return index + 1
            if (
                tokens[index] == WAKE_PHRASE_FIRST_PART &&
                tokens.getOrNull(index + 1) == WAKE_PHRASE_SECOND_PART
            ) {
                return index + 2
            }
        }
        return null
    }

    private fun normalizedTokens(value: String): List<String>? {
        if (value.length > MAX_TRANSCRIPT_CODE_POINTS * 2) return null
        if (value.codePointCount(0, value.length) > MAX_TRANSCRIPT_CODE_POINTS) return null

        val canonical = Normalizer.normalize(value, Normalizer.Form.NFKC)
        if (canonical.codePointCount(0, canonical.length) > MAX_TRANSCRIPT_CODE_POINTS) {
            return null
        }

        val normalized = StringBuilder(canonical.length)
        var index = 0
        var separatorPending = false
        while (index < canonical.length) {
            val codePoint = canonical.codePointAt(index)
            index += Character.charCount(codePoint)
            when {
                isSeparator(codePoint) -> separatorPending = normalized.isNotEmpty()
                isUnsafeCodePoint(codePoint) -> return null
                else -> {
                    if (separatorPending) normalized.append(' ')
                    normalized.appendCodePoint(codePoint)
                    separatorPending = false
                }
            }
        }
        if (normalized.isEmpty()) return emptyList()
        return normalized.toString().split(' ')
    }

    private fun isSeparator(codePoint: Int): Boolean {
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) return true
        return when (Character.getType(codePoint)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            -> true
            else -> false
        }
    }

    private fun isUnsafeCodePoint(codePoint: Int): Boolean {
        return when (Character.getType(codePoint)) {
            Character.CONTROL.toInt(),
            Character.FORMAT.toInt(),
            Character.SURROGATE.toInt(),
            Character.PRIVATE_USE.toInt(),
            Character.UNASSIGNED.toInt(),
            -> true
            else -> false
        }
    }
}

internal const val MAX_WAKE_PHRASE_TRANSCRIPT_CODE_POINTS = 500

private const val MAX_TRANSCRIPT_CODE_POINTS = MAX_WAKE_PHRASE_TRANSCRIPT_CODE_POINTS
private const val WAKE_PHRASE = "길라잡이"
private const val WAKE_PHRASE_FIRST_PART = "길라"
private const val WAKE_PHRASE_SECOND_PART = "잡이"

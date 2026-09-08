package kr.co.hanium.dreamup.walksafe.device

enum class KoreanTtsOutputDurationVerdict {
    SUFFICIENT,
    TOO_SHORT,
    EMPTY,
    UNREADABLE,
}

/**
 * Decides whether a synthesised probe file actually carries the utterance.
 *
 * Existence is not evidence: a RIFF header with no samples behind it is a non-empty file, and
 * accepting it reported speech the phone never produced. Size cannot be the bar either — the same
 * sentence measured 81,384 B on Google's engine and 99,884 B on Samsung's (SM-A716S, 2026-09-08),
 * a 23 % spread that comes from sample rate rather than from anything about the speech. Duration
 * is what the two have in common.
 *
 * Pure bytes in, verdict out, so the parsing is testable without a device.
 */
object KoreanTtsOutputDurationPolicy {
    /**
     * Both measured engines produced at least 1,694 ms for [PROBE_TEXT_KO]. The floor keeps ~40 %
     * of headroom for a faster engine or a user's raised speech rate while still rejecting a
     * header-only, truncated or silent file.
     */
    const val MINIMUM_DURATION_MS = 1_000L

    fun verify(bytes: ByteArray): KoreanTtsOutputDurationVerdict {
        if (bytes.isEmpty()) return KoreanTtsOutputDurationVerdict.EMPTY
        val durationMs = durationMsOrNull(bytes)
            ?: return KoreanTtsOutputDurationVerdict.UNREADABLE
        return if (durationMs >= MINIMUM_DURATION_MS) {
            KoreanTtsOutputDurationVerdict.SUFFICIENT
        } else {
            KoreanTtsOutputDurationVerdict.TOO_SHORT
        }
    }

    /** Reads the RIFF fmt/data chunks; null when the bytes are not a WAV this can measure. */
    fun durationMsOrNull(bytes: ByteArray): Long? {
        if (bytes.size < RIFF_HEADER_BYTES) return null
        if (ascii(bytes, 0) != "RIFF" || ascii(bytes, 8) != "WAVE") return null

        var offset = RIFF_HEADER_BYTES
        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var dataBytes = -1L
        while (offset + CHUNK_HEADER_BYTES <= bytes.size) {
            val id = ascii(bytes, offset)
            val size = le32(bytes, offset + 4)
            if (size < 0) return null
            val body = offset + CHUNK_HEADER_BYTES
            when {
                id == "fmt " && body + FMT_MINIMUM_BYTES <= bytes.size -> {
                    channels = le16(bytes, body + 2)
                    sampleRate = le32(bytes, body + 4)
                    bitsPerSample = le16(bytes, body + 14)
                }
                id == "data" -> dataBytes = minOf(size.toLong(), (bytes.size - body).toLong())
            }
            if (size == 0) break
            // Chunks are word aligned, so an odd size carries one pad byte.
            offset = body + size + (size and 1)
        }

        if (dataBytes < 0L) return null
        val byteRate = sampleRate.toLong() * channels * (bitsPerSample / 8)
        if (byteRate <= 0L) return null
        return dataBytes * 1_000L / byteRate
    }

    private fun ascii(bytes: ByteArray, at: Int): String =
        String(bytes, at, 4, Charsets.US_ASCII)

    private fun le16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)

    private fun le32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xff) or
            ((bytes[at + 1].toInt() and 0xff) shl 8) or
            ((bytes[at + 2].toInt() and 0xff) shl 16) or
            ((bytes[at + 3].toInt() and 0xff) shl 24)

    private const val RIFF_HEADER_BYTES = 12
    private const val CHUNK_HEADER_BYTES = 8
    private const val FMT_MINIMUM_BYTES = 16
}

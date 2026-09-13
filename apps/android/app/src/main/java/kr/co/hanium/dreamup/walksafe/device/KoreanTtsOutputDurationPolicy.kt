package kr.co.hanium.dreamup.walksafe.device

enum class KoreanTtsOutputDurationVerdict {
    SUFFICIENT,
    TOO_SHORT,
    EMPTY,
    UNREADABLE,
}

/**
 * Checks complete integer-PCM WAV structure and a minimum amount of synthesized audio.
 * Duration does not establish non-silence, intelligibility, the spoken text or speaker output.
 */
object KoreanTtsOutputDurationPolicy {
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

    /** Unknown chunks are skipped only after validating their unsigned size and padding. */
    fun durationMsOrNull(bytes: ByteArray): Long? {
        if (bytes.size < RIFF_HEADER_BYTES) return null
        if (ascii(bytes, 0) != "RIFF" || ascii(bytes, 8) != "WAVE") return null
        val riffEnd = le32(bytes, 4) + 8L
        if (riffEnd != bytes.size.toLong()) return null

        var offset = RIFF_HEADER_BYTES.toLong()
        var format: PcmFormat? = null
        var dataBytes: Long? = null
        while (offset < riffEnd) {
            if (riffEnd - offset < CHUNK_HEADER_BYTES) return null
            val at = offset.toInt()
            val id = ascii(bytes, at)
            val size = le32(bytes, at + 4)
            val body = offset + CHUNK_HEADER_BYTES
            if (size > riffEnd - body) return null
            val bodyEnd = body + size
            val nextChunk = bodyEnd + (size and 1L)
            if (nextChunk > riffEnd) return null

            when (id) {
                "fmt " -> {
                    if (format != null) return null
                    format = readPcmFormat(bytes, body.toInt(), size) ?: return null
                }
                "data" -> {
                    if (dataBytes != null) return null
                    dataBytes = size
                }
            }
            offset = nextChunk
        }

        val pcm = format ?: return null
        val sampleBytes = dataBytes ?: return null
        if (sampleBytes % pcm.blockAlignment != 0L) return null
        return sampleBytes * 1_000L / pcm.byteRate
    }

    private fun readPcmFormat(bytes: ByteArray, at: Int, size: Long): PcmFormat? {
        if (size < FMT_MINIMUM_BYTES || size == 17L) return null
        // Extended fmt chunks must contain their complete, declared extension as well.
        if (size >= 18L && le16(bytes, at + 16).toLong() != size - 18L) return null
        if (le16(bytes, at) != PCM_FORMAT_TAG) return null
        val channels = le16(bytes, at + 2)
        val sampleRate = le32(bytes, at + 4)
        val byteRate = le32(bytes, at + 8)
        val blockAlignment = le16(bytes, at + 12)
        val bitsPerSample = le16(bytes, at + 14)
        if (channels == 0 || sampleRate == 0L || bitsPerSample !in setOf(8, 16, 24, 32)) {
            return null
        }
        val expectedBlockAlignment = channels.toLong() * (bitsPerSample / 8)
        if (blockAlignment.toLong() != expectedBlockAlignment) return null
        if (byteRate != sampleRate * expectedBlockAlignment) return null
        return PcmFormat(blockAlignment, byteRate)
    }

    private data class PcmFormat(val blockAlignment: Int, val byteRate: Long)

    private fun ascii(bytes: ByteArray, at: Int): String =
        String(bytes, at, 4, Charsets.US_ASCII)

    private fun le16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)

    private fun le32(bytes: ByteArray, at: Int): Long =
        (bytes[at].toLong() and 0xffL) or
            ((bytes[at + 1].toLong() and 0xffL) shl 8) or
            ((bytes[at + 2].toLong() and 0xffL) shl 16) or
            ((bytes[at + 3].toLong() and 0xffL) shl 24)

    private const val RIFF_HEADER_BYTES = 12
    private const val CHUNK_HEADER_BYTES = 8
    private const val FMT_MINIMUM_BYTES = 16
    private const val PCM_FORMAT_TAG = 1
}

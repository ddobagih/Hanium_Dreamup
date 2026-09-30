package kr.co.hanium.dreamup.walksafe.report

/**
 * S15-18: bounded structural check of a report JPEG before it is frozen, queued or sent.
 *
 * Start/end markers alone let `FF D8 FF D9` pass and the item is then rejected by the server only
 * after it has occupied the queue. This walks the marker segments without decoding pixels: SOI, only
 * length-consistent segments inside the buffer, one baseline/extended/progressive Huffman frame
 * header (SOF0/1/2) with non-zero width and height before the first scan, at least one scan header
 * with entropy-coded data, and EOI as the last two bytes. Arithmetic, lossless and hierarchical
 * frames are rejected because the server decoder does not accept them either.
 */
internal object JpegStructureValidator {
    fun isStructurallyValid(bytes: ByteArray, maxBytes: Int = REPORT_QUEUE_MAX_PAYLOAD_BYTES): Boolean {
        if (bytes.size < MIN_JPEG_BYTES || bytes.size > maxBytes) return false
        if (u8(bytes, 0) != 0xff || u8(bytes, 1) != SOI) return false
        if (u8(bytes, bytes.size - 2) != 0xff || u8(bytes, bytes.size - 1) != EOI) return false
        var position = 2
        var frameSeen = false
        var scanSeen = false
        while (position < bytes.size) {
            if (u8(bytes, position) != 0xff) return false
            // Fill bytes (0xFF) may precede any marker.
            while (position < bytes.size && u8(bytes, position) == 0xff) position += 1
            if (position >= bytes.size) return false
            val marker = u8(bytes, position)
            position += 1
            when {
                marker == EOI -> return frameSeen && scanSeen && position == bytes.size
                marker == SOI || marker == 0x00 || marker == TEM || marker in RST_MARKERS -> return false
            }
            if (position + 2 > bytes.size) return false
            val length = (u8(bytes, position) shl 8) or u8(bytes, position + 1)
            if (length < 2 || position + length > bytes.size) return false
            val segmentStart = position + 2
            val segmentEnd = position + length
            when (marker) {
                in SUPPORTED_FRAME_MARKERS -> {
                    if (frameSeen || !validFrameHeader(bytes, segmentStart, segmentEnd)) return false
                    frameSeen = true
                }
                in UNSUPPORTED_FRAME_MARKERS -> return false
                SOS -> {
                    if (!frameSeen || !validScanHeader(bytes, segmentStart, segmentEnd)) return false
                    scanSeen = true
                    position = skipEntropyCodedData(bytes, segmentEnd) ?: return false
                    continue
                }
            }
            position = segmentEnd
        }
        return false
    }

    private fun validFrameHeader(bytes: ByteArray, start: Int, end: Int): Boolean {
        if (end - start < 6) return false
        val precision = u8(bytes, start)
        val height = (u8(bytes, start + 1) shl 8) or u8(bytes, start + 2)
        val width = (u8(bytes, start + 3) shl 8) or u8(bytes, start + 4)
        val components = u8(bytes, start + 5)
        return precision in setOf(8, 12) &&
            height > 0 &&
            width > 0 &&
            components in 1..4 &&
            end - start == 6 + 3 * components
    }

    private fun validScanHeader(bytes: ByteArray, start: Int, end: Int): Boolean {
        if (end - start < 1) return false
        val components = u8(bytes, start)
        return components in 1..4 && end - start == 1 + 2 * components + 3
    }

    /** Returns the index of the marker that ends the scan, or null when no data or no end marker. */
    private fun skipEntropyCodedData(bytes: ByteArray, start: Int): Int? {
        var position = start
        while (position + 1 < bytes.size) {
            if (u8(bytes, position) == 0xff) {
                val next = u8(bytes, position + 1)
                // Stuffed zero and restart markers belong to the scan.
                if (next != 0x00 && next !in RST_MARKERS && next != 0xff) {
                    return position.takeIf { it > start }
                }
            }
            position += 1
        }
        return null
    }

    private fun u8(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and 0xff

    private const val MIN_JPEG_BYTES = 20
    private const val SOI = 0xd8
    private const val EOI = 0xd9
    private const val SOS = 0xda
    private const val TEM = 0x01
    private val RST_MARKERS = 0xd0..0xd7
    private val SUPPORTED_FRAME_MARKERS = setOf(0xc0, 0xc1, 0xc2)
    private val UNSUPPORTED_FRAME_MARKERS = setOf(0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf)
}

package kr.co.hanium.dreamup.walksafe.report

/**
 * Smallest structurally valid 1x1 baseline JPEG (SOI, SOF0, SOS, one entropy byte, EOI) for queue
 * and confirmation tests. [scanByte] varies the content without changing the size (must not be 0xFF).
 * Tests that need a decoder-produced image use javax.imageio instead (JpegStructureValidatorTest).
 */
internal fun reportTestJpeg(scanByte: Int = 1): ByteArray {
    require(scanByte in 0..0xfe)
    return jpegBytes(
        0xff, 0xd8,
        0xff, 0xc0, 0x00, 0x0b, 0x08, 0x00, 0x01, 0x00, 0x01, 0x01, 0x01, 0x11, 0x00,
        0xff, 0xda, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3f, 0x00,
        scanByte,
        0xff, 0xd9,
    )
}

private fun jpegBytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

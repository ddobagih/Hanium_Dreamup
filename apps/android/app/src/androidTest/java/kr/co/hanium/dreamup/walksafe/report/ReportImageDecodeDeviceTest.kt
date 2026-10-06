package kr.co.hanium.dreamup.walksafe.report

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class ReportImageDecodeDeviceTest {
    private fun jpeg(side: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { stream ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream))
                stream.toByteArray()
            }
        } finally { bitmap.eraseColor(0); bitmap.recycle() }
    }

    @Test fun acceptsCameraSizedJpegAndRejectsOversizedOrUndecodableData() {
        assertTrue(AndroidReportImageValidator.isDecodable(jpeg(320)))
        assertFalse(AndroidReportImageValidator.isDecodable(jpeg(321)))
        val valid = jpeg(32)
        assertFalse(AndroidReportImageValidator.isDecodable(valid.copyOf(valid.size / 2)))
        // Structurally valid frame/scan without quantization or Huffman tables.
        val fake = intArrayOf(255,216,255,192,0,11,8,0,1,0,1,1,1,17,0,
            255,218,0,8,1,1,0,0,63,0,1,255,217).map { it.toByte() }.toByteArray()
        assertTrue(JpegStructureValidator.isStructurallyValid(fake))
        assertFalse(AndroidReportImageValidator.isDecodable(fake))
    }
}

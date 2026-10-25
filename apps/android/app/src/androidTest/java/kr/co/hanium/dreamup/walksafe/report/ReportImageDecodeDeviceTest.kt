package kr.co.hanium.dreamup.walksafe.report

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import kr.co.hanium.dreamup.walksafe.testing.syntheticBoundaryContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Android decoder admission, synthetic pixels only; no clarity/damage-quality claim. */
@RunWith(AndroidJUnit4::class)
class ReportImageDecodeDeviceTest {
    @Test
    fun native320PixelJpegIsAccepted() {
        syntheticBoundaryContext()
        val jpeg = jpeg(320, 320)
        assertTrue(JpegStructureValidator.isStructurallyValid(jpeg))
        assertTrue(AndroidReportJpegValidator.isDecodable(jpeg))
    }

    @Test
    fun structurallyValidButUndecodableJpegIsRejected() {
        syntheticBoundaryContext()
        // Length-consistent SOF/SOS and entropy, deliberately no quantization/Huffman tables.
        val bytes = intArrayOf(0xff, 0xd8,
            0xff, 0xc0, 0, 17, 8, 0, 32, 0, 32, 3, 1, 0x11, 0, 2, 0x11, 0, 3, 0x11, 0,
            0xff, 0xda, 0, 12, 3, 1, 0, 2, 0x11, 3, 0x11, 0, 63, 0,
            1, 2, 3, 0xff, 0xd9).map { it.toByte() }.toByteArray()
        assertTrue("This counterexample must pass structure parsing", JpegStructureValidator.isStructurallyValid(bytes))
        assertFalse("Structure alone cannot admit a native decode failure", AndroidReportJpegValidator.isDecodable(bytes))
    }

    @Test
    fun native321PixelJpegIsRejectedAtTheDimensionBoundary() {
        syntheticBoundaryContext()
        val jpeg = jpeg(321, 320)
        assertTrue(JpegStructureValidator.isStructurallyValid(jpeg))
        assertFalse(AndroidReportJpegValidator.isDecodable(jpeg))
    }

    private fun jpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.GRAY)
            ByteArrayOutputStream().use { out ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out))
                out.toByteArray()
            }
        } finally { bitmap.eraseColor(0); bitmap.recycle() }
    }
}

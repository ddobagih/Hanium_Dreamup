package kr.co.hanium.dreamup.walksafe.report

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S15-18: marker-only fakes are rejected before a report is frozen or queued. */
class JpegStructureValidatorTest {
    @Test
    fun encoderProducedBaselineAndProgressiveJpegsAreAccepted() {
        assertTrue(JpegStructureValidator.isStructurallyValid(encodedJpeg(progressive = false)))
        assertTrue(JpegStructureValidator.isStructurallyValid(encodedJpeg(progressive = true)))
        assertTrue(JpegStructureValidator.isStructurallyValid(reportTestJpeg()))
    }

    @Test
    fun startAndEndMarkersAloneAreRejected() {
        assertFalse(JpegStructureValidator.isStructurallyValid(bytes(0xff, 0xd8, 0xff, 0xd9)))
        assertFalse(JpegStructureValidator.isStructurallyValid(bytes(0xff, 0xd8, 1, 2, 0xff, 0xd9)))
        val padded = ByteArray(64).also {
            it[0] = 0xff.toByte(); it[1] = 0xd8.toByte(); it[62] = 0xff.toByte(); it[63] = 0xd9.toByte()
        }
        assertFalse(JpegStructureValidator.isStructurallyValid(padded))
    }

    @Test
    fun truncatedOrInconsistentSegmentsAreRejected() {
        val jpeg = encodedJpeg(progressive = false)
        // Cut in the middle, then re-terminate with EOI: a segment length now runs past the data.
        val truncated = jpeg.copyOf(40) + bytes(0xff, 0xd9)
        assertFalse(JpegStructureValidator.isStructurallyValid(truncated))
        // Missing EOI.
        assertFalse(JpegStructureValidator.isStructurallyValid(jpeg.copyOf(jpeg.size - 2)))
        // Trailing bytes after EOI.
        assertFalse(JpegStructureValidator.isStructurallyValid(jpeg + bytes(0xff, 0xd9)))
        // Segment length shorter than its own length field.
        val badLength = reportTestJpeg().also { it[4] = 0x00; it[5] = 0x01 }
        assertFalse(JpegStructureValidator.isStructurallyValid(badLength))
    }

    @Test
    fun zeroWidthOrHeightFrameIsRejected() {
        assertFalse(JpegStructureValidator.isStructurallyValid(reportTestJpeg().also { it[7] = 0; it[8] = 0 }))
        assertFalse(JpegStructureValidator.isStructurallyValid(reportTestJpeg().also { it[9] = 0; it[10] = 0 }))
    }

    @Test
    fun scanWithoutFrameOrWithoutDataAndUnsupportedFramesAreRejected() {
        val noFrame = bytes(0xff, 0xd8) +
            bytes(0xff, 0xfe, 0x00, 0x0d) + ByteArray(11) +
            reportTestJpeg().copyOfRange(15, reportTestJpeg().size)
        assertFalse(JpegStructureValidator.isStructurallyValid(noFrame))
        val noScanData = reportTestJpeg().let { it.copyOfRange(0, 25) + it.copyOfRange(26, it.size) }
        assertFalse(JpegStructureValidator.isStructurallyValid(noScanData))
        val arithmetic = reportTestJpeg().also { it[3] = 0xc9.toByte() }
        assertFalse(JpegStructureValidator.isStructurallyValid(arithmetic))
        val lossless = reportTestJpeg().also { it[3] = 0xc3.toByte() }
        assertFalse(JpegStructureValidator.isStructurallyValid(lossless))
    }

    @Test
    fun sizeIsBoundedBeforeParsing() {
        val jpeg = encodedJpeg(progressive = false)
        assertFalse(JpegStructureValidator.isStructurallyValid(jpeg, maxBytes = jpeg.size - 1))
        assertTrue(JpegStructureValidator.isStructurallyValid(jpeg, maxBytes = jpeg.size))
    }

    @Test
    fun queueAndExplicitConfirmationUseTheStructuralCheck() {
        val reportId = "123e4567-e89b-42d3-a456-426614174000"
        val metadata = "{\"reporter_user_id\":\"a\",\"trigger\":\"voice\",\"auto_reported\":false}".toByteArray()
        assertNull(FrozenReportPayload.freeze(reportId, metadata, bytes(0xff, 0xd8, 1, 2, 0xff, 0xd9)))
        assertNotNull(FrozenReportPayload.freeze(reportId, metadata, encodedJpeg(progressive = false)))
        val source = java.io.File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/report/ExplicitReportConfirmationPolicy.kt",
        ).readText()
        assertTrue(source.contains("!JpegStructureValidator.isStructurallyValid(imageJpeg)"))
    }

    private fun encodedJpeg(progressive: Boolean): ByteArray {
        val image = BufferedImage(16, 8, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 16) for (y in 0 until 8) image.setRGB(x, y, (x * 16 shl 16) or (y * 32 shl 8) or 0x40)
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val output = ByteArrayOutputStream()
        MemoryCacheImageOutputStream(output).use { stream ->
            writer.output = stream
            val param = writer.defaultWriteParam
            if (progressive) param.progressiveMode = ImageWriteParam.MODE_DEFAULT
            writer.write(null, IIOImage(image, null, null), param)
        }
        writer.dispose()
        return output.toByteArray()
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}

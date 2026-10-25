package kr.co.hanium.dreamup.walksafe.inference

import java.nio.ByteBuffer
import kr.co.hanium.dreamup.walksafe.UprightCameraImage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class DetectorRgbScratchTest {
    @Test
    fun pixelsAndBilinearTensorMatchOwnedReferenceForEveryRotationCropAndResolution() {
        val processor = YuvImagePreprocessor()
        val scratch = DetectorRgbScratch(processor)
        val reference = YuvImagePreprocessor()
        val frames = listOf(frame(5, 3), frame(3, 5, 41),
            frame(5, 3, 23, YuvCropRect(1, 0, 4, 3)), frame(8, 6, 91), frame(5, 3, 79))
        for (source in frames) for (turns in 0..3) {
            val expected = UprightCameraImage.rotate(source.toArgb(), turns)
            val actual = scratch.rotate(scratch.decode(source), turns)
            assertEquals(expected.width, actual.width)
            assertEquals(expected.height, actual.height)
            assertArrayEquals(expected.pixels, actual.pixels)
            val expectedInput = reference.preprocessBilinear(expected, 12)
            val actualInput = processor.preprocessBilinear(actual, 12)
            assertEquals(expectedInput.transform, actualInput.transform)
            assertArrayEquals(bits(expectedInput), bits(actualInput))
        }
    }

    @Test
    fun repeatedCapturesReuseTheirOwnerBuffersAndOverwriteEveryPixel() {
        val scratch = DetectorRgbScratch(YuvImagePreprocessor())
        val first = scratch.decode(frame(5, 3))
        val firstRotated = scratch.rotate(first, 1)
        val secondSource = frame(5, 3, 91)
        val second = scratch.decode(secondSource)
        val secondRotated = scratch.rotate(second, 3)
        assertSame(first.pixels, second.pixels)
        assertSame(firstRotated.pixels, secondRotated.pixels)
        assertNotSame(second.pixels, secondRotated.pixels)
        assertArrayEquals(secondSource.toArgb().pixels, second.pixels)
        assertArrayEquals(UprightCameraImage.rotate(secondSource.toArgb(), 3).pixels, secondRotated.pixels)
    }

    @Test
    fun separateDetectorOwnersNeverSharePixelStorage() {
        val a = DetectorRgbScratch(YuvImagePreprocessor())
        val b = DetectorRgbScratch(YuvImagePreprocessor())
        val source = frame(5, 3)
        val decodedA = a.decode(source)
        val decodedB = b.decode(source)
        assertNotSame(decodedA.pixels, decodedB.pixels)
        assertNotSame(a.rotate(decodedA, 1).pixels, b.rotate(decodedB, 1).pixels)
    }

    @Test
    fun ordinaryOwnedDecodeRemainsIndependentFromLaterScratchCaptures() {
        val source = frame(5, 3)
        val owned = source.toArgb()
        val expected = owned.pixels.copyOf()
        val scratch = DetectorRgbScratch(YuvImagePreprocessor())
        assertNotSame(owned.pixels, scratch.decode(source).pixels)
        scratch.rotate(scratch.decode(frame(5, 3, 99)), 2)
        assertArrayEquals(expected, owned.pixels)
    }

    @Test
    fun clearReleasesTheBorrowedBuffersBeforeTheNextCapture() {
        val scratch = DetectorRgbScratch(YuvImagePreprocessor())
        val source = frame(5, 3)
        val before = scratch.decode(source)
        val rotatedBefore = scratch.rotate(before, 1)
        scratch.clear()
        val after = scratch.decode(source)
        val rotatedAfter = scratch.rotate(after, 1)
        assertNotSame(before.pixels, after.pixels)
        assertNotSame(rotatedBefore.pixels, rotatedAfter.pixels)
        assertArrayEquals(before.pixels, after.pixels)
        assertArrayEquals(rotatedBefore.pixels, rotatedAfter.pixels)
    }

    @Test
    fun invalidDestinationAndRotationAreRejected() {
        val source = frame(5, 3)
        assertThrows(IllegalArgumentException::class.java) { source.toArgbInto(IntArray(14)) }
        val scratch = DetectorRgbScratch(YuvImagePreprocessor())
        assertThrows(IllegalArgumentException::class.java) { scratch.rotate(scratch.decode(source), 4) }
    }

    private fun frame(width: Int, height: Int, seed: Int = 0,
        crop: YuvCropRect = YuvCropRect.full(width, height)): OwnedYuvFrame {
        val yStride = width + 3
        val uvStride = ((width + 1) / 2) * 2 + 3
        val chromaRows = (height + 1) / 2
        return OwnedYuvFrame.copyOf(width, height,
            ByteBuffer.wrap(ByteArray(yStride * height) { (it * 31 + seed).toByte() }),
            ByteBuffer.wrap(ByteArray(uvStride * chromaRows) { (it * 7 + seed).toByte() }),
            ByteBuffer.wrap(ByteArray(uvStride * chromaRows) { (it * 11 + seed).toByte() }),
            yStride, 1, uvStride, 2, crop)
    }

    private fun bits(input: PreprocessedImage): IntArray {
        val values = input.inputBuffer.asFloatBuffer()
        return IntArray(values.remaining()) { values.get().toRawBits() }
    }
}

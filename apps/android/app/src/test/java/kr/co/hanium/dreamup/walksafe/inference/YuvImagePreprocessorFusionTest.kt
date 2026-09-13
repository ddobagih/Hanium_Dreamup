package kr.co.hanium.dreamup.walksafe.inference

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class YuvImagePreprocessorFusionTest {
    @Test
    fun neutralChromaHasKnownRgbFloatBitsAndPreservesPlaneState() {
        val source = Source(2, 2, plane(0, 1, 127, 255), plane(128), plane(128), 2, 1, 1, 1)
        val before = source.planeState()
        val result = source.fused(YuvImagePreprocessor(), 2)

        assertArrayEquals(intArrayOf(
            0, 0, 0,
            0x3b808081, 0x3b808081, 0x3b808081,
            0x3efefeff, 0x3efefeff, 0x3efefeff,
            0x3f800000, 0x3f800000, 0x3f800000,
        ), bits(result))
        assertTensorContract(result, 2)
        assertEquals(before, source.planeState())
        source.buffers.forEach { it.reset() } // Absolute reads preserve the caller's mark too.
        assertEquals(before, source.planeState())
    }

    @Test
    fun chromaStrideRoundingAndClampingMatchKnownColorFixtureThroughLetterboxing() {
        val source = Source(
            4, 3,
            plane(60, 61, 120, 121, 77, 77, 62, 63, 122, 123, 77, 77, 0, 255, 200, 201),
            plane(0, 77, 255, 77, 77, 77, 128, 77, 255),
            plane(255, 77, 0, 77, 77, 77, 128, 77, 255),
            6, 1, 6, 2,
        )
        val knownArgb = ArgbImage(4, 3, intArrayOf(
            0xffee0d00.toInt(), 0xffef0e00.toInt(), 0xff00a8ff.toInt(), 0xff00a9ff.toInt(),
            0xfff00f00.toInt(), 0xfff11000.toInt(), 0xff00aaff.toInt(), 0xff00abff.toInt(),
            0xff000000.toInt(), 0xffffffff.toInt(), 0xffff42ff.toInt(), 0xffff43ff.toInt(),
        ))
        val processor = YuvImagePreprocessor()
        for (modelSize in listOf(1, 2, 4, 5, 8)) {
            val expected = processor.preprocess(knownArgb, modelSize)
            val expectedBits = bits(expected)
            val actual = source.fused(processor, modelSize)
            assertArrayEquals("modelSize=$modelSize", expectedBits, bits(actual))
            assertEquals(expected.transform, actual.transform)
        }
    }

    @Test
    fun positiveHalfSamplingKeepsTheExistingEdgePaddingInsteadOfClamping() {
        val source = Source(1, 1, plane(255), plane(128), plane(128), 1, 1, 1, 1)
        val actual = bits(source.fused(YuvImagePreprocessor(), 2))

        assertArrayEquals(intArrayOf(
            0x3f800000, 0x3f800000, 0x3f800000,
            0x3ee4e4e5, 0x3ee4e4e5, 0x3ee4e4e5,
            0x3ee4e4e5, 0x3ee4e4e5, 0x3ee4e4e5,
            0x3ee4e4e5, 0x3ee4e4e5, 0x3ee4e4e5,
        ), actual)
    }

    @Test
    fun completeTensorBitsMatchReferenceForOddDimensionsAndPaddedPlaneLayouts() {
        val processor = YuvImagePreprocessor()
        for ((width, height) in listOf(3 to 2, 2 to 3, 5 to 3, 3 to 5, 7 to 5, 8 to 8)) {
            for (uvPixelStride in listOf(1, 2)) {
                for (yPixelStride in listOf(1, 2)) {
                    val source = patternedSource(width, height, yPixelStride, uvPixelStride)
                    val before = source.planeState()
                    val beforeBytes = source.buffers.map(::planeBytes)
                    for (modelSize in listOf(1, 2, 3, 4, 7, 12)) {
                        assertMatchesReference(source, processor, modelSize)
                    }
                    assertEquals(before, source.planeState())
                    source.buffers.forEachIndexed { index, buffer ->
                        assertArrayEquals(beforeBytes[index], planeBytes(buffer))
                    }
                }
            }
        }
    }

    @Test
    fun reusedRowsDoNotRetainPreviousFramePixelsDimensionsOrBufferCursor() {
        val processor = YuvImagePreprocessor()
        val source = patternedSource(3, 5, 1, 2)
        val first = source.fused(processor, 8)
        val originalBits = bits(first)
        // The caller may consume/change the borrowed tensor cursor before its next use.
        first.inputBuffer.limit(4)
        first.inputBuffer.position(4)
        for (buffer in source.buffers) {
            for (index in 0 until buffer.limit()) {
                buffer.put(index, (buffer.get(index).toInt() xor 0xff).toByte())
            }
        }
        val changed = assertMatchesReference(source, processor, 8)
        assertSame(first.inputBuffer, changed.inputBuffer)
        assertTrue(!originalBits.contentEquals(bits(changed)))
        assertTensorContract(changed, 8)

        // Same tensor size with different geometry and stride must discard all row content.
        assertMatchesReference(patternedSource(5, 3, 2, 1), processor, 8)
        assertMatchesReference(source, processor, 8)
    }

    @Test
    fun tensorOwnershipRemainsPerInstanceAndModelSizeAcrossStrategies() {
        val processor = YuvImagePreprocessor()
        val source = patternedSource(5, 3, 1, 2)
        val first = source.fused(processor, 8)
        val firstBits = bits(first)
        val otherSize = source.fused(processor, 4)
        assertNotSame(first.inputBuffer, otherSize.inputBuffer)
        assertArrayEquals(firstBits, bits(first))

        val reference = processor.preprocess(source.decode(), 8)
        assertSame(first.inputBuffer, reference.inputBuffer)
        assertArrayEquals(firstBits, bits(reference))
        val otherInstance = source.fused(YuvImagePreprocessor(), 8)
        assertNotSame(first.inputBuffer, otherInstance.inputBuffer)
        assertArrayEquals(firstBits, bits(otherInstance))
    }

    @Test
    fun downsamplingStillRejectsTruncatedUnselectedPixelsInEveryPlane() {
        for (planeIndex in 0..2) {
            val source = patternedSource(5, 3, 1, 2)
            val truncated = source.buffers[planeIndex]
            truncated.limit(truncated.limit() - 1)
            val before = source.planeState()

            assertThrows(IndexOutOfBoundsException::class.java) { source.decode() }
            assertThrows(IndexOutOfBoundsException::class.java) {
                // At size 1 the last source pixel is not selected, but the image is invalid.
                source.fused(YuvImagePreprocessor(), 1)
            }
            assertEquals(before, source.planeState())
        }
    }

    private fun assertMatchesReference(source: Source, processor: YuvImagePreprocessor, modelSize: Int): PreprocessedImage {
        val expected = processor.preprocess(source.decode(), modelSize)
        val expectedBits = bits(expected) // Copy before the same-size borrowed buffer is reused.
        val actual = source.fused(processor, modelSize)
        val description = "${source.width}x${source.height}, yStride=${source.yPixelStride}, uvStride=${source.uvPixelStride}, model=$modelSize"
        assertArrayEquals(description, expectedBits, bits(actual))
        assertEquals(description, expected.transform, actual.transform)
        assertTensorContract(actual, modelSize)
        return actual
    }

    private fun assertTensorContract(result: PreprocessedImage, size: Int) {
        assertTrue(result.inputBuffer.isDirect)
        assertEquals(ByteOrder.nativeOrder(), result.inputBuffer.order())
        assertEquals(0, result.inputBuffer.position())
        assertEquals(size * size * 3 * 4, result.inputBuffer.limit())
    }

    private fun patternedSource(width: Int, height: Int, yPixelStride: Int, uvPixelStride: Int): Source {
        val yRowStride = width * yPixelStride + 3
        val uvWidth = (width + 1) / 2
        val uvHeight = (height + 1) / 2
        val uvRowStride = uvWidth * uvPixelStride + 4
        fun patternedPlane(columns: Int, rows: Int, rowStride: Int, pixelStride: Int, seed: Int): ByteBuffer {
            val size = (rows - 1) * rowStride + (columns - 1) * pixelStride + 1
            val bytes = IntArray(size) { 77 }
            for (y in 0 until rows) {
                for (x in 0 until columns) {
                    bytes[y * rowStride + x * pixelStride] = (seed + x * 61 + y * 103) and 0xff
                }
            }
            return plane(*bytes)
        }
        return Source(
            width, height,
            patternedPlane(width, height, yRowStride, yPixelStride, 13),
            patternedPlane(uvWidth, uvHeight, uvRowStride, uvPixelStride, 0),
            patternedPlane(uvWidth, uvHeight, uvRowStride, uvPixelStride, 255),
            yRowStride, yPixelStride, uvRowStride, uvPixelStride,
        )
    }

    private fun plane(vararg bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes.size + 4).apply {
        put(ByteArray(bytes.size) { bytes[it].toByte() })
        limit(bytes.size) // No tail row padding, although backing capacity is larger.
        position(minOf(1, bytes.size))
        mark()
    }

    private fun bits(result: PreprocessedImage): IntArray {
        val view = result.inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        return IntArray(view.remaining() / 4) { view.float.toRawBits() }
    }

    private fun planeBytes(buffer: ByteBuffer): ByteArray = ByteArray(buffer.limit()) { buffer.get(it) }

    private data class Source(
        val width: Int,
        val height: Int,
        val y: ByteBuffer,
        val u: ByteBuffer,
        val v: ByteBuffer,
        val yRowStride: Int,
        val yPixelStride: Int,
        val uvRowStride: Int,
        val uvPixelStride: Int,
    ) {
        val buffers get() = listOf(y, u, v)

        fun planeState() = buffers.map { it.position() to it.limit() }

        fun decode() = ArgbImage(width, height, yuv420ToArgbPixels(
            width, height, y, u, v, yRowStride, yPixelStride, uvRowStride, uvPixelStride,
        ))

        fun fused(processor: YuvImagePreprocessor, modelSize: Int) = processor.preprocessYuv420(
            width, height, y, u, v, yRowStride, yPixelStride, uvRowStride, uvPixelStride, modelSize,
        )
    }
}

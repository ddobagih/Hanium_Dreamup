package kr.co.hanium.dreamup.walksafe.inference

import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class YuvImagePreprocessorTest {
    @Test
    fun paddingOnlyDetectionsAreRemovedBeforeTheAuthoritativeImageBatch() {
        val portrait = LetterboxTransform(480, 640, 768, 1.2f, 96f, 0f)
        val landscape = LetterboxTransform(640, 480, 768, 1.2f, 0f, 96f)
        val original = DetectionCandidate("person", .85f, RectNorm(.035f, .4f, .05f, .2f))
        assertNull(portrait.modelDetectionToImageDetection(original))
        assertNull(portrait.modelDetectionToImageDetection(original.copy(bboxNorm = RectNorm(.9f, .4f, .05f, .2f))))
        assertNull(landscape.modelDetectionToImageDetection(original.copy(bboxNorm = RectNorm(.4f, .035f, .2f, .05f))))
        assertNull(landscape.modelDetectionToImageDetection(original.copy(bboxNorm = RectNorm(.4f, .9f, .2f, .05f))))
        val partial = original.copy(bboxNorm = RectNorm(.1f, .4f, .1f, .2f))
        val admitted = requireNotNull(portrait.modelDetectionToImageDetection(partial))
        assertEquals(0f, admitted.bboxNorm.x, 0f)
        assertTrue(admitted.bboxNorm.width > 0f)
        assertEquals(partial.className, admitted.className)
        assertEquals(partial.detectionConfidence, admitted.detectionConfidence, 0f)
        assertNull(portrait.modelDetectionToImageDetection(original.copy(bboxNorm = RectNorm(.2f, .2f, 0f, .3f))))
        assertNull(portrait.modelDetectionToImageDetection(original.copy(bboxNorm = RectNorm(Float.NaN, .2f, .3f, .3f))))
    }

    @Test
    fun rgbChannelOrderAndNormalizationHaveTheSpecifiedFloatBits() {
        val result = YuvImagePreprocessor().preprocess(
            ArgbImage(2, 2, intArrayOf(
                0xff00017f.toInt(), 0xff80feff.toInt(),
                0xff7200ff.toInt(), 0xffff8001.toInt(),
            )),
            modelSize = 2,
        )

        assertArrayEquals(intArrayOf(
            0, 0x3b808081, 0x3efefeff,
            0x3f008081, 0x3f7efeff, 0x3f800000,
            0x3ee4e4e5, 0, 0x3f800000,
            0x3f800000, 0x3f008081, 0x3b808081,
        ), inputBits(result))
        assertTrue(result.inputBuffer.isDirect)
        assertEquals(ByteOrder.nativeOrder(), result.inputBuffer.order())
        assertEquals(0, result.inputBuffer.position())
        assertEquals(2 * 2 * 3 * 4, result.inputBuffer.limit())
    }

    @Test
    fun fractionalLandscapePaddingPreservesNearestSamplingAndNegativeRounding() {
        val result = YuvImagePreprocessor().preprocess(landscapeFixture(), modelSize = 4)

        // The first row maps (-0.5 / scale) to source row 0, while the last row is padding.
        assertPixels(result, intArrayOf(
            RED, GREEN, BLUE, BLUE,
            RED, GREEN, BLUE, BLUE,
            WHITE, BLACK, YELLOW, YELLOW,
            PAD, PAD, PAD, PAD,
        ))
        assertEquals(0x3faaaaab, result.transform.scale.toRawBits())
        assertEquals(0, result.transform.padX.toRawBits())
        assertEquals(0x3f000000, result.transform.padY.toRawBits())
    }

    @Test
    fun fractionalPortraitPaddingUsesTheOtherAxisWithTheSamePixelContract() {
        val result = YuvImagePreprocessor().preprocess(portraitFixture(), modelSize = 4)

        assertPixels(result, intArrayOf(
            RED, RED, GREEN, PAD,
            BLUE, BLUE, WHITE, PAD,
            BLACK, BLACK, YELLOW, PAD,
            BLACK, BLACK, YELLOW, PAD,
        ))
        assertEquals(0x3faaaaab, result.transform.scale.toRawBits())
        assertEquals(0x3f000000, result.transform.padX.toRawBits())
        assertEquals(0, result.transform.padY.toRawBits())
    }

    @Test
    fun downsamplingAndPositiveHalfRoundingKeepBoundaryPadding() {
        val processor = YuvImagePreprocessor()
        val reduced = processor.preprocess(landscapeFixture(), modelSize = 2)
        assertPixels(reduced, intArrayOf(PAD, PAD, WHITE, YELLOW))

        // Existing sampling rounds 0.5 to source index 1; do not silently clamp it to index 0.
        val enlarged = processor.preprocess(ArgbImage(1, 1, intArrayOf(RED)), modelSize = 2)
        assertPixels(enlarged, intArrayOf(RED, PAD, PAD, PAD))
    }

    @Test
    fun repeatedModelSizeDoesNotRetainPreviousSourceDimensionsOrPixels() {
        val processor = YuvImagePreprocessor()
        val first = processor.preprocess(landscapeFixture(), modelSize = 4)
        val firstBits = inputBits(first)
        val portrait = processor.preprocess(portraitFixture(), modelSize = 4)
        assertSame(first.inputBuffer, portrait.inputBuffer)
        assertPixels(portrait, intArrayOf(
            RED, RED, GREEN, PAD,
            BLUE, BLUE, WHITE, PAD,
            BLACK, BLACK, YELLOW, PAD,
            BLACK, BLACK, YELLOW, PAD,
        ))
        processor.preprocess(ArgbImage(1, 1, intArrayOf(BLUE)), modelSize = 2)
        val repeated = processor.preprocess(landscapeFixture(), modelSize = 4)
        assertSame(first.inputBuffer, repeated.inputBuffer)
        assertArrayEquals(firstBits, inputBits(repeated))
        assertEquals(0, repeated.inputBuffer.position())
    }

    @Test
    fun planarYuvWithRowPaddingAndOddHeightProducesKnownRoundedAndClampedColors() {
        val y = fixturePlane(60, 61, 120, 121, 77, 77, 62, 63, 122, 123, 77, 77, 0, 255, 200, 201)
        val u = fixturePlane(0, 255, 77, 77, 128, 255)
        val v = fixturePlane(255, 0, 77, 77, 128, 255)
        assertYuvFixture(y, u, v, uvRowStride = 4, uvPixelStride = 1)
    }

    @Test
    fun stridedChromaUsesAbsoluteOffsetsWithoutConsumingPositionsOrReadingTailPadding() {
        val y = fixturePlane(60, 61, 120, 121, 77, 77, 62, 63, 122, 123, 77, 77, 0, 255, 200, 201)
        val u = fixturePlane(0, 77, 255, 77, 77, 77, 128, 77, 255)
        val v = fixturePlane(255, 77, 0, 77, 77, 77, 128, 77, 255)
        assertYuvFixture(y, u, v, uvRowStride = 6, uvPixelStride = 2)
    }

    @Test
    fun pixelOutsidePlaneLimitIsNotReadFromTheLargerBackingCapacity() {
        val y = fixturePlane(100, 100, 100, 100)
        y.limit(3)
        val u = fixturePlane(128)
        val v = fixturePlane(128)

        assertThrows(IndexOutOfBoundsException::class.java) {
            yuv420ToArgbPixels(2, 2, y, u, v, 2, 1, 1, 1)
        }
        assertEquals(3, y.limit())
    }

    private fun assertYuvFixture(y: ByteBuffer, u: ByteBuffer, v: ByteBuffer, uvRowStride: Int, uvPixelStride: Int) {
        val positions = listOf(y.position(), u.position(), v.position())
        val limits = listOf(y.limit(), u.limit(), v.limit())
        val result = yuv420ToArgbPixels(4, 3, y, u, v, 6, 1, uvRowStride, uvPixelStride)

        // Each chroma sample covers a 2x2 block; the third row uses the second chroma row.
        assertArrayEquals(intArrayOf(
            0xffee0d00.toInt(), 0xffef0e00.toInt(), 0xff00a8ff.toInt(), 0xff00a9ff.toInt(),
            0xfff00f00.toInt(), 0xfff11000.toInt(), 0xff00aaff.toInt(), 0xff00abff.toInt(),
            BLACK, WHITE, 0xffff42ff.toInt(), 0xffff43ff.toInt(),
        ), result)
        assertEquals(positions, listOf(y.position(), u.position(), v.position()))
        assertEquals(limits, listOf(y.limit(), u.limit(), v.limit()))
    }

    private fun fixturePlane(vararg bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes.size + 4).apply {
        put(ByteArray(bytes.size) { bytes[it].toByte() })
        limit(bytes.size)
        position(minOf(1, bytes.size))
    }

    private fun landscapeFixture() = ArgbImage(3, 2, intArrayOf(RED, GREEN, BLUE, WHITE, BLACK, YELLOW))
    private fun portraitFixture() = ArgbImage(2, 3, intArrayOf(RED, GREEN, BLUE, WHITE, BLACK, YELLOW))

    private fun assertPixels(result: PreprocessedImage, pixels: IntArray) {
        val expected = pixels.flatMap { pixel ->
            listOf(16, 8, 0).map { shift ->
                when ((pixel ushr shift) and 0xff) {
                    0 -> 0
                    114 -> 0x3ee4e4e5
                    255 -> 0x3f800000
                    else -> error("Fixture needs an explicit normalization bit pattern")
                }
            }
        }.toIntArray()
        assertArrayEquals(expected, inputBits(result))
    }

    private fun inputBits(result: PreprocessedImage): IntArray {
        val buffer = result.inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        return IntArray(buffer.remaining() / 4) { buffer.float.toRawBits() }
    }

    private companion object {
        const val RED = -0x10000
        const val GREEN = -0xff0100
        const val BLUE = -0xffff01
        const val WHITE = -1
        const val BLACK = -0x1000000
        const val YELLOW = -0x100
        const val PAD = -0x8d8d8e
    }

    @Test
    fun modelRectToImageRectRemovesVerticalLetterboxPaddingForLandscapeImage() {
        val transform = LetterboxTransform(
            imageWidth = 1280,
            imageHeight = 720,
            modelSize = 640,
            scale = 0.5f,
            padX = 0f,
            padY = 140f,
        )
        val modelRect = RectNorm(
            x = 0.25f,
            y = 230f / 640f,
            width = 0.5f,
            height = 180f / 640f,
        )

        val imageRect = transform.modelRectToImageRect(modelRect)

        assertEquals(0.25f, imageRect.x, 0.001f)
        assertEquals(0.25f, imageRect.y, 0.001f)
        assertEquals(0.50f, imageRect.width, 0.001f)
        assertEquals(0.50f, imageRect.height, 0.001f)
    }

    @Test
    fun modelRectToImageRectRemovesHorizontalLetterboxPaddingForPortraitImage() {
        val transform = LetterboxTransform(
            imageWidth = 720,
            imageHeight = 1280,
            modelSize = 640,
            scale = 0.5f,
            padX = 140f,
            padY = 0f,
        )
        val modelRect = RectNorm(
            x = 230f / 640f,
            y = 0.25f,
            width = 180f / 640f,
            height = 0.5f,
        )

        val imageRect = transform.modelRectToImageRect(modelRect)

        assertEquals(0.25f, imageRect.x, 0.001f)
        assertEquals(0.25f, imageRect.y, 0.001f)
        assertEquals(0.50f, imageRect.width, 0.001f)
        assertEquals(0.50f, imageRect.height, 0.001f)
    }

    @Test
    fun modelRectToImageRectClampsBoxesThatOverlapLetterboxPadding() {
        val transform = LetterboxTransform(
            imageWidth = 1280,
            imageHeight = 720,
            modelSize = 640,
            scale = 0.5f,
            padX = 0f,
            padY = 140f,
        )
        val modelRect = RectNorm(
            x = 0.25f,
            y = 0.10f,
            width = 0.50f,
            height = 0.40f,
        )

        val imageRect = transform.modelRectToImageRect(modelRect)

        assertEquals(0.25f, imageRect.x, 0.001f)
        assertEquals(0.00f, imageRect.y, 0.001f)
        assertEquals(0.50f, imageRect.width, 0.001f)
        assertEquals(0.50f, imageRect.height, 0.001f)
    }
}

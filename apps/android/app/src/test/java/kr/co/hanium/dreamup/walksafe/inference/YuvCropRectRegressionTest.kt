package kr.co.hanium.dreamup.walksafe.inference

import kr.co.hanium.dreamup.walksafe.UprightCameraImage
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class YuvCropRectRegressionTest {
    private val crop = YuvCropRect(1, 1, 4, 3)

    @Test
    fun ownedCopyMasksOutsideOddOriginCropWithoutChangingSensorCoordinatesOrSourceState() {
        val source = source()
        val sourceState = source.planes.map { it.position() to it.limit() }
        val frame = source.owned(crop)
        assertEquals(sourceState, source.planes.map { it.position() to it.limit() })
        source.planes.forEach { it.reset() }
        assertEquals(sourceState, source.planes.map { it.position() to it.limit() })

        // A source Image can be closed or its buffers reused immediately after the copy.
        source.y.put(1 * source.yRowStride + source.yPixelStride, 0)
        source.u.put(0, 0)
        source.v.put(0, 0)
        val decoded = frame.toArgb()
        assertEquals(source.width, frame.width)
        assertEquals(source.height, frame.height)
        assertEquals(source.width, decoded.width)
        assertEquals(source.height, decoded.height)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            val expected = if (crop.contains(x, y)) expectedPixel(x, y) else PAD
            assertEquals("pixel ($x,$y)", expected, decoded.pixels[y * source.width + x])
        }

        // The existing full-frame rotation basis still maps crop content to sensor coordinates.
        val rotated = UprightCameraImage.rotate(decoded, 1)
        assertEquals(source.height, rotated.width)
        assertEquals(source.width, rotated.height)
        assertEquals(expectedPixel(1, 1), rotated.pixels[1 * rotated.width + (source.height - 2)])
        assertEquals(PAD, rotated.pixels[source.height - 1])
    }

    @Test
    fun fusedAndTwoPassSampleTheSameValidCropWithPaddedYAndChromaStrides() {
        val frame = source().owned(crop)
        val processor = YuvImagePreprocessor()
        for (modelSize in listOf(1, 2, 4, 5, 7, 12)) {
            val reference = processor.preprocess(frame.toArgb(), modelSize)
            val referenceBits = bits(reference)
            val fused = frame.preprocessFused(processor, modelSize)
            assertArrayEquals("modelSize=$modelSize", referenceBits, bits(fused))
            assertEquals(reference.transform, fused.transform)
            assertEquals(frame.width, fused.transform.imageWidth)
            assertEquals(frame.height, fused.transform.imageHeight)
        }
    }

    @Test
    fun planesMayEndAtTheLastValidCropPixelWithoutReadingOutsideIt() {
        val source = source()
        source.y.limit(2 * source.yRowStride + 3 * source.yPixelStride + 1)
        source.u.limit(source.uvRowStride + source.uvPixelStride + 1)
        source.v.limit(source.uvRowStride + source.uvPixelStride + 1)
        val state = source.planes.map { it.position() to it.limit() }
        val frame = source.owned(crop)
        assertEquals(expectedPixel(3, 2), frame.toArgb().pixels[2 * source.width + 3])
        val processor = YuvImagePreprocessor()
        val expected = bits(processor.preprocess(frame.toArgb(), 7))
        assertArrayEquals(expected, bits(frame.preprocessFused(processor, 7)))
        assertEquals(state, source.planes.map { it.position() to it.limit() })

        assertThrows(IndexOutOfBoundsException::class.java) {
            processor.preprocessYuv420(
                source.width, source.height, source.y, source.u, source.v,
                source.yRowStride, source.yPixelStride, source.uvRowStride, source.uvPixelStride, 1,
            )
        }
    }

    @Test
    fun modelBoxesOutsideCropAreDroppedAndCrossingBoxesAreClippedAfterRotation() {
        val frame = source().owned(crop)
        val outside = RectNorm(0f, .3f, .1f, .2f)
        val edgeOnly = RectNorm(.1f, .3f, .1f, .2f)
        val crossing = RectNorm(.1f, .1f, .5f, .5f)
        val expected = RectNorm(.2f, .25f, .4f, .35f)
        val inside = RectNorm(.3f, .3f, .2f, .2f)

        for (turns in 0..3) {
            val orientedWidth = if (turns % 2 == 0) frame.width else frame.height
            val orientedHeight = if (turns % 2 == 0) frame.height else frame.width
            val transform = YuvImagePreprocessor().preprocessBilinear(
                ArgbImage(orientedWidth, orientedHeight, IntArray(orientedWidth * orientedHeight) { PAD }),
                20,
            ).transform
            fun finalDetection(sensorRect: RectNorm): DetectionCandidate? {
                val orientedRect = UprightCameraImage.toSensor(sensorRect, (4 - turns) % 4)
                val modelRect = RectNorm(
                    (orientedRect.x * orientedWidth * transform.scale + transform.padX) / transform.modelSize,
                    (orientedRect.y * orientedHeight * transform.scale + transform.padY) / transform.modelSize,
                    orientedRect.width * orientedWidth * transform.scale / transform.modelSize,
                    orientedRect.height * orientedHeight * transform.scale / transform.modelSize,
                )
                val mapped = requireNotNull(transform.modelDetectionToImageDetection(
                    DetectionCandidate("obstacle", .9f, modelRect),
                ))
                return frame.cropRect.toValidSensorDetection(mapped, frame.width, frame.height, turns)
            }

            assertNull("outside crop, turns=$turns", finalDetection(outside))
            assertNull("touching crop edge, turns=$turns", finalDetection(edgeOnly))
            assertRect(expected, requireNotNull(finalDetection(crossing)).bboxNorm)
            assertRect(inside, requireNotNull(finalDetection(inside)).bboxNorm)
        }
    }

    @Test
    fun fullImageDetectionGeometryIsUnchangedByCropFilter() {
        val frame = source().owned(YuvCropRect.full(5, 4))
        val sensorRect = RectNorm(.1f, .2f, .3f, .4f)
        for (turns in 0..3) {
            val oriented = UprightCameraImage.toSensor(sensorRect, (4 - turns) % 4)
            val detection = DetectionCandidate("obstacle", .9f, oriented)
            val mapped = requireNotNull(frame.cropRect.toValidSensorDetection(
                detection, frame.width, frame.height, turns,
            ))
            assertRect(sensorRect, mapped.bboxNorm)
        }
    }

    private fun assertRect(expected: RectNorm, actual: RectNorm) {
        assertEquals(expected.x, actual.x, .00001f)
        assertEquals(expected.y, actual.y, .00001f)
        assertEquals(expected.width, actual.width, .00001f)
        assertEquals(expected.height, actual.height, .00001f)
    }

    private fun expectedPixel(x: Int, y: Int): Int {
        val luma = 20 + 17 * x + 31 * y
        val u = 70 + 23 * (x / 2) + 29 * (y / 2) - 128
        val v = 200 - 37 * (x / 2) - 45 * (y / 2) - 128
        val red = (luma + 1.402f * v).roundToInt().coerceIn(0, 255)
        val green = (luma - 0.344136f * u - 0.714136f * v).roundToInt().coerceIn(0, 255)
        val blue = (luma + 1.772f * u).roundToInt().coerceIn(0, 255)
        return (0xff shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun bits(result: PreprocessedImage): IntArray {
        val buffer = result.inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        return IntArray(buffer.remaining() / 4) { buffer.float.toRawBits() }
    }

    private fun source(): Source {
        fun plane(columns: Int, rows: Int, rowStride: Int, pixelStride: Int, value: (Int, Int) -> Int): ByteBuffer {
            val limit = (rows - 1) * rowStride + (columns - 1) * pixelStride + 1
            return ByteBuffer.allocateDirect(limit + 4).apply {
                for (index in 0 until limit) put(index, 77)
                for (y in 0 until rows) for (x in 0 until columns) {
                    put(y * rowStride + x * pixelStride, value(x, y).toByte())
                }
                limit(limit)
                position(1)
                mark()
            }
        }
        return Source(
            y = plane(5, 4, 12, 2) { x, y -> 20 + 17 * x + 31 * y },
            u = plane(3, 2, 8, 2) { x, y -> 70 + 23 * x + 29 * y },
            v = plane(3, 2, 8, 2) { x, y -> 200 - 37 * x - 45 * y },
        )
    }

    private data class Source(val y: ByteBuffer, val u: ByteBuffer, val v: ByteBuffer) {
        val width = 5
        val height = 4
        val yRowStride = 12
        val yPixelStride = 2
        val uvRowStride = 8
        val uvPixelStride = 2
        val planes get() = listOf(y, u, v)

        fun owned(cropRect: YuvCropRect): OwnedYuvFrame = OwnedYuvFrame.copyOf(
            width, height, y, u, v, yRowStride, yPixelStride, uvRowStride, uvPixelStride, cropRect,
        )
    }

    private companion object {
        const val PAD = -0x8d8d8e
    }
}

package kr.co.hanium.dreamup.walksafe.inference

import android.hardware.DataSpace
import android.media.DeclaredYuvTestImage
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kr.co.hanium.dreamup.walksafe.UprightCameraImage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(DeclaredYuvTestImage.IsolatedSdkRunner::class)
class YuvDeclaredColorRegressionTest {
    @Test
    fun declaredLimitedBlackIsBlackThroughPublicImageAndOwnedCopy() = atSdk(33) {
        val source = image(DataSpace.DATASPACE_BT709, 16, 128, 128)
        assertEquals(BLACK, YuvImagePreprocessor().decode(source).pixels[0])
        assertEquals(BLACK, OwnedYuvFrame.copyOf(source).toArgb().pixels[0])
        assertEquals(2, source.dataSpaceReads)
    }

    @Test
    fun adjusted601And709FullAndLimitedFollowDeclaredGrayAndChroma() = atSdk(33) {
        for (standard in listOf(DataSpace.STANDARD_BT601_525, DataSpace.STANDARD_BT601_625,
            DataSpace.STANDARD_BT709)) for (range in listOf(DataSpace.RANGE_FULL, DataSpace.RANGE_LIMITED)) {
            for ((y, u, v) in listOf(Triple(16, 128, 128), Triple(235, 128, 128),
                Triple(126, 128, 128), Triple(100, 90, 240), Triple(0, 16, 240), Triple(255, 240, 16))) {
                val source = image(standard or DataSpace.TRANSFER_SMPTE_170M or range, y, u, v)
                val expected = reference(y, u, v, standard == DataSpace.STANDARD_BT709,
                    range == DataSpace.RANGE_LIMITED)
                assertConsumers(source, IntArray(4) { expected }, 2)
            }
        }
    }

    @Test
    fun unknownUnsupportedAndUnspecifiedKeepLegacyAdjustedFull601() = atSdk(33) {
        val declarations = listOf(DataSpace.DATASPACE_UNKNOWN,
            DataSpace.STANDARD_BT2020 or DataSpace.RANGE_LIMITED,
            DataSpace.STANDARD_BT601_525_UNADJUSTED or DataSpace.RANGE_LIMITED,
            DataSpace.STANDARD_BT601_625_UNADJUSTED or DataSpace.RANGE_FULL,
            DataSpace.STANDARD_BT709 or DataSpace.RANGE_UNSPECIFIED,
            DataSpace.STANDARD_BT709 or DataSpace.RANGE_EXTENDED,
            DataSpace.RANGE_LIMITED)
        for (declaration in declarations) {
            assertConsumers(image(declaration, 100, 90, 240), IntArray(4) { 0xffff2121.toInt() }, 2)
        }
    }

    @Test
    fun api26And32NeverReadNewImageMetadataAndRetainLegacyBytes() {
        val before = Build.VERSION.SDK_INT
        for (sdk in listOf(26, 32)) atSdk(sdk) {
            val source = image(DataSpace.DATASPACE_BT709, 16, 128, 128)
            source.rejectDataSpaceRead = true
            assertConsumers(source, IntArray(4) { 0xff101010.toInt() }, 2)
            assertEquals(0, source.dataSpaceReads)
        }
        assertEquals(before, Build.VERSION.SDK_INT)
    }

    @Test
    fun ownedPixelsAndDeclarationSurviveSourceMutationCloseAndSdkChange() {
        lateinit var owned: OwnedYuvFrame
        atSdk(33) {
            val source = image(DataSpace.DATASPACE_BT709, 100, 90, 240)
            owned = OwnedYuvFrame.copyOf(source)
            assertEquals(1, source.dataSpaceReads)
            source.planes.forEach { plane ->
                val buffer = plane.buffer
                for (index in 0 until buffer.limit()) buffer.put(index, 0)
            }
            source.dataSpace = DataSpace.DATASPACE_UNKNOWN
            source.close()
        }
        atSdk(26) {
            val expected = IntArray(4) { reference(100, 90, 240, true, true) }
            assertArrayEquals(expected, owned.toArgb().pixels)
            assertArrayEquals(expected, owned.toArgbInto(IntArray(4)).pixels)
            val scratch = DetectorRgbScratch(YuvImagePreprocessor())
            assertArrayEquals(expected, scratch.decode(owned).pixels)
            assertArrayEquals(bits(YuvImagePreprocessor().preprocess(ArgbImage(2, 2, expected), 2)),
                bits(owned.preprocessFused(YuvImagePreprocessor(), 2)))
        }
    }

    @Test
    fun oddCropStridedBuffersKeepCoordinatesPositionsMarksAndTensorSampling() = atSdk(33) {
        val width = 5
        val height = 4
        val y = plane(5, 4, 12, 2) { x, row -> 20 + 17 * x + 31 * row }
        val u = plane(3, 2, 8, 2) { x, row -> 70 + 23 * x + 29 * row }
        val v = plane(3, 2, 8, 2) { x, row -> 200 - 37 * x - 45 * row }
        // Buffers may end at the last valid crop sample.
        y.limit(2 * 12 + 3 * 2 + 1)
        u.limit(8 + 2 + 1)
        v.limit(8 + 2 + 1)
        val source = DeclaredYuvTestImage(width, height, DataSpace.DATASPACE_BT709,
            intArrayOf(1, 1, 4, 3), y, u, v, 12, 2, 8, 2)
        val expected = IntArray(width * height) { index ->
            val x = index % width
            val row = index / width
            if (x in 1..3 && row in 1..2) reference(20 + 17 * x + 31 * row,
                70 + 23 * (x / 2) + 29 * (row / 2),
                200 - 37 * (x / 2) - 45 * (row / 2), true, true) else PAD
        }
        for (size in listOf(1, 2, 4, 7, 12)) assertConsumers(source, expected, size)
        for (buffer in listOf(y, u, v)) {
            assertEquals(1, buffer.position())
            buffer.reset()
            assertEquals(1, buffer.position())
        }
        assertEquals(31, y.limit())
        assertEquals(11, u.limit())
        assertEquals(11, v.limit())
    }

    @Test
    fun consecutiveDeclarationsDoNotLeakThroughScratchOrReusableTensor() = atSdk(33) {
        val processor = YuvImagePreprocessor()
        val scratch = DetectorRgbScratch(processor)
        for (declaration in listOf(DataSpace.DATASPACE_BT709, DataSpace.DATASPACE_UNKNOWN,
            DataSpace.DATASPACE_BT601_525, DataSpace.STANDARD_BT709 or DataSpace.RANGE_FULL)) {
            val source = image(declaration, 100, 90, 240)
            val expected = when (declaration) {
                DataSpace.DATASPACE_BT709 -> reference(100, 90, 240, true, true)
                DataSpace.DATASPACE_BT601_525 -> reference(100, 90, 240, false, true)
                DataSpace.DATASPACE_UNKNOWN -> 0xffff2121.toInt()
                else -> reference(100, 90, 240, true, false)
            }
            assertArrayEquals(IntArray(4) { expected }, scratch.decode(source).pixels)
            assertArrayEquals(IntArray(4) { expected }, scratch.decode(OwnedYuvFrame.copyOf(source)).pixels)
            assertArrayEquals(bits(YuvImagePreprocessor().preprocess(ArgbImage(2, 2, IntArray(4) { expected }), 2)),
                bits(processor.preprocess(source, 2, YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR)))
        }
    }

    @Test
    fun metadataRuntimeFailureFallsBackWithoutHidingPlaneFailuresOrErrors() = atSdk(33) {
        val source = image(DataSpace.DATASPACE_BT709, 100, 90, 240)
        source.metadataOnlyFailure = true
        val expected = IntArray(4) { 0xffff2121.toInt() }
        assertConsumers(source, expected, 2)
        assertEquals(6, source.dataSpaceReads)
        val owned = OwnedYuvFrame.copyOf(source)
        assertEquals(7, source.dataSpaceReads)
        source.close()
        assertArrayEquals(expected, owned.toArgb().pixels)

        val invalid = image(DataSpace.DATASPACE_BT709, 100, 90, 240)
        invalid.metadataOnlyFailure = true
        invalid.planes[0].buffer.limit(0)
        val invalidOwned = OwnedYuvFrame.copyOf(invalid)
        assertEquals(1, invalid.dataSpaceReads)
        val processor = YuvImagePreprocessor()
        val scratch = DetectorRgbScratch(processor)
        assertThrows(IndexOutOfBoundsException::class.java) { processor.decode(invalid) }
        assertThrows(IndexOutOfBoundsException::class.java) { processor.decodeInto(invalid, IntArray(4)) }
        assertThrows(IndexOutOfBoundsException::class.java) { processor.preprocess(invalid, 2) }
        assertThrows(IndexOutOfBoundsException::class.java) {
            processor.preprocess(invalid, 2, YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR)
        }
        assertThrows(IndexOutOfBoundsException::class.java) { scratch.decode(invalid) }
        assertThrows(IndexOutOfBoundsException::class.java) { invalidOwned.toArgb() }
        assertThrows(IndexOutOfBoundsException::class.java) { invalidOwned.toArgbInto(IntArray(4)) }
        assertThrows(IndexOutOfBoundsException::class.java) { invalidOwned.preprocessFused(processor, 2) }
        assertThrows(IndexOutOfBoundsException::class.java) { scratch.decode(invalidOwned) }
        assertEquals(6, invalid.dataSpaceReads)

        val fatal = image(DataSpace.DATASPACE_BT709, 100, 90, 240)
        fatal.rejectDataSpaceRead = true
        assertSame(fatal.dataSpaceReadError, assertThrows(AssertionError::class.java) { processor.decode(fatal) })
        assertSame(fatal.dataSpaceReadError, assertThrows(AssertionError::class.java) { OwnedYuvFrame.copyOf(fatal) })
        assertSame(fatal.dataSpaceReadError, assertThrows(AssertionError::class.java) {
            processor.preprocess(fatal, 2, YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR)
        })
        assertSame(fatal.dataSpaceReadError, assertThrows(AssertionError::class.java) { scratch.decode(fatal) })
        assertEquals(4, fatal.dataSpaceReads)
        val closed = image(DataSpace.DATASPACE_BT709, 100, 90, 240)
        closed.close()
        assertThrows(IllegalStateException::class.java) { processor.decode(closed) }
        assertThrows(IllegalStateException::class.java) { OwnedYuvFrame.copyOf(closed) }
        assertThrows(IllegalStateException::class.java) {
            processor.preprocess(closed, 2, YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR)
        }
        assertThrows(IllegalStateException::class.java) { scratch.decode(closed) }
    }

    private fun assertConsumers(source: DeclaredYuvTestImage, expected: IntArray, size: Int) {
        val processor = YuvImagePreprocessor()
        assertArrayEquals(expected, processor.decode(source).pixels)
        assertArrayEquals(expected, processor.decodeInto(source, IntArray(expected.size)).pixels)
        val owned = OwnedYuvFrame.copyOf(source)
        assertArrayEquals(expected, owned.toArgb().pixels)
        assertArrayEquals(expected, owned.toArgbInto(IntArray(expected.size)).pixels)
        val scratch = DetectorRgbScratch(processor)
        assertArrayEquals(expected, scratch.decode(source).pixels)
        assertArrayEquals(expected, scratch.decode(owned).pixels)
        for (turns in 0..3) {
            val oriented = UprightCameraImage.rotate(ArgbImage(source.width, source.height, expected), turns)
            val actual = scratch.rotate(scratch.decode(owned), turns)
            assertArrayEquals(oriented.pixels, actual.pixels)
            assertArrayEquals(bits(YuvImagePreprocessor().preprocessBilinear(oriented, size)),
                bits(processor.preprocessBilinear(actual, size)))
        }
        val expectedInput = bits(YuvImagePreprocessor().preprocess(ArgbImage(source.width, source.height, expected), size))
        assertArrayEquals(expectedInput, bits(processor.preprocess(source, size)))
        assertArrayEquals(expectedInput, bits(processor.preprocess(source, size, YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR)))
        assertArrayEquals(expectedInput, bits(owned.preprocessFused(processor, size)))
    }

    private fun image(dataSpace: Int, y: Int, u: Int, v: Int) = DeclaredYuvTestImage(2, 2,
        dataSpace, intArrayOf(0, 0, 2, 2), ByteBuffer.wrap(ByteArray(4) { y.toByte() }),
        ByteBuffer.wrap(byteArrayOf(u.toByte())), ByteBuffer.wrap(byteArrayOf(v.toByte())), 2, 1, 1, 1)

    private fun plane(columns: Int, rows: Int, rowStride: Int, pixelStride: Int,
        value: (Int, Int) -> Int): ByteBuffer = ByteBuffer.allocateDirect(rowStride * rows).apply {
        for (row in 0 until rows) for (x in 0 until columns) {
            put(row * rowStride + x * pixelStride, value(x, row).toByte())
        }
        position(1)
        mark()
    }

    // Independent double-precision reference from Android's published Kr/Kb and 8-bit ranges.
    private fun reference(y: Int, u: Int, v: Int, bt709: Boolean, limited: Boolean): Int {
        val kr = if (bt709) .2126 else .299
        val kb = if (bt709) .0722 else .114
        val luma = if (limited) (y - 16) * 255.0 / 219 else y.toDouble()
        val cb = (u - 128) * if (limited) 255.0 / 224 else 1.0
        val cr = (v - 128) * if (limited) 255.0 / 224 else 1.0
        val r = (luma + 2 * (1 - kr) * cr).roundToInt().coerceIn(0, 255)
        val g = (luma - 2 * kb * (1 - kb) / (1 - kr - kb) * cb -
            2 * kr * (1 - kr) / (1 - kr - kb) * cr).roundToInt().coerceIn(0, 255)
        val b = (luma + 2 * (1 - kb) * cb).roundToInt().coerceIn(0, 255)
        return (0xff shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun bits(result: PreprocessedImage): IntArray {
        val buffer = result.inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        return IntArray(buffer.remaining() / 4) { buffer.float.toRawBits() }
    }

    private fun atSdk(sdk: Int, action: () -> Unit) = DeclaredYuvTestImage.atSdk(sdk, Runnable { action() })

    private companion object {
        const val BLACK = -0x1000000
        const val PAD = -0x8d8d8e
    }
}

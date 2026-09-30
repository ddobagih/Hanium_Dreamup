package kr.co.hanium.dreamup.walksafe.inference

import kr.co.hanium.dreamup.walksafe.UprightCameraImage
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * S10-33 (originals/10.txt:327): the fixed ARGB calibration never exercises the camera path. This
 * chains the production pieces used by the unified detector for a camera frame, without a model:
 * YUV_420_888 planes -> ARGB decode -> upright rotation -> bilinear letterbox tensor -> (a box read
 * from the tensor) -> letterbox inverse -> sensor coordinates. Odd sizes, both scale directions,
 * all four quarter turns and an object touching the image corner are covered. It is not a camera,
 * colour-space or model-accuracy check.
 */
class YuvRotationCoordinateRoundTripTest {
    private data class Case(val width: Int, val height: Int, val left: Int, val top: Int, val right: Int, val bottom: Int)

    private val cases = listOf(
        Case(641, 479, 100, 50, 221, 140),   // upscale (768/641), interior object
        Case(641, 479, 0, 0, 41, 31),        // object touching the sensor corner
        Case(1281, 721, 900, 400, 1101, 721), // downscale, object touching the bottom edge
    )

    @Test fun sensorBoxSurvivesYuvDecodeRotationLetterboxAndInverseForAllQuarterTurns() {
        val preprocessor = YuvImagePreprocessor()
        for (case in cases) for (turns in 0..3) {
            val (y, u, v) = planes(case)
            val argb = ArgbImage(case.width, case.height, yuv420ToArgbPixels(
                case.width, case.height, y, u, v,
                yRowStride = case.width + 3, yPixelStride = 1,
                uvRowStride = (case.width + 1) / 2 + 5, uvPixelStride = 1,
            ))
            val upright = UprightCameraImage.rotate(argb, turns)
            val input = preprocessor.preprocessBilinear(upright, 768)
            val modelBox = requireNotNull(brightBox(input.inputBuffer, 768)) { "case=$case turns=$turns" }
            val imageBox = input.transform.modelDetectionToImageDetection(DetectionCandidate("probe", .9f, modelBox))
            val sensor = YuvCropRect.full(case.width, case.height)
                .toValidSensorDetection(requireNotNull(imageBox), case.width, case.height, turns)
            val box = requireNotNull(sensor).bboxNorm
            // Bilinear edges and integer letterbox rounding allow about two source pixels per edge.
            val tolX = 2.5f / case.width
            val tolY = 2.5f / case.height
            val label = "case=$case turns=$turns box=$box"
            assertEquals(label, case.left.toFloat() / case.width, box.x, tolX)
            assertEquals(label, case.top.toFloat() / case.height, box.y, tolY)
            assertEquals(label, case.right.toFloat() / case.width, box.x + box.width, tolX)
            assertEquals(label, case.bottom.toFloat() / case.height, box.y + box.height, tolY)
        }
    }

    /** Y=235 inside the object, Y=16 elsewhere; neutral chroma. Row strides include padding. */
    private fun planes(case: Case): Triple<ByteBuffer, ByteBuffer, ByteBuffer> {
        val yStride = case.width + 3
        val uvWidth = (case.width + 1) / 2
        val uvHeight = (case.height + 1) / 2
        val uvStride = uvWidth + 5
        val y = ByteArray(yStride * case.height)
        for (row in 0 until case.height) for (col in 0 until case.width) {
            val inside = col >= case.left && col < case.right && row >= case.top && row < case.bottom
            y[row * yStride + col] = (if (inside) 235 else 16).toByte()
        }
        val u = ByteArray(uvStride * uvHeight) { 128.toByte() }
        val v = ByteArray(uvStride * uvHeight) { 128.toByte() }
        return Triple(ByteBuffer.wrap(y), ByteBuffer.wrap(u), ByteBuffer.wrap(v))
    }

    /** Bounding box, in normalized model coordinates, of tensor pixels brighter than mid-grey. */
    private fun brightBox(tensor: ByteBuffer, size: Int): RectNorm? {
        val floats = tensor.duplicate().order(ByteOrder.nativeOrder()).apply { rewind() }.asFloatBuffer()
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
        for (row in 0 until size) for (col in 0 until size) {
            // Letterbox padding is 114/255 = 0.447 and the dark background is 16/255; the object is 235/255.
            if (floats.get((row * size + col) * 3) > 0.62f) {
                minX = minOf(minX, col); maxX = maxOf(maxX, col)
                minY = minOf(minY, row); maxY = maxOf(maxY, row)
            }
        }
        if (maxX < 0) return null
        return RectNorm(minX.toFloat() / size, minY.toFloat() / size,
            (maxX + 1 - minX).toFloat() / size, (maxY + 1 - minY).toFloat() / size)
    }
}

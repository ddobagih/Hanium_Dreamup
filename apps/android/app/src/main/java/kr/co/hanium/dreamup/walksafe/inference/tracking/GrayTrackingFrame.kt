package kr.co.hanium.dreamup.walksafe.inference.tracking

import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/** Identity of the unrotated CPU image. Epoch and geometryVersion increase monotonically. */
data class VisualFrameKey(
    val epoch: Long,
    val frameId: Long,
    val cameraTimestampNs: Long,
    val capturedAtElapsedRealtimeMs: Long,
    val geometryVersion: Long,
)

/** Owns a small immutable luma copy; never retains an Android Image or its ByteBuffer. */
class GrayTrackingFrame private constructor(
    val key: VisualFrameKey,
    val width: Int,
    val height: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    private val pixels: ByteArray,
) {
    // Only the diagnostic patch backend reads patchMean; native LK needs the luma copy alone.
    private val integralStorage = lazy {
        val result = IntArray((width + 1) * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0
            for (x in 0 until width) {
                rowSum += intensity(x, y)
                result[(y + 1) * (width + 1) + x + 1] = result[y * (width + 1) + x + 1] + rowSum
            }
        }
        result
    }
    private val integral by integralStorage

    val retainedBytes: Int get() = pixels.size +
        if (integralStorage.isInitialized()) integral.size * Int.SIZE_BYTES else 0
    internal fun copyPixels(): ByteArray = pixels.copyOf()

    internal fun intensity(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xff

    internal fun patchMean(x: Int, y: Int, radius: Int): Float {
        val stride = width + 1
        val left = x - radius
        val top = y - radius
        val right = x + radius + 1
        val bottom = y + radius + 1
        val sum = integral[bottom * stride + right] - integral[top * stride + right] -
            integral[bottom * stride + left] + integral[top * stride + left]
        val side = radius * 2 + 1
        return sum.toFloat() / (side * side)
    }

    internal fun samePixels(other: GrayTrackingFrame): Boolean = width == other.width &&
        height == other.height && sourceWidth == other.sourceWidth && sourceHeight == other.sourceHeight &&
        pixels.contentEquals(other.pixels)

    companion object {
        const val MAX_EDGE = 192

        fun copyOf(
            key: VisualFrameKey,
            width: Int,
            height: Int,
            pixels: ByteArray,
            sourceWidth: Int = width,
            sourceHeight: Int = height,
        ): GrayTrackingFrame {
            require(width in 1..MAX_EDGE && height in 1..MAX_EDGE)
            require(sourceWidth > 0 && sourceHeight > 0)
            require(pixels.size == width * height)
            require(key.frameId >= 0L && key.cameraTimestampNs > 0L && key.capturedAtElapsedRealtimeMs >= 0L)
            return GrayTrackingFrame(key, width, height, sourceWidth, sourceHeight, pixels.copyOf())
        }

        /** Four samples per output cell limit copying work even for a large camera image. */
        fun copyFromLuma(
            key: VisualFrameKey,
            imageWidth: Int,
            imageHeight: Int,
            luma: ByteBuffer,
            rowStride: Int,
            pixelStride: Int,
            maxEdge: Int = MAX_EDGE,
        ): GrayTrackingFrame {
            require(imageWidth > 0 && imageHeight > 0 && maxEdge in 16..MAX_EDGE)
            require(rowStride > 0 && pixelStride > 0)
            require(rowStride.toLong() >= (imageWidth - 1L) * pixelStride + 1L)
            val data = luma.duplicate()
            val base = data.position()
            val last = base.toLong() + (imageHeight - 1L) * rowStride + (imageWidth - 1L) * pixelStride
            require(last < data.limit().toLong()) { "Luma buffer does not cover the declared image" }
            val scale = minOf(1f, maxEdge.toFloat() / max(imageWidth, imageHeight))
            val width = max(1, (imageWidth * scale).roundToInt())
            val height = max(1, (imageHeight * scale).roundToInt())
            val result = ByteArray(width * height)
            val xSampleOffsets = IntArray(width * 2)
            for (x in 0 until width) for (sampleX in 0..1) {
                val sx = ((x + 0.25f + sampleX * 0.5f) * imageWidth / width).toInt()
                    .coerceAtMost(imageWidth - 1)
                xSampleOffsets[x * 2 + sampleX] = sx * pixelStride
            }
            for (y in 0 until height) {
                val sy0 = ((y + 0.25f + 0 * 0.5f) * imageHeight / height).toInt()
                    .coerceAtMost(imageHeight - 1)
                val sy1 = ((y + 0.25f + 1 * 0.5f) * imageHeight / height).toInt()
                    .coerceAtMost(imageHeight - 1)
                val row0 = base + sy0 * rowStride
                val row1 = base + sy1 * rowStride
                for (x in 0 until width) {
                    val x0 = xSampleOffsets[x * 2]
                    val x1 = xSampleOffsets[x * 2 + 1]
                    val sum = (data.get(row0 + x0).toInt() and 0xff) +
                        (data.get(row0 + x1).toInt() and 0xff) +
                        (data.get(row1 + x0).toInt() and 0xff) +
                        (data.get(row1 + x1).toInt() and 0xff)
                    result[y * width + x] = ((sum + 2) / 4).toByte()
                }
            }
            return copyOf(key, width, height, result, imageWidth, imageHeight)
        }
    }
}

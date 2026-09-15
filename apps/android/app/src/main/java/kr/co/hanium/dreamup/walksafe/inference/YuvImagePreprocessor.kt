package kr.co.hanium.dreamup.walksafe.inference

import android.media.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class PreprocessedImage(
    val inputBuffer: ByteBuffer,
    val transform: LetterboxTransform,
)

enum class YuvPreprocessingStrategy {
    LEGACY_TWO_PASS,
    FUSED_YUV_TO_TENSOR,
}

data class ArgbImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
)

data class LetterboxTransform(
    val imageWidth: Int,
    val imageHeight: Int,
    val modelSize: Int,
    val scale: Float,
    val padX: Float,
    val padY: Float,
    val resizedWidth: Int = (imageWidth * scale).roundToInt(),
    val resizedHeight: Int = (imageHeight * scale).roundToInt(),
) {
    /** Test before NMS in model pixels, without clipping the box used for suppression. */
    internal fun intersectsImageContent(modelRect: kr.co.hanium.dreamup.walksafe.depth.RectNorm): Boolean {
        val left = maxOf(modelRect.x * modelSize, padX, 0f)
        val top = maxOf(modelRect.y * modelSize, padY, 0f)
        // Integer resize rounding and inverse scaling can end at different subpixel positions.
        val right = minOf((modelRect.x + modelRect.width) * modelSize,
            padX + minOf(resizedWidth.toFloat(), imageWidth * scale), modelSize.toFloat())
        val bottom = minOf((modelRect.y + modelRect.height) * modelSize,
            padY + minOf(resizedHeight.toFloat(), imageHeight * scale), modelSize.toFloat())
        // Comparing before division avoids a padding-edge contact becoming a tiny visible box
        // through floating-point round-off when undoing the scale.
        return left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            right > left && bottom > top
    }

    /** Drop padding-only and degenerate predictions before they become detector evidence. */
    fun modelDetectionToImageDetection(
        candidate: kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate,
    ): kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate? {
        if (!intersectsImageContent(candidate.bboxNorm)) return null
        val rect = modelRectToImageRect(candidate.bboxNorm)
        if (!rect.x.isFinite() || !rect.y.isFinite() || !rect.width.isFinite() || !rect.height.isFinite() ||
            rect.width <= 0f || rect.height <= 0f) return null
        return candidate.copy(bboxNorm = rect)
    }

    fun modelRectToImageRect(modelRect: kr.co.hanium.dreamup.walksafe.depth.RectNorm): kr.co.hanium.dreamup.walksafe.depth.RectNorm {
        val leftPx = modelRect.x * modelSize
        val topPx = modelRect.y * modelSize
        val rightPx = (modelRect.x + modelRect.width) * modelSize
        val bottomPx = (modelRect.y + modelRect.height) * modelSize
        val imageLeft = ((leftPx - padX) / scale).coerceIn(0f, imageWidth.toFloat())
        val imageTop = ((topPx - padY) / scale).coerceIn(0f, imageHeight.toFloat())
        val imageRight = ((rightPx - padX) / scale).coerceIn(0f, imageWidth.toFloat())
        val imageBottom = ((bottomPx - padY) / scale).coerceIn(0f, imageHeight.toFloat())
        return kr.co.hanium.dreamup.walksafe.depth.RectNorm(
            x = imageLeft / imageWidth,
            y = imageTop / imageHeight,
            width = ((imageRight - imageLeft) / imageWidth).coerceAtLeast(0f),
            height = ((imageBottom - imageTop) / imageHeight).coerceAtLeast(0f),
        )
    }
}

/**
 * Converts YUV_420_888 frames to RGB float32 letterboxed model input. Buffers are reused by size,
 * so one instance is not safe for concurrent preprocessing and does not own/close the source image.
 * The returned tensor is borrowed until the next call with the same instance and model size,
 * including calls that select a different strategy. No source image or plane is retained.
 */
class YuvImagePreprocessor {
    private val bilinearScratch = mutableMapOf<Int, FloatArray>()

    /** Same uint8 bilinear resize and integer letterbox padding as the reference model. */
    fun preprocessBilinear(image: ArgbImage, modelSize: Int): PreprocessedImage {
        val buffer = reusableBuffer(modelSize)
        val scratch = bilinearScratch.getOrPut(modelSize) { FloatArray(modelSize * modelSize * 3) }
        RgbLetterboxPreprocessor.write(image.pixels, image.width, image.height, modelSize, buffer, scratch)
        val scale = min(modelSize.toDouble() / image.width, modelSize.toDouble() / image.height)
        val resizedWidth = Math.rint(image.width * scale).toInt().coerceAtLeast(1)
        val resizedHeight = Math.rint(image.height * scale).toInt().coerceAtLeast(1)
        return PreprocessedImage(buffer, LetterboxTransform(image.width, image.height, modelSize,
            scale.toFloat(), Math.rint((modelSize - resizedWidth) / 2.0 - 0.1).toFloat(),
            Math.rint((modelSize - resizedHeight) / 2.0 - 0.1).toFloat(), resizedWidth, resizedHeight))
    }
    private val reusableInputBuffers = mutableMapOf<Int, ByteBuffer>()
    private val reusableRgbRows = mutableMapOf<Int, FloatArray>()

    fun preprocess(
        image: Image,
        modelSize: Int,
        strategy: YuvPreprocessingStrategy = YuvPreprocessingStrategy.LEGACY_TWO_PASS,
    ): PreprocessedImage {
        if (strategy == YuvPreprocessingStrategy.LEGACY_TWO_PASS) {
            return preprocess(decode(image), modelSize)
        }
        val width = image.width
        val height = image.height
        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        return preprocessYuv420(
            width = width,
            height = height,
            yBuffer = yPlane.buffer.duplicate(),
            uBuffer = uPlane.buffer.duplicate(),
            vBuffer = vPlane.buffer.duplicate(),
            yRowStride = yPlane.rowStride,
            yPixelStride = yPlane.pixelStride,
            uvRowStride = uPlane.rowStride,
            uvPixelStride = uPlane.pixelStride,
            modelSize = modelSize,
        )
    }

    fun decode(image: Image): ArgbImage {
        val width = image.width
        val height = image.height
        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        return ArgbImage(
            width = width,
            height = height,
            pixels = yuv420ToArgbPixels(
                width = width,
                height = height,
                yBuffer = yPlane.buffer.duplicate(),
                uBuffer = uPlane.buffer.duplicate(),
                vBuffer = vPlane.buffer.duplicate(),
                yRowStride = yPlane.rowStride,
                yPixelStride = yPlane.pixelStride,
                uvRowStride = uPlane.rowStride,
                uvPixelStride = uPlane.pixelStride,
            ),
        )
    }

    fun preprocess(image: ArgbImage, modelSize: Int): PreprocessedImage {
        require(modelSize > 0) { "modelSize must be positive" }
        val scale = min(modelSize / image.width.toFloat(), modelSize / image.height.toFloat())
        val resizedWidth = max(1, (image.width * scale).roundToInt())
        val resizedHeight = max(1, (image.height * scale).roundToInt())
        val padX = (modelSize - resizedWidth) / 2f
        val padY = (modelSize - resizedHeight) / 2f
        val buffer = reusableBuffer(modelSize)
        val sourceXByColumn = IntArray(modelSize) { x -> ((x - padX) / scale).roundToInt() }
        val sourceYByRow = IntArray(modelSize) { y -> ((y - padY) / scale).roundToInt() }

        val padValue = LETTERBOX_PAD_VALUE / 255f
        for (y in 0 until modelSize) {
            val sourceY = sourceYByRow[y]
            for (x in 0 until modelSize) {
                val sourceX = sourceXByColumn[x]
                if (sourceX in 0 until image.width && sourceY in 0 until image.height) {
                    val pixel = image.pixels[sourceY * image.width + sourceX]
                    buffer.putFloat(((pixel shr 16) and 0xff) / 255f)
                    buffer.putFloat(((pixel shr 8) and 0xff) / 255f)
                    buffer.putFloat((pixel and 0xff) / 255f)
                } else {
                    buffer.putFloat(padValue)
                    buffer.putFloat(padValue)
                    buffer.putFloat(padValue)
                }
            }
        }
        buffer.rewind()
        return PreprocessedImage(
            inputBuffer = buffer,
            transform = LetterboxTransform(
                imageWidth = image.width,
                imageHeight = image.height,
                modelSize = modelSize,
                scale = scale,
                padX = padX,
                padY = padY,
            ),
        )
    }

    /**
     * Samples before converting, retaining the legacy nearest-neighbor and RGB arithmetic exactly.
     * Plane indices remain absolute, including when a source buffer has a nonzero position.
     */
    internal fun preprocessYuv420(
        width: Int,
        height: Int,
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        yRowStride: Int,
        yPixelStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        modelSize: Int,
    ): PreprocessedImage {
        require(modelSize > 0) { "modelSize must be positive" }
        // Check the complete source extent, even if downsampling never selects its last pixel.
        // A camera plane need not contain row padding after the final pixel.
        val lastYIndex = (height - 1).toLong() * yRowStride + (width - 1).toLong() * yPixelStride
        val lastUvIndex = ((height - 1) / 2).toLong() * uvRowStride + ((width - 1) / 2).toLong() * uvPixelStride
        if (lastYIndex !in 0 until yBuffer.limit().toLong() ||
            lastUvIndex !in 0 until uBuffer.limit().toLong() ||
            lastUvIndex !in 0 until vBuffer.limit().toLong()
        ) {
            throw IndexOutOfBoundsException("YUV plane limit does not cover the source image")
        }
        val scale = min(modelSize / width.toFloat(), modelSize / height.toFloat())
        val resizedWidth = max(1, (width * scale).roundToInt())
        val resizedHeight = max(1, (height * scale).roundToInt())
        val padX = (modelSize - resizedWidth) / 2f
        val padY = (modelSize - resizedHeight) / 2f
        val sourceXByColumn = IntArray(modelSize) { x -> ((x - padX) / scale).roundToInt() }
        val sourceYByRow = IntArray(modelSize) { y -> ((y - padY) / scale).roundToInt() }
        val buffer = reusableBuffer(modelSize)
        val output = buffer.asFloatBuffer()
        val row = reusableRgbRows.getOrPut(modelSize) { FloatArray(modelSize * CHANNELS) }
        val padValue = LETTERBOX_PAD_VALUE / 255f
        var previousSourceY = Int.MIN_VALUE
        for (sourceY in sourceYByRow) {
            // Upsampling can repeat a source row. Only the tensor copy is repeated in that case.
            if (sourceY != previousSourceY) {
                if (sourceY !in 0 until height) {
                    row.fill(padValue)
                } else {
                    val yRow = sourceY * yRowStride
                    val uvRow = (sourceY / 2) * uvRowStride
                    var previousSourceX = -1
                    var red = padValue
                    var green = padValue
                    var blue = padValue
                    var rowOffset = 0
                    for (sourceX in sourceXByColumn) {
                        if (sourceX !in 0 until width) {
                            red = padValue
                            green = padValue
                            blue = padValue
                            previousSourceX = -1
                        } else if (sourceX != previousSourceX) {
                            val yValue = yBuffer.get(yRow + sourceX * yPixelStride).toInt() and 0xff
                            val uvOffset = uvRow + (sourceX / 2) * uvPixelStride
                            val uValue = (uBuffer.get(uvOffset).toInt() and 0xff) - 128
                            val vValue = (vBuffer.get(uvOffset).toInt() and 0xff) - 128
                            red = (yValue + 1.402f * vValue).roundToInt().coerceIn(0, 255) / 255f
                            green = (yValue - 0.344136f * uValue - 0.714136f * vValue)
                                .roundToInt().coerceIn(0, 255) / 255f
                            blue = (yValue + 1.772f * uValue).roundToInt().coerceIn(0, 255) / 255f
                            previousSourceX = sourceX
                        }
                        row[rowOffset++] = red
                        row[rowOffset++] = green
                        row[rowOffset++] = blue
                    }
                }
                previousSourceY = sourceY
            }
            output.put(row)
        }
        buffer.rewind()
        return PreprocessedImage(
            inputBuffer = buffer,
            transform = LetterboxTransform(
                imageWidth = width,
                imageHeight = height,
                modelSize = modelSize,
                scale = scale,
                padX = padX,
                padY = padY,
            ),
        )
    }

    private fun reusableBuffer(modelSize: Int): ByteBuffer {
        val requiredBytes = modelSize * modelSize * CHANNELS * BYTES_PER_FLOAT
        val current = reusableInputBuffers[modelSize]
        if (current != null && current.capacity() >= requiredBytes) {
            current.clear()
            return current
        }
        return ByteBuffer.allocateDirect(requiredBytes)
            .order(ByteOrder.nativeOrder())
            .also { reusableInputBuffers[modelSize] = it }
    }

    private companion object {
        const val CHANNELS = 3
        const val BYTES_PER_FLOAT = 4
        const val LETTERBOX_PAD_VALUE = 114
    }
}

/** Absolute reads preserve each plane's position/limit; YUV_420_888 U/V share both strides. */
internal fun yuv420ToArgbPixels(
    width: Int,
    height: Int,
    yBuffer: ByteBuffer,
    uBuffer: ByteBuffer,
    vBuffer: ByteBuffer,
    yRowStride: Int,
    yPixelStride: Int,
    uvRowStride: Int,
    uvPixelStride: Int,
): IntArray {
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val yRow = y * yRowStride
        val uvRow = (y / 2) * uvRowStride
        val pixelRow = y * width
        for (x in 0 until width) {
            val yValue = yBuffer.get(yRow + x * yPixelStride).toInt() and 0xff
            val uvOffset = uvRow + (x / 2) * uvPixelStride
            val uValue = (uBuffer.get(uvOffset).toInt() and 0xff) - 128
            val vValue = (vBuffer.get(uvOffset).toInt() and 0xff) - 128
            val r = (yValue + 1.402f * vValue).roundToInt().coerceIn(0, 255)
            val g = (yValue - 0.344136f * uValue - 0.714136f * vValue).roundToInt().coerceIn(0, 255)
            val b = (yValue + 1.772f * uValue).roundToInt().coerceIn(0, 255)
            pixels[pixelRow + x] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    return pixels
}

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
) {
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

class YuvImagePreprocessor {
    private val reusableInputBuffers = mutableMapOf<Int, ByteBuffer>()

    fun preprocess(image: Image, modelSize: Int): PreprocessedImage {
        return preprocess(decode(image), modelSize)
    }

    fun decode(image: Image): ArgbImage {
        return ArgbImage(
            width = image.width,
            height = image.height,
            pixels = image.toArgbPixels(),
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

        val padValue = LETTERBOX_PAD_VALUE / 255f
        for (y in 0 until modelSize) {
            for (x in 0 until modelSize) {
                val sourceX = ((x - padX) / scale).roundToInt()
                val sourceY = ((y - padY) / scale).roundToInt()
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

    private fun Image.toArgbPixels(): IntArray {
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        val pixels = IntArray(width * height)

        for (y in 0 until height) {
            val yRow = y * yPlane.rowStride
            val uvRow = (y / 2) * uPlane.rowStride
            for (x in 0 until width) {
                val yValue = yBuffer.get(yRow + x * yPlane.pixelStride).toInt() and 0xff
                val uvOffset = uvRow + (x / 2) * uPlane.pixelStride
                val uValue = (uBuffer.get(uvOffset).toInt() and 0xff) - 128
                val vValue = (vBuffer.get(uvOffset).toInt() and 0xff) - 128
                val r = (yValue + 1.402f * vValue).roundToInt().coerceIn(0, 255)
                val g = (yValue - 0.344136f * uValue - 0.714136f * vValue).roundToInt().coerceIn(0, 255)
                val b = (yValue + 1.772f * uValue).roundToInt().coerceIn(0, 255)
                pixels[y * width + x] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return pixels
    }

    private companion object {
        const val CHANNELS = 3
        const val BYTES_PER_FLOAT = 4
        const val LETTERBOX_PAD_VALUE = 114
    }
}

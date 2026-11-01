package kr.co.hanium.dreamup.walksafe.inference

import android.graphics.Rect
import android.hardware.DataSpace
import android.os.Build
import android.media.Image
import kr.co.hanium.dreamup.walksafe.UprightCameraImage
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Declared matrix/range only; unknown declarations retain the existing full adjusted BT.601. */
internal enum class YuvColorConversion(
    private val yOffset: Int,
    private val yScale: Float,
    private val redV: Float,
    private val greenU: Float,
    private val greenV: Float,
    private val blueU: Float,
) {
    BT601_FULL(0, 1f, 1.402f, .344136f, .714136f, 1.772f),
    BT601_LIMITED(16, 255f / 219f, 1.402f * 255f / 224f,
        .344136f * 255f / 224f, .714136f * 255f / 224f, 1.772f * 255f / 224f),
    BT709_FULL(0, 1f, 1.5748f, .187324f, .468124f, 1.8556f),
    BT709_LIMITED(16, 255f / 219f, 1.5748f * 255f / 224f,
        .187324f * 255f / 224f, .468124f * 255f / 224f, 1.8556f * 255f / 224f);

    fun toArgb(y: Int, u: Int, v: Int): Int {
        val luma = (y - yOffset) * yScale
        val red = (luma + redV * v).roundToInt().coerceIn(0, 255)
        val green = (luma - greenU * u - greenV * v).roundToInt().coerceIn(0, 255)
        val blue = (luma + blueU * u).roundToInt().coerceIn(0, 255)
        return (0xff shl 24) or (red shl 16) or (green shl 8) or blue
    }

    companion object {
        fun fromImage(image: Image): YuvColorConversion {
            if (Build.VERSION.SDK_INT < 33) return BT601_FULL
            val dataSpace = try {
                image.dataSpace
            } catch (_: RuntimeException) {
                // Optional metadata must not discard otherwise readable YUV planes.
                return BT601_FULL
            }
            // These packed field masks match android.hardware.DataSpace.getStandard/getRange.
            val standard = dataSpace and (63 shl 16)
            val range = dataSpace and (7 shl 27)
            if (range != DataSpace.RANGE_FULL && range != DataSpace.RANGE_LIMITED) return BT601_FULL
            val limited = range == DataSpace.RANGE_LIMITED
            return when (standard) {
                DataSpace.STANDARD_BT601_525, DataSpace.STANDARD_BT601_625 ->
                    if (limited) BT601_LIMITED else BT601_FULL
                DataSpace.STANDARD_BT709 -> if (limited) BT709_LIMITED else BT709_FULL
                else -> BT601_FULL
            }
        }
    }
}

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

/** Plane offsets stay in full-image coordinates; pixels outside this valid rect are never read. */
internal data class YuvCropRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(left >= 0 && top >= 0 && right > left && bottom > top)
    }

    fun requireWithin(width: Int, height: Int) {
        require(right <= width && bottom <= height) { "YUV crop must fit the image" }
    }

    fun contains(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom

    fun isFull(width: Int, height: Int): Boolean = left == 0 && top == 0 && right == width && bottom == height

    /** Rotate the valid source rectangle with the image, retaining exact pixel boundaries. */
    fun toUpright(width: Int, height: Int, quarterTurns: Int): YuvCropRect {
        requireWithin(width, height)
        require(quarterTurns in 0..3)
        return when (quarterTurns) {
            0 -> this
            1 -> YuvCropRect(height - bottom, left, height - top, right)
            2 -> YuvCropRect(width - right, height - bottom, width - left, height - top)
            else -> YuvCropRect(top, width - right, bottom, width - left)
        }
    }

    /** Results are returned in full sensor coordinates; crop padding is never detector evidence. */
    fun toValidSensorDetection(
        candidate: DetectionCandidate,
        imageWidth: Int,
        imageHeight: Int,
        quarterTurns: Int = 0,
    ): DetectionCandidate? {
        val sensor = if (quarterTurns == 0) candidate else candidate.copy(
            bboxNorm = UprightCameraImage.toSensor(candidate.bboxNorm, quarterTurns),
        )
        if (isFull(imageWidth, imageHeight)) return sensor
        val box = sensor.bboxNorm
        val boxRight = box.x + box.width
        val boxBottom = box.y + box.height
        if (!box.x.isFinite() || !box.y.isFinite() || !boxRight.isFinite() || !boxBottom.isFinite()) return null
        val clippedLeft = maxOf(box.x, left.toFloat() / imageWidth)
        val clippedTop = maxOf(box.y, top.toFloat() / imageHeight)
        val clippedRight = minOf(boxRight, right.toFloat() / imageWidth)
        val clippedBottom = minOf(boxBottom, bottom.toFloat() / imageHeight)
        if (clippedRight <= clippedLeft || clippedBottom <= clippedTop) return null
        return sensor.copy(bboxNorm = RectNorm(
            clippedLeft, clippedTop, clippedRight - clippedLeft, clippedBottom - clippedTop,
        ))
    }

    companion object {
        fun full(width: Int, height: Int): YuvCropRect = YuvCropRect(0, 0, width, height)

        fun fromImage(image: Image): YuvCropRect = fromRect(image.cropRect)

        fun fromRect(rect: Rect): YuvCropRect = rect.let {
            YuvCropRect(it.left, it.top, it.right, it.bottom)
        }
    }
}

private const val YUV_CROP_PAD_ARGB = -0x8d8d8e // Opaque RGB 114, matching tensor letterbox padding.

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
    internal fun intersectsImageContent(
        modelRect: kr.co.hanium.dreamup.walksafe.depth.RectNorm,
        validImageCrop: YuvCropRect? = null,
    ): Boolean {
        val left = maxOf(modelRect.x * modelSize, padX, 0f)
        val top = maxOf(modelRect.y * modelSize, padY, 0f)
        // Integer resize rounding and inverse scaling can end at different subpixel positions.
        val right = minOf((modelRect.x + modelRect.width) * modelSize,
            padX + minOf(resizedWidth.toFloat(), imageWidth * scale), modelSize.toFloat())
        val bottom = minOf((modelRect.y + modelRect.height) * modelSize,
            padY + minOf(resizedHeight.toFloat(), imageHeight * scale), modelSize.toFloat())
        // Comparing before division avoids a padding-edge contact becoming a tiny visible box
        // through floating-point round-off when undoing the scale.
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite() ||
            right <= left || bottom <= top) return false
        if (validImageCrop == null) return true
        validImageCrop.requireWithin(imageWidth, imageHeight)
        return minOf(right, padX + validImageCrop.right * scale) >
            maxOf(left, padX + validImageCrop.left * scale) &&
            minOf(bottom, padY + validImageCrop.bottom * scale) >
            maxOf(top, padY + validImageCrop.top * scale)
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
class YuvImagePreprocessor(private val bufferProvider: ((Int) -> ByteBuffer)? = null) {
    private val bilinearScratch = mutableMapOf<Int, FloatArray>()

    /** Same uint8 bilinear resize and integer letterbox padding as the reference model. */
    @Synchronized fun preprocessBilinear(image: ArgbImage, modelSize: Int): PreprocessedImage {
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

    /** Called only after the serial caller has drained every borrowed tensor consumer.
     * Drops host cache ownership; it does not free arena backing, force GC or prove native release. */
    @Synchronized internal fun clearAfterDrain() {
        bilinearScratch.clear(); reusableInputBuffers.clear(); reusableRgbRows.clear()
    }

    @Synchronized fun preprocess(
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
            cropRect = YuvCropRect.fromImage(image),
            colorConversion = YuvColorConversion.fromImage(image),
        )
    }

    fun decode(image: Image): ArgbImage = decodeInto(image, IntArray(image.width * image.height))

    /** Destination belongs to the serial caller; the ordinary decode API still returns owned pixels. */
    internal fun decodeInto(image: Image, destination: IntArray): ArgbImage {
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
                cropRect = YuvCropRect.fromImage(image),
                destination = destination,
                colorConversion = YuvColorConversion.fromImage(image),
            ),
        )
    }

    @Synchronized fun preprocess(image: ArgbImage, modelSize: Int): PreprocessedImage {
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
    @Synchronized internal fun preprocessYuv420(
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
        cropRect: YuvCropRect = YuvCropRect.full(width, height),
    ): PreprocessedImage = preprocessYuv420(width, height, yBuffer, uBuffer, vBuffer,
        yRowStride, yPixelStride, uvRowStride, uvPixelStride, modelSize, cropRect,
        YuvColorConversion.BT601_FULL)

    @Synchronized internal fun preprocessYuv420(
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
        cropRect: YuvCropRect,
        colorConversion: YuvColorConversion,
    ): PreprocessedImage {
        require(modelSize > 0) { "modelSize must be positive" }
        cropRect.requireWithin(width, height)
        // Check the complete valid crop extent, even if downsampling never selects its last pixel.
        // A camera plane need not contain row padding after the final pixel.
        val lastYIndex = (cropRect.bottom - 1).toLong() * yRowStride + (cropRect.right - 1).toLong() * yPixelStride
        val lastUvIndex = ((cropRect.bottom - 1) / 2).toLong() * uvRowStride +
            ((cropRect.right - 1) / 2).toLong() * uvPixelStride
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
        val isCropped = !cropRect.isFull(width, height)
        var previousSourceY = Int.MIN_VALUE
        for (sourceY in sourceYByRow) {
            // Upsampling can repeat a source row. Only the tensor copy is repeated in that case.
            if (sourceY != previousSourceY) {
                if (sourceY !in 0 until height || (isCropped && (sourceY < cropRect.top || sourceY >= cropRect.bottom))) {
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
                        if (sourceX !in 0 until width || (isCropped && (sourceX < cropRect.left || sourceX >= cropRect.right))) {
                            red = padValue
                            green = padValue
                            blue = padValue
                            previousSourceX = -1
                        } else if (sourceX != previousSourceX) {
                            val yValue = yBuffer.get(yRow + sourceX * yPixelStride).toInt() and 0xff
                            val uvOffset = uvRow + (sourceX / 2) * uvPixelStride
                            val uValue = (uBuffer.get(uvOffset).toInt() and 0xff) - 128
                            val vValue = (vBuffer.get(uvOffset).toInt() and 0xff) - 128
                            val pixel = colorConversion.toArgb(yValue, uValue, vValue)
                            red = ((pixel ushr 16) and 0xff) / 255f
                            green = ((pixel ushr 8) and 0xff) / 255f
                            blue = (pixel and 0xff) / 255f
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
        return (bufferProvider?.invoke(modelSize) ?: ByteBuffer.allocateDirect(requiredBytes).order(ByteOrder.nativeOrder()))
            .also { require(it.isDirect && !it.isReadOnly && it.capacity() == requiredBytes && it.order() == ByteOrder.nativeOrder()) {
                "Preprocessor input backing must match the complete native-order tensor"
            }; it.clear() }
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
    cropRect: YuvCropRect = YuvCropRect.full(width, height),
    destination: IntArray = IntArray(width * height),
): IntArray = yuv420ToArgbPixels(width, height, yBuffer, uBuffer, vBuffer,
    yRowStride, yPixelStride, uvRowStride, uvPixelStride, cropRect, destination,
    YuvColorConversion.BT601_FULL)

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
    cropRect: YuvCropRect,
    destination: IntArray = IntArray(width * height),
    colorConversion: YuvColorConversion,
): IntArray {
    cropRect.requireWithin(width, height)
    require(destination.size.toLong() == width.toLong() * height) { "ARGB destination shape mismatch" }
    val pixels = destination
    val isCropped = !cropRect.isFull(width, height)
    for (y in 0 until height) {
        val yRow = y * yRowStride
        val uvRow = (y / 2) * uvRowStride
        val pixelRow = y * width
        var previousUvOffset = -1
        var uValue = 0
        var vValue = 0
        for (x in 0 until width) {
            if (isCropped && !cropRect.contains(x, y)) {
                pixels[pixelRow + x] = YUV_CROP_PAD_ARGB
                continue
            }
            val yValue = yBuffer.get(yRow + x * yPixelStride).toInt() and 0xff
            val uvOffset = uvRow + (x / 2) * uvPixelStride
            if (uvOffset != previousUvOffset || previousUvOffset < 0) {
                uValue = (uBuffer.get(uvOffset).toInt() and 0xff) - 128
                vValue = (vBuffer.get(uvOffset).toInt() and 0xff) - 128
                previousUvOffset = uvOffset
            }
            pixels[pixelRow + x] = colorConversion.toArgb(yValue, uValue, vValue)
        }
    }
    return pixels
}

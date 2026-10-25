package kr.co.hanium.dreamup.walksafe.inference

import android.media.Image

/**
 * Two bounded pixel buffers for one detector owner. Results are borrowed until its next decode or
 * rotation. Only use within the detector's serial runtime lock; never publish these pixels.
 */
internal class DetectorRgbScratch(private val preprocessor: YuvImagePreprocessor) {
    private var sourcePixels = IntArray(0)
    private var rotatedPixels = IntArray(0)

    fun decode(frame: OwnedYuvFrame): ArgbImage =
        frame.toArgbInto(sourceBuffer(frame.width, frame.height))

    fun decode(image: Image): ArgbImage =
        preprocessor.decodeInto(image, sourceBuffer(image.width, image.height))

    /** Same integer mapping as UprightCameraImage.rotate, applied to freshly decoded pixels. */
    fun rotate(image: ArgbImage, turns: Int): ArgbImage {
        require(turns in 0..3)
        if (turns == 0) return image
        require(image.pixels.size.toLong() == image.width.toLong() * image.height)
        if (rotatedPixels.size != image.pixels.size) rotatedPixels = IntArray(image.pixels.size)
        require(image.pixels !== rotatedPixels) { "Rotate freshly decoded source pixels" }
        val width = if (turns % 2 == 1) image.height else image.width
        val height = if (turns % 2 == 1) image.width else image.height
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val dx = when (turns) { 1 -> image.height - 1 - y; 2 -> image.width - 1 - x; else -> y }
            val dy = when (turns) { 1 -> x; 2 -> image.height - 1 - y; else -> image.width - 1 - x }
            rotatedPixels[dy * width + dx] = image.pixels[y * image.width + x]
        }
        return ArgbImage(width, height, rotatedPixels)
    }

    fun clear() {
        sourcePixels = IntArray(0)
        rotatedPixels = IntArray(0)
    }

    private fun sourceBuffer(width: Int, height: Int): IntArray {
        val count = width.toLong() * height
        require(width > 0 && height > 0 && count <= Int.MAX_VALUE)
        if (sourcePixels.size != count.toInt()) {
            sourcePixels = IntArray(count.toInt())
            // Release the previous rotated resolution even when this capture needs no rotation.
            rotatedPixels = IntArray(0)
        }
        return sourcePixels
    }
}

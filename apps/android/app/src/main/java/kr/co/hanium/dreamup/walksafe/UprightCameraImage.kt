package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.inference.ArgbImage
import kotlin.math.atan2
import kotlin.math.roundToInt

/** Rotation follows ARCore's image-to-display basis, including display rotation. */
internal object UprightCameraImage {
    fun quarterTurns(viewBasis: FloatArray): Int =
        ((atan2((viewBasis[3] - viewBasis[1]).toDouble(),
            (viewBasis[2] - viewBasis[0]).toDouble()) / (Math.PI / 2)).roundToInt() + 4) % 4

    fun rotate(image: ArgbImage, turns: Int): ArgbImage {
        require(turns in 0..3)
        if (turns == 0) return image
        val width = if (turns % 2 == 1) image.height else image.width
        val height = if (turns % 2 == 1) image.width else image.height
        val pixels = IntArray(width * height)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val dx = when (turns) { 1 -> image.height - 1 - y; 2 -> image.width - 1 - x; else -> y }
            val dy = when (turns) { 1 -> x; 2 -> image.height - 1 - y; else -> image.width - 1 - x }
            pixels[dy * width + dx] = image.pixels[y * image.width + x]
        }
        return ArgbImage(width, height, pixels)
    }

    /** Return detections to sensor coordinates before ARCore screen/depth mapping. */
    fun toSensor(rect: RectNorm, turns: Int): RectNorm = when (turns) {
        0 -> rect
        1 -> RectNorm(rect.y, 1f - rect.x - rect.width, rect.height, rect.width)
        2 -> RectNorm(1f - rect.x - rect.width, 1f - rect.y - rect.height, rect.width, rect.height)
        3 -> RectNorm(1f - rect.y - rect.height, rect.x, rect.height, rect.width)
        else -> error("Invalid quarter turn")
    }
}

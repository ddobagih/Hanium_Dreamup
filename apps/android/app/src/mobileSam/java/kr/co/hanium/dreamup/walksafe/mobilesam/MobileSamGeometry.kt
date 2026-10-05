package kr.co.hanium.dreamup.walksafe.mobilesam

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Coordinates refer to the upright source image, never the letterboxed preview. */
data class SamBox(val left: Float, val top: Float, val right: Float, val bottom: Float, val label: String) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f })
        require(right > left && bottom > top)
    }
}

object MobileSamGeometry {
    const val SIZE = 1024
    val tactileClasses = setOf("linear_tactile_paving", "damaged_linear_tactile_paving",
        "dot_tactile_paving", "damaged_dot_tactile_paving")

    fun resizedShape(width: Int, height: Int, size: Int = SIZE): Pair<Int, Int> {
        require(width > 0 && height > 0 && size > 0)
        val gain = size.toDouble() / max(width, height)
        return max(1, Math.rint(width * gain).toInt()) to max(1, Math.rint(height * gain).toInt())
    }

    fun prompt(box: SamBox, width: Int, height: Int): FloatArray {
        require(width > 0 && height > 0)
        val gain = SIZE.toFloat() / max(width, height)
        return floatArrayOf(box.left * width * gain, box.top * height * gain,
            box.right * width * gain, box.bottom * height * gain)
    }

    /** Crop bottom/right padding, then interpolate logits; thresholding first changes edges. */
    fun restoreMask(logits: FloatArray, width: Int, height: Int, size: Int = SIZE): BooleanArray {
        require(logits.size == size * size)
        val (rw, rh) = resizedShape(width, height, size)
        return BooleanArray(width * height) { i ->
            val sx = ((i % width + 0.5) * rw / width - 0.5).coerceIn(0.0, rw - 1.0)
            val sy = ((i / width + 0.5) * rh / height - 0.5).coerceIn(0.0, rh - 1.0)
            val x = floor(sx).toInt(); val y = floor(sy).toInt()
            val ax = (sx - x).toFloat(); val ay = (sy - y).toFloat()
            val x1 = min(x + 1, rw - 1); val y1 = min(y + 1, rh - 1)
            val upper = logits[y * size + x] * (1 - ax) + logits[y * size + x1] * ax
            val lower = logits[y1 * size + x] * (1 - ax) + logits[y1 * size + x1] * ax
            upper * (1 - ay) + lower * ay > 0f
        }
    }
}

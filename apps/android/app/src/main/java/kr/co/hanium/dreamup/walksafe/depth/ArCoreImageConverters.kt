package kr.co.hanium.dreamup.walksafe.depth

import android.media.Image
import java.nio.ByteOrder

fun Image.toDepthImage16Snapshot(): DepthImage16 {
    require(width > 0 && height > 0) { "ARCore depth image dimensions must be positive" }
    val plane = planes.firstOrNull() ?: error("ARCore depth image has no plane")
    val buffer = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride.takeIf { it > 0 } ?: 2
    val values = IntArray(width * height)

    for (y in 0 until height) {
        val rowBase = y * rowStride
        for (x in 0 until width) {
            val offset = rowBase + x * pixelStride
            values[y * width + x] = if (offset + 1 < buffer.limit()) {
                val low = buffer.get(offset).toInt() and 0xff
                val high = buffer.get(offset + 1).toInt() and 0xff
                (high shl 8) or low
            } else {
                0
            }
        }
    }
    return DepthImage16(width = width, height = height, millimeters = values)
}

fun Image.toConfidenceImage8Snapshot(): ConfidenceImage8 {
    require(width > 0 && height > 0) { "ARCore confidence image dimensions must be positive" }
    val plane = planes.firstOrNull() ?: error("ARCore confidence image has no plane")
    val buffer = plane.buffer.duplicate()
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride.takeIf { it > 0 } ?: 1
    val values = ByteArray(width * height)

    for (y in 0 until height) {
        val rowBase = y * rowStride
        for (x in 0 until width) {
            val offset = rowBase + x * pixelStride
            values[y * width + x] = if (offset < buffer.limit()) buffer.get(offset) else 0
        }
    }
    return ConfidenceImage8(width = width, height = height, values = values)
}

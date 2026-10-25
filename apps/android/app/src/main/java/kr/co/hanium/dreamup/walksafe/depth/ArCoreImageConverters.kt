package kr.co.hanium.dreamup.walksafe.depth

import android.media.Image
import android.os.SystemClock
import android.util.Log
import kr.co.hanium.dreamup.walksafe.BuildConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

fun Image.toDepthImage16Snapshot(): DepthImage16 {
    require(width > 0 && height > 0) { "ARCore depth image dimensions must be positive" }
    val plane = planes.firstOrNull() ?: error("ARCore depth image has no plane")
    val buffer = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride.takeIf { it > 0 } ?: 2
    val values = IntArray(width * height)
    var allZero = true
    var missingBytePixels = 0

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
            if (BuildConfig.DEBUG) {
                if (values[y * width + x] != 0) allZero = false
                if (offset + 1 >= buffer.limit()) missingBytePixels += 1
            }
        }
    }
    if (BuildConfig.DEBUG && (allZero || missingBytePixels > 0)) {
        DepthImageLayoutDiagnostics.recordIfDue(this, buffer, rowStride, pixelStride, allZero, missingBytePixels)
    }
    return DepthImage16(width = width, height = height, millimeters = values)
}

/** Observes only copied depth and layout; it never changes pixels or admission decisions. */
private object DepthImageLayoutDiagnostics {
    private val lastObservationAtMs = AtomicLong(0L)

    fun recordIfDue(image: Image, buffer: ByteBuffer, rowStride: Int, pixelStride: Int,
        allZero: Boolean, missingBytePixels: Int) {
        try {
            val nowMs = SystemClock.elapsedRealtime()
            val previousAtMs = lastObservationAtMs.get()
            if (nowMs - previousAtMs < 1_000L || !lastObservationAtMs.compareAndSet(previousAtMs, nowMs)) return
            val width = image.width
            val height = image.height
            val limit = buffer.limit()
            val requiredEnd = (height - 1L) * rowStride + (width - 1L) * pixelStride + 2L
            val nativePixelStride = image.planes.firstOrNull()?.pixelStride
            Log.d("WalkSafeDepthCheck",
                "event=DEPTH_IMAGE_LAYOUT allZero=$allZero missingBytePixels=$missingBytePixels " +
                    "format=${image.format} width=$width height=$height rowStride=$rowStride " +
                    "pixelStride=$nativePixelStride effectivePixelStride=$pixelStride " +
                    "bufferPosition=${buffer.position()} bufferLimit=$limit " +
                    "bufferCapacity=${buffer.capacity()} requiredEnd=$requiredEnd")
        } catch (_: RuntimeException) {
            // Diagnostics cannot invalidate a successfully copied image or interrupt its owner.
        }
    }
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

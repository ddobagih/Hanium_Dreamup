package kr.co.hanium.dreamup.walksafe.device

import java.nio.ByteBuffer

data class CameraLumaMeasurement(
    val normalizedBrightness: Double,
    /** Candidate proxy: fraction of sampled luma values that are near black. */
    val darkOrOccludedFraction: Double,
)

object CameraLumaMeasurementPolicy {
    private const val SAMPLE_GRID_SIZE = 32
    private const val NEAR_BLACK_LUMA = 20

    /**
     * Samples a YUV_420_888 Y plane without assuming tightly packed rows or pixels.
     * The occlusion value is deliberately only a near-black proxy until field validation.
     */
    fun measure(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ): CameraLumaMeasurement? {
        if (
            width <= 0 ||
            height <= 0 ||
            rowStride <= 0 ||
            pixelStride <= 0
        ) return null

        val pixels = buffer.duplicate()
        val firstPixelOffset = pixels.position()
        val xStep = maxOf(1, width / SAMPLE_GRID_SIZE)
        val yStep = maxOf(1, height / SAMPLE_GRID_SIZE)
        var sampleCount = 0
        var lumaSum = 0L
        var nearBlackCount = 0

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val indexLong = firstPixelOffset.toLong() +
                    y.toLong() * rowStride +
                    x.toLong() * pixelStride
                if (indexLong !in firstPixelOffset.toLong() until pixels.limit().toLong()) {
                    return null
                }
                val index = indexLong.toInt()
                val luma = pixels.get(index).toInt() and 0xff
                sampleCount += 1
                lumaSum += luma
                if (luma <= NEAR_BLACK_LUMA) nearBlackCount += 1
                x += xStep
            }
            y += yStep
        }
        if (sampleCount == 0) return null
        return CameraLumaMeasurement(
            normalizedBrightness = lumaSum.toDouble() / (sampleCount * 255.0),
            darkOrOccludedFraction = nearBlackCount.toDouble() / sampleCount,
        )
    }
}

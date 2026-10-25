package kr.co.hanium.dreamup.walksafe.inference.tracking

import java.nio.ByteBuffer
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken

/**
 * Immutable small luma sampled from one original CPU Image. There is no Image/buffer reference,
 * public pixel access, or factory for rebinding an existing capture to another token/epoch.
 */
class UnknownMaterialPointLumaCapture private constructor(
    val token: FastSamFrameToken,
    val imageQuarterTurns: Int,
    val observedCpuImageTimestampNs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val rowStride: Int,
    val pixelStride: Int,
    private val sampled: GrayTrackingFrame,
) {
    val sampledWidth: Int get() = sampled.width
    val sampledHeight: Int get() = sampled.height
    val retainedImageBytes: Int get() = sampled.retainedBytes

    /** Only the producer's local geometry revision changes; all original capture clocks remain. */
    internal fun toFrame(geometryVersion: Long): GrayTrackingFrame = GrayTrackingFrame.copyOf(
        sampled.key.copy(geometryVersion = geometryVersion), sampled.width, sampled.height,
        sampled.copyPixels(), sampled.sourceWidth, sampled.sourceHeight,
    )

    companion object {
        /**
         * Call while owning the SAME open CPU Image used to construct token. Observed timestamp,
         * dimensions, plane strides and buffer must come from that Image. Matching values alone
         * do not prove this caller provenance. This pure copy never enters a producer monitor or
         * native code. It uses the existing exact four-sample resampling and maximum edge 192.
         * Invalid input returns null; later admission still applies the original TTL/queue bounds.
         */
        fun copyFromLuma(
            token: FastSamFrameToken,
            imageQuarterTurns: Int,
            observedCpuImageTimestampNs: Long,
            imageWidth: Int,
            imageHeight: Int,
            luma: ByteBuffer,
            rowStride: Int,
            pixelStride: Int,
        ): UnknownMaterialPointLumaCapture? {
            if (token.source != FastSamFrameToken.Source.LIVE_CAMERA || imageQuarterTurns !in 0..3 ||
                observedCpuImageTimestampNs != token.cpuImageTimestampNs ||
                imageWidth != token.width || imageHeight != token.height
            ) return null
            val key = VisualFrameKey(token.sessionEpoch, token.frameId, token.cpuImageTimestampNs,
                token.capturedElapsedNs / 1_000_000L, 0L)
            val sampled = try {
                GrayTrackingFrame.copyFromLuma(key, imageWidth, imageHeight, luma, rowStride, pixelStride)
            } catch (_: IllegalArgumentException) {
                return null
            }
            return UnknownMaterialPointLumaCapture(token, imageQuarterTurns, observedCpuImageTimestampNs,
                imageWidth, imageHeight, rowStride, pixelStride, sampled)
        }
    }
}

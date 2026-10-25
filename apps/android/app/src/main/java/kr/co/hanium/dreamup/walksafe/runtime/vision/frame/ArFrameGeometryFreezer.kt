package kr.co.hanium.dreamup.walksafe.runtime.vision.frame

import kr.co.hanium.dreamup.walksafe.depth.FrozenImageToTextureCoordinateMapper
import android.graphics.RectF
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.ProximityDepthDisplay
import kr.co.hanium.dreamup.walksafe.depth.ImageSize
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.navigation.FrozenImageToDepthTransform
import kr.co.hanium.dreamup.walksafe.DebugBboxOverlayView

internal class ArFrameGeometryFreezer(private val elapsedRealtimeMs: () -> Long,
    private val viewportSize: () -> ImageSize,
    private val percent: (Float) -> String,
) {
    private val surfaceWidth get() = viewportSize().width
    private val surfaceHeight get() = viewportSize().height

    /** Freezes the current ARCore image-to-depth transform; the Frame is never retained. */
    fun createFrozenDepthMapper(
        frame: Frame,
        frameId: Long,
        imageWidth: Int,
        imageHeight: Int,
        snapshot: DepthFrameSnapshot,
        allowMissingDepth: Boolean = false,
    ): FrozenImageToTextureCoordinateMapper? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val depthSize = when {
            snapshot.rawDepth != null -> ImageSize(snapshot.rawDepth.width, snapshot.rawDepth.height)
            snapshot.fullDepth != null -> ImageSize(snapshot.fullDepth.width, snapshot.fullDepth.height)
            // The mapper returns normalized UVs. With no depth samples this neutral size permits
            // current geometry validation only; it does not create pixels or reuse an old mapping.
            allowMissingDepth -> ImageSize(1, 1)
            else -> return null
        }
        if (depthSize.width <= 0 || depthSize.height <= 0) return null
        val input = floatArrayOf(
            0f, 0f,
            imageWidth.toFloat(), 0f,
            0f, imageHeight.toFloat(),
            imageWidth.toFloat(), imageHeight.toFloat(),
            imageWidth / 2f, imageHeight / 2f,
        )
        val output = FloatArray(input.size)
        return try {
            frame.transformCoordinates2d(
                Coordinates2d.IMAGE_PIXELS,
                input,
                Coordinates2d.TEXTURE_NORMALIZED,
                output,
            )
            val points = output.toList().chunked(2).map { values -> Point2(values[0], values[1]) }
            val transform = FrozenImageToDepthTransform.create(
                frameId = frameId,
                mappedCorners = points.take(4),
                mappedCenter = points[4],
            ) ?: return null
            FrozenImageToTextureCoordinateMapper(
                frameId = frameId,
                transform = transform,
                depthSize = depthSize,
            )
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun RectNorm.toViewRect(frame: Frame, imageWidth: Int, imageHeight: Int): RectF? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val left = x.coerceIn(0f, 1f) * imageWidth
        val top = y.coerceIn(0f, 1f) * imageHeight
        val right = (x + width).coerceIn(0f, 1f) * imageWidth
        val bottom = (y + height).coerceIn(0f, 1f) * imageHeight
        val imageCorners = floatArrayOf(
            left, top,
            right, top,
            right, bottom,
            left, bottom,
        )
        val viewCorners = FloatArray(imageCorners.size)
        return try {
            frame.transformCoordinates2d(
                Coordinates2d.IMAGE_PIXELS,
                imageCorners,
                Coordinates2d.VIEW,
                viewCorners,
            )
            if (viewCorners.any { !it.isFinite() }) return null
            val xs = floatArrayOf(viewCorners[0], viewCorners[2], viewCorners[4], viewCorners[6])
            val ys = floatArrayOf(viewCorners[1], viewCorners[3], viewCorners[5], viewCorners[7])
            val maxWidth = surfaceWidth.takeIf { it > 0 }?.toFloat() ?: return null
            val maxHeight = surfaceHeight.takeIf { it > 0 }?.toFloat() ?: return null
            val mappedLeft = xs.minOrNull()?.coerceIn(0f, maxWidth) ?: return null
            val mappedTop = ys.minOrNull()?.coerceIn(0f, maxHeight) ?: return null
            val mappedRight = xs.maxOrNull()?.coerceIn(mappedLeft, maxWidth) ?: return null
            val mappedBottom = ys.maxOrNull()?.coerceIn(mappedTop, maxHeight) ?: return null
            RectF(mappedLeft, mappedTop, mappedRight, mappedBottom)
        } catch (_: RuntimeException) {
            null
        }
    }

    fun buildDebugOverlayBoxes(
        frame: Frame,
        detections: List<DetectionCandidate>,
        bestOutput: TrackedObjectDepth?,
        imageWidth: Int?,
        imageHeight: Int?,
        capturedAtElapsedRealtimeMs: Long? = null,
    ): List<DebugBboxOverlayView.DebugOverlayBox> {
        if (imageWidth == null || imageHeight == null) {
            return emptyList()
        }
        val mappedDetectionBoxes = detections
            .sortedByDescending { it.detectionConfidence }
            .take(MAX_OVERLAY_DETECTION_BOXES)
            .mapNotNull { detection ->
                val rect = detection.bboxNorm.toViewRect(frame, imageWidth, imageHeight) ?: return@mapNotNull null
                DebugBboxOverlayView.DebugOverlayBox(
                    rectPx = rect,
                    label = "${detection.className} ${percent(detection.detectionConfidence)}",
                    best = false,
                    className = detection.className,
                )
            }
        val bestBox = bestOutput?.let { output ->
            output.bboxNorm.toViewRect(frame, imageWidth, imageHeight)?.let { rect ->
                DebugBboxOverlayView.DebugOverlayBox(
                    rectPx = rect,
                    label = ProximityDepthDisplay.labelFor(output, capturedAtElapsedRealtimeMs, elapsedRealtimeMs()),
                    best = true,
                    className = output.className,
                    trackKey = output.trackId,
                )
            }
        }
        val boxes = if (bestBox == null) mappedDetectionBoxes else listOf(bestBox) +
            mappedDetectionBoxes.filterNot { it.className == bestBox.className && it.rectPx == bestBox.rectPx }
        return boxes
    }
    private companion object { const val MAX_OVERLAY_DETECTION_BOXES = 5 }
}


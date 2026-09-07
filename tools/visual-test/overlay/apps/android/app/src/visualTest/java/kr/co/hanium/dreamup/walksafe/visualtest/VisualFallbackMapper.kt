package kr.co.hanium.dreamup.walksafe.visualtest

import android.graphics.RectF
import androidx.camera.core.ImageProxy
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import kr.co.hanium.dreamup.walksafe.DebugBboxOverlayView
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate

@androidx.annotation.OptIn(markerClass = [TransformExperimental::class])
object VisualFallbackMapper {
    /**
     * Capture while the proxy is open. The factory creates a detached matrix and viewport size.
     * Detection coordinates must refer to this proxy's entire buffer, with no additional rotation.
     * Preview and analysis must share the same ViewPort when bound.
     */
    fun captureTransform(imageProxy: ImageProxy): OutputTransform = ImageProxyTransformFactory().apply {
        setUsingCropRect(false)
        setUsingRotationDegrees(false)
    }.getOutputTransform(imageProxy)

    /** Call on the UI thread with PreviewView.outputTransform and the captured image dimensions. */
    fun map(
        detections: List<DetectionCandidate>,
        imageWidth: Int,
        imageHeight: Int,
        source: OutputTransform,
        target: OutputTransform,
    ): List<DebugBboxOverlayView.DebugOverlayBox> {
        if (imageWidth <= 0 || imageHeight <= 0) return emptyList()
        val transform = try {
            CoordinateTransform(source, target)
        } catch (_: IllegalStateException) {
            return emptyList()
        }
        return detections.mapNotNull { detection ->
            val bbox = detection.bboxNorm
            if (!bbox.x.isFinite() || !bbox.y.isFinite() ||
                !bbox.width.isFinite() || !bbox.height.isFinite()
            ) return@mapNotNull null
            val rect = RectF(
                bbox.x.coerceIn(0f, 1f) * imageWidth,
                bbox.y.coerceIn(0f, 1f) * imageHeight,
                (bbox.x + bbox.width).coerceIn(0f, 1f) * imageWidth,
                (bbox.y + bbox.height).coerceIn(0f, 1f) * imageHeight,
            )
            if (rect.isEmpty) return@mapNotNull null
            transform.mapRect(rect)
            if (rect.isEmpty || !rect.left.isFinite() || !rect.top.isFinite() ||
                !rect.right.isFinite() || !rect.bottom.isFinite()
            ) return@mapNotNull null
            DebugBboxOverlayView.DebugOverlayBox(
                rectPx = rect,
                label = VisualModelFormatter.label(detection, emptyList(), null),
                best = false,
                className = detection.className,
            )
        }
    }
}

package kr.co.hanium.dreamup.walksafe.visualtest

import androidx.camera.core.ImageProxy
import androidx.camera.view.transform.OutputTransform
import kr.co.hanium.dreamup.walksafe.DebugBboxOverlayView
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate

@Suppress("UNUSED_PARAMETER")
object VisualFallbackMapper {
    fun captureTransform(imageProxy: ImageProxy): OutputTransform? = null
    fun map(detections: List<DetectionCandidate>, imageWidth: Int, imageHeight: Int,
        source: OutputTransform, target: OutputTransform): List<DebugBboxOverlayView.DebugOverlayBox> = emptyList()
}

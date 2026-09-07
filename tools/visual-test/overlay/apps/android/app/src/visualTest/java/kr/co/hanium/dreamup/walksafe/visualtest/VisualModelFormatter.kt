package kr.co.hanium.dreamup.walksafe.visualtest

import java.util.Locale
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth

object VisualModelFormatter {
    fun describe(
        detections: List<DetectionCandidate>,
        outputs: List<TrackedObjectDepth>,
        frameId: Long?,
        ageMs: Long?,
        partial: Boolean,
    ): String = buildString {
        append("프레임 ${frameId ?: "대기"} · 결과 나이 ${ageMs?.let { "${it}ms" } ?: "확인 불가"}")
        append(" · ${if (partial) "부분 결과" else "완료 결과"}")
        append("\n탐지 ${detections.size}개 · 같은 프레임 Depth ${outputs.count { it.frameId == frameId }}개")
        if (detections.isEmpty()) append("\n현재 탐지된 객체가 없습니다.")
        detections.forEachIndexed { index, detection ->
            append("\n${index + 1}. ${label(detection, outputs, frameId)}")
        }
    }

    fun label(
        detection: DetectionCandidate,
        outputs: List<TrackedObjectDepth>,
        frameId: Long?,
    ): String {
        // Match the normalization used by MaskPolygonExtractor before comparing tracked boxes.
        val bbox = detection.bboxNorm
        val left = bbox.x.coerceIn(0f, 1f)
        val top = bbox.y.coerceIn(0f, 1f)
        val normalized = RectNorm(
            left, top,
            ((bbox.x + bbox.width).coerceIn(0f, 1f) - left).coerceAtLeast(0f),
            ((bbox.y + bbox.height).coerceIn(0f, 1f) - top).coerceAtLeast(0f),
        )
        val output = outputs.singleOrNull {
            it.frameId == frameId && it.className == detection.className && it.bboxNorm == normalized
        }
        val prefix = "${detection.className} · 탐지 ${percent(detection.detectionConfidence)}"
        if (output == null) return "$prefix · 거리 확인 불가 (같은 프레임의 대응 Depth 결과 없음)"
        val source = when (output.source) {
            DepthSource.ARCORE_RAW_DEPTH -> "ARCore Raw Depth"
            DepthSource.ARCORE_FULL_DEPTH -> "ARCore Full Depth"
            else -> return "$prefix · 거리 확인 불가 (ARCore 거리 아님: ${output.source.name})"
        }
        val distance = output.riskDistanceM?.takeIf { it.isFinite() && it >= 0f }
        val distanceText = distance?.let { String.format(Locale.US, "%.2fm", it) }
            ?: "확인 불가 (유효한 거리값 없음)"
        return "$prefix · 거리 $distanceText · $source" +
            " · 거리 신뢰도 ${percent(output.confidence.finalScore)} · 표본 ${output.validSampleCount}개"
    }

    private fun percent(value: Float): String = if (value.isFinite()) {
        String.format(Locale.US, "%.0f%%", value.coerceIn(0f, 1f) * 100f)
    } else {
        "확인 불가"
    }
}

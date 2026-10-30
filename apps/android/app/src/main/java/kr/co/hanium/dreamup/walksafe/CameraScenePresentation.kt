package kr.co.hanium.dreamup.walksafe

import android.graphics.RectF
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.depth.DepthPredictionPresentation
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.depth.unknown.UnknownDepthObservation

/** Shared HOME/check selection, mapping, merge and overlay update; callers retain source/UI leases. */
internal object CameraScenePresentation {
    data class UnknownSnapshot(
        val capture: CameraObjectPresentation.Capture,
        val selected: List<CameraUnknownObservation.DisplayCandidate>,
        val regions: List<CameraObjectPresentation.Region>,
        val boxes: List<DebugBboxOverlayView.DebugOverlayBox>,
        val predictionDeadlinesMs: List<Long?>,
    )

    data class RenderedUnknown(val merged: Map<Int, Int>, val visibleIndices: List<Int>, val updated: Boolean)

    fun prepareUnknown(capture: CameraObjectPresentation.Capture,
        observations: List<UnknownDepthObservation>, outputs: List<TrackedObjectDepth>,
        admitted: List<TrackedObjectDepth>, viewBasis: FloatArray): UnknownSnapshot {
        require(viewBasis.size >= 6 && viewBasis.all { it.isFinite() })
        val selected = CameraUnknownObservation.selectForDisplay(observations, outputs, admitted, limit = Int.MAX_VALUE)
        val mapped = selected.map { candidate ->
            val rect = candidate.bboxNorm
            val corners = listOf(point(viewBasis, rect.x, rect.y),
                point(viewBasis, rect.x + rect.width, rect.y),
                point(viewBasis, rect.x, rect.y + rect.height),
                point(viewBasis, rect.x + rect.width, rect.y + rect.height))
            DebugBboxOverlayView.DebugOverlayBox(RectF(corners.minOf { it.first }, corners.minOf { it.second },
                corners.maxOf { it.first }, corners.maxOf { it.second }), label(candidate), true, "unnamed-obstacle")
        }
        val regions = selected.map { candidate ->
            CameraObjectPresentation.Region(candidate.bboxNorm, candidate.feedbackId,
                candidate.output.trustedPresentationDistanceM()?.takeIf { distance ->
                    candidate.distanceM?.let { kotlin.math.abs(it - distance) <= 0.30 } == true
                }, candidate.output?.detectionConfidence ?: 0f)
        }
        val deadlines = selected.map { candidate -> candidate.output?.prediction?.let {
            DepthPredictionPresentation.validUntilMs(it, capture.capturedAtMs) ?: capture.capturedAtMs - 1L
        } }
        return UnknownSnapshot(capture, selected, regions, mapped, deadlines)
    }

    /** Called on UI after source authority checks; returns the exact set sent to the actual View. */
    fun renderUnknown(presentation: CameraObjectPresentation, snapshot: UnknownSnapshot,
        nowMs: Long, overlay: DebugBboxOverlayView,
        shouldRender: (Map<Int, Int>, List<Int>) -> Boolean = { _, _ -> true }): RenderedUnknown {
        val merged = presentation.mergedUnknownIndices(snapshot.capture, snapshot.regions, nowMs)
        val fresh = nowMs - snapshot.capture.capturedAtMs in 0L..800L
        val visible = if (!fresh) emptyList() else snapshot.boxes.indices.filter { index -> index !in merged &&
            snapshot.predictionDeadlinesMs[index]?.let { nowMs <= it } != false }.take(6)
        val updated = shouldRender(merged, visible)
        if (updated) overlay.updateMapped(visible.map { snapshot.boxes[it] }, snapshot.capture.frameId,
            snapshot.capture.capturedAtMs, sourceComplete = true, animate = false)
        return RenderedUnknown(merged, visible, updated)
    }

    private fun label(candidate: CameraUnknownObservation.DisplayCandidate): String {
        val observation = candidate.observation
        val output = candidate.output
        if (observation == null) {
            val warning = requireNotNull(output)
            val kind = if (warning.source == DepthSource.ARCORE_FULL_DEPTH) "Full 추정" else "Raw 측정"
            val alert = if (warning.userFacing.messageLevel == MessageLevel.STOP) "정지 경고 후보" else "주의 경고 후보"
            return String.format(Locale.US, "근접 경고 영역 · %s %.2fm · %s", kind, candidate.distanceM, alert)
        }
        val distance = output?.rayDistanceM?.toDouble() ?: candidate.distanceM
        val kind = if (output?.prediction != null) "단기 예측" else if (observation.depth.source == "ARCORE_FULL_DEPTH") "Full 추정" else "Raw 측정"
        val alert = when (output?.userFacing?.messageLevel) {
            MessageLevel.STOP -> " · 정지 경고 후보"
            MessageLevel.WARNING -> " · 주의 경고 후보"
            else -> ""
        }
        return (if (observation.proximityDistanceM != null) "근접 표면 · " else "전방 영역 · ") + (distance?.let {
            String.format(Locale.US, "%s %.2fm", kind, it)
        } ?: "거리 미확인") + (observation.metricExtent?.takeIf {
            observation.proximityDistanceM == null && distance != null
        }?.let {
            String.format(Locale.US, " · 관측 폭 %.2fm/높이 %.2fm", it.widthM, it.heightM)
        } ?: "") + (output?.prediction?.let {
            String.format(Locale.US, " ±%.2fm · 공백 %dms", it.errorBoundM, it.predictionAgeMs)
        } ?: "") + alert
    }

    private fun TrackedObjectDepth?.trustedPresentationDistanceM(): Double? = this?.takeIf {
        it.source.metric && it.confidence.hardGate > 0f && it.confidence.freshnessQuality > 0f
    }?.riskDistanceM?.takeIf { it.isFinite() && it > 0f }?.toDouble()

    private fun point(basis: FloatArray, x: Float, y: Float) =
        Pair(basis[0] + x * (basis[2] - basis[0]) + y * (basis[4] - basis[0]),
            basis[1] + x * (basis[3] - basis[1]) + y * (basis[5] - basis[1]))
}

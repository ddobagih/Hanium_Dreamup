package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.inference.tracking.*

/** Synthetic fixture declares one known physical point; it does not exercise native LK. */
internal fun knownMaterialPointObservation(
    source: VisualFrameKey, target: VisualFrameKey, candidate: DetectionCandidate,
    id: VisualMaterialPointId, point: Point2 = candidate.bboxNorm.center, quality: Float = .95f,
): CurrentTrackedFrameObservation = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(
    VisualTrackingResult(source, target, listOf(VisualTrackingObservation(
        0, source, target, VisualTrackingStatus.TRACKED, candidate, quality, null,
        Point2(0f, 0f), 39, 39, 0f, false,
        materialPoint = VisualMaterialPoint(id, target, point),
    )), VisualTrackingMetrics(0L, 1, 0L, false, 2, 0)),
    listOf(candidate), true, target.capturedAtElapsedRealtimeMs,
))

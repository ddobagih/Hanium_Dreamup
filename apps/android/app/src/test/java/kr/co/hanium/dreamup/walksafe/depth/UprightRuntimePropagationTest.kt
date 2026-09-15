package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.UprightCameraImage
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingMetrics
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingObservation
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingResult
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class UprightRuntimePropagationTest {
    @Test
    fun capturedRotationReachesFrozenDetectorAndCurrentTrackedDepthDecisions() {
        for (turns in listOf(0, 1, 2, 3, null)) {
            val detection = DetectionCandidate("person", .95f,
                UprightCameraImage.toSensor(RectNorm(.42f, .72f, .16f, .16f), turns ?: 1))
            val frozen = ObjectDepthRuntimePipeline().process(snapshot, key.frameId, 1_000L,
                listOf(detection), mapper = mapper, imageQuarterTurns = turns).single()
            val observation = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(
                VisualTrackingResult(key, key, listOf(VisualTrackingObservation(
                    sourceIndex = 0, detectorSourceKey = key, trackedTargetKey = key,
                    status = VisualTrackingStatus.TRACKED, geometry = detection, trackingQuality = .95f,
                    failure = null, translationNorm = Point2(0f, 0f), originalFeatureCount = 8,
                    survivingFeatureCount = 8, residualPx = 0f, polygonConstrained = false,
                )), VisualTrackingMetrics(0L, 1, 0L, false, 1, 0)),
                listOf(detection), true, key.capturedAtElapsedRealtimeMs,
            ))
            val tracked = ObjectDepthRuntimePipeline().processTrackedObservation(snapshot, observation,
                mapper, key.frameId, key.capturedAtElapsedRealtimeMs, imageQuarterTurns = turns).single()
            for (output in listOf(frozen, tracked)) {
                assertEquals(if (turns == null) .30f else 1f, output.confidence.corridorQuality, 0f)
                assertEquals(detection.bboxNorm, output.bboxNorm)
                assertEquals(DepthSource.ARCORE_RAW_DEPTH, output.source)
                assertEquals(1.6f, output.zDistanceM!!, .00001f)
            }
        }
    }

    private val key = VisualFrameKey(1L, 1_000_000_000L, 1_000_000_001L, 5_000L, 1L)
    private val snapshot = DepthFrameSnapshot(key.frameId,
        rawDepth = DepthImage16(100, 100, IntArray(10_000) { 1_600 }),
        rawConfidence = ConfidenceImage8(100, 100, ByteArray(10_000) { 255.toByte() }), fullDepth = null,
        rawDepthTimestampNs = key.cameraTimestampNs, cameraImageTimestampNs = key.cameraTimestampNs)
    private val mapper = LetterboxCoordinateMapper(
        ModelInputTransform(100, 100, 100, 100, 1f, 0f, 0f), ImageSize(100, 100))
}

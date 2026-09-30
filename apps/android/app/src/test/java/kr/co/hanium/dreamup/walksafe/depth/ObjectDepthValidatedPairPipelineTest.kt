package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingMetrics
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingObservation
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingResult
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObjectDepthValidatedPairPipelineTest {
    private val sourceKey = key(1_000L)
    private val targetKey = key(1_040L)
    private val detection = DetectionCandidate("person", 0.9f, RectNorm(0.2f, 0.2f, 0.5f, 0.5f))
    private val mapper = LetterboxCoordinateMapper(
        ModelInputTransform(100, 100, 100, 100, 1f, 0f, 0f), ImageSize(100, 100),
    )

    @Test
    fun missingOrMismatchedRawPairFallsBackToFullDepthInBothPipelineEntrypoints() {
        val valid = snapshot()
        listOf(
            valid.copy(rawDepthTimestampNs = null),
            valid.copy(rawConfidenceTimestampNs = null),
            valid.copy(rawConfidenceTimestampNs = targetKey.cameraTimestampNs + 1L),
            valid.copy(rawConfidence = ConfidenceImage8(99, 100, ByteArray(9_900) { 255.toByte() })),
        ).forEach { invalid ->
            outputs(invalid).forEach { output ->
                assertEquals(DepthSource.ARCORE_FULL_DEPTH, output.source)
                assertEquals(2.0f, output.depthMedianM!!, 0.01f)
            }
        }
    }

    @Test
    fun verifiedRawPairStillReachesEstimatorInBothPipelineEntrypoints() {
        listOf(
            snapshot(),
            snapshot().copy(
                rawDepthTimestampNs = targetKey.cameraTimestampNs - 1L,
                rawConfidenceTimestampNs = targetKey.cameraTimestampNs - 1L,
            ),
        ).forEach { valid ->
            outputs(valid).forEach { output ->
                assertEquals(DepthSource.ARCORE_RAW_DEPTH, output.source)
                assertEquals(1.2f, output.depthMedianM!!, 0.01f)
            }
        }
    }

    @Test
    fun rejectedRawPairWithoutFullDepthKeepsNonMetricFallback() {
        outputs(snapshot().copy(rawConfidenceTimestampNs = null, fullDepth = null)).forEach { output ->
            assertEquals(DepthSource.POLYGON_TREND_PSEUDO_DEPTH, output.source)
            assertNull(output.riskDistanceM)
        }
    }

    private fun outputs(snapshot: DepthFrameSnapshot): List<TrackedObjectDepth> {
        val report = ObjectDepthRuntimePipeline().process(
            snapshot = snapshot,
            frameId = targetKey.frameId,
            timestampMs = 1_040L,
            detections = listOf(detection),
            mapper = mapper,
            mapperFrameId = targetKey.frameId,
        ).single()
        val visual = ObjectDepthRuntimePipeline().processTrackedObservation(
            snapshot = snapshot,
            observation = observation(),
            mapper = mapper,
            mapperFrameId = targetKey.frameId,
            nowElapsedRealtimeMs = targetKey.capturedAtElapsedRealtimeMs,
        ).single()
        return listOf(report, visual)
    }

    private fun snapshot() = DepthFrameSnapshot(
        frameTimestampNs = targetKey.frameId,
        rawDepth = DepthImage16(100, 100, IntArray(10_000) { 1_200 }),
        rawConfidence = ConfidenceImage8(100, 100, ByteArray(10_000) { 255.toByte() }),
        fullDepth = DepthImage16(100, 100, IntArray(10_000) { 2_000 }),
        rawDepthTimestampNs = targetKey.cameraTimestampNs,
        rawConfidenceTimestampNs = targetKey.cameraTimestampNs,
        fullDepthTimestampNs = targetKey.cameraTimestampNs,
        cameraImageTimestampNs = targetKey.cameraTimestampNs,
    )

    private fun observation(): CurrentTrackedFrameObservation = requireNotNull(
        CurrentTrackedFrameObservation.fromTrackingResult(
            VisualTrackingResult(sourceKey, targetKey, listOf(VisualTrackingObservation(
                sourceIndex = 0,
                detectorSourceKey = sourceKey,
                trackedTargetKey = targetKey,
                status = VisualTrackingStatus.TRACKED,
                geometry = detection,
                trackingQuality = 0.95f,
                failure = null,
                translationNorm = Point2(0f, 0f),
                originalFeatureCount = 8,
                survivingFeatureCount = 8,
                residualPx = 0f,
                polygonConstrained = false,
            )), VisualTrackingMetrics(0L, 1, 0L, false, 2, 0)),
            listOf(detection), true, targetKey.capturedAtElapsedRealtimeMs,
        ),
    )

    private fun key(atMs: Long) = VisualFrameKey(
        7L, atMs * 1_000_000L, atMs * 1_000_000L + 400_000L, atMs + 50_000L, 3L,
    )
}

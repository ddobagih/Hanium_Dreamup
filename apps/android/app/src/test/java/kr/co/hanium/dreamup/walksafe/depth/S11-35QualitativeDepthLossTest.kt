package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.device.DeviceGateState
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.feedback.toFeedbackCandidate
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingMetrics
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingObservation
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingResult
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class S1135QualitativeDepthLossTest {
    @Test
    fun currentArCoreFrameWithOneObjectDepthLossEmitsOnlyQualitativeCaution() {
        val pipeline = ObjectDepthRuntimePipeline()
        var outputs = emptyList<TrackedObjectDepth>()
        repeat(4) { index ->
            val atMs = 1_000L + index * 300L
            val width = 0.20f + index * 0.04f
            val height = 0.40f + index * 0.08f
            outputs = pipeline.processPrealignedForTest(
                snapshot = partialDepthFrame(atMs),
                frameId = atMs * 1_000_000L,
                timestampMs = atMs,
                detections = listOf(
                    DetectionCandidate("person", 0.9f,
                        RectNorm(0.5f - width / 2f, 0.48f - height / 2f, width, height)),
                    DetectionCandidate("bench", 0.9f, RectNorm(0.05f, 0.25f, 0.18f, 0.40f)),
                ),
            )
        }
        val pseudo = outputs.single { it.className == "person" }
        val metric = outputs.single { it.className == "bench" }
        assertEquals(DepthSource.ARCORE_RAW_DEPTH, metric.source)
        assertNotNull(metric.riskDistanceM)
        assertEquals(DepthSource.POLYGON_TREND_PSEUDO_DEPTH, pseudo.source)
        assertEquals(Trend.APPROACHING, pseudo.trend)
        assertTrue(pseudo.confidence.finalScore > 0f)
        assertTrue(pseudo.confidence.finalScore < 0.35f)
        assertEquals(4, pseudo.trackAgeFrames)
        assertTrue(pseudo.trackStableMs >= 700L)
        assertEquals(MessageLevel.CAUTION, pseudo.userFacing.messageLevel)
        assertFalse(pseudo.userFacing.message.isNullOrBlank())
        assertFalse(pseudo.userFacing.message!!.any(Char::isDigit))
        assertNull(pseudo.riskDistanceM)
        assertNull(pseudo.timeToCollisionMs)
        assertNull(pseudo.userFacing.stepsAhead)

        // Legacy estimation alone has no current visual capture lease.
        assertFalse(pseudo.isCurrentQualitativeCaution(1_900L))
    }

    @Test
    fun currentVisualLeaseOutlivesPreviousMetricDepthWithoutBecomingMetricFeedback() {
        val (pseudo, observation) = trackedSequence(personMetricBeforeLast = true, visualQuality = 0.95f)
        val sourceDeadline = observation.sourceKey.capturedAtElapsedRealtimeMs +
            CurrentTrackedFrameObservation.MAX_SOURCE_AGE_MS
        val lateNow = sourceDeadline - 150L
        assertEquals(DepthSource.POLYGON_TREND_PSEUDO_DEPTH, pseudo.source)
        assertEquals(1_600L, pseudo.depthObservedAtMs)
        assertEquals(MessageLevel.CAUTION, pseudo.userFacing.messageLevel)
        assertTrue(observation.isFreshAt(lateNow))
        assertEquals(sourceDeadline, pseudo.sourceValidUntilElapsedRealtimeMs)
        assertTrue(pseudo.isCurrentQualitativeCaution(lateNow))
        val gate = readyGate(pseudo.isCurrentQualitativeCaution(lateNow))
        assertTrue(gate.alertsAllowed)
        assertFalse(gate.reportCandidatesAllowed)
        val action = WalkSafeFeedbackPolicy().evaluate(pseudo.toFeedbackCandidate(), gate.alertsAllowed, lateNow)
        assertNotNull(action)
        assertEquals(MessageLevel.CAUTION, action!!.level)
        assertEquals("fresh_qualitative_depth_caution", action.reason)
        assertNull(action.vibrationPatternMs)
        assertNull(pseudo.riskDistanceM)
        assertNull(pseudo.timeToCollisionMs)
        assertNull(pseudo.userFacing.stepsAhead)
        assertFalse(gate.copy(staleReason = "source_stale").alertsAllowed)
        assertFalse(gate.copy(arSessionRunning = false).alertsAllowed)
        assertFalse(pseudo.isCurrentQualitativeCaution(sourceDeadline + 1L))
        assertNull(WalkSafeFeedbackPolicy().evaluate(pseudo.toFeedbackCandidate(), true, sourceDeadline + 1L))
    }

    @Test
    fun lowPositiveVisualQualityCannotTurnGrowingBoxIntoDeliveredCaution() {
        val (lowQuality, lowObservation) = trackedSequence(personMetricBeforeLast = false, visualQuality = 0.05f)
        assertEquals(DepthSource.POLYGON_TREND_PSEUDO_DEPTH, lowQuality.source)
        assertEquals(Trend.APPROACHING, lowQuality.trend)
        assertEquals(MessageLevel.NONE, lowQuality.userFacing.messageLevel)
        assertNull(lowQuality.userFacing.message)
        assertFalse(lowQuality.isCurrentQualitativeCaution(lowObservation.targetKey.capturedAtElapsedRealtimeMs))

        val (accepted, acceptedObservation) = trackedSequence(personMetricBeforeLast = false, visualQuality = 0.95f)
        assertEquals(MessageLevel.CAUTION, accepted.userFacing.messageLevel)
        assertTrue(accepted.isCurrentQualitativeCaution(acceptedObservation.targetKey.capturedAtElapsedRealtimeMs))
    }

    @Test
    fun expiredOrBlockedQualitativeEvidenceCannotOpenAlertOrReportGate() {
        val pipeline = ObjectDepthRuntimePipeline()
        val atMs = 1_000L
        val pseudo = pipeline.processPrealignedForTest(
            partialDepthFrame(atMs), atMs * 1_000_000L, atMs,
            listOf(DetectionCandidate("person", 0.9f, RectNorm(0.4f, 0.25f, 0.2f, 0.4f))),
        ).single().copy(sourceValidUntilElapsedRealtimeMs = 1_500L)
        assertFalse(pseudo.isCurrentQualitativeCaution(1_000L)) // no stable approach
        assertFalse(pseudo.isCurrentQualitativeCaution(1_501L)) // expired lease
        assertFalse(readyGate(false).alertsAllowed)
        assertFalse(readyGate(true).copy(staleReason = "source_stale").alertsAllowed)
        assertFalse(readyGate(true).copy(arSessionRunning = false).alertsAllowed)
        assertFalse(readyGate(true).copy(arCoreSupported = false).alertsAllowed)
        assertFalse(readyGate(true).copy(depthSupported = false).alertsAllowed)
        assertFalse(readyGate(true).reportCandidatesAllowed)
        assertTrue(readyGate(false).copy(freshDepthObject = true).reportCandidatesAllowed)
        assertNull(WalkSafeFeedbackPolicy().evaluate(pseudo.toFeedbackCandidate(), true, 1_501L))
    }

    private fun trackedSequence(
        personMetricBeforeLast: Boolean,
        visualQuality: Float,
    ): Pair<TrackedObjectDepth, CurrentTrackedFrameObservation> {
        val pipeline = ObjectDepthRuntimePipeline()
        val mapper = LetterboxCoordinateMapper(
            ModelInputTransform(100, 100, 100, 100, 1f, 0f, 0f), ImageSize(100, 100),
        )
        var latest: TrackedObjectDepth? = null
        var lastObservation: CurrentTrackedFrameObservation? = null
        repeat(4) { index ->
            val atMs = 1_000L + index * 300L
            val source = visualKey(atMs - 40L)
            val target = visualKey(atMs)
            val width = 0.20f + index * 0.04f
            val height = 0.40f + index * 0.08f
            val detections = listOf(
                DetectionCandidate("person", 0.9f,
                    RectNorm(0.5f - width / 2f, 0.48f - height / 2f, width, height)),
                DetectionCandidate("bench", 0.9f, RectNorm(0.05f, 0.25f, 0.18f, 0.40f)),
            )
            val result = VisualTrackingResult(
                source, target,
                detections.mapIndexed { detectionIndex, detection ->
                    VisualTrackingObservation(
                        sourceIndex = detectionIndex,
                        detectorSourceKey = source,
                        trackedTargetKey = target,
                        status = VisualTrackingStatus.TRACKED,
                        geometry = detection,
                        trackingQuality = visualQuality,
                        failure = null,
                        translationNorm = Point2(0f, 0f),
                        originalFeatureCount = 8,
                        survivingFeatureCount = 8,
                        residualPx = 0f,
                        polygonConstrained = false,
                    )
                },
                VisualTrackingMetrics(0L, 2, 0L, false, 2, 0),
            )
            val observation = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(
                result, detections, true, target.capturedAtElapsedRealtimeMs,
            ))
            val snapshot = partialDepthFrame(atMs, personMetricBeforeLast && index < 3).copy(
                rawDepthTimestampNs = target.cameraTimestampNs,
                rawConfidenceTimestampNs = target.cameraTimestampNs,
                cameraImageTimestampNs = target.cameraTimestampNs,
            )
            val outputs = pipeline.processTrackedObservation(
                snapshot, observation, mapper, target.frameId, target.capturedAtElapsedRealtimeMs,
            )
            latest = outputs.single { it.className == "person" }
            lastObservation = observation
        }
        return requireNotNull(latest) to requireNotNull(lastObservation)
    }

    private fun visualKey(atMs: Long) = VisualFrameKey(
        7L, atMs * 1_000_000L, atMs * 1_000_000L + 400_000L, atMs + 10_000L, 3L,
    )

    private fun partialDepthFrame(atMs: Long, personMetric: Boolean = false): DepthFrameSnapshot {
        val frameNs = atMs * 1_000_000L
        return DepthFrameSnapshot(
            frameTimestampNs = frameNs,
            rawDepth = DepthImage16(100, 100, IntArray(10_000) { pixel ->
                if (personMetric || pixel % 100 < 30) 2_000 else 0
            }),
            rawConfidence = ConfidenceImage8(100, 100, ByteArray(10_000) { 255.toByte() }),
            fullDepth = null,
            rawDepthTimestampNs = frameNs,
            rawConfidenceTimestampNs = frameNs,
            cameraImageTimestampNs = frameNs,
        )
    }

    private fun readyGate(freshQualitativeObject: Boolean) = DeviceGateState(
        cameraPermissionGranted = true,
        arCoreSupported = true,
        depthSupported = true,
        tfliteConfigLoaded = true,
        detectorAvailable = true,
        arSessionRunning = true,
        freshQualitativeObject = freshQualitativeObject,
    )
}

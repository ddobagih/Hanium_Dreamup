package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.*
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualMaterialPoint
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualMaterialPointId
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import org.junit.Test

class RiskEvidenceRegressionTest {
    @Test fun shorterDistanceCannotHideAnUrgentTtc() {
        val levels = listOf(4f, 2f, .8f).map { distance ->
            MessagePolicy().evaluate(MetricDepthDecision("car", DepthSource.ARCORE_RAW_DEPTH,
                distance, Trend.APPROACHING, .65f, timeToCollisionMs = 500L), 1_000L).userFacing.messageLevel
        }
        assertEquals(listOf(MessageLevel.STOP, MessageLevel.STOP, MessageLevel.STOP), levels)
    }

    @Test fun warningDistanceDoesNotHideTtcAcrossConfidenceBoundaries() {
        for (confidence in listOf(.55f, .65f, .75f, .95f)) {
            for (distance in listOf(.8f, 1.2f, 2f, 2.5f, 3f, 4f)) {
                val decision = MessagePolicy().evaluate(MetricDepthDecision("car", DepthSource.ARCORE_RAW_DEPTH,
                    distance, Trend.APPROACHING, confidence, timeToCollisionMs = 500L), 1_000L)
                assertEquals("confidence=$confidence distance=$distance", MessageLevel.STOP, decision.userFacing.messageLevel)
            }
        }
    }

    @Test fun invalidNegativeTtcDoesNotManufactureStop() {
        assertEquals(MessageLevel.NONE, MessagePolicy().evaluate(MetricDepthDecision("car",
            DepthSource.ARCORE_RAW_DEPTH, 4f, Trend.APPROACHING, .65f,
            timeToCollisionMs = -1L), 1_000L).userFacing.messageLevel)
    }

    @Test fun trackingRiskAlsoCombinesProximityAndTtc() {
        val decision = TrackingRiskPolicy().evaluate(TrackRiskSignals(3, 700L,
            DistanceObservation(1_000L, 2f, DepthSource.ARCORE_RAW_DEPTH, .65f), Trend.APPROACHING,
            TtcEstimate(500L, .65f, TtcSource.METRIC_DISTANCE, "measured"), null, null))
        assertEquals(MessageLevel.STOP, decision.messageLevel)
    }

    @Test fun majorityBackgroundCannotEraseSupportedNearPopulation() {
        val stats = sample { x, _ -> if (x < 4) 800 else 4_000 }
        assertEquals(160, stats.validSampleCount)
        assertNull(stats.medianM)
        assertNull(stats.p20M)
    }

    @Test fun isolatedNearNoiseIsNotPromotedToObjectDepth() {
        val stats = sample { x, y -> if (x == 0 && y == 0) 800 else 4_000 }
        assertEquals(4f, requireNotNull(stats.medianM), 0f)
        assertEquals(4f, requireNotNull(stats.p20M), 0f)
    }

    @Test fun connectedNearBorderIsNotDiscardedAsIsolatedNoise() {
        val stats = sample { x, y -> if (x == 0 && y < 4) 800 else 4_000 }
        assertTrue(stats.foregroundAmbiguous)
        assertNull(stats.medianM)
        assertNull(stats.p20M)
    }

    @Test fun fullDepthSupportGateCannotDiscardTheOriginalRiskPercentile() {
        val stats = sample(minSamples = 50) { x, _ -> if (x < 4) 800 else 4_000 }
        assertNull(stats.medianM)
        assertNull(stats.p20M)
    }

    @Test fun stationaryCameraStillReportsMeasuredObjectApproach() {
        val tracker = ObjectTracker()
        val estimator = ObjectDepthEstimator(tracker = tracker)
        val geometry = MaskPolygonExtractor().extract(DetectionCandidate("car", .95f, RectNorm(0f, 0f, 1f, 1f)))
        val mapper = LetterboxCoordinateMapper(ModelInputTransform(20, 20, 20, 20, 1f, 0f, 0f), ImageSize(20, 20))
        // Uniform-depth fixture observes the same physical surface point in every frame.
        val pointId = VisualMaterialPointId()
        val results = listOf(4_000, 3_500, 3_000, 2_500).mapIndexed { index, mm ->
            val at = 1_000L + index * 500L
            val pose = CameraPoseEvidence(1L, at, 0f, 0f, 0f, 0f, 0f, -1f,
                CameraImageProjection(20, 20, 14f, 14f, 10f, 10f, 1f, 0f, 0f, 0f, 1f, 0f))
            estimator.estimate(ObjectDepthInput(at * 1_000_000L, at, geometry,
                tracker.update(listOf(geometry), at).single(), mapper,
                materialPoint = VisualMaterialPoint(pointId,
                    VisualFrameKey(1L, at * 1_000_000L, at * 1_000_000L, at, 1L), Point2(.5f, .5f)),
                rawDepth = DepthImage16(20, 20, IntArray(400) { mm }),
                rawConfidence = ConfidenceImage8(20, 20, ByteArray(400) { 255.toByte() }),
                rawDepthMatchesCameraFrame = true, rawDepthTimestampNs = at * 1_000_000L,
                motionContext = MotionContext(egoForwardSpeedMps = 0f, cameraPoseEvidence = pose)))
        }
        assertEquals(ObjectMotion.OBJECT_APPROACHING, results.last().objectMotion)
        assertEquals(MessageLevel.STOP, results.last().userFacing.messageLevel)
        assertEquals(2_500L, results.last().timeToCollisionMs)
    }

    @Test fun fullSmoothingCannotEraseRawForegroundAmbiguity() {
        val tracker = ObjectTracker()
        val geometry = MaskPolygonExtractor().extract(DetectionCandidate("car", .95f, RectNorm(0f, 0f, 1f, 1f)))
        val track = listOf(0L, 500L, 1_000L).map { tracker.update(listOf(geometry), it).single() }.last()
        val result = ObjectDepthEstimator(tracker = tracker).estimate(ObjectDepthInput(
            1_000_000_000L, 1_000L, geometry, track,
            LetterboxCoordinateMapper(ModelInputTransform(20, 20, 20, 20, 1f, 0f, 0f), ImageSize(20, 20)),
            // Keep the foreground inside the eroded object polygon, not its discarded border.
            rawDepth = DepthImage16(20, 20, IntArray(400) { if (it % 20 in 7..10) 800 else 4_000 }),
            rawConfidence = ConfidenceImage8(20, 20, ByteArray(400) { 255.toByte() }),
            fullDepth = DepthImage16(20, 20, IntArray(400) { 4_000 }),
        ))
        assertNull(result.riskDistanceM)
        assertEquals(0f, result.confidence.finalScore, 0f)
        assertTrue(track.distanceHistory.isEmpty())
    }

    @Test fun fullDepthCanReplaceSupportedButUnusableRawWithoutRecordingBoth() {
        val tracker = ObjectTracker()
        val geometry = MaskPolygonExtractor().extract(DetectionCandidate("car", .95f, RectNorm(0f, 0f, 1f, 1f)))
        var track = tracker.update(listOf(geometry), 0L).single()
        track = tracker.update(listOf(geometry), 500L).single()
        track = tracker.update(listOf(geometry), 1_000L).single()
        val result = ObjectDepthEstimator(tracker = tracker).estimate(ObjectDepthInput(
            frameId = 1_000_000_000L, timestampMs = 1_000L, geometry = geometry, track = track,
            mapper = LetterboxCoordinateMapper(ModelInputTransform(20, 20, 20, 20, 1f, 0f, 0f), ImageSize(20, 20)),
            rawDepth = DepthImage16(20, 20, IntArray(400) { 2_000 }),
            rawConfidence = ConfidenceImage8(20, 20, ByteArray(400) { 255.toByte() }),
            rawDepthFreshnessQuality = .1f,
            fullDepth = DepthImage16(20, 20, IntArray(400) { 2_000 }),
        ))
        assertEquals(DepthSource.ARCORE_FULL_DEPTH, result.source)
        assertEquals(1, track.distanceHistory.size)
        assertEquals(DepthSource.ARCORE_FULL_DEPTH, track.distanceHistory.single().source)
    }

    private fun sample(minSamples: Int = 30, value: (Int, Int) -> Int): DepthStats = DepthSampler().sampleObjectDepth(
        DepthImage16(17, 11, IntArray(187) { value(it % 17, it / 17) }), null,
        bboxPolygon(RectNorm(0f, 0f, 1f, 1f), 0f),
        DepthSampleOptions(minConfidence = null, polygonErosionRatio = 0f, minValidSamples = minSamples),
    )
}

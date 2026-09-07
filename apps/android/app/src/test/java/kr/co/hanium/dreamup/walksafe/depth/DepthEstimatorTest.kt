package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthEstimatorTest {
    private val mapper = LetterboxCoordinateMapper(
        transform = ModelInputTransform(
            imageWidth = 10,
            imageHeight = 10,
            modelWidth = 10,
            modelHeight = 10,
            scale = 1f,
            padX = 0f,
            padY = 0f,
        ),
        depthSize = ImageSize(10, 10),
    )

    @Test
    fun estimatorPrefersRawDepthAndFallsBackToFullDepthWhenConfidenceMissing() {
        val geometry = MaskPolygonExtractor().extract(
            DetectionCandidate(
                className = "person",
                detectionConfidence = 0.9f,
                bboxNorm = RectNorm(0f, 0f, 1f, 1f),
            ),
        )
        val tracker = ObjectTracker()
        val track = tracker.update(listOf(geometry), timestampMs = 0L).first()
        val estimator = ObjectDepthEstimator(tracker = tracker)
        val raw = DepthImage16(10, 10, IntArray(100) { 1_000 })
        val rawConfidence = ConfidenceImage8(10, 10, ByteArray(100) { 255.toByte() })
        val full = DepthImage16(10, 10, IntArray(100) { 1_700 })

        val rawResult = estimator.estimate(
            ObjectDepthInput(
                frameId = 1L,
                timestampMs = 1_000L,
                geometry = geometry,
                track = track,
                mapper = mapper,
                rawDepth = raw,
                rawConfidence = rawConfidence,
                fullDepth = full,
            ),
        )
        val fullFallbackResult = estimator.estimate(
            ObjectDepthInput(
                frameId = 2L,
                timestampMs = 2_000L,
                geometry = geometry,
                track = track,
                mapper = mapper,
                rawDepth = raw,
                rawConfidence = null,
                fullDepth = full,
            ),
        )

        assertEquals(DepthSource.ARCORE_RAW_DEPTH, rawResult.source)
        assertEquals(1.0f, rawResult.depthMedianM!!, 0.01f)
        assertEquals(DepthSource.ARCORE_FULL_DEPTH, fullFallbackResult.source)
        assertEquals(1.7f, fullFallbackResult.depthMedianM!!, 0.01f)
    }

    @Test
    fun reprojectedRawDepthRemainsMetricButIsSoftDownweighted() {
        val geometry = MaskPolygonExtractor().extract(
            DetectionCandidate(
                className = "person",
                detectionConfidence = 0.9f,
                bboxNorm = RectNorm(0f, 0f, 1f, 1f),
            ),
        )
        val freshTracker = ObjectTracker()
        val freshTrack = freshTracker.update(listOf(geometry), timestampMs = 0L).first()
        val staleTracker = ObjectTracker()
        val staleTrack = staleTracker.update(listOf(geometry), timestampMs = 0L).first()
        val raw = DepthImage16(10, 10, IntArray(100) { 1_000 })
        val confidence = ConfidenceImage8(10, 10, ByteArray(100) { 255.toByte() })
        val fresh = ObjectDepthEstimator(tracker = freshTracker).estimate(
            ObjectDepthInput(
                frameId = 1L,
                timestampMs = 1_000L,
                geometry = geometry,
                track = freshTrack,
                mapper = mapper,
                rawDepth = raw,
                rawConfidence = confidence,
            ),
        )
        val reprojected = ObjectDepthEstimator(tracker = staleTracker).estimate(
            ObjectDepthInput(
                frameId = 2L,
                timestampMs = 1_000L,
                geometry = geometry,
                track = staleTrack,
                mapper = mapper,
                rawDepth = raw,
                rawConfidence = confidence,
                rawDepthFreshnessQuality = 0.70f,
            ),
        )

        assertEquals(DepthSource.ARCORE_RAW_DEPTH, reprojected.source)
        assertEquals(fresh.riskDistanceM!!, reprojected.riskDistanceM!!, 0f)
        assertTrue(reprojected.confidence.finalScore < fresh.confidence.finalScore)
    }

    @Test
    fun pseudoDepthDoesNotExposeDistanceOrSteps() {
        val geometry = MaskPolygonExtractor().extract(
            DetectionCandidate(
                className = "person",
                detectionConfidence = 0.9f,
                bboxNorm = RectNorm(0.3f, 0.3f, 0.3f, 0.4f),
            ),
        )
        val tracker = ObjectTracker()
        val track = tracker.update(listOf(geometry), timestampMs = 0L).first()
        val result = ObjectDepthEstimator(tracker = tracker).estimate(
            ObjectDepthInput(
                frameId = 1L,
                timestampMs = 1_000L,
                geometry = geometry,
                track = track,
                mapper = mapper,
            ),
        )

        assertEquals(DepthSource.POLYGON_TREND_PSEUDO_DEPTH, result.source)
        assertNull(result.riskDistanceM)
        assertNull(result.userFacing.stepsAhead)
    }

    @Test
    fun stableTrackerComputesApproachSpeedOnlyAfterStableMetricHistory() {
        val tracker = ObjectTracker()
        val extractor = MaskPolygonExtractor()
        lateinit var track: TrackState
        listOf(4.0f, 3.5f, 3.0f, 2.5f).forEachIndexed { index, distance ->
            val geometry = extractor.extract(
                DetectionCandidate(
                    className = "person",
                    detectionConfidence = 0.9f,
                    bboxNorm = RectNorm(0.35f, 0.2f + index * 0.01f, 0.3f, 0.4f),
                ),
            )
            track = tracker.update(listOf(geometry), timestampMs = index * 1_000L).first()
            tracker.recordDistance(track, distance, DepthSource.ARCORE_RAW_DEPTH, 0.9f, index * 1_000L)
        }

        val kinematics = tracker.approachKinematics(track, currentDistanceM = 2.5f, source = DepthSource.ARCORE_RAW_DEPTH)

        assertTrue(track.stable)
        assertEquals(Trend.APPROACHING, kinematics.trend)
        assertTrue(kinematics.approachSpeedMps!! > 0.25f)
    }
}

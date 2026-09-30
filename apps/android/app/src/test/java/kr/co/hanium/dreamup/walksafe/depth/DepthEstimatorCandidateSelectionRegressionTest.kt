package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthEstimatorCandidateSelectionRegressionTest {
    @Test
    fun usableRawKeepsPriorityAndOnlyItsObservationIsRecorded() {
        val fixture = Fixture()
        val result = fixture.estimate(rawMm = 2_000, fullMm = 3_000)
        assertEquals(DepthSource.ARCORE_RAW_DEPTH, result.source)
        assertEquals(2f, result.riskDistanceM!!, 0f)
        assertEquals(listOf(DepthSource.ARCORE_RAW_DEPTH), fixture.track.distanceHistory.map { it.source })
    }

    @Test
    fun weakSupportedRawUsesUsableFullWithoutRecordingRaw() {
        val fixture = Fixture()
        val result = fixture.estimate(rawMm = 2_000, fullMm = 3_000, rawFreshness = .1f)
        assertEquals(DepthSource.ARCORE_FULL_DEPTH, result.source)
        assertTrue(result.confidence.finalScore >= .60f)
        assertEquals(3f, result.riskDistanceM!!, 0f)
        assertEquals(listOf(DepthSource.ARCORE_FULL_DEPTH), fixture.track.distanceHistory.map { it.source })
    }

    @Test
    fun reprojectedRawStillWorksWhenItsCandidateConfidenceRemainsUsable() {
        val fresh = Fixture().estimate(rawMm = 2_000, fullMm = null)
        val fixture = Fixture()
        val reprojected = fixture.estimate(rawMm = 2_000, fullMm = null, rawFreshness = .70f)
        assertEquals(DepthSource.ARCORE_RAW_DEPTH, reprojected.source)
        assertTrue(reprojected.confidence.finalScore >= .55f)
        assertTrue(reprojected.confidence.finalScore < fresh.confidence.finalScore)
        assertEquals(fresh.riskDistanceM!!, reprojected.riskDistanceM!!, 0f)
        assertEquals(1, fixture.track.distanceHistory.size)
    }

    @Test
    fun weakRawWithoutFullReturnsQualitativeDepthWithoutRecordingAMetricSample() {
        val fixture = Fixture()
        assertQualitative(fixture.estimate(rawMm = 2_000, fullMm = null, rawFreshness = .1f))
        assertTrue(fixture.track.distanceHistory.isEmpty())
    }

    @Test
    fun weakFullWithoutRawAlsoRequiresUsableCandidateConfidence() {
        val fixture = Fixture()
        assertQualitative(fixture.estimate(rawMm = null, fullMm = 2_000, weakCommonQuality = true))
        assertTrue(fixture.track.distanceHistory.isEmpty())
    }

    @Test
    fun neitherUsableCandidateReturnsQualitativeDepthWithoutRecordingEither() {
        val fixture = Fixture()
        assertQualitative(fixture.estimate(rawMm = 2_000, fullMm = 3_000,
            rawFreshness = .1f, weakCommonQuality = true))
        assertTrue(fixture.track.distanceHistory.isEmpty())
    }

    @Test
    fun rejectedCandidatesDoNotAppendToAnExistingDistanceHistoryOrReuseItsWarning() {
        val fixture = Fixture()
        assertEquals(MessageLevel.STOP, fixture.estimate(rawMm = 800, fullMm = null).userFacing.messageLevel)
        val history = fixture.track.distanceHistory.toList()
        val unavailable = fixture.estimate(atMs = 1_500L, rawMm = 800, fullMm = 800,
            rawFreshness = .1f, weakCommonQuality = true)
        assertQualitative(unavailable)
        assertEquals(history, fixture.track.distanceHistory)
        assertFalse(fixture.track.metricDistanceReliable)
        assertNull(unavailable.userFacing.message)
        assertEquals(MessageLevel.NONE, unavailable.userFacing.messageLevel)
    }

    @Test
    fun continuityRejectedNearRawDoesNotBecomeFullBackgroundAndConfirmedRawRecovers() {
        val fixture = Fixture()
        fixture.estimate(rawMm = 4_000, fullMm = null)
        val history = fixture.track.distanceHistory.toList()
        val rejected = fixture.estimate(atMs = 1_500L, rawMm = 800, fullMm = 4_000)
        assertQualitative(rejected)
        assertEquals(0f, rejected.confidence.finalScore, 0f)
        assertNull(rejected.userFacing.message)
        assertEquals(Trend.UNKNOWN, rejected.trend)
        assertNull(rejected.prediction)
        assertEquals(DepthAvailability.UNAVAILABLE, rejected.depthAvailability)
        assertEquals(history, fixture.track.distanceHistory)

        val confirmed = fixture.estimate(atMs = 2_000L, rawMm = 750, fullMm = 4_000)
        assertEquals(DepthSource.ARCORE_RAW_DEPTH, confirmed.source)
        assertTrue(confirmed.confidence.finalScore >= .55f)
        assertEquals(.75f, confirmed.riskDistanceM!!, 0f)
        assertEquals(MessageLevel.STOP, confirmed.userFacing.messageLevel)
        assertEquals(listOf(.8f, .75f), fixture.track.distanceHistory.map { it.distanceM })
        assertTrue(fixture.track.distanceHistory.all { it.source == DepthSource.ARCORE_RAW_DEPTH })
        assertNull(confirmed.timeToCollisionMs)
    }

    @Test
    fun repeatedRejectedCaptureCannotConfirmNearRawBeforeANewCaptureArrives() {
        val fixture = Fixture()
        fixture.estimate(rawMm = 4_000, fullMm = null)
        assertQualitative(fixture.estimate(atMs = 1_500L, rawMm = 800, fullMm = 4_000))
        val repeated = fixture.estimate(atMs = 1_750L, rawMm = 800, fullMm = 4_000,
            rawCaptureAtMs = 1_500L)
        assertQualitative(repeated)
        assertEquals(0f, repeated.confidence.finalScore, 0f)
        assertEquals(Trend.UNKNOWN, repeated.trend)
        assertEquals(listOf(4f), fixture.track.distanceHistory.map { it.distanceM })
        val confirmed = fixture.estimate(atMs = 2_000L, rawMm = 750, fullMm = 4_000)
        assertEquals(DepthSource.ARCORE_RAW_DEPTH, confirmed.source)
        assertEquals(MessageLevel.STOP, confirmed.userFacing.messageLevel)
        assertEquals(listOf(.8f, .75f), fixture.track.distanceHistory.map { it.distanceM })
    }

    @Test
    fun supportedMaskContinuityVetoAlsoWithholdsMetricAndPreservesConfirmation() {
        val fixture = Fixture()
        fun estimateMask(atMs: Long, rawMm: Int): TrackedObjectDepth {
            val input = fixture.input(atMs, rawMm, fullMm = 4_000)
            val stats = DepthSampler().sampleObjectDepth(
                requireNotNull(input.rawDepth), input.rawConfidence,
                input.mapper.imagePolygonToDepthPolygon(input.geometry.polygonNorm),
            )
            return fixture.estimator.estimateSupportedMask(input, stats)
        }
        estimateMask(1_000L, 4_000)
        val rejected = estimateMask(1_500L, 800)
        assertQualitative(rejected)
        assertEquals(0f, rejected.confidence.finalScore, 0f)
        assertEquals(Trend.UNKNOWN, rejected.trend)
        assertEquals(listOf(4f), fixture.track.distanceHistory.map { it.distanceM })
        val confirmed = estimateMask(2_000L, 750)
        assertEquals(DepthSource.ARCORE_RAW_DEPTH, confirmed.source)
        assertEquals(MessageLevel.STOP, confirmed.userFacing.messageLevel)
        assertEquals(listOf(.8f, .75f), fixture.track.distanceHistory.map { it.distanceM })
    }

    @Test
    fun ambiguousRawForegroundStillVetoesFullBackgroundBeforeAnyMetricRecord() {
        val fixture = Fixture()
        val input = fixture.input(rawMm = 4_000, fullMm = 4_000).copy(
            rawDepth = DepthImage16(20, 20, IntArray(400) { if (it % 20 in 7..10) 800 else 4_000 }),
        )
        val result = fixture.estimator.estimate(input)
        assertQualitative(result)
        assertEquals(0f, result.confidence.finalScore, 0f)
        assertTrue(fixture.track.distanceHistory.isEmpty())
    }

    private fun assertQualitative(result: TrackedObjectDepth) {
        assertEquals(DepthSource.POLYGON_TREND_PSEUDO_DEPTH, result.source)
        assertNull(result.riskDistanceM)
        assertNull(result.zDistanceM)
        assertNull(result.groundDistanceM)
        assertNull(result.rayDistanceM)
        assertNull(result.depthMedianM)
        assertNull(result.timeToCollisionMs)
        assertNull(result.userFacing.stepsAhead)
    }

    private class Fixture {
        val tracker = ObjectTracker()
        val estimator = ObjectDepthEstimator(tracker = tracker)
        private val geometry = MaskPolygonExtractor().extract(
            DetectionCandidate("car", .95f, RectNorm(0f, 0f, 1f, 1f)),
        )
        var track = listOf(0L, 500L, 1_000L).map { tracker.update(listOf(geometry), it).single() }.last()
        private val mapper = LetterboxCoordinateMapper(
            ModelInputTransform(20, 20, 20, 20, 1f, 0f, 0f), ImageSize(20, 20),
        )

        fun estimate(atMs: Long = 1_000L, rawMm: Int?, fullMm: Int?, rawFreshness: Float = 1f,
                     weakCommonQuality: Boolean = false, rawCaptureAtMs: Long = atMs): TrackedObjectDepth =
            estimator.estimate(input(atMs, rawMm, fullMm, rawFreshness, weakCommonQuality, rawCaptureAtMs))

        fun input(atMs: Long = 1_000L, rawMm: Int?, fullMm: Int?, rawFreshness: Float = 1f,
                  weakCommonQuality: Boolean = false, rawCaptureAtMs: Long = atMs): ObjectDepthInput {
            if (atMs > track.lastSeenAtMs) track = tracker.update(listOf(geometry), atMs).single()
            return ObjectDepthInput(
                frameId = atMs * 1_000_000L, timestampMs = atMs, geometry = geometry, track = track, mapper = mapper,
                rawDepth = rawMm?.let { mm -> DepthImage16(20, 20, IntArray(400) { mm }) },
                rawConfidence = rawMm?.let { ConfidenceImage8(20, 20, ByteArray(400) { 255.toByte() }) },
                rawDepthFreshnessQuality = rawFreshness,
                fullDepth = fullMm?.let { mm -> DepthImage16(20, 20, IntArray(400) { mm }) },
                visualTrackingQuality = if (weakCommonQuality) 0f else 1f,
                motionContext = MotionContext(
                    motionQuality = if (weakCommonQuality) .2f else 1f,
                    cameraPoseEvidence = CameraPoseEvidence(1L, atMs, 0f, 0f, 0f, 0f, 0f, -1f,
                        CameraImageProjection(20, 20, 14f, 14f, 10f, 10f, 1f, 0f, 0f, 0f, 1f, 0f)),
                ),
                rawDepthMatchesCameraFrame = true, fullDepthMatchesCameraFrame = true,
                rawDepthTimestampNs = rawCaptureAtMs * 1_000_000L,
                fullDepthTimestampNs = atMs * 1_000_000L,
                requireIndependentDepthObservation = true,
            )
        }
    }
}

package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectTrackerTest {
    @Test
    fun metricDistanceHistoryProducesApproachSpeedAndTtc() {
        val history = listOf(
            distance(at = 0L, meters = 4.0f),
            distance(at = 500L, meters = 3.2f),
            distance(at = 1_000L, meters = 2.4f),
        )

        val speed = ApproachSpeed.fromDistanceHistory(history)
        val ttc = TTC.fromMetricDistance(currentDistanceM = 2.4f, approachSpeed = speed)

        assertEquals(Trend.APPROACHING, speed.trend)
        assertEquals(1.6f, speed.metersPerSecond!!, 0.01f)
        assertEquals(1_500L, ttc.milliseconds)
        assertEquals(TtcSource.METRIC_DISTANCE, ttc.source)
    }

    @Test
    fun objectTrackerKeepsStableGeometryAndIgnoresPseudoDistanceAsMetricHistory() {
        val tracker = ObjectTracker()
        val geometry = geometry(x = 0.40f, width = 0.20f)
        val track = tracker.update(listOf(geometry), timestampMs = 0L).single()
        tracker.update(listOf(geometry(x = 0.405f, width = 0.20f)), timestampMs = 500L)
        val stableTrack = tracker.update(listOf(geometry(x = 0.410f, width = 0.20f)), timestampMs = 1_000L).single()

        tracker.recordDistance(
            track = stableTrack,
            distanceM = 1.0f,
            source = DepthSource.POLYGON_TREND_PSEUDO_DEPTH,
            confidence = 0.9f,
            timestampMs = 1_000L,
        )

        assertEquals(track.trackId, stableTrack.trackId)
        assertTrue(stableTrack.stable)
        assertTrue(stableTrack.distanceHistory.isEmpty())
    }

    @Test
    fun idSwitchPolicyFlagsImplausibleGeometryJump() {
        val tracker = ObjectTracker()
        val track = tracker.update(listOf(geometry(x = 0.20f, width = 0.20f)), timestampMs = 0L).single()
        val policy = IdSwitchSuspicionPolicy()

        val suspicion = policy.assess(
            track = track,
            currentGeometry = geometry(x = 0.55f, width = 0.45f),
        )

        assertTrue(suspicion.suspected)
        assertEquals(track.trackId, suspicion.trackId)
        assertTrue(suspicion.confidence >= 0.5f)
    }

    @Test
    fun largeGeometryJumpStartsSeparateTrackInsteadOfMerging() {
        val tracker = ObjectTracker()
        tracker.update(listOf(geometry(x = 0.20f, width = 0.20f)), timestampMs = 0L)
        val active = tracker.update(listOf(geometry(x = 0.80f, width = 0.20f)), timestampMs = 500L)

        assertEquals(2, active.size)
        assertFalse(active.any { it.idSwitchSuspected })
    }

    @Test
    fun duplicateDetectionsAreNotCollapsedBeforeTracking() {
        val tracker = ObjectTracker()
        val duplicate = geometry(x = 0.30f, width = 0.20f)

        val active = tracker.update(listOf(duplicate, duplicate), timestampMs = 0L)

        assertEquals(2, active.size)
    }

    @Test
    fun riskPolicyUsesTtcAlertBoundary() {
        val policy = TrackingRiskPolicy()
        val boundary = policy.evaluate(riskSignals(ttcMs = 10_000L))
        val awareBoundary = policy.evaluate(riskSignals(ttcMs = 30_000L))
        val outsideBoundary = policy.evaluate(riskSignals(ttcMs = 30_001L))

        assertTrue(boundary.alertable)
        assertEquals(TrackingRiskType.APPROACHING_OBJECT, boundary.riskType)
        assertFalse(awareBoundary.alertable)
        assertEquals(TrackingRiskType.APPROACHING_OBJECT, awareBoundary.riskType)
        assertEquals(MessageLevel.AWARE, awareBoundary.messageLevel)
        assertFalse(outsideBoundary.alertable)
        assertEquals(TrackingRiskType.DISPLAY_ONLY, outsideBoundary.riskType)
    }

    @Test
    fun closeMetricDistanceBecomesBlockingRiskButPseudoDistanceDoesNot() {
        val policy = TrackingRiskPolicy()
        val metric = policy.evaluate(
            TrackRiskSignals(
                stableFrames = 1,
                stableMs = 0L,
                distance = distance(at = 0L, meters = 1.1f),
                trend = Trend.STABLE,
                timeToCollision = TtcEstimate(null, 0f, TtcSource.NONE, "none"),
                areaGrowthRatio = 0f,
                areaGrowthPerSecond = 0f,
            ),
        )
        val pseudo = policy.evaluate(
            TrackRiskSignals(
                stableFrames = 3,
                stableMs = 1_000L,
                distance = DistanceObservation(
                    timestampMs = 1_000L,
                    distanceM = 1.0f,
                    source = DepthSource.POLYGON_TREND_PSEUDO_DEPTH,
                    confidence = 0.9f,
                ),
                trend = Trend.STABLE,
                timeToCollision = TtcEstimate(null, 0f, TtcSource.NONE, "none"),
                areaGrowthRatio = 0f,
                areaGrowthPerSecond = 0f,
            ),
        )

        assertTrue(metric.alertable)
        assertEquals(TrackingRiskType.BLOCKING_OBJECT, metric.riskType)
        assertEquals(MessageLevel.STOP, metric.messageLevel)
        assertFalse(pseudo.alertable)
    }

    @Test
    fun bboxScaleTtcUsesGrowingBoxAsClosingSignal() {
        val ttc = TTC.fromBboxScale(
            bboxHistory = listOf(
                RectNorm(0.4f, 0.4f, 0.10f, 0.10f),
                RectNorm(0.375f, 0.375f, 0.15f, 0.15f),
                RectNorm(0.35f, 0.35f, 0.20f, 0.20f),
            ),
            observedAtHistory = listOf(0L, 500L, 1_000L),
        )

        assertNotNull(ttc.milliseconds)
        assertTrue(ttc.milliseconds!! > 0L)
        assertEquals(TtcSource.BBOX_SCALE, ttc.source)
    }

    private fun distance(at: Long, meters: Float): DistanceObservation = DistanceObservation(
        timestampMs = at,
        distanceM = meters,
        source = DepthSource.ARCORE_RAW_DEPTH,
        confidence = 0.90f,
    )

    private fun riskSignals(ttcMs: Long): TrackRiskSignals = TrackRiskSignals(
        stableFrames = 3,
        stableMs = 1_000L,
        distance = null,
        trend = Trend.APPROACHING,
        timeToCollision = TtcEstimate(ttcMs, 0.7f, TtcSource.BBOX_SCALE, "test"),
        areaGrowthRatio = 0f,
        areaGrowthPerSecond = 0f,
    )

    private fun geometry(x: Float, width: Float): ObjectGeometry {
        val bbox = RectNorm(x = x, y = 0.35f, width = width, height = 0.30f)
        return ObjectGeometry(
            className = "person",
            detectionConfidence = 0.90f,
            bboxNorm = bbox,
            polygonNorm = bboxPolygon(bbox, erosionRatio = 0f),
            maskAreaNorm = bbox.area,
            centerNorm = bbox.center,
            bottomContactNorm = Point2(bbox.center.x, bbox.y + bbox.height),
        )
    }
}

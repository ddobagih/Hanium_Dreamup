package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.DepthConfidenceBreakdown
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.depth.Trend
import kr.co.hanium.dreamup.walksafe.depth.UserFacingDepth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S12-17: approved policy forbids distance-less observations as an automatic-report basis
 * (FP-021 prohibited[4], FP-009-DETAIL-02). Both report selectors must keep requiring a metric
 * depth source, even for a stable, clearly visible damaged tactile observation.
 */
class AndroidRiskSelectionReportEvidenceTest {
    @Test
    fun stableDamageWithoutMetricDepthIsNeverAReportCandidate() {
        listOf(DepthSource.OBJECT_SIZE_PRIOR, DepthSource.POLYGON_TREND_PSEUDO_DEPTH).forEach { source ->
            val damage = damage("pseudo-$source", source = source)
            assertNull("$source", AndroidRiskSelectionPolicy.selectReportOutput(listOf(damage)))
            assertNull("$source", AndroidRiskSelectionPolicy.selectAutomaticReportOutput(listOf(damage)))
        }
    }

    @Test
    fun metricDamageBelowTheReportConfidenceIsExcludedFromBothSelectors() {
        val weak = damage("weak", finalScore = 0.54f)
        assertNull(AndroidRiskSelectionPolicy.selectReportOutput(listOf(weak)))
        assertNull(AndroidRiskSelectionPolicy.selectAutomaticReportOutput(listOf(weak)))
    }

    @Test
    fun stableMetricDamageRemainsSelectableBesideANonMetricObservation() {
        val metric = damage("metric", finalScore = 0.60f)
        val pseudo = damage("pseudo", source = DepthSource.OBJECT_SIZE_PRIOR, finalScore = 0.95f)
        assertEquals(metric, AndroidRiskSelectionPolicy.selectReportOutput(listOf(pseudo, metric)))
        assertEquals(metric, AndroidRiskSelectionPolicy.selectAutomaticReportOutput(listOf(pseudo, metric)))
    }

    private fun damage(
        trackId: String,
        source: DepthSource = DepthSource.ARCORE_RAW_DEPTH,
        finalScore: Float = 0.85f,
    ): TrackedObjectDepth = TrackedObjectDepth(
        frameId = 1L,
        timestampMs = 1_000L,
        trackId = trackId,
        className = "damaged_tactile_block",
        detectionConfidence = 0.95f,
        bboxNorm = RectNorm(0.2f, 0.3f, 0.4f, 0.2f),
        polygonNorm = emptyList(),
        maskAreaNorm = 0.08f,
        centerNorm = Point2(0.4f, 0.4f),
        bottomContactNorm = null,
        source = source,
        zDistanceM = if (source.metric) 1.1f else null,
        rayDistanceM = null,
        groundDistanceM = null,
        riskDistanceM = if (source.metric) 1.0f else null,
        validSampleCount = if (source.metric) 40 else 0,
        validSampleRatio = if (source.metric) 0.8f else 0f,
        depthMedianM = if (source.metric) 1.1f else null,
        depthP20M = if (source.metric) 1.0f else null,
        depthIqrM = if (source.metric) 0.1f else null,
        trend = Trend.STABLE,
        approachScore = 0f,
        approachSpeedMps = null,
        timeToCollisionMs = null,
        confidence = DepthConfidenceBreakdown(
            sourceQuality = finalScore,
            sampleQuality = 1f,
            depthQuality = 1f,
            detectionQuality = 1f,
            trackingQuality = 1f,
            motionQuality = 1f,
            freshnessQuality = 1f,
            corridorQuality = 1f,
        ),
        userFacing = UserFacingDepth(null, MessageLevel.NONE, null),
        trackAgeFrames = 10,
        trackStableMs = 2_000L,
    )
}

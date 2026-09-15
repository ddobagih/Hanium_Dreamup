package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthSamplerTest {
    @Test
    fun rawDepthSamplerRejectsInvalidAndOutlierDepth() {
        val depth = DepthImage16(
            width = 5,
            height = 5,
            millimeters = intArrayOf(
                0, 1200, 1200, 1200, 0,
                1200, 1210, 1190, 1200, 1200,
                1200, 1195, 9000, 1205, 1200,
                1200, 1200, 1190, 1210, 1200,
                0, 1200, 1200, 1200, 0,
            ),
        )
        val confidence = ConfidenceImage8(5, 5, ByteArray(25) { 255.toByte() })
        val stats = DepthSampler().sampleObjectDepth(
            depthImage = depth,
            confidenceImage = confidence,
            polygonDepthNorm = bboxPolygon(RectNorm(0f, 0f, 1f, 1f), erosionRatio = 0f),
            options = DepthSampleOptions(minValidSamples = 8, maxDepthM = 10f),
        )

        assertNotNull(stats.medianM)
        assertTrue(stats.validSampleCount >= 8)
        assertEquals(1.2f, stats.medianM!!, 0.03f)
        assertTrue(stats.outlierRatio > 0f)
    }

    @Test
    fun rawDepthSamplerReturnsEmptyStatsWhenSamplesAreSparse() {
        val depth = DepthImage16(3, 3, intArrayOf(0, 0, 0, 0, 1000, 0, 0, 0, 0))
        val stats = DepthSampler().sampleObjectDepth(
            depthImage = depth,
            confidenceImage = null,
            polygonDepthNorm = bboxPolygon(RectNorm(0f, 0f, 1f, 1f), erosionRatio = 0f),
            options = DepthSampleOptions(minValidSamples = 3, minConfidence = null),
        )

        assertNull(stats.medianM)
        assertEquals(1, stats.validSampleCount)
    }

    @Test
    fun samplerReturnsEmptyStatsWhenOutlierFilterLeavesTooFewSamples() {
        val values = IntArray(25) { index -> if (index == 12) 1_000 else 2_000 + index * 100 }
        val depth = DepthImage16(width = 5, height = 5, millimeters = values)

        val stats = DepthSampler().sampleObjectDepth(
            depthImage = depth,
            confidenceImage = null,
            polygonDepthNorm = bboxPolygon(RectNorm(0f, 0f, 1f, 1f), erosionRatio = 0f),
            options = DepthSampleOptions(
                minValidSamples = 20,
                minConfidence = null,
                minOutlierBandM = 0.01f,
                outlierMadK = 0.01f,
                maxDepthM = 8f,
            ),
        )

        assertNull(stats.medianM)
        assertTrue(stats.validSampleCount < 20)
    }

    @Test
    fun samplerCoercesNonPositiveMaxSamples() {
        val depth = DepthImage16(width = 3, height = 3, millimeters = IntArray(9) { 1_000 })

        val stats = DepthSampler().sampleObjectDepth(
            depthImage = depth,
            confidenceImage = null,
            polygonDepthNorm = bboxPolygon(RectNorm(0f, 0f, 1f, 1f), erosionRatio = 0f),
            options = DepthSampleOptions(minValidSamples = 1, minConfidence = null, maxSamples = 0),
        )

        assertEquals(1.0f, stats.medianM!!, 0.01f)
    }

    @Test
    fun samplerKeepsLargeRoiSamplesSpatiallyBalancedWhenCapped() {
        val width = 40
        val height = 40
        val depth = DepthImage16(
            width = width,
            height = height,
            millimeters = IntArray(width * height) { index ->
                val y = index / width
                1_000 + y * 100
            },
        )

        val stats = DepthSampler().sampleObjectDepth(
            depthImage = depth,
            confidenceImage = null,
            polygonDepthNorm = bboxPolygon(RectNorm(0f, 0f, 1f, 1f), erosionRatio = 0f),
            options = DepthSampleOptions(minValidSamples = 30, minConfidence = null, maxSamples = 500, maxDepthM = 8f),
        )

        assertEquals(2.9f, stats.medianM!!, 0.15f)
        assertTrue(stats.validSampleCount < width * height)
    }

    @Test
    fun obstacleWarningUsesActionWithoutStepCountsAndPseudoCautionStaysQualitative() {
        val policy = MessagePolicy(stepLengthM = 0.6f)
        val metric = policy.buildUserFacing(
            MetricDepthDecision(
                className = "person",
                source = DepthSource.ARCORE_RAW_DEPTH,
                riskDistanceM = 1.2f,
                trend = Trend.STABLE,
                confidenceFinal = 0.8f,
            ),
        )
        val pseudo = policy.buildUserFacing(
            MetricDepthDecision(
                className = "person",
                source = DepthSource.POLYGON_TREND_PSEUDO_DEPTH,
                riskDistanceM = null,
                trend = Trend.APPROACHING,
                confidenceFinal = 0.5f,
            ),
        )

        assertEquals(MessageLevel.STOP, metric.messageLevel)
        assertNull(metric.stepsAhead)
        assertTrue(metric.message!!.endsWith("멈추세요. 주변을 확인하세요."))
        assertEquals(MessageLevel.CAUTION, pseudo.messageLevel)
        assertNull(pseudo.stepsAhead)
        assertTrue(pseudo.message!!.contains("줄어드는 것"))
        for (message in listOf(metric.message!!, pseudo.message!!)) {
            assertFalse(message, Regex("""\d+(?:\.\d+)?\s*(?:보|걸음|m|미터)""").containsMatchIn(message))
        }
    }
}

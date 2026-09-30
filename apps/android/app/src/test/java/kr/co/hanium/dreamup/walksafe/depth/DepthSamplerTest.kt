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
    fun uniformDepthStatisticsRemainStableAcrossSamplingBudgetBoundary() {
        val width = 503
        val height = 2
        val depth = DepthImage16(width, height, IntArray(width * height) { 1_200 })
        val confidence = ConfidenceImage8(width, height, ByteArray(width * height) { 255.toByte() })
        val options = DepthSampleOptions(polygonErosionRatio = 0f)

        for (rightPixel in listOf(499.5f, 500.5f)) {
            val right = rightPixel / (width - 1)
            val stats = DepthSampler().sampleObjectDepth(
                depthImage = depth,
                confidenceImage = confidence,
                polygonDepthNorm = listOf(
                    Point2(0f, 0f), Point2(right, 0f), Point2(right, 0.5f), Point2(0f, 0.5f),
                ),
                options = options,
            )

            assertEquals(options.maxSamples, stats.validSampleCount)
            assertEquals(1.2f, stats.medianM!!, 0.000001f)
            assertEquals(1.2f, stats.p20M!!, 0.000001f)
            assertEquals(1f, stats.validSampleRatio, 0.000001f)
            assertEquals(1f, stats.confidenceMedian!!, 0.000001f)
            assertEquals(0f, stats.outlierRatio, 0.000001f)
            assertFalse(stats.foregroundAmbiguous)
        }
    }

    @Test
    fun validDepthIsNotLostAtThe500To501CandidateBoundary() {
        val width = 503
        val height = 2
        val depth = DepthImage16(
            width, height,
            IntArray(width * height) { index -> if (index % width % 2 == 1) 1_200 else 0 },
        )
        val confidence = ConfidenceImage8(width, height, ByteArray(width * height) { 255.toByte() })
        val options = DepthSampleOptions(polygonErosionRatio = 0f)

        for (rightPixel in listOf(499.5f, 500.5f)) {
            val right = rightPixel / (width - 1)
            val stats = DepthSampler().sampleObjectDepth(
                depthImage = depth,
                confidenceImage = confidence,
                polygonDepthNorm = listOf(
                    Point2(0f, 0f), Point2(right, 0f), Point2(right, 0.5f), Point2(0f, 0.5f),
                ),
                options = options,
            )

            assertEquals(250, stats.validSampleCount)
            assertEquals(0.5f, stats.validSampleRatio, 0.000001f)
            assertEquals(1.2f, stats.medianM!!, 0.000001f)
            assertEquals(1.2f, stats.p20M!!, 0.000001f)
            assertEquals(1f, stats.confidenceMedian!!, 0.000001f)
            assertFalse(stats.foregroundAmbiguous)
        }
    }

    @Test
    fun defaultOptionsDoNotReplaceNearDepthWithFarStripeAtBudgetBoundary() {
        val width = 40
        val height = 40
        val depth = DepthImage16(
            width, height,
            IntArray(width * height) { index -> if (index % width % 2 == 1) 1_000 else 3_000 },
        )
        val confidence = ConfidenceImage8(width, height, ByteArray(width * height) { 255.toByte() })
        val options = DepthSampleOptions()

        for (rightPixel in listOf(20f, 21f)) {
            val right = rightPixel / (width - 1)
            val bottom = 25f / (height - 1)
            val stats = DepthSampler().sampleObjectDepth(
                depthImage = depth,
                confidenceImage = confidence,
                polygonDepthNorm = listOf(
                    Point2(0f, 0f), Point2(right, 0f), Point2(right, bottom), Point2(0f, bottom),
                ),
                options = options,
            )

            assertTrue(stats.validSampleCount >= options.minValidSamples)
            assertTrue(stats.validSampleCount <= options.maxSamples)
            assertEquals(1f, stats.medianM!!, 0.000001f)
            assertEquals(1f, stats.p20M!!, 0.000001f)
            assertTrue(stats.outlierRatio > 0f)
            assertFalse(stats.foregroundAmbiguous)
        }
    }

    @Test
    fun commensurateRectangleKeepsTwoDimensionalDepthStatistics() {
        val width = 1_000
        val height = 1_000
        val confidence = ConfidenceImage8(width, height, ByteArray(width * height) { 255.toByte() })
        val options = DepthSampleOptions(polygonErosionRatio = 0f)
        val polygon = listOf(Point2(0f, 0f), Point2(1f, 0f), Point2(1f, 1f), Point2(0f, 1f))

        for (horizontal in listOf(true, false)) {
            val depth = DepthImage16(
                width, height,
                IntArray(width * height) { index ->
                    1_000 + (if (horizontal) index % width else index / width)
                },
            )
            val stats = DepthSampler().sampleObjectDepth(
                depthImage = depth,
                confidenceImage = confidence,
                polygonDepthNorm = polygon,
                options = options,
            )

            // Millimeter-ramp fixtures exercise public statistics, not a copied sampling algorithm.
            assertEquals(options.maxSamples, stats.validSampleCount)
            assertEquals(if (horizontal) 1.499f else 1.477f, stats.medianM!!, 0.001f)
            assertEquals(if (horizontal) 1.195f else 1.204f, stats.p20M!!, 0.001f)
            assertEquals(if (horizontal) 1.804f else 1.795f, stats.p80M!!, 0.001f)
            assertEquals(1f, stats.validSampleRatio, 0.000001f)
            assertEquals(0f, stats.outlierRatio, 0.000001f)
            assertFalse(stats.foregroundAmbiguous)
        }
    }

    @Test
    fun thinFullPolygonsKeepUniformDepthAtDefaultAndMinimumSampleBudgets() {
        val defaults = DepthSampleOptions()
        val polygon = listOf(Point2(0f, 0f), Point2(1f, 0f), Point2(1f, 1f), Point2(0f, 1f))

        for ((width, height) in listOf(1_000 to 2, 2 to 1_000)) {
            val depth = DepthImage16(width, height, IntArray(width * height) { 1_200 })
            val confidence = ConfidenceImage8(width, height, ByteArray(width * height) { 255.toByte() })

            for (budget in listOf(defaults.maxSamples, defaults.minValidSamples)) {
                val stats = DepthSampler().sampleObjectDepth(
                    depthImage = depth,
                    confidenceImage = confidence,
                    polygonDepthNorm = polygon,
                    options = defaults.copy(maxSamples = budget, polygonErosionRatio = 0f),
                )

                assertEquals(budget, stats.validSampleCount)
                assertTrue(stats.validSampleCount >= defaults.minValidSamples)
                assertNotNull(stats.medianM)
                assertNotNull(stats.p20M)
                assertNotNull(stats.confidenceMedian)
                assertEquals(1.2f, stats.medianM!!, 0.000001f)
                assertEquals(1.2f, stats.p20M!!, 0.000001f)
                assertEquals(1f, stats.validSampleRatio, 0.000001f)
                assertEquals(1f, stats.confidenceMedian!!, 0.000001f)
                assertEquals(0f, stats.outlierRatio, 0.000001f)
                assertFalse(stats.foregroundAmbiguous)
            }
        }
    }

    @Test
    fun connectedNearRegionWithholdsBackgroundBelowAndAboveExistingSupportGate() {
        val width = 25
        val height = 20
        val confidence = ConfidenceImage8(width, height, ByteArray(width * height) { 255.toByte() })
        val polygon = bboxPolygon(RectNorm(0f, 0f, 1f, 1f), erosionRatio = 0f)

        for (nearCount in listOf(29, 40)) {
            val values = IntArray(width * height) { 4_000 }
            repeat(nearCount) { index ->
                val x = 5 + index % 8
                val y = 5 + index / 8
                values[y * width + x] = 800
            }
            val stats = DepthSampler().sampleObjectDepth(
                depthImage = DepthImage16(width, height, values),
                confidenceImage = confidence,
                polygonDepthNorm = polygon,
                options = DepthSampleOptions(polygonErosionRatio = 0f),
            )

            assertTrue(stats.foregroundAmbiguous)
            assertNull(stats.medianM)
            assertNull(stats.p20M)
        }
    }

    @Test
    fun singleNearSampleAndUniformBackgroundRemainQuantitative() {
        val width = 25
        val height = 20
        val confidence = ConfidenceImage8(width, height, ByteArray(width * height) { 255.toByte() })
        val polygon = bboxPolygon(RectNorm(0f, 0f, 1f, 1f), erosionRatio = 0f)

        for (withNearNoise in listOf(false, true)) {
            val values = IntArray(width * height) { 4_000 }
            if (withNearNoise) values[10 * width + 10] = 800
            val stats = DepthSampler().sampleObjectDepth(
                depthImage = DepthImage16(width, height, values),
                confidenceImage = confidence,
                polygonDepthNorm = polygon,
                options = DepthSampleOptions(polygonErosionRatio = 0f),
            )

            assertFalse(stats.foregroundAmbiguous)
            assertEquals(4f, stats.medianM!!, 0.000001f)
            assertEquals(4f, stats.p20M!!, 0.000001f)
        }
    }

    @Test
    fun adjacentSampledGridCellsWithholdEvenWhenRawPixelsDoNotTouch() {
        val width = 50
        val height = 40
        val values = IntArray(width * height) { 4_000 }
        values[10 * width + 10] = 800
        values[10 * width + 12] = 800
        val stats = DepthSampler().sampleObjectDepth(
            depthImage = DepthImage16(width, height, values),
            confidenceImage = ConfidenceImage8(width, height, ByteArray(width * height) { 255.toByte() }),
            polygonDepthNorm = bboxPolygon(RectNorm(0f, 0f, 1f, 1f), erosionRatio = 0f),
            options = DepthSampleOptions(polygonErosionRatio = 0f),
        )

        assertTrue(stats.foregroundAmbiguous)
        assertNull(stats.medianM)
        assertNull(stats.p20M)
    }

    @Test
    fun confidenceRejectedNearPairDoesNotWithholdBackground() {
        val width = 50
        val height = 40
        val values = IntArray(width * height) { 4_000 }
        values[10 * width + 10] = 800
        values[10 * width + 12] = 800
        val confidence = ByteArray(width * height) { 255.toByte() }
        confidence[10 * width + 10] = 0
        confidence[10 * width + 12] = 0
        val stats = DepthSampler().sampleObjectDepth(
            depthImage = DepthImage16(width, height, values),
            confidenceImage = ConfidenceImage8(width, height, confidence),
            polygonDepthNorm = bboxPolygon(RectNorm(0f, 0f, 1f, 1f), erosionRatio = 0f),
            options = DepthSampleOptions(polygonErosionRatio = 0f),
        )

        assertFalse(stats.foregroundAmbiguous)
        assertEquals(4f, stats.medianM!!, 0.000001f)
        assertEquals(4f, stats.p20M!!, 0.000001f)
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

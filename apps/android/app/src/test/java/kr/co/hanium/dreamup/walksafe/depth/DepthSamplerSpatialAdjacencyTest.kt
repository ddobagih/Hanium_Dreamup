package kr.co.hanium.dreamup.walksafe.depth

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthSamplerSpatialAdjacencyTest {
    @Test
    fun verticalForegroundAcrossUnequalRowDensitiesWithholdsBackground() {
        // At this budget adjacent rows y=704 and y=749 have 23 and 22 samples.
        // The same narrow upright object contains x=804 (column 18) and x=795 (column 17).
        val stats = sampleScene { x, y -> x in 790..810 && y in 690..760 }

        assertForegroundAmbiguous(stats)
    }

    @Test
    fun diagonalForegroundAcrossUnequalRowDensitiesWithholdsBackground() {
        // A connected sloping strip contains (543, 704) and (522, 749), in different columns.
        val stats = sampleScene { x, y ->
            y in 690..760 && abs(x - (543f - (y - 704) * 21f / 45f)) <= 3f
        }

        assertForegroundAmbiguous(stats)
    }

    @Test
    fun diagonalForegroundAcrossEqualRowDensitiesWithholdsBackground() {
        // The strip joins (195, 204) and (239, 249); diagonal grid neighbors count too.
        val stats = sampleScene { x, y ->
            y in 195..260 && abs(x - (195f + (y - 204) * 44f / 45f)) <= 3f
        }

        assertForegroundAmbiguous(stats)
    }

    @Test
    fun uniformBackgroundAndSingleNearSampleRemainQuantitative() {
        for (withNoise in listOf(false, true)) {
            val stats = sampleScene { x, y -> withNoise && x == 804 && y == 704 }

            assertFalse(stats.foregroundAmbiguous)
            assertEquals(if (withNoise) 499 else 500, stats.validSampleCount)
            assertEquals(4f, stats.medianM!!, 0.000001f)
            assertEquals(4f, stats.p20M!!, 0.000001f)
            assertEquals(1f, stats.confidenceMedian!!, 0.000001f)
        }
    }

    @Test
    fun separatedNearSamplesRemainIsolatedOutliers() {
        // More than one horizontal or vertical sample interval separates the two pixels.
        for (second in listOf(704 to 749, 795 to 840)) {
            val stats = sampleScene { x, y ->
                (x == 804 && y == 704) || (x == second.first && y == second.second)
            }

            assertFalse(stats.foregroundAmbiguous)
            assertEquals(498, stats.validSampleCount)
            assertEquals(4f, stats.medianM!!, 0.000001f)
            assertEquals(4f, stats.p20M!!, 0.000001f)
        }
    }

    @Test
    fun confidenceRejectedVerticalPairDoesNotWithholdBackground() {
        val stats = sampleScene(rejectForegroundConfidence = true) { x, y ->
            x in 790..810 && y in 690..760
        }

        assertFalse(stats.foregroundAmbiguous)
        assertEquals(498, stats.validSampleCount)
        assertEquals(4f, stats.medianM!!, 0.000001f)
        assertEquals(4f, stats.p20M!!, 0.000001f)
    }

    @Test
    fun denseGridDoesNotJoinNearSamplesAcrossAnInterveningBackgroundRow() {
        val stats = sampleScene(width = 30, height = 20) { x, y ->
            x == 5 && (y == 1 || y == 3)
        }

        assertFalse(stats.foregroundAmbiguous)
        assertEquals(454, stats.validSampleCount)
        assertEquals(4f, stats.medianM!!, 0.000001f)
        assertEquals(4f, stats.p20M!!, 0.000001f)
    }

    @Test
    fun denseGridDoesNotJoinNearSamplesAcrossAnInterveningBackgroundColumn() {
        val stats = sampleScene(width = 30, height = 20) { x, y ->
            y == 3 && (x == 3 || x == 5)
        }

        assertFalse(stats.foregroundAmbiguous)
        assertEquals(454, stats.validSampleCount)
        assertEquals(4f, stats.medianM!!, 0.000001f)
        assertEquals(4f, stats.p20M!!, 0.000001f)
    }

    @Test
    fun diagonalForegroundAtShiftedLeftEdgeWithholdsBackground() {
        // The next row is less dense: its diagonal neighbor is 47 px away, beyond ceil(1000/22).
        val stats = sampleScene { x, y ->
            y in 690..760 && abs(x - (21f + (y - 704) * 47f / 45f)) <= 3f
        }

        assertForegroundAmbiguous(stats)
    }

    @Test
    fun denseGridStillJoinsImmediateHorizontalAndVerticalNeighbors() {
        for (second in listOf(4 to 1, 5 to 2)) {
            val stats = sampleScene(width = 30, height = 20) { x, y ->
                (x == 5 && y == 1) || (x == second.first && y == second.second)
            }

            assertTrue(stats.foregroundAmbiguous)
            assertEquals(456, stats.validSampleCount)
            assertNull(stats.medianM)
            assertNull(stats.p20M)
        }
    }

    private fun sampleScene(
        rejectForegroundConfidence: Boolean = false,
        width: Int = 1_000,
        height: Int = 1_000,
        isForeground: (Int, Int) -> Boolean,
    ): DepthStats {
        val values = IntArray(width * height) { 4_000 }
        val confidence = ByteArray(width * height) { 255.toByte() }
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (isForeground(x, y)) {
                    values[y * width + x] = 800
                    if (rejectForegroundConfidence) confidence[y * width + x] = 0
                }
            }
        }
        return DepthSampler().sampleObjectDepth(
            depthImage = DepthImage16(width, height, values),
            confidenceImage = ConfidenceImage8(width, height, confidence),
            polygonDepthNorm = bboxPolygon(RectNorm(0f, 0f, 1f, 1f), erosionRatio = 0f),
            options = DepthSampleOptions(polygonErosionRatio = 0f),
        )
    }

    private fun assertForegroundAmbiguous(stats: DepthStats) {
        assertTrue(stats.foregroundAmbiguous)
        assertEquals(500, stats.validSampleCount)
        assertEquals(1f, stats.validSampleRatio, 0.000001f)
        assertNull(stats.medianM)
        assertNull(stats.p20M)
    }
}

package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthSamplerPolygonContainmentTest {
    @Test
    fun concavePolygonNeverAcceptsDepthFromOutsideItsOriginalExtent() {
        for (erosion in listOf(0.02f, 0.2f, 0.4f)) {
            for (polygon in listOf(concavePolygon, concavePolygon.reversed())) {
                val stats = sample(insideMm = 0, outsideMm = 800, erosion = erosion, polygon = polygon)

                // The L's vertex-average centroid is outside the object. Contracting toward it
                // admits pixels in the notch unless sampling also checks the original extent.
                assertEquals("erosion=$erosion", 0, stats.validSampleCount)
                assertNull(stats.medianM)
                assertNull(stats.p20M)
            }
        }
    }

    @Test
    fun outsideForegroundAndBackgroundCannotChangeValidConcaveObjectEvidence() {
        for (erosion in listOf(0.02f, 0.2f, 0.4f)) {
            for (polygon in listOf(concavePolygon, concavePolygon.reversed())) {
                val baseline = sample(insideMm = 1_200, outsideMm = 0, erosion = erosion, polygon = polygon)
                assertEquals(1.2f, baseline.medianM!!, 0.001f)
                assertTrue(baseline.validSampleCount in 30..500)

                for (outsideMm in listOf(800, 4_000)) {
                    assertEquals(
                        "outsideMm=$outsideMm erosion=$erosion",
                        baseline,
                        sample(insideMm = 1_200, outsideMm = outsideMm, erosion = erosion, polygon = polygon),
                    )
                }
            }
        }
    }

    private fun sample(insideMm: Int, outsideMm: Int, erosion: Float, polygon: List<Point2>): DepthStats {
        val pixels = IntArray(SIDE * SIDE) { index ->
            val x = index % SIDE
            val y = index / SIDE
            // Independent integer membership for the axis-aligned L, not pointInPolygon.
            val inside = x >= 5 && x < 95 && y >= 5 && y < 95 && (x < 27 || y < 27)
            if (inside) insideMm else outsideMm
        }
        return DepthSampler().sampleObjectDepth(
            DepthImage16(SIDE, SIDE, pixels),
            ConfidenceImage8(SIDE, SIDE, ByteArray(SIDE * SIDE) { 255.toByte() }),
            polygon,
            DepthSampleOptions(polygonErosionRatio = erosion),
        )
    }

    private val concavePolygon = listOf(
        Point2(0.05f, 0.05f), Point2(0.95f, 0.05f), Point2(0.95f, 0.27f),
        Point2(0.27f, 0.27f), Point2(0.27f, 0.95f), Point2(0.05f, 0.95f),
    )

    private companion object {
        const val SIDE = 101
    }
}

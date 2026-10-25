package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthForegroundMinimumSampleRegressionTest {
    @Test
    fun adjacentNearRawPairBelowMinimumStillVetoesFullBackground() {
        val scene = Scene(rawScene(nearCount = 2, additionalBackground = false))
        val stats = requireNotNull(scene.rawStats)

        assertEquals(29, stats.validSampleCount)
        assertNull(stats.medianM)
        assertForegroundVeto(scene)
        assertTrue(stats.foregroundAmbiguous)
    }

    @Test
    fun thirtiethBackgroundRawSampleKeepsExistingForegroundVeto() {
        val scene = Scene(rawScene(nearCount = 2, additionalBackground = true))
        val stats = requireNotNull(scene.rawStats)

        assertEquals(30, stats.validSampleCount)
        assertTrue(stats.foregroundAmbiguous)
        assertNull(stats.medianM)
        assertForegroundVeto(scene)
    }

    @Test
    fun oneIsolatedNearRawSampleBelowMinimumKeepsFullFallback() {
        val scene = Scene(rawScene(nearCount = 1, additionalBackground = true))
        val stats = requireNotNull(scene.rawStats)

        assertEquals(29, stats.validSampleCount)
        assertFalse(stats.foregroundAmbiguous)
        assertNull(stats.medianM)
        assertMeasuredFullBackground(scene)
    }

    @Test
    fun absentRawKeepsNormalMeasuredFullDepth() {
        val scene = Scene(rawDepth = null)

        assertNull(scene.rawStats)
        assertMeasuredFullBackground(scene)
    }

    private fun assertForegroundVeto(scene: Scene) {
        assertEquals(DepthSource.POLYGON_TREND_PSEUDO_DEPTH, scene.result.source)
        assertEquals(DepthAvailability.UNAVAILABLE, scene.result.depthAvailability)
        assertNull(scene.result.depthMedianM)
        assertNull(scene.result.riskDistanceM)
        assertEquals(0f, scene.result.confidence.finalScore, 0f)
        assertTrue(scene.track.distanceHistory.isEmpty())
    }

    private fun assertMeasuredFullBackground(scene: Scene) {
        assertEquals(DepthSource.ARCORE_FULL_DEPTH, scene.result.source)
        assertEquals(DepthAvailability.MEASURED, scene.result.depthAvailability)
        assertEquals(4f, requireNotNull(scene.result.depthMedianM), 0f)
        assertEquals(4f, requireNotNull(scene.result.riskDistanceM), 0f)
        assertTrue(scene.track.distanceHistory.isEmpty())
        assertNull(scene.result.timeToCollisionMs)
    }

    private fun rawScene(nearCount: Int, additionalBackground: Boolean): DepthImage16 {
        val pixels = IntArray(20 * 20)
        for (y in 3..5) {
            for (x in 3..11) pixels[y * 20 + x] = 4_000
        }
        if (additionalBackground) pixels[5 * 20 + 12] = 4_000
        pixels[8 * 20 + 8] = 800
        if (nearCount == 2) pixels[8 * 20 + 9] = 800
        return DepthImage16(20, 20, pixels)
    }

    private class Scene(rawDepth: DepthImage16?) {
        val geometry = MaskPolygonExtractor().extract(
            DetectionCandidate("car", .95f, RectNorm(0f, 0f, 1f, 1f)),
        )
        private val tracker = ObjectTracker()
        val track = listOf(0L, 500L, 1_000L).map { at ->
            tracker.update(listOf(geometry), at).single()
        }.last()
        private val rawConfidence = rawDepth?.let {
            ConfidenceImage8(20, 20, ByteArray(400) { 255.toByte() })
        }
        val rawStats = rawDepth?.let {
            DepthSampler().sampleObjectDepth(it, rawConfidence, geometry.polygonNorm)
        }
        val result = ObjectDepthEstimator(tracker = tracker).estimate(
            ObjectDepthInput(
                frameId = 1_000_000_000L,
                timestampMs = 1_000L,
                geometry = geometry,
                track = track,
                mapper = LetterboxCoordinateMapper(
                    ModelInputTransform(20, 20, 20, 20, 1f, 0f, 0f), ImageSize(20, 20),
                ),
                rawDepth = rawDepth,
                rawConfidence = rawConfidence,
                fullDepth = DepthImage16(20, 20, IntArray(400) { 4_000 }),
                rawDepthMatchesCameraFrame = rawDepth != null,
                fullDepthMatchesCameraFrame = true,
                rawDepthTimestampNs = rawDepth?.let { 1_000_000_000L },
                fullDepthTimestampNs = 1_000_000_000L,
                rawDepthMotionOnly = true,
            ),
        )

        init {
            assertTrue("Fixture needs stable completed detections", track.stable)
            println("rawValid=${rawStats?.validSampleCount} foreground=${rawStats?.foregroundAmbiguous} " +
                "source=${result.source} availability=${result.depthAvailability} " +
                "median=${result.depthMedianM} risk=${result.riskDistanceM} " +
                "confidence=${result.confidence.finalScore} recorded=${track.distanceHistory.map { it.source }}")
        }
    }
}

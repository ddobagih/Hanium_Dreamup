package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthSmokePipelineTest {
    @Test
    fun sampleCenterPrefersRawDepthAndFormatsSummary() {
        val snapshot = DepthFrameSnapshot(
            frameTimestampNs = 1L,
            rawDepth = depthImage(millimeters = 1_230),
            rawConfidence = ConfidenceImage8(10, 10, ByteArray(100) { 255.toByte() }),
            fullDepth = depthImage(millimeters = 2_500),
        )

        val result = DepthSmokePipeline().sampleCenter(snapshot)!!

        assertEquals(DepthSource.ARCORE_RAW_DEPTH, result.source)
        assertEquals(1.23f, result.stats.medianM!!, 0.001f)
        assertTrue(result.stats.validSampleCount >= 12)
        assertEquals(
            "ARCORE_RAW_DEPTH · 1.23m · samples ${result.stats.validSampleCount}",
            result.summaryText(),
        )
    }

    @Test
    fun sampleCenterFallsBackToFullDepthWhenRawConfidenceIsRejected() {
        val snapshot = DepthFrameSnapshot(
            frameTimestampNs = 1L,
            rawDepth = depthImage(millimeters = 1_230),
            rawConfidence = ConfidenceImage8(10, 10, ByteArray(100) { 0.toByte() }),
            fullDepth = depthImage(millimeters = 1_800),
        )

        val result = DepthSmokePipeline().sampleCenter(snapshot)!!

        assertEquals(DepthSource.ARCORE_FULL_DEPTH, result.source)
        assertEquals(1.80f, result.stats.medianM!!, 0.001f)
        assertTrue(result.summaryText().contains("ARCORE_FULL_DEPTH · 1.80m"))
    }

    @Test
    fun sampleCenterReturnsNullWhenNoDepthSamplesAreUsable() {
        val snapshot = DepthFrameSnapshot(
            frameTimestampNs = 1L,
            rawDepth = null,
            rawConfidence = null,
            fullDepth = depthImage(millimeters = 0),
        )

        assertNull(DepthSmokePipeline().sampleCenter(snapshot))
    }

    @Test
    fun summaryTextUsesNoDistanceWhenMedianIsMissing() {
        val result = DepthSmokeResult(
            source = DepthSource.UNKNOWN,
            stats = DepthStats(
                validSampleCount = 0,
                validSampleRatio = 0f,
                medianM = null,
                p10M = null,
                p20M = null,
                p80M = null,
                iqrM = null,
                madM = null,
                confidenceMedian = null,
                outlierRatio = 1f,
            ),
        )

        assertEquals("UNKNOWN · 거리 없음 · samples 0", result.summaryText())
    }

    private fun depthImage(millimeters: Int): DepthImage16 {
        return DepthImage16(10, 10, IntArray(100) { millimeters })
    }
}

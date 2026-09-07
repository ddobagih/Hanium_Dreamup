package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthFrameSnapshotTest {
    private val depth = DepthImage16(2, 2, intArrayOf(1_000, 1_000, 1_000, 1_000))
    private val confidence = ConfidenceImage8(2, 2, ByteArray(4) { 255.toByte() })

    @Test
    fun matchingRawTimestampIsFreshQualificationEvidence() {
        val snapshot = snapshot(rawTimestampNs = 100L)

        assertTrue(snapshot.hasFreshMetricRawDepth)
        assertEquals(1f, snapshot.rawDepthFreshnessQuality, 0f)
    }

    @Test
    fun reprojectedRawTimestampIsDownweightedAndNotFreshQualificationEvidence() {
        val snapshot = snapshot(rawTimestampNs = 90L)

        assertFalse(snapshot.hasFreshMetricRawDepth)
        assertEquals(0.70f, snapshot.rawDepthFreshnessQuality, 0f)
    }

    private fun snapshot(rawTimestampNs: Long): DepthFrameSnapshot = DepthFrameSnapshot(
        frameTimestampNs = 100L,
        rawDepth = depth,
        rawConfidence = confidence,
        fullDepth = null,
        rawDepthTimestampNs = rawTimestampNs,
    )
}

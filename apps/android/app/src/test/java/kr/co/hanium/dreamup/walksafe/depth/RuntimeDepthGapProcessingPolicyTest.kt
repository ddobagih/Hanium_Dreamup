package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDepthGapProcessingPolicyTest {
    @Test
    fun completeDepthLossAllowsCurrentVisualProcessingWhileMetricOutputStaysClosed() {
        val assessment = assessRuntimeDepthFrame(200L, 100L, 1_100L, 1_000L, false, 2_000L)
        assertFalse(assessment.outputAllowed)
        assertFalse(assessment.refreshesLastValidTime)
        assertFalse(assessment.shouldHandleLoss)
        assertTrue(allows(nowMs = 1_100L))
    }

    @Test
    fun boundedGapExpiresImmediatelyAfter600Ms() {
        assertTrue(allows(nowMs = 1_600L))
        assertFalse(allows(nowMs = 1_601L))
    }

    @Test
    fun unavailableTrackingCannotUsePreviousDepth() {
        assertFalse(allows(tracking = false))
    }

    @Test
    fun oldOrUnassessedFrameCannotUseGapPermission() {
        assertFalse(allows(frameNs = 199L))
        assertFalse(allows(frameNs = 201L))
        assertFalse(allowsRuntimeDepthGapProcessing(0L, 0L, 1_100L, 1_000L, true))
    }

    @Test
    fun missingOrFutureLastValidEvidenceCannotSeedGapPermission() {
        assertFalse(allows(nowMs = 999L))
        assertFalse(allowsRuntimeDepthGapProcessing(200L, 200L, 100L, 0L, true))
    }

    @Test
    fun repeatedMissingFramesDoNotExtendMetricOrVisualEvidenceDeadline() {
        var lastValidAtMs = 1_000L
        var previousFrameNs = 100L
        var accepted = 0
        for (step in 1L..8L) {
            val frameNs = 100L + step
            val nowMs = 1_000L + step * 100L
            val assessment = assessRuntimeDepthFrame(frameNs, previousFrameNs, nowMs, lastValidAtMs, false, 2_000L)
            if (assessment.refreshesLastValidTime) lastValidAtMs = nowMs
            if (assessment.isNewFrame) previousFrameNs = frameNs
            if (allowsRuntimeDepthGapProcessing(frameNs, previousFrameNs, nowMs, lastValidAtMs, true)) accepted++
            assertFalse(assessment.outputAllowed)
        }
        assertEquals(6, accepted)
        assertEquals(1_000L, lastValidAtMs)
    }

    @Test
    fun measuredRecoveryAloneRefreshesTheNextGapDeadline() {
        val recovered = assessRuntimeDepthFrame(300L, 200L, 1_700L, 1_000L, true, 2_000L)
        assertTrue(recovered.refreshesLastValidTime)
        assertTrue(allowsRuntimeDepthGapProcessing(400L, 400L, 2_300L, 1_700L, true))
        assertFalse(allowsRuntimeDepthGapProcessing(400L, 400L, 2_301L, 1_700L, true))
    }

    private fun allows(nowMs: Long = 1_100L, frameNs: Long = 200L, tracking: Boolean = true) =
        allowsRuntimeDepthGapProcessing(frameNs, 200L, nowMs, 1_000L, tracking)
}

package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDepthFramePolicyTest {
    @Test
    fun cameraTimeoutRepeatsFrameWithoutStoppingSessionOrRefreshingEvidenceAge() {
        val repeated = assess(timestampNs = 100L, nowMs = 1_066L)

        assertFalse(repeated.isNewFrame)
        assertTrue(repeated.outputAllowed)
        assertFalse(repeated.refreshesLastValidTime)
        assertFalse(repeated.shouldHandleLoss)
    }

    @Test
    fun endlesslyRepeatedFrameEventuallyExpiresEvenWhenItsOldPixelsRemainValid() {
        val expired = assess(timestampNs = 100L, nowMs = 3_000L)

        assertFalse(expired.outputAllowed)
        assertFalse(expired.refreshesLastValidTime)
        assertTrue(expired.shouldHandleLoss)
    }

    @Test
    fun temporaryMissingDepthSuppressesDistanceButKeepsSessionAvailableForRecovery() {
        val missing = assess(timestampNs = 200L, nowMs = 1_033L, valid = false)
        assertFalse(missing.outputAllowed)
        assertFalse(missing.shouldHandleLoss)

        val recovered = assess(timestampNs = 300L, nowMs = 1_066L)
        assertTrue(recovered.outputAllowed)
        assertTrue(recovered.refreshesLastValidTime)
        assertFalse(recovered.shouldHandleLoss)

        val lost = assess(timestampNs = 400L, nowMs = 3_000L, valid = false)
        assertFalse(lost.outputAllowed)
        assertTrue(lost.shouldHandleLoss)
    }

    @Test
    fun timestampRegressionCannotReusePreviousEvidence() {
        val regressed = assess(timestampNs = 99L, nowMs = 1_033L)

        assertFalse(regressed.outputAllowed)
        assertFalse(regressed.refreshesLastValidTime)
        assertTrue(regressed.shouldHandleLoss)
    }

    @Test
    fun zeroTimestampNeverPublishesDistance() {
        val empty = assessRuntimeDepthFrame(0L, 0L, 1_000L, 1_000L, true, 2_000L)

        assertFalse(empty.outputAllowed)
        assertFalse(empty.refreshesLastValidTime)
        assertFalse(empty.shouldHandleLoss)
    }

    private fun assess(timestampNs: Long, nowMs: Long, valid: Boolean = true) = assessRuntimeDepthFrame(
        frameTimestampNanos = timestampNs,
        previousFrameTimestampNanos = 100L,
        observedAtElapsedRealtimeMs = nowMs,
        lastValidFrameAtElapsedRealtimeMs = 1_000L,
        samplesAreValid = valid,
        staleTimeoutMs = 2_000L,
    )
}

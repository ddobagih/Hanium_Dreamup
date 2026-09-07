package kr.co.hanium.dreamup.walksafe.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeMetricFrameContinuityPolicyTest {
    @Test
    fun duplicateAfterSixtySixMillisecondsSuppressesOutputWithoutLosingMeasurement() {
        val result = evaluate(frameTimestampNanos = 1_000_000_000L)
        assertFalse(result.newFrame)
        assertFalse(result.outputAllowed)
        assertFalse(result.measurementLost)
    }

    @Test
    fun reversedTimestampCannotAdvanceFrameEvidenceWithinFreshWindow() {
        val result = evaluate(frameTimestampNanos = 999_999_999L)
        assertFalse(result.newFrame)
        assertFalse(result.outputAllowed)
        assertFalse(result.measurementLost)
    }

    @Test
    fun duplicateBecomesLostAtExactStaleBoundary() {
        val fresh = evaluate(
            frameTimestampNanos = 1_000_000_000L,
            nowElapsedRealtimeMs = 11_999L,
        )
        val expired = evaluate(
            frameTimestampNanos = 1_000_000_000L,
            nowElapsedRealtimeMs = 12_000L,
        )
        assertFalse(fresh.outputAllowed)
        assertFalse(fresh.measurementLost)
        assertFalse(expired.outputAllowed)
        assertTrue(expired.measurementLost)
    }

    @Test
    fun newValidFrameRestoresOutputAfterPreviousEvidenceExpires() {
        val result = evaluate(nowElapsedRealtimeMs = 12_000L)
        assertTrue(result.newFrame)
        assertTrue(result.outputAllowed)
        assertFalse(result.measurementLost)
    }

    @Test
    fun invalidSamplesSuppressOutputAndLoseMeasurementOnlyWhenExpired() {
        val fresh = evaluate(samplesValid = false)
        val expired = evaluate(samplesValid = false, nowElapsedRealtimeMs = 12_000L)
        assertTrue(fresh.newFrame)
        assertFalse(fresh.outputAllowed)
        assertFalse(fresh.measurementLost)
        assertTrue(expired.newFrame)
        assertFalse(expired.outputAllowed)
        assertTrue(expired.measurementLost)
    }

    @Test
    fun zeroTimestampCannotRefreshEvidenceOrProduceOutput() {
        val fresh = evaluate(frameTimestampNanos = 0L)
        val expired = evaluate(frameTimestampNanos = 0L, nowElapsedRealtimeMs = 12_000L)
        assertFalse(fresh.newFrame)
        assertFalse(fresh.outputAllowed)
        assertFalse(fresh.measurementLost)
        assertFalse(expired.newFrame)
        assertFalse(expired.outputAllowed)
        assertTrue(expired.measurementLost)
    }

    @Test
    fun elapsedTimeRollbackFailsClosedEvenForNewValidFrame() {
        val result = evaluate(nowElapsedRealtimeMs = 9_999L)
        assertTrue(result.newFrame)
        assertFalse(result.outputAllowed)
        assertTrue(result.measurementLost)
    }

    @Test
    fun negativeElapsedTimesFailClosed() {
        val negativeNow = evaluate(nowElapsedRealtimeMs = -1L, lastValidFrameAtElapsedRealtimeMs = 0L)
        val negativeLastValid = evaluate(nowElapsedRealtimeMs = 0L, lastValidFrameAtElapsedRealtimeMs = -1L)
        assertFalse(negativeNow.outputAllowed)
        assertTrue(negativeNow.measurementLost)
        assertFalse(negativeLastValid.outputAllowed)
        assertTrue(negativeLastValid.measurementLost)
    }

    @Test
    fun nonPositiveTimeoutFailsClosedEvenForNewValidFrame() {
        listOf(0L, -1L).forEach { timeout ->
            val result = evaluate(staleTimeoutMs = timeout)
            assertFalse(result.outputAllowed)
            assertTrue(result.measurementLost)
        }
    }

    private fun evaluate(
        frameTimestampNanos: Long = 1_000_000_001L,
        previousFrameTimestampNanos: Long = 1_000_000_000L,
        samplesValid: Boolean = true,
        nowElapsedRealtimeMs: Long = 10_066L,
        lastValidFrameAtElapsedRealtimeMs: Long = 10_000L,
        staleTimeoutMs: Long = 2_000L,
    ) = RuntimeMetricFrameContinuityPolicy.evaluate(
        frameTimestampNanos = frameTimestampNanos,
        previousFrameTimestampNanos = previousFrameTimestampNanos,
        samplesValid = samplesValid,
        nowElapsedRealtimeMs = nowElapsedRealtimeMs,
        lastValidFrameAtElapsedRealtimeMs = lastValidFrameAtElapsedRealtimeMs,
        staleTimeoutMs = staleTimeoutMs,
    )
}

package kr.co.hanium.dreamup.walksafe.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S10-22: tracking loss may move the next detection earlier, but only inside the measured pacing budget. */
class AdaptiveInferencePacingEarlyStartTest {
    @Test
    fun earlyRequestCannotBypassAnInFlightDetectorTask() {
        val policy = AdaptiveInferencePacingPolicy()
        val ticket = checkNotNull(policy.tryStart(1_000L))
        assertFalse(policy.requestEarlyStart(1_050L))
        assertNull(policy.tryStart(1_100L))
        assertTrue(policy.complete(ticket, 1_060L, inferenceDurationMs = 50L, thermalThrottled = false))
        assertEquals(1_250L, policy.snapshot().nextEligibleAtElapsedRealtimeMs)
    }

    @Test
    fun earlyRequestSkipsTheRecoveryHoldButKeepsMinimumIntervalAndCooldown() {
        val policy = AdaptiveInferencePacingPolicy()
        val ticket = checkNotNull(policy.tryStart(1_000L))
        assertTrue(policy.complete(ticket, 1_060L, inferenceDurationMs = 50L, thermalThrottled = false))
        // A fast first sample does not yet earn a faster target (four fresh samples are required).
        assertEquals(250L, policy.snapshot().targetIntervalMs)
        assertEquals(1_250L, policy.snapshot().nextEligibleAtElapsedRealtimeMs)
        assertTrue(policy.requestEarlyStart(1_070L))
        // max(start + 100 ms, completion + 25 ms cooldown)
        assertEquals(1_100L, policy.snapshot().nextEligibleAtElapsedRealtimeMs)
        assertFalse(policy.isDue(1_099L))
        assertFalse("Repeated requests are idempotent", policy.requestEarlyStart(1_080L))
        val early = checkNotNull(policy.tryStart(1_100L))
        // Normal pacing resumes after the early start is consumed.
        assertEquals(1_350L, policy.snapshot().nextEligibleAtElapsedRealtimeMs)
        assertFalse(policy.requestEarlyStart(1_120L))
        assertTrue(policy.complete(early, 1_160L, inferenceDurationMs = 50L, thermalThrottled = false))
    }

    @Test
    fun slowMeasuredWorkIsNotShortenedBelowItsCompletionCooldown() {
        val policy = AdaptiveInferencePacingPolicy()
        val ticket = checkNotNull(policy.tryStart(1_000L))
        assertTrue(policy.complete(ticket, 1_400L, inferenceDurationMs = 380L, thermalThrottled = false))
        val next = policy.snapshot().nextEligibleAtElapsedRealtimeMs
        assertFalse(policy.requestEarlyStart(1_410L))
        assertEquals(next, policy.snapshot().nextEligibleAtElapsedRealtimeMs)
        assertTrue(next >= 1_400L + policy.snapshot().cooldownMs)
    }

    @Test
    fun knownThermalHoldAndInvalidTimingCannotEarnAnEarlyStart() {
        val thermal = AdaptiveInferencePacingPolicy()
        val hot = checkNotNull(thermal.tryStart(1_000L))
        assertTrue(thermal.complete(hot, 1_060L, inferenceDurationMs = 50L, thermalThrottled = true))
        val thermalNext = thermal.snapshot().nextEligibleAtElapsedRealtimeMs
        assertFalse(thermal.requestEarlyStart(1_070L))
        assertEquals(thermalNext, thermal.snapshot().nextEligibleAtElapsedRealtimeMs)

        val invalid = AdaptiveInferencePacingPolicy()
        val bad = checkNotNull(invalid.tryStart(1_000L))
        assertFalse(invalid.complete(bad, 1_060L, inferenceDurationMs = -1L))
        val invalidNext = invalid.snapshot().nextEligibleAtElapsedRealtimeMs
        assertFalse(invalid.requestEarlyStart(1_070L))
        assertEquals(invalidNext, invalid.snapshot().nextEligibleAtElapsedRealtimeMs)
        assertFalse(invalid.requestEarlyStart(-1L))
    }

    @Test
    fun observedLoadPressureKeepsTheLoadBackoffAndIgnoresEarlyRequests() {
        val policy = AdaptiveInferencePacingPolicy()
        val ticket = checkNotNull(policy.tryStart(1_000L))
        assertTrue(
            policy.complete(
                ticket,
                1_060L,
                inferenceDurationMs = 50L,
                thermalThrottled = false,
                // Depth work took twice its budget: the other safety stages are already late.
                loadObservation = InferenceLoadObservation(
                    observedAtElapsedRealtimeMs = 1_050L,
                    depthProcessingMs = 40L,
                    depthBudgetMs = 20L,
                ),
            ),
        )
        assertTrue(policy.snapshot().observedLoadPressure > 0.0)
        assertEquals(InferencePacingReason.OBSERVED_LOAD_BACKOFF, policy.snapshot().reason)
        val next = policy.snapshot().nextEligibleAtElapsedRealtimeMs
        assertFalse("S10-22-F1: tracking loss must not override the load backoff", policy.requestEarlyStart(1_070L))
        assertEquals(next, policy.snapshot().nextEligibleAtElapsedRealtimeMs)
        assertFalse(policy.isDue(next - 1L))
    }

    @Test
    fun aSampleOverTheFreshnessBudgetCannotEarnAnEarlyStart() {
        val policy = AdaptiveInferencePacingPolicy(maximumFreshFrameAgeMs = 50L)
        val ticket = checkNotNull(policy.tryStart(1_000L))
        assertTrue(policy.complete(ticket, 1_060L, inferenceDurationMs = 50L, thermalThrottled = false))
        assertEquals(true, policy.snapshot().overFreshnessBudget)
        val next = policy.snapshot().nextEligibleAtElapsedRealtimeMs
        assertFalse(policy.requestEarlyStart(1_070L))
        assertEquals(next, policy.snapshot().nextEligibleAtElapsedRealtimeMs)
    }

    @Test
    fun cancelledAcquisitionOnlyAllowsTheMinimumIntervalAfterItsStart() {
        val policy = AdaptiveInferencePacingPolicy()
        val ticket = checkNotNull(policy.tryStart(1_000L))
        assertTrue(policy.cancel(ticket))
        assertTrue(policy.requestEarlyStart(1_010L))
        assertEquals(1_100L, policy.snapshot().nextEligibleAtElapsedRealtimeMs)
        policy.reset()
        assertFalse("Reset already makes the next frame eligible", policy.requestEarlyStart(1_020L))
        assertTrue(policy.isDue(1_020L))
    }
}

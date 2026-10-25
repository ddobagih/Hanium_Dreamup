package kr.co.hanium.dreamup.walksafe.navigation.start

import org.junit.Assert.*
import org.junit.Test

class GuidanceStartCalibrationRetryBackoffTest {
    @Test fun repeatedRefreshCannotClaimBeforeTheFirstFailurePlusOneSecond() {
        val f = Fixture()
        assertTrue(f.c.voiceFailed(f.lease, 2_000L, true, retryDelayMs = 1_000L))
        for (now in listOf(2_001L, 2_300L, 2_999L)) {
            assertSame(f.attempt, f.c.begin(f.request, now))
            assertEquals(GuidanceStartCalibration.Phase.WAIT_VOICE_DONE, f.c.evaluate(f.attempt, now, true)?.phase)
            assertNull(f.c.claimPrompt(f.attempt, now, true, false))
            assertNull(f.c.snapshot?.readyAtMs)
        }
        val retry = requireNotNull(f.c.claimPrompt(f.attempt, 3_000L, true, false))
        assertSame(f.attempt, retry.attempt)
        assertTrue(retry.serial > f.lease.serial)
        assertTrue(f.c.isPromptCurrent(retry))
    }

    @Test fun fiveHundredRefreshesCannotBypassOrRenewTheSubsequentFiveSecondDeadline() {
        val f = Fixture()
        f.c.voiceFailed(f.lease, 2_000L, true, retryDelayMs = 1_000L)
        val second = requireNotNull(f.c.claimPrompt(f.attempt, 3_000L, true, false))
        assertTrue(f.c.voiceFailed(second, 4_000L, true, retryDelayMs = 5_000L))
        repeat(500) { i ->
            val now = 4_001L + i * 10L
            assertSame(f.attempt, f.c.begin(f.request, now))
            assertNotNull(f.c.evaluate(f.attempt, now, true))
            assertNull("Refresh $i at $now must retain the original 9000 deadline", f.c.claimPrompt(f.attempt, now, true, false))
        }
        assertNull(f.c.claimPrompt(f.attempt, 8_999L, true, false))
        val retry = requireNotNull(f.c.claimPrompt(f.attempt, 9_000L, true, false))
        assertTrue(retry.serial > second.serial)
        assertSame(f.attempt, retry.attempt)
        assertNull(f.c.snapshot?.voiceDoneAtMs)
    }

    @Test fun duplicateFailureAndLateDoneCannotRefreshTheOriginalRetryDeadline() {
        val f = Fixture()
        f.c.voiceFailed(f.lease, 2_000L, true, retryDelayMs = 1_000L)
        assertFalse(f.c.voiceFailed(f.lease, 2_500L, true, retryDelayMs = 5_000L))
        assertFalse(f.c.voiceDone(f.lease, 2_600L, 2_600L, true))
        assertNull(f.c.claimPrompt(f.attempt, 2_999L, true, false))
        val retry = requireNotNull(f.c.claimPrompt(f.attempt, 3_000L, true, false))
        assertFalse(f.c.voiceFailed(f.lease, 20_000L, true, retryDelayMs = 5_000L))
        assertFalse(f.c.voiceDone(f.lease, 20_000L, 20_000L, true))
        assertTrue(f.c.isPromptCurrent(retry))
        assertTrue(f.c.voiceFailed(retry, 4_000L, true, retryDelayMs = 5_000L))
        assertFalse(f.c.voiceFailed(f.lease, 4_500L, true, retryDelayMs = 5_000L))
        assertNull(f.c.claimPrompt(f.attempt, 8_999L, true, false))
        assertNotNull(f.c.claimPrompt(f.attempt, 9_000L, true, false))
    }

    @Test fun ownerLossCancellationAndReplacementCannotCarryAnOldLeaseOrDelayIntoTheNextIntent() {
        val retire: List<(Fixture, Any) -> Unit> = listOf(
            { f, _ -> f.c.cancel() },
            { f, _ -> assertNull(f.c.evaluate(f.attempt, 2_100L, false)) },
            { f, replacement -> assertNotNull(f.c.begin(replacement, 2_100L)) },
        )
        for (change in retire) {
            val f = Fixture()
            f.c.voiceFailed(f.lease, 2_000L, true, retryDelayMs = 5_000L)
            val replacement = Any()
            change(f, replacement)
            val next = requireNotNull(f.c.begin(replacement, 2_100L))
            assertNotSame(f.attempt, next)
            assertNull(f.c.claimPrompt(f.attempt, 20_000L, true, false))
            assertFalse(f.c.voiceFailed(f.lease, 20_000L, true, retryDelayMs = Long.MAX_VALUE))
            assertFalse(f.c.voiceDone(f.lease, 20_000L, 20_000L, true))
            val lease = requireNotNull(f.c.claimPrompt(next, 2_100L, true, false))
            assertTrue(f.c.voiceDone(lease, 2_200L, 2_200L, true))
            assertEquals(5_200L, f.c.snapshot?.readyAtMs)
        }
    }

    @Test fun negativeOrOverflowingDelayRetiresTheCurrentAttemptWhileTheLargestValidDelayKeepsItsBoundary() {
        for (delay in listOf(-1L, Long.MAX_VALUE)) {
            val f = Fixture()
            assertFalse(f.c.voiceFailed(f.lease, 2_000L, true, retryDelayMs = delay))
            assertNull(f.c.currentAttempt)
            assertNull(f.c.snapshot)
            assertNull(f.c.claimPrompt(f.attempt, 3_000L, true, false))
        }
        val f = Fixture()
        assertTrue(f.c.voiceFailed(f.lease, 2_000L, true, retryDelayMs = Long.MAX_VALUE - 2_000L))
        assertNull(f.c.claimPrompt(f.attempt, Long.MAX_VALUE - 1L, true, false))
        assertNotNull(f.c.claimPrompt(f.attempt, Long.MAX_VALUE, true, false))
    }

    private class Fixture {
        val request = Any()
        val c = GuidanceStartCalibration()
        val attempt = requireNotNull(c.begin(request, 1_000L))
        val lease = requireNotNull(c.claimPrompt(attempt, 1_000L, true, false))
    }
}

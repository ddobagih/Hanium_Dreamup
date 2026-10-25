package kr.co.hanium.dreamup.walksafe.navigation.start

import org.junit.Assert.*
import org.junit.Test

class GuidanceStartCalibrationTest {
    @Test fun elapsedTimeBeforeActualDoneCannotReleaseGuidance() {
        val c = GuidanceStartCalibration()
        val attempt = requireNotNull(c.begin(Any(), 1_000L))
        requireNotNull(c.claimPrompt(attempt, 1_000L, true, false))
        assertEquals(GuidanceStartCalibration.Phase.WAIT_VOICE_DONE, c.evaluate(attempt, 50_000L, true)?.phase)
        assertNull(c.snapshot?.voiceDoneAtMs)
        assertNull(c.snapshot?.readyAtMs)
    }

    @Test fun exactDeadlineIsDonePlusThreeSecondsRatherThanTheStartRequest() {
        val f = Fixture()
        assertTrue(f.c.voiceDone(f.lease, 5_000L, 5_000L, true))
        assertEquals(5_000L, f.c.snapshot?.voiceDoneAtMs)
        assertEquals(8_000L, f.c.snapshot?.readyAtMs)
        assertEquals(GuidanceStartCalibration.Phase.OBSERVING, f.c.evaluate(f.attempt, 7_999L, true)?.phase)
        assertEquals(GuidanceStartCalibration.Phase.READY, f.c.evaluate(f.attempt, 8_000L, true)?.phase)
    }

    @Test fun delayedDoneReceiptUsesTheOriginalDoneTime() {
        val f = Fixture()
        f.c.evaluate(f.attempt, 6_000L, true)
        assertTrue(f.c.voiceDone(f.lease, 5_000L, 7_000L, true))
        assertEquals(8_000L, f.c.snapshot?.readyAtMs)
        assertEquals(GuidanceStartCalibration.Phase.READY, f.c.evaluate(f.attempt, 8_000L, true)?.phase)
    }

    @Test fun delayedReceiptPastTheDeadlineDoesNotInventAnAdditionalThreeSeconds() {
        val f = Fixture()
        assertTrue(f.c.voiceDone(f.lease, 5_000L, 9_000L, true))
        assertEquals(GuidanceStartCalibration.Phase.READY, f.c.snapshot?.phase)
        assertEquals(8_000L, f.c.snapshot?.readyAtMs)
    }

    @Test fun busySpeechKeepsTheIntentWithoutClaimingAnUtterance() {
        val c = GuidanceStartCalibration()
        val attempt = requireNotNull(c.begin(Any(), 1_000L))
        assertNull(c.claimPrompt(attempt, 2_000L, true, true))
        assertEquals(GuidanceStartCalibration.Phase.WAIT_VOICE_DONE, c.snapshot?.phase)
        assertNull(c.snapshot?.prompt)
        val lease = requireNotNull(c.claimPrompt(attempt, 3_000L, true, false))
        assertTrue(c.isPromptCurrent(lease))
        assertNull(c.claimPrompt(attempt, 4_000L, true, false))
    }

    @Test fun speakingFailureRetriesTheSameIntentWithANewLeaseAndRejectsOldDone() {
        val f = Fixture()
        assertTrue(f.c.voiceFailed(f.lease, 2_000L, true))
        assertSame(f.attempt, f.c.currentAttempt)
        assertEquals(GuidanceStartCalibration.Phase.WAIT_VOICE_DONE, f.c.snapshot?.phase)
        assertNull(f.c.snapshot?.readyAtMs)
        val retry = requireNotNull(f.c.claimPrompt(f.attempt, 3_000L, true, false))
        assertTrue(retry.serial > f.lease.serial)
        assertFalse(f.c.voiceDone(f.lease, 4_000L, 4_000L, true))
        assertFalse(f.c.voiceFailed(f.lease, 4_000L, false))
        assertTrue(f.c.isPromptCurrent(retry))
        assertTrue(f.c.voiceDone(retry, 4_500L, 4_500L, true))
        assertEquals(7_500L, f.c.snapshot?.readyAtMs)
    }

    @Test fun duplicateDoneAndLateFailureDoNotRenewOrEraseTheObservationWindow() {
        val f = Fixture()
        assertTrue(f.c.voiceDone(f.lease, 2_000L, 2_000L, true))
        assertFalse(f.c.voiceDone(f.lease, 3_000L, 3_000L, true))
        assertFalse(f.c.voiceFailed(f.lease, 3_000L, true))
        assertEquals(2_000L, f.c.snapshot?.voiceDoneAtMs)
        assertEquals(5_000L, f.c.snapshot?.readyAtMs)
        assertEquals(GuidanceStartCalibration.Phase.READY, f.c.evaluate(f.attempt, 5_000L, true)?.phase)
    }

    @Test fun reenteringTheSameIntentPreservesItsReadyStateAndDoesNotRepeatThePrompt() {
        val request = Any()
        val c = GuidanceStartCalibration()
        val attempt = requireNotNull(c.begin(request, 1_000L))
        val lease = requireNotNull(c.claimPrompt(attempt, 1_000L, true, false))
        c.voiceDone(lease, 2_000L, 2_000L, true)
        c.evaluate(attempt, 5_000L, true)
        assertSame(attempt, c.begin(request, 6_000L))
        assertNull(c.claimPrompt(attempt, 6_000L, true, false))
        assertEquals(GuidanceStartCalibration.Phase.READY, c.snapshot?.phase)
        assertEquals(5_000L, c.snapshot?.readyAtMs)
    }

    @Test fun sameIntentDuringObservationDoesNotRestartItsDeadline() {
        val request = Any()
        val c = GuidanceStartCalibration()
        val attempt = requireNotNull(c.begin(request, 1_000L))
        val lease = requireNotNull(c.claimPrompt(attempt, 1_000L, true, false))
        c.voiceDone(lease, 2_000L, 2_000L, true)
        assertSame(attempt, c.begin(request, 4_000L))
        assertEquals(5_000L, c.snapshot?.readyAtMs)
        assertEquals(GuidanceStartCalibration.Phase.READY, c.evaluate(attempt, 5_000L, true)?.phase)
    }

    @Test fun anotherRequestInvalidatesOldDoneAndTimerWithoutPoisoningTheNewClock() {
        val f = Fixture()
        val next = requireNotNull(f.c.begin(Any(), 2_000L))
        val lease = requireNotNull(f.c.claimPrompt(next, 2_000L, true, false))
        assertTrue(next.generation > f.attempt.generation)
        assertFalse(f.c.voiceDone(f.lease, 20_000L, 20_000L, false))
        assertNull(f.c.evaluate(f.attempt, 20_000L, false))
        assertTrue(f.c.voiceDone(lease, 3_000L, 3_000L, true))
        assertEquals(6_000L, f.c.snapshot?.readyAtMs)
    }

    @Test fun permissionOrOwnerCancellationInvalidatesDoneAndQueuedTimers() {
        for (cancelExplicitly in listOf(false, true)) {
            val f = Fixture()
            if (cancelExplicitly) f.c.cancel() else assertNull(f.c.evaluate(f.attempt, 2_000L, false))
            assertFalse(f.c.voiceDone(f.lease, 3_000L, 3_000L, true))
            assertNull(f.c.evaluate(f.attempt, 6_000L, true))
            assertNull(f.c.currentAttempt)
        }
    }

    @Test fun ownerLossDuringObservingCannotBecomeReadyFromALateTimer() {
        val f = Fixture()
        f.c.voiceDone(f.lease, 2_000L, 2_000L, true)
        assertNull(f.c.evaluate(f.attempt, 4_000L, false))
        assertNull(f.c.evaluate(f.attempt, 5_000L, true))
    }

    @Test fun clockRollbackInvalidatesTheAttemptAndCannotBeUsedToRenewIt() {
        val request = Any()
        val c = GuidanceStartCalibration()
        val attempt = requireNotNull(c.begin(request, 1_000L))
        val lease = requireNotNull(c.claimPrompt(attempt, 2_000L, true, false))
        assertFalse(c.voiceDone(lease, 1_999L, 1_999L, true))
        assertNull(c.currentAttempt)
        assertNull(c.begin(request, 1_999L))
        assertNotNull(c.begin(Any(), 2_001L))
    }

    @Test fun futureDoneDoneBeforeClaimAndOverflowCannotManufactureReadiness() {
        for ((done, receipt) in listOf(3_000L to 2_000L, 999L to 2_000L,
            Long.MAX_VALUE - 1L to Long.MAX_VALUE)) {
            val f = Fixture()
            assertFalse(f.c.voiceDone(f.lease, done, receipt, true))
            assertNull(f.c.snapshot)
            assertNull(f.c.evaluate(f.attempt, receipt, true))
        }
    }

    @Test fun negativeStartAndNegativeEvaluationRemainInvalid() {
        val c = GuidanceStartCalibration()
        assertNull(c.begin(Any(), -1L))
        val attempt = requireNotNull(c.begin(Any(), 1_000L))
        assertNull(c.evaluate(attempt, -1L, true))
        assertNull(c.snapshot)
    }

    @Test fun anAttemptOrPromptFromAnotherControllerCannotBeAccepted() {
        val f = Fixture()
        val other = Fixture()
        assertFalse(f.c.voiceDone(other.lease, 2_000L, 2_000L, true))
        assertNull(f.c.evaluate(other.attempt, 2_000L, true))
        assertTrue(f.c.isPromptCurrent(f.lease))
    }

    @Test fun constructorAndSnapshotReadAreInert() {
        val c = GuidanceStartCalibration()
        assertNull(c.currentAttempt)
        assertNull(c.snapshot)
        val attempt = requireNotNull(c.begin(Any(), 1_000L))
        val first = c.snapshot
        assertEquals(first, c.snapshot)
        assertSame(attempt, c.currentAttempt)
        assertEquals(GuidanceStartCalibration.Phase.WAIT_VOICE_DONE, c.snapshot?.phase)
    }

    private class Fixture {
        val c = GuidanceStartCalibration()
        val attempt = requireNotNull(c.begin(Any(), 1_000L))
        val lease = requireNotNull(c.claimPrompt(attempt, 1_000L, true, false))
    }
}

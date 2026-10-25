package kr.co.hanium.dreamup.walksafe.runtime.vision.unknown

import kr.co.hanium.dreamup.walksafe.inference.AdaptiveInferencePacingPolicy
import kr.co.hanium.dreamup.walksafe.inference.tracking.UnknownMaterialPointCaptureLease
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import org.junit.Assert.*
import org.junit.Test

class UnknownCaptureAttemptTest {
    @Test fun resultClosesTheExactLeaseAndRecordsOneOriginalAgeSample() {
        val f = Fixture()
        assertTrue(f.attempt.claimResult())
        assertFalse(f.attempt.claimResult())
        assertTrue(f.attempt.complete(1_120L, 35L, false))
        assertTrue(f.lease.isClosed)
        assertFalse(f.attempt.retireCaptureLease())
        assertFalse(f.attempt.complete(1_200L))
        assertFalse(f.attempt.cancel())
        assertEquals(1L, f.pacing.snapshot().completedSamples)
        assertEquals(120L, f.pacing.snapshot().lastEndToEndMs)
        assertEquals(35L, f.pacing.snapshot().lastInferenceMs)
    }

    @Test fun staleDiscardIsMeasuredButRejectionAndBackstopDoNotInventSamples() {
        val stale = Fixture()
        assertTrue(stale.attempt.complete(1_801L))
        assertEquals(1L, stale.pacing.snapshot().staleSamples)
        assertFalse(stale.attempt.claimResult())
        val rejected = Fixture()
        assertTrue(rejected.attempt.cancel())
        assertTrue(rejected.lease.isClosed)
        assertFalse(rejected.attempt.complete(1_100L))
        assertEquals(0L, rejected.pacing.snapshot().completedSamples)
        assertNull(rejected.pacing.snapshot().lastEndToEndMs)
        assertFalse(rejected.pacing.snapshot().inFlight)
    }

    @Test fun invalidationRetiresLeaseImmediatelyButConsumerStillOwnsItsMeasuredTerminal() {
        val f = Fixture()
        assertTrue(f.attempt.claimResult())
        assertTrue(f.attempt.retireCaptureLease())
        assertFalse(f.attempt.retireCaptureLease())
        assertTrue(f.lease.isClosed)
        assertTrue(f.pacing.snapshot().inFlight)
        assertTrue(f.attempt.complete(1_200L))
        assertEquals(1L, f.pacing.snapshot().completedSamples)
    }

    @Test fun lateOldTerminalCannotReleaseTheSuccessorsPacingOrLease() {
        val f = Fixture()
        f.attempt.cancel()
        val nextToken = token(2L, 1_300L)
        val nextLease = UnknownMaterialPointCaptureLease(nextToken, 0, Any())
        val next = UnknownCaptureAttempt(nextToken, requireNotNull(f.pacing.tryStart(1_300L)), f.pacing)
        assertTrue(next.attachLease(nextLease))
        assertFalse(f.attempt.complete(1_400L))
        assertFalse(f.attempt.cancel())
        assertFalse(nextLease.isClosed)
        assertTrue(f.pacing.snapshot().inFlight)
        assertTrue(next.complete(1_420L))
        assertTrue(nextLease.isClosed)
        assertEquals(120L, f.pacing.snapshot().lastEndToEndMs)
    }

    @Test fun leaseAndDiscardIdentityIncludeOriginalCaptureClockAndGeometry() {
        val f = Fixture()
        assertTrue(f.attempt.matches(token(1L, 1_000L)))
        assertFalse(f.attempt.matches(token(1L, 1_001L)))
        val foreign = UnknownMaterialPointCaptureLease(token(2L, 1_000L), 0, Any())
        assertFalse(f.attempt.attachLease(foreign))
        assertFalse(foreign.isClosed)
        f.attempt.cancel()
        assertTrue(f.lease.isClosed)
        assertFalse(foreign.isClosed)
        foreign.close()
    }

    @Test fun leaseRetirementBeforeReservationRejectsLaterAttachmentWithoutTakingOwnership() {
        val pacing = AdaptiveInferencePacingPolicy(800L)
        val capture = token(1L, 1_000L)
        val attempt = UnknownCaptureAttempt(capture, requireNotNull(pacing.tryStart(1_000L)), pacing)
        assertFalse(attempt.retireCaptureLease())
        val late = UnknownMaterialPointCaptureLease(capture, 0, Any())
        assertFalse(attempt.attachLease(late))
        assertFalse(late.isClosed)
        late.close() // The rejecting submit scope retains ownership.
        assertTrue(attempt.cancel())
        assertEquals(0L, pacing.snapshot().completedSamples)
    }

    private class Fixture {
        val pacing = AdaptiveInferencePacingPolicy(800L)
        private val capture = token(1L, 1_000L)
        val lease = UnknownMaterialPointCaptureLease(capture, 0, Any())
        val attempt = UnknownCaptureAttempt(capture, requireNotNull(pacing.tryStart(1_000L)), pacing)
        init { check(attempt.attachLease(lease)) }
    }

    private companion object {
        fun token(frame: Long, atMs: Long) = FastSamFrameToken(FastSamFrameToken.Source.LIVE_CAMERA,
            1L, frame, frame * 1_000_000L, frame * 1_000_000L, atMs * 1_000_000L, "geometry", 40, 32)
    }
}

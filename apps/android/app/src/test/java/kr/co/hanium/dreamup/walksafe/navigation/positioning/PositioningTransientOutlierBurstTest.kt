package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.*
import org.junit.Test

class PositioningTransientOutlierBurstTest {
    @Test fun rapidDistinctMultipathFixesDoNotPublishMovementOrTurnOneBurstIntoPersistentFailure() {
        val coordinator = PositioningCoordinator()
        val original = coordinator.observeGnss(fix(1_000L))
        repeat(15) { index ->
            val rejected = coordinator.observeGnss(fix(1_100L + index * 100L, 37.6 + index * 0.0001))
            assertEquals(GnssHardRejectReason.CORRUPT_JUMP, rejected.gnssUpdate?.hardRejectReason)
            assertTrue(rejected.transientOutlierAgainstFreshEstimate)
            assertEquals(original.filtered?.coordinate, rejected.filtered?.coordinate)
            assertEquals(original.filtered?.elapsedRealtimeMs, rejected.filtered?.elapsedRealtimeMs)
        }
    }

    @Test fun continuingDistinctJumpsFailAfterOneRecheckIntervalWithoutRenewingTheirStart() {
        val coordinator = PositioningCoordinator()
        coordinator.observeGnss(fix(1_000L))
        coordinator.observeGnss(fix(1_100L, 37.6))
        assertTrue(coordinator.observeGnss(fix(4_099L, 37.6001)).transientOutlierAgainstFreshEstimate)
        val persistent = coordinator.observeGnss(fix(4_100L, 37.6002))
        assertEquals(3, persistent.consecutiveCorruptJumpCount)
        assertEquals(3_000L, persistent.consecutiveCorruptJumpDurationMs)
        assertFalse(persistent.transientOutlierAgainstFreshEstimate)
    }

    @Test fun delayedReceiptsCannotDisguiseAContinuousFailureAsAShortMeasurementBurst() {
        val coordinator = PositioningCoordinator()
        coordinator.observeGnss(fix(1_000L))
        coordinator.observeGnss(fix(1_100L, 37.6))
        val delayed = coordinator.observeGnss(fix(1_400L, 37.6001).copy(receivedAtElapsedRealtimeMs = 4_100L))
        assertEquals(GnssHardRejectReason.CORRUPT_JUMP, delayed.gnssUpdate?.hardRejectReason)
        assertEquals(3_000L, delayed.consecutiveCorruptJumpDurationMs)
        assertFalse(delayed.transientOutlierAgainstFreshEstimate)
    }

    @Test fun aGenuineAcceptedFixEndsTheBurstAndTheNextBurstHasItsOwnHistory() {
        val coordinator = PositioningCoordinator()
        coordinator.observeGnss(fix(1_000L))
        coordinator.observeGnss(fix(1_100L, 37.6))
        coordinator.observeGnss(fix(1_400L, 37.6001))
        val recovered = coordinator.observeGnss(fix(2_000L))
        assertFalse(recovered.transientOutlierAgainstFreshEstimate)
        assertNull(recovered.consecutiveCorruptJumpDurationMs)
        coordinator.observeGnss(fix(4_000L, 37.6))
        val nextBurst = coordinator.observeGnss(fix(4_200L, 37.6001))
        assertEquals(2, nextBurst.consecutiveCorruptJumpCount)
        assertEquals(200L, nextBurst.consecutiveCorruptJumpDurationMs)
        assertTrue(nextBurst.transientOutlierAgainstFreshEstimate)
    }

    @Test fun mockCoordinatesAndExpiredEvidenceCannotUseBurstContinuity() {
        val coordinator = PositioningCoordinator()
        coordinator.observeGnss(fix(1_000L))
        coordinator.observeGnss(fix(1_100L, 37.6))
        assertFalse(coordinator.observeGnss(fix(1_200L).copy(mock = true)).transientOutlierAgainstFreshEstimate)
        assertFalse(coordinator.observeGnss(fix(1_300L).copy(latitude = Double.NaN)).transientOutlierAgainstFreshEstimate)
        val expired = coordinator.observeGnss(fix(11_001L, 37.6001))
        assertFalse(expired.transientOutlierAgainstFreshEstimate)
        assertFalse(expired.absoluteArrivalConfirmable)
    }

    @Test fun resetRetiresTheOldBurst() {
        val coordinator = PositioningCoordinator()
        coordinator.observeGnss(fix(1_000L))
        coordinator.observeGnss(fix(1_100L, 37.6))
        coordinator.observeGnss(fix(1_400L, 37.6001))
        coordinator.reset()
        coordinator.observeGnss(fix(2_000L))
        val next = coordinator.observeGnss(fix(2_100L, 37.6))
        assertEquals(1, next.consecutiveCorruptJumpCount)
        assertEquals(0L, next.consecutiveCorruptJumpDurationMs)
    }

    private fun fix(atMs: Long, latitude: Double = 37.0) =
        GnssPositionObservation(latitude, 127.0, 1.5, atMs)
}

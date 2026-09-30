package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S09-36 (originals/09.txt:96): rejection reasons are treated separately. One far outlier against a
 * fresh estimate keeps that estimate usable; spoofing suspicion, time errors and an expired
 * estimate stay fail-closed.
 */
class PositioningRejectReasonPolicyTest {
    @Test
    fun oneFarJumpAgainstAFreshEstimateKeepsIt() {
        val coordinator = PositioningCoordinator()
        coordinator.observeGnss(fix(37.5, 127.0, 1_000L))
        coordinator.observeGnss(fix(37.5, 127.00001, 2_000L))
        val jump = coordinator.observeGnss(fix(37.6, 127.0, 3_000L))
        assertEquals(GnssHardRejectReason.CORRUPT_JUMP, jump.gnssUpdate?.hardRejectReason)
        assertTrue(jump.singleOutlierAgainstFreshEstimate)
        assertFalse(jump.confidence.guidancePaused)
        // The kept estimate is the previous one, not the outlier.
        assertEquals(37.5, requireNotNull(jump.filtered).coordinate.latitude, 1e-4)
        // The next ordinary fix is used normally.
        val next = coordinator.observeGnss(fix(37.5, 127.00002, 4_000L))
        assertEquals(GnssObservationDisposition.APPLIED, next.gnssUpdate?.disposition)
    }

    @Test
    fun spoofingTimeAndCoordinateRejectionsStayFailClosed() {
        val coordinator = PositioningCoordinator()
        coordinator.observeGnss(fix(37.5, 127.0, 1_000L))
        val mock = coordinator.observeGnss(fix(37.5, 127.0, 2_000L).copy(mock = true))
        assertEquals(GnssHardRejectReason.MOCK, mock.gnssUpdate?.hardRejectReason)
        assertFalse(mock.singleOutlierAgainstFreshEstimate)
        val badTime = coordinator.observeGnss(fix(37.5, 127.0, 2_500L).copy(receivedAtElapsedRealtimeMs = 2_400L))
        assertEquals(GnssHardRejectReason.INVALID_TIME, badTime.gnssUpdate?.hardRejectReason)
        assertFalse(badTime.singleOutlierAgainstFreshEstimate)
        val stale = coordinator.observeGnss(fix(37.5, 127.0, 3_000L).copy(receivedAtElapsedRealtimeMs = 14_000L))
        assertEquals(GnssHardRejectReason.STALE, stale.gnssUpdate?.hardRejectReason)
        assertFalse(stale.singleOutlierAgainstFreshEstimate)
        val accepted = coordinator.observeGnss(fix(37.5, 127.0, 4_000L))
        assertFalse(accepted.singleOutlierAgainstFreshEstimate)
    }

    @Test
    fun aJumpAfterTheEstimateExpiredIsNotKept() {
        val coordinator = PositioningCoordinator()
        coordinator.observeGnss(fix(37.5, 127.0, 1_000L))
        // 12 s later the previous estimate is past the 10 s confidence age.
        val jump = coordinator.observeGnss(fix(38.5, 127.0, 13_000L))
        assertEquals(GnssHardRejectReason.CORRUPT_JUMP, jump.gnssUpdate?.hardRejectReason)
        assertFalse(jump.singleOutlierAgainstFreshEstimate)
    }

    /** w25 review pos-F8 / Root R15: a run of distinct far fixes is not a single outlier. */
    @Test
    fun aSecondDistinctFarJumpInARowGoesToTheFailClosedPath() {
        val coordinator = PositioningCoordinator()
        coordinator.observeGnss(fix(37.5, 127.0, 1_000L))
        coordinator.observeGnss(fix(37.5, 127.00001, 2_000L))
        val first = coordinator.observeGnss(fix(37.6, 127.0, 3_000L))
        assertEquals(GnssHardRejectReason.CORRUPT_JUMP, first.gnssUpdate?.hardRejectReason)
        assertEquals(1, first.consecutiveCorruptJumpCount)
        assertTrue(first.singleOutlierAgainstFreshEstimate)
        // The same measurement delivered again is not a second, distinct jump.
        val repeated = coordinator.observeGnss(fix(37.6, 127.0, 3_000L))
        assertEquals(GnssHardRejectReason.CORRUPT_JUMP, repeated.gnssUpdate?.hardRejectReason)
        assertEquals(1, repeated.consecutiveCorruptJumpCount)
        assertTrue(repeated.singleOutlierAgainstFreshEstimate)
        // A second, consistent far fix: no longer "single"; the estimate itself is still fresh.
        val second = coordinator.observeGnss(fix(37.6001, 127.0, 4_000L))
        assertEquals(GnssHardRejectReason.CORRUPT_JUMP, second.gnssUpdate?.hardRejectReason)
        assertEquals(2, second.consecutiveCorruptJumpCount)
        assertFalse(second.confidence.guidancePaused)
        assertFalse(second.singleOutlierAgainstFreshEstimate)
        val third = coordinator.observeGnss(fix(37.6002, 127.0, 5_000L))
        assertFalse(third.singleOutlierAgainstFreshEstimate)
        // A rejection for another reason neither counts nor ends the run.
        val mock = coordinator.observeGnss(fix(37.5, 127.0, 5_500L).copy(mock = true))
        assertEquals(0, mock.consecutiveCorruptJumpCount)
        assertFalse(coordinator.observeGnss(fix(37.6003, 127.0, 6_000L)).singleOutlierAgainstFreshEstimate)
        // An accepted fix ends the run; a later lone jump is a single outlier again.
        val accepted = coordinator.observeGnss(fix(37.5, 127.00002, 6_500L))
        assertEquals(GnssObservationDisposition.APPLIED, accepted.gnssUpdate?.disposition)
        assertEquals(0, accepted.consecutiveCorruptJumpCount)
        val lone = coordinator.observeGnss(fix(37.6, 127.0, 7_500L))
        assertEquals(1, lone.consecutiveCorruptJumpCount)
        assertTrue(lone.singleOutlierAgainstFreshEstimate)
        // A coordinator reset forgets the run.
        coordinator.reset()
        coordinator.observeGnss(fix(37.5, 127.0, 10_000L))
        coordinator.observeGnss(fix(37.5, 127.00001, 11_000L))
        assertEquals(1, coordinator.observeGnss(fix(37.6, 127.0, 12_000L)).consecutiveCorruptJumpCount)
    }

    private fun fix(latitude: Double, longitude: Double, at: Long) =
        GnssPositionObservation(latitude, longitude, 3.0, at)
}

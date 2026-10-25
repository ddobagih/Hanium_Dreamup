package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.*
import org.junit.Test

class PositioningCoordinatorDegradationGraceTest {
    @Test
    fun watchdogUsesActualEstimatorQualityAndKeepsLowFixOutOfAbsoluteArrival() {
        val coordinator = PositioningCoordinator()
        val initial = coordinator.observeGnss(fix(1_000L))
        assertEquals(PositionQuality.HIGH, initial.quality)
        val low = coordinator.evaluate(4_000L)
        assertTrue(low.quality in setOf(PositionQuality.LOW, PositionQuality.UNAVAILABLE))
        assertEquals(low.quality, low.confidence.quality)
        assertFalse(low.confidence.guidancePaused)
        assertFalse(low.absoluteArrivalConfirmable)
        assertNull(low.confidence.announcementRequest)
        val pending = coordinator.evaluate(6_999L)
        assertEquals(PositionQuality.UNAVAILABLE, pending.quality)
        assertFalse(pending.confidence.guidancePaused)
        assertEquals(6_999L, pending.confidence.degradationPendingSinceElapsedRealtimeMs)
        val unavailable = coordinator.evaluate(7_000L)
        assertEquals(PositionQuality.UNAVAILABLE, unavailable.quality)
        assertFalse(unavailable.confidence.guidancePaused)
        assertFalse(coordinator.evaluate(9_998L).confidence.guidancePaused)
        assertTrue(coordinator.evaluate(9_999L).confidence.guidancePaused)
        assertTrue(coordinator.evaluate(10_000L).confidence.guidancePaused)
    }

    @Test
    fun informativeFreshnessExpiryBypassesAnyUnfinishedQualityGrace() {
        val coordinator = PositioningCoordinator()
        coordinator.observeGnss(fix(1_000L))
        assertFalse(coordinator.evaluate(10_500L).confidence.guidancePaused)
        val expired = coordinator.evaluate(11_001L)
        assertTrue(expired.confidence.guidancePaused)
        assertEquals(PositionConfidenceFailureReason.STALE, expired.confidence.failureReason)
        assertFalse(expired.absoluteArrivalConfirmable)
    }

    @Test
    fun oldGenerationCallbackCannotReviveAnEpisodeAfterReset() {
        val coordinator = PositioningCoordinator()
        val oldGeneration = coordinator.currentInputGeneration()
        coordinator.observeGnss(fix(1_000L))
        coordinator.evaluate(4_000L)
        coordinator.evaluate(7_000L)
        val stopped = coordinator.evaluate(10_000L)
        val token = requireNotNull(stopped.confidence.announcementRequest).token
        repeat(3) { assertTrue(coordinator.isConfidenceAnnouncementCurrent(token)) }
        coordinator.reset()
        assertFalse(coordinator.isConfidenceAnnouncementCurrent(token))
        val oldCallback = coordinator.observeGnss(fix(8_000L), oldGeneration)
        assertEquals(PositionConfidenceTransition.FAIL_CLOSED, oldCallback.confidence.transition)
        assertFalse(coordinator.commitAnnouncementDelivered(token, 8_001L))
        val firstNewFix = coordinator.observeGnss(fix(8_002L))
        assertFalse(firstNewFix.confidence.guidancePaused)
        assertNull(firstNewFix.confidence.degradedEpisodeId)
        assertNull(firstNewFix.confidence.announcementRequest)
    }

    @Test
    fun mockAndInvalidCoordinatesRemainHardRejectedDuringGrace() {
        val coordinator = PositioningCoordinator()
        coordinator.observeGnss(fix(1_000L))
        coordinator.evaluate(4_000L)
        val mock = coordinator.observeGnss(fix(4_100L).copy(mock = true))
        val invalid = coordinator.observeGnss(fix(4_200L).copy(latitude = Double.NaN))
        assertEquals(GnssObservationDisposition.HARD_REJECTED, mock.gnssUpdate?.disposition)
        assertEquals(GnssObservationDisposition.HARD_REJECTED, invalid.gnssUpdate?.disposition)
        assertFalse(mock.singleOutlierAgainstFreshEstimate)
        assertFalse(invalid.singleOutlierAgainstFreshEstimate)
    }

    private fun fix(at: Long) = GnssPositionObservation(37.0, 127.0, 1.5, at)
}

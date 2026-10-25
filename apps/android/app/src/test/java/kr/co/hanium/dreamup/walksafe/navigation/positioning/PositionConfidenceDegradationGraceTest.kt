package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.*
import org.junit.Test

class PositionConfidenceDegradationGraceTest {
    @Test
    fun lowAndOrdinaryUnavailableWaitUntilExactlyThreeSecondsWithoutPromotingQuality() {
        listOf(PositionQuality.LOW, PositionQuality.UNAVAILABLE).forEach { quality ->
            val policy = usablePolicy()
            val first = policy.observe(quality, 2_000L)
            val waiting = policy.observe(quality, 4_999L)
            val confirmed = policy.observe(quality, 5_000L)

            if (quality == PositionQuality.LOW) {
                listOf(first, waiting, confirmed).forEach {
                    assertEquals(PositionQuality.LOW, it.quality)
                    assertFalse(it.guidancePaused)
                    assertNull(it.degradedEpisodeId)
                    assertNull(it.degradationPendingSinceElapsedRealtimeMs)
                    assertNull(it.announcementRequest)
                }
                return@forEach
            }
            listOf(first, waiting).forEach {
                assertEquals(quality, it.quality)
                assertFalse(it.guidancePaused)
                assertEquals(PositionConfidenceTransition.DEGRADATION_PENDING, it.transition)
                assertEquals(2_000L, it.degradationPendingSinceElapsedRealtimeMs)
                assertNull(it.degradedEpisodeId)
                assertNull(it.announcementRequest)
            }
            assertTrue(confirmed.guidancePaused)
            assertEquals(PositionConfidenceTransition.DEGRADED, confirmed.transition)
            assertNotNull(confirmed.degradedEpisodeId)
            assertNotNull(confirmed.announcementRequest)
            assertNull(confirmed.degradationPendingSinceElapsedRealtimeMs)
        }
    }

    @Test
    fun watchdogUsesTheSameBoundaryWithoutManufacturingAnotherGnssSample() {
        val policy = usablePolicy()
        policy.observe(PositionQuality.UNAVAILABLE, 2_000L)
        assertFalse(policy.evaluate(4_999L).guidancePaused)
        val confirmed = policy.evaluate(5_000L)
        assertTrue(confirmed.guidancePaused)
        assertEquals(PositionQuality.UNAVAILABLE, confirmed.quality)
        assertEquals(0, confirmed.consecutiveHighRecoverySamples)
    }

    @Test
    fun watchdogCanObserveEstimatorDegradationAndAUsableEstimateClearsItsPendingTimer() {
        val policy = usablePolicy()
        assertFalse(policy.evaluate(2_000L, PositionQuality.LOW).guidancePaused)
        assertFalse(policy.evaluate(4_999L, PositionQuality.MEDIUM).guidancePaused)
        val next = policy.evaluate(5_000L, PositionQuality.UNAVAILABLE)
        assertEquals(5_000L, next.degradationPendingSinceElapsedRealtimeMs)
        assertFalse(policy.evaluate(7_999L).guidancePaused)
        assertTrue(policy.evaluate(8_000L).guidancePaused)
    }

    @Test
    fun aOneSecondFlapDoesNotCreateAnEpisodeOrRecoveryAnnouncement() {
        val policy = usablePolicy()
        policy.observe(PositionQuality.LOW, 2_000L)
        val good = policy.observe(PositionQuality.MEDIUM, 3_000L)
        assertFalse(good.guidancePaused)
        assertNull(good.degradedEpisodeId)
        assertNull(good.announcementRequest)
        assertNull(good.degradationPendingSinceElapsedRealtimeMs)
        assertNotEquals(PositionConfidenceTransition.RECOVERED, good.transition)
        policy.observe(PositionQuality.UNAVAILABLE, 4_000L)
        assertFalse(policy.evaluate(6_999L).guidancePaused)
        assertTrue(policy.evaluate(7_000L).guidancePaused)
    }

    @Test
    fun lowAndUnavailableShareOneContinuousBadInterval() {
        val policy = usablePolicy()
        policy.observe(PositionQuality.LOW, 2_000L)
        policy.observe(PositionQuality.UNAVAILABLE, 3_000L)
        assertFalse(policy.observe(PositionQuality.LOW, 4_999L).guidancePaused)
        val next = policy.observe(PositionQuality.UNAVAILABLE, 5_000L)
        assertFalse(next.guidancePaused)
        assertEquals(5_000L, next.degradationPendingSinceElapsedRealtimeMs)
        assertFalse(policy.evaluate(7_999L).guidancePaused)
        assertTrue(policy.evaluate(8_000L).guidancePaused)
    }

    @Test
    fun invalidTimeAndOutOfOrderImmediatelyBypassTheGracePeriod() {
        listOf(-1L to 2_000L, 2_000L to 1_999L, 1_000L to 2_000L).forEach { (sample, now) ->
            val failed = usablePolicy().observe(PositionQuality.HIGH, sample, now)
            assertTrue(failed.guidancePaused)
            assertEquals(PositionConfidenceTransition.FAIL_CLOSED, failed.transition)
            assertNotNull(failed.failureReason)
            assertNull(failed.degradationPendingSinceElapsedRealtimeMs)
        }
    }

    @Test
    fun staleEvidenceStillExpiresAtTenSecondsWithoutAnAdditionalGracePeriod() {
        val policy = usablePolicy()
        assertFalse(policy.evaluate(11_000L).guidancePaused)
        val stale = policy.evaluate(11_001L)
        assertTrue(stale.guidancePaused)
        assertEquals(PositionConfidenceFailureReason.STALE, stale.failureReason)
        assertEquals(PositionConfidenceTransition.FAIL_CLOSED, stale.transition)
    }

    @Test
    fun nonInformativeGnssCannotExtendTheOriginalTenSecondFreshness() {
        val policy = usablePolicy()
        policy.observe(PositionQuality.MEDIUM, 10_000L, informativeGnss = false)
        assertFalse(policy.evaluate(11_000L).guidancePaused)
        val stale = policy.observe(PositionQuality.MEDIUM, 11_001L, informativeGnss = false)
        assertEquals(PositionConfidenceFailureReason.STALE, stale.failureReason)
        assertTrue(stale.guidancePaused)
    }

    @Test
    fun initialLowNeverAnnouncesRecoveryEvenWhenItsWaitNoticeWasDelivered() {
        val policy = PositionConfidencePolicy()
        val waiting = policy.observe(PositionQuality.UNAVAILABLE, 1_000L)
        assertTrue(waiting.guidancePaused)
        assertNull(waiting.degradedEpisodeId)
        assertNull(waiting.announcementRequest)
        val low = policy.evaluate(4_000L)
        assertTrue(policy.commitAnnouncementDelivered(requireNotNull(low.announcementRequest).token, 4_100L))
        assertTrue(policy.observe(PositionQuality.MEDIUM, 5_000L).guidancePaused)
        val acquired = policy.observe(PositionQuality.MEDIUM, 6_000L)
        assertFalse(acquired.guidancePaused)
        assertNull(acquired.announcementRequest)
    }

    @Test
    fun initialShortBadFixThenFirstUsableFixDoesNotAnnounceRecovery() {
        val policy = PositionConfidencePolicy()
        policy.observe(PositionQuality.UNAVAILABLE, 1_000L)
        val acquired = policy.observe(PositionQuality.MEDIUM, 2_000L)
        assertFalse(acquired.guidancePaused)
        assertNull(acquired.degradedEpisodeId)
        assertNull(acquired.announcementRequest)
    }

    @Test
    fun establishedGuidanceOnlyAnnouncesRecoveryAfterItsStopNoticeWasDelivered() {
        listOf(false, true).forEach { delivered ->
            val policy = usablePolicy()
            policy.observe(PositionQuality.UNAVAILABLE, 2_000L)
            val stopped = policy.evaluate(5_000L)
            if (delivered) assertTrue(policy.commitAnnouncementDelivered(
                requireNotNull(stopped.announcementRequest).token, 5_001L,
            ))
            policy.observe(PositionQuality.MEDIUM, 6_000L)
            val recovered = policy.observe(PositionQuality.MEDIUM, 7_000L)
            assertFalse(recovered.guidancePaused)
            assertEquals(PositionConfidenceTransition.RECOVERED, recovered.transition)
            if (delivered) {
                assertEquals(PositionConfidenceAnnouncementKind.RECOVERED, recovered.announcementRequest?.kind)
            } else {
                assertNull(recovered.announcementRequest)
                assertFalse(policy.commitAnnouncementDelivered(requireNotNull(stopped.announcementRequest).token, 7_001L))
            }
        }
    }

    @Test
    fun recoveryNeedsNewInformativeGnssEvenAfterThreeSecondDegradation() {
        val policy = usablePolicy()
        policy.observe(PositionQuality.UNAVAILABLE, 2_000L)
        policy.evaluate(5_000L)
        assertTrue(policy.observe(PositionQuality.MEDIUM, 6_000L, informativeGnss = false).guidancePaused)
        assertTrue(policy.observe(PositionQuality.MEDIUM, 7_000L).guidancePaused)
        assertTrue(policy.observeMotion(PositionQuality.HIGH, 7_500L).guidancePaused)
        assertTrue(policy.evaluate(7_900L).guidancePaused)
        assertFalse(policy.observe(PositionQuality.MEDIUM, 8_000L).guidancePaused)
    }

    @Test
    fun newBadInputRetiresAnUndeliveredRecoveryNoticeDuringGrace() {
        val policy = usablePolicy()
        policy.observe(PositionQuality.UNAVAILABLE, 2_000L)
        val stopped = policy.evaluate(5_000L)
        policy.commitAnnouncementDelivered(requireNotNull(stopped.announcementRequest).token, 5_001L)
        policy.observe(PositionQuality.MEDIUM, 6_000L)
        val recovered = policy.observe(PositionQuality.MEDIUM, 7_000L)
        val pending = policy.observe(PositionQuality.UNAVAILABLE, 8_000L)
        assertNull(pending.announcementRequest)
        assertFalse(policy.commitAnnouncementDelivered(requireNotNull(recovered.announcementRequest).token, 8_001L))
    }

    @Test
    fun resetDiscardsPendingDurationAndPriorUsableHistory() {
        val policy = usablePolicy()
        policy.observe(PositionQuality.UNAVAILABLE, 2_000L)
        policy.reset()
        val freshWaiting = policy.observe(PositionQuality.UNAVAILABLE, 4_999L)
        assertTrue(freshWaiting.guidancePaused)
        assertNull(freshWaiting.degradedEpisodeId)
        assertEquals(4_999L, freshWaiting.degradationPendingSinceElapsedRealtimeMs)
        assertNull(policy.evaluate(7_998L).degradedEpisodeId)
        assertNotNull(policy.evaluate(7_999L).degradedEpisodeId)
    }

    private fun usablePolicy() = PositionConfidencePolicy().also {
        assertFalse(it.observe(PositionQuality.HIGH, 1_000L).guidancePaused)
    }
}

package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionConfidencePolicyTest {
    @Test
    fun lowImmediatelyStartsDegradedEpisode() {
        val policy = PositionConfidencePolicy()
        policy.observe(PositionQuality.HIGH, sampleAtElapsedRealtimeMs = 1_000L)

        val decision = policy.observe(PositionQuality.LOW, sampleAtElapsedRealtimeMs = 2_000L)

        assertEquals(PositionConfidenceTransition.DEGRADED, decision.transition)
        assertTrue(decision.guidancePaused)
        assertEquals(PositionConfidenceAnnouncementKind.LOW, decision.announcementRequest?.kind)
        assertNotNull(decision.degradedEpisodeId)
    }

    @Test
    fun unavailableImmediatelyStartsDegradedEpisode() {
        val decision = PositionConfidencePolicy().observe(
            PositionQuality.UNAVAILABLE,
            sampleAtElapsedRealtimeMs = 1_000L,
        )

        assertEquals(PositionConfidenceTransition.DEGRADED, decision.transition)
        assertTrue(decision.guidancePaused)
        assertEquals(PositionConfidenceAnnouncementKind.UNAVAILABLE, decision.announcementRequest?.kind)
    }

    @Test
    fun recoveryRequiresTwoConsecutiveHighSamplesWithinFiveSeconds() {
        val policy = PositionConfidencePolicy()
        policy.observe(PositionQuality.LOW, sampleAtElapsedRealtimeMs = 1_000L)

        val firstHigh = policy.observe(PositionQuality.HIGH, sampleAtElapsedRealtimeMs = 2_000L)
        val tooLateHigh = policy.observe(PositionQuality.HIGH, sampleAtElapsedRealtimeMs = 7_001L)
        val confirmed = policy.observe(PositionQuality.HIGH, sampleAtElapsedRealtimeMs = 8_000L)

        assertEquals(PositionConfidenceTransition.RECOVERY_PENDING, firstHigh.transition)
        assertTrue(firstHigh.guidancePaused)
        assertEquals(1, tooLateHigh.consecutiveHighRecoverySamples)
        assertTrue(tooLateHigh.guidancePaused)
        assertEquals(PositionConfidenceTransition.RECOVERED, confirmed.transition)
        assertFalse(confirmed.guidancePaused)
        assertEquals(PositionConfidenceAnnouncementKind.RECOVERED, confirmed.announcementRequest?.kind)
    }

    @Test
    fun mediumBreaksConsecutiveHighRecoveryEvidence() {
        val policy = PositionConfidencePolicy()
        policy.observe(PositionQuality.LOW, sampleAtElapsedRealtimeMs = 1_000L)
        policy.observe(PositionQuality.HIGH, sampleAtElapsedRealtimeMs = 2_000L)

        val medium = policy.observe(PositionQuality.MEDIUM, sampleAtElapsedRealtimeMs = 3_000L)
        val highAgain = policy.observe(PositionQuality.HIGH, sampleAtElapsedRealtimeMs = 4_000L)

        assertEquals(0, medium.consecutiveHighRecoverySamples)
        assertTrue(medium.guidancePaused)
        assertEquals(PositionConfidenceTransition.RECOVERY_PENDING, highAgain.transition)
        assertEquals(1, highAgain.consecutiveHighRecoverySamples)
    }

    @Test
    fun announcementRemainsPendingUntilDeliveryIsCommitted() {
        val policy = PositionConfidencePolicy()
        val first = policy.observe(PositionQuality.LOW, sampleAtElapsedRealtimeMs = 1_000L)
        val request = requireNotNull(first.announcementRequest)

        val retry = policy.observe(PositionQuality.LOW, sampleAtElapsedRealtimeMs = 2_000L)

        assertEquals(request.token, retry.announcementRequest?.token)
        assertFalse(policy.commitAnnouncementDelivered(request.token + 1L, 2_000L))
        assertFalse(policy.commitAnnouncementDelivered(request.token, 999L))
        assertTrue(policy.commitAnnouncementDelivered(request.token, 2_000L))
        assertNull(policy.observe(PositionQuality.LOW, sampleAtElapsedRealtimeMs = 3_000L).announcementRequest)
    }

    @Test
    fun sameEpisodeCanRepeatOnlyAfterThirtySecondsFromDelivery() {
        val policy = PositionConfidencePolicy()
        val first = requireNotNull(
            policy.observe(PositionQuality.LOW, sampleAtElapsedRealtimeMs = 1_000L).announcementRequest,
        )
        assertTrue(policy.commitAnnouncementDelivered(first.token, 2_000L))

        val beforeLimit = policy.observe(PositionQuality.LOW, sampleAtElapsedRealtimeMs = 31_999L)
        val atLimit = policy.observe(PositionQuality.LOW, sampleAtElapsedRealtimeMs = 32_000L)

        assertNull(beforeLimit.announcementRequest)
        assertEquals(PositionConfidenceAnnouncementKind.LOW, atLimit.announcementRequest?.kind)
        assertTrue(atLimit.announcementRequest?.repeated == true)
    }

    @Test
    fun lowToUnavailableEscalatesOnlyOncePerEpisode() {
        val policy = PositionConfidencePolicy()
        val low = requireNotNull(
            policy.observe(PositionQuality.LOW, sampleAtElapsedRealtimeMs = 1_000L).announcementRequest,
        )
        assertTrue(policy.commitAnnouncementDelivered(low.token, 1_000L))

        val escalation = policy.observe(PositionQuality.UNAVAILABLE, sampleAtElapsedRealtimeMs = 2_000L)
        val unavailable = requireNotNull(escalation.announcementRequest)
        assertEquals(PositionConfidenceTransition.ESCALATED, escalation.transition)
        assertEquals(PositionConfidenceAnnouncementKind.UNAVAILABLE, unavailable.kind)
        assertTrue(policy.commitAnnouncementDelivered(unavailable.token, 2_000L))

        policy.observe(PositionQuality.LOW, sampleAtElapsedRealtimeMs = 3_000L)
        val unavailableAgain = policy.observe(PositionQuality.UNAVAILABLE, sampleAtElapsedRealtimeMs = 4_000L)

        assertNull(unavailableAgain.announcementRequest)
    }

    @Test
    fun staleSampleFailsClosed() {
        val policy = PositionConfidencePolicy()
        policy.observe(PositionQuality.HIGH, sampleAtElapsedRealtimeMs = 1_000L)

        val stale = policy.observe(
            PositionQuality.HIGH,
            sampleAtElapsedRealtimeMs = 2_000L,
            nowElapsedRealtimeMs = 12_001L,
        )

        assertEquals(PositionQuality.UNAVAILABLE, stale.quality)
        assertEquals(PositionConfidenceTransition.FAIL_CLOSED, stale.transition)
        assertEquals(PositionConfidenceFailureReason.STALE, stale.failureReason)
        assertTrue(stale.guidancePaused)
    }

    @Test
    fun outOfOrderSampleFailsClosedAndNeedsConfirmedRecovery() {
        val policy = PositionConfidencePolicy()
        policy.observe(PositionQuality.HIGH, sampleAtElapsedRealtimeMs = 2_000L)

        val outOfOrder = policy.observe(
            PositionQuality.HIGH,
            sampleAtElapsedRealtimeMs = 1_500L,
            nowElapsedRealtimeMs = 3_000L,
        )
        val firstRecovery = policy.observe(PositionQuality.HIGH, sampleAtElapsedRealtimeMs = 4_000L)
        val recovered = policy.observe(PositionQuality.HIGH, sampleAtElapsedRealtimeMs = 5_000L)

        assertEquals(PositionConfidenceFailureReason.OUT_OF_ORDER, outOfOrder.failureReason)
        assertEquals(PositionConfidenceTransition.FAIL_CLOSED, outOfOrder.transition)
        assertTrue(outOfOrder.guidancePaused)
        assertTrue(firstRecovery.guidancePaused)
        assertFalse(recovered.guidancePaused)
    }

    @Test
    fun invalidTimeFailsClosed() {
        val decision = PositionConfidencePolicy().observe(
            quality = PositionQuality.HIGH,
            sampleAtElapsedRealtimeMs = 2_000L,
            nowElapsedRealtimeMs = 1_999L,
        )

        assertEquals(PositionQuality.UNAVAILABLE, decision.quality)
        assertEquals(PositionConfidenceFailureReason.INVALID_TIME, decision.failureReason)
        assertTrue(decision.guidancePaused)
    }

    @Test
    fun resetClearsStateAndInvalidatesOldAnnouncementWithoutReusingToken() {
        val policy = PositionConfidencePolicy()
        val oldRequest = requireNotNull(
            policy.observe(PositionQuality.LOW, sampleAtElapsedRealtimeMs = 1_000L).announcementRequest,
        )

        policy.reset()
        val high = policy.observe(PositionQuality.HIGH, sampleAtElapsedRealtimeMs = 1_000L)
        val newRequest = requireNotNull(
            policy.observe(PositionQuality.LOW, sampleAtElapsedRealtimeMs = 2_000L).announcementRequest,
        )

        assertEquals(PositionConfidenceTransition.INITIALIZED, high.transition)
        assertFalse(high.guidancePaused)
        assertFalse(policy.commitAnnouncementDelivered(oldRequest.token, 2_000L))
        assertNotEquals(oldRequest.token, newRequest.token)
        assertNotEquals(oldRequest.episodeId, newRequest.episodeId)
    }
}

package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recovery/acknowledgement tests isolate the confirmed-episode state with zero delay.
 * Production's default 3-second onset is exercised by PositionConfidenceDegradationGraceTest.
 */
class PositionConfidencePolicyTest {
    @Test
    fun zeroDelayConfigurationImmediatelyConfirmsLowEpisode() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        policy.observe(PositionQuality.HIGH, sampleAtElapsedRealtimeMs = 1_000L)

        val decision = policy.observe(PositionQuality.LOW, sampleAtElapsedRealtimeMs = 2_000L)

        assertEquals(PositionConfidenceTransition.QUALITY_CHANGED, decision.transition)
        assertFalse(decision.guidancePaused)
        assertEquals(PositionQuality.LOW, decision.quality)
        assertNull(decision.announcementRequest)
        assertNull(decision.degradedEpisodeId)
    }

    @Test
    fun zeroDelayConfigurationImmediatelyConfirmsUnavailableEpisode() {
        val decision = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L)).observe(
            PositionQuality.UNAVAILABLE,
            sampleAtElapsedRealtimeMs = 1_000L,
        )

        assertEquals(PositionConfidenceTransition.DEGRADED, decision.transition)
        assertTrue(decision.guidancePaused)
        assertEquals(PositionConfidenceAnnouncementKind.UNAVAILABLE, decision.announcementRequest?.kind)
    }

    @Test
    fun recoveryRequiresTwoConsecutiveHighSamplesWithinFiveSeconds() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L,
            maximumHighRecoverySampleGapMs = 5_000L))
        policy.observe(PositionQuality.UNAVAILABLE, sampleAtElapsedRealtimeMs = 1_000L)

        val firstHigh = policy.observe(PositionQuality.HIGH, sampleAtElapsedRealtimeMs = 2_000L)
        val tooLateHigh = policy.observe(PositionQuality.HIGH, sampleAtElapsedRealtimeMs = 7_001L)
        val confirmed = policy.observe(PositionQuality.HIGH, sampleAtElapsedRealtimeMs = 8_000L)

        assertEquals(PositionConfidenceTransition.RECOVERY_PENDING, firstHigh.transition)
        assertTrue(firstHigh.guidancePaused)
        assertEquals(1, tooLateHigh.consecutiveHighRecoverySamples)
        assertTrue(tooLateHigh.guidancePaused)
        assertEquals(PositionConfidenceTransition.RECOVERED, confirmed.transition)
        assertFalse(confirmed.guidancePaused)
        assertNull(confirmed.announcementRequest) // No previous usable guidance or delivered interruption.
    }

    @Test
    fun mediumBreaksConsecutiveHighRecoveryEvidenceAfterUnavailable() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        assertFalse(policy.observe(PositionQuality.MEDIUM, sampleAtElapsedRealtimeMs = 0L).guidancePaused)
        policy.observe(PositionQuality.UNAVAILABLE, sampleAtElapsedRealtimeMs = 1_000L)
        policy.observe(PositionQuality.HIGH, sampleAtElapsedRealtimeMs = 2_000L)

        val medium = policy.observe(PositionQuality.MEDIUM, sampleAtElapsedRealtimeMs = 3_000L)
        val highAgain = policy.observe(PositionQuality.HIGH, sampleAtElapsedRealtimeMs = 4_000L)

        assertEquals(0, medium.consecutiveHighRecoverySamples)
        assertFalse(medium.guidancePaused)
        assertEquals(PositionQuality.MEDIUM, medium.quality)
        assertEquals(PositionConfidenceTransition.RECOVERED, medium.transition)
        assertEquals(PositionConfidenceTransition.QUALITY_CHANGED, highAgain.transition)
        assertEquals(0, highAgain.consecutiveHighRecoverySamples)
        assertFalse(highAgain.guidancePaused)
    }

    @Test
    fun announcementRemainsPendingUntilDeliveryIsCommitted() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        val first = policy.observe(PositionQuality.UNAVAILABLE, sampleAtElapsedRealtimeMs = 1_000L)
        val request = requireNotNull(first.announcementRequest)

        val retry = policy.observe(PositionQuality.UNAVAILABLE, sampleAtElapsedRealtimeMs = 2_000L)

        assertEquals(request.token, retry.announcementRequest?.token)
        assertFalse(policy.commitAnnouncementDelivered(request.token + 1L, 2_000L))
        assertFalse(policy.commitAnnouncementDelivered(request.token, 999L))
        assertTrue(policy.commitAnnouncementDelivered(request.token, 2_000L))
        assertNull(policy.observe(PositionQuality.UNAVAILABLE, sampleAtElapsedRealtimeMs = 3_000L).announcementRequest)
    }

    @Test
    fun sameEpisodeCanRepeatOnlyAfterThirtySecondsFromDelivery() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        val first = requireNotNull(
            policy.observe(PositionQuality.UNAVAILABLE, sampleAtElapsedRealtimeMs = 1_000L).announcementRequest,
        )
        assertTrue(policy.commitAnnouncementDelivered(first.token, 2_000L))

        val beforeLimit = policy.observe(PositionQuality.UNAVAILABLE, sampleAtElapsedRealtimeMs = 31_999L)
        val atLimit = policy.observe(PositionQuality.UNAVAILABLE, sampleAtElapsedRealtimeMs = 32_000L)

        assertNull(beforeLimit.announcementRequest)
        assertEquals(PositionConfidenceAnnouncementKind.UNAVAILABLE, atLimit.announcementRequest?.kind)
        assertTrue(atLimit.announcementRequest?.repeated == true)
    }

    @Test
    fun lowToUnavailableEscalatesOnlyOncePerEpisode() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        val low = policy.observe(PositionQuality.LOW, sampleAtElapsedRealtimeMs = 1_000L)
        assertEquals(PositionQuality.LOW, low.quality)
        assertFalse(low.guidancePaused)
        assertNull(low.announcementRequest)

        val escalation = policy.observe(PositionQuality.UNAVAILABLE, sampleAtElapsedRealtimeMs = 2_000L)
        val unavailable = requireNotNull(escalation.announcementRequest)
        assertEquals(PositionConfidenceTransition.DEGRADED, escalation.transition)
        assertEquals(PositionConfidenceAnnouncementKind.UNAVAILABLE, unavailable.kind)
        assertTrue(policy.commitAnnouncementDelivered(unavailable.token, 2_000L))

        policy.observe(PositionQuality.LOW, sampleAtElapsedRealtimeMs = 3_000L)
        val unavailableAgain = policy.observe(PositionQuality.UNAVAILABLE, sampleAtElapsedRealtimeMs = 4_000L)

        assertNull(unavailableAgain.announcementRequest)
    }

    @Test
    fun staleSampleFailsClosed() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
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
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
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
        val decision = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L)).observe(
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
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        val oldRequest = requireNotNull(
            policy.observe(PositionQuality.UNAVAILABLE, sampleAtElapsedRealtimeMs = 1_000L).announcementRequest,
        )

        policy.reset()
        val high = policy.observe(PositionQuality.HIGH, sampleAtElapsedRealtimeMs = 1_000L)
        val newRequest = requireNotNull(
            policy.observe(PositionQuality.UNAVAILABLE, sampleAtElapsedRealtimeMs = 2_000L).announcementRequest,
        )

        assertEquals(PositionConfidenceTransition.INITIALIZED, high.transition)
        assertFalse(high.guidancePaused)
        assertFalse(policy.commitAnnouncementDelivered(oldRequest.token, 2_000L))
        assertNotEquals(oldRequest.token, newRequest.token)
        assertNotEquals(oldRequest.episodeId, newRequest.episodeId)
    }
}

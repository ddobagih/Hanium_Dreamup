package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recovery/acknowledgement tests isolate the confirmed-episode state with zero delay.
 * Production's default 3-second onset is exercised by PositionConfidenceDegradationGraceTest.
 */
class PositionConfidenceInitialAcquisitionTest {
    @Test
    fun repeatedWatchdogsBeforeAnyFixKeepOutputsPausedWithoutADegradedEpisode() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))

        listOf(0L, 500L, 15_000L, 30_000L).forEach { nowMs ->
            val waiting = policy.evaluate(nowMs)

            assertEquals(PositionQuality.UNAVAILABLE, waiting.quality)
            assertEquals(PositionConfidenceTransition.INITIALIZED, waiting.transition)
            assertTrue(waiting.guidancePaused)
            assertNull(waiting.previousQuality)
            assertNull(waiting.degradedEpisodeId)
            assertNull(waiting.announcementRequest)
            assertNull(waiting.failureReason)
        }
    }

    @Test
    fun firstMediumHasTheSameAdmissionWithOrWithoutInitialWatchdogs() {
        val direct = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L)).observe(PositionQuality.MEDIUM, 30_000L)
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        listOf(500L, 1_000L, 29_999L).forEach(policy::evaluate)

        val afterWaiting = policy.observe(PositionQuality.MEDIUM, 30_000L)

        assertEquals(direct, afterWaiting)
        assertFalse(afterWaiting.guidancePaused)
        assertNull(afterWaiting.degradedEpisodeId)
        assertNull(afterWaiting.announcementRequest)
    }

    @Test
    fun firstHighHasTheSameAdmissionWithOrWithoutInitialWatchdogs() {
        val direct = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L)).observe(PositionQuality.HIGH, 1_000L)
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        policy.evaluate(500L)

        val afterWaiting = policy.observe(PositionQuality.HIGH, 1_000L)

        assertEquals(direct, afterWaiting)
        assertFalse(afterWaiting.guidancePaused)
        assertEquals(PositionConfidenceTransition.INITIALIZED, afterWaiting.transition)
    }

    @Test
    fun aFreshFixMeasuredBeforeTheInitialWatchdogIsStillAcceptedOnReceipt() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        policy.evaluate(500L)

        val firstFix = policy.observe(PositionQuality.MEDIUM, 250L, 1_000L)

        assertFalse(firstFix.guidancePaused)
        assertNull(firstFix.failureReason)
        assertNull(firstFix.degradedEpisodeId)
    }

    @Test
    fun resetReturnsToInitialAcquisitionAndRetiresTheOldAnnouncement() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        val previous = requireNotNull(policy.observe(PositionQuality.UNAVAILABLE, 1_000L).announcementRequest)

        policy.reset()
        val waiting = policy.evaluate(2_000L)
        val firstFix = policy.observe(PositionQuality.MEDIUM, 2_001L)

        assertTrue(waiting.guidancePaused)
        assertNull(waiting.degradedEpisodeId)
        assertNull(waiting.announcementRequest)
        assertFalse(policy.commitAnnouncementDelivered(previous.token, 2_002L))
        assertFalse(firstFix.guidancePaused)
    }

    @Test
    fun anActualFirstLowFixStillStartsADegradedEpisode() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        policy.evaluate(500L)

        val low = policy.observe(PositionQuality.LOW, 1_000L)

        assertFalse(low.guidancePaused)
        assertEquals(PositionConfidenceTransition.INITIALIZED, low.transition)
        assertEquals(PositionQuality.LOW, low.quality)
        assertNull(low.degradedEpisodeId)
        assertNull(low.announcementRequest)
    }

    @Test
    fun anActualFirstUnavailableFixStillStartsADegradedEpisode() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        policy.evaluate(500L)

        val unavailable = policy.observe(PositionQuality.UNAVAILABLE, 1_000L)

        assertTrue(unavailable.guidancePaused)
        assertNotNull(unavailable.degradedEpisodeId)
        assertEquals(PositionConfidenceAnnouncementKind.UNAVAILABLE, unavailable.announcementRequest?.kind)
    }

    @Test
    fun aReceivedFixThatExpiresStillRequiresTwoFreshHighGnssSamples() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        policy.evaluate(500L)
        policy.observe(PositionQuality.MEDIUM, 1_000L)

        assertFalse(policy.evaluate(11_000L).guidancePaused)
        val stale = policy.evaluate(11_001L)
        val firstHigh = policy.observe(PositionQuality.HIGH, 12_000L)
        val recovered = policy.observe(PositionQuality.HIGH, 13_000L)

        assertTrue(stale.guidancePaused)
        assertEquals(PositionConfidenceFailureReason.STALE, stale.failureReason)
        assertNotNull(stale.degradedEpisodeId)
        assertTrue(firstHigh.guidancePaused)
        assertEquals(1, firstHigh.consecutiveHighRecoverySamples)
        assertFalse(recovered.guidancePaused)
        assertEquals(PositionConfidenceTransition.RECOVERED, recovered.transition)
    }

    @Test
    fun nonInformativeHighDoesNotCountTowardRecoveryFromRealDegradation() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        policy.evaluate(500L)
        policy.observe(PositionQuality.UNAVAILABLE, 1_000L)

        val weakHigh = policy.observe(PositionQuality.HIGH, 2_000L, 2_000L, informativeGnss = false)
        val firstHigh = policy.observe(PositionQuality.HIGH, 3_000L)
        val recovered = policy.observe(PositionQuality.HIGH, 4_000L)

        assertTrue(weakHigh.guidancePaused)
        assertEquals(0, weakHigh.consecutiveHighRecoverySamples)
        assertTrue(firstHigh.guidancePaused)
        assertFalse(recovered.guidancePaused)
    }

    @Test
    fun anInvalidFirstGnssTimestampStillDegradesAndNeedsConfirmedRecovery() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        policy.evaluate(500L)

        val invalid = policy.observe(PositionQuality.HIGH, 2_000L, 1_999L)
        val repeatedWatchdog = policy.evaluate(2_000L)
        val firstHigh = policy.observe(PositionQuality.HIGH, 3_000L)
        val recovered = policy.observe(PositionQuality.HIGH, 4_000L)

        assertEquals(PositionConfidenceFailureReason.INVALID_TIME, invalid.failureReason)
        assertTrue(invalid.guidancePaused)
        assertNotNull(invalid.degradedEpisodeId)
        assertEquals(invalid.degradedEpisodeId, repeatedWatchdog.degradedEpisodeId)
        assertTrue(firstHigh.guidancePaused)
        assertFalse(recovered.guidancePaused)
    }

    @Test
    fun invalidOrBackwardInitialWatchdogTimeRemainsFailClosed() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        policy.evaluate(1_000L)

        listOf(999L, -1L).forEach { nowMs ->
            val invalid = policy.evaluate(nowMs)

            assertTrue(invalid.guidancePaused)
            assertEquals(PositionConfidenceTransition.FAIL_CLOSED, invalid.transition)
            assertEquals(PositionConfidenceFailureReason.INVALID_TIME, invalid.failureReason)
        }
        val invalidReceipt = policy.observe(PositionQuality.MEDIUM, 800L, 999L)
        assertEquals(PositionConfidenceFailureReason.INVALID_TIME, invalidReceipt.failureReason)
        assertNotNull(invalidReceipt.degradedEpisodeId)
    }

    @Test
    fun motionDegradationBeforeTheFirstGnssFixIsNotTreatedAsInitialAcquisition() {
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(degradationConfirmationDurationMs = 0L))
        policy.evaluate(500L)

        val motionLow = policy.observeMotion(PositionQuality.UNAVAILABLE, 600L)
        val stillDegraded = policy.evaluate(700L)
        val firstHigh = policy.observe(PositionQuality.HIGH, 800L)
        val recovered = policy.observe(PositionQuality.HIGH, 900L)

        assertNotNull(motionLow.degradedEpisodeId)
        assertEquals(motionLow.degradedEpisodeId, stillDegraded.degradedEpisodeId)
        assertTrue(stillDegraded.guidancePaused)
        assertNotNull(stillDegraded.announcementRequest)
        assertTrue(firstHigh.guidancePaused)
        assertFalse(recovered.guidancePaused)
    }
}
